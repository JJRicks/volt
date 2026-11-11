package com.jjricks.volt;

/**
 * The riding chip row's ladder: spacing, then names shorten to their short form and an icon, then decorative icons, then
 * padding, then the last chip; never a partial chip. A name or icon two different readings share is never their only mark.
 */
public final class RidingChipFitTest {
    private static final int GAP = 8;

    // Synthetic widths: mode, temperature with its icon, clock with its icon. They name themselves, so the name rungs
    // (SHORT_NAMES, ICON_NAMES) change nothing for them: the defaults step down as they always have.
    private static final int[][] THREE = {
            {96, 90, 104},   // FULL
            {90, 90, 104},   // NO_SPACING: only the mode chip has letter spacing
            {90, 90, 104},   // SHORT_NAMES: no names to shorten
            {90, 90, 104},   // ICON_NAMES: no names to turn into icons
            {90, 66, 80},    // NO_ICONS: the two icon chips lose 24
            {76, 52, 66}};   // TIGHT: every chip loses 14 of padding

    public static void main(String[] args) {
        // Three chips: 96 + 90 + 104 + 2 gaps = 306.
        expect(THREE, 306, 3, RidingChipFit.FULL, "exact fit stays full");
        expect(THREE, 305, 3, RidingChipFit.NO_SPACING, "one short loses letter spacing first");
        expect(THREE, 300, 3, RidingChipFit.NO_SPACING, "exact fit without spacing keeps the icons");
        expect(THREE, 299, 3, RidingChipFit.NO_ICONS, "one short of that loses the icons");
        expect(THREE, 252, 3, RidingChipFit.NO_ICONS, "exact fit without icons keeps the padding");
        expect(THREE, 251, 3, RidingChipFit.TIGHT, "one short of that tightens the padding");
        expect(THREE, 210, 3, RidingChipFit.TIGHT, "exact fit at the tightest rung keeps all three");
        // 209 can't hold three at any rung, so the last chip goes; 96 + 90 + 8 = 194 fits as designed.
        expect(THREE, 209, 2, RidingChipFit.FULL, "the last chip goes only after every rung, and the rest get their icons back");
        expect(THREE, 194, 2, RidingChipFit.FULL, "two chips, exact fit");
        expect(THREE, 193, 2, RidingChipFit.NO_SPACING, "two chips tighten the same way");
        expect(THREE, 187, 2, RidingChipFit.NO_ICONS, "two chips lose icons next");
        expect(THREE, 163, 2, RidingChipFit.TIGHT, "two chips tighten the padding last");
        expect(THREE, 136, 2, RidingChipFit.TIGHT, "two chips, exact at the tightest rung");
        expect(THREE, 135, 1, RidingChipFit.FULL, "then one chip");
        expect(THREE, 95, 1, RidingChipFit.NO_SPACING, "one chip without spacing");
        expect(THREE, 89, 1, RidingChipFit.TIGHT, "one chip at the tightest rung");
        expect(THREE, 75, 0, RidingChipFit.FULL, "no room shows nothing rather than a cut chip");
        expect(THREE, Integer.MAX_VALUE, 3, RidingChipFit.FULL, "an unbounded row shows everything as designed");

        // The first chip stepped aside: two remain with one gap between them, 66 + 8 + 80 = 154 without icons.
        int[][] two = {{90, 104}, {90, 104}, {90, 104}, {90, 104}, {66, 80}, {52, 66}};
        expect(two, 154, 2, RidingChipFit.NO_ICONS, "the two left fit exactly with one gap");
        expect(two, 153, 2, RidingChipFit.TIGHT, "one short of that tightens the padding");

        // Three wide named chips: "Average speed 14.2 mph", "Max speed 21.0 mph", "Odometer 1,234.5 mi". Their full names go
        // first, then short names ("Avg", "Max", "Odo."), then icons where they have one, and only then padding and a chip.
        int[][] wide = {
                {190, 170, 180},  // FULL
                {190, 170, 180},  // NO_SPACING: no drive mode here
                {130, 120, 140},  // SHORT_NAMES
                {120, 110, 140},  // ICON_NAMES: Avg and Max share the speedometer, so they'd keep their names (see below)
                {120, 110, 140},  // NO_ICONS: names stay; only decorative icons go
                {104, 94, 124}};  // TIGHT
        expect(wide, 556, 3, RidingChipFit.FULL, "three wide chips at full names");
        expect(wide, 555, 3, RidingChipFit.SHORT_NAMES, "one short of full names: short names, not a lost chip");
        expect(wide, 406, 3, RidingChipFit.SHORT_NAMES, "exact at short names");
        expect(wide, 405, 3, RidingChipFit.ICON_NAMES, "then icons for names");
        expect(wide, 386, 3, RidingChipFit.ICON_NAMES, "exact at icon names");
        expect(wide, 385, 3, RidingChipFit.TIGHT, "then tight padding");
        expect(wide, 338, 3, RidingChipFit.TIGHT, "exact at tight");
        expect(wide, 337, 2, RidingChipFit.SHORT_NAMES, "then the last chip goes, and the two left widen back to the first rung that fits");
        expect(wide, 368, 3, RidingChipFit.TIGHT, "three at tight beat two at a wider rung");

        expect(new int[][]{{}, {}, {}, {}, {}, {}}, 300, 0, RidingChipFit.FULL, "no wanted chips");
        // No overflow from huge widths: the sum is long.
        int big = Integer.MAX_VALUE / 2 + 1;
        int[][] huge = new int[RidingChipFit.RUNGS][];
        for (int r = 0; r < RidingChipFit.RUNGS; r++) huge[r] = new int[]{big, big};
        expect(huge, Integer.MAX_VALUE, 1, RidingChipFit.FULL, "huge widths don't overflow");

        stacks();
        names();
        System.out.println("RidingChipFitTest passed");
    }

