package com.jjricks.volt.tests;

import android.Manifest;
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
import android.widget.ScrollView;
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
import com.jjricks.volt.*;

/**
 * Widgets Home's cards on the simulator's UT5 MAX, as Widgets comes on a fresh switch (2.0.5, Home / Widgets P3s).
 * Empty disposable emulator only: it refuses an app with data, never touches Bluetooth, and leaves nothing behind but
 * its screenshots. Arguments: -e theme dark|light, -e text_scale (1), -e size_label (for the pictures' names) and
 * -e mode:
 * <ul>
 *   <li>edit (default): edit mode. On every card whose editor controls sit on one row with room beside them for a title,
 *   the card's first words (its title, or the first text at its top) are not under a control (README #613: Max. speed,
 *   Record ride and Usable ride energy had their titles covered). The lock cards' centred words are left out. Pictures of
 *   the top and of the rest of Home.</li>
 *   <li>tiles: the Reading tiles (#514 review, F02). A value that grows after layout (a stored trips text gaining
 *   words) in the same space makes the tiles fit their card again, dropping labels, rather than keep a mode that no
 *   longer fits and grow past the card; once they show values only nothing more is tried. A picture of the card with the
 *   long value.</li>
 * </ul>
 */
public final class WidgetsEditValidation extends ValidationInstrumentation {
    private static final String DIR = "widgets-edit-validation";
    private MainActivity activity;
    private Bundle args;
    private int assertions;
    private String label = "";
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "edit");
        String theme = args.getString("theme", "dark"), text = args.getString("text_scale", "1");
        label = args.getString("size_label", getTargetContext().getResources().getConfiguration().screenWidthDp + "") + "-" + theme + "-" + text;
        boolean admitted = false;
        try {
            require(mode.equals("edit") || mode.equals("tiles"), "Unknown -e mode " + mode + " (edit or tiles)");
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false).putBoolean("phone_gps", false)
                    .putString("theme", theme).putString("units", "mi").putFloat("text_scale", Float.parseFloat(text))
                    .putString("home_style", "widgets").putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .putBoolean("whats_new_shown", true).commit(), "Fixture settings failed");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000000", 0);
                p.serial = "X2538-SYNTHETIC"; profiles.update(p); profiles.select(p.id);
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root() != null && root().getWidth() > 0, "App did not launch");
            main(() -> { ScooterService s = activity.service(); require(s.simulating() || s.setSimulation(true), "Simulation did not start"); s.ble.setSimulationModel(""); s.ble.tickSimulation(); });
            SystemClock.sleep(1500); settle();
            await(() -> field(activity, "screen") instanceof DashboardScreen, "Widgets Home is not shown");
            if (mode.equals("tiles")) tiles(); else edit();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            failure.printStackTrace();
            if (activity != null) try { screenshot("failure-" + label); } catch (Throwable ignored) {}
        } finally {
            if (admitted && activity != null) try {
                main(() -> {
                    Object screen = field(activity, "screen");
                    if (screen instanceof DashboardScreen && ((DashboardScreen) screen).isEditing()) invoke(screen, "toggleEditing");
                    if (activity.service() != null) activity.service().setSimulation(false);
                    activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class));
                });
            } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== Edit mode: the editor controls leave the card's first words readable =====

    private void edit() throws Exception {
        main(() -> { DashboardScreen d = dashboard(); if (!d.isEditing()) invoke(d, "toggleEditing"); });
        SystemClock.sleep(1200); settle();
        main(() -> require(dashboard().isEditing(), "Edit mode did not start"));
        List<String> seen = new ArrayList<>();
        ScrollView scroll = onMain(() -> (ScrollView) field(activity, "scroll"));
        for (int page = 0; page < 6; page++) {
            main(() -> checkCards(seen));
            shot("edit-" + label + "-" + page);
            int before = onMain(scroll::getScrollY);
            main(() -> scroll.scrollBy(0, Math.max(1, scroll.getHeight() * 2 / 3)));
            settle();
            if (onMain(scroll::getScrollY) == before) break;
        }
        require(seen.size() >= 4, label + ": edit mode checked too few cards: " + seen);
        checks.add(label + ": in edit mode each card's first words stay clear of its controls where they sit on one row with room beside them: " + seen);
    }

    /** On the main thread: each card on screen, once. */
    private void checkCards(List<String> seen) {
        DashboardScreen d = dashboard();
        float density = activity.getResources().getDisplayMetrics().density;
        for (Object tile : (List<?>) field(d, "tiles")) {
            View card = (View) tile;
            DashboardConfig.Widget widget = (DashboardConfig.Widget) field(tile, "widget");
            Rect cardRect = new Rect();
            if (!card.isShown() || !card.getGlobalVisibleRect(cardRect) || cardRect.height() < card.getHeight()) continue;
            String name = widget.type + ":" + widget.id.substring(0, Math.min(6, widget.id.length()));
            if (seen.contains(name)) continue;
            View bar = card.findViewWithTag("widget-editor-bar");
            require(bar != null, label + ": " + name + " has no editor controls in edit mode");
            int target = (Integer) field(bar, "target"), inset = (Integer) field(bar, "resizeInset");
            List<Rect> controls = new ArrayList<>();
            int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
            for (int i = 0; i < ((ViewGroup) bar).getChildCount(); i++) {
                View control = ((ViewGroup) bar).getChildAt(i);
                if (control.getVisibility() != View.VISIBLE) continue;
                Rect r = new Rect(); control.getGlobalVisibleRect(r); controls.add(r);
                top = Math.min(top, r.top); bottom = Math.max(bottom, r.bottom);
            }
            boolean oneRow = !controls.isEmpty() && bottom - top <= target + 1;
            boolean room = card.getWidth() - inset - controls.size() * target >= Math.round(96 * density);
            boolean centred = widget.type.equals("lock") || widget.type.equals("lockbar");
            TextView first = firstWords((View) field(tile, "content"), bottom);
            seen.add(name);
            if (first == null) { checks.add(label + ": " + name + ": no words under the controls' row"); continue; }
            Rect words = wordsStart(first, Math.round(56 * density));
            Rect covering = null;
            for (Rect control : controls) if (Rect.intersects(control, words)) covering = control;
            String said = first.getText().toString();
            if (oneRow && room && !centred)
                require(covering == null, label + ": in edit mode a control covers " + name + "'s first words \"" + said + "\" (" + words + " under " + covering + ")");
            checks.add(label + ": " + name + " \"" + said + "\" " + (covering == null ? "clear" : "covered") + (oneRow && room && !centred ? "" : " (not required: " + (centred ? "centred" : oneRow ? "no room" : "two rows") + ")"));
        }
    }

    /** The card's first words: its topmost shown text (leftmost of a row) that starts above the controls' bottom. */
    private TextView firstWords(View content, int above) {
        List<TextView> texts = new ArrayList<>();
        collect(content, texts);
        TextView best = null; Rect bestRect = null;
        for (TextView text : texts) {
            Rect r = new Rect();
            if (!text.isShown() || text.getText().length() == 0 || text.getText().toString().trim().isEmpty() || !text.getGlobalVisibleRect(r) || r.top >= above) continue;
            if (best == null || r.top < bestRect.top - 4 || Math.abs(r.top - bestRect.top) <= 4 && r.left < bestRect.left) { best = text; bestRect = r; }
        }
        return best;
    }

    private static void collect(View view, List<TextView> out) {
        if (view instanceof TextView) out.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }

    /** Where a text's first line starts, on screen: its first width px (or the whole line when shorter). */
    private static Rect wordsStart(TextView text, int width) {
        int[] at = new int[2]; text.getLocationOnScreen(at);
        android.text.Layout layout = text.getLayout();
        int left = at[0] + text.getTotalPaddingLeft() + (layout == null ? 0 : Math.round(layout.getLineLeft(0)));
        int lineWidth = layout == null ? text.getWidth() : Math.round(layout.getLineWidth(0));
        int top = at[1] + text.getTotalPaddingTop() + (layout == null ? 0 : layout.getLineTop(0));
        int bottom = at[1] + text.getTotalPaddingTop() + (layout == null ? text.getHeight() : layout.getLineBottom(0));
        return new Rect(left, top, left + Math.max(1, Math.min(width, lineWidth)), bottom);
    }

    // ===== Reading tiles: a value that grows fits the card again =====

    private void tiles() throws Exception {
        Object[] found = {null};
        await(() -> {
            for (Object tile : (List<?>) field(dashboard(), "tiles")) {
                Object tiles = field(tile, "readingTiles");
                if (tiles instanceof View && ((View) tiles).isShown() && ((View) tiles).getWidth() > 0) { found[0] = tiles; return true; }
            }
            return false;
        }, "Widgets Home shows no Reading tiles");
        View tiles = (View) found[0];
        main(() -> ((View) tiles).requestRectangleOnScreen(new Rect(0, 0, tiles.getWidth(), tiles.getHeight()), true));
        SystemClock.sleep(800); settle();
        String[] longer = {"12.5 km 15.0 km/h 18 min", "3.1 km 9.8 km/h 6 min", "22.0 km 17.4 km/h 41 min", "7.7 km 12.2 km/h 15 min"};
        int[] result = new int[5];
        Bitmap[] grown = {null};
        File dir = new File(getTargetContext().getExternalFilesDir(null), DIR); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, "tiles-" + label + "-grown.png");
        main(() -> {
            int budget = (Integer) field(tiles, "budget"), mode = (Integer) field(tiles, "mode");
            require(budget > 0, label + ": the Reading tiles have no height budget to fit");
            TextView value = (TextView) ((List<?>) field(tiles, "values")).get(0);
            value.setText(String.join(" ", longer));
            // The same space: what a layout pass does when only the value changed.
            tiles.measure(View.MeasureSpec.makeMeasureSpec(tiles.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            result[0] = budget; result[1] = mode; result[2] = tiles.getMeasuredHeight(); result[3] = (Integer) field(tiles, "mode");
            // Measured again in the same space: the mode it chose holds (no search on every pass).
            tiles.measure(View.MeasureSpec.makeMeasureSpec(tiles.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            result[4] = (Integer) field(tiles, "mode");
            // Its picture as measured, before a refresh can put the live value back: laid out at that height and drawn
            // over the page's background.
            int w = tiles.getWidth(), h = tiles.getMeasuredHeight();
            tiles.layout(tiles.getLeft(), tiles.getTop(), tiles.getLeft() + w, tiles.getTop() + h);
            grown[0] = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(grown[0]);
            canvas.drawColor(activity.ui().bg);
            tiles.draw(canvas);
            // Its census from the same views, at the same moment, as drawn (the picture is a render of this one view).
            TextCensus.writeView(this, file, tiles);
            tiles.requestLayout();
        });
        int budget = result[0], before = result[1], height = result[2], after = result[3];
        require(height <= budget || after == 3, label + ": a grown value left the Reading tiles " + height + " px tall in a " + budget
                + " px card, still at label mode " + after + " (was " + before + "; 3 is values only)");
        require(after >= before, label + ": a grown value gave the tiles more labels (mode " + before + " to " + after + ")");
        require(result[4] == after, label + ": measured again in the same space, the mode changed again (" + after + " to " + result[4] + ")");
        try (FileOutputStream out = new FileOutputStream(file)) { grown[0].compress(Bitmap.CompressFormat.PNG, 100, out); } finally { grown[0].recycle(); }
        screenshots.add(file.getAbsolutePath());
        settle();
        shot("tiles-" + label + "-home");
        checks.add(label + ": a value grown after layout fits the Reading tiles again (" + height + " of " + budget + " px, label mode " + before + " to " + after + ")");
    }

    // ===== Helpers =====

    private DashboardScreen dashboard() { return (DashboardScreen) field(activity, "screen"); }
    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "onboarding", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator permissions first: " + permission);
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(700); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), DIR); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory missing");
        File file = new File(dir, name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private <T> T onMain(java.util.function.Supplier<T> supplier) { AtomicReference<T> out = new AtomicReference<>(); main(() -> out.set(supplier.get())); return out.get(); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) { AtomicReference<Boolean> ready = new AtomicReference<>(); main(() -> ready.set(condition.getAsBoolean())); if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(60); }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private static Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private static Object invoke(Object target, String name) {
        try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
