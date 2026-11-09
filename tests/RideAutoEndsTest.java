package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;

/** The auto-end count recovered from the journal's STOP rows, interrupted at each step with synthesized rides. */
public final class RideAutoEndsTest {
    private static final String REASON = RideStore.STOP_AUTO_NO_MOTION;
    private static int checks;

    /**
     * app_settings' two keys as SharedPreferences.Editor.commit keeps them: memory changes first, even when
     * the disk write then fails, and a write that works writes everything in memory. count and counted are
     * what's on disk; restart() is a process death.
     */
    private static final class Prefs implements RideAutoEnds.Store {
        long count, liveCount; String counted = "", liveCounted = ""; boolean failing;
        public long count() { return liveCount; }
        public String counted() { return liveCounted; }
        public void save(long count, String counted) {
            liveCount = count; liveCounted = counted;
            if (failing) return;
            this.count = liveCount; this.counted = liveCounted;
        }
        void restart() { liveCount = count; liveCounted = counted; }
    }

    public static void main(String[] args) throws Exception {
        clean();
        deathBeforeJournalStop();
        deathAfterJournalStop();
        failedCountWrite();
        failedDiskWriteThenNewSession();
        refusedAutomaticStopThenManualStop();
        allWritesFailBeforeDeath();
        everyWriteFailsAcrossTwoEnds();
        journalRefusesStop();
        riderStop();
        manualStopAfterCountedEnd();
        deletedRideIsNotRecounted();
        everyAutomaticReason();
        renameAfterStop();
        backwardClockDeathBeforeCount();
        backwardClockFailedWriteThenNewSession();
        equalStopTimes();
        equalStopTimesFailedWrite();
        backwardClockManualStop();
        countThatGoesBackDoesNotCloseNewerSession();
        boundedAcrossManyEnds();
        System.out.println("RideAutoEndsTest passed " + checks + " checks");
    }

    private static RideStore store(File dir) throws Exception { return new RideStore(dir, true, null); }
    private static File dir() throws Exception { return Files.createTempDirectory("ride-auto-ends").toFile(); }
    private static long clock = 1_000_000;
    private static String startRide(RideStore rides) throws Exception { clock += 10_000; rides.start("p1", "Synthetic ride", clock, clock, "UTC"); return rides.activeId(); }
    private static String stoppedId;
    private static void stop(RideStore rides, String reason) throws Exception { clock += 10_000; stoppedId = rides.activeId(); rides.stop(clock, clock, reason); }
    /** A ride that stops at this wall time, with the monotonic clock still running forward. */
    private static void rideEndingAt(RideStore rides, long wall, String reason) throws Exception {
        clock += 10_000; rides.start("p1", "Synthetic ride", wall - 5_000, clock, "UTC"); stoppedId = rides.activeId();
        clock += 10_000; rides.stop(wall, clock, reason);
    }
    /** What stopRide does after an automatic STOP reaches the journal. */
    private static void stopped(RideAutoEnds ends, RideStore rides) { ends.stopped(stoppedId); }
    /** A process death: everything live is gone, the journals and the on-disk preferences stay. */
    private static RideAutoEnds reload(Prefs prefs, RideStore reloaded) {
        prefs.restart(); RideAutoEnds ends = new RideAutoEnds(prefs);
        ends.reconcile(reloaded.automaticStopsAtLoad());
        return ends;
    }

