package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A bottom sheet that follows a finger and springs (the owner's request on the Widgets Home dock, Nov 15), and that grows
 * out of the view that pulls it (the dock itself turning into the sheet, his follow-up the same day: "it should move the
 * actual dock"). Open, it looks like MainActivity.showSheet's: the same body in a scroller, Volt's background with 24 dp
 * top corners, over a dim. What differs is how it moves:
 *
 * <ul>
 *   <li>Pulled up from a view under it (the dock, through {@link Pull}), it shows at once, and its top follows the finger
 *   1:1 from where the swipe was recognised; pulling back down lowers it again.</li>
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
 * <p><b>Growing out of its source (a container transform).</b> With a {@link Source} (the dock), closed is the source
 * exactly and open is the sheet: one surface (TAG_SURFACE) goes from one to the other as SheetMorph says, driven by the
 * same offset the spring and the finger move. Its top edge is the sheet's top (the finger's, 1:1); its sides, bottom,
 * corners, fill, outline and shadow ease from the source's (read from the live view and its background every layout, so
 * a dock that changes shape or place is followed) to the open sheet's. The source's own content (the dock's buttons) is
 * drawn on the surface where it was, fading out by a quarter of the way; the sheet's handle, title and list fade in
 * from 35% to open, settling up 8 dp; never both. The travel is the distance from the source's top to the open sheet's,
 * so progress 0 is the source and the commit point is a quarter of that.
 *
 * <p><b>The hand-off</b> is what makes this one surface rather than two: the sheet lives in its activity's own window
 * (an overlay on top of the window's decor, not a window of its own), so hiding the source and drawing the surface where
 * it was happen in the same frame, in both directions. The sheet's first frame is the source exactly (bounds, corners,
 * colours, shadow and content), and the source is hidden (INVISIBLE, which keeps its layout) in that same frame; at the
 * end of a close the surface draws the source exactly for one last frame, and the next frame removes the sheet and shows
 * the source again. So there is never a frame with both, or with neither. Without a source (or once its source has gone)
 * the sheet rises from under the screen's bottom edge as a plain sheet, as before.
 *
 * <p>The overlay fills the window, edge to edge: its own scrim, the surface, and the sheet's content at its bottom. The
 * background reaches CAP_DP below the screen's edge, so the bounce and the rubber band never show a gap under it. While
 * the sheet shows, the rest of the window is hidden from accessibility services and takes no focus, so TalkBack and a
 * keyboard stay on the sheet as they would in a dialog; Back reaches the sheet first through its activity (back()). While
 * it settles closed it still takes every touch (as Material's BottomSheetDialog does while it hides), so nothing under it
 * is touched by accident, not even a lock control on the page: a finger down on the sheet catches it and drags it from
 * where it is (it then carries on closing unless dragged back up a quarter of its travel, SheetSpring.caughtOpens, so a
 * plain tap lets it go), and a touch on the dim is swallowed. It gives up Back and focus meanwhile (focusable), so a
 * Back pressed then goes to the page, and a page opened from one of its rows can focus its search field. On the light theme the status bar's icons turn white once the dim behind them passes the
 * contrast crossover (SheetSpring.whiteIcons), and dark again below it; the window's own setting comes back when it goes.
 *
 * <p><b>Deferred work and its token.</b> Nothing done later acts on a sheet that has moved on since. {@code epoch} goes up
 * with every change of what the sheet is doing: stop() (so every settle, catch, pull, take, finish and dismiss), the end
 * of a settle, and leave(). A posted settle (the first layout's, and the restart for a new travel mid-settle) acts only
 * while the epoch it was posted in is current, the restart only while the settle it restarts still runs; a running
 * settle's animator acts only while it is still {@code settling} (its identity); the dock's pull (Pull) and the sheet's
 * own drag (Frame) act only in the epoch they began in, so anything else that moves the sheet ends the rest of that
 * touch; a waiting release or open (waiting) is dropped by stop(); the last frame's removal (removal) is dropped by a
 * dismiss, which removes the sheet itself. And a let-go sheet never moves toward open again: settle(true) refuses it.
 *
 * <p><b>States and what each ignores</b> (anything not listed acts as its method says):
 * <ul>
 *   <li>NEW (not shown): open and pull show it (the source hands over in the same frame); close and leave make it GONE
 *   with nothing shown; follow, release and measured cannot reach it (not attached yet).</li>
 *   <li>PULLED (a dock finger pulls it, or a tap waits for its first layout): follow and release from that pull only
 *   (its epoch); a finger down on the sheet's overlay settles the pull where it is and the rest of that touch does
 *   nothing; open, close (Back) settle it, and the pull's later moves are ignored; leave goes at once.</li>
 *   <li>DRAGGED (its own finger holds it): open and pull are ignored; close (Back, the close button) settles it closed
 *   and the rest of that touch does nothing; leave goes at once; measured only places it.</li>
 *   <li>OPENING: open is ignored; close settles closed from where it is; a finger may take it (DRAGGED); measured
 *   restarts the opening for the new travel (posted, its token); leave goes at once.</li>
 *   <li>OPEN: open is ignored; close settles closed; leave goes at once; measured only places it.</li>
 *   <li>CLOSING: close is ignored; open springs back open; a finger on the sheet catches it (DRAGGED, Back reaches it
 *   again), on the dim is swallowed; a touch that began on its views while it was open does nothing more; Back goes to
 *   the page and the page may take focus (not focusable); measured restarts the close for the new travel; leave lets it
 *   go (below), or goes at once if nothing settles it or its activity is ending.</li>
 *   <li>CLOSING, let go: open, close, pull, back and leave are ignored (leave goes at once if the activity is ending),
 *   every touch is swallowed, settle(true) refuses; it only finishes its settle and goes, or goes at once with its
 *   activity. If it grew out of a source, that source is going (Home rebuilt): the surface keeps the shape it had when
 *   let go and slides down off the screen as a plain sheet (its travel now to under the bottom edge, the scrim fading
 *   with it), and the old source is handed back at once (it leaves with its page; the new page's dock shows under the
 *   dim). Its travel no longer follows layouts.</li>
 *   <li>GONE: everything is ignored. A sheet that grew out of a source and closed draws the source exactly for this
 *   one frame, swallowing touches, and is removed with the source shown again on the next frame (removal); any other is
 *   removed at once. A GONE sheet is never shown again.</li>
 * </ul>
 * <b>The source changing under it.</b> A dock that moves or changes size (Home laid out again, an inset, larger text)
 * is read again on its next layout (measured): the surface follows it and the travel becomes the new distance, a running
 * settle restarting for it as for a new sheet height. A dock that is rebuilt (Home rebuilt: a page a row opened, a
 * rotation, a fold, the window resized) closes its HubDock, whose leave() ends the sheet as above; a source found
 * detached without that is treated the same way (posted). Inset changes only set margins and padding (a layout, so
 * measured). The activity ending: leave() from its owner (Home closing), each settle frame dismisses at once if its
 * activity is finishing or destroyed, and an overlay taken down with its window counts as GONE.
 *
 * Another sheet opts in by building its body with ScreenHost.sheetContent as before and calling
 * host.dragSheet(source, body, DragSheet.header(body)).open() where it called host.showSheet(body) (source null for a
 * plain sheet); its close button then calls close() rather than dismiss().
 */
final class DragSheet {
    /** View tags: the sheet's content (what moves), its dim, and the surface it shows on (the source's shape, then the sheet's). */
    static final String TAG_PANEL = "drag-sheet-panel", TAG_SCRIM = "drag-sheet-scrim", TAG_SURFACE = "drag-sheet-surface";
    /** The states: not shown yet; pulled by a finger outside it; dragged by its own; settling open; open; settling closed; gone. */
    static final int NEW = 0, PULLED = 1, DRAGGED = 2, OPENING = 3, OPEN = 4, CLOSING = 5, GONE = 6;
    /** The open sheet's top corners (dp), as showSheet's. */
    private static final int CORNER_DP = 24;

    /**
     * What a sheet grows out of (Widgets Home's dock): a laid-out view whose background is its surface (a GradientDrawable,
     * maybe wrapped, as in an InsetDrawable), which the sheet hides while it draws it and its content.
     */
    interface Source {
        /** The view; its place, size, background (bounds, corners, fill), elevation and shadow colours are read from it live. */
        View view();
        /** Hide the view (INVISIBLE: true) while the sheet draws it, or show it again (false). */
        void handed(boolean handed);
        /** Draws the view's content without its background, in the view's own coordinates. */
        void drawContent(Canvas canvas);
        /** Its background's outline colour and width (px): a GradientDrawable cannot say. */
        int stroke();
        float strokeWidth();
    }

    private final Activity activity;
    private final Ui ui;
    private final Frame frame;
    private final View scrim;
    private final Surface surface;
    private final LinearLayout panel;
    private final ScrollView list;
    private final View[] header;
    private final Consumer<DragSheet> shown;
    /** The dim at fully open: the dialog theme's own (what showSheet's sheets get). */
    private final float dim;
    /** px: the most past open, how far the background reaches below the edge, a fling, Android's minimum fling, the touch slop, the content's settle. */
    private final int cap, extension, fling, minFling, slop, lift;
    private final float tolerance;
    private int state = NEW;
    /** The sheet's travel (px; 0 until it is laid out) and where it is now (px below open). */
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
    /** Back reaches it (back()), and the window's other views take no focus: false while it settles closed. */
    private boolean focusable = true;
    /**
     * A finger caught the sheet while it settled (SheetSpring.caughtOpens decides its release): where it was then (px),
     * and whether it was settling closed. NaN when the drag or pull did not catch a settle.
     */
    private float caughtAt = Float.NaN;
    private boolean caughtClosing;
    /**
     * What opened it let go of it while it settled closed (leave()): it only finishes that settle and goes. No finger
     * catches it (its overlay still takes every touch, which does nothing), and open(), close() and pull() do nothing.
     */
    private boolean letGo;
    /** The token of every deferred action (the class comment): up by one with each change of what the sheet is doing. */
    private int epoch;
    /** Light theme: the status bar's icons are white now (the dim behind them passed the crossover). */
    private boolean whiteIcons;
    private Runnable onDismiss;

    /** What it grows out of, null when none (or once it has gone); morph: it is growing out of it now (the travel is its). */
    private Source source;
    private boolean morph;
    /** The source's surface and the open sheet's (frame px), as last laid out; null until known. */
    private SheetMorph.Box dockBox, openBox;
    /** The source view's own origin in the frame (its content is drawn from there). */
    private int dockX, dockY;
    /** Let go while it grew out of its source: the look it had then (frozen), slid down by offset - frozenAt. */
    private SheetMorph.Box frozen;
    private float frozenAt, frozenPanelY, frozenContent, frozenShowing;
    /** The source's layouts (measured). */
    private final View.OnLayoutChangeListener sourceMoved = (v, l, t, r, b, ol, ot, or, ob) -> measured();
    /** Closed out of a source: the removal waiting for the next frame (the last frame drew the source). */
    private Runnable removal;
    private boolean removed;
    /** The window's other views hidden from accessibility and focus while it shows, and how they were. */
    private final List<View> covered = new ArrayList<>();
    private final List<int[]> coveredWas = new ArrayList<>();
    private final Rect clip = new Rect();

    /**
     * source: what it grows out of (the dock), or null for a sheet that rises from the screen's edge. body: the sheet's
     * body (ScreenHost.sheetContent). header: views inside it that move the sheet whenever they are dragged
     * (header(body)). shown: told when it shows (MainActivity keeps it for dialogShowing and Back), may be null. Not shown
     * until open() or pull().
     */
    DragSheet(Activity activity, Ui ui, Source source, View body, View[] header, Consumer<DragSheet> shown) {
        this.activity = activity;
        this.ui = ui;
        this.source = source;
        this.header = header == null ? new View[0] : header;
        this.shown = shown;
        Context themed = new ContextThemeWrapper(activity, ui.dark ? R.style.VoltDialog : R.style.VoltDialogLight);
        float density = activity.getResources().getDisplayMetrics().density;
        cap = Math.round(SheetSpring.CAP_DP * density);
        extension = cap + Math.round(6 * density);
        lift = Math.round(SheetMorph.LIFT_DP * density);
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
        surface = new Surface(activity);
        // Laid out by place() alone (Frame.onLayout puts it back after every layout): nothing until then.
        frame.addView(surface, new FrameLayout.LayoutParams(0, 0));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(TAG_PANEL);
        // No shadow of its own: it is raised only to stay level with the surface (place()).
        panel.setOutlineProvider(null);
        // Its background is the surface's (open: Volt's background, 24 dp top corners, the 1 dp raised outline), which
        // reaches on below the screen's edge by more than the bounce can lift it (the negative margin below).
        panel.setPadding(0, 0, 0, extension);
        // Off screen until it is laid out and placed (place()), so its first frame never flashes it.
        panel.setTranslationY(100000);
        list = new ScrollView(activity);
        list.addView(body, new ScrollView.LayoutParams(-1, -2));
        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        at.bottomMargin = -extension;
        frame.addView(panel, at);
        // Its content laid out anew (a new size): the travel first, before anyone else listening to the panel sees it
        // (Frame.onLayout measures again once the whole overlay is laid out).
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> measured());
        frame.setOnApplyWindowInsetsListener((v, insets) -> { inset(insets); return insets; });
        if (Build.VERSION.SDK_INT >= 28) {
            String title = title(this.header);
            if (title != null) panel.setAccessibilityPaneTitle(title);
        }
        Motion.quietScrollbars(frame);
    }

    /** The header of a ScreenHost.sheetContent body: its grab handle and its title row (with the close button), its first two views. */
    static View[] header(ViewGroup body) {
        int count = Math.min(2, body.getChildCount());
        View[] views = new View[count];
        for (int i = 0; i < count; i++) views[i] = body.getChildAt(i);
        return views;
    }

    /** The overlay that holds it (in its activity's window while it shows). */
    View view() { return frame; }

    /** Runs once it is gone, however it went. */
    void onDismiss(Runnable action) { onDismiss = action; }

    /** Shown, and not on its way out for good. */
    boolean showing() { return state != NEW && state != GONE && !removed; }

    /**
     * Back while it shows (MainActivity asks before its pages): closes it the animated way and returns true; false (Back
     * goes to the page) when it is not showing or already settling closed.
     */
    boolean back() {
        if (!showing() || letGo || !focusable) return false;
        close();
        return true;
    }

    /** Opens it from closed with the opening spring (a tap); one already settling closed springs back open from where it is. */
    void open() {
        if (state == GONE || letGo) return;
        if (state == NEW) {
            if (!show()) return;
            // Closed (the source exactly, or nothing pulled) until the first layout gives its travel; then it springs open.
            state = PULLED; pulled = pullBase = 0; waiting = WAIT_OPEN;
            place();
            return;
        }
        if (state == OPEN || state == OPENING || state == DRAGGED) return;
        if (travel == 0) { waiting = WAIT_OPEN; return; }
        settle(true, state == CLOSING ? velocityNow() : 0);
    }

    /** Closes it with the closing settle, then it goes (the close button, Back, a row, a tap on the dim). */
    void close() {
        if (letGo || state == GONE || state == CLOSING) return;
        if (state == NEW) { state = GONE; return; }
        if (travel == 0) { dismissNow(); return; }
        settle(false, state == PULLED || state == DRAGGED ? 0 : velocityNow());
    }

    /**
     * What opened it goes (Home rebuilt for a page a row opened, a rotation, the activity ending): a sheet already settling
     * closed (that row's own close) finishes its settle over the new page, let go (letGo: nothing can catch or reopen
     * it, as nothing owns its rows now; one that grew out of the dock slides away as it is, the dock going with its page);
     * any other goes at once (one a finger holds too), and so does every sheet of an activity that is ending.
     */
    void leave() {
        if (letGo && !activity.isFinishing() && !activity.isDestroyed() && state == CLOSING) return;
        if (state == CLOSING && settling != null && !activity.isFinishing() && !activity.isDestroyed()) {
            letGo = true;
            if (morph) slideOut();
            // Its running close carries on (the animator is its own token); anything else posted for it is dropped.
            else epoch++;
            return;
        }
        dismissNow();
    }

    /** Gone at once, with no animation. */
    void dismissNow() {
        stop();
        int was = state;
        state = GONE;
        if (was != NEW) remove();
    }

    // ---- A pull from outside (the dock) --------------------------------------------------------------------------------

    /**
     * A finger outside the sheet starts pulling it up: shown now (as its source exactly, or under the edge) if it is new,
     * or caught where it is if it was settling. Returns the pull's token for follow and release, or -1 when it cannot be
     * pulled (gone, let go, held by its own finger, or the activity is going away).
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

    /** The pull (its token) has gone up by distance (px) since its swipe was recognised. */
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

    /**
     * Puts the overlay on top of the activity's window and, with a laid-out source, takes it over: it is hidden in the
     * frame the surface first draws it.
     */
    private boolean show() {
        if (activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) { state = GONE; return false; }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup) || !decor.isAttachedToWindow()) { state = GONE; return false; }
        ViewGroup window = (ViewGroup) decor;
        try {
            window.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        } catch (RuntimeException cannot) {
            state = GONE;
            return false;
        }
        WindowInsets insets = window.getRootWindowInsets();
        if (insets != null) inset(insets);
        // The rest of the window: out of TalkBack's reach and of focus while the sheet is over it, as under a dialog.
        for (int i = 0; i < window.getChildCount(); i++) {
            View other = window.getChildAt(i);
            if (other == frame) continue;
            covered.add(other);
            coveredWas.add(new int[]{other.getImportantForAccessibility(), other instanceof ViewGroup ? ((ViewGroup) other).getDescendantFocusability() : -1});
            other.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (other instanceof ViewGroup) ((ViewGroup) other).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        SheetMorph.Box from = source == null ? null : readDock();
        if (from != null) {
            dockBox = from;
            morph = true;
            source.handed(true);
            source.view().addOnLayoutChangeListener(sourceMoved);
            if (Build.VERSION.SDK_INT >= 28) {
                surface.setOutlineAmbientShadowColor(source.view().getOutlineAmbientShadowColor());
                surface.setOutlineSpotShadowColor(source.view().getOutlineSpotShadowColor());
            }
        } else {
            source = null;
        }
        if (shown != null) shown.accept(this);
        OwnDialogs.track(this);
        return true;
    }

    /** The source's surface now, in the frame's px (its background's bounds, corners and fill; its outline and shadow), or null when it cannot be read. */
    private SheetMorph.Box readDock() {
        View view = source == null ? null : source.view();
        if (view == null || !view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        int[] at = new int[2], me = new int[2];
        view.getLocationInWindow(at);
        frame.getLocationInWindow(me);
        dockX = at[0] - me[0];
        dockY = at[1] - me[1];
        Drawable background = view.getBackground();
        while (background instanceof DrawableWrapper && ((DrawableWrapper) background).getDrawable() != null)
            background = ((DrawableWrapper) background).getDrawable();
        GradientDrawable shape = background instanceof GradientDrawable ? (GradientDrawable) background : null;
        Rect bounds = shape == null ? new Rect() : new Rect(shape.getBounds());
        if (bounds.isEmpty()) bounds.set(0, 0, view.getWidth(), view.getHeight());
        double[] radii = new double[4];
        float[] corners = shape == null ? null : shape.getCornerRadii();
        if (corners != null && corners.length >= 8) for (int i = 0; i < 4; i++) radii[i] = corners[2 * i];
        else if (shape != null) java.util.Arrays.fill(radii, shape.getCornerRadius());
        ColorStateList fills = shape == null ? null : shape.getColor();
        int fill = fills == null ? ui.surface : fills.getColorForState(view.getDrawableState(), fills.getDefaultColor());
        return new SheetMorph.Box(dockX + bounds.left, dockY + bounds.top, dockX + bounds.right, dockY + bounds.bottom, radii,
                fill, source.stroke(), source.strokeWidth(), view.getElevation() + view.getTranslationZ());
    }

    /** The open sheet's surface (frame px): the content's bounds, reaching below the edge. */
    private SheetMorph.Box openBox() {
        double r = ui.dp(CORNER_DP);
        return new SheetMorph.Box(panel.getLeft(), panel.getTop(), panel.getRight(), panel.getBottom(), new double[]{r, r, 0, 0},
                ui.bg, ui.raised, ui.dp(1), 0);
    }

    /** The overlay or the source was laid out: the travel may have changed (first layout, its content, the window, the dock). */
    private void measured() {
        if (state == NEW || removed) return;
        // Its last frame (the source exactly) stays put through any layout until it is removed.
        if (state == GONE) { place(); return; }
        if (panel.getHeight() > 0) openBox = openBox();
        int now;
        if (frozen != null) {
            now = travel;
        } else if (morph) {
            SheetMorph.Box from = readDock();
            if (from == null) {
                // Its source went without a word (its owner would have called leave()): the same, once this layout is done.
                int token = epoch;
                frame.post(() -> { if (token == epoch && morph && readDock() == null) leave(); });
                return;
            }
            dockBox = from;
            now = openBox == null ? 0 : Math.max(1, (int) Math.round(from.top - openBox.top));
        } else {
            now = Math.max(0, panel.getHeight() - extension);
        }
        if (now == travel) { place(); return; }
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
            // A new travel mid-settle: the same end, from where it is now, at the speed it had; only if that settle still
            // runs and nothing else happened since (a click handled before this runs, a close, Back, leave(), a dismiss).
            ValueAnimator running = settling;
            boolean open = plan.open;
            float velocity = velocityNow();
            int token = epoch;
            frame.post(() -> { if (token == epoch && settling == running) settle(open, velocity); });
        }
        place();
    }

    /** The overlay fills the window: the sheet stays below the status bar (and clear of a side cutout) and its body above the navigation bar. */
    private void inset(WindowInsets insets) {
        int top, bottom, left, right;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
        } else {
            top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
        }
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (at.topMargin != top || at.leftMargin != left || at.rightMargin != right) {
            at.topMargin = top; at.leftMargin = left; at.rightMargin = right;
            panel.setLayoutParams(at);
        }
        if (panel.getPaddingBottom() != extension + bottom) panel.setPadding(0, 0, 0, extension + bottom);
    }

    /** Puts the surface, the sheet's content and the dim where the state says. */
    private void place() {
        if (state == NEW || removed) return;
        if (state == PULLED && travel > 0) offset = (float) SheetSpring.dragged(travel - pulled, travel, cap);
        SheetMorph.Box box;
        double buttons = 0, content = 1, showing;
        float panelY;
        if (frozen != null) {
            double shift = offset - frozenAt;
            box = frozen.shifted(shift);
            panelY = (float) (frozenPanelY + shift);
            content = frozenContent;
            showing = travel > frozenAt ? frozenShowing * Math.max(0, travel - offset) / (travel - frozenAt) : 0;
        } else if (morph) {
            if (dockBox == null) return;
            if (travel == 0 || openBox == null) {
                // Not laid out yet: the source exactly.
                box = dockBox; buttons = 1; content = 0; panelY = 100000; showing = 0;
            } else {
                double p = SheetMorph.progress(offset, travel);
                box = SheetMorph.at(dockBox, openBox, offset, travel);
                buttons = SheetMorph.buttons(p);
                content = SheetMorph.content(p);
                panelY = (float) (offset + SheetMorph.lift(p, lift));
                showing = Math.max(0, Math.min(1, p));
            }
        } else {
            if (travel == 0 || openBox == null) return;
            box = openBox.shifted(offset);
            panelY = offset;
            showing = Math.max(0, Math.min(1, 1 - offset / (double) travel));
        }
        surface.show(box, buttons);
        // Level with the surface: a view of lower Z draws under one of higher Z whatever their order, so while the
        // surface keeps any of the dock's elevation it would hide the content. Level, the content (added after it) draws on top.
        panel.setTranslationZ((float) box.elevation);
        panel.setTranslationY(panelY);
        panel.setAlpha((float) content);
        // The content never shows outside the surface (its sides are still coming in while it fades in).
        int top = panel.getTop() + Math.round(panelY);
        clip.set(surface.getLeft() - panel.getLeft(), surface.getTop() - top, surface.getRight() - panel.getLeft(), surface.getBottom() - top);
        if (clip.left <= 0 && clip.top <= 0 && clip.right >= panel.getWidth() && clip.bottom >= panel.getHeight()) {
            if (panel.getClipBounds() != null) panel.setClipBounds(null);
        } else {
            panel.setClipBounds(clip);
        }
        scrim.setAlpha((float) (dim * showing));
        statusIcons((float) (dim * showing));
    }

    /**
     * Let go while growing out of its source (the source is going with its page): keeps the look it has now, hands the
     * source back, and finishes its close as a plain slide to under the screen's bottom edge, at the speed it had.
     */
    private void slideOut() {
        float velocity = velocityNow();
        SheetMorph.Box now = travel > 0 && dockBox != null && openBox != null ? SheetMorph.at(dockBox, openBox, offset, travel) : dockBox;
        if (now == null) { dismissNow(); return; }
        frozen = new SheetMorph.Box(now.left, now.top, now.right, now.bottom, now.radii, now.fill, now.stroke, now.strokeWidth, now.elevation);
        frozenAt = offset;
        frozenPanelY = panel.getTranslationY();
        frozenContent = panel.getAlpha();
        frozenShowing = dim > 0 ? scrim.getAlpha() / dim : 0;
        int height = frame.getHeight() > 0 ? frame.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        // Until its top (and its shadow) are under the edge.
        travel = (int) Math.ceil(offset + Math.max(0, height - now.top) + now.elevation + ui.dp(2));
        handBack();
        morph = false;
        settle(false, velocity);
    }

    /** The source shows again (and is no longer followed). */
    private void handBack() {
        Source was = source;
        source = null;
        if (was == null) return;
        if (morph && was.view() != null) was.view().removeOnLayoutChangeListener(sourceMoved);
        was.handed(false);
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
        android.view.WindowInsetsController bars = activity.getWindow() == null ? null : activity.getWindow().getInsetsController();
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

    /**
     * Closed. Grown out of its source: this frame draws the source exactly, and the next removes the sheet and shows the
     * source (removal), so the two meet in one frame. Otherwise it is under the edge, and goes now.
     */
    private void finish() {
        stop();
        offset = travel;
        state = GONE;
        place();
        if (morph && Motion.enabled() && frame.isAttachedToWindow()) {
            Runnable last = () -> { removal = null; remove(); };
            removal = last;
            frame.postOnAnimation(last);
        } else {
            remove();
        }
    }

    /** Takes the overlay down: the source shows again in the same frame, the window's views come back to TalkBack and focus. */
    private void remove() {
        if (removed) return;
        removed = true;
        if (removal != null) { frame.removeCallbacks(removal); removal = null; }
        handBack();
        morph = false;
        restoreWindow();
        ViewGroup parent = frame.getParent() instanceof ViewGroup ? (ViewGroup) frame.getParent() : null;
        if (parent != null) {
            try { parent.removeView(frame); } catch (RuntimeException alreadyGone) { /* its window went with the activity */ }
        }
        Runnable dismissed = onDismiss;
        onDismiss = null;
        if (dismissed != null) dismissed.run();
    }

    /** The window as it was before the sheet: its views' accessibility and focus, the status bar's icons. */
    private void restoreWindow() {
        for (int i = 0; i < covered.size(); i++) {
            View other = covered.get(i);
            int[] was = coveredWas.get(i);
            other.setImportantForAccessibility(was[0]);
            if (was[1] >= 0 && other instanceof ViewGroup) ((ViewGroup) other).setDescendantFocusability(was[1]);
        }
        covered.clear(); coveredWas.clear();
        if (whiteIcons) {
            whiteIcons = false;
            android.view.WindowInsetsController bars = Build.VERSION.SDK_INT < 30 || activity.getWindow() == null ? null : activity.getWindow().getInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    /**
     * While it settles closed Back goes to the page (not to a sheet already on its way out), and so does focus: a page
     * opened from one of its rows (Search) puts its cursor in its field while the sheet is still on its way down. It
     * keeps taking touches (Frame): the overlay fills the window, so none reach the page. Caught again, it takes Back
     * and focus back.
     */
    private void focusable(boolean on) {
        focusable = on;
        for (int i = 0; i < covered.size(); i++) {
            View other = covered.get(i);
            int was = coveredWas.get(i)[1];
            if (was >= 0 && other instanceof ViewGroup) ((ViewGroup) other).setDescendantFocusability(on ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : was);
        }
    }

    /** The title of a sheetContent header (its first TextView), for TalkBack's pane title. */
    private static String title(View[] header) {
        for (View view : header) {
            String found = firstText(view);
            if (found != null) return found;
        }
        return null;
    }

    private static String firstText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null || text.length() == 0 ? null : text.toString();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = firstText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The one surface: the source's shape at 0, the open sheet's at 1, and everything between (SheetMorph.at), with its
     * shadow (its elevation over an outline of its own corners) and, while they fade, the source's content where it was.
     */
    private final class Surface extends View {
        private final GradientDrawable shape = new GradientDrawable();
        private final float[] corners = new float[8];
        private double buttons;
        private int mirrorX, mirrorY;

        Surface(Context context) {
            super(context);
            setTag(TAG_SURFACE);
            setBackground(shape);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        }

        void show(SheetMorph.Box box, double buttons) {
            int l = (int) Math.round(box.left), t = (int) Math.round(box.top), r = (int) Math.round(box.right), b = (int) Math.round(box.bottom);
            if (l != getLeft() || t != getTop() || r != getRight() || b != getBottom()) layout(l, t, r, b);
            shape.setColor(box.fill);
            shape.setStroke((int) Math.round(box.strokeWidth), box.stroke);
            if (box.radii[0] == box.radii[1] && box.radii[1] == box.radii[2] && box.radii[2] == box.radii[3]) {
                shape.setCornerRadius((float) box.radii[0]);
            } else {
                for (int i = 0; i < 4; i++) corners[2 * i] = corners[2 * i + 1] = (float) box.radii[i];
                shape.setCornerRadii(corners);
            }
            setElevation((float) box.elevation);
            invalidateOutline();
            this.buttons = morph && source != null ? buttons : 0;
            mirrorX = dockX - l;
            mirrorY = dockY - t;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            Source from = source;
            if (buttons <= 0 || from == null || from.view() == null) return;
            View view = from.view();
            int save = canvas.save();
            canvas.translate(mirrorX, mirrorY);
            int alpha = (int) Math.round(buttons * 255);
            if (alpha < 255) canvas.saveLayerAlpha(0, 0, view.getWidth(), view.getHeight(), alpha);
            try {
                from.drawContent(canvas);
            } catch (RuntimeException cannot) {
                // Its content fades within a quarter of the way anyway; the surface itself is still the dock's.
                buttons = 0;
            }
            canvas.restoreToCount(save);
        }
    }

    /**
     * The sheet's overlay: its own touches. A drag on the header moves the sheet; on the list it moves the sheet only where
     * the list cannot scroll that way (and takes over once a list scrolled back to its top keeps going down); a tap on
     * the dim closes it. Everything else goes to the sheet's views as usual. Every touch it gets stays with it (it fills
     * the window, so nothing under it is ever touched). While it settles closed (or is gone but not yet removed) no touch
     * reaches its views: a finger down on the sheet catches it (catch), one on the dim is swallowed, and a touch that
     * began on its views while it was open loses them (cancelled) and does nothing more. The sheet's own drag acts only
     * in the epoch it began in (gesture): a settle, a dismiss or anything else that moved the sheet on since ends the
     * rest of that touch.
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

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // The surface's place is place()'s alone; the sheet's travel may have changed with this layout.
            measured();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestApplyInsets();
        }

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
            super.dispatchTouchEvent(event);
            // Kept even where no view of the sheet wants it: in the same window, a touch it let go would reach the page.
            return true;
        }

        /** A mouse wheel or a hover over the dim reaches nothing under it either. */
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            boolean handled = super.dispatchGenericMotionEvent(event);
            return handled || event.isFromSource(InputDevice.SOURCE_CLASS_POINTER);
        }

        @Override public boolean dispatchHoverEvent(MotionEvent event) {
            super.dispatchHoverEvent(event);
            return true;
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
         * A finger down on the sheet settling closed: it stops where it is and follows the finger from there, and Back
         * reaches it again; a release that closes it gives Back back to the page.
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

        /** Where a finger at (x, y) on the screen is: on the dim, the sheet's header, or the rest of the sheet (its surface as it shows now). */
        private int regionAt(float x, float y) {
            if (!within(surface, x, y)) return OUTSIDE;
            for (View view : header) if (view != null && view.isShown() && within(view, x, y)) return HEAD;
            return LIST;
        }

        private boolean within(View view, float x, float y) {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (tracker != null) { tracker.recycle(); tracker = null; }
            // Taken down with its window (the activity went): gone, and the dock and the window as they were.
            if (!removed) {
                stop();
                state = GONE;
                removed = true;
                if (removal != null) { removeCallbacks(removal); removal = null; }
                handBack();
                morph = false;
                restoreWindow();
                Runnable dismissed = onDismiss;
                onDismiss = null;
                if (dismissed != null) dismissed.run();
            }
        }
    }

    /**
     * A finger on a view under the sheet that pulls it up (Widgets Home's dock): give it every event of the view's touch,
     * call start once the view has recognised its upward swipe, and it does the rest (follow, release, cancel). The sheet
     * follows the finger from where it was at the recognition, so its first frame is where the pull caught it (the dock
     * exactly), with no jump by the slop the swipe needed.
     */
    static final class Pull {
        private VelocityTracker tracker;
        private DragSheet sheet;
        /** The pull's token (DragSheet.pull): the sheet ignores it once anything else has moved the sheet on. */
        private int token;
        private float fromY;

        /** Every event of the touch, from its ACTION_DOWN, each once (the view's onInterceptTouchEvent and onTouchEvent). */
        void track(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (tracker != null) tracker.clear(); else tracker = VelocityTracker.obtain();
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
            fromY = event.getRawY();
            token = pulled == null ? -1 : pulled.pull();
            sheet = token >= 0 ? pulled : null;
        }

        /** After start, each later event of the touch (already given to track). */
        void handle(MotionEvent event) {
            DragSheet pulled = sheet;
            if (pulled == null) return;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    pulled.follow(token, fromY - event.getRawY());
                    break;
                case MotionEvent.ACTION_UP:
                    pulled.follow(token, fromY - event.getRawY());
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
