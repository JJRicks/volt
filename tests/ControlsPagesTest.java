package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ControlsPages: every scooter control has exactly one page, in a fixed order (hub layout plan, PR 3). */
public final class ControlsPagesTest {
    private static int checks;

    /** Rows built by hand in MainActivity.ControlsScreen (ScooterMenuLayout ids and the live charging row). */
    private static final List<String> HAND_BUILT = Arrays.asList(ScooterMenuLayout.HEADLIGHT_INFO, ScooterMenuLayout.LIGHT_ZONES,
            ScooterMenuLayout.CLOCK_SYNC, ScooterMenuLayout.SCHEDULE_TEST, ScooterMenuLayout.CHARGER, ScooterMenuLayout.CHARGE_ALERT,
            ScooterMenuLayout.LOW_ALERT, ScooterMenuLayout.SECURITY_LINK, ScooterMenuLayout.SCOOTER_NAME, ControlsPages.CHARGE_LIVE,
            "maintenance_reminders");

    public static void main(String[] args) {
        pages();
        features();
        handBuilt();
        links();
        doneBy();
        order();
        System.out.println("ControlsPages: " + checks + " checks passed");
    }

    private static void pages() {
        for (String page : ControlsPages.pages()) {
            ControlsPages.Spec spec = ControlsPages.spec(page);
            require(HubPages.known(page) && spec.page.equals(page), "A spec names a Hub page: " + page);
            require(new HashSet<>(spec.rows).size() == spec.rows.size(), "No row twice on a page: " + page);
            require(!HubPages.subjectOf(page).equals(HubPages.SETTINGS) && !HubPages.subjectOf(page).equals(HubPages.RIDE)
                    && !HubPages.subjectOf(page).equals(HubPages.DATA), "Scooter rows stay on scooter subjects: " + page);
        }
        ControlsPages.Spec index = ControlsPages.spec(HubPages.SCOOTER);
        require(index.search && index.rows.isEmpty() && !index.topSpeed, "The index is search only");
        for (String page : ControlsPages.pages())
            require(page.equals(HubPages.SCOOTER) || !ControlsPages.spec(page).search, "Only the index searches: " + page);
        require(ControlsPages.spec(HubPages.CONTROLS).topSpeed, "The top speed card leads Controls");
        int topSpeed = 0;
        for (String page : ControlsPages.pages()) if (ControlsPages.spec(page).topSpeed) topSpeed++;
        require(topSpeed == 1, "The top speed card is on one page");
        require(ControlsPages.spec(HubPages.HOME) == null && ControlsPages.spec(null) == null && ControlsPages.spec("bogus") == null,
                "Other pages have no spec");
    }

