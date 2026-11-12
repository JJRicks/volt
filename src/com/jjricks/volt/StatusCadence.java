package com.jjricks.volt;

/**
 * How often BleController asks the scooter for its status (0x70), and how old the last answer may be before the
 * link counts as gone. Pure and Android-free, on the monotonic clock.
 *
 * With Volt open or a ride or charge recording (fast), it asks every 2 seconds; a status read older than
 * READY_AGE_MS makes BleController.ready() false (controls refuse), and SILENCE_FAST_MS of silence ends the
 * link. Out of sight it asks every 15 seconds and allows SILENCE_SLOW_MS of silence, so a 7 second window would
 * call a healthy background link lost for about 8 seconds of every 15. linked() judges the status age against
 * the cadence the link is on: the fast window in the foreground (the same answer as ready()), the background
 * silence limit out of sight.
 *
 * A switch from slow to fast (Volt comes into sight, a recording or a hold starts) does not make a status read from a
 * 15 s poll answer to the in-sight rule at once: decisions that judge the link (the connect timeout, the silence limit,
 * linked(), reading freshness) use the cadence the link has actually been on (within(), fastSinceMs), and the poll chain
 * (Chain) moves to the new cadence at once. ready()'s plain 7 s, which admits controls, is never loosened.
 *
 * Two clocks. Ages are on the elapsed clock (SystemClock.elapsedRealtime), which keeps counting while the phone is in
 * deep sleep; the poll chain runs on the main-thread Handler, whose timers wait on the awake clock (uptimeMillis), which
 * stops. With nothing holding a wake lock (Volt out of sight, no recording), the phone can sleep for hours with a poll
 * pending, so on waking the last answer is old on the elapsed clock although Volt never had the time to ask again.
 * The rule: silence that judges the link (the silence limit, the connect timeout, linked()) counts only awake time, so
 * after any sleep the link gets exactly what it would have had if the phone had stayed awake (awakeStamp(), Chain.linked(),
 * Chain.silent()). Only an answer moves those stamps; a request never does. A switch to fast opens a fresh fast window,
 * never past the background limit, and BleController checks silence at every switch as well as at every poll, so no
 * sequence of requests, switches or sleeps keeps a silent link past the limits in awake time. Everything that admits a control or shows a value as live (ready(), statusAge(), readingFreshMs()) stays
 * on the elapsed clock: a status from before a sleep is still not ready and a reading from before it is still not live.
 */
public final class StatusCadence {
    private StatusCadence() {}

    public static final long POLL_FAST_MS = 2_000L, POLL_SLOW_MS = 15_000L;
    public static final long SILENCE_FAST_MS = 12_000L, SILENCE_SLOW_MS = 35_000L;
    /** BleController.ready()'s own bound (CapabilityResolver.STATUS_MAX_AGE_MS). */
    public static final long READY_AGE_MS = 7_000L;

    /** The status request's period. */
    public static long pollMs(boolean fast) { return fast ? POLL_FAST_MS : POLL_SLOW_MS; }
    /** Silence (no frame, or no status) past this ends the link. */
    public static long silenceMs(boolean fast) { return fast ? SILENCE_FAST_MS : SILENCE_SLOW_MS; }
    /** How old the last status read may be while the link still counts as up on this cadence. */
    public static long linkedAgeMs(boolean fast) { return fast ? READY_AGE_MS : SILENCE_SLOW_MS; }

    /** Out of sight, how much older than its own rule a reading may be: one background poll's reply jitter. */
    public static final long READING_MARGIN_MS = 5_000L;
    /**
     * How old a reading may be while it still counts as live. In sight (fast) it is the reading's own rule, unchanged.
     * Out of sight a reading is only refreshed once per POLL_SLOW_MS, so it may be one background poll period plus
     * READING_MARGIN_MS old; a rule that is already longer (settings, device facts) stays as it is.
     */
    public static long readingFreshMs(long baseMs, boolean fast) {
        return fast ? baseMs : Math.max(baseMs, POLL_SLOW_MS + READING_MARGIN_MS);
    }

