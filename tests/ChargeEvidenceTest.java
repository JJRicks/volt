package com.jjricks.volt;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Report diagnostics' charging evidence (ChargeEvidence), all with synthesized readings: the timeline's connection
 * boundaries, flapping bound and cap; dense windows; intentions with lock state and nothing erasing an outcome; tests
 * with their interruption; operations; changes Volt did not make (its own pending, confirmed and lost-confirmation
 * writes excluded); storage that survives a restart, reads a damaged file as empty, forgets and prunes.
 */
public final class ChargeEvidenceTest {
    private static int checks;
    private static final long WALL = 1_792_026_000_000L;
    private static final String A = "0b0c5e9e-2d1f-4c3a-9a51-7f5b2e8d1c00", B = "9f8e7d6c-5b4a-4321-8fed-cba987654321";
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    private static final class Rig {
        final AtomicLong wall = new AtomicLong(WALL), elapsed = new AtomicLong(1_000_000);
        final List<CommandLog.Record> log = new ArrayList<>();
        final File dir;
        ChargeEvidence evidence;
        Rig() throws IOException { dir = Files.createTempDirectory("charge-evidence").toFile(); open(); }
        void open() { evidence = new ChargeEvidence(dir, () -> new ArrayList<>(log), wall::get, elapsed::get); }
        void advance(long ms) { wall.addAndGet(ms); elapsed.addAndGet(ms); }
        long[] sample(int generation, Long charging) {
            long[] s = new long[ChargeEvidence.S_COLUMNS]; Arrays.fill(s, ChargeEvidence.NONE);
            s[ChargeEvidence.S_WALL] = wall.get(); s[ChargeEvidence.S_ELAPSED] = elapsed.get(); s[ChargeEvidence.S_GENERATION] = generation;
            if (charging != null) s[ChargeEvidence.S_CHARGING] = charging;
            s[ChargeEvidence.S_SOC] = 60; s[ChargeEvidence.S_MV] = 50_400; s[ChargeEvidence.S_MA] = charging != null && charging == 1 ? 3_000 : 0;
            s[ChargeEvidence.S_W] = 151; s[ChargeEvidence.S_TEMP] = 24; s[ChargeEvidence.S_ENABLED] = 1; s[ChargeEvidence.S_WIRE] = 1_792_000_000L;
            s[ChargeEvidence.S_LOCK] = 1; s[ChargeEvidence.S_LIMIT] = 90; s[ChargeEvidence.S_VISIBLE] = 0;
            return s;
        }
        void offer(String scope, long[] s) { evidence.offer(scope, s, null); }
        ChargeEvidence.Book book(String scope) throws Exception { evidence.flush().get(10, TimeUnit.SECONDS); return evidence.snapshot(scope).book; }
    }

    public static void main(String[] args) throws Exception {
        timeline(); flapping(); denseWindows(); intentions(); tests(); operations(); notByVolt(); storage(); privacyAndScope();
        System.out.println("ChargeEvidenceTest: " + checks + " assertions passed");
    }

    private static List<Long> kinds(ChargeEvidence.Book b) { List<Long> k = new ArrayList<>(); for (long[] r : b.timeline) k.add(r[ChargeEvidence.T_KIND]); return k; }
    private static long kind(ChargeEvidence.Kind k) { return k.ordinal(); }

