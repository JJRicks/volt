package com.jjricks.volt;

import java.util.*;

/** Synthetic streams only (docs/validation/live-range.md); no personal recordings or routes. */
public final class LiveRangeEstimatorTest {
    private static int checks;
    private static final double FULL = 700;

    public static void main(String[] args) {
        battery(); gapsAndResets(); stopsHold(); convergence(); coasting(); cadence(); availability();
        readoutUnits(); readoutStates(); readoutCalm(); scenarios();
        System.out.println("LiveRangeEstimatorTest: " + checks + " assertions passed");
    }

    private static LiveRangeEstimator estimator() {
        LiveRangeEstimator e = new LiveRangeEstimator();
        e.reset(LiveRangeEstimator.Prior.synthetic(18, FULL, 20));
        return e;
    }

    /** Energy left: midpoint on first reading, the crossed boundary on a one-point drop, the reading after a jump. */
    private static void battery() {
        LiveRangeEstimator e = estimator();
        e.accept(0, 0d, 0d, 80d, 1000d, false);
        near(560, e.snapshot(0).energyLeftWh, .001, "First reading: the middle of its gauge point");
        near(560 / 18.0, e.snapshot(0).rawKm, .001, "Before riding, the learned rate");
        // Power ramps 0 to 700 W, then 700 W for 10 s (trapezoids); the reading still says 80.
        e.accept(2000, 0d, 700d, 80d, 1000d, false);
        for (int i = 2; i <= 6; i++) e.accept(i * 2000, 0d, 700d, 80d, 1000d, false);
        near(560 - (350 * 2 + 700 * 10) / 3600.0, e.snapshot(12000).energyLeftWh, .001, "Energy drawn between steps lowers it");
        for (int i = 7; i <= 40; i++) e.accept(i * 2000, 0d, 700d, 80d, 1000d, false);
        near(FULL * .795, e.snapshot(80000).energyLeftWh, .001, "Never more than half a point below the reading");
        e.accept(82000, 0d, 700d, 79d, 1000d, false);
        near(FULL * .795, e.snapshot(82000).energyLeftWh, .001, "A one-point drop lands on the boundary just crossed");
        e.accept(84000, 0d, 0d, 76d, 1000d, false);
        near(FULL * .76, e.snapshot(84000).energyLeftWh, .001, "A multi-point jump trusts the reading");
        e.accept(86000, 0d, 0d, 78d, 1000d, false);
        near(FULL * .78, e.snapshot(86000).energyLeftWh, .001, "A rise trusts the reading");
        e.accept(88000, 0d, 0d, null, 1000d, false);
        near(FULL * .78, e.snapshot(88000).energyLeftWh, .001, "A missing reading keeps the estimate");
    }

    private static void gapsAndResets() {
        LiveRangeEstimator e = estimator();
        e.accept(0, 30d, 500d, 80d, 1000d, false);
        e.accept(20000, 30d, 500d, 80d, 1000d, false);
        near(560, e.snapshot(20000).energyLeftWh, 1e-9, "A 20 s gap is skipped, not integrated");
        e.accept(22000, 30d, 500d, 80d, 1000d, false);
        check(e.snapshot(22000).energyLeftWh < 560, "Integration resumes after the gap");
        check(e.snapshot(22000).atPaceMs == 2000, "Only the short interval counts at pace: " + e.snapshot(22000).atPaceMs);
        e.accept(22000 + 11 * 60000, 30d, 500d, 80d, 1000d, false);
        LiveRangeEstimator.Snapshot s = e.snapshot(22000 + 11 * 60000);
        check(s.atPaceMs == 0 && s.energyLeftWh == 560 && s.cruiseKmh == null && s.rateWhPerKm == 18, "Over 10 min of silence begins a new ride");
        e.accept(800000, 30d, 500d, 80d, 1000d, true);
        check(!e.snapshot(800000).available && e.snapshot(800000).charging, "Charging hides it");
        e.accept(802000, 30d, 500d, 80d, 1000d, false);
        check(e.snapshot(802000).available && e.snapshot(802000).atPaceMs == 0, "After charging, a fresh start");
        e.reset(null);
        e.accept(0, 30d, 500d, 80d, 1000d, false);
        check(e.snapshot(0).available && e.snapshot(0).rawKm == null && !e.snapshot(0).energyModel, "No prior: a battery reading but no number");
    }

