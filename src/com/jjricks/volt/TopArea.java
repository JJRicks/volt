package com.jjricks.volt;

/**
 * The height of Clean Home's top area (hub layout plan 1.6): one value for every moment (parked, charging,
 * just back), so the Lock bar and the quick actions below it never move between them (owner, #383: the fixed height
 * stands, with no large blank band in any moment). Pure Java.
 *
 * <p>On a phone it follows the window's width (Configuration.screenWidthDp, recomputed on every rebuild, which folding
 * and rotation trigger), so the parked card keeps the Layout Plan's proportions on every phone: 304 dp on the 344x882 dp
 * phone the mock-up is drawn on, which holds the charging view with its large photo and leaves the parked card no band
 * of 40 dp or more, and as much more as the phone is wider (the owner's first build on a 412 dp phone: a card sized by
 * the window's height had "way too much padding" there, its content the 344 dp size in a wider card). On a short phone
 * the height bounds it ({@link #PHONE_HEIGHT_CAP}, {@link #PHONE_SLACK_DP}), so the Lock bar and the quick actions keep
 * their room. A wide window (two panes) takes a share of its height. At least 224 dp grown with large text (up to 1.3 times), at most 380 dp on
 * a phone and 460 dp on a wide window.
 *
 * <p>On a short window, what has to show on first view comes first (checklist §4: the Lock bar and the quick
 * actions): the area never takes more than the window less {@link #reserveDp} (the header, the Lock bar, the quick
 * actions and, on a phone, the bottom bar), down to {@link #FLOOR_DP}. So large text does not grow the area past
 * what fits; the moment views give way inside it in their own order (ChargingFit, the battery card's fit).
 */
final class TopArea {
    /** A phone's area per dp of the window's width (304 of 344 dp); a wide window's per dp of its height. */
    static final float PHONE_WIDTH_SHARE = 304f / 344f, WIDE_SHARE = 0.42f;
    /**
     * A phone's area is at most this share of the window's height (the Layout Plan's card takes 38% of its screen), so a
     * short, wide phone (16:9, such as 411x731 dp) does not take the room the Lock bar and the quick actions need.
     */
    static final float PHONE_HEIGHT_CAP = 0.405f;
    /**
     * And it leaves at least this much (dp) beyond {@link #reserveDp}, which is an estimate: on 360x640 dp the quick
     * actions stay where main has them (the area at its minimum).
     */
    static final int PHONE_SLACK_DP = 40;
    static final int MIN_DP = 224, PHONE_MAX_DP = 380, WIDE_MAX_DP = 460;
    static final float MAX_TEXT_GROWTH = 1.3f;
    /**
     * What a phone window keeps under and over the area at 1x (dp): the status bar (24), the header (about 100), the
     * Lock bar (12 + 54), the quick actions' circles and names (12 + 54 + 6 + 16 + 14) and the bottom bar (88). The
     * parts made of text grow with it: {@link #RESERVE_TEXT_DP} more per 1x of text scale. A wide window has a rail
     * instead of the bottom bar: {@link #RAIL_SAVES_DP} less.
     */
    static final int RESERVE_DP = 380, RESERVE_TEXT_DP = 120, RAIL_SAVES_DP = 88;
    /**
     * The least the area gets on a short window: the charging view's readings still fit in their worst cases
     * (ChargingFitTest: the state pill wrapped under the time, at 1.4x text on 725x604 dp with the percentage and its
     * track over them, about 191 dp, and on a 280x640 dp phone with system bars, where the readings stack).
     */
    static final int FLOOR_DP = 208;

    private TopArea() {}

    /**
     * The top area's height in dp for a window screenWidthDp by screenHeightDp, with the app and system text scale
     * combined.
     */
    static int heightDp(int screenWidthDp, int screenHeightDp, boolean wide, float textScale) {
        float growth = Float.isNaN(textScale) ? 1f : Math.max(1f, Math.min(MAX_TEXT_GROWTH, textScale));
        int min = Math.round(MIN_DP * growth);
        int max = wide ? WIDE_MAX_DP : PHONE_MAX_DP;
        int height = Math.max(0, screenHeightDp);
        int share = wide ? Math.round(height * WIDE_SHARE)
                : Math.min(Math.round(Math.max(0, screenWidthDp) * PHONE_WIDTH_SHARE),
                        Math.min(Math.round(height * PHONE_HEIGHT_CAP), height - reserveDp(false, textScale) - PHONE_SLACK_DP));
        int wanted = Math.max(min, Math.min(max, share));
        return Math.max(FLOOR_DP, Math.min(wanted, height - reserveDp(wide, textScale)));
    }

