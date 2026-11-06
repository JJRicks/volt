package com.jjricks.volt;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A short vertical list reordered by grab handles. The held row lifts and follows the finger,
 * its neighbours glide aside to show where it will land, and it settles into that slot on release.
 * Screen readers get Move up / Move down actions on every row instead of dragging, plus any
 * extra actions the caller gives a row. Lists nest (the Scooter tab's menu editor: a list of
 * section cards, each holding a list of rows); a handle's drag only ever moves its own list.
 */
final class ReorderList extends LinearLayout {
    interface Listener { void reordered(List<String> ids); }
    /**
     * Told when a handle is pressed, before its row lifts: the caller may change the layout first (the menu editor
     * folds every section to its header) and runs {@code ready} once that has settled. {@link #released} follows
     * every press, dropped or not, after any {@link Listener#reordered}.
     */
    interface Grab {
        void grabbing(String id, Runnable ready);
        void released(String id);
    }
    /** An extra screen-reader action on a row, e.g. Hide. */
    static final class Action {
        final int id; final String label; final Runnable run;
        Action(int id, String label, Runnable run) { this.id = id; this.label = label; this.run = run; }
    }

    private static final int MOVE_UP = R.id.reorder_move_up, MOVE_DOWN = R.id.reorder_move_down;
    private final Ui ui;
    private final Listener listener;
    private final ArrayList<String> ids = new ArrayList<>();
    private final ArrayList<String> spoken = new ArrayList<>();
    private Grab grab;
    private View held;
    private boolean waiting;
    private int from, to, pointer = -1;
    private final ReorderDrag drag;
    /** The dragging finger's latest screen Y, so a row lifted after a Grab anchors where the finger is now. */
    private float fingerY;
    private int[] restingTops = new int[0];

    ReorderList(Ui ui, Listener listener) {
        super(ui.context);
        this.ui = ui; this.listener = listener;
        drag = new ReorderDrag(android.view.ViewConfiguration.get(ui.context).getScaledTouchSlop());
        setOrientation(VERTICAL); setClipChildren(false); setClipToPadding(false);
    }

    List<String> order() { return Collections.unmodifiableList(ids); }
    void setGrab(Grab grab) { this.grab = grab; }

