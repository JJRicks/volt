package com.jjricks.volt;

/** Riding mode's screen rotation setting (owner Q14 A): lock to how the phone is held on opening, or follow it. */
final class RidingRotation {
    static final String KEY = "riding_rotation", LOCK = "lock", FOLLOW = "follow";

    private RidingRotation() {}

    /** Lock on open is the default; only an explicit Follow phone lets the screen turn. */
    static boolean locks(String stored) {
        return !FOLLOW.equals(stored);
    }
}
