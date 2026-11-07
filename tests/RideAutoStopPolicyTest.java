package com.jjricks.volt;

/** Deterministic clock-driven checks; synthetic identities only. */
public final class RideAutoStopPolicyTest {
    private static int checks;
    private static final String RIDE = "ride-one", PROFILE = "one", SERIAL = "X2538SYNTHETIC0001";
    private static final long T = 1_000_000;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static RideAutoStopPolicy policy() { return new RideAutoStopPolicy(RIDE, PROFILE, SERIAL); }
    /** One fresh motion push received at {@code at} on transport 1, observed at the same instant. */
    private static void push(RideAutoStopPolicy p, long at, int speed, int driving) { p.connected(at, PROFILE, SERIAL, 1, at, at, speed, driving); }
    /** Ride, then stop for {@code seconds} with one push per second; returns the last push time. */
    private static long rideThenPark(RideAutoStopPolicy p, long from, int seconds) {
        push(p, from, 150, 1); push(p, from + 1000, 80, 1);
        long at = from + 2000;
        for (int i = 0; i <= seconds; i++, at += 1000) push(p, at, 0, 0);
        return at - 1000;
    }

    public static void main(String[] args) {
        stationaryDropTimesOut();
        reconnectJustBeforeTimeout();
        repeatedDropsDoNotAccumulate();
        movingAndUncertainLossesStayRecoverable();
        staleAndMissingSpeedNeverArm();
        staleRunCannotBeRevivedByOneZero();
        clockEdges();
        identityProfileAndExplicitStops();
        failureAndLateCallbacks();
        walkingQuietEndsAfterOneMinute();
        speedLineAndLongBranch();
        staleOrMissingMotionTakesLongBranch();
        reconnectCancelsQuiet();
        repeatedCallbacksKeepQuietDeadline();
        phoneBluetoothOff();
        restartWhileDisconnected();
        cancellationsStopQuiet();
        parkedRuleWins();
        System.out.println("RideAutoStopPolicyTest: " + checks + " assertions passed");
    }

