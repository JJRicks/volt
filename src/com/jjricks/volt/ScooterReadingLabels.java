package com.jjricks.volt;

/**
 * Reading names in the current language, a narrow-label ladder per reading: {@link #full}, {@link #label}
 * (short) and {@link #compact}. Telemetry's names and stored values stay English data; only these display
 * names come from the catalog (res/values/strings_readings.xml).
 */
final class ScooterReadingLabels {
    private ScooterReadingLabels() {}

    /** Further abbreviations for physically narrow cards; full names remain accessible. */
    static String compact(String key, String fallback) {
        if (key == null) return fallback;
        switch (key) {
            case "battery_percent": return T.readingsBatteryPercentCompact();
            case "voltage_v": return T.readingsVoltageVCompact();
            case "current_a": return T.readingsCurrentACompact();
            case "power_w": return T.readingsPowerWCompact();
            case "temperature_c": return T.readingsTemperatureCCompact();
            case "battery_health_percent": return T.readingsBatteryHealthPercentCompact();
            case "battery_status": return T.readingsBatteryStatusCompact();
            case "charging_state": return T.readingsChargingStateCompact();
            case "charge_cycles": return T.readingsChargeCyclesCompact();
            case "energy_throughput_wh": return T.readingsEnergyThroughputWhCompact();
            case "capacity_throughput_ah": return T.readingsCapacityThroughputAhCompact();
            case "rated_capacity_ah": return T.readingsRatedCapacityAhCompact();
            case "health_adjusted_capacity_ah": return T.readingsHealthAdjustedCapacityAhCompact();
            case "speed_kmh": return T.readingsSpeedKmhCompact();
            case "remaining_range_km": return T.readingsRemainingRangeKmCompact();
            case "trip_km": return T.readingsTripKmCompact();
            case "trip_minutes": return T.readingsTripMinutesCompact();
            case "maximum_speed_kmh": return T.readingsMaximumSpeedKmhCompact();
            case "average_speed_kmh": return T.readingsAverageSpeedKmhCompact();
            case "odometer_km": return T.readingsOdometerKmCompact();
            case "driving_mode": return T.readingsDrivingModeCompact();
            case "lock_status": return T.readingsLockStatusCompact();
            case "charge_limit_percent": return T.readingsChargeLimitPercentCompact();
            case "meter_version": return T.readingsMeterVersionCompact();
            case "controller_version": return T.readingsControllerVersionCompact();
            case "battery_version": return T.readingsBatteryVersionCompact();
            case "screen_version": return T.readingsScreenVersionCompact();
            case "uwb_version": return T.readingsUwbVersionCompact();
            case "warning_code": return T.readingsWarningCodeCompact();
            default: return fallback;
        }
    }

