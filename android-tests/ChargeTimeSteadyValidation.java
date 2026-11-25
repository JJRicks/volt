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
 * Disposable empty emulator, radio off. The charging time tile's words hold still through a page change (owner, Nov 24
 * 2026: "when I tap on the settings icon or another option that opens another menu, the Estimating text flashes
 * 'Estimating time' during the animation"). While a UT5 MAX charge's time is held at plug-in (ChargeEstimateHold, set
 * through ScooterService's test-only testEstimateHold; the simulator charges, the readings are a 1.28 A soft start), the
 * tile shows "Estimating time left" or, where those words don't fit at the size beside Charging power, "Estimating".
 *
 * <p>Every refresh (the 1 Hz pulse, any reading) used to set the long words again and leave the next measure to step them
 * down. A page change draws Home into the snapshot it crossfades away in the same message as the tap, before that measure,
 * and a transition that holds the page's layout delays it too, so the long words showed clipped ("Estimating time") for
 * the length of the motion. For Widgets and Clean, in the themes asked for (argument themes, default dark,light), at the
 * emulator's current size, this refreshes the tile exactly as the pulse does and then, in the same message:
 * <ul>
 *   <li>checks the tile still draws the rung it drew before the refresh and asks for no new measure;</li>
 *   <li>draws Home as the page change's snapshot does (Motion.capture) and saves the tile from it;</li>
 *   <li>opens Settings (then, launched again, Search) as the dock's buttons do, and saves the tile from screenshots taken
 *       through the crossfade.</li>
 * </ul>
 * A real change of width still picks the rung again: measured three times as wide it draws the long words, and back at
 * its own width the short ones. Sends no scooter command.
 */
