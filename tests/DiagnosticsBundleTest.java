package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The volt-diagnostics-v1 file: what it holds, and that a fixture seeded with a known serial, MAC, owner key,
 * PIN, scooter and profile names, auth bytes and raw payloads cannot get any of them into it, by its typed
 * sources and sanitizers alone, before json()'s final secret check.
 */
public final class DiagnosticsBundleTest {
    private static int checks;
    private static final String SERIAL = CommandLogTest.SERIAL, MAC = CommandLogTest.MAC, OWNER_KEY = CommandLogTest.OWNER_KEY,
            NAME = CommandLogTest.NAME, PROFILE_NAME = "Joel's commuter", BATTERY_SERIAL = "BT2024ZX99887766";
    private static final String PIN = Integer.toString(CommandLogTest.PIN);
    private static final long WALL = 1792026000000L, NOW = 500000;
    /** Part 2's private values: a saved profile id, an email, a place, a storage path and a map token. */
    static final String PROFILE_ID = "prof7c1d2e9a", EMAIL = "joel.rider@example.com", LAT = "43.6150123", LON = "-116.2023456",
            PATH = "/storage/emulated/0/Download/Joel", TOKEN = "tok_" + CommandLogTest.OWNER_KEY;

    public static void main(String[] args) {
        String json = bundle().json(Collections.<String>emptyList());
        contents(json);
        noForbiddenData(json);
        secretGuard();
        withheldReadings();
        batteryTemperatureSurvivesExport();
        evidenceSection();
        evidenceSecrets();
        worstCaseSize();
        previewBlocks();
        part2Sections(json);
        settingsAllowList(json);
        emptySources();
        tallyAndSign();
        clockChanges();
        privateShapes();
        System.out.println("DiagnosticsBundle tests passed: " + checks + " assertions");
    }

    /** Every source, seeded with the fixture's private values wherever a source could carry them. */
    static DiagnosticsBundle bundle() {
        Telemetry t = new Telemetry();
        t.accept(0x70, withStatus(CapabilityResolverTest.full()), NOW - 1000);
        t.accept(0x74, withStatus(SERIAL.getBytes(StandardCharsets.US_ASCII)), NOW - 1000);
        t.accept(0x75, withStatus(BATTERY_SERIAL.getBytes(StandardCharsets.US_ASCII)), NOW - 1000);
        t.accept(0x7b, withStatus(Protocol.fromHex("a1b2c3d4e5f60708090a")), NOW - 1000);
        t.accept(0x73, withStatus("2031V1.4".getBytes(StandardCharsets.US_ASCII)), NOW - 1000);
        // Credentials and PIN responses never become readings.
        t.accept(0x30, withStatus(Protocol.auth(Protocol.fromHex(OWNER_KEY), 1, 0)), NOW - 900);
        t.accept(0x7a, withStatus(new byte[]{1, 7, 3, 9, 5}), NOW - 900);
        t.accept(0x63, withStatus(PIN.getBytes(StandardCharsets.US_ASCII)), NOW - 900);
        require(value(t, "scooter_serial").equals(SERIAL), "fixture: the live readings do hold the serial");

        List<ConnectionEventLog.Record> connections = Arrays.asList(
                new ConnectionEventLog.Record(WALL, NOW - 9000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, true, false, ConnectionEventLog.Phase.CONNECTING, -1, -1),
                new ConnectionEventLog.Record(WALL + 2000, NOW - 7000, ConnectionEventLog.Reason.AUTHENTICATED_READY, 0, true, false, ConnectionEventLog.Phase.READY, 40, 20),
                new ConnectionEventLog.Record(WALL + 9000, NOW - 1000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 8, true, false, ConnectionEventLog.Phase.READY, 1000, 500));
        List<CommandLog.Record> commands = new ArrayList<>();
        commands.add(new CommandLog.Session(WALL + 2100, "1.7.0", "25462", ModelRegistry.Layer.OEM_GROUP, "2031", "", false));
        commands.add(new CommandLog.Command(WALL + 3000, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.ACK, -1, null, null, 120));
        commands.add(new CommandLog.Command(WALL + 3200, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.READBACK_SAME, -1, null, 1, 640));
        commands.add(new CommandLog.Command(WALL + 4000, CommandLog.Kind.SET, MutationGate.Origin.USER, "pin", CommandLog.loggable("pin", CommandLogTest.PIN),
                CommandLog.Outcome.REJECTED, 3, null, null, 210));
        commands.add(new CommandLog.Command(WALL + 5000, CommandLog.Kind.TRY, MutationGate.Origin.GUIDED_TEST, "prompt_tone", 4, CommandLog.Outcome.READBACK_OTHER, -1, null, 3, 900));
        commands.add(new CommandLog.Command(WALL + 6000, CommandLog.Kind.SET, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW, "maximum_speed", 30, CommandLog.Outcome.REFUSED, -1,
                CapabilityResolver.Reason.MOVING, null, -1));
        commands.add(new CommandLog.Command(WALL + 7000, CommandLog.Kind.READ, null, "maximum_speed", null, CommandLog.Outcome.UNEXPECTED_VALUE, -1, null, 0, -1));

        // The compatibility report gets the descriptor with the serial; its whitelist keeps only model-level facts.
        DeviceDescriptor d = DeviceDescriptor.of(SERIAL, "25462", 1, new DeviceDescriptor.Firmware("2031", null, null, null, null, NOW), true,
                DeviceDescriptor.Transport.STANDARD, 0);
        CompatibilityReport report = new CompatibilityReport.Builder("1.7.0", new Random(4)).device(d, ModelRegistry.resolve(d), "aes")
                .fields(CapabilityResolverTest.status(CapabilityResolverTest.full(), NOW)).build();
        require(report != null, "fixture report builds");

        // This connection's drive modes and power sign (the settings frame above reported one mode too).
        t.tally.note("driving_mode", 3.0); t.tally.note("speed_kmh", 25.34); t.tally.note("speed_kmh", 18.0);
        t.tally.note("current_sign_state", 1.0); t.tally.note("current_a", -4.2);

        return new DiagnosticsBundle("1.7.0", WALL + 10000, false).phone("16", 36, "Google Pixel 9")
                .app(48, "com.android.vending").phoneState(device()).settings(stuffedSettings(), PROFILE_ID).report(report)
                .connections(connections, true, true).commands(commands, true, true).readings(t.metrics(), NOW).tally(t.tally, false)
                .chargeSchedule(schedule()).evidence(new ChargeEvidence.Snapshot(true, evidence(1)))
                .recordings(recordings()).storage(5_000_000_000L, true, false, true)
                .crash(CrashRecord.decode(CrashRecord.encode(CrashRecordTest.sample(), "1.7.0", WALL))).clockChanges(connections);
    }

