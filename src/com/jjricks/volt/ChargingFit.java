package com.jjricks.volt;

/**
 * How Clean Home's charging view (DashboardScreen.ChargingHero in Clean's top area) shares its fixed-height slot.
 * Pure Java, so the JVM suite tests it at the sizes the maintainers check (tests/ChargingFitTest.java).
 *
 * <p>The view stacks, top to bottom: the muted "Charging" line, the big percentage, a stage (the scooter's photo,
 * which reaches up behind the percentage, over the track along the stage's bottom) and the readings row (charging
 * power beside the switching time tile). The readings are the point of the view, so they are the last thing to
 * give way. When the slot is short (a short window, a narrow one whose readings stack, large text), things give
 * way in this order:
 * <ol>
 *   <li>the photo (it needs {@link #PHOTO_MIN_DP} under the percentage);</li>
 *   <li>the percentage gets smaller, down to {@link #PERCENT_MIN_SCALE} of its size;</li>
 *   <li>the "Charging" line;</li>
 *   <li>the track;</li>
 *   <li>the percentage (the track comes back if it then fits);</li>
 *   <li>only then the readings, when the slot is shorter than the readings alone.</li>
 * </ol>
 * Every height given is in pixels, every constant in dp scaled by {@code density}. The plan stacks what it shows
 * with no overlap except the photo's own reach behind the percentage ({@link #OVERLAP_DP}, only while the photo
 * shows), and the stage (or, with no stage, the space above the readings) takes any height left over, so the
 * readings end at the slot's bottom and nothing is left empty under them.
 */
final class ChargingFit {
    /** The track's thickness, and the space above it when it follows the percentage directly. */
    static final int TRACK_DP = 8, TRACK_GAP_DP = 8;
    /** The space between the track and the readings: the view's own 12 dp padding, so the track never touches the tiles. */
    static final int READINGS_GAP_DP = 12;
    /** The stage under the percentage needs this much before the photo shows (it then reaches up behind the percentage). */
    static final int PHOTO_MIN_DP = 72, OVERLAP_DP = 40;
    /** The percentage never gets smaller than this share of its size (72 sp becomes 36 sp). */
    static final float PERCENT_MIN_SCALE = .5f;

    private ChargingFit() {}

    /** What the view shows, and how tall each part is. */
    static final class Plan {
        final boolean line, percent, photo, track, readings;
        /** The percentage's text size as a share of its full size (1 when it is not shrunk or not shown). */
        final float percentScale;
        /** The stage's height and top margin (negative: the photo's reach behind the percentage); 0 with no stage. */
        final int stageHeight, stageTopMargin;
        /** The space above the readings. */
        final int readingsGap;

        Plan(boolean line, boolean percent, float percentScale, boolean photo, boolean track, int stageHeight,
             int stageTopMargin, boolean readings, int readingsGap) {
            this.line = line; this.percent = percent; this.percentScale = percentScale; this.photo = photo;
            this.track = track; this.stageHeight = stageHeight; this.stageTopMargin = stageTopMargin;
            this.readings = readings; this.readingsGap = readingsGap;
        }

        /** The height the plan stacks to, given the same heights it was made from (the percentage at its scale). */
        int stacked(int lineHeight, int percentHeight, int readingsHeight) {
            return (line ? lineHeight : 0) + (percent ? scaled(percentHeight, percentScale) : 0)
                    + stageHeight + stageTopMargin + (readings ? readingsGap + readingsHeight : 0);
        }

        @Override public String toString() {
            return "line=" + line + " percent=" + (percent ? percentScale : 0) + " photo=" + photo + " track=" + track
                    + " stage=" + stageHeight + "@" + stageTopMargin + " readings=" + readings + " gap=" + readingsGap;
        }
    }

    /** A height at a share of its size, rounded down so a shrunk percentage never needs more than planned. */
    static int scaled(int height, float scale) { return (int) Math.floor(height * scale); }

    /**
     * The plan for a slot whose inside (less the view's padding) is {@code inner} pixels tall, with the line, the
     * full-size percentage and the readings row measured at the slot's width.
     */
    static Plan plan(int inner, int lineHeight, int percentHeight, int readingsHeight, float density) {
        int track = dp(TRACK_DP, density) + dp(TRACK_GAP_DP, density);
        int gap = dp(READINGS_GAP_DP, density);
        int photoMin = dp(PHOTO_MIN_DP, density), overlap = dp(OVERLAP_DP, density);
        int readings = gap + readingsHeight;
        int minPercent = Math.max(1, scaled(percentHeight, PERCENT_MIN_SCALE));
        inner = Math.max(0, inner);

        if (readingsHeight <= inner) {
            // 1. Everything at full size; the photo only with room for it under the percentage.
            int below = inner - lineHeight - percentHeight - readings;
            if (below >= photoMin) return new Plan(true, true, 1, true, true, below + overlap, -overlap, true, gap);
            if (below >= track) return new Plan(true, true, 1, false, true, below, 0, true, gap);
            // 2. The percentage gets smaller to keep the line and the track.
            int room = inner - lineHeight - track - readings;
            if (room >= minPercent) return shrunk(true, room, percentHeight, track, inner - lineHeight - readings, gap);
            // 3. Without the line.
            room = inner - track - readings;
            if (room >= minPercent) return shrunk(false, room, percentHeight, track, inner - readings, gap);
            // 4. Without the track: the percentage, then the readings.
            room = inner - readings;
            if (room >= minPercent) return shrunk(false, room, percentHeight, 0, inner - readings, gap);
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