    static String label(String key, String fallback) {
        if (key == null) return fallback;
        switch (key) {
            case "battery_percent": return T.readingsBatteryPercentShort();
            case "voltage_v": return T.readingsVoltageVShort();
            case "current_a": return T.readingsCurrentAShort();
            case "power_w": return T.readingsPowerWShort();
            case "temperature_c": return T.readingsTemperatureCShort();
            case "battery_health_percent": return T.readingsBatteryHealthPercentShort();
            case "battery_status": return T.readingsBatteryStatusShort();
            case "charging_state": return T.readingsChargingStateShort();
            case "charge_cycles": return T.readingsChargeCyclesShort();
            case "energy_throughput_wh": return T.readingsEnergyThroughputWhShort();
            case "capacity_throughput_ah": return T.readingsCapacityThroughputAhShort();
            case "deep_discharge": return T.readingsDeepDischargeShort();
            case "self_discharge_raw": return T.readingsSelfDischargeRawShort();
            case "rated_capacity_ah": return T.readingsRatedCapacityAhShort();
            case "health_adjusted_capacity_ah": return T.readingsHealthAdjustedCapacityAhShort();
            case "battery_manufactured": return T.readingsBatteryManufacturedShort();
            case "high_temperature_duration": return T.readingsHighTemperatureDurationShort();
            case "extreme_temperature_charge_raw": return T.readingsExtremeTemperatureChargeRawShort();
            case "temperature_sign_state": return T.readingsTemperatureSignStateShort();
            case "current_sign_state": return T.readingsCurrentSignStateShort();

            case "speed_kmh": return T.readingsSpeedKmhShort();
            case "remaining_range_km": return T.readingsRemainingRangeKmShort();
            case "trip_km": return T.readingsTripKmShort();
            case "trip_minutes": return T.readingsTripMinutesShort();
            case "maximum_speed_kmh": return T.readingsMaximumSpeedKmhShort();
            case "average_speed_kmh": return T.readingsAverageSpeedKmhShort();
            case "odometer_km": return T.readingsOdometerKmShort();
            case "driving_status": return T.readingsDrivingStatusShort();
            case "stored_trips": return T.readingsStoredTripsShort();

            case "binding_status": return T.readingsBindingStatusShort();
            case "driving_mode": return T.readingsDrivingModeShort();
            case "lock_status": return T.readingsLockStatusShort();
            case "cruise_control": return T.readingsCruiseControlShort();
            case "tail_light": return T.readingsTailLightShort();
            case "energy_recovery": return T.readingsEnergyRecoveryShort();
            case "range_algorithm": return T.readingsRangeAlgorithmShort();
            case "display_units": return T.readingsDisplayUnitsShort();
            case "auto_sensor": return T.readingsAutoSensorShort();
            case "tyre_reminder": return T.readingsTyreReminderShort();
            case "ambient_light": return T.readingsAmbientLightShort();
            case "traction_control": return T.readingsTractionControlShort();
            case "turn_sound": return T.readingsTurnSoundShort();
            case "proximity_key": return T.readingsProximityKeyShort();
            case "night_mode": return T.readingsNightModeShort();
            case "range_algorithm_mode": return T.readingsRangeAlgorithmModeShort();
            case "eco_light": return T.readingsEcoLightShort();
            case "drive_light": return T.readingsDriveLightShort();
            case "sport_light": return T.readingsSportLightShort();
            case "start_speed": return T.readingsStartSpeedShort();
            case "speed_limit": return T.readingsSpeedLimitShort();
            case "volume": return T.readingsVolumeShort();
            case "voice_language": return T.readingsVoiceLanguageShort();
            case "logo_light": return T.readingsLogoLightShort();
            case "daytime_light": return T.readingsDaytimeLightShort();
            case "maximum_speed_setting": return T.readingsMaximumSpeedSettingShort();
            case "drive_mode_setting": return T.readingsDriveModeSettingShort();
            case "settings_mode_raw": return T.readingsSettingsModeRawShort();
            case "scheduled_charge_time_raw": return T.readingsScheduledChargeTimeRawShort();
            case "charge_limit_percent": return T.readingsChargeLimitPercentShort();
            case "low_power": return T.readingsLowPowerShort();
            case "timed_charging": return T.readingsTimedChargingShort();
            case "auto_lock_time": return T.readingsAutoLockTimeShort();
            case "speed_limit_eligibility": return T.readingsSpeedLimitEligibilityShort();
            case "weather": return T.readingsWeatherShort();
            case "slope_assist": return T.readingsSlopeAssistShort();
            case "long_range_mode": return T.readingsLongRangeModeShort();
            case "overspeed_control": return T.readingsOverspeedControlShort();
            case "overspeed_alert": return T.readingsOverspeedAlertShort();
            case "drive_speed_limit": return T.readingsDriveSpeedLimitShort();

            case "meter_version": return T.readingsMeterVersionShort();
            case "controller_version": return T.readingsControllerVersionShort();
            case "battery_version": return T.readingsBatteryVersionShort();
            case "screen_version": return T.readingsScreenVersionShort();
            case "uwb_version": return T.readingsUwbVersionShort();
            case "scooter_serial": return T.readingsScooterSerialShort();
            case "battery_serial_raw": return T.readingsBatterySerialRawShort();

            case "warning_code": return T.readingsWarningCodeShort();
            case "lock_push_warning": return T.readingsLockPushWarningShort();
            case "hide_e9": return T.readingsHideE9Short();
            case "ride_report_format": return T.readingsRideReportFormatShort();
            case "ride_read_raw": return T.readingsRideReadRawShort();
            case "fault_history_raw": return T.readingsFaultHistoryRawShort();
            case "response_70": return T.readingsResponse70Short();
            case "response_71": return T.readingsResponse71Short();
            case "response_72": return T.readingsResponse72Short();
            case "response_73": return T.readingsResponse73Short();
            case "response_74": return T.readingsResponse74Short();
            case "response_75": return T.readingsResponse75Short();
            case "response_76": return T.readingsResponse76Short();
            case "response_79": return T.readingsResponse79Short();
            case "response_7b": return T.readingsResponse7bShort();
            case "response_90": return T.readingsResponse90Short();
            case "response_91": return T.readingsResponse91Short();
            case "response_92": return T.readingsResponse92Short();
            default: return fallback;
        }
    }

