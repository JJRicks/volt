package com.jjricks.volt;

import android.Manifest;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.tests.HubTestPages;

/**
 * Disposable emulator only: auto-record and auto ride mode through the real service, activity and
 * BleController, on a synthetic in-process GATT link. Every packet goes to a stand-in, never a radio.
 * Bluetooth stays off. Needs hidden_api_policy 1 for the stand-in.
 *
 * <p>No arguments runs the full pass on a fresh install. {@code -e only gates}, {@code gps-grant}, {@code gps-decline},
 * {@code gps-riding}, {@code dialog-guard}, {@code cancel-empty}, {@code restored-grant} and {@code restored-decline} are
 * single runs on a fresh install too. The release sweep runs all of these. The remaining {@code -e only} scenarios follow a
 * permission across runs of one install, so whoever runs them changes it between runs with {@code pm} (see
 * {@link #permissionState}). In order, with cleared app data at the start: expiry-first; revoke location, again-offer-grant;
 * revoke and clear its flags, again-offer-decline; grant, again-settings-grant; revoke and clear flags, again-offer-decline;
 * set user-set and user-fixed, again-dontask.
 */
public final class AutoStartValidation extends Instrumentation {
    private MainActivity activity; private ScooterService service; private BleController ble;
    private ScooterProfiles.Profile profile; private FakeLink link; private int checks; private boolean admitted;
    private final List<String> screenshots = new ArrayList<>();

