package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saved ride summaries (RideSummaryStore): what a cold start reads from disk must equal a fresh journal replay, field
 * by field, for every kind of ride; anything stale, damaged or from another version must miss and be rebuilt.
 * Pure Java, temporary folders only.
 */
public final class RideSummaryStoreTest {
    private static int checks;
    private static final long WALL = 1791806400000L, DAY = 86400000L;

    public static void main(String[] args) throws Exception {
        savedEntriesEqualAFreshReplay();
        keyAndVersionMismatchesMiss();
        damagedEntriesMissAndAreRebuilt();
        unfinishedJournalsAreNeverServedFromDisk();
        pruneAndDelete();
        signTransitionsSurviveTheCache();
        neverInABackup();
        System.out.println("RideSummaryStoreTest: " + checks + " checks passed");
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------
    private static File directory(String name) throws Exception { return Files.createTempDirectory("volt-summary-" + name + "-").toFile(); }
    private static Map<String, RideStore.Sample> sample(long mono, double watts, double speed, double odo, double soc) {
        Map<String, RideStore.Sample> values = new LinkedHashMap<>();
        values.put("voltage_v", new RideStore.Sample(50.0 + (mono % 7000) / 1000.0, "V", "V", 0x72, mono));
        values.put("current_a", new RideStore.Sample(watts / 50, "A", "A", 0x72, mono));
        values.put("speed_kmh", new RideStore.Sample(speed, mono));
        values.put("odometer_km", new RideStore.Sample(odo, mono));
        values.put("battery_percent", new RideStore.Sample(soc, mono));
        values.put("temperature_c", new RideStore.Sample(21 + (mono % 5000) / 1000.0, mono));
        values.put("charging_state", new RideStore.Sample(0, mono));
        return values;
    }
    /** Ids of the fixture rides, by kind. */
    private static final class Fixtures { String scooter, autoStopped, recovered, merged, renamed, gaps; List<String> all() {
        return Arrays.asList(scooter, autoStopped, recovered, merged, renamed, gaps); } }

    /** Every fixture kind, recorded through an index-enabled store; returns after their saves are on disk. */
    private static Fixtures record(File rides, File index) throws Exception {
        Fixtures f = new Fixtures();
        RideStore store = new RideStore(rides, false, index);
        // Scooter ride: 900 samples (the preview thins to 600), a confirmed current direction part-way, regeneration.
        f.scooter = store.start("UT5", "Commute", WALL, 0);
        for (int i = 0; i < 900; i++) {
            if (i == 300) store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
            store.sample(WALL + i * 1000L, i * 1000L, sample(i * 1000L, i % 40 == 0 ? -120 : 150 + i % 9, 18 + i % 5, i * .005, 90 - i / 60.0));
        }
        store.setNotes("Synthetic note, \"quoted\"\nsecond line");
        store.stop(WALL + 900 * 1000L, 900 * 1000L);
        store.setCurrentDirection(RideStore.CurrentDirection.UNCONFIRMED);
        // Auto-stopped: readings, then a quiet link (heartbeats, a gap) before Volt ends it; the shown end is earlier.
        long w = WALL + DAY;
        f.autoStopped = store.start("UT5", "Parked", w, 0);
        for (int i = 0; i < 120; i++) store.sample(w + i * 1000L, i * 1000L, sample(i * 1000L, 140, 15, i * .004, 70));
        store.markGap(w + 125000, 125000, "Link lost");
        for (int i = 0; i < 20; i++) store.heartbeat(w + 130000 + i * 10000L, 130000 + i * 10000L);
        store.stop(w + 400000, 400000, RideStore.quietStopReason(8, true));
        // Recovered: the process dies mid-ride; a new store recovers it and the ride continues and stops.
        w = WALL + 2 * DAY;
        f.recovered = store.start("UT5", "Interrupted", w, 0);
        for (int i = 0; i < 80; i++) store.sample(w + i * 1000L, i * 1000L, sample(i * 1000L, 160, 20, i * .005, 60));
        store.flushIndex();
        RideStore restarted = new RideStore(rides, false, index);
        check(f.recovered.equals(restarted.activeId()), "fixture: the restarted store recovers the open ride");
        for (int i = 0; i < 80; i++) restarted.sample(w + 90000 + i * 1000L, 5000 + i * 1000L, sample(5000 + i * 1000L, 160, 20, .5 + i * .005, 59));
        restarted.stop(w + 175000, 90000);
        store = restarted;
        // Merged: a ride later re-associated with another scooter by a merge restore (same id, START rewritten).
        f.merged = ride(store, "UT5", "To merge", WALL + 3 * DAY, 200);
        // Renamed: a finished ride the rider renames later.
        f.renamed = ride(store, "UT5", "Before rename", WALL + 4 * DAY, 200);
        // Gaps: explicit gaps, a long silent interval and a clock jump backwards (phone reboot).
        w = WALL + 5 * DAY;
        f.gaps = store.start("UT5", "Patchy", w, 50000);
        for (int i = 0; i < 60; i++) store.sample(w + i * 1000L, 50000 + i * 1000L, sample(50000 + i * 1000L, 130, 22, i * .006, 55));
        store.markGap(w + 61000, 111000, "Scooter out of range");
        for (int i = 0; i < 60; i++) store.sample(w + 70000 + i * 1000L, 120000 + i * 1000L, sample(120000 + i * 1000L, 130, 22, .4 + i * .006, 54));
        for (int i = 0; i < 30; i++) store.sample(w + 200000 + i * 1000L, 1000 + i * 1000L, sample(1000 + i * 1000L, 130, 22, .8 + i * .006, 53));
        store.stop(w + 240000, 41000);
        store.flushIndex();
        for (String id : f.all()) check(new File(index, id + ".sum").isFile(), "a finished ride is saved when it stops: " + id);
        // Now the changes that happen after a save: a rename and a merge.
        store.rename(f.renamed, "After rename");
        File journal = new File(rides, f.merged + ".csv"), staging = directory("merge");
        File staged = new File(staging, f.merged + ".csv");
        Files.copy(journal.toPath(), staged.toPath());
        RideStore.reassignJournal(staged, "UT6");
        long before = journal.length(), modified = journal.lastModified();
        Files.move(staged.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING);
        check(journal.setLastModified(modified + 1000), "fixture: the merged journal has a new modified time");
        check(journal.length() == before, "fixture: the merge keeps the length, so only the modified time tells it apart");
        return f;
    }
    private static String ride(RideStore store, String scooter, String label, long wall, int samples) throws Exception {
        String id = store.start(scooter, label, wall, 0);
        for (int i = 0; i < samples; i++) store.sample(wall + i * 1000L, i * 1000L, sample(i * 1000L, 150, 20, i * .005, 80 - i / 100.0));
        store.stop(wall + samples * 1000L, samples * 1000L);
        return id;
    }

    // ---- the correctness proof -------------------------------------------------------------------------------
    private static void savedEntriesEqualAFreshReplay() throws Exception {
        File rides = directory("rides"), index = directory("index");
        Fixtures f = record(rides, index);
        // Cold start 1: the renamed and merged rides changed after their save, so they miss and are replayed once.
        long before = replays();
        RideStore first = new RideStore(rides, true, index);
        check(replays() == before, "a cold start reads no journal");
        first.flushIndex();
        check(first.listCached() == null, "a changed ride keeps Rides waiting for the worker");
        before = replays();
        check(first.list().size() == 6 && replays() - before == 2, "only the renamed and merged rides are replayed");
        first.flushIndex();
        // Cold start 2: everything is saved now.
        before = replays();
        RideStore racing = new RideStore(rides, true, index);
        check(racing.list().size() == 6 && replays() == before, "a worker's list() right after the start replays nothing, seeded or not");
        RideStore cold = new RideStore(rides, true, index);
        check(replays() == before, "the seed runs on the index thread and reads no journal");
        cold.flushIndex();
        List<RideStore.Summary> cached = cold.listCached();
        check(cached != null && cached.size() == 6, "after a restart, Rides draws at once from saved summaries");
        List<RideStore.Summary> listed = cold.list();
        Map<String, List<RideStore.Point>> previews = new LinkedHashMap<>();
        for (String id : f.all()) previews.put(id, cold.trend(id, RideStore.MAX_TREND_POINTS));
        List<RideStore.AnalysisSnapshot> ut5 = cold.analysisSnapshots("UT5", 0), ut6 = cold.analysisSnapshots("UT6", 0);
        check(replays() == before, "a cold start with saved entries does zero replays (list, previews, analysis)");
        check(ut5.size() == 5 && ut6.size() == 1, "analysis snapshots come from disk for every scooter");
        // Reference: a fresh replay of every journal, with no index.
        RideStore fresh = new RideStore(rides);
        Map<String, RideStore.Summary> byId = new LinkedHashMap<>();
        for (RideStore.Summary s : listed) byId.put(s.id, s);
        for (RideStore.Summary s : cached) sameSummary(s, byId.get(s.id), "listCached vs list " + s.id);
        for (String id : f.all()) {
            RideStore.Summary replay = RideStore.inspectJournal(new File(rides, id + ".csv"));
            sameSummary(byId.get(id), replay, "summary " + id);
            samePoints(previews.get(id), fresh.trend(id, RideStore.MAX_TREND_POINTS), "preview " + id);
        }
        List<RideStore.AnalysisSnapshot> all = new ArrayList<>(ut5); all.addAll(ut6);
        for (RideStore.AnalysisSnapshot snapshot : all) {
            sameSummary(snapshot.summary, RideStore.inspectJournal(new File(rides, snapshot.summary.id + ".csv")), "snapshot " + snapshot.summary.id);
            samePoints(snapshot.points, fresh.trend(snapshot.summary.id, RideStore.MAX_TREND_POINTS), "snapshot preview " + snapshot.summary.id);
        }
        // The fixtures really are the kinds they claim to be.
        RideStore.Summary auto = byId.get(f.autoStopped), gaps = byId.get(f.gaps), merged = byId.get(f.merged);
        check(auto.automaticStop && auto.shownElapsedMs < auto.elapsedMs && auto.shownGapCount < auto.gapCount && auto.stopPhoneBluetoothOff,
                "auto-stopped fixture has a shown end before the raw end");
        check(byId.get(f.recovered).recovered, "recovered fixture is recovered");
        check(merged.scooterId.equals("UT6"), "merged fixture is under the new scooter");
        check(byId.get(f.renamed).label.equals("After rename"), "renamed fixture shows the new name");
        check(gaps.gapCount >= 3 && gaps.clockDiscontinuities == 1, "gap fixture has gaps and a clock jump");
        check(byId.get(f.scooter).directionChanges == 1 && byId.get(f.scooter).drawWh != null && previews.get(f.scooter).size() > 300,
                "scooter fixture has a direction change, energy and a thinned preview");
        check(cold.trendCached(f.scooter) == previews.get(f.scooter), "a preview read from disk stays in memory");
        // Size: what service start reads, and the whole entry with its preview.
        long largest = 0, total = 0;
        for (String id : f.all()) { long size = new File(index, id + ".sum").length(); largest = Math.max(largest, size); total += size; }
        check(largest < 32 * 1024, "an entry with a 600-point preview stays small (" + largest + " bytes)");
        System.out.println("RideSummaryStoreTest: 6 entries, " + total + " bytes, largest " + largest);
    }

    private static void keyAndVersionMismatchesMiss() throws Exception {
        File rides = directory("rides"), index = directory("index");
        RideStore store = new RideStore(rides, false, index);
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < 4; k++) ids.add(ride(store, "UT5", "Ride " + k, WALL + k * DAY, 120));
        store.flushIndex();
        File touched = new File(rides, ids.get(0) + ".csv");
        check(touched.setLastModified(touched.lastModified() + 7000), "fixture: a new modified time");
        File longer = new File(rides, ids.get(1) + ".csv");
        long stamp = longer.lastModified();
        Files.write(longer.toPath(), "1,NOTE,x,0,0,,,,,,,,,,ignored\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        check(longer.setLastModified(stamp), "fixture: a new length with the old modified time");
        // An entry from another analysis version: patch its version field (after magic and format).
        patchInt(new File(index, ids.get(2) + ".sum"), 8, RideSummaryStore.ANALYSIS_VERSION + 1);
        long before = replays();
        RideStore cold = new RideStore(rides, true, index);
        check(replays() - before == 1, "the journal ending in a NOTE row is replayed at start, as before");
        before = replays();
        check(cold.list().size() == 4 && replays() - before == 2, "a changed modified time and another version each replay once");
        cold.flushIndex();
        before = replays();
        RideStore again = new RideStore(rides, true, index);
        again.flushIndex();
        check(again.listCached() != null && replays() - before == 1, "the misses were saved again (the NOTE-tailed journal still replays at start)");
        // Version bump and preview size, directly.
        File folder = directory("version");
        RideSummaryStore v1 = new RideSummaryStore(folder, 600, 1);
        v1.write(ids.get(3), 10, 20, encoder(new byte[] {1, 2, 3}, new byte[] {4}));
        v1.flush();
        check(Arrays.equals(v1.readState(ids.get(3), 10, 20), new byte[] {1, 2, 3}), "an entry reads back under its own key");
        check(v1.readState(ids.get(3), 11, 20) == null && v1.readState(ids.get(3), 10, 21) == null, "another length or modified time misses");
        check(v1.readState(ids.get(0), 10, 20) == null, "another ride id misses");
        check(new RideSummaryStore(folder, 600, 2).readState(ids.get(3), 10, 20) == null, "a version bump misses");
        check(new RideSummaryStore(folder, 300, 1).readState(ids.get(3), 10, 20) == null, "another preview size misses");
        check(Arrays.equals(RideSummaryStore.inflate(v1.readPreview(ids.get(3), 10, 20)), new byte[] {4}), "the preview reads back");
    }

    private static void damagedEntriesMissAndAreRebuilt() throws Exception {
        File rides = directory("rides"), index = directory("index");
        RideStore store = new RideStore(rides, false, index);
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < 6; k++) ids.add(ride(store, "UT5", "Ride " + k, WALL + k * DAY, 700));
        store.flushIndex();
        File flipped = new File(index, ids.get(0) + ".sum");
        try (RandomAccessFile file = new RandomAccessFile(flipped, "rw")) { file.seek(90); int b = file.read(); file.seek(90); file.write(b ^ 0x40); }
        truncate(new File(index, ids.get(1) + ".sum"), 40);
        Files.write(new File(index, ids.get(2) + ".sum").toPath(), new byte[0]);
        Files.write(new File(index, ids.get(3) + ".sum").toPath(), "not a summary at all".getBytes(StandardCharsets.UTF_8));
        // Cut inside the preview: the summary still reads, the preview misses.
        File cut = new File(index, ids.get(4) + ".sum");
        truncate(cut, cut.length() - 50);
        long before = replays();
        RideStore cold = new RideStore(rides, true, index);
        check(replays() == before, "damaged entries never crash a cold start");
        cold.flushIndex();
        check(cold.listCached() == null, "damaged summaries are not trusted");
        before = replays();
        check(cold.list().size() == 6 && replays() - before == 4, "a flipped byte, a truncated, an empty and a foreign entry each replay once");
        before = replays();
        List<RideStore.Point> preview = cold.trend(ids.get(4), RideStore.MAX_TREND_POINTS);
        check(replays() - before == 1, "a cut preview replays");
        samePoints(preview, new RideStore(rides).trend(ids.get(4), RideStore.MAX_TREND_POINTS), "rebuilt preview");
        cold.flushIndex();
        before = replays();
        RideStore rebuilt = new RideStore(rides, true, index);
        rebuilt.flushIndex();
        check(rebuilt.listCached() != null, "every damaged entry was rebuilt");
        for (String id : ids) rebuilt.trend(id, RideStore.MAX_TREND_POINTS);
        check(replays() == before, "rebuilt entries serve the list and previews without a replay");
        for (String id : ids) sameSummary(rebuilt.get(id), RideStore.inspectJournal(new File(rides, id + ".csv")), "rebuilt " + id);
    }

