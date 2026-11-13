package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Hub's page ids, their tree under Home, and the pure rules about them (hub layout plan, PR 1).
 * Ids are lower-case and never shown: titles come from the catalog. No Android imports, so the JVM suite tests it.
 * MainActivity's page stack (HubNav) only ever holds ids from {@link #ALL}.
 */
public final class HubPages {
    private HubPages() { }

    public static final String HOME = "home";
    /**
     * The Scooter tab's index page while the tabs remained (parts 3-10). Not a page since the Hub replaced the tabs
     * (PR 11): a saved "scooter" (an older Bundle, last_page) opens Home, whose subjects it listed.
     */
    public static final String SCOOTER = "scooter";
    public static final String RIDE = "ride", RIDE_RIDES = "ride.rides", RIDE_RECORDING = "ride.recording", RIDE_RIDING = "ride.riding";
    public static final String BATTERY = "battery", BATTERY_MODES = "battery.modes", BATTERY_ESTIMATES = "battery.estimates",
            BATTERY_HISTORY = "battery.history";
    public static final String CONTROLS = "controls", CONTROLS_ASSISTS = "controls.assists", CONTROLS_LIMITS = "controls.limits";
    public static final String LIGHTS = "lights", LIGHTS_LINE = "lights.line", LIGHTS_STAR = "lights.star";
    public static final String SECURITY = "security";
    public static final String SERVICE = "service", SERVICE_MAINTENANCE = "service.maintenance",
            SERVICE_EXPERIMENTAL = "service.experimental", SERVICE_COMPATIBILITY = "service.compatibility",
            SERVICE_REPORTS = "service.reports";
    public static final String DATA = "data", DATA_STORAGE = "data.storage";
    public static final String SETTINGS = "settings", SETTINGS_HOME = "settings.home", SETTINGS_UNITS = "settings.units",
            SETTINGS_SCOOTERS = "settings.scooters", SETTINGS_DEVELOPER = "settings.developer";
    /** Written in two parts: ControlsConsumersTest counts a dot before "about" in src as an English series read. */
    public static final String SETTINGS_ABOUT = "settings." + "about";
    public static final String SEARCH = "search";

    /** Each page and its parent, in a stable order (Home first, then each subject followed by its sub-pages). */
    private static final Map<String, String> PARENTS = new LinkedHashMap<>();
    static {
        PARENTS.put(HOME, null);
        PARENTS.put(RIDE, HOME); PARENTS.put(RIDE_RIDES, RIDE); PARENTS.put(RIDE_RECORDING, RIDE); PARENTS.put(RIDE_RIDING, RIDE);
        PARENTS.put(BATTERY, HOME); PARENTS.put(BATTERY_MODES, BATTERY); PARENTS.put(BATTERY_ESTIMATES, BATTERY);
        PARENTS.put(BATTERY_HISTORY, BATTERY);
        PARENTS.put(CONTROLS, HOME); PARENTS.put(CONTROLS_ASSISTS, CONTROLS); PARENTS.put(CONTROLS_LIMITS, CONTROLS);
        PARENTS.put(LIGHTS, HOME); PARENTS.put(LIGHTS_LINE, LIGHTS); PARENTS.put(LIGHTS_STAR, LIGHTS);
        PARENTS.put(SECURITY, HOME);
        PARENTS.put(SERVICE, HOME); PARENTS.put(SERVICE_MAINTENANCE, SERVICE); PARENTS.put(SERVICE_EXPERIMENTAL, SERVICE);
        PARENTS.put(SERVICE_COMPATIBILITY, SERVICE); PARENTS.put(SERVICE_REPORTS, SERVICE);
        PARENTS.put(DATA, HOME); PARENTS.put(DATA_STORAGE, DATA);
        PARENTS.put(SETTINGS, HOME); PARENTS.put(SETTINGS_HOME, SETTINGS); PARENTS.put(SETTINGS_UNITS, SETTINGS);
        PARENTS.put(SETTINGS_SCOOTERS, SETTINGS); PARENTS.put(SETTINGS_ABOUT, SETTINGS); PARENTS.put(SETTINGS_DEVELOPER, SETTINGS);
        PARENTS.put(SEARCH, HOME);
    }

    /** Every page id, in a stable order. */
    public static final List<String> ALL = Collections.unmodifiableList(new ArrayList<>(PARENTS.keySet()));
    /** The seven subjects, in Home's order. */
    public static final List<String> SUBJECTS = Collections.unmodifiableList(Arrays.asList(RIDE, BATTERY, CONTROLS, LIGHTS, SECURITY, SERVICE, DATA));

