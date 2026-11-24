package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * The online maps' shared style (MapStyle, owner Nov 23 2026: a dark/light toggle on the ride map that also controls the
 * Just back card's map, remembered and kept): its three states and how each map resolves them in each theme, a flip
 * always saving a pick, when the switch shows, its place in App preferences, both maps drawing
 * the one tile treatment, and a real backup round trip (BackupArchive, BackupTransaction and BackupTestStore, as the
 * backup suites use them) of every state over every state.
 */
public final class MapStyleTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        states();
        flips();
        shown();
        registry();
        tiles();
        backups();
        System.out.println("MapStyleTest: " + checks + " checks");
    }

    /** Never picked follows the theme; a pick holds in both themes. The same answer for both maps (one resolver). */
    private static void states() {
        check(MapStyle.KEY.equals("map_style") && MapStyle.DARK.equals("dark") && MapStyle.LIGHT.equals("light"), "the key and its values");
        for (boolean theme : new boolean[]{true, false}) {
            check(MapStyle.dark(null, theme) == theme, "never picked: the maps follow the " + (theme ? "dark" : "light") + " theme");
            check(MapStyle.dark("dark", theme), "dark picked: dark in the " + (theme ? "dark" : "light") + " theme too");
            check(!MapStyle.dark("light", theme), "light picked: light in the " + (theme ? "dark" : "light") + " theme too");
            // Anything else (a hand-edited or future value, a wrong type) reads as never picked, never as a pick.
            for (Object odd : new Object[]{"", "DARK", "auto", "system", Boolean.TRUE, 1})
                check(MapStyle.dark(odd, theme) == theme && !MapStyle.picked(odd), "'" + odd + "' reads as never picked");
        }
        check(!MapStyle.picked(null) && MapStyle.picked("dark") && MapStyle.picked("light"), "only dark and light are picks");
    }

    /** A flip saves the other of what is shown, so a pick exists from the first flip on, in either theme. */
    private static void flips() {
        check(MapStyle.flipped(null, true).equals("light"), "the dark theme's dark map (never picked) flips to a light pick");
        check(MapStyle.flipped(null, false).equals("dark"), "the light theme's light map (never picked) flips to a dark pick");
        for (boolean theme : new boolean[]{true, false}) {
            check(MapStyle.flipped("dark", theme).equals("light") && MapStyle.flipped("light", theme).equals("dark"), "a pick flips to the other pick");
            // Flipped on the ride map, the Just back card shows the same: both resolve the saved value the same way.
            Object saved = MapStyle.flipped(null, theme);
            check(MapStyle.dark(saved, theme) == !theme && MapStyle.dark(saved, !theme) == !theme,
                    "after a flip in the " + (theme ? "dark" : "light") + " theme the pick holds when the theme changes");
        }
        check(MapStyle.pick(true).equals("dark") && MapStyle.pick(false).equals("light"), "a switch's state saves as a pick");
    }

    /** The switch shows only with an online map on (the ride maps' or, in Map settings, the card's). */
    private static void shown() {
        check(!MapStyle.shown(false, false), "no online map: no style switch");
        check(MapStyle.shown(true, false) && MapStyle.shown(false, true) && MapStyle.shown(true, true), "either basemap on: the switch shows");
    }

    private static void registry() {
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", MapStyle.KEY);
        check(rule != null && BackupRegistry.PREFERENCES.equals(rule.category) && rule.type == String.class && !rule.perProfile,
                "it travels in backups with App preferences, as a string for the whole app");
    }

    /** One tile treatment for both maps: the one the Just back card was tuned with (Nov 19 2026). */
    private static void tiles() {
        check(Arrays.equals(MapStyle.darkTiles(), JustBackBasemap.darkTiles(.4f, .85f, 20)), "the dark style is the Just back card's tuned treatment");
        int white = JustBackBasemap.apply(MapStyle.darkTiles(), 0xffffff);
        check(lightness(white) < 60, "a white tile draws dark: " + Integer.toHexString(white));
        // The ride map's dark paper under loading tiles sits near what a street map's land becomes, never light.
        int land = JustBackBasemap.apply(MapStyle.darkTiles(), 0xf2efe9), paper = 0x2a2b28;
        check(Math.abs(lightness(land) - lightness(paper)) < 24, "the dark paper is close to the dark land: " + Integer.toHexString(land));
    }

    private static void backups() throws Exception {
        File root = Files.createTempDirectory("map-style").toFile();
        try {
            // Each state restores exactly, never picked included, over each state on the phone.
            String[] states = {null, "dark", "light"};
            int n = 0;
            for (String backed : states) for (String phone : states) {
                Map<String, Object> backup = new LinkedHashMap<>(), here = new LinkedHashMap<>();
                if (backed != null) backup.put(MapStyle.KEY, backed);
                backup.put("map_basemap_enabled", true); backup.put(JustBackBasemap.KEY, true);
                if (phone != null) here.put(MapStyle.KEY, phone);
                Map<String, Object> after = restore(root, "states-" + n++, backup, here);
                check(Objects.equals(after.get(MapStyle.KEY), backed),
                        "the map style restores as backed up (" + backed + " over " + phone + "): " + after.get(MapStyle.KEY));
                for (boolean theme : new boolean[]{true, false})
                    check(MapStyle.dark(after.get(MapStyle.KEY), theme) == MapStyle.dark(backed, theme),
                            "after the restore both maps draw as they did where the backup was made (" + backed + ", " + (theme ? "dark" : "light") + " theme)");
                check(Boolean.TRUE.equals(after.get("map_basemap_enabled")) && Boolean.TRUE.equals(after.get(JustBackBasemap.KEY)),
                        "the basemaps restore beside it, untouched by it");
            }
        } finally {
            AppDataArchive.deleteTree(root);
        }
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
        return target.preferences().get("app_settings");
    }

    private static int lightness(int rgb) { return Math.round(.2126f * ((rgb >> 16) & 255) + .7152f * ((rgb >> 8) & 255) + .0722f * (rgb & 255)); }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
