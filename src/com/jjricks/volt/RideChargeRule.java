package com.jjricks.volt;

/**
 * A ride recording and a charge never overlap (owner, Nov 24 2026: "If a charge session starts, a ride recording should
 * end immediately, allowing the charge recording to start if queued."). Pure Java: the service feeds it the selected
 * scooter's live charging reading (ChargeAutoRecordPolicy.liveCharging, the same 5 s rule Record charges automatically
 * starts on, never a stale reading) with the reading's own time, the fresh-speed verdict (ChargeAutoRecordPolicy.movedAway)
 * and a monotonic clock.
 *
 * <ul>
 *   <li>End: a ride that is recording ends at once, saved with RideStore.STOP_CHARGING, when the scooter reports
 *       charging in a reading taken after the ride started, and is not moving above ChargeAutoRecordPolicy.END_SPEED_KMH.
 *       No hold is set, so Record charges automatically starts on its own path and the live charging card shows once
 *       the ride is saved.</li>
 *   <li>Started by hand while charging: the rider's choice, so that charge doesn't end it ({@link Exempt}). The first
 *       fresh reading decides (at the start, or the first one after it when there was none; a fresh speed above
 *       END_SPEED_KMH decides it isn't exempt, as it ends a standing exemption); the exemption ends at the
 *       first fresh "not charging" reading or a fresh speed above END_SPEED_KMH, so the plug-in at the end of that ride
 *       ends it as any other. The journal keeps it (RideStore.chargeExemption), so a recovered ride keeps it too.</li>
 *   <li>No automatic start: Record rides automatically starts nothing while the scooter charges, nor within
 *       {@link #SETTLE_MS} of its last charging reading, so unplugging the charger and plugging it back in (seen: 1, 0, 1
 *       within 5 to 8 s, the owner replugging to get a recording to start) never starts a ride; riding off faster than
 *       END_SPEED_KMH still starts one, the speed at which an automatic charge recording ends too. The wait belongs to
 *       the scooter that charged: another scooter selected meanwhile doesn't wait.</li>
 *   <li>One try: a charge-ended stop that fails (storage full) is not tried again for that ride ({@link #stopTried}). The
 *       failed stop already told the rider to free space and Retry save, and the rider's Stop is that retry; trying on
 *       every change would toast and fail in a loop.</li>
 *   <li>No loop: starting a ride by hand ends an automatic charge recording (ChargeAutoRecordPolicy.rideEndsRecording)
 *       and holds it, and that ride is exempt from the end above while the charge lasts; an automatic start never
 *       happens while charging, so it never ends a charge recording; and the end above sets no hold of either kind.</li>
 * </ul>
 */
final class RideChargeRule {
    /** How long after the last charging reading Record rides automatically still waits (an unplug and replug). */
    static final long SETTLE_MS = 30_000;

    /** A ride's standing against the charge: kept in its journal as these names. */
    enum Exempt {
        /** Ends when the scooter charges (every automatic ride, and one started by hand while not charging). */
        NO,
        /** Started by hand while charging: runs on until the scooter stops charging or rides off. */
        YES,
        /** Started by hand with no fresh reading: the first fresh reading decides. */
        UNDECIDED;
        static Exempt of(String stored) {
            for (Exempt e : values()) if (e.name().equals(stored)) return e;
            return NO;
        }
    }

    /** The scooter whose charging reading started the wait, and when (monotonic ms; -1 for none). */
    private String chargingProfile = "";
    private long chargingSeenMs = -1;
    /** The recording ride, its start (monotonic ms) and its standing. */
    private String ride;
    private long rideStartMs;
    private Exempt exempt = Exempt.NO;
    /** The ride whose charge-ended stop was tried (stopTried); never ended by this rule again. */
    private String stopTried;

    /** Every change and tick: live is ChargeAutoRecordPolicy.liveCharging for the selected scooter (null: not fresh). */
    void observe(String profile, Boolean live, long nowMs) {
        String p = profile == null ? "" : profile;
        if (!p.equals(chargingProfile)) { chargingProfile = p; chargingSeenMs = -1; }
        if (Boolean.TRUE.equals(live)) chargingSeenMs = nowMs;
    }

    /**
     * A ride started (or was recovered) now. byHand: the rider's start; live: the fresh charging reading then (null for
     * none). A recovered ride passes its journal's standing as stored and byHand false. Returns the standing to keep in
     * the journal.
     */
    Exempt rideStarted(String rideId, long startMs, boolean byHand, Boolean live) {
        ride = rideId; rideStartMs = startMs;
        exempt = !byHand ? Exempt.NO : live == null ? Exempt.UNDECIDED : live ? Exempt.YES : Exempt.NO;
        return exempt;
    }

    /** A recovered ride, with its journal's standing. */
    void rideRecovered(String rideId, long startMs, Exempt stored) {
        ride = rideId; rideStartMs = startMs; exempt = stored == null ? Exempt.NO : stored;
    }

    /** Whether this ride's start (or recovery) was seen, so its standing is known here. */
    boolean tracks(String rideId) { return rideId != null && rideId.equals(ride); }

    /** The standing of the recording ride (NO when it isn't this one). */
    Exempt exempt(String rideId) { return rideId != null && rideId.equals(ride) ? exempt : Exempt.NO; }

    /**
     * Updates the ride's standing from a fresh reading (see the class comment) and returns whether the ride ends now
     * because the scooter charges.
     *
     * @param rideId the recording ride, null when none
     * @param readingMs the charging reading's own time (monotonic ms)
     * @param movingFast a fresh speed reading above ChargeAutoRecordPolicy.END_SPEED_KMH
     */
    boolean endsRide(String rideId, Boolean live, long readingMs, boolean movingFast) {
        if (rideId == null || !rideId.equals(ride)) return false;
        if (exempt == Exempt.UNDECIDED && movingFast) exempt = Exempt.NO;
        else if (exempt == Exempt.UNDECIDED && live != null) exempt = live ? Exempt.YES : Exempt.NO;
        else if (exempt == Exempt.YES && (Boolean.FALSE.equals(live) || movingFast)) exempt = Exempt.NO;
        return exempt == Exempt.NO && Boolean.TRUE.equals(live) && !movingFast && readingMs > rideStartMs && !rideId.equals(stopTried);
    }

    /**
     * The service is about to stop this ride because it charges: called before the stop, since a failed stop's own change
     * notice comes back here at once. Whatever the outcome, this rule doesn't end the ride again (see the class comment).
     */
    void stopTried(String rideId) { stopTried = rideId; }

    /** Whether Record rides automatically waits now for this scooter: charging, or within SETTLE_MS, unless ridden off fast. */
    boolean blocksAutoRecord(String profile, long nowMs, boolean movingFast) {
        String p = profile == null ? "" : profile;
        return !movingFast && p.equals(chargingProfile) && chargingSeenMs >= 0 && nowMs >= chargingSeenMs
                && nowMs - chargingSeenMs < SETTLE_MS;
    }
}
