package com.jjricks.volt;

import android.content.SharedPreferences;

import java.io.IOException;
import java.util.Map;

/**
 * Per-scooter riding mode storage in app_settings, like the dashboard: the layout, and the power
 * bar's observed scale so it stays steady from ride to ride. Both travel with Dashboards backups
 * (BackupRegistry).
 */
final class RidingConfig {
    static final String LAYOUT_PREFIX = "riding_layout_v1_", SCALE_PREFIX = "riding_power_scale_v1_";

    private final SharedPreferences preferences;
    private final String profileId;

    RidingConfig(SharedPreferences preferences, String profileId) {
        this.preferences = preferences;
        this.profileId = profileId;
    }

    /** The saved layout, or the shipped defaults when there is none or it can't be read. */
    RidingLayout layout() {
        return RidingLayout.parse(preferences.getString(LAYOUT_PREFIX + profileId, null));
    }

    void save(RidingLayout layout) {
        preferences.edit().putString(LAYOUT_PREFIX + profileId, layout.toJson()).commit();
    }

    /** The saved power scale, or the starting scale. */
    RidingValues.PowerScale powerScale() {
        String saved = preferences.getString(SCALE_PREFIX + profileId, null);
        if (saved == null) return new RidingValues.PowerScale();
        try {
            Map<String, Object> o = BackupJson.object(BackupJson.parse(saved, 2));
            return new RidingValues.PowerScale(number(o.get("draw"), RidingValues.PowerScale.DRAW_START_W),
                    number(o.get("regen"), RidingValues.PowerScale.REGEN_START_W));
        } catch (IOException | RuntimeException unreadable) {
            return new RidingValues.PowerScale();
        }
    }

    void save(RidingValues.PowerScale scale) {
        String json = "{\"draw\":" + Math.round(scale.drawW) + ",\"regen\":" + Math.round(scale.regenW) + "}";
        preferences.edit().putString(SCALE_PREFIX + profileId, json).commit();
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }
}
