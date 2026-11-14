package com.jjricks.volt;

/**
 * The charging view's one time tile (owner's design, Nov 12 2026). It holds up to two readings: the time to the
 * limit (the scooter's own charge limit below 100%, otherwise the charge alert's level) and the time to 100%.
 * With both, a tap or a sideways swipe shows the other one and the choice is remembered per scooter.
 * Everything here is pure, so the JVM tests cover which readings exist, which one shows, the saved choice and
 * every step of a switch (taps, drags, interruptions).
 */
final class ChargeTimeReadings {
    private ChargeTimeReadings() {}

    /** The two readings, also the order of the tile's dots: the limit (or alert) first, then 100%. */
    static final int LIMIT = 0, FULL = 1;
    /** What the limit reading counts down to. */
    static final int NO_LIMIT = 0, SCOOTER_LIMIT = 1, ALERT_LEVEL = 2;
    /** Per scooter, in app_settings: KEY + profile id. Holds FULL_CHOICE when the tile was left on 100%. */
    static final String KEY = "charge_time_reading_";
    static final String FULL_CHOICE = "full", LIMIT_CHOICE = "limit";

    /**
     * The scooter's own limit wins: it is where charging really stops, and charge alerts are offered only on
     * models without one (ChargeAlertPolicy.offered), so both at once means an alert saved on a limit model,
     * which the hero has never shown. The alert counts only while its countdown is live (not reached, not off).
     */
    static int limitSource(Double scooterTarget, boolean alertCountdown) {
        if (scooterTarget != null && !scooterTarget.isNaN() && scooterTarget > 0 && scooterTarget < 100) return SCOOTER_LIMIT;
        return alertCountdown ? ALERT_LEVEL : NO_LIMIT;
    }

    /**
     * The same, with the model's evidence: a model that offers charge alerts has no native charge limit
     * (ChargeAlertPolicy.offered), so a limit-like reading from it is not one and never replaces the alert's
     * countdown. The rule ScooterService.chargeLiveFacts uses for the same reading.
     */
    static int limitSource(Double scooterTarget, boolean alertsOffered, boolean alertCountdown) {
        return limitSource(alertsOffered ? null : scooterTarget, alertCountdown);
    }

    static boolean two(int limitSource) { return limitSource == SCOOTER_LIMIT || limitSource == ALERT_LEVEL; }

    /**
     * The cutoff the one reading is named for: the same answer as its value (review of #445). With no limit the one
     * reading is the time to 100%, so a limit-like target that limitSource did not take (a model that offers charge
     * alerts has no native limit) names 100%, never "Time to 80%" over the time to full. An unknown cutoff stays unknown.
     */
    static Double oneReadingTarget(Double estimateTarget, int limitSource) {
        return limitSource == NO_LIMIT && estimateTarget != null && !estimateTarget.isNaN() && estimateTarget < 100 ? Double.valueOf(100) : estimateTarget;
    }

    /** A missing, old or damaged value is the default: the limit reading. */
    static int preferred(String stored) { return FULL_CHOICE.equals(stored) ? FULL : LIMIT; }

    static String encode(int reading) { return reading == FULL ? FULL_CHOICE : LIMIT_CHOICE; }

    /** With one reading the tile shows the time to 100% (today's single tile) and the choice waits. */
    static int shown(boolean two, int preferred) { return two && preferred == FULL ? FULL : two ? LIMIT : FULL; }

    static int other(int reading) { return reading == FULL ? LIMIT : FULL; }

    /** Profiles only: a dashboard with no scooter keeps its choice in memory. */
    static String key(String profileId) {
        return profileId == null || profileId.isEmpty() || "unconfigured".equals(profileId) ? null : KEY + profileId;
    }

    /**
     * A tap moves like turning a page with the dots: to the second reading the old one leaves toward the start
     * (left, or right in a right-to-left layout), back to the first it leaves toward the end. Screen x sign.
     */
    static int tapDirection(int from, boolean rtl) {
        int towardStart = rtl ? 1 : -1;
        return from == LIMIT ? towardStart : -towardStart;
    }

    /** A released drag completes past this share of the way, or with a fling its own way. */
    static final float COMPLETE_AT = .4f;
    /** The outgoing reading is gone by this share of the way; the incoming starts after FADE_IN_FROM. */
    static final float FADE_OUT_BY = .45f, FADE_IN_FROM = .4f;

