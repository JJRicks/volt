package com.jjricks.volt;

import android.Manifest;
import android.app.Instrumentation;
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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
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
import java.util.function.Consumer;

/**
 * Disposable empty emulator, radio off. Home's Widgets charging view's draggable bar (ChargeBar) on the developer
 * simulator: a UT5 MAX charging with a charge limit, then a model with Volt's charge alerts. Every drag is a real
 * injected touch. Writes go only to the simulator; nothing reaches a radio.
 *
 * Default mode: at rest the bar is the old floor line with a mark at the limit and the stage keeps its height; a drag
 * thickens the bar, follows the finger in steps, shows the value, runs no layout pass, scrolls nothing and writes nothing
 * until the lift, which writes the limit once through the Charge limit control; a drag back to the start, a touch away
 * from the mark, a vertical swipe (which scrolls Home), ACTION_CANCEL, a second finger and charging ending mid-drag
 * write nothing and put the mark back; edit mode takes no touch; a write the scooter doesn't keep, or rejects, springs
 * back with the choice sheet's words; while a write is pending the mark is dimmed, labelled and takes no drag (said
 * once); TalkBack's node and its increase and decrease; right to left mirrors the drag; with animations off the bar
 * thickens at once; the alert model's mark is the alert level and a drag saves it in the alert settings.
 * Mode layout: both themes at 1x and 1.4x text at the emulator's size: the mark and the value label inside the bar at
 * both ends of the track and clear of the percentage.
 *
 * <p>Argument home=clean runs every mode on Clean Home's charging view instead (the owner's request of 19 Nov 2026: Widgets'
 * draggable bar and two-reading time tile ported to Clean), where the bar is Clean's charge track along the stage's foot:
 * the same bar class, so the same cases, but edit mode (Clean's charging view has none) and the stage-height check
 * (Clean's stage takes the top area's slot). The default mode on Clean also checks the time tile: two readings with the
 * limit's first, a tap switches and saves the choice, and the other Home style opens on the same choice (one setting).
 * Mode look (any build, for before and after pictures): the charging view at rest at the emulator's size in the given
 * theme and text size, then, where the build has them on that Home, each of the time tile's readings and the bar mid-drag.
 */
