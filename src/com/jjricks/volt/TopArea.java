package com.jjricks.volt;

/**
 * The height of Clean Home's top area (hub layout plan 1.6): one value for every moment (parked, charging,
 * just back), so the Lock bar and the quick actions below it never move (owner, #383: the fixed height stands,
 * with no large blank band in any moment). Pure Java.
 *
 * <p>A share of the window's height (Configuration.screenHeightDp, recomputed on every rebuild, which folding and
 * rotation trigger), at least 224 dp grown with large text (up to 1.3 times), at most 380 dp on a phone and 460 dp
 * on a wide window. The phone share is the Layout Plan's proportion: about 304 dp on a 344x882 dp phone, which holds
 * the charging view with the owner's photo size (6849341616) and leaves the parked card no band of 40 dp or more.
 *
 * <p>On a short window, what has to show on first view comes first (checklist §4: the Lock bar and the quick
 * actions): the area never takes more than the window less {@link #reserveDp} (the header, the Lock bar, the quick
 * actions and, on a phone, the bottom bar), down to {@link #FLOOR_DP}. So large text does not grow the area past
 * what fits; the moment views give way inside it in their own order (ChargingFit, the battery card's fit).
 */
final class TopArea {
    static final float PHONE_SHARE = 0.345f, WIDE_SHARE = 0.42f;
    static final int MIN_DP = 224, PHONE_MAX_DP = 380, WIDE_MAX_DP = 460;
    static final float MAX_TEXT_GROWTH = 1.3f;
    /**
     * What a phone window keeps under and over the area at 1x (dp): the status bar (24), the header (about 80), the
     * Lock bar (12 + 54), the quick actions' circles and names (12 + 54 + 6 + 16 + 14) and the bottom bar (88). The
     * parts made of text grow with it: {@link #RESERVE_TEXT_DP} more per 1x of text scale. A wide window has a rail
     * instead of the bottom bar: {@link #RAIL_SAVES_DP} less.
     */
    static final int RESERVE_DP = 360, RESERVE_TEXT_DP = 120, RAIL_SAVES_DP = 88;
    /** The least the area gets on a short window: the charging percentage (smaller) over the readings still fit. */
    static final int FLOOR_DP = 192;

    private TopArea() {}

    /** The top area's height in dp for a window screenHeightDp tall, with the app and system text scale combined. */
    static int heightDp(int screenHeightDp, boolean wide, float textScale) {
        float growth = Float.isNaN(textScale) ? 1f : Math.max(1f, Math.min(MAX_TEXT_GROWTH, textScale));
        int min = Math.round(MIN_DP * growth);
        int max = wide ? WIDE_MAX_DP : PHONE_MAX_DP;
        int height = Math.max(0, screenHeightDp);
        int share = Math.round(height * (wide ? WIDE_SHARE : PHONE_SHARE));
        int wanted = Math.max(min, Math.min(max, share));
        return Math.max(FLOOR_DP, Math.min(wanted, height - reserveDp(wide, textScale)));
    }

    /** What the window keeps for everything that must show with the area on first view (dp). */
    static int reserveDp(boolean wide, float textScale) {
        float scale = Float.isNaN(textScale) ? 1f : Math.max(1f, Math.min(2f, textScale));
        return Math.round(RESERVE_DP + RESERVE_TEXT_DP * (scale - 1)) - (wide ? RAIL_SAVES_DP : 0);
    }

    /** The same height in pixels for a display density (pixels per dp). */
    static int heightPx(int screenHeightDp, boolean wide, float textScale, float density) {
        return Math.round(heightDp(screenHeightDp, wide, textScale) * density);
    }

    /**
     * How the parked battery card shares the height it has beyond its own (pixels, {@code extra} ≥ 0): above its top
     * row (the percentage and the photo), between that row and the bar, and between the bar and the readings, so the
     * readings sit along the card's bottom and the space is spread about evenly, as the Layout Plan's mock-up spreads
     * it (about 36, 37 and 20 dp there), instead of one band under the readings.
     */
    static int[] parkedGaps(int extra) {
        int e = Math.max(0, extra);
        int above = Math.round(e * .34f), under = Math.round(e * .33f);
        return new int[] {above, e - above - under, under};
    }
}
