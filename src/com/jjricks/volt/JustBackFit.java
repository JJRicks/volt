package com.jjricks.volt;

/**
 * Clean Home's Just back moment, as the Layout Plan's mock-up draws it (the contributor's decisions on #383): the
 * pure rules behind JustBackView. Pure Java. The Avg speed tile's value is RideAverages.speedKmh, the rule the ride's
 * page on Rides uses too.
 *
 * <ul>
 *   <li>{@link #signedChange}: the Battery tile's signed change ("−18" for a ride that used 18%, "+2" for a gain).</li>
 *   <li>{@link #distanceSp}: the distance's size, about 60 sp as the mock-up renders it, growing only a little with
 *       large text so the route panel and the tiles keep their room in the fixed top area.</li>
 *   <li>{@link #nameScale}: the tile names a little smaller (to {@link #NAME_MIN_SCALE}) before one wraps.</li>
 *   <li>{@link #valueSize}: a tile value (number large, unit small) at the largest size that fits its tile on one
 *       line, down to the readable floor; under it the value takes its short rung.</li>
 * </ul>
 */
final class JustBackFit {
    /** The distance's size at 1x (sp), as the mock-up renders "6.2 mi"; its unit is {@link #DISTANCE_UNIT_RATIO} of it. */
    static final float DISTANCE_SP = 60, DISTANCE_UNIT_RATIO = .4f;
    /** How much large text may grow the distance (it is already large; the route and the tiles need the height). */
    static final float DISTANCE_MAX_GROWTH = 1.15f;
    /** The least height (dp) the route panel shows at: lower, a route drawn inside its inset is too flat to read. */
    static final int MIN_ROUTE_DP = 56;
    /** A tile value's size at 1x (sp, the mock-up's 21 sp) and its unit's share of it. */
    static final float VALUE_SP = 21, VALUE_UNIT_RATIO = .62f;
    /** A tile name's size at 1x (sp). */
    static final float NAME_SP = 12.5f;
    /**
     * The least share of its size a tile name takes to stay on one line, as Clean's charging tiles' labels
     * (ChargingFit.TILE_LABEL_MIN_SCALE): the app's usual "a little smaller before it wraps".
     */
    static final float NAME_MIN_SCALE = ChargingFit.TILE_LABEL_MIN_SCALE;

    private JustBackFit() {}

    /**
     * The Battery tile's change in whole percent with its sign, from the saved drop (start minus end): a drop reads
     * "−18" (a true minus sign), a gain "+2", no change "0"; null for an unknown change. Latin digits, as every
     * reading in Volt.
     */
    static String signedChange(Double dropPercent) {
        if (dropPercent == null || !Double.isFinite(dropPercent)) return null;
        // The size rounds half up, as the Rides page rounds it; the sign follows the change (U+2212, a true minus).
        long size = Math.round(Math.abs(dropPercent));
        if (size == 0) return Long.toString(0);
        return (dropPercent > 0 ? "\u2212" : "+") + Long.toString(size);
    }

    /** The distance's size in sp at the app's text scale (Ui.sp then multiplies by the scale itself). */
    static float distanceSp(float textScale) {
        float scale = Float.isNaN(textScale) || textScale <= 0 ? 1f : textScale;
        return DISTANCE_SP * Math.min(scale, DISTANCE_MAX_GROWTH) / scale;
    }

    /**
     * How the height left between the ended line and the tiles is shared (any unit, px in JustBackView): the distance
     * keeps its full box first and the route panel takes the rest; when that rest is under minRoute the distance gets
     * smaller (down to distanceFloor) so the route keeps minRoute; when even that does not fit, the panel goes
     * (0, with its gap) and the distance's box alone takes the room (its text at most full size, centred), down to
     * distanceFloor. Returns {distance box, route
     * panel, route gap}. So the fixed top area never overflows: on a short window with large text the route goes
     * first, then the distance gets smaller; the ended line, the X and the three tiles stay.
     */
    static int[] split(int room, int distanceFull, int distanceFloor, int minRoute, int routeGap) {
        int left = Math.max(0, room);
        if (left - distanceFull - routeGap >= minRoute) return new int[] {distanceFull, left - distanceFull - routeGap, routeGap};
        // Short of that, the distance gets smaller (down to its floor) so the route keeps its least height.
        if (left - distanceFloor - routeGap >= minRoute) return new int[] {left - routeGap - minRoute, minRoute, routeGap};
        // Without the route the distance's box takes all of it (its text stays at most full size, centred in the box),
        // so no blank band opens between the distance and the tiles.
        return new int[] {Math.max(Math.min(distanceFull, distanceFloor), left), 0, 0};
    }

    /**
     * The share of their size the three tile names take so each stays on one line, as the mock-up draws them ("Avg
     * speed" wrapped onto two lines at 344 dp with 1.4x text): 1 when every name fits {@code room} at full size; else
     * the largest share, in hundredths, at which the widest fits, no smaller than {@link #NAME_MIN_SCALE} nor than the
     * readable floor ({@code floorSp} over the name's full size {@code fullSp}); when even that is too wide, 1 (the
     * names keep their size and TileFit wraps them at spaces, never inside a word). widths are the names' widths at
     * full size, in the same unit as room.
     */
    static float nameScale(float[] widths, float room, float fullSp, float floorSp) {
        float widest = 0;
        for (float w : widths) widest = Math.max(widest, w);
        if (widest <= room) return 1;
        if (room <= 0 || fullSp <= 0) return 1;
        float least = Math.max(NAME_MIN_SCALE, Math.min(1, floorSp / fullSp));
        float scale = (float) Math.floor(room / widest * 100) / 100;
        return scale >= least ? scale : 1;
    }

    /**
     * The size (px) a tile value takes: {@code top} when it fits {@code room}, else the size its width shrinks to
     * (widths grow with size), no smaller than {@code floor}; -1 when even the floor is too wide, so the caller shows
     * the value's short rung. widthAtTop is the value's width at {@code top}.
     */
    static float valueSize(float top, float floor, float widthAtTop, float room) {
        if (widthAtTop <= room) return top;
        if (widthAtTop <= 0 || room <= 0) return -1;
        float size = (float) Math.floor(top * room / widthAtTop * 100) / 100;
        return size >= floor ? size : -1;
    }
}
