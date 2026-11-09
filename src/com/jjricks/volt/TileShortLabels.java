package com.jjricks.volt;

/** The short Setting tile label of a scooter control, by its feature key (data, never shown); null keeps the control's name. */
final class TileShortLabels {
    private TileShortLabels() {}

    static Msg forFeature(String key) {
        if (key == null) return null;
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
}
