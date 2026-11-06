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
 * setting is turned off, or on Stop. Stop also keeps it from arming again for the rest of this charge.
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
        public Facts(boolean enabled, boolean connected, Boolean charging, boolean scheduled, Integer percent, Integer limit) {
            this.enabled = enabled;this.connected = connected;this.charging = charging;this.scheduled = scheduled;
            this.percent = percent;this.limit = limit;
        }
    }

    private boolean active, reconnecting, stoppedCharge;
    private long since, lostSince, offSince, stoppedOffSince;
    private int stoppedBest = -1;

    /** Holding now. */
    public boolean active() { return active; }
    /** Holding, with the scooter disconnected and inside the reconnect window. */
    public boolean reconnecting() { return active && reconnecting; }
    /** When the hold must end at the latest, on the monotonic clock; 0 when not holding. */
    public long deadline() { return active ? since + HOLD_MS : 0; }
    /** The next moment step() must run even without a new reading (reconnect window or hold limit); 0 for none. */
    public long nextCheck() {
        if (!active) return 0;
        long cap = since + HOLD_MS;
        return reconnecting ? Math.min(cap, lostSince + RECONNECT_MS + 1) : cap;
    }

    /** The owner left Volt. Returns whether the hold armed. */
    public boolean leave(long nowMono, Facts f) {
        observeStopped(nowMono, f);
        active = f.enabled && f.connected && !stoppedCharge && (Boolean.TRUE.equals(f.charging) || f.scheduled);
        if (active) { since = nowMono;lostSince = offSince = 0;reconnecting = false; }
        return active;
    }
    /** Volt is open again: nothing to hold. A Stop for this charge still counts. */
    public void shown() { active = reconnecting = false; }
    /** The owner's Stop on the card: ends the hold, and it doesn't arm again until this charge is over. */
    public void stop(Integer percent) {
        active = reconnecting = false;stoppedCharge = true;stoppedOffSince = 0;stoppedBest = percent == null ? -1 : percent;
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
        if (!stoppedCharge || !f.connected) return;
        if (f.percent != null && stoppedBest >= 0 && f.percent < stoppedBest - 1) { clearStopped();return; }
        if (f.percent != null && f.percent > stoppedBest) stoppedBest = f.percent;
        if (Boolean.FALSE.equals(f.charging) && !f.scheduled) {
            if (stoppedOffSince == 0 || nowMono < stoppedOffSince) stoppedOffSince = nowMono;
            else if (nowMono - stoppedOffSince > STOP_MS) clearStopped();
        } else if (Boolean.TRUE.equals(f.charging)) stoppedOffSince = 0;
    }
    private void clearStopped() { stoppedCharge = false;stoppedOffSince = 0;stoppedBest = -1; }
}
