package com.jjricks.volt;

/**
 * 2.0.5 item 5 follow-up: after a ride ends automatically because its scooter went quiet or was switched off, Volt
 * stops reconnecting to that scooter by itself until the rider connects, Volt is opened again, or the scooter is seen
 * pushing live data. Synthetic ids only.
 */
public final class OffReconnectHoldTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        whichEndsPause();
        holdAndRelease();
        onlyWhenSeenOff();
        neverWhileInSight();
        closesAttemptInFlight();
        System.out.println("OffReconnectHoldTest: " + checks + " checks passed");
    }

    private static void whichEndsPause() {
        check(OffReconnectHold.pauses(RideStore.quietStopReason(8, false), true), "Went quiet on a judged scooter: pause");
        check(OffReconnectHold.pauses(RideStore.STOP_AUTO_QUIET, true), "The bare quiet token too");
        check(OffReconnectHold.pauses(RideStore.STOP_AUTO_PARKED, true), "Lost while parked: pause");
        check(!OffReconnectHold.pauses(RideStore.quietStopReason(-1, true), true), "The phone's own Bluetooth off: no pause");
        check(!OffReconnectHold.pauses(RideStore.STOP_AUTO_NO_MOTION, true), "No motion while connected and on: no pause");
        check(!OffReconnectHold.pauses("", true) && !OffReconnectHold.pauses(null, true), "The rider's own stop: no pause");
        check(!OffReconnectHold.pauses(RideStore.quietStopReason(8, false), false) && !OffReconnectHold.pauses(RideStore.STOP_AUTO_PARKED, false),
                "A scooter that is never judged (other models, the simulator): no pause, today's behaviour");
    }

    private static void holdAndRelease() {
        OffReconnectHold h = new OffReconnectHold();
        check(!h.held("a") && !h.held(null), "Nothing held at first");
        h.hold("a");
        check(h.held("a") && !h.held("b"), "Held for that scooter only");
        h.hold(null); h.hold("");
        check(!h.held("") && !h.held(null), "No id, no hold");
        check(h.release("a") && !h.held("a"), "Released for that scooter (the rider's Connect, or a push seen)");
        check(!h.release("a"), "Releasing twice changes nothing");
        h.hold("a"); h.hold("b");
        check(h.releaseAll() && !h.held("a") && !h.held("b"), "Volt opened again releases every hold");
        check(!h.releaseAll(), "Nothing left to release");
    }

    /** Council F05: only an end where Volt saw the scooter off on a live link pauses; a walk-away keeps today's reconnects. */
    private static void onlyWhenSeenOff() {
        String quiet = RideStore.quietStopReason(8, false);
        check(OffReconnectHold.pauses(quiet, true, true) && OffReconnectHold.pauses(RideStore.STOP_AUTO_PARKED, true, true), "Seen off: pause");
        check(!OffReconnectHold.pauses(quiet, true, false) && !OffReconnectHold.pauses(RideStore.STOP_AUTO_PARKED, true, false),
                "A link that dropped and never came back proves nothing: no pause");
        check(!OffReconnectHold.pauses(quiet, false, true), "Never judged: no pause");

        RideAutoStopPolicy p = new RideAutoStopPolicy("ride", "one", "S1");
        p.connected(1000, "one", "S1", 1, 1000, 1000, 250, 1);
        p.disconnected(4000, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(!p.seenOffWhileLinked(), "Dropped: not seen off");
        p.observe(60_000, RideAutoStopPolicy.Link.up("one", "S1", 2, 60_000, 0, -1, -1, false, true, false, 0));
        check(!p.seenOffWhileLinked(), "Reconnected but not answering yet: not seen off");
        p.observe(61_000, RideAutoStopPolicy.Link.up("one", "S1", 2, 61_000, 0, -1, -1, false, true, true, 0));
        check(p.seenOffWhileLinked(), "Answered with no push: seen off");
        p.connected(62_000, "one", "S1", 2, 62_000, 62_000, 0, 0);
        check(!p.seenOffWhileLinked(), "Its first push clears it");
        p.observe(80_000, RideAutoStopPolicy.Link.up("one", "S1", 2, 80_000, 62_000, 0, 0, true, 62_000));
        check(p.seenOffWhileLinked(), "The off verdict on a live link: seen off");
        p.disconnected(85_000, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(p.seenOffWhileLinked(), "Kept through a later drop");
        p.reset();
        check(!p.seenOffWhileLinked(), "A reset clears it");
    }

    /** Council F03: no hold while Volt is in sight, so a power-cycle with Volt in front reconnects as before. */
    private static void neverWhileInSight() {
        OffReconnectHold h = new OffReconnectHold();
        h.hold("a");
        check(!h.held("a", true), "Volt in front: the retry loop and auto-connect reconnect");
        check(h.held("a", false), "Out of sight: background reconnects pause");
    }

    /** Council F01: an attempt still connecting when the hold starts is closed, as Disconnect does before READY. */
    private static void closesAttemptInFlight() {
        check(OffReconnectHold.closesAttempt(true, false), "GATT open, not authenticated: close it");
        check(!OffReconnectHold.closesAttempt(true, true), "An authenticated link stays");
        check(!OffReconnectHold.closesAttempt(false, false), "Nothing open: nothing to close");
    }
}
