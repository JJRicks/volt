package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

/**
 * G9 golden reports: builds the volt-diagnostics-v1 JSON, a ride journal export (the ride CSV), a phone
 * ride CSV and its GPX from fixed fixtures, using only the pure classes that make them. tests/l10n/golden.sh
 * runs this under several default locales and time zones and compares the output with tests/l10n/golden/
 * byte for byte, so these English, ROOT-formatted files cannot change during localization.
 *
 * The fixtures are synthetic. Every value is chosen to print the same with any JDK's Double.toString.
 */
public final class GoldenReports {
    private static final long WALL = 1792026000000L, NOW = 500_000L;

    private GoldenReports() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: GoldenReports <output folder>");
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + out);
        File work = new File(out, ".work");
        deleteTree(work);
        try {
            write(new File(out, "diagnostics.json"), diagnostics().json(Collections.<String>emptyList()));
            write(new File(out, "ride-journal.csv"), rideJournal(new File(work, "rides")));
            PhoneRideStore.Track track = phoneTrack(new File(work, "phone"));
            write(new File(out, "phone-track.csv"), track.toCsv());
            write(new File(out, "phone-track.gpx"), track.toGpx());
        } finally {
            deleteTree(work);
        }
    }

    /** The scooter's readings: settings, battery, firmware, a home report and a v1 ride report. */
    static Telemetry telemetry(long at, int speedTenths) {
        Telemetry t = new Telemetry();
        t.accept(0x70, response(settings()), at);
        t.accept(0x72, response(battery()), at);
        t.accept(0x73, response("2031V1.4".getBytes(StandardCharsets.US_ASCII)), at);
        byte[] home = new byte[18];
        home[0] = 0; home[1] = 3; home[2] = 67; home[3] = 0; home[4] = 0; home[6] = 31; home[7] = 2;
        t.accept(0x90, response(home), at);
        byte[] ride = new byte[18];
        ride[0] = 66; ride[1] = 1;
        put(ride, 2, speedTenths, 2); ride[4] = 30; put(ride, 5, 47, 2); ride[7] = 12;
        put(ride, 8, 252, 2); put(ride, 10, 161, 2); put(ride, 12, 0, 2); put(ride, 14, 12345, 4);
        t.accept(0x92, response(ride), at);
        return t;
    }

    static DiagnosticsBundle diagnostics() {
        Telemetry t = telemetry(NOW - 1000, 185);
        List<ConnectionEventLog.Record> connections = Arrays.asList(
                new ConnectionEventLog.Record(WALL, NOW - 9000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1, true, false, ConnectionEventLog.Phase.CONNECTING, -1, -1),
                new ConnectionEventLog.Record(WALL + 2000, NOW - 7000, ConnectionEventLog.Reason.AUTHENTICATED_READY, 0, true, false, ConnectionEventLog.Phase.READY, 40, 20),
                new ConnectionEventLog.Record(WALL + 9000, NOW - 1000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 8, true, false, ConnectionEventLog.Phase.READY, 1000, 500));
        List<CommandLog.Record> commands = new ArrayList<>();
        commands.add(new CommandLog.Session(WALL + 2100, "1.11.1", "25462", ModelRegistry.Layer.OEM_GROUP, "2031", "", false));
        commands.add(new CommandLog.Command(WALL + 3000, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.ACK, -1, null, null, 120));
        commands.add(new CommandLog.Command(WALL + 3200, CommandLog.Kind.SET, MutationGate.Origin.USER, "headlight", 1, CommandLog.Outcome.READBACK_SAME, -1, null, 1, 640));
        commands.add(new CommandLog.Command(WALL + 5000, CommandLog.Kind.TRY, MutationGate.Origin.GUIDED_TEST, "prompt_tone", 4, CommandLog.Outcome.READBACK_OTHER, -1, null, 3, 900));
        commands.add(new CommandLog.Command(WALL + 6000, CommandLog.Kind.SET, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW, "maximum_speed", 30, CommandLog.Outcome.REFUSED, -1,
                CapabilityResolver.Reason.MOVING, null, -1));
        DeviceDescriptor d = DeviceDescriptor.of("N2546GOLDEN000001", "25462", 1, new DeviceDescriptor.Firmware("2031", null, null, null, null, NOW), true,
                DeviceDescriptor.Transport.STANDARD, 0);
        CompatibilityReport report = new CompatibilityReport.Builder("1.11.1", new Random(4)).device(d, ModelRegistry.resolve(d), "aes")
                .fields(StatusFields.of(frame(0x70, settings()), NOW)).build();
        return new DiagnosticsBundle("1.11.1", WALL + 10000, false).phone("16", 36, "Google Pixel 9").report(report)
                .connections(connections, true, true).commands(commands, true, true).readings(t.metrics(), NOW).chargeSchedule(schedule());
    }

    /** A missed manual start, as DiagnosticsBundleTest stores it. */
    static ChargeScheduleController.Diagnostics schedule() {
        final long start = WALL + 3_600_000L, wire = start / 1000 - 6 * 3600;
        final String record = "5|0|480|" + start + "|" + wire + "|" + (start + 86_400_000L) + "|" + WALL + "|100.0|0.0|true|" + start + "|0|-1.0"
                + "|41.0|" + ChargeScheduleController.Outcome.MISSED.ordinal() + "|" + (start + 130_000L) + "|" + start + "|41.0|1|1|" + wire + "|2|41.0|false"
                + "|" + WALL + "|" + (WALL + 120_000L) + "|" + (WALL + 126_000L) + "|" + ChargeScheduleTest.Outcome.PASSED.ordinal() + "|1|true";
        final ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        c.scope = "golden"; c.ready = true; c.now = start + 140_000L; c.enabled = 1; c.wireStart = wire; c.zone = TimeZone.getTimeZone("America/Boise");
        return new ChargeScheduleController(new ChargeScheduleController.Store() {
            public String read(String scope) { return "golden".equals(scope) ? record : null; }
            public void write(String scope, String value) { throw new AssertionError("diagnostics never write"); }
        }, new ChargeScheduleController.Driver() {
            public ChargeScheduleController.Context context() { return c; }
            public void clock(java.util.function.Consumer<Boolean> done) { throw new AssertionError("no radio"); }
            public void time(long wall, int offset, java.util.function.Consumer<Boolean> done) { throw new AssertionError("no radio"); }
            public void enabled(boolean on, java.util.function.Consumer<Boolean> done) { throw new AssertionError("no radio"); }
        }).diagnostics();
    }

    /** A ride as ScooterService records it: two frames around a Bluetooth gap, then an automatic stop. */
    static String rideJournal(File dir) throws IOException {
        RideStore store = new RideStore(dir);
        String id = store.start("UT5 MAX", "Morning ride", WALL, NOW);
        store.sample(WALL + 1000, NOW + 1000, samples(telemetry(NOW + 500, 185)));
        store.markGap(WALL + 2000, NOW + 2000, "Bluetooth disconnected");
        store.heartbeat(WALL + 3000, NOW + 3000);
        store.setCurrentDirection(RideStore.CurrentDirection.NEGATIVE_IS_DRAW);
        store.sample(WALL + 4000, NOW + 4000, samples(telemetry(NOW + 3500, 205)));
        store.stop(WALL + 5000, NOW + 5000, RideStore.STOP_AUTO_PARKED);
        File export = new File(dir.getParentFile(), "ride-export.csv");
        store.exportCsv(id, export);
        return read(export);
    }

    private static Map<String, RideStore.Sample> samples(Telemetry t) {
        Map<String, RideStore.Sample> values = new LinkedHashMap<>();
        for (Telemetry.Metric m : t.metrics())
            values.put(m.key, new RideStore.Sample(m.numericValue, m.value, "", m.sourceCmd, m.updatedMs));
        return values;
    }

    /** A mounted phone ride: GPS with altitude, scooter telemetry, one rejected fix and a gap (two segments). */
    static PhoneRideStore.Track phoneTrack(File dir) throws IOException {
        PhoneRideStore store = new PhoneRideStore(dir);
        String id = "golden-ride-1";
        store.start(id, WALL, 1000, "mounted");
        StringBuilder j = new StringBuilder();
        // Coordinates are written as fixed decimal text, as the recorder's journal would hold them.
        String[] lats = {"43.61500", "43.61505", "43.61510", "43.61515", "43.61520"};
        for (int i = 0; i < 5; i++) {
            long mono = 2000 + i * 1000L;
            j.append(PhoneRideStore.row("TELEMETRY", WALL + mono - 1000, mono - 100, 18.0 + 2 * i, 250.0 + 2 * i, 67.0, mono - 100, mono - 100, mono - 100));
            j.append(PhoneRideStore.row("GPS", WALL + mono - 1000, mono, lats[i], "-116.20250", 4.0, 5.0 + i * 0.5, 0.5, 820.0 + i * 0.25, 3.0));
        }
        j.append(PhoneRideStore.row("GPS", WALL + 5000, 7000, "43.61525", "-116.20250", 50.0, 5.0, 0.5, 820.0, 3.0));
        j.append(PhoneRideStore.row("GAP", WALL + 6000, 8000));
        String[] later = {"43.61600", "43.61605", "43.61610"};
        for (int i = 0; i < 3; i++) {
            long mono = 20000 + i * 1000L;
            j.append(PhoneRideStore.row("GPS", WALL + mono - 1000, mono, later[i], "-116.20150", 6.0, 4.0, 0.5, 821.0, 3.0));
        }
        store.appendBatch(id, j.toString(), "");
        store.stop(id, WALL + 23000, 24000);
        return store.load(id);
    }

    private static byte[] settings() {
        byte[] d = new byte[44];
        d[1] = 3; d[2] = 0; d[3] = 1; d[4] = 1; d[5] = 60; d[6] = 2; d[7] = 1; d[8] = 1; d[10] = (byte) 0x81; d[11] = 1; d[12] = 0; d[13] = 0; d[15] = 1;
        d[16] = 0x01; d[17] = (byte) 0x82; d[18] = 0x10; d[19] = 3; d[20] = (byte) 0x99; d[21] = 2; d[25] = 40; d[26] = 0; d[27] = 0x01; d[28] = 0x02;
        d[29] = 0x03; d[30] = 0x04; d[31] = 90; d[32] = 0; d[33] = 1; d[34] = 2; d[37] = 5; d[38] = 0; d[39] = 0; d[42] = (byte) 0x8f;
        return d;
    }

    private static byte[] battery() {
        byte[] data = new byte[36];
        data[0] = 0; data[1] = 67;
        put(data, 2, 52250, 4);
        put(data, 6, 0x80000000L | 1250, 4);
        data[10] = 97; data[11] = (byte) 0x85; data[12] = 1;
        put(data, 13, 0x1234, 2);
        big(data, 15, 0x010203, 3); big(data, 18, 0x0405, 2); big(data, 20, 0x0607, 2);
        data[22] = 9;
        big(data, 23, 1350, 2);
        data[25] = 26; data[26] = 4; data[27] = 9;
        for (int i = 28; i < 34; i++) data[i] = (byte) (i - 27);
        big(data, 34, 0x0809, 2);
        return data;
    }

    private static Protocol.Frame frame(int cmd, byte[] payload) {
        byte[] d = response(payload);
        byte[] p = new byte[d.length + 8];
        p[0] = 0x55; p[1] = (byte) 0xaa; p[3] = (byte) cmd; p[4] = (byte) d.length;
        System.arraycopy(d, 0, p, 5, d.length);
        int sum = 0;
        for (int i = 0; i < p.length - 3; i++) sum += p[i] & 255;
        p[p.length - 3] = (byte) sum; p[p.length - 2] = (byte) 0xfe; p[p.length - 1] = (byte) 0xfd;
        return Protocol.parse(p);
    }

    private static byte[] response(byte[] payload) {
        byte[] data = new byte[payload.length + 1];
        System.arraycopy(payload, 0, data, 1, payload.length);
        return data;
    }

    private static void put(byte[] data, int offset, long value, int length) {
        for (int i = 0; i < length; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void big(byte[] data, int offset, long value, int length) {
        for (int i = 0; i < length; i++) data[offset + length - 1 - i] = (byte) (value >>> (8 * i));
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String read(File file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) bytes.write(buffer, 0, n);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot delete " + file);
    }
}
