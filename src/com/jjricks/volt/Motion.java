package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

    /**
     * An in-place section change: only {@code sections} move (the outgoing one leaves toward
     * {@code -direction}, the incoming one arrives from {@code direction}, fading through); headers,
     * tabs and the scroll position stay still. Call immediately before changing the sections' visibility.
     */
    static void shared(ViewGroup sceneRoot, int direction, View... sections) {
        if (!enabled() || sceneRoot == null || !sceneRoot.isLaidOut()) return;
        SharedAxis axis = new SharedAxis(direction);
        for (View section : sections) if (section != null) axis.addTarget(section);
        TransitionManager.beginDelayedTransition(sceneRoot, axis);
    }

    private static final class SharedAxis extends Visibility {
        private final int direction;
        SharedAxis(int direction) { this.direction = Integer.signum(direction); setDuration(PAGE_MS); }
        @Override public Animator onAppear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
            return part(view, direction * travel(root, root.getWidth()), true);
        }
        @Override public Animator onDisappear(ViewGroup root, View view, TransitionValues start, TransitionValues end) {
            return part(view, direction * travel(root, root.getWidth()), false);
        }
        /** One side of the same fade-through that {@link #drive} gives whole pages. */
        private static Animator part(View view, float shift, boolean arriving) {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(PAGE_MS);
            animator.setInterpolator(new LinearInterpolator());
            ValueAnimator.AnimatorUpdateListener frame = animation -> {
                float t = animation.getAnimatedFraction(), glide = TRAVEL.getInterpolation(t);
                if (arriving) {
                    view.setAlpha(FADE_IN.getInterpolation(Math.max(0f, (t - THROUGH) / (1 - THROUGH))));
                    view.setTranslationX(shift * (1 - glide));
                } else {
                    view.setAlpha(1 - FADE_OUT.getInterpolation(Math.min(1f, t / THROUGH)));
                    view.setTranslationX(-shift * glide);
                }
            };
            animator.addUpdateListener(frame);
            frame.onAnimationUpdate(animator);
            animator.addListener(new AnimatorListenerAdapter() {
                // Section views are reused: never leave one shifted or transparent.
                @Override public void onAnimationEnd(Animator animation) { view.setTranslationX(0); view.setAlpha(1); }
                @Override public void onAnimationCancel(Animator animation) { view.setTranslationX(0); view.setAlpha(1); }
            });
            return animator;
        }
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
        TransitionManager.beginDelayedTransition(root, changes(instant));
    }

    /** {@link #animateChanges}' motion: what goes fades out, what moves glides, what comes fades in. */
    private static TransitionSet changes(View... instant) {
        TransitionSet set = new TransitionSet();
        for (View view : instant) if (view != null) set.excludeChildren(view, true);
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        set.addTransition(new Fade(Fade.OUT).setDuration(EXIT_MS));
        set.addTransition(new ChangeBounds().setDuration(CHANGE_MS));
        set.addTransition(new Fade(Fade.IN).setDuration(CHANGE_MS));
        set.setInterpolator(STANDARD);
        return set;
    }

    /** One {@link #reveal} motion on a root: every reveal on that root before its first frame joins it. */
    private static final class Glide {
        final WeakReference<ViewGroup> root;
        /** It reached its first frame and played; swallowed, when that frame came and it had not. */
        boolean played, swallowed, ended;
        Glide(ViewGroup root) { this.root = new WeakReference<>(root); }
    }

    /** What reveal() last asked of each view: a departure keeps its view drawn (and VISIBLE) until it ends. */
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
     * Focus and accessibility are untouched. Returns whether it changed.
     */
    static boolean reveal(ViewGroup root, View view, boolean shown, View... instant) {
        if (view == null) return false;
        Boolean asked = revealed.get(view);
        if ((asked != null ? asked : view.getVisibility() == View.VISIBLE) == shown) return false;
        revealed.put(view, shown);
        boolean glides = root != null && enabled() && root.isLaidOut();
        Glide running = moving.remove(view);
        ViewGroup was = running == null || running.ended ? null : running.root.get();
        // Ending it jumps what it moves to where it was going; only a change without a motion of its own does that.
        if (was != null && (!glides || was != root)) TransitionManager.endTransitions(was);
        if (glides) {
            // A reversal mid-motion starts here too: Android captures the running motion where it is on screen and
            // turns its views back from there.
            Glide glide = revealing.get(root);
            if (glide == null) glide = glide(root, instant);
            moving.put(view, glide);
        }
        view.setVisibility(shown ? View.VISIBLE : View.GONE);
        return true;
    }

    private static Glide glide(ViewGroup root, View... instant) {
        Glide glide = new Glide(root);
        revealing.put(root, glide);
        Runnable abandoned = () -> { revealing.remove(root); glide.swallowed = true; };
        holdScroll(root);
        TransitionSet set = changes(instant);
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
        ArrayList<View> settled = new ArrayList<>();
        for (Map.Entry<View, Glide> entry : moving.entrySet())
            if (entry.getValue() == glide || entry.getValue().swallowed) settled.add(entry.getKey());
        for (View view : settled) {
            if (view == null) continue;
            moving.remove(view);
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
    private static void holdScroll(ViewGroup root) {
        ScrollView scroll = null;
        for (ViewParent at = root; at instanceof View; at = at.getParent()) if (at instanceof ScrollView) { scroll = (ScrollView) at; break; }
        if (scroll == null || scroll.getChildCount() == 0) return;
        ScrollView page = scroll;
        View content = scroll.getChildAt(0);
        Hold hold = holds.get(content);
        if (hold == null) holds.put(content, hold = new Hold());
        // Reveals on two roots of one page in the same frame share one clamp.
        if (hold.pending) return;
        hold.pending = true;
        Hold held = hold;
        int scrolled = scroll.getScrollY(), tall = content.getHeight();
        beforeNextDraw(root, () -> {
            held.pending = false;
            int clamped = Math.min(scrolled - page.getScrollY(), tall - content.getHeight());
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

    /** Content that finished loading fades up in place rather than snapping in. */
    static void fadeIn(View view) {
        if (!enabled() || view == null) return;
        view.setAlpha(0);
        view.animate().alpha(1).setDuration(CHANGE_MS).setInterpolator(STANDARD).setListener(null).start();
    }
}
