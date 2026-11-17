package com.jjricks.volt.tests;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only (refuses a physical phone, existing app data and Bluetooth turned on). Widgets Home's
 * dock and its All sheet as a finger moves them (DragSheet and SheetSpring, the owner's request of 15 Nov), and the dock
 * itself growing into the sheet and back (SheetMorph, his follow-up the same day), on the developer simulator, with real
 * touches injected into the window (Instrumentation.sendPointerSync, explicit event times):
 *
 * <ul>
 *   <li><b>Pulls from the dock</b>: a slow pull to about a tenth of the sheet's travel, held and let go, springs back
 *   closed and dismisses it; a slow pull to about two fifths opens it; a short fast flick opens it (lifted before the
 *   sheet's first layout). The sheet shows mid-gesture (drawn in Volt's own window) and never takes the touch from the
 *   dock; it follows the finger 1:1 from where the swipe was recognised.</li>
 *   <li><b>The open sheet</b>: dragged by its grab handle a tenth of the way down it springs back open, two fifths down
 *   it closes, and a fast downward flick closes it. With its list scrolled down, a downward drag on the list scrolls the
 *   list and leaves the sheet open; dragged on past the list's top, the sheet takes over and closes.</li>
 *   <li><b>Taps</b>: a dock item opens its subject (Back returns), All opens the sheet with the spring, and its close
 *   button, Back, a row and a tap on the dim close it.</li>
 *   <li><b>Interruptions</b>: ACTION_CANCEL and a second finger settle a pull where it is; a pull taken back to its start
 *   ends closed; a new pull and a tap on All catch a sheet that is settling closed and bring it back; the activity
 *   recreated mid-pull (as a rotation, fold, resize or text-size change rebuilds Home) leaves no sheet and no crash.</li>
 *   <li><b>Every frame</b>, at animator duration scale 5 (read as each frame is drawn): the opening moves only toward
 *   open until one overshoot no larger than SheetSpring.CAP_DP, then back (any second ripple under 1 dp); a snap-back
 *   and a close move only toward closed; from open on (the bounce) the sheet's background never ends above the screen's
 *   bottom. The dock and the sheet are one surface: every frame shows exactly one of the dock and the sheet's surface
 *   (never both, never neither); the first frame of an opening (All, a pull) and the last of a close or snap-back are
 *   the dock's own bounds within 2 px (a pull's first frame as far up as its finger had gone by then), the frame
 *   after the last is the dock itself; the surface's top follows the sheet's offset 1:1 and its sides and bottom move
 *   with its progress with no jump; the dock's buttons are gone by a quarter of the way and the sheet's content shows
 *   only past 35% (drawn over the surface, never under it), never both at once.</li>
 *   <li><b>Animations off</b> (animator duration scale 0): a pull still follows the finger, and every release, tap and
 *   close jumps to its end with no settle.</li>
 * </ul>
 *
 * The animator duration scale is put back as it was at the end. -e theme dark|light and -e text_scale (1 by default) as
 * the other fixture runners; -e list_scrolls true|false requires that the window's sheet is (or is not) taller than the
 * window, so its list scrolls. -e mode hold sets Widgets Home up and holds it for -e hold_seconds (600 by default), for
 * recordings driven from adb, after a status line where=... (where to touch; where()); it checks nothing. -e mode pose
 * holds each of -e poses in turn (a comma list: top or busy scrolls Home to its top or far enough down that widgets sit
 * behind the dock, dock is the dock alone, a progress 0..1 the sheet pulled that far and held) for -e pose_seconds (4),
 * sending a status line pose=NAME as each is in place, so pictures can be taken from adb.
 */
