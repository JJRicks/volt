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
}
