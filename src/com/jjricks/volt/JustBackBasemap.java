package com.jjricks.volt;

/**
 * Clean Home's "Just back" basemap (owner, Nov 19 2026): a small switch in the route panel's corner puts map tiles
 * behind the route. It is its own preference, off by default and separate from the ride maps' map_basemap_enabled:
 * "a user might get 'flashbanged' by a light map dynamic card if the white basemap just pops up, but if they
 * specifically go looking for ride recordings they expect the basemap". The provider (MapSource) and the one-time
 * privacy explanation (map_basemap_explained) stay shared with the ride maps (MapPreferences).
 *
 * <p>Pure Java (no Android), so tests/l10n/JustBackBasemapTest runs what the card runs: the key and its default, the
 * Web Mercator box of the route's fixes, how the card's route outline maps onto it, the tile zoom, the corner the switch
 * takes, and the dark theme's tile colours.
 */
final class JustBackBasemap {
    private JustBackBasemap() { }

    /** app_settings key: the card's basemap, absent meaning off. It travels in backups (BackupRegistry, App preferences). */
    static final String KEY = "just_back_basemap";

    /** The stored value as the card reads it: on only when explicitly true. */
    static boolean on(Object stored) { return Boolean.TRUE.equals(stored); }

    private static final double MAX_LATITUDE = 85.05112878;

    /**
     * The online map's one-word summary (About, Your data, Online map; App preferences, Map): off when neither basemap is
     * on; the provider when the ride maps' is; the provider and where when only the Just back card's is ("OSM · Home"),
     * so the card's switch is never hidden behind "Off" (council, #560 r1).
     */
    static String summary(boolean rideMaps, boolean card, String provider, String off, String home) {
        if (rideMaps) return provider;
        return card ? provider + " · " + home : off;
    }

    /** Web Mercator x of a longitude, 0 to 1 across the world (as RouteMapView). */
    static double x(double longitude) { return (longitude + 180) / 360; }

