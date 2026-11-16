package com.jjricks.volt;

/**
 * A fitted value's choice of text and size (DashboardScreen.FittedValue): the narrow-label ladder before smaller
 * text. At each size from the ceiling down, its rungs are tried longest first (full, then short); the first that fits
 * is drawn. So a value keeps its full text at its full size where that fits, takes its short rung at the full size
 * where only that fits, and gets smaller only where no rung fits at the full size (the charging time beside Charging
 * power, owner, Nov 15 2026: the time matches power's size unless there is a good reason). Pure, so the JVM suite
 * tests it (tests/l10n/FitLadderTest.java).
 */
final class FitLadder {
    private FitLadder() {}

    /** Whether rung (0: full) fits its room at size (sp). */
    interface Fits { boolean fits(int rung, int sizeSp); }

    /**
     * {rung, size}: the first rung that fits at the largest size from ceiling down to floor (both sp). Where none fits
     * even at the floor, the last rung at the floor (the caller lets it wrap there, as a value without rungs does). A
     * ceiling below the floor is drawn as given, with the full rung, without measuring.
     */
    static int[] pick(int rungs, int ceiling, int floor, Fits fits) {
        int count = Math.max(1, rungs);
        if (ceiling < floor) return new int[] {0, ceiling};
        for (int size = ceiling; size >= floor; size--)
            for (int rung = 0; rung < count; rung++)
                if (fits.fits(rung, size)) return new int[] {rung, size};
        return new int[] {count - 1, floor};
    }
}
