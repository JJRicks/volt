package com.jjricks.volt;

/**
 * The Ride page's Record ride tile (RideRecordTile) and the charge track on Battery & charging's summary card
 * (ChargeTrackShape), as the owner's Layout Plan draws them.
 */
public final class HubPageTilesTest {
    private static int checks;

    public static void main(String[] args) {
        recordTile();
        chargeTrack();
        tileNames();
        summaryFit();
        trackTick();
        historyBars();
        pageRows();
        System.out.println("HubPageTiles: " + checks + " checks passed");
    }

    private static void recordTile() {
        // The look: a recording ride wins (the old button hid while one recorded), then simulation, then saving.
        require(RideRecordTile.look(false, false, false) == RideRecordTile.Look.START, "Idle: Record ride");
        require(RideRecordTile.look(true, false, false) == RideRecordTile.Look.RECORDING
                && RideRecordTile.look(true, true, true) == RideRecordTile.Look.RECORDING, "Recording wins");
        require(RideRecordTile.look(false, true, false) == RideRecordTile.Look.SIMULATING
                && RideRecordTile.look(false, true, true) == RideRecordTile.Look.SIMULATING, "Simulating before saving");
        require(RideRecordTile.look(false, false, true) == RideRecordTile.Look.SAVING, "Saving");
        // Taps, as the Start recording button allowed: start only with storage and a ready scooter; simulation's exit always.
        require(RideRecordTile.taps(RideRecordTile.Look.START, true, true), "Ready: a tap starts a ride");
        require(!RideRecordTile.taps(RideRecordTile.Look.START, false, true) && !RideRecordTile.taps(RideRecordTile.Look.START, true, false),
                "No storage or not ready: nothing");
        require(RideRecordTile.taps(RideRecordTile.Look.SIMULATING, false, false), "Simulating: a tap exits simulation, ready or not");
        require(!RideRecordTile.taps(RideRecordTile.Look.SAVING, true, true), "Saving: nothing");
        require(!RideRecordTile.taps(RideRecordTile.Look.RECORDING, true, true), "Recording: the live card's End stops it, not the tile");
        require(RideRecordTile.dim(RideRecordTile.Look.START, true, false) && RideRecordTile.dim(RideRecordTile.Look.SAVING, true, true),
                "Unavailable looks dim");
        require(!RideRecordTile.dim(RideRecordTile.Look.START, true, true) && !RideRecordTile.dim(RideRecordTile.Look.RECORDING, true, false)
                && !RideRecordTile.dim(RideRecordTile.Look.SIMULATING, false, false), "States are not dim");
    }

    private static void chargeTrack() {
        float w = 300, thick = 8;
        require(ChargeTrackShape.fillEnd(w, thick, 50) == 150, "Half full");
        require(ChargeTrackShape.fillEnd(w, thick, 100) == 300 && ChargeTrackShape.fillEnd(w, thick, 120) == 300, "Full stops at the end");
        require(ChargeTrackShape.fillEnd(w, thick, 1) == thick, "A small level is still a round dot");
        require(Float.isNaN(ChargeTrackShape.fillEnd(w, thick, 0)) && Float.isNaN(ChargeTrackShape.fillEnd(w, thick, Double.NaN))
                && Float.isNaN(ChargeTrackShape.fillEnd(0, thick, 50)), "Empty, unknown or no room: no fill");
        require(ChargeTrackShape.tickAt(w, 80) == 240, "The tick at the limit");
        require(Float.isNaN(ChargeTrackShape.tickAt(w, 100)) && Float.isNaN(ChargeTrackShape.tickAt(w, Double.NaN))
                && Float.isNaN(ChargeTrackShape.tickAt(w, -1)), "No tick at 100%, unknown or out of range");
        require(ChargeTrackShape.tickAt(w, 0) == 0, "A 0% limit sits at the start");
    }

