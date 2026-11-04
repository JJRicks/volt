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
    /** A decay sample must set a new low by this much; a fitted tau beyond FLAT_TAU is a plateau, not a taper. */
    static final double NEW_LOW_AMPS = .005, FLAT_TAU = 60;
    /**
     * The newest decay point is recent enough for ownTaper. At FLAT_TAU near END_AMPS a real decay
     * still sets a 0.01 A reading step lower about every 0.9 minutes (owner charges: every 0.8 at
     * most); twice that allows for noise, then a plateau or stopped decay hands back to v7.
     */
    static final long RECENT_MS = 2 * 60_000L;
    /**
     * ownTaper's check that the decay is still going at the estimate's pace: the decay points of
     * the last RECENT_FIT minutes, fitted alone, may be at most SLOWER slower than tau(). A slow
     * taper that tau() clamps, or a stopped decay that creeps on in small new lows, fails it. If
     * the decay keeps that recent pace, the time shown is at most SLOWER of itself short; a taper
     * that keeps slowing can read shorter. Owner charges: at most 12% slower (RECENT_FIT minutes
     * hold at least 6 points there).
     */
    static final double RECENT_FIT = 3, SLOWER = .25;
    /**
     * A fall between consecutive decay points faster than a decay of this tau (minutes) is a
     * charger's step down, not a taper. Owner charges, while their own taper applied: no fall
     * faster than 5.6 minutes within RECENT_FIT; 0.03 A of reading noise on a constructed decay
     * gave 4.7, a constructed 4.3 to 2.5 A step down in 30 s 0.8 to 1.1.
     */
    static final double STEP_TAU = 2;
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
    private double cvCeiling, lowest, held = Double.NaN;
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
        // Only sustained decay is taper evidence: a charger's current step down, then a flat
        // plateau (10 -> 8 A, still below that current's CV voltage) must not fit a taper rate.
        if (lowest > 0 && a > lowest - NEW_LOW_AMPS) return;
        lowest = a;
        double minutes = (nowMs - first) / 60000d;
        decay.addLast(new double[]{minutes, Math.log(a)});
        lastSample = nowMs;
        while (decay.size() > 2 && decay.peekFirst()[0] < minutes - FIT_WINDOW) decay.removeFirst();
    }

    /** True while this instance is following a charge of that profile. */
    public boolean tracking(String profile) { return first > 0 && profile != null && profile.equals(this.profile); }

    /** The current keeps falling at the charger's voltage limit: the taper has started. */
    boolean tapering(double volts, double amps) {
        return cvCeiling > 0 && decay.size() >= 2 && volts >= CV_VOLTS && amps < cvCeiling * FALLING;
    }

    /**
     * The charge's own taper is established and still decaying at its fitted pace now: it is
     * tapering at these readings, its watched decay points (at least three) span at least FIT_FULL
     * minutes (from which tau() uses this charge's fit alone rather than blending in the
     * reference), that fit is usable (a real decay, not a plateau that tau() replaces with the
     * reference), the newest point is no older than RECENT_MS at nowMs, the last RECENT_FIT
     * minutes of points (at least three) fall at least as fast as tau() x (1 + SLOWER), and none
     * of those points fell from the one before faster than STEP_TAU. Decay points are only added and pruned at a new low, so a
     * plateau leaves old points in place (the age check retires them) and a decay that stops but
     * creeps on in 0.01 A lows keeps the whole fit recent; within RECENT_FIT minutes of the stop
     * the recent points are only that creep, too slow for the check. A real taper slower than
     * TAU_HIGH, which tau() clamps, fails the same check. A charger's step down steepens both
     * fits, down to tau()'s TAU_LOW clamp, while the slower decay after it is too new to fail the
     * pace check; the step check hands back until the step leaves the RECENT_FIT window. A decay
     * reported in 2.5-minute steps never has three points in that window. ChargingEstimate then
     * lets this taper, not the battery percentage, place the finish; otherwise v7's choice stands.
     */
    boolean ownTaper(double volts, double amps, long nowMs) {
        if (!tapering(volts, amps) || decay.size() < 3 || decay.peekLast()[0] - decay.peekFirst()[0] < FIT_FULL
                || !finite(fittedTau()) || nowMs < lastSample || nowMs - lastSample > RECENT_MS) return false;
        double from = decay.peekLast()[0] - RECENT_FIT;
        double[] recent = line(from);
        // Decay points are strictly falling new lows at increasing times, so a fit of three always
        // falls; recent[0] < 0 stays so that a rising fit's negative -1 / slope never passes as fast.
        return recent[1] >= 3 && recent[0] < 0 && -1 / recent[0] <= tau() * (1 + SLOWER) && !stepped(from);
    }

    /** A fall between consecutive decay points from that minute on faster than a STEP_TAU decay. */
    private boolean stepped(double from) {
        double[] previous = null;
        for (double[] p : decay) if (p[0] >= from) {
            if (previous != null && previous[1] - p[1] > (p[0] - previous[0]) / STEP_TAU) return true;
            previous = p;
        }
        return false;
    }

    /** The reference tau until three minutes of decay are watched, this charge's own by six. */
    public double tau() {
        double fitted = fittedTau();
        if (!finite(fitted)) return TAU_MINUTES;
        double span = decay.peekLast()[0] - decay.peekFirst()[0];
        return TAU_MINUTES + (Math.max(TAU_LOW, Math.min(TAU_HIGH, fitted)) - TAU_MINUTES) * Math.min(1, (span - FIT_START) / (FIT_FULL - FIT_START));
    }

    /**
     * The decay's least-squares tau before clamping, or NaN without a usable fit: under three
     * points or FIT_START minutes, no fall, or one slower than FLAT_TAU (a plateau, not a taper).
     */
    double fittedTau() {
        if (decay.size() < 3) return Double.NaN;
        double slope = line(decay.peekFirst()[0])[0], span = decay.peekLast()[0] - decay.peekFirst()[0];
        return span < FIT_START || !(slope < 0) || -1 / slope > FLAT_TAU ? Double.NaN : -1 / slope;
    }

    /** {slope, points} of the least-squares line of ln(amps) on charge minutes through the decay points from that minute on. */
    private double[] line(double from) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        for (double[] p : decay) if (p[0] >= from) { n++; sx += p[0]; sy += p[1]; sxx += p[0] * p[0]; sxy += p[0] * p[1]; }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        return new double[]{slope, n};
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
        first = last = lastSample = heldAt = 0; cvCeiling = lowest = 0; held = Double.NaN; heldSessions = 0; decay.clear();
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
