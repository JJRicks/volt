package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What Volt has learned about one charger (Charge history &amp; recordings, owner's 2.0.1 item 13): the shape of a
 * learned curve's Summary as the card draws and words it. Pure Java, no Android.
 * <ul>
 *   <li>the speed: minutes each 1% takes, per percent, NaN where nothing usable is learned (a NONE or DISAGREEING
 *   percent). Minutes per 1% is what the estimates add up, so the line shows the algorithm's own numbers, and a
 *   stretch that is missing simply breaks the line; a "time to full from here" reading would be undefined below
 *   every gap;</li>
 *   <li>the coverage strip: runs of equal Coverage, 0% to 100%;</li>
 *   <li>the words: the learned span, the gaps (below, above and inside it), where recordings disagree, where a finish
 *   recording starts, and the time from the top learned stretch's start to full when that stretch reaches 100%.</li>
 * </ul>
 */
final class LearnedCurveChart {
    /** A stretch of percents [from, to), 0 to 100. */
    static final class Run {
        final int from, to;
        final ChargeSessionStore.Summary.Coverage coverage;
        Run(int from, int to, ChargeSessionStore.Summary.Coverage coverage) { this.from = from; this.to = to; this.coverage = coverage; }
        int length() { return to - from; }
        @Override public String toString() { return coverage + " " + from + "-" + to; }
    }
    /** Where a gap is: below the learned span, above it, between two learned stretches; or a stretch recordings disagree on. */
    enum Where { BELOW, ABOVE, BETWEEN, DISAGREE }
    static final class Gap {
        final Where where; final int from, to;
        Gap(Where where, int from, int to) { this.where = where; this.from = from; this.to = to; }
        @Override public String toString() { return where + " " + from + "-" + to; }
    }
    /** Inside gaps and disagreeing stretches listed by name each; the rest are counted ("and 2 more"). */
    static final int LISTED = 2;

    final ChargeSessionStore.Charger charger;
    /** Recordings behind the curve (Summary.qualifiedSessions). */
    final int recordings;
    /** Minutes for each 1%, 0% to 99%; NaN where nothing usable is learned. */
    final double[] minutes;
    /** Equal coverage in a row, 0% to 100%, in order. */
    final List<Run> runs;
    /** Stretches with usable minutes (one recording or two agreeing), in order. */
    final List<Run> learned;
    /** The words' gaps: below, the listed inside gaps, above, then the listed disagreeing stretches. */
    final List<Gap> gaps;
    /** Inside gaps and disagreeing stretches past LISTED each, not in gaps. */
    final int unlisted;
    /** The lowest finish recording's start, rounded down to a whole percent; -1 for none. */
    final int finishFrom;
    /** The top of the speed axis: the largest learned minutes rounded up to 1, 2 or 5 times a power of ten; 0 for none. */
    final double scaleMax;
    /** The percent of the slowest learned 1% (its minutes are the largest); -1 for none. */
    final int slowest;
    /** The top learned stretch's minutes to full when it reaches 100%, else NaN; from toFullFrom. */
    final double toFullMinutes;
    final int toFullFrom;

    private LearnedCurveChart(ChargeSessionStore.Summary summary) {
        charger = summary.charger; recordings = summary.qualifiedSessions;
        minutes = new double[100];
        List<Run> all = new ArrayList<>();
        int start = 0; ChargeSessionStore.Summary.Coverage current = null;
        double largest = 0; int slow = -1;
        for (int bin = 0; bin < 100; bin++) {
            ChargeSessionStore.Summary.Bin b = bin < summary.bins.size() ? summary.bins.get(bin) : null;
            ChargeSessionStore.Summary.Coverage coverage = b == null ? ChargeSessionStore.Summary.Coverage.NONE : b.coverage;
            boolean usable = b != null && usable(coverage) && b.minutes != null && Double.isFinite(b.minutes) && b.minutes > 0;
            if (!usable && usable(coverage)) coverage = ChargeSessionStore.Summary.Coverage.NONE;
            minutes[bin] = usable ? b.minutes : Double.NaN;
            if (usable && b.minutes > largest) { largest = b.minutes; slow = bin; }
            if (coverage != current) { if (current != null) all.add(new Run(start, bin, current)); start = bin; current = coverage; }
        }
        all.add(new Run(start, 100, current));
        runs = Collections.unmodifiableList(all);
        slowest = slow;
        scaleMax = largest > 0 ? niceCeiling(largest) : 0;
        List<Run> stretches = new ArrayList<>();
        for (Run run : all) {
            if (!usable(run.coverage)) continue;
            Run last = stretches.isEmpty() ? null : stretches.get(stretches.size() - 1);
            if (last != null && last.to == run.from) stretches.set(stretches.size() - 1, new Run(last.from, run.to, ChargeSessionStore.Summary.Coverage.PROVISIONAL));
            else stretches.add(new Run(run.from, run.to, ChargeSessionStore.Summary.Coverage.PROVISIONAL));
        }
        learned = Collections.unmodifiableList(stretches);
        List<Gap> words = new ArrayList<>();
        int hidden = 0;
        if (!stretches.isEmpty()) {
            int low = stretches.get(0).from, high = stretches.get(stretches.size() - 1).to;
            if (low > 0) words.add(new Gap(Where.BELOW, 0, low));
            int inside = 0;
            for (Run run : all) {
                if (run.coverage != ChargeSessionStore.Summary.Coverage.NONE || run.from < low || run.to > high) continue;
                if (inside++ < LISTED) words.add(new Gap(Where.BETWEEN, run.from, run.to)); else hidden++;
            }
            if (high < 100) words.add(new Gap(Where.ABOVE, high, 100));
        }
        int disagree = 0;
        for (Run run : all) {
            if (run.coverage != ChargeSessionStore.Summary.Coverage.DISAGREEING) continue;
            if (disagree++ < LISTED) words.add(new Gap(Where.DISAGREE, run.from, run.to)); else hidden++;
        }
        gaps = Collections.unmodifiableList(words);
        unlisted = hidden;
        int finish = -1;
        for (Double at : summary.finishStarts) if (at != null && Double.isFinite(at) && at >= 0 && at < 100) { finish = (int) Math.floor(at); break; }
        finishFrom = finish;
        Run top = stretches.isEmpty() ? null : stretches.get(stretches.size() - 1);
        if (top != null && top.to == 100) {
            double total = 0; for (int bin = top.from; bin < 100; bin++) total += minutes[bin];
            toFullMinutes = total; toFullFrom = top.from;
        } else { toFullMinutes = Double.NaN; toFullFrom = -1; }
    }

    /** The chart of one learned curve; null for none. */
    static LearnedCurveChart of(ChargeSessionStore.Summary summary) { return summary == null ? null : new LearnedCurveChart(summary); }

    /**
     * The charts to show, stock first: one per charger with a curve that has a recording; an empty list when nothing is
     * learned (the card's empty state). Nulls are skipped.
     */
    static List<LearnedCurveChart> of(List<ChargeSessionStore.Summary> summaries) {
        List<LearnedCurveChart> charts = new ArrayList<>();
        if (summaries == null) return charts;
        for (ChargeSessionStore.Charger charger : ChargeSessionStore.Charger.values())
            for (ChargeSessionStore.Summary summary : summaries)
                if (summary != null && summary.charger == charger && summary.qualifiedSessions > 0) charts.add(new LearnedCurveChart(summary));
        return charts;
    }

    /** Whether any percent has usable minutes. */
    boolean any() { return !learned.isEmpty(); }
    /** The learned span's ends: the first learned percent and the last one's end, or -1 with nothing learned. */
    int learnedFrom() { return learned.isEmpty() ? -1 : learned.get(0).from; }
    int learnedTo() { return learned.isEmpty() ? -1 : learned.get(learned.size() - 1).to; }
    /** Coverage kinds the strip shows, in the legend's order (none, one, agreeing, disagreeing), each once. */
    List<ChargeSessionStore.Summary.Coverage> legend() {
        List<ChargeSessionStore.Summary.Coverage> shown = new ArrayList<>();
        for (ChargeSessionStore.Summary.Coverage kind : new ChargeSessionStore.Summary.Coverage[]{ChargeSessionStore.Summary.Coverage.NONE,
                ChargeSessionStore.Summary.Coverage.PROVISIONAL, ChargeSessionStore.Summary.Coverage.ROBUST, ChargeSessionStore.Summary.Coverage.DISAGREEING})
            for (Run run : runs) if (run.coverage == kind) { shown.add(kind); break; }
        return shown;
    }
    /** A change worth redrawing: what the card shows, as one string. */
    String signature() {
        StringBuilder out = new StringBuilder().append(charger).append(':').append(recordings).append(':').append(finishFrom).append(':').append(runs);
        for (double m : minutes) out.append(',').append(Double.isNaN(m) ? "-" : Long.toString(Math.round(m * 100)));
        return out.toString();
    }

    private static boolean usable(ChargeSessionStore.Summary.Coverage coverage) {
        return coverage == ChargeSessionStore.Summary.Coverage.PROVISIONAL || coverage == ChargeSessionStore.Summary.Coverage.ROBUST;
    }
    /** The smallest 1, 2 or 5 times a power of ten at or above value (value > 0). */
    static double niceCeiling(double value) {
        double power = Math.pow(10, Math.floor(Math.log10(value)));
        for (double step : new double[]{1, 2, 5, 10}) if (step * power >= value - 1e-9 * power) return step * power;
        return 10 * power;
    }
}
