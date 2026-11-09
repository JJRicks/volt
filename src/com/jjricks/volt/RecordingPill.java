package com.jjricks.volt;

/**
 * Riding mode's status pill while a ride records (owner, Nov 6): the connection pill turns into a red
 * recording pill with a recording dot and the recording's own timer, kept apart from the ride-time
 * reading. Recording starts and stops only on Home's Record ride card and the Rides page; the pill takes
 * no taps. A lost link still shows inside the red pill, so the rider sees both the recording and the
 * problem. Android-free so plain JVM tests run it.
 */
final class RecordingPill {
    private RecordingPill() {}

    /**
     * What the pill shows: the plain connection pill, the red recording pill, the red pill with a link
     * problem, or the brief "Ride saved" confirmation after a recording stops.
     */
    enum Look { STATUS, RECORDING, RECORDING_PROBLEM, SAVED }

    /** How long "Ride saved" shows after the rider's stop (the ride-display plan's 3 s, plus a second to read it). */
    static final long SAVED_MS = 4000;
    /** Longer after an automatic stop: nobody tapped anything, so the rider may glance at the phone later. */
    static final long SAVED_AUTO_MS = 10_000;

    /** recordingMs is the active ride recording's elapsed time, or null when none is recording. */
    static Look look(Long recordingMs, RidingFocus.Link link) {
        if (recordingMs == null) return Look.STATUS;
        return link == RidingFocus.Link.CONNECTED ? Look.RECORDING : Look.RECORDING_PROBLEM;
    }

    /**
     * As above, and with no recording, SAVED while the last stop is recent. savedAgoMs is the time since
     * the last stop saved a ride (wall clock), or null when none did; a negative age (the clock moved back)
     * never shows it.
     */
    static Look look(Long recordingMs, RidingFocus.Link link, Long savedAgoMs, boolean automatic) {
        if (recordingMs != null) return look(recordingMs, link);
        return savedAgoMs != null && savedAgoMs >= 0 && savedAgoMs < (automatic ? SAVED_AUTO_MS : SAVED_MS) ? Look.SAVED : Look.STATUS;
    }

    /** The saved confirmation's rungs, full first. */
    static String[] savedRungs(boolean automatic) {
        return automatic ? new String[]{T.ridingStatusRideSavedAuto(), T.ridingStatusRideSavedAutoShort(), T.ridingStatusRideSavedShort()}
                : new String[]{T.ridingStatusRideSaved(), T.ridingStatusRideSavedShort()};
    }

    /** Spoken for the saved confirmation, then the connection as the status pill would say it (null: nothing to add). */
    static String savedSpoken(boolean automatic, String connection) {
        String saved = automatic ? T.ridingStatusRideSavedAuto() : T.ridingStatusRideSaved();
        return connection == null || connection.isEmpty() ? saved : T.ridingReadingSpoken(saved, connection);
    }

    /**
     * The recording's elapsed time, safe across a reboot: the larger of the recorded elapsed time and the
     * wall-clock time since the ride's start, as the Rides page shows it. A recovered recording's start
     * belongs to the previous boot's monotonic clock, so this never subtracts monotonic times.
     */
    static long elapsedMs(long recordedElapsedMs, long startWallMs, long wallNowMs) {
        return Math.max(0, Math.max(recordedElapsedMs, wallNowMs - startWallMs));
    }

    /** How long each half of an alternating pill shows: the recording's time, then the link problem. */
    static final long ALTERNATE_MS = 3000;

    /**
     * The recording pill's text, full first, for the narrow-label ladder. Connected: "REC 12:48", then the
     * time alone. With a link problem the pill names both while the short line ("12:48 · Not connected")
     * fits. Where it doesn't (a phone, with the night disc beside the pill), the pill alternates between the
     * time and the problem every ALTERNATE_MS; with the system's animations off it holds the problem's words,
     * the more urgent half (the red pill and dot still say recording, and TalkBack hears both).
     *
     * @param roomForBoth whether the short line with both fits the pill at its smallest readable size
     */
    static String[] rungs(long recordingMs, RidingFocus.Link link, boolean roomForBoth, long nowMs, boolean motionEnabled) {
        String clock = RidingFocus.clockText(recordingMs);
        if (link == RidingFocus.Link.CONNECTED) return new String[]{T.ridingStatusRecording(clock), clock};
        String[] both = both(clock, link);
        if (roomForBoth) return both;
        if (motionEnabled && timeHalf(nowMs)) return new String[]{T.ridingStatusRecording(clock), clock};
        return new String[]{both[2]};
    }

    /** With a link problem: the full line, the short line with both, and the problem's words alone. */
    static String[] both(String clock, RidingFocus.Link link) {
        if (link == RidingFocus.Link.RECONNECTING) return new String[]{T.ridingStatusRecordingReconnecting(clock),
                T.ridingStatusRecordingReconnectingShort(clock), T.ridingStatusReconnecting()};
        return new String[]{T.ridingStatusRecordingNotConnected(clock), T.ridingStatusRecordingNotConnectedShort(clock),
                T.ridingStatusNotConnectedSeenCompact()};
    }

    /** Whether an alternating pill shows the time now (the first half of each cycle). */
    static boolean timeHalf(long nowMs) {
        return Math.floorMod(nowMs / ALTERNATE_MS, 2L) == 0;
    }

    /**
     * Spoken for the pill: the time in words (seconds in the first minute, then whole minutes, so TalkBack
     * never reads "12:48" as a clock time), then any link problem. lastSeen is the last reading's age as
     * the pill's clock text ("0:42"), or null when unknown.
     */
    static String spoken(long recordingMs, RidingFocus.Link link, String lastSeen) {
        long seconds = Math.max(0, recordingMs) / 1000;
        String time;
        if (seconds < 60) time = T.ridingStatusRecordingSpokenSeconds(seconds, seconds);
        else {
            long minutes = seconds / 60;
            time = T.ridingStatusRecordingSpokenMinutes(minutes, minutes);
        }
        if (link == RidingFocus.Link.CONNECTED) return time;
        String problem = link == RidingFocus.Link.RECONNECTING ? T.ridingStatusReconnecting()
                : lastSeen != null ? T.ridingStatusNotConnectedSeen(lastSeen) : T.ridingStatusNotConnectedSeenCompact();
        return T.ridingReadingSpoken(time, problem);
    }

    /** The dot pulses gently only while recording with a good link, and never with the system's animations off. */
    static boolean pulses(Look look, boolean motionEnabled) {
        // Only RECORDING pulses; SAVED and the others hold still.
        return look == Look.RECORDING && motionEnabled;
    }
}