public final class ChargeTimeSteadyValidation extends ValidationInstrumentation {
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
                for (String theme : args.getString("themes", "dark,light").split(","))
                    for (String page : new String[]{"settings", "search"}) {
                        label = size + "-" + style + "-" + theme;
                        launch(style, theme);
                        steady(page);
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

    /** Held at plug-in, settled; then a refresh and the page change in one message, as a tap right after a pulse. */
    private void steady(String page) throws Exception {
        setHold(Boolean.TRUE);
        refresh(readings(62, 1.28));
        SystemClock.sleep(700); sync();
        String full = T.chargeLiveTextEstimating(), shortRung = T.chargingEstimateEstimating();
        AtomicReference<String> settled = new AtomicReference<>();
        AtomicReference<int[]> tileRect = new AtomicReference<>();
        main(() -> {
            TextView value = value();
            require(value.isShown(), label + " " + page + ": the time tile shows");
            String drawn = value.getText().toString();
            require(drawn.equals(full) || drawn.equals(shortRung), label + ": the held tile draws one of its rungs, not '" + drawn + "'");
            require(String.valueOf(invoke(value, "fullText", new Class<?>[0])).equals(full), label + ": TalkBack keeps the full words");
            settled.set(drawn);
            Rect r = new Rect(); value.getGlobalVisibleRect(r);
            tileRect.set(new int[] {r.left, r.top, r.right, r.bottom});
            // A real change of width still picks again: three times as wide the long words fit; its own width puts back
            // what it drew (only where it drew the short rung: a tile with room for the long one has nothing to step).
            if (drawn.equals(shortRung)) {
                int width = value.getWidth();
                value.measure(View.MeasureSpec.makeMeasureSpec(width * 3, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                require(value.getText().toString().equals(full), label + ": three times as wide the tile draws '" + full + "', not '" + value.getText() + "'");
                value.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                require(value.getText().toString().equals(shortRung), label + ": back at its width the tile draws '" + shortRung + "', not '" + value.getText() + "'");
                value.requestLayout();
            }
        });
        SystemClock.sleep(400); sync();
        record(label + " " + page + ": settled on '" + settled.get() + "'");
        capture(new File(dir(), label + "-" + page + "-before.png"), tileRect.get());
        AtomicReference<Bitmap> snapshot = new AtomicReference<>();
        AtomicReference<View> home = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<Boolean> remeasure = new AtomicReference<>();
        main(() -> {
            View hero = hero();
            invoke(hero, "refresh", new Class<?>[]{Map.class}, readings(62, 1.28));  // as the pulse does, the same estimate
            TextView value = value();
            after.set(value.getText().toString());
            remeasure.set(value.isLayoutRequested());
            home.set(root());
            snapshot.set((Bitmap) invokeStatic("com.jjricks.volt.Motion", "capture", new Class<?>[]{View.class}, root()));
            // The dock's Settings and Search buttons (MainActivity: open(SETTINGS), open(SEARCH) and its field).
            invoke(activity, "open", new Class<?>[]{String.class}, page);
        });
        // Through the crossfade: screenshots as fast as the emulator gives them.
        List<Bitmap> frames = new ArrayList<>();
        long end = SystemClock.uptimeMillis() + 600;
        while (SystemClock.uptimeMillis() < end && frames.size() < 8) { Bitmap b = getUiAutomation().takeScreenshot(); if (b != null) frames.add(b); }
        int[] g = tileRect.get();
        for (int i = 0; i < frames.size(); i++) {
            // Censused as Home's views: the frames show Home's snapshot fading over the new page.
            save(frames.get(i), new File(dir(), label + "-" + page + "-frame" + i + ".png"), g, home.get());
        }
        if (snapshot.get() != null) {
            int[] at = new int[2];
            main(() -> root().getLocationOnScreen(at));
            save(snapshot.get(), new File(dir(), label + "-" + page + "-snapshot.png"), new int[] {g[0] - at[0], g[1] - at[1], g[2] - at[0], g[3] - at[1]}, home.get());
        }
        record(label + " " + page + ": after the refresh the tile draws '" + after.get() + "', new measure asked " + remeasure.get()
                + ", snapshot " + (snapshot.get() != null) + ", " + frames.size() + " frames");
        require(after.get().equals(settled.get()), label + " " + page + ": a refresh with the same estimate keeps '" + settled.get() + "', not '" + after.get() + "' (the page change's snapshot draws it)");
        require(!remeasure.get(), label + " " + page + ": a refresh with the same estimate asks for no new measure (a transition holding the layout would leave the long words drawn)");
        await(() -> page.equals(activity.shownPage()), "The " + page + " page did not open");
    }

    /** Opens Home on the simulator's charge, then holds it to the fixture's readings (as ChargeEstimatingFirstValidation). */
    private void launch(String style, String theme) throws Exception {
        if (activity != null) { MainActivity old = activity; main(() -> { service.setSimulation(false); old.finish(); }); SystemClock.sleep(1200); }
        setHold(null);
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putBoolean("developer_options", true).putString("theme", theme).putString("last_page", "home")
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
    /** The time tile's reading shown at rest. */
    private TextView value() {
        View h = hero();
        require(h != null && h.isShown(), label + ": the charging view shows");
        Object tile = field(h, "timeTile");
        return ((TextView[]) field(tile, "values"))[(Integer) invoke(tile, "chosen", new Class<?>[0])];
    }

    /** Saves the screen, cropped to {@code g} (left, top, right, bottom) when given. */
    private void capture(File file, int[] g) throws Exception {
        sync();
        Bitmap full = getUiAutomation().takeScreenshot(); require(full != null, "Screenshot unavailable");
        save(full, file, g, null);
    }
    /** Saves full (cropped to g when given) with its census: Home's views for a render of them, else the app's windows now. */
    private void save(Bitmap full, File file, int[] g, View render) throws Exception {
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
        if (render != null) TextCensus.writeView(this, file, render); else TextCensus.write(this, file);
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
        require(android.animation.ValueAnimator.areAnimatorsEnabled(), "Animations are off on this emulator: the page change would not crossfade");
    }
    private void record(String line) { checks.add(line); Log.i("ChargeTimeSteady", line); }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-time-steady"); d.mkdirs(); return d; }
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
    private static Object invokeStatic(String className, String name, Class<?>[] types, Object... values) {
        try { Method method = Class.forName(className).getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, values); }
        catch (Exception error) { throw new IllegalStateException("Method " + className + "." + name + ": " + error, error); }
    }
}
