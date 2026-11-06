package com.jjricks.volt;

/**
 * What riding mode's live range shows (docs/validation/live-range.md#readout): the state, and a calm
 * number in the rider's unit. Smoothing, hysteresis on the last digit and limits on how fast it may
 * move live here, so the estimator's maths and the display's calm are tested separately. Formatting
 * and words belong to the widget; this class holds no text.
 */
public final class RangeReadout {
    public enum State {
        /** Charging, or no battery reading: nothing to show. */
        HIDDEN,
        /** No learned energy model: the distance model's figure as an approximate whole number, or nothing (value null). */
        NO_HISTORY,
        /** Not enough riding at pace yet: an approximate whole number. */
        SETTLING,
        /** Range at this pace, tenths when enabled. */
        LIVE,
        /** At or below LOW_PERCENT: an amber approximate number. */
        LOW,
        /** Below EMPTY_PERCENT: "Almost empty", no number. */
        ALMOST_EMPTY
    }
    static final double KM_PER_MI = 1.609344;
    /** Owner's Q26 C: an amber ≈ number down to 2%, then "Almost empty". */
    static final double LOW_PERCENT = 5, EMPTY_PERCENT = 2;

    static final class Params {
        /** Time constant of the display filter on the raw range: absorbs power noise from the shorter estimator memory. */
        double smoothTauS = 6;
        /** The shown value moves only once the filtered one is this many steps away from it. */
        double hysteresisSteps = 1;
        /** Fastest fall and rise: the larger of a step rate (steps per s) and a share of the value per s. */
        double fallStepsPerS = .5, fallSharePerS = .03, riseStepsPerS = .25, riseSharePerS = .01;
        /** Movement allowance that can be saved up while the value sits still, in seconds of the limits above. */
        double burstS = 3;
    }

    public static final class Value {
        public final State state;
        /** The number to show in the primary unit (mi for "mi" and "dual", km for "km"); null when none. */
        public final Double primary;
        /** In "dual" mode, the same distance in km at the same precision; null otherwise. */
        public final Double secondaryKm;
        public final boolean primaryIsKm;
        /** Digits after the decimal point: 1 or 0. */
        public final int decimals;
        /** Show "≈" in front. */
        public final boolean approx, amber;
        /** Stopped or slowing: energy per km is held (the widget may dim it). */
        public final boolean held;
        /** No fresh reading: the last value, frozen (the dashboard's stale handling). */
        public final boolean stale;
        Value(State state, Double primary, Double secondaryKm, boolean primaryIsKm, int decimals, boolean approx, boolean amber, boolean held, boolean stale) {
            this.state = state; this.primary = primary; this.secondaryKm = secondaryKm; this.primaryIsKm = primaryIsKm;
            this.decimals = decimals; this.approx = approx; this.amber = amber; this.held = held; this.stale = stale;
        }
    }

    final Params params;
    private double smoothKm = Double.NaN, shown = Double.NaN, step, fallBudget, riseBudget;
    private long lastMs = -1;
    private State lastState;
    private boolean lastKm;
    /** The presentation lastValue was formatted for: units ("mi", "km", "dual") and the tenths toggle. */
    private String lastUnits; private boolean lastTenths;
    /** The last displayed distance, in km: what a stale reading freezes and reformats (never the filter target). */
    private double frozenKm = Double.NaN;
    private Value lastValue;

    public RangeReadout() { this(new Params()); }
    RangeReadout(Params params) { this.params = params; }

