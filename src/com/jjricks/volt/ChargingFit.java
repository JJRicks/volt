package com.jjricks.volt;

/**
 * How Clean Home's charging view (DashboardScreen.ChargingHero in Clean's top area) shares its fixed-height slot.
 * Pure Java, so the JVM suite tests it at the sizes the maintainers check (tests/ChargingFitTest.java).
 *
 * <p>The view stacks, top to bottom: the muted "Charging" line, the big percentage, a stage (the scooter's photo
 * beside the percentage, over the track along the stage's bottom) and the readings row (charging power beside the
 * switching time tile). With room for everything, the percentage sits in the middle of the space between the line
 * and the track, and the stage starts right under the line, so the photo can run from 8 dp under the line to 8 dp
 * above the track beside the percentage (owner's photo rule, #383 6849341616; ChargingPhoto places it). The readings
 * are the point of the view, so they are the last thing to give way. When the slot is short (a short window, a
 * narrow one whose readings stack, large text), things give way in this order:
 * <ol>
 *   <li>the photo (it needs {@link #PHOTO_MIN_DP} between its two 8 dp margins);</li>
 *   <li>the percentage gets smaller, down to {@link #HALF_SCALE} of its size;</li>
 *   <li>the "Charging" line;</li>
 *   <li>the percentage gets smaller still, down to {@link #PERCENT_MIN_SCALE} of its size, with the track kept under
 *       it, so it stays in view where the readings stack under large text (344x882 dp at 1.4x, part 8's review);</li>
 *   <li>the track (the percentage, back at half its size or more, takes its room);</li>
 *   <li>the percentage (the track comes back if it then fits);</li>
 *   <li>only then the readings, when the slot is shorter than the readings alone.</li>
 * </ol>
 * Every height given is in pixels, every constant in dp scaled by {@code density}. The plan stacks what it shows
 * with no overlap except the stage's reach up beside the percentage (only while the photo shows; the photo itself
 * never overlaps the percentage), and the stage (or, with no stage, the space above the readings) takes any height
 * left over, so the readings end at the slot's bottom and nothing is left empty under them.
 */
final class ChargingFit {
    /** The track's thickness, and the space above it when it follows the percentage directly. */
    static final int TRACK_DP = 8, TRACK_GAP_DP = 8;
    /** The space between the track and the readings: the view's own 12 dp padding, so the track never touches the tiles. */
    static final int READINGS_GAP_DP = 12;
    /** The photo's least height between its margins, and its margin under the line and above the track. */
    static final int PHOTO_MIN_DP = 72, PHOTO_PAD_DP = 8;
    /**
     * Clean's charging readings as the Layout Plan draws them (152 x 66 dp tiles): the value's text size (sp) and its
     * least height (dp, both times the text scale), and the row width from which the two tiles pair up (dp, at any text
     * scale: a pair at 1.4x text keeps the percentage in view on a 344 dp phone).
     */
    static final int VALUE_SP = 21, VALUE_MIN_DP = 28, PAIR_DP = 240;
    /** The percentage's text size (sp, before the text scale): the Layout Plan's 60 sp, as on the battery card. */
    static final int PERCENT_SP = 60;
    /**
     * The line stays only while the percentage can keep half its size (72 sp becomes 36 sp), and without the track the
     * percentage keeps at least half its size too; with the track under it and no line, it may get as small as
     * {@link #PERCENT_MIN_SCALE} (72 sp becomes about 22 sp, still above the readings' labels).
     */
    static final float HALF_SCALE = .5f, PERCENT_MIN_SCALE = .3f;

    private ChargingFit() {}

    /** What the view shows, and how tall each part is. */
    static final class Plan {
        final boolean line, percent, photo, track, readings;
        /** The percentage's text size as a share of its full size (1 when it is not shrunk or not shown). */
        final float percentScale;
        /** The stage's height and top margin (negative: its reach up beside the percentage); 0 with no stage. */
        final int stageHeight, stageTopMargin;
        /** The space above the readings, and above the percentage (it sits in the middle of its room). */
        final int readingsGap, percentTopMargin;

        Plan(boolean line, boolean percent, float percentScale, boolean photo, boolean track, int stageHeight,
             int stageTopMargin, boolean readings, int readingsGap) {
            this(line, percent, percentScale, photo, track, stageHeight, stageTopMargin, readings, readingsGap, 0);
        }

        Plan(boolean line, boolean percent, float percentScale, boolean photo, boolean track, int stageHeight,
             int stageTopMargin, boolean readings, int readingsGap, int percentTopMargin) {
            this.line = line; this.percent = percent; this.percentScale = percentScale; this.photo = photo;
            this.track = track; this.stageHeight = stageHeight; this.stageTopMargin = stageTopMargin;
            this.readings = readings; this.readingsGap = readingsGap; this.percentTopMargin = percentTopMargin;
        }

