package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.function.Consumer;

/**
 * A bottom sheet that follows a finger and springs (the owner's request on the Widgets Home dock, Nov 15). It looks like
 * MainActivity.showSheet's: the same body in a scroller, Volt's background with 24 dp top corners, over a dim. What
 * differs is how it moves:
 *
 * <ul>
 *   <li>Pulled up from a view under it (the dock, through {@link Pull}), it shows at once below the screen's edge and its
 *   top follows the finger 1:1 from where the finger went down; pulling back down lowers it again.</li>
 *   <li>On the open sheet, a drag on its header (the grab handle and title row) moves it; on the list below, a downward
 *   drag moves it only once the list is at its top (the list scrolls first and hands over), and a list too short to
 *   scroll moves it either way. Past open it follows a third of the finger, up to SheetSpring.CAP_DP.</li>
 *   <li>Let go, it opens or closes as SheetSpring.opens decides (a quarter of the way, or a fling) and settles on a
 *   SheetSpring.Plan: open with one small bounce, closed with none, keeping the finger's speed. Taps on the dock's All
 *   item, the close button, Back and a tap on the dim use the same settles, from rest.</li>
 *   <li>The dim follows how much of the sheet shows.</li>
 *   <li>With Android's animations off it still follows the finger, but every settle jumps to its end.</li>
 * </ul>
 *
 * The sheet is a window of its own (a Dialog, so TalkBack, Back and focus behave as for every other sheet) that fills the
 * screen, edge to edge from Android 11: its own scrim, and the sheet at its bottom. The sheet's background reaches
 * CAP_DP below the screen's edge, so the bounce and the rubber band never show a gap under it. Its window animations
 * are off: this class moves it. While it settles closed it still takes every touch (as Material's BottomSheetDialog
 * does while it hides), so nothing under it is touched by accident, not even a lock control on the page: a finger down
 * on the sheet catches it and drags it from where it is (it then carries on closing unless dragged back up a quarter of
 * its travel, SheetSpring.caughtOpens, so a plain tap lets it go), and a touch on the dim is swallowed. It gives up focus
 * meanwhile, so a Back pressed then goes to the page. On the light theme the status bar's icons turn white once the dim
 * behind them passes the contrast crossover (SheetSpring.whiteIcons), and dark again below it.
 *
 * <p><b>Deferred work and its token.</b> Nothing done later acts on a sheet that has moved on since. {@code epoch} goes up
 * with every change of what the sheet is doing: stop() (so every settle, catch, pull, take, finish and dismiss), the end
 * of a settle, and leave(). A posted settle (the first layout's, and the restart for a new height mid-settle) acts only
 * while the epoch it was posted in is current, the restart only while the settle it restarts still runs; a running
 * settle's animator acts only while it is still {@code settling} (its identity); the dock's pull (Pull) and the sheet's
 * own drag (Frame) act only in the epoch they began in, so anything else that moves the sheet ends the rest of that
 * touch; a waiting release or open (waiting) is dropped by stop(). And a let-go sheet never moves toward open again:
 * settle(true) refuses it.
 *
 * <p><b>States and what each ignores</b> (anything not listed acts as its method says):
 * <ul>
 *   <li>NEW (not shown): open and pull show it; close and leave make it GONE with nothing shown; follow, release and
 *   measured cannot reach it (no window yet).</li>
 *   <li>PULLED (a dock finger pulls it, or a tap waits for its first layout): follow and release from that pull only
 *   (its epoch); a finger down on the sheet's window settles the pull where it is and the rest of that touch does
 *   nothing; open, close (Back) settle it, and the pull's later moves are ignored; leave goes at once.</li>
 *   <li>DRAGGED (its own finger holds it): open and pull are ignored; close (Back, the close button) settles it closed
 *   and the rest of that touch does nothing; leave goes at once; measured only places it.</li>
 *   <li>OPENING: open is ignored; close settles closed from where it is; a finger may take it (DRAGGED); measured
 *   restarts the opening for the new height (posted, its token); leave goes at once.</li>
 *   <li>OPEN: open is ignored; close settles closed; leave goes at once; measured only places it.</li>
 *   <li>CLOSING: close is ignored; open springs back open; a finger on the sheet catches it (DRAGGED, focused again), on
 *   the dim is swallowed; a touch that began on its views while it was open does nothing more; Back goes to the page
 *   (not focusable); measured restarts the close for the new height; leave lets it go (below), or goes at once if
 *   nothing settles it or its activity is ending.</li>
 *   <li>CLOSING, let go: open, close, pull and leave are ignored (leave goes at once if the activity is ending), every
 *   touch is swallowed, settle(true) refuses; it only finishes its settle (a restart for a new height is still a close)
 *   and goes, or goes at once with its activity.</li>
 *   <li>GONE: everything is ignored; its window, until removed, swallows touches. The dialog's dismiss listener
 *   (posted by Dialog) only confirms GONE: a GONE sheet is never shown again.</li>
 * </ul>
 * Inset changes only set margins and padding (a layout, so measured). The activity ending: leave() from its owner (Home
 * closing), and each settle frame dismisses at once if its activity is finishing or destroyed.
 *
 * Another sheet opts in by building its body with ScreenHost.sheetContent as before and calling
 * host.dragSheet(body, DragSheet.header(body)).open() where it called host.showSheet(body); its close button then calls
 * close() rather than dismiss().
 */