    /** What the window keeps for everything that must show with the area on first view (dp). */
    static int reserveDp(boolean wide, float textScale) {
        float scale = Float.isNaN(textScale) ? 1f : Math.max(1f, Math.min(2f, textScale));
        return Math.round(RESERVE_DP + RESERVE_TEXT_DP * (scale - 1)) - (wide ? RAIL_SAVES_DP : 0);
    }

    /** The same height in pixels for a display density (pixels per dp). */
    static int heightPx(int screenWidthDp, int screenHeightDp, boolean wide, float textScale, float density) {
        return Math.round(heightDp(screenWidthDp, screenHeightDp, wide, textScale) * density);
    }

    /**
     * The parked card's photo, as the Layout Plan's mock-up places it (the contributor's decisions on #383): right of
     * the percentage and the range line, the square picture {@link #PARKED_PHOTO_DP} dp a side (the scooter about
     * 90 x 135 dp on a 344 dp phone). The words keep {@link #PARKED_WORDS_DP} dp times the text scale beside it, so
     * with large text the photo gets narrower rather than going; under {@link #PARKED_PHOTO_MIN_DP} it does not show.
     */
    static final int PARKED_PHOTO_DP = 144, PARKED_WORDS_DP = 120, PARKED_PHOTO_MIN_DP = 64, PARKED_PHOTO_GAP_DP = 10;

    /**
     * The parked card's content width and height (dp) on the phone the Layout Plan draws, 344x882 dp (the area's 304 dp
     * less the card's 12 dp padding above and below): there the card matches the mock-up, and every size below is the
     * one it had there.
     */
    static final float PARKED_BASE_CONTENT_DP = 280f, PARKED_BASE_HEIGHT_DP = 280f;
    /** The most the parked card's content grows: 1.3 times (the percentage 58 sp to 75 sp at 1x text). */
    static final float PARKED_MAX_GROWTH = 1.3f;

    /**
     * How much the parked card's content grows with the card (owner on #383, a first build on a 412 dp phone: the card
     * had "way too much padding"): as much as the card grew in both directions from the 344 dp phone, from 1 there up to
     * {@link #PARKED_MAX_GROWTH}. A phone's card grows in both (its area follows the window's width); a wide window's
     * pane, or a window too short for the area's share, gives no growth.
     */
    static float parkedGrowth(float contentDp, float cardDp) {
        if (Float.isNaN(contentDp) || Float.isNaN(cardDp)) return 1f;
        float grown = Math.min(contentDp / PARKED_BASE_CONTENT_DP, cardDp / PARKED_BASE_HEIGHT_DP);
        return Math.max(1f, Math.min(PARKED_MAX_GROWTH, grown));
    }

    /**
     * The percentage's growth: {@link #parkedGrowth}, but large text is large already: the text grows only as far as 1x
     * text grows at most, so at 1.3x text and above it keeps its size.
     */
    static float parkedTextGrowth(float contentDp, float cardDp, float textScale) {
        float scale = Float.isNaN(textScale) ? 1f : Math.max(1f, textScale);
        return Math.max(1f, Math.min(parkedGrowth(contentDp, cardDp), PARKED_MAX_GROWTH / scale));
    }

    /** The label, the range line and the readings grow half as much as the percentage. */
    static float parkedSmallGrowth(float growth) {
        return 1f + (Math.max(1f, growth) - 1f) / 2f;
    }

