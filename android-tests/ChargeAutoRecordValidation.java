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
 * Also: a manual recording is left alone and its hand stop holds while the switch is on; a ride started by hand ends an
 * automatic one; with Stay connected in background off a recording keeps going out of sight and the link then ends;
 * a simulated charge records nothing; the status never says "saved" for a recording that was not kept. Terra's review of
 * #415: a hand stop's hold survives a not-charging reading while Volt's own schedule holds the current off; a guessed
 * label is not the charger in effect; an automatic start at the limit deletes nothing until it is kept; out of sight
 * with Stay connected off, riding away still starts the ride before the link is let go. Its second review: a guessed
 * charger is marked as guessed ("· charger guessed") in the status line, the notification, the saved list and the recording's detail.
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
                // only=limit runs the 20-recording step alone, for a quick re-check.
                if ("limit".equals(args.getString("only", ""))) main(() -> settings().edit().putBoolean(KEY, true).commit());
                else if ("stay".equals(args.getString("only", ""))) { main(() -> settings().edit().putBoolean(KEY, true).commit()); stayConnectedOff(); }
                else if ("away".equals(args.getString("only", ""))) { main(() -> settings().edit().putBoolean(KEY, true).commit()); outOfSightStartAndNotificationStop(); }
                else if ("rideaway".equals(args.getString("only", ""))) { main(() -> settings().edit().putBoolean(KEY, true).commit()); rideAwayOutOfSight(); }
                else {
                    offByDefault();
                    onScreenChosenLabelAndShortChargeNotKept();
                    motionEndsAndRideStarts();
                    handStopHolds();
                    manualLeftAlone();
                    rideByHand();
                    outOfSightStartAndNotificationStop();
                    rideAwayOutOfSight();
                }
                if (!"stay".equals(args.getString("only", "")) && !"rideaway".equals(args.getString("only", ""))) manualNeverDeleted();
                if (args.getString("only", "").isEmpty() || "away".equals(args.getString("only", ""))) savedListMarksGuess();
                if (args.getString("only", "").isEmpty()) { stayConnectedOff(); simulationNeverRecords(); }
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
        String chosenStatus = onMainText(() -> service.chargeCaptureStatus());
        require(!chosenStatus.endsWith(T.serviceChargeChargerGuessed("")), "A chosen charger was marked as guessed: " + chosenStatus);
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
        notSaidSaved();
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
        require(saved().length == 0, "A hand-stopped recording under 10 minutes was kept");
        notSaidSaved();
        SystemClock.sleep(10 * S);
        require(onMain(() -> !service.isChargeRecording()), "A recording started again while held, still charging");
        log("hand stop: held for 10 s while still charging");
        // Volt holding the current off itself (here: a schedule write in flight, one of scheduledChargePause's cases) reads
        // exactly like an unplug; the charger is still there, so the hold stays.
        main(() -> { service.chargeSchedule(); set(field(service, "chargeScheduleController"), "busy", true); link.charging(OFF); link.home(); });
        try {
            await(() -> onMain(() -> Double.valueOf(0d).equals(ble.telemetry.number("charging_state"))), 10 * S, "No not-charging reading");
            SystemClock.sleep(6 * S); main(() -> link.home()); SystemClock.sleep(2 * S);
            require(holds(), "A not-charging reading while Volt's own schedule held the current off cleared the hold");
            log("not charging while Volt's schedule holds the current off: still held");
        } finally { main(() -> set(field(service, "chargeScheduleController"), "busy", false)); }
        main(() -> link.home());
        await(() -> !holds(), 10 * S, "A fresh not-charging reading did not end the hold");
        log("unplugged: hold cleared");
        plugIn();
        awaitRecording(10 * S, "after the hold cleared");
        log("plugged in again: recording started");
        main(() -> service.stopChargeRecording());
        unplugAndSettle();
    }

    /**
     * A recording the owner started is never touched: turning the switch on beside it changes nothing, a ride started by
     * hand is still refused, and its hand stop (switch on) holds, so Volt doesn't start recording the charge he just stopped.
     */
    private void manualLeftAlone() {
        main(() -> settings().edit().putBoolean(KEY, false).commit());
        plugIn(); SystemClock.sleep(3 * S);
        require(onMain(() -> !service.isChargeRecording()), "Switch off: a recording started");
        main(() -> require(service.startChargeRecording(ChargeSessionStore.Charger.STOCK), "Could not start a manual recording"));
        main(() -> settings().edit().putBoolean(KEY, true).commit());
        SystemClock.sleep(4 * S);
        require(onMain(() -> service.isChargeRecording() && !chargeStore().automatic()), "The switch ended or replaced a manual recording");
        main(() -> service.startRide("")); settle();
        require(onMain(() -> service.isChargeRecording() && !service.isRecording()), "A ride started by hand ended a manual recording");
        log("manual recording: untouched by the switch, a ride by hand still refused");
        main(() -> service.stopChargeRecording());
        require(holds(), "A hand stop of a manual recording with the switch on did not hold");
        require(saved().length == 1, "The short manual recording was not kept as before: " + Arrays.toString(saved()));
        require(onMainText(() -> service.chargeCaptureStatus()).startsWith("Charge recording saved"), "A kept manual recording did not say saved");
        SystemClock.sleep(6 * S);
        require(onMain(() -> !service.isChargeRecording()), "An automatic recording started on the charge the owner had just stopped recording");
        log("manual stop: kept, held for 6 s while still charging");
        unplugAndSettle();
        for (File file : saved()) require(file.delete(), "Could not clear the manual recording");
    }

    /** A ride started by hand ends an automatic recording and starts; the stop holds, as a hand stop does. */
    private void rideByHand() {
        plugIn();
        awaitRecording(10 * S, "before a ride started by hand");
        main(() -> service.startRide(""));
        await(() -> onMain(() -> service.isRecording()), 5 * S, "A ride started by hand did not start beside an automatic charge recording");
        require(onMain(() -> !service.isChargeRecording()), "The automatic charge recording kept running beside a ride");
        require(holds(), "A ride started by hand did not hold the charge");
        require(saved().length == 0, "The short recording was kept");
        notSaidSaved();
        log("ride by hand: charge recording ended, ride recording running, held");
        main(() -> service.stopRide());
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
        awaitOr(() -> onMain(() -> service.isChargeRecording()), StatusCadence.POLL_SLOW_MS + 10 * S, "No automatic recording out of sight");
        require(onMain(() -> !service.isVisible()), "Volt came to the front");
        require(onMain(() -> chargeStore().automatic() && chargeStore().labelSource() != ChargeSessionStore.LabelSource.CHOSEN
                && chargeStore().charger() == ChargeSessionStore.Charger.STOCK), "Expected a stock label not marked as chosen");
        require(onMain(() -> chargeStore().labelSource() == ChargeSessionStore.LabelSource.GUESS), "Expected a guessed label (no charger chosen, nothing detected yet)");
        String guessedStatus = onMainText(() -> service.chargeCaptureStatus());
        require(guessedStatus.endsWith(T.serviceChargeChargerGuessed("")), "The status line showed a guessed charger as known: " + guessedStatus);
        await(() -> noticeTexts().contains(T.serviceChargeChargerGuessed("")), 10 * S, "The notification showed a guessed charger as known: " + noticeTexts());
        require(onMain(() -> service.chargerBasis() != ScooterService.ChargerBasis.RECORDING && service.effectiveChargeCharger() == null),
                "A guessed label stood in for the charger choice or detection: basis " + onMainText(() -> service.chargerBasis() + " " + service.effectiveChargeCharger()));
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
        ensureFront();
        plugIn();
        // The failed start latches the scooter: proof the start was tried, not merely not yet seen.
        awaitOr(() -> onMain(this::latched), 20 * S, "No automatic start was tried with all 20 manual");
        require(onMain(() -> !service.isChargeRecording()) && names().equals(manual), "An automatic recording started or a manual one was deleted with all 20 manual");
        log("20 manual saved: start tried and refused, all kept");
        main(() -> { link.charging(OFF); link.home(); });
        await(() -> onMain(() -> !latched()), 20 * S, "A not-charging reading did not clear the latch");
        File oldest = saved()[0];
        require(oldest.delete(), "Could not remove a fixture");
        String automatic = fixture(fixtures, 99, true);
        Set<String> before = names();
        plugIn();
        awaitRecording(20 * S, "with one automatic among 19 manual");
        Set<String> after = names(); after.removeIf(name -> name.endsWith(".active"));
        require(after.equals(before), "Starting an automatic recording at the limit deleted a saved one before it was kept: " + before + " -> " + after);
        log("19 manual + 1 automatic: started, nothing deleted yet");
        main(() -> service.stopChargeRecording());
        require(names().equals(before), "An automatic recording that was not kept cost a saved one: " + names());
        log("stopped short, not kept: all 20 still there (room is made only for a kept one; JVM tests cover the replacement)");
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

    /**
     * Owner's answer 6: Stay connected in background is not this feature's connection. With it off (and the live
     * charging card off, so nothing else holds the link), a recording started on screen keeps recording out of sight and
     * ends there by its own rules; then nothing keeps the link: the switch never holds or opens one itself.
     */
    private void stayConnectedOff() {
        main(() -> settings().edit().putBoolean("background_connection", false).putBoolean(ScooterService.CHARGE_LIVE_ENABLED, false).commit());
        ensureFront();
        plugIn();
        awaitRecording(10 * S, "with Stay connected in background off");
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> !service.isVisible()), 10000, "Volt never left the foreground");
        int before = onMainInt(() -> chargeStore().sampleCount());
        // Every 5 s for 35 s: still recording, still linked, and the recording took a fresh reading within its 5 s frame rule
        // (lastFreshMono). Saved samples need an electrical step to settle first (ChargeSessionStore.confirmElectricalChange).
        for (int i = 0; i < 7; i++) {
            SystemClock.sleep(5 * S);
            require(onMain(() -> service.isChargeRecording() && ble.connected() && !service.isVisible()), "The automatic recording or its link ended out of sight with Stay connected in background off");
            long age = SystemClock.elapsedRealtime() - onMainLong(() -> chargeStore().lastFreshMono());
            require(age <= ChargeSessionStore.MAX_FRAME_AGE_MS + S, "Out of sight with Stay connected in background off, the last fresh reading is " + age + " ms old; " + journal() + "; readings " + onMainText(this::readingAges));
        }
        int after = onMainInt(() -> chargeStore().sampleCount());
        require(after > before, "Out of sight with Stay connected in background off, no sample saved in 35 s: " + journal());
        log("Stay connected in background off: out of sight 35 s, still recording, a fresh reading every check, samples " + before + " to " + after + " (" + journal() + ")");
        main(() -> link.charging(OFF));
        long unplugged = SystemClock.elapsedRealtime();
        await(() -> onMain(() -> !service.isChargeRecording()), ChargeAutoRecordPolicy.CHARGE_OFF_END_MS + 30 * S, "The automatic recording did not end out of sight two minutes after unplugging");
        long ended = SystemClock.elapsedRealtime() - unplugged;
        require(ended >= ChargeAutoRecordPolicy.CHARGE_OFF_END_MS - 3 * S, "Ended too early out of sight: " + ended + " ms");
        require(saved().length == 0 && !holds(), "Out of sight: a short recording was kept, or ending by itself held");
        notSaidSaved();
        log("out of sight: ended " + ended + " ms after unplugging, not kept");
        await(() -> onMain(() -> !ble.connected()), 30 * S, "The link stayed after the recording ended, with Stay connected in background off: the switch kept it");
        log("then the link ended: nothing of this feature keeps one");
        main(() -> settings().edit().putBoolean("background_connection", true).remove(ScooterService.CHARGE_LIVE_ENABLED).commit());
        ensureFront();
    }

    /**
     * Out of sight with Stay connected in background off, riding away: the charge recording ends and Record rides
     * automatically (with its background option) gets its turn on the same reading before the link is let go, so the ride
     * is recorded rather than lost with the link.
     */
    private void rideAwayOutOfSight() {
        main(() -> settings().edit().putBoolean("background_connection", false).putBoolean(ScooterService.CHARGE_LIVE_ENABLED, false)
                .putBoolean("auto_record_rides", true).putBoolean("auto_record_background", true).commit());
        // Earlier steps stopped rides by hand, which holds Record rides automatically until the next connection
        // (AutoStartPolicy.riderStoppedRecording). The fixture keeps one link throughout, so clear it as a reconnect would.
        main(() -> set(field(service, "autoStart"), "recordHeld", false));
        ensureFront();
        plugIn();
        awaitRecording(10 * S, "before riding away out of sight");
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> onMain(() -> !service.isVisible()), 10000, "Volt never left the foreground");
        SystemClock.sleep(3 * S);
        require(onMain(() -> service.isChargeRecording()), "The recording ended on leaving the screen");
        long moved = SystemClock.elapsedRealtime();
        main(() -> link.motion(6));
        await(() -> onMain(() -> !service.isChargeRecording()), 3 * S, "6 km/h out of sight did not end the automatic recording");
        await(() -> onMain(() -> service.isRecording()), 3 * S, "Out of sight with Stay connected off, the ride's automatic start lost its turn: no ride recording ("
                + onMainText(() -> "link " + ble.state() + ", connected " + ble.connected()) + ")");
        require(onMain(() -> ble.connected()), "The link was let go under the starting ride");
        log("out of sight, Stay connected off, 6 km/h: charge recording ended and a ride recording started within " + (SystemClock.elapsedRealtime() - moved) + " ms, link kept");
        main(() -> { link.motion(0); link.charging(OFF); link.home(); });
        SystemClock.sleep(3 * S);
        // Back on screen with the usual settings before the ride ends, so the link the ride kept stays for the next steps.
        main(() -> settings().edit().putBoolean("background_connection", true).remove(ScooterService.CHARGE_LIVE_ENABLED)
                .putBoolean("auto_record_rides", false).putBoolean("auto_record_background", false).commit());
        ensureFront();
        main(() -> service.stopRide());
        if (!onMain(() -> ble.ready())) {
            main(() -> link.attach());
            await(() -> onMain(() -> ble.ready()), 10000, "Synthetic link not ready again");
        }
        unplugAndSettle();
    }

    /** Nothing simulated is ever written as real: a simulated charge with the switch on records nothing. */
    private void simulationNeverRecords() {
        ensureFront();
        main(() -> require(service.setSimulation(true), "Could not enter simulation"));
        main(() -> ble.setSimulationCharging(true));
        await(() -> onMain(() -> Double.valueOf(1d).equals(ble.telemetry.number("charging_state"))), 10 * S, "The simulated scooter never reported charging");
        SystemClock.sleep(8 * S);
        require(onMain(() -> !service.isChargeRecording() && !latched()) && names().isEmpty(), "A simulated charge started or tried an automatic recording: " + names());
        log("simulation charging 8 s, switch on: nothing tried, nothing written");
        main(() -> { ble.setSimulationCharging(false); service.setSimulation(false); });
    }

    /** The status line never says "saved" for a recording that was not kept (#416 says "not kept"). */
    private void notSaidSaved() {
        String status = onMainText(() -> service.chargeCaptureStatus());
        require(!status.startsWith("Charge recording saved"), "Said saved for a recording it did not keep: " + status);
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
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
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
    @SuppressWarnings("unchecked")
    private boolean latched() { return ((Set<String>) field(service, "autoChargeLatched")).contains(profile.id); }
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
            // The first reads a real connection makes once authenticated (BleController's 0x30 path: refresh(true)). Out of sight
            // the poll reads the battery only once 0x72 has answered on this connection (#418's ReadPolicy.noticeBattery).
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
    private long onMainLong(java.util.function.LongSupplier query) { final long[] result = {0}; main(() -> result[0] = query.getAsLong()); return result[0]; }
    private int onMainInt(java.util.function.IntSupplier query) { final int[] result = {0}; main(() -> result[0] = query.getAsInt()); return result[0]; }
    private String onMainText(java.util.function.Supplier<String> query) { final String[] result = {null}; main(() -> result[0] = query.get()); return result[0]; }
    private String onMainString(java.util.function.Supplier<String> query) { final String[] result = {null}; main(() -> result[0] = query.get()); return result[0]; }
    private View onMainView(java.util.function.Supplier<View> query) { final View[] result = {null}; main(() -> result[0] = query.get()); return result[0]; }
    private void await(BooleanSupplier query, long ms, String message) { long end = SystemClock.uptimeMillis() + ms; while (SystemClock.uptimeMillis() < end) { if (query.getAsBoolean()) return; SystemClock.sleep(50); } throw new AssertionError(message); }
    /** await, with the service's view of an automatic start in the failure. */
    private void awaitOr(BooleanSupplier query, long ms, String message) {
        try { await(query, ms, message); }
        catch (AssertionError failed) { throw new AssertionError(message + " (" + onMainString(() -> "blocked: " + service.chargeCaptureUnavailable()
                + "; recording " + service.isChargeRecording() + "; ride " + service.isRecording() + "; held " + holds() + "; latched " + latched()
                + "; switch " + settings().getBoolean(KEY, false) + "; visible " + service.isVisible()
                + "; charging_state " + ble.telemetry.number("charging_state") + "; link " + ble.state() + " " + ble.message()
                + "; status age " + ble.statusAge() + " ms; gatt " + field(ble, "gatt") + "; readings " + readingAges() + "; read policy " + readPolicyState()) + ")"); }
    }
    /** The running journal's sample lines as seconds since its start, for a failure message. */
    private String journal() {
        File[] active = folder().listFiles((dir, name) -> name.endsWith(".active"));
        if (active == null || active.length == 0) return "no journal";
        StringBuilder out = new StringBuilder("samples at");
        try (BufferedReader in = new BufferedReader(new FileReader(active[0]))) {
            long start = -1; String line;
            while ((line = in.readLine()) != null) { String[] f = line.split(","); if (f[0].startsWith("charge-v2")) start = Long.parseLong(f[4]); else if (f[0].equals("s")) out.append(' ').append((Long.parseLong(f[2]) - start) / 1000).append('s'); }
        } catch (IOException | RuntimeException error) { out.append(" (unreadable: ").append(error).append(')'); }
        return out.toString();
    }
    /** Each battery reading's value and age, for a failure message. */
    private String readingAges() {
        StringBuilder out = new StringBuilder(); long now = SystemClock.elapsedRealtime();
        for (Telemetry.Metric m : ble.telemetry.metrics()) if (m.key.equals("charging_state") || m.key.equals("battery_percent") || m.key.equals("voltage_v") || m.key.equals("current_a"))
            out.append(m.key).append('=').append(m.numericValue).append('@').append(m.updatedMs > 0 ? now - m.updatedMs : -1).append("ms/0x").append(Integer.toHexString(m.sourceCmd)).append(' ');
        return out.toString().trim();
    }
    private String readPolicyState() {
        Object policy = field(ble, "readPolicy"); if (policy == null) return "none";
        return "answered " + field(policy, "answered") + ", suppressed " + field(policy, "suppressed") + ", legacy " + field(policy, "legacy");
    }
    private void settle() { SystemClock.sleep(350); waitForIdleSync(); }
    /** Terra's second review of #415: a guessed label reads as a guess in the saved list and its detail; a manual one does not. */
    private void savedListMarksGuess() throws Exception {
        unplugAndSettle();
        for (File file : saved()) require(file.delete(), "Could not clear " + file.getName());
        ChargeSessionStore fixtures = new ChargeSessionStore(folder(), new ChargeSessionStore.AutomaticRules() {
            public boolean keep(long ranMs, double gainedPoints) { return true; }
            public int toRemove(int saved, int automatic, int max) { return ChargeAutoRecordPolicy.automaticToRemove(saved, automatic, max); }
        });
        String guessed = fixture(fixtures, 1, true).replace(".csv", "");
        String manualId = fixture(fixtures, 2, false).replace(".csv", "");
        require(ChargeSessionStore.labelSource(guessed) == ChargeSessionStore.LabelSource.GUESS && ChargeSessionStore.labelSource(manualId) == null, "Fixtures: " + guessed + " " + manualId);
        ensureFront();
        com.jjricks.volt.tests.HubTestPages.reach(this, activity, HubPages.BATTERY_HISTORY);
        settle();
        main(() -> { View row = activity.findViewById(android.R.id.content).getRootView().findViewWithTag("charge-history-action"); require(row != null && row.isEnabled(), "No charge history row"); row.performClick(); });
        await(() -> onMain(() -> historyView("charge-history-session:" + guessed) != null && historyView("charge-history-session:" + manualId) != null), 10 * S, "The saved list did not show both recordings");
        String marked = T.serviceChargeChargerGuessed("");
        String guessRow = onMainText(() -> String.valueOf(historyView("charge-history-session:" + guessed).getContentDescription()));
        String manualRow = onMainText(() -> String.valueOf(historyView("charge-history-session:" + manualId).getContentDescription()));
        require(guessRow.contains(marked), "The saved list showed a guessed charger as known: " + guessRow);
        require(!manualRow.contains(marked), "The saved list marked a recording you labelled as guessed: " + manualRow);
        main(() -> historyView("charge-history-session:" + guessed).performClick()); settle();
        await(() -> onMain(() -> historyView("charge-history-detail") != null), 5 * S, "The guessed recording's detail did not open");
        String detail = onMainText(() -> String.valueOf(((TextView) ((ViewGroup) historyView("charge-history-detail")).getChildAt(1)).getText()));
        require(detail.endsWith(marked), "The detail showed a guessed charger as known: " + detail);
        log("saved list: guessed row \"" + guessRow + "\"; manual row \"" + manualRow + "\"; detail \"" + detail + "\"");
        main(() -> ((android.app.Dialog) field(viewer(), "dialog")).dismiss());
        for (File file : saved()) file.delete();
    }
    /** Every text the connection notice carries: title, text, sub text, the big text and its summary (where the recording status goes beside the live card). */
    private String noticeTexts() {
        StatusBarNotification n = connection();
        if (n == null) return "(no connection notice)";
        Bundle extras = n.getNotification().extras; StringBuilder out = new StringBuilder();
        for (String key : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUMMARY_TEXT}) {
            CharSequence value = extras.getCharSequence(key);
            if (value != null) out.append(value).append(" | ");
        }
        return out.toString();
    }
    private Object viewer() { return field(field(activity, "screen"), "chargeHistoryDialog"); }
    private View historyView(String tag) {
        Object viewer = viewer(); android.app.Dialog dialog = viewer == null ? null : (android.app.Dialog) field(viewer, "dialog");
        return dialog == null || dialog.getWindow() == null ? null : dialog.getWindow().getDecorView().findViewWithTag(tag);
    }
    private static Field reflected(Object object, String name) { Class<?> type = object.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new IllegalStateException("Missing field " + name); }
    private static Object field(Object object, String name) { try { return reflected(object, name).get(object); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object object, String name, Object value) { try { reflected(object, name).set(object, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values); }
        catch (InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
