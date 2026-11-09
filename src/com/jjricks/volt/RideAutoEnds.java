package com.jjricks.volt;

/**
 * The count of recordings that ended by themselves, which a riding mode compares with the count when it
 * opened. The journal's STOP row is the record of an automatic end: its reason says automatic or the
 * rider's, and it is on disk before anything here is. So the stop writes only the count, and a load counts
 * any automatic STOP newer than the last one counted, so a death or failed write between the two can't lose
 * the end or count it twice. Android-free, so the JVM test can interrupt it at each step and fail any write.
 */
final class RideAutoEnds {
    /**
     * The saved count and the wall time of the newest automatic STOP counted, written together. As with
     * SharedPreferences' commit, a save that fails on disk still changes what they read in this process,
     * and the next save that reaches the disk carries it.
     */
    interface Store { long count(); long through(); void save(long count, long through); }

    private final Store store;
    RideAutoEnds(Store store) { this.store = store; }

    long count() { return store.count(); }

    /** The journal has written an automatic STOP at this wall time. */
    void stopped(long stopWall) { store.save(store.count() + 1, Math.max(store.through(), stopWall)); }

    /** When the service loads: counts the journals' automatic STOPs newer than the last one counted. */
    void reconcile(long[] automaticStops) {
        long through = store.through(), newest = through;
        int found = 0;
        for (long stop : automaticStops) if (stop > through) { found++; newest = Math.max(newest, stop); }
        if (found > 0) store.save(store.count() + found, newest);
    }
}
