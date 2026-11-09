package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Ride details' manual exports (RideExportFiles): every format's name, MIME type and bytes against what the
 * separate Export CSV, Export JSON and Export phone data buttons wrote before, for a scooter ride, a phone
 * ride and a combined ride (old phone placement included); the staged copy a several-file export saves; which
 * formats a ride offers and why not; the remembered choice; staging cleanup; and the same files under a text
 * source that throws and a comma-decimal default locale. Listed in tests/l10n/ride-export-files.sources.
 */
public final class RideExportFilesTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        names();
        sameBytesAsTheOldButtons();
        legacyPlacementKept();
        availability();
        choice();
        staging();
        throwingTextSource();
        System.out.println("RideExportFilesTest: " + checks + " checks passed");
    }

    /** What one old button handed ScreenHost: the name, the MIME type and the bytes saveText or saveFile would write. */
    private static final class Saved {
        final String name, mime; final byte[] bytes;
        Saved(String name, String mime, byte[] bytes) { this.name = name; this.mime = mime; this.bytes = bytes; }
    }
    private static Saved text(String name, String mime, String content) { return new Saved(name, mime, content.getBytes(StandardCharsets.UTF_8)); }
    private static Saved file(String name, String mime, File content) throws IOException { return new Saved(name, mime, Files.readAllBytes(content.toPath())); }

    /**
     * The removed RidesScreen code, statement for statement: Export CSV (exportCsv), Export JSON (detailSections),
     * Export phone data's five items (exportPhone) and the route notice's two raw files (rawPhoneFallback).
     */
    private static Map<String, Saved> oldButtons(Fixture f, String id, RideStore.Summary s, PhoneRideStore.Track track) throws IOException {
        Map<String, Saved> out = new LinkedHashMap<>();
        if (s != null) {
            File output = new File(f.cache, "ride-" + id + ".csv"); f.rides.exportCsv(id, output);
            out.put("csv", file("ride-" + id.substring(0, 8) + "-sensors.csv", "text/csv", output));
            out.put("json", text("ride-" + id.substring(0, 8) + "-analysis.json", "application/json", s.toJson()));
        }
        if (track != null) {
            String prefix = "ride-" + track.id.substring(0, Math.min(8, track.id.length()));
            out.put("route", text(prefix + "-route.csv", "text/csv", track.toCsv()));
            out.put("gpx", text(prefix + ".gpx", "application/gpx+xml", track.toGpx()));
            out.put("raw", file(prefix + "-phone.csv", "text/csv", f.phone.rawCsv(track.id)));
            out.put("motion", file(prefix + "-motion.csv", "text/csv", f.phone.motionCsv(track.id)));
            RideStore.Summary summary = f.rides.get(track.id);
            out.put("all", text(prefix + "-combined.json", "application/json", "{\"scooter\":" + (summary == null ? "null" : summary.toJson()) + ",\"phone\":" + track.toJson() + "}"));
            out.put("fallback-raw", file("ride-" + id.substring(0, Math.min(8, id.length())) + "-phone.csv", "text/csv", f.phone.rawCsv(id)));
            out.put("fallback-motion", file("ride-" + id.substring(0, Math.min(8, id.length())) + "-motion.csv", "text/csv", f.phone.motionCsv(id)));
        }
        return out;
    }
    private static final String[][] OLD_TO_NEW = {{"csv", "sensor_csv"}, {"json", "analysis_json"}, {"route", "route_csv"}, {"gpx", "gpx"},
            {"raw", "phone_raw"}, {"motion", "motion_raw"}, {"all", "combined_json"}, {"fallback-raw", "phone_raw"}, {"fallback-motion", "motion_raw"}};

    private static void names() {
        String id = "1a2b3c4d-0000-4000-8000-000000000001";
        String[][] expected = {{"everything_json", "ride-1a2b3c4d-everything.json", "application/json"}, {"sensor_csv", "ride-1a2b3c4d-sensors.csv", "text/csv"}, {"analysis_json", "ride-1a2b3c4d-analysis.json", "application/json"},
                {"route_csv", "ride-1a2b3c4d-route.csv", "text/csv"}, {"gpx", "ride-1a2b3c4d.gpx", "application/gpx+xml"},
                {"phone_raw", "ride-1a2b3c4d-phone.csv", "text/csv"}, {"motion_raw", "ride-1a2b3c4d-motion.csv", "text/csv"},
                {"combined_json", "ride-1a2b3c4d-combined.json", "application/json"}};
        check(RideExportFiles.Format.values().length == expected.length, "Eight formats");
        for (int i = 0; i < expected.length; i++) {
            RideExportFiles.Format f = RideExportFiles.Format.values()[i];
            check(f.id.equals(expected[i][0]) && RideExportFiles.Format.of(f.id) == f, "Format order and id " + f);
            check(RideExportFiles.name(f, id).equals(expected[i][1]), f + " keeps its old file name: " + RideExportFiles.name(f, id));
            check(f.mime.equals(expected[i][2]), f + " keeps its old MIME type");
        }
        check(RideExportFiles.name(RideExportFiles.Format.EVERYTHING_JSON, id).equals(RideAutoExport.everythingName(id)), "Automatic copies keep the same name");
        check(RideExportFiles.Format.of("CSV") == null && RideExportFiles.Format.of(null) == null, "Unknown ids read as nothing");
        check(RideExportFiles.CHOICE.equals("ride_export_choice") && RideExportFiles.FOLDER_HINT.equals("ride_export_folder_hint"), "Stored keys");
        BackupRegistry.Rule choice = BackupRegistry.rule("app_settings", RideExportFiles.CHOICE), hint = BackupRegistry.rule("app_settings", RideExportFiles.FOLDER_HINT);
        check(choice != null && BackupRegistry.PREFERENCES.equals(choice.category) && choice.type == String.class, "The remembered choice travels with App preferences");
        check(hint != null && hint.excluded() && hint.type == String.class, "The folder hint stays on this phone");
    }

    /** New payloads, and their staged copies, against the old buttons for each kind of ride. */
    private static void sameBytesAsTheOldButtons() throws IOException {
        Fixture f = new Fixture();
        try {
            String[] rides = {f.scooter, f.phoneOnly, f.combined}, kinds = {"scooter ride", "phone ride", "combined ride"};
            int[] counts = {2, 7, 9};
            for (int r = 0; r < rides.length; r++) {
                String id = rides[r];
                RideStore.Summary s = f.rides.get(id);
                PhoneRideStore.Track track = f.phone.rawCsv(id).isFile() ? f.phone.load(id) : null;
                check((s == null) == (r == 1) && (track == null) == (r == 0), kinds[r] + " fixture");
                Map<String, Saved> old = oldButtons(f, id, s, track);
                check(old.size() == counts[r], kinds[r] + ": every old button: " + old.keySet());
                File staging = RideExportFiles.newStaging(f.cache, 1700000000000L + r);
                for (String[] pair : OLD_TO_NEW) {
                    Saved before = old.get(pair[0]);
                    if (before == null) continue;
                    RideExportFiles.Format format = RideExportFiles.Format.of(pair[1]);
                    RideExportFiles.Payload payload = RideExportFiles.payload(format, id, f.rides, s, f.phone, track, f.cache);
                    String what = kinds[r] + ", " + pair[0];
                    check(RideExportFiles.name(format, id).equals(before.name) && format.mime.equals(before.mime), what + ": same name and type");
                    check(Arrays.equals(payload.bytes(), before.bytes), what + ": same bytes");
                    check((payload.text != null) == (pair[0].equals("json") || pair[0].equals("route") || pair[0].equals("gpx") || pair[0].equals("all")),
                            what + ": text stays saveText, a file stays saveFile");
                    File staged = RideExportFiles.stage(staging, format, id, payload);
                    check(staged.getName().equals(before.name) && Arrays.equals(Files.readAllBytes(staged.toPath()), before.bytes), what + ": the several-file copy is the same file");
                }
                check(Objects.requireNonNull(staging.list()).length == (r == 0 ? 2 : r == 1 ? 5 : 7), kinds[r] + ": one staged file per format");
                RideExportFiles.remove(staging);
                check(!staging.exists(), "Staging removed");
            }
            RideStore.Summary combined = f.rides.get(f.combined);
            PhoneRideStore.Track track = f.phone.load(f.combined);
            String all = RideExportFiles.payload(RideExportFiles.Format.COMBINED_JSON, f.combined, f.rides, combined, f.phone, track, f.cache).text;
            check(all.startsWith("{\"scooter\":{") && all.contains("Commute 🛴") && all.contains("\"placement\":null"), "Combined JSON holds both parts, the ride's name and placement null");
            String phoneAll = RideExportFiles.payload(RideExportFiles.Format.COMBINED_JSON, f.phoneOnly, f.rides, null, f.phone, f.phone.load(f.phoneOnly), f.cache).text;
            check(phoneAll.startsWith("{\"scooter\":null,\"phone\":{"), "A phone ride's combined JSON keeps scooter null");
            boolean missing = false;
            try { RideExportFiles.payload(RideExportFiles.Format.PHONE_RAW, f.scooter, f.rides, null, f.phone, null, f.cache); }
            catch (RideExportFiles.Missing expected) { missing = true; }
            check(missing, "A raw file that was never written is Missing (the no-phone-data toast)");
            boolean refused = false;
            try { RideExportFiles.payload(RideExportFiles.Format.ROUTE_CSV, f.combined, f.rides, combined, f.phone, null, f.cache); }
            catch (IOException expected) { refused = !(expected instanceof RideExportFiles.Missing); }
            check(refused, "No loaded recording, no route file");
        } finally { f.delete(); }
    }

    /** A journal from an older Volt keeps its placement in every JSON, as Export phone data wrote it after #245. */
    private static void legacyPlacementKept() throws IOException {
        Fixture f = new Fixture();
        try {
            String id = f.combined;
            String raw = new String(Files.readAllBytes(f.phone.rawCsv(id).toPath()), StandardCharsets.UTF_8);
            String header = raw.substring(0, raw.indexOf('\n') + 1), start = raw.substring(header.length(), raw.indexOf('\n', header.length()) + 1);
            check(start.startsWith("START,") && start.endsWith(",\n"), "A new START row has an empty placement column: " + start);
            Files.write(f.phone.rawCsv(id).toPath(), raw.replace(header + start, header + start.substring(0, start.length() - 1) + "mounted\n").getBytes(StandardCharsets.UTF_8));
            PhoneRideStore.Track track = f.phone.load(id); RideStore.Summary s = f.rides.get(id);
            check("mounted".equals(track.placement), "Old placement read");
            Map<String, Saved> old = oldButtons(f, id, s, track);
            RideExportFiles.Payload all = RideExportFiles.payload(RideExportFiles.Format.COMBINED_JSON, id, f.rides, s, f.phone, track, f.cache);
            check(Arrays.equals(all.bytes(), old.get("all").bytes) && all.text.contains("\"placement\":\"mounted\""), "Combined JSON keeps placement mounted, byte for byte");
            RideExportFiles.Payload journal = RideExportFiles.payload(RideExportFiles.Format.PHONE_RAW, id, f.rides, s, f.phone, track, f.cache);
            check(Arrays.equals(journal.bytes(), old.get("raw").bytes), "Phone raw CSV is the old journal as it is");
        } finally { f.delete(); }
    }

    private static RideExportFiles.Phone phone(boolean saving, boolean journal, boolean motion, PhoneRideStore.Track track, boolean unreadable) {
        return new RideExportFiles.Phone(saving, journal, motion, track, unreadable);
    }
    private static void availability() throws IOException {
        Fixture f = new Fixture();
        try {
            RideExportFiles.Format[] all = RideExportFiles.Format.values();
            for (RideExportFiles.Format format : all) {
                check(RideExportFiles.why(format, null) == (format.phone ? RideExportFiles.Unavailable.NO_PHONE : RideExportFiles.Unavailable.NONE), "No phone files: " + format);
                check(RideExportFiles.why(format, phone(true, false, false, null, false)) == (format.phone ? RideExportFiles.Unavailable.NO_PHONE : RideExportFiles.Unavailable.NONE), "Another ride saving, none here: " + format);
            }
            List<RideExportFiles.Format> scooterFiles = new ArrayList<>(Arrays.asList(RideExportFiles.Format.SENSOR_CSV, RideExportFiles.Format.ANALYSIS_JSON));
            if (RideExportFiles.EVERYTHING_ON_SHEET) scooterFiles.add(0, RideExportFiles.Format.EVERYTHING_JSON);
            int everything = RideExportFiles.EVERYTHING_ON_SHEET ? 1 : 0;
            check(RideExportFiles.listed(null).equals(scooterFiles), "A scooter ride lists its two files (and Everything once the sheet has it)");
            PhoneRideStore.Track gps = f.phone.load(f.combined), motionOnly = f.phone.load(f.motionOnly);
            RideExportFiles.Phone full = phone(false, true, true, gps, false);
            check(RideExportFiles.listed(full).size() == 7 + everything, "A phone recording lists all seven (and Everything)");
            for (RideExportFiles.Format format : all) check(RideExportFiles.why(format, full) == RideExportFiles.Unavailable.NONE, "Everything offered: " + format);
            RideExportFiles.Phone saving = phone(true, true, true, null, false);
            for (RideExportFiles.Format format : all) check(RideExportFiles.why(format, saving) == (format.phone || format == RideExportFiles.Format.EVERYTHING_JSON ? RideExportFiles.Unavailable.SAVING : RideExportFiles.Unavailable.NONE), "Saving: " + format);
            RideExportFiles.Phone noFixes = phone(false, true, true, motionOnly, false);
            check(motionOnly.locationCount == 0 && motionOnly.motionSampleCount > 0, "Motion-only fixture");
            check(RideExportFiles.why(RideExportFiles.Format.ROUTE_CSV, noFixes) == RideExportFiles.Unavailable.NO_FIXES
                    && RideExportFiles.why(RideExportFiles.Format.GPX, noFixes) == RideExportFiles.Unavailable.NO_FIXES
                    && RideExportFiles.why(RideExportFiles.Format.COMBINED_JSON, noFixes) == RideExportFiles.Unavailable.NONE
                    && RideExportFiles.why(RideExportFiles.Format.PHONE_RAW, noFixes) == RideExportFiles.Unavailable.NONE, "No fixes: no route or GPX, the rest offered");
            RideExportFiles.Phone unreadable = phone(false, true, false, null, true);
            check(RideExportFiles.why(RideExportFiles.Format.ROUTE_CSV, unreadable) == RideExportFiles.Unavailable.UNREADABLE
                    && RideExportFiles.why(RideExportFiles.Format.COMBINED_JSON, unreadable) == RideExportFiles.Unavailable.UNREADABLE
                    && RideExportFiles.why(RideExportFiles.Format.PHONE_RAW, unreadable) == RideExportFiles.Unavailable.NONE
                    && RideExportFiles.why(RideExportFiles.Format.MOTION_RAW, unreadable) == RideExportFiles.Unavailable.NOT_RECORDED, "Unreadable journal: the raw files that exist, as Phone data files offered");
            String nothing = "c0c0c0c0-0000-4000-8000-00000000000c";
            f.phone.start(nothing, 1700000400000L, 1000); f.phone.stop(nothing, 1700000401000L, 2000);
            PhoneRideStore.Track empty = f.phone.load(nothing);
            check(empty.locationCount == 0 && empty.motionSampleCount == 0, "Empty recording fixture");
            check(RideExportFiles.listed(phone(false, true, true, empty, false)).size() == 2 + everything, "A recording with nothing in it is hidden, as Export phone data was");
        } finally { f.delete(); }
    }

    private static void choice() throws IOException {
        Fixture f = new Fixture();
        try {
            Set<RideExportFiles.Format> first = RideExportFiles.parse(null, false);
            check(RideExportFiles.parse(null).equals(RideExportFiles.DEFAULT), "Nothing remembered: the default");
            check(first.size() == 2 && first.contains(RideExportFiles.Format.SENSOR_CSV) && first.contains(RideExportFiles.Format.ANALYSIS_JSON), "Nothing remembered, without Everything: Sensor CSV and Analysis JSON");
            check(RideExportFiles.parse("", false).isEmpty(), "An empty choice stays empty without Everything");
            check(RideExportFiles.parse("gpx,bogus,,route_csv,gpx", false).equals(EnumSet.of(RideExportFiles.Format.GPX, RideExportFiles.Format.ROUTE_CSV)), "Unknown and repeated ids dropped");
            check(RideExportFiles.format(EnumSet.of(RideExportFiles.Format.COMBINED_JSON, RideExportFiles.Format.SENSOR_CSV), false).equals("sensor_csv,combined_json"), "Stored in sheet order");
            Set<RideExportFiles.Format> phoneChoice = EnumSet.of(RideExportFiles.Format.ROUTE_CSV, RideExportFiles.Format.GPX, RideExportFiles.Format.SENSOR_CSV);
            check(RideExportFiles.initial(phoneChoice, null).equals(EnumSet.of(RideExportFiles.Format.SENSOR_CSV)), "A scooter ride checks only what it offers");
            RideExportFiles.Phone noFixes = phone(false, true, true, f.phone.load(f.motionOnly), false);
            check(RideExportFiles.initial(phoneChoice, noFixes).equals(EnumSet.of(RideExportFiles.Format.SENSOR_CSV)), "Disabled formats start unchecked");
            Set<RideExportFiles.Format> kept = RideExportFiles.remember(phoneChoice, null, EnumSet.of(RideExportFiles.Format.ANALYSIS_JSON));
            check(kept.equals(EnumSet.of(RideExportFiles.Format.ANALYSIS_JSON, RideExportFiles.Format.ROUTE_CSV, RideExportFiles.Format.GPX)), "A scooter ride's export keeps the phone formats remembered: " + kept);
            RideExportFiles.Phone full = phone(false, true, true, f.phone.load(f.combined), false);
            check(RideExportFiles.remember(phoneChoice, full, EnumSet.of(RideExportFiles.Format.MOTION_RAW)).equals(EnumSet.of(RideExportFiles.Format.MOTION_RAW)), "Every offered box is remembered as it was");
            check(RideExportFiles.parse(RideExportFiles.format(kept)).equals(kept), "Round trip");
            check(RideExportFiles.formats(new String[]{"gpx", "nope", "gpx", "sensor_csv"}).equals(Arrays.asList(RideExportFiles.Format.GPX, RideExportFiles.Format.SENSOR_CSV))
                    && RideExportFiles.formats(null).isEmpty(), "Intent ids read back in order, once each");
            check(Arrays.equals(RideExportFiles.ids(Arrays.asList(RideExportFiles.Format.GPX, RideExportFiles.Format.PHONE_RAW)), new String[]{"gpx", "phone_raw"}), "Intent ids written");
        } finally { f.delete(); }
    }

    private static void staging() throws IOException {
        File cache = Files.createTempDirectory("ride-export-cache").toFile();
        try {
            long now = 1700000000000L;
            File other = new File(cache, "ride-1a2b3c4d-0000.csv"); Files.write(other.toPath(), new byte[]{1});
            File backup = new File(cache, "backup-export"); check(backup.mkdirs(), "Unrelated folder");
            File a = RideExportFiles.newStaging(cache, now), b = RideExportFiles.newStaging(cache, now);
            check(a.isDirectory() && b.isDirectory() && !a.equals(b) && RideExportFiles.isStaging(a) && RideExportFiles.isStaging(b), "Two exports in the same millisecond get their own folders");
            Files.write(new File(a, "x.csv").toPath(), new byte[]{2});
            check(a.setLastModified(now - RideExportFiles.STALE_MS - 1000), "Age the first folder");
            check(other.setLastModified(now - RideExportFiles.STALE_MS - 1000) && backup.setLastModified(now - RideExportFiles.STALE_MS - 1000), "Age unrelated files too");
            File c = RideExportFiles.newStaging(cache, now + 5);
            check(!a.exists() && b.isDirectory() && c.isDirectory(), "A lost pick's old folder is removed; recent ones stay");
            check(other.isFile() && backup.isDirectory(), "Nothing else in the cache is touched");
            check(!RideExportFiles.isStaging(backup) && !RideExportFiles.isStaging(other), "Only staging folders count");
            RideExportFiles.remove(backup);
            check(backup.isDirectory(), "remove refuses anything that is not a staging folder");
        } finally { Fixture.delete(cache); }
    }

    /** Every payload with a text source that throws on any use and a German default locale: the same bytes. */
    private static void throwingTextSource() throws IOException {
        Map<String, String> english = everything();
        T.install(new T.Source() {
            public String string(int id) { throw new AssertionError("a ride export read catalog text " + T.key(id)); }
            public String plural(int id, long quantity) { throw new AssertionError("a ride export read catalog plural " + T.key(id)); }
            public String language() { throw new AssertionError("a ride export asked for the language"); }
        });
        Map<String, String> guarded;
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            guarded = everything();
        } finally {
            Locale.setDefault(before);
            T.install(null);
        }
        check(english.size() == 14 + (RideExportFiles.EVERYTHING_ON_SHEET ? 2 : 0), "Two scooter-ride files, five phone-ride files and seven combined-ride files: " + english.keySet());
        check(guarded.equals(english), "The same files without the catalog and in a German default locale");
    }
    private static Map<String, String> everything() throws IOException {
        Fixture f = new Fixture();
        try {
            Map<String, String> out = new TreeMap<>();
            String[] ids = {f.scooter, f.phoneOnly, f.combined}, roles = {"scooter", "phone", "combined"};
            for (int r = 0; r < ids.length; r++) {
                String id = ids[r]; RideStore.Summary s = f.rides.get(id);
                PhoneRideStore.Track track = f.phone.rawCsv(id).isFile() ? f.phone.load(id) : null;
                RideExportFiles.Phone phone = track == null ? null : phone(false, true, true, track, false);
                for (RideExportFiles.Format format : RideExportFiles.listed(phone)) {
                    // A phone recording without a scooter journal never opens ride details; its files are still checked.
                    if (s == null && !format.phone) continue;
                    String text = new String(RideExportFiles.payload(format, id, f.rides, s, f.phone, track, f.cache).bytes(), StandardCharsets.UTF_8);
                    out.put(roles[r] + "/" + format.id, text.replace(id, roles[r]));
                }
            }
            return out;
        } finally { f.delete(); }
    }

    /** A scooter-only ride, a phone-only recording (no scooter journal), a combined ride and a motion-only one. */
    private static final class Fixture {
        final File dir, cache; final RideStore rides; final PhoneRideStore phone; final String scooter, phoneOnly, combined, motionOnly;
        Fixture() throws IOException {
            dir = Files.createTempDirectory("ride-export-files-test").toFile();
            cache = new File(dir, "cache"); if (!cache.mkdirs()) throw new IOException("cache");
            rides = new RideStore(new File(dir, "rides")); phone = new PhoneRideStore(new File(dir, "phone-rides"));
            scooter = ride("", 1700000200000L, true, false, true);
            phoneOnly = "b0b0b0b0-0000-4000-8000-00000000000b";
            phoneFiles(phoneOnly, 1700000300000L, true);
            combined = ride("Commute 🛴", 1700000000000L, true, true, true);
            motionOnly = ride("Pocket, GPS off", 1700000100000L, true, true, false);
        }
        private String ride(String label, long wall, boolean scooterJournal, boolean phoneFiles, boolean fixes) throws IOException {
            String id = rides.start("a0000000-0000-4000-8000-00000000000a", label, wall, 1000);
            for (int i = 0; i <= 20; i++) {
                Map<String, RideStore.Sample> s = new HashMap<>();
                s.put("voltage_v", new RideStore.Sample(40.0 - i * 0.01, 1000 + i * 1000));
                s.put("current_a", new RideStore.Sample(2.5, 1000 + i * 1000));
                s.put("speed_kmh", new RideStore.Sample(18.0, 1000 + i * 1000));
                s.put("battery_percent", new RideStore.Sample(80.0, 1000 + i * 1000));
                s.put("odometer_km", new RideStore.Sample(100 + i * 0.005, 1000 + i * 1000));
                rides.sample(wall + i * 1000, 1000 + i * 1000, s);
            }
            if (phoneFiles) phoneFiles(id, wall, fixes);
            rides.stop(wall + 22000, 22000);
            return id;
        }
        private void phoneFiles(String id, long wall, boolean fixes) throws IOException {
            phone.start(id, wall, 1000);
            StringBuilder journal = new StringBuilder();
            if (fixes) for (int i = 0; i <= 20; i++) {
                long mono = 1000 + i * 1000;
                journal.append(PhoneRideStore.row("GPS", wall + mono, mono, 43.82 + i * 5 / 111194.9266, -111.78, 3, 5, .2, 1500.0, 4.0));
            }
            journal.append(PhoneRideStore.row("MOTION", wall + 5000, 5000, 120, 1.25));
            phone.appendBatch(id, journal.toString(), "5000,0.1,0.2,9.8,0.0,0.1,0.0,0.0,0.1,9.8,0.0,0.0,0.0,1.0,3\n");
            phone.stop(id, wall + 22000, 22000);
        }
        void delete() { delete(dir); }
        static void delete(File f) { File[] children = f.listFiles(); if (children != null) for (File c : children) delete(c); f.delete(); }
    }
}
