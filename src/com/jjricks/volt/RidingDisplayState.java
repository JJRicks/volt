package com.jjricks.volt;

/**
 * When riding mode's display may act (Codex review, round 4): only while the activity is resumed and
 * the display is still attached. A display made while the activity is paused (a rebuild from
 * onServiceConnected, or onCreate before onResume) starts paused: no light sensor, no brightness
 * ticks and no window brightness until the activity's next resume. Pure Java, so a JVM test can
 * drive the lifecycle.
 */
final class RidingDisplayState {
    private boolean resumed, detached;

    /** resumed: the activity is resumed right now (MainActivity's own flag). */
    RidingDisplayState(boolean resumed) { this.resumed = resumed; }

    /** The display may listen, tick and set the window's brightness. */
    boolean active() { return resumed && !detached; }
    boolean detached() { return detached; }

    /** The activity resumed. True when the display should start now. */
    boolean resume() {
        if (detached || resumed) return false;
        resumed = true;
        return true;
    }

    /** The activity paused. True when the display should stop listening now. */
    boolean pause() {
        if (detached || !resumed) return false;
        resumed = false;
        return true;
    }

    /** Riding mode closed. True the first time only. */
    boolean detach() {
        if (detached) return false;
        detached = true;
        return true;
    }

    /** The light sensor is registered exactly when this is true. */
    boolean listens(boolean usesSensor) { return usesSensor && active(); }
}
