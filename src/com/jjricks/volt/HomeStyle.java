package com.jjricks.volt;

/**
 * Which Home an owner sees (hub layout plan 1.8): Clean (a fixed Home) or Widgets (the dashboard they arrange).
 * Pure Java: the stored values, the one-time seed's decision and the What's new rules. HomeStylePrefs reads and
 * writes them in {@code app_settings}.
 *
 * <p>Absent or unknown means Widgets everywhere: an older Volt never wrote the key, and Widgets is the Home
 * whose saved layouts must keep showing. The seed (HomeStylePrefs.seed: at each app start once no restore is
 * waiting for recovery, and again right after recovery) writes nothing at app start on a clean new installation
 * or while its first-run setup is under way (setup's end records it: its last step's pick, or
 * origin install and Clean when first-run setup is skipped); it records Widgets and {@link #ORIGIN_UPDATE} when it finds an
 * earlier owner, re-derives a missing style from a stored origin, and adds a missing origin next to a stored
 * style (a restore). A restore of a backup without a style keeps this phone's style and choice (BackupArchive), and
 * stores an unchosen Widgets on a phone with neither a style nor an origin yet ({@link #preHubRestoreStoresWidgets}).
 * Switching style only writes {@link #KEY}; nothing in either Home's storage is touched.
 */
final class HomeStyle {
    /** The style (app_settings, String, App preferences: a restore carries the owner's pick). */
    static final String KEY = "home_style";
    /** True once the owner picked a style themselves (App preferences, travels with {@link #KEY}). The seed never sets it. */
    static final String CHOSEN_KEY = "home_style_chosen";
    /**
     * This installation's history (Device), set once and never rewritten: {@link #ORIGIN_UPDATE} by the seed when
     * it found an earlier owner, {@link #ORIGIN_INSTALL} by the first explicit pick on an installation the seed
     * left alone (HomeStylePrefs.markChosen).
     */
    static final String ORIGIN_KEY = "home_style_origin";
    /**
     * The one-time What's new sheet was answered on this installation (Device): set when the person closes it (a
     * button, its X, Back, a tap outside) or acts in it (the background switch, a style choice), not when it appears.
     * A rebuild, a service rebind or the activity going away while it shows leaves it due, so it shows again on the next
     * clear Home ({@link #whatsNewAnswered}; the rule 1.13.2's recording note follows, AutoStartPolicy.backgroundNoticeClosed
     * on release/1.13). Also set without showing when the sheet is settled ({@link #whatsNewSettled}).
     */
    static final String WHATS_NEW_SHOWN_KEY = "home_whats_new_shown";
    /**
     * A restore made the background-recording notice due on this installation (Device, Boolean, never in a backup): a
     * backup made before the background switch existed brought Record rides automatically back on, so automatic starts
     * that ran in the background on the earlier phone now wait until Volt is in front ({@link #restoreMarksNotice}).
     * The What's new sheet then shows the notice once whatever this installation's origin, without the offer to switch
     * unless that is due too ({@link #showWhatsNew(String, boolean, boolean, boolean, boolean, boolean, boolean)}).
     * Cleared with {@link #WHATS_NEW_SHOWN_KEY} when the sheet is answered, or when the notice no longer applies.
     */
    static final String RESTORED_NOTICE_KEY = "home_whats_new_restored_notice";

    static final String CLEAN = "clean", WIDGETS = "widgets";
    static final String ORIGIN_INSTALL = "install", ORIGIN_UPDATE = "update";

    private HomeStyle() {}

    /** The stored style: only a stored "clean" is Clean. Absent, null or any other value is Widgets. */
    static String of(Object stored) { return CLEAN.equals(stored) ? CLEAN : WIDGETS; }

    static boolean clean(Object stored) { return CLEAN.equals(of(stored)); }

    /** A value a picker may write: Clean or Widgets, anything else becomes Widgets. */
    static String valid(String style) { return of(style); }

    /**
     * The seed's choice for an installation that has no style yet. Any sign of an earlier owner keeps Widgets:
     * setup finished (or re-run after finishing), an installation that was updated (its first install time differs
     * from its last update time), or, once first-run setup is no longer under way, a scooter profile, a recorded
     * last page or a saved dashboard. Otherwise this is a new install: Clean.
     */
    static String initial(boolean setupFinished, boolean setupPending, boolean hasProfile, boolean hasLastPage, boolean hasDashboard,
                          long firstInstallTime, long lastUpdateTime) {
        return existingOwner(setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime) ? WIDGETS : CLEAN;
    }

