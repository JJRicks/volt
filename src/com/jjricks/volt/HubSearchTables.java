package com.jjricks.volt;

/**
 * What Hub search indexes besides feature rows and readings (hub layout plan 1.12): the scooter menu's hand-built
 * rows, the settings rows and the pages themselves. HubText.controlRowTitle and HubText.settingTitle (part 10)
 * switch on the ids to give each row its existing on-screen name; this table gives each id the page that holds it
 * and the English words search matches (part 12). Every id is the one the row already has (its ScooterMenuLayout
 * id or its view tag), so saved menu layouts, validations and TalkBack keep finding the same rows, and search can
 * bring the row on screen by it. Pure, so the JVM suite tests it (tests/l10n/HubSearchTest.java).
 *
 * <p>The English words are search keys, never shown: every language also matches the names the rows show.
 */
final class HubSearchTables {
    private HubSearchTables() {}

    // Scooter menu rows built by hand (MainActivity's controls menu). Feature rows are found by their feature key.
    static final String ROW_HEADLIGHT_INFO = ScooterMenuLayout.HEADLIGHT_INFO;
    static final String ROW_CLOCK_SYNC = ScooterMenuLayout.CLOCK_SYNC;
    static final String ROW_SCHEDULE_TEST = ScooterMenuLayout.SCHEDULE_TEST;
    static final String ROW_CHARGER = ScooterMenuLayout.CHARGER;
    static final String ROW_CHARGE_ALERT = ScooterMenuLayout.CHARGE_ALERT;
    static final String ROW_LOW_ALERT = ScooterMenuLayout.LOW_ALERT;
    static final String ROW_SECURITY_LINK = ScooterMenuLayout.SECURITY_LINK;
    static final String ROW_SCOOTER_NAME = ScooterMenuLayout.SCOOTER_NAME;
    static final String ROW_TOP_SPEED = ScooterMenuLayout.TOP_SPEED;
    /** Live charging: not a menu layout row, so its view tag (MainActivity's charge_live card). */
    static final String ROW_CHARGE_LIVE = "charge_live";
    /**
     * Maintenance's row: its menu layout id and view tag, the value of MaintenanceScreen.ROW. Spelled out because
     * HubText switches on it and the JVM suite compiles HubText without Android (MaintenanceScreen needs it);
     * tests/l10n/HubSearchTextTest.java checks the two stay equal.
     */
    static final String ROW_MAINTENANCE = "maintenance_reminders";
    /** The "Done by" row below scheduled charging: no menu layout id (it follows its parent row), so its view tag. */
    static final String ROW_CHARGE_DONE_BY = "charge-done-by";

    /** Every hand-built row search lists, in the menu's order. */
    static final String[] ROWS = {
            ROW_HEADLIGHT_INFO, ROW_CLOCK_SYNC, ROW_SCHEDULE_TEST, ROW_CHARGE_DONE_BY, ROW_CHARGER,
            ROW_CHARGE_ALERT, ROW_LOW_ALERT, ROW_CHARGE_LIVE, ROW_SECURITY_LINK, ROW_SCOOTER_NAME, ROW_MAINTENANCE, ROW_TOP_SPEED,
    };

    // Settings rows, by the view tag each already carries: rows of MoreScreen's sections (on Settings and its pages, and
    // Service › Reports for the issue report), and Ride › Riding mode for the riding-mode switches (part 5 moved them
    // there and removed More's riding group, so that group's tag is not listed).
    static final String SETTING_HOME_STYLE = "home-style-menu";
    static final String SETTING_UNITS = "preference-units-menu";
    static final String SETTING_DISPLAY = "preference-display-options";
    /**
     * The Haptic feedback switch in the display card: a tag of its own, so search names it apart from Text size.
     * Part 11 sets it on the switch in MoreScreen (a code change this text part cannot carry).
     */
    static final String SETTING_HAPTICS = "preference_haptics";
    static final String SETTING_LANGUAGE = "preference_language";
    static final String SETTING_RIDING_AUTO_OPEN = "preference_riding_auto_open";
    static final String SETTING_RIDING_AUTO_RECORD = "preference_riding_auto_record";
    static final String SETTING_RIDING_HIDE_STATUS_BAR = "preference_riding_hide_status_bar";
    static final String SETTING_RIDING_CLOCK_SECONDS = "preference_riding_clock_seconds";
    static final String SETTING_RIDING_BRIGHTNESS = "preference_riding_brightness";
    static final String SETTING_RIDING_NIGHT = "preference_riding_night";
    static final String SETTING_UPDATE_AUTO = "update_auto";
    static final String SETTING_CHECK_UPDATES = "check-updates";
    static final String SETTING_BACKUP = "data-backup";
    static final String SETTING_REPORT_ISSUE = "more-report-issue";
    static final String SETTING_DEVELOPER = "developer-options-menu";

