package com.jjricks.volt;

/**
 * Focus's chips in rows under its big number (the owner, Nov 14: "in a horizontal row right above range at this pace,
 * and then if any of them spill over they can spill into a second row just fine").
 *
 * Every row shares one rung, chosen before the chips are split, so the names read alike from row to row: row 1 never
 * shows icons while row 2 spells "Voltage" out. At each rung, in slot order, a chip that doesn't fit beside the ones
 * before it starts the next row, and a chip too wide for a row even on its own is hidden. The rung kept is the one that
 * shows the most chips, then the earliest in slot order, then needs the fewest rows (names shorten before a chip moves
 * down), then reads widest. Chips
 * past the most rows the height allows are hidden. The widths are each chip's widest value, so the split never changes
 * while riding.
 */
final class RidingChipRows {
    /** Each chip's row (-1: hidden), its name step and whether it shows its compact form. */
    final int[] row, step;
    final boolean[] compact;
    /** The rung every row uses. */
    final int rung;
    /** How many rows hold a chip. */
    final int rows;

    private RidingChipRows(int[] row, int rung, int[] step, boolean[] compact, int rows) {
        this.row = row;
        this.rung = rung;
        this.step = step;
        this.compact = compact;
        this.rows = rows;
    }

    /**
     * @param marks each chip's marks (see RidingChipFit.nameStep), in slot order
     * @param full each chip's widths with its widest value in full
     * @param compact each chip's widths in its compact form (read only for chips in canStep)
     * @param gap the space between two chips in a row
     * @param room a row's width
     * @param most the most rows there may be
     */
    static RidingChipRows split(String[][] marks, String[] contents, RidingChipFit.Widths full, RidingChipFit.Widths compact,
                                boolean[] canStep, int gap, int room, int most) {
        RidingChipRows best = null;
        for (int rung = RidingChipFit.FULL; rung < RidingChipFit.RUNGS; rung++) {
            RidingChipRows at = at(marks, contents, full, compact, canStep, gap, room, most, rung);
            if (best == null || better(at, best)) best = at;
        }
        return best;
    }

    /**
     * More chips shown; then, between as many, the one that keeps the earlier chip in slot order (so a tighter rung that
     * lets an earlier chip show beats a wider one that shows a later chip in its place); then fewer rows. A tie keeps the
     * wider rung, tried first.
     */
    private static boolean better(RidingChipRows a, RidingChipRows b) {
        if (shown(a) != shown(b)) return shown(a) > shown(b);
        for (int i = 0; i < a.row.length; i++)
            if ((a.row[i] >= 0) != (b.row[i] >= 0)) return a.row[i] >= 0;
        return a.rows < b.rows;
    }

    private static int shown(RidingChipRows r) {
        int n = 0;
        for (int x : r.row) if (x >= 0) n++;
        return n;
    }

    /** The split at one rung. */
    private static RidingChipRows at(String[][] marks, String[] contents, RidingChipFit.Widths full,
                                     RidingChipFit.Widths compact, boolean[] canStep, int gap, int room, int most, int rung) {
        int of = marks.length;
        // The chips that fit a row on their own; a chip that steps aside frees its marks, so the rest are tried again.
        boolean[] set = new boolean[of];
        java.util.Arrays.fill(set, true);
        boolean[] small = new boolean[of];
        int[] steps;
        boolean changed;
        do {
            changed = false;
            steps = RidingChipFit.steps(marks, contents, set, rung);
            for (int i = 0; i < of; i++) {
                if (!set[i]) continue;
                small[i] = false;
                if (full.at(i, rung, steps[i]) <= room) continue;
                if (canStep != null && canStep[i] && compact.at(i, rung, steps[i]) <= room) { small[i] = true; continue; }
                set[i] = false;
                changed = true;
            }
        } while (changed);
        // In slot order, a chip that doesn't fit beside the ones before it starts the next row.
        int[] row = new int[of];
        java.util.Arrays.fill(row, -1);
        int rows = 0;
        long used = 0;
        for (int i = 0; i < of; i++) {
            if (!set[i]) continue;
            int width = small[i] ? compact.at(i, rung, steps[i]) : full.at(i, rung, steps[i]);
            if (rows == 0 || used + gap + width > room) {
                if (rows == most) { set[i] = false; continue; }
                rows++;
                used = width;
            } else {
                used += gap + width;
            }
            row[i] = rows - 1;
        }
        // The chips past the last row hold no marks; the rest keep their places (a freed mark only narrows a chip).
        int[] step = RidingChipFit.steps(marks, contents, set, rung);
        for (int i = 0; i < of; i++) if (!set[i]) small[i] = false;
        return new RidingChipRows(row, rung, step, small, rows);
    }
}
