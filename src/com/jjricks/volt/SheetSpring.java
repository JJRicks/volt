package com.jjricks.volt;

/**
 * How a bottom sheet that follows a finger moves (DragSheet), in plain Java so it can be tested off the phone: the damped
 * spring it settles with once the finger lets go, the decision whether it opens or closes, and the rubber band past fully
 * open. The owner asked for this on the Widgets Home dock (Nov 15): the sheet follows the finger to a commit point, then
 * springs the rest of the way with a small bounce, or springs back.
 *
 * Positions are the sheet's offset in px below fully open: 0 is open, travel (the sheet's height as shown) is closed and
 * gone under the screen's bottom edge, and a negative offset is past open (the bounce, or a drag upward). Velocities are
 * px/s, positive downward (closing). Times are seconds of the spring's own clock; DragSheet runs that clock on a
 * ValueAnimator, so Android's animator duration scale slows or skips it.
 */
final class SheetSpring {
    /**
     * Opening: a little under critical damping (0.75), so a sheet released from rest passes open once by about 3% of the
     * way it still had to go and comes back with a second ripple of under 0.1% (barely visible). 18 rad/s gives about
     * 0.2 s to reach open, the bounce's peak at about 0.25 s, and rest (within half a dp) at about 0.5 s.
     */
    static final double OPEN_DAMPING = 0.75, OPEN_STIFFNESS = 18;
    /**
     * Closing and snapping back: critically damped (no bounce, which would only show as the sheet's top peeking back up),
     * aimed past closed by 5% of the way it has to go, so it leaves the screen in a finite time instead of creeping the
     * last pixels, and from rest every close takes about 0.3 s, a short snap-back and a close from fully open alike.
     */
    static final double CLOSE_DAMPING = 1, CLOSE_STIFFNESS = 16, CLOSE_PAST = .05;
    /** The commit point ("V1"): a quarter of the sheet's travel from where the drag started (closed or open). */
    static final double COMMIT = .25;
    /** A fling: this fast (dp/s) toward open or closed decides alone, wherever the sheet is. */
    static final float FLING_DP = 700;
    /**
     * A quick flick: a gesture this short (ms, finger down to up) that is still moving toward open or closed at least at
     * Android's minimum fling speed decides too, so a flick that lifts right after the swipe is recognised still opens the
     * sheet (as before the drag). A slow drag takes longer than this.
     */
    static final long QUICK_MS = 250;
    /** The most the sheet ever goes past open (dp): the bounce and the rubber band. DragSheet's background reaches this far below the screen. */
    static final float CAP_DP = 18;
    /** Past open, a drag moves the sheet about a third as far as the finger (then less, toward CAP_DP). */
    static final double BAND = 1 / 3d;
    /** At rest: the spring has settled when it stays within this (dp) of its end. */
    static final float REST_DP = .5f;
    /** The longest a settle runs (s, at animator scale 1), whatever it is handed. */
    static final double LONGEST = 1.2;

    private SheetSpring() {}

    /** A damped spring (damping ratio 0 < damping <= 1, natural frequency stiffness in rad/s) from x0 and v0 toward 0. */
    static final class Spring {
        final double damping, stiffness;
        Spring(double damping, double stiffness) {
            if (!(damping > 0 && damping <= 1) || !(stiffness > 0)) throw new IllegalArgumentException("damping " + damping + ", stiffness " + stiffness);
            this.damping = damping; this.stiffness = stiffness;
        }

        /** The position t seconds after starting at x0 with velocity v0 (the rest position is 0). */
        double position(double x0, double v0, double t) {
            double w = stiffness, decay = Math.exp(-damping * w * t);
            if (damping >= 1) return (x0 + (v0 + w * x0) * t) * decay;
            double wd = w * Math.sqrt(1 - damping * damping), b = (v0 + damping * w * x0) / wd;
            return decay * (x0 * Math.cos(wd * t) + b * Math.sin(wd * t));
        }