    /** Web Mercator y of a latitude, 0 at the top of the world, 1 at the bottom (as RouteMapView). */
    static double y(double latitude) {
        double lat = Math.toRadians(Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude)));
        return (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2;
    }

    /**
     * The fixes' box in Web Mercator world units, {west, north, east, south}, east possibly above 1 for a ride across
     * the 180th meridian (longitudes are taken on the first fix's side, as RouteMapView.fit does); null for no fixes.
     */
    static double[] box(double[] latitudes, double[] longitudes) {
        int count = Math.min(latitudes.length, longitudes.length);
        if (count == 0) return null;
        double anchor = x(longitudes[0]), west = anchor, east = anchor, north = y(latitudes[0]), south = north;
        for (int i = 0; i < count; i++) {
            double px = x(longitudes[i]);
            if (px - anchor > .5) px--;
            if (px - anchor < -.5) px++;
            west = Math.min(west, px); east = Math.max(east, px);
            double py = y(latitudes[i]);
            north = Math.min(north, py); south = Math.max(south, py);
        }
        return new double[]{west, north, east, south};
    }

    /** The world size (px) for a ride without extent (a single place): zoom 16 at 256 px tiles. */
    static double singlePlaceWorld(double tilePx) { return tilePx * 65536; }

    /**
     * Screen pixels per world unit for the card's outline: the outline (RidesScreen.readThumb: {width, height, points},
     * both sides scaled so the longer is 1) is drawn scale px per outline unit, and spans the Mercator box. The two sides'
     * ratios agree to well under 1% for any ride (the outline's projection differs from Mercator only by the change of
     * the latitude's cosine across the ride); their mean is used. For a ride with no extent, singlePlaceWorld.
     */
    static double world(float[] outline, double[] box, float scale, double tilePx) {
        double sum = 0; int sides = 0;
        if (outline != null && box != null && outline.length >= 2 && scale > 0) {
            double across = box[2] - box[0], down = box[3] - box[1];
            if (outline[0] > 1e-4f && across > 1e-12) { sum += outline[0] * scale / across; sides++; }
            if (outline[1] > 1e-4f && down > 1e-12) { sum += outline[1] * scale / down; sides++; }
        }
        return sides == 0 ? singlePlaceWorld(tilePx) : sum / sides;
    }

    /**
     * The tile zoom for a world of world px with tiles drawn tilePx at their own zoom (the ride maps draw a 256 px tile at
     * 256 dp, the Just back card at 128 dp, one zoom finer): the zoom whose tiles are drawn at least their size, at most maxZoom; -1 when even maxZoom's tiles would be
     * drawn more than 8 times their size (a route only a few metres across), where a map would show nothing readable.
     */
    static int tileZoom(double world, double tilePx, int maxZoom) {
        if (!(world > 0) || !(tilePx > 0)) return -1;
        double exact = Math.log(world / tilePx) / Math.log(2);
        int zoom = (int) Math.max(0, Math.min(maxZoom, Math.floor(exact + 1e-9)));
        return exact - zoom > 3 + 1e-9 ? -1 : zoom;
    }

    /**
     * Whether the route drawn in the panel touches the rectangle {left, top, right, bottom} (px), grown by pad (the end
     * dots' ring): a fix inside it, or a stretch between two fixes crossing it. outline as readThumb gives it, drawn
     * from (ox, oy) at scale px per outline unit.
     */
    static boolean touches(float[] outline, float ox, float oy, float scale, float[] rect, float pad) {
        if (outline == null || outline.length < 4) return false;
        float left = rect[0] - pad, top = rect[1] - pad, right = rect[2] + pad, bottom = rect[3] + pad;
        float px = ox + outline[2] * scale, py = oy + outline[3] * scale;
        if (px >= left && px <= right && py >= top && py <= bottom) return true;
        for (int i = 4; i + 1 < outline.length; i += 2) {
            float qx = ox + outline[i] * scale, qy = oy + outline[i + 1] * scale;
            if (crosses(px, py, qx, qy, left, top, right, bottom)) return true;
            px = qx; py = qy;
        }
        return false;
    }

    /** Whether the segment from (ax, ay) to (bx, by) meets the box (Liang-Barsky clipping). */
    private static boolean crosses(float ax, float ay, float bx, float by, float left, float top, float right, float bottom) {
        double t0 = 0, t1 = 1, dx = bx - ax, dy = by - ay;
        double[] p = {-dx, dx, -dy, dy}, q = {ax - left, right - ax, ay - top, bottom - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return false; continue; }
            double t = q[i] / p[i];
            if (p[i] < 0) { if (t > t1) return false; if (t > t0) t0 = t; }
            else { if (t < t0) return false; if (t < t1) t1 = t; }
        }
        return true;
    }

    /**
     * The dark theme's treatment of a tile (an android.graphics.ColorMatrix, row-major 4x5, colours 0 to 255): the
     * tile's lightness inverted with its hues kept (the usual dark map: invert, then turn the hues half way round), its
     * colour softened by saturation, its contrast scaled and lifted by lift (0 to 255), so the light land of a street
     * map is drawn near the panel's own dark grey instead of white. Pure arithmetic, so the test checks what a white and
     * a dark pixel become.
     */
    static float[] darkTiles(float saturation, float contrast, float lift) {
        // Hue turned 180 degrees (the CSS hue-rotate matrix at cos = -1, sin = 0).
        double[][] hue = {{-.574, 1.430, .144}, {.426, .430, .144}, {.426, 1.430, -.856}};
        // Saturation about the same luminance weights.
        double lr = .213, lg = .715, lb = .072, s = saturation;
        double[][] sat = {
                {lr + (1 - lr) * s, lg - lg * s, lb - lb * s},
                {lr - lr * s, lg + (1 - lg) * s, lb - lb * s},
                {lr - lr * s, lg - lg * s, lb + (1 - lb) * s}};
        float[] m = new float[20];
        for (int r = 0; r < 3; r++) {
            double offset = 0;
            for (int c = 0; c < 3; c++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += sat[r][k] * hue[k][c];
                // Inverted input (255 - c): the coefficient flips and 255 times it moves to the offset.
                m[r * 5 + c] = (float) (-v * contrast);
                offset += v * 255;
            }
            m[r * 5 + 4] = (float) (offset * contrast + lift);
        }
        m[18] = 1;
        return m;
    }

    /** A colour (0xRRGGBB) through a 4x5 colour matrix, clamped as Android clamps it, as 0xRRGGBB. */
    static int apply(float[] m, int rgb) {
        int[] in = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        int out = 0;
        for (int r = 0; r < 3; r++) {
            double v = m[r * 5 + 4];
            for (int c = 0; c < 3; c++) v += m[r * 5 + c] * in[c];
            out = (out << 8) | (int) Math.max(0, Math.min(255, Math.round(v)));
        }
        return out;
    }
}
