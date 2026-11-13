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
        backgroundPrerequisite();
        backgroundCadence();
        backgroundSwitch();
        backgroundSwitchDrawn();
        backgroundNotice();
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
        p.observeLink(true, 1);
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
        p.observeLink(true, 1);
        check(!p.shouldRecord(AutoStartPolicy.recordsNow(true, false, false), true, false, false, false, false, true), "held back in the background");
        check(!p.recordHeld() && p.shouldRecord(AutoStartPolicy.recordsNow(true, false, true), true, false, false, false, false, true),
                "held back is not a hold: on screen it starts");
    }

    /** The switch is drawn off while Record rides automatically is off; its stored value is not touched. */
    private static void backgroundSwitchDrawn() {
        check(!AutoStartPolicy.backgroundSwitchDrawnOn(false, true), "parent off, stored on: drawn off");
        check(AutoStartPolicy.backgroundSwitchDrawnOn(true, true) && !AutoStartPolicy.backgroundSwitchDrawnOn(true, false) && !AutoStartPolicy.backgroundSwitchDrawnOn(false, false), "otherwise drawn as stored");
    }

    /** The one-time What's new note: due only for settings an older Volt left with Record rides automatically on. */
    private static void backgroundNotice() {
        check(AutoStartPolicy.BACKGROUND_NOTICE_KEY.equals("auto_record_background_notice"), "the note's stored key");
        String due = AutoStartPolicy.NOTICE_DUE, done = AutoStartPolicy.NOTICE_DONE;
        check(due.equals(AutoStartPolicy.backgroundNoticeSeed(null, false, true, true, false)), "an update with Record rides automatically on: due");
        check(done.equals(AutoStartPolicy.backgroundNoticeSeed(null, false, true, false, false)), "an update with it off: done");
        check(done.equals(AutoStartPolicy.backgroundNoticeSeed(null, false, true, true, true)), "background switch already on: nothing to tell");
        check(done.equals(AutoStartPolicy.backgroundNoticeSeed(null, false, false, true, false)), "never updated: done, even with the switch turned on since");
        check(AutoStartPolicy.backgroundNoticeSeed(null, true, false, false, false) == null && AutoStartPolicy.backgroundNoticeSeed(null, true, true, false, false) == null,
                "empty settings (a first start, cleared data): nothing written");
        check(due.equals(AutoStartPolicy.backgroundNoticeSeed(due, false, false, false, true)) && done.equals(AutoStartPolicy.backgroundNoticeSeed(done, false, true, true, false)),
                "decided once: a later start keeps the stored state");
        check(due.equals(AutoStartPolicy.backgroundNoticeSeed("", false, true, true, false)) && done.equals(AutoStartPolicy.backgroundNoticeSeed("other", false, true, false, false)),
                "an unknown stored value counts as undecided");
        check(due.equals(AutoStartPolicy.backgroundNoticeNow(due, true, false)), "still due while its reason holds");
        check(done.equals(AutoStartPolicy.backgroundNoticeNow(due, false, false)), "Record rides automatically turned off first: done without showing");
        check(done.equals(AutoStartPolicy.backgroundNoticeNow(due, true, true)), "background switch turned on first: done without showing");
        check(done.equals(AutoStartPolicy.backgroundNoticeNow(done, true, false)) && AutoStartPolicy.backgroundNoticeNow(null, true, false) == null,
                "done stays done; undecided stays undecided");
        check(AutoStartPolicy.showsBackgroundNotice(due, true), "due on a clear screen: shows");
        check(!AutoStartPolicy.showsBackgroundNotice(due, false), "never over riding mode, setup, a dialog or a search");
        check(!AutoStartPolicy.showsBackgroundNotice(done, true) && !AutoStartPolicy.showsBackgroundNotice(null, true), "done or undecided: never");
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
