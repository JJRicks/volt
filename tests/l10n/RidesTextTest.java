package com.jjricks.volt;

import java.util.EnumSet;
import java.util.Locale;

/**
 * B15's own suite: the catalog text that replaced RidesScreen's English renders the base English
 * that RidesEnglishTest (G5) pinned, for every condition, and comes from the catalog.
 *
 * RidesScreen needs Android, so each method here is the head expression copied token for token
 * (the comment names the method) and is compared with the base copy in RidesEnglishTest.
 * phoneStatusText() is RidesScreen.phoneStatusText() copied; it is checked against the base status
 * text for every recorder state, with the flags PhoneRideRecorder.statusFlags() reports for it.
 * PhoneRideRecorder needs Android, so StatusFlag below mirrors its enum, and statusFlagsMatch()
 * reads PhoneRideRecorder.java to keep the mirror's constants and the Gps texts the same.
 */
public final class RidesTextTest {
    private static int checks;

    private RidesTextTest() {}

    /** PhoneRideRecorder.StatusFlag (PhoneRideRecorder.java:33), mirrored; statusFlagsMatch() checks it. */
    enum StatusFlag {STORAGE_ERROR, SAVING, IDLE, GPS_OFF, GPS_STARTING, GPS_WAITING, GPS_LOCATION_OFF, GPS_UNAVAILABLE,
        GPS_PERMISSION_UNAVAILABLE, GPS_NO_PERMISSION, GPS_RECORDING, GPS_SIGNAL_LOST, GPS_SIGNAL_UNAVAILABLE, BAROMETER,
        MOTION_EXPERIMENTAL, MOTION_RAW, MOTION_UNAVAILABLE}

