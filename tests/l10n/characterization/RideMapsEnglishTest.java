package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * G5 characterization for B14 (ride data, maps and the ride chart): the English these files build
 * at the merge base (f529f43), pinned before the batch.
 *
 * PhoneRideStore is plain Java, so its track status runs for real. RouteMapView, RideChartView and
 * MapTiles are Android views or need a Context, so each method below is the base expression copied
 * token for token, with the state it reads passed in as parameters; the comment names the base line.
 * B14's own suite (RideMapsTextTest) renders the catalog calls that replace each one and compares
 * them with these copies, so this file, frozen by G5, is the reference. The pins in main() spell the
 * English out by hand and run under several default locales: the base text never depends on them.
 */
public final class RideMapsEnglishTest {
    private static int checks;

    private RideMapsEnglishTest() {}

    /** RouteMapView.java:310-314, the scale chip. */
    static String scale(boolean feet, double meters) {
        return String.format(Locale.US, feet ? "%.0f ft" : "%.0f m", feet ? meters * 3.280839895 : meters);
    }

    /** RouteMapView.java:450-455, power(). */
    static String power(Double watts) {
        if (watts == null) return null;
        return Math.abs(watts) >= 1000
                ? String.format(Locale.US, "%.2f kW", watts / 1000)
                : String.format(Locale.US, "%.0f W", watts);
    }

    /** RouteMapView.java:457-459, grade(). */
    static String grade(Double percent) {
        return percent == null ? null : String.format(Locale.US, "≈%+.1f%% grade", percent);
    }

    /** RouteMapView.java:461-463, accel(). */
    static String accel(Double mps2) {
        return mps2 == null ? null : String.format(Locale.US, "%+.2f m/s² GPS accel", mps2);
    }

    /** RouteMapView.java:465-474, speed(): units is the app_settings "units" preference. */
    static String speed(Double mps, String units) {
        if (mps == null) return null;
        double kmh = mps * 3.6, mph = kmh / RideStore.KM_PER_MILE;
        return units.equals("km")
                ? String.format(Locale.US, "%.0f km/h", kmh)
                : units.equals("dual")
                        ? String.format(Locale.US, "%.0f mph / %.0f km/h", mph, kmh)
                        : String.format(Locale.US, "%.0f mph", mph);
    }

    /** RouteMapView.java:366-393, drawTooltip()'s two lines, joined by a newline. chosenLabel is
     * RouteSpeedScale.label(chosen, units()) and scooterLabel RouteSpeedScale.label(p.scooterSpeedKmh, units()). */
    static String tooltip(String metric, String units, boolean hasSource, boolean scooter, String chosenLabel,
            Double watts, Double gradePercent, Double speedMps, Double accelMps2, Double relativeAltitudeM,
            String scooterLabel) {
        boolean imperial = !"km".equals(units);
        String primary, power = power(watts), grade = grade(gradePercent),
                speed = speed(speedMps, units), accel = accel(accelMps2);
        List<String> rest = new ArrayList<String>();
        if ("speed".equals(metric)) {
            String name = scooter ? "scooter" : "GPS";
            primary = !hasSource ? "Speed not measured" : chosenLabel == null ? (scooter ? "No scooter speed" : "No GPS speed")
                    : chosenLabel + " · " + name;
            if (power != null) rest.add(power);
            if (scooter && speed != null) rest.add("GPS " + speed);
            else if (!scooter && scooterLabel != null) rest.add("Scooter " + scooterLabel);
            speed = null;
        } else if ("grade".equals(metric)) {
            primary = grade == null ? "Grade not measured" : grade;
            if (power != null) rest.add(power);
        } else if ("elevation".equals(metric)) {
            primary = relativeAltitudeM == null ? "Elevation not measured" : String.format(Locale.US, imperial ? "Elevation %+.0f ft" : "Elevation %+.0f m", relativeAltitudeM * (imperial ? 3.280839895 : 1));
            if (power != null) rest.add(power);
        } else if ("accel".equals(metric)) {
            primary = accel == null ? "GPS accel not measured" : accel;
            if (power != null) rest.add(power);
        } else {
            primary = power == null ? "Power not measured" : power;
            if (grade != null) rest.add(grade);
        }
        if (speed != null) rest.add(speed);
        return primary + "\n" + String.join(" · ", rest);
    }

    /** RideChartView.java:186-204, updateAccessibilityDescription(): each reading is {label, value,
     * at}, where value is the formatted sample (null for no reading) and at the sampleTime of a
     * sample away from the cursor (null when the sample is at the cursor). */
    static String chartDescription(String chartTitle, boolean noSeries, String inspectedAt, String[][] readings) {
        StringBuilder description = new StringBuilder(chartTitle);
        if (noSeries) description.append(". No readings selected");
        else if (inspectedAt == null) description.append(". No recorded samples");
        else {
            description.append(". At ").append(inspectedAt);
            for (String[] selected : readings) {
                description.append(". ").append(selected[0]).append(": ");
                if (selected[1] == null) description.append("No reading");
                else {
                    description.append(selected[1]);
                    if (selected[2] != null) description.append(" at ").append(selected[2]);
                }
            }
        }
        return description.toString();
    }

