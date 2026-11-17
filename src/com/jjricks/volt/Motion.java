package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Volt's one motion vocabulary. Arrivals ease out; departures are shorter and accelerate away.
 * Nothing bounces, overshoots or lingers past ~280 ms, and everything collapses to an instant
 * change when Android animations are off (Remove animations / zero scale). The one exception is
 * a sheet a finger drags (DragSheet, the owner's request for the dock's All sheet): it springs
 * open with a small bounce, as a thing let go of would (SheetSpring).
 *
 * Pages are rebuilt or swapped in place, so page changes work from a snapshot of the outgoing
 * frame rather than keeping two live screens. Moving between pages is a fade-through: the old
 * page clears as it starts to leave, then the new one fades in over the rest of its glide, so the
 * two never show through each other. Different content in the same place fades through without
 * travelling; the same content refined (a setting, fuller data) crossfades.
 */
final class Motion {
    private Motion() {}

    static final long EXIT_MS = 150, ENTER_MS = 260, CHANGE_MS = 220, CROSSFADE_MS = 220, PAGE_MS = 280;
    /** Quick start, long gentle settle: everything that arrives or changes size. */
    static final TimeInterpolator STANDARD = new PathInterpolator(.2f, 0f, 0f, 1f);
    /** Even ease for opacity, so a crossfade reads as a fade rather than a blink. */
    static final TimeInterpolator FADE = new PathInterpolator(.4f, 0f, .6f, 1f);
    /** Page travel eases in as well as out, so part of the glide is still visible after the fade-through. */
    private static final TimeInterpolator TRAVEL = new PathInterpolator(.4f, 0f, .2f, 1f);
    private static final TimeInterpolator FADE_OUT = new PathInterpolator(.4f, 0f, 1f, 1f);
    private static final TimeInterpolator FADE_IN = new PathInterpolator(0f, 0f, .2f, 1f);
    /** Share of a page change spent clearing the old page before the new one starts to show. */
    private static final float THROUGH = .35f;
    static final int DIALOG = 0, SHEET = 1, PAGE = 2;
    /** Detach callbacks run while a parent is still detaching its children; cleanup waits a turn. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static boolean enabled() { return Ui.motionEnabled(); }

    // ---- Windows -----------------------------------------------------------------------------

    /**
     * After the content is set and before show(): sheets rise from the bottom, full-screen pages
     * slide in, dialogs fade in and up, and the content's scrollbars stay hidden as it appears.
     */
    static void present(Dialog dialog, int kind) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        OwnDialogs.track(dialog); // leaving Volt over it keeps its page (HubNav.awayClockStarts)
        window.setWindowAnimations(kind == SHEET ? R.style.VoltMotion_Sheet
                : kind == PAGE ? R.style.VoltMotion_Page : R.style.VoltMotion_Dialog);
        dialog.create(); // builds the content now so its scrollers can be quieted before they attach
        quietScrollbars(window.getDecorView());
    }

    /**
     * A sheet ({@code present}ed as SHEET, its content in {@code holder}) whose height glides with the rows a reveal
     * inside it shows or hides, instead of the window resizing in one step. {@code background} is the sheet's own
     * background; call before show(). At rest the window, its background and the keyboard's resizing are as without it.
     */
    static void sheet(Dialog dialog, ScrollView holder, Drawable background) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new Sheet(window, holder, background));
    }

    /** The sheet {@code view} is in, or null. */
    private static Sheet sheetOf(View view) {
        Drawable background = view == null ? null : view.getRootView().getBackground();
        if (background instanceof android.graphics.drawable.InsetDrawable) background = ((android.graphics.drawable.InsetDrawable) background).getDrawable();
        return background instanceof Sheet ? (Sheet) background : null;
    }

    /**
     * The window's background, drawn only over the part of the window the sheet shows. Through a reveal's motion the
     * holder keeps at least the height it had, so the window never shrinks in one step (and a row gliding up is never
     * cut off); the window grows at once when the rows need more. The sheet shows {@code visible} pixels of it, gliding
     * from the height it showed to the height its content needs, anchored where the window is (its bottom, or its
     * centre on a wide screen): its content is moved down by what is not shown, and the rest of the window is left
     * clear. When the motion ends the holder lets go and the window wraps its content again, showing all of it.
     */
    private static final class Sheet extends Drawable {
        final Window window;
        final ScrollView holder;
        final Drawable inner;
        /** The height shown while it glides, or -1 at rest. */
        float visible = -1;
        ValueAnimator animator;
        ViewTreeObserver.OnPreDrawListener follow;
        private int top, bottom;
        Sheet(Window window, ScrollView holder, Drawable inner) {
            this.window = window; this.holder = holder; this.inner = inner;
            inner.setCallback(null);
        }
        /** The window's content: the part that moves. */
        private View content() {
            View decor = window.peekDecorView();
            View at = holder;
            while (at != null && at.getParent() != decor) at = at.getParent() instanceof View ? (View) at.getParent() : null;
            return at;
        }
        /** Before a reveal inside it changes the rows: holds the height it shows and starts gliding from it once they are laid out. */
        void begin() {
            View decor = window.peekDecorView();
            if (decor == null || !decor.isLaidOut() || holder.getHeight() <= 0) return;
            float from = visible >= 0 ? visible : decor.getHeight();
            if (animator != null) { ValueAnimator was = animator; animator = null; was.cancel(); }
            // The rest of the window (title, padding) is the same before and after: only the holder changes.
            int chrome = decor.getHeight() - holder.getHeight();
            holder.setMinimumHeight(holder.getHeight());
            visible = from;
            apply();
            beforeNextDraw(decor, () -> start(from, chrome), this::end);
        }
        private void start(float from, int chrome) {
            View decor = window.peekDecorView();
            if (decor == null || visible < 0) return;
            View rows = holder.getChildCount() == 0 ? null : holder.getChildAt(0);
            // What the content needs (its rows' new height, read before the motion puts them back where they start),
            // and no more than the window it is in now (a long sheet stops at the screen and scrolls).
            int need = chrome + holder.getPaddingTop() + holder.getPaddingBottom() + (rows == null ? 0 : rows.getHeight());
            float to = Math.min(need, decor.getHeight());
            if (Math.abs(to - from) < 1) { end(); return; }
            ValueAnimator glide = ValueAnimator.ofFloat(from, to);
            glide.setDuration(CHANGE_MS);
            glide.setInterpolator(STANDARD);
            glide.addUpdateListener(a -> { if (animator == a) { visible = (float) a.getAnimatedValue(); apply(); } });
            glide.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { if (animator == a) end(); }
            });
            animator = glide;
            if (follow == null) {
                // The window can change size a frame after the content asks: every frame places the content for the window it has.
                follow = () -> { apply(); return true; };
                decor.getViewTreeObserver().addOnPreDrawListener(follow);
            }
            glide.start();
        }
        /** At rest at once: the window wraps its content again and shows all of it. */
        void end() {
            ValueAnimator was = animator;
            animator = null;
            if (was != null) was.cancel();
            View decor = window.peekDecorView();
            if (follow != null && decor != null) decor.getViewTreeObserver().removeOnPreDrawListener(follow);
            follow = null;
            boolean held = holder.getMinimumHeight() != 0;
            if (held) holder.setMinimumHeight(0);
            visible = -1;
            apply();
        }
        /** Places the content and the background for the window's height now. */
        private void apply() {
            View decor = window.peekDecorView();
            if (decor == null) return;
            int height = decor.getHeight();
            int hidden = visible < 0 ? 0 : Math.max(0, Math.round(height - Math.min(visible, height)));
            boolean centred = (window.getAttributes().gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL;
            int top = centred ? hidden / 2 : hidden, bottom = centred ? hidden - hidden / 2 : 0;
            View content = content();
            if (content != null && content.getTranslationY() != top) content.setTranslationY(top);
            if (top != this.top || bottom != this.bottom) {
                this.top = top; this.bottom = bottom;
                onBoundsChange(getBounds());
                invalidateSelf();
                decor.invalidateOutline();
            }
        }
        @Override protected void onBoundsChange(android.graphics.Rect bounds) {
            inner.setBounds(bounds.left, bounds.top + top, bounds.right, Math.max(bounds.top + top, bounds.bottom - bottom));
        }
        @Override public void draw(Canvas canvas) { inner.draw(canvas); }
        @Override public void getOutline(android.graphics.Outline outline) { inner.getOutline(outline); }
        @Override public boolean getPadding(android.graphics.Rect padding) { return inner.getPadding(padding); }
        @Override public void setAlpha(int alpha) { inner.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { inner.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---- Snapshots ---------------------------------------------------------------------------

    /** The view's visible frame (a ScrollView at its current scroll), or null when it cannot be drawn. */
    static Bitmap capture(View view) { return capture(view, false); }

    /**
     * With {@code backdrop}, whatever shows behind the view (its nearest background) is drawn
     * first, so a crossfading snapshot hides the new content instead of letting it show through.
     */
    static Bitmap capture(View view, boolean backdrop) {
        if (view == null || !view.isLaidOut() || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (backdrop) drawBackdrop(view, canvas);
            canvas.translate(-view.getScrollX(), -view.getScrollY());
            view.draw(canvas);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null; // A missing snapshot only means this change is instant.
        }
    }

    private static void drawBackdrop(View view, Canvas canvas) {
        int[] at = new int[2], from = new int[2];
        view.getLocationInWindow(at);
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            Drawable background = ((View) parent).getBackground();
            if (background == null) continue;
            ((View) parent).getLocationInWindow(from);
            int saved = canvas.save();
            canvas.translate(from[0] - at[0], from[1] - at[1]);
            background.draw(canvas);
            canvas.restoreToCount(saved);
            return;
        }
    }

    // ---- Page changes ------------------------------------------------------------------------

    /**
     * How far pages travel: enough to read as direction, never a full-width swipe. Negative in a
     * right-to-left layout, where the next tab or page sits to the left, so content still arrives
     * from the side it lies on.
     */
    private static float travel(View view, int width) {
        float distance = Math.min(width * .16f, view.getResources().getDisplayMetrics().density * 48);
        return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -distance : distance;
    }

    /**
     * Runs one change from {@code cover} (a snapshot over the live content) to {@code incoming}.
     * {@code through} is a fade-through: the cover clears in the first part while it starts to
     * leave (toward {@code -shift} when {@code coverTravels}), then the incoming views fade in as
     * they finish gliding from {@code shift}. Otherwise it is a straight crossfade. {@code end}
     * runs exactly once, however the animation stops.
     */
    private static ValueAnimator drive(View cover, boolean coverTravels, boolean through, float shift, View[] incoming, Runnable end) {
        final int[] layers = new int[incoming.length];
        for (int i = 0; i < incoming.length; i++) {
            layers[i] = incoming[i].getLayerType();
            if (through) incoming[i].setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(through ? PAGE_MS : CROSSFADE_MS);
        animator.setInterpolator(new LinearInterpolator()); // each property applies its own curve
        ValueAnimator.AnimatorUpdateListener frame = animation -> {
            float t = animation.getAnimatedFraction();
            if (!through) { cover.setAlpha(1 - FADE.getInterpolation(t)); return; }
            float glide = TRAVEL.getInterpolation(t);
            cover.setAlpha(1 - FADE_OUT.getInterpolation(Math.min(1f, t / THROUGH)));
            if (coverTravels) cover.setTranslationX(-shift * glide);
            float shown = FADE_IN.getInterpolation(Math.max(0f, (t - THROUGH) / (1 - THROUGH)));
            for (View view : incoming) { view.setAlpha(shown); view.setTranslationX(shift * (1 - glide)); }
        };
        animator.addUpdateListener(frame);
        frame.onAnimationUpdate(animator); // the first frame already shows the starting state
        animator.addListener(new AnimatorListenerAdapter() {
            boolean done;
            void finish() {
                if (done) return; done = true;
                for (int i = 0; i < incoming.length; i++) {
                    incoming[i].setAlpha(1); incoming[i].setTranslationX(0);
                    if (incoming[i].getLayerType() != layers[i]) incoming[i].setLayerType(layers[i], null);
                }
                end.run();
            }
            @Override public void onAnimationEnd(Animator animation) { finish(); }
            @Override public void onAnimationCancel(Animator animation) { finish(); }
        });
        return animator;
    }

    /**
     * After a full rebuild of the window ({@code previous} is the old window): in place, the old
     * window crossfades into the new one. Between tabs ({@code direction} non-zero), the old page
     * clears and {@code incoming} (the new header and page) arrive from that side.
     */
    static void crossfade(ViewGroup root, Bitmap previous, int direction, View... incoming) {
        if (previous == null) return;
        if (!enabled() || root == null || root.getWidth() > 0 && root.getWidth() != previous.getWidth()) {
            previous.recycle(); return;
        }
        ImageView cover = new ImageView(root.getContext());
        cover.setImageBitmap(previous);
        cover.layout(0, 0, previous.getWidth(), previous.getHeight());
        root.getOverlay().add(cover);
        ArrayList<View> moving = new ArrayList<>();
        if (direction != 0) for (View view : incoming) if (view != null) moving.add(view);
        // The cover holds the old tab bar too, so it fades without travelling; the bars match.
        float shift = moving.isEmpty() ? 0 : Math.signum(direction) * travel(root, previous.getWidth());
        ValueAnimator animator = drive(cover, false, shift != 0, shift, moving.toArray(new View[0]), () -> {
            root.getOverlay().remove(cover); cover.setImageDrawable(null); previous.recycle();
        });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { view.removeOnAttachStateChangeListener(this); MAIN.post(animator::cancel); }
        });
        animator.start();
    }

    private static final WeakHashMap<View, Runnable> running = new WeakHashMap<>();

    /**
     * A change inside one scrolling page (sub-menu, section, sub-page): the outgoing frame leaves
     * toward {@code -direction} while the new content arrives from {@code direction}. Direction 0
     * crossfades in place, for the same content refined. {@code change} runs immediately and
     * exactly once.
     */
    static void page(View pageView, int direction, Runnable change) { swap(pageView, direction, direction != 0, change); }

    /** Different content in the same place (another ride in the detail pane): a fade-through without travel. */
    static void replace(View pageView, Runnable change) { swap(pageView, 0, true, change); }

    private static void swap(View pageView, int direction, boolean through, Runnable change) {
        Runnable previous = running.remove(pageView);
        if (previous != null) previous.run();
        Bitmap snapshot = enabled() ? capture(pageView, !through) : null;
        change.run();
        if (snapshot == null) return;
        ViewGroup parent = pageView.getParent() instanceof ViewGroup ? (ViewGroup) pageView.getParent() : null;
        if (parent == null) { snapshot.recycle(); return; }
        ImageView cover = new ImageView(pageView.getContext());
        cover.setImageBitmap(snapshot);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final boolean sibling = parent instanceof FrameLayout;
        final ViewGroup decor = sibling ? null : (ViewGroup) pageView.getRootView();
        if (sibling) {
            // Directly above the page and below anything floating over it (the tab bar).
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(snapshot.getWidth(), snapshot.getHeight());
            // Placed from the left edge in either layout direction (a start gravity is the right edge in RTL).
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.leftMargin = pageView.getLeft() - parent.getPaddingLeft();
            params.topMargin = pageView.getTop() - parent.getPaddingTop();
            parent.addView(cover, parent.indexOfChild(pageView) + 1, params);
        } else {
            int[] at = new int[2]; pageView.getLocationInWindow(at);
            int[] origin = new int[2]; decor.getLocationInWindow(origin);
            cover.layout(at[0] - origin[0], at[1] - origin[1], at[0] - origin[0] + snapshot.getWidth(), at[1] - origin[1] + snapshot.getHeight());
            decor.getOverlay().add(cover);
        }
        float shift = Math.signum(direction) * travel(pageView, snapshot.getWidth());
        final View.OnAttachStateChangeListener[] detach = new View.OnAttachStateChangeListener[1];
        ValueAnimator animator = drive(cover, true, through, shift, new View[] {pageView}, () -> {
            running.remove(pageView);
            pageView.removeOnAttachStateChangeListener(detach[0]);
            if (sibling) parent.removeView(cover); else decor.getOverlay().remove(cover);
            cover.setImageDrawable(null); snapshot.recycle();
        });
        detach[0] = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            // The parent is still detaching its children here; removing the cover from it
            // now would corrupt that pass, so the cleanup waits for the next loop.
            @Override public void onViewDetachedFromWindow(View view) { MAIN.post(animator::cancel); }
        };
        running.put(pageView, animator::cancel);
        pageView.addOnAttachStateChangeListener(detach[0]);
        animator.start();
    }

    // ---- Scrolling -----------------------------------------------------------------------------

    /**
     * Runs {@code action} just before the view's next frame, once. If the view detaches first,
     * {@code abandoned} (when given) runs instead and nothing stays registered on the window.
     */
    static void beforeNextDraw(View view, Runnable action, Runnable abandoned) {
        final class Once implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            ViewTreeObserver observer = view.getViewTreeObserver();
            void remove() {
                // A listener added before attach moves to the window's observer when the view attaches.
                (observer.isAlive() ? observer : view.getViewTreeObserver()).removeOnPreDrawListener(this);
                view.removeOnAttachStateChangeListener(this);
            }
            @Override public boolean onPreDraw() { remove(); action.run(); return true; }
            @Override public void onViewAttachedToWindow(View attached) { observer = attached.getViewTreeObserver(); }
            @Override public void onViewDetachedFromWindow(View detached) { remove(); if (abandoned != null) abandoned.run(); }
        }
        Once once = new Once();
        view.getViewTreeObserver().addOnPreDrawListener(once);
        view.addOnAttachStateChangeListener(once);
    }

    /** Applies a scroll position in the first frame that shows the new content, without a scrollbar flash. */
    static void scrollBeforeDraw(ScrollView scroll, int y) {
        if (scroll == null) return;
        beforeNextDraw(scroll, () -> {
            boolean bars = scroll.isVerticalScrollBarEnabled();
            scroll.setVerticalScrollBarEnabled(false);
            scroll.scrollTo(0, Math.max(0, y));
            scroll.setVerticalScrollBarEnabled(bars);
        }, null);
    }

    /**
     * Android flashes the scrollbar of every scroller as it attaches to a visible window, which
     * reads as a flicker after a rebuild or as a sheet opens. Call before {@code root} attaches:
     * its scrollers keep their bars hidden until the user scrolls.
     */
    static void quietScrollbars(View root) {
        if (root == null) return;
        if (root.isVerticalScrollBarEnabled() || root.isHorizontalScrollBarEnabled()) {
            final View view = root;
            final boolean vertical = view.isVerticalScrollBarEnabled(), horizontal = view.isHorizontalScrollBarEnabled();
            Runnable restore = () -> { view.setVerticalScrollBarEnabled(vertical); view.setHorizontalScrollBarEnabled(horizontal); };
            view.setVerticalScrollBarEnabled(false); view.setHorizontalScrollBarEnabled(false);
            beforeNextDraw(view, () -> view.post(restore), restore);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) quietScrollbars(group.getChildAt(i));
        }
    }

    // ---- Layout changes ------------------------------------------------------------------------

    /**
     * Call just before changing visibility or order inside {@code root}: changes glide instead of
     * jumping. The contents of {@code instant} views change in place (the views themselves still
     * glide), for content that is rebuilt rather than revealed.
     */
    static void animateChanges(ViewGroup root, View... instant) {
        if (!enabled() || root == null || !root.isLaidOut()) return;
        TransitionManager.beginDelayedTransition(root, changes(false, instant));
    }

    /**
     * {@link #animateChanges}' motion: what goes fades out, what moves glides, what comes fades in. Veiled (reveal's),
     * what goes is GONE from the start and only drawn fading ({@link Veil}).
     */
    private static TransitionSet changes(boolean veiled, View... instant) { return changes(veiled, true, instant); }

    /** changes(); without {@code glides}, what moves does not glide: it takes its place at once and fades in there ({@link Refade}). */
    private static TransitionSet changes(boolean veiled, boolean glides, View... instant) {
        TransitionSet set = new TransitionSet();
        for (View view : instant) if (view != null) set.excludeChildren(view, true);
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        set.addTransition((veiled ? new Veil(Visibility.MODE_OUT) : new Fade(Fade.OUT)).setDuration(EXIT_MS));
        set.addTransition(glides ? new ChangeBounds().setDuration(CHANGE_MS) : new Refade().setDuration(CHANGE_MS));
        set.addTransition((veiled ? new Veil(Visibility.MODE_IN) : new Fade(Fade.IN)).setDuration(CHANGE_MS));
        set.setInterpolator(STANDARD);
        return set;
    }

    /**
     * {@link #takeTurns}' stand-in for ChangeBounds: a view that stays shown but sits somewhere else after the change (the
     * page's foot below sections of different lengths) is in its new place from the first frame, as without motion, and
     * fades in there rather than gliding across the page or popping. Turned back mid-fade, it fades on from the opacity
     * it was drawn at. The root itself (the page, which only changes length) never fades.
     */
    private static final class Refade extends Transition {
        private static final String TOP = "volt:refade:top", ALPHA = "volt:refade:alpha", SHOWN = "volt:refade:shown";
        private void capture(TransitionValues values) {
            values.values.put(TOP, values.view.getTop());
            values.values.put(SHOWN, values.view.getVisibility() == View.VISIBLE);
            values.values.put(ALPHA, values.view.getTransitionAlpha());
        }
        @Override public void captureStartValues(TransitionValues values) { capture(values); }
        @Override public void captureEndValues(TransitionValues values) { capture(values); }
        @Override public Animator createAnimator(ViewGroup root, TransitionValues start, TransitionValues end) {
            // Views that come or go are Veil's.
            if (start == null || end == null || end.view == root || !Boolean.TRUE.equals(start.values.get(SHOWN)) || !Boolean.TRUE.equals(end.values.get(SHOWN))) return null;
            if (start.values.get(TOP).equals(end.values.get(TOP))) return null;
            View view = end.view;
            float from = (Float) start.values.get(ALPHA);
            ValueAnimator animator = ValueAnimator.ofFloat(from < 1 ? from : 0, 1);
            animator.addUpdateListener(a -> view.setTransitionAlpha((float) a.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                // Ended, or cut short (a later change starts its own fade from the opacity captured before this).
                @Override public void onAnimationEnd(Animator a) { view.setTransitionAlpha(1); }
            });
            return animator;
        }
    }

    /**
     * {@link #reveal}'s fade. Android's Fade keeps a departing view VISIBLE for the transition, so for its whole fade it
     * still takes taps, clicks, long presses, focus, keys and accessibility actions, and reads as shown. Here it stays
     * GONE from the moment it is asked to go, as it was before any motion: it takes none of those, focus leaves it as
     * Android moves focus from a GONE view, and what is under it gets the tap. Only its picture fades where it was, a
     * {@link Ghost} over the page. Shown again mid-fade, it is VISIBLE (and takes input) at once and fades in from the
     * ghost's opacity: a reversal turns back from what is on screen.
     */
    private static final class Veil extends Visibility {
        /** The opacity a view is drawn at: its ghost's while it goes, else its transition alpha. */
        private static final String ALPHA = "volt:veil:alpha";
        Veil(int mode) { setMode(mode); }
        @Override public void captureStartValues(TransitionValues values) {
            super.captureStartValues(values);
            Ghost ghost = ghosts.get(values.view);
            values.values.put(ALPHA, ghost != null ? ghost.alpha : values.view.getTransitionAlpha());
        }
        private static float from(TransitionValues values, float otherwise) {
            Object alpha = values == null ? null : values.values.get(ALPHA);
            return alpha instanceof Float ? (Float) alpha : otherwise;
        }
        @Override public Animator onAppear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
            // A gone view that was not fading was captured at 1: it fades in from nothing.
            float from = from(start, 0);
            return fade(view, from >= 1 ? 0 : from, 1);
        }
        @Override public Animator onDisappear(ViewGroup root, TransitionValues start, int startVisibility, TransitionValues end, int endVisibility) {
            // Visibility's own check, which this replaces: only the departing half of the set fades views out.
            if ((getMode() & MODE_OUT) != MODE_OUT) return null;
            View view = start == null ? null : start.view;
            // Removed (Android draws it in the root's overlay, where it takes no input) or INVISIBLE: Android's way.
            if (view == null || end == null || end.view != view || endVisibility != View.GONE || !(view.getParent() instanceof ViewGroup) || !view.isAttachedToWindow())
                return super.onDisappear(root, start, startVisibility, end, endVisibility);
            float from = from(start, 1);
            // The view itself stays GONE at full transition alpha; the ghost carries the fade.
            view.setTransitionAlpha(1);
            Ghost ghost = new Ghost(view, root, from);
            ValueAnimator animator = ValueAnimator.ofFloat(from, 0);
            animator.addUpdateListener(a -> { ghost.alpha = (float) a.getAnimatedValue(); ghost.invalidateSelf(); });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { ghost.drop(); }
            });
            ghost.animator = animator;
            ghost.show();
            return animator;
        }
        @Override public Animator onDisappear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
            return fade(view, from(start, 1), 0);
        }
        private static Animator fade(View view, float from, float to) {
            view.setTransitionAlpha(from);
            ValueAnimator animator = ValueAnimator.ofFloat(from, to);
            animator.addUpdateListener(a -> view.setTransitionAlpha((float) a.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                boolean canceled;
                // Turned back: the next motion starts from where this one is (its own start was captured from it).
                @Override public void onAnimationCancel(Animator a) { canceled = true; }
                @Override public void onAnimationEnd(Animator a) { if (!canceled) view.setTransitionAlpha(1); }
            });
            return animator;
        }
    }

    /** Views going through {@link #reveal}'s fade, each drawn by its ghost. */
    private static final WeakHashMap<View, Ghost> ghosts = new WeakHashMap<>();

    /**
     * The picture of a GONE view fading where it was: a drawable in its reveal root's overlay that draws the view each
     * frame at its last place, under its parent's current position (a gliding card carries it), at the fade's opacity.
     * An overlay takes no input.
     */
    private static final class Ghost extends Drawable {
        final View view;
        final ViewGroup root;
        float alpha;
        Animator animator;
        private final int[] at = new int[2], base = new int[2];
        Ghost(View view, ViewGroup root, float alpha) { this.view = view; this.root = root; this.alpha = alpha; }
        void show() {
            Ghost was = ghosts.put(view, this);
            if (was != null) was.end();
            setBounds(0, 0, Math.max(1, root.getWidth()), Math.max(1, root.getHeight()));
            root.getOverlay().add(this);
        }
        /** Stops the fade (its end drops the ghost). */
        void end() { if (animator != null) animator.cancel(); drop(); }
        void drop() {
            if (ghosts.get(view) == this) ghosts.remove(view);
            root.getOverlay().remove(this);
        }
        @Override public void draw(Canvas canvas) {
            float drawn = alpha * view.getAlpha();
            if (drawn <= 0 || !(view.getParent() instanceof View) || view.getWidth() <= 0 || view.getHeight() <= 0) return;
            View parent = (View) view.getParent();
            parent.getLocationInWindow(at);
            root.getLocationInWindow(base);
            int save = canvas.save();
            // The overlay draws in the root's content coordinates.
            canvas.translate(at[0] - base[0] + root.getScrollX() - parent.getScrollX() + view.getLeft(),
                    at[1] - base[1] + root.getScrollY() - parent.getScrollY() + view.getTop());
            if (!view.getMatrix().isIdentity()) canvas.concat(view.getMatrix());
            // A view that scrolls its own content (a selector wider than the screen) draws it at its scroll, as its
            // parent draws it: View.draw leaves that to the parent, so the ghost applies it, and its layer follows.
            int sx = view.getScrollX(), sy = view.getScrollY();
            canvas.translate(-sx, -sy);
            canvas.saveLayerAlpha(sx, sy, sx + view.getWidth(), sy + view.getHeight(), Math.round(255 * Math.min(1, drawn)));
            view.draw(canvas);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The opacity a leaving view is drawn at while it fades out ({@link #reveal}), or -1 when it is not leaving. */
    static float leaving(View view) {
        Ghost ghost = view == null ? null : ghosts.get(view);
        return ghost == null ? -1 : ghost.alpha * view.getAlpha();
    }

    /** A press or long press under way in a view that goes ends there: it never lands as a click or long click. */
    private static void release(View view) {
        if (view.isPressed()) view.setPressed(false);
        view.cancelLongPress();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) release(((ViewGroup) view).getChildAt(i));
    }

    /** One {@link #reveal} motion on a root: every reveal on that root before its first frame joins it. */
    private static final class Glide {
        final WeakReference<ViewGroup> root;
        /** It reached its first frame and played; swallowed, when that frame came and it had not. */
        boolean played, swallowed, ended;
        /** What runs once it ends (or is turned back or swallowed): a hold it put on a page is let go. */
        final ArrayList<Runnable> after = new ArrayList<>();
        Glide(ViewGroup root) { this.root = new WeakReference<>(root); }
    }

    /** What reveal() last asked of each view. */
    private static final WeakHashMap<View, Boolean> revealed = new WeakHashMap<>();
    /** Roots whose reveal has not reached its first frame yet: a second change joins that one. */
    private static final WeakHashMap<View, Glide> revealing = new WeakHashMap<>();
    /** The motion each view last went through, until it ends. */
    private static final WeakHashMap<View, Glide> moving = new WeakHashMap<>();

    /**
     * Shows or hides {@code view} (VISIBLE or GONE) inside {@code root}: when that changes on a laid-out page, it fades
     * in or out while what sits around it glides to its new place ({@link #animateChanges}, with {@code instant} as
     * there). A call that leaves it as it is does nothing, so a live refresh that keeps it shown or hidden never moves
     * anything. A reversal mid-motion turns back from what is on screen. A null root is an instant change, and it, or a
     * change on another root, first ends the motion the view is still in (a departure would otherwise hide it when it
     * ends, over the change). When the page gets shorter at the bottom of its scroll, the scroll's content glides to
     * where the scroll is clamped rather than jumping there. Once a view goes through here, show and hide it only here.
     * A view that goes is GONE at once, as without motion, and only its picture fades ({@link Veil}): from the moment
     * it starts leaving it takes no touch, click, long press, key, focus or accessibility action (a press under way in
     * it ends), what is under it gets the tap, and it reads as gone. Shown again, even mid-fade, it takes them at once.
     * Focus moves as Android moves it from any GONE view; accessibility flags are untouched. Returns whether it changed.
     */
    static boolean reveal(ViewGroup root, View view, boolean shown, View... instant) { return reveal(root, view, shown, true, instant); }

    /**
     * Views that take turns in one place, one shown at a time (a page's sections behind its tabs): {@code shown} is shown
     * and every other one of {@code views} hidden, in their order, each through {@link #reveal} on {@code root} with the
     * views' own contents changing in place. They crossfade where they are, the one that goes fading out over EXIT_MS as
     * the one that comes fades in over CHANGE_MS, and nothing slides: what sits around them takes its new place at once
     * and fades in there ({@link Refade}), and the page takes its new length at once. Everything else is reveal's: a
     * view that goes is GONE at once and takes no input, quick changes back and forth end as last asked and turn back
     * from what is on screen, a null root or animations off is an instant change, and asking for the one already shown
     * changes nothing. Call with the same {@code views} every time; a motion on the root not yet drawn is joined.
     */
    static void takeTurns(ViewGroup root, View shown, View... views) {
        for (View view : views) if (view != null) reveal(root, view, view == shown, false, views);
    }

    private static boolean reveal(ViewGroup root, View view, boolean shown, boolean glides, View... instant) {
        if (view == null) return false;
        Boolean asked = revealed.get(view);
        if ((asked != null ? asked : view.getVisibility() == View.VISIBLE) == shown) return false;
        revealed.put(view, shown);
        boolean moves = root != null && enabled() && root.isLaidOut();
        Glide running = moving.remove(view);
        ViewGroup was = running == null || running.ended ? null : running.root.get();
        // Ending it jumps what it moves to where it was going; only a change without a motion of its own does that.
        if (was != null && (!moves || was != root)) {
            TransitionManager.endTransitions(was);
            Sheet sheet = sheetOf(was);
            if (sheet != null) sheet.end();
        }
        if (moves) {
            // A reversal mid-motion starts here too: Android captures the running motion where it is on screen and
            // turns its views back from there.
            Glide glide = revealing.get(root);
            if (glide == null) glide = glide(root, view, glides, instant);
            else holdScroll(root, view);
            moving.put(view, glide);
        }
        // Shown mid-fade: the motion just begun captured the ghost's opacity to turn back from; the view draws itself.
        Ghost ghost = ghosts.get(view);
        if (ghost != null) ghost.end();
        if (!shown) release(view);
        view.setVisibility(shown ? View.VISIBLE : View.GONE);
        return true;
    }

    /**
     * Whether {@code view}, or a view it is in, is arriving through a {@link #reveal}'s motion: asked to show, its motion
     * not yet ended. A control that comes in where something else was (Home's lock slider where Connect was) can refuse
     * a touch that starts meanwhile, which was aimed at what it replaces. Main thread.
     */
    static boolean arriving(View view) {
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            Glide glide = moving.get(at);
            if (glide != null && !glide.ended && !glide.swallowed && Boolean.TRUE.equals(revealed.get(at))) return true;
        }
        return false;
    }

    /**
     * A control that can arrive where another one was, or glide into its place (Home's lock card: Connect or Cancel and
     * the offline line where the slider was; a page's connection line where its title was): a touch that starts while it
     * is arriving, or while the motion it sits in is still moving it ({@link #changing}), does nothing and is used up
     * until it lifts, as the rider aimed at what was there. Once the motion ends, is turned back to rest, swallowed or
     * cut short by an instant change, and always with animations off, touches reach it as before, with nothing added.
     * Keyboard and accessibility actions are not touched. It replaces any touch listener the view had.
     */
    static void ignoreTouchesWhileChanging(View view) {
        boolean[] ignoring = {false};
        view.setOnTouchListener((touched, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) ignoring[0] = changing(touched);
            if (!ignoring[0]) return false;
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) ignoring[0] = false;
            return true;
        });
    }

    /** It is arriving, or it sits in (or is) the root of a reveal's motion that has not ended or been swallowed. */
    static boolean changing(View view) {
        if (arriving(view)) return true;
        for (Glide glide : moving.values()) {
            if (glide == null || glide.ended || glide.swallowed) continue;
            ViewGroup root = glide.root.get();
            for (View at = view; root != null && at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null)
                if (at == root) return true;
        }
        return false;
    }

    /**
     * {@link #reveal} for a view in a fixed header above a page's scroll ({@code scroll}, in {@code frame}, the rest of
     * {@code root} under the header): a page's connection line. What sits around it in the header glides, and the page
     * under the header glides down or up with it as one piece; the page's own content is left to the page's own reveals
     * (frame's insides are instant here), so a banner shown on the page in the same refresh moves in its own motion, with
     * the same timing and curve, and nothing is moved twice. As it arrives the page keeps the height it had until the
     * motion ends, so the page's lowest rows slide down off the screen instead of vanishing first; as it goes, a page
     * scrolled to its end (its scroll clamped as the page grows) keeps its content where it was while the top glides
     * up. A null root, animations off or a page not laid out is an instant change. Returns whether it changed.
     */
    static boolean revealAbove(ViewGroup root, View view, boolean shown, ViewGroup frame, ScrollView scroll) {
        if (view == null) return false;
        Boolean asked = revealed.get(view);
        if ((asked != null ? asked : view.getVisibility() == View.VISIBLE) == shown) return false;
        boolean glides = root != null && enabled() && root.isLaidOut() && frame != null && scroll != null && scroll.getParent() == frame;
        if (!glides) {
            if (scroll != null) letGo(scroll, null);
            return reveal(null, view, shown);
        }
        // Before the reveal's own: the page's reveals in this refresh then share this clamp (one hold per page).
        holdScrollOf(root, scroll);
        Object token = shown ? keepHeight(scroll) : null;
        if (!shown) letGo(scroll, null);
        boolean changed = reveal(root, view, shown, frame);
        Glide glide = moving.get(view);
        if (token != null) {
            if (glide != null) glide.after.add(() -> letGo(scroll, token));
            else letGo(scroll, token);
        }
        return changed;
    }

    /** Scrolls whose height is held (their own height until a motion ends), each with the token of the hold. */
    private static final WeakHashMap<ScrollView, Object> heights = new WeakHashMap<>();

    /** Holds the scroll at the height it has (its parent may get shorter meanwhile): a token for letGo. */
    private static Object keepHeight(ScrollView scroll) {
        Object token = new Object();
        if (!heights.containsKey(scroll)) {
            ViewGroup.LayoutParams params = scroll.getLayoutParams();
            if (params == null || params.height != ViewGroup.LayoutParams.MATCH_PARENT || scroll.getHeight() <= 0) return null;
            params.height = scroll.getHeight();
            scroll.setLayoutParams(params);
        }
        heights.put(scroll, token);
        return token;
    }

    /** Lets the held height go (any hold when token is null): the scroll fills its parent again. */
    private static void letGo(ScrollView scroll, Object token) {
        Object held = heights.get(scroll);
        if (held == null || token != null && token != held) return;
        heights.remove(scroll);
        ViewGroup.LayoutParams params = scroll.getLayoutParams();
        if (params == null) return;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        scroll.setLayoutParams(params);
    }

    private static Glide glide(ViewGroup root, View view, boolean glides, View... instant) {
        Glide glide = new Glide(root);
        revealing.put(root, glide);
        Runnable abandoned = () -> { revealing.remove(root); glide.swallowed = true; };
        holdScroll(root, view);
        // In a sheet, its height glides with the rows (before the motion's own first-frame listener, which puts the
        // views it moves back where they start).
        Sheet sheet = sheetOf(root);
        if (sheet != null) sheet.begin();
        TransitionSet set = changes(true, glides, instant);
        set.addListener(new TransitionListenerAdapter() {
            @Override public void onTransitionStart(Transition transition) { glide.played = true; }
            @Override public void onTransitionEnd(Transition transition) { settle(glide); }
            @Override public void onTransitionCancel(Transition transition) { settle(glide); }
        });
        TransitionManager.beginDelayedTransition(root, set);
        // After Android's own first-frame listener: later reveals on the root start a new motion, and one that did not
        // play (the root had another motion pending this frame) leaves its views to the next motion that ends.
        beforeNextDraw(root, () -> {
            revealing.remove(root);
            if (!glide.played) glide.swallowed = true;
        }, abandoned);
        return glide;
    }

    /**
     * Once a reveal's motion ends (or never played), each view it last moved is left as last asked. A departure it
     * ended early, or one that outlived a change it never saw, would otherwise leave the view hidden while it is
     * asked to show, until the next change.
     */
    private static void settle(Glide glide) {
        if (glide.ended) return;
        glide.ended = true;
        for (Runnable action : glide.after) action.run();
        glide.after.clear();
        ArrayList<View> settled = new ArrayList<>();
        for (Map.Entry<View, Glide> entry : moving.entrySet())
            if (entry.getValue() == glide || entry.getValue().swallowed) settled.add(entry.getKey());
        for (View view : settled) {
            if (view == null) continue;
            moving.remove(view);
            Ghost ghost = ghosts.get(view);
            if (ghost != null) ghost.end();
            if (view.getTransitionAlpha() != 1) view.setTransitionAlpha(1);
            Boolean asked = revealed.get(view);
            if (asked != null && (view.getVisibility() == View.VISIBLE) != asked) view.setVisibility(asked ? View.VISIBLE : View.GONE);
        }
    }

    /** The offset a scroll's content carries while it glides to where its scroll was clamped. */
    private static final class Hold { ValueAnimator animator; float offset; boolean pending; }
    private static final WeakHashMap<View, Hold> holds = new WeakHashMap<>();

    /**
     * A page that gets shorter while scrolled to its end has its scroll clamped in the same layout, before the first
     * frame of the motion: everything on screen would jump down by the clamp and then glide. Instead the scroll's
     * content starts that frame offset by the clamp and glides to it with the rest of the motion.
     */
    private static void holdScroll(ViewGroup root, View view) {
        // The scroll the view is in: around the root, or inside it (a page whose scrolling part is one of its children).
        holdScrollOf(root, scrollOf(view));
    }

    /**
     * holdScroll for {@code scroll} itself, drawn under {@code root}. The clamp is what the content got shorter by plus
     * what the scroll itself got taller by (a header line going above it): either way the end of the page stays put.
     */
    private static void holdScrollOf(View root, ScrollView scroll) {
        if (scroll == null || scroll.getChildCount() == 0) return;
        ScrollView page = scroll;
        View content = scroll.getChildAt(0);
        Hold hold = holds.get(content);
        if (hold == null) holds.put(content, hold = new Hold());
        // Reveals on two roots of one page in the same frame share one clamp.
        if (hold.pending) return;
        hold.pending = true;
        Hold held = hold;
        int scrolled = scroll.getScrollY(), tall = content.getHeight(), seen = scroll.getHeight();
        beforeNextDraw(root, () -> {
            held.pending = false;
            int clamped = Math.min(scrolled - page.getScrollY(), tall - content.getHeight() + page.getHeight() - seen);
            if (clamped <= 0 || content.getParent() != page || !enabled()) return;
            if (held.animator != null) held.animator.cancel();
            ValueAnimator animator = ValueAnimator.ofFloat(held.offset - clamped, 0);
            animator.setDuration(CHANGE_MS);
            animator.setInterpolator(STANDARD);
            animator.addUpdateListener(a -> {
                float offset = (float) a.getAnimatedValue();
                content.setTranslationY(content.getTranslationY() - held.offset + offset);
                held.offset = offset;
            });
            animator.addListener(new AnimatorListenerAdapter() {
                boolean canceled;
                @Override public void onAnimationCancel(Animator a) { canceled = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (canceled) return;
                    content.setTranslationY(content.getTranslationY() - held.offset);
                    held.offset = 0;
                    held.animator = null;
                }
            });
            held.animator = animator;
            // Starting applies the first offset before this frame draws.
            animator.start();
        }, () -> held.pending = false);
    }

    private static ScrollView scrollOf(View view) {
        for (ViewParent at = view == null ? null : view.getParent(); at instanceof View; at = at.getParent())
            if (at instanceof ScrollView) return (ScrollView) at;
        return null;
    }

    /**
     * Brings the whole of {@code view} into its scroll's view where the change being made puts it: the place is read
     * from that change's layout, before a motion's first frame draws its views where they start, and the scroll glides
     * there with the motion (at once with animations off). Touching or scrolling the page meanwhile stops it there.
     * Call with the change, before or after it, in the same refresh.
     */
    static void bringIntoView(View view) {
        ScrollView scroll = scrollOf(view);
        if (scroll == null) return;
        final class Once implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
            final ViewTreeObserver observer = scroll.getViewTreeObserver();
            boolean done;
            void remove() {
                ViewTreeObserver now = observer.isAlive() ? observer : scroll.getViewTreeObserver();
                now.removeOnGlobalLayoutListener(this);
                now.removeOnPreDrawListener(this);
            }
            void run() {
                if (done) return;
                done = true;
                remove();
                if (view.getVisibility() != View.VISIBLE || !scroll.isAttachedToWindow() || scroll.getChildCount() == 0) return;
                View content = scroll.getChildAt(0);
                int top = 0;
                View at = view;
                for (; at != null && at != content; at = at.getParent() instanceof View ? (View) at.getParent() : null) top += at.getTop();
                if (at != content) return; // not inside the scroll's content any more
                int bottom = top + view.getHeight(), shown = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
                int from = scroll.getScrollY(), to = from;
                if (bottom > to + shown) to = bottom - shown;
                if (top < to) to = top;
                to = Math.max(0, Math.min(to, content.getHeight() - shown));
                if (to == from) return;
                if (!enabled()) { scroll.scrollTo(scroll.getScrollX(), to); return; }
                ValueAnimator animator = ValueAnimator.ofInt(from, to);
                animator.setDuration(CHANGE_MS);
                animator.setInterpolator(STANDARD);
                int[] last = {from};
                animator.addUpdateListener(a -> {
                    // The rider scrolled or flung the page: it stays where they took it.
                    if (scroll.getScrollY() != last[0] || !scroll.isAttachedToWindow()) { a.cancel(); return; }
                    scroll.scrollTo(scroll.getScrollX(), (Integer) a.getAnimatedValue());
                    last[0] = scroll.getScrollY();
                });
                animator.start();
            }
            @Override public void onGlobalLayout() { run(); }
            @Override public boolean onPreDraw() { run(); return true; }
        }
        Once once = new Once();
        // Layout comes before the first frame's pre-draw, where a motion starts its views from their old places.
        once.observer.addOnGlobalLayoutListener(once);
        once.observer.addOnPreDrawListener(once);
    }

    /**
     * The scrolling page that holds {@code view}: its nearest ScrollView's child, or its parent outside one. As a
     * {@link #reveal} root, every part of a stacked page (a Hub page built from several screens) glides together, and
     * reveals in one refresh anywhere on it join one motion.
     */
    static ViewGroup pageOf(View view) {
        ViewGroup page = null;
        for (ViewParent at = view == null ? null : view.getParent(); at instanceof ViewGroup; at = at.getParent()) {
            if (at instanceof ScrollView) return page;
            page = (ViewGroup) at;
        }
        return view != null && view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
    }

    /** How long an alert takes to fade up: it must never wait, so this is short. */
    static final long ALERT_MS = 150;

    /**
     * An alert that appears: it is in its place, laid out and taking input from the first frame, and only its opacity
     * rises over ALERT_MS (at once with animations off or before the view is attached). Call as it becomes VISIBLE.
     */
    static void alertIn(View view) {
        if (view == null) return;
        view.animate().cancel();
        if (!enabled() || !view.isAttachedToWindow()) { view.setAlpha(1); return; }
        view.setAlpha(0);
        view.animate().alpha(1).setDuration(ALERT_MS).setInterpolator(STANDARD).setListener(null).start();
    }

    /** The alert goes (it leaves at once): a fade still rising ends, so it is opaque the next time it shows. */
    static void alertOut(View view) {
        if (view == null) return;
        view.animate().cancel();
        if (view.getAlpha() != 1) view.setAlpha(1);
    }

    /** Content that finished loading fades up in place rather than snapping in. */
    static void fadeIn(View view) {
        if (!enabled() || view == null) return;
        view.setAlpha(0);
        view.animate().alpha(1).setDuration(CHANGE_MS).setInterpolator(STANDARD).setListener(null).start();
    }
}
