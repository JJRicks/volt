package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Volt settings the report diagnostics file names: an allow-list, never a dump of the preferences. Each listed key
 * is a switch, a number or a fixed choice; a text value that is not one of those shapes shows only as "(set)", so a
 * key that one day holds free text still cannot carry it out. An absent key is null: never changed, so Volt's
 * default applies. The selected scooter's settings are named without the scooter's id. Pure Java.
 */
final class DiagnosticsSettings {
    /** app_settings keys, as stored. Literals, so this stays pure; DiagnosticsBundleTest checks BackupRegistry has a rule for each one. */
    static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            // Connection and background
            "background_connection", "auto_connect", "poll_interval_ms", "history_enabled",
            // Rides
            "auto_record_rides", "auto_record_background", "riding_auto_open",
            "ride_auto_stop", "phone_gps", "phone_motion", "ride_auto_export_json",
            // Charging
            "charge_live_enabled", "charge_live_lock_detail",
            // Look and units
            "home_style", "home_style_chosen", "home_moment_charging", "home_moment_ride", "units", "temperature_unit",
            "time_format", "theme", "text_scale", "stats_density", "dashboard_columns", "app_language",
            "keep_screen_awake", "haptics_enabled", "map_basemap_enabled",
            // Riding mode
            "riding_night_vision", "riding_night_schedule", "riding_brightness", "riding_rotation",
            // Reports and updates
            "report_diagnostics", "update_auto_check"));
    /** Per-scooter keys (prefix + profile id), shown for the selected scooter under the prefix's name. */
    static final List<String> SCOOTER_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "charge_alerts_", "charge_charger_", "range_source_", "range_follow_mode_"));
    static final String SET = "(set)";
    /** A fixed choice ("km", "dark", "HIGH_SPEED"), or an encoded switch list like charge alerts' "charge=1;at=80". */
    private static final Pattern CHOICE = Pattern.compile("[A-Za-z0-9_.:-]{1,32}"),
            ENCODED = Pattern.compile("[a-z_]{1,16}=-?[0-9]{1,6}(;[a-z_]{1,16}=-?[0-9]{1,6}){0,11}");

    private DiagnosticsSettings() { }

    /** The settings section from the stored values (SharedPreferences.getAll()); only listed keys are read. */
    static Map<String, Object> section(Map<String, ?> stored, String profileId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> app = new LinkedHashMap<>();
        for (String key : KEYS) app.put(key, stored == null ? null : value(stored.get(key)));
        out.put("app", app);
        if (profileId == null || profileId.isEmpty()) out.put("selected_scooter", null);
        else {
            Map<String, Object> scooter = new LinkedHashMap<>();
            for (String prefix : SCOOTER_PREFIXES)
                scooter.put(prefix.substring(0, prefix.length() - 1), stored == null ? null : value(stored.get(prefix + profileId)));
            out.put("selected_scooter", scooter);
        }
        return out;
    }

    /** A switch or whole number as it is, a fraction to two places, a choice when it has a choice's shape, else "(set)" (any other type too). */
    static Object value(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? SET : String.format(Locale.ROOT, "%.2f", d);
        }
        if (v instanceof String) {
            String s = (String) v;
            // A choice's shape, and nothing a key, serial or address could be: DiagnosticsBundle's own rules for a reading.
            boolean shaped = CHOICE.matcher(s).matches() || ENCODED.matcher(s).matches();
            return shaped && DiagnosticsBundle.reading(s) && !DiagnosticsBundle.privateShape(s) ? s : SET;
        }
        return SET;
    }
}
