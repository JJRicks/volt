package com.jjricks.volt.tests;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
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
import java.util.function.BooleanSupplier;

/**
 * Riding mode's stopped Lock and Record show and hide with the tune disc's motion (owner, Nov 9), on the simulator's stopped and
 * riding scripts in each frame. Every drawn frame of a show and a hide is sampled: the motion is monotonic, the readout under
 * the buttons keeps its place and never shows strongly with them, and taps are off from the moment the hide starts. Real touches
 * during a hide press nothing. Rotation and a change of mind mid-way end in the right state, and the long-ride dimming holds.
 * Follows the system's animator scale: at 0 they jump, at 5 or more the frames are also screenshotted and in-between frames are required.
 */
public final class RidingStoppedMotionValidation extends Instrumentation {
    private static final long CHANGE_MS = 220;
    private MainActivity activity;
    private File evidence;
    private String profileId;
    private float scale;
    private int assertions;
    private final ArrayList<String> notes = new ArrayList<>();

    private static final class Sample {
        float controlsAlpha, recordScale, lockScale, readoutAlpha;
        int controls, readout, readoutTop, readoutHeight, slotHeight;
        boolean takes, recordClickable, recordEnabled;
    }

    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hw.contains("ranchu") || hw.contains("goldfish"), "emulator only");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-stopped-motion");
            require(evidence.mkdirs() || evidence.isDirectory(), "evidence directory");
            run();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) try {
                main(() -> { invoke(activity, "closeRiding"); activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            } catch (Throwable ignored) { }
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void run() throws Exception {
        require(prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true).commit(), "settings");
        main(() -> {
            OnboardingScreen.markDone(getTargetContext());
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile p = profiles.create("Synthetic motion scooter", "0F1E2D3C4B5A", 0);
            p.mac = "C0:FF:EE:00:00:09"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
            profileId = p.id;
        });
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch");
        settle();
        // Only known once a window exists: before that it reads the default.
        scale = ValueAnimator.getDurationScale();
        note("animator scale " + scale + ", animators enabled " + ValueAnimator.areAnimatorsEnabled());
        main(() -> require(activity.service().setSimulation(true), "simulation"));
        settle();
        await(() -> activity.service().ble.ready(), "simulation ready");
        for (String frame : new String[]{"focus", "halo", "trace"}) cycle(frame);
        // The rest runs in the default frame.
        open("focus");
        tapsDuringHide();
        changeOfMind();
        rotation();
        dimming();
    }

    private void open(String frame) throws Exception {
        if (field(activity, "riding") != null) { main(() -> invoke(activity, "closeRiding")); settle(); }
        require(prefs().edit().putString("riding_layout_v1_" + profileId, "{\"v\":1,\"frame\":\"" + frame + "\"}").commit(), "layout");
        main(() -> script("stopped"));
        main(() -> activity.openRiding());
        await(() -> field(activity, "riding") != null && "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding did not open");
        settle();
        awaitRest(true, frame + " open");
    }

    /** One hide and one show, every frame sampled, and shots at a slowed scale. */
    private void cycle(String frame) throws Exception {
        open(frame);
        shot(frame + "-0-rest-shown");
        int top = sample().readoutTop;
        List<Sample> hide = during(() -> script("riding"), frame + "-1-hide");
        judge(hide, false, frame + " hide", top);
        awaitRest(false, frame + " hide");
        shot(frame + "-2-rest-hidden");
        List<Sample> show = during(() -> script("stopped"), frame + "-3-show");
        judge(show, true, frame + " show", top);
        awaitRest(true, frame + " show");
    }

    /** Starts the change on the main thread, samples each drawn frame until it should be over, and shoots in-between frames when slowed. */
    private List<Sample> during(Runnable change, String shots) throws Exception {
        List<Sample> samples = new ArrayList<>();
        boolean[] run = {true};
        long window = (long) (CHANGE_MS * scale) + 500;
        Choreographer.FrameCallback[] tick = new Choreographer.FrameCallback[1];
        main(() -> {
            tick[0] = t -> { if (!run[0]) return; samples.add(sample()); Choreographer.getInstance().postFrameCallback(tick[0]); };
            change.run();
            Choreographer.getInstance().postFrameCallback(tick[0]);
        });
        if (scale >= 5) {
            int i = 0;
            long end = SystemClock.uptimeMillis() + (long) (CHANGE_MS * scale) - 200;
            while (SystemClock.uptimeMillis() < end) {
                Bitmap bitmap = getUiAutomation().takeScreenshot();
                if (bitmap != null) try (FileOutputStream out = new FileOutputStream(new File(evidence, shots + "-" + (i++) + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            }
            SystemClock.sleep(600);
        } else SystemClock.sleep(window);
        main(() -> run[0] = false);
        return samples;
    }

    private void judge(List<Sample> frames, boolean shown, String what, int top) {
        require(!frames.isEmpty(), what + ": no frames");
        Sample last = frames.get(frames.size() - 1);
        float previous = shown ? -1 : 2;
        int between = 0;
        for (Sample f : frames) {
            require(f.readoutTop == top, what + ": the readout moved to " + f.readoutTop + " from " + top);
            require(f.readoutAlpha * f.controlsAlpha <= .13f, what + ": readout " + f.readoutAlpha + " and buttons " + f.controlsAlpha + " both strong");
            if (!f.takes) require(!f.recordClickable && !f.recordEnabled, what + ": Record still takes taps as it hides");
            if (f.controls == View.GONE) { require(f.controlsAlpha == 0, what + ": gone with alpha"); }
            float a = f.controlsAlpha;
            if (shown) require(a + .001f >= previous, what + ": fade went backwards " + previous + " -> " + a);
            else require(a - .001f <= previous, what + ": fade went backwards " + previous + " -> " + a);
            previous = a;
            if (a > 0 && a < 1) { between++; require(near(f.recordScale, f.lockScale), what + ": buttons out of step"); }
        }
        require(shown == last.takes, what + ": taps state");
        note(what + ": " + frames.size() + " frames, " + between + " in between; first alpha " + frames.get(0).controlsAlpha + ", last " + last.controlsAlpha);
        if (scale == 0) require(between == 0, what + ": animations off must not show in-between frames");
        else if (scale >= 5) require(between >= 3, what + ": expected in-between frames at scale " + scale + ", got " + between);
    }

    /** Real pointer taps on Lock and Record as they hide: pressed never lights and nothing changes. */
    private void tapsDuringHide() throws Exception {
        boolean recording = activity.service().isRecording();
        // The control: the same touch while shown does press the button.
        View record = view("recordDisc"), lock = view("stoppedLock");
        View[] targets = lock == null ? new View[]{record} : new View[]{record, lock};
        for (View target : targets) {
            long down = down(target);
            await(target::isPressed, "a touch while shown should press the button");
            sendPointerSync(event(down, MotionEvent.ACTION_CANCEL, target));
            settle();
        }
        main(() -> script("riding"));
        await(() -> { boolean[] off = new boolean[1]; main(() -> off[0] = !takes()); return off[0]; }, "the hide did not start");
        for (View target : targets) {
            long down = down(target);
            main(() -> require(!target.isPressed(), "a touch during the hide pressed a button"));
            sendPointerSync(event(down, MotionEvent.ACTION_UP, target));
            waitForIdleSync();
        }
        settle();
        require(activity.service().isRecording() == recording, "a tap during the hide changed recording");
        Object queue = field(activity, "lockQueue");
        Method waiting = queue.getClass().getDeclaredMethod("waiting");
        waiting.setAccessible(true);
        require(!(Boolean) waiting.invoke(queue), "a tap during the hide queued a lock change");
        awaitRest(false, "tapped hide");
        note("taps during the hide pressed nothing and changed nothing");
        // Back to shown for what follows.
        main(() -> script("stopped"));
        awaitRest(true, "after taps");
    }

    /** Reverses the motion part-way, both ways. */
    private void changeOfMind() throws Exception {
        main(() -> script("riding"));
        SystemClock.sleep((long) (70 * scale));
        main(() -> { script("stopped"); require(takes(), "taps return as soon as it starts to show"); });
        awaitRest(true, "hide reversed");
        main(() -> script("riding"));
        awaitRest(false, "hide");
        main(() -> script("stopped"));
        SystemClock.sleep((long) (70 * scale));
        main(() -> { script("riding"); require(!takes(), "taps stop as soon as it starts to hide"); });
        awaitRest(false, "show reversed");
        note("a change of mind mid-way ends at rest, both ways");
    }

    private void rotation() throws Exception {
        main(() -> script("stopped"));
        awaitRest(true, "before rotation");
        // Moving off mid-hide into landscape: hidden there.
        main(() -> script("riding"));
        SystemClock.sleep((long) (40 * scale));
        main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        settle();
        await(() -> activity.getResources().getConfiguration().orientation == 2, "landscape");
        settle();
        awaitRest(false, "rotated during hide");
        shot("rotated-hidden");
        // Stopping mid-show back into portrait: shown there.
        main(() -> script("stopped"));
        SystemClock.sleep((long) (40 * scale));
        main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        settle();
        await(() -> activity.getResources().getConfiguration().orientation == 1, "portrait");
        settle();
        awaitRest(true, "rotated during show");
        shot("rotated-shown");
        main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        settle();
        note("rotation mid-motion ends at rest on the new screen");
    }

    /** A ride past the burn-in window: the buttons rest at the dimmed alpha and never brighten past it while moving. */
    private void dimming() throws Exception {
        awaitRest(true, "before dimming");
        Object session = field(field(activity, "screen"), "session");
        Field opened = find(session.getClass(), "openedMs");
        opened.setAccessible(true);
        long old = opened.getLong(session);
        opened.setLong(session, SystemClock.elapsedRealtime() - 10 * 60_000L);
        try {
            main(() -> activity.redraw());
            settle();
            await(() -> nearly(sample2().controlsAlpha, .8f), "the buttons should rest at the dimmed alpha");
            List<Sample> hide = during(() -> script("riding"), "dim-hide");
            for (Sample f : hide) require(f.controlsAlpha <= .81f, "brighter than the dim during the hide: " + f.controlsAlpha);
            awaitRest(false, "dim hide");
            List<Sample> show = during(() -> script("stopped"), "dim-show");
            for (Sample f : show) require(f.controlsAlpha <= .81f, "brighter than the dim during the show: " + f.controlsAlpha);
            await(() -> nearly(sample2().controlsAlpha, .8f), "back at the dimmed alpha");
            note("long-ride dim holds at " + sample2().controlsAlpha);
        } finally {
            opened.setLong(session, old);
        }
    }

    private void script(String id) {
        activity.service().ble.setSimulationScript(id);
        activity.service().ble.tickSimulation();
        activity.redraw();
    }

    private void awaitRest(boolean shown, String what) {
        await(() -> { Sample s = sample2(); return shown ? s.controls == View.VISIBLE && near(s.recordScale, 1) && s.takes : s.controls == View.GONE; }, what + ": never came to rest");
        Sample s = sample2();
        if (shown) {
            require(near(s.recordScale, 1) && near(s.lockScale, 1) && s.readout == View.INVISIBLE && s.takes && s.controlsAlpha > .7f, what + ": shown state");
        } else {
            require(s.controls == View.GONE && s.readout == View.VISIBLE && near(s.readoutAlpha, 1) && !s.takes, what + ": hidden state");
        }
    }

    private Sample sample2() { Sample[] s = new Sample[1]; main(() -> s[0] = sample()); return s[0]; }

    /** One reading of the views, on the main thread. */
    private Sample sample() {
        Object screen = field(activity, "screen");
        View controls = (View) field(screen, "stoppedControls"), power = (View) field(screen, "power"), record = (View) field(screen, "recordDisc"), lock = (View) field(screen, "stoppedLock");
        Sample s = new Sample();
        s.controlsAlpha = controls.getAlpha(); s.controls = controls.getVisibility(); s.readout = power.getVisibility(); s.readoutAlpha = power.getAlpha();
        s.recordScale = record.getScaleX(); s.lockScale = lock == null ? record.getScaleX() : lock.getScaleX();
        s.readoutTop = power.getTop(); s.readoutHeight = power.getHeight();
        s.slotHeight = ((View) controls.getParent()).getHeight();
        s.takes = takes();
        s.recordClickable = record.isClickable(); s.recordEnabled = record.isEnabled();
        return s;
    }

    private boolean takes() {
        Object motion = field(field(activity, "screen"), "stoppedMotion");
        try { Method m = motion.getClass().getDeclaredMethod("takesTaps"); m.setAccessible(true); return (Boolean) m.invoke(motion); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private View view(String name) { View[] v = new View[1]; main(() -> v[0] = (View) field(field(activity, "screen"), name)); return v[0]; }

    private long down(View target) {
        long at = SystemClock.uptimeMillis();
        sendPointerSync(eventAt(at, at, MotionEvent.ACTION_DOWN, target));
        return at;
    }
    private MotionEvent event(long down, int action, View target) { return eventAt(down, SystemClock.uptimeMillis(), action, target); }
    private MotionEvent eventAt(long down, long time, int action, View target) {
        float[] c = new float[2];
        main(() -> { int[] p = new int[2]; target.getLocationOnScreen(p); c[0] = p[0] + target.getWidth() / 2f; c[1] = p[1] + target.getHeight() / 2f; });
        MotionEvent e = MotionEvent.obtain(down, time, action, c[0], c[1], 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private void shot(String name) throws Exception {
        if (evidence == null) return;
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "screenshot");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    private void note(String text) { notes.add(text); android.util.Log.i("VoltMotion", text); }
    private static boolean near(float a, float b) { return Math.abs(a - b) < .01f; }
    private static boolean nearly(float a, float b) { return Math.abs(a - b) < .02f; }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m) { long end = SystemClock.uptimeMillis() + 15000; while (!c.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(30); require(c.getAsBoolean(), m); }
    private static Field find(Class<?> t, String name) { while (t != null) { try { return t.getDeclaredField(name); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
    private static Object field(Object target, String name) { try { Field f = find(target.getClass(), name); f.setAccessible(true); return f.get(target); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    private static void invoke(Object target, String name) { try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }
}
