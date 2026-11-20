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
 * during a hide press nothing, and a press or slide already down when the hide starts is over: its release after they return does
 * nothing, and a fresh one works. Rotation and a change of mind mid-way end in the right state, and the long-ride dimming holds.
 * Follows the system's animator scale: at 0 they jump, at 5 or more the frames are also screenshotted and in-between frames are required.
 *
 * The 1 mph gate (owner, Nov 19): a hide starts only after 2 s at 1 mph or more, so every hide here waits for its start. A roll
 * under 1 mph and a short blip over it keep them up on every frame; a lock tap while rolling waits, shows its side and goes out
 * at the stop; one that never sees a stop goes back after 5 s; one waiting when the controls hide goes back then.
 */
public final class RidingStoppedMotionValidation extends ValidationInstrumentation {
    private static final long CHANGE_MS = 220;
    /** RidingFrames.HIDE_AFTER_MS: a hide starts this long after the speed reaches 1 mph or more. */
    private static final long HIDE_MS = 2000;
    /** LockTapQueue.HOLD_MS: a riding-mode lock tap waits this long for a complete stop. */
    private static final long HOLD_MS = 5000;
    private MainActivity activity;
    private File evidence;
    private String profileId;
    private float scale;
    private int assertions;
    private String immersivePrevious;
    private final ArrayList<String> notes = new ArrayList<>();

    private static final class Sample {
        float controlsAlpha, recordScale, lockScale, readoutAlpha;
        int controls, readout, readoutTop, readoutHeight, slotHeight;
        boolean takes, recordClickable, recordEnabled;
    }

