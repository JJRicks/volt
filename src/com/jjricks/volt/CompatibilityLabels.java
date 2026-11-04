package com.jjricks.volt;

/**
 * The Compatibility panel's names for the scooter's status fields, in the current language (plan B9). The
 * report keeps its own English names (CompatibilityReport.fieldName and fieldLabel), which this follows: a
 * name this class does not know is shown as the report's English label. Pure, so the plain JVM suites check
 * it against the report's labels.
 */
final class CompatibilityLabels {
    private CompatibilityLabels() {}

    /** A field row's title: CompatibilityReport.fieldLabel(offset, layer), in the current language. */
    static String field(int offset, ModelRegistry.Layer layer) {
        String name = CompatibilityReport.fieldName(offset, layer);
        if (name == null) return "";
        // Lighting bytes carry UT5 MAX meanings only on that layer; elsewhere they are raw bytes.
        if (layer != ModelRegistry.Layer.UT5_MAX && (offset == 10 || offset >= 16 && offset <= 18)) return T.compatFieldLightingByte(offset);
        if (offset == 40) return T.compatFieldOsaFlag();
        if (offset == 41) return T.compatFieldByte41();
        switch (name) {
            case "binding_status": return T.compatFieldBindingStatus();
            case "driving_mode": return T.compatFieldDrivingMode();
            case "lock_status": return T.compatFieldLockStatus();
            case "cruise_control": return T.compatFieldCruiseControl();
            case "tail_light": return T.compatFieldTailLight();
            case "energy_recovery": return T.compatFieldEnergyRecovery();
            case "range_algorithm": return T.compatFieldRangeAlgorithm();
            case "display_units": return T.compatFieldDisplayUnits();
            case "auto_sensor": return T.compatFieldAutoSensor();
            case "tyre_reminder": return T.compatFieldTyreReminder();
            case "ambient_light": return T.compatFieldAmbientLight();
            case "traction_control": return T.compatFieldTractionControl();
            case "turn_sound": return T.compatFieldTurnSound();
            case "proximity_key": return T.compatFieldProximityKey();
            case "night_mode": return T.compatFieldNightMode();
            case "range_algorithm_mode": return T.compatFieldRangeAlgorithmMode();
            case "eco_light": return T.compatFieldEcoLight();
            case "drive_light": return T.compatFieldDriveLight();
            case "sport_light": return T.compatFieldSportLight();
            case "start_speed": return T.compatFieldStartSpeed();
            case "speed_limit": return T.compatFieldSpeedLimit();
            case "volume": return T.compatFieldVolume();
            case "voice_language": return T.compatFieldVoiceLanguage();
            case "logo_light": return T.compatFieldLogoLight();
            case "daytime_light": return T.compatFieldDaytimeLight();
            case "maximum_speed_setting": return T.compatFieldMaximumSpeedSetting();
            case "drive_mode_setting": return T.compatFieldDriveModeSetting();
            case "charge_limit_percent": return T.compatFieldChargeLimitPercent();
            case "low_power": return T.compatFieldLowPower();
            case "timed_charging": return T.compatFieldTimedCharging();
            case "auto_lock_time": return T.compatFieldAutoLockTime();
            case "speed_limit_eligibility": return T.compatFieldSpeedLimitEligibility();
            case "weather": return T.compatFieldWeather();
            case "slope_assist": return T.compatFieldSlopeAssist();
            case "long_range_mode": return T.compatFieldLongRangeMode();
            case "overspeed_control": return T.compatFieldOverspeedControl();
            case "drive_speed_limit": return T.compatFieldDriveSpeedLimit();
            default: return CompatibilityReport.fieldLabel(offset, layer);
        }
    }
}