final class DragSheet {
    /** View tags: the sheet itself (what moves), and its dim. */
    static final String TAG_PANEL = "drag-sheet-panel", TAG_SCRIM = "drag-sheet-scrim";
    /** The states: not shown yet; pulled by a finger outside it; dragged by its own; settling open; open; settling closed; gone. */
    static final int NEW = 0, PULLED = 1, DRAGGED = 2, OPENING = 3, OPEN = 4, CLOSING = 5, GONE = 6;

    private final Activity activity;
    private final Ui ui;
    private final Dialog dialog;
    private final Frame frame;
    private final View scrim;
    private final LinearLayout panel;
    private final ScrollView list;
    private final View[] header;
    private final Consumer<Dialog> shown;
    /** The dim at fully open: the dialog theme's own (what showSheet's sheets get). */
    private final float dim;
    /** px: the most past open, how far the background reaches below the edge, a fling, Android's minimum fling, the touch slop. */
    private final int cap, extension, fling, minFling, slop;
    private final float tolerance;
    private int state = NEW;
    /** The sheet's travel (its height as shown, px; 0 until it is laid out) and where it is now (px below open). */
    private int travel;
    private float offset;
    /** Pulled: how much of the sheet the finger shows (px; base is where it was when the pull caught it). */
    private float pulled, pullBase;
    /**
     * What to do once the first layout gives the travel: nothing, decide a release that came before it (a very quick
     * flick, with its velocity and time), or open from closed (a tap).
     */
    private static final int WAIT_NONE = 0, WAIT_RELEASE = 1, WAIT_OPEN = 2;
    private int waiting = WAIT_NONE;
    private float waitVelocity;
    private long waitMs;
    /** The running settle and its plan; null at rest. */
    private ValueAnimator settling;
    private SheetSpring.Plan plan;
    private boolean focusable = true;
    /**
     * A finger caught the sheet while it settled (SheetSpring.caughtOpens decides its release): where it was then (px),
     * and whether it was settling closed. NaN when the drag or pull did not catch a settle.
     */
    private float caughtAt = Float.NaN;
    private boolean caughtClosing;
    /**
     * What opened it let go of it while it settled closed (leave()): it only finishes that settle and goes. No finger
     * catches it (its window still takes every touch, which does nothing), and open(), close() and pull() do nothing.
     */
    private boolean letGo;
    /** The token of every deferred action (the class comment): up by one with each change of what the sheet is doing. */
    private int epoch;
    /** Light theme: the status bar's icons are white now (the dim behind them passed the crossover). */
    private boolean whiteIcons;
    private Runnable onDismiss;

