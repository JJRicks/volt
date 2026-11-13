package com.jjricks.volt;

import java.util.Arrays;

/**
 * The charging bar's draggable mark (an active user's request, Nov 2026): what the mark is, where it sits on the bar,
 * which values a drag may choose, how a touch becomes a drag, the charge limit's gates, and what happens to a released
 * value. Pure Java with no Android imports, so the rules are checked by tests/ChargeBarDragTest.java; ChargeBar draws
 * the bar and feeds it touches. Home's Widgets charging view uses it first; Clean's charging view can adopt the same
 * class once its own layout settles.
 *
 * <p>Rules, each tested:
 * <ul>
 *   <li>The mark is the scooter's charge limit where the Charge limit control is offered (a known encoding that is not
 *       a Lab variant) and a fresh limit is reported; else the charge alert's level where Volt's charge alerts are
 *       offered and the charge alert is on; else there is no mark and nothing to drag.</li>
 *   <li>A drag only ever chooses a value the control offers: the limit's own choices (the resolver's values), or the
 *       alert sheet's 50 to 100% in steps of 5. A value outside them is never committed.</li>
 *   <li>A touch is taken only when it starts inside the band around the bar and within the grab target around the
 *       mark; elsewhere on the bar nothing jumps. It becomes a drag only after it moves sideways past the touch slop
 *       before it moves up or down past it; up or down first, the page keeps it.</li>
 *   <li>The value is committed on the lift, once, and only when it changed. A cancel commits nothing.</li>
 *   <li>A released limit waits for the Charge limit control's write the way its choice sheet does: while the write is
 *       in flight the mark is pending and takes no drag; then the scooter's readback decides (kept, or failed: the
 *       sheet's failure words, and the mark springs back to what the scooter reports).</li>
 * </ul>
 */
final class ChargeBarDrag {
    private ChargeBarDrag() {}

    /** What the mark stands for. */
    static final int NONE = 0, LIMIT = 1, ALERT = 2;
    static final int[] NO_VALUES = new int[0];

    /**
     * @param limitControl the Charge limit control is offered on this model: a known encoding, not a Lab variant
     * @param limit the scooter's reported charge limit (fresh), or null
     * @param alertsOffered Volt's charge alerts are offered (ScooterService.chargeAlertsOffered: no native limit)
     * @param alertOn the charge alert is switched on
     */
    static int kind(boolean limitControl, Double limit, boolean alertsOffered, boolean alertOn) {
        // A model with its own charge limit never shows an alert's level, even with one saved (ChargeTimeReadings' rule).
        if (limitControl) return validPercent(limit) ? LIMIT : NONE;
        return alertsOffered && alertOn ? ALERT : NONE;
    }

    static boolean validPercent(Double value) {
        return value != null && Double.isFinite(value) && value > 0 && value <= 100;
    }

    /** The limit's choices as the control offers them: within 1 to 100, ascending, each once. */
    static int[] limitValues(int[] offered) {
        if (offered == null) return NO_VALUES;
        int[] kept = Arrays.stream(offered).filter(v -> v >= 1 && v <= 100).distinct().sorted().toArray();
        return kept.length == 0 ? NO_VALUES : kept;
    }

    /** min, min + step, ... up to max (the alert sheet's slider: ChargeAlertPolicy.CHARGE_MIN, CHARGE_MAX, STEP). */
    static int[] steps(int min, int max, int step) {
        if (step <= 0 || max < min) return NO_VALUES;
        int[] out = new int[(max - min) / step + 1];
        for (int i = 0; i < out.length; i++) out[i] = min + i * step;
        return out;
    }

    static boolean offered(int value, int[] allowed) {
        for (int v : allowed) if (v == value) return true;
        return false;
    }

    // ---- Geometry -------------------------------------------------------------------------------------------------

    /** Where a percentage sits along the track, from 0 at its start to 1 at its end. */
    static float fraction(double percent) {
        if (!Double.isFinite(percent)) return 0;
        return (float) Math.max(0, Math.min(1, percent / 100));
    }

    /** The x of a percentage on a track from start to end (left to right); right to left, 0% is at end. */
    static float x(double percent, float start, float end, boolean rtl) {
        float f = fraction(percent);
        return rtl ? end - f * (end - start) : start + f * (end - start);
    }

    /** The percentage at x on the same track (0 to 100). */
    static double percentAt(float x, float start, float end, boolean rtl) {
        float span = end - start;
        if (!(span > 0)) return 0;
        double f = (rtl ? end - x : x - start) / span;
        return Math.max(0, Math.min(100, f * 100));
    }

