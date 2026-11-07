package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterService;

/**
 * Pseudo-locale and shipped-language layout checks (plan sections 6.2 and 7.2 step 6), skeleton: B0b walks
 * the five main pages and the More sections, B17 the setup pages; later batches add the sheets, BackupActivity
 * and ShizukuSetupActivity. Report only: the result is PASS whenever the walk completes, and the findings are
 * counted in the result bundle and listed in locale-validation/<variant>-report.txt. Needs API 33 or later.
 * en-XA and ar-XB need the pseudo APK (VOLT_PSEUDO_LOCALES=1 build.sh); with the release APK it says the
 * pseudo resources did not resolve. Any other tag is a shipped language (status beta or stable in
 * l10n/lang/<tag>.tsv) on the normal app APK; the walk fails unless the app's l10n_tag string reads that tag.
 *
 *   -e locale en-XA|ar-XB|<tag>  (default en-XA)   -e rtl true|false  (a shipped language; default false)
 *   -e theme dark|light   -e text_scale 1|1.4   -e variant <name>
 *
 * The language is set from inside the app process with LocaleManager.setApplicationLocales, as the
 * Language row will, and cleared again at the end. Views are found by tag or field only, never by text.
 *   en-XA: text outside aapt's [..] pseudo groups that still has two plain letters was not extracted;
 *          more than one [..] group in one view means pieces were glued; a visible %1$s means a template
 *          got no arguments; plus TextFit's fit checks.
 *   ar-XB: every window root must lay out right to left; plus TextFit's fit checks.
 *   <tag>: TextFit's fit checks; with -e rtl true (rtl in l10n/lang/<tag>.tsv) also ar-XB's direction check.
 */