    private static void timeline() throws Exception {
        Rig r = new Rig();
        // A connection's first row waits for a reading that says whether it is charging.
        Rig early = new Rig();
        early.offer(A, early.sample(1, null)); early.advance(2_000); early.offer(A, early.sample(1, 1L)); early.advance(2_000); early.offer(A, early.sample(1, 1L));
        ChargeEvidence.Book eb = early.book(A);
        check(eb.timeline.size() == 1 && eb.timeline.get(0)[ChargeEvidence.T_KIND] == ChargeEvidence.Kind.FIRST.ordinal() && eb.timeline.get(0)[ChargeEvidence.T_CHARGING] == 1,
                "the first row is the first reading with a charging state");
        r.offer(A, r.sample(1, 0L)); r.advance(5_000); r.offer(A, r.sample(1, 0L)); r.advance(5_000); r.offer(A, r.sample(1, 1L));
        r.advance(5_000); r.offer(A, r.sample(1, null)); r.advance(5_000); r.offer(A, r.sample(1, 0L));
        ChargeEvidence.Book b = r.book(A);
        check(kinds(b).equals(Arrays.asList(kind(ChargeEvidence.Kind.FIRST), kind(ChargeEvidence.Kind.CHANGE), kind(ChargeEvidence.Kind.CHANGE))),
                "the first reading and each charging change; an unknown reading is no change");
        check(b.timeline.get(1)[ChargeEvidence.T_CHARGING] == 1 && b.timeline.get(1)[ChargeEvidence.T_MA] == 3_000 && b.timeline.get(1)[ChargeEvidence.T_LOCK] == 1
                && b.timeline.get(1)[ChargeEvidence.T_WIRE] == 1_792_000_000L && b.timeline.get(1)[ChargeEvidence.T_WALL] == WALL + 10_000, "a row keeps the electrical and schedule readings");
        r.advance(1_000); r.offer(A, r.sample(1, 0L)); r.evidence.unlinked(A);
        r.advance(60_000); r.offer(A, r.sample(2, 1L));
        b = r.book(A);
        List<Long> k = kinds(b);
        check(k.get(3) == kind(ChargeEvidence.Kind.LAST) && b.timeline.get(3)[ChargeEvidence.T_WALL] == WALL + 21_000 && k.get(4) == kind(ChargeEvidence.Kind.FIRST),
                "the last reading before a disconnection, then the first after the next connection");
        // A new connection without a disconnect notice still closes the old one.
        r.advance(1_000); r.offer(A, r.sample(3, 1L));
        k = kinds(r.book(A));
        check(k.get(k.size() - 2) == kind(ChargeEvidence.Kind.LAST) && k.get(k.size() - 1) == kind(ChargeEvidence.Kind.FIRST), "a changed connection closes the last one");
        // The cap drops the oldest rows first.
        for (int i = 0; i < ChargeEvidence.TIMELINE + 20; i++) { r.advance(3_000); r.offer(A, r.sample(3, (long) (i % 2))); }
        b = r.book(A);
        check(b.timeline.size() == ChargeEvidence.TIMELINE && b.timeline.get(ChargeEvidence.TIMELINE - 1)[ChargeEvidence.T_WALL] == r.wall.get(), "300 rows, newest kept");
    }

    /** Flapping stays visible but bounded: 30 changes a rolling minute one by one, then a count on the next stored row. */
    private static void flapping() throws Exception {
        Rig r = new Rig();
        r.offer(A, r.sample(1, 0L));
        for (int i = 1; i <= 50; i++) { r.advance(1_000); r.offer(A, r.sample(1, (long) (i % 2))); }
        ChargeEvidence.Book b = r.book(A);
        check(b.timeline.size() == 1 + ChargeEvidence.CHANGES_PER_MINUTE, "only 30 changes in a minute are stored one by one");
        r.advance(60_000); r.offer(A, r.sample(1, 1L));
        b = r.book(A);
        long[] last = b.timeline.get(b.timeline.size() - 1);
        check(last[ChargeEvidence.T_KIND] == kind(ChargeEvidence.Kind.CHANGE) && last[ChargeEvidence.T_SKIPPED] == 20, "the next stored change counts the 20 left out");
    }

