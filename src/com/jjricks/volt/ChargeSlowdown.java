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
 * <li>only the UT5 MAX (product {@link Ut5MaxFastChargeReference#PRODUCT_ID}) with its calibrated pack (rated
 *     capacity within 10% of 15.3 Ah, FastChargeTaper.calibratedPack) on the fast charger, detected or chosen; the
 *     standard charger's 1.9 to 2.3 A sits below every BMS limit;</li>
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
        /**
         * How fast the cold estimate's battery warms (C a minute): this charge's own measured rate once it has
         * WARM_MEASURE_MINUTES of temperatures, held to WARM_FLOOR..WARM_SHOWN; WARM_SHOWN before that.
         */
        public final double warmRate;

        State(boolean slowed, Cause cause, Double batteryTempC, boolean coldTail) { this(slowed, cause, batteryTempC, coldTail, WARM_SHOWN); }

        State(boolean slowed, Cause cause, Double batteryTempC, boolean coldTail, double warmRate) {
            this.slowed = slowed; this.cause = slowed ? cause : null; this.batteryTempC = batteryTempC; this.coldTail = coldTail;
            this.warmRate = warmRate;
        }
    }

    /** The BMS's healthy fast-charge current below and from its 80% step (A). */
    static final double FULL_AMPS = 10.05, STEP_AMPS = 8.0, STEP_SOC = 80;
    /** The 8 A stage's bulk end for the slowed estimate when the headroom table cannot place the voltage limit. */
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
    /**
     * This charge's own warming (Nov 24 2026: a pack at 8 C charging at 5.5 A warmed about 0.13 C a minute, half of
     * WARM_SHOWN): once WARM_MEASURE_MINUTES minutes hold a fresh temperature, the least-squares slope through each
     * minute's average reading, never faster than WARM_SHOWN nor slower than WARM_FLOOR, so a pack that has barely
     * warmed yet (a whole-degree reading can sit still for 8 minutes) does not make the time wildly long.
     */
    static final int WARM_MEASURE_MINUTES = 10;
    static final double WARM_FLOOR = .10;
    /** Charge done by's extra minutes for the slower taper after a cold-slowed stretch. */
    static final double COLD_TAIL_MINUTES = 6;
    /** The pack's resistance (ohms), moving the pack voltage with the current for the estimate at the expected current. */
    static final double PACK_OHMS = .10;

    private String profile;
    private boolean on, coldSeen;
    private Cause cause;
    private Double temperature;
    private long start, since = -1, last;
    /** The warming fit: its first temperature's time, the open minute's sum and count, and the closed minutes' sums. */
    private long warmFirst = -1, warmMinute;
    private double minuteSum, sx, sy, sxx, sxy;
    private int minuteCount, minutes;

    public void reset() { profile = null; clearCharge(); }

    /** The healthy current at this displayed battery level. */
    static double expectedAmps(double soc) { return soc < STEP_SOC ? FULL_AMPS : STEP_AMPS; }

    /** How far (as a share of the level) the live current may sit from a BMS cold level that explains it. */
    static final double COLD_LEVEL_BAND = .10;

    /**
     * A BMS cold level explains this live current: the battery is below COLD_FULL_C and the current is within
     * COLD_LEVEL_BAND of that temperature's level (COLDEST_AMPS below COLD_LOW_C, COOL_AMPS below COLD_FULL_C; in the
     * first degree above COLD_LOW_C, COLDEST_AMPS too, as the BMS steps up on its own sensor), and that level lies below
     * the expected current. Only then does the estimate expect the current to rise as the battery warms; a current far
     * from the level (3 A at 19 C), or a level at or above the expected current (8.5 A in the 8 A stage), is not
     * explained by the cold and is assumed to last.
     */
    static boolean coldExplains(Double temperatureC, double amps, double expected) {
        if (temperatureC == null || !finite(temperatureC) || temperatureC >= COLD_FULL_C || !finite(amps)) return false;
        double level = temperatureC < COLD_LOW_C ? COLDEST_AMPS : COOL_AMPS;
        if (near(amps, level)) return level < expected;
        return temperatureC < COLD_LOW_C + 1 && near(amps, COLDEST_AMPS) && COLDEST_AMPS < expected;
    }

    private static boolean near(double amps, double level) { return Math.abs(amps - level) <= COLD_LEVEL_BAND * level; }

    static Cause cause(Double temperatureC) {
        if (temperatureC == null || !finite(temperatureC)) return Cause.PLAIN;
        return temperatureC <= COLD_NAME_C ? Cause.COLD : temperatureC >= HOT_NAME_C ? Cause.HOT : Cause.PLAIN;
    }

    /**
     * One reading. charging is the fresh charging state (null changes nothing). ratedAh is the pack's rated capacity
     * or null (unknown: not watched). soc, volts and amps are a fresh paired reading or null; temperatureC a fresh battery temperature or null; limit the scooter's charge limit
     * (percent) or null when unknown, which counts as 100. sampleMs is the paired reading's time on the monotonic
     * clock; the same reading seen again changes nothing.
     */
    public void observe(String profile, int productId, Double ratedAh, ChargeSessionStore.Charger charger, Boolean charging,
            Double soc, Double volts, Double amps, Double temperatureC, Double limit, long sampleMs) {
        if (profile == null || profile.isEmpty() || charging == null) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (!charging) { clearCharge(); return; }
        if (soc == null || volts == null || amps == null || !finite(soc) || !finite(volts) || !finite(amps) || sampleMs <= 0) return;
        if (sampleMs == last) return;
        if (last > 0 && (sampleMs < last || sampleMs - last > MAX_SILENCE_MS)) clearCharge();
        if (productId != Ut5MaxFastChargeReference.PRODUCT_ID || ratedAh == null || !FastChargeTaper.calibratedPack(ratedAh)
                || charger != ChargeSessionStore.Charger.HIGH_SPEED) {
            // Not watched: a later fast-charger reading starts its own START_MS.
            on = false; since = -1; last = 0; temperature = null; warmFirst = -1;
            return;
        }
        if (last == 0 || sampleMs - last >= GAP_MS) { start = sampleMs; since = -1; }
        last = sampleMs;
        temperature = temperatureC != null && finite(temperatureC) ? temperatureC : null;
        if (temperature != null) warming(temperature, sampleMs);
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
        return new State(on, cause, temperature, coldSeen, warmRate());
    }

    /** One fresh temperature: a reading in a later minute closes the open one into the fit. */
    private void warming(double c, long ms) {
        if (warmFirst < 0 || ms < warmFirst) { warmFirst = ms; warmMinute = 0; minuteSum = sx = sy = sxx = sxy = 0; minuteCount = minutes = 0; }
        long minute = (ms - warmFirst) / 60_000L;
        if (minute != warmMinute && minuteCount > 0) {
            double y = minuteSum / minuteCount;
            sx += warmMinute; sy += y; sxx += (double) warmMinute * warmMinute; sxy += warmMinute * y; minutes++;
            minuteSum = 0; minuteCount = 0;
        }
        warmMinute = minute; minuteSum += c; minuteCount++;
    }

    /** The warming rate the cold estimate uses now (State.warmRate). */
    double warmRate() {
        if (minutes < WARM_MEASURE_MINUTES) return WARM_SHOWN;
        double slope = (minutes * sxy - sx * sy) / (minutes * sxx - sx * sx);
        return finite(slope) ? Math.max(WARM_FLOOR, Math.min(WARM_SHOWN, slope)) : WARM_SHOWN;
    }

    private void clearCharge() {
        on = coldSeen = false; cause = null; temperature = null; start = last = 0; since = -1; warmFirst = -1;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