    /**
     * body: the sheet's body (ScreenHost.sheetContent). header: views inside it that move the sheet whenever they are
     * dragged (header(body)). shown: told when the dialog shows (MainActivity keeps it for dialogShowing), may be null.
     * Not shown until open() or pull().
     */
    DragSheet(Activity activity, Ui ui, View body, View[] header, Consumer<Dialog> shown) {
        this.activity = activity;
        this.ui = ui;
        this.header = header == null ? new View[0] : header;
        this.shown = shown;
        Context themed = new ContextThemeWrapper(activity, ui.dark ? R.style.VoltDialog : R.style.VoltDialogLight);
        float density = activity.getResources().getDisplayMetrics().density;
        cap = Math.round(SheetSpring.CAP_DP * density);
        extension = cap + Math.round(6 * density);
        tolerance = SheetSpring.REST_DP * density;
        ViewConfiguration config = ViewConfiguration.get(activity);
        minFling = config.getScaledMinimumFlingVelocity();
        fling = Math.max(minFling, Math.round(SheetSpring.FLING_DP * density));
        slop = config.getScaledTouchSlop();
        TypedArray values = themed.obtainStyledAttributes(new int[]{android.R.attr.backgroundDimAmount});
        dim = Math.max(0, Math.min(1, values.getFloat(0, .6f)));
        values.recycle();

        frame = new Frame(activity);
        scrim = new View(activity);
        scrim.setTag(TAG_SCRIM);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(TAG_PANEL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(ui.bg);
        float r = ui.dp(24);
        background.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        background.setStroke(ui.dp(1), ui.raised);
        panel.setBackground(background);
        // The background goes on below the screen's edge by more than the bounce can lift it (the negative margin below).
        panel.setPadding(0, 0, 0, extension);
        // Off screen until it is laid out and placed (place()), so its first frame never flashes it.
        panel.setTranslationY(100000);
        list = new ScrollView(activity);
        list.addView(body, new ScrollView.LayoutParams(-1, -2));
        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        at.bottomMargin = -extension;
        frame.addView(panel, at);
        panel.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, or, ob) -> measured());
        frame.setOnApplyWindowInsetsListener((v, insets) -> { inset(insets); return insets; });

