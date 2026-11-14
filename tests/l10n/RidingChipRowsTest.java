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
        System.out.println("RidingChipRowsTest: " + checks + " checks passed (one row, a spill to a second row, a row each, "
                + "a chip too wide, the most rows, names shortening before a spill, one name style for every row, the earlier chip kept, a GPS chip in its compact form)");
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
}