    /**
     * The link is up on this cadence. up: everything else ready() requires (work allowed, READY, authenticated,
     * a GATT client and a serial); statusMs: when the last status read arrived, 0 for none.
     */
    public static boolean linked(boolean up, long statusMs, long nowMono, boolean fast) {
        return up && statusMs > 0 && nowMono - statusMs <= linkedAgeMs(fast);
    }

    /**
     * A time stamped on the elapsed clock (stampMs; 0 for none), moved later by the deep sleep since it: sleptAtStampMs and
     * sleptNowMs are the elapsed clock minus the awake clock then and now. Its age on the elapsed clock is then its age
     * in awake time, which is the time the poll chain could have asked and the scooter's answer could have been handled.
     * Never earlier than the stamp; 0 stays 0.
     */
    public static long awakeStamp(long stampMs, long sleptAtStampMs, long sleptNowMs) {
        return stampMs <= 0 ? 0 : stampMs + Math.max(0, sleptNowMs - sleptAtStampMs);
    }

    // ---- The cadence the link has actually been on --------------------------------------------------------------

    /**
     * A time-stamped fact (a status read, any frame) judged against the cadence the link has actually been on. On the
     * slow cadence: no older than slowMs. On the fast one: no older than fastMs, counted from the later of the fact and
     * fastSinceMs (when the cadence last became fast, 0 for never), and never older than slowMs. So a fact that arrived
     * after the switch keeps the fast rule exactly, and one that could only come from a 15 s poll is not judged by the
     * 2 s rule until the fast cadence has had fastMs to refresh it.
     */
    public static boolean within(long sinceMs, long nowMono, boolean fast, long fastSinceMs, long fastMs, long slowMs) {
        if (sinceMs <= 0 || nowMono - sinceMs > slowMs) return false;
        return !fast || nowMono - Math.max(sinceMs, fastSinceMs) <= fastMs;
    }
    /**
     * The link is up on the cadence it has been on: linked(up, statusMs, nowMono, fast) once a status has arrived on
     * the fast cadence (or it never switched, fastSinceMs 0), else up to READY_AGE_MS after the switch to fast while the
     * last status still meets the slow rule. For decisions that judge the link (time a connection out, call it lost,
     * interrupt work in flight); never for controls, which keep ready()'s plain 7 s.
     */
    public static boolean linked(boolean up, long statusMs, long nowMono, boolean fast, long fastSinceMs) {
        return up && within(statusMs, nowMono, fast, fastSinceMs, READY_AGE_MS, linkedAgeMs(false));
    }
    /** Silence (since: the last frame, or the last status read) that ends the link, judged the same way. 0: nothing yet. */
    public static boolean silent(long sinceMs, long nowMono, boolean fast, long fastSinceMs) {
        return sinceMs > 0 && !within(sinceMs, nowMono, fast, fastSinceMs, SILENCE_FAST_MS, SILENCE_SLOW_MS);
    }
    /**
     * readingFreshMs(baseMs, fast), with a reading taken before the switch to fast judged on the slow cadence: the
     * background allowance until baseMs has passed since the switch, then the reading's own rule. Exactly
     * within(read, now, fast, fastSinceMs, baseMs, readingFreshMs(baseMs, false)) for every reading time.
     */
    public static long readingFreshMs(long baseMs, boolean fast, long fastSinceMs, long nowMono) {
        return fast && (fastSinceMs <= 0 || nowMono - fastSinceMs > baseMs) ? baseMs : readingFreshMs(baseMs, false);
    }
    /**
     * After a cadence change, how long until the next status poll: one period of the new cadence after the last status
     * request (any 0x70: a poll, the resume refresh, a check), and never later than one period from now. 0: poll now.
     */
    public static long nextPollMs(long lastRequestMs, long nowMono, boolean fast) {
        long period = pollMs(fast);
        if (lastRequestMs <= 0) return 0;
        return Math.max(0, Math.min(period, lastRequestMs + period - nowMono));
    }

    /** BleController's main-thread Handler; tests use a fake clock. */
    public interface Timer {
        /** The elapsed clock (SystemClock.elapsedRealtime): ages, counting deep sleep. */
        long now();
        /** The awake clock (SystemClock.uptimeMillis): what post() delays wait on; it stops during deep sleep. */
        long awake();
        void post(Runnable task, long delayMs);
        void cancel(Runnable task);
    }

