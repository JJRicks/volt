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
        namesAmongShown();
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

    /** Synthetic named chips: a width per name step (-1 = names itself, then full, short, icon); TIGHT takes 14 off. */
    private static RidingChipFit.Widths named(final int[][] byStep) {
        return (chip, rung, step) -> byStep[chip][step + 1] - (rung == RidingChipFit.TIGHT ? 14 : 0)
                - (rung >= RidingChipFit.NO_ICONS && step < 0 ? 24 : 0);
    }

    /**
     * Names are worked out among the chips that show, for each set the fit tries: a chip that stepped aside holds no mark,
     * and a decorative icon that has gone holds none either. (Review of a0d0cb87: the marks were worked out once against
     * every wanted chip, so a dropped chip still pushed others to longer names and a second chip could go too.)
     */
    private static void namesAmongShown() {
        String route = RidingChipFit.iconMark("route"), battery = RidingChipFit.iconMark("battery"), clock = RidingChipFit.iconMark("clock");
        // Volt's range, Battery, Scooter's range. The two ranges share "Range" and the route icon.
        String[][] marks = {{"Volt's range", "Range", route}, {"Battery", "Battery", battery}, {"Scooter's range", "Range", route}};
        String[] contents = {"range_volt", "battery", "range_scooter"};
        int[][] byStep = {{0, 150, 90, 70}, {0, 100, 100, 60}, {0, 160, 90, 70}};
        RidingChipFit.Widths widths = named(byStep);
        // 140: three never fit. Without Scooter's range, Volt's range may take the route icon: 70 + 8 + 60 = 138. Worked out
        // against all three (a0d0cb87), Volt's range kept its full name (150, 136 tight) and only it showed.
        RidingChipFit fit = RidingChipFit.row(marks, contents, widths, GAP, 140);
        if (fit.count != 2 || fit.rung != RidingChipFit.ICON_NAMES || fit.steps[0] != RidingChipFit.NAME_ICON || fit.steps[2] != -1)
            throw new AssertionError("ranges row at 140: " + fit.count + " chips at rung " + fit.rung + " steps " + java.util.Arrays.toString(fit.steps)
                    + ", expected Volt's range on its icon beside Battery");
        // All three shown: neither range may say "Range" or show the route alone.
        fit = RidingChipFit.row(marks, contents, widths, GAP, 1000);
        if (fit.count != 3 || fit.steps[0] != RidingChipFit.NAME_FULL || fit.steps[2] != RidingChipFit.NAME_FULL)
            throw new AssertionError("ranges row at 1000: " + java.util.Arrays.toString(fit.steps));
        sweepRow("ranges", marks, contents, byStep);
        // Ride time beside the clock: the clock face is the clock chip's while it shows; from NO_ICONS on, it's gone.
        String[][] times = {{"Ride time", "Time", clock}, {null, null, clock}};
        String[] timeContents = {"ride_time", "clock"};
        boolean[] both = {true, true};
        if (RidingChipFit.nameStep(times, timeContents, both, 0, RidingChipFit.ICON_NAMES) != RidingChipFit.NAME_SHORT
                || RidingChipFit.nameStep(times, timeContents, both, 0, RidingChipFit.NO_ICONS) != RidingChipFit.NAME_ICON
                || RidingChipFit.nameStep(times, timeContents, both, 0, RidingChipFit.TIGHT) != RidingChipFit.NAME_ICON)
            throw new AssertionError("a decorative icon is taken only while it shows");
        sweepRow("times", times, timeContents, new int[][]{{0, 100, 60, 44}, {90, 0, 0, 0}});
        // Trace's column, room 100: the two ranges need their full names together (150, 160), which never fit; alone,
        // Volt's range takes "Range" (90). a0d0cb87 kept its full name and showed nothing.
        String[][] two = {marks[0], marks[2]};
        String[] twoContents = {"range_volt", "range_scooter"};
        int[][] twoBy = {byStep[0], byStep[2]};
        fit = RidingChipFit.column(two, twoContents, named(twoBy), 100);
        if (!java.util.Arrays.equals(fit.shown, new boolean[]{true, false}) || fit.rung != RidingChipFit.SHORT_NAMES || fit.steps[0] != RidingChipFit.NAME_SHORT)
            throw new AssertionError("ranges column at 100: " + java.util.Arrays.toString(fit.shown) + " rung " + fit.rung + " steps " + java.util.Arrays.toString(fit.steps));
        sweepColumn("ranges column", two, twoContents, twoBy);
        // A column of a wide first chip and two ranges: the first steps aside alone, and the ranges still fit each other out.
        String[][] three = {{"Recording time", "Rec", clock}, marks[0], marks[2]};
        sweepColumn("mixed column", three, new String[]{"recording_time", "range_volt", "range_scooter"}, new int[][]{{0, 400, 380, 360}, byStep[0], byStep[2]});
    }

    /** At every room: the shown chips fit with the steps the fit reports, a chip never comes back on a narrower row, the
     *  fit is the same when worked out again, and no shown chip keeps a longer name than the chips that show force. */
    private static void sweepRow(String name, String[][] marks, String[] contents, int[][] byStep) {
        RidingChipFit.Widths widths = named(byStep);
        int last = Integer.MAX_VALUE;
        for (int room = 1000; room >= 0; room--) {
            RidingChipFit fit = RidingChipFit.row(marks, contents, widths, GAP, room);
            RidingChipFit again = RidingChipFit.row(marks, contents, widths, GAP, room);
            if (fit.rung != again.rung || !java.util.Arrays.equals(fit.shown, again.shown) || !java.util.Arrays.equals(fit.steps, again.steps))
                throw new AssertionError(name + " at " + room + ": not the same twice");
            if (fit.count > last) throw new AssertionError(name + " at " + room + ": a chip came back");
            last = fit.count;
            long used = GAP * Math.max(0, fit.count - 1L);
            for (int chip = 0; chip < marks.length; chip++) if (fit.shown[chip]) used += widths.at(chip, fit.rung, fit.steps[chip]);
            if (used > room) throw new AssertionError(name + " at " + room + ": needs " + used);
            fair(name + " at " + room, marks, contents, fit);
        }
    }

    private static void sweepColumn(String name, String[][] marks, String[] contents, int[][] byStep) {
        RidingChipFit.Widths widths = named(byStep);
        for (int room = 1000; room >= 0; room--) {
            RidingChipFit fit = RidingChipFit.column(marks, contents, widths, room);
            RidingChipFit again = RidingChipFit.column(marks, contents, widths, room);
            if (fit.rung != again.rung || !java.util.Arrays.equals(fit.shown, again.shown) || !java.util.Arrays.equals(fit.steps, again.steps))
                throw new AssertionError(name + " at " + room + ": not the same twice");
            for (int chip = 0; chip < marks.length; chip++)
                if (fit.shown[chip] && widths.at(chip, fit.rung, fit.steps[chip]) > room) throw new AssertionError(name + " at " + room + ": chip " + chip + " too wide");
            fair(name + " at " + room, marks, contents, fit);
        }
    }

    /** A shown chip that kept a longer name than its rung asks did so only because a shown chip holds the shorter mark. */
    private static void fair(String name, String[][] marks, String[] contents, RidingChipFit fit) {
        for (int chip = 0; chip < marks.length; chip++) {
            if (!fit.shown[chip] || fit.steps[chip] < 0) continue;
            int asked = Math.min(RidingChipFit.nameStep(fit.rung), marks[chip].length - 1);
            for (int k = fit.steps[chip] + 1; k <= asked; k++) {
                if (marks[chip][k] == null) continue;
                boolean held = false;
                for (int other = 0; other < marks.length; other++) {
                    if (other == chip || !fit.shown[other] || marks[other] == null || contents[other].equals(contents[chip])) continue;
                    if (marks[other][0] == null && fit.rung >= RidingChipFit.NO_ICONS) continue;
                    for (String m : marks[other]) held |= marks[chip][k].equals(m);
                }
                if (!held) throw new AssertionError(name + ": chip " + chip + " kept step " + fit.steps[chip] + " at rung " + fit.rung + " for no shown chip");
            }
        }
        for (int a = 0; a < marks.length; a++)
            for (int b = a + 1; b < marks.length; b++) {
                if (!fit.shown[a] || !fit.shown[b] || fit.steps[a] < 0 || fit.steps[b] < 0 || contents[a].equals(contents[b])) continue;
                if (marks[a][fit.steps[a]].equals(marks[b][fit.steps[b]])) throw new AssertionError(name + ": chips " + a + " and " + b + " look alike");
            }
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
