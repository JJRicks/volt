package com.jjricks.volt;

/**
 * Whether a riding mode shows Home when Volt comes back on screen, because the scooter's connection was lost.
 * Android-free, so the JVM test can walk every path.
 *
 * The kinds of disconnect are told apart by what ended the link, never by timing:
 * - Volt's own Bluetooth pause is BleController.suspend() closing the link (about 10 s after leaving, with
 *   "Stay connected in background" off). It costs nothing if the rider is back within PAUSE_GRACE_MS of leaving.
 * - Every other fall from connected is a real loss and counts at once: the scooter switched off or out of
 *   range, Bluetooth off, a failed link, or the rider's own Disconnect.
 * - Process death shows as a different per-process token in the saved leave, and counts.
 *
 * Time away uses the phone's monotonic clock, so a clock change can't move it. Nothing here compares a
 * wall-clock time; a reboot starts a new process, which is the process-death rule.
 */
final class RidingLink {
    /** How long after leaving Volt's own pause still costs nothing. */
    static final long PAUSE_GRACE_MS = 120_000;

    private static final int UP = 0, PAUSED = 1, LOST = 2;

    /**
     * Every way a link has ended in this process. Kept for the process and not per service: after Volt's pause
     * the service can stop and a new one start, and that must not look like anything happened.
     */
    static final class Log {
        private final long process;
        private long losses, pauses;
        private boolean up, pausedDown;
        Log(long process) { this.process = process; }

        /** The link as it is now. ownPause says a fall right now is Volt's own pause. */
        void observe(boolean connected, boolean ownPause) {
            if (connected) pausedDown = false;
            else if (up) {
                if (ownPause) { pauses++; pausedDown = true; }
                else { losses++; pausedDown = false; }
            }
            up = connected;
        }

        Now now() { return new Now(process, losses, pauses, up ? UP : pausedDown ? PAUSED : LOST); }
    }

    /** One look at the Log. */
    static final class Now {
        private final long process, losses, pauses;
        private final int down;
        private Now(long process, long losses, long pauses, int down) { this.process = process; this.losses = losses; this.pauses = pauses; this.down = down; }
        boolean connected() { return down == UP; }
    }

    /** What the link looked like when Volt went off screen, and when. */
    static final class Leave {
        private final long process, losses, pauses, atMs;
        private final int down;
        /** elapsedMs is the phone's monotonic clock. */
        Leave(Now now, long elapsedMs) { this(now.process, now.losses, now.pauses, now.down, elapsedMs); }
        private Leave(long process, long losses, long pauses, int down, long atMs) { this.process = process; this.losses = losses; this.pauses = pauses; this.down = down; this.atMs = atMs; }

        /** For saved state, so the time away survives the activity being recreated. */
        long[] state() { return new long[]{process, losses, pauses, down, atMs}; }

        /** Reads state() back; null for anything else, which means on screen. */
        static Leave of(long[] state) {
            if (state == null || state.length != 5 || state[3] < UP || state[3] > LOST) return null;
            return new Leave(state[0], state[1], state[2], (int) state[3], state[4]);
        }
    }

    /**
     * True when riding mode should show Home. It must have seen a scooter connected, Volt must have left the
     * screen, and no recording may be running at this open. Then: the process died, or the link was really lost
     * (before the leave and still down, or during it, even if it has reconnected since), or Volt paused the link
     * and the rider has been away for the grace period or longer. A pause carried over from an earlier leave
     * counts as a pause of this one, whose own time away is what's measured.
     */
    static boolean lost(boolean linkSeen, Leave leave, Now now, boolean recording, long elapsedMs) {
        if (!linkSeen || leave == null || recording) return false;
        if (leave.process != now.process) return true;
        if (leave.down == LOST || now.losses != leave.losses) return true;
        if (leave.down != PAUSED && now.pauses == leave.pauses) return false;
        long away = elapsedMs - leave.atMs;
        return away < 0 || away >= PAUSE_GRACE_MS;
    }

    private RidingLink() {}
}
