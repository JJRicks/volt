package com.jjricks.volt;

/**
 * Live charging's connection hold (owner answers Q45 and Q47). Pure and Android-free, on the monotonic
 * clock. It never sends a scooter command: holding only keeps the battery reads Volt already makes.
 *
 * It arms only when the owner leaves Volt, and only when all of these hold then: the setting is on,
 * the selected scooter is connected, and it is charging or has a Volt charge schedule (Charge done by
 * or Scheduled charging) due to start within the hold limit. Nothing else arms it: a charge that
 * starts later, or a reconnect, never does. Opening Volt again disarms it, and the next leave decides
 * afresh.
 *
 * It ends when the scooter stays disconnected for RECONNECT_MS, when charging finishes at the limit,
 * when charging stays off (with no schedule waiting) for more than STOP_MS, after HOLD_MS, when the
 * setting is turned off, or on Stop. Stop also keeps it from arming again for the rest of this charge, for that
 * scooter only (Facts.profile), and for HOLD_MS at most. Each scooter's Stop is its own (up to MAX_STOPS of them):
 * a Stop on one never replaces or ends another's. The service stores them (stopped()) on the device and hands
 * them back to a new service (restoreStopped), so a recreated service doesn't arm the same charge again.
 * The owner opening Volt (opened) ends that scooter's Stop, so leaving again arms the card as usual (owner,
 * 2026-11-06); the switch is what turns it off for good.
 */
public final class ChargeLiveHold {
    /** Owner: a scooter that doesn't reconnect within about 15 to 20 seconds ends the card. */
    public static final long RECONNECT_MS = 18_000L;
    /** Owner: at most 12 hours. */
    public static final long HOLD_MS = 12 * 60 * 60_000L;
    /** A charger that cuts out and resumes keeps the hold, as it keeps a battery alert's charge session. */
    public static final long STOP_MS = ChargeAlertPolicy.STOP_MS;
    /** Charging finished when it stops at the limit, allowing one point of reading noise; 99% counts as full. */
    public static final int FULL_FROM = ChargeAlertPolicy.FULL_FROM;

    public enum End { NONE, OFF, STOPPED, LOST, TIME_LIMIT, FINISHED, CHARGER_OFF }

    /** What the service sees at one moment. charging and percent are null unless connected and fresh. */
    public static final class Facts {
        public final boolean enabled, connected, scheduled;
        public final Boolean charging;
        public final Integer percent, limit;
        /** The selected scooter's profile id ("" for none); connected, charging and percent are about it. */
        public final String profile;
        public Facts(boolean enabled, boolean connected, Boolean charging, boolean scheduled, Integer percent, Integer limit) {
            this(enabled, connected, charging, scheduled, percent, limit, null);
        }
        public Facts(boolean enabled, boolean connected, Boolean charging, boolean scheduled, Integer percent, Integer limit, String profile) {
            this.enabled = enabled;this.connected = connected;this.charging = charging;this.scheduled = scheduled;
            this.percent = percent;this.limit = limit;this.profile = profile == null ? "" : profile;
        }
    }

    /** A Stop that still counts: which scooter, the best level seen since, and how long ago it was pressed. */
    public static final class Stopped {
        public final String profile;public final int best;public final long ageMs;
        Stopped(String profile, int best, long ageMs) { this.profile = profile;this.best = best;this.ageMs = ageMs; }
    }

    /** Scooters whose Stop still counts at once; the oldest Stop goes first past this. */
    public static final int MAX_STOPS = 8;
    /** One scooter's Stop: the best level seen since, when it was pressed, and since when charging has been off. */
    private static final class Stop { int best;long at, offSince; }

    private boolean active, reconnecting;
    private long since, lostSince, offSince;
    /** By profile id, oldest Stop first. */
    private final java.util.LinkedHashMap<String, Stop> stops = new java.util.LinkedHashMap<>();

    /** Holding now. */
    public boolean active() { return active; }
    /** Holding, with the scooter disconnected and inside the reconnect window. */
    public boolean reconnecting() { return active && reconnecting; }
    /** When the hold must end at the latest, on the monotonic clock; 0 when not holding. */
    public long deadline() { return active ? since + HOLD_MS : 0; }
    /** The next moment step() must run even without a new reading (reconnect window, charger-off window or hold limit); 0 for none. */
    public long nextCheck() {
        if (!active) return 0;
        long cap = since + HOLD_MS;
        if (reconnecting) return Math.min(cap, lostSince + RECONNECT_MS + 1);
        // Out of sight a charger that stayed off must end the card when its window does, not on some later poll.
        return offSince != 0 ? Math.min(cap, offSince + STOP_MS + 1) : cap;
    }

