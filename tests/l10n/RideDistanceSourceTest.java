package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * The distance line's source and look (#618 round 1): one odometer report snapshotted for several seconds is one reading,
 * not several, so GPS draws the line; the ride's summary picks the source, so a thinned preview agrees with the full
 * graph; and Distance never looks like another line, Power above all (pattern and grey level).
 */
public final class RideDistanceSourceTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("ride-distance-source").toFile();
        RideStore store = new RideStore(dir);
        PhoneRideStore phones = new PhoneRideStore(Files.createTempDirectory("ride-distance-source-phone").toFile());

        // One full report with the odometer at 5 s, then short reports without it: the service snapshots the last
        // telemetry each second and the ride keeps a reading up to 3 s, so several points carry that one report.
        String once = store.start("fixture", "One report", 1000000, 1000);
        RideStore.Sample report = new RideStore.Sample(812.3, 6000);
        for (int i = 0; i < 60; i++) {
            long mono = 1000 + i * 1000L;
            Map<String, RideStore.Sample> row = new HashMap<>();
            row.put("speed_kmh", new RideStore.Sample(18d, mono));
            if (i >= 5) row.put("odometer_km", report);
            store.sample(1000000 + i * 1000L, mono, row);
        }
        store.stop(1060000, 61000);
        List<RideStore.Point> points = store.fullTrend(once);
        int carrying = 0; for (RideStore.Point p : points) if (p.odometerKm != null) carrying++;
        check(carrying >= 2, "the fixture: one report shows on " + carrying + " points");
        phones.start(once, 1000000, 1000);
        StringBuilder fixes = new StringBuilder();
        for (int t = 1; t <= 58; t++) fixes.append(PhoneRideStore.row("GPS", 1000000 + t * 1000L, 1000 + t * 1000L, 43.82 + t * 4.5e-5, -111.78, 3, 5, .2, 1500, 4));
        phones.appendBatch(once, fixes.toString(), "");
        phones.stop(once, 1060000, 61000);
        PhoneRideStore.Track track = phones.load(once);
        RideChartData.Series line = last(RideChartData.forRide(points, track, false, true, false));
        check(line.label.equals(RideChartData.GPS_DISTANCE_LABEL) && line.available(), "one report is one reading: GPS draws the line, " + line.label);
        RideChartData.Series withRide = last(RideChartData.forRide(points, track, false, true, false, store.get(once)));
        check(withRide.label.equals(RideChartData.GPS_DISTANCE_LABEL) && withRide.displayLabel().equals("GPS distance"), "with the ride's summary too: " + withRide.label);

        // The summary picks the source: the odometer moved once, on a sample the preview drops (past 600 samples the
        // journal keeps samples 0 and 1, then every other one), so the preview alone sees one value.
        String thinned = store.start("fixture", "Thinned", 2000000, 1000);
        for (int i = 0; i < 1300; i++) {
            long mono = 1000 + i * 1000L;
            Map<String, RideStore.Sample> row = new HashMap<>();
            row.put("speed_kmh", new RideStore.Sample(18d, mono));
            if (i >= 2) row.put("odometer_km", new RideStore.Sample(i == 2 ? 700.0 : 700.1, mono));
            store.sample(2000000 + i * 1000L, mono, row);
        }
        store.stop(3300000, 1301000);
        RideStore.Summary ride = store.get(thinned);
        Set<Double> previewValues = new HashSet<>();
        for (RideStore.Point p : store.trend(thinned, 600)) if (p.odometerKm != null) previewValues.add(p.odometerKm);
        check(previewValues.size() == 1 && ride.distanceKm != null && ride.distanceKm > 0.09, "the fixture: the preview sees " + previewValues + ", the ride " + ride.distanceKm + " km");
        RideChartData.Series preview = last(RideChartData.forRide(store.trend(thinned, 600), null, false, false, false, ride));
        RideChartData.Series full = last(RideChartData.forRide(store.fullTrend(thinned), null, false, true, false, ride));
        check(preview.label.equals(RideChartData.DISTANCE_KEY) && full.label.equals(RideChartData.DISTANCE_KEY), "both from the odometer: " + preview.label + " / " + full.label);
        check(Math.abs(preview.values.get(preview.values.size() - 1).value - ride.distanceKm) < 1e-9, "the preview ends at the ride's distance");

        // Distance looks like no other line: a different pattern (index % 3) or a grey level at least 30 apart.
        List<RideChartData.Series> lines = RideChartData.forRide(Collections.<RideStore.Point>emptyList(), null, false, false, false);
        RideChartData.Series distance = last(lines), power = lines.get(0);
        check(power.key.equals("power_w") && distance.index % 3 != power.index % 3, "Distance's pattern isn't Power's: " + distance.index % 3);
        for (RideChartData.Series other : lines) {
            if (other == distance) continue;
            boolean samePattern = other.index % 3 == distance.index % 3;
            double apart = Math.abs(grey(other.color) - grey(distance.color));
            check(!samePattern || apart >= 30, "Distance and " + other.key + " share a pattern and a grey level (" + apart + " apart)");
        }
        check(Math.abs(grey(distance.color) - grey(power.color)) >= 30, "Distance's grey is far from Power's too: " + grey(distance.color) + " vs " + grey(power.color));
        System.out.println("RideDistanceSourceTest: " + checks + " checks passed (one report, summary source, pattern and grey)");
    }

    private static double grey(int color) { return .299 * ((color >> 16) & 255) + .587 * ((color >> 8) & 255) + .114 * (color & 255); }

    private static RideChartData.Series last(List<RideChartData.Series> lines) { return lines.get(lines.size() - 1); }
}
