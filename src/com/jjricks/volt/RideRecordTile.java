package com.jjricks.volt;

/**
 * The Ride page's Record ride tile (the owner's Layout Plan: Riding mode and Record ride side by side): what it shows
 * and whether a tap does anything, from the recorder's state. Pure, so the host tests run it. RidesScreen draws it and
 * takes its words from the catalog; the rules are the ones the full-width Start recording button had.
 */
final class RideRecordTile {
    private RideRecordTile() {}

    /** What the tile says: start a ride, the simulation's way out, phone sensors still saving, or a ride recording. */
    enum Look { START, SIMULATING, SAVING, RECORDING }

    /**
     * The look for the recorder's state. A recording ride wins, as the button it replaces was hidden while one
     * recorded; then simulation (whose tap exits it), then sensor files still saving.
     */
    static Look look(boolean recording, boolean simulating, boolean saving) {
        if (recording) return Look.RECORDING;
        if (simulating) return Look.SIMULATING;
        return saving ? Look.SAVING : Look.START;
    }

    /**
     * Whether a tap does something, as the Start recording button allowed: while simulating it exits simulation (it
     * never starts a ride with sample data); otherwise it starts a ride once ride storage is open, the scooter is
     * ready and nothing is saving. While a ride records the tile only says so: the live card's End button stops it,
     * as before, so no new way to end a ride appears here.
     */
    static boolean taps(Look look, boolean storage, boolean ready) {
        switch (look) {
            case SIMULATING: return true;
            case START: return storage && ready;
            default: return false;
        }
    }

    /** Drawn as unavailable (dim): a start that can't happen yet, and saving. Recording and simulation are states. */
    static boolean dim(Look look, boolean storage, boolean ready) {
        return look == Look.SAVING || (look == Look.START && !(storage && ready));
    }
}
