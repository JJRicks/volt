package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A scooter's learned usable capacity (usable-capacity-v1, owner, Nov 19 2026: "anything to improve estimates for every
 * scooter"): the charge its battery gauge takes per percent below BULK_LIMIT, learned from its own charge recordings on
 * any model. The current-rate estimate assumes the rated capacity fills the gauge's 0–100%; a battery controller can
 * map a smaller part of the pack there, and then every current-rate time reads long by the same factor. No Android API.
 *
 * Per recording: the whole-percent steps below BULK_LIMIT (entered by a step up from the level below, left by a step up
 * to the next, continuous), each step's charge the integral of |current| over it. A recording counts with at least
 * MIN_LEVELS such steps; its value is its charge over its steps (ampere-hours per 100 gauge points). A fast charger's
 * steps with the battery outside the learning temperatures don't count (as for the learned curve).
 *
 * Across recordings: a recording agrees when it is within AGREEMENT of the recordings' median (two recordings: within
 * AGREEMENT of each other, since both always lie near their own midpoint). Two or more that agree give a robust value (their mean); a single recording gives a
 * provisional one; two or more without two that agree give none. The
 * value is used below BULK_LIMIT in place of the rated capacity, clamped to MIN_SHARE–1 times rated, and only where it
 * is below DEAD_BAND times rated (a gauge that holds its rating keeps the rated time exactly). Above BULK_LIMIT the rated
 * capacity stays, the taper's cushion. Charge done by plans with the rated time (ChargingEstimate.withUsableCapacity).
 */
public final class UsableCapacity {
    /** Steps below this level only: above it a charger's taper and the gauge's end behaviour vary. */
    public static final double BULK_LIMIT = 80;
    public static final int MIN_LEVELS = 10;
    /**
     * A recording agrees within 5% of the recordings' median (two: of each other). One step's charge varies about 14% around its level's (a gauge's
     * jitter), so a 10-step recording's own value is good to about 4.5%; 5% keeps honest short recordings and still
     * rejects one that measured a different battery.
     */
    public static final double AGREEMENT = .05;
    public static final double MIN_SHARE = .8, DEAD_BAND = .97;
    /** The battery temperatures (C, inclusive) a fast charger's steps count at: the learned curve's window. */
    static final double MIN_C = 20, MAX_C = 38;

    private UsableCapacity() {}

    /** What the recordings say: ampere-hours per 100 gauge points, from how many recordings, and whether only one. */
    public static final class Learned {
        public final double ahPer100;
        public final int recordings;
        public final boolean provisional;
        Learned(double ahPer100, int recordings, boolean provisional) {
            this.ahPer100 = ahPer100; this.recordings = recordings; this.provisional = provisional;
        }
    }

    /** A fast charger's step counts only within MIN_C–MAX_C battery temperature; none reported excludes nothing. */
    static boolean countsAt(boolean fast, Double batteryC) {
        return !fast || batteryC == null || !Double.isFinite(batteryC) || batteryC >= MIN_C && batteryC <= MAX_C;
    }

    /**
     * Each recording's {ampere-hours, steps} below BULK_LIMIT; those with under MIN_LEVELS steps are ignored. Null when
     * none counts, or when two or more count but no two agree.
     */
    public static Learned combine(List<double[]> recordings) {
        List<Double> values = new ArrayList<>();
        if (recordings != null) for (double[] r : recordings)
            if (r != null && r.length >= 2 && r[1] >= MIN_LEVELS && Double.isFinite(r[0]) && r[0] > 0) values.add(r[0] / r[1] * 100);
        if (values.isEmpty()) return null;
        if (values.size() == 1) return new Learned(values.get(0), 1, true);
        double sum = 0; int agreeing = 0;
        if (values.size() == 2) {
            double a = values.get(0), b = values.get(1);
            return Math.abs(a - b) <= Math.min(a, b) * AGREEMENT ? new Learned((a + b) / 2, 2, false) : null;
        }
        double median = median(values);
        for (double value : values) if (Math.abs(value - median) <= median * AGREEMENT) { sum += value; agreeing++; }
        return agreeing >= 2 ? new Learned(sum / agreeing, agreeing, false) : null;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    /**
     * The ampere-hours per 100 gauge points to use below BULK_LIMIT for this rated capacity: the learned value clamped
     * to MIN_SHARE–1 times rated, or null (use the rated capacity) without one or where it is DEAD_BAND times rated or more.
     */
    public static Double usable(Learned learned, double ratedAh) {
        if (learned == null || !Double.isFinite(ratedAh) || ratedAh <= 0 || !Double.isFinite(learned.ahPer100)) return null;
        if (learned.ahPer100 >= ratedAh * DEAD_BAND) return null;
        return Math.max(ratedAh * MIN_SHARE, learned.ahPer100);
    }

    /**
     * Minutes from soc to target at amps: below BULK_LIMIT at usableAh per 100 gauge points, above it at ratedAh. Null
     * for an unusable input.
     */
    public static Double minutes(double soc, double target, double amps, double usableAh, double ratedAh) {
        if (!(amps > 0) || !Double.isFinite(soc) || !Double.isFinite(target)) return null;
        double bulk = Math.max(0, Math.min(target, BULK_LIMIT) - soc), rest = Math.max(0, target - Math.max(soc, BULK_LIMIT));
        double result = (bulk * usableAh + rest * ratedAh) / 100 / amps * 60;
        return Double.isFinite(result) && result >= 0 ? result : null;
    }
}
