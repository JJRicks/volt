package com.jjricks.volt;

/**
 * How large the highlight's number may draw when its spot has room to spare (a tester, Nov 14: with the power bar
 * hidden the Clock stayed at its size beside empty space). The size is worked out from the widest value the reading
 * can show, drawn with tabular figures, and from the spot's size: never from the value on show, so it never changes
 * while riding. It changes only when the layout does (the spot's width or height, the power bar, the window, text
 * size, units, clock format or language).
 *
 * Only readings with a known widest value grow (see {@link #grows}). The others, such as a range that can read
 * "Almost empty" or a GPS state in words, keep the size they always had.
 */
final class RidingHighlightSize {
    private RidingHighlightSize() { }

    /** The most the highlight grows: this much over its designed size, and never past the big number's largest size. */
    static final float GROW_MAX = 1.3f;

    /**
     * Sample times for the clock's widest value, {hour, minute, second}: one morning and one evening (both AM/PM
     * markers), each with a two-figure hour in both 12- and 24-hour clocks. With figures of one width every time of day
     * in the same format is as wide as one of these, give or take its marker.
     */
    static final int[][] CLOCK_SAMPLES = {{10, 58, 58}, {22, 58, 58}};

    /** Sample stopwatch time for ride and recording time: a two-figure hour ("10:58:58"), wider than any shorter ride. */
    static final long STOPWATCH_SAMPLE_MS = ((10 * 60 + 58) * 60 + 58) * 1000L;

    /** Sample number for speeds: three whole figures and a tenth, as wide as any speed under 1000. */
    static final double SPEED_SAMPLE = 888.8;
    /** Sample battery percentage: three figures, as wide as 100 %. */
    static final long BATTERY_SAMPLE = 888;

    /**
     * True for the readings whose widest value is known, so their size can be worked out once: the clock, ride and
     * recording time, the battery and the speeds. Every other reading keeps its designed size.
     */
    static boolean grows(String content) {
        if (content == null) return false;
        switch (content) {
            case RidingLayout.CLOCK: case RidingLayout.RIDE_TIME: case RidingLayout.RECORDING_TIME: case RidingLayout.BATTERY:
            case RidingLayout.SPEED: case RidingLayout.AVERAGE_SPEED: case RidingLayout.MAX_SPEED:
                return true;
            default:
                return false;
        }
    }

    /**
     * The highlight number's largest size in px.
     *
     * @param widestPerPx the widest value's drawn width per px of number size (its widest sample, tabular figures)
     * @param heightPerPx the number's drawn height per px of size
     * @param width the room across, px
     * @param height the room down, px
     * @param basePx the size the spot was designed with
     * @param bigPx the big number's largest size: the highlight never draws larger
     * @return basePx, or a larger size at which the widest value still fits whole; never smaller than basePx
     */
    static float px(float widestPerPx, float heightPerPx, float width, float height, float basePx, float bigPx) {
        if (!(widestPerPx > 0) || !(heightPerPx > 0) || !(width > 0) || !(height > 0) || !(basePx > 0)) return basePx;
        float cap = Math.min(basePx * GROW_MAX, Math.max(basePx, bigPx));
        float fits = Math.min(width / widestPerPx, height / heightPerPx);
        // A spot that can't hold the widest value at its designed size keeps today's behaviour: the designed size.
        if (fits <= basePx) return basePx;
        return Math.min(cap, fits);
    }
}
