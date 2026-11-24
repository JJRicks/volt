package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * The ride graph's distance line in English (owner, Nov 23 2026): "Distance" when the scooter's odometer draws it,
 * "GPS distance" when the phone's track does, last after GPS accuracy, off until chosen, in the user's unit.
 */
public final class RideDistanceTextTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        List<RideChartData.Series> none = RideChartData.forRide(Collections.<RideStore.Point>emptyList(), null, true, false, false);
        check(none.size() == RideChartData.KEYS.length + 2, "the graph offers its readings, GPS accuracy, then distance: " + none.size());
        check(none.get(RideChartData.KEYS.length).key.equals(GpsAccuracy.KEY), "GPS accuracy keeps its place");
        RideChartData.Series last = none.get(none.size() - 1);
        check(last.key.equals(RideChartData.DISTANCE_KEY), "distance is last: " + last.key);
        check(!last.available() && last.displayLabel().equals("GPS distance"), "no odometer: the GPS name, with no data: " + last.displayLabel());
        check(!RideChartData.defaults().contains(RideChartData.DISTANCE_KEY), "off until chosen");

        File dir = Files.createTempDirectory("ride-distance-text").toFile();
        RideStore store = new RideStore(dir);
        String id = store.start("fixture", "Odometer", 1000000, 1000);
        for (int i = 0; i < 30; i++) {
            long mono = 1000 + i * 1000L;
            Map<String, RideStore.Sample> row = new HashMap<>();
            row.put("speed_kmh", new RideStore.Sample(20d, mono));
            row.put("odometer_km", new RideStore.Sample(100.0 + (i / 10) * 0.1, mono));
            store.sample(1000000 + i * 1000L, mono, row);
        }
        store.stop(1030000, 31000);
        List<RideStore.Point> points = store.fullTrend(id);
        RideChartData.Series miles = last(RideChartData.forRide(points, null, true, true, false));
        RideChartData.Series km = last(RideChartData.forRide(points, null, false, true, false));
        check(miles.displayLabel().equals("Distance") && miles.notedLabel().equals("Distance"), "the odometer's name: " + miles.displayLabel());
        RideChartData.Series withRide = last(RideChartData.forRide(points, null, false, true, false, store.get(id)));
        check(withRide.displayLabel().equals("Distance") && Math.abs(withRide.values.get(withRide.values.size() - 1).value - store.get(id).distanceKm) < 1e-9,
                "given the ride, the line ends at the ride's distance");
        check(miles.unit.equals("mi") && km.unit.equals("km"), "the unit follows the setting: " + miles.unit + " / " + km.unit);
        check(Math.abs(km.sampleAt(25000).value - 0.2) < 1e-9 && Math.abs(miles.sampleAt(25000).value - 0.2 / RideStore.KM_PER_MILE) < 1e-9, "the cursor's value in either unit");
        System.out.println("RideDistanceTextTest: " + checks + " checks passed (names by source, place, default, units)");
    }

    private static RideChartData.Series last(List<RideChartData.Series> lines) { return lines.get(lines.size() - 1); }
}
