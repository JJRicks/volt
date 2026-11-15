package com.jjricks.volt;

/**
 * Decides when Volt starts a ride recording (auto-record) or opens riding mode (auto ride mode) on its
 * own because the scooter started moving. Pure Java and in-process only: the caller feeds it the
 * connection, the switches and a monotonic clock, and it never reads a clock or a sensor itself.
 *
 * Both are off by default and share one gate: Volt is connected to the scooter, the switch is on, and
 * a fresh speed reading above zero arrived (see {@link #moving}). A stale reading never counts.
 * Auto-record starts while Volt is in the background only with its own switch, also off by default
 * (see {@link #recordsNow}): Android doesn't let Volt turn on location from the background, so such a
 * ride has no GPS route.
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
    /**
     * Lets auto-record also start while Volt is in the background (owner's answer 18 on #383). Absent means off, so
     * an install or a backup from before the switch starts rides only while Volt is on screen.
     */
    static final String AUTO_RECORD_BACKGROUND_KEY = "auto_record_background";
    /**
     * Volt 1.13.2's one-time What's new note about background starts (release/1.13 only; this branch never writes it): a
     * String in app_settings, NOTICE_DUE or NOTICE_DONE, decided at that version's first start. An installation updated
     * from 1.13.2 still holds it; HomeStyle.whatsNewBackgroundNotice reads it so an owner who already closed that note is
     * not told again. It belongs to that installation, so BackupRegistry leaves it out.
     */
    static final String BACKGROUND_NOTICE_KEY = "auto_record_background_notice", NOTICE_DUE = "due", NOTICE_DONE = "done";
    /** The Rides GPS route switch, on by default. */
    static final String PHONE_GPS_KEY = "phone_gps";
    /** Set while the rider's last answer to auto-record's location offer stands as a decline. A grant leaves it unset, so one that later ends can be offered again. Belongs to this phone's permission, so BackupRegistry leaves it out. */
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

    /**
     * Whether auto-record is on for where Volt is now: on screen whenever its switch is on, in the background only
     * when the background switch is on too. The result is {@link #shouldRecord}'s enabled. A ride that was held
     * back in the background starts once Volt comes on screen, if the scooter is still moving.
     *
     * @param onScreen Volt's activity is resumed (ScooterService's visible)
     */
    static boolean recordsNow(boolean autoRecord, boolean backgroundAllowed, boolean onScreen) {
        return autoRecord && (onScreen || backgroundAllowed);
    }

    /**
     * How Recording settings draws Start recording in the background: on only while Record rides automatically is on,
     * since it counts only then. While that is off the switch is greyed and drawn off; the stored value is kept.
     */
    static boolean backgroundSwitchDrawnOn(boolean autoRecord, boolean backgroundAllowed) { return autoRecord && backgroundAllowed; }

    /** Whether to start a recording now. Refusal reasons are inputs here so that the automatic start stays silent. */
    boolean shouldRecord(boolean enabled, boolean moving, boolean recording, boolean chargeRecording, boolean simulating,
            boolean phoneSaving, boolean canStart) {
        return enabled && moving && !recordHeld && !recording && !chargeRecording && !simulating && !phoneSaving && canStart;
    }

    /**
     * Whether turning auto-record on should first offer the ride location prompt. Asked only from the rider's tap on
     * the switch, never from an automatic start. Either answer turns it on; declining records without a route.
     * riding: a recording is running or the scooter is moving. No prompt then; {@link #offersLocationOnScreen} asks afterwards.
     */
    static boolean asksLocationFirst(boolean phoneGps, boolean simulating, boolean riding) { return phoneGps && !simulating && !riding; }

    /** Whether the rider turning the GPS route switch on, with auto-record already on, should offer the same prompt. */
    static boolean asksLocationOnGpsOn(boolean autoRecord, boolean simulating, boolean riding) {
        return autoRecord && asksLocationFirst(true, simulating, riding);
    }

    /**
     * Whether Volt, on screen, should offer the location prompt because auto-record and GPS route are on and the
     * permission is missing: both switches restore from a backup, Android's permission doesn't, and a one-time grant
     * ends by itself. Asked while stopped, never from the service or because of motion. A decline ends it
     * ({@link #declineNote}); declining records without a route.
     */
    static boolean offersLocationOnScreen(boolean autoRecord, boolean phoneGps, boolean hasLocation, boolean declined,
            boolean simulating, boolean riding, boolean screenClear) {
        return autoRecord && phoneGps && !hasLocation && !declined && !simulating && !riding && screenClear;
    }

    /**
     * The decline note after a request or a look at the permission. Holding the permission clears it, whichever way it
     * was granted. Without it, a rider's answer sets it, and Android refusing at once after "don't ask again" arrives as
     * that same denied answer. A request Android cancelled with no answer leaves the note as it was.
     *
     * @param answered a permission result came back for this request
     */
    static boolean declineNote(boolean note, boolean answered, boolean hasLocation) {
        return !hasLocation && (note || answered);
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
