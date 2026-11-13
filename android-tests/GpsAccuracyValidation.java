package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.GpsAccuracy;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.PhoneRideRecorder;
import com.jjricks.volt.PhoneRideStore;
import com.jjricks.volt.RideChartData;
import com.jjricks.volt.RideChartView;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * GPS accuracy, where it shows: riding mode's optional reading and the ride graph's line. Emulator only, synthetic data.
 *
 * -e part riding: the reading as Halo's third chip (beside Trip and Ride time) and as Halo's second tile, at 344 dp and 1.4x
 * text, in every state: Not recording, Off (a recording without location permission), Waiting (GPS asked, no fix yet), a fix
 * ("±13 ft", then 20 m and 80 m fixes, then metres), No fix (ten seconds without one, and a fix already over ten seconds old
 * when it arrives is never shown), and simulation (no phone recording, so Not recording). Fixes come from an Android GPS test
 * provider (appops mock_location), which stands in for the emulator's own GPS, so the test sets every fix's accuracy and can
 * leave ten seconds of silence. Checks that showing the reading starts no recorder and holds no wake lock or keep-screen-on,
 * that names follow the label ladder whole, that spoken text carries the value and unit, and that no coordinate is shown.
 * -e locale de (or pt-PT, pt-BR) runs the same in that app language.
 *
 * -e part graph: saved rides with a phone journal whose fixes vary in accuracy, with a gap and two very poor fixes; a ride
 * with no phone journal, one with a journal and no GPS, one with a single fix and one with two fixes across a gap. Checks the
 * line's chip is off by default, reads "no data" without GPS, that a gap stays a gap, that the poor fixes don't set the scale
 * and the top mark reads "≥", the cursor reads "±984 ft", metres follow the setting, and the other lines are as before.
 * Screenshots at 344 and 725 dp, dark and light, 1.0x and 1.4x. With -e locale only the chips' fit at 344 dp 1.4x is checked.
 */
