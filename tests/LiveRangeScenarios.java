package com.jjricks.volt;

import java.util.*;

/**
 * Synthetic rides for the live range (docs/validation/live-range.md#synthetic-rides-regression-guards): a simple
 * scooter physics model with noisy readings, streamed through LiveRangeEstimator and RangeReadout.
 * It scores how fast the shown number reacts to a real change in riding and how much it jitters
 * on steady riding. No personal recordings or routes.
 *
 * <p>Run it directly to print the score table: {@code java -cp <classes> com.jjricks.volt.LiveRangeScenarios}.
 */
final class LiveRangeScenarios {
    static final double FULL_WH = 700, PRIOR_WH_PER_KM = 18;
    private static final double MASS = 120, G = 9.81, CRR = .015, CDA = .55, RHO = 1.2, EFFICIENCY = .8, REGEN = .4, IDLE_W = 12;
    /** The speed sensor reads high, as some do; the odometer is right. */
    static final double SPEED_BIAS = 1.05;

    /** One stretch of riding: hold a target speed on a grade, coast with the motor off, or stop. */
    static final class Leg {
        final double seconds, kmh, grade; final boolean coast;
        Leg(double seconds, double kmh, double grade, boolean coast) { this.seconds = seconds; this.kmh = kmh; this.grade = grade; this.coast = coast; }
    }
    static Leg ride(double s, double kmh) { return new Leg(s, kmh, 0, false); }
    static Leg climb(double s, double kmh, double grade) { return new Leg(s, kmh, grade, false); }
    static Leg stop(double s) { return new Leg(s, 0, 0, false); }
    static Leg coast(double s) { return new Leg(s, 0, 0, true); }

    static final class Scenario {
        final String name; final double startPercent; final Leg[] legs;
        Scenario(String name, double startPercent, Leg... legs) { this.name = name; this.startPercent = startPercent; this.legs = legs; }
        double legStart(int index) { double t = 0; for (int i = 0; i < index; i++) t += legs[i].seconds; return t; }
        double seconds() { return legStart(legs.length); }
    }

    static Scenario steady() { return new Scenario("steady 32 km/h", 90, ride(900, 32)); }
    static Scenario climb() { return new Scenario("5% climb", 90, ride(240, 32), climb(180, 28, .05), ride(240, 32)); }
    static Scenario speed() { return new Scenario("32 → 22 → 32 km/h", 90, ride(240, 32), ride(300, 22), ride(240, 32)); }
    static Scenario stops() {
        List<Leg> legs = new ArrayList<Leg>();
        for (int i = 0; i < 6; i++) { legs.add(ride(90, 32)); legs.add(stop(30)); }
        legs.add(ride(90, 32));
        return new Scenario("lights: 6 stops", 90, legs.toArray(new Leg[0]));
    }
    static Scenario coasting() { return new Scenario("20 s coast", 90, ride(240, 32), coast(20), ride(180, 32)); }
    static Scenario descent() { return new Scenario("4% descent", 90, ride(180, 32), climb(120, 32, -.04), ride(180, 32)); }
    static Scenario lowBattery() { return new Scenario("low battery", 7, ride(600, 25)); }

    /** Battery power for a steady target speed on a grade, W. */
    static double steadyPower(double kmh, double grade) { double v = kmh / 3.6; return battery(force(v, grade, 0) * v); }
    /** Steady energy per km, Wh/km. */
    static double steadyRate(double kmh, double grade) { return steadyPower(kmh, grade) / kmh; }
    private static double force(double v, double grade, double accel) { return MASS * G * (CRR + grade) + .5 * RHO * CDA * v * v + MASS * accel; }
    private static double battery(double wheelW) { return (wheelW > 0 ? wheelW / EFFICIENCY : wheelW * REGEN) + IDLE_W; }

    /** One sample as the app would see it, plus the truth at that moment. */
    static final class Sample {
        long ms; double speed, power, soc, odo, trueWh, truthKm; int leg; Double shown; RangeReadout.Value value; LiveRangeEstimator.Snapshot snap;
    }