    /**
     * The Widgets dock's subjects, most-used first (owner's answer 8 on #383: a portrait dock must not be cramped): when
     * not all seven fit at a comfortable width, the dock shows the first of these, in Home's order, and its "All" item
     * opens the full list. Ride, Battery & charging, Controls and Data come first: the old tab bar's Live Stats and Rides
     * tabs and the Scooter tab's most-visited pages.
     */
    public static final List<String> DOCK_ORDER = Collections.unmodifiableList(Arrays.asList(RIDE, BATTERY, CONTROLS, DATA, LIGHTS, SERVICE, SECURITY));
    /** The dock's "All" item's icon (the old tab bar's More icon). */
    public static final String DOCK_ALL_ICON = "more";
    /** The width one dock item needs at normal text size, in dp: a 48 dp target with room for its one-line label. */
    public static final int DOCK_ITEM_DP = 60;
    /** The dock's margin from each side of a phone's window (the old tab bar's) and its inner padding, in dp. */
    public static final int DOCK_SIDE_DP = 14, DOCK_PADDING_DP = 4;

    /** The room a phone's dock gives its items in a window widthDp wide: the window less the dock's margins and padding. */
    public static int dockRoom(int widthDp) { return widthDp - 2 * DOCK_SIDE_DP - 2 * DOCK_PADDING_DP; }

    /**
     * The subjects the dock shows in widthDp of room at this text scale (app text size times Android's font size, at
     * least 1), in Home's order: all seven when they and the "All" item each get DOCK_ITEM_DP (scaled), else as many of
     * DOCK_ORDER as fit beside "All", and always at least one. Every subject stays one tap away through "All".
     */
    public static List<String> dockSubjects(float widthDp, float textScale) {
        float item = DOCK_ITEM_DP * Math.max(1f, textScale);
        int slots = (int) Math.floor(widthDp / item);
        int count = Math.max(1, Math.min(SUBJECTS.size(), slots - 1));
        List<String> shown = new ArrayList<>();
        for (String subject : SUBJECTS) if (DOCK_ORDER.indexOf(subject) < count) shown.add(subject);
        return Collections.unmodifiableList(shown);
    }
    /**
     * A wide dock's captions (#392's early review, emulator note): true when every subject's full name fits on one line,
     * so each item shows its full name; false when any would wrap, and then every item shows its short name, keeping the
     * row even and the dock one line of text tall. nameWidthsDp: each full name's width in dp at the caption's size;
     * roomDp: the width the page frame gives the dock; items: the dock's items, "All" included; itemMaxDp: a wide item's
     * cap; paddingDp: the dock's inner padding. Each caption has its item's width less 2 dp each side (Ui.nav).
     */
    public static boolean wideDockNames(float[] nameWidthsDp, float roomDp, int items, float itemMaxDp, float paddingDp) {
        if (items <= 0) return false;
        float dock = Math.min(itemMaxDp * items + 2 * paddingDp, roomDp);
        float caption = (dock - 2 * paddingDp) / items - 4;
        for (float width : nameWidthsDp) if (!(width <= caption)) return false;
        return true;
    }

    /**
     * Whether a move on the dock is its upward swipe (it opens the All sheet at once): more than twice the touch slop
     * upward, and more up than across. upDp is how far the finger moved up (negative when down), acrossDp how far sideways.
     */
    public static boolean dockSwipe(float up, float across, float slop) {
        return up > slop * 2 && up > across;
    }

    /**
     * The tab bar's roots, in the bar's order (Scooter was the raised centre button), from parts 1-10. The Hub (PR 11) no
     * longer shows the tab bar or the side rail; MainActivity keeps their code, unused, until the owner has used the Hub and
     * says it can go (owner's answer 8 on #383), and this list with it.
     */
    public static final List<String> TABS = Collections.unmodifiableList(Arrays.asList(HOME, DATA, SCOOTER, RIDE, SETTINGS));

