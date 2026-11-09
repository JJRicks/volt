package com.jjricks.volt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Disposable emulator only: auto-record and auto ride mode through the real service, activity and
 * BleController, on a synthetic in-process GATT link. Every packet goes to a stand-in, never a radio.
 * Bluetooth stays off. Needs hidden_api_policy 1 for the stand-in.
 */
public final class AutoStartValidation extends Instrumentation {
    private MainActivity activity; private ScooterService service; private BleController ble;
    private ScooterProfiles.Profile profile; private FakeLink link; private int checks; private boolean admitted;
    private final List<String> screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            main(() -> {
                prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", true)
                        .putBoolean("phone_gps", false).putBoolean("phone_motion", false).putBoolean("map_basemap_enabled", false).commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                profile = profiles.create("Synthetic auto-start scooter", "000000000000", 0);
                profile.serial = "Q2538ZZ9US7777777"; profile.mac = "C0:FF:EE:00:25:38"; profiles.update(profile);
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service ready");
            service = activity.service(); ble = service.ble;
            connect();
            switchesOffDoNothing();
            autoRecord();
            autoRecordInBackground();
            autoRide();
            settingsRows();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            try { shot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (activity != null) main(() -> { if (service != null && service.isRecording()) service.stopRide(); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission first");
        BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must remain off");
        require(prefs().getAll().isEmpty() && getTargetContext().databaseList().length == 0, "Existing app data refused");
    }

    private void switchesOffDoNothing() {
        require(!prefs().contains(AutoStartPolicy.AUTO_RECORD_KEY) && !prefs().contains(AutoStartPolicy.AUTO_RIDE_MODE_KEY), "Switches absent on a fresh install");
        moving(); settle();
        require(!recording() && !ridingOpen(), "With both switches off, moving does nothing");
    }

    private void autoRecord() {
        enable(AutoStartPolicy.AUTO_RECORD_KEY, true);
        stale(); settle();
        require(!recording(), "A stale speed reading never starts a recording");
        stopped(); settle();
        require(!recording(), "Speed zero does not start a recording");
        moving(); await(this::recording, "Speed above zero starts a recording");
        shot("auto-record-started");
        main(() -> service.stopRide());
        require(!recording(), "Manual stop stops");
        moving(); settle(); moving(); settle();
        require(!recording(), "After a manual stop, still moving does not restart it");
        reconnect(); stopped(); settle();
        require(!recording(), "Reconnect alone (no speed) does not start one");
        moving(); await(this::recording, "Disconnect, reconnect and speed starts one");
        // The automatic 10-minute end: same private stop the policy's tick uses, no hold.
        main(() -> invoke(service, "stopRide", new Class<?>[]{String.class}, RideStore.STOP_AUTO_NO_MOTION));
        require(!recording(), "Automatic end stopped the recording");
        moving(); await(this::recording, "After an automatic end, moving again starts a new recording");
        main(() -> service.stopRide());
    }

    private void autoRecordInBackground() {
        reconnect(); stopped(); settle();
        require(!recording(), "Precondition: not recording, hold cleared");
        goHome();
        require(!resumed(), "Volt is in the background");
        moving(); await(this::recording, "Auto-record starts in the background");
        require(!ridingOpen(), "Auto ride mode stayed off");
        main(() -> service.stopRide());
        returnToVolt();
        enable(AutoStartPolicy.AUTO_RECORD_KEY, false);
    }

    private void autoRide() {
        enable(AutoStartPolicy.AUTO_RIDE_MODE_KEY, true);
        reconnect(); stopped(); settle();
        goHome();
        moving(); settle(); moving(); settle();
        require(!ridingOpen(), "Auto ride mode never opens from the background");
        returnToVolt();
        stale(); settle();
        require(!ridingOpen(), "A stale reading never opens riding mode");
        moving(); await(this::ridingOpen, "Moving with Volt on screen opens riding mode");
        settle(); shot("auto-ride-opened");
        main(() -> invoke(activity, "navigateBack", new Class<?>[0]));
        await(() -> !ridingOpen(), "Back out closes riding mode");
        moving(); settle(); moving(); settle();
        require(!ridingOpen(), "After backing out, still moving does not reopen");
        reconnect(); stopped(); settle();
        require(!ridingOpen(), "Reconnect alone does not open it");
        final AlertDialog[] dialog = {null};
        main(() -> { dialog[0] = new AlertDialog.Builder(activity).setMessage("Synthetic dialog").create(); dialog[0].show(); });
        await(() -> !onMain(() -> activity.hasWindowFocus()), "Dialog took focus");
        moving(); settle(); moving(); settle();
        require(!ridingOpen(), "Riding mode never opens over a dialog");
        main(() -> dialog[0].dismiss());
        await(() -> onMain(() -> activity.hasWindowFocus()), "Focus came back");
        moving(); await(this::ridingOpen, "After the dialog closes, moving opens riding mode");
        main(() -> invoke(activity, "navigateBack", new Class<?>[0]));
        await(() -> !ridingOpen(), "Back out again");
        enable(AutoStartPolicy.AUTO_RIDE_MODE_KEY, false);
    }

    private void settingsRows() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        main(() -> invoke(field(activity, "screen"), "selectSection", new Class<?>[]{String.class}, "App")); settle();
        for (String label : new String[]{"Record rides automatically", "Open automatically"}) {
            final View[] found = {null};
            main(() -> found[0] = find(activity.getWindow().getDecorView(), label));
            require(found[0] != null, label + " row shown");
            main(() -> found[0].requestRectangleOnScreen(new Rect(0, -200, found[0].getWidth(), found[0].getHeight() + 300), true)); settle();
            shot("settings-" + label.split(" ")[0].toLowerCase(Locale.ROOT));
        }
    }

    private void connect() {
        link = new FakeLink(); main(link::attach);
        await(() -> onMain(() -> ble.ready()), "Synthetic link never ready");
    }
    private void reconnect() {
        main(() -> ble.disconnect()); settle();
        require(!onMain(() -> ble.connected()), "Disconnected");
        connect();
    }
    /** A fresh status and motion push, as a riding scooter sends. */
    private void moving() { main(() -> { link.status(); link.motion(1, 18); }); SystemClock.sleep(60); waitForIdleSync(); }
    private void stopped() { main(() -> { link.status(); link.motion(0, 0); }); SystemClock.sleep(60); waitForIdleSync(); }
    /** A moving push whose time is too old to count, then a change for the service to judge it on. */
    private void stale() {
        main(() -> { link.status(); set(ble, "speedRaw", 180); set(ble, "driving", 1); set(ble, "motionMs", SystemClock.elapsedRealtime() - 60000); invoke(ble, "changed", new Class<?>[0]); });
        SystemClock.sleep(60); waitForIdleSync();
    }
    private boolean recording() { return onMain(() -> service.isRecording()); }
    private boolean ridingOpen() { return onMain(() -> field(activity, "riding") != null); }
    private boolean resumed() { return onMain(() -> (Boolean) field(activity, "resumed")); }
    private void enable(String key, boolean on) { prefs().edit().putBoolean(key, on).commit(); }
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(home);
        await(() -> !resumed(), "Volt left the foreground");
        SystemClock.sleep(400);
    }
    private void returnToVolt() {
        Activity back = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        require(back == activity, "Same activity came back");
        await(() -> resumed() && onMain(() -> activity.hasWindowFocus()), "Volt back on screen");
        settle();
    }
    private void shot(String name) throws Exception {
        File folder = new File(getTargetContext().getExternalFilesDir(null), "auto-start-validation"); folder.mkdirs();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        screenshots.add(name);
    }
    private static View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View hit = find(((ViewGroup) view).getChildAt(i), text); if (hit != null) return hit; }
        return null;
    }