    /** The allowed value nearest a percentage; halfway between two, the lower. Allowed must be ascending. */
    static int snap(double percent, int[] allowed) {
        if (allowed.length == 0) throw new IllegalArgumentException();
        int best = allowed[0];
        for (int v : allowed) if (Math.abs(v - percent) < Math.abs(best - percent)) best = v;
        return best;
    }

    /**
     * One step from value (TalkBack's increase and decrease): the next allowed value above (direction > 0) or below,
     * or value itself at that end. A value between steps goes to its neighbour on that side.
     */
    static int step(int value, int direction, int[] allowed) {
        if (direction > 0) { for (int v : allowed) if (v > value) return v; }
        else for (int i = allowed.length - 1; i >= 0; i--) if (allowed[i] < value) return allowed[i];
        return value;
    }

    // ---- What the label shows and the screen reader says ----------------------------------------------------------

    /**
     * How much of the value label shows: as much as the bar is lifted under a finger, and wholly while a write is
     * pending or while the bar has keyboard focus (so an arrow key's user sees the value and the row's note, such as
     * "Untested here", before a key writes anything).
     */
    static float labelShown(float lift, boolean pending, boolean focused) {
        return Math.max(lift, pending || focused ? 1 : 0);
    }

    /**
     * The spoken state after the value: the row's "confirming" while a write is pending, then the row's note when it
     * has one, so what is said matches the label (which keeps the note while pending). Null parts are left out.
     */
    static String spokenState(boolean pending, String confirming, String note) {
        return (pending && confirming != null ? confirming : "") + (note != null ? note : "");
    }

    // ---- The charge limit's gates ---------------------------------------------------------------------------------

    /** Why a limit can't be dragged or written now; OK when it can. ChargeBar words each one as its control does. */
    enum Gate { OK, NO_SERVICE, UNMAPPED, LAB, RULE, NOT_CONNECTED, BUSY, NOT_FRESH, GATED, PARKED, NO_CHOICES, NOT_OFFERED, SAME }

    /** What the gate needs to know, read from BleController and ScooterService by ChargeBar. */
    static final class LimitState {
        boolean service, knownEncoding, lab, rule, ready, busy, fresh, available, parkedOnly;
        Integer actual;
        int[] choices = NO_VALUES;
    }

    /**
     * The Charge limit control's gates in its own order: Quick access's checks (MainActivity.quickAction: a service,
     * a known encoding, not a Lab variant, no region, firmware or full-ID rule against it, connected and ready, no
     * other change in flight, a fresh reported value), then the resolver's decision (a guided test, a restore, riding
     * or anything else that refuses writes; riding says "Stop to change" as the row does), then the choice sheet's
     * (the firmware offers choices, and only one of them is written). value null asks only whether a drag may start.
     * BleController.set still makes the final check at the write, as it does for the control.
     */
    static Gate limitGate(LimitState s, Integer value) {
        if (!s.service) return Gate.NO_SERVICE;
        if (!s.knownEncoding) return Gate.UNMAPPED;
        if (s.lab) return Gate.LAB;
        if (s.rule) return Gate.RULE;
        if (!s.ready) return Gate.NOT_CONNECTED;
        if (s.busy) return Gate.BUSY;
        if (!s.fresh || s.actual == null) return Gate.NOT_FRESH;
        if (!s.available) return s.parkedOnly ? Gate.PARKED : Gate.GATED;
        if (s.choices.length == 0) return Gate.NO_CHOICES;
        if (value == null) return Gate.OK;
        if (!offered(value, limitValues(s.choices))) return Gate.NOT_OFFERED;
        return s.actual.intValue() == value ? Gate.SAME : Gate.OK;
    }

    // ---- Touch ----------------------------------------------------------------------------------------------------

    /**
     * One touch on the bar, from its first contact to its lift or cancel. The view passes its geometry in pixels; the
     * gesture keeps no Android state, so a test drives it with numbers.
     */
    static final class Gesture {
        /** IDLE: no touch. PRESSED: down near the mark, direction not known yet. DRAGGING: the mark follows the finger.
         *  BLOCKED: a deliberate drag on a mark that can't move now (said once). DONE: the rest of this touch is ignored. */
        static final int IDLE = 0, PRESSED = 1, DRAGGING = 2, BLOCKED = 3, DONE = 4;
        /** What a move did, for the view to answer (hold the parents off, thicken, tick, say why). */
        static final int NOTHING = 0, STARTED = 1, MOVED = 2, STEPPED = 3, LET_GO = 4, REFUSED = 5;
        int phase = IDLE;
        float downX, downY, markX;
        int startValue, value;
        boolean live;

