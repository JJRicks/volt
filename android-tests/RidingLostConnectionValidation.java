package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.jjricks.volt.BleController;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Riding mode shows Home at the next open after the connection was lost with no recording running, and at no
 * other time. Most steps drive the simulated scooter's connection. Volt's own Bluetooth pause doesn't exist in
 * simulation, so those steps fake a real authenticated link and let the service's real 10-second deadline pause it,
 * and wait out the real 2 minutes. Process death can't be driven from inside the process that would die, so that
 * one is in RidingValuesTest's saved-state round trip. Disposable emulator only.
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
        main(() -> require(service.setSimulation(false), "Simulation off failed"));
        settle();
        ownPauseBackInTimeThenLeaveAgain();
        ownPausePastGrace();
        realDropBeforeThePause();
        ownPauseActivityGoneBackInTime();
        ownPauseActivityGonePastGrace();
    }

    /** Back within 2 minutes of Volt's pause stays; a second leave with the link still paused gets its own 2 minutes. */
    private void ownPauseBackInTimeThenLeaveAgain() throws Exception {
        link();
        leave();
        awaitOwnPause();
        comeBack();
        requireOpen("Volt's own pause, back inside 2 minutes");
        shot("8-own-pause-back-in-time-open");
        long losses = linkCount("losses");
        leave();
        comeBack();
        requireOpen("off again with the link still paused, back at once");
        leave();
        SystemClock.sleep(WAIT_PAST_GRACE_MS);
        comeBack();
        require(linkCount("losses") == losses, "No real loss was counted");
        requireHome("the second leave ran past its own 2 minutes");
    }

    private void ownPausePastGrace() throws Exception {
        link();
        leave();
        awaitOwnPause();
        SystemClock.sleep(WAIT_PAST_GRACE_MS);
        comeBack();
        requireHome("Volt's own pause, away past 2 minutes");
        shot("9-own-pause-past-grace-home");
    }

    /** A native disconnect inside the pause's 10 seconds is a real drop: it counts at once. */
    private void realDropBeforeThePause() throws Exception {
        link();
        leave();
        require(linkCount("pauses") == pausesAtLink, "Volt hasn't paused yet");
        long losses = linkCount("losses");
        main(() -> ((BluetoothGattCallback) field(service.ble, "callbacks")).onConnectionStateChange(fake.gatt, 0, BluetoothProfile.STATE_DISCONNECTED));
        await(() -> !service.ble.connected() && linkCount("losses") == losses + 1, "Native disconnect not counted as a real loss");
        comeBack();
        requireHome("a real drop while away, back at once");
    }

    /** The activity is destroyed while away, Volt pauses, and the same process rebuilds both inside 2 minutes. */
    private void ownPauseActivityGoneBackInTime() throws Exception {
        link();
        goneToBackground();
        awaitOwnPause();
        awaitServiceGone();
        foreground();
        await(() -> field(activity, "riding") != null, "A saved riding mode must come back inside the 2 minutes");
        settle();
        requireOpen("activity destroyed, Volt's pause, new service, back inside 2 minutes");
        closeRidingMode();
    }

    /** As above but past the 2 minutes: the time away came through the saved state. */
    private void ownPauseActivityGonePastGrace() throws Exception {
        link();
        goneToBackground();
        awaitOwnPause();
        SystemClock.sleep(WAIT_PAST_GRACE_MS);
        foreground();
        require(field(activity, "riding") == null && !(Boolean) field(activity, "ridingRestore"), "A saved riding mode must not come back past 2 minutes");
        requireHome("activity destroyed, Volt's pause, away past 2 minutes");
    }

    private static final long WAIT_PAST_GRACE_MS = 125_000;
    private FakeLink fake;
    private long pausesAtLink;

    /** A real (not simulated) authenticated link, and riding mode opened on it. */
    private void link() {
        clearHold();
        await(() -> service.ble.state() == BleController.State.DISCONNECTED, "Link still in use");
        fake = new FakeLink();
        main(fake::attach);
        await(() -> service.ble.connected(), "Fake link did not read as connected");
        pausesAtLink = linkCount("pauses");
        openRiding();
    }
    /** The service's real background deadline, through BleController.suspend, and counted as a pause and nothing else. */
    private void awaitOwnPause() {
        long losses = linkCount("losses");
        await(() -> !service.ble.connected(), "Volt did not pause Bluetooth");
        require(linkCount("pauses") == pausesAtLink + 1, "Counted as Volt's own pause");
        require(linkCount("losses") == losses, "And not as a loss");
    }
    /** ScooterService.running is the current instance; null once the idle service has stopped. */
    private void awaitServiceGone() { await(() -> staticField(ScooterService.class, "running") != service, "Idle service did not stop"); }
    private long linkCount(String name) {
        AtomicReference<Long> value = new AtomicReference<>();
        main(() -> value.set((Long) field(staticField(ScooterService.class, "LINK_LOG"), name)));
        return value.get();
    }

    private final class FakeLink {
        BluetoothGatt gatt;
        void attach() { try {
            BleController ble = service.ble;
            ScooterProfiles.Profile profile = ble.profile();
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01");
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == args[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "RidingFixtureGatt"; }
                Class<?> type = method.getReturnType(); return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            set(gatt, "mClientRegistered", true); set(gatt, "mDeviceBusy", false); set(gatt, "mDeviceBusyLock", new Object()); set(gatt, "mStateLock", new Object()); set(gatt, "mDevice", device); set(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService gattService = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(gattService, "mDevice", device);
            BluetoothGattCharacteristic writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            gattService.addCharacteristic(writer);
            set(ble, "profile", profile); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", profile.serial);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            call(service, "changed", new Class<?>[]{});
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable: " + error, error); } }
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
        service = activity.service();
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
    private static Object staticField(Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
