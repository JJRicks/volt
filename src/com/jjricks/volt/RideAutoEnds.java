package com.jjricks.volt;

import java.util.function.Predicate;

/**
 * The count of recordings that ended by themselves, kept recoverable together with the journal's STOP row.
 * The stop writes its intent first, then the journal, then the count: a process death between any two is
 * reconciled against the journal when the service next loads. Android-free, so the JVM test can interrupt
 * it at each step and fail any write.
 */
final class RideAutoEnds {
    /** The saved count and pending ride id (null clears it), written together; false when the write failed. */
    interface Store { long count(); String pending(); boolean save(long count, String pending); }

    private final Store store;
    /** Automatic ends this process couldn't save; a later successful save takes them with it. */
    private long unsaved;

    RideAutoEnds(Store store) { this.store = store; }

    long count() { return store.count() + unsaved; }

    /** Before the journal stops: if the process dies now, the journal will say what happened. */
    void stopping(String rideId) { store.save(store.count(), rideId); }

    /** The journal refused the stop: the recording goes on and may yet be the rider's to stop. */
    void stopFailed() { store.save(store.count(), null); }

    /** The journal has stopped. */
    void stopped() {
        if (store.save(count() + 1, null)) unsaved = 0;
        else unsaved++;
    }

    /** When the service loads: a pending stop whose journal finished happened, one still recording did not. */
    void reconcile(String activeId, Predicate<String> finished) {
        String pending = store.pending();
        if (pending == null) return;
        boolean ended = !pending.equals(activeId) && finished.test(pending);
        if (store.save(store.count() + (ended ? 1 : 0), null)) return;
        if (ended) unsaved++;
    }
}
