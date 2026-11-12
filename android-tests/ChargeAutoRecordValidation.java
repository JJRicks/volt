package com.jjricks.volt;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.tests.TextCensus;

/**
 * Disposable emulator only: Record charges automatically (ChargeAutoRecordPolicy, off by default) on the real service,
 * tick and poll cadence, against an in-process GATT stand-in with a synthesized scooter (modelled on
 * ChargeLiveUnplugValidation). No radio, no phone, nothing sent to a scooter but reads. A run takes about seven minutes.
 *
 * The emulator's Bluetooth adapter is turned on for the run (a charge recording ends at once with Bluetooth off) and
 * back off at the end, as the sweep keeps it.
 *
 * Default mode: off by default and nothing starts; on, a charge starts a recording on screen and out of sight (the
 * background read only: no pushed report); the label comes from the chosen charger, else a guess; a short charge is
 * not kept and the recording ends two minutes after charging stops; moving above 4.8 km/h ends it at once and
 * Record rides automatically starts a ride in that moment, while jostling below it doesn't; a hand stop (on the page
 * or from the notification) holds until the scooter stops charging; an automatic recording never deletes a manual one.
 *
 * Mode "page" (needs the switch row): Battery & charging › Charge history & recordings with the switch, screenshots
 * and a text check for cut or mid-word-broken text; the sweep runs it at 344 dp and 725 dp, both themes, large text.
 */
public final class ChargeAutoRecordValidation extends Instrumentation {
    private static final long S = 1000L;
    private static final byte OFF = 0, ON = 1;
    private static final String KEY = ChargeAutoRecordPolicy.KEY, HOLD = ChargeAutoRecordPolicy.HOLD_KEY;
    private MainActivity activity;
    private ScooterService service;
    private BleController ble;
    private ScooterProfiles.Profile profile;
    private FakeLink link;
    private int checks;
    private final StringBuilder timeline = new StringBuilder();
    private final ArrayList<String> screenshots = new ArrayList<>();
    private Bundle args = new Bundle();
    private boolean bluetoothTurnedOn;

