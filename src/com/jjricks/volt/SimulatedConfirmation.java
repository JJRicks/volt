package com.jjricks.volt;

/**
 * A slow simulated scooter, for emulator validations only (the pending spinner's, PendingControl). The simulator
 * normally applies a change at once; with a delay set here a change waits that long as a pending write (BleController's
 * own pending(), as a real scooter's unconfirmed change does) before the simulator applies it, and with {@link #NEVER}
 * it never confirms: after {@link #TIMEOUT_MS}, the real confirmation window, the change ends unconfirmed as a real one
 * does, keeping the old value.
 *
 * <p>Nothing in the app sets it, no setting or screen reaches it, and it has no setter: android-tests sets
 * {@code delayMs} by reflection in the test process, as with EndlessMotion. It is 0 in the app, always, and a real
 * scooter's changes never look at it.
 */
final class SimulatedConfirmation {
    /** The simulator never confirms: the change times out. */
    static final long NEVER = -1;
    /** BleController's confirmation window after a change goes out (mutationTimeout). */
    static final long TIMEOUT_MS = 6500;
    /** Test-only. 0 in the app, always; written only by reflection from android-tests. */
    private static volatile long delayMs;

    private SimulatedConfirmation() { }

    /** How long the simulator waits before it confirms a change: 0 at once (always, in the app), or NEVER. */
    static long delayMs() { return delayMs; }
}
