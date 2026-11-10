package com.jjricks.volt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Infers which readings of an unsigned current were regen, for a scooter whose current has no
 * direction (PowerSign). Measured on rides from scooters that really sign their current, with the
 * sign stripped. Display only: a stored or exported value never changes.
 *
 * <p>A reading can only be regen while the scooter is slowing, and regen raises the voltage where
 * draw lowers it, so a reading is compared against a resting voltage from nearby low-current
 * samples. Two limits never give way: regen can't supply more power than the deceleration has,
 * and a reading too strong to be regen stays draw. Speeds are never compared across an interruption
 * (a recorded gap, or a new connection in ride mode), because the change between two readings
 * separated by one says nothing about what the scooter did; a resting voltage still carries across.
 */
final class RegenInference {
    enum Label { DRAW, REGEN, UNKNOWN }

    /** Thresholds of the inference. The defaults are the ones the measurement was made with. */
    static final class Params {
        /** Below this much current the voltage difference is too small to read. */
        double minAmps = 1.5;
        /** The current below which a sample can serve as a resting voltage. */
        double restAmps = 1.0;
        /** Regen fades out near a stop; below this speed nothing is called regen. */
        double minSpeedKmh = 3;
        /** Slowing required, in km/h per second. */
        double minDecelKmhPerS = 0.5;
        /** How far from the draw prediction (0) toward the regen prediction (1) a reading must sit. */
        double tau = 0.5;
        /** Right after strong draw the voltage is still recovering, so the bar is higher. */
        double afterDrawAmps = 8, afterDrawTau = 0.7; long afterDrawMs = 2_500;
        /** The voltage must also sit this far above what draw predicts. */
        double marginV = 0.25;
        /** Mass for the kinetic bound: a heavy rider and scooter, so it errs towards allowing regen. */
        double massKg = 200;
        /** Allowance on the kinetic bound for smoothing of the speed reading. */
        double kineticSlackW = 80;
        /** Readings this strong are draw whatever else is true (RidingValues.inferredDrawThresholdW). */
        double drawLimitW = 1200;
        double defaultOhms = 0.08, minOhms = 0.02, maxOhms = 0.35;
        /** How strong, and how many, accelerating samples it takes to learn the resistance. */
        double learnAmps = 8; int learnSamples = 5;
        long restWindowMs = 60_000; int restSamples = 3;
        long pastDecelMs = 2_500, aroundDecelMs = 2_600;
        /** A weak unknown reading next to a regen one, in the same slowing, joins it. */
        boolean extend = true; long extendMs = 1_600;
    }

    /** One power reading: its magnitude, the voltage and speed measured with it. */
    static final class Sample {
        final long timeMs; final double watts, volts, speedKmh;
        /** Samples with different segments have an interruption between them. */
        final int segment;
        Sample(long timeMs, double watts, double volts, double speedKmh) { this(timeMs, watts, volts, speedKmh, 0); }
        Sample(long timeMs, double watts, double volts, double speedKmh, int segment) {
            this.timeMs = timeMs; this.watts = Math.abs(watts); this.volts = volts; this.speedKmh = speedKmh; this.segment = segment;
        }
        boolean usable() {
            return Double.isFinite(watts) && Double.isFinite(volts) && Double.isFinite(speedKmh) && volts > 0 && speedKmh >= 0;
        }
        double amps() { return watts / volts; }
    }

    private RegenInference() { }

    /** Labels a whole ride, free to look both ways. Samples must be in time order. */
    static Label[] wholeRide(List<Sample> samples) { return wholeRide(samples, new Params()); }

