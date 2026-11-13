package com.jjricks.volt;

/**
 * ChargingFit: how Clean Home's charging view shares its slot (council review 5886927849, finding 1). The sizes are
 * the ones the maintainers check on the emulator, 344x882 dp and 725x604 dp at 1x and 1.4x text, plus 320x640 dp at
 * 1x. Heights are in dp (density 1), derived from the same constants the view uses:
 * <ul>
 *   <li>the slot: TopArea.heightDp for the window (wide by WideLayout.wide), less the view's 12 dp top and bottom
 *       padding; once with the window's full height as screenHeightDp, once with 72 dp of system bars taken off;</li>
 *   <li>the width: the window less Home's 18 dp side padding (MainActivity's scroller), at most Clean's 560 dp
 *       column, less the view's 14 dp side padding; Clean's readings pair up from ChargingFit.PAIR_DP (240 dp at any
 *       text scale), else they stack with an 8 dp gap;</li>
 *   <li>text height: 1.17 times the text size without font padding (the line at 12.5 sp, the percentage at
 *       ChargingFit.PERCENT_SP, a tile's label at 12 sp), 1.33 times with it (a tile's value, up to
 *       ChargingFit.VALUE_SP), all times the text scale;</li>
 *   <li>a tile: 10 dp padding above and below, the label line, 2 dp, the value (at least ChargingFit.VALUE_MIN_DP
 *       times the text scale); in the worst case its state pill wraps under the value (11 sp text, 3 dp padding above
 *       and below, 4 dp under it).</li>
 * </ul>
 * The view measures the real heights; these estimates stand in for them here, and the robustness checks run the
 * same sizes with every height 30% taller.
 */
public final class ChargingFitTest {
    private static int checks;

    /** One window: its plan and the heights it was made from. */
    private static final class Case {
        final String name;
        final int inner, line, percent, readings;
        final ChargingFit.Plan plan;
        Case(String name, int inner, int line, int percent, int readings) {
            this.name = name; this.inner = inner; this.line = line; this.percent = percent; this.readings = readings;
            plan = ChargingFit.plan(inner, line, percent, readings, 1f);
        }
    }

    private static Case window(int width, int height, float text, int bars, boolean pill, float taller) {
        boolean wide = WideLayout.wide(width, text);
        int inner = TopArea.heightDp(height - bars, wide, text) - 24;
        int across = Math.min(width - 36, 560) - 28;
        int line = Math.round(12.5f * text * 1.17f * taller);
        int percent = Math.round(ChargingFit.PERCENT_SP * text * 1.17f * taller);
        float tile = 20 + 12 * text * 1.17f + 2 + Math.max(ChargingFit.VALUE_MIN_DP * text, ChargingFit.VALUE_SP * text * 1.33f)
                + (pill ? 11 * text * 1.17f + 6 + 4 : 0);
        int readings = Math.round((across >= ChargingFit.PAIR_DP ? tile : 2 * tile + 8) * taller);
        return new Case(width + "x" + height + " at " + text + "x" + (bars > 0 ? ", bars" : "") + (pill ? ", pill" : "")
                + (taller > 1 ? ", taller" : ""), inner, line, percent, readings);
    }

    /**
     * A window as the emulator measured it in part 8's review, before Clean's readings paired at large text: they pair
     * up only from 280 dp times the text scale (InfoTiles' usual rule, so at 344 dp and 1.4x they stack, as they still
     * do on a narrower window), and the line and the percentage are 1.33 times their text size (font padding).
     */
    private static Case stacked(int width, int height, float text, int bars, boolean pill) {
        boolean wide = WideLayout.wide(width, text);
        int inner = TopArea.heightDp(height - bars, wide, text) - 24;
        int across = Math.min(width - 36, 560) - 28;
        int line = Math.round(12.5f * text * 1.33f);
        int percent = Math.round(72 * text * 1.33f);
        float tile = 20 + 12 * text * 1.33f + 2 + Math.max(48, 26 * text * 1.33f) + (pill ? 11 * text * 1.33f + 6 + 4 : 0);
        int readings = Math.round(across >= 280 * text ? tile : 2 * tile + 8);
        return new Case(width + "x" + height + " at " + text + "x, as measured" + (bars > 0 ? ", bars" : "") + (pill ? ", pill" : ""),
                inner, line, percent, readings);
    }

