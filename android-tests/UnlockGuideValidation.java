package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
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
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio off. Security's "Unlock without phone" guide and its handlebar drawing (owner, Nov 17
 * 2026: the old drawing "is old and made by Codex and sucks"). At the emulator's current size, for each case of argument
 * cases (theme:text scale, comma separated; default light:1,dark:1,dark:1.4), with a saved UT5 MAX: reaches Security the
 * rider's way, taps the guide's row, and checks the drawing is shown whole inside the dialog with its screen-reader text,
 * beside the three steps. Then once with another saved model (a GT3 Max): the same steps, and no drawing, since the drawing
 * pictures the UT5 MAX's controls. A screenshot of each case is taken for review. Sends no scooter command.
 */
public final class UnlockGuideValidation extends Instrumentation {
    private MainActivity activity;
    private Bundle args;
    private String label = "";
    private int assertions;
    private String ut5, other;
    /** The redrawn handlebar's class. Named, not referenced, outside verifyDrawing: the base app (old inner class) lacks it. */
    private static final String REDRAWN = "com.jjricks.volt.HandlebarDiagram";
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            main(() -> {
                // Scooter pages need a saved scooter; nothing here connects to it.
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic GT3 Max", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                p.advertisedPid = "24182"; p.advertisedPidWall = System.currentTimeMillis(); profiles.update(p); other = p.id;
                ScooterProfiles.Profile u = profiles.create("Synthetic UT5 MAX", "000000000000", 0); u.serial = "X2538-SYNTHETIC"; profiles.update(u); ut5 = u.id;
                profiles.select(ut5); OnboardingScreen.markDone(getTargetContext());
            });
            String size = args.getString("size_label", "size");
            for (String c : args.getString("cases", "light:1,dark:1,dark:1.4").split(",")) {
                String[] parts = c.split(":");
                label = size + "-" + parts[0] + "-" + parts[1];
                verifyGuide(parts[0], Float.parseFloat(parts[1]), true);
            }
            main(() -> new ScooterProfiles(getTargetContext()).select(other));
            label = size + "-other-model";
            verifyGuide("light", 1, false);
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", label + ": " + failure);
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot(label + "-failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void verifyGuide(String theme, float textScale, boolean drawn) throws Exception {
        if (activity != null) { MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle(); }
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putString("theme", theme).putFloat("text_scale", textScale)
                .putString("home_style", "clean").putBoolean("home_style_chosen", true).commit(), "Could not seed preferences");
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch");
        SystemClock.sleep(1200); settle();
        HubTestPages.reach(this, activity, "security"); settle();
        View[] row = {null};
        main(() -> row[0] = guideRow());
        HubTestPages.tapView(this, activity, row[0], "the Unlock without phone row");
        AlertDialog[] dialog = {null};
        await(() -> (dialog[0] = openDialog()) != null, "The Unlock without phone row opened no dialog");
        SystemClock.sleep(600); settle();
        main(() -> {
            View panel = dialog[0].getWindow().getDecorView();
            View drawing = find(panel, v -> v.getContentDescription() != null && v.getContentDescription().toString().startsWith(T.morePinDiagram()));
            for (String step : new String[]{T.morePinGuideSignal(), T.morePinGuideDigit(), T.morePinGuidePower()})
                require(find(panel, v -> v instanceof TextView && step.contentEquals(((TextView) v).getText())) != null, "The guide has no step '" + step + "'");
            if (!drawn) {
                View redrawn = find(panel, v -> REDRAWN.equals(v.getClass().getName()));
                if (redrawn == null && drawing != null) {
                    // The base app (before the redraw) shows its old drawing for every model: reported, not failed, so a
                    // census of the base still captures the guide.
                    checks.add(label + ": the base app's old drawing (" + drawing.getClass().getSimpleName() + ") is shown for this model too");
                    return;
                }
                require(drawing == null && redrawn == null, "Another model's guide shows the UT5 MAX handlebar");
                checks.add(label + ": another model's guide shows the three steps and no handlebar drawing");
                return;
            }
            require(drawing != null && drawing.isShown(), "The guide shows no handlebar drawing");
            Rect visible = new Rect();
            require(drawing.getGlobalVisibleRect(visible) && visible.width() == drawing.getWidth(), "The drawing is clipped across: " + visible.width() + " of " + drawing.getWidth() + " px");
            require(drawing.getHeight() > 0, "The drawing has no height");
            checks.add(label + ": the guide's drawing is " + Math.round(drawing.getWidth() / density()) + "x" + Math.round(drawing.getHeight() / density())
                    + " dp in a " + Math.round(panel.getWidth() / density()) + " dp dialog, whole across, beside the three steps; it says '" + drawing.getContentDescription() + "'");
            if (REDRAWN.equals(drawing.getClass().getName())) verifyDrawing(drawing);
            else checks.add(label + ": the base app's old drawing (" + drawing.getClass().getSimpleName() + "); the redraw's checks do not apply");
        });
        shot(label + "-guide");
        main(() -> dialog[0].dismiss()); settle();
    }
    /**
     * The redrawn handlebar (owner, Nov 17 2026): in proportion at its width (never squashed or stretched), the switch big
     * enough to read, the three parts the steps use drawn in the accent and the light button not, and screen-reader text that
     * names the numbered parts. Main thread.
     */
    private void verifyDrawing(View drawing) {
        HandlebarDiagram diagram = (HandlebarDiagram) drawing;
        float drawn = Math.min(drawing.getWidth(), 420 * density()), expected = drawn * HandlebarDiagram.HEIGHT / HandlebarDiagram.WIDTH;
        require(Math.abs(drawing.getHeight() - expected) <= 2, "The drawing is " + drawing.getHeight() + " px tall, not " + Math.round(expected) + " px for its width");
        float rocker = 50 * diagram.scale() / density();
        require(rocker >= 32, "The turn-signal rocker is only " + rocker + " dp wide");
        Bitmap picture = Bitmap.createBitmap(drawing.getWidth(), drawing.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            drawing.draw(new android.graphics.Canvas(picture));
            int accent = activity.ui().accent;
            for (float[] point : HandlebarDiagram.ACCENT_POINTS) {
                float[] at = diagram.toView(point[0], point[1]); int pixel = picture.getPixel(Math.round(at[0]), Math.round(at[1]));
                require(near(pixel, accent), "The part at " + point[0] + "," + point[1] + " is #" + Integer.toHexString(pixel) + ", not the accent");
            }
            for (float[] point : HandlebarDiagram.NEUTRAL_POINTS) {
                float[] at = diagram.toView(point[0], point[1]); int pixel = picture.getPixel(Math.round(at[0]), Math.round(at[1]));
                require(android.graphics.Color.alpha(pixel) == 255 && !near(pixel, accent), "The light button at " + point[0] + "," + point[1] + " is #" + Integer.toHexString(pixel));
            }
        } finally { picture.recycle(); }
        require(String.valueOf(drawing.getContentDescription()).equals(T.morePinDiagram()), "The drawing says '" + drawing.getContentDescription() + "'");
        checks.add(label + ": the drawing keeps its proportions, its rocker is " + Math.round(rocker) + " dp wide, and the rocker, brake lever and M/power button are in the accent");
    }
    private static boolean near(int a, int b) {
        return Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= 6 && Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= 6
                && Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= 6;
    }
    /** The guide's row on Security: the shown row whose title is the guide's name. Main thread. */
    private View guideRow() {
        View title = find(root(), v -> v instanceof TextView && v.isShown() && T.morePinGuideRow().contentEquals(((TextView) v).getText()));
        require(title != null, "Security shows no Unlock without phone row");
        View row = title;
        while (row != null && !row.hasOnClickListeners()) row = row.getParent() instanceof View ? (View) row.getParent() : null;
        require(row != null, "The Unlock without phone row is not tappable");
        return row;
    }
    @SuppressWarnings("unchecked")
    private AlertDialog openDialog() {
        Object more = HubTestPages.part(field(activity, "screen"), "MoreScreen");
        if (more == null) return null;
        for (AlertDialog d : (List<AlertDialog>) field(more, "dialogs")) if (d.isShowing()) return d;
        return null;
    }
    private interface Match { boolean test(View view); }
    private static View find(View view, Match match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), match); if (found != null) return found;
        }
        return null;
    }
    private float density() { return getTargetContext().getResources().getDisplayMetrics().density; }

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
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "unlock-guide-validation"); d.mkdirs(); return d; }
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
    private static Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
}