        /** The height the plan stacks to, given the same heights it was made from (the percentage at its scale). */
        int stacked(int lineHeight, int percentHeight, int readingsHeight) {
            return (line ? lineHeight : 0) + (percent ? percentTopMargin + scaled(percentHeight, percentScale) : 0)
                    + stageHeight + stageTopMargin + (readings ? readingsGap + readingsHeight : 0);
        }

        @Override public String toString() {
            return "line=" + line + " percent=" + (percent ? percentScale : 0) + "@" + percentTopMargin + " photo=" + photo
                    + " track=" + track + " stage=" + stageHeight + "@" + stageTopMargin + " readings=" + readings + " gap=" + readingsGap;
        }
    }

    /** A height at a share of its size, rounded down so a shrunk percentage never needs more than planned. */
    static int scaled(int height, float scale) { return (int) Math.floor(height * scale); }

    /**
     * The plan for a slot whose inside (less the view's padding) is {@code inner} pixels tall, with the line, the
     * full-size percentage and the readings row measured at the slot's width.
     */
    static Plan plan(int inner, int lineHeight, int percentHeight, int readingsHeight, float density) {
        int trackOnly = dp(TRACK_DP, density), track = trackOnly + dp(TRACK_GAP_DP, density);
        int gap = dp(READINGS_GAP_DP, density);
        int photoMin = dp(PHOTO_MIN_DP, density), pad = dp(PHOTO_PAD_DP, density);
        int readings = gap + readingsHeight;
        // Rounded up, so the scale a room gives is never below the smallest share.
        int minPercent = Math.max(1, (int) Math.ceil(percentHeight * PERCENT_MIN_SCALE));
        int halfPercent = Math.max(1, (int) Math.ceil(percentHeight * HALF_SCALE));
        inner = Math.max(0, inner);

        if (readingsHeight <= inner) {
            // 1. Everything at full size: the percentage in the middle of the room between the line and the track. The
            // stage runs from under the line to the track's bottom, beside the percentage, so the photo (when that is
            // tall enough) spans it with 8 dp to spare at each end; with no photo the stage starts under the percentage.
            int room = inner - lineHeight - readings - track;
            if (room >= percentHeight) {
                int stage = inner - lineHeight - readings, above = (room - percentHeight) / 2;
                if (stage - trackOnly - 2 * pad >= photoMin)
                    return new Plan(true, true, 1, true, true, stage, -(above + percentHeight), true, gap, above);
                return new Plan(true, true, 1, false, true, stage - above - percentHeight, 0, true, gap, above);
            }
            // 2. The percentage gets smaller (to half its size) to keep the line and the track.
            if (room >= halfPercent) return shrunk(true, room, percentHeight, track, inner - lineHeight - readings, gap);
            // 3. Without the line: the percentage as large as fits, down to its smallest.
            room = inner - track - readings;
            if (room >= minPercent) return shrunk(false, room, percentHeight, track, inner - readings, gap);
            // 4. Without the track: the percentage at half its size or more, then the readings.
            room = inner - readings;
            if (room >= halfPercent) return shrunk(false, room, percentHeight, 0, inner - readings, gap);
            // 5. The readings alone, with the track above them when it fits.
            if (inner - readings >= track) return new Plan(false, false, 1, false, true, inner - readings, 0, true, gap);
            return new Plan(false, false, 1, false, false, 0, 0, true, inner - readingsHeight);
        }
        // 6. Not even the readings fit: the percentage (as large as fits) and the track, nothing else.
        int room = inner - track;
        if (room >= minPercent) return shrunk(false, room, percentHeight, track, inner, 0, false);
        if (inner >= minPercent) return shrunk(false, inner, percentHeight, 0, inner, 0, false);
        return new Plan(false, false, 1, false, false, 0, 0, false, 0);
    }

    private static Plan shrunk(boolean line, int room, int percentHeight, int track, int left, int gap) {
        return shrunk(line, room, percentHeight, track, left, gap, true);
    }

    /** The percentage as large as {@code room} allows (at most its size); the stage takes what is left of {@code left}. */
    private static Plan shrunk(boolean line, int room, int percentHeight, int track, int left, int gap, boolean readings) {
        float scale = percentHeight <= 0 ? 1 : Math.min(1f, room / (float) percentHeight);
        int shown = scaled(percentHeight, scale);
        int stage = track == 0 ? 0 : Math.max(track, left - shown);
        // With no stage, what is left goes above the readings, so they stay at the slot's bottom.
        if (track == 0 && readings) gap += Math.max(0, left - shown);
        return new Plan(line, true, scale, false, track > 0, stage, 0, readings, gap);
    }

    private static int dp(int value, float density) { return Math.round(value * density); }
}