    /** A phone's state; its text fields are checked by shape, so a name in one cannot pass (emptySources checks). */
    static DiagnosticsBundle.Device device() {
        DiagnosticsBundle.Device d = new DiagnosticsBundle.Device();
        d.phoneLocale = "en-US"; d.appLocale = "pt-BR"; d.fontScale = 1.3f; d.uiNightBits = 0x20;
        d.smallestWidthDp = 411; d.widthDp = 411; d.heightDp = 914; d.densityDpi = 420;
        d.batteryOptimisationExempt = true; d.powerSave = false; d.backgroundRestricted = false; d.notificationsEnabled = true;
        d.locationEnabled = true; d.bluetoothEnabled = true; d.standbyBucket = 10;
        d.channels.put("scooter", 2); d.channels.put("charge_alerts", 4); d.channels.put("maintenance", -1);
        d.channels.put(NAME, 3); // an id the file does not list never shows
        d.permissions.put("bluetooth_scan", true); d.permissions.put("bluetooth_connect", true); d.permissions.put("fine_location", false);
        d.permissions.put(PROFILE_NAME, true);
        return d;
    }

    /**
     * Preferences stuffed with private values: free text, a serial, a MAC and an email in listed keys, and unlisted keys
     * holding a map token, a ride label, a folder grant, a place and another scooter's settings.
     */
    static Map<String, Object> stuffedSettings() {
        Map<String, Object> p = new HashMap<>();
        for (String key : DiagnosticsSettings.KEYS) p.put(key, true);
        p.put("units", "km"); p.put("text_scale", 1.15f); p.put("poll_interval_ms", 1000); p.put("app_language", "pt-BR"); p.put("riding_night_vision", "scheduled");
        p.put("theme", NAME); p.put("time_format", SERIAL); p.put("temperature_unit", MAC); p.put("home_style", EMAIL); p.put("stats_density", OWNER_KEY + OWNER_KEY);
        p.put("dashboard_columns", Collections.singleton(NAME));
        p.remove("charge_live_lock_detail");
        p.put("charge_alerts_" + PROFILE_ID, "charge=1;at=80;low=0;below=20;notify=1;alarm=0");
        p.put("charge_charger_" + PROFILE_ID, "HIGH_SPEED"); p.put("range_follow_mode_" + PROFILE_ID, false);
        p.put("charge_alerts_otherscooter", "charge=1;at=57;low=1;below=13;notify=0;alarm=1");
        p.put("map_tile_url", "https://tiles.example.com/{z}/{x}/{y}?token=" + TOKEN); p.put("last_ride_label", PROFILE_NAME);
        p.put("ride_auto_export_folder", "content://com.android.externalstorage.documents/tree/primary%3AJoel");
        p.put("riding_night_ride_area", LAT + "," + LON); p.put("stats_favourites", new HashSet<>(Collections.singletonList(NAME)));
        return p;
    }