    /** The mirror holds PhoneRideRecorder's StatusFlag constants in order, and GPS_TEXTS its Gps texts. */
    private static void statusFlagsMatch() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(new java.io.File(L10nFiles.root(), "src/com/jjricks/volt/PhoneRideRecorder.java").toPath()), "UTF-8");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("enum StatusFlag \\{([^}]*)\\}").matcher(source);
        if (!m.find()) throw new AssertionError("PhoneRideRecorder.StatusFlag not found");
        StringBuilder mirror = new StringBuilder();
        for (StatusFlag flag : StatusFlag.values()) mirror.append(mirror.length() == 0 ? "" : ",").append(flag.name());
        eq(m.group(1).replaceAll("\\s+", ""), mirror, "StatusFlag constants");
        for (int g = 0; g < GPS_TEXTS.length; g++)
            if (!source.contains(GPS_FLAGS[g].name() + "(StatusFlag." + GPS_FLAGS[g].name() + ", \"" + GPS_TEXTS[g] + "\")"))
                throw new AssertionError("Gps text " + GPS_TEXTS[g]);
        checks++;
    }

    /** Every id answered with its key in marks, so a moved text is told from English. */
    private static final T.Source KEYS = new T.Source() {
        public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›"; }
        public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "#" + quantity + "›" : null; }
        public String language() { return "en"; }
    };

    private static void eq(String expected, CharSequence actual, String what) {
        checks++;
        String shown = actual == null ? null : actual.toString();
        if (expected == null ? shown != null : !expected.equals(shown))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + shown + ">");
    }

    // ----- RidesScreen's head expressions -----

    /** RidesScreen.percent(). */
    static String percent(double coverage) { return T.fmtPercent(Fmt.decimal(Locale.US, coverage * 100, 0)); }

    /** RidesScreen.distance(). */
    static String distance(Double km, String units) {
        if (km == null) return "Not measured";
        String metric = T.fmtQuantity(Fmt.decimal(Locale.US, km, 2), T.unitsKm()), miles = T.fmtQuantity(Fmt.decimal(Locale.US, km / RideStore.KM_PER_MILE, 2), T.unitsMi());
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }

    /** RidesScreen.speed(). */
    static String speed(Double kmh, String units) {
        if (kmh == null) return "Not measured";
        String metric = T.fmtQuantity(Fmt.decimal(Locale.US, kmh, 1), T.unitsKmh()), miles = T.fmtQuantity(Fmt.decimal(Locale.US, kmh / RideStore.KM_PER_MILE, 1), T.unitsMph());
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }

    /** RidesScreen.accel(). */
    static String accel(Double value) { return value == null ? "—" : T.fmtQuantity(Fmt.decimal(Locale.US, value, 2), T.ridesUnitAcceleration()); }

    /** RidesScreen.acceleration(). */
    static String acceleration(Double value) { return value == null ? "—" : T.ridesAccelerationWithG(Fmt.decimal(Locale.US, value, 2), T.ridesUnitAcceleration(), Fmt.decimal(Locale.US, value / 9.80665, 2), T.ridesUnitGravity()); }

    /** RidesScreen.updateSensorsRow()'s value. */
    static String sensorsOn(int on) { return on == 0 ? T.commonOff() : T.ridesSensorsOnCount(on); }

    /** RidesScreen.recordingNotes()'s title. */
    static String notesTitle(String label) { return (label.isEmpty() ? T.ridesNotesTitleUnnamed() : T.ridesNotesTitle(label)); }

    /** RidesScreen.refreshActive()'s title. */
    static String activeTitle(String error, String label) {
        String name = label.isEmpty() ? T.ridesDefaultName() : label;
        return (error == null ? T.ridesLiveTitle(name) : T.ridesLiveTitleAttention(name));
    }

    /** RidesScreen.refreshActive()'s elapsed description; duration() is unchanged. */
    static String elapsed(long elapsed) { return T.ridesElapsedDescription(RidesEnglishTest.duration(elapsed)); }

    /** RidesScreen.refreshActive()'s gap chip. */
    static String gapChip(long gapCount) { return gapCount == 1 ? T.ridesGapOne() : T.ridesGaps(gapCount, gapCount); }

    /** RidesScreen.refreshActive()'s coverage caption. */
    static String coverage(double electricalCoverage) { return T.ridesCoverage(percent(electricalCoverage)); }

    /** RidesScreen.refreshActive()'s coverage description. */
    static String coverageDescription(double electricalCoverage, long electricalCoverageMs, long elapsedMs) {
        return T.ridesCoverageDescription(percent(electricalCoverage), RidesEnglishTest.duration(electricalCoverageMs), RidesEnglishTest.duration(elapsedMs));
    }

    /** RidesScreen.updateHistoryCount(). */
    static String historyCount(int savedCount, String storageText) {
        String count = savedCount == 0 ? T.ridesHistoryNone() : T.ridesHistoryCount(savedCount, savedCount);
        return storageText.isEmpty() ? count : count + " · " + storageText;
    }

    /** RidesScreen.measureStorage()'s size text. */
    static String storage(long bytes) {
        return bytes < 1000 * 1000 ? T.fmtQuantity(Fmt.decimal(Locale.US, Math.max(1, bytes / 1000.0), 0), T.ridesUnitKilobyte()) : T.fmtQuantity(Fmt.decimal(Locale.US, bytes / 1e6, 1), T.ridesUnitMegabyte());
    }

    /** RidesScreen.historyRow()'s two descriptions. */
    static String historyRow(String spoken, boolean wide) { return wide ? T.ridesHistoryRowWide(spoken) : T.ridesHistoryRow(spoken); }

    /** RidesScreen.selectRide()'s announcement. */
    static String showing(CharSequence title) { return T.ridesShowing(title); }

    /** RidesScreen.updateComparisonActions()'s description. */
    static String compareRow(int savedCount) { return T.ridesCompareTwoSaved(savedCount); }

    /** RidesScreen.chooseComparison()'s count description. */
    static String chosen(int picked) { return T.ridesCompareChosen(picked); }

    /** RidesScreen.buildOverview()'s battery description. */
    static String battery(boolean batteryGain, double batteryDropPercent) {
        return (batteryGain ? T.ridesBatteryGainDescription(Math.round(Math.abs(batteryDropPercent)))
                : T.ridesBatteryDropDescription(Math.round(Math.abs(batteryDropPercent))));
    }

    /** RidesScreen.directionSplit()'s three texts. */
    static String[] direction(long confirmedMs, long unconfirmedMs) {
        return new String[] {T.ridesDirectionConfirmedFor(RidesEnglishTest.duration(confirmedMs)), T.ridesDirectionUnconfirmedFor(RidesEnglishTest.duration(unconfirmedMs)),
                T.ridesDirectionSplitDescription(RidesEnglishTest.duration(confirmedMs), RidesEnglishTest.duration(unconfirmedMs))};
    }

    /** RidesScreen.switchRow()'s experimental description. */
    static String experimental(String title) { return T.ridesSwitchExperimental(title); }

    /** RidesScreen.phoneRide()'s motion note. */
    static String placement(String placement, long motionSampleCount) {
        return ("mounted".equals(placement) ? T.ridesMotionNoteMounted(String.format(Locale.US, "%,d", motionSampleCount)) : T.ridesMotionNotePocket(String.format(Locale.US, "%,d", motionSampleCount)));
    }

    /** RidesScreen.confirmDelete()'s title. */
    static String deleteTitle(String label) { return (label.isEmpty() ? T.ridesDeleteTitleUnnamed() : T.ridesDeleteTitle(label)); }

    /** RidesScreen.showComparison()'s view descriptions. */
    static String viewRoute(String name) { return T.ridesViewRouteOf(name); }

    /** RidesScreen.compareLine()'s description. */
    static String compareLine(String title, String spokenFirst, String spokenSecond) { return T.ridesCompareLineDescription(title, spokenFirst, spokenSecond); }

    /** RidesScreen.compareLine()'s reason toast. */
    static String reason(String firstReason, String secondReason) {
        return firstReason != null && firstReason.equals(secondReason) ? firstReason
                : firstReason == null ? T.ridesCompareReasonSecond(secondReason) : secondReason == null ? T.ridesCompareReasonFirst(firstReason)
                : T.ridesCompareReasonBoth(firstReason, secondReason);
    }

    /** Base RidesScreen.java:1525-1528, the reason toast. */
    static String baseReason(String firstReason, String secondReason) {
        return firstReason != null && firstReason.equals(secondReason) ? firstReason
                : firstReason == null ? "Second: " + secondReason : secondReason == null ? "First: " + firstReason
                : "First: " + firstReason + " · Second: " + secondReason;
    }

    /** RidesScreen.efficiency()'s suffix. */
    static String efficiency(String value, boolean net) { return (!net ? T.ridesEfficiencyThroughput(value) : T.ridesEfficiencyNet(value)); }

    /** RidesScreen.mapLegend() and the elevation and speed legends. */
    static String[] legends(String watts, String low, String high, boolean scooter, String top) {
        return new String[] {T.ridesLegendPower(watts), T.ridesLegendElevation(low, high),
                (scooter ? T.ridesLegendSpeedScooter(top) : T.ridesLegendSpeedGps(top))};
    }

    /** RidesScreen.phoneStatusText(). */
    static String phoneStatusText(EnumSet<StatusFlag> flags) {
        if (flags.contains(StatusFlag.SAVING)) return T.ridesSavingSensors();
        if (flags.contains(StatusFlag.STORAGE_ERROR)) return T.ridesSensorStatusStorage();
        if (flags.contains(StatusFlag.IDLE)) return T.ridesSensorStatusIdle();
        String text = flags.contains(StatusFlag.GPS_STARTING) ? T.ridesSensorStatusGpsStarting()
                : flags.contains(StatusFlag.GPS_WAITING) ? T.ridesSensorStatusGpsWaiting()
                : flags.contains(StatusFlag.GPS_LOCATION_OFF) ? T.ridesSensorStatusLocationOff()
                : flags.contains(StatusFlag.GPS_UNAVAILABLE) ? T.ridesSensorStatusGpsUnavailable()
                : flags.contains(StatusFlag.GPS_PERMISSION_UNAVAILABLE) ? T.ridesSensorStatusPermissionUnavailable()
                : flags.contains(StatusFlag.GPS_NO_PERMISSION) ? T.ridesSensorStatusNoPermission()
                : flags.contains(StatusFlag.GPS_RECORDING) ? T.ridesSensorStatusGpsRecording()
                : flags.contains(StatusFlag.GPS_SIGNAL_LOST) ? T.ridesSensorStatusSignalLost()
                : flags.contains(StatusFlag.GPS_SIGNAL_UNAVAILABLE) ? T.ridesSensorStatusSignalUnavailable()
                : T.ridesSensorStatusGpsOff();
        if (flags.contains(StatusFlag.BAROMETER)) text = T.ridesSensorStatusBarometer(text);
        if (flags.contains(StatusFlag.MOTION_EXPERIMENTAL)) text = T.ridesSensorStatusMotionExperimental(text);
        else if (flags.contains(StatusFlag.MOTION_RAW)) text = T.ridesSensorStatusMotionRaw(text);
        else if (flags.contains(StatusFlag.MOTION_UNAVAILABLE)) text = T.ridesSensorStatusMotionUnavailable(text);
        return text;
    }

    /** RidesScreen.datePattern() and skeleton(), with Android's best-pattern lookup passed in. */
    static String datePattern(String legacy, java.util.function.BiFunction<Locale, String, String> best) {
        return Fmt.datePattern(legacy, skeleton(legacy), best);
    }
    static String skeleton(String pattern) {
        StringBuilder letters = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) if (Character.isLetter(pattern.charAt(i))) letters.append(pattern.charAt(i));
        return letters.toString();
    }

    /** The ride dates keep their legacy patterns (base RidesScreen.java:1506, 2094, 2095) and default locale in English. */
    private static void dates() {
        eq("MMMd", skeleton("MMM d"), "date skeleton");
        eq("EEEMMMd", skeleton("EEE, MMM d"), "day date skeleton");
        java.util.function.BiFunction<Locale, String, String> best = (locale, skeleton) -> { throw new AssertionError("English asked for a best pattern"); };
        eq("MMM d", datePattern("MMM d", best), "date pattern");
        eq("EEE, MMM d", datePattern("EEE, MMM d", best), "day date pattern");
        checks++;
        if (Fmt.locale(Locale.getDefault()) != Locale.getDefault()) throw new AssertionError("dates keep the default locale in English");
        long wall = 1_700_000_000_000L;
        eq(new java.text.SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(new java.util.Date(wall)),
                new java.text.SimpleDateFormat(datePattern("EEE, MMM d", best), Fmt.locale(Locale.getDefault())).format(new java.util.Date(wall)), "long date");
    }

    /** The PhoneRideRecorder.Gps texts and flags (PhoneRideRecorder.java:42-51), in order. */
    private static final String[] GPS_TEXTS = {"GPS off", "Starting phone sensors", "Waiting for GPS", "Location is off",
            "GPS unavailable", "GPS permission unavailable", "Location permission not granted", "GPS recording",
            "GPS signal lost", "GPS signal unavailable"};
    private static final StatusFlag[] GPS_FLAGS = {StatusFlag.GPS_OFF,
            StatusFlag.GPS_STARTING, StatusFlag.GPS_WAITING,
            StatusFlag.GPS_LOCATION_OFF, StatusFlag.GPS_UNAVAILABLE,
            StatusFlag.GPS_PERMISSION_UNAVAILABLE, StatusFlag.GPS_NO_PERMISSION,
            StatusFlag.GPS_RECORDING, StatusFlag.GPS_SIGNAL_LOST,
            StatusFlag.GPS_SIGNAL_UNAVAILABLE};

    /** Every recorder state: the head text from its flags equals the base status text. */
    private static void phoneStatus() {
        eq(RidesEnglishTest.phoneStatus(true, false, null, false, true, "GPS off", false, false, false, false),
                phoneStatusText(EnumSet.of(StatusFlag.SAVING)), "saving");
        eq(RidesEnglishTest.phoneStatus(false, true, null, false, true, "GPS off", false, false, false, false),
                phoneStatusText(EnumSet.of(StatusFlag.STORAGE_ERROR)), "no store");
        eq(RidesEnglishTest.phoneStatus(false, false, "Phone sensor storage unavailable", false, true, "GPS off", true, true, true, false),
                phoneStatusText(EnumSet.of(StatusFlag.STORAGE_ERROR)), "storage error");
        eq(RidesEnglishTest.phoneStatus(false, false, null, true, true, "GPS off", false, false, false, false),
                phoneStatusText(EnumSet.of(StatusFlag.SAVING)), "closing");
        eq(RidesEnglishTest.phoneStatus(false, false, null, false, false, "GPS off", false, false, false, false),
                phoneStatusText(EnumSet.of(StatusFlag.IDLE)), "idle");
        for (int g = 0; g < GPS_TEXTS.length; g++)
            for (int state = 0; state < 16; state++) {
                boolean pressure = (state & 1) != 0, motion = (state & 2) != 0, linear = (state & 4) != 0, raw = (state & 8) != 0;
                EnumSet<StatusFlag> flags = EnumSet.of(GPS_FLAGS[g]);
                if (pressure) flags.add(StatusFlag.BAROMETER);
                if (motion) flags.add(linear ? StatusFlag.MOTION_EXPERIMENTAL : raw ? StatusFlag.MOTION_RAW : StatusFlag.MOTION_UNAVAILABLE);
                eq(RidesEnglishTest.phoneStatus(false, false, null, false, true, GPS_TEXTS[g], pressure, motion, linear, raw),
                        phoneStatusText(flags), "status " + flags);
            }
    }

    private static final String[] UNITS = {"mi", "km", "dual"};
    private static final String[] NAMES = {"", "Hill", "Mode 2 (raw)"};

    private static void english() {
        double[] fractions = {0, .004, .005, .125, .5, .8, .995, 1};
        for (double f : fractions) {
            eq(RidesEnglishTest.percent(f), percent(f), "percent " + f);
            eq(RidesEnglishTest.coverage(f), coverage(f), "coverage " + f);
            eq(RidesEnglishTest.coverageDescription(f, 65_000, 3_700_000), coverageDescription(f, 65_000, 3_700_000), "coverage description");
        }
        double[] values = {0, 0.004, 0.005, 1.5, 2.0, 12.345, 99.995, 1234.5, -3.25};
        for (String units : UNITS) {
            eq(RidesEnglishTest.distance(null, units), distance(null, units), "distance null");
            eq(RidesEnglishTest.speed(null, units), speed(null, units), "speed null");
            for (double v : values) {
                eq(RidesEnglishTest.distance(v, units), distance(v, units), "distance " + v + " " + units);
                eq(RidesEnglishTest.speed(v, units), speed(v, units), "speed " + v + " " + units);
            }
        }
        eq(RidesEnglishTest.accel(null), accel(null), "accel null");
        eq(RidesEnglishTest.acceleration(null), acceleration(null), "acceleration null");
        for (double v : values) {
            eq(RidesEnglishTest.accel(v), accel(v), "accel " + v);
            eq(RidesEnglishTest.acceleration(v), acceleration(v), "acceleration " + v);
        }
        for (int on = 0; on <= 3; on++) eq(RidesEnglishTest.sensorsOn(on), sensorsOn(on), "sensors " + on);
        for (String name : NAMES) {
            eq(RidesEnglishTest.notesTitle(name), notesTitle(name), "notes title");
            eq(RidesEnglishTest.activeTitle(null, name), activeTitle(null, name), "active title");
            eq(RidesEnglishTest.activeTitle("x", name), activeTitle("x", name), "active title attention");
            eq(RidesEnglishTest.deleteTitle(name), deleteTitle(name), "delete title");
            eq(RidesEnglishTest.viewRoute(name.isEmpty() ? "Ride" : name), viewRoute(name.isEmpty() ? T.ridesDefaultName() : name), "view route");
            eq(RidesEnglishTest.experimental(name), experimental(name), "experimental");
            eq(RidesEnglishTest.showing(name), showing(name), "showing");
            for (boolean wide : new boolean[] {true, false}) eq(RidesEnglishTest.historyRow(name, wide), historyRow(name, wide), "history row");
        }
        for (long millis : new long[] {-1, 0, 999, 59_999, 60_000, 3_599_999, 3_600_000, 86_400_000L * 3})
            eq(RidesEnglishTest.elapsed(millis), elapsed(millis), "elapsed " + millis);
        for (long n = 0; n <= 25; n++) {
            eq(RidesEnglishTest.gapChip(n), gapChip(n), "gaps " + n);
            eq(RidesEnglishTest.historyCount((int) n, ""), historyCount((int) n, ""), "history " + n);
            eq(RidesEnglishTest.historyCount((int) n, "12 KB"), historyCount((int) n, "12 KB"), "history size " + n);
            eq(RidesEnglishTest.compareRow((int) n), compareRow((int) n), "compare row " + n);
            eq(RidesEnglishTest.chosen((int) n), chosen((int) n), "chosen " + n);
            eq(RidesEnglishTest.placement("mounted", n * 997), placement("mounted", n * 997), "mounted " + n);
            eq(RidesEnglishTest.placement("pocket", n * 997), placement("pocket", n * 997), "pocket " + n);
        }
        for (long bytes : new long[] {0, 1, 499, 999_499, 999_999, 1_000_000, 12_340_000, 5_000_000_000L})
            eq(RidesEnglishTest.storage(bytes), storage(bytes), "storage " + bytes);
        for (double drop : new double[] {-12.6, -1.2, -0.4, 0, 0.5, 1.2, 33.3})
            for (boolean gain : new boolean[] {true, false}) eq(RidesEnglishTest.battery(gain, drop), battery(gain, drop), "battery " + drop);
        String[] a = RidesEnglishTest.direction(65_000, 3_600_000), b = direction(65_000, 3_600_000);
        for (int i = 0; i < a.length; i++) eq(a[i], b[i], "direction " + i);
        eq(RidesEnglishTest.compareLine("Distance", "1 km", "Not measured (Too short)"), compareLine("Distance", "1 km", "Not measured (Too short)"), "compare line");
        String[] reasons = {null, "Too short", "Odometer reset"};
        for (String first : reasons) for (String second : reasons)
            if (first != null || second != null) eq(baseReason(first, second), reason(first, second), "reason " + first + " " + second);
        // Base RidesScreen.java:2092 and 1328-1381, spelled out.
        eq("25.0 Wh/mi · throughput", efficiency("25.0 Wh/mi", false), "efficiency throughput");
        eq("25.0 Wh/mi · net", efficiency("25.0 Wh/mi", true), "efficiency net");
        String[] legends = legends("1.20 kW", "12 ft", "120 ft", true, "20 mph");
        eq("Power magnitude · blue 0 → yellow 1.20 kW · gray: missing", legends[0], "power legend");
        eq("Relative elevation · blue 12 ft → yellow 120 ft · gray: missing", legends[1], "elevation legend");
        eq("Scooter speed · blue 0 → yellow 20 mph or faster · gray dashed: missing", legends[2], "scooter legend");
        eq("GPS speed · blue 0 → yellow 20 mph or faster · gray dashed: missing", legends("", "", "", false, "20 mph")[2], "GPS legend");
        eq("At 12:04:05", T.mapsChartCursorAt("12:04:05"), "chart cursor");
        eq(", accuracy ±12 ft", T.ridesPointAccuracy("12 ft"), "accuracy");
        phoneStatus();
        dates();
    }

    /** The texts come from the catalog: with a source that answers keys, each names its key. */
    private static void fromCatalog() {
        T.Source saved = T.source();
        T.install(KEYS);
        try {
            eq("‹rides_live_title›", activeTitle(null, "Hill"), "title key");
            eq("‹rides_gaps#3›", gapChip(3), "gaps key");
            eq("‹rides_gap_one›", gapChip(1), "gap one key");
            eq("‹rides_history_count#1›", historyCount(1, ""), "history key");
            eq("‹rides_sensor_status_motion_raw›", phoneStatusText(EnumSet.of(StatusFlag.GPS_WAITING, StatusFlag.MOTION_RAW)), "status key");
            eq("‹fmt_quantity›", distance(2.0, "km"), "distance key");
            eq("‹fmt_percent›", percent(.5), "percent key");
            eq("‹rides_acceleration_with_g›", acceleration(1.0), "acceleration key");
        } finally {
            T.install(saved);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[] {Locale.US, Locale.GERMANY, new Locale("ar", "EG"), new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                english();
            }
        } finally {
            Locale.setDefault(saved);
        }
        fromCatalog();
        statusFlagsMatch();
        System.out.println("RidesTextTest: " + checks + " checks passed");
    }
}
