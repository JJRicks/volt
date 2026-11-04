package com.jjricks.volt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * G5 characterization for B16 (Live Stats and charge history), committed before the batch changes
 * anything.
 *
 * StatsScreen, StatsCharts and ChargeHistoryDialog need Android, so a plain JVM test cannot call them;
 * G2 (LedgerTest) proves each new template renders the original concatenation, and G7's census covers
 * the screens. What this pins is the English and the pure arithmetic and formatting those screens do
 * today, which the batch moves onto the catalog and Fmt:
 *   - Live Stats row names: ScooterReadingLabels.label with " (raw)" removed, for every reading in
 *     Telemetry's catalog (the chip says "raw" instead), and which readings carry the marker;
 *   - the reading groups and Live Stats section ids, which stay the stored ids;
 *   - StatsScreen's converted speed and distance ("%.2f %s" with Locale.US) and its range headline's
 *     whole numbers, against Fmt.decimal and Fmt.integer;
 *   - StatsCharts' latest values ("%.0f" or "%.1f" with Locale.US) against Fmt.decimal;
 *   - ChargeHistoryDialog's phone-locale numbers ("%.0f%% → %.0f%%", "%.1e", "%.<n>f",
 *     " · %.0f%% target"), its paused-time durations and its "MMM d" date, against Fmt.locale,
 *     Fmt.decimal, Fmt.integer and Fmt.datePattern.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR.
 */
