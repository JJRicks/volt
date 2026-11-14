package com.jjricks.volt;

/**
 * Focus's chips in rows under the big number (the owner, Nov 14): in slot order, a chip that doesn't fit beside the ones
 * before it starts the next row; one too wide for a row of its own is hidden. Worked out from widest widths only.
 */
public final class RidingChipRowsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final int GAP = 21;

    public static void main(String[] args) {
        oneRow();
        spills();
        ownRow();
        hidden();
        most();
        names();
        oneStyle();
        priority();
        compactAlone();
        lastRowStops();
        earlierBeatsMore();
        compactBeforeHiding();
        twoRanges();
        waitingMode();
        System.out.println("RidingChipRowsTest: " + checks + " checks passed (one row, a spill to a second row, a row each, "
                + "a chip too wide, the most rows, names shortening before a spill, one name style for every row, the earlier chip kept, a GPS chip in its compact form, "
                + "the last row stops at the first chip that misses, the earlier chip over more chips, GPS compact before a later chip hides, "
                + "two range chips, a drive-mode chip waiting for its mode)");
    }

    /** Widths that don't change with the rung or name step. */
    private static RidingChipFit.Widths fixed(int... widths) { return (chip, rung, step) -> widths[chip]; }
    private static RidingChipRows split(int room, int most, int... widths) {
        int n = widths.length;
        return RidingChipRows.split(new String[n][], new String[n], fixed(widths), fixed(widths), new boolean[n], GAP, room, most);
    }

    private static void oneRow() {
        RidingChipRows r = split(818, 3, 246, 161, 329);
        check(r.rows == 1 && r.row[0] == 0 && r.row[1] == 0 && r.row[2] == 0, "three that fit share one row");
        RidingChipRows none = split(818, 3);
        check(none.rows == 0, "no chips, no rows");
    }

    private static void spills() {
        RidingChipRows r = split(600, 3, 246, 161, 329);
        check(r.rows == 2 && r.row[0] == 0 && r.row[1] == 0 && r.row[2] == 1, "the third spills to a second row, in slot order");
        RidingChipRows again = split(600, 3, 246, 161, 329);
        check(java.util.Arrays.equals(r.row, again.row), "the same widths, the same split");
    }

    private static void ownRow() {
        RidingChipRows r = split(400, 3, 300, 300, 300);
        check(r.rows == 3 && r.row[0] == 0 && r.row[1] == 1 && r.row[2] == 2, "chips that can't share a row take one each");
    }

    private static void hidden() {
        RidingChipRows r = split(420, 3, 300, 500, 90);
        check(r.row[1] == -1, "a chip too wide for a row of its own is hidden");
        check(r.row[0] == 0 && r.row[2] == 0 && r.rows == 1, "and the next one takes its place beside the first");
    }

    private static void most() {
        RidingChipRows r = split(400, 2, 300, 300, 300);
        check(r.rows == 2 && r.row[2] == -1, "past the most rows the height allows, the last chip is hidden");
        RidingChipRows zero = split(400, 0, 300);
        check(zero.rows == 0 && zero.row[0] == -1, "no height, no rows");
    }

    private static void names() {
        // Names shorten before a chip moves down: at a tighter rung the two fit one row.
        RidingChipFit.Widths ladder = (chip, rung, step) -> chip == 0 ? 300 - rung * 40 : 280 - rung * 40;
        RidingChipRows r = RidingChipRows.split(new String[2][], new String[2], ladder, ladder, new boolean[2], GAP, 450, 3);
        check(r.rows == 1 && r.rung > RidingChipFit.FULL, "a tighter rung keeps them on one row");
    }

    private static void oneStyle() {
        // Three chips that need two rows at any rung: the first two share a row only from rung 2, the third is the same
        // width at every rung. Fitted row by row, the second row would read in full while the first shows short names;
        // every row takes rung 2.
        RidingChipFit.Widths ladder = (chip, rung, step) -> chip == 0 ? 300 - rung * 40 : chip == 1 ? 280 - rung * 40 : 400;
        RidingChipRows r = RidingChipRows.split(new String[3][], new String[3], ladder, ladder, new boolean[3], GAP, 450, 3);
        check(r.rows == 2 && r.row[0] == 0 && r.row[1] == 0 && r.row[2] == 1, "two rows, the third chip below");
        check(r.rung == RidingChipFit.SHORT_NAMES, "one rung for both rows, the widest at which the first two share a row");
        // Names: the rung's name step reaches every chip, whichever row it is on.
        String[][] marks = {{"Average speed", "Avg", "\u0000icon:avg"}, {"Max speed", "Max", "\u0000icon:max"}, {"Voltage", "Volt", "\u0000icon:v"}};
        String[] contents = {"average_speed", "max_speed", "voltage"};
        RidingChipFit.Widths named = (chip, rung, step) -> (chip == 2 ? 400 : step == RidingChipFit.NAME_FULL ? 300 : step == RidingChipFit.NAME_SHORT ? 200 : 120);
        RidingChipRows n = RidingChipRows.split(marks, contents, named, named, new boolean[3], GAP, 450, 3);
        check(n.step[0] == n.step[1] && n.step[1] == n.step[2] && n.step[0] == RidingChipFit.NAME_SHORT,
                "row 2's chip takes the same short name as row 1's, not its full one");
    }

    private static void priority() {
        // Chip 1 is too wide for a row at the widest rungs and fits from rung 3; with room for two rows, a wide rung would
        // show chips 0 and 2, and rung 3 shows chips 0 and 1. The earlier chip in slot order wins: the same chip (the last)
        // always hides.
        RidingChipFit.Widths w = (chip, rung, step) -> chip == 1 ? (rung < RidingChipFit.ICON_NAMES ? 700 : 450) : chip == 0 ? 500 : 465;
        RidingChipRows r = RidingChipRows.split(new String[3][], new String[3], w, w, new boolean[3], GAP, 639, 2);
        check(r.row[0] == 0 && r.row[1] == 1 && r.row[2] == -1, "a tighter rung that keeps the earlier chip beats a wider one that swaps in a later one");
        check(r.rung == RidingChipFit.ICON_NAMES, "at the widest rung where chip 1 fits");
    }

    private static void compactAlone() {
        // A GPS chip too wide in full for a row of its own shows its compact dash rather than hiding.
        RidingChipFit.Widths full = fixed(300, 600), dash = fixed(300, 90);
        RidingChipRows r = RidingChipRows.split(new String[2][], new String[2], full, dash, new boolean[]{false, true}, GAP, 450, 3);
        check(r.row[1] == 0 && r.compact[1] && !r.compact[0], "the GPS chip shows compact beside the first");
    }
    private static void lastRowStops() {
        // One row of 400: the first two don't fit together, the first and third would. The second misses the last row, so
        // it and every chip after it hide; the third never takes the second's place (the council on #496).
        RidingChipRows r = split(400, 1, 200, 300, 80);
        check(r.row[0] == 0 && r.row[1] == -1 && r.row[2] == -1 && r.rows == 1, "rows [0,-1,-1]: the chip that hides is the last, and every one after it");
        RidingChipRows two = split(400, 2, 300, 300, 70, 300);
        check(two.row[0] == 0 && two.row[1] == 1 && two.row[2] == 1 && two.row[3] == -1, "with two rows, the chip that misses the second hides, with the rest");
        // A chip too wide for any row on its own still steps aside alone, and the next takes its place (as before).
        RidingChipRows alone = split(400, 1, 200, 500, 80);
        check(alone.row[0] == 0 && alone.row[1] == -1 && alone.row[2] == 0, "a chip too wide even alone is the only exception");
    }

    private static void earlierBeatsMore() {
        // Chip 1 is too wide for a row at the wide rungs, where chips 0, 2 and 3 share the row; from the icon rung chip 1 fits
        // beside chip 0 and the row ends there. The rung that keeps chip 1 wins, though it shows fewer: the last chips hide.
        RidingChipFit.Widths w = (chip, rung, step) -> chip == 1 ? (rung < RidingChipFit.ICON_NAMES ? 500 : 250) : 100;
        RidingChipRows r = RidingChipRows.split(new String[4][], new String[4], w, w, new boolean[4], GAP, 400, 1);
        check(r.row[0] == 0 && r.row[1] == 0 && r.row[2] == -1 && r.row[3] == -1, "chips 0 and 1 show; 2 and 3 hide");
        check(r.rung == RidingChipFit.ICON_NAMES, "at the widest rung where chip 1 fits");
    }

    private static void compactBeforeHiding() {
        // The reviewer's numbers: GPS, Current and Clock at 150, 90 and 70 in full, GPS 80 in its compact form, a gap of 8,
        // one row of 300. In full the Clock misses the row; with GPS's dash all three fit, so GPS shows compact.
        RidingChipFit.Widths full = fixed(150, 90, 70), dash = fixed(80, 90, 70);
        boolean[] canStep = {true, false, false};
        RidingChipRows r = RidingChipRows.split(new String[3][], new String[3], full, dash, canStep, 8, 300, 1);
        check(r.row[0] == 0 && r.row[1] == 0 && r.row[2] == 0, "all three shown");
        check(r.compact[0] && !r.compact[1] && !r.compact[2], "GPS in its compact form");
        // With room for all three in full, GPS keeps its words.
        RidingChipRows roomy = RidingChipRows.split(new String[3][], new String[3], full, dash, canStep, 8, 330, 1);
        check(roomy.row[2] == 0 && !roomy.compact[0], "GPS stays in full when nothing would hide");
        // Its dash only when it shows more: with the Clock too wide even then, GPS keeps its words and the Clock hides.
        RidingChipRows none = RidingChipRows.split(new String[3][], new String[3], fixed(150, 90, 140), fixed(80, 90, 140), canStep, 8, 300, 1);
        check(none.row[2] == -1 && !none.compact[0], "GPS keeps its words when its dash gains nothing");
        // Two rows: GPS in full fits one, Current and Clock the next; nothing hides, so nothing changes.
        RidingChipRows rows = RidingChipRows.split(new String[3][], new String[3], full, dash, canStep, 8, 200, 2);
        check(rows.row[0] == 0 && rows.row[1] == 1 && rows.row[2] == 1 && !rows.compact[0], "a spill to a second row is not a hidden chip");
    }

    private static void twoRanges() {
        // Scooter's range and Volt's range share the route icon and the short name "Range": while both are in, each keeps
        // its full name, too wide for the row. Only the first joins; it then shows its icon and the second steps aside.
        String[][] marks = {{"Scooter's range", "Range", RidingChipFit.iconMark("route")}, {"Volt's range", "Range", RidingChipFit.iconMark("route")}};
        String[] contents = {"range_scooter", "range_volt"};
        RidingChipFit.Widths w = (chip, rung, step) -> step == RidingChipFit.NAME_FULL ? 500 : step == RidingChipFit.NAME_SHORT ? 450 : 150;
        RidingChipRows r = RidingChipRows.split(marks, contents, w, w, new boolean[2], GAP, 400, 3);
        check(r.row[0] == 0 && r.row[1] == -1, "the first range chip shows, the second hides");
        check(r.step[0] == RidingChipFit.NAME_ICON, "the first shows the route icon, freed by the second");
        // The same with three of them, and the order kept: the earliest shows.
        String[][] three = {marks[0], marks[1], {"Range at this pace", "Range", RidingChipFit.iconMark("route")}};
        RidingChipRows t = RidingChipRows.split(three, new String[]{"range_scooter", "range_volt", "range_at_pace"}, w, w, new boolean[3], GAP, 400, 3);
        check(t.row[0] == 0 && t.row[1] == -1 && t.row[2] == -1 && t.step[0] == RidingChipFit.NAME_ICON, "of three, the first shows with its icon");
    }

    private static void waitingMode() {
        // A drive-mode chip waiting for its mode is split with the rest: the rows, their height and so the number above
        // never change with it; it doesn't draw and the chips after it on its row close up (the council on #496).
        int rowHeight = 40, gap = GAP;
        RidingChipRows r = split(400, 2, 120, 150, 300);
        check(r.row[0] == 0 && r.row[1] == 0 && r.row[2] == 1, "mode and clock on row 1, average speed on row 2");
        boolean[] present = {true, true, true}, missing = {false, true, true};
        int[] widths = {120, 150, 300};
        check(r.height(rowHeight, gap) == 2 * rowHeight + gap, "two rows' height, whether the mode is in or not");
        int[] with = r.lefts(widths, present, gap), without = r.lefts(widths, missing, gap);
        check(with[0] == 0 && with[1] == 120 + gap && with[2] == 0, "with the mode: mode, then the clock beside it");
        check(without[0] == -1 && without[1] == 0 && without[2] == 0, "without: the clock closes up, on the same row");
        check(r.across(widths, missing, gap) == 300 && r.across(widths, present, gap) == 300, "the widest row");
        // Alone, it keeps its (empty) row.
        RidingChipRows alone = split(400, 2, 120);
        check(alone.rows == 1 && alone.row[0] == 0 && alone.height(rowHeight, gap) == rowHeight, "a mode chip alone keeps its row");
        check(alone.lefts(new int[]{120}, new boolean[]{false}, gap)[0] == -1 && alone.across(new int[]{120}, new boolean[]{false}, gap) == 0,
                "and draws nothing there while it waits");
        // Starting row 2, it keeps row 2, and the chip after it stays there.
        RidingChipRows second = split(400, 2, 300, 200, 90);
        check(second.row[0] == 0 && second.row[1] == 1 && second.row[2] == 1 && second.rows == 2, "the mode chip starts row 2");
        int[] left = second.lefts(new int[]{300, 200, 90}, new boolean[]{true, false, true}, gap);
        check(left[2] == 0 && second.row[2] == 1 && second.height(rowHeight, gap) == 2 * rowHeight + gap,
                "without its mode, the chip after it starts row 2 and both rows stay");
    }
}
