package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntToDoubleFunction;

/**
 * English text fixes for 2.0.2: the Record a charge dialog names the recordings' rotation (#563 F01), Map settings' switch
 * for the Just back card's map has its own label and summary (#560), and What Volt has learned words a stretch a fast
 * charge measured only too cold or hot as that, not as missing data (#572 F01). Summaries are built by hand and from
 * synthesized recordings (a high-speed-like model, as LearnedCurveReasonsTest). No real recording is used.
 */
public final class EnglishFixes202Test {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;
    private static final ChargeSessionStore.Summary.Coverage NONE = ChargeSessionStore.Summary.Coverage.NONE,
            ONE = ChargeSessionStore.Summary.Coverage.PROVISIONAL, SPLIT = ChargeSessionStore.Summary.Coverage.DISAGREEING;

    public static void main(String[] args) throws Exception {
        recordBody();
        mapSwitch();
        temperatureGaps();
        synthesized();
        System.out.println("English fixes 2.0.2 tests passed: " + checks + " checks");
    }

    /** #563 F01: no "keeps the last 20"; the oldest go when the room is full (ChargeSessionStore's rotation). */
    private static void recordBody() {
        String body = T.moreDevRecordBody();
        check(body.equals("Saved on phone · stops at 100% · oldest cleared when full"), "Record a charge: " + body);
        check(!body.contains("20"), "No fixed count of recordings kept: " + body);
        check(ChargeSessionStore.MAX_RECORDINGS == 1000 && ChargeSessionStore.BUDGET_BYTES == 150L * 1024 * 1024,
                "The rotation the words describe: up to 1,000 recordings in about 150 MB");
    }

    /** #560: the switch and the summary name the last ride, not the Home tab alone. */
    private static void mapSwitch() {
        check(T.mapsJustBackSwitch().equals("Map on Home's last ride"), "Map settings' switch: " + T.mapsJustBackSwitch());
        check(T.morePrefsMapLastRide().equals("Last ride"), "Its summary word: " + T.morePrefsMapLastRide());
        check(JustBackBasemap.summary(false, true, "OSM", "Off", T.morePrefsMapLastRide()).equals("OSM · Last ride"),
                "Only the card's map on: " + JustBackBasemap.summary(false, true, "OSM", "Off", T.morePrefsMapLastRide()));
        check(JustBackBasemap.summary(true, true, "OSM", "Off", T.morePrefsMapLastRide()).equals("OSM"), "The ride maps' on: the provider alone");
    }

    /** #572 F01: a NONE stretch measured outside the learning temperatures is its own gap, with its own words. */
    private static void temperatureGaps() {
        // 0-30 never recorded, 30-50 measured only cold, 50-100 learned.
        LearnedCurveChart cold = LearnedCurveChart.of(summary(30, 50, 50, 100, -1, -1));
        check(cold.gaps.toString().equals("[BELOW 0-30, TEMPERATURE 30-50]"), "Cold start: " + cold.gaps);
        check(cold.any(), "Something is learned");
        check(T.moreLearnedGapTemperature("30", "50").equals("Too cold or hot to learn 30–50%"), "Its words: " + T.moreLearnedGapTemperature("30", "50"));
        check(T.moreLearnedGapBelow("30").equals("No data below 30%"), "The stretch never recorded stays no data: " + T.moreLearnedGapBelow("30"));
        // The whole run below the learned span measured cold: no "No data below" at all.
        LearnedCurveChart allCold = LearnedCurveChart.of(summary(0, 40, 40, 100, -1, -1));
        check(allCold.gaps.toString().equals("[TEMPERATURE 0-40]"), "Cold from 0%: " + allCold.gaps);
        // Hot at the top, after the learned span; a plain gap above it stays no data.
        LearnedCurveChart hot = LearnedCurveChart.of(summary(80, 90, 10, 80, -1, -1));
        check(hot.gaps.toString().equals("[BELOW 0-10, ABOVE 90-100, TEMPERATURE 80-90]"), "Hot near the top: " + hot.gaps);
        // Inside the learned span, between two learned stretches.
        LearnedCurveChart inside = LearnedCurveChart.of(summary(40, 45, 20, 40, 45, 100));
        check(inside.gaps.toString().equals("[BELOW 0-20, TEMPERATURE 40-45]"), "Cold inside: " + inside.gaps);
        // A disagreeing stretch keeps its own words, after the temperature ones.
        List<ChargeSessionStore.Summary.Bin> bins = new ArrayList<>();
        for (int bin = 0; bin < 100; bin++) {
            ChargeSessionStore.Summary.Coverage c = bin >= 60 ? ONE : bin >= 50 ? SPLIT : NONE;
            bins.add(new ChargeSessionStore.Summary.Bin(bin, c, c == ONE ? 1 : c == SPLIT ? 2 : 0, c == ONE ? Double.valueOf(2.5) : null, null, false, bin >= 20 && bin < 50));
        }
        LearnedCurveChart mixed = LearnedCurveChart.of(new ChargeSessionStore.Summary(ChargeSessionStore.Charger.HIGH_SPEED, 2, Collections.emptyList(), bins));
        check(mixed.gaps.toString().equals("[BELOW 0-20, TEMPERATURE 20-50, DISAGREE 50-60]"), "With disagreement: " + mixed.gaps);
        // Past two, temperature stretches are counted, as inside gaps are.
        bins = new ArrayList<>();
        for (int bin = 0; bin < 100; bin++) {
            boolean learned = bin % 10 >= 5;
            bins.add(new ChargeSessionStore.Summary.Bin(bin, learned ? ONE : NONE, learned ? 1 : 0, learned ? Double.valueOf(2.5) : null, null, false, !learned && bin >= 10));
        }
        LearnedCurveChart many = LearnedCurveChart.of(new ChargeSessionStore.Summary(ChargeSessionStore.Charger.HIGH_SPEED, 1, Collections.emptyList(), bins));
        check(many.gaps.toString().equals("[BELOW 0-5, TEMPERATURE 10-15, TEMPERATURE 20-25]") && many.unlisted == 7, "Many: " + many.gaps + " and " + many.unlisted);
        // The card redraws when only which percents were too cold or hot changes.
        check(!cold.signature().equals(LearnedCurveChart.of(summary(30, 50, 50, 100, -1, -1, false)).signature()), "The signature carries the temperature stretches");
        // Without any outside percent, the gaps are as before.
        check(LearnedCurveChart.of(summary(30, 50, 50, 100, -1, -1, false)).gaps.toString().equals("[BELOW 0-50]"), "No temperature: no data below 50%");
    }

