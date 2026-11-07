package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Automatic ride copies (RideAutoExport): file names, no duplicates, the persisted waiting list, the two
 * types and when a ride leaves the list, the failure ids and the settings row's state, and the bytes of
 * each file against the manual Rides exports. The folder is an in-memory fake that names clashes the way
 * Android's local storage provider does ("name (1).csv"), so a duplicate would show up here.
 *
 * The export path is run once with the built-in English and once with a text source that throws on any
 * use (T.install, as ReportsStayEnglishTest does): every file must come out the same, so the files never
 * read the catalog. Listed in tests/l10n/ride-auto-export.sources; tests/run.sh runs it.
 */
public final class RideAutoExportTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    /** Fake SAF folder. Like FileSystemProvider, create and rename never overwrite: they pick "name (n).ext". */
    static class FakeFolder implements RideAutoExport.Folder {
        final Map<String, byte[]> files = new TreeMap<>();
        final List<String> log = new ArrayList<>();
        boolean gone, full, renameFails, readFails;
        /** create fails for names containing this, when set. */
        String refuse;
        private void reachable() throws IOException { if (gone) throw new RideAutoExport.Unavailable("gone"); }
        private String unique(String name) {
            if (!files.containsKey(name)) return name;
            int dot = name.lastIndexOf('.'); String base = dot <= 0 ? name : name.substring(0, dot), ext = dot <= 0 ? "" : name.substring(dot);
            for (int n = 1; ; n++) if (!files.containsKey(base + " (" + n + ")" + ext)) return base + " (" + n + ")" + ext;
        }
        @Override public byte[] read(String name) throws IOException { reachable(); log.add("read " + name); if (readFails && files.containsKey(name)) throw new IOException("read"); return files.get(name); }
        @Override public void delete(String name) throws IOException { reachable(); if (files.remove(name) != null) log.add("delete " + name); }
        @Override public String create(String name, byte[] data) throws IOException {
            reachable(); if (full || (refuse != null && name.contains(refuse))) throw new IOException("ENOSPC");
            String given = unique(name); files.put(given, data.clone()); log.add("create " + given); return given;
        }
        @Override public void rename(String from, String to) throws IOException {
            reachable(); if (renameFails) throw new IOException("rename");
            byte[] data = files.remove(from); if (data == null) throw new IOException("missing " + from);
            String given = unique(to); files.put(given, data); log.add("rename " + from + " -> " + given);
            if (!given.equals(to)) throw new IOException("renamed to " + given);
        }
        boolean noDuplicatesOrTemporaries() {
            for (String name : files.keySet()) if (name.contains(" (") || name.endsWith(".partial")) return false;
            return true;
        }
        String text(String name) { byte[] b = files.get(name); return b == null ? null : new String(b, StandardCharsets.UTF_8); }
    }

    private static final String A = "1a2b3c4d-0000-4000-8000-000000000001", B = "5e6f7a8b-0000-4000-8000-000000000002",
            C = "9c0d1e2f-0000-4000-8000-000000000003", NO_GPS = "aaaabbbb-0000-4000-8000-000000000004";

    public static void main(String[] args) throws Exception {
        names(); waitingList(); folderLabels(); problemsAndRow();
        savesThroughTemporary(); sameFileTwice(); changedFileReplaced(); interruptedPassCleanedUp();
        whichRidesExport(); bothTypes(); partialFailureKeepsRideWaiting(); failures(); retryAfterFailure();
        storeBytesMatchManualExports();
        throwingTextSource();
        System.out.println("RideAutoExportTest: " + checks + " checks passed");
    }

    private static void names() {
        check(RideAutoExport.routeCsvName(A).equals("ride-1a2b3c4d-route.csv"), "Same name as Rides, Export phone data, Route CSV");
        check(RideAutoExport.combinedJsonName(A).equals("ride-1a2b3c4d-combined.json"), "Same name as All (JSON)");
        check(RideAutoExport.analysisJsonName(A).equals("ride-1a2b3c4d-analysis.json"), "Same name as ride details, Export JSON");
        check(RideAutoExport.routeCsvName("abc").equals("ride-abc-route.csv"), "Short ids keep the manual export's rule");
        for (String name : new String[]{RideAutoExport.routeCsvName(A), RideAutoExport.combinedJsonName(A), RideAutoExport.analysisJsonName(A)}) {
            String temporary = RideAutoExport.temporaryName(name);
            check(temporary.startsWith(".") && temporary.endsWith(".partial") && !temporary.equals(name), "Temporary name is hidden and never looks like a finished file: " + temporary);
        }
        check(RideAutoExport.ROUTE_CSV.equals("ride_auto_export_route_csv") && RideAutoExport.JSON.equals("ride_auto_export_json")
                && RideAutoExport.FOLDER.equals("ride_auto_export_folder") && RideAutoExport.PENDING.equals("ride_auto_export_pending")
                && RideAutoExport.PROBLEM.equals("ride_auto_export_problem"), "Stored keys are the ones BackupRegistry classifies");
        for (String key : new String[]{RideAutoExport.ROUTE_CSV, RideAutoExport.JSON}) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", key);
            check(rule != null && BackupRegistry.PREFERENCES.equals(rule.category) && rule.type == Boolean.class, key + " travels with App preferences as a boolean");
        }
        for (String key : new String[]{RideAutoExport.FOLDER, RideAutoExport.PENDING, RideAutoExport.PROBLEM}) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", key);
            check(rule != null && rule.excluded() && rule.type == String.class, key + " stays on this phone");
        }
    }

    private static void waitingList() {
        String s = RideAutoExport.add("", A); s = RideAutoExport.add(s, B); s = RideAutoExport.add(s, A);
        check(s.equals(A + "," + B), "Each ride waits once, oldest first: " + s);
        check(RideAutoExport.add(s, "../evil").equals(s) && RideAutoExport.add(s, null).equals(s) && RideAutoExport.add(s, "a,b").equals(s), "Only ride ids are stored");
        check(RideAutoExport.parse(null).isEmpty() && RideAutoExport.parse("").isEmpty() && RideAutoExport.parse(",,").isEmpty(), "Empty or absent list");
        check(RideAutoExport.parse(A + ",bad/id," + B + "," + A).equals(Arrays.asList(A, B)), "Damaged entries and repeats are dropped when read");
        String during = RideAutoExport.add(s, C);
        check(RideAutoExport.remove(during, Arrays.asList(A, B)).equals(C), "Rides added during a pass stay waiting");
        check(RideAutoExport.remove(during, Collections.singletonList(B)).equals(A + "," + C), "Unfinished rides keep their order");
        String big = "";
        for (int i = 0; i < RideAutoExport.MAX_PENDING + 3; i++) big = RideAutoExport.add(big, "r" + i);
        List<String> kept = RideAutoExport.parse(big);
        check(kept.size() == RideAutoExport.MAX_PENDING && kept.get(0).equals("r3") && kept.get(kept.size() - 1).equals("r" + (RideAutoExport.MAX_PENDING + 2)), "The bound keeps the newest rides");
    }

    private static void folderLabels() {
        check("Download/VoltRides".equals(RideAutoExport.folderLabel("primary:Download/VoltRides")), "Local storage path");
        check("Rides".equals(RideAutoExport.folderLabel("1A2B-3C4D:Rides/")), "SD card path without trailing slash");
        check(RideAutoExport.folderLabel("primary:") == null && RideAutoExport.folderLabel("opaque-id-123") == null && RideAutoExport.folderLabel(null) == null, "Opaque or root ids give no label");
    }

    private static void problemsAndRow() {
        check(RideAutoExport.Problem.FOLDER.id.equals("folder") && RideAutoExport.Problem.SAVE.id.equals("save") && RideAutoExport.Problem.NONE.id.isEmpty(), "Stored failure ids");
        for (RideAutoExport.Problem p : RideAutoExport.Problem.values()) check(RideAutoExport.Problem.of(p.id) == p, "Problem id round trip " + p);
        check(RideAutoExport.Problem.of(null) == RideAutoExport.Problem.NONE && RideAutoExport.Problem.of("Folder") == RideAutoExport.Problem.NONE, "Unknown stored id is no problem");
        RideAutoExport.Problem none = RideAutoExport.Problem.NONE, folder = RideAutoExport.Problem.FOLDER, save = RideAutoExport.Problem.SAVE;
        check(RideAutoExport.rowState(false, true, true, folder) == RideAutoExport.RowState.OFF, "Off shows the off line, whatever is stored");
        check(RideAutoExport.rowState(true, false, false, none) == RideAutoExport.RowState.CHOOSE_FOLDER, "Restored setting without a folder asks for one");
        check(RideAutoExport.rowState(true, false, false, folder) == RideAutoExport.RowState.CHOOSE_FOLDER, "Restored setting stays 'choose a folder' after a waiting ride");
        check(RideAutoExport.rowState(true, true, false, none) == RideAutoExport.RowState.FOLDER_PROBLEM, "A revoked grant is a folder problem before any ride fails");
        check(RideAutoExport.rowState(true, true, true, folder) == RideAutoExport.RowState.FOLDER_PROBLEM, "Folder deleted");
        check(RideAutoExport.rowState(true, true, true, save) == RideAutoExport.RowState.SAVE_PROBLEM, "Write failed");
        check(RideAutoExport.rowState(true, true, true, none) == RideAutoExport.RowState.SAVING, "Working");
        check(RideAutoExport.anyOn(true, false) && RideAutoExport.anyOn(false, true) && !RideAutoExport.anyOn(false, false), "Either type keeps it on");
    }

    private static void savesThroughTemporary() throws IOException {
        FakeFolder folder = new FakeFolder(); String name = RideAutoExport.routeCsvName(A), temporary = RideAutoExport.temporaryName(name);
        check(RideAutoExport.save(folder, name, "wall_ms\n1\n") == RideAutoExport.Outcome.SAVED, "New file saved");
        check(Arrays.equals(folder.files.get(name), "wall_ms\n1\n".getBytes(StandardCharsets.UTF_8)) && folder.files.size() == 1, "Exactly the text, under its final name only");
        check(folder.log.contains("create " + temporary) && folder.log.contains("rename " + temporary + " -> " + name), "Written under the temporary name, then renamed: " + folder.log);
        check(!folder.log.contains("create " + name), "The final name never holds a file being written");
    }

    private static void sameFileTwice() throws IOException {
        FakeFolder folder = new FakeFolder(); String name = RideAutoExport.combinedJsonName(A);
        RideAutoExport.save(folder, name, "{}"); folder.log.clear();
        check(RideAutoExport.save(folder, name, "{}") == RideAutoExport.Outcome.UNCHANGED, "Unchanged bytes are left alone");
        check(folder.files.size() == 1 && folder.noDuplicatesOrTemporaries(), "No second copy");
        for (String entry : folder.log) check(entry.startsWith("read "), "Leaving it alone writes nothing: " + entry);
    }

    private static void changedFileReplaced() throws IOException {
        FakeFolder folder = new FakeFolder(); String name = RideAutoExport.routeCsvName(A);
        RideAutoExport.save(folder, name, "old\n");
        check(RideAutoExport.save(folder, name, "new\n") == RideAutoExport.Outcome.SAVED, "Changed content replaces the file");
        check(folder.files.size() == 1 && "new\n".equals(folder.text(name)) && folder.noDuplicatesOrTemporaries(), "Replaced in place, never name (1).csv: " + folder.files.keySet());
        FakeFolder manual = new FakeFolder(); manual.files.put(RideAutoExport.routeCsvName(B), "same\n".getBytes(StandardCharsets.UTF_8));
        check(RideAutoExport.save(manual, RideAutoExport.routeCsvName(B), "same\n") == RideAutoExport.Outcome.UNCHANGED && manual.files.size() == 1, "A manual export of the same ride is not duplicated");
    }

    private static void interruptedPassCleanedUp() throws IOException {
        String name = RideAutoExport.routeCsvName(A), temporary = RideAutoExport.temporaryName(name);
        FakeFolder folder = new FakeFolder(); folder.files.put(temporary, "half".getBytes(StandardCharsets.UTF_8));
        RideAutoExport.save(folder, name, "whole\n");
        check(folder.files.size() == 1 && folder.noDuplicatesOrTemporaries() && "whole\n".equals(folder.text(name)), "A temporary file left by an interrupted pass is replaced, not suffixed: " + folder.files.keySet());
        FakeFolder unchanged = new FakeFolder();
        unchanged.files.put(name, "whole\n".getBytes(StandardCharsets.UTF_8)); unchanged.files.put(temporary, "half".getBytes(StandardCharsets.UTF_8));
        RideAutoExport.save(unchanged, name, "whole\n");
        check(unchanged.files.size() == 1, "A stale temporary file is removed even when the file is already there");
    }

    /** A fake source: csv by id, json by id (name and text). */
    private static RideAutoExport.Source source(Map<String, String> csv, Map<String, RideAutoExport.Output> json) {
        return new RideAutoExport.Source() {
            @Override public String routeCsv(String id) { return csv.get(id); }
            @Override public RideAutoExport.Output json(String id) { return json.get(id); }
        };
    }
    private static Map<String, RideAutoExport.Output> jsonFor(String... ids) {
        Map<String, RideAutoExport.Output> out = new HashMap<>();
        for (String id : ids) out.put(id, new RideAutoExport.Output(RideAutoExport.combinedJsonName(id), "{\"id\":\"" + id + "\"}"));
        return out;
    }

    private static void whichRidesExport() {
        Map<String, String> rides = new HashMap<>(); rides.put(A, "a\n"); rides.put(B, "b\n");
        FakeFolder folder = new FakeFolder();
        // NO_GPS: GPS off or denied, so no Route CSV; C: deleted before its turn. Both finish quietly.
        RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(A, NO_GPS, B, C), true, false, source(rides, jsonFor()), folder);
        check(r.problem == RideAutoExport.Problem.NONE && r.finished.equals(Arrays.asList(A, NO_GPS, B, C)) && r.saved.equals(Arrays.asList(RideAutoExport.routeCsvName(A), RideAutoExport.routeCsvName(B))), "Routes saved; rides without one skipped quietly");
        check(folder.files.keySet().equals(new TreeSet<>(Arrays.asList(RideAutoExport.routeCsvName(A), RideAutoExport.routeCsvName(B)))), "Only rides with a route get a file: " + folder.files.keySet());
        RideAutoExport.Result none = RideAutoExport.run(Collections.singletonList(NO_GPS), true, false, source(rides, jsonFor()), null);
        check(none.problem == RideAutoExport.Problem.NONE && none.finished.equals(Collections.singletonList(NO_GPS)), "A ride without a route needs no folder at all");
        RideAutoExport.Result again = RideAutoExport.run(Arrays.asList(A, B), true, false, source(rides, jsonFor()), folder);
        check(again.problem == RideAutoExport.Problem.NONE && again.saved.isEmpty() && again.finished.size() == 2 && folder.files.size() == 2, "Running the same rides again changes nothing");
        RideAutoExport.Result off = RideAutoExport.run(Arrays.asList(A, B), false, false, source(rides, jsonFor(A, B)), null);
        check(off.finished.size() == 2 && off.saved.isEmpty() && off.problem == RideAutoExport.Problem.NONE, "With both types off nothing is written");
    }

    private static void bothTypes() {
        Map<String, String> csv = new HashMap<>(); csv.put(A, "a\n");
        Map<String, RideAutoExport.Output> json = jsonFor(A, NO_GPS);
        FakeFolder folder = new FakeFolder();
        RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(A, NO_GPS), true, true, source(csv, json), folder);
        check(r.problem == RideAutoExport.Problem.NONE && r.finished.equals(Arrays.asList(A, NO_GPS)), "Both types: both rides finished");
        check(folder.files.keySet().equals(new TreeSet<>(Arrays.asList(RideAutoExport.routeCsvName(A), RideAutoExport.combinedJsonName(A), RideAutoExport.combinedJsonName(NO_GPS)))), "Route CSV where there are fixes, JSON for every ride: " + folder.files.keySet());
        FakeFolder onlyJson = new FakeFolder();
        RideAutoExport.run(Arrays.asList(A, NO_GPS), false, true, source(csv, json), onlyJson);
        check(onlyJson.files.keySet().equals(new TreeSet<>(Arrays.asList(RideAutoExport.combinedJsonName(A), RideAutoExport.combinedJsonName(NO_GPS)))), "JSON only: no Route CSV written");
    }

    /** A ride leaves the list only once every type that is on has been written; the written file is left alone on retry. */
    private static void partialFailureKeepsRideWaiting() {
        Map<String, String> csv = new HashMap<>(); csv.put(A, "a\n");
        FakeFolder folder = new FakeFolder(); folder.refuse = ".json";
        RideAutoExport.Result first = RideAutoExport.run(Collections.singletonList(A), true, true, source(csv, jsonFor(A)), folder);
        check(first.problem == RideAutoExport.Problem.SAVE && first.finished.isEmpty(), "Route CSV written, JSON failed: the ride waits");
        check(folder.files.keySet().equals(Collections.singleton(RideAutoExport.routeCsvName(A))) && folder.noDuplicatesOrTemporaries(), "No half-written JSON: " + folder.files.keySet());
        folder.refuse = null; folder.log.clear();
        RideAutoExport.Result second = RideAutoExport.run(Collections.singletonList(A), true, true, source(csv, jsonFor(A)), folder);
        check(second.problem == RideAutoExport.Problem.NONE && second.finished.equals(Collections.singletonList(A)) && second.saved.equals(Collections.singletonList(RideAutoExport.combinedJsonName(A))), "Retry writes only the missing JSON");
        check(!folder.log.contains("create " + RideAutoExport.temporaryName(RideAutoExport.routeCsvName(A))) && folder.files.size() == 2 && folder.noDuplicatesOrTemporaries(), "The saved Route CSV is left alone: " + folder.log);
    }

    private static void failures() {
        Map<String, String> rides = new HashMap<>(); rides.put(A, "a\n"); rides.put(B, "b\n"); rides.put(C, "c\n");
        RideAutoExport.Result restored = RideAutoExport.run(Arrays.asList(A, NO_GPS, B), true, false, source(rides, jsonFor()), null);
        check(restored.problem == RideAutoExport.Problem.FOLDER && restored.finished.isEmpty(), "No folder on this phone: rides wait; nothing is dropped");
        RideAutoExport.Result restoredJson = RideAutoExport.run(Collections.singletonList(NO_GPS), false, true, source(rides, jsonFor(NO_GPS)), null);
        check(restoredJson.problem == RideAutoExport.Problem.FOLDER && restoredJson.finished.isEmpty(), "JSON needs the folder for every ride");
        FakeFolder gone = new FakeFolder(); gone.gone = true;
        RideAutoExport.Result revoked = RideAutoExport.run(Arrays.asList(A, B), true, true, source(rides, jsonFor(A, B)), gone);
        check(revoked.problem == RideAutoExport.Problem.FOLDER && revoked.finished.isEmpty(), "Unreachable folder keeps both rides waiting");
        FakeFolder full = new FakeFolder(); full.full = true;
        RideAutoExport.Result noSpace = RideAutoExport.run(Arrays.asList(A, B), true, false, source(rides, jsonFor()), full);
        check(noSpace.problem == RideAutoExport.Problem.SAVE && noSpace.finished.isEmpty() && full.files.isEmpty(), "Storage full: both wait, no partial file");
        FakeFolder stuck = new FakeFolder(); stuck.renameFails = true;
        RideAutoExport.Result refused = RideAutoExport.run(Collections.singletonList(A), true, false, source(rides, jsonFor()), stuck);
        check(refused.problem == RideAutoExport.Problem.SAVE && refused.finished.isEmpty() && stuck.files.isEmpty(), "Failed rename leaves no temporary file: " + stuck.files.keySet());
        FakeFolder race = new FakeFolder() {
            @Override public void rename(String from, String to) throws IOException { files.put(to, "other".getBytes(StandardCharsets.UTF_8)); super.rename(from, to); }
        };
        RideAutoExport.Result raced = RideAutoExport.run(Collections.singletonList(A), true, false, source(rides, jsonFor()), race);
        check(raced.problem == RideAutoExport.Problem.SAVE && raced.finished.isEmpty(), "A rename the provider suffixed counts as a failure");
        RideAutoExport.Source flaky = new RideAutoExport.Source() {
            @Override public String routeCsv(String id) throws IOException { if (id.equals(A)) throw new IOException("journal busy"); return rides.get(id); }
            @Override public RideAutoExport.Output json(String id) { return null; }
        };
        FakeFolder ok = new FakeFolder();
        RideAutoExport.Result partly = RideAutoExport.run(Arrays.asList(A, B), true, false, flaky, ok);
        check(partly.problem == RideAutoExport.Problem.SAVE && partly.finished.equals(Collections.singletonList(B)) && ok.files.size() == 1, "One unreadable ride does not hold up the next");
        FakeFolder unreadable = new FakeFolder(); unreadable.files.put(RideAutoExport.routeCsvName(A), "a\n".getBytes(StandardCharsets.UTF_8)); unreadable.readFails = true;
        check(RideAutoExport.run(Collections.singletonList(A), true, false, source(rides, jsonFor()), unreadable).problem == RideAutoExport.Problem.SAVE, "An unreadable existing file is a save problem, not a silent skip");
    }

    private static void retryAfterFailure() {
        Map<String, String> rides = new HashMap<>(); rides.put(A, "a\n"); rides.put(B, "b\n");
        FakeFolder folder = new FakeFolder(); folder.gone = true;
        String stored = RideAutoExport.add("", A);
        RideAutoExport.Result first = RideAutoExport.run(RideAutoExport.parse(stored), true, true, source(rides, jsonFor(A, B)), folder);
        stored = RideAutoExport.remove(stored, first.finished);
        check(stored.equals(A) && first.problem == RideAutoExport.Problem.FOLDER, "Failed ride stays waiting");
        folder.gone = false; stored = RideAutoExport.add(stored, B);
        RideAutoExport.Result second = RideAutoExport.run(RideAutoExport.parse(stored), true, true, source(rides, jsonFor(A, B)), folder);
        stored = RideAutoExport.remove(stored, second.finished);
        check(stored.isEmpty() && second.problem == RideAutoExport.Problem.NONE && folder.files.size() == 4 && folder.noDuplicatesOrTemporaries(), "Retry on the next ride end exports both rides, both types");
    }

    // ---- The real stores ----------------------------------------------------------------------------

    private static final class Fixture {
        final File dir; final RideStore rides; final PhoneRideStore phone; final String gps, motionOnly, scooterOnly;
        Fixture() throws IOException {
            dir = Files.createTempDirectory("ride-auto-export-test").toFile();
            rides = new RideStore(new File(dir, "rides")); phone = new PhoneRideStore(new File(dir, "phone-rides"));
            gps = ride("Commute 🛴", 1700000000000L, true, true);
            motionOnly = ride("Pocket, GPS off", 1700000100000L, false, true);
            scooterOnly = ride("", 1700000200000L, false, false);
        }
        private String ride(String label, long wall, boolean fixes, boolean phoneFiles) throws IOException {
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
            if (phoneFiles) {
                phone.start(id, wall, 1000);
                StringBuilder journal = new StringBuilder();
                if (fixes) for (int i = 0; i <= 20; i++) {
                    long mono = 1000 + i * 1000;
                    journal.append(PhoneRideStore.row("GPS", wall + mono, mono, 43.82 + i * 5 / 111194.9266, -111.78, 3, 5, .2, 1500.0, 4.0));
                }
                journal.append(PhoneRideStore.row("MOTION", wall + 5000, 5000, 120, 1.25));
                phone.appendBatch(id, journal.toString(), ""); phone.stop(id, wall + 22000, 22000);
            }
            rides.stop(wall + 22000, 22000);
            return id;
        }
        void delete() { delete(dir); }
        private static void delete(File f) { File[] children = f.listFiles(); if (children != null) for (File c : children) delete(c); f.delete(); }
    }

    /** Each file is the manual export's bytes: Route CSV, All (JSON) as RidesScreen built it inline, Export JSON. */
    private static void storeBytesMatchManualExports() throws IOException {
        Fixture f = new Fixture();
        try {
            RideAutoExport.Source source = RideAutoExport.stores(f.rides, f.phone);
            PhoneRideStore.Track track = f.phone.load(f.gps); RideStore.Summary summary = f.rides.get(f.gps);
            check(source.routeCsv(f.gps).equals(track.toCsv()), "Route CSV equals Track.toCsv()");
            check(track.toCsv().startsWith("wall_ms,monotonic_ms,") && track.toCsv().split("\n").length == 22, "Header plus one row per fix");
            // The expression RidesScreen.exportPhone used inline before it called RideAutoExport.combinedJson.
            String inline = "{\"scooter\":" + (summary == null ? "null" : summary.toJson()) + ",\"phone\":" + track.toJson() + "}";
            RideAutoExport.Output json = source.json(f.gps);
            check(json.name.equals(RideAutoExport.combinedJsonName(f.gps)) && json.text.equals(inline), "JSON equals All (JSON)");
            check(RideAutoExport.combinedJson(null, track).equals("{\"scooter\":null,\"phone\":" + track.toJson() + "}"), "All (JSON) without a scooter journal keeps null, as by hand");
            check(source.routeCsv(f.motionOnly) == null, "No GPS fixes: no Route CSV");
            RideAutoExport.Output motion = source.json(f.motionOnly);
            check(motion.name.equals(RideAutoExport.combinedJsonName(f.motionOnly)) && motion.text.contains("\"motionSampleCount\":120"), "A motion-only phone recording still gets All (JSON)");
            RideAutoExport.Output scooter = source.json(f.scooterOnly);
            check(source.routeCsv(f.scooterOnly) == null && scooter.name.equals(RideAutoExport.analysisJsonName(f.scooterOnly)) && scooter.text.equals(f.rides.get(f.scooterOnly).toJson()), "No phone recording: Export JSON's analysis");
            check(source.routeCsv(C) == null && source.json(C) == null, "A deleted ride has nothing to write");
            FakeFolder folder = new FakeFolder();
            RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(f.gps, f.motionOnly, f.scooterOnly, C), true, true, source, folder);
            check(r.problem == RideAutoExport.Problem.NONE && r.finished.size() == 4 && folder.files.size() == 4, "Every ride finished: " + folder.files.keySet());
            check(Arrays.equals(folder.files.get(RideAutoExport.routeCsvName(f.gps)), track.toCsv().getBytes(StandardCharsets.UTF_8)), "Route CSV written byte for byte");
            check(Arrays.equals(folder.files.get(RideAutoExport.combinedJsonName(f.gps)), inline.getBytes(StandardCharsets.UTF_8)), "All (JSON) written byte for byte, UTF-8 ride name included");
            check(folder.text(RideAutoExport.combinedJsonName(f.gps)).contains("Commute 🛴"), "The ride's own name stays inside the file, never in its name");
        } finally { f.delete(); }
    }

    /** The whole export path with a text source that throws on any use: the same files, so none reads the catalog. */
    private static void throwingTextSource() throws IOException {
        Map<String, String> english = exportAll();
        T.install(new T.Source() {
            public String string(int id) { throw new AssertionError("an automatic ride copy read catalog text " + T.key(id)); }
            public String plural(int id, long quantity) { throw new AssertionError("an automatic ride copy read catalog plural " + T.key(id)); }
            public String language() { throw new AssertionError("an automatic ride copy asked for the language"); }
        });
        Map<String, String> guarded;
        Locale before = Locale.getDefault();
        try {
            // A comma-decimal default locale as well: the files keep dots.
            Locale.setDefault(Locale.GERMANY);
            guarded = exportAll();
        } finally {
            Locale.setDefault(before);
            T.install(null);
        }
        check(guarded.equals(english), "The same files without the catalog and in a German default locale");
        check(english.size() == 5, "Route CSV, two All (JSON) files and one Export JSON were written: " + english.keySet());
    }
    /** A fresh fixture, exported through the real stores with both types on; names and texts, without the ride ids' random part. */
    private static Map<String, String> exportAll() throws IOException {
        Fixture f = new Fixture();
        try {
            FakeFolder folder = new FakeFolder();
            RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(f.gps, f.motionOnly, f.scooterOnly), true, true, RideAutoExport.stores(f.rides, f.phone), folder);
            check(r.problem == RideAutoExport.Problem.NONE, "Export pass succeeded");
            Map<String, String> out = new TreeMap<>();
            String[] ids = {f.gps, f.motionOnly, f.scooterOnly}, roles = {"gps", "motion", "scooter"};
            for (Map.Entry<String, byte[]> e : folder.files.entrySet()) {
                String name = e.getKey(), text = new String(e.getValue(), StandardCharsets.UTF_8);
                for (int i = 0; i < ids.length; i++) { name = name.replace(ids[i].substring(0, 8), roles[i]); text = text.replace(ids[i], roles[i]); }
                out.put(name, text);
            }
            out.put("RideAutoExport.combinedJson", RideAutoExport.combinedJson(f.rides.get(f.gps), f.phone.load(f.gps)).replace(f.gps, "gps"));
            return out;
        } finally { f.delete(); }
    }
}
