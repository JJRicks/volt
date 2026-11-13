package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which scooter rows each Hub page shows, and in what order (hub layout plan, PR 3). Pure, so the JVM suite tests it.
 *
 * <p>Rows are named by id: a feature key ({@link ScooterCommands}), one of {@link ScooterMenuLayout}'s hand-built row
 * ids, {@link #CHARGE_LIVE}, or a link to another page ({@link #link}). Each page lists its rows in a fixed order,
 * the most used first; controls owners set once go on sub-pages (owner's decision 3: a best guess, adjusted after use,
 * so every order lives in this one table). There is no saved arrangement: the Scooter tab's arrange-and-hide editor
 * is gone (owner's decision 5 in #383, approved in his answers 1 and 5 there), and {@code scooter_menu_v1_<pid>} is no longer read.
 *
 * <p>A "Done by" row follows its parent ({@code timed_charging}, {@code charge_start}) wherever that row goes. A
 * control the model's Lab tries (ModelRegistry.experimental, the list More's Experimental controls is built from) and
 * doesn't offer as an ordinary row shows as an "Experimental" entry in that row's place, which opens its test
 * ({@link #labEntries}, {@link #labEntryShown}). A link to a sub-page shows only while that page has a row or an entry
 * to show ({@link #linkShown}).
 */
final class ControlsPages {
    private ControlsPages() { }

    /** The live charging row (ChargeLiveRow); it has no menu id of its own. */
    static final String CHARGE_LIVE = "charge_live";
    /**
     * Sub-pages a page links to that another screen builds, not these rows (hub layout plan, PR 4): Battery's
     * Charge history & recordings is MoreScreen's Charging section.
     */
    static final List<String> HOSTED_ELSEWHERE = Collections.unmodifiableList(Arrays.asList(HubPages.BATTERY_HISTORY));
    /** Prefix of a row that opens another page; the rest is the page id. */
    static final String LINK = "link:";
    /** Row ids no page shows any more, each still reachable elsewhere (the plan's "retired" list). */
    static final List<String> RETIRED = Collections.unmodifiableList(Arrays.asList(
            ScooterMenuLayout.SCOOTER_NAME,   // Rename: More › My scooters, Home's edit mode, Quick access "section:Profile"
            ScooterMenuLayout.LIGHT_ZONES,    // replaced by the Line lights and Star lights links
            "maintenance_reminders",          // MaintenanceScreen.ROW: Service › Maintenance reminders is a page now
            ScooterMenuLayout.SECURITY_LINK)); // Security is a subject page now (PIN, proximity unlock and pairing are on it)
    /**
     * Pages whose links the Scooter tab's index search finds as results of their own: Line lights and Star lights replaced
     * the zones row ({@link ScooterMenuLayout#LIGHT_ZONES}), and their links carry its search words ("zones", "ambient").
     */
    static final List<String> SEARCHED_LINKS = Collections.unmodifiableList(Arrays.asList(HubPages.LIGHTS_LINE, HubPages.LIGHTS_STAR));
    /**
     * Pages whose lock bar above the rows already has Connect while disconnected (Security): their last-seen banner leaves
     * out its own Connect pill, and More's Security section on the page its full-width Connect, so the page shows one
     * Connect as every other page does (#387 review).
     */
    static final List<String> CONNECT_ABOVE = Collections.unmodifiableList(Arrays.asList(HubPages.SECURITY));
    /** Controls never placed as rows (as before the Hub): the lock bar, the handlebar PIN and proximity unlock live elsewhere. */
    static final List<String> NEVER = Collections.unmodifiableList(Arrays.asList("lock", "pin", "proximity_key"));

    /** One page's rows. */
    static final class Spec {
        final String page;
        /** Row ids in the order shown. */
        final List<String> rows;
        /** The UT5 MAX top speed card leads the page (another model's maximum_speed table is an ordinary row). */
        final boolean topSpeed;
        /** The page is the Scooter tab's index: a search field over every row, and no rows of its own. */
        final boolean search;

        Spec(String page, List<String> rows, boolean topSpeed, boolean search) {
            this.page = page; this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.topSpeed = topSpeed; this.search = search;
        }

        boolean includes(String id) { return id != null && rows.contains(id); }
        /** Position of a row in the page, or -1. */
        int indexOf(String id) { return rows.indexOf(id); }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private static void put(String page, boolean topSpeed, boolean search, String... rows) {
        SPECS.put(page, new Spec(page, Arrays.asList(rows), topSpeed, search));
    }

    static String link(String page) { return LINK + page; }
    static boolean isLink(String id) { return id != null && id.startsWith(LINK); }
    /** The page a link row opens; null for any other row. */
    static String linkTarget(String id) { return isLink(id) ? id.substring(LINK.length()) : null; }

    static {
        put(HubPages.SCOOTER, false, true);
        put(HubPages.CONTROLS, true, false,
                "maximum_speed", "cruise_control", "energy_recovery", "traction_control", "acceleration", "dual_drive",
                link(HubPages.CONTROLS_ASSISTS), link(HubPages.CONTROLS_LIMITS));
        put(HubPages.CONTROLS_ASSISTS, false, false, "parking_assist", "hill_start_assist", "hill_descent_control", "start_speed");
        put(HubPages.CONTROLS_LIMITS, false, false, "speed_limit", "drive_speed_limit", "overspeed_control");
        put(HubPages.BATTERY, false, false,
                "timed_charging", "charge_start", "charge_limit", CHARGE_LIVE, ScooterMenuLayout.CHARGE_ALERT, ScooterMenuLayout.LOW_ALERT,
                link(HubPages.BATTERY_MODES), link(HubPages.BATTERY_ESTIMATES), link(HubPages.BATTERY_HISTORY));
        put(HubPages.BATTERY_MODES, false, false, "low_power", "long_range_mode");
        put(HubPages.BATTERY_ESTIMATES, false, false, ScooterMenuLayout.CHARGER, "range_algorithm", ScooterMenuLayout.SCHEDULE_TEST);
        ArrayList<String> lights = new ArrayList<>(Arrays.asList(ScooterMenuLayout.HEADLIGHT_INFO, "headlight", "auto_sensor"));
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) lights.add(f.key);
        lights.addAll(Arrays.asList(link(HubPages.LIGHTS_LINE), link(HubPages.LIGHTS_STAR),
                "turn_sound", "prompt_tone", "display_units", ScooterMenuLayout.CLOCK_SYNC));
        put(HubPages.LIGHTS, false, false, lights.toArray(new String[0]));
        put(HubPages.LIGHTS_LINE, false, false, family("line_"));
        put(HubPages.LIGHTS_STAR, false, false, family("star_"));
        // The Security page puts the lock bar above this row and More's Security section (PIN, proximity unlock, pairing) below it.
        put(HubPages.SECURITY, false, false, "lock_expiry");
    }

    /** A light family's controls (line_, star_), in the catalog's order. */
    private static String[] family(String prefix) {
        ArrayList<String> keys = new ArrayList<>();
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) if (f.key.startsWith(prefix)) keys.add(f.key);
        return keys.toArray(new String[0]);
    }

    /** The page's rows, or null for a page these rows don't build. */
    static Spec spec(String page) { return page == null ? null : SPECS.get(page); }

    /** Every page with rows, in a stable order. */
    static List<String> pages() { return Collections.unmodifiableList(new ArrayList<>(SPECS.keySet())); }

    /**
     * The page's controls that get an "Experimental" entry on this model: each of its feature rows the model's Lab list
     * contains (modelLab: ModelRegistry.experimental for the device), in the page's order. Links and hand-built rows never do.
     */
    static List<String> labEntries(Spec spec, List<String> modelLab) {
        ArrayList<String> out = new ArrayList<>();
        if (spec == null || modelLab == null) return out;
        for (String id : spec.rows) if (!isLink(id) && modelLab.contains(id)) out.add(id);
        return out;
    }

    /**
     * Whether a control's "Experimental" entry shows, exactly as More's Experimental controls lists it: the model's Lab
     * list holds it and its experimental decision (BleController.experimentalDecision) is a Lab try; never while the page
     * shows it as an ordinary row.
     */
    static boolean labEntryShown(boolean ordinaryRow, boolean inModelLab, boolean experimentalDecision) {
        return !ordinaryRow && inModelLab && experimentalDecision;
    }

    /**
     * Whether a link to a page shows: the page has at least one row (shown: a row id the page would show now, an
     * ordinary row or its Experimental entry) or a link that shows. A page these rows don't build always shows its link.
     */
    static boolean linkShown(String page, java.util.function.Predicate<String> shown) {
        return linkShown(page, shown, new java.util.HashSet<>());
    }

    private static boolean linkShown(String page, java.util.function.Predicate<String> shown, java.util.Set<String> seen) {
        Spec spec = spec(page);
        if (spec == null) return true;
        if (!seen.add(page)) return false;
        for (String id : spec.rows) {
            String target = linkTarget(id);
            if (target != null ? linkShown(target, shown, seen) : shown.test(id)) return true;
        }
        return false;
    }

    /**
     * Where a page's links to its sub-pages start their own card (the owner's Layout Plan: the rows in one card, then
     * "More controls", Battery modes and the other sub-pages in a second card under it): the index in spec.rows from
     * which every row is a link. rows.size() keeps one card: no trailing links, links in the middle of the rows (Lights
     * & sound's Line and Star lights), or a page of links only.
     */
    static int linksCardFrom(Spec spec) {
        if (spec == null) return 0;
        int from = spec.rows.size();
        while (from > 0 && isLink(spec.rows.get(from - 1))) from--;
        return from == 0 ? spec.rows.size() : from;
    }

    /**
     * Whether a page's rows carry their icon tiles. The Layout Plan draws them on the Controls rows and leaves them off
     * the Battery & charging page (its CHARGING card and the links under it).
     */
    static boolean iconTiles(String page) { return !HubPages.BATTERY.equals(page); }

    /** The page that shows a row (a "Done by" row's parent decides for it); null when no page does. */
    static String pageOf(String id) {
        for (Spec spec : SPECS.values()) if (spec.includes(id)) return spec.page;
        return null;
    }
}
