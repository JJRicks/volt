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
        System.out.println("GpsAccuracyTextTest: " + checks + " checks passed (values, axis marks, states, the graph's line, the riding reading)");
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
}
