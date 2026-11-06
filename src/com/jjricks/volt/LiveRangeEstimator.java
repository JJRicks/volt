package com.jjricks.volt;

/**
 * Riding mode's "range at this pace" (docs/validation/live-range.md): battery energy left divided by
 * the energy per km of the last half minute or so ridden at the rider's cruising speed.
 *
 * <p>It reacts within seconds to a climb or a faster pace, and it holds through stops, coasting and
 * slowdowns: only stretches ridden near the cruising speed teach it energy per km, and the cruising
 * speed itself is held while the rider is stopped. It never divides by a near-zero speed. The
 * learned range model supplies the battery energy table and the starting energy per km.
 *
 * <p>Pure Java with no Android imports. State lives in memory only; it is never backed up.
 */
public final class LiveRangeEstimator {
    /** Tunables (docs/validation/live-range.md#tuning); package-private so the replay tools can sweep them. */
    static final class Params {
        /**
         * Time constant of the energy-per-km memory, counted only while riding at pace. Tuned on the
         * owner's recorded rides (docs/validation/live-range.md#tuning): shorter reacts sooner but
         * reverses more often on noisy power.
         */
        double rateTauS = 16;
        /**
         * The shown energy per km follows that memory with these time constants, at pace only: quickly
         * when it rises (a climb, a faster pace: the range falls) and more slowly when it drops (an
         * easier pace: the range rises). The slower drop keeps stops, coasts and descents from lifting
         * the number, and leans the bias cautious.
         */
        double riseTauS = 3, relaxTauS = 20;
        /** The learned prior starts with the weight of this many seconds at PRIOR_SPEED_KMH. */
        double priorS = 20;
        /**
         * Cruising speed, the speed the rider hovers around: smoothed speed, followed quickly upward
         * and slowly downward, more slowly still below the pace gate (braking, a slow stretch), and
         * held when stopped. A lasting slower pace is still learned within a minute or two.
         */
        double speedTauS = 4, cruiseRiseTauS = 8, cruiseFallTauS = 90, cruiseSlowTauS = 300;
        /** An interval teaches energy per km only when both ends are at least this share of cruising speed. */
        double paceShare = .6, minPaceKmh = 8, movingKmh = 2;
        /**
         * Coasting: an interval drawing less than this share of the learned energy per km teaches
         * nothing (rolling down a hill or easing off says little about the pace to come).
         */
        double coastShare = .25;
        /** Energy per km is kept within these multiples of the learned rate (coasting downhill, steep climbs). */
        double floorFactor = .5, ceilingFactor = 3;
        /** Shown as settling (approximate, whole numbers) until this much riding at pace and live share. */
        double settleS = 30, settleShare = .5;
        long maxIntervalMs = 12000, staleMs = 15000, holdAfterMs = 5000, resetAfterMs = 600000;
        Params copy() {
            Params p = new Params();
            p.rateTauS = rateTauS; p.riseTauS = riseTauS; p.relaxTauS = relaxTauS; p.priorS = priorS; p.speedTauS = speedTauS;
            p.cruiseRiseTauS = cruiseRiseTauS; p.cruiseFallTauS = cruiseFallTauS; p.cruiseSlowTauS = cruiseSlowTauS; p.paceShare = paceShare; p.coastShare = coastShare;
            p.minPaceKmh = minPaceKmh; p.movingKmh = movingKmh; p.floorFactor = floorFactor; p.ceilingFactor = ceilingFactor;
            p.settleS = settleS; p.settleShare = settleShare; p.maxIntervalMs = maxIntervalMs; p.staleMs = staleMs;
            p.holdAfterMs = holdAfterMs; p.resetAfterMs = resetAfterMs; return p;
        }
    }
    static final double PRIOR_SPEED_KMH = 20;

