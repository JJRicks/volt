package com.jjricks.volt;

/**
 * How many riding-mode chips a row can show, and how tight they must get to do it. A narrow row
 * gives up its decoration before it gives up a reading: first the drive mode's letter spacing, then
 * the reading names shorten (full, then abbreviated, then an icon), then the icons that only
 * decorate (battery temperature's thermometer, the clock's face) go, then some padding, and only
 * then the last chip. The rung applies to the whole row so the chips stay alike. Text is never
 * shrunk or broken: a chip shows whole or not at all.
 *
 * A chip that holds a reading whose value doesn't name itself (Average speed, Odometer and the
 * rest) always carries a name or an icon beside its value. Drive mode, battery temperature and the
 * clock name themselves ("SPORT", "31 °C", "12:48") and look as they always have.
 */
final class RidingChipFit {
    /**
     * Rungs, widest first; each keeps the one before: as designed (full names); no letter spacing;
     * short names; names as icons; no decorative icons; tight padding.
     */
    static final int FULL = 0, NO_SPACING = 1, SHORT_NAMES = 2, ICON_NAMES = 3, NO_ICONS = 4, TIGHT = 5, RUNGS = 6;
    /** A chip's name ladder: its full name, its short name, its icon. */
    static final int NAME_FULL = 0, NAME_SHORT = 1, NAME_ICON = 2;

    /** A chip's whole width at a rung and name step (-1 for a chip without names). */
    interface Widths {
        int at(int chip, int rung, int step);
    }

    /** How many chips show; a later chip is lower priority. */
    final int count;
    /** The rung the shown chips use: the widest one at which they all fit. */
    final int rung;
    /** Which chips show, by position: in a row, the first count of them. */
    final boolean[] shown;
    /** Each shown chip's name step at that rung, worked out against the chips that show; -1 for the rest. */
    final int[] steps;

    private RidingChipFit(int count, int rung, boolean[] shown, int[] steps) {
        this.count = count;
        this.rung = rung;
        this.shown = shown;
        this.steps = steps;
    }

    private static boolean[] first(int count, int of) {
        boolean[] out = new boolean[of];
        for (int i = 0; i < count && i < of; i++) out[i] = true;
        return out;
    }

    private static int count(boolean[] set) {
        int n = 0;
        for (boolean s : set) if (s) n++;
        return n;
    }

    /** Each chip's name step at a rung against the chips in set; -1 outside it. */
    static int[] steps(String[][] marks, String[] contents, boolean[] set, int rung) {
        int[] out = new int[marks.length];
        for (int chip = 0; chip < marks.length; chip++) out[chip] = set[chip] ? nameStep(marks, contents, set, chip, rung) : -1;
        return out;
    }

    /** Fixed widths with no names, [rung][chip]. */
    private static Widths fixed(final int[][] widths) {
        return new Widths() { @Override public int at(int chip, int rung, int step) { return widths[rung][chip]; } };
    }

    private static String[][] unnamed(int of) { return new String[of][]; }

    /** A row with fixed widths and no names to work out (see {@link #row}). */
    static RidingChipFit choose(int[][] widths, int gap, int room) {
        int of = widths[FULL].length;
        return row(unnamed(of), new String[of], fixed(widths), gap, room);
    }

    /**
     * Keeps as many chips as fit at any rung, and among those takes the widest rung, so a row that
     * has already lost a chip keeps its icons when the rest have room for them. Each candidate row
     * works out its names among its own chips: a chip that stepped aside no longer holds a mark, so
     * the rest can take it (and so can be narrower).
     *
     * @param marks each chip's marks (see {@link #nameStep}), in priority order
     * @param gap the space between two chips
     * @param room the row's width
     */
    static RidingChipFit row(String[][] marks, String[] contents, Widths widths, int gap, int room) {
        int of = marks.length;
        for (int count = of; count > 0; count--) {
            boolean[] set = first(count, of);
            for (int rung = FULL; rung < RUNGS; rung++) {
                int[] steps = steps(marks, contents, set, rung);
                long used = gap * (count - 1L);
                for (int chip = 0; chip < count; chip++) used += widths.at(chip, rung, steps[chip]);
                if (used <= room) return new RidingChipFit(count, rung, set, steps);
            }
        }
        return new RidingChipFit(0, FULL, first(0, of), steps(marks, contents, first(0, of), FULL));
    }

