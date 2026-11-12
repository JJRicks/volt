package com.jjricks.volt;

/**
 * Where the ride map's route-point popup sits, without Android dependencies. All values are in
 * pixels: {@code offset} is the gap between the tapped point and the popup, {@code headroom} the
 * band at the top of the map the popup avoids when it would open above the point, and
 * {@code margin} the popup's distance from the map's edges.
 */
final class RoutePopupPlacement {
    private RoutePopupPlacement() {}

    /**
     * The popup's top edge: above the point when it clears the headroom, otherwise below it, then
     * kept inside the map. A popup with no room on either side is clamped and can cover the point.
     */
    static float top(float py, float height, float mapHeight, float offset, float headroom, float margin) {
        float top = py - offset - height;
        if (top < headroom) top = py + offset;
        return Math.max(margin, Math.min(mapHeight - margin - height, top));
    }

    /**
     * Whether a popup this tall keeps clear of the point: it opens above it by the rule in
     * {@link #top}, or it opens below and still ends inside the map, so the clamp never moves it
     * back onto the point. The joined context line only breaks onto further lines when this holds.
     */
    static boolean clearOfPoint(float py, float height, float mapHeight, float offset, float headroom, float margin) {
        return py - offset - height >= headroom || py + offset + height <= mapHeight - margin;
    }
}
