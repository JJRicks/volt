package com.jjricks.volt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The opt-in automatic update check (More › Updates), off by default. One JobScheduler job that needs a network
 * and runs at most once a day (UpdateSchedule). It sends the same single request as the manual check
 * (UpdateCheck.ENDPOINT, Volt's User-Agent) and nothing else. A newer release is announced once per version with a
 * quiet notification on its own low-importance channel; tapping it opens MainActivity, which runs the manual check's
 * dialog. No push service, no Firebase. UpdateRuns owns each run's side effects: a stopped run, or one the owner
 * turned off mid-check, sends, saves and posts nothing more.
 *
 * Its network constraint needs ACCESS_NETWORK_STATE, an install-time permission with no prompt. The job is not
 * persisted, so Volt needs no boot permission: VoltApp re-schedules it (sync) whenever Volt's process
 * starts, so after a phone restart the daily check resumes the next time Volt runs.
 */
public final class UpdateCheckJob extends JobService {
    static final int JOB_ID = 5626001;
    static final String CHANNEL = "volt_updates";
    /** MainActivity: the notification was tapped; open the update check. */
    static final String EXTRA = "volt_update_found";
    private static final int NOTICE = 91, TAP = 91;

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }
    /** On only with both the switch and the owner's consent to ask GitHub. */
    static boolean enabled(SharedPreferences settings) {
        return settings.getBoolean(UpdateSchedule.AUTO, false) && settings.getBoolean(UpdateCheck.CONSENT, false);
    }

    /** Process start: schedule the job when the check is on and none is waiting, cancel it when off. Never throws. */
    static void sync(Context context) {
        try {
            SharedPreferences settings = settings(context);
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            if (!enabled(settings)) jobs.cancel(JOB_ID);
            else if (jobs.getPendingJob(JOB_ID) == null) schedule(context, settings);
        } catch (RuntimeException unavailable) {
            // As in schedule: the next start tries again.
        }
    }
    /** The next check: once the day since the last one has passed, on any network (ACCESS_NETWORK_STATE, no prompt). Never throws. */
    static void schedule(Context context, SharedPreferences settings) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        long wait = UpdateSchedule.wait(settings.getLong(UpdateSchedule.LAST, 0), System.currentTimeMillis());
        try {
            jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(wait).build());
        } catch (RuntimeException refused) {
            // JobScheduler can refuse (too many jobs); the switch stays on and the next process start tries again (sync).
        }
    }
    /** The switch turned off: a run already going loses its request, result and notice at once, then the job goes. */
    static void cancel(Context context) {
        runs(context).revoke();
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB_ID);
    }

    private static UpdateRuns runs;
    /** One owner for every run in this process, so a revoke or a newer run reaches a worker still going. */
    static synchronized UpdateRuns runs(Context context) {
        if (runs == null) runs = new UpdateRuns(new Checks(context.getApplicationContext()));
        return runs;
    }
    /** UpdateRuns' view of app_settings, GitHub (the manual check's request) and the notification manager. */
    private static final class Checks implements UpdateRuns.Host {
        private final Context context;
        Checks(Context context) { this.context = context; }
        @Override public boolean enabled() { return UpdateCheckJob.enabled(settings(context)); }
        @Override public long last() { return settings(context).getLong(UpdateSchedule.LAST, 0); }
        @Override public String newer(UpdateRuns.Run run) throws Exception {
            String installed = AppVersion.name(context);
            // A revoke or stop while the request is out closes its connection; one before it connects stops it.
            UpdateCheck.opened.set(connection -> {
                if (!run.onAbort(connection::disconnect)) throw new java.util.concurrent.CancellationException();
            });
            try {
                UpdateCheck.Result result = UpdateCheck.evaluate(installed, UpdateCheck.fetcher.get(UpdateCheck.ENDPOINT, UpdateCheck.userAgent(installed)));
                return result.outcome == UpdateCheck.Outcome.AVAILABLE ? result.latest : null;
            } finally { UpdateCheck.opened.remove(); }
        }
        @Override public void saveLast(long now) { settings(context).edit().putLong(UpdateSchedule.LAST, now).apply(); }
        @Override public String told() { return settings(context).getString(UpdateSchedule.TOLD, ""); }
        @Override public boolean post(String latest) { return notifyAvailable(context, latest); }
        @Override public void seen(String latest) { UpdateCheckJob.seen(settings(context), latest); }
    }

    /** This service's current run; onStopJob hands it back to UpdateRuns. */
    private UpdateRuns.Run run;

    @Override public boolean onStartJob(JobParameters params) {
        if (!enabled(settings(this))) return false;
        Context context = getApplicationContext();
        UpdateRuns owner = runs(context);
        UpdateRuns.Run mine = owner.start();
        run = mine;
        Thread worker = new Thread(() -> {
            owner.run(mine, System.currentTimeMillis());
            // A stopped, revoked or replaced run leaves jobFinished and the next schedule to the run that owns them.
            if (!owner.finish(mine)) return;
            jobFinished(params, false);
            if (enabled(settings(context))) schedule(context, settings(context));
        }, "volt_update_job");
        worker.setDaemon(true); worker.start();
        return true;
    }
    /** Stopped mid-check (the network went away, or the switch turned off): the run is void; a retry only while still on. */
    @Override public boolean onStopJob(JobParameters params) {
        UpdateRuns.Run stopped = run;
        return stopped != null && runs(this).stop(stopped);
    }

    /** The owner saw this version (a notice, or the manual check's dialog): it is not announced again. */
    static void seen(SharedPreferences settings, String latest) {
        settings.edit().putString(UpdateSchedule.TOLD, UpdateSchedule.told(latest, settings.getString(UpdateSchedule.TOLD, ""))).apply();
    }

    /** Posts the quiet notice; false when Android would not show it, so the next day's check can try again. */
    static boolean notifyAvailable(Context context, String latest) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        channel(manager);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        Intent open = new Intent(context, MainActivity.class).putExtra(EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, TAP, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTICE, new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_volt_notification)
                .setContentTitle(T.moreUpdateAvailable(latest)).setContentText(T.moreUpdatesNotice())
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION).setVisibility(Notification.VISIBILITY_PUBLIC).build());
        return true;
    }
    /** Creates the channel, or renames it in the current language (Android keeps the owner's own channel settings). */
    private static void channel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, T.moreUpdatesChannel(), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(T.moreUpdatesChannelDescription()); channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
    /** A language change: re-label the channel if it exists. */
    static void relabel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL) != null) channel(manager);
    }
    /** MainActivity: this intent came from the notice. */
    static boolean opened(Intent intent) { return intent != null && intent.getBooleanExtra(EXTRA, false); }
}
