package com.jjricks.volt;

/**
 * ChargingFit: how Clean Home's charging view shares its slot, as the Layout Plan's clean-charging mock-up draws it
 * (the contributor's decisions on #383, 13 Nov 2026: the photo large behind the number, nothing dropped when space
 * runs out). The sizes are the ones the maintainers check on the emulator, 344x882 dp and 725x604 dp at 1x and 1.4x
 * text, plus 412x915 dp and a few narrower or shorter windows. Heights are in dp (density 1), derived from the same
 * constants the view uses:
 * <ul>
 *   <li>the slot: TopArea.heightDp for the window (wide by WideLayout.wide); once with the window's full height as
 *       screenHeightDp, once with 72 dp of system bars taken off. The view has no padding (it sits on the page);</li>
 *   <li>the width: the window less Home's 18 dp side padding, at most Clean's 560 dp column; the readings pair up
 *       from ChargingFit.PAIR_DP, else they stack with an 8 dp gap;</li>
 *   <li>text height: 1.17 times the text size without font padding (the line at ChargingFit.LINE_SP, the percentage
 *       at ChargingFit.PERCENT_SP, a tile's label at 12 sp), 1.33 times with it (a tile's value), times the text
 *       scale; the line wraps to two lines where "Charging to your 90% limit" (about 12.7 dp per sp) is wider than
 *       the slot;</li>
 *   <li>a tile: 10 dp padding above and below, the label line, 2 dp, the value (at least ChargingFit.VALUE_MIN_DP
 *       times the text scale); in the worst case its state pill wraps under the value.</li>
 * </ul>
 * The robustness checks run the same sizes with every height 30% taller.
 */
public final class ChargingFitTest {
    private static int checks;

    /** One window: its plan and the heights it was made from. */
    private static final class Case {
        final String name;
        final int inner, line, shortLine, percent, readings;
        final ChargingFit.Plan plan;
        Case(String name, int inner, int line, int shortLine, int percent, int readings) {
            this.name = name; this.inner = inner; this.line = line; this.shortLine = shortLine; this.percent = percent;
            this.readings = readings;
            plan = ChargingFit.plan(inner, line, shortLine, percent, readings, 1f);
        }
    }

    private static Case window(int width, int height, float text, int bars, boolean pill, float taller) {
        boolean wide = WideLayout.wide(width, text);
        int inner = TopArea.heightDp(height - bars, wide, text);
        int across = Math.min(width - 36, 560);
        int oneLine = Math.round(ChargingFit.LINE_SP * text * 1.17f * taller);
        int line = 12.7f * ChargingFit.LINE_SP * text > across ? 2 * oneLine : oneLine;
        int percent = Math.round(ChargingFit.PERCENT_SP * text * 1.17f * taller);
        float tile = 20 + 12 * text * 1.17f + 2 + Math.max(ChargingFit.VALUE_MIN_DP * text, ChargingFit.VALUE_SP * text * 1.33f)
                + (pill ? 11 * text * 1.17f + 6 + 4 : 0);
        int readings = Math.round((across >= ChargingFit.PAIR_DP ? tile : 2 * tile + 8) * taller);
        return new Case(width + "x" + height + " at " + text + "x" + (bars > 0 ? ", bars" : "") + (pill ? ", pill" : "")
                + (taller > 1 ? ", taller" : ""), inner, line, oneLine, percent, readings);
    }

