package com.jjricks.volt;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

/**
 * Volt's Application (named in AndroidManifest.xml). It only connects the text catalog to Android
 * resources: attachBaseContext runs before any Activity, Service or ContentProvider is created, so
 * every T and Msg lookup after that reads the resolved resources, and a configuration change
 * re-applies Volt's language choice (AppLanguage). Until then, and if that fails, T reads its built-in
 * English.
 *
 * The Shizuku shell process (OwnerCaptureUserService) never creates this class and never uses T.
 */
public final class VoltApp extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        AppLanguage.install(this);
    }

    @Override public void onCreate() {
        super.onCreate();
        AppLanguage.migrate(this);
        // Ride auto-save's retired Route CSV switch becomes the one switch before anything reads or backs it up.
        RideAutoExportFolder.migrate(getSharedPreferences("app_settings", MODE_PRIVATE));
        // Home style (HomeStyle): before any Activity reads it. An earlier owner records Widgets; a clean new
        // install, or one still in first-run setup, writes nothing (absent reads as Widgets) so fresh app data stays
        // empty; a stored origin is kept. While a restore waits for recovery it waits too (DataMaintenance runs it then).
        HomeStylePrefs.seed(this, getSharedPreferences("app_settings", MODE_PRIVATE));
        // The opt-in daily update check is not a persisted job; each process start puts it back (UpdateCheckJob).
        UpdateCheckJob.sync(this);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AppLanguage.configurationChanged(this);
        UpdateCheckJob.relabel(this);
    }
}
