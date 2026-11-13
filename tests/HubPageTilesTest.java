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
            require(RideRecordTile.nameRung(w, phone) == 0, "344 dp 1.0x: full name " + w);
            require(RideRecordTile.nameRung(w, wide) == 0, "725 dp 1.0x: full name " + w);
        }
        // 1.4x: on the phone "Simulating · Exit" is too wide for one line; it wraps before the dot (RideRecordTile.dotsLead),
        // and "Simulating" alone fits the first line. Riding mode and Record ride still fit; nothing changes at 725 dp.
        require(exit * 1.4f > phone && simulating * 1.4f <= phone, "344 dp 1.4x: Simulating · Exit wraps after Simulating");
        require(RideRecordTile.dotsLead("Simulating \u00B7 Exit").equals("Simulating \u00B7\u00A0Exit"), "The dot leads the second line");
        require(RideRecordTile.dotsLead("Saving phone sensors\u2026").equals("Saving phone sensors\u2026") && RideRecordTile.dotsLead(null) == null,
                "Names without the dot are unchanged");
        require(RideRecordTile.nameRung(riding * 1.4f, phone) == 0 && RideRecordTile.nameRung(record * 1.4f, phone) == 0,
                "344 dp 1.4x: Riding mode and Record ride fit");
        for (float w : new float[]{exit, record, riding}) require(RideRecordTile.nameRung(w * 1.4f, wide) == 0, "725 dp 1.4x: full name " + w);
        require(RideRecordTile.nameRung(500, 0) == 0 && RideRecordTile.nameRung(500, -1) == 0, "Before layout: the full name");
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
    }

    private static void trackTick() {
        float[] clean = ChargeTrackShape.tickSpan(100, 24, 9, 0);
        require(clean[0] == 91 && clean[1] == 124, "Clean's charging view: the tick 3 dp above the track only");
        float[] card = ChargeTrackShape.tickSpan(100, 24, 13.5f, 13.5f);
        require(card[0] == 86.5f && card[1] == 137.5f, "The summary card: 4.5 dp above and below the track");
        float[] none = ChargeTrackShape.tickSpan(100, 24, -5, -5);
        require(none[0] == 100 && none[1] == 124, "A negative reach is none");
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
