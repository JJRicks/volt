package com.jjricks.volt;

/**
 * How much RouteMapView's tile cache holds: room for every tile its view can show at once, half again over for a pan,
 * within a cap (#560 review r2, F01). With the finer tiles (RouteMapView.FINER_TILES) a large tablet's full-screen ride
 * map shows more tiles than the fixed 20 MB (80 tiles) held, so each frame evicted tiles it was about to draw.
 */
final class RouteTileBudget {
    /** One 256 px map tile as decoded (ARGB_8888, 4 bytes a pixel). */
    static final int TILE_BYTES = 256 * 256 * 4;
    /** The cache's size before it followed the view (80 tiles): the least, so a small map keeps what it kept. */
    static final int FLOOR_BYTES = 20 * 1024 * 1024;
    /** The most it takes, whatever the view's size. */
    static final int CAP_BYTES = 64 * 1024 * 1024;

    private RouteTileBudget() {}

    /**
     * The most tiles a view widthPx by heightPx shows at once when each is drawn minTilePx a side or more: a row spans
     * its width's worth plus one, a tile cut at each end (RouteMapView.drawTiles), and a column likewise.
     */
    static int visibleTiles(int widthPx, int heightPx, double minTilePx) {
        if (widthPx <= 0 || heightPx <= 0 || !(minTilePx > 0)) return 0;
        long across = (long) Math.ceil(widthPx / minTilePx) + 1, down = (long) Math.ceil(heightPx / minTilePx) + 1;
        return (int) Math.min(Integer.MAX_VALUE, across * down);
    }

    /**
     * The cache's size in bytes for that view: its visible tiles times 1.5, at least FLOOR_BYTES and at most CAP_BYTES or
     * heapCap, whichever is less (never below FLOOR_BYTES, the size it always had).
     */
    static int bytes(int widthPx, int heightPx, double minTilePx, long heapCap) {
        long wanted = (long) Math.ceil(visibleTiles(widthPx, heightPx, minTilePx) * 1.5) * TILE_BYTES;
        long top = Math.max(FLOOR_BYTES, Math.min(CAP_BYTES, heapCap));
        return (int) Math.max(FLOOR_BYTES, Math.min(top, wanted));
    }
}
