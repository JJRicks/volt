package com.jjricks.volt;

import static com.jjricks.volt.ChargeTimeReadings.*;

/** The charging view's combined time tile: which readings exist, which shows, the saved choice and every switch path. */
public final class ChargeTimeReadingsTest {
    private static int checks;

    public static void main(String[] args) {
        readings();
        choice();
        taps();
        drags();
        interruptions();
        visuals();
        touches();
        System.out.println("ChargeTimeReadingsTest: " + checks + " checks");
    }

    private static void readings() {
        check(limitSource(null, false) == NO_LIMIT && !two(limitSource(null, false)), "no limit and no alert: one reading");
        check(limitSource(100d, false) == NO_LIMIT, "a limit at 100% is no limit");
        check(limitSource(80d, false) == SCOOTER_LIMIT && two(SCOOTER_LIMIT), "a limit below 100%: two readings, the limit first");
        check(limitSource(null, true) == ALERT_LEVEL && two(ALERT_LEVEL), "an alert ahead: two readings, the alert first");
        check(limitSource(100d, true) == ALERT_LEVEL, "a limit at 100% with an alert: the alert");
        check(limitSource(80d, true) == SCOOTER_LIMIT, "a limit below 100% and an alert: the scooter's own limit wins");
        check(limitSource(0d, false) == NO_LIMIT && limitSource(-5d, false) == NO_LIMIT && limitSource(Double.NaN, false) == NO_LIMIT,
                "a zero, negative or unknown limit is no limit");
        check(limitSource(150d, true) == ALERT_LEVEL, "an out-of-range limit never counts");
        check(limitSource(80d, true, true) == ALERT_LEVEL && limitSource(80d, true, false) == NO_LIMIT,
                "a model that offers charge alerts has no native limit: its limit-like reading never replaces the alert");
        check(limitSource(80d, false, true) == SCOOTER_LIMIT && limitSource(80d, false, false) == SCOOTER_LIMIT,
                "a model without charge alerts: its limit below 100% is the scooter's own");
        // The one reading's name follows its value (review of #445): a rejected limit-like target names 100%.
        check(oneReadingTarget(80d, limitSource(80d, true, false)) == 100d, "a limit the guard rejects names the one reading 100%, not 80%");
        check(oneReadingTarget(100d, NO_LIMIT) == 100d && oneReadingTarget(null, NO_LIMIT) == null,
                "a 100% cutoff stays 100%, an unknown one stays unknown");
        check(oneReadingTarget(80d, SCOOTER_LIMIT) == 80d && oneReadingTarget(80d, ALERT_LEVEL) == 80d,
                "two readings keep the estimate's target for the limit face");
    }

    private static void choice() {
        check(preferred(null) == LIMIT, "nothing saved (new scooter, or saved by 1.13.x): the limit reading");
        check(preferred("full") == FULL && preferred("limit") == LIMIT, "saved choices read back");
        check(preferred("") == LIMIT && preferred("FULL") == LIMIT && preferred("2") == LIMIT && preferred(" full") == LIMIT,
                "anything else is the default, never a crash");
        check(preferred(encode(FULL)) == FULL && preferred(encode(LIMIT)) == LIMIT, "encode round-trips");
        check(shown(true, LIMIT) == LIMIT && shown(true, FULL) == FULL, "two readings: the choice shows");
        check(shown(false, LIMIT) == FULL && shown(false, FULL) == FULL, "one reading: the time to 100%, whatever was chosen");
        check(other(LIMIT) == FULL && other(FULL) == LIMIT, "other");
        check("charge_time_reading_p1".equals(key("p1")), "per scooter key");
        check(key("unconfigured") == null && key("") == null && key(null) == null, "no scooter: nothing saved");
        check(!key("a").equals(key("b")), "two scooters never share a key");
    }

    private static void taps() {
        Switch s = new Switch();
        s.rest(LIMIT);
        check(s.atRest() && s.goal() == 0, "starts at rest");
        check(s.tap(false) == FULL && s.goal() == 1 && s.direction == -1, "a tap heads to 100%, the limit leaving to the left");
        run(s);
        check(s.atRest() && s.resting == FULL, "the animation ends on 100%");
        check(s.tap(false) == LIMIT && s.direction == 1, "back to the limit, leaving to the right (a page back)");
        run(s);
        check(s.atRest() && s.resting == LIMIT, "back on the limit");
        s.tap(true);
        check(s.direction == 1, "right to left: the first reading leaves to the right");
        s.settle();
        s.tap(true);
        check(s.direction == -1, "right to left: back again leaves to the left");
        s.settle();
    }

    private static void drags() {
        Switch s = new Switch();
        s.rest(LIMIT);
        s.startDrag();
        s.drag(-.2f);
        check(s.progress == .2f && s.direction == -1 && !s.atRest(), "the outgoing reading follows the finger");
        check(s.release(0) == LIMIT && s.goal() == 0, "a short drag returns");
        run(s);
        check(s.atRest() && s.resting == LIMIT, "an abandoned drag ends on the reading it started from");
        s.startDrag(); s.drag(.6f);
        check(s.release(0) == FULL && s.direction == 1, "past the threshold it completes, the way it was dragged");
        run(s);
        check(s.atRest() && s.resting == FULL, "dragged to 100%");
        s.startDrag(); s.drag(-.15f);
        check(s.release(-1) == LIMIT, "a short fling the way it goes completes");
        run(s);
        check(s.resting == LIMIT, "flung back to the limit");
        s.startDrag(); s.drag(.7f);
        check(s.release(-1) == LIMIT && s.goal() == 0, "flung against the drag returns, even past the threshold");
        run(s);
        check(s.resting == LIMIT && s.atRest(), "flung back");
        s.startDrag(); s.drag(.3f); s.drag(-.1f);
        check(s.direction == -1 && Math.abs(s.progress - .1f) < 1e-6, "crossing back over the start turns the direction");
        s.drag(0);
        check(s.release(0) == LIMIT && s.atRest(), "lifting at the start point stays put");
        s.startDrag(); s.drag(-2f);
        check(s.progress == 1, "a drag past the travel distance stops at fully switched");
        s.cancelDrag();
        check(s.target == LIMIT && !s.dragging, "a scroll taking the gesture goes back");
        run(s);
        check(s.atRest() && s.resting == LIMIT, "and ends at rest");
        s.startDrag(); s.drag(Float.NaN);
        check(s.progress == 0, "a NaN share is no movement");
        s.cancelDrag(); s.settle();
    }