    private static void denseWindows() throws Exception {
        Rig r = new Rig();
        long start = WALL + 120_000;
        r.offer(A, r.sample(1, 0L));
        r.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        for (int i = 0; i < 300; i++) { r.advance(1_000); r.offer(A, r.sample(1, r.wall.get() >= start ? 1L : 0L)); }
        ChargeEvidence.Book b = r.book(A);
        check(b.windows.size() == 1 && b.windows.get(0)[ChargeEvidence.W_KIND] == ChargeEvidence.Window.START.ordinal() && b.windows.get(0)[ChargeEvidence.W_ANCHOR_WALL] == start,
                "a window around the Volt-armed start");
        List<long[]> w = b.windowSamples.get(0);
        check(w.get(0)[ChargeEvidence.D_WALL] >= start - ChargeEvidence.BEFORE_START_MS && w.get(0)[ChargeEvidence.D_WALL] < start - ChargeEvidence.BEFORE_START_MS + 2_000,
                "it opens 30 s before the start");
        boolean spaced = true; for (int i = 1; i < w.size(); i++) spaced &= w.get(i)[ChargeEvidence.D_ELAPSED] - w.get(i - 1)[ChargeEvidence.D_ELAPSED] >= ChargeEvidence.DENSE_GAP_MS;
        check(spaced && w.get(w.size() - 1)[ChargeEvidence.D_WALL] <= start + ChargeEvidence.AFTER_START_MS && w.size() <= ChargeEvidence.WINDOW_SAMPLES,
                "one sample per poll, at least 2 s apart, to 5 min after the start");
        // After a test ends: 5 minutes, capped at 180 samples.
        r.evidence.operation(A, ChargeScheduleController.Op.TEST_END, ChargeScheduleController.Result.OK, null, 1_000, WALL, ChargeScheduleTest.Outcome.PASSED);
        for (int i = 0; i < 400; i++) { r.advance(2_000); r.offer(A, r.sample(1, 1L)); }
        b = r.book(A);
        check(b.windows.size() == 2 && b.windows.get(1)[ChargeEvidence.W_KIND] == ChargeEvidence.Window.TEST_END.ordinal() && b.windowSamples.get(1).size() <= 151,
                "a window after a test ends lasts 5 minutes");
        // Three windows at most, oldest dropped; each capped at 180 samples.
        for (int n = 0; n < 3; n++) {
            long next = r.wall.get() + 40_000;
            r.evidence.saved(A, diagnostics(1, r.wall.get(), next, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
            for (int i = 0; i < 200; i++) { r.advance(2_000); r.offer(A, r.sample(1, (long) (i % 2))); }
        }
        b = r.book(A);
        boolean capped = true; for (List<long[]> s : b.windowSamples) capped &= s.size() <= ChargeEvidence.WINDOW_SAMPLES;
        check(b.windows.size() == ChargeEvidence.WINDOWS && b.windows.get(0)[ChargeEvidence.W_KIND] == ChargeEvidence.Window.START.ordinal() && capped,
                "three windows kept, oldest first out, 180 samples each at most");
    }

    /** A synthesized saved record (ChargeScheduleController.Diagnostics) through the controller's own encoding. */
    static ChargeScheduleController.Diagnostics diagnostics(int mode, long armed, long start, ChargeScheduleController.Outcome outcome, long outcomeWall,
            ChargeScheduleTest.Outcome test, long testWall) {
        // mode|minute|start|wire|deadline|armed|target|estimate|retired|manualStart|calculatedWire|capturedSoc|armedSoc|outcome|outcomeMs|outcomeStart|outcomeSoc|outcomeMode|
        // outcomeEnabled|outcomeWire|alert|outcomeArmedSoc|started|testWall|testStart|testResume|testOutcome|testOff|testPaused
        String record = "5|" + mode + "|480|" + start + "|" + (start / 1000 - 21600) + "|" + (start + 86_400_000L) + "|" + armed + "|90.0|0.0|false|0|0|-1.0|55.0|"
                + outcome.ordinal() + "|" + outcomeWall + "|" + (outcome == ChargeScheduleController.Outcome.NONE ? 0 : start) + "|" + (outcome == ChargeScheduleController.Outcome.NONE ? "-1.0" : "56.0")
                + "|1|0|-1|0|" + (outcome == ChargeScheduleController.Outcome.NONE ? "-1.0" : "55.0") + "|false|" + testWall + "|" + (testWall == 0 ? 0 : testWall + 120_000) + "|0|" + test.ordinal() + "|-1|false";
        return ChargeScheduleController.diagnostics(record);
    }

    private static void intentions() throws Exception {
        Rig r = new Rig();
        long start = WALL + 3_600_000;
        r.offer(A, r.sample(1, 0L));
        r.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        ChargeEvidence.Book b = r.book(A);
        check(b.open != null && b.open[ChargeEvidence.I_LOCK_ARMED] == 1 && b.open[ChargeEvidence.I_START] == start && b.open[ChargeEvidence.I_ARMED_SOC] == 55
                && b.open[ChargeEvidence.I_OFFSET] == -21600 && b.open[ChargeEvidence.I_END] == ChargeEvidence.END_OPEN, "an armed intention, locked at arming");
        // The lock as the start passes, from a fresh reading of the live link.
        r.wall.set(start + 1_000); r.elapsed.addAndGet(3_601_000); long[] s = r.sample(1, 0L); s[ChargeEvidence.S_LOCK] = 0; r.offer(A, s);
        r.advance(200_000);
        r.evidence.saved(A, diagnostics(0, WALL, start, ChargeScheduleController.Outcome.MISSED, r.wall.get(), ChargeScheduleTest.Outcome.NONE, 0));
        b = r.book(A);
        check(b.open == null && b.intentions.size() == 1 && b.intentions.get(0)[ChargeEvidence.I_END] == ChargeScheduleController.Outcome.MISSED.ordinal()
                && b.intentions.get(0)[ChargeEvidence.I_LOCK_START] == 0 && b.intentions.get(0)[ChargeEvidence.I_START_SEEN] == 1 && b.intentions.get(0)[ChargeEvidence.I_END_SOC] == 56,
                "the outcome closes it, with the lock at the start");
        check(b.intentions.get(0)[ChargeEvidence.I_LOCK_END] == ChargeEvidence.NONE, "a lock reading 200 s old is unknown at the outcome");
        // The same saved outcome again (any later save) is not a second entry.
        r.evidence.saved(A, diagnostics(0, WALL, start, ChargeScheduleController.Outcome.MISSED, r.wall.get(), ChargeScheduleTest.Outcome.NONE, 0));
        check(r.book(A).intentions.size() == 1, "one outcome, once");
        // A re-arm with another start replaces the open one; nothing erases an earlier outcome; ten ended are kept.
        for (int i = 0; i < 14; i++) {
            long next = r.wall.get() + 7_200_000;
            r.evidence.saved(A, diagnostics(2, r.wall.get(), next, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
            r.advance(1_000);
            if (i % 2 == 0) r.evidence.saved(A, diagnostics(0, r.wall.get(), next, ChargeScheduleController.Outcome.TURNED_OFF, r.wall.get(), ChargeScheduleTest.Outcome.NONE, 0));
        }
        b = r.book(A);
        int replaced = 0; for (long[] i : b.intentions) if (i[ChargeEvidence.I_END] == ChargeEvidence.END_REPLACED) replaced++;
        check(b.intentions.size() == ChargeEvidence.INTENTIONS && b.open != null && replaced > 0, "ten ended intentions plus the open one; a re-arm is 'replaced'");
        // Outcome without a tracked arming (history began later): still recorded, arming unknown.
        Rig late = new Rig();
        late.evidence.saved(A, diagnostics(0, WALL, start, ChargeScheduleController.Outcome.ENDED, WALL + 10, ChargeScheduleTest.Outcome.NONE, 0));
        b = late.book(A);
        check(b.intentions.size() == 1 && b.intentions.get(0)[ChargeEvidence.I_ARMED] == ChargeEvidence.NONE && b.intentions.get(0)[ChargeEvidence.I_LOCK_ARMED] == ChargeEvidence.NONE,
                "an outcome seen first after an update is kept, with unknown arming");
    }

    private static void tests() throws Exception {
        Rig r = new Rig();
        for (int i = 0; i < 7; i++) {
            long began = WALL + i * 600_000L;
            r.evidence.saved(A, diagnostics(1, began, began + 120_000, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.RUNNING, began));
            r.evidence.saved(A, diagnostics(1, began, began + 120_000, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.INTERRUPTED, began));
            r.evidence.operation(A, ChargeScheduleController.Op.TEST_END, ChargeScheduleController.Result.INTERRUPTED, ChargeScheduleController.Interruption.READINGS_STALE, 40_000, began,
                    ChargeScheduleTest.Outcome.INTERRUPTED);
        }
        ChargeEvidence.Book b = r.book(A);
        check(b.tests.size() == ChargeEvidence.TESTS && b.tests.get(0)[ChargeEvidence.X_WALL] == WALL + 2 * 600_000L, "the last five tests");
        check(b.tests.get(4)[ChargeEvidence.X_OUTCOME] == ChargeScheduleTest.Outcome.INTERRUPTED.ordinal()
                && b.tests.get(4)[ChargeEvidence.X_WHY] == ChargeScheduleController.Interruption.READINGS_STALE.ordinal(), "an interrupted test says why");
    }

    private static void operations() throws Exception {
        Rig r = new Rig();
        for (int i = 0; i < 130; i++) { r.advance(10); r.evidence.operation(A, ChargeScheduleController.Op.MANUAL_ON, i % 2 == 0 ? ChargeScheduleController.Result.OK : ChargeScheduleController.Result.INTERRUPTED,
                i % 2 == 0 ? null : ChargeScheduleController.Interruption.GENERATION_CHANGED, 1500, 0, null); }
        ChargeEvidence.Book b = r.book(A);
        long[] last = b.operations.get(b.operations.size() - 1);
        check(b.operations.size() == ChargeEvidence.OPERATIONS && last[ChargeEvidence.O_WHY] == ChargeScheduleController.Interruption.GENERATION_CHANGED.ordinal()
                && last[ChargeEvidence.O_DURATION] == 1500 && last[ChargeEvidence.O_TEST_WALL] == ChargeEvidence.NONE, "100 operations with result ids and reasons");
    }

    private static CommandLog.Command write(long wall, String key, Integer value, CommandLog.Outcome outcome, Long time) {
        return new CommandLog.Command(wall, CommandLog.Kind.SET, MutationGate.Origin.USER, key, value, outcome, outcome == CommandLog.Outcome.REJECTED ? 3 : -1,
                outcome == CommandLog.Outcome.REFUSED ? CapabilityResolver.Reason.values()[0] : null, null, -1, time, time == null ? null : -21600);
    }
    private static int count(ChargeEvidence.Book b, ChargeEvidence.Watched w) { int n = 0; for (long[] c : b.changes) if (c[ChargeEvidence.C_KEY] == w.ordinal()) n++; return n; }

    private static void notByVolt() throws Exception {
        Rig r = new Rig();
        r.log.add(new CommandLog.Session(WALL - 1, "1.13.1", "24011", ModelRegistry.Layer.values()[0], "", "", false));
        r.offer(A, r.sample(1, 0L));
        // The scooter's own switch goes off: no Volt write explains it.
        r.advance(10_000); long[] s = r.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = 0; r.offer(A, s);
        ChargeEvidence.Book b = r.book(A);
        check(count(b, ChargeEvidence.Watched.TIMED_CHARGING) == 1 && b.changes.get(0)[ChargeEvidence.C_OLD] == 1 && b.changes.get(0)[ChargeEvidence.C_NEW] == 0
                && b.changes.get(0)[ChargeEvidence.C_ACROSS] == 0, "a schedule switch Volt did not turn off");
        // Volt's own confirmed write: no event.
        r.advance(5_000); r.log.add(write(r.wall.get(), "timed_charging", 1, CommandLog.Outcome.READBACK_SAME, null));
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = 1; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 1, "Volt's confirmed ON is not an outside change");
        // A write still in flight (pending) is Volt's.
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; r.evidence.offer(A, s, "charge_limit");
        check(count(r.book(A), ChargeEvidence.Watched.CHARGE_LIMIT) == 0, "a pending limit write explains its readback");
        // A start whose confirmation was lost (timeout) and reads back later is Volt's; another start is not.
        r.advance(1_000); r.log.add(write(r.wall.get(), "charge_start", null, CommandLog.Outcome.TIMEOUT, 1_792_003_600L));
        r.advance(30_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_003_600L; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.CHARGE_START) == 0, "a lost confirmation that reads back later is still Volt's");
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_007_200L; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.CHARGE_START) == 1, "a start Volt never sent is an outside change");
        // Refused and rejected writes never reached the scooter: an unlock after one is not Volt's.
        r.log.add(write(r.wall.get(), "lock", 0, CommandLog.Outcome.REJECTED, null));
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_007_200L; s[ChargeEvidence.S_LOCK] = 0; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 1, "a rejected unlock does not explain an unlock");
        // A simulated session's writes never explain a real readback.
        r.log.add(new CommandLog.Session(r.wall.get(), "1.13.1", "24011", ModelRegistry.Layer.values()[0], "", "", true));
        r.log.add(write(r.wall.get() + 1, "lock", 1, CommandLog.Outcome.READBACK_SAME, null));
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_007_200L; s[ChargeEvidence.S_LOCK] = 1; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 2, "a simulated write is not Volt's");
        // A change across a disconnection is marked so.
        r.evidence.unlinked(A); r.advance(60_000); s = r.sample(2, 0L); s[ChargeEvidence.S_LIMIT] = 100; s[ChargeEvidence.S_WIRE] = 1_792_007_200L; r.offer(A, s);
        b = r.book(A);
        long[] limit = b.changes.get(b.changes.size() - 1);
        check(limit[ChargeEvidence.C_KEY] == ChargeEvidence.Watched.CHARGE_LIMIT.ordinal() && limit[ChargeEvidence.C_ACROSS] == 1, "seen across a disconnection");
        // A phone clock set back between the readings does not turn Volt's write into an outside change.
        Rig clock = new Rig();
        clock.offer(A, clock.sample(1, 0L));
        clock.wall.addAndGet(-600_000); clock.elapsed.addAndGet(1_000);
        clock.log.add(write(clock.wall.get(), "timed_charging", 0, CommandLog.Outcome.ACK, null));
        clock.advance(2_000); s = clock.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = 0; clock.offer(A, s);
        check(clock.book(A).changes.isEmpty(), "a clock change does not hide Volt's write");
        // Volt's journal never sends: the rule only reads the log.
        check(ChargeEvidence.voltWrote(null, ChargeEvidence.Watched.LOCK, 1, 0) == false, "no log, no write");
        // Capped at 100.
        Rig many = new Rig();
        for (int i = 0; i < 130; i++) { many.advance(1_000); s = many.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = i % 2; many.offer(A, s); }
        check(many.book(A).changes.size() == ChargeEvidence.CHANGES, "100 changes kept");
    }

    private static void storage() throws Exception {
        Rig r = new Rig();
        r.offer(A, r.sample(1, 0L)); r.advance(5_000); r.offer(A, r.sample(1, 1L));
        r.evidence.saved(A, diagnostics(1, WALL, WALL + 600_000, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        r.evidence.operation(A, ChargeScheduleController.Op.MANUAL_ON, ChargeScheduleController.Result.OK, null, 900, 0, null);
        r.evidence.flush().get(10, TimeUnit.SECONDS);
        File file = new File(r.dir, A);
        check(file.isFile() && file.length() < ChargeEvidence.MAX_FILE_BYTES, "flushed to its own file");
        // Killed before a disconnect: the last reading saved joins the timeline at the next start.
        r.open(); r.evidence.prefetch(A);
        ChargeEvidence.Book back = r.book(A);
        check(back.timeline.size() == 3 && back.timeline.get(2)[ChargeEvidence.T_KIND] == ChargeEvidence.Kind.LAST_SAVED.ordinal() && back.open != null
                && back.operations.size() == 1 && back.healthy, "everything reads back; the last saved reading closes the timeline");
        // Event durability: the first write is immediate, a second within 5 s of it waits for the 5 s spacing.
        Rig d = new Rig(); File df = new File(d.dir, A);
        d.evidence.operation(A, ChargeScheduleController.Op.MANUAL_ON, ChargeScheduleController.Result.OK, null, 1, 0, null);
        long until = System.nanoTime() + 3_000_000_000L; while (!df.isFile() && System.nanoTime() < until) Thread.sleep(20);
        check(df.isFile(), "an event is on disk at once when nothing was written in the last 5 s");
        long first = df.length();
        d.evidence.operation(A, ChargeScheduleController.Op.MANUAL_OFF, ChargeScheduleController.Result.OK, null, 1, 0, null);
        Thread.sleep(1_000);
        check(df.length() == first, "a second event within 5 s waits");
        until = System.nanoTime() + 7_000_000_000L; while (df.length() == first && System.nanoTime() < until) Thread.sleep(50);
        check(df.length() > first, "and is on disk within about 5 s");
        // A damaged, oversized, truncated or out-of-range file reads as empty (and unhealthy) without throwing.
        byte[] good = Files.readAllBytes(file.toPath());
        byte[] flipped = good.clone(); flipped[20] ^= 1;
        check(bad(flipped), "a flipped bit");
        check(bad(Arrays.copyOf(good, good.length - 3)), "a truncated file");
        check(bad(new byte[ChargeEvidence.MAX_FILE_BYTES + 1]), "an oversized file");
        ChargeEvidence.Book odd = new ChargeEvidence.Book(); long[] row = r.sample(1, 0L).clone(); row[ChargeEvidence.S_SOC] = 900; odd.lastSeen = row;
        check(bad(ChargeEvidence.encode(odd)), "a value out of range");
        Files.write(file.toPath(), flipped);
        r.open(); r.evidence.prefetch(A);
        ChargeEvidence.Book empty = r.book(A);
        check(empty.timeline.isEmpty() && !empty.healthy, "a damaged file loads as empty, marked unhealthy");
        // Forget and prune.
        Rig f = new Rig();
        f.offer(A, f.sample(1, 0L)); f.offer(B, f.sample(1, 0L)); f.evidence.flush().get(10, TimeUnit.SECONDS);
        f.evidence.forget(A); f.evidence.flush().get(10, TimeUnit.SECONDS);
        check(!new File(f.dir, A).exists() && new File(f.dir, B).exists() && !f.evidence.snapshot(A).loaded, "forgetting a scooter removes its evidence");
        f.evidence.prune(Collections.<String>emptyList()); f.evidence.flush().get(10, TimeUnit.SECONDS);
        check(!new File(f.dir, B).exists(), "files of scooters no longer saved are pruned");
        // Service end: the connection's last reading and everything pending reach disk without waiting; later calls are ignored.
        Rig end = new Rig(); File ef = new File(end.dir, A);
        end.offer(A, end.sample(1, 0L)); end.advance(2_000); end.offer(A, end.sample(1, 1L)); end.evidence.unlinked(A); end.evidence.close();
        end.offer(A, end.sample(1, 0L)); end.evidence.close(); end.evidence.operation(A, ChargeScheduleController.Op.MANUAL_ON, ChargeScheduleController.Result.OK, null, 1, 0, null);
        until = System.nanoTime() + 3_000_000_000L; while (!ef.isFile() && System.nanoTime() < until) Thread.sleep(20);
        Thread.sleep(200);
        end.open(); end.evidence.prefetch(A); ChargeEvidence.Book closed = end.book(A);
        check(closed.timeline.size() == 3 && closed.timeline.get(2)[ChargeEvidence.T_KIND] == ChargeEvidence.Kind.LAST.ordinal() && closed.operations.isEmpty(),
                "close writes what came before it, and nothing after");
        // An update starts empty: no file, an empty book.
        Rig fresh = new Rig(); fresh.evidence.prefetch(A);
        ChargeEvidence.Snapshot none = fresh.evidence.snapshot(A); fresh.evidence.flush().get(10, TimeUnit.SECONDS);
        ChargeEvidence.Snapshot loaded = fresh.evidence.snapshot(A);
        check(loaded.loaded && loaded.book.healthy && loaded.book.timeline.isEmpty() && loaded.book.intentions.isEmpty(), "a first start has empty logs");
    }
    private static boolean bad(byte[] bytes) {
        try { ChargeEvidence.decode(bytes); return false; } catch (IOException expected) { return true; } catch (RuntimeException crash) { return false; }
    }

    private static void privacyAndScope() throws Exception {
        Rig r = new Rig();
        r.offer("simulation:" + A + ":24011:0", r.sample(1, 0L));
        r.evidence.saved("simulation:" + A, diagnostics(1, WALL, WALL + 1, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        r.evidence.flush().get(10, TimeUnit.SECONDS);
        String[] files = r.dir.list();
        check(files == null || files.length == 0, "nothing from a simulated link is kept");
        check(!ChargeEvidence.scope("../x") && !ChargeEvidence.scope("") && ChargeEvidence.scope(A), "only a saved profile id names a file");
        // Out-of-range readings become unknown before they are kept.
        long[] s = r.sample(1, 0L); s[ChargeEvidence.S_SOC] = 140; s[ChargeEvidence.S_TEMP] = 900; r.offer(A, s);
        ChargeEvidence.Book b = r.book(A);
        check(b.timeline.get(0)[ChargeEvidence.T_SOC] == ChargeEvidence.NONE && b.timeline.get(0)[ChargeEvidence.T_TEMP] == ChargeEvidence.NONE, "out-of-range values are unknown");
        // One scooter's evidence never appears in another's.
        r.offer(B, r.sample(5, 1L));
        check(r.book(A).timeline.size() == 1 && r.book(B).timeline.size() == 1 && r.book(B).timeline.get(0)[ChargeEvidence.T_CHARGING] == 1, "each scooter keeps its own book");
    }
}
