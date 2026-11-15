package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
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
 * Disposable empty emulator, radio off. The charging view's time beside Charging power (owner, Nov 15 2026: "the text
 * is a little too small on the 'time to full' estimation, ideally it matches the charging power text size"). At the
 * emulator's current size, on Clean and Widgets, at 1x and 1.3x text, in English and German, on the developer simulator:
 * <ul>
 *   <li>ut5: the simulator's UT5 MAX charging (the owner's case: a built-in limit, one reading);</li>
 *   <li>alert: a simulated GT3 Max with a charge alert (Widgets: both readings, the alert's and 100%);</li>
 *   <li>start: a charge's first readings without a current (the slot says Waiting for readings, too wide for the full
 *       size), then with one: the time must come back to power's size with nothing else laying Home out (the owner's
 *       phone showed "≈ 54m" at the size the state had taken);</li>
 *   <li>hours: a synthetic time of hours.</li>
 * </ul>
 * Each time value is drawn at power's size, never larger; smaller only where even its narrow form is too wide at
 * power's size, and never in English. Sizes are recorded in the checks; argument diagnose records without failing.
 * Sends no scooter command.
 */
public final class ChargeTimeSizeValidation extends Instrumentation {
    private static final int LIMIT = 0, FULL = 1;
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String label = "";
    private int assertions;
    private LocaleList foundLocales;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private final ChargeAlertPolicy.Settings alert70 = new ChargeAlertPolicy.Settings(true, 70, false, 20, true, false);

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> OnboardingScreen.markDone(getTargetContext()));
            verifySizes();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            if (foundLocales != null) try { LocaleList back = foundLocales; main(() -> getTargetContext().getSystemService(LocaleManager.class).setApplicationLocales(back)); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void verifySizes() throws Exception {
        String size = args.getString("size_label", "size");
        String styles = args.getString("styles", "widgets,clean");
        for (String style : styles.split(",")) for (String text : new String[] {"1", "1.3"}) for (String locale : new String[] {"", "de"}) {
            label = size + "-" + style + "-" + (locale.isEmpty() ? "en" : locale) + "-" + text;
            if (activity != null) { MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle(); }
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", "dark").putFloat("text_scale", Float.parseFloat(text))
                    .putString("home_style", style).putBoolean("home_style_chosen", true).commit(), "Could not seed preferences");
            setLocale(locale);
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
            SystemClock.sleep(1500); settle();
            simulate("");
            requireSizes("ut5", locale.isEmpty());
            shot(label + "-ut5");
            simulate("24182");
            main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
            redrawUntilShown();
            requireSizes("alert", locale.isEmpty());
            shot(label + "-alert");
            // The fixture's readings from here on: Home no longer follows the simulator.
            main(() -> {
                ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
                service.unlisten((Runnable) field(activity, "serviceChanged"));
            });
            Map<String, TelemetryHistory.Reading> waiting = readings(55, 2);
            waiting.remove("current_a");
            refresh(waiting);
            main(() -> { TextView[] slot = values();
                checks.add(label + " start: the time's slots say '" + slot[LIMIT].getText() + "' and '" + slot[FULL].getText() + "'"); });
            refresh(readings(55, 2));
            requireSizes("start", locale.isEmpty());
            shot(label + "-start");
            refresh(readings(20, 2));
            requireSizes("hours", locale.isEmpty());
            shot(label + "-hours");
            main(() -> { require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"); service.setSimulation(false); });
        }
        MainActivity old = activity; main(old::finish); SystemClock.sleep(800);
        setLocale("");
    }

    /**
     * Power's and each time value's drawn size, recorded in the checks. A time value smaller than power while its own
     * text would fit on one line at power's size fails, in every language; so does one larger than power; in English
     * (strict) any time smaller than power fails.
     */
    private void requireSizes(String what, boolean strict) {
        main(() -> {
            View h = hero();
            TextView p = (TextView) field(h, "power");
            boolean two = Boolean.TRUE.equals(field(field(h, "timeTile"), "two"));
            TextView[] vals = values();
            float sp = getTargetContext().getResources().getDisplayMetrics().scaledDensity;
            float ps = p.getTextSize() / sp;
            StringBuilder line = new StringBuilder(label + " " + what + ": power " + String.format(Locale.US, "%.1f", ps) + " sp '" + p.getText() + "'");
            ArrayList<String> problems = new ArrayList<>();
            for (int r : two ? new int[] {LIMIT, FULL} : new int[] {FULL}) {
                TextView t = vals[r];
                float ts = t.getTextSize() / sp;
                android.text.TextPaint at = new android.text.TextPaint(t.getPaint()); at.setTextSize(p.getTextSize());
                float room = t.getWidth() - t.getCompoundPaddingLeft() - t.getCompoundPaddingRight();
                boolean fits = at.measureText(t.getText().toString()) <= room;
                line.append(String.format(Locale.US, " | time[%s] %.1f sp '%s' (%s at power's size; lines %d)",
                        r == FULL ? "full" : "limit", ts, t.getText(), fits ? "fits" : "too wide", t.getLineCount()));
                if (ts > ps + .01f) problems.add("time " + r + " larger than power");
                if (ts < ps - .01f && (fits || strict)) problems.add("time " + r + " '" + t.getText() + "' " + ts + " sp below power's " + ps + " sp" + (fits ? " with room" : ""));
            }
            checks.add(line.toString());
            Log.i("ChargeTimeSizeValidation", line.toString());
            if (args.getString("diagnose") == null) require(problems.isEmpty(), line + " :: " + problems);
        });
    }

    // ---- Fixtures -------------------------------------------------------------------------------------------

    /** The developer simulator charging as model pid ("" its default, the UT5 MAX), shown on Home. */
    private void simulate(String pid) throws Exception {
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel(pid); service.ble.setSimulationCharging(true); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready(), "The simulated scooter is not ready");
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        redrawUntilShown();
    }
    private void redrawUntilShown() throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            main(() -> activity.redraw());
            settle(); SystemClock.sleep(150);
            main(() -> { View h = hero(); ok.set(h != null && h.isShown() && field(h, "reveal") == null); });
            if (Boolean.TRUE.equals(ok.get())) { SystemClock.sleep(600); settle(); return; }
        }
        throw new IllegalStateException(label + ": the charging view did not show on the simulator");
    }
    /** Synthetic readings straight to the charging view (no redraw of Home, as a live update reaches it). */
    private void refresh(Map<String, TelemetryHistory.Reading> values) {
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
        settle(); SystemClock.sleep(420); settle();
    }
    private static Map<String, TelemetryHistory.Reading> readings(double percent, double amps) {
        long wall = System.currentTimeMillis();
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        for (Object[] r : new Object[][]{{"charging_state", 1d}, {"voltage_v", 52d}, {"current_a", -amps}, {"battery_percent", percent}, {"rated_capacity_ah", 14.5d}})
            values.put((String) r[0], new TelemetryHistory.Reading((String) r[0], (String) r[0], "Synthetic fixture", String.valueOf(r[1]), "", (Double) r[1], wall, true));
        return values;
    }
    /** The charging view: Clean's (tagged) when it shows, else Widgets'. */
    private View hero() {
        View clean = root().findViewWithTag("clean-home-charging");
        if (clean != null) return clean;
        Object screen = field(activity, "screen");
        return screen instanceof DashboardScreen ? (View) field(screen, "chargingHero") : null;
    }
    private TextView[] values() { return (TextView[]) field(field(hero(), "timeTile"), "values"); }
    private void setLocale(String tag) throws Exception {
        if (Build.VERSION.SDK_INT < 33) { require(tag.isEmpty(), "Languages need API 33"); return; }
        if (foundLocales == null) main(() -> foundLocales = getTargetContext().getSystemService(LocaleManager.class).getApplicationLocales());
        main(() -> getTargetContext().getSystemService(LocaleManager.class)
                .setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag)));
        SystemClock.sleep(1200); settle();
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
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-time-size-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(500); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
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
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
