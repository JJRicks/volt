package com.jjricks.volt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * More › App preferences › Language (plan section 5): the row's value and the picker sheet.
 *
 * The row is offered only once a language other than English ships (beta or stable in
 * l10n/lang/*.tsv), or with the pseudo APK while Developer options are on, where the sheet also lists
 * en-XA and ar-XB for layout checks. Until then English builds show no row at all.
 *
 * Choosing goes through AppLanguage.choose. On API 33 and later the platform's per-app language is the
 * source of truth: the framework recreates the activities, and the row mirrors getApplicationLocales()
 * into app_settings/app_language each time it renders. On API 26-32 Volt recreates the activity itself,
 * after ScooterService.applyLocale() has re-labelled the service's notices (5.3). The sheet is dismissed
 * before the change is applied, so no window leaks on recreate. The choice is per phone and never backed
 * up (BackupRegistry excludes app_language and app_language_migrated).
 */
final class LanguageSheet {
    private static Boolean pseudo;

    private LanguageSheet() {}

    /** Whether the Language row shows: a non-English language ships, or the pseudo APK in Developer options. */
    static boolean offered(Context context, boolean developer) {
        for (int i = 0; i < AppLanguages.size(); i++)
            if (AppLanguages.shipped(i) && !AppLanguages.ENGLISH.equals(AppLanguages.tag(i))) return true;
        return developer && pseudoBuild(context);
    }

    /** The row's value: "System default (Deutsch)", "English", "Español · Beta" or a pseudo locale's tag. */
    static String value(Context context) {
        String tag = choice(context);
        if (tag.isEmpty()) return T.moreLanguageSystem(systemName(context));
        int i = AppLanguages.indexOf(tag);
        if (i < 0) return tag;
        return beta(i) ? T.moreLanguageValueBeta(AppLanguages.endonym(i)) : AppLanguages.endonym(i);
    }

    /**
     * The picker. changed runs after a choice on API 33 and later, where the framework recreates the
     * activity only when the locale it resolves to changes, so the row's value can catch up.
     */
    static void show(ScreenHost host, boolean developer, Runnable changed) {
        Activity activity = host.activity();
        if (activity == null) return;
        Ui ui = host.ui();
        String current = choice(activity);
        List<String> tags = new ArrayList<>(), labels = new ArrayList<>();
        List<Boolean> betas = new ArrayList<>();
        tags.add(""); labels.add(T.moreLanguageSystem(systemName(activity))); betas.add(false);
        for (int i = 0; i < AppLanguages.size(); i++) {
            if (!AppLanguages.shipped(i)) continue;
            tags.add(AppLanguages.tag(i)); labels.add(AppLanguages.endonym(i)); betas.add(beta(i));
        }
        if (developer && pseudoBuild(activity)) {
            for (Locale locale : pseudoLocales()) {
                tags.add(locale.toLanguageTag()); labels.add(locale.toLanguageTag()); betas.add(false);
            }
        }
        final AlertDialog[] self = new AlertDialog[1];
        LinearLayout body = host.sheetContent(T.moreLanguageTitle(), null, () -> { if (self[0] != null) self[0].dismiss(); });
        body.setTag("language_sheet");
        LinearLayout options = ui.groupCard();
        for (int i = 0; i < tags.size(); i++) {
            final String tag = tags.get(i);
            boolean selected = tag.equals(current);
            LinearLayout option = new LinearLayout(activity);
            option.setOrientation(LinearLayout.HORIZONTAL); option.setGravity(Gravity.CENTER_VERTICAL);
            option.setMinimumHeight(ui.dp(52)); option.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
            option.setTag("language_option");
            TextView name = ui.text(labels.get(i), 15, selected);
            if (selected) name.setTextColor(ui.accent);
            option.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (betas.get(i)) {
                TextView chip = ui.text(T.moreLanguageBeta(), 11.5f, false);
                chip.setTextColor(ui.orange); chip.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2));
                chip.setBackground(ui.shape(0, 100, Ui.withAlpha(ui.orange, 120)));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = ui.dp(8);
                option.addView(chip, cp);
            }
            option.setFocusable(true); option.setClickable(true); option.setSelected(selected);
            if (Build.VERSION.SDK_INT >= 30 && selected) option.setStateDescription(T.commonSelected());
            option.setOnClickListener(v -> {
                if (self[0] != null) self[0].dismiss();
                if (tag.equals(current)) return;
                // Applied after the dismissal is drawn, so a recreate never finds the sheet's window.
                v.getRootView().post(() -> apply(host, tag, changed));
            });
            if (i > 0) ui.divider(options);
            options.addView(option, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(options, new LinearLayout.LayoutParams(-1, -2));
        TextView note = ui.caption(T.moreLanguageNote());
        note.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0);
        body.addView(note);
        if (Build.VERSION.SDK_INT >= 33) {
            TextView android = ui.caption(T.moreLanguageAndroidSettings());
            android.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0);
            body.addView(android);
        }
        self[0] = host.showSheet(body);
    }

    private static void apply(ScreenHost host, String tag, Runnable changed) {
        Activity activity = host.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AppLanguage.choose(activity, tag);
        if (Build.VERSION.SDK_INT >= 33) {
            if (changed != null) changed.run();
            return;
        }
        ScooterService service = host.service();
        if (service != null) service.applyLocale();
        activity.recreate();
    }

    /** The in-app choice; on API 33 and later the platform's per-app language, mirrored into the preference. */
    static String choice(Context context) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        if (Build.VERSION.SDK_INT < 33) return AppLanguage.choice(app);
        LocaleList platform = AppLanguage.Platform.get(app);
        String tag = platform.isEmpty() ? "" : platform.get(0).toLanguageTag();
        if (!tag.equals(AppLanguage.choice(app))) {
            // The platform's value came from Volt or from Android Settings; never push it back (migrate()).
            app.getSharedPreferences(AppLanguage.PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(AppLanguage.CHOICE, tag).putBoolean(AppLanguage.MIGRATED, true).apply();
        }
        return tag;
    }

    /** The phone's first language in its own words, ignoring Volt's choice ("Deutsch", "English"). */
    static String systemName(Context context) {
        LocaleList system = AppLanguage.systemLocales(context);
        Locale locale = system == null || system.isEmpty() ? Locale.getDefault() : system.get(0);
        String name = locale.getDisplayLanguage(locale);
        if (name.isEmpty()) return locale.toLanguageTag();
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    private static boolean beta(int i) {
        return "beta".equals(AppLanguages.STATUSES[i]);
    }

    /** The pseudo locales VOLT_PSEUDO_LOCALES=1 adds (plan 6.1); a release APK has no folder for them. */
    private static Locale[] pseudoLocales() {
        return new Locale[]{new Locale("en", "xa"), new Locale("ar", "xb")};
    }

    /**
     * True for the pseudo APK: its en-XA or ar-XB text differs from English. The release APK has no such
     * folder (build.sh fails if one appears), so Android falls back to English there; the framework's own
     * pseudo locales on an emulator do not count. Checked once per process.
     */
    static boolean pseudoBuild(Context context) {
        if (pseudo == null) {
            boolean found = false;
            try {
                Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
                String english = title(app, Locale.ENGLISH);
                for (Locale locale : pseudoLocales()) found |= !english.equals(title(app, locale));
            } catch (RuntimeException unavailable) {
                found = false;
            }
            pseudo = found;
        }
        return pseudo;
    }

    private static String title(Context app, Locale locale) {
        Configuration configuration = new Configuration();
        configuration.setLocales(new LocaleList(locale));
        String text = new AndroidText(app.createConfigurationContext(configuration).getResources()).string(T.MORE_LANGUAGE_TITLE);
        return text == null ? "" : text;
    }
}