    /**
     * One session's status poll chain and the cadence it is on. At most one poll is pending at any time: every arm
     * cancels the one before it. cadence() records a switch (and when it became fast); retime() then moves the pending
     * poll to the new cadence at once, anchored to the last status request so a refresh just sent is not doubled.
     * A retimed poll that finds a newer request when it fires (a ride start's refresh) waits once for one period after
     * that request; every later poll runs one period after the last. stop() ends the chain (close, suspend, simulation).
     * The chain's own times (the last request, when a poll was armed) are on the awake clock, the one its timers wait on.
     *
     * linked() and silent() judge the link on awake time (StatusCadence.awakeStamp): each stamp comes with the deep sleep
     * total when it was taken (slept()), so sleep never counts as silence. They are for decisions about the link only.
     */
    public static final class Chain {
        private final Timer timer;
        private boolean fast;
        private long fastSinceMs, fastSinceSleptMs, lastRequestMs;
        private Runnable tick, pending;
        public Chain(Timer timer) { this.timer = timer; }

        public boolean fast() { return fast; }
        /** When the cadence last became fast (monotonic), 0 for never. */
        public long fastSinceMs() { return fastSinceMs; }
        /** When the last status request was queued, on the awake clock; 0 for none. */
        public long lastRequestMs() { return lastRequestMs; }
        /** Deep sleep so far: the elapsed clock minus the awake clock. It grows only while the phone sleeps. */
        public long slept() { return timer.now() - timer.awake(); }
        /**
         * The link is up on the cadence it has been on (StatusCadence.linked with fastSinceMs), with the time the phone
         * slept since the status (statusSleptMs: slept() when statusMs was taken) and since the switch cut out.
         */
        public boolean linked(boolean up, long statusMs, long statusSleptMs) {
            long slept = slept();
            return StatusCadence.linked(up, awakeStamp(statusMs, statusSleptMs, slept), timer.now(), fast, awakeStamp(fastSinceMs, fastSinceSleptMs, slept));
        }
        /** Silence since sinceMs (the last frame or status; slept() then) that ends the link, in awake time. 0: nothing yet. */
        public boolean silent(long sinceMs, long sinceSleptMs) {
            long slept = slept();
            return StatusCadence.silent(awakeStamp(sinceMs, sinceSleptMs, slept), timer.now(), fast, awakeStamp(fastSinceMs, fastSinceSleptMs, slept));
        }
        /** A poll is pending (the chain is running). */
        public boolean running() { return pending != null; }

        /** The cadence is fast (in sight, or recording or holding) or not; true when that changed. Does not move the poll. */
        public boolean cadence(boolean nowFast) {
            if (nowFast == fast) return false;
            fast = nowFast;
            if (nowFast) { fastSinceMs = timer.now();fastSinceSleptMs = slept(); }
            return true;
        }
        /** A status request (0x70) was queued now. */
        public void requested() { lastRequestMs = timer.awake(); }
        /** Starts this session's chain (replacing any other): tick runs one period of the current cadence from now. */
        public void start(Runnable tick) { this.tick = tick; arm(pollMs(fast), false); }
        /** The tick polled and the chain goes on: one period of the current cadence from now. */
        public void next() { if (tick != null) arm(pollMs(fast), false); }
        /** After cadence() changed: the pending poll moves to the new cadence (nothing to move if the chain is not running). */
        public void retime() { if (pending != null) arm(nextPollMs(lastRequestMs, timer.awake(), fast), true); }
        public void stop() { if (pending != null) timer.cancel(pending); pending = null; tick = null; }

        private void arm(long delayMs, boolean settle) {
            if (pending != null) timer.cancel(pending);
            final long armedAt = timer.awake();
            Runnable task = new Runnable() {
                @Override public void run() {
                    if (pending != this) return;
                    pending = null;
                    if (settle && lastRequestMs >= armedAt) {
                        long wait = nextPollMs(lastRequestMs, timer.awake(), fast);
                        if (wait > 0) { arm(wait, false); return; }
                    }
                    Runnable t = tick;
                    if (t != null) t.run();
                }
            };
            pending = task;
            timer.post(task, Math.max(0, delayMs));
        }
    }
}
