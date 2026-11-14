package com.jjricks.volt;

/**
 * Hub 9: a finished ride's Avg speed, end to end in plain Java, on both pages that show it: Clean Home's Just back tile
 * and the ride's page (its Avg speed tile) and compare sheet on Rides. RideAverages.speedKmh gives the value for both
 * (the saved average, else the measured distance over the ride's time); RideText.speed formats it as Rides formats every
 * speed, in the "units" preference. None reads as a dash on Just back and as "Not measured" on Rides.
 *
 * JustBackView and RidesScreen need Android, so the two text expressions below are their head expressions copied, and
 * wiring() reads their sources to keep every place that shows the average on RideAverages.
 */
public final class JustBackSpeedTest {
    private static int checks;
    /** The rig's seeded ride: 6.21 mi in 20:00, with no speed readings saved. */
    private static final double SEEDED_KM = 9.994;
    private static final long TWENTY_MINUTES = 20 * 60_000L;

    private JustBackSpeedTest() {}

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    /** The tile's text, as JustBackView shows it: the formatted speed, or a dash when there is none. */
    private static String tile(Double stored, Double moving, Double distanceKm, long movingMs, long elapsedMs, String units) {
        Double kmh = RideAverages.speedKmh(stored, moving, distanceKm, movingMs, elapsedMs);
        return kmh == null ? "—" : RideText.speed(kmh, units).toString();
    }

    /** The Avg speed text on Rides, its tile and compare row: RidesScreen.speed(RideAverages.speedKmh(ride)). */
    private static String rides(Double stored, Double moving, Double distanceKm, long movingMs, long elapsedMs, String units) {
        Double kmh = RideAverages.speedKmh(stored, moving, distanceKm, movingMs, elapsedMs);
        return kmh == null ? T.ridesNotMeasured() : RideText.speed(kmh, units).toString();
    }

    /** Both pages' text for one ride; they show the same speed, and only their "none" differs. */
    private static void both(String expected, Double stored, Double moving, Double distanceKm, long movingMs, long elapsedMs, String units, String what) {
        eq(expected == null ? "—" : expected, tile(stored, moving, distanceKm, movingMs, elapsedMs, units), "Just back: " + what);
        eq(expected == null ? "Not measured" : expected, rides(stored, moving, distanceKm, movingMs, elapsedMs, units), "Rides: " + what);
    }

    /** Every place JustBackView and RidesScreen show a ride's average speed goes through RideAverages. */
    private static void wiring() {
        String view = L10nFiles.read(new java.io.File(L10nFiles.root(), "src/com/jjricks/volt/JustBackView.java"));
        String rides = L10nFiles.read(new java.io.File(L10nFiles.root(), "src/com/jjricks/volt/RidesScreen.java"));
        eq(true, view.contains("Double average = RideAverages.speedKmh(s);"), "Just back's tile reads RideAverages");
        eq(true, rides.contains("Double average = RideAverages.speedKmh(ride);"), "the ride's page reads RideAverages");
        eq(true, rides.contains("T.ridesTileAvgSpeed(), speed(average)"), "its Avg speed tile shows that value");
        eq(true, rides.contains("if (average != null) tileReadings(speedParts(average));"), "and sizes its tiles for it");
        eq(true, rides.contains("T.ridesTileAvgSpeed(), speed(RideAverages.speedKmh(a)), speed(RideAverages.speedKmh(b))"), "the compare sheet reads RideAverages");
        // Nothing on either page shows the saved field directly any more.
        eq(false, rides.contains("speed(ride.averageSpeedKmh)") || rides.contains("speedParts(ride.averageSpeedKmh)")
                || rides.contains("speed(a.averageSpeedKmh)") || rides.contains("speed(b.averageSpeedKmh)"), "no saved field shown as is on Rides");
        eq(false, view.contains("JustBackFit.averageSpeedKmh"), "no second copy of the rule on Just back");
    }

    public static void main(String[] args) {
        // A missing average is derived from distance and time, in each unit, the same on both pages.
        both("18.6 mph", null, null, SEEDED_KM, 0, TWENTY_MINUTES, "mi", "seeded ride in mph");
        both("30.0 km/h", null, null, SEEDED_KM, 0, TWENTY_MINUTES, "km", "seeded ride in km/h");
        both("18.6 mph / 30.0 km/h", null, null, SEEDED_KM, 0, TWENTY_MINUTES, "dual", "seeded ride in both");
        // Over the moving time when the ride has one: 10 km in 15 minutes moving.
        both("24.9 mph", null, null, 10.0, 15 * 60_000L, TWENTY_MINUTES, "mi", "over the moving time");
        // A stored average is used as saved, then a stored moving average.
        both("15.5 mph", 24.94, null, SEEDED_KM, 0, TWENTY_MINUTES, "mi", "stored average in mph");
        both("24.9 km/h", 24.94, null, SEEDED_KM, 0, TWENTY_MINUTES, "km", "stored average in km/h");
        both("26.0 km/h", null, 26.0, SEEDED_KM, 0, TWENTY_MINUTES, "km", "stored moving average");
        // A zero duration (or no distance) has nothing to derive from: a dash on Just back, "Not measured" on Rides.
        both(null, null, null, SEEDED_KM, 0, 0, "mi", "zero duration");
        both(null, null, null, null, 0, TWENTY_MINUTES, "km", "no distance");
        both(null, null, null, 0.0, 0, TWENTY_MINUTES, "dual", "zero distance");
        wiring();
        System.out.println("JustBackSpeed: " + checks + " checks passed");
    }
}
