package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The Setting tile short labels of the scooter controls: each control's tile shows its short rung, a screen
 * reader and every other screen keep the control's full name, and each new English short label fits the
 * 90 dp tile budget (volt:max) so it is not cut off at 344 dp.
 */
public final class TileShortRungTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static final String[][] TABLE = {
        {"acceleration", "Acceleration profile", "Acceleration"},
        {"auto_sensor", "Automatic lights", "Auto lights"},
        {"turn_sound", "Turn signal sound", "Turn sound"},
        {"display_units", "Scooter display units", "Display units"},
        {"speed_limit", "Custom speed limit", "Speed limit"},
        {"drive_speed_limit", "Drive mode speed limit", "Drive limit"},
        {"charge_start", "Charging start time", "Charge start"},
        {"low_power", "Low Battery Mode", "Low batt. mode"},
        {"hill_descent_control", "Hill descent control", "Hill descent"},
        {"overspeed_control", "Overspeed active control", "Overspeed ctrl."},
        {"line_pattern", "Line lights pattern", "Line pattern"},
        {"star_pattern", "Star lights pattern", "Star pattern"},
        {"line_color", "Line lights color", "Line color"},
        {"star_color", "Star lights color", "Star color"},
        {"line_boost_color", "Line lights Boost color", "Line Boost"},
        {"star_boost_color", "Star lights Boost color", "Star Boost"},
        // Short rungs for the languages; their English is the full name because it fits.
        {"lock", "Electronic lock", "Electronic lock"},
        {"cruise_control", "Cruise control", "Cruise control"},
        {"energy_recovery", "Energy recovery", "Energy recovery"},
        {"traction_control", "Traction control", "Traction control"},
        {"range_algorithm", "Range estimate", "Range estimate"},
        {"start_speed", "Kick-start speed", "Kick-start speed"},
        {"dual_drive", "Dual motor drive", "Dual motor drive"},
        {"lock_expiry", "Lock duration", "Lock duration"},
        {"long_range_mode", "Long range mode", "Long range mode"},
        {"hill_start_assist", "Walk Assist", "Walk Assist"},
        {"parking_assist", "Power Assist", "Power Assist"},
        {"proximity_key", "Proximity unlock", "Proximity unlock"},
    };

    public static void main(String[] args) throws Exception {
        for (String[] row : TABLE) {
            QuickAccessActions.Entry e = QuickAccessActions.find(QuickAccessActions.FEATURE_PREFIX + row[0]);
            check(e != null, row[0] + " is in the catalog");
            check(row[1].equals(e.label().render()), row[0] + " keeps its full name for screen readers: " + e.label().render());
            Msg rung = TileShortLabels.forFeature(row[0]);
            check(rung != null && row[2].equals(rung.render()), row[0] + " tile label: " + (rung == null ? null : rung.render()));
        }
        // A control without a short rung keeps its full name on the tile.
        QuickAccessActions.Entry charge = QuickAccessActions.find(QuickAccessActions.FEATURE_PREFIX + "charge_limit");
        check(TileShortLabels.forFeature("charge_limit") == null && charge.shortLabel().render().equals(charge.label().render()), "charge_limit has no short rung");
        check(T.dashboardModelTileChargeScheduleShort().equals("Charge sched."), "Charge schedule's tile label");
        check(T.dashboardModelTileChargeSchedule().equals("Charge schedule"), "Charge schedule's spoken name is unchanged");
        // Every short tile label carries the tile budget.
        String controls = new String(Files.readAllBytes(Paths.get("res/values/strings_controls.xml")), StandardCharsets.UTF_8);
        for (String key : new String[]{"acceleration", "auto_sensor", "turn_sound", "low_power", "light_pattern", "light_boost_color"}) {
            int at = controls.indexOf("name=\"controls_feature_" + key + "_short\"");
            check(at >= 0, key + " has a short rung");
            int end = controls.indexOf('>', at);
            check(controls.substring(at, end).contains("volt:max=\"90dp@12.5sp\""), key + " short rung carries the tile budget");
        }
        System.out.println("TileShortRungTest: " + checks + " checks passed (tile short rungs, spoken full names)");
    }
}
