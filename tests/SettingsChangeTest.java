package com.jjricks.volt;

/** SettingsChange: what the activity does when an app setting changes, a clear()'s null key included. */
public final class SettingsChangeTest {
    private static int checks;

    public static void main(String[] args) {
        // Android 11+ calls the listener with a null key after clear() (a restore): this crashed with key.equals.
        require(SettingsChange.of(null) == SettingsChange.REBUILD, "A clear() (null key) rebuilds the page, not a crash");
        for (String key : new String[]{"theme", "units", "temperature_unit", "stats_density", HomeStyle.KEY})
            require(SettingsChange.of(key) == SettingsChange.REBUILD, key + " rebuilds after the pause");
        require(SettingsChange.of("text_scale") == SettingsChange.REBUILD_NOW, "Text size rebuilds at once");
        require(SettingsChange.of("auto_record") == SettingsChange.REDRAW && SettingsChange.of("") == SettingsChange.REDRAW, "Anything else redraws");
        System.out.println("SettingsChange: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
