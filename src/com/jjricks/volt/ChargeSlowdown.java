package com.jjricks.volt;

/**
 * "Charging slowed" on the UT5 MAX fast charger: the charge's current is well below the level the battery's BMS
 * gives a healthy charge, and the battery temperature names why (owner, Nov 19 2026: power is the signal, the
 * temperature only names the cause).
 *
 * The BMS sets fixed current levels. A healthy fast charge holds about 10.05 A below a displayed 80% and 8.0 A
 * from 80% (that step is not temperature: every charge still below the charger's voltage limit takes it). A cold
 * battery gets 5.5 A below 15 C and 8.5 A from 15 C, with full current from 20 C; a hot one steps down to 8, 6, 4
 * and 3 A from about 38 to 41 C on. A fixed nominal power would miss the cold 8.5 A stage (450 W against 490), so
 * the live current is compared with the expected current instead.
 *
 * The rule, on fresh paired readings of one charge:
 * <ul>
 * <li>only the UT5 MAX (product {@link Ut5MaxFastChargeReference#PRODUCT_ID}) on the fast charger, detected or
 *     chosen; the standard charger's 1.9 to 2.3 A sits below every BMS limit;</li>
 * <li>eligible outside the charger's voltage limit (V + CV_DROP x I below {@link #CV_EDGE_V}, FastChargeTaper's
 *     CV_SET less CV_MARGIN), below the charge limit less {@link #LIMIT_MARGIN} percent, and at least
 *     {@link #START_MS} after charging began or after a gap or pause in readings of {@link #GAP_MS} or more;</li>
 * <li>slowed once the current stays below SHOW x the expected current for {@link #HOLD_MS} without a break;
 *     cleared once it stays at CLEAR x or more for HOLD_MS, or at once when the charge leaves eligibility;</li>
 * <li>the cause is cold at {@link #COLD_NAME_C} or below, hot at {@link #HOT_NAME_C} or above, otherwise (or with
 *     no fresh temperature) plain. It is set when the state shows and kept while it shows, except that a plain
 *     one takes cold or hot once a temperature names it.</li>
 * </ul>
 *
 * ChargingEstimate.forScooter takes {@link #state} as its slowdown argument, so the time shown and the charging
 * status share this one state. Nothing is persisted or loaded: the constants are bundled, each reading costs a
 * few comparisons, and a process restart starts watching the charge again. Instances hold one profile's charge.
 * See docs/validation/fast-charge-slowdown.md.
 */
public final class ChargeSlowdown {
    /** Why charging is slowed. */
    public enum Cause { COLD, HOT, PLAIN }

    /** An immutable view for the estimate and the charging status. */
    public static final class State {
        /** Not slowed, nothing known: no charge watched, another scooter, simulation, or stale readings. */
        public static final State NONE = new State(false, null, null, false);
        /** Charging is slowed now. */
        public final boolean slowed;
        /** Why, while slowed; null otherwise. */
        public final Cause cause;
        /** The latest fresh battery temperature (C) of the watched charge, or null. */
        public final Double batteryTempC;
        /**
         * This charge was slowed while the battery sat in the BMS's cold band (below COLD_FULL_C): its taper runs
         * slower too, so Charge done by allows COLD_TAIL_MINUTES more. Kept to the charge's end.
         */
        public final boolean coldTail;

        State(boolean slowed, Cause cause, Double batteryTempC, boolean coldTail) {
            this.slowed = slowed; this.cause = slowed ? cause : null; this.batteryTempC = batteryTempC; this.coldTail = coldTail;
        }
    }

    /** The BMS's healthy fast-charge current below and from its 80% step (A). */
    static final double FULL_AMPS = 10.05, STEP_AMPS = 8.0, STEP_SOC = 80;
    /** Where the bulk stage ends for the slowed estimate: the 80% step, or 85% within the 8 A stage. */
    static final double STEP_END_SOC = 85;
    static final double SHOW = .90, CLEAR = .95;
    /** FastChargeTaper.CV_SET less 0.15 V: at and above this the charger's voltage limit is near and current falls by design. */
    static final double CV_MARGIN = .15, CV_EDGE_V = FastChargeTaper.CV_SET - CV_MARGIN;
    static final double LIMIT_MARGIN = 2;
    static final long HOLD_MS = 60_000L, START_MS = 60_000L, GAP_MS = 30_000L;
    /** A silence this long ends the watched charge, as FastChargeTaper's. */
    static final long MAX_SILENCE_MS = FastChargeTaper.MAX_SILENCE_MS;
    /** Cause naming edges (C): 70 F and 100 F. */
    static final double COLD_NAME_C = 21, HOT_NAME_C = 38;
    /** The BMS's cold levels: COLDEST_AMPS below COLD_LOW_C, COOL_AMPS below COLD_FULL_C, full current from it. */
    static final double COLD_LOW_C = 15, COLD_FULL_C = 20, COLDEST_AMPS = 5.5, COOL_AMPS = 8.5;
    /**
     * Battery warming while charging cold (C a minute): the time shown uses the measured 0.25, starting half way
     * through the 1 C reading; Charge done by plans with 0.15 from the bottom of the reading, since the room decides it.
     */
    static final double WARM_SHOWN = .25, WARM_PLAN = .15;
    /** Charge done by's extra minutes for the slower taper after a cold-slowed stretch. */
    static final double COLD_TAIL_MINUTES = 6;
    /** The pack's resistance (ohms), moving the pack voltage with the current for the estimate at the expected current. */
    static final double PACK_OHMS = .10;