    @Override public void onCreate(Bundle arguments) { if (arguments != null) args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean page = "page".equals(args.getString("mode", ""));
        try {
            admit();
            if (!page) bluetoothOn();
            main(() -> {
                SharedPreferences.Editor edit = settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", true);
                if (page) edit.putString("theme", args.getString("theme", "dark")).putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1")));
                edit.commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext()); profile = profiles.create("Synthetic auto charge fixture", "000000000000", 0);
                profile.serial = "Q2538ZZ9US7777777"; profile.mac = "C0:FF:EE:00:25:38"; profiles.update(profile); profiles.select(profile.id);
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, 10000, "Service missing"); service = activity.service(); ble = service.ble; waitForIdleSync();
            if (page) pageShots();
            else {
                main(() -> { link = new FakeLink(); link.attach(); });
                await(() -> onMain(() -> ble.ready()), 10000, "Synthetic link never ready");
                log("link ready, not charging");
                offByDefault();
                onScreenChosenLabelAndShortChargeNotKept();
                motionEndsAndRideStarts();
                handStopHolds();
                outOfSightStartAndNotificationStop();
                manualNeverDeleted();
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
            try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            try { if (activity != null) main(() -> { if (service != null) { if (service.isChargeRecording()) service.stopChargeRecording(); if (service.isRecording()) service.stopRide(); } }); } catch (Throwable ignored) {}
            if (bluetoothTurnedOn) try { shell("cmd bluetooth_manager disable"); } catch (Throwable ignored) {}
            result.putInt("assertions", checks); result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            result.putString("timeline", timeline.toString()); result.putStringArrayList("screenshots", screenshots);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator permissions first: " + permission);
        require(getTargetContext().getSystemService(BluetoothManager.class).getAdapter() != null, "The emulator has no Bluetooth adapter object");
        require(settings().getAll().isEmpty(), "Refusing existing app settings");
    }
    /** ChargeRecoveryPolicy ends a recording at once with Bluetooth off, which is how the sweep leaves the emulator. */
    private void bluetoothOn() throws Exception {
        BluetoothAdapter adapter = getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        if (!adapter.isEnabled()) { shell("cmd bluetooth_manager enable"); bluetoothTurnedOn = true; }
        await(adapter::isEnabled, 30000, "Bluetooth did not turn on");
        log("emulator Bluetooth on for the run");
    }
    private void shell(String command) throws IOException {
        try (ParcelFileDescriptor out = getUiAutomation().executeShellCommand(command); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            byte[] buffer = new byte[1024]; while (in.read(buffer) >= 0) { /* wait for the command to finish */ }
        }
    }

    // ===== Scenarios =====

    private void offByDefault() {
        require(!settings().contains(KEY), "The switch has a stored value before anyone set it");
        main(() -> { link.charging(ON); link.home(); });
        SystemClock.sleep(8 * S);
        require(onMain(() -> !service.isChargeRecording()) && saved().length == 0, "A charge started a recording with the switch off");
        log("off by default: charging for 8 s, nothing recorded");
        unplugAndSettle();
    }

    /** On screen, the chosen charger labels it; unplugged, it ends after two minutes (not 30), and under 10 minutes it isn't kept. */
    private void onScreenChosenLabelAndShortChargeNotKept() {
        main(() -> { require(service.setChargeCharger(ChargeSessionStore.Charger.HIGH_SPEED), "Could not choose the charger"); settings().edit().putBoolean(KEY, true).commit(); });
        plugIn();
        long started = awaitRecording(10 * S, "on screen");
        require(onMain(() -> chargeStore().automatic() && chargeStore().labelSource() == ChargeSessionStore.LabelSource.CHOSEN
                && chargeStore().charger() == ChargeSessionStore.Charger.HIGH_SPEED), "Expected the chosen high-speed label");
        log("on screen: started " + (started) + " ms after plugging in, label chosen high-speed; notice: " + notice());
        require(notice().contains("Synthetic") || notice().length() > 0, "No connection notice while recording");
        SystemClock.sleep(15 * S);
        long unplugged = SystemClock.elapsedRealtime();
        main(() -> { link.charging(OFF); link.home(); });
        log("unplugged");
        SystemClock.sleep(60 * S);
        require(onMain(() -> service.isChargeRecording()), "The recording ended within a minute of unplugging, before the two-minute wait");
        await(() -> onMain(() -> !service.isChargeRecording()), ChargeAutoRecordPolicy.CHARGE_OFF_END_MS + 30 * S, "The automatic recording did not end two minutes after unplugging");
        long ended = SystemClock.elapsedRealtime() - unplugged;
        require(ended >= ChargeAutoRecordPolicy.CHARGE_OFF_END_MS - 3 * S, "Ended too early: " + ended + " ms");
        log("ended " + ended + " ms after unplugging; status: " + onMainText(() -> service.chargeCaptureStatus()));
        require(saved().length == 0, "A recording under 10 minutes was kept: " + Arrays.toString(saved()));
        require(!holds(), "A recording that ended by itself set a hold");
    }

    /** Jostling below 4.8 km/h keeps it; above, it ends at once and Record rides automatically starts a ride in that moment. */
    private void motionEndsAndRideStarts() {
        main(() -> settings().edit().putBoolean("auto_record_rides", true).commit());
        plugIn();
        awaitRecording(10 * S, "before motion");
        for (int i = 0; i < 6; i++) { main(() -> link.motion(3)); SystemClock.sleep(S); }
        require(onMain(() -> service.isChargeRecording() && !service.isRecording()), "Jostling at 3 km/h ended the charge recording or started a ride");
        log("jostling at 3 km/h for 6 s: still recording the charge");
        long moved = SystemClock.elapsedRealtime();
        main(() -> link.motion(6));
        await(() -> onMain(() -> !service.isChargeRecording()), 3 * S, "6 km/h did not end the automatic recording");
        long ended = SystemClock.elapsedRealtime() - moved;
        await(() -> onMain(() -> service.isRecording()), 3 * S, "Record rides automatically did not start a ride when the charge recording ended");
        long ride = SystemClock.elapsedRealtime() - moved;
        log("6 km/h: charge recording ended within " + ended + " ms, ride recording running within " + ride + " ms");
        require(!holds(), "Moving away set a hold");
        main(() -> { link.motion(0); link.charging(OFF); link.home(); });
        SystemClock.sleep(3 * S);
        main(() -> { service.stopRide(); settings().edit().putBoolean("auto_record_rides", false).commit(); });
        require(saved().length == 0, "The short recording was kept");
        unplugAndSettle();
    }

    /** A hand stop holds until the scooter stops charging; then plugging in starts again. */
    private void handStopHolds() {
        plugIn();
        awaitRecording(10 * S, "before the hand stop");
        main(() -> service.stopChargeRecording());
        require(holds(), "A hand stop did not store a hold");
        SystemClock.sleep(10 * S);
        require(onMain(() -> !service.isChargeRecording()), "A recording started again while held, still charging");
        log("hand stop: held for 10 s while still charging");
        main(() -> { link.charging(OFF); link.home(); });
        await(() -> !holds(), 10 * S, "A fresh not-charging reading did not end the hold");
        log("unplugged: hold cleared");
        plugIn();
        awaitRecording(10 * S, "after the hold cleared");
        log("plugged in again: recording started");
        main(() -> service.stopChargeRecording());
        unplugAndSettle();
    }

    /**
     * Out of sight, no pushed report: only the 15 s background read sees charging. No chosen charger: a guessed label.
     * The notification's Stop (the service action) holds, as the page's does.
     */
    private void outOfSightStartAndNotificationStop() {
        main(() -> require(service.setChargeCharger(null), "Could not set Automatic"));
        ensureFront();
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> !service.isVisible()), 10000, "Volt never left the foreground");
        SystemClock.sleep(3 * S);
        main(() -> link.charging(ON));
        log("out of sight: charging, no report pushed");
        long plugged = SystemClock.elapsedRealtime();
        await(() -> onMain(() -> service.isChargeRecording()), StatusCadence.POLL_SLOW_MS + 10 * S, "No automatic recording out of sight");
        require(onMain(() -> !service.isVisible()), "Volt came to the front");
        require(onMain(() -> chargeStore().automatic() && chargeStore().labelSource() != ChargeSessionStore.LabelSource.CHOSEN
                && chargeStore().charger() == ChargeSessionStore.Charger.STOCK), "Expected a stock label not marked as chosen");
        log("out of sight: started " + (SystemClock.elapsedRealtime() - plugged) + " ms after plugging in, label " + onMainText(() -> String.valueOf(chargeStore().labelSource())) + "; notice: " + notice());
        SystemClock.sleep(5 * S);
        getTargetContext().startForegroundService(new Intent(getTargetContext(), ScooterService.class).setAction("STOP_CHARGE_RECORDING"));
        await(() -> onMain(() -> !service.isChargeRecording()), 10 * S, "The notification's Stop did not stop it");
        require(holds(), "The notification's Stop did not hold");
        log("notification Stop: stopped and held; notice: " + notice());
        main(() -> link.charging(OFF));
        await(() -> !holds(), StatusCadence.POLL_SLOW_MS + 10 * S, "The background read's not-charging reading did not end the hold");
        log("out of sight unplugged: hold cleared");
        ensureFront();
    }

