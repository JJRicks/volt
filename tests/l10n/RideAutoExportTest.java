package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Automatic ride copies (RideAutoExport): the file name, no duplicates, the persisted waiting list, the one switch and
 * the retired Route CSV switch it still reads, when a ride leaves the list, the failure ids and the settings row's
 * state, and the file's bytes against the Export ride data sheet's Everything export. The folder is an in-memory fake
 * that names clashes the way Android's local storage provider does ("name (1).json"), so a duplicate would show up here.
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
        private void reachable() throws IOException { if (gone) throw new RideAutoExport.Unavailable("gone"); }
        private String unique(String name) {
            if (!files.containsKey(name)) return name;
            int dot = name.lastIndexOf('.'); String base = dot <= 0 ? name : name.substring(0, dot), ext = dot <= 0 ? "" : name.substring(dot);
            for (int n = 1; ; n++) if (!files.containsKey(base + " (" + n + ")" + ext)) return base + " (" + n + ")" + ext;
        }
        @Override public InputStream open(String name) throws IOException {
            reachable(); log.add("read " + name); if (readFails && files.containsKey(name)) throw new IOException("read");
            byte[] b = files.get(name); return b == null ? null : new ByteArrayInputStream(b);
        }
        @Override public void delete(String name) throws IOException { reachable(); if (files.remove(name) != null) log.add("delete " + name); }
        @Override public String create(String name, File data) throws IOException {
            reachable(); if (full) throw new IOException("ENOSPC");
            String given = unique(name); files.put(given, Files.readAllBytes(data.toPath())); log.add("create " + given); return given;
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
            C = "9c0d1e2f-0000-4000-8000-000000000003";
    private static File scratch;

    public static void main(String[] args) throws Exception {
        scratch = Files.createTempDirectory("ride-auto-export-scratch").toFile();
        try {
            names(); theSwitch(); waitingList(); folderLabels(); problemsAndRow();
            savesThroughTemporary(); sameFileTwice(); changedFileReplaced(); interruptedPassCleanedUp(); oldFilesLeftAlone();
            whichRidesExport(); failures(); retryAfterFailure(); cacheFilesRemoved();
            storeBytesMatchTheSheet();
            throwingTextSource();
        } finally { Fixture.delete(scratch); }
        System.out.println("RideAutoExportTest: " + checks + " checks passed");
    }

    private static void names() {
        check(RideAutoExport.everythingName(A).equals("ride-1a2b3c4d-everything.json"), "Same name as the sheet's Everything in one JSON");
        check(RideAutoExport.everythingName(A).equals(RideExportFiles.name(RideExportFiles.Format.EVERYTHING_JSON, A)), "The sheet's own name rule");
        check(RideAutoExport.everythingName("abc").equals("ride-abc-everything.json"), "Short ids keep the manual export's rule");
        String temporary = RideAutoExport.temporaryName(RideAutoExport.everythingName(A));
        check(temporary.startsWith(".") && temporary.endsWith(".partial") && !temporary.endsWith(".json"), "Temporary name is hidden and never looks like a finished file: " + temporary);
        check(RideAutoExport.ON.equals("ride_auto_export_json") && RideAutoExport.LEGACY_ROUTE_CSV.equals("ride_auto_export_route_csv")
                && RideAutoExport.FOLDER.equals("ride_auto_export_folder") && RideAutoExport.PENDING.equals("ride_auto_export_pending")
                && RideAutoExport.PROBLEM.equals("ride_auto_export_problem"), "Stored keys are the ones BackupRegistry classifies");
        for (String key : new String[]{RideAutoExport.ON, RideAutoExport.LEGACY_ROUTE_CSV}) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", key);
            check(rule != null && BackupRegistry.PREFERENCES.equals(rule.category) && rule.type == Boolean.class, key + " travels with App preferences as a boolean");
        }
        for (String key : new String[]{RideAutoExport.FOLDER, RideAutoExport.PENDING, RideAutoExport.PROBLEM}) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", key);
            check(rule != null && rule.excluded() && rule.type == String.class, key + " stays on this phone");
        }
    }

    /**
     * The one switch after the update, and backups both ways. The stored pair is (ride_auto_export_json, the retired
     * ride_auto_export_route_csv); RideAutoExportFolder.set writes (on, false).
     */
    private static void theSwitch() {
        check(!RideAutoExport.on(false, false), "Absent or both off: off, as on a fresh install");
        check(RideAutoExport.on(false, true), "Only the old Route CSV switch on: the update keeps auto-save on");
        check(RideAutoExport.on(true, false), "Only the old ride JSON switch on: on");
        check(RideAutoExport.on(true, true), "Both old switches on: on");
        // Restoring an older backup maps its two keys the same way: they restore as they are, then read as one.
        check(RideAutoExport.on(Boolean.TRUE, Boolean.FALSE) && RideAutoExport.on(Boolean.FALSE, Boolean.TRUE) && !RideAutoExport.on(Boolean.FALSE, Boolean.FALSE), "An older backup: either one on means on");
        // A newer backup holds what set wrote: (on, false). An older Volt reads ride_auto_export_json as its ride JSON switch.
        check(RideAutoExport.ON.equals("ride_auto_export_json"), "The switch keeps the old ride JSON key, so an older Volt restores it as that switch");
        check(!RideAutoExport.on(false, false) && RideAutoExport.on(true, false), "What set writes reads back as the switch, here and in an older Volt");
        // The update's migration and the backup copy: Route CSV only becomes (true, false); the folder and waiting rides stay.
        Map<String, Object> routeOnly = new LinkedHashMap<>();
        routeOnly.put(RideAutoExport.LEGACY_ROUTE_CSV, true); routeOnly.put(RideAutoExport.ON, false);
        routeOnly.put(RideAutoExport.FOLDER, "content://tree"); routeOnly.put(RideAutoExport.PENDING, A);
        check(RideAutoExport.normalize(routeOnly) && Boolean.TRUE.equals(routeOnly.get(RideAutoExport.ON)) && Boolean.FALSE.equals(routeOnly.get(RideAutoExport.LEGACY_ROUTE_CSV))
                && "content://tree".equals(routeOnly.get(RideAutoExport.FOLDER)) && A.equals(routeOnly.get(RideAutoExport.PENDING)) && routeOnly.size() == 4, "Route CSV only: normalized to the switch on, folder and waiting rides kept");
        check(!RideAutoExport.normalize(routeOnly), "Normalizing twice changes nothing");
        Map<String, Object> jsonOnly = new LinkedHashMap<>(); jsonOnly.put(RideAutoExport.ON, true);
        Map<String, Object> off = new LinkedHashMap<>(); off.put(RideAutoExport.ON, false); off.put(RideAutoExport.LEGACY_ROUTE_CSV, false);
        Map<String, Object> fresh = new LinkedHashMap<>();
        check(!RideAutoExport.normalize(jsonOnly) && jsonOnly.size() == 1 && !RideAutoExport.normalize(off) && Boolean.FALSE.equals(off.get(RideAutoExport.ON))
                && !RideAutoExport.normalize(fresh) && fresh.isEmpty() && !RideAutoExport.normalize(null), "Ride JSON only, off and a fresh install are left as they are");
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
    }

    // ---- One file ---------------------------------------------------------------------------------

    private static File file(String text) throws IOException {
        File f = File.createTempFile("everything", ".json", scratch);
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static void savesThroughTemporary() throws IOException {
        FakeFolder folder = new FakeFolder(); String name = RideAutoExport.everythingName(A), temporary = RideAutoExport.temporaryName(name);
        check(RideAutoExport.save(folder, name, file("{\"a\":1}")) == RideAutoExport.Outcome.SAVED, "New file saved");
        check("{\"a\":1}".equals(folder.text(name)) && folder.files.size() == 1, "Exactly the bytes, under the final name only");
        check(folder.log.contains("create " + temporary) && folder.log.contains("rename " + temporary + " -> " + name), "Written under the temporary name, then renamed: " + folder.log);
        check(!folder.log.contains("create " + name), "The final name never holds a file being written");
    }

    private static void sameFileTwice() throws IOException {
        FakeFolder folder = new FakeFolder(); String name = RideAutoExport.everythingName(A);
        RideAutoExport.save(folder, name, file("{}")); folder.log.clear();
        check(RideAutoExport.save(folder, name, file("{}")) == RideAutoExport.Outcome.UNCHANGED, "Unchanged bytes are left alone");
        check(folder.files.size() == 1 && folder.noDuplicatesOrTemporaries(), "No second copy");
        for (String entry : folder.log) check(entry.startsWith("read "), "Leaving it alone writes nothing: " + entry);
    }

    private static void changedFileReplaced() throws IOException {
        FakeFolder folder = new FakeFolder(); String name = RideAutoExport.everythingName(A);
        RideAutoExport.save(folder, name, file("{\"old\":1}"));
        check(RideAutoExport.save(folder, name, file("{\"new\":1}")) == RideAutoExport.Outcome.SAVED, "Changed content replaces the file");
        check(folder.files.size() == 1 && "{\"new\":1}".equals(folder.text(name)) && folder.noDuplicatesOrTemporaries(), "Replaced in place, never name (1).json: " + folder.files.keySet());
        RideAutoExport.save(folder, name, file("{\"new\":1,\"longer\":true}"));
        check("{\"new\":1,\"longer\":true}".equals(folder.text(name)), "A longer file with the same start is a change");
        RideAutoExport.save(folder, name, file("{\"new\":1"));
        check("{\"new\":1".equals(folder.text(name)) && folder.files.size() == 1, "A shorter file with the same start is a change");
        FakeFolder manual = new FakeFolder(); manual.files.put(RideAutoExport.everythingName(B), "same".getBytes(StandardCharsets.UTF_8));
        check(RideAutoExport.save(manual, RideAutoExport.everythingName(B), file("same")) == RideAutoExport.Outcome.UNCHANGED && manual.files.size() == 1, "A manual export of the same ride is not duplicated");
    }

    private static void interruptedPassCleanedUp() throws IOException {
        String name = RideAutoExport.everythingName(A), temporary = RideAutoExport.temporaryName(name);
        FakeFolder folder = new FakeFolder(); folder.files.put(temporary, "half".getBytes(StandardCharsets.UTF_8));
        RideAutoExport.save(folder, name, file("whole"));
        check(folder.files.size() == 1 && folder.noDuplicatesOrTemporaries() && "whole".equals(folder.text(name)), "A temporary file left by an interrupted pass is replaced, not suffixed: " + folder.files.keySet());
        FakeFolder unchanged = new FakeFolder();
        unchanged.files.put(name, "whole".getBytes(StandardCharsets.UTF_8)); unchanged.files.put(temporary, "half".getBytes(StandardCharsets.UTF_8));
        RideAutoExport.save(unchanged, name, file("whole"));
        check(unchanged.files.size() == 1, "A stale temporary file is removed even when the file is already there");
    }

    /** Files the two old switches saved stay in the folder as they are: auto-save only ever writes its own name. */
    private static void oldFilesLeftAlone() {
        FakeFolder folder = new FakeFolder();
        String route = "ride-1a2b3c4d-route.csv", combined = "ride-1a2b3c4d-combined.json", analysis = "ride-5e6f7a8b-analysis.json";
        for (String old : new String[]{route, combined, analysis}) folder.files.put(old, old.getBytes(StandardCharsets.UTF_8));
        RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(A, B), source(A, B), folder);
        check(r.problem == RideAutoExport.Problem.NONE && r.finished.equals(Arrays.asList(A, B)), "Both waiting rides written");
        for (String old : new String[]{route, combined, analysis}) check(Arrays.equals(folder.files.get(old), old.getBytes(StandardCharsets.UTF_8)), "Left as it was: " + old);
        check(folder.files.size() == 5 && folder.files.containsKey(RideAutoExport.everythingName(A)) && folder.files.containsKey(RideAutoExport.everythingName(B)), "Everything files beside the old ones: " + folder.files.keySet());
        for (String entry : folder.log) check(!entry.contains("route.csv") && !entry.contains("combined.json") && !entry.contains("analysis.json"), "Never read, deleted or renamed: " + entry);
    }

    // ---- Passes -----------------------------------------------------------------------------------

    /** Outputs made by the fake source, still in the cache (not yet discarded). */
    private static final List<RideAutoExport.Output> made = new ArrayList<>();
    /** A fake source: each listed id has a small file; any other id is a deleted ride. */
    private static RideAutoExport.Source source(String... ids) {
        Set<String> rides = new HashSet<>(Arrays.asList(ids));
        return id -> {
            if (!rides.contains(id)) return null;
            File own = RideExportFiles.newStaging(scratch, System.currentTimeMillis());
            File f = new File(own, RideAutoExport.everythingName(id));
            Files.write(f.toPath(), ("{\"rideId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
            RideAutoExport.Output out = new RideAutoExport.Output(RideAutoExport.everythingName(id), f, own);
            made.add(out); return out;
        };
    }

    private static void whichRidesExport() {
        FakeFolder folder = new FakeFolder();
        // C: deleted before its turn. It finishes quietly.
        RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(A, C, B), source(A, B), folder);
        check(r.problem == RideAutoExport.Problem.NONE && r.finished.equals(Arrays.asList(A, C, B)) && r.saved.equals(Arrays.asList(RideAutoExport.everythingName(A), RideAutoExport.everythingName(B))), "Saved; a deleted ride is skipped quietly");
        check(folder.files.keySet().equals(new TreeSet<>(Arrays.asList(RideAutoExport.everythingName(A), RideAutoExport.everythingName(B)))) && folder.noDuplicatesOrTemporaries(), "One Everything file per ride: " + folder.files.keySet());
        RideAutoExport.Result none = RideAutoExport.run(Collections.singletonList(C), source(), null);
        check(none.problem == RideAutoExport.Problem.NONE && none.finished.equals(Collections.singletonList(C)), "A deleted ride needs no folder at all");
        RideAutoExport.Result again = RideAutoExport.run(Arrays.asList(A, B), source(A, B), folder);
        check(again.problem == RideAutoExport.Problem.NONE && again.saved.isEmpty() && again.finished.size() == 2 && folder.files.size() == 2, "Running the same rides again changes nothing");
    }

    private static void failures() {
        RideAutoExport.Result restored = RideAutoExport.run(Arrays.asList(A, C, B), source(A, B), null);
        check(restored.problem == RideAutoExport.Problem.FOLDER && restored.finished.isEmpty(), "No folder on this phone: rides wait; nothing is dropped");
        FakeFolder gone = new FakeFolder(); gone.gone = true;
        RideAutoExport.Result revoked = RideAutoExport.run(Arrays.asList(A, B), source(A, B), gone);
        check(revoked.problem == RideAutoExport.Problem.FOLDER && revoked.finished.isEmpty(), "Unreachable folder keeps both rides waiting");
        FakeFolder full = new FakeFolder(); full.full = true;
        RideAutoExport.Result noSpace = RideAutoExport.run(Arrays.asList(A, B), source(A, B), full);
        check(noSpace.problem == RideAutoExport.Problem.SAVE && noSpace.finished.isEmpty() && full.files.isEmpty(), "Storage full: both wait, no partial file");
        FakeFolder stuck = new FakeFolder(); stuck.renameFails = true;
        RideAutoExport.Result refused = RideAutoExport.run(Collections.singletonList(A), source(A), stuck);
        check(refused.problem == RideAutoExport.Problem.SAVE && refused.finished.isEmpty() && stuck.files.isEmpty(), "Failed rename leaves no temporary file: " + stuck.files.keySet());
        FakeFolder race = new FakeFolder() {
            @Override public void rename(String from, String to) throws IOException { files.put(to, "other".getBytes(StandardCharsets.UTF_8)); super.rename(from, to); }
        };
        RideAutoExport.Result raced = RideAutoExport.run(Collections.singletonList(A), source(A), race);
        check(raced.problem == RideAutoExport.Problem.SAVE && raced.finished.isEmpty(), "A rename the provider suffixed counts as a failure");
        RideAutoExport.Source good = source(A, B);
        RideAutoExport.Source flaky = id -> { if (id.equals(A)) throw new IOException("journal busy"); return good.everything(id); };
        FakeFolder ok = new FakeFolder();
        RideAutoExport.Result partly = RideAutoExport.run(Arrays.asList(A, B), flaky, ok);
        check(partly.problem == RideAutoExport.Problem.SAVE && partly.finished.equals(Collections.singletonList(B)) && ok.files.size() == 1, "One unreadable ride does not hold up the next");
        FakeFolder unreadable = new FakeFolder(); unreadable.files.put(RideAutoExport.everythingName(A), "a".getBytes(StandardCharsets.UTF_8)); unreadable.readFails = true;
        check(RideAutoExport.run(Collections.singletonList(A), source(A), unreadable).problem == RideAutoExport.Problem.SAVE, "An unreadable existing file is a save problem, not a silent skip");
    }

    private static void retryAfterFailure() {
        FakeFolder folder = new FakeFolder(); folder.gone = true;
        String stored = RideAutoExport.add("", A);
        RideAutoExport.Result first = RideAutoExport.run(RideAutoExport.parse(stored), source(A, B), folder);
        stored = RideAutoExport.remove(stored, first.finished);
        check(stored.equals(A) && first.problem == RideAutoExport.Problem.FOLDER, "Failed ride stays waiting");
        folder.gone = false; stored = RideAutoExport.add(stored, B);
        RideAutoExport.Result second = RideAutoExport.run(RideAutoExport.parse(stored), source(A, B), folder);
        stored = RideAutoExport.remove(stored, second.finished);
        check(stored.isEmpty() && second.problem == RideAutoExport.Problem.NONE && folder.files.size() == 2 && folder.noDuplicatesOrTemporaries(), "Retry on the next ride end exports both rides");
    }

    /** Every file a pass made in the cache is gone afterwards, written or not. */
    private static void cacheFilesRemoved() {
        check(!made.isEmpty(), "The passes above made files");
        for (RideAutoExport.Output out : made) check(!out.file.exists() && !out.file.getParentFile().exists(), "Discarded after its pass: " + out.file);
        made.clear();
    }

    // ---- The real stores ----------------------------------------------------------------------------

    private static final class Fixture {
        final File dir, cache; final RideStore rides; final PhoneRideStore phone; final String gps, motionOnly, scooterOnly;
        Fixture() throws IOException {
            dir = Files.createTempDirectory("ride-auto-export-test").toFile();
            cache = new File(dir, "cache"); if (!cache.mkdirs()) throw new IOException("cache");
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
        /** The sheet's Everything file for a ride, as RidesScreen.exportSheet loads it (phone.load, or no track). */
        byte[] sheet(String id, String version) throws IOException {
            RideStore.Summary summary = rides.get(id);
            PhoneRideStore.Track track = phone.rawCsv(id).isFile() || phone.motionCsv(id).isFile() ? phone.load(id) : null;
            RideExportFiles.Payload p = RideExportFiles.payload(RideExportFiles.Format.EVERYTHING_JSON, id, rides, summary, phone, track, cache, version);
            try { return p.bytes(); } finally { p.discard(); }
        }
        void delete() { delete(dir); }
        static void delete(File f) { File[] children = f.listFiles(); if (children != null) for (File c : children) delete(c); f.delete(); }
    }

    /** Each ride's file is the sheet's Everything file, byte for byte, with or without a phone recording. */
    private static void storeBytesMatchTheSheet() throws IOException {
        Fixture f = new Fixture();
        try {
            RideAutoExport.Source source = RideAutoExport.stores(f.rides, f.phone, f.cache, "1.12.0");
            check(source.everything(C) == null, "A deleted ride has nothing to write");
            FakeFolder folder = new FakeFolder();
            RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(f.gps, f.motionOnly, f.scooterOnly, C), source, folder);
            check(r.problem == RideAutoExport.Problem.NONE && r.finished.size() == 4 && folder.files.size() == 3 && folder.noDuplicatesOrTemporaries(), "Every ride finished, one file each: " + folder.files.keySet());
            for (String id : new String[]{f.gps, f.motionOnly, f.scooterOnly}) {
                byte[] auto = folder.files.get(RideAutoExport.everythingName(id));
                check(auto != null && Arrays.equals(auto, f.sheet(id, "1.12.0")), "Written byte for byte as the sheet's Everything in one JSON: " + id);
                String text = new String(auto, StandardCharsets.UTF_8);
                check(text.startsWith("{\"format\":\"volt-ride-everything\",\"formatVersion\":1,\"rideId\":\"" + id + "\""), "The Everything format, for this ride");
            }
            check(folder.text(RideAutoExport.everythingName(f.gps)).contains("\"phone\":{\"journal\":") && folder.text(RideAutoExport.everythingName(f.scooterOnly)).contains("\"phone\":null"), "With and without a phone recording");
            check(folder.text(RideAutoExport.everythingName(f.gps)).contains("Commute 🛴"), "The ride's own name stays inside the file, never in its name");
            String[] left = f.cache.list();
            check(left != null && left.length == 0, "Nothing left in the cache: " + Arrays.toString(left));
            RideAutoExport.Result again = RideAutoExport.run(Arrays.asList(f.gps, f.motionOnly, f.scooterOnly), RideAutoExport.stores(f.rides, f.phone, f.cache, "1.12.0"), folder);
            check(again.saved.isEmpty() && again.finished.size() == 3 && folder.files.size() == 3, "The same rides again: unchanged files left alone");
            RideAutoExport.Result updated = RideAutoExport.run(Collections.singletonList(f.gps), RideAutoExport.stores(f.rides, f.phone, f.cache, "1.12.1"), folder);
            check(updated.saved.equals(Collections.singletonList(RideAutoExport.everythingName(f.gps))) && folder.files.size() == 3
                    && Arrays.equals(folder.files.get(RideAutoExport.everythingName(f.gps)), f.sheet(f.gps, "1.12.1")), "A changed file (another app version) replaces it in place");
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
        check(english.size() == 3, "Three Everything files were written: " + english.keySet());
    }
    /** A fresh fixture, exported through the real stores; names and texts, without the ride ids' random part. */
    private static Map<String, String> exportAll() throws IOException {
        Fixture f = new Fixture();
        try {
            FakeFolder folder = new FakeFolder();
            RideAutoExport.Result r = RideAutoExport.run(Arrays.asList(f.gps, f.motionOnly, f.scooterOnly), RideAutoExport.stores(f.rides, f.phone, f.cache, "1.12.0"), folder);
            check(r.problem == RideAutoExport.Problem.NONE, "Export pass succeeded");
            Map<String, String> out = new TreeMap<>();
            String[] ids = {f.gps, f.motionOnly, f.scooterOnly}, roles = {"gps", "motion", "scooter"};
            for (Map.Entry<String, byte[]> e : folder.files.entrySet()) {
                String name = e.getKey(), text = new String(e.getValue(), StandardCharsets.UTF_8);
                for (int i = 0; i < ids.length; i++) { name = name.replace(ids[i].substring(0, 8), roles[i]); text = text.replace(ids[i], roles[i]); }
                out.put(name, text);
            }
            return out;
        } finally { f.delete(); }
    }
}
