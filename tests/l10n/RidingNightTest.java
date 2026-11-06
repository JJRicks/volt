package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Night vision's Android wiring (ride-display plan, PR 3), checked in the source: RidingScreen builds the
 * night palette and the moon button and detaches it on close; RidingNight puts the colour filter on the
 * riding root as a hardware layer and keeps the moon's choice per visit; RidingDisplay rescales its levels
 * into the night range only while night vision shows; MoreScreen offers Off · On · Scheduled and asks
 * before it uses the rounded ride area; BackupRegistry keeps the area answer on the device.
 * Listed in tests/l10n/riding-night-vision.sources; tests/run.sh runs it from the repository root.
 */
public final class RidingNightTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static String read(String path) throws IOException { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
    private static void has(String text, String needle, String where) { check(text.contains(needle), where + " lacks: " + needle); }

    /** The generated catalog's accessor for a key: riding_night_on -> ridingNightOn. */
    private static String accessor(String key) {
        StringBuilder out = new StringBuilder(); boolean up = false;
        for (char c : key.toCharArray()) { if (c == '_') { up = true; continue; } out.append(up ? Character.toUpperCase(c) : c); up = false; }
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        String screen = read("src/com/jjricks/volt/RidingScreen.java");
        has(screen, "ui = RidingNight.palette(host, session);", "RidingScreen");
        has(screen, "night = new RidingNight(host, ui, session, view, display);", "RidingScreen");
        has(screen, "top.addView(night.button(), RidingNight.slot(ui));", "RidingScreen");
        has(screen, "night.detach();", "RidingScreen");

        String night = read("src/com/jjricks/volt/RidingNight.java");
        has(night, "View.LAYER_TYPE_HARDWARE", "RidingNight");
        has(night, "new ColorMatrixColorFilter(NightFade.matrix(layer))", "RidingNight");
        has(night, "Settings.Global.ANIMATOR_DURATION_SCALE", "RidingNight");
        has(night, "NightFade.instant(scale)", "RidingNight");
        has(night, "fade.setDuration(NightFade.FADE_MS)", "RidingNight");
        has(night, "getOverlay().add(picture)", "RidingNight");
        // Opening with night vision on: the final look at once, never a fade from the normal look.
        has(night, "else { if (carried != null) carried.snapshot.recycle(); look(shown ? 1 : 0, shown ? 1 : 0); }", "RidingNight");
        has(night, "handler.removeCallbacks(recheck);\n        endFade();", "RidingNight");
        has(night, "display.night(", "RidingNight");
        has(night, "new WeakHashMap<", "RidingNight");
        has(night, "NightSchedule.tap(", "RidingNight");
        has(night, "R.raw.tz_coords", "RidingNight");
        has(night, "setContentDescription(", "RidingNight");
        has(night, "T.ridingNightButtonOn()", "RidingNight");
        has(night, "T.ridingNightButtonOff()", "RidingNight");
        check(!night.contains("URL") && !night.contains("http"), "RidingNight must not use the network");
        check(!night.contains(".putString(") && !night.contains(".putFloat("), "RidingNight must not store the ride area");

        String display = read("src/com/jjricks/volt/RidingDisplay.java");
        has(display, "void night(float amount, boolean on)", "RidingDisplay");
        has(display, "boolean hide = statusBarSetting || on;", "RidingDisplay");
        has(display, "level = NightFade.level(level, night);", "RidingDisplay");

        String more = read("src/com/jjricks/volt/MoreScreen.java");
        for (String key : new String[] {"riding_night_off", "riding_night_on", "riding_night_scheduled", "riding_night_sun",
                "riding_night_custom", "riding_night_consent_title", "riding_night_consent_allow", "riding_night_consent_zone"})
            has(more, "T." + accessor(key) + "(", "MoreScreen");
        has(more, "NightSchedule.AREA_KEY", "MoreScreen");
        has(more, "nightPreferences()", "MoreScreen");

        String backup = read("src/com/jjricks/volt/BackupRegistry.java");
        for (String key : new String[] {"MODE_KEY", "SCHEDULE_KEY", "START_KEY", "END_KEY", "AREA_KEY"}) has(backup, "NightSchedule." + key, "BackupRegistry");

        String strings = read("res/values/strings_riding_night.xml");
        int names = 0;
        for (int at = strings.indexOf("<string name=\""); at >= 0; at = strings.indexOf("<string name=\"", at + 1)) {
            names++;
            String name = strings.substring(at + 14, strings.indexOf('"', at + 14));
            check(name.startsWith("riding_night_"), "String key outside riding_night_: " + name);
            String all = screen + night + display + more;
            check(all.contains("T." + accessor(name) + "("), "Unused string: " + name);
        }
        check(names == 27, "Expected 27 strings, found " + names);
        System.out.println("RidingNightTest: " + checks + " checks passed (night vision's wiring: layer, moon, brightness range, settings, consent, backup, strings)");
    }
}
