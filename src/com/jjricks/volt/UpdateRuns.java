package com.jjricks.volt;

/**
 * Who owns an automatic update check's side effects, kept pure for the JVM tests (UpdateRunsTest). Each job run
 * gets a token. A run is live only while it is the newest run, was not stopped, was not revoked by the owner
 * turning the check off, and the setting is still on. A run that is no longer live sends no request, saves no
 * result, posts no notice, never reports itself finished and schedules nothing. The manual Check for updates
 * does not go through here.
 *
 * The request is admitted under the lock revoke and stop take: a revoke or stop before admission means no request;
 * one after it is ordered after it, closes the request through its abort hook (outside the lock) and voids its
 * result. The lock is never held across the network, so the switch's cancel on the UI thread never waits on it.
 */
final class UpdateRuns {
    /** What a run touches; UpdateCheckJob backs it with app_settings, GitHub and the notification manager. */
    interface Host {
        /** The switch and the GitHub consent are both on. */
        boolean enabled();
        long last();
        /**
         * The manual check's request, for an admitted run: the newer release's version, null when there is none;
         * throws when unreachable or aborted. It hands its connection's close to run.onAbort before connecting.
         */
        String newer(Run run) throws Exception;
        void saveLast(long now);
        String told();
        /** Posts the notice; false when Android would not show it. */
        boolean post(String latest);
        void seen(String latest);
    }

    /** One job run. */
    static final class Run {
        final long id;
        private final UpdateRuns owner;
        private boolean stopped;
        private Runnable abort;
        private Run(UpdateRuns owner, long id) { this.owner = owner; this.id = id; }
        /**
         * The request's abort hook (closing its connection), run if the run is revoked or stopped while the request
         * is out. False when the run is already void: the request must not go out.
         */
        boolean onAbort(Runnable hook) { return owner.abortWith(this, hook); }
    }

    private final Host host;
    private long newest;
    /** The admitted run whose request is out, if any. */
    private Run inFlight;
    UpdateRuns(Host host) { this.host = host; }

    /** A new job run; any older run loses its side effects. */
    synchronized Run start() { return new Run(this, ++newest); }
    /** JobScheduler stopped this run (onStopJob). True when a retry is wanted: only while the check is still on. */
    boolean stop(Run run) {
        Runnable abort; boolean retry;
        synchronized (this) { run.stopped = true; abort = takeAbort(run); retry = host.enabled(); }
        if (abort != null) abort.run();
        return retry;
    }
    /** The owner turned the check off: every run so far is revoked, before Android even stops it. Never waits on the network. */
    void revoke() {
        Runnable abort;
        synchronized (this) { newest++; abort = inFlight == null ? null : takeAbort(inFlight); }
        if (abort != null) abort.run();
    }
    synchronized boolean live(Run run) { return !run.stopped && run.id == newest && host.enabled(); }
    /** The run ended: true when it is still the job's own run, so it may call jobFinished and schedule the next. */
    synchronized boolean finish(Run run) { return !run.stopped && run.id == newest; }

    private synchronized boolean abortWith(Run run, Runnable hook) {
        if (run != inFlight || !live(run)) return false;
        run.abort = hook;
        return true;
    }
    private Runnable takeAbort(Run run) { Runnable abort = run.abort; run.abort = null; return abort; }

    /** The check itself, on the job's worker thread: a request at most once a day, a notice at most once per version. */
    void run(Run run, long now) {
        long last = host.last();
        synchronized (this) {
            // Admission: the live check and the in-flight mark are one step, so a revoke or stop is either before it
            // (no request) or after it (it reaches this request's abort hook).
            if (!live(run) || !UpdateSchedule.due(last, now)) return;
            inFlight = run;
        }
        String newer;
        try { newer = host.newer(run); } catch (Exception unreachable) { newer = null; }
        synchronized (this) {
            if (inFlight == run) inFlight = null;
            run.abort = null;
            // Revoke and stop take this lock too, so a run that passes here can't lose a race with them.
            if (!live(run)) return;
            host.saveLast(now);
            if (newer != null && UpdateSchedule.announce(newer, host.told()) && host.post(newer)) host.seen(newer);
        }
    }
}
