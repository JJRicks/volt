package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * ChargeBarDrag: the rules of the charging bar's draggable mark (Home's Widgets charging view). Geometry is in pixels
 * on a 300 px track from 20 to 320 with an 8 px touch slop; the mark's band is 30 px above the bar and 18 px below,
 * its grab target 28 px either side. The optional argument is ChargeBar.java, checked for storing nothing of its own.
 */
public final class ChargeBarDragTest {
    private static int checks;
    private static final float START = 20, END = 320, BAR = 100, ABOVE = 30, BELOW = 18, HALF = 28, SLOP = 8;
    private static final int[] LIMITS = {80, 85, 90, 95, 100};

    public static void main(String[] args) throws Exception {
        whatTheMarkIs();
        onlyOfferedValues();
        geometryAndRtl();
        steps();
        touchStartsOnlyNearTheMark();
        commitsOnceOnRelease();
        cancelsCommitNothing();
        verticalSwipeIsThePages();
        notLiveSaysWhy();
        limitGates();
        writesAndReadback();
        if (args.length > 0) storesNothing(args[0]);
        System.out.println("ChargeBarDrag: " + checks + " checks passed");
    }

    /** Rule 1: the limit on native-limit models, else the alert level when the alert is on, else nothing. */
    private static void whatTheMarkIs() {
        require(ChargeBarDrag.kind(true, 85.0, false, false) == ChargeBarDrag.LIMIT, "a native limit is the mark");
        require(ChargeBarDrag.kind(true, 100.0, false, false) == ChargeBarDrag.LIMIT, "a 100% limit is still a mark (it can be dragged down)");
        require(ChargeBarDrag.kind(true, null, true, true) == ChargeBarDrag.NONE, "a native-limit model with no limit reported has no mark, never an alert's");
        require(ChargeBarDrag.kind(true, 85.0, true, true) == ChargeBarDrag.LIMIT, "the limit wins over a saved alert");
        require(ChargeBarDrag.kind(true, Double.NaN, false, false) == ChargeBarDrag.NONE, "an unreadable limit is no mark");
        require(ChargeBarDrag.kind(true, 0.0, false, false) == ChargeBarDrag.NONE, "a 0% limit is no mark");
        require(ChargeBarDrag.kind(true, 120.0, false, false) == ChargeBarDrag.NONE, "a limit above 100% is no mark");
        require(ChargeBarDrag.kind(false, null, true, true) == ChargeBarDrag.ALERT, "charge alerts offered and on: the alert level");
        require(ChargeBarDrag.kind(false, 85.0, true, true) == ChargeBarDrag.ALERT, "no limit control: the alert, whatever a stale target says");
        require(ChargeBarDrag.kind(false, null, true, false) == ChargeBarDrag.NONE, "an alert switched off is no mark");
        require(ChargeBarDrag.kind(false, null, false, true) == ChargeBarDrag.NONE, "alerts not offered here: no mark even when saved on");
        require(ChargeBarDrag.kind(false, 85.0, false, false) == ChargeBarDrag.NONE, "no control and no alert: no mark");
    }

    /** Rule 2: a drag only lands on values the control offers. */
    private static void onlyOfferedValues() {
        require(Arrays.equals(ChargeBarDrag.limitValues(new int[] {100, 80, 85, 85, 0, 101, 90, 95}), LIMITS), "the limit's choices, cleaned and sorted");
        require(ChargeBarDrag.limitValues(null).length == 0 && ChargeBarDrag.limitValues(new int[] {0, 150}).length == 0, "no usable choices: none");
        int[] alerts = ChargeBarDrag.steps(ChargeAlertPolicy.CHARGE_MIN, ChargeAlertPolicy.CHARGE_MAX, ChargeAlertPolicy.STEP);
        require(Arrays.equals(alerts, new int[] {50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100}), "the alert sheet's 50 to 100% by 5: " + Arrays.toString(alerts));
        require(ChargeBarDrag.steps(10, 5, 5).length == 0 && ChargeBarDrag.steps(10, 20, 0).length == 0, "no steps from a bad range");
        for (double p = 0; p <= 100; p += .5) {
            require(ChargeBarDrag.offered(ChargeBarDrag.snap(p, LIMITS), LIMITS), "snap stays on the limit's choices at " + p);
            require(ChargeBarDrag.offered(ChargeBarDrag.snap(p, alerts), alerts), "snap stays on the alert's steps at " + p);
        }
        require(ChargeBarDrag.snap(10, LIMITS) == 80 && ChargeBarDrag.snap(83, LIMITS) == 85 && ChargeBarDrag.snap(82.5, LIMITS) == 80,
                "nearest choice, halfway goes lower, below the lowest is the lowest");
        // A drag across the whole track only ever reports allowed values.
        ChargeBarDrag.Gesture g = pressed(85, true);
        for (float x = -100; x < 500; x += 3) {
            g.move(x, BAR, SLOP, START, END, false, LIMITS);
            require(!g.dragging() || ChargeBarDrag.offered(g.value, LIMITS), "a drag holds an offered value at x " + x);
        }
        Integer up = g.up();
        require(up == null || ChargeBarDrag.offered(up, LIMITS), "the lift commits an offered value");
    }

