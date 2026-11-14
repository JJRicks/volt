package com.jjricks.volt;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;

import java.util.function.BooleanSupplier;

/**
 * Two panes side by side, each the row's full height (hub layout plan 1.11, PR 13): the wide Clean Home's scooter pane
 * and page pane. Extracted in PR 13 from More's old wide menu (removed in PR 14); no text of its own.
 *
 * <p>The split follows HubSplit: the gutter on a vertical hinge when there is one, otherwise on the window's centre line,
 * each pane held to its minimum. The window position is known only at layout, so the row re-splits there before the
 * frame draws instead of asking for another layout. After every layout the owner's word check runs once: when it says
 * a word no longer fits (wordsBreak), onePane is posted once and never again from this row, so the owner can rebuild in
 * one pane and remember that size (the two layouts can't alternate).
 */
final class HubPanes extends LinearLayout {
    private final Ui ui;
    private final View first, gutter, second;
    private final int minLeft, minRight, gap;
    private final float share;
    /** A vertical hinge in window coordinates, or null. */
    private final Rect hinge;
    private final BooleanSupplier wordsBreak;
    private final Runnable onePane;
    private int split = -1, gapShown;
    private boolean onePaneAsked;

    /**
     * @param minLeftDp  the left pane's minimum width
     * @param minRightDp the right pane's minimum width
     * @param share      the left pane's share of the row before it knows its place in the window
     * @param hinge      a vertical hinge (window coordinates), or null
     * @param wordsBreak true when a word in a pane no longer fits its width; null checks nothing
     * @param onePane    run (posted) once when wordsBreak says so
     */
    HubPanes(Context context, Ui ui, View left, View right, int minLeftDp, int minRightDp, float share, Rect hinge,
             BooleanSupplier wordsBreak, Runnable onePane) {
        super(context);
        this.ui = ui;
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        first = left;
        second = right;
        // The Layout Plan's wide mock-ups draw a hairline down the gutter's middle between the two panes.
        gutter = new View(context) {
            private final android.graphics.Paint line = new android.graphics.Paint();
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                line.setColor(ui.raised);
                float x = getWidth() / 2f, half = Math.max(1, ui.dp(1)) / 2f;
                canvas.drawRect(x - half, 0, x + half, getHeight(), line);
            }
        };
        gutter.setWillNotDraw(false);
        minLeft = ui.dp(minLeftDp);
        minRight = ui.dp(minRightDp);
        gap = ui.dp(20);
        this.share = share;
        this.hinge = hinge == null || hinge.width() <= 0 || hinge.height() <= 0 ? null : new Rect(hinge);
        this.wordsBreak = wordsBreak;
        this.onePane = onePane;
        addView(first, new LinearLayout.LayoutParams(0, -1));
        addView(gutter, new LinearLayout.LayoutParams(gap, -1));
        addView(second, new LinearLayout.LayoutParams(0, -1));
    }

    /** The left pane's view. */
    View left() { return first; }

    /** The right pane's view. */
    View right() { return second; }

    private HubSplit.Split split(int size) {
        int centre = -1, hingeLeft = 0, hingeWidth = 0;
        View window = getRootView();
        // Layout position, not on-screen position: a page transition slides this row in, and measuring mid-slide used to
        // leave the left pane narrow until the next layout.
        int leftInWindow = Ui.layoutLeftInWindow(this) + getPaddingLeft();
        if (isAttachedToWindow() && window != null && window.getWidth() > 0) centre = HubSplit.centreLeft(window.getWidth(), gap, leftInWindow);
        if (hinge != null) { hingeLeft = hinge.left - leftInWindow; hingeWidth = hinge.width(); }
        return HubSplit.split(size, gap, minLeft, minRight, share, centre, hingeLeft, hingeWidth, ui.dp(8));
    }

    private void apply(HubSplit.Split s, int size) {
        split = s.left;
        gapShown = s.gap;
        ((LinearLayout.LayoutParams) first.getLayoutParams()).width = s.left;
        ((LinearLayout.LayoutParams) gutter.getLayoutParams()).width = s.gap;
        ((LinearLayout.LayoutParams) second.getLayoutParams()).width = s.right(size);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        apply(split(size), size);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // The window position is final only now. Re-split before this frame draws rather than posting another layout,
        // so the first frame already shows the resting widths.
        int size = r - l - getPaddingLeft() - getPaddingRight();
        HubSplit.Split wanted = split(size);
        if (wanted.left != split || wanted.gap != gapShown) {
            apply(wanted, size);
            int height = MeasureSpec.makeMeasureSpec(Math.max(0, b - t - getPaddingTop() - getPaddingBottom()), MeasureSpec.EXACTLY);
            first.measure(MeasureSpec.makeMeasureSpec(wanted.left, MeasureSpec.EXACTLY), height);
            second.measure(MeasureSpec.makeMeasureSpec(wanted.right(size), MeasureSpec.EXACTLY), height);
        }
        super.onLayout(changed, l, t, r, b);
        onePaneIfWordsBreak();
    }

    /** The last rung of the panes' narrow-label ladder: the owner rebuilds in one pane, once. */
    private void onePaneIfWordsBreak() {
        if (onePaneAsked || wordsBreak == null || onePane == null || !wordsBreak.getAsBoolean()) return;
        onePaneAsked = true;
        post(onePane);
    }
}
