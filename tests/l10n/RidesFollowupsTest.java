package com.jjricks.volt;

import java.util.Arrays;
import java.util.Locale;

/**
 * F5: the head Rides text against the base English pinned in RidesFollowupsEnglishTest, and the point of
 * the change: a value's number and unit are found from its parts, whatever a language's template does.
 */
public final class RidesFollowupsTest {
    private static int checks;
    private static final double[] VALUES = {0, 0.004, 0.005, 0.5, 1, 1.005, 2.5, 3.14159, 9.995, 12.3, 99.5, 100, 999.999,
            1234.5, 99999.9, -0.004, -0.5, -12.3, -1234.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    private static final String[] UNITS = {"mi", "km", "dual"};

    private RidesFollowupsTest() {}

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    /** A Formatted's number and rest say what splitNumber said of its base English. */
    private static void parts(String baseText, Formatted head, String what) {
        eq(baseText, head.toString(), what + " text");
        String[] base = RidesFollowupsEnglishTest.splitNumber(baseText), found = RideText.numberAndRest(head);
        eq(base == null ? null : Arrays.asList(base), found == null ? null : Arrays.asList(found), what + " parts");
        if (found != null) {
            eq(0, head.numberStart(0), what + " number start");
            eq(base[0].length(), head.numberEnd(0), what + " number end");
            eq(baseText, RidesFollowupsEnglishTest.styled(base), what + " styled");
        }
    }

    private static void matchesBase() {
        for (String units : UNITS) for (double v : VALUES) {
            parts(RidesFollowupsEnglishTest.distance(v, units), RideText.distance(v, units), "distance " + v + units);
            parts(RidesFollowupsEnglishTest.speed(v, units), RideText.speed(v, units), "speed " + v + units);
            for (double w : VALUES) {
                parts(RidesFollowupsEnglishTest.efficiency(v, null, w, null, units), RideText.efficiency(v, w, true, units), "net " + v + units);
                parts(RidesFollowupsEnglishTest.efficiency(null, v, null, w, units), RideText.efficiency(v, w, false, units), "throughput " + v + units);
                String ascent = RidesFollowupsEnglishTest.ascent(v, w, units);
                double first = units.equals("km") ? v : v * 3.280839895;
                parts(ascent, RideText.approximate(ascent, Fmt.decimal(Locale.US, first, 0)), "ascent " + v + units);
            }
        }
        for (String unit : new String[] {"Wh", "V", "W", "ft", "m"}) for (int decimals = 0; decimals < 3; decimals++) for (double v : VALUES)
            parts(RidesFollowupsEnglishTest.quantity(v, unit, decimals), RideText.quantity(v, unit, decimals), "quantity " + v + unit);
        for (double v : VALUES) {
            long points = Math.round(Math.abs(v));
            eq(RidesFollowupsEnglishTest.battery(v), Double.isFinite(v) ? T.fmtPercent(Fmt.integer(points)) : T.ridesNotMeasured(), "battery " + v);
            if (points != 0) eq(RidesFollowupsEnglishTest.change(v), (v >= 0 ? "−" : "+") + T.fmtPercent(Fmt.integer(points)), "change " + v);
            else eq(RidesFollowupsEnglishTest.change(v), T.fmtPercent(Fmt.integer(points)), "change " + v);
        }
        eq("Not measured", T.ridesNotMeasured(), "the sentinel's English");
        eq(RidesFollowupsEnglishTest.battery(null), T.ridesNotMeasured(), "battery none");
        eq(RidesFollowupsEnglishTest.change(null), T.ridesNotMeasured(), "change none");
        eq(RidesFollowupsEnglishTest.sensorSummary(false, true), T.ridesSensorsMotionStart(), "motion alone starts the sentence");
        eq(RidesFollowupsEnglishTest.sensorSummary(true, true), T.ridesSensorsGps() + " · " + T.ridesSensorsElevation() + " · " + T.ridesSensorsMotion(), "all sensors");
        eq(RidesFollowupsEnglishTest.sensorSummary(false, false), T.ridesSensorsScooterOnly(), "no sensors");
        for (String accuracy : new String[] {"5 ft", "—"})
            eq(RidesFollowupsEnglishTest.pointSpoken(2, "GPS", "3.0 mph", true, accuracy), T.ridesPointGpsAccuracy("GPS", "3.0 mph", accuracy), "point accuracy");
        for (int n = 0; n < 12; n++) {
            eq(RidesFollowupsEnglishTest.compareRow(n), T.ridesCompareTwoSavedCount(n, n), "compare count " + n);
            eq(RidesFollowupsEnglishTest.chosen(n), T.ridesCompareChosenCount(n, n), "chosen " + n);
        }
        eq(RidesFollowupsEnglishTest.SPEED, T.ridesLegendTitleSpeed(), "legend speed");
        eq(RidesFollowupsEnglishTest.GRADE, T.ridesLegendTitleGrade(), "legend grade");
        eq(RidesFollowupsEnglishTest.ACCEL, T.ridesLegendTitleAccel(), "legend accel");
        eq(RidesFollowupsEnglishTest.ELEVATION, T.ridesLegendTitleElev(), "legend elevation");
        eq(RidesFollowupsEnglishTest.POWER, T.ridesLegendTitlePower(), "legend power");
        eq(RidesFollowupsEnglishTest.MISSING, T.ridesLegendMissing(), "legend missing");
        eq(RidesFollowupsEnglishTest.MISSING, M.ridesLegendMissing().toString(), "legend missing as a Msg");
    }

    /** A template that puts the unit first with a no-break space: splitNumber read this as words. */
    private static void anyTemplateOrder() {
        T.Source saved = T.source();
        T.install(new T.Source() {
            public String string(int id) { return id == T.FMT_QUANTITY ? "%2$s %1$s" : null; }
            public String plural(int id, long quantity) { return null; }
            public String language() { return "en"; }
        });
        try {
            Formatted distance = RideText.distance(6.8, "km");
            eq("km 6.80", distance.toString(), "unit first");
            eq(Arrays.asList("6.80", "km"), Arrays.asList(RideText.numberAndRest(distance)), "number found after the unit");
            eq(3, distance.numberStart(0), "number start after the unit");
            eq(7, distance.numberEnd(0), "number end");
            eq(null, RidesFollowupsEnglishTest.splitNumber(distance.toString()), "the base split could not read it");
            Formatted approx = RideText.approximate("≈km 12 / km 3", "12");
            eq(4, approx.numberStart(0), "a mark before a unit stays with the unit");
        } finally {
            T.install(saved);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[] {Locale.US, Locale.GERMANY, new Locale("ar", "EG"), new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                matchesBase();
                anyTemplateOrder();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("RidesFollowupsTest: " + checks + " checks passed");
    }
}