    /** The origin that goes with {@link #initial}: {@link #ORIGIN_UPDATE} exactly when it keeps Widgets. */
    static String origin(boolean setupFinished, boolean setupPending, boolean hasProfile, boolean hasLastPage, boolean hasDashboard,
                         long firstInstallTime, long lastUpdateTime) {
        return existingOwner(setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime) ? ORIGIN_UPDATE : ORIGIN_INSTALL;
    }

    /**
     * What the seed writes while no style is stored, as {style, origin}, or null to write nothing. A stored origin
     * is never re-decided: {@link #ORIGIN_INSTALL} gives Clean and {@link #ORIGIN_UPDATE} Widgets. (A restore of a
     * backup without a style keeps this phone's style, so this only matters when the style went some other way.)
     * With no origin, an earlier owner records Widgets and {@link #ORIGIN_UPDATE}; a clean new installation, and
     * one whose first-run setup is still under way, writes nothing (absent reads as Widgets), so the decision
     * waits for setup to end and app data stays empty at app start.
     */
    static String[] reseed(String storedOrigin, boolean setupFinished, boolean setupPending, boolean hasProfile, boolean hasLastPage,
                           boolean hasDashboard, long firstInstallTime, long lastUpdateTime) {
        if (ORIGIN_INSTALL.equals(storedOrigin)) return new String[]{CLEAN, ORIGIN_INSTALL};
        if (ORIGIN_UPDATE.equals(storedOrigin)) return new String[]{WIDGETS, ORIGIN_UPDATE};
        if (existingOwner(setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime))
            return new String[]{WIDGETS, ORIGIN_UPDATE};
        return null;
    }

    /**
     * The origin the seed records when a style is stored but no origin is (a restore, which never carries the
     * origin, or an earlier build of this series): {@link #ORIGIN_UPDATE} for an earlier owner, by the same signals
     * as {@link #reseed}; otherwise null, writing nothing (a new install's origin is recorded by its own pick or by
     * setup ending). Null too when a known origin is stored: it is never rewritten. The stored style is never changed.
     */
    static String backfillOrigin(String storedOrigin, boolean setupFinished, boolean setupPending, boolean hasProfile, boolean hasLastPage,
                                 boolean hasDashboard, long firstInstallTime, long lastUpdateTime) {
        if (ORIGIN_INSTALL.equals(storedOrigin) || ORIGIN_UPDATE.equals(storedOrigin)) return null;
        return existingOwner(setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime) ? ORIGIN_UPDATE : null;
    }

    /** The origin an explicit pick records when none is stored: the seed found no earlier owner, so a new install. */
    static String pickedOrigin(String storedOrigin) {
        return ORIGIN_INSTALL.equals(storedOrigin) || ORIGIN_UPDATE.equals(storedOrigin) ? storedOrigin : ORIGIN_INSTALL;
    }

    /**
     * The origin an explicit pick (setup's end included) records, given whether a style is already stored. With no
     * origin and no style, the seed found no earlier owner: a new install. With no origin but a stored style, that style
     * came from a restore (the seed writes nothing while first-run setup is under way, and a restore never carries the
     * origin), for example App preferences restored from the welcome screen carrying the Widgets an earlier phone's
     * seed wrote: an earlier owner, {@link #ORIGIN_UPDATE}, so that owner still sees the What's new sheet once.
     * A stored origin is never rewritten.
     */
    static String pickedOrigin(String storedOrigin, boolean styleStored) {
        if (ORIGIN_INSTALL.equals(storedOrigin) || ORIGIN_UPDATE.equals(storedOrigin)) return storedOrigin;
        return styleStored ? ORIGIN_UPDATE : ORIGIN_INSTALL;
    }

