package com.jjricks.volt;

/**
 * How Clean Home's charging view (DashboardScreen.ChargingHero in Clean's top area) shares its fixed-height slot, as
 * the Layout Plan's clean-charging mock-up draws it (the contributor's decisions on #383, 13 Nov 2026). Pure Java, so
 * the JVM suite tests it at the sizes the maintainers check (tests/ChargingFitTest.java).
 *
 * <p>The view sits on the page background and stacks, top to bottom: the muted "Charging to your N% limit" line,
 * centred; a stage holding the big percentage at its top, centred, with the scooter's photo large under it and
 * reaching up behind the number (a soft dark fade behind the number keeps it readable), the red glow under the wheels
 * and the track along the stage's bottom; then the two readings (charging power and the time to the limit or to
 * full). The photo takes whatever height the stage has, so it is as large as the slot allows.
 *
 * <p>When the slot is short (a short window, large text), nothing is dropped while the readings fit: the percentage,
 * the photo behind it, the track and the readings all stay. Things give way in this order:
 * <ol>
 *   <li>the space over the readings closes from {@link #READINGS_GAP_DP} to {@link #READINGS_GAP_MIN_DP};</li>
 *   <li>the line, where it wraps, shortens to one line ("Charging"), first with the space open again; a line that
 *       already fits on one line keeps its words (shortening it gains nothing);</li>
 *   <li>the photo gets smaller (it is the stage less the track, so it shrinks with it);</li>
 *   <li>the percentage gets smaller, down to {@link #HALF_SCALE} of its size;</li>
 *   <li>the line makes way (the owner's ruling for 344x882 dp at 1.4x: the percentage stays, the line may go); the
 *       percentage stays at most half its size, so the height the line frees goes to the photo;</li>
 *   <li>the percentage gets smaller still, down to {@link #PERCENT_MIN_SCALE} of its size;</li>
 *   <li>the track and the space over the readings make way: the percentage stays over the readings at
 *       {@link #PERCENT_MIN_SCALE} of its size (it never grows as the slot shrinks), then smaller, down to
 *       {@link #PERCENT_TINY_SCALE}; nothing is drawn under it.</li>
 * </ol>
 * The readings never go (the contributor's answer 2: the tiles stay): in a slot too short for the
 * percentage even at {@link #PERCENT_TINY_SCALE} over them, the percentage gets smaller still and goes only when the
 * readings fill the slot; a slot shorter than the readings shows them alone, from its top. Nothing that went comes
 * back as the slot shrinks: the percentage never grows and the photo never returns. The photo
 * shows while it is at least {@link #PHOTO_MIN_DP} tall. Every height given is in pixels, every constant in dp scaled
 * by {@code density}. The stage takes any height left over, so the readings end at the slot's bottom.
 */
final class ChargingFit {
    /** The track's thickness (the mock-up's 7 dp) and how far the limit's tick reaches above and below it. */
    static final int TRACK_DP = 7, TICK_DP = 4;
    /** The space between the line and the stage (the top of the percentage and of the photo). */
    static final int LINE_GAP_DP = 8;
    /** The space between the scooter's wheels and the track, and between a stage-filling percentage and the track. */
    static final int PHOTO_GAP_DP = 8;
    /** The space between the track and the readings: the mock-up's 24 dp, closing to 12 dp on a short slot. */
    static final int READINGS_GAP_DP = 24, READINGS_GAP_MIN_DP = 12;
    /** The photo's least height; below it the photo does not show. */
    static final int PHOTO_MIN_DP = 40;
    /**
     * Clean's charging readings as the Layout Plan draws them (152 x 66 dp tiles): the value's text size (sp) and its
     * least height (dp, both times the text scale), and the row width from which the two tiles pair up (dp, at any text
     * scale: a pair at 1.4x text keeps the percentage in view on a 344 dp phone).
     */
    static final int VALUE_SP = 21, VALUE_MIN_DP = 28, PAIR_DP = 240;
    /** The percentage's text size (sp, before the text scale): the Layout Plan's 60 sp, as on the battery card. */
    static final int PERCENT_SP = 60;
    /** The line's text size (sp, before the text scale): the mock-up's 15 sp. */
    static final float LINE_SP = 15;
    /** The percentage keeps half its size while the line shows; without the line it may get as small as this share. */
    static final float HALF_SCALE = .5f, PERCENT_MIN_SCALE = .3f;
    /** The least share of its size the percentage takes over the readings once the track has made way (9 sp at 1x, about 13 sp at 1.4x). */
    static final float PERCENT_TINY_SCALE = .15f;

    private ChargingFit() {}

