package com.jjricks.volt;

/**
 * The Hub's text (hub layout plan): page titles, link rows, the page header's back link, Home's
 * subject summaries and Home's two styles (Clean and Widgets, res/values/strings_home.xml). Every
 * catalog read for the Hub's pages lives here, so the screens that build them hold no catalog
 * references of their own. Everything returns a {@link Msg}, which renders when shown: keep the Msg,
 * never the rendered text (rule R2).
 *
 * Pages are named by their {@link HubPages} id. Each summary is a whole line chosen by its state, with
 * only values filled in; values arrive formatted ({@code Fmt}, {@code DisplayUnits}, {@code DisplayTime}).
 * No Android imports, so the JVM suite tests it (tests/l10n/HubTextTest.java, tests/l10n/HomeTextTest.java).
 */
public final class HubText {
    private HubText() { }

    /**
     * A page's title, also its link row's name and the back link's target name. Null for a page whose
     * title arrives later in the plan ({@link HubPages#SEARCH} in PR 10) and for an unknown id.
     */
    public static Msg title(String page) {
        if (page == null) return null;
        switch (page) {
            case HubPages.HOME: return M.mainNavHome();
            case HubPages.SCOOTER: return M.mainNavScooter();
            case HubPages.RIDE: return M.hubSubjectRide();
            case HubPages.RIDE_RIDES: return M.hubPageRideRides();
            case HubPages.RIDE_RECORDING: return M.hubPageRideRecording();
            case HubPages.RIDE_RIDING: return M.hubPageRideRiding();
            case HubPages.BATTERY: return M.hubSubjectBattery();
            case HubPages.BATTERY_MODES: return M.hubPageBatteryModes();
            case HubPages.BATTERY_ESTIMATES: return M.hubPageBatteryEstimates();
            case HubPages.BATTERY_HISTORY: return M.hubPageBatteryHistory();
            case HubPages.CONTROLS: return M.hubSubjectControls();
            case HubPages.CONTROLS_ASSISTS: return M.hubPageControlsAssists();
            case HubPages.CONTROLS_LIMITS: return M.hubPageControlsLimits();
            case HubPages.LIGHTS: return M.hubSubjectLights();
            case HubPages.LIGHTS_LINE: return M.hubPageLightsLine();
            case HubPages.LIGHTS_STAR: return M.hubPageLightsStar();
            case HubPages.SECURITY: return M.hubSubjectSecurity();
            case HubPages.SERVICE: return M.hubSubjectService();
            case HubPages.SERVICE_MAINTENANCE: return M.maintenanceTitle();
            case HubPages.SERVICE_EXPERIMENTAL: return M.hubPageServiceExperimental();
            case HubPages.SERVICE_COMPATIBILITY: return M.hubPageServiceCompatibility();
            case HubPages.SERVICE_REPORTS: return M.hubPageServiceReports();
            case HubPages.DATA: return M.hubSubjectData();
            case HubPages.DATA_STORAGE: return M.hubPageDataStorage();
            case HubPages.SETTINGS: return M.hubPageSettings();
            case HubPages.SETTINGS_HOME: return M.homeStyleTitle();
            case HubPages.SETTINGS_UNITS: return M.moreUnitsTitle();
            case HubPages.SETTINGS_SCOOTERS: return M.moreSectionScooters();
            case HubPages.SETTINGS_ABOUT: return M.moreSectionAbout();
            case HubPages.SETTINGS_DEVELOPER: return M.moreSectionDeveloper();
            default: return null;
        }
    }

    /** A subject's short name, for narrow places such as icon captions; null for any other page. */
    public static Msg shortTitle(String subject) {
        if (subject == null) return null;
        switch (subject) {
            case HubPages.RIDE: return M.hubSubjectRideShort();
            case HubPages.BATTERY: return M.hubSubjectBatteryShort();
            case HubPages.CONTROLS: return M.hubSubjectControlsShort();
            case HubPages.LIGHTS: return M.hubSubjectLightsShort();
            case HubPages.SECURITY: return M.hubSubjectSecurityShort();
            case HubPages.SERVICE: return M.hubSubjectServiceShort();
            case HubPages.DATA: return M.hubSubjectDataShort();
            default: return null;
        }
    }

    /** The name of a link row that opens a page: the page's title. */
    public static Msg rowTitle(String page) { return title(page); }

    /** Service's row that opens Data › Diagnostics (a link, not a page of its own). */
    public static Msg diagnosticsRow() { return M.hubPageServiceDiagnostics(); }
    /** Ride's row that opens the ride backups. */
    public static Msg rideBackupsRow() { return M.ridesBackups(); }
    /** Ride's button that opens riding mode now. */
    public static Msg openRiding() { return M.hubRideOpenRiding(); }
    /** The label above Ride's last few rides. */
    public static Msg recentRides() { return M.hubRideRecent(); }

