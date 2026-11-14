package com.jjricks.volt;

/**
 * Focus's chips in rows under its big number (the owner, Nov 14: "in a horizontal row right above range at this pace,
 * and then if any of them spill over they can spill into a second row just fine").
 *
 * In slot order, the first row takes as many chips as fit beside each other at any rung, at the widest rung that fits
 * them (RidingChipFit.row, so names shorten before a chip moves down); the chips left over start the next row, fitted the
 * same way. A chip too wide for a row even on its own is hidden, as is any chip past the most rows the height allows.
 * The widths are each chip's widest value, so the split never changes while riding.
 */
final class RidingChipRows {
    /** Each chip's row (-1: hidden), its row's rung, its name step and whether it shows its compact form. */
    final int[] row, rung, step;
    final boolean[] compact;
    /** How many rows hold a chip. */
    final int rows;

    private RidingChipRows(int[] row, int[] rung, int[] step, boolean[] compact, int rows) {
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
        int of = marks.length;
        int[] row = new int[of], rung = new int[of], step = new int[of];
        boolean[] small = new boolean[of];
        java.util.Arrays.fill(row, -1);
        java.util.Arrays.fill(step, -1);
        // First, the chips too wide for a row even on their own: they're hidden, and the rest close up.
        int[] order = new int[of];
        int kept = 0;
        for (int i = 0; i < of; i++) {
            final int one = i;
            RidingChipFit alone = RidingChipFit.stepping(new String[][]{marks[i]}, new String[]{contents[i]},
                    (chip, r, st) -> full.at(one, r, st), (chip, r, st) -> compact.at(one, r, st),
                    new boolean[]{canStep != null && canStep[i]}, false, gap, room);
            if (alone.count > 0) order[kept++] = i;
        }
        int next = 0, rows = 0;
        while (next < kept && rows < most) {
            // The chips not yet placed, in slot order.
            final int[] left = java.util.Arrays.copyOfRange(order, next, kept);
            String[][] m = new String[left.length][];
            String[] c = new String[left.length];
            boolean[] s = new boolean[left.length];
            for (int i = 0; i < left.length; i++) { m[i] = marks[left[i]]; c[i] = contents[left[i]]; s[i] = canStep != null && canStep[left[i]]; }
            RidingChipFit fit = RidingChipFit.stepping(m, c,
                    (chip, r, st) -> full.at(left[chip], r, st), (chip, r, st) -> compact.at(left[chip], r, st), s, false, gap, room);
            if (fit.count == 0) break;
            for (int i = 0; i < left.length; i++) {
                if (!fit.shown[i]) continue;
                row[left[i]] = rows;
                rung[left[i]] = fit.rung;
                step[left[i]] = fit.steps[i];
                small[left[i]] = fit.compact[i];
            }
            next += fit.count;
            rows++;
        }
        return new RidingChipRows(row, rung, step, small, rows);
    }
}
