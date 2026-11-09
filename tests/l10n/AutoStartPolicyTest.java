package com.jjricks.volt;

/** Auto-record and auto ride mode: every gate and hold rule, on synthesized inputs (no clock, sensor or scooter). */
public final class AutoStartPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        moving();
        recordingGates();
        recordingHolds();
        rideModeGates();
        rideModeHolds();
        System.out.println("AutoStartPolicyTest: " + checks + " checks");
    }

    private static void moving() {
        check(AutoStartPolicy.moving(true, 1, 1000, 1000), "any nonzero speed counts: raw 1 is 0.1 km/h");
        check(AutoStartPolicy.moving(true, 200, 1000, 1000 + AutoStartPolicy.SPEED_FRESH_MS), "a reading exactly at the limit is fresh");
        check(!AutoStartPolicy.moving(true, 200, 1000, 1001 + AutoStartPolicy.SPEED_FRESH_MS), "a stale reading never counts");
        check(!AutoStartPolicy.moving(false, 200, 1000, 1000), "no link, no motion");
        check(!AutoStartPolicy.moving(true, 0, 1000, 1000), "speed zero is not moving");
        check(!AutoStartPolicy.moving(true, -1, 1000, 1000), "the unknown speed is not moving");
        check(!AutoStartPolicy.moving(true, 200, 0, 1000), "a reading that never arrived is not moving");
        check(!AutoStartPolicy.moving(true, 200, 2000, 1000), "a reading from the future (clock went back) is not moving");
    }

    private static boolean record(AutoStartPolicy p) { return p.shouldRecord(true, true, false, false, false, false, true); }

    private static void recordingGates() {
        AutoStartPolicy p = new AutoStartPolicy();
        p.observeLink(true, 1);
        check(record(p), "all gates open: starts");
        check(!p.shouldRecord(false, true, false, false, false, false, true), "switch off: nothing (the default)");
        check(!p.shouldRecord(true, false, false, false, false, false, true), "not moving, or stale: nothing");
        check(!p.shouldRecord(true, true, true, false, false, false, true), "already recording: nothing");
        check(!p.shouldRecord(true, true, false, true, false, false, true), "charge recording: nothing");
        check(!p.shouldRecord(true, true, false, false, true, false, true), "simulation: nothing");
        check(!p.shouldRecord(true, true, false, false, false, true, true), "still saving the last ride: nothing");
        check(!p.shouldRecord(true, true, false, false, false, false, false), "link not ready: nothing");
    }

    private static void recordingHolds() {
        AutoStartPolicy p = new AutoStartPolicy();
        p.observeLink(true, 1);
        p.riderStoppedRecording();
        check(p.recordHeld() && !record(p), "a manual stop holds it while still moving");
        p.observeLink(true, 1);
        check(!record(p), "the same connection never clears the hold");
        p.observeLink(false, 1);
        check(!record(p) && p.recordHeld(), "the disconnect alone does not clear it");
        p.observeLink(true, 2);
        check(!p.recordHeld() && record(p), "disconnect and reconnect clears the hold");

        p.riderStoppedRecording();
        p.observeLink(true, 3);
        check(!p.recordHeld(), "a drop too quick to see still clears it (new connection number)");

        p.observeLink(false, 3);
        p.riderStoppedRecording();
        check(p.recordHeld(), "a stop made while disconnected holds");
        p.observeLink(true, 3);
        check(!p.recordHeld(), "and the next connection clears it");

        p.recordingStartFailed();
        check(p.recordHeld() && !record(p), "a failed start is not retried on the next speed sample");
        p.observeLink(false, 3);
        p.observeLink(true, 4);
        check(record(p), "the next connection may try again");

        AutoStartPolicy end = new AutoStartPolicy();
        end.observeLink(true, 1);
        check(record(end), "nothing but a rider's stop or a failure sets the hold, so an automatic ride end starts again on motion");
    }

    private static boolean ride(AutoStartPolicy p, boolean clear) { return p.shouldOpenRidingMode(true, true, clear); }

    private static void rideModeGates() {
        AutoStartPolicy p = new AutoStartPolicy();
        p.observeLink(true, 1);
        check(ride(p, true), "all gates open: opens");
        check(!p.shouldOpenRidingMode(false, true, true), "switch off: nothing (the default)");
        check(!p.shouldOpenRidingMode(true, false, true), "not moving, or stale: nothing");
        check(!ride(p, false), "screen not clear: nothing");
        check(AutoStartPolicy.screenClear(true, true, false, false, false), "resumed and focused with nothing open is clear");
        check(!AutoStartPolicy.screenClear(false, true, false, false, false), "not on screen: never (no background launch)");
        check(!AutoStartPolicy.screenClear(true, false, false, false, false), "a dialog or sheet has the focus: never");
        check(!AutoStartPolicy.screenClear(true, true, true, false, false), "riding mode already open: never");
        check(!AutoStartPolicy.screenClear(true, true, false, true, false), "onboarding: never");
        check(!AutoStartPolicy.screenClear(true, true, false, false, true), "editor or setup flow: never");
    }

    private static void rideModeHolds() {
        AutoStartPolicy p = new AutoStartPolicy();
        p.observeLink(true, 1);
        p.rideModeOpened();
        check(p.rideModeHeld() && !ride(p, true), "one open per trigger");
        p.observeLink(false, 1);
        p.observeLink(true, 2);
        check(ride(p, true), "the next connection is a new trigger");
        p.riderLeftRidingMode();
        check(!ride(p, true), "backing out holds while still moving");
        p.observeLink(false, 2);
        check(!ride(p, true), "the disconnect alone does not clear it");
        p.observeLink(true, 3);
        check(ride(p, true), "reconnect clears the back-out hold");
        p.riderStoppedRecording();
        check(ride(p, true) && p.recordHeld(), "the two holds are independent");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
