package com.jjricks.volt;

import java.util.HashMap;
import java.util.Map;

/**
 * Battery alerts for scooters without a native charge limit (a GT3 Max owner asked first). Pure and
 * Android-free: fed the fresh charging state and battery percentage with both clocks, it decides when
 * a charge alert or a low-battery alert is due, and whether this charge is worth a bounded background
 * hold. It never sends a scooter command and never alerts on a setting change.
 *
 * A charge session begins at the first fresh charging reading. It ends when charging is seen off for
 * more than STOP_MS (a charger that cuts out and resumes keeps its session), when the percentage drops
 * two points below the session's best (a ride or a new charge), or when the scooter was last seen more
 * than SESSION_GAP_MS ago. The persisted State carries the session across reconnects and restarts, so
 * one charge alerts once, and a charge first seen at or above its level never alerts.
 */
public final class ChargeAlertPolicy {
    public static final int CHARGE_MIN = 50, CHARGE_MAX = 100, CHARGE_DEFAULT = 80, LOW_MIN = 5, LOW_MAX = 50, LOW_DEFAULT = 20, STEP = 5;
    /** Full also counts when charging stops at 99%: chargers often end there. */
    public static final int FULL_FROM = 99;
    /** Low re-arms only above its level plus this, so a reading hovering at the level alerts once. */
    public static final int LOW_REARM = 5;
    /** Background reads arrive about every 15 s; two missed reads still count as fresh. */
    public static final long FRESH_MS = 45_000L, STOP_MS = 3 * 60_000L, RECONNECT_MS = 2 * 60_000L;
    public static final long HOLD_MS = 12 * 60 * 60_000L, SESSION_GAP_MS = HOLD_MS;
    private static final long SEEN_STEP_MS = 60_000L;

    public enum Event { NONE, CHARGE, FULL, LOW, PAUSED, TIME_LIMIT }
    /** CONNECTED: this scooter is authenticated and ready; RECONNECTING: a recoverable loss being retried. */
    public enum Link { CONNECTED, RECONNECTING, LOST }

    /** One scooter's choices. Everything is off by default, and each delivery is its own opt-in. */
    public static final class Settings {
        public static final Settings OFF = new Settings(false, CHARGE_DEFAULT, false, LOW_DEFAULT, false, false);
        public final boolean charge, low, notify, alarm;
        public final int chargeAt, lowBelow;
        public Settings(boolean charge, int chargeAt, boolean low, int lowBelow, boolean notify, boolean alarm) {
            this.charge = charge; this.low = low; this.notify = notify; this.alarm = alarm;
            this.chargeAt = snap(chargeAt, CHARGE_MIN, CHARGE_MAX); this.lowBelow = snap(lowBelow, LOW_MIN, LOW_MAX);
        }
        public Settings charge(boolean on, int at) { return new Settings(on, at, low, lowBelow, notify, alarm); }
        public Settings low(boolean on, int below) { return new Settings(charge, chargeAt, on, below, notify, alarm); }
        public Settings delivery(boolean notify, boolean alarm) { return new Settings(charge, chargeAt, low, lowBelow, notify, alarm); }
        /** An alert needs a way to reach the owner: the alarm, or a notification Android will show. */
        public boolean delivers(boolean canNotify) { return alarm || notify && canNotify; }
        public boolean any() { return charge || low; }
        public String encode() {
            return "charge=" + bit(charge) + ";at=" + chargeAt + ";low=" + bit(low) + ";below=" + lowBelow + ";notify=" + bit(notify) + ";alarm=" + bit(alarm);
        }
        /** Unreadable text is everything off: an alert never turns itself on. */
        public static Settings decode(String text) {
            Map<String, String> f = fields(text);
            return new Settings("1".equals(f.get("charge")), number(f.get("at"), CHARGE_DEFAULT), "1".equals(f.get("low")),
                    number(f.get("below"), LOW_DEFAULT), "1".equals(f.get("notify")), "1".equals(f.get("alarm")));
        }
        @Override public boolean equals(Object o) { return o instanceof Settings && encode().equals(((Settings) o).encode()); }
        @Override public int hashCode() { return encode().hashCode(); }
    }

    /** What persists between connections: the current charge session and the low-battery latch. */
    public static final class State {
        boolean session, lowLatched;
        /** firedAt: the highest level alerted this session (100 after Stop watching); 0 for none. */
        int startPct, bestPct, firedAt;
        long seenWall;
        public String encode() {
            return "session=" + bit(session) + ";start=" + startPct + ";best=" + bestPct + ";fired=" + firedAt + ";seen=" + seenWall + ";low=" + bit(lowLatched);
        }
        public static State decode(String text) {
            Map<String, String> f = fields(text);State s = new State();
            s.session = "1".equals(f.get("session"));s.lowLatched = "1".equals(f.get("low"));
            s.startPct = clamp(number(f.get("start"), 0));s.bestPct = clamp(number(f.get("best"), 0));s.firedAt = clamp(number(f.get("fired"), 0));
            try { s.seenWall = Long.parseLong(f.get("seen")); } catch (RuntimeException unreadable) { s.session = false; }
            if (s.session && s.startPct > s.bestPct) s.session = false;
            return s;
        }
    }