    private static void interruptions() {
        Switch s = new Switch();
        s.rest(LIMIT);
        s.tap(false); s.step(.3f);
        check(s.tap(false) == LIMIT && s.goal() == 0, "a second tap mid-switch turns back");
        s.step(.1f);
        check(s.tap(false) == FULL && s.goal() == 1 && s.direction == -1, "a third turns forward again, the same way");
        run(s);
        check(s.atRest() && s.resting == FULL, "rapid taps end at rest on the last choice");
        // Ten fast taps, a frame between each: the last choice wins, at rest.
        for (int i = 0; i < 10; i++) { s.tap(false); s.step(s.progress + (s.goal() == 1 ? .07f : -.07f)); }
        int chosen = s.target;
        run(s);
        check(s.atRest() && s.resting == chosen, "ten fast taps settle on the last choice");
        s.tap(false); s.step(.5f);
        s.settle();
        check(s.atRest() && s.resting == s.target, "hidden or detached mid-switch: settles on the choice");
        int before = s.resting;
        s.tap(false); s.step(.5f);
        s.startDrag();
        check(s.resting == other(before) && s.progress == 0 && s.dragging, "a drag mid-switch first finishes it, then starts at rest");
        s.release(0);
        check(s.atRest(), "and a lift without movement is still at rest");
        s.dragging = true; s.step(.9f);
        check(s.progress == 0, "animation frames never move a held drag");
        s.dragging = false;
        s.rest(FULL);
        check(s.tap(false) == LIMIT, "a restored 100% choice taps back to the limit");
        s.settle();
        check(s.release(1) == s.target && s.atRest(), "a release with no drag changes nothing");
    }

    private static void visuals() {
        check(outgoingAlpha(0) == 1 && outgoingAlpha(FADE_OUT_BY) == 0 && outgoingAlpha(1) == 0, "the outgoing reading clears first");
        check(incomingAlpha(0) == 0 && incomingAlpha(FADE_IN_FROM) == 0 && incomingAlpha(1) == 1, "the incoming reading fades in after");
        float overlap = 0;
        for (int i = 0; i <= 100; i++) { float p = i / 100f; overlap = Math.max(overlap, Math.min(outgoingAlpha(p), incomingAlpha(p))); }
        check(overlap < .1f, "the two readings never both show at more than 10% opacity: " + overlap);
        check(outgoingShift(0, -1) == 0 && outgoingShift(1, -1) == -1 && incomingShift(0, -1) == 1 && incomingShift(1, -1) == 0,
                "outgoing leaves one way, incoming arrives from the other");
        check(outgoingShift(2, 1) == 1 && incomingShift(-1, 1) == -1, "shifts clamp");
        Switch s = new Switch(); s.rest(LIMIT);
        check(dotFill(s, LIMIT) == 1 && dotFill(s, FULL) == 0, "the shown reading's dot is filled");
        s.tap(false); s.step(.5f);
        check(dotFill(s, LIMIT) == .5f && dotFill(s, FULL) == .5f, "the dots cross over with the switch");
        run(s);
        check(dotFill(s, FULL) == 1 && dotFill(s, LIMIT) == 0, "at rest on 100%, its dot is filled");
    }

    /** A touch cut off by a reset (or by the tile's switchability changing under it) does nothing until the next one. */
    private static void touches() {
        Touch t = new Touch();
        check(!t.own() && !t.dead() && !t.cut(), "no finger down: a reset cuts nothing");
        t.begin(true);
        check(t.own() && !t.changed(true), "a touch that begins switchable is the tile's");
        check(t.cut() && t.dead() && !t.own(), "a reset with the finger down (reveal end, hide, a reading going) cuts it");
        check(!t.cut() && t.dead(), "a second reset leaves it cut, and drops the press only once");
        check(!t.changed(true) && !t.changed(false), "a cut touch stays cut whatever the tile becomes");
        t.end();
        check(!t.dead() && !t.own(), "the lift ends it");
        t.begin(true);
        check(t.own(), "the next touch is the tile's again");
        t.end();
        t.begin(false);
        check(!t.own() && !t.dead() && !t.changed(false), "a touch that begins with one reading is the plain tile's");
        check(t.changed(true), "the second reading arriving mid-touch changes it");
        check(t.cut() && t.dead(), "and cuts it");
        t.end(); t.begin(true);
        check(t.changed(false), "a touch that began switchable is changed by losing the second reading, editing or closing");
        t.cut(); t.end(); t.begin(true);
        check(t.own(), "after a cut touch, an ordinary touch works");
        t.begin(false);
        check(!t.own() && !t.dead(), "a new first contact starts fresh even without a lift (a missed cancel)");
        t.end();
    }

    /** Runs the switch's animation to its goal in frames, as the tile's animator does. */
    private static void run(Switch s) {
        for (int i = 0; i < 50 && !s.atRest(); i++) s.step(s.progress + (s.goal() == 1 ? .1f : -.1f));
        check(s.atRest(), "the animation reaches rest");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