        /** The velocity at t. */
        double velocity(double x0, double v0, double t) {
            double w = stiffness, decay = Math.exp(-damping * w * t);
            if (damping >= 1) { double c = v0 + w * x0; return (c - w * (x0 + c * t)) * decay; }
            double wd = w * Math.sqrt(1 - damping * damping), b = (v0 + damping * w * x0) / wd;
            double cos = Math.cos(wd * t), sin = Math.sin(wd * t);
            return decay * (-damping * w * (x0 * cos + b * sin) + wd * (b * cos - x0 * sin));
        }

        /** The time after which the position stays within tolerance of rest (0 when it starts there), at most LONGEST. */
        double settle(double x0, double v0, double tolerance) {
            double w = stiffness;
            if (damping < 1) {
                // The envelope: the position never exceeds it, and it only shrinks.
                double wd = w * Math.sqrt(1 - damping * damping), b = (v0 + damping * w * x0) / wd;
                double amplitude = Math.hypot(x0, b);
                return amplitude <= tolerance ? 0 : Math.min(LONGEST, Math.log(amplitude / tolerance) / (damping * w));
            }
            // Critically damped: |x| <= (|x0| + |v0 + w x0| t) e^-wt, which rises at most once and then only falls.
            double c = Math.abs(v0 + w * x0), a = Math.abs(x0), last = 0;
            for (double t = 0; t <= LONGEST; t += .001) if ((a + c * t) * Math.exp(-w * t) > tolerance) last = t + .001;
            return Math.min(LONGEST, last);
        }
    }

    static final Spring OPENING = new Spring(OPEN_DAMPING, OPEN_STIFFNESS), CLOSING = new Spring(CLOSE_DAMPING, CLOSE_STIFFNESS);

    /**
     * The release decision: true opens the sheet, false closes it. startedOpen: the drag began on the open sheet (else it
     * pulled the sheet up from closed). offset and travel: where the sheet is now and its full travel (px). velocity: the
     * finger's, px/s downward. fling and minFling: the fling speed and Android's minimum fling speed, px/s. gestureMs: the
     * time from finger down to up. A fling decides first, then a quick flick, then the position: from closed the sheet
     * opens once a quarter of it shows, and from open it closes once it went a quarter of the way down.
     */
    static boolean opens(boolean startedOpen, double offset, double travel, double velocity, double fling, double minFling, long gestureMs) {
        if (velocity <= -fling) return true;
        if (velocity >= fling) return false;
        if (gestureMs >= 0 && gestureMs <= QUICK_MS) {
            if (velocity <= -minFling) return true;
            if (velocity >= minFling) return false;
        }
        if (!(travel > 0)) return startedOpen;
        double shown = 1 - offset / travel;
        return startedOpen ? shown > 1 - COMMIT : shown >= COMMIT;
    }

    /**
     * The release decision for a sheet a finger caught while it settled (DragSheet: a finger down on a sheet springing
     * closed or open): true opens it. closing: it was settling closed when caught (else open). caughtAt: its offset then
     * (px); offset: where it is now; moved: how far (px) the finger went toward open since the catch (up positive), before
     * the rubber band past open; the rest as for opens. A fling or a quick flick decides first, as for opens; otherwise the
     * settle it was on carries on unless the finger moved against it by a quarter of the travel or more (up to reopen one
     * that was closing, down to close one that was opening), or took the sheet all the way to the other end (one caught
     * closing with under a quarter to go to open, pushed back fully open, stays open; only when the catch began short of
     * that end), so a catch taken back to where it started, or a plain tap, changes nothing. The finger's own distance
     * counts, not the sheet's: a close that began during the opening bounce is caught past open, where the sheet barely
     * moves (the rubber band, at most CAP_DP), and a deliberate drag up a quarter there still reopens it.
     */
    static boolean caughtOpens(boolean closing, double caughtAt, double offset, double moved, double travel, double velocity, double fling, double minFling, long gestureMs) {
        if (velocity <= -fling) return true;
        if (velocity >= fling) return false;
        if (gestureMs >= 0 && gestureMs <= QUICK_MS) {
            if (velocity <= -minFling) return true;
            if (velocity >= minFling) return false;
        }
        if (!(travel > 0)) return !closing;
        double up = moved / travel;
        // All the way to the other end counts only for a catch that began short of it: a close that started while the
        // opening bounce was past open is caught at or past open already, and a plain tap there must not reopen it (and
        // an opening caught at closed, its first frame, must not close on a plain tap).
        if (closing) return up >= COMMIT || offset <= 0 && caughtAt > 0;
        return -up < COMMIT && (offset < travel || caughtAt >= travel);
    }