    /** All 20 saved recordings manual: no automatic recording. One automatic among them: only it makes room. */
    private void manualNeverDeleted() throws Exception {
        unplugAndSettle();
        File folder = folder();
        for (File file : saved()) require(file.delete(), "Could not clear " + file.getName());
        ChargeSessionStore.AutomaticRules keepAll = new ChargeSessionStore.AutomaticRules() {
            public boolean keep(long ranMs, double gainedPoints) { return true; }
            public int toRemove(int saved, int automatic, int max) { return ChargeAutoRecordPolicy.automaticToRemove(saved, automatic, max); }
        };
        ChargeSessionStore fixtures = new ChargeSessionStore(folder, keepAll);
        for (int i = 0; i < ChargeSessionStore.MAX_SESSIONS; i++) fixture(fixtures, i, false);
        Set<String> manual = names();
        require(manual.size() == ChargeSessionStore.MAX_SESSIONS, "Expected 20 manual fixtures");
        plugIn();
        SystemClock.sleep(8 * S);
        require(onMain(() -> !service.isChargeRecording()) && names().equals(manual), "An automatic recording started or a manual one was deleted with all 20 manual");
        log("20 manual saved: no automatic recording, all kept");
        main(() -> { link.charging(OFF); link.home(); });
        SystemClock.sleep(4 * S);
        File oldest = saved()[0];
        require(oldest.delete(), "Could not remove a fixture");
        String automatic = fixture(fixtures, 99, true);
        Set<String> before = names();
        plugIn();
        awaitRecording(10 * S, "with one automatic among 19 manual");
        Set<String> after = names();
        require(!after.contains(automatic), "The automatic fixture was not the one replaced");
        Set<String> manualLeft = new HashSet<>(before); manualLeft.remove(automatic);
        require(after.containsAll(manualLeft), "A manual recording was deleted for an automatic one");
        log("19 manual + 1 automatic: only the automatic one made room");
        main(() -> service.stopChargeRecording());
        unplugAndSettle();
        for (File file : saved()) file.delete();
    }

