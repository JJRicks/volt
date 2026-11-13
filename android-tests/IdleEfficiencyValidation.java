package com.jjricks.volt;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Disposable emulator only: Volt at rest costs the phone nothing it doesn't need (owner, Nov 12 2026, after a battery
 * investigation). Every packet goes to an in-process GATT stand-in with a synthesized scooter; no radio, no phone.
 *
 * Notice: connected and idle, the connection notice isn't posted again while nothing in it changes, apart from the
 * refresh that keeps its header's time at "now", in sight and out of sight (15 s tick), counted from Android's event log
 * (notification_enqueue); a battery change still updates it at once. Window: idle on Home, the keep-screen-on flag no longer lays the window out every second (relayout calls
 * counted on the window's own session), and the flag is still set and cleared as before: riding mode, a recording with
 * the keep-awake setting, the setting turned off, a recreation in riding mode. Simulator: a recreation keeps the
 * simulation; Home, and ten seconds later the service is no longer a started service, the simulation still there on
 * return; a recreation Android defers until Volt is shown again keeps it too; a simulated charge in riding mode comes
 * back as it was; and, reported only, what's left when the activity itself is destroyed while Volt is away. Mode "measure"
 * reports the counts and the service state without asserting them (for a run on main); part (notice, window, simulator)
 * runs one section.
 */
public final class IdleEfficiencyValidation extends Instrumentation {
    private static final long S = 1000L;
    private static final byte OFF = 0;
    private MainActivity activity;
    private ScooterService service;
    private BleController ble;
    private ScooterProfiles.Profile profile;
    private FakeLink link;
    private int checks;
    private String mode = "", part = "";
    /** The night mode to put back ("no", "yes", "auto", ...), set while the test has flipped it. */
    private String nightBack;
    private final StringBuilder timeline = new StringBuilder();
    private final Bundle numbers = new Bundle();
    private final AtomicInteger relayouts = new AtomicInteger();

    @Override public void onCreate(Bundle args) { if (args != null) { mode = args.getString("mode", ""); part = args.getString("part", ""); } start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", true).putBoolean("keep_screen_awake", true).commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext()); profile = profiles.create("Synthetic idle fixture", "000000000000", 0);
                profile.serial = "Q2538ZZ9US7777778"; profile.mac = "C0:FF:EE:00:25:39"; profiles.update(profile); profiles.select(profile.id);
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, 10000, "Service missing"); service = activity.service(); ble = service.ble; waitForIdleSync();
            main(() -> { link = new FakeLink(); link.attach(); });
            await(() -> onMain(() -> ble.ready()), 10000, "Synthetic link never ready");
            log("link ready, not charging");
            if (part.isEmpty() || part.equals("notice")) notice();
            if (part.isEmpty() || part.equals("window")) window();
            if (part.isEmpty() || part.equals("simulator")) simulator();
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace(); }
        finally {
            try { if (nightBack != null) shell("cmd uimode night " + nightBack); } catch (RuntimeException ignored) { }
            result.putAll(numbers);
            result.putInt("assertions", checks); result.putBoolean("physical_device_tested", false); result.putString("timeline", timeline.toString());
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    private boolean measuring() { return "measure".equals(mode); }
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator permissions first: " + permission);
        require(getTargetContext().getSystemService(BluetoothManager.class).getAdapter() != null, "The emulator has no Bluetooth adapter object");
    }

    // ---- The connection notice -------------------------------------------------------------------------

    private void notice() {
        ensureFront();
        SystemClock.sleep(6 * S);
        int inSight = postsOver(60 * S);
        long inSightAge = postAge();
        log("in sight, idle 60 s: " + inSight + " connection notice posts; showing: " + shown() + "; its time is " + inSightAge / 1000 + " s old");
        numbers.putInt("notice_posts_in_sight_60s", inSight);
        require(shown().contains("62%"), "The connected notice shows the battery: " + shown());

        double from = now();
        main(() -> { link.level = 61; link.home(); link.battery(); });
        await(() -> shown().contains("61%"), 8000, "A battery change never reached the notice: " + shown());
        SystemClock.sleep(1500);
        int changed = posts(from, now());
        log("battery 62% to 61%: notice updated (" + changed + " posts): " + shown());
        numbers.putInt("notice_posts_on_change", changed);
        require(changed >= 1, "The changed notice was not posted");

        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> !service.isVisible()), 10000, "Volt never left the foreground");
        SystemClock.sleep(5 * S);
        require(onMain(() -> ble.connected()), "The background connection dropped");
        int away = postsOver(60 * S);
        long awayAge = postAge();
        log("out of sight, idle 60 s: " + away + " connection notice posts; showing: " + shown() + "; its time is " + awayAge / 1000 + " s old");
        numbers.putInt("notice_posts_out_of_sight_60s", away);
        require(shown().contains("61%"), "The notice out of sight lost its text: " + shown());

        from = now();
        main(() -> { link.level = 60; link.home(); link.battery(); });
        await(() -> shown().contains("60%"), 20 * S, "Out of sight, a battery change never reached the notice: " + shown());
        int awayChanged = posts(from, now() + 1);
        log("out of sight, 61% to 60%: notice updated (" + awayChanged + " posts)");
        require(awayChanged >= 1, "Out of sight, the changed notice was not posted");
        if (!measuring()) {
            // An unchanged notice posts again only to keep its header's time at "now" (NoticeRepeat.REFRESH_MS, 40 s).
            require(inSight <= 2, "In sight the unchanged notice was posted " + inSight + " times in 60 s");
            require(away <= 2, "Out of sight the unchanged notice was posted " + away + " times in 60 s");
            require(inSightAge < 60 * S && awayAge < 60 * S, "The notice's time aged past a minute: " + inSightAge + " ms, " + awayAge + " ms");
        }
        ensureFront();
    }
    private int postsOver(long ms) { double from = now(); SystemClock.sleep(ms); return posts(from, now()); }
    private static double now() { return System.currentTimeMillis() / 1000d; }
    /** notification_enqueue entries in the event log for Volt's notice 71 between two wall times (seconds). */
    private int posts(double from, double to) {
        String marker = "," + getTargetContext().getPackageName() + ",71,";
        int count = 0;
        for (String line : shell("logcat -b events -d -v epoch").split("\n")) {
            if (!line.contains("notification_enqueue") || !line.contains(marker)) continue;
            double at;
            try { at = Double.parseDouble(line.trim().split("\\s+")[0]); } catch (RuntimeException unreadable) { continue; }
            if (at >= from && at <= to) count++;
        }
        return count;
    }
    /** How old the time in the connection notice's header is (Android shows "now" below a minute). */
    private long postAge() { StatusBarNotification n = connection(); return n == null ? -1 : System.currentTimeMillis() - n.getNotification().when; }
    private StatusBarNotification connection() {
        for (StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == 71 && n.getTag() == null) return n;
        return null;
    }
    private String shown() {
        StatusBarNotification n = connection(); if (n == null) return "(no connection notice)";
        Bundle extras = n.getNotification().extras;
        return extras.getCharSequence(Notification.EXTRA_TITLE) + " | " + extras.getCharSequence(Notification.EXTRA_TEXT);
    }

    // ---- The window --------------------------------------------------------------------------------------

    private void window() throws Exception {
        ensureFront();
        require(!keepScreenOn(), "Idle on Home, the screen is not kept on");
        countRelayouts();
        SystemClock.sleep(3 * S);
        int before = relayouts.get();
        SystemClock.sleep(60 * S);
        int idle = relayouts.get() - before;
        log("idle on Home 60 s: " + idle + " window relayouts");
        numbers.putInt("window_relayouts_idle_60s", idle);
        if (!measuring()) require(idle <= 5, "Idle on Home the window was laid out " + idle + " times in 60 s");

        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null && onMain(this::keepScreenOn), 5000, "Riding mode did not keep the screen on");
        log("riding mode: screen kept on");
        MainActivity next = recreate();
        await(() -> field(next, "riding") != null && onMain(this::keepScreenOn), 8000, "After a recreation riding mode did not keep the screen on");
        log("riding mode after a recreation: screen kept on");
        main(() -> call(activity, "riderClosedRiding"));
        await(() -> field(activity, "riding") == null && !onMain(this::keepScreenOn), 5000, "Closing riding mode did not release the screen");
        log("riding mode closed: released");

        freshLink();
        main(() -> service.startRide("Synthetic idle ride"));
        await(() -> onMain(() -> service.isRecording()), 8000, "The ride did not start");
        await(() -> onMain(this::keepScreenOn), 5000, "A recording with keep-awake on did not keep the screen on");
        log("recording, keep-awake on: kept on");
        main(() -> settings().edit().putBoolean("keep_screen_awake", false).commit());
        await(() -> !onMain(this::keepScreenOn), 5000, "Turning keep-awake off did not release the screen");
        log("recording, keep-awake off: released");
        main(() -> settings().edit().putBoolean("keep_screen_awake", true).commit());
        await(() -> onMain(this::keepScreenOn), 5000, "Turning keep-awake back on did not keep the screen on");
        log("recording, keep-awake on again: kept on");
        main(() -> service.stopRide());
        await(() -> !onMain(() -> service.isRecording()) && !onMain(this::keepScreenOn), 10000, "Stopping the ride did not release the screen");
        log("ride stopped: released");
        await(() -> !onMain(() -> service.phoneSaving()), 15000, "The ride never finished saving");
        require(!onMain(this::keepScreenOn), "Idle again, the screen is not kept on");
    }
    /**
     * startRide needs a fresh status (ble.ready). The synthetic link answers every poll, but a write the stand-in GATT
     * refused once (a pool emulator whose Bluetooth stack had crashed at boot) left it not ready; that isn't what this
     * test checks, so the link is attached again, and the timeline says so.
     */
    private void freshLink() {
        main(() -> link.status());
        long end = SystemClock.uptimeMillis() + 3000;
        while (SystemClock.uptimeMillis() < end && !onMain(() -> ble.ready())) SystemClock.sleep(50);
        if (onMain(() -> ble.ready())) return;
        log("synthetic link not ready (" + field(ble, "state") + "): attached again");
        main(() -> link.attach());
        await(() -> onMain(() -> ble.ready()), 10000, "The synthetic link never came back");
    }
    private boolean keepScreenOn() { return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0; }
    /** Every relayout the activity's window asks the window manager for, counted on its own session. */
    private void countRelayouts() {
        main(() -> {
            try {
                Object root = activity.getWindow().getDecorView().getParent();
                Field session = reflected(root, "mWindowSession");
                Object real = session.get(root);
                Class<?> type = Class.forName("android.view.IWindowSession");
                Object counting = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    if (method.getName().startsWith("relayout")) relayouts.incrementAndGet();
                    try { return method.invoke(real, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
                });
                session.set(root, counting);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Window session unavailable; relax hidden_api_policy on the disposable emulator", error); }
        });
    }

    // ---- The simulator -----------------------------------------------------------------------------------

    private void simulator() throws Exception {
        ensureFront();
        main(() -> require(service.setSimulation(true), "The simulator did not turn on"));
        await(() -> onMain(() -> service.simulating() && service.ble.connected()), 8000, "The simulation never connected");
        ScooterService simulated = service;

        recreate();
        require(activity.service() == simulated && onMain(() -> simulated.simulating()), "A recreation in sight lost the simulation");
        log("recreation in sight: same service, simulation on");

        String started = leaveFor(12 * S);
        numbers.putString("simulator_start_requested_after_home", started);
        require(onMain(() -> !Boolean.TRUE.equals(field(simulated, "destroyed")) && simulated.simulating()), "Out of sight the simulation ended while the activity lived");
        if (!measuring()) require("false".equals(started), "Ten seconds out of sight the simulator's service is still started: startRequested=" + started);
        ensureFront();
        require(activity.service() == simulated && onMain(() -> simulated.simulating()), "Back in Volt the simulation is gone");
        log("back in Volt: simulation on, startRequested=" + startRequested());

        // Android recreates a hidden activity only when it's shown again: the old one unbinds, then the new one binds.
        // Flip whatever the activity now has (an earlier step, or "auto" at night, may have it dark), and put the mode back after.
        String night = shell("cmd uimode night").trim();
        String back = night.substring(night.lastIndexOf(' ') + 1);
        boolean dark = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String flip = dark ? "no" : "yes";
        leaveFor(12 * S);
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        nightBack = back;
        shell("cmd uimode night " + flip);
        SystemClock.sleep(3 * S);
        require(onMain(() -> !Boolean.TRUE.equals(field(simulated, "destroyed"))), "The theme change alone ended the service");
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        MainActivity next = newActivity(monitor, activity, 15 * S);
        require(next != null, "Showing Volt after the theme change did not recreate it");
        activity = next;
        await(() -> activity.service() != null && onMain(() -> service().isVisible()), 10000, "The recreated activity never bound");
        boolean kept = activity.service() == simulated && onMain(() -> activity.service().simulating());
        numbers.putBoolean("simulator_kept_after_deferred_recreation", kept);
        log("recreated on return after a hidden theme change: " + (kept ? "same service, simulation on" : "simulation lost"));
        require(kept, "A recreation on return lost the simulation");
        ActivityMonitor restored = addMonitor(MainActivity.class.getName(), null, false);
        shell("cmd uimode night " + back);
        nightBack = null;
        MainActivity light = newActivity(restored, activity, 15 * S);
        if (light != null) activity = light;
        await(() -> activity.service() != null && onMain(() -> service().isVisible()), 10000, "The activity never came back after the theme change");

        // Something going on in the simulation: a simulated charge, with riding mode open.
        main(() -> { service().ble.setSimulationCharging(true); activity.openRiding(); });
        await(() -> field(activity, "riding") != null && onMain(() -> service().ble.simulationCharging()), 5000, "Riding mode or the simulated charge did not start");
        String during = leaveFor(12 * S);
        ensureFront();
        boolean riding = field(activity, "riding") != null, charging = onMain(() -> service().ble.simulationCharging()), on = onMain(() -> service().simulating());
        log("simulated charge in riding mode, left 12 s (startRequested=" + during + "), back: riding mode " + (riding ? "open" : "closed")
                + ", charging " + charging + ", simulation " + on);
        numbers.putBoolean("simulator_riding_open_after_return", riding);
        require(on && charging, "The simulated charge did not survive leaving Volt");
        main(() -> { if (field(activity, "riding") != null) call(activity, "riderClosedRiding"); service().ble.setSimulationCharging(false); });

        // Reported, not asserted: the activity itself is destroyed while Volt is away (finished, as when Android or the
        // person closes it), with the simulation on.
        ScooterService before = service();
        MainActivity away = activity;
        leaveFor(2 * S);
        main(() -> away.finish());
        await(() -> away.isDestroyed(), 10000, "The activity was not destroyed");
        SystemClock.sleep(12 * S);
        String afterDestroy = startRequested();
        boolean ended = onMain(() -> Boolean.TRUE.equals(field(before, "destroyed")) || !before.simulating());
        ActivityMonitor shown = addMonitor(MainActivity.class.getName(), null, false);
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        MainActivity fresh = newActivity(shown, away, 15 * S);
        require(fresh != null, "Volt did not open again");
        activity = fresh;
        await(() -> activity.service() != null && onMain(() -> service().isVisible()), 10000, "Volt never came back after the activity was destroyed");
        boolean survived = activity.service() == before && onMain(() -> service().simulating());
        log("activity destroyed while away, 12 s later: startRequested=" + afterDestroy + ", simulation " + (ended ? "ended" : "kept")
                + "; opened again: simulation " + (survived ? "on" : "off"));
        numbers.putBoolean("simulator_kept_after_activity_destroyed_away", survived);
        main(() -> service().setSimulation(false));
    }
    private ScooterService service() { return activity.service(); }
    /** Home for a while: the service's startRequested from dumpsys at the end ("absent" with no service record). */
    private String leaveFor(long ms) {
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> activity.service() == null || !activity.service().isVisible()), 10000, "Volt never left the foreground");
        SystemClock.sleep(ms);
        String started = startRequested();
        log("out of sight " + (ms / 1000) + " s: startRequested=" + started);
        return started;
    }
    private String startRequested() {
        String dump = shell("dumpsys activity services " + getTargetContext().getPackageName() + "/.ScooterService");
        int at = dump.indexOf("startRequested=");
        if (at < 0) return "absent";
        int end = at + "startRequested=".length();
        while (end < dump.length() && Character.isLetter(dump.charAt(end))) end++;
        return dump.substring(at + "startRequested=".length(), end);
    }
    /** The first MainActivity other than old the monitor sees (a startActivity call wakes a plain wait early, with nothing). */
    private MainActivity newActivity(ActivityMonitor monitor, MainActivity old, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        try {
            while (SystemClock.uptimeMillis() < end) {
                Activity seen = monitor.getLastActivity();
                if (seen instanceof MainActivity && seen != old) return (MainActivity) seen;
                SystemClock.sleep(50);
            }
            return null;
        } finally { removeMonitor(monitor); }
    }
    private MainActivity recreate() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity old = activity;
        main(() -> old.recreate());
        MainActivity next = (MainActivity) waitForMonitorWithTimeout(monitor, 15 * S); removeMonitor(monitor);
        require(next != null && next != old, "No new activity after recreate()");
        activity = next;
        await(() -> activity.service() != null && onMain(() -> activity.service().isVisible()), 10000, "The recreated activity never bound");
        settle();
        return next;
    }
    private void ensureFront() {
        if (activity.service() != null && onMain(() -> activity.service().isVisible())) return;
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        await(() -> activity.service() != null && onMain(() -> activity.service().isVisible()), 10000, "Volt never returned to the foreground");
        settle();
    }

    private final class FakeLink {
        final Handler handler = new Handler(Looper.getMainLooper());
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        int level = 62;
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == args[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "IdleFixtureGatt"; }
                if (method.getName().equals("writeCharacteristic")) {
                    byte[] frame = null; if (args != null) for (Object arg : args) if (arg instanceof byte[]) frame = ((byte[]) arg).clone();
                    set(gatt, "mDeviceBusy", false); final byte[] packet = frame;
                    if (packet != null) handler.postDelayed(() -> received(packet), 10); return 0;
                }
                Class<?> type = method.getReturnType(); return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            set(gatt, "mClientRegistered", true); set(gatt, "mDeviceBusy", false); set(gatt, "mDeviceBusyLock", new Object()); set(gatt, "mStateLock", new Object()); set(gatt, "mDevice", device); set(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService scooter = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(scooter, "mDevice", device);
            writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier = new BluetoothGattCharacteristic(BleController.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0); scooter.addCharacteristic(writer); scooter.addCharacteristic(notifier);
            callbacks = (BluetoothGattCallback) field(ble, "callbacks"); set(ble, "profile", profile); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", profile.serial);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            status(); home(); battery();
            Method poll = BleController.class.getDeclaredMethod("poll", int.class); poll.setAccessible(true); poll.invoke(ble, ble.connectionGeneration());
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); } }
        void received(byte[] frame) {
            callbacks.onCharacteristicWrite(gatt, writer, 0);
            if (frame.length < 4) return;
            switch (frame[3] & 255) { case 0x70: status(); break; case 0x72: battery(); break; default: break; }
        }
        void status() { byte[] state = new byte[44]; state[1] = 3; state[3] = 1; state[6] = 2; state[7] = 1; state[19] = 3; state[25] = 50; state[31] = 100; state[34] = 3; packet(0x70, state); }
        void home() { byte[] data = new byte[12]; data[1] = 1; data[2] = (byte) level; data[4] = OFF; packet(0x90, data); }
        void battery() { byte[] data = new byte[24]; data[1] = (byte) level; data[12] = OFF; packet(0x72, data); }
        void packet(int cmd, byte[] payload) {
            byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa; packet[3] = (byte) cmd; packet[4] = (byte) (payload.length + 1);
            System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0; for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
            packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
            handler.postDelayed(() -> callbacks.onCharacteristicChanged(gatt, notifier, packet), 10);
        }
    }

    private String shell(String command) {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private void log(String line) { timeline.append(SystemClock.elapsedRealtime() / 1000).append("s ").append(line).append('\n'); Bundle status = new Bundle(); status.putString("progress", line); sendStatus(0, status); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE); }
    private void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private void main(Runnable task) { final Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new IllegalStateException(failure[0]); }
    private boolean onMain(BooleanSupplier query) { final boolean[] result = {false}; main(() -> result[0] = query.getAsBoolean()); return result[0]; }
    private void await(BooleanSupplier query, long ms, String message) { long end = SystemClock.uptimeMillis() + ms; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) return; SystemClock.sleep(50); } throw new AssertionError(message); }
    private void settle() { SystemClock.sleep(350); waitForIdleSync(); }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void call(Object object, String name) { try { Method m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(object); } catch (Exception error) { throw new IllegalStateException(error); } }
}
