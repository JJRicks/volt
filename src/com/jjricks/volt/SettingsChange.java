package com.jjricks.volt;

/**
 * What the activity does when an app setting changes (MainActivity's settings listener): rebuild after a short pause for
 * the settings that change every page's look, rebuild at once for text size, redraw for the rest.
 */
final class SettingsChange {
    static final int REDRAW = 0, REBUILD = 1, REBUILD_NOW = 2;

    private SettingsChange() { }

    /**
     * The action for a changed key. Null is Android 11 and later reporting a clear() (a restore replaces every setting,
     * AppDataBackup): anything may have changed, theme and units among them, so the whole page rebuilds.
     */
    static int of(String key) {
        if (key == null) return REBUILD;
        if (key.equals("theme") || key.equals("units") || key.equals("temperature_unit") || key.equals("stats_density") || key.equals(HomeStyle.KEY)) return REBUILD;
        return key.equals("text_scale") ? REBUILD_NOW : REDRAW;
    }
}