    /** What must hold for every plan: it fills the slot exactly with the readings, and nothing overlaps. */
    private static void sound(Case c) {
        ChargingFit.Plan p = c.plan;
        int stacked = p.stacked(c.line, c.shortLine, ChargingFit.LINE_GAP_DP, c.readings);
        require(p.readings ? stacked == c.inner : stacked <= c.inner, c.name + ": stacks within the slot, to its bottom with the readings: " + p);
        int under = ChargingFit.TRACK_DP + ChargingFit.TICK_DP + ChargingFit.PHOTO_GAP_DP;
        if (p.photo()) {
            require(p.photoHeight >= ChargingFit.PHOTO_MIN_DP, c.name + ": the photo has its least height");
            require(p.photoHeight == p.stageHeight - under, c.name + ": the photo runs from the stage's top to 8 dp above the track: " + p);
        }
        if (p.percent && p.stageHeight > 0 && p.photo())
            require(ChargingFit.scaled(c.percent, p.percentScale) + under <= p.stageHeight,
                    c.name + ": the percentage stays clear of the track: " + p);
        if (p.readings && p.line) require(p.readingsGap == ChargingFit.READINGS_GAP_DP || p.readingsGap == ChargingFit.READINGS_GAP_MIN_DP,
                c.name + ": the readings keep the mock-up's 24 dp or at least 12 dp from the track");
        require(!p.percent || p.percentScale > 0 && p.percentScale <= 1, c.name + ": the percentage within its sizes");
        require(!p.shortLine || p.line, c.name + ": a short line is a line");
        require(p.line || !p.readings || !p.percent || p.percentScale <= ChargingFit.HALF_SCALE,
                c.name + ": dropping the line never enlarges the percentage past half: " + p);
        // The track never draws over the percentage: with the track, the number ends above its zone; without it, the
        // number fits the stage and nothing is drawn under it (no photo either).
        if (p.percent && p.track && p.stageHeight > 0)
            require(ChargingFit.scaled(c.percent, p.percentScale) + under <= p.stageHeight, c.name + ": the track stays under the percentage: " + p);
        if (!p.track) require(!p.photo() && (!p.percent || ChargingFit.scaled(c.percent, p.percentScale) <= p.stageHeight),
                c.name + ": without the track, the percentage fits the stage and no photo shows: " + p);
        // The percentage stays while anything shows (the contributor's pick).
        require(p.percent || p.stageHeight == 0 && !p.readings || c.inner == 0, c.name + ": the percentage stays while anything shows: " + p);
        require(!p.shortLine || c.shortLine < c.line, c.name + ": a line already on one line keeps its words: " + p);
    }

    /** The maintainers' sizes: nothing is dropped but the line (the percentage, photo, track and readings stay). */
    private static Case checked(int width, int height, float text, int bars, boolean pill) {
        Case c = window(width, height, text, bars, pill, 1);
        sound(c);
        require(c.plan.readings, c.name + ": Charging power and the time tile show");
        require(c.plan.percent && c.plan.photo(), c.name + ": the percentage and the photo behind it show: " + c.plan);
        require(c.plan.percentScale >= ChargingFit.HALF_SCALE || !c.plan.line, c.name + ": the line goes before the percentage is below half");
        System.out.println("  " + c.name + ": " + c.plan);
        return c;
    }

