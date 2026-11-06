package com.jjricks.volt;

/**
 * What a tap on the Home Ride recorder card does, and how small the card may get. The red disc
 * and its words are the full control; the rest of the card is a larger start target that can
 * never stop a ride.
 */
final class RecorderTap {
    static final int NONE = 0, START = 1, CONNECT = 2, STOP = 3;
    /** The recorder's dashboard widget type (DashboardConfig). */
    static final String TYPE = "ride";
    /** Below this width (dp) the disc, the words and the arrow can't share one row. */
    static final float ONE_ROW_MIN_WIDTH_DP = 112;

    private RecorderTap() {}

    /**
     * The disc and its words: stop while recording, connect while disconnected, otherwise start.
     * While a start is already on its way ("Starting…"), a second tap does nothing.
     */
    static int lead(boolean recording, boolean ready, boolean starting) {
        if (recording) return STOP;
        if (starting) return NONE;
        return ready ? START : CONNECT;
    }

    /**
     * Anywhere else on the card: the disc's own start (or connect) path, and nothing while
     * recording, while starting, while editing the dashboard, or while the disc is disabled
     * (saving, connecting).
     */
    static int card(boolean editing, boolean leadEnabled, boolean recording, boolean ready, boolean starting) {
        if (editing || !leadEnabled || recording) return NONE;
        return lead(false, ready, starting);
    }

    /** What closing Home does with a start that was tapped but has not run yet. */
    static final int CLOSE_NOTHING = 0, CLOSE_HAND_OFF = 1, CLOSE_REPORT = 2;

    /**
     * Closing Home before the start's frame: nothing pending does nothing; while the app stays open
     * the start is handed to the app (it runs right after the screen change, through the same
     * Rides path and checks as an ordinary start); when the app is going away it can't run, so
     * Volt says the recording did not start.
     */
    static int onClose(boolean pending, boolean appStaying) {
        if (!pending) return CLOSE_NOTHING;
        return appStaying ? CLOSE_HAND_OFF : CLOSE_REPORT;
    }

    /**
     * An accepted start tap that has not run yet. Exactly one of the start's frame, its timeout or
     * Home's close takes it, so a tap starts at most once and is never dropped silently. Each tap
     * gets its own token: a late callback from an earlier tap can never take a later tap's start.
     */
    static final class PendingStart {
        private boolean armed;
        private int generation;
        /** A new tap: the earlier one, if still waiting, is replaced. Returns this tap's token. */
        int arm() {
            armed = true;
            return ++generation;
        }
        boolean pending() { return armed; }
        /** True once per arm, for any tap (Home closing): the caller now owns the start. */
        boolean take() {
            if (!armed) return false;
            armed = false;
            return true;
        }
        /** True once, and only for the latest tap's own token. */
        boolean take(int token) {
            return token == generation && take();
        }
        /** No later tap has replaced this one. */
        boolean latest(int token) { return token == generation; }
    }

    /**
     * The start's three-second safety net: if the tap is still waiting (its frame never came), the
     * card has shown "Starting…" long enough, so the start is cancelled and Volt says it did not
     * start; it can't run later. False when the frame, Home's close or a newer tap got there first.
     */
    static boolean timeoutCancels(PendingStart pending, int token) {
        return pending.take(token);
    }

    /** The fewest dashboard rows a recorder card of this width needs: one, or two on the narrowest. */
    static int minimumRows(float widthDp) {
        return widthDp < ONE_ROW_MIN_WIDTH_DP ? 2 : 1;
    }
}
