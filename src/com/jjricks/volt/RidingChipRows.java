package com.jjricks.volt;

/**
 * Focus's chips in rows under its big number (the owner, Nov 14: "in a horizontal row right above range at this pace,
 * and then if any of them spill over they can spill into a second row just fine").
 *
 * Every row shares one rung, chosen before the chips are split, so the names read alike from row to row: row 1 never
 * shows icons while row 2 spells "Voltage" out. At each rung, in slot order, a chip that doesn't fit beside the ones
 * before it starts the next row, and a chip too wide for a row even on its own is hidden. Once a chip misses the last row
 * the height allows, it and every chip after it are hidden: the chip that hides is always the last in slot order, never
 * an earlier one with a narrower later chip in its place. Before that happens, a GPS chip shows its compact dash when that
 * lets more chips show (as RidingChipFit.steadyStepping does for Halo and Trace). The rung kept is the one that keeps the
 * earliest chips in slot order, then needs the fewest rows (names shorten before a chip moves down), then reads widest.
 * The widths are each chip's widest value, so the split never changes while riding: a usual-room reading drawn narrower
 * than that, or widened once, never moves a chip to another row (fitting).
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
     * The rows' height: from the split alone, so from every chip picked, never from which of them show now. A drive-mode
     * chip waiting for its mode keeps its row, and the number above keeps its size (the council on #496).
     */
    int height(int rowHeight, int gap) {
        return rows == 0 ? 0 : rows * rowHeight + (rows - 1) * gap;
    }

    /**
     * Each chip's left edge in its row, or -1 for one that doesn't show: hidden by the split, or not showing now (a
     * drive-mode chip with no mode yet). The chips that show close up in slot order, as Halo's do when the drive-mode chip
     * steps aside; none changes row.
     */
    int[] lefts(int[] widths, boolean[] showing, int gap) {
        int[] left = new int[row.length], end = new int[Math.max(1, rows)];
        boolean[] started = new boolean[Math.max(1, rows)];
        for (int i = 0; i < row.length; i++) {
            left[i] = -1;
            if (row[i] < 0 || !showing[i]) continue;
            int r = row[i];
            left[i] = started[r] ? end[r] + gap : 0;
            end[r] = left[i] + widths[i];
            started[r] = true;
        }
        return left;
    }

    /** The widest row of the chips showing (lefts), chips and gaps: the unit line's row hugs it. */
    int across(int[] widths, boolean[] showing, int gap) {
        int[] left = lefts(widths, showing, gap);
        int most = 0;
        for (int i = 0; i < row.length; i++) if (left[i] >= 0) most = Math.max(most, left[i] + widths[i]);
        return most;
    }

    /**
     * The chips that show at the widths they draw at now: showing, less each row's last chips while that row is wider than
     * room (its first chip always stays). The split comes from each chip's widest values, and a chip draws at most that
     * wide until it keeps a value past them (a ±1000 ft fix): then its row's last chip steps aside, as Halo's does. No
     * chip ever changes row, so the rows, how many there are and the number above them never change (the council on #500).
     */
    boolean[] fitting(int[] widths, boolean[] showing, int gap, int room) {
        boolean[] out = showing.clone();
        for (int r = 0; r < rows; r++) {
            while (true) {
                int used = 0, count = 0, last = -1;
                for (int i = 0; i < row.length; i++) {
                    if (row[i] != r || !out[i]) continue;
                    used += (count > 0 ? gap : 0) + widths[i];
                    count++;
                    last = i;
                }
                if (used <= room || count <= 1) break;
                out[last] = false;
            }
        }
        return out;
    }

    /**
     * The one that keeps the earlier chip in slot order (so a tighter rung that lets an earlier chip show beats a wider
     * one that shows a later chip in its place, even more of them: the chip that hides is the last); then, with the same
     * chips, fewer rows. A tie keeps the wider rung, tried first.
     */
    private static boolean better(RidingChipRows a, RidingChipRows b) {
        for (int i = 0; i < a.row.length; i++)
            if ((a.row[i] >= 0) != (b.row[i] >= 0)) return a.row[i] >= 0;
        return a.rows < b.rows;
    }

    private static int shown(RidingChipRows r) {
        int n = 0;
        for (int x : r.row) if (x >= 0) n++;
        return n;
    }

    /**
     * The split at one rung: in full, and, when that hides a chip, with every chip that has a compact form in it, taken
     * when more chips show. Each from the widths alone, so neither changes with the value on show.
     */
    private static RidingChipRows at(String[][] marks, String[] contents, RidingChipFit.Widths full,
                                     RidingChipFit.Widths compact, boolean[] canStep, int gap, int room, int most, int rung) {
        RidingChipRows plain = at(marks, contents, full, compact, canStep, gap, room, most, rung, false);
        if (canStep == null || shown(plain) == marks.length) return plain;
        boolean any = false;
        for (int i = 0; i < marks.length; i++) any |= canStep[i] && !plain.compact[i];
        if (!any) return plain;
        RidingChipRows small = at(marks, contents, full, compact, canStep, gap, room, most, rung, true);
        return shown(small) > shown(plain) ? small : plain;
    }

    private static RidingChipRows at(String[][] marks, String[] contents, RidingChipFit.Widths full,
                                     RidingChipFit.Widths compact, boolean[] canStep, int gap, int room, int most, int rung,
                                     boolean everyCompact) {
        int of = marks.length;
        // The chips that fit a row on their own, joined in slot order: a chip joins when it and every chip already in fit
        // a row each, their names worked out among themselves. So a chip only holds a mark once it's in: two range chips
        // too wide while each keeps its full name (the other has "Range" and the route) don't both hide; the first shows
        // with its icon and the second, which would push the first back to its full name, steps aside. One pass: it
        // always ends.
        boolean[] set = new boolean[of];
        boolean[] small = new boolean[of];
        for (int i = 0; i < of; i++) {
            set[i] = true;
            if (!alone(marks, contents, full, compact, canStep, room, rung, everyCompact, set, small)) set[i] = false;
        }
        alone(marks, contents, full, compact, canStep, room, rung, everyCompact, set, small);
        int[] steps = RidingChipFit.steps(marks, contents, set, rung);
        // In slot order, a chip that doesn't fit beside the ones before it starts the next row; past the last row, it and
        // every chip after it are hidden.
        int[] row = new int[of];
        java.util.Arrays.fill(row, -1);
        int rows = 0;
        long used = 0;
        boolean past = false;
        for (int i = 0; i < of; i++) {
            if (!set[i]) continue;
            if (past) { set[i] = false; continue; }
            int width = small[i] ? compact.at(i, rung, steps[i]) : full.at(i, rung, steps[i]);
            if (rows == 0 || used + gap + width > room) {
                if (rows == most) { past = true; set[i] = false; continue; }
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

    /**
     * True when every chip in set fits a row on its own at this rung, its names worked out among the set, in full or, for
     * a chip with a compact form, in that form (always in it with everyCompact); marks in small the chips in compact form.
     */
    private static boolean alone(String[][] marks, String[] contents, RidingChipFit.Widths full, RidingChipFit.Widths compact,
                                 boolean[] canStep, int room, int rung, boolean everyCompact, boolean[] set, boolean[] small) {
        int[] steps = RidingChipFit.steps(marks, contents, set, rung);
        boolean all = true;
        for (int i = 0; i < marks.length; i++) {
            small[i] = false;
            if (!set[i]) continue;
            boolean shrinks = canStep != null && canStep[i];
            boolean fitsFull = full.at(i, rung, steps[i]) <= room, fitsSmall = shrinks && compact.at(i, rung, steps[i]) <= room;
            if (fitsSmall && (everyCompact || !fitsFull)) small[i] = true;
            else if (!fitsFull) all = false;
        }
        return all;
    }
}
