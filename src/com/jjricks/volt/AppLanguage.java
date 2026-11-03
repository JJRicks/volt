package com.jjricks.volt;

import android.annotation.TargetApi;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

/**
 * Volt's language choice (plan section 5) and where catalog text comes from.
 *
 * The choice is "" for System default (Volt follows the phone) or a language tag. On API 33 and later
 * the platform's per-app language is the source of truth and the framework applies it; on API 26-32
 * Volt applies it itself with a sparse configuration that carries only the locales, so screen size,
 * night mode and font scale still come from the system.
 *
 * With System default, which is every installation until the Language row ships, nothing here changes
 * how Volt runs: {@link #wrap} returns its argument, {@link #resources} returns the application's own
 * Resources, and nothing is written.
 *
 * Never used by the Shizuku shell process (OwnerCaptureUserService); CatalogUsageTest checks that.
 */
public final class AppLanguage {
    static final String PREFERENCES = "app_settings";
    /** The in-app choice: "" for System default, else a BCP 47 tag. Per phone, never backed up. */
    static final String CHOICE = "app_language";
    /** Set once the API 33+ platform list has been seeded from an API 26-32 choice (after an OS upgrade). */
    static final String MIGRATED = "app_language_migrated";

    private static final Object LOCK = new Object();
    private static String cachedTag;
    private static Resources cachedResources;

    private AppLanguage() {}

    /** The in-app choice, "" for System default. */
    public static String choice(Context context) {
        String tag = preferences(context).getString(CHOICE, "");
        return tag == null ? "" : tag.trim();
    }

    /**
     * The Context an Activity attaches on API 26-32, so it shows the in-app choice. System default, and
     * every call on API 33 and later, returns base itself.
     */
    public static Context wrap(Context base) {
        if (base == null || Build.VERSION.SDK_INT >= 33) return base;
        String tag = choice(base);
        if (tag.isEmpty()) return base;
        return base.createConfigurationContext(sparse(tag));
    }

    /**
     * The Resources catalog text comes from. API 33 and later, and System default: the application's own
     * Resources, which the framework keeps current. API 26-32 with a choice: a cached configuration
     * context for that language, rebuilt when the choice changes.
     */
    public static Resources resources(Context context) {
        Context app = application(context);
        if (Build.VERSION.SDK_INT >= 33) return app.getResources();
        String tag = choice(app);
        if (tag.isEmpty()) return app.getResources();
        synchronized (LOCK) {
            if (cachedResources == null || !tag.equals(cachedTag)) {
                cachedResources = app.createConfigurationContext(sparse(tag)).getResources();
                cachedTag = tag;
            }
            return cachedResources;
        }
    }

    /**
     * Installs Android resources as T's text source. VoltApp calls this from attachBaseContext, before
     * any Activity, Service or ContentProvider starts, and again after every configuration or choice
     * change. If anything fails, T keeps its English source rather than stopping the app.
     */
    static void install(Context context) {
        try {
            Context app = application(context);
            if (Build.VERSION.SDK_INT < 33) {
                String tag = choice(app);
                if (!tag.isEmpty()) LocaleList.setDefault(LocaleList.forLanguageTags(tag));
            }
            T.install(new AndroidText(resources(app)));
        } catch (RuntimeException error) {
            T.install(null);
        }
    }

    /** VoltApp.onConfigurationChanged: the system configuration changed, so re-apply the choice over it. */
    static void configurationChanged(Context context) {
        synchronized (LOCK) {
            cachedResources = null;
            cachedTag = null;
        }
        install(context);
    }

    /**
     * Records a new choice ("" for System default) and applies it to catalog text. On API 33 and later the
     * platform list is set too, and the framework recreates the activities. On API 26-32 the caller
     * recreates the visible activity (plan section 5.3).
     */
    static void choose(Context context, String tag) {
        String value = tag == null ? "" : tag.trim();
        Context app = application(context);
        SharedPreferences.Editor editor = preferences(app).edit().putString(CHOICE, value);
        // A choice made on API 33+ is already the platform's; migrate() must never push it back later
        // (for example after the user resets Volt to System default in Android Settings).
        if (Build.VERSION.SDK_INT >= 33) editor.putBoolean(MIGRATED, true);
        editor.commit();
        if (Build.VERSION.SDK_INT >= 33) {
            Platform.set(app, value);
        } else if (value.isEmpty()) {
            LocaleList.setDefault(Resources.getSystem().getConfiguration().getLocales());
        }
        configurationChanged(app);
    }

    /**
     * One-time migration after an OS upgrade from Android 12 or earlier: an in-app choice made there is
     * pushed to the platform once, if the platform has none. Does nothing, not even a system call, when
     * the choice is System default.
     */
    static void migrate(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;
        Context app = application(context);
        String tag = choice(app);
        if (tag.isEmpty()) return;
        SharedPreferences preferences = preferences(app);
        if (preferences.getBoolean(MIGRATED, false)) return;
        if (Platform.get(app).isEmpty()) Platform.set(app, tag);
        preferences.edit().putBoolean(MIGRATED, true).apply();
    }

    /** The phone's own languages, ignoring Volt's choice (for "System default (Deutsch)"). */
    static LocaleList systemLocales(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return Platform.system(application(context));
        return Resources.getSystem().getConfiguration().getLocales();
    }

    private static Configuration sparse(String tag) {
        Configuration configuration = new Configuration();
        configuration.setLocales(LocaleList.forLanguageTags(tag));
        return configuration;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static Context application(Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    /** The API 33+ platform per-app language (LocaleManager); loaded only on API 33 and later. */
    @TargetApi(33)
    static final class Platform {
        private Platform() {}

        static LocaleList get(Context context) {
            return context.getSystemService(LocaleManager.class).getApplicationLocales();
        }

        static void set(Context context, String tag) {
            context.getSystemService(LocaleManager.class).setApplicationLocales(
                    tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        }

        static LocaleList system(Context context) {
            return context.getSystemService(LocaleManager.class).getSystemLocales();
        }
    }
}