        dialog = new Sheet(themed);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(frame, new ViewGroup.LayoutParams(-1, -1));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // This class moves the sheet; a window animation on top of that would move it twice.
            window.setWindowAnimations(0);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().setPadding(0, 0, 0, 0);
            if (Build.VERSION.SDK_INT >= 30) {
                // Edge to edge, so the dim covers the status and navigation bars as a dialog's dim does; the sheet keeps
                // its body clear of them (inset()).
                window.setDecorFitsSystemWindows(false);
                WindowManager.LayoutParams params = window.getAttributes();
                params.setFitInsetsTypes(0);
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                window.setAttributes(params);
                // Bar backgrounds of its own, both clear (a window that draws none gets a black strip painted under the
                // navigation bar): the dim shows under the status bar and the sheet's own background under the
                // navigation bar, whose icons suit that background.
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setNavigationBarColor(Color.TRANSPARENT);
                window.setStatusBarContrastEnforced(false);
                window.setNavigationBarContrastEnforced(false);
                android.view.WindowInsetsController bars = window.getInsetsController();
                // Light theme: dark icons on both bars at first, as on the page under it; the status bar's turn white
                // once the dim behind them passes the contrast crossover (statusIcons()).
                if (bars != null) bars.setSystemBarsAppearance(ui.dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
        dialog.setOnDismissListener(d -> {
            stop();
            state = GONE;
            if (onDismiss != null) onDismiss.run();
        });
        OwnDialogs.track(dialog);
        dialog.create();
        Motion.quietScrollbars(frame);
    }

    /** The header of a ScreenHost.sheetContent body: its grab handle and its title row (with the close button), its first two views. */
    static View[] header(ViewGroup body) {
        int count = Math.min(2, body.getChildCount());
        View[] views = new View[count];
        for (int i = 0; i < count; i++) views[i] = body.getChildAt(i);
        return views;
    }

    Dialog dialog() { return dialog; }

    /** Runs once the dialog is gone, however it went. */
    void onDismiss(Runnable action) { onDismiss = action; }

    /** Shown, and not on its way out for good. */
    boolean showing() { return state != NEW && state != GONE && dialog.isShowing(); }

    /** Opens it from closed with the opening spring (a tap); one already settling closed springs back open from where it is. */
    void open() {
        if (state == GONE || letGo) return;
        if (state == NEW) {
            if (!show()) return;
            // Closed (nothing pulled) until the first layout gives its travel; then it springs open from there.
            state = PULLED; pulled = pullBase = 0; waiting = WAIT_OPEN;
            return;
        }
        if (state == OPEN || state == OPENING || state == DRAGGED) return;
        if (travel == 0) { waiting = WAIT_OPEN; return; }
        settle(true, state == CLOSING ? velocityNow() : 0);
    }

    /** Closes it with the closing settle, then dismisses it (the close button, Back, a row, a tap on the dim). */
    void close() {
        if (letGo || state == GONE || state == CLOSING) return;
        if (state == NEW) { state = GONE; return; }
        if (travel == 0) { dismissNow(); return; }
        settle(false, state == PULLED || state == DRAGGED ? 0 : velocityNow());
    }

    /**
     * What opened it goes (Home rebuilt for a page a row opened, a rotation, the activity ending): a sheet already settling
     * closed (that row's own close) finishes its settle over the new page, let go (letGo: nothing can catch or reopen
     * it, as nothing owns its rows now); any other goes at once (one a finger holds too), and so does every sheet of an
     * activity that is ending.
     */
    void leave() {
        if (state == CLOSING && settling != null && !activity.isFinishing() && !activity.isDestroyed()) {
            // Its running close carries on (the animator is its own token); anything else posted for it is dropped.
            letGo = true;
            epoch++;
            return;
        }
        dismissNow();
    }

    /** Gone at once, with no animation. */
    void dismissNow() {
        stop();
        int was = state;
        state = GONE;
        if (was != NEW && dialog.isShowing()) {
            try { dialog.dismiss(); } catch (RuntimeException alreadyGone) { /* its window went with the activity */ }
        }
    }

    // ---- A pull from outside (the dock) --------------------------------------------------------------------------------

    /**
     * A finger outside the sheet starts pulling it up: shown now (off screen) if it is new, or caught where it is if it
     * was settling. Returns the pull's token for follow and release, or -1 when it cannot be pulled (gone, let go, held
     * by its own finger, or the activity is going away).
     */
    int pull() {
        if (state == GONE || letGo || state == DRAGGED) return -1;
        if (state == NEW) {
            if (!show()) return -1;
            pullBase = 0;
            epoch++;
        } else {
            caught();
            stop();
            pullBase = travel > 0 ? Math.max(0, travel - offset) : 0;
        }
        waiting = WAIT_NONE;
        pulled = pullBase;
        state = PULLED;
        place();
        return epoch;
    }

    /** The pull (its token) has gone up by distance (px) since its finger went down. */
    void follow(int pull, float distance) {
        if (pull != epoch || state != PULLED) return;
        pulled = pullBase + distance;
        place();
    }

    /**
     * The pull (its token) let go: velocity px/s (downward positive), gestureMs from its down. A settle by position alone
     * (ACTION_CANCEL, a second finger) passes 0 and -1.
     */
    void release(int pull, float velocity, long gestureMs) {
        if (pull != epoch || state != PULLED) return;
        letPull(velocity, gestureMs);
    }

    /** The pull ends: settles by position and speed, or, before the first layout, waits for it (waiting). */
    private void letPull(float velocity, long gestureMs) {
        if (travel == 0) {
            if (waiting != WAIT_OPEN) { waiting = WAIT_RELEASE; waitVelocity = velocity; waitMs = gestureMs; }
            return;
        }
        float raw = travel - pulled;
        settle(decide(false, pulled - pullBase, velocity, gestureMs), velocity * (float) SheetSpring.dragSlope(raw, cap));
    }

    /** Notes a catch: the sheet is settling (open or closed) and a finger takes it now. Before stop(). */
    private void caught() {
        boolean settles = state == OPENING || state == CLOSING;
        caughtAt = settles ? offset : Float.NaN;
        caughtClosing = state == CLOSING;
    }

    /**
     * A finger let go of the sheet: opens it or not. startedOpen: the drag began on the open sheet (else a pull from
     * closed). moved: how far (px) the finger went toward open since the drag began. A sheet the finger caught
     * mid-settle decides by SheetSpring.caughtOpens from where it was caught.
     */
    private boolean decide(boolean startedOpen, float moved, float velocity, long gestureMs) {
        float at = caughtAt;
        caughtAt = Float.NaN;
        if (!Float.isNaN(at)) return SheetSpring.caughtOpens(caughtClosing, at, offset, moved, travel, velocity, fling, minFling, gestureMs);
        return SheetSpring.opens(startedOpen, offset, travel, velocity, fling, minFling, gestureMs);
    }

    // ---- Inside -----------------------------------------------------------------------------------------------------

    private boolean show() {
        if (activity.isFinishing() || activity.isDestroyed()) { state = GONE; return false; }
        try {
            dialog.show();
        } catch (RuntimeException windowGone) {
            state = GONE;
            return false;
        }
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (shown != null) shown.accept(dialog);
        return true;
    }

    /** The sheet was laid out: its travel may have changed (first layout, its content or the window changed). */
    private void measured() {
        int now = Math.max(0, panel.getHeight() - extension);
        if (now == travel) return;
        boolean first = travel == 0;
        travel = now;
        if (travel == 0) return;
        if (first && waiting != WAIT_NONE) {
            offset = (float) SheetSpring.dragged(travel - pulled, travel, cap);
            boolean opens = waiting == WAIT_OPEN || SheetSpring.opens(false, offset, travel, waitVelocity, fling, minFling, waitMs);
            float velocity = waiting == WAIT_OPEN ? 0 : waitVelocity;
            waiting = WAIT_NONE;
            // Posted: a settle starts an animator, which is not for the middle of a layout pass. Only if nothing has
            // changed what the sheet does since (a close, a new pull, a dismiss all move the epoch on).
            int token = epoch;
            frame.post(() -> { if (token == epoch && state == PULLED) settle(opens, velocity); });
        } else if (!first && settling != null && plan != null) {
            // A new height mid-settle: the same end, from where it is now, at the speed it had; only if that settle still
            // runs and nothing else happened since (a click handled before this runs, a close, Back, leave(), a dismiss).
            ValueAnimator running = settling;
            boolean open = plan.open;
            float velocity = velocityNow();
            int token = epoch;
            frame.post(() -> { if (token == epoch && settling == running) settle(open, velocity); });
        }
        place();
    }

    /** Edge to edge (Android 11 on): the sheet stays below the status bar and its body above the navigation bar. */
    private void inset(WindowInsets insets) {
        int top = 0, bottom = 0, left = 0, right = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
        }
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (at.topMargin != top || at.leftMargin != left || at.rightMargin != right) {
            at.topMargin = top; at.leftMargin = left; at.rightMargin = right;
            panel.setLayoutParams(at);
        }
        if (panel.getPaddingBottom() != extension + bottom) panel.setPadding(0, 0, 0, extension + bottom);
    }

