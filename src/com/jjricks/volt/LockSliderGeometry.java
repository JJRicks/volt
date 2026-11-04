package com.jjricks.volt;

/**
 * Shared drawing/touch geometry: a horizontal thumb keeps travel even in a tall card. Positions run
 * from 0 at the start edge to 1 at the end edge: left to right, or right to left when the layout is.
 */
public final class LockSliderGeometry {
    public final float diameter, radius, firstCenter, travel;
    /** True when position 0 is at the right edge (a right-to-left layout). */
    public final boolean rightToLeft;
    private final float width;

    public LockSliderGeometry(float width, float height, float maximumDiameter, float inset) {
        this(width, height, maximumDiameter, inset, false);
    }

    /** {@code rightToLeft} mirrors the travel: the view's layout direction is right to left. */
    public LockSliderGeometry(float width, float height, float maximumDiameter, float inset, boolean rightToLeft) {
        diameter = Math.max(0, Math.min(Math.min(height, maximumDiameter), width / 2f));
        radius = Math.max(0, diameter / 2f - inset);
        firstCenter = diameter / 2f;
        travel = Math.max(0, width - diameter);
        this.width = width;
        this.rightToLeft = rightToLeft;
    }

    /** The thumb's centre, as an x coordinate from the view's left edge. */
    public float centerFor(float position) {
        float fromStart = firstCenter + travel * Math.max(0, Math.min(1, position));
        return rightToLeft ? width - fromStart : fromStart;
    }

    /** The position a touch at x (from the view's left edge) points at. */
    public float positionFor(float x) {
        float fromStart = rightToLeft ? width - x : x;
        return Math.max(0, Math.min(1, (fromStart - firstCenter) / Math.max(1, travel)));
    }
}
