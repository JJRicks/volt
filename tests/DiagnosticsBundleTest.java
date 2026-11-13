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

        return new DiagnosticsBundle("1.7.0", WALL + 10000, false).phone("16", 36, "Google Pixel 9").report(report)
                .connections(connections, true, true).commands(commands, true, true).readings(t.metrics(), NOW).chargeSchedule(schedule()).evidence(new ChargeEvidence.Snapshot(true, evidence(1)));
    }

    private static long[] filled(int columns, long value) { long[] r = new long[columns]; Arrays.fill(r, value); return r; }
    /** A synthesized evidence book with every section at its cap (scale 1) or scale times over it. */
    static ChargeEvidence.Book evidence(int scale) {
        ChargeEvidence.Book b = new ChargeEvidence.Book();
        long start = WALL + 3_600_000L, wire = start / 1000 - 6 * 3600;
        for (int i = 0; i < ChargeEvidence.INTENTIONS; i++)
            b.intentions.add(new long[]{WALL + i, 2, start + i, start + 7_200_000L, wire, -21600, 41, 90, 1, ChargeScheduleController.Outcome.MISSED.ordinal(), start + 130_000L,
                    2, 41, 1, wire, 1, 1, 0, 1});
        b.open = new long[]{WALL + 50, 1, start + 86_400_000L, start + 172_800_000L, wire + 86_400, -21600, 52, 100, 0, ChargeEvidence.END_OPEN, ChargeEvidence.NONE, ChargeEvidence.NONE,
                ChargeEvidence.NONE, ChargeEvidence.NONE, ChargeEvidence.NONE, 1, ChargeEvidence.NONE, ChargeEvidence.NONE, 0};
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
                "\"loaded\": true", "\"storage_healthy\": true"})
            require(json.contains(expected), "evidence holds " + expected);
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
        require(log.contains("\"time_sent\": 1792000000") && log.contains("\"offset_sent_s\": -21600"), "charge_start carries the start and offset sent");
    }

    /**
     * json()'s final check covers the new sections: a private value that is all digits (as a serial or a MAC without
     * separators could be), planted in any numeric field of the evidence, withholds the file.
     */
    private static void evidenceSecrets() {
        String secret = "248813";
        int[][] plants = {{0, ChargeEvidence.I_WIRE}, {0, ChargeEvidence.I_ARMED_SOC}, {1, ChargeEvidence.X_OFFSET}, {2, ChargeEvidence.T_MV}, {2, ChargeEvidence.T_ELAPSED},
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
        String json = worst.json(Collections.<String>emptyList());
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
                "a1b2c3d4e5f60708090a", "scooter_serial", "battery_serial_raw", "fault_history_raw", "raw_diagnostic_payloads"};
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
