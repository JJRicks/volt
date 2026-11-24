package com.jjricks.volt.tests;

import android.Manifest;
import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;
import com.jjricks.volt.TelemetryHistory;

/**
 * Disposable emulator only; no scooter, no radio, nothing sent. Two of 2.0.5's charging fixes:
 * <ul>
 *   <li>-e mode range (default): Battery &amp; charging's summary card on the simulator's UT5 MAX, in km. Its range line
 *   ("about 38 km left") keeps the number and its unit together with a no-break space, and wherever the line wraps no
 *   line starts with the unit (#452: "about 38 / km left" at 344 dp, 1.4x). For each language of -e locales ("en,de")
 *   in both themes at -e text_scale, with a screenshot of the card.</li>
 *   <li>-e mode invite: Email to Volt's hand-over (#537 review, F03). With this package's stand-in email app (MailStub,
 *   enabled with pm for this step only) beside the emulator's own, Android asks which app takes the recordings' email.
 *   Backing out of that chooser leaves the invite unanswered (before: "sent", and the Battery page's invite gone for
 *   good); picking the stand-in, Volt stops behind it and the answer is "sent".</li>
 * </ul>
 */
public final class ChargingWordingValidation extends ValidationInstrumentation {
    private static final String KEY = "charge_invite", STUB = "com.jjricks.volt.tests/com.jjricks.volt.tests.MailStub";
    private MainActivity activity;
    private Bundle args;
    private int assertions;
    private String label = "";
    private LocaleList foundLocales;
    private boolean stubEnabled;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "range");
        try {
            admit();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000000", 0);
                p.serial = "X2538-SYNTHETIC"; profiles.update(p); profiles.select(p.id);
                OnboardingScreen.markDone(getTargetContext());
            });
            if (mode.equals("invite")) invite();
            else range();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            failure.printStackTrace();
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            if (stubEnabled) try { shell("pm disable " + STUB); } catch (Throwable ignored) {}
            try { if (foundLocales != null) setLocale(null); } catch (Throwable ignored) {}
            if (activity != null) try { main(() -> { if (activity.service() != null) activity.service().setSimulation(false); }); main(() -> activity.finish()); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== Range line =====

    private void range() throws Exception {
        String size = args.getString("size_label", getTargetContext().getResources().getConfiguration().screenWidthDp + ""), text = args.getString("text_scale", "1.3");
        for (String locale : args.getString("locales", "en,de").split(",")) for (String theme : args.getString("themes", "dark,light").split(",")) {
            label = size + "-" + locale + "-" + theme + "-" + text;
            if (activity != null) { MainActivity old = activity; main(old::finish); await(old::isDestroyed, "The activity did not finish"); }
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putString("units", "km")
                    .putString("theme", theme).putFloat("text_scale", Float.parseFloat(text)).commit(), "Could not seed preferences");
            setLocale("en".equals(locale) ? "" : locale);
            launch();
            main(() -> { ScooterService s = activity.service(); require(s.simulating() || s.setSimulation(true), "Simulation did not start"); s.ble.setSimulationModel(""); s.ble.tickSimulation(); });
            await(() -> { TelemetryHistory.Reading r = activity.service().displayReadings().get("remaining_range_km"); return r != null && r.live && r.number != null; },
                    "The simulator reported no range");
            main(() -> HubTestPages.open(activity, HubPages.HOME)); settle();
            HubTestPages.reach(this, activity, HubPages.BATTERY); settle();
            TextView[] line = {null};
            await(() -> (line[0] = rangeLine()) != null && line[0].isShown() && line[0].getLayout() != null, "Battery & charging shows no range line");
            SystemClock.sleep(700); settle();
            main(() -> {
                TextView view = rangeLine(); CharSequence value = view.getText(); Layout layout = view.getLayout();
                String unit = unitIn(value.toString());
                int at = value.toString().indexOf(unit);
                require(at > 1 && Character.isDigit(value.charAt(at - 2)) && value.charAt(at - 1) == ' ',
                        label + ": the range's number and unit are not joined by a no-break space: \"" + visible(value) + "\"");
                for (int i = 1; i < layout.getLineCount(); i++) {
                    String next = value.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString();
                    require(!next.startsWith(unit), label + ": a line of the range starts with its unit: \"" + visible(value) + "\"");
                }
                require(layout.getEllipsisCount(layout.getLineCount() - 1) == 0, label + ": the range line is cut: " + value);
                checks.add(label + ": \"" + visible(value) + "\" on " + layout.getLineCount() + " line(s), number and unit together");
                reveal(view);
            });
            shot(label + "-battery-range");
        }
    }

    /** The summary card's range line: its only text with a distance unit. Main thread. */
    private TextView rangeLine() {
        View card = HubTestPages.shownTag(activity, "hub-battery-summary");
        if (card == null) return null;
        ArrayDeque<View> stack = new ArrayDeque<>(); stack.push(card);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v instanceof TextView && v.getVisibility() == View.VISIBLE && unitIn(((TextView) v).getText().toString()) != null) return (TextView) v;
            if (v instanceof ViewGroup) for (int i = ((ViewGroup) v).getChildCount() - 1; i >= 0; i--) stack.push(((ViewGroup) v).getChildAt(i));
        }
        return null;
    }
    private static String unitIn(String text) { return text.matches("(?s).*\\d[\\s\\u00a0\\u202f]km\\b.*") ? "km" : null; }
    private static String visible(CharSequence text) { return text.toString().replace(' ', '·'); }

    // ===== Invite hand-over =====

    private void invite() throws Exception {
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putString("theme", args.getString("theme", "light")).commit(),
                "Could not seed preferences");
        launch();
        require(!settings().contains(KEY), "The invite has an answer before anyone gave one");
        boolean[] stop = {false};
        android.app.Application.ActivityLifecycleCallbacks watch = new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStopped(android.app.Activity a) { if (a == activity) stop[0] = true; }
            @Override public void onActivityCreated(android.app.Activity a, Bundle b) { }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityResumed(android.app.Activity a) { }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        };
        main(() -> activity.getApplication().registerActivityLifecycleCallbacks(watch));
        // This package's stand-in email app joins the emulator's own (often Gmail), so more than one app can take the
        // email and Android always asks: its "Open with" list, or (where the email-apps-only hand-over finds no match, as on
        // Android 15 and later with Gmail) Volt's fallback share sheet.
        shell("pm enable " + STUB); stubEnabled = true; SystemClock.sleep(800);
        // 1. The chooser closed with Back.
        send();
        await(() -> !activity.resumed(), "Nothing opened over Volt for the email");
        SystemClock.sleep(1500);
        shot("invite-chooser");
        require(!activity.isFinishing() && !stop[0], "The chooser stopped Volt: it isn't a see-through window here");
        require(getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "Back was not sent");
        await(() -> activity.resumed(), "Volt did not come back after the chooser closed");
        SystemClock.sleep(600); settle();
        require(!stop[0], "Volt stopped while the chooser was open");
        require(!settings().contains(KEY), "Backing out of the chooser answered the invite: " + settings().getString(KEY, ""));
        battery();
        main(() -> require(HubTestPages.shownTag(activity, "battery-charge-invite") != null, "The invite banner is gone after a cancelled email"));
        shot("invite-after-cancel");
        checks.add("Email to Volt, the chooser closed with Back: no answer stored, the Battery page's invite still shows");
        // 2. The stand-in picked in the chooser takes the email: Volt stops behind it, and the answer is "sent".
        send();
        await(() -> !activity.resumed(), "Nothing opened over Volt for the email");
        SystemClock.sleep(1200);
        pick("A stand-in email app");
        await(() -> "sent".equals(settings().getString(KEY, "")), "The stand-in email app took the email, but the invite has no answer");
        require(stop[0], "Volt never stopped behind the stand-in email app");
        main(() -> activity.getApplication().unregisterActivityLifecycleCallbacks(watch));
        await(() -> activity.resumed(), "Volt did not come back after the stand-in email app closed itself");
        settle();
        shell("pm disable " + STUB); stubEnabled = false;
        battery();
        main(() -> require(HubTestPages.shownTag(activity, "battery-charge-invite") == null, "The invite banner still shows after the email was taken"));
        shot("invite-after-send");
        checks.add("Email to Volt, the stand-in picked in the chooser took it (Volt stopped behind it): \"sent\" stored, the invite gone");
    }

    /** Taps the app named name in whatever chooser shows (scrolling it if needed), then "Just once" if Android asks. */
    private void pick(String name) throws Exception {
        long end = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < end) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(name)) {
                    android.view.accessibility.AccessibilityNodeInfo target = node;
                    while (target != null && !target.isClickable()) target = target.getParent();
                    if (target != null && target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        SystemClock.sleep(1200);
                        android.view.accessibility.AccessibilityNodeInfo next = getUiAutomation().getRootInActiveWindow();
                        if (next != null) for (String once : new String[] {"Just once", "JUST ONCE"})
                            for (android.view.accessibility.AccessibilityNodeInfo b : next.findAccessibilityNodeInfosByText(once)) b.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                        return;
                    }
                }
                ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new ArrayDeque<>(); nodes.push(root);
                while (!nodes.isEmpty()) {
                    android.view.accessibility.AccessibilityNodeInfo n = nodes.pop();
                    if (n.isScrollable()) { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
                    for (int i = 0; i < n.getChildCount(); i++) if (n.getChild(i) != null) nodes.push(n.getChild(i));
                }
            }
            SystemClock.sleep(500);
        }
        shot("invite-pick-missing");
        throw new IllegalStateException("The chooser never showed \"" + name + "\"");
    }

    /** The recordings' email, as Charge history & recordings' Email to Volt hands it over once the export is ready. */
    private void send() throws Exception {
        File file = new File(getTargetContext().getCacheDir(), "volt-charge-recordings.json");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write("{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8)); }
        main(() -> activity.emailChargeRecordings(file, "UT5 MAX"));
    }
    private void battery() {
        main(() -> HubTestPages.open(activity, HubPages.HOME)); settle();
        HubTestPages.reach(this, activity, HubPages.BATTERY); settle();
    }

    // ===== Helpers =====

    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root() != null && root().getWidth() > 0, "App did not launch");
        SystemClock.sleep(1500); settle();
    }
    private void setLocale(String tag) {
        if (Build.VERSION.SDK_INT < 33) { require(tag == null || tag.isEmpty(), "Languages need API 33"); return; }
        LocaleManager manager = getTargetContext().getSystemService(LocaleManager.class);
        if (foundLocales == null) foundLocales = manager.getApplicationLocales();
        LocaleList next = tag == null ? foundLocales : tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag);
        main(() -> manager.setApplicationLocales(next));
        SystemClock.sleep(1200); settle();
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(fd.getFileDescriptor())) { byte[] b = new byte[1024]; for (int n; (n = in.read(b)) >= 0; ) out.write(b, 0, n); }
        finally { fd.close(); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private void reveal(View view) {
        View parent = view; while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        if (parent == null) return;
        int[] at = new int[2], top = new int[2]; view.getLocationOnScreen(at); parent.getLocationOnScreen(top);
        ((ScrollView) parent).scrollBy(0, Math.max(0, at[1] - top[1] - parent.getHeight() / 4));
    }
    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator permissions first: " + permission);
    }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(900); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charging-wording-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(60); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
}