    /**
     * The tiles' names at the reference windows (344x882 and 725x604 dp; 725 dp at 1.4x text keeps the phone layout, so
     * its page is 725 dp wide too), with widths estimated for Saira Bold at 15 sp: "Simulating · Exit"
     * ≈ 123 dp, "Simulating" ≈ 74 dp, "Record ride" ≈ 80 dp, "Riding mode" ≈ 88 dp, each times the text size.
     */
    private static void tileNames() {
        float exit = 123, simulating = 74, record = 80, riding = 88;
        float phone = RideRecordTile.nameRoom(344 - 32), wide = RideRecordTile.nameRoom(725 - 48);
        require(phone == 128 && wide == 310.5f, "Room beside the 8 dp gap, less 12 dp each side");
        // 1.0x: every full name on one line.
        for (float w : new float[]{exit, record, riding}) {
            require(fullName(w, phone) == 0, "344 dp 1.0x: full name " + w);
            require(fullName(w, wide) == 0, "725 dp 1.0x: full name " + w);
        }
        // 1.4x: on the phone "Simulating · Exit" is too wide for one line; it wraps before the dot (RideRecordTile.dotsLead),
        // and "Simulating" alone fits the first line. Riding mode and Record ride still fit; nothing changes at 725 dp.
        require(exit * 1.4f > phone && simulating * 1.4f <= phone, "344 dp 1.4x: Simulating · Exit wraps after Simulating");
        require(RideRecordTile.dotsLead("Simulating \u00B7 Exit").equals("Simulating \u00B7\u00A0Exit"), "The dot leads the second line");
        require(RideRecordTile.dotsLead("Saving phone sensors\u2026").equals("Saving phone sensors\u2026") && RideRecordTile.dotsLead(null) == null,
                "Names without the dot are unchanged");
        require(fullName(riding * 1.4f, phone) == 0 && fullName(record * 1.4f, phone) == 0,
                "344 dp 1.4x: Riding mode and Record ride fit");
        for (float w : new float[]{exit, record, riding}) require(fullName(w * 1.4f, wide) == 0, "725 dp 1.4x: full name " + w);
        require(fullName(500, 0) == 0 && fullName(500, -1) == 0, "Before layout: the full name");
        tileLadder(phone, wide);
    }

    /**
     * Every look of the Record ride tile through the ladder (RideRecordTile.rung) at 344x882 dp (128 dp of room) and
     * 725x604 dp (310.5 dp; at 1.4x the phone layout's tile is as wide), 1.0x and 1.4x, and 2.0x for the largest text:
     * French "Enregistrement" ≈ 110 dp at 15 sp bold, then the riding screen's abbreviation "REC" ≈ 30 dp (its reading
     * name "Enreg." is not a rung: in Italian it is "Tempo REC"); "Simulating · Exit" ≈ 123 dp
     * (widest part "Simulating" ≈ 74 dp), "Simulation · Quitter" ≈ 140 dp (widest part "Simulation" ≈ 78 dp).
     */
    /**
     * The ladder's pick for a two-rung name (full, then short at half its width) on the one-line test: 0 while the full
     * name fits room (and before the first layout, room <= 0), else 1.
     */
    private static int fullName(float fullWidth, float room) {
        return RideRecordTile.rung(new float[]{fullWidth, fullWidth / 2}, new float[]{fullWidth, fullWidth / 2}, room, false);
    }