    private String only; private boolean prefix, again;
    /** Pauses of MainActivity: one for each permission request, even one Android answers without a dialog. */
    private volatile int pauses;
    @Override public void onCreate(Bundle args) { only = args == null ? null : args.getString("only"); prefix = args != null && args.getString("prefix") != null; again = only != null && only.startsWith("again-"); start(); }
    @Override public void callActivityOnCreate(android.app.Activity a, Bundle saved) { super.callActivityOnCreate(a, saved); if (a instanceof MainActivity) activity = (MainActivity) a; }
    @Override public void callActivityOnPause(android.app.Activity a) { if (a instanceof MainActivity) pauses++; super.callActivityOnPause(a); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            if (again) main(() -> profile = new ScooterProfiles(getTargetContext()).active());
            else main(() -> {
                prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", true)
                        .putBoolean("phone_gps", false).putBoolean("phone_motion", false).putBoolean("map_basemap_enabled", false).commit();
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                profile = profiles.create("Synthetic auto-start scooter", "000000000000", 0);
                profile.serial = "Q2538ZZ9US7777777"; profile.mac = "C0:FF:EE:00:25:38"; profiles.update(profile);
            });
            boolean restored = only != null && only.startsWith("restored-");
            boolean fresh = "expiry-first".equals(only) || "dialog-guard".equals(only);
            if (restored || fresh) prefs().edit().putBoolean(AutoStartPolicy.AUTO_RECORD_KEY, true).putBoolean("phone_gps", true).commit();
            if ("gates".equals(only)) prefs().edit().putBoolean("phone_gps", true).commit();
            if ("cancel-empty".equals(only)) prefs().edit().putBoolean(AutoStartPolicy.AUTO_RECORD_KEY, true).commit();
            if ("again-settings-grant".equals(only)) require(asked() && PhoneRideRecorder.hasLocationPermission(getTargetContext()), "Precondition: declined earlier, granted in Android settings since");
            if ("again-dontask".equals(only)) { require(asked(), "Precondition: declined earlier"); prefs().edit().remove(AutoStartPolicy.LOCATION_ASKED_KEY).commit(); }
            if (only != null && only.startsWith("gps-")) prefs().edit().putBoolean(AutoStartPolicy.AUTO_RECORD_KEY, true).putBoolean("phone_gps", false).commit();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "Service ready");
            service = activity.service(); ble = service.ble;
            if (restored) { restored(only.endsWith("grant")); result.putString("result", "PASS"); return; }
            if (again || fresh || "cancel-empty".equals(only)) { permissionState(); result.putString("result", "PASS"); return; }
            connect();
            if ("gates".equals(only)) { gates(); result.putString("result", "PASS"); return; }
            if ("gps-grant".equals(only)) { gpsAfterAutoRecord(true); result.putString("result", "PASS"); return; }
            if ("gps-decline".equals(only)) { gpsAfterAutoRecord(false); result.putString("result", "PASS"); return; }
            if ("gps-riding".equals(only)) { gpsDuringRide(); result.putString("result", "PASS"); return; }
            if ("cadence".equals(only)) { backgroundCadence(); result.putString("result", "PASS"); return; }
            if ("location".equals(only)) { locationChoice(); result.putString("result", "PASS"); return; }
            switchesOffDoNothing();
            autoRecord();
            autoRecordInBackground();
            autoRide();
            statsTextEntry();
            settingsRows();
            backgroundCadence();
            locationChoice();
            backgroundOffCutoff();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
            if (admitted) try { shot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (admitted && activity != null) main(() -> { if (service != null && service.isRecording()) service.stopRide(); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
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
        if (!again) require(prefs().getAll().isEmpty() && getTargetContext().databaseList().length == 0, "Existing app data refused");
    }

    private void switchesOffDoNothing() {
        require(!prefs().contains(AutoStartPolicy.AUTO_RECORD_KEY) && !prefs().contains(AutoStartPolicy.AUTO_RIDE_MODE_KEY), "Switches absent on a fresh install");
        moving(); settle();
        require(!recording() && !ridingOpen(), "With both switches off, moving does nothing");
        stopped(); settle(); // Otherwise that fresh sample could correctly start a recording the moment the switch turns on.
    }

    private void autoRecord() throws Exception {
        enable(AutoStartPolicy.AUTO_RECORD_KEY, true);
        stale(); settle();
        require(!recording(), "A stale speed reading never starts a recording");
        stopped(); settle();
        require(!recording(), "Speed zero does not start a recording");
        moving(); await(this::recording, "Speed above zero starts a recording");
        shot("auto-record-started");
        // Fresh speed at the moment of the stop: the hold must already be in place when the stop announces itself.
        moving(); main(() -> service.stopRide());
        require(!recording(), "Manual stop stops");
        moving(); settle(); moving(); settle();
        require(!recording(), "After a manual stop, still moving does not restart it");
        reconnect(); stopped(); settle();
        require(!recording(), "Reconnect alone (no speed) does not start one");
        moving(); await(this::recording, "Disconnect, reconnect and speed starts one");
        // The automatic 10-minute end: same private stop the policy's tick uses, no hold. It only happens after no motion.
        stopped(); settle();
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
        moving(); settle(); moving(); settle();
        require(!recording(), "Start recording in the background off (the default): no start while Volt is in the background");
        enable(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, true);
        moving(); await(this::recording, "Auto-record starts in the background");
        require(!ridingOpen(), "Auto ride mode stayed off");
        moving(); main(() -> service.stopRide());
        require(!recording(), "Manual stop in the background holds too");
        returnToVolt();
        enable(AutoStartPolicy.AUTO_RECORD_KEY, false);
        enable(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false);
    }

    private void autoRide() throws Exception {
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

    /** The activity's real wiring: a Stats search holding focus (window focus unchanged) blocks the open; clear Stats does not. */
    private void statsTextEntry() throws Exception {
        enable(AutoStartPolicy.AUTO_RIDE_MODE_KEY, true);
        reconnect(); stopped(); settle();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "stats")); settle();
        Object stats = field(activity, "screen");
        main(() -> invoke(stats, "selectSection", new Class<?>[]{String.class}, "Readings")); settle();
        EditText search = (EditText) field(stats, "search");
        main(search::requestFocus);
        await(() -> onMain(() -> activity.getCurrentFocus() == search), "Stats search took focus");
        require(onMain(() -> activity.hasWindowFocus()), "Window focus is still the activity's: it can't tell a search apart");
        moving(); settle(); moving(); settle();
        require(!ridingOpen(), "Riding mode never opens over a focused Stats search");
        shot("stats-search-focused");
        main(() -> { search.setText("batt"); search.clearFocus(); }); settle();
        require(onMain(() -> !(activity.getCurrentFocus() instanceof EditText)), "Search let go of focus");
        moving(); await(this::ridingOpen, "A clear Stats screen, text left in the search, is eligible");
        main(() -> invoke(activity, "navigateBack", new Class<?>[0]));
        await(() -> !ridingOpen(), "Back out of the Stats open");
        enable(AutoStartPolicy.AUTO_RIDE_MODE_KEY, false);
    }

    /**
     * A healthy background link polls status every 15 s: fresh motion between polls must start a recording, and only
     * fresh motion on an authenticated link bound to the active scooter may.
     */
    private void backgroundCadence() throws Exception {
        enable(AutoStartPolicy.AUTO_RECORD_KEY, true);
        enable(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, true);
        reconnect(); stopped(); settle();
        goHome();
        ageStatus(9000);
        require(onMain(() -> ble.linked() && !ble.ready()), "Status 9 s old in the background: linked, past ready()'s 7 s");
        staleOnly(); settle();
        require(!recording(), "Stale speed between background polls never starts");
        ScooterProfiles.Profile other = onMainProfile();
        require(other != null && !other.id.equals(profile.id), "Second synthetic profile");
        main(() -> set(ble, "profile", other));
        ageStatus(9000); motionOnly(); settle();
        require(!recording(), "A link bound to another scooter never starts");
        main(() -> set(ble, "profile", profile));
        ageStatus(36000); motionOnly(); settle();
        require(!recording(), "Status past the background silence limit is not a link");
        ageStatus(9000); motionOnly();
        await(this::recording, "Fresh motion on a healthy background link at its normal cadence starts a recording");
        moving(); main(() -> service.stopRide());
        require(!recording(), "Stopped the background recording");
        returnToVolt();
        enable(AutoStartPolicy.AUTO_RECORD_KEY, false);
        enable(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false);
    }

    /** Turning the switch on is the location choice; a backup-restored switch or a Phone GPS that is off never prompts. */
    private void locationChoice() throws Exception {
        Context app = getTargetContext();
        require(!PhoneRideRecorder.hasLocationPermission(app), "Precondition: no location permission");
        prefs().edit().putBoolean("phone_gps", true).commit();
        enable(AutoStartPolicy.AUTO_RECORD_KEY, false);
        reconnect(); stopped(); settle();
        // Record rides automatically left More › App preferences for Ride › Recording settings in the Hub layout (#389).
        HubTestPages.reach(this, activity, "ride.recording"); settle();

        // A switch restored from a backup: nobody tapped it, so nothing prompts, even while the scooter moves.
        enable(AutoStartPolicy.AUTO_RECORD_KEY, true); settle();
        require(focused(), "A restored switch does not prompt");
        moving(); await(this::recording, "A restored switch still records");
        settle();
        require(focused() && !gps(), "No prompt from the automatic start: it records without a route");
        moving(); main(() -> service.stopRide());
        reconnect(); stopped(); settle();

        // Off is saved at once, and Phone GPS off means there is no location to offer.
        tapAutoRecord(); settle();
        require(focused() && !prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), "Turning it off just saves");
        prefs().edit().putBoolean("phone_gps", false).commit();
        tapAutoRecord(); settle();
        require(focused() && prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), "Phone GPS off: no prompt, saved");
        tapAutoRecord(); settle();
        prefs().edit().putBoolean("phone_gps", true).commit();

