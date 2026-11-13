package com.jjricks.volt;

import android.content.SharedPreferences;

/** Replaceable basemaps; choosing one never enables network loading by itself. OpenStreetMap is the default. */
final class MapSource {
    static final String USGS_URL = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}";
    static final String OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    static final MapSource USGS = new MapSource("USGS Topo · United States", USGS_URL,
            "U.S. Geological Survey, National Geospatial Program",
            "https://www.usgs.gov/faqs/what-are-terms-uselicensing-map-services-and-data-national-map");
    static final MapSource OSM = new MapSource("OpenStreetMap", OSM_URL,
            "© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright");

    final String name, url, attribution, attributionUrl;

    MapSource(String name, String url, String attribution, String attributionUrl) {
        this.name = name; this.url = url; this.attribution = attribution;
        this.attributionUrl = attributionUrl;
    }

    /**
     * The provider in use: the saved one, or with none saved, OpenStreetMap, except that someone who had
     * the basemap on or explained under the old USGS default keeps USGS (MapDefault.keepsUsgs).
     */
    static MapSource load(SharedPreferences settings) {
        if (pinnedToUsgs(settings)) return USGS;
        if (!settings.contains(MapDefault.URL_KEY)) return OSM;
        String url = settings.getString("map_tile_url", USGS_URL);
        // No version saves an address that fails this check; keep the fallback such a value always had.
        if (!MapTiles.validUrl(url)) return USGS;
        MapSource preset = USGS_URL.equals(url) ? USGS : OSM_URL.equals(url) ? OSM : null;
        return new MapSource(preset == null ? "Custom map" : preset.name, url,
                settings.getString("map_tile_attribution", preset == null ? "Map provider" : preset.attribution),
                settings.getString("map_tile_attribution_url", preset == null ? url : preset.attributionUrl));
    }

    /** True when no provider is saved and the old USGS default stays (MapDefault.keepsUsgs). */
    static boolean pinnedToUsgs(SharedPreferences settings) {
        return MapDefault.keepsUsgs(settings.contains(MapDefault.URL_KEY),
                settings.getBoolean(MapDefault.ENABLED_KEY, false), settings.getBoolean(MapDefault.EXPLAINED_KEY, false));
    }

    /**
     * Writes the old-default pin down once: while pinnedToUsgs holds, saves USGS as the provider, marked as the
     * old default (MapDefault.USGS_BY_DEFAULT_KEY), so the pin no longer depends on the basemap being on or
     * explained. Called wherever a map control is built and before the basemap is turned off. True when it wrote.
     */
    static boolean keepPin(SharedPreferences settings) {
        if (!pinnedToUsgs(settings)) return false;
        SharedPreferences.Editor pin = settings.edit();
        USGS.put(pin);
        pin.putBoolean(MapDefault.USGS_BY_DEFAULT_KEY, true);
        pin.apply();
        return true;
    }

    /** True when the provider is USGS by the old default, written down or not (MapDefault.usgsByDefault). */
    static boolean usgsByDefault(SharedPreferences settings) {
        return MapDefault.usgsByDefault(settings.contains(MapDefault.URL_KEY), USGS_URL.equals(settings.getString(MapDefault.URL_KEY, null)),
                settings.getBoolean(MapDefault.USGS_BY_DEFAULT_KEY, false), settings.getBoolean(MapDefault.ENABLED_KEY, false),
                settings.getBoolean(MapDefault.EXPLAINED_KEY, false));
    }

    void save(SharedPreferences settings) {
        SharedPreferences.Editor edit = settings.edit();
        put(edit);
        edit.apply();
    }

    /**
     * Adds this provider to an edit, so it can be saved in the same commit as other map settings. A provider
     * saved this way is the person's own choice, so it clears the old-default mark (keepPin sets it after).
     */
    void put(SharedPreferences.Editor edit) {
        edit.putString("map_tile_url", url).putString("map_tile_attribution", attribution)
                .putString("map_tile_attribution_url", attributionUrl);
        edit.remove(MapDefault.USGS_BY_DEFAULT_KEY);
    }

    /**
     * The name a screen shows, in the current language. name stays the English the presets and load()
     * give it; a source is a preset exactly when its address is the preset's, as in load().
     */
    String displayName() {
        return USGS_URL.equals(url) ? T.mapsSourceUsgs() : OSM_URL.equals(url) ? T.mapsSourceOsm() : T.mapsSourceCustom();
    }

    /** displayName() where room is short: the preset's name without its coverage ("USGS Topo"). */
    String shortName() {
        return USGS_URL.equals(url) ? T.mapsSourceUsgsShort() : OSM_URL.equals(url) ? T.mapsSourceOsm() : T.mapsSourceCustom();
    }

    /**
     * The credit a screen shows: the provider's own attribution, as saved, except load()'s English
     * stand-in for a custom provider with none saved, which is shown in the current language.
     */
    String displayAttribution() {
        return Msg.of(T.MAPS_SOURCE_PROVIDER_FALLBACK).english().equals(attribution) ? T.mapsSourceProviderFallback() : attribution;
    }

    static int maximumTileZoom(String url) { return USGS_URL.equals(url) ? 16 : 19; }
}
