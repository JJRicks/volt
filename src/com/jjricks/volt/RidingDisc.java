package com.jjricks.volt;

/**
 * The riding top bar's tune disc as it appears and disappears (owner, Nov 6: "a nice animation for
 * the tune button"), kept Android-free so plain JVM tests run it. The disc grows from nothing beside
 * the status pill while it fades and scales in, so the pill narrows smoothly instead of jumping, and
 * shrinks away the same way. It takes taps only while it's meant to show: the instant it starts
 * hiding, taps stop. With the system's animations off it simply appears or goes.
 */
final class RidingDisc {
    /** The smallest scale the disc grows from and shrinks to. */
    static final float MIN_SCALE = .6f;

    private boolean target;
    private float progress;

    /** Where the disc is going: true while it's shown or showing. */
    boolean target() { return target; }
    /** How far it is, 0 gone to 1 fully shown. */
    float progress() { return progress; }

    /** Sets where the disc is going; true when that changed and the motion must start. */
    boolean want(boolean shown) {
        if (shown == target) return false;
        target = shown;
        return true;
    }

    /** Taps count only while the disc is meant to show, never while it shrinks away. */
    boolean takesTaps() { return target; }

    /** Moves the motion on; outside 0 to 1 it's clamped. */
    void at(float value) { progress = Math.max(0, Math.min(1, value)); }

    /** Jumps to the end, as with animations off. */
    void finish() { progress = target ? 1 : 0; }

    /** True while any of it is on screen. */
    boolean visible() { return progress > 0; }

    /** The motion's length for the rest of the way: the full length scaled by what's left, 0 with animations off. */
    static long duration(long fullMs, float from, boolean target, boolean animationsOn) {
        if (!animationsOn) return 0;
        float left = target ? 1 - from : from;
        return Math.round(fullMs * Math.max(0, Math.min(1, left)));
    }

    /** The width share beside the pill: eased, so the pill starts and stops gently. */
    static float width(float progress) {
        float t = Math.max(0, Math.min(1, progress));
        return t * t * (3 - 2 * t);
    }

    static float alpha(float progress) { return Math.max(0, Math.min(1, progress)); }

    static float scale(float progress) { return MIN_SCALE + (1 - MIN_SCALE) * width(progress); }
}