    // ---- The light theme's status bar over the dim --------------------------------------------------------------------

    /**
     * The relative luminance (WCAG 2) at which white and black icons contrast equally with what is behind them, both
     * 4.58:1: (1 + .05) / (L + .05) = (L + .05) / .05. Darker than this, white icons have more (and at least 4.58:1),
     * lighter, black ones do.
     */
    static final double ICON_CROSSOVER = Math.sqrt(1.05 * .05) - .05;

    /** WCAG 2 relative luminance of an sRGB colour (its alpha ignored). */
    static double luminance(int rgb) {
        return .2126 * linear((rgb >> 16) & 255) + .7152 * linear((rgb >> 8) & 255) + .0722 * linear(rgb & 255);
    }

    /** The luminance of colour with black at alpha (0..1) over it, blended as Android composites (in sRGB). */
    static double dimmed(int rgb, double alpha) {
        double keep = 1 - Math.max(0, Math.min(1, alpha));
        int r = (int) Math.round(((rgb >> 16) & 255) * keep), g = (int) Math.round(((rgb >> 8) & 255) * keep), b = (int) Math.round((rgb & 255) * keep);
        return luminance((r << 16) | (g << 8) | b);
    }

    /** Whether the status bar's icons should be white over background dimmed by alpha (else dark): past the crossover. */
    static boolean whiteIcons(int background, double alpha) { return dimmed(background, alpha) < ICON_CROSSOVER; }

    /**
     * whiteIcons, with the sheet's own surface reaching up under the status bar ({@code sheetUnderBar}: a short window's
     * bounce or rubber band past open): the icons then sit on the light sheet, not on the dim, so they stay dark
     * whatever the dim (#510 review, round 5).
     */
    static boolean whiteIcons(int background, double alpha, boolean sheetUnderBar) { return !sheetUnderBar && whiteIcons(background, alpha); }

    /** WCAG 2 contrast ratio of two luminances. */
    static double contrast(double a, double b) { return (Math.max(a, b) + .05) / (Math.min(a, b) + .05); }

    private static double linear(int channel) {
        double c = channel / 255d;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }

    // ---- Past open: the rubber band while dragging, the soft limit on the bounce -------------------------------------------

    /**
     * Where the sheet shows (offset) for a drag whose finger would put it at raw: 1:1 between open and closed, never below
     * closed, and past open a third of the finger's travel, easing toward cap (never past it).
     */
    static double dragged(double raw, double travel, double cap) {
        if (raw >= 0) return Math.min(raw, Math.max(0, travel));
        return -cap * Math.tanh(-raw * BAND / cap);
    }

    /** The finger position (raw) that shows the sheet at offset: dragged's inverse, so a drag can start where a settle was. */
    static double undragged(double offset, double cap) {
        if (offset >= 0) return offset;
        return -atanh(Math.min(-offset / cap, .999)) * cap / BAND;
    }

    /** How fast the sheet moves for a finger at raw moving at 1 px/s: 1 on the way, under a third past open. */
    static double dragSlope(double raw, double cap) {
        if (raw >= 0) return 1;
        double t = Math.tanh(-raw * BAND / cap);
        return BAND * (1 - t * t);
    }

    /** The bounce's soft limit: a spring position past open (negative) shows eased toward cap, with no kink at open. */
    static double capped(double x, double cap) { return x >= 0 ? x : cap * Math.tanh(x / cap); }