    private static void tileLadder(float phone, float wide) {
        float[] recLines = {110, 30}, recWords = {110, 30};
        for (float scale : new float[]{1f, 1.4f, 2f}) {
            float[] lines = times(recLines, scale), words = times(recWords, scale);
            int onPhone = RideRecordTile.rung(lines, words, phone, false), onWide = RideRecordTile.rung(lines, words, wide, false);
            require(onPhone >= 0 && lines[onPhone] <= phone, "344 dp " + scale + "x: a recording name on one line, never split");
            require(onWide >= 0 && lines[onWide] <= wide, "725 dp " + scale + "x: a recording name on one line");
            require(onPhone == (110 * scale <= phone ? 0 : 1), "344 dp " + scale + "x: Enregistrement while it fits, else REC");
            require(onWide == 0, "725 dp " + scale + "x: the whole name");
        }
        // Simulating · Exit wraps between words before a shorter rung; whichever is drawn, no word is wider than the tile.
        for (float[] sim : new float[][]{{123, 74, 74, 74}, {140, 78, 150, 78}}) {
            for (float scale : new float[]{1f, 1.4f, 2f}) {
                float[] lines = {sim[0] * scale, sim[2] * scale}, words = {sim[1] * scale, sim[3] * scale};
                for (float room : new float[]{phone, wide}) {
                    int rung = RideRecordTile.rung(lines, words, room, true);
                    String at = room + " dp, " + scale + "x, " + sim[0];
                    require(rung == -1 || words[rung] <= room, "No word split at " + at);
                    require(rung != 1 || words[0] > room, "The way out stays while its words fit at " + at);
                }
            }
        }
        require(RideRecordTile.rung(new float[]{123 * 1.4f}, new float[]{74 * 1.4f}, phone, true) == 0, "344 dp 1.4x: Simulating · Exit wraps, whole");
        require(RideRecordTile.rung(new float[]{200, 180}, new float[]{200, 180}, 128, false) == -1, "Nothing fits: the icon alone");
        require(RideRecordTile.rung(new float[]{200, 90}, new float[]{100, 90}, 128, false) == 1, "Short before wrapping");
        require(RideRecordTile.rung(new float[]{200, 150}, new float[]{100, 150}, 128, false) == 0, "No rung on one line: the first whose words fit, wrapped");
        require(RideRecordTile.rung(new float[]{500}, new float[]{500}, 0, false) == 0 && RideRecordTile.rung(new float[0], new float[0], 100, false) == -1,
                "Before layout the first rung; no rungs, none");
        require(java.util.Arrays.equals(RideRecordTile.words("Simulating \u00B7\u00A0Exit"), new String[]{"Simulating", "\u00B7\u00A0Exit"})
                && RideRecordTile.words("Enregistrement").length == 1 && RideRecordTile.words(null).length == 0 && RideRecordTile.words("").length == 0,
                "Parts split at ordinary spaces only");
    }

    private static float[] times(float[] widths, float scale) {
        float[] out = new float[widths.length];
        for (int i = 0; i < widths.length; i++) out[i] = widths[i] * scale;
        return out;
    }

    /**
     * The summary card's level at 1.0x and 1.4x text (48 sp Saira Bold: ascent ≈ 1.06 em over the digits' top at
     * ≈ 0.70 em; descent ≈ 0.31 em under the baseline), beside or under the right column. Pixels at 3 px per dp.
     */
    private static void summaryFit() {
        for (float scale : new float[]{1f, 1.4f}) {
            float em = 48 * scale * 3;
            int glyphTop = Math.round(em * (1.06f - .70f)), under = Math.round(em * .31f);
            int besideCap = Math.round(3 * (2 + 14 * scale * .45f));
            int[] beside = BatterySummaryFit.levelMargins(glyphTop, under, besideCap);
            require(glyphTop + beside[0] == besideCap, "The digits' top is level with Health's (" + scale + "x)");
            require(beside[1] == -under, "No font space under the digits (" + scale + "x)");
            int[] stacked = BatterySummaryFit.levelMargins(glyphTop, under, 0);
            require(glyphTop + stacked[0] == 0, "Stacked: the digits start at the card's padding (" + scale + "x)");
        }
        require(BatterySummaryFit.rangePadding(30, 12) == 18 && BatterySummaryFit.rangePadding(30, 40) == 0
                && BatterySummaryFit.rangePadding(30, -3) == 30, "The range line keeps 10 dp under the digits, never less than none");
        require(BatterySummaryFit.levelMargins(10, -2, 0)[1] == 0, "A font with no space under the digits keeps none");
        // Beside or stacked, at 344 dp (279 dp inside the card) and 725 dp (wide pane ≈ 330 dp, phone layout 645 dp):
        // "78%" ≈ 80 dp at 48 sp and the column ("Not charging") ≈ 86 dp at 14 sp, each times the text size; 12 dp gap.
        for (int room : new int[]{279, 330, 645})
            for (float scale : new float[]{1f, 1.4f})
                require(!BatterySummaryFit.stacks(room, Math.round(86 * scale), 12, 80 * scale), "Beside at " + room + " dp, " + scale + "x");
        require(BatterySummaryFit.stacks(200, 120, 12, 112), "A narrow card stacks the column under the level");
        summaryColumn();
    }

