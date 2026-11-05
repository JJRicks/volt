package com.jjricks.volt;

/**
 * What a tap on the Home Ride recorder card does. The red disc and its words are the full
 * control; the rest of the card is a larger start target that can never stop a ride.
 */
final class RecorderTap {
    static final int NONE = 0, START = 1, CONNECT = 2, STOP = 3;

    private RecorderTap() {}

    /** The disc and its words: stop while recording, connect while disconnected, otherwise start. */
    static int lead(boolean recording, boolean ready) {
        if (recording) return STOP;
        return ready ? START : CONNECT;
    }

    /**
     * Anywhere else on the card: the disc's own start (or connect) path, and nothing while
     * recording, while editing the dashboard, or while the disc is disabled (saving, connecting).
     */
    static int card(boolean editing, boolean leadEnabled, boolean recording, boolean ready) {
        if (editing || !leadEnabled || recording) return NONE;
        return lead(false, ready);
    }
}
