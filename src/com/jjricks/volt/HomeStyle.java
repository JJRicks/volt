package com.jjricks.volt;

/**
 * Which Home an owner sees (hub layout plan 1.8): Clean (a fixed Home) or Widgets (the dashboard they arrange).
 * Pure Java: the stored values, the one-time seed's decision and the What's new rule. HomeStylePrefs reads and
 * writes them in {@code app_settings}.
 *
 * <p>Absent or unknown means Widgets everywhere: an older Volt never wrote the key, and Widgets is the Home
 * whose saved layouts must keep showing. The seed (VoltApp, at each app start while no style is stored)
 * writes nothing on a clean new installation, so its app data stays empty until setup ends (its last step's
 * pick, or Clean when setup is skipped); it records Widgets and {@link #ORIGIN_UPDATE} when it finds an earlier owner, and once an
 * origin is stored it only re-derives the style from it (a restore that dropped the style). Switching style
 * only writes {@link #KEY}; nothing in either Home's storage is touched.
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
     * setup finished, a scooter profile, a recorded last page, a saved dashboard, or an installation that was
     * updated (its first install time differs from its last update time). Otherwise this is a new install: Clean.
     */
    static String initial(boolean onboardingDone, boolean hasProfile, boolean hasLastPage, boolean hasDashboard,
                          long firstInstallTime, long lastUpdateTime) {
        return existingOwner(onboardingDone, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime) ? WIDGETS : CLEAN;
    }

    /** The origin that goes with {@link #initial}: {@link #ORIGIN_UPDATE} exactly when it keeps Widgets. */
    static String origin(boolean onboardingDone, boolean hasProfile, boolean hasLastPage, boolean hasDashboard,
                         long firstInstallTime, long lastUpdateTime) {
        return existingOwner(onboardingDone, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime) ? ORIGIN_UPDATE : ORIGIN_INSTALL;
    }

    /**
     * What the seed writes while no style is stored, as {style, origin}, or null to write nothing. A stored origin
     * is never re-decided: {@link #ORIGIN_INSTALL} gives Clean and {@link #ORIGIN_UPDATE} Widgets, so a restore that
     * dropped the style (a pre-Hub backup, Replace everything) keeps this installation's history and its What's new
     * rule. With no origin, an earlier owner records Widgets and {@link #ORIGIN_UPDATE}; a clean new installation
     * writes nothing (absent reads as Widgets), so app data stays empty at app start.
     */
    static String[] reseed(String storedOrigin, boolean onboardingDone, boolean hasProfile, boolean hasLastPage,
                           boolean hasDashboard, long firstInstallTime, long lastUpdateTime) {
        return reseed(storedOrigin, onboardingDone, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime, false);
    }

    /**
     * {@link #reseed}, told whether a first setup is under way (setup never finished or skipped, not a rerun, and past
     * its first page or holding the profile it created). While it is, nothing is written even when a profile exists:
     * setup made that profile itself, so it is no sign of an earlier owner. Setup's end decides instead
     * (HomeStylePrefs.markChosen on its last step, HomeStylePrefs.setupSkipped on a skip), and its last step keeps
     * Clean preselected after the app restarts mid-setup.
     */
    static String[] reseed(String storedOrigin, boolean onboardingDone, boolean hasProfile, boolean hasLastPage,
                           boolean hasDashboard, long firstInstallTime, long lastUpdateTime, boolean firstSetupInProgress) {
        if (ORIGIN_INSTALL.equals(storedOrigin)) return new String[]{CLEAN, ORIGIN_INSTALL};
        if (ORIGIN_UPDATE.equals(storedOrigin)) return new String[]{WIDGETS, ORIGIN_UPDATE};
        if (firstSetupInProgress) return null;
        if (existingOwner(onboardingDone, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime))
            return new String[]{WIDGETS, ORIGIN_UPDATE};
        return null;
    }

    /** The origin an explicit pick records when none is stored: the seed found no earlier owner, so a new install. */
    static String pickedOrigin(String storedOrigin) {
        return ORIGIN_INSTALL.equals(storedOrigin) || ORIGIN_UPDATE.equals(storedOrigin) ? storedOrigin : ORIGIN_INSTALL;
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
     * installation with no origin records {@link #ORIGIN_INSTALL}, and one whose origin is install and that has no style
     * gets Clean, the style the seed would give it at the next start anyway. A stored style is never changed.
     */
    static String[] afterSkippedSetup(String storedOrigin, boolean styleStored) {
        String origin = storedOrigin == null ? ORIGIN_INSTALL : null;
        String style = !styleStored && ORIGIN_INSTALL.equals(storedOrigin == null ? ORIGIN_INSTALL : storedOrigin) ? CLEAN : null;
        return new String[]{style, origin};
    }

    private static boolean existingOwner(boolean onboardingDone, boolean hasProfile, boolean hasLastPage, boolean hasDashboard,
                                         long firstInstallTime, long lastUpdateTime) {
        return onboardingDone || hasProfile || hasLastPage || hasDashboard || firstInstallTime != lastUpdateTime;
    }

    /** Whether a stored dashboard key name is a saved widget dashboard (any scooter, or the unconfigured one). */
    static boolean dashboardKey(String key) {
        return key != null && key.startsWith("dashboard_v1_") && key.length() > "dashboard_v1_".length();
    }

    /**
     * Whether the one-time What's new sheet shows now (Joel 6): only on an installation the seed found updated,
     * whose owner never chose a style and never saw the sheet, on Home, resumed, with neither setup nor riding
     * mode open. A fresh installation never sees it once setup recorded its origin (HomeStylePrefs.setupSkipped
     * or markChosen); neither does a restore that brought an explicit choice.
     */
    static boolean showWhatsNew(String origin, boolean chosen, boolean shown, boolean onboardingShowing,
                                boolean riding, boolean onHome, boolean resumed) {
        return ORIGIN_UPDATE.equals(origin) && !chosen && !shown && !onboardingShowing && !riding && onHome && resumed;
    }
}
