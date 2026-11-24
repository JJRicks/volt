package com.jjricks.volt;

/**
 * "Estimating time left" first (owner, Nov 23 2026: "I'd rather show an 'estimating' first instead of an inaccurate
 * estimate"). Right after plugging in, a UT5 MAX charge's first readings give a time that is far off:
 * <ul>
 * <li>the fast charger starts with a soft start of about 1.3 A for up to a minute or two before its bulk current, and the
 *     only time those readings give is the plain current rate (battery left over that current): 80 to 300 minutes for
 *     a charge that takes 35 to 60;</li>
 * <li>under Auto, before charger detection has seen enough frames (ChargerDetection), the current rate stands in too;</li>
 * <li>in the first minute at bulk current the pack voltage is still settling, so the fast-charge model starts a few
 *     minutes long and comes down quickly.</li>
 * </ul>
 * So the time is held (ChargingEstimate.estimating: no minutes, the "Estimating time left" state) until this charge is
 * ready: its charger is known (chosen or detected) and, on the fast charger, the bulk current has started (at least
 * {@link #FAST_BULK_A}) or the pack is already at the charger's voltage limit, where the taper model places it. On a
 * charge Volt saw begin, one more {@link #SETTLE_MS} follows while the voltage settles. Never longer than
 * {@link #MAX_HOLD_MS} after Volt first sees the charge: a charger stuck in its soft start, or a standard charger read
 * as the fast one, still gets a time then.
 *
 * Only on models with charger detection ({@link ChargerDetection#supports}, the UT5 MAX): elsewhere the charger is
 * never identified, so the hold would be a flat {@link #MAX_HOLD_MS} on every charge, followed by the same current-rate
 * time it held back.
 *
 * The hold is for display and planning only. The charge recording saves what the estimator computed, with its
 * source, so recorded charges can still be scored against the estimator. Fed once per fresh frame, beside the other
 * charge observers; nothing is stored, and a process restart watches the charge again. Instances hold one profile's
 * charge, on the monotonic clock. It is also fed the charging state alone from the reports the live card follows, so
 * a pause and resume between background status reads still starts a new hold.
 */
public final class ChargeEstimateHold {
    /** The fast charger's bulk stage starts here; below it a fast charge is still in its soft start (FastChargeTaper). */
    public static final double FAST_BULK_A = FastChargeTaper.MIN_BULK_AMPS;
    /** At or above this pack voltage plus the cable drop the fast charger is at its voltage limit (ChargeSlowdown's edge). */
    static final double CV_EDGE_V = ChargeSlowdown.CV_EDGE_V;
    /** After a charge Volt saw begin is ready: one more minute while the pack voltage settles. */
    public static final long SETTLE_MS = 60_000L;
    /** The hard cap, from the first charging reading Volt sees of this charge. */
    public static final long MAX_HOLD_MS = 3 * 60_000L;
    /** A not-charging reading this soon before charging means Volt saw the charge begin (ChargeProgressTracker's grace). */
    static final long START_GRACE_MS = ChargeProgressTracker.START_GRACE_MS;
    /** Readings stopping this long ends the watched charge (ChargeProgressTracker's silence). */
    static final long MAX_SILENCE_MS = ChargeProgressTracker.MAX_SILENCE_MS;

    private String profile;
    private boolean active, startObserved, released;
    private long firstSeen, lastSeen, lastNotCharging, readyAt;
    /** The charger in effect when the charge became ready; another one chosen or detected makes it unready again. */
    private ChargeSessionStore.Charger readyCharger;
    /** readyCharger was an explicit choice (the setting, or the recording's label), not detection's. */
    private boolean readyChosen;

    /** Whether this model's estimate is held at plug-in: the models whose charger Volt identifies. */
    public static boolean applies(int productId) { return ChargerDetection.supports(productId); }