    /** Trace's column: each chip as wide as the room at most; one rung for all; a chip too wide anywhere steps aside alone. */
    private static void stacks() {
        int[][] two = {{150, 104}, {150, 104}, {110, 104}, {96, 104}, {96, 80}, {82, 66}};
        stack(two, 150, new boolean[]{true, true}, RidingChipFit.FULL, "both fit as designed");
        stack(two, 149, new boolean[]{true, true}, RidingChipFit.SHORT_NAMES, "the wide one shortens its name");
        stack(two, 103, new boolean[]{true, true}, RidingChipFit.NO_ICONS, "then the decorative icon goes, one rung for both");
        stack(two, 81, new boolean[]{false, true}, RidingChipFit.NO_ICONS, "the first is too wide at every rung: the second shows alone");
        stack(two, 65, new boolean[]{false, false}, RidingChipFit.FULL, "nothing fits: nothing shows");
        // The first chip can't fit at any rung, the second can: the second still shows, at its own widest rung.
        int[][] first = {{300, 90}, {300, 90}, {300, 90}, {300, 90}, {300, 66}, {290, 52}};
        stack(first, 100, new boolean[]{false, true}, RidingChipFit.FULL, "a chip too wide steps aside alone");
        // Each fits at some rung, but no rung holds both (a wide icon): the lower priority one goes.
        int[][] apart = {{200, 200}, {200, 200}, {90, 200}, {200, 90}, {200, 200}, {200, 200}};
        stack(apart, 100, new boolean[]{true, false}, RidingChipFit.SHORT_NAMES, "no shared rung: the later chip goes");
        stack(new int[][]{{}, {}, {}, {}, {}, {}}, 100, new boolean[0], RidingChipFit.FULL, "no chips");
        stack(two, 0, new boolean[]{false, false}, RidingChipFit.FULL, "no room");
    }