    /** Units preference: "mi", "km" or "dual" (anything else reads as "mi"); tenths: the riding layout's toggle. */
    public Value update(LiveRangeEstimator.Snapshot s, String units, boolean tenths) {
        boolean km = "km".equals(units), dual = "dual".equals(units);
        String unitsKey = km ? "km" : dual ? "dual" : "mi";
        if (s == null || !s.available) { clear(); return remember(unitsKey, tenths, new Value(State.HIDDEN, null, null, km, 0, false, false, false, false)); }
        double soc = s.batteryPercent;
        if (soc < EMPTY_PERCENT) { clear(); return remember(unitsKey, tenths, new Value(State.ALMOST_EMPTY, null, null, km, 0, false, true, s.held, s.stale)); }
        if (s.stale && lastValue != null && lastValue.state != State.HIDDEN && lastValue.state != State.ALMOST_EMPTY) {
            lastMs = s.monoMs;
            // The displayed distance stays frozen, in km; only its presentation follows a units or tenths change.
            if (lastValue.primary != null && !Double.isNaN(frozenKm) && (!unitsKey.equals(lastUnits) || tenths != lastTenths)) {
                Value v = lastValue;
                double target = frozenKm / (km ? 1 : KM_PER_MI);
                boolean fine = tenths && (v.state == State.LIVE || v.state == State.LOW) && target < 99.5;
                double q = fine ? .1 : 1, value = Math.max(0, round(target, q));
                // Carry the switch into the calm state, so fresh readings continue from here without counting it as a movement.
                shown = value; step = q; lastKm = km;
                remember(unitsKey, tenths, new Value(v.state, value == 0 ? 0d : value, dual ? (Double) round(value * KM_PER_MI, q) : null, km, fine ? 1 : 0,
                        v.approx, v.amber, v.held, false));
            }
            Value v = lastValue;
            return new Value(v.state, v.primary, v.secondaryKm, v.primaryIsKm, v.decimals, v.approx, v.amber, v.held, true);
        }
        if (s.rawKm == null || !Double.isFinite(s.rawKm)) { clear(); return remember(unitsKey, tenths, new Value(State.NO_HISTORY, null, null, km, 0, false, false, s.held, s.stale)); }
        State state = !s.energyModel ? State.NO_HISTORY : soc <= LOW_PERCENT ? State.LOW
                : s.settling && lastState != State.LIVE ? State.SETTLING : State.LIVE;
        double perUnit = km ? 1 : KM_PER_MI;
        double dt = lastMs < 0 || s.monoMs <= lastMs ? 0 : (s.monoMs - lastMs) / 1000.0;
        lastMs = s.monoMs;
        double raw = Math.max(0, s.rawKm);
        smoothKm = Double.isNaN(smoothKm) ? raw : smoothKm + (raw - smoothKm) * (1 - Math.exp(-dt / params.smoothTauS));
        double target = smoothKm / perUnit;
        boolean fine = tenths && (state == State.LIVE || state == State.LOW) && target < 99.5;
        double q = fine ? .1 : 1;
        if (Double.isNaN(shown) || q != step || state != lastState || km != lastKm) {
            shown = round(target, q); fallBudget = 0; riseBudget = 0;
        } else {
            double fallRate = Math.max(params.fallStepsPerS * q, params.fallSharePerS * shown);
            double riseRate = Math.max(params.riseStepsPerS * q, params.riseSharePerS * shown);
            fallBudget = Math.min(fallBudget + fallRate * dt, Math.max(q, fallRate * params.burstS));
            riseBudget = Math.min(riseBudget + riseRate * dt, Math.max(q, riseRate * params.burstS));
            double gap = target - shown;
            if (Math.abs(gap) >= params.hysteresisSteps * q) {
                double want = round(target, q) - shown;
                double budget = want < 0 ? fallBudget : riseBudget;
                double move = Math.min(Math.abs(want), Math.floor(budget / q + 1e-9) * q);
                if (move > 0) {
                    shown = round(shown + Math.signum(want) * move, q);
                    if (want < 0) fallBudget -= move; else riseBudget -= move;
                }
            }
        }
        step = q; lastState = state; lastKm = km;
        double value = Math.max(0, shown);
        frozenKm = value * perUnit;
        Double secondary = dual ? (Double) round(value * KM_PER_MI, q) : null;
        boolean approx = state != State.LIVE || s.belowSeen;
        return remember(unitsKey, tenths, new Value(state, value == 0 ? 0d : value, secondary, km, fine ? 1 : 0, approx, state == State.LOW, s.held, s.stale));
    }

    private Value remember(String units, boolean tenths, Value v) { lastValue = v; lastUnits = units; lastTenths = tenths; return v; }
    private void clear() { smoothKm = Double.NaN; shown = Double.NaN; frozenKm = Double.NaN; lastState = null; lastMs = -1; }
    /** Rounded to the step; never "-0.0". */
    static double round(double x, double q) { double r = Math.round(x / q) * q; r = Math.round(r * 10) / 10.0; return r == 0 ? 0 : r; }
}