    /**
     * Restoring a backup made before Home styles (no home_style) brings back an earlier owner's widget dashboards and
     * preferences, whose Home was Widgets. Whether the restore stores Widgets: yes when this phone's style was not
     * chosen by its owner but is stored, or would be Clean by its install origin (setup skipped on a new phone stores
     * Clean unchosen), so Home lands on the backup owner's Widgets instead of staying or turning Clean. A chosen style
     * is kept (part 6's rule), and a phone with neither a style nor an install origin is left to
     * {@link #preHubRestoreStoresWidgets} (App preferences store Widgets, unchosen, when no origin is stored either;
     * otherwise it is left as it is, since absent reads as Widgets).
     */
    static boolean preHubRestoreGivesWidgets(boolean chosen, boolean styleStored, Object storedOrigin) {
        return !chosen && (styleStored || ORIGIN_INSTALL.equals(storedOrigin));
    }

    /**
     * Whether restoring App preferences from a backup made before Home styles (no home_style) stores Widgets, unchosen:
     * only on a phone with neither a style nor an origin yet, which is a fresh installation restoring from the welcome
     * screen while first-run setup is under way (the seed waits for setup to end). Those preferences are an earlier
     * owner's, whose Home was Widgets; a style stored without an origin is how setup's end recognises a restored
     * earlier owner ({@link #pickedOrigin(String, boolean)}, {@link #afterSkippedSetup}), so that owner gets origin
     * update and still sees the What's new sheet once, with the background-recording notice when the restored Record
     * rides automatically is on (council early review of part 9, note 1). Absent already reads as Widgets, so Home
     * looks the same. A stored style or origin is left to the rules around it; the restore never writes the origin
     * itself (a device key: no restore changes those).
     */
    static boolean preHubRestoreStoresWidgets(boolean styleStored, Object storedOrigin) {
        return !styleStored && !ORIGIN_INSTALL.equals(storedOrigin) && !ORIGIN_UPDATE.equals(storedOrigin);
    }

    /**
     * The style setup's last step shows selected: the one already picked there (pending), else the stored style on a
     * rerun of setup or on an installation that has one (absent reads as Widgets), else Clean, the preselected choice of
     * a new install (Joel 6).
     */
    static String setupChoice(String pending, boolean rerun, Object storedStyle) {
        if (CLEAN.equals(pending) || WIDGETS.equals(pending)) return pending;
        if (rerun || storedStyle != null) return of(storedStyle);
        return CLEAN;
    }

    /**
     * What a skipped setup leaves stored, as {style, origin}; null for either means unchanged. It chooses nothing: an
     * installation with no origin records {@link #ORIGIN_INSTALL}, or {@link #ORIGIN_UPDATE} when a style is already
     * stored (a restore during setup brought an earlier owner's style: {@link #pickedOrigin(String, boolean)}), and one
     * whose origin is install and that has no style gets Clean, the style the seed would give it at the next start
     * anyway. A stored style is never changed.
     */
    static String[] afterSkippedSetup(String storedOrigin, boolean styleStored) {
        String origin = storedOrigin == null ? pickedOrigin(null, styleStored) : null;
        String style = !styleStored && ORIGIN_INSTALL.equals(storedOrigin == null ? ORIGIN_INSTALL : storedOrigin) ? CLEAN : null;
        return new String[]{style, origin};
    }

    /**
     * An earlier owner: setup finished (or re-run after finishing), or the installation was updated, or (package
     * times unreadable: the caller passes times that differ) unknown history. A scooter profile, a last page or a
     * saved dashboard also count, except while first-run setup is still under way on a fresh installation: setup
     * writes those itself before it finishes, so an interrupted setup must not be mistaken for an earlier owner.
     */
    private static boolean existingOwner(boolean setupFinished, boolean setupPending, boolean hasProfile, boolean hasLastPage,
                                         boolean hasDashboard, long firstInstallTime, long lastUpdateTime) {
        if (setupFinished || firstInstallTime != lastUpdateTime) return true;
        return !setupPending && (hasProfile || hasLastPage || hasDashboard);
    }

    /** Whether a stored dashboard key name is a saved widget dashboard (any scooter, or the unconfigured one). */
    static boolean dashboardKey(String key) {
        return key != null && key.startsWith("dashboard_v1_") && key.length() > "dashboard_v1_".length();
    }

