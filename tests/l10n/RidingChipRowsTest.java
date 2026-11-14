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
        System.out.println("RidingChipRowsTest: " + checks + " checks passed (one row, a spill to a second row, a row each, "
                + "a chip too wide, the most rows, names shortening before a spill)");
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
        check(r.rows == 1 && r.rung[0] == r.rung[1] && r.rung[0] > RidingChipFit.FULL, "a tighter rung keeps them on one row");
    }
}
