package com.jjricks.volt;

/**
 * The words for a scooter switched off but still answering (ScooterPowerState, 2.0.5 item 5): Home's status line, the
 * connection state and its line, and the UT5 MAX's refusal before the first motion report. A refused change while off
 * gives CapabilityResolver.OFF_REASON's own words ("Turn the scooter on first").
 */
final class ScooterOffWords {
    private ScooterOffWords() { }
    /** Home's status line while off ("Scooter off · Turn the scooter on to use it"). */
    static String status() { return T.dashboardStatusScooterOff(); }
    /** Its short rung, where the full line does not fit ("Scooter off"). */
    static String statusShort() { return T.dashboardStatusScooterOffShort(); }
    /** The status line as a screen reader says it. */
    static String spoken() { return T.dashboardStatusSpokenScooterOff(); }
    /** The connection state word ("Scooter off") for the connection line and details. */
    static String state() { return T.bleStatusScooterOff(); }
    /** The line under it in the connection details ("Turn the scooter on to use it"). */
    static String detail() { return T.bleScooterOffDetail(); }
    /**
     * The UT5 MAX's refusal before the first motion report after connecting ("Waiting for the scooter to report its
     * speed"). Never "Bring the scooter to a stop first": that is for a scooter that is moving.
     */
    static CharSequence waitingForSpeed() { return M.bleWaitingSpeedReport(); }
}
