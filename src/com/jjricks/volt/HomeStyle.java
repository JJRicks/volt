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
 * style (a restore). A restore of a backup without a style keeps this phone's style and choice (BackupArchive).
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
    /** The one-time What's new sheet was shown on this installation (Device). Set when shown, not when answered. */
    static final String WHATS_NEW_SHOWN_KEY = "home_whats_new_shown";

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
     * is kept (part 6's rule), and a phone with neither a style nor an install origin is left as it is (absent reads as
     * Widgets, and the seed's earlier-owner rule keeps it so).
     */
    static boolean preHubRestoreGivesWidgets(boolean chosen, boolean styleStored, Object storedOrigin) {
        return !chosen && (styleStored || ORIGIN_INSTALL.equals(storedOrigin));
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
     * that never saw the sheet, on Home, resumed, with neither setup nor riding mode open. Choosing a style first
     * (in settings, or a restore that brought a choice) does not skip it: the sheet also carries the changelog and
     * one-time notices; the choice only hides its offer to switch ({@link #offerSwitch}). A fresh installation never
     * sees it once setup recorded its origin (HomeStylePrefs.recordNewInstall or markChosen).
     */
    static boolean showWhatsNew(String origin, boolean shown, boolean onboardingShowing,
                                boolean riding, boolean onHome, boolean resumed) {
        return ORIGIN_UPDATE.equals(origin) && !shown && !onboardingShowing && !riding && onHome && resumed;
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
     * Whether the What's new sheet also carries the notice that a recording now starts in the background only when
     * the owner turns that on (owner's answer on #403): only on an installation the seed found updated, and only for
     * an owner who has Record rides automatically on (AutoStartPolicy.AUTO_RECORD_KEY) when the sheet shows, which is
     * the first time Home shows after the update. An owner who never used it sees nothing about it. It needs no key
     * of its own: it shows with the rest of the sheet, and the sheet shows once.
     */
    static boolean whatsNewBackgroundNotice(String origin, boolean autoRecordOn) {
        return ORIGIN_UPDATE.equals(origin) && autoRecordOn;
    }
}