    public static void main(String[] args) {
        // 344x882 dp at 1x: everything at full size, the photo large behind the number (the mock-up's ~150 dp).
        Case c = checked(344, 882, 1f, 0, false);
        require(c.plan.line && !c.plan.shortLine && c.plan.percentScale == 1 && c.plan.readingsGap == ChargingFit.READINGS_GAP_DP,
                "344x882 at 1x: everything as the mock-up");
        require(c.plan.photoHeight >= 140 && c.plan.photoHeight <= 190, "344x882 at 1x: the photo is large, " + c.plan.photoHeight);
        require(c.plan.photoHeight > c.percent, "344x882 at 1x: the photo reaches up behind the number and below it");
        // 344x882 dp at 1.4x: the percentage, the photo, the track and both tiles stay.
        c = checked(344, 882, 1.4f, 0, false);
        require(c.plan.line && c.plan.photoHeight > c.percent, "344x882 at 1.4x: the line, and the photo behind the number");
        c = checked(344, 882, 1.4f, 72, true);
        c = checked(412, 915, 1f, 0, false);
        require(c.plan.line && c.plan.percentScale == 1 && c.plan.photoHeight >= 150, "412x915 at 1x: everything, a large photo");
        // 725x604 dp (wide at 1x): everything at full size.
        c = checked(725, 604, 1f, 0, false);
        require(c.plan.line && c.plan.percentScale == 1, "725x604 at 1x: the rest at full size");
        // 725x604 dp at 1.4x keeps the phone layout in a short area: the photo shrinks with the stage, the percentage
        // stays (smaller if it must), the readings show.
        c = checked(725, 604, 1.4f, 0, false);
        for (int bars : new int[]{0, 72})
            for (boolean pill : new boolean[]{false, true})
                for (float taller : new float[]{1f, 1.3f}) {
                    Case w = window(725, 604, 1.4f, bars, pill, taller);
                    require(w.plan.line || w.plan.percentScale <= ChargingFit.HALF_SCALE,
                            w.name + ": without the line, the percentage is at most half: " + w.plan);
                }
        for (int width : new int[]{344, 725})
            for (float text : new float[]{1f, 1.4f})
                for (int bars : new int[]{0, 72})
                    for (boolean pill : new boolean[]{false, true}) {
                        checked(width, width == 344 ? 882 : 604, text, bars, pill);
                        // Measured heights well above the estimates: the readings and the percentage still stay.
                        Case taller = window(width, width == 344 ? 882 : 604, text, bars, pill, 1.3f);
                        sound(taller);
                        require(taller.plan.readings && taller.plan.percent, taller.name + ": the readings and the percentage still show: " + taller.plan);
                    }

        // The full line with the space over the readings closed up is tried before "Charging" (council review
        // 5904036506, finding 5): a slot a little short keeps the words and closes the gap.
        c = new Case("wrapped line", 300, 40, 20, 70, 66);
        require(c.plan.line && !c.plan.shortLine && c.plan.percentScale == 1, "room for the wrapped line: it stays: " + c.plan);
        int fullNeeds = 40 + ChargingFit.LINE_GAP_DP + ChargingFit.READINGS_GAP_DP + 66 + 70 + 19;
        c = new Case("wrapped line, short slot", fullNeeds - 1, 40, 20, 70, 66);
        require(c.plan.line && !c.plan.shortLine && c.plan.readingsGap == ChargingFit.READINGS_GAP_MIN_DP && c.plan.percentScale == 1,
                "a slot 1 dp short: the gap closes, the line keeps its words: " + c.plan);
        int gapGain = ChargingFit.READINGS_GAP_DP - ChargingFit.READINGS_GAP_MIN_DP;
        c = new Case("wrapped line, shorter slot", fullNeeds - gapGain - 1, 40, 20, 70, 66);
        require(c.plan.shortLine && c.plan.readingsGap == ChargingFit.READINGS_GAP_DP && c.plan.percentScale == 1,
                "too short for the full line even with the gap closed: \"Charging\", the gap open again: " + c.plan);
        // A line already on one line (as tall as "Charging") never shortens: shortening it gains nothing.
        for (int inner = 300; inner >= 0; inner--) {
            Case one = new Case("one-line line, inner " + inner, inner, 18, 18, 70, 66);
            sound(one);
            require(!one.plan.shortLine, "a one-line line keeps its words, at " + inner + ": " + one.plan);
        }
        int oneNeeds = 18 + ChargingFit.LINE_GAP_DP + ChargingFit.READINGS_GAP_MIN_DP + 66 + 70 + 19;
        c = new Case("one-line line, gap closed", oneNeeds, 18, 18, 70, 66);
        require(c.plan.line && !c.plan.shortLine && c.plan.percentScale == 1 && c.plan.readingsGap == ChargingFit.READINGS_GAP_MIN_DP,
                "a one-line line with the gap closed keeps its words at full size: " + c.plan);
        // 280x640 dp at 1x (narrower than the maintainers test): the readings stack; the percentage and photo stay.
        c = window(280, 640, 1f, 0, false, 1);
        sound(c);
        System.out.println("  " + c.name + ": " + c.plan);
        require(c.plan.readings && c.plan.percent, "280x640 at 1x: the readings and the percentage stay");
        // Readings taller than the whole slot: the percentage shows over the track rather than a cut-off row.
        c = new Case("readings taller than the slot", 200, 18, 18, 84, 222);
        sound(c);
        require(!c.plan.readings && c.plan.percent, "readings taller than the slot: the percentage: " + c.plan);

        // The order things give way in, on one slot getting shorter: the line shortens, the gap closes, the photo
        // shrinks, the percentage shrinks to half, the line goes, the percentage shrinks further; the readings last.
        int line = 36, shortLine = 18, percent = 84, readings = 85;
        boolean shortened = false, closed = false, shrunk = false, lineGone = false, readingsGone = false, trackGone = false;
        float lastScale = 1;
        for (int inner = 420; inner >= 0; inner--) {
            Case s = new Case("inner " + inner, inner, line, shortLine, percent, readings);
            sound(s);
            ChargingFit.Plan p = s.plan;
            // The photo shows while the stage leaves it its least height (it is never dropped for anything else).
            if (p.readings && p.percent && p.stageHeight - ChargingFit.TRACK_DP - ChargingFit.TICK_DP - ChargingFit.PHOTO_GAP_DP >= ChargingFit.PHOTO_MIN_DP
                    && p.percentScale >= ChargingFit.PERCENT_MIN_SCALE && inner - readings >= p.stageHeight)
                require(p.photo() || !p.line && p.readingsGap == 0, "the photo stays while it has its least height, at " + inner);
            if (p.readingsGap == ChargingFit.READINGS_GAP_MIN_DP) closed = true;
            if (p.shortLine || !p.line) { require(closed, "the gap closes before the line shortens, at " + inner); shortened = true; }
            if (p.percentScale < 1 && p.readings) { require(closed || !p.line, "the gap closes before the percentage shrinks, at " + inner); shrunk = true; }
            if (!p.line) {
                require(shrunk || !p.readings, "the percentage shrinks before the line goes, at " + inner);
                lineGone = true;
            }
            if (p.line && p.percent) require(p.percentScale >= ChargingFit.HALF_SCALE, "with the line, the percentage is at least half, at " + inner);
            if (!p.readings) {
                require(inner - readings < Math.ceil(percent * ChargingFit.PERCENT_TINY_SCALE),
                        "the readings go only when the percentage would not fit over them at its least, at " + inner);
                readingsGone = true;
            } else {
                require(p.percent, "while the readings show, the percentage shows, at " + inner + ": " + p);
                // Council review 5904036506, finding 6: the percentage never grows as the slot shrinks.
                require(p.percentScale <= lastScale + 1e-6f, "the percentage never grows as the slot shrinks, at " + inner + ": " + p);
                lastScale = p.percentScale;
                if (!p.track) trackGone = true;
            }
            if (!p.track && p.readings) require(p.percentScale <= ChargingFit.PERCENT_MIN_SCALE,
                    "without the track, the percentage is no larger than its smallest with it, at " + inner);
        }
        require(shortened && closed && shrunk && lineGone && readingsGone && trackGone, "every step was reached");
        // Council review 5904036506, finding 7: stacked tiles at large text with the top area at its floor leave less
        // than 30% of the percentage's height; the percentage still shows over them, smaller, with nothing under it.
        c = new Case("stacked tiles, 208 dp floor", 208, 2 * 25, 25, 98, 186);
        sound(c);
        require(c.plan.readings && c.plan.percent && !c.plan.track && c.plan.percentScale < ChargingFit.PERCENT_MIN_SCALE,
                "the stacked tiles and a smaller percentage over them: " + c.plan);
        // Too short even for the percentage at its least over the readings: they make way, the percentage stays and is
        // no larger than it was over them.
        c = new Case("readings fit, percentage does not", 190, 50, 25, 98, 186);
        sound(c);
        require(c.plan.percent && !c.plan.readings && c.plan.percentScale <= ChargingFit.PERCENT_MIN_SCALE,
                "the percentage stays, the readings make way: " + c.plan);
        // A model with no photo: the percentage and the track are centred together in the stage (finding 9).
        require(ChargingFit.noPhotoTop(200, 70, 19) == 55 && ChargingFit.noPhotoTop(89, 70, 19) == 0 && ChargingFit.noPhotoTop(50, 70, 19) == 0,
                "no photo: the percentage and the track centred in the stage, never above its top");
        int least = (int) Math.ceil(percent * ChargingFit.PERCENT_TINY_SCALE);
        require(ChargingFit.plan(readings + least, line, shortLine, percent, readings, 1f).readings
                        && ChargingFit.plan(readings + least, line, shortLine, percent, readings, 1f).percent,
                "the readings' height and the percentage at its least keeps both");
        require(ChargingFit.plan(readings, line, shortLine, percent, readings, 1f).percent,
                "exactly the readings' height: the percentage stays (the readings make way for it)");
        require(ChargingFit.plan(260, line, shortLine, percent, readings, 1f).percentScale == 1
                        && ChargingFit.plan(260, line, shortLine, percent, readings, 2.625f).percentScale < 1,
                "constants scale with density: 260 px holds the full percentage at 1, not at 2.625");
        // The fixed time tile: the time to the scooter's own limit below 100%, else the time to full.
        require(ChargingFit.timeToLimit(90d, false), "a 90% limit: Time to 90%");
        require(!ChargingFit.timeToLimit(100d, false) && !ChargingFit.timeToLimit(null, false) && !ChargingFit.timeToLimit(Double.NaN, false),
                "no limit below 100%: Time to full");
        require(!ChargingFit.timeToLimit(80d, true), "a model that offers charge alerts has no limit: Time to full");
        tiles();
        System.out.println("ChargingFit: " + checks + " checks passed");
    }