    /** Stopped for 10 minutes: energy per km and cruising speed hold; the range drops by idle draw only. */
    private static void stopsHold() {
        LiveRangeEstimator e = estimator(); long t = 0;
        for (; t <= 300000; t += 2000) e.accept(t, 32d, 576d, 80d, 1000 + t / 1000.0 * 32 / 3600, false);
        near(18, e.snapshot(t - 2000).rateWhPerKm, 1e-6, "Cruising at 18 Wh/km");
        check(!e.snapshot(t - 2000).held, "Riding at pace: not held");
        // Braking to a stop: only the first interval, still near cruising speed with power on, counts.
        for (int i = 0; i < 6; i++, t += 2000) e.accept(t, 32d - i * 6, 0d, 80d, 1000 + 300 * 32 / 3600.0 + .01 * i / 2, false);
        LiveRangeEstimator.Snapshot before = e.snapshot(t - 2000);
        check(before.rateWhPerKm > 16, "Braking barely moves it: " + before.rateWhPerKm);
        long stop = t;
        for (; t <= stop + 600000; t += 2000) e.accept(t, 0d, 10d, 80d, 1000 + 300 * 32 / 3600.0 + .03, false);
        LiveRangeEstimator.Snapshot after = e.snapshot(t - 2000);
        check(after.held, "Stopped: held");
        near(before.rateWhPerKm, after.rateWhPerKm, 1e-9, "Energy per km holds through the stop");
        near(before.cruiseKmh, after.cruiseKmh, .05, "Cruising speed holds through the stop");
        check(Double.isFinite(after.rawKm) && after.rawKm <= before.rawKm, "Never infinity, never up when stopped");
        for (int i = 0; i < 10; i++, t += 2000) e.accept(t, 6d + i * 0.5, 900d, 80d, 1000 + 300 * 32 / 3600.0 + .03, false);
        near(before.rateWhPerKm, e.snapshot(t).rateWhPerKm, 1e-9, "Pulling away at walking pace doesn't divide by a tiny speed");
        // A slower steady pace is learned once the cruising speed has come down to it.
        double odo = 1000 + 300 * 32 / 3600.0 + .03;
        for (int i = 0; i < 300; i++, t += 2000) e.accept(t, 16d, 16 * 12d, 80d, odo += 16 * 2 / 3600.0, false);
        near(12, e.snapshot(t).rateWhPerKm, .5, "A new, slower pace is learned: " + e.snapshot(t).rateWhPerKm);
        check(e.snapshot(t).cruiseKmh < 19, "Cruising speed follows a lasting slowdown: " + e.snapshot(t).cruiseKmh);
    }

    private static void convergence() {
        LiveRangeEstimator e = estimator(); long t = 0;
        for (; t < 30000; t += 2000) e.accept(t, 32d, 640d, 90d, 1000d, false);
        check(e.snapshot(t).settling, "Settling for the first half minute");
        for (; t <= 180000; t += 2000) e.accept(t, 32d, 640d, 90d, 1000d, false);
        LiveRangeEstimator.Snapshot s = e.snapshot(t);
        check(!s.settling && s.liveShare > .95, "Settled; the prior has faded: " + s.liveShare);
        near(20, s.rateWhPerKm, .2, "Converges to the ride's 20 Wh/km");
        // Climb: power doubles at the same speed. Most of the change shows within half a minute.
        long climb = t;
        for (; t <= climb + 30000; t += 2000) e.accept(t, 32d, 1280d, 90d, 1000d, false);
        check(e.snapshot(t).rateWhPerKm > 20 + .63 * 20, "A climb registers within 30 s: " + e.snapshot(t).rateWhPerKm);
        for (; t <= climb + 120000; t += 2000) e.accept(t, 32d, 1280d, 90d, 1000d, false);
        near(40, e.snapshot(t).rateWhPerKm, .5, "and settles on the climb's rate");
        for (; t <= climb + 400000; t += 2000) e.accept(t, 32d, 32 * 200d, 90d, 1000d, false);
        near(18 * 3, e.snapshot(t).rateWhPerKm, 1e-3, "Capped at three times the learned rate");
        // Odometer cross-check: the speed sensor reads 10% high over 2 km.
        e = estimator(); t = 0;
        for (; t <= 400000; t += 2000) e.accept(t, 33d, 33 * 20d, 90d, 1000 + t / 1000.0 * 30 / 3600, false);
        near(22, e.snapshot(t).rateWhPerKm, .3, "Energy per odometer km once a km has gone by: " + e.snapshot(t).rateWhPerKm);
    }