public final class ChargeBarValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private boolean admitted;
    private String ut5, label = "";
    /** Clean Home's charging view (home=clean) rather than Widgets'. */
    private boolean clean;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    /** The bar's host wrapped: every limit write and every toast the bar asks for. */
    private final ArrayList<Integer> writes = new ArrayList<>();
    private final ArrayList<String> toasts = new ArrayList<>();
    /** null: pass writes to the app. Else what the wrapper does instead (hold, or accept without writing). */
    private String trap;
    private Consumer<Boolean> held;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String mode = args.getString("mode", "all");
        try {
            admit(); admitted = true;
            clean = "clean".equals(args.getString("home", "widgets"));
            seed(args.getString("theme", "dark"), args.getString("text_scale", "1"));
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile pu = profiles.create("Synthetic UT5 MAX", "000000000000", 0); pu.serial = "X2538-SYNTHETIC"; profiles.update(pu); ut5 = pu.id;
                profiles.select(ut5); OnboardingScreen.markDone(getTargetContext());
            });
            launch();
            if (mode.equals("layout")) verifyLayoutMatrix();
            else if (mode.equals("look")) look();
            else {
                verifyRest();
                if (clean) verifyCleanTile();
                verifyDragCommitsOnce();
                verifyNothingWithoutADrag();
                verifyVerticalScrolls();
                verifyCancels();
                verifyEditMode();
                verifyRefusedSpringsBack();
                verifyPendingNotDraggable();
                verifyAccessibility();
                verifyRtl();
                verifyReducedMotion();
                verifyAlert();
                verifyNoUntestedNote();
                verifyKeys();
            }
            assertNoRadio();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            restoreFound();
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { if (service != null) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Cases ------------------------------------------------------------------------------------------------

    private void verifyRest() throws Exception {
        limit(85);
        main(() -> {
            ChargeBar b = bar();
            require(b.kind == ChargeBarDrag.LIMIT && b.drawn == 85f && b.lift == 0f, "The mark is not the 85% limit at rest: kind " + b.kind + " drawn " + b.drawn + " lift " + b.lift);
            require(b.gate == ChargeBarDrag.Gate.OK && b.blocked == null, "The simulated limit is not draggable: " + b.gate);
            require(Arrays.equals(b.allowed, new int[] {80, 85, 90, 95, 100}), "The limit's choices: " + Arrays.toString(b.allowed));
            View stage = (View) field(hero(), "stage");
            if (!clean) require(stage.getHeight() == stage.getMinimumHeight(), "The bar made the stage taller: " + stage.getHeight() + " vs " + stage.getMinimumHeight());
            else {
                // Clean: the bar is the track along the stage's foot, whole inside the stage, fully shown at rest.
                require(b.getParent() == stage && b.getBottom() == stage.getHeight() && b.getTop() >= 0, "Clean's bar is not along the stage's foot: " + b.getTop() + ".." + b.getBottom() + " in " + stage.getHeight());
                require(b.getAlpha() == 1f && b.getVisibility() == View.VISIBLE, "Clean's bar is not fully shown at rest: alpha " + b.getAlpha());
                require(b.xAt(0) >= 0 && b.xAt(100) <= b.getWidth(), "Clean's track runs past the bar");
                // Taller than its least 48 dp only as far as the room under the percentage allows.
                TextView percent = (TextView) field(hero(), "percent");
                require(onScreen(b).top >= onScreen(percent).bottom - 1 || b.getHeight() == activity.ui().dp(48),
                        "Clean's bar reaches up over the percentage: bar " + onScreen(b) + ", percentage " + onScreen(percent));
            }
            require(b.getHeight() >= activity.ui().dp(48), "The bar is under 48 dp: " + b.getHeight());
            require(b.labelBox.isEmpty(), "A value label shows at rest");
        });
        shot("rest");
        checks.add(clean ? "Clean at rest: the track along the stage's foot with a mark at the 85% limit, fully shown; no label"
                : "At rest: the floor line with a mark at the 85% limit; the stage keeps its height; no label");
    }

    /**
     * Clean's time tile is Widgets' (ChargeTimeTile): beside the simulated UT5 MAX's 85% limit it holds two readings,
     * the time to 85% first and the time to 100%, dots and all; a tap switches and saves the choice in the one setting
     * Widgets reads, so the other Home style opens on it; TalkBack's node is the same switchable control.
     */
    private void verifyCleanTile() throws Exception {
        limit(85);
        main(() -> {
            View tile = tile();
            require(Boolean.TRUE.equals(field(tile, "two")), "Clean's tile does not hold two readings beside an 85% limit");
            TextView[] captions = (TextView[]) field(tile, "captions");
            require(String.valueOf(captions[0].getContentDescription()).contains("85") && String.valueOf(captions[1].getContentDescription()).contains("100"),
                    "Clean's readings are not the time to 85% and to 100%: " + captions[0].getContentDescription() + " / " + captions[1].getContentDescription());
            require(tile.isClickable() && tile.isFocusable(), "Clean's tile is not a control with two readings");
            AccessibilityNodeInfo node = tile.createAccessibilityNodeInfo();
            require(node.isClickable() && String.valueOf(node.getContentDescription()).length() > 0, "Clean's tile has no spoken reading");
            node.recycle();
        });
        String key = ChargeTimeReadings.KEY + ut5;
        int before = tileReading();
        shot("tile-" + (before == 0 ? "limit" : "full"));
        main(() -> tile().performClick());
        await(() -> shownReading() != before && tileAtRest(), "A tap did not switch Clean's tile");
        String saved = settings().getString(key, null);
        require(ChargeTimeReadings.encode(1 - before).equals(saved), "Clean's switch did not save the choice: " + saved);
        shot("tile-" + (before == 0 ? "full" : "limit"));
        // The other Home style reads the same setting: Widgets opens on the reading Clean left showing.
        relaunchStyle("widgets");
        await(() -> shownReading() == 1 - before, "Widgets did not open on the reading Clean saved (" + saved + ")");
        main(() -> tile().performClick());
        await(() -> shownReading() == before && tileAtRest(), "A tap did not switch Widgets' tile back");
        relaunchStyle("clean");
        await(() -> shownReading() == before, "Clean did not open on the reading Widgets saved");
        checks.add("Clean's time tile is Widgets': two readings beside an 85% limit (85% first, then 100%), a tap switches and saves it, "
                + "and each Home style opens on the reading the other left (one setting per scooter)");
    }
    private View tile() { return (View) field(hero(), "timeTile"); }
    /** The reading the tile shows (0 the limit's, 1 the time to 100%), on the main thread; reading() from the test's. */
    private int shownReading() { View t = tile(); return Boolean.TRUE.equals(field(t, "two")) ? (Integer) field(field(t, "state"), "resting") : 1; }
    private int tileReading() { int[] r = new int[1]; main(() -> r[0] = shownReading()); return r[0]; }
    private boolean tileAtRest() { return Boolean.TRUE.equals(invoke(field(tile(), "state"), "atRest")) && field(tile(), "motion") == null; }
    /** Home in the other style: the activity again with home_style set, the simulator still charging. */
    private void relaunchStyle(String style) throws Exception {
        MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle();
        require(settings().edit().putString("home_style", style).commit(), "Could not set the Home style");
        boolean was = clean; clean = style.equals("clean");
        try { launch(); } catch (RuntimeException e) { clean = was; throw e; }
        limit(85);
    }

    /** Pictures only (any build): the view at rest, each reading of the time tile, and the bar mid-drag, where they exist. */
    private void look() throws Exception {
        String size = args.getString("size_label", "size"), theme = args.getString("theme", "dark"), text = args.getString("text_scale", "1");
        String name = (clean ? "clean" : "widgets") + "-" + size + "-" + theme + "-" + text;
        boolean hasBar = field(hero(), "bar") != null;
        if (hasBar) limit(85);
        else { main(() -> service.ble.set("charge_limit", 85)); SystemClock.sleep(1500); redraw(); }
        shot(name + "-rest");
        View t = tile();
        boolean[] two = new boolean[1]; main(() -> two[0] = Boolean.TRUE.equals(field(t, "two")) && t.isClickable());
        if (two[0]) {
            int first = tileReading();
            shot(name + "-tile-" + (first == 0 ? "limit" : "full"));
            main(() -> tile().performClick());
            await(() -> shownReading() != first && tileAtRest(), "The tile did not switch");
            shot(name + "-tile-" + (first == 0 ? "full" : "limit"));
            main(() -> tile().performClick());
            await(() -> shownReading() == first && tileAtRest(), "The tile did not switch back");
        }
        if (hasBar) {
            Finger f = new Finger(mark());
            f.move(stepPx() + slop() * .5f, 0, 8);
            SystemClock.sleep(300); waitForIdleSync();
            shot(name + "-bar-drag");
            f.send(MotionEvent.ACTION_CANCEL, f.x, f.y); settle();
        }
        checks.add(name + ": pictures at rest" + (two[0] ? ", on each reading" : "") + (hasBar ? ", mid-drag" : "") + " (" + (hasBar ? "bar" : "no bar") + ", " + (two[0] ? "two readings" : "one reading") + ")");
    }
    private void redraw() throws Exception { main(() -> activity.redraw()); settle(); SystemClock.sleep(200); settle(); }

    private void verifyDragCommitsOnce() throws Exception {
        limit(85);
        int[] layouts = new int[1];
        ViewTreeObserver.OnGlobalLayoutListener counter = () -> layouts[0]++;
        float step = stepPx();
        int[] felt = new int[2];
        main(() -> { felt[0] = bar().ticks; felt[1] = bar().confirms; });
        Finger f = new Finger(mark());
        f.move(slop() + 2, 0, 4);
        f.move(step * 2, 0, 8);
        SystemClock.sleep(260); waitForIdleSync();
        main(() -> bar().getViewTreeObserver().addOnGlobalLayoutListener(counter));
        f.move(step * 2 + 3, ui(2), 3);
        SystemClock.sleep(120); waitForIdleSync();
        main(() -> {
            ChargeBar b = bar();
            require(b.lift == 1f, "The bar did not thicken under the finger: " + b.lift);
            require(b.drawn == 95f, "The mark did not follow two steps: " + b.drawn);
            require(!b.labelBox.isEmpty(), "No value label while dragging");
            require(service.ble.featureValue("charge_limit") == 85 && writes.isEmpty(), "A value was written during the drag");
            // Two steps snapped (85 to 90 to 95): two ticks; the last small move stayed on 95 and played nothing.
            require(b.ticks - felt[0] == 2, "Ticks for two steps: " + (b.ticks - felt[0]));
            require(b.confirms == felt[1], "A confirm played before the lift");
        });
        shot("dragging");
        main(() -> bar().getViewTreeObserver().removeOnGlobalLayoutListener(counter));
        require(layouts[0] == 0, "The drag ran " + layouts[0] + " layout passes");
        f.up();
        await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 95, "The release did not set 95");
        // A slow machine may still be easing the bar back: wait for rest rather than a fixed time.
        await(() -> bar().lift == 0f && bar().drawn == 95f && bar().labelBox.isEmpty(), "The bar did not come to rest on 95");
        SystemClock.sleep(300); settle();
        main(() -> require(writes.equals(Collections.singletonList(95)), "The release wrote " + writes + ", not 95 once"));
        main(() -> require(bar().confirms - felt[1] == 1, "The release of a changed value did not confirm once: " + (bar().confirms - felt[1])));
        checks.add("A real drag: thickens, follows in steps (85 to 95) with one tick per step, shows the value, no layout pass, nothing written until the lift; the lift writes 95 once with one confirm");
    }

    private void verifyNothingWithoutADrag() throws Exception {
        limit(85);
        float step = stepPx();
        Finger f = new Finger(mark());
        f.move(step * 2, 0, 6); f.move(ui(1), 0, 6); f.up();
        settle();
        main(() -> require(writes.isEmpty() && bar().drawn == 85f, "A drag back to the start wrote " + writes));
        float[] away = point(40);
        f = new Finger(away);
        f.move(step * 3, 0, 8); f.up(); settle();
        main(() -> require(writes.isEmpty() && bar().drawn == 85f, "A drag away from the mark moved it or wrote " + writes));
        f = new Finger(away); f.up(); settle();
        main(() -> require(writes.isEmpty() && bar().drawn == 85f, "A tap on the bar jumped the mark"));
        f = new Finger(mark()); SystemClock.sleep(80); f.up(); settle();
        main(() -> require(writes.isEmpty() && bar().drawn == 85f, "A tap on the mark wrote " + writes));
        checks.add("A drag back to the start, a drag or tap away from the mark and a tap on it write nothing and don't move it");
    }

    private void verifyVerticalScrolls() throws Exception {
        limit(85);
        int direction = scrollRoom();
        int before = scrollY();
        Finger f = new Finger(mark());
        f.move(ui(2), direction * slop() * 6, 10); f.up(); settle();
        int after = scrollY();
        main(() -> require(writes.isEmpty() && bar().drawn == 85f && bar().lift == 0f, "A vertical swipe on the mark moved it or wrote " + writes));
        require(after != before, "A vertical swipe starting on the mark did not scroll Home (" + before + ")");
        main(() -> scroller().scrollTo(0, before)); settle();
        checks.add("A vertical swipe starting on the mark scrolls Home and leaves the mark");
    }

    private void verifyCancels() throws Exception {
        limit(85);
        float step = stepPx();
        int[] confirmed = new int[1];
        main(() -> confirmed[0] = bar().confirms);
        Finger f = new Finger(mark());
        f.move(step * 2, 0, 8);
        f.send(MotionEvent.ACTION_CANCEL, f.x, f.y);
        settle(); SystemClock.sleep(300);
        main(() -> require(writes.isEmpty() && bar().drawn == 85f && bar().lift == 0f, "ACTION_CANCEL left the mark at " + bar().drawn + " or wrote " + writes));
        f = new Finger(mark());
        f.move(step * 2, 0, 8);
        f.secondFinger();
        f.move(step * 3, 0, 4);
        f.up(); settle(); SystemClock.sleep(300);
        main(() -> require(writes.isEmpty() && bar().drawn == 85f, "A second finger did not cancel the drag: " + bar().drawn + " " + writes));
        main(() -> require(bar().confirms == confirmed[0], "A cancelled drag played a confirm"));
        f = new Finger(mark());
        f.move(step * 2, 0, 8);
        main(() -> { service.ble.setSimulationCharging(false); service.ble.tickSimulation(); activity.redraw(); });
        settle();
        f.up(); settle();
        main(() -> require(writes.isEmpty(), "Charging ending mid-drag still wrote " + writes));
        main(() -> { service.ble.setSimulationCharging(true); service.ble.tickSimulation(); });
        limit(85);
        main(() -> require(bar().drawn == 85f && service.ble.featureValue("charge_limit") == 85, "After charging ended mid-drag the limit is " + bar().drawn));
        checks.add("ACTION_CANCEL, a second finger and charging ending mid-drag write nothing; the mark goes back");
    }

    private void verifyEditMode() throws Exception {
        if (clean) { checks.add("Edit mode: not on Clean, whose charging view has no edit mode"); return; }
        limit(85);
        main(() -> setField(screen(), "editing", true));
        try {
            Finger f = new Finger(mark());
            f.move(stepPx() * 2, 0, 8); f.up(); settle();
            main(() -> require(writes.isEmpty() && bar().drawn == 85f && bar().lift == 0f, "The bar moved or wrote while editing"));
        } finally { main(() -> setField(screen(), "editing", false)); }
        checks.add("Edit mode: the bar takes no touch");
    }

    private void verifyRefusedSpringsBack() throws Exception {
        limit(85);
        trap = "accept";
        try {
            dragSteps(2);
            await(() -> !bar().writePending(), "The unkept write did not resolve");
            SystemClock.sleep(400); settle();
            main(() -> {
                require(bar().drawn == 85f && service.ble.featureValue("charge_limit") == 85, "An unkept write did not spring back: " + bar().drawn);
                require(toasts.contains(M.mainChipNotConfirmed().render()), "An unkept write did not say Not confirmed: " + toasts);
            });
            toasts.clear();
            main(() -> putResult("Rejected by scooter"));
            dragSteps(-1);
            await(() -> !bar().writePending(), "The rejected write did not resolve");
            SystemClock.sleep(400); settle();
            main(() -> {
                require(bar().drawn == 85f, "A rejected write did not spring back: " + bar().drawn);
                require(toasts.contains(M.mainChipRejected().render()), "A rejected write did not say Rejected: " + toasts);
            });
        } finally { trap = null; toasts.clear(); main(() -> putResult(null)); }
        trap = "refuse";
        try {
            dragSteps(1); settle(); SystemClock.sleep(400);
            main(() -> require(bar().drawn == 85f && !bar().writePending(), "A refused write did not spring back"));
        } finally { trap = null; }
        writes.clear();
        checks.add("A write the scooter doesn't keep says Not confirmed, a rejected one Rejected, a refused one nothing new; each springs back to 85");
    }

    private void verifyPendingNotDraggable() throws Exception {
        limit(85);
        trap = "hold";
        try {
            dragSteps(1); settle();
            main(() -> require(bar().writePending() && bar().drawn == 90f && !bar().labelBox.isEmpty(), "A pending write is not shown at 90 with its label"));
            shot("pending");
            Finger f = new Finger(mark());
            f.move(stepPx() * 2, 0, 8); f.move(stepPx() * 3, 0, 4); f.up(); settle();
            main(() -> {
                require(bar().drawn == 90f && writes.size() == 1, "A pending mark was dragged: " + bar().drawn + " " + writes);
                require(toasts.equals(Collections.singletonList(T.mainQuickWaitChange())), "The refused drag did not say once to wait: " + toasts);
                if (Build.VERSION.SDK_INT >= 30) require(String.valueOf(bar().getStateDescription()).endsWith(T.mainRowConfirmingPart()), "Pending is not spoken: " + bar().getStateDescription());
            });
            main(() -> { Consumer<Boolean> c = held; held = null; c.accept(false); });
            settle(); SystemClock.sleep(400);
            main(() -> require(bar().drawn == 85f && !bar().writePending(), "The cancelled pending write did not spring back"));
        } finally { trap = null; held = null; writes.clear(); toasts.clear(); }
        checks.add("While a write is pending the mark is dimmed at its value with the label, takes no drag and says once to wait; then it follows the report");
    }

    private void verifyAccessibility() throws Exception {
        limit(85);
        main(() -> {
            ChargeBar b = bar();
            AccessibilityNodeInfo node = b.createAccessibilityNodeInfo();
            require(android.widget.SeekBar.class.getName().contentEquals(node.getClassName()), "Not an adjustable control: " + node.getClassName());
            require(node.getRangeInfo() != null && node.getRangeInfo().getCurrent() == 85f, "Range is not 85");
            List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
            require(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD) && actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD),
                    "No increase and decrease");
            String spoken = Build.VERSION.SDK_INT >= 30 ? node.getContentDescription() + ", " + node.getStateDescription() : String.valueOf(node.getContentDescription());
            require("Charge limit, 85%".equals(spoken), "Spoken as '" + spoken + "'");
            require(b.isFocusable() && b.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_YES, "The bar is not focusable for TalkBack");
            node.recycle();
            require(b.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null), "Decrease was refused");
        });
        await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 80, "Decrease did not set 80");
        main(() -> activity.redraw()); settle();
        main(() -> {
            require(writes.equals(Collections.singletonList(80)), "Decrease wrote " + writes);
            require(!bar().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null), "Decrease below the lowest choice did something");
            require(bar().performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null), "Increase was refused");
        });
        await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 85, "Increase did not set 85");
        main(() -> require(writes.equals(Arrays.asList(80, 85)), "Increase wrote " + writes));
        writes.clear();
        limit(85);
        // A held drag (TalkBack's double-tap and hold): the node and the spoken value follow each snapped step.
        Finger f = new Finger(mark());
        f.move(stepPx() * 2 + slop() * .5f, 0, 10);
        SystemClock.sleep(120); waitForIdleSync();
        main(() -> {
            ChargeBar b = bar();
            require(b.drawn == 95f, "The held drag did not reach 95: " + b.drawn);
            AccessibilityNodeInfo node = b.createAccessibilityNodeInfo();
            require(node.getRangeInfo() != null && node.getRangeInfo().getCurrent() == 95f, "During the drag the range says " + (node.getRangeInfo() == null ? null : node.getRangeInfo().getCurrent()));
            String spoken = Build.VERSION.SDK_INT >= 30 ? String.valueOf(node.getStateDescription()) : String.valueOf(node.getContentDescription());
            require(spoken.contains("95%"), "During the drag the value is spoken as '" + spoken + "'");
            node.recycle();
        });
        f.send(MotionEvent.ACTION_CANCEL, f.x, f.y); settle(); SystemClock.sleep(300);
        main(() -> {
            AccessibilityNodeInfo node = bar().createAccessibilityNodeInfo();
            require(node.getRangeInfo().getCurrent() == 85f, "After a cancelled drag the range says " + node.getRangeInfo().getCurrent());
            String spoken = Build.VERSION.SDK_INT >= 30 ? String.valueOf(node.getStateDescription()) : String.valueOf(node.getContentDescription());
            require(spoken.contains("85%") && writes.isEmpty(), "After a cancelled drag the value is spoken as '" + spoken + "' or wrote " + writes);
            node.recycle();
        });
        checks.add("TalkBack: an adjustable control spoken 'Charge limit, 85%'; decrease and increase each move one step and write once; a held drag is spoken and ranged at each step (95%), and back at 85% after a cancel");
    }

    private void verifyRtl() throws Exception {
        limit(85);
        main(() -> { bar().setLayoutDirection(View.LAYOUT_DIRECTION_RTL); bar().invalidate(); });
        settle();
        try {
            float[] m = mark();
            main(() -> { int[] at = new int[2]; bar().getLocationOnScreen(at); float mid = at[0] + bar().getWidth() / 2f; require(m[0] < mid, "Right to left the 85% mark is not left of the middle"); });
            Finger f = new Finger(m);
            f.move(-stepPx(), 0, 8); f.up();
            await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 90, "Right to left, a drag to the left did not raise the limit");
            main(() -> require(writes.equals(Collections.singletonList(90)), "Right to left wrote " + writes));
        } finally { main(() -> bar().setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT)); writes.clear(); }
        checks.add("Right to left the bar and the drag are mirrored: 85% sits left of the middle, a drag left raises it");
    }

    private void verifyReducedMotion() throws Exception {
        limit(85);
        scale("0");
        try {
            Finger f = new Finger(mark());
            f.move(slop() + 2, 0, 2);
            main(() -> require(bar().lift == 1f, "With animations off the bar did not thicken at once: " + bar().lift));
            f.move(stepPx(), 0, 3);
            f.up();
            main(() -> require(bar().lift == 0f, "With animations off the bar did not return at once: " + bar().lift));
            await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 90, "With animations off the drag did not set 90");
        } finally { scale(foundScale == null || foundScale.equals("null") ? "1" : foundScale); writes.clear(); }
        checks.add("With animations off the bar thickens and returns at once, and the drag still writes");
    }

    private void verifyAlert() throws Exception {
        String chosen = null;
        for (String pid : new String[] {"24182", "39991"}) {
            String p = pid;
            main(() -> { service.ble.setSimulationModel(p); service.ble.setSimulationCharging(true); service.ble.tickSimulation(); });
            settle();
            boolean[] offered = new boolean[1]; main(() -> offered[0] = service.chargeAlertsOffered());
            if (offered[0]) { chosen = pid; break; }
        }
        require(chosen != null, "No simulated model offers charge alerts");
        main(() -> require(service.setChargeAlerts(service.chargeAlerts().charge(true, 80)), "Could not save the alert"));
        redrawUntil(() -> bar().kind == ChargeBarDrag.ALERT && bar().drawn == 80f, "The alert model's mark is not the 80% alert");
        main(() -> {
            AccessibilityNodeInfo node = bar().createAccessibilityNodeInfo();
            String spoken = Build.VERSION.SDK_INT >= 30 ? node.getContentDescription() + ", " + node.getStateDescription() : String.valueOf(node.getContentDescription());
            require("Charge alert, 80%".equals(spoken), "The alert is spoken as '" + spoken + "'");
            node.recycle();
        });
        shot("alert-rest");
        Finger f = new Finger(mark());
        f.move(stepPx() + slop() * .5f, 0, 8);
        SystemClock.sleep(200); waitForIdleSync();
        shot("alert-dragging");
        f.up(); settle();
        main(() -> {
            // Simulated: the real profile's alert keeps its 80; the mark holds 85 until simulation ends.
            require(service.chargeAlerts().charge && service.chargeAlerts().chargeAt == 80, "A simulated drag changed the saved alert to " + service.chargeAlerts().chargeAt);
            require(bar().drawn == 85f && Integer.valueOf(85).equals(bar().simulatedAlert), "The mark did not hold the dragged 85: " + bar().drawn);
            require(bar().confirms > 0, "The alert drag did not confirm");
            require(writes.isEmpty(), "An alert drag wrote a limit");
        });
        main(() -> activity.redraw()); settle();
        main(() -> require(bar().drawn == 85f, "A refresh put the simulated alert mark back to " + bar().drawn));
        main(() -> require(service.setChargeAlerts(service.chargeAlerts().charge(false, 80)), "Could not turn the alert off"));
        redrawUntil(() -> bar().kind == ChargeBarDrag.NONE, "The mark stayed with the alert off");
        main(() -> { service.ble.setSimulationModel(""); service.ble.tickSimulation(); });
        checks.add("Simulated " + chosen + ": the mark is the charge alert, spoken 'Charge alert, 80%'; in simulation a drag to 85 is held by the mark and never saved (the alert settings keep 80) and writes no limit; alert off, no mark");
    }

    /**
     * The OEM-mapped model the tests use (24431): since the owner dropped "Untested here" (Nov 17 2026), neither the Charge
     * limit row nor the bar carries a note there, shown or spoken, at rest, focused, pending or dragged.
     */
    private void verifyNoUntestedNote() throws Exception {
        main(() -> { service.ble.setSimulationModel("24431"); service.ble.setSimulationCharging(true); service.ble.tickSimulation(); });
        settle();
        redrawUntil(() -> shownAtRest() && bar().kind == ChargeBarDrag.LIMIT && bar().allowed.length > 0 && bar().gate == ChargeBarDrag.Gate.OK, "The mapped model has no draggable limit mark");
        String gone = T.mainRowUntested();
        int[] from = new int[1];
        main(() -> {
            ChargeBar b = bar();
            require(b.note == null, "The bar's note on the mapped model is " + b.note + ", not none");
            String state = Build.VERSION.SDK_INT >= 30 ? String.valueOf(b.getStateDescription()) : String.valueOf(b.getContentDescription());
            require(!state.toLowerCase(java.util.Locale.ROOT).contains(gone.toLowerCase(java.util.Locale.ROOT)), "The old note is spoken: '" + state + "'");
            from[0] = Math.round(b.drawn);
        });
        writes.clear(); toasts.clear();
        // Keyboard focus: the value shows, with no note, before any key writes.
        // Leaving touch mode goes through the window manager and reaches the window later on a slow host.
        setInTouchMode(false); waitForIdleSync();
        try {
            await(() -> !bar().isInTouchMode(), "The window stayed in touch mode");
            try { await(() -> bar().isFocused() || bar().requestFocus(), "focus"); }
            catch (IllegalStateException refused) {
                main(() -> { ChargeBar b = bar(); View holder = b.getRootView().findFocus();
                    require(false, "The bar did not take keyboard focus: focusable " + b.isFocusable() + ", in touch mode " + b.isInTouchMode()
                            + ", shown " + b.isShown() + ", enabled " + b.isEnabled() + ", focus held by " + (holder == null ? "nothing" : holder.getClass().getName())); });
            }
            main(() -> require(bar().isFocused(), "The bar did not keep keyboard focus"));
            settle();
            main(() -> {
                ChargeBar b = bar();
                require(!b.labelBox.isEmpty() && !b.labelText(Math.round(b.drawn)).contains(gone), "Focused, the bar does not show its value alone: " + b.labelBox);
                require(writes.isEmpty(), "Focusing the bar wrote " + writes);
            });
            shot("mapped-focused");
            main(() -> bar().clearFocus()); settle();
            main(() -> require(bar().labelBox.isEmpty(), "The label stayed after focus left"));
        } finally { setInTouchMode(true); waitForIdleSync(); }
        // A pending write is spoken as confirming, with no note, as the label shows it.
        trap = "hold";
        try {
            int up = from[0] >= 100 ? -1 : 1;
            Finger held1 = new Finger(mark());
            held1.move(up * (stepPx() + slop() * .5f), 0, 8); held1.up(); settle();
            main(() -> {
                ChargeBar b = bar();
                require(b.writePending(), "The held write is not pending");
                String state = Build.VERSION.SDK_INT >= 30 ? String.valueOf(b.getStateDescription()) : String.valueOf(b.getContentDescription());
                String lower = state.toLowerCase(java.util.Locale.ROOT);
                require(lower.contains(T.mainRowConfirmingPart().toLowerCase(java.util.Locale.ROOT).trim()) && !lower.contains(gone.toLowerCase(java.util.Locale.ROOT)),
                        "Pending, the spoken state is '" + state + "', not confirming without a note");
                require(!b.labelText(Math.round(b.drawn)).contains(gone), "Pending, the label shows the old note");
            });
            main(() -> { Consumer<Boolean> c = held; held = null; if (c != null) c.accept(false); });
            settle(); SystemClock.sleep(300);
        } finally { trap = null; held = null; writes.clear(); toasts.clear(); }
        int direction = from[0] >= 100 ? -1 : 1;
        Finger f = new Finger(mark());
        f.move(direction * (stepPx() + slop() * .5f), 0, 8);
        SystemClock.sleep(260); waitForIdleSync();
        main(() -> {
            ChargeBar b = bar();
            String shown = b.labelText(Math.round(b.drawn));
            require(!shown.isEmpty() && !shown.contains(gone), "The dragged value shows the old note: '" + shown + "'");
            RectF inside = new RectF(0, 0, b.getWidth(), b.getHeight());
            require(!b.labelBox.isEmpty() && inside.contains(b.labelBox), "The label leaves the bar: " + b.labelBox);
        });
        shot("mapped-dragging");
        f.up();
        int wanted = from[0] + direction * 5;
        await(() -> !bar().writePending() && Objects.equals(service.ble.featureValue("charge_limit"), wanted), "The mapped model's drag did not set " + wanted);
        main(() -> require(writes.equals(Collections.singletonList(wanted)), "The mapped model's drag wrote " + writes));
        main(() -> { service.ble.setSimulationModel(""); service.ble.tickSimulation(); });
        redrawUntil(() -> shownAtRest() && bar().kind == ChargeBarDrag.LIMIT && bar().note == null, "Back on the UT5 MAX the note stayed");
        writes.clear(); toasts.clear();
        checks.add("Simulated 24431 (OEM-mapped): no 'Untested here' (owner, Nov 17 2026): the bar has no note, none spoken at rest or while confirming, none beside the focused, pending or dragged value; the drag still writes once; none on the UT5 MAX");
    }

    /** Left and right step the focused bar as increase and decrease do, mirrored right to left, through the same commit. */
    private void verifyKeys() throws Exception {
        limit(85);
        main(() -> require(key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), "Right was not taken"));
        await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 90, "Right did not set 90");
        main(() -> activity.redraw()); settle();
        main(() -> require(key(android.view.KeyEvent.KEYCODE_DPAD_LEFT), "Left was not taken"));
        await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 85, "Left did not set 85");
        main(() -> { activity.redraw(); bar().setLayoutDirection(View.LAYOUT_DIRECTION_RTL); });
        settle();
        try {
            main(() -> require(key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), "Right to left, right was not taken"));
            await(() -> !bar().writePending() && service.ble.featureValue("charge_limit") == 80, "Right to left, right did not lower the limit to 80");
            main(() -> activity.redraw()); settle();
            main(() -> require(!key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), "Right to left, right past the lowest choice was taken"));
        } finally { main(() -> bar().setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT)); }
        main(() -> require(writes.equals(Arrays.asList(90, 85, 80)), "The keys wrote " + writes));
        writes.clear();
        checks.add("Arrow keys: right and left step the bar and write once each (90, 85); right to left they are mirrored (80); at the end the key is not taken");
    }
    private boolean key(int code) {
        ChargeBar b = bar();
        long now = SystemClock.uptimeMillis();
        boolean taken = b.dispatchKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0));
        b.dispatchKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0));
        return taken;
    }

    private void verifyLayoutMatrix() throws Exception {
        String size = args.getString("size_label", "size");
        for (String theme : new String[] {"dark", "light"}) for (String text : new String[] {"1", "1.4"}) {
            label = size + "-" + theme + "-" + text;
            MainActivity old = activity; main(old::finish); SystemClock.sleep(800); settle();
            seed(theme, text); launch();
            for (int end : new int[] {80, 100}) {
                limit(end == 80 ? 85 : 95);
                Finger f = new Finger(mark());
                f.move((end == 80 ? -1 : 1) * stepPx() * 3, 0, 10);
                SystemClock.sleep(260); waitForIdleSync();
                final int e = end;
                main(() -> {
                    ChargeBar b = bar();
                    require(b.drawn == e, label + ": the drag did not reach " + e + " (" + b.drawn + ")");
                    RectF inside = new RectF(0, 0, b.getWidth(), b.getHeight());
                    require(!b.labelBox.isEmpty() && inside.contains(b.labelBox), label + ": the label " + b.labelBox + " leaves the bar " + inside + " at " + e);
                    require(inside.contains(b.markBox), label + ": the mark " + b.markBox + " leaves the bar at " + e);
                    RectF percent = onScreen((View) field(hero(), "percent")), bar = onScreen(b);
                    RectF labelOnScreen = new RectF(b.labelBox); labelOnScreen.offset(bar.left, bar.top);
                    require(!RectF.intersects(labelOnScreen, percent), label + ": the label covers the percentage at " + e);
                    require(b.labelBox.height() >= activity.ui().dp(12) * Float.parseFloat(text), label + ": the label is tiny: " + b.labelBox.height());
                });
                shot(label + "-drag-" + end);
                f.send(MotionEvent.ACTION_CANCEL, f.x, f.y); settle();
            }
            shot(label + "-rest");
            checks.add(label + ": the mark and the value label inside the bar at both ends, clear of the percentage");
        }
    }

    // ---- Fixtures -------------------------------------------------------------------------------------------

    private void seed(String theme, String text) {
        SharedPreferences.Editor edit = settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                .putString("theme", theme).putFloat("text_scale", Float.parseFloat(text));
        if (clean) edit.putString("home_style", "clean").putBoolean("home_style_chosen", true).putBoolean("home_whats_new_shown", true);
        require(edit.commit(), "Could not seed preferences");
    }
    private void launch() throws Exception {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
        SystemClock.sleep(1500); settle();
        main(() -> {
            if (!service.simulating()) require(service.setSimulation(true), "Simulation did not start");
            service.ble.setSimulationModel(""); service.ble.setSimulationCharging(true); service.ble.tickSimulation();
        });
        await(() -> service.ble.ready() && service.featureFresh("charge_limit"), "The simulated UT5 MAX is not ready with a charge limit");
        freeze();
    }
    /** Home, with the app's own refreshes unhooked: the hero changes only when this run redraws (or the app's write does). */
    private void freeze() throws Exception {
        await(() -> field(activity, "screen") != null, "No screen");
        main(() -> {
            invoke(activity, "select", new Class<?>[]{String.class}, "home");
            ((android.os.Handler) field(activity, "handler")).removeCallbacks((Runnable) field(activity, "pulse"));
            service.unlisten((Runnable) field(activity, "serviceChanged"));
            invoke(activity, "cancelDeferredBodyRefresh");
            ((SharedPreferences) field(activity, "preferences")).unregisterOnSharedPreferenceChangeListener(
                    (SharedPreferences.OnSharedPreferenceChangeListener) field(activity, "settingsChanged"));
        });
        settle();
        boolean look = "look".equals(args.getString("mode"));
        redrawUntil(() -> hero() != null && shownAtRest() && (look || bar().kind != ChargeBarDrag.NONE), "The charging view did not show on the simulator");
    }
    private boolean shownAtRest() {
        View hero = hero();
        if (hero == null || !hero.isShown() || field(hero, "reveal") != null) return false;
        if (clean) {
            // Clean: Plug in has ended (its top area at rest) and the view is fully shown.
            Object home = cleanHome();
            if (home == null || Boolean.TRUE.equals(invoke(field(home, "transition"), "changing")) || hero.getAlpha() != 1f) return false;
        }
        return field(hero, "bar") == null || bar().isAttachedToWindow();
    }
    /** Sets the simulated limit (no counting), redraws until the mark rests there, and clears the record. */
    private void limit(int value) throws Exception {
        main(() -> { if (!Objects.equals(service.ble.featureValue("charge_limit"), value)) require(service.ble.set("charge_limit", value), "Could not set the simulated limit to " + value); service.ble.tickSimulation(); });
        redrawUntil(() -> shownAtRest() && bar().kind == ChargeBarDrag.LIMIT && bar().drawn == value && !bar().writePending() && bar().lift == 0f, "The mark did not rest at " + value);
        writes.clear(); toasts.clear();
    }
    private void redrawUntil(BooleanSupplier condition, String message) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ok = new AtomicReference<>();
            main(() -> { activity.redraw(); });
            settle(); SystemClock.sleep(120);
            main(() -> ok.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(ok.get())) { SystemClock.sleep(Motion.CHANGE_MS + 80); settle(); return; }
        }
        throw new IllegalStateException(message);
    }
    private final class Recorder implements ChargeBar.Host {
        final ChargeBar.Host real;
        Recorder(ChargeBar.Host real) { this.real = real; }
        @Override public ScooterService service() { return real.service(); }
        @Override public boolean inert() { return real.inert(); }
        @Override public void chargeLimit(int value, Consumer<Boolean> accepted) {
            writes.add(value);
            if (trap == null) real.chargeLimit(value, accepted);
            else if (trap.equals("hold")) held = accepted;
            else if (trap.equals("accept")) accepted.accept(true);   // the scooter acknowledged; its readback decides
            else accepted.accept(false);
        }
        @Override public void toast(String text) { toasts.add(text); real.toast(text); }
        @Override public ChargeBar.Words words() { return real.words(); }
    }
    @SuppressWarnings("unchecked")
    private void putResult(String result) {
        Map<String, String> results = (Map<String, String>) field(service.ble, "commandResults");
        if (result == null) results.remove("charge_limit"); else results.put("charge_limit", result);
    }
    /** One finger on the screen. */
    private final class Finger {
        final long down = SystemClock.uptimeMillis();
        float x, y; boolean two;
        Finger(float[] at) { x = at[0]; y = at[1]; send(MotionEvent.ACTION_DOWN, x, y); }
        /** Moves by (dx, dy) from where it went down, in n moves. */
        void move(float dx, float dy, int n) {
            float fromX = x, fromY = y, toX = startX() + dx, toY = startY() + dy;
            for (int i = 1; i <= n; i++) { x = fromX + (toX - fromX) * i / n; y = fromY + (toY - fromY) * i / n; send(MotionEvent.ACTION_MOVE, x, y); SystemClock.sleep(14); }
        }
        float sx = Float.NaN, sy;
        float startX() { return sx; }
        float startY() { return sy; }
        void up() {
            if (two) {
                MotionEvent e = pair(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y);
                try { sendPointerSync(e); } finally { e.recycle(); }
                two = false;
            }
            send(MotionEvent.ACTION_UP, x, y); waitForIdleSync();
        }
        void send(int action, float px, float py) {
            if (Float.isNaN(sx)) { sx = px; sy = py; }
            MotionEvent e = two ? pair(action, px, py) : MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0);
            try { sendPointerSync(e); } finally { e.recycle(); }
        }
        /** A second finger lands well away from the first. */
        void secondFinger() {
            two = true;
            MotionEvent e = pair(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), x, y);
            try { sendPointerSync(e); } finally { e.recycle(); }
        }
        MotionEvent pair(int action, float px, float py) {
            MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
            props[0].id = 0; props[1].id = 1; props[0].toolType = props[1].toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
            coords[0].x = px; coords[0].y = py; coords[1].x = px - ui(120); coords[1].y = py - ui(80);
            coords[0].pressure = coords[1].pressure = 1; coords[0].size = coords[1].size = 1;
            return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, props, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        }
    }
    /** A drag of n steps from the mark (negative: down), lifted. */
    private void dragSteps(int n) throws Exception {
        Finger f = new Finger(mark());
        f.move(n * stepPx() + Math.signum(n) * slop() * .5f, 0, 10); f.up(); settle();
    }
    /** The mark's centre on screen. */
    private float[] mark() throws Exception { reveal(); float[] p = new float[2]; main(() -> { float[] q = at(bar().drawn); p[0] = q[0]; p[1] = q[1]; }); return p; }
    private float[] point(double percent) throws Exception { reveal(); float[] p = new float[2]; main(() -> { float[] q = at(percent); p[0] = q[0]; p[1] = q[1]; }); return p; }
    private float[] at(double percent) {
        ChargeBar b = bar(); int[] loc = new int[2]; b.getLocationOnScreen(loc);
        return new float[] {loc[0] + b.xAt(percent), loc[1] + b.floorY()};
    }
    private static RectF onScreen(View v) { int[] at = new int[2]; v.getLocationOnScreen(at); return new RectF(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()); }
    private float stepPx() { float[] w = new float[1]; main(() -> w[0] = Math.abs(bar().xAt(5) - bar().xAt(0))); return w[0]; }
    private float ui(float dp) { return dp * getTargetContext().getResources().getDisplayMetrics().density; }
    private float slop() { return ViewConfiguration.get(getTargetContext()).getScaledTouchSlop(); }
    private void reveal() throws Exception {
        main(() -> {
            View view = bar(); android.widget.ScrollView parent = scroller();
            Rect r = new Rect(); view.getDrawingRect(r); parent.offsetDescendantRectToMyCoords(view, r);
            // Home's floating navigation covers the scroller's bottom padding: the bar counts as reachable only above
            // it (the 725 dp, 1.4x run once pressed the navigation instead), and is otherwise scrolled to the middle.
            int seen = parent.getHeight() - parent.getPaddingBottom();
            int top = parent.getScrollY() + parent.getPaddingTop(), bottom = parent.getScrollY() + seen;
            if (r.top >= top && r.bottom <= bottom) return;
            parent.scrollTo(0, Math.max(0, r.centerY() - seen / 2));
        });
        settle();
    }
    private android.widget.ScrollView scroller() {
        View v = bar();
        while (v != null && !(v instanceof android.widget.ScrollView)) v = v.getParent() instanceof View ? (View) v.getParent() : null;
        require(v != null, "No scroller above the bar");
        return (android.widget.ScrollView) v;
    }
    private int scrollY() { int[] y = new int[1]; main(() -> y[0] = scroller().getScrollY()); return y[0]; }
    /** Which way a finger moves to scroll Home from here (1 down, -1 up). */
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
    private Object screen() { return field(activity, "screen"); }
    /** The charging view: Clean's (tagged, in one pane or the scooter pane of two), else Widgets' takeover. */
    private View hero() {
        if (clean) return root().findViewWithTag("clean-home-charging");
        Object screen = screen();
        return screen == null || !(screen instanceof DashboardScreen) ? null : (View) field(screen, "chargingHero");
    }
    /** Clean Home (one pane, or the scooter pane's of two): the charging view's top area's owner. */
    private Object cleanHome() {
        Object screen = screen();
        if (screen != null && screen.getClass().getSimpleName().equals("CleanHomeScreen")) return screen;
        Object pane = field(activity, "homeScreen");
        return pane != null && pane.getClass().getSimpleName().equals("CleanHomeScreen") ? pane : null;
    }
    /** The Widgets bar, its host wrapped (again, if Home was rebuilt) so every write and toast is seen. */
    private ChargeBar bar() {
        ChargeBar b = (ChargeBar) field(hero(), "bar");
        Object real = field(b, "host");
        if (!(real instanceof Recorder)) setField(b, "host", new Recorder((ChargeBar.Host) real));
        return b;
    }

    // ---- Plumbing ---------------------------------------------------------------------------------------------

    private String foundScale;
    private void restoreFound() {
        if (foundScale != null) try {
            shell(foundScale.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + foundScale);
        } catch (Throwable ignored) {}
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
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "charge-bar-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(300); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); com.jjricks.volt.tests.TextCensus.write(this, file);
    }
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
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
}
