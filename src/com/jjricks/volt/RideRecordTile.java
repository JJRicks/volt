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

    /** The tiles' side padding, dp: as the plan's tiles (≈153 dp wide) leave room for a bold one-line name. */
    static final int PAD_DP = 12;

    /** The room in dp for a tile's name: half the content width beside the 8 dp gap, less the side padding. */
    static float nameRoom(float contentWidthDp) { return (contentWidthDp - 8) / 2 - 2 * PAD_DP; }

    /**
     * Which name the tile draws (the narrow-label ladder; owner: short forms before anything wraps): 0, the full name,
     * while it fits room (measured, same units), and before the first layout (no room yet, room <= 0) until the layout
     * measures it; 1, the short rung, otherwise.
     */
    static int nameRung(float fullWidth, float room) {
        return room <= 0 || fullWidth <= room ? 0 : 1;
    }

    /**
     * A tile name with a middle dot ("Simulating · Exit") as it may wrap: the space after each " · " no longer breaks,
     * so a narrow tile wraps before the dot ("Simulating" / "· Exit") and never leaves it at a line's end. Text without
     * the dot is unchanged; nothing is reworded.
     */
    static String dotsLead(String name) { return name == null ? null : name.replace(" \u00B7 ", " \u00B7\u00A0"); }

    /** Drawn as unavailable (dim): a start that can't happen yet, and saving. Recording and simulation are states. */
    static boolean dim(Look look, boolean storage, boolean ready) {
        return look == Look.SAVING || (look == Look.START && !(storage && ready));
    }
}
