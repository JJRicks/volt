package com.jjricks.volt;

import java.util.ArrayDeque;

/**
 * UT5 MAX high-speed time to displayed 100% from pack voltage and charging current, replacing a
 * current-rate guess when the SOC-indexed reference and personal curves cannot place this charge.
 *
 * Where the gauge sits when the charger reaches its voltage limit varies (72%, 86% and 91% in
 * observed charges), so this model ignores the percentage. Constant current runs until pack
 * voltage reaches CV_SET - CV_DROP x amps (charger limit less cable drop); the Ah still needed
 * comes from voltage headroom, calibrated on the 16-100% reference charge. After that, current
 * decays nearly exponentially to about 0.68 A, when 100% appears: remaining = tau x ln(I / 0.68).
 * Tau was 13-14 minutes in three charges and 11 in one started near full, so each charge refits
 * it from its own decay. See docs/validation/fast-charge-taper.md.
 *
 * Instances hold one profile's runtime charge state only (watched decay and a briefly held
 * personal finish); nothing is persisted. Callers reset at a charge or scooter boundary.
 */
public final class FastChargeTaper {
    public static final double RATED_AH = 15.3, END_AMPS = 0.68, TAU_MINUTES = 14.1;
    static final double CV_SET = 54.53, CV_DROP = 0.107, CV_VOLTS = 53.3;
    /** Current falling below this share of the highest current seen at CV voltage marks the taper. */
    static final double FALLING = 0.97, MIN_BULK_AMPS = 4, MAX_AMPS = 11.6;
    static final double TAU_LOW = 8, TAU_HIGH = 20, FIT_START = 3, FIT_FULL = 6, FIT_WINDOW = 10;
    public static final long HOLD_MS = 3 * 60_000L, SPACING_MS = 10_000L, MAX_SILENCE_MS = 30 * 60_000L;
    // Reference charge, constant current: volts below the CV entry voltage -> Ah delivered before it.
    static final double[] HEADROOM_VOLTS = {0, .1, .2, .3, .4, .5, .6, .7, .8, .9, 1, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9,
        2, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9,
        4, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 5, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8};
    static final double[] BULK_AH = {.055, .139, .238, .336, .435, .534, .633, .745, .844, .943, 1.055, 1.168, 1.281, 1.394, 1.506, 1.619, 1.732, 1.844, 1.957, 2.070,
        2.183, 2.310, 2.437, 2.549, 2.662, 2.775, 2.902, 3.028, 3.141, 3.268, 3.395, 3.521, 3.662, 3.803, 3.944, 4.085, 4.226, 4.367, 4.522, 4.677,
        4.832, 5.001, 5.156, 5.311, 5.480, 5.649, 5.818, 6.016, 6.241, 6.466, 6.720, 7.015, 7.324, 7.648, 7.971, 8.239, 8.421, 8.534, 8.590};

    private String profile;
    private long first, last, lastSample, heldAt;
    private double cvCeiling, held = Double.NaN;
    private int heldSessions;
    private final ArrayDeque<double[]> decay = new ArrayDeque<>();

    public void reset() { profile = null; clearCharge(); }

    /** charging is the fresh charging state (null changes nothing); volts and amps are a fresh pair or null. */
    public void observe(String profile, Boolean charging, Double volts, Double amps, long nowMs) {
        if (profile == null || profile.isEmpty() || nowMs <= 0 || charging == null) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (!charging || first > 0 && (nowMs < last || nowMs - last > MAX_SILENCE_MS)) clearCharge();
        if (!charging) return;
        if (first == 0) first = nowMs;
        last = nowMs;
        if (volts == null || amps == null || !finite(volts) || !finite(amps)) return;
        double a = Math.abs(amps);
        if (volts < CV_VOLTS || a > MAX_AMPS) return;
        cvCeiling = Math.max(cvCeiling, a);
        if (a >= cvCeiling * FALLING || a <= END_AMPS || lastSample > 0 && nowMs - lastSample < SPACING_MS) return;
        double minutes = (nowMs - first) / 60000d;
        decay.addLast(new double[]{minutes, Math.log(a)});
        lastSample = nowMs;
        while (decay.size() > 2 && decay.peekFirst()[0] < minutes - FIT_WINDOW) decay.removeFirst();
    }

