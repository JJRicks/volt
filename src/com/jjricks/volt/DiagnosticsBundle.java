package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
 * return a file that contains any saved scooter's serial, MAC or owner key, or a chosen name in a value (never in a key or
 * in Volt's own words; see json). Pure and Android-free.
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
    /**
     * Values that carry text from outside Volt's code: readings, settings values and what the scooter reports about itself
     * (model ID, region, SKU, firmware). They never count as Volt's own words when json() looks for a chosen name.
     */
    private final Set<String> outside = new HashSet<>();

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
        Map<String, Object> t = report == null ? null : report.tree();
        // What the scooter says about itself; the rest of the report is Volt's own tokens.
        if (t != null && t.get("device") instanceof Map) for (String k : new String[]{"pid", "pid_prefix", "region", "sku", "firmware"})
            strings(((Map<?, ?>) t.get("device")).get(k), outside);
        tree.put("compatibility_report", t);
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
                if (!s.meter.isEmpty()) { e.put("meter_firmware", s.meter); outside.add(s.meter); }
                if (!s.controller.isEmpty()) { e.put("controller_firmware", s.controller); outside.add(s.controller); }
                outside.add(s.modelId);
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
                // charge_start and clock_sync, one convention: the local seconds sent on the wire, and the UTC instant they
                // stand for (seconds); the UTC offset sent is their difference (as charge_schedule's wire_start and clock_offset_s).
                if (c.time != null) e.put("wire_time_s", c.time);
                if (c.time != null && c.offset != null) e.put("utc_s", c.time - c.offset);
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
        // The rule for every value "at" a moment: from the first sample within moment_window_ms after it, on the connection
        // that was up at it; *_sample_after_ms says how long after; each sample value was at most reading_max_age_ms old.
        m.put("moment_window_ms", ChargeEvidence.MOMENT_MS); m.put("reading_max_age_ms", ChargeEvidence.READING_MAX_AGE_MS);
        List<Object> rows = new ArrayList<>();
        for (long[] r : intentions) rows.add(Arrays.asList(mode(r[ChargeEvidence.I_MODE]), rel(r[ChargeEvidence.I_ARMED], at), rel(r[ChargeEvidence.I_START], at),
                rel(r[ChargeEvidence.I_DEADLINE], at), num(r[ChargeEvidence.I_WIRE]), num(r[ChargeEvidence.I_OFFSET]),
                num(r[ChargeEvidence.I_TARGET]), num(r[ChargeEvidence.I_STARTED]), end(r[ChargeEvidence.I_END]), rel(r[ChargeEvidence.I_END_WALL], at),
                mode(r[ChargeEvidence.I_END_MODE]),
                num(r[ChargeEvidence.I_ARMED_READ]), num(r[ChargeEvidence.I_ARMED_SOC]), num(r[ChargeEvidence.I_LOCK_ARMED]),
                num(r[ChargeEvidence.I_START_READ]), num(r[ChargeEvidence.I_LOCK_START]),
                num(r[ChargeEvidence.I_END_READ]), num(r[ChargeEvidence.I_END_SOC]), num(r[ChargeEvidence.I_END_ENABLED]), num(r[ChargeEvidence.I_END_WIRE]), num(r[ChargeEvidence.I_LOCK_END]),
                rel(r[ChargeEvidence.I_CHANGED], at), mode(r[ChargeEvidence.I_CHANGED_MODE]), rel(r[ChargeEvidence.I_CHANGED_DEADLINE], at), num(r[ChargeEvidence.I_CHANGED_TARGET])));
        m.put("intentions", table(rows, "mode", "armed_ms", "start_ms", "deadline_ms", "wire_start", "clock_offset_s", "target", "started",
                "end", "end_ms", "end_mode", "arming_sample_after_ms", "soc_at_arming", "locked_at_arming", "start_sample_after_ms", "locked_at_start",
                "end_sample_after_ms", "end_soc", "end_schedule_on", "end_wire_start", "locked_at_end",
                // mode, deadline_ms and target are as armed; the last change to them before the plan ended, and when Volt saw it, else unknown.
                "changed_ms", "changed_mode", "changed_deadline_ms", "changed_target"));
        rows = new ArrayList<>();
        for (long[] r : b.tests) rows.add(Arrays.asList(rel(r[ChargeEvidence.X_WALL], at), rel(r[ChargeEvidence.X_START], at), name(ChargeScheduleTest.Outcome.values(), r[ChargeEvidence.X_OUTCOME]),
                num(r[ChargeEvidence.X_OFFSET]), num(r[ChargeEvidence.X_PAUSED]), num(r[ChargeEvidence.X_OFF]), name(ChargeScheduleController.Interruption.values(), r[ChargeEvidence.X_WHY])));
        // resume_minus_start_s: when charging resumed, in seconds after the start (not a clock offset).
        m.put("schedule_tests", table(rows, "wall_ms", "start_ms", "outcome", "resume_minus_start_s", "paused", "schedule_off", "interrupted_by"));
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
            r.put("group", metric.group); r.put("key", metric.key); r.put("value", metric.value); outside.add(metric.value);
            if (metric.updatedMs >= 0) r.put("age_ms", Math.max(0, nowElapsedMs - metric.updatedMs));
            if (metric.sourceCmd >= 0) r.put("source", CompatibilityReport.hex(metric.sourceCmd));
            out.add(r);
        }
        tree.put("readings", out);
        return this;
    }
    // ---- Part 2: app and phone state, settings, recordings, storage, the last crash, clock changes, drive modes and the
    // power sign. Every section is built inside a guard: a source that fails leaves its section null, never the file.

    /** How Volt was installed, as a class: the installer's package name itself never goes in the file. */
    static String installSource(String installer) {
        if (installer == null || installer.isEmpty()) return "none";
        switch (installer) {
            case "com.android.vending": return "play_store";
            case "com.google.android.packageinstaller": case "com.android.packageinstaller": return "package_installer";
            case "com.android.shell": return "adb";
            default: return "other";
        }
    }

    /** Volt's version code and how it was installed. */
    DiagnosticsBundle app(long versionCode, String installer) {
        guard("app", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version_code", versionCode > 0 ? versionCode : null);
            m.put("install_source", installSource(installer));
            return m;
        });
        return this;
    }

    /** The phone's state that support needs, as Android reports it when the report is built. A null field: Android did not say. */
    static final class Device {
        String phoneLocale, appLocale;
        Float fontScale;
        /** Configuration.uiMode's night bits: 0x10 no, 0x20 yes, 0 undefined. */
        Integer uiNightBits;
        Integer smallestWidthDp, widthDp, heightDp, densityDpi;
        Boolean batteryOptimisationExempt, powerSave, backgroundRestricted, notificationsEnabled, locationEnabled, bluetoothEnabled;
        /** UsageStatsManager's standby bucket (10 active ... 45 restricted). */
        Integer standbyBucket;
        /** Importance by channel id (CHANNELS only); -1: not created yet. */
        final Map<String, Integer> channels = new LinkedHashMap<>();
        /** Granted by short name (PERMISSIONS only). */
        final Map<String, Boolean> permissions = new LinkedHashMap<>();
    }
    /** Volt's own notification channels, by their fixed ids. */
    static final List<String> CHANNELS = Collections.unmodifiableList(Arrays.asList("scooter", "charge_alerts", "maintenance", "volt_updates"));
    /** The permissions the file reports, by short name. */
    static final List<String> PERMISSIONS = Collections.unmodifiableList(Arrays.asList(
            "bluetooth_scan", "bluetooth_connect", "fine_location", "coarse_location", "post_notifications"));
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}");

    DiagnosticsBundle phoneState(Device d) {
        guard("phone_state", () -> {
            if (d == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phone_locale", pattern(d.phoneLocale, LOCALE.pattern(), null));
            m.put("app_locale", pattern(d.appLocale, LOCALE.pattern(), null));
            m.put("font_scale_percent", d.fontScale == null || d.fontScale.isNaN() || d.fontScale.isInfinite() ? null : (long) Math.round(d.fontScale * 100));
            m.put("night_mode", d.uiNightBits == null ? null : d.uiNightBits == 0x20 ? "yes" : d.uiNightBits == 0x10 ? "no" : "undefined");
            Map<String, Object> screen = new LinkedHashMap<>();
            screen.put("smallest_width_dp", small(d.smallestWidthDp)); screen.put("width_dp", small(d.widthDp));
            screen.put("height_dp", small(d.heightDp)); screen.put("density_dpi", small(d.densityDpi));
            screen.put("width_class", d.widthDp == null || d.widthDp <= 0 ? null : d.widthDp < 600 ? "compact" : d.widthDp < 840 ? "medium" : "expanded");
            m.put("screen", screen);
            m.put("battery_optimisation_exempt", d.batteryOptimisationExempt);
            m.put("power_save", d.powerSave);
            m.put("background_restricted", d.backgroundRestricted);
            m.put("standby_bucket", d.standbyBucket == null ? null : bucket(d.standbyBucket));
            m.put("notifications_enabled", d.notificationsEnabled);
            Map<String, Object> channels = new LinkedHashMap<>();
            for (String id : CHANNELS) { Integer v = d.channels.get(id); channels.put(id, v == null ? null : v < 0 ? "not_created" : importance(v)); }
            m.put("notification_channels", channels);
            Map<String, Object> permissions = new LinkedHashMap<>();
            for (String p : PERMISSIONS) permissions.put(p, d.permissions.get(p));
            m.put("permissions", permissions);
            m.put("location_enabled", d.locationEnabled);
            m.put("bluetooth_enabled", d.bluetoothEnabled);
            return m;
        });
        return this;
    }
    private static Long small(Integer v) { return v == null || v < 0 || v > 100_000 ? null : (long) v; }
    private static String bucket(int b) {
        switch (b) { case 5: return "exempted"; case 10: return "active"; case 20: return "working_set"; case 30: return "frequent";
            case 40: return "rare"; case 45: return "restricted"; case 50: return "never"; default: return "other_" + Math.max(0, Math.min(999, b)); }
    }
    private static String importance(int i) {
        switch (i) { case 0: return "none"; case 1: return "min"; case 2: return "low"; case 3: return "default"; case 4: return "high"; case 5: return "max";
            default: return "unspecified"; }
    }

    /** The allow-listed settings (DiagnosticsSettings); the selected scooter's under their own names, never its id. */
    DiagnosticsBundle settings(Map<String, ?> stored, String profileId) {
        guard("settings", () -> { Map<String, Object> m = DiagnosticsSettings.section(stored, profileId); strings(m, outside); return m; });
        return this;
    }

    /** Where the recordings live and what the recorders hold now. Folders and stores may be null (not opened). */
    static final class Recordings {
        File rideFolder, phoneRideFolder, chargeFolder;
        /** RideStore.listCached(): newest first; null when its summaries are not in memory (nothing is read to get them). */
        List<RideStore.Summary> rides;
        boolean rideActive, chargeActive;
        long chargeActiveSamples = -1;
        long nowWallMs;
    }
    private static final Pattern RIDE_JOURNAL = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.csv");
    /** Bytes read from the newest charge recording: its first line and its end, never the whole file. */
    static final int CHARGE_HEAD_BYTES = 512, CHARGE_TAIL_BYTES = 4096;

    /**
     * Counts, sizes and ages of the ride and charge recordings, and the newest ride's and charge's length, samples and
     * end. Never a route, a coordinate, a label, a note, a scooter id or a file name. Ages come from the times each
     * recording holds (a ride's summary, a charge's first line), never from file times: a restore writes every file anew.
     */
    DiagnosticsBundle recordings(Recordings r) {
        guard("recordings", () -> {
            if (r == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            // A ride's start and end by id, from the summaries in memory (none are read to get them).
            Map<String, long[]> rideTimes = new HashMap<>();
            if (r.rides != null) for (RideStore.Summary s : r.rides)
                if (s != null && s.id != null && s.startWallMs > 0) rideTimes.put(s.id, new long[]{s.startWallMs, Math.max(s.startWallMs, s.endWallMs)});
            Map<String, Object> rides = folder(r.rideFolder, f -> RIDE_JOURNAL.matcher(f.getName()).matches());
            if (rides != null) {
                List<long[]> times = new ArrayList<>();
                if (r.rides != null) for (RideStore.Summary s : r.rides) if (s != null && s.id != null && rideTimes.containsKey(s.id)) times.add(rideTimes.get(s.id));
                ages(rides, r.rides == null ? null : times, r.nowWallMs);
                rides.put("recording_now", r.rideActive);
                rides.put("summaries_in_memory", r.rides != null);
                RideStore.Summary last = null;
                if (r.rides != null) for (RideStore.Summary s : r.rides) if (s != null && !s.active) { last = s; break; }
                rides.put("last", last == null ? null : lastRide(last));
            }
            m.put("rides", rides);
            Map<String, Object> phone = folder(r.phoneRideFolder, f -> f.getName().endsWith(PHONE_RIDE));
            if (phone != null) {
                // A phone ride belongs to the ride with its id: its age is that ride's.
                List<long[]> times = null;
                File[] files = r.rides == null ? null : r.phoneRideFolder.listFiles();
                if (files != null) {
                    times = new ArrayList<>();
                    for (File f : files) {
                        String name = f.getName();
                        long[] t = f.isFile() && name.endsWith(PHONE_RIDE) ? rideTimes.get(name.substring(0, name.length() - PHONE_RIDE.length())) : null;
                        if (t != null) times.add(t);
                    }
                }
                ages(phone, times, r.nowWallMs);
            }
            m.put("phone_rides", phone);
            Map<String, Object> charges = folder(r.chargeFolder, f -> f.getName().endsWith(".csv") || f.getName().endsWith(".active"));
            if (charges != null) {
                List<long[]> times = new ArrayList<>();
                File[] files = r.chargeFolder.listFiles();
                if (files != null) for (File f : files) {
                    if (!f.isFile() || !(f.getName().endsWith(".csv") || f.getName().endsWith(".active"))) continue;
                    long start = chargeStart(f);
                    if (start > 0) times.add(new long[]{start, start});
                }
                ages(charges, times, r.nowWallMs);
                charges.put("recording_now", r.chargeActive);
                if (r.chargeActive && r.chargeActiveSamples >= 0) charges.put("recording_samples", r.chargeActiveSamples);
                charges.put("last", lastCharge(r.chargeFolder));
            }
            m.put("charges", charges);
            return m;
        });
        return this;
    }
    private static final String PHONE_RIDE = "-phone.csv";
    /** A folder's matching files: count and bytes; null when it can't be listed. */
    private static Map<String, Object> folder(File dir, java.util.function.Predicate<File> counted) {
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        long count = 0, bytes = 0, all = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            long length = Math.max(0, f.length()); all += length;
            if (!counted.test(f)) continue;
            count++; bytes += length;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count); m.put("bytes", bytes); m.put("folder_bytes", all);
        return m;
    }
    /** The newest recording's age (from its end) and the oldest's (from its start), from {start, end} wall times; null when unknown. */
    private static void ages(Map<String, Object> m, List<long[]> times, long now) {
        long newest = Long.MIN_VALUE, oldest = Long.MAX_VALUE;
        if (times != null) for (long[] t : times) { newest = Math.max(newest, t[1]); oldest = Math.min(oldest, t[0]); }
        m.put("newest_age_s", newest == Long.MIN_VALUE ? null : Math.max(0, now - newest) / 1000);
        m.put("oldest_age_s", oldest == Long.MAX_VALUE ? null : Math.max(0, now - oldest) / 1000);
    }
    private static Map<String, Object> lastRide(RideStore.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("duration_s", Math.max(0, s.elapsedMs) / 1000);
        m.put("samples", Math.max(0, s.sampleCount));
        m.put("gaps", Math.max(0, s.gapCount));
        m.put("clock_discontinuities", Math.max(0, s.clockDiscontinuities));
        m.put("end", rideEnd(s.stopReason));
        m.put("recovered", s.recovered);
        m.put("direction_confirmed", s.directionConfirmed);
        return m;
    }
    /** A ride's end as a fixed token (the summary's stop reason is already the bare token; anything else reads as other). */
    static String rideEnd(String reason) {
        if (reason == null) return "recording";
        if (reason.isEmpty()) return "rider";
        if (reason.equals(RideStore.STOP_AUTO_NO_MOTION)) return "automatic_no_motion";
        if (reason.equals(RideStore.STOP_AUTO_PARKED) || reason.startsWith(RideStore.STOP_AUTO_PARKED + ";")) return "auto_parked_disconnect";
        if (reason.equals(RideStore.STOP_AUTO_QUIET) || reason.startsWith(RideStore.STOP_AUTO_QUIET + ";")) return "auto_quiet_disconnect";
        return "other";
    }
    /** A charge recording's start (its first line's wall time), from its first CHARGE_HEAD_BYTES only; -1 when unreadable. */
    static long chargeStart(File f) {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r")) {
            byte[] head = new byte[(int) Math.min(CHARGE_HEAD_BYTES, in.length())];
            in.readFully(head);
            String[] start = new String(head, java.nio.charset.StandardCharsets.UTF_8).split("\n", -1)[0].split(",", -1);
            return start.length == 5 && start[3].matches("[0-9]{1,15}") ? Long.parseLong(start[3]) : -1;
        } catch (IOException | RuntimeException unreadable) { return -1; }
    }
    /**
     * The newest finished charge recording (by the start it holds) and its length and end, from its first line and its
     * last few kilobytes only. The length runs to its last sample or pause line, whichever is later: a recording that
     * ends while paused closes the pause at its end.
     */
    static Map<String, Object> lastCharge(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        File newest = null; long newestStart = Long.MIN_VALUE;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".csv")) continue;
            long start = chargeStart(f);
            if (newest == null || start > newestStart) { newest = f; newestStart = start; }
        }
        if (newest == null) return null;
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(newest, "r")) {
            long length = in.length();
            long startWall = newestStart;
            int tail = (int) Math.min(CHARGE_TAIL_BYTES, length);
            byte[] end = new byte[tail];
            in.seek(length - tail); in.readFully(end);
            String[] lines = new String(end, java.nio.charset.StandardCharsets.UTF_8).split("\n");
            String stop = null; long lastWall = -1;
            for (int i = lines.length - 1; i >= 0; i--) {
                String[] p = lines[i].split(",", -1);
                if (stop == null && p.length == 2 && p[0].equals("end")) stop = chargeStop(p[1]);
                else if (p.length > 2 && (p[0].equals("s") || p[0].equals("p") && p.length == 4) && p[1].matches("[0-9]{1,15}")) {
                    lastWall = Math.max(lastWall, Long.parseLong(p[1]));
                    if (p[0].equals("s")) break; // nothing before the last sample ends later
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("duration_s", startWall > 0 && lastWall >= startWall ? (lastWall - startWall) / 1000 : null);
            m.put("end", stop == null ? "unknown" : stop);
            m.put("bytes", length);
            return m;
        } catch (IOException | RuntimeException unreadable) { return null; }
    }
    private static String chargeStop(String name) {
        for (ChargeSessionStore.Stop s : ChargeSessionStore.Stop.values()) if (s.name().equals(name)) return lower(s);
        return "other";
    }

    /** Free space as a class, and whether the recorders' storage is open and its last write worked. */
    DiagnosticsBundle storage(Long freeBytes, Boolean rideStoreOpen, Boolean rideWriteFailed, Boolean chargeStoreOpen) {
        guard("storage", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("free_space", freeBytes == null || freeBytes < 0 ? null : freeClass(freeBytes));
            m.put("ride_store_open", rideStoreOpen);
            m.put("ride_last_write_failed", rideWriteFailed);
            m.put("charge_store_open", chargeStoreOpen);
            return m;
        });
        return this;
    }
    static String freeClass(long bytes) {
        long mb = 1024L * 1024;
        return bytes < 100 * mb ? "under_100_mb" : bytes < 1024 * mb ? "under_1_gb" : bytes < 10 * 1024 * mb ? "under_10_gb" : "10_gb_or_more";
    }

    /** The last uncaught crash Volt recorded (CrashRecord): classes and Volt's own frames only; null when there is none. */
    DiagnosticsBundle crash(CrashRecord c) {
        guard("last_crash", () -> {
            if (c == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wall_ms", c.wallMs);
            m.put("app_version", c.appVersion);
            List<Object> chain = new ArrayList<>();
            for (CrashRecord.Thrown t : c.chain) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("exception", t.exception);
                e.put("volt_frames", new ArrayList<Object>(t.frames));
                e.put("other_frames", (long) t.otherFrames);
                chain.add(e);
            }
            m.put("chain", chain);
            return m;
        });
        return this;
    }

    /** A wall clock step against the monotonic clock bigger than this between two connection events is a clock change. */
    static final long CLOCK_JUMP_MS = 2_000;
    static final int MAX_CLOCK_CHANGES = 30;
    /**
     * Clock changes seen in the connection log: between consecutive events, how far the wall clock moved beyond the
     * monotonic clock (a time zone change does not move it; setting the clock or a network time fix does), and phone
     * restarts. A restart is the monotonic clock (time since the phone started) going back, or reading less than the
     * wall time since the event before: the phone has started since then. (A clock set forward by more than the earlier
     * uptime reads as a restart too; a restart is far the likelier.) Explains timestamps that look out of order.
     */
    DiagnosticsBundle clockChanges(List<ConnectionEventLog.Record> records) {
        guard("clock_changes", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("threshold_ms", CLOCK_JUMP_MS);
            List<Object> changes = new ArrayList<>();
            long restarts = 0, compared = 0, left = 0;
            ConnectionEventLog.Record previous = null;
            if (records != null) for (ConnectionEventLog.Record r : records) {
                if (r == null) continue;
                if (previous != null) {
                    compared++;
                    if (r.elapsedMs < previous.elapsedMs || r.elapsedMs < r.wallMs - previous.wallMs) restarts++;
                    else {
                        long jump = (r.wallMs - previous.wallMs) - (r.elapsedMs - previous.elapsedMs);
                        if (Math.abs(jump) > CLOCK_JUMP_MS) {
                            if (changes.size() < MAX_CLOCK_CHANGES) {
                                Map<String, Object> c = new LinkedHashMap<>();
                                c.put("wall_ms", r.wallMs); c.put("jump_ms", jump); changes.add(c);
                            } else left++;
                        }
                    }
                }
                previous = r;
            }
            m.put("events_compared", compared);
            m.put("changes", changes);
            if (left > 0) m.put("changes_left_out", left);
            m.put("phone_restarts", restarts);
            return m;
        });
        return this;
    }

    /** This connection's drive modes and the current's direction (DiagnosticsTally), marked when they came from the simulator. */
    DiagnosticsBundle tally(DiagnosticsTally t, boolean simulated) {
        guard("power_sign", () -> { if (t == null) return null; Map<String, Object> m = t.powerSign(); m.put("simulated", simulated); return m; });
        guard("drive_modes", () -> { if (t == null) return null; Map<String, Object> m = t.driveModes(); m.put("simulated", simulated); return m; });
        return this;
    }

    /** Puts a section, or null when building it failed: one bad source never costs the file. */
    private void guard(String section, java.util.function.Supplier<Object> build) {
        Object value;
        try { value = build.get(); } catch (RuntimeException failed) { value = null; }
        tree.put(section, value);
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

    /** The file with only the identities check (no chosen names): see json(identities, names). */
    String json(Collection<String> identities) { return json(identities, null); }

    /**
     * The file, or null when it would carry a private value: a private shape anywhere (PRIVATE_SHAPES); any of the
     * identities (saved ids, serials, owner keys, MACs with and without separators) anywhere in the file; or one of the
     * chosen scooter names in a value. A name is never looked for in the keys, and a name that is one of Volt's own
     * words (vocabulary: a key, a fixed value, a word of either, a fixed reading text, a model name) is not looked for
     * at all: finding "Scooter" says nothing about anyone. Any other name in any value withholds the file. Values and
     * names are compared ignoring case. A null means the email goes without the file.
     */
    String json(Collection<String> identities, Collection<String> names) {
        String json = capped();
        if (privateShape(json)) return null;
        String upper = json.toUpperCase(Locale.ROOT);
        if (identities != null) for (String secret : identities) {
            if (secret == null) continue;
            String s = secret.trim().toUpperCase(Locale.ROOT);
            if (s.length() >= MIN_SECRET && upper.contains(s)) return null;
        }
        if (names != null && !names.isEmpty()) {
            Set<String> keys = new HashSet<>();
            List<String> values = new ArrayList<>();
            walk(tree, keys, values);
            Set<String> vocabulary = vocabulary(keys, values);
            for (String name : names) if (named(name, values, vocabulary)) return null;
        }
        return json;
    }

    /** Whether this chosen name is in one of the file's values; never when it is short or one of Volt's own words. */
    static boolean named(String name, List<String> values, Set<String> vocabulary) {
        if (name == null) return false;
        String n = name.trim();
        if (n.length() < MIN_SECRET || vocabulary.contains(normal(n))) return false;
        String upper = n.toUpperCase(Locale.ROOT);
        for (String v : values) if (v.toUpperCase(Locale.ROOT).contains(upper)) return true;
        return false;
    }

    /** A Volt token as the file writes it: an enum name, lowercased or not, or a snake_case id. */
    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_]{0,63}|[A-Z][A-Z0-9_]{0,63}");
    /** Words Volt writes outside this class's tokens: the readings' fixed texts and the simulator's mark. */
    private static final List<String> FIXED_WORDS = Collections.unmodifiableList(Arrays.asList("simulation", "simulated"));

    /**
     * Volt's own words in this file: every key, every value that is a Volt token and came from Volt's code (never one of
     * the outside values), each whole and split into words; Telemetry's fixed reading texts; the known model names. A
     * leaked name cannot make itself vocabulary: outside values are left out, and Volt's tokens do not hold free text.
     */
    private Set<String> vocabulary(Set<String> keys, List<String> values) {
        Set<String> v = new HashSet<>();
        for (String k : keys) words(k, v);
        for (String s : values) if (!outside.contains(s) && TOKEN.matcher(s).matches()) words(s, v);
        for (String s : Telemetry.FIXED_TEXT) words(s, v);
        for (String s : FIXED_WORDS) words(s, v);
        for (String s : ModelRegistry.knownNames()) words(s, v);
        return v;
    }
    private static void words(String s, Set<String> into) {
        String n = normal(s);
        if (n.isEmpty()) return;
        into.add(n);
        for (String w : n.split("_")) if (!w.isEmpty()) into.add(w);
    }
    /** Lower case, every run of other characters as one underscore: "Selected Scooter" and "selected_scooter" are one word. */
    static String normal(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
    /** Every key and every string value in a section, maps and lists alike. */
    @SuppressWarnings("unchecked")
    private static void walk(Object node, Set<String> keys, List<String> values) {
        if (node instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) node).entrySet()) { keys.add(e.getKey()); walk(e.getValue(), keys, values); }
        else if (node instanceof List) for (Object o : (List<Object>) node) walk(o, keys, values);
        else if (node instanceof String) values.add((String) node);
    }
    /** The string values in a section (the outside values). */
    private static void strings(Object node, Set<String> into) {
        List<String> values = new ArrayList<>();
        walk(node, new HashSet<>(), values);
        into.addAll(values);
    }

    /**
     * Shapes no part of the file may have, whatever its source: a Bluetooth address, an email address, a coordinate
     * (a number with five or more decimals; readings carry at most three), or a path into shared storage or a content
     * URI (folder and file names there can carry a person's or scooter's name). One scan over the whole file.
     */
    private static final Pattern PRIVATE_SHAPES = Pattern.compile(
            "(?i)(?<![0-9a-f])[0-9a-f]{2}([:-])[0-9a-f]{2}(\\1[0-9a-f]{2}){4}(?![0-9a-f])"
            + "|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"
            + "|[0-9]\\.[0-9]{5,}"
            + "|/storage/|/sdcard|/mnt/|/data/user/|/data/data/|content://|file://");
    static boolean privateShape(String file) { return file != null && PRIVATE_SHAPES.matcher(file).find(); }

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
