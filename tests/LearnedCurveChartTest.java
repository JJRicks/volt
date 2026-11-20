package com.jjricks.volt;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Charge history &amp; recordings' learned-curve card (LearnedCurveChart): the runs, gaps, finish tick, scale and time to
 * full it reads from a curve's Summary. Summaries are built by hand, and from recordings synthesized from a stock-like
 * and a high-speed-like charger model, as StitchedChargeEstimateTest does. No real recording is used.
 */
public final class LearnedCurveChartTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final ChargeSessionStore.Summary.Coverage NONE = ChargeSessionStore.Summary.Coverage.NONE,
            ONE = ChargeSessionStore.Summary.Coverage.PROVISIONAL, TWO = ChargeSessionStore.Summary.Coverage.ROBUST,
            SPLIT = ChargeSessionStore.Summary.Coverage.DISAGREEING;

    /** A summary by hand: coverage[i] for percent i, minutes 2.5 where usable (taper: 10 from 95%), finish starts. */
    private static ChargeSessionStore.Summary summary(ChargeSessionStore.Charger charger, int sessions, ChargeSessionStore.Summary.Coverage[] coverage, Double... finish) {
        List<ChargeSessionStore.Summary.Bin> bins = new ArrayList<>();
        for (int bin = 0; bin < 100; bin++) {
            ChargeSessionStore.Summary.Coverage c = coverage[bin];
            boolean usable = c == ONE || c == TWO;
            bins.add(new ChargeSessionStore.Summary.Bin(bin, c, c == NONE ? 0 : c == ONE ? 1 : 2, usable ? Double.valueOf(bin >= 95 ? 10 : 2.5) : null, null, false));
        }
        return new ChargeSessionStore.Summary(charger, sessions, Arrays.asList(finish), bins);
    }
    private static ChargeSessionStore.Summary.Coverage[] fill(Object... spans) {
        ChargeSessionStore.Summary.Coverage[] out = new ChargeSessionStore.Summary.Coverage[100]; Arrays.fill(out, NONE);
        for (int i = 0; i < spans.length; i += 3) for (int bin = (Integer) spans[i]; bin < (Integer) spans[i + 1]; bin++) out[bin] = (ChargeSessionStore.Summary.Coverage) spans[i + 2];
        return out;
    }

    /** A charger model, as in StitchedChargeEstimateTest. */
    private static final class Model {
        final double ratedAh; final DoubleUnaryOperator amps, volts;
        Model(double ratedAh, DoubleUnaryOperator amps, DoubleUnaryOperator volts) { this.ratedAh = ratedAh; this.amps = amps; this.volts = volts; }
        double minutes(int level) { return ratedAh * .01 / amps.applyAsDouble(level) * 60; }
    }
    private static final Model STOCK = new Model(10, soc -> soc < 85 ? 2.4 : 2.4 * Math.max(.18, 1 - (soc - 85) / 18), soc -> 38 + 4 * Math.min(soc, 90) / 100);
    private static final Model FAST = new Model(20, soc -> soc < 80 ? 10.7 : 10.7 * Math.max(.12, 1 - (soc - 80) / 22), soc -> 38.5 + 4.5 * Math.min(soc, 85) / 100);
    private static ChargeSessionStore.Value value(double v, long mono) { return new ChargeSessionStore.Value(v, mono, 0x72); }
    private static void record(ChargeSessionStore store, ChargeSessionStore.Charger charger, Model model, int start, int end) throws Exception {
        store.start("one", charger, WALL, MONO);
        long t = 0;
        for (int level = start; level < end; level++) {
            long span = (long) (model.minutes(level) * 60000);
            for (long at = 0; at < span; at += 10000, t += 10000) sample(store, model, t, level, false);
        }
        boolean complete = end == 100;
        if (complete) sample(store, model, t, 100, true);
        else for (int i = 0; i < 3; i++, t += 10000) sample(store, model, t, end, false);
        store.stop(complete ? ChargeSessionStore.Stop.COMPLETE : ChargeSessionStore.Stop.STOPPED);
    }
    private static void sample(ChargeSessionStore store, Model model, long t, int level, boolean completion) throws Exception {
        long mono = MONO + t; double amps = model.amps.applyAsDouble(Math.min(level, 99)), volts = model.volts.applyAsDouble(level);
        ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(WALL + t, mono, value(completion ? 0 : 1, mono), value(level, mono), value(volts, mono), value(-amps, mono), value(23, mono));
        check(completion ? store.sampleCompletion(frame) : store.sample(frame), "Synthesized sample saved at " + level + "%");
    }
    private static List<ChargeSessionStore.Summary> summaries(ChargeSessionStore store) throws Exception {
        List<ChargeSessionStore.Summary> out = new ArrayList<>();
        for (ChargeSessionStore.Curve curve : store.snapshot().curves.values()) out.add(curve.summary());
        return out;
    }

    /**
     * A recording from start to end at the battery temperature tempC (a sample every ten seconds), to 100% complete; with
     * dropoutAt > start, it charges to dropoutAt, the phone loses it for three hours, and it comes back at resumeAt.
     */
    private static void recordAt(ChargeSessionStore store, ChargeSessionStore.Charger charger, Model model, int start, int dropoutAt, int resumeAt, double tempC) throws Exception {
        store.start("one", charger, WALL, MONO);
        long t = 0;
        for (int level = start; level < 100; level++) {
            if (level == dropoutAt) { t += 3 * 3_600_000L; level = resumeAt; }
            long span = (long) (model.minutes(level) * 60000);
            for (long at = 0; at < span; at += 10000, t += 10000) sampleAt(store, model, t, level, false, tempC);
        }
        sampleAt(store, model, t, 100, true, tempC);
        store.stop(ChargeSessionStore.Stop.COMPLETE);
    }
    private static void sampleAt(ChargeSessionStore store, Model model, long t, int level, boolean completion, double tempC) throws Exception {
        long mono = MONO + t; double amps = model.amps.applyAsDouble(Math.min(level, 99)), volts = model.volts.applyAsDouble(level);
        ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(WALL + t, mono, value(completion ? 0 : 1, mono), value(level, mono), value(volts, mono), value(-amps, mono), value(tempC, mono));
        check(completion ? store.sampleCompletion(frame) : store.sample(frame), "Synthesized sample saved at " + level + "%");
    }

    public static void main(String[] args) throws Exception {
        // Nothing learned: no charts, the card's empty state.
        check(LearnedCurveChart.of((List<ChargeSessionStore.Summary>) null).isEmpty(), "No summaries: no charts");
        check(LearnedCurveChart.of(Collections.singletonList(summary(ChargeSessionStore.Charger.STOCK, 0, fill()))).isEmpty(), "A curve without recordings is not shown");

        // One partial recording, 17–50%.
        LearnedCurveChart partial = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.STOCK, 1, fill(17, 50, ONE)));
        check(partial.any() && partial.learnedFrom() == 17 && partial.learnedTo() == 50 && partial.recordings == 1, "Learned 17–50% from one recording");
        check(partial.runs.toString().equals("[NONE 0-17, PROVISIONAL 17-50, NONE 50-100]"), "Runs: " + partial.runs);
        check(partial.gaps.toString().equals("[BELOW 0-17, ABOVE 50-100]"), "No data below 17% or above 50%: " + partial.gaps);
        check(partial.legend().equals(Arrays.asList(NONE, ONE)), "Legend: no data and one recording: " + partial.legend());
        check(partial.finishFrom == -1 && Double.isNaN(partial.toFullMinutes) && partial.toFullFrom == -1, "No finish, no time to full");
        check(partial.scaleMax == 3 && partial.slowest == 17, "Scale 0–3 min over 2.5 min: " + partial.scaleMax);
        check(Double.isNaN(partial.minutes[16]) && partial.minutes[17] == 2.5 && partial.minutes[49] == 2.5 && Double.isNaN(partial.minutes[50]), "Minutes only where learned");

        // Two partial recordings joined: 17–45% one, 45–50% both, 50–100% one; a finish recording from 45%.
        LearnedCurveChart joined = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.STOCK, 2, fill(17, 45, ONE, 45, 50, TWO, 50, 100, ONE), 45d));
        check(joined.learned.size() == 1 && joined.learnedFrom() == 17 && joined.learnedTo() == 100, "One learned stretch 17–100%: " + joined.learned);
        check(joined.gaps.toString().equals("[BELOW 0-17]"), "Only no data below 17%: " + joined.gaps);
        check(joined.legend().equals(Arrays.asList(NONE, ONE, TWO)), "Legend: three kinds");
        check(joined.finishFrom == 45, "Finish tick at 45%");
        check(joined.toFullFrom == 17 && Math.abs(joined.toFullMinutes - (78 * 2.5 + 5 * 10)) < 1e-9, "17% to full: " + joined.toFullMinutes);
        check(joined.scaleMax == 12 && joined.slowest == 95, "The taper sets the scale, 12 over 10 min: " + joined.scaleMax);

        // Inside gaps and disagreement: two listed, the rest counted.
        LearnedCurveChart gappy = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.HIGH_SPEED, 3,
                fill(5, 20, TWO, 20, 22, NONE, 22, 30, ONE, 30, 33, SPLIT, 33, 40, ONE, 40, 41, NONE, 41, 60, TWO, 60, 62, NONE, 62, 90, ONE)));
        check(gappy.gaps.toString().equals("[BELOW 0-5, BETWEEN 20-22, BETWEEN 40-41, ABOVE 90-100, DISAGREE 30-33]"), "Gaps in order: " + gappy.gaps);
        check(gappy.unlisted == 1, "The third inside gap is counted: " + gappy.unlisted);
        check(gappy.learned.size() == 5 && gappy.legend().equals(Arrays.asList(NONE, ONE, TWO, SPLIT)), "Five stretches, four kinds");
        check(Double.isNaN(gappy.minutes[31]), "A disagreeing percent draws no speed");

        // A usable kind with no minutes (never written so, but read defensively) counts as no data.
        List<ChargeSessionStore.Summary.Bin> odd = new ArrayList<>();
        for (int bin = 0; bin < 100; bin++) odd.add(new ChargeSessionStore.Summary.Bin(bin, bin < 50 ? ONE : NONE, 1, null, null, false));
        LearnedCurveChart empty = LearnedCurveChart.of(new ChargeSessionStore.Summary(ChargeSessionStore.Charger.STOCK, 1, Collections.emptyList(), odd));
        check(!empty.any() && empty.gaps.isEmpty() && empty.scaleMax == 0 && empty.runs.size() == 1, "No minutes: nothing learned, one run of no data");

        // Nice ceilings.
        check(LearnedCurveChart.niceCeiling(2.5) == 2.5 && LearnedCurveChart.niceCeiling(1) == 1 && LearnedCurveChart.niceCeiling(10.4) == 12
                && LearnedCurveChart.niceCeiling(13.9) == 15 && LearnedCurveChart.niceCeiling(0.3) == .3 && LearnedCurveChart.niceCeiling(55) == 60
                && LearnedCurveChart.niceCeiling(8.5) == 10 && LearnedCurveChart.niceCeiling(1.1) == 1.2, "Nice steps just over the value");

        // From synthesized recordings: a stock 17–50% and 45–100%, and a fast 30–100%: stock first, then high-speed.
        ChargeSessionStore store = new ChargeSessionStore(Files.createTempDirectory("learned-curve-chart-").toFile());
        record(store, ChargeSessionStore.Charger.HIGH_SPEED, FAST, 30, 100);
        record(store, ChargeSessionStore.Charger.STOCK, STOCK, 17, 50);
        record(store, ChargeSessionStore.Charger.STOCK, STOCK, 45, 100);
        List<LearnedCurveChart> charts = LearnedCurveChart.of(summaries(store));
        check(charts.size() == 2 && charts.get(0).charger == ChargeSessionStore.Charger.STOCK && charts.get(1).charger == ChargeSessionStore.Charger.HIGH_SPEED, "Stock first, then high-speed");
        LearnedCurveChart stock = charts.get(0), fast = charts.get(1);
        check(stock.recordings == 2 && stock.learnedFrom() == 18 && stock.learnedTo() == 100 && stock.gaps.toString().equals("[BELOW 0-18]"), "Stock joined, learned from 18% (a recording's first part-percent is not counted): " + stock.gaps + " " + stock.learned);
        check(stock.legend().contains(TWO) && stock.legend().contains(ONE), "Stock: one and two recordings: " + stock.runs);
        check(stock.finishFrom == 46 && stock.slowest >= 85 && stock.scaleMax >= stock.minutes[stock.slowest] * LearnedCurveChart.HEADROOM && stock.scaleMax < stock.minutes[stock.slowest] * 1.5, "Stock finish from 46%, slowest in the taper: " + stock.finishFrom + " " + stock.slowest);
        check(stock.toFullFrom == 18 && stock.toFullMinutes > 0, "Stock time to full");
        check(fast.recordings == 1 && fast.learnedFrom() == 31 && fast.learnedTo() == 100 && fast.minutes[50] < stock.minutes[50], "Fast learned from 31%, faster: " + fast.learned);
        check(!stock.signature().equals(fast.signature()) && stock.signature().equals(LearnedCurveChart.of(summaries(store)).get(0).signature()), "Signatures follow the data");

        // Gaps are only stretches no recording covers: recordings that disagree at 0–10% are not "no data below 30%".
        LearnedCurveChart trimmed = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.STOCK, 2, fill(0, 10, SPLIT, 30, 90, ONE, 95, 100, SPLIT)));
        check(trimmed.gaps.toString().equals("[BETWEEN 10-30, BETWEEN 90-95, DISAGREE 0-10, DISAGREE 95-100]"), "Gaps trimmed to no-data stretches: " + trimmed.gaps);
        LearnedCurveChart edges = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.STOCK, 2, fill(0, 5, NONE, 5, 10, SPLIT, 10, 60, ONE, 60, 70, SPLIT)));
        check(edges.gaps.toString().equals("[BELOW 0-5, ABOVE 70-100, DISAGREE 5-10, DISAGREE 60-70]"), "Below and above stop at a disagreeing stretch: " + edges.gaps);

        // Nothing usable: why, and the finish still shows.
        LearnedCurveChart split = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.STOCK, 2, fill(20, 60, SPLIT)));
        check(!split.any() && split.why() == LearnedCurveChart.Why.DISAGREE && split.gaps.toString().equals("[DISAGREE 20-60]"), "Disagreeing everywhere: " + split.gaps);
        LearnedCurveChart finishOnly = LearnedCurveChart.of(summary(ChargeSessionStore.Charger.STOCK, 1, fill(), 86d));
        check(!finishOnly.any() && finishOnly.why() == LearnedCurveChart.Why.TOO_LITTLE && finishOnly.finishFrom == 86 && finishOnly.gaps.isEmpty(), "A finish with no usable percent: too little data, finish at 86%");
        List<ChargeSessionStore.Summary.Bin> coldBins = new ArrayList<>();
        for (int bin = 0; bin < 100; bin++) coldBins.add(new ChargeSessionStore.Summary.Bin(bin, NONE, 0, null, null, false, bin >= 30));
        LearnedCurveChart coldHand = LearnedCurveChart.of(new ChargeSessionStore.Summary(ChargeSessionStore.Charger.HIGH_SPEED, 1, Collections.emptyList(), coldBins));
        check(!coldHand.any() && coldHand.outsideTemperature && coldHand.why() == LearnedCurveChart.Why.TEMPERATURE, "Measured only outside the learning temperatures");

        // From synthesized recordings: a fast charge done entirely at 10 C teaches nothing and says why.
        ChargeSessionStore cold = new ChargeSessionStore(Files.createTempDirectory("learned-curve-cold-").toFile());
        recordAt(cold, ChargeSessionStore.Charger.HIGH_SPEED, FAST, 30, -1, -1, 10);
        List<LearnedCurveChart> coldCharts = LearnedCurveChart.of(summaries(cold));
        check(coldCharts.size() == 1 && !coldCharts.get(0).any() && coldCharts.get(0).why() == LearnedCurveChart.Why.TEMPERATURE && coldCharts.get(0).finishFrom == -1,
                "A cold fast charge: shown, nothing learned, too cold or hot: " + coldCharts.get(0).runs + " " + coldCharts.get(0).finishFrom);
        ChargeSessionStore.Summary coldSummary = summaries(cold).get(0);
        check(coldSummary.bins.get(50).outsideTemperature && !coldSummary.bins.get(20).outsideTemperature, "The summary marks the percents measured outside the temperatures");
        // The same charge at 25 C learns as usual, and marks nothing.
        ChargeSessionStore warm = new ChargeSessionStore(Files.createTempDirectory("learned-curve-warm-").toFile());
        recordAt(warm, ChargeSessionStore.Charger.HIGH_SPEED, FAST, 30, -1, -1, 25);
        LearnedCurveChart warmChart = LearnedCurveChart.of(summaries(warm)).get(0);
        check(warmChart.any() && !warmChart.outsideTemperature, "A warm fast charge learns");
        // A stock charge lost for three hours (50% to 85%) keeps its finish but no percent: too little data, with the finish.
        ChargeSessionStore dropout = new ChargeSessionStore(Files.createTempDirectory("learned-curve-dropout-").toFile());
        recordAt(dropout, ChargeSessionStore.Charger.STOCK, STOCK, 45, 55, 85, 23);
        List<LearnedCurveChart> dropoutCharts = LearnedCurveChart.of(summaries(dropout));
        check(dropoutCharts.size() == 1 && !dropoutCharts.get(0).any() && dropoutCharts.get(0).why() == LearnedCurveChart.Why.TOO_LITTLE && dropoutCharts.get(0).finishFrom >= 85,
                "A dropout with a valid finish: " + (dropoutCharts.isEmpty() ? "no chart" : dropoutCharts.get(0).runs + " finish " + dropoutCharts.get(0).finishFrom));
        System.out.println("Learned curve chart tests passed: " + checks + " checks");
    }
}