    /** Ride, phone-ride and charge folders whose files carry a label, notes, a place, a profile id and a scooter-named file. */
    static DiagnosticsBundle.Recordings recordings() {
        try {
            java.io.File root = java.nio.file.Files.createTempDirectory("diag-recordings").toFile();
            java.io.File rides = new java.io.File(root, "rides"), phone = new java.io.File(root, "phone-rides"), charges = new java.io.File(root, "charge-recordings");
            require(phone.mkdirs() && charges.mkdirs(), "fixture folders");
            RideStore store = new RideStore(rides);
            store.start(PROFILE_ID, PROFILE_NAME, WALL, 1000, "America/Boise");
            store.setNotes("Met " + EMAIL + " at " + LAT + "," + LON);
            store.stop(WALL + 60_000, 61_000, "");
            store.start(PROFILE_ID, NAME, WALL + 100_000, 101_000, "America/Boise");
            store.stop(WALL + 700_000, 701_000, RideStore.quietStopReason(8, false));
            write(new java.io.File(rides, PROFILE_NAME + ".gpx"), LAT + "," + LON);
            write(new java.io.File(phone, "0b9c1e7e-1d2f-4c3a-9f00-123456789abc-phone.csv"), LAT + "," + LON + "\n");
            StringBuilder charge = new StringBuilder("charge-v2," + PROFILE_ID + ",STOCK," + WALL + ",5000\n");
            for (int i = 1; i <= 3; i++) charge.append("s,").append(WALL + i * 60_000L).append(',').append(5000 + i * 60_000L).append(",5").append(i).append(",41.2,2.1,86.5,24,0,8,,,,,,0\n");
            charge.append("end,COMPLETE\n");
            write(new java.io.File(charges, "1f6b2a3c-4d5e-4f60-8a71-b2c3d4e5f607.csv"), charge.toString());
            DiagnosticsBundle.Recordings r = new DiagnosticsBundle.Recordings();
            r.rideFolder = rides; r.phoneRideFolder = phone; r.chargeFolder = charges; r.rides = store.list(); r.nowWallMs = System.currentTimeMillis();
            return r;
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
    static void write(java.io.File f, String text) throws java.io.IOException { java.nio.file.Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }

    /** Part 2's sections, each with its fields. */
    private static void part2Sections(String json) {
        for (String expected : new String[]{
                "\"app\": {", "\"version_code\": 48", "\"install_source\": \"play_store\"",
                "\"phone_state\": {", "\"phone_locale\": \"en-US\"", "\"app_locale\": \"pt-BR\"", "\"font_scale_percent\": 130", "\"night_mode\": \"yes\"",
                "\"smallest_width_dp\": 411", "\"width_class\": \"compact\"", "\"battery_optimisation_exempt\": true", "\"background_restricted\": false",
                "\"standby_bucket\": \"active\"", "\"scooter\": \"low\"", "\"charge_alerts\": \"high\"", "\"maintenance\": \"not_created\"", "\"volt_updates\": null",
                "\"bluetooth_connect\": true", "\"fine_location\": false", "\"post_notifications\": null", "\"location_enabled\": true",
                "\"settings\": {", "\"units\": \"km\"", "\"text_scale\": \"1.15\"", "\"poll_interval_ms\": 1000", "\"app_language\": \"pt-BR\"",
                "\"theme\": \"(set)\"", "\"time_format\": \"(set)\"", "\"temperature_unit\": \"(set)\"", "\"home_style\": \"(set)\"", "\"stats_density\": \"(set)\"",
                "\"dashboard_columns\": \"(set)\"", "\"charge_live_lock_detail\": null", "\"background_connection\": true",
                "\"selected_scooter\": {", "\"charge_alerts\": \"charge=1;at=80;low=0;below=20;notify=1;alarm=0\"", "\"charge_charger\": \"HIGH_SPEED\"",
                "\"range_source\": null", "\"range_follow_mode\": false",
                "\"power_sign\": {", "\"current_direction_known\": true", "\"basis\": \"sign_state\"",
                "\"drive_modes\": {", "\"code\": 3", "\"top_speed_kmh\": \"25.3\"", "\"current\": true",
                "\"recordings\": {", "\"rides\": {", "\"count\": 2", "\"summaries_in_memory\": true", "\"duration_s\": 600", "\"end\": \"auto_quiet_disconnect\"",
"\"phone_rides\": {", "\"count\": 1", "\"charges\": {", "\"end\": \"complete\"", "\"duration_s\": 180",
                "\"storage\": {", "\"free_space\": \"under_10_gb\"", "\"ride_last_write_failed\": false",
                "\"last_crash\": {", "\"exception\": \"java.lang.RuntimeException\"", "\"ScooterService$3.run:1204\"",
                "\"clock_changes\": {", "\"threshold_ms\": 2000", "\"events_compared\": 2", "\"phone_restarts\": 0"})
            require(json.contains(expected), "part 2 holds " + expected);
        // The power sign and drive modes sit next to the readings they come from.
        int readings = json.indexOf("\"readings\": ["), power = json.indexOf("\"power_sign\": {"), schedule = json.indexOf("\"charge_schedule\": {");
        require(readings < power && power < schedule, "power_sign follows the readings");
        require(!json.contains("\"simulated\": true"), "a real connection's sections are not marked simulated");
    }

    /**
     * The settings allow-list: every listed key is one Volt stores (BackupRegistry has its rule, and some other source
     * names it), each shows, and nothing unlisted does.
     */
    private static void settingsAllowList(String json) {
        for (String key : DiagnosticsSettings.KEYS) {
            require(BackupRegistry.rule("app_settings", key) != null, "a listed setting is one Volt stores: " + key);
            require(namedElsewhere(key), "a listed setting is still read somewhere: " + key);
            require(json.contains("\"" + key + "\": "), "a listed setting shows: " + key);
        }
        for (String prefix : DiagnosticsSettings.SCOOTER_PREFIXES) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", prefix + PROFILE_ID);
            require(rule != null && rule.perProfile, "a listed scooter setting is stored per scooter: " + prefix);
            require(namedElsewhere(prefix), "a listed scooter setting is still read somewhere: " + prefix);
        }
        for (String unlisted : new String[]{"map_tile_url", "last_ride_label", "ride_auto_export_folder", "riding_night_ride_area", "stats_favourites", "at=57", "charge_alerts_", "tiles.example"})
            require(!json.contains(unlisted), "an unlisted setting never shows: " + unlisted);
        require(DiagnosticsSettings.value(Double.NaN).equals(DiagnosticsSettings.SET) && DiagnosticsSettings.value(new Object()).equals(DiagnosticsSettings.SET)
                && DiagnosticsSettings.value(7L).equals(7L) && DiagnosticsSettings.value("12:30").equals("12:30"), "values by type");
    }
    /** Whether a source file other than the allow-list and the backup registry has this key as a literal. */
    private static boolean namedElsewhere(String key) {
        java.io.File[] files = new java.io.File("src/com/jjricks/volt").listFiles();
        require(files != null && files.length > 100, "the tests run from the app folder");
        for (java.io.File f : files) {
            String name = f.getName();
            if (!name.endsWith(".java") || name.equals("DiagnosticsSettings.java") || name.equals("BackupRegistry.java")) continue;
            try { if (new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("\"" + key + "\"")) return true; }
            catch (java.io.IOException e) { throw new AssertionError(e); }
        }
        return false;
    }

    /**
     * An old install, a fresh install, a simulation, no scooter selected and unreadable sources: every new section is
     * empty or null, nothing throws, and the file is still made.
     */
    private static void emptySources() {
        DiagnosticsBundle.Recordings none = new DiagnosticsBundle.Recordings();
        String fresh = new DiagnosticsBundle("1.13.1", WALL, false).app(0, null).phoneState(null).settings(null, null).tally(null, false)
                .recordings(none).storage(null, null, null, null).crash(null).clockChanges(null).json(null);
        require(fresh != null, "a fresh install's file is made");
        for (String expected : new String[]{"\"version_code\": null", "\"install_source\": \"none\"", "\"phone_state\": null", "\"selected_scooter\": null",
                "\"power_sign\": null", "\"drive_modes\": null", "\"rides\": null", "\"charges\": null", "\"free_space\": null", "\"last_crash\": null", "\"events_compared\": 0"})
            require(fresh.contains(expected), "fresh install: " + expected);
        // An old install: settings never changed, folders missing, a phone that won't say anything.
        DiagnosticsBundle.Recordings missing = new DiagnosticsBundle.Recordings();
        missing.rideFolder = new java.io.File("/nonexistent/rides"); missing.chargeFolder = new java.io.File("/nonexistent/charges");
        String old = new DiagnosticsBundle("1.13.1", WALL, false).phoneState(new DiagnosticsBundle.Device()).settings(new HashMap<String, Object>(), "")
                .recordings(missing).json(null);
        require(old != null && old.contains("\"background_connection\": null") && old.contains("\"selected_scooter\": null") && old.contains("\"rides\": null")
                && old.contains("\"night_mode\": null") && old.contains("\"width_class\": null"), "an old install: nulls, never a throw");
        // A simulated connection: its sections say so.
        DiagnosticsTally simulated = new DiagnosticsTally(); simulated.note("driving_mode", 1.0);
        String sim = new DiagnosticsBundle("1.13.1", WALL, true).tally(simulated, true).json(null);
        require(sim.contains("\"drive_modes\": {") && sim.split("\"simulated\": true", -1).length == 4, "the simulator's drive modes and power sign are marked simulated");
        // Unreadable: a file where a folder should be, a damaged charge recording, a crash file that isn't one.
        try {
            java.io.File root = java.nio.file.Files.createTempDirectory("diag-unreadable").toFile();
            java.io.File notFolder = new java.io.File(root, "rides"); write(notFolder, "x");
            java.io.File charges = new java.io.File(root, "charges"); require(charges.mkdir(), "fixture");
            write(new java.io.File(charges, "a.csv"), "garbage\nend,NOT_A_STOP\n");
            java.io.File crash = new java.io.File(root, "last-crash"); write(crash, "{\"not\": \"ours\"}");
            DiagnosticsBundle.Recordings bad = new DiagnosticsBundle.Recordings();
            bad.rideFolder = notFolder; bad.chargeFolder = charges; bad.chargeActive = true; bad.chargeActiveSamples = 12;
            String unreadable = new DiagnosticsBundle("1.13.1", WALL, false).recordings(bad).crash(CrashRecord.read(crash)).json(null);
            require(unreadable != null && unreadable.contains("\"rides\": null") && unreadable.contains("\"end\": \"other\"") && unreadable.contains("\"duration_s\": null")
                    && unreadable.contains("\"recording_samples\": 12") && unreadable.contains("\"last_crash\": null"), "unreadable sources leave their parts empty");
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        // Shapes checked: a name in a locale or unknown ids never pass.
        DiagnosticsBundle.Device odd = new DiagnosticsBundle.Device(); odd.phoneLocale = NAME; odd.appLocale = EMAIL; odd.standbyBucket = 999_999; odd.widthDp = -4;
        String shaped = new DiagnosticsBundle("1.13.1", WALL, false).phoneState(odd).json(null);
        require(shaped != null && shaped.contains("\"phone_locale\": null") && shaped.contains("\"app_locale\": null") && shaped.contains("\"standby_bucket\": \"other_999\"")
                && shaped.contains("\"width_dp\": null"), "text fields by shape");
        require(DiagnosticsBundle.installSource("com.example.store").equals("other") && DiagnosticsBundle.installSource("com.android.shell").equals("adb")
                && DiagnosticsBundle.installSource("com.google.android.packageinstaller").equals("package_installer"), "install sources as classes");
        require(DiagnosticsBundle.freeClass(50L << 20).equals("under_100_mb") && DiagnosticsBundle.freeClass(11L << 30).equals("10_gb_or_more"), "free space classes");
        for (String reason : new String[]{null, "", "AUTO_PARKED_DISCONNECT", "automatic_no_motion", "SOMETHING_NEW;" + NAME})
            require(!DiagnosticsBundle.rideEnd(reason).contains("NAME") && DiagnosticsBundle.rideEnd(reason).matches("[a-z_]+"), "ride ends are fixed tokens");
    }

    /** The drive modes and the power sign, from the readings Telemetry accepts, cleared with them. */
    private static void tallyAndSign() {
        DiagnosticsTally t = new DiagnosticsTally();
        require(t.powerSign().get("current_direction_known") == null && "no_readings".equals(t.powerSign().get("basis")), "nothing read: unknown");
        t.note("driving_mode", 1.0); t.note("speed_kmh", 20.0); t.note("speed_kmh", 31.26); t.note("driving_mode", 2.0); t.note("speed_kmh", 12.0);
        t.note("driving_mode", 1.5); t.note("speed_kmh", Double.NaN); t.note("speed_kmh", 999.0); t.note(null, 1.0); t.note("driving_mode", null);
        List<?> modes = (List<?>) t.driveModes().get("modes");
        require(modes.size() == 2 && ((Map<?, ?>) modes.get(0)).get("top_speed_kmh").equals("31.3") && ((Map<?, ?>) modes.get(0)).get("speed_readings").equals(2L)
                && ((Map<?, ?>) modes.get(1)).get("current").equals(true) && ((Map<?, ?>) modes.get(1)).get("top_speed_kmh").equals("12.0"), "each mode's top speed, the current mode marked");
        for (int i = 0; i < 40; i++) t.note("driving_mode", (double) i);
        require(((List<?>) t.driveModes().get("modes")).size() == DiagnosticsTally.MAX_MODES && t.driveModes().get("codes_left_out").equals(24L), "codes are capped");
        t.note("current_sign_state", 254.0); t.note("current_a", 3.0);
        require(t.powerSign().get("current_direction_known").equals(false) && "unsigned".equals(t.powerSign().get("basis")), "a GT3 Max's 254: unsigned");
        t.note("current_sign_state", 0.0);
        require(t.powerSign().get("current_direction_known").equals(true) && "sign_state".equals(t.powerSign().get("basis")), "a 0 or 1: signed");
        t.note("current_sign_state", 254.0);
        require(t.powerSign().get("current_direction_known").equals(false) && "sign_state_lost".equals(t.powerSign().get("basis"))
                && t.powerSign().get("recording_direction_known").equals(true), "a 254 after a 0: unknown live, a recording still counts it signed");
        t.clear();
        require(((List<?>) t.driveModes().get("modes")).isEmpty() && "no_readings".equals(t.powerSign().get("basis")), "cleared");
        DiagnosticsTally ut5 = new DiagnosticsTally(); ut5.note("current_a", -2.0);
        require("no_sign_state".equals(ut5.powerSign().get("basis")) && ut5.powerSign().get("current_direction_known").equals(true), "no sign state at all (UT5 MAX): signed");
        // Through Telemetry: an accepted reading feeds it; clear() empties it.
        Telemetry telemetry = new Telemetry();
        telemetry.putSimulationSample("driving_mode", "2 (raw)", 2.0, 10); telemetry.putSimulationSample("speed_kmh", "22.0 km/h", 22.0, 11);
        List<?> fed = (List<?>) telemetry.tally.driveModes().get("modes");
        require(fed.size() == 1 && ((Map<?, ?>) fed.get(0)).get("top_speed_kmh").equals("22.0"), "Telemetry feeds the tally");
        telemetry.clear();
        require(((List<?>) telemetry.tally.driveModes().get("modes")).isEmpty(), "Telemetry.clear() clears it");
    }

    /** Clock changes: the wall clock moving against the monotonic clock between connection events, and phone restarts. */
    private static void clockChanges() {
        List<ConnectionEventLog.Record> r = new ArrayList<>();
        ConnectionEventLog.Reason a = ConnectionEventLog.Reason.CONNECT_ATTEMPT; ConnectionEventLog.Phase p = ConnectionEventLog.Phase.CONNECTING;
        r.add(new ConnectionEventLog.Record(WALL, 10_000, a, -1, true, false, p, -1, -1));
        r.add(new ConnectionEventLog.Record(WALL + 1_500, 11_000, a, -1, true, false, p, -1, -1));       // +500 ms: noise
        r.add(new ConnectionEventLog.Record(WALL + 3_601_500, 12_000, a, -1, true, false, p, -1, -1));   // clock set an hour ahead
        r.add(new ConnectionEventLog.Record(WALL + 3_000, 20_000, a, -1, true, false, p, -1, -1));       // set back
        r.add(new ConnectionEventLog.Record(WALL + 900_000, 5_000, a, -1, true, false, p, -1, -1));      // restarted
        String json = new DiagnosticsBundle("1.13.1", WALL, false).clockChanges(r).json(null);
        require(json.contains("\"jump_ms\": 3599000") && json.contains("\"jump_ms\": -3606500") && json.contains("\"phone_restarts\": 1")
                && json.contains("\"events_compared\": 4") && json.split("\"jump_ms\"", -1).length == 3, "jumps and restarts, noise left out");
        List<ConnectionEventLog.Record> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add(new ConnectionEventLog.Record(WALL + i * 10_000L * (i % 2 == 0 ? 1 : 2), 1000 + i, a, -1, true, false, p, -1, -1));
        String capped = new DiagnosticsBundle("1.13.1", WALL, false).clockChanges(many).json(null);
        require(capped.split("\"jump_ms\"", -1).length == DiagnosticsBundle.MAX_CLOCK_CHANGES + 1 && capped.contains("\"changes_left_out\": "), "changes are capped");
    }

    /** The one scan over the whole file: private shapes anywhere withhold it, whatever source they came through. */
    private static void privateShapes() {
        for (String bad : new String[]{MAC, "c0-ff-ee-12-34-56", EMAIL, LAT, LON, PATH, "/sdcard/Joel", "content://media/external/file/7", "file:///data/user/0/x"})
            require(DiagnosticsBundle.privateShape("{\"x\": \"" + bad + "\"}"), "a private shape: " + bad);
        for (String fine : new String[]{"1.13.1", "12:30", "3.125 A", "0b9c1e7e-1d2f-4c3a-9f00-123456789abc", "OK; 43 data bytes", "-21600", "charge=1;at=80"})
            require(!DiagnosticsBundle.privateShape(fine), "not a private shape: " + fine);
        // Planted in a reading (a source whose sanitizer allows the characters), each withholds the whole file.
        for (String bad : new String[]{MAC, EMAIL, LAT + " deg", "/storage/x"}) {
            Telemetry t = new Telemetry(); t.putSimulationSample("warning_code", bad, null, 10);
            require(new DiagnosticsBundle("1.13.1", WALL, false).readings(t.metrics(), 20).json(null) == null, "a private shape in a reading withholds the file: " + bad);
        }
        require(!DiagnosticsBundle.privateShape(bundle().json(null)), "the full fixture has no private shape");
    }

    private static long[] filled(int columns, long value) { long[] r = new long[columns]; Arrays.fill(r, value); return r; }
    /** A synthesized evidence book with every section at its cap (scale 1) or scale times over it. */
    static ChargeEvidence.Book evidence(int scale) {
        ChargeEvidence.Book b = new ChargeEvidence.Book();
        long start = WALL + 3_600_000L, wire = start / 1000 - 6 * 3600;
        for (int i = 0; i < ChargeEvidence.INTENTIONS; i++)
            b.intentions.add(new long[]{WALL + i, 2, start + i, start + 7_200_000L, wire, -21600, 41, 90, 1, ChargeScheduleController.Outcome.MISSED.ordinal(), start + 130_000L,
                    2, 41, 1, wire, 1, 1, 0, 1, 30_000, 30_000, 30_000, start + 120_000L + i, 1, start + 86_400_000L, 100});
        // The open plan: Charge done by as armed, then the charge limit changed 70 ms after base (it became a manual plan to 80 %).
        b.open = new long[]{WALL + 50, 2, start + 86_400_000L, start + 172_800_000L, wire + 86_400, -21600, 52, 100, 0, ChargeEvidence.END_OPEN, ChargeEvidence.NONE, ChargeEvidence.NONE,
                ChargeEvidence.NONE, ChargeEvidence.NONE, ChargeEvidence.NONE, 1, ChargeEvidence.NONE, ChargeEvidence.NONE, 0, 1_500, ChargeEvidence.NONE, ChargeEvidence.NONE,
                WALL + 70, 1, start + 86_400_000L + 86_400_000L, 80};
        for (int i = 0; i < ChargeEvidence.TESTS; i++)
            b.tests.add(new long[]{WALL + i * 600_000L, WALL + i * 600_000L + 120_000L, ChargeScheduleTest.Outcome.INTERRUPTED.ordinal(), ChargeEvidence.NONE, 1, ChargeEvidence.NONE,
                    ChargeScheduleController.Interruption.READINGS_STALE.ordinal()});
        for (int i = 0; i < ChargeEvidence.TIMELINE * scale; i++)
            b.timeline.add(new long[]{i % 4, WALL + i * 61_000L, 9_999_999_999L + i, i % 2, 100, 249_999, -999_999, -99_999, -79, 1, 4_294_967_295L, 1, 1, 99_999});
        for (int w = 0; w < ChargeEvidence.WINDOWS; w++) {
            b.windows.add(new long[]{w % 2, start + w * 1000L, ChargeEvidence.NONE});
            List<long[]> rows = new ArrayList<>();
            for (int i = 0; i < ChargeEvidence.WINDOW_SAMPLES * scale; i++) rows.add(new long[]{start + i * 2000L, 9_999_999_999L + i, 1, 100, 249_999, -999_999, -99_999, 1, 1});
            b.windowSamples.add(rows);
        }
        for (int i = 0; i < ChargeEvidence.OPERATIONS; i++)
            b.operations.add(new long[]{WALL + i, 9_999_999_999L, ChargeScheduleController.Op.MEASURE_NOT_SET.ordinal(), ChargeScheduleController.Result.BATTERY_SAVED_NO_ESTIMATE.ordinal(),
                    ChargeScheduleController.Interruption.GENERATION_CHANGED.ordinal(), 604_799_999L, WALL, ChargeScheduleTest.Outcome.RESUMED_EARLY.ordinal()});
        for (int i = 0; i < ChargeEvidence.CHANGES; i++)
            b.changes.add(new long[]{WALL + i, 9_999_999_999L, ChargeEvidence.Watched.TIMED_CHARGING.ordinal(), 4_294_967_295L, 4_294_967_294L, WALL, 1});
        return b;
    }

    /** The evidence sections: columns, enum names and times after base_wall_ms. */
    private static void evidenceSection() {
        String json = new DiagnosticsBundle("1.13.1", WALL, false).evidence(new ChargeEvidence.Snapshot(true, evidence(1))).json(Collections.<String>emptyList());
        for (String expected : new String[]{"\"charge_evidence\": {", "\"base_wall_ms\": " + WALL, "\"intentions\": {", "\"columns\": [\"mode\", \"armed_ms\", \"start_ms\"",
                "[\"done_by\", 0, 3600000, 10800000, ", "\"missed\"", "\"open\"", "\"schedule_tests\": {", "\"readings_stale\"", "\"charging_timeline\": {",
                "[\"change\", 0, 9999999999, 0, 100, 249999, -999999, -99999, -79, 1, 4294967295, 1, 1, 99999]", "\"dense_windows\": [", "\"kind\": \"test_end\"",
                "\"schedule_operations\": {", "\"measure_not_set\"", "\"battery_saved_no_estimate\"", "\"generation_changed\"", "\"changes_not_by_volt\": {", "\"timed_charging\"",
                "\"loaded\": true", "\"storage_healthy\": true", "\"moment_window_ms\": 30000", "\"reading_max_age_ms\": 20000",
                "\"end_mode\", \"arming_sample_after_ms\", \"soc_at_arming\", \"locked_at_arming\", \"start_sample_after_ms\", \"locked_at_start\", \"end_sample_after_ms\", \"end_soc\", \"end_schedule_on\", \"end_wire_start\", \"locked_at_end\", \"changed_ms\", \"changed_mode\", \"changed_deadline_ms\", \"changed_target\"]",
                // Council review of 6c968d5c (finding 3): the plan as armed, its change apart.
                "[\"done_by\", 50, 90000000, 176400000, " + (WALL / 1000 + 3600 - 6 * 3600 + 86_400) + ", -21600, 100, 0, \"open\", null, null, 1500, 52, 1, null, null, null, null, null, null, null, 70, \"manual\", 176400000, 80]",
                "\"columns\": [\"wall_ms\", \"start_ms\", \"outcome\", \"resume_minus_start_s\", \"paused\", \"schedule_off\", \"interrupted_by\"]"})
            require(json.contains(expected), "evidence holds " + expected);
        require(!json.contains("\"offset_s\"") && !json.contains("time_sent"), "no column named for a clock offset that is not one, nor a time of two meanings");
        require(!json.contains("\"rows_left_out_for_size\""), "a file within the cap is complete");
        String empty = new DiagnosticsBundle("1.13.1", WALL, false).evidence(new ChargeEvidence.Snapshot(false, new ChargeEvidence.Book())).json(Collections.<String>emptyList());
        require(empty.contains("\"loaded\": false") && empty.contains("\"rows\": []") && empty.contains("\"base_wall_ms\": 0"), "an unloaded book is an empty section");
        require(new DiagnosticsBundle("1.13.1", WALL, false).evidence(null).json(null).contains("\"charge_evidence\": null"), "no selected scooter, no evidence");
        // An unknown stored ordinal never throws; it reads as "unknown".
        ChargeEvidence.Book odd = new ChargeEvidence.Book(); long[] row = filled(ChargeEvidence.O_COLUMNS, ChargeEvidence.NONE); row[ChargeEvidence.O_WALL] = WALL; row[ChargeEvidence.O_OP] = 999;
        row[ChargeEvidence.O_RESULT] = 0; odd.operations.add(row);
        require(new DiagnosticsBundle("1.13.1", WALL, false).evidence(new ChargeEvidence.Snapshot(true, odd)).json(null).contains("\"unknown\""), "an unknown id reads as unknown");
        // The command log's charge start and clock sync values.
        List<CommandLog.Record> commands = Arrays.<CommandLog.Record>asList(new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "charge_start", null,
                CommandLog.Outcome.READBACK_SAME, -1, null, null, 300, 1_792_000_000L, -21600));
        String log = new DiagnosticsBundle("1.13.1", WALL, false).commands(commands, true, true).json(null);
        require(log.contains("\"wire_time_s\": 1792000000,") && log.contains("\"utc_s\": 1792021600") && !log.contains("offset") && !log.contains("time_sent"),
                "charge_start carries the wire start and the UTC instant it stands for (their difference is the offset sent)");
        // Council review of 4e7c8802 (finding 2): a clock sync uses the same convention, the wire value, not UTC seconds.
        List<CommandLog.Record> clock = Arrays.<CommandLog.Record>asList(new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "clock_sync", null,
                CommandLog.Outcome.ACK, -1, null, null, 90, WALL / 1000 - 21600, -21600));
        String synced = new DiagnosticsBundle("1.13.1", WALL, false).commands(clock, true, true).json(null);
        require(synced.contains("\"wire_time_s\": " + (WALL / 1000 - 21600) + ",") && synced.contains("\"utc_s\": " + WALL / 1000), "a clock sync's wire time and UTC instant, by name");
        // Council review of 6c968d5c (finding 2): a charge start cancelled while queued never went on the wire; its event has no wire time or UTC instant.
        boolean sent = false;
        List<CommandLog.Record> cancelled = Arrays.<CommandLog.Record>asList(new CommandLog.Command(WALL, CommandLog.Kind.SET, MutationGate.Origin.USER, "charge_start", null,
                CommandLog.closed(sent, false), -1, null, null, -1, CommandLog.sentTime(sent, 1_792_000_000L), CommandLog.sentOffset(sent, -21600)));
        String unsent = new DiagnosticsBundle("1.13.1", WALL, false).commands(cancelled, true, true).json(null);
        require(unsent.contains("\"cancelled\"") && !unsent.contains("wire_time_s") && !unsent.contains("utc_s"), "an unsent charge start has no wire time");
    }

