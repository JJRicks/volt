package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for the ride map popup batch: the English of the route-point popup's signed
 * lines (grade, GPS acceleration, elevation) as RouteMapView builds them at the merge base (the prep
 * commit c5402796, which formats each signed number with Fmt.signedDecimal and passes it through a
 * plain %s), pinned before the batch moves the four format strings into the catalog.
 *
 * RouteMapView is an Android view, so each method below is the base method copied token for token;
 * the comment names it. The batch's own suite (RideMapPopupTextTest) renders the catalog calls that
 * replace the format strings and compares them with these copies, so this file, frozen by G5, is the
 * reference. The pins in main() spell the English out by hand and run under several default locales:
 * the base text never depends on them.
 */
public final class RideMapPopupEnglishTest {
    private static int checks;

    private RideMapPopupEnglishTest() {}

    /** RouteMapView.grade(). */
    static String grade(Double percent) {
        return percent == null ? null : gradeLine(Fmt.signedDecimal(Locale.US, percent, 1));
    }

    /** RouteMapView.gradeLine(). */
    static String gradeLine(String signedPercent) {
        return String.format(Locale.US, "≈%s%% grade", signedPercent);
    }

    /** RouteMapView.accel(). */
    static String accel(Double mps2) {
        return mps2 == null ? null : accelLine(Fmt.signedDecimal(Locale.US, mps2, 2));
    }

    /** RouteMapView.accelLine(). */
    static String accelLine(String signedMps2) {
        return String.format(Locale.US, "%s m/s² GPS accel", signedMps2);
    }

    /** RouteMapView.elevation(). */
    static String elevation(double relativeAltitudeM, boolean imperial) {
        String signed = Fmt.signedDecimal(Locale.US, relativeAltitudeM * (imperial ? 3.280839895 : 1), 0);
        return imperial ? elevationFeetLine(signed) : elevationMetersLine(signed);
    }

    /** RouteMapView.elevationFeetLine(). */
    static String elevationFeetLine(String signedFeet) {
        return String.format(Locale.US, "Elevation %s ft", signedFeet);
    }

    /** RouteMapView.elevationMetersLine(). */
    static String elevationMetersLine(String signedMeters) {
        return String.format(Locale.US, "Elevation %s m", signedMeters);
    }

    private static void eq(String expected, String actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void pins() {
        eq(null, grade(null), "no grade");
        eq("≈+2.5% grade", grade(2.46), "grade");
        eq("≈-2.5% grade", grade(-2.46), "negative grade");
        eq("≈+0.0% grade", grade(0.0), "zero grade");
        eq("≈-0.0% grade", grade(-0.01), "small negative grade keeps its sign");
        eq("≈+12.3% grade", grade(12.34), "steep grade");
        eq("≈+1234.5% grade", grade(1234.5), "no grouping");
        eq(null, accel(null), "no accel");
        eq("+0.25 m/s² GPS accel", accel(.25), "accel");
        eq("-1.00 m/s² GPS accel", accel(-1.0), "negative accel");
        eq("+0.00 m/s² GPS accel", accel(0.0), "zero accel");
        eq("Elevation +4 m", elevation(4.2, false), "elevation m");
        eq("Elevation -1 m", elevation(-1.4, false), "negative elevation m");
        eq("Elevation +0 m", elevation(0.0, false), "zero elevation m");
        eq("Elevation +14 ft", elevation(4.2, true), "elevation ft");
        eq("Elevation -5 ft", elevation(-1.4, true), "negative elevation ft");
        eq("Elevation +1500 m", elevation(1500.0, false), "no grouping m");
        eq("≈+2.5% grade", gradeLine("+2.5"), "grade line");
        eq("+0.25 m/s² GPS accel", accelLine("+0.25"), "accel line");
        eq("Elevation +12 ft", elevationFeetLine("+12"), "feet line");
        eq("Elevation +4 m", elevationMetersLine("+4"), "metres line");
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale locale : new Locale[] {Locale.US, Locale.GERMANY, Locale.forLanguageTag("pt-PT"),
                    Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva")}) {
                Locale.setDefault(locale);
                pins();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("RideMapPopupEnglishTest: " + checks + " checks passed");
    }
}