    /**
     * Whether the one-time What's new sheet shows now (Joel 6, 10): only on an installation the seed found updated
     * that never saw the sheet, on Home, resumed, with neither setup nor riding mode open. A style chosen first (in
     * settings, or a restore that brought a choice) hides its offer to switch ({@link #offerSwitch}); the sheet then
     * shows only for the background-recording notice ({@link #whatsNewShows}), and without it the decision is settled
     * ({@link #whatsNewSettled}). A fresh installation never
     * sees it once setup recorded its origin (HomeStylePrefs.recordNewInstall or markChosen).
     */
    static boolean showWhatsNew(String origin, boolean shown, boolean onboardingShowing,
                                boolean riding, boolean onHome, boolean resumed) {
        return showWhatsNew(origin, shown, false, onboardingShowing, riding, onHome, resumed);
    }

    /**
     * As {@link #showWhatsNew(String, boolean, boolean, boolean, boolean, boolean)}, also when a restore made the
     * background-recording notice due ({@link #RESTORED_NOTICE_KEY}, restoredNotice), whatever the origin: restoring a
     * backup made before the background switch on a phone whose setup was skipped or finished (origin install) must
     * still tell the owner (council's second review of part 9, finding 1). The same places and moments as the update's sheet.
     */
    static boolean showWhatsNew(String origin, boolean shown, boolean restoredNotice, boolean onboardingShowing,
                                boolean riding, boolean onHome, boolean resumed) {
        return (whatsNewUpdateDue(origin, shown) || restoredNotice) && !onboardingShowing && !riding && onHome && resumed;
    }

    /** Whether the update's sheet (offer, notice, notes) is still due: an installation the seed found updated that never answered it. */
    static boolean whatsNewUpdateDue(String origin, boolean shown) {
        return ORIGIN_UPDATE.equals(origin) && !shown;
    }

    /**
     * Whether restoring a backup makes the background-recording notice due ({@link #RESTORED_NOTICE_KEY}): the restore
     * brings App preferences (Replace everything or App preferences alone) from a backup made before Home styles
     * (preHub: no home_style) and before the background switch (the backup holds no auto_record_background, so its
     * automatic starts ran in the background), and after it Record rides automatically is on and Start recording in the
     * background is not. A backup that already holds the switch was made by an owner who had it, and one with Record rides
     * automatically off has nothing to tell.
     */
    static boolean restoreMarksNotice(boolean preHub, boolean backupHasBackgroundSwitch, Object restoredAutoRecord,
                                      Object restoredBackground) {
        return preHub && !backupHasBackgroundSwitch && Boolean.TRUE.equals(restoredAutoRecord) && !Boolean.TRUE.equals(restoredBackground);
    }

    /**
     * Whether a closing of the What's new sheet answers it ({@link #WHATS_NEW_SHOWN_KEY} and {@link #RESTORED_NOTICE_KEY}
     * settled): only the person can, by closing it themselves (a button, its X, Back, a tap outside; byPerson) or by
     * acting in it (the background switch, a style choice; acted, so a later dismissal by Volt doesn't bring it back).
     * A dismissal by Volt itself (a rebuild, the activity going away, a service rebind) is neither: the sheet stays due
     * and shows again on the next clear Home, never two at once (MainActivity keeps the one showing).
     */
    static boolean whatsNewAnswered(boolean byPerson, boolean acted) {
        return byPerson || acted;
    }

    /**
     * Whether the What's new sheet offers to switch to Clean: only while the owner never chose a style themselves
     * and Home is not already Clean (the stored style, as {@link #of} reads it).
     */
    static boolean offerSwitch(boolean chosen, Object storedStyle) {
        return !chosen && !clean(storedStyle);
    }

    /**
     * Whether the What's new sheet has anything to show once {@link #showWhatsNew} says it may: the offer to switch
     * ({@link #offerSwitch}), the background-recording notice ({@link #whatsNewBackgroundNotice}) or this version's
     * bundled notes. An owner who already chose a style, on an APK built without notes and with no notice, gets no
     * empty sheet; nothing is recorded, so it is decided again the next time Home shows.
     */
    static boolean whatsNewHasContent(boolean offer, boolean backgroundNotice, boolean hasNotes) {
        return offer || backgroundNotice || hasNotes;
    }

    /**
     * Whether the What's new sheet shows, once {@link #showWhatsNew} says it may (the contributor's decisions on #383:
     * a screen introducing Clean on the first launch after the update, asking whether to switch, never to someone who
     * already chose a style). An owner who has not chosen sees it with the offer, the notice when it applies and this
     * version's notes; an owner who already chose sees it only for the background-recording notice (owner's answer on
     * #403), which must not be missed, never for the notes alone.
     */
    static boolean whatsNewShows(boolean chosen, boolean offer, boolean backgroundNotice, boolean hasNotes) {
        if (chosen) return backgroundNotice;
        return whatsNewHasContent(offer, backgroundNotice, hasNotes);
    }

