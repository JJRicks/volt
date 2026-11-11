package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import java.util.Map;

/**
 * HomeStyle's keys in {@code app_settings}: reading the style, an owner's explicit pick, and the seed VoltApp
 * runs before any Activity exists (hub layout plan 1.8). The rules themselves are in HomeStyle.
 */
final class HomeStylePrefs {
    private HomeStylePrefs() {}

    /** The style to show: Clean only when "clean" is stored; absent or anything else is Widgets. */
    static String of(SharedPreferences prefs) {
        Object stored;
        try { stored = prefs.getAll().get(HomeStyle.KEY); } catch (RuntimeException e) { stored = null; }
        return HomeStyle.of(stored);
    }

    /**
     * An owner's explicit pick (Settings, Home's edit sheet, the Layout sheet, setup, What's new): the style, that it
     * was chosen, and, when the seed left this installation alone (a new install), its origin.
     */
    static void markChosen(SharedPreferences prefs, String style) {
        String origin;
        try { Object stored = prefs.getAll().get(HomeStyle.ORIGIN_KEY); origin = stored instanceof String ? (String) stored : null; }
        catch (RuntimeException e) { origin = null; }
        SharedPreferences.Editor edit = prefs.edit().putString(HomeStyle.KEY, HomeStyle.valid(style)).putBoolean(HomeStyle.CHOSEN_KEY, true);
        if (origin == null) edit.putString(HomeStyle.ORIGIN_KEY, HomeStyle.pickedOrigin(null));
        edit.apply();
    }

    /**
     * Setup ending on a new install, finished or skipped (the onboarding part calls it): records origin install when
     * none is stored, so a later start never mistakes this owner's own setup for an earlier owner. Writes no style.
     */
    static void recordNewInstall(SharedPreferences prefs) {
        if (!prefs.contains(HomeStyle.ORIGIN_KEY)) prefs.edit().putString(HomeStyle.ORIGIN_KEY, HomeStyle.ORIGIN_INSTALL).apply();
    }

    /**
     * At each app start, when no style is stored (HomeStyle.reseed): a stored origin is kept and only gives the
     * style back; otherwise an earlier owner records Widgets and its origin together in one commit, and a clean
     * new installation writes nothing, so fresh app data stays empty. A failure to read the package times counts
     * as an update (Widgets), the choice that keeps saved layouts on screen.
     */
    static void seed(Context context, SharedPreferences app) {
        if (app.contains(HomeStyle.KEY)) return;
        Map<String, ?> all = app.getAll();
        Object storedOrigin = all.get(HomeStyle.ORIGIN_KEY);
        String origin = storedOrigin instanceof String ? (String) storedOrigin : null;
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
        String[] seed = HomeStyle.reseed(origin, onboardingDone, hasProfile, hasLastPage, hasDashboard, first, last);
        if (seed == null) return;
        SharedPreferences.Editor edit = app.edit().putString(HomeStyle.KEY, seed[0]);
        if (!seed[1].equals(origin)) edit.putString(HomeStyle.ORIGIN_KEY, seed[1]);
        edit.commit();
    }
}
