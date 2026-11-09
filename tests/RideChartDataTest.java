package com.jjricks.volt;
import java.util.*;
import java.nio.file.Files;
import java.io.File;

public final class RideChartDataTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void near(double expected, Double actual, String message) { check(actual != null && Math.abs(expected - actual) < 0.00001, message + ": " + actual); }
    public static void main(String[] args) throws Exception {
        inferredRegen();
        signedRideIsNotInferred();
        noRegenAcrossARecordedGap();
        near(50, RideChartData.energyPerDistance(1000d, 20d, false), "W divided by km/h yields Wh/km");
        near(80.4672, RideChartData.energyPerDistance(1000d, 20d, true), "Imperial conversion preserves energy");
        near(-10, RideChartData.energyPerDistance(-200d, 20d, false), "Battery input keeps negative sign");
        check(RideChartData.energyPerDistance(1000d, 2.9, false) == null, "Low-speed ratio unavailable");
        check(RideChartData.energyPerDistance(1000d, 0d, false) == null, "Stationary energy does not divide by zero");
        check(RideChartData.energyPerDistance(Double.NaN, 20d, false) == null, "Nonfinite readings excluded");
        check(RideChartData.energyPerDistance(null, 20d, false) == null, "Missing readings excluded");
        check(RideChartData.defaults().equals(new LinkedHashSet<String>(Arrays.asList("power_w", "speed_kmh"))), "Power and wheel speed default together");
        File dir = Files.createTempDirectory("ride-chart").toFile(); RideStore store = new RideStore(dir);
        String id = store.start("fixture", "Hill", 1000000, 1000);
        for (int i = 0; i < 3; i++) { Map<String,RideStore.Sample> row = new HashMap<>(); long mono = 1000 + i * 1000;
            row.put("voltage_v", new RideStore.Sample(50d, "", "V", 0x72, mono)); row.put("current_a", new RideStore.Sample(20d, "", "A", 0x72, mono));
            row.put("speed_kmh", new RideStore.Sample(20d, mono)); row.put("battery_percent", new RideStore.Sample(95d, mono)); store.sample(1000000 + i * 1000, mono, row); }
        store.stop(1003000, 4000); List<RideStore.Point> points = store.trend(id, 600);
        List<RideChartData.Series> metric = RideChartData.build(points, null, false), imperial = RideChartData.build(points, null, true);
        check(metric.size() == 8, "Every unified graph series exists");
        check(RideChartData.axes(metric).equals(metric), "Ride readings retain their independent axes");
        ChartScale.Range shared = ChartScale.range("clock", "Time", false, 1000, 2000);
        List<RideChartData.Value> axisValues = Arrays.asList(new RideChartData.Value(0, 1500d, 0));
        RideChartData.Series a = new RideChartData.Series("a", "A", "Time", 1, 0, axisValues, shared);
        RideChartData.Series b = new RideChartData.Series("b", "B", "Time", 2, 1, axisValues, new ChartScale.Range(shared.min, shared.max));
        RideChartData.Series c = new RideChartData.Series("c", "C", "min", 3, 2, axisValues, shared);
        check(RideChartData.axes(Arrays.asList(a, b, c)).size() == 3, "Equal bounds or different units do not accidentally share axes");
        boolean frozen = false; try { RideChartData.axes(Arrays.asList(a)).clear(); } catch (UnsupportedOperationException expected) { frozen = true; }
        check(frozen, "Axis selection is immutable");
        near(20, metric.get(1).values.get(0).value, "Metric wheel speed"); near(20 / 1.609344, imperial.get(1).values.get(0).value, "Imperial wheel speed");
        near(50, metric.get(7).values.get(0).value, "Instantaneous efficiency is separate from ride average");
        check(metric.get(4).min == 0 && metric.get(4).max == 100, "Battery scale fixed to meaningful limits");
        check(!metric.get(2).available() && !metric.get(5).available() && !metric.get(6).available(), "Old rides do not invent phone data");
        check(metric.get(0).max > metric.get(0).min, "Constant data still has valid scale");
        check(points.get(0).speedKmh == 20, "Display conversion does not mutate canonical readings");
        PhoneRideStore phoneStore = new PhoneRideStore(Files.createTempDirectory("ride-chart-phone").toFile());
        phoneStore.start("delayed", 1000500, 1500);
        phoneStore.appendBatch("delayed", PhoneRideStore.row("GPS", 1001000, 2000, 43.82, -111.78, 3, 5, .2, 1500, 4), "");
        PhoneRideStore.Track track = phoneStore.load("delayed");
        List<RideChartData.Series> together = RideChartData.build(points, track, false);
        check(together.get(2).values.get(0).time == 1000, "Delayed phone start aligns using source clocks, not its own zero");
        near(18, together.get(2).values.get(0).value, "GPS speed converted from m/s independently of wheel speed");
        check(together.get(5).available(), "Recorded phone elevation included in unified plot");
        String gapId = store.start("fixture", "Gap", 2000000, 10000);
        Map<String,RideStore.Sample> row = new HashMap<>(); row.put("speed_kmh", new RideStore.Sample(10d, 10000));
        store.sample(2000000, 10000, row); store.markGap(2000500, 10500, "Bluetooth disconnected");
        row.put("speed_kmh", new RideStore.Sample(12d, 11000)); store.sample(2001000, 11000, row); store.stop(2001500,11500);
        List<RideChartData.Value> segmented = RideChartData.build(store.trend(gapId, 600), null, false).get(1).values;
        check(segmented.get(0).segment != segmented.get(1).segment, "Short gaps break plotted lines even below ten seconds");
        List<RideChartData.Value> longRide=new ArrayList<>();for(int i=0;i<10000;i++)longRide.add(new RideChartData.Value(i*1000L,(double)i,i<5000?0:1));
        RideChartData.Series series=new RideChartData.Series(0,"W",longRide);int[] visible=series.visibleRange(4500000,4510000);
        check(visible[0]==4499&&visible[1]==4512,"Viewport keeps only visible points and edge neighbors");
        check(series.typicalIntervalMs==1000,"Sampling interval cached once");
        check(Arrays.equals(series.visibleRange(0,9999000),new int[]{0,10000}),"Whole-ride view retains endpoints");
        check(Arrays.equals(series.visibleRange(-1000,-500),new int[]{0,1}),"Range before first point remains bounded");
        check(Arrays.equals(series.visibleRange(10000000,10001000),new int[]{9999,10000}),"Range after last point remains bounded");
        RideChartData.Series unordered=new RideChartData.Series(0,"W",Arrays.asList(new RideChartData.Value(2000,1d,0),new RideChartData.Value(1000,2d,1)));
        check(Arrays.equals(unordered.visibleRange(1000,1500),new int[]{0,2}),"Nonmonotonic source timeline preserves safe full traversal");
        check(Arrays.equals(new RideChartData.Series(0,"W",Collections.emptyList()).visibleRange(0,1),new int[]{0,0}),"Empty series range is safe");
        check(series.sampleAt(4500400) == longRide.get(4500), "Inspection returns the original nearest sample, not interpolation");
        check(series.sampleAt(4500600) == longRide.get(4501), "Sliding picks the next original sample");
        check(series.sampleAt(4999500) == null, "Inspection never bridges a segment boundary");
        check(series.sampleAt(4999000) == longRide.get(4999) && series.sampleAt(5000000) == longRide.get(5000), "Real samples on both gap edges remain inspectable");
        check(series.sampleAt(-1) == null && series.sampleAt(9999001) == null, "Inspection does not carry endpoints beyond recorded data");
        check(series.sampleAt(Double.NaN) == null, "Unset cursor is safe");
        List<RideChartData.Value> sparse = Arrays.asList(new RideChartData.Value(0, 12.3456, 0), new RideChartData.Value(1000, null, 0),
                new RideChartData.Value(2000, 23.4567, 0), new RideChartData.Value(3000, 34.5678, 0), new RideChartData.Value(90000, 45.6789, 0));
        RideChartData.Series gaps = new RideChartData.Series(0, "W", sparse);
        check(gaps.sampleAt(500) == null && gaps.sampleAt(1500) == null, "Missing-value spans stay unavailable, not nearest valid values");
        check(gaps.sampleAt(1000) == sparse.get(1) && gaps.sampleAt(1000).value == null, "Exact missing sample preserves its identity");
        check(gaps.sampleAt(30000) == null, "Long unobserved interval stays unavailable");
        near(23.4567, gaps.sampleAt(2400).value, "Raw numeric precision is retained before display formatting");
        near(20 / 1.609344, imperial.get(1).sampleAt(1000).value, "Inspected wheel speed follows displayed units");
        check(together.get(2).sampleAt(1000).time == 1000, "Phone readout uses the aligned scooter elapsed timestamp");
        check(unordered.sampleAt(2000).value == 1 && unordered.sampleAt(1500) == null, "Unordered timeline permits exact points but not reversed segments");
        check(new RideChartData.Series(0, "W", Collections.emptyList()).sampleAt(0) == null, "Empty series inspection is safe");
        List<RideChartData.Value> dense = new ArrayList<>();
        for (int i = 0; i < 100000; i++) { Double value = 100d; if (i == 12345) value = 9999d; else if (i == 12346) value = -9876d; else if (i == 45001) value = null; dense.add(new RideChartData.Value(i * 100L, value, i < 60000 ? 0 : 1)); }
        RideChartData.Series denseSeries = new RideChartData.Series(0, "W", dense);
        int[] plan = denseSeries.drawIndices(0, 9999900, 320);
        check(plan.length < 320 * 4 + 20, "Dense rendering is bounded by pixel columns and gap edges");
        Set<Integer> kept = new HashSet<>(); for (int encoded : plan) kept.add(encoded < 0 ? -encoded - 1 : encoded);
        for (int index : new int[]{0,12345,12346,45000,45002,59999,60000,99999}) check(kept.contains(index), "Drawing preserves endpoint/extremum/gap edge " + index);
        for (int encoded : plan) if (encoded == -45003 || encoded == -60001) check(encoded < 0, "New paths are encoded at data gaps");
        check(Arrays.stream(plan).anyMatch(i -> i == -45003) && Arrays.stream(plan).anyMatch(i -> i == -60001), "Pixel compression never joins a missing sample or segment boundary");
        check(plan == denseSeries.drawIndices(0, 9999900, 320), "Scrubbing reuses the unchanged viewport drawing plan");
        check(denseSeries.sampleAt(1234500) == dense.get(12345), "Inspection accesses original full source, including a brief spike");
        int[] zoomed = denseSeries.drawIndices(1234000,1235000,320);
        check(Arrays.stream(zoomed).anyMatch(i -> i == 12345 || i == -12346), "Zoom resolves the original short spike");
        check(denseSeries.values.size() == 100000 && denseSeries.endTime == 9999900, "Drawing never removes source data");
        RideChartData.Series isolated = new RideChartData.Series(0, "W", Arrays.asList(new RideChartData.Value(0, null, 0), new RideChartData.Value(1000, 321d, 0), new RideChartData.Value(2000, null, 0)));
        check(Arrays.equals(isolated.drawIndices(0,2000,1),new int[]{-2}), "An isolated valid source sample remains a visible standalone point");
        String bootId=store.start("fixture","Reboot clocks",600000,1000);
        Map<String,RideStore.Sample> bootRow=new HashMap<>();bootRow.put("speed_kmh",new RideStore.Sample(20d,1000));store.sample(600000,1000,bootRow);
        bootRow.put("speed_kmh",new RideStore.Sample(20d,2000));store.sample(601000,2000,bootRow);
        bootRow.put("speed_kmh",new RideStore.Sample(20d,1000));store.sample(700000,1000,bootRow);store.stop(701000,2000);
        List<RideStore.Point> bootPoints=store.fullTrend(bootId);
        phoneStore.start("boot",700000,1000);phoneStore.appendBatch("boot",PhoneRideStore.row("GPS",700500,1500,43.82,-111.78,2,5,.1,1500,2),"");
        List<RideChartData.Value> bootPhone=RideChartData.build(bootPoints,phoneStore.loadSnapshot("boot"),false,true).get(2).values;
        check(bootPhone.size()==1&&bootPhone.get(0).time==bootPoints.get(2).elapsedMs+500,"Indexed alignment disambiguates repeated monotonic clocks by matching the correct boot's wall offset");
        phoneStore.start("unmatched",900000,1000);phoneStore.appendBatch("unmatched",PhoneRideStore.row("GPS",900500,1500,43.82,-111.78,2,5,.1,1500,2),"");
        check(RideChartData.build(bootPoints,phoneStore.loadSnapshot("unmatched"),false,true).get(2).values.isEmpty(),"Unmatched phone clock epoch is excluded instead of assigned a fabricated elapsed timestamp");
        List<RideChartData.Value> slow = Arrays.asList(
                new RideChartData.Value(0, 1d, 0), new RideChartData.Value(1000, 2d, 0),
                new RideChartData.Value(2000, 0d, 0), new RideChartData.Value(3000, 3d, 0),
                new RideChartData.Value(4000, null, 0), new RideChartData.Value(5000, 1d, 0),
                new RideChartData.Value(6000, 1d, 1), new RideChartData.Value(7000, 2d, 1),
                new RideChartData.Value(90000, 2d, 1), new RideChartData.Value(91000, 2d, 1));
        double[] slowExpected = {0, 2000, 6000, 7000, 90000, 91000};
        check(Arrays.equals(slowExpected, RideChartData.lowSpeedSpans(new RideChartData.Series(1, "km/h", slow))),
                "Low-speed shading excludes threshold, missing readings, segment boundaries and long gaps");
        List<RideChartData.Value> slowMiles = new ArrayList<>();
        for (RideChartData.Value value : slow) slowMiles.add(new RideChartData.Value(value.time,
                value.value == null ? null : value.value / RideStore.KM_PER_MILE, value.segment));
        check(Arrays.equals(slowExpected, RideChartData.lowSpeedSpans(new RideChartData.Series(1, "mph", slowMiles))),
                "Low-speed shading uses identical physical threshold in miles");
        check(RideChartData.lowSpeedSpans(new RideChartData.Series(1, "km/h", segmented)).length == 0,
                "Missing electrical readings at riding speed are not labeled below walking pace");
        check(RideChartData.lowSpeedSpans(new RideChartData.Series(7, "Wh/km", sparse)).length == 0,
                "Missing efficiency cannot be used as evidence of low speed");
        check(RideChartData.lowSpeedSpans(new RideChartData.Series(1, "km/h", Arrays.asList(
                new RideChartData.Value(2000, 1d, 0), new RideChartData.Value(1000, 1d, 0)))).length == 0,
                "Reversed timestamps cannot hatch an unobserved interval");
        List<RideChartData.Value> customValues = new ArrayList<>(Arrays.asList(
                new RideChartData.Value(0, 123.456789, 0), new RideChartData.Value(1000, 100d, 0),
                new RideChartData.Value(2000, null, 0), new RideChartData.Value(3000, 75d, 1)));
        ChartScale.Range customScale = ChartScale.range("charge_power", "W", true, 75, 124);
        RideChartData.Series custom = new RideChartData.Series("charge_power", "Power", "W", 0xff56b4e9,
                19, customValues, customScale);
        check(custom.key.equals("charge_power") && custom.label.equals("Power") && custom.index == 19
                && custom.color == 0xff56b4e9, "Custom series accepts independent identity and styling");
        check(custom.scale == customScale && custom.min == customScale.min && custom.max == customScale.max,
                "Custom series preserves its requested viewing range");
        check(custom.available() && custom.endTime == 3000 && custom.typicalIntervalMs == 1000,
                "Custom series uses the recorded sampling timeline");
        near(123.456789, custom.sampleAt(0).value, "Custom inspection preserves exact recorded scalar");
        check(custom.sampleAt(1500) == null && custom.sampleAt(2500) == null,
                "Custom inspection does not bridge missing readings or segment gaps");
        customValues.clear();
        check(custom.values.size() == 4 && custom.sampleAt(0).value == 123.456789,
                "Mutating the adapter source cannot alter immutable chart data");
        boolean immutable = false;
        try { custom.values.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Custom chart source is exposed read-only");
        check(Arrays.equals(custom.drawIndices(0, 3000, 1), new int[]{-1, 1, -4}),
                "Custom pixel buckets preserve gap-edge samples");
        RideChartData.Series extreme = new RideChartData.Series("estimated_minutes", "Est. countdown", "min",
                0xffe69f00, 0, Arrays.asList(new RideChartData.Value(0, -Double.MAX_VALUE, 0),
                new RideChartData.Value(1000, Double.MAX_VALUE, 0)), null);
        check(Double.isFinite(extreme.min) && Double.isFinite(extreme.max) && extreme.max > extreme.min,
                "Extreme custom values receive finite viewing bounds");
        for (int i = 0; i <= 4; i++) {
            double fraction = i / 4d, tick = extreme.scale.at(fraction);
            check(Double.isFinite(tick), "Extreme axis interpolation stays finite at " + fraction);
            near(fraction, extreme.scale.fraction(tick), "Extreme sample placement remains meaningful at " + fraction);
        }
        check(extreme.sampleAt(1000).value == Double.MAX_VALUE, "Safe rendering never clamps the recorded scalar");
        RideChartData.Series legacyExtreme = new RideChartData.Series(0, "W", extreme.values);
        check(Double.isFinite(legacyExtreme.min) && Double.isFinite(legacyExtreme.max),
                "Ride series also recover from overflowing imported bounds");
        RideChartData.Series sameIndex = new RideChartData.Series("temperature", "Temperature", "°C", 0xffe69f00,
                4, Arrays.asList(new RideChartData.Value(0, 20d, 0)), null);
        check(sameIndex.min > 0 && sameIndex.max < 100, "Custom color index does not trigger the ride battery scale");
        Thread.currentThread().interrupt(); boolean canceled = false;
        try { RideChartData.build(points, null, false, true); } catch (java.util.concurrent.CancellationException expected) { canceled = true; } finally { Thread.interrupted(); }
        check(canceled, "Full chart preparation honors cancellation before posting UI data");
        System.out.println("RideChartDataTest: " + checks + " assertions passed");
    }
    /** Unsigned ride: the power line goes below zero on the brake, and the energy rows stay unavailable; a signed plot is untouched. */
    private static void inferredRegen() throws Exception {
        File dir = Files.createTempDirectory("ride-chart-regen").toFile(); RideStore store = new RideStore(dir);
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW); String id = store.start("fixture", "Brake", 1000000, 1000);
        long t = 0; double kmh = 0, rest = 50, r = 0.08;
        for (int i = 0; i < 8; i++) unsigned(store, t++, 0.2, rest, 0);
        for (int i = 0; i < 8; i++) { kmh += 3; unsigned(store, t++, 18, rest - 18 * r, kmh); }
        for (int i = 0; i < 8; i++) unsigned(store, t++, 0.4, rest, kmh);
        for (int i = 0; i < 6; i++) { kmh -= 3.2; unsigned(store, t++, 8, rest + 8 * r, kmh); }
        for (int i = 0; i < 4; i++) unsigned(store, t++, 0.3, rest, kmh);
        store.stop(1000000 + t * 1000, 1000 + t * 1000);
        RideStore.Summary s = store.get(id);
        check(s.unsignedCurrent(), "254 with no negative current is an unsigned ride");
        List<RideStore.Point> points = store.fullTrend(id);
        List<RideChartData.Value> raw = RideChartData.build(points, null, false, true).get(0).values, shown = RideChartData.build(points, null, false, true, true).get(0).values;
        int negative = 0;
        for (RideChartData.Value v : raw) check(v.value == null || v.value >= 0, "the stored line never goes negative");
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).value != null && shown.get(i).value < 0) {
            negative++; check(Math.abs(shown.get(i).value + raw.get(i).value) < 1e-9, "a flipped point is the same magnitude"); }
        check(negative >= 4, "the brake goes below zero: " + negative);
        RideChartData.Series power = RideChartData.build(points, null, false, true, true).get(0);
        check(power.directionInferred, "a plot with inferred regen says so");
        check(!RideChartData.build(points, null, false, true, true).get(1).directionInferred, "only power's direction is inferred");
        check(!RideChartData.build(points, null, false, true, false).get(0).directionInferred, "the raw plot isn't");
        check(power.notedLabel().equals(power.displayLabel()), "no note until the screen sets one");
        power.directionNote = "Direction ?";
        check(power.notedLabel().equals(power.displayLabel() + " · Direction ?"), "the label carries the note: " + power.notedLabel());
        check(s.drawWh == null && s.returnedWh == null, "an unsigned ride's draw and input stay unavailable, inference or not");
        check(s.measuredThroughputWh != null, "its throughput is still measured");
    }
    /** A signed ride keeps its stored power line and its measured draw and input. */
    private static void signedRideIsNotInferred() throws Exception {
        File dir = Files.createTempDirectory("ride-chart-signed").toFile(); RideStore store = new RideStore(dir);
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW); String id = store.start("fixture", "Signed", 1000000, 1000);
        long t = 0; double kmh = 0;
        for (int i = 0; i < 8; i++) { kmh += 3; signed(store, t++, 18, 48.56, kmh); }
        for (int i = 0; i < 6; i++) { kmh -= 3.2; signed(store, t++, -8, 50.64, kmh); }
        store.stop(1000000 + t * 1000, 1000 + t * 1000);
        RideStore.Summary s = store.get(id);
        check(!s.unsignedCurrent(), "a scooter that signs its current is not an unsigned ride");
        check(s.drawWh != null && s.drawWh > 0 && s.returnedWh != null && s.returnedWh > 0, "its draw and input are the measured ones: " + s.drawWh + " / " + s.returnedWh);
        List<RideStore.Point> points = store.fullTrend(id);
        List<RideChartData.Value> shown = RideChartData.build(points, null, false, true, s.unsignedCurrent()).get(0).values;
        for (int i = 0; i < shown.size(); i++) check(Objects.equals(shown.get(i).value, points.get(i).powerW), "a signed point plots as stored");
        check(!RideChartData.build(points, null, false, true, s.unsignedCurrent()).get(0).directionInferred, "a signed plot is never marked inferred");
    }
    /** Different speeds on the two sides of a marked gap don't make a slowing; the same readings without one do. */
    private static void noRegenAcrossARecordedGap() throws Exception {
        RegenInference.Label[] gap = gapRide(true), joined = gapRide(false);
        for (int i = 0; i < gap.length; i++) check(gap[i] != RegenInference.Label.REGEN, "no regen on either side of the gap, at " + i + ": " + gap[i]);
        boolean found = false;
        for (RegenInference.Label label : joined) found |= label == RegenInference.Label.REGEN;
        check(found, "the same readings with no gap are read as a slowing, so the gap is what stops it");
    }
    private static RegenInference.Label[] gapRide(boolean marked) throws Exception {
        File dir = Files.createTempDirectory("ride-chart-gap").toFile(); RideStore store = new RideStore(dir);
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW); String id = store.start("fixture", "Gap", 1000000, 1000);
        long t = 0; double kmh = 0, watts = 150, volts = 50.24, amps = watts / volts;
        for (int i = 0; i < 8; i++) unsigned(store, t++, 0.2, 50, 0);
        for (int i = 0; i < 8; i++) { kmh += 3.75; unsigned(store, t++, 18, 50 - 18 * 0.08, kmh); }
        for (int i = 0; i < 3; i++) unsigned(store, t++, 0.4, 50, 30);
        for (int i = 0; i < 3; i++) unsigned(store, t++, amps, volts, 30);
        if (marked) store.markGap(1000000 + t * 1000, 1000 + t * 1000, "Bluetooth disconnected");
        t++; // one second missed
        for (int i = 0; i < 2; i++) unsigned(store, t++, amps, volts, 20);
        store.stop(1000000 + t * 1000, 1000 + t * 1000);
        return RideChartData.regenLabels(store.fullTrend(id));
    }
    private static void signed(RideStore store, long t, double amps, double volts, double kmh) throws Exception {
        long mono = 1000 + t * 1000; Map<String,RideStore.Sample> row = new HashMap<>();
        row.put("voltage_v", new RideStore.Sample(volts, "", "V", 0x72, mono)); row.put("current_a", new RideStore.Sample(amps, "", "A", 0x72, mono));
        row.put("speed_kmh", new RideStore.Sample(kmh, mono)); row.put("current_sign_state", new RideStore.Sample(1d, mono)); row.put("battery_percent", new RideStore.Sample(90d, mono));
        store.sample(1000000 + t * 1000, mono, row);
    }
    private static void unsigned(RideStore store, long t, double amps, double volts, double kmh) throws Exception {
        long mono = 1000 + t * 1000; Map<String,RideStore.Sample> row = new HashMap<>();
        row.put("voltage_v", new RideStore.Sample(volts, "", "V", 0x72, mono)); row.put("current_a", new RideStore.Sample(amps, "", "A", 0x72, mono));
        row.put("speed_kmh", new RideStore.Sample(kmh, mono)); row.put("current_sign_state", new RideStore.Sample(254d, mono)); row.put("battery_percent", new RideStore.Sample(90d, mono));
        store.sample(1000000 + t * 1000, mono, row);
    }
}