    /**
     * The right column's width (BatterySummaryFit.columnWidth) at 344x882 dp (279 dp inside the card) and 725x604 dp
     * (the page's pane ≈ 330 dp at 1.0x, the phone layout ≈ 645 dp at 1.4x), 1.0x and 1.4x text: the widest line wins,
     * so a short state line ("Charging" ≈ 58 dp at 14 sp, GT3 Max and GT5 Max with no charge limit) never cuts
     * "Health 97%" (≈ 44 + 5 + 26 dp) or "Temp. 24 °C" (≈ 37 + 5 + 36 dp), and a long one ("Charging to 90%" ≈ 104 dp,
     * "Not charging" ≈ 86 dp) still sets the width. The level ("97%" ≈ 80 dp at 48 sp) stays beside the column.
     */
    private static void summaryColumn() {
        for (int room : new int[]{279, 330, 645}) {
            for (float scale : new float[]{1f, 1.4f}) {
                int gap = 5;
                int health = BatterySummaryFit.factWidth(44 * scale, gap, 26 * scale), temp = BatterySummaryFit.factWidth(37 * scale, gap, 36 * scale);
                for (float stateDp : new float[]{58, 86, 104}) {
                    int state = (int) Math.ceil(stateDp * scale);
                    int column = BatterySummaryFit.columnWidth(room, health, temp, state);
                    String at = room + " dp, " + scale + "x, state " + stateDp + " dp";
                    require(column >= health && column >= temp && column >= state, "No line of the column is cut at " + at);
                    require(column == Math.max(Math.max(health, temp), state), "The column is its widest line at " + at);
                    require(!BatterySummaryFit.stacks(room, column, 12, 80 * scale), "The level stays beside the column at " + at);
                }
                int noState = BatterySummaryFit.columnWidth(room, health, temp, 0);
                require(noState == Math.max(health, temp), "No state line: the readings set the width at " + room + " dp, " + scale + "x");
                require(BatterySummaryFit.columnWidth(room, 0, 0, (int) Math.ceil(58 * scale)) == (int) Math.ceil(58 * scale),
                        "Only the state line: its width at " + room + " dp, " + scale + "x");
            }
        }
        require(BatterySummaryFit.factWidth(43.2f, 5, 25.1f) == 44 + 5 + 26, "A line's parts round up: no glyph clipped");
        require(BatterySummaryFit.columnWidth(100, 140, 60) == 100, "Never wider than the room");
        require(BatterySummaryFit.columnWidth(100) == 0 && BatterySummaryFit.columnWidth(100, -3) == 0, "No line: no width");
    }

    private static void trackTick() {
        float[] above = ChargeTrackShape.tickSpan(100, 24, 9, 0);
        require(above[0] == 91 && above[1] == 124, "A tick 3 dp above the track only");
        // Clean's charging view at 3 px per dp: a ChargingFit.TRACK_DP track, the tick ChargingFit.TICK_DP each way.
        float[] clean = ChargeTrackShape.tickSpan(100, 3 * ChargingFit.TRACK_DP, 3 * ChargingFit.TICK_DP, 3 * ChargingFit.TICK_DP);
        require(clean[0] == 88 && clean[1] == 133, "Clean's charging view: the tick 4 dp above and below its 7 dp track");
        float[] card = ChargeTrackShape.tickSpan(100, 24, 13.5f, 13.5f);
        require(card[0] == 86.5f && card[1] == 137.5f, "The summary card: 4.5 dp above and below the track");
        float[] none = ChargeTrackShape.tickSpan(100, 24, -5, -5);
        require(none[0] == 100 && none[1] == 124, "A negative reach is none");
    }

