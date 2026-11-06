package com.jjricks.volt;

/**
 * OLED burn-in care for riding mode, which can hold the same bright layout on screen for an hour.
 *
 * The plan's 1-2 px shift every few minutes keeps a static edge on the same couple of pixels for
 * most of a ride. This policy is stronger and still unnoticeable:
 *  - The whole layout drifts on a slow Lissajous orbit, up to AMPLITUDE_DP each way (about 22 px of
 *    travel on a typical phone). The two periods don't share a factor, so over a ride the path
 *    covers the whole square instead of retracing one line. At its fastest the drift is about
 *    0.07 dp a second, so a drawn edge moves by single pixels several seconds apart.
 *  - Static chrome (labels, units, dividers, outlines; never the live numbers or gauges) dims to
 *    CHROME_DIM after CHROME_FULL_MS, over CHROME_RAMP_MS, so the fade itself isn't seen.
 * The riding view keeps AMPLITUDE_DP of margin on every edge so the drift never clips.
 */
final class RidingBurnIn {
    static final float AMPLITUDE_DP = 4f;
    static final long PERIOD_X_MS = 11 * 60_000L, PERIOD_Y_MS = 7 * 60_000L;
    static final long CHROME_FULL_MS = 3 * 60_000L, CHROME_RAMP_MS = 60_000L;
    static final float CHROME_DIM = 0.8f;

    private RidingBurnIn() { }

    /** Layout offset in dp, {x, y}, for the time since riding mode opened. Starts at the centre. */
    static float[] offsetDp(long elapsedMs) {
        double t = Math.max(0, elapsedMs);
        double x = AMPLITUDE_DP * Math.sin(2 * Math.PI * t / PERIOD_X_MS);
        double y = AMPLITUDE_DP * Math.sin(2 * Math.PI * t / PERIOD_Y_MS);
        return new float[]{(float) x, (float) y};
    }

    /** Alpha multiplier for static chrome, from 1 down to CHROME_DIM. */
    static float chromeAlpha(long elapsedMs) {
        if (elapsedMs <= CHROME_FULL_MS) return 1f;
        double ramp = Math.min(1, (elapsedMs - CHROME_FULL_MS) / (double) CHROME_RAMP_MS);
        // Smoothstep, so the fade starts and ends gently.
        double eased = ramp * ramp * (3 - 2 * ramp);
        return (float) (1 - (1 - CHROME_DIM) * eased);
    }
}
