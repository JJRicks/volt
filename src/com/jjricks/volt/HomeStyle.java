package com.jjricks.volt;

/**
 * Which Home an owner sees (hub layout plan 1.8): Clean (a fixed Home) or Widgets (the dashboard they arrange).
 * Pure Java: the stored values, the one-time seed's decision and the What's new rule. HomeStylePrefs reads and
 * writes them in {@code app_settings}.
 *
 * <p>Absent or unknown means Widgets everywhere: an older Volt never wrote the key, and Widgets is the Home
 * whose saved layouts must keep showing. The seed (VoltApp, once per installation) is the only writer that
 * picks Clean without the owner: a fresh installation with no sign of an earlier owner. Switching style only
 * writes {@link #KEY}; nothing in either Home's storage is touched.
 */
final class HomeStyle {
    /** The style (app_settings, String, App preferences: a restore carries the owner's pick). */
    static final String KEY = "home_style";
    /** True once the owner picked a style themselves (App preferences, travels with {@link #KEY}). The seed never sets it. */
    static final String CHOSEN_KEY = "home_style_chosen";
    /** Why the seed chose what it did on this installation, {@link #ORIGIN_INSTALL} or {@link #ORIGIN_UPDATE} (Device). */
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

    /** The origin the same seed records with its choice: {@link #ORIGIN_UPDATE} exactly when it kept Widgets. */
    static String origin(boolean onboardingDone, boolean hasProfile, boolean hasLastPage, boolean hasDashboard,
                         long firstInstallTime, long lastUpdateTime) {
        return existingOwner(onboardingDone, hasProfile, hasLastPage, hasDashboard, firstInstallTime, lastUpdateTime) ? ORIGIN_UPDATE : ORIGIN_INSTALL;
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
     * mode open. A fresh installation never sees it; neither does a restore that brought an explicit choice.
     */
    static boolean showWhatsNew(String origin, boolean chosen, boolean shown, boolean onboardingShowing,
                                boolean riding, boolean onHome, boolean resumed) {
        return ORIGIN_UPDATE.equals(origin) && !chosen && !shown && !onboardingShowing && !riding && onHome && resumed;
    }
}