    /**
     * Battery & charging's HISTORY card (ChargeHistoryBars) at the reference windows: 344x882 dp (279 dp inside the
     * card), 725x604 dp at 1.0x (the page's pane, ≈ 330 dp inside) and at 1.4x (phone layout, 645 dp inside). The bars
     * do not change with the text size; the summary line ("42% → 90% · 2 h 10 min" ≈ 150 dp at 14 sp) fits one line
     * at 1.0x and 1.4x at every width.
     */
    private static void historyBars() {
        require(ChargeHistoryBars.maxBars(279) == 7, "344 dp: seven bars, as the Layout Plan draws");
        require(ChargeHistoryBars.maxBars(330) == 8, "725 dp pane: eight");
        require(ChargeHistoryBars.maxBars(645) == ChargeHistoryBars.MAX_BARS, "725 dp phone layout (1.4x): at most twelve");
        require(ChargeHistoryBars.maxBars(100) == ChargeHistoryBars.MIN_BARS && ChargeHistoryBars.maxBars(0) == ChargeHistoryBars.MIN_BARS
                && ChargeHistoryBars.maxBars(Float.NaN) == ChargeHistoryBars.MIN_BARS, "Never fewer than four, nor before layout");
        for (float room : new float[]{279, 330, 645}) {
            int n = ChargeHistoryBars.maxBars(room);
            float bar = ChargeHistoryBars.barWidth(room, n);
            require(bar >= ChargeHistoryBars.MIN_BAR_DP, "Bars at least 28 dp at " + room);
            require(Math.abs(bar * n + ChargeHistoryBars.GAP_DP * (n - 1) - room) < 0.01, "Bars and gaps fill the card at " + room);
            for (float scale : new float[]{1f, 1.4f}) require(150 * scale <= room, "The last charge's line fits one line at " + room + " dp, " + scale + "x");
        }
        // Slots: the plan's seven while there are fewer charges, one per charge beyond that as room allows.
        for (float room : new float[]{279, 330, 645}) {
            require(ChargeHistoryBars.slots(room, 0) == 7 && ChargeHistoryBars.slots(room, 7) == 7, "Seven slots, few charges, at " + room);
            require(ChargeHistoryBars.slots(room, 20) == ChargeHistoryBars.maxBars(room), "Many charges: as many as fit at " + room);
        }
        require(ChargeHistoryBars.slots(330, 8) == 8 && ChargeHistoryBars.slots(645, 9) == 9, "One per charge where they fit");
        require(ChargeHistoryBars.slots(150, 3) == ChargeHistoryBars.maxBars(150) && ChargeHistoryBars.maxBars(150) < 7, "A narrow card draws what fits");
        require(ChargeHistoryBars.barWidth(279, 0) == 0 && ChargeHistoryBars.barWidth(0, 7) == 0, "No bars, no room: no width");
        // Fills: the newest charges right-aligned, oldest to newest; empty slots are the track (-1).
        java.util.List<ChargeHistoryBars.Charge> newestFirst = java.util.Arrays.asList(
                new ChargeHistoryBars.Charge(42, 90, 7_800_000L), new ChargeHistoryBars.Charge(80, 82, 600_000L),
                new ChargeHistoryBars.Charge(Double.NaN, Double.NaN, 1000), new ChargeHistoryBars.Charge(10, 100, 1L));
        double[] fills = ChargeHistoryBars.fills(newestFirst, 7);
        require(fills.length == 7 && fills[0] == -1 && fills[1] == -1 && fills[2] == -1, "Three empty slots on the left");
        require(fills[3] == 0.9 && fills[4] == ChargeHistoryBars.MIN_FILL && fills[5] == ChargeHistoryBars.MIN_FILL && fills[6] == 0.48,
                "Oldest to newest: 90 points, then a 2-point top-up and an unmeasured charge at the least fill, the newest 48");
        double[] two = ChargeHistoryBars.fills(newestFirst, 2);
        require(two.length == 2 && two[0] == ChargeHistoryBars.MIN_FILL && two[1] == 0.48, "More charges than slots: the newest");
        require(ChargeHistoryBars.fills(null, 4)[3] == -1 && ChargeHistoryBars.fills(newestFirst, 0).length == 0, "No charges or no slots");
        require(new ChargeHistoryBars.Charge(90, 40, 5).gained() == 0 && new ChargeHistoryBars.Charge(1, 2, -5).ms == 0, "No negative gain or time");
        // The summary line's charge: the newest with battery samples.
        require(ChargeHistoryBars.last(newestFirst) == newestFirst.get(0), "The newest charge");
        require(ChargeHistoryBars.last(java.util.Arrays.asList(new ChargeHistoryBars.Charge(Double.NaN, 5, 1), newestFirst.get(1))) == newestFirst.get(1),
                "Skips one without samples");
        require(ChargeHistoryBars.last(null) == null && ChargeHistoryBars.last(java.util.Collections.emptyList()) == null, "None: the empty words");
        // The line under the bars: a failed read is not "none", and bars without battery samples are not "none" either.
        require(ChargeHistoryBars.line(false, newestFirst) == ChargeHistoryBars.Line.LAST, "Measured charges: the last one's line");
        require(ChargeHistoryBars.line(false, java.util.Collections.emptyList()) == ChargeHistoryBars.Line.EMPTY
                && ChargeHistoryBars.line(false, null) == ChargeHistoryBars.Line.EMPTY, "No recordings: the empty words");
        require(ChargeHistoryBars.line(false, java.util.Arrays.asList(new ChargeHistoryBars.Charge(Double.NaN, Double.NaN, 4000))) == ChargeHistoryBars.Line.NO_SAMPLES,
                "A bar with no battery sample: No battery samples, not the empty words");
        require(ChargeHistoryBars.line(true, null) == ChargeHistoryBars.Line.FAILED && ChargeHistoryBars.line(true, newestFirst) == ChargeHistoryBars.Line.FAILED,
                "A failed read says so");
    }

