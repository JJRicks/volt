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
 *    - A scooter switched off but still answering ({@link #scooterOff}, 2.0.5) counts as gone, timed from its last
 *      push, and ends the ride with the link still up ({@link #mayEnd}). The link stays bound meanwhile, so rule 3
 *      keeps counting; a later drop keeps the countdown, and only a push cancels it.
 *
 * 3. No motion while connected: the link stays up but the scooter has not moved for
 *    {@link #NO_MOTION_MS}. A phone that stays connected keeps the scooter from switching itself off,
 *    so rules 1 and 2 never fire; a parked scooter may stop sending speed altogether while battery
 *    readings carry on, so missing speed pushes count as no motion, not only speed-0 pushes.
 *    - Motion is a fresh speed push above 0 (any speed: walk mode pushes 0.2 to 3 km/h with driving
 *      status 0), a fresh push with driving status 1, or the odometer or trip going up. A held,
 *      stale speed never counts. Driving status 0 is not evidence of a stop.
 *    - The countdown runs from the latest motion, or from the first authenticated observation of this
 *      ride's scooter on the current connection (in practice the ride's start) before any motion.
 *    - It needs a frame within {@link #LOSS_WINDOW_MS}: a silent link is the loss watchdog's to report.
 *    - A disconnection stops it and hands over to rules 1 and 2 unchanged.
 *    - A reconnect within {@link #RECONNECT_CARRY_MS} of the last frame heard keeps counting from the
 *      motion before the drop, with the same odometer and trip baselines, so a few-second Bluetooth blip
 *      at the destination does not restart the 10 minutes (the owner's ride of 2026-11-06 had one).
 *      Distance gained during the gap still counts as motion at the first fresh reading. A carried count
 *      cannot end the ride until a fresh odometer or trip reading from after the drop has been judged, or
 *      {@link #CARRY_EVIDENCE_MS} after the reconnect when none arrives, so a rider who set off during
 *      the gap is not cut off before the readings show it. A new drop while that wait is unresolved keeps
 *      the original pre-gap baselines (and the metrics already ruled out) and the original deadline, so a
 *      value first seen between two blips is never taken as proof and a flapping link cannot extend the
 *      wait past {@link #CARRY_EVIDENCE_MS} after the first reconnect. After a longer gap the countdown starts again
 *      from the reconnect, since the scooter may have moved meanwhile.
 *    - No phone GPS guard, as for the Bluetooth-off rule (owner answer Q30 B).
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
    /** Rule 3: a connected ride with no motion for this long ends (the owner: long enough for traffic lights). */
    public static final long NO_MOTION_MS = 600_000;
    /** Rule 3: a reconnect this soon after the last frame heard keeps the countdown from before the drop. */
    public static final long RECONNECT_CARRY_MS = 60_000;
    /**
     * Rule 3: after a carried reconnect, the longest wait for a fresh distance reading before the carried
     * count may end the ride anyway (a scooter that never reports distance cannot keep a ride open).
     */
    public static final long CARRY_EVIDENCE_MS = 30_000;
    /** The service feeds distance readings at most this old (RideStore.MAX_SENSOR_AGE_MS). */
    static final long READING_AGE_MS = 3_000;

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
        return loss(permissions, bluetoothEnabled, recoverable, false);
    }
    /**
     * As above, for a transport whose latest event is the phone's Bluetooth switching off
     * ({@code adapterOffLast}, see {@link ConnectionEventLog#adapterOffLast}). With Bluetooth back on,
     * its retry flag still says "not retried" from that adapter-off failure while the reconnect runs, so
     * the loss stays recoverable and an armed countdown keeps its deadline. A failed reconnect records
     * its own event, after which the transport's flag decides again.
     */
    public static Loss loss(boolean permissions, boolean bluetoothEnabled, boolean recoverable, boolean adapterOffLast) {
        if (!permissions) return Loss.UNRECOVERABLE;
        if (!bluetoothEnabled) return Loss.PHONE_BLUETOOTH_OFF;
        return recoverable || adapterOffLast ? Loss.RECOVERABLE : Loss.UNRECOVERABLE;
    }
    /** Why the quiet countdown runs. */
    public enum Quiet { NONE, SLOW, LONG, PHONE_BLUETOOTH_OFF, RESTART }

    public final String rideId, profile, serial;
    private int connection;
    private boolean bound, moved, fresh = true;
    /** The link is up but the scooter is switched off (scooterOff): its countdown runs while rule 3 keeps counting. */
    private boolean off;
    /** Since the last push: Volt saw the scooter off on a live link (ScooterPowerState's off verdict, never a first-push wait alone). */
    private boolean seenOff;
    private long lastNow, lastMotion, lastFrame, runStart, runLast, lossMono;
    private int runCount;
    /** Last fresh motion push on this connection: its speed (-1 unknown) and push time. */
    private int speedRaw = -1;
    private long speedMono;
    /** Last frame of the latest loss of this ride's scooter; 0 once reconnected, fired or reset. */
    private long lostFrame;
    private Quiet quiet = Quiet.NONE;
    private long quietFrom, quietMs;
    /** Rule 3: the time the no-motion countdown counts from (0 when it doesn't run), the last speed push it judged, distance baselines. */
    private long stillFrom, stillPush;
    private double stillOdometer = Double.NaN, stillTrip = Double.NaN;
    /** Rule 3 across a drop: the countdown's start and baselines at the loss, and that loss's last frame (0 when none). */
    private long heldFrom, heldFrame;
    /** The held count's own wait was still unresolved at the drop: its deadline, kept for the next reconnect (0 when none). */
    private long heldHold;
    private double heldOdometer = Double.NaN, heldTrip = Double.NaN;
    /** Rule 3 after a carried reconnect: no ending before this time (0 when not waiting) unless a distance reading after {@link #carryAfter} arrives. */
    private long carryHold, carryAfter;
    /** The pre-gap odometer and trip a fresh reading must be compared with to release the hold; NaN when unknown or reset. */
    private double carryOdometer = Double.NaN, carryTrip = Double.NaN;

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
    /** Rule 3 is counting: this ride's scooter is connected and the ride has not ended for no motion. */
    public boolean noMotionArmed() { return stillFrom > 0; }
    /** The latest motion (or the countdown's start before any motion); 0 when rule 3 is not counting. */
    public long noMotionSince() { return stillFrom; }
    /** Time until the sooner countdown ends; 0 when neither runs. */
    public long remainingMs(long now) {
        long parked = parkedArmed() ? Math.max(0, DISCONNECT_GRACE_MS - (now - lossMono)) : Long.MAX_VALUE;
        long q = quietArmed() ? Math.max(0, quietMs - (now - quietFrom)) : Long.MAX_VALUE;
        long soonest = Math.min(parked, q);
        return soonest == Long.MAX_VALUE ? 0 : soonest;
    }

    /** Explicit stop, user disconnect, profile change, setting off or uncertain state: forget all evidence. */
    public void reset() {
        bound = moved = fresh = off = seenOff = false; lastMotion = lastFrame = lossMono = lostFrame = 0;
        clearRun(); clearSpeed(); clearQuiet(); clearStill(); clearHeld();
    }

    /**
     * An authenticated connection is up. The motion facts are the transport's own (zero/-1 when no
     * push has arrived), never cached display readings.
     */
    public void connected(long now, String boundProfile, String transportSerial, int transport,
            long frameMono, long motionMono, int speedRaw, int driving) {
        connected(now, boundProfile, transportSerial, transport, frameMono, motionMono, speedRaw, driving, Double.NaN, Double.NaN);
    }

    /**
     * As above, with the scooter's latest odometer and trip readings in km (NaN when unknown), which
     * rule 3 counts as motion when they go up. Same as the plain call followed by {@link #distance}.
     */
    public void connected(long now, String boundProfile, String transportSerial, int transport,
            long frameMono, long motionMono, int speedRaw, int driving, double odometerKm, double tripKm) {
        if (!clock(now)) return;
        // Any authenticated reconnect ends the continuous disconnection, for both rules; a push ends a switch-off.
        lossMono = 0; lostFrame = 0; clearQuiet(); fresh = false; off = false; seenOff = false;
        if (!profile.equals(boundProfile) || !serial.equals(transportSerial)) {
            // Unknown or different scooter: nothing here is evidence for this ride.
            // A serial not read yet right after a reconnect may still be this scooter: keep rule 3's held count.
            if (transportSerial != null && !transportSerial.isEmpty()) clearHeld();
            bound = false; lastMotion = lastFrame = 0; clearRun(); clearSpeed(); clearStill(); return;
        }
        if (!bound || transport != connection) {
            bound = true; connection = transport; lastMotion = lastFrame = 0; clearRun(); clearSpeed(); clearStill(); stillFrom = now;
            if (heldFrom > 0 && heldFrame > 0 && now >= heldFrame && now - heldFrame <= RECONNECT_CARRY_MS) {
                stillFrom = heldFrom; stillOdometer = heldOdometer; stillTrip = heldTrip;
                carryHold = heldHold > 0 ? heldHold : now + CARRY_EVIDENCE_MS; carryAfter = heldFrame + READING_AGE_MS;
                carryOdometer = heldOdometer; carryTrip = heldTrip;
            }
            clearHeld();
        }
        if (frameMono > 0 && frameMono <= now && frameMono >= lastFrame) lastFrame = frameMono;
        if (stillFrom > 0) { still(now, motionMono, speedRaw, driving); distance(now, odometerKm, tripKm); }
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
            // A switch-off already started this loss's countdown (scooterOff): the drop keeps it, as any later observation does.
            if (off) off = false;
            else arm(now, loss);
            if (stillFrom > 0) {
                heldFrom = stillFrom; heldFrame = frame; heldOdometer = stillOdometer; heldTrip = stillTrip; heldHold = 0;
                // Unresolved carry: keep the original pre-gap baselines and deadline, never values learned since.
                if (carryHold > 0) { heldHold = carryHold; heldOdometer = carryOdometer; heldTrip = carryTrip; }
            }
            clearRun(); clearSpeed(); clearStill(); lastMotion = lastFrame = 0;
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

    /**
     * The link is still up but the scooter is switched off (ScooterPowerState, 2.0.5 item 5): it answers reads but
     * pushes nothing. Its countdown is the one the scooter going away starts, a {@link Loss#RECOVERABLE} loss whose last
     * frame heard is its last push ({@code pushMono}, 0 when none arrived on this connection), so both rules time it from the
     * switch-off as they would after a power-off that dropped the link. Only the first observation decides, as for
     * {@link #disconnected}; the scooter's first push afterwards is an authenticated reconnect ({@link #connected}).
     */
    public void scooterOff(long now, long pushMono) { scooterOff(now, pushMono, 0); }

    /**
     * As above, with the newest frame the link has carried ({@code frameMono}, the answered polls; 0 when unknown). The
     * link is still up, so this ride's scooter stays bound: rule 3 keeps counting from the last motion (the answered
     * polls keep its link check fresh), so a ride whose countdown ran out but whose save failed still ends at ten
     * minutes, as on a link that stays up. A later drop keeps the countdown; a push ({@link #connected}) cancels it.
     */
    public void scooterOff(long now, long pushMono, long frameMono) {
        seenOff = true;
        if (!bound) { disconnected(now, Loss.RECOVERABLE); return; }
        if (!clock(now)) return;
        if (!off) {
            off = true;
            if (pushMono > 0 && pushMono <= now && pushMono < lastFrame) lastFrame = pushMono;
            arm(now, Loss.RECOVERABLE);
            clearRun(); clearSpeed();
        }
        if (frameMono > 0 && frameMono <= now && frameMono >= lastFrame) lastFrame = frameMono;
    }

    /** The countdown a loss of this bound scooter starts now (rules 1 and 2), timed from the last frame heard. */
    private void arm(long now, Loss loss) {
        long frame = lastFrame > 0 ? lastFrame : now;
        boolean parked = loss == Loss.RECOVERABLE && runCount >= STATIONARY_SAMPLES && runLast - runStart >= STATIONARY_SPAN_MS
                && lastFrame >= runLast && lastFrame - runLast <= MOTION_FRESH_MS
                && now >= lastFrame && now - lastFrame <= LOSS_WINDOW_MS;
        boolean slow = speedRaw >= 0 && speedRaw <= WALKING_RAW && lastFrame > 0
                && lastFrame >= speedMono && lastFrame - speedMono <= MOTION_FRESH_MS
                && now - lastFrame <= LOSS_WINDOW_MS;
        lostFrame = frame;
        if (parked) lossMono = now;
        else if (loss == Loss.PHONE_BLUETOOTH_OFF) startQuiet(frame, QUIET_LONG_MS, Quiet.PHONE_BLUETOOTH_OFF);
        else if (slow) startQuiet(frame, QUIET_SLOW_MS, Quiet.SLOW);
        else startQuiet(frame, QUIET_LONG_MS, Quiet.LONG);
    }

    /**
     * What ScooterService sees of the link at one observation (observeRideAutoStop): the transport's own facts, never
     * cached readings. switchedOff is ScooterPowerState's verdict, which only a push ends.
     */
    public static final class Link {
        public final boolean connected, switchedOff;
        /** Up, but no push yet on a scooter that is judged (ScooterPowerState.awaitingPush): not yet a reconnect. */
        public final boolean awaitingPush;
        final String profile, serial;
        final int transport, speedRaw, driving;
        final long frameMono, motionMono, pushMono;
        final Loss loss;
        private Link(boolean connected, boolean switchedOff, boolean awaitingPush, String profile, String serial, int transport, long frameMono, long motionMono,
                int speedRaw, int driving, long pushMono, Loss loss) {
            this.connected = connected; this.switchedOff = switchedOff; this.awaitingPush = awaitingPush; this.profile = profile; this.serial = serial; this.transport = transport;
            this.frameMono = frameMono; this.motionMono = motionMono; this.speedRaw = speedRaw; this.driving = driving; this.pushMono = pushMono; this.loss = loss;
        }
        /** An authenticated link, as {@link #connected} takes it; pushMono is its latest push (0 for none). */
        public static Link up(String boundProfile, String transportSerial, int transport, long frameMono, long motionMono, int speedRaw, int driving,
                boolean switchedOff, long pushMono) {
            return up(boundProfile, transportSerial, transport, frameMono, motionMono, speedRaw, driving, switchedOff, false, pushMono);
        }
        /** As above; awaitingPush: no push yet on this connection to a judged scooter ({@link #awaitingPush}). */
        public static Link up(String boundProfile, String transportSerial, int transport, long frameMono, long motionMono, int speedRaw, int driving,
                boolean switchedOff, boolean awaitingPush, long pushMono) {
            return new Link(true, switchedOff, awaitingPush && !switchedOff, boundProfile, transportSerial, transport, frameMono, motionMono, speedRaw, driving, pushMono, null);
        }
        /** No authenticated link: the loss as {@link #loss} classifies it. */
        public static Link down(Loss loss) { return new Link(false, false, false, "", "", 0, 0, 0, -1, -1, 0, loss); }
        /**
         * Whether the ride takes readings from this link: up, and showing the scooter on. A switched-off scooter's answered
         * polls, and a reconnect's before its first push, are a gap like a drop, so a ride's saved end and duration never
         * stretch over them.
         */
        public boolean recordsReadings() { return connected && !switchedOff && !awaitingPush; }
    }

    /**
     * One observation of the link: a loss, a switched-off scooter still answering, a connection still awaiting its first
     * push, or an authenticated connection.
     */
    public void observe(long now, Link link) {
        if (link == null) return;
        if (!link.connected) disconnected(now, link.loss);
        else if (link.switchedOff) {
            // Nothing running and nothing bound (End rides automatically switched back on mid-ride, or a service restart,
            // with the scooter already off): bind to this ride's scooter on this link first, so the switch-off arms its
            // countdown from the last push. A countdown already running (a reconnect after a drop) is never rebound.
            if (!bound && !armed()) connected(now, link.profile, link.serial, link.transport, link.frameMono, link.motionMono, link.speedRaw, link.driving);
            scooterOff(now, link.pushMono, link.frameMono);
        }
        // Awaiting its first push is not an off verdict (an on scooter at the edge of range does it too): only the
        // verdict on a live link (scooterOff above) marks the scooter seen off.
        else if (link.awaitingPush) awaitingPush(now);
        else connected(now, link.profile, link.serial, link.transport, link.frameMono, link.motionMono, link.speedRaw, link.driving);
    }

    /**
     * Connected again, but the scooter has not pushed yet on this connection and is judged by ScooterPowerState (2.0.5 item
     * 5 follow-up): a switched-off scooter that keeps answering reads looks exactly like this. It is not a reconnect, so a
     * running countdown keeps its deadline: the owner's 23 November ride ran on because a reconnect four minutes after the
     * switch-off, to a scooter that never pushed, cancelled it. The first push is the reconnect ({@link #connected}); the off
     * verdict, 10 s later at most, is {@link #scooterOff}. Meanwhile {@link #mayEnd} is false, so a deadline passing in
     * that grace waits for the verdict instead of cutting off a scooter that is about to push.
     */
    public void awaitingPush(long now) { clock(now); }

    /**
     * Whether, since the scooter's last push, Volt saw it switched off on a live link: the off verdict ({@link #scooterOff}),
     * on the first link or on a reconnect that answered long enough for one. A drop that never came back is not that (a
     * walk-away out of range looks the same), and neither is a reconnect that dropped again inside the first-push grace (an
     * on scooter at the edge of range): OffReconnectHold then sets no hold. A push or a reset clears it.
     */
    public boolean seenOffWhileLinked() { return seenOff; }

    /** Whether rules 1 and 2 may end the ride with this link: gone, or up to a scooter switched off; never one that is on. */
    public static boolean mayEnd(Link link) { return link == null || !link.connected || link.switchedOff; }

    /** Which rule ends the ride at a tick. */
    public enum Stop { NONE, PARKED, QUIET, NO_MOTION }

    /**
     * ScooterService's tick, after this tick's observation: the rule that ends the ride now, once. Rules 1 and 2 are
     * looked at only when {@link #mayEnd} allows, so a countdown is never used up by a stop that would be refused.
     */
    public Stop due(long now, Link link) {
        if (mayEnd(link)) {
            if (due(now)) return Stop.PARKED;
            if (dueQuiet(now)) return Stop.QUIET;
        }
        return dueNoMotion(now) ? Stop.NO_MOTION : Stop.NONE;
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

    /**
     * True once when this ride's scooter is connected and has not moved for {@link #NO_MOTION_MS}; the
     * caller then ends this ride through its normal stop. Needs a recent frame on this connection.
     */
    public boolean dueNoMotion(long now) {
        if (!clock(now) || !bound || stillFrom <= 0 || lastFrame <= 0 || now - lastFrame > LOSS_WINDOW_MS
                || now - stillFrom < NO_MOTION_MS) return false;
        // A carried count waits for a distance reading from after the drop, or for the bound.
        if (carryHold > 0 && now < carryHold) return false;
        clearStill(); return true;
    }

    /** True only for the policy instance that belongs to the ride still recording. */
    public boolean owns(String activeRideId) { return Objects.equals(rideId, activeRideId); }

    private void clearRun() { runCount = 0; runStart = runLast = 0; }
    private void clearSpeed() { speedRaw = -1; speedMono = 0; }
    private void clearStill() { stillFrom = stillPush = 0; stillOdometer = stillTrip = Double.NaN; carryHold = carryAfter = 0; carryOdometer = carryTrip = Double.NaN; }
    private void clearHeld() { heldFrom = heldFrame = heldHold = 0; heldOdometer = heldTrip = Double.NaN; }
    /** Rule 3's speed evidence from one connected observation. */
    private void still(long now, long pushMono, int speedRaw, int driving) {
        if (pushMono > 0 && pushMono != stillPush && pushMono <= now && now - pushMono <= MOTION_FRESH_MS) {
            stillPush = pushMono;
            if (speedRaw > 0 || driving == 1) stillFrom = Math.max(stillFrom, pushMono);
        }
    }

    /**
     * The scooter's latest odometer and trip in km (NaN when missing or stale), right after a
     * {@link #connected} observation at the same time: going up is rule 3 motion. Ignored while this
     * ride's scooter is not connected; a drop (trip reset) only moves the baseline.
     */
    public void distance(long now, double odometerKm, double tripKm) {
        if (!clock(now) || !bound || stillFrom <= 0) return;
        if (rose(stillOdometer, odometerKm) || rose(stillTrip, tripKm)) stillFrom = Math.max(stillFrom, now);
        if (odometerKm >= 0) stillOdometer = odometerKm;
        if (tripKm >= 0) stillTrip = tripKm;
        // A reading at most READING_AGE_MS old, seen after carryAfter, was taken after the drop. It releases
        // the hold only when the same metric had a value before the gap and has not gone down since (a trip
        // reset, or an odometer that reads lower, leaves that metric unable to say anything about the gap).
        if (carryHold > 0 && now > carryAfter) {
            if (comparable(carryOdometer, odometerKm) || comparable(carryTrip, tripKm)) carryHold = 0;
            else {
                if (odometerKm >= 0 && carryOdometer >= 0) carryOdometer = Double.NaN;
                if (tripKm >= 0 && carryTrip >= 0) carryTrip = Double.NaN;
            }
        }
    }
    /** A distance reading went up by at least its 0.1 km step; a drop (trip reset) only moves the baseline. */
    /** A fresh reading of a metric known before the gap, at or above that value: it shows whether the scooter moved. */
    private static boolean comparable(double before, double after) { return before >= 0 && after >= 0 && after >= before - 1e-6; }
    private static boolean rose(double before, double after) { return before >= 0 && after >= 0 && after > before + 1e-6; }
    private void clearQuiet() { quiet = Quiet.NONE; quietFrom = quietMs = 0; }
    private void startQuiet(long from, long ms, Quiet why) { quiet = why; quietFrom = from; quietMs = ms; }

    /** A clock that moves backwards can no longer measure the gap or the stop: fail closed. */
    private boolean clock(long now) {
        if (now <= 0 || now < lastNow) { reset(); lastNow = Math.max(0, now); return false; }
        lastNow = now; return true;
    }
}
