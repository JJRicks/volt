package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * The OpenStreetMap default, the rule that keeps USGS for basemaps approved under the old default, and the
 * one-time offer to leave USGS: every combination of basemap on or off, explained or not, provider saved or
 * not, answered or not, inside or outside USGS coverage, and the same settings after a real backup restore
 * (BackupArchive, BackupTransaction and BackupTestStore, as the backup suites use them). Synthesized places
 * only. MapTilesValidation (android-tests) runs MapSource.load itself against real preferences.
 */
public final class MapDefaultTest {
    private static int checks;
    private static final String OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    private static final String USGS = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}";

    public static void main(String[] args) throws Exception {
        defaultAndPin();
        offer();
        coverage();
        restores();
        System.out.println("MapDefaultTest: " + checks + " checks");
    }

    /** What MapSource.load decides for these app_settings (its first two steps, from the same MapDefault rule). */
    private static String provider(Map<String, Object> app) {
        boolean saved = app.containsKey(MapDefault.URL_KEY);
        if (MapDefault.keepsUsgs(saved, Boolean.TRUE.equals(app.get(MapDefault.ENABLED_KEY)), Boolean.TRUE.equals(app.get(MapDefault.EXPLAINED_KEY)))) return "usgs";
        if (!saved) return "osm";
        Object url = app.get(MapDefault.URL_KEY);
        return OSM.equals(url) ? "osm" : USGS.equals(url) ? "usgs" : "custom";
    }

    private static boolean offered(Map<String, Object> app, boolean inArea) {
        return MapDefault.offersSwitch(app.containsKey(MapDefault.URL_KEY), Boolean.TRUE.equals(app.get(MapDefault.ENABLED_KEY)),
                Boolean.TRUE.equals(app.get(MapDefault.EXPLAINED_KEY)), Boolean.TRUE.equals(app.get(MapDefault.SWITCH_ANSWERED_KEY)), inArea);
    }

    private static void defaultAndPin() {
        for (int mask = 0; mask < 8; mask++) {
            boolean saved = (mask & 1) != 0, enabled = (mask & 2) != 0, explained = (mask & 4) != 0;
            boolean pinned = MapDefault.keepsUsgs(saved, enabled, explained);
            String what = "saved=" + saved + " enabled=" + enabled + " explained=" + explained;
            if (saved) check(!pinned, "a saved provider is never replaced: " + what);
            else if (enabled || explained) check(pinned, "a basemap turned on or explained under the USGS default keeps USGS: " + what);
            else check(!pinned, "someone who never turned the basemap on gets OpenStreetMap: " + what);
        }
        Map<String, Object> app = new HashMap<>();
        check(provider(app).equals("osm"), "a new install defaults to OpenStreetMap");
        check(!Boolean.TRUE.equals(app.get(MapDefault.ENABLED_KEY)), "and the basemap stays off: no tile request until the person turns it on");
        app.put(MapDefault.URL_KEY, USGS); check(provider(app).equals("usgs"), "an explicit USGS choice stays");
        app.put(MapDefault.URL_KEY, OSM); app.put(MapDefault.ENABLED_KEY, true); check(provider(app).equals("osm"), "an explicit OpenStreetMap choice stays");
        app.put(MapDefault.URL_KEY, "https://tiles.example.test/{z}/{x}/{y}.png"); check(provider(app).equals("custom"), "a custom provider stays");
    }

    private static void offer() {
        for (int mask = 0; mask < 32; mask++) {
            boolean saved = (mask & 1) != 0, enabled = (mask & 2) != 0, explained = (mask & 4) != 0, answered = (mask & 8) != 0, inArea = (mask & 16) != 0;
            boolean expected = !saved && enabled && !answered && !inArea;
            check(MapDefault.offersSwitch(saved, enabled, explained, answered, inArea) == expected,
                    "offer only to a pinned, showing, unanswered basemap outside USGS: saved=" + saved + " enabled=" + enabled
                            + " explained=" + explained + " answered=" + answered + " inArea=" + inArea);
        }
        check(!MapDefault.offersSwitch(false, false, true, false, false), "explained but off: nothing to show, so no offer yet");
        check(MapDefault.offersSwitch(false, true, false, false, false), "on from a version before the explanation, no provider: offered");
    }

    private static void coverage() {
        double[][] inside = {
            {40.75, -73.99}, {34.05, -118.25}, {47.61, -122.33}, {25.77, -80.19}, {24.55, -81.80}, {44.80, -68.77},
            {61.22, -149.90}, {51.88, -176.65}, {52.93, 173.20}, {21.31, -157.86}, {19.72, -155.08},
            {18.47, -66.11}, {18.34, -64.93}, {13.47, 144.75}, {15.18, 145.75}, {-14.28, -170.70},
            {49.28, -123.12}, {43.65, -79.38}, {32.53, -117.04}}; // the last three: Canada and Mexico near the border count as covered
        for (double[] p : inside) check(MapDefault.inUsgsArea(p[0], p[1]), "covered by USGS (or near enough): " + Arrays.toString(p));
        double[][] outside = {
            {48.86, 2.29}, {52.36, 4.87}, {40.42, -3.70}, {52.52, 13.40}, {38.72, -9.14}, {59.33, 18.07}, {41.01, 28.98},
            {52.23, 21.01}, {45.46, 9.19}, {51.51, -0.13}, {-33.87, 151.21}, {35.68, 139.69}, {19.43, -99.13}, {-23.55, -46.63},
            {32.30, -64.78}, {53.55, -113.49}, {Double.NaN, Double.NaN}, {64.15, -21.94}};
        for (double[] p : outside) check(!MapDefault.inUsgsArea(p[0], p[1]), "outside USGS coverage: " + Arrays.toString(p));
        check(MapDefault.anyInUsgsArea(new double[0], new double[0]), "a map with no points never asks");
        check(!MapDefault.anyInUsgsArea(new double[]{48.85, 48.86, 48.87}, new double[]{2.29, 2.30, 2.31}), "a ride in Paris lies outside");
        check(MapDefault.anyInUsgsArea(new double[]{48.85, 40.75, 48.87}, new double[]{2.29, -73.99, 2.31}), "one point in the United States is enough to count as covered");
        check(!MapDefault.anyInUsgsArea(new double[]{48.85, 40.75}, new double[]{2.29}), "mismatched lengths read only whole pairs");
    }

    /** A backup made at one point, restored onto a phone in another state: the provider follows the backup. */
    private static void restores() throws Exception {
        File root = Files.createTempDirectory("map-default").toFile();
        try {
            // Made by an older version with the basemap on under the USGS default, restored onto a phone where the
            // person had since chosen OpenStreetMap: USGS again, as when the backup was made, and offered once.
            Map<String, Object> after = restore(root, "pinned", settings(true, true, null, null), settings(true, true, OSM, null));
            check(provider(after).equals("usgs") && offered(after, false) && !offered(after, true), "an older pinned backup restores USGS, and the offer outside the US only");
            // Made by an older version by someone who never turned the basemap on: OpenStreetMap, still off.
            after = restore(root, "never", settings(null, null, null, null), settings(true, true, USGS, true));
            check(provider(after).equals("osm") && !Boolean.TRUE.equals(after.get(MapDefault.ENABLED_KEY)) && !offered(after, false), "a never-used backup restores the default, off");
            // Explained but turned off under the old default: USGS, and no offer until it is turned on.
            after = restore(root, "explained-off", settings(false, true, null, null), settings(null, null, null, null));
            check(provider(after).equals("usgs") && !offered(after, false), "explained-but-off keeps USGS without an offer");
            // Made after the person answered Switch: OpenStreetMap, never asked again, whatever the phone held.
            after = restore(root, "answered-switch", settings(true, true, OSM, true), settings(true, true, null, null));
            check(provider(after).equals("osm") && !offered(after, false), "a backup made after Switch restores OpenStreetMap, never asked again");
            // Made after the person answered Keep USGS: USGS as their own choice, never asked again.
            after = restore(root, "answered-keep", settings(true, true, USGS, true), settings(true, true, null, null));
            check(provider(after).equals("usgs") && !offered(after, false), "a backup made after Keep USGS restores USGS, never asked again");
            // An explicit choice made by any version restores as that choice.
            after = restore(root, "explicit-osm", settings(true, true, OSM, null), settings(true, true, null, null));
            check(provider(after).equals("osm") && !offered(after, false), "an explicit OpenStreetMap choice restores as chosen");
            after = restore(root, "explicit-usgs", settings(false, true, USGS, null), settings(null, null, null, null));
            check(provider(after).equals("usgs") && !offered(after, false), "an explicit USGS choice restores as chosen");
            // Made before the person answered, restored after: the backup's state returns, so the offer can return.
            after = restore(root, "before-answer", settings(true, true, null, null), settings(true, true, OSM, true));
            check(provider(after).equals("usgs") && offered(after, false), "a backup from before the answer brings back that time's state");
            // A new install's basemap approved under this version saves its provider, so a backup of it is not pinned.
            after = restore(root, "approved-now", settings(true, true, OSM, null), settings(null, null, null, null));
            check(provider(after).equals("osm") && !MapDefault.keepsUsgs(true, true, true), "an approval saved with its provider is not pinned");
        } finally {
            AppDataArchive.deleteTree(root);
        }
    }

    private static Map<String, Object> settings(Boolean enabled, Boolean explained, String url, Boolean answered) {
        Map<String, Object> app = new LinkedHashMap<>();
        if (enabled != null) app.put(MapDefault.ENABLED_KEY, enabled);
        if (explained != null) app.put(MapDefault.EXPLAINED_KEY, explained);
        if (url != null) { app.put(MapDefault.URL_KEY, url); app.put("map_tile_attribution", "credit"); app.put("map_tile_attribution_url", "https://example.test/"); }
        if (answered != null) app.put(MapDefault.SWITCH_ANSWERED_KEY, answered);
        return app;
    }

    /** Backs up `backup`'s App preferences, restores them over `phone`'s and returns the phone's app_settings. */
    private static Map<String, Object> restore(File root, String name, Map<String, Object> backup, Map<String, Object> phone) throws Exception {
        BackupTestStore source = new BackupTestStore(new File(root, name + "-source"));
        for (Map.Entry<String, Object> e : backup.entrySet()) source.put("app_settings", e.getKey(), e.getValue());
        BackupTestStore target = new BackupTestStore(new File(root, name + "-phone"));
        for (Map.Entry<String, Object> e : phone.entrySet()) target.put("app_settings", e.getKey(), e.getValue());
        Set<String> chosen = Collections.singleton(BackupRegistry.PREFERENCES);
        BackupArchive.Plan plan = target.plan(target.stage(source.export(chosen)), chosen, false, null);
        BackupTransaction.apply(target, plan);
        Map<String, Object> app = target.preferences().get("app_settings");
        for (String key : new String[]{MapDefault.URL_KEY, MapDefault.ENABLED_KEY, MapDefault.EXPLAINED_KEY, MapDefault.SWITCH_ANSWERED_KEY})
            check(Objects.equals(app.get(key), backup.get(key)), name + ": " + key + " restores exactly as backed up, absent included");
        return app;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
