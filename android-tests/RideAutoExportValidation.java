package com.jjricks.volt.tests;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Rect;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator only. Automatic ride copies (RideAutoExport) end to end: the folder picker
 * activity driving the real system picker (a new Download/VoltRides folder, a cancelled pick, choosing the
 * folder again), synthetic phone recordings stopped from the notification with Volt hidden and the screen
 * off, one Everything (JSON) file per ride (GPS, motion only, no phone recording), byte for byte the sheet's
 * Everything export and the manual export through the real save picker, re-export without duplicates, the
 * update from the two old switches (either on means on, waiting rides saved as Everything, old files left
 * alone), the keep-alive and maintenance block while a copy runs, and the failure paths: folder deleted (retried
 * on the next ride end and at app start), grant revoked (retried after choosing the folder again), a restored
 * setting with no folder, and turning it off. Never connects Bluetooth. run-ride-auto-export.py runs it.
 */
public final class RideAutoExportValidation extends Instrumentation {
    private static final String FOLDER_NAME = "VoltRides", FOLDER_PATH = "/sdcard/Download/" + FOLDER_NAME;
    /** ON is the one switch (the old ride JSON switch's key); LEGACY the retired Route CSV switch, still read as on. */
    private static final String LEGACY = "ride_auto_export_route_csv", ON = "ride_auto_export_json", FOLDER = "ride_auto_export_folder",
            PENDING = "ride_auto_export_pending", PROBLEM = "ride_auto_export_problem";
    private Bundle args; private MainActivity activity; private int checks; private String profileId;
    private final List<String> screenshots = new ArrayList<>(), notes = new ArrayList<>();
    private final Map<String, String> rides = new HashMap<>();
    private SharedPreferences settings;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Refusing physical phone");
            for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents"}) check(context.getSharedPreferences(name, 0).getAll().isEmpty(), "Requires empty preferences");
            check(context.getFilesDir().list().length == 0 && context.databaseList().length == 0, "Requires empty private data");
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter(); check(adapter == null || !adapter.isEnabled(), "Bluetooth must be off");
            check(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 0, "Runner grants location for the synthetic GPS route");
            check(context.getContentResolver().getPersistedUriPermissions().isEmpty(), "No folder grants yet");
            shell("rm -rf " + FOLDER_PATH + " /sdcard/Download/ride-*");
            settings = context.getSharedPreferences("app_settings", 0);
            trackPickers();
            settings.edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("map_basemap_enabled", false)
                    .putBoolean("phone_gps", true).putBoolean("phone_motion", true).putString("theme", "dark").commit();
            AtomicReference<String> created = new AtomicReference<>();
            main(() -> created.set(new ScooterProfiles(context).create("Synthetic export scooter", "A1B2C3D4E5F6", 0).id)); profileId = created.get();
            launch();

