package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;

/** The auto-end count and the journal's STOP row, interrupted at each step with synthesized rides. */
public final class RideAutoEndsTest {
    private static final String REASON = RideStore.STOP_AUTO_NO_MOTION;
    private static int checks;

    /** app_settings' two keys: any save can be made to fail, and a "process death" just drops the unsaved count. */
    private static final class Prefs implements RideAutoEnds.Store {
        long count; String pending; boolean failing, failingOnce;
        public long count() { return count; }
        public String pending() { return pending; }
        public boolean save(long count, String pending) {
            if (failing || failingOnce) { failingOnce = false; return false; }
            this.count = count; this.pending = pending; return true;
        }
    }

    public static void main(String[] args) throws Exception {
        clean();
        deathBeforeJournalStop();
        deathAfterJournalStop();
        failedCountWrite();
        failedIntentWrite();
        bothWritesFail();
        journalRefusesStop();
        riderStop();
        System.out.println("RideAutoEndsTest passed " + checks + " checks");
    }

    private static RideStore store(File dir) throws Exception { return new RideStore(dir, true, null); }
    private static File dir() throws Exception { return Files.createTempDirectory("ride-auto-ends").toFile(); }
    private static String startRide(RideStore rides) throws Exception { rides.start("p1", "Synthetic ride", 1_000_000, 1000, "UTC"); return rides.activeId(); }
    private static void stop(RideStore rides, String reason) throws Exception { rides.stop(1_010_000, 11_000, reason); }

    private static void clean() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        String id = startRide(rides);
        ends.stopping(id); stop(rides, REASON); ends.stopped();
        check(ends.count() == 1 && prefs.pending == null, "a clean automatic end counts once");
    }

    /** Interrupted before the STOP row: the recording recovers on the next load, and this was no end. */
    private static void deathBeforeJournalStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        String id = startRide(rides);
        new RideAutoEnds(prefs).stopping(id);
        RideStore reloaded = store(dir); RideAutoEnds ends = new RideAutoEnds(prefs);
        check(id.equals(reloaded.activeId()), "the unfinished journal recovers");
        ends.reconcile(reloaded.activeId(), reloaded::finished);
        check(ends.count() == 0 && prefs.pending == null, "and no automatic end is counted, so riding mode stays open");
        ends.stopping(id); stop(reloaded, REASON); ends.stopped();
        check(ends.count() == 1, "its real end later still counts");
    }

    /** Interrupted after the STOP row, before the count: the journal says it ended, and riding mode must not reopen. */
    private static void deathAfterJournalStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        String id = startRide(rides);
        new RideAutoEnds(prefs).stopping(id);
        stop(rides, REASON);
        RideStore reloaded = store(dir); RideAutoEnds ends = new RideAutoEnds(prefs);
        check(reloaded.activeId() == null && reloaded.finished(id), "the journal finished");
        ends.reconcile(reloaded.activeId(), reloaded::finished);
        check(ends.count() == 1 && prefs.pending == null, "the load counts the automatic end");
        ends.reconcile(reloaded.activeId(), reloaded::finished);
        check(ends.count() == 1, "only once");
    }

    /** The count's own write fails: this process still knows, and the next load recovers it from the pending intent. */
    private static void failedCountWrite() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        String id = startRide(rides);
        ends.stopping(id);
        stop(rides, REASON);
        prefs.failing = true;
        ends.stopped();
        check(ends.count() == 1 && prefs.count == 0 && id.equals(prefs.pending), "after a failed write it still reads 1 in this process");
        prefs.failing = false;
        RideStore reloaded = store(dir); RideAutoEnds next = new RideAutoEnds(prefs);
        next.reconcile(reloaded.activeId(), reloaded::finished);
        check(next.count() == 1 && prefs.count == 1 && prefs.pending == null, "and the next load saves it exactly once");
    }

    /** Only the intent's write fails: the count after the journal stop stands on its own. */
    private static void failedIntentWrite() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        String id = startRide(rides);
        prefs.failingOnce = true;
        ends.stopping(id);
        check(prefs.pending == null, "the intent didn't save");
        stop(rides, REASON); ends.stopped();
        check(ends.count() == 1 && prefs.count == 1, "the count still lands");
    }

    /** Nothing can be written at all: this process still closes riding mode, and a later save picks it up. */
    private static void bothWritesFail() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        String id = startRide(rides);
        prefs.failing = true;
        ends.stopping(id); stop(rides, REASON); ends.stopped();
        check(ends.count() == 1 && prefs.count == 0, "a failed write still counts here");
        prefs.failing = false;
        String second = startRide(rides);
        ends.stopping(second); stop(rides, REASON); ends.stopped();
        check(ends.count() == 2 && prefs.count == 2 && prefs.pending == null, "and the next good save includes it");
    }

    /** The journal refused the STOP: no end, and a later stop by the rider isn't counted as automatic. */
    private static void journalRefusesStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs(); RideAutoEnds ends = new RideAutoEnds(prefs);
        String id = startRide(rides);
        ends.stopping(id); ends.stopFailed();
        check(ends.count() == 0 && prefs.pending == null, "a refused stop is no end");
        stop(rides, "");
        RideStore reloaded = store(dir); RideAutoEnds next = new RideAutoEnds(prefs);
        next.reconcile(reloaded.activeId(), reloaded::finished);
        check(next.count() == 0, "the rider's later stop isn't counted");
    }

    /** The rider's own stop writes no intent, so a load finds nothing to count. */
    private static void riderStop() throws Exception {
        File dir = dir(); RideStore rides = store(dir); Prefs prefs = new Prefs();
        startRide(rides);
        stop(rides, "");
        RideStore reloaded = store(dir); RideAutoEnds ends = new RideAutoEnds(prefs);
        ends.reconcile(reloaded.activeId(), reloaded::finished);
        check(ends.count() == 0, "a rider's stop isn't an automatic end");
    }

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
}