    /** A fast charge cold from 30% to 50%, then warm to full: learned from about 50%, the cold stretch in its own words. */
    private static void synthesized() throws Exception {
        ChargeSessionStore store = new ChargeSessionStore(Files.createTempDirectory("english-fixes-cold-start-").toFile());
        record(store, 30, level -> level < 50 ? 10 : 25);
        LearnedCurveChart chart = null;
        for (ChargeSessionStore.Curve curve : store.snapshot().curves.values()) chart = LearnedCurveChart.of(curve.summary());
        check(chart != null && chart.any(), "The warm part learns");
        LearnedCurveChart.Gap temperature = null, below = null;
        for (LearnedCurveChart.Gap gap : chart.gaps) {
            if (gap.where == LearnedCurveChart.Where.TEMPERATURE) temperature = gap;
            if (gap.where == LearnedCurveChart.Where.BELOW) below = gap;
        }
        check(temperature != null && Math.abs(temperature.from - 30) <= 1 && Math.abs(temperature.to - 50) <= 1, "The cold stretch: " + chart.gaps);
        check(below != null && below.to == temperature.from, "No data only below where the recording starts: " + chart.gaps);
        check(Math.abs(chart.learnedFrom() - temperature.to) <= 1, "Learned from where it warmed: " + chart.learned);
        // LearnedCurveWords (Android-side, beside the card) words it with its own key.
        String source = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/LearnedCurveWords.java")), StandardCharsets.UTF_8).replace(" ", "");
        check(source.contains("caseTEMPERATURE:returnT.moreLearnedGapTemperature(Fmt.integer(gap.from),Fmt.integer(gap.to));"), "The card words a temperature stretch with its own key");
    }

    /** By hand: outside from outFrom to outTo (NONE), learned (ONE) aFrom-aTo and bFrom-bTo; the rest NONE. */
    private static ChargeSessionStore.Summary summary(int outFrom, int outTo, int aFrom, int aTo, int bFrom, int bTo) {
        return summary(outFrom, outTo, aFrom, aTo, bFrom, bTo, true);
    }
    private static ChargeSessionStore.Summary summary(int outFrom, int outTo, int aFrom, int aTo, int bFrom, int bTo, boolean outside) {
        List<ChargeSessionStore.Summary.Bin> bins = new ArrayList<>();
        for (int bin = 0; bin < 100; bin++) {
            boolean learned = bin >= aFrom && bin < aTo || bin >= bFrom && bin < bTo;
            bins.add(new ChargeSessionStore.Summary.Bin(bin, learned ? ONE : NONE, learned ? 1 : 0, learned ? Double.valueOf(2.5) : null, null, false,
                    outside && !learned && bin >= outFrom && bin < outTo));
        }
        return new ChargeSessionStore.Summary(ChargeSessionStore.Charger.HIGH_SPEED, 1, Collections.emptyList(), bins);
    }

    // A high-speed-like charger (LearnedCurveReasonsTest's FAST).
    private static final double RATED_AH = 20;
    private static final DoubleUnaryOperator AMPS = soc -> soc < 80 ? 10.7 : 10.7 * Math.max(.12, 1 - (soc - 80) / 22);
    private static final DoubleUnaryOperator VOLTS = soc -> 38.5 + 4.5 * Math.min(soc, 85) / 100;
    private static double minutes(int level) { return RATED_AH * .01 / AMPS.applyAsDouble(level) * 60; }
    private static ChargeSessionStore.Value value(double v, long mono) { return new ChargeSessionStore.Value(v, mono, 0x72); }

    /** To 100% complete from start, a sample every ten seconds, at the battery temperature tempC(level). */
    private static void record(ChargeSessionStore store, int start, IntToDoubleFunction tempC) throws Exception {
        store.start("one", ChargeSessionStore.Charger.HIGH_SPEED, WALL, MONO);
        long t = 0;
        for (int level = start; level < 100; level++) {
            long span = (long) (minutes(level) * 60000);
            for (long at = 0; at < span; at += 10000, t += 10000) sample(store, t, level, false, tempC.applyAsDouble(level));
        }
        sample(store, t, 100, true, tempC.applyAsDouble(100));
        store.stop(ChargeSessionStore.Stop.COMPLETE);
    }
    private static void sample(ChargeSessionStore store, long t, int level, boolean completion, double tempC) throws Exception {
        long mono = MONO + t;
        ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(WALL + t, mono, value(completion ? 0 : 1, mono), value(level, mono),
                value(VOLTS.applyAsDouble(level), mono), value(-AMPS.applyAsDouble(Math.min(level, 99)), mono), value(tempC, mono));
        check(completion ? store.sampleCompletion(frame) : store.sample(frame), "Synthesized sample saved at " + level + "%");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