public final class LocaleValidation extends Instrumentation {
    private static final String[] PAGES = {"home", "stats", "controls", "rides", "more"};
    /** OnboardingScreen's steps WELCOME (0) to DONE (10), in order, as screenshot labels. */
    private static final String[] SETUP_STEPS = {"welcome", "path", "prepare", "usb", "computer", "helper",
            "import", "identity", "nearby", "connect", "done"};
    private static final String[] MORE_SECTIONS = {"App", "Scooters", "Security", "Lab", "Data", "About", "Developer"};
    private static final Pattern PSEUDO_GROUP = Pattern.compile("\\[[^\\[\\]]*\\]");
    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern UNFILLED = Pattern.compile("%\\d+\\$[sdf]");
    private static final Pattern LANGUAGE_TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*");
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted, localeSet;
    private String locale;
    private boolean pseudo, rtl;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final List<String> unextracted = new ArrayList<>(), glued = new ArrayList<>(), unfilled = new ArrayList<>(),
            fit = new ArrayList<>(), direction = new ArrayList<>();
    private int screens;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        locale = arguments.getString("locale", "en-XA");
        try {
            pseudo = locale.equals("en-XA") || locale.equals("ar-XB");
            require(pseudo || LANGUAGE_TAG.matcher(locale).matches() && !locale.equals("en"), "locale must be en-XA, ar-XB or a shipped language tag");
            rtl = locale.equals("ar-XB") || !pseudo && Boolean.parseBoolean(arguments.getString("rtl", "false"));
            if (Build.VERSION.SDK_INT < 33) {
                result.putString("result", "PASS"); result.putString("skipped", "LocaleManager needs API 33");
                return;
            }
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", arguments.getString("theme", "dark"))
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            main(() -> OnboardingScreen.markDone(getTargetContext()));
            setLocale(locale); localeSet = true;
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.getWindow().getDecorView().isAttachedToWindow() && activity.getWindow().getDecorView().getWidth() > 0, "App did not launch");
            settle();
            Locale applied = activity.getResources().getConfiguration().getLocales().get(0);
            if (pseudo) {
                String expected = locale.equals("en-XA") ? "XA" : "XB";
                require(expected.equals(applied.getCountry()), "the activity did not get " + locale);
                int label = getTargetContext().getResources().getIdentifier("app_name", "string", getTargetContext().getPackageName());
                require(label != 0 && !"Volt".equals(activity.getString(label)), "the pseudo-locale resources did not resolve; install the pseudo APK (VOLT_PSEUDO_LOCALES=1 build.sh)");
            } else {
                require(Locale.forLanguageTag(locale).getLanguage().equals(applied.getLanguage()), "the activity did not get " + locale + " (it has " + applied.toLanguageTag() + ")");
                int tag = getTargetContext().getResources().getIdentifier("l10n_tag", "string", getTargetContext().getPackageName());
                String resolved = tag == 0 ? null : activity.getString(tag);
                require(locale.equals(resolved), "the " + locale + " resources did not resolve: l10n_tag reads " + resolved
                        + "; is " + locale + " shipped (res/values-<qual>/, beta or stable) in this APK?");
            }
            for (String page : PAGES) {
                select(page); check("page-" + page);
            }
            select("more");
            for (String section : MORE_SECTIONS) {
                main(() -> invoke(field(activity, "screen"), "selectSection", new Class<?>[]{String.class}, section)); settle();
                check("more-" + section.toLowerCase(Locale.ROOT));
            }
            main(() -> invoke(field(activity, "screen"), "selectSection", new Class<?>[]{String.class}, "")); settle();
            walkSetup();
            writeReport();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
        } finally {
            result.putString("locale", locale); result.putBoolean("pseudo", pseudo); result.putInt("screens", screens);
            result.putInt("unextracted", unextracted.size()); result.putInt("glued", glued.size()); result.putInt("unfilled", unfilled.size());
            result.putInt("fit_problems", fit.size()); result.putInt("direction_problems", direction.size());
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false); result.putBoolean("report_only", true);
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            if (localeSet) try { setLocale(""); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /**
     * B17: every setup page on the computer-capture route, opened as "Run setup again" resumes them
     * (the saved step, then a rebuild), so no button is tapped and nothing connects or imports. Setup is
     * marked done again afterwards.
     */
    private void walkSetup() throws Exception {
        for (int setupStep = 0; setupStep < SETUP_STEPS.length; setupStep++) {
            final int savedStep = setupStep;
            require(getTargetContext().getSharedPreferences(OnboardingScreen.PREFS, Context.MODE_PRIVATE).edit().clear()
                    .putBoolean("rerun", true).putInt("step", savedStep).putString("path", "capture").commit(), "Setup fixture failed");
            main(() -> activity.rebuild()); settle();
            require(field(activity, "onboarding") != null, "Setup walk: setup did not open at step " + savedStep);
            check("setup-" + SETUP_STEPS[savedStep]);
        }
        main(() -> { OnboardingScreen.markDone(getTargetContext()); activity.rebuild(); }); settle();
    }

    private void setLocale(String tag) {
        LocaleManager manager = getTargetContext().getSystemService(LocaleManager.class);
        manager.setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        SystemClock.sleep(500); waitForIdleSync();
    }

    /** One screen: screenshot and census, then the fit, extraction and direction checks over every window. */
    private void check(String label) throws Exception {
        screens++;
        screenshot(label);
        main(() -> {
            View root = activity.getWindow().getDecorView();
            if (rtl && root.getLayoutDirection() != View.LAYOUT_DIRECTION_RTL) direction.add(label + ": the window lays out left to right");
            for (TextView text : texts(root)) {
                TextFit.collectNarrowTextProblems(text, label + " " + where(text), fit);
                if (!locale.equals("en-XA") || !text.isShown()) continue;
                String value = text.getText().toString();
                Matcher groups = PSEUDO_GROUP.matcher(value);
                int count = 0;
                while (groups.find()) count++;
                if (count > 1) glued.add(label + " " + where(text) + ": " + value);
                if (UNFILLED.matcher(value).find()) unfilled.add(label + " " + where(text) + ": " + value);
                if (PLAIN_WORD.matcher(PSEUDO_GROUP.matcher(value).replaceAll(" ")).find()) unextracted.add(label + " " + where(text) + ": " + value);
            }
        });
    }

    private void writeReport() throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("locale ").append(locale).append(", ").append(screens).append(" screens (report only)\n");
        section(out, "Not extracted (plain English outside [..])", unextracted);
        section(out, "Glued pieces (more than one [..] group)", glued);
        section(out, "Unfilled placeholders", unfilled);
        section(out, "Text fit", fit);
        section(out, "Layout direction", direction);
        try (FileOutputStream file = new FileOutputStream(new File(folder(), variant() + "-report.txt"))) { file.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
    }

    private static void section(StringBuilder out, String title, List<String> lines) {
        out.append('\n').append(title).append(": ").append(lines.size()).append('\n');
        for (String line : lines) out.append("  ").append(line.replace('\n', '|')).append('\n');
    }

    private File screenshot(String label) throws Exception {
        settle(); SystemClock.sleep(300); settle();
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File file = new File(folder(), variant() + "-" + label + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
        return file;
    }

    private File folder() {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "locale-validation");
        require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        return dir;
    }

    private String variant() { return arguments.getString("variant", locale).replaceAll("[^a-zA-Z0-9_.-]", ""); }

    private static String where(View view) { return view.getTag() instanceof String ? "tag=" + view.getTag() : view.getClass().getSimpleName(); }

    private static List<TextView> texts(View view) {
        List<TextView> found = new ArrayList<>();
        if (!view.isShown()) return found;
        if (view instanceof TextView) found.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) found.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return found;
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing private preferences");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
    }

    private void select(String page) { main(() -> invoke(activity, "select", new Class<?>[]{String.class}, page)); settle(); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>(false); runOnMainSync(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) return; SystemClock.sleep(40);
        }
        throw new IllegalStateException(message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
}
