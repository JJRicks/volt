package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The diagnostics file a report email can carry (volt-diagnostics-v1): Volt's and Android's versions, the
 * phone model, the sanitized compatibility report, the connection and command logs, the charging schedule record and
 * the current readings, without identities or payload bytes. Built only from typed or sanitized sources; json() then refuses to
 * return a file that contains any saved scooter's serial, MAC, owner key or chosen name. Pure and Android-free.
 */
final class DiagnosticsBundle {
    static final String FORMAT = "volt-diagnostics-v1";
    /** Readings that identify a scooter or battery, or are raw payload bytes: never in the file. */
    static final Set<String> WITHHELD = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "scooter_serial", "battery_serial_raw", "ride_read_raw", "fault_history_raw")));
    /** Printable ASCII, short, with no run of hex long enough to be a payload, key or MAC, and nothing serial-shaped. */
    private static final Pattern HEX_RUN = Pattern.compile("[0-9A-Fa-f]{12,}"), SERIAL = Pattern.compile("[A-Za-z][A-Za-z0-9]{16}");
    /** A secret shorter than this is too common to search for (a name like "Sport" can be a reading); longer ones must not appear. */
    static final int MIN_SECRET = 6;

    private final Map<String, Object> tree = new LinkedHashMap<>();

    DiagnosticsBundle(String appVersion, long createdWallMs, boolean simulated) {
        tree.put("format", FORMAT);
        tree.put("app_version", pattern(appVersion, CommandLog.APP_VERSION, "unknown"));
        tree.put("created_wall_ms", Math.max(0, createdWallMs));
        tree.put("simulated", simulated);
    }

    /** As Report an issue's email: the Android release and API level, and the phone's make and model. */
    DiagnosticsBundle phone(String androidRelease, int sdk, String phone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("android_release", pattern(androidRelease, "[0-9A-Za-z. ]{1,16}", "unknown"));
        m.put("android_sdk", (long) sdk);
        m.put("model", pattern(phone == null ? null : phone.trim(), "[0-9A-Za-z ._()+-]{1,64}", "unknown"));
        tree.put("phone", m);
        return this;
    }

    /** The current compatibility report, already sanitized by its own whitelist; null when there is none. */
    DiagnosticsBundle report(CompatibilityReport report) {
        tree.put("compatibility_report", report == null ? null : report.tree());
        return this;
    }

    /** The always-on connection log (ConnectionEventLog: enums and numbers only). */
    DiagnosticsBundle connections(List<ConnectionEventLog.Record> records, boolean loaded, boolean healthy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", loaded); m.put("storage_healthy", healthy);
        List<Object> events = new ArrayList<>();
        if (records != null) for (ConnectionEventLog.Record r : records) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("wall_ms", r.wallMs); e.put("elapsed_ms", r.elapsedMs);
            e.put("reason", r.reason.name()); e.put("phase", r.phase.name());
            if (r.statusCode >= 0) e.put("status_code", (long) r.statusCode);
            e.put("ui_visible", r.visible); e.put("recording", r.recording);
            if (r.statusAgeMs >= 0) e.put("status_age_ms", r.statusAgeMs);
            if (r.frameAgeMs >= 0) e.put("frame_age_ms", r.frameAgeMs);
            events.add(e);
        }
        m.put("events", events);
        tree.put("connection_log", m);
        return this;
    }

    /** The always-on command log (CommandLog: enums, catalogue keys, bounded numbers and checked versions). */
    DiagnosticsBundle commands(List<CommandLog.Record> records, boolean loaded, boolean healthy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", loaded); m.put("storage_healthy", healthy);
        List<Object> events = new ArrayList<>();
        if (records != null) for (CommandLog.Record r : records) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("wall_ms", r.wallMs);
            if (r instanceof CommandLog.Session) {
                CommandLog.Session s = (CommandLog.Session) r;
                e.put("type", "session"); e.put("app_version", s.appVersion);
                e.put("model_id", s.modelId.isEmpty() ? "unknown" : s.modelId); e.put("layer", lower(s.layer));
                if (!s.meter.isEmpty()) e.put("meter_firmware", s.meter);
                if (!s.controller.isEmpty()) e.put("controller_firmware", s.controller);
                e.put("simulated", s.simulated);
            } else {
                CommandLog.Command c = (CommandLog.Command) r;
                e.put("type", "command"); e.put("kind", lower(c.kind));
                if (c.origin != null) e.put("origin", lower(c.origin));
                e.put("key", c.key);
                if (c.value != null) e.put("value", (long) c.value);
                e.put("outcome", lower(c.outcome));
                if (c.code >= 0) e.put("code", (long) c.code);
                if (c.reason != null) e.put("reason", lower(c.reason));
                if (c.read != null) e.put("read", (long) c.read);
                if (c.elapsedMs >= 0) e.put("elapsed_ms", c.elapsedMs);
                // charge_start: the wire start sent; clock_sync: the wall seconds sent; each with the UTC offset sent.
                if (c.time != null) e.put("time_sent", c.time);
                if (c.offset != null) e.put("offset_sent_s", (long) c.offset);
            }
            events.add(e);
        }
        m.put("events", events);
        tree.put("command_log", m);
        return this;
    }

    private static final String[] SCHEDULE_MODES = {"none", "manual", "done_by"}, ALERTS = {"none", "pending", "shown"};
    /**
     * The selected scooter's charging schedule record (ChargeScheduleController.Diagnostics): times, the wire start and
     * clock offset sent, battery percentages, enums and the last Scheduled charging test. It has no scope or other identity; null when no scooter is selected.
     * Its writes and their readback outcomes are already in command_log (clock_sync, charge_start, timed_charging).
     */
    DiagnosticsBundle chargeSchedule(ChargeScheduleController.Diagnostics d) {
        if (d == null) { tree.put("charge_schedule", null); return this; }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", SCHEDULE_MODES[d.mode]);
        if (d.armedWallMs > 0) m.put("armed_wall_ms", d.armedWallMs);
        if (d.startWallMs > 0) m.put("start_wall_ms", d.startWallMs);
        if (d.deadlineWallMs > 0) m.put("deadline_wall_ms", d.deadlineWallMs);
        if (d.wireStart > 0) m.put("wire_start", d.wireStart);
        if (d.clockOffsetSeconds != null) m.put("clock_offset_s", (long) d.clockOffsetSeconds);
        if (d.armedSoc != null) m.put("soc_at_arming", (long) d.armedSoc);
        m.put("target", (long) d.target);
        m.put("outcome", lower(d.outcome));
        if (d.outcome != ChargeScheduleController.Outcome.NONE) {
            m.put("outcome_wall_ms", d.outcomeWallMs); m.put("outcome_mode", SCHEDULE_MODES[d.outcomeMode]);
            if (d.outcomeStartWallMs > 0) m.put("outcome_start_wall_ms", d.outcomeStartWallMs);
            if (d.outcomeArmedSoc != null) m.put("outcome_soc_at_arming", (long) d.outcomeArmedSoc);
            if (d.outcomeSoc != null) m.put("outcome_soc", (long) d.outcomeSoc);
            if (d.outcomeEnabled != null) m.put("outcome_enabled", (long) d.outcomeEnabled);
            if (d.outcomeWireStart != null) m.put("outcome_wire_start", d.outcomeWireStart);
        }
        m.put("alert", ALERTS[d.alert]);
        if (d.testOutcome != ChargeScheduleTest.Outcome.NONE) {
            // The last Scheduled charging test: resume minus start in seconds, and whether OFF was confirmed afterwards.
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("wall_ms", d.testWallMs); t.put("start_wall_ms", d.testStartWallMs); t.put("outcome", lower(d.testOutcome));
            if (d.testOffsetSeconds != null) t.put("offset_s", (long) d.testOffsetSeconds);
            t.put("paused", d.testPaused);
            if (d.testScheduleOff != null) t.put("schedule_off", (long) d.testScheduleOff);
            m.put("test", t);
        }
        if (d.readWallMs != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("wall_ms", d.readWallMs);
            if (d.readEnabled != null) r.put("enabled", (long) d.readEnabled);
            if (d.readWireStart != null) r.put("wire_start", d.readWireStart);
            m.put("readback", r);
        }
        tree.put("charge_schedule", m);
        return this;
    }

    /**
     * The selected scooter's charging evidence (ChargeEvidence): ended intentions and tests, the charging timeline, dense
     * windows, schedule operations and changes Volt did not make. Numbers and enum names only; every wall time is
     * milliseconds after base_wall_ms, so the file has no long run of digits a private value could hide in.
     */
    DiagnosticsBundle evidence(ChargeEvidence.Snapshot snapshot) {
        if (snapshot == null) { tree.put("charge_evidence", null); return this; }
        ChargeEvidence.Book b = snapshot.book;
        long base = Long.MAX_VALUE;
        List<long[]> intentions = new ArrayList<>(b.intentions); if (b.open != null) intentions.add(b.open);
        for (long[] r : intentions) base = min(base, r[ChargeEvidence.I_ARMED], r[ChargeEvidence.I_START]);
        for (long[] r : b.tests) base = min(base, r[ChargeEvidence.X_WALL]);
        for (long[] r : b.timeline) base = min(base, r[ChargeEvidence.T_WALL]);
        for (long[] r : b.operations) base = min(base, r[ChargeEvidence.O_WALL]);
        for (long[] r : b.changes) base = min(base, r[ChargeEvidence.C_OLD_WALL], r[ChargeEvidence.C_WALL]);
        for (List<long[]> w : b.windowSamples) for (long[] r : w) base = min(base, r[ChargeEvidence.D_WALL]);
        for (long[] r : b.windows) base = min(base, r[ChargeEvidence.W_ANCHOR_WALL]);
        if (base == Long.MAX_VALUE) base = 0;
        final long at = base;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", snapshot.loaded); m.put("storage_healthy", b.healthy); m.put("base_wall_ms", at);
        List<Object> rows = new ArrayList<>();
        for (long[] r : intentions) rows.add(Arrays.asList(mode(r[ChargeEvidence.I_MODE]), rel(r[ChargeEvidence.I_ARMED], at), rel(r[ChargeEvidence.I_START], at),
                rel(r[ChargeEvidence.I_DEADLINE], at), num(r[ChargeEvidence.I_WIRE]), num(r[ChargeEvidence.I_OFFSET]), num(r[ChargeEvidence.I_ARMED_SOC]),
                num(r[ChargeEvidence.I_TARGET]), num(r[ChargeEvidence.I_STARTED]), end(r[ChargeEvidence.I_END]), rel(r[ChargeEvidence.I_END_WALL], at),
                mode(r[ChargeEvidence.I_END_MODE]),
                num(r[ChargeEvidence.I_END_SOC]), num(r[ChargeEvidence.I_END_ENABLED]), num(r[ChargeEvidence.I_END_WIRE]),
                num(r[ChargeEvidence.I_LOCK_ARMED]), r[ChargeEvidence.I_START_SEEN] == 1 ? num(r[ChargeEvidence.I_LOCK_START]) : null, num(r[ChargeEvidence.I_LOCK_END])));
        m.put("intentions", table(rows, "mode", "armed_ms", "start_ms", "deadline_ms", "wire_start", "clock_offset_s", "soc_at_arming", "target", "started",
                "end", "end_ms", "end_mode", "end_soc", "end_schedule_on", "end_wire_start", "locked_at_arming", "locked_at_start", "locked_at_end"));
        rows = new ArrayList<>();
        for (long[] r : b.tests) rows.add(Arrays.asList(rel(r[ChargeEvidence.X_WALL], at), rel(r[ChargeEvidence.X_START], at), name(ChargeScheduleTest.Outcome.values(), r[ChargeEvidence.X_OUTCOME]),
                num(r[ChargeEvidence.X_OFFSET]), num(r[ChargeEvidence.X_PAUSED]), num(r[ChargeEvidence.X_OFF]), name(ChargeScheduleController.Interruption.values(), r[ChargeEvidence.X_WHY])));
        m.put("schedule_tests", table(rows, "wall_ms", "start_ms", "outcome", "offset_s", "paused", "schedule_off", "interrupted_by"));
        rows = new ArrayList<>();
        for (long[] r : b.timeline) rows.add(Arrays.asList(name(ChargeEvidence.Kind.values(), r[ChargeEvidence.T_KIND]), rel(r[ChargeEvidence.T_WALL], at), num(r[ChargeEvidence.T_ELAPSED]),
                num(r[ChargeEvidence.T_CHARGING]), num(r[ChargeEvidence.T_SOC]), num(r[ChargeEvidence.T_MV]), num(r[ChargeEvidence.T_MA]), num(r[ChargeEvidence.T_W]),
                num(r[ChargeEvidence.T_TEMP]), num(r[ChargeEvidence.T_ENABLED]), num(r[ChargeEvidence.T_WIRE]), num(r[ChargeEvidence.T_LOCK]), num(r[ChargeEvidence.T_VISIBLE]),
                num(r[ChargeEvidence.T_SKIPPED])));
        m.put("charging_timeline", table(rows, "kind", "wall_ms", "elapsed_ms", "charging", "soc", "voltage_mv", "current_ma", "power_w", "temperature_c",
                "schedule_on", "wire_start", "locked", "in_sight", "skipped_changes"));
        List<Object> windows = new ArrayList<>();
        for (int i = 0; i < b.windows.size() && i < b.windowSamples.size(); i++) {
            long[] w = b.windows.get(i);
            rows = new ArrayList<>();
            for (long[] r : b.windowSamples.get(i)) rows.add(Arrays.asList(rel(r[ChargeEvidence.D_WALL], at), num(r[ChargeEvidence.D_ELAPSED]), num(r[ChargeEvidence.D_CHARGING]),
                    num(r[ChargeEvidence.D_SOC]), num(r[ChargeEvidence.D_MV]), num(r[ChargeEvidence.D_MA]), num(r[ChargeEvidence.D_W]), num(r[ChargeEvidence.D_ENABLED]), num(r[ChargeEvidence.D_LOCK])));
            Map<String, Object> t = table(rows, "wall_ms", "elapsed_ms", "charging", "soc", "voltage_mv", "current_ma", "power_w", "schedule_on", "locked");
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("kind", name(ChargeEvidence.Window.values(), w[ChargeEvidence.W_KIND])); window.put("anchor_ms", rel(w[ChargeEvidence.W_ANCHOR_WALL], at));
            window.putAll(t); windows.add(window);
        }
        m.put("dense_windows", windows);
        rows = new ArrayList<>();
        for (long[] r : b.operations) rows.add(Arrays.asList(rel(r[ChargeEvidence.O_WALL], at), num(r[ChargeEvidence.O_ELAPSED]), name(ChargeScheduleController.Op.values(), r[ChargeEvidence.O_OP]),
                name(ChargeScheduleController.Result.values(), r[ChargeEvidence.O_RESULT]), name(ChargeScheduleController.Interruption.values(), r[ChargeEvidence.O_WHY]),
                num(r[ChargeEvidence.O_DURATION]), rel(r[ChargeEvidence.O_TEST_WALL], at), name(ChargeScheduleTest.Outcome.values(), r[ChargeEvidence.O_TEST_OUTCOME])));
        m.put("schedule_operations", table(rows, "wall_ms", "elapsed_ms", "op", "result", "interrupted_by", "duration_ms", "test_ms", "test_outcome"));
        rows = new ArrayList<>();
        for (long[] r : b.changes) rows.add(Arrays.asList(rel(r[ChargeEvidence.C_WALL], at), num(r[ChargeEvidence.C_ELAPSED]), name(ChargeEvidence.Watched.values(), r[ChargeEvidence.C_KEY]),
                num(r[ChargeEvidence.C_OLD]), num(r[ChargeEvidence.C_NEW]), rel(r[ChargeEvidence.C_OLD_WALL], at), num(r[ChargeEvidence.C_ACROSS])));
        m.put("changes_not_by_volt", table(rows, "wall_ms", "elapsed_ms", "reading", "from", "to", "from_ms", "across_disconnect"));
        tree.put("charge_evidence", m);
        return this;
    }
    /** Lines per Preview block: small enough that a block lays out in a few milliseconds. */
    static final int PREVIEW_LINES = 40;
    /**
     * The file in blocks of PREVIEW_LINES lines for Preview's recycled list, so opening lays out only the blocks on
     * screen: one TextView holding a 500 KB file stalled the main thread for about 5 s. Joined with "\n" the blocks are
     * the file exactly.
     */
    static List<String> previewBlocks(String file) {
        List<String> blocks = new ArrayList<>();
        if (file == null) return blocks;
        int from = 0, lines = 0;
        for (int i = 0; i < file.length(); i++) {
            if (file.charAt(i) != '\n' || ++lines < PREVIEW_LINES) continue;
            blocks.add(file.substring(from, i)); from = i + 1; lines = 0;
        }
        blocks.add(file.substring(from));
        return blocks;
    }
    private static long min(long base, long... walls) { for (long w : walls) if (w != ChargeEvidence.NONE && w > 0 && w < base) base = w; return base; }
    private static Long rel(long wall, long base) { return wall == ChargeEvidence.NONE || wall <= 0 ? null : wall - base; }
    private static Long num(long v) { return v == ChargeEvidence.NONE ? null : v; }
    private static String name(Enum<?>[] values, long ordinal) { return ordinal == ChargeEvidence.NONE ? null : ordinal >= 0 && ordinal < values.length ? lower(values[(int) ordinal]) : "unknown"; }
    private static String end(long end) {
        return end == ChargeEvidence.END_OPEN ? "open" : end == ChargeEvidence.END_REPLACED ? "replaced" : name(ChargeScheduleController.Outcome.values(), end);
    }
    private static String mode(long mode) { return mode >= 0 && mode <= 2 ? SCHEDULE_MODES[(int) mode] : null; }
    private static Map<String, Object> table(List<Object> rows, String... columns) {
        Map<String, Object> t = new LinkedHashMap<>(); t.put("columns", new ArrayList<Object>(Arrays.asList(columns))); t.put("rows", rows); return t;
    }

    /**
     * The current readings, as Live Stats › Diagnostics exports them, less what identifies a scooter or is raw
     * bytes (WITHHELD), and less any value that is not short printable text without a long hex run.
     */
    DiagnosticsBundle readings(List<Telemetry.Metric> metrics, long nowElapsedMs) {
        List<Object> out = new ArrayList<>();
        if (metrics != null) for (Telemetry.Metric metric : metrics) {
            if (metric.value == null || WITHHELD.contains(metric.key) || !reading(metric.key, metric.value)) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("group", metric.group); r.put("key", metric.key); r.put("value", metric.value);
            if (metric.updatedMs >= 0) r.put("age_ms", Math.max(0, nowElapsedMs - metric.updatedMs));
            if (metric.sourceCmd >= 0) r.put("source", CompatibilityReport.hex(metric.sourceCmd));
            out.add(r);
        }
        tree.put("readings", out);
        return this;
    }
    // Telemetry formats battery temperature with a degree sign. Allow only that typed numeric
    // value, not arbitrary Unicode in diagnostic strings or other fields.
    static boolean reading(String key, String value) {
        return reading(value) || ("temperature_c".equals(key) && value != null
                && value.matches("-?[0-9]{1,3} °C"));
    }
    static boolean reading(String value) {
        if (value == null || value.length() > 80) return false;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); if (c < 0x20 || c > 0x7e) return false; }
        return !HEX_RUN.matcher(value).find() && !SERIAL.matcher(value).find();
    }

    /**
     * The file, or null when it would contain any of these private values (saved serials, MACs with and without
     * separators, owner keys, chosen names). A null means the email goes without it.
     */
    String json(Collection<String> secrets) {
        String json = capped();
        if (secrets != null) {
            String upper = json.toUpperCase(Locale.ROOT);
            for (String secret : secrets) {
                if (secret == null) continue;
                String s = secret.trim().toUpperCase(Locale.ROOT);
                if (s.length() >= MIN_SECRET && upper.contains(s)) return null;
            }
        }
        return json;
    }

    /** The file's size target in the worst case the logs' own caps allow, and the hard cap enforced here. */
    static final int TARGET_BYTES = 512 * 1024, MAX_BYTES = 640 * 1024;
    /**
     * The file within MAX_BYTES: over it, the oldest dense samples go first (oldest window first), then the oldest timeline
     * rows, and the file says how many were left out. Only the charging evidence is ever trimmed.
     */
    @SuppressWarnings("unchecked")
    private String capped() {
        String json = CompatibilityReport.json(tree);
        Object e = tree.get("charge_evidence");
        if (json.length() <= MAX_BYTES || !(e instanceof Map)) return json;
        Map<String, Object> m = (Map<String, Object>) e;
        long dropped = 0;
        while (json.length() > MAX_BYTES) {
            int over = json.length() - MAX_BYTES, removed = 0;
            for (Object w : (List<Object>) m.get("dense_windows")) {
                List<Object> rows = (List<Object>) ((Map<String, Object>) w).get("rows");
                while (!rows.isEmpty() && removed * 60 < over) { rows.remove(0); removed++; }
            }
            List<Object> timeline = (List<Object>) ((Map<String, Object>) m.get("charging_timeline")).get("rows");
            while (removed * 60 < over && !timeline.isEmpty()) { timeline.remove(0); removed++; }
            if (removed == 0) break;
            dropped += removed;
            m.put("rows_left_out_for_size", dropped);
            json = CompatibilityReport.json(tree);
        }
        return json;
    }

    private static String pattern(String v, String pattern, String fallback) { return v != null && v.matches(pattern) ? v : fallback; }
    private static String lower(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }
}