    /** Screen-reader label of a sub-page's back link; parent is the title of the page it returns to. */
    public static Msg backTo(CharSequence parent) { return M.hubBackTo(parent); }
    /** The badge on a subject page's row for a control that is experimental on this model. */
    public static Msg experimentalBadge() { return M.hubExperimentalBadge(); }
    /** Screen-reader text of such a row; feature is the control's name. */
    public static Msg experimentalRowDescription(CharSequence feature) { return M.hubExperimentalRowDescription(feature); }
    /** Screen-reader text of a row or icon carrying the maintenance dot; title is its name. */
    public static Msg dueDescription(CharSequence title) { return M.maintenanceTabDue(title); }

    // ===== Home's subject summaries =====

    /** Ride, the last ride ended today; distance with its unit. */
    public static Msg rideLastToday(CharSequence distance) { return M.hubSummaryRideLastToday(distance); }
    /** Ride, the last ride ended yesterday. */
    public static Msg rideLastYesterday(CharSequence distance) { return M.hubSummaryRideLastYesterday(distance); }
    /** Ride, the last ride ended on an earlier day; date formatted for the language. */
    public static Msg rideLastOn(CharSequence distance, CharSequence date) { return M.hubSummaryRideLastDate(distance, date); }
    /** Ride, while a ride is recorded; elapsed is the time so far. */
    public static Msg rideRecording(CharSequence elapsed) { return M.hubSummaryRideRecording(elapsed); }
    /** Ride, before the first ride. */
    public static Msg rideNone() { return M.hubSummaryRideNone(); }

    /** Battery & charging on a model with a charge limit; limit is a formatted percent. */
    public static Msg batteryLimit(CharSequence limit, boolean charging) {
        return charging ? M.hubSummaryBatteryLimitCharging(limit) : M.hubSummaryBatteryLimitIdle(limit);
    }

    /** Battery & charging on a model with no charge limit; level is a formatted percent. */
    public static Msg batteryLevel(CharSequence level, boolean charging) {
        return charging ? M.hubSummaryBatteryCharging(level) : M.hubSummaryBatteryIdle(level);
    }

    /** Controls; mode is the drive mode as Home shows it ("Sport 31 mph"). */
    public static Msg controls(CharSequence mode, boolean cruiseOn) {
        return cruiseOn ? M.hubSummaryControlsCruiseOn(mode) : M.hubSummaryControlsCruiseOff(mode);
    }

    /** Lights & sound while the headlight is on automatic. */
    public static Msg headlightAuto() { return M.hubSummaryLightsAuto(); }
    /** Lights & sound while the headlight is set on or off. */
    public static Msg headlight(boolean on) { return on ? M.hubSummaryLightsOn() : M.hubSummaryLightsOff(); }

    /** Security, once both states are known (an unknown state shows no summary). */
    public static Msg security(boolean pin, boolean proximity) {
        if (pin) return proximity ? M.hubSummarySecurityPinOnProximityOn() : M.hubSummarySecurityPinOnProximityOff();
        return proximity ? M.hubSummarySecurityPinOffProximityOn() : M.hubSummarySecurityPinOffProximityOff();
    }

    /** Service with a check due or coming up; name is the reminder's, when is MaintenanceView's text. */
    public static Msg serviceDue(CharSequence name, CharSequence when) { return M.hubSummaryServiceDue(name, when); }
    /** Service with no check due. */
    public static Msg serviceClear() { return M.hubSummaryServiceClear(); }
    /** Data's summary. */
    public static Msg dataSummary() { return M.hubSummaryData(); }

    // ===== Battery & charging page, summary card =====

    /** Level (a formatted percent) and the estimated range left (with its unit). */
    public static Msg batteryLevelRange(CharSequence level, CharSequence range) { return M.hubBatteryLevelRange(level, range); }
    /** While charging; limit is the formatted percent the charge stops at. */
    public static Msg batteryChargingTo(CharSequence limit) { return M.hubBatteryChargingTo(limit); }
    /** While not charging. */
    public static Msg batteryNotCharging() { return M.hubBatteryNotCharging(); }

    // ===== Home style (Settings › Home style, Clean's Edit sheet, the Widgets Layout sheet) =====

