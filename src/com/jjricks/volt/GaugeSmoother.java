package com.jjricks.volt;

/**
 * Glides a bar, ring or trace end to each new reading with a critically damped spring, tuned to
 * the reading's interval so the motion is done before the next reading lands (riding-mode plan,
 * "Smoothing to the polling rate").
 *
 * It never overshoots and never extrapolates: the shown value always moves toward the latest real
 * reading and stops there. A retarget against the current motion starts from rest; a retarget along
 * it keeps the speed, so a rising reading doesn't stutter. The value is a closed-form function of
 * time, so the frame rate doesn't change the motion.
 */
final class GaugeSmoother {
    /** The motion reaches 95 % of a step at this fraction of the source's interval. */
    static final double SETTLE_FRACTION = 0.6;
    /** (1 + x) e^-x = 0.05 for a critically damped spring from rest. */
    private static final double SETTLE_X = 4.7439;
    /** A reading interval is never treated as shorter than this. */
    static final long MIN_INTERVAL_MS = 100;

    private boolean started;
    private double x0, v0, target, omega;
    private long t0;
    /** Time after t0 at which the curve reaches the target, or +infinity when it never crosses. */
    private double crossS = Double.POSITIVE_INFINITY;

    /** Spring rate (per second) for a source that updates every intervalMs. */
    static double omega(long intervalMs) {
        return SETTLE_X / (SETTLE_FRACTION * Math.max(MIN_INTERVAL_MS, intervalMs) / 1000.0);
    }

    boolean started() { return started; }
    double target() { return target; }

    /** Jumps to a value with no motion, for the first reading or after a reset. */
    void snap(double value, long nowMs) {
        started = true;
        x0 = target = value; v0 = 0; t0 = nowMs; crossS = Double.POSITIVE_INFINITY;
        omega = omega(1000);
    }

    /** A new real reading. The first one snaps. */
    void setTarget(double value, long intervalMs, long nowMs) {
        if (!Double.isFinite(value)) return;
        if (!started) { snap(value, nowMs); return; }
        double x = value(nowMs), v = velocity(nowMs);
        double d = x - value;
        // Moving away from the new reading would carry the gauge past where it has ever been asked to be.
        if (d == 0 || v * d > 0) v = 0;
        x0 = x; v0 = v; target = value; t0 = nowMs; omega = omega(intervalMs);
        // e(t) = (d + c t) e^(-omega t) crosses zero at t = -d / c when that is in the future.
        double c = v0 + omega * d;
        crossS = c != 0 && -d / c > 0 ? -d / c : Double.POSITIVE_INFINITY;
        if (d == 0) crossS = 0;
    }

    /**
     * Follows a reading that can change sign and be corrected in place, such as power (Telemetry
     * re-signs the latest current after a 0x79 reply but keeps its measurement time). A new reading
     * glides there, and so does a correction of the latest one, so the gauge never keeps a sign the
     * reading no longer has. A change of sign restarts from zero: the shown sign is always the latest
     * reading's, and smoothing never shows draw while the reading is regen, or the other way round.
     *
     * @param newReading true for a new measurement; false for the latest one again, possibly corrected
     */
    void follow(double value, long intervalMs, boolean newReading, long nowMs) {
        if (!Double.isFinite(value)) return;
        if (!started) { snap(value, nowMs); return; }
        if (!newReading && value == target) return;
        if (value * value(nowMs) < 0) snap(0, nowMs);
        setTarget(value, intervalMs, nowMs);
    }

    /** The value to draw at this time. */
    double value(long nowMs) {
        if (!started) return Double.NaN;
        double t = Math.max(0, (nowMs - t0) / 1000.0);
        if (t >= crossS) return target;
        double d = x0 - target;
        return target + (d + (v0 + omega * d) * t) * Math.exp(-omega * t);
    }

    /** Units per second at this time. */
    double velocity(long nowMs) {
        if (!started) return 0;
        double t = Math.max(0, (nowMs - t0) / 1000.0);
        if (t >= crossS) return 0;
        double d = x0 - target, c = v0 + omega * d;
        return (c - omega * (d + c * t)) * Math.exp(-omega * t);
    }

    /** True once the remaining motion is below tolerance, so the view can stop animating. */
    boolean settled(long nowMs, double tolerance) {
        return !started || (Math.abs(value(nowMs) - target) <= tolerance && Math.abs(velocity(nowMs)) <= tolerance * omega);
    }
}
