package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
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
 * Disposable empty emulator, radio off. 2.0.5 item 5: a scooter switched off but still answering. The simulator can't
 * produce that state (it never pushes, and the detector never judges the simulator), so this step turns on the
 * detector's validation-only hook, ScooterPowerState.heldOff (by reflection; nothing in the app reaches it), on the
 * simulator's UT5 MAX, not charging. On Clean and Widgets, dark and light, at the emulator's size:
 * <ul>
 *   <li>before: Home says Connected, never Scooter off;</li>
 *   <li>off: Home's status line says "Scooter off · Turn the scooter on to use it" (or its short rung "Scooter off" when
 *       narrow, never cut mid-word), the connection details say Scooter off with the line under it;</li>
 *   <li>off: cruise control, the headlight and locking are refused with "Turn the scooter on first" and nothing is
 *       applied; the charge limit is still set (and put back);</li>
 *   <li>on again (the hook cleared): Connected comes back and cruise control is set (and put back).</li>
 * </ul>
 * Pictures of every state go to the app's external files (scooter-off-validation). Sends no scooter command.
 */
public final class ScooterOffValidation extends ValidationInstrumentation {
    private static final String OFF_FULL = "Scooter off · Turn the scooter on to use it", OFF_SHORT = "Scooter off", REFUSAL = "Turn the scooter on first";
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
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000001", 0); p.serial = "Q2538ZZ9US7777777";
                profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            run();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            try { hold(false); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        String size = args.getString("size_label", "size");
        for (String style : args.getString("styles", "clean,widgets").split(",")) for (String theme : new String[] {"dark", "light"}) {
            label = size + "-" + style + "-" + theme;
            if (activity != null) { MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle(); }
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", theme)
                    .putString("home_style", style).putBoolean("home_style_chosen", true).commit(), "Could not seed preferences");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
            SystemClock.sleep(1200); settle();
            simulate();

            // Before: Connected, never Scooter off.
            redraw();
            require(!shownStartsWith(OFF_SHORT), label + ": Scooter off before the hook");
            require(!service.ble.scooterOff(), label + ": off before the hook");
            shot(label + "-on");

            // Off: Home, the refusals, the charge limit, the connection details.
            hold(true);
            await(() -> service.ble.scooterOff(), label + ": the hook did not take");
            redraw();
            String shown = statusText();
            require(OFF_FULL.equals(shown) || OFF_SHORT.equals(shown), label + ": Home's status line says '" + shown + "'");
            checks.add(label + " off: Home's status line '" + shown + "'");
            shot(label + "-off");
            refused("cruise_control", 1);
            refused("headlight", 1);
            AtomicReference<CapabilityResolver.Decision> lock = new AtomicReference<>();
            main(() -> lock.set(service.ble.decision("lock", 1)));
            require(lock.get().reason == CapabilityResolver.Reason.SCOOTER_OFF && REFUSAL.equals(lock.get().reasonText()), label + ": locking is not refused: " + lock.get());
            settable("charge_limit", 90, label + ": the charge limit while off");
            connectionDetails();

            // On again: Connected, cruise control usable.
            hold(false);
            await(() -> !service.ble.scooterOff(), label + ": still off after the hook cleared");
            redraw();
            require(!shownStartsWith(OFF_SHORT), label + ": Scooter off stayed after switching on");
            settable("cruise_control", 1, label + ": cruise control once on");
            shot(label + "-on-again");
            main(() -> service.setSimulation(false));
        }
        MainActivity old = activity; main(old::finish); SystemClock.sleep(800);
    }

    /** A change refused while off: false from set(), the refusal shown, the value unchanged. */
    private void refused(String key, int value) {
        AtomicReference<Integer> before = new AtomicReference<>(), after = new AtomicReference<>();
        AtomicReference<Boolean> sent = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        main(() -> { before.set(service.ble.featureValue(key)); sent.set(service.ble.set(key, value)); message.set(service.ble.displayText()); after.set(service.ble.featureValue(key)); });
        require(Boolean.FALSE.equals(sent.get()), label + ": " + key + " was applied while off");
        require(REFUSAL.equals(message.get()), label + ": " + key + " refusal says '" + message.get() + "'");
        require(Objects.equals(before.get(), after.get()), label + ": " + key + " changed while off");
        checks.add(label + " off: " + key + " refused with '" + message.get() + "'");
    }
    /** A change that goes through: set to value (or the other choice if it is already that), then put back. */
    private void settable(String key, int value, String what) {
        AtomicReference<Integer> before = new AtomicReference<>();
        AtomicReference<Boolean> sent = new AtomicReference<>();
        main(() -> before.set(service.ble.featureValue(key)));
        int target = before.get() != null && before.get() == value ? (key.equals("charge_limit") ? 85 : 1 - value) : value;
        main(() -> sent.set(service.ble.set(key, target)));
        require(Boolean.TRUE.equals(sent.get()), what + " was refused: " + service.ble.displayText());
        AtomicReference<Integer> now = new AtomicReference<>();
        main(() -> now.set(service.ble.featureValue(key)));
        require(Integer.valueOf(target).equals(now.get()), what + ": the simulator reads " + now.get());
        if (before.get() != null) main(() -> service.ble.set(key, before.get()));
        checks.add(what + ": set to " + target + " and put back");
    }
    private void connectionDetails() throws Exception {
        main(() -> invoke(activity, "showConnectionDetails", new Class<?>[0]));
        settle(); SystemClock.sleep(500);
        AtomicReference<AlertDialog> dialog = new AtomicReference<>();
        main(() -> dialog.set((AlertDialog) field(activity, "controlDialog")));
        require(dialog.get() != null && dialog.get().isShowing(), label + ": the connection details did not open");
        AtomicReference<Boolean> word = new AtomicReference<>(), line = new AtomicReference<>();
        main(() -> { View v = dialog.get().getWindow().getDecorView(); word.set(find(v, OFF_SHORT, true)); line.set(find(v, "Turn the scooter on to use it", true)); });
        require(Boolean.TRUE.equals(word.get()) && Boolean.TRUE.equals(line.get()), label + ": the connection details don't say Scooter off and what to do");
        shot(label + "-off-details");
        main(() -> dialog.get().dismiss());
        settle();
    }

    // ---- Fixtures -------------------------------------------------------------------------------------------

    /** The validation-only hook (ScooterPowerState.heldOff), by reflection: the app has no other way in. */
    private void hold(boolean off) throws Exception {
        Field field = Class.forName("com.jjricks.volt.ScooterPowerState").getDeclaredField("heldOff");
        field.setAccessible(true); field.setBoolean(null, off);
        if (activity != null) main(() -> { service.ble.refresh(false); activity.redraw(); });
    }
    /** The developer simulator's UT5 MAX, parked and not charging, on Home. */
    private void simulate() throws Exception {
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel(""); service.ble.setSimulationCharging(false); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready(), "The simulated scooter is not ready");
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        redraw();
    }
    private void redraw() { main(() -> activity.redraw()); settle(); SystemClock.sleep(700); settle(); }
    /** Home's status line: the shown TextView whose text is a connection state. */
    private String statusText() {
        AtomicReference<String> found = new AtomicReference<>();
        main(() -> found.set(first(root(), OFF_SHORT)));
        return found.get();
    }
    private boolean shownStartsWith(String prefix) {
        AtomicReference<Boolean> found = new AtomicReference<>();
        main(() -> found.set(first(root(), prefix) != null));
        return Boolean.TRUE.equals(found.get());
    }
    private static String first(View v, String prefix) {
        if (v == null || !v.isShown()) return null;
        if (v instanceof TextView) { CharSequence t = ((TextView) v).getText(); if (t != null && t.toString().startsWith(prefix)) return t.toString(); }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { String s = first(((ViewGroup) v).getChildAt(i), prefix); if (s != null) return s; }
        return null;
    }
    private static boolean find(View v, String text, boolean prefix) {
        String s = first(v, text);
        return s != null && (prefix || s.equals(text));
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
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "scooter-off-validation"); d.mkdirs(); return d; }
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
