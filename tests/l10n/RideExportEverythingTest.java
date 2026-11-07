package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The Everything (JSON) ride export (RideExportEverything, docs/ride-export-everything.md): the file's shape against
 * the documented schema; a lossless round trip that rebuilds the scooter journal, the phone journal and the motion
 * file from it byte for byte; identifiers left out; the recorded time zone; the remembered choice with and without
 * Everything on the sheet; the same bytes under a throwing text source and a German default locale; and a one-hour
 * synthetic ride's size and time. Listed in tests/l10n/ride-export-everything.sources.
 */
public final class RideExportEverythingTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    static final String PROFILE = "a0000000-0000-4000-8000-00000000000a", SERIAL = "SYNTHETICSERIAL01", BATTERY = "BATTERYSERIAL0042";

    public static void main(String[] args) throws Exception {
        numbers();
        shapeAndRoundTrip();
        scooterOnlyAndOlderRide();
        choice();
        lifecycle();
        throwingTextSource();
        oneHour();
        System.out.println("RideExportEverythingTest: " + checks + " checks passed");
    }

    private static void numbers() {
        for (String yes : new String[]{"0", "-0.0", "66.0", "1.0E-5", "1500", "43.82000449", "-111.78", "2E+3"})
            check(RideExportEverything.isNumber(yes), "JSON number: " + yes);
        for (String no : new String[]{"", "-", "05", "1.", ".5", "NaN", "Infinity", "1e", "0x10", "1,5", "١"})
            check(!RideExportEverything.isNumber(no), "Not a JSON number: " + no);
        check(RideExportEverything.value("").equals("null") && RideExportEverything.value("NaN").equals("\"NaN\"")
                && RideExportEverything.value("12.5").equals("12.5"), "CSV fields as JSON values");
    }

    // ---- Fixture ----------------------------------------------------------------------------------------------------

    private static final class Fixture {
        final File dir, cache; final RideStore rides; final PhoneRideStore phone;
        Fixture() throws IOException {
            dir = Files.createTempDirectory("ride-everything-test").toFile();
            cache = new File(dir, "cache"); if (!cache.mkdirs()) throw new IOException("cache");
            rides = new RideStore(new File(dir, "rides")); phone = new PhoneRideStore(new File(dir, "phone-rides"));
        }
        void delete() { delete(dir); }
        static void delete(File f) { File[] c = f.listFiles(); if (c != null) for (File x : c) delete(x); f.delete(); }
    }

    /** A combined ride with every row kind: repeats, a key that drops out, identifiers, a gap, direction, note, rename. */
    private static String combinedRide(Fixture f, long wall, int seconds, String zone, boolean phoneFiles) throws IOException {
        f.rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        String id = zone == null ? f.rides.start(PROFILE, "Commute, \"fast\" 🛴", wall, 1000)
                : f.rides.start(PROFILE, "Commute, \"fast\" 🛴", wall, 1000, zone);
        if (phoneFiles) f.phone.start(id, wall, 1000);
        StringBuilder journal = new StringBuilder(), motion = new StringBuilder();
        for (int i = 1; i <= seconds; i++) {
            long mono = 1000 + i * 1000L;
            if (i == seconds / 3) { f.rides.markGap(wall + mono - 500, mono - 500, "bluetooth lost"); f.rides.heartbeat(wall + mono - 200, mono - 200); }
            if (i == seconds / 2) f.rides.setCurrentDirection(RideStore.CurrentDirection.NEGATIVE_IS_DRAW);
            Map<String, RideStore.Sample> s = new LinkedHashMap<>();
            long fresh = mono - 300, slow = 1000 + (i / 10) * 10000L;
            s.put("speed_kmh", new RideStore.Sample(18.0 + (i % 7) * 0.1, "speed", "km/h", 0x92, fresh));
            s.put("voltage_v", new RideStore.Sample(52.25 - i * 0.001, String.format(Locale.ROOT, "%.3f V", 52.25 - i * 0.001), "", 0x92, fresh));
            s.put("current_a", new RideStore.Sample(i % 5 == 0 ? -3.5 : 7.25, null, null, 0x92, fresh));
            s.put("battery_percent", new RideStore.Sample(80.0 - i / 60, "80 %", "", 0x91, slow));
            s.put("scooter_serial", new RideStore.Sample(null, SERIAL, "", 0x70, 1000));
            s.put("battery_serial_raw", new RideStore.Sample(null, BATTERY, "", 0x71, 1000));
            s.put("driving_mode", new RideStore.Sample(2.0, "Sport, \"fast\"", "", 0x91, 1000));
            if (i % 4 != 0) s.put("temperature_c", new RideStore.Sample(21.0, 1000));
            s.put("response_7b", new RideStore.Sample(null, null, null, -1, -1));
            f.rides.sample(wall + mono, mono, s);
            if (phoneFiles) {
                journal.append(PhoneRideStore.row("GPS", wall + mono - 40, mono - 40, 43.82 + i * 5 / 111194.9266, -111.78, 3.5f, i % 9 == 0 ? null : 5.0, .2f, 1500.0, 4.0f));
                journal.append(PhoneRideStore.row("PRESSURE", wall + mono + 5, mono - 10, 851.25f));
                journal.append(PhoneRideStore.row("TELEMETRY", wall + mono + 6, mono, 18.0, 379.0, 80.0, fresh, fresh, slow));
                journal.append(PhoneRideStore.row("MOTION", wall + mono + 7, mono + 7, 20, i % 2 == 0 ? null : 1.25));
                for (int k = 0; k < 20; k++) {
                    long t = mono - 1000 + k * 50;
                    motion.append(t).append(",0.1,0.2,9.8,").append(k == 3 ? "" : "0.01").append(",0.02,-0.03,0.0,0.1,9.8,0.0,0.0,0.7071,0.7071,3\n");
                }
                if (i == seconds / 3) journal.append(PhoneRideStore.row("GAP", wall + mono, mono));
                if (i % 10 == 0) { f.phone.appendBatch(id, journal.toString(), motion.toString()); journal.setLength(0); motion.setLength(0); }
            }
        }
        f.rides.setNotes("Note with\nnew line");
        if (phoneFiles) { f.phone.appendBatch(id, journal.toString(), motion.toString()); f.phone.stop(id, wall + seconds * 1000L + 2000, seconds * 1000L + 2000); }
        f.rides.stop(wall + seconds * 1000L + 2000, seconds * 1000L + 2000);
        f.rides.rename(id, "Renamed ride");
        return id;
    }

    private static File export(Fixture f, String id, String version) throws IOException {
        RideStore.Summary s = f.rides.get(id);
        PhoneRideStore.Track track = f.phone.rawCsv(id).isFile() ? f.phone.load(id) : null;
        return RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, version).file;
    }

    // ---- Where the file lives -----------------------------------------------------------------------------------

    /** Staging folders (ride-export-*) in the cache right now. */
    private static List<File> stagings(File cache) {
        List<File> out = new ArrayList<>();
        File[] files = cache.listFiles();
        if (files != null) for (File x : files) if (RideExportFiles.isStaging(x)) out.add(x);
        return out;
    }

    /**
     * The file holds GPS positions and notes, so it never outlives its save: it is written into a staging folder of
     * its own that the payload owns, discard removes it, a failed write leaves nothing, and removeStale catches a
     * save the process lost.
     */
    private static void lifecycle() throws IOException {
        Fixture f = new Fixture();
        try {
            String id = combinedRide(f, 1762400000000L, 30, "America/Boise", true);
            RideStore.Summary s = f.rides.get(id);
            PhoneRideStore.Track track = f.phone.load(id);
            RideExportFiles.Payload p = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            File own = p.file.getParentFile();
            check(p.text == null && p.file.isFile() && RideExportFiles.isStaging(own) && own.getParentFile().equals(f.cache)
                    && p.owned.equals(own) && Objects.requireNonNull(own.list()).length == 1, "Everything is written into a staging folder of its own");
            check(p.file.getName().equals(RideExportFiles.name(RideExportFiles.Format.EVERYTHING_JSON, id)), "Under its saved name");
            check(!new File(f.cache, "ride-" + id + "-everything.json").exists(), "No fixed per-ride file in the cache");
            byte[] bytes = p.bytes();
            p.discard();
            check(!own.exists() && stagings(f.cache).isEmpty(), "discard removes the file and its folder");
            p.discard();
            check(RideExportFiles.payload(RideExportFiles.Format.ANALYSIS_JSON, id, f.rides, s, f.phone, track, f.cache).owned == null
                    && RideExportFiles.payload(RideExportFiles.Format.SENSOR_CSV, id, f.rides, s, f.phone, track, f.cache).owned == null,
                    "Other formats own no folder (their buttons save as they always did)");

            // Two Everything saves at once each get their own folder; discarding one leaves the other.
            RideExportFiles.Payload a = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            RideExportFiles.Payload b = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            check(!a.owned.equals(b.owned), "Two saves, two folders");
            a.discard();
            check(!a.owned.exists() && b.file.isFile() && Arrays.equals(Files.readAllBytes(b.file.toPath()), bytes), "Discarding one save leaves the other's file whole");
            b.discard();

            // Several files: the copy in the shared staging folder stays; Everything's own folder goes.
            File staging = RideExportFiles.newStaging(f.cache, System.currentTimeMillis());
            RideExportFiles.Payload c = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            File staged;
            try { staged = RideExportFiles.stage(staging, RideExportFiles.Format.EVERYTHING_JSON, id, c); } finally { c.discard(); }
            check(!c.owned.exists() && Arrays.equals(Files.readAllBytes(staged.toPath()), bytes) && stagings(f.cache).equals(Collections.singletonList(staging)),
                    "Several files: the staged copy stays, Everything's own folder is gone");
            RideExportFiles.remove(staging);

            // A failed write leaves nothing behind.
            File journal = f.rides.journal(id);
            check(journal.setReadable(false), "Make the journal unreadable");
            try {
                if (!journal.canRead()) {
                    boolean threw = false;
                    try { RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0"); }
                    catch (IOException expected) { threw = true; }
                    check(threw && stagings(f.cache).isEmpty(), "A failed write removes its folder");
                }
            } finally { journal.setReadable(true); }

            // A save the process lost (killed before discard) is swept by the next export once it is a day old.
            RideExportFiles.Payload lost = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            RideExportFiles.Payload recent = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            long now = System.currentTimeMillis();
            check(lost.owned.setLastModified(now - RideExportFiles.STALE_MS - 60000), "Age the lost save");
            RideExportFiles.Payload next = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, f.rides, s, f.phone, track, f.cache, "1.0");
            check(!lost.owned.exists() && recent.file.isFile() && next.file.isFile(), "The next export sweeps a day-old lost save and keeps recent ones");
            recent.discard(); next.discard();
            check(stagings(f.cache).isEmpty(), "Nothing left in the cache");
        } finally { f.delete(); }
    }

    // ---- Shape and round trip -------------------------------------------------------------------------------------

    private static final List<String> TOP = Arrays.asList("format", "formatVersion", "rideId", "app", "timeZone", "scooter", "phone", "clock", "privacy");
    private static final List<String> SCOOTER = Arrays.asList("summary", "frames", "readings", "events", "directions", "uncommittedRows", "unreadableRows");
    private static final List<String> FRAME_COLUMNS = Arrays.asList("frame", "wall_ms", "monotonic_ms", "sensor_rows");
    private static final List<String> READING_COLUMNS = Arrays.asList("sensor_updated_monotonic_ms", "numeric", "text", "unit", "source_command", "first_frame", "last_frame");
    private static final List<String> EVENT_COLUMNS = Arrays.asList("after_frame", "event", "wall_ms", "monotonic_ms", "frame", "key", "numeric", "text", "unit",
            "source_command", "sensor_updated_monotonic_ms", "sensor_age_ms", "current_direction", "details");
    private static final List<String> PHONE_EVENTS = Arrays.asList("START", "GPS", "PRESSURE", "TELEMETRY", "MOTION", "GAP", "STOP");

    private static void shapeAndRoundTrip() throws IOException {
        Fixture f = new Fixture();
        try {
            String id = combinedRide(f, 1792026000000L, 40, "America/Boise", true);
            File file = export(f, id, "1.13.0");
            check(file.getName().equals(RideExportFiles.name(RideExportFiles.Format.EVERYTHING_JSON, id)) && RideExportFiles.isStaging(file.getParentFile())
                    && file.getParentFile().getParentFile().equals(f.cache), "Written into its own staging folder in the cache: " + file);
            check(RideExportFiles.name(RideExportFiles.Format.EVERYTHING_JSON, id).equals("ride-" + id.substring(0, 8) + "-everything.json")
                    && RideExportFiles.Format.EVERYTHING_JSON.mime.equals("application/json"), "Saved name and type");
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = obj(Json.parse(text));
            check(new ArrayList<>(root.keySet()).equals(TOP), "Top-level keys in order: " + root.keySet());
            check("volt-ride-everything".equals(root.get("format")) && num(root.get("formatVersion")) == 1 && id.equals(root.get("rideId")), "Format id, version and ride");
            check("1.13.0".equals(obj(root.get("app")).get("version")), "App version");
            Map<String, Object> zone = obj(root.get("timeZone"));
            check("America/Boise".equals(zone.get("recorded")) && TimeZone.getDefault().getID().equals(zone.get("atExport")), "Recorded and export time zones: " + zone);

            Map<String, Object> scooter = obj(root.get("scooter"));
            check(new ArrayList<>(scooter.keySet()).equals(SCOOTER), "Scooter keys: " + scooter.keySet());
            Map<String, Object> summary = obj(scooter.get("summary"));
            check(summary.containsKey("scooterId") && summary.get("scooterId") == null && id.equals(summary.get("id")) && "Renamed ride".equals(summary.get("label")),
                    "The analysis, without the profile id");
            check(Objects.equals(RideExportEverything.withoutProfile(f.rides.get(id)), f.rides.get(id).toJson().replace("\"scooterId\":\"" + PROFILE + "\"", "\"scooterId\":null")),
                    "Only the profile field changes in the analysis");
            List<Object> frames = table(scooter.get("frames"), FRAME_COLUMNS);
            check(frames.size() == 40, "One frame row per committed frame: " + frames.size());
            Map<String, Object> readings = obj(scooter.get("readings"));
            check(list(readings.get("columns")).equals(new ArrayList<Object>(READING_COLUMNS)), "Reading columns");
            Map<String, Object> keys = obj(readings.get("keys"));
            check(new ArrayList<>(keys.keySet()).equals(Arrays.asList("speed_kmh", "voltage_v", "current_a", "battery_percent", "driving_mode", "temperature_c", "response_7b")),
                    "Keys in first-seen order, identifiers left out: " + keys.keySet());
            for (Object rows : keys.values()) for (Object row : list(rows)) check(list(row).size() == READING_COLUMNS.size(), "Reading row width");
            check(list(keys.get("driving_mode")).size() == 1 && list(keys.get("battery_percent")).size() == 5, "A repeated reading is kept once per run: "
                    + list(keys.get("battery_percent")).size());
            check(list(keys.get("temperature_c")).size() == 10, "A key that drops out of a frame starts a new run");
            List<Object> events = table(scooter.get("events"), EVENT_COLUMNS);
            List<String> kinds = new ArrayList<>();
            for (Object e : events) kinds.add((String) list(e).get(1));
            check(kinds.equals(Arrays.asList("START", "GAP", "HEARTBEAT", "DIRECTION", "NOTE", "STOP", "RENAME")), "Every non-frame row, in order: " + kinds);
            check(list(events.get(0)).get(5) == null, "START's profile id left out");
            List<Object> directions = table(scooter.get("directions"), Arrays.asList("monotonic_ms", "current_direction"));
            check(directions.size() == 2 && "POSITIVE_IS_DRAW".equals(list(directions.get(0)).get(1)) && "NEGATIVE_IS_DRAW".equals(list(directions.get(1)).get(1)),
                    "The starting direction and its change: " + directions);
            check(num(scooter.get("uncommittedRows")) == 0 && num(scooter.get("unreadableRows")) == 0, "Nothing dropped");

            Map<String, Object> phone = obj(root.get("phone"));
            check(new ArrayList<>(phone.keySet()).equals(Arrays.asList("journal", "motion", "track")), "Phone keys");
            Map<String, Object> journal = obj(phone.get("journal"));
            Map<String, Object> byEvent = obj(journal.get("events"));
            check(new ArrayList<>(byEvent.keySet()).equals(PHONE_EVENTS), "Phone events: " + byEvent.keySet());
            List<Object> gps = list(obj(byEvent.get("GPS")).get("rows"));
            check(gps.size() == 40 && list(obj(byEvent.get("GPS")).get("columns")).get(1).equals("fix_time_utc_ms"), "Every GPS fix, with its fix time");
            check(num(list(gps.get(0)).get(1)) == 1792026000000L + 2000 - 40 && num(list(gps.get(0)).get(2)) == 2000 - 40, "Fix time and fix clock as stored");
            for (String event : PHONE_EVENTS) {
                Map<String, Object> t = obj(byEvent.get(event));
                for (Object row : list(t.get("rows"))) check(list(row).size() == list(t.get("columns")).size(), event + " row width");
            }
            Map<String, Object> motion = obj(phone.get("motion"));
            check(list(motion.get("rows")).size() == 800 && list(motion.get("columns")).size() == 15, "Every motion sample");
            for (Object row : list(motion.get("rows"))) check(list(row).size() == 15, "Motion row width");
            Map<String, Object> track = obj(phone.get("track"));
            check(id.equals(track.get("id")) && list(track.get("points")).size() > 0, "The phone track as Track.toJson writes it");
            check(text.contains(f.phone.load(id).toJson()), "Track JSON byte for byte");

            List<Object> anchors = table(obj(root.get("clock")).get("anchors"), Arrays.asList("source", "event", "wall_ms", "monotonic_ms"));
            List<String> anchorKinds = new ArrayList<>();
            for (Object a : anchors) anchorKinds.add(list(a).get(0) + " " + list(a).get(1));
            check(anchorKinds.equals(Arrays.asList("scooter START", "scooter GAP", "scooter STOP", "phone START", "phone GAP", "phone STOP")), "Clock pairs: " + anchorKinds);

            Map<String, Object> privacy = obj(root.get("privacy"));
            check(list(privacy.get("withheldKeys")).equals(Arrays.asList("battery_serial_raw", "scooter_serial")) && num(privacy.get("withheldRows")) == 80, "Withheld: " + privacy);
            check(!text.contains(SERIAL) && !text.contains(BATTERY) && !text.contains(PROFILE), "No serials and no profile id anywhere in the file");

            // Lossless: the three recordings rebuilt from the file, byte for byte.
            String original = new String(Files.readAllBytes(f.rides.journal(id).toPath()), StandardCharsets.UTF_8);
            check(rebuildJournal(scooter).equals(withoutIdentifiers(original)), "The scooter journal rebuilt from the file");
            check(rebuildPhone(journal).equals(new String(Files.readAllBytes(f.phone.rawCsv(id).toPath()), StandardCharsets.UTF_8)), "The phone journal rebuilt from the file");
            check(rebuildMotion(motion).equals(new String(Files.readAllBytes(f.phone.motionCsv(id).toPath()), StandardCharsets.UTF_8)), "The motion file rebuilt from the file");
        } finally { f.delete(); }
    }

    /** A scooter ride with no phone files, an older ride with no time zone, and a journal with a torn frame. */
    private static void scooterOnlyAndOlderRide() throws IOException {
        Fixture f = new Fixture();
        try {
            String id = combinedRide(f, 1792027000000L, 12, null, false);
            Map<String, Object> root = obj(Json.parse(new String(Files.readAllBytes(export(f, id, null).toPath()), StandardCharsets.UTF_8)));
            check(obj(root.get("timeZone")).get("recorded") == null, "A ride from before the time zone was recorded");
            check(root.get("phone") == null && "unknown".equals(obj(root.get("app")).get("version")), "No phone files: phone null; no version: unknown");
            check(RideStore.timeZoneDetail("Europe/Lisbon").equals("time_zone=Europe/Lisbon") && RideStore.timeZoneDetail("bad zone,1").isEmpty()
                    && RideStore.timeZoneDetail(null).isEmpty(), "Only a zone-shaped id is recorded");
            // A frame torn by a crash, then a rider-visible row: the frame is counted, not kept.
            File journal = f.rides.journal(id);
            String torn = "1,BEGIN,2026-10-15T01:00:01.000Z,1792027099000,99000,99,,2,,,,,,,\n1,SENSOR,2026-10-15T01:00:01.000Z,1792027099000,99000,99,speed_kmh,1.0,,,1,99000,0,,\n";
            Files.write(journal.toPath(), (new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8) + torn + "2,FUTURE,x\n").getBytes(StandardCharsets.UTF_8));
            File out = new File(f.cache, "torn.json");
            RideExportEverything.write(out, id, "1.13.0", journal, f.rides.get(id), null, null, null);
            Map<String, Object> scooter = obj(obj(Json.parse(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8))).get("scooter"));
            check(num(scooter.get("uncommittedRows")) == 2 && num(scooter.get("unreadableRows")) == 1, "Torn frame and unknown schema counted: " + scooter.get("uncommittedRows"));
        } finally { f.delete(); }
    }

    // ---- The remembered choice -----------------------------------------------------------------------------------

    private static void choice() {
        RideExportFiles.Format E = RideExportFiles.Format.EVERYTHING_JSON, CSV = RideExportFiles.Format.SENSOR_CSV,
                JSON = RideExportFiles.Format.ANALYSIS_JSON, GPX = RideExportFiles.Format.GPX;
        check(RideExportFiles.Format.values()[0] == E && E.section() == 0 && CSV.section() == 1 && GPX.section() == 2 && !E.phone, "Everything is first, in its own group");
        check(RideExportFiles.Format.of("everything_json") == E && RideExportFiles.Format.of(RideExportFiles.EVERYTHING_OFF) == null, "Its id; the marker is no format");
        // A sheet that lists Everything.
        check(RideExportFiles.parse(null, true).equals(EnumSet.of(E)), "Nothing remembered: Everything only");
        check(RideExportFiles.parse("sensor_csv,gpx", true).equals(EnumSet.of(E, CSV, GPX)), "A choice from before Everything gains it once, keeping its boxes");
        check(RideExportFiles.parse("", true).equals(EnumSet.of(E)), "An empty old choice gains it too");
        check(RideExportFiles.parse("sensor_csv,everything_json_off", true).equals(EnumSet.of(CSV)), "Unchecked Everything stays unchecked");
        check(RideExportFiles.parse("everything_json,gpx", true).equals(EnumSet.of(E, GPX)), "A choice with Everything is read as it is");
        check(RideExportFiles.format(EnumSet.of(GPX, E, CSV), true).equals("everything_json,sensor_csv,gpx"), "Stored in sheet order, Everything first");
        check(RideExportFiles.format(EnumSet.of(GPX, CSV), true).equals("sensor_csv,gpx,everything_json_off"), "Unchecked Everything is stored as the marker");
        check(RideExportFiles.format(EnumSet.noneOf(RideExportFiles.Format.class), true).equals("everything_json_off"), "Only the marker");
        for (Set<RideExportFiles.Format> s : Arrays.asList(EnumSet.of(E), EnumSet.of(CSV, GPX), EnumSet.of(E, JSON)))
            check(RideExportFiles.parse(RideExportFiles.format(s, true), true).equals(s), "Round trip with Everything listed: " + s);
        // A sheet that does not list it: as before.
        check(RideExportFiles.parse(null, false).equals(EnumSet.of(CSV, JSON)), "Without it: Sensor CSV and Analysis JSON");
        check(RideExportFiles.parse("", false).isEmpty() && RideExportFiles.parse("everything_json,gpx", false).equals(EnumSet.of(GPX)), "Without it, never in the choice");
        check(RideExportFiles.format(EnumSet.of(E, GPX), false).equals("gpx"), "Without it, never stored");
        check(RideExportFiles.DEFAULT.equals(RideExportFiles.defaults(RideExportFiles.EVERYTHING_ON_SHEET))
                && RideExportFiles.parse(null).equals(RideExportFiles.DEFAULT), "DEFAULT follows the sheet");
        check(RideExportFiles.listed(null).contains(E) == RideExportFiles.EVERYTHING_ON_SHEET, "Listed only once the sheet has it");
        // Older Volt reading a newer choice: the marker is an unknown id and drops out.
        check(RideExportFiles.formats(new String[]{"everything_json_off", "gpx"}).equals(Collections.singletonList(GPX)), "Unknown to older readers");
        // Availability: offered for any ride, waiting only while this ride's phone files are being written.
        check(RideExportFiles.why(E, null) == RideExportFiles.Unavailable.NONE, "A scooter ride offers it");
        check(RideExportFiles.why(E, new RideExportFiles.Phone(true, false, false, null, false)) == RideExportFiles.Unavailable.NONE, "Another ride saving does not matter");
        check(RideExportFiles.why(E, new RideExportFiles.Phone(true, true, true, null, false)) == RideExportFiles.Unavailable.SAVING, "This ride's phone files saving");
        check(RideExportFiles.why(E, new RideExportFiles.Phone(false, true, false, null, true)) == RideExportFiles.Unavailable.NONE, "An unreadable track still exports the raw rows");
    }

    // ---- Locale and catalog --------------------------------------------------------------------------------------

    private static void throwingTextSource() throws IOException {
        byte[] english = everythingBytes();
        T.install(new T.Source() {
            public String string(int id) { throw new AssertionError("the Everything export read catalog text " + T.key(id)); }
            public String plural(int id, long quantity) { throw new AssertionError("the Everything export read catalog plural " + T.key(id)); }
            public String language() { throw new AssertionError("the Everything export asked for the language"); }
        });
        byte[] guarded;
        Locale before = Locale.getDefault();
        try { Locale.setDefault(Locale.GERMANY); guarded = everythingBytes(); }
        finally { Locale.setDefault(before); T.install(null); }
        check(Arrays.equals(english, guarded), "The same file without the catalog and in a German default locale");
    }
    private static byte[] everythingBytes() throws IOException {
        Fixture f = new Fixture();
        try {
            String id = combinedRide(f, 1792028000000L, 15, "Europe/Berlin", true);
            String text = new String(Files.readAllBytes(export(f, id, "1.13.0").toPath()), StandardCharsets.UTF_8);
            return text.replace(id, "ride").getBytes(StandardCharsets.UTF_8);
        } finally { f.delete(); }
    }

    // ---- One hour -------------------------------------------------------------------------------------------------

    /** A one-hour ride at Volt's rates: 1 Hz frames of 94 keys, 1 Hz GPS, pressure and telemetry, 20 Hz motion. */
    private static void oneHour() throws IOException {
        Fixture f = new Fixture();
        try {
            long wall = 1792030000000L; int seconds = 3600;
            String id = f.rides.start(PROFILE, "Long ride", wall, 1000, "America/Boise");
            f.phone.start(id, wall, 1000);
            StringBuilder journal = new StringBuilder(), motion = new StringBuilder();
            Random random = new Random(7);
            for (int i = 1; i <= seconds; i++) {
                long mono = 1000 + i * 1000L;
                Map<String, RideStore.Sample> s = new LinkedHashMap<>();
                for (int k = 0; k < 94; k++) {
                    boolean live = k < 14;
                    long updated = live ? mono - 200 : 1000 + (i / 300) * 300000L;
                    double value = live ? Math.round(random.nextDouble() * 100000) / 1000.0 : k;
                    s.put("key_" + k, new RideStore.Sample(value, live ? null : "setting " + k, "", 0x90 + (k % 4), updated));
                }
                f.rides.sample(wall + mono, mono, s);
                journal.append(PhoneRideStore.row("GPS", wall + mono - 40, mono - 40, 43.82 + i * 1e-5, -111.78 - i * 1e-5, 3.5f, 5.25f, .2f, 1500.0 + random.nextDouble(), 4.0f));
                journal.append(PhoneRideStore.row("PRESSURE", wall + mono, mono - 10, 851.25f + random.nextFloat()));
                journal.append(PhoneRideStore.row("TELEMETRY", wall + mono, mono, 18.0, 379.0, 80.0, mono - 200, mono - 200, mono - 200));
                journal.append(PhoneRideStore.row("MOTION", wall + mono, mono, 20, 1.25));
                for (int k = 0; k < 20; k++) {
                    motion.append(mono - 1000 + k * 50);
                    for (int c = 0; c < 13; c++) motion.append(',').append((float) (random.nextGaussian()));
                    motion.append(",3\n");
                }
                if (i % 60 == 0) { f.phone.appendBatch(id, journal.toString(), motion.toString()); journal.setLength(0); motion.setLength(0); }
            }
            f.phone.stop(id, wall + seconds * 1000L + 2000, seconds * 1000L + 2000);
            f.rides.stop(wall + seconds * 1000L + 2000, seconds * 1000L + 2000);
            long raw = f.rides.journal(id).length() + f.phone.rawCsv(id).length() + f.phone.motionCsv(id).length();
            Runtime runtime = Runtime.getRuntime();
            System.gc();
            long before = runtime.totalMemory() - runtime.freeMemory(), started = System.nanoTime();
            File file = export(f, id, "1.13.0");
            long millis = (System.nanoTime() - started) / 1000000, used = runtime.totalMemory() - runtime.freeMemory() - before;
            System.out.println(String.format(Locale.ROOT, "RideExportEverythingTest: one hour: %.1f MB raw files -> %.1f MB Everything in %d ms (heap growth about %.0f MB)",
                    raw / 1e6, file.length() / 1e6, millis, Math.max(0, used) / 1e6));
            check(file.length() < raw * 0.75, "Smaller than the raw files together: " + file.length() + " vs " + raw);
            check(file.length() < 25_000_000L, "Under 25 MB for an hour: " + file.length());
            Map<String, Object> root = obj(Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
            check(list(obj(obj(root.get("scooter")).get("frames")).get("rows")).size() == seconds
                    && list(obj(obj(root.get("phone")).get("motion")).get("rows")).size() == seconds * 20, "Every frame and motion sample of the hour");
        } finally { f.delete(); }
    }

    // ---- Rebuilding the recordings from the file -----------------------------------------------------------------

    private static String withoutIdentifiers(String journal) {
        StringBuilder out = new StringBuilder();
        for (String line : journal.split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.contains(",scooter_serial,") || line.contains(",battery_serial_raw,")) continue;
            out.append(line.replace("," + PROFILE + ",", ",,")).append('\n');
        }
        return out.toString();
    }
    private static String rebuildJournal(Map<String, Object> scooter) {
        StringBuilder out = new StringBuilder("schema_version,event,recorded_utc,wall_ms,monotonic_ms,frame,key,numeric,text,unit,source_command,sensor_updated_monotonic_ms,sensor_age_ms,current_direction,details\n");
        List<Object> frames = list(obj(scooter.get("frames")).get("rows")), events = list(obj(scooter.get("events")).get("rows"));
        Map<String, Object> keys = obj(obj(scooter.get("readings")).get("keys"));
        int e = 0;
        for (; e < events.size() && num(list(events.get(e)).get(0)) < 0; e++) out.append(eventRow(list(events.get(e))));
        for (int i = 0; i < frames.size(); i++) {
            List<Object> fr = list(frames.get(i));
            String frame = raw(fr.get(0)), wall = raw(fr.get(1)), mono = raw(fr.get(2)), count = raw(fr.get(3));
            out.append(csv("1", "BEGIN", utc(Long.parseLong(wall)), wall, mono, frame, "", count, "", "", "", "", "", "", ""));
            for (Map.Entry<String, Object> k : keys.entrySet()) for (Object r : list(k.getValue())) {
                List<Object> v = list(r);
                if (num(v.get(5)) <= i && i <= num(v.get(6))) {
                    long updated = num(v.get(0)), at = Long.parseLong(mono);
                    String age = updated < 0 || at < updated ? "" : Long.toString(at - updated);
                    out.append(csv("1", "SENSOR", utc(Long.parseLong(wall)), wall, mono, frame, k.getKey(), raw(v.get(1)), (String) v.get(2), (String) v.get(3), raw(v.get(4)), raw(v.get(0)), age, "", ""));
                }
            }
            out.append(csv("1", "COMMIT", utc(Long.parseLong(wall)), wall, mono, frame, "", count, "", "", "", "", "", "", ""));
            for (; e < events.size() && num(list(events.get(e)).get(0)) == i; e++) out.append(eventRow(list(events.get(e))));
        }
        return out.toString();
    }
    private static String eventRow(List<Object> v) {
        String[] c = new String[15];
        c[0] = "1"; c[1] = (String) v.get(1); c[2] = utc(num(v.get(2)));
        for (int i = 2; i < v.size(); i++) c[i + 1] = v.get(i) instanceof String ? (String) v.get(i) : raw(v.get(i));
        return csv(c);
    }
    private static String rebuildPhone(Map<String, Object> journal) {
        TreeMap<Long, String> lines = new TreeMap<>();
        for (Map.Entry<String, Object> t : obj(journal.get("events")).entrySet())
            for (Object r : list(obj(t.getValue()).get("rows"))) {
                List<Object> v = list(r);
                StringBuilder line = new StringBuilder(t.getKey());
                for (int i = 1; i < v.size(); i++) line.append(',').append(v.get(i) instanceof String ? (String) v.get(i) : raw(v.get(i)));
                lines.put(num(v.get(0)), line.append('\n').toString());
            }
        StringBuilder out = new StringBuilder("event,wall_ms,monotonic_ms,a,b,c,d,e,f,g,h,i,j\n");
        for (String line : lines.values()) out.append(line);
        return out.toString();
    }
    private static String rebuildMotion(Map<String, Object> motion) {
        StringBuilder out = new StringBuilder();
        List<Object> columns = list(motion.get("columns"));
        for (int i = 0; i < columns.size(); i++) out.append(i > 0 ? "," : "").append(columns.get(i));
        out.append('\n');
        for (Object r : list(motion.get("rows"))) {
            List<Object> v = list(r);
            for (int i = 0; i < v.size(); i++) out.append(i > 0 ? "," : "").append(v.get(i) instanceof String ? (String) v.get(i) : raw(v.get(i)));
            out.append('\n');
        }
        return out.toString();
    }
    private static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date(millis));
    }
    private static String csv(String... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            String v = values[i] == null ? "" : values[i];
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) out.append('"').append(v.replace("\"", "\"\"")).append('"');
            else out.append(v);
        }
        return out.append('\n').toString();
    }

    // ---- A small JSON reader that keeps numbers as written -------------------------------------------------------

    static final class Num { final String raw; Num(String raw) { this.raw = raw; } public String toString() { return raw; } }
    private static String raw(Object v) { return v == null ? "" : ((Num) v).raw; }
    private static long num(Object v) { return Long.parseLong(((Num) v).raw); }
    @SuppressWarnings("unchecked") private static Map<String, Object> obj(Object v) { check(v instanceof Map, "An object: " + v); return (Map<String, Object>) v; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object v) { check(v instanceof List, "An array: " + v); return (List<Object>) v; }
    /** A {columns, rows} table with exactly these columns; every row as wide as the columns. */
    private static List<Object> table(Object v, List<String> columns) {
        Map<String, Object> t = obj(v);
        check(list(t.get("columns")).equals(new ArrayList<Object>(columns)), "Columns " + columns + ": " + t.get("columns"));
        List<Object> rows = list(t.get("rows"));
        for (Object row : rows) check(list(row).size() == columns.size(), "Row width " + columns.size() + ": " + row);
        return rows;
    }
    static final class Json {
        private final String s; private int i;
        private Json(String s) { this.s = s; }
        static Object parse(String text) {
            Json p = new Json(text); Object v = p.value(); p.space();
            if (p.i != text.length()) throw new AssertionError("Trailing text at " + p.i);
            return v;
        }
        private void space() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }
        private Object value() {
            space();
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); space();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    space(); String k = string(); space(); expect(':');
                    if (m.containsKey(k)) throw new AssertionError("Duplicate key " + k);
                    m.put(k, value()); space();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); space();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); space(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; }
            }
            if (c == '"') return string();
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String number = s.substring(start, i);
            if (!RideExportEverything.isNumber(number)) throw new AssertionError("Bad number at " + start + ": " + number);
            return new Num(number);
        }
        private void expect(char c) { if (s.charAt(i) != c) throw new AssertionError("Expected " + c + " at " + i); i++; }
        private String string() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 32) throw new AssertionError("Control character in a string at " + i);
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break; case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }
}
