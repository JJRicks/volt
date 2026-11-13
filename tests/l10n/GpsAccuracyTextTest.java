package com.jjricks.volt;

import java.util.*;

/**
 * GPS accuracy's text (owner, Nov 12): "±12 ft" in either unit, whole axis marks with "≥" at a clipped top, riding
 * mode's plain states, the ride graph's ninth line and the riding reading's place among the chips and tiles.
 */
public final class GpsAccuracyTextTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        values();
        axis();
        states();
        graph();
        riding();
        stepDown();
        System.out.println("GpsAccuracyTextTest: " + checks + " checks passed (values, axis marks, states, the graph's line, the riding reading, its step-down)");
    }

    private static void values() {
        check(GpsAccuracyText.value(12.4, GpsAccuracy.FEET).toString().equals("±12 ft"), "feet: " + GpsAccuracyText.value(12.4, GpsAccuracy.FEET));
        check(GpsAccuracyText.value(3.6, GpsAccuracy.METRES).toString().equals("±4 m"), "metres: " + GpsAccuracyText.value(3.6, GpsAccuracy.METRES));
        check(GpsAccuracyText.value(0.3, GpsAccuracy.METRES).toString().equals("±1 m"), "never ±0 for a real radius");
        Formatted f = GpsAccuracyText.value(12, GpsAccuracy.FEET);
        String text = f.toString();
        check(f.numbers() == 1 && text.substring(f.numberStart(0), f.numberEnd(0)).equals("12"), "the number is the part riding mode shows large");
        check(text.substring(0, f.numberStart(0)).equals("±") && text.substring(f.numberEnd(0)).equals(" ft"), "± before it, the unit after it");
        // The same value switches with units everywhere (graph, cursor, chip, spoken): only the unit decides.
        double metres = 4;
        check(GpsAccuracyText.value(GpsAccuracy.shown(metres, GpsAccuracy.feet("mi")), GpsAccuracy.unit(GpsAccuracy.feet("mi"))).toString().equals("±13 ft"), "miles: feet");
        check(GpsAccuracyText.value(GpsAccuracy.shown(metres, GpsAccuracy.feet("dual")), GpsAccuracy.unit(GpsAccuracy.feet("dual"))).toString().equals("±13 ft"), "both: feet");
        check(GpsAccuracyText.value(GpsAccuracy.shown(metres, GpsAccuracy.feet("km")), GpsAccuracy.unit(GpsAccuracy.feet("km"))).toString().equals("±4 m"), "kilometres: metres");
    }

    private static RideChartData.Series line(double... values) {
        List<RideChartData.Value> points = new ArrayList<>();
        List<Double> shown = new ArrayList<>();
        for (int i = 0; i < values.length; i++) { points.add(new RideChartData.Value(i * 1000L, values[i], 0)); shown.add(values[i]); }
        return new RideChartData.Series(GpsAccuracy.KEY, GpsAccuracy.KEY, GpsAccuracy.FEET, GpsAccuracy.COLOR, GpsAccuracy.CHART_INDEX, points, GpsAccuracy.scale(shown, true));
    }

    private static void axis() {
        List<Double> typical = new ArrayList<>();
        for (int i = 0; i < 97; i++) typical.add(15d);
        double[] spiky = new double[100];
        for (int i = 0; i < 100; i++) spiky[i] = i < 97 ? 15 : 900;
        RideChartData.Series clipped = line(spiky);
        check(clipped.clipsAbove() && clipped.scale.max == 40, "a few bad fixes are clipped: " + clipped.scale.max);
        String[] marks = new String[5];
        for (int tick = 0; tick < 5; tick++) marks[tick] = GpsAccuracyText.axis(clipped, clipped.scale.at(1 - tick / 4d));
        check(Arrays.asList(marks).equals(Arrays.asList("≥40", "30", "20", "10", "0")), "whole marks, the clipped top reads ≥: " + Arrays.asList(marks));
        RideChartData.Series calm = line(15, 16, 14);
        check(!calm.clipsAbove() && GpsAccuracyText.axis(calm, calm.scale.max).equals("40"), "an unclipped top is a plain number");
        check(GpsAccuracyText.sample(clipped, 900).equals("±900 ft"), "inspecting a clipped fix shows its real value");
    }

    private static void states() {
        Set<String> seen = new HashSet<>();
        for (GpsAccuracy.State state : new GpsAccuracy.State[]{GpsAccuracy.State.NOT_RECORDING, GpsAccuracy.State.OFF, GpsAccuracy.State.WAITING, GpsAccuracy.State.NO_FIX}) {
            String text = GpsAccuracyText.state(state);
            check(text != null && !text.isEmpty() && !text.contains("±") && seen.add(text), "each state has its own plain words: " + state + " " + text);
        }
        check(GpsAccuracyText.state(GpsAccuracy.State.NOT_RECORDING).equals("Not recording") && GpsAccuracyText.state(GpsAccuracy.State.OFF).equals("Off")
                && GpsAccuracyText.state(GpsAccuracy.State.WAITING).equals("Waiting") && GpsAccuracyText.state(GpsAccuracy.State.NO_FIX).equals("No fix"), "the English states");
    }

    private static void graph() {
        List<RideChartData.Series> lines = RideChartData.forRide(Collections.<RideStore.Point>emptyList(), null, true, false, false);
        check(lines.size() == 9, "the ride graph offers its eight lines and GPS accuracy: " + lines.size());
        for (int i = 0; i < 8; i++) check(lines.get(i).key.equals(RideChartData.KEYS[i]) && lines.get(i).color == RideChartData.COLORS[i], "the eight lines keep their order and colours");
        RideChartData.Series accuracy = lines.get(8);
        check(accuracy.key.equals(GpsAccuracy.KEY) && accuracy.displayLabel().equals("GPS accuracy") && accuracy.notedLabel().equals("GPS accuracy"), "its legend name");
        check(!accuracy.available(), "a ride without GPS: the line is there with no data, as the other phone lines");
        check(!RideChartData.defaults().contains(GpsAccuracy.KEY), "off until chosen, as the other optional lines");
        check(RideChartData.forRide(Collections.<RideStore.Point>emptyList(), null, false, false, false).get(8).unit.equals(GpsAccuracy.METRES), "metres with kilometres");
    }

    private static void riding() {
        List<String> chips = RidingLayout.choices(RidingLayout.Kind.CHIP);
        check(chips.size() == 19 && chips.get(18).equals(RidingLayout.GPS_ACCURACY), "chips: the eighteen readings, then GPS accuracy: " + chips);
        check(RidingLayout.accepts(RidingLayout.Kind.TILE, RidingLayout.GPS_ACCURACY) && RidingLayout.accepts(RidingLayout.Kind.CHIP, RidingLayout.GPS_ACCURACY), "a tile or a chip");
        for (RidingLayout.Kind kind : new RidingLayout.Kind[]{RidingLayout.Kind.BIG, RidingLayout.Kind.HIGHLIGHT, RidingLayout.Kind.RING, RidingLayout.Kind.STRIP, RidingLayout.Kind.TOP, RidingLayout.Kind.TRACE})
            check(!RidingLayout.accepts(kind, RidingLayout.GPS_ACCURACY), "not a " + kind);
        check(!RidingLayout.chipNamesItself(RidingLayout.GPS_ACCURACY), "its chip carries its name");
        check("gps_accuracy".equals(RidingLayout.icon(RidingLayout.GPS_ACCURACY)), "its crosshair icon");
        for (RidingLayout.Content c : RidingLayout.CATALOG)
            if (!c.id.equals(RidingLayout.GPS_ACCURACY)) check(!"gps_accuracy".equals(RidingLayout.icon(c.id)), "no other reading uses it: " + c.id);
        for (String frame : RidingLayout.FRAMES)
            for (RidingLayout.Spot spot : RidingLayout.frameDef(frame).spots)
                check(!RidingLayout.GPS_ACCURACY.equals(spot.fallback), "no frame shows it until chosen: " + frame + "/" + spot.id);
        check(T.ridingLabelGpsAccuracy().equals("GPS accuracy") && T.ridingLabelGpsAccuracyShort().equals("GPS"), "its names, full and short");
    }

    /**
     * A state too long for its spot steps down to the no-reading dash instead of hiding the chip or drawing small
     * (Terra on #428): widths in dp as Trace's column and Halo's row measure them at 344 dp, 1x text.
     */
    private static void stepDown() {
        for (GpsAccuracy.State s : new GpsAccuracy.State[]{GpsAccuracy.State.NOT_RECORDING, GpsAccuracy.State.OFF, GpsAccuracy.State.WAITING, GpsAccuracy.State.NO_FIX})
            check(T.ridingNoValue().equals(GpsAccuracyText.compact(s)) && !GpsAccuracyText.state(s).equals(GpsAccuracyText.compact(s)), "each state steps down to the dash: " + s);
        check(GpsAccuracyText.compact(GpsAccuracy.State.FIX) == null, "a fix has no step-down form: its value is short");

        // Tiles: the words stay while they draw at a chip's value size or more, or at the tile's own largest size.
        float floor = GpsAccuracyText.STATE_MIN_SP;
        check(!RidingClockSeconds.keepsMarker(11, 60, floor), "Focus's bottom tile: \"Not recording\" at 11 dp shows the dash instead");
        check(RidingClockSeconds.keepsMarker(17, 60, floor) && RidingClockSeconds.keepsMarker(30, 60, floor), "at a chip's size or more the words stay");
        check(!RidingClockSeconds.keepsMarker(16.9f, 60, floor), "just under a chip's size, the dash");
        check(RidingClockSeconds.keepsMarker(14, 14, floor), "a spot whose largest size is under the floor keeps its words at that size");
        check(!RidingClockSeconds.keepsMarker(20, 60, floor * 1.4f), "at 1.4x text the floor grows with the chip's text");

        String gps = RidingLayout.GPS_ACCURACY, trip = RidingLayout.TRIP, time = RidingLayout.RIDE_TIME, temp = RidingLayout.BATTERY_TEMPERATURE;
        // Trace's column at 344 dp: 45% of the row, about 105 dp; GPS accuracy first, temperature second.
        int notRecording = 120, noFix = 50, dash = 18;
        Chip g = new Chip("gps-full", "gps-short", 92, 30, notRecording, dash), tc = Chip.namesItself(45);
        RidingChipFit plain = RidingChipFit.column(marks(g, tc), new String[]{gps, temp}, widths(false, g, tc), 105);
        check(!plain.shown[0] && plain.shown[1], "in full, \"Not recording\" steps aside in Trace's column (the finding)");
        RidingChipFit fit = RidingChipFit.stepping(marks(g, tc), new String[]{gps, temp}, widths(false, g, tc), widths(true, g, tc), new boolean[]{true, false}, true, 8, 105);
        check(fit.shown[0] && fit.compact[0] && fit.shown[1] && !fit.compact[1], "stepping: the GPS chip shows its dash and temperature stays");
        check(fit.rung == RidingChipFit.SHORT_NAMES && fit.steps[0] == RidingChipFit.NAME_SHORT && fit.steps[1] == -1, "\"GPS \u2014\", temperature unchanged");
        check(widths(true, g, tc).at(0, fit.rung, fit.steps[0]) <= 105 && widths(false, g, tc).at(1, fit.rung, fit.steps[1]) <= 105, "both fit the column");
        Chip nf = new Chip("gps-full", "gps-short", 92, 30, noFix, dash);
        RidingChipFit noFixFit = RidingChipFit.stepping(marks(nf, tc), new String[]{gps, temp}, widths(false, nf, tc), widths(true, nf, tc), new boolean[]{true, false}, true, 8, 105);
        check(noFixFit.shown[0] && !noFixFit.compact[0] && noFixFit.rung == RidingChipFit.ICON_NAMES, "\"No fix\" fits with the icon: its words stay");

        // A ride starting: "Not recording" (the dash), then a state that fits, then "Not recording" again. The dash on
        // screen is the same for every state, so the chip asks for a new fit whenever its state changes under the dash,
        // and that fit brings the words back.
        GpsAccuracy.State[] states = {GpsAccuracy.State.NOT_RECORDING, GpsAccuracy.State.OFF, GpsAccuracy.State.NOT_RECORDING,
                GpsAccuracy.State.WAITING, GpsAccuracy.State.NOT_RECORDING};
        int[] stateWidths = {notRecording, 30, notRecording, 48, notRecording};
        boolean shownCompact = false;
        String was = null;
        for (int i = 0; i < states.length; i++) {
            String now = GpsAccuracyText.state(states[i]);
            boolean refit = was == null || RidingChipFit.refitsCompact(shownCompact, was, now) || !shownCompact && !now.equals(was);
            check(refit, "a new state under the dash is fitted again: " + was + " to " + now);
            Chip c = new Chip("gps-full", "gps-short", 92, 30, stateWidths[i], dash);
            RidingChipFit f = RidingChipFit.stepping(marks(c, tc), new String[]{gps, temp}, widths(false, c, tc), widths(true, c, tc), new boolean[]{true, false}, true, 8, 105);
            boolean wantDash = states[i] == GpsAccuracy.State.NOT_RECORDING;
            check(f.shown[0] && f.compact[0] == wantDash && f.shown[1], now + (wantDash ? " shows the dash" : " shows its words") + ", temperature stays");
            shownCompact = f.compact[0];
            was = now;
        }
        check(RidingChipFit.refitsCompact(true, GpsAccuracyText.state(GpsAccuracy.State.NOT_RECORDING), GpsAccuracyText.state(GpsAccuracy.State.OFF)),
                "Not recording to Off under the dash: fit again");
        check(!RidingChipFit.refitsCompact(true, GpsAccuracyText.state(GpsAccuracy.State.OFF), GpsAccuracyText.state(GpsAccuracy.State.OFF)),
                "the same state again: nothing to fit");
        check(!RidingChipFit.refitsCompact(false, GpsAccuracyText.state(GpsAccuracy.State.OFF), GpsAccuracyText.state(GpsAccuracy.State.NOT_RECORDING)),
                "words showing: the new words' own text change measures the chip");

        // Halo's row at 344 dp (296 dp): trip, ride time, then GPS accuracy, which in full would be the chip to go.
        Chip tr = new Chip("trip-name", "trip-name", 32, 32, 50, dash), rt = new Chip("time-full", "time-short", 62, 34, 45, dash);
        String[] rowContents = {trip, time, gps};
        RidingChipFit rowPlain = RidingChipFit.row(marks(tr, rt, g), rowContents, widths(false, tr, rt, g), 8, 296);
        check(rowPlain.count == 2 && !rowPlain.shown[2], "in full the GPS chip steps aside from the row");
        RidingChipFit row = RidingChipFit.stepping(marks(tr, rt, g), rowContents, widths(false, tr, rt, g), widths(true, tr, rt, g), new boolean[]{false, false, true}, false, 8, 296);
        check(row.count == 3 && row.compact[2] && !row.compact[0] && !row.compact[1], "stepping: all three show, GPS as its dash");
        long used = 16;
        for (int i = 0; i < 3; i++) used += widths(i == 2, tr, rt, g).at(i, row.rung, row.steps[i]);
        check(used <= 296, "and they fit the row: " + used);

        // Nothing else changes: with GPS first its words take the row as before (the owner keeps that), and with no
        // chip able to step down the fit is the plain one.
        String[] first = {gps, trip, time};
        RidingChipFit before = RidingChipFit.row(marks(g, tr, rt), first, widths(false, g, tr, rt), 8, 296);
        RidingChipFit after = RidingChipFit.stepping(marks(g, tr, rt), first, widths(false, g, tr, rt), widths(true, g, tr, rt), new boolean[]{true, false, false}, false, 8, 296);
        check(before.shown[0] && same(before, after) && !after.compact[0], "a GPS chip that shows in full keeps its words, the rest as before");
        RidingChipFit none = RidingChipFit.stepping(marks(tr, rt, g), rowContents, widths(false, tr, rt, g), widths(true, tr, rt, g), new boolean[3], false, 8, 296);
        check(same(rowPlain, none), "no chip that can step down: the plain fit");
        RidingChipFit noneColumn = RidingChipFit.stepping(marks(g, tc), new String[]{gps, temp}, widths(false, g, tc), widths(true, g, tc), null, true, 8, 105);
        check(same(plain, noneColumn), "the same for a column");
    }

    private static boolean same(RidingChipFit a, RidingChipFit b) {
        return a.count == b.count && a.rung == b.rung && Arrays.equals(a.shown, b.shown) && Arrays.equals(a.steps, b.steps);
    }

    /** A chip as RidingScreen measures it: padding, then its name (full, short or icon) or its decorative icon, then its value. */
    private static final class Chip {
        final String full, shortName;
        final int fullWidth, shortWidth, value, compact;
        final boolean namesItself;
        Chip(String full, String shortName, int fullWidth, int shortWidth, int value, int compact) {
            this.full = full; this.shortName = shortName; this.fullWidth = fullWidth; this.shortWidth = shortWidth;
            this.value = value; this.compact = compact; this.namesItself = false;
        }
        private Chip(int value) {
            this.full = null; this.shortName = null; this.fullWidth = 0; this.shortWidth = 0;
            this.value = value; this.compact = value; this.namesItself = true;
        }
        static Chip namesItself(int value) { return new Chip(value); }
        String[] marks(int i) {
            String icon = RidingChipFit.iconMark("icon" + i);
            return namesItself ? new String[]{null, null, icon} : new String[]{full, shortName, icon};
        }
        int width(int rung, int step, boolean compactForm) {
            int padding = rung == RidingChipFit.TIGHT ? 12 : 30;
            int mark;
            if (namesItself) mark = rung < RidingChipFit.NO_ICONS ? 24 : 0;
            else mark = step == RidingChipFit.NAME_FULL ? fullWidth + 6 : step == RidingChipFit.NAME_SHORT ? shortWidth + 6 : 24;
            return padding + mark + (compactForm ? compact : value);
        }
    }

    private static String[][] marks(Chip... chips) {
        String[][] out = new String[chips.length][];
        for (int i = 0; i < chips.length; i++) out[i] = chips[i].marks(i);
        return out;
    }

    private static RidingChipFit.Widths widths(final boolean compact, final Chip... chips) {
        return new RidingChipFit.Widths() {
            @Override public int at(int chip, int rung, int step) { return chips[chip].width(rung, step, compact); }
        };
    }
}
