package com.jjricks.volt;

/**
 * When a control waiting on the scooter shows its pending spinner (owner, Nov 23): a control the rider changed that the
 * scooter hasn't confirmed yet. Nothing new for the first {@link #DELAY_MS}, so a fast scooter never flickers; then a
 * spinner over the control until the wait ends. The wait is the caller's own state (BleController.pending(), the lock's
 * LockTapQueue, a charge schedule's busy flag): the scooter's readback, the existing command timeout, a refusal or a
 * dropped link end it, and the next look stops the spinner. Nothing here ends a wait, retries or decides a value.
 *
 * <p>Each control keeps one and looks at it on every refresh with whether it waits and since when. Given the wait's own
 * start (the tap, or the write's start), a control rebuilt mid-wait (fold, unfold, a theme change) keeps the timing
 * instead of starting a new second. {@link #LIMIT_MS} is only a backstop past every existing timeout, so a wait that
 * something forgot to end can't spin for ever. Pure Java, clock passed in (tests/PendingControlTest).
 */
final class PendingControl {
    /** How long a wait goes unmarked: a confirmation inside it shows nothing new. */
    static final long DELAY_MS = 1000;
    /**
     * Never spin longer than this: past the lock's longest wait (LockTapQueue.HOLD_MS before it goes out, then the 3.5 s
     * write and 6.5 s confirmation windows in BleController), and every other control's (no hold).
     */
    static final long LIMIT_MS = 20_000;
    /** Which wait each control last announced (control key to the wait's start), so a screen reader hears it once. */
    private static final java.util.HashMap<String, Long> announced = new java.util.HashMap<>();

    /** The start of the wait this control is in (elapsed ms), or 0 when it isn't waiting. */
    private long since;
    /** The latest write of that wait seen so far (elapsed ms), or 0: the backstop counts from it. */
    private long latest;

    /**
     * One look at the control.
     * @param waiting whether it waits on the scooter right now
     * @param startedMs when that wait began (elapsed ms), or 0 when the caller can't say: then from the first look
     * @param now the elapsed clock
     */
    void look(boolean waiting, long startedMs, long now) { look(waiting, startedMs, 0, now); }

    /**
     * One look at a wait of several writes (a charge schedule: clock, time, switch).
     * @param latestMs when its current write began (elapsed ms), or 0 between writes: the backstop counts from the latest
     *                 write seen, so a long but healthy operation isn't cut short; the second still counts from startedMs
     */
    void look(boolean waiting, long startedMs, long latestMs, long now) {
        if (!waiting) { since = 0; latest = 0; return; }
        long start = startedMs > 0 ? Math.min(startedMs, now) : since > 0 ? since : now;
        if (start != since) latest = 0;
        since = start;
        if (latestMs > 0) latest = Math.max(latest, Math.min(latestMs, now));
    }

    /** Whether the spinner shows now. */
    boolean showing(long now) {
        if (since <= 0) return false;
        return now - since >= DELAY_MS && now - backstopFrom() < LIMIT_MS;
    }

    /** How long until the spinner should start (or the backstop stop it), for a redraw then; -1 when nothing is due. */
    long nextLookIn(long now) {
        if (since <= 0) return -1;
        long age = now - since, last = now - backstopFrom();
        return age < DELAY_MS ? DELAY_MS - age : last < LIMIT_MS ? LIMIT_MS - last : -1;
    }

    private long backstopFrom() { return Math.max(since, latest); }

    /**
     * True once per wait of this control key, on the first look its spinner shows: the moment to tell a screen reader
     * that the scooter hasn't answered yet (ScreenHost.pendingWords). Another view of the same control (a second lock
     * switch on two panes) gets false.
     */
    boolean announce(String key, long now) {
        if (!showing(now)) return false;
        synchronized (announced) {
            Long last = announced.get(key);
            if (last != null && last == since) return false;
            announced.put(key, since);
            return true;
        }
    }

    /** Tests only: start with no wait announced. */
    static void forgetAnnouncements() { synchronized (announced) { announced.clear(); } }
}