    static Label[] wholeRide(List<Sample> samples, Params p) {
        int n = samples.size();
        Label[] labels = new Label[n];
        double[] rest = new double[n];
        Arrays.fill(labels, Label.UNKNOWN); Arrays.fill(rest, Double.NaN);
        double[] before = restSeries(samples, p, 1), after = restSeries(samples, p, -1);
        long[] beforeAt = restTimes(samples, p, 1), afterAt = restTimes(samples, p, -1);
        for (int i = 0; i < n; i++) rest[i] = interpolate(before[i], beforeAt[i], after[i], afterAt[i], samples.get(i).timeMs);
        // Learned over the whole ride, from readings that can only be draw.
        List<Double> ohms = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            if (!s.usable() || Double.isNaN(rest[i]) || s.amps() < p.learnAmps) continue;
            if (decel(samples, i, p.aroundDecelMs, p.aroundDecelMs) >= 0) continue;
            ohms.add((rest[i] - s.volts) / s.amps());
        }
        double r = resistance(ohms, p);
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            if (s.usable()) labels[i] = decide(s, decel(samples, i, p.aroundDecelMs, p.aroundDecelMs), rest[i], r, afterDraw(samples, labels, i, p), p);
        }
        if (p.extend) extend(samples, labels, p);
        return labels;
    }

    private static void extend(List<Sample> samples, Label[] labels, Params p) {
        boolean[] seed = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) seed[i] = labels[i] == Label.REGEN;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != Label.UNKNOWN || !samples.get(i).usable() || samples.get(i).amps() < p.minAmps) continue;
            double decel = decel(samples, i, p.aroundDecelMs, p.aroundDecelMs);
            if (decel <= 0 || !withinLimits(samples.get(i), decel, p)) continue;
            boolean near = i > 0 && seed[i - 1] && samples.get(i - 1).segment == samples.get(i).segment && samples.get(i).timeMs - samples.get(i - 1).timeMs <= p.extendMs
                    || i + 1 < labels.length && seed[i + 1] && samples.get(i + 1).segment == samples.get(i).segment && samples.get(i + 1).timeMs - samples.get(i).timeMs <= p.extendMs;
            if (near) labels[i] = Label.REGEN;
        }
    }

    /** The past-only inference for live ride mode: a reading is labelled once, when it arrives. */
    static final class Live {
        private final Params p;
        private final ArrayDeque<Sample> recent = new ArrayDeque<>();
        private final ArrayDeque<Sample> low = new ArrayDeque<>();
        private final ArrayList<Double> ohms = new ArrayList<>();
        private Sample previous; private Label previousLabel = Label.UNKNOWN;
        Live() { this(new Params()); }
        Live(Params p) { this.p = p; }

        Label accept(Sample s) {
            if (!s.usable()) return Label.UNKNOWN;
            boolean sameRun = previous != null && previous.segment == s.segment;
            if (!sameRun) recent.clear(); // no slowing is measured across an interruption
            while (!recent.isEmpty() && s.timeMs - recent.peekFirst().timeMs > Math.max(p.pastDecelMs, 5_000)) recent.removeFirst();
            while (!low.isEmpty() && s.timeMs - low.peekFirst().timeMs > p.restWindowMs) low.removeFirst();
            double rest = median(low, p.restSamples);
            double decel = pastDecel(s);
            if (!Double.isNaN(rest) && s.amps() >= p.learnAmps && decel < 0 && ohms.size() < 400) ohms.add((rest - s.volts) / s.amps());
            boolean afterDraw = previous != null && previousLabel == Label.DRAW && previous.amps() >= p.afterDrawAmps && s.timeMs - previous.timeMs <= p.afterDrawMs;
            Label label = decide(s, decel, rest, resistance(ohms, p), afterDraw, p);
            if (p.extend && label == Label.UNKNOWN && previousLabel == Label.REGEN && sameRun && s.timeMs - previous.timeMs <= p.extendMs
                    && s.amps() >= p.minAmps && decel > 0 && withinLimits(s, decel, p)) label = Label.REGEN;
            previous = s; previousLabel = label;
            recent.addLast(s);
            if (s.amps() < p.restAmps) low.addLast(s);
            return label;
        }
        private double pastDecel(Sample s) {
            Sample anchor = null;
            for (Sample r : recent) if (s.timeMs - r.timeMs <= p.pastDecelMs) { anchor = r; break; }
            if (anchor == null || s.timeMs <= anchor.timeMs) return 0;
            return (anchor.speedKmh - s.speedKmh) * 1000.0 / (s.timeMs - anchor.timeMs);
        }
    }

    private static boolean afterDraw(List<Sample> samples, Label[] labels, int i, Params p) {
        if (i == 0 || labels[i - 1] != Label.DRAW) return false;
        Sample prior = samples.get(i - 1);
        return prior.amps() >= p.afterDrawAmps && samples.get(i).timeMs - prior.timeMs <= p.afterDrawMs;
    }

    private static Label decide(Sample s, double decel, double rest, double ohms, boolean afterDraw, Params p) {
        if (s.watts >= p.drawLimitW) return Label.DRAW;
        double amps = s.amps();
        if (decel <= 0 && amps >= p.minAmps) return Label.DRAW; // speed rising: regen is impossible.
        if (amps < p.minAmps || s.speedKmh < p.minSpeedKmh || decel < p.minDecelKmhPerS) return Label.UNKNOWN;
        if (s.watts > kineticLimitW(s, decel, p)) return Label.DRAW;
        if (Double.isNaN(rest)) return Label.UNKNOWN;
        double swing = 2 * amps * ohms;
        double above = s.volts - rest + amps * ohms; // 0 if this is draw, swing if it is regen
        return above / swing >= (afterDraw ? p.afterDrawTau : p.tau) && above >= p.marginV ? Label.REGEN : above < 0 ? Label.DRAW : Label.UNKNOWN;
    }

    /** The most power this much slowing can supply as regen. */
    private static double kineticLimitW(Sample s, double decel, Params p) {
        return p.massKg * (s.speedKmh / 3.6) * (decel / 3.6) + p.kineticSlackW;
    }

    /** What an extended reading must meet as well: extension skips the voltage, never the physics. */
    private static boolean withinLimits(Sample s, double decel, Params p) {
        return s.speedKmh >= p.minSpeedKmh && s.watts <= kineticLimitW(s, decel, p);
    }

    private static double decel(List<Sample> samples, int i, long pastMs, long futureMs) {
        Sample s = samples.get(i), first = s, last = s;
        for (int j = i - 1; j >= 0 && samples.get(j).segment == s.segment && s.timeMs - samples.get(j).timeMs <= pastMs; j--) if (samples.get(j).usable()) first = samples.get(j);
        for (int j = i + 1; j < samples.size() && samples.get(j).segment == s.segment && samples.get(j).timeMs - s.timeMs <= futureMs; j++) if (samples.get(j).usable()) last = samples.get(j);
        return last.timeMs <= first.timeMs ? 0 : (first.speedKmh - last.speedKmh) * 1000.0 / (last.timeMs - first.timeMs);
    }

    private static double[] restSeries(List<Sample> samples, Params p, int direction) {
        double[] rest = new double[samples.size()];
        ArrayDeque<Sample> low = new ArrayDeque<>();
        for (int k = 0; k < samples.size(); k++) {
            int i = direction > 0 ? k : samples.size() - 1 - k;
            Sample s = samples.get(i);
            while (!low.isEmpty() && Math.abs(s.timeMs - low.peekFirst().timeMs) > p.restWindowMs) low.removeFirst();
            rest[i] = median(low, p.restSamples);
            if (s.usable() && s.amps() < p.restAmps) low.addLast(s);
        }
        return rest;
    }
    private static long[] restTimes(List<Sample> samples, Params p, int direction) {
        long[] at = new long[samples.size()];
        long last = Long.MIN_VALUE;
        for (int k = 0; k < samples.size(); k++) {
            int i = direction > 0 ? k : samples.size() - 1 - k;
            Sample s = samples.get(i);
            if (last != Long.MIN_VALUE && Math.abs(s.timeMs - last) > p.restWindowMs) last = Long.MIN_VALUE;
            at[i] = last;
            if (s.usable() && s.amps() < p.restAmps) last = s.timeMs;
        }
        return at;
    }
    private static double interpolate(double a, long at, double b, long bt, long t) {
        boolean hasA = !Double.isNaN(a) && at != Long.MIN_VALUE, hasB = !Double.isNaN(b) && bt != Long.MIN_VALUE;
        if (hasA && hasB && bt > at) return a + (b - a) * (t - at) / (double) (bt - at);
        return hasA ? a : hasB ? b : Double.NaN;
    }
    private static double median(ArrayDeque<Sample> low, int count) {
        if (low.isEmpty()) return Double.NaN;
        List<Double> values = new ArrayList<>(count);
        java.util.Iterator<Sample> it = low.descendingIterator();
        while (it.hasNext() && values.size() < count) values.add(it.next().volts);
        Collections.sort(values);
        return values.get(values.size() / 2);
    }
    private static double resistance(List<Double> samples, Params p) {
        if (samples.size() < p.learnSamples) return p.defaultOhms;
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return Math.max(p.minOhms, Math.min(p.maxOhms, sorted.get(sorted.size() / 2)));
    }
}