        /**
         * A finger down at (x, y). Taken (true) only inside the band (above and below barY) and within halfWidth of
         * the mark; anything else stays the page's and never moves the mark. live: the mark may move now; a mark that
         * can't still takes the touch, so a deliberate drag can say why.
         */
        boolean down(float x, float y, float markX, float barY, float above, float below, float halfWidth, boolean live, int value) {
            phase = IDLE;
            if (y < barY - above || y > barY + below || Math.abs(x - markX) > halfWidth) return false;
            downX = x; downY = y; this.markX = markX; this.live = live;
            startValue = this.value = value;
            phase = PRESSED;
            return true;
        }

        /**
         * A move to (x, y). From PRESSED: sideways past the slop, and more sideways than up or down, starts the drag (or,
         * on a mark that can't move, refuses it once); up or down past the slop first lets the touch go to the page.
         * While dragging the mark keeps the finger's offset from where it went down and lands on the nearest allowed
         * value; STEPPED means the value changed.
         */
        int move(float x, float y, float slop, float start, float end, boolean rtl, int[] allowed) {
            float dx = x - downX, dy = y - downY;
            if (phase == PRESSED) {
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    if (!live || allowed.length == 0) { phase = BLOCKED; return REFUSED; }
                    phase = DRAGGING;
                    int v = snap(percentAt(markX + dx, start, end, rtl), allowed);
                    value = v;
                    return STARTED;
                }
                if (Math.abs(dy) > slop) { phase = DONE; return LET_GO; }
                return NOTHING;
            }
            if (phase != DRAGGING) return NOTHING;
            int v = snap(percentAt(markX + dx, start, end, rtl), allowed);
            if (v == value) return MOVED;
            value = v;
            return STEPPED;
        }

        /** The lift: the value to commit, or null (no drag, a refused one, or back where it began). Ends the touch. */
        Integer up() {
            Integer out = phase == DRAGGING && value != startValue ? Integer.valueOf(value) : null;
            phase = IDLE;
            return out;
        }

        /**
         * A cancel: the system's ACTION_CANCEL, a second finger, the view detached or hidden, the window losing focus,
         * charging ending or the mark becoming undraggable. Nothing commits; with the finger still down the rest of the
         * touch is ignored. True when a drag was under way (the view puts the mark back).
         */
        boolean cancel(boolean fingerDown) {
            boolean was = phase == DRAGGING;
            phase = fingerDown && phase != IDLE ? DONE : IDLE;
            return was;
        }

        boolean dragging() { return phase == DRAGGING; }
        boolean active() { return phase != IDLE; }
    }

    // ---- After the lift -------------------------------------------------------------------------------------------

    /**
     * A released value on its way to the scooter (the limit) or the alert store. confirmed is what the scooter (or the
     * store) reports; target the released value while it is asked for (a confirmation may be showing) or in flight.
     */
    static final class Commit {
        static final int REST = 0, ASKING = 1, IN_FLIGHT = 2;
        /** What resolve found. */
        static final int NO_CHANGE = 0, WAITING = 1, KEPT = 2, FAILED = 3;
        int phase = REST;
        Integer confirmed, target;

        /** A fresh report from the scooter or the store; the mark shows it whenever nothing is asked for or in flight. */
        void report(Integer value) { confirmed = value; }
        /** The value the mark stands at when not dragged. */
        Integer shown() { return phase != REST && target != null ? target : confirmed; }
        boolean pending() { return phase != REST; }

        /** Released: asked for (the control's confirmation, then its write). */
        void ask(int value) { phase = ASKING; target = value; }
        /** The control accepted the write: it is in flight until the scooter answers. */
        void accepted() { if (phase == ASKING) phase = IN_FLIGHT; }
        /** Refused, cancelled at the confirmation, or dropped: the mark springs back to confirmed. */
        void refused() { phase = REST; target = null; }

        /**
         * The choice sheet's rule (MainActivity.ChoiceSheet.refresh): while the control's write is in flight, wait;
         * once it is not, the scooter's readback decides. Kept when it reads back the target; otherwise failed (the
         * command result picks the sheet's words) and the mark goes back to what the scooter reports.
         */
        int resolve(boolean inFlight, Integer actual) {
            if (phase != IN_FLIGHT) return NO_CHANGE;
            if (inFlight) return WAITING;
            int wanted = target;
            phase = REST; target = null;
            if (actual != null && actual == wanted) { confirmed = actual; return KEPT; }
            if (actual != null) confirmed = actual;
            return FAILED;
        }
    }
}
