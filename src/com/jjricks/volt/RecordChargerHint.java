package com.jjricks.volt;

/**
 * "Different charger? Record it": when this charge's power is wildly off the only charger family this scooter has
 * recordings for. Owner, Nov 19: "if the power is wildly off the known curve (like say i recorded with stock and then
 * plugged in high speed and its a delta of like 400W) a warning saying 'hey you should record with this charger too'".
 * Pure Java: the service feeds it fresh paired frames and the learned curves; it reads no clock or setting itself.
 *
 * Far off ({@link #farOff}): outside the learned curves' match band (65-135%) and at least {@link #FAR_ABOVE} times or at
 * most {@link #FAR_BELOW} times the family's saved power at this 1% battery level (Curve.familyWatts, one recording
 * already gives it), and at least {@link #MIN_APART_W} apart. A stock charge against a high-speed one is 3-5 times apart;
 * a cold or warm battery's 20-30% is well inside, and a small model's 2 A against 4 A charger (about twice, 80 W apart)
 * still counts.
 *
 * Sustained ({@link #observe}): every usable frame with saved power at this level far off in the same direction for
 * {@link #SUSTAIN_MS}, counting no frame in the first {@link #SETTLE_MS} of the charge or after a link drop or a gap
 * longer than ChargerDetection.MAX_CONTINUITY_GAP_MS, and none at or above {@link #MAX_SOC}% (tapers overlap). A frame
 * with power inside the far limits restarts the run; a frame with no saved power at its level neither counts nor breaks
 * it. Exactly one family must have learned recordings for this profile; with none or both, nothing is far off.
 *
 * Shown ({@link #show}): sustained, still charging, no charge recording running, and no charging slowdown reported (a
 * throttled charge runs at 40-80% of nominal and must not read as a different charger). The charger setting does not
 * matter: under Automatic or a manual setting alike the hint names a charger that has not been recorded.
 *
 * The run's direction also gives automatic recordings a family under Automatic ({@link #otherFamily}): far above a stock
 * curve is the high-speed charger, far below a high-speed curve the stock one (ChargeAutoRecordPolicy.detectedLabel).
 */
public final class RecordChargerHint {
    public static final double FAR_ABOVE = 1.8, FAR_BELOW = .5, MIN_APART_W = 50;
    /** The match band of Curve.powerMatch and familyPowerMatch. */
    static final double MATCH_LOW = .65, MATCH_HIGH = 1.35;
    public static final long SETTLE_MS = 60_000L, SUSTAIN_MS = 60_000L;
    public static final double MAX_SOC = 90;

    private String profile;
    private long settleFrom, lastFrame, lastNow, lastValid, runStart, runLast;
    private ChargeSessionStore.Charger recorded;
    private int direction;
    private boolean relinked;
    private double lastSoc = Double.NaN;

    /** Whether live power is far off the saved power at the same battery level (see the class comment). */
    public static boolean farOff(double saved, double live) {
        if (!Double.isFinite(saved) || !Double.isFinite(live) || saved < 1 || live < 1) return false;
        if (live >= saved * MATCH_LOW && live <= saved * MATCH_HIGH) return false;
        return (live >= saved * FAR_ABOVE || live <= saved * FAR_BELOW) && Math.abs(live - saved) >= MIN_APART_W;
    }

    /**
     * The family a far-off run points to: far above the stock curve, the high-speed charger; far below the high-speed
     * curve, the stock one; otherwise (far below stock, far above high-speed) no known family, null.
     */
    public static ChargeSessionStore.Charger otherFamily(ChargeSessionStore.Charger recorded, int direction) {
        if (recorded == ChargeSessionStore.Charger.STOCK && direction > 0) return ChargeSessionStore.Charger.HIGH_SPEED;
        if (recorded == ChargeSessionStore.Charger.HIGH_SPEED && direction < 0) return ChargeSessionStore.Charger.STOCK;
        return null;
    }

    /** Whether to show the tip now. sustained is {@link #sustained}; slowed is the charging-slowed detector's report. */
    public static boolean show(boolean charging, boolean sustained, boolean recording, boolean slowed) {
        return charging && sustained && !recording && !slowed;
    }

    public void reset() {
        profile = null; recorded = null;
        settleFrom = lastFrame = lastNow = lastValid = 0;
        lastSoc = Double.NaN; relinked = false;
        clearRun();
    }

    /** The link dropped: the next usable frame starts a new settling window. */
    public void disconnected() { relinked = true; }

    /**
     * One reading, as LearnedChargerMatch.observe takes it. stock and fast are this profile's learned curves (null:
     * none). Unusable frames (null readings) neither count nor break the run.
     */
    public void observe(String profile, boolean charging, Double soc, Double volts, Double amps, long frameMs, long nowMs,
            ChargeSessionStore.Curve stock, ChargeSessionStore.Curve fast) {
        if (profile == null || profile.isEmpty() || nowMs <= 0) return;
        if (!charging || !profile.equals(this.profile) || nowMs < lastNow
                || lastValid > 0 && nowMs - lastValid > ChargerDetection.RETAIN_MS
                || usable(soc, 0, 100) && soc <= lastSoc - 1) reset();
        if (!charging) return;
        if (this.profile == null) { this.profile = profile; settleFrom = frameMs > 0 ? frameMs : 0; }
        lastNow = nowMs;
        if (!usable(soc, 0, 100) || !usable(volts, 10, 120) || amps == null || !Double.isFinite(amps) || Math.abs(amps) <= 0
                || Math.abs(amps) > 40 || frameMs <= 0 || frameMs > nowMs || frameMs < lastFrame || frameMs == lastFrame) return;
        // A link drop or a long gap: the charger may have changed and the charge may be settling again.
        if (relinked || settleFrom == 0 || lastFrame > 0 && frameMs - lastFrame > ChargerDetection.MAX_CONTINUITY_GAP_MS) {
            settleFrom = frameMs; relinked = false; clearRun();
        }
        lastFrame = frameMs; lastValid = nowMs; lastSoc = soc;
        ChargeSessionStore.Curve only = stock != null && fast == null ? stock : fast != null && stock == null ? fast : null;
        if (only == null || soc >= MAX_SOC || frameMs - settleFrom < SETTLE_MS) { clearRun(); return; }
        Double saved = only.familyWatts(soc);
        if (saved == null) return;
        double live = volts * Math.abs(amps);
        if (!farOff(saved, live)) { clearRun(); return; }
        int way = live > saved ? 1 : -1;
        if (runStart == 0 || way != direction || only.charger != recorded) { runStart = frameMs; direction = way; recorded = only.charger; }
        runLast = frameMs;
    }

    /** Whether this profile's charge has been far off for SUSTAIN_MS, up to a frame no older than the continuity gap. */
    public boolean sustained(String profile, long nowMs) {
        return profile != null && profile.equals(this.profile) && runStart > 0 && runLast - runStart >= SUSTAIN_MS
                && nowMs >= lastValid && nowMs - lastValid <= ChargerDetection.MAX_CONTINUITY_GAP_MS;
    }

    /** While sustained, the family the run points to ({@link #otherFamily}), else null. */
    public ChargeSessionStore.Charger otherFamily(String profile, long nowMs) {
        return sustained(profile, nowMs) ? otherFamily(recorded, direction) : null;
    }

    private void clearRun() { runStart = runLast = 0; direction = 0; }
    private static boolean usable(Double value, double low, double high) {
        return value != null && Double.isFinite(value) && value >= low && value <= high;
    }
}