    /**
     * json()'s final check covers the new sections: a private value that is all digits (as a serial or a MAC without
     * separators could be), planted in any numeric field of the evidence, withholds the file.
     */
    private static void evidenceSecrets() {
        String secret = "248813";
        int[][] plants = {{0, ChargeEvidence.I_WIRE}, {0, ChargeEvidence.I_ARMED_SOC}, {0, ChargeEvidence.I_ARMED_READ}, {0, ChargeEvidence.I_START_READ}, {0, ChargeEvidence.I_END_READ},
                {0, ChargeEvidence.I_LOCK_START}, {0, ChargeEvidence.I_CHANGED_TARGET}, {1, ChargeEvidence.X_OFFSET}, {2, ChargeEvidence.T_MV}, {2, ChargeEvidence.T_ELAPSED},
                {2, ChargeEvidence.T_SKIPPED}, {3, ChargeEvidence.D_MV}, {3, ChargeEvidence.D_ELAPSED}, {4, ChargeEvidence.O_DURATION}, {4, ChargeEvidence.O_ELAPSED},
                {5, ChargeEvidence.C_OLD}, {5, ChargeEvidence.C_NEW}, {5, ChargeEvidence.C_ELAPSED}};
        for (int[] plant : plants) {
            ChargeEvidence.Book b = new ChargeEvidence.Book();
            long[] row;
            switch (plant[0]) {
                case 0: row = filled(ChargeEvidence.I_COLUMNS, ChargeEvidence.NONE); row[ChargeEvidence.I_END] = ChargeEvidence.END_OPEN; b.open = row; break;
                case 1: row = filled(ChargeEvidence.X_COLUMNS, ChargeEvidence.NONE); b.tests.add(row); break;
                case 2: row = filled(ChargeEvidence.T_COLUMNS, ChargeEvidence.NONE); row[0] = 0; b.timeline.add(row); break;
                case 3: row = filled(ChargeEvidence.D_COLUMNS, ChargeEvidence.NONE); b.windows.add(new long[]{0, WALL, ChargeEvidence.NONE}); b.windowSamples.add(new ArrayList<>(Collections.singletonList(row))); break;
                case 4: row = filled(ChargeEvidence.O_COLUMNS, ChargeEvidence.NONE); row[ChargeEvidence.O_OP] = 0; row[ChargeEvidence.O_RESULT] = 0; b.operations.add(row); break;
                default: row = filled(ChargeEvidence.C_COLUMNS, ChargeEvidence.NONE); row[ChargeEvidence.C_KEY] = 0; b.changes.add(row); break;
            }
            int column = plant[1];
            row[column] = Long.parseLong(secret);
            DiagnosticsBundle bundle = new DiagnosticsBundle("1.13.1", WALL, false).evidence(new ChargeEvidence.Snapshot(true, b));
            require(bundle.json(Collections.<String>emptyList()).contains(secret) && bundle.json(Collections.singletonList(secret)) == null,
                    "a saved value planted in evidence section " + plant[0] + " column " + column + " withholds the file");
        }
        // The fixture's string secrets have nowhere to go in the evidence: the full bundle with every section still passes.
        require(bundle().json(Arrays.asList(SERIAL, MAC, MAC.replace(":", ""), OWNER_KEY, NAME, PROFILE_NAME)) != null, "a clean full file passes");
    }

