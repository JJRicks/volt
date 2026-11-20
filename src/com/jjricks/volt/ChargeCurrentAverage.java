package com.jjricks.volt;

/**
 * The charging current every time estimate divides by: the mean of the last minute of one charge's readings, in place of
 * the newest reading alone. Pure and Android-free, on the monotonic clock.
 *
 * The battery reports its current in steps of about 0.02 to 0.03 A. When the real current sits at a step's edge, the
 * reading moves between the two steps from one battery read to the next (about every two seconds with Volt open). On
 * the standard charger's 2 A that is a 1% change, which every estimate passes on: about two minutes on a four-hour
 * charge, so the time to full flipped between two values, both counting down (owner's report on 2.0.1). Over a
 * minute's readings, weighted by time, the steps average out to the current between them, so the time moves smoothly.
 *
 * A real change is never averaged away: a reading at least STEP_A and STEP_FRACTION away from the mean (a charger
 * plugged or swapped, the high-speed charger's step down, a slowdown) starts the mean again from it, so the estimate
 * follows it at once, as before. A gradual change (the standard charger's warm-up decay, a taper) shows with about half
 * the window's delay. Losing charging, another scooter, a clock running back or a gap longer than the window starts
 * afresh, and the mean only ever stands in for the newest reading it holds (steady), so a reading it never saw is used
 * as it is.
 */
public final class ChargeCurrentAverage {
    /** How far back the mean reaches from the newest reading. */
    public static final long WINDOW_MS = 60_000L;
    /** A reading this far from the mean (both: amperes and fraction of the mean) is a real change, never a step's edge. */
    public static final double STEP_A = 0.1, STEP_FRACTION = 0.05;
    /** Readings kept at most; past this the oldest goes first (a minute at the two-second read is thirty). */
    static final int CAPACITY = 64;

    private String profile;
    private final long[] times = new long[CAPACITY];
    private final double[] amps = new double[CAPACITY];
    private int first, count;

    public void reset() { profile = null; first = count = 0; }

    /**
     * One battery read: charging is its fresh charging state (null when stale or unknown, which changes nothing; false
     * starts afresh), current its paired current reading (null when unpaired or stale, which changes nothing) and
     * frameMs that reading's monotonic time. A reading already held (same frameMs) changes nothing.
     */
    public void observe(String profile, Boolean charging, Double current, long frameMs) {
        if (profile == null || profile.isEmpty() || charging == null) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (!charging) { first = count = 0; return; }
        if (current == null || !finite(current) || frameMs <= 0) return;
        double value = Math.abs(current);
        if (count > 0) {
            long newest = times[index(count - 1)];
            if (frameMs == newest) return;
            if (frameMs < newest || frameMs - newest > WINDOW_MS) first = count = 0;
        }
        if (count > 0) {
            double mean = mean();
            if (Math.abs(value - mean) >= STEP_A && Math.abs(value - mean) >= STEP_FRACTION * mean) first = count = 0;
        }
        if (count == CAPACITY) { first = index(1); count--; }
        times[index(count)] = frameMs;
        amps[index(count)] = value;
        count++;
        // Keep the one reading at or before the window's start: the mean's first stretch begins there.
        while (count > 2 && frameMs - times[index(1)] >= WINDOW_MS) { first = index(1); count--; }
    }

    /**
     * The current an estimate uses for this reading: current itself unless it is the newest reading held for this
     * profile (same magnitude), and that no older than WINDOW_MS at nowMs; then the mean, with current's sign, its
     * freshness and its frame time, so the voltage it was paired with stays paired. Null and stale readings pass through.
     */
    public ChargingEstimate.Value steady(String profile, ChargingEstimate.Value current, long nowMs) {
        if (current == null || current.number == null || !current.fresh || !finite(current.number) || count == 0
                || profile == null || !profile.equals(this.profile)) return current;
        int newest = index(count - 1);
        if (Math.abs(current.number) != amps[newest] || nowMs < times[newest] || nowMs - times[newest] > WINDOW_MS) return current;
        return new ChargingEstimate.Value(Math.copySign(mean(), current.number), true, current.observedMs);
    }

    /** How many readings the mean holds now (tests and diagnostics). */
    int held() { return count; }

    /**
     * The mean over the window ending at the newest reading (or since the first one held), weighted by time: the
     * current runs in a straight line from each reading to the next. A reading moving between two steps at every read
     * then averages to exactly halfway whichever reading the window starts on; a plain mean of the readings would swing
     * by a step over their count at each read, enough to flip the shown minute as it turns.
     */
    private double mean() {
        int last = index(count - 1);
        if (count == 1) return amps[last];
        long end = times[last], start = Math.max(times[first], end - WINDOW_MS);
        double area = 0;
        for (int i = 1; i < count; i++) {
            long t0 = times[index(i - 1)], t1 = times[index(i)];
            double a0 = amps[index(i - 1)], a1 = amps[index(i)];
            if (t1 <= start) continue;
            if (t0 < start) { a0 += (a1 - a0) * (start - t0) / (t1 - t0); t0 = start; }
            area += (t1 - t0) * (a0 + a1) / 2;
        }
        return end > start ? area / (end - start) : amps[last];
    }

    private int index(int i) { return (first + i) % CAPACITY; }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
