package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * HomeReadings: the overview card's two readings (Settings › Home style, owner Nov 19 2026): the catalogue, the defaults,
 * each slot's choice, the fallback for a stored reading a slot can't hold, the Widgets card's follow rule, the report
 * diagnostics' settings, and a real encrypted backup round trip (exact restore, and a merge onto another scooter). All
 * synthetic.
 */
public final class HomeReadingsTest {
    private static int checks;
    private static final String A = BackupTestStore.A, B = BackupTestStore.B, C = BackupArchiveTest.C;
    private static final String ODO = HomeReadings.ODOMETER, MODE = HomeReadings.DRIVE_MODE;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        catalog();
        defaults();
        choicePerSlot();
        fallback();
        follows();
        stockPairOnOwnCard();
        diagnostics();
        backup();
        System.out.println("HomeReadingsTest: " + checks + " checks passed (catalogue, defaults, each slot, fallback, Widgets follow rule, diagnostics, backup round trip)");
    }

    /**
     * A Widgets card with its own readings given the stock pair (Odometer, then Drive mode) in its Readings sheet: that
     * pair means "follow Home style", so Home style's two go back to the stock pair and the card shows what was picked.
     */
    private static void stockPairOnOwnCard() {
        List<String> homePair = Arrays.asList("voltage_v", "power_w");
        List<String> stockPick = Arrays.asList(ODO, MODE);
        check(HomeReadings.resetsHome(stockPick, homePair), "the stock pair picked on a card puts Home style's two back to the stock pair");
        check(!HomeReadings.resetsHome(stockPick, HomeReadings.DEFAULTS), "nothing to put back while Home style's two are the stock pair");
        check(!HomeReadings.resetsHome(Arrays.asList(MODE, ODO), homePair), "Drive mode then Odometer is the card's own list, Home style's two stay");
        check(!HomeReadings.resetsHome(Arrays.asList(ODO, MODE, "voltage_v"), homePair), "three readings are the card's own list");
        check(!HomeReadings.resetsHome(Collections.singletonList(ODO), homePair), "Odometer alone is the card's own list");
        check(!HomeReadings.resetsHome(homePair, homePair), "any other pair is the card's own list");
        check(HomeReadings.followsHome(stockPick) && HomeReadings.load(key -> null, "p1").equals(stockPick),
                "after the reset the card follows Home style, whose two are now the pick");
    }

    private static void catalog() {
        List<String> all = HomeReadings.catalog();
        for (String key : new String[]{ODO, MODE, "voltage_v", "current_a", "power_w", "temperature_c", "speed_kmh", "trip_km",
                "trip_minutes", "average_speed_kmh", "maximum_speed_kmh", "battery_health_percent", "charge_cycles"})
            check(all.contains(key) && HomeReadings.offered(key), "a slot can hold " + key);
        for (String key : new String[]{"battery_percent", "remaining_range_km", "stored_trips", "high_temperature_duration",
                "battery_manufactured", "charging_state", "driving_status", "self_discharge_raw", "scooter_serial", "battery_serial_raw",
                "warning_code", "lock_status", "meter_version", "response_70", "bogus", "", null})
            check(!HomeReadings.offered(key), "a slot never holds " + key);
        check(new HashSet<>(all).size() == all.size(), "no reading is listed twice");
        Set<String> telemetry = new HashSet<>();
        for (Telemetry.Metric metric : new Telemetry().metrics()) telemetry.add(metric.key);
        check(telemetry.containsAll(all), "every choice is one of Telemetry's own readings (the dashboard pickers' catalogue)");
        check(all.indexOf(MODE) == all.indexOf(ODO) + 1, "Drive mode is listed right after the odometer");
        // The ride readings first (Drive mode with them), then the battery's: one heading each in the picker.
        String last = null; int changes = 0;
        for (String key : all) {
            String group = HomeReadings.group(key);
            check(Telemetry.GROUP_RIDE.equals(group) || Telemetry.GROUP_BATTERY.equals(group), "a choice is a ride or battery reading: " + key);
            if (last != null && !last.equals(group)) changes++;
            last = group;
        }
        check(changes == 1 && Telemetry.GROUP_RIDE.equals(HomeReadings.group(all.get(0))), "ride readings, then battery readings, each in one run");
    }

    private static void defaults() {
        check(HomeReadings.DEFAULTS.equals(Arrays.asList(ODO, MODE)), "the defaults are Odometer, then Drive mode");
        check(HomeReadings.load(key -> null, A).equals(HomeReadings.DEFAULTS), "nothing stored: the defaults");
        check(HomeReadings.load(key -> "voltage_v", null).equals(HomeReadings.DEFAULTS)
                && HomeReadings.load(key -> "voltage_v", "").equals(HomeReadings.DEFAULTS), "no scooter: the defaults");
        check(HomeReadings.load(null, A).equals(HomeReadings.DEFAULTS), "no store: the defaults");
        check(HomeReadings.key(0, A).equals("home_reading_1_v1_" + A) && HomeReadings.key(1, A).equals("home_reading_2_v1_" + A),
                "one key per slot and scooter");
        check(HomeReadings.resolve(null, null).equals(HomeReadings.DEFAULTS), "both absent: the defaults");
    }

    private static void choicePerSlot() {
        Map<String, String> stored = new HashMap<>();
        stored.put(HomeReadings.key(0, A), "voltage_v");
        check(HomeReadings.load(stored::get, A).equals(Arrays.asList("voltage_v", MODE)), "the first slot alone: the second keeps Drive mode");
        stored.clear();
        stored.put(HomeReadings.key(1, A), "power_w");
        check(HomeReadings.load(stored::get, A).equals(Arrays.asList(ODO, "power_w")), "the second slot alone: the first keeps the odometer");
        stored.put(HomeReadings.key(0, A), "voltage_v");
        check(HomeReadings.load(stored::get, A).equals(Arrays.asList("voltage_v", "power_w")), "both slots");
        check(HomeReadings.load(stored::get, B).equals(HomeReadings.DEFAULTS), "another scooter keeps its own (the defaults)");
        List<String> now = HomeReadings.DEFAULTS;
        now = HomeReadings.choose(now, 0, "voltage_v");
        check(now.equals(Arrays.asList("voltage_v", MODE)), "picking for the first slot changes only it");
        now = HomeReadings.choose(now, 1, "power_w");
        check(now.equals(Arrays.asList("voltage_v", "power_w")), "picking for the second slot changes only it");
        check(HomeReadings.choose(now, 1, "voltage_v").equals(Arrays.asList("power_w", "voltage_v")), "the other slot's reading swaps the two");
        check(HomeReadings.choose(now, 0, "voltage_v").equals(now), "picking a slot's own reading changes nothing");
        check(HomeReadings.choose(now, 0, "battery_percent").equals(now) && HomeReadings.choose(now, 0, null).equals(now)
                && HomeReadings.choose(now, 2, "trip_km").equals(now) && HomeReadings.choose(now, -1, "trip_km").equals(now),
                "an unknown reading or slot changes nothing");
        check(HomeReadings.choose(null, 0, "trip_km").equals(Arrays.asList("trip_km", MODE))
                && HomeReadings.choose(Collections.singletonList("x"), 1, "trip_km").equals(Arrays.asList(ODO, "trip_km")), "a damaged current list starts from the defaults");
        check(HomeReadings.choose(HomeReadings.choose(HomeReadings.DEFAULTS, 0, MODE), 1, MODE).equals(Arrays.asList(ODO, MODE)),
                "Drive mode first, then back second: the odometer returns first");
    }

    private static void fallback() {
        // A choice this Volt can't show (a newer Volt's reading, a reading left out, a damaged value) shows that slot's default.
        check(HomeReadings.resolve("future_reading", "power_w").equals(Arrays.asList(ODO, "power_w")), "an unknown first reading: the odometer");
        check(HomeReadings.resolve("voltage_v", "battery_percent").equals(Arrays.asList("voltage_v", MODE)), "a left-out second reading: Drive mode");
        check(HomeReadings.resolve("", " ").equals(HomeReadings.DEFAULTS), "blank values: the defaults");
        check(HomeReadings.resolve("power_w", "power_w").equals(Arrays.asList("power_w", MODE)), "the same reading twice: the second takes its default");
        check(HomeReadings.resolve(MODE, MODE).equals(Arrays.asList(MODE, ODO)), "Drive mode twice: the second takes the odometer");
        check(HomeReadings.resolve(MODE, "bogus").equals(Arrays.asList(MODE, ODO)), "Drive mode first and an unknown second: the odometer, never Drive mode twice");
        Map<String, String> unreadable = new HashMap<>();
        unreadable.put(HomeReadings.key(0, A), null);
        check(HomeReadings.load(unreadable::get, A).equals(HomeReadings.DEFAULTS), "an unreadable value (wrong type, read as null): the default");
        for (String first : HomeReadings.catalog()) for (String second : new String[]{null, "x", first, ODO, MODE}) {
            List<String> two = HomeReadings.resolve(first, second);
            check(two.size() == 2 && !two.get(0).equals(two.get(1)) && HomeReadings.offered(two.get(0)) && HomeReadings.offered(two.get(1)),
                    "always two different readings a slot can hold: " + first + ", " + second + " -> " + two);
        }
    }

    private static void follows() {
        check(HomeReadings.followsHome(Arrays.asList(ODO, MODE)), "the stock pair follows Home style's choice");
        check(!HomeReadings.followsHome(Arrays.asList(MODE, ODO)) && !HomeReadings.followsHome(Arrays.asList(ODO, MODE, "power_w"))
                && !HomeReadings.followsHome(Collections.singletonList(ODO)) && !HomeReadings.followsHome(Collections.<String>emptyList())
                && !HomeReadings.followsHome(null), "a list picked in the card's own sheet keeps it");
    }

    private static void diagnostics() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(HomeReadings.key(0, A), "voltage_v");
        stored.put(HomeReadings.key(1, A), "battery_health_percent");
        Map<String, Object> section = DiagnosticsSettings.section(stored, A);
        @SuppressWarnings("unchecked") Map<String, Object> scooter = (Map<String, Object>) section.get("selected_scooter");
        check("voltage_v".equals(scooter.get("home_reading_1_v1")) && "battery_health_percent".equals(scooter.get("home_reading_2_v1")),
                "the report diagnostics name the selected scooter's two: " + scooter);
        check(DiagnosticsSettings.SCOOTER_PREFIXES.contains(HomeReadings.FIRST_PREFIX) && DiagnosticsSettings.SCOOTER_PREFIXES.contains(HomeReadings.SECOND_PREFIX),
                "both keys are on the diagnostics list");
        for (String key : HomeReadings.catalog())
            check(key.equals(DiagnosticsSettings.value(key)), "every choice shows as itself in the diagnostics: " + key);
        Map<String, Object> none = DiagnosticsSettings.section(new HashMap<>(), A);
        @SuppressWarnings("unchecked") Map<String, Object> empty = (Map<String, Object>) none.get("selected_scooter");
        check(empty.containsKey("home_reading_1_v1") && empty.get("home_reading_1_v1") == null, "never chosen: null (the defaults apply)");
    }

    private static void backup() throws Exception {
        for (String prefix : new String[]{HomeReadings.FIRST_PREFIX, HomeReadings.SECOND_PREFIX}) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", prefix + A);
            check(rule != null && rule.perProfile && BackupRegistry.SCOOTER_PREFERENCES.equals(rule.category) && rule.type == String.class,
                    "a slot's key is a per-scooter String in Scooter preferences: " + prefix);
        }
        File root = Files.createTempDirectory("volt-home-readings-").toFile();
        try {
            BackupTestStore source = BackupTestStore.rich(new File(root, "source"));
            source.put("app_settings", HomeReadings.key(0, A), "voltage_v");
            source.put("app_settings", HomeReadings.key(1, A), "power_w");
            source.put("app_settings", HomeReadings.key(0, B), "trip_km");
            byte[] complete = source.export(BackupTestStore.all());

            BackupTestStore fresh = new BackupTestStore(new File(root, "fresh"));
            fresh.restore(complete, BackupTestStore.all(), true, null);
            Map<String, Object> app = fresh.preferences().get("app_settings");
            check("voltage_v".equals(app.get(HomeReadings.key(0, A))) && "power_w".equals(app.get(HomeReadings.key(1, A)))
                    && "trip_km".equals(app.get(HomeReadings.key(0, B))) && !app.containsKey(HomeReadings.key(1, B)),
                    "Replace everything restores each scooter's two exactly");
            check(HomeReadings.load(key -> (String) app.get(key), A).equals(Arrays.asList("voltage_v", "power_w"))
                    && HomeReadings.load(key -> (String) app.get(key), B).equals(Arrays.asList("trip_km", MODE)), "and they read back as chosen");

            BackupTestStore phone = BackupArchiveTest.phone(new File(root, "merge"));
            phone.put("app_settings", HomeReadings.key(0, C), "speed_kmh");
            BackupArchive.Prepared staged = phone.stage(complete);
            Map<String, String> chosen = new LinkedHashMap<>(); chosen.put(A, C); chosen.put(B, B);
            BackupTransaction.apply(phone, BackupArchive.plan(phone, staged, BackupTestStore.all(), false, chosen));
            Map<String, Object> merged = phone.preferences().get("app_settings");
            check("voltage_v".equals(merged.get(HomeReadings.key(0, C))) && "power_w".equals(merged.get(HomeReadings.key(1, C))),
                    "a merge moves the two onto the scooter they are mapped to: " + merged);
            check(!merged.containsKey(HomeReadings.key(0, A)) && !merged.containsKey(HomeReadings.key(1, A)), "the backup's profile id is not written");
            check("trip_km".equals(merged.get(HomeReadings.key(0, B))), "a scooter added under its own id keeps its choice");

            BackupTestStore appOnly = BackupArchiveTest.phone(new File(root, "app-only"));
            appOnly.put("app_settings", HomeReadings.key(0, C), "speed_kmh");
            appOnly.restore(complete, new LinkedHashSet<>(Collections.singletonList(BackupRegistry.PREFERENCES)), false, null);
            Map<String, Object> kept = appOnly.preferences().get("app_settings");
            check("speed_kmh".equals(kept.get(HomeReadings.key(0, C))) && !kept.containsKey(HomeReadings.key(0, A)),
                    "App preferences alone leaves each scooter's two as they are (they are Scooter preferences)");
        } finally {
            BackupTestStore.delete(root);
        }
    }
}