    /** RTL mirrors the bar and the gesture. */
    private static void geometryAndRtl() {
        require(ChargeBarDrag.x(0, START, END, false) == START && ChargeBarDrag.x(100, START, END, false) == END, "left to right: 0% at the start");
        require(ChargeBarDrag.x(0, START, END, true) == END && ChargeBarDrag.x(100, START, END, true) == START, "right to left: 0% at the end");
        require(ChargeBarDrag.x(50, START, END, true) == 170, "the middle is the middle either way");
        for (int p = 0; p <= 100; p += 5)
            for (boolean rtl : new boolean[] {false, true})
                require(Math.abs(ChargeBarDrag.percentAt(ChargeBarDrag.x(p, START, END, rtl), START, END, rtl) - p) < 1e-3, "x and percent agree at " + p + (rtl ? " rtl" : ""));
        require(ChargeBarDrag.percentAt(-50, START, END, false) == 0 && ChargeBarDrag.percentAt(900, START, END, false) == 100, "off the track clamps");
        require(ChargeBarDrag.percentAt(100, 50, 50, false) == 0, "a zero-width track gives 0, never NaN");
        require(ChargeBarDrag.x(Double.NaN, START, END, false) == START && ChargeBarDrag.x(250, START, END, false) == END, "NaN and overflow stay on the track");
        // Right to left, dragging toward the left raises the value.
        ChargeBarDrag.Gesture g = new ChargeBarDrag.Gesture();
        float mark = ChargeBarDrag.x(85, START, END, true);
        require(g.down(mark, BAR, mark, BAR, ABOVE, BELOW, HALF, true, 85), "rtl down on the mark");
        require(g.move(mark - 16, BAR, SLOP, START, END, true, LIMITS) == ChargeBarDrag.Gesture.STARTED && g.value == 90, "rtl: leftward is up, value " + g.value);
        require(g.up() == 90, "rtl commits the raised value");
        g.down(ChargeBarDrag.x(85, START, END, false), BAR, ChargeBarDrag.x(85, START, END, false), BAR, ABOVE, BELOW, HALF, true, 85);
        g.move(ChargeBarDrag.x(85, START, END, false) - 16, BAR, SLOP, START, END, false, LIMITS);
        require(g.up() == 80, "ltr: leftward is down");
    }

    /** TalkBack's increase and decrease move one allowed step. */
    private static void steps() {
        require(ChargeBarDrag.step(85, 1, LIMITS) == 90 && ChargeBarDrag.step(85, -1, LIMITS) == 80, "one step either way");
        require(ChargeBarDrag.step(100, 1, LIMITS) == 100 && ChargeBarDrag.step(80, -1, LIMITS) == 80, "the ends stay put");
        require(ChargeBarDrag.step(87, 1, LIMITS) == 90 && ChargeBarDrag.step(87, -1, LIMITS) == 85, "between steps goes to the neighbour on that side");
        require(ChargeBarDrag.step(85, 1, ChargeBarDrag.NO_VALUES) == 85, "no values: no step");
    }