    /** A style's name: Clean (the fixed Home) or Widgets (the dashboard the owner arranges). */
    public static Msg homeStyleName(boolean clean) { return clean ? M.homeStyleClean() : M.homeStyleWidgets(); }
    /** The line under a style's name. */
    public static Msg homeStyleDescription(boolean clean) { return clean ? M.homeStyleCleanSub() : M.homeStyleWidgetsSub(); }
    /** Screen-reader text of a style's preview drawing. */
    public static Msg homeStylePreview(boolean clean) { return clean ? M.homeStyleCleanPreview() : M.homeStyleWidgetsPreview(); }
    /** The note that switching style keeps the widget layout. */
    public static Msg homeStyleKeepsLayout() { return M.homeStyleKeepsLayout(); }

    /** The switch for Clean's charging view, and the line under it. */
    public static Msg momentCharging() { return M.homeMomentCharging(); }
    public static Msg momentChargingDescription() { return M.homeMomentChargingSub(); }
    /** The switch for Clean's last-ride view, and the line under it. */
    public static Msg momentRide() { return M.homeMomentRide(); }
    public static Msg momentRideDescription() { return M.homeMomentRideSub(); }

    // ===== Clean Home's top area =====

    /** The charging view's muted line on a model with a charge limit; limit is a formatted percent. */
    public static Msg chargingToLimit(CharSequence limit) { return M.homeChargingToLimit(limit); }
    /** The charging view's muted line on a model with no charge limit. */
    public static Msg charging() { return M.dashboardCharging(); }
    /** The charging view's power tile. */
    public static Msg chargingPower() { return M.dashboardChargingPower(); }

    /** The last ride's muted line; minutes since it was saved (less than one reads "just now"). */
    public static Msg justBackSaved(long minutes) {
        return minutes < 1 ? M.homeJustBackSavedNow() : M.homeJustBackSavedMinutes(minutes, minutes);
    }
    /** Screen-reader text of the round X that closes the last ride. */
    public static Msg justBackClose() { return M.homeJustBackClose(); }
    /** Screen-reader text of the last ride's card; distance with its unit. */
    public static Msg justBackDescription(CharSequence distance) { return M.homeJustBackDescription(distance); }
    /** The last ride's three tiles: ride time, average speed and battery used. */
    public static Msg justBackRideTime() { return M.ridingLabelRideTime(); }
    public static Msg justBackAvgSpeed() { return M.ridesTileAvgSpeed(); }
    public static Msg justBackBatteryUsed() { return M.homeJustBackBatteryUsed(); }

    // ===== Clean Home's Edit sheet and quick actions =====

    public static Msg editTitle() { return M.homeEditTitle(); }
    /** The label above the four quick action slots. */
    public static Msg editQuickActions() { return M.homeEditQuickActions(); }
    /** Screen-reader text of a slot; position counts from 1, action is the name of the action in it. */
    public static Msg editQuickSlot(long position, CharSequence action) { return M.homeEditQuickSlot(position, action); }

    /** The name of one of Clean's own quick actions (HomeQuickActions); null for any other id. */
    public static Msg quickActionLabel(String id) {
        if (id == null) return null;
        switch (id) {
            case HomeQuickActions.RIDING: return M.homeQaRiding();
            case HomeQuickActions.RECORD: return M.homeQaRecord();
            default: return null;
        }
    }

    /** The short rung of one of Clean's own quick actions, for a button too narrow for the name; null for any other id. */
    public static Msg quickActionShortLabel(String id) {
        if (id == null) return null;
        switch (id) {
            case HomeQuickActions.RIDING: return M.homeQaRidingShort();
            case HomeQuickActions.RECORD: return M.homeQaRecordShort();
            default: return null;
        }
    }

    /** The Record action's value while a ride is being recorded. */
    public static Msg qaRecording() { return M.homeQaRecording(); }

    // ===== Home's header =====

    public static Msg searchDescription() { return M.homeSearchDescription(); }
    public static Msg settingsDescription() { return M.homeSettingsDescription(); }
    /** Screen-reader text of the scooter's name, which switches scooter; name is the scooter's. */
    public static Msg switchScooterDescription(CharSequence name) { return M.homeSwitchScooterDescription(name); }

    // ===== Setup's last step and the one-time What's new sheet =====

    public static Msg setupStyleTitle() { return M.homeSetupStyleTitle(); }
    public static Msg setupStyleBody() { return M.homeSetupStyleBody(); }

    public static Msg whatsNewTitle() { return M.homeWhatsNewTitle(); }
    public static Msg whatsNewBody() { return M.homeWhatsNewBody(); }
    /** The sheet's main button: switch to Clean. */
    public static Msg whatsNewSwitch() { return M.homeWhatsNewSwitch(); }
    /** The sheet's second button: keep Widgets. */
    public static Msg whatsNewKeep() { return M.homeWhatsNewKeep(); }
    public static Msg whatsNewLater() { return M.homeWhatsNewLater(); }
}