    private static void unfinishedJournalsAreNeverServedFromDisk() throws Exception {
        File rides = directory("rides"), index = directory("index");
        RideStore store = new RideStore(rides, false, index);
        String id = ride(store, "UT5", "Crashed", WALL, 300);
        store.flushIndex();
        // The phone dies before STOP reaches the disk: drop the STOP row, then make the saved key match the journal.
        File journal = new File(rides, id + ".csv");
        String text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
        String unfinished = text.substring(0, text.lastIndexOf("1,STOP,"));
        Files.write(journal.toPath(), unfinished.getBytes(StandardCharsets.UTF_8));
        File entry = new File(index, id + ".sum");
        int key = 16 + 2 + id.length();
        patchLong(entry, key, journal.length()); patchLong(entry, key + 8, journal.lastModified());
        RideSummaryStore direct = new RideSummaryStore(index, RideStore.MAX_TREND_POINTS);
        check(direct.readState(id, journal.length(), journal.lastModified()) != null, "fixture: the planted entry matches the unfinished journal");
        RideStore cold = new RideStore(rides, true, index);
        check(id.equals(cold.activeId()), "the unfinished journal is recovered as the active ride");
        RideStore.Summary summary = cold.get(id);
        check(summary.active && summary.recovered, "it is served from the recovered recording, not the saved finished summary");
        check(cold.stop(WALL + 400000, 400000) != null, "the recovered ride stops normally");
        cold.flushIndex();
        RideStore after = new RideStore(rides, true, index);
        after.flushIndex();
        sameSummary(after.listCached().get(0), RideStore.inspectJournal(journal), "the re-finished ride is saved again");
    }

