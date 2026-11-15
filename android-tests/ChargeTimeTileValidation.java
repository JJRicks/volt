package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.app.UiAutomation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio off. The charging view's one time tile (owner's design, Nov 12 2026) on synthetic
 * readings fed to the charging hero with the service's own updates unhooked, as ChargeAlertValidation does. Sends no
 * scooter command.
 *
 * Default mode: one reading without an alert (no dots, no switching); an alert's two readings with the alert first;
 * real taps and swipes switch; an abandoned swipe and a vertical move do not; touches that start on the label (which has
 * a tooltip), the value, the state pill and beside the dots all switch; a sideways swipe drifting up or down at the start
 * still switches and Home does not scroll; a scroll that starts on the tile scrolls Home and never switches; a long press
 * shows the tile's tooltip with two readings and the label's own with one; editing takes no touch; the second reading is
 * the plain time to 100%; a reset under a finger (the reveal ending, the second reading arriving) ends that touch with
 * no crash, drag, switch or tap, and the next tap and swipe work. Argument "cases" (comma list, e.g. touch-label,diagonal) runs only those cases; rapid taps, hiding mid-switch, a
 * re-parent (fold) mid-switch and a rotation mid-switch all end at rest on the last choice; the choice survives
 * hiding the view and an activity restart, per scooter; a scooter with a charge limit; reduced motion; TalkBack's
 * node and one announcement; slowed frames of one tap and one held drag saved as crops.
 * Mode layout: every theme, text size (1x, 1.4x) and language (English, German, both Portuguese) at the emulator's
 * current size: labels whole, the dots clear of the label and the state pill, one height for both readings.
 * Mode restart: run after a default pass on the same install (no reinstall): the saved choices survive a new process.
 */