    /**
     * The worst case every log's own cap allows: 1000 command events, 200 connection events and full evidence, with the
     * longest values. It must stay under the size target; an evidence book beyond its caps is trimmed to the hard cap,
     * oldest dense samples first, and says so.
     */
    /** Preview's blocks: at most PREVIEW_LINES lines each, and joined with line breaks exactly the file, edges included. */
    private static void previewBlocks() {
        for (String file : new String[]{"", "{", "\n", "a\n", "\n\nb\n\n", bundle().json(Collections.<String>emptyList())}) {
            List<String> blocks = DiagnosticsBundle.previewBlocks(file);
            require(String.join("\n", blocks).equals(file) && !blocks.isEmpty(), "blocks rebuild the file exactly: " + file.length());
            for (String b : blocks) require(b.split("\n", -1).length <= DiagnosticsBundle.PREVIEW_LINES, "a block stays within its line count");
        }
        StringBuilder many = new StringBuilder(); for (int i = 0; i < 1000; i++) many.append("line ").append(i).append('\n');
        List<String> blocks = DiagnosticsBundle.previewBlocks(many.toString());
        require(blocks.size() == 26 && blocks.get(0).startsWith("line 0\n") && blocks.get(1).startsWith("line 40\n") && blocks.get(25).isEmpty(), "1,000 lines make 25 full blocks and the empty last line");
        require(DiagnosticsBundle.previewBlocks(null).isEmpty(), "no file, no blocks");
    }
    private static void worstCaseSize() {
        List<CommandLog.Record> commands = new ArrayList<>();
        for (int i = 0; i < CommandLog.MAX_RECORDS; i++)
            commands.add(new CommandLog.Command(WALL + i, CommandLog.Kind.RESTORE, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW, "charge_start", null, CommandLog.Outcome.REFUSED, -1,
                    CapabilityResolver.Reason.values()[0], null, 86_399_999L, 4_294_967_295L, -64_800));
        List<ConnectionEventLog.Record> connections = new ArrayList<>();
        for (int i = 0; i < ConnectionEventLog.MAX_RECORDS; i++)
            connections.add(new ConnectionEventLog.Record(WALL + i, 9_999_999_999L, ConnectionEventLog.Reason.CHARGE_RECOVERY_CANCELLED, 255, true, true, ConnectionEventLog.Phase.AUTHENTICATING,
                    9_999_999_999L, 9_999_999_999L));
        DiagnosticsBundle worst = bundle().connections(connections, true, true).commands(commands, true, true).evidence(new ChargeEvidence.Snapshot(true, evidence(1)));
        // Part 2's sections at their own caps: the longest crash record decode accepts, every clock change, every drive
        // mode, and every listed setting at its longest value.
        StringBuilder crash = new StringBuilder(CrashRecord.FORMAT + "\nwall=" + WALL + "\nversion=1999.1999.1999-rc.12345678\n");
        StringBuilder cls = new StringBuilder("C"), method = new StringBuilder("m"), exception = new StringBuilder("e");
        while (cls.length() < 64) cls.append('c'); while (method.length() < 48) method.append('m'); while (exception.length() < 200) exception.append(exception.length() % 2 == 0 ? "." : "x");
        for (int e = 0; e <= CrashRecord.MAX_CAUSES; e++) {
            crash.append("exception=").append(exception).append('\n');
            for (int f = 0; f < CrashRecord.MAX_FRAMES; f++) crash.append("at=").append(cls).append('.').append(method).append(":-99999\n");
            crash.append("other=99999\n");
        }
        CrashRecord longest = CrashRecord.decode(crash.toString());
        require(longest != null && longest.chain.size() == CrashRecord.MAX_CAUSES + 1, "fixture: the longest crash record");
        List<ConnectionEventLog.Record> jumps = new ArrayList<>();
        for (int i = 0; i < 2 * DiagnosticsBundle.MAX_CLOCK_CHANGES; i++)
            jumps.add(new ConnectionEventLog.Record(WALL + (i % 2 == 0 ? 0 : 86_400_000_000L), 9_999_999_999L, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, true, false, ConnectionEventLog.Phase.CONNECTING, -1, -1));
        DiagnosticsTally modes = new DiagnosticsTally();
        for (int i = 0; i < 4 * DiagnosticsTally.MAX_MODES; i++) { modes.note("driving_mode", (double) (255 - i)); modes.note("speed_kmh", 199.99); }
        Map<String, Object> longSettings = new HashMap<>();
        for (String key : DiagnosticsSettings.KEYS) longSettings.put(key, "abcdefghij_abcdefghij_abcdefghij".substring(0, 32));
        for (String prefix : DiagnosticsSettings.SCOOTER_PREFIXES) longSettings.put(prefix + PROFILE_ID, "charge=999999;at=999999;low=999999;below=999999;notify=999999;alarm=999999");
        worst.crash(longest).clockChanges(jumps).tally(modes, true).settings(longSettings, PROFILE_ID);
        String json = worst.json(Collections.<String>emptyList());
        require(json.contains("\"changes_left_out\": ") && json.contains("\"codes_left_out\": ") && json.contains(cls + "." + method), "the part 2 sections are at their caps");
        int evidenceOnly = new DiagnosticsBundle("1.13.1", WALL, false).evidence(new ChargeEvidence.Snapshot(true, evidence(1))).json(null).length();
        int commandsOnly = new DiagnosticsBundle("1.13.1", WALL, false).commands(commands, true, true).json(null).length();
        int connectionsOnly = new DiagnosticsBundle("1.13.1", WALL, false).connections(connections, true, true).json(null).length();
        System.out.println("DiagnosticsBundle worst case parts: evidence " + evidenceOnly + ", command log " + commandsOnly + ", connection log " + connectionsOnly);
        System.out.println("DiagnosticsBundle worst case: " + json.length() + " bytes (target " + DiagnosticsBundle.TARGET_BYTES + ", hard cap " + DiagnosticsBundle.MAX_BYTES + ")");
        require(json.length() <= DiagnosticsBundle.TARGET_BYTES && !json.contains("rows_left_out_for_size"), "the worst case the caps allow is within the size target");
        String over = bundle().connections(connections, true, true).commands(commands, true, true).evidence(new ChargeEvidence.Snapshot(true, evidence(8))).json(Collections.<String>emptyList());
        require(over.length() <= DiagnosticsBundle.MAX_BYTES && over.contains("\"rows_left_out_for_size\": ") && over.contains("\"intentions\": {")
                && over.contains("\"command_log\": {"), "an oversized book is trimmed to the hard cap, the rest kept");
    }