    /** Charge done by's value, the Live charging row's sub-line and the Ride page's recent ride line (PageRows). */
    private static void pageRows() {
        require(PageRows.doneBy(true, false) == PageRows.DoneBy.TIME, "On: the finish time");
        require(PageRows.doneBy(false, false) == PageRows.DoneBy.OFF, "Off: Off");
        require(PageRows.doneBy(false, true) == PageRows.DoneBy.MEASURING && PageRows.doneBy(true, true) == PageRows.DoneBy.MEASURING,
                "Measuring: its minutes, whatever the switch");
        require(PageRows.liveSub(false, false, false) == PageRows.LiveSub.NONE && PageRows.liveSub(false, true, false) == PageRows.LiveSub.NONE,
                "Off: no sub-line");
        require(PageRows.liveSub(true, false, false) == PageRows.LiveSub.NOTIFICATIONS_OFF && PageRows.liveSub(true, false, true) == PageRows.LiveSub.NOTIFICATIONS_OFF,
                "The warning comes first");
        require(PageRows.liveSub(true, true, false) == PageRows.LiveSub.LOCK_SCREEN, "On and shown there: On the lock screen");
        require(PageRows.liveSub(true, true, true) == PageRows.LiveSub.NONE, "Hidden on the lock screen: not said");
        require(PageRows.rideLine("6.2 mi", "24m 00s", "\u221218%").equals("6.2 mi \u00B7 24m 00s \u00B7 \u221218%"), "Distance, duration, battery");
        require(PageRows.rideLine("6.2 mi", "24m 00s", null).equals("6.2 mi \u00B7 24m 00s") && PageRows.rideLine(null, "5s", "").equals("5s")
                && PageRows.rideLine(null, null, null).isEmpty(), "Missing parts are left out");
        // At 344 dp (the recent rides' card ≈ 290 dp inside, less the 25 dp chevron) the 12 sp line "6.2 mi · 1h 02m · −18%"
        // (≈ 125 dp) fits one line at 1.0x and 1.4x, and at 725 dp (≈ 640 dp) with room to spare.
        for (float room : new float[]{290 - 25, 640 - 25}) for (float scale : new float[]{1f, 1.4f}) require(125 * scale <= room, "Ride line fits at " + room);
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
