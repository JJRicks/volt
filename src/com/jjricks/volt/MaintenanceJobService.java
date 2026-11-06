package com.jjricks.volt;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The daily maintenance check (maintenance plan, PR 3; owner answer Q40 A): a time reminder can notify while
 * Volt isn't open. It reads each scooter's reminders, posts what newly became due and stops; it never
 * starts the scooter service or Bluetooth, and nothing keeps it running. {@link MaintenanceNotices#plan}
 * schedules it only while a scooter needs it.
 *
 * A run that Android stops, or that a backup or restore overtakes, posts nothing: every side effect checks
 * this run's token first, and the check reads the reminders once more before posting.
 */
public final class MaintenanceJobService extends JobService {
    private final AtomicInteger run = new AtomicInteger();

    @Override public boolean onStartJob(JobParameters params) {
        if (DataMaintenance.active() || !DataMaintenance.readyForStartup(this)) return false;
        int token = run.incrementAndGet();
        java.util.function.BooleanSupplier live = () -> run.get() == token && !DataMaintenance.active();
        SharedPreferences settings = getSharedPreferences(MaintenanceNotices.SETTINGS, MODE_PRIVATE);
        Thread work = new Thread(() -> {
            try {
                MaintenanceKeeper keeper = new MaintenanceKeeper(MaintenanceNotices.storage(settings));
                ScooterProfiles profiles = new ScooterProfiles(this);
                long now = System.currentTimeMillis();
                MaintenanceNotices.checkAll(this, keeper, profiles, now, live);
                // No longer needed (a snooze ended, say): the next run doesn't happen.
                if (live.getAsBoolean()) MaintenanceNotices.plan(this, keeper, profiles, now);
            } catch (RuntimeException failed) {
                // The next run tries again.
            } finally {
                if (run.get() == token) jobFinished(params, false);
            }
        });
        work.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        run.incrementAndGet();
        return false;
    }
}
