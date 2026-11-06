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
 * dialog. No push service, no Firebase.
 *
 * The job is not persisted, so Volt needs no boot permission: VoltApp re-schedules it (sync) whenever Volt's process
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
            // JobScheduler can refuse (too many jobs); the switch stays as it is and the next start tries again.
        }
    }
    /** The next check: once the day since the last one has passed, on any network. */
    static void schedule(Context context, SharedPreferences settings) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        long wait = UpdateSchedule.wait(settings.getLong(UpdateSchedule.LAST, 0), System.currentTimeMillis());
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(wait).build());
    }
    static void cancel(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB_ID);
    }

    @Override public boolean onStartJob(JobParameters params) {
        SharedPreferences settings = settings(this);
        if (!enabled(settings)) return false;
        Context context = getApplicationContext();
        Thread worker = new Thread(() -> {
            if (UpdateSchedule.due(settings.getLong(UpdateSchedule.LAST, 0), System.currentTimeMillis())) check(context, settings);
            jobFinished(params, false);
            if (enabled(settings)) schedule(context, settings);
        }, "volt_update_job");
        worker.setDaemon(true); worker.start();
        return true;
    }
    /** Stopped mid-check (the network went away): JobScheduler retries; a finished check is never repeated within the day. */
    @Override public boolean onStopJob(JobParameters params) { return true; }

    /** One background check: the manual check's request and reading, then at most one notification per new version. */
    static void check(Context context, SharedPreferences settings) {
        long now = System.currentTimeMillis();
        String installed = AppVersion.name(context);
        UpdateCheck.Result result;
        try { result = UpdateCheck.evaluate(installed, UpdateCheck.fetcher.get(UpdateCheck.ENDPOINT, UpdateCheck.userAgent(installed))); }
        catch (java.io.IOException | RuntimeException unreachable) { result = null; }
        settings.edit().putLong(UpdateSchedule.LAST, now).apply();
        if (result == null || result.outcome != UpdateCheck.Outcome.AVAILABLE) return;
        String told = settings.getString(UpdateSchedule.TOLD, "");
        if (UpdateSchedule.announce(result.latest, told) && notifyAvailable(context, result.latest)) seen(settings, result.latest);
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