    /** Battery energy table and starting energy per km, from the learned range model. */
    public static final class Prior {
        interface Table { Double energyLeftWh(double percent); }
        final Double whPerKm;
        final Table table;
        final Integer lowestSeenPercent;
        /** DISTANCE model only: its remaining km, shown as an approximate fallback. */
        private final LearnedRangeEstimate.Result distance;
        interface Learned { Double remainingKm(Double percent); }
        /** The learned model's own remaining range from past rides, either model; never this ride's pace. */
        private final Learned learned;
        private Prior(Double whPerKm, Table table, Integer lowestSeen, LearnedRangeEstimate.Result distance, Learned learned) {
            this.whPerKm = whPerKm; this.table = table; lowestSeenPercent = lowestSeen; this.distance = distance; this.learned = learned;
        }
        /** Null when the learned model has no ready result. */
        public static Prior of(final LearnedRangeEstimate.Result r) {
            if (r == null || r.status != LearnedRangeEstimate.Status.READY) return null;
            if (r.model == LearnedRangeEstimate.Model.ENERGY && r.whPerKm != null && r.whPerKm > 0)
                return new Prior(r.whPerKm, new Table() { public Double energyLeftWh(double p) { return r.energyLeftWh(p); } }, r.lowestSeenPercent(), null, r::remainingKm);
            return new Prior(null, null, null, r, r::remainingKm);
        }
        /** A linear battery of fullWh at whPerKm: tests, replays and simulation (never persisted). */
        static Prior synthetic(double whPerKm, final double fullWh, Integer lowestSeen) {
            return new Prior(whPerKm, new Table() {
                public Double energyLeftWh(double p) { return p >= 0 && p <= 100 ? (Double) (fullWh * p / 100) : null; }
            }, lowestSeen, null, p -> p == null || !Double.isFinite(p) || p < 0 || p > 100 ? null : (Double) (fullWh * p / 100 / whPerKm));
        }
        /** Volt's learned remaining km at a battery reading (Home's learned range); null without one. */
        Double learnedKm(Double percent) {
            Double km = percent == null || learned == null ? null : learned.remainingKm(percent);
            return km == null || !Double.isFinite(km) || km < 0 ? null : km;
        }
        boolean energy() { return table != null; }
        Double energyAt(double percent) { return table.energyLeftWh(Math.max(0, Math.min(100, percent))); }
    }

    public static final class Snapshot {
        public final long monoMs;
        /** A battery reading is present and the scooter isn't charging. */
        public final boolean available, charging;
        /** The live energy model runs (an ENERGY prior); otherwise rawKm is the distance model's figure or null. */
        public final boolean energyModel;
        /** Energy left ÷ energy per km, km; null without a prior or battery reading. */
        public final Double rawKm, energyLeftWh, rateWhPerKm, cruiseKmh, batteryPercent;
        /** Share of the energy-per-km memory that comes from this ride rather than the prior, 0..1. */
        public final double liveShare, movingKm;
        public final long atPaceMs;
        /** Not riding at pace for a moment: energy per km is held. */
        public final boolean held;
        /** Too few seconds at pace yet; show it as approximate. */
        public final boolean settling;
        /** Below the lowest battery level the learned model has evidence for. */
        public final boolean belowSeen;
        /** No fresh battery or power reading for more than Params.staleMs. */
        public final boolean stale;
        Snapshot(long monoMs, boolean available, boolean charging, boolean energyModel, Double rawKm, Double energyLeftWh,
                Double rateWhPerKm, Double cruiseKmh, Double batteryPercent, double liveShare, double movingKm, long atPaceMs,
                boolean held, boolean settling, boolean belowSeen, boolean stale) {
            this.monoMs = monoMs; this.available = available; this.charging = charging; this.energyModel = energyModel;
            this.rawKm = rawKm; this.energyLeftWh = energyLeftWh; this.rateWhPerKm = rateWhPerKm; this.cruiseKmh = cruiseKmh;
            this.batteryPercent = batteryPercent; this.liveShare = liveShare; this.movingKm = movingKm; this.atPaceMs = atPaceMs;
            this.held = held; this.settling = settling; this.belowSeen = belowSeen; this.stale = stale;
        }
    }

