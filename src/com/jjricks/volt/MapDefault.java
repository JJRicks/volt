package com.jjricks.volt;

/**
 * Which basemap someone gets when they never chose one, and when Volt offers once to move them off USGS.
 * Pure Java (no Android), so tests/l10n/MapDefaultTest runs exactly what the app runs.
 *
 * Volt 1.13 and earlier used USGS Topo (United States only) whenever no provider was saved. OpenStreetMap
 * is now that default. The one-time explanation approved a provider seeing the viewed map area, and the
 * map showed USGS, so someone who turned the basemap on (or approved the explanation) under the old
 * default, with no provider saved, keeps USGS rather than being moved to another provider silently.
 * Approving the explanation now saves the provider it was approved for, so this rule only ever matches
 * settings written by an older version, or restored from a backup an older version made.
 */
final class MapDefault {
    private MapDefault() { }

    /** app_settings keys. */
    static final String URL_KEY = "map_tile_url";
    static final String ENABLED_KEY = "map_basemap_enabled";
    static final String EXPLAINED_KEY = "map_basemap_explained";
    /** Set once the person answered the offer to leave USGS, whatever the answer; it travels in backups. */
    static final String SWITCH_ANSWERED_KEY = "map_usgs_switch_answered";

    /** True when no provider is saved and the basemap was on or explained: the old USGS default stays. */
    static boolean keepsUsgs(boolean providerSaved, boolean enabled, boolean explained) {
        return !providerSaved && (enabled || explained);
    }

    /**
     * The one-time "USGS covers only the United States" offer: only to someone keepsUsgs() keeps on USGS,
     * with the basemap showing, who has not answered it, and whose map lies wholly outside USGS coverage.
     * Someone who chose a provider, or gets OpenStreetMap as the default, is never asked.
     */
    static boolean offersSwitch(boolean providerSaved, boolean enabled, boolean explained, boolean answered, boolean mapInUsgsArea) {
        return keepsUsgs(providerSaved, enabled, explained) && enabled && !answered && !mapInUsgsArea;
    }

    /**
     * Generous boxes around the United States and its territories (south, west, north, east in degrees).
     * They also take in parts of Canada and Mexico: a doubtful place counts as covered, so Volt errs
     * toward not asking. Alaska's Aleutians cross 180°, so they have a box on each side.
     */
    private static final double[][] USGS_AREA = {
        {24.0, -125.5, 49.6, -66.5},     // the contiguous states
        {50.5, -180.0, 72.0, -129.0},    // Alaska
        {50.5, 172.0, 55.5, 180.0},      // the western Aleutians
        {18.5, -178.5, 28.5, -154.5},    // Hawaii, with the northwestern islands
        {17.5, -68.0, 18.8, -64.3},      // Puerto Rico and the U.S. Virgin Islands
        {13.0, 144.4, 20.7, 146.2},      // Guam and the Northern Mariana Islands
        {-14.8, -171.2, -10.9, -168.0},  // American Samoa
    };

    /** True when the point lies in a USGS box. Not-a-number never does. */
    static boolean inUsgsArea(double latitude, double longitude) {
        for (double[] box : USGS_AREA)
            if (latitude >= box[0] && latitude <= box[2] && longitude >= box[1] && longitude <= box[3]) return true;
        return false;
    }

    /** True when any of the points lies in a USGS box; a map with no points counts as covered (no offer). */
    static boolean anyInUsgsArea(double[] latitudes, double[] longitudes) {
        int count = Math.min(latitudes.length, longitudes.length);
        if (count == 0) return true;
        for (int i = 0; i < count; i++) if (inUsgsArea(latitudes[i], longitudes[i])) return true;
        return false;
    }
}