    /** A column with fixed widths and no names to work out (see {@link #column}). */
    static RidingChipFit stack(int[][] widths, int room) {
        int of = widths[FULL].length;
        return column(unnamed(of), new String[of], fixed(widths), room);
    }

    /**
     * Chips stacked in a column (Trace's, beside the big number), each as wide as room at most. In
     * priority order, a chip joins when it and the chips already in fit at a shared rung, their names
     * worked out among themselves; a chip that can't join steps aside on its own, holding no mark. They
     * take the widest rung at which they all fit.
     */
    static RidingChipFit column(String[][] marks, String[] contents, Widths widths, int room) {
        int of = marks.length;
        boolean[] set = new boolean[of];
        for (int chip = 0; chip < of; chip++) {
            set[chip] = true;
            if (widestColumnRung(marks, contents, widths, set, room) < 0) set[chip] = false;
        }
        int rung = Math.max(FULL, widestColumnRung(marks, contents, widths, set, room));
        return new RidingChipFit(count(set), rung, set, steps(marks, contents, set, rung));
    }

    private static int widestColumnRung(String[][] marks, String[] contents, Widths widths, boolean[] set, int room) {
        if (count(set) == 0) return FULL;
        for (int rung = FULL; rung < RUNGS; rung++) {
            int[] steps = steps(marks, contents, set, rung);
            boolean all = true;
            for (int chip = 0; chip < set.length && all; chip++) all = !set[chip] || widths.at(chip, rung, steps[chip]) <= room;
            if (all) return rung;
        }
        return -1;
    }

    /** The step of the name ladder a rung asks for. */
    static int nameStep(int rung) {
        return rung <= NO_SPACING ? NAME_FULL : rung == SHORT_NAMES ? NAME_SHORT : NAME_ICON;
    }

    /**
     * The name step a chip takes at a rung: the one the rung asks for, unless that step is missing or
     * another chip in the group (one showing a different reading) carries the same mark, in which case it
     * keeps a longer one. So Average and Max speed never both show the speedometer alone, and two range
     * readings never both show "Range". A decorative icon (the clock face, the thermometer) counts as
     * taken while it shows, so not from {@link #NO_ICONS} on.
     *
     * @param marks each chip's marks, [chip][step], null where the chip has no such step; a chip that
     *              names itself lists only its decorative icon
     * @param contents each chip's content id, so two chips showing the same reading never push each other
     * @param set the chips that show; only they hold marks (null: every chip)
     * @return the step, or -1 for a chip without names
     */
    static int nameStep(String[][] marks, String[] contents, boolean[] set, int chip, int rung) {
        String[] own = marks[chip];
        if (own == null || own.length <= NAME_FULL || own[NAME_FULL] == null) return -1;
        int step = Math.min(nameStep(rung), own.length - 1);
        while (step > NAME_FULL && (own[step] == null || taken(marks, contents, set, chip, own[step], rung))) step--;
        return step;
    }

    /** {@link #nameStep(String[][], String[], boolean[], int, int)} with every chip showing. */
    static int nameStep(String[][] marks, String[] contents, int chip, int rung) {
        return nameStep(marks, contents, null, chip, rung);
    }

    private static boolean taken(String[][] marks, String[] contents, boolean[] set, int chip, String mark, int rung) {
        for (int other = 0; other < marks.length; other++) {
            if (other == chip || marks[other] == null || (set != null && !set[other])) continue;
            if (contents[other] != null && contents[other].equals(contents[chip])) continue;
            boolean decorative = marks[other].length <= NAME_FULL || marks[other][NAME_FULL] == null;
            if (decorative && rung >= NO_ICONS) continue;
            for (String m : marks[other]) if (mark.equals(m)) return true;
        }
        return false;
    }

    /** A mark for an icon, apart from any name a translation could give. */
    static String iconMark(String key) { return key == null ? null : "\u0000icon:" + key; }
}