    static List<Sample> run(Scenario sc, double cadenceS, long seed, LiveRangeEstimator.Params ep, RangeReadout.Params rp, String units, boolean tenths) {
        Random random = new Random(seed);
        LiveRangeEstimator estimator = new LiveRangeEstimator(ep);
        estimator.reset(LiveRangeEstimator.Prior.synthetic(PRIOR_WH_PER_KM, FULL_WH, 20));
        RangeReadout readout = new RangeReadout(rp);
        List<Sample> out = new ArrayList<Sample>();
        double dt = .25, v = 0, wh = FULL_WH * sc.startPercent / 100, km = 0, noise = 0, next = 0, t = 0;
        int leg = 0; double legEnd = sc.legs[0].seconds, end = sc.seconds();
        for (; t <= end + 1e-9; t += dt) {
            while (leg < sc.legs.length - 1 && t >= legEnd) { leg++; legEnd += sc.legs[leg].seconds; }
            Leg l = sc.legs[leg]; double target = l.kmh / 3.6, accel, wheel;
            if (l.coast) { accel = -force(v, l.grade, 0) / MASS; if (v + accel * dt < 0) accel = -v / dt; wheel = 0; }
            else {
                accel = Math.max(-2.5, Math.min(1.5, (target - v) / 2));
                wheel = force(v, l.grade, accel) * v;
            }
            double p = l.coast ? IDLE_W : battery(wheel);
            v = Math.max(0, v + accel * dt); km += v * dt / 1000; wh -= p * dt / 3600;
            if (t + 1e-9 >= next) {
                next += cadenceS;
                noise = .7 * noise + random.nextGaussian() * .12 * Math.sqrt(1 - .49);
                Sample s = new Sample(); s.ms = Math.round(t * 1000); s.leg = leg;
                double shownSpeed = v * 3.6 * SPEED_BIAS + (v > .3 ? random.nextGaussian() * .8 : 0);
                s.speed = Math.max(0, Math.round(shownSpeed * 10) / 10.0);
                s.power = Math.round(p * (1 + (v > .3 ? noise : 0)) + random.nextGaussian() * 15);
                s.soc = Math.max(0, Math.round(wh / (FULL_WH / 100))); s.odo = Math.floor(km * 10) / 10; s.trueWh = wh;
                Leg now = sc.legs[leg]; Leg pace = now.kmh > 0 && !now.coast ? now : previousRiding(sc, leg);
                s.truthKm = Math.max(0, wh) / Math.max(1, steadyRate(pace.kmh, pace.grade));
                estimator.accept(s.ms, s.speed, s.power, s.soc, 1000 + s.odo, false);
                s.snap = estimator.snapshot(s.ms);
                s.value = readout.update(s.snap, units, tenths);
                s.shown = s.value.primary == null ? null : s.value.primary * (s.value.primaryIsKm ? 1 : RangeReadout.KM_PER_MI);
                out.add(s);
            }
        }
        return out;
    }
    private static Leg previousRiding(Scenario sc, int leg) {
        for (int i = leg; i >= 0; i--) if (sc.legs[i].kmh > 0 && !sc.legs[i].coast) return sc.legs[i];
        return sc.legs[0];
    }
    static List<Sample> run(Scenario sc, double cadenceS, long seed) {
        return run(sc, cadenceS, seed, new LiveRangeEstimator.Params(), new RangeReadout.Params(), "mi", true);
    }

    /**
     * Seconds from the start of leg until the shown range has closed 63% (or 90%) of its gap to the
     * new pace's truth; NaN when it never does within the leg.
     */
    static double reaction(List<Sample> run, Scenario sc, int leg, double share) {
        double start = sc.legStart(leg) * 1000, stop = sc.legStart(leg + 1) * 1000; Double gap0 = null;
        for (Sample s : run) {
            if (s.ms < start) continue;
            if (s.ms > stop || s.shown == null) break;
            double gap = Math.abs(s.shown - s.truthKm);
            if (gap0 == null) { gap0 = gap; if (gap0 < .2) return 0; }
            if (gap <= (1 - share) * gap0) return (s.ms - start) / 1000;
        }
        return Double.NaN;
    }
    /** Direction changes of the shown number per 10 minutes, from fromS on. */
    static double reversalsPer10Min(List<Sample> run, double fromS) {
        int reversals = 0, sign = 0; Double prev = null; long first = -1, lastMs = 0;
        for (Sample s : run) {
            if (s.ms < fromS * 1000 || s.shown == null) continue;
            if (first < 0) first = s.ms; lastMs = s.ms;
            if (prev != null && Math.abs(s.shown - prev) > 1e-9) {
                int now = s.shown > prev ? 1 : -1;
                if (sign != 0 && now != sign) reversals++;
                sign = now;
            }
            prev = s.shown;
        }
        return lastMs > first ? reversals * 600000.0 / (lastMs - first) : 0;
    }
    /** 95th percentile distance between the shown number and the noise-free truth, in mi, from fromS on. */
    static double wobbleMi(List<Sample> run, double fromS) {
        List<Double> d = new ArrayList<Double>();
        for (Sample s : run) if (s.ms >= fromS * 1000 && s.shown != null) d.add(Math.abs(s.shown - s.truthKm) / RangeReadout.KM_PER_MI);
        Collections.sort(d); return d.isEmpty() ? 0 : d.get((int) Math.floor(.95 * (d.size() - 1)));
    }
    /** Largest single change of the shown number, in mi. */
    static double largestStepMi(List<Sample> run, double fromS, double toS) {
        double max = 0; Double prev = null;
        for (Sample s : run) {
            if (s.ms < fromS * 1000 || s.ms > toS * 1000 || s.shown == null) { if (s.ms > toS * 1000) break; continue; }
            if (prev != null) max = Math.max(max, Math.abs(s.shown - prev)); prev = s.shown;
        }
        return max / RangeReadout.KM_PER_MI;
    }
    /** The largest rise of the shown number over its value when the window began, as a share. */
    static double largestRise(List<Sample> run, double fromS, double toS) {
        Double base = null; double max = 0;
        for (Sample s : run) {
            if (s.ms < fromS * 1000 || s.shown == null) continue;
            if (s.ms > toS * 1000) break;
            if (base == null) base = s.shown; else max = Math.max(max, s.shown / base - 1);
        }
        return max;
    }