    /**
     * Whether a fresh paired reading shows a charge whose time can be shown: the charger is known (chosen or detected),
     * and on the fast charger the bulk current has started or the pack is at the charger's voltage limit already (a
     * charge plugged in near full, which the taper model times). The standard charger's current at plug-in is its bulk.
     */
    public static boolean ready(ChargeSessionStore.Charger charger, double volts, double amps) {
        if (charger == null || !finite(volts) || !finite(amps)) return false;
        if (charger != ChargeSessionStore.Charger.HIGH_SPEED) return true;
        double current = Math.abs(amps);
        return current >= FAST_BULK_A || volts + FastChargeTaper.CV_DROP * current >= CV_EDGE_V;
    }

    public void reset() { profile = null; lastNotCharging = 0; clearCharge(); }

    /** One reading whose electrical values (if any) were taken now: {@link #observe(String, int, Boolean,
     * ChargeSessionStore.Charger, Double, Double, long, long)} with sampleMs = nowMs. */
    public void observe(String profile, int productId, Boolean charging, ChargeSessionStore.Charger charger,
            Double volts, Double amps, long nowMs) {
        observe(profile, productId, charging, charger, volts, amps, nowMs, nowMs);
    }

    /**
     * One reading. charging is the fresh charging state (null changes nothing); a charging report without electrical
     * values (volts and amps null) feeds the transitions alone, so a pause and resume seen only there still starts a new
     * hold. productId is the model's charge reference product; charger the charger in effect for this reading (null: not
     * known yet). volts and amps are a fresh paired reading or null, and sampleMs the time it was taken: a reading from
     * before the last not-charging state (the scooter's report kept the old values) never makes this charge ready.
     * Another charger than the one in effect when the charge became ready (Standard, then High-speed chosen during the
     * soft start) makes it unready again, within the cap. nowMs is the monotonic clock.
     */
    public void observe(String profile, int productId, Boolean charging, ChargeSessionStore.Charger charger,
            Double volts, Double amps, long sampleMs, long nowMs) {
        observe(profile, productId, charging, charger, false, volts, amps, sampleMs, nowMs);
    }

    /**
     * As above; chosen says the charger in effect is an explicit choice (the charger setting, or a running recording's
     * label) rather than detection's. Going from a chosen charger to none in effect (Standard, then Auto, still
     * identifying the charger) is a change too; detection losing its charger for a moment is not.
     */
    public void observe(String profile, int productId, Boolean charging, ChargeSessionStore.Charger charger, boolean chosen,
            Double volts, Double amps, long sampleMs, long nowMs) {
        if (profile == null || profile.isEmpty() || charging == null || nowMs <= 0) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (!applies(productId)) { clearCharge(); return; }
        if (!charging) { lastNotCharging = nowMs; clearCharge(); return; }
        if (active && (nowMs < lastSeen || nowMs - lastSeen > MAX_SILENCE_MS)) clearCharge();
        if (!active) {
            active = true; firstSeen = nowMs;
            startObserved = lastNotCharging > 0 && nowMs - lastNotCharging <= START_GRACE_MS;
        }
        lastSeen = nowMs;
        if (readyCharger != null && charger != readyCharger && (charger != null || readyChosen)) {
            readyAt = 0; readyCharger = null; readyChosen = false; released = false;
        }
        if (released || readyAt > 0) return;
        boolean thisCharge = lastNotCharging <= 0 || sampleMs > lastNotCharging;
        if (thisCharge && volts != null && amps != null && ready(charger, volts, amps)) { readyAt = nowMs; readyCharger = charger; readyChosen = chosen; }
    }

    /** Whether this profile's charge shows "Estimating time left" now instead of a time. */
    public boolean holding(String profile, long nowMs) {
        if (!active || released || profile == null || !profile.equals(this.profile) || nowMs < firstSeen) return false;
        if (nowMs - firstSeen >= MAX_HOLD_MS) { released = true; return false; }
        if (readyAt <= 0) return true;
        if (!startObserved || nowMs - readyAt >= SETTLE_MS) { released = true; return false; }
        return true;
    }

    private void clearCharge() { active = startObserved = released = false; firstSeen = lastSeen = readyAt = 0; readyCharger = null; readyChosen = false; }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
