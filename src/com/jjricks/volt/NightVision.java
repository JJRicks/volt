package com.jjricks.volt;

/**
 * Night vision's look and brightness (ride-display plan, PR 3). The colour matrix turns everything
 * riding mode draws into shades of red, weighted so red accents stay visible rather than plain
 * luminance; it always draws over the dark palette, since a light page turned red is a red floodlight.
 * Under night vision, Volt auto and Fixed use a dim range: their level maps onto 0.01 to 0.35. Phone
 * keeps Android's brightness. Pure Java.
 */
final class NightVision {
    private NightVision() { }

    /** Android's 4×5 ColorMatrix: R' = 0.60R + 0.35G + 0.10B, G' = 0.05R + 0.03G, B' = 0, alpha kept. */
    static final float[] MATRIX = {
            0.60f, 0.35f, 0.10f, 0, 0,
            0.05f, 0.03f, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 1, 0,
    };

    static final float FLOOR = 0.01f, CEILING = 0.35f;

    /** A window level (0.05..1, or below 0 for the phone's own) under night vision. */
    static float level(float day) {
        if (day < 0 || Float.isNaN(day)) return day;
        float clamped = BrightnessCurve.clamp(day);
        return FLOOR + (clamped - BrightnessCurve.FLOOR) / (1 - BrightnessCurve.FLOOR) * (CEILING - FLOOR);
    }

    /** The colour a pixel (0..255 per channel) becomes. */
    static int[] filter(int red, int green, int blue) {
        return new int[]{
                Math.min(255, Math.round(MATRIX[0] * red + MATRIX[1] * green + MATRIX[2] * blue)),
                Math.min(255, Math.round(MATRIX[5] * red + MATRIX[6] * green)),
                0,
        };
    }
}
