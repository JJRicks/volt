package com.jjricks.volt;

import java.util.Objects;

/**
 * Decides when one ride recording should end on its own because the scooter went away. In-process
 * only and never persisted. Every input carries the caller's monotonic clock
 * (SystemClock.elapsedRealtime in the app); the policy never reads a clock or a sensor itself.
 *
 * Two rules end a ride. Any authenticated reconnect cancels both, and separate drops never add up:
 * only one continuous disconnection counts.
 *
 * 1. Parked (the original rule, unchanged): fresh motion pushes showed the scooter parked, then the
 *    link was lost. The ride ends {@link #DISCONNECT_GRACE_MS} after the loss.
 *    - A stationary run needs {@link #STATIONARY_SAMPLES} distinct motion pushes (speed 0 and
 *      driving status 0) spanning at least {@link #STATIONARY_SPAN_MS}, after earlier motion in the
 *      ride. Any moving, missing or out-of-order push clears the run.
 *    - A push counts only when it is at most {@link #MOTION_FRESH_MS} old when seen. At the loss,
 *      the newest stationary push must also be within that window of the last frame heard.
 *    - The loss must be seen within {@link #LOSS_WINDOW_MS} of the last frame. A recording declares
 *      a silent link lost after 12 s (checked every 2 s), so walking out of range lands inside it.
 *    - Only a recoverable transport loss arms it.
 *
 * 2. Went quiet: every other loss of this ride's scooter starts a countdown from the last frame
 *    heard, so "a minute" means a minute after the scooter switched off.
 *    - {@link #QUIET_SLOW_MS} when the last fresh motion push (at most {@link #MOTION_FRESH_MS}
 *      older than the last frame) showed walking pace or a stop: speed at most {@link #WALKING_RAW},
 *      whatever the driving status. Walking a scooter and switching it off looks like this.
 *    - {@link #QUIET_LONG_MS} otherwise: riding speed (more likely radio trouble), a missing, stale
 *      or unknown speed, or a loss seen more than {@link #LOSS_WINDOW_MS} after the last frame.
 *    - The phone's own Bluetooth switched off ({@link Loss#PHONE_BLUETOOTH_OFF}) also ends the ride
 *      after {@link #QUIET_LONG_MS}, timed from the last frame. It never shortens or restarts a
 *      countdown that is already running.
 *    - A policy whose first observation is a disconnection (a service restart mid-ride) cannot know
 *      the speed: it takes {@link #QUIET_LONG_MS}, timed from that first observation.
 *    - When the parked rule arms, it wins and the quiet countdown does not also run, so a failed
 *      save is never retried automatically.
 *
 * Any other loss (permission revoked, owner rejected), {@link #reset()} and a clock that moves
 * backwards cancel everything. After a reset only a new authenticated connection to this ride's
 * scooter can start a countdown again.
 */
public final class RideAutoStopPolicy {
    public static final int STATIONARY_SAMPLES = 2;
    public static final long STATIONARY_SPAN_MS = 3000, MOTION_FRESH_MS = 6000, LOSS_WINDOW_MS = 15000,
            DISCONNECT_GRACE_MS = 10000;
    public static final long QUIET_SLOW_MS = 60_000, QUIET_LONG_MS = 300_000;
    /** Walking pace or a stop, in the push's tenths of km/h: 6.4 km/h, about 4 mph. */
    public static final int WALKING_RAW = 64;

    /** How the authenticated connection went away. */
    public enum Loss {
        /** A transport loss the reconnect loop keeps retrying (out of range, scooter switched off). */
        RECOVERABLE,
        /** The phone's own Bluetooth is off: no reconnect can happen until it comes back on. */
        PHONE_BLUETOOTH_OFF,
        /** Permission revoked, owner rejected or anything else Volt will not retry on its own. */
        UNRECOVERABLE
    }
    /**
     * The service's view of a loss: no Bluetooth permission is unrecoverable; the phone's Bluetooth
     * switched off is {@link Loss#PHONE_BLUETOOTH_OFF}; otherwise the transport's own retry decision.
     */
    public static Loss loss(boolean permissions, boolean bluetoothEnabled, boolean recoverable) {
        if (!permissions) return Loss.UNRECOVERABLE;
        if (!bluetoothEnabled) return Loss.PHONE_BLUETOOTH_OFF;
        return recoverable ? Loss.RECOVERABLE : Loss.UNRECOVERABLE;
    }
    /** Why the quiet countdown runs. */
    public enum Quiet { NONE, SLOW, LONG, PHONE_BLUETOOTH_OFF, RESTART }

