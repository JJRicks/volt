package com.jjricks.volt;

/**
 * Night vision's fade (NightFade, owner Nov 6): the colour matrix and the window level blend from the
 * normal look (0) to night vision (1); the ends are exact; the level stays between its two end states
 * and moves one way only, so a fade never brightens the screen; Phone mode passes through; the system's
 * animation scale 0 switches at once. Listed in tests/l10n/riding-night-fade.sources.
 */
public final class NightFadeTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};

    public static void main(String[] args) {
        matrixEnds();
        matrixBlend();
        levels();
        noOvershoot();
        phoneMode();
        instant();
        between();
        System.out.println("NightFadeTest: " + checks + " checks passed (blend, ends, no brightness overshoot, Phone mode, animations off)");
    }

    private static void matrixEnds() {
        float[] zero = NightFade.matrix(0), one = NightFade.matrix(1);
        check(zero.length == 20 && one.length == 20, "4×5 matrices");
        for (int i = 0; i < 20; i++) {
            check(zero[i] == IDENTITY[i], "amount 0 is the identity at " + i);
            check(one[i] == NightVision.MATRIX[i], "amount 1 is night vision at " + i);
            check(NightFade.matrix(-3)[i] == IDENTITY[i] && NightFade.matrix(Float.NaN)[i] == IDENTITY[i], "below 0 and NaN clamp to 0 at " + i);
            check(NightFade.matrix(7)[i] == NightVision.MATRIX[i], "above 1 clamps at " + i);
        }
    }

    private static void matrixBlend() {
        float[] half = NightFade.matrix(0.5f);
        for (int i = 0; i < 20; i++) check(Math.abs(half[i] - (IDENTITY[i] + NightVision.MATRIX[i]) / 2) < 1e-6, "half-way blend at " + i);
        check(half[18] == 1, "alpha kept through the fade");
        // Each pixel's channels move one way between the two looks: a white pixel's red, green and blue.
        float previousG = 2, previousB = 2;
        for (int step = 0; step <= 100; step++) {
            float[] m = NightFade.matrix(step / 100f);
            float g = m[5] + m[6] + m[7], b = m[10] + m[11] + m[12];
            check(g <= previousG + 1e-6 && b <= previousB + 1e-6, "white's green and blue only fall, step " + step);
            previousG = g; previousB = b;
        }
    }

    private static void levels() {
        check(NightFade.level(0.62f, 0) == 0.62f, "amount 0 keeps the day level");
        check(NightFade.level(0.62f, 1) == NightVision.level(0.62f), "amount 1 is the night level");
        check(Math.abs(NightFade.level(0.62f, 1) - 0.214f) < 0.001f, "Fixed 0.62 at night is about 0.214");
        check(NightFade.level(1f, 1) == NightVision.CEILING, "full at night is the ceiling");
        check(NightFade.level(BrightnessCurve.FLOOR, 1) == NightVision.FLOOR, "the floor at night is the night floor");
        float mid = NightFade.level(0.62f, 0.5f);
        check(Math.abs(mid - (0.62f + NightVision.level(0.62f)) / 2) < 1e-6, "half-way level");
    }

    private static void noOvershoot() {
        for (int d = 0; d <= 200; d++) {
            float day = BrightnessCurve.FLOOR + (1 - BrightnessCurve.FLOOR) * d / 200f;
            float night = NightVision.level(day), hi = Math.max(day, night), lo = Math.min(day, night);
            float previous = day;
            for (int step = 0; step <= 200; step++) {
                float level = NightFade.level(day, step / 200f);
                check(level <= hi + 1e-6 && level >= lo - 1e-6, "level " + level + " leaves [" + lo + ", " + hi + "] for day " + day);
                check(level <= previous + 1e-6, "fading on only dims, day " + day + " step " + step);
                previous = level;
            }
            // Fading off from part-way (a tap mid-fade) still only rises to the day level.
            float from = 0.4f, previousOff = NightFade.level(day, from);
            for (int step = 0; step <= 50; step++) {
                float level = NightFade.level(day, NightFade.between(from, 0, step / 50f));
                check(level >= previousOff - 1e-6 && level <= day + 1e-6, "fading off only brightens to the day level, day " + day);
                previousOff = level;
            }
        }
    }

    private static void phoneMode() {
        for (float a : new float[]{0, 0.3f, 1}) check(NightFade.level(-1, a) == -1, "Phone mode passes through at " + a);
        check(Float.isNaN(NightFade.level(Float.NaN, 0.5f)), "NaN passes through");
    }

    private static void instant() {
        check(NightFade.instant(0), "animations off (scale 0) switch at once");
        check(NightFade.instant(-1) && NightFade.instant(Float.NaN) && NightFade.instant(Float.POSITIVE_INFINITY), "unreadable scales switch at once");
        check(!NightFade.instant(1) && !NightFade.instant(0.5f) && !NightFade.instant(10), "a normal, fast or slow scale fades");
        check(NightFade.FADE_MS >= 300 && NightFade.FADE_MS <= 600, "the fade takes 300-600 ms");
    }

    private static void between() {
        check(NightFade.between(0, 1, 0) == 0 && NightFade.between(0, 1, 1) == 1, "ends");
        check(Math.abs(NightFade.between(1, 0, 0.25f) - 0.75f) < 1e-6, "fading off");
        check(Math.abs(NightFade.between(0.4f, 1, 0.5f) - 0.7f) < 1e-6, "from part-way");
        check(NightFade.between(0, 1, 2) == 1 && NightFade.between(0, 1, -1) == 0, "progress clamps");
        check(NightFade.clamp(Float.NaN) == 0 && NightFade.clamp(2) == 1 && NightFade.clamp(0.3f) == 0.3f, "clamp");
    }
}
