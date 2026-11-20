package com.jjricks.volt;

/**
 * Automatic's charger family from this scooter's own learned curves, for charges ChargerDetection cannot place: every
 * model but the UT5 MAX, and a UT5 MAX on a charger outside its two known envelopes. Owner, Nov 19: "the next time they
 * plug in and record again, its with the high speed, that records as a separate curve, and then next time they plug in
 * the appropriate one is selected for the given wattage". Pure Java; nothing is persisted here.
 *
 * Evidence: a fresh paired frame's battery-side power against each family's saved power at this 1% battery bin
 * (Curve.familyPowerMatch: powerMatch's 65-135% band on Curve.familyWatts, which one recording already gives). Below
 * {@link #SETTLE_SOC} a frame names a family when exactly one curve matches, or when both match and the live power is
 * clearly closer to one ({@link #closer}). From {@link #SETTLE_SOC} up, where tapers of different chargers overlap, a
 * frame names a family only when it lies within {@link #CLEAR_BAND} of that curve and the other family's curve is saved
 * there and does not match: so a charge first seen near full with one family recorded stays unknown. No saved power at
 * this level (UNKNOWN for every curve) names nothing.
 *
 * Memory: a family is settled by {@link ChargerDetection#MIN_EVIDENCE_FRAMES} distinct frames over
 * {@link ChargerDetection#MIN_EVIDENCE_MS}, and remembered for this profile's physical charge with ChargerDetection's
 * rules: a scooter change, charging stopping, the battery level falling by a point, the clock going back or
 * {@link ChargerDetection#RETAIN_MS} without a usable frame forget it. Below {@link #SETTLE_SOC} a sustained contrary
 * run (the settled curve DIFFERENT, or the other one naming itself, for as many frames over as long) forgets it; at and
 * above, nothing but those boundaries does, so the choice never flips frame by frame in the taper. After a link drop the
 * first usable frame keeps it only when the settled curve does not differ there and, from {@link #SETTLE_SOC} up, that
 * frame on its own names the same family (a swap while disconnected is otherwise indistinguishable).
 *
 * ScooterService uses it only after ChargerDetection (detectedCharger), so the UT5 MAX keeps its physical rules, and
 * only under Automatic: the charger setting and a recording's label still win (chargerBasis). Simulation never feeds it.
 */
public final class LearnedChargerMatch {
    /** From here a new frame needs a clear, two-sided match; a settled family is never contradicted. */
    public static final double SETTLE_SOC = 90;
    /** Near full: live power within 15% of the one curve that matches. */
    public static final double CLEAR_BAND = .15;
    /** Both curves matching: the closer one (by power ratio) must be at most half as far off as the other... */
    static final double CLOSER_FACTOR = .5;
    /** ...and the two saved curves must differ by at least 25% at this level, or nothing tells them apart. */
    static final double DISTINCT_CURVES = 1.25;

    private String profile;
    private ChargeSessionStore.Charger selected, candidate;
    private long lastFrame, lastNow, lastValid, candidateStarted, contraryStarted;
    private int candidateFrames, contraryFrames;
    private boolean relinked;
    private double lastSoc = Double.NaN;

    /** Clears the match at a scooter, charging-state or simulation boundary (with ChargerDetection's reset). */
    public void reset() {
        profile = null;
        selected = null;
        lastFrame = lastNow = lastValid = 0;
        lastSoc = Double.NaN;
        relinked = false;
        clearCandidate();
        clearContrary();
    }

    /** The link dropped; the charger may have been swapped before the next usable frame. */
    public void disconnected() { relinked = true; }

    /** Null unless this profile's charge settled on a family within RETAIN_MS. */
    public ChargeSessionStore.Charger selected(String profile, long nowMs) {
        return selected != null && profile != null && profile.equals(this.profile)
                && nowMs >= lastValid && nowMs - lastValid <= ChargerDetection.RETAIN_MS ? selected : null;
    }