    /** Medians over seeds of the scores the validation doc reports. */
    static final class Scores {
        double climbFall63, climbFall90, climbRelease63, speedRise63, speedFall63, steadyReversals, steadyWobbleMi, stopRise, coastRise, descentRise, largestStepMi;
        String row(String label) {
            return String.format(Locale.US, "%-24s climb %4.0f s (90%% %4.0f s)  climb over %4.0f s  slower %4.0f s  faster %4.0f s | steady %4.2f rev/10 min, ±%.2f mi | stops %+4.1f%%  coast %+4.1f%%  descent %+5.1f%%  step %.2f mi",
                    label, climbFall63, climbFall90, climbRelease63, speedRise63, speedFall63, steadyReversals, steadyWobbleMi, 100 * stopRise, 100 * coastRise, 100 * descentRise, largestStepMi);
        }
    }
    static Scores score(double cadenceS, int seeds, LiveRangeEstimator.Params ep, RangeReadout.Params rp) {
        double[][] v = new double[11][seeds];
        for (int i = 0; i < seeds; i++) {
            long seed = 1000 + i;
            Scenario c = climb(), sp = speed(), st = steady(), so = stops(), co = coasting(), de = descent();
            List<Sample> rc = run(c, cadenceS, seed, ep, rp, "mi", true), rs = run(sp, cadenceS, seed, ep, rp, "mi", true),
                    rt = run(st, cadenceS, seed, ep, rp, "mi", true), ro = run(so, cadenceS, seed, ep, rp, "mi", true),
                    rco = run(co, cadenceS, seed, ep, rp, "mi", true), rde = run(de, cadenceS, seed, ep, rp, "mi", true);
            v[0][i] = reaction(rc, c, 1, .63); v[1][i] = reaction(rc, c, 1, .9); v[2][i] = reaction(rc, c, 2, .63);
            v[3][i] = reaction(rs, sp, 1, .63); v[4][i] = reaction(rs, sp, 2, .63);
            v[5][i] = reversalsPer10Min(rt, 120); v[6][i] = wobbleMi(rt, 120);
            double rise = 0; for (int k = 0; k < 6; k++) rise = Math.max(rise, largestRise(ro, so.legStart(2 * k + 1) - 2, so.legStart(2 * k + 2) + 10));
            v[7][i] = rise; v[8][i] = largestRise(rco, co.legStart(1) - 2, co.legStart(2) + 15);
            v[9][i] = largestRise(rde, de.legStart(1) - 2, de.legStart(2) + 30);
            double step = 0; for (List<Sample> r : Arrays.asList(rt, ro, rco)) step = Math.max(step, largestStepMi(r, 60, 1e9));
            v[10][i] = step;
        }
        Scores s = new Scores();
        s.climbFall63 = median(v[0]); s.climbFall90 = median(v[1]); s.climbRelease63 = median(v[2]); s.speedRise63 = median(v[3]); s.speedFall63 = median(v[4]);
        s.steadyReversals = median(v[5]); s.steadyWobbleMi = median(v[6]); s.stopRise = median(v[7]); s.coastRise = median(v[8]); s.descentRise = median(v[9]); s.largestStepMi = median(v[10]);
        return s;
    }
    static double median(double[] x) { double[] y = x.clone(); Arrays.sort(y); return y.length % 2 == 1 ? y[y.length / 2] : (y[y.length / 2 - 1] + y[y.length / 2]) / 2; }

    public static void main(String[] args) {
        System.out.printf(Locale.US, "Steady energy per km: 32 km/h %.1f, 22 km/h %.1f, 28 km/h on 5%% %.1f, 32 km/h on -4%% %.1f Wh/km; prior %.0f%n",
                steadyRate(32, 0), steadyRate(22, 0), steadyRate(28, .05), steadyRate(32, -.04), PRIOR_WH_PER_KM);
        for (double cadence : new double[]{2, 5, 10})
            System.out.println(score(cadence, 15, new LiveRangeEstimator.Params(), new RangeReadout.Params()).row("defaults, " + (int) cadence + " s polls"));
    }
}