    /** The owner left Volt. Returns whether the hold armed. */
    public boolean leave(long nowMono, Facts f) {
        observeStopped(nowMono, f);
        boolean stopped = stops.containsKey(f.profile);
        active = f.enabled && f.connected && !stopped && (Boolean.TRUE.equals(f.charging) || f.scheduled);
        if (active) { since = nowMono;lostSince = offSince = 0;reconnecting = false; }
        return active;
    }
    /** Volt is open again: nothing to hold. A Stop for this charge still counts. */
    public void shown() { active = reconnecting = false; }
    /**
     * The owner opened Volt (its screen in front, never a service recreated in the background): nothing to hold,
     * and the selected scooter's Stop is over, so the next leave decides afresh. Other scooters' Stops still count.
     */
    public void opened(String profile) { shown(); stops.remove(profile == null ? "" : profile); }
    /** The owner's Stop on the card: ends the hold, and it doesn't arm again until this charge is over. */
    public void stop(Integer percent) { stop(percent, null, 0); }
    /** Stop for the scooter with this profile id, at nowMono. */
    public void stop(Integer percent, String profile, long nowMono) {
        active = reconnecting = false;
        put(profile == null ? "" : profile, percent == null ? -1 : percent, nowMono);
    }
    /** Every Stop that still counts, oldest first, for the service to store; empty when none. */
    public java.util.List<Stopped> stopped(long nowMono) {
        prune(nowMono);
        java.util.ArrayList<Stopped> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Stop> e : stops.entrySet()) out.add(new Stopped(e.getKey(), e.getValue().best, nowMono - e.getValue().at));
        return out;
    }
    /** A new service takes back a stored Stop pressed ageMs ago; one older than HOLD_MS (or from the future) is over. */
    public void restoreStopped(String profile, int best, long ageMs, long nowMono) {
        if (ageMs < 0 || ageMs >= HOLD_MS || profile == null || profile.isEmpty()) return;
        put(profile, best < 0 || best > 100 ? -1 : best, nowMono - ageMs);
    }
    /** Adds or replaces one scooter's Stop as the newest, dropping the oldest past MAX_STOPS. */
    private void put(String profile, int best, long at) {
        stops.remove(profile);
        Stop stop = new Stop();stop.best = best;stop.at = at;
        stops.put(profile, stop);
        java.util.Iterator<String> oldest = stops.keySet().iterator();
        while (stops.size() > MAX_STOPS) { oldest.next();oldest.remove(); }
    }
    /** Ends every Stop pressed HOLD_MS ago or more (or, after a clock change, in the future). */
    private void prune(long nowMono) {
        stops.values().removeIf(stop -> nowMono < stop.at || nowMono - stop.at >= HOLD_MS);
    }

    /** One evaluation; any End other than NONE has just ended the hold. */
    public End step(long nowMono, Facts f) {
        observeStopped(nowMono, f);
        if (!active) return End.NONE;
        if (!f.enabled) return end(End.OFF);
        if (nowMono < since || nowMono - since >= HOLD_MS) return end(End.TIME_LIMIT);
        if (!f.connected) {
            if (!reconnecting) { reconnecting = true;lostSince = nowMono; }
            if (nowMono < lostSince || nowMono - lostSince > RECONNECT_MS) return end(End.LOST);
            return End.NONE;
        }
        reconnecting = false;lostSince = 0;
        if (Boolean.TRUE.equals(f.charging) || f.scheduled) { offSince = 0;return End.NONE; }
        if (Boolean.FALSE.equals(f.charging) && f.percent != null && f.percent >= finishAt(f.limit)) return end(End.FINISHED);
        // Off, or no fresh reading while connected: a charger that cuts out briefly keeps the hold.
        if (offSince == 0 || nowMono < offSince) offSince = nowMono;
        else if (nowMono - offSince > STOP_MS) return end(End.CHARGER_OFF);
        return End.NONE;
    }

    /** The level a stopped charge has reached its target at: the limit less one point, or 99% without one. */
    public static int finishAt(Integer limit) {
        return limit == null || limit >= 100 ? FULL_FROM : Math.max(0, limit - 1);
    }

    private End end(End why) { active = reconnecting = false;offSince = lostSince = 0;return why; }

    /** A stopped charge is over once charging stays off (nothing scheduled) past STOP_MS, or the level drops two points. */
    private void observeStopped(long nowMono, Facts f) {
        prune(nowMono);
        // Only the selected scooter's own readings say anything about its stopped charge.
        Stop stop = f.connected ? stops.get(f.profile) : null;
        if (stop == null) return;
        if (f.percent != null && stop.best >= 0 && f.percent < stop.best - 1) { stops.remove(f.profile);return; }
        if (f.percent != null && f.percent > stop.best) stop.best = f.percent;
        if (Boolean.FALSE.equals(f.charging) && !f.scheduled) {
            if (stop.offSince == 0 || nowMono < stop.offSince) stop.offSince = nowMono;
            else if (nowMono - stop.offSince > STOP_MS) stops.remove(f.profile);
        } else if (Boolean.TRUE.equals(f.charging)) stop.offSince = 0;
    }

    /**
     * Whether a battery alert already announced this finish, so the done notice would repeat it: a Full alert, or a
     * charge alert at or above the level this charge finished at, for the same scooter, posted during this hold and
     * still showing. Anything less (a low battery alert, an older or swiped-away one, another scooter's) posts the notice.
     */
    public static boolean alreadyAnnounced(boolean fullAlert, boolean chargeAlert, int alertLevel, int doneLevel,
            boolean sameScooter, boolean thisCharge, boolean showing) {
        if (!sameScooter || !thisCharge || !showing) return false;
        return fullAlert || chargeAlert && alertLevel >= doneLevel;
    }
}
