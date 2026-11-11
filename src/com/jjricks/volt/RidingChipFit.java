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

    /** How many chips show, from the first; a later chip is lower priority. */
    final int count;
    /** The rung the shown chips use: the widest one at which they all fit. */
    final int rung;
    /** Which chips show, by position: in a row, the first count of them. */
    final boolean[] shown;

    private RidingChipFit(int count, int rung, boolean[] shown) {
        this.count = count;
        this.rung = rung;
        this.shown = shown;
    }

    private static boolean[] first(int count, int of) {
        boolean[] out = new boolean[of];
        for (int i = 0; i < count && i < of; i++) out[i] = true;
        return out;
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
        int of = widths[FULL].length;
        for (int count = of; count > 0; count--)
            for (int rung = FULL; rung < RUNGS; rung++) {
                long used = gap * (count - 1L);
                for (int chip = 0; chip < count; chip++) used += widths[rung][chip];
                if (used <= room) return new RidingChipFit(count, rung, first(count, of));
            }
        return new RidingChipFit(0, FULL, first(0, of));
    }

    /**
     * Chips stacked in a column (Trace's, beside the big number), each as wide as room at most. A chip
     * too wide at every rung steps aside on its own; then the lowest-priority chips go until the rest
     * share a rung, and they take the widest one at which they all fit.
     */
    static RidingChipFit stack(int[][] widths, int room) {
        int of = widths[FULL].length;
        boolean[] keep = new boolean[of];
        for (int chip = 0; chip < of; chip++)
            for (int rung = FULL; rung < RUNGS && !keep[chip]; rung++) keep[chip] = widths[rung][chip] <= room;
        while (true) {
            int count = 0;
            for (boolean k : keep) if (k) count++;
            if (count == 0) return new RidingChipFit(0, FULL, keep);
            for (int rung = FULL; rung < RUNGS; rung++) {
                boolean all = true;
                for (int chip = 0; chip < of && all; chip++) all = !keep[chip] || widths[rung][chip] <= room;
                if (all) return new RidingChipFit(count, rung, keep);
            }
            for (int chip = of - 1; chip >= 0; chip--) if (keep[chip]) { keep[chip] = false; break; }
        }
    }

    /** The step of the name ladder a rung asks for. */
    static int nameStep(int rung) {
        return rung <= NO_SPACING ? NAME_FULL : rung == SHORT_NAMES ? NAME_SHORT : NAME_ICON;
    }

    /**
     * The name step a chip takes at a rung: the one the rung asks for, unless that step is missing or
     * another chip in the row (one showing a different reading) carries the same mark, in which case it
     * keeps a longer one. So Average and Max speed never both show the speedometer alone, and two range
     * readings never both show "Range".
     *
     * @param marks each chip's marks, [chip][step], null where the chip has no such step; a chip that
     *              names itself lists only its decorative icon, which still counts as taken
     * @param contents each chip's content id, so two chips showing the same reading never push each other
     * @return the step, or -1 for a chip without names
     */
    static int nameStep(String[][] marks, String[] contents, int chip, int rung) {
        String[] own = marks[chip];
        if (own == null || own.length <= NAME_FULL || own[NAME_FULL] == null) return -1;
        int step = Math.min(nameStep(rung), own.length - 1);
        while (step > NAME_FULL && (own[step] == null || taken(marks, contents, chip, own[step]))) step--;
        return step;
    }

    private static boolean taken(String[][] marks, String[] contents, int chip, String mark) {
        for (int other = 0; other < marks.length; other++) {
            if (other == chip || marks[other] == null) continue;
            if (contents[other] != null && contents[other].equals(contents[chip])) continue;
            for (String m : marks[other]) if (mark.equals(m)) return true;
        }
        return false;
    }

    /** A mark for an icon, apart from any name a translation could give. */
    static String iconMark(String key) { return key == null ? null : "\u0000icon:" + key; }
}
