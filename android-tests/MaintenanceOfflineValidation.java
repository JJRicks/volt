package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Fresh disposable emulator only, Bluetooth off. Adding maintenance reminders with no connection (owner, Nov 11).
 *
 * <ol>
 * <li>A saved scooter whose odometer Volt never read (connected before reminders were on: readings count only
 *     while they are on), reminders off: the Maintenance page opens, the switch turns on, and Add reminder saves
 *     a custom name by Distance (its note says it counts from the next connection), by Time in months and by
 *     Either, and the manual's Add saves a suggested interval. Every mode chip is enabled, focusable and spoken
 *     by its label; the note is shown text. The distance reminders wait with no base; nothing connects.</li>
 * <li>An app restart: the same document and one row per reminder, no duplicate.</li>
 * <li>A scooter last read 10 days ago (stale): the sheet's Distance note is the stale one, and the reminder
 *     counts from the last known odometer.</li>
 * </ol>
 * Synthesized profiles and readings only. Screenshots go to the app's external files (maintenance-offline-validation).
 */
public final class MaintenanceOfflineValidation extends Instrumentation {
    private static final long DAY = 86_400_000L;
    /** MaintenanceStore.PREFIX (package-private in the app). */
    private static final String PREFIX = "maintenance_v1_";
    private Bundle arguments;
    private MainActivity activity;
    private String neverRead, stale;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", arguments.getString("theme", "dark")).putString("units", "km")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed();
            neverReadScooter();
            restart();
            staleScooter();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false);
            stop();
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Two synthetic scooters, saved before launch: one Volt never read the odometer of, and one read 10 days ago. */
    private void seed() {
        main(() -> {
            OnboardingScreen.markDone(getTargetContext());
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile a = profiles.create("Synthetic never read", "0F1E2D3C4B5A", 0);
            a.mac = "C0:FF:EE:00:00:21"; a.lastConnected = System.currentTimeMillis() - 3 * DAY; a.ownerModel = "GT3 Max"; profiles.update(a);
            ScooterProfiles.Profile b = profiles.create("Synthetic stale", "1A2B3C4D5E6F", 0);
            b.mac = "C0:FF:EE:00:00:22"; b.lastConnected = System.currentTimeMillis() - 10 * DAY; profiles.update(b);
            neverRead = a.id; stale = b.id;
            profiles.select(neverRead);
        });
        long read = System.currentTimeMillis() - 10 * DAY;
        String staleDocument = "{\"v\":1,\"on\":true,\"distance\":{\"lastRawKm\":1250.0,\"lastWall\":" + read
                + ",\"bits\":32,\"accKm\":250.0,\"resetWall\":0,\"floorRawKm\":0.0},\"reminders\":[]}";
        require(settings().edit().putString(PREFIX + stale, staleDocument).commit(), "Maintenance fixture failed");
    }

    private void neverReadScooter() throws Exception {
        launch();
        openPage();
        View toggle = page().findViewWithTag("maintenance_switch");
        require(toggle != null && toggle.isEnabled() && toggle.isFocusable(), "The reminders switch is there and usable disconnected");
        main(toggle::performClick); settle();
        require(document(neverRead).optBoolean("on"), "Turning reminders on disconnected is saved");
        notConnected();
        View add = page().findViewWithTag("maintenance_add");
        require(add != null && add.isShown() && add.isEnabled() && add.isFocusable() && text("maintenance_add").contentEquals(add.getContentDescription()), "Add reminder is shown, focusable and spoken");

        // A custom name by distance: every mode is enabled, and Distance says it counts from the next connection.
        open(add);
        for (int i = 0; i < 3; i++) {
            TextView chip = (TextView) sheetView("maintenance_mode" + i);
            require(chip.isEnabled() && chip.isFocusable() && chip.isClickable() && chip.getAlpha() == 1f, "Mode " + i + " is offered disconnected like its connected twin");
            require(chip.getText().toString().contentEquals(chip.getContentDescription()), "Mode " + i + " is spoken by its label");
        }
        View note = sheetView("maintenance_distance_note");
        require(note.getVisibility() == View.GONE, "Time (the default with no reading) needs no note");
        click("maintenance_mode0");
        require(note.isShown() && text("maintenance_distance_next").contentEquals(((TextView) note).getText()), "Distance explains it counts from the next connection");
        require(note.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO, "The note is read by a screen reader");
        type("maintenance_name", "Oil the hinge (synthetic)");
        type("maintenance_distance", "300");
        screenshot("never-read-add-distance");
        save();
        JSONObject oil = reminder(neverRead, "Oil the hinge (synthetic)");
        require(oil.getDouble("km") == 300 && oil.getInt("days") == 0 && oil.getInt("months") == 0, "Saved by distance");
        require(!oil.has("baseAccKm"), "No reading: it waits for the next connection");

        // A custom name by time, in months: no note, counts from now.
        open(page().findViewWithTag("maintenance_add"));
        click("maintenance_mode1");
        require(sheetView("maintenance_distance_note").getVisibility() == View.GONE, "Time needs no note");
        type("maintenance_name", "Check the bell (synthetic)");
        type("maintenance_time", "2");
        click("maintenance_unit30");
        long before = System.currentTimeMillis();
        save();
        JSONObject bell = reminder(neverRead, "Check the bell (synthetic)");
        require(bell.getInt("months") == 2 && bell.isNull("km") && bell.getLong("baseWall") >= before - 1000, "Saved by time, starting now");

        // Either: the time part counts, the distance part waits.
        open(page().findViewWithTag("maintenance_add"));
        click("maintenance_mode2");
        require(sheetView("maintenance_distance_note").isShown(), "Either shows the note too");
        type("maintenance_name", "Pads (synthetic)");
        type("maintenance_distance", "500");
        type("maintenance_time", "10");
        click("maintenance_unit1");
        save();
        JSONObject pads = reminder(neverRead, "Pads (synthetic)");
        require(pads.getDouble("km") == 500 && pads.getInt("days") == 10 && !pads.has("baseAccKm"), "Saved as Either, its distance waiting");

        // The manual's Add, for a listed model: prefilled, saved as is.
        TextView manualAdd = null;
        for (TextView t : texts(page())) if (t.isShown() && t.isClickable() && text("maintenance_manual_add").contentEquals(t.getText())) { manualAdd = t; break; }
        require(manualAdd != null && manualAdd.isFocusable(), "The manual lists an Add for this model");
        open(manualAdd);
        save();
        JSONArray all = document(neverRead).getJSONArray("reminders");
        require(all.length() == 4, "Four reminders, not " + all.length());
        JSONObject manual = all.getJSONObject(3);
        require(!manual.getString("task").isEmpty() && manual.getString("name").isEmpty() && !manual.isNull("km") && !manual.has("baseAccKm"), "The manual's interval is saved, its distance waiting");
        notConnected();
        screenshot("never-read-page");
    }

    /** An app restart: the same document, every reminder once. */
    private void restart() throws Exception {
        String saved = settings().getString(PREFIX + neverRead, null);
        stop();
        launch();
        openPage();
        require(saved.equals(settings().getString(PREFIX + neverRead, null)), "A restart changes nothing in the document");
        JSONArray all = document(neverRead).getJSONArray("reminders");
        for (int i = 0; i < all.length(); i++) {
            String id = all.getJSONObject(i).getString("id");
            require(rows(page(), id) == 1, "Reminder " + id + " shows once after a restart");
        }
        screenshot("never-read-after-restart");
    }

    /** A scooter last read 10 days ago: the stale note, and the count from the last known odometer. */
    private void staleScooter() throws Exception {
        stop();
        main(() -> new ScooterProfiles(getTargetContext()).select(stale));
        launch();
        openPage();
        open(page().findViewWithTag("maintenance_add"));
        TextView note = (TextView) sheetView("maintenance_distance_note");
        // With a reading, Distance is the default; Time hides the note, Distance shows it again.
        click("maintenance_mode1");
        require(!note.isShown(), "Time needs no note");
        click("maintenance_mode0");
        String template = text("maintenance_stale");
        require(note.isShown() && note.getText().toString().startsWith(template.substring(0, template.indexOf("%1$s"))), "Distance shows the stale note: " + note.getText());
        type("maintenance_name", "Tighten the stem (synthetic)");
        type("maintenance_distance", "300");
        screenshot("stale-add-distance");
        save();
        JSONObject stem = reminder(stale, "Tighten the stem (synthetic)");
        require(stem.getDouble("baseAccKm") == 250, "It counts from the last known odometer");
        require(document(neverRead).getJSONArray("reminders").length() == 4, "The other scooter's reminders are untouched");
        notConnected();
    }

    // ---- The sheet -----------------------------------------------------------------------------

    private Object maintenance() { Object m = field(field(activity, "screen"), "maintenance"); require(m != null, "The Maintenance page is not open"); return m; }
    private View page() { return (View) invokeAny(maintenance(), "view"); }
    private AlertDialog sheet() { return (AlertDialog) field(maintenance(), "sheet"); }
    private View sheetView(String tag) {
        AlertDialog sheet = sheet(); require(sheet != null && sheet.isShowing(), "No sheet is open");
        View found = sheet.getWindow().getDecorView().findViewWithTag(tag); require(found != null, "The sheet has no " + tag); return found;
    }
    private void open(View button) {
        require(button != null, "Nothing to tap");
        main(button::performClick); settle();
        await(() -> { AlertDialog s = sheet(); return s != null && s.isShowing(); }, "The sheet did not open");
        settle();
    }
    private void click(String tag) { View v = sheetView(tag); main(v::performClick); settle(); }
    private void type(String tag, String value) { EditText field = (EditText) sheetView(tag); main(() -> field.setText(value)); settle(); }
    private void save() {
        AlertDialog sheet = sheet();
        click("maintenance_save");
        await(() -> !sheet.isShowing(), "Save did not close the sheet (a field refused it?)");
        settle();
    }

    // ---- App ------------------------------------------------------------------------------------

    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "App did not launch");
        settle();
        notConnected();
    }
    private void openPage() {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        if (field(field(activity, "screen"), "maintenance") == null) {
            View row = activity.getWindow().getDecorView().findViewWithTag("maintenance_reminders");
            require(row != null && row.isShown() && row.isClickable(), "The Scooter tab's Reminders row is there disconnected");
            main(row::performClick);
        }
        await(() -> field(field(activity, "screen"), "maintenance") != null, "The Maintenance page did not open");
        SystemClock.sleep(500); settle();
    }
    private void stop() {
        if (activity == null) return;
        MainActivity leaving = activity; activity = null;
        try { main(() -> { leaving.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
        long end = SystemClock.uptimeMillis() + 5000;
        while (staticField(ScooterService.class, "running") != null && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        settle();
    }
    /** Nothing connects: Bluetooth is off and the service reports no link. */
    private void notConnected() {
        ScooterService service = activity.service();
        require(service != null && !service.ble.connected(), "The scooter must stay disconnected");
    }

    // ---- Data -----------------------------------------------------------------------------------

    private JSONObject document(String profile) throws Exception {
        String text = settings().getString(PREFIX + profile, null); require(text != null, "No maintenance document for " + profile);
        return new JSONObject(text);
    }
    private JSONObject reminder(String profile, String name) throws Exception {
        JSONArray all = document(profile).getJSONArray("reminders"); JSONObject found = null;
        for (int i = 0; i < all.length(); i++) if (name.equals(all.getJSONObject(i).getString("name"))) { require(found == null, "Duplicate " + name); found = all.getJSONObject(i); }
        require(found != null, "Not saved: " + name);
        return found;
    }
    private String text(String name) {
        int id = getTargetContext().getResources().getIdentifier(name, "string", getTargetContext().getPackageName());
        require(id != 0, "No string " + name);
        return activity.getString(id);
    }

    // ---- Harness --------------------------------------------------------------------------------

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "maintenance-offline-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private static List<TextView> texts(View root) {
        List<TextView> out = new ArrayList<>();
        if (root instanceof TextView) out.add((TextView) root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) out.addAll(texts(((ViewGroup) root).getChildAt(i)));
        return out;
    }
    private static int rows(View root, String tag) {
        int n = tag.equals(root.getTag()) && root.isShown() ? 1 : 0;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) n += rows(((ViewGroup) root).getChildAt(i), tag);
        return n;
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private static Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object staticField(Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object invokeAny(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
}
