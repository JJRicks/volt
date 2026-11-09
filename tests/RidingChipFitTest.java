package com.jjricks.volt;

/** The riding chip row's ladder: spacing, then icons, then the last chip; never a partial chip. */
public final class RidingChipFitTest {
    private static final int GAP = 8;

    // Synthetic widths: mode, temperature with its icon, clock with its icon.
    private static final int[][] THREE = {
            {96, 90, 104},   // FULL
            {90, 90, 104},   // NO_SPACING: only the mode chip has letter spacing
            {90, 66, 80}};   // NO_ICONS: the two icon chips lose 24

    public static void main(String[] args) {
        // 96 + 90 + 104 + 2 gaps = 306.
        expect(THREE, 306, 3, RidingChipFit.FULL, "exact fit stays full");
        expect(THREE, 305, 3, RidingChipFit.NO_SPACING, "one short loses letter spacing first");
        expect(THREE, 300, 3, RidingChipFit.NO_SPACING, "exact fit without spacing keeps the icons");
        expect(THREE, 299, 3, RidingChipFit.NO_ICONS, "one short of that loses the icons");
        expect(THREE, 252, 3, RidingChipFit.NO_ICONS, "exact fit without icons keeps all three");
        // 251 can't hold three at any rung, so the clock goes; 96 + 90 + 8 = 194 fits as designed.
        expect(THREE, 251, 2, RidingChipFit.FULL, "the last chip goes only after every rung, and the rest get their icons back");
        expect(THREE, 194, 2, RidingChipFit.FULL, "two chips, exact fit");
        expect(THREE, 193, 2, RidingChipFit.NO_SPACING, "two chips tighten the same way");
        expect(THREE, 188, 2, RidingChipFit.NO_SPACING, "two chips, exact without spacing");
        expect(THREE, 187, 2, RidingChipFit.NO_ICONS, "two chips lose icons next");
        expect(THREE, 164, 2, RidingChipFit.NO_ICONS, "two chips, exact without icons");
        expect(THREE, 163, 1, RidingChipFit.FULL, "then one chip");
        expect(THREE, 95, 1, RidingChipFit.NO_SPACING, "one chip without spacing");
        expect(THREE, 89, 0, RidingChipFit.FULL, "no room shows nothing rather than a cut chip");
        expect(THREE, Integer.MAX_VALUE, 3, RidingChipFit.FULL, "an unbounded row shows everything as designed");

        expect(new int[][]{{}, {}, {}}, 300, 0, RidingChipFit.FULL, "no wanted chips");
        // No overflow from huge widths: the sum is long.
        int big = Integer.MAX_VALUE / 2 + 1;
        expect(new int[][]{{big, big}, {big, big}, {big, big}}, Integer.MAX_VALUE, 1, RidingChipFit.FULL, "huge widths don't overflow");
    }

    private static void expect(int[][] widths, int room, int count, int rung, String name) {
        RidingChipFit fit = RidingChipFit.choose(widths, GAP, room);
        if (fit.count != count || fit.rung != rung)
            throw new AssertionError(name + ": room " + room + " gave " + fit.count + " chips at rung " + fit.rung
                    + ", expected " + count + " at rung " + rung);
    }
}
