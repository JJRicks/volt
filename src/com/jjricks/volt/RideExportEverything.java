package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The Everything (JSON) ride export: one UTF-8 JSON file holding everything Volt recorded for one ride, each value on
 * the clock it was recorded with. docs/ride-export-everything.md is the format's description; this class writes it.
 *
 * What it holds: the scooter sensor journal (every committed frame and every non-frame row, readings kept once per
 * run of frames that repeated them), the ride's analysis, the phone journal's rows by event (GPS fixes with their own
 * fix times, pressure, telemetry, motion summaries, gaps), every motion sample, the phone track as Track.toJson
 * writes it, the current direction at the start and each change, the time zone when the ride recorded one, the app
 * version and the wall/monotonic clock pairs a reader needs to place everything on one timeline.
 *
 * What it leaves out: readings that identify the scooter or battery or are raw payload bytes
 * (DiagnosticsBundle.WITHHELD), and the Volt scooter profile id (START's key and the analysis's scooterId). The
 * privacy object lists what was left out of this file.
 *
 * Written as a stream: the phone files and the motion file are copied row by row, and only the scooter readings are
 * held (as JSON text, once per change) until the scooter part is written. English and locale-independent; it never
 * reads the catalog (RideExportEverythingTest proves it under a throwing text source).
 */
final class RideExportEverything {
    private RideExportEverything() { }

    /** The file's format id and version: a reader checks both before reading anything else. */
    static final String FORMAT = "volt-ride-everything";
    static final int VERSION = 1;
    /** START details on rides recorded since this export existed: the phone's time zone id when the ride began. */
    static final String TIME_ZONE_DETAIL = "time_zone=";

    /** The scooter journal's columns, as RideStore writes them. */
    private static final int SCHEMA = 0, EVENT = 1, WALL = 3, MONO = 4, FRAME = 5, KEY = 6, NUMERIC = 7, TEXT = 8,
            UNIT = 9, CMD = 10, UPDATED = 11, DIRECTION = 13, DETAILS = 14, COLUMNS = 15;
    /** A phone journal or motion line longer than this is not one Volt wrote; it is skipped and counted. */
    private static final int MAX_LINE = 65536;

    /** Phone journal events with named columns, in the order the file lists them. Any other event keeps a..j. */
    private static final String[][] PHONE_EVENTS = {
            {"START", "wall_ms", "monotonic_ms", "placement"},
            {"GPS", "fix_time_utc_ms", "fix_monotonic_ms", "latitude", "longitude", "accuracy_m", "speed_mps",
                    "speed_accuracy_mps", "altitude_m", "vertical_accuracy_m"},
            {"PRESSURE", "wall_ms", "sensor_monotonic_ms", "pressure_hpa"},
            {"TELEMETRY", "wall_ms", "monotonic_ms", "speed_kmh", "power_w", "battery_percent",
                    "speed_updated_monotonic_ms", "power_updated_monotonic_ms", "battery_updated_monotonic_ms"},
            {"MOTION", "wall_ms", "monotonic_ms", "motion_rows", "peak_linear_mps2"},
            {"GAP", "wall_ms", "monotonic_ms"},
            {"STOP", "wall_ms", "monotonic_ms"}};
    private static final String[] OTHER_PHONE_EVENT = {"", "wall_ms", "monotonic_ms", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};

