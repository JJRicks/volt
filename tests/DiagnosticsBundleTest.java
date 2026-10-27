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
                .connections(connections, true, true).commands(commands, true, true).readings(t.metrics(), NOW);
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
                "\"readings\": [", "\"key\": \"display_units\"", "\"key\": \"meter_version\"", "\"source\": \"0x70\""})
            require(json.contains(expected), "bundle holds " + expected);
        require(!json.contains("\"key\": \"pin\",\n          \"value\""), "the PIN event carries no value");
    }

    /** None of the fixture's private values, in any spelling, nor any payload-length hex run. */
    private static void noForbiddenData(String json) {
        String upper = json.toUpperCase(Locale.ROOT);
        String[] forbidden = {SERIAL, MAC, MAC.replace(":", ""), OWNER_KEY, NAME, PROFILE_NAME, BATTERY_SERIAL, PIN,
                Protocol.hex(BATTERY_SERIAL.getBytes(StandardCharsets.US_ASCII)), Protocol.hex(SERIAL.getBytes(StandardCharsets.US_ASCII)),
                Protocol.hex(Protocol.auth(Protocol.fromHex(OWNER_KEY), 1, 0)), Protocol.hex(PIN.getBytes(StandardCharsets.US_ASCII)), "0107030905",
                "a1b2c3d4e5f60708090a", "scooter_serial", "battery_serial_raw", "fault_history_raw", "raw_diagnostic_payloads"};
        for (String secret : forbidden) require(!upper.contains(secret.toUpperCase(Locale.ROOT)), "never in the bundle: " + secret);
        require(!java.util.regex.Pattern.compile("[0-9A-Fa-f]{12,}").matcher(json.replaceAll("\"(created_)?wall_ms\": \\d+", "").replaceAll("\"report_id\": \"[0-9a-f]{16}\"", "")).find(),
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