            offByDefault();
            cancelledPickerChangesNothing();
            chooseNewFolder();
            hiddenStopFromNotification();
            everyKindOfRide();
            hiddenStopWithoutRecorder();
            manualExportsMatch();
            reExportNoDuplicate();
            updatedFromOldSwitches();
            neverDuringMaintenance();
            folderDeletedRetriedOnNextRide();
            folderDeletedRetriedAtAppStart();
            grantRevokedChooseAgain();
            restoredWithoutFolder();
            turnedOff();
            uiRowsOff();
            uiPickerFromRow();
            uiHookStops();
            uiFailureLineAndNotice();
            uiRestoredWithoutFolder();
            uiNarrowAndLargeText();
            uiOffFromRow();
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or recording left running");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots)); result.putStringArrayList("notes", new ArrayList<>(notes));
            try { shell("input keyevent 224"); } catch (Throwable ignored) {}
            try { shell("wm size reset"); shell("cmd statusbar collapse"); } catch (Throwable ignored) {}
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null, 15000, "Service ready");
        settle();
    }

    // ---- Off by default, and the picker activity ---------------------------------------------------

    private void offByDefault() {
        check(!settings.contains(ON) && !settings.contains(LEGACY) && !settings.contains(FOLDER) && !settings.contains(PENDING), "Absent on a fresh install, so off");
        String id = recordRide("Before turning on", true); stopOnly(null); awaitPassDone(); rideSaved(id);
        check(!settings.contains(PENDING) && !shellText("ls /sdcard/Download/").contains("ride-"), "Off: a finished ride is never copied");
    }

    /** RideAutoExportPickerActivity's intent, as the settings rows start it: the switch (turnOn) or the folder row. */
    private void openPicker(boolean turnOn) {
        Intent picker = new Intent(activity, RideAutoExportPickerActivity.class).putExtra("turn_on", turnOn);
        main(() -> activity.startActivity(picker));
    }

    private void cancelledPickerChangesNothing() {
        openPicker(true);
        node("USE THIS FOLDER", 15000); SystemClock.sleep(800);
        for (int i = 0; i < 4 && pickerShown(); i++) { shell("input keyevent 4"); SystemClock.sleep(1200); }
        awaitApp();
        check(!settings.getBoolean(ON, false) && !settings.contains(FOLDER), "Cancelled picker leaves auto-save off and stores no folder");
        check(getTargetContext().getContentResolver().getPersistedUriPermissions().isEmpty(), "No grant kept after cancel");
        check(!pickerActivityShown(), "The picker activity finished with the picker");
    }

    /** Through the real picker: Download, New folder "VoltRides", Use this folder, Allow. */
    private void chooseNewFolder() throws Exception {
        openPicker(true);
        node("USE THIS FOLDER", 15000); SystemClock.sleep(800);
        tap(node("Download", 10000)); node("Files in Download", 10000); SystemClock.sleep(500);
        tap(nodeByDescription("New folder", 10000));
        AccessibilityNodeInfo name = node("Folder name", 10000); tap(name); SystemClock.sleep(600);
        shell("input text " + FOLDER_NAME); SystemClock.sleep(600);
        screenshot("picker-1-new-folder");
        tap(node("OK", 10000));
        node("Files in " + FOLDER_NAME, 10000); SystemClock.sleep(800);
        screenshot("picker-2-use-this-folder");
        tap(node("USE THIS FOLDER", 10000));
        AccessibilityNodeInfo allow = node("ALLOW", 10000); SystemClock.sleep(500);
        screenshot("picker-3-allow");
        tap(allow); awaitApp();
        await(() -> settings.getBoolean(ON, false) && settings.contains(FOLDER), 10000, "Picked folder stored and auto-save on");
        check(settings.contains(LEGACY) && !settings.getBoolean(LEGACY, true), "The retired Route CSV switch is written off");
        Uri tree = Uri.parse(settings.getString(FOLDER, ""));
        check(granted(tree), "Persisted read/write grant kept for " + tree);
        check(DocumentsContract.getTreeDocumentId(tree).endsWith(":Download/" + FOLDER_NAME), "Tree is the new folder: " + tree);
        check(!pickerActivityShown(), "The picker activity finished");
    }

    // ---- Rides -------------------------------------------------------------------------------------

    /** The PhoneRideValidation fixture: a journal below the BLE-authenticated Start, the real recorder and synthetic fixes. */
    private String recordRide(String label, boolean gps) { return recordRide(label, gps, true); }
    private String recordRide(String label, boolean gps, boolean motion) {
        settings.edit().putBoolean("phone_gps", gps).putBoolean("phone_motion", motion).commit();
        ScooterService service = activity.service();
        AtomicReference<String> id = new AtomicReference<>();
        main(() -> { try { id.set(service.rides.start(profileId, label, System.currentTimeMillis(), SystemClock.elapsedRealtime())); } catch (Exception e) { throw new IllegalStateException(e); } });
        main(() -> method(service, "applyWorkPolicy", new Class<?>[]{boolean.class}, false));
        PhoneRideRecorder recorder = (PhoneRideRecorder) field(service, "phoneRecorder");
        check(recorder != null, "Phone recorder started");
        await(() -> recorder.active() && !recorder.status().startsWith("Starting phone sensors"), 8000, "Phone capture started");
        check(recorder.locationActive() == gps, "GPS registration follows the fixture: " + gps);
        if (gps) injectLocations(recorder);
        SystemClock.sleep(motion ? 2500 : 1200);
        settings.edit().putBoolean("phone_gps", true).putBoolean("phone_motion", true).commit();
        return id.get();
    }
    private void injectLocations(PhoneRideRecorder recorder) {
        Handler worker = (Handler) field(recorder, "worker");
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        worker.post(() -> {
            try {
                long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                for (int i = 0; i < 5; i++) {
                    Location l = new Location("gps"); l.setLatitude(40.7608 + i * 0.0001); l.setLongitude(-111.8910);
                    l.setAccuracy(3); l.setSpeed(5 + i); l.setSpeedAccuracyMetersPerSecond(0.2f); l.setAltitude(1300); l.setVerticalAccuracyMeters(2);
                    l.setTime(wall - 8000 + i * 2000); l.setElapsedRealtimeNanos((now - 8000 + i * 2000) * 1000000L);
                    recorder.onLocationChanged(l);
                }
            } catch (Throwable e) { failure.set(e); } finally { done.countDown(); }
        });
        try { check(done.await(5, TimeUnit.SECONDS) && failure.get() == null, "Synthetic fixes delivered"); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
    /**
     * Queues the active ride for an automatic copy, as ScooterService.stopRide's hook does, then ends it with
     * stopOnly. Queueing it here as well is harmless (a ride waits once) and lets this run without the hook.
     */
    private boolean stopFor(String reason) {
        String id = activity.service().rides.activeId(); check(id != null, "A ride is recording");
        main(() -> queueRide(id));
        return stopOnly(reason);
    }
    /** Ends the active ride through ScooterService.stopRide: the rider's stop (reason null) or an automatic one. */
    private boolean stopOnly(String reason) {
        ScooterService service = activity.service();
        check(service.rides.activeId() != null, "A ride is recording");
        AtomicReference<Boolean> saved = new AtomicReference<>();
        main(() -> {
            if (reason == null) { service.stopRide(); saved.set(!service.isRecording()); }
            else saved.set((Boolean) method(service, "stopRide", new Class<?>[]{String.class}, reason));
        });
        check(Boolean.TRUE.equals(saved.get()), "stopRide saved the ride");
        return saved.get();
    }
    private void queueRide(String id) { folderClass("queue", new Class<?>[]{SharedPreferences.class, String.class}, settings, id); }
    private void setOn(boolean on) { main(() -> folderClass("set", new Class<?>[]{Context.class, SharedPreferences.class, boolean.class}, getTargetContext(), settings, on)); settle(); }
    private boolean busy() { return (Boolean) method(activity.service(), "rideAutoExportBusy", new Class<?>[0]); }
    private void awaitPassDone() {
        await(() -> { AtomicReference<Boolean> idle = new AtomicReference<>(); main(() -> idle.set(!activity.service().phoneSaving() && !busy())); return idle.get(); }, 25000, "Phone save and export pass finished");
        settle();
    }
    private void rideSaved(String id) {
        try {
            RideStore.Summary s = activity.service().rides.get(id);
            check(s != null && !s.active && !activity.service().isRecording(), "Ride " + id + " saved and stopped");
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static String shortId(String id) { return id.substring(0, 8); }
    private static String everythingName(String id) { return "ride-" + shortId(id) + "-everything.json"; }
    /**
     * The sheet's Everything in one JSON for the ride, as RidesScreen.exportSheet and saveLater make it: the analysis, the
     * phone recording when it has files (PhoneRideStore.load), RideExportFiles.payload into the cache, and Volt's
     * version. RideExportFiles is package-private in the app, so through reflection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String expectedEverything(String id) {
        try {
            ScooterService service = activity.service(); PhoneRideStore phone = service.phoneRides;
            RideStore.Summary summary = service.rides.get(id);
            PhoneRideStore.Track track = phone != null && (phone.rawCsv(id).isFile() || phone.motionCsv(id).isFile()) ? phone.load(id) : null;
            Context context = getTargetContext();
            String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            Class<?> files = Class.forName("com.jjricks.volt.RideExportFiles"), format = Class.forName("com.jjricks.volt.RideExportFiles$Format");
            Method payload = files.getDeclaredMethod("payload", format, String.class, RideStore.class, RideStore.Summary.class, PhoneRideStore.class,
                    PhoneRideStore.Track.class, File.class, String.class);
            payload.setAccessible(true);
            Object made = payload.invoke(null, Enum.valueOf((Class) format, "EVERYTHING_JSON"), id, service.rides, summary, phone, track, context.getCacheDir(), version);
            try { return new String((byte[]) method(made, "bytes", new Class<?>[0]), StandardCharsets.UTF_8); }
            finally { method(made, "discard", new Class<?>[0]); }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** The folder holds the ride's Everything file, byte for byte the sheet's, and no other file for it. */
    private void savedAsEverything(String id, String what) {
        String text = folderFile(everythingName(id));
        check(text.startsWith("{\"format\":\"volt-ride-everything\",\"formatVersion\":1,\"rideId\":\"" + id + "\""), what + ": the Everything format for this ride");
        check(text.equals(expectedEverything(id)), what + ": byte for byte the sheet's Everything in one JSON");
        for (String name : listing()) check(!name.startsWith("ride-" + shortId(id)) || name.equals(everythingName(id)), what + ": only the Everything file, found " + name);
    }
    /** A file the old switches would have left, written through Volt's folder grant (the shell can't redirect). */
    private void writeToFolder(String name, String text) {
        Uri tree = Uri.parse(settings.getString(FOLDER, ""));
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try {
            Uri file = DocumentsContract.createDocument(getTargetContext().getContentResolver(), parent, "application/octet-stream", name);
            check(file != null, "Created " + name);
            try (OutputStream out = getTargetContext().getContentResolver().openOutputStream(file, "wt")) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        } catch (IOException e) { throw new IllegalStateException(e); }
        check(listing().contains(name), "Old file named exactly " + name + ": " + listing());
    }
    private String folderFile(String name) { return shellText("cat " + FOLDER_PATH + "/" + name); }

    // ---- Hidden stop, keep-alive -------------------------------------------------------------------

    /** Samples the service on the main thread every 5 ms while it runs: busy, work allowed, foreground and maintenance. */
    private final class Watch implements Runnable {
        final Handler handler = new Handler(Looper.getMainLooper());
        int busySamples, notAllowed, notForeground, maintenanceOpen; boolean stopped;
        @Override public void run() {
            if (stopped) return;
            ScooterService service = ScooterService.class.cast(method(ScooterService.class, null, "currentForMaintenance"));
            if (service != null && (Boolean) method(service, "rideAutoExportBusy", new Class<?>[0])) {
                busySamples++;
                if (!service.workAllowed()) notAllowed++;
                if (!(Boolean) field(service, "foregroundStarted")) notForeground++;
                if (service.dataMaintenanceUnavailable() == null) maintenanceOpen++;
            }
            handler.postDelayed(this, 5);
        }
        Watch begin() { main(() -> handler.post(this)); return this; }
        void end() { main(() -> { stopped = true; handler.removeCallbacks(this); }); }
    }

    private void hide() { main(() -> activity.moveTaskToBack(true)); await(() -> !activity.service().isVisible(), 5000, "Activity in background"); }
    private void screenOff() {
        shell("input keyevent 223"); SystemClock.sleep(1500);
        check(!getTargetContext().getSystemService(PowerManager.class).isInteractive(), "Screen off");
    }
    private void screenOnAndShow() {
        shell("input keyevent 224"); shell("wm dismiss-keyguard"); SystemClock.sleep(800);
        main(() -> activity.startActivity(new Intent(activity, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))); settle();
        await(() -> activity.service() != null && activity.service().isVisible(), 10000, "Volt in front again");
    }
    /** An active notification Volt posted, by id, or null. */
    private Notification notice(int id) {
        for (android.service.notification.StatusBarNotification n : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())
            if (n.getId() == id) return n.getNotification();
        return null;
    }
    /** Closes Volt's screen and ends its service, as swiping Volt away with nothing recording does. */
    private void closeVolt() { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); }
    /** Removes one of Volt's notifications, as swiping it away does. */
    private void dismissNotice(int id) { getTargetContext().getSystemService(NotificationManager.class).cancel(id); }
    /** The recording notification's Stop, sent as tapping it does (its PendingIntent); queueFirst as stopFor. */
    private void stopFromNotification(boolean queueFirst) {
        String id = activity.service().rides.activeId(); check(id != null, "A ride is recording");
        await(() -> notice(71) != null && notice(71).actions != null && notice(71).actions.length > 0, 10000, "Recording notification with its Stop action");
        if (queueFirst) main(() -> queueRide(id));
        try { notice(71).actions[0].actionIntent.send(); } catch (PendingIntent.CanceledException e) { throw new IllegalStateException(e); }
        await(() -> !activity.service().isRecording(), 10000, "Notification Stop ended the ride");
    }

    /** Rider's Stop from the notification with Volt in the background and the screen off. */
    private void hiddenStopFromNotification() {
        String id = recordRide("Hidden stop, screen off", true); rides.put("A", id);
        hide(); screenOff();
        Watch watch = new Watch().begin();
        stopFromNotification(true);
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(id)), 25000, "Copied in the background with the screen off");
        awaitPassDone(); watch.end();
        check(!getTargetContext().getSystemService(PowerManager.class).isInteractive(), "The copy finished while the screen stayed off");
        check(watch.busySamples > 0, "The copy was seen running");
        check(watch.notAllowed == 0 && watch.notForeground == 0, "Keep-alive: work stayed allowed and the service in the foreground while copying (" + watch.busySamples + " samples)");
        check(watch.maintenanceOpen == 0, "Backup and restore were refused while copying");
        check(!activity.service().workAllowed(), "Once copied, a hidden idle service stops its work again");
        notes.add("keep-alive samples while copying: " + watch.busySamples);
        screenOnAndShow();
        rideSaved(id); folderClean();
        savedAsEverything(id, "Hidden stop");
        notes.add("hidden stop: " + everythingName(id) + " sha256 " + sha(expectedEverything(id)));
    }

    /** A GPS ride, a motion-only ride and a ride without phone data: each gets its one Everything file. */
    private void everyKindOfRide() {
        String b = recordRide("GPS and motion", true); rides.put("B", b); stopFor(null); awaitPassDone(); rideSaved(b);
        check(listing().contains(everythingName(b)) && settings.getString(PENDING, "").isEmpty(), "Everything in one JSON: " + listing());
        savedAsEverything(b, "GPS ride");
        check(folderFile(everythingName(b)).contains("\"phone\":{\"journal\":"), "The GPS ride's file holds its phone recording");
        String c = recordRide("Motion only", false); stopFor(null); awaitPassDone(); rideSaved(c);
        PhoneRideStore.Track motion; try { motion = activity.service().phoneRides.load(c); } catch (IOException e) { throw new IllegalStateException(e); }
        check(motion.locationCount == 0, "No GPS fixes");
        savedAsEverything(c, "Motion-only ride");
        notes.add("motion-only ride motion samples: " + motion.motionSampleCount);
        String d = recordRide("No phone data", false, false); stopFor(null); awaitPassDone(); rideSaved(d);
        savedAsEverything(d, "Ride without phone data");
        check(settings.getString(PENDING, "").isEmpty() && !settings.contains(PROBLEM), "Nothing waiting, no problem");
        folderClean();
    }

    /**
     * Hidden stop of a ride that never had a phone recorder (phone storage did not open, so phoneRides is null),
     * with auto-save on and the ride waiting. recordRide always starts a real recorder, so this starts the ride itself.
     * The copy's hold must exist when stopRide runs its work policy: otherwise a hidden idle service drops the
     * foreground and stops itself before the copy starts.
     */
    private void hiddenStopWithoutRecorder() {
        ScooterService service = activity.service();
        PhoneRideStore kept = service.phoneRides;
        AtomicReference<String> started = new AtomicReference<>();
        boolean[] atStop = new boolean[5];
        try {
            main(() -> service.phoneRides = null);
            main(() -> { try { started.set(service.rides.start(profileId, "No phone recorder, hidden", System.currentTimeMillis(), SystemClock.elapsedRealtime())); } catch (Exception e) { throw new IllegalStateException(e); } });
            main(() -> method(service, "applyWorkPolicy", new Class<?>[]{boolean.class}, false));
            String id = started.get(); rides.put("N", id);
            check(service.isRecording() && field(service, "phoneRecorder") == null && !service.phoneSaving(), "Recording with no phone recorder at all");
            SystemClock.sleep(1200);
            hide();
            check(settings.getBoolean(ON, false) && !settings.getBoolean("background_connection", true) && service.backgroundDeadlineMs() == 0, "Hidden, auto-save on, no background connection and no idle deadline");
            Watch watch = new Watch().begin();
            main(() -> {
                queueRide(id); service.stopRide();
                atStop[0] = !service.isRecording(); atStop[1] = busy(); atStop[2] = service.workAllowed();
                atStop[3] = (Boolean) field(service, "foregroundStarted"); atStop[4] = (Boolean) field(service, "workActive");
            });
            check(atStop[0], "stopRide saved the ride");
            check(atStop[1], "No recorder: the copy is already running when stopRide returns");
            check(atStop[2] && atStop[3] && atStop[4], "No recorder: work still allowed, foreground and active right after the hidden stop");
            await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(id)), 25000, "No-recorder ride copied while hidden");
            awaitPassDone(); watch.end();
            check(watch.notAllowed == 0 && watch.notForeground == 0, "No recorder keep-alive: work stayed allowed and the service in the foreground while copying (" + watch.busySamples + " samples)");
            check(ScooterService.class.cast(method(ScooterService.class, null, "currentForMaintenance")) == service, "The same service copied the ride; it never stopped first");
            check(!service.workAllowed(), "Once copied, a hidden idle service stops its work again");
            notes.add("no-recorder keep-alive samples while copying: " + watch.busySamples);
            rideSaved(id);
        } finally {
            main(() -> service.phoneRides = kept);
        }
        String id = rides.get("N");
        savedAsEverything(id, "No recorder");
        check(folderFile(everythingName(id)).contains("\"phone\":null") && settings.getString(PENDING, "").isEmpty() && !settings.contains(PROBLEM), "No recorder: no phone part, nothing waiting, no problem");
        notes.add("no-recorder hidden stop: " + everythingName(id) + " sha256 " + sha(expectedEverything(id)));
        screenOnAndShow();
        folderClean();
    }

    /** The real sheet: Export ride data with only Everything in one JSON checked, through Android's save picker into Download. */
    private void manualExportsMatch() {
        String id = rides.get("B");
        manualExport(id, T.ridesExportEverythingJson(), everythingName(id));
        String sums = shellText("sha256sum /sdcard/Download/" + everythingName(id) + " " + FOLDER_PATH + "/" + everythingName(id));
        String[] lines = sums.trim().split("\n");
        check(lines.length == 2 && lines[0].split(" ")[0].equals(lines[1].split(" ")[0]), "Everything copy byte-identical to the manual export:\n" + sums);
        notes.add("manual vs automatic: " + sums.trim().replace('\n', ' '));
        folderClean();
    }
    private void manualExport(String id, String format, String name) {
        main(() -> method(activity, "select", new Class<?>[]{String.class}, "rides")); settle();
        RidesScreen screen = (RidesScreen) field(activity, "screen"); boolean wide = (boolean) field(screen, "wide");
        main(() -> method(screen, wide ? "selectRide" : "showDetails", new Class<?>[]{String.class}, id)); settle();
        // The button's label can step down to "Export" or the icon on a narrow screen; its spoken label stays the full one.
        String export = T.ridesExportData();
        await(() -> byDescription(rideRoot(screen), export) != null, 15000, "Ride details with the Export ride data button");
        int before = ((List<?>) field(screen, "dialogs")).size();
        main(() -> byDescription(rideRoot(screen), export).performClick());
        // Read on the main thread, once the sheet shows: its window's decor is built there (Motion.present), and a
        // getDecorView from this thread between dialogs.add and create races it (AlertController.setupView NPE).
        await(() -> { boolean[] shown = new boolean[1]; main(() -> { List<?> open = (List<?>) field(screen, "dialogs");
            shown[0] = open.size() > before && ((AlertDialog) open.get(open.size() - 1)).isShowing() && text(rideRoot(screen), format) != null; });
            return shown[0]; }, 15000, "Export ride data sheet lists " + format);
        View sheet = rideRoot(screen);
        // Only this format checked, so Export takes the one-file Save dialog.
        main(() -> uncheckAll(sheet));
        main(() -> ((CheckBox) text(sheet, format)).setChecked(true)); settle();
        check(((CheckBox) text(sheet, format)).isChecked() && text(sheet, T.ridesExportHintOne()) != null, "Sheet: only " + format + " checked, one-file hint");
        main(() -> text(sheet, T.ridesExportAction()).performClick());
        node("SAVE", 15000); SystemClock.sleep(800);
        // DocumentsUI may reopen the last folder Volt used; the manual copy goes to Download itself.
        if (findNode("Files in " + FOLDER_NAME) != null || findBreadcrumb(FOLDER_NAME)) { tap(nodeExact("Download", 5000)); SystemClock.sleep(1000); }
        check(findNode(name) != null, "Save picker offers the manual export's name " + name);
        tap(node("SAVE", 5000)); awaitApp();
        await(() -> shellText("ls /sdcard/Download/").contains(name), 15000, "Manual export saved to Download: " + name);
        await(() -> getTargetContext().getSharedPreferences("pending_documents", 0).getAll().isEmpty(), 10000, "Manual save finished");
        closeDialogs(screen);
    }

    private void reExportNoDuplicate() {
        int before = fileCount();
        String b = rides.get("B"); String json = folderFile(everythingName(b));
        settings.edit().putString(PENDING, b).commit();
        main(() -> method(activity.service(), "startRideAutoExport", new Class<?>[0])); awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty() && fileCount() == before && folderClean(), "Re-export of the same ride: no second file, no name (1)");
        check(folderFile(everythingName(b)).equals(json), "Unchanged file left as it was");
    }

    /**
     * After the update: a phone that had only the old Route CSV switch on, with a ride still waiting and the two old
     * switches' files in the folder. Auto-save is on, the waiting ride is saved as Everything, the old files stay.
     */
    private void updatedFromOldSwitches() {
        String a = rides.get("A");
        String route = "ride-" + shortId(a) + "-route.csv", combined = "ride-" + shortId(a) + "-combined.json";
        writeToFolder(route, "old route\n"); writeToFolder(combined, "{\"old\":true}\n");
        shell("rm -f " + FOLDER_PATH + "/" + everythingName(a)); SystemClock.sleep(500);
        String oldSums = shellText("sha256sum " + FOLDER_PATH + "/" + route + " " + FOLDER_PATH + "/" + combined);
        check(oldSums.trim().split("\n").length == 2 && folderFile(route).equals("old route\n"), "Two old switches' files in the folder: " + oldSums);
        settings.edit().putBoolean(LEGACY, true).putBoolean(ON, false).putString(PENDING, a).commit();
        check((Boolean) folderClass("on", new Class<?>[]{SharedPreferences.class}, settings), "Only the old Route CSV switch on: auto-save is on");
        main(() -> method(activity.service(), "startRideAutoExport", new Class<?>[0])); awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty() && !settings.contains(PROBLEM), "The waiting ride was saved: " + exportState());
        check(folderFile(everythingName(a)).equals(expectedEverything(a)), "Saved as the sheet's Everything in one JSON");
        check(shellText("sha256sum " + FOLDER_PATH + "/" + route + " " + FOLDER_PATH + "/" + combined).equals(oldSums), "The old switches' files are left as they were");
        settings.edit().putBoolean(LEGACY, false).putBoolean(ON, true).putString(PENDING, a).commit();
        main(() -> method(activity.service(), "startRideAutoExport", new Class<?>[0])); awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty() && shellText("sha256sum " + FOLDER_PATH + "/" + route + " " + FOLDER_PATH + "/" + combined).equals(oldSums), "Only the old ride JSON switch on: on too, old files still left alone");
        notes.add("old files kept: " + oldSums.trim().replace('\n', ' '));
        shell("rm -f " + FOLDER_PATH + "/" + route + " " + FOLDER_PATH + "/" + combined); SystemClock.sleep(500);
        folderClean();
    }

    /** No pass starts while backup or restore owns app data; the ride waits for the next start. */
    private void neverDuringMaintenance() {
        ScooterService service = activity.service();
        main(() -> { field(service, "dataMaintenance", true); settings.edit().putString(PENDING, rides.get("A")).commit(); method(service, "startRideAutoExport", new Class<?>[0]); });
        check(!busy() && settings.getString(PENDING, "").equals(rides.get("A")), "No copy starts during maintenance; the ride waits");
        main(() -> { field(service, "dataMaintenance", false); method(service, "startRideAutoExport", new Class<?>[0]); });
        awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty(), "Retried once maintenance is over");
    }

    private void folderDeletedRetriedOnNextRide() {
        shell("rm -rf " + FOLDER_PATH); SystemClock.sleep(500);
        check(!shellText("ls /sdcard/Download/").contains(FOLDER_NAME), "Folder deleted");
        String d = recordRide("Folder deleted", true); rides.put("D", d); stopFor(null); awaitPassDone();
        rideSaved(d);
        try { check(activity.service().phoneRides.load(d).locationCount > 0, "The ride and its route are saved in Volt"); } catch (IOException e) { throw new IllegalStateException(e); }
        check(settings.getString(PENDING, "").equals(d) && "folder".equals(settings.getString(PROBLEM, "")), "Waiting, with a folder problem: " + exportState());
        shell("mkdir -p " + FOLDER_PATH); SystemClock.sleep(500);
        String e = recordRide("Folder back", true); stopFor(null); awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty() && !settings.contains(PROBLEM), "Next ride end retried the waiting ride");
        List<String> names = listing(); check(names.contains(everythingName(d)) && names.contains(everythingName(e)), "Both rides: " + names);
        folderClean();
    }

    private void folderDeletedRetriedAtAppStart() {
        shell("rm -rf " + FOLDER_PATH); SystemClock.sleep(500);
        String f = recordRide("Retried at app start", true); stopFor(null); awaitPassDone();
        check(settings.getString(PENDING, "").equals(f), "Waiting after the failure");
        shell("mkdir -p " + FOLDER_PATH); SystemClock.sleep(500);
        closeVolt();
        await(() -> method(ScooterService.class, null, "currentForMaintenance") == null, 15000, "Service ended");
        check(settings.getString(PENDING, "").equals(f), "Still waiting while Volt is closed");
        launch();
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(f)), 25000, "App start retried the waiting ride");
        folderClean();
    }

    private void grantRevokedChooseAgain() {
        Uri tree = Uri.parse(settings.getString(FOLDER, ""));
        getTargetContext().getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        check(!granted(tree), "Grant revoked");
        String g = recordRide("Grant revoked", true); stopFor(null); awaitPassDone(); rideSaved(g);
        check(settings.getString(PENDING, "").equals(g) && "folder".equals(settings.getString(PROBLEM, "")), "Waiting after the revoked grant");
        // Choose the folder again, as the folder row does (the switch stays as it is): the picker opens on it.
        openPicker(false);
        node("Files in " + FOLDER_NAME, 15000); SystemClock.sleep(600);
        tap(node("USE THIS FOLDER", 5000)); tap(node("ALLOW", 10000)); awaitApp();
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(g)), 25000, "Choosing the folder again retried the ride");
        check(granted(tree) && !settings.contains(PROBLEM) && settings.getBoolean(ON, false), "Grant back, problem cleared, still on");
        folderClean();
    }

    /** A full backup restored on another phone brings the switch but no folder or grant. */
    private void restoredWithoutFolder() {
        String kept = settings.getString(FOLDER, "");
        settings.edit().remove(FOLDER).remove(PROBLEM).commit(); settle();
        String h = recordRide("Restored, no folder yet", true); rides.put("H", h); stopFor(null); awaitPassDone(); rideSaved(h);
        check(settings.getString(PENDING, "").equals(h) && "folder".equals(settings.getString(PROBLEM, "")), "Ride waits for a folder");
        settings.edit().putString(FOLDER, kept).commit();
        main(() -> method(activity.service(), "rideAutoExportFolderChosen", new Class<?>[0]));
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(h)), 25000, "Copied once a folder is there");
        folderClean();
    }

    private void turnedOff() {
        settings.edit().putBoolean(LEGACY, true).putString(PENDING, rides.get("A")).commit();
        setOn(false);
        check(!settings.getBoolean(ON, true) && !settings.getBoolean(LEGACY, true) && !settings.contains(FOLDER) && !settings.contains(PENDING) && !settings.contains(PROBLEM), "Off (the old Route CSV switch too) forgets the folder and the waiting list");
        check(getTargetContext().getContentResolver().getPersistedUriPermissions().isEmpty(), "Off releases the folder grant");
        int before = shellText("ls " + FOLDER_PATH).split("\n").length;
        check(before > 0 && shellText("ls " + FOLDER_PATH).contains("-everything.json"), "Files already saved stay in the folder");
        String i = recordRide("After turning off", true); stopFor(null); awaitPassDone(); rideSaved(i);
        check(!settings.contains(PENDING) && !shellText("ls " + FOLDER_PATH).contains(shortId(i)) && shellText("ls " + FOLDER_PATH).split("\n").length == before, "Nothing copied while off");
    }

    // ---- The settings rows, the stop hook and the failure notice (catalog text through T) ----------

    private void uiRowsOff() {
        String title = T.moreAutoSaveJson();
        openDataHistory(title);
        check(!switchOf(title).isChecked(), "The auto-save switch is off");
        check(T.moreAutoSaveJsonSub().contentEquals(subOf(title).getText()), "The switch says what it saves: " + subOf(title).getText());
        check(text(activity.getWindow().getDecorView(), "Auto-save Route CSV") == null, "The old Route CSV switch is gone");
        String folder = T.moreAutoSaveFolder();
        View history = rowTitled(T.moreHistorySave());
        ViewGroup recording = (ViewGroup) history.getParent();
        ViewGroup group = (ViewGroup) rowTitled(title).getParent();
        check(group != recording && rowTitled(folder).getParent() == group, "Its own group: the switch and the folder row");
        check(group.indexOfChild(rowTitled(folder)) > group.indexOfChild(rowTitled(title)), "The switch, then the folder");
        ViewGroup page = (ViewGroup) recording.getParent();
        TextView heading = text(page, T.moreAutoSaveHeading());
        check(heading != null && heading.getParent() == page && page.indexOfChild(recording) < page.indexOfChild(heading) && page.indexOfChild(heading) == page.indexOfChild(group) - 1, "Ride auto-save heading right above the group, below Save readings history");
        TextView storage = text(page, T.moreHistoryLocalStorage());
        check(storage != null && page.indexOfChild(group) < page.indexOfChild((View) storage.getParent().getParent()), "The group sits above the Local storage card");
        TextView note = text(page, T.moreAutoSaveFolderNote());
        check(note != null && page.indexOfChild(note) == page.indexOfChild(group) + 1, "The folder note right below the group");
        check(!rowTitled(folder).isEnabled() && T.moreAutoSaveNoFolder().contentEquals(subOf(folder).getText()), "Off: the folder row stays, greyed out, no folder chosen yet");
        click(rowTitled(folder)); SystemClock.sleep(1200);
        check(!pickerActivityShown() && !pickerShown(), "The greyed folder row opens nothing");
        awaitFullyShown(() -> titleOf(folder), "Folder title");
        awaitFullyShown(() -> subOf(folder), "No folder line");
        fullyShown(titleOf(title), "Switch title"); awaitFullyShown(() -> subOf(title), "Switch caption"); fullyShown(switchOf(title), "Switch");
        check(switchOf(title).getHeight() >= dp(48), "48dp switch target");
        check(title.contentEquals(switchOf(title).getContentDescription()), "The switch speaks its row title");
        screenshot("01-rows-off-dark");
        theme("light"); openDataHistory(title); screenshot("01-rows-off-light"); theme("dark");
    }

    /** In the open folder picker, go to Download/VoltRides (it may already be open there). */
    private void uiPickVoltRides(String shot) {
        node("USE THIS FOLDER", 15000); SystemClock.sleep(800);
        if (findNode("Files in " + FOLDER_NAME) == null) {
            if (findBreadcrumb("Download")) tap(nodeExact("Download", 5000)); else tap(node("Download", 10000));
            node("Files in Download", 10000); SystemClock.sleep(500);
            tap(node(FOLDER_NAME, 10000));
            node("Files in " + FOLDER_NAME, 10000); SystemClock.sleep(800);
        }
        if (shot != null) screenshot(shot);
        tap(node("USE THIS FOLDER", 10000));
        AccessibilityNodeInfo allow = node("ALLOW", 10000); SystemClock.sleep(400);
        if (shot != null) screenshot(shot + "-allow");
        tap(allow); awaitApp();
    }

    /** The switch turned on opens the picker; a cancel leaves it off; the folder row then changes the folder only. */
    private void uiPickerFromRow() {
        String title = T.moreAutoSaveJson();
        openDataHistory(title);
        click(switchOf(title));
        node("USE THIS FOLDER", 15000); SystemClock.sleep(800);
        for (int i = 0; i < 4 && pickerShown(); i++) { shell("input keyevent 4"); SystemClock.sleep(1200); }
        awaitApp(); openDataHistory(title);
        check(!settings.getBoolean(ON, false) && !settings.contains(FOLDER) && !switchOf(title).isChecked(), "Cancelled picker from the row: still off");
        check(T.moreAutoSaveJsonSub().contentEquals(subOf(title).getText()) && T.moreAutoSaveNoFolder().contentEquals(subOf(T.moreAutoSaveFolder()).getText()), "Still no folder");
        click(switchOf(title));
        uiPickVoltRides("02-picker-from-row");
        openDataHistory(title);
        check(settings.getBoolean(ON, false) && !settings.getBoolean(LEGACY, true) && settings.contains(FOLDER), "On once the folder is kept");
        String saving = T.moreAutoSaveSavingTo("Download/" + FOLDER_NAME);
        String folder = T.moreAutoSaveFolder();
        check(switchOf(title).isChecked() && saving.contentEquals(subOf(folder).getText()) && rowTitled(folder).isEnabled(), "The folder row shows the folder: " + subOf(folder).getText());
        check(T.moreAutoSaveJsonSub().contentEquals(subOf(title).getText()), "The switch keeps saying what it saves");
        awaitFullyShown(() -> subOf(folder), "Folder line"); fullyShown(titleOf(title), "Switch title while on");
        screenshot("03-on-dark");
        theme("light"); openDataHistory(title); screenshot("03-on-light"); theme("dark");
        // The folder row changes the folder only: it opens at the current folder and leaves the switch on.
        String kept = settings.getString(FOLDER, "");
        openDataHistory(folder); click(rowTitled(folder));
        node("Files in " + FOLDER_NAME, 15000);
        uiPickVoltRides("04-picker-from-folder-row");
        openDataHistory(folder);
        check(kept.equals(settings.getString(FOLDER, "")) && settings.getBoolean(ON, false) && !settings.contains(PROBLEM), "Folder row: same folder kept, switch as it was");
        check(switchOf(title).isChecked() && saving.contentEquals(subOf(folder).getText()), "Folder row: still on, still saving to the folder");
    }

    /** The stop hook alone (no queue here): the notification Stop while hidden, the parked auto-stop and a revived ride. */
    private void uiHookStops() {
        String a = recordRide("Hook: notification Stop", true);
        hide(); screenOff();
        Watch watch = new Watch().begin();
        stopFromNotification(false);
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(a)), 25000, "The stop hook queued and copied the hidden ride");
        awaitPassDone(); watch.end();
        check(watch.busySamples > 0 && watch.notAllowed == 0 && watch.notForeground == 0 && watch.maintenanceOpen == 0, "Keep-alive and maintenance block during the hooked copy");
        check(notice(78) == null, "A success posts nothing");
        screenOnAndShow();
        savedAsEverything(a, "Hooked copy");
        String p = recordRide("Hook: parked auto-stop", true);
        stopOnly(RideStore.STOP_AUTO_PARKED); awaitPassDone();
        check(listing().contains(everythingName(p)) && settings.getString(PENDING, "").isEmpty(), "The parked auto-stop is copied too");
        String r = recordRide("Hook: revived ride", false, false);
        ScooterService ended = activity.service();
        closeVolt();
        await(() -> (Boolean) field(ended, "destroyed"), 15000, "Service ended with the ride still open");
        launch();
        check(r.equals(activity.service().rides.activeId()), "The ride is revived by the new service");
        stopOnly(null); awaitPassDone();
        check(listing().contains(everythingName(r)) && settings.getString(PENDING, "").isEmpty(), "A revived ride stopped later is copied: " + listing());
        folderClean();
    }

    /** Folder gone: the row's failure line, one notification while hidden, none for a repeat or while visible. */
    private void uiFailureLineAndNotice() {
        String csv = T.moreAutoSaveJson();
        shell("rm -rf " + FOLDER_PATH); SystemClock.sleep(500);
        String f = recordRide("Failure while hidden", true);
        hide();
        stopFromNotification(false); awaitPassDone();
        check("folder".equals(settings.getString(PROBLEM, "")) && settings.getString(PENDING, "").equals(f), "Waiting with a folder problem");
        await(() -> notice(78) != null, 10000, "Failure notification posted while hidden");
        Notification n = notice(78);
        check(T.serviceAutoSaveFailed().contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE)) && T.serviceAutoSaveFolder().contentEquals(n.extras.getCharSequence(Notification.EXTRA_TEXT)), "Notification title and text from the catalog");
        check("scooter".equals(n.getChannelId()), "On the existing scooter channel");
        String nightMode = nightMode();
        shell("cmd uimode night yes"); shell("cmd statusbar expand-notifications"); SystemClock.sleep(1800);
        screenshot("06-failure-notification-dark");
        shell("cmd uimode night no"); SystemClock.sleep(1800);
        screenshot("06-failure-notification-light");
        // Back to the mode Volt was created in while it is hidden, or showing it again relaunches MainActivity and strands `activity`.
        shell("cmd uimode night " + nightMode); SystemClock.sleep(1800);
        shell("cmd statusbar collapse"); SystemClock.sleep(800);
        dismissNotice(78);
        screenOnAndShow();
        String g = recordRide("Second failure, same kind", true);
        hide();
        stopFromNotification(false); awaitPassDone();
        SystemClock.sleep(1500);
        check(notice(78) == null, "At most one notification per failure kind");
        screenOnAndShow();
        openDataHistory(csv);
        String folder = T.moreAutoSaveFolder();
        check(T.moreAutoSaveFolderProblem().contentEquals(subOf(folder).getText()), "Failure line on the folder row: " + subOf(folder).getText());
        check(T.moreAutoSaveJsonSub().contentEquals(subOf(csv).getText()), "The switch keeps its own caption");
        check(switchOf(csv).isChecked(), "The setting never turns itself off");
        awaitFullyShown(() -> subOf(folder), "Failure line");
        screenshot("05-failure-line-dark");
        theme("light"); openDataHistory(csv); screenshot("05-failure-line-light"); theme("dark");
        shell("mkdir -p " + FOLDER_PATH); SystemClock.sleep(500);
        String h = recordRide("Folder back", true); stopOnly(null); awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty() && !settings.contains(PROBLEM), "Retried at the next ride end: " + exportState());
        check(listing().contains(everythingName(f)) && listing().contains(everythingName(g)) && listing().contains(everythingName(h)), "All three rides copied");
        openDataHistory(csv);
        check(T.moreAutoSaveSavingTo("Download/" + FOLDER_NAME).contentEquals(subOf(folder).getText()), "Folder line back");
        shell("rm -rf " + FOLDER_PATH); SystemClock.sleep(500);
        String v = recordRide("Failure while visible", true); stopOnly(null); awaitPassDone();
        check("folder".equals(settings.getString(PROBLEM, "")), "Failed while visible");
        SystemClock.sleep(1500);
        check(notice(78) == null, "No notification while Volt is visible; the row shows it");
        // The same failure on a later hidden ride: the visible one was never posted, so this one is, once.
        String x = recordRide("Same failure while hidden", true);
        hide();
        stopFromNotification(false); awaitPassDone();
        check("folder".equals(settings.getString(PROBLEM, "")), "Still the folder problem");
        await(() -> notice(78) != null, 10000, "Visible failure, then the same failure while hidden: one notification");
        check("FOLDER".equals(String.valueOf(field(activity.service(), "autoExportNotified"))), "The service keeps the kind it posted");
        dismissNotice(78);
        screenOnAndShow();
        String y = recordRide("Same failure, hidden again", true);
        hide();
        stopFromNotification(false); awaitPassDone();
        SystemClock.sleep(1500);
        check(notice(78) == null, "Posted once: no repeat on the next hidden ride");
        check("FOLDER".equals(String.valueOf(field(activity.service(), "autoExportNotified"))), "The service keeps the posted kind through the repeat");
        screenOnAndShow();
        shell("mkdir -p " + FOLDER_PATH); SystemClock.sleep(500);
        String w = recordRide("Folder back again", false, false); stopOnly(null); awaitPassDone();
        check(listing().contains(everythingName(v)) && listing().contains(everythingName(w)) && !settings.contains(PROBLEM), "Both copied after the folder came back");
        check(listing().contains(everythingName(x)) && listing().contains(everythingName(y)), "The hidden rides waiting with it are copied too");
        check("NONE".equals(String.valueOf(field(activity.service(), "autoExportNotified"))), "A success forgets the posted kind");
        folderClean();
    }

    /**
     * An older backup restored: only its Route CSV switch on (the update maps either old switch to on), no folder on this
     * phone. The switch shows on; rides wait; choosing a folder from the row copies them as Everything.
     */
    private void uiRestoredWithoutFolder() {
        String title = T.moreAutoSaveJson();
        Uri tree = Uri.parse(settings.getString(FOLDER, ""));
        getTargetContext().getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        settings.edit().remove(FOLDER).remove(PROBLEM).putBoolean(LEGACY, true).putBoolean(ON, false).commit();
        openDataHistory(title);
        check(switchOf(title).isChecked(), "An older backup with only Route CSV on shows auto-save on");
        String folder = T.moreAutoSaveFolder();
        check(T.moreAutoSaveChooseFolder().contentEquals(subOf(folder).getText()) && rowTitled(folder).isEnabled(), "Choose a folder to start saving: " + subOf(folder).getText());
        screenshot("07-restored-no-folder-dark");
        theme("light"); openDataHistory(title); screenshot("07-restored-no-folder-light"); theme("dark");
        String r = recordRide("Restored, waiting", true); stopOnly(null); awaitPassDone();
        check(settings.getString(PENDING, "").equals(r), "The ride waits for a folder");
        openDataHistory(title);
        check(T.moreAutoSaveChooseFolder().contentEquals(subOf(folder).getText()), "Still asks for a folder after a waiting ride");
        click(rowTitled(folder));
        uiPickVoltRides(null);
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(everythingName(r)), 25000, "Choosing a folder from the row copied the waiting ride");
        check(folderFile(everythingName(r)).equals(expectedEverything(r)), "Saved as Everything");
        openDataHistory(title);
        check(T.moreAutoSaveSavingTo("Download/" + FOLDER_NAME).contentEquals(subOf(folder).getText()) && switchOf(title).isChecked(), "Saving again, still on");
        folderClean();
    }

    /** About 327dp wide and Volt's text at 130%: titles and folder line wrap, nothing clipped. */
    private void uiNarrowAndLargeText() {
        String title = T.moreAutoSaveJson();
        shell("wm size 900x2340"); SystemClock.sleep(1500); textScale(1.3f);
        openDataHistory(title);
        fullyShown(titleOf(title), "Narrow switch title");
        String folder = T.moreAutoSaveFolder();
        awaitFullyShown(() -> subOf(title), "Narrow switch caption");
        awaitFullyShown(() -> titleOf(folder), "Narrow folder title"); awaitFullyShown(() -> subOf(folder), "Narrow folder line");
        fullyShown(switchOf(title), "Narrow switch");
        awaitFullyShown(() -> text(activity.getWindow().getDecorView(), T.moreAutoSaveHeading()), "Narrow heading");
        awaitFullyShown(() -> text(activity.getWindow().getDecorView(), T.moreAutoSaveFolderNote()), "Narrow folder note");
        Rect titleRect = new Rect(); Rect toggle = new Rect(); titleOf(title).getGlobalVisibleRect(titleRect); switchOf(title).getGlobalVisibleRect(toggle);
        Rect caption = new Rect(); subOf(title).getGlobalVisibleRect(caption);
        check(titleRect.right <= toggle.left && caption.right <= toggle.left, "Title and caption never run under the switch");
        screenshot("08-narrow-large-text-dark");
        theme("light"); openDataHistory(title); screenshot("08-narrow-large-text-light"); theme("dark");
        textScale(1f); shell("wm size reset"); SystemClock.sleep(1500); settle();
    }

    private void uiOffFromRow() {
        String title = T.moreAutoSaveJson();
        openDataHistory(title);
        String folder = T.moreAutoSaveFolder();
        check(switchOf(title).isChecked() && T.moreAutoSaveSavingTo("Download/" + FOLDER_NAME).contentEquals(subOf(folder).getText()) && rowTitled(folder).isEnabled(), "On: the folder row shows the folder");
        Rect before = new Rect();
        rowTitled(folder).getGlobalVisibleRect(before);
        int beforeHeight = rowTitled(folder).getHeight();
        click(switchOf(title));
        openDataHistory(title);
        check(!settings.getBoolean(ON, true) && !settings.contains(FOLDER) && !settings.contains(PENDING) && getTargetContext().getContentResolver().getPersistedUriPermissions().isEmpty(), "Off from the row: grant released, list cleared");
        check(!switchOf(title).isChecked(), "The switch is off");
        check(!rowTitled(folder).isEnabled() && T.moreAutoSaveNoFolder().contentEquals(subOf(folder).getText()), "Folder row greyed out again, no folder chosen yet");
        Rect after = new Rect();
        rowTitled(folder).getGlobalVisibleRect(after);
        check(after.top == before.top && rowTitled(folder).getHeight() == beforeHeight, "The folder row stays in place: the layout does not jump (" + before + " -> " + after + ")");
        screenshot("09-off-again");
    }

    // ---- Folder checks ---------------------------------------------------------------------------

    /** Names in the chosen folder, read through the app's own grant. */
    private List<String> listing() {
        List<String> names = new ArrayList<>();
        String stored = settings.getString(FOLDER, null); if (stored == null) return names;
        Uri tree = Uri.parse(stored);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = getTargetContext().getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) names.add(c.getString(0));
        } catch (RuntimeException gone) { }
        return names;
    }
    /** No temporary file, no "(1)" copy, and only finished ride files, checked from the shell (hidden files included). */
    private boolean folderClean() {
        String all = shellText("ls -a " + FOLDER_PATH);
        for (String name : all.split("\n")) {
            name = name.trim(); if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
            check(name.matches("ride-[0-9a-f]{8}-everything\\.json"), "Only finished Everything files in the folder, found: " + name);
        }
        return true;
    }
    private int fileCount() { int n = 0; for (String name : shellText("ls -a " + FOLDER_PATH).split("\n")) if (name.trim().startsWith("ride-")) n++; return n; }
    private boolean granted(Uri tree) {
        for (UriPermission p : getTargetContext().getContentResolver().getPersistedUriPermissions()) if (p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission()) return true;
        return false;
    }

    // ---- Other apps' windows (DocumentsUI) through UiAutomation ------------------------------------

    private AccessibilityNodeInfo findNode(String text) {
        for (AccessibilityNodeInfo root : roots()) { AccessibilityNodeInfo hit = exact(root, text, false); if (hit != null) return hit; }
        return null;
    }
    private boolean findBreadcrumb(String text) {
        for (AccessibilityNodeInfo root : roots()) for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId("com.google.android.documentsui:id/breadcrumb_text")) if (n.getText() != null && text.contentEquals(n.getText())) return true;
        return false;
    }
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try { for (android.view.accessibility.AccessibilityWindowInfo w : getUiAutomation().getWindows()) if (w.getRoot() != null) roots.add(w.getRoot()); } catch (RuntimeException ignored) { }
        AccessibilityNodeInfo active = getUiAutomation().getRootInActiveWindow(); if (active != null) roots.add(active);
        return roots;
    }
    private static AccessibilityNodeInfo exact(AccessibilityNodeInfo node, String text, boolean description) {
        if (node == null) return null;
        CharSequence value = description ? node.getContentDescription() : node.getText();
        if (value != null && text.contentEquals(value) && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo hit = exact(node.getChild(i), text, description); if (hit != null) return hit; }
        return null;
    }
    private AccessibilityNodeInfo node(String text, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) { AccessibilityNodeInfo n = findNode(text); if (n != null) { checks++; return n; } SystemClock.sleep(200); }
        throw new AssertionError("Picker text not shown: " + text);
    }
    /** Prefers a breadcrumb of the same text. */
    private AccessibilityNodeInfo nodeExact(String text, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            for (AccessibilityNodeInfo root : roots()) for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId("com.google.android.documentsui:id/breadcrumb_text"))
                if (n.getText() != null && text.contentEquals(n.getText()) && n.isVisibleToUser()) { checks++; return n; }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Breadcrumb not shown: " + text);
    }
    private AccessibilityNodeInfo nodeByDescription(String text, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            for (AccessibilityNodeInfo root : roots()) { AccessibilityNodeInfo hit = exact(root, text, true); if (hit != null) { checks++; return hit; } }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Picker button not shown: " + text);
    }
    private void tap(AccessibilityNodeInfo node) { Rect r = new Rect(); node.getBoundsInScreen(r); shell("input tap " + r.centerX() + " " + r.centerY()); SystemClock.sleep(700); }
    /** The picker's windows have closed, so Volt (or one of its dialogs) is in front again. */
    private boolean pickerShown() { for (AccessibilityNodeInfo root : roots()) if (root.getPackageName() != null && root.getPackageName().toString().contains("documentsui")) return true; return false; }
    /** Live RideAutoExportPickerActivity instances, counted through the app's lifecycle callbacks (installed in onStart). */
    private final Set<Activity> pickers = Collections.synchronizedSet(new HashSet<>());
    private boolean pickerActivityShown() { return !pickers.isEmpty(); }
    private void trackPickers() {
        ((Application) getTargetContext().getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { if (a instanceof RideAutoExportPickerActivity) pickers.add(a); }
            @Override public void onActivityDestroyed(Activity a) { pickers.remove(a); }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        });
    }
    private void awaitApp() {
        await(() -> !pickerShown(), 15000, "Back in Volt");
        await(() -> !pickerActivityShown(), 10000, "Picker activity finished");
        settle();
    }

    private String shellText(String command) {
        try (ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(command, e); }
    }
    private void shell(String command) { shellText(command); }
    /** The emulator's night mode as `cmd uimode night` reports and accepts it: yes, no, auto or a custom mode. */
    private String nightMode() {
        String output = shellText("cmd uimode night");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Night mode: (\\w+)").matcher(output);
        check(m.find(), "Night mode readable: " + output);
        return m.group(1);
    }
    private static String sha(String text) {
        try { StringBuilder b = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x)); return b.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---- The settings row (MoreScreen, Data & history) ------------------------------------------

    /** More, Data & history, scrolled so the named row is centred. */
    private void openDataHistory(String rowTitle) {
        main(() -> { method(activity, "select", new Class<?>[]{String.class}, "more"); method(field(activity, "screen"), "selectSection", new Class<?>[]{String.class}, "Data"); }); settle();
        TextView title = text(activity.getWindow().getDecorView(), rowTitle); check(title != null, "Row present: " + rowTitle);
        centre((View) title.getParent().getParent());
    }
    private View rowTitled(String rowTitle) { TextView title = text(activity.getWindow().getDecorView(), rowTitle); check(title != null, "Row present: " + rowTitle); return (View) title.getParent().getParent(); }
    private Switch switchOf(String rowTitle) { return find(rowTitled(rowTitle), Switch.class); }
    private TextView subOf(String rowTitle) { TextView title = text(activity.getWindow().getDecorView(), rowTitle); check(title != null, "Row present: " + rowTitle); return (TextView) ((ViewGroup) title.getParent()).getChildAt(1); }
    private TextView titleOf(String rowTitle) { TextView title = text(activity.getWindow().getDecorView(), rowTitle); check(title != null, "Row present: " + rowTitle); return title; }
    private void theme(String theme) { settings.edit().putString("theme", theme).commit(); SystemClock.sleep(900); settle(); }
    private void textScale(float scale) { settings.edit().putFloat("text_scale", scale).commit(); SystemClock.sleep(900); settle(); }
    private void click(View view) { main(view::performClick); settle(); }
    private int dp(int value) { return activity.ui().dp(value); }

    // ---- Shared helpers (as RideAutoStopValidation) ----------------------------------------------

    private View rideRoot(RidesScreen screen) { List<?> dialogs = (List<?>) field(screen, "dialogs"); return dialogs.isEmpty() ? screen.view() : ((AlertDialog) dialogs.get(dialogs.size() - 1)).getWindow().getDecorView(); }
    private static void uncheckAll(View view) { if (view instanceof CheckBox) ((CheckBox) view).setChecked(false); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) uncheckAll(((ViewGroup) view).getChildAt(i)); }
    private void closeDialogs(RidesScreen screen) { main(() -> { for (Object d : new ArrayList<>((List<?>) field(screen, "dialogs"))) ((AlertDialog) d).dismiss(); }); settle(); }
    private void centre(View row) {
        main(() -> { View v = row; while (v != null && !(v instanceof ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            check(v != null, "Preference row scrolls"); ScrollView scroll = (ScrollView) v; Rect r = new Rect(); row.getDrawingRect(r); scroll.offsetDescendantRectToMyCoords(row, r);
            scroll.scrollTo(0, Math.max(0, r.centerY() - scroll.getHeight() / 2)); }); settle();
    }
    /** fullyShown, looking the view up again until it holds: a screen rebuild between lookup and check replaces it. */
    private void awaitFullyShown(java.util.function.Supplier<View> lookup, String what) {
        await(() -> {
            View view; try { view = lookup.get(); } catch (AssertionError | RuntimeException rebuilding) { return false; }
            Rect r = new Rect();
            if (view == null || !view.isShown() || !view.getGlobalVisibleRect(r) || r.width() != view.getWidth() || r.height() != view.getHeight()) return false;
            if (!(view instanceof TextView)) return true;
            android.text.Layout layout = ((TextView) view).getLayout();
            return layout != null && layout.getEllipsisCount(layout.getLineCount() - 1) == 0;
        }, 5000, what + " fully visible, not clipped or ellipsized");
    }
    private void fullyShown(View view, String what) {
        Rect r = new Rect(); check(view.isShown() && view.getGlobalVisibleRect(r) && r.width() == view.getWidth() && r.height() == view.getHeight(), what + " fully visible, not clipped");
        if (view instanceof TextView) { android.text.Layout layout = ((TextView) view).getLayout(); check(layout != null && layout.getEllipsisCount(layout.getLineCount() - 1) == 0, what + " text not ellipsized"); }
    }
    /** The text census goes beside it (TextCensus.write; the shade's notification text is not part of it). */
    private void screenshot(String label) {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "auto-export-validation"); dir.mkdirs();
        File file = new File(dir, label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        catch (IOException e) { throw new IllegalStateException(e); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
        TextCensus.write(this, file);
    }
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private void await(BooleanSupplier test, long timeout, String why) { long end = SystemClock.elapsedRealtime() + timeout; while (SystemClock.elapsedRealtime() < end) { if (test.getAsBoolean()) { checks++; return; } SystemClock.sleep(100); } throw new AssertionError(why + " " + exportState()); }
    private String exportState() { Map<String, Object> m = new TreeMap<>(); for (Map.Entry<String, ?> e : settings.getAll().entrySet()) if (e.getKey().startsWith("ride_auto_export")) m.put(e.getKey(), e.getValue()); return m.toString(); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(400); waitForIdleSync(); }
    private void main(Runnable action) { AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } }); if (failure.get() != null) throw new IllegalStateException(failure.get()); }
    private static TextView text(View view, String label) { if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isShown()) return (TextView) view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView r = text(((ViewGroup) view).getChildAt(i), label); if (r != null) return r; } return null; }
    private static View byDescription(View view, String label) { if (view.getContentDescription() != null && label.contentEquals(view.getContentDescription()) && view.getVisibility() == View.VISIBLE) return view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View r = byDescription(((ViewGroup) view).getChildAt(i), label); if (r != null) return r; } return null; }
    private static <V> V find(View view, Class<V> type) { if (type.isInstance(view)) return type.cast(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { V r = find(((ViewGroup) view).getChildAt(i), type); if (r != null) return r; } return null; }
    private static Object field(Object target, String name) { try { Class<?> c = target.getClass(); while (c != null) { try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static void field(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    private static Object method(Object target, String name, Class<?>[] types, Object... values) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    /** A static method with no arguments; target unused. */
    private static Object method(Class<?> type, Object unused, String name) { try { Method m = type.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(null); } catch (Exception e) { throw new IllegalStateException(name, e); } }
    /** RideAutoExportFolder is package-private in the app: its static helpers through reflection. */
    private static Object folderClass(String name, Class<?>[] types, Object... values) {
        try { Method m = Class.forName("com.jjricks.volt.RideAutoExportFolder").getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, values); }
        catch (Exception e) { throw new IllegalStateException(name, e); }
    }
}