    /** Interval sign accounting (RideStore.Accumulator) reads back identically from the journal and the saved summary. */
    private static void signTransitionsSurviveTheCache() throws Exception {
        File rides = directory("rides"), index = directory("index");
        RideStore store = new RideStore(rides, false, index);
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        double[][][] cases = {
            {{1, 254, 254, 254}, {-100, -100, -100, -100}},
            {{254, 254, 254, 1, 1}, {100, 100, 100, 100, 100}},
            {{254, 254, 254, 1, 1}, {-100, 100, 100, 100, 100}},
            {{0, 254, 254, 0, 0}, {100, 100, -100, 100, 100}},
            {{254, 254, 254, 254}, {100, 100, 100, 100}},
            {{Double.NaN, Double.NaN, Double.NaN}, {100, -100, 100}},
        };
        Map<String, RideStore.Summary> live = new LinkedHashMap<>();
        for (int c = 0; c < cases.length; c++) {
            long w = WALL + c * DAY;
            String id = store.start("UT5", "Sign " + c, w, 0);
            for (int i = 0; i < cases[c][0].length; i++) {
                Map<String, RideStore.Sample> m = sample(i * 1000L, cases[c][1][i], 20, i * .01, 80);
                if (!Double.isNaN(cases[c][0][i])) m.put("current_sign_state", new RideStore.Sample(cases[c][0][i], "raw", "", 0x79, i * 1000L));
                store.sample(w + i * 1000L, i * 1000L, m);
            }
            live.put(id, store.stop(w + cases[c][0].length * 1000L, cases[c][0].length * 1000L));
        }
        store.flushIndex();
        long before = replays();
        RideStore cold = new RideStore(rides, true, index);
        cold.flushIndex();
        check(replays() == before, "a cold start reads no journal for these rides");
        for (RideStore.Summary cached : cold.listCached()) {
            sameSummary(cached, live.get(cached.id), "saved summary vs live " + cached.label);
            sameSummary(cached, RideStore.inspectJournal(new File(rides, cached.id + ".csv")), "saved summary vs journal " + cached.label);
        }
        RideStore.Summary stale = live.values().iterator().next();
        check(!stale.directionConfirmed && stale.confirmedDirectionCoverageMs == 0 && stale.regeneratedWh == null, "the first case really is the stale-sign ride");
    }

