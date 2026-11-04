package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B11: the Backup screen keeps its session through a language change. BackupActivity is an Activity, so
 * a plain JVM test reads its source and the manifest; the emulator census runs the screen itself.
 *   - the manifest handles locale and layoutDirection for BackupActivity, so Android does not recreate
 *     it (a recreated BackupActivity ends the session: onCreate's interrupted path drops a staged
 *     restore, the typed passwords and the page);
 *   - attachBaseContext wraps the base with AppLanguage.wrap, like every Volt Activity;
 *   - onConfigurationChanged compares the locale list and relabels in place instead of rebuilding a
 *     page, so a rotation or font change does nothing new;
 *   - the Save hint names the Save backup button through the button's short rung, which is the first
 *     word of the full label in English.
 */
public final class BackupScreenTest {
    private static int checks;

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        String manifest = read("AndroidManifest.xml");
        Matcher activity = Pattern.compile("<activity android:name=\"\\.BackupActivity\"[^>]*>", Pattern.DOTALL).matcher(manifest);
        check(activity.find(), "the manifest declares BackupActivity");
        Matcher changes = Pattern.compile("android:configChanges=\"([^\"]*)\"").matcher(activity.group());
        check(changes.find(), "BackupActivity handles configuration changes itself");
        java.util.List<String> handled = java.util.Arrays.asList(changes.group(1).split("\\|"));
        for (String kind : new String[]{"orientation", "screenSize", "smallestScreenSize", "screenLayout", "keyboardHidden", "locale", "layoutDirection"})
            check(handled.contains(kind), "BackupActivity handles " + kind + " without being recreated");

        String code = read("src/com/jjricks/volt/BackupActivity.java");
        check(code.contains("protected void attachBaseContext(android.content.Context base){super.attachBaseContext(AppLanguage.wrap(base));}"),
                "BackupActivity attaches AppLanguage.wrap(base)");
        int changed = code.indexOf("public void onConfigurationChanged(");
        check(changed > 0, "BackupActivity overrides onConfigurationChanged");
        String body = code.substring(changed, code.indexOf("\n    }", changed));
        check(body.contains("now.equals(shownLocales))return;"), "only a new locale list relabels the screen");
        check(body.contains("handler.post(this::relabel)") && !body.contains("recreate()") && !body.contains("Form()"),
                "a language change relabels in place; it never recreates the Activity or rebuilds the form");
        check(code.contains("status.setText(M.backupPickerFailedSave(T.backupSaveShort()))"), "the Save hint names the button's short rung");
        check(T.backupSave().startsWith(T.backupSaveShort() + " "), "English: Save is the first word of Save backup");
        check(T.backupPickerFailedSave(T.backupSaveShort()).equals("A file picker could not open. Tap Save to try again."),
                "English: the Save hint is unchanged");
        System.out.println("BackupScreenTest: " + checks + " checks passed (manifest configChanges, AppLanguage.wrap, "
                + "in-place relabel on a locale change, the Save hint)");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
