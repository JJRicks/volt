package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs RideAutoExport passes for ScooterService: one at a time, on its own worker thread, never in the
 * ride's save path. The service decides when a pass may start (not during backup or restore, not while a
 * phone journal is still saving) and keeps itself running while {@link #busy()} is true. A pass only reads
 * the ride stores; a failure is stored as a RideAutoExport.Problem id and handed back on the main thread,
 * never as text.
 */
final class RideAutoExportRunner {
    /** Called on the main thread after each pass. again: a start was asked for while it ran. */
    interface Done { void finished(RideAutoExport.Problem before, RideAutoExport.Problem after, boolean again); }

    /** A pass over many rides on a slow provider is still far shorter; the hold is released when the pass ends. */
    private static final long HOLD_MS = 5 * 60 * 1000L;
    private final Context context;
    private final SharedPreferences settings;
    private final Handler main;
    private final Done done;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "ride-auto-export"));
    /** Main thread only. */
    private boolean busy, again;

    RideAutoExportRunner(Context context, SharedPreferences settings, Handler main, Done done) {
        this.context = context.getApplicationContext(); this.settings = settings; this.main = main; this.done = done;
    }

    /** True from a pass's start until its completion has run on the main thread: the service's keep-alive. */
    boolean busy() { return busy; }

    /** Main thread. Starts a pass over the waiting rides, or asks for another one after the running pass. */
    void start(RideStore rides, PhoneRideStore phone) {
        if (!RideAutoExportFolder.waiting(settings)) return;
        if (busy) { again = true; return; }
        busy = true;
        final RideAutoExport.Problem before = RideAutoExportFolder.problem(settings);
        final PowerManager.WakeLock hold = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Volt:RideAutoExport");
        hold.setReferenceCounted(false); hold.acquire(HOLD_MS);
        try {
            worker.execute(() -> {
                RideAutoExport.Problem after = before;
                try { after = pass(rides, phone); }
                catch (Throwable unexpected) { android.util.Log.w("Volt", "Ride auto-export pass failed", unexpected); }
                finally {
                    if (hold.isHeld()) hold.release();
                    final RideAutoExport.Problem result = after;
                    main.post(() -> { busy = false; boolean more = again; again = false; done.finished(before, result, more); });
                }
            });
        } catch (RejectedExecutionException closed) {
            busy = false; again = false; if (hold.isHeld()) hold.release();
        }
    }

    /** The service is ending: a running pass finishes its current file; anything waiting stays stored for the next start. */
    void shutdown() { worker.shutdown(); }

    /** Worker thread. Reads the stored choice, writes the files, then stores what is still waiting. */
    private RideAutoExport.Problem pass(RideStore rides, PhoneRideStore phone) {
        List<String> pending; boolean on; Uri folderUri;
        synchronized (RideAutoExportFolder.LOCK) {
            on = RideAutoExportFolder.on(settings);
            pending = RideAutoExport.parse(settings.getString(RideAutoExport.PENDING, ""));
            folderUri = RideAutoExportFolder.chosen(settings);
        }
        if (pending.isEmpty() || !on) return RideAutoExport.Problem.NONE;
        RideAutoExport.Folder folder = null;
        try { if (folderUri != null) folder = new RideAutoExportFolder(context, folderUri); } catch (RideAutoExport.Unavailable gone) { folder = null; }
        RideAutoExport.Result result = RideAutoExport.run(pending, RideAutoExport.stores(rides, phone, context.getCacheDir(), AppVersion.name(context)), folder);
        int waiting;
        synchronized (RideAutoExportFolder.LOCK) {
            // Turned off meanwhile: the list and the problem were already cleared; do not bring them back.
            if (!RideAutoExportFolder.on(settings)) return RideAutoExport.Problem.NONE;
            String left = RideAutoExport.remove(settings.getString(RideAutoExport.PENDING, ""), result.finished);
            SharedPreferences.Editor edit = settings.edit().putString(RideAutoExport.PENDING, left);
            if (result.problem == RideAutoExport.Problem.NONE) edit.remove(RideAutoExport.PROBLEM);
            else edit.putString(RideAutoExport.PROBLEM, result.problem.id);
            edit.commit();
            waiting = RideAutoExport.parse(left).size();
        }
        android.util.Log.i("Volt", String.format(Locale.ROOT, "Ride auto-export: %d file(s) written, %d ride(s) waiting, problem=%s",
                result.saved.size(), waiting, result.problem.id));
        return result.problem;
    }
}