    /** Puts the sheet and the dim where the state says. */
    private void place() {
        if (travel == 0) return;
        if (state == PULLED) offset = (float) SheetSpring.dragged(travel - pulled, travel, cap);
        panel.setTranslationY(offset);
        float showing = Math.max(0, Math.min(1, 1 - offset / travel));
        scrim.setAlpha(dim * showing);
        statusIcons(dim * showing);
    }

    /**
     * Light theme (edge to edge): the status bar's icons turn white once the dim behind them (over the page's background)
     * passes the contrast crossover, and dark again below it, so whichever shows has at least 4.5:1. Only on a crossing.
     */
    private void statusIcons(float alpha) {
        if (ui.dark || Build.VERSION.SDK_INT < 30) return;
        boolean white = SheetSpring.whiteIcons(ui.bg, alpha);
        if (white == whiteIcons) return;
        whiteIcons = white;
        Window window = dialog.getWindow();
        android.view.WindowInsetsController bars = window == null ? null : window.getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(white ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    }

    /**
     * Starts the settle to open or closed from where the sheet is, at velocity (px/s downward); instant without animations.
     * A let-go sheet only ever closes: settle(true) leaves it as it is. Starting one ends whatever else was going on (stop()
     * moves the epoch on): a held drag's rest of the touch, a pull, a posted settle, a waiting release.
     */
    private void settle(boolean open, float velocity) {
        if (state == GONE || open && letGo) return;
        stop();
        focusable(open);
        if (travel == 0) {
            if (open) { offset = 0; state = OPEN; } else finish();
            return;
        }
        if (!Motion.enabled()) {
            if (open) { offset = 0; state = OPEN; place(); } else finish();
            return;
        }
        SheetSpring.Plan next = open ? SheetSpring.Plan.open(offset, velocity, travel, cap, tolerance) : SheetSpring.Plan.close(offset, velocity, travel);
        state = open ? OPENING : CLOSING;
        plan = next;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(next.millis());
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            if (a != settling) return;
            // Its activity went (a let-go sheet has no owner to say so): it goes at once.
            if (activity.isFinishing() || activity.isDestroyed()) { dismissNow(); return; }
            offset = (float) next.offset(a.getAnimatedFraction() * next.seconds);
            place();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (a != settling) return;
                settling = null;
                stop();
                if (next.open) { offset = 0; state = OPEN; place(); } else finish();
            }
        });
        settling = animator;
        animator.start();
    }

    /** The sheet's speed now (px/s downward): the running settle's, else none. */
    private float velocityNow() {
        ValueAnimator animator = settling;
        SheetSpring.Plan running = plan;
        if (animator == null || running == null) return 0;
        return (float) running.velocity(animator.getAnimatedFraction() * running.seconds);
    }

    /**
     * Stops a settle where it is (a finger caught it, another settle replaces it, it is going away), and moves the epoch
     * on: whatever was posted or waiting for the old motion, and the rest of a touch that drove it, now does nothing.
     */
    private void stop() {
        ValueAnimator animator = settling;
        settling = null; plan = null;
        waiting = WAIT_NONE;
        epoch++;
        if (animator != null) animator.cancel();
    }

    /** Closed: the dialog goes. */
    private void finish() {
        stop();
        offset = travel;
        place();
        state = GONE;
        if (dialog.isShowing()) {
            try { dialog.dismiss(); } catch (RuntimeException alreadyGone) { /* its window went with the activity */ }
        }
    }

    /**
     * While it settles closed the window gives up focus, so a Back pressed meanwhile goes to the page (not to a sheet
     * already on its way out). It keeps taking touches (Frame): the window fills the screen, so none reach the page.
     */
    private void focusable(boolean on) {
        if (focusable == on) return;
        focusable = on;
        Window window = dialog.getWindow();
        if (window == null) return;
        if (on) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    /** Back (and anything else that cancels the dialog) closes it the animated way. */
    private final class Sheet extends Dialog {
        Sheet(Context context) { super(context); }
        @Override public void cancel() { close(); }
    }

    /**
     * The sheet's window: its own touches. A drag on the header moves the sheet; on the list it moves the sheet only where
     * the list cannot scroll that way (and takes over once a list scrolled back to its top keeps going down); a tap on
     * the dim closes it. Everything else goes to the sheet's views as usual. While it settles closed (or is gone but its
     * window not yet removed) no touch reaches its views or anything under it: a finger down on the sheet catches it
     * (catch), one on the dim is swallowed, and a touch that began on its views while it was open loses them (cancelled)
     * and does nothing more. The sheet's own drag acts only in the epoch it began in (gesture): a settle, a dismiss or
     * anything else that moved the sheet on since ends the rest of that touch.
     */
    private final class Frame extends FrameLayout {
        private static final int NONE = 0, VIEWS = 1, DRAG = 2, IGNORE = 3;
        private static final int OUTSIDE = 0, HEAD = 1, LIST = 2;
        private VelocityTracker tracker;
        private int mode, region;
        /** The epoch the sheet's own drag (DRAG) began in. */
        private int gesture;
        private float downX, downY, lastY, dragY, dragRaw;
        private boolean moved;

        Frame(Context context) { super(context); }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            float x = event.getRawX(), y = event.getRawY();
            if (action == MotionEvent.ACTION_DOWN) {
                if (tracker != null) tracker.clear(); else tracker = VelocityTracker.obtain();
                downX = x; downY = y; lastY = y; moved = false;
                region = regionAt(x, y);
                // A second finger while one on the dock pulls the sheet: that pull settles where it is, and this touch does nothing.
                // Let go by its page: no finger catches it; the touch does nothing (and reaches nothing under it).
                if (letGo) { mode = IGNORE; return true; }
                if (state == PULLED) { letPull(0, -1); mode = IGNORE; return true; }
                // Settling closed: a finger on the sheet catches it (its rows never get the touch); the dim swallows it.
                if (state == CLOSING && region != OUTSIDE) { catchAt(y); track(event); return true; }
                mode = state == OPEN || state == OPENING ? NONE : IGNORE;
            }
            track(event);
            // The sheet moved on without this touch (Back, the close button, a dismiss): the rest of its drag does nothing.
            if (mode == DRAG && (gesture != epoch || state != DRAGGED)) mode = IGNORE;
            // A touch on its views while it was open, and it is no longer (it settles closed or is gone): they lose it.
            if ((mode == NONE || mode == VIEWS) && state != OPEN && state != OPENING) {
                if (action != MotionEvent.ACTION_DOWN) cancelViews(event);
                mode = IGNORE;
            }
            if (mode == IGNORE) return true;
            switch (action) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    // A second finger ends the sheet's drag: it settles by where it is.
                    if (mode == DRAG) { mode = IGNORE; let(0, -1); return true; }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) moved = true;
                    if (mode == DRAG) { drag(y); lastY = y; return true; }
                    if (mode == NONE && (state == OPEN || state == OPENING) && Math.abs(y - downY) > slop && Math.abs(y - downY) > Math.abs(x - downX)) {
                        boolean down = y > downY;
                        boolean takes = region == HEAD || region == LIST && (down ? !list.canScrollVertically(-1) : !list.canScrollVertically(-1) && !list.canScrollVertically(1));
                        if (takes) { take(event, y); return true; }
                        if (region == LIST) mode = VIEWS;
                    } else if (mode == VIEWS && region == LIST && y > lastY && (state == OPEN || state == OPENING) && !list.canScrollVertically(-1)) {
                        // The list came back to its top under the finger: the rest of the way down moves the sheet.
                        take(event, y);
                        return true;
                    }
                    lastY = y;
                    break;
                case MotionEvent.ACTION_UP:
                    if (mode == DRAG) {
                        drag(y); lastY = y;
                        tracker.computeCurrentVelocity(1000);
                        let(tracker.getYVelocity(), event.getEventTime() - event.getDownTime());
                        mode = NONE;
                        return true;
                    }
                    if (region == OUTSIDE && !moved && mode == NONE) { close(); return true; }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (mode == DRAG) { let(0, -1); mode = NONE; return true; }
                    break;
                default:
                    break;
            }
            // The dim holds nothing to touch.
            if (region == OUTSIDE) return true;
            return super.dispatchTouchEvent(event);
        }

        /** The sheet's own drag starts here: the views lose the touch, and the sheet moves from where it is (no jump). */
        private void take(MotionEvent event, float y) {
            cancelViews(event);
            caught();
            stop();
            gesture = epoch;
            dragY = y; lastY = y;
            dragRaw = (float) SheetSpring.undragged(offset, cap);
            mode = DRAG;
            state = DRAGGED;
        }

        /**
         * A finger down on the sheet settling closed: it stops where it is and follows the finger from there, focused again
         * (so Back goes to it, and the page doesn't act as if no sheet were over it); a release that closes it gives the
         * focus back to the page.
         */
        private void catchAt(float y) {
            caught();
            stop();
            gesture = epoch;
            focusable(true);
            dragY = y; lastY = y;
            dragRaw = (float) SheetSpring.undragged(offset, cap);
            mode = DRAG;
            state = DRAGGED;
        }

        /** The sheet's views lose this touch (an ACTION_CANCEL to them). */
        private void cancelViews(MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }

        private void drag(float y) {
            if (state != DRAGGED || gesture != epoch) return;
            offset = (float) SheetSpring.dragged(dragRaw + (y - dragY), travel, cap);
            place();
        }

        /** The sheet's own drag let go (or cancelled: velocity 0, gestureMs -1). */
        private void let(float velocity, long gestureMs) {
            if (state != DRAGGED || gesture != epoch) return;
            float raw = dragRaw + (lastY - dragY);
            settle(decide(true, dragY - lastY, velocity, gestureMs), velocity * (float) SheetSpring.dragSlope(raw, cap));
        }

        private void track(MotionEvent event) {
            if (tracker == null) return;
            // In screen coordinates: the sheet moves under the finger, so its own would not do.
            MotionEvent copy = MotionEvent.obtain(event);
            copy.setLocation(event.getRawX(), event.getRawY());
            tracker.addMovement(copy);
            copy.recycle();
        }

        private int regionAt(float x, float y) {
            if (!within(panel, x, y)) return OUTSIDE;
            for (View view : header) if (view != null && view.isShown() && within(view, x, y)) return HEAD;
            return LIST;
        }

        private boolean within(View view, float x, float y) {
            Rect seen = new Rect();
            if (!view.getGlobalVisibleRect(seen)) return false;
            int[] window = new int[2], screen = new int[2];
            getLocationInWindow(window);
            getLocationOnScreen(screen);
            seen.offset(screen[0] - window[0], screen[1] - window[1]);
            return seen.contains(Math.round(x), Math.round(y));
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
            if (tracker != null) { tracker.recycle(); tracker = null; }
        }
    }

    /**
     * A finger on a view under the sheet that pulls it up (Widgets Home's dock): give it every event of the view's touch,
     * call start once the view has recognised its upward swipe, and it does the rest (follow, release, cancel).
     */
    static final class Pull {
        private VelocityTracker tracker;
        private DragSheet sheet;
        /** The pull's token (DragSheet.pull): the sheet ignores it once anything else has moved the sheet on. */
        private int token;
        private float downY;

        /** Every event of the touch, from its ACTION_DOWN, each once (the view's onInterceptTouchEvent and onTouchEvent). */
        void track(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (tracker != null) tracker.clear(); else tracker = VelocityTracker.obtain();
                downY = event.getRawY();
                sheet = null;
            }
            if (tracker == null) return;
            MotionEvent copy = MotionEvent.obtain(event);
            copy.setLocation(event.getRawX(), event.getRawY());
            tracker.addMovement(copy);
            copy.recycle();
        }

        /** The swipe is recognised on event: the sheet (null when none can show) is pulled from here. */
        void start(DragSheet pulled, MotionEvent event) {
            token = pulled == null ? -1 : pulled.pull();
            sheet = token >= 0 ? pulled : null;
            handle(event);
        }

        /** After start, each later event of the touch (already given to track). */
        void handle(MotionEvent event) {
            DragSheet pulled = sheet;
            if (pulled == null) return;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    pulled.follow(token, downY - event.getRawY());
                    break;
                case MotionEvent.ACTION_UP:
                    pulled.follow(token, downY - event.getRawY());
                    tracker.computeCurrentVelocity(1000);
                    pulled.release(token, tracker.getYVelocity(), event.getEventTime() - event.getDownTime());
                    sheet = null;
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Settles by where it is; the rest of this touch moves nothing.
                    pulled.release(token, 0, -1);
                    sheet = null;
                    break;
                default:
                    break;
            }
        }
    }
}
