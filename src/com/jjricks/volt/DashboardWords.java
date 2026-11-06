package com.jjricks.volt;

import java.util.List;

/**
 * Whole sentences the Home dashboard builds from a state, kept out of DashboardScreen so a plain JVM test can call them
 * (tests/l10n/DashboardFollowupsTest.java checks each against the base's expression). Rendered now: show, don't keep.
 */
final class DashboardWords {
    private DashboardWords() {}

    /**
     * The status line under the scooter's name when not connecting: mode only when ready (the drive mode word, as the
     * scooter shows it), lastSeen (a shown time, or null) only when not.
     */
    static String statusLine(boolean ready, String mode, String lastSeen) {
        if (ready) return mode != null ? T.dashboardStatusConnectedMode(mode) : T.dashboardStatusConnected();
        return lastSeen != null ? T.dashboardStatusDisconnectedSeen(lastSeen) : T.dashboardStatusDisconnected();
    }

    /**
     * statusLine's narrow-label ladder, full line first (Ui.statusRungs shows the first that fits): the drive mode
     * drops before the word Connected does, and the words last seen before the time does.
     */
    static String[] statusRungs(boolean ready, String mode, String lastSeen) {
        String full = statusLine(ready, mode, lastSeen);
        if (ready) return mode != null ? new String[]{full, T.dashboardStatusConnected()} : new String[]{full};
        return lastSeen != null
                ? new String[]{full, T.dashboardStatusDisconnectedSeenShort(lastSeen), T.dashboardStatusDisconnected(),
                        T.dashboardStatusDisconnectedShort()}
                : new String[]{full, T.dashboardStatusDisconnectedShort()};
    }

    /**
     * The status line while connecting: the connection state's word, two spaces and U+FFFC, where DashboardScreen
     * draws its spinner. The mark stays out of the catalog: Saira has no glyph for it (GlyphCoverageTest).
     */
    static String connectingText() {
        return T.bleStatusConnecting() + new String(new char[]{32, 32, 0xFFFC});
    }

    /** The status line's spoken form; lastSeen (or null) only when neither ready nor connecting. */
    static String statusSpoken(boolean ready, boolean connecting, String mode, String lastSeen) {
        if (ready) return mode != null ? T.dashboardStatusSpokenConnectedMode(mode) : T.dashboardStatusSpokenConnected();
        if (connecting) return T.dashboardStatusSpokenConnecting();
        return lastSeen != null ? T.dashboardStatusSpokenDisconnectedSeen(lastSeen) : T.dashboardStatusSpokenDisconnected();
    }

    /** A chart window's long name: "10 minutes", "1 hour", "90 days". */
    static String windowName(long ms) {
        long count = ms / (ms < 3600000 ? 60000 : ms < 86400000 ? 3600000 : 86400000);
        if (ms < 3600000) return T.dashboardWindowNameMinutes(count, count);
        return ms < 86400000 ? T.dashboardWindowNameHours(count, count) : T.dashboardWindowNameDays(count, count);
    }

    /** The recording button's spoken text: parts are what the recorder has measured so far. */
    static String recordingSpoken(String clock, List<String> parts) {
        return parts.isEmpty() ? T.dashboardRecordingSpoken(clock)
                : T.dashboardRecordingSpokenParts(clock, String.join(T.dashboardRecordingSpokenSeparator(), parts));
    }
}
