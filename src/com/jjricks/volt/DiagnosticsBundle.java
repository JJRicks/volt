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
        String json = CompatibilityReport.json(tree);
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

    private static String pattern(String v, String pattern, String fallback) { return v != null && v.matches(pattern) ? v : fallback; }
    private static String lower(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }
}