    final Params params;
    private Prior prior;
    // Battery: live energy left, between gauge steps.
    private Integer level; private Double energyLeft; private Double socReading;
    // Energy-per-km memory: faded Wh and km at pace (start: the prior's weight), and the shown rate.
    private double fadedWh, fadedKm, priorKm, rate; private long atPaceMs;
    // Speed and distance.
    private double smoothKmh = Double.NaN, cruiseKmh, movingKm, speedKm; private Double odoStart, odoNow;
    private long last = -1, lastPaceMs = -1, lastSocMs = -1, lastPowerMs = -1;
    private Double lastSpeed, lastPower;
    private boolean charging;

    public LiveRangeEstimator() { this(new Params()); }
    LiveRangeEstimator(Params params) { this.params = params; reset(null); }

    /** New prior (profile change, new learned result) or a fresh start; forgets this ride. */
    public void reset(Prior prior) { this.prior = prior; clearRide(); }

    private void clearRide() {
        level = null; energyLeft = null; socReading = null; atPaceMs = 0;
        double r0 = prior != null && prior.energy() ? prior.whPerKm : 0;
        priorKm = PRIOR_SPEED_KMH * params.priorS / 3600; fadedKm = priorKm; fadedWh = r0 * priorKm; rate = r0;
        smoothKmh = Double.NaN; cruiseKmh = 0; movingKm = 0; speedKm = 0; odoStart = null; odoNow = null;
        last = -1; lastPaceMs = -1; lastSocMs = -1; lastPowerMs = -1; lastSpeed = null; lastPower = null;
    }

    /**
     * One telemetry update. Power is signed, positive = draw; any reading may be missing. An interval
     * longer than Params.maxIntervalMs is skipped without forgetting; a silence over resetAfterMs or
     * the start of charging begins a new ride.
     */
    public void accept(long monoMs, Double speedKmh, Double powerW, Double socPercent, Double odometerKm, boolean charging) {
        if (charging) { clearRide(); this.charging = true; return; }
        this.charging = false;
        if (last >= 0 && (monoMs - last > params.resetAfterMs || monoMs < last)) clearRide();
        Double v = finite(speedKmh) && speedKmh >= 0 ? speedKmh : null, p = finite(powerW) ? powerW : null;
        boolean ok = last >= 0 && monoMs > last && monoMs - last <= params.maxIntervalMs;
        double dt = ok ? (monoMs - last) / 1000.0 : 0;
        // Odometer cross-check of the speed sensor, once a km has gone by (25% tolerance, as the learned model).
        if (finite(odometerKm)) { if (odoStart == null || odometerKm < odoStart) odoStart = odometerKm; odoNow = odometerKm; }
        double dWh = ok && p != null && lastPower != null ? (lastPower + p) / 2 * dt / 3600 : Double.NaN;
        double dKmRaw = ok && v != null && lastSpeed != null ? (lastSpeed + v) / 2 * dt / 3600 : Double.NaN;
        if (!Double.isNaN(dKmRaw)) speedKm += dKmRaw;
        double scale = odoStart != null && odoNow - odoStart >= 1 && speedKm > 0 ? clamp((odoNow - odoStart) / speedKm, .75, 1.25) : 1;
        double dKm = dKmRaw * scale;
        // Pace is judged against the cruising speed before this interval updates it.
        double gate = Math.max(params.minPaceKmh, params.paceShare * cruiseKmh);
        boolean atPace = !Double.isNaN(dWh) && !Double.isNaN(dKm) && Math.min(v, lastSpeed) >= gate
                && prior != null && prior.energy() && dWh >= params.coastShare * prior.whPerKm * dKm;
        if (v != null) {
            smoothKmh = Double.isNaN(smoothKmh) || !ok ? v : smoothKmh + (v - smoothKmh) * fade(dt, params.speedTauS);
            if (ok && v >= params.movingKmh && smoothKmh >= params.movingKmh)
                cruiseKmh += (smoothKmh - cruiseKmh) * fade(dt, smoothKmh > cruiseKmh ? params.cruiseRiseTauS
                        : smoothKmh >= gate ? params.cruiseFallTauS : params.cruiseSlowTauS);
            if (ok && !Double.isNaN(dKm) && Math.min(v, lastSpeed) >= params.movingKmh) movingKm += dKm;
        }
        if (prior != null && prior.energy()) {
            if (atPace) {
                double f = Math.exp(-dt / params.rateTauS);
                fadedWh = fadedWh * f + dWh; fadedKm = fadedKm * f + dKm; priorKm *= f;
                atPaceMs += monoMs - last; lastPaceMs = monoMs;
                double r0 = prior.whPerKm, live = clamp(fadedWh / fadedKm, params.floorFactor * r0, params.ceilingFactor * r0);
                // A harder pace shows within seconds; an easier one only gradually.
                rate += (live - rate) * fade(dt, live > rate ? params.riseTauS : params.relaxTauS);
            }
            battery(socPercent, dWh, monoMs);
        } else if (finite(socPercent) && socPercent >= 0 && socPercent <= 100) { socReading = socPercent; lastSocMs = monoMs; }
        if (p != null) lastPowerMs = monoMs;
        lastSpeed = v; lastPower = p; last = monoMs;
    }

