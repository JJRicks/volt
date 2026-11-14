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
 * Search, the Widgets dock and the wide Home's right pane (PR 10) name the rows they find by the names the rows
 * already show, through {@link #controlRowTitle} and {@link #settingTitle}.
 * No Android imports, so the JVM suite tests it (tests/l10n/HubTextTest.java, tests/l10n/HomeTextTest.java,
 * tests/l10n/HubSearchTextTest.java).
 */
public final class HubText {
    private HubText() { }

    /** A page's title, also its link row's name and the back link's target name. Null for an unknown id. */
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
            case HubPages.SEARCH: return M.hubPageSearch();
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

    /** Lights & sound while the Automatic lights setting is on. */
    public static Msg automaticLights() { return M.hubSummaryLightsAutomatic(); }
    /** Lights & sound from the Tail light setting, on or off (feature key {@code headlight} drives the tail light). */
    public static Msg tailLight(boolean on) { return on ? M.hubSummaryLightsTailOn() : M.hubSummaryLightsTailOff(); }

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

    // ===== Battery & charging › Charge history & recordings =====

    /** The row that emails this scooter's charge recordings to Volt (the Compatibility panel's words). */
    public static Msg chargeSendRow() { return M.compatEmail(); }
    /** Under that row: why sending them helps (charging time estimates for the scooter's model). */
    public static Msg chargeSendNote() { return M.hubChargeSendNote(); }
    /** When no email app can open that email. */
    public static Msg chargeSendNoEmail() { return M.hubChargeSendNoEmail(); }
    /** Toast when that row is tapped while a charge is being recorded (recordings are sent only once capture stops). */
    public static Msg chargeSendBusy() { return M.hubChargeSendBusy(); }
    /** The row that shows the charger chosen for estimates and opens that setting (the Charger row's own name). */
    public static Msg chargerRow() { return M.mainRowCharger(); }
    /** That row's value while Automatic has not recognized the charger. */
    public static Msg chargerAutomatic() { return M.mainRowAutomatic(); }
    /** That row's value for a known charger; detected when Automatic recognized it rather than the owner choosing it. */
    public static Msg chargerValue(boolean highSpeed, boolean detected) {
        Msg family = highSpeed ? M.mainRowHighSpeed() : M.mainRowStock();
        return detected ? M.mainRowAutoFamily(family) : family;
    }
    /**
     * That row's detail line, chosen as the Charger row chooses it: while this scooter's charge is being recorded
     * (the label comes from the recording); else when Automatic recognized the charger from charging power; else
     * while another scooter's charge is being recorded (the charger can't change then). Null when there is none.
     */
    public static Msg chargerDetail(boolean recordingThisScooter, boolean detected, boolean recordingOther) {
        if (recordingThisScooter) return M.mainRowRecordingLabel();
        if (detected) return M.mainRowDetected();
        return recordingOther ? M.mainRowStopRecording() : null;
    }

    // ===== Ride › Recording settings =====

    /** The switch that lets Record rides automatically also start while Volt is in the background. */
    public static Msg recordInBackground() { return M.hubRecordingAutoBackground(); }
    /** Under it: such a ride has no GPS route. */
    public static Msg recordInBackgroundNote() { return M.hubRecordingAutoBackgroundNote(); }

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

    /**
     * The last ride's muted line; minutes since the ride's shown end (HomeMoment.minutesAgo), less than one reads
     * "just now". For a ride that ended by itself the shown end is the last movement, before the save, so the line
     * says when the ride ended, not when it was saved (owner's answer on #383).
     */
    public static Msg justBackEnded(long minutes) {
        return minutes < 1 ? M.homeJustBackEndedNow() : M.homeJustBackEndedMinutes(minutes, minutes);
    }
    /** Screen-reader text of the round X that closes the last ride. */
    public static Msg justBackClose() { return M.homeJustBackClose(); }
    /** Screen-reader text of the last ride's card; distance with its unit. */
    public static Msg justBackDescription(CharSequence distance) { return M.homeJustBackDescription(distance); }
    /** The last ride's three tiles: ride time, average speed and the battery change. */
    public static Msg justBackRideTime() { return M.ridingLabelRideTime(); }
    public static Msg justBackAvgSpeed() { return M.ridesTileAvgSpeed(); }
    /**
     * The battery tile's label, with the Rides page's own words (owner's answer on #383): "Battery gain" when the
     * ride ended with more charge than it started, else "Battery drop". batteryDropPercent is the saved ride's
     * start minus end (RideStore.Summary.batteryDropPercent), chosen exactly as RidesScreen chooses: a gain only
     * for a known value below zero, so zero and an unknown change read as a drop.
     */
    public static Msg justBackBattery(Double batteryDropPercent) {
        return batteryDropPercent != null && batteryDropPercent < 0 ? M.ridesTileBatteryGain() : M.ridesTileBatteryDrop();
    }
    /**
     * The battery tile's label as the Layout Plan's mock-up has it (the contributor's decisions on #383, which replace
     * the drop/gain pair above on Just back): "Battery", the value carrying the signed change ("−18%"). The Battery
     * subject's short name (hub_subject_battery_short); Just back now shows {@link #justBackBatteryName()}, its own key.
     * Kept while HomeTextTest checks it (an existing test a batch part may not edit); unused by the app.
     */
    public static Msg justBackBatteryTile() { return shortTitle(HubPages.BATTERY); }
    /**
     * The battery tile's name on Just back, "Battery", with the signed change as its value ("−18%"): Just back's own key
     * (hub_just_back_battery), so a language can name the change where the subject's short name would not fit or read.
     */
    public static Msg justBackBatteryName() { return M.hubJustBackBattery(); }

    /**
     * The Ride time tile's value as the mock-up writes it, short and without the estimate sign the charging time
     * carries: "45s" under a minute, "24m" under an hour, then "1h 02m" (minutes always two digits). Whole units,
     * rounded down, as the Rides page's Duration; a negative time counts as none.
     */
    public static Msg justBackRideTimeValue(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 60) return M.hubJustBackTimeSeconds(Fmt.integer(seconds));
        long minutes = seconds / 60;
        if (minutes < 60) return M.hubJustBackTimeMinutes(Fmt.integer(minutes));
        long rest = minutes % 60;
        return M.hubJustBackTimeHours(Fmt.integer(minutes / 60), (rest < 10 ? Fmt.integer(0) : "") + Fmt.integer(rest));
    }

    /**
     * The Ride time tile's value for screen readers, in words with the same whole units as
     * {@link #justBackRideTimeValue(long)}, so "20m" is never read as a distance: "45 seconds", "20 minutes", "1 hour",
     * "1 hour 2 minutes".
     */
    public static Msg justBackRideTimeSpoken(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 60) return M.hubJustBackTimeSpokenSeconds(seconds, seconds);
        long minutes = seconds / 60;
        if (minutes < 60) return M.hubJustBackTimeSpokenMinutes(minutes, minutes);
        long hours = minutes / 60, rest = minutes % 60;
        Msg spokenHours = M.hubJustBackTimeSpokenHours(hours, hours);
        return rest == 0 ? spokenHours : M.hubJustBackTimeSpokenHoursMinutes(spokenHours, M.hubJustBackTimeSpokenMinutes(rest, rest));
    }

    /**
     * Screen-reader text of the last ride on Clean Home, one whole sentence per language: the ended line first (what
     * sighted owners read first), then the distance, then what a tap does ("Ride ended 12 minutes ago, 6.2 mi. Opens
     * the ride."). minutes as {@link #justBackEnded(long)} takes them; distance with its unit.
     */
    public static Msg justBackSpoken(long minutes, CharSequence distance) {
        return minutes < 1 ? M.hubJustBackSpokenNow(distance) : M.hubJustBackSpokenMinutes(minutes, minutes, distance);
    }

    // ===== Clean Home's Edit sheet and quick actions =====

    public static Msg editTitle() { return M.homeEditTitle(); }
    /** The label above the four quick action slots. */
    public static Msg editQuickActions() { return M.homeEditQuickActions(); }
    /** Screen-reader text of a slot; position counts from 1, action is the name of the action in it. */
    public static Msg editQuickSlot(long position, CharSequence action) { return M.homeEditQuickSlot(position, action); }

    /**
     * The name of one of Clean's own quick actions (HomeQuickActions); null for any other id. The way into riding
     * mode is named RIDE, with the Widgets header's RIDE button's own words, so it reads as entering riding mode
     * rather than as a settings shortcut (owner's answer 6 on #383).
     */
    public static Msg quickActionLabel(String id) {
        if (id == null) return null;
        switch (id) {
            case HomeQuickActions.RIDING: return M.ridingRideButton();
            case HomeQuickActions.RECORD: return M.homeQaRecord();
            case HomeQuickActions.CHARGE_ALERTS: return M.homeQaChargeAlerts();
            default: return null;
        }
    }

    /**
     * The short rung of one of Clean's own quick actions, for a button too narrow for the name; null for any other
     * id. RIDE is already its own short form (its next rung is the icon alone, as in the Widgets header).
     */
    public static Msg quickActionShortLabel(String id) {
        if (id == null) return null;
        switch (id) {
            case HomeQuickActions.RIDING: return M.ridingRideButton();
            case HomeQuickActions.RECORD: return M.homeQaRecordShort();
            case HomeQuickActions.CHARGE_ALERTS: return M.homeQaChargeAlertsShort();
            default: return null;
        }
    }

    /**
     * Screen-reader text of one of Clean's own quick actions where its name alone does not say what it does: the
     * RIDE action, spoken as the RIDE button is ("Open riding mode"). Null for any other id (its name is read).
     */
    public static Msg quickActionDescription(String id) {
        if (HomeQuickActions.RIDING.equals(id)) return M.ridingRideDescription();
        return null;
    }

    /**
     * The one short name an action shows under its round button on Clean Home, in Settings › Home style's Quick actions
     * line and in the Home style pictures (the contributor's decisions on #383: one short name on one line; "Ride", not
     * "RIDE"; "Charge by" for Charge done by, round 3). Clean's own actions (HomeQuickActions) and the catalog's Charge
     * done by and controls (QuickAccessActions ids, a control as FEATURE_PREFIX + its feature key) that have a round
     * name; null for any other action (QuickAccessGrid then picks its short rung). Its spoken name stays the full one.
     */
    public static Msg quickActionRoundName(String id) {
        if (id == null) return null;
        if (id.startsWith(QuickAccessActions.FEATURE_PREFIX)) {
            switch (id.substring(QuickAccessActions.FEATURE_PREFIX.length())) {
                case "cruise_control": return M.hubQaCruiseRound();
                case "traction_control": return M.hubQaTractionRound();
                case "energy_recovery": return M.hubQaRecoveryRound();
                case "overspeed_control": return M.hubQaOverspeedRound();
                case "hill_descent_control": return M.hubQaDescentRound();
                default: return null;
            }
        }
        switch (id) {
            case HomeQuickActions.RIDING: return M.hubQaRideRound();
            case HomeQuickActions.RECORD: return M.hubQaRecordRound();
            case HomeQuickActions.CHARGE_ALERTS: return M.hubQaAlertsRound();
            case "charge_done_by": return M.hubQaChargeDoneByRound();
            default: return null;
        }
    }

    /** Between the names of a short list on one line: Settings › Home style's Quick actions ("Ride, Record, Charge by, Cruise"). */
    public static Msg listSeparator() { return M.hubListSeparator(); }

    /**
     * The Quick access tile that opens search (owner-era item the contributor kept, search-settings-tile): its name,
     * "Search settings", and its short rung, "Search", where the name does not fit its one line.
     */
    public static Msg quickAccessSearch() { return M.hubQaSearchSettings(); }
    public static Msg quickAccessSearchShort() { return M.hubQaSearchSettingsShort(); }

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

    /*
     * The offer to switch to Clean: title, body, the two buttons and the note under them. The sheet shows these only
     * while HomeStyle.offerSwitch holds (no style chosen yet and Home not already Clean); an owner who chose a style
     * still gets the sheet once (HomeStyle.showWhatsNew), with the release notes and one-time notices below only.
     */
    public static Msg whatsNewTitle() { return M.homeWhatsNewTitle(); }
    public static Msg whatsNewBody() { return M.homeWhatsNewBody(); }
    /** The sheet's main button: switch to Clean. */
    public static Msg whatsNewSwitch() { return M.homeWhatsNewSwitch(); }
    /** The sheet's second button: keep Widgets. */
    public static Msg whatsNewKeep() { return M.homeWhatsNewKeep(); }
    public static Msg whatsNewLater() { return M.homeWhatsNewLater(); }
    /**
     * The heading above this release's notes (owner's answers 9, 10 and 12); version as Volt shows it ("1.14.0").
     * Without the switch offer (HomeStyle.offerSwitch false) it is the first line of the sheet.
     */
    public static Msg whatsNewChanges(CharSequence version) { return M.homeWhatsNewChanges(version); }
    /** The line above the notes while Volt is not in English: the notes come in English (the update check's line). */
    public static Msg whatsNewNotesInEnglish() { return M.compatUpdateNotesInEnglish(); }
    /**
     * The line under the notes when the sheet shows only the first of them (WhatsNewNotes.Notes#cut), above the
     * link to the release page. Text only here; an ordinary part shows it.
     */
    public static Msg whatsNewCut() { return M.hubWhatsNewCut(); }
    /** The link under the notes that opens the release page with the full notes (the update check's button). */
    public static Msg whatsNewReleasePage() { return M.moreUpdateOpen(); }
    /** The message when no browser can open the release page (the update check's). */
    public static Msg whatsNewNoBrowser() { return M.moreUpdateNoBrowser(); }
    /**
     * The one-time notice for owners who already had Record rides automatically on (owner's answer on #403): an
     * automatic start in the background is now off until they turn it on.
     */
    public static Msg whatsNewBackgroundNotice() { return M.homeWhatsNewBackground(); }
    /** The switch right under that notice: Recording settings' own "Start recording in the background". */
    public static Msg whatsNewBackgroundSwitch() { return M.hubRecordingAutoBackground(); }
    /** The link under that notice to Ride › Recording settings, the page that holds the switch (its own title). */
    public static Msg whatsNewRecordingSettings() { return M.hubPageRideRecording(); }

    // ===== Search (hub layout plan 1.12) =====

    /** The hint inside the empty search field. */
    public static Msg searchHint() { return M.hubSearchHint(); }
    /** Shown in place of results when nothing matches. */
    public static Msg searchNone() { return M.hubSearchNone(); }
    /** The count of results, read out as it changes; count is the number of results. */
    public static Msg searchCount(long count) { return M.hubSearchCount(count, count); }
    /** The label above a group of results. */
    public static Msg searchGroupPages() { return M.hubSearchGroupPages(); }
    public static Msg searchGroupSettings() { return M.hubSearchGroupSettings(); }
    public static Msg searchGroupReadings() { return M.hubSearchGroupReadings(); }
    public static Msg searchGroupRides() { return M.hubSearchGroupRides(); }
    /** The line under a result naming the subject it lives in; subject is that subject's title. */
    public static Msg searchIn(CharSequence subject) { return M.hubSearchIn(subject); }
    /**
     * Words search matches for the Charge history and recordings page ({@link HubPages#BATTERY_HISTORY}), never shown:
     * its rows record, export, clear and email charge recordings, which its title alone does not name.
     */
    public static Msg batteryHistorySearchWords() { return M.hubSearchBatteryHistory(); }
    /**
     * Words search matches for the Line lights and Star lights rows, never shown: the scooter's light zones,
     * patterns and colours. The existing, already-translated words the controls' own search matched them by
     * (main_search_zones): Hub search's Line lights and Star lights pages (HubSearch.pageSearchWords) and the old
     * index's links to them match these in the current language.
     */
    public static Msg lightZonesSearchWords() { return M.mainSearchZones(); }
    /**
     * The names a light family goes by in the current language ({@link HubPages#LIGHTS_LINE} or
     * {@link HubPages#LIGHTS_STAR}): its page title and its on/off control's already-translated name
     * (ScooterCommands' line_light or star_light). Search uses them so a family name in the shared zones words
     * (lightZonesSearchWords) reaches only its own page (#404 review); empty for any other page.
     */
    public static Msg[] lightFamilyNames(String page) {
        String family = HubPages.LIGHTS_LINE.equals(page) ? "line_light" : HubPages.LIGHTS_STAR.equals(page) ? "star_light" : null;
        if (family == null) return new Msg[0];
        ScooterCommands.Feature control = ScooterCommands.find(family);
        return new Msg[] {title(page), control == null ? null : control.label()};
    }

    /**
     * The name a hand-built row of the scooter's controls already shows ({@link HubSearchTables#ROWS}: the
     * row's ScooterMenuLayout id or view tag), for search's results; null for any other id. Feature rows are
     * named by their feature (ScooterCommands.Feature#label).
     */
    public static Msg controlRowTitle(String row) {
        if (row == null) return null;
        switch (row) {
            case HubSearchTables.ROW_HEADLIGHT_INFO: return M.mainRowHeadlight();
            case HubSearchTables.ROW_CLOCK_SYNC: return M.mainRowClock();
            case HubSearchTables.ROW_SCHEDULE_TEST: return M.mainScheduleTestRow();
            case HubSearchTables.ROW_CHARGE_DONE_BY: return M.mainScheduleDoneBy();
            case HubSearchTables.ROW_CHARGER: return M.mainRowCharger();
            case HubSearchTables.ROW_CHARGE_ALERT: return M.mainAlertCharge();
            case HubSearchTables.ROW_LOW_ALERT: return M.mainAlertLow();
            case HubSearchTables.ROW_CHARGE_LIVE: return M.chargeLiveRow();
            case HubSearchTables.ROW_SECURITY_LINK: return M.mainRowSecurity();
            case HubSearchTables.ROW_SCOOTER_NAME: return M.mainRenameTitle();
            case HubSearchTables.ROW_MAINTENANCE: return M.maintenanceRow();
            case HubSearchTables.ROW_TOP_SPEED: return M.mainSpeedTitle();
            default: return null;
        }
    }

    /**
     * The name a settings row already shows, by its view tag ({@link HubSearchTables#SETTINGS}), for search's
     * results; null for any other tag. The display card's tag stands for its Text size row, the one row in it
     * with no tag of its own: Language and Units carry theirs, and Haptic feedback has its own
     * ({@link HubSearchTables#SETTING_HAPTICS}, set on the switch by part 11). The riding-mode boxes for
     * brightness and night vision are named by their labels.
     */
    public static Msg settingTitle(String tag) {
        if (tag == null) return null;
        switch (tag) {
            case HubSearchTables.SETTING_HOME_STYLE: return M.homeStyleTitle();
            case HubSearchTables.SETTING_UNITS: return M.morePrefsUnits();
            case HubSearchTables.SETTING_DISPLAY: return M.morePrefsTextSize();
            case HubSearchTables.SETTING_HAPTICS: return M.morePrefsHaptics();
            case HubSearchTables.SETTING_LANGUAGE: return M.moreLanguageTitle();
            case HubSearchTables.SETTING_RIDING_AUTO_OPEN: return M.ridingDisplayAutoOpen();
            case HubSearchTables.SETTING_RIDING_AUTO_RECORD: return M.morePrefsAutoRecord();
            case HubSearchTables.SETTING_RIDING_HIDE_STATUS_BAR: return M.ridingDisplayHideStatusBar();
            case HubSearchTables.SETTING_RIDING_CLOCK_SECONDS: return M.ridingDisplayClockSeconds();
            case HubSearchTables.SETTING_RIDING_BRIGHTNESS: return M.ridingDisplayBrightness();
            case HubSearchTables.SETTING_RIDING_NIGHT: return M.ridingNightVision();
            case HubSearchTables.SETTING_UPDATE_AUTO: return M.moreUpdatesAuto();
            case HubSearchTables.SETTING_CHECK_UPDATES: return M.moreAboutCheckUpdates();
            case HubSearchTables.SETTING_BACKUP: return M.moreBackupRestore();
            case HubSearchTables.SETTING_REPORT_ISSUE: return M.moreReportIssue();
            case HubSearchTables.SETTING_DEVELOPER: return M.moreSectionDeveloper();
            default: return null;
        }
    }

    // ===== Widgets Home's dock and the wide Home's right pane =====

    /** What TalkBack reads for the dock's "All" item (and its long-press name), and the title of the sheet listing every subject. */
    public static Msg dockAll() { return M.hubDockAll(); }
    /**
     * The right pane's link back to the list of all subjects, and the short label under the dock's "All" item on a
     * phone (owner's answer 8 on #383: a portrait dock that is not cramped shows fewer icons, each labelled, and
     * this item opens the rest); read by {@link #dockAll()}. As narrow as the subjects' short names (64 dp at 12 sp).
     */
    public static Msg all() { return M.hubAll(); }
    /** The Service card: its title, and its line when no reminder is set. */
    public static Msg serviceCardTitle() { return M.hubServiceCardTitle(); }
    public static Msg serviceCardNone() { return M.hubServiceCardNone(); }

    // ===== Battery & charging, Controls and Ride pages (the Layout Plan's pages) =====

    /** Battery & charging's section labels, shown in capitals: CHARGING over the charging rows, HISTORY over the chart. */
    public static Msg batterySectionCharging() { return M.hubBatterySectionCharging(); }
    public static Msg batterySectionHistory() { return M.hubBatterySectionHistory(); }
    /**
     * The HISTORY card's line: "Last charge 42% → 90% · 2 h 10 min". span is the start and end battery
     * (stats_charge_battery_span), duration how long it took (stats_charge_duration_*), or null when not known.
     */
    public static Msg batteryLastCharge(CharSequence span, CharSequence duration) {
        return duration == null ? M.hubBatteryLastChargeSpan(span) : M.hubBatteryLastCharge(span, duration);
    }

    /** Controls' label over the links to its sub-pages, shown in capitals, and the lines under Assists and Speed limits. */
    public static Msg controlsMore() { return M.hubControlsMore(); }
    public static Msg controlsAssistsSub() { return M.hubControlsAssistsSub(); }
    public static Msg controlsLimitsSub() { return M.hubControlsLimitsSub(); }

    /**
     * A recent ride's title on the Ride page when it started today or yesterday ("Yesterday, 5:42 pm"); null for an
     * older ride (or one dated after today), which keeps its day and date. time is the start time formatted for the
     * language.
     */
    public static Msg recentRideTitle(java.time.LocalDate rideDay, java.time.LocalDate today, CharSequence time) {
        if (rideDay == null || today == null) return null;
        if (rideDay.equals(today)) return M.hubRideToday(time);
        if (rideDay.equals(today.minusDays(1))) return M.hubRideYesterday(time);
        return null;
    }

    /**
     * The Record ride tile's shorter name while simulating ("Simulating"), where rides_simulating_exit ("Simulating ·
     * Exit") does not fit one line. Its own key, not a rung of that ladder-less, already translated one.
     */
    public static Msg rideSimulatingShort() { return M.hubRideSimulating(); }
}
