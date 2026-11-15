package com.jjricks.volt;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A row (or a column) of cards that all take the tallest one's height, side by side or stacked: the Home style cards
 * in What's new and in the Home style picker (owner, on a first live build: Clean's description wrapped to three lines
 * and Widgets' to two, and "Cards of equal height would read better"). Each card keeps its content where it lays it
 * out; a shorter card's extra height is its own background under its content. Text is never cut: the tallest card is
 * measured at its full height first, and the others only grow.
 */
final class EqualHeights extends LinearLayout {
    EqualHeights(Context context) {
        super(context);
        // A card's text must not shift it down to line up baselines.
        setBaselineAligned(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int tallest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) tallest = Math.max(tallest, child.getMeasuredHeight());
        }
        int added = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int missing = tallest - child.getMeasuredHeight();
            if (child.getVisibility() == GONE || missing <= 0) continue;
            child.measure(MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY));
            added += missing;
        }
        // Stacked, the column is taller by what its cards grew; side by side it already holds the tallest.
        if (getOrientation() == VERTICAL && added > 0)
            setMeasuredDimension(getMeasuredWidth(), resolveSize(getMeasuredHeight() + added, heightSpec));
    }
}
