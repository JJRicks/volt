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
    // there and removed More's riding group, so that group's tag is not listed). Record rides automatically is the one
    // riding switch whose Hub home is Ride › Recording settings (Riding mode leaves it out: withoutAutoRecord), where
    // MoreScreen.recording() tags it with the same tag (#393 review, finding 3).
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

    /**
     * The page that holds a settings row: Settings, except the riding-mode switches (Ride › Riding mode), Record rides
     * automatically (Ride › Recording settings) and the issue report (Service › Reports).
     */
    static String settingPage(String tag) {
        if (tag == null) return null;
        switch (tag) {
            case SETTING_RIDING_AUTO_OPEN: case SETTING_RIDING_HIDE_STATUS_BAR:
            case SETTING_RIDING_CLOCK_SECONDS: case SETTING_RIDING_BRIGHTNESS: case SETTING_RIDING_NIGHT:
                return HubPages.RIDE_RIDING;
            case SETTING_RIDING_AUTO_RECORD: return HubPages.RIDE_RECORDING;
            case SETTING_REPORT_ISSUE: return HubPages.SERVICE_REPORTS;
            default: return java.util.Arrays.asList(SETTINGS).contains(tag) ? HubPages.SETTINGS : null;
        }
    }

    /**
     * The view search brings on screen for a settings row: its tag, except Brightness and Night vision, whose tags sit on
     * the cards below their selectors (the reset row and note, the schedule), so the selector itself, tagged
     * "preference-selector-" and its preference key by RidingSettingsMenu.selector (#393 review, note 6). Null for an
     * unknown tag.
     */
    static String settingAnchor(String tag) {
        if (tag == null) return null;
        switch (tag) {
            // RidingBrightness.MODE_KEY and NightSchedule.MODE_KEY (tests/l10n/HubSearchTest.java checks both).
            case SETTING_RIDING_BRIGHTNESS: return "preference-selector-riding_brightness";
            case SETTING_RIDING_NIGHT: return "preference-selector-riding_night_vision";
            default: return settingPage(tag) == null ? null : tag;
        }
    }

    /** A settings row's English search words; null for an unknown tag. */
    static String settingWords(String tag) {
        if (tag == null) return null;
        switch (tag) {
            case SETTING_HOME_STYLE: return "home style clean widgets dashboard layout";
            case SETTING_UNITS: return "units miles kilometres kilometers km mi temperature celsius fahrenheit";
            // Haptic feedback has an entry of its own; Appearance is found through Settings' words (#393 review, note 7).
            case SETTING_DISPLAY: return "text size font large display";
            case SETTING_HAPTICS: return "haptic haptics feedback vibration vibrate vibrations";
            case SETTING_LANGUAGE: return "language translation locale";
            case SETTING_RIDING_AUTO_OPEN: return "riding mode open automatically auto start";
            case SETTING_RIDING_AUTO_RECORD: return "record rides automatically auto record start recording";
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

    /**
     * A page's English search words besides its title; "" for a page with none: its own words (pageOwnWords) and the
     * titles of the rows it always shows that have no search entry of their own (pageRows), so typing the name written
     * on a row finds the page that holds it (#393 emulator check: 45 of 114 settings rows were not found;
     * tests/l10n/HubSearchTest.java fails for a settings row whose title search does not find).
     */
    static String pageWords(String page) {
        StringBuilder words = new StringBuilder(pageOwnWords(page));
        for (String row : pageRows(page)) words.append(words.length() == 0 ? "" : " ").append(row.toLowerCase(java.util.Locale.ROOT));
        return words.toString();
    }

    /**
     * The English titles, exactly as shown, of the rows a page always shows (on the developer simulator's UT5 MAX, in
     * English) that have no search entry of their own; empty for a page with none. HubValidation's search-targets walk
     * (#393 council review, second round) types each title, opens this page's result and requires a row reading that
     * title on it, so a title can't point at a page that no longer holds its row. A row shown only in some states (while a
     * charge records, on one Android version, while disconnected) keeps its words in pageOwnWords instead.
     */
    static String[] pageRows(String page) {
        if (page == null) return new String[0];
        switch (page) {
            case HubPages.RIDE_RIDES: return new String[] {"Compare two rides", "Ride backups"};
            case HubPages.RIDE_RECORDING: return new String[] {"Keep screen awake", "End rides automatically", "Start recording in the background",
                    "Background recording", "Map settings", "GPS route", "Barometer elevation", "Motion sensors"};
            case HubPages.RIDE_RIDING: return new String[] {"Screen rotation"};
            case HubPages.BATTERY_HISTORY: return new String[] {"Record charges automatically", "Export charge recordings"};
            case HubPages.SECURITY: return new String[] {"PIN unlock", "Set or change PIN", "Pair this phone with scooter", "Unlock without phone"};
            case HubPages.SERVICE: return new String[] {"Diagnostics & connection activity"};
            case HubPages.SERVICE_EXPERIMENTAL: return new String[] {"Controls to try", "Command evidence", "Clear try results"};
            case HubPages.DATA: return new String[] {"Favorites"};
            case HubPages.DATA_STORAGE: return new String[] {"Save readings history", "Clear this scooter's history", "Clear all readings history",
                    "Auto-save rides", "Folder"};
            case HubPages.SETTINGS: return new String[] {"Appearance"};
            case HubPages.SETTINGS_HOME: return new String[] {"Show charging automatically", "Show the last ride after riding"};
            case HubPages.SETTINGS_SCOOTERS: return new String[] {"Connect automatically", "Stay connected in background", "Telemetry refresh"};
            case HubPages.SETTINGS_ABOUT: return new String[] {"Nearby devices", "Location", "Notifications", "Online map", "Connection activity",
                    "Licenses & attribution"};
            case HubPages.SETTINGS_DEVELOPER: return new String[] {"Compatibility checker", "Simulate scooter as connected", "Simulated model",
                    "Hide developer options"};
            default: return new String[0];
        }
    }

    /** A page's own English search words (pageWords without its rows' titles); "" for a page with none. */
    static String pageOwnWords(String page) {
        if (page == null) return "";
        switch (page) {
            case HubPages.RIDE: return "ride rides riding recent";
            case HubPages.RIDE_RIDES: return "all rides compare history list";
            case HubPages.RIDE_RECORDING: return "recording record rides automatically gps map sensors auto stop background recording gps route";
            case HubPages.RIDE_RIDING: return "riding mode display brightness night reset volt auto brightness sunset from starts ends";
            case HubPages.BATTERY: return "battery charging charge limit";
            case HubPages.BATTERY_MODES: return "battery modes low battery mode long range";
            case HubPages.BATTERY_ESTIMATES: return "estimates range charger capacity";
            // Record this charge and Stop charge recording show only while charging or recording; Live Update status only
            // on Android 16; Clear this scooter's charge recordings only with recordings saved.
            case HubPages.BATTERY_HISTORY: return "charge history charge recordings record this charge stop charge recording"
                    + " clear this scooter's charge recordings live update status";
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
            case HubPages.SERVICE_COMPATIBILITY: return "compatibility test check model email to volt";
            case HubPages.SERVICE_REPORTS: return "reports report issue";
            case HubPages.DATA: return "data live stats readings charts diagnostics favourites";
            case HubPages.DATA_STORAGE: return "data history storage export backup live stats detail density compact dense comfortable";
            case HubPages.SETTINGS: return "settings preferences options theme dark light follow system";
            case HubPages.SETTINGS_HOME: return "home style clean widgets quick actions";
            case HubPages.SETTINGS_UNITS: return "units miles kilometres";
            case HubPages.SETTINGS_SCOOTERS: return "my scooters scooter connection connect auto connect";
            // View full notices sits under Licenses & attribution; Connect selected scooter shows only while not connected.
            case HubPages.SETTINGS_ABOUT: return "about permissions version licenses view full notices connect selected scooter";
            // Simulation scene, Drive mode and Load all widgets into slot 3 show only while simulating.
            case HubPages.SETTINGS_DEVELOPER: return "developer options simulate simulation simulation scene drive mode load all widgets into slot 3";
            default: return "";
        }
    }
}
