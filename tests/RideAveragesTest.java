package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RideAverages: a finished ride's average speed, the one rule Clean Home's Just back card and the ride's page and compare
 * sheet on Rides share. The saved average first, then the saved moving average, then the measured distance over the
 * moving time, else the shown time; none without a positive, finite distance or a positive time. Moved here from
 * JustBackFitTest with every check it had, plus saved rides read back from a RideStore journal.
 */
public final class RideAveragesTest {
    private static int checks;
    private static final long WALL = 1791806400000L;

    public static void main(String[] args) throws Exception {
        order();
        derived();
        none();
        savedRides();
        System.out.println("RideAverages: " + checks + " checks passed");
    }

    private static void order() {
        // A stored average is used as it is, ahead of the moving average and of anything derived.
        require(RideAverages.speedKmh(24.0, 26.0, 9.994, 0, 20 * 60_000L) == 24.0, "a stored average comes first");
        require(RideAverages.speedKmh(null, 26.0, 9.994, 0, 20 * 60_000L) == 26.0, "then the stored moving average");
        require(RideAverages.speedKmh(0.0, null, 5.0, 0, 60_000L) == 0.0, "a stored zero is a real average");
        require(RideAverages.speedKmh(Double.NaN, Double.POSITIVE_INFINITY, 10.0, 0, 30 * 60_000L) == 20.0, "unusable stored values are skipped");
        require(RideAverages.speedKmh(-1.0, null, 10.0, 0, 30 * 60_000L) == 20.0, "a negative stored average is unusable");
        require(RideAverages.speedKmh(Double.NaN, 20.0, 10.0, 0, 60_000L) == 20.0, "an unusable average falls back to the moving average");
    }

    private static void derived() {
        // A missing average is derived: the rig's seeded ride, 6.21 mi (9.994 km) in 20:00 with no speed readings saved,
        // is about 18.6 mph (30.0 km/h), not a dash or "Not measured".
        Double seeded = RideAverages.speedKmh(null, null, 9.994, 0, 20 * 60_000L);
        require(seeded != null && Math.abs(seeded - 29.982) < .01, "distance over the shown time: " + seeded);
        require(Math.abs(seeded / 1.609344 - 18.63) < .01, "about 18.6 mph");
        Double moving = RideAverages.speedKmh(null, null, 10.0, 15 * 60_000L, 20 * 60_000L);
        require(moving != null && Math.abs(moving - 40.0) < 1e-9, "over the moving time when the ride has one");
    }

    private static void none() {
        // Nothing measured to derive from: none, which Just back shows as a dash and Rides as "Not measured".
        require(RideAverages.speedKmh(null, null, 9.994, 0, 0) == null, "zero duration: none");
        require(RideAverages.speedKmh(null, null, 9.994, 0, -5) == null, "negative duration: none");
        require(RideAverages.speedKmh(null, null, null, 0, 60_000L) == null, "no distance (odometer reset or unread): none");
        require(RideAverages.speedKmh(null, null, 0.0, 0, 60_000L) == null, "zero distance: none");
        require(RideAverages.speedKmh(null, null, -1.0, 0, 60_000L) == null, "negative distance: none");
        require(RideAverages.speedKmh(null, null, Double.NaN, 0, 60_000L) == null, "NaN distance: none");
        require(RideAverages.speedKmh(null, null, Double.POSITIVE_INFINITY, 0, 60_000L) == null, "infinite distance: none");
    }

    /** Saved rides through RideStore: the Summary overload reads the distance and time the pages show. */
    private static void savedRides() throws Exception {
        // Like the rig's seeded ride: odometer readings 20 s apart and no speed readings, so nothing saved an average.
        RideStore.Summary seeded = ride(new double[]{0, 9.994}, false);
        require(seeded.averageSpeedKmh == null && seeded.movingAverageSpeedKmh == null, "the journal saves no average (exports keep none)");
        require(seeded.distanceKm != null && Math.abs(seeded.distanceKm - 9.994) < 1e-9, "the distance the pages show: " + seeded.distanceKm);
        require(seeded.shownElapsedMs == 20 * 60_000L, "the Ride time the pages show: " + seeded.shownElapsedMs);
        Double kmh = RideAverages.speedKmh(seeded);
        require(kmh != null && Math.abs(kmh - seeded.distanceKm * 3_600_000d / seeded.shownElapsedMs) < 1e-9, "the shown distance over the shown time: " + kmh);
        require(Math.abs(kmh / RideStore.KM_PER_MILE - 18.63) < .01, "about 18.6 mph");
        require(seeded.averageSpeedKmh == null, "reading the average changes nothing saved");
        // An odometer that went backwards: the pages show no distance, so there is no average to show either.
        RideStore.Summary reset = ride(new double[]{5, 1}, false);
        require(reset.odometerReset && reset.distanceKm == null, "an odometer reset has no distance");
        require(RideAverages.speedKmh(reset) == null, "and no average");
        // Speed readings close together: the saved average comes first, as saved.
        RideStore.Summary measured = ride(new double[]{0, 10}, true);
        require(measured.averageSpeedKmh != null, "a ride with speed readings saves an average");
        require(RideAverages.speedKmh(measured).equals(measured.averageSpeedKmh), "the saved average is shown as saved");
    }

    /** A 20-minute ride with the odometer moving from odo[0] to odo[1] in 20 s steps; speed readings every second when speed. */
    private static RideStore.Summary ride(double[] odo, boolean speed) throws Exception {
        File dir = Files.createTempDirectory("ride-averages").toFile();
        RideStore store = new RideStore(dir);
        store.start("scooter", "Averages", WALL, 0);
        long step = speed ? 1000 : 20_000, end = 20 * 60_000L;
        for (long t = 0; t <= end; t += step) {
            Map<String, RideStore.Sample> m = new LinkedHashMap<>();
            m.put("odometer_km", new RideStore.Sample(odo[0] + (odo[1] - odo[0]) * t / end, t));
            m.put("battery_percent", new RideStore.Sample(80, t));
            if (speed) m.put("speed_kmh", new RideStore.Sample(30, t));
            store.sample(WALL + t, t, m);
        }
        return store.stop(WALL + end, end);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
