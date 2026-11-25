package com.jjricks.volt;

/** Auto-record and auto ride mode: every gate and hold rule, on synthesized inputs (no clock, sensor or scooter). */
public final class AutoStartPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        moving();
        recordingGates();
        recordingHolds();
        ownPauseKeepsTheHold();
        ownPauseLength();
        recreatedServiceIsNoDisconnect();
        rideModeGates();
        rideModeHolds();
        backgroundPrerequisite();
        backgroundCadence();
        backgroundSwitch();
        backgroundSwitchDrawn();
        locationPrompt();
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
        link(p, true, 1, 0);
        check(record(p), "all gates open: starts");
        check(!p.shouldRecord(false, true, false, false, false, false, true), "switch off: nothing (the default)");
        check(!p.shouldRecord(true, false, false, false, false, false, true), "not moving, or stale: nothing");
        check(!p.shouldRecord(true, true, true, false, false, false, true), "already recording: nothing");
        check(!p.shouldRecord(true, true, false, true, false, false, true), "charge recording: nothing");
        check(!p.shouldRecord(true, true, false, false, true, false, true), "simulation: nothing");
        check(!p.shouldRecord(true, true, false, false, false, true, true), "still saving the last ride: nothing");
        check(!p.shouldRecord(true, true, false, false, false, false, false), "link not ready: nothing");
    }

    /** A link at time t (monotonic ms) to a scooter that is on, with no Disconnect from the rider. */
    private static void link(AutoStartPolicy p, boolean connected, int generation, long t) { p.observeLink(connected, generation, t, false, BLE, 0, false); }
    /** One transport (BleController) for a service's life; a recreated service brings a new one. */
    private static final Object BLE = new Object();
    /** A look with everything: own is Volt's own background pause (out of sight, link closed by Volt). */
    private static void look(AutoStartPolicy p, boolean connected, int generation, long t, boolean off, Object ble, long disconnects, boolean own) {
        p.observeLink(connected, generation, t, off, ble, disconnects, own);
    }

    private static final long MIN = 60_000;

    private static void recordingHolds() {
        AutoStartPolicy p = new AutoStartPolicy();
        link(p, true, 1, 0);
        p.riderStoppedRecording();
        check(p.recordHeld() && !record(p), "a manual stop holds it while still moving");
        link(p, true, 1, 1000);
        check(!record(p), "the same connection never clears the hold");
        link(p, false, 1, 2000);
        check(!record(p) && p.recordHeld(), "the disconnect alone does not clear it");
        link(p, true, 2, 7000);
        check(p.recordHeld() && !record(p), "Volt's own quick reconnect (5 s) keeps the hold");
        link(p, false, 2, 10_000);
        link(p, true, 3, 10_000 + AutoStartPolicy.SESSION_END_MS);
        check(!p.recordHeld() && record(p), "a link gone for SESSION_END_MS is a real end: the reconnect clears the hold");

        p.riderStoppedRecording();
        link(p, true, 4, 10_000 + AutoStartPolicy.SESSION_END_MS + 1000);
        check(p.recordHeld(), "a drop too quick to see (new connection number) keeps it");

        long t = 600_000;
        link(p, true, 4, t);
        link(p, false, 4, t + 1000);
        p.riderStoppedRecording();
        check(p.recordHeld(), "a stop made while disconnected holds");
        link(p, true, 5, t + 5000);
        check(p.recordHeld(), "and a reconnect seconds later keeps it");
        link(p, false, 5, t + 6000);
        link(p, false, 5, t + 6000 + AutoStartPolicy.SESSION_END_MS);
        check(p.recordHeld() && !record(p), "gone long enough ends the session, but nothing starts without a link");
        link(p, true, 6, t + 10 * MIN);
        check(!p.recordHeld(), "the next connection after that clears it");

        AutoStartPolicy fail = new AutoStartPolicy();
        link(fail, true, 1, 0);
        fail.recordingStartFailed();
        check(fail.recordHeld() && !record(fail), "a failed start is not retried on the next speed sample");
        link(fail, false, 1, 1000);
        link(fail, true, 2, 2000);
        check(record(fail), "any next connection, Volt's own too, may try again");

        AutoStartPolicy end = new AutoStartPolicy();
        link(end, true, 1, 0);
        check(record(end), "nothing but a rider's stop or a failure sets the hold, so an automatic ride end starts again on motion");
    }

    /**
     * The owner's report (Nov 24 2026), synthesized: auto-record on, a ride stopped by hand at 16:10, Volt to the background
     * at 16:11:25 (its own pause closes the link) and back with a reconnect at 16:11:30. Rolling the scooter indoors must
     * not start a ride. Only a real end of the session clears the hold.
     */
    private static void ownPauseKeepsTheHold() {
        long stop = 1_000_000, pause = stop + 85_000, back = pause + 5000;
        AutoStartPolicy p = new AutoStartPolicy();
        look(p, true, 7, stop - 1000, false, BLE, 3, false);
        p.riderStoppedRecording();
        p.riderLeftRidingMode();
        look(p, true, 7, pause - 200, false, BLE, 3, false);
        look(p, false, 7, pause, false, BLE, 3, true);         // BACKGROUND_PAUSE, out of sight
        look(p, false, 7, back - 2000, false, BLE, 3, false);  // Volt on screen again: CONNECT_ATTEMPT
        look(p, true, 8, back, false, BLE, 3, false);          // AUTHENTICATED_READY
        check(p.recordHeld() && !record(p), "16:11:30 reconnect after Volt's own background pause: rolling the scooter starts no ride");
        check(p.rideModeHeld() && !ride(p, true), "and the back-out hold stands too");
        // Connect retries and a timeout: 18 s to connect, a retry, then up.
        look(p, false, 8, back + 10_000, false, BLE, 3, false);
        look(p, false, 8, back + 28_000, false, BLE, 3, false);
        look(p, true, 9, back + 30_000, false, BLE, 3, false);
        check(p.recordHeld() && p.rideModeHeld(), "connect retries and a timeout keep both holds");
        // Lost just under the limit: still not an end.
        look(p, false, 9, back + 31_000, false, BLE, 3, false);
        look(p, true, 10, back + 31_000 + AutoStartPolicy.SESSION_END_MS - 1, false, BLE, 3, false);
        check(p.recordHeld(), "a real loss just under SESSION_END_MS keeps the hold");

        // Switched off (ScooterPowerState) while still answering: the session is over; the next look with it on clears.
        long off = back + 10 * MIN;
        look(p, true, 10, off, true, BLE, 3, false);
        check(p.recordHeld(), "while it stays switched off the hold still reads as set");
        p.riderStoppedRecording(); // a stop made after the switch-off ends with the same session
        look(p, true, 10, off + 30_000, false, BLE, 3, false);
        check(!p.recordHeld() && !p.rideModeHeld() && record(p), "switched on again: a new session, both holds clear");

        // The rider's own Disconnect is a real end too: the next connection clears.
        p.riderStoppedRecording();
        look(p, false, 10, off + MIN, false, BLE, 4, false);
        look(p, true, 11, off + MIN + 3000, false, BLE, 4, false);
        check(!p.recordHeld(), "the rider's Disconnect, then Connect: cleared");
        // A first look never reads its Disconnect count as a change.
        AutoStartPolicy fresh = new AutoStartPolicy();
        fresh.riderStoppedRecording();
        look(fresh, true, 1, 5, false, BLE, 9, false);
        check(fresh.recordHeld(), "the first look's Disconnect count is its baseline, not a Disconnect");
    }

    /**
     * #643 round 1, finding 1: Stay connected in background off, a hand stop, then minutes with Volt out of sight. Volt's
     * own pause counts toward OWN_PAUSE_END_MS, never toward SESSION_END_MS; a real loss still ends at SESSION_END_MS.
     */
    private static void ownPauseLength() {
        long t = 5_000_000;
        AutoStartPolicy p = new AutoStartPolicy();
        look(p, true, 1, t, false, BLE, 0, false);
        p.riderStoppedRecording();
        look(p, false, 1, t + 10_000, false, BLE, 0, true);          // the ten-second stop pauses the link
        look(p, false, 1, t + 10_000 + 3 * MIN, false, BLE, 0, false); // opened again 3 minutes later
        look(p, true, 2, t + 10_000 + 3 * MIN + 2000, false, BLE, 0, false);
        check(p.recordHeld() && !record(p), "3 minutes in a pocket (Volt's own pause), reopened: the hold stands");
        // A service stopped in the pause: no look until Volt opens again; the gap counts as the pause it was.
        long u = t + 20 * MIN;
        look(p, false, 2, u, false, BLE, 0, true);
        look(p, false, 2, u + AutoStartPolicy.OWN_PAUSE_END_MS - 60_000, false, BLE, 0, false);
        look(p, true, 3, u + AutoStartPolicy.OWN_PAUSE_END_MS - 58_000, false, BLE, 0, false);
        check(p.recordHeld(), "a pause just under OWN_PAUSE_END_MS (and the minute's reconnect after it): still held");
        long v = u + 2 * AutoStartPolicy.OWN_PAUSE_END_MS;
        look(p, false, 3, v, false, BLE, 0, true);
        look(p, false, 3, v + AutoStartPolicy.OWN_PAUSE_END_MS, false, BLE, 0, false);
        look(p, true, 4, v + AutoStartPolicy.OWN_PAUSE_END_MS + 3000, false, BLE, 0, false);
        check(!p.recordHeld() && record(p), "half an hour in Volt's own pause: a parked scooter, the session is over");
        // A real loss of 2 minutes (Stay connected on, the scooter out of reach) still ends it.
        p.riderStoppedRecording();
        long w = v + 3 * AutoStartPolicy.OWN_PAUSE_END_MS;
        look(p, true, 4, w, false, BLE, 0, false);
        look(p, false, 4, w + 1000, false, BLE, 0, false);
        look(p, true, 5, w + 1000 + AutoStartPolicy.SESSION_END_MS, false, BLE, 0, false);
        check(!p.recordHeld(), "a real loss of SESSION_END_MS ends it as before");
        // Lost for real, then paused: the lost minute and the paused minutes are counted apart; neither reaches its limit.
        p.riderStoppedRecording();
        long x = w + 10 * MIN;
        look(p, false, 5, x, false, BLE, 0, false);
        look(p, false, 5, x + MIN, false, BLE, 0, true);
        look(p, true, 6, x + MIN + 10 * MIN, false, BLE, 0, false);
        check(p.recordHeld(), "a minute lost and ten paused: still held");
    }

    /**
     * #643 round 1, finding 5: the hold lives for the process, but each service's transport counts Disconnects from 0. A
     * recreated service is a new count to compare with, not a Disconnect.
     */
    private static void recreatedServiceIsNoDisconnect() {
        long t = 9_000_000;
        AutoStartPolicy p = new AutoStartPolicy();
        Object first = new Object(), second = new Object();
        look(p, true, 1, t, false, first, 0, false);
        look(p, false, 1, t + 1000, false, first, 1, false);
        look(p, true, 2, t + 4000, false, first, 1, false);  // the rider's Disconnect and Connect (nothing held yet)
        p.riderStoppedRecording();
        look(p, false, 2, t + 10_000, false, first, 1, true);  // out of sight, Volt's pause; the idle service stops
        look(p, false, 0, t + 70_000, false, second, 0, false); // a new service, its count back at 0
        look(p, true, 1, t + 72_000, false, second, 0, false);
        check(p.recordHeld() && !record(p), "a recreated service within the minute: still held");
        look(p, false, 1, t + 80_000, false, second, 1, false);
        look(p, true, 2, t + 83_000, false, second, 1, false);
        check(!p.recordHeld(), "its own Disconnect still counts");
    }

    private static boolean ride(AutoStartPolicy p, boolean clear) { return p.shouldOpenRidingMode(true, true, clear); }

    private static void rideModeGates() {
        AutoStartPolicy p = new AutoStartPolicy();
        link(p, true, 1, 0);
        check(ride(p, true), "all gates open: opens");
        check(!p.shouldOpenRidingMode(false, true, true), "switch off: nothing (the default)");
        check(!p.shouldOpenRidingMode(true, false, true), "not moving, or stale: nothing");
        check(!ride(p, false), "screen not clear: nothing");
        check(AutoStartPolicy.screenClear(true, true, false, false, false, false), "resumed and focused with nothing open is clear");
        check(!AutoStartPolicy.screenClear(false, true, false, false, false, false), "not on screen: never (no background launch)");
        check(!AutoStartPolicy.screenClear(true, false, false, false, false, false), "a dialog or sheet has the focus: never");
        check(!AutoStartPolicy.screenClear(true, true, true, false, false, false), "riding mode already open: never");
        check(!AutoStartPolicy.screenClear(true, true, false, true, false, false), "onboarding: never");
        check(!AutoStartPolicy.screenClear(true, true, false, false, true, false), "editor or setup flow: never");
        check(!AutoStartPolicy.screenClear(true, true, false, false, false, true), "a text field in the window has the focus (a Stats search): never");
    }

    private static void rideModeHolds() {
        AutoStartPolicy p = new AutoStartPolicy();
        link(p, true, 1, 0);
        p.rideModeOpened();
        check(p.rideModeHeld() && !ride(p, true), "one open per trigger");
        link(p, false, 1, 1000);
        link(p, true, 2, 3000);
        check(ride(p, true), "the next connection, Volt's own included, is a new trigger");
        p.riderLeftRidingMode();
        check(!ride(p, true), "backing out holds while still moving");
        link(p, false, 2, 4000);
        check(!ride(p, true), "the disconnect alone does not clear it");
        link(p, true, 3, 9000);
        check(!ride(p, true), "Volt's own quick reconnect keeps the back-out hold");
        link(p, false, 3, 10_000);
        link(p, true, 4, 10_000 + AutoStartPolicy.SESSION_END_MS);
        check(ride(p, true), "a real end of the session clears the back-out hold");
        p.riderStoppedRecording();
        check(ride(p, true) && p.recordHeld(), "the two holds are independent");
    }

    /**
     * Auto-record is not a reason to stay connected. With nothing else keeping the service, leaving Volt still
     * arms the ten-second stop unless the rider turned on Stay connected in background (off by default).
     */
    private static void backgroundPrerequisite() {
        WorkReasons idle = new WorkReasons(false, false, false, false, false, false);
        check(!idle.keepsServiceUp(false, false), "default background off, out of sight, no work: the service does not stay up");
        check(!idle.skipsTenSecondStop(true, false), "default background off: leaving arms the ten-second stop");
        check(idle.keepsServiceUp(false, true) && idle.skipsTenSecondStop(true, true), "background connection on: no ten-second stop, so auto-record can see motion");
        WorkReasons recording = new WorkReasons(true, false, false, false, false, false);
        check(recording.keepsServiceUp(false, false) && recording.skipsTenSecondStop(true, false), "a recording already running stays connected with background off");
    }

    /**
     * A healthy background link polls status every 15 s, so at most fresh-motion moments its status is past ready()'s
     * 7 s. The service gates on the cadence-aware link; the speed sample's own freshness is unchanged.
     */
    private static void backgroundCadence() {
        long status = 1000, sample = 9000;
        boolean background = StatusCadence.linked(true, status, sample, false);
        check(background && !StatusCadence.linked(true, status, sample, true), "9 s into the poll cycle the link is up on its cadence, past the foreground window");
        AutoStartPolicy p = new AutoStartPolicy();
        link(p, true, 1, 0);
        check(p.shouldRecord(true, AutoStartPolicy.moving(true, 200, sample, sample), false, false, false, false, background), "a fresh moving sample between polls starts");
        long nextStatus = status + StatusCadence.POLL_SLOW_MS;
        check(!p.shouldRecord(true, AutoStartPolicy.moving(true, 200, sample, nextStatus), false, false, false, false, StatusCadence.linked(true, nextStatus, nextStatus, false)), "the same sample at the next poll has expired: still no stale start");
        check(!p.shouldRecord(true, AutoStartPolicy.moving(true, 200, sample, sample), false, false, false, false, false), "a link past its cadence, or bound to another scooter, never starts");
        check(!StatusCadence.linked(true, status, status + StatusCadence.SILENCE_SLOW_MS + 1, false), "past the background silence limit the link is gone");
        check(!StatusCadence.linked(false, status, sample, false), "an unauthenticated or unbound link is never up");
    }

    /** Starting in the background is its own opt-in, off by default (owner's answer 18 on #383): such a ride has no GPS route. */
    private static void backgroundSwitch() {
        check(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY.equals("auto_record_background"), "the background switch's stored key");
        check(AutoStartPolicy.recordsNow(true, false, true), "on screen: Record rides automatically starts as before, background switch off");
        check(AutoStartPolicy.recordsNow(true, true, true), "on screen with both on: starts");
        check(!AutoStartPolicy.recordsNow(true, false, false), "in the background with only Record rides automatically on (absent is off): nothing");
        check(AutoStartPolicy.recordsNow(true, true, false), "in the background with the background switch on: starts");
        check(!AutoStartPolicy.recordsNow(false, true, false) && !AutoStartPolicy.recordsNow(false, true, true), "the background switch alone never starts a ride");
        check(!AutoStartPolicy.recordsNow(false, false, true), "both off (the default): nothing");
        // Held back in the background, then Volt comes on screen while the scooter still moves: no hold was set, so it starts.
        AutoStartPolicy p = new AutoStartPolicy();
        link(p, true, 1, 0);
        check(!p.shouldRecord(AutoStartPolicy.recordsNow(true, false, false), true, false, false, false, false, true), "held back in the background");
        check(!p.recordHeld() && p.shouldRecord(AutoStartPolicy.recordsNow(true, false, true), true, false, false, false, false, true),
                "held back is not a hold: on screen it starts");
    }

    /** The switch is drawn off while Record rides automatically is off; its stored value is not touched. */
    private static void backgroundSwitchDrawn() {
        check(!AutoStartPolicy.backgroundSwitchDrawnOn(false, true), "parent off, stored on: drawn off");
        check(AutoStartPolicy.backgroundSwitchDrawnOn(true, true) && !AutoStartPolicy.backgroundSwitchDrawnOn(true, false) && !AutoStartPolicy.backgroundSwitchDrawnOn(false, false), "otherwise drawn as stored");
    }

    private static void locationPrompt() {
        check(AutoStartPolicy.asksLocationFirst(true, false, false), "Phone GPS on (the default): turning it on offers the prompt");
        check(!AutoStartPolicy.asksLocationFirst(false, false, false), "GPS recording off: nothing to offer");
        check(!AutoStartPolicy.asksLocationFirst(true, true, false), "simulation: the request would only refuse with a toast");
        check(!AutoStartPolicy.asksLocationFirst(true, false, true), "riding: no prompt from the tap, the on-screen offer asks later");
        check(AutoStartPolicy.asksLocationOnGpsOn(true, false, false), "GPS turned on with auto-record on: offers the prompt");
        check(!AutoStartPolicy.asksLocationOnGpsOn(false, false, false), "auto-record off: the GPS switch only saves, a manual start asks");
        check(!AutoStartPolicy.asksLocationOnGpsOn(true, true, false), "GPS switch in simulation: no prompt");
        check(!AutoStartPolicy.asksLocationOnGpsOn(true, false, true), "GPS switch during a ride: no prompt");
        check(offers(true, true, false, false, false, false, true), "restored on, no permission, stopped, on screen: offered");
        check(!offers(false, true, false, false, false, false, true), "auto-record off: no offer");
        check(!offers(true, false, false, false, false, false, true), "GPS route off: no offer");
        check(!offers(true, true, true, false, false, false, true), "permission already held: no offer");
        check(!offers(true, true, false, true, false, false, true), "already declined: not offered again");
        check(!offers(true, true, false, false, true, false, true), "simulation: no offer");
        check(!offers(true, true, false, false, false, true, true), "moving or recording: no offer");
        check(!offers(true, true, false, false, false, false, false), "a dialog, setup or typing on screen: no offer");
        permissionHistory();
    }

    /** The decline note through the permission's whole life, driven the way the service and activity drive it. */
    private static void permissionHistory() {
        Offer o = new Offer();
        check(o.refresh() && o.requests == 1, "Permission missing, nothing declined: one request");
        check(!o.refresh() && o.requests == 1, "While the dialog is up the refresh cannot ask again");
        o.answer(true);
        check(!o.note && !o.refresh(), "A grant leaves the note unset, and nothing to offer while it holds");

        o.hasLocation = false; // A one-time grant ended, or the rider revoked it in Android settings.
        check(o.refresh() && o.requests == 2 && !o.note, "After a grant ends: offered once more");
        check(!o.refresh() && o.requests == 2, "Still one dialog");
        o.answer(true);
        o.hasLocation = false;
        check(o.refresh() && o.requests == 3, "Another grant that ends: offered again");
        o.answer(false);
        check(o.note && !o.refresh() && o.requests == 3, "A decline ends it");
        for (int i = 0; i < 5; i++) check(!o.refresh() && o.requests == 3, "and the 1 Hz refresh never asks again");

        o.hasLocation = true; // The rider allowed it in Android settings.
        check(!o.refresh() && o.requests == 3 && !o.note, "Declined, then granted in Android settings: no offer, the decline is over");
        o.hasLocation = false;
        check(o.refresh() && o.requests == 4, "That grant ending: offered once more");
        o.answer(false);
        check(o.note && !o.refresh(), "Decline again: ended");

        Offer dontAsk = new Offer();
        check(dontAsk.refresh(), "Don't ask again: the request still goes out");
        dontAsk.answer(false); // Android returns denied at once, with no dialog.
        check(dontAsk.note && dontAsk.requests == 1, "It counts as a decline");
        for (int i = 0; i < 5; i++) check(!dontAsk.refresh() && dontAsk.requests == 1, "One request only, no loop on the refresh");

        Offer cancelled = new Offer();
        check(cancelled.refresh(), "Requested");
        cancelled.cancelled();
        check(!cancelled.note, "A cancelled request records no answer");
        for (int i = 0; i < 5; i++) check(!cancelled.refresh() && cancelled.requests == 1, "and that activity does not retry it on the refresh");
        cancelled.recreated();
        check(cancelled.refresh() && cancelled.requests == 2, "A new activity may ask once");
        cancelled.answer(false);
        check(cancelled.note && !cancelled.refresh(), "and its decline counts");

        Offer recreated = new Offer();
        check(recreated.refresh(), "Requested");
        recreated.recreated(); // The old activity's pending request is dropped, and with it any answer to it.
        check(!recreated.note && recreated.refresh() && recreated.requests == 2, "Recreated during the dialog: nothing recorded, may ask once more");
        recreated.answer(true);
        check(!recreated.note && !recreated.refresh(), "and the grant is not noted");
        check(AutoStartPolicy.declineNote(true, true, true) == false, "A permission held at the answer is never a decline, even with an old note");
    }

    /** The service's note and the activity's request guards, around the real policy. */
    private static final class Offer {
        boolean note, hasLocation, pending, cancelledHere;
        int requests;

        boolean refresh() {
            note = AutoStartPolicy.declineNote(note, false, hasLocation);
            if (pending || cancelledHere || !offers(true, true, hasLocation, note, false, false, true)) return false;
            pending = true;
            requests++;
            return true;
        }

        void answer(boolean grant) {
            pending = false;
            hasLocation = grant;
            note = AutoStartPolicy.declineNote(note, true, hasLocation);
        }

        void cancelled() {
            pending = false;
            cancelledHere = true;
            note = AutoStartPolicy.declineNote(note, false, hasLocation);
        }

        void recreated() {
            pending = false;
            cancelledHere = false;
        }
    }

    private static boolean offers(boolean autoRecord, boolean phoneGps, boolean hasLocation, boolean asked, boolean simulating, boolean riding, boolean clear) {
        return AutoStartPolicy.offersLocationOnScreen(autoRecord, phoneGps, hasLocation, asked, simulating, riding, clear);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