    /** Coasting at speed teaches nothing; a lighter pace raises the range only gradually. */
    private static void coasting() {
        LiveRangeEstimator e = estimator(); long t = 0;
        for (; t <= 200000; t += 2000) e.accept(t, 32d, 576d, 90d, 1000d, false);
        double rate = e.snapshot(t).rateWhPerKm;
        for (long end = t + 30000; t <= end; t += 2000) e.accept(t, 30d, 20d, 90d, 1000d, false);
        near(rate, e.snapshot(t).rateWhPerKm, .1, "Coasting: held (only the interval easing off counts)");
        check(e.snapshot(t).held, "Coasting shows as held");
        for (long end = t + 30000; t <= end; t += 2000) e.accept(t, 32d, -300d, 90d, 1000d, false);
        near(rate, e.snapshot(t).rateWhPerKm, .1, "Regenerating downhill: held");
        for (long end = t + 20000; t <= end; t += 2000) e.accept(t, 32d, 288d, 90d, 1000d, false);
        double r = e.snapshot(t).rateWhPerKm;
        check(r < rate && r > rate * .7, "Half the power lowers the rate only gradually: " + r);
        for (long end = t + 600000; t <= end; t += 2000) e.accept(t, 32d, 160d, 90d, 1000d, false);
        near(9, e.snapshot(t).rateWhPerKm, 1e-3, "Floor: half the learned rate");
    }

    /** A 10 s poll interval ends where a 2 s one does. */
    private static void cadence() {
        double[] rates = new double[2]; double[] km = new double[2]; int i = 0;
        for (long step : new long[]{2000, 10000}) {
            LiveRangeEstimator e = estimator(); long t = 0;
            for (; t <= 600000; t += step) e.accept(t, 25d, 25 * (t < 300000 ? 16d : 24d), 70d, 1000 + t / 1000.0 * 25 / 3600, false);
            rates[i] = e.snapshot(t).rateWhPerKm; km[i++] = e.snapshot(t).rawKm;
        }
        near(rates[0], rates[1], .1, "Same rate at 2 s and 10 s polls");
        near(km[0], km[1], .1, "Same range at 2 s and 10 s polls");
    }

    private static void availability() {
        LiveRangeEstimator e = estimator();
        check(!e.snapshot(0).available, "No battery reading: unavailable");
        e.accept(0, 20d, 300d, 50d, 1000d, false);
        check(e.snapshot(0).available && !e.snapshot(0).stale, "Fresh");
        check(e.snapshot(16000).stale, "Stale after 15 s without readings");
        e.accept(16000, 20d, null, 50d, 1000d, false);
        check(e.snapshot(17000).stale, "Stale without fresh power, too");
        e.accept(0, 20d, 300d, 15d, 1000d, false);
        check(e.snapshot(0).belowSeen, "Below the lowest band with evidence");
        check(LiveRangeEstimator.Prior.of(null) == null && LiveRangeEstimator.Prior.of(LearnedRangeEstimate.unavailable("A", "x")) == null, "No ready result, no prior");
        e.accept(0, 20d, Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, false);
        check(e.snapshot(0).available, "Non-finite readings are ignored");
    }

