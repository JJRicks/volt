package com.jjricks.volt.tests;

import android.Manifest;
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
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio off. "Estimating time left" first (owner, Nov 23 2026; ChargeEstimateHold): while a
 * UT5 MAX charge's time is held at plug-in, every place that shows or uses it says so instead of a guess, and the time
 * comes back when the hold ends. The developer simulator charges as the UT5 MAX (it never holds by itself); the hold is
 * set through ScooterService's test-only testEstimateHold and the readings are the fixture's: a 1.28 A soft start while
 * held, then 9.6 A.
 *
 * <p>For Widgets and Clean, in the themes asked for (argument themes, default dark,light), at the emulator's current
 * size: the charging view's time tile shows the words "Estimating time left" in the muted colour and no time while held,
 * a time in the normal colour after; the live charging card's text (also its lock-screen version) says "Estimating time
 * left" while held and a finish time after; Charge done by's plan has no minutes to plan with while held (so it can't
 * plan from the guess) and the estimate's after. Each state is captured: the whole screen and the tile. Sends no scooter
 * command.
 */
public final class ChargeEstimatingFirstValidation extends ValidationInstrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 Max", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            String size = args.getString("size_label", "size");
            for (String style : args.getString("styles", "widgets,clean").split(","))
                for (String theme : args.getString("themes", "dark,light").split(",")) {
                    label = size + "-" + style + "-" + theme;
                    launch(style, theme);
                    verify(true);
                    verify(false);
                }
            MainActivity old = activity; main(() -> { service.setSimulation(false); old.finish(); }); SystemClock.sleep(800);
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { capture(new File(dir(), "failure-" + label + ".png"), null); } catch (Throwable ignored) {}
        } finally {
            try { setHold(null); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** One state: held (the soft start's readings) or shown (bulk readings). */
    private void verify(boolean held) throws Exception {
        String name = held ? "estimating" : "time";
        setHold(held ? Boolean.TRUE : null);
        refresh(readings(62, held ? 1.28 : 9.6));
        SystemClock.sleep(700); sync();
        String estimating = T.chargeLiveTextEstimating();
        AtomicReference<String> facts = new AtomicReference<>();
        AtomicReference<int[]> tileRect = new AtomicReference<>();
        main(() -> {
            View h = hero();
            require(h != null && h.isShown(), label + " " + name + ": the charging view shows");
            Object tile = field(h, "timeTile");
            TextView value = ((TextView[]) field(tile, "values"))[(Integer) invoke(tile, "chosen", new Class<?>[0])];  // the reading shown at rest
            String text = String.valueOf(invoke(value, "fullText", new Class<?>[0]));
            Ui ui = activity.ui();
            if (held) {
                require(text.equals(estimating), label + " held: the tile says '" + estimating + "', not '" + text + "'");
                require(value.getCurrentTextColor() == ui.muted, label + " held: the words are muted");
                // TalkBack says the words once: the held state has no status to repeat them (review of #619).
                CharSequence spoken = value.getContentDescription();
                int said = spoken == null ? 0 : spoken.toString().split(java.util.regex.Pattern.quote(estimating), -1).length - 1;
                require(said == 1, label + " held: TalkBack reads '" + estimating + "' once, not in '" + spoken + "'");
            } else {
                require(!text.equals(estimating) && text.matches(".*\\d.*"), label + " shown: the tile shows a time, not '" + text + "'");
                require(value.getCurrentTextColor() == ui.text, label + " shown: the time in the normal colour");
            }
            Rect r = new Rect(); ((View) tile).getGlobalVisibleRect(r);
            tileRect.set(new int[] {r.left, r.top, r.right, r.bottom});
            // The live charging card and its lock-screen version: the service's own estimate of its own readings, built into
            // the card as ScooterService.chargeLiveState() does for a connected scooter (the simulator's link counts as
            // out of reach for the card's connection facts, so the card is built here from the same two calls).
            @SuppressWarnings("unchecked")
            Map<String, TelemetryHistory.Reading> own = (Map<String, TelemetryHistory.Reading>) invoke(service, "displayReadings", new Class<?>[0]);
            ChargingEstimate.Result estimate = service.chargingEstimate(own);
            require(estimate.charging, label + " " + name + ": the simulator charges");
            ChargeLiveState.Input in = new ChargeLiveState.Input();
            in.scooter = "Synthetic UT5 Max"; in.percent = 62; in.nowWall = System.currentTimeMillis();
            boolean capped = estimate.targetPercent != null && estimate.targetPercent < 100;
            in.minutesLeft = capped ? estimate.remainingMinutesToLimit : estimate.remainingMinutesToFull;
            in.watts = estimate.inputWatts;
            require(held == (in.minutesLeft == null), label + " " + name + ": the card's minutes " + in.minutesLeft);
            ChargeLiveState live = ChargeLiveState.of(in, wall -> "10:42 PM");
            require(live != null && live.text != null, label + " " + name + ": a live card");
            require(live.text.startsWith(estimating) == held && (live.publicText == null || live.publicText.startsWith(estimating) == held),
                    label + " " + name + ": the live card says '" + live.text + "' (lock screen '" + live.publicText + "')");
            // Charge done by plans from the estimate's minutes: none while held.
            Object plan = invoke(service, "chargeScheduleContext", new Class<?>[0]);
            Object minutes = field(plan, "estimateMinutes");
            require(held ? minutes == null : minutes instanceof Double && (Double) minutes > 0,
                    label + " " + name + ": Charge done by's minutes " + minutes);
            facts.set(label + " " + name + ": tile '" + text + "', live card '" + live.text + "', Charge done by minutes " + minutes);
        });
        record(facts.get());
        capture(new File(dir(), label + "-" + name + ".png"), null);
        capture(new File(dir(), label + "-" + name + "-tile.png"), tileRect.get());
    }

    /** Opens Home on the simulator's charge, then holds it to the fixture's readings (as ChargingPillValidation). */
    private void launch(String style, String theme) throws Exception {
        if (activity != null) { MainActivity old = activity; main(() -> { service.setSimulation(false); old.finish(); }); SystemClock.sleep(1200); }
        setHold(null);
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putBoolean("developer_options", true).putString("theme", theme)
                .putString("home_style", style).putBoolean("home_style_chosen", true).commit(), "Could not seed preferences");
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        SystemClock.sleep(1500); sync();
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel(""); service.ble.setSimulationCharging(true); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready(), "The simulated scooter is not ready");
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        long end = SystemClock.uptimeMillis() + 15000;
        boolean shown = false;
        while (!shown && SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            main(() -> activity.redraw());
            SystemClock.sleep(400); sync();
            main(() -> { View h = hero(); ok.set(h != null && h.isShown() && field(h, "reveal") == null); });
            shown = Boolean.TRUE.equals(ok.get());
        }
        require(shown, label + ": the charging view did not show on the simulator");
        main(() -> {
            ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
            service.unlisten((Runnable) field(activity, "serviceChanged"));
        });
        SystemClock.sleep(1500);
    }

    // ---- The hold hook ----------------------------------------------------------------------------------------

    /** ScooterService's test-only hold (null: the live one, which never holds on the simulator). */
    private static void setHold(Boolean hold) {
        try {
            Field f = ScooterService.class.getDeclaredField("testEstimateHold");
            f.setAccessible(true);
            f.set(null, hold);
        } catch (Exception e) { throw new IllegalStateException("testEstimateHold", e); }
    }

    // ---- Fixtures and captures --------------------------------------------------------------------------------

    private void refresh(Map<String, TelemetryHistory.Reading> values) {
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
        SystemClock.sleep(300); sync();
    }
    private static Map<String, TelemetryHistory.Reading> readings(double percent, double amps) {
        long wall = System.currentTimeMillis();
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        for (Object[] r : new Object[][]{{"charging_state", 1d}, {"voltage_v", 50d}, {"current_a", -amps}, {"battery_percent", percent}, {"rated_capacity_ah", 15.3d}})
            values.put((String) r[0], new TelemetryHistory.Reading((String) r[0], (String) r[0], "Synthetic fixture", String.valueOf(r[1]), "", (Double) r[1], wall, true));
        return values;
    }
    private View hero() {
        View clean = root().findViewWithTag("clean-home-charging");
        if (clean != null) return clean;
        Object screen = field(activity, "screen");
        return screen instanceof DashboardScreen ? (View) field(screen, "chargingHero") : null;
    }

    /** Saves the screen, cropped to {@code g} (left, top, right, bottom) when given. */
    private void capture(File file, int[] g) throws Exception {
        sync();
        Bitmap full = getUiAutomation().takeScreenshot(); require(full != null, "Screenshot unavailable");
        Bitmap out = full;
        if (g != null) {
            int pad = Math.round(8 * getTargetContext().getResources().getDisplayMetrics().density);
            int left = Math.max(0, g[0] - pad), top = Math.max(0, g[1] - pad), right = Math.min(full.getWidth(), g[2] + pad), bottom = Math.min(full.getHeight(), g[3] + pad);
            out = Bitmap.createBitmap(full, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }
        try (FileOutputStream stream = new FileOutputStream(file)) { out.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        if (out != full) out.recycle();
        full.recycle();
        screenshots.add(file.getAbsolutePath());
        TextCensus.write(this, file);
    }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

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
    private void record(String line) { checks.add(line); Log.i("ChargeEstimatingFirst", line); }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-estimating-first"); d.mkdirs(); return d; }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    /** A pass through the main thread's queue (the charging glow never lets it idle). */
    private void sync() { main(() -> {}); SystemClock.sleep(120); main(() -> {}); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(40); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name + ": " + error, error); }
    }
}