    private static void features() {
        List<ScooterCommands.Feature> all = new ArrayList<>(ScooterCommands.catalog());
        all.addAll(ScooterCommands.oemOnly());
        for (ScooterCommands.Feature f : all) {
            int places = places(f.key);
            if (ControlsPages.NEVER.contains(f.key)) require(places == 0, "Never a row: " + f.key);
            else require(places == 1, "On exactly one page: " + f.key + " (" + places + ")");
            if (f.experimental) require(ControlsPages.pageOf(f.key) != null, "An experimental control sits on its subject page: " + f.key);
        }
        require(places("lock") == 0 && places("pin") == 0 && places("proximity_key") == 0, "Lock, PIN and proximity stay off these pages");
        // Set-once controls on sub-pages (owner's decision 3).
        for (String key : new String[] {"parking_assist", "hill_start_assist", "hill_descent_control", "start_speed"})
            require(HubPages.CONTROLS_ASSISTS.equals(ControlsPages.pageOf(key)), "Assist: " + key);
        for (String key : new String[] {"speed_limit", "drive_speed_limit", "overspeed_control"})
            require(HubPages.CONTROLS_LIMITS.equals(ControlsPages.pageOf(key)), "Limit: " + key);
        for (String key : new String[] {"low_power", "long_range_mode"})
            require(HubPages.BATTERY_MODES.equals(ControlsPages.pageOf(key)), "Battery mode: " + key);
        require(HubPages.BATTERY_ESTIMATES.equals(ControlsPages.pageOf("range_algorithm")), "Range estimate source is set once");
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            if (f.key.startsWith("line_")) require(HubPages.LIGHTS_LINE.equals(ControlsPages.pageOf(f.key)), "Line light: " + f.key);
            if (f.key.startsWith("star_")) require(HubPages.LIGHTS_STAR.equals(ControlsPages.pageOf(f.key)), "Star light: " + f.key);
        }
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) require(HubPages.LIGHTS.equals(ControlsPages.pageOf(f.key)), "Another model's light: " + f.key);
    }

    private static void handBuilt() {
        for (String id : HAND_BUILT) {
            int places = places(id);
            if (ControlsPages.RETIRED.contains(id)) require(places == 0, "Retired, on no page: " + id);
            else require(places == 1, "On exactly one page: " + id + " (" + places + ")");
        }
        for (String id : ControlsPages.RETIRED) require(HAND_BUILT.contains(id), "Retired ids are hand-built rows: " + id);
        // Every row id on a page is a control, a hand-built row or a link.
        Set<String> keys = new HashSet<>();
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) keys.add(f.key);
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) keys.add(f.key);
        for (String page : ControlsPages.pages())
            for (String id : ControlsPages.spec(page).rows)
                require(keys.contains(id) || HAND_BUILT.contains(id) || ControlsPages.isLink(id), "A known row: " + page + " " + id);
    }

    private static void links() {
        for (String page : ControlsPages.pages())
            for (String id : ControlsPages.spec(page).rows) {
                if (!ControlsPages.isLink(id)) continue;
                String target = ControlsPages.linkTarget(id);
                require(HubPages.known(target) && page.equals(HubPages.parent(target)), "A link opens one of the page's sub-pages: " + page + " " + target);
                require(ControlsPages.spec(target) != null, "A linked page has rows: " + target);
            }
        // Every sub-page with rows is linked from its parent, so it is two taps from the Scooter tab.
        for (String page : ControlsPages.pages()) {
            if (HubPages.depth(page) < 2) continue;
            require(ControlsPages.spec(HubPages.parent(page)).includes(ControlsPages.link(page)), "Linked from its parent: " + page);
        }
        require(ControlsPages.linkTarget("charge_limit") == null && !ControlsPages.isLink(null), "Only links have targets");
    }

    private static void doneBy() {
        // The "Done by" row follows its parent: both charging rows sit on Battery & charging.
        require(HubPages.BATTERY.equals(ControlsPages.pageOf("timed_charging")) && HubPages.BATTERY.equals(ControlsPages.pageOf("charge_start")),
                "Charge done by follows its parents onto Battery & charging");
    }

    private static void order() {
        // Most used first (owner's decision 3): drive mode then cruise on Controls; scheduled charging first on Battery.
        List<String> controls = ControlsPages.spec(HubPages.CONTROLS).rows;
        require(controls.indexOf("maximum_speed") == 0 && controls.indexOf("cruise_control") == 1, "Controls opens with drive mode and cruise");
        List<String> battery = ControlsPages.spec(HubPages.BATTERY).rows;
        require(battery.indexOf("timed_charging") == 0, "Battery opens with scheduled charging");
        List<String> lights = ControlsPages.spec(HubPages.LIGHTS).rows;
        require(lights.indexOf(ScooterMenuLayout.HEADLIGHT_INFO) == 0, "Lights opens with the headlight's note");
        require(lights.indexOf(ControlsPages.link(HubPages.LIGHTS_STAR)) < lights.indexOf("turn_sound"), "Lights before sounds");
    }

    private static int places(String id) {
        int n = 0;
        for (String page : ControlsPages.pages()) if (ControlsPages.spec(page).includes(id)) n++;
        return n;
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
