package com.jjricks.volt.tests;

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

import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Range at this pace at riding mode's start (owner, 2.0.5): until this ride's pace is known it shows a dash, not the
 * learned prior's figure (which opened near a standstill at about 2.5 times the settled number) and not the scooter's
 * own range. Against the sample scooter (its synthetic prior), in one riding mode: opened while stopped (a dash, and
 * still a dash while stopped), riding (the number arrives once the estimator has settled, about half a minute at pace,
 * and never goes back to a dash), a stop (the number holds), rotation and a fold or unfold (the same session, the number
 * kept), then Back and reopening while moving (a dash again while the new session learns the pace).
 *
 * <p>Arguments: {@code -e window phone|fold} (1080x2400 or the unfolded 2074x2153, both at 420 dpi; default phone)
 * and {@code -e theme dark|light} (default dark). Screenshots go to the app's files, riding-range-start/.
 * Disposable emulator only: it refuses a device or an app with data.
 */
public final class RidingRangeStartValidation extends ValidationInstrumentation {
    private static final String NAME = "Range at this pace";
    /** The phone, and the unfolded Fold. */
    private static final String[] WINDOWS = {"1080x2400", "2074x2153"};
    private MainActivity activity;
    private final Bundle result = new Bundle();
    private final ArrayList<String> notes = new ArrayList<>();
    private File evidence;
    private int assertions;
    private boolean fold;
    private String theme = "dark", immersivePrevious;
    /** What this run changed on the emulator, so the finally restores only that (a refused device is left as it was). */
    private boolean immersiveChanged, windowChanged;