public final class GpsAccuracyValidation extends Instrumentation {
    private static final String PKG = "com.jjricks.volt";
    /** Synthetic coordinates: if any of these digits show on screen, a position leaked. */
    private static final double LAT = 12.3456789, LON = -98.7654321;
    private static final String[] LEAKS = {"12.345", "12,345", "98.765", "98,765"};
    private Bundle args;
    private MainActivity activity;
    private String profileId, immersivePrevious, locale = "";
    private File evidence;
    private boolean testProvider;
    private final List<String> notes = new ArrayList<>();
    private final List<String> rideIds = new ArrayList<>();
    private int assertions;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String part = args.getString("part", "riding");
        locale = args.getString("locale", "");
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "emulator only");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; use a fresh install");
            require(part.equals("riding") || part.equals("graph"), "Use -e part riding or graph");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "gps-accuracy-" + part + (locale.isEmpty() ? "" : "-" + locale));
            require(evidence.isDirectory() || evidence.mkdirs(), "evidence folder");
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            shell("wm density 480");
            size(344, 882);
            if (!locale.isEmpty()) {
                getTargetContext().getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(locale));
                SystemClock.sleep(500);
            }
            require(prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("phone_gps", true).putBoolean("phone_motion", false)
                    .putString("units", "mi").putString("theme", "dark").putFloat("text_scale", 1.4f).commit(), "settings");
            main(() -> {
                OnboardingScreen.markDone(getTargetContext());
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic GPS scooter", "0F1E2D3C4B5A", 0);
                p.mac = "C0:FF:EE:00:00:09"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
                profileId = p.id;
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch", 10000);
            settle();
            if (part.equals("riding")) riding(); else graph();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", android.util.Log.getStackTraceString(error));
            try { if (evidence != null) shot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions);
            try { java.nio.file.Files.write(new File(evidence, "notes.txt").toPath(), notes); } catch (Exception ignored) { }
            cleanup();
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Riding mode ------------------------------------------------------------------------------------------------

    private static String halo(String spots) { return "{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{" + spots + "}}}}"; }
    // First in a crowded row (Trip and Ride time beside it): at 344 dp and 1.4x the row holds two named chips, and the
    // last one steps aside as any third chip does, so the reading's own fit is what's checked.
    private static final String CHIP = halo("\"chip_1\":\"gps_accuracy\",\"chip_2\":\"trip\",\"chip_3\":\"ride_time\"");
    private static final String TILE = halo("\"tile_1\":\"trip\",\"tile_2\":\"gps_accuracy\"");
    private static final String PLAIN = halo("\"chip_1\":\"trip\",\"chip_2\":\"ride_time\",\"chip_3\":\"clock\"");

    private void riding() throws Exception {
        ScooterService service = activity.service();
        main(() -> activity.openRiding());
        settle();
        await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding did not open", 10000);
        String notRecording = str("riding_gps_not_recording"), off = str("common_off"), waiting = str("riding_gps_waiting"), noFix = str("riding_gps_no_fix");
        require(new LinkedHashSet<>(Arrays.asList(notRecording, off, waiting, noFix)).size() == 4, "states read the same: " + notRecording + "/" + off + "/" + waiting + "/" + noFix);

        // Not recording. Showing the reading asks nothing of GPS: no recorder, no wake lock, and the window keeps the screen
        // on (or not) exactly as it does with the clock in that chip.
        int plainFlags = layout(PLAIN) & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        both("not-recording", notRecording, GpsAccuracy.State.NOT_RECORDING);
        int chipFlags = layout(CHIP) & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        require(plainFlags == chipFlags, "the reading changed keep-screen-on: " + plainFlags + " vs " + chipFlags);
        SystemClock.sleep(2500);
        main(() -> {
            require(field(service, "phoneRecorder") == null && !service.isRecording(), "showing the reading started a recorder");
            require(!((PowerManager.WakeLock) field(service, "wakeLock")).isHeld(), "showing the reading holds a wake lock");
        });
        note("not recording: no recorder, no wake lock, keep-screen-on unchanged (" + chipFlags + ")");

        // Off: a recording without location permission records no GPS.
        require(!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                "start this part without location permission (the sweep grants Nearby only)");
        record();
        both("off", off, GpsAccuracy.State.OFF);
        stopRecording();

        // Waiting, then fixes from the test provider.
        getUiAutomation().grantRuntimePermission(PKG, Manifest.permission.ACCESS_FINE_LOCATION);
        getUiAutomation().grantRuntimePermission(PKG, Manifest.permission.ACCESS_COARSE_LOCATION);
        startTestProvider();
        record();
        PhoneRideRecorder recorder = (PhoneRideRecorder) field(service, "phoneRecorder");
        await(recorder::locationActive, "GPS was not requested for the recording", 5000);
        both("waiting", waiting, GpsAccuracy.State.WAITING);

        fixState("fix-4m", 4, "13", "ft");
        fixState("fix-20m", 20, "66", "ft");
        fixState("fix-80m", 80, "262", "ft");
        prefs("app_settings").edit().putString("units", "km").commit();
        fixState("fix-4m-km", 4, "4", "m");
        prefs("app_settings").edit().putString("units", "dual").commit();
        fixState("fix-4m-dual", 4, "13", "ft");
        prefs("app_settings").edit().putString("units", "mi").commit();
        prefs("app_settings").edit().putFloat("text_scale", 1f).commit();
        fixState("fix-4m-1x", 4, "13", "ft");
        prefs("app_settings").edit().putFloat("text_scale", 1.4f).commit();

        // No fix: ten seconds pass with none. The last value (±13 ft) must not stay on screen as if current.
        long last = push(4);
        await(() -> service.gpsAccuracy(SystemClock.elapsedRealtime()).state == GpsAccuracy.State.FIX, "fix before the silence", 3000);
        while (SystemClock.elapsedRealtime() - last <= GpsAccuracy.STALE_MS + 300) SystemClock.sleep(100);
        both("no-fix", noFix, GpsAccuracy.State.NO_FIX);
        // A fix whose own time is already over ten seconds old is never shown as current.
        pushAged(4, 10500);
        SystemClock.sleep(800);
        both("no-fix-aged", noFix, GpsAccuracy.State.NO_FIX);
        stopRecording();
        main(() -> require(!recorder.locationActive() && !recorder.active(), "GPS stayed on after the recording stopped"));
        both("stopped", notRecording, GpsAccuracy.State.NOT_RECORDING);

        // Simulation: no phone recording there, so the reading says Not recording and starts nothing.
        main(() -> require(service.setSimulation(true), "simulation"));
        await(() -> service.ble.ready(), "simulation ready", 10000);
        both("simulation", notRecording, GpsAccuracy.State.NOT_RECORDING);
        main(() -> require(field(service, "phoneRecorder") == null, "simulation started a phone recorder"));
        main(() -> service.setSimulation(false));
        settle();
    }

    /** A recording below the BLE-gated Start button, as PhoneRideValidation does: the service's phone recorder follows it. */
    private void record() throws Exception {
        ScooterService service = activity.service();
        AtomicReference<String> id = new AtomicReference<>();
        main(() -> {
            try { id.set(service.rides.start(profileId, "Synthetic GPS accuracy", System.currentTimeMillis(), SystemClock.elapsedRealtime())); }
            catch (Exception e) { throw new IllegalStateException(e); }
            invoke(service, "applyWorkPolicy", new Class<?>[]{boolean.class}, false);
        });
        rideIds.add(id.get());
        await(() -> { PhoneRideRecorder r = (PhoneRideRecorder) field(service, "phoneRecorder"); return r != null && r.active() && !r.status().startsWith("Starting"); },
                "phone recorder did not start", 6000);
    }

    private void stopRecording() {
        ScooterService service = activity.service();
        main(service::stopRide);
        await(() -> field(service, "phoneRecorder") == null && !service.isRecording() && !service.phoneSaving(), "recording did not stop", 8000);
    }

    private void fixState(String label, double metres, String number, String unit) throws Exception {
        for (String layout : new String[]{CHIP, TILE}) {
            push(metres);
            String kind = layout.equals(CHIP) ? "chip" : "tile";
            layout(layout);
            push(metres);
            redraw();
            ScooterService service = activity.service();
            GpsAccuracy.Reading r = service.gpsAccuracy(SystemClock.elapsedRealtime());
            require(r.state == GpsAccuracy.State.FIX && Math.abs(r.metres - metres) < 0.01, label + ": reading " + r.state + " " + r.metres);
            String[] seen = kind.equals("chip") ? chip(label + "-chip") : tile(label + "-tile");
            String value = seen[0], spoken = seen[1];
            require(value.startsWith("±") && value.replaceAll("[^0-9]", "").equals(number) && value.contains(unit),
                    label + " " + kind + ": shows '" + value + "', wanted ±" + number + " " + unit);
            require(spoken.contains(value), label + " " + kind + ": spoken '" + spoken + "' lacks '" + value + "'");
            noPosition(label + "-" + kind);
            shot(label + "-" + kind);
        }
    }

    /** The reading as a chip, then as a tile, in a state shown as plain text. */
    private void both(String label, String text, GpsAccuracy.State state) throws Exception {
        for (String layout : new String[]{CHIP, TILE}) {
            String kind = layout.equals(CHIP) ? "chip" : "tile";
            layout(layout);
            ScooterService service = activity.service();
            GpsAccuracy.State now = service.gpsAccuracy(SystemClock.elapsedRealtime()).state;
            require(now == state, label + ": reading is " + now + ", wanted " + state);
            String[] seen = kind.equals("chip") ? chip(label + "-chip") : tile(label + "-tile");
            require(seen[0].equals(text), label + " " + kind + ": shows '" + seen[0] + "', wanted '" + text + "'");
            require(!seen[0].contains("±"), label + " " + kind + ": a number shows in a state without a current fix");
            require(seen[1].contains(text), label + " " + kind + ": spoken '" + seen[1] + "' lacks '" + text + "'");
            noPosition(label + "-" + kind);
            shot(label + "-" + kind);
        }
    }

    /** Sets the riding layout and rebuilds riding mode; returns the window's flags. */
    private int layout(String layout) throws Exception {
        require(prefs("app_settings").edit().putString("riding_layout_v1_" + profileId, layout).commit(), "layout");
        main(() -> activity.rebuild());
        settle();
        await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding gone", 10000);
        redraw();
        AtomicReference<Integer> flags = new AtomicReference<>();
        main(() -> flags.set(activity.getWindow().getAttributes().flags));
        return flags.get();
    }

    private void redraw() {
        for (int i = 0; i < 2; i++) { main(() -> activity.redraw()); SystemClock.sleep(250); }
        settle();
    }

    /** The GPS accuracy chip's shown value and spoken text; checks every chip is whole and named by a whole rung. */
    private String[] chip(String label) {
        String[] out = new String[2];
        main(() -> {
            List<?> chips = (List<?>) field(field(activity, "screen"), "chips");
            Object mine = null;
            for (Object c : chips) {
                View v = (View) c;
                if (v.getVisibility() != View.VISIBLE) continue;
                String content = (String) field(c, "content");
                TextView value = (TextView) field(c, "text"), name = (TextView) field(c, "name");
                View icon = (View) field(c, "icon");
                float needed = value.getPaint().measureText(value.getText().toString());
                require(value.getWidth() >= Math.floor(needed) && value.getLineCount() <= 1, label + ": " + content + " value cut: '" + value.getText() + "'");
                boolean nameShown = name.getVisibility() == View.VISIBLE, iconShown = icon != null && icon.getVisibility() == View.VISIBLE;
                if (nameShown) {
                    require(name.getWidth() >= Math.floor(name.getPaint().measureText(name.getText().toString())) && name.getLineCount() <= 1,
                            label + ": " + content + " name cut: '" + name.getText() + "'");
                    String[] names = (String[]) field(c, "names");
                    require(Arrays.asList(names).contains(name.getText().toString()), label + ": " + content + " name '" + name.getText() + "' is not a rung of " + Arrays.toString(names));
                }
                if (content.equals("gps_accuracy")) {
                    mine = c;
                    require(nameShown || iconShown, label + ": the GPS accuracy chip shows a bare value");
                    String[] names = (String[]) field(c, "names");
                    require(names[0].equals(str("riding_label_gps_accuracy")) && names[names.length - 1].equals(str("riding_label_gps_accuracy_short")),
                            label + ": ladder " + Arrays.toString(names));
                    CharSequence spoken = v.createAccessibilityNodeInfo().getContentDescription();
                    out[0] = value.getText().toString();
                    out[1] = String.valueOf(spoken);
                    require(out[1].contains(names[0]), label + ": spoken '" + out[1] + "' lacks the full name");
                    StringBuilder row = new StringBuilder();
                    for (Object o : chips) row.append(' ').append(field(o, "content")).append(((View) o).getVisibility() == View.VISIBLE ? "" : ":gone");
                    note(label + ": chip " + (nameShown ? "'" + name.getText() + "'" : "") + (iconShown ? " icon" : "") + " '" + out[0] + "' "
                            + v.getWidth() + "px spoken '" + out[1] + "' row:" + row);
                }
            }
            if (mine == null) {
                StringBuilder all = new StringBuilder();
                for (Object c : chips) all.append(' ').append(field(c, "content")).append(((View) c).getVisibility() == View.VISIBLE ? "" : ":gone")
                        .append(" '").append(((TextView) field(c, "text")).getText()).append("' w=").append(((View) c).getWidth());
                require(false, label + ": the GPS accuracy chip is not shown:" + all);
            }
            List<String> fit = new ArrayList<>();
            texts(activity.getWindow().getDecorView(), fit, label);
            require(fit.isEmpty(), label + ": " + fit);
        });
        assertions++;
        return out;
    }

    /** The GPS accuracy tile's drawn value and spoken text, and its label's rung. */
    private String[] tile(String label) {
        String[] out = new String[2];
        main(() -> {
            Object mine = null;
            for (Object cell : (List<?>) field(field(activity, "screen"), "cells")) if ("gps_accuracy".equals(field(cell, "content"))) mine = cell;
            require(mine != null && ((View) mine).isShown(), label + ": the GPS accuracy tile is not shown");
            Object number = field(mine, "number");
            out[0] = String.valueOf(field(number, "prefix")) + field(number, "main") + field(number, "fraction") + field(number, "suffix");
            out[1] = String.valueOf(((View) mine).getContentDescription());
            TextView name = (TextView) field(mine, "label");
            String full = str("riding_label_gps_accuracy"), brief = str("riding_label_gps_accuracy_short");
            String shown = name.getText().toString();
            require(shown.equals(full) || shown.equals(brief) || (shown.isEmpty() && name.getCompoundDrawables()[0] != null),
                    label + ": tile name '" + shown + "' is not a whole rung");
            require(out[1].contains(full), label + ": spoken '" + out[1] + "' lacks the full name");
            List<String> fit = new ArrayList<>();
            texts(activity.getWindow().getDecorView(), fit, label);
            require(fit.isEmpty(), label + ": " + fit);
            note(label + ": tile '" + shown + "' '" + out[0] + "' spoken '" + out[1] + "'");
        });
        assertions++;
        return out;
    }

    /** No synthetic coordinate appears in any text or spoken description on screen. */
    private void noPosition(String label) {
        main(() -> {
            List<String> all = new ArrayList<>();
            words(activity.getWindow().getDecorView(), all);
            for (String s : all) for (String leak : LEAKS) require(!s.contains(leak), label + ": a coordinate shows: " + s);
        });
        assertions++;
    }

    private void startTestProvider() throws Exception {
        shell("appops set " + PKG + " android:mock_location allow");
        LocationManager lm = getTargetContext().getSystemService(LocationManager.class);
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (IllegalArgumentException ignored) { }
        lm.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE);
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        testProvider = true;
        note("fixes come from a GPS test provider (appops mock_location), accuracy set per fix");
    }

    /** One fix with this accuracy, timed now; returns its time. */
    private long push(double metres) { return pushAged(metres, 0); }

    /**
     * Sends a fix whose own time is ageMs ago. A fresh one is re-sent until the recorder has it: Android may drop a test
     * location that comes sooner than the recorder's one-second request interval after the last.
     */
    private long pushAged(double metres, long ageMs) {
        LocationManager lm = getTargetContext().getSystemService(LocationManager.class);
        ScooterService service = activity.service();
        for (int attempt = 0; attempt < 6; attempt++) {
            long now = SystemClock.elapsedRealtime(), at = now - ageMs;
            Location l = new Location(LocationManager.GPS_PROVIDER);
            l.setLatitude(LAT); l.setLongitude(LON); l.setAccuracy((float) metres);
            l.setSpeed(4f); l.setAltitude(30);
            l.setTime(System.currentTimeMillis() - ageMs); l.setElapsedRealtimeNanos(at * 1000000L);
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, l);
            if (ageMs > 0) return at;
            long until = SystemClock.elapsedRealtime() + 1500;
            while (SystemClock.elapsedRealtime() < until) {
                GpsAccuracy.Fix fix = ((PhoneRideRecorder) field(service, "phoneRecorder")).latestFix();
                if (fix != null && fix.monoMs == at && Math.abs(fix.metres - metres) < 0.01) return at;
                SystemClock.sleep(50);
            }
        }
        throw new IllegalStateException("the recorder never saw the " + metres + " m fix");
    }

    // ---- Ride graph -------------------------------------------------------------------------------------------------

    private RideStore store;
    private String full, none, sensorsOnly, single, pair;
    private static final long WALL = 1_791_835_200_000L;

    private void graph() throws Exception {
        ScooterService service = activity.service();
        store = new RideStore(new File(getTargetContext().getFilesDir(), "rides"));
        full = seed("GPS accuracy fixture", WALL, 150, Journal.FULL);
        none = seed("No phone journal", WALL - 1_000_000, 60, Journal.NONE);
        sensorsOnly = seed("Journal without GPS", WALL - 2_000_000, 60, Journal.SENSORS);
        single = seed("One fix", WALL - 3_000_000, 60, Journal.SINGLE);
        pair = seed("Two fixes across a gap", WALL - 4_000_000, 60, Journal.PAIR);
        main(() -> {
            setField(service, "rides", store);
            service.settings.edit().remove("ride_chart_series").commit();
        });
        if (!locale.isEmpty()) {
            // Another language: the chips' fit only, at 344 dp and 1.4x, with the line chosen and not.
            open(full, 344, 882, "dark", 1.4f);
            toggles("fit-344-1.4x-default");
            choose(true);
            open(full, 344, 882, "dark", 1.4f);
            toggles("fit-344-1.4x");
            shotChart("chart-344-dark-1.4x");
            return;
        }
        // Default: the line is offered but off, as the other optional lines are; the default lines are unchanged.
        open(full, 344, 882, "dark", 1f);
        List<RideChartData.Series> data = data();
        require(data.size() == 9 && data.get(8).key.equals("gps_accuracy") && data.get(8).index == 8, "the line is not the ninth: " + keys(data));
        require(!RideChartData.defaults().contains("gps_accuracy"), "the line is on by default");
        CheckBox chip = toggle(8);
        AtomicReference<Boolean> checked = new AtomicReference<>();
        main(() -> checked.set(chip.isChecked()));
        require(!checked.get(), "the line's chip starts checked");
        toggles("default-344");
        shotChart("default-344-dark-1x");
        note("defaults " + RideChartData.defaults() + "; lines " + keys(data));

        // Values: feet, a gap stays a gap, the poor fixes clip at a "≥" top mark.
        choose(true);
        open(full, 344, 882, "dark", 1f);
        RideChartData.Series line = data().get(8);
        require(line.available() && line.unit.equals("ft"), "line unit " + line.unit);
        int before = -1, after = -1; double high = 0;
        for (RideChartData.Value v : line.values) {
            if (v.value == null) continue;
            high = Math.max(high, v.value);
            if (v.time <= 99_000) before = Math.max(before, v.segment);
            if (v.time >= 110_000) after = after < 0 ? v.segment : Math.min(after, v.segment);
        }
        require(before >= 0 && after > before, "the gap was bridged: segments " + before + " then " + after);
        RideChartData.Value inGap = line.sampleAt(104_000);
        require(inGap == null || inGap.value == null, "a value shows inside the gap: " + (inGap == null ? null : inGap.value));
        require(Math.abs(high - 300 * 3.280839895) < 0.5, "the 300 m fix is missing from the line: " + high);
        require(line.scale.min == 0 && line.scale.max >= 18 * 3.280839895 && line.scale.max <= 200, "scale " + line.scale.min + ".." + line.scale.max);
        require(line.clipsAbove(), "the poor fixes should draw at the top edge");
        String top = axis(line, line.scale.max), mid = axis(line, line.scale.max / 2);
        require(top.startsWith("≥") && !mid.contains("≥") && mid.matches("[0-9]+"), "axis marks '" + top + "' '" + mid + "'");
        note("feet scale 0.." + line.scale.max + " top mark '" + top + "' mid '" + mid + "' highest " + high);
        String outlier = inspect(70_000);
        require(outlier.contains("±984 ft"), "the cursor on the 300 m fix reads: " + outlier);
        String gapCell = inspect(104_000);
        require(!gapCell.contains("±"), "the cursor in the gap reads a value: " + gapCell);
        shotChart("inspect-outlier-344");
        // The other lines: same keys, order, colours and units as RideChartData.build gives.
        AtomicReference<List<RideChartData.Series>> built = new AtomicReference<>();
        main(() -> {
            View p = panel();
            @SuppressWarnings("unchecked") List<RideStore.Point> measured = (List<RideStore.Point>) field(p, "measured");
            built.set(RideChartData.build(measured, (PhoneRideStore.Track) field(p, "phone"), true, false, (Boolean) field(p, "inferRegen")));
        });
        List<RideChartData.Series> plain = built.get();
        require(plain.size() == 8, "build gives " + plain.size() + " lines");
        for (int i = 0; i < 8; i++) {
            RideChartData.Series a = plain.get(i), b = data().get(i);
            require(a.key.equals(b.key) && a.color == b.color && a.unit.equals(b.unit) && a.index == b.index
                    && a.scale.min == b.scale.min && a.scale.max == b.scale.max && a.values.size() == b.values.size(), "line " + a.key + " changed");
        }

        // Screenshots: 344 and 725 dp, dark and light, 1.0x and 1.4x.
        for (int[] size : new int[][]{{344, 882}, {725, 1000}})
            for (String theme : new String[]{"dark", "light"})
                for (float scale : new float[]{1f, 1.4f}) {
                    String name = "chart-" + size[0] + "-" + theme + "-" + (scale == 1f ? "1x" : "1.4x");
                    open(full, size[0], size[1], theme, scale);
                    toggles(name);
                    inspect(30_000);
                    shotChart(name);
                }

        // Metres.
        prefs("app_settings").edit().putString("units", "km").commit();
        open(full, 344, 882, "dark", 1f);
        line = data().get(8);
        require(line.unit.equals("m") && line.scale.max >= 18 && line.scale.max <= 60, "metres scale " + line.scale.max + " " + line.unit);
        String metres = inspect(70_000);
        require(metres.contains("±300 m"), "the cursor in metres reads: " + metres);
        shotChart("chart-344-dark-1x-km");
        prefs("app_settings").edit().putString("units", "mi").commit();

        // Rides with little or no GPS open without trouble.
        open(none, 344, 882, "dark", 1f);
        require(!data().get(8).available(), "a ride without a phone journal has GPS accuracy data");
        toggles("no-journal");
        shotChart("no-journal");
        open(sensorsOnly, 344, 882, "dark", 1f);
        require(!data().get(8).available(), "a journal without GPS has GPS accuracy data");
        shotChart("no-gps");
        open(single, 344, 882, "dark", 1f);
        line = data().get(8);
        require(line.available() && line.values.size() == 1, "one fix gives " + line.values.size() + " values");
        require(line.scale.max >= 30, "one fix's scale " + line.scale.max);
        shotChart("one-fix");
        open(pair, 344, 882, "dark", 1f);
        line = data().get(8);
        require(line.values.size() == 2 && line.values.get(0).segment != line.values.get(1).segment, "two fixes across a gap joined");
        shotChart("two-fixes-gap");
        // A choice saved before this change (no gps_accuracy in it) keeps its lines.
        main(() -> service.settings.edit().putStringSet("ride_chart_series", new LinkedHashSet<>(Arrays.asList("power_w", "speed_kmh"))).commit());
        open(full, 344, 882, "dark", 1f);
        AtomicReference<Boolean> kept = new AtomicReference<>();
        CheckBox gpsChip = toggle(8), powerChip = toggle(0);
        main(() -> kept.set(!gpsChip.isChecked() && powerChip.isChecked()));
        require(kept.get(), "a remembered choice changed");
        note("rides with no journal, no GPS, one fix and two fixes opened");
    }

    private enum Journal { NONE, SENSORS, SINGLE, PAIR, FULL }

    /** A saved ride: scooter samples each second, and a phone journal on the same clocks. */
    private String seed(String label, long wall, int seconds, Journal journal) throws Exception {
        String id = store.start(profileId, label, wall, 1000);
        rideIds.add(id);
        for (int t = 0; t < seconds; t++) {
            long mono = 1000 + t * 1000L;
            java.util.Map<String, RideStore.Sample> row = new java.util.LinkedHashMap<>();
            double kmh = 10 + 8 * Math.sin(t / 9.0);
            row.put("voltage_v", new RideStore.Sample(50 - kmh * 0.05, "", "V", 0x72, mono));
            row.put("current_a", new RideStore.Sample(4 + kmh * 0.4, "", "A", 0x72, mono));
            row.put("speed_kmh", new RideStore.Sample(kmh, mono));
            row.put("battery_percent", new RideStore.Sample(80d - t / 30.0, mono));
            store.sample(wall + t * 1000L, mono, row);
        }
        store.stop(wall + seconds * 1000L, 1000 + seconds * 1000L);
        if (journal == Journal.NONE) return id;
        PhoneRideStore phones = activity.service().phoneRides;
        phones.start(id, wall, 1000);
        StringBuilder batch = new StringBuilder();
        for (int t = 1; t < seconds - 1; t++) {
            long w = wall + t * 1000L, m = 1000 + t * 1000L;
            batch.append(PhoneRideStore.row("PRESSURE", w, m, 1013.0 - t * 0.01));
            boolean fix;
            double accuracy = 3 + 2 * Math.sin(t / 7.0);
            switch (journal) {
                case SINGLE: fix = t == 20; break;
                case PAIR: fix = t == 20 || t == 40; if (t == 30) batch.append(PhoneRideStore.row("GAP", w, m)); break;
                case FULL:
                    fix = t < 100 || t >= 110;
                    if (t == 100) batch.append(PhoneRideStore.row("GAP", w, m));
                    if (t >= 40 && t <= 55) accuracy = 6 + (t - 40);
                    if (t == 70) accuracy = 300;
                    if (t == 71) accuracy = 120;
                    break;
                default: fix = false;
            }
            if (fix) batch.append(PhoneRideStore.row("GPS", w, m, LAT + t * 1e-5, LON, accuracy, 4.0, 0.3, 30.0, 3.0));
        }
        phones.appendBatch(id, batch.toString(), "");
        phones.stop(id, wall + seconds * 1000L, 1000 + seconds * 1000L);
        return id;
    }

    private void choose(boolean on) {
        main(() -> {
            LinkedHashSet<String> set = new LinkedHashSet<>(activity.service().settings.getStringSet("ride_chart_series", RideChartData.defaults()));
            if (on) set.add("gps_accuracy"); else set.remove("gps_accuracy");
            activity.service().settings.edit().putStringSet("ride_chart_series", set).commit();
        });
    }

    /** Opens a ride's details at a window size, theme and text size, and waits for its chart. */
    private void open(String id, int w, int h, String theme, float scale) throws Exception {
        size(w, h);
        require(prefs("app_settings").edit().putString("theme", theme).putFloat("text_scale", scale).commit(), "prefs");
        SystemClock.sleep(800);
        await(() -> activity.getWindow().getDecorView().getWidth() == w * 3, "window width", 10000);
        main(() -> activity.rebuild());
        settle();
        main(() -> call(activity, "select", new Class<?>[]{String.class}, "rides"));
        settle();
        SystemClock.sleep(2500); // the Rides list loads off the main thread
        main(() -> call(field(activity, "screen"), "openRide", new Class<?>[]{String.class}, id));
        settle();
        // The chart draws the scooter's lines first; the phone journal (and so GPS accuracy) arrives after it loads.
        boolean journal = !id.equals(none);
        // await's condition already runs on the main thread.
        await(() -> { View p = panel(); return p != null && field(p, "data") != null && (!journal || field(p, "phone") != null); },
                "chart never loaded", 15000);
        if (!journal) SystemClock.sleep(1500);
        settle();
        main(() -> {
            View p = panel();
            p.requestRectangleOnScreen(new Rect(0, 0, p.getWidth(), p.getHeight()), true);
        });
        settle();
    }

    private View root() {
        Object screen = field(activity, "screen");
        List<?> dialogs = (List<?>) field(screen, "dialogs");
        return dialogs.isEmpty() ? activity.getWindow().getDecorView() : ((android.app.AlertDialog) dialogs.get(dialogs.size() - 1)).getWindow().getDecorView();
    }

    private View panel() {
        View chart = find(root(), RideChartView.class);
        View v = chart;
        while (v != null && !v.getClass().getName().contains("ChartPanel")) v = v.getParent() instanceof View ? (View) v.getParent() : null;
        return v;
    }

    @SuppressWarnings("unchecked")
    private List<RideChartData.Series> data() {
        AtomicReference<List<RideChartData.Series>> out = new AtomicReference<>();
        main(() -> out.set((List<RideChartData.Series>) field(panel(), "data")));
        return out.get();
    }

    private CheckBox toggle(int index) {
        AtomicReference<CheckBox> out = new AtomicReference<>();
        main(() -> out.set((CheckBox) ((ViewGroup) field(panel(), "choices")).getChildAt(index)));
        return out.get();
    }

    /** Every line's chip fits whole: no clipped line and no word broken across lines. */
    private void toggles(String label) {
        main(() -> {
            ViewGroup choices = (ViewGroup) field(panel(), "choices");
            List<String> problems = new ArrayList<>();
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < choices.getChildCount(); i++) {
                TextView t = (TextView) choices.getChildAt(i);
                TextFit.collectNarrowTextProblems(t, label + " chip " + i, problems);
                require(t.getWidth() <= choices.getWidth() + 1, label + ": chip " + t.getText() + " is wider than its row");
                seen.append(" [").append(t.getText()).append(" lines=").append(t.getLineCount()).append(']');
            }
            CheckBox gps = (CheckBox) choices.getChildAt(8);
            require(gps.getText().toString().contains(str("readings_chart_gps_accuracy")), label + ": chip 8 reads " + gps.getText());
            require(problems.isEmpty(), label + ": " + problems);
            note(label + ":" + seen);
        });
        assertions++;
    }

    /** Puts the chart's cursor at a time and returns the inspection cells' text. */
    private String inspect(double time) {
        AtomicReference<String> out = new AtomicReference<>();
        main(() -> {
            View chart = find(root(), RideChartView.class);
            setField(chart, "inspectedTime", time);
            call(chart, "notifyInspection", new Class<?>[0]);
        });
        settle();
        main(() -> {
            List<String> all = new ArrayList<>();
            words((View) field(panel(), "inspection"), all);
            out.set(String.join(" | ", all));
        });
        note("cursor at " + time + ": " + out.get());
        return out.get();
    }

    private String axis(RideChartData.Series series, double value) {
        try {
            Field f = RideChartView.class.getDeclaredField("DEFAULT_FORMATTER"); f.setAccessible(true);
            return ((RideChartView.ValueFormatter) f.get(null)).axis(series, value);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private void shotChart(String name) throws Exception {
        main(() -> {
            View p = panel();
            if (p != null) p.requestRectangleOnScreen(new Rect(0, 0, p.getWidth(), Math.min(p.getHeight(), activity.getWindow().getDecorView().getHeight())), true);
        });
        settle();
        noPosition(name);
        shot(name);
    }

    private static String keys(List<RideChartData.Series> data) {
        StringBuilder out = new StringBuilder();
        for (RideChartData.Series s : data) out.append(s.key).append(' ');
        return out.toString().trim();
    }

    // ---- Shared -----------------------------------------------------------------------------------------------------

    private void cleanup() {
        try {
            if (activity != null) {
                ScooterService service = activity.service();
                main(() -> {
                    if (service != null && service.isRecording()) service.stopRide();
                    if (service != null && service.simulating()) service.setSimulation(false);
                });
                if (service != null) await(() -> field(service, "phoneRecorder") == null, "recorder did not stop", 8000);
                if (service != null) for (String id : rideIds) {
                    try { service.rides.delete(id); } catch (Throwable ignored) { }
                    try { if (store != null) store.delete(id); } catch (Throwable ignored) { }
                    try { if (service.phoneRides != null) service.phoneRides.delete(id); } catch (Throwable ignored) { }
                }
                main(() -> { try { invoke(activity, "closeRiding", new Class<?>[0]); } catch (Throwable ignored) { } activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
                // Clearing app_settings below tells a live MainActivity's listener of a null key, which it doesn't expect.
                await(activity::isDestroyed, "the Activity did not finish", 8000);
            }
        } catch (Throwable ignored) { }
        try {
            if (testProvider) getTargetContext().getSystemService(LocationManager.class).removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (Throwable ignored) { }
        try {
            shell("appops set " + PKG + " android:mock_location default");
            if (!locale.isEmpty()) getTargetContext().getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.getEmptyLocaleList());
            shell("wm size reset"); shell("wm density reset");
            shell(immersivePrevious == null || immersivePrevious.isEmpty() || immersivePrevious.equals("null")
                    ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious);
            for (String name : new String[]{"app_settings", "profiles", "scooter"}) prefs(name).edit().clear().commit();
        } catch (Throwable ignored) { }
    }

    private void size(int w, int h) throws Exception { shell("wm size " + (w * 3) + "x" + (h * 3)); }

    /** An app string by resource name, in the app's language. */
    private String str(String name) {
        int id = activity.getResources().getIdentifier(name, "string", PKG);
        require(id != 0, "no string " + name);
        return activity.getString(id).replace("\\'", "'");
    }

    private boolean granted(String permission) { return getTargetContext().checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    /** Fit problems of every shown TextView under a view. */
    private static void texts(View view, List<String> problems, String label) {
        if (view instanceof TextView && view.isShown()) TextFit.collectNarrowTextProblems((TextView) view, label + " '" + ((TextView) view).getText() + "'", problems);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) texts(((ViewGroup) view).getChildAt(i), problems, label);
    }

    /** Every shown text and content description under a view. */
    private static void words(View view, List<String> out) {
        if (!view.isShown()) return;
        if (view instanceof TextView && ((TextView) view).length() > 0) out.add(((TextView) view).getText().toString());
        if (view.getContentDescription() != null) out.add(view.getContentDescription().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) words(((ViewGroup) view).getChildAt(i), out);
    }

    private static View find(View view, Class<?> type) {
        if (view == null) return null;
        if (type.isInstance(view) && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View hit = find(((ViewGroup) view).getChildAt(i), type); if (hit != null) return hit; }
        return null;
    }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("screenshot");
        File image = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
        TextCensus.write(this, image);
    }

    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = r.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }

    private void note(String s) { notes.add(s); android.util.Log.i("VoltGpsAccuracy", s); }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            runOnMainSync(() -> { try { done.set(c.getAsBoolean()); } catch (Throwable ignored) { } });
            if (done.get()) return;
            SystemClock.sleep(50);
        }
        throw new IllegalStateException(m);
    }
    private static Object field(Object target, String name) {
        try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static void setField(Object target, String name, Object value) {
        try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> t = target.getClass(); while (t != null) { try { Method m = t.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (NoSuchMethodException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... values) { call(target, name, types, values); }
}