    /**
     * The full name of a reading, as Live Stats details and the widest Home cards show it: in English, the
     * reading's name in Telemetry (Metric.label), which reports, diagnostics and history keep in English.
     * The top rung of the ladder {@link #label} (short) and {@link #compact} continue.
     */
    static String full(String key, String fallback) {
        if (key == null) return fallback;
        switch (key) {
            case "battery_percent": return T.readingsBatteryPercent();
            case "voltage_v": return T.readingsVoltageV();
            case "current_a": return T.readingsCurrentA();
            case "power_w": return T.readingsPowerW();
            case "temperature_c": return T.readingsTemperatureC();
            case "battery_health_percent": return T.readingsBatteryHealthPercent();
            case "battery_status": return T.readingsBatteryStatus();
            case "charging_state": return T.readingsChargingState();
            case "charge_cycles": return T.readingsChargeCycles();
            case "energy_throughput_wh": return T.readingsEnergyThroughputWh();
            case "capacity_throughput_ah": return T.readingsCapacityThroughputAh();
            case "deep_discharge": return T.readingsDeepDischarge();
            case "self_discharge_raw": return T.readingsSelfDischargeRaw();
            case "rated_capacity_ah": return T.readingsRatedCapacityAh();
            case "health_adjusted_capacity_ah": return T.readingsHealthAdjustedCapacityAh();
            case "battery_manufactured": return T.readingsBatteryManufactured();
            case "high_temperature_duration": return T.readingsHighTemperatureDuration();
            case "extreme_temperature_charge_raw": return T.readingsExtremeTemperatureChargeRaw();
            case "temperature_sign_state": return T.readingsTemperatureSignState();
            case "current_sign_state": return T.readingsCurrentSignState();
            case "speed_kmh": return T.readingsSpeedKmh();
            case "remaining_range_km": return T.readingsRemainingRangeKm();
            case "trip_km": return T.readingsTripKm();
            case "trip_minutes": return T.readingsTripMinutes();
            case "maximum_speed_kmh": return T.readingsMaximumSpeedKmh();
            case "average_speed_kmh": return T.readingsAverageSpeedKmh();
            case "odometer_km": return T.readingsOdometerKm();
            case "driving_status": return T.readingsDrivingStatus();
            case "stored_trips": return T.readingsStoredTrips();
            case "binding_status": return T.readingsBindingStatus();
            case "driving_mode": return T.readingsDrivingMode();
            case "lock_status": return T.readingsLockStatus();
            case "cruise_control": return T.readingsCruiseControl();
            case "tail_light": return T.readingsTailLight();
            case "energy_recovery": return T.readingsEnergyRecovery();
            case "range_algorithm": return T.readingsRangeAlgorithm();
            case "display_units": return T.readingsDisplayUnits();
            case "auto_sensor": return T.readingsAutoSensor();
            case "tyre_reminder": return T.readingsTyreReminder();
            case "ambient_light": return T.readingsAmbientLight();
            case "traction_control": return T.readingsTractionControl();
            case "turn_sound": return T.readingsTurnSound();
            case "proximity_key": return T.readingsProximityKey();
            case "night_mode": return T.readingsNightMode();
            case "range_algorithm_mode": return T.readingsRangeAlgorithmMode();
            case "eco_light": return T.readingsEcoLight();
            case "drive_light": return T.readingsDriveLight();
            case "sport_light": return T.readingsSportLight();
            case "start_speed": return T.readingsStartSpeed();
            case "speed_limit": return T.readingsSpeedLimit();
            case "volume": return T.readingsVolume();
            case "voice_language": return T.readingsVoiceLanguage();
            case "logo_light": return T.readingsLogoLight();
            case "daytime_light": return T.readingsDaytimeLight();
            case "maximum_speed_setting": return T.readingsMaximumSpeedSetting();
            case "drive_mode_setting": return T.readingsDriveModeSetting();
            case "settings_mode_raw": return T.readingsSettingsModeRaw();
            case "scheduled_charge_time_raw": return T.readingsScheduledChargeTimeRaw();
            case "charge_limit_percent": return T.readingsChargeLimitPercent();
            case "low_power": return T.readingsLowPower();
            case "timed_charging": return T.readingsTimedCharging();
            case "auto_lock_time": return T.readingsAutoLockTime();
            case "speed_limit_eligibility": return T.readingsSpeedLimitEligibility();
            case "weather": return T.readingsWeather();
            case "slope_assist": return T.readingsSlopeAssist();
            case "long_range_mode": return T.readingsLongRangeMode();
            case "overspeed_control": return T.readingsOverspeedControl();
            case "overspeed_alert": return T.readingsOverspeedAlert();
            case "drive_speed_limit": return T.readingsDriveSpeedLimit();
            case "meter_version": return T.readingsMeterVersion();
            case "controller_version": return T.readingsControllerVersion();
            case "battery_version": return T.readingsBatteryVersion();
            case "screen_version": return T.readingsScreenVersion();
            case "uwb_version": return T.readingsUwbVersion();
            case "scooter_serial": return T.readingsScooterSerial();
            case "battery_serial_raw": return T.readingsBatterySerialRaw();
            case "warning_code": return T.readingsWarningCode();
            case "lock_push_warning": return T.readingsLockPushWarning();
            case "hide_e9": return T.readingsHideE9();
            case "ride_report_format": return T.readingsRideReportFormat();
            case "ride_read_raw": return T.readingsRideReadRaw();
            case "fault_history_raw": return T.readingsFaultHistoryRaw();
            case "response_70": return T.readingsResponse70();
            case "response_71": return T.readingsResponse71();
            case "response_72": return T.readingsResponse72();
            case "response_73": return T.readingsResponse73();
            case "response_74": return T.readingsResponse74();
            case "response_75": return T.readingsResponse75();
            case "response_76": return T.readingsResponse76();
            case "response_79": return T.readingsResponse79();
            case "response_7b": return T.readingsResponse7b();
            case "response_90": return T.readingsResponse90();
            case "response_91": return T.readingsResponse91();
            case "response_92": return T.readingsResponse92();
            default: return fallback;
        }
    }
}