    /** What must hold for every plan: it fills the slot exactly and nothing sits on anything else. */
    private static void sound(Case c) {
        ChargingFit.Plan p = c.plan;
        int stacked = p.stacked(c.line, c.percent, c.readings);
        require(p.readings ? stacked == c.inner : stacked <= c.inner, c.name + ": stacks within the slot, to its bottom with the readings: " + p);
        require(p.stageTopMargin == 0 || p.photo, c.name + ": only the photo's stage reaches up beside the percentage");
        if (p.photo) {
            require(p.stageHeight - ChargingFit.TRACK_DP - 2 * ChargingFit.PHOTO_PAD_DP >= ChargingFit.PHOTO_MIN_DP,
                    c.name + ": the photo has its least height between its 8 dp margins");
            require(p.stageTopMargin == -(p.percentTopMargin + c.percent) && p.line,
                    c.name + ": the photo's stage starts right under the line, beside the percentage: " + p);
            require(p.stageHeight + p.stageTopMargin >= ChargingFit.TRACK_DP + ChargingFit.TRACK_GAP_DP,
                    c.name + ": the track stays under the percentage");
        }
        require(p.percentTopMargin >= 0 && (p.percentTopMargin == 0 || p.percentScale == 1),
                c.name + ": the percentage is centred only at its full size");
        if (p.track) require(p.stageHeight + Math.min(0, p.stageTopMargin) >= ChargingFit.TRACK_DP + ChargingFit.TRACK_GAP_DP,
                c.name + ": the track has its own room");
        if (p.readings && p.track) require(p.readingsGap == ChargingFit.READINGS_GAP_DP, c.name + ": the track keeps 12 dp from the tiles");
        require(!p.percent || p.percentScale >= ChargingFit.PERCENT_MIN_SCALE && p.percentScale <= 1, c.name + ": the percentage within its sizes");
    }

    /** The maintainers' sizes: both readings, the percentage (maybe smaller) and the track always show. */
    private static Case checked(int width, int height, float text, int bars, boolean pill) {
        Case c = window(width, height, text, bars, pill, 1);
        sound(c);
        require(c.plan.readings, c.name + ": Charging power and the time tile show");
        require(c.plan.percent && c.plan.track, c.name + ": the percentage and the track show: " + c.plan);
        System.out.println("  " + c.name + ": " + c.plan);
        return c;
    }