public final class ChargeTimeTileValidation extends Instrumentation {
    private static final int LIMIT = 0, FULL = 1;
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted;
    private String a, b, ut5, label = "";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private final ChargeAlertPolicy.Settings alert70 = new ChargeAlertPolicy.Settings(true, 70, false, 20, true, false);

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "all");
        try {
            if (mode.equals("restart")) { admitEmulator(); restartCheck(); }
            else {
                admit(); admitted = true;
                seed(args.getString("theme", "dark"), args.getString("text_scale", "1"));
                main(() -> {
                    ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                    ScooterProfiles.Profile pa = profiles.create("Synthetic GT3 Max A", "000000000001", 0); pa.serial = "Q2345ZZ9ES7777777";
                    pa.advertisedPid = "24182"; pa.advertisedPidWall = System.currentTimeMillis(); profiles.update(pa); a = pa.id;
                    ScooterProfiles.Profile pb = profiles.create("Synthetic GT3 Max B", "000000000002", 0); pb.serial = "Q2345ZZ9ES7777778";
                    pb.advertisedPid = "24182"; pb.advertisedPidWall = System.currentTimeMillis(); profiles.update(pb); b = pb.id;
                    ScooterProfiles.Profile pu = profiles.create("Synthetic UT5 MAX", "000000000000", 0); pu.serial = "X2538-SYNTHETIC"; profiles.update(pu); ut5 = pu.id;
                    profiles.select(a); OnboardingScreen.markDone(getTargetContext());
                });
                launch();
                if (mode.equals("layout")) verifyLayoutMatrix();
                else {
                    verifyOneReading();
                    verifyAlertReadings();
                    if (wanted("gestures")) verifyGestures();
                    if (wanted("touch")) verifyTouchEverywhere();
                    if (wanted("diagonal")) verifyDiagonalSwipe();
                    if (wanted("scroll")) verifyScrollFromTile();
                    if (wanted("longpress")) verifyLongPress();
                    if (wanted("edit")) verifyEditMode();
                    if (wanted("reset")) verifyResetMidTouch();
                    if (wanted("label")) verifyHundredLabel();
                    if (wanted("interruptions")) verifyInterruptions();
                    if (wanted("scooters")) verifyPerScooterAndRestart();
                    if (wanted("limit")) verifyLimitScooter();
                    if (wanted("motion")) verifyReducedMotion();
                    if (wanted("accessibility")) verifyAccessibility();
                    if (wanted("frames")) verifyFrames();
                    if (wanted("rotation")) verifyRotation();
                }
                assertNoRadio();
            }
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            // Only what this run changed goes back, to the value it found: a refused device is left as it was.
            restoreFound();
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Cases ------------------------------------------------------------------------------------------------

    private void verifyOneReading() throws Exception {
        hero(50, null, 2);
        main(() -> {
            require(!two() && tile().isShown(), "Without an alert the tile does not show one reading");
            require(face(FULL).getVisibility() == View.VISIBLE && face(LIMIT).getVisibility() == View.GONE, "One reading: the 100% face alone");
            require(!tile().isClickable() && !tile().isFocusable() && tile().getContentDescription() == null, "One reading: the tile became a control");
            require(String.valueOf(value(FULL).getContentDescription()).startsWith(String.valueOf(caption(FULL).getContentDescription())),
                    "One reading: the value does not speak its name: " + value(FULL).getContentDescription());
            require(power().getParent() == tile().getParent(), "Power is not beside the time");
        });
        shot("one-reading");
        tap();
        main(() -> require(!two() && face(FULL).getVisibility() == View.VISIBLE && pref(a) == null, "A tap on one reading switched or saved something"));
        checks.add("No alert, no limit: one reading (time to 100%), no dots, not a control, a tap does nothing");
    }

    private void verifyAlertReadings() throws Exception {
        main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
        hero(50, null, 2);
        main(() -> {
            require(two() && shownReading() == LIMIT && atRest(), "With an alert the tile does not open on the alert's time");
            require(String.valueOf(caption(LIMIT).getContentDescription()).contains("70"), "The alert reading is not named for 70%: " + caption(LIMIT).getContentDescription());
            require(!"—".contentEquals(value(LIMIT).getText()) && value(LIMIT).length() > 0 && value(FULL).length() > 0, "A reading has no value");
            require(face(FULL).getVisibility() == View.INVISIBLE, "The other reading is drawn at rest");
            require(power().getParent() == tile().getParent(), "The two readings took a second row");
            require(pref(a) == null, "Opening the view saved a choice");
        });
        shot("alert-default");
        checks.add("An alert ahead: one row, power beside one tile holding the alert's time (shown first) and the time to 100%");
    }

    private void verifyGestures() throws Exception {
        tap();
        awaitRest();
        main(() -> require(shownReading() == FULL && "full".equals(pref(a)), "A tap did not switch to 100% and save it"));
        swipe(-.8f, false);
        awaitRest();
        main(() -> require(shownReading() == LIMIT && "limit".equals(pref(a)), "A swipe to the left did not switch back"));
        swipe(.8f, false);
        awaitRest();
        main(() -> require(shownReading() == FULL, "A swipe to the right did not switch"));
        swipe(-.14f, true);  // about a third of the travel, held still, then let go
        awaitRest();
        main(() -> require(shownReading() == FULL && "full".equals(pref(a)), "An abandoned swipe switched"));
        verticalPass();
        awaitRest();
        main(() -> require(shownReading() == FULL, "Moving up or down over the tile switched it"));
        tap(); awaitRest();
        main(() -> require(shownReading() == LIMIT, "Tap back failed"));
        checks.add("A tap and a swipe either way switch and save; a swipe let go short, and an up-down move over the tile, do not");
    }

    /** The optional "cases" argument (comma list) runs only those default-mode cases after the two set-up ones. */
    private boolean wanted(String name) {
        String cases = args.getString("cases", "");
        if (cases.isEmpty()) return true;
        for (String c : cases.split(",")) { c = c.trim(); if (c.equals(name) || name.startsWith(c + "-") || c.startsWith(name + "-")) return true; }
        return false;
    }

    /**
     * Real touches that start on every child of the tile (the label, which has a tooltip, the value, the state pill and
     * beside the dots): a tap and a sideways drag switch, a drag let go short does not.
     */
    private void verifyTouchEverywhere() throws Exception {
        for (String point : new String[] {"label", "value", "pill", "dots"}) {
            if (!wanted("touch-" + point)) continue;
            boolean low = point.equals("pill");
            hero(50, null, low ? 0.03 : 2);  // under ChargingEstimate.MINIMUM_ETA_CURRENT_A: the Low current pill on both faces
            if (low) main(() -> require(tags(LIMIT).isShown() || tags(FULL).isShown(), "No state pill to touch at low current"));
            int start = readingNow();
            tapAt(point(point));
            awaitRest();
            main(() -> require(shownReading() == other(start) && ChargeTimeReadingsPref(pref(a)) == other(start),
                    "A tap that starts on the " + point + " did not switch: shown " + shownReading() + ", saved " + pref(a)));
            float[] p = point(point); int width = tileWidth();
            drag(p, readingNow() == FULL ? .8f * width : -.8f * width, 0, false);
            awaitRest();
            main(() -> require(shownReading() == start, "A sideways drag that starts on the " + point + " did not switch back"));
            p = point(point);
            drag(p, -.14f * width, 0, true);
            awaitRest();
            main(() -> require(shownReading() == start && ChargeTimeReadingsPref(pref(a)) == start, "An abandoned drag that starts on the " + point + " switched"));
            checks.add("Touches that start on the " + point + ": a tap and a drag switch, a drag let go short does not");
        }
        hero(50, null, 2);
    }

    /** A mostly sideways swipe that drifts up or down in its first move is the tile's, not Home's scroller's. */
    private void verifyDiagonalSwipe() throws Exception {
        hero(50, null, 2);
        int start = readingNow();
        float[] p = point("value");
        int sign = scrollRoom();
        int before = scrollY();
        float s = slop(), width = tileWidth();
        float dx = (start == LIMIT ? -1 : 1) * .8f * width;
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(16);
        // First move: past the scroller's slop up or down, but more sideways than that.
        send(MotionEvent.ACTION_MOVE, down, p[0] + Math.signum(dx) * 2.2f * s, p[1] + sign * 1.4f * s); SystemClock.sleep(16);
        for (int i = 1; i <= 8; i++) { send(MotionEvent.ACTION_MOVE, down, p[0] + dx * i / 8f, p[1] + sign * (1.4f + .1f * i) * s); SystemClock.sleep(16); }
        send(MotionEvent.ACTION_UP, down, p[0] + dx, p[1] + sign * 2.2f * s);
        settle(); awaitRest();
        int after = scrollY();
        main(() -> require(shownReading() == other(start), "A sideways swipe with a drift up or down at the start did not switch (Home scrolled "
                + (after - before) + " px)"));
        require(Math.abs(after - before) <= 1, "A sideways swipe on the tile scrolled Home by " + (after - before) + " px");
        checks.add("A sideways swipe with vertical drift past the slop in its first move switches, and Home does not scroll");
    }

    /** An up or down scroll that starts on the tile (on its label, then its value) scrolls Home and never switches. */
    private void verifyScrollFromTile() throws Exception {
        hero(50, null, 2);
        for (String point : new String[] {"label", "value"}) {
            int start = readingNow(); String saved = callString(() -> pref(a));
            float[] p = point(point);
            int sign = scrollRoom(), before = scrollY();
            float s = slop();
            long down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(16);
            for (int i = 1; i <= 10; i++) { send(MotionEvent.ACTION_MOVE, down, p[0] + .1f * s * i, p[1] + sign * 1.2f * s * i); SystemClock.sleep(16); }
            SystemClock.sleep(120);  // held still before lifting: no fling
            send(MotionEvent.ACTION_UP, down, p[0] + s, p[1] + sign * 12f * s);
            settle(); SystemClock.sleep(300); settle();
            int after = scrollY();
            require(Math.abs(after - before) >= 3 * s, "A scroll that starts on the tile's " + point + " did not scroll Home: " + before + " -> " + after);
            main(() -> require(shownReading() == start && cleanRest() && Objects.equals(saved, pref(a)), "A scroll that starts on the tile's " + point + " switched or saved"));
            checks.add("A scroll that starts on the tile's " + point + " scrolls Home (" + (after - before) + " px) and does not switch");
        }
    }

    /**
     * A long press: with two readings anywhere on the tile (the label too) it shows the tile's tooltip, the shown reading, and
     * never switches; with one reading the label keeps its own tooltip, as the single tile had.
     */
    private void verifyLongPress() throws Exception {
        hero(50, null, 2);
        int start = readingNow();
        String tile = callString(() -> String.valueOf(tile().getTooltipText()));
        String heard = longPress(point("label"));
        main(() -> require(shownReading() == start && cleanRest(), "A long press on the label switched"));
        require(tile.equals(heard), "A long press on the label with two readings did not show the tile's tooltip '" + tile + "': " + heard);
        checks.add("Two readings: a long press on the label shows the shown reading's tooltip and does not switch");
        main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"));
        hero(50, null, 2);
        String label = callString(() -> String.valueOf(caption(FULL).getTooltipText()));
        heard = longPress(point("label"));
        require(label.equals(heard), "A long press on the label with one reading did not show the label's tooltip '" + label + "': " + heard);
        main(() -> require(!two() && cleanRest(), "One reading: the long press left the tile changed"));
        checks.add("One reading: a long press on the label shows the label's own tooltip");
        main(() -> require(service.setChargeAlerts(alert70), "Could not restore the alert"));
        hero(50, null, 2);
    }

    /**
     * Edit mode: the charging view hides, and the tile takes no touch. The moment edit mode begins (the flag set while the
     * view is still up) real touches on the label go to the label as with one reading: no switch, no drag, the label's
     * own tooltip on a long press. In edit mode itself, touches handed straight to the hidden tile switch nothing.
     */
    private void verifyEditMode() throws Exception {
        hero(50, null, 2);
        int start = readingNow(); String saved = callString(() -> pref(a));
        main(() -> setField(screen(), "editing", true));
        try {
            tapAt(point("label"));
            drag(point("value"), -.8f * tileWidth(), 0, false);
            String label = callString(() -> String.valueOf(caption(start).getTooltipText()));
            String heard = longPress(point("label"));
            main(() -> require(shownReading() == start && cleanRest() && Objects.equals(saved, pref(a)), "Touches switched the tile while editing"));
            require(label.equals(heard), "While editing a long press on the label did not show the label's own tooltip '" + label + "': " + heard);
        } finally { main(() -> setField(screen(), "editing", false)); }
        checks.add("Editing begun with the view up: a tap, a drag and a long press go to the label as with one reading; nothing switches");
        main(() -> invoke(screen(), "toggleEditing")); SystemClock.sleep(700); settle();
        try {
            hero(50, null, 2, false);
            main(() -> {
                require(!hero().isShown(), "The charging view shows in edit mode");
                View t = tile(); TextView c = caption(start);
                Rect r = TextFit.relativeBounds(c, t);
                float x = r.exactCenterX(), y = r.exactCenterY(); long down = SystemClock.uptimeMillis();
                for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                    MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, action == MotionEvent.ACTION_MOVE ? x - 200 : x, y, 0);
                    t.dispatchTouchEvent(e); e.recycle();
                }
                down = SystemClock.uptimeMillis();
                for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); t.dispatchTouchEvent(e); e.recycle();
                }
                require(shownReading() == start && Objects.equals(saved, pref(a)), "A touch handed to the tile in edit mode switched it");
            });
        } finally { main(() -> invoke(screen(), "toggleEditing")); SystemClock.sleep(700); settle(); freeze(); }
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == start && cleanRest(), "Leaving edit mode lost the reading"));
        checks.add("Edit mode hides the charging view; a tap or drag handed to the tile switches nothing");
    }

    /**
     * A reset of the tile while a finger is down ends that touch: the rest of it does nothing (no drag, no switch, no
     * tap), Home's scroller gets it back, and the next ordinary tap and swipe work. The resets: the charging view's
     * reveal ending under the finger (real, slowed), the same call made directly (it also runs when the view is shown or
     * hidden), and the second reading arriving under a touch that began with one.
     */
    private void verifyResetMidTouch() throws Exception {
        if (wanted("reset-reveal")) {
            hero(50, null, 2);
            int start = readingNow(); String saved = callString(() -> pref(a));
            hero(50, null, 0);  // hidden, so the next charging readings reveal it again
            main(() -> scroller().scrollTo(0, 0));
            scale("10");
            long down;
            float[] p;
            try {
                refreshNow(50, null, 2);
                p = touchableDuringReveal();
                down = SystemClock.uptimeMillis();
                send(MotionEvent.ACTION_DOWN, down, p[0], p[1]);
                boolean[] revealing = new boolean[1];
                main(() -> revealing[0] = field(hero(), "reveal") != null);
                require(revealing[0], "The reveal ended before the touch began");
                await(() -> field(hero(), "reveal") == null, "The reveal did not end");
            } finally { scale("1"); }
            sidewaysAndLift(down, p, start);
            awaitRest();
            main(() -> require(two() && shownReading() == start && Objects.equals(saved, pref(a)), "A swipe after the reveal ended under the finger switched"));
            checks.add("A touch begun during the charging view's reveal, held past its end, then swiped sideways and lifted: no crash, no switch");
            ordinaryAfter("the reveal ended under a finger");
        }
        if (wanted("reset-direct")) {
            hero(50, null, 2);
            int start = readingNow(); String saved = callString(() -> pref(a));
            float[] p = centre();
            long down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down, p[0], p[1]);
            main(() -> invoke(hero(), "stopReveal"));  // what the reveal's end, and showing or hiding the view, run
            sidewaysAndLift(down, p, start);
            awaitRest();
            main(() -> require(shownReading() == start && Objects.equals(saved, pref(a)), "A swipe after a reset under the finger switched"));
            down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(30);
            main(() -> invoke(hero(), "stopReveal"));
            send(MotionEvent.ACTION_UP, down, p[0], p[1]);
            settle(); SystemClock.sleep(300); awaitRest();
            main(() -> require(shownReading() == start && Objects.equals(saved, pref(a)), "A lift after a reset under the finger tapped the tile"));
            checks.add("A reset under the finger (the reveal-end call), then a sideways swipe or a plain lift: no crash, no switch, no tap");
            // The cut touch lets Home's scroller go: an up or down move after the reset still scrolls Home.
            p = point("value");
            int sign = scrollRoom(), before = scrollY();
            float s = slop();
            down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(16);
            main(() -> invoke(hero(), "stopReveal"));
            for (int i = 1; i <= 10; i++) { send(MotionEvent.ACTION_MOVE, down, p[0], p[1] + sign * 1.2f * s * i); SystemClock.sleep(16); }
            SystemClock.sleep(120);
            send(MotionEvent.ACTION_UP, down, p[0], p[1] + sign * 12f * s);
            settle(); SystemClock.sleep(300); settle();
            int after = scrollY();
            require(Math.abs(after - before) >= 3 * s, "After a reset under the finger an up or down move did not scroll Home: " + before + " -> " + after);
            main(() -> require(shownReading() == start && cleanRest() && Objects.equals(saved, pref(a)), "A scroll after a reset under the finger switched"));
            checks.add("After a reset under the finger an up or down move scrolls Home (" + (after - before) + " px) and does not switch");
            ordinaryAfter("a reset under a finger");
        }
        if (wanted("reset-second")) {
            hero(50, null, 2);
            tap(); awaitRest();  // an ordinary touch at the centre first: the one the old gesture's coordinates came from
            int shown = readingNow(); String saved = callString(() -> pref(a));
            main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"));
            hero(50, null, 2);
            main(() -> require(!two(), "Alert off: still two readings"));
            float[] p = centre();
            long down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(30);
            main(() -> require(service.setChargeAlerts(alert70), "Could not turn the alert back on"));
            refreshNow(50, null, 2);  // the second reading arrives under the finger
            main(() -> require(two(), "The second reading did not arrive"));
            sidewaysAndLift(down, p, shown);
            awaitRest();
            main(() -> require(two() && shownReading() == shown && Objects.equals(saved, pref(a)), "A swipe after the second reading arrived under the finger switched"));
            // The same with a plain lift: no tap either.
            main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"));
            hero(50, null, 2);
            p = centre();
            down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(30);
            main(() -> require(service.setChargeAlerts(alert70), "Could not turn the alert back on"));
            refreshNow(50, null, 2);
            send(MotionEvent.ACTION_UP, down, p[0], p[1]);
            settle(); SystemClock.sleep(300); awaitRest();
            main(() -> require(two() && shownReading() == shown && Objects.equals(saved, pref(a)), "A lift after the second reading arrived under the finger tapped the tile"));
            checks.add("A touch begun with one reading, the second arriving under it, then a sideways swipe or a plain lift: no crash, no switch, no tap");
            ordinaryAfter("the second reading arrived under a finger");
        }
    }
    /** Ten sideways moves of most of the tile's width, away from the shown reading, then the lift. */
    private void sidewaysAndLift(long down, float[] p, int shown) throws Exception {
        float dx = (shown == LIMIT ? -1 : 1) * .8f * tileWidth();
        for (int i = 1; i <= 10; i++) { send(MotionEvent.ACTION_MOVE, down, p[0] + dx * i / 10f, p[1]); SystemClock.sleep(12); }
        send(MotionEvent.ACTION_UP, down, p[0] + dx, p[1]);
        settle(); SystemClock.sleep(300); settle();
    }
    /** The next ordinary tap and swipe after a cut-off touch both switch. */
    private void ordinaryAfter(String what) throws Exception {
        int start = readingNow();
        tap(); awaitRest();
        main(() -> require(shownReading() == other(start) && ChargeTimeReadingsPref(pref(a)) == other(start), "After " + what + ", the next tap did not switch"));
        swipe(start == LIMIT ? .8f : -.8f, false); awaitRest();
        main(() -> require(shownReading() == start && ChargeTimeReadingsPref(pref(a)) == start, "After " + what + ", the next swipe did not switch"));
        checks.add("After " + what + ": the next tap and swipe switch");
    }
    /** Charging readings handed to the hero with no wait, as a fresh estimate arrives. */
    private void refreshNow(double percent, Double limit, double amps) {
        Map<String, TelemetryHistory.Reading> values = readings(percent, limit, amps);
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
    }
    /** Waits, during the slowed reveal, until the shown label is inside the growing view; its centre on screen. */
    private float[] touchableDuringReveal() {
        float[] p = new float[2]; boolean[] ok = new boolean[1]; String[] why = {""};
        long end = SystemClock.uptimeMillis() + 6000;
        while (!ok[0] && SystemClock.uptimeMillis() < end) {
            main(() -> {
                View h = hero(); TextView c = caption(shownReading());
                if (field(h, "reveal") == null) { why[0] = "the reveal ended first"; return; }
                int[] hat = new int[2], cat = new int[2], sat = new int[2]; h.getLocationOnScreen(hat); c.getLocationOnScreen(cat);
                View s = scroller(); s.getLocationOnScreen(sat);
                int bottom = cat[1] + c.getHeight();
                why[0] = "label bottom " + bottom + ", view bottom " + (hat[1] + h.getHeight()) + ", Home bottom " + (sat[1] + s.getHeight());
                if (c.getHeight() > 0 && bottom + activity.ui().dp(4) <= hat[1] + h.getHeight() && cat[1] >= sat[1] && bottom <= sat[1] + s.getHeight() && c.isShown()) {
                    p[0] = cat[0] + c.getWidth() / 2f; p[1] = cat[1] + c.getHeight() / 2f; ok[0] = true;
                }
            });
            if (!ok[0]) SystemClock.sleep(15);
        }
        require(ok[0], "The label never came under a finger during the reveal: " + why[0]);
        return p;
    }

    private void verifyHundredLabel() throws Exception {
        String[] uncapped = new String[3];
        main(() -> { uncapped[0] = string("dashboard_charge_time_uncapped"); uncapped[1] = string("dashboard_charge_time_uncapped_short"); uncapped[2] = string("dashboard_charge_time_uncapped_compact"); });
        hero(50, null, 2);
        assertHundred("alert", uncapped);
        choose(ut5);
        hero(50, 80d, 2);
        assertHundred("limit 80%", uncapped);
        choose(a);
        main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"));
        hero(50, null, 2);
        main(() -> require(!two() && uncapped[0].equals(String.valueOf(caption(FULL).getContentDescription())),
                "One reading with no known cutoff no longer reads as before: " + caption(FULL).getContentDescription()));
        main(() -> require(service.setChargeAlerts(alert70), "Could not restore the alert"));
        hero(50, null, 2);
        checks.add("Two readings: the second is the plain time to 100% (label, every rung and speech), beside an alert and a limit; one reading unchanged");
    }
    private void assertHundred(String what, String[] uncapped) {
        main(() -> {
            require(two(), what + ": not two readings");
            String name = String.valueOf(caption(FULL).getContentDescription()), shown = String.valueOf(caption(FULL).getText());
            String plain = string("dashboard_charge_time_to", percent100()), compact = string("dashboard_charge_time_to_compact", percent100());
            require(name.equals(plain), what + ": the second reading is not named '" + plain + "': " + name);
            require(shown.isEmpty() || shown.equals(plain) || shown.equals(compact), what + ": the second reading's label is '" + shown + "'");
            for (String u : uncapped) require(!name.equals(u) && !shown.equals(u), what + ": the second reading still says " + u);
            require(String.valueOf(value(FULL).getContentDescription()).startsWith(plain + ","), what + ": the spoken second reading does not match: " + value(FULL).getContentDescription());
        });
    }
    private String percent100() { return string("fmt_percent", "100"); }
    private String string(String name, Object... format) {
        Context c = getTargetContext(); int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        require(id != 0, "No string " + name);
        return format.length == 0 ? c.getString(id) : c.getString(id, format);
    }

    private void verifyInterruptions() throws Exception {
        int start = readingNow();
        for (int i = 0; i < 7; i++) { tapQuick(); SystemClock.sleep(35); }
        awaitRest();
        main(() -> {
            require(shownReading() != start && cleanRest(), "Seven fast taps did not end cleanly on the other reading");
            require(ChargeTimeReadingsPref(pref(a)) == shownReading(), "The saved choice is not the shown one after fast taps");
        });
        // Hidden mid-switch (charging stops reporting), then shown again.
        int before = readingNow();
        scale("6");
        tapQuick(); SystemClock.sleep(200);
        main(() -> require(!atRest(), "The slowed switch was not running"));
        hero(50, null, 0);
        main(() -> require(cleanRest() && state("target") == other(before), "Hiding mid-switch left a half state"));
        scale("1");
        hero(50, null, 2);
        main(() -> require(shownReading() == other(before) && cleanRest(), "Shown again, the tile lost the choice"));
        // Re-parented mid-switch (a fold hands the header to another pane).
        int now = readingNow();
        scale("6");
        tapQuick(); SystemClock.sleep(200);
        main(() -> {
            require(!atRest(), "The slowed switch was not running");
            View hero = hero(); ViewGroup parent = (ViewGroup) hero.getParent(); int index = parent.indexOfChild(hero);
            ViewGroup.LayoutParams params = hero.getLayoutParams(); parent.removeView(hero); parent.addView(hero, index, params);
            require(cleanRest() && shownReading() == other(now), "A re-parent mid-switch left a half state");
        });
        scale("1");
        hero(50, null, 2);
        // A fresh estimate mid-switch: label and value stay together on each face.
        scale("6");
        tapQuick(); SystemClock.sleep(150);
        hero(52, null, 2);
        main(() -> require(!atRest(), "The slowed switch ended before the fresh estimate"));
        scale("1");
        awaitRest();
        main(() -> require(cleanRest() && String.valueOf(value(LIMIT).getContentDescription()).startsWith(String.valueOf(caption(LIMIT).getContentDescription())),
                "A fresh estimate mid-switch separated a label from its value"));
        checks.add("Fast taps, hiding, a re-parent and a fresh estimate mid-switch all end with one reading drawn, at rest on the last choice");
    }

    private void verifyPerScooterAndRestart() throws Exception {
        if (readingNow() != FULL) { tap(); awaitRest(); }
        main(() -> require("full".equals(pref(a)), "A's choice was not saved"));
        choose(b);
        main(() -> require(service.setChargeAlerts(alert70), "Could not save B's alert"));
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == LIMIT && pref(b) == null, "B inherited A's choice"));
        choose(a);
        hero(50, null, 2);
        main(() -> require(shownReading() == FULL, "A lost its choice after B"));
        relaunch();
        hero(50, null, 2);
        main(() -> require(shownReading() == FULL && cleanRest(), "A's choice did not survive an activity restart"));
        // The alert reached: one reading; the choice waits for two again.
        hero(75, null, 2);
        main(() -> require(!two() && face(FULL).isShown() && "full".equals(pref(a)), "A reached alert changed the saved choice"));
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == FULL, "The choice did not come back with two readings"));
        main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn A's alert off"));
        hero(50, null, 2);
        main(() -> require(!two() && "full".equals(pref(a)), "Turning the alert off lost the choice"));
        main(() -> require(service.setChargeAlerts(alert70), "Could not restore A's alert"));
        hero(50, null, 2);
        main(() -> require(two() && shownReading() == FULL, "The alert back on did not restore the choice"));
        checks.add("The choice is per scooter (B opens on its alert, A keeps 100%), survives an activity restart, and waits while only one reading exists");
    }

    private void verifyLimitScooter() throws Exception {
        choose(ut5);
        main(() -> require(settings().edit().putString("charge_alerts_" + ut5, alert70.encode()).commit(), "Could not seed an alert on the limit model"));
        hero(50, 80d, 2);
        main(() -> {
            require(two() && shownReading() == LIMIT, "A charge limit below 100% does not open on the limit");
            require(String.valueOf(caption(LIMIT).getContentDescription()).contains("80") && !String.valueOf(caption(LIMIT).getContentDescription()).contains("70"),
                    "The limit reading is not the scooter's 80%: " + caption(LIMIT).getContentDescription());
            require(String.valueOf(caption(FULL).getContentDescription()).contains("100"), "The other reading is not the time to 100%: " + caption(FULL).getContentDescription());
        });
        shot("limit-80");
        hero(50, 100d, 2);
        main(() -> require(!two(), "A limit at 100% shows two readings"));
        main(() -> settings().edit().remove("charge_alerts_" + ut5).commit());
        choose(a); hero(50, null, 2);
        checks.add("A scooter limit below 100% is the first reading (an alert saved on it is not used); at 100% one reading");
    }

    private void verifyReducedMotion() throws Exception {
        scale("0");
        int before = readingNow();
        tap();
        main(() -> require(cleanRest() && shownReading() == other(before) && field(tile(), "motion") == null, "Reduced motion still animated"));
        swipe(-.8f, false);
        main(() -> require(cleanRest() && shownReading() == before, "Reduced motion: a swipe did not switch at once"));
        scale("1");
        checks.add("With animations off the switch is instant, by tap and by swipe");
    }

    private void verifyAccessibility() throws Exception {
        if (readingNow() != LIMIT) { tap(); awaitRest(); }
        reveal();
        main(() -> {
            AccessibilityNodeInfo node = tile().createAccessibilityNodeInfo();
            require(node.isClickable() && node.isFocusable() && node.isVisibleToUser(), "The tile is not a reachable control");
            boolean click = false; for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) click |= action.getId() == AccessibilityNodeInfo.ACTION_CLICK;
            require(click, "The tile offers no click action");
            String spoken = String.valueOf(node.getContentDescription());
            require(spoken.equals(String.valueOf(value(LIMIT).getContentDescription())), "The tile does not speak the shown reading: " + spoken);
            for (int r : new int[] {LIMIT, FULL}) require(face(r).getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, "A face is read on its own");
        });
        ArrayList<String> heard = new ArrayList<>();
        String[] names = new String[2];
        main(() -> { names[0] = String.valueOf(caption(LIMIT).getContentDescription()); names[1] = String.valueOf(caption(FULL).getContentDescription()); });
        UiAutomation automation = getUiAutomation();
        automation.setOnAccessibilityEventListener(event -> {
            if (event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT) return;
            String text = String.valueOf(event.getText());
            if (text.contains(names[0]) || text.contains(names[1])) synchronized (heard) { heard.add(text); }
        });
        SystemClock.sleep(300);
        try {
            main(() -> tile().performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            awaitRest(); SystemClock.sleep(400);
            String expected = callString(() -> String.valueOf(value(FULL).getContentDescription()));
            synchronized (heard) {
                require(heard.size() == 1 && heard.get(0).contains(expected.substring(0, Math.min(12, expected.length()))),
                        "Switching did not announce the new reading once: " + heard + " expected " + expected);
            }
            heard.clear();
            hero(50, null, 2); hero(51, null, 2);
            SystemClock.sleep(300);
            synchronized (heard) { require(heard.isEmpty(), "Fresh values announced again: " + heard); }
        } finally { automation.setOnAccessibilityEventListener(null); }
        main(() -> require(String.valueOf(tile().getContentDescription()).equals(String.valueOf(value(FULL).getContentDescription())), "After switching the tile speaks the old reading"));
        checks.add("TalkBack: one focusable node speaking the shown reading, click action switches, one announcement per switch and none for fresh values, faces hidden");
        // The catalog follow-up names the switch: a labelled click, a "Switch time" action and the other time as state.
        int switchId = getTargetContext().getResources().getIdentifier("charge_time_switch", "id", getTargetContext().getPackageName());
        if (switchId != 0) {
            int before = readingNow();
            main(() -> {
                AccessibilityNodeInfo node = tile().createAccessibilityNodeInfo();
                CharSequence clickLabel = null, switchLabel = null;
                for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                    if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) clickLabel = action.getLabel();
                    if (action.getId() == switchId) switchLabel = action.getLabel();
                }
                require(clickLabel != null && clickLabel.length() > 0 && switchLabel != null && switchLabel.length() > 0,
                        "The switch is not named as a click and an action: " + clickLabel + " / " + switchLabel);
                if (Build.VERSION.SDK_INT >= 30) require(String.valueOf(node.getStateDescription()).contains(String.valueOf(caption(other(before)).getContentDescription())),
                        "The tile does not say there is another time: " + node.getStateDescription());
                require(tile().performAccessibilityAction(switchId, null), "The switch action was refused");
            });
            awaitRest();
            main(() -> require(shownReading() == other(before), "The switch action did not switch"));
            main(() -> tile().performAccessibilityAction(switchId, null)); awaitRest();
            checks.add("TalkBack names the switch (labelled click and a Switch time action) and the other time as state; the action switches");
        } else checks.add("No Switch time action in this build: the catalog follow-up adds it");
    }

    private void verifyFrames() throws Exception {
        if (readingNow() != LIMIT) { tap(); awaitRest(); }
        reveal();
        Rect box = callRect(() -> { int[] at = new int[2]; tile().getLocationOnScreen(at); return new Rect(at[0], at[1], at[0] + tile().getWidth(), at[1] + tile().getHeight()); });
        int[] heights = new int[1]; main(() -> heights[0] = tile().getHeight());
        scale("10");
        tapQuick();
        long end = SystemClock.uptimeMillis() + 2600; int n = 0; boolean steadyHeight = true;
        while (SystemClock.uptimeMillis() < end && n < 40) {
            Bitmap frame = screencap();
            saveCrop(frame, box, String.format(Locale.ROOT, "frames/tap-%02d", n++));
            int[] h = new int[1]; main(() -> h[0] = tile().getHeight()); steadyHeight &= h[0] == heights[0];
        }
        awaitRest();
        require(steadyHeight, "The tile's height changed during the switch");
        // A held drag at about a third of the way (slow scale keeps nothing else moving).
        int[] at = new int[2]; main(() -> tile().getLocationOnScreen(at));
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        float x = at[0] + box.width() / 2f, y = at[1] + box.height() / 2f;
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, x, y);
        for (int i = 1; i <= 8; i++) send(MotionEvent.ACTION_MOVE, down, x - i * 5 * density, y);
        SystemClock.sleep(250);
        saveCrop(screencap(), box, "frames/drag-held-40dp");
        main(() -> require(Boolean.TRUE.equals(field(tile(), "dragging")) && !atRest(), "The held drag is not dragging"));
        for (int i = 9; i <= 16; i++) send(MotionEvent.ACTION_MOVE, down, x - i * 5 * density, y);
        SystemClock.sleep(200);
        saveCrop(screencap(), box, "frames/drag-held-80dp");
        send(MotionEvent.ACTION_UP, down, x - 80 * density, y);
        SystemClock.sleep(150);
        saveCrop(screencap(), box, "frames/drag-released");
        awaitRest();
        scale("1");
        checks.add("Frames of one slowed tap (" + n + ") and a held drag saved under frames/; the tile's height stayed " + heights[0] + " px throughout");
    }

    private void verifyRotation() throws Exception {
        int before = readingNow();
        scale("6");
        tapQuick(); SystemClock.sleep(150);
        rotate(UiAutomation.ROTATION_FREEZE_90);
        SystemClock.sleep(2500); settle();
        scale("1");
        freeze(); hero(50, null, 2);
        main(() -> require(cleanRest() && shownReading() == other(before) && ChargeTimeReadingsPref(pref(a)) == other(before), "A rotation mid-switch did not end on the choice"));
        shot("rotated");
        rotate(UiAutomation.ROTATION_FREEZE_0);
        SystemClock.sleep(1500); settle(); freeze(); hero(50, null, 2);
        main(() -> require(cleanRest() && shownReading() == other(before), "Rotating back lost the choice"));
        checks.add("A rotation mid-switch rebuilds the screen at rest on the choice");
    }

    /** Every theme, text size and language at this size: labels whole, dots clear, one height for both readings. */
    private void verifyLayoutMatrix() throws Exception {
        String size = args.getString("size_label", "size");
        for (String locale : new String[] {"", "de", "pt-PT", "pt-BR"}) {
            for (String theme : new String[] {"dark", "light"}) for (String text : new String[] {"1", "1.4"}) {
                label = size + "-" + (locale.isEmpty() ? "en" : locale) + "-" + theme + "-" + text;
                MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle();
                seed(theme, text); setLocale(locale); launch();
                main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
                hero(50, null, 2);
                if (readingNow() != LIMIT) { tap(); awaitRest(); }
                assertLayout("limit");
                int[] limitBox = boxes();
                shot(label + "-limit");
                main(() -> tile().performClick()); awaitRest();
                assertLayout("full");
                int[] fullBox = boxes();
                require(Arrays.equals(limitBox, fullBox), label + ": the tile or power moved between readings " + Arrays.toString(limitBox) + " vs " + Arrays.toString(fullBox));
                shot(label + "-full");
                hero(50, null, 0.03);  // under ChargingEstimate.MINIMUM_ETA_CURRENT_A: the Low current pill on both faces
                main(() -> require(tags(FULL).isShown(), label + ": no state pill at low current"));
                assertLayout("full-low");
                shot(label + "-full-low");
                main(() -> tile().performClick()); awaitRest();
                assertLayout("limit-low");
                main(() -> require(service.setChargeAlerts(alert70.charge(false, 70)), "Could not turn the alert off"));
                hero(50, null, 2);
                assertLayout("one");
                shot(label + "-one");
                main(() -> require(service.setChargeAlerts(alert70), "Could not restore the alert"));
                checks.add(label + ": labels whole, dots clear of label and pill, both readings one height, power still");
            }
        }
        MainActivity old = activity; main(old::finish); SystemClock.sleep(800);
        setLocale("");
    }

    /** No broken or tiny text in the hero's tiles; the dots inside the card and clear of the caption's text and the pill. */
    private void assertLayout(String what) {
        main(() -> {
            ArrayList<String> problems = new ArrayList<>();
            ArrayDeque<View> stack = new ArrayDeque<>(); stack.push((View) field(hero(), "tiles"));
            float sp = getTargetContext().getResources().getDisplayMetrics().scaledDensity;
            while (!stack.isEmpty()) {
                View v = stack.pop();
                if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) stack.push(((ViewGroup) v).getChildAt(i));
                if (!(v instanceof TextView) || !v.isShown()) continue;
                TextView t = (TextView) v;
                TextFit.collectNarrowTextProblems(t, "charge time tile", problems);
                // The state pill keeps main's 11 sp chip style (tagChip), unchanged by the tile; everything else has a 12 sp floor.
                boolean pill = t == tags(LIMIT) || t == tags(FULL);
                if (t.length() > 0 && t.getTextSize() / sp < (pill ? 11 : 12) - .01f) problems.add("tiny text: " + t.getText() + " " + t.getTextSize() / sp);
            }
            require(problems.isEmpty(), label + " " + what + ": " + problems);
            if (!two()) return;
            View tile = tile();
            float r = activity.ui().dp(6) / 2f; int gap = activity.ui().dp(9); int width = Math.round(r * 2) + gap;
            boolean rtl = tile.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float left = rtl ? tile.getPaddingLeft() : tile.getWidth() - tile.getPaddingRight() - width;
            int shown = shownReading();
            TextView caption = caption(shown);
            Rect cap = TextFit.relativeBounds(caption, tile);
            android.text.Layout layout = caption.getLayout();
            float y = cap.top + caption.getTotalPaddingTop() + layout.getLineBaseline(0)
                    + (caption.getPaint().getFontMetrics().ascent + caption.getPaint().getFontMetrics().descent) / 2f;
            RectF dots = new RectF(left, y - r, left + width, y + r);
            require(dots.left >= 0 && dots.right <= tile.getWidth() && dots.top >= 0 && dots.bottom <= tile.getHeight(), label + " " + what + ": dots outside the card " + dots);
            for (int line = 0; line < layout.getLineCount(); line++) {
                if (layout.getLineEnd(line) == layout.getLineStart(line)) continue;
                float lineLeft = cap.left + caption.getTotalPaddingLeft() + layout.getLineLeft(line), lineRight = cap.left + caption.getTotalPaddingLeft() + layout.getLineRight(line);
                RectF text = new RectF(lineLeft, cap.top + caption.getTotalPaddingTop() + layout.getLineTop(line), lineRight, cap.top + caption.getTotalPaddingTop() + layout.getLineBottom(line));
                require(!RectF.intersects(inflate(dots, r), text), label + " " + what + ": dots touch the label '" + caption.getText() + "' " + dots + " " + text);
            }
            TextView pill = tags(shown);
            if (pill.isShown()) require(!RectF.intersects(inflate(dots, r), new RectF(TextFit.relativeBounds(pill, tile))), label + " " + what + ": dots touch the state pill");
        });
    }

    // ---- Restart mode -----------------------------------------------------------------------------------------

    private void restartCheck() throws Exception {
        main(() -> {
            for (ScooterProfiles.Profile p : new ScooterProfiles(getTargetContext()).all()) {
                if (p.name.equals("Synthetic GT3 Max A")) a = p.id;
                if (p.name.equals("Synthetic GT3 Max B")) b = p.id;
            }
            require(a != null && b != null, "Run the default mode first on this install");
            new ScooterProfiles(getTargetContext()).select(a);
        });
        launch();
        main(() -> require(service.setChargeAlerts(alert70), "Could not save the alert"));
        hero(50, null, 2);
        int expected = ChargeTimeReadingsPref(pref(a));
        main(() -> require(two() && shownReading() == expected && expected == FULL, "A's choice did not survive a new process: " + pref(a)));
        choose(b); hero(50, null, 2);
        main(() -> require(shownReading() == LIMIT, "B's default changed after a new process"));
        checks.add("In a new process A opens on its saved 100% and B on its alert");
    }

    // ---- Fixtures -------------------------------------------------------------------------------------------

    private void seed(String theme, String text) {
        require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                .putString("theme", theme).putFloat("text_scale", Float.parseFloat(text)).commit(), "Could not seed preferences");
    }
    private void setLocale(String tag) throws Exception {
        if (Build.VERSION.SDK_INT < 33) { require(tag.isEmpty(), "Languages need API 33"); return; }
        if (foundLocales == null) main(() -> foundLocales = getTargetContext().getSystemService(LocaleManager.class).getApplicationLocales());
        main(() -> getTargetContext().getSystemService(LocaleManager.class)
                .setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag)));
        SystemClock.sleep(1200); settle();
    }
    private void launch() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        // The first screens settle (service bound, scooter state read) before the fixture takes over.
        SystemClock.sleep(2500); settle();
        freeze();
    }
    private void relaunch() throws Exception {
        MainActivity old = activity;
        main(old::finish);
        SystemClock.sleep(800); settle();
        launch();
    }
    /** Home, with the activity's own refreshes unhooked so only the fixture readings reach the hero. */
    private void freeze() throws Exception {
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> {
            invoke(activity, "select", new Class<?>[]{String.class}, "home");
            ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
            service.unlisten((Runnable) field(activity, "serviceChanged"));
            invoke(activity, "cancelDeferredBodyRefresh");
            // Saving the choice is a settings change, which redraws Home from the app's own (empty) readings: in the
            // app that is one more refresh with the same readings (the mid-switch estimate case below covers it).
            ((SharedPreferences) field(activity, "preferences")).unregisterOnSharedPreferenceChangeListener(
                    (SharedPreferences.OnSharedPreferenceChangeListener) field(activity, "settingsChanged"));
        });
        settle();
    }
    private void choose(String id) throws Exception {
        main(() -> { service.profiles.select(id); service.ble.profileSelected(); });
        settle();
        main(() -> invoke(activity, "redraw"));  // the frozen app does not refresh by itself: Home sees the new scooter and rebuilds
        SystemClock.sleep(600); settle(); freeze();
        main(() -> require(id.equals(field(screen(), "profileId")), "The dashboard did not follow the scooter"));
    }
    /** Fresh synthetic charging readings; amps 0 means not charging (the hero hides). */
    private void hero(double percent, Double limit, double amps) throws Exception { hero(percent, limit, amps, true); }
    /** expectShown false: edit mode, where charging readings leave the view hidden. */
    private void hero(double percent, Double limit, double amps, boolean expectShown) throws Exception {
        Map<String, TelemetryHistory.Reading> values = readings(percent, limit, amps);
        main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
        settle(); SystemClock.sleep(amps > 0 ? 420 : 320); settle();
        boolean[] stray = new boolean[1];
        main(() -> stray[0] = expectShown && amps > 0 && !Boolean.TRUE.equals(field(hero(), "shown")));
        if (stray[0]) {  // a refresh of the app's own (empty) readings landed after the fixture's: once more, and say so
            Log.w("ChargeTimeTileValidation", "a stray refresh hid the fixture; refreshing again");
            main(() -> invoke(hero(), "refresh", new Class<?>[]{Map.class}, values));
            settle(); SystemClock.sleep(420); settle();
        }
        if (expectShown && amps > 0) main(() -> {
            View h = hero(); StringBuilder chain = new StringBuilder();
            for (View v = h; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) chain.append(v.getClass().getSimpleName()).append(':').append(v.getVisibility()).append(' ');
            require(h.isShown() && tile().isShown(), "The charging view did not show for the fixture readings: shown=" + field(h, "shown")
                    + " attached=" + h.isAttachedToWindow() + " charging=" + service.chargingEstimate(values).charging + " chain=" + chain);
        });
    }

    private static Map<String, TelemetryHistory.Reading> readings(double percent, Double limit, double amps) {
        long wall = System.currentTimeMillis();
        Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>();
        for (Object[] r : new Object[][]{{"charging_state", amps > 0 ? 1d : 0d}, {"voltage_v", 52d}, {"current_a", -amps}, {"battery_percent", percent}, {"rated_capacity_ah", 14.5d}})
            values.put((String) r[0], new TelemetryHistory.Reading((String) r[0], (String) r[0], "Synthetic fixture", String.valueOf(r[1]), "", (Double) r[1], wall, true));
        if (limit != null) values.put("charge_limit_percent", new TelemetryHistory.Reading("charge_limit_percent", "charge_limit_percent", "Synthetic fixture", limit + " %", "%", limit, wall, true));
        return values;
    }

    // ---- Touch ------------------------------------------------------------------------------------------------

    private float[] centre() throws Exception {
        reveal();
        float[] c = new float[2];
        main(() -> { int[] at = new int[2]; tile().getLocationOnScreen(at); c[0] = at[0] + tile().getWidth() / 2f; c[1] = at[1] + tile().getHeight() / 2f; });
        return c;
    }
    private void tap() throws Exception { tapQuick(); settle(); }
    private void tapQuick() throws Exception {
        float[] c = centre(); long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, c[0], c[1]); SystemClock.sleep(40); send(MotionEvent.ACTION_UP, down, c[0], c[1]);
        waitForIdleSync();
    }
    /** A sideways swipe of share x the tile's width; abandoned: it stops, holds still, then lifts. */
    private void swipe(float share, boolean abandoned) throws Exception {
        float[] c = centre(); int[] w = new int[1]; main(() -> w[0] = tile().getWidth());
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, c[0], c[1]);
        for (int i = 1; i <= 10; i++) { send(MotionEvent.ACTION_MOVE, down, c[0] + share * w[0] * i / 10f, c[1]); SystemClock.sleep(12); }
        if (abandoned) { for (int i = 0; i < 12; i++) { send(MotionEvent.ACTION_MOVE, down, c[0] + share * w[0], c[1]); SystemClock.sleep(25); } }
        send(MotionEvent.ACTION_UP, down, c[0] + share * w[0], c[1]);
        settle();
    }
    /** A finger moving down across the tile and lifting on it: a scroll or nothing, never a tap. */
    private void verticalPass() throws Exception {
        float[] c = centre(); float density = getTargetContext().getResources().getDisplayMetrics().density;
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, c[0], c[1] - 10 * density);
        for (int i = 1; i <= 6; i++) { send(MotionEvent.ACTION_MOVE, down, c[0] + i * density, c[1] - 10 * density + i * 4 * density); SystemClock.sleep(16); }
        send(MotionEvent.ACTION_UP, down, c[0] + 6 * density, c[1] + 14 * density);
        settle();
    }
    /** Where a touch starts, on screen: the shown reading's label or value text, its state pill, or the first dot. */
    private float[] point(String what) throws Exception {
        reveal();
        float[] p = new float[2];
        main(() -> {
            View tile = tile(); int[] at = new int[2]; tile.getLocationOnScreen(at);
            int shown = shownReading();
            if (what.equals("dots")) {
                float r = activity.ui().dp(6) / 2f; int gap = activity.ui().dp(9); int width = Math.round(r * 2) + gap;
                boolean rtl = tile.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                TextView caption = caption(shown); Rect cap = TextFit.relativeBounds(caption, tile);
                android.text.Layout layout = caption.getLayout();
                float y = cap.top + caption.getTotalPaddingTop() + layout.getLineBaseline(0)
                        + (caption.getPaint().getFontMetrics().ascent + caption.getPaint().getFontMetrics().descent) / 2f;
                p[0] = at[0] + (rtl ? tile.getPaddingLeft() + width - r : tile.getWidth() - tile.getPaddingRight() - width + r);
                p[1] = at[1] + y;
                return;
            }
            TextView t = what.equals("label") ? caption(shown) : what.equals("value") ? value(shown) : tags(shown);
            require(t.isShown() && t.getLayout() != null && t.length() > 0, "Nothing to touch: the " + what + " is not shown");
            int[] v = new int[2]; t.getLocationOnScreen(v);
            android.text.Layout layout = t.getLayout();
            p[0] = v[0] + t.getTotalPaddingLeft() + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f;
            p[1] = v[1] + t.getTotalPaddingTop() + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f;
            require(p[0] >= at[0] && p[0] < at[0] + tile.getWidth() && p[1] >= at[1] && p[1] < at[1] + tile.getHeight(), "The " + what + " is outside the tile");
        });
        return p;
    }
    private void tapAt(float[] p) {
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, p[0], p[1]); SystemClock.sleep(40); send(MotionEvent.ACTION_UP, down, p[0], p[1]);
        waitForIdleSync(); settle();
    }
    /** A drag of (dx, dy) px from p in ten moves; abandoned: it holds still, then lifts. */
    private void drag(float[] p, float dx, float dy, boolean abandoned) {
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, p[0], p[1]);
        for (int i = 1; i <= 10; i++) { send(MotionEvent.ACTION_MOVE, down, p[0] + dx * i / 10f, p[1] + dy * i / 10f); SystemClock.sleep(12); }
        if (abandoned) for (int i = 0; i < 12; i++) { send(MotionEvent.ACTION_MOVE, down, p[0] + dx, p[1] + dy); SystemClock.sleep(25); }
        send(MotionEvent.ACTION_UP, down, p[0] + dx, p[1] + dy);
        settle();
    }
    /** Holds a finger still at p past the long-press time; returns the tooltip shown then (or what windows there were). */
    private String longPress(float[] p) {
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, down, p[0], p[1]);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 700);
        String shown = tooltip();
        send(MotionEvent.ACTION_UP, down, p[0], p[1]);
        settle();
        long end = SystemClock.uptimeMillis() + 6000;  // a touch tooltip hides 1.5 s after the lift
        while (SystemClock.uptimeMillis() < end && !tooltip().startsWith("none")) SystemClock.sleep(150);
        return shown;
    }
    /**
     * The text of a tooltip on screen: another window of the app's (not the activity's) holding a single shown text.
     * A tooltip window takes no touch, so accessibility does not list it; WindowInspector (API 29) lists every window.
     */
    private String tooltip() {
        AtomicReference<String> out = new AtomicReference<>();
        main(() -> {
            StringBuilder seen = new StringBuilder("none:");
            View decor = activity.getWindow().getDecorView();
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                if (root == decor || !root.isAttachedToWindow() || !root.isShown()) continue;
                ArrayList<String> texts = new ArrayList<>(); texts(root, texts);
                seen.append(' ').append(root.getClass().getSimpleName()).append(texts.size() > 3 ? "[" + texts.size() + " texts]" : texts.toString());
                if (texts.size() == 1 && out.get() == null) out.set(texts.get(0));
            }
            if (out.get() == null) out.set(seen.toString());
        });
        return out.get();
    }
    private void texts(View view, List<String> out) {
        if (!view.isShown()) return;
        if (view instanceof TextView && ((TextView) view).length() > 0) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) texts(((ViewGroup) view).getChildAt(i), out);
    }
    private android.widget.ScrollView scroller() {
        View v = tile();
        while (v != null && !(v instanceof android.widget.ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
        require(v != null, "No scroller above the tile");
        return (android.widget.ScrollView) v;
    }
    private int scrollY() { int[] y = new int[1]; main(() -> y[0] = scroller().getScrollY()); return y[0]; }
    /** Which way a finger can move to scroll Home from here (1 down, -1 up), with room for the checks. */
    private int scrollRoom() {
        int[] out = new int[1];
        main(() -> {
            android.widget.ScrollView s = scroller(); View child = s.getChildAt(0);
            int up = s.getScrollY(), downRoom = child.getHeight() - (s.getHeight() - s.getPaddingTop() - s.getPaddingBottom()) - s.getScrollY();
            require(Math.max(up, downRoom) >= 6 * slop(), "Home cannot scroll far enough here for the check: up " + up + ", down " + downRoom);
            out[0] = up >= downRoom ? 1 : -1;
        });
        return out[0];
    }
    private float slop() { return ViewConfiguration.get(getTargetContext()).getScaledTouchSlop(); }
    private int tileWidth() { int[] w = new int[1]; main(() -> w[0] = tile().getWidth()); return w[0]; }
    private void send(int action, long down, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { sendPointerSync(e); } finally { e.recycle(); }
    }
    private void reveal() throws Exception {
        main(() -> {
            View view = tile(), parent = view;
            while (parent != null && !(parent instanceof android.widget.ScrollView)) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            if (parent == null) return;
            Rect r = new Rect(); view.getDrawingRect(r); ((ViewGroup) parent).offsetDescendantRectToMyCoords(view, r);
            parent.scrollTo(0, Math.max(0, r.bottom - parent.getHeight() + activity.ui().dp(24)));
        });
        settle();
    }

    // ---- Tile state -------------------------------------------------------------------------------------------

    private Object screen() { return field(activity, "screen"); }
    private View hero() { return (View) field(screen(), "chargingHero"); }
    private View tile() { return (View) field(hero(), "timeTile"); }
    private View power() { return (View) ((TextView) field(hero(), "power")).getParent(); }
    private View face(int r) { return ((View[]) field(tile(), "faces"))[r]; }
    private TextView caption(int r) { return ((TextView[]) field(tile(), "captions"))[r]; }
    private TextView value(int r) { return ((TextView[]) field(tile(), "values"))[r]; }
    private TextView tags(int r) { return ((TextView[]) field(tile(), "tags"))[r]; }
    private boolean two() { return Boolean.TRUE.equals(field(tile(), "two")); }
    private int state(String name) { return (Integer) field(field(tile(), "state"), name); }
    private boolean atRest() { return Boolean.TRUE.equals(invoke(field(tile(), "state"), "atRest")); }
    private int shownReading() { return two() ? state("resting") : FULL; }
    private int readingNow() { int[] r = new int[1]; main(() -> r[0] = shownReading()); return r[0]; }
    private static int other(int r) { return r == FULL ? LIMIT : FULL; }
    private static int ChargeTimeReadingsPref(String stored) { return "full".equals(stored) ? FULL : LIMIT; }
    /** At rest: no animator, exactly the shown face drawn, whole and in place; the other hidden. */
    private boolean cleanRest() {
        if (!atRest() || field(tile(), "motion") != null) return false;
        int shown = shownReading();
        View on = face(shown), off = face(other(shown));
        boolean otherHidden = two() ? off.getVisibility() == View.INVISIBLE : off.getVisibility() == View.GONE;
        return on.getVisibility() == View.VISIBLE && on.getAlpha() == 1f && on.getTranslationX() == 0f && otherHidden;
    }
    private void awaitRest() { await(this::cleanRest, "The tile did not come to rest"); }
    private int[] boxes() {
        int[] out = new int[6];
        main(() -> {
            Rect t = TextFit.relativeBounds(tile(), root()), p = TextFit.relativeBounds(power(), root());
            out[0] = t.left; out[1] = t.top; out[2] = t.height(); out[3] = p.left; out[4] = p.top; out[5] = p.height();
        });
        return out;
    }
    private String pref(String profile) { return settings().getString("charge_time_reading_" + profile, null); }
    private static RectF inflate(RectF r, float by) { return new RectF(r.left - by, r.top - by, r.right + by, r.bottom + by); }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

    /** The device settings as this run found them, read just before its first change of each; null: never changed. */
    private String foundScale, foundAutoRotate, foundUserRotation;
    private LocaleList foundLocales;
    private void restoreFound() {
        if (foundScale != null) try {
            shell(foundScale.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + foundScale);
        } catch (Throwable ignored) {}
        if (foundAutoRotate != null) try {
            int user = foundUserRotation.matches("[0-3]") ? Integer.parseInt(foundUserRotation) : 0;
            getUiAutomation().setRotation("1".equals(foundAutoRotate) ? UiAutomation.ROTATION_UNFREEZE : user);  // ROTATION_FREEZE_n == n
            if (foundUserRotation.matches("[0-3]")) shell("settings put system user_rotation " + foundUserRotation);
            if (foundAutoRotate.matches("[01]")) shell("settings put system accelerometer_rotation " + foundAutoRotate);
        } catch (Throwable ignored) {}
        if (foundLocales != null) try {
            LocaleList back = foundLocales;
            main(() -> getTargetContext().getSystemService(LocaleManager.class).setApplicationLocales(back));
        } catch (Throwable ignored) {}
    }
    private void rotate(int rotation) throws Exception {
        if (foundAutoRotate == null) {
            foundUserRotation = shell("settings get system user_rotation").trim();
            foundAutoRotate = shell("settings get system accelerometer_rotation").trim();
        }
        getUiAutomation().setRotation(rotation);
    }
    private void scale(String value) throws Exception {
        if (foundScale == null) foundScale = shell("settings get global animator_duration_scale").trim();
        shell("settings put global animator_duration_scale " + value);
        float target = Float.parseFloat(value);
        await(() -> android.animation.ValueAnimator.getDurationScale() == target, "Animator scale did not reach " + value);
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            StringBuilder out = new StringBuilder(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) >= 0) out.append(new String(buffer, 0, n, "UTF-8"));
            return out.toString();
        }
    }
    private Bitmap screencap() throws Exception {
        ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand("screencap -p");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
            Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
            require(bitmap != null, "screencap returned no image");
            return bitmap;
        }
    }
    private void saveCrop(Bitmap frame, Rect box, String name) throws Exception {
        File file = new File(dir(), name + ".png"); require(file.getParentFile().isDirectory() || file.getParentFile().mkdirs(), "Frame directory missing");
        Rect clip = new Rect(box); clip.inset(-activity.ui().dp(8), -activity.ui().dp(8));
        require(clip.intersect(0, 0, frame.getWidth(), frame.getHeight()), "Tile is off screen");
        Bitmap part = Bitmap.createBitmap(frame, clip.left, clip.top, clip.width(), clip.height());
        try (FileOutputStream out = new FileOutputStream(file)) { part.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { part.recycle(); frame.recycle(); }
        TextCensus.writeView(this, file, tile());  // the crop shows the tile: census its text as drawn (both faces mid-switch)
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-time-tile-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(500); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    private void admitEmulator() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
    }
    private void admit() {
        admitEmulator();
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
    }
    private void assertNoRadio() {
        main(() -> {
            for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(service.ble, name) == null, "Validation touched " + name);
            require(((Collection<?>) field(service.ble, "queue")).isEmpty(), "Validation queued radio bytes");
        });
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
    private interface Call<T> { T get(); }
    private String callString(Call<String> call) { AtomicReference<String> out = new AtomicReference<>(); main(() -> out.set(call.get())); return out.get(); }
    private Rect callRect(Call<Rect> call) { AtomicReference<Rect> out = new AtomicReference<>(); main(() -> out.set(call.get())); return out.get(); }
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
    private void setField(Object target, String name, Object value) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
