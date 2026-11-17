package com.jjricks.volt;

/**
 * Where two side-by-side panes divide a row (hub layout plan 1.11, PR 13): the wide Clean Home's scooter pane and its
 * page pane, and More's old menu and section panes (HubPanes). Pure Java (no Android), so the JVM tests reach it; every
 * size is in the same unit (pixels on a device, dp in the tests).
 *
 * <p>The gutter falls on a vertical hinge when there is one and both panes keep their minimums beside it; otherwise on the
 * window's centre line, with each pane held to its minimum; before the row knows where it sits in the window, the left
 * pane takes its share of the row. A row too narrow for both minimums splits in half (HubPanes) or, where the caller
 * asks first with {@link #fits}, takes one pane.
 */
final class HubSplit {
    private HubSplit() { }

    /** The left pane's width and the gutter's, in the row's unit. */
    static final class Split {
        final int left, gap;
        Split(int left, int gap) { this.left = left; this.gap = gap; }
        /** The right pane: what is left of the row. */
        int right(int size) { return Math.max(0, size - left - gap); }
        @Override public String toString() { return left + "+" + gap; }
    }

    /** Whether a row this wide holds both panes at their minimums with the gutter between them. */
    static boolean fits(int size, int gap, int minLeft, int minRight) {
        return size > 0 && size - gap - minRight >= minLeft;
    }

    /**
     * The split of a row size wide.
     *
     * @param centreLeft the left width that puts the gutter on the window's centre line, or a negative number while the
     *                   row's place in the window is not known yet (then share decides)
     * @param hingeLeft  a vertical hinge's start, measured from the row's own left edge
     * @param hingeWidth the hinge's width; 0 without a hinge
     * @param hingePad   room kept clear on each side of the hinge
     */
    static Split split(int size, int gap, int minLeft, int minRight, float share, int centreLeft, int hingeLeft, int hingeWidth, int hingePad) {
        size = Math.max(0, size);
        if (hingeWidth > 0) {
            // A book posture: no row may lie under the fold, so the gutter covers the hinge with room on both sides.
            int hingeGap = Math.max(gap, hingeWidth + 2 * hingePad), proposed = hingeLeft - hingePad;
            if (proposed >= minLeft && size - proposed - hingeGap >= minRight) return new Split(proposed, hingeGap);
        }
        int max = size - gap - minRight;
        if (max < minLeft) return new Split(Math.max(0, (size - gap) / 2), gap);
        int want = centreLeft >= 0 ? centreLeft : Math.round((size - gap) * share);
        return new Split(Math.max(minLeft, Math.min(max, want)), gap);
    }

    /** The left width that centres the gutter on a window this wide, for a row starting leftInWindow from its edge. */
    static int centreLeft(int windowWidth, int gap, int leftInWindow) {
        return windowWidth / 2 - gap / 2 - leftInWindow;
    }
}