    private String fixture(ChargeSessionStore store, int index, boolean automatic) throws IOException {
        long wall = System.currentTimeMillis() - (100 - index) * 3_600_000L, mono = 1_000_000L + index;
        if (automatic) store.startAutomatic(profile.id, ChargeSessionStore.Charger.STOCK, ChargeSessionStore.LabelSource.GUESS, wall, mono);
        else store.start(profile.id, ChargeSessionStore.Charger.STOCK, wall, mono);
        ChargeSessionStore.Value one = new ChargeSessionStore.Value(1d, mono, 0x72);
        store.sample(new ChargeSessionStore.Frame(wall, mono, one, new ChargeSessionStore.Value(50d, mono, 0x72), new ChargeSessionStore.Value(52d, mono, 0x72),
                new ChargeSessionStore.Value(-2d, mono, 0x72), null));
        Set<String> before = names();
        store.stop(ChargeSessionStore.Stop.STOPPED);
        SystemClock.sleep(20); // distinct modification times, oldest first
        for (String name : names()) if (!before.contains(name)) return name;
        for (String name : names()) if (ChargeSessionStore.labelSource(name) != null == automatic && name.endsWith(".csv")) return name;
        throw new IllegalStateException("Fixture missing");
    }

    // ===== Page =====

    /** The switch beside the recording controls; screenshots of the row and its note, and no cut or mid-word-broken text. */
    private void pageShots() throws Exception {
        main(() -> { invoke(activity, "select", new Class<?>[]{String.class}, HubPages.HOME); for (String step : HubPages.path(HubPages.BATTERY_HISTORY)) if (!step.equals(HubPages.HOME)) activity.open(step); });
        settle();
        View row = onMainView(() -> activity.findViewById(android.R.id.content).getRootView().findViewWithTag(KEY));
        require(row != null, "No Record charges automatically switch on Battery & charging › Charge history & recordings");
        main(() -> reveal(row)); settle(); SystemClock.sleep(600);
        String size = getTargetContext().getResources().getConfiguration().screenWidthDp + "dp-" + args.getString("theme", "dark") + "-" + args.getString("text_scale", "1");
        screenshot("charge-auto-record-off-" + size);
        main(() -> textProblems(row));
        main(row::performClick); settle();
        require(settings().getBoolean(KEY, false), "Tapping the switch did not turn it on");
        SystemClock.sleep(400); screenshot("charge-auto-record-on-" + size);
        main(() -> textProblems(row));
        View capture = onMainView(() -> activity.findViewById(android.R.id.content).getRootView().findViewWithTag("charge-capture-action"));
        require(capture != null, "The recording controls are missing");
        main(() -> require(row.getParent() == capture.getParent(), "The switch is not beside the recording controls"));
        log("page " + size + ": switch shown, on and off, text whole");
    }
    /** Every visible text in the row: not ellipsized, and each line break falls between words. */
    private void textProblems(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view; Layout layout = text.getLayout(); CharSequence value = text.getText();
            if (layout != null && value.length() > 0) for (int line = 0; line < layout.getLineCount(); line++) {
                require(layout.getEllipsisCount(line) == 0, "Cut text: " + value);
                int end = layout.getLineEnd(line);
                if (line < layout.getLineCount() - 1 && end > 0 && end < value.length())
                    require(!(Character.isLetterOrDigit(value.charAt(end - 1)) && Character.isLetterOrDigit(value.charAt(end))), "Mid-word break in: " + value);
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) textProblems(((ViewGroup) view).getChildAt(i));
    }
    private void reveal(View view) {
        View parent = view; while (parent != null && !(parent instanceof android.widget.ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        if (parent == null) return; android.graphics.Rect r = new android.graphics.Rect(); view.getDrawingRect(r); ((ViewGroup) parent).offsetDescendantRectToMyCoords(view, r); parent.scrollTo(0, Math.max(0, r.top - parent.getHeight() / 3));
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charge-auto-record-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, label + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.writeWithNotifications(this, file);
    }

    // ===== Helpers =====

    private void plugIn() { main(() -> { link.charging(ON); link.home(); }); }
    /** Charging off, a fresh reading of it, no recording running and no hold. */
    private void unplugAndSettle() {
        main(() -> { link.charging(OFF); link.home(); });
        await(() -> onMain(() -> Double.valueOf(0d).equals(ble.telemetry.number("charging_state"))), 10 * S, "No not-charging reading");
        SystemClock.sleep(3 * S);
        main(() -> { if (service.isChargeRecording()) service.stopChargeRecording(); });
        main(() -> link.home()); SystemClock.sleep(3 * S);
        require(!holds() && onMain(() -> !service.isChargeRecording()), "Could not settle: still recording or held");
    }
    private long awaitRecording(long ms, String what) {
        long from = SystemClock.elapsedRealtime();
        await(() -> onMain(() -> service.isChargeRecording()), ms, "No automatic recording " + what);
        require(onMain(() -> chargeStore().automatic()), "The recording " + what + " is not marked automatic");
        return SystemClock.elapsedRealtime() - from;
    }
    private void ensureFront() {
        if (onMain(() -> service.isVisible())) return;
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        await(() -> onMain(() -> service.isVisible()), 10000, "Volt never returned to the foreground");
        settle();
    }
    private ChargeSessionStore chargeStore() { return (ChargeSessionStore) field(service, "chargeStore"); }
    private boolean holds() { return ChargeAutoRecordPolicy.holds(settings().getString(HOLD, "")).contains(profile.id); }
    private File folder() { return new File(getTargetContext().getFilesDir(), "charge-recordings"); }
    private File[] saved() {
        File[] files = folder().listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName)); return files;
    }
    private Set<String> names() { Set<String> names = new TreeSet<>(); File[] files = folder().listFiles(); if (files != null) for (File f : files) names.add(f.getName()); return names; }
    private void log(String line) { timeline.append(SystemClock.elapsedRealtime() / 1000).append("s ").append(line).append('\n'); Bundle status = new Bundle(); status.putString("progress", line); sendStatus(0, status); }
    private StatusBarNotification connection() { for (StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == 71) return n; return null; }
    private String notice() { StatusBarNotification n = connection(); if (n == null) return "(no connection notice)"; Bundle extras = n.getNotification().extras; return extras.getCharSequence(Notification.EXTRA_TITLE) + " | " + extras.getCharSequence(Notification.EXTRA_TEXT); }

