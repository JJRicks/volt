package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B14's own suite: the catalog text that replaced the ride, map and chart English renders the base
 * English that RideMapsEnglishTest (G5) pinned, for every condition, and comes from the catalog.
 *
 * PhoneRideStore runs for real: Track.statusText() equals Track.status in English for built tracks
 * and for every combination of counts. RouteMapView, RideChartView and MapTiles need Android, so each
 * method here is the head expression copied token for token (the comment names the method), and is
 * compared with the base copy in RideMapsEnglishTest. MapTilesValidation (android-tests) checks the
 * MapTiles and MapSource code itself on the emulator.
 */
public final class RideMapsTextTest {
    private static int checks;

    private RideMapsTextTest() {}

    /** Every id answered with its key in marks, so a moved text is told from English. */
    private static final T.Source KEYS = new T.Source() {
        public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + (T.isFormatted(id) ? specs(EnglishText.INSTANCE.string(id)) : "") + "›"; }
        public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "#" + quantity + specs(EnglishText.INSTANCE.plural(id, quantity)) + "›" : null; }
        public String language() { return "en"; }
    };

    private static String specs(String english) {
        StringBuilder b = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%[0-9]+\\$[sdf]|%[0-9]+\\$\\.[0-9]f").matcher(english);
        while (m.find()) b.append(b.length() == 0 ? ":" : " ").append(m.group());
        return b.toString();
    }

    private static void eq(String expected, CharSequence actual, String what) {
        checks++;
        String shown = actual == null ? null : actual.toString();
        if (expected == null ? shown != null : !expected.equals(shown))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + shown + ">");
    }

    // ----- RouteMapView's head expressions -----

    /** RouteMapView.power(). */
    static String power(Double watts) {
        if (watts == null) return null;
        return Math.abs(watts) >= 1000
                ? T.fmtQuantity(Fmt.decimal(Locale.US, watts / 1000, 2), T.mapsUnitKilowatt())
                : T.fmtQuantity(Fmt.decimal(Locale.US, watts, 0), T.unitsWatt());
    }

    /** RouteMapView.speed(); units is the app_settings "units" preference. */
    static String speed(Double mps, String units) {
        if (mps == null) return null;
        double kmh = mps * 3.6, mph = kmh / RideStore.KM_PER_MILE;
        return units.equals("km")
                ? T.fmtQuantity(Fmt.decimal(Locale.US, kmh, 0), T.unitsKmh())
                : units.equals("dual")
                        ? T.mapsRouteSpeedDual(Fmt.decimal(Locale.US, mph, 0), T.unitsMph(), Fmt.decimal(Locale.US, kmh, 0), T.unitsKmh())
                        : T.fmtQuantity(Fmt.decimal(Locale.US, mph, 0), T.unitsMph());
    }

    /** RouteMapView.grade(). */
    static String grade(Double percent) {
        return percent == null ? null : gradeLine(Fmt.signedDecimal(Locale.US, percent, 1));
    }

    /** RouteMapView.gradeLine(). */
    static String gradeLine(String signedPercent) {
        return "≈" + signedPercent + "% grade";
    }

    /** RouteMapView.accel(). */
    static String accel(Double mps2) {
        return mps2 == null ? null : accelLine(Fmt.signedDecimal(Locale.US, mps2, 2));
    }

    /** RouteMapView.accelLine(). */
    static String accelLine(String signedMps2) {
        return signedMps2 + " m/s² GPS accel";
    }

    /** RouteMapView.elevation(). */
    static String elevation(double relativeAltitudeM, boolean imperial) {
        String signed = Fmt.signedDecimal(Locale.US, relativeAltitudeM * (imperial ? 3.280839895 : 1), 0);
        return imperial ? elevationFeetLine(signed) : elevationMetersLine(signed);
    }

    /** RouteMapView.elevationFeetLine(). */
    static String elevationFeetLine(String signedFeet) {
        return "Elevation " + signedFeet + " ft";
    }

    /** RouteMapView.elevationMetersLine(). */
    static String elevationMetersLine(String signedMeters) {
        return "Elevation " + signedMeters + " m";
    }

    /** RouteMapView.drawTooltip()'s two lines; imperial is RouteMapView.imperial(). */
    static String tooltip(String metric, String units, boolean hasSource, boolean scooter, String chosenLabel,
            Double watts, Double gradePercent, Double speedMps, Double accelMps2, Double relativeAltitudeM,
            String scooterLabel) {
        boolean imperial = !"km".equals(units);
        String primary, power = power(watts), grade = grade(gradePercent),
                speed = speed(speedMps, units), accel = accel(accelMps2);
        List<String> rest = new ArrayList<String>();
        if ("speed".equals(metric)) {
            String name = scooter ? T.unitsRouteSourceScooter() : T.unitsRouteSourceGps();
            primary = !hasSource ? T.mapsRouteSpeedNotMeasured() : chosenLabel == null ? (scooter ? T.mapsRouteNoScooterSpeed() : T.mapsRouteNoGpsSpeed())
                    : T.mapsRouteSpeedSource(chosenLabel, name);
            if (power != null) rest.add(power);
            if (scooter && speed != null) rest.add(T.mapsRouteGpsSpeed(speed));
            else if (!scooter && scooterLabel != null) rest.add(T.mapsRouteScooterSpeed(scooterLabel));
            speed = null;
        } else if ("grade".equals(metric)) {
            primary = grade == null ? T.mapsRouteGradeNotMeasured() : grade;
            if (power != null) rest.add(power);
        } else if ("elevation".equals(metric)) {
            primary = relativeAltitudeM == null ? T.mapsRouteElevationNotMeasured() : elevation(relativeAltitudeM, imperial);
            if (power != null) rest.add(power);
        } else if ("accel".equals(metric)) {
            primary = accel == null ? T.mapsRouteAccelNotMeasured() : accel;
            if (power != null) rest.add(power);
        } else {
            primary = power == null ? T.mapsRoutePowerNotMeasured() : power;
            if (grade != null) rest.add(grade);
        }
        if (speed != null) rest.add(speed);
        return primary + "\n" + String.join(" · ", rest);
    }

    // ----- RideChartView's head expressions -----

    /** RideChartView.updateAccessibilityDescription(); each reading is {name, value, at} as in the base copy. */
    static String chartDescription(CharSequence chartTitle, boolean noSeries, String inspectedAt, String[][] readings) {
        StringBuilder description = new StringBuilder(chartTitle);
        if (noSeries) description.append(T.mapsChartDescNoReadings());
        else if (inspectedAt == null) description.append(T.mapsChartDescNoSamples());
        else {
            description.append(T.mapsChartDescAt()).append(inspectedAt);
            for (String[] selected : readings) {
                description.append(T.mapsChartDescReading(selected[0]));
                if (selected[1] == null) description.append(T.mapsChartDescNoReading());
                else {
                    description.append(selected[1]);
                    if (selected[2] != null) description.append(T.mapsChartDescValueAt()).append(selected[2]);
                }
            }
        }
        return description.toString();
    }

    /** RideChartView.axisNumber() and number(). */
    static String axisNumber(String unit, double value) {
        return UnitCode.MPH.matches(unit) || UnitCode.KMH.matches(unit) ? String.format(Fmt.locale(Locale.US), "%.1f", Math.abs(value) < .05 ? 0 : value) : number(value);
    }

    static String number(double value) { if (Math.abs(value) < .005) value = 0; return String.format(Fmt.locale(Locale.US), Math.abs(value) >= 100 ? "%.0f" : Math.abs(value) >= 10 ? "%.1f" : "%.2f", value); }

    /** RideChartView.sampleValue() for a series unit and its value. */
    static String sampleValue(String unit, Double value) {
        if (value == null || !Double.isFinite(value)) return "—";
        int places = UnitCode.MPH.matches(unit) || UnitCode.KMH.matches(unit) ? 1 : 2;
        double raw = Math.abs(value) < Math.pow(10, -places) / 2 ? 0 : value;
        return T.fmtQuantity(String.format(Fmt.locale(Locale.US), "%." + places + "f", raw), unitSymbol(unit));
    }

    /** RideChartView.unitSymbol(), the unit drawn above each axis. */
    static String unitSymbol(String unit) {
        for (UnitCode code : UnitCode.values()) if (code.matches(unit)) return code.symbol();
        return unit;
    }

    // ----- PhoneRideStore, for real -----

    private static String gps(long mono, double lat, double accuracy, double speed, Double altitude) {
        return PhoneRideStore.row("GPS", 1700000000000L + mono, mono, lat, -111.78, accuracy, speed, .1, altitude, 4.0);
    }

    /** Track.statusText()'s chain, for counts no journal is needed to reach. */
    static Msg statusText(int fixes, int rejected, int rejectedElevation) {
        if (fixes == 0) return M.mapsTrackNoFixes();
        Msg text = M.mapsTrackFixes(fixes, fixes);
        if (rejected > 0) text = M.mapsTrackFixesLowQuality(rejected, text, rejected);
        if (rejectedElevation > 0) text = M.mapsTrackFixesOutliers(rejectedElevation, text, rejectedElevation);
        return text;
    }

    private static void tracks() throws Exception {
        File dir = Files.createTempDirectory("b14-text").toFile();
        try {
            PhoneRideStore store = new PhoneRideStore(dir);
            List<PhoneRideStore.Track> built = new ArrayList<>();
            built.add(store.load("missing"));
            store.start("one", 1700000000000L, 1000);
            store.appendBatch("one", gps(1000, 43.82, 3, 5, 1500.0), "");
            built.add(store.load("one"));
            store.start("quality", 1700000000000L, 1000);
            StringBuilder b = new StringBuilder();
            b.append(gps(1000, 43.82, 2, 0, 1500.0)).append(gps(2000, 43.82005, 2, 0, 1550.0))
                    .append(gps(3000, 43.8201, 90, 5, 1600.0)).append(gps(4000, 43.8202, 2, 5, 1600.0))
                    .append(gps(20000, 43.83, 2, 5, 1600.0)).append(gps(21000, 45.0, 2, 5, 1600.0))
                    .append(gps(20000, 45.001, 2, 5, 1600.0));
            store.appendBatch("quality", b.toString(), "");
            built.add(store.load("quality"));
            store.start("spike", 1700000000000L, 1000);
            b.setLength(0);
            for (int i = 0; i <= 30; i++) {
                double altitude = i >= 5 && i <= 25 ? 1500 + 76.2 * (1 - Math.abs(i - 15) / 10.0) : 1500;
                b.append(gps(1000 + i * 1000, 43.82 + i * 5 / 111194.9266, 3, 5, altitude));
            }
            store.appendBatch("spike", b.toString(), "");
            built.add(store.load("spike"));
            for (PhoneRideStore.Track track : built) {
                String shown = singular(track.status, track.locationCount, track.rejectedLocationCount, track.rejectedElevationCount);
                eq(shown, track.statusText().english(), "statusText English, " + track.id);
                eq(shown, track.statusText().render(), "statusText rendered, " + track.id);
                eq(singular(RideMapsEnglishTest.trackStatus(track.locationCount, track.rejectedLocationCount, track.rejectedElevationCount),
                        track.locationCount, track.rejectedLocationCount, track.rejectedElevationCount),
                        track.statusText(), "statusText against the base formula, " + track.id);
                eq(statusText(track.locationCount, track.rejectedLocationCount, track.rejectedElevationCount).english(),
                        track.statusText().english(), "the suite's copy of statusText, " + track.id);
            }
            T.install(KEYS);
            try {
                int outliers = built.get(2).rejectedElevationCount;
                eq("‹maps_track_fixes_outliers#" + outliers + ":‹maps_track_fixes_low_quality#3:‹maps_track_fixes#4:4› 3› " + outliers + "›",
                        built.get(2).statusText().render(), "statusText from the catalog");
                eq("‹maps_track_no_fixes›", built.get(0).statusText().render(), "no fixes from the catalog");
                eq("No usable GPS fixes recorded", built.get(0).status, "status stays English");
            } finally {
                T.install(null);
            }
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    // ----- English parity with the base copies -----

    /** The base's track status with the English "one" forms the catalog now has (l10n/english-changes.tsv). */
    private static String singular(String base, int fixes, int rejected, int outliers) {
        String s = fixes == 1 ? base.replaceFirst("^1 GPS fixes", "1 GPS fix") : base;
        if (rejected == 1) s = s.replace(" · 1 low-quality fixes excluded", " · 1 low-quality fix excluded");
        if (outliers == 1) s = s.replace(" · 1 elevation outliers excluded", " · 1 elevation outlier excluded");
        return s;
    }

    private static void parity() {
        for (int fixes : new int[] {0, 1, 2, 31, 1200})
            for (int rejected : new int[] {0, 1, 3})
                for (int outliers : new int[] {0, 1, 12})
                    eq(singular(RideMapsEnglishTest.trackStatus(fixes, rejected, outliers), fixes, rejected, outliers), statusText(fixes, rejected, outliers).render(),
                            "track status " + fixes + "/" + rejected + "/" + outliers);
        Double[] watts = {null, 0.0, 999.0, 999.6, 1000.0, 1500.0, -1200.0, 12345.0};
        for (Double w : watts) eq(RideMapsEnglishTest.power(w), power(w), "power " + w);
        Double[] speeds = {null, 0.0, .1388, .1389, -.1389, 10.0, 6.9, 12.42, 44.704, -3.0, 1e6, Double.NaN};
        for (Double mps : speeds)
            for (String units : new String[] {"km", "mi", "dual", "", "MI"}) {
                eq(RideMapsEnglishTest.speed(mps, units), speed(mps, units), "speed " + mps + " " + units);
                if (mps != null) eq(RouteSpeedScale.label(mps * 3.6, units), speed(mps, units), "speed as the primary line " + mps + " " + units);
            }
        // The signed lines against the base's String.format, at signs, roundings and edges.
        Double[] signedValues = {null, 0.0, -0.0, .04, -.04, .05, -.05, .25, -.25, .005, -.005, 2.5, -2.5, 12.34, -15.44,
                99.95, 1234.5, -1500.0, 1e7, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Double v : signedValues) {
            eq(RideMapsEnglishTest.grade(v), grade(v), "grade " + v);
            eq(RideMapsEnglishTest.accel(v), accel(v), "accel " + v);
            if (v == null) continue;
            for (boolean imperial : new boolean[] {false, true})
                eq(String.format(Locale.US, imperial ? "Elevation %+.0f ft" : "Elevation %+.0f m", v * (imperial ? 3.280839895 : 1)),
                        elevation(v, imperial), "elevation " + v + (imperial ? " ft" : " m"));
        }
        String[] metrics = {"speed", "grade", "elevation", "accel", "power"};
        String[] unitsList = {"km", "mi", "dual"};
        Double[] optional = {null, 2.5};
        for (String metric : metrics)
            for (String units : unitsList)
                for (boolean hasSource : new boolean[] {false, true})
                    for (boolean scooter : new boolean[] {false, true})
                        for (String chosen : new String[] {null, "25 mph"})
                            for (Double w : new Double[] {null, 999.0, 1500.0})
                                for (Double v : optional)
                                    for (String scooterLabel : new String[] {null, "30 km/h"})
                                        eq(RideMapsEnglishTest.tooltip(metric, units, hasSource, scooter, chosen, w, v, v == null ? null : 10.0, v, v, scooterLabel),
                                                tooltip(metric, units, hasSource, scooter, chosen, w, v, v == null ? null : 10.0, v, v, scooterLabel),
                                                "tooltip " + metric + " " + units + " " + hasSource + " " + scooter + " " + chosen + " " + w + " " + v + " " + scooterLabel);
        String[][] readings = {{"Power", "1500.00 W", null}, {"Voltage", null, null}, {"GPS speed", "20.0 mph", "1:04.000"}};
        for (String title : new String[] {"Ride chart", "Recording chart", "Charge recording"}) {
            eq(RideMapsEnglishTest.chartDescription(title, true, null, null), chartDescription(title, true, null, null), "no readings");
            eq(RideMapsEnglishTest.chartDescription(title, false, null, null), chartDescription(title, false, null, null), "no samples");
            eq(RideMapsEnglishTest.chartDescription(title, false, "1:05.250", readings), chartDescription(title, false, "1:05.250", readings), "readings");
        }
        eq("Ride chart", M.mapsChartRide(), "default title");
        eq("Fit whole ride", M.mapsChartFitRide(), "default fit label");
        eq("Recording chart", M.mapsChartRecording(), "caller's empty title");
        eq("Fit whole recording", M.mapsChartFitRecording(), "caller's missing fit label");
        eq("Ride chart. No readings selected", chartDescription(M.mapsChartRide(), true, null, null), "deferred title in the description");
        eq(RideMapsEnglishTest.cursor("0:01.000"), T.mapsChartCursorAt("0:01.000"), "cursor");
        for (String unit : new String[] {"mph", "km/h", "W", "V", "%", "ft", "m/s²", "Wh/mi"})
            for (double value : new double[] {0, .004, .04, -.04, 1.234, 12.34, 15.44, 150.4, -1500, 1500})
                eq(RideMapsEnglishTest.axisNumber(unit, value), axisNumber(unit, value), "axis " + unit + " " + value);
        for (String unit : new String[] {"mph", "km/h", "W", "%", "V", "A", "Wh", "Ah", "°C", "°F", "km", "mi", "min", "Time", "ft", "m", "m/s²", "Wh/mi", "Wh/km", "MPH", ""})
            eq(unit, unitSymbol(unit), "axis unit " + unit);
        for (String unit : new String[] {"mph", "km/h", "W", "%", "V", "ft", "Wh/mi", "°C"})
            for (Double value : new Double[] {null, Double.NaN, 0.0, .004, .04, 12.34, 1500.0, -3.333})
                eq(RideMapsEnglishTest.sampleValue(unit, value), sampleValue(unit, value), "sample " + unit + " " + value);
        for (int code : new int[] {0, 404, 429, 503, 999})
            eq(RideMapsEnglishTest.httpStatus(code), M.mapsTilesHttp(code), "HTTP " + code);
        eq("Map provider blocked access", M.mapsTilesBlocked(), "blocked");
        eq("Map provider rate limit · try later", M.mapsTilesRateLimited(), "rate limit");
        eq("Map provider returned an invalid response", M.mapsTilesInvalidResponse(), "invalid");
        eq("Map tiles unavailable · check connection", M.mapsTilesOffline(), "offline");
        eq("Map tiles unavailable", M.mapsTilesUnavailable(), "unavailable");
        eq("Map tiles temporarily unavailable", M.mapsTilesPaused(), "the restored default");
        eq("USGS Topo · United States", T.mapsSourceUsgs(), "USGS name");
        eq("USGS Topo", T.mapsSourceUsgsShort(), "USGS short name, the base split at \" · \"");
        eq("OpenStreetMap", T.mapsSourceOsm(), "OSM name");
        eq("Custom map", T.mapsSourceCustom(), "custom name");
        eq("Map provider", T.mapsSourceProviderFallback(), "credit stand-in");
        eq("S", T.mapsRouteMarkerStart(), "start marker");
        eq("E", T.mapsRouteMarkerEnd(), "end marker");
        checks++;
        if (T.mapsRouteMarkerStart().codePointCount(0, T.mapsRouteMarkerStart().length()) != 1
                || T.mapsRouteMarkerEnd().codePointCount(0, T.mapsRouteMarkerEnd().length()) != 1)
            throw new AssertionError("markers are one glyph");
    }

    /** The texts come from the catalog, not from English left in the code. */
    private static void keyEcho() {
        T.install(KEYS);
        try {
            eq("‹fmt_quantity:1.50 ‹maps_unit_kilowatt››", power(1500.0), "power template");
            eq("‹maps_route_power_not_measured›\n", tooltip("power", "km", true, true, null, null, null, null, null, null, null), "power none");
            eq("‹maps_route_speed_source:25 mph ‹units_route_source_scooter››\n", tooltip("speed", "km", true, true, "25 mph", null, null, null, null, null, null), "speed source");
            eq("‹maps_route_speed_not_measured›\n‹maps_route_scooter_speed:30 km/h›",
                    tooltip("speed", "km", false, false, null, null, null, null, null, null, "30 km/h"), "speed none, scooter context");
            eq("Title‹maps_chart_desc_at›1:05.250‹maps_chart_desc_reading:Power›‹maps_chart_desc_no_reading›",
                    chartDescription("Title", false, "1:05.250", new String[][] {{"Power", null, null}}), "chart description");
            eq("‹maps_chart_ride›", M.mapsChartRide(), "deferred title");
            eq("‹maps_tiles_http:503›", M.mapsTilesHttp(503), "HTTP template");
            eq("‹fmt_quantity:36 ‹units_kmh››", speed(10.0, "km"), "GPS speed, km");
            eq("‹fmt_quantity:22 ‹units_mph››", speed(10.0, "mi"), "GPS speed, mi");
            eq("‹maps_route_speed_dual:22 ‹units_mph› 36 ‹units_kmh››", speed(10.0, "dual"), "GPS speed, both");
            eq("‹maps_route_no_scooter_speed›\n‹maps_route_gps_speed:‹fmt_quantity:22 ‹units_mph›››",
                    tooltip("speed", "mi", true, true, null, null, null, 10.0, null, null, null), "GPS speed line");
            eq("‹units_watt›", unitSymbol("W"), "axis unit symbol");
            eq("Wh/mi", unitSymbol("Wh/mi"), "a unit with no symbol key stays as it is");
        } finally {
            T.install(null);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[] {Locale.US, Locale.GERMANY, new Locale("ar", "EG"), new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                parity();
            }
        } finally {
            Locale.setDefault(saved);
        }
        keyEcho();
        tracks();
        System.out.println("RideMapsTextTest: " + checks + " checks passed (track status, route tooltip, chart text and numbers, tile status against the base English; catalog sources)");
    }
}