    private Settings settings;
    private final State state;
    private boolean dirty, watching, capped, paused;
    private long stoppedSince, watchSince, lostSince;
    private Integer lastPct;
    private boolean lastFresh;

    public ChargeAlertPolicy(Settings settings, State state) {
        this.settings = settings == null ? Settings.OFF : settings;this.state = state == null ? new State() : state;
    }

    public Settings settings() { return settings; }
    public State state() { return state; }
    /** True once after the persisted State changed; the caller saves it then. */
    public boolean takeDirty() { boolean was = dirty; dirty = false; return was; }
    /** A charge alert is armed for this charge: the caller may hold the connection (bounded by HOLD_MS). */
    public boolean watching() { return watching; }
    /** When the current watch must end at the latest, on the monotonic clock; 0 when not watching. */
    public long watchDeadline() { return watching ? watchSince + HOLD_MS : 0; }
    /** The last battery percentage seen (fresh or not) and whether the latest step had it fresh. */
    public Integer percent() { return lastPct; }
    public boolean percentFresh() { return lastFresh; }

    /**
     * Alerts fire on reaching a level, never on choosing one: a charge level this session already passed,
     * or a low level the battery is already under, counts as reached.
     */
    public void settings(Settings next) {
        if (next == null || next.equals(settings)) return;
        if (next.charge && (!settings.charge || next.chargeAt != settings.chargeAt) && state.session && state.bestPct >= next.chargeAt && state.firedAt < next.chargeAt) {
            state.firedAt = next.chargeAt;dirty = true;
        }
        if (next.low && (!settings.low || next.lowBelow != settings.lowBelow)) {
            boolean under = lastPct != null && lastPct < next.lowBelow;
            if (under != state.lowLatched) { state.lowLatched = under;dirty = true; }
        }
        settings = next;
        if (!next.charge || !next.notify && !next.alarm) idle();
    }
    /** Stop watching: no charge alert for the rest of this charge; the next charge alerts as usual. */
    public void skip() { if (state.session && state.firedAt < 100) { state.firedAt = 100;dirty = true; } watching = false;watchSince = 0; }
    /** The owner opened Volt: a paused or timed-out watch may hold again. */
    public void resume() { capped = paused = false; }
    /** Not evaluated (alerts off, a model with a charge limit, sample data): nothing is watched meanwhile. */
    public void idle() { watching = false;watchSince = 0; }

    /**
     * One evaluation. offered: the model has no native charge limit (offered(DeviceDescriptor)). canNotify:
     * an alert notification would show (or Volt is open). Readings count only while connected and fresh.
     */
    public Event step(long nowMono, long nowWall, boolean offered, boolean canNotify, Link link,
            Double charging, long chargingMono, Double percent, long percentMono) {
        Event event = Event.NONE;
        boolean connected = link == Link.CONNECTED;
        boolean chargeOn = offered && settings.charge && settings.delivers(canNotify), lowOn = offered && settings.low && settings.delivers(canNotify);
        Integer pct = connected && fresh(percent, percentMono, nowMono) && percent >= 0 && percent <= 100 ? (int) Math.round(percent) : null;
        Integer state01 = connected && fresh(charging, chargingMono, nowMono) && (charging == 0 || charging == 1) ? charging.intValue() : null;
        if (pct != null) lastPct = pct;
        lastFresh = pct != null;
        if (connected) { lostSince = 0;paused = false; } else if (lostSince == 0) lostSince = nowMono;
        if (pct != null && state01 != null) {
            // Unseen for longer than a charge lasts, or the phone clock jumped back: that charge can't be told from a new one.
            if (state.session && (nowWall - state.seenWall > SESSION_GAP_MS || nowWall < state.seenWall - SEEN_STEP_MS)) end();
            // One point of reading noise allowed; a larger drop is a ride or a new charge.
            if (state.session && pct < state.bestPct - 1) end();
            if (state01 == 1) {
                stoppedSince = 0;
                if (!state.session) begin(pct, nowWall);
                seen(pct, nowWall);
                if (chargeOn && pct >= settings.chargeAt && due(settings.chargeAt)) event = fire(settings.chargeAt);
            } else if (state.session) {
                seen(pct, nowWall);
                if (chargeOn && settings.chargeAt == 100 && pct >= FULL_FROM && due(100)) event = fire(100);
                if (stoppedSince == 0 || nowMono < stoppedSince) stoppedSince = nowMono;
                else if (nowMono - stoppedSince > STOP_MS) end();
            }
            if (state01 == 0 && lowOn && !state.lowLatched && pct < settings.lowBelow) {
                state.lowLatched = true;dirty = true;if (event == Event.NONE) event = Event.LOW;
            }
        }
        if (pct != null && state.lowLatched && pct > settings.lowBelow + LOW_REARM) { state.lowLatched = false;dirty = true; }
        boolean want = chargeOn && state.session && due(settings.chargeAt) && !capped && !paused
                && (stoppedSince == 0 || nowMono - stoppedSince <= STOP_MS);
        if (want && !connected && (link == Link.LOST || nowMono - lostSince > RECONNECT_MS)) {
            want = false;
            if (watching) { paused = true;if (event == Event.NONE) event = Event.PAUSED; }
        }
        if (want && watchSince == 0) watchSince = nowMono;
        if (want && (nowMono - watchSince >= HOLD_MS || nowMono < watchSince)) {
            want = false;capped = true;if (event == Event.NONE) event = Event.TIME_LIMIT;
        }
        if (!want) watchSince = 0;
        watching = want;
        return event;
    }

