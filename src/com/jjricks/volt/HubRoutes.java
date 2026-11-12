package com.jjricks.volt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each saved Quick access id leads in the Hub layout (hub layout plan, PR 4). Pure, so the JVM suite tests it
 * (tests/HubRoutesTest.java). Ids saved in owners' dashboards, slots and transfers never change and never stop
 * resolving. The owner's answer 3 (#383): a shortcut to a whole settings page (Settings, Security, Experimental
 * controls, My scooters, App preferences and the like) opens that page; a group of quick controls changed in a moment
 * (Lights, Ride settings with maximum speed and energy recovery, Battery settings, Line lights, Star lights, Dashboard
 * & sound) keeps its pop-up sheet over Home; every other id keeps its direct action in MainActivity.quickAction (a
 * single control's id keeps its own pop-up editor). Routes only ever name HubPages ids.
 */
final class HubRoutes {
    private HubRoutes() { }

    /** Quick access ids that open a page, and the page. */
    private static final Map<String, String> PAGES = new LinkedHashMap<>();
    static {
        // "Settings" (the legacy default) opened every scooter setting, Security and App preferences: the Scooter tab holds them.
        PAGES.put("controls", HubPages.SCOOTER);
        PAGES.put("stats", HubPages.DATA);
        PAGES.put("rides", HubPages.RIDE);
        PAGES.put("section:Security", HubPages.SECURITY);
        PAGES.put("more:App", HubPages.SETTINGS);
        PAGES.put("more:Scooters", HubPages.SETTINGS_SCOOTERS);
        PAGES.put("more:Lab", HubPages.SERVICE_EXPERIMENTAL);
        PAGES.put("more:Data", HubPages.DATA_STORAGE);
        PAGES.put("more:About", HubPages.SETTINGS_ABOUT);
    }

    /** Quick access ids that keep their quick-control sheet over Home: Lights, and a scooter settings group. */
    static final java.util.List<String> SHEETS = Collections.unmodifiableList(java.util.Arrays.asList(
            "lights", "section:Ride", "section:Battery", "section:Line lights", "section:Star lights", "section:Dashboard & sound"));

    /** The quick-control sheet a Quick access id opens (MainActivity.showQuickSettings' section), or null. */
    static String sheetFor(String id) {
        if (id == null || !SHEETS.contains(id)) return null;
        return id.startsWith("section:") ? id.substring("section:".length()) : "Lights";
    }

    /** Ids that keep an action of their own (rename, the charge schedule's shortcuts); every "feature:" id is one too. */
    static final java.util.List<String> ACTIONS = Collections.unmodifiableList(java.util.Arrays.asList(
            "section:Profile", "charge_done_by", "charge_done_time"));

    /** The page a Quick access id opens, or null when the id opens a sheet (SHEETS) or runs an action (ACTIONS, a control's "feature:" id, an unknown id). */
    static String pageFor(String id) { return id == null ? null : PAGES.get(id); }

    /** Every id that opens a page, in the catalog's order. */
    static Map<String, String> pages() { return Collections.unmodifiableMap(PAGES); }

    /**
     * A control's shortcut that can't be a direct toggle or editor on Home: one the Lab tries on this model opens its
     * test in Service › Experimental controls (lab: the resolver's variant is a Lab try); the handlebar PIN and the
     * other sensitive controls, and proximity unlock, open Security. Null for the rest, which keep their toggle or editor.
     */
    static String forFeature(ScooterCommands.Feature feature, boolean lab) {
        if (lab) return HubPages.SERVICE_EXPERIMENTAL;
        return feature != null && securityControl(feature) ? HubPages.SECURITY : null;
    }

    /** The controls whose rows live on Security, so their shortcuts open it. */
    static boolean securityControl(ScooterCommands.Feature feature) {
        return feature.sensitive || feature.key.equals("proximity_key");
    }

    /**
     * The More section a Settings or Data page shows as its body (hub layout plan, PR 5), or null for a page MainActivity
     * builds otherwise. Settings itself is still More's tab while the tabs remain: a shortcut to it (more:App) opens More
     * on App preferences, as it always has.
     */
    static String moreSectionOf(String page) {
        if (page == null) return null;
        switch (page) {
            case HubPages.SETTINGS: return "App";
            case HubPages.SETTINGS_SCOOTERS: return "Scooters";
            case HubPages.SETTINGS_UNITS: return "Units";
            case HubPages.SETTINGS_DEVELOPER: return "Developer";
            case HubPages.DATA_STORAGE: return "Data";
            default: return HubPages.SETTINGS_ABOUT.equals(page) ? "About" : null;
        }
    }
}