public final class StatsTextTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        TimeZone savedZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                rowNames(phone);
                groups(phone);
                statsNumbers(phone);
                chartNumbers(phone);
                chargeHistory(phone);
            }
        } finally {
            Locale.setDefault(saved);
            TimeZone.setDefault(savedZone);
        }
        System.out.println("StatsTextTest: " + checks + " checks passed (Live Stats row names, numbers and charge "
                + "history formatting under 5 phone locales)");
    }

    /** Every reading whose short name carries " (raw)", with the row name StatsScreen shows for it. */
    static final String[][] RAW_ROWS = {
        {"charging_state", "Charging"}, {"self_discharge_raw", "Self-discharge"},
        {"extreme_temperature_charge_raw", "Hot/cold charge time"}, {"temperature_sign_state", "Temp. sign"},
        {"current_sign_state", "Current sign"}, {"driving_status", "Ride status"},
        {"binding_status", "Account binding"}, {"cruise_control", "Cruise"}, {"tail_light", "Tail light"},
        {"energy_recovery", "Recovery"}, {"range_algorithm", "Range algorithm"}, {"auto_sensor", "Auto light"},
        {"tyre_reminder", "Tyre reminder"}, {"ambient_light", "Line pattern"}, {"traction_control", "Traction"},
        {"turn_sound", "Turn sound"}, {"proximity_key", "Proximity key"}, {"night_mode", "Night mode"},
        {"range_algorithm_mode", "Range mode"}, {"drive_light", "Star pattern"}, {"start_speed", "Start speed"},
        {"speed_limit", "Speed limit"}, {"volume", "Audio"}, {"voice_language", "Voice language"},
        {"logo_light", "Logo light"}, {"daytime_light", "Daytime light"},
        {"maximum_speed_setting", "Max. speed setting"}, {"drive_mode_setting", "Mode setting"},
        {"settings_mode_raw", "Settings mode byte"}, {"scheduled_charge_time_raw", "Charge schedule"},
        {"low_power", "Low-battery mode"}, {"timed_charging", "Timed charging"}, {"auto_lock_time", "Lock duration"},
        {"speed_limit_eligibility", "Speed eligibility"}, {"weather", "Weather"}, {"slope_assist", "Hill assist"},
        {"long_range_mode", "Long range"}, {"overspeed_control", "Overspeed control"},
        {"overspeed_alert", "Overspeed alert"}, {"drive_speed_limit", "Drive speed limit"},
        {"battery_serial_raw", "Battery serial"}, {"lock_push_warning", "Lock movement"}, {"hide_e9", "E9 flag"},
        {"ride_read_raw", "Ride read"}, {"fault_history_raw", "Fault history"},
    };

    private static void rowNames(Locale phone) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (String[] row : RAW_ROWS) raw.put(row[0], row[1]);
        int seen = 0;
        for (Telemetry.Metric metric : new Telemetry().metrics()) {
            String label = ScooterReadingLabels.label(metric.key, metric.label);
            String row = label.replace(" (raw)", "");
            if (raw.containsKey(metric.key)) {
                seen++;
                equal(label, row + " (raw)", phone, metric.key + " short name ends with the raw marker");
                equal(raw.get(metric.key), row, phone, metric.key + " Live Stats row name");
            } else {
                equal(label, row, phone, metric.key + " short name has no raw marker");
            }
        }
        equal(String.valueOf(RAW_ROWS.length), String.valueOf(seen), phone, "raw-marked readings");
        // A reading outside the catalog keeps its own fallback, marker removed the same way.
        equal("Response 0x7A", ScooterReadingLabels.label("response_7a", "Response 0x7A (raw)").replace(" (raw)", ""),
                phone, "fallback row name");
    }

    private static void groups(Locale phone) {
        String[] ids = {Telemetry.GROUP_BATTERY, Telemetry.GROUP_RIDE, Telemetry.GROUP_SETTINGS,
                Telemetry.GROUP_DEVICE, Telemetry.GROUP_DIAGNOSTICS};
        String[] english = {"Battery", "Ride", "Settings", "Device", "Diagnostics"};
        for (int i = 0; i < ids.length; i++) {
            equal(english[i], ids[i], phone, "group id " + english[i]);
            equal(english[i], ScooterReadingLabels.group(ids[i]), phone, "group title " + english[i]);
        }
        for (Telemetry.Metric metric : new Telemetry().metrics()) {
            boolean known = false;
            for (String id : ids) known |= id.equals(metric.group);
            equal("true", String.valueOf(known), phone, metric.key + " is in a known group");
        }
    }

    static final double[] NUMBERS = {0, -0.0, 0.004, 0.005, 0.015, 0.025, 0.05, 0.45, 0.5, 1.005, 1.25, 1.35, 2.5,
        9.95, 12.345, 25.3, 99.95, 999.94, 999.95, 1000, 1000.5, 1234.5, 1e6, -3.25, -0.05, -999.96, -1000.5,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};

    private static void statsNumbers(Locale phone) {
        for (double km : NUMBERS) {
            double miles = km / RideStore.KM_PER_MILE;
            for (boolean speed : new boolean[]{true, false}) {
                String unit = speed ? "mph" : "mi";
                equal(String.format(Locale.US, "%.2f %s", miles, unit), Fmt.decimal(Locale.US, miles, 2) + " " + unit,
                        phone, "Live Stats converted value " + km);
            }
            if (Double.isNaN(km) || Double.isInfinite(km)) continue;
            long wholeKm = Math.round(km), wholeMiles = Math.round(km / RideStore.KM_PER_MILE);
            equal(wholeKm + " km", Fmt.integer(wholeKm) + " km", phone, "range km " + km);
            equal(wholeMiles + " mi / " + wholeKm + " km", Fmt.integer(wholeMiles) + " mi / " + Fmt.integer(wholeKm) + " km",
                    phone, "range dual " + km);
            equal(wholeMiles + " mi", Fmt.integer(wholeMiles) + " mi", phone, "range mi " + km);
        }
        equal("≈ 12 km left", "≈ " + 12L + " km" + " left", phone, "range headline");
    }

    private static void chartNumbers(Locale phone) {
        for (double n : NUMBERS) {
            String legacy = String.format(Locale.US, Math.abs(n) >= 1000 ? "%.0f" : "%.1f", n);
            equal(legacy, Fmt.decimal(Locale.US, n, Math.abs(n) >= 1000 ? 0 : 1), phone, "chart latest value " + n);
        }
    }

    private static void chargeHistory(Locale phone) {
        same(Locale.getDefault(), Fmt.locale(Locale.getDefault()), phone, "charge history keeps the phone's locale");
        double[] percents = {0, 0.5, 1.5, 2.5, 49.5, 50.5, 99.4, 99.5, 100, -0.4};
        for (double first : percents) for (double last : percents) {
            equal(String.format(Locale.getDefault(), "%.0f%% → %.0f%%", first, last),
                    Fmt.decimal(Locale.getDefault(), first, 0) + "% → " + Fmt.decimal(Locale.getDefault(), last, 0) + "%",
                    phone, "battery span " + first + " " + last);
        }
        for (double target : percents) {
            equal(String.format(Locale.getDefault(), " · %.0f%% target", target),
                    " · " + Fmt.decimal(Locale.getDefault(), target, 0) + "% target", phone, "target " + target);
        }
        for (double n : NUMBERS) {
            equal(String.format(Locale.getDefault(), "%.1e", n), String.format(Fmt.locale(Locale.getDefault()), "%.1e", n),
                    phone, "axis exponent " + n);
            for (int places = 0; places <= 6; places++) {
                equal(String.format(Locale.getDefault(), "%." + places + "f", n), Fmt.decimal(Locale.getDefault(), n, places),
                        phone, "axis " + places + " places " + n);
            }
        }
        long[] durations = {0, 29_999, 30_000, 59_999, 60_000, 89_999, 90_000, 3_569_999, 3_570_000, 3_600_000,
            3_629_999, 3_630_000, 7_200_000, 86_400_000, 90_061_000, -60_000};
        String[] expected = {"0 min", "0 min", "1 min", "1 min", "1 min", "1 min", "2 min", "59 min", "1 h 0 min",
            "1 h 0 min", "1 h 0 min", "1 h 1 min", "2 h 0 min", "24 h 0 min", "25 h 1 min", "-1 min"};
        for (int i = 0; i < durations.length; i++) {
            long minutes = Math.round(durations[i] / 60000d);
            String legacy = minutes >= 60 ? minutes / 60 + " h " + minutes % 60 + " min" : minutes + " min";
            equal(expected[i], legacy, phone, "paused duration " + durations[i]);
            equal(legacy, minutes >= 60 ? Fmt.integer(minutes / 60) + " h " + Fmt.integer(minutes % 60) + " min"
                    : Fmt.integer(minutes) + " min", phone, "paused duration parts " + durations[i]);
        }
        for (int pauses : new int[]{1, 2, 3, 11, 21}) {
            equal(pauses == 1 ? "once" : pauses + " times", pauses == 1 ? "once" : Fmt.integer(pauses) + " times", phone,
                    "charger pauses " + pauses);
        }
        java.util.function.BiFunction<Locale, String, String> never = (locale, skeleton) -> {
            throw new AssertionError("an English charge history asked for a best pattern");
        };
        for (long wall : new long[]{0L, 1700000000000L, 1767225599000L, 1751350245000L}) {
            equal(new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(wall)),
                    new SimpleDateFormat(Fmt.datePattern("MMM d", "MMMd", never), Fmt.locale(Locale.getDefault()))
                            .format(new Date(wall)), phone, "chart day " + wall);
        }
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(what + " (phone " + phone.toLanguageTag() + "): expected [" + expected + "] got [" + actual + "]");
    }

    private static void same(Object expected, Object actual, Locale phone, String what) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(what + " (phone " + phone.toLanguageTag() + "): expected " + expected + " got " + actual);
    }
}
