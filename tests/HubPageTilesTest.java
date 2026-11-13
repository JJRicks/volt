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

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