    /** Every settings row search lists. */
    static final String[] SETTINGS = {
            SETTING_HOME_STYLE, SETTING_UNITS, SETTING_DISPLAY, SETTING_HAPTICS, SETTING_LANGUAGE,
            SETTING_RIDING_AUTO_OPEN, SETTING_RIDING_AUTO_RECORD, SETTING_RIDING_HIDE_STATUS_BAR, SETTING_RIDING_CLOCK_SECONDS,
            SETTING_RIDING_BRIGHTNESS, SETTING_RIDING_NIGHT, SETTING_UPDATE_AUTO, SETTING_CHECK_UPDATES, SETTING_BACKUP,
            SETTING_REPORT_ISSUE, SETTING_DEVELOPER,
    };

    /**
     * The page that holds a hand-built row. Rows still on a scooter page are found in ControlsPages; the rows no
     * scooter page shows any more (ControlsPages.RETIRED), the "Done by" row (it follows its parent) and the top speed
     * card (it leads Controls) are named here.
     */
    static String rowPage(String row) {
        if (row == null) return null;
        switch (row) {
            case ROW_SECURITY_LINK: return HubPages.SECURITY;
            case ROW_SCOOTER_NAME: return HubPages.SETTINGS_SCOOTERS;
            case ROW_MAINTENANCE: return HubPages.SERVICE_MAINTENANCE;
            case ROW_CHARGE_DONE_BY: return HubPages.BATTERY;
            // The UT5 MAX top speed card leads Controls (ControlsPages.Spec.topSpeed), outside its rows.
            case ROW_TOP_SPEED: return HubPages.CONTROLS;
            default: return ControlsPages.pageOf(row);
        }
    }

    /**
     * The id search asks the row's page to bring on screen: the row's own id, or null where the page is the row's
     * whole content (Security, My scooters, Maintenance reminders).
     */
    static String rowAnchor(String row) {
        if (row == null || ControlsPages.RETIRED.contains(row)) return null;
        return row;
    }

    /** A hand-built row's English search words (what the Scooter tab's search matched); null for an unknown id. */
    static String rowWords(String row) {
        if (row == null) return null;
        switch (row) {
            case ROW_HEADLIGHT_INFO: return "headlight front lamp power button";
            case ROW_CLOCK_SYNC: return "sync scooter clock scheduled charging time timezone";
            case ROW_SCHEDULE_TEST: return "test scheduled charging check scooter clock schedule resume";
            case ROW_CHARGE_DONE_BY: return "charge done by finish charging deadline time";
            case ROW_CHARGER: return "charger for estimates automatic auto stock high speed charging time remaining";
            case ROW_CHARGE_ALERT: return "charge alert battery alerts level reached full charged notify notification alarm sound";
            case ROW_LOW_ALERT: return "low battery alert battery alerts notify notification alarm sound";
            case ROW_CHARGE_LIVE: return "live charging lock screen notification card";
            case ROW_SECURITY_LINK: return "pin password proximity unlock pairing bluetooth bond security";
            case ROW_SCOOTER_NAME: return "scooter name rename profile";
            case ROW_MAINTENANCE: return "maintenance reminders service check tyre tire brakes";
            case ROW_TOP_SPEED: return "top speed maximum speed sport mode limit mph km/h";
            default: return null;
        }
    }

    /** The page that holds a settings row: Settings, except the riding-mode switches (Ride › Riding mode) and the issue report (Service › Reports). */
    static String settingPage(String tag) {
        if (tag == null) return null;
        switch (tag) {
            case SETTING_RIDING_AUTO_OPEN: case SETTING_RIDING_AUTO_RECORD: case SETTING_RIDING_HIDE_STATUS_BAR:
            case SETTING_RIDING_CLOCK_SECONDS: case SETTING_RIDING_BRIGHTNESS: case SETTING_RIDING_NIGHT:
                return HubPages.RIDE_RIDING;
            case SETTING_REPORT_ISSUE: return HubPages.SERVICE_REPORTS;
            default: return java.util.Arrays.asList(SETTINGS).contains(tag) ? HubPages.SETTINGS : null;
        }
    }

