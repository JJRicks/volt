package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * B3's own checks (the English output itself is pinned by characterization/ReadingsTextTest):
 *
 * - every reading's full name (ScooterReadingLabels.full) is its Telemetry name in English, and every group's
 *   label (ScooterReadingLabels.group) is the group id, so later batches can switch to them with no English change;
 * - the stored unit suffixes ScooterReadingLabels.shown recognises are " " plus the UnitCode's data code, which is what
 *   makes the Fmt quantity it writes equal the stored text in English;
 * - every chart series' displayLabel() is its English label;
 * - with a comma-decimal locale (what Fmt gives a shipped German), Live Stats quantities take that locale's
 *   separator and keep their decimals, and text Telemetry did not write in that form stays as it was. The
 *   locale goes in as ScooterReadingLabels.shown's legacy argument: no language ships yet, so Fmt.legacy()
 *   is true under every T.Source (AppLanguages.resolve gives English for any tag outside l10n/lang) and the
 *   display path cannot be switched on in a test;
 * - with a source that answers every id with its key (a stand-in for a translation), the reading names, groups,
 *   values and legends all come from the catalog, never from English text: nothing compares rendered text.
 */
public final class ReadingsCatalogTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        english();
        suffixes();
        commaLocale();
        keyEcho();
        System.out.println("ReadingsCatalogTest: " + checks + " checks passed (reading ladders, groups, values and legends from the catalog)");
    }

    /**
     * Full names the owner-approved D3 terms renamed (l10n/english-changes.tsv). Their Telemetry names stay
     * English as they were, for reports, history and the Stats search; every other full name is its Telemetry name.
     */
    static final java.util.Map<String, String> RENAMED = new java.util.HashMap<>();

    /** The full name a reading shows in English: its Telemetry name, or its D3 rename. */
    static String shownName(Telemetry.Metric m) { return RENAMED.containsKey(m.key) ? RENAMED.get(m.key) : m.label; }
    static {
        String[][] renamed = {{"maximum_speed_kmh", "Current trip top speed"}, {"driving_mode", "Drive mode (raw)"},
                {"display_units", "Scooter display units"}, {"auto_sensor", "Automatic lights (raw)"},
                {"tyre_reminder", "Tire reminder (raw)"}, {"proximity_key", "Proximity unlock setting (raw)"},
                {"start_speed", "Kick-start speed setting (raw)"}, {"timed_charging", "Scheduled charging setting (raw)"},
                {"slope_assist", "Assist settings (raw)"}, {"overspeed_control", "Overspeed active control setting (raw)"},
                {"drive_speed_limit", "Drive mode speed limit setting (raw)"}};
        for (String[] r : renamed) RENAMED.put(r[0], r[1]);
    }

    private static void english() throws Exception {
        int renamed = 0;
        for (Telemetry.Metric m : new Telemetry().metrics()) {
            String expected = RENAMED.containsKey(m.key) ? RENAMED.get(m.key) : m.label;
            if (RENAMED.containsKey(m.key)) {
                renamed++;
                check(!m.label.equals(expected), m.key + ": listed as renamed, but its Telemetry name is " + m.label);
            }
            check(expected.equals(ScooterReadingLabels.full(m.key, null)), m.key + ": the full name is " + expected + ", not "
                    + ScooterReadingLabels.full(m.key, null));
            check(m.group.equals(ScooterReadingLabels.group(m.group)), m.key + ": the group label is the group id " + m.group);
            check(!ScooterReadingLabels.label(m.key, "<none>").equals("<none>"), m.key + " has a short name");
        }
        check(renamed == RENAMED.size(), "every renamed reading is a Telemetry metric");
        check("x".equals(ScooterReadingLabels.full("not_a_reading", "x")) && ScooterReadingLabels.full(null, null) == null,
                "unknown readings keep the fallback");
        check("Firmware".equals(ScooterReadingLabels.group("Firmware")) && ScooterReadingLabels.group(null) == null, "unknown groups stay as they are");
        for (boolean miles : new boolean[]{false, true})
            for (RideChartData.Series s : RideChartData.build(Collections.<RideStore.Point>emptyList(), null, miles))
                check(s.label.equals(s.displayLabel()), "ride series " + s.key + " shows its English label");
        // The charge chart with all twelve series (a lower target and a witnessed 100% end).
        String rows = row(0, 78, meta("v1", 20d, 5d, 80d)) + row(10000, 79, meta("v1", 19d, 4.5, 80d))
                + row(20000, 99, meta("v2", 2d, 1d, 100d)) + row(30000, 100, meta("v2", null, null, 100d));
        java.io.File directory = java.nio.file.Files.createTempDirectory("readings-catalog-test-").toFile();
        java.io.File file = new java.io.File(directory, java.util.UUID.randomUUID() + ".csv");
        try {
            java.nio.file.Files.write(file.toPath(), ("charge-v2,fixture,STOCK," + WALL + "," + MONO + "\n" + rows + "end,COMPLETE\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            List<RideChartData.Series> charge = ChargeChartData.build(new ChargeSessionStore(directory).recordings("fixture").get(0), false);
            check(charge.size() == 12, "the charge fixture has all twelve series, not " + charge.size());
            for (RideChartData.Series s : charge) check(s.label.equals(s.displayLabel()), "charge series " + s.key + " shows its English label");
        } finally {
            file.delete();
            directory.delete();
        }
        List<RideChartData.Value> none = new ArrayList<>();
        RideChartData.Series custom = new RideChartData.Series("power_w", "Battery power", "W", 0, 0, none, null);
        check("Battery power".equals(custom.displayLabel()), "a series built with display text shows that text");
    }

    private static void suffixes() {
        Object[][] pairs = {{Telemetry.SUFFIX_VOLT, UnitCode.VOLT}, {Telemetry.SUFFIX_AMP, UnitCode.AMP}, {Telemetry.SUFFIX_WATT, UnitCode.WATT},
            {Telemetry.SUFFIX_CELSIUS, UnitCode.CELSIUS}, {Telemetry.SUFFIX_WATT_HOUR, UnitCode.WATT_HOUR},
            {Telemetry.SUFFIX_AMP_HOUR, UnitCode.AMP_HOUR}, {Telemetry.SUFFIX_KMH, UnitCode.KMH}, {Telemetry.SUFFIX_KM, UnitCode.KM},
            {Telemetry.SUFFIX_MINUTE, UnitCode.MINUTE}};
        for (Object[] pair : pairs) {
            UnitCode unit = (UnitCode) pair[1];
            check(pair[0].equals(" " + unit.code), unit + ": the stored suffix is a space and the data code");
            check(unit.symbol().equals(unit.code), unit + ": the English symbol is the data code");
        }
        check(Telemetry.SUFFIX_RANGE.startsWith(Telemetry.SUFFIX_KM + " "), "the range suffix starts with the km suffix");
        check("Time".equals(UnitCode.TIME.code) && "Time".equals(UnitCode.TIME.symbol()), "the chart's Time unit");
    }

    private static void commaLocale() {
        Locale de = Locale.GERMANY;
        equal("36,500 V", ScooterReadingLabels.shown("voltage_v", "36.500 V", 36.5, de), "voltage keeps three decimals");
        equal("-1,250 A", ScooterReadingLabels.shown("current_a", "-1.250 A", -1.25, de), "signed current");
        equal("0,000 A", ScooterReadingLabels.shown("current_a", "0.000 A", -0.0, de), "a negative zero shows a plain zero");
        equal("-45,6 W", ScooterReadingLabels.shown("power_w", "-45.6 W", -45.6, de), "power");
        equal("25 °C", ScooterReadingLabels.shown("temperature_c", "25 °C", 25.0, de), "temperature");
        equal("12,34 Ah", ScooterReadingLabels.shown("rated_capacity_ah", "12.34 Ah", 12.34, de), "capacity");
        equal("25,3 km/h", ScooterReadingLabels.shown("speed_kmh", "25.3 km/h", 25.3, de), "speed in tenths");
        equal("25 km/h", ScooterReadingLabels.shown("speed_kmh", "25 km/h", 25.0, de), "speed in whole units");
        equal("12 km", ScooterReadingLabels.shown("remaining_range_km", "12 km (estimate)", 12.0, de), "range drops (estimate)");
        equal("1234,5 km", ScooterReadingLabels.shown("odometer_km", "1234.5 km", 1234.5, de), "odometer, no grouping");
        equal("30 min", ScooterReadingLabels.shown("trip_minutes", "30 min", 30.0, de), "trip time");
        equal("75 %", ScooterReadingLabels.shown("battery_percent", "75 %", 75.0, de), "percentage");
        equal("12 charges", ScooterReadingLabels.shown("charge_cycles", "12 charges", 12.0, de), "charge count");
        equal("Code 14", ScooterReadingLabels.shown("warning_code", "Code 14", 14.0, de), "warning code");
        // Not the form Telemetry writes: left to displayValue's English rules.
        equal(null, ScooterReadingLabels.shown("voltage_v", "36.500 V", 36.4, de), "a value that does not match its number");
        equal(null, ScooterReadingLabels.shown("voltage_v", "36.500 V", null, de), "a value without a number");
        equal(null, ScooterReadingLabels.shown("voltage_v", "36.5000 V", 36.5, de), "more decimals than Telemetry writes");
        equal(null, ScooterReadingLabels.shown("voltage_v", "036.500 V", 36.5, de), "a padded number");
        equal(null, ScooterReadingLabels.shown("voltage_v", "36.500 V (raw)", 36.5, de), "a raw value");
        equal(null, ScooterReadingLabels.shown("power_w", "36.500 V", 36.5, de), "another unit");
        equal(null, ScooterReadingLabels.shown("warning_code", "Code 14", 13.0, de), "a code that does not match its number");
        equal(null, ScooterReadingLabels.shown("battery_status", "Abnormal; code 04", 4.0, de), "a padded battery code");
        equal(null, ScooterReadingLabels.shown("driving_mode", "SPORT", 3.0, de), "drive-mode names stay as the scooter shows them");
        equal(null, ScooterReadingLabels.shown("battery_manufactured", "2025-03-01", null, de), "dates stay as stored");
        equal(null, ScooterReadingLabels.shown("battery_status", "Abnormal; code -4", -4.0, de), "Telemetry never writes a negative code");
        equal(null, ScooterReadingLabels.shown("warning_code", "Code -4", -4.0, de), "nor a negative warning");
        equal(null, ScooterReadingLabels.shown("charge_cycles", "-1 charges", -1.0, de), "nor a negative count");
        // The unknown history format note, exactly as Telemetry writes it, and text that only resembles it.
        String note = Telemetry.UNKNOWN_FORMAT + 7 + Telemetry.UNKNOWN_FORMAT_SEPARATOR + 1 + Telemetry.UNKNOWN_FORMAT_RECORDS;
        equal("Unknown format 7 \u00b7 1 record", ScooterReadingLabels.shown("stored_trips", note, null, de), "the note, 1 record");
        equal(null, ScooterReadingLabels.shown("stored_trips", note.replace("7;", "07;"), null, de), "a padded format number");
        equal(null, ScooterReadingLabels.shown("stored_trips", note.replace("; 1", "; -1"), null, de), "a negative record count");
        equal(null, ScooterReadingLabels.shown("stored_trips", note.replace("7;", "7; 2;"), null, de), "a third number");
        equal(null, ScooterReadingLabels.shown("stored_trips", note + " ", null, de), "trailing text");
        equal(null, ScooterReadingLabels.shown("stored_trips", note.replace("; 1", "; 9999999999"), null, de), "an oversized count");
        equal(null, ScooterReadingLabels.shown("stored_trips", note.replace("7", "\u0667"), null, de), "a non-Latin digit");
    }

    private static void keyEcho() {
        T.install(new T.Source() {
            @Override public String string(int id) { return "<" + T.key(id) + (T.isFormatted(id) ? args(id) : "") + ">"; }
            @Override public String plural(int id, long quantity) { return "<" + T.key(id) + specs(EnglishText.INSTANCE.plural(id, quantity)) + ">"; }
            @Override public String language() { return "en"; }
        });
        try {
            equal("<readings_odometer_km>", ScooterReadingLabels.full("odometer_km", null), "full name");
            equal("<readings_odometer_km_short>", ScooterReadingLabels.label("odometer_km", null), "short name");
            equal("<readings_odometer_km_compact>", ScooterReadingLabels.compact("odometer_km", null), "compact name");
            equal("<readings_group_battery>", ScooterReadingLabels.group("Battery"), "group");
            equal("<readings_value_locked>", Telemetry.displayValue("lock_status", "Locked (1)", 1.0), "lock word");
            equal("<readings_value_unlocked>", Telemetry.displayValue("lock_status", "Unlocked (0)", 0.0), "unlock word");
            equal("<readings_value_imperial>", Telemetry.displayValue("display_units", "Imperial (0)", 0.0), "units word");
            equal("<readings_value_warning_code:14>", Telemetry.displayValue("warning_code", "Code 14", 14.0), "warning code");
            equal("<readings_value_battery_abnormal>", Telemetry.displayValue("battery_status", "Abnormal; code 4", 4.0), "battery word");
            equal("<readings_value_trips:3>", Telemetry.displayValue("stored_trips", "Trip 1: 1 km\nTrip 2: 2 km\nTrip 3: 3 km", 3.0), "trip count");
            equal("<readings_value_charges:12>", Telemetry.displayValue("charge_cycles", "12 charges", 12.0), "charge count");
            equal("<readings_value_percent:75>", Telemetry.displayValue("battery_percent", "75 %", 75.0), "percentage");
            equal("<readings_value_acknowledged>", Telemetry.displayValue("ride_read_raw", "Acknowledged; ride data arrives separately", null), "ride read");
            equal("<readings_value_unknown_format_records:7 3>", Telemetry.displayValue("stored_trips",
                    "Unknown history format 7; 3 records (raw bytes available in export)", null), "unknown history format, a plural on the count");
            equal("<readings_value_unknown_format:7 · 2 · 3>", Telemetry.displayValue("stored_trips",
                    "Unknown history format 7; 2; 3 records (raw bytes available in export)", null), "unknown history format, text Volt did not write");
            equal("<readings_value_no_trips>", Telemetry.displayValue("stored_trips", "No stored trips reported", 0.0), "no trips");
            equal("<readings_value_empty_response>", Telemetry.displayValue("fault_history_raw", "Empty response", null), "empty response");
            equal("<readings_value_missing_status>", Telemetry.displayValue("response_70", "Missing response status", null), "missing status");
            equal("<readings_value_empty_serial>", Telemetry.displayValue("scooter_serial", "Empty serial value", null), "empty serial");
            equal("<fmt_quantity:36.500 <units_volt>>", Telemetry.displayValue("voltage_v", "36.500 V", 36.5), "quantity template and unit symbol");
            RideChartData.Series wheel = RideChartData.build(Collections.<RideStore.Point>emptyList(), null, false).get(1);
            equal("<readings_chart_wheel_speed>", wheel.displayLabel(), "ride legend");
            check("Wheel speed".equals(wheel.label), "the series label stays English");
        } finally {
            T.install(null);
        }
    }

    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;

    private static String meta(String version, Double full, Double limit, Double target) {
        return "," + version + "," + field(full) + "," + field(limit) + "," + field(target) + ",CURRENT_RATE,CURRENT_RATE,0";
    }

    private static String field(Double value) { return value == null ? "" : value.toString(); }

    private static String row(long offset, double soc, String metadata) {
        double current = soc >= 100 ? 0 : -2;
        return "s," + (WALL + offset) + "," + (MONO + offset) + "," + soc + ",52.0," + current + "," + (52 * current) + ",20.0,"
                + (offset == 0 ? "1" : "0") + metadata + "\n";
    }

    private static String args(int id) { return specs(EnglishText.INSTANCE.string(id)); }

    /** The placeholders of an English template, in order: ":%1$s %2$d". */
    private static String specs(String english) {
        StringBuilder found = new StringBuilder();
        for (int n = 1; english.contains("%" + n + "$"); n++) {
            int at = english.indexOf("%" + n + "$");
            found.append(n == 1 ? ":" : " ").append(english, at, english.indexOf(english.contains("%" + n + "$d") ? 'd' : 's', at) + 1);
        }
        return found.toString();
    }

    private static void equal(String expected, String actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
