package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;

/**
 * The regen inference on synthesized readings. Shapes follow what was measured on ride recordings
 * (one reading a second, voltage and current measured together), but every value is made up here.
 */
public final class RegenInferenceTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    static final double REST = 50, OHMS = 0.08;

    /** An unsigned sample: draw sags below the rest voltage, regen rises above it. */
    private static RegenInference.Sample at(long seconds, double kmh, double amps, boolean regen) {
        double volts = REST + (regen ? amps * OHMS : -amps * OHMS);
        return new RegenInference.Sample(seconds * 1000, amps * volts, volts, kmh);
    }
    private static RegenInference.Sample sagged(long seconds, double kmh, double amps, double volts) {
        return new RegenInference.Sample(seconds * 1000, amps * volts, volts, kmh);
    }

    /** A ride: rest, acceleration to learn the resistance, a cruise, then a brake with regen. */
    private static List<RegenInference.Sample> ride() {
        List<RegenInference.Sample> s = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 6; i++) s.add(at(t++, 0, 0.2, false));
        double kmh = 0;
        for (int i = 0; i < 8; i++) { kmh += 3; s.add(at(t++, kmh, 18, false)); }
        for (int i = 0; i < 8; i++) s.add(at(t++, kmh, 0.4, false));
        for (int i = 0; i < 6; i++) { kmh -= 3.2; s.add(at(t++, kmh, 8, true)); }
        for (int i = 0; i < 4; i++) s.add(at(t++, kmh, 0.3, false));
        return s;
    }

    public static void main(String[] args) {
        wholeRideFindsBrake();
        liveFindsBrake();
        liveNeverLooksAhead();
        accelerationStaysDraw();
        slowingDrawWithSaggingVoltageIsNotRegen();
        strongReadingNeverFlips();
        kineticBoundStopsWeakDeceleration();
        nearStandstillAndWeakCurrentStayUnknown();
        noRestingVoltageStaysUnknown();
        unusableSamplesStayUnknown();
        extensionKeepsTheLimits();
        System.out.println("RegenInferenceTest passed " + checks + " checks");
    }

    /** Rest, an acceleration for the resistance, a cruise, then a gentle slowing where seconds 3 and 4 of it hold the seed and its neighbour. */
    private static List<RegenInference.Sample> seedAndNeighbour(double neighbourKmh, double neighbourAmps) {
        List<RegenInference.Sample> s = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 6; i++) s.add(at(t++, 0, 0.2, false));
        double kmh = 0;
        for (int i = 0; i < 8; i++) { kmh += 3; s.add(at(t++, kmh, 18, false)); }
        for (int i = 0; i < 8; i++) s.add(at(t++, kmh, 0.4, false));
        s.add(at(t++, 21.2, 0.4, false));
        s.add(at(t++, 20.4, 0.4, false));
        s.add(at(t++, 20, 4, true)); // the seed: index 24
        s.add(sagged(t++, neighbourKmh, neighbourAmps, REST)); // its neighbour: index 25
        s.add(at(t++, 19.1, 0.4, false));
        s.add(at(t++, 19, 0.4, false));
        return s;
    }

    /** A weak unknown reading can join a neighbouring regen one, but the physics still apply to it. */
    private static void extensionKeepsTheLimits() {
        List<RegenInference.Sample> fits = seedAndNeighbour(19.9, 2.5);
        for (RegenInference.Label[] labels : bothModes(fits)) {
            check(labels[24] == RegenInference.Label.REGEN, "the seed is regen: " + labels[24]);
            check(labels[25] == RegenInference.Label.REGEN, "a neighbour within the limits joins it: " + labels[25]);
        }
        for (RegenInference.Label[] labels : bothModes(seedAndNeighbour(19.9, 8)))
            check(labels[24] == RegenInference.Label.REGEN && labels[25] != RegenInference.Label.REGEN, "400 W is more than the gentle slowing can supply: " + labels[25]);
        for (RegenInference.Label[] labels : bothModes(seedAndNeighbour(1, 2.5)))
            check(labels[24] == RegenInference.Label.REGEN && labels[25] != RegenInference.Label.REGEN, "1 km/h is under the minimum speed: " + labels[25]);
    }
    private static List<RegenInference.Label[]> bothModes(List<RegenInference.Sample> s) {
        RegenInference.Live live = new RegenInference.Live();
        RegenInference.Label[] past = new RegenInference.Label[s.size()];
        for (int i = 0; i < past.length; i++) past[i] = live.accept(s.get(i));
        return java.util.Arrays.asList(RegenInference.wholeRide(s), past);
    }

    private static int regenCount(RegenInference.Label[] labels, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) if (labels[i] == RegenInference.Label.REGEN) n++;
        return n;
    }

    private static void wholeRideFindsBrake() {
        List<RegenInference.Sample> s = ride();
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        check(regenCount(labels, 22, 28) >= 4, "the brake is regen (the last, slowest samples are beyond what the deceleration can supply): " + regenCount(labels, 22, 28));
        check(regenCount(labels, 0, 22) == 0, "nothing before it is");
    }

    private static void liveFindsBrake() {
        List<RegenInference.Sample> s = ride();
        RegenInference.Live live = new RegenInference.Live();
        RegenInference.Label[] labels = new RegenInference.Label[s.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = live.accept(s.get(i));
        check(regenCount(labels, 22, 28) >= 3, "live finds the brake once speed has fallen: " + regenCount(labels, 22, 28));
        check(regenCount(labels, 0, 22) == 0, "and not before it");
    }

    private static void liveNeverLooksAhead() {
        List<RegenInference.Sample> s = ride();
        RegenInference.Live a = new RegenInference.Live(), b = new RegenInference.Live();
        RegenInference.Label[] first = new RegenInference.Label[s.size()];
        for (int i = 0; i < first.length; i++) first[i] = a.accept(s.get(i));
        for (int i = 0; i < 24; i++) check(b.accept(s.get(i)) == first[i], "a label is decided from what came before it, at " + i);
    }

    private static void accelerationStaysDraw() {
        List<RegenInference.Sample> s = ride();
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        for (int i = 6; i < 14; i++) check(labels[i] == RegenInference.Label.DRAW, "rising speed is draw, at " + i);
    }

    private static void slowingDrawWithSaggingVoltageIsNotRegen() {
        // Riding on the throttle up a hill: speed falls and the voltage sags, so it is draw.
        List<RegenInference.Sample> s = new ArrayList<>(ride().subList(0, 22));
        long t = 22;
        for (int i = 0; i < 6; i++) s.add(at(t++, 24 - i * 2.0, 10, false));
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        check(regenCount(labels, 22, 28) == 0, "sagging voltage is not regen");
    }

    private static void strongReadingNeverFlips() {
        List<RegenInference.Sample> s = new ArrayList<>(ride().subList(0, 22));
        long t = 22;
        double volts = REST + 1;
        for (int i = 0; i < 6; i++) s.add(new RegenInference.Sample(t++ * 1000, 1300, volts, 24 - i * 3.0));
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        for (int i = 22; i < 28; i++) check(labels[i] == RegenInference.Label.DRAW, "1,300 W is draw even with the voltage up, at " + i);
    }

    private static void kineticBoundStopsWeakDeceleration() {
        // 900 W while coasting down at 0.8 km/h/s: the deceleration can't supply that.
        List<RegenInference.Sample> s = new ArrayList<>(ride().subList(0, 22));
        long t = 22;
        for (int i = 0; i < 6; i++) s.add(sagged(t++, 20 - i * 0.8, 18, REST + 0.2));
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        check(regenCount(labels, 22, 28) == 0, "more power than the deceleration has is not regen");
    }

    private static void nearStandstillAndWeakCurrentStayUnknown() {
        List<RegenInference.Sample> s = new ArrayList<>(ride().subList(0, 22));
        long t = 22;
        s.add(at(t++, 2.5, 8, true)); // too slow
        s.add(sagged(t++, 1.0, 8, REST + 0.5));
        s.add(at(t++, 15, 0.6, true)); // too weak, on either side of a brake
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        check(regenCount(labels, 22, 25) == 0, "too slow or too weak to call");
    }

    private static void noRestingVoltageStaysUnknown() {
        // A recording that starts already braking has no resting voltage yet.
        List<RegenInference.Sample> s = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 5; i++) s.add(at(t++, 30 - i * 3.0, 8, true));
        RegenInference.Label[] labels = RegenInference.wholeRide(s);
        check(regenCount(labels, 0, 5) == 0, "without a resting voltage nothing is called regen");
    }

    private static void unusableSamplesStayUnknown() {
        RegenInference.Label[] labels = RegenInference.wholeRide(java.util.Arrays.asList(
                new RegenInference.Sample(0, Double.NaN, 50, 10), new RegenInference.Sample(1000, 200, Double.NaN, 10),
                new RegenInference.Sample(2000, 200, 50, Double.NaN)));
        for (RegenInference.Label l : labels) check(l == RegenInference.Label.UNKNOWN, "a sample missing a reading is unknown");
    }
}