    /** True while this instance is following a charge of that profile. */
    public boolean tracking(String profile) { return first > 0 && profile != null && profile.equals(this.profile); }

    /** The current is falling at the charger's voltage limit: the taper has started. */
    boolean tapering(double volts, double amps) {
        return cvCeiling > 0 && volts >= CV_VOLTS && amps < cvCeiling * FALLING;
    }

    /** The reference tau until three minutes of decay are watched, this charge's own by six. */
    public double tau() {
        if (decay.size() < 3) return TAU_MINUTES;
        double sx = 0, sy = 0, sxx = 0, sxy = 0, start = decay.peekFirst()[0], end = decay.peekLast()[0];
        int n = decay.size();
        for (double[] p : decay) { sx += p[0]; sy += p[1]; sxx += p[0] * p[0]; sxy += p[0] * p[1]; }
        double span = end - start, slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        if (span < FIT_START || !(slope < 0)) return TAU_MINUTES;
        double fitted = Math.max(TAU_LOW, Math.min(TAU_HIGH, -1 / slope));
        return TAU_MINUTES + (fitted - TAU_MINUTES) * Math.min(1, (span - FIT_START) / (FIT_FULL - FIT_START));
    }

    /** A personal finish shown now; a dropout within HOLD_MS keeps counting it down. */
    void rememberPersonal(double minutes, int sessions, long nowMs) {
        if (!finite(minutes) || minutes < 0 || nowMs <= 0) return;
        held = minutes; heldSessions = sessions; heldAt = nowMs;
    }

    /** {minutes, sessions} of a held personal countdown, or null once it has expired. */
    double[] heldPersonal(long nowMs) {
        if (!finite(held) || nowMs < heldAt || nowMs - heldAt > HOLD_MS) return null;
        return new double[]{Math.max(0, held - (nowMs - heldAt) / 60000d), heldSessions};
    }

    /**
     * Minutes to displayed 100%, or null outside the calibrated pack and current range. state may
     * be null (no watched decay): the reference tau applies and only voltage headroom marks CV.
     */
    public static Double minutes(double volts, double amps, double ratedAh, FastChargeTaper state) {
        if (!finite(volts) || !finite(amps) || !finite(ratedAh) || Math.abs(ratedAh - RATED_AH) > RATED_AH * .1
                || amps <= 0 || amps > MAX_AMPS || volts <= 0) return null;
        double tau = state == null ? TAU_MINUTES : state.tau();
        double tail = amps <= END_AMPS ? 0 : tau * Math.log(amps / END_AMPS);
        double headroom = CV_SET - CV_DROP * amps - volts;
        if (headroom <= 0 || state != null && state.tapering(volts, amps)) return tail;
        if (amps < MIN_BULK_AMPS) return null; // Not a high-speed constant-current stage.
        Double ah = bulkAh(headroom);
        return ah == null ? null : ah / amps * 60 + tail;
    }

    /** Ah delivered at constant current before CV; null below the calibrated range. */
    static Double bulkAh(double headroom) {
        if (!finite(headroom)) return null;
        if (headroom <= 0) return 0d;
        int n = HEADROOM_VOLTS.length;
        if (headroom > HEADROOM_VOLTS[n - 1]) return null;
        int i = 1;
        while (headroom > HEADROOM_VOLTS[i]) i++;
        double f = (headroom - HEADROOM_VOLTS[i - 1]) / (HEADROOM_VOLTS[i] - HEADROOM_VOLTS[i - 1]);
        return BULK_AH[i - 1] + f * (BULK_AH[i] - BULK_AH[i - 1]);
    }

    private void clearCharge() {
        first = last = lastSample = heldAt = 0; cvCeiling = 0; held = Double.NaN; heldSessions = 0; decay.clear();
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