    private static void names() {
        String speed = RidingChipFit.iconMark("speed"), route = RidingChipFit.iconMark("route"), clock = RidingChipFit.iconMark("clock");
        // Average and Max speed side by side: their short names differ, their icons don't, so they keep names.
        String[][] marks = {{"Average speed", "Avg", speed}, {"Max speed", "Max", speed}, {null, null, clock}};
        String[] contents = {"average_speed", "max_speed", "clock"};
        step(marks, contents, 0, RidingChipFit.FULL, RidingChipFit.NAME_FULL, "full names first");
        step(marks, contents, 0, RidingChipFit.NO_SPACING, RidingChipFit.NAME_FULL, "spacing rung keeps full names");
        step(marks, contents, 0, RidingChipFit.SHORT_NAMES, RidingChipFit.NAME_SHORT, "short name");
        step(marks, contents, 0, RidingChipFit.ICON_NAMES, RidingChipFit.NAME_SHORT, "shared speedometer: Avg keeps its short name");
        step(marks, contents, 1, RidingChipFit.TIGHT, RidingChipFit.NAME_SHORT, "and Max keeps its own");
        step(marks, contents, 2, RidingChipFit.ICON_NAMES, -1, "the clock names itself");
        // Average speed beside the clock and trip: its speedometer is its own, so it can stand for the name.
        String[][] alone = {{"Average speed", "Avg", speed}, {"Trip", "Trip", route}, {null, null, clock}};
        String[] aloneContents = {"average_speed", "trip", "clock"};
        step(alone, aloneContents, 0, RidingChipFit.ICON_NAMES, RidingChipFit.NAME_ICON, "a unique icon replaces the name");
        step(alone, aloneContents, 0, RidingChipFit.TIGHT, RidingChipFit.NAME_ICON, "and keeps it at the tight rung");
        // Ride time's clock face is the clock chip's decoration: Ride time keeps "Time".
        String[][] times = {{"Ride time", "Time", clock}, {null, null, clock}};
        step(times, new String[]{"ride_time", "clock"}, 0, RidingChipFit.ICON_NAMES, RidingChipFit.NAME_SHORT, "a decorative icon counts as taken");
        // Two range readings share "Range" and the route icon: both keep their full names.
        String[][] ranges = {{"Range at this pace", "Range", route}, {"Volt's range", "Range", route}};
        String[] rangeContents = {"range_at_pace", "range_volt"};
        step(ranges, rangeContents, 0, RidingChipFit.TIGHT, RidingChipFit.NAME_FULL, "two ranges never both say Range");
        step(ranges, rangeContents, 1, RidingChipFit.SHORT_NAMES, RidingChipFit.NAME_FULL, "either of them");
        // The same reading in two chips is the rider's choice: they don't push each other to full names.
        String[][] same = {{"Average speed", "Avg", speed}, {"Average speed", "Avg", speed}};
        step(same, new String[]{"average_speed", "average_speed"}, 1, RidingChipFit.ICON_NAMES, RidingChipFit.NAME_ICON, "a repeated reading");
        // No icon (Voltage): the icon step falls back to the short name; one name: the short is the full.
        String[][] volts = {{"Voltage", "Voltage", null}};
        step(volts, new String[]{"voltage"}, 0, RidingChipFit.TIGHT, RidingChipFit.NAME_SHORT, "no icon keeps the name");
        // A chip with no names yet (before its first reading) or a missing mark list never throws.
        step(new String[][]{{null, null, null}}, new String[]{"odometer"}, 0, RidingChipFit.TIGHT, -1, "no names yet");
        step(new String[][]{null, {"Trip", "Trip", route}}, new String[]{null, "trip"}, 1, RidingChipFit.TIGHT, RidingChipFit.NAME_ICON, "a null neighbour");
        step(new String[][]{{}}, new String[]{"trip"}, 0, RidingChipFit.TIGHT, -1, "an empty mark list");
        if (RidingChipFit.iconMark(null) != null || RidingChipFit.iconMark("speed").equals("speed"))
            throw new AssertionError("an icon mark never equals a name");
    }

    private static void expect(int[][] widths, int room, int count, int rung, String name) {
        RidingChipFit fit = RidingChipFit.choose(widths, GAP, room);
        if (fit.count != count || fit.rung != rung)
            throw new AssertionError(name + ": room " + room + " gave " + fit.count + " chips at rung " + fit.rung
                    + ", expected " + count + " at rung " + rung);
        for (int i = 0; i < fit.shown.length; i++)
            if (fit.shown[i] != (i < count)) throw new AssertionError(name + ": a row shows its first chips only");
    }

    private static void stack(int[][] widths, int room, boolean[] shown, int rung, String name) {
        RidingChipFit fit = RidingChipFit.stack(widths, room);
        if (!java.util.Arrays.equals(fit.shown, shown) || fit.rung != rung)
            throw new AssertionError(name + ": room " + room + " gave " + java.util.Arrays.toString(fit.shown) + " at rung " + fit.rung
                    + ", expected " + java.util.Arrays.toString(shown) + " at rung " + rung);
        int count = 0;
        for (boolean s : shown) if (s) count++;
        if (fit.count != count) throw new AssertionError(name + ": count " + fit.count);
        for (int i = 0; i < shown.length; i++)
            if (shown[i] && widths[rung][i] > room) throw new AssertionError(name + ": chip " + i + " shown wider than the room");
    }

    private static void step(String[][] marks, String[] contents, int chip, int rung, int expected, String name) {
        int got = RidingChipFit.nameStep(marks, contents, chip, rung);
        if (got != expected) throw new AssertionError(name + ": chip " + chip + " at rung " + rung + " took step " + got + ", expected " + expected);
    }
}
