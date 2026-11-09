package com.jjricks.volt;

import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.*;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Disposable emulator only. Riding mode's stopped Lock and Record controls on a synthetic scooter (an in-process GATT
 * stand-in, never a radio): shown while stopped, gone while moving with nothing shifting, Lock and unlock through the
 * real tap queue, a lock tap as the scooter starts moving refused, Record starting and stopping, a lock with no readable
 * state, and simulation's refused Record. With -e mode layout, only the stopped/moving screenshots and geometry.
 */
public final class RidingStoppedControlsValidation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private BleController ble;
    private ScooterProfiles.Profile profile;
    private FakeLink link;
    private int checks;
    private String tag;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pump = new Runnable() { @Override public void run() { if (link != null) { link.motion(); link.status(); handler.postDelayed(this, 300); } } };

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
            Context context = getTargetContext();
            require(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Grant the emulator Nearby permission first");
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
            require(adapter != null && !adapter.isEnabled(), "Bluetooth must stay off");
            String frame = args.getString("frame", "focus"), mode = args.getString("mode", "full");
            tag = args.getString("variant", "default") + "-" + frame;
            seed(frame);
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "The service did not start");
            waitForIdleSync();
            main(() -> {
                activity.service().setSimulation(false);
                ble = activity.service().ble;
                link = new FakeLink(); link.attach();
                handler.post(pump);
                ble.visibility(true, false);
            });
            await(() -> onMain(() -> ble.ready()), "The synthetic scooter is not ready");
            main(() -> activity.openRiding());
            settle();
            requireRiding();
            await(() -> onMain(() -> field(screen(), "stoppedShown").equals(Boolean.TRUE)), "The stopped controls did not show");
            settle();
            geometry(frame);
            if (mode.equals("full")) { lockAndUnlock(); moveDuringTap(); record(); noLockField(); simulation(); }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", String.valueOf(error));
            error.printStackTrace();
        } finally {
            link = null;
            handler.removeCallbacks(pump);
            result.putInt("assertions", checks);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void seed(String frame) {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            profile = profiles.create("Synthetic riding scooter", "0F1E2D3C4B5A", 0);
            profile.serial = "Q2538ZZ9US7777777"; profile.mac = "C0:FF:EE:00:25:38"; profiles.update(profile);
            SharedPreferences settings = getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            settings.edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("phone_gps", false).commit();
            try {
                Class<?> layouts = Class.forName("com.jjricks.volt.RidingLayout"), frames = Class.forName("com.jjricks.volt.RidingFrames");
                Class<?> config = Class.forName("com.jjricks.volt.RidingConfig");
                Constructor<?> make = config.getDeclaredConstructor(SharedPreferences.class, String.class); make.setAccessible(true);
                Object cfg = make.newInstance(settings, profile.id);
                Method m = config.getDeclaredMethod("layout"); m.setAccessible(true);
                Object layout = m.invoke(cfg);
                Method choose = frames.getDeclaredMethod("choose", layouts, String.class); choose.setAccessible(true);
                choose.invoke(null, layout, frame);
                Method save = config.getDeclaredMethod("save", layouts); save.setAccessible(true);
                save.invoke(cfg, layout);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }

    private void geometry(String frame) throws Exception {
        require(view("stoppedControls").getVisibility() == View.VISIBLE, "Stopped: controls hidden");
        require(view("power").getVisibility() == View.INVISIBLE, "Stopped: the power readout still shows");
        float density = activity.getResources().getDisplayMetrics().density;
        View controls = view("stoppedControls"), lock = lock(), record = view("recordDisc");
        requireSize(lock, record, density);
        // The controls stay inside the slot they share with the power readout.
        View slot = (View) controls.getParent();
        require(controls.getTop() >= 0 && controls.getBottom() <= slot.getHeight() && controls.getLeft() >= 0 && controls.getRight() <= slot.getWidth(),
                frame + ": the controls overflow their slot (" + controls.getHeight() + " in " + slot.getHeight() + ")");
        require(lock.getWidth() >= Math.round(96 * density), frame + ": lock too narrow (" + lock.getWidth() + "px)");
        int[] speedBefore = position(view("big")), barBefore = position(view("bar"));
        screenshot("stopped");
        moving(true);
        await(() -> onMain(() -> view("stoppedControls").getVisibility() != View.VISIBLE), "Moving: the controls stayed");
        settle();
        require(view("power").getVisibility() == View.VISIBLE, "Moving: the power readout did not return");
        require(Arrays.equals(speedBefore, position(view("big"))) && Arrays.equals(barBefore, position(view("bar"))), frame + ": gauges moved when the controls hid");
        screenshot("moving");
        moving(false);
        await(() -> onMain(() -> view("stoppedControls").getVisibility() == View.VISIBLE), "Stopped again: no controls");
        settle();
        require(Arrays.equals(speedBefore, position(view("big"))), frame + ": gauges moved when the controls showed");
    }

    private void requireSize(View lock, View record, float density) {
        int size = Math.round(56 * density);
        require(lock.getHeight() == size && record.getHeight() == size && record.getWidth() == size,
                "The controls are not 56 dp (" + lock.getHeight() + ", " + record.getHeight() + ", " + record.getWidth() + ")");
    }

    private void lockAndUnlock() throws Exception {
        require(ble.locked() != null && !ble.locked(), "Fixture starts unlocked");
        main(() -> lock().performClick());
        await(() -> link.lockWrites.size() == 1, "The lock tap wrote nothing");
        await(() -> onMain(() -> Boolean.TRUE.equals(ble.locked())), "The scooter did not read back locked");
        settle();
        screenshot("locked");
        main(() -> lock().performClick());
        await(() -> link.lockWrites.size() == 2, "The unlock tap wrote nothing");
        await(() -> onMain(() -> Boolean.FALSE.equals(ble.locked())), "The scooter did not read back unlocked");
        settle();
        screenshot("unlocked");
    }

    /** The tap beats the gate: the scooter is already moving when the switch is tapped. */
    private void moveDuringTap() throws Exception {
        int before = link.lockWrites.size();
        main(() -> { link.speedRaw = 90; link.driving = 2; link.motion(); });
        await(() -> onMain(() -> !ble.stationary()), "The scooter did not start moving");
        main(() -> { lock().performClick(); view("recordDisc").performClick(); });
        settle();
        require(link.lockWrites.size() == before, "A lock tap went out while moving");
        require(!onMain(() -> activity.service().isRecording()), "Record started while moving");
        moving(false);
        await(() -> onMain(() -> view("stoppedControls").getVisibility() == View.VISIBLE), "Stopped again: no controls");
    }

    private void record() throws Exception {
        View disc = view("recordDisc");
        require(onMain(disc::isEnabled), "Record disabled while connected");
        main(disc::performClick);
        await(() -> onMain(() -> activity.service().isRecording()), "Record did not start");
        settle();
        require(String.valueOf(onValue(disc::getContentDescription)).length() > 0, "Record has no description");
        screenshot("recording");
        main(disc::performClick);
        await(() -> onMain(() -> !activity.service().isRecording()), "Record did not stop");
        settle();
        screenshot("recording-stopped");
    }

    private void noLockField() throws Exception {
        main(() -> link.state[2] = (byte) 9);
        await(() -> onMain(() -> ble.locked() == null), "The lock field still reads");
        settle();
        screenshot("lock-unavailable");
        main(() -> require(!lock().isEnabled(), "The lock stayed enabled with no state"));
        main(() -> link.state[2] = 0);
        await(() -> onMain(() -> ble.locked() != null), "The lock field did not return");
    }

    private void simulation() throws Exception {
        main(() -> { link = null; require(activity.service().setSimulation(true), "Simulation did not start"); });
        settle(); settle();
        await(() -> onMain(() -> ble.ready()), "The simulator did not connect");
        main(() -> activity.redraw());
        settle();
        screenshot("simulation");
        main(() -> view("recordDisc").performClick());
        settle();
        require(!onMain(() -> activity.service().isRecording()), "Record started on the simulator");
        main(() -> activity.service().setSimulation(false));
    }

    private void moving(boolean on) { main(() -> { link.speedRaw = on ? 120 : 0; link.driving = on ? 2 : 0; link.motion(); activity.redraw(); }); }
    private void requireRiding() { main(() -> require("RidingScreen".equals(screen().getClass().getSimpleName()), "Riding mode is not open")); }
    private Object screen() { return field(activity, "screen"); }
    private View view(String name) { return (View) field(screen(), name); }
    private View lock() { return (View) field(screen(), "stoppedLock"); }
    private int[] position(View v) { int[] xy = new int[2]; final View target = v; main(() -> target.getLocationOnScreen(xy)); return xy; }

    private void screenshot(String name) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "riding-stopped");
        dir.mkdirs();
        SystemClock.sleep(150);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot failed");
        try (FileOutputStream out = new FileOutputStream(new File(dir, tag + "-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 90, out); }
    }

    private final class FakeLink {
        final byte[] state = new byte[44];
        final List<byte[]> lockWrites = new CopyOnWriteArrayList<>();
        volatile int speedRaw, driving;
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        FakeLink() { state[1] = 3; state[2] = 0; state[3] = 1; state[6] = 2; state[7] = 1; state[19] = 3; state[25] = 50; state[31] = 100; state[33] = 0; state[34] = 3; }
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, a) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == a[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "RidingFixtureGatt"; }
                if (method.getName().equals("writeCharacteristic")) {
                    byte[] frame = null; if (a != null) for (Object arg : a) if (arg instanceof byte[]) frame = ((byte[]) arg).clone();
                    set(gatt, "mDeviceBusy", false); final byte[] packet = frame;
                    if (packet != null) handler.postDelayed(() -> received(packet), 10);
                    return 0;
                }
                Class<?> type = method.getReturnType();
                return type == int.class ? 0 : type == boolean.class ? false : type == long.class ? 0L : null;
            };
            set(gatt, "mService", Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, transport));
            set(gatt, "mClientRegistered", true); set(gatt, "mDeviceBusy", false); set(gatt, "mDeviceBusyLock", new Object()); set(gatt, "mStateLock", new Object()); set(gatt, "mDevice", device); set(gatt, "mServices", new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService service = new BluetoothGattService(BleController.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY); set(service, "mDevice", device);
            writer = new BluetoothGattCharacteristic(BleController.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier = new BluetoothGattCharacteristic(BleController.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0); service.addCharacteristic(writer); service.addCharacteristic(notifier);
            callbacks = (BluetoothGattCallback) field(ble, "callbacks"); set(ble, "profile", profile); set(ble, "gatt", gatt); set(ble, "writer", writer); set(ble, "serial", profile.serial);
            set(ble, "authenticated", true); set(ble, "discovered", true); set(ble, "subscribed", true); set(ble, "wanted", false); set(ble, "state", BleController.State.READY);
            status(); motion();
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); } }
        void received(byte[] frame) {
            callbacks.onCharacteristicWrite(gatt, writer, 0);
            if (frame.length > 7 && (frame[3] & 255) == 0x51) { lockWrites.add(frame); state[2] = frame[6]; notifyPacket(0x51, 0, new byte[]{frame[6]}); status(); }
        }
        void status() { notifyPacket(0x70, 0, state); }
        void motion() { notifyPacket(0x91, 0, new byte[]{0, (byte) driving, (byte) (speedRaw / 10)}); }
        void notifyPacket(int cmd, int status, byte[] payload) {
            byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa; packet[3] = (byte) cmd; packet[4] = (byte) (payload.length + 1); packet[5] = (byte) status;
            System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0; for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
            packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
            handler.postDelayed(() -> { if (callbacks != null && link != null) callbacks.onCharacteristicChanged(gatt, notifier, packet); }, 10);
        }
    }

    private void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private void main(Runnable task) { final Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new IllegalStateException(failure[0]); }
    private boolean onMain(BooleanSupplier query) { final boolean[] result = {false}; main(() -> result[0] = query.getAsBoolean()); return result[0]; }
    private Object onValue(java.util.function.Supplier<?> query) { final Object[] result = {null}; main(() -> result[0] = query.get()); return result[0]; }
    private void await(BooleanSupplier query, String message) { long end = SystemClock.uptimeMillis() + 8000; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) return; SystemClock.sleep(25); } throw new AssertionError(message); }
    private void settle() { SystemClock.sleep(450); waitForIdleSync(); }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
}
