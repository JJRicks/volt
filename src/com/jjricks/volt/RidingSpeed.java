package com.jjricks.volt;

/**
 * Max and Avg for one open riding mode. Android-free and clock-free, so the JVM tests drive it with
 * synthetic readings. The service's Telemetry feeds it every speed reading as it arrives, so a locked
 * phone or a coalesced screen refresh loses nothing; the screen's refresh feeds it too, which is how a
 * reading from just before opening becomes the baseline. Readings already counted add nothing.
 *
 * Avg is time-weighted over the intervals the scooter was moving, with each interval's speed the mean of
 * its two ends: the same rule as a recording's moving average, so a ride's last second before a stop
 * counts. An interval only counts from opening on, and a gap in the readings is not moving time, nor is
 * the time across a new connection, however short.
 */
final class RidingSpeed {
    /** Length of state(): seen, top, moving time, moving km/h-ms, last reading's time and speed, and its interval. */
    private static final int STATE_LENGTH = 7;
    private final long openedMs;
    private boolean seen;
    private double topKmh, movingMs, movingKmhMs, lastKmh;
    /** When the newest reading counted was measured, or -1 before any. */
    private long lastMs = -1;
    /** A new connection began after that reading: it still spots a repeat, but no interval starts from it. */
    private boolean unanchored;
    /** The readings' measured interval, an EMA like RidingValues', which decides what counts as a gap. */
    private double intervalMs = RidingValues.RIDE_NOMINAL_MS;

    RidingSpeed(long openedMs) { this.openedMs = openedMs; }

    /** Carries on from state(); saved is ignored unless it has that shape, and a time from a later boot is no baseline. */
    RidingSpeed(long openedMs, double[] saved, long nowMs) {
        this(openedMs);
        if (saved == null || saved.length != STATE_LENGTH) return;
        seen = saved[0] != 0;
        topKmh = saved[1];
        movingMs = saved[2];
        movingKmhMs = saved[3];
        lastMs = (long) saved[4];
        lastKmh = saved[5];
        intervalMs = saved[6];
        if (lastMs > nowMs) lastMs = -1;
    }

    long openedMs() { return openedMs; }

    /** Saved without the last time once unanchored: a restored collector has no repeats to spot. */
    synchronized double[] state() { return new double[]{seen ? 1 : 0, topKmh, movingMs, movingKmhMs, unanchored ? -1 : lastMs, lastKmh, intervalMs}; }

    /** A new connection: Max and Avg stand, and the next reading starts a new interval however soon it comes. */
    synchronized void connectionReset() { unanchored = lastMs >= 0; }

    /** One speed reading and when it was measured. Time across a dropout isn't counted, the same gap rule as the speed trace. */
    synchronized void accept(double kmh, long atMs) {
        if (!Double.isFinite(kmh) || atMs < 0 || atMs <= lastMs) return;
        long previousMs = lastMs;
        double previousKmh = lastKmh;
        boolean first = previousMs < 0 || unanchored;
        unanchored = false;
        seen = true;
        lastMs = atMs;
        lastKmh = kmh;
        if (kmh >= RidingFrames.STOPPED_KMH) topKmh = Math.max(topKmh, kmh);
        if (first) return;
        long stepMs = atMs - previousMs;
        boolean joined = stepMs <= Math.max(RidingValues.MIN_GAP_MS, RidingValues.GAP_INTERVALS * intervalMs);
        // A dropout is staleness, not cadence: it doesn't stretch the interval.
        if (stepMs <= RidingValues.GAP_INTERVALS * intervalMs)
            intervalMs = RidingValues.clampInterval(intervalMs * (1 - RidingValues.EMA_ALPHA) + stepMs * RidingValues.EMA_ALPHA);
        // A baseline from before this open: only the part of the interval after opening is this session's.
        long startMs = Math.max(previousMs, openedMs);
        if (!joined || atMs <= startMs) return;
        double startKmh = previousKmh + (kmh - previousKmh) * (startMs - previousMs) / stepMs;
        if (startKmh < RidingFrames.STOPPED_KMH && kmh < RidingFrames.STOPPED_KMH) return;
        movingMs += atMs - startMs;
        movingKmhMs += (startKmh + kmh) / 2 * (atMs - startMs);
    }

    /** Top speed since opening: null before the first speed reading, then 0 until something has moved. */
    synchronized Double maxKmh() { return seen ? topKmh : null; }

    /** Moving average since opening: null before the first reading or when it has moved for no measurable time, 0 when nothing has moved. */
    synchronized Double averageKmh() {
        if (!seen) return null;
        if (movingMs > 0) return movingKmhMs / movingMs;
        return topKmh > 0 ? null : Double.valueOf(0);
    }
}
