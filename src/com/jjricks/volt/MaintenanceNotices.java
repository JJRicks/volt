package com.jjricks.volt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Posts and removes maintenance notices on the {@link MaintenanceNotify#CHANNEL} channel, and plans the
 * daily {@link MaintenanceJobService} run (maintenance plan, PR 3). Text is rendered at each post from the
 * reminders, so a notice is in the language shown when it posts. Tapping a notice opens the Maintenance page.
 */
final class MaintenanceNotices implements MaintenanceNotify.Poster {
    /** One notice per scooter: this id, tagged with the scope. ScooterService's own notices use 71 to 79. */
    static final int NOTICE = 80;
    static final int JOB = 8_001;
    static final String SETTINGS = "app_settings";
    private final Context context;
    private final ScooterProfiles profiles;

    MaintenanceNotices(Context context, ScooterProfiles profiles) { this.context = context; this.profiles = profiles; }

    @Override public void post(String scope, List<MaintenancePolicy.Reminder> due) {
        if (due.isEmpty()) return;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            channel(manager);
            String name = scooterName(scope);
            int n = due.size();
            String title = name.isEmpty() ? T.maintenanceNoticeTitlePlain(n, n) : T.maintenanceNoticeTitle(n, n, name);
            String first = MaintenanceView.name(due.get(0));
            String text = n == 1 ? first : T.maintenanceNoticeMore(n - 1, n - 1, first);
            Notification.InboxStyle lines = new Notification.InboxStyle();
            for (MaintenancePolicy.Reminder r : due) lines.addLine(MaintenanceView.name(r));
            // The tap names this notice's scope. Its own action and a request code per scope keep each
            // scooter's tap apart from the others and from the service's own taps.
            Intent open = new Intent(context, MainActivity.class).setAction(MaintenanceNotify.OPEN)
                    .putExtra(MaintenanceNotify.OPEN, true).putExtra(MaintenanceNotify.PROFILE, scope)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tap = PendingIntent.getActivity(context, scope.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notice = new Notification.Builder(context, MaintenanceNotify.CHANNEL).setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(title).setContentText(text).setStyle(n > 1 ? lines : new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build();
            manager.notify(scope, NOTICE, notice);
        } catch (RuntimeException unavailable) { }
    }

    @Override public void cancel(String scope) {
        try { context.getSystemService(NotificationManager.class).cancel(scope, NOTICE); } catch (RuntimeException unavailable) { }
    }

    private String scooterName(String scope) {
        String id = MaintenanceKeeper.profileOf(scope);
        if (profiles != null) for (ScooterProfiles.Profile p : profiles.all()) if (p.id.equals(id)) return p.shownName();
        return "";
    }

    /**
     * Creates or re-labels the channel. Low importance: a maintenance check is never urgent, so it makes
     * no sound and never pops up over what the owner is doing.
     */
    static void channel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(MaintenanceNotify.CHANNEL, T.maintenanceChannel(), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(T.maintenanceChannelDetail());
        manager.createNotificationChannel(channel);
    }

    /** For ScooterService.applyLocale: only a channel that already exists. */
    static void relabel(NotificationManager manager) {
        if (manager.getNotificationChannel(MaintenanceNotify.CHANNEL) != null) channel(manager);
    }

    /** app_settings for a {@link MaintenanceKeeper}. */
    static MaintenanceKeeper.Storage storage(SharedPreferences settings) {
        return new MaintenanceKeeper.Storage() {
            @Override public String read(String key) { return settings.getString(key, null); }
            @Override public void write(String key, String value) { settings.edit().putString(key, value).apply(); }
            @Override public void remove(String key) { settings.edit().remove(key).apply(); }
        };
    }

    /** Checks every saved scooter (the daily job). Returns how many notices posted. */
    static int checkAll(Context context, MaintenanceKeeper keeper, ScooterProfiles profiles, long nowWall, BooleanSupplier live) {
        MaintenanceNotices notices = new MaintenanceNotices(context, profiles);
        int posted = 0;
        for (ScooterProfiles.Profile p : profiles.all()) {
            if (!live.getAsBoolean()) break;
            if (MaintenanceNotify.check(keeper, p.id, nowWall, ZoneId.systemDefault(), false, live, notices)) posted++;
        }
        return posted;
    }

    /**
     * Schedules the daily job while a scooter needs it (MaintenanceNotify.needsJob) and cancels it otherwise.
     * A snooze that ended since the last check is checked first ({@link MaintenanceNotify#replan}), so
     * dropping the job never loses its notice. A job already planned is left alone, so its day is not
     * restarted by every change. Persisted: Android plans it again after a restart (RECEIVE_BOOT_COMPLETED,
     * a permission with no prompt). {@code live} is the caller's run token, as for a check.
     */
    static void plan(Context context, MaintenanceKeeper keeper, ScooterProfiles profiles, long nowWall, BooleanSupplier live) {
        try {
            List<String> scopes = new ArrayList<>();
            if (profiles != null) for (ScooterProfiles.Profile p : profiles.all()) scopes.add(p.id);
            boolean needed = MaintenanceNotify.replan(keeper, scopes, nowWall, ZoneId.systemDefault(), live, new MaintenanceNotices(context, profiles));
            if (!live.getAsBoolean()) return;
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (!needed) { jobs.cancel(JOB); return; }
            if (jobs.getPendingJob(JOB) != null) return;
            jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, MaintenanceJobService.class))
                    .setPeriodic(MaintenanceNotify.PERIOD_MS, MaintenanceNotify.FLEX_MS).setPersisted(true).setRequiresBatteryNotLow(true).build());
        } catch (RuntimeException unavailable) { }
    }

    /** A notice's tap, read once: where it goes and the name of the scooter it named. */
    static final class Tap {
        final MaintenanceNotify.Route route; final String name;
        Tap(MaintenanceNotify.Route route, String name) { this.route = route; this.name = name; }
    }

    /**
     * A tap on a maintenance notice, or null for any other start. It consumes the extras and never selects
     * a scooter: for the active scooter the Maintenance page opens next time the Scooter tab is built.
     */
    static Tap tapped(Intent intent, SharedPreferences preferences, ScooterProfiles profiles) {
        if (intent == null || !intent.getBooleanExtra(MaintenanceNotify.OPEN, false)) return null;
        String scope = intent.getStringExtra(MaintenanceNotify.PROFILE);
        intent.removeExtra(MaintenanceNotify.OPEN);
        intent.removeExtra(MaintenanceNotify.PROFILE);
        List<String> saved = new ArrayList<>();
        String name = "";
        ScooterProfiles.Profile active = profiles == null ? null : profiles.active();
        if (profiles != null) for (ScooterProfiles.Profile p : profiles.all()) {
            saved.add(p.id);
            if (scope != null && p.id.equals(MaintenanceKeeper.profileOf(scope))) name = p.shownName();
        }
        MaintenanceNotify.Route route = MaintenanceNotify.route(scope, active == null ? null : active.id, saved);
        if (route == MaintenanceNotify.Route.OPEN_PAGE) MaintenanceScreen.remember(preferences, true);
        return new Tap(route, name);
    }
}