    /** A missed manual start, stored under a scope that is the fixture's serial: the scope must not reach the file. */
    static ChargeScheduleController.Diagnostics schedule() {
        long start = WALL + 3_600_000L, wire = start / 1000 - 6 * 3600;
        String record = "5|0|480|" + start + "|" + wire + "|" + (start + 86_400_000L) + "|" + WALL + "|100.0|0.0|true|" + start + "|0|-1.0"
                + "|41.0|" + ChargeScheduleController.Outcome.MISSED.ordinal() + "|" + (start + 130_000L) + "|" + start + "|41.0|1|1|" + wire + "|2|41.0|false"
                + "|" + WALL + "|" + (WALL + 120_000L) + "|" + (WALL + 126_000L) + "|" + ChargeScheduleTest.Outcome.PASSED.ordinal() + "|1|true";
        ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        c.scope = SERIAL; c.ready = true; c.now = start + 140_000L; c.enabled = 1; c.wireStart = wire; c.zone = TimeZone.getTimeZone("America/Boise");
        return new ChargeScheduleController(new ChargeScheduleController.Store() {
            public String read(String scope) { return SERIAL.equals(scope) ? record : null; }
            public void write(String scope, String value) { throw new AssertionError("diagnostics never write"); }
        }, new ChargeScheduleController.Driver() {
            public ChargeScheduleController.Context context() { return c; }
            public void clock(java.util.function.Consumer<Boolean> done) { throw new AssertionError("no radio"); }
            public void time(long wall, int offset, java.util.function.Consumer<Boolean> done) { throw new AssertionError("no radio"); }
            public void enabled(boolean on, java.util.function.Consumer<Boolean> done) { throw new AssertionError("no radio"); }
        }).diagnostics();
    }