    public final String rideId, profile, serial;
    private int connection;
    private boolean bound, moved, fresh = true;
    private long lastNow, lastMotion, lastFrame, runStart, runLast, lossMono;
    private int runCount;
    /** Last fresh motion push on this connection: its speed (-1 unknown) and push time. */
    private int speedRaw = -1;
    private long speedMono;
    /** Last frame of the latest loss of this ride's scooter; 0 once reconnected, fired or reset. */
    private long lostFrame;
    private Quiet quiet = Quiet.NONE;
    private long quietFrom, quietMs;

    public RideAutoStopPolicy(String rideId, String profile, String serial) {
        if (rideId == null || rideId.isEmpty() || profile == null || profile.isEmpty() || serial == null || serial.isEmpty())
            throw new IllegalArgumentException("A ride on a verified scooter is required");
        this.rideId = rideId; this.profile = profile; this.serial = serial;
    }

    /** Either countdown is running. */
    public boolean armed() { return parkedArmed() || quietArmed(); }
    /** Waiting out the disconnect grace after a parked loss. */
    public boolean parkedArmed() { return lossMono > 0; }
    /** Waiting out the went-quiet countdown. */
    public boolean quietArmed() { return quiet != Quiet.NONE; }
    /** Why the quiet countdown runs; {@link Quiet#NONE} when it doesn't. */
    public Quiet quiet() { return quiet; }
    /** The time the quiet countdown counts from (the last frame heard); 0 when it doesn't run. */
    public long quietSince() { return quiet == Quiet.NONE ? 0 : quietFrom; }
    /** Time until the sooner countdown ends; 0 when neither runs. */
    public long remainingMs(long now) {
        long parked = parkedArmed() ? Math.max(0, DISCONNECT_GRACE_MS - (now - lossMono)) : Long.MAX_VALUE;
        long q = quietArmed() ? Math.max(0, quietMs - (now - quietFrom)) : Long.MAX_VALUE;
        long soonest = Math.min(parked, q);
        return soonest == Long.MAX_VALUE ? 0 : soonest;
    }

    /** Explicit stop, user disconnect, profile change, setting off or uncertain state: forget all evidence. */
    public void reset() {
        bound = moved = fresh = false; lastMotion = lastFrame = lossMono = lostFrame = 0;
        clearRun(); clearSpeed(); clearQuiet();
    }

    /**
     * An authenticated connection is up. The motion facts are the transport's own (zero/-1 when no
     * push has arrived), never cached display readings.
     */
    public void connected(long now, String boundProfile, String transportSerial, int transport,
            long frameMono, long motionMono, int speedRaw, int driving) {
        if (!clock(now)) return;
        // Any authenticated reconnect ends the continuous disconnection, for both rules.
        lossMono = 0; lostFrame = 0; clearQuiet(); fresh = false;
        if (!profile.equals(boundProfile) || !serial.equals(transportSerial)) {
            // Unknown or different scooter: nothing here is evidence for this ride.
            bound = false; lastMotion = lastFrame = 0; clearRun(); clearSpeed(); return;
        }
        if (!bound || transport != connection) { bound = true; connection = transport; lastMotion = lastFrame = 0; clearRun(); clearSpeed(); }
        if (frameMono > 0 && frameMono <= now && frameMono >= lastFrame) lastFrame = frameMono;
        if (motionMono <= 0) { clearRun(); clearSpeed(); return; }
        // Preserve a parked run during complete radio silence until the loss watchdog fires.
        // Other fresh frames with old motion establish uncertainty and invalidate that run.
        if (motionMono == lastMotion) {
            if (lastFrame - motionMono > MOTION_FRESH_MS || speedRaw < 0 || driving < 0) clearRun();
            return;
        }
        if (motionMono < lastMotion || motionMono > now) { lastMotion = motionMono; clearRun(); clearSpeed(); return; }
        if (lastMotion > 0 && motionMono - lastMotion > MOTION_FRESH_MS) clearRun();
        lastMotion = motionMono;
        boolean freshPush = now - motionMono <= MOTION_FRESH_MS;
        if (freshPush && speedRaw >= 0) { this.speedRaw = speedRaw; speedMono = motionMono; } else clearSpeed();
        if (!freshPush || speedRaw < 0 || driving < 0) { clearRun(); return; }
        if (speedRaw > 0 || driving != 0) { moved = true; clearRun(); return; }
        if (!moved) return;
        if (runCount == 0) runStart = motionMono;
        runLast = motionMono; runCount++;
    }

