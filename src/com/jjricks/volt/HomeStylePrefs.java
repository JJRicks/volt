package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import java.util.Map;

/**
 * HomeStyle's keys in {@code app_settings}: reading the style, an owner's explicit pick, and the one-time seed
 * VoltApp runs before any Activity exists (hub layout plan 1.8). The rules themselves are in HomeStyle.
 */
final class HomeStylePrefs {
    private HomeStylePrefs() {}

    /** The style to show: Clean only when "clean" is stored; absent or anything else is Widgets. */
    static String of(SharedPreferences prefs) {
        Object stored;
        try { stored = prefs.getAll().get(HomeStyle.KEY); } catch (RuntimeException e) { stored = null; }
        return HomeStyle.of(stored);
    }

    /** An owner's explicit pick (Settings, Home's edit sheet, the Layout sheet, setup, What's new): the style and that it was chosen. */
    static void markChosen(SharedPreferences prefs, String style) {
        prefs.edit().putString(HomeStyle.KEY, HomeStyle.valid(style)).putBoolean(HomeStyle.CHOSEN_KEY, true).apply();
    }

    /**
     * Once per installation, when no style is stored: records the seed's style and its origin together in one
     * commit, so the decision is never made twice. A failure to read the package times counts as an update
     * (Widgets), the choice that keeps saved layouts on screen.
     */
    static void seed(Context context, SharedPreferences app) {
        if (app.contains(HomeStyle.KEY)) return;
        Map<String, ?> all = app.getAll();
        boolean hasDashboard = false;
        for (String key : all.keySet()) if (HomeStyle.dashboardKey(key)) { hasDashboard = true; break; }
        boolean hasLastPage = all.containsKey(MaintenanceScreen.LAST_PAGE);
        boolean onboardingDone = context.getSharedPreferences(OnboardingScreen.PREFS, Context.MODE_PRIVATE).getBoolean(OnboardingScreen.KEY_DONE, false);
        String items = context.getSharedPreferences("profiles", Context.MODE_PRIVATE).getString("items", "");
        boolean hasProfile = (items != null && items.contains("\"id\""))
                || !context.getSharedPreferences("scooter", Context.MODE_PRIVATE).getAll().isEmpty();
        long first = 0, last = 1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            first = info.firstInstallTime; last = info.lastUpdateTime;
        } catch (Exception ignored) {
            // Unknown history: first != last, so the seed treats it as an update.
        }
        app.edit()
                .putString(HomeStyle.KEY, HomeStyle.initial(onboardingDone, hasProfile, hasLastPage, hasDashboard, first, last))
                .putString(HomeStyle.ORIGIN_KEY, HomeStyle.origin(onboardingDone, hasProfile, hasLastPage, hasDashboard, first, last))
                .commit();
    }
}
