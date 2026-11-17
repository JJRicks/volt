package com.jjricks.volt.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Fresh disposable emulator only. Home's change between parked and charging ("Plug in", ChargeTransition), watched frame
 * by frame (a Choreographer callback reads every part's state on the main thread at each frame), on the simulator's UT5
 * MAX: Clean Home (one pane at 344 dp, the scooter pane of two at 725 dp) and Widgets Home. It fails on a pop:
 *
 * <ul>
 * <li>a change that plays no in-between frames, or a part that jumps between two frames close together (an opacity by
 *     more than 0.6, a place, size or height by more than 60% of its whole travel), the number included where the card's
 *     and the charging view's hand over, and a view that disappears while still showing;</li>
 * <li>the charge pulse running before the arrival ends, or not running after it;</li>
 * <li>a quick unplug mid-arrival that does not turn back from where it is (its progress rises again: a double play);</li>
 * <li>any motion with Android's animations off (scale 0), or on recreation (Home rebuilt while charging or parked);</li>
 * <li>a resting view that is not exactly as Home shows it without the change (positions, offsets, opacity, the card's
 *     photo drawn: the photo once vanished after a change because its drawable kept a zero alpha).</li>
 * </ul>
 *
 * Arguments: size_label (for the evidence's names). The step's window size decides one pane or two.
 */
public final class ChargeTransitionValidation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private ScooterService service;
    private int assertions;
    private String scaleBefore = "";
    private final ArrayList<String> checks = new ArrayList<>();
    private final StringBuilder notes = new StringBuilder();
    private float density = 1;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            scaleBefore = shell("settings get global animator_duration_scale").trim();
            setScale("1");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile saved = profiles.create("UT5 MAX", "000000000000", 0);
                saved.serial = "X2538-SYNTHETIC"; saved.lastConnected = 1767225600000L; profiles.update(saved);
                OnboardingScreen.markDone(getTargetContext());
            });
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", "dark").putString("units", "km")
                    .putString("home_style", "clean").putBoolean("home_style_chosen", true).putBoolean("home_whats_new_shown", true)
                    .commit(), "Could not seed preferences");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "App did not launch", 10000);
            service = activity.service();
            density = activity.getResources().getDisplayMetrics().density;
            main(() -> { if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
                service.ble.setSimulationModel(""); service.ble.setSimulationCharging(false); service.ble.tickSimulation(); });
            await(() -> service.ble.ready(), "The simulated UT5 MAX is not ready", 10000);
            main(() -> invoke(activity, "select", new Class<?>[] {String.class}, "home"));
            await(() -> clean() != null && moment(clean()) == 0, "Clean Home did not show parked", 10000);
            SystemClock.sleep(1500);
            clean();
            note("Clean Home: " + (twoPane() ? "the scooter pane of two" : "one pane"));

            // One change each way first, unwatched: the first one bakes the charging picture's bitmaps, a one-off cost
            // that a slow emulator pays in dropped frames (the phone pays it once per Home too).
            warmUp(() -> moment(clean()) == 1 && !cleanChanging(), () -> moment(clean()) == 0 && !cleanChanging());
            String parkedRef = parkedState(), chargingRef;
            // Parked to charging.
            List<float[]> in = record(() -> charging(true), () -> !cleanChanging() && moment(clean()) == 1, 4000, 1300);
            cleanChange("Clean parked to charging", in, true);
            String chargingAfter = chargingState();
            // Recreated while charging: Home comes back at rest, with no motion.
            List<float[]> recreated = recreate(() -> moment(clean()) == 1);
            noMotion("Clean recreated while charging", recreated, true);
            chargingRef = chargingState();
            same("Clean charging at rest after the change", chargingAfter, chargingRef);
            // Charging to parked.
            List<float[]> out = record(() -> charging(false), () -> !cleanChanging() && moment(clean()) == 0, 4000, 600);
            cleanChange("Clean charging to parked", out, false);
            same("Clean parked at rest after the change", parkedState(), parkedRef);
            // A quick unplug mid-arrival turns back from where it is.
            List<float[]> back = record(() -> charging(true), () -> cleanChanging() && progress() >= .35f, 3000, 0);
            back.addAll(record(() -> charging(false), () -> !cleanChanging() && moment(clean()) == 0, 4000, 600));
            cleanReversal("Clean replugged mid-arrival", back);
            same("Clean parked at rest after the reversal", parkedState(), parkedRef);
            // Recreated while parked.
            noMotion("Clean recreated while parked", recreate(() -> moment(clean()) == 0), false);
            same("Clean parked at rest after recreation", parkedState(), parkedRef);
            // Animations off: at once, both ways.
            setScale("0");
            await(() -> !android.animation.ValueAnimator.areAnimatorsEnabled(), "Animations did not turn off", 8000);
            noMotion("Clean parked to charging with animations off", record(() -> charging(true), () -> moment(clean()) == 1, 3000, 600), true);
            noMotion("Clean charging to parked with animations off", record(() -> charging(false), () -> moment(clean()) == 0, 3000, 600), false);
            setScale("1");
            await(() -> android.animation.ValueAnimator.areAnimatorsEnabled(), "Animations did not turn back on", 8000);
            SystemClock.sleep(500);

            // Widgets Home's takeover.
            require(settings().edit().putString("home_style", "widgets").commit(), "Could not switch to Widgets");
            await(() -> hero() != null && field(screen(), "chargingHero") != null && !(screen().getClass().getSimpleName().contains("Clean")), "Widgets Home did not show", 10000);
            SystemClock.sleep(1500);
            warmUp(() -> heroShown() && !widgetsChanging(), () -> !heroShown() && !widgetsChanging());
            List<float[]> wIn = record(() -> charging(true), () -> !widgetsChanging() && heroShown(), 4000, 1300);
            widgetsChange("Widgets parked to charging", wIn, true);
            String wCharging = widgetsState();
            List<float[]> wOut = record(() -> charging(false), () -> !widgetsChanging() && !heroShown(), 4000, 600);
            widgetsChange("Widgets charging to parked", wOut, false);
            List<float[]> wBack = record(() -> charging(true), () -> widgetsChanging() && revealProgress() >= .35f, 3000, 0);
            wBack.addAll(record(() -> charging(false), () -> !widgetsChanging() && !heroShown(), 4000, 600));
            widgetsReversal("Widgets replugged mid-opening", wBack);
            main(() -> { service.ble.setSimulationCharging(true); activity.redraw(); });
            await(() -> heroShown() && !widgetsChanging(), "Widgets charging did not settle", 5000);
            SystemClock.sleep(1300);
            List<float[]> wRecreated = recreate(() -> hero() != null && heroShown());
            noWidgetsMotion("Widgets recreated while charging", wRecreated);
            same("Widgets charging at rest after the change", wCharging, widgetsState());
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
        } finally {
            try { writeNotes(); } catch (Throwable ignored) {}
            try { shell(scaleBefore.isEmpty() || scaleBefore.equals("null") ? "settings put global animator_duration_scale 1" : "settings put global animator_duration_scale " + scaleBefore); } catch (Throwable ignored) {}
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Clean's checks -------------------------------------------------------------------------------------------

    /** Sample columns (Clean). */
    private static final int T = 0, ACTIVE = 1, P = 2, CARD = 3, PHOTO = 4, IMAGE = 5, IMAGE_SCALE = 6, IMAGE_X = 7, IMAGE_Y = 8,
            NUM_X = 9, NUM_Y = 10, NUM_SIZE = 11, LINE = 12, ROWS = 13, ROWS_Y = 14, PULSE = 15, GLOW = 16, HERO_SHOWN = 17, CARD_SHOWN = 18;
    private static final int[] OPACITIES = {CARD, PHOTO, IMAGE, LINE, ROWS, GLOW};
    private static final int[] PLACES = {IMAGE_SCALE, IMAGE_X, IMAGE_Y, NUM_X, NUM_Y, NUM_SIZE, ROWS_Y};
    private static final String[] NAMES = {"t", "active", "p", "card", "photo", "image", "image scale", "image x", "image y",
            "number x", "number y", "number size", "line", "readings", "readings y", "pulse", "glow", "hero shown", "card shown"};

    private void cleanChange(String what, List<float[]> frames, boolean in) {
        int between = 0;
        for (float[] f : frames) if (f[ACTIVE] > 0 && f[P] > 0 && f[P] < 1) between++;
        require(between >= (in ? 6 : 4), what + ": only " + between + " frames in between (a cut, not a change)");
        smooth(what, frames, OPACITIES, PLACES, NAMES);
        number(what, frames);
        float last = -1;
        for (float[] f : frames) {
            if (f[ACTIVE] > 0) {
                require(last < 0 || (in ? f[P] >= last - 1e-4f : f[P] <= last + 1e-4f), what + ": its progress turned back (" + last + " to " + f[P] + ")");
                last = f[P];
                if (in) require(f[PULSE] == 0, what + ": the charge pulse ran before the arrival ended (p " + f[P] + ")");
            }
        }
        if (in) require(frames.get(frames.size() - 1)[PULSE] > 0, what + ": the charge pulse did not start after the arrival");
        float[] end = frames.get(frames.size() - 1);
        require(in ? end[HERO_SHOWN] > 0 && end[CARD_SHOWN] == 0 : end[HERO_SHOWN] == 0 && end[CARD_SHOWN] > 0, what + ": not at rest at the end");
        note(what + ": " + between + " frames in between over " + Math.round(span(frames)) + " ms");
        assertions++; checks.add(what);
    }

    private void cleanReversal(String what, List<float[]> frames) {
        smooth(what, frames, OPACITIES, PLACES, NAMES);
        number(what, frames);
        float top = -1, last = -1;
        boolean turned = false;
        int after = 0;
        for (float[] f : frames) {
            if (f[ACTIVE] == 0) continue;
            if (last >= 0 && f[P] < last - 1e-4f) turned = true;
            if (turned) {
                require(f[P] <= last + 1e-4f, what + ": its progress rose again after turning back (a double play): " + last + " to " + f[P]);
                if (f[P] > 0) after++;
                require(f[PULSE] == 0, what + ": the pulse started while turned back mid-arrival");
            }
            top = Math.max(top, f[P]);
            last = f[P];
        }
        require(turned && top > .2f && top < 1, what + ": it did not turn back mid-arrival (highest progress " + top + ")");
        require(after >= 3, what + ": only " + after + " frames on the way back");
        note(what + ": turned back at p " + String.format(Locale.US, "%.2f", top) + ", " + after + " frames back");
        assertions++; checks.add(what);
    }

    /** A recreated Home or a change with animations off: never a frame between the two. */
    private void noMotion(String what, List<float[]> frames, boolean charging) {
        require(!frames.isEmpty(), what + ": no frames seen");
        for (float[] f : frames) {
            require(f[ACTIVE] == 0, what + ": a change ran (p " + f[P] + ")");
            if (f[HERO_SHOWN] > 0) require(f[IMAGE] == 1 && f[LINE] == 1 && f[ROWS] == 1, what + ": the charging view was part-way in: " + row(f, NAMES));
            if (f[CARD_SHOWN] > 0) require(f[CARD] == 1, what + ": the card was part-way in: " + row(f, NAMES));
        }
        float[] end = frames.get(frames.size() - 1);
        require(charging ? end[HERO_SHOWN] > 0 && end[CARD_SHOWN] == 0 : end[CARD_SHOWN] > 0 && end[HERO_SHOWN] == 0, what + ": wrong moment at the end");
        note(what + ": " + frames.size() + " frames, all at rest");
        assertions++; checks.add(what);
    }

    /** The number the eye follows (the card's or the charging view's, whichever shows) never jumps, at the handover too. */
    private void number(String what, List<float[]> frames) {
        float travel = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] f : frames) if (!Float.isNaN(f[NUM_X])) { minX = Math.min(minX, f[NUM_X]); maxX = Math.max(maxX, f[NUM_X]); minY = Math.min(minY, f[NUM_Y]); maxY = Math.max(maxY, f[NUM_Y]); }
        travel = Math.max(maxX - minX, maxY - minY);
        for (int i = 1; i < frames.size(); i++) {
            float[] a = frames.get(i - 1), b = frames.get(i);
            if (Float.isNaN(a[NUM_X]) || Float.isNaN(b[NUM_X]) || b[T] - a[T] > 25) continue;
            if (Math.abs(b[P] - a[P]) > Math.max(.06f, .15f * (b[T] - a[T]) / 25f)) continue;
            float jump = Math.max(Math.abs(b[NUM_X] - a[NUM_X]), Math.abs(b[NUM_Y] - a[NUM_Y]));
            require(jump <= Math.max(4 * density, .6f * travel), what + ": the number jumped " + Math.round(jump) + " px in one frame: " + row(a, NAMES) + " -> " + row(b, NAMES));
        }
    }

    // ---- Widgets' checks ------------------------------------------------------------------------------------------

    private static final int W_T = 0, W_ACTIVE = 1, W_P = 2, W_HEIGHT = 3, W_ALPHA = 4, W_STAGE = 5, W_ROWS = 6, W_LINE = 7, W_PULSE = 8, W_SHOWN = 9, W_ID = 10;
    private static final String[] W_NAMES = {"t", "active", "p", "height", "alpha", "stage", "readings", "line", "pulse", "shown", "hero"};

    private void sameHero(String what, List<float[]> frames) {
        for (float[] f : frames) require(f[W_ID] == frames.get(0)[W_ID], what + ": Widgets Home was rebuilt during the change");
    }

    private void widgetsChange(String what, List<float[]> frames, boolean in) {
        sameHero(what, frames);
        int between = 0;
        for (float[] f : frames) if (f[W_ACTIVE] > 0 && f[W_P] > 0 && f[W_P] < 1) between++;
        require(between >= (in ? 6 : 4), what + ": only " + between + " frames in between");
        smooth(what, frames, new int[] {W_ALPHA, W_STAGE, W_ROWS, W_LINE}, new int[] {W_HEIGHT}, W_NAMES);
        for (float[] f : frames) if (in && f[W_ACTIVE] > 0) require(f[W_PULSE] == 0, what + ": the charge pulse ran before the takeover had arrived");
        if (in) require(frames.get(frames.size() - 1)[W_PULSE] > 0, what + ": the charge pulse did not start after the arrival");
        note(what + ": " + between + " frames in between over " + Math.round(span(frames)) + " ms");
        assertions++; checks.add(what);
    }

    private void widgetsReversal(String what, List<float[]> frames) {
        sameHero(what, frames);
        smooth(what, frames, new int[] {W_ALPHA, W_STAGE, W_ROWS, W_LINE}, new int[] {W_HEIGHT}, W_NAMES);
        float top = -1, last = -1;
        boolean turned = false;
        for (float[] f : frames) {
            if (f[W_ACTIVE] == 0) continue;
            if (last >= 0 && f[W_P] < last - 1e-4f) turned = true;
            if (turned) require(f[W_P] <= last + 1e-4f, what + ": its progress rose again after turning back: " + last + " to " + f[W_P]);
            top = Math.max(top, f[W_P]); last = f[W_P];
        }
        require(turned && top > .2f && top < 1, what + ": it did not turn back mid-opening (highest " + top + ")");
        note(what + ": turned back at p " + String.format(Locale.US, "%.2f", top));
        assertions++; checks.add(what);
    }

    private void noWidgetsMotion(String what, List<float[]> frames) {
        require(!frames.isEmpty(), what + ": no frames seen");
        for (float[] f : frames) {
            require(f[W_ACTIVE] == 0, what + ": the takeover animated (p " + f[W_P] + ")");
            if (f[W_SHOWN] > 0) require(f[W_ALPHA] == 1 && f[W_STAGE] == 1 && f[W_ROWS] == 1, what + ": part-way in: " + row(f, W_NAMES));
        }
        note(what + ": " + frames.size() + " frames, all at rest");
        assertions++; checks.add(what);
    }

    // ---- The pop rule ---------------------------------------------------------------------------------------------

    /**
     * Between two frames at most 25 ms apart (a frame or two at 60 Hz), no opacity moves by more than 0.6 and no place,
     * size or height by more than 60% of its whole travel in this change (at least 4 dp, so a still part never fails on
     * a pixel); a part invisible in both frames (opacity 0.05 or less) may move. A view that goes is gone through its
     * opacity column, so it cannot vanish while it shows. Two frames are compared only when the change's own progress
     * moved about a frame's worth between them (at most 0.06, or 0.15 over 25 ms): a loaded emulator sometimes advances
     * the animation by several frames between two frame callbacks, which is a dropped frame, not a pop in what the
     * change draws for its progress. Every part being a function of the progress, that is what is judged.
     */
    private void smooth(String what, List<float[]> frames, int[] opacities, int[] places, String[] names) {
        require(frames.size() >= 3, what + ": only " + frames.size() + " frames seen");
        float[] min = new float[names.length], max = new float[names.length];
        java.util.Arrays.fill(min, Float.MAX_VALUE); java.util.Arrays.fill(max, -Float.MAX_VALUE);
        for (float[] f : frames) for (int c : places) if (!Float.isNaN(f[c])) { min[c] = Math.min(min[c], f[c]); max[c] = Math.max(max[c], f[c]); }
        int pairs = 0;
        for (int i = 1; i < frames.size(); i++) {
            float[] a = frames.get(i - 1), b = frames.get(i);
            if (b[0] - a[0] > 25) continue;
            if (Math.abs(b[2] - a[2]) > Math.max(.06f, .15f * (b[0] - a[0]) / 25f)) continue; // column 2: the progress
            pairs++;
            for (int c : opacities) require(Math.abs(b[c] - a[c]) <= .6f, what + ": " + names[c] + " jumped from " + fmt(a[c]) + " to " + fmt(b[c]) + " in one frame: " + row(a, names) + " -> " + row(b, names));
            for (int c : places) {
                if (Float.isNaN(a[c]) || Float.isNaN(b[c])) continue;
                // A part that shows in neither frame may move: nothing is seen to jump.
                int seen = names == NAMES ? (c == IMAGE_SCALE || c == IMAGE_X || c == IMAGE_Y ? IMAGE : c == ROWS_Y ? ROWS : -1) : -1;
                if (seen >= 0 && a[seen] <= .05f && b[seen] <= .05f) continue;
                float limit = Math.max(4 * density, .6f * (max[c] - min[c]));
                if (c == IMAGE_SCALE && names == NAMES) limit = Math.max(.05f, .6f * (max[c] - min[c]));
                require(Math.abs(b[c] - a[c]) <= limit, what + ": " + names[c] + " jumped from " + fmt(a[c]) + " to " + fmt(b[c]) + " in one frame: " + row(a, names) + " -> " + row(b, names));
            }
        }
        require(pairs >= 3, what + ": too few frames close together to judge (" + pairs + ")");
    }

    private void warmUp(BooleanSupplier charged, BooleanSupplier parked) {
        main(() -> charging(true));
        await(charged, "Warm-up: charging did not settle", 6000);
        SystemClock.sleep(900);
        main(() -> charging(false));
        await(parked, "Warm-up: parked did not settle", 6000);
        SystemClock.sleep(900);
    }

    // ---- Recording ------------------------------------------------------------------------------------------------

    /**
     * Runs {@code change} on the main thread and reads every frame from then until {@code done} holds (then {@code after}
     * ms more), up to {@code limit} ms. Clean's or Widgets' columns, whichever Home shows.
     */
    private List<float[]> record(Runnable change, BooleanSupplier done, long limit, long after) {
        ArrayList<float[]> frames = new ArrayList<>();
        boolean[] on = {true};
        Choreographer.FrameCallback[] tick = {null};
        main(() -> {
            tick[0] = time -> {
                if (!on[0]) return;
                float[] f = sample(time / 1e6f);
                if (f != null) synchronized (frames) { frames.add(f); }
                Choreographer.getInstance().postFrameCallback(tick[0]);
            };
            Choreographer.getInstance().postFrameCallback(tick[0]);
            change.run();
        });
        long end = SystemClock.uptimeMillis() + limit;
        boolean[] ok = {false};
        while (SystemClock.uptimeMillis() < end) {
            main(() -> ok[0] = done.getAsBoolean());
            if (ok[0]) break;
            SystemClock.sleep(16);
        }
        require(ok[0], "The change did not settle within " + limit + " ms");
        SystemClock.sleep(after);
        main(() -> on[0] = false);
        synchronized (frames) { return new ArrayList<>(frames); }
    }

    /** Recreates the activity and reads its frames from the new Home's first ones (recording starts before it shows). */
    private List<float[]> recreate(BooleanSupplier shown) {
        ArrayList<float[]> frames = new ArrayList<>();
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity old = activity;
        main(old::recreate);
        Activity next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        require(next instanceof MainActivity, "The activity was not recreated");
        activity = (MainActivity) next;
        boolean[] on = {true};
        Choreographer.FrameCallback[] tick = {null};
        main(() -> {
            tick[0] = time -> {
                if (!on[0]) return;
                float[] f = sample(time / 1e6f);
                if (f != null) synchronized (frames) { frames.add(f); }
                Choreographer.getInstance().postFrameCallback(tick[0]);
            };
            Choreographer.getInstance().postFrameCallback(tick[0]);
        });
        await(() -> activity.service() != null && shown.getAsBoolean(), "Home did not come back after recreation", 10000);
        service = activity.service();
        SystemClock.sleep(1200);
        main(() -> on[0] = false);
        synchronized (frames) { return new ArrayList<>(frames); }
    }

    private float[] sample(float time) {
        Object home = home();
        if (home == null) return null;
        if (home.getClass().getSimpleName().equals("CleanHomeScreen")) return cleanSample(home, time);
        View hero = hero();
        if (hero == null) return null;
        float[] f = new float[W_NAMES.length];
        boolean shown = hero.getVisibility() == View.VISIBLE;
        f[W_T] = time;
        f[W_ACTIVE] = field(hero, "reveal") != null ? 1 : 0;
        f[W_P] = (Float) field(hero, "revealProgress");
        f[W_HEIGHT] = shown ? hero.getHeight() : 0;
        f[W_ALPHA] = shown ? hero.getAlpha() : 0;
        f[W_STAGE] = shown ? ((View) field(hero, "stage")).getAlpha() : 0;
        f[W_ROWS] = shown ? ((View) field(hero, "rows")).getAlpha() : 0;
        f[W_LINE] = shown ? ((View) field(hero, "chip")).getAlpha() : 0;
        f[W_PULSE] = field(hero, "pulse") != null ? 1 : 0;
        f[W_SHOWN] = shown ? 1 : 0;
        f[W_ID] = System.identityHashCode(hero);
        return f;
    }

    private float[] cleanSample(Object home, float time) {
        Object transition = field(home, "transition");
        View overview = (View) field(home, "overview"), hero = (View) field(home, "charging");
        Object parts = field(home, "parts");
        ImageView photo = (ImageView) invoke(parts, "parkedPhoto", new Class<?>[0]);
        TextView parkedPercent = (TextView) invoke(parts, "parkedPercent", new Class<?>[0]);
        TextView percent = (TextView) field(hero, "percent");
        View[] images = (View[]) invoke(hero, "images", new Class<?>[0]);
        boolean heroShown = hero.getVisibility() == View.VISIBLE, cardShown = overview.getVisibility() == View.VISIBLE;
        float[] f = new float[NAMES.length];
        f[T] = time;
        f[ACTIVE] = (Boolean) invoke(transition, "changing", new Class<?>[0]) ? 1 : 0;
        f[P] = (Float) invoke(transition, "progress", new Class<?>[0]);
        f[CARD] = cardShown ? overview.getAlpha() : 0;
        Object flight = field(transition, "flight");
        float flying = flight == null ? 0 : (Float) field(flight, "alpha");
        boolean photoDrawn = cardShown && photo != null && photo.getVisibility() == View.VISIBLE && photo.getClipBounds() == null;
        f[PHOTO] = Math.max(photoDrawn ? overview.getAlpha() : 0, f[ACTIVE] > 0 ? flying : 0);
        View image = images.length > 0 ? images[0] : null;
        f[IMAGE] = heroShown && image != null ? hero.getAlpha() * image.getAlpha() : 0;
        f[IMAGE_SCALE] = image == null || !heroShown ? Float.NaN : image.getScaleX();
        f[IMAGE_X] = image == null || !heroShown ? Float.NaN : image.getTranslationX();
        f[IMAGE_Y] = image == null || !heroShown ? Float.NaN : image.getTranslationY();
        // The number that shows: the charging view's (in flight or at rest) or the card's.
        float[] at = null;
        if (heroShown && percent.getVisibility() == View.VISIBLE && percent.getAlpha() >= .5f) at = numberAt(percent, true);
        else if (cardShown && parkedPercent != null && overview.getAlpha() * parkedPercent.getAlpha() >= .5f) at = numberAt(parkedPercent, false);
        f[NUM_X] = at == null ? Float.NaN : at[0];
        f[NUM_Y] = at == null ? Float.NaN : at[1];
        f[NUM_SIZE] = at == null ? Float.NaN : at[2];
        float line = 0;
        for (String name : new String[] {"chip", "chipShort"}) {
            View v = (View) field(hero, name);
            if (v != null && v.getVisibility() == View.VISIBLE) line = Math.max(line, v.getAlpha());
        }
        f[LINE] = heroShown ? line : 0;
        View rows = (View) field(hero, "rows");
        f[ROWS] = heroShown && rows.getVisibility() == View.VISIBLE ? rows.getAlpha() : 0;
        f[ROWS_Y] = heroShown ? rows.getTranslationY() : Float.NaN;
        f[PULSE] = field(hero, "pulse") != null ? 1 : 0;
        f[GLOW] = heroShown ? (Float) field(hero, "glowShown") : 0;
        f[HERO_SHOWN] = heroShown ? 1 : 0;
        f[CARD_SHOWN] = cardShown ? 1 : 0;
        return f;
    }

    /** The number's anchor (line left at the baseline) in the window, through its own scale and offset, and its size. */
    private static float[] numberAt(TextView text, boolean transformed) {
        android.text.Layout layout = text.getLayout();
        if (layout == null) return null;
        float[] point = {text.getTotalPaddingLeft() + layout.getLineLeft(0), text.getTotalPaddingTop() + layout.getLineBaseline(0)};
        if (transformed) { Matrix m = text.getMatrix(); m.mapPoints(point); }
        View parent = (View) text.getParent();
        int[] loc = new int[2];
        parent.getLocationInWindow(loc);
        return new float[] {loc[0] + text.getLeft() + point[0], loc[1] + text.getTop() + point[1], text.getTextSize() * (transformed ? text.getScaleX() : 1)};
    }

    // ---- Resting states -------------------------------------------------------------------------------------------

    /** Clean parked at rest: the card and its parts as laid out, the photo drawn, the charging view gone. */
    private String parkedState() {
        StringBuilder s = new StringBuilder();
        main(() -> {
            Object home = clean();
            View overview = (View) field(home, "overview"), hero = (View) field(home, "charging");
            Object parts = field(home, "parts");
            ImageView photo = (ImageView) invoke(parts, "parkedPhoto", new Class<?>[0]);
            TextView parkedPercent = (TextView) invoke(parts, "parkedPercent", new Class<?>[0]);
            s.append("card ").append(view(overview)).append(" hero visibility ").append(hero.getVisibility());
            s.append(" photo ").append(view(photo)).append(" clip ").append(photo.getClipBounds())
                    .append(" drawable alpha ").append(photo.getDrawable() == null ? "none" : photo.getDrawable().getAlpha());
            s.append(" number ").append(view(parkedPercent));
            s.append(" a11y ").append(overview.getImportantForAccessibility());
        });
        return s.toString();
    }

    /** Clean charging at rest: the charging view's parts as laid out (ChargingFit), with no offset, scale or fade left. */
    private String chargingState() {
        StringBuilder s = new StringBuilder();
        main(() -> {
            Object home = clean();
            View overview = (View) field(home, "overview"), hero = (View) field(home, "charging");
            s.append("card visibility ").append(overview.getVisibility()).append(" hero ").append(view(hero));
            for (String name : new String[] {"chip", "chipShort", "percent", "stage", "rows", "photo"}) {
                View v = (View) field(hero, name);
                if (v != null) s.append(' ').append(name).append(' ').append(view(v));
            }
            View[] images = (View[]) invoke(hero, "images", new Class<?>[0]);
            for (View v : images) s.append(" image ").append(view(v));
            s.append(" glow ").append(field(hero, "glowShown")).append(" hold ").append(field(hero, "holdPulse"));
            s.append(" a11y ").append(hero.getImportantForAccessibility());
        });
        return s.toString();
    }

    private String widgetsState() {
        StringBuilder s = new StringBuilder();
        main(() -> {
            View hero = hero();
            s.append("hero ").append(view(hero)).append(" height ").append(hero.getLayoutParams().height);
            for (String name : new String[] {"chip", "percent", "stage", "rows"}) s.append(' ').append(name).append(' ').append(view((View) field(hero, name)));
            s.append(" glow ").append(field(hero, "glowShown")).append(" hold ").append(field(hero, "holdPulse"));
        });
        return s.toString();
    }

    private static String view(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return String.format(Locale.US, "[%d %d %dx%d v%d a%.3f s%.3f t%.1f,%.1f]", loc[0], loc[1], v.getWidth(), v.getHeight(),
                v.getVisibility(), v.getAlpha(), v.getScaleX(), v.getTranslationX() + 0f, v.getTranslationY() + 0f); // + 0f: -0 reads as 0
    }

    private void same(String what, String after, String reference) {
        require(after.equals(reference), what + ": differs from Home shown without the change.\n after: " + after + "\n  rest: " + reference);
        assertions++; checks.add(what);
    }

    // ---- Home ------------------------------------------------------------------------------------------------------

    private Object screen() { return field(activity, "screen"); }
    /** Clean Home (the one-pane screen, or the two-pane Home's scooter pane), Widgets' DashboardScreen, or null. */
    private Object home() {
        Object pane = field(activity, "homeScreen");
        return pane != null ? pane : screen();
    }
    private Object clean() {
        Object home = home();
        return home != null && home.getClass().getSimpleName().equals("CleanHomeScreen") ? home : null;
    }
    private boolean twoPane() { boolean[] pane = {false}; main(() -> pane[0] = field(activity, "homeScreen") != null); return pane[0]; }
    private int moment(Object clean) { return clean == null ? -1 : (Integer) field(clean, "moment"); }
    private boolean cleanChanging() { Object c = clean(); return c != null && (Boolean) invoke(field(c, "transition"), "changing", new Class<?>[0]); }
    private float progress() { Object c = clean(); return c == null ? -1 : (Float) invoke(field(c, "transition"), "progress", new Class<?>[0]); }
    private View hero() {
        Object screen = screen();
        if (screen == null || !screen.getClass().getSimpleName().equals("DashboardScreen")) return null;
        return (View) field(screen, "chargingHero");
    }
    private boolean heroShown() { View h = hero(); return h != null && h.getVisibility() == View.VISIBLE; }
    private boolean widgetsChanging() { View h = hero(); return h != null && field(h, "reveal") != null; }
    private float revealProgress() { View h = hero(); return h == null ? -1 : (Float) field(h, "revealProgress"); }
    /** The simulated scooter starts or stops charging, and the body refreshes at once (as a live reading's next refresh). */
    private void charging(boolean on) { service.ble.setSimulationCharging(on); activity.redraw(); }

    // ---- Plumbing --------------------------------------------------------------------------------------------------

    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        require(getTargetContext().databaseList().length == 0, "Refusing existing app data");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
    }
    private void setScale(String value) throws Exception { shell("settings put global animator_duration_scale " + value); }
    private void writeNotes() throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "charge-transition-validation"); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "notes-" + args.getString("size_label", "size") + ".txt"))) { out.write(notes.toString().getBytes(StandardCharsets.UTF_8)); }
    }
    private void note(String line) { notes.append(line).append('\n'); }
    private static float span(List<float[]> frames) { return frames.isEmpty() ? 0 : frames.get(frames.size() - 1)[0] - frames.get(0)[0]; }
    private static String fmt(float v) { return String.format(Locale.US, "%.2f", v); }
    private static String row(float[] f, String[] names) {
        StringBuilder s = new StringBuilder("{");
        for (int i = 0; i < f.length; i++) s.append(names[i]).append('=').append(fmt(f[i])).append(i + 1 < f.length ? ", " : "}");
        return s.toString();
    }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        boolean[] ok = {false};
        while (SystemClock.uptimeMillis() < end) {
            main(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) return;
            SystemClock.sleep(50);
        }
        throw new IllegalStateException(message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { byte[] buffer = new byte[1024]; for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n); }
        return out.toString("UTF-8");
    }
    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
                catch (NoSuchFieldException e) { type = type.getSuperclass(); }
            }
            throw new NoSuchFieldException(name);
        } catch (Exception e) { throw new IllegalStateException("Field " + name, e); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try {
            Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values);
        } catch (Exception e) { throw new IllegalStateException("Invoke " + name, e); }
    }
}
