package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Riding mode's ride time and distance start from zero at each open (owner, Nov 8), against the sample
 * scooter, whose trip is already past 6 km and 23 minutes. Back and reopen starts over; rotation and a
 * recreated activity keep counting. Disposable emulator only: it refuses a device or an app with data.
 */
public final class RidingFromZeroValidation extends Instrumentation {
    private MainActivity activity;
    private final Bundle result = new Bundle();
    private final ArrayList<String> notes = new ArrayList<>();
    private File evidence;
    private int assertions;

    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-from-zero");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).commit();
                new ScooterProfiles(getTargetContext()).create("Synthetic riding fixture", "000000000000", 0);
                OnboardingScreen.markDone(getTargetContext());
            });
            run();
            result.putString("result", "PASS");
        } catch (Throwable t) {
            result.putString("result", "FAIL");
            result.putString("failure", String.valueOf(t));
        } finally {
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
        main(() -> require(activity.service().setSimulation(true), "Simulation on failed"));
        await(() -> number("trip_km") != null && number("trip_minutes") != null, "No simulated trip readings");
        settle();
        double tripKm = number("trip_km"), tripMinutes = number("trip_minutes");
        require(tripKm >= 4 && tripMinutes >= 15, "The sample trip should already be well under way: " + tripKm + " km, " + tripMinutes + " min");
        note("scooter trip before opening: " + tripKm + " km, " + tripMinutes + " min");

        // Open: both zero, then counting up.
        openRiding();
        requireStart("open");
        shot("1-opened");
        SystemClock.sleep(20_000);
        long time = rideMs();
        double km = rideKm();
        require(time >= 19_000 && time < 30_000, "Ride time should count up with the clock: " + time);
        require(km > 0 && km < 1, "Distance should count up from zero: " + km);
        requireScreen(time, km, "counting");
        shot("2-counting");
        note("after 20 s: " + time + " ms, " + km + " km");

        // Rotation (a configuration change the activity handles itself).
        Object session = session();
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        require(session() == session, "Rotation should keep the session");
        long rotated = rideMs();
        require(rotated >= time && rideKm() >= km, "Rotation must not reset: " + rotated + " ms, " + rideKm() + " km");
        requireScreen(rotated, rideKm(), "rotated");
        shot("3-rotated");
        note("rotated: " + rotated + " ms, " + rideKm() + " km");
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // A real recreation: a new session built from saved state.
        long beforeRecreate = rideMs();
        double kmBeforeRecreate = rideKm();
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        main(() -> activity.recreate());
        Activity next = waitForMonitorWithTimeout(monitor, 8000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "Activity did not recreate");
        activity = (MainActivity) next;
        await(() -> activity.service() != null && field(activity, "riding") != null, "Riding mode did not come back");
        require(session() != session, "A recreation should build a new session");
        settle();
        long recreated = rideMs();
        require(recreated >= beforeRecreate && rideKm() >= kmBeforeRecreate,
                "Recreation must not reset: " + beforeRecreate + " -> " + recreated + " ms, " + kmBeforeRecreate + " -> " + rideKm() + " km");
        requireScreen(recreated, rideKm(), "recreated");
        shot("4-recreated");
        note("recreated: " + recreated + " ms, " + rideKm() + " km");

        // Back, then open again: both start over.
        main(() -> activity.onBackPressed());
        await(() -> field(activity, "riding") == null, "Back did not close riding mode");
        settle();
        SystemClock.sleep(3000);
        openRiding();
        requireStart("reopen");
        shot("5-reopened");
        SystemClock.sleep(6000);
        long reopened = rideMs();
        require(reopened >= 5000 && reopened < 12_000, "Reopened ride time should count up from zero: " + reopened);
        require(rideKm() < 0.5, "Reopened distance should start over: " + rideKm());
        shot("6-reopened-counting");
        note("reopened and after 6 s: " + reopened + " ms, " + rideKm() + " km");
    }

    private void openRiding() {
        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null, "Riding mode did not open");
    }

    /** Just opened: within a moment both read zero (the sample scooter is already connected, so a baseline is at hand). */
    private void requireStart(String label) {
        await(() -> kmOrNull() != null, label + ": distance never appeared");
        long time = rideMs();
        require(time < 4000, label + ": ride time should start at zero: " + time);
        require(rideKm() < 0.05, label + ": distance should start at zero: " + rideKm());
        requireScreen(time, rideKm(), label);
        note(label + ": " + time + " ms, " + rideKm() + " km");
    }

    private void rotate(int orientation) throws Exception {
        main(() -> activity.setRequestedOrientation(orientation));
        SystemClock.sleep(2500);
        settle();
    }

    /** The screen shows the session's numbers: a clock near the ride time and the distance to one decimal. */
    private void requireScreen(long ms, double km, String label) {
        SystemClock.sleep(1200); // Let the once-a-second refresh draw them.
        List<String> texts = new ArrayList<>();
        main(() -> collect(activity.getWindow().getDecorView(), texts));
        long seconds = ms / 1000;
        boolean clock = false;
        for (String text : texts) {
            String[] parts = text.split(":");
            if (parts.length == 2 && parts[0].matches("[0-9]+") && parts[1].matches("[0-9]{2}")) {
                long shown = Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
                clock |= shown >= seconds && shown <= seconds + 6;
            }
        }
        require(clock, label + ": no ride time on screen near " + seconds + " s, in " + texts);
        note(label + " on screen: " + texts);
    }

    private void collect(View view, List<String> out) {
        if (view instanceof TextView && view.isShown() && ((TextView) view).getText().length() > 0) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }

    private Object session() {
        AtomicReference<Object> session = new AtomicReference<>();
        main(() -> session.set(field(activity, "riding")));
        return session.get();
    }
    private long rideMs() {
        AtomicReference<Object> ms = new AtomicReference<>();
        main(() -> ms.set(call(field(activity, "riding"), "rideTimeMs", new Class<?>[]{long.class}, SystemClock.elapsedRealtime())));
        return (Long) ms.get();
    }
    private Double kmOrNull() {
        Object trip = call(field(activity, "riding"), "trip", new Class<?>[]{});
        return (Double) field(trip, "km");
    }
    private double rideKm() {
        AtomicReference<Double> km = new AtomicReference<>();
        main(() -> km.set(kmOrNull()));
        require(km.get() != null, "No distance yet");
        return km.get();
    }
    private Double number(String key) {
        Object telemetry = field(activity.service().ble, "telemetry");
        return (Double) call(telemetry, "number", new Class<?>[]{String.class}, key);
    }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot failed");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    private void note(String text) { notes.add(text); }
    private void require(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long until = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < until) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(message);
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(550); }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