    private static void stationaryDropTimesOut() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4);
        // The link goes silent; the 12 s read timeout then reports the loss.
        p.connected(last + 12000, PROFILE, SERIAL, 1, last, last, 0, 0);
        long lost = last + 13000; p.disconnected(lost, true);
        check(p.parkedArmed() && p.remainingMs(lost) == RideAutoStopPolicy.DISCONNECT_GRACE_MS, "Parked loss starts the grace");
        p.disconnected(lost + 1000, true);
        check(p.remainingMs(lost + 1000) == 9000, "Repeated loss callbacks do not restart the grace");
        check(!p.due(lost + 9999), "Not due a millisecond early");
        check(p.due(lost + 10000), "Due after ten continuous seconds");
        check(!p.due(lost + 11000) && !p.parkedArmed(), "Fires once");
        p = policy(); last = rideThenPark(p, T, 3);
        p.disconnected(last + 500, true);
        check(p.parkedArmed(), "Three seconds of zeros across four pushes arm a prompt native disconnect");
        p = policy(); last = rideThenPark(p, T, 4);
        p.disconnected(last + RideAutoStopPolicy.LOSS_WINDOW_MS + 1, true);
        check(!p.parkedArmed(), "A loss long after the last frame is not explained by walking away");
    }

    private static void reconnectJustBeforeTimeout() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        p.connected(lost + 9999, PROFILE, SERIAL, 2, lost + 9999, 0, -1, -1);
        check(!p.parkedArmed() && !p.due(lost + 10000) && !p.due(lost + 60000), "Authenticated reconnect just before the deadline cancels");
        p = policy(); last = rideThenPark(p, T, 4); lost = last + 2000;
        p.disconnected(lost, true);
        p.connected(lost + 5000, PROFILE, "", 2, lost + 5000, 0, -1, -1);
        check(!p.parkedArmed(), "Reconnect before the serial is read still proves the scooter is near");
    }

    private static void repeatedDropsDoNotAccumulate() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        long back = lost + 6000; p.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1);
        p.disconnected(back + 1000, true);
        check(!p.parkedArmed(), "A drop right after reconnect has no new stationary evidence");
        check(!p.due(lost + 10000) && !p.due(back + 1000 + 10000), "Separate short gaps never add up");
        // New evidence on the new connection arms a new, separately timed loss.
        long at = back + 12000; for (int i = 0; i < 4; i++, at += 1000) p.connected(at, PROFILE, SERIAL, 3, at, at, 0, 0);
        p.disconnected(at, true);
        check(p.parkedArmed() && !p.due(at + 9999) && p.due(at + 10000), "Grace restarts from the latest continuous loss");
    }

    private static void movingAndUncertainLossesStayRecoverable() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4);
        push(p, last + 1000, 120, 1); p.disconnected(last + 3000, true);
        check(!p.parkedArmed() && !p.due(last + 60000), "Moving loss (tunnel or glitch) keeps recording");
        p = policy(); last = rideThenPark(p, T, 4);
        push(p, last + 1000, 0, 2); p.disconnected(last + 3000, true);
        check(!p.parkedArmed(), "Nonzero driving status at zero speed is not parked");
        p = policy(); last = rideThenPark(p, T, 4);
        p.disconnected(last + 2000, false);
        check(!p.parkedArmed(), "A non-recoverable loss (owner rejected, Bluetooth off) never arms");
        p = policy(); long at = T;
        for (int i = 0; i < 6; i++, at += 1000) push(p, at, 0, 0);
        p.disconnected(at, true);
        check(!p.parkedArmed(), "Zeros from a scooter that never moved this session (startup or restart) never arm");
        p = policy(); push(p, T, 150, 1); push(p, T + 1000, 0, 0); push(p, T + 2000, 0, 0);
        p.disconnected(T + 3000, true);
        check(!p.parkedArmed(), "Two zeros two seconds apart are a pause, not a stop");
        p = policy(); push(p, T, 150, 1); push(p, T + 1000, 0, 0); push(p, T + 4500, 0, 0);
        p.disconnected(T + 5000, true);
        check(p.parkedArmed(), "Two distinct zero pushes spanning more than three seconds arm");
    }

    private static void staleAndMissingSpeedNeverArm() {
        RideAutoStopPolicy p = policy();
        push(p, T, 150, 1);
        for (int i = 1; i <= 8; i++) p.connected(T + i * 1000, PROFILE, SERIAL, 1, T + i * 1000, T + 1000, 0, 0);
        p.disconnected(T + 9000, true);
        check(!p.parkedArmed(), "One zero push seen repeatedly is one sample, not a sustained stop");
        p = policy(); long last = rideThenPark(p, T, 4);
        // Other packets keep arriving but motion pushes stopped: the zeros went stale.
        p.connected(last + 9000, PROFILE, SERIAL, 1, last + 9000, last, 0, 0);
        p.disconnected(last + 10000, true);
        check(!p.parkedArmed(), "Zeros older than six seconds at the last frame are stale");
        p = policy(); push(p, T, 150, 1);
        long at = T + 1000; for (int i = 0; i < 5; i++, at += 1000) p.connected(at + 7000, PROFILE, SERIAL, 1, at + 7000, at, 0, 0);
        p.disconnected(at + 7000, true);
        check(!p.parkedArmed(), "Pushes first seen more than six seconds late are not fresh evidence");
        p = policy(); last = rideThenPark(p, T, 4);
        push(p, last + 1000, -1, -1); p.disconnected(last + 2000, true);
        check(!p.parkedArmed(), "A missing speed push clears the stationary run");
        p = policy(); push(p, T, 150, 1);
        for (int i = 1; i <= 6; i++) p.connected(T + i * 1000, PROFILE, SERIAL, 1, T + i * 1000, 0, -1, -1);
        p.disconnected(T + 7000, true);
        check(!p.parkedArmed(), "No motion data after riding never becomes zero");
        p = policy(); last = rideThenPark(p, T, 4);
        p.connected(last + 1000, PROFILE, SERIAL, 1, last + 1000, last - 2500, 0, 0);
        p.disconnected(last + 2000, true);
        check(!p.parkedArmed(), "A push timestamp that moves backwards is uncertain");
        p = policy(); push(p, T, 150, 1);
        p.connected(T + 1000, PROFILE, SERIAL, 1, T + 1000, T + 5000, 0, 0);
        p.connected(T + 6000, PROFILE, SERIAL, 1, T + 6000, T + 5000, 0, 0);
        p.disconnected(T + 6500, true);
        check(!p.parkedArmed(), "A push stamped in the future is rejected");
    }

    private static void clockEdges() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        check(!p.due(lost - 1) && !p.parkedArmed(), "Clock rollback while waiting cancels instead of guessing");
        check(!p.due(lost + 20000), "Nothing fires after a rollback");
        p = policy(); last = rideThenPark(p, T, 4);
        p.connected(last - 5000, PROFILE, SERIAL, 1, last - 5000, last - 5000, 0, 0);
        p.disconnected(last, true);
        check(!p.parkedArmed(), "Rollback during the stop discards the run");
        p = policy(); last = rideThenPark(p, T, 4);
        p.disconnected(0, true);
        check(!p.parkedArmed(), "A zero clock is invalid");
        p = policy(); last = rideThenPark(p, T, 4); lost = last + 1000;
        p.disconnected(lost, true);
        check(p.due(lost + RideAutoStopPolicy.DISCONNECT_GRACE_MS + 3_600_000L), "A long sleep is still one continuous disconnection");
    }

    private static void staleRunCannotBeRevivedByOneZero() {
        for (boolean callbacks : new boolean[]{false, true}) {
            RideAutoStopPolicy p = policy();
            push(p, T, 150, 1); push(p, T + 1000, 0, 0);
            if (callbacks) for (int sec = 2; sec <= 61; sec++)
                p.connected(T + sec * 1000, PROFILE, SERIAL, 1, T + sec * 1000, T + 1000, 0, 0);
            push(p, T + 62000, 0, 0);
            p.disconnected(T + 63000, true);
            check(!p.parkedArmed() && !p.due(T + 73000), "One zero cannot bridge missing motion, callbacks=" + callbacks);
            // Fresh sustained evidence after the gap may qualify independently.
            for (int sec = 80; sec <= 84; sec++)
                p.connected(T + sec * 1000, PROFILE, SERIAL, 2, T + sec * 1000, T + sec * 1000, 0, 0);
            p.disconnected(T + 85000, true);
            check(p.parkedArmed(), "Fresh sustained stop after uncertainty can qualify");
        }
        RideAutoStopPolicy p = policy(); long last = rideThenPark(p, T, 4);
        p.disconnected(last + 1000, true);
        p.disconnected(last + 2000, false);
        check(!p.parkedArmed() && !p.due(last + 20000), "A later nonrecoverable loss cancels the pending grace");
    }

    private static void identityProfileAndExplicitStops() {
        RideAutoStopPolicy p = policy();
        long at = T; p.connected(at, PROFILE, "OTHER2538SYNTHETIC", 1, at, at, 150, 1);
        for (int i = 1; i <= 5; i++) p.connected(at + i * 1000, PROFILE, "OTHER2538SYNTHETIC", 1, at + i * 1000, at + i * 1000, 0, 0);
        p.disconnected(at + 6000, true);
        check(!p.parkedArmed(), "Another scooter's zeros are not evidence");
        p = policy(); p.connected(at, "two", SERIAL, 1, at, at, 150, 1);
        for (int i = 1; i <= 5; i++) p.connected(at + i * 1000, "two", SERIAL, 1, at + i * 1000, at + i * 1000, 0, 0);
        p.disconnected(at + 6000, true);
        check(!p.parkedArmed(), "Another profile's zeros are not evidence");
        p = policy(); long last = rideThenPark(p, T, 4); p.disconnected(last + 1000, true);
        p.reset();
        check(!p.parkedArmed() && !p.due(last + 20000), "Explicit stop, user disconnect, setting off or new profile cancels");
        p = policy(); last = rideThenPark(p, T, 4); p.reset();
        p.disconnected(last + 1000, true);
        check(!p.parkedArmed(), "Reset forgets earlier stationary evidence");
        p = policy(); last = rideThenPark(p, T, 4);
        at = last + 1000; for (int i = 0; i < 5; i++, at += 1000) p.connected(at, PROFILE, SERIAL, 7, at, at, 0, 0);
        p.disconnected(at, true);
        check(p.parkedArmed(), "Motion earlier in the ride is remembered across a reconnect");
        p = policy(); last = rideThenPark(p, T, 4);
        p.connected(last + 1000, PROFILE, SERIAL, 7, last + 1000, last + 1000, 0, 0);
        p.disconnected(last + 1500, true);
        check(!p.parkedArmed(), "A new transport restarts the stationary run");
        boolean threw = false;
        try { new RideAutoStopPolicy(RIDE, PROFILE, ""); } catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "A policy needs a verified serial");
    }

    /** Mirrors ScooterService: one policy per ride id, fired once, guarded by owner and instance. */
    private static void failureAndLateCallbacks() {
        RideAutoStopPolicy old = policy();
        long last = rideThenPark(old, T, 4), lost = last + 1000;
        old.disconnected(lost, true);
        check(old.due(lost + 10000), "First attempt");
        // Storage failed: the ride stays recording. No automatic retry storm on later ticks.
        check(!old.due(lost + 11000) && !old.due(lost + 60000), "A failed save is not retried automatically");
        old.connected(lost + 70000, PROFILE, SERIAL, 2, lost + 70000, 0, -1, -1);
        check(!old.parkedArmed(), "After a failed save, reconnecting returns to ordinary recording");

        RideAutoStopPolicy first = policy(); last = rideThenPark(first, T, 4); first.disconnected(last + 1000, true);
        RideAutoStopPolicy next = new RideAutoStopPolicy("ride-two", PROFILE, SERIAL);
        check(!first.owns("ride-two") && next.owns("ride-two"), "A late decision from the previous ride's policy cannot match the new ride");
        check(!next.parkedArmed() && !next.due(last + 20000), "A new ride starts unarmed");
        RideAutoStopPolicy restarted = policy();
        restarted.disconnected(T + 5000, true);
        check(!restarted.parkedArmed() && !restarted.due(T + 60000), "A restarted service sees the gap but never treats it as parking");
        check(restarted.quiet() == RideAutoStopPolicy.Quiet.RESTART, "It waits out the five-minute quiet rule instead");
    }

    // ---- Went-quiet rule: a scripted power-off is pushes at 1 Hz, silence, the loss, then ticks.

    /** Pushes at 1 Hz at {@code speed} from {@code from}; returns the last push time. */
    private static long walk(RideAutoStopPolicy p, long from, int seconds, int speed, int driving) {
        push(p, from, 150, 1);
        long at = from + 1000;
        for (int i = 0; i < seconds; i++, at += 1000) push(p, at, speed, driving);
        return at - 1000;
    }
    /** The 12 s silent link: ticks keep seeing the same last frame and push, then the watchdog reports the loss. */
    private static long silence(RideAutoStopPolicy p, long last, int speed, int driving) {
        for (long at = last + 1000; at <= last + 12000; at += 1000) p.connected(at, PROFILE, SERIAL, 1, last, last, speed, driving);
        long lost = last + 13000; p.disconnected(lost, true); return lost;
    }

    private static void walkingQuietEndsAfterOneMinute() {
        for (int speed : new int[]{16, 32, 48}) {
            RideAutoStopPolicy p = policy();
            long last = walk(p, T, 20, speed, 1), lost = silence(p, last, speed, 1);
            check(!p.parkedArmed() && p.quietArmed() && p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "Walking pace starts the one-minute countdown, raw=" + speed);
            check(p.quietSince() == last && p.remainingMs(lost) == RideAutoStopPolicy.QUIET_SLOW_MS - 13000, "Timed from the last frame heard, not from the watchdog");
            for (long at = lost + 2000; at < last + 60000; at += 2000) { p.disconnected(at, true); check(!p.dueQuiet(at) && !p.due(at), "Not due while waiting"); }
            check(!p.dueQuiet(last + 59999), "Not due a millisecond early");
            check(p.dueQuiet(last + 60000), "Due exactly one minute after the last frame");
            check(!p.dueQuiet(last + 61000) && !p.armed() && !p.due(last + 61000), "Fires once");
            p.disconnected(last + 62000, true);
            check(!p.armed() && !p.dueQuiet(last + 400000), "Later callbacks of the same loss never start another countdown");
        }
        RideAutoStopPolicy p = policy();
        push(p, T, 150, 1); push(p, T + 1000, 0, 0);
        long lost = silence(p, T + 1000, 0, 0);
        check(!p.parkedArmed() && p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "One zero push is not parked, but it is quiet at a stop");
        check(!p.dueQuiet(T + 60999) && p.dueQuiet(T + 61000), "It ends after a minute (the parked rule never did)");
        p = policy(); push(p, T, 150, 1); push(p, T + 1000, 0, 0); push(p, T + 2000, 0, 0);
        p.disconnected(T + 2500, true);
        check(!p.parkedArmed() && p.quiet() == RideAutoStopPolicy.Quiet.SLOW && p.dueQuiet(T + 62000), "Under three seconds of zeros also ends at a minute");
        p = policy(); long last = walk(p, T, 10, 30, -1); silence(p, last, 30, -1);
        check(p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "Driving status is not needed for the walking line");
        p = policy(); push(p, T, 30, 1); lost = silence(p, T, 30, 1);
        check(p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "Walking from the first push still counts (no earlier motion needed)");
    }

    private static void speedLineAndLongBranch() {
        RideAutoStopPolicy p = policy();
        long last = walk(p, T, 20, 150, 1), lost = silence(p, last, 150, 1);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG, "Riding speed takes the five-minute branch");
        check(!p.dueQuiet(last + 60000) && !p.dueQuiet(last + 299999) && p.dueQuiet(last + 300000), "Ends at five minutes, not one");
        p = policy(); last = walk(p, T, 5, RideAutoStopPolicy.WALKING_RAW, 1); silence(p, last, RideAutoStopPolicy.WALKING_RAW, 1);
        check(p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "6.4 km/h is still walking pace");
        p = policy(); last = walk(p, T, 5, RideAutoStopPolicy.WALKING_RAW + 1, 1); silence(p, last, RideAutoStopPolicy.WALKING_RAW + 1, 1);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG, "Just over the line is riding speed");
        p = policy(); last = walk(p, T, 10, 150, 1); push(p, last + 1000, 40, 1); silence(p, last + 1000, 40, 1);
        check(p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "The newest push decides, not the fastest one");
    }

    private static void staleOrMissingMotionTakesLongBranch() {
        RideAutoStopPolicy p = policy();
        long last = walk(p, T, 10, 20, 1);
        // Other frames keep arriving but motion pushes stopped: the walking speed went stale.
        p.connected(last + 7000, PROFILE, SERIAL, 1, last + 7000, last, 20, 1);
        p.disconnected(last + 9000, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG && p.quietSince() == last + 7000, "Speed older than six seconds at the last frame is stale");
        p = policy(); last = walk(p, T, 10, 20, 1); push(p, last + 1000, -1, -1); p.disconnected(last + 2000, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG, "A missing speed is unknown, not walking");
        p = policy(); push(p, T, 20, 1);
        p.connected(T + 9000, PROFILE, SERIAL, 1, T + 9000, T + 1000, 20, 1);
        p.disconnected(T + 9500, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG, "A push first seen more than six seconds late is not fresh");
        p = policy(); for (int i = 0; i < 5; i++) p.connected(T + i * 1000, PROFILE, SERIAL, 1, T + i * 1000, 0, -1, -1);
        p.disconnected(T + 5000, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG && p.quietSince() == T + 4000, "No motion push at all takes five minutes");
        p = policy(); last = walk(p, T, 10, 20, 1);
        p.disconnected(last + RideAutoStopPolicy.LOSS_WINDOW_MS + 1, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG && p.quietSince() == last, "A loss seen long after the last frame cannot vouch for the speed");
        p = policy(); p.connected(T, PROFILE, SERIAL, 1, 0, 0, -1, -1); p.disconnected(T + 1000, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG && p.quietSince() == T + 1000, "No frame on this connection: timed from the loss");
        p = policy(); last = walk(p, T, 10, 20, 1);
        p.connected(last + 1000, PROFILE, SERIAL, 2, last + 1000, 0, -1, -1);
        p.disconnected(last + 2000, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG, "Walking speed from an earlier connection does not carry over");
    }

    private static void reconnectCancelsQuiet() {
        RideAutoStopPolicy p = policy();
        long last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        p.connected(last + 59000, PROFILE, SERIAL, 2, last + 59000, 0, -1, -1);
        check(!p.armed() && !p.dueQuiet(last + 60000) && !p.dueQuiet(last + 69000), "A reconnect at 59 s cancels");
        p.disconnected(last + 70000, true);
        check(p.quietSince() == last + 59000 && !p.dueQuiet(last + 118999) && p.dueQuiet(last + 359000),
                "A later loss starts a fresh timer from its own last frame");
        p = policy(); last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        p.connected(last + 30000, PROFILE, "", 2, last + 30000, 0, -1, -1);
        check(!p.armed(), "Any reconnect cancels, even before the serial is read");
        p.disconnected(last + 31000, true);
        check(!p.armed(), "That connection was never proven to be this scooter, so its loss starts nothing");
        // Many 20 s drops over half an hour: each reconnect cancels, nothing adds up.
        p = policy(); long at = T;
        for (int drop = 0; drop < 60; drop++) {
            long walked = walk(p, at, 5, 20, 1);
            p.disconnected(walked + 2000, true);
            check(!p.dueQuiet(walked + 20000), "Never due during a short drop");
            at = walked + 20000; p.connected(at, PROFILE, SERIAL, 3 + drop, at, 0, -1, -1);
            check(!p.armed(), "Each reconnect cancels");
            at += 1000;
        }
        check(!p.dueQuiet(at + 400000), "Separate drops never add up");
    }

    private static void repeatedCallbacksKeepQuietDeadline() {
        RideAutoStopPolicy p = policy();
        long last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        for (long at = last + 14000; at < last + 60000; at += 1000) {
            p.disconnected(at, true);
            check(p.quietSince() == last && p.quiet() == RideAutoStopPolicy.Quiet.SLOW, "Reconnect attempts while waiting neither restart nor clear the timer");
        }
        p.disconnected(last + 59500, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(p.quiet() == RideAutoStopPolicy.Quiet.SLOW && p.dueQuiet(last + 60000), "Bluetooth off during the countdown keeps its deadline");
        p = policy(); last = walk(p, T, 10, 150, 1); silence(p, last, 150, 1);
        p.disconnected(last + 100000, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG && p.quietSince() == last && p.dueQuiet(last + 300000), "Bluetooth off never restarts a running five-minute countdown");
    }

    private static void phoneBluetoothOff() {
        for (int speed : new int[]{0, 20, 150}) {
            RideAutoStopPolicy p = policy();
            long last = walk(p, T, 10, speed, 1), off = last + 400;
            p.disconnected(off, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
            check(!p.parkedArmed() && p.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF && p.quietSince() == last,
                    "Phone Bluetooth off mid-ride starts five minutes from the last frame, raw=" + speed);
            for (long at = off + 1000; at < last + 300000; at += 10000) { p.disconnected(at, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF); check(!p.dueQuiet(at), "Not due early"); }
            check(!p.dueQuiet(last + 299999) && p.dueQuiet(last + 300000) && !p.dueQuiet(last + 301000), "Ends once at five minutes");
        }
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4);
        p.disconnected(last + 400, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(!p.parkedArmed() && p.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF, "The parked rule needs a recoverable link");
        p = policy(); last = walk(p, T, 10, 150, 1);
        p.disconnected(last + 400, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        p.connected(last + 120000, PROFILE, SERIAL, 2, last + 120000, 0, -1, -1);
        check(!p.armed() && !p.dueQuiet(last + 300000), "Bluetooth back on and reconnected cancels");
        p = policy(); last = walk(p, T, 10, 150, 1);
        p.disconnected(last + 400, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        p.disconnected(last + 60000, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(p.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF && p.dueQuiet(last + 300000), "Bluetooth on but the scooter still gone keeps the same deadline");
        p = policy(); last = walk(p, T, 10, 150, 1);
        p.disconnected(last + 400, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        p.disconnected(last + 1000, false);
        check(!p.armed() && !p.dueQuiet(last + 300000), "Permission revoked after Bluetooth off cancels");
        // Parked grace already running, then Bluetooth off: the parked rule keeps running.
        p = policy(); last = rideThenPark(p, T, 4); p.disconnected(last + 1000, true);
        p.disconnected(last + 3000, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(p.parkedArmed() && !p.quietArmed() && p.due(last + 11000), "Bluetooth off does not delay a parked end");
        p.disconnected(last + 12000, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(!p.armed() && !p.dueQuiet(last + 400000), "After the parked end fired (even if the save failed) Bluetooth off starts nothing");
        // Quiet countdown fired, then Bluetooth off: no retry.
        p = policy(); last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        check(p.dueQuiet(last + 60000), "Quiet end fired");
        p.disconnected(last + 70000, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(!p.armed() && !p.dueQuiet(last + 400000), "A fired quiet end is not retried when Bluetooth goes off");
        // A non-matching scooter's connection never vouches for this ride.
        p = policy(); p.connected(T, PROFILE, "OTHER2538SYNTHETIC", 1, T, T, 150, 1);
        p.disconnected(T + 1000, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(!p.armed(), "Bluetooth off after another scooter's connection starts nothing");
    }

    private static void restartWhileDisconnected() {
        RideAutoStopPolicy p = policy();
        p.disconnected(T, true);
        check(!p.parkedArmed() && p.quiet() == RideAutoStopPolicy.Quiet.RESTART && p.quietSince() == T, "A restart while disconnected takes five minutes from creation");
        for (long at = T + 1000; at < T + 300000; at += 5000) { p.disconnected(at, true); check(!p.dueQuiet(at) && !p.due(at), "Not due early"); }
        check(p.dueQuiet(T + 300000) && !p.dueQuiet(T + 301000), "Ends once at five minutes");
        p = policy(); p.disconnected(T, true);
        p.connected(T + 4000, PROFILE, SERIAL, 1, T + 4000, 0, -1, -1);
        check(!p.armed(), "The scooter coming back cancels the restart countdown");
        p = policy(); p.disconnected(T, RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF);
        check(p.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF && p.dueQuiet(T + 300000), "A restart with the phone's Bluetooth off also takes five minutes");
        p = policy(); p.disconnected(T, false);
        p.disconnected(T + 1000, true);
        check(!p.armed(), "A restart that first sees an unrecoverable loss waits for a real connection");
    }

    private static void cancellationsStopQuiet() {
        RideAutoStopPolicy p = policy();
        long last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        p.reset();
        check(!p.armed() && !p.dueQuiet(last + 60000), "Explicit Disconnect, profile change, setting off or a storage error cancels");
        p.disconnected(last + 70000, true);
        check(!p.armed() && !p.dueQuiet(last + 400000), "After a reset, a loss without a new connection starts nothing");
        p = policy(); last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        p.disconnected(last + 20000, false);
        check(!p.armed() && !p.dueQuiet(last + 60000), "A non-recoverable loss cancels");
        p = policy(); last = walk(p, T, 10, 20, 1); long lost = silence(p, last, 20, 1);
        check(!p.dueQuiet(lost - 1) && !p.armed() && !p.dueQuiet(last + 400000), "Clock rollback cancels");
        p = policy(); last = walk(p, T, 10, 20, 1); silence(p, last, 20, 1);
        check(p.dueQuiet(last + 3_600_000L), "A long sleep is still one continuous disconnection");
        p = policy(); last = walk(p, T, 10, 20, 1);
        p.disconnected(last + 1000, (RideAutoStopPolicy.Loss) null);
        check(!p.armed(), "An unknown loss kind fails closed");
    }

    private static void parkedRuleWins() {
        RideAutoStopPolicy p = policy();
        long last = rideThenPark(p, T, 4), lost = last + 2000;
        p.disconnected(lost, true);
        check(p.parkedArmed() && !p.quietArmed() && p.remainingMs(lost) == RideAutoStopPolicy.DISCONNECT_GRACE_MS, "Parked evidence keeps the ten-second rule");
        check(p.due(lost + 10000) && !p.dueQuiet(last + 60000) && !p.dueQuiet(last + 400000), "Only one end fires; a failed save is not retried by the quiet rule");
    }
}
