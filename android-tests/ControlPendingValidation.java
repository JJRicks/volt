package com.jjricks.volt.tests;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The pending spinner on controls (owner, Nov 23, 2.0.5 item 6), on the simulator made slow (SimulatedConfirmation, set
 * here by reflection). For the lock switch and the Lights page's Headlight row: every drawn frame after a tap is sampled,
 * and the spinner never shows in the first second, shows after it, and stops on the confirmation; a tap confirmed inside
 * the second never shows it. A change that never confirms stops at the existing timeout with the old state back; a
 * disconnect mid-wait stops it and a reconnect doesn't bring it back; a second lock tap while one is in flight changes
 * nothing (LockTapQueue's rule). A fold-size change, an in-app theme change and a recreated activity mid-wait keep the
 * timing and end on the confirmed state, never stuck spinning. TalkBack's announcement comes once per wait. With
 * animations off (animator scale 0) the spinner still shows, as a still picture, and the main thread goes idle.
 *
 * -e part all (default) runs the checks; -e part render takes only the mid-spinner pictures (lock and row) at the
 * window's size, in -e theme dark|light, named -e shots PREFIX.
 */
public final class ControlPendingValidation extends ValidationInstrumentation {
    private static final long DELAY = 1000;
    private MainActivity activity;
    private File evidence;
    private int assertions;
    private String part, theme, shots;
    private String previousScale;
    private final ArrayList<String> notes = new ArrayList<>();
    private final AtomicInteger announcements = new AtomicInteger();

    @Override public void onCreate(Bundle args) {
        part = args == null ? "all" : args.getString("part", "all");
        theme = args == null ? "dark" : args.getString("theme", "dark");
        shots = args == null ? "pending" : args.getString("shots", "pending");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hw.contains("ranchu") || hw.contains("goldfish"), "emulator only");
            // A disposable app only: this run seeds a profile and changes settings it doesn't put back.
            for (String store : new String[]{"app_settings", "profiles", "scooter"})
                require(getTargetContext().getSharedPreferences(store, Context.MODE_PRIVATE).getAll().isEmpty(), "Refusing existing " + store + " preferences; clear the disposable app first");
            require(getTargetContext().databaseList().length == 0, "Refusing existing databases");
            String[] files = getTargetContext().getFilesDir().list();
            require(files == null || files.length == 0, "Refusing existing files");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "control-pending");
            require(evidence.mkdirs() || evidence.isDirectory(), "evidence directory");
            launch();
            if ("render".equals(part)) render();
            else {
                listenForAnnouncements();
                lockTimings();
                lockQuickConfirm();
                lockSecondTap();
                lockTimeout();
                lockDisconnect();
                lockFold();
                lockThemeChange();
                lockRecreated();
                rowTimings();
                rowTimeout();
                stillWithAnimationsOff();
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            try { delay(0); } catch (Throwable ignored) { }
            try { getUiAutomation().setOnAccessibilityEventListener(null); } catch (Throwable ignored) { }
            // Only what this run changed goes back: the animator scale, when the animations-off check set it.
            if (previousScale != null) try {
                shell(previousScale.isEmpty() || previousScale.equals("null") ? "settings delete global animator_duration_scale"
                        : "settings put global animator_duration_scale " + previousScale);
            } catch (Throwable ignored) { }
            if (activity != null) try {
                main(() -> { activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            } catch (Throwable ignored) { }
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void launch() throws Exception {
        require(prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                .putString("theme", theme).commit(), "settings");
        main(() -> {
            OnboardingScreen.markDone(getTargetContext());
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile p = profiles.create("Synthetic pending scooter", "0F1E2D3C4B5A", 0);
            p.mac = "C0:FF:EE:00:00:0A"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
        });
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch");
        settle();
        main(() -> require(activity.service().setSimulation(true), "simulation"));
        settle();
        await(() -> activity.service().ble.ready(), "simulation ready");
        home();
    }

    // ---- The lock switch ---------------------------------------------------------------------------------------------

    /** The slow scooter: no spinner in the first second, a spinner after it, none once confirmed; one announcement. */
    private void lockTimings() throws Exception {
        delay(2500);
        Boolean before = locked();
        int heard = announcements.get();
        List<long[]> frames = sampleLock(this::tapLockNow, 3300);
        Boolean after = locked();
        require(before != null && after != null && after == !before, "The slow lock change lands: " + before + " -> " + after);
        judge(frames, 2500, "lock");
        shot(shots + "-lock-after");
        await(() -> announcements.get() > heard, "TalkBack hears the wait");
        SystemClock.sleep(500);
        require(announcements.get() == heard + 1, "TalkBack hears it once, not " + (announcements.get() - heard) + " times");
        require(!lockSpinning(), "No spinner after the change landed");
    }

    /** A quick scooter (under a second): the spinner never shows. */
    private void lockQuickConfirm() throws Exception {
        delay(400);
        Boolean before = locked();
        int heard = announcements.get();
        List<long[]> frames = sampleLock(this::tapLockNow, 1600);
        for (long[] f : frames) require(f[1] == 0, "A quick confirmation showed the spinner at " + f[0] + " ms");
        require(locked() == !before, "The quick change lands");
        require(announcements.get() == heard, "Nothing to announce for a quick change");
        note("quick confirm: " + frames.size() + " frames, none spinning");
    }

    /** A second tap while the first is in flight changes nothing (LockTapQueue: one command at a time; the readback settles it). */
    private void lockSecondTap() throws Exception {
        delay(2500);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1300);
        require(lockSpinning(), "Spinning before the second tap");
        tapLock();
        settle();
        require(lockSpinning() && "lock".equals(pending()), "The second tap leaves the first in flight, spinning");
        await(() -> locked() == !before && !lockSpinning(), "The first tap's change lands and the spinner stops");
        SystemClock.sleep(600);
        require(locked() == !before, "The second tap sent nothing afterwards");
    }

    /** Never confirmed: at the existing timeout the spinner stops and the switch shows the old (read-back) state. */
    private void lockTimeout() throws Exception {
        delay(-1);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1300);
        require(lockSpinning(), "Spinning while the scooter doesn't answer");
        require(Boolean.valueOf(!before).equals(lockShown()), "The switch shows the asked side while waiting");
        shot(shots + "-lock-timeout-waiting");
        await(() -> pending() == null, "The change times out", 9000);
        settle();
        require(!lockSpinning(), "The timeout stops the spinner");
        require(locked() == before && Boolean.valueOf(before).equals(lockShown()), "The switch is back on the scooter's state");
        require(!(Boolean) call(field(activity, "lockQueue"), "waiting"), "Nothing left waiting");
        String result = String.valueOf(activity.service().ble.commandResults().get("lock"));
        require(result.contains("not confirmed"), "The existing failure result: " + result);
        delay(0);
    }

    /** A disconnect mid-wait: the spinner stops at once, and the reconnect doesn't bring it back. */
    private void lockDisconnect() throws Exception {
        delay(4000);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1300);
        require(lockSpinning(), "Spinning when the link drops");
        main(() -> activity.service().ble.disconnect());
        settle();
        require(!lockSpinning() && pending() == null, "The drop stops the spinner and the wait");
        main(() -> activity.service().ble.connectLast());
        await(() -> activity.service().ble.ready(), "simulator reconnects");
        settle();
        SystemClock.sleep(4000);
        main(() -> activity.redraw());
        settle();
        require(!lockSpinning(), "Not stuck spinning after the reconnect");
        require(locked() == before, "The dropped change never landed");
        delay(0);
    }

    /** Fold or unfold mid-wait (a new window size): the spinner carries on and stops on the confirmation. */
    private void lockFold() throws Exception {
        String size = shell("wm size").trim();
        boolean overridden = size.contains("Override size");
        String original = overridden ? size.substring(size.lastIndexOf(':') + 1).trim() : null;
        delay(3500);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1300);
        require(lockSpinning(), "Spinning before the fold change");
        try {
            shell("wm size 2074x2153");
            SystemClock.sleep(900);
            settle();
            home();
            require(lockSpinning(), "Still spinning after the size change (the wait is " + pending() + ")");
            await(() -> locked() == !before && !lockSpinning(), "Lands and stops after the size change");
        } finally {
            shell(original != null ? "wm size " + original : "wm size reset");
            SystemClock.sleep(900);
            settle();
        }
        home();
        require(!lockSpinning(), "Not spinning after folding back");
    }

    /** An in-app theme change mid-wait rebuilds the switch: the new one keeps the timing and stops on the confirmation. */
    private void lockThemeChange() throws Exception {
        delay(3500);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1300);
        String other = "dark".equals(theme) ? "light" : "dark";
        require(prefs().edit().putString("theme", other).commit(), "theme");
        SystemClock.sleep(700);
        settle();
        home();
        require(lockSpinning(), "The rebuilt switch spins at once (the wait is past its second)");
        await(() -> locked() == !before && !lockSpinning(), "Lands and stops after the theme change");
        require(prefs().edit().putString("theme", theme).commit(), "theme back");
        SystemClock.sleep(700);
        settle();
        home();
        require(!lockSpinning(), "Not spinning after the theme goes back");
    }

    /** A recreated activity (the system theme following Android's dark mode) mid-wait: never stuck spinning. */
    private void lockRecreated() throws Exception {
        delay(3500);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1300);
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        main(() -> activity.recreate());
        Activity next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "recreated");
        activity = (MainActivity) next;
        await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "recreated launch");
        settle();
        home();
        require(lockSpinning(), "The recreated switch spins for the write still in flight");
        await(() -> locked() == !before && !lockSpinning(), "Lands and stops after the recreate");
        delay(0);
    }

    // ---- A controls row ----------------------------------------------------------------------------------------------

    private void rowTimings() throws Exception {
        delay(2500);
        openLights();
        Object row = headlightRow();
        Integer before = feature("headlight");
        require(before != null, "headlight reads");
        int heard = announcements.get();
        View spinner = (View) field(row, "spinner"), toggle = (View) field(row, "toggle");
        List<long[]> frames = new ArrayList<>();
        android.widget.Switch shown = (android.widget.Switch) toggle;
        sample(toggle::performClick, 3300, frames, () -> spinner.getVisibility() == View.VISIBLE && toggle.getVisibility() != View.VISIBLE,
                () -> toggle.getVisibility() == View.VISIBLE ? (shown.isChecked() ? 1 : 0) : -1);
        require(Integer.valueOf(1 - before).equals(feature("headlight")), "The slow headlight change lands");
        judge(frames, 2500, "headlight row");
        // The first second shows the tapped side, never the old one (the tap must not look refused).
        int early = 0;
        for (long[] f : frames) if (f[0] < DELAY - 50 && f[0] > 0) {
            early++;
            require(f[2] == 1 - before, "headlight row: the switch shows " + (f[2] == -1 ? "nothing" : f[2] == before ? "the old side" : "?") + " at " + f[0] + " ms");
        }
        require(early > 0, "headlight row: frames in the first second");
        note("headlight row: the tapped side in all " + early + " frames of the first second");
        require(spinner.getVisibility() != View.VISIBLE && toggle.getVisibility() == View.VISIBLE, "The switch is back, the spinner gone");
        await(() -> announcements.get() > heard, "TalkBack hears the row's wait");
        SystemClock.sleep(500);
        require(announcements.get() == heard + 1, "Once for the row too");
        // Put it back quickly.
        delay(0);
        main(() -> toggle.performClick());
        settle();
        require(before.equals(feature("headlight")), "headlight restored");
    }

    private void rowTimeout() throws Exception {
        delay(-1);
        Object row = headlightRow();
        Integer before = feature("headlight");
        View spinner = (View) field(row, "spinner"), toggle = (View) field(row, "toggle");
        main(() -> toggle.performClick());
        SystemClock.sleep(500);
        require(spinner.getVisibility() != View.VISIBLE, "No row spinner under a second");
        SystemClock.sleep(800);
        require(spinner.getVisibility() == View.VISIBLE, "The row spinner after a second");
        await(() -> pending() == null, "The row's change times out", 9000);
        settle();
        require(spinner.getVisibility() != View.VISIBLE && toggle.getVisibility() == View.VISIBLE, "The timeout puts the switch back");
        require(before.equals(feature("headlight")) && ((android.widget.Switch) toggle).isChecked() == (before == 1), "Showing the scooter's own value");
        View chip = (View) field(row, "result");
        require(chip.getVisibility() == View.VISIBLE, "The existing failure chip shows");
        delay(0);
        home();
    }

    /** Animations off: the spinner still shows after a second, as a still picture, and nothing redraws for ever. */
    private void stillWithAnimationsOff() throws Exception {
        previousScale = shell("settings get global animator_duration_scale").trim();
        shell("settings put global animator_duration_scale 0");
        SystemClock.sleep(500);
        delay(3000);
        Boolean before = locked();
        tapLock();
        SystemClock.sleep(1400);
        boolean[] motion = new boolean[1];
        main(() -> motion[0] = android.animation.ValueAnimator.areAnimatorsEnabled());
        require(!motion[0], "animations off");
        require(lockSpinning(), "The spinner shows with animations off");
        long idleFrom = SystemClock.uptimeMillis();
        waitForIdleSync();
        require(SystemClock.uptimeMillis() - idleFrom < 3000, "Still: the main thread goes idle while it shows");
        shot(shots + "-lock-still");
        await(() -> locked() == !before && !lockSpinning(), "Lands and stops with animations off");
        delay(0);
    }

    // ---- Pictures ----------------------------------------------------------------------------------------------------

    private void render() throws Exception {
        // Slow, not never: a timeout's toast would sit over the next picture.
        delay(7000);
        tapLock();
        SystemClock.sleep(1500);
        require(lockSpinning(), "lock spinning for the picture");
        shot(shots + "-lock");
        await(() -> pending() == null, "picture's lock change ends", 12000);
        settle();
        openLights();
        Object row = headlightRow();
        View spinner = (View) field(row, "spinner"), toggle = (View) field(row, "toggle");
        main(() -> toggle.performClick());
        SystemClock.sleep(1500);
        require(spinner.getVisibility() == View.VISIBLE, "row spinning for the picture");
        main(() -> ((View) field(row, "card")).requestRectangleOnScreen(new android.graphics.Rect(0, 0, 10, 10), true));
        SystemClock.sleep(300);
        shot(shots + "-row");
        await(() -> pending() == null, "picture's row change ends", 12000);
        delay(0);
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    /** Samples the lock switch on every drawn frame from the tap (run on the main thread): {ms since the tap, spinning 0/1}. */
    private List<long[]> sampleLock(Runnable tap, long forMs) throws Exception {
        List<long[]> frames = new ArrayList<>();
        sample(tap, forMs, frames, this::lockSpinningNow, () -> 0);
        return frames;
    }

    /** Each frame: {ms since the tap, spinning 0/1, side(): what else the frame shows}. */
    private void sample(Runnable tap, long forMs, List<long[]> frames, BooleanSupplier spinning, java.util.function.IntSupplier side) throws Exception {
        boolean[] run = {true};
        long[] start = new long[1];
        Choreographer.FrameCallback[] tick = new Choreographer.FrameCallback[1];
        main(() -> {
            tick[0] = t -> {
                if (!run[0]) return;
                frames.add(new long[]{SystemClock.elapsedRealtime() - start[0], spinning.getAsBoolean() ? 1 : 0, side.getAsInt()});
                // Frames keep coming while nothing animates (the validation holds endless motion still).
                activity.getWindow().getDecorView().invalidate();
                Choreographer.getInstance().postFrameCallback(tick[0]);
            };
            start[0] = SystemClock.elapsedRealtime();
            tap.run();
            Choreographer.getInstance().postFrameCallback(tick[0]);
        });
        SystemClock.sleep(forMs);
        main(() -> run[0] = false);
    }

    /**
     * No spinning frame before the second, spinning frames from just after it until the confirmation, none after it.
     * Frames within 150 ms of each edge are not judged (a frame's own timing).
     */
    private void judge(List<long[]> frames, long confirmAt, String what) {
        require(frames.size() > 20, what + ": too few frames " + frames.size());
        int early = 0, middle = 0, spinning = 0, late = 0;
        for (long[] f : frames) {
            if (f[0] < DELAY - 50) { early++; require(f[1] == 0, what + ": spinning at " + f[0] + " ms, under a second"); }
            else if (f[0] > DELAY + 150 && f[0] < confirmAt - 50) { middle++; if (f[1] == 1) spinning++; }
            else if (f[0] > confirmAt + 400) { late++; require(f[1] == 0, what + ": still spinning at " + f[0] + " ms, after the confirmation"); }
        }
        require(middle > 0 && spinning == middle, what + ": spinning in " + spinning + " of " + middle + " frames between 1 s and the confirmation");
        require(late > 0, what + ": no frames after the confirmation");
        note(what + ": " + early + " frames under 1 s without a spinner, " + spinning + " spinning, " + late + " after the confirmation without");
    }

    private void listenForAnnouncements() {
        UiAutomation automation = getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        if (info != null) { info.eventTypes |= AccessibilityEvent.TYPE_ANNOUNCEMENT; automation.setServiceInfo(info); }
        String words = waitingWords();
        automation.setOnAccessibilityEventListener(event -> {
            if (event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT) return;
            for (CharSequence text : event.getText()) if (text != null && text.toString().equals(words)) { announcements.incrementAndGet(); return; }
        });
    }

    private String waitingWords() {
        Context app = getTargetContext();
        int id = app.getResources().getIdentifier("main_speed_confirming", "string", app.getPackageName());
        require(id != 0, "string main_speed_confirming");
        return app.getString(id);
    }

    private void tapLock() { main(this::tapLockNow); }
    /** On the main thread (sample's tap). */
    private void tapLockNow() { ((View) field(activity, "lockControl")).performClick(); }
    private boolean lockSpinning() { boolean[] v = new boolean[1]; main(() -> v[0] = lockSpinningNow()); return v[0]; }
    private boolean lockSpinningNow() {
        Object control = field(activity, "lockControl");
        if (control == null) return false;
        Object look = field(control, "waitLook");
        return (Boolean) field(control, "spinning") && (Boolean) call(look, "showing", SystemClock.elapsedRealtime());
    }
    private Boolean lockShown() { Object[] v = new Object[1]; main(() -> v[0] = call(field(activity, "lockControl"), "shown")); return (Boolean) v[0]; }
    private Boolean locked() { Boolean[] v = new Boolean[1]; main(() -> v[0] = activity.service().ble.locked()); return v[0]; }
    private String pending() { String[] v = new String[1]; main(() -> v[0] = activity.service().ble.pending()); return v[0]; }
    private Integer feature(String key) { Integer[] v = new Integer[1]; main(() -> v[0] = activity.service().ble.featureValue(key)); return v[0]; }

    private void home() throws Exception {
        main(() -> activity.open(HubPages.HOME));
        settle();
        await(() -> { Object[] c = new Object[1]; main(() -> c[0] = field(activity, "lockControl")); return c[0] != null && ((View) c[0]).isShown(); }, "Home's lock switch");
    }

    private void openLights() throws Exception {
        main(() -> activity.open(HubPages.LIGHTS));
        settle();
        await(() -> { Object[] r = new Object[1]; main(() -> r[0] = headlightRowNow()); return r[0] != null; }, "Lights page's Headlight row");
    }

    private Object headlightRow() { Object[] r = new Object[1]; main(() -> r[0] = headlightRowNow()); require(r[0] != null, "Headlight row"); return r[0]; }
    private Object headlightRowNow() {
        Object screen = field(activity, "screen");
        if (screen == null || !"ControlsScreen".equals(screen.getClass().getSimpleName())) return null;
        for (Object row : (List<?>) field(screen, "rows")) {
            Object f = field(row, "feature");
            if ("headlight".equals(field(f, "key")) && ((View) field(row, "card")).isShown()) return row;
        }
        return null;
    }

    /** SimulatedConfirmation.delayMs, test-only: 0 at once, -1 never. */
    private static void delay(long ms) {
        try {
            Field f = Class.forName("com.jjricks.volt.SimulatedConfirmation").getDeclaredField("delayMs");
            f.setAccessible(true);
            f.setLong(null, ms);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "screenshot");
        File image = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        TextCensus.write(this, image);
    }

    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = reader.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }

    private void note(String text) { notes.add(text); android.util.Log.i("ControlPending", text); }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m) { await(c, m, 15000); }
    private void await(BooleanSupplier c, String m, long ms) { long end = SystemClock.uptimeMillis() + ms; while (!c.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30); require(c.getAsBoolean(), m); }
    private static Field find(Class<?> t, String name) { while (t != null) { try { return t.getDeclaredField(name); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
    private static Object field(Object target, String name) { try { Field f = find(target.getClass(), name); f.setAccessible(true); return f.get(target); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    private static Object call(Object target, String name, Object... args) {
        try {
            for (Class<?> t = target.getClass(); t != null; t = t.getSuperclass())
                for (Method m : t.getDeclaredMethods())
                    if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(target, args); }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        throw new IllegalStateException(name);
    }
}