    /**
     * One reading. frameMs identifies the paired voltage/current frame, nowMs the same monotonic clock; null readings
     * mark an unusable frame (no evidence, the charge goes on). stock and fast are this profile's learned curves (null:
     * none saved). Returns the settled family or null.
     */
    public ChargeSessionStore.Charger observe(String profile, boolean charging, Double soc, Double volts, Double amps,
            long frameMs, long nowMs, ChargeSessionStore.Curve stock, ChargeSessionStore.Curve fast) {
        if (profile == null || profile.isEmpty() || nowMs <= 0) return null;
        if (!charging || !profile.equals(this.profile) || nowMs < lastNow
                || lastValid > 0 && nowMs - lastValid > ChargerDetection.RETAIN_MS
                || between(soc, 0, 100) && soc <= lastSoc - 1) reset();
        if (!charging) return null;
        this.profile = profile;
        lastNow = nowMs;
        if (!between(soc, 0, 100) || !between(volts, 10, 120) || amps == null || !Double.isFinite(amps)
                || Math.abs(amps) <= 0 || Math.abs(amps) > 40 || frameMs <= 0 || frameMs > nowMs || frameMs < lastFrame) {
            clearCandidate();
            clearContrary();
            return selected;
        }
        if (frameMs == lastFrame) return selected;
        if (lastFrame > 0 && frameMs - lastFrame > ChargerDetection.MAX_CONTINUITY_GAP_MS) { clearCandidate(); clearContrary(); }
        lastFrame = frameMs;
        lastValid = nowMs;
        lastSoc = soc;

        double watts = volts * Math.abs(amps);
        ChargeSessionStore.Charger evidence = evidence(soc, watts, stock, fast);
        if (evidence == null || evidence == selected) clearCandidate();
        else if (candidate != evidence) { candidate = evidence; candidateStarted = frameMs; candidateFrames = 1; }
        else candidateFrames = Math.min(ChargerDetection.MIN_EVIDENCE_FRAMES, candidateFrames + 1);
        // Whether the settled family's own curve rules this frame out (a curve no longer saved does too).
        ChargeSessionStore.Curve own = selected == ChargeSessionStore.Charger.STOCK ? stock : fast;
        boolean differs = selected != null && (own == null
                || own.familyPowerMatch(soc, watts) == ChargeSessionStore.Curve.PowerMatch.DIFFERENT);
        if (relinked && selected != null && (differs || soc >= SETTLE_SOC && evidence != selected)) { selected = null; clearContrary(); }
        relinked = false;
        if (selected != null) {
            boolean contrary = soc < SETTLE_SOC && (differs || evidence != null && evidence != selected);
            if (!contrary) { clearContrary(); return selected; }
            if (contraryFrames == 0) contraryStarted = frameMs;
            contraryFrames = Math.min(ChargerDetection.MIN_EVIDENCE_FRAMES, contraryFrames + 1);
            if (contraryFrames < ChargerDetection.MIN_EVIDENCE_FRAMES || frameMs - contraryStarted < ChargerDetection.MIN_EVIDENCE_MS) return selected;
            selected = null;
            clearContrary();
        }
        if (candidate != null && candidateFrames >= ChargerDetection.MIN_EVIDENCE_FRAMES
                && frameMs - candidateStarted >= ChargerDetection.MIN_EVIDENCE_MS) {
            selected = candidate;
            clearCandidate();
        }
        return selected;
    }

    /** The family one frame names, or null (see the class comment). */
    static ChargeSessionStore.Charger evidence(double soc, double watts,
            ChargeSessionStore.Curve stock, ChargeSessionStore.Curve fast) {
        Double s = stock == null ? null : stock.familyWatts(soc), f = fast == null ? null : fast.familyWatts(soc);
        boolean stockMatch = s != null && stock.familyPowerMatch(soc, watts) == ChargeSessionStore.Curve.PowerMatch.MATCH;
        boolean fastMatch = f != null && fast.familyPowerMatch(soc, watts) == ChargeSessionStore.Curve.PowerMatch.MATCH;
        if (soc >= SETTLE_SOC) {
            // Near full only a clear, two-sided answer: within the tight band of one curve, and the other saved and different.
            if (stockMatch && !fastMatch && f != null && within(watts, s, CLEAR_BAND)) return ChargeSessionStore.Charger.STOCK;
            if (fastMatch && !stockMatch && s != null && within(watts, f, CLEAR_BAND)) return ChargeSessionStore.Charger.HIGH_SPEED;
            return null;
        }
        if (stockMatch && fastMatch) return closer(watts, s, f);
        return stockMatch ? ChargeSessionStore.Charger.STOCK : fastMatch ? ChargeSessionStore.Charger.HIGH_SPEED : null;
    }

    /** Both curves match: the clearly closer family, or null when the curves are too alike or the power sits between. */
    static ChargeSessionStore.Charger closer(double watts, double stockWatts, double fastWatts) {
        if (Math.max(stockWatts, fastWatts) < Math.min(stockWatts, fastWatts) * DISTINCT_CURVES) return null;
        double toStock = Math.abs(Math.log(watts / stockWatts)), toFast = Math.abs(Math.log(watts / fastWatts));
        if (toStock <= toFast * CLOSER_FACTOR) return ChargeSessionStore.Charger.STOCK;
        if (toFast <= toStock * CLOSER_FACTOR) return ChargeSessionStore.Charger.HIGH_SPEED;
        return null;
    }

    private static boolean within(double watts, double saved, double band) {
        return watts >= saved * (1 - band) && watts <= saved * (1 + band);
    }
    private void clearCandidate() { candidate = null; candidateStarted = 0; candidateFrames = 0; }
    private void clearContrary() { contraryStarted = 0; contraryFrames = 0; }
    private static boolean between(Double value, double low, double high) {
        return value != null && Double.isFinite(value) && value >= low && value <= high;
    }
}