    /**
     * The two reading tiles at 344x882 and 725x604 dp, 1x and 1.4x text: a pair in Home's row (the window less its 18 dp
     * side padding, at most Clean's 560 dp column, less the 8 dp between the tiles), or one tile across the row.
     * Clean's are the mock-up's: no icon, the 12 dp inset, the label a little smaller before it wraps. Widgets' keep
     * their rule: the icon and the 12 dp inset from 170 dp times the text scale, else no icon and 6 dp.
     */
    private static void tiles() {
        for (int window : new int[]{344, 725})
            for (float text : new float[]{1f, 1.4f}) {
                float row = Math.min(window - 36, 560);
                for (float width : new float[]{(row - 8) / 2, row}) {
                    String at = window + " dp at " + text + "x, a tile " + width + " dp wide";
                    ChargingFit.Tile clean = ChargingFit.tile(true, width, text);
                    require(!clean.icon, at + ": Clean's charging tile has no icon, as the mock-up: " + clean);
                    require(clean.insetDp == 12, at + ": Clean's charging tile keeps the plan's 12 dp inset: " + clean);
                    require(clean.labelMinScale == ChargingFit.TILE_LABEL_MIN_SCALE && clean.labelMinScale >= .85f && clean.labelMinScale < 1,
                            at + ": a Clean label a little too wide gets a little smaller before it wraps: " + clean);
                    ChargingFit.Tile widgets = ChargingFit.tile(false, width, text);
                    boolean wide = width >= 170 * text;
                    require(widgets.icon == wide && widgets.insetDp == (wide ? 12 : 6) && widgets.labelMinScale == 1,
                            at + ": Widgets' tile unchanged: " + widgets);
                }
            }
        // 344x882: Clean's pair is 150 dp each, narrow by Widgets' rule at either text size, and still has no icon.
        require(!ChargingFit.tile(false, 150, 1f).icon && ChargingFit.tile(false, 150, 1f).insetDp == 6, "a 150 dp Widgets tile is narrow");
        require(ChargingFit.tile(false, 170, 1f).icon && !ChargingFit.tile(false, 170, 1.4f).icon, "the narrow width grows with the text");
        require(!ChargingFit.tile(true, 600, 1f).icon && !ChargingFit.tile(true, 0, 2f).icon, "Clean's tiles never show an icon");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