    /** RideChartView.java:329, the cursor label above the plot. */
    static String cursor(String sampleTime) {
        return "At " + sampleTime;
    }

    /** RideChartView.java:376, axisNumber(), with number() at line 377. */
    static String axisNumber(String unit, double value) {
        return unit.equals("mph") || unit.equals("km/h") ? String.format(Locale.US, "%.1f", Math.abs(value) < .05 ? 0 : value) : number(value);
    }

    static String number(double value) { if (Math.abs(value) < .005) value = 0; return String.format(Locale.US, Math.abs(value) >= 100 ? "%.0f" : Math.abs(value) >= 10 ? "%.1f" : "%.2f", value); }

    /** RideChartView.java:385-390, sampleValue() for a series unit and its value. */
    static String sampleValue(String unit, Double value) {
        if (value == null || !Double.isFinite(value)) return "—";
        int places = unit.equals("mph") || unit.equals("km/h") ? 1 : 2;
        double raw = Math.abs(value) < Math.pow(10, -places) / 2 ? 0 : value;
        return String.format(Locale.US, "%." + places + "f", raw) + " " + unit;
    }

    /** MapTiles.java:116, the HTTP status pause. */
    static String httpStatus(int code) {
        return "Map tiles unavailable (HTTP " + code + ")";
    }

    /** PhoneRideStore.java:218-226, Track.status from the counts it is built from. */
    static String trackStatus(int fixes, int rejected, int rejectedElevation) {
        return fixes == 0
                ? "No usable GPS fixes recorded"
                : fixes
                        + " GPS fixes"
                        + (rejected > 0
                                ? " · " + rejected + " low-quality fixes excluded"
                                : "")
                        + (rejectedElevation > 0 ? " · " + rejectedElevation + " elevation outliers excluded" : "");
    }

    private static void eq(String expected, String actual, String what) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static String gps(long mono, double lat, double accuracy, double speed, Double altitude) {
        return PhoneRideStore.row("GPS", 1700000000000L + mono, mono, lat, -111.78, accuracy, speed, .1, altitude, 4.0);
    }