    // ---- RangeReadout ----

    private static LiveRangeEstimator.Snapshot snap(long ms, double km, double soc, boolean settling) {
        return snap(ms, km, soc, settling, false, false, true);
    }
    private static LiveRangeEstimator.Snapshot snap(long ms, Double km, double soc, boolean settling, boolean stale, boolean below, boolean energy) {
        return new LiveRangeEstimator.Snapshot(ms, true, false, energy, km, 300d, 18d, 30d, soc, settling ? .2 : 1, 5, 60000, false, settling, below, stale);
    }

    private static void readoutUnits() {
        RangeReadout r = new RangeReadout();
        RangeReadout.Value v = r.update(snap(0, 38.0, 60, false), "mi", true);
        check(v.state == RangeReadout.State.LIVE && !v.approx && v.decimals == 1 && !v.primaryIsKm, "Live: tenths, no ≈");
        near(23.6, v.primary, 1e-9, "38 km is 23.6 mi");
        v = new RangeReadout().update(snap(0, 38.0, 60, false), "km", true);
        check(v.primaryIsKm && v.secondaryKm == null, "km mode");
        near(38.0, v.primary, 1e-9, "km value");
        v = new RangeReadout().update(snap(0, 38.0, 60, false), "dual", true);
        near(23.6, v.primary, 1e-9, "Dual: miles first"); near(38.0, v.secondaryKm, 1e-9, "Dual: km beside it");
        v = new RangeReadout().update(snap(0, 38.0, 60, false), "mi", false);
        check(v.decimals == 0, "Tenths off: whole numbers"); near(24, v.primary, 1e-9, "Rounded");
        v = new RangeReadout().update(snap(0, 170.0, 60, false), "mi", true);
        check(v.decimals == 0, "100 or more drops the tenths"); near(106, v.primary, 1e-9, "Whole above 100");
        v = new RangeReadout().update(snap(0, -0.01, 60, false), "km", true);
        check(v.primary == 0 && 1 / v.primary > 0, "Never -0.0");
        v = new RangeReadout().update(snap(0, 38.0, 60, false), "furlongs", true);
        check(!v.primaryIsKm, "Unknown units read as miles");
    }

    private static void readoutStates() {
        RangeReadout r = new RangeReadout();
        check(r.update(null, "mi", true).state == RangeReadout.State.HIDDEN, "No snapshot: hidden");
        LiveRangeEstimator.Snapshot off = new LiveRangeEstimator.Snapshot(0, false, true, true, null, null, null, null, null, 0, 0, 0, true, true, false, false);
        check(r.update(off, "mi", true).state == RangeReadout.State.HIDDEN, "Charging: hidden");
        RangeReadout.Value v = new RangeReadout().update(snap(0, 38.0, 60, true), "mi", true);
        check(v.state == RangeReadout.State.SETTLING && v.approx && v.decimals == 0, "Settling: ≈ whole number");
        r = new RangeReadout();
        r.update(snap(0, 38.0, 60, false), "mi", true);
        v = r.update(snap(2000, 38.0, 60, true), "mi", true);
        check(v.state == RangeReadout.State.LIVE, "Once live, it stays live (no tenths flicker)");
        v = new RangeReadout().update(snap(0, 6.0, 5, false), "mi", true);
        check(v.state == RangeReadout.State.LOW && v.amber && v.approx && v.decimals == 1, "5%: amber ≈ with tenths");
        near(3.7, v.primary, 1e-9, "Low value");
        v = new RangeReadout().update(snap(0, 6.0, 2, false), "mi", true);
        check(v.state == RangeReadout.State.LOW, "2%: still a number");
        v = new RangeReadout().update(snap(0, 3.0, 1, false), "mi", true);
        check(v.state == RangeReadout.State.ALMOST_EMPTY && v.primary == null, "Below 2%: Almost empty");
        v = new RangeReadout().update(snap(0, null, 60, false, false, false, false), "mi", true);
        check(v.state == RangeReadout.State.NO_HISTORY && v.primary == null, "No learned model: no number");
        v = new RangeReadout().update(snap(0, 30.0, 60, false, false, false, false), "mi", true);
        check(v.state == RangeReadout.State.NO_HISTORY && v.approx && v.decimals == 0, "Distance model: ≈ whole number");
        v = new RangeReadout().update(snap(0, 30.0, 15, false, false, true, true), "mi", true);
        check(v.state == RangeReadout.State.LIVE && v.approx, "Below the lowest band seen: ≈");
        r = new RangeReadout();
        RangeReadout.Value fresh = r.update(snap(0, 38.0, 60, false), "mi", true);
        v = r.update(snap(2000, 20.0, 60, false, true, false, true), "mi", true);
        check(v.stale && v.primary.equals(fresh.primary), "Stale: the last value, frozen");
        readoutStalePresentation();
    }

