package com.jjricks.volt;

/**
 * What Clean Home's top area shows (hub layout plan 1.7): the battery card while parked, the charging view by
 * itself while charging (Joel 2), or the last ride for about an hour after it ends until the owner closes it.
 * Pure Java; only Clean uses it. Widgets keeps today's charging takeover and nothing else.
 */
final class HomeMoment {
    static final int PARKED = 0, CHARGING = 1, JUST_BACK = 2;
    /** Show charging while plugged in (app_settings, Boolean, App preferences). Absent means on. */
    static final String CHARGING_KEY = "home_moment_charging";
    /** Show the last ride after riding (app_settings, Boolean, App preferences). Absent means on. */
    static final String RIDE_KEY = "home_moment_ride";
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
        if (chargingOn && charging) return CHARGING;
        if (rideOn && !recording && lastRideId != null && !lastRideId.isEmpty() && !lastRideId.equals(dismissedId)
                && lastRideEndWall > 0 && Math.max(0, now - lastRideEndWall) < JUST_BACK_MS) return JUST_BACK;
        return PARKED;
    }

    /** Whole minutes since the ride ended, for "Ride saved N minutes ago" (0 for a future end). */
    static long minutesAgo(long lastRideEndWall, long now) {
        return Math.max(0, now - lastRideEndWall) / 60_000L;
    }
}
