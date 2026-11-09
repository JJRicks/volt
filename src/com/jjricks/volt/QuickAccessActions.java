package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Stable, named dashboard shortcuts. IDs never contain commands or arbitrary navigation targets. */
public final class QuickAccessActions {
    public static final int MIN_ITEMS = 2, MAX_ITEMS = 8;
    /** A scooter control's shortcut id is this prefix plus its ScooterCommands.Feature key (data, never shown). */
    public static final String FEATURE_PREFIX = "feature:";
    public static final class Entry {
        /** label and group stay the English (search, older screens); label(), shortLabel() and groupLabel() are shown. */
        public final String id, label, icon, group, featureKey;
        private final Msg labelMsg, groupMsg;
        private Entry(String id, Msg label, String icon, Msg group, String featureKey) {
            this.id = id; this.labelMsg = label; this.label = label.english(); this.icon = icon;
            this.groupMsg = group; this.group = group.english();
            this.featureKey = featureKey;
        }
        /** The full name in the current language: Quick access buttons, tooltips, screen readers, the picker. */
        public Msg label() { return labelMsg; }
        /** The group caption in the current language (the shortcut picker). */
        public Msg groupLabel() { return groupMsg; }
        /**
         * The short rung of the name (the narrow-label ladder), for a Setting tile's one-line label. A control's
         * shortcut takes the control's own short rung ({@link #featureShort}) or, without one, the control's name.
         * In English a Volt shortcut's two rungs are the same text.
         */
        public Msg shortLabel() {
            switch (labelMsg.id()) {
                case T.DASHBOARD_MODEL_QA_LIGHTS: return M.dashboardModelQaLightsShort();
                case T.DASHBOARD_MODEL_QA_SETTINGS: return M.dashboardModelQaSettingsShort();
                case T.DASHBOARD_MODEL_QA_LIVE_STATS: return M.dashboardModelQaLiveStatsShort();
                case T.DASHBOARD_MODEL_QA_RIDES: return M.dashboardModelQaRidesShort();
                case T.DASHBOARD_MODEL_QA_RIDE_SETTINGS: return M.dashboardModelQaRideSettingsShort();
                case T.DASHBOARD_MODEL_QA_BATTERY_SETTINGS: return M.dashboardModelQaBatterySettingsShort();
                case T.DASHBOARD_MODEL_QA_LINE_LIGHTS: return M.dashboardModelQaLineLightsShort();
                case T.DASHBOARD_MODEL_QA_STAR_LIGHTS: return M.dashboardModelQaStarLightsShort();
                case T.DASHBOARD_MODEL_QA_DASHBOARD_SOUND: return M.dashboardModelQaDashboardSoundShort();
                case T.DASHBOARD_MODEL_QA_PIN_PROXIMITY: return M.dashboardModelQaPinProximityShort();
                case T.DASHBOARD_MODEL_QA_SCOOTER_NAME: return M.dashboardModelQaScooterNameShort();
                case T.DASHBOARD_MODEL_QA_APP_PREFERENCES: return M.dashboardModelQaAppPreferencesShort();
                case T.DASHBOARD_MODEL_QA_MY_SCOOTERS: return M.dashboardModelQaMyScootersShort();
                case T.DASHBOARD_MODEL_QA_EXPERIMENTAL: return M.dashboardModelQaExperimentalShort();
                case T.DASHBOARD_MODEL_QA_DATA_HISTORY: return M.dashboardModelQaDataHistoryShort();
                case T.DASHBOARD_MODEL_QA_ABOUT_PERMISSIONS: return M.dashboardModelQaAboutPermissionsShort();
                case T.DASHBOARD_MODEL_QA_CHARGE_DONE_BY: return M.dashboardModelQaChargeDoneByShort();
                case T.DASHBOARD_MODEL_QA_CHARGE_DONE_TIME: return M.dashboardModelQaChargeDoneTimeShort();
                default: break;
            }
            Msg rung = featureKey == null ? null : featureShort(featureKey);
            return rung == null ? labelMsg : rung;
        }
        /** A scooter control's short tile rung by its feature key (data), or null where the name is short enough in every language so far. */
        private static Msg featureShort(String key) {
            switch (key) {
                case "lock": return M.controlsFeatureLockShort();
                case "cruise_control": return M.controlsFeatureCruiseControlShort();
                case "energy_recovery": return M.controlsFeatureEnergyRecoveryShort();
                case "acceleration": return M.controlsFeatureAccelerationShort();
                case "auto_sensor": return M.controlsFeatureAutoSensorShort();
                case "traction_control": return M.controlsFeatureTractionControlShort();
                case "turn_sound": return M.controlsFeatureTurnSoundShort();
                case "display_units": return M.controlsFeatureDisplayUnitsShort();
                case "range_algorithm": return M.controlsFeatureRangeAlgorithmShort();
                case "start_speed": return M.controlsFeatureStartSpeedShort();
                case "speed_limit": return M.controlsFeatureSpeedLimitShort();
                case "dual_drive": return M.controlsFeatureDualDriveShort();
                case "drive_speed_limit": return M.controlsFeatureDriveSpeedLimitShort();
                case "charge_start": return M.controlsFeatureChargeStartShort();
                case "lock_expiry": return M.controlsFeatureLockExpiryShort();
                case "low_power": return M.controlsFeatureLowPowerShort();
                case "long_range_mode": return M.controlsFeatureLongRangeModeShort();
                case "hill_start_assist": return M.controlsFeatureHillStartAssistShort();
                case "parking_assist": return M.controlsFeatureParkingAssistShort();
                case "hill_descent_control": return M.controlsFeatureHillDescentControlShort();
                case "overspeed_control": return M.controlsFeatureOverspeedControlShort();
                case "proximity_key": return M.controlsFeatureProximityKeyShort();
                case "line_pattern": return M.controlsFeatureLightPatternShort(M.controlsFeatureLineLightsShort());
                case "star_pattern": return M.controlsFeatureLightPatternShort(M.controlsFeatureStarLightsShort());
                case "line_color": return M.controlsFeatureLightColorShort(M.controlsFeatureLineLightsShort());
                case "star_color": return M.controlsFeatureLightColorShort(M.controlsFeatureStarLightsShort());
                case "line_boost_color": return M.controlsFeatureLightBoostColorShort(M.controlsFeatureLineLightsShort());
                case "star_boost_color": return M.controlsFeatureLightBoostColorShort(M.controlsFeatureStarLightsShort());
                default: return null;
            }
        }
        public ScooterCommands.Feature feature() {
            return featureKey == null ? null : ScooterCommands.find(featureKey);
        }
        /** Sensitive and experimental controls retain their explained, guarded panels. */
        public boolean directToggle() {
            if (id.equals("charge_done_by")) return true;
            ScooterCommands.Feature f = feature();
            return f != null && f.type == ScooterCommands.Type.TOGGLE && !f.experimental
                    && !f.sensitive && !f.key.equals("proximity_key");
        }
        /**
         * The connected model's decision: on another model's mapping a non-Lab on/off control toggles
         * directly even where the UT5 MAX keeps it in Lab. The UT5 MAX and unmapped controls keep directToggle().
         */
        public boolean directToggle(CapabilityResolver.Decision d) {
            ScooterCommands.Feature f = feature();
            if (f == null || d == null || d.support != CapabilityResolver.Support.KNOWN_ENCODING || d.variant == null
                    || d.variant.decode == FeatureVariant.Decode.UT5_FEATURE) return directToggle();
            return f.type == ScooterCommands.Type.TOGGLE && !d.variant.lab && !f.sensitive
                    && !f.key.equals("proximity_key") && Arrays.equals(d.variant.values, new int[]{0, 1});
        }
    }
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();
    static {
        add("lights", M.dashboardModelQaLights(), "lights", M.dashboardModelQaGroupMenus(), null);
        add("controls", M.dashboardModelQaSettings(), "settings", M.dashboardModelQaGroupMenus(), null);
        add("stats", M.dashboardModelQaLiveStats(), "stats", M.dashboardModelQaGroupPages(), null);
        add("rides", M.dashboardModelQaRides(), "rides", M.dashboardModelQaGroupPages(), null);
        add("section:Ride", M.dashboardModelQaRideSettings(), "scooter", M.dashboardModelQaGroupMenus(), null);
        add("section:Battery", M.dashboardModelQaBatterySettings(), "battery", M.dashboardModelQaGroupMenus(), null);
        add("section:Line lights", M.dashboardModelQaLineLights(), "lights", M.dashboardModelQaGroupMenus(), null);
        add("section:Star lights", M.dashboardModelQaStarLights(), "star", M.dashboardModelQaGroupMenus(), null);
        add("section:Dashboard & sound", M.dashboardModelQaDashboardSound(), "sound", M.dashboardModelQaGroupMenus(), null);
        add("section:Security", M.dashboardModelQaPinProximity(), "shield", M.dashboardModelQaGroupMenus(), null);
        add("section:Profile", M.dashboardModelQaScooterName(), "pencil", M.dashboardModelQaGroupMenus(), null);
        add("more:App", M.dashboardModelQaAppPreferences(), "settings", M.dashboardModelQaGroupMenus(), null);
        add("more:Scooters", M.dashboardModelQaMyScooters(), "scooter", M.dashboardModelQaGroupMenus(), null);
        add("more:Lab", M.dashboardModelQaExperimental(), "lab", M.dashboardModelQaGroupMenus(), null);
        add("more:Data", M.dashboardModelQaDataHistory(), "history", M.dashboardModelQaGroupMenus(), null);
        add("more:About", M.dashboardModelQaAboutPermissions(), "info", M.dashboardModelQaGroupMenus(), null);
        // Volt schedules a supported charge-start command; these are not new BLE opcodes.
        add("charge_done_by", M.dashboardModelQaChargeDoneBy(), "clock", M.controlsGroupBattery(), null);
        add("charge_done_time", M.dashboardModelQaChargeDoneTime(), "clock", M.controlsGroupBattery(), null);
        for (ScooterCommands.Feature feature : ScooterCommands.catalog())
            add(FEATURE_PREFIX + feature.key, feature.label(), icon(feature.key),
                    feature.experimental ? M.dashboardModelQaGroupExperimental() : feature.groupLabel(), feature.key);
    }
    private QuickAccessActions() {}
    private static void add(String id, Msg label, String icon, Msg group, String featureKey) {
        ENTRIES.put(id, new Entry(id, label, icon, group, featureKey));
    }
    public static List<Entry> catalog() {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES.values()));
    }
    public static Entry find(String id) { return ENTRIES.get(id); }
    public static List<String> defaultIds() {
        return Arrays.asList("lights", "controls", "stats", "rides");
    }
    /** Unknown/duplicate values cannot become executable actions. Missing legacy lists keep defaults. */
    public static ArrayList<String> normalize(List<String> ids) {
        return normalize(ids, MIN_ITEMS, MAX_ITEMS);
    }
    /** Same rules with a widget-specific count, e.g. one to four setting tiles. */
    public static ArrayList<String> normalize(List<String> ids, int minimum, int maximum) {
        int most = Math.max(1, Math.min(MAX_ITEMS, maximum));
        int least = Math.max(1, Math.min(most, minimum));
        ArrayList<String> result = new ArrayList<>();
        if (ids == null) result.addAll(defaultIds().subList(0, Math.min(most, defaultIds().size())));
        else for (String id : ids) {
            if (result.size() >= most) break;
            if (find(id) != null && !result.contains(id)) result.add(id);
        }
        for (String fallback : defaultIds()) {
            if (result.size() >= least) break;
            if (!result.contains(fallback)) result.add(fallback);
        }
        return result;
    }
    /** Mirrors the named UI icon family while keeping the catalog independent of Android. */
    private static String icon(String key) {
        if (key.equals("hill_start_assist")) return "walk";
        if (key.equals("charge_start") || key.equals("timed_charging")) return "clock";
        if (key.contains("speed") || key.equals("cruise_control")) return "speed";
        if (key.contains("charge") || key.equals("low_power") || key.equals("long_range_mode")) return "battery";
        if (key.equals("energy_recovery")) return "recovery";
        if (key.equals("headlight") || key.equals("auto_sensor") || key.startsWith("line_")) return "lights";
        if (key.startsWith("star_")) return "star";
        if (key.contains("lock") || key.equals("pin") || key.equals("proximity_key")) return "lock";
        if (key.contains("sound") || key.equals("prompt_tone")) return "sound";
        if (key.equals("traction_control")) return "shield";
        if (key.contains("assist") || key.contains("hill")) return "hill";
        if (key.contains("unit")) return "ruler";
        if (key.contains("range")) return "route";
        return "tune";
    }
}
