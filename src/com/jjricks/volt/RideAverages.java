package com.jjricks.volt;

/**
 * A finished ride's average speed as every page shows it: Clean Home's Just back card (JustBackView) and the ride's
 * own page and the compare sheet on Rides (RidesScreen). One rule, so the pages agree with each other and with the
 * distance and time they show beside it. Pure Java.
 *
 * <p>Display only: exports (CSV, GPX, JSON), backups and RideStore's saved fields keep what was measured, so a ride
 * that saved no average still has none there.</p>
 */
final class RideAverages {
    private RideAverages() {}

    /**
     * The ride's average speed in km/h, or null ("Not measured" on Rides, a dash on Just back) when it has nothing
     * measured to give one. The saved average (RideStore's, over the time speed was reported) comes first, then the
     * saved moving average. A ride that stored neither (no speed readings close enough together to integrate: a
     * GPS-only ride, or the rig's seeded ride with samples 20 s apart) still has a measured distance (the odometer's
     * change, null when it reset or was never read) and a recorded time, so its average is that distance over its
     * moving time, else over its shown time: the same distance and time the pages show. Only measured values go in;
     * nothing is filled in for a reading that is missing, so a ride without a positive, finite distance or a positive
     * time has none (CONTRIBUTING: missing telemetry stays unavailable).
     */
    static Double speedKmh(Double stored, Double movingStored, Double distanceKm, long movingMs, long shownElapsedMs) {
        if (usable(stored)) return stored;
        if (usable(movingStored)) return movingStored;
        if (distanceKm == null || !Double.isFinite(distanceKm) || distanceKm <= 0) return null;
        long time = movingMs > 0 ? movingMs : shownElapsedMs;
        return time > 0 ? distanceKm * 3_600_000d / time : null;
    }

    /** A saved ride's average speed: {@link #speedKmh(Double, Double, Double, long, long)} with the distance and Ride
     * time its pages show (Summary.distanceKm, Summary.shownElapsedMs). */
    static Double speedKmh(RideStore.Summary ride) {
        return speedKmh(ride.averageSpeedKmh, ride.movingAverageSpeedKmh, ride.distanceKm, ride.movingMs, ride.shownElapsedMs);
    }

    private static boolean usable(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }
}