    /**
     * Whether the What's new sheet is settled for good without showing: an owner who already chose a style and gets no
     * background-recording notice has no offer and never sees the notes alone, so it is recorded as shown (not decided
     * again at each Home rebuild, which would open it weeks later if they turned Record rides automatically on).
     * The same for a sheet due only for a restore's notice (WhatsNewSheet passes noticeOnly as chosen) once Record rides
     * automatically is off: the restore's mark is cleared. Only an owner who has not chosen, on an APK without notes and
     * with nothing else to show, is left undecided.
     */
    static boolean whatsNewSettled(boolean chosen, boolean backgroundNotice) {
        return chosen && !backgroundNotice;
    }

    /** The narrowest half (dp) the style picker's cards take side by side: the drawing, then the name and radio. */
    static final int PICKER_HALF_DP = 128;
    /** The page's or sheet's side gutters and the gap between the two cards (dp). */
    static final int PICKER_CHROME_DP = 42;

    /**
     * Whether the style picker's two cards sit side by side, as the mock-up draws them on a 344 dp phone: while each
     * half of the window's width less the gutters is at least {@link #PICKER_HALF_DP} dp, and the text is at most 1.5
     * times, else stacked (each card a row, its drawing at the left).
     */
    static boolean pickerSideBySide(int screenWidthDp, float textScale) {
        float scale = Float.isNaN(textScale) ? 1f : textScale;
        return (screenWidthDp - PICKER_CHROME_DP) / 2f >= PICKER_HALF_DP && scale <= 1.5f;
    }

    /** The What's new sheet's sections, by {@link #whatsNewSections}. */
    static final String SECTION_NOTICE = "notice", SECTION_OFFER = "offer", SECTION_NOTES = "notes";

    /**
     * The What's new sheet's sections in order, of those it holds: the background-recording notice first, then the
     * offer to switch, then this version's notes. Either offer button closes the sheet, so the notice goes above the
     * offer, where it is read before a choice closes it (council early review of part 9, note 4).
     */
    static java.util.List<String> whatsNewSections(boolean offer, boolean backgroundNotice, boolean hasNotes) {
        java.util.List<String> sections = new java.util.ArrayList<>();
        if (backgroundNotice) sections.add(SECTION_NOTICE);
        if (offer) sections.add(SECTION_OFFER);
        if (hasNotes) sections.add(SECTION_NOTES);
        return sections;
    }

    /**
     * Whether the What's new sheet also carries the notice that a recording now starts in the background only when
     * the owner turns that on (owner's answer on #403): only on an installation the seed found updated, and only for
     * an owner who has Record rides automatically on (AutoStartPolicy.AUTO_RECORD_KEY) when the sheet shows, which is
     * the first time Home shows after the update. An owner who never used it sees nothing about it. It needs no key
     * of its own: it shows with the rest of the sheet, and the sheet shows once.
     */
    static boolean whatsNewBackgroundNotice(String origin, boolean autoRecordOn) {
        return whatsNewBackgroundNotice(origin, autoRecordOn, null, false);
    }

    /**
     * As {@link #whatsNewBackgroundNotice(String, boolean)}, with two more sources. restoredNotice: a restore made it due
     * ({@link #RESTORED_NOTICE_KEY}), whatever the origin, while Record rides automatically is still on. legacyNotice:
     * Volt 1.13.2's own recording note (AutoStartPolicy.BACKGROUND_NOTICE_KEY, "due" or "done"), which told an owner the
     * same thing once in its What's new dialog: an owner updating from 1.13.2 who already closed it (or for whom it had
     * nothing to say) is not told again, as 1.13.2 promised to tell them once; one whose note was still due is.
     */
    static boolean whatsNewBackgroundNotice(String origin, boolean autoRecordOn, Object legacyNotice, boolean restoredNotice) {
        if (!autoRecordOn) return false;
        if (restoredNotice) return true;
        return ORIGIN_UPDATE.equals(origin) && !AutoStartPolicy.NOTICE_DONE.equals(legacyNotice);
    }
}
