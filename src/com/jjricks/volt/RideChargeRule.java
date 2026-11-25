package com.jjricks.volt;

/**
 * A ride recording and a charge never overlap (owner, Nov 24 2026: "If a charge session starts, a ride recording should
 * end immediately, allowing the charge recording to start if queued."). Pure Java: the service feeds it the selected
 * scooter's live charging reading (ChargeAutoRecordPolicy.liveCharging, the same 5 s rule Record charges automatically
 * starts on, never a stale reading), the fresh-speed verdict (ChargeAutoRecordPolicy.movedAway) and a monotonic clock.
 *
 * <ul>
 *   <li>End: a ride that is recording ends at once, saved with RideStore.STOP_CHARGING, when the scooter reports
 *       charging and is not moving above ChargeAutoRecordPolicy.END_SPEED_KMH. No hold is set, so Record charges
 *       automatically starts on its own path and the live charging card shows once the ride is saved. One exception: a
 *       ride the rider started by hand while the scooter already charged is the rider's choice, and runs on.</li>
 *   <li>No automatic start: Record rides automatically starts nothing while the scooter charges, nor within
 *       {@link #SETTLE_MS} of the last charging reading, so unplugging the charger and plugging it back in (seen: 1, 0, 1
 *       within 5 to 8 s, the owner replugging to get a recording to start) never starts a ride; riding off faster than END_SPEED_KMH still starts one, the speed at which an
 *       automatic charge recording ends too.</li>
 *   <li>No loop: starting a ride by hand ends an automatic charge recording (ChargeAutoRecordPolicy.rideEndsRecording)
 *       and holds it, and that ride is exempt from the end above; an automatic start never happens while charging, so it
 *       never ends a charge recording; and the end above sets no hold of either kind.</li>
 * </ul>
 */
final class RideChargeRule {
    /** How long after the last charging reading Record rides automatically still waits (an unplug and replug). */
    static final long SETTLE_MS = 30_000;

    /** The last fresh charging reading (monotonic ms), -1 for none. */
    private long chargingSeenMs = -1;
    /** The ride the rider started by hand while the scooter charged, or null. */
    private String startedWhileCharging;

    /** Every change and tick: live is ChargeAutoRecordPolicy.liveCharging for the selected scooter (null: not fresh). */
    void observe(Boolean live, long nowMs) {
        if (Boolean.TRUE.equals(live)) chargingSeenMs = nowMs;
    }

    /** The rider started this ride by hand; live as for {@link #observe}. */
    void riderStarted(String rideId, Boolean live) {
        startedWhileCharging = Boolean.TRUE.equals(live) ? rideId : null;
    }

    /**
     * Whether the recording ride ends now because the scooter charges.
     *
     * @param rideId the recording ride, null when none
     * @param movingFast a fresh speed reading above ChargeAutoRecordPolicy.END_SPEED_KMH
     */
    boolean endsRide(String rideId, Boolean live, boolean movingFast) {
        return rideId != null && Boolean.TRUE.equals(live) && !movingFast && !rideId.equals(startedWhileCharging);
    }

    /** Whether Record rides automatically waits now: charging, or charging within SETTLE_MS, unless riding off fast. */
    boolean blocksAutoRecord(long nowMs, boolean movingFast) {
        return !movingFast && chargingSeenMs >= 0 && nowMs >= chargingSeenMs && nowMs - chargingSeenMs < SETTLE_MS;
    }
}