    private static void contents(String json) {
        require(json != null && json.startsWith("{\n  \"format\": \"volt-diagnostics-v1\",\n  \"app_version\": \"1.7.0\""), "format and version first");
        for (String expected : new String[]{
                "\"android_release\": \"16\"", "\"android_sdk\": 36", "\"model\": \"Google Pixel 9\"",
                "\"compatibility_report\": {", "\"format\": \"volt-compat-report-v1\"", "\"pid\": \"25462\"",
                "\"connection_log\": {", "\"reason\": \"NATIVE_DISCONNECT\"", "\"status_code\": 8",
                "\"command_log\": {", "\"type\": \"session\"", "\"model_id\": \"25462\"", "\"layer\": \"oem_group\"", "\"meter_firmware\": \"2031\"",
                "\"key\": \"headlight\"", "\"outcome\": \"readback_same\"", "\"read\": 1", "\"elapsed_ms\": 640",
                "\"outcome\": \"rejected\"", "\"code\": 3", "\"kind\": \"try\"", "\"origin\": \"guided_test\"", "\"outcome\": \"readback_other\"",
                "\"origin\": \"automatic_range_follow\"", "\"reason\": \"moving\"", "\"outcome\": \"unexpected_value\"",
                "\"readings\": [", "\"key\": \"display_units\"", "\"key\": \"meter_version\"", "\"source\": \"0x70\"",
                "\"charge_schedule\": {", "\"mode\": \"none\"", "\"wire_start\": " + ((WALL + 3_600_000L) / 1000 - 6 * 3600), "\"clock_offset_s\": -21600",
                "\"soc_at_arming\": 41", "\"target\": 100", "\"outcome\": \"missed\"", "\"outcome_mode\": \"manual\"", "\"outcome_soc\": 41", "\"outcome_soc_at_arming\": 41",
                "\"outcome_enabled\": 1", "\"alert\": \"shown\"", "\"readback\": {", "\"enabled\": 1",
                "\"test\": {", "\"wall_ms\": " + WALL, "\"outcome\": \"passed\"", "\"offset_s\": 6", "\"paused\": true", "\"schedule_off\": 1"})
            require(json.contains(expected), "bundle holds " + expected);
        require(!json.contains("\"key\": \"pin\",\n          \"value\""), "the PIN event carries no value");
        String schedule = json.substring(json.indexOf("\"charge_schedule\": {"), json.indexOf("\"charge_evidence\": {")); // up to the evidence section
        require(schedule.replaceAll("\"[a-z_]+\": (-?[0-9]+|\"[a-z_]+\"|\\{|true|false)", "").replaceAll("[\\s,{}]", "").isEmpty(),
                "the schedule section holds only numbers and lower-case enums");
        require(new DiagnosticsBundle("1.9.0", WALL, false).chargeSchedule(null).json(Collections.<String>emptyList()).contains("\"charge_schedule\": null"),
                "no selected scooter, no schedule record");
    }

