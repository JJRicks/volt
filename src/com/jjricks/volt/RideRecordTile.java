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
     * The narrow-label ladder for every look of the tile (owner: short forms before anything wraps; never a word broken
     * or the text shrunk). Rungs run longest first: lines[i] is rung i's one-line width, longestWords[i] the width of
     * its widest unbreakable part (RideRecordTile.words), both measured in room's units. Before the first layout (room
     * <= 0) rung 0. Otherwise, unless the look wraps first, the first rung that fits on one line; then the first whose
     * every word fits, wrapping between words; -1 when none does, where the tile shows its icon alone (its spoken name
     * stays). wrapsFirst: the full name may wrap between words before a shorter rung is used ("Simulating · Exit",
     * which wraps before the dot, keeps its way out on the tile).
     */
    static int rung(float[] lines, float[] longestWords, float room, boolean wrapsFirst) {
        if (lines.length == 0) return -1;
        if (room <= 0) return 0;
        if (!wrapsFirst) for (int i = 0; i < lines.length; i++) if (lines[i] <= room) return i;
        for (int i = 0; i < longestWords.length; i++) if (longestWords[i] <= room) return i;
        return -1;
    }

    /** The parts a name may wrap between: split at ordinary spaces only (a no-break space, as dotsLead adds, joins). */
    static String[] words(String name) {
        if (name == null || name.isEmpty()) return new String[0];
        return name.split(" +");
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