    private String profile;
    private boolean on, coldSeen;
    private Cause cause;
    private Double temperature;
    private long start, since = -1, last;

    public void reset() { profile = null; clearCharge(); }

    /** The healthy current at this displayed battery level. */
    static double expectedAmps(double soc) { return soc < STEP_SOC ? FULL_AMPS : STEP_AMPS; }

    /** The end of the bulk stage the slowed estimate covers from this level. */
    static double bulkEnd(double soc) { return soc < STEP_SOC ? STEP_SOC : STEP_END_SOC; }

    static Cause cause(Double temperatureC) {
        if (temperatureC == null || !finite(temperatureC)) return Cause.PLAIN;
        return temperatureC <= COLD_NAME_C ? Cause.COLD : temperatureC >= HOT_NAME_C ? Cause.HOT : Cause.PLAIN;
    }

    /**
     * One reading. charging is the fresh charging state (null changes nothing). soc, volts and amps are a fresh
     * paired reading or null; temperatureC a fresh battery temperature or null; limit the scooter's charge limit
     * (percent) or null when unknown, which counts as 100. sampleMs is the paired reading's time on the monotonic
     * clock; the same reading seen again changes nothing.
     */
    public void observe(String profile, int productId, ChargeSessionStore.Charger charger, Boolean charging,
            Double soc, Double volts, Double amps, Double temperatureC, Double limit, long sampleMs) {
        if (profile == null || profile.isEmpty() || charging == null) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (!charging) { clearCharge(); return; }
        if (soc == null || volts == null || amps == null || !finite(soc) || !finite(volts) || !finite(amps) || sampleMs <= 0) return;
        if (sampleMs == last) return;
        if (last > 0 && (sampleMs < last || sampleMs - last > MAX_SILENCE_MS)) clearCharge();
        if (productId != Ut5MaxFastChargeReference.PRODUCT_ID || charger != ChargeSessionStore.Charger.HIGH_SPEED) {
            // Not watched: a later fast-charger reading starts its own START_MS.
            on = false; since = -1; last = 0; temperature = null;
            return;
        }
        if (last == 0 || sampleMs - last >= GAP_MS) { start = sampleMs; since = -1; }
        last = sampleMs;
        temperature = temperatureC != null && finite(temperatureC) ? temperatureC : null;
        double current = Math.abs(amps), expected = expectedAmps(soc);
        double cutoff = limit != null && finite(limit) && limit > 0 && limit <= 100 ? limit : 100;
        boolean eligible = volts + FastChargeTaper.CV_DROP * current < CV_EDGE_V && soc < cutoff - LIMIT_MARGIN && sampleMs - start >= START_MS;
        if (!eligible) { on = false; since = -1; }
        else {
            double ratio = current / expected;
            boolean want = on ? ratio < CLEAR : ratio < SHOW;
            if (want == on) since = -1;
            else {
                if (since < 0) since = sampleMs;
                if (sampleMs - since >= HOLD_MS) {
                    on = want; since = -1;
                    if (on) cause = cause(temperature);
                }
            }
        }
        if (on && cause == Cause.PLAIN) cause = cause(temperature);
        if (on && temperature != null && temperature < COLD_FULL_C) coldSeen = true;
    }

    /**
     * The state for this profile's charge at nowMs (the monotonic clock): NONE for another profile or without a
     * watched fast charge, and not slowed once the last reading is GAP_MS old.
     */
    public State state(String profile, long nowMs) {
        if (profile == null || !profile.equals(this.profile) || last <= 0) return State.NONE;
        boolean fresh = nowMs >= last && nowMs - last < GAP_MS;
        if (!fresh) return coldSeen ? new State(false, null, null, true) : State.NONE;
        return new State(on, cause, temperature, coldSeen);
    }

    private void clearCharge() {
        on = coldSeen = false; cause = null; temperature = null; start = last = 0; since = -1;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
