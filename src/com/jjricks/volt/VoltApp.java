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
    /** The last crash's record in no-backup storage (CrashRecord; BackupRegistry.ROOTS lists it as device diagnostics). */
    static final String CRASH_FILE = "last-crash";

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        AppLanguage.install(this);
    }

    @Override public void onCreate() {
        super.onCreate();
        // The last uncaught crash, for Report an issue's diagnostics file only (CrashRecord): nothing is read or written
        // until a crash, and then only the exception's class and Volt's own frames, never its message.
        try { CrashRecord.install(new java.io.File(getNoBackupFilesDir(), CRASH_FILE), AppVersion.name(this)); } catch (RuntimeException unavailable) { }
        AppLanguage.migrate(this);
        // Ride auto-save's retired Route CSV switch becomes the one switch before anything reads or backs it up.
        RideAutoExportFolder.migrate(getSharedPreferences("app_settings", MODE_PRIVATE));
        // Whether this installation owes its owner the one-time note about starting recordings in the background, decided
        // before anything can change the switches it is decided from (AutoStartPolicy.backgroundNoticeSeed).
        seedBackgroundNotice(getSharedPreferences("app_settings", MODE_PRIVATE));
        // The opt-in daily update check is not a persisted job; each process start puts it back (UpdateCheckJob).
        UpdateCheckJob.sync(this);
    }

    private void seedBackgroundNotice(android.content.SharedPreferences settings) {
        try {
            Object stored = settings.getAll().get(AutoStartPolicy.BACKGROUND_NOTICE_KEY);
            String seeded = AutoStartPolicy.backgroundNoticeSeed(stored instanceof String ? (String) stored : null, settings.getAll().isEmpty(), updated(),
                    settings.getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), settings.getBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false));
            if (seeded != null && !seeded.equals(stored)) settings.edit().putString(AutoStartPolicy.BACKGROUND_NOTICE_KEY, seeded).apply();
        } catch (ClassCastException wrongType) {
            // A switch stored with another type: no note, never a crash at start.
        }
    }

    /** Whether this installation was updated since it was installed; unreadable times count as updated. */
    private boolean updated() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.firstInstallTime != info.lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unreadable) {
            return true;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AppLanguage.configurationChanged(this);
        UpdateCheckJob.relabel(this);
    }
}