    public static void main(String[] args) {
        // 344x882 dp: everything with the photo at 1x, and at 1.4x too now that the readings pair up there (the photo
        // still gives way first wherever the room runs out, see the stacked cases below).
        Case c = checked(344, 882, 1f, 0, false);
        require(c.plan.photo && c.plan.line && c.plan.percentScale == 1, "344x882 at 1x: everything, with the photo");
        // The owner's photo size (6849341616): about 167 dp of scooter at 344 dp wide, the stage less its margins.
        int scooter = c.plan.stageHeight - ChargingFit.TRACK_DP - 2 * ChargingFit.PHOTO_PAD_DP;
        require(scooter >= 160 && scooter <= 180, "344x882 at 1x: about 167 dp of scooter, " + scooter);
        c = checked(344, 882, 1.4f, 0, false);
        require(c.plan.line && c.plan.percentScale == 1, "344x882 at 1.4x: the line and the full percentage");
        c = checked(344, 882, 1.4f, 72, true);
        require(c.plan.line, "344x882 at 1.4x, bars and a wrapped pill: the line stays");
        c = checked(412, 915, 1f, 0, false);
        scooter = c.plan.stageHeight - ChargingFit.TRACK_DP - 2 * ChargingFit.PHOTO_PAD_DP;
        require(c.plan.photo && scooter >= 170 && scooter <= 190, "412x915 at 1x: about 179 dp of scooter, " + scooter);
        // 725x604 dp (wide at 1x): the photo shows beside the percentage (there is room between the line and the bar).
        c = checked(725, 604, 1f, 0, false);
        require(c.plan.photo && c.plan.line && c.plan.percentScale == 1, "725x604 at 1x: the photo, the rest at full size");
        c = checked(725, 604, 1f, 72, true);
        require(c.plan.line && !c.plan.photo, "725x604 at 1x, bars and a wrapped pill: the photo gives way first, the line stays");
        // 725x604 dp at 1.4x keeps the phone layout, and the area is what the window leaves under the header with the
        // Lock bar and the quick actions in view (TopArea): no photo, the percentage stays (smaller), the readings show.
        c = checked(725, 604, 1.4f, 0, false);
        require(!c.plan.photo && c.plan.percentScale < 1, "725x604 at 1.4x: the photo gives way, the percentage stays, smaller");
        c = checked(725, 604, 1.4f, 0, true);
        require(!c.plan.photo && c.plan.percentScale < 1, "725x604 at 1.4x with a wrapped pill: the percentage gets smaller");
        for (int width : new int[]{344, 725})
            for (float text : new float[]{1f, 1.4f})
                for (int bars : new int[]{0, 72})
                    for (boolean pill : new boolean[]{false, true}) {
                        checked(width, width == 344 ? 882 : 604, text, bars, pill);
                        // Measured heights well above the estimates: the readings still stay.
                        Case taller = window(width, width == 344 ? 882 : 604, text, bars, pill, 1.3f);
                        sound(taller);
                        require(taller.plan.readings, taller.name + ": the readings still show: " + taller.plan);
                    }

        // 344x882 dp at 1.4x as the emulator measured it in part 8's review (5887950282): InfoTiles pairs the readings
        // only from 280 dp times the text scale, so at 1.4x they stack, and the percentage's height includes its font
        // padding. The percentage must stay in view there, smaller (owner); the "Charging" line may go.
        for (int bars : new int[]{0, 72})
            for (boolean pill : new boolean[]{false, true}) {
                Case m = stacked(344, 882, 1.4f, bars, pill);
                sound(m);
                System.out.println("  " + m.name + ": " + m.plan);
                require(m.plan.readings && !m.plan.photo, m.name + ": the readings show, the photo gave way first");
                // With a wrapped pill as well, the stacked readings leave less than the track and the smallest
                // percentage need: the readings come first there.
                if (pill) continue;
                require(m.plan.percent && m.plan.percentScale < 1 && m.plan.percentScale >= ChargingFit.PERCENT_MIN_SCALE,
                        m.name + ": the percentage stays, smaller: " + m.plan);
            }
        // The band the emulator saw above the stacked readings (about 60 dp, of a 311 or 284 dp slot): the percentage
        // shows in it, smaller, with the track under it, never the line, up to where it could be half its size. Its smallest size with the track needs 50 dp.
        int big = Math.round(ChargingFit.PERCENT_SP * 1.4f * 1.33f), smallest = (int) Math.ceil(big * ChargingFit.PERCENT_MIN_SCALE);
        for (int inner : new int[]{311, 284})
            for (int band = ChargingFit.TRACK_DP + ChargingFit.TRACK_GAP_DP + smallest; band < ChargingFit.TRACK_DP + ChargingFit.TRACK_GAP_DP + big / 2; band++) {
                Case m = new Case("emulator band " + band + " of " + inner, inner, Math.round(12.5f * 1.4f * 1.33f), big,
                        inner - ChargingFit.READINGS_GAP_DP - band);
                sound(m);
                require(m.plan.readings && m.plan.percent && m.plan.track && !m.plan.line && !m.plan.photo
                        && m.plan.percentScale < ChargingFit.HALF_SCALE, m.name + ": the percentage stays, smaller, over the track: " + m.plan);
            }
        // The line goes only once the percentage would be smaller than half its size.
        c = new Case("line kept at half", 286, 20, 100, 200 - 12);
        require(c.plan.line && c.plan.percentScale == ChargingFit.HALF_SCALE, "the line stays while the percentage can be half its size: " + c.plan);
        c = new Case("line gone below half", 285, 20, 100, 200 - 12);
        require(!c.plan.line && c.plan.percentScale > ChargingFit.HALF_SCALE, "below half, the line goes and the percentage takes its room: " + c.plan);

        // 280x640 dp at 1x (narrower than the maintainers test; 320 dp now pairs Clean's readings): the readings stack,
        // so they and the track stay and the percentage gives way after the line.
        c = window(280, 640, 1f, 0, false, 1);
        sound(c);
        System.out.println("  " + c.name + ": " + c.plan);
        require(c.plan.readings && c.plan.track && !c.plan.line && !c.plan.photo, "280x640 at 1x: the readings and the track stay");
        c = window(280, 640, 1f, 72, false, 1);
        sound(c);
        System.out.println("  " + c.name + ": " + c.plan);
        require(c.plan.readings && !c.plan.line && !c.plan.photo, "280x640 at 1x with bars: the readings stay, the line and photo go");
        c = window(280, 640, 1f, 72, true, 1);
        sound(c);
        System.out.println("  " + c.name + ": " + c.plan);
        require(c.plan.readings && !c.plan.photo, "280x640 at 1x, bars and a wrapped pill: the readings stay");
        c = window(320, 640, 1f, 0, false, 1);
        sound(c);
        System.out.println("  " + c.name + ": " + c.plan);
        require(c.plan.readings && c.plan.track && c.plan.percent, "320x640 at 1x: the readings, the track and the percentage");
        // Stacked readings taller than the whole slot: they can't show without the slot growing, and the percentage
        // shows with the track rather than a cut-off row.
        c = new Case("readings taller than the slot", 200, 15, 84, 222);
        sound(c);
        require(!c.plan.readings && c.plan.percent && c.plan.track, "readings taller than the slot: the percentage and the track: " + c.plan);

        // The order things give way in, on one slot getting shorter: photo, percentage size, line, track, percentage, readings.
        int line = 15, percent = 84, readings = 85;
        int stage = 0;
        boolean photoGone = false, shrunk = false, lineGone = false, trackGone = false, percentGone = false;
        for (int inner = 400; inner >= 0; inner--) {
            Case s = new Case("inner " + inner, inner, line, percent, readings);
            sound(s);
            ChargingFit.Plan p = s.plan;
            if (!p.photo) photoGone = true; else require(!photoGone, "the photo never comes back on a shorter slot");
            if (p.percentScale < 1) { require(photoGone, "the photo goes before the percentage shrinks at " + inner); shrunk = true; }
            if (!p.line) { require(shrunk, "the percentage shrinks before the line goes at " + inner); lineGone = true; }
            if (!p.track && p.percent) { require(lineGone, "the line goes before the track at " + inner); trackGone = true; }
            if (!p.percent) { require(trackGone || lineGone, "the percentage goes after the line and the track at " + inner); percentGone = true; }
            if (!p.readings) require(percentGone && inner < readings, "the readings go last, only when they alone don't fit, at " + inner);
            else require(inner >= readings, "the readings show whenever they fit alone, at " + inner);
            stage++;
        }
        require(photoGone && shrunk && lineGone && trackGone && percentGone && stage == 401, "every step was reached");
        require(ChargingFit.plan(readings, line, percent, readings, 1f).readings, "exactly the readings' height keeps them");
        require(ChargingFit.plan(330, line, percent, readings, 1f).photo && !ChargingFit.plan(330, line, percent, readings, 2.625f).photo,
                "constants scale with density: 330 px holds the photo at 1, not at 2.625");
        System.out.println("ChargingFit: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
