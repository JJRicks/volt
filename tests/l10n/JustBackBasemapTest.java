package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Clean Home's Just back basemap (owner, Nov 19 2026: its own preference, off by default, remembered "even in app
 * backups"): the key and its default, its place in App preferences and the diagnostics settings list, a real backup
 * round trip (BackupArchive, BackupTransaction and BackupTestStore, as the backup suites use them) that keeps it apart
 * from the ride maps' map_basemap_enabled, the restore preview's online map line, and the
 * card's map geometry: the route's Web Mercator box, the scale its outline gives, the tile zoom, the corner the switch
 * takes, and the dark theme's tile colours. Synthesized places only.
 */
public final class JustBackBasemapTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        preference();
        backups();
        geometry();
        corner();
        darkTiles();
        System.out.println("JustBackBasemapTest: " + checks + " checks");
    }

    private static void preference() {
        check(JustBackBasemap.KEY.equals("just_back_basemap"), "the key");
        check(!JustBackBasemap.on(null), "absent is off: Home shows no map until someone asks for it");
        check(!JustBackBasemap.on(false) && JustBackBasemap.on(true), "a saved choice is kept");
        check(!JustBackBasemap.on("true") && !JustBackBasemap.on(1), "only a real switch value turns it on");
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", JustBackBasemap.KEY);
        check(rule != null && BackupRegistry.PREFERENCES.equals(rule.category) && rule.type == Boolean.class && !rule.perProfile,
                "it travels in backups with App preferences, as a switch for the whole app");
        int ours = DiagnosticsSettings.KEYS.indexOf(JustBackBasemap.KEY), maps = DiagnosticsSettings.KEYS.indexOf("map_basemap_enabled");
        check(ours >= 0 && ours == maps + 1, "the diagnostics settings list it next to map_basemap_enabled");
        // The online map's summary (About, Your data; App preferences, Map) never says Off while the card fetches tiles.
        check(JustBackBasemap.summary(false, false, "OSM", "Off", "Home").equals("Off"), "neither basemap on: Off");
        check(JustBackBasemap.summary(true, false, "OSM", "Off", "Home").equals("OSM") && JustBackBasemap.summary(true, true, "USGS", "Off", "Home").equals("USGS"),
                "the ride maps' basemap on: the provider, as before");
        check(JustBackBasemap.summary(false, true, "OSM", "Off", "Home").equals("OSM · Home"), "only the card's basemap on: the provider and Home, not Off");
    }

    private static void backups() throws Exception {
        File root = Files.createTempDirectory("just-back-basemap").toFile();
        try {
            // Each state of the card's switch restores exactly, absent included, over each state on the phone, and the ride
            // maps' switch restores as its own backed-up value, never pulled along by the card's.
            Boolean[] states = {null, true, false};
            int n = 0;
            for (Boolean backed : states) for (Boolean phone : states) for (Boolean maps : new Boolean[]{null, false, true}) {
                Map<String, Object> backup = new LinkedHashMap<>(), here = new LinkedHashMap<>();
                if (backed != null) backup.put(JustBackBasemap.KEY, backed);
                if (maps != null) backup.put("map_basemap_enabled", maps);
                if (phone != null) here.put(JustBackBasemap.KEY, phone);
                here.put("map_basemap_enabled", maps == null || !maps);
                Restored after = restore(root, "states-" + n++, backup, here);
                check(Objects.equals(after.app.get(JustBackBasemap.KEY), backed),
                        "the card's basemap restores as backed up (" + backed + " over " + phone + "): " + after.app.get(JustBackBasemap.KEY));
                check(Objects.equals(after.app.get("map_basemap_enabled"), maps),
                        "the ride maps' basemap restores as its own value (" + maps + "), whatever the card's: " + after.app.get("map_basemap_enabled"));
                boolean online = Boolean.TRUE.equals(backed) || Boolean.TRUE.equals(maps);
                check(after.lines.contains("Online map: on") == online,
                        "the restore preview says the online map is on exactly when either basemap will be (" + backed + ", " + maps + "): " + after.lines);
            }
            // Turned on from the card only: the explanation's approval and the card's switch, with the ride maps still off.
            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put(JustBackBasemap.KEY, true); backup.put("map_basemap_explained", true); backup.put("map_basemap_enabled", false);
            Restored after = restore(root, "card-only", backup, new LinkedHashMap<>());
            check(Boolean.TRUE.equals(after.app.get(JustBackBasemap.KEY)) && Boolean.TRUE.equals(after.app.get("map_basemap_explained"))
                    && Boolean.FALSE.equals(after.app.get("map_basemap_enabled")), "a card-only basemap comes back card-only, approved: " + after.app);
        } finally {
            AppDataArchive.deleteTree(root);
        }
    }

    private static final class Restored {
        final Map<String, Object> app; final List<String> lines;
        Restored(Map<String, Object> app, List<String> lines) { this.app = app; this.lines = lines; }
    }

    /** Backs up `backup`'s App preferences, restores them over `phone`'s and returns the phone's app_settings and the preview. */
    private static Restored restore(File root, String name, Map<String, Object> backup, Map<String, Object> phone) throws Exception {
        BackupTestStore source = new BackupTestStore(new File(root, name + "-source"));
        for (Map.Entry<String, Object> e : backup.entrySet()) source.put("app_settings", e.getKey(), e.getValue());
        BackupTestStore target = new BackupTestStore(new File(root, name + "-phone"));
        for (Map.Entry<String, Object> e : phone.entrySet()) target.put("app_settings", e.getKey(), e.getValue());
        Set<String> chosen = Collections.singleton(BackupRegistry.PREFERENCES);
        BackupArchive.Plan plan = target.plan(target.stage(source.export(chosen)), chosen, false, null);
        List<String> lines = new ArrayList<>();
        for (CharSequence line : plan.lines) lines.add(line.toString());
        BackupTransaction.apply(target, plan);
        return new Restored(target.preferences().get("app_settings"), lines);
    }

    private static void geometry() {
        // A synthetic loop about 3 km across, its outline as RidesScreen.readThumb projects it (longitude times the cosine
        // of the mean latitude, latitude down, both sides scaled so the longer is 1).
        int count = 64;
        double[] lat = new double[count], lon = new double[count];
        for (int i = 0; i < count; i++) {
            double t = i / (count - 1.0) * 2 * Math.PI;
            lat[i] = 45.52 + 0.010 * Math.sin(t) + 0.003 * Math.sin(3 * t);
            lon[i] = -122.68 + 0.020 * (1 - Math.cos(t)) - 0.004 * Math.sin(2 * t);
        }
        float[] outline = outline(lat, lon);
        double[] box = JustBackBasemap.box(lat, lon);
        check(box != null && box[0] < box[2] && box[1] < box[3], "the box runs west to east and north to south: " + Arrays.toString(box));
        check(box[0] > 0.15 && box[2] < 0.17 && box[1] > 0.35 && box[3] < 0.36, "the box is where the ride is: " + Arrays.toString(box));
        // The two sides give the same scale to within 1%: the outline lies on the map's tiles.
        float scale = 300;
        double across = outline[0] * scale / (box[2] - box[0]), down = outline[1] * scale / (box[3] - box[1]);
        check(Math.abs(across - down) / down < .01, "the outline's two sides agree with the map's scale: " + across + " vs " + down);
        double world = JustBackBasemap.world(outline, box, scale, 672);
        check(close(world, (across + down) / 2, 1e-6), "the world size is their mean");
        int zoom = JustBackBasemap.tileZoom(world, 672, 19);
        double exact = Math.log(world / 672) / Math.log(2);
        check(zoom == (int) Math.floor(exact) && zoom >= 10 && zoom <= 14, "a 3 km loop on a 300 px route takes a street zoom: " + zoom + " (" + exact + ")");
        check(JustBackBasemap.tileZoom(672 * 1024, 672, 19) == 10, "an exact zoom is that zoom");
        check(JustBackBasemap.tileZoom(672 * Math.pow(2, 18.5), 672, 16) == 16, "never beyond the provider's deepest zoom (USGS 16), its tiles drawn larger");
        check(JustBackBasemap.tileZoom(672 * Math.pow(2, 22.5), 672, 19) == -1, "a route a few metres across draws no map rather than a blur");
        check(JustBackBasemap.tileZoom(300, 672, 19) == 0, "a ride across a continent takes the whole-world tile");
        check(JustBackBasemap.tileZoom(0, 672, 19) == -1 && JustBackBasemap.tileZoom(Double.NaN, 672, 19) == -1, "no world size, no map");
        // One place (a ride whose fixes never moved): the outline has no extent; zoom 16 around it.
        float[] still = {0, 0, 0, 0};
        double[] point = JustBackBasemap.box(new double[]{45.5}, new double[]{-122.6});
        check(point[0] == point[2] && point[1] == point[3], "a single place has an empty box");
        check(JustBackBasemap.world(still, point, 0, 672) == JustBackBasemap.singlePlaceWorld(672)
                && JustBackBasemap.tileZoom(JustBackBasemap.singlePlaceWorld(672), 672, 19) == 16, "a single place shows zoom 16 around it");
        // A ride due north: only the height gives the scale.
        float[] line = {0, 1, 0, 0, 0, 1};
        double[] north = JustBackBasemap.box(new double[]{45.50, 45.53}, new double[]{-122.6, -122.6});
        check(close(JustBackBasemap.world(line, north, 200, 672), 200 / (north[3] - north[1]), 1e-6), "a ride with no width takes its scale from its height");
        // Across the 180th meridian: east of west, the short way round.
        double[] dateline = JustBackBasemap.box(new double[]{-17, -17.01}, new double[]{179.99, -179.99});
        check(dateline[2] > dateline[0] && dateline[2] - dateline[0] < 1e-4, "a ride across the 180th meridian keeps its short width: " + Arrays.toString(dateline));
        check(JustBackBasemap.box(new double[0], new double[0]) == null, "no fixes, no box");
        check(close(JustBackBasemap.x(0), .5, 1e-12) && close(JustBackBasemap.y(0), .5, 1e-12) && JustBackBasemap.y(89.9) == JustBackBasemap.y(85.05112878) && Math.abs(JustBackBasemap.y(89.9)) < 1e-9
                        && Math.abs(JustBackBasemap.y(-89.9) - 1) < 1e-9,
                "Web Mercator as the ride maps draw it, the poles clamped");
    }

    private static void corner() {
        // A 320x120 px panel, its switch's 48 px target in the bottom right corner.
        float[] corner = {272, 72, 320, 120};
        float[] across = {1, .2f, 0, 0, 1, .2f};
        // From (10, 40) to (310, 100): the end dot inside the corner.
        check(JustBackBasemap.touches(across, 10, 40, 300, corner, 7), "an end dot under the switch is caught");
        // From (10, 10) to (200, 48): wholly clear, also with the dot's ring.
        check(!JustBackBasemap.touches(across, 10, 10, 190, corner, 7), "a route clear of the corner is left where it is");
        // A stretch that crosses the corner with both fixes outside it.
        // From (200, 50) to (300, 130): both fixes outside the corner, the stretch between them through it.
        float[] diagonal = {1, .8f, 0, .5f, 1, 1.3f};
        check(JustBackBasemap.touches(diagonal, 200, 0, 100, corner, 0), "a stretch crossing the switch counts, not only its fixes");
        check(JustBackBasemap.touches(across, 10, 40, 300, corner, 0), "without the ring the dot's own place still counts");
        // The ring: a dot 5 px left of the switch is too close; 9 px is clear.
        float[] dot = {0, 0, 0, 0};
        check(JustBackBasemap.touches(dot, 267, 100, 1, corner, 7) && !JustBackBasemap.touches(dot, 263, 100, 1, corner, 7), "the dot's ring is kept clear too");
        check(!JustBackBasemap.touches(null, 0, 0, 1, corner, 7) && !JustBackBasemap.touches(new float[]{0, 0}, 0, 0, 1, corner, 7), "no route, nothing under the switch");
    }

    private static void darkTiles() {
        float[] m = JustBackBasemap.darkTiles(.4f, .85f, 20);
        check(m.length == 20 && m[18] == 1 && m[15] == 0 && m[16] == 0 && m[17] == 0 && m[19] == 0, "alpha passes through");
        int white = JustBackBasemap.apply(m, 0xffffff), land = JustBackBasemap.apply(m, 0xf2efe9), black = JustBackBasemap.apply(m, 0x000000);
        int panel = 0x1f201e;
        check(lightness(white) < lightness(panel) + 12 && lightness(land) < lightness(panel) + 20,
                "a street map's white and its light land are drawn about as dark as the dark panel, never white: "
                        + Integer.toHexString(white) + ", " + Integer.toHexString(land));
        check(lightness(black) > 180, "its dark lines and labels come out light: " + Integer.toHexString(black));
        int water = JustBackBasemap.apply(m, 0xaad3df);
        check(((water & 255) > ((water >> 16) & 255)) && lightness(water) < 110, "water stays bluish and dark: " + Integer.toHexString(water));
        int grey = JustBackBasemap.apply(JustBackBasemap.darkTiles(1, 1, 0), 0x808080);
        check(Math.abs(lightness(grey) - 127) <= 2, "with no softening, mid grey stays mid grey: " + Integer.toHexString(grey));
    }

    /** The outline RidesScreen.readThumb gives these fixes, all of them kept. */
    private static float[] outline(double[] lat, double[] lon) {
        int count = lat.length;
        double mean = 0; for (double v : lat) mean += v; mean /= count;
        double cos = Math.max(.05, Math.cos(Math.toRadians(mean)));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double[] xs = new double[count], ys = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = lon[i] * cos; ys[i] = -lat[i];
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        double span = Math.max(maxX - minX, maxY - minY);
        float[] result = new float[2 + 2 * count];
        result[0] = (float) ((maxX - minX) / span); result[1] = (float) ((maxY - minY) / span);
        for (int i = 0; i < count; i++) { result[2 + 2 * i] = (float) ((xs[i] - minX) / span); result[3 + 2 * i] = (float) ((ys[i] - minY) / span); }
        return result;
    }

    private static int lightness(int rgb) { return Math.round(.2126f * ((rgb >> 16) & 255) + .7152f * ((rgb >> 8) & 255) + .0722f * (rgb & 255)); }

    private static boolean close(double a, double b, double tolerance) { return Math.abs(a - b) <= tolerance; }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
