package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A recording that ends by itself closes riding mode, on screen and with the activity gone; the rider's own
 * stop doesn't; neither leaves an auto-open hold. Max and Average speed count from opening. Runs against the
 * sample scooter, whose trip max is 35.6 km/h while its live speed never passes 29.8. Disposable emulator only.
 */
public final class RidingAutoCloseValidation extends Instrumentation {
    private static final String NO_MOTION = "auto_no_motion";
    private MainActivity activity;
    private ScooterService service;
    private final Bundle result = new Bundle();
    private final ArrayList<String> notes = new ArrayList<>();
    private File evidence;
    private int assertions;

    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-auto-close");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).commit();
                new ScooterProfiles(getTargetContext()).create("Synthetic riding fixture", "000000000000", 0);
                OnboardingScreen.markDone(getTargetContext());
            });
            run();
            result.putString("result", "PASS");
        } catch (Throwable t) {
            result.putString("result", "FAIL");
            result.putString("failure", String.valueOf(t));
        } finally {
            try { setAlwaysFinish(false); } catch (Throwable ignored) { }
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
        service = activity.service();
        main(() -> require(service.setSimulation(true), "Simulation on failed"));
        main(() -> saveHaloLayout());
        script("stopped");
        await(() -> reading("speed_kmh") != null, "No simulated speed");
        settle();

        speedCountsFromOpen();
        closeRidingMode();
        speedCountsInBackground();
        closeRidingMode();
        manualStopLeavesItOpen();
        autoEndOnScreen();
        autoEndWithActivityGone();
        manualStopWithActivityGone();
        openedAfterAutoEnd();
    }

    /** Parked, then moving, then stopped; the sample scooter's own 35.6 / 21.4 never show. */
    private void speedCountsFromOpen() throws Exception {
        openRiding();
        await(() -> sessionMax() != null, "No session speed after opening");
        settle();
        require(sessionMax() == 0 && sessionAvg() == 0, "Parked at open: " + sessionMax() + ", " + sessionAvg());
        requireStrip("Max", 0, "parked");
        requireStrip("Average", 0, "parked");
        shot("1-parked-at-zero");
        note("parked: max 0, avg 0 beside trip max " + number("maximum_speed_kmh") + ", avg " + number("average_speed_kmh"));

        script("riding");
        SystemClock.sleep(12_000);
        double max = sessionMax(), avg = sessionAvg();
        require(max >= 18 && max < 31, "Max should be this session's top speed, not the trip's 35.6: " + max);
        require(avg >= 18 && avg <= max, "Avg should be the session's moving average, not the trip's 21.4 alone: " + avg);
        requireStrip("Max", max, "moving");
        requireStrip("Average", avg, "moving");
        shot("2-moving");
        note("moving: max " + round(max) + ", avg " + round(avg));

        script("stopped");
        SystemClock.sleep(7_000);
        double heldMax = sessionMax(), heldAvg = sessionAvg();
        require(heldMax >= max && heldMax < 31, "Max should hold through the stop: " + max + " -> " + heldMax);
        require(Math.abs(heldAvg - avg) < 3, "Avg should hold through the stop: " + avg + " -> " + heldAvg);
        requireStrip("Max", heldMax, "stopped");
        requireStrip("Average", heldAvg, "stopped");
        shot("3-held-after-stop");
        note("stopped: max " + round(heldMax) + ", avg " + round(heldAvg));

        Object session = session();
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        require(session() == session, "Rotation should keep the session");
        require(sessionMax() == heldMax && Math.abs(sessionAvg() - heldAvg) < 0.5, "Rotation must keep them: " + sessionMax() + ", " + sessionAvg());
        requireStrip("Max", heldMax, "rotated");
        shot("4-rotated");
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        Object before = session();
        recreate();
        await(() -> field(activity, "riding") != null, "Riding mode did not come back");
        require(session() != before, "A recreation builds a new session");
        settle();
        require(sessionMax() == heldMax && Math.abs(sessionAvg() - heldAvg) < 0.5, "Recreation must keep them: " + sessionMax() + ", " + sessionAvg());
        requireStrip("Max", heldMax, "recreated");
        requireStrip("Average", heldAvg, "recreated");
        shot("5-recreated");
        note("recreated: max " + round(sessionMax()) + ", avg " + round(sessionAvg()));
    }

    /** With the activity gone the service keeps counting: a peak seen only while Volt is off screen shows on return. */
    private void speedCountsInBackground() throws Exception {
        openRiding();
        await(() -> sessionMax() != null, "No session speed after opening");
        settle();
        goneToBackground();
        // Past what the sample scooter ever reports, so only the telemetry path can have seen it.
        for (double kmh : new double[]{33, 34, 33}) {
            final double reading = kmh;
            main(() -> call(field(service.ble, "telemetry"), "putSimulationSample", new Class<?>[]{String.class, String.class, Double.class, long.class}, "speed_kmh", reading + " km/h", reading, SystemClock.elapsedRealtime()));
            SystemClock.sleep(400);
        }
        foreground();
        await(() -> field(activity, "riding") != null, "Riding mode did not come back");
        settle();
        require(sessionMax() >= 34, "A background peak should count: " + sessionMax());
        requireStrip("Max", sessionMax(), "after background");
        shot("5b-background-peak");
        note("background peak: max " + round(sessionMax()) + ", avg " + round(sessionAvg()));
    }

    /** Back closes for the next steps and sets the hold; a later step needs that hold clear again. */
    private void closeRidingMode() {
        main(() -> activity.onBackPressed());
        await(() -> field(activity, "riding") == null, "Back did not close riding mode");
        settle();
    }

    /** The rider's own stop leaves riding mode open. */
    private void manualStopLeavesItOpen() throws Exception {
        clearHold();
        openRiding();
        startRecording();
        main(() -> service.stopRide());
        require(!recording(), "The rider's stop stopped");
        settle();
        require(field(activity, "riding") != null, "The rider's own stop must leave riding mode open");
        require(!held(), "The rider's stop in riding mode sets no auto-open hold");
        shot("6-manual-stop-still-open");
        note("manual stop on screen: riding mode still open");
        closeRidingMode();
    }

    /** On screen: closes at once, keeps the notice path, and sets no hold. */
    private void autoEndOnScreen() throws Exception {
        clearHold();
        openRiding();
        startRecording();
        main(() -> call(service, "stopRide", new Class<?>[]{String.class}, NO_MOTION));
        require(!recording(), "The automatic end stopped");
        await(() -> field(activity, "riding") == null, "An automatic end should close riding mode at once");
        settle();
        require("home".equals(field(activity, "selectedPage")), "Closed to Home");
        require(!screenName().contains("RidingScreen"), "Home is on screen, not riding mode: " + screenName());
        require(!held(), "An automatic end must set no auto-open hold");
        shot("7-auto-end-on-screen-home");
        note("auto-end on screen: riding mode closed at once, hold " + held());
    }

    /** The activity is gone when the recording ends: the saved state must not bring riding mode back. */
    private void autoEndWithActivityGone() throws Exception {
        clearHold();
        openRiding();
        startRecording();
        goneToBackground();
        require(recording(), "Recording should outlive the activity");
        main(() -> call(service, "stopRide", new Class<?>[]{String.class}, NO_MOTION));
        require(!recording(), "The automatic end stopped");
        SystemClock.sleep(1500);
        foreground();
        settle();
        require(field(activity, "riding") == null && !(Boolean) field(activity, "ridingRestore"), "A saved riding mode must not come back after its recording auto-ended");
        require("home".equals(field(activity, "selectedPage")), "Reopens to Home");
        require(!screenName().contains("RidingScreen"), "Home is on screen: " + screenName());
        require(!held(), "An automatic end in the background sets no hold");
        shot("8-auto-end-activity-gone-home");
        note("auto-end with activity gone: reopened to Home, hold " + held());
    }

    /** Control for the step above: nothing auto-ended, so the same flow must bring riding mode back. */
    private void manualStopWithActivityGone() throws Exception {
        openRiding();
        startRecording();
        goneToBackground();
        main(() -> service.stopRide());
        require(!recording(), "Stopped in the background");
        SystemClock.sleep(1000);
        foreground();
        await(() -> field(activity, "riding") != null, "A manual stop with the activity gone must restore riding mode");
        settle();
        shot("9-manual-stop-activity-gone-restored");
        note("manual stop with activity gone: riding mode restored, ride time " + rideMs() + " ms");
        closeRidingMode();
    }

    /** A riding mode opened after an auto-end is unaffected, even across a recreation. */
    private void openedAfterAutoEnd() throws Exception {
        openRiding();
        settle();
        require(field(activity, "riding") != null, "Opens fresh after an auto-end");
        SystemClock.sleep(1500);
        recreate();
        await(() -> field(activity, "riding") != null, "Opened after the auto-end: recreation brings it back");
        settle();
        SystemClock.sleep(1500);
        require(field(activity, "riding") != null, "and it stays open");
        note("opened after an auto-end: recreation kept it open");
    }

    private void saveHaloLayout() {
        try {
            Class<?> type = Class.forName("com.jjricks.volt.RidingLayout");
            Constructor<?> c = type.getDeclaredConstructor(); c.setAccessible(true);
            Object layout = c.newInstance();
            String halo = (String) staticField(type, "HALO");
            require((Boolean) call(layout, "setFrame", new Class<?>[]{String.class}, halo), "Halo frame");
            require((Boolean) call(layout, "setContent", new Class<?>[]{String.class, String.class, String.class}, halo, "strip_2", staticField(type, "MAX_SPEED")), "Max in strip");
            String json = (String) call(layout, "toJson", new Class<?>[]{});
            String profile = ((ScooterProfiles.Profile) service.profiles.active()).id;
            prefs("app_settings").edit().putString("riding_layout_v1_" + profile, json).commit();
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private void script(String id) { main(() -> service.ble.setSimulationScript(id)); }
    private void openRiding() {
        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null, "Riding mode did not open");
        settle();
    }
    private void startRecording() {
        main(() -> call(service, "beginRide", new Class<?>[]{String.class}, "Synthetic"));
        await(this::recording, "Recording did not start");
        settle();
    }
    private boolean recording() { AtomicReference<Boolean> r = new AtomicReference<>(); main(() -> r.set(service.isRecording())); return r.get(); }
    private boolean held() {
        AtomicReference<Boolean> h = new AtomicReference<>();
        main(() -> h.set((Boolean) field(field(service, "autoStart"), "rideModeHeld")));
        return h.get();
    }
    /** Earlier steps' Back presses left the hold set; the assertions below are about what each step adds. */
    private void clearHold() {
        main(() -> {
            try { Field f = field(service, "autoStart").getClass().getDeclaredField("rideModeHeld"); f.setAccessible(true); f.setBoolean(field(service, "autoStart"), false); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    /** Destroys the activity the way Android does under memory pressure ("Don't keep activities"): its state is saved. */
    private void goneToBackground() throws Exception {
        MainActivity old = activity;
        setAlwaysFinish(true);
        shell("input keyevent KEYCODE_HOME");
        await(old::isDestroyed, "Activity was not destroyed");
        setAlwaysFinish(false);
    }
    /** The Developer option's own call: the setting alone only takes effect at boot. Needs the sweep's hidden_api step. */
    private void setAlwaysFinish(boolean on) throws Exception {
        android.app.UiAutomation automation = getUiAutomation();
        automation.adoptShellPermissionIdentity("android.permission.SET_ALWAYS_FINISH");
        try {
            Object am = android.app.ActivityManager.class.getMethod("getService").invoke(null);
            am.getClass().getMethod("setAlwaysFinish", boolean.class).invoke(am, on);
        } finally { automation.dropShellPermissionIdentity(); }
    }
    private void foreground() throws Exception {
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        // The intent the task started with brings it forward, so the saved state is what comes back.
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity next = waitForMonitorWithTimeout(monitor, 8000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "Activity did not come back");
        activity = (MainActivity) next;
        await(() -> activity.service() != null, "Service did not rebind");
        settle();
    }
    private void recreate() {
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        main(() -> activity.recreate());
        Activity next = waitForMonitorWithTimeout(monitor, 8000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "Activity did not recreate");
        activity = (MainActivity) next;
    }
    private void rotate(int orientation) throws Exception {
        main(() -> activity.setRequestedOrientation(orientation));
        SystemClock.sleep(2500);
        settle();
    }

    private Object session() { AtomicReference<Object> s = new AtomicReference<>(); main(() -> s.set(field(activity, "riding"))); return s.get(); }
    private Double sessionMax() { return (Double) sessionCall("maxKmh"); }
    private Double sessionAvg() { return (Double) sessionCall("averageKmh"); }
    private Object sessionCall(String name) { AtomicReference<Object> v = new AtomicReference<>(); main(() -> v.set(call(field(activity, "riding"), name, new Class<?>[]{}))); return v.get(); }
    private long rideMs() { AtomicReference<Object> ms = new AtomicReference<>(); main(() -> ms.set(call(field(activity, "riding"), "rideTimeMs", new Class<?>[]{long.class}, SystemClock.elapsedRealtime()))); return (Long) ms.get(); }
    private Object screen() { AtomicReference<Object> s = new AtomicReference<>(); main(() -> s.set(field(activity, "screen"))); return s.get(); }
    private String screenName() { Object s = screen(); return s == null ? "null" : s.getClass().getSimpleName(); }

    /** The strip's spoken text carries the number; it may be in mph, so compare against both units. */
    private void requireStrip(String label, double kmh, String step) {
        SystemClock.sleep(1200);
        List<String> spoken = new ArrayList<>();
        main(() -> collect(activity.getWindow().getDecorView(), spoken));
        boolean found = false;
        for (String text : spoken) if (text.startsWith(label)) {
            Matcher m = Pattern.compile("([0-9]+[.][0-9])").matcher(text);
            if (!m.find()) continue;
            double shown = Double.parseDouble(m.group(1));
            found |= Math.abs(shown - kmh) < 1.5 || Math.abs(shown - kmh * 0.621371) < 1;
        }
        require(found, step + ": no " + label + " near " + kmh + " km/h in " + spoken);
        note(step + " spoken " + label + ": ok");
    }
    private void collect(View view, List<String> out) {
        CharSequence said = view.isShown() ? view.getContentDescription() : null;
        if (said != null && said.length() > 0) out.add(said.toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }

    private Double number(String key) { AtomicReference<Double> v = new AtomicReference<>(); main(() -> v.set(reading(key))); return v.get(); }
    private Double reading(String key) {
        Object telemetry = field(service.ble, "telemetry");
        return (Double) call(telemetry, "number", new Class<?>[]{String.class}, key);
    }
    private void shell(String command) throws Exception {
        ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { byte[] buffer = new byte[1024]; while (in.read(buffer) >= 0) { } }
    }
    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot failed");
        File image = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        TextCensus.write(this, image);
    }
    private static String round(double v) { return String.format(java.util.Locale.US, "%.1f", v); }
    private void note(String text) { notes.add(text); }
    private void require(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { action.run(); return; }
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long until = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < until) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(message);
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(550); }
    private static Object staticField(Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