    /** Rule 6: only a touch in the band and near the mark is taken; nothing jumps to a touch elsewhere. */
    private static void touchStartsOnlyNearTheMark() {
        float mark = ChargeBarDrag.x(85, START, END, false);
        ChargeBarDrag.Gesture g = new ChargeBarDrag.Gesture();
        require(!g.down(mark, BAR - ABOVE - 1, mark, BAR, ABOVE, BELOW, HALF, true, 85), "above the band: not taken");
        require(!g.down(mark, BAR + BELOW + 1, mark, BAR, ABOVE, BELOW, HALF, true, 85), "below the band: not taken");
        require(!g.down(mark - HALF - 1, BAR, mark, BAR, ABOVE, BELOW, HALF, true, 85), "beside the grab target: not taken");
        require(!g.active(), "a touch not taken leaves no gesture");
        require(g.down(mark + HALF, BAR - ABOVE, mark, BAR, ABOVE, BELOW, HALF, true, 85), "the band's corner is taken");
        require(g.value == 85, "a down never moves the mark");
        require(g.move(mark + HALF, BAR - ABOVE, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.NOTHING && g.value == 85,
                "a press without movement doesn't jump to the finger");
        require(g.move(mark + HALF + SLOP, BAR - ABOVE, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.NOTHING, "exactly the slop is not yet a drag");
        require(g.move(mark + HALF + SLOP + 1, BAR - ABOVE, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.STARTED,
                "past the slop sideways it is a drag");
        // The mark keeps the finger's offset: 9 px of travel (3%) from 85 lands on 90; jumping to the finger, 28 + 9 px
        // right of the mark (97%), would land on 95.
        require(g.value == 90, "the mark moves by the finger's travel, not to the finger: " + g.value);
    }

    /** Rule 3: commit on release, once, and only if the value changed. */
    private static void commitsOnceOnRelease() {
        ChargeBarDrag.Gesture g = pressed(85, true);
        float mark = ChargeBarDrag.x(85, START, END, false);
        require(g.move(mark + 10, BAR, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.STARTED, "starts");
        int stepped = 0;
        for (float x = mark + 10; x <= mark + 31; x += 1) if (g.move(x, BAR + 3, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.STEPPED) stepped++;
        require(stepped == 1 && g.value == 95, "one step from 85 + 10 px to 85 + 31 px: " + stepped + " to " + g.value);
        require(g.move(mark + 31, BAR + 3, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.MOVED, "the same value again is no step (no extra tick)");
        Integer value = g.up();
        require(value != null && value == 95, "the lift commits the dragged value");
        require(g.up() == null && !g.active(), "a second lift commits nothing");
        g = pressed(85, true);
        g.move(mark + 20, BAR, SLOP, START, END, false, LIMITS);
        g.move(mark + 2, BAR, SLOP, START, END, false, LIMITS);
        require(g.dragging() && g.value == 85 && g.up() == null, "dragged back to where it began: nothing to commit");
        g = pressed(85, true);
        require(g.up() == null, "a tap commits nothing");
    }

    /** Rule 3: ACTION_CANCEL, a second finger, detach, rotation, pause, charging ending, disconnect: nothing commits. */
    private static void cancelsCommitNothing() {
        float mark = ChargeBarDrag.x(85, START, END, false);
        ChargeBarDrag.Gesture g = pressed(85, true);
        g.move(mark + 40, BAR, SLOP, START, END, false, LIMITS);
        require(g.dragging() && g.value != 85, "dragging");
        require(g.cancel(true), "a cancel reports the drag it ended (the mark goes back)");
        require(g.move(mark + 60, BAR, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.NOTHING, "the rest of the touch is ignored");
        require(g.up() == null, "the lift after a cancel commits nothing");
        g = pressed(85, true);
        g.move(mark + 40, BAR, SLOP, START, END, false, LIMITS);
        require(g.cancel(false) && !g.active() && g.up() == null, "a cancel with no finger down (detached, paused) commits nothing");
        g = pressed(85, true);
        require(!g.cancel(true), "a cancel before a drag reports none");
    }

    /** Rule 6: a vertical swipe starting on the bar is the page's. */
    private static void verticalSwipeIsThePages() {
        float mark = ChargeBarDrag.x(85, START, END, false);
        ChargeBarDrag.Gesture g = pressed(85, true);
        require(g.move(mark + 5, BAR + 9, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.LET_GO, "down past the slop first: let go");
        require(g.move(mark + 60, BAR + 9, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.NOTHING && !g.dragging(), "never a drag after");
        require(g.up() == null, "nothing commits");
        g = pressed(85, true);
        require(g.move(mark + 10, BAR - 12, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.LET_GO, "a diagonal more vertical than sideways is the page's");
        g = pressed(85, true);
        require(g.move(mark + 12, BAR - 10, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.STARTED, "more sideways than vertical is a drag");
    }

    /** Rules 4 and 5: a mark that can't move (pending, disconnected, gated) takes the touch only to say why, once. */
    private static void notLiveSaysWhy() {
        float mark = ChargeBarDrag.x(85, START, END, false);
        ChargeBarDrag.Gesture g = pressed(85, false);
        require(g.move(mark + 4, BAR, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.NOTHING, "within the slop, nothing yet");
        require(g.move(mark + 20, BAR, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.REFUSED, "a deliberate drag is refused");
        require(g.value == 85 && !g.dragging(), "the mark doesn't move");
        require(g.move(mark + 60, BAR, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.NOTHING, "refused once, not per move");
        require(g.up() == null, "nothing commits");
        g = pressed(85, false);
        require(g.move(mark, BAR + 20, SLOP, START, END, false, LIMITS) == ChargeBarDrag.Gesture.LET_GO, "a vertical swipe on a blocked mark still scrolls");
        g = pressed(85, true);
        require(g.move(mark + 20, BAR, SLOP, START, END, false, ChargeBarDrag.NO_VALUES) == ChargeBarDrag.Gesture.REFUSED, "no values: refused, never a crash");
    }

    /** Rule 2: the Charge limit control's gates, in Quick access's order, then its sheet's. */
    private static void limitGates() {
        require(ChargeBarDrag.limitGate(open(), null) == ChargeBarDrag.Gate.OK, "all open: a drag may start");
        require(ChargeBarDrag.limitGate(open(), 90) == ChargeBarDrag.Gate.OK, "an offered new value may be written");
        require(ChargeBarDrag.limitGate(open(), 85) == ChargeBarDrag.Gate.SAME, "the current value is not written again");
        require(ChargeBarDrag.limitGate(open(), 87) == ChargeBarDrag.Gate.NOT_OFFERED, "a value the control doesn't offer is never written");
        require(ChargeBarDrag.limitGate(open(), 50) == ChargeBarDrag.Gate.NOT_OFFERED, "an alert-style value is not a limit choice");
        // Each gate closes on its own and, closed together, the earlier one speaks (Quick access's order).
        ChargeBarDrag.Gate[] order = {ChargeBarDrag.Gate.NO_SERVICE, ChargeBarDrag.Gate.UNMAPPED, ChargeBarDrag.Gate.LAB, ChargeBarDrag.Gate.RULE,
                ChargeBarDrag.Gate.NOT_CONNECTED, ChargeBarDrag.Gate.BUSY, ChargeBarDrag.Gate.NOT_FRESH, ChargeBarDrag.Gate.GATED, ChargeBarDrag.Gate.NO_CHOICES};
        for (int i = 0; i < order.length; i++) {
            ChargeBarDrag.LimitState s = open();
            close(s, order[i]);
            require(ChargeBarDrag.limitGate(s, 90) == order[i], order[i] + " alone closes the gate");
            for (int j = i + 1; j < order.length; j++) close(s, order[j]);
            require(ChargeBarDrag.limitGate(s, 90) == order[i], order[i] + " speaks before the later gates");
        }
        ChargeBarDrag.LimitState riding = open();
        riding.available = false; riding.parkedOnly = true;
        require(ChargeBarDrag.limitGate(riding, 90) == ChargeBarDrag.Gate.PARKED, "riding says stop to change, as the row does");
        ChargeBarDrag.LimitState noValue = open();
        noValue.actual = null;
        require(ChargeBarDrag.limitGate(noValue, 90) == ChargeBarDrag.Gate.NOT_FRESH, "no reported value is not fresh");
    }

    /** Rule 4: pending until the scooter answers, then its readback decides, as the choice sheet does. */
    private static void writesAndReadback() {
        ChargeBarDrag.Commit c = new ChargeBarDrag.Commit();
        c.report(85);
        require(!c.pending() && c.shown() == 85, "at rest the mark is the report");
        c.ask(90);
        require(c.pending() && c.shown() == 90, "asked: the mark stays where it was released, pending");
        c.report(85);
        require(c.shown() == 90, "an old report while asking doesn't pull the mark back");
        require(c.resolve(false, 85, null) == ChargeBarDrag.Commit.NO_CHANGE && c.pending(), "nothing resolves before the write is accepted");
        c.refused();
        require(!c.pending() && c.shown() == 85, "refused or cancelled at the caution: back to the report");
        c.ask(90); c.accepted();
        require(c.phase == ChargeBarDrag.Commit.IN_FLIGHT && c.pending(), "accepted: in flight");
        require(c.resolve(true, 85, null) == ChargeBarDrag.Commit.WAITING && c.shown() == 90, "while in flight it waits");
        require(c.resolve(false, 90, "Setting confirmed") == ChargeBarDrag.Commit.KEPT && !c.pending() && c.shown() == 90, "kept");
        c.ask(80); c.accepted();
        require(c.resolve(false, 90, "Rejected by scooter") == ChargeBarDrag.Commit.REJECTED && c.shown() == 90, "rejected: back to what the scooter says");
        c.ask(95); c.accepted();
        require(c.resolve(false, 90, "Timed out") == ChargeBarDrag.Commit.NOT_CONFIRMED && c.shown() == 90, "timed out: not confirmed, back");
        c.ask(95); c.accepted();
        require(c.resolve(false, null, null) == ChargeBarDrag.Commit.NOT_CONFIRMED && c.shown() == 90, "no readback: not confirmed, the last report stays");
        c.ask(80); c.accepted();
        require(c.resolve(false, 85, "Setting confirmed") == ChargeBarDrag.Commit.NOT_CONFIRMED && c.shown() == 85, "a different readback is shown, not confirmed");
        c.accepted();
        require(c.phase == ChargeBarDrag.Commit.REST, "accepting with nothing asked does nothing");
    }

    /** Rule 9: the bar keeps no store of its own; the alert level goes through ScooterService's alert settings. */
    private static void storesNothing(String path) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        for (String banned : new String[] {"SharedPreferences", "getSharedPreferences", ".edit()", "putInt(", "putString(", "putBoolean(", "openFileOutput"})
            require(!source.contains(banned), "ChargeBar stores nothing of its own (" + banned + ")");
        require(source.contains("setChargeAlerts("), "the alert level is saved through the alert store");
        require(source.contains("host.chargeLimit("), "the limit is written only through the host (the Charge limit control)");
        require(!source.contains("ble.set("), "ChargeBar never writes the scooter itself");
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private static ChargeBarDrag.Gesture pressed(int value, boolean live) {
        ChargeBarDrag.Gesture g = new ChargeBarDrag.Gesture();
        float mark = ChargeBarDrag.x(value, START, END, false);
        require(g.down(mark, BAR, mark, BAR, ABOVE, BELOW, HALF, live, value), "down on the mark is taken");
        return g;
    }

    private static ChargeBarDrag.LimitState open() {
        ChargeBarDrag.LimitState s = new ChargeBarDrag.LimitState();
        s.service = s.knownEncoding = s.ready = s.fresh = s.available = true;
        s.actual = 85;
        s.choices = LIMITS.clone();
        return s;
    }

    private static void close(ChargeBarDrag.LimitState s, ChargeBarDrag.Gate gate) {
        switch (gate) {
            case NO_SERVICE: s.service = false; break;
            case UNMAPPED: s.knownEncoding = false; break;
            case LAB: s.lab = true; break;
            case RULE: s.rule = true; break;
            case NOT_CONNECTED: s.ready = false; break;
            case BUSY: s.busy = true; break;
            case NOT_FRESH: s.fresh = false; break;
            case GATED: s.available = false; break;
            case NO_CHOICES: s.choices = ChargeBarDrag.NO_VALUES; break;
            default: throw new IllegalArgumentException(gate.toString());
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
