package com.jjricks.volt;

import com.jjricks.volt.tests.ValidationInstrumentation;
import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Disposable Android 16 (API 36) emulator only: Volt's connection notice as a Live Update, through the real service and
 * its real notice builders (no simulator: the simulator posts no connection notice). A recording ride is started in the
 * service's own RideStore, as RideAutoStopValidation does, and the scooter is an in-process GATT stand-in with a
 * synthesized charging scooter, as in ChargeLiveUnplugValidation. No radio, no phone, nothing sent to a scooter.
 *
 * <ul>
 * <li>A recording ride out of sight: notice 71 asks to be promoted, Android sets FLAG_PROMOTED_ONGOING, its chip is the
 *     ride's chronometer (no short text) and the notice isn't reposted while only the clock moves. The Live Update status
 *     row reports it. Swiped away in the shade (a swipe across the card, as a person's; Android 16 lets even a promoted
 *     foreground notice go), its delete intent reaches the service and the notice stays away for the rest of the ride, through a change that
 *     would otherwise repost it; ending the ride posts the next notice.</li>
 * <li>The live charging card out of sight: promoted, its chip the level by default; Finish time shows the finish time
 *     the card's text shows, or the level while the card has none; back to Level.</li>
 * <li>Throughout, the foreground service keeps its notice and type, and nothing sounds, vibrates or pops up
 *     (dumpsys notification's own record of the notice).</li>
 * </ul>
 *
 * Argument hold (seconds, default 0): render mode, pausing at each stage (status "hold:ride", "hold:charge-level",
 * "hold:charge-finish") so the runner can capture the status bar, shade and lock screen.
 */
public final class LiveUpdatesValidation extends ValidationInstrumentation {
    private static final long S = 1000L;
    private static final byte OFF = 0, ON = 1;
    /** A UT5 MAX serial (as ChargeEstimateValidation's), so the card gets a finish time once the plug-in hold ends. */
    private static final String SERIAL = "X2538-SYNTHETIC";
    /** Notification.EXTRA_SHORT_CRITICAL_TEXT, newer than the SDK the validations compile against. */
    private static final String SHORT_CRITICAL_TEXT = "android.shortCriticalText";
    private MainActivity activity;
    private ScooterService service;
    private BleController ble;
    private ScooterProfiles.Profile profile;
    private FakeLink link;
    private int checks;
    private long hold;
    private final StringBuilder timeline = new StringBuilder();

    @Override public void onCreate(Bundle args) {
        hold = args == null ? 0 : Long.parseLong(args.getString("hold", "0")) * S;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", true).commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                profile = profiles.create("Synthetic Live Update scooter", "000000000000", 0);
                profile.serial = SERIAL; profile.mac = "C0:FF:EE:00:20:06"; profiles.update(profile); profiles.select(profile.id);
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, 10000, "Service missing");
            service = activity.service(); ble = service.ble; waitForIdleSync();
            main(() -> { link = new FakeLink(); link.attach(); });
            await(() -> onMain(() -> ble.ready()), 10000, "Synthetic link never ready");
            log("link ready, not charging");
            rideLiveUpdate();
            chargeChip();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            try { shell("cmd statusbar collapse"); } catch (Throwable ignored) {}
            result.putInt("assertions", checks); result.putBoolean("physical_device_tested", false); result.putString("timeline", timeline.toString());
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Refuses before touching anything: a physical phone, an Android without Live Updates, missing permissions, an app with data. */
    private void admit() {
        Context context = getTargetContext();
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Physical device refused");
        require(Build.VERSION.SDK_INT >= ChargeLiveUpdate.SDK, "Needs Android 16 (API 36) or later for Live Updates");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS})
            require(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant " + permission + " first");
        for (String name : new String[]{"app_settings", "profiles", "scooter"}) require(context.getSharedPreferences(name, 0).getAll().isEmpty(), "Requires an app without preferences (" + name + ")");
        String[] files = context.getFilesDir().list();
        require((files == null || files.length == 0) && context.databaseList().length == 0, "Requires an app without files or databases");
        require(context.getSystemService(NotificationManager.class).canPostPromotedNotifications(), "Live updates are off for Volt in Android's settings");
        require(context.getSystemService(BluetoothManager.class).getAdapter() != null, "The emulator has no Bluetooth adapter object");
    }

    // ---- The ride -----------------------------------------------------------------------------------------

    private void rideLiveUpdate() throws Exception {
        ensureFront();
        long startWall = startRide("Synthetic Live Update ride");
        leave();
        await(() -> rideNotice() && promoted(), 20 * S, "The ride's notice was not promoted out of sight: " + describe());
        StatusBarNotification posted = connection(); Notification n = posted.getNotification();
        log("ride: " + describe());
        require((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0, "The ride's notice is the ongoing foreground notice");
        require(n.hasPromotableCharacteristics(), "Android reads the ride's notice as promotable");
        require(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.when == startWall, "The chip is the ride clock: a chronometer from the ride's start (" + n.when + " vs " + startWall + ")");
        require(n.extras.getCharSequence(SHORT_CRITICAL_TEXT) == null, "No short text, so the chip is the clock");
        require(!n.extras.getBoolean(Notification.EXTRA_COLORIZED) && n.extras.getString(Notification.EXTRA_TEMPLATE) == null, "Not colorized, no style of its own");
        require(n.actions != null && n.actions.length == 1 && "Stop recording".contentEquals(n.actions[0].title), "Stop recording on the card");
        require(n.deleteIntent != null, "A swipe away is heard (delete intent)");
        foregroundValid("ride");
        quiet("ride");
        // The clock counts up by itself: nothing reposts the notice while only the time moves.
        long postTime = posted.getPostTime();
        SystemClock.sleep(20 * S);
        require(connection() != null && connection().getPostTime() == postTime && promoted(), "The ride's notice was reposted while only the clock moved: " + describe());
        require(onMainGet(() -> service.liveUpdateStatus()) == ChargeLiveUpdate.Status.PROMOTED, "The Live Update status row reports the ride's promotion");
        dumpsys("ride");
        holdFor("ride");
        dismissRide();
        reopenAfterSwipe();
        dismissRide();
        errorAfterSwipe();
        main(() -> service.stopRide());
        await(() -> connection() != null && !rideNotice(), 20 * S, "Ending the dismissed ride posted no notice: " + describe());
        require(!onMain(() -> ((LiveNotices.Dismissal) field(service, "rideDismissal")).active()), "Ending the ride clears the dismissal");
        log("ride ended: " + describe());
        await(() -> { try { return !promoted() && !record71().contains("PROMOTED_ONGOING"); } catch (Exception e) { throw new IllegalStateException(e); } },
                10 * S, "The notice after the ride kept the ride's promotion: " + describe());
        foregroundValid("ride ended");
        quiet("ride ended");
    }

    /** The synthetic odometer, km: each ride() moves it on, so the distance only grows. */
    private double odometerKm = 101;
    /** Main thread: count more 1 Hz samples of riding from now, the odometer 100 m further each. */
    private void ride(long now, long wall, int count) {
        // Samples run on from the last one (an earlier call may have written a little ahead of now), never back in time.
        long from = Math.max(now, lastSampleMono + S);
        wall += from - now;
        try { for (int i = 0; i < count; i++) service.rides.sample(wall + i * S, from + i * S, values(18, 69, odometerKm += 0.1, from + i * S)); }
        catch (IOException e) { throw new IllegalStateException(e); }
        lastSampleMono = from + (count - 1) * S;
    }
    private long lastSampleMono;
    /**
     * #644 F06: after a swipe, opening Volt shows the ride's notice again (the foreground start), and from then on it
     * updates: leaving Volt and riding on moves its distance.
     */
    private void reopenAfterSwipe() throws Exception {
        ensureFront();
        await(() -> rideNotice() && !onMain(() -> ((LiveNotices.Dismissal) field(service, "rideDismissal")).active()), 10 * S,
                "Opening Volt didn't show the swiped ride's notice again: " + describe());
        leave();
        String before = text();
        main(() -> { ride(SystemClock.elapsedRealtime(), System.currentTimeMillis(), 20); invoke(service, "updateNotification", new Class<?>[]{}); });
        await(() -> rideNotice() && text() != null && !text().equals(before), 15 * S, "After opening Volt, the ride's notice never updated: still " + before);
        require(promoted(), "Still a Live Update after opening Volt");
        log("ride: reopened after the swipe, the distance moves on: " + before + " -> " + text());
    }
    /**
     * #644 F08: after a swipe, a recording error is not the ride clock's Live Update: it posts at once (plain) and ends
     * the hold; once the error clears, the ride's Live Update posts again.
     */
    private void errorAfterSwipe() throws Exception {
        main(() -> { set(service, "recordingError", "Synthetic recording error"); invoke(service, "updateNotification", new Class<?>[]{}); });
        await(() -> "Recording needs attention".equals(title()), 15 * S, "The recording error never posted after the swipe: " + describe());
        require(!promoted() && !onMain(() -> ((LiveNotices.Dismissal) field(service, "rideDismissal")).active()), "The error is plain and ended the hold: " + describe());
        log("ride: error after the swipe posted: " + describe());
        main(() -> { set(service, "recordingError", null); invoke(service, "updateNotification", new Class<?>[]{}); });
        await(() -> rideNotice() && promoted(), 20 * S, "The ride's Live Update didn't come back after the error cleared: " + describe());
        log("ride: error cleared, the Live Update is back: " + describe());
    }
    /** A recording in the service's own RideStore, a minute old, its odometer moved; the service then works as for any ride. */
    private long startRide(String label) {
        final long[] wall = {0};
        // The ride is a minute old on the monotonic clock: a freshly booted emulator waits until it has been up that long.
        while (SystemClock.elapsedRealtime() < 65 * S) SystemClock.sleep(500);
        main(() -> {
            require(!service.isRecording(), "No recording before the ride");
            long now = SystemClock.elapsedRealtime(), nowWall = System.currentTimeMillis(), start = now - 60 * S;
            wall[0] = nowWall - 60 * S;
            try {
                service.rides.start(profile.id, label, wall[0], start);
                for (long t = start; t <= now; t += S) service.rides.sample(nowWall - (now - t), t, values(18, 70, 100 + (t - start) / 200000.0, t));
            } catch (IOException e) { throw new IllegalStateException(e); }
            invoke(service, "applyWorkPolicy", new Class<?>[]{boolean.class}, false);
        });
        require(onMain(() -> service.isRecording()), label + " is recording");
        return wall[0];
    }
    private static Map<String, RideStore.Sample> values(double speed, double soc, double odo, long updated) {
        Map<String, RideStore.Sample> v = new LinkedHashMap<>();
        v.put("battery_percent", new RideStore.Sample(soc, updated)); v.put("odometer_km", new RideStore.Sample(odo, updated));
        v.put("speed_kmh", new RideStore.Sample(speed, updated)); v.put("temperature_c", new RideStore.Sample(20, updated));
        v.put("charging_state", new RideStore.Sample(0, updated)); return v;
    }

    /** A person's swipe: the shade open, the ride's card swiped sideways where the shade shows it. */
    private void dismissRide() throws Exception {
        UiAutomation ui = getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        ui.setServiceInfo(info);
        shell("cmd statusbar expand-notifications");
        android.graphics.Rect bounds = null;
        long end = SystemClock.uptimeMillis() + 10 * S;
        while (bounds == null && SystemClock.uptimeMillis() < end) {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            AccessibilityNodeInfo active = ui.getRootInActiveWindow();
            if (active != null) roots.add(active);
            for (android.view.accessibility.AccessibilityWindowInfo window : ui.getWindows()) if (window.getRoot() != null) roots.add(window.getRoot());
            for (AccessibilityNodeInfo root : roots) {
                if (root == null) continue;
                for (AccessibilityNodeInfo found : root.findAccessibilityNodeInfosByText("Ride recording is active")) {
                    android.graphics.Rect r = new android.graphics.Rect(); found.getBoundsInScreen(r);
                    if (r.height() > 0) { bounds = r; break; }
                }
                if (bounds != null) break;
            }
            if (bounds == null) SystemClock.sleep(250);
        }
        if (bounds == null) {
            StringBuilder seen = new StringBuilder();
            for (android.view.accessibility.AccessibilityWindowInfo window : ui.getWindows()) seen.append(window.getTitle()).append(window.getRoot() == null ? "(no root) " : " ");
            AccessibilityNodeInfo active = ui.getRootInActiveWindow();
            log("ride: the shade's card not in the accessibility tree; windows: " + seen + "; active: " + (active == null ? null : active.getPackageName()));
        }
        android.util.DisplayMetrics screen = getTargetContext().getResources().getDisplayMetrics();
        int width = screen.widthPixels;
        // Where the shade puts its first card when the accessibility tree doesn't say: about 31% down a 1080x2400 phone and 44%
        // down an unfolded Fold. Swiped from the card's middle third to the right edge, lower each time until it goes.
        float[] tries = bounds != null ? new float[]{(float) bounds.centerY() / screen.heightPixels} : new float[]{0.31f, 0.37f, 0.44f, 0.50f};
        for (float at : tries) {
            int y = Math.round(screen.heightPixels * at);
            log("ride: swiping the card away at y=" + y + (bounds != null ? " (its title's bounds)" : " (where the shade's first card sits)"));
            shell("input swipe " + width * 2 / 5 + " " + y + " " + (width - 10) + " " + y + " 200");
            SystemClock.sleep(1500);
            if (connection() == null) break;
        }
        await(() -> connection() == null, 10 * S, "The ride's notice is still posted after the dismiss: " + describe());
        await(() -> onMain(() -> ((LiveNotices.Dismissal) field(service, "rideDismissal")).active()), 10 * S, "The delete intent never reached the service");
        log("ride: dismissed in the shade");
        shell("cmd statusbar collapse");
        // A change that reposts a ride's notice (more distance), then the tick: the notice stays away.
        main(() -> {
            long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
            ride(now, wall, 30);
            invoke(service, "updateNotification", new Class<?>[]{});
        });
        SystemClock.sleep(12 * S);
        require(connection() == null && onMain(() -> service.isRecording()), "The dismissed ride's notice came back during the ride: " + describe());
        foregroundValid("ride dismissed");
    }

    // ---- The live charging card's chip ---------------------------------------------------------------------

    private void chargeChip() throws Exception {
        require(onMainGet(() -> service.chargeLiveChip()) == ChargeLiveState.Chip.LEVEL, "Level is the default chip");
        ensureFront();
        readChargingInFront();
        leave();
        await(() -> isCard() && promoted(), 30 * S, "The live card was not promoted out of sight: " + describe());
        log("card: " + describe());
        await(() -> "62%".equals(chip()), 10 * S, "Level: the chip is the level: " + chip());
        foregroundValid("card");
        quiet("card");
        holdFor("charge-level");
        main(() -> service.setChargeLiveChip(ChargeLiveState.Chip.FINISH));
        require("finish".equals(settings().getString(ScooterService.CHARGE_LIVE_CHIP, null)), "The choice is saved");
        await(() -> finishShown().equals(chip()), 10 * S, "Finish time: the chip " + chip() + " vs the card's " + text());
        log("finish chip: " + chip() + " | " + text());
        // Wait for the card's own finish time if it has none yet, so both sides of the fallback are seen.
        // (ChargeEstimateHold holds a new charge's time for up to MAX_HOLD_MS; the chip must follow the card throughout.)
        long end = SystemClock.elapsedRealtime() + ChargeEstimateHold.MAX_HOLD_MS + 60 * S;
        while (finishShown().equals("62%") && SystemClock.elapsedRealtime() < end) { require(finishShown().equals(chip()) || !"62%".equals(finishShown()), "While estimating, the level stands in: " + chip()); SystemClock.sleep(S); }
        require(!finishShown().equals("62%"), "The card never got a finish time: " + text());
        await(() -> finishShown().equals(chip()), 20 * S, "Finish time once the card has a time: the chip " + chip() + " vs " + text());
        log("finish chip with a time: " + chip() + " | " + text());
        require(promoted(), "Still promoted with the finish time chip");
        quiet("card finish");
        holdFor("charge-finish");
        main(() -> service.setChargeLiveChip(ChargeLiveState.Chip.LEVEL));
        await(() -> "62%".equals(chip()), 10 * S, "Back to Level: " + chip());
        // Unplugged out of sight: the card clears to the plain Connected notice.
        main(() -> link.charging(OFF));
        await(() -> !isCard() && connection() != null, StatusCadence.POLL_SLOW_MS + ChargeLiveHold.UNPLUG_MS + 10 * S, "The card never cleared after unplugging: " + describe());
        log("unplugged: " + describe());
        // The plain notice that follows asks for nothing: Android drops the promotion (no stale chip in the status bar).
        await(() -> { try { return !promoted() && !record71().contains("PROMOTED_ONGOING"); } catch (Exception e) { throw new IllegalStateException(e); } },
                10 * S, "Scooter connected kept the card's promotion: " + describe());
        foregroundValid("card ended");
        quiet("card ended");
        main(() -> settings().edit().remove(ScooterService.CHARGE_LIVE_CHIP).commit());
    }
    /** The chip Finish time should show now: the time in the card's "Done 4:45 PM · 412 W", else the level. */
    private String finishShown() {
        String t = text();
        if (t == null || !t.startsWith("Done ")) return "62%";
        String time = t.substring(5);
        int dot = time.indexOf(" · ");
        return dot < 0 ? time : time.substring(0, dot);
    }
    private void readChargingInFront() {
        main(() -> { link.charging(ON); link.home(); });
        settle();
        await(() -> onMain(() -> ble.ready() && Double.valueOf(1d).equals(ble.telemetry.number("charging_state"))), StatusCadence.POLL_SLOW_MS + 10 * S, "No fresh charging reading to arm on");
        SystemClock.sleep(4 * S);
    }

    // ---- Shared checks -------------------------------------------------------------------------------------

    /** The service is still in the foreground with its connected-device type, and Android shows (or held back) its one notice 71. */
    private void foregroundValid(String what) {
        require(onMain(() -> service.getForegroundServiceType() != 0 && (Boolean) field(service, "foregroundStarted")), what + ": the service left the foreground");
        int count = 0;
        for (StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == 71) count++;
        require(count <= 1, what + ": more than one connection notice");
    }
    /**
     * Nothing sounds, vibrates or pops up: dumpsys notification's attention state names no Volt notice as the one
     * sounding or vibrating, and its record of notice 71 is logged with the stage (importance, channel, interruptive).
     */
    private void quiet(String what) throws Exception {
        String dump = shell("dumpsys notification --noredact");
        String pkg = "|" + getTargetContext().getPackageName() + "|";
        for (String line : dump.split("\n")) {
            String l = line.trim();
            if (l.startsWith("mSoundNotificationKey=") || l.startsWith("mVibrateNotificationKey="))
                require(!l.contains(pkg), what + ": Android is sounding or vibrating for a Volt notice: " + l);
        }
        log(what + " record: " + record71().replace('\n', ' '));
    }
    /** The lines of dumpsys notification's record for Volt's notice 71 (empty when none is posted). */
    private String record71() throws Exception { return record71(false); }
    private String record71(boolean full) throws Exception {
        String dump = shell("dumpsys notification --noredact");
        String pkg = getTargetContext().getPackageName();
        int at = dump.indexOf("NotificationRecord(");
        while (at >= 0) {
            int next = dump.indexOf("NotificationRecord(", at + 1);
            String block = next < 0 ? dump.substring(at) : dump.substring(at, next);
            if (block.contains("pkg=" + pkg) && block.contains("id=71 ")) {
                if (full) return block;
                StringBuilder keep = new StringBuilder();
                for (String line : block.split("\n")) {
                    String l = line.trim();
                    if (l.startsWith("mImportance=") || l.startsWith("mSound=") || l.startsWith("mVibration=") || l.startsWith("mIsInterruptive=")
                            || l.startsWith("flags=")) keep.append(l).append('\n');
                    else if (l.startsWith("effectiveNotificationChannel=")) keep.append(l.replaceAll(",.*", "")).append('\n');
                }
                return keep.toString();
            }
            at = next;
        }
        return "";
    }
    private void dumpsys(String what) throws Exception { log(what + " dumpsys: " + record71(true).replace('\n', ' ')); }
    private void holdFor(String stage) {
        if (hold <= 0) return;
        log("hold:" + stage);
        SystemClock.sleep(hold);
    }

    private void leave() {
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> !service.isVisible()), 10000, "Volt never left the foreground");
    }
    private void ensureFront() {
        if (onMain(() -> service.isVisible())) return;
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        await(() -> onMain(() -> service.isVisible()), 10000, "Volt never returned to the foreground");
        settle();
    }
    private String shell(String command) throws IOException {
        ParcelFileDescriptor out = getUiAutomation().executeShellCommand(command);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) { byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n); }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private StatusBarNotification connection() { for (StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == 71) return n; return null; }
    private boolean promoted() { StatusBarNotification n = connection(); return n != null && (n.getNotification().flags & Notification.FLAG_PROMOTED_ONGOING) != 0; }
    private String title() { StatusBarNotification n = connection(); CharSequence t = n == null ? null : n.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE); return t == null ? null : t.toString(); }
    private String text() { StatusBarNotification n = connection(); CharSequence t = n == null ? null : n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT); return t == null ? null : t.toString(); }
    private String chip() { StatusBarNotification n = connection(); CharSequence t = n == null ? null : n.getNotification().extras.getCharSequence(SHORT_CRITICAL_TEXT); return t == null ? null : t.toString(); }
    private boolean rideNotice() { return "Ride recording is active".equals(title()); }
    private boolean isCard() { String t = title(); return t != null && t.startsWith("Charging"); }
    private String describe() {
        StatusBarNotification n = connection();
        if (n == null) return "(no connection notice)";
        return title() + " | " + text() + " | chip " + chip() + " | channel " + n.getNotification().getChannelId() + " | promoted " + promoted();
    }
    private void log(String line) { timeline.append(SystemClock.elapsedRealtime() / 1000).append("s ").append(line).append('\n'); Bundle status = new Bundle(); status.putString("progress", line); sendStatus(0, status); }

    private final class FakeLink {
        byte charging = OFF;
        /** Charging current; 2 A (104 W at 52 V). */
        int milliamps = 2000;
        final Handler handler = new Handler(Looper.getMainLooper());
        BluetoothGatt gatt; BluetoothGattCharacteristic writer, notifier; BluetoothGattCallback callbacks;
        void charging(byte value) { charging = value; }
        void attach() { try {
            BluetoothDevice device = getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Field unsafe = unsafeType.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            gatt = (BluetoothGatt) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), BluetoothGatt.class);
            Class<?> iface = Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport = (proxy, method, a) -> {
                if (method.getDeclaringClass() == Object.class) { if (method.getName().equals("equals")) return proxy == a[0]; if (method.getName().equals("hashCode")) return System.identityHashCode(proxy); return "LiveUpdateFixtureGatt"; }
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
            ble.refresh(true);
            Method poll = BleController.class.getDeclaredMethod("poll", int.class); poll.setAccessible(true); poll.invoke(ble, ble.connectionGeneration());
        } catch (Exception error) { throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator", error); } }
        void received(byte[] frame) {
            callbacks.onCharacteristicWrite(gatt, writer, 0);
            if (frame.length < 4) return;
            switch (frame[3] & 255) { case 0x70: status(); break; case 0x72: battery(); break; default: break; }
        }
        void status() { byte[] state = new byte[44]; state[1] = 3; state[3] = 1; state[6] = 2; state[7] = 1; state[19] = 3; state[25] = 50; state[31] = 100; state[34] = 3; packet(0x70, state); }
        void home() { byte[] data = new byte[12]; data[1] = 1; data[2] = 62; data[4] = charging; packet(0x90, data); }
        /**
         * 62%, 52.000 V and the charging current (sign bit set) or 0 A, little-endian, as Telemetry.electrical reads them;
         * health 100% and a rated 18.00 Ah, so the charging estimate has a capacity and the card a finish time.
         */
        void battery() {
            byte[] data = new byte[26]; data[1] = 62; data[12] = charging; data[10] = 100; data[23] = (byte) 0x08; data[24] = (byte) 0x07;
            int volts = 52000, amps = charging == ON ? milliamps : 0;
            for (int i = 0; i < 4; i++) { data[2 + i] = (byte) (volts >>> (8 * i)); data[6 + i] = (byte) (amps >>> (8 * i)); }
            if (charging == ON) data[9] |= (byte) 0x80;
            packet(0x72, data);
        }
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
    private <V> V onMainGet(java.util.function.Supplier<V> query) { final List<V> result = new ArrayList<>(); main(() -> result.add(query.get())); return result.get(0); }
    private void await(BooleanSupplier query, long ms, String message) { long end = SystemClock.uptimeMillis() + ms; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) return; SystemClock.sleep(100); } throw new AssertionError(message); }
    private void settle() { SystemClock.sleep(350); waitForIdleSync(); }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        try { Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(object, args); }
        catch (InvocationTargetException e) { throw new IllegalStateException(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