    private final class FakeLink {
        byte charging = OFF;
        final Handler handler = new Handler(Looper.getMainLooper());
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        void charging(byte value) { charging = value; }
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, a) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == a[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "AutoChargeFixtureGatt"; }
                if (method.getName().equals("writeCharacteristic")) {
                    byte[] frame = null; if (a != null) for (Object arg : a) if (arg instanceof byte[]) frame = ((byte[]) arg).clone();
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
        void home() { byte[] data = new byte[12]; data[1] = 1; data[2] = 62; data[4] = charging; packet(0x90, data); }
        /** 62%, 52.000 V and 2.000 A charging (sign bit set) or 0 A, little-endian, as Telemetry.electrical reads them. */
        void battery() {
            byte[] data = new byte[24]; data[1] = 62; data[12] = charging;
            int volts = 52000, amps = charging == ON ? 2000 : 0;
            for (int i = 0; i < 4; i++) { data[2 + i] = (byte) (volts >>> (8 * i)); data[6 + i] = (byte) (amps >>> (8 * i)); }
            if (charging == ON) data[9] |= (byte) 0x80;
            packet(0x72, data);
        }
        /** A v0 ride report: battery, driving status, whole km/h. */
        void motion(int kmh) { packet(0x91, new byte[]{62, (byte) (kmh > 0 ? 1 : 0), (byte) kmh}); }
        void packet(int cmd, byte[] payload) {
            byte[] packet = new byte[payload.length + 9]; packet[0] = 0x55; packet[1] = (byte) 0xaa; packet[3] = (byte) cmd; packet[4] = (byte) (payload.length + 1);
            System.arraycopy(payload, 0, packet, 6, payload.length); int sum = 0; for (int i = 0; i < packet.length - 3; i++) sum += packet[i] & 255;
            packet[packet.length - 3] = (byte) sum; packet[packet.length - 2] = (byte) 0xfe; packet[packet.length - 1] = (byte) 0xfd;
            handler.postDelayed(() -> callbacks.onCharacteristicChanged(gatt, notifier, packet), 10);
        }
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private void main(Runnable task) { final Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new IllegalStateException(failure[0]); }
    private boolean onMain(BooleanSupplier query) { final boolean[] result = {false}; main(() -> result[0] = query.getAsBoolean()); return result[0]; }
    private String onMainText(java.util.function.Supplier<String> query) { final String[] result = {null}; main(() -> result[0] = query.get()); return result[0]; }
    private View onMainView(java.util.function.Supplier<View> query) { final View[] result = {null}; main(() -> result[0] = query.get()); return result[0]; }
    private void await(BooleanSupplier query, long ms, String message) { long end = SystemClock.uptimeMillis() + ms; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) return; SystemClock.sleep(50); } throw new AssertionError(message); }
    private void settle() { SystemClock.sleep(350); waitForIdleSync(); }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values); }
        catch (InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