    /**
     * The parked photo's side (dp): {@link #PARKED_PHOTO_DP} on the 344 dp phone, growing as the card does ({@link
     * #parkedGrowth}), while the words keep their room beside it: {@link #PARKED_WORDS_DP} dp times the text scale and
     * the text's growth ({@code textGrowth}), or the range line's own width ({@code wordsDp}) when that is wider, so a
     * long line such as "about 124 km left" at 1.4x stays whole and the photo gets narrower instead. Under {@link
     * #PARKED_PHOTO_MIN_DP} it does not show (0).
     */
    static int parkedPhotoDp(float contentDp, float cardDp, float textScale, float textGrowth, float wordsDp) {
        float scale = Float.isNaN(textScale) ? 1f : Math.max(1f, textScale);
        float growth = Float.isNaN(textGrowth) ? 1f : Math.max(1f, Math.min(PARKED_MAX_GROWTH, textGrowth));
        float words = Math.max(PARKED_WORDS_DP * scale * growth, Float.isNaN(wordsDp) ? 0f : wordsDp);
        // A hair over the exact product, so the 344 dp card gives exactly 144 dp after flooring.
        int side = (int) Math.floor(Math.min(PARKED_PHOTO_DP * parkedGrowth(contentDp, cardDp) + 1e-3f,
                contentDp - PARKED_PHOTO_GAP_DP - words));
        return side >= PARKED_PHOTO_MIN_DP ? side : 0;
    }

    /**
     * Whether the grown sizes would drop the photo that the 344 dp sizes keep: a range line ({@code wordsDp} wide at the
     * grown size) too long for the photo beside it at {@code textGrowth}, but not at 1. The card then fits at the 344 dp
     * sizes, as it does when growing would cost the range line or a row of readings.
     */
    static boolean parkedGrowthDropsPhoto(float contentDp, float cardDp, float textScale, float textGrowth, float wordsDp) {
        if (!(textGrowth > 1f) || parkedPhotoDp(contentDp, cardDp, textScale, textGrowth, wordsDp) > 0) return false;
        return parkedPhotoDp(contentDp, cardDp, textScale, 1f, wordsDp / parkedSmallGrowth(textGrowth)) > 0;
    }

    /**
     * The parked photo's height (pixels) in the card's top row: the words' height ({@code row}) at least, growing into
     * the card's spare height up to its side, so it takes the mock-up's size where the area allows and never pushes
     * the range line or the readings out (they are fitted first, without it).
     */
    static int parkedPhotoHeight(int row, int spare, int side) {
        return Math.max(Math.max(0, row), Math.min(side, Math.max(0, row) + Math.max(0, spare)));
    }

    /**
     * How the parked battery card shares the height it has beyond its own (pixels, {@code extra} ≥ 0): above its top
     * row (the percentage and the photo), between that row and the bar, and between the bar and the readings, so the
     * readings sit along the card's bottom, weighted as the Layout Plan's mock-up spreads it (about 36, 37 and 20 dp
     * there), so the readings sit close under the bar and no gap becomes a band, instead of one band under the readings.
     */
    static int[] parkedGaps(int extra) {
        int e = Math.max(0, extra);
        int above = Math.round(e * .39f), under = Math.round(e * .22f);
        return new int[] {above, e - above - under, under};
    }

    /** The most any of the parked card's gaps may be (dp): under 40, so none reads as a band. */
    static final int PARKED_GAP_MAX_DP = 39;

    /**
     * {@link #parkedGaps(int)} where no gap passes {@code most} (pixels), counting the {@code around} pixels the bar
     * keeps above and below itself in the two gaps around it. What a gap cannot take goes above the top row first (where
     * the mock-up has its largest space), then to the other gaps, and only when all are full back above the top row.
     */
    static int[] parkedGaps(int extra, int most, int around) {
        int[] gaps = parkedGaps(extra);
        int max0 = Math.max(0, most), max12 = Math.max(0, most - Math.max(0, around));
        int[] limit = {max0, max12, max12};
        int over = 0;
        for (int i = 0; i < 3; i++)
            if (gaps[i] > limit[i]) { over += gaps[i] - limit[i]; gaps[i] = limit[i]; }
        for (int i = 0; i < 3 && over > 0; i++) {
            int room = Math.min(over, limit[i] - gaps[i]);
            gaps[i] += room;
            over -= room;
        }
        gaps[0] += over;
        return gaps;
    }
}
