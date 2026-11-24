package com.jjricks.volt;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * ChargeSlowdown's "Charging slowed" rule and the temperature-aware UT5 MAX estimate (ut5-fast-thermal-v10), on
 * synthesized fast charges only: 10 s readings of a 15.3 Ah pack with V = 47.6 + 0.085 x (SoC - 16) at 10 A and a
 * 0.1 ohm pack, whole-percent and whole-degree readings. No recorded charge is used, apart from the parity replay of
 * the fixture already in docs/validation/data/fast-charge-taper-v7 (no temperatures), which must not change.
 */
public final class ChargeSlowdownTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void near(double want, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(actual - want) <= tolerance, message + ": want " + want + ", got " + actual);
    }

    private static final double RATED = 15.3;
    private static final long T0 = 80_000_000L, STEP = 10_000L, MIN = 60_000L;
    private static final int UT5 = Ut5MaxFastChargeReference.PRODUCT_ID;
    private static final ChargeSessionStore.Charger FAST = ChargeSessionStore.Charger.HIGH_SPEED, STOCK = ChargeSessionStore.Charger.STOCK;
    private static final String P = "fixture";

    /** One reading. temp null: no temperature. */
    static final class Sample {
        final long t; final double soc, volts, amps; final Double temp;
        Sample(long t, double soc, double volts, double amps, Double temp) { this.t = t; this.soc = soc; this.volts = volts; this.amps = amps; this.temp = temp; }
    }

    /** How a synthesized battery management system sets the current and the warming for the next 10 s. */
    interface Bms { double amps(Sim s); double warm(Sim s); }

    /** A synthesized charge: true charge and temperature integrate; readings are whole percent and whole degrees. */
    static final class Sim {
        final List<Sample> samples = new ArrayList<>();
        double soc, temp; long t = T0; boolean tempKnown = true;
        /** Charger voltage limit reached; when, at which current; the decay's tau (minutes). */
        boolean cv; long cvAt; double cvAmps, tau = 13.5;
        /** Time the reading reaches 100% (the current falls to 0.68 A), or 0. */
        long complete;
        Sim(double soc, double temp) { this.soc = soc; this.temp = temp; }
        double reading() { return Math.min(cv && complete == 0 ? 99 : 100, Math.floor(soc + 1e-9)); }
        Double tempReading() { return tempKnown ? Double.valueOf(Math.floor(temp + 1e-9)) : null; }
        static double bulkVolts(double soc, double amps) { return 47.6 + .085 * (soc - 16) + .1 * (amps - 10); }
        /** One reading at amps and volts, then 10 s at amps with the battery warming at warm C a minute. */
        void add(double amps, double volts, double warm) {
            samples.add(new Sample(t, reading(), volts, amps, tempReading()));
            soc = Math.min(100, soc + amps * 10 / 3600 / RATED * 100);
            temp += warm / 6; t += STEP;
        }
        void bulk(double amps, double warm) { add(amps, bulkVolts(soc, amps), warm); }
        void bulkFor(double amps, double warm, long ms) { for (long end = t + ms; t < end; ) bulk(amps, warm); }
        /** Silence in readings. */
        void skip(long ms) { t += ms; }
        /**
         * Runs to completion: bulk at the BMS's current until V + 0.107 I reaches 54.53 V (or the reading reaches
         * cvAtSoc), then I decays from there with tau to 0.68 A at V = 54.53 - 0.107 I.
         */
        Sim run(Bms bms, double cvAtSoc) {
            while (complete == 0 && t < T0 + 600 * MIN) {
                if (!cv) {
                    double a = bms.amps(this), v = bulkVolts(soc, a);
                    if (v + .107 * a >= 54.53 || reading() >= cvAtSoc) { cv = true; cvAt = t; cvAmps = a; }
                    else { add(a, v, bms.warm(this)); continue; }
                }
                double a = cvAmps * Math.exp(-(t - cvAt) / (tau * MIN));
                if (a <= FastChargeTaper.END_AMPS) { complete = t; samples.add(new Sample(t, 100, 54.53 - .107 * a, a, tempReading())); break; }
                add(a, 54.53 - .107 * a, -.05);
            }
            return this;
        }
    }

    /** The observer fed with every sample in order; slowed[i] and cause[i] after sample i. */
    static final class Watch {
        final ChargeSlowdown observer = new ChargeSlowdown();
        final List<ChargeSlowdown.State> states = new ArrayList<>();
        Watch(List<Sample> samples, int product, ChargeSessionStore.Charger charger, Double limit) { this(samples, product, RATED, charger, limit); }
        Watch(List<Sample> samples, int product, double rated, ChargeSessionStore.Charger charger, Double limit) {
            for (Sample s : samples) {
                observer.observe(P, product, rated, charger, true, s.soc, s.volts, -s.amps, s.temp, limit, s.t);
                states.add(observer.state(P, s.t));
            }
        }
        Watch(List<Sample> samples) { this(samples, UT5, FAST, 100d); }
        boolean ever() { for (ChargeSlowdown.State s : states) if (s.slowed) return true; return false; }
        int shows() { int n = 0; boolean was = false; for (ChargeSlowdown.State s : states) { if (s.slowed && !was) n++; was = s.slowed; } return n; }
        /** The first sample index from which every later sample is slowed, or -1. */
        int firstShown() { for (int i = 0; i < states.size(); i++) if (states.get(i).slowed) return i; return -1; }
    }

    public static void main(String[] args) throws Exception {
        warmTopUp();
        deepEarlyCv();
        coldStart();
        ownerExample();
        hotStart();
        midBand();
        temperatureEdges();
        flicker();
        dropoutsAndRamps();
        limitApproach();
        stock();
        eightyStep();
        observerState();
        bulkMinutes();
        voltagePlanning();
        neverShorter();
        otherPack();
        historyLabels();
        ownWarming();
        if (args.length > 0) committedReplay(Paths.get(args[0]));
        System.out.println("ChargeSlowdown: " + checks + " checks passed");
    }

    // ----- Estimates -----

    private static ChargingEstimate.Value live(double value, long t) { return new ChargingEstimate.Value(value, true, t); }

    /** {v9 (no slowdown state), v10} at each sample, each with its own gate and taper, as ScooterService keeps them. */
    static final class Estimates {
        final List<ChargingEstimate.Result> v9 = new ArrayList<>(), v10 = new ArrayList<>();
        Estimates(List<Sample> samples, Watch watch, Double limit, ChargeSessionStore.Charger charger, int product) {
            ChargeReferenceGate g9 = new ChargeReferenceGate(), g10 = new ChargeReferenceGate();
            FastChargeTaper t9 = new FastChargeTaper(), t10 = new FastChargeTaper();
            ChargeProgressTracker p9 = new ChargeProgressTracker(), p10 = new ChargeProgressTracker();
            long first = samples.get(0).t;
            p9.observe(P, false, null, null, first - 1000); p10.observe(P, false, null, null, first - 1000);
            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                p9.observe(P, true, s.soc, s.amps, s.t); p10.observe(P, true, s.soc, s.amps, s.t);
                t9.observe(P, true, s.volts, s.amps, s.t); t10.observe(P, true, s.volts, s.amps, s.t);
                ChargingEstimate.Value soc = live(s.soc, s.t), rated = live(RATED, s.t);
                ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, s.t), live(s.volts, s.t), live(-s.amps, s.t), soc, rated,
                        new ChargingEstimate.Value(limit, true, s.t));
                v9.add(ChargingEstimate.forScooter(base, soc, rated, product, charger, null, g9, P + ":serial", s.t, s.t, p9.progress(P, s.t),
                        t9.tracking(P) ? t9 : null));
                v10.add(ChargingEstimate.forScooter(base, soc, rated, product, charger, null, g10, P + ":serial", s.t, s.t, p10.progress(P, s.t),
                        t10.tracking(P) ? t10 : null, watch.states.get(i)));
            }
        }
        Estimates(List<Sample> samples, Watch watch) { this(samples, watch, 100d, FAST, UT5); }
    }

    private static boolean same(Double a, Double b) { return a == null ? b == null : a.equals(b); }

    /** Every field a caller reads, compared exactly. */
    private static boolean identical(ChargingEstimate.Result a, ChargingEstimate.Result b) {
        return a.charging == b.charging && same(a.inputWatts, b.inputWatts) && same(a.inputAmps, b.inputAmps)
                && same(a.remainingMinutesToFull, b.remainingMinutesToFull) && same(a.remainingMinutesToLimit, b.remainingMinutesToLimit)
                && same(a.planningMinutesToFull, b.planningMinutesToFull) && same(a.planningMinutesToLimit, b.planningMinutesToLimit)
                && same(a.targetPercent, b.targetPercent) && Objects.equals(a.label, b.label) && Objects.equals(a.status, b.status)
                && a.learnedToFull == b.learnedToFull && a.learnedToLimit == b.learnedToLimit && a.learnedSessions == b.learnedSessions
                && a.referenceToFull == b.referenceToFull && a.referenceToLimit == b.referenceToLimit && a.referenceCharger == b.referenceCharger
                && a.stockVoltageToFull == b.stockVoltageToFull && a.stockPackModel == b.stockPackModel && same(a.limitRate, b.limitRate);
    }

    /** The displayed times are the same; planning may differ. */
    private static boolean sameShown(ChargingEstimate.Result a, ChargingEstimate.Result b) {
        return same(a.remainingMinutesToFull, b.remainingMinutesToFull) && same(a.remainingMinutesToLimit, b.remainingMinutesToLimit)
                && Objects.equals(a.label, b.label) && Objects.equals(a.status, b.status) && a.learnedToFull == b.learnedToFull
                && a.referenceToFull == b.referenceToFull && a.learnedSessions == b.learnedSessions;
    }

    private static void planningAtLeastShown(Estimates e, String name) {
        for (int i = 0; i < e.v10.size(); i++) {
            ChargingEstimate.Result r = e.v10.get(i);
            if (r.remainingMinutesToFull != null)
                check(r.planningMinutesToFull != null && r.planningMinutesToFull >= r.remainingMinutesToFull - 1e-9, name + " sample " + i + ": Charge done by plans at least the time shown");
            if (r.remainingMinutesToLimit != null)
                check(r.planningMinutesToLimit != null && r.planningMinutesToLimit >= r.remainingMinutesToLimit - 1e-9, name + " sample " + i + ": planning to the limit at least the time shown");
        }
    }

    // ----- The 12 fixtures -----

    private static final Bms WARM = new Bms() {
        public double amps(Sim s) { return s.reading() < 80 ? 10.05 : 8.0; }
        public double warm(Sim s) { return .3; }
    };

    /** 1. Warm top-up: never shows; the time shown equals v9's everywhere. */
    private static void warmTopUp() {
        Sim sim = new Sim(55, 24).run(WARM, 101);
        check(sim.complete > 0, "The warm top-up completes");
        Watch w = new Watch(sim.samples);
        check(!w.ever(), "A warm top-up never shows slowed");
        Estimates e = new Estimates(sim.samples, w);
        for (int i = 0; i < sim.samples.size(); i++) check(sameShown(e.v9.get(i), e.v10.get(i)), "Warm top-up sample " + i + ": the time shown is v9's");
        planningAtLeastShown(e, "Warm top-up");
        // From 72% Charge done by plans as v9; below it only the voltage check can lengthen it.
        for (int i = 0; i < sim.samples.size(); i++) {
            ChargingEstimate.Result a = e.v9.get(i), b = e.v10.get(i);
            if (sim.samples.get(i).soc >= 72) check(same(a.planningMinutesToFull, b.planningMinutesToFull), "Warm top-up from 72%: planning is v9's");
            else check(b.planningMinutesToFull >= a.planningMinutesToFull, "Below 72% planning only lengthens");
        }
    }

    /** 2. Deep charge with the voltage limit at 72% and 10 to 5 A over 72-80% without a step: never shows. */
    private static void deepEarlyCv() {
        Sim sim = new Sim(16, 22);
        sim.tau = 14.5;
        sim.run(new Bms() {
            public double amps(Sim s) { return 10.05; }
            public double warm(Sim s) { return .27; }
        }, 72);
        check(sim.complete > 0 && sim.cv, "The deep charge reaches its voltage limit and completes");
        Watch w = new Watch(sim.samples);
        check(!w.ever(), "A deep charge with an early voltage limit never shows slowed");
        Estimates e = new Estimates(sim.samples, w);
        for (int i = 0; i < sim.samples.size(); i++) check(sameShown(e.v9.get(i), e.v10.get(i)), "Deep charge sample " + i + ": the time shown is v9's");
        planningAtLeastShown(e, "Deep charge");
    }

    private static final Bms COLD = new Bms() {
        public double amps(Sim s) { return Math.min(s.temp < 15 ? 5.5 : s.temp < 20 ? 8.5 : 10.05, s.reading() < 80 ? 10.05 : 8.0); }
        public double warm(Sim s) { return s.temp < 15 ? .15 : .3; }
    };

    /** 3. Cold start at 58% and 13 C: shown cold from 2 min until 60 s after the current is back; no jump at 15 C. */
    private static void coldStart() {
        Sim sim = new Sim(58, 13).run(COLD, 101);
        check(sim.complete > 0, "The cold charge completes");
        Watch w = new Watch(sim.samples);
        int first = w.firstShown();
        check(first >= 0 && sim.samples.get(first).t - T0 == 2 * MIN, "Cold start shows slowed at 2 min (start and hold): " + first);
        check(w.shows() == 1, "Cold start shows once");
        int recovered = -1, cleared = -1;
        for (int i = first; i < sim.samples.size(); i++) {
            Sample s = sim.samples.get(i);
            if (recovered < 0 && s.amps >= .95 * ChargeSlowdown.expectedAmps(s.soc)) recovered = i;
            if (cleared < 0 && !w.states.get(i).slowed) cleared = i;
        }
        check(recovered > 0 && cleared > 0 && sim.samples.get(cleared).t - sim.samples.get(recovered).t == MIN,
                "Cold start clears 60 s after the current is back");
        for (int i = first; i < cleared; i++) check(w.states.get(i).cause == ChargeSlowdown.Cause.COLD, "Cold start is named cold while shown");
        check(w.states.get(cleared).coldTail && w.states.get(w.states.size() - 1).coldTail, "The cold stretch is remembered to the end of the charge");
        Estimates e = new Estimates(sim.samples, w);
        planningAtLeastShown(e, "Cold start");
        // No jump over 3 minutes (beyond the 10 s that pass) where the BMS steps from 5.5 to 8.5 A at 15 C.
        int step = -1;
        for (int i = 1; i < sim.samples.size(); i++) if (sim.samples.get(i - 1).amps == 5.5 && sim.samples.get(i).amps == 8.5) step = i;
        check(step > first && w.states.get(step).slowed, "The 15 C step is inside the slowed stretch");
        for (int i = step - 3; i <= step + 3; i++) {
            double jump = e.v10.get(i).remainingMinutesToFull - e.v10.get(i - 1).remainingMinutesToFull + STEP / 60000d;
            check(Math.abs(jump) <= 3, "No jump over 3 min at the 15 C step: " + jump);
        }
        // v9 follows the live current, so it jumps where the BMS steps up; the layer already expects the step.
        double jump9 = e.v9.get(step - 1).remainingMinutesToFull - e.v9.get(step).remainingMinutesToFull;
        check(jump9 > 3, "v9 drops by more than 3 min at the 15 C step: " + jump9);
        // Against the synthesized charge's own finish, Charge done by (plus its 20-minute buffer) is never late, and
        // never later than v9's. (This battery warms 0.15 C a minute at 5.5 A, slower than the time shown assumes,
        // which is why planning warms at 0.15.)
        double margin9 = Double.MAX_VALUE, margin10 = Double.MAX_VALUE;
        for (int i = 0; i < sim.samples.size(); i++) {
            double actual = (sim.complete - sim.samples.get(i).t) / 60000d;
            ChargingEstimate.Result a = e.v9.get(i), b = e.v10.get(i);
            if (a.remainingMinutesToFull == null || b.remainingMinutesToFull == null) continue;
            margin9 = Math.min(margin9, a.planningMinutesToFull + 20 - actual);
            margin10 = Math.min(margin10, b.planningMinutesToFull + 20 - actual);
        }
        check(margin10 >= 15 && margin10 > margin9, "Charge done by stays early on a cold charge: " + margin10 + " vs " + margin9);
        // After the stretch: the same time shown as v9, Charge done by 6 minutes longer for the cold taper.
        for (int i = cleared; i < sim.samples.size(); i++) {
            ChargingEstimate.Result a = e.v9.get(i), b = e.v10.get(i);
            check(sameShown(a, b), "After the cold stretch the time shown is v9's");
            if (a.planningMinutesToFull != null) near(a.planningMinutesToFull + 6, b.planningMinutesToFull, 1e-9, "After a cold stretch Charge done by adds 6 min");
        }
    }

    /** One stretch at constant readings after a normal start. */
    private static List<Sample> steady(double soc, double volts, double amps, Double temp, long ms) {
        List<Sample> out = new ArrayList<>();
        for (long t = 0; t <= ms; t += STEP) out.add(new Sample(T0 + t, soc, volts, amps, temp));
        return out;
    }

    /** 4. The owner's example: 430 W (8.74 A at 49.2 V) at 19 C and 40%: shown, cold, at 2 min. */
    private static void ownerExample() {
        List<Sample> samples = steady(40, 49.2, 8.74, 19d, 3 * MIN);
        Watch w = new Watch(samples);
        check(!w.states.get(11).slowed && w.states.get(12).slowed, "The owner's example shows after 60 s of start and 60 s of hold");
        check(w.states.get(12).cause == ChargeSlowdown.Cause.COLD && w.states.get(12).batteryTempC == 19, "It is named cold, with the temperature");
        Estimates e = new Estimates(samples, w);
        planningAtLeastShown(e, "Owner's example");
        ChargingEstimate.Result r = e.v10.get(15);
        check(r.remainingMinutesToFull > e.v9.get(15).remainingMinutesToFull - 60 && r.status.contains("cold battery"), "The slowed status says why");
    }

    /** 5. Hot start at 44% and 47 C: 3 A, 6 A at 45 C, 4 A after 8 min, 8 A 10 min after 41 C: shown hot throughout. */
    private static void hotStart() {
        Sim sim = new Sim(44, 47.5);
        while (sim.temp > 45) sim.bulk(3.0, -1);
        long sixAt = sim.t;
        while (sim.t < sixAt + 8 * MIN) sim.bulk(6.0, 0);
        while (sim.temp > 41) sim.bulk(4.0, -.1);
        long fortyOne = sim.t;
        while (sim.t < fortyOne + 10 * MIN) sim.bulk(4.0, 0);
        while (sim.reading() < 79) sim.bulk(8.0, .05);
        Watch w = new Watch(sim.samples);
        int first = w.firstShown();
        check(first >= 0 && sim.samples.get(first).t - T0 == 2 * MIN, "Hot start shows at 2 min");
        for (int i = first; i < sim.samples.size(); i++)
            check(w.states.get(i).slowed && w.states.get(i).cause == ChargeSlowdown.Cause.HOT, "Hot start is shown hot throughout, sample " + i);
        Estimates e = new Estimates(sim.samples, w);
        for (int i = first; i < sim.samples.size(); i++) {
            Sample s = sim.samples.get(i);
            ChargingEstimate.Result r = e.v10.get(i);
            double nominalVolts = s.volts + .1 * (10.05 - s.amps);
            FastChargeTaper.CvPoint cv = FastChargeTaper.cvPoint(s.soc, nominalVolts, 10.05, RATED);
            Double nominal = cv != null ? Double.valueOf(cv.full) : FastChargeTaper.minutes(nominalVolts, 10.05, RATED, null);
            check(nominal != null, "The voltage model covers the hot charge");
            // The bulk to 80% lasts at the live current, and never shorter than the same reading unslowed.
            double ah = RATED * (80 - s.soc) / 100, expected = Math.max(nominal + ah / s.amps * 60 - ah / 10.05 * 60, e.v9.get(i).remainingMinutesToFull);
            near(expected, r.remainingMinutesToFull, 1e-9, "Hot: the bulk lasts at the live current, sample " + i);
            near(r.remainingMinutesToFull, r.planningMinutesToFull, 1e-9, "Hot: no warming to plan for");
            check(r.status.contains("stays this low"), "The hot status says the current is assumed to stay low");
        }
    }

    /** 6. A drop to 7 A at 28 C and 50% for 5 min: plain "Charging slowed". */
    private static void midBand() {
        Sim sim = new Sim(50, 28);
        sim.bulkFor(10.05, 0, 3 * MIN);
        long drop = sim.t;
        sim.bulkFor(7.0, 0, 5 * MIN);
        long back = sim.t;
        sim.bulkFor(10.05, 0, 3 * MIN);
        Watch w = new Watch(sim.samples);
        check(w.shows() == 1, "The mid-band drop shows once");
        int first = w.firstShown();
        check(sim.samples.get(first).t - drop == MIN && w.states.get(first).cause == ChargeSlowdown.Cause.PLAIN, "Shown plain 60 s into the drop");
        int cleared = first;
        while (w.states.get(cleared).slowed) cleared++;
        check(sim.samples.get(cleared).t - back == MIN, "Cleared 60 s after the current is back");
        Estimates e = new Estimates(sim.samples, w);
        planningAtLeastShown(e, "Mid-band");
        check(e.v10.get(first).remainingMinutesToFull > e.v9.get(first - 7).remainingMinutesToFull - 2, "Slowed, the time allows for the lower current");
    }

    /** 7. The owner's example at 21, 22, 37 and 38 C and without a temperature: cold, plain, plain, hot, plain. */
    private static void temperatureEdges() {
        Double[] temps = {21d, 22d, 37d, 38d, null};
        ChargeSlowdown.Cause[] want = {ChargeSlowdown.Cause.COLD, ChargeSlowdown.Cause.PLAIN, ChargeSlowdown.Cause.PLAIN, ChargeSlowdown.Cause.HOT, ChargeSlowdown.Cause.PLAIN};
        for (int i = 0; i < temps.length; i++) {
            Watch w = new Watch(steady(40, 49.2, 8.74, temps[i], 3 * MIN));
            ChargeSlowdown.State s = w.states.get(w.states.size() - 1);
            check(s.slowed && s.cause == want[i], "At " + temps[i] + " C the cause is " + want[i] + ", not " + s.cause);
            check(same(s.batteryTempC, temps[i]), "The state carries the temperature");
        }
        // A plain one takes the cause once a temperature names it, and keeps a named one.
        ChargeSlowdown o = new ChargeSlowdown();
        long t = T0;
        for (; t <= T0 + 3 * MIN; t += STEP) o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, null, null, t);
        check(o.state(P, t).cause == ChargeSlowdown.Cause.PLAIN, "No temperature: plain");
        o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, 12d, null, t);
        check(o.state(P, t).cause == ChargeSlowdown.Cause.COLD, "A temperature arriving names it");
        t += STEP;
        o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, 25d, null, t);
        check(o.state(P, t).cause == ChargeSlowdown.Cause.COLD && o.state(P, t).batteryTempC == 25, "Once named, the cause holds while shown");
        check(ChargeSlowdown.cause(Double.NaN) == ChargeSlowdown.Cause.PLAIN, "An unusable temperature is unknown");
    }

    /** 8. Flicker: 8.9 A shows it, then 8.9/9.3 A alternating every 20 s keeps it, and it clears only after 60 s at 9.6 A. */
    private static void flicker() {
        Sim sim = new Sim(40, 25);
        sim.bulkFor(10.05, 0, 2 * MIN);
        sim.bulkFor(8.9, 0, 90_000);
        for (int k = 0; k < 15; k++) sim.bulkFor(k % 2 == 0 ? 9.3 : 8.9, 0, 20_000);
        sim.bulkFor(9.5, 0, 2 * MIN); // 0.945 x: still not cleared
        long clearFrom = sim.t;
        sim.bulkFor(9.6, 0, 2 * MIN);
        Watch w = new Watch(sim.samples);
        check(w.shows() == 1, "Flicker shows once: " + w.shows());
        int cleared = w.firstShown();
        while (w.states.get(cleared).slowed) cleared++;
        check(sim.samples.get(cleared).t - clearFrom == MIN, "Flicker clears only after 60 s at 9.6 A");
        // From a clear start, 8.9/9.3 A every 20 s never holds below 0.90 x for 60 s: the hold is unbroken.
        Sim alternating = new Sim(40, 25);
        alternating.bulkFor(10.05, 0, 2 * MIN);
        for (int k = 0; k < 30; k++) alternating.bulkFor(k % 2 == 0 ? 8.9 : 9.3, 0, 20_000);
        check(!new Watch(alternating.samples).ever(), "Alternating from clear never holds the show condition");
    }

    /** 9. A 30 s ramp from 1 to 9.5 A, one 1.8 A reading mid-bulk, and a 45 s gap then 30 s at 6 A: never shows. */
    private static void dropoutsAndRamps() {
        Sim sim = new Sim(30, 25);
        for (int k = 0; k < 3; k++) sim.bulk(1 + k * 4.25, .3);
        sim.bulkFor(10.05, .3, 10 * MIN);
        sim.bulk(1.8, .3);
        sim.bulkFor(10.05, .3, 5 * MIN);
        sim.skip(45_000);
        sim.bulkFor(6.0, .3, 30_000);
        sim.bulkFor(10.05, .3, 5 * MIN);
        Watch w = new Watch(sim.samples);
        check(!w.ever(), "Ramps, one low reading and a short low stretch after a gap never show");
    }

    /** 10. An 80% limit with the current falling from 78% to 80%: never shows. */
    private static void limitApproach() {
        List<Sample> samples = new ArrayList<>();
        long t = T0;
        for (int k = 0; k < 30; k++, t += STEP) samples.add(new Sample(t, 70 + k / 6, Sim.bulkVolts(70 + k / 6.0, 10.05), 10.05, 25d));
        for (int k = 0; k <= 30; k++, t += STEP) {
            double soc = 78 + k / 15.0, amps = 10.05 - k * .2;
            samples.add(new Sample(t, Math.floor(soc), Sim.bulkVolts(soc, amps), amps, 25d));
        }
        check(!new Watch(samples, UT5, FAST, 80d).ever(), "Approaching an 80% limit never shows");
        check(new Watch(samples, UT5, FAST, 100d).ever(), "The same fall without a limit near would show");
        check(new Watch(samples, UT5, FAST, null).ever(), "An unknown limit counts as 100");
    }

    /** 11. The standard charger at 2.0 A, at 10 C and at 45 C: never shows, and its estimate is v9's in every field. */
    private static void stock() {
        for (double temp : new double[]{10, 45}) {
            Sim sim = new Sim(40, temp);
            sim.bulkFor(2.0, 0, 10 * MIN);
            Watch w = new Watch(sim.samples, UT5, STOCK, 100d);
            check(!w.ever(), "Stock at " + temp + " C never shows");
            check(!new Watch(sim.samples, 1234, FAST, 100d).ever(), "Another model never shows");
        }
        // Even a slowed state leaves the standard charger, another model and an unknown charger untouched.
        ChargeSlowdown.State forced = new ChargeSlowdown.State(true, ChargeSlowdown.Cause.COLD, 10d, true);
        Sim sim = new Sim(40, 10);
        sim.bulkFor(2.0, 0, 10 * MIN);
        for (Object[] c : new Object[][]{{STOCK, UT5}, {FAST, 1234}, {null, UT5}}) {
            ChargeSessionStore.Charger charger = (ChargeSessionStore.Charger) c[0];
            int product = (Integer) c[1];
            Watch w = new Watch(sim.samples, product, charger, 100d);
            for (int i = 0; i < w.states.size(); i++) w.states.set(i, forced);
            Estimates e = new Estimates(sim.samples, w, 95d, charger, product);
            for (int i = 0; i < sim.samples.size(); i++) check(identical(e.v9.get(i), e.v10.get(i)), charger + "/" + product + " sample " + i + " is v9's in every field");
        }
    }

    /** 12. The BMS's 10 to 8 A step at 80% (V + 0.107 I = 54.0) never shows; at 79% only if it lasts 60 s there. */
    private static void eightyStep() {
        check(!new Watch(step(80, 0)).ever(), "The 80% step never shows");
        check(!new Watch(step(79, 50_000)).ever(), "An early step that reaches 80% within 60 s never shows");
        Watch late = new Watch(step(79, 70_000));
        check(late.ever() && late.shows() == 1, "An early step that stays below 80% for 60 s shows");
        int first = late.firstShown(), cleared = first;
        while (late.states.get(cleared).slowed) cleared++;
        check(late.states.get(cleared - 1).slowed && cleared < late.states.size(), "It clears once 8 A is expected");
    }

    /** 10.05 A at 78%, then 8.0 A from the reading stepAt, held there for heldMs, then 80%; V + 0.107 I = 54.0 throughout. */
    private static List<Sample> step(double stepAt, long heldMs) {
        List<Sample> samples = new ArrayList<>();
        long t = T0;
        for (int k = 0; k < 18; k++, t += STEP) samples.add(new Sample(t, 78, 54.0 - .107 * 10.05, 10.05, 25d));
        long end = t + heldMs;
        for (; t < end; t += STEP) samples.add(new Sample(t, stepAt, 54.0 - .107 * 8, 8.0, 25d));
        for (int k = 0; k < 18; k++, t += STEP) samples.add(new Sample(t, 80, 54.0 - .107 * 8, 8.0, 25d));
        return samples;
    }

    // ----- The observer's edges -----

    private static void observerState() {
        ChargeSlowdown o = new ChargeSlowdown();
        check(o.state(P, T0) == ChargeSlowdown.State.NONE, "Nothing watched: NONE");
        long t = T0;
        for (; t <= T0 + 3 * MIN; t += STEP) o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, -8.74, 15d, 100d, t);
        long last = t - STEP;
        check(o.state(P, last).slowed && o.state("other", last) == ChargeSlowdown.State.NONE, "Another profile reads NONE");
        check(o.state(P, last + 29_000).slowed && !o.state(P, last + 30_000).slowed, "Stale readings are not slowed");
        check(o.state(P, last + 30_000).coldTail, "A stale charge keeps its cold stretch for planning");
        // The same reading seen again changes nothing; nor does a reading without its pair or a stale charging state.
        o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 10.05, 25d, 100d, last);
        o.observe(P, UT5, (Double) RATED, FAST, true, null, 49.2, 10.05, 25d, 100d, last + 5000);
        o.observe(P, UT5, (Double) RATED, FAST, null, 40d, 49.2, 10.05, 25d, 100d, last + 6000);
        check(o.state(P, last).slowed && o.state(P, last).batteryTempC == 15, "Repeated, unpaired and unknown readings change nothing");
        // A gap of 30 s or more clears it at once and restarts the start window.
        o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, 15d, 100d, last + 30_000);
        check(!o.state(P, last + 30_000).slowed, "A 30 s gap clears at once");
        final long NEXT = T0 + 3 * MIN + STEP; // the reading after slowedNow()
        // Leaving the fast charger clears at once; coming back starts over.
        ChargeSlowdown c = slowedNow();
        c.observe(P, UT5, (Double) RATED, STOCK, true, 40d, 49.2, 8.74, 15d, 100d, NEXT);
        check(c.state(P, NEXT) == ChargeSlowdown.State.NONE, "Another charger clears at once");
        c.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, 15d, 100d, NEXT + STEP);
        check(!c.state(P, NEXT + STEP).slowed && c.state(P, NEXT + STEP).coldTail, "Back on the fast charger: a new start window, the cold stretch kept");
        // The charge ending clears everything, cold stretch included.
        c.observe(P, UT5, (Double) RATED, FAST, false, null, null, null, null, null, T0 + 5 * MIN);
        check(c.state(P, T0 + 5 * MIN) == ChargeSlowdown.State.NONE, "A finished charge clears the state");
        // The voltage limit clears at once.
        ChargeSlowdown v = slowedNow();
        v.observe(P, UT5, (Double) RATED, FAST, true, 40d, 54.38 - .107 * 8.74, 8.74, 15d, 100d, NEXT);
        check(!v.state(P, NEXT).slowed, "At the charger's voltage limit it clears at once");
        ChargeSlowdown below = slowedNow();
        below.observe(P, UT5, (Double) RATED, FAST, true, 40d, 54.37 - .107 * 8.74, 8.74, 15d, 100d, NEXT);
        check(below.state(P, NEXT).slowed, "Just below the voltage limit it holds");
        // A long silence starts a new charge, and a profile switch resets.
        ChargeSlowdown s = slowedNow();
        s.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 10.05, 25d, 100d, T0 + 40 * MIN);
        check(!s.state(P, T0 + 40 * MIN).coldTail, "After 30 min of silence it is a new charge");
        ChargeSlowdown q = slowedNow();
        q.observe("other", UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, 15d, 100d, NEXT);
        check(q.state(P, NEXT) == ChargeSlowdown.State.NONE && !q.state("other", NEXT).slowed, "A profile switch starts over");
        check(new ChargeSlowdown.State(false, ChargeSlowdown.Cause.HOT, 40d, false).cause == null, "A cause only while slowed");
    }

    /** The same states with today's fixed warming assumption (State's four-argument form). */
    private static Watch fixedWarming(List<Sample> samples) {
        Watch w = new Watch(samples);
        for (int i = 0; i < w.states.size(); i++) {
            ChargeSlowdown.State s = w.states.get(i);
            w.states.set(i, s == ChargeSlowdown.State.NONE ? s : new ChargeSlowdown.State(s.slowed, s.cause, s.batteryTempC, s.coldTail));
        }
        return w;
    }

    /** A BMS that holds the cold levels while the pack warms 0.13 C a minute throughout (Nov 24 2026's cold charge). */
    private static final Bms SLOW_WARMING = new Bms() {
        public double amps(Sim s) { return Math.min(s.temp < 15 ? 5.5 : s.temp < 20 ? 8.5 : 10.05, s.reading() < 80 ? 10.05 : 8.0); }
        public double warm(Sim s) { return .13; }
    };

    /**
     * 13. This charge's own warming rate: a cold charge from 46% and 8 C warming 0.13 C a minute is shown too short by
     * the fixed 0.25 C a minute, and closer once 10 minutes of temperatures give its own rate; the first 10 minutes,
     * and a warm or hot charge throughout, are exactly as before.
     */
    private static void ownWarming() {
        Sim sim = new Sim(46, 8).run(SLOW_WARMING, 101);
        check(sim.complete > 0, "The slow-warming cold charge completes");
        Watch w = new Watch(sim.samples), fixed = fixedWarming(sim.samples);
        Estimates now = new Estimates(sim.samples, w), before = new Estimates(sim.samples, fixed);
        planningAtLeastShown(now, "Slow-warming cold charge");
        for (int i = 0; i < sim.samples.size(); i++) {
            long t = sim.samples.get(i).t - T0;
            if (t < 10 * MIN) check(identical(before.v10.get(i), now.v10.get(i)), "Before 10 minutes of temperatures, sample " + i + " is as before");
        }
        for (long at : new long[] {12 * MIN, 20 * MIN, 30 * MIN, 40 * MIN}) {
            int i = (int) (at / STEP);
            check(w.states.get(i).slowed && w.states.get(i).cause == ChargeSlowdown.Cause.COLD, "Slowed cold at " + at / MIN + " min");
            double actual = (sim.complete - sim.samples.get(i).t) / 60000d;
            double was = before.v10.get(i).remainingMinutesToFull - actual, is = now.v10.get(i).remainingMinutesToFull - actual;
            check(was < -5, "The fixed 0.25 C a minute is too short at " + at / MIN + " min: " + was);
            check(is > was + 1.5 && Math.abs(is) < Math.abs(was), "Its own warming rate is closer at " + at / MIN + " min: " + is + " vs " + was);
            check(now.v10.get(i).planningMinutesToFull >= before.v10.get(i).planningMinutesToFull, "Charge done by is never earlier at " + at / MIN + " min");
        }
        check(w.states.get((int) (20 * MIN / STEP)).warmRate > .10 && w.states.get((int) (20 * MIN / STEP)).warmRate < .16, "About 0.13 C a minute measured by 20 min");
        warmingClamp();
        // No temperature-limited stage, nothing changes: a warm top-up, a deep warm charge through the 80% step, a hot one.
        List<List<Sample>> others = new ArrayList<>();
        others.add(new Sim(55, 24).run(WARM, 101).samples);
        others.add(new Sim(16, 25).run(WARM, 101).samples);
        Sim hot = new Sim(44, 47.5);
        while (hot.temp > 41) hot.bulk(4.0, -.1);
        hot.bulkFor(4.0, 0, 15 * MIN);
        others.add(hot.samples);
        for (List<Sample> samples : others) {
            Estimates a = new Estimates(samples, fixedWarming(samples)), b = new Estimates(samples, new Watch(samples));
            for (int i = 0; i < samples.size(); i++) check(identical(a.v10.get(i), b.v10.get(i)), "No cold stage: sample " + i + " is as before");
        }
    }

    /** A slowed cold charge at 40% whose battery reads degrees(t) (t in minutes since its start), for ms. */
    private static ChargeSlowdown warming(java.util.function.DoubleUnaryOperator degrees, long ms) {
        ChargeSlowdown o = new ChargeSlowdown();
        for (long t = T0; t <= T0 + ms; t += STEP)
            o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, -5.5, Math.floor(degrees.applyAsDouble((t - T0) / 60000d)), 100d, t);
        return o;
    }

    /** The measured rate: 0.25 C a minute until 10 minutes hold temperatures, then the fit, held to 0.10..0.25. */
    private static void warmingClamp() {
        check(ChargeSlowdown.State.NONE.warmRate == ChargeSlowdown.WARM_SHOWN && ChargeSlowdown.WARM_FLOOR == .10, "Nothing measured: 0.25; the floor is 0.10");
        ChargeSlowdown fast = warming(m -> 5 + .5 * m, 15 * MIN), flat = warming(m -> 9.5, 15 * MIN), slow = warming(m -> 8 + .13 * m, 30 * MIN);
        for (java.util.function.DoubleUnaryOperator d : Arrays.<java.util.function.DoubleUnaryOperator>asList(m -> 5 + .5 * m, m -> 9.5, m -> 8 + .13 * m)) {
            ChargeSlowdown o = warming(d, 10 * MIN - STEP);
            check(o.state(P, T0 + 10 * MIN - STEP).slowed, "Fixture: slowed cold");
            check(o.state(P, T0 + 10 * MIN - STEP).warmRate == ChargeSlowdown.WARM_SHOWN, "Before 10 minutes of temperatures: 0.25");
        }
        check(fast.state(P, T0 + 15 * MIN).warmRate == ChargeSlowdown.WARM_SHOWN, "Warming 0.5 C a minute: never faster than 0.25");
        check(flat.state(P, T0 + 15 * MIN).warmRate == ChargeSlowdown.WARM_FLOOR, "Not warming at all: the floor");
        double measured = slow.state(P, T0 + 30 * MIN).warmRate;
        check(measured > .11 && measured < .15, "Warming 0.13 C a minute is measured as such: " + measured);
        // 10 minutes of readings: the first reading in minute 10 closes the tenth.
        ChargeSlowdown edge = warming(m -> 5 + .5 * m, 10 * MIN);
        check(edge.state(P, T0 + 10 * MIN).warmRate == ChargeSlowdown.WARM_SHOWN && edge.warmRate() == ChargeSlowdown.WARM_SHOWN, "At 0.5 C a minute it holds 0.25");
        ChargeSlowdown edgeFlat = warming(m -> 9.5, 10 * MIN);
        check(edgeFlat.state(P, T0 + 10 * MIN).warmRate == ChargeSlowdown.WARM_FLOOR, "The tenth minute closes at 10 min");
        // A finished charge, a long silence, another charger or another profile starts measuring again.
        flat.observe(P, UT5, (Double) RATED, FAST, false, null, null, null, null, null, T0 + 16 * MIN);
        flat.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, -5.5, 9d, 100d, T0 + 17 * MIN);
        check(flat.state(P, T0 + 17 * MIN).warmRate == ChargeSlowdown.WARM_SHOWN, "A new charge measures afresh");
        ChargeSlowdown silent = warming(m -> 9.5, 15 * MIN);
        silent.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, -5.5, 9d, 100d, T0 + 50 * MIN);
        check(silent.state(P, T0 + 50 * MIN).warmRate == ChargeSlowdown.WARM_SHOWN, "After 30 min of silence it measures afresh");
        ChargeSlowdown stock = warming(m -> 9.5, 15 * MIN);
        stock.observe(P, UT5, (Double) RATED, STOCK, true, 40d, 49.2, -5.5, 9d, 100d, T0 + 15 * MIN + STEP);
        stock.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, -5.5, 9d, 100d, T0 + 15 * MIN + 2 * STEP);
        check(stock.state(P, T0 + 15 * MIN + 2 * STEP).warmRate == ChargeSlowdown.WARM_SHOWN, "Another charger in between: measured afresh");
        // A pack that has barely warmed in its first 10 minutes and warms later is held at the floor, not left at
        // nothing: the estimate stays within the live current lasting to 80%, and well short of it.
        Sim late = new Sim(46, 8.5);
        late.bulkFor(5.5, 0, 12 * MIN);
        late.run(SLOW_WARMING, 101);
        Watch lw = new Watch(late.samples);
        int i = (int) (11 * MIN / STEP);
        ChargingEstimate.Result r = new Estimates(late.samples, lw).v10.get(i);
        check(lw.states.get(i).warmRate == ChargeSlowdown.WARM_FLOOR, "Barely warmed in 10 minutes: the floor");
        double lasting = ChargingEstimate.extraBulkMinutes(late.samples.get(i).soc, 80, 5.5, 10.05, null, 1, 0, RATED);
        // From 8 C the floor reaches 15 C only after 80%: no longer than the live current lasting, the bound the layer
        // keeps for any slowdown. From 13 C the warm-up still counts at the floor.
        double floor = ChargingEstimate.extraBulkMinutes(late.samples.get(i).soc, 80, 5.5, 10.05, 8d, ChargeSlowdown.WARM_FLOOR, .5, RATED);
        double warmer = ChargingEstimate.extraBulkMinutes(late.samples.get(i).soc, 80, 5.5, 10.05, 13d, ChargeSlowdown.WARM_FLOOR, .5, RATED);
        check(floor <= lasting + 1e-9 && warmer < lasting - 5, "At the floor: " + floor + " and " + warmer + " vs " + lasting);
        double actual = (late.complete - late.samples.get(i).t) / 60000d;
        check(r.remainingMinutesToFull - actual < 5, "Not wildly long: " + (r.remainingMinutesToFull - actual));
    }

    private static ChargeSlowdown slowedNow() {
        ChargeSlowdown o = new ChargeSlowdown();
        for (long t = T0; t <= T0 + 3 * MIN; t += STEP) o.observe(P, UT5, (Double) RATED, FAST, true, 40d, 49.2, 8.74, 15d, 100d, t);
        check(o.state(P, T0 + 3 * MIN).slowed, "Fixture: slowed");
        return o;
    }

    /** The per-stage bulk time agrees with a fine time-stepped simulation of the same levels. */
    private static void bulkMinutes() {
        for (double soc : new double[]{20, 45, 58, 70, 79.5, 82})
            for (double amps : new double[]{3, 5.5, 7, 8.74, 9.5})
                for (Double temp : new Double[]{null, 5d, 13d, 14.6d, 17d, 19.4d, 19.9d, 20d, 30d, 45d})
                    for (double warm : new double[]{.15, .25})
                        for (double offset : new double[]{0, .5}) {
                            double expected = ChargeSlowdown.expectedAmps(soc), end = soc < 80 ? 80 : 85;
                            if (amps >= expected) continue;
                            double got = ChargingEstimate.extraBulkMinutes(soc, end, amps, expected, temp, warm, offset, RATED);
                            near(stepped(soc, end, amps, expected, temp, warm, offset), got, .06, "Bulk minutes at " + soc + "% " + amps + " A " + temp + " C");
                            check(got >= -1e-9, "Slowed bulk never takes less than at the expected current");
                        }
        near(0, ChargingEstimate.extraBulkMinutes(80, 80, 5, 8, 10d, .25, .5, RATED), 0, "No bulk left: nothing extra");
        double a = ChargingEstimate.extraBulkMinutes(40, 80, 5.5, 10.05, 12d, .15, 0, RATED), b = ChargingEstimate.extraBulkMinutes(40, 80, 5.5, 10.05, 12d, .25, .5, RATED);
        check(a > b, "Planning's slower warming takes longer than the time shown");
    }

    /** The replay candidate's 0.05-minute stepped form. */
    private static double stepped(double soc, double end, double amps, double expected, Double temp, double warm, double offset) {
        double ah = RATED * (end - soc) / 100, atExpected = ah / expected * 60;
        if (temp == null || temp >= 20) return ah / amps * 60 - atExpected;
        double t = 0, left = ah, c = temp + offset, dt = .001;
        while (left > 0 && t < 600) {
            double level = c < 15 ? 5.5 : c < 20 ? 8.5 : expected, a = Math.min(expected, Math.max(amps, level));
            left -= a * dt / 60; t += dt; c += warm * dt;
        }
        return t - atExpected;
    }

    /**
     * Below 72% at 9 A or more without past charges, Charge done by takes the longer of the time to 100% and the
     * bundled voltage model (cvPoint's where it applies, which at 9.5 A or more already sets the time shown).
     */
    private static void voltagePlanning() {
        int longer = 0, total = 0;
        for (double soc = 20; soc < 72; soc += 1) {
            for (double[] c : new double[][]{{10.05, -.4}, {10.05, 0}, {10.05, .4}, {9.2, -.4}, {9.2, 0}, {9.2, .4}}) {
                double amps = c[0], offset = c[1];
                long t = T0 + (long) soc * MIN;
                double volts = Sim.bulkVolts(soc, amps) + offset;
                ChargingEstimate.Value s = live(soc, t), rated = live(RATED, t);
                ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(volts, t), live(-amps, t), s, rated, live(100, t));
                ChargingEstimate.Result a = ChargingEstimate.forScooter(base, s, rated, UT5, FAST, null, null, null, t, t, null, null);
                ChargingEstimate.Result b = ChargingEstimate.forScooter(base, s, rated, UT5, FAST, null, null, null, t, t, null, null, ChargeSlowdown.State.NONE);
                check(sameShown(a, b), "Voltage planning never changes the time shown");
                if (!a.referenceToFull || a.planningMinutesToFull == null) continue;
                total++;
                FastChargeTaper.CvPoint cv = FastChargeTaper.cvPoint(soc, volts, amps, RATED);
                Double voltage = cv != null ? Double.valueOf(cv.full) : FastChargeTaper.minutes(volts, amps, RATED, null);
                double want = voltage == null ? a.planningMinutesToFull : Math.max(a.planningMinutesToFull, voltage);
                near(want, b.planningMinutesToFull, 1e-9, "Planning at " + soc + "% " + amps + " A " + offset + " V is the longer of the reference and the voltage model");
                near(want, b.planningMinutesToLimit, 1e-9, "A 100% limit plans the same");
                if (want > a.planningMinutesToFull) longer++;
            }
        }
        check(total > 0 && longer > 0, "The voltage model lengthens some bulk plans: " + longer + " of " + total);
        // A lower limit's planning and a slower current are left alone.
        long t = T0;
        ChargingEstimate.Value s = live(40, t), rated = live(RATED, t);
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(Sim.bulkVolts(40, 10.05) + .4, t), live(-10.05, t), s, rated, live(80, t));
        ChargingEstimate.Result a = ChargingEstimate.forScooter(base, s, rated, UT5, FAST, null, null, null, t, t, null, null);
        ChargingEstimate.Result b = ChargingEstimate.forScooter(base, s, rated, UT5, FAST, null, null, null, t, t, null, null, ChargeSlowdown.State.NONE);
        check(same(a.planningMinutesToLimit, b.planningMinutesToLimit), "An 80% limit's planning is unchanged");
        base = ChargingEstimate.calculate(live(1, t), live(Sim.bulkVolts(40, 8.8) + .4, t), live(-8.8, t), s, rated, live(100, t));
        a = ChargingEstimate.forScooter(base, s, rated, UT5, FAST, null, null, null, t, t, null, null);
        b = ChargingEstimate.forScooter(base, s, rated, UT5, FAST, null, null, null, t, t, null, null, ChargeSlowdown.State.NONE);
        check(identical(a, b), "Below 9 A nothing is planned differently");
    }

    // ----- Review of #561 -----

    /** One estimate at a single reading, with or without a slowdown state (null: v9's form). */
    private static ChargingEstimate.Result at(double soc, double volts, double amps, double rated, Double limit, ChargeSessionStore.Curve curve,
            ChargeSlowdown.State state, boolean withState) {
        long t = T0;
        ChargingEstimate.Value s = live(soc, t), r = live(rated, t);
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(volts, t), live(-amps, t), s, r, new ChargingEstimate.Value(limit, true, t));
        return withState ? ChargingEstimate.forScooter(base, s, r, UT5, FAST, curve, null, P + ":serial", t, t, null, null, state)
                : ChargingEstimate.forScooter(base, s, r, UT5, FAST, curve, null, P + ":serial", t, t, null, null);
    }

    private static void atLeast(Double slowed, Double plain, String what) {
        check(plain == null || slowed != null && slowed >= plain - 1e-9, what + ": slowed " + slowed + " < unslowed " + plain);
    }

    /** The source a recording logs and the label agree: past charges exactly when the label names them. */
    private static void consistent(ChargingEstimate.Result r, String what) {
        ChargeSessionStore.Estimate saved = ChargingEstimate.recordingSnapshot(r);
        boolean personal = saved.fullSource == ChargeSessionStore.Source.PERSONAL || saved.limitSource == ChargeSessionStore.Source.PERSONAL;
        check(r.label != null && r.label.startsWith("From ") == personal && personal == (r.learnedSessions > 0), what + ": label " + r.label + " with " + saved.fullSource + "/" + saved.limitSource);
    }

    /**
     * A slowdown never makes the time shown or Charge done by shorter than the same reading without it: the review's
     * probe (86%, 53.1 V, 3 A, 40 C: 42.8 fell to 35.6), its neighbours, and a sweep of level, current, temperature,
     * voltage and limit.
     */
    private static void neverShorter() {
        for (double soc : new double[]{82, 84, 86}) {
            double volts = 53.1 - .085 * (86 - soc);
            List<Sample> samples = steady(soc, volts, 3.0, 40d, 3 * MIN);
            Watch w = new Watch(samples);
            ChargeSlowdown.State state = w.states.get(w.states.size() - 1);
            check(state.slowed && state.cause == ChargeSlowdown.Cause.HOT, "3 A at " + soc + "% and 40 C is slowed, hot");
            ChargingEstimate.Result a = at(soc, volts, 3, RATED, 100d, null, null, false), b = at(soc, volts, 3, RATED, 100d, null, state, true);
            atLeast(b.remainingMinutesToFull, a.remainingMinutesToFull, soc + "% shown");
            atLeast(b.planningMinutesToFull, a.planningMinutesToFull, soc + "% planned");
            atLeast(b.planningMinutesToFull, b.remainingMinutesToFull, soc + "% planning covers the time shown");
            if (soc == 86) check(a.remainingMinutesToFull > 40 && b.remainingMinutesToFull >= a.remainingMinutesToFull, "The probe no longer drops: " + a.remainingMinutesToFull + " -> " + b.remainingMinutesToFull);
        }
        int n = 0, unexplained = 0;
        for (double soc = 20; soc <= 98; soc += 2)
            for (double amps : new double[]{2, 3, 4, 5.5, 6, 7, 7.5, 8.5, 9})
                for (Double temp : new Double[]{null, 5d, 13d, 17d, 19.5d, 25d, 40d, 47d})
                    for (double offset : new double[]{-1, -.3, 0, .3, .6})
                        for (Double limit : new Double[]{100d, 95d, 80d, null})
                            for (boolean coldTail : new boolean[]{false, true}) {
                                double volts = Math.min(54.6, Sim.bulkVolts(soc, amps) + offset);
                                ChargeSlowdown.State state = new ChargeSlowdown.State(true, ChargeSlowdown.cause(temp), temp, coldTail);
                                ChargingEstimate.Result a = at(soc, volts, amps, RATED, limit, null, null, false), b = at(soc, volts, amps, RATED, limit, null, state, true);
                                String what = soc + "% " + amps + " A " + volts + " V " + temp + " C limit " + limit;
                                // The time shown may sit below the unslowed estimate only where a BMS cold level explains the current.
                                if (!ChargeSlowdown.coldExplains(temp, amps, ChargeSlowdown.expectedAmps(soc))) {
                                    unexplained++;
                                    atLeast(b.remainingMinutesToFull, a.remainingMinutesToFull, what + " full shown");
                                    atLeast(b.remainingMinutesToLimit, a.remainingMinutesToLimit, what + " limit shown");
                                }
                                atLeast(b.planningMinutesToFull, a.planningMinutesToFull, what + " full planned");
                                atLeast(b.planningMinutesToFull, a.remainingMinutesToFull, what + " full planned over the unslowed time shown");
                                atLeast(b.planningMinutesToLimit, a.planningMinutesToLimit, what + " limit planned");
                                atLeast(b.planningMinutesToFull, b.remainingMinutesToFull, what + " planning covers the time shown");
                                if (b.remainingMinutesToLimit != null) atLeast(b.planningMinutesToLimit, b.remainingMinutesToLimit, what + " limit planning covers the time shown");
                                consistent(b, what);
                                n++;
                            }
        check(n > 50_000 && unexplained > n / 2, "The sweep covers " + n + " readings, " + unexplained + " not explained by the cold");
        // The cold model only where a cold level explains the current, and below the stage's expected current.
        check(ChargeSlowdown.coldExplains(13d, 5.52, 10.05) && ChargeSlowdown.coldExplains(17d, 8.55, 10.05) && ChargeSlowdown.coldExplains(19d, 8.74, 10.05)
                && ChargeSlowdown.coldExplains(15d, 5.5, 10.05), "The BMS's cold levels explain 5.5 A below 15 C (and at the step) and 8.5 A to 20 C");
        check(!ChargeSlowdown.coldExplains(19d, 3, 10.05) && !ChargeSlowdown.coldExplains(17d, 5, 10.05) && !ChargeSlowdown.coldExplains(17d, 8.5, 8.0)
                && !ChargeSlowdown.coldExplains(20d, 8.5, 10.05) && !ChargeSlowdown.coldExplains(null, 8.5, 10.05) && !ChargeSlowdown.coldExplains(17d, 5.5, 10.05),
                "A current far from the level, a level above the 8 A stage's, 20 C or no temperature is not explained");
        // The review's probe: a slowdown far below the cold level is never shown as recovering, and nothing jumps at 20 C.
        for (double[] c : new double[][]{{40, 3}, {41, 3}, {50, 5}, {86, 3}}) {
            double soc = c[0], amps = c[1], volts = soc >= 80 ? 53.1 : Sim.bulkVolts(soc, amps);
            ChargingEstimate.Result a = at(soc, volts, amps, RATED, 100d, null, null, false);
            Double before = null;
            for (double temp : new double[]{14, 17, 19, 19.9, 20, 21}) {
                ChargeSlowdown.State state = new ChargeSlowdown.State(true, ChargeSlowdown.cause(temp), temp, false);
                ChargingEstimate.Result b = at(soc, volts, amps, RATED, 100d, null, state, true);
                String what = "Probe " + soc + "% " + amps + " A " + temp + " C";
                // 5 A at 14 C sits within the 5.5 A cold level's band, so there the warm-up may be shown.
                if (!(amps == 5 && temp < 15)) atLeast(b.remainingMinutesToFull, a.remainingMinutesToFull, what + " shown");
                atLeast(b.planningMinutesToFull, a.planningMinutesToFull, what + " planned");
                if (temp >= 17) check(before == null || Math.abs(b.remainingMinutesToFull - before) < 1e-9, what + ": no jump at the 20 C edge, " + before + " -> " + b.remainingMinutesToFull);
                if (temp >= 17) before = b.remainingMinutesToFull;
            }
        }
    }

    /** Another pack (25 Ah): no slowdown state and no layer, so every field is v9's. */
    private static void otherPack() {
        List<Sample> samples = steady(40, 49.2, 8.74, 15d, 3 * MIN);
        check(!new Watch(samples, UT5, 25, FAST, 100d).ever(), "A 25 Ah pack is not watched");
        check(new Watch(samples, UT5, 16.8, FAST, 100d).ever() && !new Watch(samples, UT5, 16.9, FAST, 100d).ever(), "Within 10% of 15.3 Ah only");
        ChargeSlowdown unknown = new ChargeSlowdown();
        for (Sample s : samples) unknown.observe(P, UT5, null, FAST, true, s.soc, s.volts, s.amps, s.temp, 100d, s.t);
        check(unknown.state(P, samples.get(samples.size() - 1).t) == ChargeSlowdown.State.NONE, "An unknown capacity is not watched");
        ChargeSlowdown.State forced = new ChargeSlowdown.State(true, ChargeSlowdown.Cause.COLD, 15d, true);
        for (double soc = 20; soc < 100; soc += 5)
            for (double amps : new double[]{3, 8.74, 10.05}) {
                double volts = Sim.bulkVolts(soc, amps);
                check(identical(at(soc, volts, amps, 25, 100d, null, null, false), at(soc, volts, amps, 25, 100d, null, forced, true)),
                        "25 Ah at " + soc + "% " + amps + " A is v9's in every field");
                check(identical(at(soc, volts, amps, 25, 100d, null, null, false), at(soc, volts, amps, 25, 100d, null, ChargeSlowdown.State.NONE, true)),
                        "25 Ah gets no voltage planning or cold tail either");
            }
    }

    /**
     * With past charges: a slowed estimate whose time comes from the bundled model carries the bundled label and is
     * recorded as the reference; one that keeps past charges' time keeps their label. Never shorter either way.
     */
    private static void historyLabels() throws Exception {
        Path dir = Files.createTempDirectory("slowdown-history-");
        try {
            for (int k = 0; k < 3; k++) {
                Sim sim = new Sim(40 + k, 24).run(WARM, 101);
                List<FastChargeTaperReplay.Sample> rows = new ArrayList<>();
                for (Sample x : sim.samples) {
                    FastChargeTaperReplay.Sample r = new FastChargeTaperReplay.Sample();
                    r.session = k; r.charger = "HIGH_SPEED"; r.stop = "COMPLETE"; r.elapsed = x.t - T0; r.soc = x.soc; r.volts = x.volts; r.amps = x.amps;
                    rows.add(r);
                }
                File file = dir.resolve(String.format("00000000-0000-0000-0000-%012d.csv", k)).toFile();
                FastChargeTaperReplay.journal(rows, file);
                file.setLastModified(FastChargeTaperReplay.BASE_WALL + k * 1000L);
            }
            ChargeSessionStore.Curve curve = new ChargeSessionStore(dir.toFile()).snapshot().curves.get(ChargeSessionStore.key(P, FAST));
            check(curve != null && curve.qualifiedSessions >= 2, "Synthesized history qualifies");
            int personal = 0, bundled = 0, mixed = 0;
            for (double soc = 42; soc < 98; soc += 2)
                for (double amps : new double[]{3, 5.5, 7, 8.5})
                    for (Double temp : new Double[]{12d, 25d, 40d}) {
                        double volts = Sim.bulkVolts(soc, amps);
                        ChargeSlowdown.State state = new ChargeSlowdown.State(true, ChargeSlowdown.cause(temp), temp, false);
                        ChargingEstimate.Result a = at(soc, volts, amps, RATED, 100d, curve, null, false), b = at(soc, volts, amps, RATED, 100d, curve, state, true);
                        String what = "History, " + soc + "% " + amps + " A " + temp + " C";
                        consistent(a, what + " unslowed");
                        consistent(b, what + " slowed");
                        // Past charges are named exactly when they set the time at the expected current.
                        double expected = ChargeSlowdown.expectedAmps(soc);
                        boolean rerun = at(soc, volts + .1 * (expected - amps), expected, RATED, 100d, curve, null, false).learnedToFull;
                        check(b.learnedToFull == rerun, what + ": past charges named " + b.learnedToFull + ", but they set the nominal time: " + rerun);
                        if (a.learnedToFull != rerun) mixed++;
                        if (!ChargeSlowdown.coldExplains(temp, amps, ChargeSlowdown.expectedAmps(soc))) atLeast(b.remainingMinutesToFull, a.remainingMinutesToFull, what);
                        atLeast(b.planningMinutesToFull, a.planningMinutesToFull, what);
                        if (b.learnedToFull) personal++; else bundled++;
                    }
            check(personal > 0 && bundled > 0 && mixed > 0, "Both kinds occur: past charges " + personal + ", bundled " + bundled
                    + ", and " + mixed + " where the reading without a slowdown names the other source");
        } finally {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) { for (Path f : files) Files.delete(f); }
            Files.delete(dir);
        }
    }

    // ----- The committed fast-charge fixture -----

    /**
     * docs/validation/data/fast-charge-taper-v7 (no temperatures) through the observer and both forms of the
     * estimate, each charge learning from the ones before it: slowed only in its one cold charge, every time shown
     * outside a slowed stretch unchanged, every
     * standard-charger row unchanged in every field, and Charge done by changed only upward on the fast charger's
     * bulk below 72% without past charges.
     */
    private static void committedReplay(Path data) throws Exception {
        List<List<FastChargeTaperReplay.Sample>> sessions = FastChargeTaperReplay.read(data.resolve("sessions.csv"));
        Path tmp = Files.createTempDirectory("slowdown-replay-");
        int rows = 0, planned = 0, slowed = 0;
        Set<Integer> slowedSessions = new TreeSet<>();
        try {
            for (int index = 0; index < sessions.size(); index++) {
                List<FastChargeTaperReplay.Sample> session = sessions.get(index);
                Path history = Files.createTempDirectory(tmp, "history-");
                try {
                    for (int prior = 0; prior < index; prior++) {
                        File file = history.resolve(String.format("00000000-0000-0000-0000-%012d.csv", prior)).toFile();
                        FastChargeTaperReplay.journal(sessions.get(prior), file);
                        file.setLastModified(FastChargeTaperReplay.BASE_WALL + prior * 1000L);
                    }
                    ChargeSessionStore.Charger charger = ChargeSessionStore.Charger.valueOf(session.get(0).charger);
                    ChargeSessionStore.Curve curve = new ChargeSessionStore(history.toFile()).snapshot().curves.get(ChargeSessionStore.key(P, charger));
                    ChargeReferenceGate g9 = new ChargeReferenceGate(), g10 = new ChargeReferenceGate();
                    ChargeProgressTracker progress = new ChargeProgressTracker();
                    FastChargeTaper t9 = new FastChargeTaper(), t10 = new FastChargeTaper();
                    ChargeSlowdown slowdown = new ChargeSlowdown();
                    long start = FastChargeTaperReplay.mono(session.get(0));
                    progress.observe(P, false, null, null, start - 1000);
                    for (FastChargeTaperReplay.Sample s : session) {
                        long t = FastChargeTaperReplay.mono(s);
                        progress.observe(P, true, s.soc, s.amps, t);
                        t9.observe(P, true, s.volts, s.amps, t); t10.observe(P, true, s.volts, s.amps, t);
                        slowdown.observe(P, UT5, (Double) RATED, charger, true, s.soc, s.volts, s.amps, null, 100d, t);
                        ChargeSlowdown.State state = slowdown.state(P, t);
                        ChargingEstimate.Value soc = live(s.soc, t), rated = live(RATED, t);
                        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(s.volts, t), live(s.amps, t), soc, rated, live(100, t));
                        ChargeProgressTracker.Progress p = progress.progress(P, t);
                        ChargingEstimate.Result a = ChargingEstimate.forScooter(base, soc, rated, UT5, charger, curve, g9, P + ":serial", t, t, p, t9.tracking(P) ? t9 : null);
                        ChargingEstimate.Result b = ChargingEstimate.forScooter(base, soc, rated, UT5, charger, curve, g10, P + ":serial", t, t, p, t10.tracking(P) ? t10 : null, state);
                        rows++;
                        if (charger == STOCK) { check(!state.slowed && identical(a, b), "Standard-charger session " + s.session + " is unchanged in every field"); continue; }
                        if (state.slowed) {
                            slowed++; slowedSessions.add(s.session);
                            check(b.planningMinutesToFull >= b.remainingMinutesToFull, "Slowed: Charge done by plans at least the time shown");
                            continue;
                        }
                        check(sameShown(a, b), "Session " + s.session + " shows the same time outside a slowed stretch");
                        double tail = state.coldTail ? ChargeSlowdown.COLD_TAIL_MINUTES : 0;
                        if (a.planningMinutesToFull != null && Math.abs(a.planningMinutesToFull + tail - b.planningMinutesToFull) > 1e-9) {
                            planned++;
                            check(s.soc < 72 && Math.abs(s.amps) >= 9 && !a.learnedToFull && b.planningMinutesToFull > a.planningMinutesToFull + tail,
                                    "Session " + s.session + ": outside the cold tail only the bulk's Charge done by changes, and only longer");
                        }
                    }
                } finally {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(history)) { for (Path f : files) Files.delete(f); }
                    Files.delete(history);
                }
            }
        } finally { Files.delete(tmp); }
        check(rows == 7813, "The replay covers every committed sample: " + rows);
        // The fixture holds one cold charge (no temperatures recorded, so its cause reads plain); nothing else slows.
        check(slowedSessions.size() == 1, "Only the fixture's cold charge shows slowed: " + slowedSessions);
        System.out.println("ChargeSlowdown committed replay: " + rows + " samples; slowed only in session(s) " + slowedSessions + " (" + slowed
                + " samples); elsewhere the times shown are unchanged; " + planned + " fast-charge bulk plans longer");
    }
}