    /** While stale the estimate stays frozen, but units and tenths follow the rider's current choice. */
    private static void readoutStalePresentation() {
        // mi -> km: 24 km shown as 15 mi, then 16 s without fresh telemetry.
        RangeReadout r = new RangeReadout();
        RangeReadout.Value v = r.update(snap(0, 24.0, 60, false), "mi", false);
        near(15, v.primary, 1e-9, "Fresh: 24 km as 15 mi");
        v = r.update(snap(16000, 30.0, 60, false, true, false, true), "km", false);
        check(v.stale && v.primaryIsKm && v.decimals == 0 && v.secondaryKm == null, "Stale mi -> km: shown in km, still stale");
        near(24, v.primary, 1e-9, "Stale mi -> km: the frozen 24 km, not the new reading");
        v = r.update(snap(18000, 30.0, 60, false, true, false, true), "km", false);
        near(24, v.primary, 1e-9, "Stale in km: stays frozen");
        // mi -> dual.
        r = new RangeReadout(); r.update(snap(0, 24.0, 60, false), "mi", true);
        v = r.update(snap(16000, 30.0, 60, false, true, false, true), "dual", true);
        check(v.stale && !v.primaryIsKm && v.decimals == 1, "Stale mi -> dual: miles first, tenths");
        near(14.9, v.primary, 1e-9, "Stale dual: frozen miles"); near(24.0, v.secondaryKm, 1e-9, "Stale dual: frozen km at the same precision");
        // Precision: tenths on, then off, while stale.
        r = new RangeReadout(); r.update(snap(0, 24.0, 60, false), "km", false);
        v = r.update(snap(16000, 30.0, 60, false, true, false, true), "km", true);
        check(v.stale && v.decimals == 1, "Stale, tenths on: one decimal"); near(24.0, v.primary, 1e-9, "Stale, tenths on: frozen value");
        r = new RangeReadout(); r.update(snap(0, 23.7, 60, false), "km", true);
        v = r.update(snap(16000, 30.0, 60, false, true, false, true), "km", false);
        check(v.stale && v.decimals == 0, "Stale, tenths off: whole number"); near(24, v.primary, 1e-9, "Stale, tenths off: frozen 23.7 rounds to 24");
        // A switch is not a movement: fresh readings carry on from the reformatted value under the calm rules.
        v = r.update(snap(18000, 23.7, 60, false), "km", false);
        check(!v.stale && v.primary == 24, "Fresh again, same value: no jump after the switch");
    }

