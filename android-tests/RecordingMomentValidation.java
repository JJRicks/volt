package com.jjricks.volt;

import com.jjricks.volt.tests.ValidationInstrumentation;
import android.Manifest;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Disposable empty emulator, radio off. Clean Home's recording moment, the Ride clock (Ollie's mock-up, Nov 24 2026: the approved
 * mock-up's "C · Ride clock"), at the emulator's current size, theme (-e theme), text size (-e text_scale) and units
 * (-e units), on a recording the validation seeds itself: the way existing validations make one (RideStore.start on the
 * service's own store, back-dated samples, as HubValidation's and LocaleValidation's rides), never a recorder start, which
 * refuses sample data. Nothing reaches a scooter.
 *
 * <p>-e mode sim (the default), on the developer simulator with readings held at the mock-up's (73%, 37.2 km of range,
 * 27 km/h, the odometer still; ScooterSimulation.hold, the validations' own seam):
 * <ul>
 *   <li>Parked: Home's place measured (the Lock bar, the top area and the scooter's name) and pictured.</li>
 *   <li>A recording started 14:38 ago, 4.02 km (2.5 mi) so far with a stop at a red light: Clean Home shows the recording
 *       moment, the Lock bar, the top area and the name exactly where they were parked; its time is the Ride row's own
 *       ("Recording · 14:38"); the Range cell is the parked card's figure ("about 23 mi left": the same number) marked as
 *       an estimate; the ribbon draws the ride; the Open riding mode button is whole on screen, at least 48 dp high, and
 *       opens riding mode, and a tap on the moment opens the Ride page. Every part fits inside the top area.</li>
 *   <li>Saving paused, storage full while recording and the failed save: the head row says so in orange in Home's own
 *       words, whole on screen (full, short, or without "Recording"), the dot hollow and still, no speed now; cleared,
 *       the moment is healthy again.</li>
 *   <li>Reconnecting (the simulator's dropout): the head row says so in riding mode's words, the dot hollow.</li>
 *   <li>Stopped through the service's own stop: Just back shows that ride, the Lock bar still in its place.</li>
 *   <li>A new recording with no speed reading: the ribbon's calm empty state, no line.</li>
 * </ul>
 * <p>-e mode range, without the simulator (Bluetooth off, so the link is lost): a scooter range of 30 km from the cached
 * readings and three past rides that teach Volt's learned range (11 km at 50%, as LearnedRangeValidation's): the parked
 * card's range line is read under each range source, then a recording shows the same figure in its Range cell under
 * each source (last seen, the link lost), as Not connected in the head row.
 *
 * <p>Pictures named by -e size_label. Sends no scooter command.
 */