    static float outgoingAlpha(float progress) { return 1 - clamp(progress / FADE_OUT_BY); }
    static float incomingAlpha(float progress) { return clamp((progress - FADE_IN_FROM) / (1 - FADE_IN_FROM)); }
    /** Outgoing travels with direction; incoming arrives from the other side. Fractions of the travel distance. */
    static float outgoingShift(float progress, int direction) { return direction * clamp(progress); }
    static float incomingShift(float progress, int direction) { return -direction * (1 - clamp(progress)); }
    /** How much of the dot for reading r is filled (1 shown, 0 not), following the switch. */
    static float dotFill(Switch state, int reading) {
        float p = clamp(state.progress);
        return reading == state.resting ? 1 - p : p;
    }

    private static float clamp(float value) { return value != value ? 0 : Math.max(0, Math.min(1, value)); }

    /**
     * One tile's switch: the reading it rests on, the one it is heading for (the user's choice, saved at once),
     * how far it has gone (0 = resting fully shown, 1 = the other fully shown) and which way the outgoing
     * reading travels. Every path ends at rest on target: no half state survives settle().
     */
    static final class Switch {
        int resting = LIMIT, target = LIMIT, direction = -1;
        float progress;
        boolean dragging;

        boolean atRest() { return !dragging && progress == 0 && target == resting; }
        /** Where the running animation is heading: 0 back to resting, 1 on to the other. */
        float goal() { return target == resting ? 0 : 1; }

        /** Jumps to rest on a reading (a restore, one reading only, reduced motion, hidden or detached). */
        void rest(int reading) { resting = target = reading; progress = 0; dragging = false; }
        /** Ends any motion where it was heading. */
        void settle() { rest(target); }

        /** A tap: from rest it heads to the other reading; mid-switch it turns back. Returns the chosen reading. */
        int tap(boolean rtl) {
            if (dragging) return target;
            if (progress == 0 && target == resting) {
                direction = tapDirection(resting, rtl);
                target = other(resting);
            } else target = target == resting ? other(resting) : resting;
            return target;
        }

        /** A sideways drag takes over: a switch still moving finishes first, so a drag always starts at rest. */
        void startDrag() { settle(); dragging = true; }

        /** dx as a share of the travel distance, signed by screen x; the outgoing reading follows the finger. */
        void drag(float share) {
            if (!dragging) return;
            if (share != 0) direction = share > 0 ? 1 : -1;
            progress = clamp(Math.abs(share));
        }

        /**
         * Lift: past COMPLETE_AT, or flung the way it was going, it completes; flung back or short, it returns.
         * fling is the release velocity's sign along screen x (0 for none). Returns the chosen reading.
         */
        int release(int fling) {
            if (!dragging) return target;
            dragging = false;
            boolean forward = fling == 0 ? progress >= COMPLETE_AT : fling == direction && progress > 0;
            target = forward ? other(resting) : resting;
            if (progress == 0) target = resting;
            return target;
        }

        /** The scroll took the gesture or it was cancelled: back to where it started. */
        void cancelDrag() { if (dragging) { dragging = false; target = resting; } }

        /** One animation frame. Reaching the goal ends at rest; the other reading becomes the resting one. */
        void step(float next) {
            if (dragging) return;
            progress = clamp(next);
            if (target != resting && progress >= 1) rest(target);
            else if (target == resting && progress <= 0) rest(resting);
        }
    }

    /**
     * One touch on the tile, from its first contact to its lift or cancel. OWN began while the tile could switch and
     * is the tile's (tap, drag, long press); PLAIN began with one reading or while editing and is the plain tile's;
     * DEAD was cut off while the finger was down, by a reset of the tile (the reveal ending, shown or hidden, a
     * reading arriving or going, edit mode, close, detach) or by the tile's switchability changing under it, and
     * does nothing more (no drag, no switch, no tap) until the next touch begins.
     */
    static final class Touch {
        static final int NONE = 0, OWN = 1, PLAIN = 2, DEAD = 3;
        int phase = NONE;

        /** The first contact: every touch starts fresh, whatever the last one left. */
        void begin(boolean switchable) { phase = switchable ? OWN : PLAIN; }
        /** The lift or cancel, or a first contact nothing in the tile took. */
        void end() { phase = NONE; }
        /** A reset with a finger down. True when it cut a touch: the caller drops its press and lets the parents go. */
        boolean cut() {
            if (phase == NONE || phase == DEAD) return false;
            phase = DEAD;
            return true;
        }
        /** Checked on each later event: a touch that began one way and can now switch the other way is cut. */
        boolean changed(boolean switchable) { return phase == OWN ? !switchable : phase == PLAIN && switchable; }
        boolean own() { return phase == OWN; }
        boolean dead() { return phase == DEAD; }
    }
}