    /** None of the fixture's private values, in any spelling, nor any payload-length hex run. */
    private static void noForbiddenData(String json) {
        String upper = json.toUpperCase(Locale.ROOT);
        String[] forbidden = {SERIAL, MAC, MAC.replace(":", ""), OWNER_KEY, NAME, PROFILE_NAME, BATTERY_SERIAL, PIN,
                Protocol.hex(BATTERY_SERIAL.getBytes(StandardCharsets.US_ASCII)), Protocol.hex(SERIAL.getBytes(StandardCharsets.US_ASCII)),
                Protocol.hex(Protocol.auth(Protocol.fromHex(OWNER_KEY), 1, 0)), Protocol.hex(PIN.getBytes(StandardCharsets.US_ASCII)), "0107030905",
                "a1b2c3d4e5f60708090a", "scooter_serial", "battery_serial_raw", "fault_history_raw", "raw_diagnostic_payloads",
                // Part 2's sources: a profile id, an email, a place, a storage path, a map token, a folder grant, a ride file
                // name and the crash's message.
                PROFILE_ID, EMAIL, LAT, LON, PATH, TOKEN, "tiles.example", "primary%3AJoel", "Joel", ".gpx", "Synthetic", "failed for", "otherscooter"};
        for (String secret : forbidden) require(!upper.contains(secret.toUpperCase(Locale.ROOT)), "never in the bundle: " + secret);
        require(!java.util.regex.Pattern.compile("[0-9A-Fa-f]{12,}").matcher(json.replaceAll("\"[a-z_]*wall_ms\": \\d+", "").replaceAll("\"report_id\": \"[0-9a-f]{16}\"", "")).find(),
                "no payload-length hex run anywhere");
    }

    /** json()'s last check: a saved serial, MAC, key or chosen name anywhere in the file withholds the whole file. */
    private static void secretGuard() {
        DiagnosticsBundle b = bundle();
        List<String> saved = Arrays.asList(SERIAL, MAC, MAC.replace(":", ""), OWNER_KEY, NAME, PROFILE_NAME, "", null, "Max");
        require(b.json(saved) != null, "a clean file passes the secret check, short names are not searched");
        require(b.json(Collections.singletonList("Google Pixel")) == null, "a secret that does appear withholds the file");
        require(b.json(Collections.singletonList("google pixel")) == null, "the check ignores case");
    }

    /** The readings sanitizer on its own. */
    private static void withheldReadings() {
        require(!DiagnosticsBundle.reading("a1b2c3d4e5f60708") && !DiagnosticsBundle.reading("Battery " + SERIAL) && !DiagnosticsBundle.reading("café")
                && DiagnosticsBundle.reading("82") && DiagnosticsBundle.reading("Scooter returned status 3") && DiagnosticsBundle.reading("OK; 43 data bytes"),
                "readings: short printable text, no payload hex or serials");
        require(DiagnosticsBundle.WITHHELD.containsAll(Arrays.asList("scooter_serial", "battery_serial_raw", "fault_history_raw", "ride_read_raw")), "identity and raw readings withheld");
    }

    private static void batteryTemperatureSurvivesExport() {
        for(int degrees:new int[]{0,23,-7}) {
            Telemetry t=new Telemetry();
            byte[] battery=new byte[36]; battery[11]=(byte)(degrees<0?0x80-degrees:degrees);
            t.accept(0x72,withStatus(battery),NOW-1000);
            String json=new DiagnosticsBundle("1.8.1",WALL,false).readings(t.metrics(),NOW).json(Collections.<String>emptyList());
            int start=json.indexOf("\"key\": \"temperature_c\"");
            require(start>=0,"Temperature is exported, including zero and negative values");
            String entry=json.substring(start,json.indexOf('}',start));
            require(entry.contains("\"value\": \""+degrees+" \\u00b0C\"")&&entry.contains("\"age_ms\": 1000")
                &&entry.contains("\"source\": \"0x72\""),"Temperature retains value, age and source");
        }
        require(!DiagnosticsBundle.reading("other","23 °C")&&!DiagnosticsBundle.reading("temperature_c","café")
            &&!DiagnosticsBundle.reading("temperature_c","23 °C\n"),"Degree exception is numeric, single-line and temperature-only");
        require(!DiagnosticsBundle.reading("temperature_c",SERIAL+" °C")
            &&!DiagnosticsBundle.reading("temperature_c",OWNER_KEY+" °C"),"Temperature exception cannot export identity or key text");
    }

    private static byte[] withStatus(byte[] data) { byte[] out = new byte[data.length + 1]; System.arraycopy(data, 0, out, 1, data.length); return out; }
    private static String value(Telemetry t, String key) { for (Telemetry.Metric m : t.metrics()) if (m.key.equals(key)) return m.value; return null; }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