    /** What the view shows, and how tall each part is. */
    static final class Plan {
        /** The line shows; shortLine: as its one-line short form ("Charging"). */
        final boolean line, shortLine;
        /** The percentage shows (always while anything does), at a share of its full size. */
        final boolean percent;
        final float percentScale;
        /** The stage's height (the percentage, the photo, the glow and the track); 0 with no stage. */
        final int stageHeight;
        /** The photo's height in the stage (from the stage's top down to the wheels); 0 when it does not show. */
        final int photoHeight;
        /** The track shows along the stage's bottom (false once it has made way for the percentage). */
        final boolean track;
        /** The readings show, and the space above them. */
        final boolean readings;
        final int readingsGap;

        Plan(boolean line, boolean shortLine, boolean percent, float percentScale, int stageHeight, int photoHeight,
             boolean track, boolean readings, int readingsGap) {
            this.line = line; this.shortLine = shortLine; this.percent = percent; this.percentScale = percentScale;
            this.stageHeight = stageHeight; this.photoHeight = photoHeight; this.track = track; this.readings = readings;
            this.readingsGap = readingsGap;
        }

        boolean photo() { return photoHeight > 0; }

        /** The height the plan stacks to, given the heights it was made from and the line gap. */
        int stacked(int lineHeight, int shortLineHeight, int lineGap, int readingsHeight) {
            return (line ? (shortLine ? shortLineHeight : lineHeight) + lineGap : 0) + stageHeight
                    + (readings ? readingsGap + readingsHeight : 0);
        }

        @Override public String toString() {
            return "line=" + (line ? shortLine ? "short" : "full" : "none") + " percent=" + (percent ? percentScale : 0)
                    + " stage=" + stageHeight + " photo=" + photoHeight + " track=" + track + " readings=" + readings + " gap=" + readingsGap;
        }
    }

    /** A height at a share of its size, rounded down so a shrunk percentage never needs more than planned. */
    static int scaled(int height, float scale) { return (int) Math.floor(height * scale); }

    /**
     * The plan for a slot {@code inner} pixels tall, with the line (as it wraps at the slot's width), its one-line short
     * form, the full-size percentage and the readings row measured at the slot's width.
     */
    static Plan plan(int inner, int lineHeight, int shortLineHeight, int percentHeight, int readingsHeight, float density) {
        int lineGap = dp(LINE_GAP_DP, density), gap = dp(READINGS_GAP_DP, density), gapMin = dp(READINGS_GAP_MIN_DP, density);
        // The track zone at the stage's bottom: the track, its tick's reach under it, and the gap above it.
        int under = dp(TRACK_DP + TICK_DP + PHOTO_GAP_DP, density);
        int photoMin = dp(PHOTO_MIN_DP, density);
        inner = Math.max(0, inner);
        shortLineHeight = Math.min(shortLineHeight, lineHeight);
        // A line already on one line is as short as "Charging": it keeps its words.
        boolean shortens = shortLineHeight < lineHeight;
        int full = lineHeight + lineGap, brief = shortLineHeight + lineGap;

        if (readingsHeight <= inner) {
            // 1-4. The percentage at full size: the line as it is with the readings' gap full, then closed up; then
            // the short line with the gap full, then closed up.
            int[][] tries = {{full, gap, 0}, {full, gapMin, 0}, {brief, gap, 1}, {brief, gapMin, 1}};
            for (int[] t : tries) {
                int stage = inner - t[0] - t[1] - readingsHeight;
                if (stage >= percentHeight + under) return plan(true, t[2] == 1 && shortens, 1, stage, under, photoMin, true, t[1]);
            }
            // 5. The percentage smaller, to half its size, under the short line.
            int stage = inner - brief - gapMin - readingsHeight;
            float scale = share(stage - under, percentHeight);
            if (scale >= HALF_SCALE) return plan(true, shortens, scale, stage, under, photoMin, true, gapMin);
            // 6-7. Without the line: the percentage at most the half that step 5 could not reach (dropping the line
            // never enlarges it; the freed height goes to the photo), down to its smallest.
            stage = inner - gapMin - readingsHeight;
            scale = share(stage - under, percentHeight);
            if (scale >= PERCENT_MIN_SCALE) return plan(false, false, Math.min(HALF_SCALE, scale), stage, under, photoMin, true, gapMin);
            // 8. Too short for the percentage at its smallest with the track: the track, the photo and the gap make
            // way and the percentage stays over the readings, no larger than step 7's smallest (so it never grows as
            // the slot shrinks), then smaller down to its least, and smaller still in the very shortest slots. The
            // readings stay (the contributor's answer 2: the tiles stay), and the height never goes back to a photo.
            stage = inner - readingsHeight;
            scale = share(stage, percentHeight);
            if (scale >= PERCENT_TINY_SCALE) return new Plan(false, false, true, Math.min(PERCENT_MIN_SCALE, scale), stage, 0, false, true, 0);
            return new Plan(false, false, stage > 0, Math.min(PERCENT_TINY_SCALE, scale), Math.max(0, stage), 0, false, true, 0);
        }
        // 9. Not even the readings fit: they stay, from the slot's top (the percentage, already gone at a slot as tall
        // as they are, does not come back, and no photo or track takes the height).
        return new Plan(false, false, false, 0, 0, 0, false, true, 0);
    }

