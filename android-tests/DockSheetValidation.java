package com.jjricks.volt.tests;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
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
 * dock and its All sheet as a finger moves them (DragSheet and SheetSpring, the owner's request of 15 Nov), on the
 * developer simulator, with real touches injected into the windows (Instrumentation.sendPointerSync, explicit event
 * times):
 *
 * <ul>
 *   <li><b>Pulls from the dock</b>: a slow pull to about a tenth of the sheet's travel, held and let go, springs back
 *   closed and dismisses it; a slow pull to about two fifths opens it; a short fast flick opens it (lifted before the
 *   sheet's first layout). The sheet's dialog shows mid-gesture and never takes the touch from the dock.</li>
 *   <li><b>The open sheet</b>: dragged by its grab handle a tenth of the way down it springs back open, two fifths down
 *   it closes, and a fast downward flick closes it. With its list scrolled down, a downward drag on the list scrolls the
 *   list and leaves the sheet open; dragged on past the list's top, the sheet takes over and closes.</li>
 *   <li><b>Taps</b>: a dock item opens its subject (Back returns), All opens the sheet with the spring, and its close
 *   button, Back, a row and a tap on the dim close it.</li>
 *   <li><b>Interruptions</b>: ACTION_CANCEL and a second finger settle a pull where it is; a pull taken back to its start
 *   ends closed; a new pull and a tap on All catch a sheet that is settling closed and bring it back; the activity
 *   recreated mid-pull (as a rotation, fold, resize or text-size change rebuilds Home) leaves no sheet and no crash.</li>
 *   <li><b>Every frame</b>, at animator duration scale 5: the opening moves only toward open until one overshoot no
 *   larger than SheetSpring.CAP_DP, then back (any second ripple under 1 dp); a snap-back and a close move only toward
 *   closed; and at no frame of any pull, drag or settle does the sheet's background end above the screen's bottom.</li>
 *   <li><b>Animations off</b> (animator duration scale 0): a pull still follows the finger, and every release, tap and
 *   close jumps to its end with no settle.</li>
 * </ul>
 *
 * The animator duration scale is put back as it was at the end. -e theme dark|light and -e text_scale (1 by default) as
 * the other fixture runners; -e list_scrolls true|false requires that the window's sheet is (or is not) taller than the
 * window, so its list scrolls. -e mode hold sets Widgets Home up and holds it for -e hold_seconds (600 by default), for
 * recordings driven from adb; it checks nothing.
 */
public final class DockSheetValidation extends Instrumentation {
    private static final String FIXTURE_NAME = "Synthetic dock fixture", FIXTURE_IDENTITY = "B1C2D3E4F5A6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:57";
    private static final String STYLE_KEY = "home_style", STYLE_CHOSEN = "home_style_chosen", STYLE_ORIGIN = "home_style_origin", WIDGETS = "widgets";
    /** DragSheet's states (package-private in the app, read once by reflection). */
    private int OPENING, OPEN, CLOSING, PULLED;
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
            require(mode.equals("all") || mode.equals("hold"), "Unknown -e mode " + mode + " (all or hold)");
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
            if (mode.equals("hold")) {
                checks.add("Widgets Home held for recordings");
                SystemClock.sleep(Long.parseLong(arguments.getString("hold_seconds", "600")) * 1000);
            } else {
                OPENING = state("OPENING"); OPEN = state("OPEN"); CLOSING = state("CLOSING"); PULLED = state("PULLED");
                cap = (float) (spring("CAP_DP") * dp());
                dp = dp();
                scaleBefore = shell("settings get global animator_duration_scale").trim();
                try {
                    scale(1);
                    taps();
                    pulls();
                    drags();
                    scrolledList();
                    interruptions();
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
        checks.add("The open sheet sits below the status bar with its list above the navigation bar, and its background reaches the screen's bottom edge on screen");
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
        View row = onMain(() -> dialog().getWindow().getDecorView().findViewWithTag("home-subject-" + subject));
        require(row != null, "The All sheet has no row for " + subject);
        main(() -> row.requestRectangleOnScreen(new Rect(0, 0, row.getWidth(), row.getHeight()), true));
        settle();
        // The dock goes with Home as the subject opens; the sheet it opened settles closed over the subject all the same.
        Object sheet = onMain(this::drag);
        Dialog window = onMain(this::dialog);
        States states = new States(sheet);
        tapCentre(row, "the All sheet's " + subject + " row");
        long end = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < end && !states.gone()) SystemClock.sleep(10);
        seen = states.stop();
        require(!onMain(window::isShowing), "The All sheet stayed open after its " + subject + " row");
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
        main(() -> require(offset() >= travel - .5f, "Pulled back to its start, the sheet still shows " + (travel - offset()) + " px"));
        finger.hold(120);
        finger.up();
        awaitGone("a pull taken back to its start");
        // A sheet settling closed (slowed, so it is caught on its way) is caught by a new pull and by a tap on All. Scale 10
        // (a close of about 3 s): on a loaded machine the page's focus coming back and the pull's first moves take a while.
        scale(10);
        tapAll();
        closeStarts(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
        Object before = onMain(this::drag);
        finger = new Finger(dockCentre());
        finger.moveBy(-.45f * travel, 600, 30);
        boolean same = onMain(() -> drag() == before), caught = onMain(() -> drag() != null && field(drag(), "state").equals(PULLED));
        require(same && caught, "A pull while the sheet settled closed did not catch it (" + (same ? "" : "a new sheet, ") + describe() + ")");
        finger.hold(150);
        finger.up();
        awaitOpen("a pull that caught the closing sheet");
        closeStarts(() -> sendKeyDownUpSync(KeyEvent.KEYCODE_BACK));
        Object closing = onMain(this::drag);
        tap(onMain(() -> centreOf(root().findViewWithTag("hub-dock-handle"))));
        awaitOpen("All tapped while the sheet settled closed");
        require(onMain(() -> drag() == closing), "All tapped while the sheet settled closed made a second sheet (" + describe() + ")");
        scale(1);
        back(); awaitGone("Back");
        checks.add("ACTION_CANCEL settles a pull where it is (a tenth closes, two fifths opens); a second finger settles it at once and the rest of the touch moves nothing; "
                + "a pull taken back to its start closes; the page has focus while the sheet settles closed, and a new pull and a tap on All catch it and bring the same sheet back");
    }

    /** Every frame at animator scale 5: the opening, the snap-back and the close, and no gap under the sheet at any frame. */
    private void frames() {
        scale(5);
        Sampler sampler = new Sampler();
        // The opening from closed (All).
        sampler.start();
        tapAll();
        List<float[]> open = sampler.stop();
        float overshoot = opening(open, "All's opening");
        require(overshoot >= 2 * dp, "All's opening shows no bounce (" + overshoot / dp + " dp past open)");
        // The close (Back): toward closed only.
        sampler.start();
        back(); awaitGone("Back");
        closing(sampler.stop(), "Back's close");
        // A pull to two fifths: the finger's frames and the opening.
        sampler.start();
        pullTo(.40, 900, true);
        awaitOpen("a slowed pull to two fifths");
        float pulled = opening(sampler.stop(), "a pull's opening");
        // A drag of the open sheet a tenth down, let go: back to open.
        sampler.start();
        Finger finger = new Finger(centre(handle()));
        finger.moveBy(.10f * travel, 600, 30);
        finger.hold(150);
        finger.up();
        awaitOpen("a slowed drag a tenth down");
        noGap(sampler.stop(), "a drag a tenth down and its return");
        back(); awaitGone("Back");
        // A pull to a tenth, held and let go: the snap-back, toward closed only.
        sampler.start();
        pullTo(.10, 700, true);
        awaitGone("a slowed pull to a tenth");
        float still = closing(sampler.stop(), "a held pull's snap-back");
        require(still <= .5f, "a held pull's snap-back moved toward open first (" + still + " px)");
        // The same let go while the finger still moves up: it carries on a little, then goes, with no bounce.
        sampler.start();
        pullTo(.10, 700, false);
        awaitGone("a slowed pull to a tenth let go moving");
        float carried = closing(sampler.stop(), "a moving pull's snap-back");
        scale(1);
        checks.add("At animator scale 5, every frame: All's opening and a pull's opening move only toward open until one overshoot (" + round(overshoot / dp) + " dp and "
                + round(pulled / dp) + " dp, cap " + spring("CAP_DP") + " dp) and back with no second ripple of 1 dp; Back's close and a held pull's snap-back move only toward closed (let go still moving up, it carries on " + round(carried / dp) + " dp, then closes with no bounce); "
                + "the sheet's background never ends above the screen's bottom at any frame of a pull, a drag or a settle");
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
        float shown = onMain(() -> travel - offset());
        require(Math.abs(shown - .40f * travel) < 6 * dp, "With animations off, the pulled sheet shows " + shown + " px for a finger " + .40f * travel + " px up");
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
        Dialog stray = onMain(this::dialog);
        require(stray != null && stray.isShowing(), "The pull showed no sheet");
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
        await(() -> onMain(() -> !stray.isShowing()), "The sheet outlived the activity that pulled it");
        main(() -> require(field(dock(), "sheet") == null, "The new dock has a sheet"));
        tapAll();
        back(); awaitGone("Back");
        checks.add("The activity recreated mid-pull: the sheet goes with it, the new Widgets Home's dock has none, and All opens a new one");
    }

    // ===== Frames =====

    /** Every drawn frame's sheet: [offset px, the background's bottom less the screen's px, state]. */
    private final class Sampler {
        private final List<float[]> samples = new ArrayList<>();
        private boolean run;
        private Choreographer.FrameCallback tick;
        void start() {
            samples.clear();
            main(() -> {
                run = true;
                tick = t -> {
                    if (!run) return;
                    Object sheet = activity == null ? null : field(activity, "dock") == null ? null : field(field(activity, "dock"), "drag");
                    if (sheet != null && ((Dialog) field(sheet, "dialog")).isShowing() && (Integer) field(sheet, "travel") > 0) {
                        View panel = (View) field(sheet, "panel"), frame = (View) field(sheet, "frame");
                        int[] p = new int[2], f = new int[2];
                        panel.getLocationOnScreen(p); frame.getLocationOnScreen(f);
                        float bottom = p[1] + panel.getHeight(), screen = f[1] + frame.getHeight();
                        samples.add(new float[]{panel.getTranslationY(), bottom - screen, (Integer) field(sheet, "state")});
                    }
                    Choreographer.getInstance().postFrameCallback(tick);
                };
                Choreographer.getInstance().postFrameCallback(tick);
            });
        }
        List<float[]> stop() {
            main(() -> run = false);
            return new ArrayList<>(samples);
        }
    }

    /** An opening's frames: toward open until one overshoot within the cap, then back with no ripple of 1 dp. Returns the overshoot (px). */
    private float opening(List<float[]> frames, String what) {
        noGap(frames, what);
        List<Float> settle = new ArrayList<>();
        for (float[] frame : frames) if (frame[2] == OPENING || frame[2] == OPEN && !settle.isEmpty()) settle.add(frame[0]);
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
    private float closing(List<float[]> frames, String what) {
        noGap(frames, what);
        List<Float> settle = new ArrayList<>();
        for (float[] frame : frames) if (frame[2] == CLOSING) settle.add(frame[0]);
        require(settle.size() >= 5, what + ": only " + settle.size() + " frames of its settle");
        int turned = 1;
        while (turned < settle.size() && settle.get(turned) <= settle.get(turned - 1) + .5f) turned++;
        float carried = settle.get(0) - settle.get(Math.max(0, turned - 1));
        require(carried <= 4 * dp, what + ": carried on toward open by " + carried + " px after the finger let go");
        for (int i = turned; i < settle.size(); i++) require(settle.get(i) >= settle.get(i - 1) - .5f, what + ": moved back toward open at frame " + i + " (" + settle.get(i - 1) + " -> " + settle.get(i) + " px)");
        return carried;
    }

    /** No frame shows a gap under the sheet: its background always ends at or below the screen's bottom. */
    private void noGap(List<float[]> frames, String what) {
        require(!frames.isEmpty(), what + ": no frames sampled");
        for (int i = 0; i < frames.size(); i++)
            require(frames.get(i)[1] >= 0, what + ": at frame " + i + " the sheet's background ends " + -frames.get(i)[1] + " px above the screen's bottom (offset " + frames.get(i)[0] + " px)");
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
        States(Object sheet) {
            only = sheet;
            main(() -> {
                run = true;
                tick = t -> {
                    if (!run) return;
                    record();
                    Choreographer.getInstance().postFrameCallback(tick);
                };
                record();
                Choreographer.getInstance().postFrameCallback(tick);
            });
        }
        /** On the main thread: notes the sheet's state now. */
        private void record() {
            Object sheet = only != null ? only : activity == null ? null : drag();
            if (sheet == null) { gone = true; return; }
            Integer state = (Integer) field(sheet, "state");
            gone = only != null && !((Dialog) field(sheet, "dialog")).isShowing();
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(state)) seen.add(state);
        }
        /** The last state seen (null before any). */
        Integer last() { return onMain(() -> { record(); return seen.isEmpty() ? null : seen.get(seen.size() - 1); }); }
        /** True once the sheet is gone (the dock has none, or the watched sheet's window is down). */
        boolean gone() { return onMain(() -> { record(); return gone; }); }
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
        // On its way out it gives up focus: a Back pressed meanwhile goes to the page.
        await(() -> onMain(() -> activity.hasWindowFocus()), "The page did not get focus back while the sheet settled closed");
        main(() -> require(drag() != null && field(drag(), "state").equals(CLOSING), "The slowed close ended too soon to catch"));
    }

    /** From the dock's centre, a slow pull showing fraction of the sheet, held still, then let go (or left held when hold is false: let go moving slowly). */
    private void pullTo(double fraction, long ms, boolean hold) {
        HubTestPages.showDock(this, activity);
        Finger finger = new Finger(dockCentre());
        finger.moveBy((float) (-fraction * travel), ms, (int) (ms / 16));
        main(() -> require(drag() != null && field(drag(), "state").equals(PULLED), "The pull did not take the sheet"));
        float shown = onMain(() -> travel - offset());
        require(Math.abs(shown - fraction * travel) < 6 * dp, "The pulled sheet shows " + shown + " px for a finger " + fraction * travel + " px up (it should follow 1:1)");
        if (hold) finger.hold(150);
        finger.up();
    }

    private void awaitOpen(String after) {
        await(() -> onMain(() -> drag() != null && field(drag(), "state").equals(OPEN)), () -> "The sheet is not open after " + after + " (" + describe() + ")");
        settle();
        main(() -> require(dialog().isShowing() && Math.abs(offset()) < .5f, "After " + after + " the open sheet is at " + offset() + " px"));
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
        private boolean two;
        Finger(float[] at) {
            x = at[0]; y = at[1];
            down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, down);
        }
        /** Moves by dy (px, down positive) over ms in steps moves, one each ms / steps. */
        void moveBy(float dy, long ms, int steps) {
            float from = y;
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
    private Dialog dialog() { Object drag = drag(); return drag == null ? null : (Dialog) field(drag, "dialog"); }
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
