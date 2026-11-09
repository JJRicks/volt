package com.jjricks.volt.tests;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Riding mode's tune menu (owner, Nov 9), against the sample scooter on a disposable emulator: the
 * moved settings under the layout editor, the tune button while disconnected and hidden while moving,
 * the menu staying open at walking pace and closing at 4 mph with its dialogs, auto-save with no Done
 * button, and More keeping only the two automatic switches. Refuses a device or an app with data.
 */
public final class RidingConfigMenuValidation extends Instrumentation {
    private MainActivity activity;
    private final Bundle result = new Bundle();
    private final ArrayList<String> notes = new ArrayList<>();
    private File evidence;
    private String label = "normal";
    private String profileId;
    private int assertions;

    @Override public void onCreate(Bundle args) {
        if (args != null && args.getString("label") != null) label = args.getString("label");
        start();
    }

    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            // Android's one-time "viewing full screen" note would take the window focus these checks watch.
            getUiAutomation().executeShellCommand("settings put secure immersive_mode_confirmations confirmed").close();
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-config-menu");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).commit();
                ScooterProfiles.Profile profile = new ScooterProfiles(getTargetContext()).create("Synthetic riding fixture", "000000000000", 0);
                profileId = profile.id;
                OnboardingScreen.markDone(getTargetContext());
            });
            run();
            result.putString("result", "PASS");
        } catch (Throwable t) {
            result.putString("result", "FAIL");
            result.putString("failure", android.util.Log.getStackTraceString(t));
        } finally {
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            try { if (activity != null) main(() -> { activity.service().setSimulation(false); activity.finish(); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
        main(() -> require(activity.service().setSimulation(true), "Simulation on failed"));
        await(() -> activity.service().ble.ready(), "Simulation not ready");
        main(() -> activity.openRiding());
        await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "Riding mode did not open");
        settle();
        speed(0);
        tuneShows("stopped, connected");
        shot(label + "-1-tune-button");
        openMenu();

        // The menu: moved settings below the editor, no Done button.
        View body = root();
        for (String tag : new String[]{"preference-selector-riding_brightness", "preference-selector-riding_rotation",
                "preference-selector-riding_night_vision", "preference_riding_night", "preference_riding_auto_open",
                "preference_riding_auto_record", "preference_riding_hide_status_bar"})
            require(findTag(body, tag) != null, "Menu is missing " + tag);
        require(!hasText(body, "Done"), "The Done button is gone");
        shot(label + "-2-menu-top");
        scrollMenu();
        shot(label + "-3-menu-moved-settings");
        scrollMenuEnd();
        shot(label + "-4-menu-end");
        scrollMenuTop();

        // Auto-save: a layout change persists at once, the menu stays open, Reset saves too.
        click(findTag(root(), "halo"));
        settle();
        require(menuOpen(), "A layout change closes nothing");
        require(storedFrame().contains("\"frame\":\"halo\""), "The frame saved at once: " + storedFrame());
        require(field(screen(), "frameId").equals("focus"), "The layout applies when the menu closes, not mid-edit");
        click(findText(root(), "Reset Halo to default"));
        settle();
        require(menuOpen() && prefs("app_settings").contains("riding_layout_v1_" + profileId), "Reset saves and keeps the menu open");
        click(findTag(root(), "trace"));
        settle();
        require(storedFrame().contains("\"frame\":\"trace\""), "Another change saves at once: " + storedFrame());

        // The two automatic switches work from the menu, on the same preference as More's.
        require(!prefs("app_settings").getBoolean("riding_auto_open", false), "Auto-open off to start");
        Object screenBefore = screen();
        click(findTag(root(), "preference_riding_auto_open"));
        settle();
        require(prefs("app_settings").getBoolean("riding_auto_open", false), "Open automatically is the same preference");
        require(menuOpen() && screen() == screenBefore, "A switch doesn't close the menu or rebuild the screen");
        click(findTag(root(), "preference_riding_auto_open"));
        settle();

        // The speed close rule.
        speed(5);
        settle();
        require(menuOpen(), "Walking pace (5 km/h) keeps the menu open");
        tuneHidden("moving");
        shot(label + "-5-walking-menu-open");
        speed(6.437);
        await(() -> !menuOpen(), "4 mph closes the menu");
        await(() -> ((Boolean) field(screen(), "closed")) == false && !field(screen(), "frameId").equals("focus"), "The closed menu's layout applied");
        require(field(screen(), "frameId").equals("trace"), "Trace applied on close");
        note("menu closed at 6.437 km/h and the saved Trace layout applied");
        speed(30);
        settle();
        tuneHidden("riding at speed");
        speed(0);
        tuneShows("stopped again");

        // A moved setting saves at once, leaves the menu open, and riding mode picks it up when the menu closes.
        openMenu();
        Object beforeFixed = screen();
        click(findText(root(), "Fixed"));
        settle();
        require("fixed".equals(prefs("app_settings").getString("riding_brightness", null)), "Fixed saved at once");
        require(menuOpen() && screen() == beforeFixed, "A moved setting doesn't close the menu or rebuild the screen");
        require(((View) field(field(screen(), "display"), "controls")).getVisibility() == View.GONE, "The slider waits for the menu to close");
        close();
        await(() -> screen() != beforeFixed && ((View) field(field(screen(), "display"), "controls")).getVisibility() == View.VISIBLE, "Fixed brightness applied when the menu closed");
        main(() -> prefs("app_settings").edit().remove("riding_brightness").commit());
        tuneShows("after the brightness change");

        // Dialogs from the menu close with it at 4 mph: the time picker, and the consent sheet.
        schedule("custom");
        openMenu();
        click(findTag(root(), "preference_riding_night_starts"));
        await(() -> !menuFocused(), "The time picker opened over the menu");
        shot(label + "-6-time-picker");
        speed(8);
        await(() -> !menuOpen() && activity.hasWindowFocus(), "The time picker closed with the menu at 4 mph");
        speed(0);
        tuneShows("after the time picker");
        schedule("sun");
        main(() -> prefs("app_settings").edit().remove("riding_night_ride_area").commit());
        openMenu();
        click(findTag(root(), "preference_riding_night_from"));
        await(() -> !menuFocused(), "The consent sheet opened over the menu");
        shot(label + "-7-consent");
        speed(8);
        await(() -> !menuOpen() && activity.hasWindowFocus(), "The consent sheet closed with the menu at 4 mph");
        speed(0);
        tuneShows("after the consent sheet");
        main(() -> prefs("app_settings").edit().remove("riding_night_vision").remove("riding_night_schedule").commit());

        // Disconnected: the tune button shows, Lock and Record don't, and lost readings don't close the menu.
        openMenu();
        main(() -> { activity.service().ble.setSimulationScript("dropout"); activity.redraw(); });
        settle();
        require(menuOpen(), "Losing the connection and readings doesn't close the menu");
        close();
        tuneShows("disconnected");
        require(((View) field(screen(), "stoppedControls")).getVisibility() == View.GONE, "Lock and Record stay stopped-only while disconnected");
        shot(label + "-8-disconnected");
        openMenu();
        shot(label + "-9-menu-disconnected");
        close();
        main(() -> activity.service().ble.setSimulationScript(""));
        speed(0);

        // A layout change persists through leaving and reopening riding mode, with no Done anywhere.
        settle();
        openMenu();
        click(findTag(root(), "halo"));
        settle();
        close();
        await(() -> "halo".equals(field(screen(), "frameId")), "The layout applied when the menu closed");
        main(() -> call(activity, "closeRiding", new Class<?>[0]));
        settle();
        main(() -> activity.openRiding());
        await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "Riding mode reopened");
        settle();
        require("halo".equals(field(screen(), "frameId")), "The saved layout persists across reopening");
        main(() -> call(activity, "closeRiding", new Class<?>[0]));
        settle();

        // More keeps the two automatic switches, and only those.
        main(() -> {
            call(activity, "select", new Class<?>[]{String.class}, "more");
        });
        settle();
        Object more = field(activity, "screen");
        main(() -> call(more, "selectSection", new Class<?>[]{String.class}, "App"));
        settle();
        View moreView = activity.getWindow().getDecorView();
        require(findTag(moreView, "preference_riding_auto_open") != null, "More keeps Open automatically");
        require(hasText(moreView, "Record rides automatically"), "More keeps Record rides automatically");
        for (String tag : new String[]{"preference-selector-riding_brightness", "preference-selector-riding_rotation",
                "preference-selector-riding_night_vision", "preference-selector-riding_night_schedule",
                "preference_riding_night", "preference_riding_hide_status_bar"})
            require(findTag(moreView, tag) == null, "More no longer shows " + tag);
        require(!hasText(moreView, "Screen brightness") && !hasText(moreView, "Night vision"), "More no longer shows moved settings");
        View autoOpen = findTag(moreView, "preference_riding_auto_open");
        main(() -> { ScrollView scroll = scroller(autoOpen); int[] a = new int[2], b = new int[2]; autoOpen.getLocationInWindow(a); scroll.getLocationInWindow(b); scroll.scrollBy(0, a[1] - b[1] - scroll.getHeight() / 2); });
        settle();
        shot(label + "-10-more");
        note("More shows the two automatic switches and no moved setting");
    }

    private void speed(double kmh) {
        main(() -> {
            try {
                Object ble = activity.service().ble;
                Object sim = field(ble, "simulation");
                Method sample = sim.getClass().getDeclaredMethod("sample", String.class, String.class, double.class);
                sample.setAccessible(true);
                sample.invoke(sim, "speed_kmh", kmh + " km/h", kmh);
                activity.service().ble.tickSimulation();
                activity.redraw();
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }

    private Object screen() { return field(activity, "screen"); }
    private Object disc() { return field(screen(), "disc"); }
    private View picker() { return (View) field(screen(), "picker"); }

    private boolean takesTaps() { return (Boolean) call(disc(), "takesTaps", new Class<?>[0]); }
    private void tuneShows(String when) {
        await(() -> takesTaps() && picker().getVisibility() == View.VISIBLE && picker().getScaleX() > .99f, "The tune button should show: " + when);
    }
    private void tuneHidden(String when) {
        await(() -> !takesTaps(), "The tune button should hide: " + when);
    }

    private void openMenu() {
        await(this::takesTaps, "The tune button takes taps");
        main(() -> picker().performClick());
        await(this::menuFocused, "The menu did not open");
        settle();
    }
    private boolean menuOpen() { AlertDialog d = sheet(); return d != null && d.isShowing(); }
    private boolean menuFocused() { AlertDialog d = sheet(); return d != null && d.getWindow().getDecorView().hasWindowFocus(); }
    private AlertDialog sheet() { return (AlertDialog) field(screen(), "sheet"); }
    private View root() { return sheet().getWindow().getDecorView(); }
    /** The sheet's own scroller: AlertDialog's template has an empty one of its own that comes first. */
    private ScrollView menuScroll() { return scroller(findTag(root(), "riding_layout_editor")); }
    private static ScrollView scroller(View v) {
        for (Object p = v.getParent(); p != null; p = ((android.view.ViewParent) p).getParent()) if (p instanceof ScrollView) return (ScrollView) p;
        throw new AssertionError("No scroller");
    }
    private void scrollMenu() { main(() -> { View v = findTag(root(), "preference-selector-riding_brightness"); menuScroll().scrollTo(0, Math.max(0, v.getTop() - 40)); }); settle(); }
    private void scrollMenuEnd() { main(() -> menuScroll().scrollTo(0, 100000)); settle(); }
    private void scrollMenuTop() { main(() -> menuScroll().scrollTo(0, 0)); settle(); }

    private void close() {
        main(() -> {
            View dismiss = findDescription(root(), "Close");
            require(dismiss != null, "The close button is there");
            dismiss.performClick();
        });
        await(() -> !menuOpen(), "The menu closed");
        settle();
    }

    private void click(View view) {
        require(view != null, "Missing view to click");
        main(view::performClick);
    }

    private void schedule(String which) {
        main(() -> prefs("app_settings").edit().putString("riding_night_vision", "scheduled").putString("riding_night_schedule", which).commit());
    }

    private String storedFrame() { String s = prefs("app_settings").getString("riding_layout_v1_" + profileId, ""); return s == null ? "" : s; }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        SystemClock.sleep(350);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot failed");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    private void note(String text) { notes.add(text); }
    private void require(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long until = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < until) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) { assertions++; return; }
            SystemClock.sleep(100);
        }
        throw new AssertionError(message);
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(550); }

    private static View findTag(View v, Object tag) {
        if (tag.equals(v.getTag())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View found = findTag(((ViewGroup) v).getChildAt(i), tag); if (found != null) return found; }
        return null;
    }
    private static View findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View found = findText(((ViewGroup) v).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static boolean hasText(View v, String text) { return findText(v, text) != null; }
    private static View findDescription(View v, String text) {
        if (v.getContentDescription() != null && text.contentEquals(v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View found = findDescription(((ViewGroup) v).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static Object field(Object target, String name) {
        try {
            for (Class<?> t = target.getClass(); t != null; t = t.getSuperclass()) {
                try { Field f = t.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
                catch (NoSuchFieldException next) { }
            }
            throw new NoSuchFieldException(name);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