    /** Real tracks: the status PhoneRideStore stores is the formula above. */
    private static void tracks() throws Exception {
        File dir = Files.createTempDirectory("b14-tracks").toFile();
        try {
            PhoneRideStore store = new PhoneRideStore(dir);
            PhoneRideStore.Track missing = store.load("missing");
            eq("No usable GPS fixes recorded", missing.status, "no GPS");
            store.start("one", 1700000000000L, 1000, "pocket");
            store.appendBatch("one", gps(1000, 43.82, 3, 5, 1500.0), "");
            PhoneRideStore.Track one = store.load("one");
            eq("1 GPS fixes", one.status, "one fix keeps the base plural bug");
            store.start("quality", 1700000000000L, 1000, "mounted");
            StringBuilder b = new StringBuilder();
            b.append(gps(1000, 43.82, 2, 0, 1500.0));
            b.append(gps(2000, 43.82005, 2, 0, 1550.0));
            b.append(gps(3000, 43.8201, 90, 5, 1600.0));
            b.append(gps(4000, 43.8202, 2, 5, 1600.0));
            b.append(gps(20000, 43.83, 2, 5, 1600.0));
            b.append(gps(21000, 45.0, 2, 5, 1600.0));
            b.append(gps(20000, 45.001, 2, 5, 1600.0));
            store.appendBatch("quality", b.toString(), "");
            PhoneRideStore.Track quality = store.load("quality");
            eq(trackStatus(quality.locationCount, quality.rejectedLocationCount, quality.rejectedElevationCount),
                    quality.status, "quality formula");
            eq("4 GPS fixes · 3 low-quality fixes excluded · 1 elevation outliers excluded", quality.status, "rejected fixes and an outlier");
            store.start("spike", 1700000000000L, 1000, "pocket");
            b.setLength(0);
            for (int i = 0; i <= 30; i++) {
                double altitude = i >= 5 && i <= 25 ? 1500 + 76.2 * (1 - Math.abs(i - 15) / 10.0) : 1500;
                b.append(gps(1000 + i * 1000, 43.82 + i * 5 / 111194.9266, 3, 5, altitude));
            }
            store.appendBatch("spike", b.toString(), "");
            PhoneRideStore.Track spike = store.load("spike");
            eq(trackStatus(spike.locationCount, spike.rejectedLocationCount, spike.rejectedElevationCount),
                    spike.status, "spike formula");
            eq("31 GPS fixes · " + spike.rejectedElevationCount + " elevation outliers excluded", spike.status, "outliers");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) deleteTree(f);
            dir.delete();
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    private static void pins() {
        eq("No usable GPS fixes recorded", trackStatus(0, 2, 1), "status none");
        eq("1 GPS fixes · 1 low-quality fixes excluded · 1 elevation outliers excluded", trackStatus(1, 1, 1), "status ones");
        eq("1200 GPS fixes · 2 elevation outliers excluded", trackStatus(1200, 0, 2), "status no grouping");
        eq("80 ft", scale(true, 24.4), "scale ft");
        eq("1235 m", scale(false, 1234.6), "scale m");
        eq("1.50 kW", power(1500.0), "kW");
        eq("-1.20 kW", power(-1200.0), "negative kW");
        eq("999 W", power(999.0), "W");
        eq("≈+2.5% grade", grade(2.46), "grade");
        eq("≈-0.0% grade", grade(-0.01), "negative zero grade");
        eq("+0.25 m/s² GPS accel", accel(.25), "accel");
        eq("-1.00 m/s² GPS accel", accel(-1.0), "negative accel");
        eq("36 km/h", speed(10.0, "km"), "speed km");
        eq("22 mph / 36 km/h", speed(10.0, "dual"), "speed dual");
        eq("22 mph", speed(10.0, "mi"), "speed mi");
        eq("Speed not measured\n1.50 kW · GPS 22 mph", tooltip("speed", "mi", false, true, null, 1500.0, null, 10.0, null, null, null), "speed none");
        eq("No scooter speed\nGPS 22 mph", tooltip("speed", "mi", true, true, null, null, null, 10.0, null, null, null), "no scooter");
        eq("No GPS speed\nScooter 30 km/h", tooltip("speed", "km", true, false, null, null, null, 10.0, null, null, "30 km/h"), "no GPS");
        eq("25 mph · scooter\n999 W · GPS 22 mph", tooltip("speed", "mi", true, true, "25 mph", 999.0, null, 10.0, null, null, null), "scooter speed");
        eq("36 km/h · GPS\n", tooltip("speed", "km", true, false, "36 km/h", null, null, 10.0, null, null, null), "GPS speed");
        eq("Grade not measured\n22 mph", tooltip("grade", "mi", true, true, null, null, null, 10.0, null, null, null), "grade none");
        eq("≈+3.0% grade\n1.50 kW · 36 km/h", tooltip("grade", "km", true, true, null, 1500.0, 3.0, 10.0, null, null, null), "grade");
        eq("Elevation not measured\n", tooltip("elevation", "mi", true, true, null, null, null, null, null, null, null), "elevation none");
        eq("Elevation +33 ft\n", tooltip("elevation", "mi", true, true, null, null, null, null, null, 10.0, null), "elevation ft");
        eq("Elevation -10 m\n", tooltip("elevation", "km", true, true, null, null, null, null, null, -10.0, null), "elevation m");
        eq("GPS accel not measured\n", tooltip("accel", "mi", true, true, null, null, null, null, null, null, null), "accel none");
        eq("+0.50 m/s² GPS accel\n999 W", tooltip("accel", "mi", true, true, null, 999.0, null, null, .5, null, null), "accel");
        eq("Power not measured\n≈+1.0% grade · 22 mph / 36 km/h", tooltip("power", "dual", true, true, null, null, 1.0, 10.0, null, null, null), "power none");
        eq("1.50 kW\n", tooltip("power", "km", true, true, null, 1500.0, null, null, null, null, null), "power");
        eq("Ride chart. No readings selected", chartDescription("Ride chart", true, null, null), "no readings");
        eq("Recording chart. No recorded samples", chartDescription("Recording chart", false, null, null), "no samples");
        eq("Ride chart. At 1:05.250. Power: 1500.00 W. Voltage: No reading. GPS speed: 20.0 mph at 1:04.000",
                chartDescription("Ride chart", false, "1:05.250",
                        new String[][] {{"Power", "1500.00 W", null}, {"Voltage", null, null}, {"GPS speed", "20.0 mph", "1:04.000"}}),
                "readings");
        eq("At 0:01.000", cursor("0:01.000"), "cursor");
        eq("12.3", axisNumber("mph", 12.34), "axis mph");
        eq("0.0", axisNumber("km/h", .04), "axis km/h zero");
        eq("150", axisNumber("W", 150.4), "axis hundreds");
        eq("15.4", axisNumber("V", 15.44), "axis tens");
        eq("1.23", axisNumber("%", 1.234), "axis ones");
        eq("0.00", axisNumber("W", .004), "axis zero");
        eq("—", sampleValue("W", null), "no sample");
        eq("12.3 mph", sampleValue("mph", 12.34), "sample mph");
        eq("0.0 km/h", sampleValue("km/h", .04), "sample km/h zero");
        eq("1500.00 W", sampleValue("W", 1500.0), "sample W, no grouping");
        eq("Map tiles unavailable (HTTP 503)", httpStatus(503), "HTTP status");
    }

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[] {Locale.US, Locale.GERMANY, new Locale("ar", "EG"), new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                pins();
            }
        } finally {
            Locale.setDefault(saved);
        }
        tracks();
        System.out.println("RideMapsEnglishTest: " + checks + " checks passed");
    }
}