    // A motion check: riding mode as on a phone, its recording dot pulsing.
    @Override public void onCreate(Bundle args) { runEndlessMotion(); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hw.contains("ranchu") || hw.contains("goldfish"), "emulator only");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-stopped-motion");
            require(evidence.mkdirs() || evidence.isDirectory(), "evidence directory");
            // Android's one-time "Viewing full screen" notice would otherwise take the taps and cover the frames.
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            run();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) try {
                main(() -> { invoke(activity, "closeRiding"); activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            } catch (Throwable ignored) { }
            if (immersivePrevious != null) try {
                shell(immersivePrevious.isEmpty() || immersivePrevious.equals("null") ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious);
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
        heldGestures();
        boundary();
        rollingLock();
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
        List<Sample> hide = during(() -> script("riding"), frame + "-1-hide", true);
        judge(hide, false, frame + " hide", top);
        awaitRest(false, frame + " hide");
        shot(frame + "-2-rest-hidden");
        List<Sample> show = during(() -> script("stopped"), frame + "-3-show", false);
        judge(show, true, frame + " show", top);
        awaitRest(true, frame + " show");
    }

    /**
     * Starts the change on the main thread, samples each drawn frame until it should be over, and shoots in-between frames when slowed.
     * A hide samples from the change on, through the 2 s at speed before it starts (shown, every frame), to its end.
     */
    private List<Sample> during(Runnable change, String shots, boolean hide) throws Exception {
        List<Sample> samples = new ArrayList<>();
        boolean[] run = {true};
        long window = (long) (CHANGE_MS * scale) + 500;
        Choreographer.FrameCallback[] tick = new Choreographer.FrameCallback[1];
        main(() -> {
            tick[0] = t -> { if (!run[0]) return; samples.add(sample()); Choreographer.getInstance().postFrameCallback(tick[0]); };
            change.run();
            Choreographer.getInstance().postFrameCallback(tick[0]);
        });
        if (hide) awaitHideStart(shots);
        if (scale >= 5) {
            int i = 0;
            long end = SystemClock.uptimeMillis() + (long) (CHANGE_MS * scale) - 200;
            while (SystemClock.uptimeMillis() < end) {
                shot(shots + "-" + (i++));
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
        awaitHideStart("the taps' hide");
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

    /**
     * Reverses a hide part-way. A show can't be reversed by speed any more: speed under 2 s old hides nothing, so the show
     * finishes and they stay up until the 2 s have passed (owner, Nov 19).
     */
    private void changeOfMind() throws Exception {
        main(() -> script("riding"));
        awaitHideStart("the reversed hide");
        SystemClock.sleep((long) (70 * scale));
        main(() -> { script("stopped"); require(takes(), "taps return as soon as it starts to show"); });
        awaitRest(true, "hide reversed");
        main(() -> script("riding"));
        awaitRest(false, "hide");
        main(() -> script("stopped"));
        SystemClock.sleep((long) (70 * scale));
        long sped = SystemClock.uptimeMillis();
        main(() -> { script("riding"); require(takes(), "speed mid-show doesn't take the taps away"); });
        awaitRest(true, "the show finishes at speed");
        awaitHideStart("the hide after the show");
        long waited = SystemClock.uptimeMillis() - sped;
        require(waited >= HIDE_MS - 100, "the hide started " + waited + " ms after the speed, before the 2 s");
        awaitRest(false, "show then speed");
        note("a hide reversed mid-way ends shown; speed mid-show lets the show finish and hides " + waited + " ms later");
    }

    private void rotation() throws Exception {
        main(() -> script("stopped"));
        awaitRest(true, "before rotation");
        // Moving off mid-hide into landscape: hidden there.
        main(() -> script("riding"));
        awaitHideStart("the rotated hide");
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
            List<Sample> hide = during(() -> script("riding"), "dim-hide", true);
            for (Sample f : hide) require(f.controlsAlpha <= .81f, "brighter than the dim during the hide: " + f.controlsAlpha);
            awaitRest(false, "dim hide");
            List<Sample> show = during(() -> script("stopped"), "dim-show", false);
            for (Sample f : show) require(f.controlsAlpha <= .81f, "brighter than the dim during the show: " + f.controlsAlpha);
            await(() -> nearly(sample2().controlsAlpha, .8f), "back at the dimmed alpha");
            note("long-ride dim holds at " + sample2().controlsAlpha);
        } finally {
            opened.setLong(session, old);
        }
    }

    /**
     * A press or a slide already down when the hide starts, across stopped, moving and stopped again (to rest, and reversed part-way),
     * released once the controls are back: nothing fires, the slider is back on its side, and a fresh gesture then works.
     * Every case runs before judging, so a failure names them all. Replaces Record's click with a counter: the simulator refuses a recording.
     */
    private void heldGestures() throws Exception {
        awaitRest(true, "before held gestures");
        View record = view("recordDisc"), lock = view("stoppedLock");
        int[] clicks = {0};
        main(() -> record.setOnClickListener(v -> clicks[0]++));
        List<String> wrong = new ArrayList<>();
        for (boolean reversed : new boolean[]{false, true}) {
            String how = reversed ? "a reversed hide" : "a full hide";
            heldRecord(record, clicks, reversed, how, wrong);
            if (lock != null) heldLock(lock, reversed, how, wrong);
        }
        require(wrong.isEmpty(), "held gestures: " + String.join("; ", wrong));
        note("held press and slide: cancelled when the hide starts, nothing on release, a fresh one works");
    }

    private void heldRecord(View record, int[] clicks, boolean reversed, String how, List<String> wrong) throws Exception {
        int before = clicks[0];
        long down = down(record);
        await(record::isPressed, "Record should press before the hide");
        boolean stillDown = awayAndBack(record, null, reversed);
        sendPointerSync(event(down, MotionEvent.ACTION_UP, record));
        waitForIdleSync();
        SystemClock.sleep(150);
        waitForIdleSync();
        if (stillDown) wrong.add("Record stayed pressed through " + how);
        if (clicks[0] != before) wrong.add("Record clicked on release after " + how);
        int settled = clicks[0];
        long fresh = down(record);
        await(record::isPressed, "a fresh press after " + how);
        sendPointerSync(event(fresh, MotionEvent.ACTION_UP, record));
        waitForIdleSync();
        SystemClock.sleep(150);
        waitForIdleSync();
        if (clicks[0] != settled + 1) wrong.add("a fresh tap on Record after " + how + " clicked " + (clicks[0] - settled) + " times");
    }

    private void heldLock(View lock, boolean reversed, String how, List<String> wrong) throws Exception {
        Object queue = field(activity, "lockQueue");
        Boolean was = locked();
        require(was != null, "the simulator reports a lock state");
        long down = down(lock);
        await(lock::isPressed, "Lock should press before the hide");
        slide(down, lock, .5f, was ? .15f : .85f);
        await(() -> Boolean.TRUE.equals(field(lock, "dragging")), "the slide should be dragging before the hide");
        boolean stillDown = awayAndBack(lock, "dragging", reversed);
        // The move a held slide keeps sending: the slide's own disallow-intercept must not keep it alive.
        sendPointerSync(dragEvent(down, MotionEvent.ACTION_MOVE, lock, was ? .1f : .9f));
        sendPointerSync(dragEvent(down, MotionEvent.ACTION_UP, lock, was ? .1f : .9f));
        waitForIdleSync();
        boolean admitted = false;
        for (long end = SystemClock.uptimeMillis() + 600; SystemClock.uptimeMillis() < end; SystemClock.sleep(20))
            admitted |= waiting(queue) || !was.equals(locked());
        if (stillDown) wrong.add("Lock's slide stayed live through " + how);
        if (admitted) {
            wrong.add("Lock acted on a slide from before " + how);
            await(() -> !waiting(queue), "the admitted slide to settle");
        }
        Boolean now = locked();
        await(() -> Math.abs(((Float) field(lock, "position")) - (Boolean.TRUE.equals(now) ? 1 : 0)) < .01f, "the slider should snap back after " + how);
        boolean[] live = new boolean[1];
        main(() -> live[0] = lock.isPressed() || Boolean.TRUE.equals(field(lock, "dragging")));
        if (live[0]) wrong.add("Lock still looks held after " + how);
        long fresh = down(lock);
        await(lock::isPressed, "a fresh slide after " + how);
        slide(fresh, lock, .5f, now ? .15f : .85f);
        sendPointerSync(dragEvent(fresh, MotionEvent.ACTION_UP, lock, now ? .15f : .85f));
        waitForIdleSync();
        long end = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < end && now.equals(locked())) SystemClock.sleep(30);
        if (now.equals(locked())) wrong.add("a fresh slide on Lock after " + how + " did nothing");
        await(() -> !waiting(queue), "the fresh slide to settle");
    }

    /**
     * Stopped to moving and back with the finger still down: to rest, or reversed part-way (at once with animations off).
     * True when the gesture was still live straight after the hide started: pressed, or the named flag.
     */
    private boolean awayAndBack(View target, String flag, boolean reversed) {
        boolean[] live = new boolean[1];
        main(() -> script("riding"));
        // The first frame of the hide: the gesture must be over by then (the 2 s at speed before it keep it live).
        await(() -> { boolean[] off = new boolean[1]; main(() -> { off[0] = !takes(); if (off[0]) live[0] = target.isPressed() || (flag != null && Boolean.TRUE.equals(field(target, flag))); }); return off[0]; }, "the held hide did not start");
        if (reversed) SystemClock.sleep((long) (70 * scale));
        else awaitRest(false, "held hide");
        main(() -> script("stopped"));
        awaitRest(true, "held show");
        return live[0];
    }

    /**
     * The owner's boundary (Nov 19), on the stopped script with held speeds: a roll under 1 mph (1.609 km/h) and blips over it
     * shorter than 2 s keep them up and taking taps on every drawn frame; 2 s at 1 mph or more hides them, and back under
     * 1 mph shows them at once.
     */
    private void boundary() throws Exception {
        main(() -> script("stopped"));
        awaitRest(true, "before the boundary");
        List<Sample> frames = new ArrayList<>();
        frames.addAll(watch(() -> speed(1.5), 1500));
        frames.addAll(watch(() -> speed(5), 1000));
        frames.addAll(watch(() -> speed(1.2), 1200));
        frames.addAll(watch(() -> speed(3), 1500));
        frames.addAll(watch(() -> speed(0), 600));
        int dips = 0;
        for (Sample f : frames) if (f.controls != View.VISIBLE || !f.takes || !near(f.recordScale, 1)) dips++;
        require(!frames.isEmpty() && dips == 0, "a roll under 1 mph or a blip under 2 s dipped the controls on " + dips + " of " + frames.size() + " frames");
        note("rolling at 1.5 and 1.2 km/h with blips to 5 km/h for 1 s and 3 km/h for 1.5 s: up on all " + frames.size() + " frames");
        shot("boundary-0-rolling-shown");
        long sped = SystemClock.uptimeMillis();
        main(() -> speed(2));
        awaitHideStart("2 s at 2 km/h");
        long waited = SystemClock.uptimeMillis() - sped;
        require(waited >= HIDE_MS - 100 && waited < HIDE_MS + 1500, "2 km/h hid them after " + waited + " ms, not about 2 s");
        awaitRest(false, "sustained 2 km/h");
        long slowed = SystemClock.uptimeMillis();
        main(() -> speed(1.4));
        awaitRest(true, "back under 1 mph");
        long back = SystemClock.uptimeMillis() - slowed;
        require(back < 1500, "under 1 mph again took " + back + " ms to show them");
        note("sustained 2 km/h hid them after " + waited + " ms; 1.4 km/h showed them " + back + " ms later");
        main(() -> speed(0));
    }

    /**
     * A lock tap while rolling under 1 mph (owner, Nov 19): it waits with the switch on the requested side and goes out at the
     * stop; one that sees no stop goes back after 5 s with nothing sent; one waiting when the controls hide or the link drops
     * goes back then; a tap back cancels it. The simulator's stationary flag is always on, so its live speed decides here.
     */
    private void rollingLock() throws Exception {
        View lock = view("stoppedLock");
        require(lock != null, "the simulator's UT5 MAX has Lock");
        Object queue = field(activity, "lockQueue");
        main(() -> { script("stopped"); speed(0); });
        awaitRest(true, "before the rolling lock");
        settle();
        Boolean was = locked();
        require(was != null, "the simulator reports a lock state");

        // Rolling: waits, shown on the requested side, nothing sent; the stop sends it.
        main(() -> speed(1.2));
        settle();
        tapLock(lock);
        require(waiting(queue), "a tap at 1.2 km/h waits");
        require(!was.equals(shownSide(lock)), "the switch shows the requested side at once");
        shot("lock-0-rolling-waiting");
        SystemClock.sleep(1500);
        require(waiting(queue) && was.equals(locked()), "still rolling 1.5 s later: nothing sent");
        long stopped = SystemClock.uptimeMillis();
        main(() -> speed(0));
        await(() -> !waiting(queue) && !was.equals(locked()), "the stop sends the waiting tap");
        long sent = SystemClock.uptimeMillis() - stopped;
        require(sent < 1500, "the tap went out " + sent + " ms after the stop");
        note("a lock tap at 1.2 km/h waited 1.5 s and went out " + sent + " ms after the stop");
        Boolean now = locked();

        // Never stopped: back after 5 s, nothing sent.
        main(() -> speed(1.2));
        settle();
        long tapped = SystemClock.uptimeMillis();
        tapLock(lock);
        require(waiting(queue) && !now.equals(shownSide(lock)), "the second tap waits on its side");
        await(() -> !waiting(queue), "the waiting tap goes back");
        long held = SystemClock.uptimeMillis() - tapped;
        require(held >= HOLD_MS - 200 && held < HOLD_MS + 1500, "the waiting tap went back after " + held + " ms, not about 5 s");
        require(now.equals(locked()) && now.equals(shownSide(lock)), "after the 5 s nothing was sent and the switch is back");
        settle();
        shot("lock-1-put-back");
        note("a tap that saw no stop went back after " + held + " ms, nothing sent");

        // Waiting when the controls hide (2 s at 5 km/h): back then, before the 5 s.
        tapped = SystemClock.uptimeMillis();
        tapLock(lock);
        require(waiting(queue), "the third tap waits");
        main(() -> speed(5));
        awaitHideStart("the hide with a tap waiting");
        await(() -> !waiting(queue), "the hide puts the tap back");
        held = SystemClock.uptimeMillis() - tapped;
        require(held < HOLD_MS && now.equals(locked()), "the hide put it back after " + held + " ms with nothing sent");
        note("a waiting tap went back when the controls hid, " + held + " ms after it");
        main(() -> speed(1.2));
        awaitRest(true, "shown again");
        settle();

        // The link drops while it waits: back, and the reconnect sends nothing.
        tapLock(lock);
        require(waiting(queue), "the fourth tap waits");
        main(() -> script("dropout"));
        await(() -> !waiting(queue), "a dropped link puts the tap back");
        main(() -> { script("stopped"); speed(0); });
        await(() -> activity.service().ble.ready(), "reconnected");
        awaitRest(true, "after the reconnect");
        SystemClock.sleep(1500);
        require(now.equals(locked()) && !waiting(queue), "the reconnect sent nothing");

        // A tap back cancels a waiting one.
        main(() -> speed(1.2));
        settle();
        tapLock(lock);
        require(waiting(queue), "the fifth tap waits");
        tapLock(lock);
        require(!waiting(queue), "a tap back cancels it");
        main(() -> speed(0));
        SystemClock.sleep(1500);
        require(now.equals(locked()), "nothing went out at the stop after the tap back");
        note("a drop and a tap back each cancelled a waiting tap with nothing sent");
        main(() -> { unhold(); script("stopped"); });
    }

    /** Samples each drawn frame for ms after the change. */
    private List<Sample> watch(Runnable change, long ms) {
        List<Sample> samples = new ArrayList<>();
        boolean[] run = {true};
        Choreographer.FrameCallback[] tick = new Choreographer.FrameCallback[1];
        main(() -> {
            tick[0] = t -> { if (!run[0]) return; samples.add(sample()); Choreographer.getInstance().postFrameCallback(tick[0]); };
            change.run();
            Choreographer.getInstance().postFrameCallback(tick[0]);
        });
        SystemClock.sleep(ms);
        main(() -> run[0] = false);
        return new ArrayList<>(samples);
    }

    /** Waits for the hide's first frame: taps off. */
    private void awaitHideStart(String what) {
        await(() -> { boolean[] off = new boolean[1]; main(() -> off[0] = !takes()); return off[0]; }, what + ": the hide did not start");
    }

    /** A held simulated speed (km/h) that wins over the script's, applied at once; on the main thread. */
    private void speed(double kmh) {
        try {
            Object sim = field(activity.service().ble, "simulation");
            Method hold = sim.getClass().getDeclaredMethod("hold", String.class, String.class, double.class);
            hold.setAccessible(true);
            hold.invoke(sim, "speed_kmh", kmh + " km/h", kmh);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        activity.service().ble.tickSimulation();
        activity.redraw();
    }

    /** The script's own speed again. */
    private void unhold() { ((java.util.Map<?, ?>) field(field(activity.service().ble, "simulation"), "held")).remove("speed_kmh"); }

    /** A real tap: down, then up a moment later. */
    private void tapLock(View lock) {
        long down = down(lock);
        SystemClock.sleep(40);
        sendPointerSync(event(down, MotionEvent.ACTION_UP, lock));
        waitForIdleSync();
    }

    /** The side the switch draws: a waiting tap's until the scooter settles it. */
    private Boolean shownSide(View lock) {
        Boolean[] v = new Boolean[1];
        main(() -> { try { Method m = lock.getClass().getDeclaredMethod("shown"); m.setAccessible(true); v[0] = (Boolean) m.invoke(lock); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } });
        return v[0];
    }

    private void slide(long down, View lock, float from, float to) {
        for (float at : new float[]{from + (to - from) * .25f, from + (to - from) * .6f, to}) sendPointerSync(dragEvent(down, MotionEvent.ACTION_MOVE, lock, at));
    }

    private Boolean locked() { Boolean[] v = new Boolean[1]; main(() -> v[0] = activity.service().ble.locked()); return v[0]; }

    private boolean waiting(Object queue) {
        boolean[] v = new boolean[1];
        main(() -> { try { Method m = queue.getClass().getDeclaredMethod("waiting"); m.setAccessible(true); v[0] = (Boolean) m.invoke(queue); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } });
        return v[0];
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

    /** A move or release at a fraction of the view's width. */
    private MotionEvent dragEvent(long down, int action, View target, float fraction) {
        float[] c = new float[2];
        main(() -> { int[] p = new int[2]; target.getLocationOnScreen(p); c[0] = p[0] + target.getWidth() * fraction; c[1] = p[1] + target.getHeight() / 2f; });
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, c[0], c[1], 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return e;
    }

    private void shot(String name) throws Exception {
        if (evidence == null) return;
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