    private final class FakeLink {
        final byte[] state = new byte[44];
        final Handler handler = new Handler(Looper.getMainLooper());
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        FakeLink() { state[1] = 3; state[2] = 0; state[3] = 1; state[6] = 2; state[7] = 1; state[19] = 3; state[25] = 50; state[31] = 100; state[33] = 0; state[34] = 3; }
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == args[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "AutoStartFixtureGatt"; }
                if (method.getName().equals("writeCharacteristic")) { set(gatt, "mDeviceBusy", false); handler.postDelayed(() -> callbacks.onCharacteristicWrite(gatt, writer, 0), 10); return 0; }
                Class<?> type = method.getReturnType(); return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            set(gatt, "mClientRegistered", true); set(gatt, "mDeviceBusy", false); set(gatt, "mDeviceBusyLock", new Object()); set(gatt, "mStateLock", new Object()); set(gatt, "mDevice", device); set(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService gattService = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(gattService, "mDevice", device);
            writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier = new BluetoothGattCharacteristic(BleController.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0); gattService.addCharacteristic(writer); gattService.addCharacteristic(notifier);
            callbacks = (BluetoothGattCallback) field(ble, "callbacks"); set(ble, "profile", profile); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", profile.serial);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            status(); motion(0, 0);
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); } }
        void status() { notifyPacket(0x70, 0, state); }
        void motion(int driving, int kmh) { notifyPacket(0x91, 0, new byte[]{0, (byte) driving, (byte) kmh}); }
        void notifyPacket(int cmd, int status, byte[] payload) {
            byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa; packet[3] = (byte) cmd; packet[4] = (byte) (payload.length + 1); packet[5] = (byte) status;
            System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0; for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
            packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
            handler.postDelayed(() -> callbacks.onCharacteristicChanged(gatt, notifier, packet), 10);
        }
    }

    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private void main(Runnable task) { final Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new IllegalStateException(failure[0]); }
    private boolean onMain(BooleanSupplier query) { final boolean[] result = {false}; main(() -> result[0] = query.getAsBoolean()); return result[0]; }
    private void await(BooleanSupplier query, String message) { long end = SystemClock.uptimeMillis() + 8000; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) { checks++; return; } SystemClock.sleep(40); } throw new AssertionError(message); }
    private void settle() { SystemClock.sleep(350); waitForIdleSync(); }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static Object invoke(Object object, String name, Class<?>[] signature, Object... args) { try { Method m = object.getClass().getDeclaredMethod(name, signature); m.setAccessible(true); return m.invoke(object, args); } catch (Exception error) { throw new IllegalStateException(error); } }
}
