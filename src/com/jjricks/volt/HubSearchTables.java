package com.jjricks.volt;

/**
 * The ids Hub search indexes besides pages and feature rows (hub layout plan 1.12): the scooter menu's hand-built
 * rows and More's settings rows. Constants only for now: HubText.controlRowTitle and HubText.settingTitle (part 10)
 * switch on them to give each row its existing on-screen name, and part 12 adds each id's page and English search
 * words beside it. Every id is the one the row already has (its ScooterMenuLayout id or its view tag), so saved
 * menu layouts, validations and TalkBack keep finding the same rows.
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
            SETTING_HOME_STYLE, SETTING_UNITS, SETTING_DISPLAY, SETTING_LANGUAGE,
            SETTING_RIDING_AUTO_OPEN, SETTING_RIDING_AUTO_RECORD, SETTING_RIDING_HIDE_STATUS_BAR, SETTING_RIDING_CLOCK_SECONDS,
            SETTING_RIDING_BRIGHTNESS, SETTING_RIDING_NIGHT, SETTING_UPDATE_AUTO, SETTING_CHECK_UPDATES, SETTING_BACKUP,
            SETTING_REPORT_ISSUE, SETTING_DEVELOPER,
    };
}