        // Declined: the switch stays on and rides record without a route.
        tapAutoRecord();
        await(() -> !focused(), "Turning it on offers the location prompt");
        require(!prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), "Nothing is saved until the rider answers, so no ride starts behind the prompt");
        settle(); shot("location-prompt");
        pressPermission("permission_deny_button");
        await(() -> resumed() && focused(), "Back from declining");
        require(prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), "Declining keeps the switch on");
        require(!PhoneRideRecorder.hasLocationPermission(app), "Still no permission");
        moving(); await(this::recording, "Declined: moving still records");
        settle();
        require(!gps(), "Declined: no route");
        moving(); main(() -> service.stopRide());
        reconnect(); stopped(); settle();

        // Granted: the next ride gets its route.
        tapAutoRecord(); settle();
        tapAutoRecord();
        await(() -> !focused(), "The prompt comes back after one decline");
        settle();
        pressPermission("permission_allow_foreground_only_button");
        await(() -> resumed() && focused(), "Back from allowing");
        require(prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false) && PhoneRideRecorder.hasLocationPermission(app), "Granted, switch on");
        moving(); await(this::recording, "Granted: moving records");
        settle();
        require(gps(), "Granted: the recording has the route");
        moving(); main(() -> service.stopRide());
        await(() -> !gps(), "Recorder stopped");

        // Already granted: turning it on again asks nothing.
        tapAutoRecord(); settle(); tapAutoRecord(); settle();
        require(focused() && prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), "Granted: no prompt");
        enable(AutoStartPolicy.AUTO_RECORD_KEY, false);
        prefs().edit().putBoolean("phone_gps", false).commit();
    }

    private void restored(boolean grant) throws Exception {
        Context app = getTargetContext();
        require(!PhoneRideRecorder.hasLocationPermission(app), "Precondition: fresh phone, no location permission");
        await(this::promptShown, "Restored settings: Volt offers location on screen");
        require(!asked() && !recording(), "Not noted until answered; nothing recording");
        SystemClock.sleep(2000); waitForIdleSync(); shot("restored-prompt-" + (grant ? "grant" : "decline"));
        answer(grant);
        noted(grant);
        require(PhoneRideRecorder.hasLocationPermission(app) == grant, "Permission matches the answer");
        require(prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false) && prefs().getBoolean("phone_gps", false), "Both switches stay on");
        noSecondPrompt("restored");
        connect(); stopped(); settle();
        require(!promptShown(), "A connection does not ask again");
        moving(); await(this::recording, "Auto-record starts after the answer");
        settle();
        require(gps() == grant, "Route follows the answer");
        require(!promptShown(), "No prompt from the automatic start");
        moving(); main(() -> service.stopRide());
        await(() -> !recording(), "Stopped");
    }

    private void gpsAfterAutoRecord(boolean grant) throws Exception {
        Context app = getTargetContext();
        stopped(); settle();
        require(!PhoneRideRecorder.hasLocationPermission(app) && !promptShown() && !asked(), "GPS route off: auto-record on prompts nothing");
        openSensors();
        tapGpsRoute();
        await(this::promptShown, "Turning GPS route on offers location");
        require(prefs().getBoolean("phone_gps", false), "The switch saves straight away");
        SystemClock.sleep(2000); waitForIdleSync(); shot("gps-prompt-" + (grant ? "grant" : "decline"));
        answer(grant);
        noted(grant);
        require(PhoneRideRecorder.hasLocationPermission(app) == grant, "Permission matches the answer");
        closeSheet();
        noSecondPrompt("gps");
        stopped(); settle();
        moving(); await(this::recording, "Auto-record still starts");
        settle();
        require(gps() == grant, "Route follows the answer");
        moving(); main(() -> service.stopRide());
    }

    /** GPS route turned on during a ride asks nothing then, and the on-screen offer asks once when it is over. */
    private void gpsDuringRide() throws Exception {
        stopped(); settle();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "rides")); settle();
        moving(); await(this::recording, "Riding");
        openSensors();
        tapGpsRoute(); settle(); moving(); settle();
        require(prefs().getBoolean("phone_gps", false), "The switch saves");
        require(!promptShown() && !asked(), "No prompt during a ride");
        closeSheet();
        moving(); main(() -> service.stopRide());
        require(!recording(), "Stopped by hand");
        stopped();
        await(this::promptShown, "Stopped and on screen: the one offer");
        answer(false);
        await(this::asked, "Noted");
        noSecondPrompt("gps-riding");
    }

    private void gates() throws Exception {
        stopped(); settle();
        require(!promptShown(), "Auto-record off: no offer");
        // On More: setup flows live there, so the offer waits.
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
        enable(AutoStartPolicy.AUTO_RECORD_KEY, true); stopped(); SystemClock.sleep(2500); waitForIdleSync();
        require(!promptShown() && !asked(), "Not over More's setup");
        enable(AutoStartPolicy.AUTO_RECORD_KEY, false);
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
        // Motion first (packets are delivered 10 ms later), so the offer never sees the switch on while stopped.
        moving(); enable(AutoStartPolicy.AUTO_RECORD_KEY, true); moving();
        await(this::recording, "Moving: recording starts");
        for (int i = 0; i < 4; i++) { moving(); SystemClock.sleep(600); require(!promptShown() && !asked(), "No prompt while recording"); }
        shot("gates-recording-no-prompt");
        moving(); main(() -> service.stopRide());
        require(!recording(), "Stopped by hand, still moving");
        for (int i = 0; i < 4; i++) { moving(); SystemClock.sleep(600); require(!promptShown() && !asked() && !recording(), "No prompt while moving"); }
        stopped();
        await(this::promptShown, "Stopped: offered");
        SystemClock.sleep(2000); waitForIdleSync(); shot("gates-offer-after-stop");
        answer(false);
        await(this::asked, "Noted");
        noSecondPrompt("gates");
        stopped(); settle();
        require(!promptShown(), "Still only once");
    }

    /**
     * The permission states around the on-screen offer, across phases of one app install. The permission changes between
     * phases with pm (order in the class comment), because revoking kills the app's process, as a one-time grant ending does.
     */
    private void permissionState() throws Exception {
        Context app = getTargetContext();
        switch (only) {
            case "expiry-first": {
                require(!PhoneRideRecorder.hasLocationPermission(app), "Precondition: no permission");
                await(this::promptShown, "First offer");
                answer(true);
                await(() -> PhoneRideRecorder.hasLocationPermission(app), "Allow granted it");
                settle();
                if (!prefix) require(!asked(), "A grant is not noted");
                noSecondPrompt("expiry-first");
                goHome();
                break;
            }
            case "again-offer-grant": {
                require(!PhoneRideRecorder.hasLocationPermission(app) && (prefix || !asked()), "Precondition: the earlier grant has gone, nothing declined");
                await(this::promptShown, "Coming back after the grant ended offers it again");
                SystemClock.sleep(2000); waitForIdleSync(); shot("expired-grant-offered-again");
                answer(true);
                await(() -> PhoneRideRecorder.hasLocationPermission(app), "Allow granted it");
                settle();
                require(!asked(), "This grant is not noted either");
                noSecondPrompt("again-offer-grant");
                break;
            }
            case "again-offer-decline": {
                require(!PhoneRideRecorder.hasLocationPermission(app) && !asked(), "Precondition: permission gone, nothing declined");
                await(this::promptShown, "Offered once more");
                answer(false);
                await(this::asked, "A decline is noted");
                require(!PhoneRideRecorder.hasLocationPermission(app), "Still no permission");
                noSecondPrompt("again-offer-decline");
                connect(); stopped(); settle();
                require(!promptShown(), "A connection does not ask again");
                moving(); await(this::recording, "Declined: moving still records");
                settle();
                require(!gps() && !promptShown(), "No route, no prompt");
                moving(); main(() -> service.stopRide());
                await(() -> !recording(), "Stopped");
                break;
            }
            case "again-settings-grant": {
                SystemClock.sleep(2500); waitForIdleSync();
                require(!promptShown(), "Granted in Android settings: no offer");
                await(() -> !asked(), "The rider's own grant ends the decline");
                noSecondPrompt("again-settings-grant");
                break;
            }
            case "again-dontask": {
                await(this::resumedAndFocused, "On screen");
                int before = pauses; long end = SystemClock.uptimeMillis() + 2000;
                await(this::asked, "Android's instant refusal counts as a decline");
                while (SystemClock.uptimeMillis() < end) { require(!promptShown(), "Don't ask again: no dialog"); SystemClock.sleep(100); }
                require(!PhoneRideRecorder.hasLocationPermission(app), "Still no permission");
                int settled = pauses;
                require(settled - before <= 1, "One request only, saw " + (settled - before) + " pauses");
                SystemClock.sleep(4000); waitForIdleSync();
                require(pauses == settled, "Nothing further on the 1 Hz refresh, saw " + (pauses - settled) + " more pauses");
                require(!promptShown() && asked(), "Still ended");
                break;
            }
            case "dialog-guard": {
                await(this::promptShown, "Offer up");
                Object pending = field(activity, "pendingRideLocation");
                require(pending != null, "The request is pending");
                final boolean[] second = {false};
                main(() -> { activity.requestRideLocation(() -> second[0] = true); invoke(activity, "maybeAutoOpenRiding", new Class<?>[0]); });
                settle();
                require(!second[0] && field(activity, "pendingRideLocation") == pending && !(Boolean) field(activity, "rideLocationResolved"), "A second request while the dialog is up does nothing");
                require(promptShown() && !asked(), "Still the one dialog, nothing noted");
                MainActivity before = activity;
                main(before::recreate);
                await(() -> activity != before && onMain(() -> activity.service() != null), "Activity recreated under the dialog");
                service = activity.service(); ble = service.ble;
                settle();
                require(promptShown() && !asked(), "Recreating the activity records nothing");
                answer(false);
                require(!asked(), "An answer that reached no pending request is not noted");
                await(this::promptShown, "Asked once more, since no answer was recorded");
                require(!asked(), "Still not noted while it asks again");
                SystemClock.sleep(2000); waitForIdleSync();
                answer(false);
                await(this::asked, "This decline is noted");
                noSecondPrompt("dialog-guard");
                break;
            }
            case "cancel-empty": {
                settle();
                require(!PhoneRideRecorder.hasLocationPermission(app) && !promptShown() && !asked(), "Precondition: GPS route off, nothing offered");
                await(() -> resumed() && focused(), "On screen");
                // A request already running makes Android cancel the next one with empty results, in the same call.
                main(() -> {
                    prefs().edit().putBoolean("phone_gps", true).commit();
                    activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 7777);
                    invoke(activity, "maybeAutoOpenRiding", new Class<?>[0]);
                });
                await(() -> resumed() && focused(), "Back after the cancelled request");
                require(!asked(), "A cancelled request is not a decline");
                int settled = pauses;
                SystemClock.sleep(3500); waitForIdleSync();
                require(!promptShown() && !asked() && pauses == settled, "A cancelled request is not retried on the 1 Hz refresh, saw " + (pauses - settled) + " more pauses");
                MainActivity before = activity;
                main(before::recreate);
                await(() -> activity != before && onMain(() -> activity.service() != null), "Activity recreated");
                service = activity.service(); ble = service.ble;
                await(this::promptShown, "A new activity may offer it once");
                answer(false);
                await(this::asked, "The decline is noted");
                noSecondPrompt("cancel-empty");
                break;
            }
            default: throw new AssertionError("Unknown scenario " + only);
        }
    }
    private boolean resumedAndFocused() { return resumed() && focused(); }
    /** Only a decline leaves the note; a grant leaves it unset. */
    private void noted(boolean grant) {
        if (grant) { await(() -> PhoneRideRecorder.hasLocationPermission(getTargetContext()), "Granted"); settle(); require(!asked(), "A grant is not noted"); }
        else await(this::asked, "The decline is noted");
    }
    private boolean asked() { return prefs().getBoolean(AutoStartPolicy.LOCATION_ASKED_KEY, false); }
    private boolean promptShown() { AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow(); return root != null && String.valueOf(root.getPackageName()).contains("permissioncontroller"); }
    private void answer(boolean grant) { pressPermission(grant ? "permission_allow_foreground_only_button" : "permission_deny_button"); await(() -> !promptShown(), "Prompt closed"); }
    private void noSecondPrompt(String where) {
        await(() -> resumed(), "Back on Volt");
        goHome(); returnToVolt(); SystemClock.sleep(2500); waitForIdleSync();
        require(!promptShown(), where + ": asked only once, across leaving and coming back");
    }
    /**
     * The phone sensors: Ride › Recording settings' card since the Hub layout (#389) replaced the Rides tab's Phone
     * sensors sheet, which nothing opens any more. Opened over Ride, so Back (closeSheet) returns there.
     */
    private void openSensors() {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "rides")); settle();
        HubTestPages.reach(this, activity, "ride.recording"); settle();
        main(() -> require("ride.recording".equals(HubTestPages.current(activity)), "Recording settings did not open"));
    }
    /** Back from the phone sensors (their page now, a sheet before #389). */
    private void closeSheet() {
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        await(() -> resumed() && focused(), "Sheet closed");
    }
    /**
     * The GPS route switch, matched on its whole label: findAccessibilityNodeInfosByText matches substrings, and
     * Recording settings also carries "Start recording in the background", whose caption ends "…has no GPS route."
     */
    private void tapGpsRoute() {
        long end = SystemClock.uptimeMillis() + 8000;
        int seen = 0;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> labels = new ArrayList<>();
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("GPS route")) {
                    if (node.getText() != null && "GPS route".contentEquals(node.getText())) labels.add(node);
                }
                seen = labels.size();
                if (seen == 1) {
                    AccessibilityNodeInfo n = labels.get(0); while (n != null && !n.isClickable()) n = n.getParent();
                    require(n != null, "GPS route row is not clickable");
                    require(n.performAction(AccessibilityNodeInfo.ACTION_CLICK), "GPS route row refused the click");
                    return;
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("GPS route row not found (" + seen + " rows labelled exactly GPS route)");
    }

    private boolean focused() { return onMain(() -> activity.hasWindowFocus()); }
    private boolean gps() { return onMain(() -> (Boolean) field(service, "phoneLocationForeground")); }
    /** Never saved: only ble's bound profile reads it. */
    private ScooterProfiles.Profile onMainProfile() { return new ScooterProfiles.Profile("synthetic-other", "Other synthetic scooter", "000000000001", 0, "", "", 0); }
    private void tapAutoRecord() {
        main(() -> {
            View label = find(activity.getWindow().getDecorView(), "Record rides automatically");
            require(label != null, "Auto-record row shown");
            View view = label;
            Switch toggle = null;
            while (view != null && toggle == null) { if (view instanceof ViewGroup) toggle = switchIn((ViewGroup) view); view = view.getParent() instanceof View ? (View) view.getParent() : null; }
            require(toggle != null && toggle.isEnabled(), "Auto-record switch live");
            toggle.performClick();
        });
    }
    private static Switch switchIn(ViewGroup group) { for (int i = 0; i < group.getChildCount(); i++) { View c = group.getChildAt(i); if (c instanceof Switch) return (Switch) c; if (c instanceof ViewGroup) { Switch s = switchIn((ViewGroup) c); if (s != null) return s; } } return null; }
    private void pressPermission(String id) {
        long end = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (String pkg : new String[]{"com.google.android.permissioncontroller", "com.android.permissioncontroller"}) {
                // Android's second prompt names its deny button differently.
                for (String name : "permission_deny_button".equals(id) ? new String[]{id, "permission_deny_and_dont_ask_again_button"} : new String[]{id}) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + name);
                    if (!nodes.isEmpty()) { nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Permission button not found: " + id);
    }

    /** Default Stay connected in background off: leaving Volt cuts the link after ten seconds, so motion afterwards can't record. */
    private void backgroundOffCutoff() {
        prefs().edit().putBoolean("background_connection", false).commit();
        reconnect(); stopped(); settle();
        enable(AutoStartPolicy.AUTO_RECORD_KEY, true);
        enable(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, true);
        stopped(); settle();
        require(!recording(), "Precondition: not recording");
        goHome();
        long end = SystemClock.uptimeMillis() + 14000;
        while (SystemClock.uptimeMillis() < end && onMain(() -> ble.connected())) SystemClock.sleep(100);
        require(!onMain(() -> ble.connected()), "Background off and auto-record on: the ten-second stop still drops the link");
        moving(); settle();
        require(!recording(), "Motion after the cutoff can't start a recording");
        require(!prefs().getBoolean("background_connection", true), "The rider's preference is left alone");
        require(prefs().getBoolean(AutoStartPolicy.AUTO_RECORD_KEY, false), "Auto-record stays on");
        enable(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY, false);
    }

    /**
     * Both switches, each on its page since the Hub layout (#389) moved them out of More › App preferences: Record rides
     * automatically on Ride › Recording settings, Open automatically on Ride › Riding mode settings.
     */
    private void settingsRows() throws Exception {
        for (String[] row : new String[][]{{"Record rides automatically", "ride.recording"}, {"Open automatically", "ride.riding"}}) {
            String label = row[0];
            HubTestPages.reach(this, activity, row[1]); settle();
            final View[] found = {null};
            main(() -> found[0] = find(activity.getWindow().getDecorView(), label));
            require(found[0] != null, label + " row shown");
            main(() -> HubTestPages.requireShown(activity, found[0], label + " on " + row[1]));
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
    /** Only a motion push, no status read: what arrives between two background polls. */
    private void motionOnly() { main(() -> link.motion(1, 18)); SystemClock.sleep(60); waitForIdleSync(); }
    /** Backdates the last status read, so this link is that far into its background poll cycle. */
    private void ageStatus(long ms) { main(() -> set(ble, "statusMs", SystemClock.elapsedRealtime() - ms)); }
    /** A too-old moving sample and no status read, then a change for the service to judge it on. */
    private void staleOnly() {
        main(() -> { set(ble, "speedRaw", 180); set(ble, "driving", 1); set(ble, "motionMs", SystemClock.elapsedRealtime() - 60000); invoke(ble, "changed", new Class<?>[0]); });
        SystemClock.sleep(60); waitForIdleSync();
    }
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
        // Not startActivitySync: it waits for a new instance, and this brings the existing one back.
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await(() -> resumed() && onMain(() -> activity.hasWindowFocus()), "Volt back on screen");
        settle();
    }
    private void shot(String name) throws Exception {
        File folder = new File(getTargetContext().getExternalFilesDir(null), "auto-start-validation"); folder.mkdirs();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        com.jjricks.volt.tests.TextCensus.write(this, new File(folder, name + ".png"));
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
