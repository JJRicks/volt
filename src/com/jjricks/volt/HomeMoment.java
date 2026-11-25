package com.jjricks.volt;

/**
 * What Clean Home's top area shows (hub layout plan 1.7): the battery card while parked, the charging view by
 * itself while charging (Joel 2), the ride being recorded while one records (the Ride clock; Ollie's mock-up, Nov 24 2026), or
 * the last ride for about an hour after it ends until the owner closes it. Pure Java; only Clean uses it. Widgets keeps
 * today's charging takeover and nothing else.
 */
final class HomeMoment {
    /** The moments, also the index of each one's view in Clean's top area (CleanHomeScreen.moments). */
    static final int PARKED = 0, CHARGING = 1, JUST_BACK = 2, RECORDING = 3;
    /** Show charging automatically, while the scooter reports charging (app_settings, Boolean, App preferences). Absent means on. */
    static final String CHARGING_KEY = "home_moment_charging";
    /** Show the last ride after riding (app_settings, Boolean, App preferences). Absent means on. */
    static final String RIDE_KEY = "home_moment_ride";
    /**
     * Show the ride being recorded while a ride records (app_settings, Boolean, App preferences). Absent means on.
     */
    static final String RECORDING_KEY = "home_moment_recording";
    /** The ride id whose Just back card the owner closed (app_settings, String, Device). */
    static final String DISMISSED_KEY = "home_just_back_dismissed";
    /** How long after a ride ends Just back shows. */
    static final long JUST_BACK_MS = 60L * 60L * 1000L;

    private HomeMoment() {}

    /** A moment switch: only a stored false is off. Absent, null or anything else reads as on. */
    static boolean on(Object stored) { return !Boolean.FALSE.equals(stored); }

    /**
     * The moment to show. Charging wins while charging is reported (the same predicate the charging takeover uses;
     * stale readings never count). Otherwise Just back while the newest finished ride ended under an hour ago, it
     * was not closed, and no ride is being recorded; a ride whose end is in the future (clock skew) counts as just
     * ended. Otherwise parked.
     *
     * @param lastRideEndWall the ride's shown end, wall-clock ms (RideStore.Summary.shownEndWallMs)
     */
    static int choose(boolean chargingOn, boolean charging, boolean rideOn, boolean recording,
                      String lastRideId, long lastRideEndWall, String dismissedId, long now) {
        return choose(chargingOn, charging, false, rideOn, recording, lastRideId, lastRideEndWall, dismissedId, now);
    }

    /**
     * As above, with the recording moment's switch (recordingOn): while a ride records and that switch is on, the
     * recording shows, after charging (which still wins: a ride recorded while the scooter charges is the charge's
     * moment) and before Just back (which never shows while recording). With the switch off a recording shows the
     * battery card, as before the recording moment.
     */
    static int choose(boolean chargingOn, boolean charging, boolean recordingOn, boolean rideOn, boolean recording,
                      String lastRideId, long lastRideEndWall, String dismissedId, long now) {
        if (chargingOn && charging) return CHARGING;
        if (recordingOn && recording) return RECORDING;
        if (rideOn && !recording && lastRideId != null && !lastRideId.isEmpty() && !lastRideId.equals(dismissedId)
                && lastRideEndWall > 0 && Math.max(0, now - lastRideEndWall) < JUST_BACK_MS) return JUST_BACK;
        return PARKED;
    }

    /** How long the recording moment may stay after its ride stopped while Clean reads the ride just saved. */
    static final long HANDOFF_MS = 5_000L;

    /**
     * Whether the recording moment stays, a moment longer, after its ride stopped: a straight change to Just back for the
     * ride just saved, never the battery card (or an older ride's Just back) in between while Clean is still reading the
     * saved rides (a stop leaves the ride uncached until it is read again). True while the moment shows the ride that
     * ended (endedRide, null for none), the ride Clean found (foundRide) is not yet that one, the next moment is not
     * charging, and under {@link #HANDOFF_MS} has passed since the stop itself (sinceStopMs: wall-clock ms since
     * RideStore.lastStopWallMs, -1 for none), not since Clean next looked: back from the background long after a stop,
     * nothing is held.
     */
    static boolean holdRecording(int shown, int next, String endedRide, String foundRide, long sinceStopMs) {
        return shown == RECORDING && next != CHARGING && next != RECORDING && endedRide != null && !endedRide.equals(foundRide)
                && sinceStopMs >= 0 && sinceStopMs < HANDOFF_MS;
    }

    /** How often Clean looks for a newer finished ride while nothing else says one may have ended. */
    static final long RIDES_RECHECK_MS = 30_000L;

    /**
     * Whether Clean looks at the saved rides again: on its first refresh (lastLook &lt; 0), when recording started or
     * stopped since it last looked, or once {@link #RIDES_RECHECK_MS} passed (a ride saved by another path, such as
     * an automatic stop while the screen stayed open). Times are elapsed-realtime ms.
     */
    static boolean lookAgain(long lastLook, long now, boolean recordingChanged) {
        return lastLook < 0 || recordingChanged || now - lastLook >= RIDES_RECHECK_MS || now < lastLook;
    }

    /** Whole minutes since the ride ended, for "Ride ended N minutes ago" (0 for a future end). */
    static long minutesAgo(long lastRideEndWall, long now) {
        return Math.max(0, now - lastRideEndWall) / 60_000L;
    }
}
