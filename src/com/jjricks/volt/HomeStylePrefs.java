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
     * At each app start (VoltApp) and again when an interrupted restore has been recovered (DataMaintenance):
     * decides from settled data only, so it does nothing while a restore still waits for recovery. While no style
     * is stored: a stored origin is kept and only gives the style back; otherwise an earlier owner records Widgets
     * and its origin together in one commit, and a clean new installation, or one whose first-run setup is still
     * under way, writes nothing (HomeStyle.reseed). While a style is stored but no origin (a restore, which never
     * carries it): an earlier owner's origin is added, and the style is left as stored (HomeStyle.backfillOrigin).
     * A failure to read the package times counts as an update (Widgets), the choice that keeps saved layouts on screen.
     */
    static void seed(Context context, SharedPreferences app) {
        Map<String, ?> all = app.getAll();
        Object storedOrigin = all.get(HomeStyle.ORIGIN_KEY);
        String origin = storedOrigin instanceof String ? (String) storedOrigin : null;
        boolean styleStored = all.containsKey(HomeStyle.KEY);
        boolean originKnown = HomeStyle.ORIGIN_INSTALL.equals(origin) || HomeStyle.ORIGIN_UPDATE.equals(origin);
        if (styleStored && originKnown) return;
        // A half-applied restore must not decide this installation's history: its rollback would leave the decision.
        // Read-only check: the startup gates (MainActivity, ScooterService) still decide recovery themselves.
        if (DataMaintenance.recoveryPending(context)) return;
        boolean hasDashboard = false;
        for (String key : all.keySet()) if (HomeStyle.dashboardKey(key)) { hasDashboard = true; break; }
        boolean hasLastPage = all.containsKey(MaintenanceScreen.LAST_PAGE);
        boolean setupFinished = OnboardingScreen.finishedOnce(context);
        boolean setupPending = OnboardingScreen.firstRunPending(context);
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
        if (styleStored) {
            String backfill = HomeStyle.backfillOrigin(origin, setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, first, last);
            if (backfill != null) app.edit().putString(HomeStyle.ORIGIN_KEY, backfill).commit();
            return;
        }
        String[] seed = HomeStyle.reseed(origin, setupFinished, setupPending, hasProfile, hasLastPage, hasDashboard, first, last);
        if (seed == null) return;
        SharedPreferences.Editor edit = app.edit().putString(HomeStyle.KEY, seed[0]);
        if (!seed[1].equals(origin)) edit.putString(HomeStyle.ORIGIN_KEY, seed[1]);
        edit.commit();
    }
}
