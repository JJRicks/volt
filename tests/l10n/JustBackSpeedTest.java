package com.jjricks.volt;

/**
 * Hub 9: Clean Home's Just back Avg speed tile, end to end in plain Java. JustBackFit.averageSpeedKmh gives the value
 * (the saved average, else the measured distance over the ride's time; null shows as a dash), and RideText.speed
 * formats it as Rides formats every speed, in the "units" preference.
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
        Double kmh = JustBackFit.averageSpeedKmh(stored, moving, distanceKm, movingMs, elapsedMs);
        return kmh == null ? "—" : RideText.speed(kmh, units).toString();
    }

    public static void main(String[] args) {
        // A missing average is derived from distance and time, in each unit.
        eq("18.6 mph", tile(null, null, SEEDED_KM, 0, TWENTY_MINUTES, "mi"), "seeded ride in mph");
        eq("30.0 km/h", tile(null, null, SEEDED_KM, 0, TWENTY_MINUTES, "km"), "seeded ride in km/h");
        eq("18.6 mph / 30.0 km/h", tile(null, null, SEEDED_KM, 0, TWENTY_MINUTES, "dual"), "seeded ride in both");
        // A stored average is used as saved.
        eq("15.5 mph", tile(24.94, null, SEEDED_KM, 0, TWENTY_MINUTES, "mi"), "stored average in mph");
        eq("24.9 km/h", tile(24.94, null, SEEDED_KM, 0, TWENTY_MINUTES, "km"), "stored average in km/h");
        // A zero duration (or no distance) has nothing to derive from: a dash.
        eq("—", tile(null, null, SEEDED_KM, 0, 0, "mi"), "zero duration");
        eq("—", tile(null, null, null, 0, TWENTY_MINUTES, "km"), "no distance");
        System.out.println("JustBackSpeed: " + checks + " checks passed");
    }
}
