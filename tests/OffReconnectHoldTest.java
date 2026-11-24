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
        timelines();
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
        p.observe(60_000, RideAutoStopPolicy.Link.up("one", "S1", 2, 60_000, 0, -1, -1, false, true, 0));
        p.observe(61_000, RideAutoStopPolicy.Link.up("one", "S1", 2, 61_000, 0, -1, -1, false, true, 0));
        check(!p.seenOffWhileLinked(), "Reconnected and answering, but no off verdict yet: not seen off (council F06, round 3)");
        p.observe(70_000, RideAutoStopPolicy.Link.up("one", "S1", 2, 70_000, 0, -1, -1, true, 0));
        check(p.seenOffWhileLinked(), "The off verdict on that reconnect: seen off");
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

    /**
     * BleController's link states as far as the hold touches them, driven by the same decisions it uses
     * (OffReconnectHold.drop at the ride's end, on leaving sight, and in a scheduled retry; releaseAll on coming into
     * sight; auto-connect only from DISCONNECTED). RideAutoStopWireTest.reconnectHoldWiring pins those call sites.
     */
    private static final class FakeBle {
        final OffReconnectHold hold = new OffReconnectHold();
        String state = "READY"; boolean visible = true, gatt = true, authenticated = true, retryPending, scooterOn = false; int attempts;
        void drop() {
            if (!hold.held("a", visible)) return;
            switch (OffReconnectHold.drop(gatt, authenticated, state.equals("WAITING"))) {
                case CLOSE_ATTEMPT: gatt = false; authenticated = false; state = "DISCONNECTED"; retryPending = false; break;
                case STOP_WAITING: state = "DISCONNECTED"; retryPending = false; break;
                default: break;
            }
        }
        /** The link drops; the retry loop schedules a reconnect unless the hold is active. */
        void lost() { gatt = false; authenticated = false; if (hold.held("a", visible)) { state = "DISCONNECTED"; retryPending = false; } else { state = "WAITING"; retryPending = true; } }
        void rideEnded(boolean seenOff) { if (OffReconnectHold.pauses(RideStore.quietStopReason(8, false), true, seenOff)) { hold.hold("a"); drop(); } }
        void leaveSight() { visible = false; drop(); }
        /** The posted retry fires: under an active hold it ends in DISCONNECTED, else it starts an attempt. */
        void retryFires() {
            if (!retryPending) return;
            retryPending = false;
            if (hold.held("a", visible)) { drop(); return; }
            attempt();
        }
        void attempt() { attempts++; gatt = true; authenticated = false; state = "CONNECTING"; }
        void attemptCompletes() { if (!gatt) return; if (scooterOn) { authenticated = true; state = "READY"; } else lost(); }
        /** Volt comes into sight: every hold ends, and auto-connect starts from DISCONNECTED. */
        void reopen() { visible = true; hold.releaseAll(); if (state.equals("DISCONNECTED")) attempt(); }
    }

    /** The hold's timelines (#627 PR description, "State machine"), council round 3 F01, F02, F03. */
    private static void timelines() {
        check(OffReconnectHold.drop(true, false, false) == OffReconnectHold.Drop.CLOSE_ATTEMPT, "an attempt not yet authenticated is closed");
        check(OffReconnectHold.drop(true, true, false) == OffReconnectHold.Drop.NONE, "an authenticated link is never closed by a hold");
        check(OffReconnectHold.drop(false, false, true) == OffReconnectHold.Drop.STOP_WAITING, "a waiting reconnect stops: DISCONNECTED");
        check(OffReconnectHold.drop(false, false, false) == OffReconnectHold.Drop.NONE, "already disconnected: nothing to do");
        // G: the ride ends with Volt in sight; Volt leaves; the retry is skipped; the scooter is switched on; reopening reconnects.
        FakeBle b = new FakeBle();
        b.lost(); check(b.state.equals("WAITING") && b.retryPending, "In sight: the retry loop runs");
        b.rideEnded(true); check(b.hold.held("a") && b.state.equals("WAITING"), "Hold recorded in sight, the retry loop untouched");
        b.leaveSight(); check(b.state.equals("DISCONNECTED") && !b.retryPending, "Leaving sight: the waiting reconnect stops, DISCONNECTED");
        b.retryFires(); check(b.state.equals("DISCONNECTED") && b.attempts == 0, "Nothing reconnects while held and out of sight");
        b.scooterOn = true; b.reopen(); b.attemptCompletes();
        check(b.attempts == 1 && b.state.equals("READY") && !b.hold.held("a"), "Reopening Volt reconnects (auto-connect from DISCONNECTED)");
        // G': the retry fires after leaving sight while it was still pending (state WAITING): it ends in DISCONNECTED.
        b = new FakeBle(); b.lost(); b.rideEnded(true); b.visible = false; b.retryFires();
        check(b.state.equals("DISCONNECTED") && b.attempts == 0, "A skipped retry ends in DISCONNECTED, never stuck WAITING");
        b.reopen(); check(b.attempts == 1, "and reopening reconnects");
        // H: an attempt started on screen is still connecting when Volt leaves: it is closed, nothing reconnects.
        b = new FakeBle(); b.lost(); b.rideEnded(true); b.retryFires();
        check(b.state.equals("CONNECTING") && b.attempts == 1, "In sight the retry starts an attempt");
        b.scooterOn = true; b.leaveSight(); b.attemptCompletes();
        check(b.state.equals("DISCONNECTED") && !b.authenticated, "Leaving sight closes the unauthenticated attempt; it never completes");
        // I: a power-cycle with Volt in front: the hold never applies there, the scooter comes back on, it reconnects.
        b = new FakeBle(); b.lost(); b.rideEnded(true); b.scooterOn = true; b.retryFires(); b.attemptCompletes();
        check(b.state.equals("READY"), "In front of Volt a power-cycle reconnects");
        // C: the walk-away sets no hold, so the background retry carries on.
        b = new FakeBle(); b.visible = false; b.lost(); b.rideEnded(false); b.scooterOn = true; b.retryFires(); b.attemptCompletes();
        check(b.state.equals("READY") && !b.hold.held("a"), "Walk-away: no hold, the background reconnect comes back");
    }
}
