package com.jjricks.volt;

/**
 * The drag maths behind {@link ReorderList}, kept free of Android so tests/ReorderDragTest can check it on the JVM.
 *
 * <p>A drag is measured from where the finger is when its row lifts ({@link #anchor}), not where it first pressed:
 * a {@link ReorderList.Grab} may move the row before it lifts (the menu editor folds every section), and a finger
 * that has not moved since must not count as a drag. Until the finger travels the touch slop from the anchor the row
 * stays put, so a resting or jittering finger never changes the order. After that the row follows the finger.
 */
final class ReorderDrag {
    private final float slop;
    private float anchor;
    private boolean dragging;

    ReorderDrag(float slop) { this.slop = Math.max(0, slop); }

    /** The row lifts with the finger at {@code rawY}: displacement counts from here. */
    void anchor(float rawY) { anchor = rawY; dragging = false; }

    /** How far the held row moves for the finger at {@code rawY}: 0 within the slop, then the finger's travel. */
    float offset(float rawY) {
        float dy = rawY - anchor;
        if (!dragging) {
            if (Math.abs(dy) < slop) return 0;
            dragging = true;
        }
        return dy;
    }

    /**
     * The slot the held row at index {@code from} would land in, its edges at {@code top}..{@code bottom}: a neighbour
     * gives way once the held row's leading edge passes its middle, so rows of different heights still trade places.
     */
    static int target(int[] tops, int[] heights, int from, float top, float bottom) {
        int target = from;
        for (int i = 0; i < tops.length; i++) {
            float middle = tops[i] + heights[i] / 2f;
            if (i < from && middle > top) target--;
            else if (i > from && middle < bottom) target++;
        }
        return target;
    }
}