    /** A settings row's English search words; null for an unknown tag. */
    static String settingWords(String tag) {
        if (tag == null) return null;
        switch (tag) {
            case SETTING_HOME_STYLE: return "home style clean widgets dashboard layout";
            case SETTING_UNITS: return "units miles kilometres kilometers km mi temperature celsius fahrenheit";
            case SETTING_DISPLAY: return "text size font large display haptic haptics vibration vibrate feedback";
            case SETTING_LANGUAGE: return "language translation locale";
            case SETTING_RIDING_AUTO_OPEN: return "riding mode open automatically auto start";
            case SETTING_RIDING_AUTO_RECORD: return "record rides automatically auto record";
            case SETTING_RIDING_HIDE_STATUS_BAR: return "riding mode hide status bar full screen";
            case SETTING_RIDING_CLOCK_SECONDS: return "riding mode clock seconds time";
            case SETTING_RIDING_BRIGHTNESS: return "riding mode brightness screen";
            case SETTING_RIDING_NIGHT: return "riding mode night vision dark red";
            case SETTING_UPDATE_AUTO: return "updates automatic update check";
            case SETTING_CHECK_UPDATES: return "check for updates version";
            case SETTING_BACKUP: return "back up backup restore export import";
            case SETTING_REPORT_ISSUE: return "report issue bug problem email";
            case SETTING_DEVELOPER: return "developer options simulate simulation debug";
            default: return null;
        }
    }

    /** A page's English search words besides its title; "" for a page with none. */
    static String pageWords(String page) {
        if (page == null) return "";
        switch (page) {
            case HubPages.RIDE: return "ride rides riding recent";
            case HubPages.RIDE_RIDES: return "all rides compare history list";
            case HubPages.RIDE_RECORDING: return "recording record rides automatically gps map sensors keep awake";
            case HubPages.RIDE_RIDING: return "riding mode display brightness night";
            case HubPages.BATTERY: return "battery charging charge limit";
            case HubPages.BATTERY_MODES: return "battery modes low battery mode long range";
            case HubPages.BATTERY_ESTIMATES: return "estimates range charger capacity";
            case HubPages.BATTERY_HISTORY: return "charge history charge recordings";
            case HubPages.CONTROLS: return "controls drive mode speed cruise";
            case HubPages.CONTROLS_ASSISTS: return "assists hill start parking walk";
            case HubPages.CONTROLS_LIMITS: return "speed limits overspeed";
            case HubPages.LIGHTS: return "lights sound headlight";
            // Each light family's own words, its title among them (the zones row both replaced matched "zones" for
            // both): "star" doesn't list Line lights, and "line lights" finds its page (#404 review).
            case HubPages.LIGHTS_LINE: return "line lights zones ambient pattern color colour boost";
            case HubPages.LIGHTS_STAR: return "star lights zones ambient pattern color colour boost";
            case HubPages.SECURITY: return "security lock pin proximity pairing";
            case HubPages.SERVICE: return "service maintenance diagnostics";
            case HubPages.SERVICE_MAINTENANCE: return "maintenance reminders service check";
            case HubPages.SERVICE_EXPERIMENTAL: return "experimental lab try test controls";
            case HubPages.SERVICE_COMPATIBILITY: return "compatibility test check model";
            case HubPages.SERVICE_REPORTS: return "reports report issue";
            case HubPages.DATA: return "data live stats readings charts diagnostics";
            case HubPages.DATA_STORAGE: return "data history storage export backup live stats detail density compact dense comfortable";
            case HubPages.SETTINGS: return "settings preferences options";
            case HubPages.SETTINGS_HOME: return "home style clean widgets";
            case HubPages.SETTINGS_UNITS: return "units miles kilometres";
            case HubPages.SETTINGS_SCOOTERS: return "my scooters scooter connection connect auto connect automatically background stay connected telemetry refresh";
            case HubPages.SETTINGS_ABOUT: return "about permissions version licenses";
            case HubPages.SETTINGS_DEVELOPER: return "developer options simulate";
            default: return "";
        }
    }
}
