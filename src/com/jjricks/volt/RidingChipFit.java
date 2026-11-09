package com.jjricks.volt;

/**
 * How many riding-mode chips a row can show, and how tight they must get to do it. A narrow row
 * gives up its decoration before it gives up a reading: first the drive mode's letter spacing,
 * then the chips' icons, and only then the last chip. The rung applies to the whole row so the
 * chips stay alike. Text is never shrunk or broken: a chip shows whole or not at all.
 */
final class RidingChipFit {
    /** Rungs, widest first: as designed; no letter spacing; no letter spacing and no icons. */
    static final int FULL = 0, NO_SPACING = 1, NO_ICONS = 2, RUNGS = 3;

    /** How many chips show, from the first; a later chip is lower priority. */
    final int count;
    /** The rung the shown chips use: the widest one at which they all fit. */
    final int rung;

    private RidingChipFit(int count, int rung) {
        this.count = count;
        this.rung = rung;
    }

    /**
     * Keeps as many chips as fit at any rung, and among those takes the widest rung, so a row that
     * has already lost a chip keeps its icons when the rest have room for them.
     *
     * @param widths each rung's width of each chip, [rung][chip], in priority order
     * @param gap the space between two chips
     * @param room the row's width
     */
    static RidingChipFit choose(int[][] widths, int gap, int room) {
        for (int count = widths[FULL].length; count > 0; count--)
            for (int rung = FULL; rung < RUNGS; rung++) {
                long used = gap * (count - 1L);
                for (int chip = 0; chip < count; chip++) used += widths[rung][chip];
                if (used <= room) return new RidingChipFit(count, rung);
            }
        return new RidingChipFit(0, FULL);
    }
}