    /**
     * Pages riding mode may open over by itself: no forms, setup flows or sub-pages (the screen's own busy() still counts).
     * Of the scooter subjects only Controls, Battery & charging and Lights & sound (the owner's answer 21 on #383); every
     * other subject, sub-page, form or setup flow blocks it.
     */
    private static final Set<String> CLEAR_FOR_RIDING = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            HOME, RIDE, RIDE_RIDES, DATA, CONTROLS, BATTERY, LIGHTS)));

    /** Page ids saved before the Hub (tab ids in Bundles, last_page and Home's navigate calls), and what they open now. */
    private static final Map<String, String> LEGACY = new LinkedHashMap<>();
    static {
        LEGACY.put("stats", DATA);
        LEGACY.put("rides", RIDE);
        LEGACY.put("more", SETTINGS);
        // The Scooter tab's index (PR 11): its subjects are Home's now.
        LEGACY.put(SCOOTER, HOME);
    }

    /** More's section ids and the page that holds each one. */
    private static final Map<String, String> MORE_SECTIONS = new LinkedHashMap<>();
    static {
        MORE_SECTIONS.put("App", SETTINGS);
        MORE_SECTIONS.put("Scooters", SETTINGS_SCOOTERS);
        MORE_SECTIONS.put("Security", SECURITY);
        MORE_SECTIONS.put("Lab", SERVICE_EXPERIMENTAL);
        MORE_SECTIONS.put("Data", DATA_STORAGE);
        MORE_SECTIONS.put("About", SETTINGS_ABOUT);
        MORE_SECTIONS.put("Developer", SETTINGS_DEVELOPER);
        MORE_SECTIONS.put("Units", SETTINGS_UNITS);
        MORE_SECTIONS.put("Compatibility", SERVICE_COMPATIBILITY);
        MORE_SECTIONS.put("CompatibilityRecovery", SERVICE_COMPATIBILITY);
        // Sections cut out of Developer options and More's index for their subject pages (PR 4).
        MORE_SECTIONS.put("Charging", BATTERY_HISTORY);
        MORE_SECTIONS.put("Reports", SERVICE_REPORTS);
        // Recording switches and ride auto-save, cut out of App preferences and Data & history (PR 5).
        MORE_SECTIONS.put("Recording", RIDE_RECORDING);
    }

    public static boolean known(String id) { return id != null && PARENTS.containsKey(id); }

    /** The page an id opens: legacy ids map to their new home, a known id is itself, anything else is Home. */
    public static String canonical(String id) {
        if (id == null) return HOME;
        String mapped = LEGACY.get(id);
        if (mapped != null) return mapped;
        return known(id) ? id : HOME;
    }

    /** The page Back from a page's own content would show; null for Home and for an unknown id. */
    public static String parent(String id) { return known(id) ? PARENTS.get(id) : null; }

    /** Home first, then each page down to id; just Home for Home or an unknown id. */
    public static List<String> path(String id) {
        ArrayList<String> path = new ArrayList<>();
        for (String at = known(id) ? id : HOME; at != null; at = PARENTS.get(at)) path.add(0, at);
        return path;
    }

    /** Steps below Home: 0 for Home (and unknown ids), 1 for a subject, 2 for a sub-page. */
    public static int depth(String id) { return path(id).size() - 1; }

    /** The page directly under Home that id belongs to (a subject, Settings or Search); Home for Home. */
    public static String subjectOf(String id) {
        List<String> path = path(id);
        return path.size() > 1 ? path.get(1) : HOME;
    }

    /** Ui icon key for a page's subject. */
    public static String icon(String id) {
        switch (subjectOf(id)) {
            case RIDE: return "route";
            case BATTERY: return "battery";
            case CONTROLS: return "speed";
            case LIGHTS: return "lights";
            case SECURITY: return "shield";
            case SERVICE: return "tune";
            case DATA: return "chart";
            case SETTINGS: return "gear";
            case SEARCH: return "search";
            default: return "home";
        }
    }

    /** Whether riding mode may open over this page by itself (AutoStartPolicy); unknown ids count as busy. */
    public static boolean clearForRiding(String id) { return id != null && CLEAR_FOR_RIDING.contains(id); }

    /** The tab that held a page, by its root id in {@link #TABS}: only the kept, unused tab bar and rail code reads it (PR 11). */
    public static String tabOf(String id) {
        String page = canonical(id);
        switch (subjectOf(page)) {
            case HOME: return HOME;
            case DATA: return DATA;
            case RIDE: return RIDE;
            case SETTINGS: return SETTINGS;
            default: return SCOOTER;
        }
    }

    /**
     * The page that holds one of More's sections (MoreScreen.SECTIONS, Units, both Compatibility ids, and the Charging,
     * Reports and Recording sections the subject pages show); Settings otherwise.
     */
    public static String fromMoreSection(String section) {
        String page = section == null ? null : MORE_SECTIONS.get(section);
        return page == null ? SETTINGS : page;
    }
}
