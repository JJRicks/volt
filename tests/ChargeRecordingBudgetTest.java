package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Charge recordings as a compressed rotating buffer (owner, Nov 19 2026: "bump the limit to say like, 100-200MB
 * compressed, and auto clear old ones in a rotating buffer instead"; "auto and manual recordings could be classed the
 * same in that way. rotate away."). The budget and its rotation order, the per-scooter-and-charger balance, automatic
 * and manual alike, plain and compressed reading, an active journal's recovery, the backup round trip in plain form,
 * an old plain file read in place and then compressed, and the learner's cap. Synthesized data only.
 */
public final class ChargeRecordingBudgetTest {
    static int checks;
    static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L, HOUR = 3_600_000L;
    static final ChargeSessionStore.Charger STOCK = ChargeSessionStore.Charger.STOCK, FAST = ChargeSessionStore.Charger.HIGH_SPEED;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        compressedReadsAsPlain();
        oldPlainFileMigrates();
        countRotationOrder();
        budgetRotation();
        bucketBalance();
        automaticAndManualAlike();
        activeJournalRecovery();
        corruptCompressedFile();
        learnerCap();
        summaries();
        backupRoundTrip();
        System.out.println("ChargeRecordingBudget: " + checks + " checks passed");
    }

    static File folder() throws IOException { return Files.createTempDirectory("charge-budget-").toFile(); }
    /** ChargeAutoRecordPolicy.keep's rule (ten minutes and five points), here without the policy's own dependencies. */
    static final ChargeSessionStore.AutomaticRules KEEP = (ranMs, gained) -> ranMs >= 600_000L && gained >= 5;
    static ChargeSessionStore store(File folder, int max, long budget) throws IOException { return new ChargeSessionStore(folder, KEEP, max, budget); }
    static ChargeSessionStore.Value value(double v, long mono) { return new ChargeSessionStore.Value(v, mono, 0x72); }
    /** A qualified charge from 40% to 60% at one point a minute, started at `wall` (each recording its own times). */
    static String charge(ChargeSessionStore store, String profile, ChargeSessionStore.Charger charger, long wall, boolean automatic) throws IOException {
        return charge(store, profile, charger, wall, automatic, 60_000L);
    }
    /** perPercent: how long each battery point takes. */
    static String charge(ChargeSessionStore store, String profile, ChargeSessionStore.Charger charger, long wall, boolean automatic, long perPercent) throws IOException {
        int from = 40, to = 60; double amps = 2;
        long mono = MONO + (wall - WALL);
        if (automatic) store.startAutomatic(profile, charger, ChargeSessionStore.LabelSource.CHOSEN, wall, mono); else store.start(profile, charger, wall, mono);
        String id = activeId(store);
        for (long t = 0; t <= (to - from) * perPercent; t += 10_000) {
            long at = mono + t; double soc = from + t / perPercent;
            store.sample(new ChargeSessionStore.Frame(wall + t, at, value(1, at), value(soc, at), value(52, at), value(-amps, at), value(23, at)));
        }
        store.stop(ChargeSessionStore.Stop.STOPPED);
        return id;
    }
    /** A recording too short to teach anything: one sample. */
    static String failed(ChargeSessionStore store, String profile, long wall) throws IOException {
        long mono = MONO + (wall - WALL);
        store.start(profile, STOCK, wall, mono); String id = activeId(store);
        store.sample(new ChargeSessionStore.Frame(wall, mono, value(1, mono), value(50, mono), value(52, mono), value(-2, mono), value(23, mono)));
        store.stop(ChargeSessionStore.Stop.ERROR); return id;
    }
    static String activeId(ChargeSessionStore store) throws IOException {
        // The running journal is the one .active file of its folder.
        File folder = folderOf(store);
        for (String name : folder.list()) if (name.endsWith(".active")) return name.substring(0, 36);
        throw new IllegalStateException("No running journal");
    }
    static final Map<ChargeSessionStore, File> FOLDERS = new IdentityHashMap<>();
    static File folderOf(ChargeSessionStore store) { return FOLDERS.get(store); }
    static ChargeSessionStore open(File folder, int max, long budget) throws IOException { ChargeSessionStore s = store(folder, max, budget); FOLDERS.put(s, folder); return s; }
    /** Saved ids, oldest first, as the store orders them (modification time, then name). */
    static List<String> saved(File folder) {
        List<File> files = new ArrayList<>(); for (File f : folder.listFiles()) if (f.getName().endsWith(".csv")) files.add(f);
        files.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        List<String> ids = new ArrayList<>(); for (File f : files) ids.add(f.getName().substring(0, 36)); return ids;
    }
    /** Dates a recording, as a restore does, so the order under test never depends on the clock's resolution. */
    static void date(File folder, String id, long wall) { check(new File(folder, id + ".csv").setLastModified(wall), "Fixture dated"); }
    static boolean gz(File file) throws IOException { try (InputStream in = new FileInputStream(file)) { return in.read() == 0x1f && in.read() == 0x8b; } }
    static String export(ChargeSessionStore store, String profile) throws IOException { StringWriter out = new StringWriter(); store.exportJson(profile, out); return out.toString(); }
    static String describe(List<ChargeSessionStore.HistorySession> list) {
        StringBuilder out = new StringBuilder();
        for (ChargeSessionStore.HistorySession h : list) {
            out.append(h.id).append(' ').append(h.profile).append(' ').append(h.charger).append(' ').append(h.stop).append(' ').append(h.startedWall).append(' ')
                    .append(h.observedCompleteMono).append(' ').append(h.pausedMs).append(' ').append(h.pauses).append(' ').append(h.chargerPausedMs).append(' ')
                    .append(h.chargerPauses).append(' ').append(h.firstPercent).append(' ').append(h.lastPercent).append(' ').append(h.lastSampleMono).append(' ').append(h.points.size()).append('\n');
            for (ChargeSessionStore.HistoryPoint p : h.points) out.append(p.wall).append(',').append(p.mono).append(',').append(p.batteryPercent).append(',').append(p.power).append(',').append(p.temperature).append(',').append(p.gap).append(';');
        }
        return out.toString();
    }

    /** One recording read plain, then after the worker compressed it: the same list, export, curves and modification time. */
    static void compressedReadsAsPlain() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 50, ChargeSessionStore.BUDGET_BYTES);
        String a = charge(store, "one", STOCK, WALL, false), b = charge(store, "one", STOCK, WALL + HOUR, true);
        date(f, a, WALL); date(f, b, WALL + HOUR);
        File fileA = new File(f, a + ".csv");
        byte[] plain = Files.readAllBytes(fileA.toPath());
        check(!gz(fileA) && new String(plain, StandardCharsets.UTF_8).startsWith("charge-v2,one,STOCK,"), "A just-saved recording is plain");
        String listed = describe(store.recordings("one")), exported = export(store, "one");
        ChargeSessionStore.Snapshot before = store.snapshot();
        check(store.maintain() == 0, "Nothing over the limits: nothing removed");
        check(gz(fileA) && gz(new File(f, b + ".csv")), "The worker compressed both, under their own names");
        check(fileA.lastModified() == WALL, "Compression keeps the modification time rotation goes by");
        check(fileA.length() * 3 < plain.length, "Compressed several times over: " + plain.length + " -> " + fileA.length());
        try (InputStream in = new java.util.zip.GZIPInputStream(new FileInputStream(fileA))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            check(Arrays.equals(out.toByteArray(), plain), "Any gzip reader gets the journal back byte for byte");
        }
        check(ChargeSessionStore.Facts.parse(ChargeSessionStore.gzipComment(fileA)) != null, "Its facts are in the gzip header");
        ChargeSessionStore reopened = open(f, 50, ChargeSessionStore.BUDGET_BYTES);
        check(describe(reopened.recordings("one")).equals(listed), "The list reads the same from compressed files");
        check(export(reopened, "one").equals(exported), "The export is the same, plain JSON");
        ChargeSessionStore.Snapshot after = reopened.snapshot();
        ChargeSessionStore.Curve c1 = before.curves.get(ChargeSessionStore.key("one", STOCK)), c2 = after.curves.get(ChargeSessionStore.key("one", STOCK));
        check(c1 != null && c2 != null && c1.qualifiedSessions == 2 && c2.qualifiedSessions == 2 && c1.estimate(45, 55).minutes == c2.estimate(45, 55).minutes,
                "The learned curve is the same");
        check(after.sessions == 2 && after.bytes < before.bytes, "Stored recordings counted the same, smaller on disk");
        byte[] compressedBytes = Files.readAllBytes(fileA.toPath());
        check(reopened.maintain() == 0 && Arrays.equals(Files.readAllBytes(fileA.toPath()), compressedBytes), "A second run leaves compressed files alone");
        ChargeSessionStore.Journal journal = ChargeSessionStore.inspectJournal(fileA);
        check(journal.profile.equals("one") && journal.startedWall == WALL && journal.finished, "A compressed journal inspects from its facts");
        check(Arrays.equals(ChargeSessionStore.digest(fileA), java.security.MessageDigest.getInstance("SHA-256").digest(plain)), "Its digest is the plain text's");
        check(ChargeSessionStore.plainLength(fileA) == plain.length && ChargeSessionStore.plainLengthHint(fileA) == plain.length, "Its plain length, exact and from the header");
        String head; try (BufferedReader in = new BufferedReader(new InputStreamReader(ChargeSessionStore.open(fileA), StandardCharsets.UTF_8))) { head = in.readLine(); }
        check(head.startsWith("charge-v2,one,STOCK,") && DiagnosticsBundle.chargeStart(fileA) == WALL, "Diagnostics read its start through the compression");
        Map<String, Object> last = DiagnosticsBundle.lastCharge(f);
        check(last != null && "stopped".equals(last.get("end")) && Long.valueOf(20 * 60).equals(last.get("duration_s")), "Diagnostics read the newest one's end and length: " + last);
    }

    /** An older Volt's plain file (format 1, no estimates) reads in place, then compresses and reads the same. */
    static void oldPlainFileMigrates() throws Exception {
        File f = folder();
        String id = UUID.randomUUID().toString(); File legacy = new File(f, id + ".csv");
        StringBuilder text = new StringBuilder("charge-v1,one,STOCK," + WALL + "," + MONO + "\n");
        for (int i = 0; i <= 120; i++) { long t = i * 10_000L; text.append("s,").append(WALL + t).append(',').append(MONO + t).append(',').append(40 + i / 6).append(".0,52.0,-2.0,-104.0,,0\n"); }
        text.append("end,STOPPED\n");
        Files.write(legacy.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        ChargeSessionStore store = open(f, 50, ChargeSessionStore.BUDGET_BYTES);
        String before = describe(store.recordings("one")); int learned = store.snapshot().curves.get(ChargeSessionStore.key("one", STOCK)).qualifiedSessions;
        check(store.recordings("one").size() == 1 && learned == 1 && !gz(legacy), "Read in place, plain");
        store.maintain();
        check(gz(legacy) && describe(store.recordings("one")).equals(before) && store.snapshot().curves.get(ChargeSessionStore.key("one", STOCK)).qualifiedSessions == 1,
                "Compressed by the worker, read the same");
    }

    /** Over the count, the oldest goes at the stop that crosses it; nothing at a start. */
    static void countRotationOrder() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 5, ChargeSessionStore.BUDGET_BYTES);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) { ids.add(charge(store, "one", STOCK, WALL + i * HOUR, false)); date(f, ids.get(i), WALL + i * HOUR); }
        check(saved(f).equals(ids), "Fixture: five, oldest first");
        store.start("one", STOCK, WALL + 9 * HOUR, MONO + 9 * HOUR);
        check(saved(f).equals(ids), "A start deletes nothing; the running journal is one over");
        store.stop(ChargeSessionStore.Stop.STOPPED);
        List<String> after = saved(f);
        check(after.size() == 5 && !after.contains(ids.get(0)) && after.containsAll(ids.subList(1, 5)), "The stop rotated the oldest away: " + after);
    }

    /** Over the budget in bytes (measured on the compressed files), the worker rotates the oldest away. */
    static void budgetRotation() throws Exception {
        File probe = folder(); ChargeSessionStore sizing = open(probe, 50, ChargeSessionStore.BUDGET_BYTES);
        charge(sizing, "one", STOCK, WALL, false); sizing.maintain();
        long one = probe.listFiles()[0].length();
        File f = folder(); ChargeSessionStore store = open(f, 50, one * 4 + one / 2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) { ids.add(charge(store, "one", STOCK, WALL + i * HOUR, false)); date(f, ids.get(i), WALL + i * HOUR); }
        check(store.maintain() == 0 && saved(f).size() == 4, "Four compressed fit the budget");
        ids.add(charge(store, "one", STOCK, WALL + 4 * HOUR, false)); date(f, ids.get(4), WALL + 4 * HOUR);
        check(saved(f).size() == 5, "The stop keeps the new one while it is plain (the count is not reached)");
        check(store.maintain() == 1, "Compressed, five are over the budget: one goes");
        long bytes = 0; for (File file : f.listFiles()) bytes += file.length();
        check(saved(f).equals(ids.subList(1, 5)) && bytes <= one * 4 + one / 2, "The oldest went, and the folder is within its budget: " + bytes);
        check(ChargeSessionStore.BUDGET_BYTES == 150L * 1024 * 1024 && ChargeSessionStore.MAX_RECORDINGS == 1000 && ChargeSessionStore.LEARN_SESSIONS == 20, "The shipped limits");
    }

    /** Failed ones go first; then the largest scooter-and-charger bucket's oldest; a minority curve keeps its two. */
    static void bucketBalance() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 8, ChargeSessionStore.BUDGET_BYTES);
        long at = WALL; List<String> stock = new ArrayList<>(), fast = new ArrayList<>(), other = new ArrayList<>();
        for (int i = 0; i < 2; i++) { String id = charge(store, "one", FAST, at, false); date(f, id, at += HOUR); fast.add(id); }
        for (int i = 0; i < 2; i++) { String id = charge(store, "two", STOCK, at, false); date(f, id, at += HOUR); other.add(id); }
        String broken = failed(store, "one", at); date(f, broken, at += HOUR);
        for (int i = 0; i < 3; i++) { String id = charge(store, "one", STOCK, at, true); date(f, id, at += HOUR); stock.add(id); }
        check(saved(f).size() == 8, "Fixture: full");
        String next = charge(store, "one", STOCK, at, false); date(f, next, at += HOUR); stock.add(next);
        check(!saved(f).contains(broken) && saved(f).size() == 8, "A failed recording goes before any that teaches a curve, though it is newer");
        for (int i = 0; i < 6; i++) { String id = charge(store, "one", STOCK, at, i % 2 == 0); date(f, id, at += HOUR); stock.add(id); }
        List<String> left = saved(f);
        check(left.containsAll(fast) && left.containsAll(other), "Frequent stock charges never evict the minority high-speed curve or the other scooter's");
        check(left.size() == 8 && left.containsAll(stock.subList(stock.size() - 4, stock.size())) && !left.contains(stock.get(0)), "The stock bucket lost its oldest: " + left);
        ChargeSessionStore.Snapshot snapshot = store.snapshot();
        check(snapshot.curves.get(ChargeSessionStore.key("one", FAST)).qualifiedSessions == 2 && snapshot.curves.get(ChargeSessionStore.key("two", STOCK)).qualifiedSessions == 2,
                "Both minority curves still learn from their two");
    }

    /** Automatic and manual recordings rotate alike; an automatic start never finds the folder full. */
    static void automaticAndManualAlike() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 4, ChargeSessionStore.BUDGET_BYTES);
        List<String> manual = new ArrayList<>();
        for (int i = 0; i < 4; i++) { manual.add(charge(store, "one", STOCK, WALL + i * HOUR, false)); date(f, manual.get(i), WALL + i * HOUR); }
        store.startAutomatic("one", STOCK, ChargeSessionStore.LabelSource.CHOSEN, WALL + 5 * HOUR, MONO + 5 * HOUR);
        check(store.active() && saved(f).equals(manual), "All four manual: an automatic recording starts all the same, deleting nothing");
        store.stop(ChargeSessionStore.Stop.STOPPED);
        check(saved(f).equals(manual), "Too short to keep: discarded, and it cost nothing");
        String automatic = charge(store, "one", STOCK, WALL + 6 * HOUR, true); date(f, automatic, WALL + 6 * HOUR);
        check(!saved(f).contains(manual.get(0)) && saved(f).contains(automatic) && saved(f).size() == 4, "Kept: the oldest went, though it was started by hand");
        String manualAgain = charge(store, "one", STOCK, WALL + 7 * HOUR, false); date(f, manualAgain, WALL + 7 * HOUR);
        check(!saved(f).contains(manual.get(1)) && saved(f).contains(automatic) && saved(f).size() == 4, "A manual one rotates the oldest the same way");
        for (int i = 0; i < 3; i++) { String id = charge(store, "one", STOCK, WALL + (8 + i) * HOUR, false); date(f, id, WALL + (8 + i) * HOUR); }
        check(!saved(f).contains(automatic), "And an automatic one is rotated away in its turn, oldest first");
    }

    /** A process death mid-recording: the next start finishes it as interrupted, plain; the worker compresses it later. */
    static void activeJournalRecovery() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 50, ChargeSessionStore.BUDGET_BYTES);
        store.start("one", STOCK, WALL, MONO);
        for (long t = 0; t <= 600_000; t += 10_000) store.sample(new ChargeSessionStore.Frame(WALL + t, MONO + t, value(1, MONO + t), value(40 + t / 60_000, MONO + t), value(52, MONO + t), value(-2, MONO + t), value(23, MONO + t)));
        File running = null; for (File file : f.listFiles()) if (file.getName().endsWith(".active")) running = file;
        check(running != null && !gz(running), "The running journal is plain");
        check(store.maintain() == 0 && !gz(running) && running.exists(), "The worker never touches the running journal");
        File stale = new File(f, "44444444-4444-4444-8444-444444444444.csv.repair");
        Files.write(stale.toPath(), new byte[]{0x1f, (byte) 0x8b, 8});
        ChargeSessionStore after = open(f, 50, ChargeSessionStore.BUDGET_BYTES);
        File recovered = new File(f, running.getName().replace(".active", ".csv"));
        check(!stale.exists(), "A compression a process death cut short leaves nothing at the next start");
        check(!after.active() && recovered.exists() && !gz(recovered), "Recovered at start, still plain");
        check(after.recordings("one").get(0).stop == ChargeSessionStore.Stop.INTERRUPTED && after.recordings("one").get(0).points.size() == 61, "Finished as interrupted, every sample kept");
        after.maintain();
        check(gz(recovered) && after.recordings("one").get(0).stop == ChargeSessionStore.Stop.INTERRUPTED && after.recordings("one").get(0).points.size() == 61, "Compressed later, it reads the same");
        check(after.snapshot().curves.isEmpty(), "And still teaches no curve");
    }

    /** A compressed file that is corrupt is no recording: not listed, never breaks a read of the rest, and goes first. */
    static void corruptCompressedFile() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 3, ChargeSessionStore.BUDGET_BYTES);
        String good = charge(store, "one", STOCK, WALL, false); store.maintain();
        File bad = new File(f, UUID.randomUUID() + ".csv");
        byte[] bytes = Files.readAllBytes(new File(f, good + ".csv").toPath());
        Files.write(bad.toPath(), Arrays.copyOf(bytes, bytes.length / 2));
        bad.setLastModified(System.currentTimeMillis() + HOUR);
        File headless = new File(f, UUID.randomUUID() + ".csv");
        Files.write(headless.toPath(), Arrays.copyOf(bytes, 12));
        headless.setLastModified(System.currentTimeMillis() + HOUR);
        ChargeSessionStore reopened = open(f, 4, ChargeSessionStore.BUDGET_BYTES);
        // Its facts may survive in the header; reading it in full finds it out, and from then on it is no recording.
        check(reopened.recordings("one").size() == 1, "A cut-short compressed file is not listed and breaks nothing");
        check(reopened.snapshot().sessions == 1, "Read once, it is counted as no recording");
        check(ChargeSessionStore.plainLength(bad) == -1 && ChargeSessionStore.plainLength(headless) == -1, "Neither has a plain length");
        String newer = charge(reopened, "one", STOCK, WALL + HOUR, false); date(f, newer, WALL + HOUR);
        String newest = charge(reopened, "one", STOCK, WALL + 2 * HOUR, false); date(f, newest, WALL + 2 * HOUR);
        String last = charge(reopened, "one", STOCK, WALL + 3 * HOUR, false); date(f, last, WALL + 3 * HOUR);
        check(!bad.exists() && !headless.exists() && saved(f).size() == 4 && saved(f).contains(good), "Over the count, both went first, though they were dated newest");
    }

    /** The learner reads at most the newest LEARN_SESSIONS per scooter and charger. */
    static void learnerCap() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 100, ChargeSessionStore.BUDGET_BYTES);
        // The five oldest took two minutes a point; the newest twenty one minute.
        for (int i = 0; i < 25; i++) { String id = charge(store, "one", STOCK, WALL + i * HOUR, false, i < 5 ? 120_000L : 60_000L); date(f, id, WALL + i * HOUR); }
        ChargeSessionStore.Snapshot snapshot = store.snapshot();
        ChargeSessionStore.Curve curve = snapshot.curves.get(ChargeSessionStore.key("one", STOCK));
        check(snapshot.sessions == 25 && curve.qualifiedSessions == ChargeSessionStore.LEARN_SESSIONS, "Twenty-five stored, twenty learned from: " + curve.qualifiedSessions);
        check(snapshot.qualifiedRecordings("one", STOCK) == 25 && snapshot.qualifiedRecordings("one", FAST) == 0, "The charger guess still counts all twenty-five");
        check(Math.abs(curve.estimate(45, 55).minutes - 10) < 1e-9, "They are the newest twenty (with the five older ones it would be 12 minutes): " + curve.estimate(45, 55).minutes);
    }

    /** The list's summaries carry what its rows show, from the facts alone. */
    static void summaries() throws Exception {
        File f = folder(); ChargeSessionStore store = open(f, 50, ChargeSessionStore.BUDGET_BYTES);
        for (int i = 0; i < 4; i++) { String id = charge(store, "one", i % 2 == 0 ? STOCK : FAST, WALL + i * HOUR, i == 3); date(f, id, WALL + i * HOUR); }
        charge(store, "two", STOCK, WALL + 9 * HOUR, false);
        store.maintain();
        List<ChargeSessionStore.HistorySession> full = store.recordings("one"), mixed = store.history("one", 1);
        check(full.size() == 4 && mixed.size() == 4 && !mixed.get(0).light && mixed.get(1).light && mixed.get(3).light && mixed.get(1).points.isEmpty(), "Newest one in full, the rest summaries");
        for (int i = 0; i < 4; i++) {
            ChargeSessionStore.HistorySession a = full.get(i), b = mixed.get(i);
            check(a.id.equals(b.id) && a.charger == b.charger && a.startedWall == b.startedWall && a.stop == b.stop && a.firstPercent == b.firstPercent && a.lastPercent == b.lastPercent
                    && a.lastSampleMono == b.lastSampleMono && Objects.equals(a.observedCompleteMono, b.observedCompleteMono) && a.automatic == b.automatic && a.labelSource == b.labelSource,
                    "A summary has its row's fields: " + a.id);
            ChargeHistoryBars.Charge one = ChargeHistoryBars.of(a), two = ChargeHistoryBars.of(b);
            check(one.from == two.from && one.to == two.to && one.ms == two.ms && one.from == 40 && one.to == 60, "The HISTORY card's bar from a summary");
        }
        ChargeSessionStore.HistorySession opened = store.recording("one", mixed.get(2).id);
        check(opened != null && !opened.light && describe(Collections.singletonList(opened)).equals(describe(Collections.singletonList(full.get(2)))), "Opening a summary reads it in full");
        check(store.recording("two", mixed.get(2).id) == null && store.recording("one", "not-an-id") == null, "Only this scooter's, only a recording's id");
    }

    /** Backups and exports carry the plain journal; a restore writes it plain, and the worker compresses it again. */
    static void backupRoundTrip() throws Exception {
        File root = folder();
        BackupTestStore phone = BackupArchiveTest.phone(new File(root, "phone"));
        String profile = BackupArchiveTest.C;
        File live = new File(phone.files(), "charge-recordings");
        for (File old : live.listFiles()) check(old.delete(), "Fixture: only this test's charge");
        ChargeSessionStore store = open(live, 50, ChargeSessionStore.BUDGET_BYTES);
        String id = charge(store, profile, STOCK, WALL, false);
        byte[] plain = Files.readAllBytes(new File(live, id + ".csv").toPath());
        store.maintain();
        check(gz(new File(live, id + ".csv")), "Fixture: compressed on the phone");
        long[] inventory = BackupArchive.inventory(phone).get(BackupRegistry.CHARGES);
        check(inventory[0] == 1 && inventory[1] == plain.length, "The Back up list counts it at its plain size");
        byte[] backup = phone.export(Collections.singleton(BackupRegistry.CHARGES));
        BackupTestStore fresh = BackupArchiveTest.phone(new File(root, "fresh"));
        for (File old : new File(fresh.files(), "charge-recordings").listFiles()) check(old.delete(), "Fixture: an empty charge folder");
        BackupArchive.Prepared prepared = fresh.stage(backup);
        File staged = new File(prepared.data, "files/charge-recordings/" + id + ".csv");
        check(staged.isFile() && Arrays.equals(Files.readAllBytes(staged.toPath()), plain), "The backup holds the plain journal, byte for byte, as an older Volt expects");
        check(prepared.charges.size() == 1 && prepared.charges.get(0).readable, "It stages as a readable charge");
        BackupArchive.discard(fresh, prepared);
        // The same recording, compressed here and plain in the backup, is already here.
        BackupArchive.Plan again = phone.plan(phone.stage(backup), Collections.singleton(BackupRegistry.CHARGES), false, profile);
        check(again.additions.isEmpty() && again.lines.contains("Charges: 0 new · 1 already here · 0 kept here"), "A compressed copy matches its plain backup: " + again.lines);
        // Restored on another phone: written back plain, read, then compressed by the worker.
        BackupArchive.Plan restore = fresh.plan(fresh.stage(backup), Collections.singleton(BackupRegistry.CHARGES), false, profile);
        check(restore.lines.contains("Charges: 1 new · 0 already here · 0 kept here"), "One new charge: " + restore.lines);
        BackupTransaction.apply(fresh, restore);
        File restored = new File(fresh.files(), "charge-recordings/" + id + ".csv");
        check(restored.isFile() && !gz(restored) && Arrays.equals(Files.readAllBytes(restored.toPath()), plain), "Restored plain");
        ChargeSessionStore back = open(new File(fresh.files(), "charge-recordings"), 50, ChargeSessionStore.BUDGET_BYTES);
        String listed = describe(back.recordings(profile));
        back.maintain();
        check(gz(restored) && describe(back.recordings(profile)).equals(listed) && back.recordings(profile).size() == 1, "And compressed afterwards, it reads the same");
        byte[] second = fresh.export(Collections.singleton(BackupRegistry.CHARGES));
        BackupArchive.Prepared round = new BackupTestStore(new File(root, "third")).stage(second);
        check(Arrays.equals(Files.readAllBytes(new File(round.data, "files/charge-recordings/" + id + ".csv").toPath()), plain), "Backed up again: still the plain journal");
    }
}
