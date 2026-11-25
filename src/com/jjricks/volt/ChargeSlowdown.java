package com.jjricks.volt;

/**
 * "Charging slowed" on the UT5 MAX: the charge's current is well below the level the battery's BMS gives a healthy
 * charge, and the battery temperature names why (owner, Nov 19 2026: power is the signal, the temperature only names
 * the cause).
 *
 * The BMS sets fixed current levels. A healthy fast charge holds about 10.05 A below a displayed 80% and 8.0 A
 * from 80% (that step is not temperature: every charge still below the charger's voltage limit takes it). A cold
 * battery gets 5.5 A below 15 C and 8.5 A from 15 C, with full current from 20 C; a hot one steps down to 8, 6, 4
 * and 3 A from about 38 to 41 C on, and right after a ride (34 to 36 C) it can hold about 1 to 2 A for minutes.
 * A fixed nominal power would miss the cold 8.5 A stage (450 W against 490), so the live current is compared with
 * the expected current instead.
 *
 * The rule, on fresh paired readings of one charge:
 * <ul>
 * <li>only the UT5 MAX (product {@link Ut5MaxFastChargeReference#PRODUCT_ID}) with its calibrated pack (rated
 *     capacity within 10% of 15.3 Ah, FastChargeTaper.calibratedPack);</li>
 * <li>on any charger, a current below {@link #ANY_SHOW_AMPS} is slowed: the standard charger's own 1.9 to 2.3 A sits
 *     well above it, so it needs no charger known (owner, Nov 24 2026: 1 to 2 A on the fast charger with no charger
 *     chosen or detected showed nothing); between that and the fast charger's levels only the fast charger, detected
 *     or chosen, is slowed (below SHOW x the expected current), as the standard charger's current sits there;</li>
 * <li>eligible outside the charger's voltage limit (V + CV_DROP x I below {@link #CV_EDGE_V}, FastChargeTaper's
 *     CV_SET less CV_MARGIN; without the fast charger known, also outside the standard charger's, {@link
 *     #STOCK_CV_EDGE_V}), below the charge limit less {@link #LIMIT_MARGIN} percent, and at least {@link #START_MS}
 *     after charging began or after a gap or pause in readings of {@link #GAP_MS} or more (a not-charging state, such as
 *     an unplug and replug, ends the charge);</li>
 * <li>slowed once the current stays below the show level for {@link #HOLD_MS} without a break, and, unless the battery
 *     reads {@link #HOT_NAME_C} or more, no sooner than {@link #SOFT_START_MS} after charging began: the fast
 *     charger's soft start (about 1.3 A) is not a slowdown, while heat right after a ride is the owner's case;</li>
 * <li>cleared once it stays at the clear level (CLEAR x the expected current, or {@link #ANY_CLEAR_AMPS} by the
 *     any-charger rule) or more for {@link #CLEAR_MS}; at once on a jump from below the show level to the charger's
 *     normal level in one reading ({@link #jumped}); and at once when the charge leaves eligibility or another
 *     charger is chosen or detected;</li>
 * <li>the cause is cold at {@link #COLD_NAME_C} or below, hot at {@link #HOT_NAME_C} or above, otherwise (or with
 *     no fresh temperature) plain. It is set when the state shows and kept while it shows, except that a plain
 *     one takes cold or hot once a temperature names it.</li>
 * </ul>
 *
 * ChargingEstimate.forScooter takes {@link #state} as its slowdown argument, so the time shown and the charging
 * status share this one state; the estimate uses it on the fast charger only. Nothing is persisted or loaded: the
 * constants are bundled, each reading costs a few comparisons, and a process restart starts watching the charge
 * again. Instances hold one profile's charge. See docs/validation/fast-charge-slowdown.md.
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
    /**
     * Shown after START_MS of charging and HOLD_MS below the show level (2.0.6: 20 s each, from 60 s each): readings come
     * every 2 s with Home open and every 15 s out of sight, so 20 s is several readings, and a single low reading or a
     * plug-in ramp (1 to 9.5 A over 30 s) never holds it. Cleared after CLEAR_MS at the clear level, as cautious as
     * before, so a current moving between two of the BMS's levels does not flicker it.
     */
    static final long HOLD_MS = 20_000L, START_MS = 20_000L, CLEAR_MS = 60_000L, GAP_MS = 30_000L;
    /**
     * Below HOT_NAME_C (or with no temperature) nothing shows sooner than this after charging began. The fast charger's
     * soft start holds about 1.3 A first: the owner's charges went to full current within 4 s, 19 s and 30 s, a cold
     * one (8 C, 1.8 A) within 30 s, and the longest held 55 s before its charging state dropped. 90 s clears it with
     * a margin; a real slowdown below 35 C still shows 30 s sooner than 2.0.5's 120 s.
     */
    static final long SOFT_START_MS = 90_000L;
    /** The standard charger's lowest bulk current (A): a jump to it from below ANY_SHOW_AMPS on the standard charger clears at once. */
    static final double STANDARD_LOW_AMPS = 1.9;
    /**
     * The any-charger rule (A): slowed below ANY_SHOW_AMPS, cleared at ANY_CLEAR_AMPS or more. The standard charger's
     * bulk current is 1.9 to 2.3 A (its recordings: 1.94 to 2.30 A), and below 1.9 A only in its finish at its voltage
     * limit (STOCK_CV_EDGE_V); 1.6 A sits 0.3 A (about 15%) under its lowest level, far beyond the 0.02 to 0.03 A
     * reading steps, and the 0.1 A between show and clear is the same hysteresis as SHOW and CLEAR around 2 A.
     */
    static final double ANY_SHOW_AMPS = 1.6, ANY_CLEAR_AMPS = 1.7;
    /**
     * The standard charger's voltage limit less CV_MARGIN: its finish line is Ut5MaxStockChargeReference.FINISH_CV_SET
     * less FINISH_CV_DROP an amp, so its current falls below ANY_SHOW_AMPS there with the pack under the fast charger's edge.
     */
    static final double STOCK_CV_EDGE_V = Ut5MaxStockChargeReference.FINISH_CV_SET - CV_MARGIN, STOCK_CV_DROP = Ut5MaxStockChargeReference.FINISH_CV_DROP;
    /** A silence this long ends the watched charge, as FastChargeTaper's. */
    static final long MAX_SILENCE_MS = FastChargeTaper.MAX_SILENCE_MS;
    /**
     * Cause naming edges (C): 70 F and 95 F. Hot from 35 C (2.0.6, from 38 C): right after a ride the owner's BMS held
     * the current at 1 to 2 A with the battery at 34 to 36 C.
     */
    static final double COLD_NAME_C = 21, HOT_NAME_C = 35;
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
    /** The charger detected or chosen last in this charge (null: none known yet), and whether the fast rule applied last. */
    private ChargeSessionStore.Charger knownCharger;
    private boolean fastRule;
    private Cause cause;
    private Double temperature;
    private long start, since = -1, last;
    /** The previous fresh current of this charge (A), or NaN. */
    private double lastAmps = Double.NaN;
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
     * One reading. charging is the fresh charging state (null changes nothing; false ends the charge). charger is the
     * charger in effect (detected or chosen) or null.
     * ratedAh is the pack's rated capacity or null (unknown: not watched). soc, volts and amps are a fresh paired reading
     * or null; temperatureC a fresh battery temperature or null; limit the scooter's charge limit (percent) or null when
     * unknown, which counts as 100. sampleMs is the paired reading's time on the monotonic clock; the same reading seen
     * again changes nothing.
     */
    public void observe(String profile, int productId, Double ratedAh, ChargeSessionStore.Charger charger, Boolean charging,
            Double soc, Double volts, Double amps, Double temperatureC, Double limit, long sampleMs) {
        if (profile == null || profile.isEmpty() || charging == null) return;
        if (!profile.equals(this.profile)) { reset(); this.profile = profile; }
        if (!charging) { clearCharge(); return; }
        if (soc == null || volts == null || amps == null || !finite(soc) || !finite(volts) || !finite(amps) || sampleMs <= 0) return;
        if (sampleMs == last) return;
        if (last > 0 && (sampleMs < last || sampleMs - last > MAX_SILENCE_MS)) clearCharge();
        if (productId != Ut5MaxFastChargeReference.PRODUCT_ID || ratedAh == null || !FastChargeTaper.calibratedPack(ratedAh)) {
            // Not watched: a later reading of a watched pack starts its own START_MS.
            on = false; since = -1; last = 0; temperature = null; warmFirst = -1; knownCharger = null; lastAmps = Double.NaN;
            return;
        }
        if (last == 0 || sampleMs - last >= GAP_MS) { start = sampleMs; since = -1; }
        // Another charger chosen or detected than the one known in this charge: cleared at once, a new start window
        // (the cold stretch is kept). A charger not known for a moment changes the rule only.
        if (charger != null && knownCharger != null && charger != knownCharger) { on = false; since = -1; start = sampleMs; warmFirst = -1; }
        if (charger != null) knownCharger = charger;
        boolean fast = charger == ChargeSessionStore.Charger.HIGH_SPEED;
        if (fast != fastRule) { fastRule = fast; since = -1; }
        last = sampleMs;
        temperature = temperatureC != null && finite(temperatureC) ? temperatureC : null;
        if (temperature != null) warming(temperature, sampleMs);
        double current = Math.abs(amps), expected = expectedAmps(soc), previous = lastAmps;
        lastAmps = current;
        boolean early = (temperature == null || temperature < HOT_NAME_C) && sampleMs - start < SOFT_START_MS;
        double cutoff = limit != null && finite(limit) && limit > 0 && limit <= 100 ? limit : 100;
        boolean eligible = volts + FastChargeTaper.CV_DROP * current < CV_EDGE_V && soc < cutoff - LIMIT_MARGIN && sampleMs - start >= START_MS
                && (fast || volts + STOCK_CV_DROP * current < STOCK_CV_EDGE_V);
        if (!eligible) { on = false; since = -1; }
        else {
            boolean want = fast ? (on ? current / expected < CLEAR : current / expected < SHOW)
                    : (on ? current < ANY_CLEAR_AMPS : current < ANY_SHOW_AMPS);
            if (on && jumped(charger, previous, current, expected)) { on = false; since = -1; }
            else if (want == on) since = -1;
            else {
                if (since < 0) since = sampleMs;
                if (sampleMs - since >= (want ? HOLD_MS : CLEAR_MS) && !(want && early)) {
                    on = want; since = -1;
                    if (on) cause = cause(temperature);
                }
            }
        }
        if (on && cause == Cause.PLAIN) cause = cause(temperature);
        if (on && temperature != null && temperature < COLD_FULL_C) coldSeen = true;
    }

    /**
     * A jump to full power in one reading, which clears at once (a creep back across the band still takes CLEAR_MS):
     * from below SHOW x the expected current to CLEAR x it or more on the fast charger, or with no charger known (only
     * the fast charger reaches it); on the standard charger, from below ANY_SHOW_AMPS to its own STANDARD_LOW_AMPS.
     */
    static boolean jumped(ChargeSessionStore.Charger charger, double previous, double current, double expected) {
        if (!finite(previous)) return false;
        if (charger == ChargeSessionStore.Charger.STOCK) return previous < ANY_SHOW_AMPS && current >= STANDARD_LOW_AMPS;
        return previous < SHOW * expected && current >= CLEAR * expected;
    }

    /**
     * The state for this profile's charge at nowMs (the monotonic clock): NONE for another profile, without a
     * watched charge, and not slowed once the last reading is GAP_MS old.
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
        on = coldSeen = fastRule = false; cause = null; temperature = null; start = last = 0; since = -1; warmFirst = -1;
        knownCharger = null; lastAmps = Double.NaN;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
