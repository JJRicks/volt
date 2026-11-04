package com.jjricks.volt;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.security.SecureRandom;
import rikka.shizuku.Shizuku;

/** Process-local coordinator: survives the NAVEE foreground switch, never persists a captured identity. */
final class ShizukuCaptureClient {
    static final int REQUEST_PERMISSION = 7041;
    /*
     * The status lines message can hold. They stay English here: ShizukuCaptureClientTest and the census
     * validations compare message with them. ShizukuStatus pairs each with its catalog text for screens.
     */
    static final String ACCESS_DENIED = "Access denied";
    static final String NOT_RUNNING = "Shizuku isn't running";
    static final String UPDATE_SHIZUKU = "Update Shizuku";
    static final String ROOT_UNSUPPORTED = "Root Shizuku isn't supported. Start it with wireless debugging.";
    static final String ALLOW_IN_SHIZUKU = "Allow Volt in Shizuku";
    static final String SHIZUKU_STOPPED = "Shizuku stopped";
    static final String CLEANUP_UNKNOWN = "Cleanup unknown. Use computer recovery.";
    static final String START_FAILED = "Couldn't start. Nothing changed.";
    static final String HELPER_NOT_STARTED = "Helper didn't start";
    static final String CLEANUP_UNCONFIRMED = "Logging cleanup unconfirmed";
    static final String NOTHING_FOUND = "Nothing found in 90 s";
    static final String SEVERAL_SCOOTERS = "Several scooters. Connect one.";
    static final String CAPTURE_BUSY = "Another capture is running";
    static final String LOG_UNREADABLE = "Volt couldn't read NAVEE's log";
    static final String STOPPED = "Stopped";
    static final String CAPTURE_FAILED = "Capture failed";
    static final String RESTORE_AGAIN = "Restore logging again";
    static final String RESTORING = "Restoring NAVEE…";
    static final String LOST = "Shizuku lost. Restore logging.";
    // Bump with every UserService/guard behavior change, including same-version previews.
    private static final int USER_SERVICE_VERSION = 2;
    private static ShizukuCaptureClient singleton;
    static synchronized ShizukuCaptureClient get(Context context) {
        if (singleton == null) singleton = new ShizukuCaptureClient(context.getApplicationContext());
        return singleton;
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences journal;
    private final Shizuku.UserServiceArgs args;
    private IOwnerCaptureService remote;
    private ServiceConnection binding;
    private Runnable listener;
    private volatile String token = "";
    private boolean sessionCancelled, recoveryAttempt;
    private volatile boolean remoteDispatched;
    volatile boolean busy;
    int phase;
    /** The status line: "" or one of the English constants above; screens show {@link ShizukuStatus#display(String)}. */
    String message = "";
    /** Set when Android won't show Shizuku's prompt again: Volt has to be allowed inside Shizuku. */
    boolean allowInShizuku;
    /** When NAVEE capture began (monotonic ms), for the setup screen's 90-second countdown; 0 if not capturing. */
    long captureSinceMs;
    OwnerIdentityParser.Result owner;
    private OwnerIdentityParser.Result approved;
    private ShizukuCaptureClient(Context context) {
        journal = context.getSharedPreferences("owner_capture_cleanup", Context.MODE_PRIVATE);
        args = new Shizuku.UserServiceArgs(new ComponentName(context, OwnerCaptureUserService.class))
                .daemon(true).tag("volt-owner-capture-v1").version(USER_SERVICE_VERSION).debuggable(false).processNameSuffix("owner_setup");
        Shizuku.addBinderReceivedListenerSticky(this::changed);
        Shizuku.addBinderDeadListener(() -> lost());
        Shizuku.addRequestPermissionResultListener((request, grant) -> {
            if (request == REQUEST_PERMISSION) {
                // Granted moves the screen to the capture step by itself; only a refusal needs words.
                message = grant == PackageManager.PERMISSION_GRANTED ? "" : ACCESS_DENIED;
                allowInShizuku = false;
                changed();
            }
        });
    }
    void listen(Runnable update) { listener = update; }
    void unlisten(Runnable update) { if (listener == update) listener = null; }
    private void changed() { handler.post(() -> { if (listener != null) listener.run(); }); }
    boolean pendingCleanup() { return journal.getBoolean("pending", false); }
    boolean available() {
        try { return Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == 2000; }
        catch (RuntimeException ignored) { return false; }
    }
    /** True once Shizuku's service answers, even if it is too old or runs as root. */
    boolean running() {
        try { return Shizuku.pingBinder(); } catch (RuntimeException ignored) { return false; }
    }
    /** Why {@link #available()} is false, as a short status; empty when it is available. */
    String unavailableReason() {
        try {
            if (!Shizuku.pingBinder()) return NOT_RUNNING;
            if (Shizuku.getVersion() < 13) return UPDATE_SHIZUKU;
            if (Shizuku.getUid() != 2000) return ROOT_UNSUPPORTED;
            return "";
        } catch (RuntimeException ignored) { return NOT_RUNNING; }
    }
    boolean permitted() {
        try { return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException ignored) { return false; }
    }
    void requestPermission() {
        if (!available()) { message = unavailableReason(); changed(); return; }
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                message = ALLOW_IN_SHIZUKU; allowInShizuku = true;
            } else Shizuku.requestPermission(REQUEST_PERMISSION);
        } catch (RuntimeException ignored) { message = SHIZUKU_STOPPED; }
        changed();
    }
    void start(boolean recovery) {
        if (busy || !permitted() || (!recovery && pendingCleanup())) return;
        owner = null; approved = null; message = ""; sessionCancelled = false; busy = true; phase = OwnerCaptureEngine.PREPARING;
        captureSinceMs = 0; allowInShizuku = false;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String session = recovery ? journal.getString("token", "") : Protocol.hex(random).toLowerCase(java.util.Locale.ROOT);
        String original = recovery ? journal.getString("original", null) : null;
        if (!OwnerCaptureEngine.validToken(session) || (recovery && original != null && !OwnerCaptureEngine.validProperty(original))) {
            busy = false; message = CLEANUP_UNKNOWN; changed(); return;
        }
        token = session; recoveryAttempt = recovery; remoteDispatched = false;
        // Reserve a non-secret recovery token before the shell process can acquire its lock.
        // Even a process kill between mkdir and the original-property callback stays recoverable.
        if (!recovery && !journal.edit().putBoolean("pending", true).putString("token", session).remove("original").commit()) {
            busy = false; message = START_FAILED; changed(); return;
        }
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binding != this || !busy || !session.equals(token)) return;
                remote = IOwnerCaptureService.Stub.asInterface(binder);
                try {
                    binder.linkToDeath(() -> handler.post(() -> { if (binding == this) lost(); }), 0);
                    // Mark before IPC: an exception can mean the remote started but
                    // its reply was lost. Only definitely-unsent calls may drop a receipt.
                    remoteDispatched = true;
                    if (recovery) remote.recover(session, original, callback); else remote.start(session, callback);
                } catch (Exception ignored) { lost(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { if (binding == this) lost(); }
        };
        binding = connection;
        try { Shizuku.bindUserService(args, connection); }
        catch (RuntimeException ignored) { lost(); }
        handler.postDelayed(() -> {
            if (binding == connection && busy && remote == null) {
                discardUnsentJournal();
                busy = false; phase = 0;
                message = HELPER_NOT_STARTED;
                unbind(); changed();
            }
        }, 15000);
        changed();
    }
    private final IOwnerCaptureCallback callback = new IOwnerCaptureCallback.Stub() {
        private void authorize() { if (Binder.getCallingUid() != 2000) throw new SecurityException("Not authorized"); }
        @Override public boolean prepareCleanup(String session, String original) {
            authorize();
            synchronized (ShizukuCaptureClient.this) {
                if (!busy || !token.equals(session) || !OwnerCaptureEngine.validProperty(original)) return false;
                return journal.edit().putBoolean("pending", true).putString("token", session)
                        .putString("original", original).commit();
            }
        }
        @Override public void progress(String session, int next) {
            authorize(); handler.post(() -> {
                if (busy && token.equals(session)) {
                    if (next == OwnerCaptureEngine.CONNECT_NAVEE && phase != next) captureSinceMs = System.nanoTime() / 1000000L;
                    phase = next; changed();
                }
            });
        }
        @Override public void completed(String session, int outcome, boolean clean, String identity, int role) {
            authorize();
            // The only credential boundary. It is never logged, copied, put in an Intent or saved state.
            handler.post(() -> {
                if (!token.equals(session)) return;
                boolean wasCancelled = sessionCancelled || !busy;
                busy = false; phase = 0; owner = null;
                if (clean && !journal.edit().clear().commit()) cleanResultFailure();
                else if (!clean) message = CLEANUP_UNCONFIRMED;
                else if (!wasCancelled && outcome == OwnerCaptureEngine.OK && identity != null
                        && identity.matches("[0-9A-Fa-f]{12}") && (role == 0 || role == 1)) {
                    owner = new OwnerIdentityParser.Result(identity.toUpperCase(java.util.Locale.ROOT), role);
                    // The Ready screen shows this result itself.
                    message = "";
                } else if (outcome == OwnerCaptureEngine.TIMED_OUT) message = NOTHING_FOUND;
                else if (outcome == OwnerCaptureEngine.CONFLICT) message = SEVERAL_SCOOTERS;
                else if (outcome == OwnerCaptureEngine.BUSY) message = CAPTURE_BUSY;
                else if (outcome == OwnerCaptureEngine.UNREADABLE) message = LOG_UNREADABLE;
                else if (outcome == OwnerCaptureEngine.CANCELLED || wasCancelled) message = STOPPED;
                else message = CAPTURE_FAILED;
                unbind(); changed();
            });
        }
    };
    private void cleanResultFailure() { message = RESTORE_AGAIN; }
    void cancel() {
        owner = null; sessionCancelled = true;
        if (busy) {
            phase = OwnerCaptureEngine.CLEANING; message = RESTORING;
            if (remote != null) {
                try { remote.cancel(token); } catch (Exception ignored) { lost(); }
            } else {
                discardUnsentJournal(); busy = false; phase = 0;
                message = STOPPED;
                unbind();
            }
        }
        changed();
    }
    private void discardUnsentJournal() {
        // A bind failure cannot have changed the phone. Recovery, however, refers
        // to an older possibly-mutating session and must retain its receipt.
        if (busy && binding != null && !recoveryAttempt && !remoteDispatched
                && token.equals(journal.getString("token", ""))) journal.edit().clear().commit();
    }
    private void lost() {
        discardUnsentJournal();
        remote = null; busy = false; owner = null;
        message = pendingCleanup() ? LOST : SHIZUKU_STOPPED;
        unbind(); changed();
    }
    private void unbind() {
        ServiceConnection connection = binding; binding = null; remote = null;
        if (connection != null) try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) { }
    }
    boolean approveImport() {
        if (owner == null || busy || pendingCleanup()) return false;
        approved = owner; owner = null; return true;
    }
    OwnerIdentityParser.Result takeApproved() { OwnerIdentityParser.Result value = approved; approved = null; return value; }
    /** Drops an unimported capture; its status line goes with it so step 3 doesn't show a stale result. */
    void discard() { owner = null; message = ""; changed(); }
}
