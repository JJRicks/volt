package com.jjricks.volt;

/**
 * Where Focus's chips go (the owner, Nov 14: "D on phones, and the one you recommended for folding").
 *
 * On a phone, upright or sideways, they sit in rows under the big number, just above the row under it (RidingChipRows).
 * On a wide screen (the Fold's inner screen, a tablet) they sit on the line the number's unit draws on, right of "mph"
 * (RidingUnitLine), when every chip picked fits there in one row with the number at its full size; otherwise in the same
 * rows as on a phone.
 *
 * The choice is made once per layout: from the spot's size, the units and every chip picked (each at its widest value,
 * whether it shows now or not), never from the readings on show. So the chips never jump between the unit line and the
 * rows while riding; a rotation, a fold or unfold, or another text size builds the screen again and chooses again.
 */
final class RidingChipPlace {
    private RidingChipPlace() { }

    /**
     * The least smallest-side width, dp, of a wide screen. The same 600 dp the riding screen's narrow status pill uses,
     * taken on the window's smallest side so a phone turned sideways stays a phone: the Fold's inner screen (673 dp)
     * and a tablet are wide; every phone (480 dp or less across when upright) is not.
     */
    static final int WIDE_MIN_DP = 600;

    /** True for a window whose smallest side is at least WIDE_MIN_DP. */
    static boolean wide(int smallestWidthDp) { return smallestWidthDp >= WIDE_MIN_DP; }

    /**
     * True when the chips go on the unit line: a wide screen, a number with its unit under it, room right of the unit, and
     * every chip picked shown in one row of that room (rows: RidingChipRows.split with one row at most).
     */
    static boolean onUnitLine(boolean wide, boolean unitBelow, boolean hasUnit, int room, RidingChipRows oneRow) {
        if (!wide || !unitBelow || !hasUnit || room <= 0 || oneRow == null) return false;
        for (int r : oneRow.row) if (r < 0) return false;
        return oneRow.rows <= 1;
    }

    /** The choice, worked out on demand. */
    interface Choice { boolean onUnitLine(); }

    /**
     * Holds the choice for a layout: asked again with the same size and units, it answers as it did, without asking the
     * choice again, so nothing that changes while riding (a reading, a drive mode coming in, a chip widening once for a
     * value past its samples) can move the chips between the unit line and the rows. Another size or units chooses again.
     */
    static final class Latch {
        private boolean chosen, onUnitLine;
        private int width, height;
        private String units;

        boolean choose(int width, int height, String units, Choice choice) {
            if (chosen && width == this.width && height == this.height && java.util.Objects.equals(units, this.units))
                return onUnitLine;
            chosen = true;
            this.width = width;
            this.height = height;
            this.units = units;
            onUnitLine = choice.onUnitLine();
            return onUnitLine;
        }
    }
}