    /** The authenticated connection is gone; {@code recoverable} false is {@link Loss#UNRECOVERABLE}. */
    public void disconnected(long now, boolean recoverable) { disconnected(now, recoverable ? Loss.RECOVERABLE : Loss.UNRECOVERABLE); }

    /**
     * The authenticated connection is gone. Only the first observation of a loss decides which
     * countdown runs; later calls while still disconnected never restart or clear it.
     */
    public void disconnected(long now, Loss loss) {
        if (!clock(now)) return;
        if (loss == null || loss == Loss.UNRECOVERABLE) { reset(); return; }
        if (bound) {
            bound = false;
            long frame = lastFrame > 0 ? lastFrame : now;
            boolean parked = loss == Loss.RECOVERABLE && runCount >= STATIONARY_SAMPLES && runLast - runStart >= STATIONARY_SPAN_MS
                    && lastFrame >= runLast && lastFrame - runLast <= MOTION_FRESH_MS
                    && now >= lastFrame && now - lastFrame <= LOSS_WINDOW_MS;
            boolean slow = speedRaw >= 0 && speedRaw <= WALKING_RAW && lastFrame > 0
                    && lastFrame >= speedMono && lastFrame - speedMono <= MOTION_FRESH_MS
                    && now - lastFrame <= LOSS_WINDOW_MS;
            clearRun(); clearSpeed(); lastMotion = lastFrame = 0;
            lostFrame = frame;
            if (parked) lossMono = now;
            else if (loss == Loss.PHONE_BLUETOOTH_OFF) startQuiet(frame, QUIET_LONG_MS, Quiet.PHONE_BLUETOOTH_OFF);
            else if (slow) startQuiet(frame, QUIET_SLOW_MS, Quiet.SLOW);
            else startQuiet(frame, QUIET_LONG_MS, Quiet.LONG);
            return;
        }
        if (fresh) {
            // Created while already disconnected (service restart mid-ride): no speed evidence.
            fresh = false; lostFrame = now;
            startQuiet(now, QUIET_LONG_MS, loss == Loss.PHONE_BLUETOOTH_OFF ? Quiet.PHONE_BLUETOOTH_OFF : Quiet.RESTART);
            return;
        }
        // Bluetooth switched off after a loss whose countdown already ended or never ran here.
        if (loss == Loss.PHONE_BLUETOOTH_OFF && !armed() && lostFrame > 0)
            startQuiet(lostFrame, QUIET_LONG_MS, Quiet.PHONE_BLUETOOTH_OFF);
    }

    /** True once when the parked grace has run out; the caller then ends this ride through its normal stop. */
    public boolean due(long now) {
        if (!clock(now) || !parkedArmed() || now - lossMono < DISCONNECT_GRACE_MS) return false;
        lossMono = 0; lostFrame = 0; return true;
    }

    /** True once when the went-quiet countdown has run out; the caller then ends this ride through its normal stop. */
    public boolean dueQuiet(long now) {
        if (!clock(now) || !quietArmed() || now - quietFrom < quietMs) return false;
        clearQuiet(); lostFrame = 0; return true;
    }

    /** True only for the policy instance that belongs to the ride still recording. */
    public boolean owns(String activeRideId) { return Objects.equals(rideId, activeRideId); }

    private void clearRun() { runCount = 0; runStart = runLast = 0; }
    private void clearSpeed() { speedRaw = -1; speedMono = 0; }
    private void clearQuiet() { quiet = Quiet.NONE; quietFrom = quietMs = 0; }
    private void startQuiet(long from, long ms, Quiet why) { quiet = why; quietFrom = from; quietMs = ms; }

    /** A clock that moves backwards can no longer measure the gap or the stop: fail closed. */
    private boolean clock(long now) {
        if (now <= 0 || now < lastNow) { reset(); lastNow = Math.max(0, now); return false; }
        lastNow = now; return true;
    }
}