    /** Not yet alerted at this level this session, and the session began below it (full counts from 99%). */
    private boolean due(int level) { return state.firedAt < level && state.startPct < (level >= 100 ? FULL_FROM : level); }
    private Event fire(int level) { state.firedAt = level;dirty = true;return level >= 100 ? Event.FULL : Event.CHARGE; }
    private void begin(int pct, long wall) {
        state.session = true;state.startPct = state.bestPct = pct;state.firedAt = 0;state.seenWall = wall;dirty = true;
        capped = false;watchSince = 0;
    }
    private void end() {
        if (state.session) dirty = true;
        state.session = false;state.startPct = state.bestPct = state.firedAt = 0;stoppedSince = 0;capped = false;
    }
    private void seen(int pct, long wall) {
        if (pct > state.bestPct) { state.bestPct = pct;dirty = true; }
        if (Math.abs(wall - state.seenWall) >= SEEN_STEP_MS) { state.seenWall = wall;dirty = true; }
    }
    private static boolean fresh(Double value, long mono, long now) {
        return value != null && Double.isFinite(value) && mono > 0 && now >= mono && now - mono <= FRESH_MS;
    }

    /**
     * Charge alerts are for scooters without a native charge limit: never where Volt offers the
     * charge_limit control (BleController.controlOffered's rule). A profile that never connected has no
     * model evidence and shows UT5 MAX rows, so it doesn't qualify either.
     */
    public static boolean offered(DeviceDescriptor d) {
        if (d == null || !d.simulated && d.pidSource() == DeviceDescriptor.PidSource.NONE) return false;
        return CapabilityResolver.decide("charge_limit", d, StatusFields.NONE, null).support != CapabilityResolver.Support.KNOWN_ENCODING;
    }

    // Notification wording, kept here so the formatting is unit-tested.
    public static String levelText(int level) { return level >= 100 ? "Full" : level + "%"; }
    public static String watchTitle(int target) { return target >= 100 ? "Charging to full" : "Charging to " + target + "%"; }
    /** "42% now · alert at 80%"; a stale reading says "Last seen" instead of passing for live. */
    public static String watchText(Integer percent, boolean fresh, int target) {
        String goal = target >= 100 ? "alert when full" : "alert at " + target + "%";
        if (percent == null) return Character.toUpperCase(goal.charAt(0)) + goal.substring(1);
        return (fresh ? percent + "% now · " : "Last seen " + percent + "% · ") + goal;
    }
    /** Determinate progress on a 0–target bar (stable across reconnects); -1 hides the bar when nothing was seen. */
    public static int watchProgress(Integer percent, int target) { return percent == null ? -1 : Math.max(0, Math.min(target, percent)); }
    public static String alertTitle(Event event, int level) {
        return event == Event.FULL ? "Fully charged" : event == Event.LOW ? "Battery low" : event == Event.CHARGE ? "Charged to " + level + "%" : "Charge alert paused";
    }
    public static String alertText(Event event, String scooter, int percent) {
        if (event == Event.PAUSED) return "Volt lost the connection to " + scooter + ". Open Volt to keep watching this charge.";
        if (event == Event.TIME_LIMIT) return "Volt stopped watching after " + HOLD_MS / 3_600_000L + " hours. Open Volt to keep watching this charge.";
        return scooter + " is at " + percent + "%.";
    }

    private static int snap(int value, int min, int max) {
        int v = Math.max(min, Math.min(max, value));return min + Math.round((v - min) / (float) STEP) * STEP;
    }
    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
    private static String bit(boolean value) { return value ? "1" : "0"; }
    private static int number(String text, int fallback) { try { return Integer.parseInt(text); } catch (RuntimeException unreadable) { return fallback; } }
    private static Map<String, String> fields(String text) {
        Map<String, String> out = new HashMap<>();if (text == null) return out;
        for (String part : text.split(";")) { int at = part.indexOf('=');if (at > 0) out.put(part.substring(0, at).trim(), part.substring(at + 1).trim()); }
        return out;
    }
}