    private static void readoutCalm() {
        // Hysteresis: a filtered value under a full step from the shown one doesn't move it.
        RangeReadout r = new RangeReadout();
        r.update(snap(0, 23.6 * RangeReadout.KM_PER_MI, 60, false), "mi", true);
        RangeReadout.Value v = null;
        for (int i = 1; i <= 30; i++) v = r.update(snap(i * 2000, 23.68 * RangeReadout.KM_PER_MI, 60, false), "mi", true);
        near(23.6, v.primary, 1e-9, "Under a step away: holds");
        for (int i = 31; i <= 60; i++) v = r.update(snap(i * 2000, 23.71 * RangeReadout.KM_PER_MI, 60, false), "mi", true);
        near(23.7, v.primary, 1e-9, "A full step away: moves");
        // A large correction glides: at most 3% of the value per second (plus a 3 s allowance).
        r = new RangeReadout(); r.update(snap(0, 40.0, 60, false), "km", true);
        double prev = 40, maxDrop = 0;
        for (int i = 1; i <= 60; i++) { v = r.update(snap(i * 2000, 20.0, 60, false), "km", true); maxDrop = Math.max(maxDrop, prev - v.primary); prev = v.primary; }
        check(maxDrop <= 40 * .03 * 3 + 1e-9, "Falls glide: " + maxDrop);
        near(20, v.primary, .1 + 1e-9, "and arrive within one step (hysteresis)");
        // Rises are slower: 1% per second.
        r = new RangeReadout(); r.update(snap(0, 20.0, 60, false), "km", true);
        for (int i = 1; i <= 10; i++) v = r.update(snap(i * 2000, 40.0, 60, false), "km", true);
        check(v.primary < 20 * Math.pow(1.01, 20) + 1, "Rises glide more slowly: " + v.primary);
        // A steady countdown at 0.1 km per 12 s is never throttled and never reverses.
        r = new RangeReadout(); prev = Double.NaN; boolean monotone = true; double km = 30;
        for (int i = 0; i <= 300; i++) { v = r.update(snap(i * 2000, km - i * .1 / 6, 60, false), "km", true); monotone &= Double.isNaN(prev) || v.primary <= prev; prev = v.primary; }
        check(monotone, "Countdown has no reversals");
        near(km - 5, v.primary, .2, "and keeps up: " + v.primary);
        // Switching units re-rounds at once.
        v = r.update(snap(602000, 25.0, 60, false), "mi", true);
        near(15.5, v.primary, .1 + 1e-9, "Unit switch re-rounds at once (the filter trails the countdown by under a step)");
    }

    /** The synthetic rides in LiveRangeScenarios: react to real changes, hold through stops. */
    private static void scenarios() {
        LiveRangeScenarios.Scores s = LiveRangeScenarios.score(2, 9, new LiveRangeEstimator.Params(), new RangeReadout.Params());
        check(s.climbFall63 <= 30, "Climb shows within 30 s: " + s.climbFall63);
        check(s.speedFall63 <= 20, "A faster pace shows within 20 s: " + s.speedFall63);
        check(s.speedRise63 <= 90, "A slower pace shows within 90 s: " + s.speedRise63);
        check(s.climbRelease63 <= 90, "The end of a climb shows within 90 s: " + s.climbRelease63);
        check(s.steadyReversals <= 16, "Steady riding: few reversals: " + s.steadyReversals);
        check(s.largestStepMi <= 1.05, "No single step over about 1 mi: " + s.largestStepMi);
        check(s.stopRise <= .03 && s.coastRise <= .03, "Stops and coasting: at most 3% up: " + s.stopRise + " " + s.coastRise);
        check(s.descentRise <= .1, "A 2-minute descent: at most 10% up: " + s.descentRise);
        List<LiveRangeScenarios.Sample> low = LiveRangeScenarios.run(LiveRangeScenarios.lowBattery(), 2, 7);
        boolean sawLow = false, sawEmpty = false, afterEmptyNumber = false;
        for (LiveRangeScenarios.Sample x : low) {
            if (x.value.state == RangeReadout.State.LOW) { sawLow = true; afterEmptyNumber |= sawEmpty; }
            if (x.value.state == RangeReadout.State.ALMOST_EMPTY) sawEmpty = true;
            check(x.shown == null || Double.isFinite(x.shown), "Always finite");
        }
        check(sawLow && sawEmpty && !afterEmptyNumber, "Low battery: amber number, then Almost empty");
    }

    private static void near(double expected, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(expected - actual) <= tolerance, message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