    /**
     * Energy left between gauge steps: a one-point drop to L puts it on the boundary just crossed,
     * E(L + 0.5); between drops it falls by the energy drawn, never more than half a point from the
     * reading. A bigger jump or a rise trusts the reading's midpoint.
     */
    private void battery(Double soc, double dWh, long monoMs) {
        if (energyLeft != null && !Double.isNaN(dWh)) energyLeft = clamp(energyLeft - dWh, prior.energyAt(level - .5), prior.energyAt(level + .5));
        if (!finite(soc) || soc < 0 || soc > 100) return;
        int s = (int) Math.round(soc); socReading = soc; lastSocMs = monoMs;
        if (level == null || s > level || s < level - 1) energyLeft = prior.energyAt(s);
        else if (s == level - 1) energyLeft = prior.energyAt(s + .5);
        level = s;
    }

    public Snapshot snapshot(long nowMonoMs) {
        boolean available = !charging && socReading != null;
        boolean energy = prior != null && prior.energy();
        Double raw = null, left = null, r = null;
        if (available && energy && energyLeft != null && rate > 0) { left = Math.max(0, energyLeft); r = rate; raw = left / rate; }
        else if (available && prior != null && !energy) raw = prior.distance.remainingKm(socReading);
        double share = fadedKm > 0 ? clamp(1 - priorKm / fadedKm, 0, 1) : 0;
        boolean held = lastPaceMs < 0 || nowMonoMs - lastPaceMs > params.holdAfterMs;
        boolean settling = atPaceMs < params.settleS * 1000 || share < params.settleShare;
        boolean below = energy && level != null && prior.lowestSeenPercent != null && level < prior.lowestSeenPercent;
        boolean stale = lastSocMs < 0 || nowMonoMs - lastSocMs > params.staleMs
                || energy && (lastPowerMs < 0 || nowMonoMs - lastPowerMs > params.staleMs);
        return new Snapshot(nowMonoMs, available, charging, energy, raw, left, r, cruiseKmh > 0 ? (Double) cruiseKmh : null,
                socReading, share, movingKm, atPaceMs, held, settling, below, stale);
    }

    private static double fade(double dt, double tau) { return tau <= 0 ? 1 : 1 - Math.exp(-dt / tau); }
    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    private static Double clamp(double x, Double lo, Double hi) { return Math.max(lo == null ? x : lo, Math.min(hi == null ? x : hi, x)); }
    private static boolean finite(Double v) { return v != null && Double.isFinite(v); }
}
