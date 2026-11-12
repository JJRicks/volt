package com.jjricks.volt;

import java.util.Locale;

/**
 * The ride map popup batch's own suite: the catalog templates that replaced the popup's signed lines
 * (maps_route_grade, maps_route_accel, maps_route_elevation_ft, maps_route_elevation_m) render the
 * base English that RideMapPopupEnglishTest (G5) pinned, for every sign, rounding and edge value, and
 * the text comes from the catalog.
 *
 * RouteMapView needs Android, so each method here is the head method copied token for token (the
 * comment names it), and is compared with the base copy in RideMapPopupEnglishTest.
 */
public final class RideMapPopupTextTest {
    private static int checks;

    private RideMapPopupTextTest() {}

    /** Every id answered with its key in marks, so a moved text is told from English. */
    private static final T.Source KEYS = new T.Source() {
        public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + (T.isFormatted(id) ? ":%1$s" : "") + "›"; }
        public String plural(int id, long quantity) { return null; }
        public String language() { return "en"; }
    };

    private static void eq(String expected, CharSequence actual, String what) {
        checks++;
        String shown = actual == null ? null : actual.toString();
        if (expected == null ? shown != null : !expected.equals(shown))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + shown + ">");
    }

    // ----- RouteMapView's head methods -----

    /** RouteMapView.grade(). */
    static String grade(Double percent) {
        return percent == null ? null : gradeLine(Fmt.signedDecimal(Locale.US, percent, 1));
    }

    /** RouteMapView.gradeLine(). */
    static String gradeLine(String signedPercent) {
        return T.mapsRouteGrade(signedPercent);
    }

    /** RouteMapView.accel(). */
    static String accel(Double mps2) {
        return mps2 == null ? null : accelLine(Fmt.signedDecimal(Locale.US, mps2, 2));
    }

    /** RouteMapView.accelLine(). */
    static String accelLine(String signedMps2) {
        return T.mapsRouteAccel(signedMps2);
    }

    /** RouteMapView.elevation(). */
    static String elevation(double relativeAltitudeM, boolean imperial) {
        String signed = Fmt.signedDecimal(Locale.US, relativeAltitudeM * (imperial ? 3.280839895 : 1), 0);
        return imperial ? elevationFeetLine(signed) : elevationMetersLine(signed);
    }

    /** RouteMapView.elevationFeetLine(). */
    static String elevationFeetLine(String signedFeet) {
        return T.mapsRouteElevationFt(signedFeet);
    }

    /** RouteMapView.elevationMetersLine(). */
    static String elevationMetersLine(String signedMeters) {
        return T.mapsRouteElevationM(signedMeters);
    }

    /** The head's lines against the base copies, at signs, roundings and edges. */
    private static void english() {
        Double[] values = {null, 0.0, -0.0, .004, -.004, .005, -.005, .04, -.04, .05, -.05, .25, -.25, .5, -.5,
                1.0, -1.0, 2.46, -2.46, 2.5, -2.5, 12.34, -15.44, 99.95, -99.95, 1234.5, -1500.0, 1e7,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Double v : values) {
            eq(RideMapPopupEnglishTest.grade(v), grade(v), "grade " + v);
            eq(RideMapPopupEnglishTest.accel(v), accel(v), "accel " + v);
            if (v == null) continue;
            for (boolean imperial : new boolean[] {false, true})
                eq(RideMapPopupEnglishTest.elevation(v, imperial), elevation(v, imperial), "elevation " + v + (imperial ? " ft" : " m"));
        }
        java.util.Random random = new java.util.Random(383);
        for (int i = 0; i < 2000; i++) {
            double v = (random.nextDouble() - .5) * Math.pow(10, random.nextInt(7) - 2);
            eq(RideMapPopupEnglishTest.grade(v), grade(v), "grade " + v);
            eq(RideMapPopupEnglishTest.accel(v), accel(v), "accel " + v);
            eq(RideMapPopupEnglishTest.elevation(v, false), elevation(v, false), "elevation m " + v);
            eq(RideMapPopupEnglishTest.elevation(v, true), elevation(v, true), "elevation ft " + v);
        }
        for (String signed : new String[] {"+2.5", "-0.0", "+0", "-12", "+1234.5"}) {
            eq(RideMapPopupEnglishTest.gradeLine(signed), gradeLine(signed), "grade line " + signed);
            eq(RideMapPopupEnglishTest.accelLine(signed), accelLine(signed), "accel line " + signed);
            eq(RideMapPopupEnglishTest.elevationFeetLine(signed), elevationFeetLine(signed), "feet line " + signed);
            eq(RideMapPopupEnglishTest.elevationMetersLine(signed), elevationMetersLine(signed), "metres line " + signed);
        }
        eq("≈+2.5% grade", grade(2.46), "grade pin");
        eq("+0.25 m/s² GPS accel", accel(.25), "accel pin");
        eq("Elevation +14 ft", elevation(4.2, true), "feet pin");
        eq("Elevation -1 m", elevation(-1.4, false), "metres pin");
    }

    /** The texts come from the catalog, not from English left in the code. */
    private static void keyEcho() {
        T.install(KEYS);
        try {
            eq("‹maps_route_grade:+2.5›", gradeLine("+2.5"), "grade key");
            eq("‹maps_route_accel:+0.25›", accelLine("+0.25"), "accel key");
            eq("‹maps_route_elevation_ft:+12›", elevationFeetLine("+12"), "feet key");
            eq("‹maps_route_elevation_m:+4›", elevationMetersLine("+4"), "metres key");
        } finally {
            T.install(null);
        }
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale locale : new Locale[] {Locale.US, Locale.GERMANY, Locale.forLanguageTag("pt-PT"),
                    Locale.forLanguageTag("ar-EG")}) {
                Locale.setDefault(locale);
                english();
            }
        } finally {
            Locale.setDefault(saved);
        }
        keyEcho();
        System.out.println("RideMapPopupTextTest: " + checks + " checks passed (signed popup lines from the catalog)");
    }
}
