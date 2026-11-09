package com.jjricks.volt;

/**
 * Night vision's fade (owner, Nov 6): switching on or off blends from the normal look to the red one
 * over FADE_MS instead of snapping. An amount of 0 is the normal look, 1 is full night vision. The colour
 * matrix and the window level both follow the amount, and the level always stays between its two end
 * states, so a fade never brightens the screen. With the system's animations off it switches at once.
 * Pure Java.
 */
final class NightFade {
    private NightFade() { }

    /** How long a fade takes at the system's normal animation speed (ValueAnimator scales it). */
    static final long FADE_MS = 450;

    private static final float[] IDENTITY = {
            1, 0, 0, 0, 0,
            0, 1, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, 1, 0,
    };

    static float clamp(float amount) {
        if (Float.isNaN(amount) || amount <= 0) return 0;
        return Math.min(1, amount);
    }

    /** The 4×5 colour matrix at this amount: the identity blended towards NightVision.MATRIX. */
    static float[] matrix(float amount) {
        float a = clamp(amount);
        float[] out = new float[20];
        for (int i = 0; i < 20; i++) out[i] = IDENTITY[i] * (1 - a) + NightVision.MATRIX[i] * a;
        return out;
    }

    /**
     * A window level at this amount, between the day level and its night level (NightVision.level). Phone
     * mode (below 0) passes through: night vision never takes over Android's brightness.
     */
    static float level(float day, float amount) {
        if (day < 0 || Float.isNaN(day)) return day;
        float a = clamp(amount);
        if (a == 0) return day;
        float night = NightVision.level(day);
        if (a == 1) return night;
        return day + (night - day) * a;
    }

    /** The system's animator duration scale: 0 (animations off) or anything unreadable switches at once. */
    static boolean instant(float animatorScale) {
        return !(animatorScale > 0) || Float.isInfinite(animatorScale);
    }

    /** The amount part-way through a fade from one amount to another (progress 0..1). */
    static float between(float from, float to, float progress) {
        float p = clamp(progress);
        return clamp(from) + (clamp(to) - clamp(from)) * p;
    }
}