    /**
     * Writes the file. journal is the scooter sensor journal (null or missing for a phone-only recording); summary its
     * analysis (null when there is none); phoneRaw and motion the phone files (null or missing when not recorded);
     * track the loaded phone recording (null when there is none or it could not be read).
     */
    static void write(File output, String rideId, String appVersion, File journal, RideStore.Summary summary,
                      File phoneRaw, File motion, PhoneRideStore.Track track) throws IOException {
        Scooter scooter = journal != null && journal.isFile() ? Scooter.scan(journal) : null;
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8), 65536)) {
            write(out, rideId, appVersion, scooter, summary, phoneRaw, motion, track);
        }
    }

    private static void write(Writer out, String rideId, String appVersion, Scooter scooter, RideStore.Summary summary,
                              File phoneRaw, File motion, PhoneRideStore.Track track) throws IOException {
        List<String[]> anchors = new ArrayList<>();
        out.write("{\"format\":"); out.write(quote(FORMAT));
        out.write(",\"formatVersion\":"); out.write(Integer.toString(VERSION));
        out.write(",\"rideId\":"); out.write(quote(rideId));
        out.write(",\"app\":{\"version\":"); out.write(quote(appVersion == null || appVersion.isEmpty() ? "unknown" : appVersion));
        out.write("},\"timeZone\":{\"recorded\":");
        out.write(scooter == null || scooter.timeZone == null ? "null" : quote(scooter.timeZone));
        out.write(",\"atExport\":"); out.write(quote(TimeZone.getDefault().getID()));
        out.write("},\"scooter\":");
        if (scooter == null && summary == null) out.write("null");
        else {
            out.write("{\"summary\":"); out.write(summary == null ? "null" : withoutProfile(summary));
            if (scooter != null) scooter.write(out, anchors);
            else out.write(",\"frames\":null,\"readings\":null,\"events\":null,\"directions\":null");
            out.write('}');
        }
        boolean raw = phoneRaw != null && phoneRaw.isFile(), moving = motion != null && motion.isFile();
        out.write(",\"phone\":");
        if (!raw && !moving && track == null) out.write("null");
        else {
            out.write("{\"journal\":");
            if (raw) phoneJournal(out, phoneRaw, anchors); else out.write("null");
            out.write(",\"motion\":");
            if (moving) motion(out, motion); else out.write("null");
            out.write(",\"track\":"); out.write(track == null ? "null" : track.toJson());
            out.write('}');
        }
        out.write(",\"clock\":{\"anchors\":{\"columns\":[\"source\",\"event\",\"wall_ms\",\"monotonic_ms\"],\"rows\":[");
        for (int i = 0; i < anchors.size(); i++) {
            String[] a = anchors.get(i);
            if (i > 0) out.write(',');
            out.write('['); out.write(quote(a[0])); out.write(','); out.write(quote(a[1])); out.write(',');
            out.write(number(a[2])); out.write(','); out.write(number(a[3])); out.write(']');
        }
        out.write("]}},\"privacy\":{\"withheldKeys\":[");
        if (scooter != null) {
            int i = 0;
            for (String key : scooter.withheldKeys) { if (i++ > 0) out.write(','); out.write(quote(key)); }
        }
        out.write("],\"withheldRows\":"); out.write(Long.toString(scooter == null ? 0 : scooter.withheldRows));
        out.write(",\"withheldFields\":[\"scooter.summary.scooterId\",\"scooter.events.START.key\"]}}");
    }

    /** The analysis as Summary.toJson writes it, with the Volt scooter profile id replaced by null. */
    static String withoutProfile(RideStore.Summary summary) {
        String json = summary.toJson();
        String field = "\"scooterId\":" + quote(summary.scooterId == null ? "" : summary.scooterId);
        int at = json.indexOf(field);
        if (at < 0) throw new IllegalStateException("The ride analysis has no scooter profile field");
        return json.substring(0, at) + "\"scooterId\":null" + json.substring(at + field.length());
    }

    // ---- The scooter sensor journal --------------------------------------------------------------------------------

    /** One key's readings: each distinct reading once, with the first and last frame (by index) of its run. */
    private static final class Readings {
        final StringBuilder rows = new StringBuilder();
        String numeric, text, unit, cmd, updated;
        int first = -1, last = -1;
        boolean same(List<String> r) {
            return r.get(NUMERIC).equals(numeric) && r.get(TEXT).equals(text) && r.get(UNIT).equals(unit)
                    && r.get(CMD).equals(cmd) && r.get(UPDATED).equals(updated);
        }
        void flush() {
            if (first < 0) return;
            if (rows.length() > 0) rows.append(',');
            rows.append('[').append(number(updated)).append(',').append(number(numeric)).append(',').append(quote(text))
                    .append(',').append(quote(unit)).append(',').append(number(cmd)).append(',').append(first)
                    .append(',').append(last).append(']');
            first = -1;
        }
    }

    /** One pass over the journal; what it found is written by write(). */
    private static final class Scooter {
        final StringBuilder frames = new StringBuilder(), events = new StringBuilder(), directions = new StringBuilder();
        final Map<String, Readings> readings = new LinkedHashMap<>();
        final Set<String> withheldKeys = new TreeSet<>();
        final List<String[]> anchors = new ArrayList<>();
        long withheldRows, uncommittedRows, unreadableRows;
        int frameCount;
        String timeZone;

        static Scooter scan(File journal) throws IOException {
            Scooter s = new Scooter();
            try (Reader in = new BufferedReader(new InputStreamReader(new FileInputStream(journal), StandardCharsets.UTF_8), 65536)) {
                List<String> r;
                List<List<String>> pending = null;
                boolean header = true, started = false;
                while ((r = RideStore.csvRecord(in)) != null) {
                    if (header) { header = false; if (!r.isEmpty() && "schema_version".equals(r.get(0))) continue; }
                    if (r.size() != COLUMNS || !"1".equals(r.get(SCHEMA))) {
                        s.unreadableRows++;
                        if (pending != null) { s.uncommittedRows += pending.size(); pending = null; }
                        continue;
                    }
                    String event = r.get(EVENT);
                    if ("BEGIN".equals(event)) {
                        if (pending != null) s.uncommittedRows += pending.size();
                        pending = new ArrayList<>(); pending.add(r);
                    } else if ("SENSOR".equals(event)) {
                        if (pending != null && sameFrame(pending.get(0), r)) pending.add(r); else s.uncommittedRows++;
                    } else if ("COMMIT".equals(event)) {
                        if (pending != null && sameFrame(pending.get(0), r) && counts(pending, r)) s.commit(pending);
                        else s.uncommittedRows += (pending == null ? 0 : pending.size()) + 1;
                        pending = null;
                    } else {
                        if (pending != null) { s.uncommittedRows += pending.size(); pending = null; }
                        if ("START".equals(event) && !started) {
                            started = true;
                            String details = r.get(DETAILS);
                            if (details.startsWith(TIME_ZONE_DETAIL)) s.timeZone = details.substring(TIME_ZONE_DETAIL.length());
                        }
                        s.event(r);
                    }
                }
                if (pending != null) s.uncommittedRows += pending.size();
            }
            for (Readings k : s.readings.values()) k.flush();
            return s;
        }
        private static boolean sameFrame(List<String> begin, List<String> r) {
            return begin.get(FRAME).equals(r.get(FRAME)) && begin.get(WALL).equals(r.get(WALL)) && begin.get(MONO).equals(r.get(MONO));
        }
        /** BEGIN and COMMIT name the frame's sensor count; both must match what arrived, as RideStore's replay requires. */
        private static boolean counts(List<List<String>> pending, List<String> commit) {
            String size = Integer.toString(pending.size() - 1), begun = pending.get(0).get(NUMERIC), ended = commit.get(NUMERIC);
            return (begun.isEmpty() || begun.equals(size)) && (ended.isEmpty() || ended.equals(size));
        }
        private void commit(List<List<String>> pending) {
            List<String> begin = pending.get(0);
            int index = frameCount++;
            if (frames.length() > 0) frames.append(',');
            frames.append('[').append(number(begin.get(FRAME))).append(',').append(number(begin.get(WALL))).append(',')
                    .append(number(begin.get(MONO))).append(',').append(number(begin.get(NUMERIC))).append(']');
            for (int i = 1; i < pending.size(); i++) {
                List<String> r = pending.get(i);
                String key = r.get(KEY);
                if (DiagnosticsBundle.WITHHELD.contains(key)) { withheldKeys.add(key); withheldRows++; continue; }
                Readings k = readings.get(key);
                if (k == null) readings.put(key, k = new Readings());
                if (k.first >= 0 && k.last == index - 1 && k.same(r)) { k.last = index; continue; }
                k.flush();
                k.numeric = r.get(NUMERIC); k.text = r.get(TEXT); k.unit = r.get(UNIT); k.cmd = r.get(CMD); k.updated = r.get(UPDATED);
                k.first = index; k.last = index;
            }
        }
        private void event(List<String> r) {
            String event = r.get(EVENT);
            if (events.length() > 0) events.append(',');
            events.append('[').append(frameCount - 1).append(',').append(quote(event));
            for (int c = WALL; c < COLUMNS; c++) {
                events.append(',');
                if (c == KEY && "START".equals(event)) events.append("null");
                else if (c == WALL || c == MONO || c == FRAME || c == NUMERIC || c == CMD || c == UPDATED || c == UPDATED + 1) events.append(number(r.get(c)));
                else events.append(quote(r.get(c)));
            }
            events.append(']');
            if ("START".equals(event) || "DIRECTION".equals(event)) {
                if (directions.length() > 0) directions.append(',');
                directions.append('[').append(number(r.get(MONO))).append(',').append(quote(r.get(DIRECTION))).append(']');
            }
            if ("START".equals(event) || "GAP".equals(event) || "RECOVER".equals(event) || "STOP".equals(event))
                anchors.add(new String[]{"scooter", event, r.get(WALL), r.get(MONO)});
        }

        void write(Writer out, List<String[]> clock) throws IOException {
            clock.addAll(anchors);
            out.write(",\"frames\":{\"columns\":[\"frame\",\"wall_ms\",\"monotonic_ms\",\"sensor_rows\"],\"rows\":[");
            out.append(frames);
            out.write("]},\"readings\":{\"columns\":[\"sensor_updated_monotonic_ms\",\"numeric\",\"text\",\"unit\",\"source_command\",\"first_frame\",\"last_frame\"],\"keys\":{");
            int i = 0;
            for (Map.Entry<String, Readings> e : readings.entrySet()) {
                if (i++ > 0) out.write(',');
                out.write(quote(e.getKey())); out.write(":["); out.append(e.getValue().rows); out.write(']');
            }
            out.write("}},\"events\":{\"columns\":[\"after_frame\",\"event\",\"wall_ms\",\"monotonic_ms\",\"frame\",\"key\",\"numeric\",\"text\",\"unit\",\"source_command\",\"sensor_updated_monotonic_ms\",\"sensor_age_ms\",\"current_direction\",\"details\"],\"rows\":[");
            out.append(events);
            out.write("]},\"directions\":{\"columns\":[\"monotonic_ms\",\"current_direction\"],\"rows\":[");
            out.append(directions);
            out.write("]},\"uncommittedRows\":"); out.write(Long.toString(uncommittedRows));
            out.write(",\"unreadableRows\":"); out.write(Long.toString(unreadableRows));
        }
    }

    // ---- The phone files -------------------------------------------------------------------------------------------

    /** Calls each for every newline-ended line after the header, with its 1-based line number; returns lines skipped. */
    private interface Line { void accept(int number, String[] fields) throws IOException; }
    private static long lines(File file, boolean skipHeader, Line each) throws IOException {
        long skipped = 0; int number = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean tooLong = false; int ch;
            while ((ch = in.read()) >= 0) {
                if (ch != '\n') {
                    if (line.size() < MAX_LINE) line.write(ch); else tooLong = true;
                    continue;
                }
                number++;
                String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
                line.reset();
                boolean wasTooLong = tooLong; tooLong = false;
                if (number == 1 && skipHeader) continue;
                if (wasTooLong) { skipped++; continue; }
                if (text.isEmpty()) continue;
                each.accept(number, text.split(",", -1));
            }
        }
        return skipped;
    }

    private static void phoneJournal(Writer out, File raw, List<String[]> clock) throws IOException {
        Set<String> known = new HashSet<>();
        for (String[] e : PHONE_EVENTS) known.add(e[0]);
        Set<String> others = new TreeSet<>();
        long[] tooShort = {0};
        long skipped = lines(raw, true, (n, v) -> { if (v.length < 3) tooShort[0]++; else if (!known.contains(v[0])) others.add(v[0]); });
        List<String[]> tables = new ArrayList<>(Arrays.asList(PHONE_EVENTS));
        for (String event : others) { String[] columns = OTHER_PHONE_EVENT.clone(); columns[0] = event; tables.add(columns); }
        out.write("{\"events\":{");
        for (int t = 0; t < tables.size(); t++) {
            String[] table = tables.get(t);
            String event = table[0];
            if (t > 0) out.write(',');
            out.write(quote(event)); out.write(":{\"columns\":[\"line\"");
            for (int c = 1; c < table.length; c++) { out.write(','); out.write(quote(table[c])); }
            out.write("],\"rows\":[");
            boolean[] first = {true};
            boolean anchor = event.equals("START") || event.equals("GAP") || event.equals("STOP");
            lines(raw, true, (n, v) -> {
                if (v.length < 3 || !v[0].equals(event)) return;
                if (!first[0]) out.write(',');
                first[0] = false;
                out.write('['); out.write(Integer.toString(n));
                for (int c = 1; c < v.length; c++) { out.write(','); out.write(value(v[c])); }
                out.write(']');
                if (anchor) clock.add(new String[]{"phone", event, v[1], v[2]});
            });
            out.write("]}");
        }
        out.write("},\"skippedLines\":"); out.write(Long.toString(skipped + tooShort[0])); out.write('}');
    }

    private static void motion(Writer out, File motion) throws IOException {
        String[] header = new String[0];
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(motion), StandardCharsets.UTF_8))) {
            String first = in.readLine();
            if (first != null && first.length() < MAX_LINE) header = first.split(",", -1);
        }
        out.write("{\"columns\":[");
        for (int c = 0; c < header.length; c++) { if (c > 0) out.write(','); out.write(quote(header[c])); }
        out.write("],\"rows\":[");
        boolean[] first = {true};
        long skipped = lines(motion, true, (n, v) -> {
            if (!first[0]) out.write(',');
            first[0] = false;
            out.write('[');
            for (int c = 0; c < v.length; c++) { if (c > 0) out.write(','); out.write(value(v[c])); }
            out.write(']');
        });
        out.write("],\"skippedLines\":"); out.write(Long.toString(skipped)); out.write('}');
    }

    // ---- JSON values ---------------------------------------------------------------------------------------------

    /** A CSV field as JSON: empty is null, a field already shaped like a JSON number stays as written, the rest a string. */
    static String value(String field) {
        if (field == null || field.isEmpty()) return "null";
        return isNumber(field) ? field : quote(field);
    }
    /** A numeric column: as value(), so NaN or anything unexpected is kept as a string instead of breaking the file. */
    private static String number(String field) { return value(field); }

    /** RFC 8259's number grammar. */
    static boolean isNumber(String s) {
        int i = 0, n = s.length();
        if (i < n && s.charAt(i) == '-') i++;
        if (i >= n) return false;
        if (s.charAt(i) == '0') i++;
        else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') { while (i < n && Character.isDigit(s.charAt(i)) && s.charAt(i) < 128) i++; }
        else return false;
        if (i < n && s.charAt(i) == '.') { i++; int d = i; while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++; if (i == d) return false; }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++; if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int d = i; while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++; if (i == d) return false;
        }
        return i == n;
    }

    /** The same escaping as RideStore's analysis JSON, so the profile field is found exactly. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
