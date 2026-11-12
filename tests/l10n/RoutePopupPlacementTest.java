package com.jjricks.volt;

/**
 * The ride map popup's placement (RoutePopupPlacement): where it opens, and when a taller, wrapped
 * popup still keeps clear of the tapped point. Sizes are in dp at density 1 unless a test scales
 * them; the 16 dp offset, 44 dp headroom and 8 dp margin are RouteMapView's.
 */
public final class RoutePopupPlacementTest {
    private static final float OFFSET = 16, HEADROOM = 44, MARGIN = 8;
    /** The popup's height at 1.4x text with two context lines (wrapped) and with one (cut). */
    private static final float WRAPPED = 94, CUT = 71;
    private static int checks;

    private RoutePopupPlacementTest() {}

    public static void main(String[] args) {
        placement();
        tallMapWraps();
        shortMapCuts();
        boundaries();
        clearMeansClear();
        System.out.println("RoutePopupPlacementTest: " + checks + " checks");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }

    private static boolean clear(float py, float height, float mapHeight) {
        return RoutePopupPlacement.clearOfPoint(py, height, mapHeight, OFFSET, HEADROOM, MARGIN);
    }

    private static float top(float py, float height, float mapHeight) {
        return RoutePopupPlacement.top(py, height, mapHeight, OFFSET, HEADROOM, MARGIN);
    }

    /** The rule RouteMapView used inline before it moved here, kept for comparison. */
    private static float inlineTop(float py, float height, int mapHeight, int offset, int headroom, int margin) {
        float top = py - offset - height;
        if (top < headroom) top = py + offset;
        top = Math.max(margin, Math.min(mapHeight - margin - height, top));
        return top;
    }

    private static void placement() {
        check(top(200, WRAPPED, 300) == 200 - 16 - WRAPPED, "room above: opens above, 16 dp clear");
        check(top(100, WRAPPED, 300) == 116, "no room above: opens below, 16 dp clear");
        check(top(250, WRAPPED, 300) == 250 - 16 - WRAPPED, "near the bottom: above");
        check(top(20, WRAPPED, 120) == 120 - 8 - WRAPPED, "no room either side: clamped inside the map");
        check(top(-50, WRAPPED, 300) == 8, "a point above the map: the popup stays inside it");
        // The same floats as the inline rule it replaced, so where the line fits nothing moves.
        float[] densities = {1f, 2f, 2.625f, 2.75f, 3f, 3.5f};
        float[] heights = {40.5f, 71.25f, 94.75f, 130f};
        for (float density : densities) {
            int offset = Math.round(16 * density), headroom = Math.round(44 * density), margin = Math.round(8 * density);
            for (int dpHeight : new int[] {100, 110, 120, 130, 300, 312, 344, 725}) {
                int mapHeight = Math.round(dpHeight * density);
                for (float h : heights) {
                    float height = h * density;
                    for (float py = -40.3f * density; py <= mapHeight + 40; py += 3.7f) {
                        checks++;
                        float expected = inlineTop(py, height, mapHeight, offset, headroom, margin);
                        float actual = RoutePopupPlacement.top(py, height, mapHeight, offset, headroom, margin);
                        if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual))
                            throw new AssertionError("top differs from the inline rule at density " + density
                                    + ", map " + mapHeight + ", height " + height + ", point " + py
                                    + ": " + expected + " vs " + actual);
                    }
                }
            }
        }
    }

    /** Maps of real size (300 dp and more): the wrapped popup fits beside every point on the map. */
    private static void tallMapWraps() {
        for (float mapHeight : new float[] {300, 312, 344, 725}) {
            for (float py = 0; py <= mapHeight; py += 0.5f)
                check(clear(py, WRAPPED, mapHeight), "a " + mapHeight + " dp map wraps at point " + py);
        }
        float density = 2.75f;
        for (float py = 0; py <= 300 * density; py += 1)
            check(RoutePopupPlacement.clearOfPoint(py, WRAPPED * density, 300 * density,
                    16 * density, 44 * density, 8 * density), "300 dp at density 2.75 wraps at " + py);
    }

    /** Split screen at large text: the wrapped popup has no room beside the point, so the line is cut. */
    private static void shortMapCuts() {
        check(!clear(20, WRAPPED, 130), "130 dp map, point near the top: cut");
        check(!clear(110, WRAPPED, 130), "130 dp map, point near the bottom: cut");
        check(!clear(15, WRAPPED, 120), "120 dp map, point near the top: cut");
        check(!clear(105, WRAPPED, 120), "120 dp map, point near the bottom: cut");
        for (float py = 0; py <= 110; py += 0.5f) check(!clear(py, WRAPPED, 110), "110 dp map: cut at " + py);
        // The review's case: at 130 dp with the point near the top, the cut popup was clear.
        check(clear(20, CUT, 130), "130 dp map, point near the top: the cut popup opens below, clear");
        check(top(20, CUT, 130) >= 20 + OFFSET, "and it sits below the point");
    }

    private static void boundaries() {
        // Above: exactly at the headroom fits; a hair less does not (and below has no room).
        float py = HEADROOM + OFFSET + WRAPPED; // 154
        check(clear(py, WRAPPED, 160), "above, exactly at the headroom: wraps");
        check(!clear(py - 0.25f, WRAPPED, 160), "above, 0.25 dp short: cut");
        check(top(py, WRAPPED, 160) == HEADROOM, "and the boundary popup opens above, at the headroom");
        // Below: ending exactly at the bottom margin fits; a hair further does not.
        float mapHeight = 200;
        float below = mapHeight - MARGIN - WRAPPED - OFFSET; // 82
        check(clear(below, WRAPPED, mapHeight), "below, ending exactly at the margin: wraps");
        check(!clear(below + 0.25f, WRAPPED, mapHeight), "below, 0.25 dp over: cut");
        check(top(below, WRAPPED, mapHeight) == below + OFFSET, "and the boundary popup opens below, unclamped");
        // The smallest map where every point has room: 2 x (height + offset) + headroom + margin.
        float least = 2 * (WRAPPED + OFFSET) + HEADROOM + MARGIN; // 272
        for (float p = 0; p <= least; p += 0.5f) check(clear(p, WRAPPED, least), "272 dp map wraps at " + p);
        boolean anyCut = false;
        for (float p = 0; p <= least - 1; p += 0.5f) anyCut |= !clear(p, WRAPPED, least - 1);
        check(anyCut, "one dp shorter, some point has no room");
    }

    /** For every point on the map, clearOfPoint says exactly whether top() keeps the popup off the point. */
    private static void clearMeansClear() {
        for (int mapHeight = 60; mapHeight <= 400; mapHeight += 7) {
            for (int height = 30; height <= 140; height += 9) {
                for (int py = 0; py <= mapHeight; py++) {
                    float t = top(py, height, mapHeight);
                    boolean off = t >= py + OFFSET || t + height <= py - OFFSET;
                    check(off == clear(py, height, mapHeight),
                            "map " + mapHeight + ", height " + height + ", point " + py + ": top " + t);
                }
            }
        }
    }
}