    @Override public void onCreate(Bundle args) {
        if (args != null) { fold = "fold".equals(args.getString("window")); theme = "light".equals(args.getString("theme")) ? "light" : "dark"; }
        start();
    }

    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-range-start");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            immersiveChanged = true;
            shell("settings put secure immersive_mode_confirmations confirmed");
            windowChanged = true;
            window(fold);
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).putString("theme", theme).commit();
                new ScooterProfiles(getTargetContext()).create("Synthetic range fixture", "000000000000", 0);
                OnboardingScreen.markDone(getTargetContext());
            });
            run();
            result.putString("result", "PASS");
        } catch (Throwable t) {
            result.putString("result", "FAIL");
            result.putString("failure", android.util.Log.getStackTraceString(t));
        } finally {
            if (activity != null) try { main(() -> { activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            try { if (windowChanged) { shell("wm size reset"); shell("wm density reset"); } } catch (Throwable ignored) { }
            try {
                if (immersiveChanged) shell(immersivePrevious.isEmpty() || immersivePrevious.equals("null")
                        ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious);
            } catch (Throwable ignored) { }
            try { java.nio.file.Files.write(new File(evidence, "notes-" + label() + ".txt").toPath(), notes); } catch (Throwable ignored) { }
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
        main(() -> require(activity.service().setSimulation(true), "Simulation on failed"));
        await(() -> activity.service().ble.ready(), "Simulation not ready");
        script("stopped");
        settle();

        // Opened while stopped: a dash at once, and still a dash while stopped (the prior's figure would be 40 km here).
        openRiding();
        Object session = session();
        require(range().equals(none()), "Opened stopped: " + range());
        shot("1-opened-stopped");
        dashFor(12_000, "stopped after opening");
        shot("2-stopped-12s");

        // Riding: a dash while the estimator settles (about half a minute at pace), then this ride's number.
        script("riding");
        long rideFrom = SystemClock.uptimeMillis();
        long shownAt = untilNumber(150_000, "riding");
        long settleMs = shownAt - rideFrom;
        require(settleMs >= 25_000, "The number came before the pace could be known: " + settleMs + " ms");
        note("riding: number after " + settleMs + " ms: " + range());
        SystemClock.sleep(3000);
        shot("3-settled");
        numberFor(20_000, "riding on");

        // A stop: the number holds (dimmed), never a dash.
        script("stopped");
        numberFor(20_000, "stopped mid-ride");
        shot("4-stopped-held");
        script("riding");

        // Rotation and a fold or unfold: the activity handles both itself, so the session and its number stay.
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        require(session() == session, "Rotation should keep the session");
        numberFor(3000, "landscape");
        shot("5-landscape");
        rotate(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        window(!fold);
        settle();
        require(session() == session, (fold ? "Folding" : "Unfolding") + " should keep the session");
        numberFor(3000, fold ? "folded" : "unfolded");
        shot(fold ? "6-folded" : "6-unfolded");
        window(fold);
        settle();
        require(session() == session, "Changing back should keep the session");
        numberFor(3000, "window back");

        // Back, then reopen while riding: a new session learns the pace again, so a dash first.
        main(() -> activity.onBackPressed());
        await(() -> field(activity, "riding") == null, "Back did not close riding mode");
        settle();
        openRiding();
        require(session() != session, "Reopening should start a new session");
        require(range().equals(none()), "Reopened moving: " + range());
        shot("7-reopened-moving");
        long reopenedFrom = SystemClock.uptimeMillis();
        long again = untilNumber(150_000, "reopened") - reopenedFrom;
        require(again >= 25_000, "Reopened: the number came before the pace could be known: " + again + " ms");
        note("reopened: number after " + again + " ms: " + range());
        main(() -> activity.onBackPressed());
        await(() -> field(activity, "riding") == null, "Back did not close riding mode");
    }

    /** What the range spot says to a screen reader: its name and value, or its name and "no reading" for the dash. */
    private String range() {
        AtomicReference<String> found = new AtomicReference<>();
        main(() -> found.set(find(activity.getWindow().getDecorView())));
        require(found.get() != null, "No " + NAME + " spot on screen");
        return found.get();
    }
    private String find(View view) {
        CharSequence said = view.isShown() ? view.getContentDescription() : null;
        if (said != null && said.toString().startsWith(NAME + ",")) return said.toString();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            String inner = find(((ViewGroup) view).getChildAt(i));
            if (inner != null) return inner;
        }
        return null;
    }
    private static String none() { return NAME + ", no reading"; }
    private boolean number(String said) { return said.matches(NAME + ", ≈? ?[0-9]+([.][0-9])? (mi|km)"); }

    private void dashFor(long ms, String label) {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) { String r = range(); require(r.equals(none()), label + ": " + r); SystemClock.sleep(500); }
        note(label + ": a dash for " + ms + " ms");
    }
    private void numberFor(long ms, String label) {
        long until = SystemClock.uptimeMillis() + ms;
        do { String r = range(); require(number(r), label + ": " + r); SystemClock.sleep(500); } while (SystemClock.uptimeMillis() < until);
        note(label + ": a number for " + ms + " ms, last " + range());
    }
    /** Waits for the number; until then only the dash, never another figure. Returns when it arrived. */
    private long untilNumber(long ms, String label) {
        long until = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < until) {
            String r = range();
            if (number(r)) return SystemClock.uptimeMillis();
            require(r.equals(none()), label + ": neither the dash nor a number: " + r);
            SystemClock.sleep(250);
        }
        throw new AssertionError(label + ": no number within " + ms + " ms: " + range());
    }

    private void openRiding() {
        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null, "Riding mode did not open");
        settle();
        SystemClock.sleep(1200); // The once-a-second refresh draws the spots.
    }
    private void script(String id) { main(() -> activity.service().ble.setSimulationScript(id)); }
    private void rotate(int orientation) {
        main(() -> activity.setRequestedOrientation(orientation));
        SystemClock.sleep(2500);
        settle();
    }
    private void window(boolean unfolded) throws Exception {
        shell("wm density 420");
        shell("wm size " + WINDOWS[unfolded ? 1 : 0]);
        SystemClock.sleep(1500);
    }
    private Object session() {
        AtomicReference<Object> session = new AtomicReference<>();
        main(() -> session.set(field(activity, "riding")));
        return session.get();
    }
    private String label() { return (fold ? "fold" : "phone") + "-" + theme; }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot failed");
        File image = new File(evidence, label() + "-" + name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
        TextCensus.write(this, image);
    }
    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = r.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
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
        long until = SystemClock.uptimeMillis() + 10_000;
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
}
