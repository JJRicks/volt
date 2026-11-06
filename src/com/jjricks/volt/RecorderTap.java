package com.jjricks.volt;

/**
 * What a tap on the Home Ride recorder card does, and how small the card may get. The red disc
 * and its words are the full control; the rest of the card is a larger start target that can
 * never stop a ride.
 */
final class RecorderTap {
    static final int NONE = 0, START = 1, CONNECT = 2, STOP = 3;
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

    /** The fewest dashboard rows a recorder card of this width needs: one, or two on the narrowest. */
    static int minimumRows(float widthDp) {
        return widthDp < ONE_ROW_MIN_WIDTH_DP ? 2 : 1;
    }
}