    /**
     * Where the percentage sits in a stage {@code stage} pixels tall when the model has no photo: the percentage
     * ({@code percent} tall) and the track zone under it ({@code under}) are centred together in the stage, so the
     * stage keeps its height (the top area's height is fixed) without an empty photo-sized band between the number and
     * the track. Returns the percentage's top; the track's zone ends {@code percent + under} below it. With a photo,
     * or no room to spare, it is 0 (the percentage at the stage's top, the track at its bottom).
     */
    static int noPhotoTop(int stage, int percent, int under) {
        return Math.max(0, (stage - percent - under) / 2);
    }

    /** A stage of this height with the percentage at this share: the photo fills it down to the track zone. */
    private static Plan plan(boolean line, boolean shortLine, float scale, int stage, int under, int photoMin,
                             boolean readings, int gap) {
        int photo = stage - under;
        return new Plan(line, shortLine, true, scale, stage, photo >= photoMin ? photo : 0, true, readings, gap);
    }

    /**
     * Clean's second tile is fixed, as the mock-up draws it (no switching): "Time to N%" while the scooter's own charge
     * limit is below 100% (the line says "Charging to your N% limit"), otherwise "Time to full (est.)". A charge alert's
     * level is not a limit (ChargeTimeReadings.limitSource), so a model that offers alerts shows the time to full.
     */
    static boolean timeToLimit(Double scooterTarget, boolean alertsOffered) {
        return ChargeTimeReadings.limitSource(scooterTarget, alertsOffered, false) == ChargeTimeReadings.SCOOTER_LIMIT;
    }

    /** A reading tile's inner space at its sides (dp): the Layout Plan's 12 dp, and Widgets' 6 dp in a narrow tile. */
    static final int TILE_INSET_DP = 12, TILE_NARROW_INSET_DP = 6;
    /** Widgets' reading tiles are narrow below this width (dp, times the text scale): the icon goes, the inset halves. */
    static final int TILE_NARROW_DP = 170;
    /** The least share of its size a Clean charging tile's label takes to stay on one line before it wraps. */
    static final float TILE_LABEL_MIN_SCALE = .85f;

    /** How a reading tile lays out its label row. */
    static final class Tile {
        /** The inset at the tile's sides (dp). */
        final int insetDp;
        /** Whether the small icon shows before the label. */
        final boolean icon;
        /** The least share of its size the label takes to stay on one line (1: it wraps at its size). */
        final float labelMinScale;

        Tile(int insetDp, boolean icon, float labelMinScale) {
            this.insetDp = insetDp; this.icon = icon; this.labelMinScale = labelMinScale;
        }

        @Override public String toString() { return "inset=" + insetDp + " icon=" + icon + " labelMin=" + labelMinScale; }
    }

    /**
     * A reading tile {@code widthDp} wide at {@code textScale} (the app's and the system's together). Clean's two
     * charging tiles ({@code clean}) are the mock-up's: no icon at any width (the contributor's decisions, round 3, on
     * #383), the 12 dp inset kept at every width and text size, and a label a little too wide gets a little smaller
     * (down to {@link #TILE_LABEL_MIN_SCALE}) before it wraps. Widgets' tiles are unchanged: below
     * {@link #TILE_NARROW_DP} dp times the text scale the icon goes and the inset is 6 dp; the label wraps at its size.
     */
    static Tile tile(boolean clean, float widthDp, float textScale) {
        if (clean) return new Tile(TILE_INSET_DP, false, TILE_LABEL_MIN_SCALE);
        boolean narrow = widthDp < TILE_NARROW_DP * textScale;
        return new Tile(narrow ? TILE_NARROW_INSET_DP : TILE_INSET_DP, !narrow, 1f);
    }

    private static float share(int room, int height) { return height <= 0 ? 1 : Math.max(0, room) / (float) height; }

    private static int dp(int value, float density) { return Math.round(value * density); }
}
