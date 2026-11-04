package com.jjricks.volt;

import android.content.SharedPreferences;

/** Replaceable basemaps; choosing one never enables network loading by itself. */
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

    static MapSource load(SharedPreferences settings) {
        String url = settings.getString("map_tile_url", USGS_URL);
        if (!MapTiles.validUrl(url)) return USGS;
        MapSource preset = USGS_URL.equals(url) ? USGS : OSM_URL.equals(url) ? OSM : null;
        if (!settings.contains("map_tile_url")) return USGS;
        return new MapSource(preset == null ? "Custom map" : preset.name, url,
                settings.getString("map_tile_attribution", preset == null ? "Map provider" : preset.attribution),
                settings.getString("map_tile_attribution_url", preset == null ? url : preset.attributionUrl));
    }

    void save(SharedPreferences settings) {
        settings.edit().putString("map_tile_url", url).putString("map_tile_attribution", attribution)
                .putString("map_tile_attribution_url", attributionUrl).apply();
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
