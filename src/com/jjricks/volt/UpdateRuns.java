package com.jjricks.volt;

/**
 * Who owns an automatic update check's side effects, kept pure for the JVM tests (UpdateRunsTest). Each job run
 * gets a token. A run is live only while it is the newest run, was not stopped, was not revoked by the owner
 * turning the check off, and the setting is still on. A run that is no longer live sends no request, saves no
 * result, posts no notice, never reports itself finished and schedules nothing. The manual Check for updates
 * does not go through here.
 */
final class UpdateRuns {
    /** What a run touches; UpdateCheckJob backs it with app_settings, GitHub and the notification manager. */
    interface Host {
        /** The switch and the GitHub consent are both on. */
        boolean enabled();
        long last();
        /** The manual check's request: the newer release's version, null when there is none; throws when unreachable. */
        String newer() throws Exception;
        void saveLast(long now);
        String told();
        /** Posts the notice; false when Android would not show it. */
        boolean post(String latest);
        void seen(String latest);
    }

    /** One job run. */
    static final class Run {
        final long id;
        private boolean stopped;
        private Run(long id) { this.id = id; }
    }

    private final Host host;
    private long newest;
    UpdateRuns(Host host) { this.host = host; }

    /** A new job run; any older run loses its side effects. */
    synchronized Run start() { return new Run(++newest); }
    /** JobScheduler stopped this run (onStopJob). True when a retry is wanted: only while the check is still on. */
    synchronized boolean stop(Run run) { run.stopped = true; return host.enabled(); }
    /** The owner turned the check off: every run so far is revoked, before Android even stops it. */
    synchronized void revoke() { newest++; }
    synchronized boolean live(Run run) { return !run.stopped && run.id == newest && host.enabled(); }
    /** The run ended: true when it is still the job's own run, so it may call jobFinished and schedule the next. */
    synchronized boolean finish(Run run) { return !run.stopped && run.id == newest; }

    /** The check itself, on the job's worker thread: a request at most once a day, a notice at most once per version. */
    void run(Run run, long now) {
        if (!live(run) || !UpdateSchedule.due(host.last(), now)) return;
        String newer;
        try { newer = host.newer(); } catch (Exception unreachable) { newer = null; }
        synchronized (this) {
            // Revoke and stop take this lock too, so a run that passes here can't lose a race with them.
            if (!live(run)) return;
            host.saveLast(now);
            if (newer != null && UpdateSchedule.announce(newer, host.told()) && host.post(newer)) host.seen(newer);
        }
    }
}