    private static void clean() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        startRide(rides);
        stop(rides, REASON); stopped(ends, rides);
        check(ends.count() == 1 && prefs.count == 1 && !prefs.counted.isEmpty(), "a clean automatic end counts once");
        check(reload(prefs, store(dir)).count() == 1, "and a load finds it already counted");
    }

    /** Interrupted before the STOP row: the recording recovers on the next load, and this was no end. */
    private static void deathBeforeJournalStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        String id = startRide(rides);
        RideStore reloaded = store(dir); RideAutoEnds ends = reload(prefs, reloaded);
        check(id.equals(reloaded.activeId()), "the unfinished journal recovers");
        check(ends.count() == 0, "and no automatic end is counted, so riding mode stays open");
        stop(reloaded, REASON); stopped(ends, reloaded);
        check(ends.count() == 1, "its real end later still counts");
    }

    /** Interrupted after the STOP row, before the count: the journal says it ended, and riding mode must not reopen. */
    private static void deathAfterJournalStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        String id = startRide(rides);
        RidingSession.Resume opened = new RidingSession("p1", null, 1_000, null, prefs.liveCount).resume();
        stop(rides, REASON);
        RideStore reloaded = store(dir); RideAutoEnds ends = reload(prefs, reloaded);
        check(reloaded.activeId() == null && reloaded.get(id) != null, "the journal finished");
        check(ends.count() == 1 && prefs.count == 1, "the load counts the automatic end");
        check(opened.autoEnded(ends.count()), "the riding mode it ended doesn't come back");
        check(reload(prefs, store(dir)).count() == 1, "only once");
    }

    /** The count's own write fails: this process still knows, and the next load recovers it from the journal. */
    private static void failedCountWrite() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        startRide(rides);
        stop(rides, REASON);
        prefs.failing = true;
        stopped(ends, rides);
        check(ends.count() == 1 && prefs.count == 0, "after a failed write it still reads 1 in this process");
        prefs.failing = false;
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 1 && prefs.count == 1 && !prefs.counted.isEmpty(), "and the next load saves it exactly once");
    }

    /** One end whose count missed the disk: the riding mode from before it closes, and one opened after it survives a restart. */
    private static void failedDiskWriteThenNewSession() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        RidingSession before = new RidingSession("p1", null, 1_000, null, ends.count());
        startRide(rides);
        stop(rides, REASON);
        prefs.failing = true;
        stopped(ends, rides);
        check(ends.count() == 1 && prefs.count == 0, "one end reads 1 live, with nothing on disk");
        check(before.autoEnded(ends.count()), "the riding mode it ended closes now");
        RidingSession after = new RidingSession("p1", null, 2_000, null, ends.count());
        check(!after.autoEnded(ends.count()), "a riding mode opened after the end is open");
        RidingSession.Resume savedBefore = before.resume(), savedAfter = after.resume();

        // The disk is still failing at the first load: the journal still has it, and it reads 1 again.
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 1 && prefs.count == 0, "a load that can't write still reads 1");
        check(!savedAfter.autoEnded(next.count()), "and the later riding mode restores");

        prefs.failing = false;
        next = reload(prefs, store(dir));
        check(next.count() == 1 && prefs.count == 1, "a working load saves it once");
        check(!savedAfter.autoEnded(next.count()), "the riding mode opened after the end is restored");
        check(savedBefore.autoEnded(next.count()), "the one it ended is not");
        check(reload(prefs, store(dir)).count() == 1, "and nothing counts it twice");
    }

    /**
     * The journal refused an automatic stop while storage was full and the preferences
     * couldn't be written either; the rider later stops the same ride by hand and the process restarts.
     */
    private static void refusedAutomaticStopThenManualStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        RidingSession.Resume opened = new RidingSession("p1", null, 1_000, null, ends.count()).resume();
        String id = startRide(rides);
        // The automatic stop's journal write failed here, so stopRide wrote nothing and didn't reach stopped().
        stop(rides, "");
        RideStore reloaded = store(dir); RideAutoEnds next = reload(prefs, reloaded);
        check(reloaded.get(id).stopReason.isEmpty(), "the journal says the rider stopped");
        check(next.count() == 0 && prefs.count == 0, "the rider's stop isn't counted as automatic");
        check(!opened.autoEnded(next.count()), "and the riding mode stays open");
    }

    /**
     * No preference write has reached the disk, the automatic STOP is in the journal,
     * and the process dies before the count is saved.
     */
    private static void allWritesFailBeforeDeath() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        RidingSession.Resume opened = new RidingSession("p1", null, 1_000, null, prefs.liveCount).resume();
        startRide(rides);
        prefs.failing = true;
        stop(rides, REASON);
        prefs.failing = false;
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 1 && prefs.count == 1, "the journal's automatic STOP is counted with nothing else on disk");
        check(opened.autoEnded(next.count()), "so the old riding mode doesn't reopen");
    }

    /** Every write fails across two ends and a restart: the journals still give the exact count. */
    private static void everyWriteFailsAcrossTwoEnds() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        prefs.failing = true;
        startRide(rides); stop(rides, REASON); stopped(ends, rides);
        startRide(rides); stop(rides, REASON); stopped(ends, rides);
        check(ends.count() == 2 && prefs.count == 0, "both count in this process");
        RideAutoEnds still = reload(prefs, store(dir));
        check(still.count() == 2 && prefs.count == 0, "a load with a failing disk still reads 2");
        prefs.failing = false;
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 2 && prefs.count == 2, "and a working load saves exactly 2");
        check(reload(prefs, store(dir)).count() == 2, "once");
    }

    /** The journal refused the STOP: no end is recorded anywhere, and the recording goes on. */
    private static void journalRefusesStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        String id = startRide(rides);
        RideStore reloaded = store(dir); RideAutoEnds next = reload(prefs, reloaded);
        check(next.count() == 0 && id.equals(reloaded.activeId()), "a refused stop is no end");
    }

    /** The rider's own stop writes no automatic STOP, so a load finds nothing to count. */
    private static void riderStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        startRide(rides);
        stop(rides, "");
        check(reload(prefs, store(dir)).count() == 0, "a rider's stop isn't an automatic end");
    }

    /** A counted automatic end followed by the rider's own stop: the second is no end, and the first isn't counted again. */
    private static void manualStopAfterCountedEnd() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        startRide(rides); stop(rides, REASON); stopped(ends, rides);
        startRide(rides); stop(rides, "");
        check(reload(prefs, store(dir)).count() == 1, "only the automatic end counts");
    }

    /** Deleting the newest counted ride mustn't make an older automatic end look new. */
    private static void deletedRideIsNotRecounted() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        startRide(rides); stop(rides, REASON); stopped(ends, rides);
        String newest = startRide(rides); stop(rides, REASON); stopped(ends, rides);
        rides.delete(newest);
        check(reload(prefs, store(dir)).count() == 2, "an older automatic STOP stays counted");
    }

    /** Each automatic reason counts on recovery, parameters included. */
    private static void everyAutomaticReason() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        startRide(rides); stop(rides, RideStore.STOP_AUTO_PARKED);
        startRide(rides); stop(rides, RideStore.STOP_AUTO_NO_MOTION);
        startRide(rides); stop(rides, RideStore.quietStopReason(8, true));
        check(reload(prefs, store(dir)).count() == 3, "parked, no motion and quiet all count");
    }

    /** RENAME rows may follow STOP. An unquoted name keeps the fast tail read and a quoted one forces a replay; both read the same reason. */
    private static void renameAfterStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        String plain = startRide(rides); stop(rides, REASON); rides.rename(plain, "Plain name");
        String quoted = startRide(rides); stop(rides, REASON); rides.rename(quoted, "Name, with a comma");
        String manual = startRide(rides); stop(rides, ""); rides.rename(manual, "Rider, stopped");
        check(reload(prefs, store(dir)).count() == 2, "renamed rides keep their stop reasons");
    }

    /** The clock is corrected backwards between two ends, and the process dies before the second is counted. */
    private static void backwardClockDeathBeforeCount() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        RidingSession.Resume opened = new RidingSession("p1", null, 1_000, null, ends.count()).resume();
        rideEndingAt(rides, 9_010_000, REASON);
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 2 && prefs.count == 2, "a STOP earlier on the clock than a counted one still counts");
        check(opened.autoEnded(next.count()), "and the riding mode it ended doesn't come back");
        check(reload(prefs, store(dir)).count() == 2, "and neither end counts again");
    }

    /** The same correction with the second count's write failing: a riding mode opened after it must survive the recovery. */
    private static void backwardClockFailedWriteThenNewSession() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        RidingSession.Resume before = new RidingSession("p1", null, 1_000, null, ends.count()).resume();
        prefs.failing = true;
        rideEndingAt(rides, 9_010_000, REASON); stopped(ends, rides);
        check(ends.count() == 2 && prefs.count == 1, "the failed write still reads 2 live and 1 on disk");
        RidingSession.Resume after = new RidingSession("p1", null, 2_000, null, ends.count()).resume();
        prefs.failing = false;
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 2 && prefs.count == 2, "the load recovers exactly the missed end");
        check(!after.autoEnded(next.count()), "the riding mode opened after it isn't closed");
        check(before.autoEnded(next.count()), "the one from before it is");
    }

    /** Two ends in the same millisecond, the second not counted when the process dies. */
    private static void equalStopTimes() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        rideEndingAt(rides, 10_010_000, REASON);
        check(reload(prefs, store(dir)).count() == 2 && prefs.count == 2, "an end at the same wall time as a counted one still counts");
        check(reload(prefs, store(dir)).count() == 2, "once");
    }

    /** Two ends in the same millisecond with every write failing: both are found. */
    private static void equalStopTimesFailedWrite() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        prefs.failing = true;
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        RideAutoEnds still = reload(prefs, store(dir));
        check(still.count() == 2 && prefs.count == 0, "both are found while the disk still fails");
        prefs.failing = false;
        check(reload(prefs, store(dir)).count() == 2 && prefs.count == 2, "and saved exactly once it works");
    }

    /** A rider's stop earlier on the clock than a counted automatic end is still no end, and only the real one counts. */
    private static void backwardClockManualStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        rideEndingAt(rides, 9_010_000, "");
        check(reload(prefs, store(dir)).count() == 1, "the rider's stop isn't counted");
        rideEndingAt(rides, 8_010_000, RideStore.STOP_AUTO_PARKED);
        check(reload(prefs, store(dir)).count() == 2, "and a later automatic one is");
    }

    /** The newest count never reached the disk and its ride was deleted: the count reads lower, and a newer riding mode stays. */
    private static void countThatGoesBackDoesNotCloseNewerSession() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        rideEndingAt(rides, 10_010_000, REASON); stopped(ends, rides);
        prefs.failing = true;
        rideEndingAt(rides, 10_020_000, REASON); stopped(ends, rides);
        String lost = stoppedId;
        RidingSession.Resume newer = new RidingSession("p1", null, 2_000, null, ends.count()).resume();
        prefs.failing = false;
        store(dir).delete(lost);
        RideAutoEnds next = reload(prefs, store(dir));
        check(next.count() == 1, "with the ride gone the count reads 1");
        check(!newer.autoEnded(next.count()), "and a count that went backwards closes nothing");
    }

    /** Far more ends than the record's size: it doesn't grow, nothing counted looks new, and the newest are known. */
    private static void boundedAcrossManyEnds() {
        Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        String[] ids = new String[20_000];
        for (int i = 0; i < ids.length; i++) ids[i] = java.util.UUID.nameUUIDFromBytes(("ride " + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        for (int i = 0; i < 2_000; i++) ends.stopped(ids[i]);
        int size = prefs.counted.length();
        check(size <= 1_368, "the record is a fixed size: " + size);
        check(ends.count() == 2_000, "every live stop counts");
        ends.reconcile(java.util.Arrays.copyOf(ids, 2_000));
        check(ends.count() == 2_000, "and no counted ride is counted again");

        Prefs first = new Prefs(); RideAutoEnds load = new RideAutoEnds(first);
        load.reconcile(java.util.Arrays.copyOf(ids, 300));
        check(load.count() == 300, "a first load counts each once");
        load.reconcile(java.util.Arrays.copyOf(ids, 300));
        check(load.count() == 300, "and a second counts none");
        int missed = 0;
        for (int i = 300; i < 20_000; i++) { long had = load.count(); load.reconcile(new String[]{ids[i]}); if (load.count() == had) missed++; }
        check(first.counted.length() <= 1_368, "the same size after 20,000: " + first.counted.length());
        load.reconcile(ids);
        check(load.count() == 20_000 - missed, "after 20,000 no ride counts twice, the filter full or not");
    }

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
}
