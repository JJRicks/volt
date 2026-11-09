package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Riding mode shows Home at the next open after the connection was lost with no recording running, and at no
 * other time. Drives the simulated scooter's connection. Process death can't be driven from inside the process
 * that would die, so that one is in RidingValuesTest's saved-state round trip. Disposable emulator only.
 */
public final class RidingLostConnectionValidation extends Instrumentation {
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
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-lost-connection");
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
        await(() -> activity.service() != null, "Activity not ready");
        service = activity.service();
        main(() -> require(service.setSimulation(true), "Simulation on failed"));
        main(() -> service.ble.setSimulationScript("stopped"));
        await(this::connected, "Simulated scooter not connected");
        settle();

        manualStopThenDisconnect();
        manualStopStillConnected();
        dropOnScreen();
        dropAndReconnectOffScreen();
        recordingHolds();
        openedWithNoScooter();
        pauseIsNotALeave();
        recreatedOnScreenWhileDown();
        activityGoneControl();
        activityGoneAfterDrop();
    }

    /** The owner's case: stop the recording in riding mode, the scooter disconnects, next open is Home. */
    private void manualStopThenDisconnect() throws Exception {
        clearHold();
        openRiding();
        startRecording();
        main(() -> service.stopRide());
        require(!recording(), "Stopped");
        settle();
        require(open(), "The rider's own stop leaves riding mode open");
        drop();
        SystemClock.sleep(1500);
        require(open(), "A drop on screen doesn't close it");
        shot("1-manual-stop-dropped-still-open");
        leave();
        comeBack();
        requireHome("manual stop, disconnect, leave, return");
        shot("2-next-open-home");
        connect();
    }

    /** A manual stop with the scooter still connected keeps today's behaviour, across a leave too. */
    private void manualStopStillConnected() throws Exception {
        clearHold();
        openRiding();
        startRecording();
        main(() -> service.stopRide());
        settle();
        require(open(), "Still open after the rider's stop");
        leave();
        comeBack();
        requireOpen("manual stop, still connected, leave, return");
        closeRidingMode();
    }

    private void dropOnScreen() throws Exception {
        clearHold();
        openRiding();
        drop();
        SystemClock.sleep(2500);
        requireOpen("drop on screen");
        shot("3-drop-on-screen-open");
        connect();
        SystemClock.sleep(1000);
        requireOpen("drop and reconnect on screen");
        leave();
        comeBack();
        requireOpen("a drop and reconnect on screen counts for nothing at the next return");
        closeRidingMode();
    }

    private void dropAndReconnectOffScreen() throws Exception {
        clearHold();
        openRiding();
        leave();
        drop();
        connect();
        require(connected(), "Reconnected before the return");
        comeBack();
        requireHome("dropped off screen, reconnected before the return");
        shot("4-dropped-off-screen-reconnected-home");
    }

    private void recordingHolds() throws Exception {
        clearHold();
        openRiding();
        startRecording();
        leave();
        drop();
        comeBack();
        require(recording(), "Recording still running");
        requireOpen("a recording still running");
        shot("5-recording-holds-open");
        connect();
        main(() -> service.stopRide());
        settle();
        requireOpen("the return was already judged; a later stop on screen closes nothing");
        closeRidingMode();
    }

    /** Doesn't count until it has seen one connected, then does. The system-settings trip is this leave. */
    private void openedWithNoScooter() throws Exception {
        clearHold();
        drop();
        openRiding();
        leave();
        comeBack();
        requireOpen("opened with no scooter, through a leave and return");
        shot("6-opened-disconnected-stays");
        connect();
        settle();
        leave();
        drop();
        comeBack();
        requireHome("seen connected on screen, then lost off screen");
        connect();
    }

    /** Paused, not stopped, as under a permission dialog. Driven through the activity's own callbacks. */
    private void pauseIsNotALeave() throws Exception {
        clearHold();
        openRiding();
        main(() -> callActivityOnPause(activity));
        drop();
        main(() -> callActivityOnResume(activity));
        settle();
        require(!away(), "A pause isn't a leave");
        requireOpen("a drop under a pause, like a permission dialog");
        connect();
        closeRidingMode();
    }

    /** A recreation on screen isn't a leave, whatever the connection is doing. */
    private void recreatedOnScreenWhileDown() throws Exception {
        clearHold();
        openRiding();
        drop();
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        main(() -> activity.recreate());
        Activity next = waitForMonitorWithTimeout(monitor, 30000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "Activity did not recreate");
        activity = (MainActivity) next;
        await(() -> field(activity, "riding") != null, "A recreation on screen must bring riding mode back");
        settle();
        requireOpen("recreated on screen with the connection down");
        connect();
        closeRidingMode();
    }

    /** Control for the next step: the activity is destroyed but the connection is untouched, so riding mode comes back. */
    private void activityGoneControl() throws Exception {
        clearHold();
        openRiding();
        goneToBackground();
        foreground();
        await(() -> field(activity, "riding") != null, "With the connection untouched a saved riding mode must come back");
        settle();
        requireOpen("activity destroyed, connection untouched");
        closeRidingMode();
    }

    private void activityGoneAfterDrop() throws Exception {
        clearHold();
        openRiding();
        goneToBackground();
        drop();
        connect();
        foreground();
        require(field(activity, "riding") == null && !(Boolean) field(activity, "ridingRestore"), "A saved riding mode must not come back after a loss");
        requireHome("activity destroyed, dropped and reconnected while away");
        shot("7-activity-gone-home");
    }

    private void requireHome(String step) {
        settle();
        require(!open(), step + ": riding mode should have closed");
        require("home".equals(field(activity, "selectedPage")), step + ": closed to Home");
        require(!screenName().contains("RidingScreen"), step + ": Home is on screen, not " + screenName());
        require(!held(), step + ": no auto-open hold");
        note(step + ": Home, hold " + held());
    }
    private void requireOpen(String step) {
        settle();
        require(open(), step + ": riding mode should stay open");
        require(screenName().contains("RidingScreen"), step + ": riding mode is on screen, not " + screenName());
        require(!held(), step + ": no auto-open hold");
        note(step + ": still open");
    }

    private boolean open() { return read(() -> field(activity, "riding") != null); }
    private boolean connected() { return read(() -> service.ble.connected()); }
    private boolean recording() { return read(() -> service.isRecording()); }
    private boolean away() { return read(() -> (Boolean) call(field(activity, "riding"), "away", new Class<?>[]{})); }
    private boolean held() { return read(() -> (Boolean) field(field(service, "autoStart"), "rideModeHeld")); }
    private String screenName() { AtomicReference<String> s = new AtomicReference<>(); main(() -> { Object v = field(activity, "screen"); s.set(v == null ? "null" : v.getClass().getSimpleName()); }); return s.get(); }
    private boolean read(BooleanSupplier value) { AtomicReference<Boolean> v = new AtomicReference<>(); main(() -> v.set(value.getAsBoolean())); return v.get(); }

    private void drop() { main(() -> service.ble.disconnect()); await(() -> !service.ble.connected(), "Simulated scooter did not disconnect"); settle(); }
    private void connect() { main(() -> service.ble.connectLast()); await(() -> service.ble.connected(), "Simulated scooter did not reconnect"); settle(); }
    private void openRiding() { main(() -> activity.openRiding()); await(() -> field(activity, "riding") != null, "Riding mode did not open"); settle(); }
    private void startRecording() { main(() -> call(service, "beginRide", new Class<?>[]{String.class}, "Synthetic")); await(() -> service.isRecording(), "Recording did not start"); settle(); }
    private void closeRidingMode() { main(() -> activity.onBackPressed()); await(() -> field(activity, "riding") == null, "Back did not close riding mode"); settle(); }
    /** Back sets the hold on purpose; each step asserts only what it adds. */
    private void clearHold() {
        main(() -> {
            try { Object auto = field(service, "autoStart"); Field f = auto.getClass().getDeclaredField("rideModeHeld"); f.setAccessible(true); f.setBoolean(auto, false); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    /** Off screen with the activity alive. */
    private void leave() throws Exception { shell("input keyevent KEYCODE_HOME"); await(() -> field(activity, "riding") != null && (Boolean) call(field(activity, "riding"), "away", new Class<?>[]{}), "Activity did not stop"); settle(); }
    private void comeBack() throws Exception { bringForward(); await(() -> (Boolean) field(activity, "resumed"), "Activity did not come back"); settle(); }
    /** The intent the task started with brings it forward, so the same task and its saved state come back. */
    private void bringForward() { getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    /** Destroyed the way Android does under memory pressure: its state is saved. */
    private void goneToBackground() throws Exception {
        MainActivity old = activity;
        setAlwaysFinish(true);
        shell("input keyevent KEYCODE_HOME");
        await(old::isDestroyed, "Activity was not destroyed");
        setAlwaysFinish(false);
    }
    private void foreground() throws Exception {
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        bringForward();
        Activity next = waitForMonitorWithTimeout(monitor, 30000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "Activity did not come back");
        activity = (MainActivity) next;
        await(() -> activity.service() != null, "Service did not rebind");
        settle();
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
        long until = SystemClock.uptimeMillis() + 30000;
        while (SystemClock.uptimeMillis() < until) {
            if (read(condition)) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(message);
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(550); }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
