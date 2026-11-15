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
    /**
     * In dp: where a phone dock's items start from each side of the window (the old floating tab bar's margin; the dock's
     * sheet now runs edge to edge and keeps this as its side padding), the dock's inner padding, and how far its items sit
     * above the screen's bottom edge or the navigation bar (the old bar's bottom margin, now part of the sheet).
     */
    public static final int DOCK_SIDE_DP = 14, DOCK_PADDING_DP = 4, DOCK_LIFT_DP = 10;

    /** The room a phone's dock gives its items in a window widthDp wide: the window less the dock's side padding. */
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
     * Where Widgets Home's dock sits: its top, in window pixels, for a window whose bottom edge is windowBottom and a dock
     * dockHeight tall. The dock's sheet runs down to the screen's bottom edge, under the navigation bar or gesture handle
     * (the contributor's decision, 15 Nov, #383, as the Layout Plan's dock sheet), so nothing scrolls by beneath it; it is
     * pinned there at all times (14 Nov): it never lifts above the Lock bar and never steps aside for it, because on
     * Widgets the Lock bar is one of the rider's own widgets and they can move it. Nothing about the page's widgets goes
     * into this.
     */
    public static int dockTop(int windowBottom, int dockHeight) { return windowBottom - dockHeight; }

    /**
     * The dock sheet's bottom padding: its inner padding, the lift and the navigation inset (window pixels), so its items
     * sit lift above the navigation bar, where the old floating bar's items sat, while its surface runs on under the bar.
     */
    public static int dockBottomPadding(int padding, int lift, int inset) { return padding + lift + Math.max(0, inset); }

    /**
     * The bottom padding Widgets Home's scroller needs so every widget, the Lock bar included, can be scrolled fully clear of
     * the pinned dock with gap to spare (window pixels). The scroller ends at the navigation inset; the dock, inset
     * included, is dockHeight tall, so it rises dockHeight - inset above the scroller's bottom. Scrolled to its end, the
     * page's last widget then ends at the scroller's bottom less this, which is gap above dockTop.
     */
    public static int dockClearance(int dockHeight, int inset, int gap) { return Math.max(0, dockHeight - Math.max(0, inset)) + gap; }

    /**
     * Where the bottom of the scroller's content sits once it is scrolled to its end, in the frame's pixels: a scroller
     * frameBottom tall with paddingBottom under its content. Content shorter than the scroller ends higher still.
     */
    public static int scrolledEndBottom(int frameBottom, int paddingBottom) { return frameBottom - paddingBottom; }

    /**
     * Whether a move on the dock is its upward swipe (it opens the All sheet at once): more than twice the touch slop
     * upward, and more up than across. upDp is how far the finger moved up (negative when down), acrossDp how far sideways.
     */
    public static boolean dockSwipe(float up, float across, float slop) {
        return up > slop * 2 && up > across;
    }

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

    /** Settings and its sub-pages: the app's own pages, which are not about the scooter. */
    public static boolean appPage(String id) { return SETTINGS.equals(subjectOf(canonical(id))); }

    /** headerConnectionLine's answers: no line and no room for it; its room kept, blank; the line shown. */
    public static final int LINE_GONE = 0, LINE_ROOM = 1, LINE_SHOWN = 2;

    /**
     * The connection line in a page's header ("Disconnected · last seen 5 min ago" between the back link and the title;
     * the contributor's decision, 14 Nov, #383): on a page it shows only while the scooter is not connected (disconnected,
     * connecting, searching, Bluetooth off, a held release: every state but the plain connected one), so a connected
     * page's header is the back link and the title, as in the Layout Plan's page mock-ups. Settings and its sub-pages
     * never show it. Home keeps its own line above the scooter's name (LINE_SHOWN, not built from this).
     * connected: the scooter is connected and ready. roomHeld: the line has shown on this page since it opened; then a
     * connection made while the page is open blanks the line but keeps its room (LINE_ROOM), so the title keeps its place
     * until the next page opens. A connection lost on an open page shows the line at once.
     */
    public static int headerConnectionLine(String page, boolean connected, boolean roomHeld) {
        String id = canonical(page);
        if (HOME.equals(id)) return LINE_SHOWN;
        if (appPage(id)) return LINE_GONE;
        if (!connected) return LINE_SHOWN;
        return roomHeld ? LINE_ROOM : LINE_GONE;
    }

    /**
     * headerConnectionLine with the service's state: what a page's header shows while ScooterService is bound or not.
     * serviceBound: the activity holds the service. bindCanCome: unbound, a bind will follow (Nearby devices permission is
     * granted, so ensureService binds), as after an activity recreation, where the page is built before the service binds:
     * then the connection is not known yet, and the line waits, gone and holding no room, for the rebuild the binding
     * makes, so a connected page never keeps a blank line from that first build; a line that has already shown on the
     * page (roomHeld) stays shown meanwhile, as the scooter is still not connected. With no bind to come (Nearby devices
     * not granted: the service never binds) the scooter is not connected and the line shows, with its tap into the
     * connection details and their button that grants the permission. connected counts only while the service is bound.
     */
    public static int pageConnectionLine(String page, boolean serviceBound, boolean bindCanCome, boolean connected, boolean roomHeld) {
        String id = canonical(page);
        if (!serviceBound && bindCanCome && !HOME.equals(id) && !appPage(id)) return roomHeld ? LINE_SHOWN : LINE_GONE;
        return headerConnectionLine(page, serviceBound && connected, roomHeld);
    }

    /** Ui icon key for a page's subject: the Layout Plan's glyphs (Battery & charging a bolt, Controls bars, Lights & sound a bulb, Service a wrench). */
    public static String icon(String id) {
        switch (subjectOf(id)) {
            case RIDE: return "route";
            case BATTERY: return "bolt";
            case CONTROLS: return "bars";
            case LIGHTS: return "bulb";
            case SECURITY: return "shield";
            case SERVICE: return "wrench";
            case DATA: return "chart";
            case SETTINGS: return "gear";
            case SEARCH: return "search";
            default: return "home";
        }
    }

    /** Whether riding mode may open over this page by itself (AutoStartPolicy); unknown ids count as busy. */
    public static boolean clearForRiding(String id) { return id != null && CLEAR_FOR_RIDING.contains(id); }

    /**
     * The page that holds one of More's sections (MoreScreen.SECTIONS, Units, both Compatibility ids, and the Charging,
     * Reports and Recording sections the subject pages show); Settings otherwise.
     */
    public static String fromMoreSection(String section) {
        String page = section == null ? null : MORE_SECTIONS.get(section);
        return page == null ? SETTINGS : page;
    }
}
