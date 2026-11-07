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

    /** What the pill shows: the plain connection pill, the red recording pill, or the red pill with a link problem. */
    enum Look { STATUS, RECORDING, RECORDING_PROBLEM }

    /** recordingMs is the active ride recording's elapsed time, or null when none is recording. */
    static Look look(Long recordingMs, RidingFocus.Link link) {
        if (recordingMs == null) return Look.STATUS;
        return link == RidingFocus.Link.CONNECTED ? Look.RECORDING : Look.RECORDING_PROBLEM;
    }

    /**
     * The recording pill's text, full first, for the narrow-label ladder: the time stays while there is
     * room, and a link problem keeps its words to the last rung (the red pill and dot still say recording).
     */
    static String[] rungs(long recordingMs, RidingFocus.Link link) {
        String clock = RidingFocus.clockText(recordingMs);
        if (link == RidingFocus.Link.CONNECTED) return new String[]{T.ridingStatusRecording(clock), clock};
        if (link == RidingFocus.Link.RECONNECTING) return new String[]{T.ridingStatusRecordingReconnecting(clock),
                T.ridingStatusRecordingReconnectingShort(clock), T.ridingStatusReconnecting()};
        return new String[]{T.ridingStatusRecordingNotConnected(clock), T.ridingStatusRecordingNotConnectedShort(clock),
                T.ridingStatusNotConnectedSeenCompact()};
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
        return look == Look.RECORDING && motionEnabled;
    }
}
