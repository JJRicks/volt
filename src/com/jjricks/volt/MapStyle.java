package com.jjricks.volt;

/**
 * The online map's style, dark or light (owner, Nov 23 2026: "Dark mode map is awesome, can we have a toggle for that on
 * the ride recording map itself as well? Remember and save what the user picked last and keep it. Then that toggle could
 * control both the 'just back' and ride recording maps together"). One setting for both maps: the ride maps (RouteMapView,
 * inline and expanded) and Clean Home's Just back card (JustBackView).
 *
 * <p>Three states, stored under KEY: never picked (absent), which follows the app's theme, dark in the dark theme and
 * light in the light one, as the Just back card always did; and a pick, dark or light, kept in either theme until the
 * next flip. A flip always saves a pick, the other of what the maps show. Any other stored value reads as never picked.
 * The setting travels in backups with App preferences (BackupRegistry).
 *
 * <p>Pure Java (no Android), so tests/l10n/MapStyleTest runs what the maps run.
 */
final class MapStyle {
    private MapStyle() { }

    /** app_settings key: "dark" or "light" once picked; absent while the maps follow the app's theme. */
    static final String KEY = "map_style";
    static final String DARK = "dark", LIGHT = "light";

    /**
     * The dark style's tile treatment (JustBackBasemap.darkTiles): lightness inverted, colour softened to 40%, contrast
     * at 85% and lifted so a street map's land sits just above the dark panel's grey. Chosen from renders of a light street
     * map on the Just back card's dark panel against plain dimming, which left a flat mid-grey map as bright as the text
     * (Nov 19 2026); both maps draw it, so they look the same.
     */
    static final float DARK_SATURATION = .4f, DARK_CONTRAST = .85f, DARK_LIFT = 20;

    /** The dark style's colour matrix (android.graphics.ColorMatrix, row-major 4x5). */
    static float[] darkTiles() { return JustBackBasemap.darkTiles(DARK_SATURATION, DARK_CONTRAST, DARK_LIFT); }

    /** Whether the stored value is a pick (dark or light) rather than following the theme. */
    static boolean picked(Object stored) { return DARK.equals(stored) || LIGHT.equals(stored); }

    /** Whether the maps draw dark tiles: the pick when there is one, else the app's theme (darkTheme). */
    static boolean dark(Object stored, boolean darkTheme) {
        if (DARK.equals(stored)) return true;
        if (LIGHT.equals(stored)) return false;
        return darkTheme;
    }

    /** The value a flip saves: the other of what the maps show now, so a pick is kept from then on. */
    static String flipped(Object stored, boolean darkTheme) { return dark(stored, darkTheme) ? LIGHT : DARK; }

    /** The value a switch set to dark (or not) saves: always a pick. */
    static String pick(boolean dark) { return dark ? DARK : LIGHT; }

    /**
     * Whether the style switch shows: only while an online map is on (the ride maps' map_basemap_enabled, or for Map
     * settings also the Just back card's own), since the style changes nothing without one.
     */
    static boolean shown(boolean rideMaps, boolean card) { return rideMaps || card; }
}
