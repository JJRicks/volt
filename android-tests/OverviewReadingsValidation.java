package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Dialog;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio off. Settings › Home style › Overview readings (owner, Nov 19 2026: the overview
 * card's Odometer and Drive mode "could be set to any particular data parameter individually"), on the developer
 * simulator (a GT3 Max). Sends no scooter command.
 *
 * <p>Default mode: Clean's battery card shows Odometer and Drive mode; Home style's Overview readings row names them and
 * opens First reading and Second reading, each opening the reading list (TalkBack: the rows say their slot and reading,
 * the list's rows are radio buttons, the slot's own checked). Battery voltage goes in the first slot and Battery power in
 * the second, by taps; both are stored for the scooter; the row names them. Clean shows them at once with live values and
 * names, in the same place and at the same height as before (nothing on the card moves); a reading the scooter hasn't
 * reported shows a dash and is read as Not reported. Picking the other slot's reading swaps the two. Widgets' Scooter
 * overview shows the same two; after a relaunch both Home styles still do.
 *
 * <p>Mode render: pictures only (with the checks above that apply to them), at the emulator's size, theme and text size
 * (arguments theme, text_scale, size_label): Clean and Widgets with the defaults, and with two long readings fed to the
 * card (Est. capacity (health) and Lifetime throughput, values in Ah), then Home style's page, its sheet and the list.
 */
public final class OverviewReadingsValidation extends ValidationInstrumentation {
    private static final String ODO = "odometer_km", MODE = "driving_mode", VOLT = "voltage_v", POWER = "power_w";
    private static final String LONG_A = "health_adjusted_capacity_ah", LONG_B = "capacity_throughput_ah";
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String profile, label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "all");
        label = args.getString("size_label", "size") + "-" + args.getString("theme", "dark") + "-" + args.getString("text_scale", "1");
        try {
            admit();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic GT3 Max", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                p.advertisedPid = "24182"; p.advertisedPidWall = System.currentTimeMillis(); profiles.update(p);
                profiles.select(p.id); profile = p.id; OnboardingScreen.markDone(getTargetContext());
            });
            if (mode.equals("render")) render(); else functional();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Default mode ------------------------------------------------------------------------------------------

    private void functional() throws Exception {
        launch("clean");
        requireCard(ODO, MODE, true);
        int[] before = onMain(this::cardGeometry);
        shot("clean-defaults");
        checks.add("Clean's battery card shows Odometer and Drive mode until a choice is made");

        openHomeStyle();
        main(() -> {
            View row = root().findViewWithTag("home-style-overview-readings");
            require(row != null && row.isShown() && row.isClickable(), "Home style has no Overview readings row");
            View quick = root().findViewWithTag("home-style-quick-actions");
            require(quick != null && quick.getParent() == row.getParent()
                    && ((ViewGroup) row.getParent()).indexOfChild(row) == ((ViewGroup) quick.getParent()).indexOfChild(quick) + 2,
                    "Overview readings is not right under Quick actions (after its divider)");
            String line = rowLine(row);
            require(line.equals("Odometer, Drive mode"), "The row's line says '" + line + "', not 'Odometer, Drive mode'");
        });
        shot("home-style");
        pick(0, VOLT, true);
        await(() -> VOLT.equals(settings().getString("home_reading_1_v1_" + profile, null)), "Battery voltage was not stored for the first slot");
        pick(1, POWER, false);
        await(() -> POWER.equals(settings().getString("home_reading_2_v1_" + profile, null)), "Battery power was not stored for the second slot");
        main(() -> {
            String line = rowLine(root().findViewWithTag("home-style-overview-readings"));
            require(line.equals("Battery voltage, Battery power"), "After the picks the row's line says '" + line + "'");
        });
        checks.add("Home style › Overview readings: right under Quick actions; First reading ← Battery voltage, Second reading ← Battery power, by taps, stored for the scooter; the row names them");

        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle(); SystemClock.sleep(600); settle();
        requireCard(VOLT, POWER, true);
        int[] after = onMain(this::cardGeometry);
        require(Arrays.equals(before, after), "The card moved when its readings changed: before " + Arrays.toString(before) + ", after " + Arrays.toString(after));
        shot("clean-chosen");
        checks.add("Clean shows Battery voltage and Battery power with live values; the card's top row, bar and readings keep their place and height " + Arrays.toString(after));

        // A reading the scooter hasn't reported: a dash under its name, read as Not reported (the card's own refresh, fed directly).
        main(() -> {
            Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>(service.dashboardReadings());
            values.remove(POWER);
            invoke(cleanTile(), "refresh", new Class<?>[]{Map.class}, values);
            TextView value = value(POWER);
            require("—".contentEquals(value.getText()), "An unreported reading shows '" + value.getText() + "', not a dash");
            require(String.valueOf(value.getContentDescription()).equals("Battery power, Not reported"),
                    "An unreported reading is read as '" + value.getContentDescription() + "'");
            require(captionText(POWER).length() > 0 || captionHasIcon(POWER), "An unreported reading lost its name");
        });
        shot("clean-not-reported");
        main(() -> invoke(cleanTile(), "refresh", new Class<?>[]{Map.class}, service.dashboardReadings()));
        checks.add("A reading the scooter hasn't reported: a dash under its name, read as 'Battery power, Not reported'");

        // The other slot's reading swaps the two.
        openHomeStyle();
        pick(0, POWER, false);
        await(() -> POWER.equals(settings().getString("home_reading_1_v1_" + profile, null))
                && VOLT.equals(settings().getString("home_reading_2_v1_" + profile, null)), "Picking the second slot's reading for the first did not swap them");
        pick(0, VOLT, false);
        await(() -> VOLT.equals(settings().getString("home_reading_1_v1_" + profile, null))
                && POWER.equals(settings().getString("home_reading_2_v1_" + profile, null)), "Swapping back failed");
        checks.add("Picking the reading the other slot shows swaps the two");

        relaunch("widgets");
        requireCard(VOLT, POWER, false);
        shot("widgets-chosen");
        checks.add("Widgets' Scooter overview shows the same two");
        relaunch("widgets");
        requireCard(VOLT, POWER, false);
        relaunch("clean");
        requireCard(VOLT, POWER, true);
        shot("clean-relaunched");
        checks.add("After a relaunch, both Home styles still show Battery voltage and Battery power");
    }

    // ---- Render mode -------------------------------------------------------------------------------------------

    private void render() throws Exception {
        for (String style : new String[]{"clean", "widgets"}) {
            if (activity == null) launch(style); else relaunch(style);
            requireCard(ODO, MODE, style.equals("clean"));
            shot(style + "-defaults");
            int[] before = style.equals("clean") ? onMain(this::cardGeometry) : null;
            // Two long readings, fed to the card with Home's own updates unhooked so the pictures hold them.
            require(settings().edit().putString("home_reading_1_v1_" + profile, LONG_A).putString("home_reading_2_v1_" + profile, LONG_B).commit(), "Could not store");
            hold();
            main(() -> {
                Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>(service.dashboardReadings());
                long wall = System.currentTimeMillis();
                values.put(LONG_A, new TelemetryHistory.Reading(LONG_A, LONG_A, "Battery", "13.92 Ah", "Ah", 13.92, wall, true));
                values.put(LONG_B, new TelemetryHistory.Reading(LONG_B, LONG_B, "Battery", "1234.56 Ah", "Ah", 1234.56, wall, true));
                invoke(overviewTile(style.equals("clean")), "refresh", new Class<?>[]{Map.class}, values);
            });
            settle(); SystemClock.sleep(400); settle();
            requireCard(LONG_A, LONG_B, style.equals("clean"));
            if (before != null) {
                int[] after = onMain(this::cardGeometry);
                require(Arrays.equals(before, after), label + ": the card moved for two long readings: " + Arrays.toString(before) + " → " + Arrays.toString(after));
            }
            main(() -> checks.add(label + " " + style + " long: '" + captionText(LONG_A) + "' " + value(LONG_A).getText() + " | '" + captionText(LONG_B) + "' " + value(LONG_B).getText()));
            shot(style + "-long");
            unhold();
            require(settings().edit().remove("home_reading_1_v1_" + profile).remove("home_reading_2_v1_" + profile).commit(), "Could not clear");
        }
        openHomeStyle();
        shot("home-style");
        main(() -> click(root().findViewWithTag("home-style-overview-readings")));
        settle(); SystemClock.sleep(500);
        await(() -> sheetRoot(row(), "sheet") != null, "The Overview readings sheet did not open");
        shot("home-style-sheet");
        main(() -> click(sheetRoot(row(), "sheet").findViewWithTag("home-reading-slot-1")));
        settle(); SystemClock.sleep(500);
        await(() -> picker() != null, "The reading list did not open");
        shot("home-style-list");
        main(() -> ((Dialog) fieldIn(fieldIn(fieldIn(row(), "chooser"), "this$0"), "dialog")).dismiss());
        settle();
    }

    // ---- Steps ---------------------------------------------------------------------------------------------------

    /** Home style's sheet, then slot's row, then the reading's row in the list, all by taps; TalkBack's words checked on the way. */
    private void pick(int slot, String key, boolean pictures) throws Exception {
        main(() -> click(root().findViewWithTag("home-style-overview-readings")));
        settle();
        await(() -> sheetRoot(row(), "sheet") != null, "The Overview readings sheet did not open");
        main(() -> {
            View sheet = sheetRoot(row(), "sheet");
            for (int i = 1; i <= 2; i++) {
                View row = sheet.findViewWithTag("home-reading-slot-" + i);
                require(row != null && row.isShown() && row.isClickable(), "The sheet has no slot row " + i);
                String said = String.valueOf(row.getContentDescription());
                require(said.startsWith(i == 1 ? "First reading: " : "Second reading: ") && said.endsWith(". Change"), "Slot row " + i + " is read as '" + said + "'");
            }
        });
        if (pictures) shot("home-style-sheet");
        main(() -> click(sheetRoot(row(), "sheet").findViewWithTag("home-reading-slot-" + (slot + 1))));
        settle();
        await(() -> picker() != null, "The reading list did not open");
        main(() -> {
            View list = picker();
            List<String> stored = Arrays.asList(settings().getString("home_reading_1_v1_" + profile, ODO), settings().getString("home_reading_2_v1_" + profile, MODE));
            for (String shown : new String[]{ODO, MODE, VOLT, POWER, "speed_kmh", "temperature_c"}) {
                View row = list.findViewWithTag("overview-reading-choice:" + shown);
                require(row != null, "The list has no " + shown);
                AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
                require("android.widget.RadioButton".contentEquals(node.getClassName()) && node.isCheckable()
                        && node.isChecked() == shown.equals(stored.get(slot)), shown + " is not a radio button checked only for the slot's own reading");
                require(row.getContentDescription() != null && row.getContentDescription().length() > 0, shown + " says nothing");
            }
            for (String never : new String[]{"battery_percent", "remaining_range_km", "scooter_serial", "stored_trips"})
                require(list.findViewWithTag("overview-reading-choice:" + never) == null, "The list offers " + never);
        });
        if (pictures) shot("home-style-list");
        main(() -> {
            View row = picker().findViewWithTag("overview-reading-choice:" + key);
            row.requestRectangleOnScreen(new Rect(0, 0, row.getWidth(), row.getHeight()), true);
        });
        settle();
        main(() -> click(picker().findViewWithTag("overview-reading-choice:" + key)));
        settle(); SystemClock.sleep(400); settle();
        await(() -> picker() == null && sheetRoot(row(), "sheet") == null, "The list and the sheet did not close after a pick");
    }

    /** The card shows exactly first and second as its readings, each with a name (or its icon) and a value; Clean's, or Widgets' overview. */
    private void requireCard(String first, String second, boolean clean) throws Exception {
        await(() -> {
            View card = overviewTile(clean);
            return card != null && card.findViewWithTag("overview-reading:" + first) != null && card.findViewWithTag("overview-reading:" + second) != null
                    && !"—".contentEquals(value(first).getText()) && !"—".contentEquals(value(second).getText());
        }, () -> label + ": " + (clean ? "Clean" : "Widgets") + " does not show " + first + " and " + second + " with values");
        main(() -> {
            View card = overviewTile(clean);
            List<View> items = new ArrayList<>();
            collectTagged(card, "overview-reading:", items);
            require(items.size() == 2, "The card shows " + items.size() + " readings, not two");
            for (String key : new String[]{first, second}) {
                View item = card.findViewWithTag("overview-reading:" + key);
                require(item.isShown(), key + " is not shown");
                TextView caption = (TextView) ((ViewGroup) item).getChildAt(0), value = value(key);
                require(caption.getLineCount() <= 1, key + "'s name takes " + caption.getLineCount() + " lines");
                require(caption.length() > 0 || caption.getCompoundDrawablesRelative()[0] != null, key + " has neither a name nor its icon");
                require(value.getLineCount() == 1, key + "'s value '" + value.getText() + "' takes " + value.getLineCount() + " lines");
                float sp = value.getTextSize() / getTargetContext().getResources().getDisplayMetrics().scaledDensity;
                require(sp >= 12.9f, key + "'s value is drawn at " + sp + " sp");
                String said = String.valueOf(value.getContentDescription());
                require(said.length() > 0 && !said.startsWith(","), key + "'s value is read as '" + said + "'");
                checks.add(label + " " + (clean ? "clean " : "widgets ") + key + ": '" + caption.getText() + "' " + value.getText() + " (" + String.format(Locale.US, "%.1f", sp) + " sp), read '" + said + "'");
            }
        });
    }

    /** The card's top row, bar and readings box: left, top, right and bottom in the window. */
    private int[] cardGeometry() {
        View card = overviewTile(true);
        View any = card.findViewWithTag("overview-reading:" + MODE);
        if (any == null) any = card.findViewWithTag("overview-reading:" + POWER);
        if (any == null) any = card.findViewWithTag("overview-reading:" + LONG_B);
        View box = (View) any.getParent().getParent();
        ViewGroup overview = (ViewGroup) box.getParent();
        int[] out = new int[12];
        for (int i = 0; i < 3; i++) {
            View part = overview.getChildAt(i);
            int[] at = new int[2]; part.getLocationInWindow(at);
            out[i * 4] = at[0]; out[i * 4 + 1] = at[1]; out[i * 4 + 2] = at[0] + part.getWidth(); out[i * 4 + 3] = at[1] + part.getHeight();
        }
        return out;
    }

    // ---- Fixtures ------------------------------------------------------------------------------------------------

    private void launch(String style) throws Exception {
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putBoolean("developer_options", true).putString("theme", args.getString("theme", "dark"))
                .putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1")))
                .putString("home_style", style).putBoolean("home_style_chosen", true).commit(), "Could not seed preferences");
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch");
        service = activity.service();
        SystemClock.sleep(1200); settle();
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel("24182"); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready(), "The simulated scooter is not ready");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle(); SystemClock.sleep(800); settle();
    }
    private void relaunch(String style) throws Exception {
        MainActivity old = activity;
        main(old::finish);
        SystemClock.sleep(900); settle();
        launch(style);
    }
    private void openHomeStyle() {
        main(() -> invoke(activity, "open", new Class<?>[]{String.class}, HubPages.SETTINGS_HOME));
        settle(); SystemClock.sleep(500); settle();
        await(() -> root().findViewWithTag("home-style-overview-readings") != null, "Home style did not open");
    }
    /** Home's own updates off (the pulse and the service listener), so fed readings stay on the card for its picture. */
    private void hold() {
        main(() -> {
            ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
            service.unlisten((Runnable) field(activity, "serviceChanged"));
        });
    }
    private void unhold() {
        main(() -> {
            service.listen((Runnable) field(activity, "serviceChanged"));
            ((android.os.Handler) field(activity, "handler")).post((Runnable) field(activity, "pulse"));
        });
    }
    /** Clean's battery card (a Tile), or Widgets' first Scooter overview card (the Tile holding a reading). */
    private View overviewTile(boolean clean) {
        if (clean) return root().findViewWithTag("clean-home-overview");
        List<View> items = new ArrayList<>();
        collectTagged(root(), "overview-reading:", items);
        if (items.isEmpty()) return null;
        View at = items.get(0);
        while (at != null && !at.getClass().getName().endsWith("$Tile")) at = at.getParent() instanceof View ? (View) at.getParent() : null;
        return at;
    }
    private View cleanTile() { return overviewTile(true); }
    private Object screen() { return field(activity, "screen"); }
    /** Home style's Overview readings row (HomeReadingsEditor.Row, the page's field "readings"). */
    private Object row() { return fieldIn(screen(), "readings"); }
    private View picker() {
        Object chooser = fieldIn(row(), "chooser");
        return chooser == null ? null : sheetRoot(fieldIn(chooser, "this$0"), "dialog");
    }
    private TextView value(String key) {
        View card = overviewTile(Objects.equals(settings().getString("home_style", ""), "clean"));
        View item = card == null ? null : card.findViewWithTag("overview-reading:" + key);
        return item == null ? new TextView(getTargetContext()) : (TextView) ((ViewGroup) item).getChildAt(1);
    }
    private String captionText(String key) {
        View item = overviewTile(Objects.equals(settings().getString("home_style", ""), "clean")).findViewWithTag("overview-reading:" + key);
        return ((TextView) ((ViewGroup) item).getChildAt(0)).getText().toString();
    }
    private boolean captionHasIcon(String key) {
        View item = overviewTile(Objects.equals(settings().getString("home_style", ""), "clean")).findViewWithTag("overview-reading:" + key);
        return ((TextView) ((ViewGroup) item).getChildAt(0)).getCompoundDrawablesRelative()[0] != null;
    }
    /** The Home style row's line (its second text). */
    private static String rowLine(View row) {
        List<TextView> texts = new ArrayList<>();
        collectTexts(row, texts);
        return texts.size() < 2 ? "" : texts.get(1).getText().toString();
    }

    // ---- Plumbing ------------------------------------------------------------------------------------------------

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
    private static View sheetRoot(Object owner, String name) {
        Object sheet = owner == null ? null : fieldIn(owner, name);
        return sheet instanceof Dialog && ((Dialog) sheet).isShowing() ? ((Dialog) sheet).getWindow().getDecorView() : null;
    }
    private static void collectTagged(View view, String prefix, List<View> into) {
        if (view == null) return;
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix)) into.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectTagged(((ViewGroup) view).getChildAt(i), prefix, into);
    }
    private static void collectTexts(View view, List<TextView> into) {
        if (view instanceof TextView) into.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectTexts(((ViewGroup) view).getChildAt(i), into);
    }
    private void click(View view) {
        require(view != null && view.isShown() && view.isClickable(), "Cannot tap " + view);
        Rect visible = new Rect();
        require(view.getGlobalVisibleRect(visible) && visible.height() > 0, "Not on screen: " + view.getTag());
        view.performClick();
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "overview-readings-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(500); waitForIdleSync(); screenshot(label + "-" + name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) { await(condition, () -> message); }
    private void await(BooleanSupplier condition, Supplier<String> message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> { try { ready.set(condition.getAsBoolean()); } catch (RuntimeException notYet) { ready.set(false); } });
            if (Boolean.TRUE.equals(ready.get())) return;
            SystemClock.sleep(60);
        }
        throw new IllegalStateException(message.get());
    }
    private <T> T onMain(Supplier<T> action) { AtomicReference<T> out = new AtomicReference<>(); main(() -> out.set(action.get())); return out.get(); }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private static Object fieldIn(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object field(Object target, String name) { return fieldIn(target, name); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