public final class RecordingMomentValidation extends ValidationInstrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String size = "size", units = "mi", profile;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    /** The mock-up's sample: 14:38 recorded, 2.5 mi (4.02 km), 73%, about 23 mi (37.2 km) left, 16.8 mph (27.04 km/h). */
    private static final long ELAPSED_MS = 878_000L;
    private static final double TRIP_KM = 4.02, ODOMETER_KM = 1204.02, RANGE_KM = 37.2, SPEED_KMH = 27.04;
    /**
     * The fixture's sample spacing (ms), well under RideStore.MAX_INTEGRATION_INTERVAL_MS (3000): wider, every interval is
     * a gap and the ribbon has no line to draw. Spacing the samples over the time since boot did that once an emulator had
     * been up about 9 minutes.
     */
    private static final long SAMPLE_STEP_MS = 2000;
    /** When the last fixture recording was seeded (wall clock), for the time check. */
    private long seededWall;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            size = args.getString("size_label", "size");
            units = args.getString("units", "mi");
            String mode = args.getString("mode", "sim");
            require(mode.equals("sim") || mode.equals("range"), "Unknown -e mode " + mode + " (sim or range)");
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", args.getString("theme", "dark")).putString("units", units)
                    .putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1")))
                    .putString("home_style", "clean").putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .commit(), "Could not seed preferences");
            if (mode.equals("sim")) {
                main(() -> {
                    ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                    ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                    profiles.update(p); profiles.select(p.id); profile = p.id; OnboardingScreen.markDone(getTargetContext());
                });
                launch();
                simulate();
                sim();
            } else {
                seedRangeHistory();
                launch();
                range();
            }
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> {
                if (service != null && service.isRecording()) service.rides.stop(System.currentTimeMillis(), SystemClock.elapsedRealtime());
                if (service != null && service.simulating()) service.setSimulation(false);
                activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== -e mode sim: the mock-up's recording on the simulator =====

    private void sim() throws Exception {
        hold("battery_percent", "73 %", 73);
        hold("remaining_range_km", RANGE_KM + " km", RANGE_KM);
        hold("odometer_km", ODOMETER_KM + " km", ODOMETER_KM);
        hold("speed_kmh", SPEED_KMH + " km/h", SPEED_KMH);
        main(() -> { service.ble.tickSimulation(); activity.redraw(); });
        await(() -> moment() == HomeMoment.PARKED, 10000, "Clean Home is not parked before the recording");
        settle();
        int[] parked = place();
        String parkedRange = parkedRange();
        // 37.2 km is "about 23 mi left" in miles and with both units, "about 37 km left" in km (-e units).
        String parkedFigure = "km".equals(units) ? "37 km" : "23 mi";
        require(quantities(parkedRange).contains(parkedFigure), "The parked card's range line reads \"" + parkedRange + "\" for 37.2 km held, not " + parkedFigure);
        shot("parked");
        checks.add(size + ": parked, the range line \"" + parkedRange + "\", Lock bar " + describe(parked));

        String ride = seedRecording(ELAPSED_MS, true);
        showRecording(ride);
        int[] recording = place();
        require(Arrays.equals(parked, recording), "The Lock bar, the top area or the scooter name moved for the recording: " + describe(parked) + " -> " + describe(recording));
        requireFits();
        requireSameTime();
        requireRange(parkedRange);
        requireRibbon(true);
        requireButton();
        shot("recording");
        checks.add(size + ": the recording moment in place of the battery card, nothing below it moved " + describe(recording)
                + "; its time the Ride row's; its range the parked card's (\"" + text(stat(2)) + "\" for \"" + parkedRange + "\"); every part inside the area; the button whole and at least 48 dp");
        openRidingFromButton();
        openRidePageFromMoment();
        storagePaused(parked);

        // Reconnecting: the simulator's dropout holds the link in Connecting, as a real dropout does.
        main(() -> service.ble.setSimulationScript(ScooterSimulation.DROPOUT));
        await(() -> { activity.redraw(); return "Reconnecting…".contentEquals(side().getText()) || "Not connected".contentEquals(side().getText()); }, 10000,
                "The head row does not say the link is lost: \"" + onMain(() -> side().getText()) + "\"");
        require(onMain(() -> field(view(), "look")) == RecordingPill.Look.RECORDING_PROBLEM, "The dot does not take the problem look");
        // The header's status line may change with the link (as it always has); the top area keeps its height and the
        // Lock bar its place under it.
        requireAreaKept(parked, "the link problem");
        shot("reconnecting");
        checks.add(size + ": link lost: \"" + onMain(() -> side().getText()) + "\" in the head row, in riding mode's words, the dot hollow");
        main(() -> { service.ble.setSimulationScript(""); service.ble.tickSimulation(); activity.redraw(); });
        await(() -> service.ble.ready(), 10000, "The simulated scooter did not come back");

        // Stopped through the service's own stop: Just back takes over in the same place.
        // Clean Home's own refreshes only (no forced look at the rides): the recording hands over to Just back with no
        // battery card or other moment in between (HomeMoment.holdRecording).
        main(() -> service.stopRide());
        List<Integer> seen = new ArrayList<>();
        await(() -> { activity.redraw(); int m = moment(); if (seen.isEmpty() || seen.get(seen.size() - 1) != m) seen.add(m); return m == HomeMoment.JUST_BACK && !service.isRecording(); },
                15000, "Clean Home does not show Just back after the recording stopped (moments " + seen + ")");
        require(seen.size() <= 2 && seen.get(seen.size() - 1) == HomeMoment.JUST_BACK && (seen.size() == 1 || seen.get(0) == HomeMoment.RECORDING),
                "The recording did not hand over straight to Just back: moments " + seen);
        checks.add(size + ": stopped, the moments went " + seen + " (recording, then Just back, nothing between)");
        require(ride.equals(onMain(() -> ((JustBackView) root().findViewWithTag(JustBackView.TAG)).rideId())), "Just back shows another ride than the one just stopped");
        // After the dropout the header's status line may still differ from the parked one (as it always has); the top
        // area keeps its height and the Lock bar its place under it.
        requireAreaKept(parked, "Just back after the recording");
        shot("just-back");
        checks.add(size + ": stopped, Just back shows the ride just recorded, the Lock bar still in place");

        // A new recording with no reading yet (the link drops as it starts, so the recorder marks a gap and stores no
        // speed): the ribbon's calm empty state, the head row saying why.
        main(() -> service.ble.setSimulationScript(ScooterSimulation.DROPOUT));
        await(() -> !service.ble.ready(), 10000, "The simulated link did not drop");
        String empty = seedRecording(40_000L, false);
        showRecording(empty);
        main(() -> setField(clean(), "trendAt", -1L));
        SystemClock.sleep(1500);
        requireRibbon(false);
        requireFits();
        shot("no-speed");
        checks.add(size + ": a recording with no speed reading: the ribbon shows its label and baseline, no line");
        main(() -> { service.ble.setSimulationScript(""); service.ble.tickSimulation(); });
    }

    /**
     * The service pauses saving the ride, in both its states (ScooterService.recordingPause, set with the recordingError
     * its sample write's or its save's failure sets; a real full storage can't be made on a disposable emulator): the
     * head row shows Home's own words for it in orange, whole on screen by the narrow-label ladder (full, then short, then
     * without "Recording"; no ellipsis, inside the row, clear of "Recording"); the dot takes the problem look (hollow,
     * still), the speed now goes and the description says it; the top area and the Lock bar stay put. Cleared, the
     * moment is healthy again.
     */
    private void storagePaused(int[] parked) throws Exception {
        // The service's error text is a marker here, not its real English: Home must show its own words, never that text.
        pausedState(parked, RecordingFit.Pause.STORAGE_FULL, "service error text (storage)", "storage-paused");
        pausedState(parked, RecordingFit.Pause.SAVE_FAILED, "service error text (save)", "save-failed");
        main(() -> { setField(service, "recordingError", null); setField(service, "recordingPause", RecordingFit.Pause.NONE); });
        await(() -> { activity.redraw(); return field(view(), "look") == RecordingPill.Look.RECORDING && ((View) field(view(), "label")).isShown()
                && !String.valueOf(side().getText()).equals(HubText.recordingPaused(RecordingFit.Pause.SAVE_FAILED, false).render()); }, 10000,
                "Cleared, the moment did not return to Recording: \"" + onMain(() -> side().getText()) + "\"");
        requireFits();
        checks.add(size + ": saving resumed: the moment healthy again, \"" + onMain(() -> side().getText()) + "\"");
    }

    private void pausedState(int[] parked, RecordingFit.Pause pause, String serviceWords, String shotName) throws Exception {
        String full = HubText.recordingPaused(pause, true).render(), shorter = HubText.recordingPaused(pause, false).render();
        main(() -> { setField(service, "recordingPause", pause); setField(service, "recordingError", serviceWords); });
        await(() -> { activity.redraw(); String shown = side().getText().toString(); return shown.equals(full) || shown.equals(shorter); }, 10000,
                "The head row does not say the recording is paused (" + pause + "): \"" + onMain(() -> side().getText()) + "\"");
        settle();
        String[] seen = new String[2];
        main(() -> {
            RecordingView moment = view();
            TextView words = side(), label = (TextView) field(moment, "label");
            seen[0] = words.getText().toString();
            seen[1] = label.isShown() ? "beside Recording" : "beside the dot alone";
            require(field(moment, "look") == RecordingPill.Look.RECORDING_PROBLEM, "Paused, the dot does not take the problem look");
            require(field(moment, "pulse") == null, "Paused, the dot still pulses");
            require(words.isShown() && words.getCurrentTextColor() == ((Ui) field(moment, "ui")).orange, "Paused, the words are not shown in orange");
            // What fits on screen, not only the text: one line, no ellipsis, the words' width inside the view, the view inside
            // the head row and clear of "Recording" where it shows.
            require(words.getLayout() != null && words.getLineCount() == 1 && words.getLayout().getEllipsisCount(0) == 0,
                    "Paused, the words are cut: \"" + seen[0] + "\"");
            require(words.getPaint().measureText(seen[0]) <= words.getWidth() - words.getPaddingLeft() - words.getPaddingRight() + 1,
                    "Paused, the words \"" + seen[0] + "\" are wider than their view (" + words.getWidth() + " px)");
            Rect row = windowRect(field(moment, "head")), side = windowRect(words);
            require(row.contains(side), "Paused, the words " + side + " run out of the head row " + row);
            if (label.isShown()) require(windowRect(label).right <= side.left, "Paused, the words overlap \"Recording\"");
            require(!(Boolean) field(field(moment, "ribbon"), "nowShown"), "Paused, the ribbon still shows a speed now");
            require(String.valueOf(moment.getContentDescription()).contains(full), "Paused, the description does not say so: " + moment.getContentDescription());
            require(!String.valueOf(moment.getContentDescription()).contains(serviceWords) && !seen[0].contains(serviceWords),
                    "Paused, Home shows or reads the service's own error text");
        });
        requireAreaKept(parked, "saving paused (" + pause + ")");
        requireFits();
        shot(shotName);
        checks.add(size + ": saving paused (" + pause + "): \"" + seen[0] + "\" " + seen[1] + ", whole, in orange; the dot hollow and still, no speed now, spoken");
    }

    /** Opens riding mode with the button (the Ride quick action's way) and comes back with Back. */
    private void openRidingFromButton() throws Exception {
        // A real tap at the button's centre, through the window (not performClick), as a finger would.
        tap(button());
        await(() -> field(activity, "riding") != null, 10000, "Open riding mode did not open riding mode");
        settle();
        // Closed the way the riding validations close it (the system's full-screen hint may hold the first Back).
        main(() -> invoke(activity, "closeRiding", new Class<?>[0]));
        await(() -> field(activity, "riding") == null && moment() == HomeMoment.RECORDING, 10000, "Closing riding mode did not return to the recording");
        settle();
        checks.add(size + ": Open riding mode opens riding mode (the Ride quick action's way); closed, Home shows the recording again");
    }

    /** A tap on the moment opens the Ride page, as the Ride row does. */
    private void openRidePageFromMoment() throws Exception {
        main(() -> require(view().performClick(), "The recording moment took no tap"));
        await(() -> HubPages.RIDE.equals(field(activity, "selectedPage")), 10000, "A tap on the recording did not open the Ride page (" + onMain(() -> field(activity, "selectedPage")) + ")");
        settle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(() -> HubPages.HOME.equals(field(activity, "selectedPage")) && moment() == HomeMoment.RECORDING, 10000, "Back from the Ride page did not return to the recording");
        settle();
        checks.add(size + ": a tap on the moment opens the Ride page; Back returns");
    }

    // ===== -e mode range: the Range cell under both range sources, as the parked card =====

    private void range() throws Exception {
        await(() -> service.displayReadings().get("battery_percent") != null && service.displayReadings().get("battery_percent").number != null, 10000, "Cached readings absent");
        await(() -> moment() == HomeMoment.PARKED, 10000, "Clean Home is not parked");
        main(() -> require(!service.usesLearnedRange(), "A new scooter does not start on the scooter's range"));
        settle();
        int[] parked = place();
        String scooterLine = parkedRange();
        shot("range-parked-scooter");
        main(() -> service.useLearnedRange(true));
        await(() -> { activity.redraw(); TelemetryHistory.Reading r = service.dashboardReadings().get("remaining_range_km"); return r != null && r.number != null && Math.abs(r.number - 11) < .01; },
                20000, "Volt's learned range did not arrive");
        settle();
        String learnedLine = parkedRange();
        require(!learnedLine.equals(scooterLine), "The parked card's range line did not change with the range source: \"" + learnedLine + "\"");
        shot("range-parked-learned");
        main(() -> service.useLearnedRange(false));

        String ride = seedRecording(ELAPSED_MS, true);
        showRecording(ride);
        require(Arrays.equals(parked, place()), "Home moved for the recording");
        requireFits();
        requireRange(scooterLine);
        await(() -> { activity.redraw(); return side().getText().length() > 0 && !side().getText().toString().startsWith("Started"); }, 10000,
                "The head row does not say the link is lost: \"" + onMain(() -> side().getText()) + "\"");
        shot("range-scooter");
        String scooterCell = onMain(() -> text(stat(2)));
        main(() -> service.useLearnedRange(true));
        await(() -> { activity.redraw(); return !text(stat(2)).equals(scooterCell); }, 20000, "The Range cell did not follow the range source to learned");
        requireRange(learnedLine);
        shot("range-learned");
        checks.add(size + " " + units + ": the Range cell follows the range source as the parked card does: scooter \"" + scooterCell + "\" (parked \"" + scooterLine
                + "\"), learned \"" + onMain(() -> text(stat(2))) + "\" (parked \"" + learnedLine + "\"); link lost: \"" + onMain(() -> side().getText()) + "\"");
        main(() -> service.useLearnedRange(false));
    }

    /** The cached readings (50%, 30 km) and three finished rides that teach 22 km a full battery, as LearnedRangeValidation's. */
    private void seedRangeHistory() throws Exception {
        AtomicReference<TelemetryHistory> store = new AtomicReference<>();
        AtomicReference<TelemetryHistory.Snapshot> done = new AtomicReference<>();
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile p = profiles.create("Synthetic range fixture", "000000000000", 0);
            profile = p.id; profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            long now = System.currentTimeMillis() - 60000;
            Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
            values.put("battery_percent", new TelemetryHistory.Reading("battery_percent", "Battery", "Battery", "50 %", "%", 50d, now, true));
            values.put("remaining_range_km", new TelemetryHistory.Reading("remaining_range_km", "Scooter range", "Ride", "30 km", "km", 30d, now, true));
            store.set(new TelemetryHistory(getTargetContext()));
            store.get().record(profile, new TelemetryHistory.Snapshot(values, Collections.emptyMap(), now), 1, true);
            store.get().load(profile, done::set);
        });
        await(() -> done.get() != null, 15000, "History seed timeout");
        main(() -> store.get().close());
        learnedRide(8, 2); learnedRide(8, 1.2); learnedRide(4, 1.2);
    }

    private void learnedRide(double drop, double km) throws Exception {
        RideStore fixture = new RideStore(new File(getTargetContext().getCacheDir(), "range-" + UUID.randomUUID()));
        long start = System.currentTimeMillis() - 7_200_000L, mono = 1000;
        String id = fixture.start(profile, "Synthetic range", start, mono);
        for (int sec = 0; sec <= 600; sec++) {
            long t = mono + sec * 1000L;
            Map<String, RideStore.Sample> values = new LinkedHashMap<>();
            values.put("battery_percent", new RideStore.Sample(80 - drop * sec / 600, t));
            values.put("odometer_km", new RideStore.Sample(100 + km * sec / 600, t));
            values.put("speed_kmh", new RideStore.Sample(km * 6, t));
            values.put("charging_state", new RideStore.Sample(0, t));
            fixture.sample(start + sec * 1000L, t, values);
        }
        fixture.stop(start + 600000, mono + 600000);
        File rides = new File(getTargetContext().getFilesDir(), "rides");
        require(rides.isDirectory() || rides.mkdirs(), "Rides folder");
        fixture.exportCsv(id, new File(rides, id + ".csv"));
    }

    // ===== The fixture recording =====

    /**
     * A recording in the service's own ride store, started elapsedMs ago (wall clock): with samples, the mock-up's ride (a
     * ride with a stop at a red light, 4.02 km so far, 78% to 73%), up to the odometer the simulator holds; without, none
     * yet. The service then records on, as for any active ride. Its monotonic start is as far back as this boot allows.
     * The samples run back from 1.5 s ago every {@link #SAMPLE_STEP_MS} on both clocks (closer on the monotonic one only on
     * an emulator up for less than the ride), so no interval is a gap however long the emulator has been up.
     */
    private String seedRecording(long elapsedMs, boolean samples) {
        return onMain(() -> {
            long wall = System.currentTimeMillis(), nowMono = SystemClock.elapsedRealtime();
            seededWall = wall;
            long start = wall - elapsedMs, mono = Math.max(1000L, nowMono - elapsedMs);
            String id = service.rides.start(profile, "Synthetic Ride clock", start, mono);
            if (samples) {
                int steps = (int) ((elapsedMs - 1500) / SAMPLE_STEP_MS);
                long wallStep = SAMPLE_STEP_MS, monoStep = Math.min(SAMPLE_STEP_MS, (nowMono - 1500 - mono) / steps);
                require(monoStep > 0 && monoStep < RideStore.MAX_INTEGRATION_INTERVAL_MS, "The fixture's sample step " + monoStep + " ms is not under the gap limit");
                long wallFirst = wall - 1500 - steps * wallStep, monoFirst = nowMono - 1500 - steps * monoStep;
                double km = 0;
                for (int i = 0; i <= steps; i++) {
                    double t = i / (double) steps;
                    // Pull away, ride, a red light (stopped) a little past half way, then ride on.
                    double ride = 23 + 4 * Math.sin(t * 13) + 1.5 * Math.sin(t * 41);
                    // Slowing into the light and pulling away again, as a rider does.
                    double light = t < .50 ? 1 : t < .53 ? (.53 - t) / .03 : t < .60 ? 0 : t < .64 ? (t - .60) / .04 : 1;
                    double kmh = i == 0 ? 0 : t < .06 ? Math.min(ride, 340 * t) : ride * light;
                    if (i == steps) kmh = SPEED_KMH;
                    km += i == 0 ? 0 : kmh * wallStep / 3_600_000.0;
                    Map<String, RideStore.Sample> s = new LinkedHashMap<>();
                    long at = monoFirst + i * monoStep;
                    s.put("speed_kmh", new RideStore.Sample(kmh, at));
                    s.put("battery_percent", new RideStore.Sample(78 - 5 * t, at));
                    s.put("odometer_km", new RideStore.Sample(ODOMETER_KM - TRIP_KM + TRIP_KM * Math.min(1, t), at));
                    service.rides.sample(wallFirst + i * wallStep, at, s);
                }
                RideStore.Summary seeded = service.rides.activeSummary();
                require(seeded != null && seeded.gapCount == 0, "The fixture recording has " + (seeded == null ? "no summary" : seeded.gapCount + " gaps")
                        + " (sample step " + monoStep + " ms, up " + nowMono / 1000 + " s)");
                checks.add(size + ": the fixture's " + (steps + 1) + " samples every " + monoStep + " ms (up " + nowMono / 1000 + " s), no gap");
            }
            require(service.isRecording(), "The fixture recording is not recording");
            return id;
        });
    }

    /** Clean Home looks again and shows the recording moment for ride id, with its ribbon read. */
    private void showRecording(String id) throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle();
        await(() -> { Object clean = clean(); setField(clean, "ridesLooked", -1L); activity.redraw(); return moment() == HomeMoment.RECORDING && id.equals(view().rideId()); },
                10000, "Clean Home does not show the recording moment (moment " + onMain(this::moment) + ")");
        SystemClock.sleep(900); settle();
    }

    // ===== Checks =====

    /** Every part of the moment inside the top area, none cut, and the head row's "Recording" whole. */
    private void requireFits() {
        main(() -> {
            View area = root().findViewWithTag(CleanHomeScreen.TAG_TOP), moment = view();
            Rect top = windowRect(area);
            require(windowRect(moment).equals(top), "The recording does not fill the top area: " + windowRect(moment) + " in " + top);
            for (String tag : new String[]{RecordingFit.TAG_CLOCK, RecordingFit.TAG_STATS, RecordingFit.TAG_RIDING}) {
                View part = moment.findViewWithTag(tag);
                require(part != null && part.isShown(), tag + " is not shown");
                View box = tag.equals(RecordingFit.TAG_CLOCK) ? (View) part.getParent() : part;
                require(top.contains(windowRect(box)), tag + " " + windowRect(box) + " runs out of the top area " + top);
            }
            View ribbon = moment.findViewWithTag(RecordingFit.TAG_RIBBON);
            if (ribbon.isShown()) require(top.contains(windowRect(ribbon)) && ribbon.getHeight() >= dp(RecordingFit.MIN_RIBBON_DP) - 1,
                    "The ribbon " + windowRect(ribbon) + " is not whole in the area or under its least height");
            TextView label = (TextView) field(moment, "label");
            // While saving is paused, "Recording" may give way to the paused words (RecordingFit.pausedHead) on a narrow row.
            if (field(moment, "pause") == RecordingFit.Pause.NONE || label.isShown()) require(label.isShown() && label.getLayout() != null && label.getLayout().getEllipsisCount(0) == 0
                    && label.getPaint().measureText(label.getText().toString()) <= label.getWidth() + 1, "\"Recording\" is not whole");
            TextView clock = (TextView) field(moment, "clock");
            require(clock.getPaint().measureText(clock.getText().toString()) <= moment.getWidth() + 1, "The time is wider than the area");
            int bottom = top.bottom - windowRect(field(moment, "button")).bottom;
            require(bottom < dp(40), "A band of " + bottom + " px under the button");
        });
    }

    /** The moment's time is the Ride row's ("Recording · 14:38") and riding mode's REC pill's (RecordingFit.elapsedMs). */
    private void requireSameTime() {
        String[] seen = {null, null};
        await(() -> {
            activity.redraw();
            Object subjects = twoPanes() ? field(field(activity, "screen"), "subjects") : field(clean(), "subjects");
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) field(subjects, "ids");
            @SuppressWarnings("unchecked") List<TextView> values = (List<TextView>) field(subjects, "values");
            seen[0] = values.get(ids.indexOf(HubPages.RIDE)).getText().toString();
            seen[1] = ((TextView) field(view(), "clock")).getText().toString();
            return seen[0].equals(HubText.rideRecording(seen[1]).render());
        }, 5000, "The Ride row reads \"" + seen[0] + "\" while the moment shows " + seen[1]);
        String clock = seen[1];
        // The time from the seeding on: at least the fixture's 14:38, at most that plus the time this run has taken since.
        long shown = clockSeconds(clock), least = ELAPSED_MS / 1000, most = (ELAPSED_MS + System.currentTimeMillis() - seededWall) / 1000 + 1;
        require(shown >= least && shown <= most, "The fixture's time reads " + clock + ", not between " + RidingFocus.clockText(least * 1000) + " and " + RidingFocus.clockText(most * 1000));
        checks.add(size + ": the moment's time " + clock + " is the Ride row's \"" + seen[0] + "\"");
    }

    /** "14:38" or "1:02:48" in seconds. */
    private static long clockSeconds(String clock) {
        long total = 0;
        for (String part : clock.trim().split(":")) total = total * 60 + Long.parseLong(part.trim());
        return total;
    }

    /**
     * The Range cell is the parked card's figure, marked as an estimate: its quantities (number and unit, as whole tokens)
     * are the card's, in order; both units wherever the row shows them, which at 1x text is wherever the card does
     * (RecordingFit.row); the first unit alone only where the row's fit falls back to it, together with Trip.
     */
    private void requireRange(String parkedLine) {
        String[] seen = {null};
        await(() -> {
            String cell = text(stat(2));
            seen[0] = cell;
            List<String> card = quantities(parkedLine), shown = quantities(cell);
            boolean firstOnly = stats().shortShown(2);
            if (!cell.startsWith("≈") || shown.isEmpty()) return false;
            if (firstOnly) return card.size() == 2 && shown.equals(card.subList(0, 1));
            return shown.equals(card);
        }, 10000, "The Range cell reads \"" + seen[0] + "\", not the parked card's \"" + parkedLine + "\" (" + quantities(parkedLine) + ")");
        boolean firstOnly = onMain(() -> stats().shortShown(2));
        if ("dual".equals(units) && textScale() <= 1.01f) require(!firstOnly, "With both units at 1x text, the Range cell dropped one: \"" + seen[0] + "\"");
        if (firstOnly) require(onMain(() -> stats().shortShown(0)), "Range took its first unit alone but Trip did not");
        checks.add(size + ": the Range cell \"" + seen[0] + "\" is the parked card's \"" + parkedLine + "\"" + (firstOnly ? " (first unit, the row's fallback)" : ""));
    }

    /** Each "number unit" in a line, as whole tokens ("23 mi", "37 km"), in order. */
    private static List<String> quantities(String line) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*(mi|km)\\b").matcher(line);
        while (m.find()) out.add(m.group(1) + " " + m.group(2));
        return out;
    }

    private RecordingView.Stats stats() { return (RecordingView.Stats) field(view(), "stats"); }
    private float textScale() { return (Float) onMain(() -> field(view(), "textScale")); }

    /**
     * The ribbon shows exactly where RecordingFit.layout gives it room for this area, width and text size (shown at 390,
     * 412 and 344 dp at 1x; given up on two panes and with large text on the narrow phones); where it shows, it draws
     * this ride (line) or, with no speed reading, its empty state.
     */
    private void requireRibbon(boolean line) {
        main(() -> {
            RecordingView moment = view();
            View panel = (View) field(moment, "ribbon");
            float density = activity.getResources().getDisplayMetrics().density, scale = (Float) field(moment, "textScale");
            RecordingFit.Layout fit = RecordingFit.layout(Math.round(moment.getHeight() / density), moment.getWidth() / density, scale);
            require(panel.isShown() == (fit.ribbon > 0), "The ribbon is " + (panel.isShown() ? "shown" : "hidden") + " but RecordingFit gives it "
                    + fit.ribbon + " dp at " + Math.round(moment.getWidth() / density) + "x" + Math.round(moment.getHeight() / density) + " dp, " + scale + "x");
            if (!panel.isShown()) { checks.add(size + ": the ribbon gives way here, as RecordingFit decides"); return; }
            Object trace = field(panel, "trace");
            int points = trace == null ? 0 : ((RecordingFit.Trace) trace).size();
            if (line) {
                require(points >= 20, "The ribbon draws " + points + " points for a 14 min ride");
                // A connected line: the fixture's ride has no gap, so no break from its start to its end (the service's own
                // samples after it may add one in the last few seconds on a busy emulator, never before).
                RecordingFit.Trace t = (RecordingFit.Trace) trace;
                int breaks = 0, run = 0, longest = 0;
                for (int i = 0; i < points; i++) {
                    if (t.breaks[i]) { run = 0; if (i > 0 && t.x[i] < .98f) breaks++; }
                    run++;
                    longest = Math.max(longest, run);
                }
                require(breaks == 0 && longest >= Math.max(20, points * 9 / 10),
                        "The ribbon's line is broken: " + breaks + " breaks before its last 2%, longest run " + longest + " of " + points + " points");
                checks.add(size + ": the ribbon shows, " + Math.round(panel.getHeight() / density) + " dp, " + points + " points in one connected line (longest run " + longest + ")");
                return;
            }
            require(points == 0, "The ribbon draws a line (" + points + " points) with no speed reading");
            checks.add(size + ": the ribbon shows, " + Math.round(panel.getHeight() / density) + " dp, its empty state");
        });
    }

    private void requireButton() {
        main(() -> {
            View button = button();
            Rect seen = new Rect();
            require(button.getGlobalVisibleRect(seen) && seen.width() == button.getWidth() && seen.height() == button.getHeight(), "The button is not whole on screen: " + seen);
            require(button.getHeight() >= dp(48), "The button is " + button.getHeight() + " px, under 48 dp");
            require(String.valueOf(button.getContentDescription()).equals(HubText.recordingOpenRiding().render()), "The button has no name of its own");
            TextView words = (TextView) field(view(), "buttonLabel");
            require(words.isShown() && words.getLayout() != null && words.getLayout().getEllipsisCount(0) == 0
                    && words.getPaint().measureText(words.getText().toString()) <= words.getWidth() + 1, "The button's words are cut: \"" + words.getText() + "\"");
        });
    }

    /** The top area's height and the Lock bar's size and place under it are as parked (px; place()). */
    private void requireAreaKept(int[] parked, String what) {
        int[] now = place();
        require(now[5] == parked[5] && now[1] - now[4] == parked[1] - parked[4] && now[2] == parked[2] && now[3] == parked[3],
                "The top area or the Lock bar under it changed for " + what + ": " + describe(parked) + " -> " + describe(now));
        checks.add(size + ": " + what + ": the top area " + now[5] + " px high and the Lock bar under it as parked " + describe(now));
    }

    /** A finger's tap at a view's centre, injected into the window. */
    private void tap(View view) {
        int[] at = onMain(() -> { int[] l = new int[2]; view.getLocationOnScreen(l); return new int[]{l[0] + view.getWidth() / 2, l[1] + view.getHeight() / 2}; });
        long down = SystemClock.uptimeMillis();
        android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, at[0], at[1], 0);
        android.view.MotionEvent lift = android.view.MotionEvent.obtain(down, down + 60, android.view.MotionEvent.ACTION_UP, at[0], at[1], 0);
        press.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        lift.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(press);
        sendPointerSync(lift);
        press.recycle();
        lift.recycle();
    }

    // ===== Plumbing =====

    private Object clean() { Object screen = field(activity, "screen"); return screen instanceof CleanHomeScreen ? screen : field(activity, "homeScreen"); }
    private boolean twoPanes() { return !(field(activity, "screen") instanceof CleanHomeScreen); }
    private int moment() { Object clean = clean(); return clean == null ? -1 : (Integer) field(clean, "moment"); }
    private RecordingView view() { return (RecordingView) root().findViewWithTag(RecordingFit.TAG); }
    private View button() { return view().findViewWithTag(RecordingFit.TAG_RIDING); }
    private TextView side() { return (TextView) field(view(), "side"); }
    private TextView stat(int i) { return ((TextView[]) field(field(view(), "stats"), "values"))[i]; }
    private static String text(TextView view) { return view.getText().toString(); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    /** The parked card's range line ("about 23 mi left"), read while it shows. */
    private String parkedRange() {
        return onMain(() -> {
            View overview = root().findViewWithTag("clean-home-overview");
            Object card = field(overview, "overview");
            return ((TextView) field(card, "range")).getText().toString();
        });
    }

    /** The Lock bar's and the top area's place and size, and the scooter name's place, in the window (px). */
    private int[] place() {
        return onMain(() -> {
            Rect lock = windowRect(root().findViewWithTag(CleanHomeScreen.TAG_LOCK)), top = windowRect(root().findViewWithTag(CleanHomeScreen.TAG_TOP));
            View nameView = root().findViewWithTag(CleanHomeScreen.TAG_NAME);
            Rect name = nameView == null ? new Rect() : windowRect(nameView);
            return new int[]{lock.left, lock.top, lock.width(), lock.height(), top.top, top.height(), name.left, name.top};
        });
    }
    private static String describe(int[] place) { return "(lock " + place[0] + "," + place[1] + " " + place[2] + "x" + place[3] + ", area at " + place[4] + " " + place[5] + " high, name at " + place[6] + "," + place[7] + ")"; }
    private static Rect windowRect(Object view) { View v = (View) view; int[] at = new int[2]; v.getLocationInWindow(at); return new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()); }

    private void hold(String key, String text, double value) {
        main(() -> {
            ScooterSimulation simulation = (ScooterSimulation) field(service.ble, "simulation");
            require(simulation != null, "No simulator to hold readings on");
            simulation.hold(key, text, value);
        });
    }

    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, 10000, "App did not launch");
        service = activity.service();
        SystemClock.sleep(1200); settle();
    }

    /** The developer simulator's UT5 MAX, connected and parked. */
    private void simulate() {
        main(() -> require(service.setSimulation(true), "Simulation did not start"));
        await(() -> service.ble.ready(), 10000, "The simulated scooter did not connect");
        main(() -> { service.ble.tickSimulation(); activity.redraw(); });
        settle();
    }

    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "recording-moment-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(600); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), size + "-" + name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); com.jjricks.volt.tests.TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, long ms, String message) { if (!waitFor(condition, ms)) throw new IllegalStateException(message); }
    private boolean waitFor(BooleanSupplier condition, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> ready.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(ready.get())) return true;
            SystemClock.sleep(60);
        }
        return false;
    }
    private void main(ThrowingRunnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private <V> V onMain(Callable<V> action) {
        AtomicReference<V> value = new AtomicReference<>();
        main(() -> value.set(action.call()));
        return value.get();
    }
    private interface ThrowingRunnable { void run() throws Exception; }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private void setField(Object target, String name, Object value) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); java.lang.reflect.Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
