package com.jjricks.volt;

/**
 * How often BleController asks the scooter for its status (0x70), and how old the last answer may be before the
 * link counts as gone. Pure and Android-free, on the monotonic clock.
 *
 * With Volt open or a ride or charge recording (fast), it asks every 2 seconds; a status read older than
 * READY_AGE_MS makes BleController.ready() false (controls refuse), and SILENCE_FAST_MS of silence ends the
 * link. Out of sight it asks every 15 seconds and allows SILENCE_SLOW_MS of silence, so a 7 second window would
 * call a healthy background link lost for about 8 seconds of every 15. linked() judges the status age against
 * the cadence the link is on: the fast window in the foreground (the same answer as ready()), the background
 * silence limit out of sight.
 */
public final class StatusCadence {
    private StatusCadence() {}

    public static final long POLL_FAST_MS = 2_000L, POLL_SLOW_MS = 15_000L;
    public static final long SILENCE_FAST_MS = 12_000L, SILENCE_SLOW_MS = 35_000L;
    /** BleController.ready()'s own bound (CapabilityResolver.STATUS_MAX_AGE_MS). */
    public static final long READY_AGE_MS = 7_000L;

    /** The status request's period. */
    public static long pollMs(boolean fast) { return fast ? POLL_FAST_MS : POLL_SLOW_MS; }
    /** Silence (no frame, or no status) past this ends the link. */
    public static long silenceMs(boolean fast) { return fast ? SILENCE_FAST_MS : SILENCE_SLOW_MS; }
    /** How old the last status read may be while the link still counts as up on this cadence. */
    public static long linkedAgeMs(boolean fast) { return fast ? READY_AGE_MS : SILENCE_SLOW_MS; }

    /** Out of sight, how much older than its own rule a reading may be: one background poll's reply jitter. */
    public static final long READING_MARGIN_MS = 5_000L;
    /**
     * How old a reading may be while it still counts as live. In sight (fast) it is the reading's own rule, unchanged.
     * Out of sight a reading is only refreshed once per POLL_SLOW_MS, so it may be one background poll period plus
     * READING_MARGIN_MS old; a rule that is already longer (settings, device facts) stays as it is.
     */
    public static long readingFreshMs(long baseMs, boolean fast) {
        return fast ? baseMs : Math.max(baseMs, POLL_SLOW_MS + READING_MARGIN_MS);
    }

    /**
     * The link is up on this cadence. up: everything else ready() requires (work allowed, READY, authenticated,
     * a GATT client and a serial); statusMs: when the last status read arrived, 0 for none.
     */
    public static boolean linked(boolean up, long statusMs, long nowMono, boolean fast) {
        return up && statusMs > 0 && nowMono - statusMs <= linkedAgeMs(fast);
    }
}