    private static void pruneAndDelete() throws Exception {
        File rides = directory("rides"), index = directory("index");
        RideStore store = new RideStore(rides, false, index);
        String kept = ride(store, "UT5", "Kept", WALL, 50), deleted = ride(store, "UT5", "Deleted", WALL + DAY, 50),
                vanished = ride(store, "UT5", "Removed elsewhere", WALL + 2 * DAY, 50);
        store.flushIndex();
        check(store.delete(deleted), "fixture: delete a ride");
        store.flushIndex();
        check(!new File(index, deleted + ".sum").exists(), "deleting a ride deletes its saved summary");
        check(new File(rides, vanished + ".csv").delete(), "fixture: a journal removed outside this store (a restore)");
        String orphan = java.util.UUID.randomUUID().toString();
        Files.write(new File(index, orphan + ".sum").toPath(), new byte[] {1});
        Files.write(new File(index, kept + ".sum.tmp").toPath(), new byte[] {2});
        RideStore cold = new RideStore(rides, true, index);
        cold.flushIndex();
        String[] left = index.list();
        check(left != null && Arrays.asList(left).equals(Collections.singletonList(kept + ".sum")),
                "a cold start prunes entries without a journal and leftover temporary files: " + Arrays.toString(left));
        check(cold.listCached() != null && cold.listCached().size() == 1, "the kept ride is still served");
        // A missing index folder is created on the first save; an unusable one only costs replays.
        File missing = new File(directory("absent"), "rides-index");
        RideStore fresh = new RideStore(rides, true, missing);
        fresh.list(); fresh.flushIndex();
        check(new File(missing, kept + ".sum").isFile(), "the index folder is created on first save");
        File blocked = new File(directory("blocked"), "rides-index");
        Files.write(blocked.toPath(), new byte[] {0});
        RideStore unusable = new RideStore(rides, true, blocked);
        check(unusable.list().size() == 1, "an index path that is a file never breaks the ride list");
        unusable.flushIndex();
    }

