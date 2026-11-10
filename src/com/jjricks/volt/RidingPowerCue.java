package com.jjricks.volt;

/**
 * What riding mode prints for an inferred regen reading, by how much room its label has. The scooter
 * didn't say this reading's direction, so a minus sign needs something next to it saying so:
 * the full caption while it fits, else the short "Direction ?" cue, and with no room for either the
 * number shows its size alone. Pure, so the host tests run it. Every other direction, a signed
 * scooter's included, is left exactly as it was.
 */
final class RidingPowerCue {
    private RidingPowerCue() {}

    /** The rungs: the full caption, the short cue, then no cue at all (the plain label, or nothing). */
    static final int FULL = 0, CUE = 1, NONE = 2;

    /** True only for a reading whose minus sign is Volt's inference: it's the one this ladder guards. */
    static boolean applies(RidingValues.Direction direction) { return direction == RidingValues.Direction.REGEN_INFERRED; }

    /**
     * The first rung that fits room (pixels, after padding), at the size it may not go below. No room,
     * which covers a label that's hidden or not laid out yet, can't show a cue, so it's NONE.
     */
    static int rung(float fullPx, float cuePx, float room) {
        if (room <= 0) return NONE;
        if (fullPx <= room) return FULL;
        return cuePx <= room ? CUE : NONE;
    }

    /** The rung a laddered label landed on, given its rungs run full, cue, plain; -1 is its icon alone. */
    static int rungOfLabel(int picked) { return picked == FULL || picked == CUE ? picked : NONE; }

    /** The watts to print: the same measured size at every rung, losing only an inferred sign that has no cue. */
    static double shownWatts(double watts, RidingValues.Direction direction, int rung) {
        return applies(direction) && rung == NONE ? Math.abs(watts) : watts;
    }
}
