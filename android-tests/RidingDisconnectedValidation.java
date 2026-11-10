package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.jjricks.volt.BleController;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The controls while the scooter is disconnected (owner, Nov 9). On Home, a recording stops from the disc with the link gone.
 * In riding mode Lock, Record and the tune button share one gate: shown when not connected or stopped, hidden connected and
 * moving, and together on the frame the gate flips. Record stops a recording while disconnected and otherwise does nothing;
 * Lock keeps its last known state and queues nothing. Taps are real touches. The simulator covers the states; a synthetic
 * authenticated link covers what the simulator can't: connected with no motion report yet, and a real end of the link.
 * Disposable emulator only.
 */
public final class RidingDisconnectedValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private File evidence;
    private String label = "", only = "", immersivePrevious;
    private int assertions;
    private final Bundle result = new Bundle();
    private final ArrayList<String> notes = new ArrayList<>();

    @Override public void onCreate(Bundle args) {
        if (args != null) { label = args.getString("label", ""); only = args.getString("only", ""); }
        start();
    }

    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "emulator only");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-disconnected");
            require(evidence.mkdirs() || evidence.isDirectory(), "evidence directory");
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            run();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            try { if (activity != null) main(() -> { invoke(activity, "closeRiding"); service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            try { if (immersivePrevious != null) shell(immersivePrevious.isEmpty() || "null".equals(immersivePrevious) ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious); } catch (Throwable ignored) { }
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        require(prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                .putBoolean("phone_gps", false).commit(), "settings");
        main(() -> {
            OnboardingScreen.markDone(getTargetContext());
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile p = profiles.create("Synthetic disconnect scooter", "0F1E2D3C4B5A", 0);
            p.mac = "C0:FF:EE:00:00:0A"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
        });
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch");
        service = activity.service();
        settle();
        main(() -> require(service.setSimulation(true), "simulation"));
        script("stopped");
        await(() -> service.ble.ready(), "simulation ready");
        settle();
        homeDisc();
        if ("home".equals(only)) return;
        riding();
        gates();
        recordWhileDisconnected();
        lockWhileDisconnected();
        whileSaving();
        automaticEndWithTap();
        syntheticLink();
    }

    // Home's disc, the owner's "just covering my bases" case.

    private void homeDisc() throws Exception {
        View lead = find(v -> v.getClass().getSimpleName().equals("RecordLead"));
        require(lead != null, "Home has a recorder disc");
        int rides = rideCount();
        beginRide();
        drop();
        require(!connected() && recording(), "recording, link gone");
        shot("home-1-recording-disconnected");
        require(read(lead::isEnabled), "Home's disc takes a tap while recording and disconnected");
        tap(lead);
        await(() -> !service.isRecording(), "Home's disc stopped the recording while disconnected");
        require(rideCount() == rides + 1, "exactly one ride saved");
        require(!connected(), "stopping doesn't connect");
        settle();
        shot("home-2-stopped-disconnected");
        note("Home: recording, disconnected, tap: the recording stopped");
        // Not recording and disconnected keeps Home's connect behaviour.
        tap(lead);
        await(() -> service.ble.connected(), "Home's disc still connects when not recording");
        require(!recording(), "and doesn't start a recording");
        settle();
    }

    // Riding mode.

    private void riding() throws Exception {
        script("stopped");
        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null && "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding mode opened");
        settle();
        await(() -> controlsVisible() && tuneTakes(), "stopped: all three show");
    }

    /** The gate, each change sampled every frame: the three stay together and nothing flickers. */
    private void gates() throws Exception {
        require(picker().getVisibility() == View.VISIBLE, "tune shown stopped");
        shot("riding-1-stopped-connected");
        change("riding", false, "connected and moving: all three hidden");
        shot("riding-2-moving");
        // The link drops while moving: the three come back, once.
        change("dropout", true, "moving, then disconnected: all three shown");
        shot("riding-3-disconnected-from-moving");
        // Reconnect while moving: gone again.
        change("riding", false, "reconnected moving: hidden");
        change("stopped", true, "stopped: shown");
        // The link drops while stopped: the gate never closes, so no frame may dip.
        List<float[]> steady = frames(() -> script("dropout"));
        for (float[] f : steady) require(f[0] == View.VISIBLE && near(f[1], 1) && f[2] == 1, "stopped to disconnected keeps them up on every frame: " + java.util.Arrays.toString(f));
        await(() -> !service.ble.connected(), "dropped");
        require(controlsVisible() && tuneTakes() && picker().getVisibility() == View.VISIBLE, "disconnected: all three shown");
        shot("riding-4-disconnected-from-stopped");
        steady = frames(() -> script("stopped"));
        for (float[] f : steady) require(f[0] == View.VISIBLE && near(f[1], 1) && f[2] == 1, "reconnecting stopped keeps them up: " + java.util.Arrays.toString(f));
        await(() -> service.ble.ready(), "reconnected");
    }

    /** Scale and visibility of the row every frame through a script change, with the tune disc's agreement. */
    private void change(String id, boolean shown, String what) throws Exception {
        List<float[]> samples = frames(() -> script(id));
        await(() -> { boolean controls = controlsVisible() && stoppedTakes(); return (shown ? controls : !controlsVisible()) && tuneTakes() == shown; }, what);
        settle();
        require(stoppedTakes() == shown && tuneTakes() == shown, what + ": the row and the tune button agree");
        // One change of mind at most, the right way, and the size only ever moves toward the end: no flicker.
        int flips = 0, reversals = 0;
        for (int i = 1; i < samples.size(); i++) {
            float[] a = samples.get(i - 1), b = samples.get(i);
            if (a[2] != b[2]) flips++;
            if (shown ? b[1] + .02f < a[1] : b[1] > a[1] + .02f) reversals++;
        }
        require(flips <= 1 && reversals == 0 && !samples.isEmpty() && (samples.get(samples.size() - 1)[2] != 0) == shown,
                what + ": no flicker, " + samples.size() + " frames, " + flips + " flips, " + reversals + " reversals");
    }

    private List<float[]> frames(Runnable change) {
        List<float[]> samples = new ArrayList<>();
        boolean[] run = {true};
        main(change);
        main(() -> Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long nanos) {
                View controls = (View) field(screen(), "stoppedControls"), record = (View) field(screen(), "recordDisc");
                samples.add(new float[]{controls.getVisibility(), record.getScaleX(), stoppedTakes() ? 1 : 0});
                if (run[0]) Choreographer.getInstance().postFrameCallback(this);
            }
        }));
        SystemClock.sleep(600);
        run[0] = false;
        waitForIdleSync();
        return samples;
    }

    private void recordWhileDisconnected() throws Exception {
        int rides = rideCount();
        main(() -> set(field(service, "autoStart"), "recordHeld", false));
        beginRide();
        script("dropout");
        await(() -> !service.ble.connected(), "dropped");
        await(() -> controlsVisible(), "Record shown");
        settle();
        View record = view("recordDisc");
        require(read(record::isEnabled) && read(record::isClickable), "Record takes a tap while recording and disconnected");
        require(english("ridesEndDescription").equals(text(record.getContentDescription())), "the label says it stops the ride: " + record.getContentDescription());
        shot("riding-5-disconnected-recording");
        Object state = read2(() -> service.ble.state());
        tap(record);
        await(() -> !service.isRecording(), "a tap stops the recording while disconnected");
        require(rideCount() == rides + 1, "one ride saved");
        require(open(), "the rider's own stop leaves riding mode open");
        require(!connected() && read2(() -> service.ble.state()) == state, "the stop tries no connection");
        require((Boolean) read2(() -> field(field(service, "autoStart"), "recordHeld")), "a hand stop holds auto-record");
        settle();

        // Not recording, disconnected: dim, and a tap does nothing.
        require(!read(record::isEnabled) && !read(record::isClickable), "dim Record takes no tap");
        String spoken = text(record.getContentDescription());
        // The simulator's dropout is a reconnect in progress (CONNECTING): Home's "Connecting" words; a link at rest says it needs one.
        String needed = state == BleController.State.CONNECTING ? english("dashboardRecordConnecting") : english("dashboardRecordConnect");
        require(needed.equals(spoken), "the label doesn't offer a start: " + spoken);
        shot("riding-6-disconnected-idle");
        tap(record);
        settle();
        require(!recording() && rideCount() == rides + 1, "a tap on the dim Record starts nothing");
        require(!connected() && read2(() -> service.ble.state()) == state, "and tries no connection");

        // Reconnect: nothing the tap did starts a second recording.
        script("stopped");
        await(() -> service.ble.ready(), "reconnected");
        settle();
        require(!recording() && rideCount() == rides + 1, "no second recording after the reconnect");
        require(read(record::isEnabled), "Record is live again");
    }

    private void lockWhileDisconnected() throws Exception {
        View lock = view("stoppedLock");
        require(lock != null, "the lock is there");
        main(activity::redraw);
        settle();
        Boolean before = (Boolean) read2(() -> field(lock, "value"));
        require(before != null, "the lock has a state while connected");
        script("dropout");
        await(() -> !service.ble.connected(), "dropped");
        await(() -> controlsVisible() && !(Boolean) field(lock, "available"), "Lock goes inoperable");
        settle();
        require(lock.getVisibility() == View.VISIBLE && lock.getParent() == view("stoppedControls"), "the same switch stays in its row, no offline card swapped in");
        Boolean shown = (Boolean) read2(() -> field(lock, "value"));
        note("Lock while disconnected shows " + shown + " (connected it read " + before + ")");
        String expected = shown == null ? english("mainLockSwitchUnavailable") : shown ? english("mainLockLastKnownFullLocked") : english("mainLockLastKnownFullUnlocked");
        require(expected.equals(text(lock.getContentDescription())), "the label doesn't promise a tap: " + lock.getContentDescription());
        shot("riding-7-lock-disconnected");
        tap(lock);
        slide(lock);
        settle();
        require(!lockWaiting(), "no tap queued while disconnected");
        require(java.util.Objects.equals(shown, read2(() -> field(lock, "value"))), "the shown state doesn't move");
        script("stopped");
        await(() -> service.ble.ready() && controlsVisible(), "reconnected");
        settle();
        await(() -> (Boolean) field(lock, "available"), "Lock is live again");
        Boolean now = (Boolean) read2(() -> field(lock, "value"));
        require(now != null, "a state again");
        tap(lock);
        await(() -> !lockWaiting() && !java.util.Objects.equals(now, service.ble.locked()), "after the reconnect the queue sends as before");
        tap(lock);
        await(() -> !lockWaiting() && java.util.Objects.equals(now, service.ble.locked()), "and back");
    }

    private void whileSaving() throws Exception {
        View record = view("recordDisc");
        script("dropout");
        await(() -> !service.ble.connected(), "dropped");
        settle();
        int rides = rideCount();
        main(() -> set(service, "phoneSavingCount", 1));
        main(activity::redraw);
        settle();
        require(!read(record::isEnabled), "still saving: dim");
        require(english("dashboardRecordSaving").equals(text(record.getContentDescription())), "the label says it's saving");
        tap(record);
        settle();
        require(!recording() && rideCount() == rides, "a tap during the save starts nothing");
        beginRide();
        main(activity::redraw);
        settle();
        require(!read(record::isEnabled), "a recording during a save stays dim, as on Home");
        main(() -> set(service, "phoneSavingCount", 0));
        main(activity::redraw);
        await(() -> record.isEnabled() && record.isClickable(), "the save done, it takes the stop");
        tap(record);
        await(() -> !service.isRecording(), "the stop lands");
        require(rideCount() == rides + 1, "one ride");
        settle();
    }

    /** An automatic ride end and a tap together: one stop, whichever lands first. */
    private void automaticEndWithTap() throws Exception {
        View record = view("recordDisc");
        script("stopped");
        await(() -> service.ble.ready(), "reconnected");
        settle();
        // The tap first, on a disconnected recording: the rider's own stop, and the automatic one finds nothing.
        int rides = rideCount();
        beginRide();
        script("dropout");
        await(() -> !service.ble.connected(), "dropped");
        settle();
        boolean[] autoStopped = {true};
        main(() -> { record.performClick(); autoStopped[0] = (Boolean) call(service, "stopRide", new Class<?>[]{String.class}, automatic()); });
        require(!autoStopped[0] && !recording(), "tap then automatic end: the automatic end found nothing to stop");
        require(rideCount() == rides + 1, "one ride");
        settle();
        require(open(), "a rider's stop leaves riding mode open");
        script("stopped");
        await(() -> service.ble.ready(), "reconnected");
        settle();
    }

    /** A real authenticated link, with no simulator: connected and no motion report, and the real end of the link. */
    private void syntheticLink() throws Exception {
        if (open()) { main(() -> invoke(activity, "closeRiding")); settle(); }
        main(() -> require(service.setSimulation(false), "simulation off"));
        await(() -> service.ble.state() == BleController.State.DISCONNECTED, "link idle");
        FakeLink fake = new FakeLink();
        main(fake::attach);
        await(() -> service.ble.connected(), "synthetic link up");
        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null && "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding mode opened");
        settle();
        require(service.ble.connected() && !service.ble.stationary(), "connected with no motion report");
        await(() -> !controlsVisible() && !tuneTakes(), "connected, no motion report: all three hidden together");
        require(picker().getVisibility() != View.VISIBLE || !tuneTakes(), "the tune button goes with them");
        shot("riding-8-connected-no-motion-report");
        report(0, 0);
        await(() -> controlsVisible() && tuneTakes(), "the first stopped report brings all three back");
        settle();
        // A recording, the real link ending: the hold's rule.
        int rides = rideCount();
        main(() -> set(field(service, "autoStart"), "recordHeld", false));
        beginRide();
        report(0, 0);
        main(() -> service.ble.disconnect());
        await(() -> !service.ble.connected(), "the link ended");
        await(() -> controlsVisible() && tuneTakes(), "disconnected: shown");
        settle();
        View record = view("recordDisc");
        tap(record);
        await(() -> !service.isRecording(), "the stop lands during the dropout");
        require(rideCount() == rides + 1, "one ride");
        settle();
        require((Boolean) read2(() -> field(field(service, "autoStart"), "recordHeld")), "a hand stop during a dropout holds auto-record");
        main(activity::redraw);
        settle();
        String atRest = read2(() -> service.ble.state() + " / " + text(record.getContentDescription()));
        require(!read(record::isEnabled) && atRest.endsWith(" / " + english("dashboardRecordConnect")), "a link at rest: dim, and it says it needs a connection: " + atRest);
        shot("riding-9-link-ended-idle");
        FakeLink again = new FakeLink();
        main(again::attach);
        await(() -> service.ble.connected(), "synthetic link back");
        await(() -> !(Boolean) field(field(service, "autoStart"), "recordHeld"), "the next connection ends the hold, as its rule says");
        require(!recording() && rideCount() == rides + 1, "no second recording from the tap");
        report(0, 0);
        await(() -> controlsVisible() && tuneTakes(), "reconnected, stopped: all three back");
        settle();

        // The automatic end first, connected, ready and stopped, before the disc has been redrawn: a tap on its stop mark
        // starts nothing. Off the simulator a start would really run, so this is where it can be seen.
        require(read(() -> service.ble.ready()), "the synthetic link reads as ready");
        rides = rideCount();
        beginRide();
        report(0, 0);
        settle();
        View disc = view("recordDisc");
        require(read(disc::isEnabled), "Record shows its stop mark, live");
        boolean[] stopped = {false};
        main(() -> { stopped[0] = (Boolean) call(service, "stopRide", new Class<?>[]{String.class}, automatic()); disc.performClick(); });
        require(stopped[0], "the automatic end stopped it");
        settle();
        require(!recording() && rideCount() == rides + 1, "automatic end then tap: one stop, and no recording starts from the tap");
    }

    private static String automatic() {
        try { return (String) Class.forName("com.jjricks.volt.RideStore").getField("STOP_AUTO_NO_MOTION").get(null); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** A motion report: fresh, and the scooter's speed and drive state. */
    private void report(int speed, int driving) {
        main(() -> {
            Object ble = service.ble;
            set(ble, "motionMs", SystemClock.elapsedRealtime());
            set(ble, "statusMs", SystemClock.elapsedRealtime());
            set(ble, "speedRaw", speed);
            set(ble, "driving", driving);
            invoke(service, "changed");
            activity.redraw();
        });
    }

    private final class FakeLink {
        void attach() { try {
            BleController ble = service.ble;
            ScooterProfiles.Profile profile = service.profiles.active();
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice("AA:BB:CC:DD:EE:02");
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            BluetoothGatt gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == args[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "SyntheticGatt"; }
                Class<?> type = method.getReturnType(); return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            // Field names differ across Android versions: those this one lacks it doesn't use.
            optional(gatt, "mClientRegistered", true); optional(gatt, "mDeviceBusy", false); optional(gatt, "mDeviceBusyLock", new Object()); optional(gatt, "mStateLock", new Object());
            set(gatt, "mDevice", device); optional(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService gattService = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(gattService, "mDevice", device);
            BluetoothGattCharacteristic writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            gattService.addCharacteristic(writer);
            set(ble, "profile", profile); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", profile.serial);
            set(ble, "motionMs", 0L);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            call(service, "changed", new Class<?>[]{});
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable: " + error, error); } }
    }

    // Helpers.

    private Object screen() { return field(activity, "screen"); }
    private View view(String name) { return (View) read2(() -> field(screen(), name)); }
    private View picker() { return view("picker"); }
    private boolean open() { return read(() -> field(activity, "riding") != null); }
    private boolean connected() { return read(() -> service.ble.connected()); }
    private boolean recording() { return read(() -> service.isRecording()); }
    private boolean controlsVisible() { return ((View) field(screen(), "stoppedControls")).getVisibility() == View.VISIBLE; }
    private boolean stoppedTakes() { return (Boolean) invoke(field(screen(), "stoppedMotion"), "takesTaps"); }
    private boolean tuneTakes() { return (Boolean) invoke(field(screen(), "disc"), "takesTaps"); }
    private boolean lockWaiting() { return read(() -> (Boolean) invoke(field(activity, "lockQueue"), "waiting")); }
    private String english(String name) { try { Method m = Class.forName("com.jjricks.volt.T").getDeclaredMethod(name); m.setAccessible(true); return (String) m.invoke(null); } catch (Exception e) { throw new RuntimeException(e); } }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private int rideCount() throws Exception { return service.rides.list().size(); }
    private void beginRide() { main(() -> call(service, "beginRide", new Class<?>[]{String.class}, "Synthetic")); await(() -> service.isRecording(), "recording started"); settle(); }
    private void drop() { main(() -> service.ble.disconnect()); await(() -> !service.ble.connected(), "simulated scooter disconnected"); settle(); }
    private void script(String id) { main(() -> { service.ble.setSimulationScript(id); service.ble.tickSimulation(); activity.redraw(); }); }

    private View find(Predicate<View> test) { View[] v = new View[1]; main(() -> v[0] = search(activity.getWindow().getDecorView(), test)); return v[0]; }
    private static View search(View root, Predicate<View> test) {
        if (test.test(root)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = search(((ViewGroup) root).getChildAt(i), test); if (found != null) return found; }
        return null;
    }

    private void tap(View target) {
        main(() -> target.requestRectangleOnScreen(new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight()), true));
        settle();
        long at = SystemClock.uptimeMillis();
        float[] c = centre(target, .5f);
        sendPointerSync(pointer(at, at, MotionEvent.ACTION_DOWN, c));
        SystemClock.sleep(40);
        sendPointerSync(pointer(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, c));
        waitForIdleSync();
    }
    private void slide(View target) {
        long at = SystemClock.uptimeMillis();
        sendPointerSync(pointer(at, at, MotionEvent.ACTION_DOWN, centre(target, .2f)));
        for (float f = .3f; f <= .8f; f += .1f) { sendPointerSync(pointer(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, centre(target, f))); SystemClock.sleep(16); }
        sendPointerSync(pointer(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, centre(target, .8f)));
        waitForIdleSync();
    }
    private float[] centre(View target, float fraction) {
        float[] c = new float[2];
        main(() -> { int[] p = new int[2]; target.getLocationOnScreen(p); c[0] = p[0] + target.getWidth() * fraction; c[1] = p[1] + target.getHeight() / 2f; });
        return c;
    }
    private MotionEvent pointer(long down, long time, int action, float[] at) {
        MotionEvent e = MotionEvent.obtain(down, time, action, at[0], at[1], 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "screenshot");
        File image = new File(evidence, (label.isEmpty() ? "" : label + "-") + name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        TextCensus.write(this, image);
    }
    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = reader.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }
    private void note(String text) { notes.add(text); android.util.Log.i("VoltDisconnected", text); }
    private static boolean near(float a, float b) { return Math.abs(a - b) < .01f; }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(400); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        long until = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < until) {
            if (read(condition)) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError(message);
    }
    private boolean read(BooleanSupplier value) { boolean[] v = new boolean[1]; main(() -> v[0] = value.getAsBoolean()); return v[0]; }
    private <T> T read2(java.util.function.Supplier<T> value) { AtomicReference<T> v = new AtomicReference<>(); main(() -> v.set(value.get())); return v.get(); }
    private void main(Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { action.run(); return; }
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error.set(t); } });
        if (error.get() != null) throw new RuntimeException(error.get());
    }
    private static Object field(Object target, String name) {
        try { Field f = field(target.getClass(), name); f.setAccessible(true); return f.get(target); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> t = type; t != null; t = t.getSuperclass()) try { return t.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object target, String name, Object value) {
        try { Field f = field(target.getClass(), name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void optional(Object target, String name, Object value) {
        try { Field f = field(target.getClass(), name); f.setAccessible(true); f.set(target, value); } catch (NoSuchFieldException ignored) { } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object invoke(Object target, String name) { return call(target, name, new Class<?>[0]); }
}