public final class DockSheetValidation extends Instrumentation {
    private static final String FIXTURE_NAME = "Synthetic dock fixture", FIXTURE_IDENTITY = "B1C2D3E4F5A6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:57";
    private static final String STYLE_KEY = "home_style", STYLE_CHOSEN = "home_style_chosen", STYLE_ORIGIN = "home_style_origin", WIDGETS = "widgets";
    /** DragSheet's states (package-private in the app, read once by reflection). */
    private int OPENING, OPEN, CLOSING, PULLED, DRAGGED;
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>();
    /** px: the sheet's travel at this window, the most it goes past open, a dp. */
    private int travel;
    private float cap, dp;
    private String scaleBefore;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "all");
            require(mode.equals("all") || mode.equals("hold") || mode.equals("pose"), "Unknown -e mode " + mode + " (all, hold or pose)");
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1")))
                    .putString(STYLE_KEY, WIDGETS).putBoolean(STYLE_CHOSEN, true).putString(STYLE_ORIGIN, "install")
                    .commit(), "Fixture settings failed");
            seed();
            launch();
            simulate();
            requireHome();
            OPENING = state("OPENING"); OPEN = state("OPEN"); CLOSING = state("CLOSING"); PULLED = state("PULLED"); DRAGGED = state("DRAGGED");
            if (mode.equals("hold")) {
                where();
                checks.add("Widgets Home held for recordings");
                SystemClock.sleep(Long.parseLong(arguments.getString("hold_seconds", "600")) * 1000);
            } else if (mode.equals("pose")) {
                poses();
            } else {
                steadyBars();
                cap = (float) (spring("CAP_DP") * dp());
                dp = dp();
                scaleBefore = shell("settings get global animator_duration_scale").trim();
                try {
                    scale(1);
                    // -e only window (or early, nested, keys): the own-window checks alone, or one of them.
                    String only = arguments.getString("only", "");
                    if (!only.isEmpty()) {
                        switch (only) {
                            case "window": ownWindow(); break;
                            case "early": earlyTap(); break;
                            case "nested": nested(); break;
                            case "keys": keys(); break;
                            case "keys-back": keysBack(); break;
                            default: require(false, "Unknown -e only " + only + " (window, early, nested or keys)");
                        }
                        result.putString("result", "PASS");
                        return;
                    }
                    taps();
                    pulls();
                    drags();
                    scrolledList();
                    interruptions();
                    ownWindow();
                    relayout();
                    frames();
                    animationsOff();
                    activityGoes();
                } finally {
                    shell(scaleBefore.isEmpty() || scaleBefore.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + scaleBefore);
                }
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== The checks =====

    /** All, the close button, Back, a row, the dim, a dock item: the taps a rider makes. Also measures the sheet's travel. */
    private void taps() throws Exception {
        // A dock item still opens its subject, and Back returns.
        String subject = onMain(() -> {
            for (String each : HubPages.SUBJECTS) { View item = root().findViewWithTag("hub-dock-" + each); if (shown(item)) return each; }
            return null;
        });
        require(subject != null, "The dock shows no subject item");
        HubTestPages.showDock(this, activity);
        tapCentre(onMain(() -> root().findViewWithTag("hub-dock-" + subject)), "the dock's " + subject + " item");
        await(() -> onMain(() -> subject.equals(HubTestPages.current(activity))), "The dock's " + subject + " item did not open it");
        settle();
        back();
        requireHome();
        checks.add("A dock item (" + subject + ") opens its subject, and Back returns to Widgets Home");

        // All: the sheet springs open from closed (OPENING, then OPEN at offset 0).
        List<Integer> seen = tapAll();
        require(seen.contains(OPENING), "All opened the sheet without its spring (states " + seen + ")");
        travel = onMain(() -> (Integer) field(drag(), "travel"));
        require(travel > 0, "The open sheet has no travel");
        fits();
        checks.add("The open sheet sits below the status bar with its list above the navigation bar, its bar icons suit the theme (light theme: white status icons over the full dim, dark navigation icons over the sheet), and its background reaches the screen's bottom edge on screen");
        // The close button: the animated close, then gone.
        seen = closeBy(() -> tapCentre(closeButton(), "the sheet's close button"));
        require(seen.contains(CLOSING), "The close button closed the sheet without its settle (states " + seen + ")");
        // Back: the same.
        tapAll();
        seen = closeBy(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
        require(seen.contains(CLOSING), "Back closed the sheet without its settle (states " + seen + ")");
        // A tap on the dim, above the sheet.
        tapAll();
        // Midway between the status bar (the system's own window takes touches there) and the sheet's top.
        float[] above = onMain(() -> {
            View panel = panel(); int[] at = new int[2]; panel.getLocationOnScreen(at);
            android.view.WindowInsets insets = panel.getRootWindowInsets();
            int bar = insets != null && Build.VERSION.SDK_INT >= 30 ? insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()).top : 0;
            return new float[]{at[0] + panel.getWidth() / 2f, (bar + at[1]) / 2f, at[1] - bar};
        });
        if (above[2] > 16 * dp) {
            seen = closeBy(() -> tap(above[0], above[1]));
            require(seen.contains(CLOSING), "A tap on the dim closed the sheet without its settle (states " + seen + ")");
            checks.add("A tap on the dim above the sheet closes it with the same settle");
        } else {
            back(); awaitGone("Back");
            checks.add("The sheet fills the window's height here; no dim above it to tap");
        }
        // A row: its subject opens and the sheet closes.
        tapAll();
        View row = onMain(() -> sheetView().findViewWithTag("home-subject-" + subject));
        require(row != null, "The All sheet has no row for " + subject);
        main(() -> row.requestRectangleOnScreen(new Rect(0, 0, row.getWidth(), row.getHeight()), true));
        settle();
        // The dock goes with Home as the subject opens; the sheet it opened settles closed over the subject all the same.
        Object sheet = onMain(this::drag);
        View window = onMain(this::sheetView);
        States states = new States(sheet);
        tapCentre(row, "the All sheet's " + subject + " row");
        long end = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < end && !states.gone()) SystemClock.sleep(10);
        seen = states.stop();
        require(!onMain(window::isAttachedToWindow), "The All sheet stayed open after its " + subject + " row");
        require(seen.contains(CLOSING), "The " + subject + " row closed the sheet without its settle (states " + seen + ")");
        await(() -> onMain(() -> subject.equals(HubTestPages.current(activity))), "The All sheet's " + subject + " row did not open it");
        awaitGone("the " + subject + " row");
        back();
        requireHome();
        checks.add("All opens the sheet with its spring (" + travel + " px of travel); the close button, Back and a row close it with the settle, and a row opens its subject");
    }

    /** Pulls from the dock: a tenth springs back closed, two fifths opens, a short fast flick opens. */
    private void pulls() {
        pullTo(.10, 700, true);
        awaitGone("a pull to a tenth");
        pullTo(.40, 900, true);
        awaitOpen("a pull to two fifths");
        back(); awaitGone("Back");
        // A flick: 120 dp in about 64 ms, lifted at once (before the sheet's first layout).
        Finger finger = new Finger(dockCentre());
        finger.moveBy(-120 * dp, 64, 4);
        finger.up();
        awaitOpen("a short fast flick");
        back(); awaitGone("Back");
        checks.add("From the dock: a slow pull to a tenth springs back closed and dismisses; a slow pull to two fifths opens; a 120 dp flick in 64 ms opens");
    }

    /** The open sheet dragged by its grab handle: a tenth springs back, two fifths closes, a fast flick down closes. */
    private void drags() {
        tapAll();
        Finger finger = new Finger(centre(handle()));
        finger.moveBy(.10f * travel, 700, 40);
        finger.hold(150);
        finger.up();
        awaitOpen("the handle dragged a tenth down");
        main(() -> require(Math.abs(offset()) < .5f, "The sheet came back to " + offset() + " px, not open"));
        finger = new Finger(centre(handle()));
        finger.moveBy(.40f * travel, 900, 50);
        finger.hold(150);
        finger.up();
        awaitGone("the handle dragged two fifths down");
        tapAll();
        finger = new Finger(centre(handle()));
        finger.moveBy(90 * dp, 60, 4);
        finger.up();
        awaitGone("a fast flick down on the handle");
        checks.add("The open sheet by its grab handle: a tenth down springs back open, two fifths down closes, a fast flick down closes");
    }

    /** A list scrolled down: a downward drag scrolls it and the sheet stays; on past its top, the sheet takes over. */
    private void scrolledList() {
        tapAll();
        boolean scrolls = onMain(() -> list().canScrollVertically(1));
        if (arguments.containsKey("list_scrolls"))
            require(scrolls == Boolean.parseBoolean(arguments.getString("list_scrolls")), "The sheet's list " + (scrolls ? "scrolls" : "does not scroll") + " at this window; -e list_scrolls " + arguments.getString("list_scrolls"));
        if (!scrolls) {
            // A list too short to scroll: a drag on it moves the sheet either way (down two fifths closes).
            Finger finger = new Finger(listPoint());
            finger.moveBy(.40f * travel, 900, 50);
            finger.hold(150);
            finger.up();
            awaitGone("a drag on a list that cannot scroll");
            checks.add("The sheet's list fits (it does not scroll): a drag down on it moves the sheet, and two fifths closes it");
            return;
        }
        main(() -> list().fullScroll(View.FOCUS_DOWN));
        settle();
        int scrolled = onMain(() -> list().getScrollY());
        require(scrolled > 4 * dp, "The sheet's list did not scroll down (" + scrolled + " px)");
        // Down by half of what it scrolled: the list scrolls back that far and the sheet stays open.
        Finger finger = new Finger(listPoint());
        finger.moveBy(scrolled / 2f + 12 * dp, 700, 40);
        finger.hold(150);
        finger.up();
        settle();
        int now = onMain(() -> list().getScrollY());
        require(now < scrolled - scrolled / 4, "A downward drag on the scrolled list did not scroll it (" + scrolled + " -> " + now + " px)");
        require(onMain(() -> field(drag(), "state")).equals(OPEN) && Math.abs(onMain(this::offset)) < .5f, "A downward drag on the scrolled list moved the sheet");
        // Down by the rest of the scroll and two fifths more: the list reaches its top, then the sheet follows and closes.
        finger = new Finger(listPoint());
        finger.moveBy(now + .45f * travel, 1300, 70);
        finger.hold(150);
        finger.up();
        awaitGone("a drag on past the list's top");
        checks.add("The sheet's list scrolled down (" + scrolled + " px): a downward drag scrolls the list and leaves the sheet open; dragged on past the list's top, the sheet follows and closes");
    }

    /** ACTION_CANCEL, a second finger, a pull taken back, and a pull or tap catching a sheet on its way out. */
    private void interruptions() {
        // ACTION_CANCEL at a tenth: settled by position, closed.
        Finger finger = new Finger(dockCentre());
        finger.moveBy(-.10f * travel, 600, 30);
        finger.cancel();
        awaitGone("a pull cancelled at a tenth");
        // ACTION_CANCEL at two fifths: open.
        finger = new Finger(dockCentre());
        finger.moveBy(-.40f * travel, 800, 40);
        finger.cancel();
        awaitOpen("a pull cancelled at two fifths");
        back(); awaitGone("Back");
        // A second finger at two fifths: the pull settles open there, and the rest of the touch (pulled back down) moves nothing.
        finger = new Finger(dockCentre());
        finger.moveBy(-.40f * travel, 800, 40);
        finger.hold(100);
        finger.secondFinger();
        finger.moveBy(.30f * travel, 300, 15);
        finger.up();
        awaitOpen("a second finger at two fifths");
        back(); awaitGone("Back");
        // Up a third, then back below where it began: closed, and the sheet never showed more than the finger did.
        finger = new Finger(dockCentre());
        finger.moveBy(-.30f * travel, 500, 25);
        finger.moveBy(.30f * travel + 20 * dp, 500, 25);
        // Against the sheet's own travel now (the window, and so the travel, can have changed since taps() measured it).
        main(() -> { int now = (Integer) field(drag(), "travel");
            require(offset() >= now - .5f, "Pulled back to its start, the sheet still shows " + (now - offset()) + " px (offset " + offset() + " of " + now + " px)"); });
        finger.hold(120);
        finger.up();
        awaitGone("a pull taken back to its start");
        // A sheet settling closed (slowed to scale 10, a close of about 3 s, so it is caught on its way) keeps every touch:
        // nothing reaches the page under it (Widgets Home's dock and cards, its lock control among them).
        scale(10);
        // A plain tap on the closing sheet, right over a dock item (which would open its subject): the sheet stops under
        // the finger and carries on closing; neither the item nor the sheet's row under the finger opens anything. The
        // item is found while the dock shows (the sheet hides it, keeping its place).
        String item = onMain(() -> {
            for (String each : HubPages.SUBJECTS) { View view = root().findViewWithTag("hub-dock-" + each); if (shown(view)) return "hub-dock-" + each; }
            return null;
        });
        require(item != null, "The dock shows no subject item");
        float[] under = onMain(() -> centreOf(root().findViewWithTag(item)));
        Finger tapping;
        Object before;
        // A slow machine can take longer than the slowed close between Back and the finger landing: then the close has
        // ended (observed: the sheet has gone, or no longer covers the item), the finger is cancelled (no tap reaches the
        // dock) and the same is tried slower. A sheet still showing that did not stop under the finger fails at once.
        for (int slower = 10; ; slower *= 2) {
            scale(slower);
            tapAll();
            before = onMain(this::drag);
            closeStarts(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
            boolean covers = onMain(() -> { if (drag() == null || !shown(panel())) return false; int[] at = new int[2]; panel().getLocationOnScreen(at); return at[1] < under[1] - 8 * dp; });
            // Down: caught (DRAGGED) at once. Up straight after, as a tap: carries on closing.
            tapping = covers ? new Finger(under) : null;
            Object caughtState = onMain(() -> drag() == null ? null : field(drag(), "state"));
            if (tapping != null && Integer.valueOf(DRAGGED).equals(caughtState)) break;
            boolean ended = onMain(() -> { View overlay = sheetView(); return overlay == null || !overlay.isAttachedToWindow(); }) || !covers;
            if (tapping != null) tapping.cancel();
            require(ended && slower < 80, "A finger down on the closing sheet did not stop it under the finger (" + describe() + ")");
            awaitGone("a close that ended before the finger landed");
            requireHome();
        }
        States states = new States(before);
        tapping.up();
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end && !states.gone()) SystemClock.sleep(10);
        List<Integer> seen = states.stop();
        require(!seen.contains(OPENING) && !seen.contains(OPEN), "A plain tap on the closing sheet brought it back (states " + seen + ")");
        awaitGone("a tap on the closing sheet");
        requireHome();
        checks.add("A plain tap on the sheet while it settles closed, over a dock item: the sheet stops under the finger and carries on closing; nothing under it (the dock item, the sheet's own row) opens");
        // Caught low (two fifths of the way closed), taken up 30% of the travel: it springs back open, the same sheet.
        tapAll();
        Object reopened = onMain(this::drag);
        Finger finger2 = catchLow();
        finger2.moveBy(-.30f * travel, 500, 25);
        finger2.hold(150);
        finger2.up();
        awaitOpen("a catch taken up 30%");
        require(onMain(() -> drag() == reopened), "The caught sheet's reopening is a second sheet");
        // Caught low, taken up 30% and back to where it was caught: it carries on closing (a quarter from the catch decides).
        Object again = onMain(this::drag);
        Finger finger3 = catchLow();
        // Held, the caught sheet takes Back again, and the page knows a sheet is over it.
        await(() -> onMain(() -> Boolean.TRUE.equals(field(again, "focusable")) && activity.dialogShowing()),
                () -> "The caught sheet did not take Back again (" + describe() + ")");
        finger3.moveBy(-.30f * travel, 500, 25);
        finger3.moveBy(.30f * travel, 500, 25);
        States back = new States(again);
        finger3.hold(150);
        finger3.up();
        end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end && !back.gone()) SystemClock.sleep(10);
        seen = back.stop();
        require(!seen.contains(OPENING) && !seen.contains(OPEN), "A catch taken back to where it was caught reopened the sheet (states " + seen + ")");
        awaitGone("a catch taken back to its start");
        requireHome();
        // Caught and held, then Back: the sheet settles closed, and the rest of that touch (moves up, the lift) does nothing.
        tapAll();
        Object backed = onMain(this::drag);
        View backedView = onMain(this::sheetView);
        Finger finger4 = catchLow();
        await(() -> onMain(() -> Boolean.TRUE.equals(field(backed, "focusable"))), () -> "The caught sheet did not take Back again (" + describe() + ")");
        States afterBack = new States(backed);
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(() -> onMain(() -> Integer.valueOf(CLOSING).equals(field(backed, "state")) || !backedView.isAttachedToWindow()), () -> "Back on a held sheet did not close it (" + describe() + ")");
        float fromBack = onMain(() -> (Float) field(backed, "offset"));
        finger4.moveBy(-.30f * travel, 400, 20);
        float afterMoves = onMain(() -> (Float) field(backed, "offset"));
        require(!onMain(backedView::isAttachedToWindow) || afterMoves >= fromBack - 1, "After Back, the held sheet still followed the finger up (" + fromBack + " -> " + afterMoves + " px)");
        finger4.up();
        end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end && !afterBack.gone()) SystemClock.sleep(10);
        seen = afterBack.stop();
        // From the close Back started (the watch began on the held sheet, DRAGGED): nothing but closing and gone.
        List<Integer> sinceBack = seen.contains(CLOSING) ? seen.subList(seen.indexOf(CLOSING), seen.size()) : seen;
        require(!sinceBack.contains(OPENING) && !sinceBack.contains(OPEN) && !sinceBack.contains(DRAGGED), "After Back on a held sheet, the rest of the touch moved or reopened it (states " + seen + ")");
        awaitGone("Back on a held sheet");
        requireHome();
        // A row's close, let go by Widgets Home as the subject opens: no finger catches it (a pull of 30% does not reopen
        // it), it finishes closing, the subject stays, and nothing under the finger is clicked. The row is clicked and the
        // close it starts paused in one main-thread task, before any frame moves it: building the subject's page can
        // stall a slow machine's main thread for seconds, and the close would catch up on its next frame. The finger goes
        // down on the paused sheet, then the close resumes under it.
        tapAll();
        String rowSubject = onMain(() -> {
            for (String each : HubPages.SUBJECTS) { View view = sheetView().findViewWithTag("home-subject-" + each); if (shown(view)) return each; }
            return null;
        });
        require(rowSubject != null, "The All sheet shows no subject row");
        Object rowSheet = onMain(this::drag);
        View rowWindow = onMain(this::sheetView);
        View rowView = onMain(() -> rowWindow.findViewWithTag("home-subject-" + rowSubject));
        main(() -> rowView.requestRectangleOnScreen(new Rect(0, 0, rowView.getWidth(), rowView.getHeight()), true));
        settle();
        main(() -> {
            require(rowView.performClick(), "The All sheet's " + rowSubject + " row did not take its click");
            ValueAnimator closing = (ValueAnimator) field(rowSheet, "settling");
            require(closing != null && Integer.valueOf(CLOSING).equals(field(rowSheet, "state")), "The " + rowSubject + " row did not start the sheet's close (state " + field(rowSheet, "state") + ")");
            closing.pause();
        });
        require(onMain(() -> Boolean.TRUE.equals(field(rowSheet, "letGo"))), "Opening " + rowSubject + " did not let its closing sheet go (state " + onMain(() -> field(rowSheet, "state")) + ")");
        float[] onPanel = onMain(() -> middleOf(rowSheet));
        States letGo = new States(rowSheet);
        Finger finger5 = new Finger(onPanel);
        Object stateDown = onMain(() -> field(rowSheet, "state"));
        require(Integer.valueOf(CLOSING).equals(stateDown) && onMain(rowWindow::isAttachedToWindow), "A finger down on a let-go sheet caught it (state " + stateDown + ")");
        require(!Integer.valueOf(0).equals(onMain(() -> field(field(rowSheet, "frame"), "region"))), "The finger meant for the let-go sheet landed on the dim");
        main(() -> ((ValueAnimator) field(rowSheet, "settling")).resume());
        finger5.moveBy(-.30f * travel, 500, 25);
        finger5.hold(150);
        finger5.up();
        end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end && !letGo.gone()) SystemClock.sleep(10);
        seen = letGo.stop();
        require(!onMain(rowWindow::isAttachedToWindow), "The let-go sheet stayed after its settle");
        require(!seen.contains(DRAGGED) && !seen.contains(OPENING) && !seen.contains(OPEN), "A pull on the let-go sheet caught or reopened it (states " + seen + ")");
        await(() -> onMain(() -> rowSubject.equals(HubTestPages.current(activity))), "The subject the row opened did not stay (" + HubTestPages.current(activity) + ")");
        settle();
        require(onMain(() -> rowSubject.equals(HubTestPages.current(activity))), "A touch on the let-go sheet reached the page under it (now on " + onMain(() -> HubTestPages.current(activity)) + ")");
        back();
        requireHome();
        // A tap on the dim while the sheet settles closed is swallowed: the close carries on, and the page under it is not touched.
        tapAll();
        float[] dim = onMain(() -> {
            View panel = panel(); int[] at = new int[2]; panel.getLocationOnScreen(at);
            android.view.WindowInsets insets = panel.getRootWindowInsets();
            int bar = insets != null && Build.VERSION.SDK_INT >= 30 ? insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()).top : 0;
            return new float[]{at[0] + panel.getWidth() / 2f, (bar + at[1]) / 2f, at[1] - bar};
        });
        if (dim[2] > 16 * dp) {
            Object dimmed = onMain(this::drag);
            closeStarts(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
            States swallowed = new States(dimmed);
            tap(dim[0], dim[1]);
            end = SystemClock.uptimeMillis() + 15000;
            while (SystemClock.uptimeMillis() < end && !swallowed.gone()) SystemClock.sleep(10);
            seen = swallowed.stop();
            require(!seen.contains(DRAGGED) && !seen.contains(OPENING), "A tap on the dim while the sheet settled closed did more than nothing (states " + seen + ")");
            awaitGone("a tap on the dim while closing");
            requireHome();
        } else {
            back(); awaitGone("Back");
        }
        scale(1);
        checks.add("ACTION_CANCEL settles a pull where it is (a tenth closes, two fifths opens); a second finger settles it at once and the rest of the touch moves nothing; "
                + "a pull taken back to its start closes; Back goes to the page while the sheet settles closed; a finger on the closing sheet catches it: taken up 30% from where it "
                + "was caught it reopens (the same sheet), taken back to that point it carries on closing; held, it takes Back again; Back while held closes it and the rest "
                + "of that touch does nothing; a row's close, let go by Home as the subject opens, cannot be caught (a 30% pull does not reopen it) and reaches nothing under it; "
                + "a tap on the dim meanwhile is swallowed");
    }

    /**
     * In Volt's own window (no dialog window doing it for free): a row reaches no touch before it can be read, nested
     * sheets give the page back exactly as it was, and key focus moves into the sheet and back out as a dialog's would.
     */
    private void ownWindow() {
        earlyTap();
        nested();
        keys();
    }

    /**
     * A tap early in the opening (slowed; the first third of the travel, content still transparent) at a row's place under
     * the surface: nothing opens, and the sheet finishes opening. The tap is tried slower until it lands in that window.
     * The tap on All is pressed without waiting for the app to go idle (press): on a slow emulator the main thread is busy
     * drawing every frame of the slowed opening, so waitForIdleSync would return only once the sheet is open. If this
     * thread still gets its first look after the opening has run (its frames, watched from before the tap, saw it), it is
     * tried slower too; an opening no frame showed fails.
     */
    private void earlyTap() {
        requireHome();
        for (int slower = 10; ; slower *= 2) {
            scale(slower);
            HubTestPages.showDock(this, activity);
            States states = new States(null);
            float[] at = pressAndLook(states);
            if (at == null) {
                require(states.saw(OPENING), "The slowed opening never showed a row under its surface (" + describe() + ")");
                missed(slower, states);
            }
            Finger finger = at != null && at[2] <= 1 / 3f ? new Finger(at) : null;
            float[] landed = onMain(() -> drag() == null ? null : new float[]{1 - offset() / (Integer) field(drag(), "travel"), panel().getAlpha(), (Integer) field(drag(), "state")});
            boolean early = finger != null && landed != null && landed[2] == OPENING && landed[0] <= 1 / 3f && landed[1] < .5f;
            if (early) finger.up(); else if (finger != null) finger.cancel();
            long until = SystemClock.uptimeMillis() + 30000;
            while (SystemClock.uptimeMillis() < until) { Integer state = states.last(); if (state != null && state == OPEN || !onMain(() -> HubPages.HOME.equals(HubTestPages.current(activity)))) break; SystemClock.sleep(10); }
            List<Integer> seen = states.stop();
            settle();
            String page = onMain(() -> HubTestPages.current(activity));
            require(HubPages.HOME.equals(page), (at == null ? "The slowed opening (no early tap made)" : "A tap on a row early in the opening (" + pct(at[2]) + " of the way)") + " opened " + page);
            require(!seen.isEmpty() && seen.get(seen.size() - 1) == OPEN && !seen.contains(CLOSING), "After a tap early in the opening the sheet did not finish opening (states " + seen + ")");
            back(); awaitGone("Back");
            requireHome();
            if (early) {
                checks.add("A tap at a row's place under the surface early in the opening (" + pct(landed[0]) + " of the way, content alpha " + round(landed[1]) + ", scale " + slower + "): nothing opens and the sheet finishes opening");
                earlyDrag(slower);
                break;
            }
            require(slower < 80, "The slowed opening passed its first third before a row showed under its surface");
        }
        scale(1);
    }

    /**
     * At the same slowed scale (or slower, as earlyTap, when this thread's first look comes too late): a touch that starts
     * there and moves down takes the sheet (its rows never get it), and nothing opens.
     */
    private void earlyDrag(int slower) {
        for (; ; slower *= 2) {
            scale(slower);
            HubTestPages.showDock(this, activity);
            States states = new States(null);
            float[] at = pressAndLook(states);
            if (at == null || at[0] <= 0) {
                require(at != null || states.saw(OPENING), "The slowed opening never showed a row under its surface again (" + describe() + ")");
                if (at == null) missed(slower, states);
                long end = SystemClock.uptimeMillis() + 30000;
                while (SystemClock.uptimeMillis() < end && onMain(() -> drag() != null && !Integer.valueOf(OPEN).equals(field(drag(), "state")))) SystemClock.sleep(10);
                List<Integer> seen = states.stop();
                settle();
                require(onMain(() -> HubPages.HOME.equals(HubTestPages.current(activity))), "The slowed opening opened " + onMain(() -> HubTestPages.current(activity)));
                require(!seen.isEmpty() && seen.get(seen.size() - 1) == OPEN && !seen.contains(CLOSING), "The slowed opening did not finish opening (states " + seen + ")");
                back(); awaitGone("Back");
                requireHome();
                require(slower < 80, "The slowed opening passed its first third before a row showed under its surface again");
                continue;
            }
            states.stop();
            Object sheet = onMain(this::drag);
            int sheetTravel = onMain(() -> (Integer) field(sheet, "travel"));
            Finger finger = new Finger(at);
            boolean unreadable = onMain(() -> ((View) field(sheet, "panel")).getAlpha() < .5f);
            finger.moveBy(.15f * sheetTravel, 300, 15);
            Object held = onMain(() -> field(sheet, "state"));
            finger.up();
            require(!unreadable || Integer.valueOf(DRAGGED).equals(held), "A touch that began on a row early in the opening and moved down did not take the sheet (state " + held + ")");
            long end = SystemClock.uptimeMillis() + 30000;
            while (SystemClock.uptimeMillis() < end && onMain(() -> drag() != null && !Integer.valueOf(OPEN).equals(field(drag(), "state")))) SystemClock.sleep(10);
            settle();
            require(onMain(() -> HubPages.HOME.equals(HubTestPages.current(activity))), "A drag that began on a row early in the opening opened " + onMain(() -> HubTestPages.current(activity)));
            if (onMain(() -> drag() != null)) { back(); }
            awaitGone("a drag early in the opening");
            requireHome();
            if (unreadable) checks.add("A touch that began on a row early in the opening and moved down takes the sheet (DRAGGED) and opens nothing");
            return;
        }
    }

    /**
     * Presses the dock's All (states already watching, from before the press) with no idle wait, then looks at the opening
     * from this thread until earlyRow gives a point (returned), or the frames have seen the sheet open (null: the opening
     * ran its course before this thread got a look), or 15 s pass (null).
     */
    private float[] pressAndLook(States states) {
        press(onMain(() -> centreOf(root().findViewWithTag("hub-dock-handle"))));
        float[] at = null;
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end && (at = onMain(this::earlyRow)) == null && !states.saw(OPEN)) SystemClock.sleep(5);
        return at;
    }

    /** The frames showed the slowed opening but this thread looked only after it: noted in the log, and tried slower. */
    private void missed(int slower, States states) {
        android.util.Log.w("DockSheetValidation", "The slowed opening (scale " + slower + ") ran before this thread could look (frames saw " + onMain(() -> new ArrayList<>(states.seen)) + "); trying slower");
    }

    /**
     * Waits (up to 2 minutes) until the system's bars have held still for 6 s: the home app (which draws the taskbar and, on
     * the emulator's three-button navigation, the bar's buttons) keeps one process, and Volt's window keeps its size and
     * insets. After the sweep sets a step's window size the emulator's launcher can crash and restart over and over for up
     * to about half a minute (at 725 x 604 dp: DeviceProfile.calculateCellWidth, divide by zero), and each time its taskbar
     * goes and comes back, taking the window's bottom inset (168 px there) with it, and a new taskbar paints its own
     * background for a moment. Measured meanwhile, the sheet's travel is 168 px too long and the screen's bottom shows the
     * taskbar rather than the sheet. A system that never settles is noted, and the checks go on.
     */
    private void steadyBars() throws Exception {
        String resolved = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim();
        String last = resolved.isEmpty() ? "" : resolved.substring(resolved.lastIndexOf('\n') + 1).trim();
        String home = last.matches("[A-Za-z0-9_.]+/.+") ? last.substring(0, last.indexOf('/')) : null;
        long start = SystemClock.uptimeMillis(), end = start + 120000, since = start;
        String before = null;
        boolean steady = false;
        while (SystemClock.uptimeMillis() < end) {
            String now = (home == null ? "" : shell("pidof " + home).trim()) + " | " + onMain(() -> {
                View root = root(); android.view.WindowInsets insets = root.getRootWindowInsets();
                return root.getWidth() + "x" + root.getHeight() + " " + (insets == null || Build.VERSION.SDK_INT < 30 ? "-" : insets.getInsets(android.view.WindowInsets.Type.systemBars()).toString());
            });
            if (!now.equals(before)) { before = now; since = SystemClock.uptimeMillis(); }
            else if (SystemClock.uptimeMillis() - since >= 6000) { steady = true; break; }
            SystemClock.sleep(100);
        }
        long waited = (SystemClock.uptimeMillis() - start) / 1000;
        if (!steady) android.util.Log.w("DockSheetValidation", "The system's bars did not hold still for 6 s in 2 minutes (" + home + ": " + before + ")");
        checks.add(steady ? "The system's bars held still for 6 s before the checks (home app " + home + ", " + waited + " s)" : "The system's bars never held still for 6 s in " + waited + " s (home app " + home + "); checked anyway");
        settle();
    }

    /** On the main thread, while the sheet opens: {x, y, progress} of a point on a row inside the surface and the screen, or null. */
    private float[] earlyRow() {
        Object sheet = drag();
        if (sheet == null || !Integer.valueOf(OPENING).equals(field(sheet, "state"))) return null;
        View surface = (View) field(sheet, "surface");
        int travel = (Integer) field(sheet, "travel");
        if (travel <= 0 || surface.getHeight() <= 0) return null;
        float progress = 1 - offset() / travel;
        int[] box = new int[2];
        surface.getLocationOnScreen(box);
        int bottom = Math.min(box[1] + surface.getHeight(), activity.getResources().getDisplayMetrics().heightPixels);
        List<String> tags = new ArrayList<>();
        tags.add("home-search"); tags.add("home-settings");
        for (String each : HubPages.SUBJECTS) tags.add("home-subject-" + each);
        for (String tag : tags) {
            View row = sheetView().findViewWithTag(tag);
            if (row == null || row.getHeight() <= 0 || !row.isClickable()) continue;
            int[] at = new int[2];
            row.getLocationOnScreen(at);
            int top = Math.max(at[1], box[1]) + 2, end = Math.min(at[1] + row.getHeight(), bottom) - 2;
            if (end - top < 4 * dp) continue;
            float x = Math.max(box[0] + 4 * dp, Math.min(box[0] + surface.getWidth() - 4 * dp, at[0] + row.getWidth() / 2f));
            return new float[]{x, (top + end) / 2f, progress};
        }
        return progress > 1 / 3f ? new float[]{0, 0, progress} : null;
    }

    /**
     * Two sheets at once: a row's close let go (paused on its way out), Home back under it and All opened again over it.
     * The page stays hidden and blocked while either shows, the let-go overlay is never taken for the page, and once both
     * have gone every window view is exactly as it was before the first.
     */
    private void nested() {
        requireHome();
        java.util.Map<View, int[]> before = onMain(this::windowLook);
        scale(10);
        tapAll();
        String subject = onMain(() -> {
            for (String each : HubPages.SUBJECTS) { View view = sheetView().findViewWithTag("home-subject-" + each); if (shown(view)) return each; }
            return null;
        });
        require(subject != null, "The All sheet shows no subject row");
        Object old = onMain(this::drag);
        View oldWindow = onMain(this::sheetView);
        View row = onMain(() -> oldWindow.findViewWithTag("home-subject-" + subject));
        main(() -> {
            require(row.performClick(), "The All sheet's " + subject + " row did not take its click");
            ValueAnimator closing = (ValueAnimator) field(old, "settling");
            require(closing != null, "The " + subject + " row did not start the sheet's close");
            closing.pause();
        });
        require(onMain(() -> Boolean.TRUE.equals(field(old, "letGo"))), "Opening " + subject + " did not let its closing sheet go");
        await(() -> onMain(() -> subject.equals(HubTestPages.current(activity))), "The row did not open " + subject);
        settle();
        back();
        requireHome();
        require(onMain(oldWindow::isAttachedToWindow), "The paused let-go sheet went before Home came back");
        // Settling closed, it hides the page but leaves it focus (a page a row opened may focus its field meanwhile).
        List<String> problems = new ArrayList<>();
        covered(before, false, "with a let-go sheet still showing", problems);
        // Its overlay takes every touch (as it should): All by its click.
        main(() -> require(root().findViewWithTag("hub-dock-handle").performClick(), "All did not take its click"));
        awaitOpen("All over a let-go sheet");
        Object now = onMain(this::drag);
        require(now != old, "All over a let-go sheet is the same sheet");
        covered(before, true, "with a new sheet over a let-go one", problems);
        if (onMain(() -> oldWindow.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS))
            problems.add("The new sheet covered the let-go sheet's overlay as if it were the page");
        main(() -> ((ValueAnimator) field(old, "settling")).resume());
        await(() -> !onMain(oldWindow::isAttachedToWindow), "The let-go sheet did not finish going");
        settle();
        covered(before, true, "after the let-go sheet went under the open one", problems);
        back(); awaitGone("Back");
        settle();
        java.util.Map<View, int[]> after = onMain(this::windowLook);
        // The end state first (the finding itself), then what was seen on the way.
        for (java.util.Map.Entry<View, int[]> each : before.entrySet()) {
            int[] was = each.getValue(), is = after.get(each.getKey());
            require(is != null, "A window view went missing (" + each.getKey() + ")");
            require(java.util.Arrays.equals(was, is), "After two sheets " + each.getKey().getClass().getSimpleName() + " is " + java.util.Arrays.toString(is) + ", was " + java.util.Arrays.toString(was) + " (importance, focusability)"
                    + (problems.isEmpty() ? "" : "; also " + problems));
        }
        require(problems.isEmpty(), "Two sheets at once: " + problems);
        scale(1);
        checks.add("Two sheets at once (a let-go sheet still going when All opens again): the page stays hidden from TalkBack and blocked from focus while either shows, the let-go overlay is not taken for the page, "
                + "and once both have gone every window view's accessibility importance and focusability are as they were");
    }

    /** On the main thread: the window's views other than sheets' overlays, each {importance, descendant focusability or -1}. */
    private java.util.Map<View, int[]> windowLook() {
        java.util.Map<View, int[]> look = new java.util.IdentityHashMap<>();
        ViewGroup window = (ViewGroup) root();
        for (int i = 0; i < window.getChildCount(); i++) {
            View child = window.getChildAt(i);
            if (child.getClass().getName().startsWith("com.jjricks.volt.DragSheet")) continue;
            look.put(child, new int[]{child.getImportantForAccessibility(), child instanceof ViewGroup ? ((ViewGroup) child).getDescendantFocusability() : -1});
        }
        return look;
    }

    /** The page's views (those of before) are hidden from TalkBack and, if blocked, from focus; else notes it in problems. */
    private void covered(java.util.Map<View, int[]> before, boolean blocked, String what, List<String> problems) {
        main(() -> {
            assertions++;
            for (View view : before.keySet()) {
                if (view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) { problems.add("the page shows to TalkBack " + what); return; }
                if (blocked && view instanceof ViewGroup && ((ViewGroup) view).getDescendantFocusability() != ViewGroup.FOCUS_BLOCK_DESCENDANTS) { problems.add("the page takes key focus " + what); return; }
            }
        });
    }

    /**
     * Keys, out of touch mode (a keyboard or D-pad): with Home's lock focused, All opened by TalkBack's click takes key
     * focus off the lock, so ENTER never reaches it. Opened from All by ENTER, with Settings focused in the sheet, Back
     * then ENTER at once: the closing sheet's rows lose focus (All is hidden under the morph, so the overlay holds it in
     * All's place), Settings never opens and the sheet carries on closing. Gone, focus is back on All.
     */
    private void keys() {
        requireHome();
        HubTestPages.showDock(this, activity);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        settle();
        View lock = onMain(() -> {
            View found = (View) field(activity, "lockControl");
            if (shown(found)) return found;
            @SuppressWarnings("unchecked") List<Object> locks = (List<Object>) field(activity, "dashboardLocks");
            for (Object each : locks) { View view = (View) field(each, "lockControl"); if (shown(view)) return view; }
            return null;
        });
        require(lock != null, "Widgets Home shows no lock control");
        main(() -> require(!lock.isInTouchMode() && lock.requestFocus() && lock.isFocused(), "Home's lock did not take key focus (touch mode " + lock.isInTouchMode() + ")"));
        Object lockBefore = onMain(() -> invoke(lock, "shown"));
        View all = onMain(() -> root().findViewWithTag("hub-dock-handle"));
        main(() -> require(all.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null), "All did not take TalkBack's click"));
        awaitOpen("All by TalkBack's click");
        String focusedOpen = onMain(() -> focusName(root().findFocus()));
        boolean lockHeld = onMain(lock::isFocused);
        sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        settle();
        Object lockAfter = onMain(() -> invoke(lock, "shown"));
        require(java.util.Objects.equals(lockBefore, lockAfter), "ENTER under the sheet All opened reached Home's lock (" + lockBefore + " -> " + lockAfter + ", focus on " + focusedOpen + ")");
        require(!lockHeld, "Home's lock kept key focus under the open sheet");
        require(focusedOpen.startsWith("sheet"), "The open sheet did not take key focus (focus on " + focusedOpen + ")");
        // Back to Home with the sheet gone, whatever the ENTER pressed in it.
        for (int tries = 0; tries < 4 && !onMain(() -> HubPages.HOME.equals(HubTestPages.current(activity)) && drag() == null); tries++) back();
        awaitGone("ENTER in the sheet");
        requireHome();
        checks.add("Keys out of touch mode: All opened by TalkBack's click takes key focus off Home's lock (focus on " + focusedOpen + "; ENTER changes nothing on the lock)");
        keysBack();
    }

    /** Back then ENTER at once with Settings focused in the sheet (keys' second half; -e only keys-back runs it alone). */
    private void keysBack() {
        requireHome();
        HubTestPages.showDock(this, activity);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        settle();
        String backTo = "";
        for (int slower = 5; ; slower *= 2) {
            scale(slower);
            View allNow = onMain(() -> root().findViewWithTag("hub-dock-handle"));
            main(() -> require(!allNow.isInTouchMode() && allNow.requestFocus(), "All did not take key focus"));
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            awaitOpen("ENTER on All");
            Object sheet = onMain(this::drag);
            View settings = onMain(() -> sheetView().findViewWithTag("home-settings"));
            require(settings != null, "The All sheet has no Settings row");
            main(() -> require(settings.requestFocus() && settings.isFocused(), "The sheet's Settings row did not take key focus"));
            States closes = new States(sheet);
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            Object state = onMain(() -> field(sheet, "state"));
            String focused = onMain(() -> focusName(root().findFocus()));
            boolean closing = Integer.valueOf(CLOSING).equals(state) && onMain(() -> ((View) field(sheet, "frame")).isAttachedToWindow());
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            long end = SystemClock.uptimeMillis() + 30000;
            while (SystemClock.uptimeMillis() < end && !closes.gone() && onMain(() -> HubPages.HOME.equals(HubTestPages.current(activity)))) SystemClock.sleep(10);
            List<Integer> seen = closes.stop();
            settle();
            String page = onMain(() -> HubTestPages.current(activity));
            require(!HubPages.SETTINGS.equals(page), "ENTER straight after Back opened Settings from the closing sheet (" + (closing ? "closing" : "state " + state) + ", focus on " + focused + ")");
            require(HubPages.HOME.equals(page), "ENTER straight after Back opened " + page + " (focus on " + focused + ")");
            List<Integer> sinceBack = seen.contains(CLOSING) ? seen.subList(seen.indexOf(CLOSING), seen.size()) : seen;
            require(!sinceBack.contains(OPENING) && !sinceBack.contains(OPEN), "ENTER straight after Back reopened the sheet (states " + seen + ", focus on " + focused + ")");
            awaitGone("Back then ENTER");
            if (closing) {
                // While it closed, its rows held no focus (the overlay held it for the hidden All); gone, focus is back on All.
                require(!focused.contains("home-settings"), "The closing sheet's Settings row kept key focus (" + focused + ")");
                String home = onMain(() -> focusName(root().findFocus()));
                require(onMain(allNow::isFocused), "Key focus did not go back to All after the sheet closed (focus on " + home + "; while closing on " + focused + ")");
                backTo = focused;
                break;
            }
            require(slower < 40, "The slowed close ended before an ENTER straight after Back (state " + state + ")");
            requireHome();
        }
        scale(1);
        checks.add("Keys out of touch mode: with Settings focused in the sheet All opened by ENTER, Back then ENTER at once "
                + "never opens Settings or anything else: the closing sheet's rows lose focus (focus on " + backTo + " while it closes) and the sheet carries on closing; gone, focus is back on All");
    }

    /** On the main thread: where key focus is ("sheet: …" inside a sheet's overlay, "page: …" otherwise, "none"). */
    private String focusName(View focused) {
        if (focused == null) return "none";
        boolean inSheet = false;
        for (Object at = focused; at instanceof View; at = ((View) at).getParent()) if (at.getClass().getName().startsWith("com.jjricks.volt.DragSheet")) inSheet = true;
        Object tag = focused.getTag();
        CharSequence said = focused.getContentDescription();
        return (inSheet ? "sheet: " : "page: ") + focused.getClass().getSimpleName() + (tag != null ? " " + tag : "") + (said != null ? " \"" + said + "\"" : "");
    }

    /**
     * A new height while the sheet settles open (slowed to scale 10): its restart is posted from the layout pass, and a
     * click handled before that runs (here: run at the front of the main queue straight after the layout) must win. Three
     * ways: nothing else, so the opening carries on to open at the new height; close(), so the stale restart must not
     * reopen it; close() then leave() (a row's close as its page opens), so it must not reopen and must finish closing.
     */
    private void relayout() {
        scale(10);
        String[] ways = {"a new height mid-open", "a new height mid-open, then close() before its restart runs", "a new height mid-open, then close() and leave() before its restart runs"};
        for (int way = 0; way < ways.length; way++) {
            int which = way;
            // Well into the opening (under three fifths of the way still to go), so the close that follows is a real settle
            // (from near closed, a shorter sheet is already past its new closed end and goes at once). Caught on the main
            // thread at the first frame it holds, not by polling: a slow machine's stall can carry a whole stretch of the
            // opening past in one frame. Should the opening reach open without a frame in that stretch, it tries again.
            Object[] caught = {null};
            // The travel before the new height and with it (read as it is laid out: a let-go sheet's slide has its own).
            int[] travelBefore = {0, 0};
            String[] layouts = {""};
            States[] watched = {null};
            boolean[] ran = new boolean[2];
            for (int attempt = 0; attempt < 4 && caught[0] == null; attempt++) {
                if (attempt > 0) { back(); awaitGone("Back after a missed opening"); }
                HubTestPages.showDock(this, activity);
                boolean[] watching = {true}, missed = {false};
                main(() -> {
                    Choreographer.FrameCallback[] tick = {null};
                    tick[0] = t -> {
                        if (!watching[0]) return;
                        Object sheet = drag();
                        if (sheet != null && Integer.valueOf(OPEN).equals(field(sheet, "state"))) { watching[0] = false; missed[0] = true; return; }
                        if (sheet != null && Integer.valueOf(OPENING).equals(field(sheet, "state")) && field(sheet, "settling") != null
                                && (Integer) field(sheet, "travel") > 0 && (Float) field(sheet, "offset") < .6f * (Integer) field(sheet, "travel")) {
                            watching[0] = false;
                            caught[0] = sheet;
                            // Watched from this frame on: a slow machine may run the whole close before this thread looks.
                            watched[0] = new States(sheet, true);
                            travelBefore[0] = (Integer) field(sheet, "travel");
                            newHeight(sheet, which, travelBefore, ran, layouts);
                            return;
                        }
                        Choreographer.getInstance().postFrameCallback(tick[0]);
                    };
                    Choreographer.getInstance().postFrameCallback(tick[0]);
                });
                tap(onMain(() -> centreOf(root().findViewWithTag("hub-dock-handle"))));
                await(() -> onMain(() -> caught[0] != null || missed[0]), () -> ways[which] + ": All did not open the sheet (" + describe() + ")");
                main(() -> watching[0] = false);
            }
            require(caught[0] != null, ways[which] + ": no frame of All's opening came between two fifths and open in four tries (" + describe() + ")");
            Object sheet = caught[0];
            View window = onMain(() -> (View) field(sheet, "frame"));
            States states = watched[0];
            await(() -> onMain(() -> ran[0] && (which == 0 || ran[1])), () -> ways[which] + ": the new height was never laid out (" + describe() + "; " + layouts[0] + ")");
            int before = travelBefore[0];
            int after = travelBefore[1];
            require(after < before, ways[which] + ": the travel did not change (" + before + " -> " + after + " px; " + layouts[0] + ")");
            if (which == 0) {
                awaitOpen(ways[which]);
                List<Integer> seen = states.stop();
                require(!seen.contains(CLOSING), ways[which] + ": the opening did not carry on (states " + seen + ")");
                main(() -> require(drag() == sheet && Math.abs(offset()) < .5f && (Integer) field(sheet, "travel") == after, ways[which] + ": not open at the new height"));
                back(); awaitGone("Back");
            } else {
                if (which == 2) main(() -> require(Boolean.TRUE.equals(field(sheet, "letGo")), ways[which] + ": the sheet was not let go"));
                long end = SystemClock.uptimeMillis() + 20000;
                while (SystemClock.uptimeMillis() < end && !states.gone()) SystemClock.sleep(10);
                List<Integer> seen = states.stop();
                require(!onMain(window::isAttachedToWindow), ways[which] + ": the sheet never finished closing (states " + seen + ", " + onMain(() -> "state " + field(sheet, "state") + ", offset " + field(sheet, "offset")) + ")");
                List<Integer> sinceClose = seen.contains(CLOSING) ? seen.subList(seen.indexOf(CLOSING), seen.size()) : seen;
                require(seen.contains(CLOSING), ways[which] + ": its close was never seen settling (states " + seen + ")");
                require(!sinceClose.contains(OPENING) && !sinceClose.contains(OPEN), ways[which] + ": the stale restart reopened it (states " + seen + ")");
                awaitGone(ways[which]);
                requireHome();
            }
        }
        scale(1);
        checks.add("A new height while the sheet settles open: the opening carries on to open at the new height; with close() handled before its posted restart runs, "
                + "the stale restart does not reopen it and it finishes closing; the same with close() and leave() (let go): no reopening, it finishes closing and goes");
    }

    /**
     * On the main thread, mid-opening: makes the sheet's list 48 dp shorter, and once that new height is laid out (the
     * sheet's own listener ran first and posted its restart) runs close() (way 1) or close() and leave() (way 2) at the
     * front of the main queue, ahead of that restart.
     */
    private void newHeight(Object sheet, int which, int[] before, boolean[] ran, String[] seen) {
        View panel = (View) field(sheet, "panel");
        ScrollView list = (ScrollView) field(sheet, "list");
        int target = Math.max(Math.round(48 * dp), list.getHeight() - Math.round(48 * dp));
        seen[0] = "list " + list.getHeight() + " -> " + target + " px, panel top " + panel.getTop() + ", dock " + dockTop();
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int now = (Integer) field(sheet, "travel");
                // Another change laid out first (its content still arriving): the travel to compare with is that one's.
                if (list.getHeight() != target) { before[0] = now; return; }
                if (now == before[0]) return;
                before[1] = now;
                seen[0] += "; then list " + list.getHeight() + " px, panel top " + panel.getTop() + ", dock " + dockTop();
                v.removeOnLayoutChangeListener(this);
                ran[0] = true;
                if (which > 0) new android.os.Handler(android.os.Looper.getMainLooper()).postAtFrontOfQueue(() -> {
                    invoke(sheet, "close");
                    if (which == 2) invoke(sheet, "leave");
                    ran[1] = true;
                });
            }
        });
        ViewGroup.LayoutParams size = list.getLayoutParams();
        size.height = target;
        list.setLayoutParams(size);
    }
    /** On the main thread: the dock's top on screen (px), or -1. */
    private int dockTop() {
        Object dock = field(activity, "dock");
        if (dock == null) return -1;
        int[] at = new int[2]; ((View) field(dock, "view")).getLocationOnScreen(at);
        return at[1];
    }

    /**
     * Back on the open sheet (scale 10), then a finger down on the sheet once it is two fifths of the way closed: returns
     * that finger, held, with the sheet caught (DRAGGED) where it was.
     */
    private Finger catchLow() {
        closeStarts(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
        await(() -> onMain(() -> drag() != null && offset() >= .40f * travel), () -> "The slowed close never reached two fifths (" + describe() + ")");
        // The middle of what shows of it: a slow machine may move the closing sheet on a long way before the touch lands.
        float[] at = onMain(this::middleOfSheet);
        Finger finger = new Finger(at);
        Object state = onMain(() -> drag() == null ? null : field(drag(), "state"));
        require(Integer.valueOf(DRAGGED).equals(state), "A finger down on the closing sheet did not catch it (" + describe() + ")");
        return finger;
    }

    /** On the main thread: the middle of what shows of the dock's sheet (its surface, between its top and the window's bottom). */
    private float[] middleOfSheet() { return middleOf(drag()); }
    private float[] middleOf(Object sheet) {
        View surface = (View) field(sheet, "surface"), frame = (View) field(sheet, "frame");
        int[] p = new int[2], f = new int[2]; surface.getLocationOnScreen(p); frame.getLocationOnScreen(f);
        float bottom = Math.min(p[1] + surface.getHeight(), f[1] + frame.getHeight());
        return new float[]{p[0] + surface.getWidth() / 2f, (p[1] + bottom) / 2f};
    }

    /**
     * Every frame at animator scale 5: the opening, the snap-back and the close, no gap under the sheet from open on, and
     * the dock and the sheet as one surface (oneSurface) through each.
     */
    private void frames() {
        scale(5);
        worstStep = worstSlip = worstDockMatch = 0; framesChecked = 0;
        Sampler sampler = new Sampler();
        // The opening from closed (All).
        HubTestPages.showDock(this, activity);
        sampler.start();
        tapAll();
        List<float[]> open = sampler.stop();
        oneSurface(open, "All's opening", true, false);
        float overshoot = opening(open, "All's opening");
        require(overshoot >= 2 * dp, "All's opening shows no bounce (" + overshoot / dp + " dp past open)");
        // The close (Back): toward closed only.
        sampler.start();
        back(); awaitGone("Back");
        List<float[]> shut = sampler.stop();
        oneSurface(shut, "Back's close", false, true);
        closing(shut, "Back's close");
        // A pull to two fifths: the finger's frames and the opening.
        HubTestPages.showDock(this, activity);
        sampler.start();
        pullTo(.40, 900, true);
        awaitOpen("a slowed pull to two fifths");
        List<float[]> pull = sampler.stop();
        oneSurface(pull, "a pull's opening", true, false);
        float pulled = opening(pull, "a pull's opening");
        // A drag of the open sheet a tenth down, let go: back to open.
        sampler.start();
        Finger finger = new Finger(centre(handle()));
        finger.moveBy(.10f * travel, 600, 30);
        finger.hold(150);
        finger.up();
        awaitOpen("a slowed drag a tenth down");
        List<float[]> back = sampler.stop();
        oneSurface(back, "a drag a tenth down and its return", false, false);
        noGap(back, "a drag a tenth down and its return");
        // Dragged two fifths down and let go: it closes into the dock.
        sampler.start();
        finger = new Finger(centre(handle()));
        finger.moveBy(.40f * travel, 700, 40);
        finger.hold(120);
        finger.up();
        awaitGone("a slowed drag two fifths down");
        List<float[]> down = sampler.stop();
        oneSurface(down, "a drag two fifths down and its close", false, true);
        closing(down, "a drag two fifths down and its close");
        // A pull to a tenth, held and let go: the snap-back, toward closed only.
        HubTestPages.showDock(this, activity);
        sampler.start();
        pullTo(.10, 700, true);
        awaitGone("a slowed pull to a tenth");
        List<float[]> snap = sampler.stop();
        oneSurface(snap, "a held pull's snap-back", true, true);
        float still = closing(snap, "a held pull's snap-back");
        require(still <= .5f, "a held pull's snap-back moved toward open first (" + still + " px)");
        // The same let go while the finger still moves up: it carries on a little, then goes, with no bounce.
        HubTestPages.showDock(this, activity);
        sampler.start();
        pullTo(.10, 700, false);
        awaitGone("a slowed pull to a tenth let go moving");
        List<float[]> moving = sampler.stop();
        oneSurface(moving, "a moving pull's snap-back", true, true);
        float carried = closing(moving, "a moving pull's snap-back");
        scale(1);
        checks.add("At animator scale 5, every frame: All's opening and a pull's opening move only toward open until one overshoot (" + round(overshoot / dp) + " dp and "
                + round(pulled / dp) + " dp, cap " + spring("CAP_DP") + " dp) and back with no second ripple of 1 dp; Back's close, a drag down's close and a held pull's snap-back move only toward closed (let go still moving up, it carries on " + round(carried / dp) + " dp, then closes with no bounce); "
                + "from open on the sheet's surface never ends above the screen's bottom");
        checks.add("At animator scale 5, the dock and the sheet are one surface in all " + framesChecked + " frames of the sheet drawn (All, Back, a pull, a drag back and a drag down, two snap-backs): never both or neither; "
                + "every opening's first frame and every close's and snap-back's last are the dock's bounds (as far as their progress moved them) within " + round(worstDockMatch) + " px, then the dock alone; the top follows the offset within " + round(worstSlip)
                + " px a frame and the sides and bottom never step more than " + round(Math.max(0, worstStep)) + " px beyond what the progress moved them; the dock's buttons are gone by 25% and the sheet's content hidden up to 35%, never both");
    }

    /** Animations off: the finger is still followed, and every release, tap and close jumps. */
    private void animationsOff() {
        scale(0);
        // All: open at once.
        List<Integer> seen = tapAll();
        require(!seen.contains(OPENING), "With animations off, All's sheet still settled open (states " + seen + ")");
        seen = closeBy(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
        require(!seen.contains(CLOSING), "With animations off, Back's close still settled (states " + seen + ")");
        // A pull follows the finger, and its release jumps open.
        Finger finger = new Finger(dockCentre());
        finger.moveBy(-.40f * travel, 800, 40);
        followsFinger(finger, "With animations off");
        finger.hold(120);
        finger.up();
        waitForIdleSync();
        main(() -> require(drag() != null && field(drag(), "state").equals(OPEN) && Math.abs(offset()) < .5f && field(drag(), "settling") == null,
                "With animations off, a pull's release did not jump open"));
        // The handle dragged two fifths down: gone at once.
        finger = new Finger(centre(handle()));
        finger.moveBy(.40f * travel, 800, 40);
        finger.hold(120);
        finger.up();
        waitForIdleSync();
        main(() -> require(drag() == null && field(dock(), "sheet") == null, "With animations off, a drag down two fifths did not close the sheet at once"));
        scale(1);
        checks.add("Animations off (scale 0): All opens and Back closes at once; a pull follows the finger and its release jumps open; a drag down closes at once");
    }

    /** The activity recreated mid-pull (a rotation, fold, resize or text-size change rebuilds Home): no sheet left, no crash. */
    private void activityGoes() {
        Finger finger = new Finger(dockCentre());
        finger.moveBy(-.40f * travel, 800, 40);
        View stray = onMain(this::sheetView);
        require(stray != null && stray.isAttachedToWindow(), "The pull showed no sheet");
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity was = activity;
        main(was::recreate);
        Activity next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        require(next instanceof MainActivity && next != was, "The activity was not recreated");
        activity = (MainActivity) next;
        // The rest of the touch: its window is gone, so it may go nowhere.
        try { finger.moveBy(-20 * dp, 100, 5); finger.up(); } catch (RuntimeException dropped) { /* the old window took the touch with it */ }
        await(() -> activity.service() != null, "The recreated activity has no service");
        settle();
        requireHome();
        await(() -> onMain(() -> !stray.isAttachedToWindow()), "The sheet outlived the activity that pulled it");
        main(() -> require(field(dock(), "sheet") == null, "The new dock has a sheet"));
        tapAll();
        // Recreated while the sheet settles closed (slowed): it goes at once with the old activity, and the new Home takes touches.
        scale(10);
        closeStarts(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
        View closing = onMain(this::sheetView);
        require(closing != null && closing.isAttachedToWindow(), "The closing sheet is not showing");
        monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity before = activity;
        main(before::recreate);
        next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        require(next instanceof MainActivity && next != before, "The activity was not recreated while the sheet closed");
        activity = (MainActivity) next;
        await(() -> activity.service() != null, "The recreated activity has no service");
        settle();
        requireHome();
        await(() -> onMain(() -> !closing.isAttachedToWindow()), "The closing sheet outlived the activity that showed it");
        scale(1);
        tapAll();
        back(); awaitGone("Back");
        checks.add("The activity recreated mid-pull or while the sheet settles closed: the sheet goes with it, the new Widgets Home's dock has none, and All opens a new one");
    }

    // ===== Pictures =====

    /**
     * -e mode pose: each of -e poses in turn, held for -e pose_seconds, with a status line pose=NAME (and its index) once
     * it is in place. top and busy scroll Home (its top, or far enough down that widgets sit behind the dock); dock is the
     * dock alone (no sheet); a number 0..1 is the sheet pulled to that progress from the dock and held (one pull for all
     * of them, never let go). Checks nothing but that each pose is reached.
     */
    private void poses() {
        long hold = Math.round(Double.parseDouble(arguments.getString("pose_seconds", "4")) * 1000);
        String[] list = arguments.getString("poses", "dock,0,0.15,0.4,0.7,1").split(",");
        int[] token = {-1};
        Object[] held = {null};
        for (int index = 0; index < list.length; index++) {
            String pose = list[index].trim();
            if (pose.equals("top") || pose.equals("busy")) {
                endPose(held);
                main(() -> {
                    ScrollView page = homeScroll();
                    if (page == null) return;
                    int range = Math.max(0, page.getChildAt(0).getHeight() - page.getHeight());
                    page.scrollTo(0, pose.equals("top") ? 0 : Math.min(range, Math.round(page.getHeight() * .45f)));
                });
                settle();
                SystemClock.sleep(600);
            } else if (pose.equals("dock")) {
                endPose(held);
                HubTestPages.showDock(this, activity);
            } else {
                double progress = Double.parseDouble(pose);
                if (held[0] == null) {
                    HubTestPages.showDock(this, activity);
                    main(() -> {
                        held[0] = invoke(dock(), "allSheet");
                        token[0] = (Integer) invoke(held[0], "pull");
                    });
                    require(token[0] >= 0, "The sheet could not be pulled for a pose");
                    await(() -> onMain(() -> (Integer) field(held[0], "travel") > 0), "The pulled sheet was never laid out");
                }
                main(() -> invoke(held[0], "follow", new Class<?>[]{int.class, float.class}, token[0], (float) (progress * (Integer) field(held[0], "travel"))));
                settle();
                main(() -> require(Integer.valueOf(PULLED).equals(field(held[0], "state")), "The posed sheet is not held"));
            }
            SystemClock.sleep(500);
            Bundle status = new Bundle();
            status.putString("pose", pose);
            status.putInt("index", index);
            sendStatus(1, status);
            SystemClock.sleep(hold);
        }
        endPose(held);
        checks.add("Posed " + String.join(", ", list));
    }
    private void endPose(Object[] held) {
        if (held[0] == null) return;
        Object sheet = held[0];
        held[0] = null;
        main(() -> invoke(sheet, "dismissNow"));
        settle();
    }
    /**
     * For recordings driven from adb (-e mode hold): a status line with where to touch, in screen px: the dock's centre
     * (dock_x, dock_y), its All item (all_x, all_y), the open sheet's grab handle (handle_x, handle_y) and the sheet's
     * travel (opened with All once, then closed with Back, at the animator scale as it is).
     */
    private void where() {
        HubTestPages.showDock(this, activity);
        float[] dock = dockCentre(), all = onMain(() -> centreOf(root().findViewWithTag("hub-dock-handle")));
        tapAll();
        float[] grab = centre(handle());
        int travel = onMain(() -> (Integer) field(drag(), "travel"));
        back(); awaitGone("Back");
        HubTestPages.showDock(this, activity);
        Bundle status = new Bundle();
        status.putString("where", String.format(Locale.ROOT, "dock_x=%.0f dock_y=%.0f all_x=%.0f all_y=%.0f handle_x=%.0f handle_y=%.0f travel=%d",
                dock[0], dock[1], all[0], all[1], grab[0], grab[1], travel));
        sendStatus(1, status);
    }

    /** Widgets Home's own scrolling page: the tallest shown ScrollView in the window. */
    private ScrollView homeScroll() {
        List<View> all = new ArrayList<>(); all.add(root());
        ScrollView best = null;
        for (int i = 0; i < all.size(); i++) {
            View view = all.get(i);
            if (view instanceof ScrollView && shown(view) && ((ScrollView) view).getChildCount() > 0 && (best == null || view.getHeight() > best.getHeight())) best = (ScrollView) view;
            if (view instanceof ViewGroup) for (int c = 0; c < ((ViewGroup) view).getChildCount(); c++) all.add(((ViewGroup) view).getChildAt(c));
        }
        return best;
    }

    // ===== Frames =====

    /** A sampled frame's fields (Sampler): the sheet's offset, its surface's bottom less the screen's, its state, ... */
    private static final int F_OFFSET = 0, F_GAP = 1, F_STATE = 2, F_LEFT = 3, F_TOP = 4, F_RIGHT = 5, F_BOTTOM = 6, F_DOCK = 7,
            F_OVERLAY = 8, F_BUTTONS = 9, F_CONTENT = 10, F_P = 11, F_TRAVEL = 12, F_DOCK_LEFT = 13, F_DOCK_TOP = 14, F_DOCK_RIGHT = 15,
            F_DOCK_BOTTOM = 16, F_MORPH = 17, F_FROZEN = 18, F_OPEN_LEFT = 19, F_OPEN_RIGHT = 20, F_OPEN_BOTTOM = 21, F_CONTENT_Z = 22, F_SURFACE_Z = 23, F_SIZE = 24;
    /** The shape's easing (SheetMorph.ease, Motion.STANDARD) is never steeper than this (its steepest is 4.05). */
    private static final float EASE_SLOPE = 4.2f;

    /**
     * Every frame as it is drawn (an OnDrawListener on Volt's window, which the sheet's overlay is part of): what shows
     * of the dock and of the sheet, in screen px. Frames with no sheet are recorded too (the dock alone).
     */
    private final class Sampler {
        private final List<float[]> samples = new ArrayList<>();
        private android.view.ViewTreeObserver.OnDrawListener listener;
        private android.view.ViewTreeObserver observer;
        private Object last;
        void start() {
            samples.clear();
            main(() -> {
                last = drag();
                listener = () -> samples.add(sample());
                observer = root().getViewTreeObserver();
                observer.addOnDrawListener(listener);
            });
        }
        List<float[]> stop() {
            main(() -> { if (observer.isAlive()) observer.removeOnDrawListener(listener); listener = null; });
            return new ArrayList<>(samples);
        }
        /** On the main thread, as a frame is drawn. */
        private float[] sample() {
            float[] s = new float[F_SIZE];
            java.util.Arrays.fill(s, Float.NaN);
            Object dock = activity == null ? null : field(activity, "dock");
            Object sheet = dock == null ? null : field(dock, "drag");
            if (sheet != null) last = sheet; else sheet = last;
            View view = dock == null ? null : (View) field(dock, "view");
            s[F_DOCK] = view != null && view.isShown() && view.getAlpha() > 0 ? 1 : 0;
            if (view != null) {
                int[] at = new int[2]; view.getLocationOnScreen(at);
                android.graphics.drawable.Drawable shape = view.getBackground();
                while (shape instanceof android.graphics.drawable.DrawableWrapper && ((android.graphics.drawable.DrawableWrapper) shape).getDrawable() != null)
                    shape = ((android.graphics.drawable.DrawableWrapper) shape).getDrawable();
                Rect bounds = shape == null ? new Rect() : new Rect(shape.getBounds());
                if (bounds.isEmpty()) bounds.set(0, 0, view.getWidth(), view.getHeight());
                s[F_DOCK_LEFT] = at[0] + bounds.left; s[F_DOCK_TOP] = at[1] + bounds.top; s[F_DOCK_RIGHT] = at[0] + bounds.right; s[F_DOCK_BOTTOM] = at[1] + bounds.bottom;
            }
            View frame = sheet == null ? null : (View) field(sheet, "frame");
            if (frame == null || !frame.isAttachedToWindow()) { s[F_OVERLAY] = 0; return s; }
            View surface = (View) field(sheet, "surface"), panel = (View) field(sheet, "panel");
            s[F_OVERLAY] = surface.getWidth() > 0 && surface.getHeight() > 0 ? 1 : 0;
            int[] at = new int[2], f = new int[2];
            surface.getLocationOnScreen(at); frame.getLocationOnScreen(f);
            s[F_LEFT] = at[0]; s[F_TOP] = at[1]; s[F_RIGHT] = at[0] + surface.getWidth(); s[F_BOTTOM] = at[1] + surface.getHeight();
            s[F_GAP] = s[F_BOTTOM] - (f[1] + frame.getHeight());
            s[F_OFFSET] = (Float) field(sheet, "offset");
            s[F_STATE] = (Integer) field(sheet, "state");
            int travel = (Integer) field(sheet, "travel");
            s[F_TRAVEL] = travel;
            s[F_P] = travel > 0 ? 1 - s[F_OFFSET] / travel : 0;
            s[F_BUTTONS] = (float) (double) (Double) field(surface, "buttons");
            s[F_CONTENT] = panel.getAlpha();
            s[F_CONTENT_Z] = panel.getZ(); s[F_SURFACE_Z] = surface.getZ();
            s[F_MORPH] = Boolean.TRUE.equals(field(sheet, "morph")) ? 1 : 0;
            s[F_FROZEN] = field(sheet, "frozen") != null ? 1 : 0;
            s[F_OPEN_LEFT] = f[0] + panel.getLeft(); s[F_OPEN_RIGHT] = f[0] + panel.getRight(); s[F_OPEN_BOTTOM] = f[1] + panel.getBottom();
            return s;
        }
    }

    /** The frames that show the sheet with its travel known (its settle and its drags). */
    private static List<float[]> sheetFrames(List<float[]> frames) {
        List<float[]> out = new ArrayList<>();
        for (float[] frame : frames) if (frame[F_OVERLAY] == 1 && frame[F_TRAVEL] > 0) out.add(frame);
        return out;
    }

    /** An opening's frames: toward open until one overshoot within the cap, then back with no ripple of 1 dp. Returns the overshoot (px). */
    private float opening(List<float[]> all, String what) {
        noGap(all, what);
        List<float[]> frames = sheetFrames(all);
        List<Float> settle = new ArrayList<>();
        for (float[] frame : frames) if (frame[F_STATE] == OPENING || frame[F_STATE] == OPEN && !settle.isEmpty()) settle.add(frame[F_OFFSET]);
        require(settle.size() >= 10, what + ": only " + settle.size() + " frames of its settle");
        int lowest = 0;
        for (int i = 1; i < settle.size(); i++) if (settle.get(i) < settle.get(lowest)) lowest = i;
        for (int i = 1; i <= lowest; i++) require(settle.get(i) <= settle.get(i - 1) + .5f, what + ": moved back toward closed at frame " + i + " (" + settle.get(i - 1) + " -> " + settle.get(i) + " px) before its overshoot");
        float overshoot = -settle.get(lowest);
        require(overshoot <= cap + 1, what + ": went " + overshoot + " px past open, over the cap of " + cap + " px");
        for (int i = lowest + 1; i < settle.size(); i++) {
            require(settle.get(i) <= dp, what + ": a second ripple of " + settle.get(i) + " px after the overshoot");
            require(settle.get(i) >= settle.get(i - 1) - dp, what + ": a second overshoot after the first (" + settle.get(i - 1) + " -> " + settle.get(i) + " px)");
        }
        require(Math.abs(settle.get(settle.size() - 1)) < .5f, what + ": ended at " + settle.get(settle.size() - 1) + " px, not open");
        return overshoot;
    }

    /**
     * A close's frames: toward closed only, with no bounce. Let go while still moving up, it first carries that speed on
     * a little (the hand-off keeps it), and from its first frame toward closed it never turns back.
     */
    private float closing(List<float[]> all, String what) {
        noGap(all, what);
        List<Float> settle = new ArrayList<>();
        for (float[] frame : sheetFrames(all)) if (frame[F_STATE] == CLOSING) settle.add(frame[F_OFFSET]);
        require(settle.size() >= 5, what + ": only " + settle.size() + " frames of its settle");
        int turned = 1;
        while (turned < settle.size() && settle.get(turned) <= settle.get(turned - 1) + .5f) turned++;
        float carried = settle.get(0) - settle.get(Math.max(0, turned - 1));
        require(carried <= 4 * dp, what + ": carried on toward open by " + carried + " px after the finger let go");
        for (int i = turned; i < settle.size(); i++) require(settle.get(i) >= settle.get(i - 1) - .5f, what + ": moved back toward open at frame " + i + " (" + settle.get(i - 1) + " -> " + settle.get(i) + " px)");
        return carried;
    }

    /**
     * From open on (the bounce, the rubber band past open), no frame shows a gap under the sheet: its surface always ends
     * at or below the screen's bottom. (On its way from the dock its bottom is still coming down from the dock's.)
     */
    private void noGap(List<float[]> all, String what) {
        List<float[]> frames = sheetFrames(all);
        require(!frames.isEmpty(), what + ": no frames sampled");
        for (int i = 0; i < frames.size(); i++) {
            float[] frame = frames.get(i);
            if (frame[F_MORPH] == 1 && frame[F_P] < 1) continue;
            require(frame[F_GAP] >= 0, what + ": at frame " + i + " the sheet's surface ends " + -frame[F_GAP] + " px above the screen's bottom (offset " + frame[F_OFFSET] + " px)");
        }
    }

    /** The largest step (px) seen between two frames beyond what the progress allows, and the top's largest slip from the offset, over a run of checks. */
    private float worstStep, worstSlip, worstDockMatch;
    private int framesChecked;

    /**
     * The dock and the sheet as one surface, frame by frame: every frame shows exactly one of the dock and the sheet's
     * surface (never both, never neither; a let-go slide over a new page's dock aside); opens: its first frame showing
     * the sheet is the dock's own bounds (2 px, beyond what its progress moved them: dockMatch), after a frame of the
     * dock alone when one was drawn; closes: its last
     * frame showing the sheet is the dock's bounds and the frame after it shows the dock alone. Between frames the
     * surface's top moves with the sheet's offset (2 px) and its sides and bottom by no more than the progress moved
     * them; the dock's buttons are gone from a quarter of the way and the sheet's content shows only past 35%, never
     * both at once.
     */
    private void oneSurface(List<float[]> frames, String what, boolean opens, boolean closes) {
        require(!frames.isEmpty(), what + ": no frames drawn");
        int first = -1, lastShown = -1;
        for (int i = 0; i < frames.size(); i++) {
            float[] f = frames.get(i);
            if (f[F_FROZEN] == 1) continue;
            boolean dock = f[F_DOCK] == 1, sheet = f[F_OVERLAY] == 1;
            require(dock != sheet, what + ": frame " + i + " of " + frames.size() + " shows " + (dock ? "both the dock and the sheet" : "neither the dock nor the sheet") + frameText(f));
            if (!sheet) continue;
            framesChecked++;
            if (first < 0) first = i;
            lastShown = i;
            if (f[F_MORPH] == 1) {
                require(f[F_P] < SHEET_BUTTONS_GONE || f[F_BUTTONS] == 0, what + ": frame " + i + " still shows the dock's buttons (" + f[F_BUTTONS] + ") at " + pct(f[F_P]));
                require(f[F_P] > SHEET_CONTENT_FROM || f[F_CONTENT] == 0, what + ": frame " + i + " shows the sheet's content (" + f[F_CONTENT] + ") at " + pct(f[F_P]));
                require(!(f[F_BUTTONS] > 0 && f[F_CONTENT] > 0), what + ": frame " + i + " shows the dock's buttons and the sheet's content at once at " + pct(f[F_P]));
                // Drawn over the surface, not under it (a view of lower Z draws under one of higher Z whatever their order).
                require(f[F_CONTENT] == 0 || f[F_CONTENT_Z] >= f[F_SURFACE_Z], what + ": frame " + i + "'s content (Z " + f[F_CONTENT_Z] + ") is under the surface (Z " + f[F_SURFACE_Z] + ") at " + pct(f[F_P]));
            }
            if (i == 0) continue;
            float[] g = frames.get(i - 1);
            if (g[F_OVERLAY] != 1 || g[F_FROZEN] == 1 || f[F_MORPH] != 1 || g[F_MORPH] != 1 || f[F_TRAVEL] <= 0 || g[F_TRAVEL] != f[F_TRAVEL]) continue;
            float travel = f[F_TRAVEL];
            float slip = Math.abs((f[F_TOP] - g[F_TOP]) - (Math.min(f[F_OFFSET], travel) - Math.min(g[F_OFFSET], travel)));
            worstSlip = Math.max(worstSlip, slip);
            require(slip <= 2, what + ": frame " + i + "'s top moved " + (f[F_TOP] - g[F_TOP]) + " px for an offset change of " + (f[F_OFFSET] - g[F_OFFSET]) + " px");
            float moved = Math.abs(clamp(f[F_P]) - clamp(g[F_P]));
            float[][] edges = {{F_LEFT, F_DOCK_LEFT, F_OPEN_LEFT}, {F_RIGHT, F_DOCK_RIGHT, F_OPEN_RIGHT}, {F_BOTTOM, F_DOCK_BOTTOM, F_OPEN_BOTTOM}};
            String[] names = {"left", "right", "bottom"};
            for (int e = 0; e < 3; e++) {
                int at = (int) edges[e][0], dockAt = (int) edges[e][1], openAt = (int) edges[e][2];
                float step = Math.abs(f[at] - g[at]);
                float allowed = EASE_SLOPE * Math.abs(f[dockAt] - f[openAt]) * moved + (at == F_BOTTOM ? Math.abs(Math.min(0, f[F_OFFSET]) - Math.min(0, g[F_OFFSET])) : 0);
                worstStep = Math.max(worstStep, step - allowed);
                require(step <= allowed + 2, what + ": frame " + i + "'s " + names[e] + " edge jumped " + step + " px (the progress moved " + moved + ", allowing " + allowed + " px)" + frameText(f));
            }
        }
        require(first >= 0, what + ": no frame showed the sheet");
        if (opens) {
            float match = dockMatch(frames.get(first));
            worstDockMatch = Math.max(worstDockMatch, match);
            require(match <= 2, what + ": its first frame of the sheet is " + match + " px off the dock's bounds" + frameText(frames.get(first)));
            if (first > 0) require(frames.get(first - 1)[F_DOCK] == 1, what + ": the frame before the sheet's first does not show the dock");
        }
        if (closes) {
            float match = dockMatch(frames.get(lastShown));
            worstDockMatch = Math.max(worstDockMatch, match);
            require(match <= 2, what + ": its last frame of the sheet is " + match + " px off the dock's bounds" + frameText(frames.get(lastShown)));
            require(lastShown + 1 < frames.size() && frames.get(lastShown + 1)[F_DOCK] == 1 && frames.get(lastShown + 1)[F_OVERLAY] == 0,
                    what + ": no frame of the dock alone after the sheet's last");
        }
    }
    private static final float SHEET_BUTTONS_GONE = .25f, SHEET_CONTENT_FROM = .35f;
    private static float clamp(float p) { return Math.max(0, Math.min(1, p)); }
    private static String pct(float p) { return Math.round(p * 100) + "%"; }
    /**
     * How far a frame's surface is off the dock's bounds beyond what its progress allows: its top by the offset it has
     * moved from closed (1:1), its sides and bottom as far as an edge step from closed may take them (EASE_SLOPE). At
     * closed this is the dock's bounds exactly; a pull whose first drawn frame comes a few px after the finger moved (a
     * slow machine draws later) is that far up, no further.
     */
    private static float dockMatch(float[] f) {
        boolean along = f[F_MORPH] == 1 && f[F_TRAVEL] > 0 && !Float.isNaN(f[F_P]);
        float up = along ? f[F_TRAVEL] - Math.min(f[F_OFFSET], f[F_TRAVEL]) : 0, p = along ? clamp(f[F_P]) : 0;
        float top = Math.abs(f[F_TOP] - (f[F_DOCK_TOP] - up));
        float left = Math.abs(f[F_LEFT] - f[F_DOCK_LEFT]) - EASE_SLOPE * Math.abs(f[F_DOCK_LEFT] - f[F_OPEN_LEFT]) * p;
        float right = Math.abs(f[F_RIGHT] - f[F_DOCK_RIGHT]) - EASE_SLOPE * Math.abs(f[F_DOCK_RIGHT] - f[F_OPEN_RIGHT]) * p;
        float bottom = Math.abs(f[F_BOTTOM] - f[F_DOCK_BOTTOM]) - EASE_SLOPE * Math.abs(f[F_DOCK_BOTTOM] - f[F_OPEN_BOTTOM]) * p;
        return Math.max(Math.max(top, Math.max(0, left)), Math.max(Math.max(0, right), Math.max(0, bottom)));
    }
    private static String frameText(float[] f) {
        return String.format(Locale.ROOT, " (state %.0f, offset %.1f of %.0f, %s; surface %.0f,%.0f,%.0f,%.0f; dock %.0f,%.0f,%.0f,%.0f%s)",
                f[F_STATE], f[F_OFFSET], f[F_TRAVEL], Float.isNaN(f[F_P]) ? "-" : pct(f[F_P]), f[F_LEFT], f[F_TOP], f[F_RIGHT], f[F_BOTTOM],
                f[F_DOCK_LEFT], f[F_DOCK_TOP], f[F_DOCK_RIGHT], f[F_DOCK_BOTTOM], f[F_DOCK] == 1 ? ", shown" : "");
    }

    /**
     * The pulled sheet shows exactly as far as the finger has gone up since the dock recognised its swipe: 1:1, within
     * 1.5 px and two of the finger's steps (Android resamples a moving touch to the frame's time, the one that was recognised too).
     */
    private void followsFinger(Finger finger, String what) {
        waitForIdleSync();
        main(() -> {
            Object pull = field(field(dock(), "view"), "pull");
            float from = (Float) field(pull, "fromY"), moved = from - finger.y;
            float shown = (Integer) field(drag(), "travel") - offset();
            require(Math.abs(shown - moved) <= 1.5f + 2 * finger.step, what + " shows " + shown + " px for a finger " + moved + " px up since its swipe was recognised (it should follow 1:1)");
        });
    }

    // ===== Steps =====

    /**
     * The sheet's states, read on the main thread at every frame (a Choreographer callback), so a settle is seen even when
     * a slow machine stalls the main thread: polling from this thread can miss a whole settle that a stall swallowed, but
     * a settle lasts at least one frame. Records each change; a missing sheet is not recorded.
     */
    private final class States {
        private final List<Integer> seen = new ArrayList<>();
        private final Object only;
        private boolean run, gone;
        private Choreographer.FrameCallback tick;
        /** Watches whichever sheet the dock has, or only sheet when it is not null (one that outlives the dock). */
        States(Object sheet) { this(sheet, false); }
        /** here: called on the main thread, it starts watching now (else it asks the main thread to). */
        States(Object sheet, boolean here) {
            only = sheet;
            Runnable begin = () -> {
                run = true;
                tick = t -> {
                    if (!run) return;
                    record();
                    Choreographer.getInstance().postFrameCallback(tick);
                };
                record();
                Choreographer.getInstance().postFrameCallback(tick);
            };
            if (here) begin.run(); else main(begin);
        }
        /** On the main thread: notes the sheet's state now. */
        private void record() {
            Object sheet = only != null ? only : activity == null ? null : drag();
            if (sheet == null) { gone = true; return; }
            Integer state = (Integer) field(sheet, "state");
            gone = only != null && !((View) field(sheet, "frame")).isAttachedToWindow();
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(state)) seen.add(state);
        }
        /** The last state seen (null before any). */
        Integer last() { return onMain(() -> { record(); return seen.isEmpty() ? null : seen.get(seen.size() - 1); }); }
        /** True once the sheet is gone (the dock has none, or the watched sheet's overlay is down). */
        boolean gone() { return onMain(() -> { record(); return gone; }); }
        /** True once any frame (or now) has shown the sheet in state. */
        boolean saw(int state) { return onMain(() -> { record(); return seen.contains(state); }); }
        List<Integer> stop() { return onMain(() -> { run = false; record(); return new ArrayList<>(seen); }); }
    }

    /** Taps the dock's All item and waits for the sheet to be open and still; returns the sheet's states seen meanwhile. */
    private List<Integer> tapAll() {
        HubTestPages.showDock(this, activity);
        States states = new States(null);
        tap(onMain(() -> centreOf(root().findViewWithTag("hub-dock-handle"))));
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            Integer state = states.last();
            if (state != null && state == OPEN) break;
            SystemClock.sleep(10);
        }
        List<Integer> seen = states.stop();
        require(!seen.isEmpty() && seen.get(seen.size() - 1) == OPEN, "All did not open the sheet (states " + seen + ")");
        settle();
        return seen;
    }

    /** Runs action (a close) on the open sheet and waits for it to be gone; returns the states seen meanwhile. */
    private List<Integer> closeBy(Runnable action) {
        States states = new States(null);
        action.run();
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end && !states.gone()) SystemClock.sleep(10);
        List<Integer> seen = states.stop();
        awaitGone("a close");
        return seen;
    }

    /** Runs action (a close) and waits until the sheet is settling closed (slowed, so it is still on its way). */
    private void closeStarts(Runnable action) {
        action.run();
        await(() -> onMain(() -> drag() != null && field(drag(), "state").equals(CLOSING)), "The sheet did not start settling closed");
        // On its way out it gives up Back: a Back pressed meanwhile goes to the page.
        await(() -> onMain(() -> drag() == null || !Boolean.TRUE.equals(field(drag(), "focusable"))), "The sheet kept Back while it settled closed");
        main(() -> require(drag() != null && field(drag(), "state").equals(CLOSING), "The slowed close ended too soon to catch"));
    }

    /** From the dock's centre, a slow pull showing fraction of the sheet, held still, then let go (or left held when hold is false: let go moving slowly). */
    private void pullTo(double fraction, long ms, boolean hold) {
        HubTestPages.showDock(this, activity);
        Finger finger = new Finger(dockCentre());
        finger.moveBy((float) (-fraction * travel), ms, (int) (ms / 16));
        main(() -> require(drag() != null && field(drag(), "state").equals(PULLED), "The pull did not take the sheet"));
        followsFinger(finger, "The pulled sheet");
        if (fraction <= .1 && Build.VERSION.SDK_INT >= 30) main(() -> {
            // A short pull's light dim: the light theme's status icons stay dark (light on the dark theme).
            int status = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
            int look = activity.getWindow().getInsetsController().getSystemBarsAppearance() & status;
            require(look == (activity.ui().dark ? 0 : status), "At a short pull the sheet's status icons are " + (look == 0 ? "light" : "dark") + " on the " + (activity.ui().dark ? "dark" : "light") + " theme");
        });
        if (hold) finger.hold(150);
        finger.up();
    }

    private void awaitOpen(String after) {
        await(() -> onMain(() -> drag() != null && field(drag(), "state").equals(OPEN)), () -> "The sheet is not open after " + after + " (" + describe() + ")");
        settle();
        main(() -> require(sheetView().isAttachedToWindow() && Math.abs(offset()) < .5f, "After " + after + " the open sheet is at " + offset() + " px"));
    }

    private void awaitGone(String after) {
        await(() -> onMain(() -> drag() == null && (field(activity, "dock") == null || field(dock(), "sheet") == null)), () -> "The sheet is still there after " + after + " (" + describe() + ")");
        settle();
    }

    private String describe() {
        return onMain(() -> drag() == null ? "no sheet" : "state " + field(drag(), "state") + ", offset " + offset() + " of " + field(drag(), "travel") + " px");
    }

    /** The open sheet sits inside the window: its top below the status bar, its list's bottom above the screen's. */
    private void fits() {
        main(() -> {
            View panel = panel(), list = list();
            int[] p = new int[2], l = new int[2]; panel.getLocationOnScreen(p); list.getLocationOnScreen(l);
            View frame = (View) field(drag(), "frame"); int[] f = new int[2]; frame.getLocationOnScreen(f);
            require(p[1] >= f[1], "The open sheet's top is above its window");
            android.view.WindowInsets insets = frame.getRootWindowInsets();
            int top = 0, bottom = 0;
            if (insets != null && Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            }
            require(p[1] >= f[1] + top, "The open sheet's top is under the status bar");
            require(l[1] + list.getHeight() <= f[1] + frame.getHeight() - bottom + 1, "The sheet's list ends under the navigation bar");
            if (Build.VERSION.SDK_INT >= 30) {
                // The bars' icons at full open: light on the dark theme; on the light one, dark on the navigation bar (over
                // the sheet's own background) and white on the status bar (the full dim over the page is past the crossover).
                int status = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, nav = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int look = activity.getWindow().getInsetsController().getSystemBarsAppearance() & (status | nav);
                float dim = (Float) field(drag(), "dim");
                boolean white = whiteIcons(activity.ui().bg, dim);
                require(activity.ui().dark || white, "The light theme's full dim (" + dim + ") is not past the status icons' crossover");
                require(look == (activity.ui().dark ? 0 : nav), "The open sheet's bar icons do not suit the " + (activity.ui().dark ? "dark" : "light") + " theme (appearance " + look + ", dim " + dim + ")");
            }
        });
        // On screen: the sheet's own background reaches the screen's bottom edge, under the navigation bar too (a window
        // that draws no bar backgrounds gets a black strip painted there).
        settle();
        android.graphics.Bitmap shot = getUiAutomation().takeScreenshot();
        require(shot != null, "Screenshot unavailable");
        try {
            int bg = onMain(() -> activity.ui().bg) | 0xff000000;
            for (int y : new int[]{shot.getHeight() - 1, shot.getHeight() - Math.round(4 * dp)}) {
                int seen = shot.getPixel(Math.round(shot.getWidth() * .1f), y) | 0xff000000;
                int diff = Math.abs(((seen >> 16) & 255) - ((bg >> 16) & 255)) + Math.abs(((seen >> 8) & 255) - ((bg >> 8) & 255)) + Math.abs((seen & 255) - (bg & 255));
                require(diff <= 12, String.format(Locale.ROOT, "The screen's bottom (row %d) under the open sheet is #%06x, not the sheet's background #%06x", y, seen & 0xffffff, bg & 0xffffff));
            }
        } finally { shot.recycle(); }
    }

    // ===== Touches =====

    /** One finger's touch, injected with explicit event times (real time: what the app sees is a real gesture's pace). */
    private final class Finger {
        private final long down;
        /** The last event's time: the lift follows it as a finger's would, however long the injection itself took. */
        private long last;
        private float x, y;
        /** px: its last move's step (the app resamples touches to its frames, so it may see up to a step off the last one sent). */
        private float step;
        private boolean two;
        Finger(float[] at) {
            x = at[0]; y = at[1];
            down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down);
        }
        /** Moves by dy (px, down positive) over ms in steps moves, one each ms / steps. */
        void moveBy(float dy, long ms, int steps) {
            float from = y;
            step = Math.abs(dy / steps);
            long start = SystemClock.uptimeMillis();
            for (int i = 1; i <= steps; i++) {
                long at = start + ms * i / steps;
                long wait = at - SystemClock.uptimeMillis();
                if (wait > 0) SystemClock.sleep(wait);
                y = from + dy * i / steps;
                send(MotionEvent.ACTION_MOVE, at);
            }
        }
        /** Still for ms, with a move each frame (so its speed at the lift is none). */
        void hold(long ms) {
            long start = SystemClock.uptimeMillis();
            for (long at = start + 16; at <= start + ms; at += 16) {
                long wait = at - SystemClock.uptimeMillis();
                if (wait > 0) SystemClock.sleep(wait);
                send(MotionEvent.ACTION_MOVE, at);
            }
        }
        void up() {
            if (two) send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), last + 8);
            two = false;
            send(MotionEvent.ACTION_UP, last + 8);
        }
        void cancel() { send(MotionEvent.ACTION_CANCEL, last + 8); }
        /** A second finger down, 40 dp beside the first. */
        void secondFinger() {
            two = true;
            send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), Math.max(last + 8, SystemClock.uptimeMillis()));
        }
        private void send(int action, long time) {
            last = time;
            int count = two ? 2 : 1;
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords(); coords[i].x = x + i * 40 * dp; coords[i].y = y; coords[i].pressure = 1; coords[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(down, time, action, count, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { sendPointerSync(event); } finally { event.recycle(); }
        }
    }

    private void tap(float x, float y) { Finger finger = new Finger(new float[]{x, y}); finger.up(); waitForIdleSync(); }
    /**
     * A tap with no wait for the app to go idle after it, for a tap whose animation is watched as it runs (on a slow
     * emulator the main thread can be busy drawing every frame of it, and waitForIdleSync returns only after it ends).
     */
    private void press(float[] at) { new Finger(at).up(); }
    private void tap(float[] at) { tap(at[0], at[1]); }
    private void tapCentre(View view, String name) {
        float[] at = onMain(() -> { require(shown(view), name + " is not shown"); return centreOf(view); });
        tap(at);
    }
    private float[] dockCentre() { return onMain(() -> centreOf((View) invoke(dock(), "view"))); }
    private float[] centre(View view) { return onMain(() -> centreOf(view)); }
    /** A point on the sheet's list below its header: the middle of the list's visible part. */
    private float[] listPoint() {
        return onMain(() -> {
            View list = list(); int[] at = new int[2]; list.getLocationOnScreen(at);
            View head = ((View[]) field(drag(), "header"))[1]; int[] h = new int[2]; head.getLocationOnScreen(h);
            float top = Math.max(at[1], h[1] + head.getHeight());
            return new float[]{at[0] + list.getWidth() / 2f, (top + at[1] + list.getHeight()) / 2f};
        });
    }
    private static float[] centreOf(View view) {
        int[] at = new int[2]; view.getLocationOnScreen(at);
        return new float[]{at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f};
    }
    private void back() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(); }

    // ===== The sheet's parts =====

    private Object dock() { Object dock = field(activity, "dock"); require(dock != null, "Widgets Home has no dock"); return dock; }
    /** The dock's sheet; null with none, or with no dock (a page a row opened). */
    private Object drag() { Object dock = field(activity, "dock"); return dock == null ? null : field(dock, "drag"); }
    /** The dock's sheet's overlay (in Volt's window while it shows); null with no sheet. */
    private View sheetView() { Object drag = drag(); return drag == null ? null : (View) field(drag, "frame"); }
    private View panel() { return (View) field(drag(), "panel"); }
    private ScrollView list() { return (ScrollView) field(drag(), "list"); }
    private float offset() { return (Float) field(drag(), "offset"); }
    private View handle() { return onMain(() -> ((View[]) field(drag(), "header"))[0]); }
    /** The close button: the clickable view with a spoken name in the sheet's title row (ScreenHost.sheetContent). */
    private View closeButton() {
        return onMain(() -> {
            View head = ((View[]) field(drag(), "header"))[1];
            List<View> all = new ArrayList<>(); all.add(head);
            for (int i = 0; i < all.size(); i++) if (all.get(i) instanceof ViewGroup) for (int c = 0; c < ((ViewGroup) all.get(i)).getChildCount(); c++) all.add(((ViewGroup) all.get(i)).getChildAt(c));
            for (View view : all) if (view.isClickable() && view.getContentDescription() != null && view.getContentDescription().length() > 0) return view;
            throw new IllegalStateException("The sheet's title row has no close button");
        });
    }

    private int state(String name) {
        try { Field f = Class.forName("com.jjricks.volt.DragSheet").getDeclaredField(name); f.setAccessible(true); return f.getInt(null); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static boolean whiteIcons(int background, double alpha) {
        try { java.lang.reflect.Method m = Class.forName("com.jjricks.volt.SheetSpring").getDeclaredMethod("whiteIcons", int.class, double.class); m.setAccessible(true); return (Boolean) m.invoke(null, background, alpha); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static double spring(String name) {
        try { Field f = Class.forName("com.jjricks.volt.SheetSpring").getDeclaredField(name); f.setAccessible(true); return ((Number) f.get(null)).doubleValue(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** Android's animator duration scale, set and seen in this process. */
    private void scale(float value) {
        try { shell("settings put global animator_duration_scale " + (value == (int) value ? String.valueOf((int) value) : String.valueOf(value))); }
        catch (Exception error) { throw new IllegalStateException(error); }
        await(() -> ValueAnimator.getDurationScale() == value, "The animator duration scale did not reach the app (" + ValueAnimator.getDurationScale() + ", wanted " + value + ")");
        settle();
    }

    // ===== Fixture =====

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence", "onboarding"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, FIXTURE_IDENTITY, 0);
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null, "App did not launch");
        settle();
    }
    private void simulate() {
        main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
        await(() -> onMain(() -> activity.service().ble.ready()), "The simulated scooter did not connect");
        main(() -> activity.redraw()); settle();
    }
    private void requireHome() {
        await(() -> onMain(() -> HubPages.HOME.equals(field(activity, "selectedPage")) && field(activity, "screen") != null
                && field(activity, "screen").getClass().getSimpleName().equals("DashboardScreen") && field(activity, "dock") != null), "Widgets Home with its dock is not shown");
        settle();
    }

    // ===== Plumbing =====

    private View root() { return activity.getWindow().getDecorView(); }
    private float dp() { return activity.getResources().getDisplayMetrics().density; }
    private static boolean shown(View view) { return view != null && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0; }
    private static String round(float value) { return String.format(Locale.ROOT, "%.1f", value); }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = reader.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private <T> T onMain(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(task.get()));
        return value.get();
    }
    private void await(BooleanSupplier condition, String message) { await(condition, () -> message); }
    private void await(BooleanSupplier condition, Supplier<String> message) {
        long end = SystemClock.uptimeMillis() + 15000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
        boolean met = condition.getAsBoolean();
        require(met, met ? "" : message.get());
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Class<?> type = target.getClass(); while (type != null) { try { java.lang.reflect.Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException next) { type = type.getSuperclass(); } }
            throw new NoSuchMethodException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
}
