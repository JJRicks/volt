package com.jjricks.volt;

/**
 * Live Stats text that StatsScreen and StatsCharts (Android views) choose by id, kept free of Android so
 * the pure tests can check it: row names without the raw marker, the section and chart-window labels,
 * shown unit symbols and the shown-language search.
 */
final class StatsLabels {
    private StatsLabels() {}

    /**
     * The Live Stats row name of a reading whose short name carries "(raw)": the same name without it,
     * since the RAW tag beside an undecoded value says it. null for any other key, whose row name is its
     * short name.
     */
    static String row(String key) {
        switch (key) {
            case "charging_state": return T.statsRowChargingState();
            case "self_discharge_raw": return T.statsRowSelfDischargeRaw();
            case "extreme_temperature_charge_raw": return T.statsRowExtremeTemperatureChargeRaw();
            case "temperature_sign_state": return T.statsRowTemperatureSignState();
            case "current_sign_state": return T.statsRowCurrentSignState();
            case "driving_status": return T.statsRowDrivingStatus();
            case "binding_status": return T.statsRowBindingStatus();
            case "cruise_control": return T.statsRowCruiseControl();
            case "tail_light": return T.statsRowTailLight();
            case "energy_recovery": return T.statsRowEnergyRecovery();
            case "range_algorithm": return T.statsRowRangeAlgorithm();
            case "auto_sensor": return T.statsRowAutoSensor();
            case "tyre_reminder": return T.statsRowTyreReminder();
            case "ambient_light": return T.statsRowAmbientLight();
            case "traction_control": return T.statsRowTractionControl();
            case "turn_sound": return T.statsRowTurnSound();
            case "proximity_key": return T.statsRowProximityKey();
            case "night_mode": return T.statsRowNightMode();
            case "range_algorithm_mode": return T.statsRowRangeAlgorithmMode();
            case "drive_light": return T.statsRowDriveLight();
            case "start_speed": return T.statsRowStartSpeed();
            case "speed_limit": return T.statsRowSpeedLimit();
            case "volume": return T.statsRowVolume();
            case "voice_language": return T.statsRowVoiceLanguage();
            case "logo_light": return T.statsRowLogoLight();
            case "daytime_light": return T.statsRowDaytimeLight();
            case "maximum_speed_setting": return T.statsRowMaximumSpeedSetting();
            case "drive_mode_setting": return T.statsRowDriveModeSetting();
            case "settings_mode_raw": return T.statsRowSettingsModeRaw();
            case "scheduled_charge_time_raw": return T.statsRowScheduledChargeTimeRaw();
            case "low_power": return T.statsRowLowPower();
            case "timed_charging": return T.statsRowTimedCharging();
            case "auto_lock_time": return T.statsRowAutoLockTime();
            case "speed_limit_eligibility": return T.statsRowSpeedLimitEligibility();
            case "weather": return T.statsRowWeather();
            case "slope_assist": return T.statsRowSlopeAssist();
            case "long_range_mode": return T.statsRowLongRangeMode();
            case "overspeed_control": return T.statsRowOverspeedControl();
            case "overspeed_alert": return T.statsRowOverspeedAlert();
            case "drive_speed_limit": return T.statsRowDriveSpeedLimit();
            case "battery_serial_raw": return T.statsRowBatterySerialRaw();
            case "lock_push_warning": return T.statsRowLockPushWarning();
            case "hide_e9": return T.statsRowHideE9();
            case "ride_read_raw": return T.statsRowRideReadRaw();
            case "fault_history_raw": return T.statsRowFaultHistoryRaw();
            default: return null;
        }
    }

    /** Catalog ids of the section labels, in the order of the ids StatsScreen stores (rule R5). */
    private static final int[] SECTION_LABELS = {T.STATS_SECTION_OVERVIEW, T.STATS_SECTION_READINGS, T.STATS_SECTION_CHARTS,
        T.STATS_SECTION_DIAGNOSTICS};

    /** The shown labels of section ids, given the full id list they come from (StatsScreen.SECTIONS). */
    static String[] sections(String[] ids, String[] all) {
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) labels[i] = T.render(SECTION_LABELS[java.util.Arrays.asList(all).indexOf(ids[i])]);
        return labels;
    }

    /** Catalog ids of the chart-window labels, in StatsCharts.WINDOWS order (rule R5). */
    static final int[] WINDOW_LABELS = {T.STATS_WINDOW_2_MIN, T.STATS_WINDOW_10_MIN, T.STATS_WINDOW_1_HOUR, T.STATS_WINDOW_6_HOURS,
        T.STATS_WINDOW_24_HOURS, T.STATS_WINDOW_7_DAYS, T.STATS_WINDOW_30_DAYS, T.STATS_WINDOW_ALL};

    static String[] windows() {
        String[] labels = new String[WINDOW_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = T.render(WINDOW_LABELS[i]);
        return labels;
    }

    /** The symbol shown for a stored unit code ("km/h", "°C"); a code UnitCode does not list shows as it is. */
    static String unitSymbol(String code) {
        for (UnitCode unit : UnitCode.values()) if (unit.matches(code)) return unit.symbol();
        return code;
    }

    /**
     * Whether a search the English match missed finds a row by its text in the shown language (names
     * and group as shown, folded as NaveeModels.displayFold folds). In English the shown text is the
     * English the caller already searched, so nothing is added.
     */
    static boolean matchesShown(String query, String... shown) {
        if (Fmt.legacy()) return false;
        String needle = NaveeModels.displayFold(query.trim());
        if (needle.isEmpty()) return false;
        for (String text : shown) if (text != null && NaveeModels.displayFold(text).contains(needle)) return true;
        return false;
    }
}
