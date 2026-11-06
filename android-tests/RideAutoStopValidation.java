package com.jjricks.volt.tests;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.text.Layout;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator only. Synthetic journals that ended with the automatic parked stop:
 * ride details pill, End rides automatically preference, ride backup round trip, learned-range screening
 * and the Ride saved automatically notice. Then the went-quiet rule through the service's real tick:
 * synthetic recordings (started in the service's own RideStore, never through Bluetooth) whose policy
 * is fed a scripted scooter trace, then left to the tick: a walking power-off ends after about a
 * minute, a reconnect inside the window keeps recording, and the phone's Bluetooth off ends after
 * 5 minutes (time-shifted). Never connects Bluetooth.
 */
public final class RideAutoStopValidation extends Instrumentation {
    private Bundle args; private MainActivity activity; private int checks; private final List<String> screenshots = new ArrayList<>();
    private String profileId; private final List<String> automatic = new ArrayList<>(); private String manual;
    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Refusing physical phone");
            for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents"}) check(context.getSharedPreferences(name, 0).getAll().isEmpty(), "Requires empty preferences");
            check(context.getFilesDir().list().length == 0 && context.databaseList().length == 0, "Requires empty private data");
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter(); check(adapter == null || !adapter.isEnabled(), "Bluetooth must be off");
            context.getSharedPreferences("app_settings", 0).edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("map_basemap_enabled", false).putString("theme", args.getString("theme", "dark")).commit();
            AtomicReference<String> created = new AtomicReference<>();
            main(() -> created.set(new ScooterProfiles(context).create("Synthetic auto-stop scooter", "A1B2C3D4E5F6", 0).id)); profileId = created.get();
            File folder = new File(context.getFilesDir(), "rides");
            seed(folder); backupRoundTrip(context, folder); preferenceTypes();
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service ready");
            learnedRange();
            details();
            preference();
            notice();
            if (!"0".equals(args.getString("live", "1"))) {
                reconnectKeepsRecording();
                phoneBluetoothOff();
                String quiet = walkingPowerOff();
                quietDetails(quiet);
            }
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or active recording");
            // The census for the runner's shade screenshot (run-ride-auto-stop.py moves it beside the PNG).
            TextCensus.writeNotifications(this, new File(new File(context.getExternalFilesDir(null), "auto-stop-validation"), args.getString("variant", "auto-stop") + "-notification-shade.png"));
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Same shape as RideAutoStopStoreTest: 570 s riding, 30 s parked, 12 s silent link, the gap, 10 s grace. */
    private void seed(File folder) throws Exception {
        RideStore store = new RideStore(folder); long base = System.currentTimeMillis() - 4 * 3600000L;
        for (int n = 0; n < 4; n++) {
            boolean auto = n < 3; long start = base + n * 900000L, mono = 0;
            String id = store.start(profileId, auto ? "Parked campus ride " + (n + 1) : "Stopped by hand", start, 0);
            for (int sec = 0; sec <= 600; sec++) {
                mono = sec * 1000L; boolean parked = sec > 570; double f = Math.min(1, sec / 570d);
                store.sample(start + mono, mono, values(parked ? 0 : 24, 80 - 8 * f, 100 + 4 * f, mono));
            }
            long last = mono;
            for (int i = 1; i <= 12; i++) { mono = last + i * 1000L; store.sample(start + mono, mono, values(0, 72, 104, last)); }
            store.markGap(start + mono + 1000, mono + 1000, "Bluetooth disconnected");
            for (int i = 1; i <= 10; i++) store.heartbeat(start + mono + 1000 + i * 1000L, mono + 1000 + i * 1000L);
            mono += 11000;
            store.stop(start + mono, mono, auto ? RideStore.STOP_AUTO_PARKED : "");
            if (auto) automatic.add(id); else manual = id;
        }
        for (String id : automatic) { RideStore.Summary s = store.get(id); check(s.automaticStop && RideStore.STOP_AUTO_PARKED.equals(s.stopReason) && s.gapCount == 1, "Seeded automatic stop reads back"); }
        RideStore.Summary hand = store.get(manual); check(!hand.automaticStop && "".equals(hand.stopReason), "Seeded rider stop reads back as the rider's");
        check(store.get(automatic.get(0)).toJson().contains("\"stopReason\":\"automatic_parked_disconnect\"") && hand.toJson().contains("\"stopReason\":\"rider\""), "Analysis JSON names each stop");
    }
    private static Map<String, RideStore.Sample> values(double speed, double soc, double odo, long updated) {
        Map<String, RideStore.Sample> v = new LinkedHashMap<>();
        v.put("battery_percent", new RideStore.Sample(soc, updated)); v.put("odometer_km", new RideStore.Sample(odo, updated));
        v.put("speed_kmh", new RideStore.Sample(speed, updated)); v.put("temperature_c", new RideStore.Sample(20, updated));
        v.put("charging_state", new RideStore.Sample(0, updated)); return v;
    }

    /** Real .voltrides export, delete, stage, plan and apply through the app's private storage. */
    private void backupRoundTrip(Context context, File folder) throws Exception {
        Class<?> archive = Class.forName("com.jjricks.volt.RideArchive"), storageType = Class.forName("com.jjricks.volt.RideArchive$Storage"),
                preparedType = Class.forName("com.jjricks.volt.RideArchive$Prepared"), planType = Class.forName("com.jjricks.volt.RideArchive$Plan");
        Object storage = call(Class.forName("com.jjricks.volt.RideBackup"), null, "storage", new Class<?>[]{Context.class}, context);
        Map<String, byte[]> journals = new HashMap<>(); RideStore store = new RideStore(folder);
        for (RideStore.Summary s : store.list()) journals.put(s.id, Files.readAllBytes(new File(folder, s.id + ".csv").toPath()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        check(Integer.valueOf(4).equals(call(archive, null, "write", new Class<?>[]{storageType, OutputStream.class, Map.class}, storage, bytes, Collections.singletonMap(profileId, "Synthetic auto-stop scooter"))), "Ride backup exports all four rides");
        for (RideStore.Summary s : store.list()) store.delete(s.id);
        check(new RideStore(folder).list().isEmpty(), "Rides removed before restore");
        Object prepared = call(archive, null, "stage", new Class<?>[]{storageType, InputStream.class}, storage, new ByteArrayInputStream(bytes.toByteArray()));
        Object plan = call(archive, null, "plan", new Class<?>[]{storageType, preparedType, Map.class}, storage, prepared, Collections.singletonMap(profileId, profileId));
        check((int) field(plan, "added") == 4, "Restore preview adds the four rides");
        call(archive, null, "apply", new Class<?>[]{storageType, planType}, storage, plan);
        RideStore reopened = new RideStore(folder);
        for (String id : automatic) { RideStore.Summary s = reopened.get(id); check(s != null && s.automaticStop && !s.active && s.scooterId.equals(profileId), "Restored ride keeps its automatic stop reason"); }
        check(!reopened.get(manual).automaticStop, "Restored rider stop stays the rider's");
        for (Map.Entry<String, byte[]> e : journals.entrySet()) check(Arrays.equals(e.getValue(), Files.readAllBytes(new File(folder, e.getKey() + ".csv").toPath())), "Restored journal bytes identical");
        Object again = call(archive, null, "plan", new Class<?>[]{storageType, preparedType, Map.class}, storage,
                call(archive, null, "stage", new Class<?>[]{storageType, InputStream.class}, storage, new ByteArrayInputStream(bytes.toByteArray())), Collections.singletonMap(profileId, profileId));
        check((int) field(again, "identical") == 4 && (int) field(again, "added") == 0, "Reimport is idempotent");
        call(archive, null, "apply", new Class<?>[]{storageType, planType}, storage, again);
    }

    /** Full app backups reject a mistyped End rides automatically value before replacing live state. */
    private void preferenceTypes() throws Exception {
        Class<?> archive = Class.forName("com.jjricks.volt.AppDataArchive");
        for (Object value : new Object[]{Boolean.TRUE, Boolean.FALSE, "true", 1}) {
            Map<String, Map<String, Object>> all = new HashMap<>();
            for (String name : new String[]{"app_settings", "profiles", "scooter", "control_evidence", "onboarding"}) all.put(name, new HashMap<>());
            all.get("app_settings").put("ride_auto_stop", value);
            boolean rejected = false;
            try { call(archive, null, "validatePreferenceTypes", new Class<?>[]{Map.class}, all); } catch (IOException expected) { rejected = true; }
            check(rejected == !(value instanceof Boolean), "Full backup type check for ride_auto_stop=" + value + " (" + value.getClass().getSimpleName() + ")");
        }
    }

    private void learnedRange() throws Exception {
        ScooterService service = activity.service(); AtomicReference<LearnedRangeEstimate.Result> estimate = new AtomicReference<>();
        main(() -> service.requestLearnedRangeEstimate(estimate::set)); await(() -> estimate.get() != null, "Range analysis finished");
        LearnedRangeEstimate.Result r = estimate.get();
        check(r.rides == 3 && r.rejectedRides == 1, "Learned range trains on the three automatic rides and rejects the hand-stopped tail: rides=" + r.rides + " rejected=" + r.rejectedRides);
        check(r.fullRangeKm != null && Math.abs(r.fullRangeKm - 50) < .001 && Math.abs(r.distanceKm - 12) < 1e-6 && Math.abs(r.batteryPoints - 24) < 1e-6,
                "Parked tail adds no distance or battery use: range=" + r.fullRangeKm + " km=" + r.distanceKm + " points=" + r.batteryPoints);
    }

    private void details() throws Exception {
        main(() -> invoke(activity, "select", new Class[]{String.class}, "rides")); settle();
        RidesScreen screen = (RidesScreen) field(activity, "screen"); boolean wide = (boolean) field(screen, "wide");
        main(() -> invoke(screen, wide ? "selectRide" : "showDetails", new Class[]{String.class}, automatic.get(2))); settle();
        TextView pill = text(rideRoot(screen), "Saved automatically"); check(pill != null, "Ride details show Saved automatically");
        check("Saved automatically after Bluetooth was lost while parked".contentEquals(pill.getContentDescription()), "Pill explains itself to screen readers");
        main(() -> pill.requestRectangleOnScreen(new Rect(0, -activity.ui().dp(160), pill.getWidth(), pill.getHeight() + activity.ui().dp(120)), true)); settle();
        fullyShown(pill, "Saved automatically pill");
        View parent = (View) pill.getParent(); Rect p = new Rect(), own = new Rect(); parent.getGlobalVisibleRect(p); pill.getGlobalVisibleRect(own);
        check(own.left >= p.left && own.right <= p.right, "Pill sits inside the details column");
        screenshot("details-automatic");
        if (wide) { main(() -> invoke(screen, "selectRide", new Class[]{String.class}, manual)); }
        else { main(() -> ((AlertDialog) ((List<?>) field(screen, "dialogs")).get(((List<?>) field(screen, "dialogs")).size() - 1)).dismiss()); settle(); main(() -> invoke(screen, "showDetails", new Class[]{String.class}, manual)); }
        settle(); check(text(rideRoot(screen), "Saved automatically") == null, "A rider's stop has no automatic pill");
        if (!wide) { main(() -> { List<?> dialogs = (List<?>) field(screen, "dialogs"); for (Object d : new ArrayList<>(dialogs)) ((AlertDialog) d).dismiss(); }); settle(); }
        screenshot("history");
    }
    private View rideRoot(RidesScreen screen) { List<?> dialogs = (List<?>) field(screen, "dialogs"); return dialogs.isEmpty() ? screen.view() : ((AlertDialog) dialogs.get(dialogs.size() - 1)).getWindow().getDecorView(); }

    private void preference() throws Exception {
        SharedPreferences settings = getTargetContext().getSharedPreferences("app_settings", 0);
        check(!settings.contains(ScooterServiceKeys.AUTO_STOP), "Preference absent on a fresh install");
        main(() -> { invoke(activity, "select", new Class[]{String.class}, "more"); invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "App"); }); settle();
        View root = activity.getWindow().getDecorView();
        TextView title = text(root, "End rides automatically"); check(title != null, "End rides automatically row present");
        TextView sub = text(root, "When the scooter turns off, stays out of reach for 5 min, or doesn't move for 10 min"); check(sub != null, "Row explains the rule");
        View row = (View) title.getParent().getParent(); Switch toggle = find(row, Switch.class); check(toggle != null, "Row has a switch");
        TextView heading = text(root, "Connection & recording"); check(heading != null, "Connection & recording heading present");
        centre(row);
        fullyShown(title, "Preference title"); fullyShown(sub, "Preference detail"); fullyShown(toggle, "Preference switch");
        Rect t = new Rect(), s = new Rect(), w = new Rect(); title.getGlobalVisibleRect(t); sub.getGlobalVisibleRect(s); toggle.getGlobalVisibleRect(w);
        check(t.right <= w.left && s.right <= w.left && t.bottom <= s.top + 1, "Title, detail and switch do not overlap");
        check(toggle.isChecked() && toggle.isEnabled() && toggle.getHeight() >= activity.ui().dp(48), "Default on, enabled, 48dp switch target");
        check("End rides automatically".contentEquals(toggle.getContentDescription()), "Switch is named for screen readers");
        screenshot("preference-on");
        main(row::performClick); settle();
        check(!toggle.isChecked() && settings.contains(ScooterServiceKeys.AUTO_STOP) && !settings.getBoolean(ScooterServiceKeys.AUTO_STOP, true), "Row tap turns the preference off");
        File xml = new File(getTargetContext().getDataDir(), "shared_prefs/app_settings.xml");
        await(() -> { try { return new String(Files.readAllBytes(xml.toPath()), "UTF-8").contains("name=\"ride_auto_stop\" value=\"false\""); } catch (IOException e) { return false; } }, "Off choice written to disk");
        screenshot("preference-off");
        main(() -> { invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "Data"); invoke(field(activity, "screen"), "selectSection", new Class[]{String.class}, "App"); }); settle();
        Switch again = find((View) text(activity.getWindow().getDecorView(), "End rides automatically").getParent().getParent(), Switch.class);
        check(again != null && !again.isChecked(), "Reopened section still shows off");
        main(() -> ((View) again.getParent()).performClick()); settle();
        check(again.isChecked() && settings.getBoolean(ScooterServiceKeys.AUTO_STOP, false), "Turning it back on persists");
    }
    /** Scrolls the row to mid-screen so the floating navigation bar cannot cover it, then checks that. */
    private void centre(View row) {
        main(() -> { View v = row; while (v != null && !(v instanceof ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            check(v != null, "Preference row scrolls"); ScrollView scroll = (ScrollView) v; Rect r = new Rect(); row.getDrawingRect(r); scroll.offsetDescendantRectToMyCoords(row, r);
            scroll.scrollTo(0, Math.max(0, r.centerY() - scroll.getHeight() / 2)); }); settle();
        int[] at = new int[2]; row.getLocationOnScreen(at); int screen = activity.getWindow().getDecorView().getHeight();
        // Compact widths float the navigation bar over the content; wide layouts use a side rail.
        int clearance = activity.getResources().getConfiguration().screenWidthDp >= 600 ? 24 : 140;
        check(at[1] + row.getHeight() <= screen - activity.ui().dp(clearance), "Row clear of the navigation bar and gesture area");
    }
    /** The service's key, read from the class so a rename fails here instead of passing silently. */
    private static final class ScooterServiceKeys { static final String AUTO_STOP = readKey(); private static String readKey() { try { Field f = ScooterService.class.getDeclaredField("RIDE_AUTO_STOP"); f.setAccessible(true); return (String) f.get(null); } catch (Exception e) { throw new IllegalStateException(e); } } }

    /** Posts the real notice through the service's private method; the shipped app gains no hook. */
    private void notice() throws Exception {
        Context context = getTargetContext(); NotificationManager manager = context.getSystemService(NotificationManager.class);
        check(manager.areNotificationsEnabled(), "Runner granted POST_NOTIFICATIONS");
        String id = automatic.get(2); ScooterService service = activity.service();
        main(() -> invoke(service, "showAutoStopNotice", new Class[]{String.class}, id));
        await(() -> notice(manager) != null, "Ride saved automatically notice posted");
        Notification n = notice(manager).getNotification();
        check("Ride saved automatically".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE)), "Notice title");
        check("Parked campus ride 3 · Bluetooth lost while parked".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TEXT)), "Notice names the ride and the reason: " + n.extras.getCharSequence(Notification.EXTRA_TEXT));
        check((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && (n.flags & Notification.FLAG_ONGOING_EVENT) == 0 && n.contentIntent != null, "Notice is dismissable, auto-cancels and opens Volt");
        main(() -> invoke(service, "cancelAutoStopNotice", new Class[0]));
        await(() -> notice(manager) == null, "The service clears the notice (as startRide does)");
        // Left posted for the runner, which opens the shade and captures it after this process ends.
        main(() -> invoke(service, "showAutoStopNotice", new Class[]{String.class}, id));
        await(() -> notice(manager) != null, "Notice reposted for the shade capture");
    }
    private static StatusBarNotification notice(NotificationManager manager) { for (StatusBarNotification n : manager.getActiveNotifications()) if (n.getId() == 72) return n; return null; }

    // ---- Went quiet, through the service's tick -----------------------------------------------------

    private static final String SERIAL = "N2546000000001";
    /**
     * Starts a recording in the service's own RideStore, as RideAutoExportValidation does, with synthetic
     * scooter samples up to {@code lastFrame}, and hands the tick a policy fed the same scripted trace: 1 Hz
     * pushes at {@code speedRaw}, then the loss. The service then runs as on a phone whose scooter went quiet.
     */
    private RideAutoStopPolicy liveRide(String label, long startAgo, long lastAgo, int speedRaw, RideAutoStopPolicy.Loss loss) throws Exception {
        ScooterService service = activity.service(); AtomicReference<RideAutoStopPolicy> made = new AtomicReference<>();
        main(() -> {
            try {
                check(!service.isRecording() && !service.ble.connected(), "No recording or connection before " + label);
                service.profiles.active().serial = SERIAL; set(service, "gapMarked", false);
                long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis(), start = now - startAgo, last = now - lastAgo;
                check(start > 0, "Emulator uptime covers the synthetic ride");
                String id = service.rides.start(profileId, label, wall - startAgo, start);
                for (long t = start; t <= last; t += 1000) service.rides.sample(wall - (now - t), t, values(speedRaw / 10.0, 70, 110, t));
                RideAutoStopPolicy policy = new RideAutoStopPolicy(id, profileId, SERIAL);
                for (long t = Math.max(start, last - 20000); t <= last; t += 1000) policy.connected(t, profileId, SERIAL, 1, t, t, speedRaw, 1);
                policy.disconnected(now, loss);
                set(service, "rideAutoStop", policy); set(service, "rideAutoStopDisconnects", service.ble.intentionalDisconnectSequence());
                invoke(service, "applyWorkPolicy", new Class[]{boolean.class}, false);
                made.set(policy);
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        check(activity.service().isRecording(), label + " is recording");
        return made.get();
    }
    private RideStore.Summary awaitStopped(String id, long deadlineMono, String what) throws Exception {
        while (SystemClock.elapsedRealtime() < deadlineMono && activity.service().isRecording()) SystemClock.sleep(100);
        check(!activity.service().isRecording(), what);
        return activity.service().rides.get(id);
    }
    private String noticeText() {
        StatusBarNotification n = notice(getTargetContext().getSystemService(NotificationManager.class));
        return n == null ? null : String.valueOf(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
    }
    private String clock(long wall) { return DisplayTime.clock(getTargetContext(), getTargetContext().getSharedPreferences("app_settings", 0), wall); }

    /** The phone's Bluetooth off (the emulator's is off): 5 min from the last frame, time-shifted so it is due in ~5 s. */
    private void phoneBluetoothOff() throws Exception {
        RideAutoStopPolicy policy = liveRide("Phone Bluetooth off", 330000, 295000, 200, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(policy.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF, "Phone Bluetooth off counts down 5 min");
        long due = policy.quietSince() + RideAutoStopPolicy.QUIET_LONG_MS;
        RideStore.Summary s = awaitStopped(policy.rideId, due + 4000, "Phone Bluetooth off ride ended by the tick");
        check(RideStore.STOP_AUTO_QUIET.equals(s.stopReason) && s.automaticStop && s.stopPhoneBluetoothOff && s.stopDisconnectCode == -1, "Quiet stop marked phone Bluetooth off");
        await(() -> ("Phone Bluetooth off · Phone Bluetooth stayed off. Ended at " + clock(s.shownEndWallMs) + ", its last reading.").equals(noticeText()), "Phone Bluetooth notice: " + noticeText());
    }

    /** A walking power-off, then an authenticated reconnect at 40 s: no end at 60 s; the rider's Stop ends it. */
    private void reconnectKeepsRecording() throws Exception {
        RideAutoStopPolicy policy = liveRide("Short drop", 120000, 12000, 30, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(policy.quiet() == RideAutoStopPolicy.Quiet.SLOW, "Walking pace takes the 1 min countdown");
        long last = policy.quietSince();
        while (SystemClock.elapsedRealtime() < last + 40000) SystemClock.sleep(200);
        // The authenticated reconnect the transport would report (the emulator has no scooter to reconnect to).
        main(() -> { long now = SystemClock.elapsedRealtime(); policy.connected(now, profileId, SERIAL, 2, now, now, 0, 0); check(!policy.armed(), "Reconnect cancels the countdown"); });
        while (SystemClock.elapsedRealtime() < last + 66000) SystemClock.sleep(200);
        check(activity.service().isRecording() && policy.quiet() != RideAutoStopPolicy.Quiet.SLOW, "Still recording 66 s after the first loss: the reconnect kept the ride");
        main(() -> activity.service().stopRide());
        RideStore.Summary s = activity.service().rides.get(policy.rideId);
        check(!activity.service().isRecording() && !s.automaticStop && "".equals(s.stopReason), "The rider's Stop ends it as before");
    }

    /** Walking at 3 km/h, then silence: the tick ends the ride ~60 s after the last frame through stopRide, and the copy hook runs. */
    private String walkingPowerOff() throws Exception {
        Context context = getTargetContext(); SharedPreferences settings = context.getSharedPreferences("app_settings", 0);
        Class<?> folder = Class.forName("com.jjricks.volt.RideAutoExportFolder");
        call(folder, null, "set", new Class<?>[]{Context.class, SharedPreferences.class, String.class, boolean.class}, context, settings, "ride_auto_export_json", true);
        RideAutoStopPolicy policy = liveRide("Walk to the rack", 180000, 12000, 30, RideAutoStopPolicy.Loss.RECOVERABLE);
        call(folder, null, "queue", new Class<?>[]{SharedPreferences.class, String.class}, settings, policy.rideId);
        check(!settings.contains("ride_auto_export_problem"), "No automatic copy has run yet");
        long last = policy.quietSince();
        check(policy.quiet() == RideAutoStopPolicy.Quiet.SLOW && policy.remainingMs(SystemClock.elapsedRealtime()) <= 49000, "1 min countdown from the last frame");
        while (SystemClock.elapsedRealtime() < last + 58000) SystemClock.sleep(200);
        check(activity.service().isRecording(), "Still recording 58 s after the last frame");
        long stopWall = System.currentTimeMillis();
        RideStore.Summary s = awaitStopped(policy.rideId, last + 64000, "Ended by the tick within 64 s");
        long after = s.endWallMs - (s.shownEndWallMs);
        check(RideStore.STOP_AUTO_QUIET.equals(s.stopReason) && s.automaticStop && !s.stopPhoneBluetoothOff, "Went-quiet stop token");
        check(s.stopLastReadingMonotonicMs > 0 && s.stopLastReadingMonotonicMs <= last + RideStore.MAX_SENSOR_AGE_MS, "Last reading saved");
        check(s.shownEndWallMs < s.endWallMs && s.shownElapsedMs < s.elapsedMs && s.shownGapCount == 0 && s.gapCount >= 1, "Shown end is the last reading, raw end kept");
        check(after >= 56000 && after <= 64000 && s.endWallMs >= stopWall, "Saved about a minute after the last reading: " + after + " ms");
        check(s.toJson().contains("\"stopReason\":\"automatic_quiet_disconnect\""), "Analysis JSON names the stop");
        String expected = "Walk to the rack · Scooter turned off or out of range. Ended at " + clock(s.shownEndWallMs) + ", its last reading.";
        await(() -> expected.equals(noticeText()), "Quiet notice names the ride, the reason and the shown end: " + noticeText());
        Notification n = notice(context.getSystemService(NotificationManager.class)).getNotification();
        check("Ride saved automatically".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE)) && expected.contentEquals(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)), "Title and expanded text");
        // stopRide's rideFullySaved → startRideAutoExport ran: the ride's JSON needs a folder and none is chosen, so the pass
        // leaves the ride waiting with its problem.
        await(() -> settings.contains("ride_auto_export_problem") && settings.getString("ride_auto_export_pending", "").contains(policy.rideId), "Automatic ride copy ran after the automatic end");
        call(folder, null, "set", new Class<?>[]{Context.class, SharedPreferences.class, String.class, boolean.class}, context, settings, "ride_auto_export_json", false);
        return policy.rideId;
    }

    private void quietDetails(String id) throws Exception {
        main(() -> invoke(activity, "select", new Class[]{String.class}, "rides")); settle();
        RidesScreen screen = (RidesScreen) field(activity, "screen"); boolean wide = (boolean) field(screen, "wide");
        main(() -> invoke(screen, wide ? "selectRide" : "showDetails", new Class[]{String.class}, id)); settle();
        TextView pill = text(rideRoot(screen), "Saved automatically"); check(pill != null, "Quiet ride shows Saved automatically");
        check("Saved automatically after the scooter turned off or went out of range".contentEquals(pill.getContentDescription()), "Pill names the quiet reason for screen readers");
        main(() -> pill.requestRectangleOnScreen(new Rect(0, -activity.ui().dp(160), pill.getWidth(), pill.getHeight() + activity.ui().dp(120)), true)); settle();
        fullyShown(pill, "Quiet ride pill");
        screenshot("details-quiet");
        if (!wide) { main(() -> { List<?> dialogs = (List<?>) field(screen, "dialogs"); for (Object d : new ArrayList<>(dialogs)) ((AlertDialog) d).dismiss(); }); settle(); }
        screenshot("history-quiet");
    }
    private static void set(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new IllegalStateException(name, e); } }

    private void fullyShown(View view, String what) {
        Rect r = new Rect(); check(view.isShown() && view.getGlobalVisibleRect(r) && r.width() == view.getWidth() && r.height() == view.getHeight(), what + " fully visible, not clipped");
        if (view instanceof TextView) { Layout layout = ((TextView) view).getLayout(); check(layout != null && layout.getEllipsisCount(layout.getLineCount() - 1) == 0 && layout.getHeight() <= view.getHeight() - view.getPaddingTop() - view.getPaddingBottom() + 1, what + " text not ellipsized or cut"); }
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "auto-stop-validation"); dir.mkdirs();
        File file = new File(dir, args.getString("variant", "auto-stop") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.writeWithNotifications(this, file);
    }
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private void await(BooleanSupplier test, String why) { long end = SystemClock.elapsedRealtime() + 15000; while (SystemClock.elapsedRealtime() < end) { if (test.getAsBoolean()) { checks++; return; } SystemClock.sleep(50); } throw new AssertionError(why); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(350); waitForIdleSync(); }
    private void main(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } }); if (failure.get() != null) throw new IllegalStateException(failure.get()); }
    private static TextView text(View view, String label) { if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isShown()) return (TextView) view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView r = text(((ViewGroup) view).getChildAt(i), label); if (r != null) return r; } return null; }
    private static <T> T find(View view, Class<T> type) { if (type.isInstance(view)) return type.cast(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { T r = find(((ViewGroup) view).getChildAt(i), type); if (r != null) return r; } return null; }
    private static Object field(Object target, String name) { try { Class<?> c = target.getClass(); while (c != null) { try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static Object call(Class<?> type, Object object, String name, Class<?>[] types, Object... values) throws Exception { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); try { return m.invoke(object, values); } catch (InvocationTargetException e) { if (e.getCause() instanceof Exception) throw (Exception) e.getCause(); throw (Error) e.getCause(); } }
}
