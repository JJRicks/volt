package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.MoreScreen;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only; never reaches the network. More → About & permissions →
 * Check for updates with GitHub's reply replaced in-process: the first tap asks before any
 * request (Cancel sends nothing), then newer, current, unreachable and unsafe replies show the
 * right result. Open release page hands the browser only Volt's release page (intercepted); when the reply lists the
 * release's own volt-<version>.apk, Download hands the browser exactly that file, and nothing else.
 */
public final class UpdateCheckValidation extends Instrumentation {
    private static final String PAGE = "https://github.com/JJRicks/volt/releases/tag/v9.9.9";
    private static final String APK = "https://github.com/JJRicks/volt/releases/download/v9.9.9/volt-9.9.9.apk";
    private Bundle arguments;
    private MainActivity activity;
    /** Settings' Updates sheet (part 11), open under every dialog Check for updates shows. */
    private AlertDialog sheet;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>(), agents = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> reply = new AtomicReference<>(""), opened = new AtomicReference<>();
    private Field fetcher; private Object original;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putString("theme", arguments.getString("theme", "dark")).putString("last_page", "more").putString("more_section", "About").commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic update fixture", "000000000000", 0);
                p.mac = "C0:FF:EE:00:00:01"; p.serial = "Q2538ZZ9US7777777"; profiles.update(p); profiles.select(p.id);
                OnboardingScreen.markDone(getTargetContext());
            });
            stubGitHub();
            activity = (MainActivity) startActivitySync(new Intent(Intent.ACTION_MAIN).setClass(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
            addMonitor(new ActivityMonitor() {
                @Override public ActivityResult onStartActivity(Intent intent) {
                    if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
                    opened.set(String.valueOf(intent.getData())); return new ActivityResult(0, null);
                }
            });
            // Check for updates sat in More's own Updates section, in the menu. Since part 11 (#392) More is Settings, reached
            // from Home by the rider's taps, and Updates is its own row there (settings-updates, with the automatic check's
            // state): the row opens the Updates sheet that holds the automatic check's switch and Check for updates. The
            // saved More section (About) must not stand in the way.
            HubTestPages.reach(this, activity, HubPages.SETTINGS);
            main(() -> require(HubPages.SETTINGS.equals(HubTestPages.current(activity)) && field(activity, "screen") instanceof MoreScreen, "Settings did not open from Home"));
            View[] updatesRow = {null};
            main(() -> updatesRow[0] = HubTestPages.shownTag(activity, "settings-updates"));
            require(updatesRow[0] != null, "Settings has no Updates row");
            HubTestPages.tapView(this, activity, updatesRow[0], "Settings' Updates row");
            // Read on the main thread (tag() walks MoreScreen's dialog list and each dialog's views); updatesSheet() posts there itself.
            await(() -> {
                AtomicReference<Boolean> ok = new AtomicReference<>(false);
                main(() -> { View check = tag("check-updates"), auto = tag("update_auto"); ok.set(check != null && check.isShown() && auto != null && auto.isShown()); });
                return ok.get() && updatesSheet() != null;
            }, "Check for updates is not in Settings' Updates sheet");
            sheet = updatesSheet();

            reply.set(release("v9.9.9", PAGE));
            tap(); require(showing("Check GitHub for updates?") && requests.isEmpty(), "No consent before the first request");
            require(text(dialogView(), "Volt asks GitHub for its latest release. GitHub sees your IP address and your Volt version; nothing else is sent. Volt never downloads or installs anything itself.") != null, "Consent wording");
            screenshot("consent");
            press("Cancel"); settle();
            require(showingCount() == 0 && requests.isEmpty() && !prefs("app_settings").getBoolean("update_check_consented", false), "Cancel sent a request or saved consent");

            tap(); press("Check");
            await(() -> showing("Volt 9.9.9 is available"), "A newer release was not offered");
            require(requests.size() == 1 && requests.get(0).equals("https://api.github.com/repos/JJRicks/volt/releases/latest")
                    && agents.get(0).equals("Volt/" + installed() + " (Android; https://github.com/JJRicks/volt)"), "One request, to GitHub, with Volt's User-Agent");
            require(prefs("app_settings").getBoolean("update_check_consented", false) && text(dialogView(), "Shorter range estimate, longer rides.") != null, "Consent saved and notes shown");
            settle(); SystemClock.sleep(600); screenshot("available");
            press("Open release page"); settle();
            require(PAGE.equals(opened.get()) && showingCount() == 0, "Open release page did not open the release page");

            reply.set(release("v" + installed().replaceAll("-.*", ""), PAGE)); opened.set(null);
            tap(); await(() -> showing("You're up to date"), "The current release was not recognised");
            require(requests.size() == 2 && text(dialogView(), "Close") == null, "Consent is asked only once; one OK closes it"); settle(); SystemClock.sleep(600); screenshot("current"); press("OK"); settle();

            reply.set(null);
            tap(); await(() -> showing("Couldn't check for updates"), "An unreachable GitHub was not explained");
            require(text(dialogView(), "GitHub didn't answer. Check your connection and try again.") != null && text(dialogView(), "Close") == null, "Unreachable wording with a single OK"); press("OK"); settle();

            reply.set(release("v9.9.9", "https://example.com/volt.apk"));
            tap(); await(() -> showing("Couldn't check for updates"), "A reply pointing elsewhere was offered");
            press("OK"); settle();
            require(opened.get() == null, "A page outside Volt's releases was opened");

            // The reply lists the release's APK beside SHA256SUMS and the source zip: Download opens exactly the APK.
            reply.set(releaseWithApk("v9.9.9", APK)); opened.set(null);
            tap(); await(() -> showing("Volt 9.9.9 is available"), "A newer release with its APK was not offered");
            require(text(dialogView(), "Download") != null && text(dialogView(), "Open release page") == null, "Download replaces Open release page");
            require(text(dialogView(), "You have " + installed() + ". Download the new version and install it over this one; your data stays.") != null, "Download wording");
            settle(); SystemClock.sleep(600); screenshot("available-download");
            press("Download"); settle();
            require(APK.equals(opened.get()) && showingCount() == 0, "Download did not open the release's APK");

            // An APK that is not exactly Volt's (here on another host) is never offered: the release page as before.
            reply.set(releaseWithApk("v9.9.9", "https://example.com/JJRicks/volt/releases/download/v9.9.9/volt-9.9.9.apk")); opened.set(null);
            tap(); await(() -> showing("Volt 9.9.9 is available"), "A newer release was not offered");
            require(text(dialogView(), "Download") == null, "A foreign APK was offered");
            press("Open release page"); settle();
            require(PAGE.equals(opened.get()) && showingCount() == 0, "A foreign APK did not fall back to the release page");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            try { if (fetcher != null) fetcher.set(null, original); } catch (Exception ignored) { }
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private String installed() {
        try { return getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException missing) { throw new IllegalStateException(missing); }
    }
    private static String release(String tag, String url) {
        return "{\"html_url\":\"" + url + "\",\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"volt.apk\"}],\"body\":\"Shorter range estimate, longer rides.\"}";
    }
    private static String releaseWithApk(String tag, String apk) {
        String base = "https://github.com/JJRicks/volt/releases/download/" + tag + "/";
        return "{\"html_url\":\"" + PAGE + "\",\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,\"assets\":["
                + "{\"name\":\"SHA256SUMS\",\"state\":\"uploaded\",\"browser_download_url\":\"" + base + "SHA256SUMS\"},"
                + "{\"name\":\"volt-9.9.9-source.zip\",\"state\":\"uploaded\",\"browser_download_url\":\"" + base + "volt-9.9.9-source.zip\"},"
                + "{\"name\":\"volt-9.9.9.apk\",\"state\":\"uploaded\",\"browser_download_url\":\"" + apk + "\"}],"
                + "\"body\":\"Shorter range estimate, longer rides.\"}";
    }
    /** Replaces GitHub in-process: the reply is the fixture, or an IOException when it is null. */
    private void stubGitHub() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> check = Class.forName("com.jjricks.volt.UpdateCheck", true, loader), type = Class.forName("com.jjricks.volt.UpdateCheck$Fetcher", true, loader);
        fetcher = check.getDeclaredField("fetcher"); fetcher.setAccessible(true); original = fetcher.get(null);
        fetcher.set(null, Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (!method.getName().equals("get")) return method.getName().equals("hashCode") ? 1 : method.getName().equals("equals") ? proxy == args[0] : "stub";
            requests.add((String) args[0]); agents.add((String) args[1]);
            String body = reply.get(); if (body == null) throw new java.io.IOException("offline");
            return body;
        }));
    }

    private void tap() { main(() -> tag("check-updates").performClick()); settle(); }
    private void press(String label) {
        main(() -> { TextView button = text(dialogView(), label); require(button != null, "No " + label + " button"); button.performClick(); });
    }
    private boolean showing(String title) { AtomicReference<Boolean> found = new AtomicReference<>(false); main(() -> { View v = dialogView(); found.set(v != null && text(v, title) != null); }); return found.get(); }
    private View dialogView() {
        List<?> list = (List<?>) field(more(), "dialogs");
        for (int i = list.size() - 1; i >= 0; i--) { AlertDialog d = (AlertDialog) list.get(i); if (d.isShowing()) return d.getWindow().getDecorView(); }
        return null;
    }
    /**
     * Dialogs Check for updates opened, showing now. The Updates sheet that holds the row (part 11) is not one of them: it
     * must still be showing under them, so a check that closed it also fails here.
     */
    private int showingCount() {
        AtomicReference<Integer> n = new AtomicReference<>(0);
        main(() -> {
            require(sheet == null || sheet.isShowing(), "The Updates sheet closed under Check for updates");
            int c = 0; for (Object d : (List<?>) field(more(), "dialogs")) if (d != sheet && ((AlertDialog) d).isShowing()) c++; n.set(c);
        });
        return n.get();
    }
    /** The Updates sheet over Settings (MoreScreen.updatesSheet): the showing dialog that holds Check for updates. Main thread. */
    private AlertDialog updatesSheet() {
        AtomicReference<AlertDialog> found = new AtomicReference<>();
        main(() -> { for (Object d : (List<?>) field(more(), "dialogs")) {
            AlertDialog dialog = (AlertDialog) d;
            if (dialog.isShowing() && dialog.getWindow().getDecorView().findViewWithTag("check-updates") != null) found.set(dialog);
        } });
        return found.get();
    }
    private MoreScreen more() { return (MoreScreen) field(activity, "screen"); }
    /** A tagged view in the activity's window or, since Check for updates sits in the Updates sheet (part 11), in a showing dialog. Main thread. */
    private View tag(String tag) {
        View found = activity.getWindow().getDecorView().findViewWithTag(tag);
        if (found != null || !(field(activity, "screen") instanceof MoreScreen)) return found;
        for (Object d : (List<?>) field(more(), "dialogs")) {
            AlertDialog dialog = (AlertDialog) d;
            if (dialog.isShowing() && (found = dialog.getWindow().getDecorView().findViewWithTag(tag)) != null) return found;
        }
        return null;
    }
    private TextView text(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(text)) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { TextView t = text(((ViewGroup) root).getChildAt(i), text); if (t != null) return t; }
        return null;
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter"}) require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "update-check-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException next) { type = type.getSuperclass(); } }
            throw new NoSuchMethodException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
