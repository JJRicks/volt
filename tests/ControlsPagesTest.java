package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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

    public static void main(String[] args) throws IOException {
        pages();
        features();
        handBuilt();
        links();
        doneBy(args.length > 0 ? args[0] : "src/com/jjricks/volt/MainActivity.java");
        order();
        experimental();
        linkVisibility();
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
                require(ControlsPages.spec(target) != null || ControlsPages.HOSTED_ELSEWHERE.contains(target), "A linked page has rows: " + target);
            }
        // Every sub-page with rows is linked from its parent, so it is two taps from the Scooter tab.
        for (String page : ControlsPages.pages()) {
            if (HubPages.depth(page) < 2) continue;
            require(ControlsPages.spec(HubPages.parent(page)).includes(ControlsPages.link(page)), "Linked from its parent: " + page);
        }
        require(ControlsPages.linkTarget("charge_limit") == null && !ControlsPages.isLink(null), "Only links have targets");
        // Charge history & recordings is MoreScreen's page, linked from Battery & charging (hub layout plan, PR 4).
        for (String page : ControlsPages.HOSTED_ELSEWHERE)
            require(ControlsPages.spec(page) == null && ControlsPages.spec(HubPages.parent(page)).includes(ControlsPages.link(page)),
                    "Linked from its parent, built elsewhere: " + page);
    }

    private static void doneBy(String mainActivity) throws IOException {
        // The "Done by" row follows its parent: both charging rows sit on Battery & charging.
        require(HubPages.BATTERY.equals(ControlsPages.pageOf("timed_charging")) && HubPages.BATTERY.equals(ControlsPages.pageOf("charge_start")),
                "Charge done by follows its parents onto Battery & charging");
        // ...and ControlsScreen places it directly after its parent's card: glued below that card, then placed straight after it.
        String source = new String(Files.readAllBytes(Paths.get(mainActivity)), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        require(source.contains("gluedBelow.put(control.card,done.row.card);"), "Each Done by row is glued below its parent row");
        require(source.contains("place(card);Viewdone=gluedBelow.get(card);if(done!=null)place(done);"),
                "A page places a Done by row directly after its parent");
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

    /** A row the page would show on this model with no status yet: an ordinary row, or its Experimental entry. */
    private static boolean shownOn(DeviceDescriptor d, String id) {
        boolean feature = ScooterCommands.oemOnly(id);
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) feature |= f.key.equals(id);
        if (!feature) return true; // hand-built rows
        CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
        CapabilityResolver.Decision ordinary = CapabilityResolver.decide(id, d, StatusFields.NONE, rt);
        boolean row = ordinary.support == CapabilityResolver.Support.KNOWN_ENCODING && ordinary.variant != null && !ordinary.variant.lab;
        return row || ControlsPages.labEntryShown(row, ModelRegistry.experimental(d).contains(id),
                CapabilityResolver.decideExperimental(id, d, StatusFields.NONE, rt).experimental());
    }

    private static void experimental() {
        // The GT3 Max (24182): its Lab tries the three assists, Prompt tone and the custom speed cap, none an ordinary row.
        // Each gets an entry on its page, shown as More lists it.
        DeviceDescriptor gt3 = OemControlsTest.model("24182", "ES");
        List<String> lab = ModelRegistry.experimental(gt3);
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS_ASSISTS), lab)
                .equals(Arrays.asList("parking_assist", "hill_start_assist", "hill_descent_control")), "GT3 Max assists: " + lab);
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS_LIMITS), lab).equals(Arrays.asList("speed_limit")), "GT3 Max speed cap");
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.LIGHTS), lab).contains("prompt_tone"), "GT3 Max Prompt tone");
        for (String key : new String[] {"parking_assist", "hill_start_assist", "hill_descent_control", "prompt_tone", "speed_limit"}) {
            // The assists aren't flagged experimental in the catalogue (the old builder never made their entries); Prompt tone
            // and the speed cap are (UT5 MAX Lab controls), but the old check asked the ordinary decision and hid them.
            if (key.endsWith("assist") || key.equals("hill_descent_control")) require(!ScooterCommands.find(key).experimental, "Not a catalogue Lab control: " + key);
            CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
            CapabilityResolver.Decision ordinary = CapabilityResolver.decide(key, gt3, StatusFields.NONE, rt);
            boolean row = ordinary.support == CapabilityResolver.Support.KNOWN_ENCODING && ordinary.variant != null && !ordinary.variant.lab;
            require(!row, "No ordinary row on the GT3 Max: " + key);
            // The ordinary decision has no Lab variant here (the old check hid the entry); the experimental one does.
            require(!(ordinary.support == CapabilityResolver.Support.KNOWN_ENCODING && ordinary.variant != null && ordinary.variant.lab),
                    "The ordinary decision is no Lab try: " + key);
            boolean tryNow = CapabilityResolver.decideExperimental(key, gt3, StatusFields.NONE, rt).experimental();
            require(tryNow && ControlsPages.labEntryShown(row, lab.contains(key), tryNow), "The GT3 Max entry shows: " + key);
        }
        // Traction control is an ordinary OEM-mapped row on the GT3 Max: no entry.
        require(!lab.contains("traction_control") && ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), lab).stream().noneMatch("traction_control"::equals),
                "GT3 Max traction control is a row, not an entry");
        // The UT5 MAX keeps its catalogue Lab controls' entries (Acceleration profile, Dual motor drive).
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        List<String> ut5Lab = ModelRegistry.experimental(ut5);
        List<String> controls = ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), ut5Lab);
        require(controls.contains("acceleration") && controls.contains("dual_drive"), "UT5 MAX entries on Controls: " + controls);
        for (ScooterCommands.Feature f : ScooterCommands.catalog())
            if (ut5Lab.contains(f.key) && ControlsPages.pageOf(f.key) != null)
                require(ControlsPages.labEntries(ControlsPages.spec(ControlsPages.pageOf(f.key)), ut5Lab).contains(f.key), "Every Lab control on its page: " + f.key);
        // The rule: never beside an ordinary row, never outside the model's Lab list, only with a Lab try decision.
        require(!ControlsPages.labEntryShown(true, true, true) && !ControlsPages.labEntryShown(false, false, true)
                && !ControlsPages.labEntryShown(false, true, false) && ControlsPages.labEntryShown(false, true, true), "labEntryShown");
        require(ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), Arrays.asList(ControlsPages.link(HubPages.CONTROLS_ASSISTS))).isEmpty()
                && ControlsPages.labEntries(null, lab).isEmpty() && ControlsPages.labEntries(ControlsPages.spec(HubPages.CONTROLS), null).isEmpty(),
                "Links and missing input give no entries");
    }

    private static void linkVisibility() {
        // A link shows only while its page has a row or an entry to show.
        for (String page : ControlsPages.pages()) {
            if (HubPages.depth(page) < 2) continue;
            require(!ControlsPages.linkShown(page, id -> false), "Nothing to show hides the link: " + page);
            require(ControlsPages.linkShown(page, id -> true), "Anything to show keeps the link: " + page);
        }
        require(ControlsPages.linkShown(HubPages.BATTERY_MODES, "long_range_mode"::equals), "One row is enough");
        require(!ControlsPages.linkShown(HubPages.BATTERY_MODES, "charge_limit"::equals), "A row of another page doesn't count");
        require(ControlsPages.linkShown(HubPages.HOME, id -> false), "A page these rows don't build keeps its link");
        // The GT3 Max's Assists page shows its three Experimental entries and kick-start speed's row or entry, so its link shows.
        DeviceDescriptor gt3 = OemControlsTest.model("24182", "ES");
        require(ControlsPages.linkShown(HubPages.CONTROLS_ASSISTS, id -> shownOn(gt3, id)), "GT3 Max Assists link");
        require(ControlsPages.linkShown(HubPages.CONTROLS_LIMITS, id -> shownOn(gt3, id)), "GT3 Max Speed limits link (the cap's entry)");
        require(ControlsPages.linkShown(HubPages.BATTERY_ESTIMATES, id -> shownOn(gt3, id)), "Estimates & charger always has its own rows");
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
