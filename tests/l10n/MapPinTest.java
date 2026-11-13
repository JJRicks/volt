package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Writing the old-default USGS pin down once (MapSource.keepPin, MapDefault.usgsByDefault): every combination of
 * provider saved (none, OpenStreetMap, USGS, custom), basemap on/off/absent, explained or not, marked as the old
 * default or not, answered or not; the pin written, then the basemap turned off and on again; and backups made
 * before and after the write, restored onto a fresh install and onto one with its own choice (BackupArchive,
 * BackupTransaction and BackupTestStore, as MapDefaultTest uses them). The settings are app_settings maps; the
 * steps mirror MapSource.keepPin, MapSource.put and the basemap controls, and MapTilesValidation runs the real
 * MapSource against real preferences. Synthesized values only.
 */
public final class MapPinTest {
    private static int checks;
    private static final String OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    private static final String USGS = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}";
    private static final String CUSTOM = "https://tiles.example.test/{z}/{x}/{y}.png";

    public static void main(String[] args) throws Exception {
        combinations();
        oldRuleLostThePin();
        restores();
        System.out.println("MapPinTest: " + checks + " checks");
    }

    private static boolean on(Map<String, Object> app, String key) { return Boolean.TRUE.equals(app.get(key)); }

    /** MapSource.load: the unwritten pin, else the saved provider, else OpenStreetMap. */
    private static String provider(Map<String, Object> app) {
        boolean saved = app.containsKey(MapDefault.URL_KEY);
        if (MapDefault.keepsUsgs(saved, on(app, MapDefault.ENABLED_KEY), on(app, MapDefault.EXPLAINED_KEY))) return "usgs";
        if (!saved) return "osm";
        Object url = app.get(MapDefault.URL_KEY);
        return OSM.equals(url) ? "osm" : USGS.equals(url) ? "usgs" : "custom";
    }

    /** MapSource.usgsByDefault. */
    private static boolean byDefault(Map<String, Object> app) {
        return MapDefault.usgsByDefault(app.containsKey(MapDefault.URL_KEY), USGS.equals(app.get(MapDefault.URL_KEY)),
                on(app, MapDefault.USGS_BY_DEFAULT_KEY), on(app, MapDefault.ENABLED_KEY), on(app, MapDefault.EXPLAINED_KEY));
    }

    /** offerOsmSwitch's check. */
    private static boolean offered(Map<String, Object> app, boolean inArea) {
        return MapDefault.asksToLeaveUsgs(byDefault(app), on(app, MapDefault.ENABLED_KEY), on(app, MapDefault.SWITCH_ANSWERED_KEY), inArea);
    }

    /** MapSource.keepPin. */
    private static boolean keepPin(Map<String, Object> app) {
        if (!MapDefault.keepsUsgs(app.containsKey(MapDefault.URL_KEY), on(app, MapDefault.ENABLED_KEY), on(app, MapDefault.EXPLAINED_KEY))) return false;
        put(app, USGS);
        app.put(MapDefault.USGS_BY_DEFAULT_KEY, true);
        return true;
    }

    /** MapSource.put: a provider saved as the person's own choice. */
    private static void put(Map<String, Object> app, String url) {
        app.put(MapDefault.URL_KEY, url); app.put("map_tile_attribution", "credit"); app.put("map_tile_attribution_url", "https://example.test/");
        app.remove(MapDefault.USGS_BY_DEFAULT_KEY);
    }

    /** The basemap switch turned off (BasemapControl, ProviderControl's Off): the pin is written first. */
    private static void turnOff(Map<String, Object> app) { keepPin(app); app.put(MapDefault.ENABLED_KEY, false); }

    /** Turned on: directly once explained, else through the explanation, whose approval saves the named provider when none is saved. */
    private static void turnOn(Map<String, Object> app) {
        if (!on(app, MapDefault.EXPLAINED_KEY)) {
            String named = provider(app);
            if (!app.containsKey(MapDefault.URL_KEY)) put(app, named.equals("usgs") ? USGS : OSM);
            app.put(MapDefault.EXPLAINED_KEY, true);
        }
        app.put(MapDefault.ENABLED_KEY, true);
    }

    private static void combinations() {
        String[] urls = {null, OSM, USGS, CUSTOM};
        Boolean[] flags = {null, false, true};
        int cases = 0;
        for (String url : urls) for (Boolean enabled : flags) for (Boolean explained : flags) for (Boolean mark : flags) for (Boolean answered : flags) {
            Map<String, Object> app = settings(enabled, explained, url, answered, mark);
            String what = app.toString();
            String before = provider(app);
            boolean pinned = byDefault(app), outside = offered(app, false), inside = offered(app, true);
            boolean legacy = MapDefault.keepsUsgs(url != null, Boolean.TRUE.equals(enabled), Boolean.TRUE.equals(explained));
            // Who is on USGS by the old default: the unwritten pin, or a saved USGS with the mark. Nobody else.
            check(pinned == (url == null ? legacy : USGS.equals(url) && Boolean.TRUE.equals(mark)), "by default exactly when pinned or marked: " + what);
            check(!inside, "never asked inside USGS coverage: " + what);
            check(outside == (pinned && Boolean.TRUE.equals(enabled) && !Boolean.TRUE.equals(answered)), "asked outside only when pinned, showing and unanswered: " + what);
            if (USGS.equals(url) && !Boolean.TRUE.equals(mark)) check(!outside, "someone who chose USGS is never asked: " + what);
            // Writing the pin down changes nothing anyone sees or is asked.
            boolean wrote = keepPin(app);
            check(wrote == legacy, "the pin is written exactly when the old rule applies: " + what);
            check(provider(app).equals(before) && byDefault(app) == pinned && offered(app, false) == outside && !offered(app, true), "writing the pin changes nothing: " + what);
            check(!keepPin(app), "written once: " + what);
            // Off, then on again: the same provider, still pinned (or not), the same question.
            turnOff(app);
            check(provider(app).equals(before), "turning the basemap off keeps the provider: " + what);
            turnOn(app);
            check(provider(app).equals(before), "turning it on again keeps the provider: " + what);
            check(byDefault(app) == pinned, "and who is on USGS by the old default: " + what);
            check(offered(app, false) == (pinned && !Boolean.TRUE.equals(answered)) && !offered(app, true), "and the question outside the US, once: " + what);
            // Someone who chooses USGS themselves, pinned or not, is never asked from then on.
            put(app, USGS);
            check(provider(app).equals("usgs") && !byDefault(app) && !offered(app, false), "an own USGS choice clears the mark: " + what);
            cases++;
        }
        check(cases == 4 * 3 * 3 * 3 * 3, "every combination ran");
    }

    /** The legacy state #421's review found: on, never explained, no provider. Without the write, off and on lost USGS. */
    private static void oldRuleLostThePin() {
        Map<String, Object> app = settings(true, null, null, null, null);
        Map<String, Object> unwritten = new LinkedHashMap<>(app);
        unwritten.put(MapDefault.ENABLED_KEY, false); // off without writing the pin, as #421 did
        check(provider(unwritten).equals("osm"), "(the old behaviour) off without the write lost the pin");
        turnOff(app); turnOn(app);
        check(provider(app).equals("usgs") && byDefault(app) && offered(app, false), "with the write: still USGS, still asked once outside the US");
        app.put(MapDefault.SWITCH_ANSWERED_KEY, true); put(app, USGS); // Keep USGS
        turnOff(app); turnOn(app);
        check(provider(app).equals("usgs") && !offered(app, false), "after Keep USGS: USGS, never asked again");
    }

    /** Backups made before and after the write, restored onto a fresh install and onto one with its own choice. */
    private static void restores() throws Exception {
        File root = Files.createTempDirectory("map-pin").toFile();
        try {
            Map<String, Object> fresh = settings(null, null, null, null, null);
            Map<String, Object> ownChoice = settings(true, true, OSM, true, null);
            Map<String, Object> pinnedPhone = settings(true, true, USGS, null, true);
            // Before the write: on, never explained, no provider (a backup from 1.13.1 or earlier).
            Map<String, Object> before = settings(true, null, null, null, null);
            for (Map<String, Object> phone : Arrays.asList(fresh, ownChoice, pinnedPhone)) {
                Map<String, Object> app = restore(root, "before", before, phone);
                check(provider(app).equals("usgs") && byDefault(app) && offered(app, false) && !offered(app, true), "a backup from before the write keeps USGS by default, asked outside: " + phone);
                check(keepPin(app) && USGS.equals(app.get(MapDefault.URL_KEY)) && on(app, MapDefault.USGS_BY_DEFAULT_KEY), "and the pin is written on the first map: " + phone);
                turnOff(app); turnOn(app);
                check(provider(app).equals("usgs") && offered(app, false), "and survives off and on: " + phone);
            }
            // After the write, basemap on and off.
            for (Boolean enabled : new Boolean[]{true, false}) {
                Map<String, Object> after = settings(enabled, true, USGS, null, true);
                for (Map<String, Object> phone : Arrays.asList(fresh, ownChoice, pinnedPhone)) {
                    Map<String, Object> app = restore(root, "after-" + enabled, after, phone);
                    check(provider(app).equals("usgs") && byDefault(app) && offered(app, false) == enabled && !keepPin(app), "a backup from after the write restores the marked pin: " + enabled + " " + phone);
                    turnOff(app); turnOn(app);
                    check(provider(app).equals("usgs") && offered(app, false), "and is asked once it shows: " + enabled + " " + phone);
                }
            }
            // Made after Keep USGS (the answer saves USGS as their own choice): never asked again, wherever restored.
            Map<String, Object> kept = settings(true, true, USGS, true, null);
            for (Map<String, Object> phone : Arrays.asList(fresh, pinnedPhone)) {
                Map<String, Object> app = restore(root, "kept", kept, phone);
                check(provider(app).equals("usgs") && !byDefault(app) && !offered(app, false), "a backup made after Keep USGS is never asked: " + phone);
            }
            // An own USGS choice restored over a marked pin: the phone's mark does not survive the restore.
            Map<String, Object> app = restore(root, "own-usgs", settings(true, true, USGS, null, null), pinnedPhone);
            check(provider(app).equals("usgs") && !byDefault(app) && !offered(app, false), "an own USGS choice restores as chosen, over a phone's mark");
            // Never used: OpenStreetMap, off, nothing written.
            app = restore(root, "never", settings(null, null, null, null, null), pinnedPhone);
            check(provider(app).equals("osm") && !keepPin(app) && !offered(app, false), "a never-used backup restores the default, off, unpinned");
        } finally {
            AppDataArchive.deleteTree(root);
        }
    }

    private static Map<String, Object> settings(Boolean enabled, Boolean explained, String url, Boolean answered, Boolean mark) {
        Map<String, Object> app = new LinkedHashMap<>();
        if (enabled != null) app.put(MapDefault.ENABLED_KEY, enabled);
        if (explained != null) app.put(MapDefault.EXPLAINED_KEY, explained);
        if (url != null) { app.put(MapDefault.URL_KEY, url); app.put("map_tile_attribution", "credit"); app.put("map_tile_attribution_url", "https://example.test/"); }
        if (answered != null) app.put(MapDefault.SWITCH_ANSWERED_KEY, answered);
        if (mark != null) app.put(MapDefault.USGS_BY_DEFAULT_KEY, mark);
        return app;
    }

    /** Backs up `backup`'s App preferences, restores them over `phone`'s and returns the phone's app_settings (a copy). */
    private static Map<String, Object> restore(File root, String name, Map<String, Object> backup, Map<String, Object> phone) throws Exception {
        name = name + "-" + checks;
        BackupTestStore source = new BackupTestStore(new File(root, name + "-source"));
        for (Map.Entry<String, Object> e : backup.entrySet()) source.put("app_settings", e.getKey(), e.getValue());
        BackupTestStore target = new BackupTestStore(new File(root, name + "-phone"));
        for (Map.Entry<String, Object> e : phone.entrySet()) target.put("app_settings", e.getKey(), e.getValue());
        Set<String> chosen = Collections.singleton(BackupRegistry.PREFERENCES);
        BackupArchive.Plan plan = target.plan(target.stage(source.export(chosen)), chosen, false, null);
        BackupTransaction.apply(target, plan);
        Map<String, Object> app = new LinkedHashMap<>(target.preferences().get("app_settings"));
        for (String key : new String[]{MapDefault.URL_KEY, MapDefault.ENABLED_KEY, MapDefault.EXPLAINED_KEY, MapDefault.SWITCH_ANSWERED_KEY, MapDefault.USGS_BY_DEFAULT_KEY})
            check(Objects.equals(app.get(key), backup.get(key)), name + ": " + key + " restores exactly as backed up, absent included");
        return app;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