    private static void neverInABackup() throws Exception {
        check(BackupRegistry.root("no_backup/rides-index") == null && BackupRegistry.root("no_backup/rides-index/x.sum") == null,
                "the index is a registered non-backup root");
        check(!Arrays.asList(AppDataArchive.DIRECTORIES).contains("rides-index"), "unified backups never collect it");
        // A ride backup of a data tree that has the index beside it: only journals travel.
        File root = directory("app");
        File files = new File(root, "files"), work = new File(root, "no_backup/ride-transfer");
        check(files.mkdirs() && work.mkdirs(), "fixture: app folders");
        File index = new File(root, "no_backup/rides-index");
        RideStore store = new RideStore(new File(files, "rides"), false, index);
        String id = ride(store, "UT5", "Backed up", WALL, 50);
        store.flushIndex();
        check(new File(index, id + ".sum").isFile(), "fixture: the index has an entry");
        RideArchive.Storage storage = new RideArchive.Storage() {
            public File files() { return files; }
            public File work() { return work; }
            public void sync(File directory) throws java.io.IOException { AppDataArchive.syncDirectory(directory); }
            public void copied(String path) { }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RideArchive.write(storage, out, Collections.singletonMap("UT5", "Synthetic scooter"));
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) names.add(e.getName());
        }
        boolean journal = false, summary = false;
        for (String name : names) { journal |= name.endsWith(id + ".csv"); summary |= name.contains("rides-index") || name.endsWith(".sum"); }
        check(journal && !summary, "a ride backup has the journal and no saved summary: " + names);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------
    private static long replays() { return RideStore.replays.get(); }
    private static RideSummaryStore.Encoder encoder(byte[] state, byte[] preview) {
        return new RideSummaryStore.Encoder() { public byte[] state() { return state; } public byte[] preview() { return preview; } };
    }
    /** Every field of Summary, public or not, compared by value. */
    private static void sameSummary(RideStore.Summary a, RideStore.Summary b, String what) throws Exception {
        check(a != null && b != null, what + ": both present");
        int compared = 0;
        for (Field field : RideStore.Summary.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            check(Objects.equals(field.get(a), field.get(b)), what + ": " + field.getName() + " " + field.get(a) + " vs " + field.get(b));
            compared++;
        }
        check(compared >= 70 && a.toJson().equals(b.toJson()), what + ": all " + compared + " fields and the JSON match");
    }
    private static void samePoints(List<RideStore.Point> a, List<RideStore.Point> b, String what) throws Exception {
        check(a != null && b != null && a.size() == b.size() && !a.isEmpty(), what + ": same number of points");
        for (int i = 0; i < a.size(); i++)
            for (Field field : RideStore.Point.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                check(Objects.equals(field.get(a.get(i)), field.get(b.get(i))), what + ": point " + i + " " + field.getName());
            }
    }
    private static void patchInt(File file, long offset, int value) throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.seek(offset); out.writeInt(value); }
    }
    private static void patchLong(File file, long offset, long value) throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.seek(offset); out.writeLong(value); }
    }
    private static void truncate(File file, long length) throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(length); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
