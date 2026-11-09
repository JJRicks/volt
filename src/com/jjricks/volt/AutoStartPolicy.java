package com.jjricks.volt;

/**
 * Decides when Volt starts a ride recording (auto-record) or opens riding mode (auto ride mode) on its
 * own because the scooter started moving. Pure Java and in-process only: the caller feeds it the
 * connection, the switches and a monotonic clock, and it never reads a clock or a sensor itself.
 *
 * Both are off by default and share one gate: Volt is connected to the scooter, the switch is on, and
 * a fresh speed reading above zero arrived (see {@link #moving}). A stale reading never counts.
 *
 * Hold rules:
 * - A recording the rider stops by hand holds auto-record. The automatic ride-end rules (no motion
 *   for 10 minutes, the scooter going away) set no hold, so moving again starts a new recording.
 * - A failed automatic start also holds it, so a full disk is not retried on every speed sample.
 * - Backing out of riding mode holds auto ride mode, and so does the automatic open itself: one open
 *   per trigger.
 * - Every hold ends on a new connection to the scooter.
 *
 * The service owns the only instance, so a hold survives the activity being recreated and the policy
 * sees connections that come and go while Volt is out of sight.
 */
final class AutoStartPolicy {
    /** Settings switches, both off by default (BackupRegistry includes them). */
    static final String AUTO_RECORD_KEY = "auto_record_rides", AUTO_RIDE_MODE_KEY = "riding_auto_open";
    /** The Rides GPS route switch, on by default. */
    static final String PHONE_GPS_KEY = "phone_gps";
    /** Set once the rider has answered auto-record's location offer. Belongs to this phone's permission, so BackupRegistry leaves it out. */
    static final String LOCATION_ASKED_KEY = "auto_record_location_asked";
    /** The longest a speed reading may have been held. Each one is judged as it arrives, so this only guards stale reads. */
    static final long SPEED_FRESH_MS = 3000;

    private boolean linked, recordHeld, rideModeHeld;
    private int generation;

    /** True for a current link reporting a speed above zero, received within {@link #SPEED_FRESH_MS}. */
    static boolean moving(boolean connected, int speedRaw, long sampleMs, long nowMs) {
        return connected && speedRaw > 0 && sampleMs > 0 && nowMs >= sampleMs && nowMs - sampleMs <= SPEED_FRESH_MS;
    }

    /**
     * Call on every connection change, before asking for a decision. A new link ends every hold,
     * including a drop and reconnect too quick to be seen as disconnected in between.
     *
     * @param generation the transport's connection number, which changes whenever a link is torn down
     */
    void observeLink(boolean connected, int generation) {
        if (connected && (!linked || generation != this.generation)) recordHeld = rideModeHeld = false;
        linked = connected;
        if (connected) this.generation = generation;
    }

    /** The rider ended a recording by hand (not an automatic ride end). */
    void riderStoppedRecording() { recordHeld = true; }

    /** An automatic start was tried and failed. */
    void recordingStartFailed() { recordHeld = true; }

    /** The rider backed out of riding mode. */
    void riderLeftRidingMode() { rideModeHeld = true; }

    /** Riding mode was opened automatically. */
    void rideModeOpened() { rideModeHeld = true; }

    boolean recordHeld() { return recordHeld; }

    boolean rideModeHeld() { return rideModeHeld; }

    /** Whether to start a recording now. Refusal reasons are inputs here so that the automatic start stays silent. */
    boolean shouldRecord(boolean enabled, boolean moving, boolean recording, boolean chargeRecording, boolean simulating,
            boolean phoneSaving, boolean canStart) {
        return enabled && moving && !recordHeld && !recording && !chargeRecording && !simulating && !phoneSaving && canStart;
    }

    /**
     * Whether turning auto-record on should first offer the ride location prompt. Asked only from the rider's tap on
     * the switch, never from an automatic start. The answer doesn't matter: declining still turns it on, without a route.
     * riding: a recording is running or the scooter is moving. No prompt then; {@link #offersLocationOnScreen} asks afterwards.
     */
    static boolean asksLocationFirst(boolean phoneGps, boolean simulating, boolean riding) { return phoneGps && !simulating && !riding; }

    /** Whether the rider turning the GPS route switch on, with auto-record already on, should offer the same prompt. */
    static boolean asksLocationOnGpsOn(boolean autoRecord, boolean simulating, boolean riding) {
        return autoRecord && asksLocationFirst(true, simulating, riding);
    }

    /**
     * Whether Volt, on screen, should offer the location prompt because auto-record and GPS route are on without the
     * rider ever having answered it: both restore from a backup, Android's permission doesn't. Asked once, while
     * stopped, never from the service or because of motion. Either answer ends it; declining records without a route.
     */
    static boolean offersLocationOnScreen(boolean autoRecord, boolean phoneGps, boolean hasLocation, boolean asked,
            boolean simulating, boolean riding, boolean screenClear) {
        return autoRecord && phoneGps && !hasLocation && !asked && !simulating && !riding && screenClear;
    }

    /** Whether to open riding mode now. screenClear: resumed on screen, with nothing open that it would cover. */
    boolean shouldOpenRidingMode(boolean enabled, boolean moving, boolean screenClear) {
        return enabled && moving && !rideModeHeld && screenClear;
    }

    /**
     * Whether the activity may open riding mode over what it is showing. A focused window is what rules
     * out every dialog, sheet and system prompt, wherever it was opened. It says nothing about a text
     * field in the activity's own window, such as a Stats search, so textEntry covers those: a field
     * holding the focus is still taking text, with or without an on-screen keyboard.
     */
    static boolean screenClear(boolean resumed, boolean windowFocused, boolean ridingOpen, boolean onboarding,
            boolean inSetupOrEditor, boolean textEntry) {
        return resumed && windowFocused && !ridingOpen && !onboarding && !inSetupOrEditor && !textEntry;
    }
}
