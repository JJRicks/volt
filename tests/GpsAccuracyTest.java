package com.jjricks.volt;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

/**
 * GPS accuracy (owner, Nov 12): the ride graph's line (values, gaps, scale, units, old rides and rides with few or no
 * fixes) and riding mode's reading (its states, and never a stale fix as current). Synthesized fixtures only.
 */
public final class GpsAccuracyTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void near(double expected, Double actual, String message) {
        check(actual != null && Math.abs(expected - actual) < 1e-6, message + ": " + actual);
    }

    public static void main(String[] args) throws Exception {
        units();
        scale();
        series();
        preview();
        reading();
        unchanged();
        System.out.println("GpsAccuracyTest passed " + checks + " checks");
    }

    private static void units() {
        check(GpsAccuracy.feet("mi") && GpsAccuracy.feet("dual") && GpsAccuracy.feet(null) && !GpsAccuracy.feet("km"),
                "feet unless kilometres, as the graph's elevation and riding mode's distances");
        check(GpsAccuracy.unit(true).equals("ft") && GpsAccuracy.unit(false).equals("m"), "the units are ft and m");
        near(3.280839895 * 4, GpsAccuracy.shown(4, true), "metres to feet");
        near(4, GpsAccuracy.shown(4, false), "metres stay metres");
        check(GpsAccuracy.whole(12.4) == 12 && GpsAccuracy.whole(12.5) == 13, "whole numbers, rounded");
        check(GpsAccuracy.whole(0.2) == 1, "a fix with any radius never reads as ±0");
        check(GpsAccuracy.whole(0) == 0 && GpsAccuracy.whole(Double.NaN) == 0 && GpsAccuracy.whole(-1) == 0, "0 only for 0 or nonsense");
    }

    private static List<Double> repeat(double value, int count) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(value);
        return out;
    }

    private static void scale() {
        ChartScale.Range empty = GpsAccuracy.scale(Collections.<Double>emptyList(), false);
        check(empty.min == 0 && empty.max == 12, "no fixes: the 10 m floor, rounded to four steps of 3: " + empty.max);
        check(GpsAccuracy.scale(null, true).max == 40, "no fixes in feet: the 30 ft floor, four steps of 10");
        List<Double> good = new ArrayList<>();
        for (int i = 0; i < 100; i++) good.add(3.0 + i % 6);
        ChartScale.Range calm = GpsAccuracy.scale(good, false);
        check(calm.min == 0 && calm.max == 12, "good fixes keep the floor's scale, so they don't look noisy: " + calm.max);
        // A few fixes of several hundred feet must not flatten the line.
        List<Double> spiky = new ArrayList<>(repeat(15, 97));
        spiky.addAll(Arrays.asList(400d, 650d, 900d));
        ChartScale.Range clipped = GpsAccuracy.scale(spiky, true);
        check(clipped.max == 40, "three bad fixes among 100 leave the scale to the typical ones: " + clipped.max);
        check(clipped.fraction(900) == 1 && clipped.fraction(15) > .3, "the bad fixes draw at the top edge; typical ones use the height");
        // Many poor fixes are the ride's story: the scale covers them.
        List<Double> poor = new ArrayList<>(repeat(15, 50));
        poor.addAll(repeat(120, 50));
        check(GpsAccuracy.scale(poor, true).max >= 120, "a ride of many poor fixes is scaled to show them");
        ChartScale.Range one = GpsAccuracy.scale(Arrays.asList(25d), false);
        check(one.max >= 25 && one.max > one.min, "one fix: a valid scale that holds it: " + one.max);
        check(GpsAccuracy.scale(Arrays.asList(Double.NaN, null, -3d, Double.POSITIVE_INFINITY), false).max == 12, "nonsense values are ignored");
        double[] tops = {4, 8, 12, 20, 40, 80, 120, 200, 400, 800, 1200, 2000};
        for (double value : new double[]{0, 3.9, 4.1, 11, 13, 39, 41, 119, 121, 199, 201, 1500})
            check(Arrays.binarySearch(tops, GpsAccuracy.niceTop(value)) >= 0 && GpsAccuracy.niceTop(value) >= value,
                    "the top is four whole even steps that reach " + value + ": " + GpsAccuracy.niceTop(value));
        check(GpsAccuracy.niceTop(Double.MAX_VALUE) > 0 && Double.isFinite(GpsAccuracy.niceTop(Double.MAX_VALUE)), "huge values stay finite");
    }

    private static String gps(long mono, double accuracy) {
        return PhoneRideStore.row("GPS", 1700000000000L + mono, mono, 43.82 + mono * 1e-7, -111.78, accuracy, 5, .2, 1500, 4);
    }

    private static List<RideStore.Point> scooter(File dir) throws Exception {
        RideStore store = new RideStore(dir);
        String id = store.start("fixture", "Accuracy", 1700000000000L + 1000, 1000);
        for (int i = 0; i < 40; i++) {
            Map<String, RideStore.Sample> row = new HashMap<>();
            long mono = 1000 + i * 1000;
            row.put("speed_kmh", new RideStore.Sample(20d, mono));
            store.sample(1700000000000L + mono, mono, row);
        }
        store.stop(1700000000000L + 41000, 41000);
        return store.trend(id, 600);
    }

    private static void series() throws Exception {
        File base = Files.createTempDirectory("gps-accuracy").toFile();
        List<RideStore.Point> points = scooter(new File(base, "scooter"));
        PhoneRideStore phone = new PhoneRideStore(new File(base, "phone"));
        phone.start("ride", 1700000000000L + 1000, 1000);
        StringBuilder b = new StringBuilder();
        // 2..6 s good fixes, 7 s a 90 m fix the route rejects, 8..10 s good, a GAP, 11..13 s, then a 20 s pause and 34..36 s.
        for (long s = 2; s <= 6; s++) b.append(gps(s * 1000, 4));
        b.append(gps(7000, 90));
        for (long s = 8; s <= 10; s++) b.append(gps(s * 1000, 6));
        b.append(PhoneRideStore.row("GAP", 1700000000000L + 10500, 10500));
        for (long s = 11; s <= 13; s++) b.append(gps(s * 1000, 8));
        for (long s = 34; s <= 36; s++) b.append(gps(s * 1000, 5));
        b.append(PhoneRideStore.row("GPS", 1700000000000L + 37000, 37000, 99, -111.78, 3, 5, .2, 1500, 4)); // invalid latitude
        phone.appendBatch("ride", b.toString(), "");
        PhoneRideStore.Track track = phone.load("ride");
        check(track.fullAccuracy().size() == 15, "every reported fix's accuracy, the rejected 90 m one included, a corrupt row not: " + track.fullAccuracy().size());
        check(track.rejectedLocationCount >= 1 && track.locationCount < 15, "the route still drops the 90 m fix");
        RideChartData.Series metres = RideChartData.gpsAccuracy(points, track, false, false, -1);
        RideChartData.Series feet = RideChartData.gpsAccuracy(points, track, true, true, -1);
        check(metres.key.equals("gps_accuracy") && metres.unit.equals("m") && feet.unit.equals("ft"), "its key and units");
        check(metres.color == GpsAccuracy.COLOR && metres.index == 8 && metres.index % 3 == 2, "its colour and dotted pattern");
        check(metres.values.size() == 15 && feet.values.size() == 15, "one point per fix");
        near(1000, (double) metres.values.get(0).time, "aligned to the scooter's timeline like the other phone lines");
        near(4, metres.values.get(0).value, "metres as reported");
        near(4 * GpsAccuracy.FEET_PER_METRE, feet.values.get(0).value, "feet for miles");
        near(90, metres.values.get(5).value, "the rejected fix is plotted at its real value");
        check(metres.max == 20 && metres.min == 0, "the 90 m fix doesn't set the scale: " + metres.max);
        check(metres.clipsAbove(), "and the series knows it draws at the top edge");
        RideChartData.Value beforeGap = metres.values.get(8), afterGap = metres.values.get(9);
        check(metres.connects(metres.values.get(0), metres.values.get(1)), "fixes a second apart join");
        check(!metres.connects(beforeGap, afterGap) && beforeGap.segment != afterGap.segment, "a recorded GAP breaks the line");
        check(!metres.connects(metres.values.get(11), metres.values.get(12)), "a 20 s pause breaks the line");
        for (RideChartData.Value v : metres.values) check(v.value != null && v.value > 0, "a gap is never drawn as zero");
        check(metres.sampleAt(20000) == null, "inspecting inside the pause finds no value");
        // Old rides and rides with one or no fixes.
        RideChartData.Series none = RideChartData.gpsAccuracy(points, null, false, false, -1);
        check(!none.available() && none.values.isEmpty() && none.max > none.min && !none.clipsAbove(), "a ride without a phone track: no data, valid scale");
        RideChartData.Series none2 = RideChartData.gpsAccuracy(Collections.<RideStore.Point>emptyList(), phone.load("missing"), true, true, -1);
        check(!none2.available() && none2.max == 40, "a phone track with no fixes: no data");
        phone.start("one", 1700000000000L + 1000, 1000);
        phone.appendBatch("one", gps(5000, 7), "");
        RideChartData.Series one = RideChartData.gpsAccuracy(points, phone.load("one"), false, false, -1);
        check(one.available() && one.values.size() == 1 && one.max > one.min && one.sampleAt(one.values.get(0).time) != null, "one fix: a point you can inspect");
        phone.start("few", 1700000000000L + 1000, 1000);
        phone.appendBatch("few", gps(5000, 3) + gps(6000, 3), "");
        RideChartData.Series few = RideChartData.gpsAccuracy(points, phone.load("few"), false, false, -1);
        check(few.available() && few.values.size() == 2 && few.max == 12, "two equal fixes: the floor's scale");
        // Phone-only (no scooter points): elapsed time from the phone's own start, as the other phone lines.
        RideChartData.Series alone = RideChartData.gpsAccuracy(Collections.<RideStore.Point>emptyList(), track, false, false, -1);
        check(alone.values.size() == 15 && alone.values.get(0).time == 1000, "phone-only rides use the phone's own elapsed time");
        // Privacy: the samples hold an accuracy and clocks, never a position.
        for (Field f : PhoneRideStore.AccuracySample.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            check(!name.contains("lat") && !name.contains("lon") && !name.contains("position"), "no position in an accuracy sample: " + f.getName());
        }
        // Exports are untouched: the CSV and JSON already carry accuracy, and nothing new.
        check(track.toCsv().startsWith("wall_ms,monotonic_ms,elapsed_ms,segment,latitude,longitude,accuracy_m,"), "the CSV export's columns are unchanged");
        check(!track.toCsv().contains("gps_accuracy") && !track.toJson().contains("gps_accuracy"), "no new export field");
    }

    private static void preview() throws Exception {
        File base = Files.createTempDirectory("gps-accuracy-long").toFile();
        PhoneRideStore phone = new PhoneRideStore(base);
        phone.start("long", 1700000000000L, 0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7000; i++) b.append(gps(1000 + i * 1000L, i == 4321 ? 300 : 4));
        phone.appendBatch("long", b.toString(), "");
        PhoneRideStore.Track track = phone.load("long");
        check(track.fullAccuracy().size() == 7000, "every fix in full detail");
        check(track.accuracy.size() <= 3001 && track.accuracy.size() >= 2000, "a bounded preview: " + track.accuracy.size());
        double worst = 0;
        for (PhoneRideStore.AccuracySample s : track.accuracy) worst = Math.max(worst, s.metres);
        check(worst == 300, "the preview keeps a short spike of bad fixes");
        List<PhoneRideStore.AccuracySample> split = new ArrayList<>();
        for (int i = 0; i < 10; i++) split.add(new PhoneRideStore.AccuracySample(i * 1000L, i * 1000L, i * 1000L, 5, i < 5 ? 0 : 1));
        List<PhoneRideStore.AccuracySample> bounded = PhoneRideStore.AccuracySample.bounded(split, 3);
        for (int i = 1; i < bounded.size(); i++)
            check(bounded.get(i).segment >= bounded.get(i - 1).segment, "a preview run never spans a segment");
        boolean both = false;
        for (PhoneRideStore.AccuracySample s : bounded) both |= s.segment == 1;
        check(both && bounded.get(0).segment == 0, "both segments survive");
    }

    private static void reading() {
        GpsAccuracy.Fix fix = new GpsAccuracy.Fix(50000, 3.7);
        check(GpsAccuracy.reading(false, GpsAccuracy.Phase.LIVE, fix, 50000).state == GpsAccuracy.State.NOT_RECORDING, "not recording");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.OFF, fix, 50000).state == GpsAccuracy.State.OFF, "GPS off");
        check(GpsAccuracy.reading(true, null, null, 50000).state == GpsAccuracy.State.OFF, "no recorder: GPS off");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.WAITING, null, 50000).state == GpsAccuracy.State.WAITING, "waiting for a fix");
        GpsAccuracy.Reading live = GpsAccuracy.reading(true, GpsAccuracy.Phase.LIVE, fix, 52000);
        check(live.state == GpsAccuracy.State.FIX && live.metres == 3.7, "a fresh fix shows its accuracy");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.LIVE, fix, 60000).state == GpsAccuracy.State.FIX, "10 s old is still current");
        GpsAccuracy.Reading stale = GpsAccuracy.reading(true, GpsAccuracy.Phase.LIVE, fix, 60001);
        check(stale.state == GpsAccuracy.State.NO_FIX && stale.metres == null, "older than 10 s: no fix, and no number at all");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.LOST, fix, 51000).state == GpsAccuracy.State.NO_FIX, "signal lost: no fix, even with a recent one");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.LIVE, null, 51000).state == GpsAccuracy.State.NO_FIX, "live without a fix: no fix");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.LIVE, new GpsAccuracy.Fix(70000, 3), 50000).state == GpsAccuracy.State.NO_FIX, "a fix from the future is not current");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.LIVE, new GpsAccuracy.Fix(50000, Double.NaN), 50000).state == GpsAccuracy.State.NO_FIX, "a nonsense accuracy is not shown");
        check(GpsAccuracy.reading(true, GpsAccuracy.Phase.WAITING, fix, 50000).state == GpsAccuracy.State.WAITING, "waiting never shows an old ride's fix");
    }

    private static void unchanged() throws Exception {
        check(Arrays.asList(RideChartData.KEYS).equals(Arrays.asList("power_w", "speed_kmh", "gps_speed", "voltage_v", "battery_percent", "elevation", "gps_accel", "wh_per_distance")),
                "the eight ride lines keep their keys and order");
        check(Arrays.equals(RideChartData.COLORS, new int[]{0xffe69f00, 0xff56b4e9, 0xff009e73, 0xffcc79a7, 0xfff0e442, 0xff0072b2, 0xffd55e00, 0xff999999}), "and their colours");
        for (int color : RideChartData.COLORS) check(color != GpsAccuracy.COLOR, "the new line's colour is its own");
        check(!Arrays.asList(RideChartData.KEYS).contains(GpsAccuracy.KEY), "its key is its own");
        check(RideChartData.defaults().equals(new LinkedHashSet<String>(Arrays.asList("power_w", "speed_kmh"))), "the graph still opens on power and wheel speed: GPS accuracy is off until chosen");
        List<RideChartData.Series> built = RideChartData.build(Collections.<RideStore.Point>emptyList(), null, true, false, false);
        List<RideChartData.Series> ride = RideChartData.forRide(Collections.<RideStore.Point>emptyList(), null, true, false, false);
        for (int i = 0; i < built.size(); i++)
            check(ride.get(i).key.equals(built.get(i).key) && ride.get(i).unit.equals(built.get(i).unit) && ride.get(i).color == built.get(i).color,
                    "the ride graph's first lines are build()'s, unchanged: " + built.get(i).key);
    }
}
