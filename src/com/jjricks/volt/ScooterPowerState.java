package com.jjricks.volt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether a connected scooter is switched off but still answering (2.0.5 item 5). Pure Java; BleController feeds it.
 *
 * <p>A UT5 MAX switched on pushes frames by itself: 0x90 (battery, lock, drive mode, warning) and 0x92 (speed,
 * odometer, driving status) about once a second, riding or parked. Other models push 0x91 instead of 0x92. One owner's
 * UT5 MAX was seen once, on 23 November 2026, switched off yet still taking connections: it answered every poll
 * (0x70, 0x72, 0x79) and acknowledged writes, but pushed nothing for 25 minutes. In 34 connection-log snapshots that
 * day, an on scooter's newest frame was never older than 2.7 s with polling stopped, and the off one's was never
 * younger than the polls. That state can't be reproduced on demand, so the rule is conservative and every guard is its
 * own small rule here, to flip on its own:
 *
 * <ul>
 *   <li>{@link #GRACE_MS}: no verdict until this long after the link became ready (the first push came within 3 s).</li>
 *   <li>{@link #SILENCE_MS}: off once no 0x90, 0x91 or 0x92 has arrived for this long.</li>
 *   <li>{@link #armed}: only on the UT5 MAX layer or a scooter that has pushed before; a model that never pushes is
 *       never judged off.</li>
 *   <li>{@link #charging}: never off while the scooter reports charging (its charging state or a current, read within
 *       the status window).</li>
 *   <li>Fresh status: never off unless the scooter is answering status reads (a silent link is the loss watchdog's).</li>
 *   <li>Never in the simulator (only a validation's hook, {@link #heldOff}, can show the state there).</li>
 *   <li>All times are awake time (SystemClock.uptimeMillis): a phone in deep sleep never counts its sleep as silence.</li>
 * </ul>
 * Once off, only a push ends it (the first one clears the verdict at once), or the link closing: a stale or missing
 * status, the link not ready for a moment, or a charging reading afterwards all leave it off, so neither a ride's
 * countdown nor Home's state flickers with background polling or a short radio gap.
 */
final class ScooterPowerState {
    /** No verdict this soon after the link became ready. */
    static final long GRACE_MS = 10_000;
    /** Off once no push has arrived for this long (awake time). */
    static final long SILENCE_MS = 10_000;
    /** A current at least this large (either sign) counts as charging. */
    static final double CHARGING_CURRENT_A = 0.2;
    /** The frames a switched-on scooter pushes by itself. */
    static final int[] PUSHES = {0x90, 0x91, 0x92};

    enum State {
        /** A push arrived within {@link #SILENCE_MS}: the scooter is on. */
        LIVE,
        /** Ready for less than {@link #GRACE_MS} and no push yet. */
        WAITING,
        /** Switched off but answering. */
        OFF,
        /** Not judged: see the Why. */
        NOT_JUDGED
    }
    enum Why { PUSHING, GRACE, SILENT, NOT_READY, SIMULATION, NOT_ARMED, CHARGING, STATUS_STALE }

    static final class Result {
        final State state; final Why why;
        Result(State state, Why why) { this.state = state; this.why = why; }
        boolean off() { return state == State.OFF; }
        @Override public String toString() { return state + "/" + why; }
    }

    /**
     * Test-only. False in the app, always: the simulator never pushes, so an emulator validation of Home's "Scooter off"
     * sets it by reflection (android-tests/ScooterOffValidation) and clears it again. Nothing in the app writes it.
     */
    private static volatile boolean heldOff;

    private long readyAt;
    private long lastPush;
    /** Judged off since the last push on this connection: sticky until a push or the link closes. */
    private boolean off;
    /** The charging reading the latest verdict was given: it suspends the off words (shownOff). */
    private boolean chargingNow;
    private final long[] counts = new long[PUSHES.length];
    private final long[] lastAt = new long[PUSHES.length];

    /** The link became ready (authenticated, status read) at this awake time: a new session. */
    void start(long awakeMs) { reset(); readyAt = awakeMs; }
    /** The link closed. */
    void reset() {
        readyAt = 0; lastPush = 0; off = false;
        for (int i = 0; i < PUSHES.length; i++) { counts[i] = 0; lastAt[i] = 0; }
    }
    /** A frame from the scooter while ready; only the pushes count. True when it was one. */
    boolean frame(int cmd, long awakeMs) {
        int i = index(cmd);
        if (i < 0 || readyAt <= 0) return false;
        counts[i]++; lastAt[i] = awakeMs; lastPush = Math.max(lastPush, awakeMs); off = false;
        return true;
    }
    /** Any push this session. */
    boolean pushed() { return lastPush > 0; }
    /** Awake time of the latest push, 0 for none. */
    long lastPush() { return lastPush; }

    /**
     * This session's verdict. ready: an authenticated, ready link; statusFresh: the latest status read is fresh;
     * charging: {@link #charging}; armed: {@link #armed}. Once off, it stays off until a push or {@link #reset}.
     */
    Result verdict(long nowAwake, boolean simulation, boolean ready, boolean armed, boolean statusFresh, boolean charging) {
        chargingNow = charging;
        if (simulation) return heldOff && ready ? new Result(State.OFF, Why.SIMULATION) : new Result(State.NOT_JUDGED, Why.SIMULATION);
        if (off && readyAt > 0) return new Result(State.OFF, Why.SILENT);
        if (!ready || readyAt <= 0) return new Result(State.NOT_JUDGED, Why.NOT_READY);
        if (lastPush > 0 && nowAwake - lastPush < SILENCE_MS) return new Result(State.LIVE, Why.PUSHING);
        if (!armed) return new Result(State.NOT_JUDGED, Why.NOT_ARMED);
        if (charging) return new Result(State.NOT_JUDGED, Why.CHARGING);
        if (!statusFresh) return new Result(State.NOT_JUDGED, Why.STATUS_STALE);
        if (lastPush <= 0 && nowAwake - readyAt < GRACE_MS) return new Result(State.WAITING, Why.GRACE);
        off = true;
        return new Result(State.OFF, Why.SILENT);
    }

    /**
     * Connected, but not yet shown to be on: no push on this connection (before the link is ready, in the grace, or with
     * its status stale), on a judged scooter ({@code armed}), not charging, not the simulator, and not already off. A
     * switched-off scooter that keeps answering looks exactly like this until its verdict; a ride's auto-end does not take
     * such a link as a reconnect (RideAutoStopPolicy.Link), only its first push.
     */
    boolean awaitingPush(Result verdict, boolean armed) {
        if (!armed || lastPush > 0 || verdict == null || verdict.off()) return false;
        return verdict.why != Why.SIMULATION && verdict.why != Why.CHARGING && verdict.why != Why.PUSHING;
    }

    /** What BleController's event log records for a verdict, given whether it last logged off. */
    enum Logged { NONE, SWITCHED_OFF, SWITCHED_ON, CLEARED }

    /**
     * The event-log line for this verdict: "switched off" once when it turns off, "switched on" only when a push ends
     * it (LIVE). Anything else away from off (the simulator's hook cleared) only clears the mark, with no line.
     */
    static Logged logged(boolean loggedOff, Result r) { return logged(loggedOff, r, false); }

    /**
     * As above, from a push itself ({@code pushedNow}): a push that ends a logged off state is a switch-on whatever the
     * verdict says at that moment (with the status stale it is "not ready", not LIVE).
     */
    static Logged logged(boolean loggedOff, Result r, boolean pushedNow) {
        if (r.off()) return loggedOff ? Logged.NONE : Logged.SWITCHED_OFF;
        if (!loggedOff) return Logged.NONE;
        return pushedNow || r.state == State.LIVE ? Logged.SWITCHED_ON : Logged.CLEARED;
    }

    /**
     * Whether Volt says "Scooter off" (and "Turn the scooter on first") for this verdict: off, and not charging now.
     * Switched off and then plugged in, the usual way to charge, the verdict stays off until a push, and the logic (the
     * charging settings' waiver, the refusals, a ride's auto-end) keeps reading it; a charging reading suspends only the
     * words, so "Scooter off" never sits over the Charging view. The simulator's hook is shown as set.
     */
    static boolean shownOff(Result verdict, boolean chargingNow) {
        return verdict != null && verdict.off() && (verdict.why == Why.SIMULATION || !chargingNow);
    }
    /** As above, with the charging reading the latest {@link #verdict} was given (BleController.scooterOff). */
    boolean shownOff(Result verdict) { return shownOff(verdict, chargingNow); }

    /** Which scooters are judged: the UT5 MAX layer, or one that has pushed on an earlier connection. */
    static boolean armed(boolean ut5Max, boolean pushedBefore) { return ut5Max || pushedBefore; }

    /**
     * Charging: a charging state other than 0, or a current of at least {@link #CHARGING_CURRENT_A}, each read within the
     * status window (CapabilityResolver.STATUS_MAX_AGE_MS; ages in ms, negative when never read). Unknown or older
     * readings are not charging: with battery reads paused, an old charging current says nothing about now.
     */
    static boolean charging(Double chargingState, long stateAgeMs, Double currentA, long currentAgeMs) {
        if (chargingState != null && fresh(stateAgeMs) && chargingState != 0) return true;
        return currentA != null && fresh(currentAgeMs) && Math.abs(currentA) >= CHARGING_CURRENT_A;
    }
    private static boolean fresh(long ageMs) { return ageMs >= 0 && ageMs <= CapabilityResolver.STATUS_MAX_AGE_MS; }

    /**
     * What stays usable while the scooter is off: the charging settings, which the owner sets with the scooter switched
     * off on its charger (charge limit, the scheduled charge and its switch, and the clock the schedule runs on). Every
     * other change is refused with "Turn the scooter on first". A write that is let through still needs the scooter's
     * acknowledgement and readback, as always.
     */
    static boolean usableWhileOff(String feature) {
        if (feature == null) return false;
        switch (feature) {
            case "charge_limit": case "timed_charging": case "charge_start": case "clock_sync": return true;
            default: return false;
        }
    }

    /**
     * Whether a change usable while off also goes without the parked rule then. On the UT5 MAX the charge limit, the
     * scheduled charge and its switch need the scooter parked, which Volt knows only from a motion report, and a switched-off
     * scooter sends none, so without this they would stay refused ("No motion report yet") just when the owner sets them.
     * Off means no live data at all for 10 s, which a moving scooter never does. Ordinary changes only: guided tests and
     * experimental tries keep the parked rule. Flip {@link #WAIVE_PARKED_WHILE_OFF} to keep the rule.
     */
    static boolean parkedWaivedWhileOff(String feature) { return WAIVE_PARKED_WHILE_OFF && usableWhileOff(feature); }
    static final boolean WAIVE_PARKED_WHILE_OFF = true;

    /** The diagnostics file's push_frames section: per push, this session's count and the last one's age. */
    Map<String, Object> diagnostics(long nowAwake, Result verdict, boolean armed) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> count = new LinkedHashMap<>(), age = new LinkedHashMap<>();
        for (int i = 0; i < PUSHES.length; i++) {
            String name = String.format(java.util.Locale.ROOT, "0x%02X", PUSHES[i]);
            count.put(name, counts[i]);
            age.put(name, lastAt[i] <= 0 ? null : (Object) Math.max(0, nowAwake - lastAt[i]));
        }
        m.put("counts", count);
        m.put("last_age_ms", age);
        m.put("ready_age_ms", readyAt <= 0 ? null : (Object) Math.max(0, nowAwake - readyAt));
        m.put("armed", armed);
        m.put("verdict", verdict == null ? null : verdict.state.name().toLowerCase(java.util.Locale.ROOT));
        m.put("why", verdict == null ? null : verdict.why.name().toLowerCase(java.util.Locale.ROOT));
        return m;
    }

    private static int index(int cmd) {
        for (int i = 0; i < PUSHES.length; i++) if (PUSHES[i] == cmd) return i;
        return -1;
    }
}
