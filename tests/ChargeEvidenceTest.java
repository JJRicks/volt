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
        void offer(String scope, long[] s) { evidence.offer(scope, s, null, null); }
        ChargeEvidence.Book book(String scope) throws Exception { evidence.flush().get(10, TimeUnit.SECONDS); return evidence.snapshot(scope).book; }
    }

    public static void main(String[] args) throws Exception {
        timeline(); flapping(); denseWindows(); intentions(); tests(); operations(); notByVolt(); storage(); privacyAndScope();
        // Council review of 4e7c8802, one per finding (2's file side is CommandLogTest and DiagnosticsBundleTest).
        momentsNeedTheirConnection(); everySavedScooterLoads(); processEndInterruptsTest(); missedStartKeepsItsWindow(); pendingOnlyExplainsItsValue();
        otherScootersWritesExplainNothing(); armedBeforeTheUpdate(); versionOneStillReads();
        // Council review of 6c968d5c, one per finding (2 is CommandLogTest and DiagnosticsBundleTest; 4 is ChargeScheduleControllerTest, through this class).
        lostConfirmationAfterALongWait(); laterOutsideChangesStillCount(); lostConfirmationAcrossAReconnect(); versionTwoStillReads();
        planChangeKeepsWhatWasArmed();
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
        return ChargeScheduleController.diagnostics(encoded(mode, armed, start, outcome, outcomeWall, test, testWall), 1);
    }
    /** The controller's saved encoding for these values (as ScooterService keeps it in charge_schedule_owned_&lt;id&gt;). */
    static String encoded(int mode, long armed, long start, ChargeScheduleController.Outcome outcome, long outcomeWall,
            ChargeScheduleTest.Outcome test, long testWall) {
        // mode|minute|start|wire|deadline|armed|target|estimate|retired|manualStart|calculatedWire|capturedSoc|armedSoc|outcome|outcomeMs|outcomeStart|outcomeSoc|outcomeMode|
        // outcomeEnabled|outcomeWire|alert|outcomeArmedSoc|started|testWall|testStart|testResume|testOutcome|testOff|testPaused
        String record = "5|" + mode + "|480|" + start + "|" + (start / 1000 - 21600) + "|" + (start + 86_400_000L) + "|" + armed + "|90.0|0.0|false|0|0|-1.0|55.0|"
                + outcome.ordinal() + "|" + outcomeWall + "|" + (outcome == ChargeScheduleController.Outcome.NONE ? 0 : start) + "|" + (outcome == ChargeScheduleController.Outcome.NONE ? "-1.0" : "56.0")
                + "|1|0|-1|0|" + (outcome == ChargeScheduleController.Outcome.NONE ? "-1.0" : "55.0") + "|false|" + testWall + "|" + (testWall == 0 ? 0 : testWall + 120_000) + "|0|" + test.ordinal() + "|-1|false";
        return record;
    }

    private static void intentions() throws Exception {
        Rig r = new Rig();
        long start = WALL + 3_600_000;
        r.offer(A, r.sample(1, 0L));
        r.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        ChargeEvidence.Book b = r.book(A);
        check(b.open != null && b.open[ChargeEvidence.I_LOCK_ARMED] == ChargeEvidence.NONE && b.open[ChargeEvidence.I_ARMED_SOC] == ChargeEvidence.NONE,
                "values at arming wait for a sample at or after it (the one before does not count)");
        r.advance(1_500); r.offer(A, r.sample(1, 0L));
        b = r.book(A);
        check(b.open != null && b.open[ChargeEvidence.I_LOCK_ARMED] == 1 && b.open[ChargeEvidence.I_START] == start && b.open[ChargeEvidence.I_ARMED_SOC] == 60
                && b.open[ChargeEvidence.I_ARMED_READ] == 1_500 && b.open[ChargeEvidence.I_OFFSET] == -21600 && b.open[ChargeEvidence.I_END] == ChargeEvidence.END_OPEN,
                "an armed intention, with the battery and lock from the sample 1.5 s after arming");
        // The lock as the start passes, from a fresh reading of the live link.
        r.wall.set(start + 1_000); r.elapsed.addAndGet(3_601_000); long[] s = r.sample(1, 0L); s[ChargeEvidence.S_LOCK] = 0; r.offer(A, s);
        r.advance(200_000); long missedAt = r.wall.get();
        r.evidence.saved(A, diagnostics(0, WALL, start, ChargeScheduleController.Outcome.MISSED, missedAt, ChargeScheduleTest.Outcome.NONE, 0));
        b = r.book(A);
        check(b.open == null && b.intentions.size() == 1 && b.intentions.get(0)[ChargeEvidence.I_END] == ChargeScheduleController.Outcome.MISSED.ordinal()
                && b.intentions.get(0)[ChargeEvidence.I_LOCK_START] == 0 && b.intentions.get(0)[ChargeEvidence.I_START_READ] == 1_000 && b.intentions.get(0)[ChargeEvidence.I_START_SEEN] == 1,
                "the outcome closes it, with the lock 1 s after the start on the connection that was up across it");
        check(b.intentions.get(0)[ChargeEvidence.I_LOCK_END] == ChargeEvidence.NONE && b.intentions.get(0)[ChargeEvidence.I_END_SOC] == ChargeEvidence.NONE,
                "the 200 s old reading is not the outcome's: unknown until a sample after it");
        r.advance(2_000); s = r.sample(1, 0L); s[ChargeEvidence.S_SOC] = 61; s[ChargeEvidence.S_ENABLED] = 0; r.offer(A, s);
        b = r.book(A);
        check(b.intentions.get(0)[ChargeEvidence.I_END_SOC] == 61 && b.intentions.get(0)[ChargeEvidence.I_END_ENABLED] == 0 && b.intentions.get(0)[ChargeEvidence.I_END_WIRE] == 1_792_000_000L
                && b.intentions.get(0)[ChargeEvidence.I_LOCK_END] == 1 && b.intentions.get(0)[ChargeEvidence.I_END_READ] == 2_000, "the outcome's values from the sample 2 s after it");
        // The same saved outcome again (any later save) is not a second entry.
        r.evidence.saved(A, diagnostics(0, WALL, start, ChargeScheduleController.Outcome.MISSED, missedAt, ChargeScheduleTest.Outcome.NONE, 0));
        check(r.book(A).intentions.size() == 1 && r.book(A).intentions.get(0)[ChargeEvidence.I_END_SOC] == 61, "one outcome, once, its values kept");
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
        // Volt's own confirmed write, seen in flight on this scooter's link: no event.
        r.advance(5_000); s = r.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = 0; r.evidence.offer(A, s, "timed_charging", 1L);
        r.log.add(write(r.wall.get() + 500, "timed_charging", 1, CommandLog.Outcome.READBACK_SAME, null));
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = 1; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 1, "Volt's confirmed ON is not an outside change");
        // A write still in flight (pending) explains a readback of its own value.
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; r.evidence.offer(A, s, "charge_limit", 80L);
        check(count(r.book(A), ChargeEvidence.Watched.CHARGE_LIMIT) == 0, "a pending limit write explains its readback");
        // A start whose confirmation was lost (timeout) and reads back later is Volt's; another start is not.
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; r.evidence.offer(A, s, "charge_start", 1_792_003_600L);
        r.log.add(write(r.wall.get() + 9_000, "charge_start", null, CommandLog.Outcome.TIMEOUT, 1_792_003_600L));
        r.advance(30_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_003_600L; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.CHARGE_START) == 0, "a lost confirmation that reads back later is still Volt's");
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_007_200L; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.CHARGE_START) == 1, "a start Volt never sent is an outside change");
        // Refused and rejected writes never reached the scooter: an unlock after one is not Volt's.
        s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; s[ChargeEvidence.S_WIRE] = 1_792_007_200L; r.evidence.offer(A, s, "lock", 0L);
        r.log.add(write(r.wall.get() + 300, "lock", 0, CommandLog.Outcome.REJECTED, null));
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
        long[] inFlight = clock.sample(1, 0L); inFlight[ChargeEvidence.S_ENABLED] = ChargeEvidence.NONE; clock.evidence.offer(A, inFlight, "timed_charging", 0L);
        clock.log.add(write(clock.wall.get(), "timed_charging", 0, CommandLog.Outcome.ACK, null));
        clock.advance(2_000); s = clock.sample(1, 0L); s[ChargeEvidence.S_ENABLED] = 0; clock.offer(A, s);
        check(clock.book(A).changes.isEmpty(), "a clock change does not hide Volt's write");
        // Volt's journal never sends: the rule only reads the log.
        check(!ChargeEvidence.voltWrote(null, null, ChargeEvidence.Watched.LOCK, 1, 0), "no log, no write");
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

    private static final String C = "1a2b3c4d-0000-4000-8000-00000000000c";
    private static long[] open(ChargeEvidence.Book b) { check(b.open != null, "an open intention"); return b.open; }
    private static boolean unknown(long[] row, int... columns) { for (int c : columns) if (row[c] != ChargeEvidence.NONE) return false; return true; }

    /** Finding 1: a value at a moment comes only from a sample within 30 s after it, on the connection that was up at it. */
    private static void momentsNeedTheirConnection() throws Exception {
        long start = WALL + 600_000;
        // The start passes while disconnected: a reading of the next connection, hours later, is not the lock at the start.
        Rig away = new Rig();
        away.offer(A, away.sample(1, 0L));
        away.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        away.advance(1_000); away.offer(A, away.sample(1, 0L)); away.evidence.unlinked(A);
        away.wall.set(start + 3 * 3_600_000L); away.elapsed.addAndGet(4 * 3_600_000L); long[] s = away.sample(2, 0L); s[ChargeEvidence.S_LOCK] = 0; away.offer(A, s);
        long[] o = open(away.book(A));
        check(o[ChargeEvidence.I_START_SEEN] == 1 && unknown(o, ChargeEvidence.I_LOCK_START, ChargeEvidence.I_START_READ), "a start passed while disconnected has an unknown lock");
        // A connection that came up 5 s after the start was not up across it.
        Rig after = new Rig();
        after.offer(A, after.sample(1, 0L));
        after.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        after.evidence.unlinked(A); after.wall.set(start + 5_000); after.elapsed.addAndGet(605_000); after.offer(A, after.sample(2, 0L));
        o = open(after.book(A));
        check(o[ChargeEvidence.I_START_SEEN] == 1 && unknown(o, ChargeEvidence.I_LOCK_START, ChargeEvidence.I_START_READ), "a connection that began after the start does not say the lock at it");
        // Up across the start, the lock unknown in the first sample after it: the next sample within 30 s answers, with its delay.
        Rig wait = new Rig();
        wait.offer(A, wait.sample(1, 0L));
        wait.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        wait.wall.set(start + 500); wait.elapsed.addAndGet(600_500); s = wait.sample(1, 0L); s[ChargeEvidence.S_LOCK] = ChargeEvidence.NONE; wait.offer(A, s);
        wait.advance(2_000); s = wait.sample(1, 0L); s[ChargeEvidence.S_LOCK] = 0; wait.offer(A, s);
        o = open(wait.book(A));
        check(o[ChargeEvidence.I_LOCK_START] == 0 && o[ChargeEvidence.I_START_READ] == 2_500, "the first sample after the start that knows the lock, 2.5 s after it");
        // Up across the start, but the first sample after it comes 40 s later: unknown.
        Rig slow = new Rig();
        slow.offer(A, slow.sample(1, 0L));
        slow.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        slow.wall.set(start + 40_000); slow.elapsed.addAndGet(640_000); slow.offer(A, slow.sample(1, 0L));
        o = open(slow.book(A));
        check(o[ChargeEvidence.I_START_SEEN] == 1 && unknown(o, ChargeEvidence.I_LOCK_START, ChargeEvidence.I_START_READ), "a sample 40 s after the start is too late");
        // Arming: a sample 40 s later is too late; one from the next connection does not count; a record saved with no connection never waits.
        Rig late = new Rig();
        late.offer(A, late.sample(1, 0L));
        late.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        late.advance(40_000); late.offer(A, late.sample(1, 0L));
        check(unknown(open(late.book(A)), ChargeEvidence.I_ARMED_SOC, ChargeEvidence.I_LOCK_ARMED, ChargeEvidence.I_ARMED_READ), "a sample 40 s after arming is not the arming's");
        Rig moved = new Rig();
        moved.offer(A, moved.sample(1, 0L));
        moved.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        moved.evidence.unlinked(A); moved.advance(2_000); moved.offer(A, moved.sample(2, 0L));
        check(unknown(open(moved.book(A)), ChargeEvidence.I_ARMED_SOC, ChargeEvidence.I_LOCK_ARMED, ChargeEvidence.I_ARMED_READ), "the next connection's sample is not the arming's");
        Rig offline = new Rig();
        offline.offer(A, offline.sample(1, 0L));
        offline.evidence.saved(A, ChargeScheduleController.diagnostics(encoded(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0)));
        offline.advance(1_000); offline.offer(A, offline.sample(1, 0L));
        check(unknown(open(offline.book(A)), ChargeEvidence.I_ARMED_SOC, ChargeEvidence.I_LOCK_ARMED, ChargeEvidence.I_ARMED_READ), "a record saved on no connection has unknown values at arming");
        // An outcome saved with no connection: its battery and readbacks stay unknown, even with a connection seconds later.
        Rig settled = new Rig();
        settled.offer(A, settled.sample(1, 0L));
        settled.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        settled.evidence.unlinked(A); settled.wall.set(start + 200_000); settled.elapsed.addAndGet(800_000);
        settled.evidence.saved(A, ChargeScheduleController.diagnostics(encoded(0, WALL, start, ChargeScheduleController.Outcome.MISSED, settled.wall.get(), ChargeScheduleTest.Outcome.NONE, 0)));
        settled.advance(2_000); settled.offer(A, settled.sample(2, 0L));
        ChargeEvidence.Book b = settled.book(A);
        check(b.intentions.size() == 1 && unknown(b.intentions.get(0), ChargeEvidence.I_END_SOC, ChargeEvidence.I_END_ENABLED, ChargeEvidence.I_END_WIRE, ChargeEvidence.I_LOCK_END,
                ChargeEvidence.I_END_READ, ChargeEvidence.I_LOCK_START), "an outcome settled offline has unknown readbacks; its start passed offline too");
        // A replaced intention ends at the re-arm: its values from the sample after that save, on that connection.
        Rig replaced = new Rig();
        replaced.offer(A, replaced.sample(1, 0L));
        replaced.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        replaced.advance(60_000);
        replaced.evidence.saved(A, diagnostics(1, replaced.wall.get(), start + 3_600_000, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        replaced.advance(700); s = replaced.sample(1, 0L); s[ChargeEvidence.S_SOC] = 62; replaced.offer(A, s);
        b = replaced.book(A);
        check(b.intentions.size() == 1 && b.intentions.get(0)[ChargeEvidence.I_END] == ChargeEvidence.END_REPLACED && b.intentions.get(0)[ChargeEvidence.I_END_SOC] == 62
                && b.intentions.get(0)[ChargeEvidence.I_END_READ] == 700 && b.open[ChargeEvidence.I_ARMED_SOC] == 62 && b.open[ChargeEvidence.I_ARMED_READ] == 700,
                "a re-arm ends the old intention and arms the new one at the same moment, from the same sample");
    }

    /** Finding 4: every saved scooter's file loads at the service start, so a scooter chosen offline shows its evidence. */
    private static void everySavedScooterLoads() throws Exception {
        Rig r = new Rig();
        r.offer(A, r.sample(1, 0L)); r.offer(B, r.sample(2, 1L)); r.evidence.flush().get(10, TimeUnit.SECONDS);
        r.open();
        r.evidence.open(Arrays.asList(A, B), id -> null);
        r.evidence.flush().get(10, TimeUnit.SECONDS);
        ChargeEvidence.Snapshot b = r.evidence.snapshot(B);
        check(r.evidence.snapshot(A).loaded && b.loaded && !b.book.timeline.isEmpty() && b.book.timeline.get(0)[ChargeEvidence.T_CHARGING] == 1,
                "after a restart both saved scooters' files are loaded without a connection or a selection");
        // A scooter added since (no file) loads empty when it is selected.
        r.evidence.prefetch(C); r.evidence.flush().get(10, TimeUnit.SECONDS);
        check(r.evidence.snapshot(C).loaded && r.evidence.snapshot(C).book.timeline.isEmpty(), "a selected scooter with no file reads as loaded and empty");
    }

    /** Finding 5: a test still running when the process ended reads as interrupted, with why, once the file loads again. */
    private static void processEndInterruptsTest() throws Exception {
        Rig r = new Rig();
        // As the file is when the process ends mid-test: the controller's live record said running (a decoded one never does).
        ChargeEvidence.Book running = new ChargeEvidence.Book();
        running.tests.add(new long[]{WALL, WALL + 120_000, ChargeScheduleTest.Outcome.RUNNING.ordinal(), ChargeEvidence.NONE, 0, ChargeEvidence.NONE, ChargeEvidence.NONE});
        Files.write(new File(r.dir, A).toPath(), ChargeEvidence.encode(running));
        r.open(); r.evidence.prefetch(A);
        ChargeEvidence.Book b = r.book(A);
        check(b.tests.size() == 1 && b.tests.get(0)[ChargeEvidence.X_OUTCOME] == ChargeScheduleTest.Outcome.INTERRUPTED.ordinal()
                && b.tests.get(0)[ChargeEvidence.X_WHY] == ChargeScheduleController.Interruption.PROCESS_ENDED.ordinal(), "a test cut off by the process end is interrupted: process ended");
        r.evidence.flush().get(10, TimeUnit.SECONDS); r.open(); r.evidence.prefetch(A);
        b = r.book(A);
        check(b.tests.get(0)[ChargeEvidence.X_OUTCOME] == ChargeScheduleTest.Outcome.INTERRUPTED.ordinal(), "and so on disk");
        // The controller's own record (decoded as interrupted) keeps the reason when it is saved again.
        r.evidence.saved(A, diagnostics(1, WALL, WALL + 120_000, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.INTERRUPTED, WALL));
        check(r.book(A).tests.get(0)[ChargeEvidence.X_WHY] == ChargeScheduleController.Interruption.PROCESS_ENDED.ordinal(), "a later save keeps why");
    }

    /** Finding 6: a missed start settles at two minutes; its dense window still runs to five. */
    private static void missedStartKeepsItsWindow() throws Exception {
        Rig r = new Rig();
        long start = WALL + 60_000;
        r.offer(A, r.sample(1, 0L));
        r.evidence.saved(A, diagnostics(1, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0));
        boolean settled = false;
        for (int i = 0; i < 240; i++) {
            r.advance(2_000);
            if (!settled && r.wall.get() >= start + 120_000) {
                r.evidence.saved(A, diagnostics(0, WALL, start, ChargeScheduleController.Outcome.MISSED, r.wall.get(), ChargeScheduleTest.Outcome.NONE, 0)); settled = true;
            }
            r.offer(A, r.sample(1, r.wall.get() >= start + 200_000 ? (long) (i % 2) : 0L));
        }
        ChargeEvidence.Book b = r.book(A);
        check(b.open == null && b.windows.size() == 1 && b.windows.get(0)[ChargeEvidence.W_ANCHOR_WALL] == start, "one start window");
        List<long[]> w = b.windowSamples.get(0);
        long last = w.get(w.size() - 1)[ChargeEvidence.D_WALL];
        check(last >= start + ChargeEvidence.AFTER_START_MS - ChargeEvidence.DENSE_GAP_MS && last <= start + ChargeEvidence.AFTER_START_MS,
                "the window keeps sampling to 5 min after the start, past the miss at 2 min");
    }

    /** Finding 7: a write in flight explains only a change to the value it asks for. */
    private static void pendingOnlyExplainsItsValue() throws Exception {
        Rig r = new Rig();
        r.offer(A, r.sample(1, 0L));
        // Locking (asks for 1) while the reading goes 1 to 0: the other way, not the write's.
        r.advance(1_000); long[] s = r.sample(1, 0L); s[ChargeEvidence.S_LOCK] = 0; r.evidence.offer(A, s, "lock", 1L);
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 1, "a change away from the value being written is recorded");
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LOCK] = 1; r.evidence.offer(A, s, "lock", 1L);
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 1, "the change to the value being written is the write's");
        // A limit write asking for 80 while the reading moves 90 to 70: recorded; then 70 to 80: the write's.
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 70; r.evidence.offer(A, s, "charge_limit", 80L);
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LIMIT] = 80; r.evidence.offer(A, s, "charge_limit", 80L);
        ChargeEvidence.Book b = r.book(A);
        check(count(b, ChargeEvidence.Watched.CHARGE_LIMIT) == 1 && b.changes.get(b.changes.size() - 1)[ChargeEvidence.C_NEW] == 70, "only the move to 70 is recorded");
        // A different control in flight explains nothing about the lock.
        r.advance(1_000); s = r.sample(1, 0L); s[ChargeEvidence.S_LOCK] = 0; s[ChargeEvidence.S_LIMIT] = 80; r.evidence.offer(A, s, "charge_limit", 80L);
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 2, "another control's write does not explain an unlock");
    }

    /** Finding 8: the shared command log has no scooter identity; only a write seen in flight on this scooter's own link explains its change. */
    private static void otherScootersWritesExplainNothing() throws Exception {
        Rig r = new Rig();
        r.log.add(new CommandLog.Session(WALL - 1, "1.13.1", "24011", ModelRegistry.Layer.values()[0], "", "", false));
        r.offer(A, r.sample(1, 0L)); r.evidence.unlinked(A);
        // Volt unlocks scooter B on B's own link; the log has the write, with no identity.
        r.advance(10_000); r.offer(B, r.sample(2, 0L));
        r.advance(1_000); r.evidence.offer(B, r.sample(2, 0L), "lock", 0L);
        r.log.add(write(r.wall.get() + 300, "lock", 0, CommandLog.Outcome.READBACK_SAME, null));
        r.advance(1_000); long[] s = r.sample(2, 0L); s[ChargeEvidence.S_LOCK] = 0; r.offer(B, s);
        r.evidence.unlinked(B);
        // Scooter A comes back unlocked (another phone, the official app): B's write does not explain it.
        r.advance(60_000); s = r.sample(3, 0L); s[ChargeEvidence.S_LOCK] = 0; r.offer(A, s);
        check(count(r.book(B), ChargeEvidence.Watched.LOCK) == 0, "B's own write explains B's readback");
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 1, "B's write does not explain A's change");
        // A's own write with its log entry trimmed away still explains A's readback.
        r.log.clear();
        r.advance(1_000); s = r.sample(3, 0L); s[ChargeEvidence.S_LOCK] = 0; r.evidence.offer(A, s, "lock", 1L);
        r.evidence.unlinked(A); r.advance(30_000); s = r.sample(4, 0L); s[ChargeEvidence.S_LOCK] = 1; r.offer(A, s);
        check(count(r.book(A), ChargeEvidence.Watched.LOCK) == 1, "A's own write explains A's readback across a reconnection, log or not");
        // Spent: the run it explained has ended, so it explains nothing more, also after a restart (lostConfirmationAcrossAReconnect keeps one on disk).
        r.evidence.flush().get(10, TimeUnit.SECONDS); r.open(); r.evidence.prefetch(A);
        check(r.book(A).own.isEmpty(), "the own write is spent once it explained a change");
    }

    /** Finding 9: a plan armed before the update (no evidence file yet) is handed over at the start: open intention and start window. */
    private static void armedBeforeTheUpdate() throws Exception {
        Rig r = new Rig();
        long armed = WALL - 3_600_000L, start = WALL + 60_000;
        String record = encoded(1, armed, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0);
        r.evidence.open(Collections.singletonList(A), id -> A.equals(id) ? record : null);
        ChargeEvidence.Book b = r.book(A);
        check(b.open != null && b.open[ChargeEvidence.I_START] == start && b.open[ChargeEvidence.I_ARMED] == armed && b.intentions.isEmpty()
                && unknown(b.open, ChargeEvidence.I_ARMED_SOC, ChargeEvidence.I_LOCK_ARMED, ChargeEvidence.I_ARMED_READ), "the open plan, armed before, with unknown values at arming");
        r.offer(A, r.sample(1, 0L));
        for (int i = 0; i < 60; i++) { r.advance(2_000); r.offer(A, r.sample(1, 0L)); }
        b = r.book(A);
        check(b.windows.size() == 1 && b.windows.get(0)[ChargeEvidence.W_ANCHOR_WALL] == start && b.windowSamples.get(0).size() > 30 && b.open[ChargeEvidence.I_LOCK_START] == 1,
                "its start window and the lock at its start follow");
        // The next start hands the same record over again: nothing doubles or resets.
        r.evidence.flush().get(10, TimeUnit.SECONDS); r.open();
        r.evidence.open(Collections.singletonList(A), id -> record);
        b = r.book(A);
        check(b.intentions.isEmpty() && b.open[ChargeEvidence.I_ARMED] == armed && b.open[ChargeEvidence.I_LOCK_START] == 1 && b.windows.size() == 1, "a second handover changes nothing");
        // A record that does not decode is no plan.
        Rig odd = new Rig(); odd.evidence.open(Collections.singletonList(A), id -> "garbage");
        check(odd.book(A).open == null && odd.book(A).intentions.isEmpty(), "an unreadable record hands over nothing");
    }

    /** Version 1 files (development builds before this layout) still read; their at-moment values had no provenance and become unknown. */
    private static void versionOneStillReads() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(0x56434556); out.writeInt(1);
            long[] row = new long[ChargeEvidence.I_COLUMNS_V1]; Arrays.fill(row, ChargeEvidence.NONE);
            row[ChargeEvidence.I_ARMED] = WALL; row[ChargeEvidence.I_MODE] = 1; row[ChargeEvidence.I_START] = WALL + 60_000; row[ChargeEvidence.I_ARMED_SOC] = 55;
            row[ChargeEvidence.I_TARGET] = 90; row[ChargeEvidence.I_STARTED] = 0; row[ChargeEvidence.I_END] = ChargeEvidence.END_OPEN; row[ChargeEvidence.I_LOCK_ARMED] = 1;
            row[ChargeEvidence.I_LOCK_START] = 0; row[ChargeEvidence.I_START_SEEN] = 1;
            out.writeShort(1); out.writeByte(ChargeEvidence.I_COLUMNS_V1); for (long v : row) out.writeLong(v);
            int[] empty = {ChargeEvidence.X_COLUMNS, ChargeEvidence.T_COLUMNS, ChargeEvidence.O_COLUMNS, ChargeEvidence.C_COLUMNS, ChargeEvidence.W_COLUMNS};
            for (int columns : empty) { out.writeShort(0); out.writeByte(columns); }
            out.writeShort(ChargeEvidence.Watched.values().length); out.writeByte(ChargeEvidence.F_COLUMNS_V2);
            for (int i = 0; i < ChargeEvidence.Watched.values().length * ChargeEvidence.F_COLUMNS_V2; i++) out.writeLong(ChargeEvidence.NONE);
            out.writeShort(0); out.writeByte(ChargeEvidence.S_COLUMNS); out.writeBoolean(false);
        }
        byte[] body = buffer.toByteArray(); java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(body);
        byte[] all = Arrays.copyOf(body, body.length + 8); long v = crc.getValue(); for (int i = 0; i < 8; i++) all[body.length + i] = (byte) (v >>> (56 - 8 * i));
        ChargeEvidence.Book b = ChargeEvidence.decode(all);
        check(b.open != null && b.open[ChargeEvidence.I_START] == WALL + 60_000 && b.open[ChargeEvidence.I_TARGET] == 90 && b.own.isEmpty()
                && unknown(b.open, ChargeEvidence.I_ARMED_SOC, ChargeEvidence.I_LOCK_ARMED, ChargeEvidence.I_LOCK_START, ChargeEvidence.I_ARMED_READ, ChargeEvidence.I_START_READ),
                "a version 1 file reads, its at-moment values unknown");
        check(unknown(b.open, ChargeEvidence.I_CHANGED, ChargeEvidence.I_CHANGED_MODE, ChargeEvidence.I_CHANGED_DEADLINE, ChargeEvidence.I_CHANGED_TARGET), "with no plan change known");
        check(ChargeEvidence.decode(ChargeEvidence.encode(b)).open[ChargeEvidence.I_START] == WALL + 60_000, "and writes back as the current version");
    }

    private static long[] enabled(Rig r, int generation, long value) { long[] s = r.sample(generation, 0L); s[ChargeEvidence.S_ENABLED] = value; return s; }
    private static CommandLog.Session real() { return new CommandLog.Session(WALL - 1, "1.13.1", "24011", ModelRegistry.Layer.values()[0], "", "", false); }

    /**
     * Finding 1: Volt turns the schedule on in sight on a weak link. The old value keeps reading every 2 s while the
     * write waits 6.5 s and longer, the confirmation is lost (timeout), and the value Volt asked for reads back later:
     * the change is Volt's, however long the old value kept reading after the write was issued.
     */
    private static void lostConfirmationAfterALongWait() throws Exception {
        Rig r = new Rig(); r.log.add(real());
        r.offer(A, enabled(r, 1, 0));
        long issued = r.wall.get() + 2_000;
        for (int i = 0; i < 4; i++) { r.advance(2_000); r.evidence.offer(A, enabled(r, 1, 0), "timed_charging", 1L); }
        r.log.add(write(issued + 6_500, "timed_charging", 1, CommandLog.Outcome.TIMEOUT, null));
        for (int i = 0; i < 5; i++) { r.advance(2_000); r.offer(A, enabled(r, 1, 0)); }
        r.advance(2_000); r.offer(A, enabled(r, 1, 1));
        check(count(r.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 0, "a lost confirmation read back after 18 s of the old value is Volt's");
    }
    /** Finding 1, the other side: an own write already confirmed and seen applied explains no later outside change to the same value. */
    private static void laterOutsideChangesStillCount() throws Exception {
        Rig r = new Rig(); r.log.add(real());
        r.offer(A, enabled(r, 1, 0));
        r.advance(2_000); r.evidence.offer(A, enabled(r, 1, 0), "timed_charging", 1L);
        r.log.add(write(r.wall.get() + 500, "timed_charging", 1, CommandLog.Outcome.READBACK_SAME, null));
        r.advance(2_000); r.evidence.offer(A, enabled(r, 1, 1), "timed_charging", 1L);
        r.advance(2_000); r.offer(A, enabled(r, 1, 1));
        check(count(r.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 0, "Volt's confirmed ON is Volt's");
        // The official app turns it off, then on again, in sight: both are outside changes.
        r.advance(2_000); r.offer(A, enabled(r, 1, 0));
        r.advance(2_000); r.offer(A, enabled(r, 1, 1));
        ChargeEvidence.Book b = r.book(A);
        check(count(b, ChargeEvidence.Watched.TIMED_CHARGING) == 2 && b.changes.get(1)[ChargeEvidence.C_NEW] == 1, "a later ON by someone else is listed, though Volt once wrote ON");
        // The same after a write whose confirmation was lost: it explains its own readback once, then nothing.
        Rig lost = new Rig(); lost.log.add(real());
        lost.offer(A, enabled(lost, 1, 0));
        lost.advance(2_000); lost.evidence.offer(A, enabled(lost, 1, 0), "timed_charging", 1L);
        lost.log.add(write(lost.wall.get() + 6_500, "timed_charging", 1, CommandLog.Outcome.TIMEOUT, null));
        for (int i = 0; i < 4; i++) { lost.advance(2_000); lost.offer(A, enabled(lost, 1, 0)); }
        lost.advance(2_000); lost.offer(A, enabled(lost, 1, 1));
        lost.advance(60_000); lost.offer(A, enabled(lost, 1, 0));
        lost.advance(60_000); lost.offer(A, enabled(lost, 1, 1));
        b = lost.book(A);
        check(count(b, ChargeEvidence.Watched.TIMED_CHARGING) == 2 && b.changes.get(0)[ChargeEvidence.C_NEW] == 0 && b.changes.get(1)[ChargeEvidence.C_NEW] == 1,
                "the lost write explains its own readback only; the later OFF and ON are listed");
        // A rejected write explains nothing, however its run goes.
        Rig rejected = new Rig(); rejected.log.add(real());
        rejected.offer(A, enabled(rejected, 1, 0));
        rejected.advance(2_000); rejected.evidence.offer(A, enabled(rejected, 1, 0), "timed_charging", 1L);
        rejected.log.add(write(rejected.wall.get() + 300, "timed_charging", 1, CommandLog.Outcome.REJECTED, null));
        rejected.advance(2_000); rejected.offer(A, enabled(rejected, 1, 0));
        rejected.advance(10_000); rejected.offer(A, enabled(rejected, 1, 1));
        check(count(rejected.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 1, "a rejected ON does not explain an ON");
    }
    /** Finding 1 across a reconnection and a process restart: the run of the old value and the own write are both on disk. */
    private static void lostConfirmationAcrossAReconnect() throws Exception {
        Rig r = new Rig(); r.log.add(real());
        r.offer(A, enabled(r, 1, 0));
        for (int i = 0; i < 3; i++) { r.advance(2_000); r.evidence.offer(A, enabled(r, 1, 0), "timed_charging", 1L); }
        r.log.add(write(r.wall.get(), "timed_charging", 1, CommandLog.Outcome.INTERRUPTED, null));
        r.evidence.unlinked(A); r.evidence.flush().get(10, TimeUnit.SECONDS);
        r.open(); r.evidence.prefetch(A);
        ChargeEvidence.Book kept = r.book(A);
        check(kept.own.size() == 1 && kept.own.get(0)[ChargeEvidence.OW_RUN] == WALL && kept.fresh[ChargeEvidence.Watched.TIMED_CHARGING.ordinal()][ChargeEvidence.F_SINCE] == WALL,
                "the own write, tagged with the old value's run, and that run's first reading are kept on disk");
        // The next connection first reads the old value for a while, then the value Volt asked for.
        r.advance(120_000);
        for (int i = 0; i < 6; i++) { r.advance(2_000); r.offer(A, enabled(r, 2, 0)); }
        r.advance(2_000); r.offer(A, enabled(r, 2, 1));
        ChargeEvidence.Book b = r.book(A);
        check(count(b, ChargeEvidence.Watched.TIMED_CHARGING) == 0 && b.own.isEmpty(), "Volt's write, read back on the next connection after a restart; then spent");
        // Read back at once on the next connection: Volt's too, and marked so had it been listed (it is not).
        Rig quick = new Rig(); quick.log.add(real());
        quick.offer(A, enabled(quick, 1, 0));
        quick.advance(2_000); quick.evidence.offer(A, enabled(quick, 1, 0), "timed_charging", 1L); quick.evidence.unlinked(A);
        quick.advance(60_000); quick.offer(A, enabled(quick, 2, 1));
        check(count(quick.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 0, "read back first thing on the next connection: Volt's");
        // Another value across the reconnection is listed, marked across.
        quick.evidence.unlinked(A); quick.advance(60_000); quick.offer(A, enabled(quick, 3, 0));
        b = quick.book(A);
        check(count(b, ChargeEvidence.Watched.TIMED_CHARGING) == 1 && b.changes.get(0)[ChargeEvidence.C_ACROSS] == 1, "an OFF nobody in Volt sent, across a disconnection");
    }
    /** Version 2 files (development builds of this pull request) still read: each run begins at its last reading, an own write the old rule counted joins it, no plan change is known. */
    private static void versionTwoStillReads() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long lastRead = WALL + 20_000;
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(0x56434556); out.writeInt(2);
            long[] row = new long[ChargeEvidence.I_COLUMNS_V2]; Arrays.fill(row, ChargeEvidence.NONE);
            row[ChargeEvidence.I_ARMED] = WALL; row[ChargeEvidence.I_MODE] = 2; row[ChargeEvidence.I_START] = WALL + 60_000; row[ChargeEvidence.I_TARGET] = 90;
            row[ChargeEvidence.I_STARTED] = 0; row[ChargeEvidence.I_END] = ChargeEvidence.END_OPEN; row[ChargeEvidence.I_START_SEEN] = 0;
            out.writeShort(1); out.writeByte(ChargeEvidence.I_COLUMNS_V2); for (long v : row) out.writeLong(v);
            int[] empty = {ChargeEvidence.X_COLUMNS, ChargeEvidence.T_COLUMNS, ChargeEvidence.O_COLUMNS, ChargeEvidence.C_COLUMNS, ChargeEvidence.W_COLUMNS};
            for (int columns : empty) { out.writeShort(0); out.writeByte(columns); }
            out.writeShort(ChargeEvidence.Watched.values().length); out.writeByte(ChargeEvidence.F_COLUMNS_V2);
            for (ChargeEvidence.Watched w : ChargeEvidence.Watched.values()) {
                if (w == ChargeEvidence.Watched.TIMED_CHARGING) { out.writeLong(0); out.writeLong(lastRead); out.writeLong(1_020_000); }
                else for (int i = 0; i < ChargeEvidence.F_COLUMNS_V2; i++) out.writeLong(ChargeEvidence.NONE);
            }
            out.writeShort(0); out.writeByte(ChargeEvidence.S_COLUMNS); out.writeBoolean(false);
            // Two own writes of ON: one the old rule still counted (2 s before the last reading), one it no longer did (60 s before).
            out.writeShort(2); out.writeByte(ChargeEvidence.OW_COLUMNS_V2);
            for (long wall : new long[]{lastRead - 2_000, lastRead - 60_000}) { out.writeLong(ChargeEvidence.Watched.TIMED_CHARGING.ordinal()); out.writeLong(1); out.writeLong(wall); }
        }
        byte[] body = buffer.toByteArray(); java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(body);
        byte[] all = Arrays.copyOf(body, body.length + 8); long v = crc.getValue(); for (int i = 0; i < 8; i++) all[body.length + i] = (byte) (v >>> (56 - 8 * i));
        ChargeEvidence.Book b = ChargeEvidence.decode(all);
        long[] f = b.fresh[ChargeEvidence.Watched.TIMED_CHARGING.ordinal()];
        check(b.open != null && b.open[ChargeEvidence.I_MODE] == 2 && unknown(b.open, ChargeEvidence.I_CHANGED, ChargeEvidence.I_CHANGED_MODE, ChargeEvidence.I_CHANGED_DEADLINE, ChargeEvidence.I_CHANGED_TARGET)
                && f[ChargeEvidence.F_SINCE] == lastRead && b.own.size() == 2 && b.own.get(0)[ChargeEvidence.OW_RUN] == lastRead && b.own.get(1)[ChargeEvidence.OW_RUN] == ChargeEvidence.NONE,
                "a version 2 file reads: its runs begin at their last reading, the own write the old rule counted joins that run, no plan change known");
        check(ChargeEvidence.decode(ChargeEvidence.encode(b)).own.size() == 2, "and writes back as the current version");
        // In use: the counted write still explains its readback.
        Rig r = new Rig(); r.log.add(real());
        Files.write(new File(r.dir, A).toPath(), all); r.open(); r.evidence.prefetch(A);
        r.wall.set(lastRead + 30_000); r.offer(A, enabled(r, 1, 1));
        check(count(r.book(A), ChargeEvidence.Watched.TIMED_CHARGING) == 0, "a version 2 own write the old rule counted still explains its readback");
    }
    private static String withTarget(String record, int mode, int target) {
        String[] v = record.split("\\|", -1); v[1] = String.valueOf(mode); v[7] = target + ".0"; return String.join("|", v);
    }
    /** Finding 3: a charge limit edit before the start turns Charge done by into a manual plan; the row keeps the plan as armed and the change apart. */
    private static void planChangeKeepsWhatWasArmed() throws Exception {
        Rig r = new Rig();
        long start = WALL + 3_600_000, deadline = start + 86_400_000L;
        String armed = encoded(2, WALL, start, ChargeScheduleController.Outcome.NONE, 0, ChargeScheduleTest.Outcome.NONE, 0);
        r.evidence.saved(A, ChargeScheduleController.diagnostics(armed, 1));
        r.advance(600_000); long changedAt = r.wall.get();
        String changed = withTarget(armed, 1, 80);
        r.evidence.saved(A, ChargeScheduleController.diagnostics(changed, 1));
        ChargeEvidence.Book b = r.book(A);
        check(b.open[ChargeEvidence.I_MODE] == 2 && b.open[ChargeEvidence.I_DEADLINE] == deadline && b.open[ChargeEvidence.I_TARGET] == 90
                && b.open[ChargeEvidence.I_CHANGED] == changedAt && b.open[ChargeEvidence.I_CHANGED_MODE] == 1 && b.open[ChargeEvidence.I_CHANGED_DEADLINE] == ChargeEvidence.NONE
                && b.open[ChargeEvidence.I_CHANGED_TARGET] == 80, "Charge done by as armed, then the change to a manual plan to 80 %, with when");
        // The same record saved again later is no new change.
        r.advance(60_000); r.evidence.saved(A, ChargeScheduleController.diagnostics(changed, 1));
        check(r.book(A).open[ChargeEvidence.I_CHANGED] == changedAt, "an unchanged save keeps the change's time");
        // It ends: the ended row still says what was armed and what it became.
        r.advance(60_000);
        r.evidence.saved(A, ChargeScheduleController.diagnostics(encoded(0, WALL, start, ChargeScheduleController.Outcome.TURNED_OFF, r.wall.get(), ChargeScheduleTest.Outcome.NONE, 0), 1));
        b = r.book(A);
        check(b.open == null && b.intentions.size() == 1 && b.intentions.get(0)[ChargeEvidence.I_MODE] == 2 && b.intentions.get(0)[ChargeEvidence.I_CHANGED_MODE] == 1
                && b.intentions.get(0)[ChargeEvidence.I_CHANGED_TARGET] == 80, "the ended plan keeps both");
        // Armed again for the same start: the plan as newly armed, no change yet.
        Rig again = new Rig();
        again.evidence.saved(A, ChargeScheduleController.diagnostics(armed, 1));
        again.advance(1_000); again.evidence.saved(A, ChargeScheduleController.diagnostics(changed, 1));
        again.advance(1_000); again.evidence.saved(A, ChargeScheduleController.diagnostics(withTarget(encoded(2, again.wall.get(), start, ChargeScheduleController.Outcome.NONE, 0,
                ChargeScheduleTest.Outcome.NONE, 0), 2, 85), 1));
        b = again.book(A);
        check(b.open[ChargeEvidence.I_ARMED] == again.wall.get() && b.open[ChargeEvidence.I_MODE] == 2 && b.open[ChargeEvidence.I_TARGET] == 85
                && unknown(b.open, ChargeEvidence.I_CHANGED, ChargeEvidence.I_CHANGED_MODE, ChargeEvidence.I_CHANGED_DEADLINE, ChargeEvidence.I_CHANGED_TARGET), "a re-arm starts a new plan as armed");
    }
}
