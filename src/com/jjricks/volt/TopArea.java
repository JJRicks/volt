package com.jjricks.volt;

/**
 * The height of Clean Home's top area (hub layout plan 1.6): one value for every moment (parked, charging,
 * just back), so the Lock bar and the quick actions below it never move. Pure Java.
 *
 * <p>A share of the window's usable height (Configuration.screenHeightDp, recomputed on every rebuild, which
 * folding and rotation trigger), at least 224 dp grown with large text (up to 1.3 times), at most 380 dp on
 * a phone and 460 dp on a wide window. The moment views fit themselves to it.
 */
final class TopArea {
    static final float PHONE_SHARE = 0.38f, WIDE_SHARE = 0.42f;
    static final int MIN_DP = 224, PHONE_MAX_DP = 380, WIDE_MAX_DP = 460;
    static final float MAX_TEXT_GROWTH = 1.3f;

    private TopArea() {}

    /** The top area's height in dp for a window screenHeightDp tall, with the app and system text scale combined. */
    static int heightDp(int screenHeightDp, boolean wide, float textScale) {
        float growth = Float.isNaN(textScale) ? 1f : Math.max(1f, Math.min(MAX_TEXT_GROWTH, textScale));
        int min = Math.round(MIN_DP * growth);
        int max = wide ? WIDE_MAX_DP : PHONE_MAX_DP;
        int share = Math.round(Math.max(0, screenHeightDp) * (wide ? WIDE_SHARE : PHONE_SHARE));
        return Math.max(min, Math.min(max, share));
    }

    /** The same height in pixels for a display density (pixels per dp). */
    static int heightPx(int screenHeightDp, boolean wide, float textScale, float density) {
        return Math.round(heightDp(screenHeightDp, wide, textScale) * density);
    }
}
