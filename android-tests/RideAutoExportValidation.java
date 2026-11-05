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
 * off, both types (Route CSV, and ride JSON as All (JSON) or Export JSON), the manual Rides exports through
 * the real save picker for a byte comparison, re-export without duplicates, the keep-alive and maintenance
 * block while a copy runs, and the failure paths: folder deleted (retried on the next ride end and at app
 * start), grant revoked (retried after choosing the folder again), a restored setting with no folder, and
 * both types off. Never connects Bluetooth. run-ride-auto-export.py runs it.
 */
public final class RideAutoExportValidation extends Instrumentation {
    private static final String FOLDER_NAME = "VoltRides", FOLDER_PATH = "/sdcard/Download/" + FOLDER_NAME;
    private static final String ROUTE = "ride_auto_export_route_csv", JSON = "ride_auto_export_json", FOLDER = "ride_auto_export_folder",
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
            bothTypes();
            manualExportsMatch();
            reExportNoDuplicate();
            neverDuringMaintenance();
            folderDeletedRetriedOnNextRide();
            folderDeletedRetriedAtAppStart();
            grantRevokedChooseAgain();
            restoredWithoutFolder();
            bothOff();
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or recording left running");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots)); result.putStringArrayList("notes", new ArrayList<>(notes));
            try { shell("input keyevent 224"); } catch (Throwable ignored) {}
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
        check(!settings.contains(ROUTE) && !settings.contains(JSON) && !settings.contains(FOLDER) && !settings.contains(PENDING), "Absent on a fresh install, so both types off");
        String id = recordRide("Before turning on", true); stopOnly(null); awaitPassDone(); rideSaved(id);
        check(!settings.contains(PENDING) && !shellText("ls /sdcard/Download/").contains("ride-"), "Off: a finished ride is never copied");
    }

    /** RideAutoExportPickerActivity's intent, as the settings row starts it; type null keeps both types as they are. */
    private void openPicker(String type) {
        Intent picker = new Intent(activity, RideAutoExportPickerActivity.class);
        if (type != null) picker.putExtra("type", type);
        main(() -> activity.startActivity(picker));
    }

    private void cancelledPickerChangesNothing() {
        openPicker(ROUTE);
        node("USE THIS FOLDER", 15000); SystemClock.sleep(800);
        for (int i = 0; i < 4 && pickerShown(); i++) { shell("input keyevent 4"); SystemClock.sleep(1200); }
        awaitApp();
        check(!settings.getBoolean(ROUTE, false) && !settings.contains(FOLDER), "Cancelled picker leaves the type off and stores no folder");
        check(getTargetContext().getContentResolver().getPersistedUriPermissions().isEmpty(), "No grant kept after cancel");
        check(!pickerActivityShown(), "The picker activity finished with the picker");
    }

    /** Through the real picker: Download, New folder "VoltRides", Use this folder, Allow. */
    private void chooseNewFolder() throws Exception {
        openPicker(ROUTE);
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
        await(() -> settings.getBoolean(ROUTE, false) && settings.contains(FOLDER), 10000, "Picked folder stored and Route CSV on");
        check(!settings.getBoolean(JSON, false), "Only the type that opened the picker is on");
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
    private void setType(String key, boolean on) { main(() -> folderClass("set", new Class<?>[]{Context.class, SharedPreferences.class, String.class, boolean.class}, getTargetContext(), settings, key, on)); settle(); }
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
    private static String csvName(String id) { return "ride-" + shortId(id) + "-route.csv"; }
    private static String combinedName(String id) { return "ride-" + shortId(id) + "-combined.json"; }
    private static String analysisName(String id) { return "ride-" + shortId(id) + "-analysis.json"; }
    private String expectedCsv(String id) { try { return activity.service().phoneRides.load(id).toCsv(); } catch (IOException e) { throw new IllegalStateException(e); } }
    /** The expression RidesScreen.exportPhone used inline for All (JSON) before RideAutoExport.combinedJson. */
    private String expectedCombined(String id) {
        try {
            RideStore.Summary summary = activity.service().rides.get(id); PhoneRideStore.Track track = activity.service().phoneRides.load(id);
            return "{\"scooter\":" + (summary == null ? "null" : summary.toJson()) + ",\"phone\":" + track.toJson() + "}";
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private String expectedAnalysis(String id) { try { return activity.service().rides.get(id).toJson(); } catch (IOException e) { throw new IllegalStateException(e); } }
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
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(csvName(id)), 25000, "Copied in the background with the screen off");
        awaitPassDone(); watch.end();
        check(!getTargetContext().getSystemService(PowerManager.class).isInteractive(), "The copy finished while the screen stayed off");
        check(watch.busySamples > 0, "The copy was seen running");
        check(watch.notAllowed == 0 && watch.notForeground == 0, "Keep-alive: work stayed allowed and the service in the foreground while copying (" + watch.busySamples + " samples)");
        check(watch.maintenanceOpen == 0, "Backup and restore were refused while copying");
        check(!activity.service().workAllowed(), "Once copied, a hidden idle service stops its work again");
        notes.add("keep-alive samples while copying: " + watch.busySamples);
        screenOnAndShow();
        rideSaved(id); folderClean();
        check(folderFile(csvName(id)).equals(expectedCsv(id)), "Folder copy equals Track.toCsv()");
        check(!listing().contains(combinedName(id)) && !listing().contains(analysisName(id)), "JSON off: no JSON file");
        notes.add("hidden stop: " + csvName(id) + " sha256 " + sha(expectedCsv(id)));
    }

    /** JSON on as well: a GPS ride gets both files, a motion-only ride All (JSON), a ride without phone data Export JSON. */
    private void bothTypes() {
        setType(JSON, true);
        check(settings.getBoolean(ROUTE, false) && settings.getBoolean(JSON, false) && settings.contains(FOLDER), "Both types on, one folder");
        String b = recordRide("Both types", true); rides.put("B", b); stopFor(null); awaitPassDone(); rideSaved(b);
        check(listing().contains(csvName(b)) && listing().contains(combinedName(b)) && settings.getString(PENDING, "").isEmpty(), "Route CSV and All (JSON): " + listing());
        check(folderFile(combinedName(b)).equals(expectedCombined(b)), "All (JSON) copy equals the manual export's text");
        check(folderFile(csvName(b)).equals(expectedCsv(b)), "Route CSV copy equals Track.toCsv()");
        String c = recordRide("Motion only", false); stopFor(null); awaitPassDone(); rideSaved(c);
        PhoneRideStore.Track motion; try { motion = activity.service().phoneRides.load(c); } catch (IOException e) { throw new IllegalStateException(e); }
        check(motion.locationCount == 0, "No GPS fixes");
        check(!listing().contains(csvName(c)), "No GPS fixes: no Route CSV, skipped quietly");
        if (motion.motionSampleCount > 0) check(folderFile(combinedName(c)).equals(expectedCombined(c)), "Motion-only phone recording: All (JSON)");
        else check(folderFile(analysisName(c)).equals(expectedAnalysis(c)), "No phone samples at all: Export JSON");
        notes.add("motion-only ride motion samples: " + motion.motionSampleCount);
        String d = recordRide("No phone data", false, false); stopFor(null); awaitPassDone(); rideSaved(d);
        check(!listing().contains(csvName(d)) && !listing().contains(combinedName(d)), "No phone recording: no Route CSV and no All (JSON)");
        check(folderFile(analysisName(d)).equals(expectedAnalysis(d)), "No phone recording: Export JSON's analysis, byte for byte");
        check(settings.getString(PENDING, "").isEmpty() && !settings.contains(PROBLEM), "Nothing waiting, no problem");
        folderClean();
    }

    /** The real Rides exports, Export phone data, Route CSV and All (JSON), through Android's save picker into Download. */
    private void manualExportsMatch() {
        String id = rides.get("B");
        manualExport(id, 0, csvName(id));
        manualExport(id, 4, combinedName(id));
        String sums = shellText("sha256sum /sdcard/Download/" + csvName(id) + " " + FOLDER_PATH + "/" + csvName(id) + " /sdcard/Download/" + combinedName(id) + " " + FOLDER_PATH + "/" + combinedName(id));
        String[] lines = sums.trim().split("\n");
        check(lines.length == 4 && lines[0].split(" ")[0].equals(lines[1].split(" ")[0]), "Route CSV copy byte-identical to the manual export:\n" + sums);
        check(lines[2].split(" ")[0].equals(lines[3].split(" ")[0]), "All (JSON) copy byte-identical to the manual export:\n" + sums);
        notes.add("manual vs automatic: " + sums.trim().replace('\n', ' '));
        folderClean();
    }
    private void manualExport(String id, int item, String name) {
        main(() -> method(activity, "select", new Class<?>[]{String.class}, "rides")); settle();
        RidesScreen screen = (RidesScreen) field(activity, "screen"); boolean wide = (boolean) field(screen, "wide");
        main(() -> method(screen, wide ? "selectRide" : "showDetails", new Class<?>[]{String.class}, id)); settle();
        String export = T.ridesExportPhone();
        await(() -> text(rideRoot(screen), export) != null, 15000, "Route loaded with its export button");
        main(() -> text(rideRoot(screen), export).performClick()); settle();
        AlertDialog chooser = (AlertDialog) ((List<?>) field(screen, "dialogs")).get(((List<?>) field(screen, "dialogs")).size() - 1);
        main(() -> chooser.getListView().performItemClick(chooser.getListView().getChildAt(item), item, item));
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
        String b = rides.get("B"); String csv = folderFile(csvName(b)), json = folderFile(combinedName(b));
        settings.edit().putString(PENDING, b).commit();
        main(() -> method(activity.service(), "startRideAutoExport", new Class<?>[0])); awaitPassDone();
        check(settings.getString(PENDING, "").isEmpty() && fileCount() == before && folderClean(), "Re-export of the same ride: no second file, no name (1)");
        check(folderFile(csvName(b)).equals(csv) && folderFile(combinedName(b)).equals(json), "Unchanged files left as they were");
        // A ride that ended while JSON was off gains its JSON on a later pass; its Route CSV is left alone.
        String a = rides.get("A"); String routeA = folderFile(csvName(a));
        settings.edit().putString(PENDING, a).commit();
        main(() -> method(activity.service(), "startRideAutoExport", new Class<?>[0])); awaitPassDone();
        check(fileCount() == before + 1 && listing().contains(combinedName(a)) && folderFile(csvName(a)).equals(routeA) && folderClean(), "Only the missing JSON was added");
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
        List<String> names = listing(); check(names.contains(csvName(d)) && names.contains(combinedName(d)) && names.contains(csvName(e)) && names.contains(combinedName(e)), "Both rides, both types: " + names);
        folderClean();
    }

    private void folderDeletedRetriedAtAppStart() {
        shell("rm -rf " + FOLDER_PATH); SystemClock.sleep(500);
        String f = recordRide("Retried at app start", true); stopFor(null); awaitPassDone();
        check(settings.getString(PENDING, "").equals(f), "Waiting after the failure");
        shell("mkdir -p " + FOLDER_PATH); SystemClock.sleep(500);
        main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        await(() -> method(ScooterService.class, null, "currentForMaintenance") == null, 15000, "Service ended");
        check(settings.getString(PENDING, "").equals(f), "Still waiting while Volt is closed");
        launch();
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(csvName(f)) && listing().contains(combinedName(f)), 25000, "App start retried the waiting ride");
        folderClean();
    }

    private void grantRevokedChooseAgain() {
        Uri tree = Uri.parse(settings.getString(FOLDER, ""));
        getTargetContext().getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        check(!granted(tree), "Grant revoked");
        String g = recordRide("Grant revoked", true); stopFor(null); awaitPassDone(); rideSaved(g);
        check(settings.getString(PENDING, "").equals(g) && "folder".equals(settings.getString(PROBLEM, "")), "Waiting after the revoked grant");
        // Choose the folder again (no type: both stay as they are): the picker opens on it.
        openPicker(null);
        node("Files in " + FOLDER_NAME, 15000); SystemClock.sleep(600);
        tap(node("USE THIS FOLDER", 5000)); tap(node("ALLOW", 10000)); awaitApp();
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(csvName(g)), 25000, "Choosing the folder again retried the ride");
        check(granted(tree) && !settings.contains(PROBLEM) && settings.getBoolean(ROUTE, false) && settings.getBoolean(JSON, false), "Grant back, problem cleared, both types still on");
        folderClean();
    }

    /** A full backup restored on another phone brings the two choices but no folder or grant. */
    private void restoredWithoutFolder() {
        String kept = settings.getString(FOLDER, "");
        settings.edit().remove(FOLDER).remove(PROBLEM).commit(); settle();
        String h = recordRide("Restored, no folder yet", true); rides.put("H", h); stopFor(null); awaitPassDone(); rideSaved(h);
        check(settings.getString(PENDING, "").equals(h) && "folder".equals(settings.getString(PROBLEM, "")), "Ride waits for a folder");
        settings.edit().putString(FOLDER, kept).commit();
        main(() -> method(activity.service(), "rideAutoExportFolderChosen", new Class<?>[0]));
        await(() -> settings.getString(PENDING, "").isEmpty() && listing().contains(csvName(h)), 25000, "Copied once a folder is there");
        folderClean();
    }

    private void bothOff() {
        setType(ROUTE, false);
        check(settings.getBoolean(JSON, false) && settings.contains(FOLDER) && !getTargetContext().getContentResolver().getPersistedUriPermissions().isEmpty(), "One type off: the other keeps the folder");
        settings.edit().putString(PENDING, rides.get("A")).commit();
        setType(JSON, false);
        check(!settings.getBoolean(ROUTE, true) && !settings.getBoolean(JSON, true) && !settings.contains(FOLDER) && !settings.contains(PENDING) && !settings.contains(PROBLEM), "Both off forgets the folder and the waiting list");
        check(getTargetContext().getContentResolver().getPersistedUriPermissions().isEmpty(), "Both off releases the folder grant");
        int before = shellText("ls " + FOLDER_PATH).split("\n").length;
        String i = recordRide("After turning off", true); stopFor(null); awaitPassDone(); rideSaved(i);
        check(!settings.contains(PENDING) && !shellText("ls " + FOLDER_PATH).contains(shortId(i)) && shellText("ls " + FOLDER_PATH).split("\n").length == before, "Nothing copied while both are off");
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
            check(name.matches("ride-[0-9a-f]{8}-(route\\.csv|combined\\.json|analysis\\.json)"), "Only finished ride files in the folder, found: " + name);
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
    private void closeDialogs(RidesScreen screen) { main(() -> { for (Object d : new ArrayList<>((List<?>) field(screen, "dialogs"))) ((AlertDialog) d).dismiss(); }); settle(); }
    private void centre(View row) {
        main(() -> { View v = row; while (v != null && !(v instanceof ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            check(v != null, "Preference row scrolls"); ScrollView scroll = (ScrollView) v; Rect r = new Rect(); row.getDrawingRect(r); scroll.offsetDescendantRectToMyCoords(row, r);
            scroll.scrollTo(0, Math.max(0, r.centerY() - scroll.getHeight() / 2)); }); settle();
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
