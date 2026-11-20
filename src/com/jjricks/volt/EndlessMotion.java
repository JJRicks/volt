package com.jjricks.volt;

/**
 * Whether Volt's never-ending animations may run: the charging glow and the cable pulses (DashboardScreen's charging
 * view), the odometer carry-over's rings, the riding recording dot, the drive-mode question's dot, Live Stats' signal
 * and the app's own drawn spinners. Each asks {@link Ui#endlessMotionEnabled()}, and when the answer is no it shows the
 * still look it already uses with animations off.
 *
 * <p>In the app the answer is always the system's animation setting ({@link Ui#motionEnabled()}), so on a phone these
 * run at the display's full rate exactly as before. The one difference is {@link #held}, a hold for emulator
 * validations only: on an emulator drawing in software an endless animation keeps the main thread from ever going
 * idle, and a step that waits for idle stalls. Nothing in the app sets it, no setting or screen reaches it, and it has
 * no setter: the validations' shared base class (android-tests/ValidationInstrumentation) sets it by reflection in the
 * test process, and validations that check motion turn it off again. Pure Java (tests/EndlessMotionTest checks the
 * default).
 */
final class EndlessMotion {
    /** Test-only. False in the app, always; written only by reflection from android-tests. */
    private static volatile boolean held;

    private EndlessMotion() { }

    /** Whether a never-ending animation may run, given whether the system lets apps animate at all. */
    static boolean allowed(boolean motionEnabled) {
        return motionEnabled && !held;
    }

    /** Whether an emulator validation holds the endless animations still (false outside validations). */
    static boolean held() {
        return held;
    }
}