    /** {@code said} is how screen readers name the row (the visible label can carry symbols). */
    void add(String id, String label, String said) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56)); row.setPadding(ui.dp(16), ui.dp(6), ui.dp(4), ui.dp(6));
        row.setBackground(ui.shape(ui.raised, 14, 0));
        TextView name = ui.text(label, 15, false);
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(name, new LayoutParams(0, -2, 1));
        View handle = handle(said, 20, ui.muted);
        row.addView(handle, new LayoutParams(ui.dp(48), ui.dp(48)));
        add(id, row, handle, null, said, Collections.<Action>emptyList(), ui.dp(8));
    }

    /** A 48 dp grab handle with the grip glyph, hidden from screen readers (they use the row's Move actions). */
    View handle(String said, int glyphDp, int color) {
        FrameLayout handle = new FrameLayout(getContext());
        handle.addView(ui.icon("grip", glyphDp, color), new FrameLayout.LayoutParams(ui.dp(glyphDp), ui.dp(glyphDp), Gravity.CENTER));
        handle.setContentDescription(T.uiReorderDrag(said));
        // Dragging is for touch; screen readers reach the row itself and its Move actions.
        handle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return handle;
    }

    /**
     * A caller-built row: {@code row} is what moves, a press on {@code handle} drags it, and {@code spokenAs} (the
     * row itself when null) is what screen readers focus, named {@code said}, with Move up / Move down and
     * {@code extra}. {@code gap} is the space below the row.
     */
    void add(String id, View row, View handle, View spokenAs, String said, List<Action> extra, int gap) {
        View host = spokenAs == null ? row : spokenAs;
        handle.setOnTouchListener((view, event) -> drag(row, event));
        host.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                int index = indexOfChild(row);
                if (index > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_UP, T.uiReorderMoveUp()));
                if (index >= 0 && index < getChildCount() - 1)
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_DOWN, T.uiReorderMoveDown()));
                for (Action action : extra) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(action.id, action.label));
            }
            @Override public boolean performAccessibilityAction(View view, int action, Bundle arguments) {
                int index = indexOfChild(row);
                if (action == MOVE_UP && index > 0) { move(index, index - 1, true); return true; }
                if (action == MOVE_DOWN && index >= 0 && index < getChildCount() - 1) { move(index, index + 1, true); return true; }
                for (Action extraAction : extra) if (extraAction.id == action) { extraAction.run.run(); return true; }
                return super.performAccessibilityAction(view, action, arguments);
            }
        });
        LayoutParams params = new LayoutParams(-1, -2);
        params.bottomMargin = gap;
        addView(row, params);
        host.setFocusable(true); host.setContentDescription(said);
        ids.add(id); spoken.add(said);
    }

    /** Takes a row out of the list (the caller animates the change, e.g. with Motion.animateChanges). */
    boolean remove(String id) {
        int index = ids.indexOf(id);
        if (index < 0 || held != null) return false;
        removeViewAt(index); ids.remove(index); spoken.remove(index);
        return true;
    }

    /** Screen-reader style move of one row by {@code delta} places, as the Move actions do; false when nothing moved. */
    boolean move(String id, int delta) {
        int index = ids.indexOf(id), target = Math.max(0, Math.min(ids.size() - 1, index + delta));
        if (index < 0 || target == index || held != null) return false;
        move(index, target, true);
        return true;
    }

    /** The held row lifts: its neighbours' resting places are taken now, after any change the Grab made. */
    private void lift(View row) {
        from = to = indexOfChild(row);
        restingTops = new int[getChildCount()];
        for (int i = 0; i < getChildCount(); i++) restingTops[i] = getChildAt(i).getTop();
        // Measure from where the finger is now: a Grab may have moved the row (folding) while the finger stayed still.
        drag.anchor(fingerY);
        row.animate().cancel();
        if (Motion.enabled())
            row.animate().scaleX(1.03f).scaleY(1.03f).translationZ(ui.dp(10)).setDuration(Motion.EXIT_MS)
                    .setInterpolator(Motion.STANDARD).setListener(null).start();
        else row.setTranslationZ(ui.dp(10));
    }

    private void released(View row) {
        int index = indexOfChild(row);
        if (grab != null && index >= 0) grab.released(ids.get(index));
    }

    private boolean drag(View row, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (held != null || indexOfChild(row) < 0) return false;
                held = row; pointer = event.getPointerId(0); fingerY = event.getRawY();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                Haptics.lift(row);
                if (grab == null) { lift(row); return true; }
                waiting = true;
                grab.grabbing(ids.get(indexOfChild(row)), () -> { if (held == row && waiting) { waiting = false; lift(row); } });
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (held != row) return false;
                int index = event.findPointerIndex(pointer);
                if (index < 0) return true;
                // Only the finger that lifted the row moves it. getRawY(int) needs API 29, and
                // every pointer shares this view's transform, so offset from the first pointer.
                fingerY = event.getRawY() + (event.getY(index) - event.getY(0));
                if (waiting) return true;
                float dy = drag.offset(fingerY);
                // Keep the held row within the list: its top stops at the first row's top, its bottom at the last row's bottom.
                View last = getChildAt(getChildCount() - 1);
                float lowest = restingTops[0] - restingTops[from];
                float highest = restingTops[getChildCount() - 1] + last.getHeight() - restingTops[from] - row.getHeight();
                dy = Math.max(lowest, Math.min(Math.max(lowest, highest), dy));
                row.setTranslationY(dy);
                float top = restingTops[from] + dy, bottom = top + row.getHeight();
                int[] heights = new int[getChildCount()];
                for (int i = 0; i < heights.length; i++) heights[i] = getChildAt(i).getHeight();
                int target = ReorderDrag.target(restingTops, heights, from, top, bottom);
                if (target != to) {
                    to = target;
                    Haptics.tick(row);
                    int[] slots = slots(from, to);
                    for (int i = 0; i < getChildCount(); i++) {
                        if (i == from) continue;
                        View other = getChildAt(i);
                        float shift = slots[i] - restingTops[i];
                        if (Motion.enabled()) other.animate().translationY(shift).setDuration(Motion.CHANGE_MS)
                                .setInterpolator(Motion.STANDARD).setListener(null).start();
                        else other.setTranslationY(shift);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (held != row) return false;
                if (event.getPointerId(event.getActionIndex()) != pointer) return true;
                // The dragging finger lifted while another stays down: drop here.
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (held != row || pointer < 0) return held == row;
                pointer = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (waiting) { waiting = false; held = null; released(row); return true; }
                // Only a real release drops the row; a cancelled gesture puts everything back.
                settle(row, event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                return true;
            default:
                return held == row;
        }
    }

    /** Resting top of every current child once {@code moving} sits at index {@code target}. */
    private int[] slots(int moving, int target) {
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) if (i != moving) order.add(i);
        order.add(target, moving);
        int[] tops = new int[getChildCount()];
        int y = restingTops.length > 0 ? restingTops[0] : getPaddingTop();
        for (int index : order) {
            View child = getChildAt(index);
            tops[index] = y;
            y += child.getHeight() + ((LayoutParams) child.getLayoutParams()).bottomMargin;
        }
        return tops;
    }

    /** Lands the held row in its new slot ({@code drop}) or back where it started, saving only a drop. */
    private void settle(View row, boolean drop) {
        if (!drop) to = from;
        int[] slots = slots(from, to);
        float landing = slots[from] - restingTops[from];
        Runnable commit = () -> {
            row.setScaleX(1); row.setScaleY(1); row.setTranslationZ(0);
            for (int i = 0; i < getChildCount(); i++) { getChildAt(i).animate().cancel(); getChildAt(i).setTranslationY(0); }
            int moved = from, target = to;
            held = null;
            if (moved != target) {
                reorder(moved, target);
                announceForAccessibility(T.uiReorderMoved(spoken.get(target), Fmt.integer(target + 1), Fmt.integer(ids.size())));
                if (listener != null) listener.reordered(order());
            }
            released(row);
        };
        if (!Motion.enabled()) { commit.run(); return; }
        if (!drop) for (int i = 0; i < getChildCount(); i++) {
            if (i != from) getChildAt(i).animate().translationY(0).setDuration(Motion.CHANGE_MS)
                    .setInterpolator(Motion.STANDARD).setListener(null).start();
        }
        row.animate().translationY(landing).scaleX(1).scaleY(1).translationZ(0).setDuration(Motion.CHANGE_MS)
                .setInterpolator(Motion.STANDARD).setListener(new AnimatorListenerAdapter() {
                    boolean done;
                    @Override public void onAnimationEnd(Animator animation) { if (!done) { done = true; commit.run(); } }
                    @Override public void onAnimationCancel(Animator animation) { if (!done) { done = true; commit.run(); } }
                }).start();
    }

    private void reorder(int moved, int target) {
        View child = getChildAt(moved);
        removeViewAt(moved); addView(child, target);
        ids.add(target, ids.remove(moved)); spoken.add(target, spoken.remove(moved));
    }

    /** Screen-reader moves: rows glide from their old place to the new one. */
    private void move(int moved, int target, boolean notify) {
        int[] before = new int[getChildCount()];
        View[] children = new View[getChildCount()];
        for (int i = 0; i < getChildCount(); i++) { children[i] = getChildAt(i); before[i] = children[i].getTop(); }
        reorder(moved, target);
        if (Motion.enabled()) {
            Motion.beforeNextDraw(this, () -> {
                for (int i = 0; i < children.length; i++) {
                    View child = children[i];
                    child.setTranslationY(before[i] - child.getTop());
                    child.animate().translationY(0).setDuration(Motion.CHANGE_MS).setInterpolator(Motion.STANDARD).setListener(null).start();
                }
            }, null);
        }
        announceForAccessibility(T.uiReorderMoved(spoken.get(target), Fmt.integer(target + 1), Fmt.integer(ids.size())));
        if (notify && listener != null) listener.reordered(order());
    }
}
