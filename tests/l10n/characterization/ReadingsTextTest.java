package com.jjricks.volt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * G5 characterization for B3 (telemetry and reading labels): the English these classes produced before
 * the batch, byte for byte.
 *
 * - Telemetry's catalog: every reading's key, group and full name, in order (the data reports keep).
 * - ScooterReadingLabels: the short and compact names of every reading, and the fallback for others.
 * - Telemetry.drivingModeLabel and Telemetry.displayValue (the Live Stats value column), for listed
 *   inputs and, through a digest, for a grid of every reading key against many stored values and numbers.
 * - Telemetry's decoded values: seeded random replies to every read command, with each reading's stored
 *   value and its displayValue, through a digest.
 * - The ride and charge chart series: keys, legend names, units and scales.
 * - DisplayUnits.metricText on stored-trip and distance text.
 *
 * Everything runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR: while Volt is English the
 * output must not follow the phone's region.
 */
public final class ReadingsTextTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"), new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                table("Telemetry catalog", METRICS, metricRows(), phone);
                table("reading names", LABELS, labelRows(), phone);
                table("drive mode names", MODES, modeRows(), phone);
                table("Live Stats values", DISPLAY, displayRows(), phone);
                table("ride chart series", RIDE_SERIES, rideRows(), phone);
                table("charge chart series", CHARGE_SERIES, chargeRows(), phone);
                table("stored-trip and distance text", METRIC_TEXT, metricTextRows(), phone);
                String[] grid = displayGrid();
                equal(DISPLAY_GRID_DIGEST, grid[0], phone, "Live Stats value grid digest (" + grid[1] + " values)");
                equal(DISPLAY_GRID_COUNT, grid[1], phone, "Live Stats value grid size");
                String[] decoded = decodedGrid();
                equal(DECODED_DIGEST, decoded[0], phone, "decoded reply digest (" + decoded[1] + " readings)");
                equal(DECODED_COUNT, decoded[1], phone, "decoded reply grid size");
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("ReadingsTextTest: " + checks + " checks passed (B3 reading names, values and chart text under 5 phone locales)");
    }

    // Inputs

    static final String[] EXTRA_KEYS = {"unknown_key", "", "Battery", "response_7a", "gps_speed", "estimated_range_km"};

    static final int[] PRODUCTS = {2538, 25462, 22131, 0, -1};

    static final Double[] MODE_CODES = {null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 1.0, 2.0, 3.0,
        4.0, 5.0, 11.0, 2.5, -1.0, -0.0, 255.0, 1e20};

    /** Stored values the grid pairs with every key and number. */
    static final String[] VALUES = {
        null, "", "Imperial (0)", "Metric (1)", "Imperial (5)", "Imperial", "2 (raw)", "None (0)", "None", "Code 3", "Code 0",
        "Code x", "Normal (0)", "Abnormal; code 4", "Abnormal; code ", "Abnormal", "Unlocked (0)", "Locked (1)", "Locked (2)",
        "3 (raw)", "Locked (1) (raw)", " (raw)", "No stored trips reported", "Empty response", "Missing response status",
        "Empty serial value", "Acknowledged; ride data arrives separately", "Acknowledged;", "Acknowledged",
        "v1: tenths; optional 32-bit odometer", "v0: whole units", "v1:", "v2: x",
        "Unknown history format 7; 3 records (raw bytes available in export)", "Unknown history format 7; 3",
        "Unknown history format 2; 5; 6 records", "Unknown history format  records",
        "Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "Trip 1: 1 km\nTrip 2: 2 km", "Trip x",
        "75 %", "100 %", "0 %", "75%", "7 %", "75.0 %", "36.500 V", "36.5 V", "36 V", "-1.250 A", "0.000 A", "-0.000 A",
        "1.250 A", "120.5 W", "-3.0 W", "0.0 W", "25 °C", "-5 °C", "0 °C", "1234 Wh", "56 Ah", "12.34 Ah", "0.50 Ah",
        "12 km (estimate)", "12 km", "12.5 km", "12.50 km", "25.3 km/h", "25 km/h", "0.5 km/h", "30 min", "12 charges",
        "1 charges", "0 charges", "5", "2025-03-01", "Year 25, month 13, day 1 (raw)", "0 y 0 mo 0 d 2 h 15 min 0 s",
        "OK; 12 data bytes", "OK; incomplete history record (9 data bytes)", "Scooter returned status 3",
        "OK; 2 data bytes; invalid battery_percent=101", "deadbeef", "1.2.3.4", "0102 (raw)", "SPORT", "WALK", "ECO",
        "DRIVE", "—", "12.5 km/h (raw)", "1e3 V", "036.500 V", "36.500  V", "NaN V", "Infinity km", "12 km/h km",
        "12,5 km", "1,234 Wh"
    };

    static final Double[] NUMBERS = {null, 0.0, -0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 7.0, 12.0, 12.5, 12.34, 25.3, 30.0, 36.5,
        75.0, 100.0, -1.25, -3.0, -5.0, 1234.0, 56.0, 0.5, 1.5, 0.9999, 1.0000001, Double.NaN, Double.POSITIVE_INFINITY, 1e20};

    /** Listed Live Stats inputs: key, stored value, number. */
    static final String[][] DISPLAY_INPUTS = {
        {"display_units", "Imperial (0)", "0.0"}, {"display_units", "Metric (1)", "1.0"}, {"display_units", "4 (raw)", "4.0"},
        {"warning_code", "None (0)", "0.0"}, {"warning_code", "Code 14", "14.0"}, {"battery_status", "Normal (0)", "0.0"},
        {"battery_status", "Abnormal; code 4", "4.0"}, {"lock_status", "Unlocked (0)", "0.0"}, {"lock_status", "Locked (1)", "1.0"},
        {"lock_status", "3 (raw)", "3.0"}, {"remaining_range_km", "12 km (estimate)", "12.0"},
        {"remaining_range_km", "0 km (estimate)", "0.0"}, {"ride_report_format", "v1: tenths; optional 32-bit odometer", "1.0"},
        {"ride_report_format", "v0: whole units", "0.0"}, {"ride_read_raw", "Acknowledged; ride data arrives separately", null},
        {"ride_read_raw", "0102", null}, {"stored_trips", "No stored trips reported", "0.0"},
        {"stored_trips", "Trip 1: 1 km", "1.0"}, {"stored_trips", "Trip 1: 1 km\nTrip 2: 2 km\nTrip 3: 3 km", "3.0"},
        {"stored_trips", "Trip 1: 1 km", "0.5"}, {"stored_trips", "Trip 1: 1 km", "1.5"}, {"stored_trips", "Trip 1: 1 km", "21.0"},
        {"stored_trips", "Unknown history format 7; 3 records (raw bytes available in export)", null},
        {"stored_trips", "Unknown history format 7; 3", null}, {"fault_history_raw", "Empty response", null},
        {"fault_history_raw", "0a0b", null}, {"scooter_serial", "Empty serial value", null}, {"response_70", "Missing response status", null},
        {"response_72", "OK; 34 data bytes", null}, {"response_90", "Scooter returned status 2", null},
        {"battery_percent", "75 %", "75.0"}, {"battery_percent", "100 %", "100.0"}, {"battery_health_percent", "98 %", "98.0"},
        {"charge_limit_percent", "80 %", "80.0"}, {"voltage_v", "36.500 V", "36.5"}, {"voltage_v", "0.000 V", "0.0"},
        {"current_a", "-1.250 A", "-1.25"}, {"current_a", "0.000 A", "0.0"}, {"power_w", "-45.6 W", "-45.6"},
        {"temperature_c", "-5 °C", "-5.0"}, {"temperature_c", "25 °C", "25.0"}, {"energy_throughput_wh", "1234 Wh", "1234.0"},
        {"capacity_throughput_ah", "56 Ah", "56.0"}, {"rated_capacity_ah", "12.34 Ah", "12.34"},
        {"health_adjusted_capacity_ah", "12.09 Ah", "12.0932"}, {"charge_cycles", "12 charges", "12.0"},
        {"charge_cycles", "1 charges", "1.0"}, {"charge_cycles", "0 charges", "0.0"}, {"deep_discharge", "3", "3.0"},
        {"speed_kmh", "25.3 km/h", "25.3"}, {"speed_kmh", "25 km/h", "25.0"}, {"maximum_speed_kmh", "31.0 km/h", "31.0"},
        {"average_speed_kmh", "14.2 km/h", "14.2"}, {"trip_km", "12.5 km", "12.5"}, {"trip_km", "12 km", "12.0"},
        {"odometer_km", "1234.5 km", "1234.5"}, {"trip_minutes", "30 min", "30.0"}, {"battery_manufactured", "2025-03-01", null},
        {"battery_manufactured", "Year 25, month 13, day 1 (raw)", null},
        {"high_temperature_duration", "0 y 0 mo 0 d 2 h 15 min 0 s", null}, {"meter_version", "1.2.3.4", null},
        {"meter_version", "00010203 (raw)", null}, {"cruise_control", "1 (raw)", "1.0"}, {"driving_mode", "SPORT", "3.0"},
        {"driving_mode", "3 (raw)", "3.0"}, {"self_discharge_raw", "7 (raw)", "7.0"}, {"voltage_v", "36.5 V", "36.5"},
        {"voltage_v", "36.500 V", "36.4"}, {"battery_percent", "75 %", null}, {"speed_kmh", "25.3 km/h", null},
        {"remaining_range_km", "12 km (estimate)", null}, {null, "36.500 V", "36.5"}, {"voltage_v", null, "36.5"},
        {"unknown_key", "36.500 V", "36.5"}
    };

    /** DisplayUnits.metricText inputs: text, units preference. */
    static final String[][] METRIC_TEXT_INPUTS = {
        {"Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "km"},
        {"Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "mi"},
        {"Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "dual"},
        {"Trip 1: 12 km; 30 min; avg 15 km/h; max 25 km/h\nTrip 2: 3 km; 9 min; avg 20 km/h; max 28 km/h", "dual"},
        {"12.50 km", "km"}, {"12.50 km", "mi"}, {"12.50 km", "dual"}, {"0.00 km", "dual"}, {"012.5 km", "dual"},
        {"12.500000000000000001 km", "dual"}, {"99999999999999999999 km", "dual"}, {"1.25 km/h", "dual"}, {"36.500 V", "dual"},
        {"No stored trips reported", "dual"}, {"12 kmh", "dual"}, {"12 km/hx", "dual"}, {"x12 km", "dual"}, {"12.5 km", null},
        {"12.5 km", "other"}, {null, "dual"}, {"", "dual"}
    };

    // Rows

    static String[][] metricRows() {
        List<String[]> rows = new ArrayList<>();
        for (Telemetry.Metric m : new Telemetry().metrics()) rows.add(new String[]{m.key, m.group, m.label});
        return rows.toArray(new String[0][]);
    }

    static String[][] labelRows() {
        List<String> keys = new ArrayList<>();
        for (Telemetry.Metric m : new Telemetry().metrics()) keys.add(m.key);
        keys.addAll(Arrays.asList(EXTRA_KEYS));
        List<String[]> rows = new ArrayList<>();
        for (String key : keys)
            rows.add(new String[]{key, ScooterReadingLabels.label(key, "<fallback>"), ScooterReadingLabels.compact(key, "<fallback>")});
        rows.add(new String[]{null, ScooterReadingLabels.label(null, "<fallback>"), ScooterReadingLabels.compact(null, "<fallback>")});
        return rows.toArray(new String[0][]);
    }

    static String[][] modeRows() {
        List<String[]> rows = new ArrayList<>();
        for (int product : PRODUCTS) for (Double code : MODE_CODES)
            rows.add(new String[]{Integer.toString(product), code == null ? null : Double.toString(code), Telemetry.drivingModeLabel(product, code)});
        return rows.toArray(new String[0][]);
    }

    static String[][] displayRows() {
        List<String[]> rows = new ArrayList<>();
        for (String[] input : DISPLAY_INPUTS)
            rows.add(new String[]{input[0], input[1], input[2],
                Telemetry.displayValue(input[0], input[1], input[2] == null ? null : Double.valueOf(input[2]))});
        return rows.toArray(new String[0][]);
    }

    static String[][] rideRows() {
        List<String[]> rows = new ArrayList<>();
        for (boolean miles : new boolean[]{false, true})
            for (RideChartData.Series s : RideChartData.build(Collections.<RideStore.Point>emptyList(), null, miles))
                rows.add(new String[]{Boolean.toString(miles), s.key, s.label, s.unit, Double.toString(s.min), Double.toString(s.max)});
        return rows.toArray(new String[0][]);
    }

    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;

    static String[][] chargeRows() throws Exception {
        List<String[]> rows = new ArrayList<>();
        String[] fixtures = {
            // No estimates, one sample with no temperature.
            "charge-v1|" + row(0, 50, -2, 20d, true, "") + row(10000, 51, 1.5, null, false, "") + "|STOPPED",
            // A lower target and a witnessed 100% end: all twelve series.
            "charge-v2|" + row(0, 78, -2, 20d, true, meta("v1", 20d, 5d, 80d)) + row(10000, 79, -2, 21d, false, meta("v1", 19d, 4.5, 80d))
                + row(20000, 99, -2, 22d, false, meta("v2", 2d, 1d, 100d)) + row(30000, 100, 0, 22d, false, meta("v2", null, null, 100d)) + "|COMPLETE",
            // Constant estimates: the finish-time scale is widened around one value.
            "charge-v2|" + row(0, 50, -2, 20d, true, meta("v1", 3d, 3d, 100d)) + row(10000, 50, -2, 20d, false, meta("v1", 3d, 3d, 100d)) + "|STOPPED",
        };
        int index = 0;
        for (String fixture : fixtures) {
            String[] parts = fixture.split("\\|", -1);
            ChargeSessionStore.HistorySession session = session(parts[0], parts[1], parts[2]);
            for (boolean fahrenheit : new boolean[]{false, true})
                for (RideChartData.Series s : ChargeChartData.build(session, fahrenheit))
                    rows.add(new String[]{index + (fahrenheit ? "F" : "C"), s.key, s.label, s.unit, Double.toString(s.min), Double.toString(s.max)});
            index++;
        }
        return rows.toArray(new String[0][]);
    }

    private static String meta(String version, Double full, Double limit, Double target) {
        return "," + version + "," + field(full) + "," + field(limit) + "," + field(target) + ",CURRENT_RATE,CURRENT_RATE,0";
    }

    private static String field(Double value) { return value == null ? "" : value.toString(); }

    private static String row(long offset, double soc, double current, Double temperature, boolean gap, String metadata) {
        return "s," + (WALL + offset) + "," + (MONO + offset) + "," + soc + ",52.0," + current
                + "," + (52 * current) + "," + field(temperature) + "," + (gap ? "1" : "0") + metadata + "\n";
    }

    private static ChargeSessionStore.HistorySession session(String version, String rows, String stop) throws Exception {
        File directory = Files.createTempDirectory("readings-text-test-").toFile();
        File file = new File(directory, UUID.randomUUID() + ".csv");
        try {
            Files.write(file.toPath(), (version + ",fixture,STOCK," + WALL + "," + MONO + "\n" + rows + "end," + stop + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            List<ChargeSessionStore.HistorySession> result = new ChargeSessionStore(directory).recordings("fixture");
            if (result.size() != 1) throw new AssertionError("charge fixture did not parse: " + result.size());
            return result.get(0);
        } finally {
            File[] left = directory.listFiles();
            if (left != null) for (File each : left) each.delete();
            directory.delete();
        }
    }

    static String[][] metricTextRows() {
        List<String[]> rows = new ArrayList<>();
        for (String[] input : METRIC_TEXT_INPUTS) rows.add(new String[]{input[0], input[1], DisplayUnits.metricText(input[0], input[1])});
        return rows.toArray(new String[0][]);
    }

    /** Every reading key (and a few others) against every stored value and number: {digest, count}. */
    static String[] displayGrid() throws Exception {
        List<String> keys = new ArrayList<>();
        for (Telemetry.Metric m : new Telemetry().metrics()) keys.add(m.key);
        keys.addAll(Arrays.asList(EXTRA_KEYS));
        keys.add(null);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        for (String key : keys) for (String value : VALUES) for (Double number : NUMBERS) {
            String shown = Telemetry.displayValue(key, value, number);
            digest.update((key + "\u0000" + value + "\u0000" + number + "\u0000" + shown + "\n").getBytes(StandardCharsets.UTF_8));
            count++;
        }
        return new String[]{hex(digest.digest()), Long.toString(count)};
    }

    /**
     * Seeded random replies (java.util.Random is fully specified, so every JVM sees the same bytes) to
     * every read command, one Telemetry for the whole run as on a connection: after each reply, every
     * reading's stored value, number, displayValue and drive-mode name. {digest, count}.
     */
    static String[] decodedGrid() throws Exception {
        int[] commands = {0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x79, 0x7b, 0x90, 0x91, 0x92};
        Random random = new Random(0xB3B3B3L);
        Telemetry telemetry = new Telemetry();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0, time = 0;
        for (int round = 0; round < 60; round++) {
            for (int cmd : commands) {
                int length = random.nextInt(4) == 0 ? random.nextInt(4) : random.nextInt(50);
                byte[] reply = new byte[length];
                random.nextBytes(reply);
                if (length > 0 && random.nextInt(5) != 0) reply[0] = 0;
                if (cmd == 0x76 && length > 1 && random.nextBoolean()) {
                    // Valid history: whole records, optionally a version byte of 0 or 1.
                    int records = (length - 1) / 8;
                    byte[] history = new byte[1 + records * 8 + (random.nextBoolean() ? 1 : 0)];
                    random.nextBytes(history);
                    history[0] = 0;
                    if (history.length % 8 == 2) history[history.length - 1] = (byte) random.nextInt(3);
                    reply = history;
                }
                telemetry.accept(cmd, reply, time += 1 + random.nextInt(500));
                for (Telemetry.Metric m : telemetry.metrics()) {
                    String line = m.key + "\u0000" + m.group + "\u0000" + m.label + "\u0000" + m.value + "\u0000" + m.numericValue
                            + "\u0000" + Telemetry.displayValue(m.key, m.value, m.numericValue) + "\u0000"
                            + Telemetry.drivingModeLabel(round % 2 == 0 ? 2538 : 25462, m.numericValue) + "\n";
                    digest.update(line.getBytes(StandardCharsets.UTF_8));
                    count++;
                }
            }
            if (round % 20 == 19) telemetry.clear();
        }
        return new String[]{hex(digest.digest()), Long.toString(count)};
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return text.toString();
    }

    // Checks

    private static void table(String what, String[][] expected, String[][] actual, Locale phone) {
        equal(Integer.toString(expected.length), Integer.toString(actual.length), phone, what + ": row count");
        for (int i = 0; i < Math.min(expected.length, actual.length); i++)
            equal(Arrays.toString(expected[i]), Arrays.toString(actual[i]), phone, what + ": row " + i);
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + " under " + phone + ": expected <" + expected + "> but was <" + actual + ">");
    }

    // Expected English, generated from the merge base

    static final String DISPLAY_GRID_DIGEST = "831428e67174134ab060c4f381de57a899cf2b9c6c0a11049e4555ca7dcaeaf9";
    static final String DISPLAY_GRID_COUNT = "289971";
    static final String DECODED_DIGEST = "8632a3308378c480c3689e99fba1415ce66b1863c67dbf5c5a6fd3864727125a";
    static final String DECODED_COUNT = "67680";

    static final String[][] METRICS = {
        {"battery_percent", "Battery", "Battery charge (%)"},
        {"voltage_v", "Battery", "Battery voltage"},
        {"current_a", "Battery", "Battery current (signed)"},
        {"power_w", "Battery", "Battery power (signed)"},
        {"temperature_c", "Battery", "Battery temperature"},
        {"battery_health_percent", "Battery", "Reported battery health"},
        {"battery_status", "Battery", "Battery status"},
        {"charging_state", "Battery", "Charging state (raw)"},
        {"charge_cycles", "Battery", "Reported charge count"},
        {"energy_throughput_wh", "Battery", "Energy counter (unverified)"},
        {"capacity_throughput_ah", "Battery", "Lifetime capacity throughput"},
        {"deep_discharge", "Battery", "Deep discharge counter"},
        {"self_discharge_raw", "Battery", "Self discharge rate (raw; unit unknown)"},
        {"rated_capacity_ah", "Battery", "Rated battery capacity"},
        {"health_adjusted_capacity_ah", "Battery", "Capacity estimate from reported health"},
        {"battery_manufactured", "Battery", "Battery manufacturing date"},
        {"high_temperature_duration", "Battery", "High temperature exposure"},
        {"extreme_temperature_charge_raw", "Battery", "Extreme-temperature charging duration (raw; unit unknown)"},
        {"temperature_sign_state", "Battery", "Temperature sign state (raw)"},
        {"current_sign_state", "Battery", "Current sign state (raw)"},
        {"speed_kmh", "Ride", "Live speed"},
        {"remaining_range_km", "Ride", "Scooter's estimated remaining range"},
        {"trip_km", "Ride", "Scooter's current trip distance"},
        {"trip_minutes", "Ride", "Scooter's current trip time"},
        {"maximum_speed_kmh", "Ride", "Current trip maximum speed"},
        {"average_speed_kmh", "Ride", "Current trip average speed"},
        {"odometer_km", "Ride", "Total odometer"},
        {"driving_status", "Ride", "Driving status (raw)"},
        {"stored_trips", "Ride", "Stored trips"},
        {"binding_status", "Settings", "Account binding status (raw)"},
        {"driving_mode", "Settings", "Driving mode (raw)"},
        {"lock_status", "Settings", "Electronic lock"},
        {"cruise_control", "Settings", "Cruise control (raw)"},
        {"tail_light", "Settings", "Tail light setting (raw)"},
        {"energy_recovery", "Settings", "Energy recovery setting (raw)"},
        {"range_algorithm", "Settings", "Range algorithm (raw)"},
        {"display_units", "Settings", "Dashboard units"},
        {"auto_sensor", "Settings", "Automatic light sensor (raw)"},
        {"tyre_reminder", "Settings", "Tyre reminder (raw)"},
        {"ambient_light", "Settings", "Line lighting state / pattern (raw)"},
        {"traction_control", "Settings", "Traction control (raw)"},
        {"turn_sound", "Settings", "Turn signal sound (raw)"},
        {"proximity_key", "Settings", "Proximity key setting (raw)"},
        {"night_mode", "Settings", "Night mode (raw)"},
        {"range_algorithm_mode", "Settings", "Range algorithm mode (raw)"},
        {"eco_light", "Settings", "Line normal / Boost colors (packed raw)"},
        {"drive_light", "Settings", "Star lighting state / pattern (raw)"},
        {"sport_light", "Settings", "Star normal / Boost colors (packed raw)"},
        {"start_speed", "Settings", "Start speed setting (raw)"},
        {"speed_limit", "Settings", "Speed limit setting (raw)"},
        {"volume", "Settings", "Audio setting (model-dependent raw)"},
        {"voice_language", "Settings", "Voice language setting (raw)"},
        {"logo_light", "Settings", "Logo light setting (raw)"},
        {"daytime_light", "Settings", "Daytime running light (raw)"},
        {"maximum_speed_setting", "Settings", "Maximum speed setting (raw)"},
        {"drive_mode_setting", "Settings", "Drive mode setting (raw)"},
        {"settings_mode_raw", "Settings", "Settings read mode byte (raw)"},
        {"scheduled_charge_time_raw", "Settings", "Scheduled charge time (raw timestamp)"},
        {"charge_limit_percent", "Settings", "Charge limit"},
        {"low_power", "Settings", "Low Battery Mode (raw)"},
        {"timed_charging", "Settings", "Timed charging setting (raw)"},
        {"auto_lock_time", "Settings", "Lock duration setting (raw)"},
        {"speed_limit_eligibility", "Settings", "Speed-limit eligibility flag (raw)"},
        {"weather", "Settings", "Weather setting (raw)"},
        {"slope_assist", "Settings", "Slope assistance setting (raw)"},
        {"long_range_mode", "Settings", "Long range setting (raw)"},
        {"overspeed_control", "Settings", "Overspeed control setting (raw)"},
        {"overspeed_alert", "Settings", "Overspeed alert setting (raw)"},
        {"drive_speed_limit", "Settings", "Drive speed limit setting (raw)"},
        {"meter_version", "Device", "Meter firmware"},
        {"controller_version", "Device", "Motor controller firmware"},
        {"battery_version", "Device", "Battery management firmware"},
        {"screen_version", "Device", "Screen firmware"},
        {"uwb_version", "Device", "Proximity firmware"},
        {"scooter_serial", "Device", "Scooter serial number"},
        {"battery_serial_raw", "Device", "Battery serial response (raw)"},
        {"warning_code", "Diagnostics", "Active warning code"},
        {"lock_push_warning", "Diagnostics", "Locked movement warning (raw)"},
        {"hide_e9", "Diagnostics", "E9 display flag (raw)"},
        {"ride_report_format", "Diagnostics", "Ride report format"},
        {"ride_read_raw", "Diagnostics", "Ride read acknowledgment (raw)"},
        {"fault_history_raw", "Diagnostics", "Fault history response (raw)"},
        {"response_70", "Diagnostics", "Scooter settings read (0x70)"},
        {"response_71", "Diagnostics", "Ride read (0x71)"},
        {"response_72", "Diagnostics", "Battery diagnostics read (0x72)"},
        {"response_73", "Diagnostics", "Firmware versions read (0x73)"},
        {"response_74", "Diagnostics", "Scooter serial read (0x74)"},
        {"response_75", "Diagnostics", "Battery serial read (0x75)"},
        {"response_76", "Diagnostics", "Ride history read (0x76)"},
        {"response_79", "Diagnostics", "Battery sign states read (0x79)"},
        {"response_7b", "Diagnostics", "Fault history read (0x7B)"},
        {"response_90", "Diagnostics", "Home telemetry report (0x90)"},
        {"response_91", "Diagnostics", "Ride telemetry report v0 (0x91)"},
        {"response_92", "Diagnostics", "Ride telemetry report v1 (0x92)"},
    };

    static final String[][] LABELS = {
        {"battery_percent", "Battery charge (%)", "Charge"},
        {"voltage_v", "Battery voltage", "Voltage"},
        {"current_a", "Battery current", "Current"},
        {"power_w", "Battery power", "Power"},
        {"temperature_c", "Battery temp.", "Temp."},
        {"battery_health_percent", "Reported health", "Health"},
        {"battery_status", "Battery status", "Status"},
        {"charging_state", "Charging (raw)", "Charge (raw)"},
        {"charge_cycles", "Reported charges", "Charges"},
        {"energy_throughput_wh", "Energy (unverified)", "Energy (unverified)"},
        {"capacity_throughput_ah", "Lifetime throughput", "Throughput"},
        {"deep_discharge", "Deep discharges", "<fallback>"},
        {"self_discharge_raw", "Self-discharge (raw)", "<fallback>"},
        {"rated_capacity_ah", "Rated capacity", "Capacity"},
        {"health_adjusted_capacity_ah", "Est. capacity (health)", "Est. capacity"},
        {"battery_manufactured", "Battery made", "<fallback>"},
        {"high_temperature_duration", "High-temp exposure", "<fallback>"},
        {"extreme_temperature_charge_raw", "Hot/cold charge time (raw)", "<fallback>"},
        {"temperature_sign_state", "Temp. sign (raw)", "<fallback>"},
        {"current_sign_state", "Current sign (raw)", "<fallback>"},
        {"speed_kmh", "Speed", "Speed"},
        {"remaining_range_km", "Est. range", "Range"},
        {"trip_km", "Trip distance", "Distance"},
        {"trip_minutes", "Trip time", "Time"},
        {"maximum_speed_kmh", "Trip top speed", "Top speed"},
        {"average_speed_kmh", "Trip avg. speed", "Avg. speed"},
        {"odometer_km", "Odometer", "Odo."},
        {"driving_status", "Ride status (raw)", "<fallback>"},
        {"stored_trips", "Stored trips", "<fallback>"},
        {"binding_status", "Account binding (raw)", "<fallback>"},
        {"driving_mode", "Drive mode", "Mode"},
        {"lock_status", "Electronic lock", "Lock"},
        {"cruise_control", "Cruise (raw)", "<fallback>"},
        {"tail_light", "Tail light (raw)", "<fallback>"},
        {"energy_recovery", "Recovery (raw)", "<fallback>"},
        {"range_algorithm", "Range algorithm (raw)", "<fallback>"},
        {"display_units", "Display units", "<fallback>"},
        {"auto_sensor", "Auto light (raw)", "<fallback>"},
        {"tyre_reminder", "Tyre reminder (raw)", "<fallback>"},
        {"ambient_light", "Line pattern (raw)", "<fallback>"},
        {"traction_control", "Traction (raw)", "<fallback>"},
        {"turn_sound", "Turn sound (raw)", "<fallback>"},
        {"proximity_key", "Proximity key (raw)", "<fallback>"},
        {"night_mode", "Night mode (raw)", "<fallback>"},
        {"range_algorithm_mode", "Range mode (raw)", "<fallback>"},
        {"eco_light", "Line colors (packed)", "<fallback>"},
        {"drive_light", "Star pattern (raw)", "<fallback>"},
        {"sport_light", "Star colors (packed)", "<fallback>"},
        {"start_speed", "Start speed (raw)", "<fallback>"},
        {"speed_limit", "Speed limit (raw)", "<fallback>"},
        {"volume", "Audio (raw)", "<fallback>"},
        {"voice_language", "Voice language (raw)", "<fallback>"},
        {"logo_light", "Logo light (raw)", "<fallback>"},
        {"daytime_light", "Daytime light (raw)", "<fallback>"},
        {"maximum_speed_setting", "Max. speed setting (raw)", "<fallback>"},
        {"drive_mode_setting", "Mode setting (raw)", "<fallback>"},
        {"settings_mode_raw", "Settings mode byte (raw)", "<fallback>"},
        {"scheduled_charge_time_raw", "Charge schedule (raw)", "<fallback>"},
        {"charge_limit_percent", "Charge limit", "Limit"},
        {"low_power", "Low-battery mode (raw)", "<fallback>"},
        {"timed_charging", "Timed charging (raw)", "<fallback>"},
        {"auto_lock_time", "Lock duration (raw)", "<fallback>"},
        {"speed_limit_eligibility", "Speed eligibility (raw)", "<fallback>"},
        {"weather", "Weather (raw)", "<fallback>"},
        {"slope_assist", "Hill assist (raw)", "<fallback>"},
        {"long_range_mode", "Long range (raw)", "<fallback>"},
        {"overspeed_control", "Overspeed control (raw)", "<fallback>"},
        {"overspeed_alert", "Overspeed alert (raw)", "<fallback>"},
        {"drive_speed_limit", "Drive speed limit (raw)", "<fallback>"},
        {"meter_version", "Meter firmware", "Meter FW"},
        {"controller_version", "Controller firmware", "Control FW"},
        {"battery_version", "Battery firmware", "Battery FW"},
        {"screen_version", "Screen firmware", "Screen FW"},
        {"uwb_version", "Proximity firmware", "Proximity FW"},
        {"scooter_serial", "Scooter serial", "<fallback>"},
        {"battery_serial_raw", "Battery serial (raw)", "<fallback>"},
        {"warning_code", "Warning code", "Warning"},
        {"lock_push_warning", "Lock movement (raw)", "<fallback>"},
        {"hide_e9", "E9 flag (raw)", "<fallback>"},
        {"ride_report_format", "Ride format", "<fallback>"},
        {"ride_read_raw", "Ride read (raw)", "<fallback>"},
        {"fault_history_raw", "Fault history (raw)", "<fallback>"},
        {"response_70", "Settings read \u00b7 70", "<fallback>"},
        {"response_71", "Ride read \u00b7 71", "<fallback>"},
        {"response_72", "Battery read \u00b7 72", "<fallback>"},
        {"response_73", "Firmware read \u00b7 73", "<fallback>"},
        {"response_74", "Scooter serial \u00b7 74", "<fallback>"},
        {"response_75", "Battery serial \u00b7 75", "<fallback>"},
        {"response_76", "Ride history \u00b7 76", "<fallback>"},
        {"response_79", "Battery signs \u00b7 79", "<fallback>"},
        {"response_7b", "Fault history \u00b7 7B", "<fallback>"},
        {"response_90", "Home report \u00b7 90", "<fallback>"},
        {"response_91", "Ride report v0 \u00b7 91", "<fallback>"},
        {"response_92", "Ride report v1 \u00b7 92", "<fallback>"},
        {"unknown_key", "<fallback>", "<fallback>"},
        {"", "<fallback>", "<fallback>"},
        {"Battery", "<fallback>", "<fallback>"},
        {"response_7a", "<fallback>", "<fallback>"},
        {"gps_speed", "<fallback>", "<fallback>"},
        {"estimated_range_km", "<fallback>", "<fallback>"},
        {null, "<fallback>", "<fallback>"},
    };

    static final String[][] MODES = {
        {"2538", null, "\u2014"},
        {"2538", "NaN", "\u2014"},
        {"2538", "Infinity", "\u2014"},
        {"2538", "-Infinity", "\u2014"},
        {"2538", "0.0", "0 (raw)"},
        {"2538", "1.0", "1 (raw)"},
        {"2538", "2.0", "ECO"},
        {"2538", "3.0", "SPORT"},
        {"2538", "4.0", "4 (raw)"},
        {"2538", "5.0", "5 (raw)"},
        {"2538", "11.0", "WALK"},
        {"2538", "2.5", "2.5 (raw)"},
        {"2538", "-1.0", "-1 (raw)"},
        {"2538", "-0.0", "-0 (raw)"},
        {"2538", "255.0", "255 (raw)"},
        {"2538", "1.0E20", "100000000000000000000 (raw)"},
        {"25462", null, "\u2014"},
        {"25462", "NaN", "\u2014"},
        {"25462", "Infinity", "\u2014"},
        {"25462", "-Infinity", "\u2014"},
        {"25462", "0.0", "0 (raw)"},
        {"25462", "1.0", "1 (raw)"},
        {"25462", "2.0", "DRIVE"},
        {"25462", "3.0", "SPORT"},
        {"25462", "4.0", "4 (raw)"},
        {"25462", "5.0", "SPORT"},
        {"25462", "11.0", "WALK"},
        {"25462", "2.5", "2.5 (raw)"},
        {"25462", "-1.0", "-1 (raw)"},
        {"25462", "-0.0", "-0 (raw)"},
        {"25462", "255.0", "255 (raw)"},
        {"25462", "1.0E20", "100000000000000000000 (raw)"},
        {"22131", null, "\u2014"},
        {"22131", "NaN", "\u2014"},
        {"22131", "Infinity", "\u2014"},
        {"22131", "-Infinity", "\u2014"},
        {"22131", "0.0", "0 (raw)"},
        {"22131", "1.0", "1 (raw)"},
        {"22131", "2.0", "2 (raw)"},
        {"22131", "3.0", "3 (raw)"},
        {"22131", "4.0", "4 (raw)"},
        {"22131", "5.0", "5 (raw)"},
        {"22131", "11.0", "11 (raw)"},
        {"22131", "2.5", "2.5 (raw)"},
        {"22131", "-1.0", "-1 (raw)"},
        {"22131", "-0.0", "-0 (raw)"},
        {"22131", "255.0", "255 (raw)"},
        {"22131", "1.0E20", "100000000000000000000 (raw)"},
        {"0", null, "\u2014"},
        {"0", "NaN", "\u2014"},
        {"0", "Infinity", "\u2014"},
        {"0", "-Infinity", "\u2014"},
        {"0", "0.0", "0 (raw)"},
        {"0", "1.0", "1 (raw)"},
        {"0", "2.0", "2 (raw)"},
        {"0", "3.0", "3 (raw)"},
        {"0", "4.0", "4 (raw)"},
        {"0", "5.0", "5 (raw)"},
        {"0", "11.0", "11 (raw)"},
        {"0", "2.5", "2.5 (raw)"},
        {"0", "-1.0", "-1 (raw)"},
        {"0", "-0.0", "-0 (raw)"},
        {"0", "255.0", "255 (raw)"},
        {"0", "1.0E20", "100000000000000000000 (raw)"},
        {"-1", null, "\u2014"},
        {"-1", "NaN", "\u2014"},
        {"-1", "Infinity", "\u2014"},
        {"-1", "-Infinity", "\u2014"},
        {"-1", "0.0", "0 (raw)"},
        {"-1", "1.0", "1 (raw)"},
        {"-1", "2.0", "2 (raw)"},
        {"-1", "3.0", "3 (raw)"},
        {"-1", "4.0", "4 (raw)"},
        {"-1", "5.0", "5 (raw)"},
        {"-1", "11.0", "11 (raw)"},
        {"-1", "2.5", "2.5 (raw)"},
        {"-1", "-1.0", "-1 (raw)"},
        {"-1", "-0.0", "-0 (raw)"},
        {"-1", "255.0", "255 (raw)"},
        {"-1", "1.0E20", "100000000000000000000 (raw)"},
    };

    static final String[][] DISPLAY = {
        {"display_units", "Imperial (0)", "0.0", "Imperial"},
        {"display_units", "Metric (1)", "1.0", "Metric"},
        {"display_units", "4 (raw)", "4.0", "4"},
        {"warning_code", "None (0)", "0.0", "None"},
        {"warning_code", "Code 14", "14.0", "Code 14"},
        {"battery_status", "Normal (0)", "0.0", "Normal"},
        {"battery_status", "Abnormal; code 4", "4.0", "Abnormal"},
        {"lock_status", "Unlocked (0)", "0.0", "Unlocked"},
        {"lock_status", "Locked (1)", "1.0", "Locked"},
        {"lock_status", "3 (raw)", "3.0", "3"},
        {"remaining_range_km", "12 km (estimate)", "12.0", "12 km"},
        {"remaining_range_km", "0 km (estimate)", "0.0", "0 km"},
        {"ride_report_format", "v1: tenths; optional 32-bit odometer", "1.0", "v1"},
        {"ride_report_format", "v0: whole units", "0.0", "v0"},
        {"ride_read_raw", "Acknowledged; ride data arrives separately", null, "Acknowledged"},
        {"ride_read_raw", "0102", null, "0102"},
        {"stored_trips", "No stored trips reported", "0.0", "No stored trips reported"},
        {"stored_trips", "Trip 1: 1 km", "1.0", "1 trip"},
        {"stored_trips", "Trip 1: 1 km\nTrip 2: 2 km\nTrip 3: 3 km", "3.0", "3 trips"},
        {"stored_trips", "Trip 1: 1 km", "0.5", "Trip 1: 1 km"},
        {"stored_trips", "Trip 1: 1 km", "1.5", "2 trips"},
        {"stored_trips", "Trip 1: 1 km", "21.0", "21 trips"},
        {"stored_trips", "Unknown history format 7; 3 records (raw bytes available in export)", null, "Unknown format 7 \u00b7 3 records"},
        {"stored_trips", "Unknown history format 7; 3", null, "Unknown history format 7; 3"},
        {"fault_history_raw", "Empty response", null, "Empty response"},
        {"fault_history_raw", "0a0b", null, "0a0b"},
        {"scooter_serial", "Empty serial value", null, "Empty serial value"},
        {"response_70", "Missing response status", null, "Missing response status"},
        {"response_72", "OK; 34 data bytes", null, "OK; 34 data bytes"},
        {"response_90", "Scooter returned status 2", null, "Scooter returned status 2"},
        {"battery_percent", "75 %", "75.0", "75 %"},
        {"battery_percent", "100 %", "100.0", "100 %"},
        {"battery_health_percent", "98 %", "98.0", "98 %"},
        {"charge_limit_percent", "80 %", "80.0", "80 %"},
        {"voltage_v", "36.500 V", "36.5", "36.500 V"},
        {"voltage_v", "0.000 V", "0.0", "0.000 V"},
        {"current_a", "-1.250 A", "-1.25", "-1.250 A"},
        {"current_a", "0.000 A", "0.0", "0.000 A"},
        {"power_w", "-45.6 W", "-45.6", "-45.6 W"},
        {"temperature_c", "-5 \u00b0C", "-5.0", "-5 \u00b0C"},
        {"temperature_c", "25 \u00b0C", "25.0", "25 \u00b0C"},
        {"energy_throughput_wh", "1234 Wh", "1234.0", "1234 Wh"},
        {"capacity_throughput_ah", "56 Ah", "56.0", "56 Ah"},
        {"rated_capacity_ah", "12.34 Ah", "12.34", "12.34 Ah"},
        {"health_adjusted_capacity_ah", "12.09 Ah", "12.0932", "12.09 Ah"},
        {"charge_cycles", "12 charges", "12.0", "12 charges"},
        {"charge_cycles", "1 charges", "1.0", "1 charges"},
        {"charge_cycles", "0 charges", "0.0", "0 charges"},
        {"deep_discharge", "3", "3.0", "3"},
        {"speed_kmh", "25.3 km/h", "25.3", "25.3 km/h"},
        {"speed_kmh", "25 km/h", "25.0", "25 km/h"},
        {"maximum_speed_kmh", "31.0 km/h", "31.0", "31.0 km/h"},
        {"average_speed_kmh", "14.2 km/h", "14.2", "14.2 km/h"},
        {"trip_km", "12.5 km", "12.5", "12.5 km"},
        {"trip_km", "12 km", "12.0", "12 km"},
        {"odometer_km", "1234.5 km", "1234.5", "1234.5 km"},
        {"trip_minutes", "30 min", "30.0", "30 min"},
        {"battery_manufactured", "2025-03-01", null, "2025-03-01"},
        {"battery_manufactured", "Year 25, month 13, day 1 (raw)", null, "Year 25, month 13, day 1"},
        {"high_temperature_duration", "0 y 0 mo 0 d 2 h 15 min 0 s", null, "0 y 0 mo 0 d 2 h 15 min 0 s"},
        {"meter_version", "1.2.3.4", null, "1.2.3.4"},
        {"meter_version", "00010203 (raw)", null, "00010203"},
        {"cruise_control", "1 (raw)", "1.0", "1"},
        {"driving_mode", "SPORT", "3.0", "SPORT"},
        {"driving_mode", "3 (raw)", "3.0", "3"},
        {"self_discharge_raw", "7 (raw)", "7.0", "7"},
        {"voltage_v", "36.5 V", "36.5", "36.5 V"},
        {"voltage_v", "36.500 V", "36.4", "36.500 V"},
        {"battery_percent", "75 %", null, "75 %"},
        {"speed_kmh", "25.3 km/h", null, "25.3 km/h"},
        {"remaining_range_km", "12 km (estimate)", null, "12 km"},
        {null, "36.500 V", "36.5", "36.500 V"},
        {"voltage_v", null, "36.5", null},
        {"unknown_key", "36.500 V", "36.5", "36.500 V"},
    };

    static final String[][] RIDE_SERIES = {
        {"false", "power_w", "Power", "W", "-0.06", "1.06"},
        {"false", "speed_kmh", "Wheel speed", "km/h", "-0.06", "1.06"},
        {"false", "gps_speed", "GPS speed", "km/h", "-0.06", "1.06"},
        {"false", "voltage_v", "Voltage", "V", "-0.06", "1.06"},
        {"false", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"false", "elevation", "Elevation", "m", "-0.06", "1.06"},
        {"false", "gps_accel", "GPS acceleration", "m/s\u00b2", "-0.06", "1.06"},
        {"false", "wh_per_distance", "Energy / distance", "Wh/km", "-0.06", "1.06"},
        {"true", "power_w", "Power", "W", "-0.06", "1.06"},
        {"true", "speed_kmh", "Wheel speed", "mph", "-0.06", "1.06"},
        {"true", "gps_speed", "GPS speed", "mph", "-0.06", "1.06"},
        {"true", "voltage_v", "Voltage", "V", "-0.06", "1.06"},
        {"true", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"true", "elevation", "Elevation", "ft", "-0.06", "1.06"},
        {"true", "gps_accel", "GPS acceleration", "m/s\u00b2", "-0.06", "1.06"},
        {"true", "wh_per_distance", "Energy / distance", "Wh/mi", "-0.06", "1.06"},
    };

    static final String[][] CHARGE_SERIES = {
        {"0C", "power_w", "Power", "W", "-125.84", "99.84"},
        {"0C", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"0C", "estimated_finish", "Est. finish time", "Time", "-7200.0", "67200.0"},
        {"0C", "estimated_countdown", "Est. countdown time", "min", "0.0", "1.5"},
        {"0C", "voltage_v", "Voltage", "V", "51.938", "52.062"},
        {"0C", "current_a", "Current", "A", "-2.42", "1.92"},
        {"0C", "temperature_c", "Temperature", "\u00b0C", "19.38", "20.62"},
        {"0F", "power_w", "Power", "W", "-125.84", "99.84"},
        {"0F", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"0F", "estimated_finish", "Est. finish time", "Time", "-7200.0", "67200.0"},
        {"0F", "estimated_countdown", "Est. countdown time", "min", "0.0", "1.5"},
        {"0F", "voltage_v", "Voltage", "V", "51.938", "52.062"},
        {"0F", "current_a", "Current", "A", "-2.42", "1.92"},
        {"0F", "temperature_c", "Temperature", "\u00b0F", "67.38", "68.62"},
        {"1C", "power_w", "Power", "W", "-116.48", "12.480000000000004"},
        {"1C", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"1C", "estimated_finish", "Est. finish time", "Time", "1.7999998896E12", "1.8000013404E12"},
        {"1C", "estimated_countdown", "Est. countdown time", "min", "0.0", "25.0"},
        {"1C", "voltage_v", "Voltage", "V", "51.938", "52.062"},
        {"1C", "current_a", "Current", "A", "-2.24", "0.24"},
        {"1C", "temperature_c", "Temperature", "\u00b0C", "19.76", "22.24"},
        {"1C", "limit_finish", "Est. finish at limit", "Time", "1.8000000536E12", "1.8000003264E12"},
        {"1C", "limit_countdown", "Est. countdown to limit", "min", "0.0", "6.0"},
        {"1C", "charge_limit", "Charge limit", "%", "0.0", "100.0"},
        {"1C", "observed_finish", "Observed finish", "Time", "1.7999998896E12", "1.8000013404E12"},
        {"1C", "observed_countdown", "Actual countdown", "min", "0.0", "25.0"},
        {"1F", "power_w", "Power", "W", "-116.48", "12.480000000000004"},
        {"1F", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"1F", "estimated_finish", "Est. finish time", "Time", "1.7999998896E12", "1.8000013404E12"},
        {"1F", "estimated_countdown", "Est. countdown time", "min", "0.0", "25.0"},
        {"1F", "voltage_v", "Voltage", "V", "51.938", "52.062"},
        {"1F", "current_a", "Current", "A", "-2.24", "0.24"},
        {"1F", "temperature_c", "Temperature", "\u00b0F", "67.568", "72.032"},
        {"1F", "limit_finish", "Est. finish at limit", "Time", "1.8000000536E12", "1.8000003264E12"},
        {"1F", "limit_countdown", "Est. countdown to limit", "min", "0.0", "6.0"},
        {"1F", "charge_limit", "Charge limit", "%", "0.0", "100.0"},
        {"1F", "observed_finish", "Observed finish", "Time", "1.7999998896E12", "1.8000013404E12"},
        {"1F", "observed_countdown", "Actual countdown", "min", "0.0", "25.0"},
        {"2C", "power_w", "Power", "W", "-105.28960000000001", "-102.71039999999999"},
        {"2C", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"2C", "estimated_finish", "Est. finish time", "Time", "1.8000001478E12", "1.8000002222E12"},
        {"2C", "estimated_countdown", "Est. countdown time", "min", "0.0", "4.0"},
        {"2C", "voltage_v", "Voltage", "V", "51.938", "52.062"},
        {"2C", "current_a", "Current", "A", "-2.62", "-1.38"},
        {"2C", "temperature_c", "Temperature", "\u00b0C", "19.38", "20.62"},
        {"2F", "power_w", "Power", "W", "-105.28960000000001", "-102.71039999999999"},
        {"2F", "battery_percent", "Battery %", "%", "0.0", "100.0"},
        {"2F", "estimated_finish", "Est. finish time", "Time", "1.8000001478E12", "1.8000002222E12"},
        {"2F", "estimated_countdown", "Est. countdown time", "min", "0.0", "4.0"},
        {"2F", "voltage_v", "Voltage", "V", "51.938", "52.062"},
        {"2F", "current_a", "Current", "A", "-2.62", "-1.38"},
        {"2F", "temperature_c", "Temperature", "\u00b0F", "67.38", "68.62"},
    };

    static final String[][] METRIC_TEXT = {
        {"Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "km", "Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h"},
        {"Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "mi", "Trip 1: 7.77 mi; 30 min; avg 9.32 mph; max 15.53 mph"},
        {"Trip 1: 12.5 km; 30 min; avg 15.0 km/h; max 25.0 km/h", "dual", "Trip 1: 7.77 mi / 12.5 km; 30 min; avg 9.32 mph / 15.0 km/h; max 15.53 mph / 25.0 km/h"},
        {"Trip 1: 12 km; 30 min; avg 15 km/h; max 25 km/h\nTrip 2: 3 km; 9 min; avg 20 km/h; max 28 km/h", "dual", "Trip 1: 7.46 mi / 12 km; 30 min; avg 9.32 mph / 15 km/h; max 15.53 mph / 25 km/h\nTrip 2: 1.86 mi / 3 km; 9 min; avg 12.43 mph / 20 km/h; max 17.40 mph / 28 km/h"},
        {"12.50 km", "km", "12.50 km"},
        {"12.50 km", "mi", "7.77 mi"},
        {"12.50 km", "dual", "7.77 mi / 12.50 km"},
        {"0.00 km", "dual", "0.00 mi / 0.00 km"},
        {"012.5 km", "dual", "7.77 mi / 012.5 km"},
        {"12.500000000000000001 km", "dual", "7.77 mi / 12.500000000000000001 km"},
        {"99999999999999999999 km", "dual", "62137119223733395000.00 mi / 99999999999999999999 km"},
        {"1.25 km/h", "dual", "0.78 mph / 1.25 km/h"},
        {"36.500 V", "dual", "36.500 V"},
        {"No stored trips reported", "dual", "No stored trips reported"},
        {"12 kmh", "dual", "12 kmh"},
        {"12 km/hx", "dual", "7.46 mi / 12 km/hx"},
        {"x12 km", "dual", "x7.46 mi / 12 km"},
        {"12.5 km", null, "7.77 mi"},
        {"12.5 km", "other", "7.77 mi"},
        {null, "dual", null},
        {"", "dual", ""},
    };
}