    private static double atanh(double x) { return .5 * Math.log((1 + x) / (1 - x)); }

    // ---- A settle ---------------------------------------------------------------------------------------------------------

    /** One settle after a release (or a tap): the sheet's offset over time, to open or to closed. */
    static final class Plan {
        final boolean open;
        /** Its spring: OPENING or CLOSING, or for a hard fling open a more damped one (Plan.open). */
        private final Spring spring;
        /** The spring's own start (opening: before the soft limit is applied) and target, px. */
        private final double x0, v0, target, travel, cap;
        /** How long it runs (s, at animator scale 1). Closing: until the sheet is wholly under the bottom edge. */
        final double seconds;

        private Plan(boolean open, Spring spring, double x0, double v0, double target, double travel, double cap, double seconds) {
            this.open = open; this.spring = spring; this.x0 = x0; this.v0 = v0; this.target = target; this.travel = travel; this.cap = cap; this.seconds = seconds;
        }

        /**
         * To open from offset (px, past open when negative) moving at velocity (px/s downward), with the sheet's travel, the
         * bounce's cap and the rest tolerance (px). The hand-off keeps both the position and the velocity.
         */
        static Plan open(double offset, double velocity, double travel, double cap, double tolerance) {
            double x0 = offset, v0 = velocity;
            if (offset < 0) {
                // Undo the soft limit, so the spring starts where the sheet shows and as fast as it moves.
                double c = Math.min(-offset / cap, .999);
                x0 = -atanh(c) * cap;
                v0 = velocity / (1 - c * c);
            }
            Spring spring = opening(x0, v0, cap);
            return new Plan(true, spring, x0, v0, 0, travel, cap, spring.settle(x0, v0, tolerance));
        }

        /**
         * The opening spring for this start: OPENING, unless the finger flung it so hard that it would pass open by more
         * than twice the cap (which the soft limit hides, but whose second ripple would show); then just enough more
         * damping (up to critical, which has no ripple) to keep it there.
         */
        private static Spring opening(double x0, double v0, double cap) {
            for (double damping = OPEN_DAMPING; damping < 1; damping += .025) {
                Spring spring = damping == OPEN_DAMPING ? OPENING : new Spring(damping, OPEN_STIFFNESS);
                double past = 0;
                for (double t = 0; t <= LONGEST; t += .002) past = Math.min(past, spring.position(x0, v0, t));
                if (past >= -2 * cap) return spring;
            }
            return new Spring(1, OPEN_STIFFNESS);
        }

        /** To closed from offset moving at velocity: critically damped toward a little past closed, ending once wholly gone. */
        static Plan close(double offset, double velocity, double travel) {
            double target = Math.max(0, travel) + Math.max(1, CLOSE_PAST * (travel - offset)), x0 = offset - target;
            double seconds = LONGEST;
            if (offset >= travel) seconds = 0;
            else for (double t = .001; t <= LONGEST; t += .001) if (CLOSING.position(x0, velocity, t) + target >= travel) { seconds = t; break; }
            return new Plan(false, CLOSING, x0, velocity, target, travel, Math.max(0, travel), seconds);
        }

        /** The sheet's offset t seconds in (at the end: exactly open, or closed). */
        double offset(double t) {
            if (t >= seconds) return open ? 0 : travel;
            if (open) return capped(spring.position(x0, v0, t), cap);
            return target + spring.position(x0, v0, t);
        }

        /** The sheet's velocity at t (px/s downward), for a hand-off when the settle is restarted (its height changed). */
        double velocity(double t) {
            if (t >= seconds) return 0;
            if (!open) return spring.velocity(x0, v0, t);
            double x = spring.position(x0, v0, t), v = spring.velocity(x0, v0, t);
            if (x >= 0) return v;
            double c = Math.tanh(x / cap);
            return v * (1 - c * c);
        }

        long millis() { return Math.round(seconds * 1000); }
    }
}
