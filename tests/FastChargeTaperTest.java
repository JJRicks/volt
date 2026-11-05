package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** High-speed voltage/current model, runtime refit and hold, composition, and owner-charge replay. */
public final class FastChargeTaperTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void near(double want, Double actual, double tolerance, String message) {
        check(actual != null && Math.abs(actual - want) <= tolerance, message + ": " + actual);
    }
    private static final long T0 = 50_000_000L, SEC = 1000L, MIN = 60_000L;
    private static final ChargeSessionStore.Charger FAST = ChargeSessionStore.Charger.HIGH_SPEED, STOCK = ChargeSessionStore.Charger.STOCK;
    private static ChargingEstimate.Value live(double value, long t) { return new ChargingEstimate.Value(value, true, t); }
    private static ChargingEstimate.Result estimate(double soc, double volts, double amps, Double target, int pid,
            ChargeSessionStore.Charger family, FastChargeTaper taper, long t) {
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(volts, t), live(-amps, t), live(soc, t), live(15.3, t),
                new ChargingEstimate.Value(target, true, t));
        return ChargingEstimate.forScooter(base, live(soc, t), live(15.3, t), pid, family, null, null, null, t, t, null, taper);
    }

    public static void main(String[] args) throws Exception {
        model();
        tracker();
        composition();
        ownTaper();
        plateau();
        noise();
        planning();
        replay(Paths.get(args[0]));
        System.out.println("FastChargeTaper: " + checks + " checks passed");
    }

    private static void model() {
        check(FastChargeTaper.bulkAh(0) == 0 && FastChargeTaper.bulkAh(-1) == 0, "At or above the CV voltage no bulk charge remains");
        near(.097, FastChargeTaper.bulkAh(.05), 1e-9, "Headroom interpolates between calibration points");
        near(8.59, FastChargeTaper.bulkAh(5.8), 1e-9, "The calibrated range ends at the reference start");
        check(FastChargeTaper.bulkAh(5.81) == null && FastChargeTaper.bulkAh(Double.NaN) == null, "No extrapolation below the reference charge");
        for (double h = .01; h <= 5.8; h += .01) check(FastChargeTaper.bulkAh(h) >= FastChargeTaper.bulkAh(h - .01), "More headroom never needs less charge");
        near(14.1 * Math.log(2.2 / .68), FastChargeTaper.minutes(54.30, 2.2, 15.3, null), 1e-9, "At the voltage limit only the exponential taper remains");
        // Session 11 at 75%: 10.09 A, 52.07 V, observed 47.9 minutes to 100%.
        double headroom = 54.53 - .107 * 10.09 - 52.07;
        near(FastChargeTaper.bulkAh(headroom) / 10.09 * 60 + 14.1 * Math.log(10.09 / .68), FastChargeTaper.minutes(52.07, 10.09, 15.3, null), 1e-9,
                "Bulk adds constant-current time before the taper");
        near(46.8, FastChargeTaper.minutes(52.07, 10.09, 15.3, null), .3, "Bulk lands near the observed finish");
        check(FastChargeTaper.minutes(54.48, .6, 15.3, null) == 0, "Below the end current the taper is complete");
        check(FastChargeTaper.minutes(51.4, 1.98, 15.3, null) == null, "A low current below the voltage limit is not a high-speed bulk stage");
        check(FastChargeTaper.minutes(46, 10.09, 15.3, null) == null, "Below the calibrated voltage range");
        check(FastChargeTaper.minutes(52, 12, 15.3, null) == null && FastChargeTaper.minutes(52, 10, 10, null) == null,
                "Unsupported current or pack capacity");
        check(FastChargeTaper.minutes(Double.NaN, 8, 15.3, null) == null && FastChargeTaper.minutes(53, 0, 15.3, null) == null, "Invalid readings");
    }

    private static void tracker() {
        FastChargeTaper t = new FastChargeTaper();
        check(!t.tracking("a") && t.tau() == FastChargeTaper.TAU_MINUTES, "No charge, reference tau");
        t.observe("a", false, null, null, T0);
        double[] fitted = new double[61];
        for (int i = 0; i <= 60; i++) {
            long now = T0 + MIN + i * 10 * SEC;
            t.observe("a", true, 54.0, 8.0 * Math.exp(-(i / 6d) / 11), now);
            fitted[i] = t.tau();
        }
        check(t.tracking("a") && !t.tracking("b"), "Tracking belongs to one profile");
        check(fitted[17] == FastChargeTaper.TAU_MINUTES, "Under three minutes of decay keeps the reference tau");
        check(fitted[27] < FastChargeTaper.TAU_MINUTES && fitted[27] > 11.1, "The charge's own tau phases in");
        near(11, fitted[60], .05, "Six minutes of decay give this charge's tau");
        check(t.tapering(54.0, 7.0) && !t.tapering(53.0, 7.0), "Falling current at CV voltage is the taper");
        near(11 * Math.log(3 / .68), FastChargeTaper.minutes(54.0, 3, 15.3, t), .05, "Estimates use the refitted tau");

        t.rememberPersonal(12, 2, T0 + 20 * MIN);
        double[] held = t.heldPersonal(T0 + 21 * MIN);
        check(held != null && Math.abs(held[0] - 11) < 1e-9 && held[1] == 2, "A held personal finish keeps counting down");
        check(t.heldPersonal(T0 + 23 * MIN + 1) == null && t.heldPersonal(T0 + 19 * MIN) == null, "The hold lasts three minutes and never runs backwards");
        t.rememberPersonal(Double.NaN, 1, T0 + 24 * MIN);
        check(t.heldPersonal(T0 + 24 * MIN) == null, "Invalid personal minutes are not held");

        t.rememberPersonal(5, 1, T0 + 25 * MIN);
        t.observe("a", false, 54.4, 0d, T0 + 26 * MIN);
        check(!t.tracking("a") && t.tau() == FastChargeTaper.TAU_MINUTES && t.heldPersonal(T0 + 26 * MIN) == null, "Charging off forgets the charge");

        FastChargeTaper slow = new FastChargeTaper();
        for (int i = 0; i <= 60; i++) slow.observe("a", true, 54.0, 8.0 * Math.exp(-(i / 6d) / 40), T0 + i * 10 * SEC);
        check(slow.tau() == FastChargeTaper.TAU_HIGH, "A slow decay is limited to twenty minutes");
        FastChargeTaper derate = new FastChargeTaper();
        for (int i = 0; i <= 60; i++) derate.observe("a", true, 52.6 + i * .01, i < 30 ? 10.09 : 8.06, T0 + i * 10 * SEC);
        check(derate.tau() == FastChargeTaper.TAU_MINUTES && !derate.tapering(53.2, 8.06), "A bulk current step below CV voltage is not a taper");
        FastChargeTaper flat = new FastChargeTaper();
        for (int i = 0; i <= 60; i++) flat.observe("a", true, 53.6, 8.06, T0 + i * 10 * SEC);
        check(flat.tau() == FastChargeTaper.TAU_MINUTES && !flat.tapering(53.6, 8.06), "Constant current at CV voltage is still bulk");
        // A derate above the CV floor, then a long flat plateau below 8 A's own CV voltage (review probe).
        FastChargeTaper plateau = new FastChargeTaper();
        plateau.observe("a", true, 53.4, 10.09, T0);
        for (int i = 1; i <= 61; i++) plateau.observe("a", true, 53.4 + i * .003, 8.06, T0 + i * 10 * SEC);
        check(plateau.tau() == FastChargeTaper.TAU_MINUTES && !plateau.tapering(53.583, 8.06), "A current step then a flat plateau is not a taper");
        near(FastChargeTaper.minutes(53.583, 8.06, 15.3, null), FastChargeTaper.minutes(53.583, 8.06, 15.3, plateau), 1e-9, "The plateau adds no decay history");
        for (int i = 0; i < 40; i++) plateau.observe("a", true, 53.7, 8.06 * Math.exp(-(i / 6d) / 12), T0 + 11 * MIN + i * 10 * SEC);
        check(plateau.tapering(53.7, 5.0) && plateau.tau() < FastChargeTaper.TAU_MINUTES && plateau.tau() > 11, "A real taper after the plateau is still learned");
        FastChargeTaper noisy = new FastChargeTaper();
        noisy.observe("a", true, 53.6, 8.3, T0);
        for (int i = 1; i <= 61; i++) noisy.observe("a", true, 53.6, i % 2 == 0 ? 8.06 : 8.04, T0 + i * 10 * SEC);
        check(noisy.tau() == FastChargeTaper.TAU_MINUTES && !noisy.tapering(53.6, 8.04), "Quantization flicker on a plateau is not a decay");
        flat.observe("a", true, 53.7, 7.5, T0 + 20 * MIN + FastChargeTaper.MAX_SILENCE_MS);
        check(!flat.tapering(53.7, 7.5), "A long silence starts a new charge");
        flat.observe("b", true, 53.7, 7.5, T0 + 60 * MIN);
        check(!flat.tracking("a") && flat.tracking("b"), "Switching scooters restarts tracking");
        flat.reset();
        check(!flat.tracking("b"), "Reset forgets the charge");
    }

    private static void composition() {
        // Session 12's first reading: charger already at its voltage limit at 91%.
        ChargingEstimate.Result early = estimate(91, 53.551, 8.04, 100d, 2538, FAST, null, T0);
        ChargeSessionStore.Estimate saved = ChargingEstimate.recordingSnapshot(early);
        near(36.0, early.remainingMinutesToFull, .1, "Voltage and current replace the 10-minute current rate");
        check(early.remainingMinutesToLimit.equals(early.remainingMinutesToFull) && early.planningMinutesToFull.equals(early.remainingMinutesToFull),
                "A 100% limit and Charge done by use the same displayed time");
        check(saved.fullSource == ChargeSessionStore.Source.FAST_REFERENCE && saved.limitSource == ChargeSessionStore.Source.FAST_REFERENCE
                && saved.learnedSessions == 0, "Recorded as the bundled high-speed reference");
        check(saved.version.equals("ut5-fast-taper-v8"), "Algorithm changes carry a new capture version");
        ChargingEstimate.Result lower = estimate(91, 53.551, 8.04, 95d, 2538, FAST, null, T0);
        near(15.3 * .04 / 8.04 * 60, lower.remainingMinutesToLimit, 1e-9, "A lower cutoff keeps the current rate");
        check(ChargingEstimate.recordingSnapshot(lower).limitSource == ChargeSessionStore.Source.CURRENT_RATE, "and says so");
        ChargingEstimate.Result high = estimate(98, 54.33, 2.1, 99.5d, 2538, FAST, null, T0);
        check(high.remainingMinutesToLimit <= high.remainingMinutesToFull, "A cutoff never takes longer than 100%");
        ChargingEstimate.Result other = estimate(91, 53.551, 8.04, 100d, -1, FAST, null, T0);
        check(!other.referenceToFull && Math.abs(other.remainingMinutesToFull - 15.3 * .09 / 8.04 * 60) < 1e-9, "Other models keep the current rate");
        ChargingEstimate.Result reference = estimate(60, 51.71, 10.09, 100d, 2538, FAST, null, T0);
        near(49.25, reference.remainingMinutesToFull, 1, "The SOC reference still owns charges it can place");
        for (double[] r : new double[][]{{91, 53.551, 8.04}, {60, 51.0, 2.3}, {95, 54.2, 1.2}, {99, 54.4, .9}}) {
            FastChargeTaper state = new FastChargeTaper();
            state.observe("a", true, r[1], r[2], T0);
            state.rememberPersonal(30, 3, T0);
            ChargingEstimate.Result with = estimate(r[0], r[1], r[2], 100d, 2538, STOCK, state, T0), without = estimate(r[0], r[1], r[2], 100d, 2538, STOCK, null, T0);
            check(Objects.equals(with.remainingMinutesToFull, without.remainingMinutesToFull) && Objects.equals(with.remainingMinutesToLimit, without.remainingMinutesToLimit)
                    && with.learnedToFull == without.learnedToFull && with.referenceToFull == without.referenceToFull, "Standard-charger estimates ignore high-speed state");
        }
        FastChargeTaper state = new FastChargeTaper();
        state.observe("a", true, 54.34, 2.0, T0);
        state.rememberPersonal(12.5, 2, T0);
        ChargingEstimate.Result held = estimate(99, 54.34, 2.0, 100d, 2538, FAST, state, T0 + 24 * SEC);
        near(12.1, held.remainingMinutesToFull, 1e-9, "A personal dropout keeps counting down the last personal finish");
        ChargeSessionStore.Estimate heldSaved = ChargingEstimate.recordingSnapshot(held);
        check(heldSaved.fullSource == ChargeSessionStore.Source.PERSONAL && heldSaved.limitSource == ChargeSessionStore.Source.PERSONAL
                && heldSaved.learnedSessions == 2, "A held finish remains personal evidence");
        ChargingEstimate.Result expired = estimate(99, 54.34, 2.0, 100d, 2538, FAST, state, T0 + 4 * MIN);
        check(!expired.learnedToFull && expired.referenceToFull, "After the hold the voltage/current model returns");
        check(estimate(100, 54.48, .68, 100d, 2538, FAST, state, T0 + MIN).remainingMinutesToFull == 0, "At 100% nothing is held");
        // Hard admission failures are never bridged by the hold or replaced by the taper (review probe).
        for (double amps : new double[]{0, .01, .049}) {
            FastChargeTaper low = new FastChargeTaper();
            low.observe("a", true, 54.34, 2.0, T0);
            low.rememberPersonal(12.5, 2, T0);
            ChargingEstimate.Result refused = estimate(99, 54.34, amps, 100d, 2538, FAST, low, T0 + 24 * SEC);
            check(refused.remainingMinutesToFull == null && refused.remainingMinutesToLimit == null && refused.planningMinutesToLimit == null
                    && !refused.learnedToFull, "Low current " + amps + " A stays without an estimate");
        }
    }

    /** A taper of tau 11 at 54 V, observed every 10 s for the given samples (first decay point at 0.5 min). */
    private static FastChargeTaper decayed(int samples) {
        FastChargeTaper t = new FastChargeTaper();
        for (int i = 0; i < samples; i++) t.observe("a", true, 54.0, 8.0 * Math.exp(-(i / 6d) / 11), T0 + i * 10 * SEC);
        return t;
    }

    private static boolean same(ChargingEstimate.Result a, ChargingEstimate.Result b) {
        return a == null ? b == null : b != null && Objects.equals(a.remainingMinutesToFull, b.remainingMinutesToFull)
                && Objects.equals(a.remainingMinutesToLimit, b.remainingMinutesToLimit) && Objects.equals(a.planningMinutesToFull, b.planningMinutesToFull)
                && Objects.equals(a.planningMinutesToLimit, b.planningMinutesToLimit) && Objects.equals(a.targetPercent, b.targetPercent)
                && Objects.equals(a.label, b.label) && Objects.equals(a.status, b.status) && a.charging == b.charging
                && a.learnedToFull == b.learnedToFull && a.learnedToLimit == b.learnedToLimit && a.learnedSessions == b.learnedSessions
                && a.referenceToFull == b.referenceToFull && a.referenceToLimit == b.referenceToLimit && a.referenceCharger == b.referenceCharger
                && a.stockVoltageToFull == b.stockVoltageToFull && a.stockPackModel == b.stockPackModel;
    }

    /** ut5-fast-taper-v8: once FIT_FULL minutes of this charge's own decay are watched, its taper owns the estimate. */
    private static void ownTaper() {
        // Decay points start at sample 3 (0.5 min); FIT_FULL (6 min) of them span samples 3-39.
        FastChargeTaper five = decayed(39), six = decayed(40);
        long now = T0 + 6 * MIN + 40 * SEC;
        check(five.tapering(54.0, 3.3) && !five.ownTaper(54.0, 3.3, now), "Under FIT_FULL minutes of decay the taper is not yet this charge's own");
        check(six.ownTaper(54.0, 3.3, now), "FIT_FULL minutes of decay establish this charge's own taper");
        check(!six.ownTaper(53.2, 3.3, now) && !six.ownTaper(54.0, 7.9, now), "Only while tapering: below CV voltage or near the CV current it is not");
        check(!decayed(3).ownTaper(54.0, 7.0, now) && !new FastChargeTaper().ownTaper(54.0, 3.3, now), "No decay, no own taper");
        // The newest decay point (sample 39) is at 6.5 minutes: RECENT_MS later still counts, a moment more does not.
        check(six.ownTaper(54.0, 3.3, T0 + 390 * SEC + FastChargeTaper.RECENT_MS) && !six.ownTaper(54.0, 3.3, T0 + 390 * SEC + FastChargeTaper.RECENT_MS + 1)
                && !six.ownTaper(54.0, 3.3, T0 + 390 * SEC - 1), "Only while its newest decay point is recent");
        six.observe("a", false, null, null, T0 + 10 * MIN);
        check(!six.ownTaper(54.0, 3.3, now), "Charging off forgets it");

        // 95% at 3 A: the SOC-indexed reference places this sample (about 21 minutes), v7's choice.
        FastChargeTaper own = decayed(61), early = decayed(39);
        double soc = 95, volts = 54.25, amps = 3.0;
        long t = T0 + 11 * MIN;
        ChargingEstimate.Result v7 = estimate(soc, volts, amps, 100d, 2538, FAST, null, t);
        check(v7.referenceToFull && v7.status.startsWith("Experimental UT5 MAX high-speed reference"), "The reference covers this sample");
        check(same(v7, estimate(soc, volts, amps, 100d, 2538, FAST, early, t)), "Before its own taper is established the reference stays");
        ChargingEstimate.Result mine = estimate(soc, volts, amps, 100d, 2538, FAST, own, t);
        near(FastChargeTaper.minutes(volts, amps, 15.3, own), mine.remainingMinutesToFull, 1e-12, "Its own taper replaces the reference");
        near(own.tau() * Math.log(amps / FastChargeTaper.END_AMPS), mine.remainingMinutesToFull, 1e-12, "using this charge's tau");
        check(Math.abs(own.tau() - 11) < .1 && Math.abs(mine.remainingMinutesToFull - v7.remainingMinutesToFull) > 3, "A different answer from the reference");
        check(mine.referenceToFull && !mine.learnedToFull && mine.learnedSessions == 0 && mine.referenceCharger == FAST
                && mine.status.startsWith("Experimental UT5 MAX high-speed estimate from pack voltage")
                && mine.label.equals(v7.label), "Shown and labelled as the existing high-speed estimate");
        ChargeSessionStore.Estimate saved = ChargingEstimate.recordingSnapshot(mine);
        check(saved.version.equals("ut5-fast-taper-v8") && saved.fullSource == ChargeSessionStore.Source.FAST_REFERENCE
                && saved.limitSource == ChargeSessionStore.Source.FAST_REFERENCE, "Recorded as FAST_REFERENCE under the new estimator version");
        check(mine.remainingMinutesToLimit.equals(mine.remainingMinutesToFull) && mine.planningMinutesToFull.equals(mine.remainingMinutesToFull),
                "A 100% limit and Charge done by use the displayed time");
        ChargingEstimate.Result cutoff = estimate(soc, volts, amps, 99d, 2538, FAST, own, t), cutoffV7 = estimate(soc, volts, amps, 99d, 2538, FAST, null, t);
        check(cutoff.remainingMinutesToFull.equals(mine.remainingMinutesToFull)
                && cutoff.remainingMinutesToLimit.equals(Math.min(cutoffV7.remainingMinutesToLimit, mine.remainingMinutesToFull))
                && cutoff.referenceToLimit == cutoffV7.referenceToLimit, "A lower cutoff keeps its evidence and source, never past 100%");

        // Every other case is exactly v7: the branch does not apply, whatever the taper state says.
        Object[][] others = {
            {soc, volts, amps, 2538, STOCK, "standard charger"},
            {soc, volts, amps, -1, FAST, "another model"},
            {100d, 54.47, .7, 2538, FAST, "100% displayed"},
            {soc, volts, .03, 2538, FAST, "current too low for an estimate"},
        };
        for (Object[] o : others) {
            double s = (Double) o[0], v = (Double) o[1], a = (Double) o[2];
            int pid = (Integer) o[3];
            ChargeSessionStore.Charger family = (ChargeSessionStore.Charger) o[4];
            check(same(estimate(s, v, a, 100d, pid, family, early, t), estimate(s, v, a, 100d, pid, family, own, t)), "Unchanged for " + o[5]);
        }
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(volts, t), live(-amps, t), live(soc, t), live(15.3, t), live(100, t));
        ChargingEstimate.Value stale = new ChargingEstimate.Value(soc, false, t), small = live(10, t);
        check(same(ChargingEstimate.forScooter(base, stale, live(15.3, t), 2538, FAST, null, null, null, t, t, null, early),
                ChargingEstimate.forScooter(base, stale, live(15.3, t), 2538, FAST, null, null, null, t, t, null, own)), "Unchanged without a valid battery %");
        check(same(ChargingEstimate.forScooter(base, live(soc, t), small, 2538, FAST, null, null, null, t, t, null, early),
                ChargingEstimate.forScooter(base, live(soc, t), small, 2538, FAST, null, null, null, t, t, null, own)), "Unchanged outside the calibrated pack");
        check(same(ChargingEstimate.forScooter(base, live(soc, t), live(15.3, t), 2538, null, null, null, null, t, t, null, own), base),
                "Unknown charger keeps the live rate");
    }

    /**
     * Two identical synthetic completed high-speed charges, 50% to 100% at 54 V and the given amps,
     * 5 minutes per percent: past charges that match a live reading at that current (not a measured charge).
     */
    private static ChargeSessionStore.Curve history(Path dir, double amps) throws Exception { return history(dir, amps, 30); }

    /** The same, with readingsPerPercent 10-s readings (readingsPerPercent / 6 minutes) per percent. */
    private static ChargeSessionStore.Curve history(Path dir, double amps, int readingsPerPercent) throws Exception {
        for (int h = 0; h < 2; h++) {
            List<FastChargeTaperReplay.Sample> samples = new ArrayList<>();
            for (int i = 0; i <= 50 * readingsPerPercent; i++) {
                FastChargeTaperReplay.Sample s = new FastChargeTaperReplay.Sample();
                s.session = h; s.charger = "HIGH_SPEED"; s.stop = "COMPLETE"; s.elapsed = i * 10 * SEC;
                s.soc = Math.min(100, 50 + Math.floor(i / (double) readingsPerPercent)); s.volts = 54.0; s.amps = -amps;
                samples.add(s);
            }
            FastChargeTaperReplay.journal(samples, dir.resolve(String.format("00000000-0000-0000-0000-%012d.csv", h)).toFile());
        }
        ChargeSessionStore.Curve curve = new ChargeSessionStore(dir.toFile()).snapshot().curves.get(ChargeSessionStore.key("fixture", FAST));
        check(curve != null && curve.qualifiedSessions == 2, "Two synthetic past charges qualify");
        return curve;
    }

    /** One synthetic case: the taper state after its readings, the live current and the time now. */
    private static final class Case {
        final FastChargeTaper taper = new FastChargeTaper(); double amps; long now;
        Case() { taper.observe("a", true, 54.0, -10.0, T0); } // A 10 A ceiling at the charger's voltage limit.
        void at(double a, long t) { amps = a; now = t; taper.observe("a", true, 54.0, -a, t); }
    }

    /** A tau-11 decay from 8 A, read every 10 s at 0.01 A, whose decay points span 6.5 minutes. */
    private static Case decaying() { return decaying(11, 39); }

    /** A decay of that tau from 8 A, read every 10 s at 0.01 A for samples 0 to last. */
    private static Case decaying(double tau, int last) {
        Case c = new Case();
        for (int i = 0; i <= last; i++) c.at(Math.round(800 * Math.exp(-(i / 6d) / tau)) / 100d, T0 + MIN + i * 10 * SEC);
        return c;
    }

    /**
     * decaying(), then the decay stops and the current creeps: a reading 0.01 A lower every
     * stepMin minutes (each one a new low), read every 10 s up to readMin minutes after the stop.
     */
    private static Case creeping(double stepMin, double readMin) {
        Case c = decaying();
        double stop = c.amps;
        long from = c.now;
        for (long t = from + 10 * SEC; t <= from + (long) (readMin * MIN); t += 10 * SEC)
            c.at(Math.round((stop - .01 * Math.floor((t - from) / (stepMin * MIN) + 1e-9)) * 100) / 100d, t);
        return c;
    }

    /**
     * A charger's step down in the taper, read every 10 s to minute 11 of decay: a tau-13 decay
     * from 8 A to 4.32 A at minute 8, a straight-line fall to 2.5 A over fallSeconds, then a slow
     * tau-40 decay. Records whether this charge's own taper applies at minute 8 and at any reading
     * after it, and the fastest fall between two readings of the step down as a decay's tau.
     */
    private static final class StepDown {
        final Case c = new Case();
        boolean before, after;
        double fastestTau = Double.POSITIVE_INFINITY;
        StepDown(int fallSeconds) {
            double top = Math.round(800 * Math.exp(-8 / 13d)) / 100d, fall = fallSeconds / 60d, previous = top;
            for (long t = T0 + MIN; t <= T0 + 12 * MIN; t += 10 * SEC) {
                double m = (t - T0 - MIN) / 60000d;
                double a = m <= 8 ? 8 * Math.exp(-m / 13) : m <= 8 + fall ? top - (top - 2.5) * (m - 8) / fall : 2.5 * Math.exp(-(m - 8 - fall) / 40);
                c.at(Math.round(a * 100) / 100d, t);
                if (m > 8 && m <= 8 + fall) fastestTau = Math.min(fastestTau, (10 * SEC / 60000d) / Math.log(previous / c.amps));
                previous = c.amps;
                boolean taking = c.taper.ownTaper(54.0, c.amps, c.now);
                if (m <= 8) before = taking; else after |= taking;
            }
        }
    }

    /**
     * Review of 4da6840 (Codex): a flat or stale decay must not take over as this charge's own
     * taper. Each case is pinned at 75% with two matching synthetic past charges, at a 100% and a
     * 95% limit, to the time, sources, label and Charge done by minutes that v7 (f529f43) gives for
     * the same readings; 4da6840 showed 34.7, 34.6 and 20.6 minutes from FAST_REFERENCE instead of
     * 122.5 from past charges in the first three. Rechecks of a30493a added a slow real taper the
     * fit clamps (raw tau 28 and 40) and a stopped decay followed by a 0.01 A creep, every 0.5 to 5
     * minutes, whose new lows kept the old fit recent; both now give v7's answer too. Rechecks of
     * 5648b59 added a decay reported in 2.5-minute steps and a charger's step down in the taper,
     * rechecks of c01a57d the same step down spread over 90 s.
     */
    private static void plateau() throws Exception {
        Path tmp = Files.createTempDirectory("fast-taper-plateau-");
        try {
            // Codex's case: four lows over six minutes (8.00 to 7.97 A), then 20 minutes at 7.97 A.
            // The fit is flat (tau() keeps the reference) and the last new low is 20 minutes old.
            Case flatStale = new Case();
            double[] lows = {8.00, 7.99, 7.98, 7.97};
            for (int i = 0; i < 4; i++) flatStale.at(lows[i], T0 + (1 + 2 * i) * MIN);
            for (long t = T0 + 7 * MIN + 10 * SEC; t <= T0 + 27 * MIN; t += 10 * SEC) flatStale.at(7.97, t);
            // A flat fit that is still recent: a new 0.01 A low every minute (tau about 800 minutes).
            Case flat = new Case();
            for (int i = 0; i <= 7; i++) flat.at(8.00 - .01 * i, T0 + (1 + i) * MIN);
            flat.at(7.93, T0 + 8 * MIN + 30 * SEC);
            // A real tau-11 decay that stopped: no new low for the next 10 minutes, so nothing is pruned.
            Case stale = decaying();
            for (long end = stale.now + 10 * MIN, t = stale.now + 10 * SEC; t <= end; t += 10 * SEC) stale.at(stale.amps, t);
            // The same decay read 10 s after its newest low: this charge's own taper takes over.
            Case own = decaying();
            own.now += 10 * SEC;
            // A plateau (a new 0.01 A low every minute for 8 minutes), then 90 s of a tau-11 decay:
            // its recent points fall fast enough, but over FIT_FULL minutes the fit is still flat.
            Case flatThenFalling = new Case();
            for (int k = 0; k <= 8; k++) for (int j = 0; j < 6; j++) flatThenFalling.at(8.00 - .01 * k, T0 + (1 + k) * MIN + j * 10 * SEC);
            for (int i = 1; i <= 9; i++) flatThenFalling.at(Math.round(792 * Math.exp(-(i / 6d) / 11)) / 100d, T0 + 9 * MIN + (50 + i * 10) * SEC);
            // Rechecks of a30493a: a slow real taper (raw tau 40, and 28) read 10 s after 7.5 minutes
            // of it; tau() clamps both at 20, so a30493a showed 45.5 and 43.9 minutes from
            // FAST_REFERENCE (a tau-40 decay from 6.63 A needs about 91).
            Case slow = decaying(40, 45), over = decaying(28, 45);
            slow.now += 10 * SEC; over.now += 10 * SEC;
            // A stopped tau-11 decay, then a 0.01 A creep: each step is a new low, so the newest
            // decay point stays recent and a30493a showed 21.9 to 29.1 minutes from FAST_REFERENCE.
            // Creep every 0.5 to 5 minutes (the reviewer's case is every 2), read at a creep low 2
            // to 5 minutes after the stop.
            double[][] creeps = {{2, 2}, {2, 4}, {1, 3}, {1.5, 3}, {3, 3}, {5, 5}, {.5, 3}};
            Case[] crept = new Case[creeps.length];
            for (int i = 0; i < creeps.length; i++) crept[i] = creeping(creeps[i][0], creeps[i][1]);
            check(Math.abs(flatStale.taper.tau() - FastChargeTaper.TAU_MINUTES) < 1e-12 && Math.abs(flat.taper.tau() - FastChargeTaper.TAU_MINUTES) < 1e-12,
                    "A flat fit falls back to the reference tau");
            check(Math.abs(stale.taper.tau() - 11) < .05 && Math.abs(own.taper.tau() - 11) < .05, "A stopped decay keeps its old fit");
            check(flatStale.taper.tapering(54.0, 7.97) && flat.taper.tapering(54.0, 7.93) && stale.taper.tapering(54.0, stale.amps),
                    "All three are still tapering by the old rule");
            check(!flatStale.taper.ownTaper(54.0, 7.97, flatStale.now), "Codex's plateau is not this charge's own taper");
            check(!flat.taper.ownTaper(54.0, 7.93, flat.now), "A flat fit is not, however recent");
            check(!stale.taper.ownTaper(54.0, stale.amps, stale.now), "A stopped decay is not, however good its fit");
            check(own.taper.ownTaper(54.0, own.amps, own.now), "A recent, usable decay is");
            check(Double.isNaN(flatThenFalling.taper.fittedTau()) && !flatThenFalling.taper.ownTaper(54.0, flatThenFalling.amps, flatThenFalling.now),
                    "A decay of 90 s after a plateau is not yet this charge's own taper");
            check(slow.taper.fittedTau() > 39 && slow.taper.fittedTau() < 41 && over.taper.fittedTau() > 27.5 && over.taper.fittedTau() < 28.5
                    && slow.taper.tau() == FastChargeTaper.TAU_HIGH && over.taper.tau() == FastChargeTaper.TAU_HIGH,
                    "Slow real tapers: raw fits of 40 and 28, both clamped at TAU_HIGH");
            check(slow.taper.tapering(54.0, slow.amps) && !slow.taper.ownTaper(54.0, slow.amps, slow.now)
                    && !over.taper.ownTaper(54.0, over.amps, over.now), "A taper slower than tau() x (1 + SLOWER) is not this charge's own");
            for (int i = 0; i < crept.length; i++) {
                Case c = crept[i];
                String where = "Creep every " + creeps[i][0] + " min, " + creeps[i][1] + " min after the stop";
                // The reading now is a creep low, so the newest decay point is recent, and the whole fit still looks like a decay.
                check(c.taper.tapering(54.0, c.amps) && c.taper.fittedTau() > 11 && c.taper.fittedTau() < 16, where + ": an old fit, kept recent");
                check(!c.taper.ownTaper(54.0, c.amps, c.now), where + ": its recent points are too slow");
            }
            // The hand-back can take up to RECENT_FIT, about three minutes: a minute after the stop, the creep's first low still counts.
            Case early = creeping(1, 1);
            check(early.taper.ownTaper(54.0, early.amps, early.now), "A minute after the stop it has not handed back yet");
            // Rechecks of 5648b59. A real tau-11 decay reported in 2.5-minute steps (like the stairs25
            // probe, which is a tau-12 decay; a30493a switched source 10 times in 30 minutes there):
            // each step is a new low, so the newest point stays recent and the whole fit is a good
            // decay, but RECENT_FIT minutes never hold three of its points. It never takes over
            // (without that count it does).
            Case stairs = new Case();
            boolean stairsOwn = false;
            for (long t = T0 + MIN; t <= T0 + 26 * MIN; t += 10 * SEC) {
                stairs.at(Math.round(800 * Math.exp(-Math.floor((t - T0 - MIN) / (2.5 * MIN)) * 2.5 / 11)) / 100d, t);
                stairsOwn |= stairs.taper.ownTaper(54.0, stairs.amps, stairs.now);
            }
            check(stairs.taper.tapering(54.0, stairs.amps) && Math.abs(stairs.taper.fittedTau() - 11) < .3, "Stairs: a real tau-11 decay, " + stairs.taper.fittedTau());
            check(!stairsOwn, "A decay reported in 2.5-minute steps never has three points in RECENT_FIT minutes, so never takes over");
            // A charger's step down in the taper (the stepslow40 probe): a tau-13 decay from 8 A, a
            // fall to 2.5 A within 30 s, then a slow tau-40 decay. The fall steepens both fits, down to
            // tau()'s TAU_LOW clamp, before the slow decay after it can fail the pace check: 5648b59
            // showed 9.9 to 21.5 minutes from FAST_REFERENCE for about 3 minutes where about 50 remained.
            // While the fall is in the RECENT_FIT window, the step check (STEP_TAU) hands back.
            StepDown step = new StepDown(30);
            Case stepDown = step.c;
            check(step.before && !step.after, "A step down hands back from its first reading for as long as it is in the RECENT_FIT window");
            check(stepDown.taper.tau() == FastChargeTaper.TAU_LOW && stepDown.taper.fittedTau() < FastChargeTaper.TAU_LOW,
                    "The step drives tau() to its TAU_LOW clamp, " + stepDown.taper.fittedTau());
            // Rechecks of c01a57d: the same fall spread over 90 s (the ramp90t40 probe). No two
            // readings fall faster than a tau-2 decay (tau 2.17 at the fall's end), so a STEP_TAU of 2
            // kept the own taper for about 3.5 minutes, down to 10.4 minutes from FAST_REFERENCE where
            // 50 remained (Charge done by 19.6 minutes past its buffer; v7 +32). STEP_TAU 4 catches it.
            StepDown slowerStep = new StepDown(90);
            check(slowerStep.fastestTau > 2 && slowerStep.fastestTau < FastChargeTaper.STEP_TAU,
                    "A 90-s step down falls faster than STEP_TAU but no faster than a tau-2 decay, " + slowerStep.fastestTau);
            check(slowerStep.before && !slowerStep.after, "A 90-s step down hands back from its first reading for as long as it is in the RECENT_FIT window");
            Object[][] cases = {
                // name, case, v7 time to 100%, source, v7 time to 95%
                {"Codex's plateau", flatStale, 122.5, "PERSONAL", 100.0},
                {"A flat fit", flat, 122.5, "PERSONAL", 100.0},
                {"A stopped decay", stale, 122.5, "PERSONAL", 100.0},
                {"A plateau, then 90 s of decay", flatThenFalling, 122.5, "PERSONAL", 100.0},
                {"A slow taper, raw tau 40", slow, 122.5, "PERSONAL", 100.0},
                {"A slow taper, raw tau 28", over, 122.5, "PERSONAL", 100.0},
                {"A step down, 2.5 minutes later", stepDown, 122.5, "PERSONAL", 100.0},
                {"A 90-s step down, 1.5 minutes later", slowerStep.c, 122.5, "PERSONAL", 100.0},
            };
            List<Object[]> all = new ArrayList<>(Arrays.asList(cases));
            for (int i = 0; i < crept.length; i++)
                all.add(new Object[]{"Creep every " + creeps[i][0] + " min, " + creeps[i][1] + " min after the stop", crept[i], 122.5, "PERSONAL", 100.0});
            cases = all.toArray(new Object[0][]);
            for (Object[] o : cases) {
                Case c = (Case) o[1];
                ChargeSessionStore.Curve curve = history(Files.createTempDirectory(tmp, "history-"), c.amps);
                for (double target : new double[]{100, 95}) {
                    String where = o[0] + " at a " + (int) target + "% limit";
                    ChargingEstimate.Result r = scheduled(75, c.amps, target, curve, c.taper, c.now);
                    double limit = target == 100 ? (Double) o[2] : (Double) o[4];
                    near((Double) o[2], r.remainingMinutesToFull, 1e-9, where + ": v7's time to 100%");
                    near(limit, r.remainingMinutesToLimit, 1e-9, where + ": v7's time to the limit");
                    near(limit, r.planningMinutesToLimit, 1e-9, where + ": v7's Charge done by minutes");
                    check(FastChargeTaperReplay.source(r).equals(o[3]) && FastChargeTaperReplay.limitSource(r).equals(o[3]) && r.learnedSessions == 2
                            && r.label.equals("From 2 past charges"), where + ": v7's source and label, " + FastChargeTaperReplay.source(r) + " " + r.label);
                    ChargeSessionStore.Estimate saved = ChargingEstimate.recordingSnapshot(r);
                    check(saved.fullSource == ChargeSessionStore.Source.PERSONAL && saved.limitSource == ChargeSessionStore.Source.PERSONAL,
                            where + ": recorded as personal evidence");
                }
            }

            // The positive case keeps v8: its own taper replaces the past charges' 122.5 minutes on
            // screen, while Charge done by keeps planning with them (planning(), review of 59ac1d9).
            ChargeSessionStore.Curve curve = history(Files.createTempDirectory(tmp, "history-"), own.amps);
            double mine = own.taper.tau() * Math.log(own.amps / FastChargeTaper.END_AMPS);
            ChargingEstimate.Result full = scheduled(75, own.amps, 100, curve, own.taper, own.now);
            near(20.631, full.remainingMinutesToFull, .001, "Its own taper places 100%");
            near(mine, full.remainingMinutesToFull, 1e-9, "from this charge's tau");
            check(full.remainingMinutesToLimit.equals(full.remainingMinutesToFull), "A 100% limit shows the time to 100%");
            near(122.5, full.planningMinutesToLimit, 1e-9, "Charge done by plans with the longer, still-matching past charges");
            near(122.5, full.planningMinutesToFull, 1e-9, "and so does the time to 100% it would plan with");
            check(FastChargeTaperReplay.source(full).equals("FAST_REFERENCE") && FastChargeTaperReplay.limitSource(full).equals("FAST_REFERENCE")
                    && full.learnedSessions == 0 && full.label.equals("High-speed reference"), "Shown as the high-speed estimate");
            ChargingEstimate.Result lower = scheduled(75, own.amps, 95, curve, own.taper, own.now);
            check(Objects.equals(lower.remainingMinutesToFull, full.remainingMinutesToFull) && Objects.equals(lower.remainingMinutesToLimit, full.remainingMinutesToFull)
                    && FastChargeTaperReplay.limitSource(lower).equals("PERSONAL") && lower.learnedSessions == 2 && lower.label.equals("From 2 past charges"),
                    "At 95%, past charges' 100 minutes are shown capped at its own time to 100%");
            near(100.0, lower.planningMinutesToLimit, 1e-9, "and planned uncapped");

            // A slow taper within SLOWER of TAU_HIGH (raw tau 24) still takes over, clamped at TAU_HIGH:
            // 43.0 minutes against a pure exponential's 51.6, inside Charge done by's buffer.
            Case under = decaying(24, 45);
            under.now += 10 * SEC;
            check(under.taper.fittedTau() > 23.5 && under.taper.fittedTau() < 24.5 && under.taper.ownTaper(54.0, under.amps, under.now),
                    "A raw tau of 24 is this charge's own taper");
            ChargingEstimate.Result slowOwn = scheduled(75, under.amps, 100, history(Files.createTempDirectory(tmp, "history-"), under.amps), under.taper, under.now);
            near(43.042, slowOwn.remainingMinutesToFull, .001, "Clamped at TAU_HIGH");
            check(FastChargeTaperReplay.source(slowOwn).equals("FAST_REFERENCE"), "and shown as the high-speed estimate");
            near(122.5, slowOwn.planningMinutesToLimit, 1e-9, "Charge done by keeps the past charges' longer time");
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(tmp)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    /**
     * Reading noise is not a charger's step down (STEP_AMPS). The noise-t13-n3 and noise-t11-n3
     * probes: after a minute at 10 A, a tau-13 or tau-11 decay from 8 A with up to 0.03 A of noise
     * on each 10-s reading, read at 0.01 A for 40 minutes. Below about 1.85 A that noise made new
     * lows fall faster than STEP_TAU by 0.04 to 0.08 A, and before STEP_AMPS (9be375d) all six
     * realizations handed back to v7 at 1.2 to 1.8 A, for 3 to 7.3 minutes at a time (without
     * STEP_AMPS, or with it at .045 or .065, the first one here hands back at 1290 s). With it,
     * these five take over once and keep their own taper until the decay reaches END_AMPS. The
     * sixth (tau 11, seed 3) still lines up a 0.08 A fall at about 1.8 A and hands back once, for
     * 3 minutes. A charger's step down still hands back: the 30-s and 90-s ones in plateau() fall
     * 0.6 and 0.2 A between readings. jitter() with StrictMath gives the probes' readings exactly.
     */
    private static void noise() {
        for (int[] ts : new int[][]{{13, 1}, {13, 2}, {13, 3}, {11, 1}, {11, 2}}) {
            Case c = new Case();
            long tookAt = 0, handedBack = 0;
            for (long dt = 10 * SEC; dt <= 40 * MIN; dt += 10 * SEC) {
                double m = dt / 60000d - 1, clean = m < 0 ? 10 : 8 * Math.exp(-m / ts[0]);
                c.at(m < 0 ? 10 : Math.round((clean + .03 * jitter(dt, ts[1])) * 100) / 100d, T0 + dt);
                if (c.taper.ownTaper(54.0, c.amps, c.now)) { if (tookAt == 0) tookAt = dt; }
                else if (tookAt > 0 && handedBack == 0 && clean > FastChargeTaper.END_AMPS) handedBack = dt;
            }
            String where = "A tau-" + ts[0] + " decay with 0.03 A of noise (seed " + ts[1] + ")";
            check(tookAt > 0 && tookAt <= 7 * MIN, where + " takes over by minute 7, " + tookAt / SEC + " s");
            check(handedBack == 0, where + " keeps its own taper to END_AMPS; handed back at " + handedBack / SEC + " s");
        }
        // The same noise on a 30-s step down from 4.32 to 2.5 A still hands back.
        Case c = new Case();
        boolean before = false, after = false;
        for (long dt = 10 * SEC; dt <= 12 * MIN; dt += 10 * SEC) {
            double m = dt / 60000d - 1;
            double clean = m < 0 ? 10 : m <= 8 ? 8 * Math.exp(-m / 13) : m <= 8.5 ? 4.32 - 1.82 * (m - 8) / .5 : 2.5 * Math.exp(-(m - 8.5) / 40);
            c.at(m < 0 ? 10 : Math.round((clean + .03 * jitter(dt, 1)) * 100) / 100d, T0 + dt);
            boolean taking = c.taper.ownTaper(54.0, c.amps, c.now);
            if (m <= 8) before = taking; else after |= taking;
        }
        check(before && !after, "A noisy 30-s step down hands back from its first reading for as long as it is in the RECENT_FIT window");
    }

    /** One reading of a timeline: its case after the reading, and whether the decay had stopped. */
    private interface Reading { void read(Case c, boolean stopped) throws Exception; }

    /**
     * Codex's synthetic timeline from the review of 59ac1d9, not a measured charge: a 10 A ceiling,
     * then decaying(11, 72), 12 minutes to 2.69 A, then a hold that falls 0.01 A every 30 s, read
     * every 10 s for 2 minutes. The own taper keeps it for 50 s after the stop.
     */
    private static void stopping(Reading each) throws Exception {
        Case c = new Case();
        for (int i = 0; i <= 84; i++) {
            c.at(i <= 72 ? Math.round(800 * Math.exp(-(i / 6d) / 11)) / 100d : Math.round((2.69 - .01 * Math.floor((i - 72) / 3d)) * 100) / 100d,
                    T0 + MIN + i * 10 * SEC);
            each.read(c, i > 72);
        }
    }

    /** Past charges' estimate for this reading alone (ChargingEstimate.withObservedCurve), as v7 matches them. */
    private static ChargingEstimate.Result past(double soc, double amps, double target, ChargeSessionStore.Curve curve, long t) {
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(54.0, t), live(-amps, t), live(soc, t), live(15.3, t), live(target, t));
        return ChargingEstimate.withObservedCurve(base, live(soc, t), curve);
    }

    /**
     * Review of 59ac1d9 (Codex): Charge done by programs its start once, from planningMinutesToLimit,
     * and the own taper needs up to RECENT_FIT minutes to see a decay stop. 59ac1d9 planned with the
     * own taper's 15.1 minutes there, where two matching past charges give about 80: a start about
     * 65 minutes late. While the own taper places the time shown, Charge done by plans with the
     * longer of it and the personal time v7 shows; the display keeps the own taper.
     */
    private static void planning() throws Exception {
        planningWindow();
        planningGuards();
        planningLowerLimits();
        planningSchedule();
    }

    private interface InTemp { void run(Path tmp) throws Exception; }

    /** Runs body with a temporary folder for synthetic past charges, deleted afterwards. */
    private static void inTemp(InTemp body) throws Exception {
        Path tmp = Files.createTempDirectory("fast-taper-planning-");
        try { body.run(tmp); } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(tmp)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    /** Two synthetic past charges at the hold's 2.69 A, 8.5 minutes per percent: about 80 minutes at 90%. */
    private static ChargeSessionStore.Curve matching(Path tmp) throws Exception { return history(Files.createTempDirectory(tmp, "history-"), 2.69, 51); }

    /** Codex's window: the time shown is the own taper's, Charge done by plans with at least the matching past charges'. */
    private static void planningWindow() throws Exception {
        inTemp(tmp -> {
            ChargeSessionStore.Curve matching = matching(tmp);
            int[] window = {0}, ownRows = {0};
            stopping((c, stopped) -> {
                if (!c.taper.ownTaper(54.0, c.amps, c.now)) return;
                ownRows[0]++;
                ChargingEstimate.Result r = scheduled(90, c.amps, 100, matching, c.taper, c.now), history = past(90, c.amps, 100, matching, c.now);
                String where = "Codex's timeline at " + (c.now - T0 - MIN) / SEC + " s, " + c.amps + " A";
                near(FastChargeTaper.minutes(54.0, c.amps, 15.3, c.taper), r.remainingMinutesToFull, 1e-12, where + ": the time shown is its own taper's");
                check(r.remainingMinutesToLimit.equals(r.remainingMinutesToFull) && FastChargeTaperReplay.source(r).equals("FAST_REFERENCE")
                        && r.label.equals("High-speed reference"), where + ": shown as the high-speed estimate");
                double plan = history.learnedToFull ? Math.max(r.remainingMinutesToFull, history.remainingMinutesToFull) : r.remainingMinutesToFull;
                near(plan, r.planningMinutesToLimit, 1e-9, where + ": Charge done by plans with the longer of it and matching past charges");
                near(plan, r.planningMinutesToFull, 1e-9, where + ": and plans the time to 100% the same way");
                if (!stopped) return;
                window[0]++;
                near(15.15, r.remainingMinutesToFull, .05, where + ": about 15 minutes shown");
                check(history.learnedToFull && history.learnedSessions == 2, where + ": two past charges still match");
                near(80.75, history.remainingMinutesToFull, .01, where + ": about 80 minutes from them");
                check(r.planningMinutesToLimit >= history.remainingMinutesToFull, where + ": Charge done by plans with at least their time");
            });
            check(window[0] == 5 && ownRows[0] > window[0], "The own taper keeps the stopped decay for 50 s: " + window[0] + " readings");
        });
    }

    /** Without matching past charges, or with matching ones that are shorter, Charge done by plans with the time shown. */
    private static void planningGuards() throws Exception {
        inTemp(tmp -> {
            ChargeSessionStore.Curve other = history(Files.createTempDirectory(tmp, "history-"), 1.0, 51),
                    shorter = history(Files.createTempDirectory(tmp, "history-"), 2.69, 6);
            int[] ownRows = {0}, guarded = {0, 0};
            stopping((c, stopped) -> {
                if (!c.taper.ownTaper(54.0, c.amps, c.now)) return;
                ownRows[0]++;
                String where = "Codex's timeline at " + (c.now - T0 - MIN) / SEC + " s";
                for (ChargeSessionStore.Curve curve : new ChargeSessionStore.Curve[]{null, other}) {
                    ChargingEstimate.Result r = scheduled(90, c.amps, 100, curve, c.taper, c.now);
                    check(curve == null || !past(90, c.amps, 100, curve, c.now).learnedToFull, where + ": a 1 A past charge never matches 2.7 A or more");
                    check(r.planningMinutesToLimit.equals(r.remainingMinutesToLimit) && r.planningMinutesToFull.equals(r.remainingMinutesToFull),
                            where + (curve == null ? ", no past charges" : ", unmatched past charges") + ": Charge done by plans with the time shown");
                    guarded[0]++;
                }
                ChargingEstimate.Result r = scheduled(90, c.amps, 100, shorter, c.taper, c.now), history = past(90, c.amps, 100, shorter, c.now);
                if (history.learnedToFull) {
                    check(history.remainingMinutesToFull < r.remainingMinutesToFull - 5, where + ": the past charges are shorter, " + history.remainingMinutesToFull);
                    check(r.planningMinutesToLimit.equals(r.remainingMinutesToLimit) && r.planningMinutesToFull.equals(r.remainingMinutesToFull),
                            where + ": a longer own taper keeps its own time for Charge done by");
                    guarded[1]++;
                }
            });
            check(ownRows[0] > 5 && guarded[0] == 2 * ownRows[0] && guarded[1] >= 5, "Guards covered " + guarded[0] + " and " + guarded[1] + " own-taper readings");
        });
    }

    /**
     * Lower limits: past charges' time from 75% to 80, 85 or 90% exceeds the own taper's time to
     * 100%. It is shown capped there (as at 59ac1d9), and planned uncapped.
     */
    private static void planningLowerLimits() throws Exception {
        inTemp(tmp -> {
            ChargeSessionStore.Curve matching = matching(tmp);
            for (double target : new double[]{80, 85, 90}) {
                int[] capped = {0};
                stopping((c, stopped) -> {
                    if (!c.taper.ownTaper(54.0, c.amps, c.now)) return;
                    ChargingEstimate.Result r = scheduled(75, c.amps, target, matching, c.taper, c.now), history = past(75, c.amps, target, matching, c.now);
                    String where = (int) target + "% limit at " + (c.now - T0 - MIN) / SEC + " s";
                    if (!history.learnedToLimit) {
                        check(r.planningMinutesToLimit.equals(r.remainingMinutesToLimit), where + ": without past charges' time, the time shown plans");
                        return;
                    }
                    check(FastChargeTaperReplay.limitSource(r).equals("PERSONAL")
                            && Math.abs(r.remainingMinutesToLimit - Math.min(history.remainingMinutesToLimit, r.remainingMinutesToFull)) <= 1e-12,
                            where + ": past charges' time is shown, capped at the own taper's time to 100%");
                    check(r.planningMinutesToLimit >= history.remainingMinutesToLimit, where + ": Charge done by plans with no less than past charges' time, "
                            + r.planningMinutesToLimit + " < " + history.remainingMinutesToLimit);
                    if (history.remainingMinutesToLimit > r.remainingMinutesToFull) capped[0]++;
                });
                check(capped[0] >= 20, (int) target + "% limit: the cap bites on " + capped[0] + " own-taper readings");
            }
        });
    }

    /**
     * Through the controller: arming Charge done by in the window, at a 100% and a 90% limit,
     * programs a start no later than the matching past charges' start, with the 20-minute buffer added once.
     */
    private static void planningSchedule() throws Exception {
        inTemp(tmp -> {
            ChargeSessionStore.Curve matching = matching(tmp);
            for (double[] at : new double[][]{{90, 100}, {75, 90}}) {
                double soc = at[0], target = at[1];
                ChargingEstimate.Result[] seen = new ChargingEstimate.Result[2];
                stopping((c, stopped) -> {
                    if (stopped && seen[0] == null && c.taper.ownTaper(54.0, c.amps, c.now)) {
                        seen[0] = scheduled(soc, c.amps, target, matching, c.taper, c.now); seen[1] = past(soc, c.amps, target, matching, c.now);
                    }
                });
                String where = "Charge done by at " + (int) soc + "%, " + (int) target + "% limit";
                check(seen[0] != null && seen[1].learnedToLimit, where + ": armed in the window, with past charges' time to the limit");
                ChargingEstimate.Result r = seen[0];
                double history = seen[1].remainingMinutesToLimit;
                Scooter s = new Scooter(soc, target, r.planningMinutesToLimit);
                s.arm();
                long deadline = ChargeScheduleController.nextOccurrence(s.c.now, 8 * 60, s.c.zone);
                long pastStart = ChargeScheduleController.plan(s.c.now, 8 * 60, history, s.c.zone, false).startWallMs;
                long shownStart = ChargeScheduleController.plan(s.c.now, 8 * 60, r.remainingMinutesToLimit, s.c.zone, false).startWallMs;
                check(s.controller.view().doneByEnabled && s.controller.view().startWallMs == s.sentTime, where + ": armed");
                check(s.sentTime <= pastStart, where + ": the start is no later than past charges' " + pastStart + ", " + s.sentTime);
                double lead = (deadline - s.sentTime) / 60_000d, buffer = ChargeScheduleController.BUFFER_MS / 60_000d;
                check(lead >= history + buffer && lead < history + buffer + 1, where + ": " + lead + " minutes ahead, past charges' " + history + " plus one buffer");
                check(shownStart - s.sentTime >= 60 * MIN, where + ": 59ac1d9 would have started " + (shownStart - s.sentTime) / MIN + " minutes later");
            }
        });
    }

    /** ChargeScheduleControllerTest's rig, reduced to arming Charge done by at 11 PM for 8 AM in Boise. */
    private static final class Scooter implements ChargeScheduleController.Store, ChargeScheduleController.Driver {
        final Map<String, String> saved = new HashMap<>();
        final ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        final List<String> writes = new ArrayList<>(), results = new ArrayList<>();
        final ChargeScheduleController controller = new ChargeScheduleController(this, this);
        java.util.function.Consumer<Boolean> callback;
        long sentTime;
        int sentOffset;
        Scooter(double soc, double target, Double estimateMinutes) {
            c.scope = "owner-A"; c.ready = true; c.generation = 1; c.now = java.time.Instant.parse("2026-10-20T05:00:00Z").toEpochMilli();
            c.zone = TimeZone.getTimeZone("America/Boise"); c.charging = 1; c.enabled = 0; c.soc = soc; c.target = target; c.estimateMinutes = estimateMinutes; c.sampleMs = 100_000;
        }
        public String read(String key) { return saved.get(key); }
        public void write(String key, String value) { saved.put(key, value); }
        public ChargeScheduleController.Context context() { return c; }
        public void clock(java.util.function.Consumer<Boolean> result) { writes.add("clock"); callback = result; }
        public void time(long wall, int offset, java.util.function.Consumer<Boolean> result) { writes.add("time"); sentTime = wall; sentOffset = offset; callback = result; }
        public void enabled(boolean enabled, java.util.function.Consumer<Boolean> result) { writes.add(enabled ? "on" : "off"); callback = result; }
        void reply() { java.util.function.Consumer<Boolean> done = callback; callback = null; done.accept(true); }
        /** Turns Charge done by on and confirms the clock, the start and ON. */
        void arm() {
            controller.setDoneTime(8, 0, results::add);
            controller.setDoneEnabled(true, results::add);
            reply(); reply(); c.wireStart = sentTime / 1000 + sentOffset; reply(); c.enabled = 1;
            check(writes.equals(Arrays.asList("clock", "time", "on")) && results.equals(Arrays.asList(null, null)), "Charge done by armed: " + writes + " " + results);
        }
    }

    /** The probes' noise, -1 to 1, for a reading dt after the start and a seed. */
    private static double jitter(long dt, int seed) {
        double x = StrictMath.sin((dt / 1000d + seed * 1013.37) * 12.9898) * 43758.5453;
        return 2 * (x - Math.floor(x)) - 1;
    }

    /** As ScooterService schedules it: high-speed charger, product 2538, the given past charges and taper state. */
    private static ChargingEstimate.Result scheduled(double soc, double amps, double target, ChargeSessionStore.Curve curve, FastChargeTaper taper, long t) {
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1, t), live(54.0, t), live(-amps, t), live(soc, t), live(15.3, t), live(target, t));
        return ChargingEstimate.forScooter(base, live(soc, t), live(15.3, t), 2538, FAST, curve, null, null, t, t, null, taper);
    }

    /** Owner charges: high-speed fixes and parity for everything each step must not touch. */
    private static void replay(Path data) throws Exception {
        List<FastChargeTaperReplay.Row> rows = FastChargeTaperReplay.replay(data.resolve("sessions.csv"));
        List<String[]> v6 = baseline(data.resolve("expected-v6.csv")), v7 = baseline(data.resolve("expected-v7.csv"));
        check(rows.size() == 7813 && v7.size() == rows.size() && v6.size() == 6646, "Fixture and baselines cover every sample");
        int stock = 0, reference = 0, recorded = 0, recordedV7 = 0, liveV7 = 0, liveSource = 0, own = 0, ownFast = 0, changed = 0, replaced = 0, planned = 0;
        double earlier = 0;
        for (int i = 0; i < rows.size(); i++) {
            FastChargeTaperReplay.Row row = rows.get(i);
            String[] base = v7.get(i);
            Double before = base[2].isEmpty() ? null : Double.valueOf(base[2]);
            boolean same = before == null ? row.full == null : row.full != null && Math.abs(before - row.full) <= 1e-6;
            same = same && base[3].equals(row.source) && Integer.parseInt(base[4]) == row.learned;
            // Charge done by plans with the time shown, except where this charge's own taper replaced
            // past charges that v7 showed: there it keeps the longer of the two (review of 59ac1d9).
            boolean personal = row.ownTaper && row.sample.charger.equals("HIGH_SPEED") && row.source.equals("FAST_REFERENCE") && base[3].equals("PERSONAL");
            Double plan = personal ? Double.valueOf(Math.max(row.full, before)) : row.full;
            check(plan == null ? row.planningLimit == null : row.planningLimit != null && Math.abs(row.planningLimit - plan) <= 1e-6,
                    "Session " + row.sample.session + " at " + row.minutes + " min: Charge done by plans with " + plan + ", not " + row.planningLimit);
            if (personal) replaced++;
            if (personal && before > row.full + 1e-6) { planned++; earlier = Math.max(earlier, before - row.full); }
            // v8 changes only samples where this charge's own taper is established.
            if (row.ownTaper) own++;
            if (row.ownTaper && row.sample.charger.equals("HIGH_SPEED")) ownFast++;
            if (!same) {
                changed++;
                check(row.ownTaper && row.sample.charger.equals("HIGH_SPEED") && row.source.equals("FAST_REFERENCE") && row.learned == 0,
                        "Outside its own taper, session " + row.sample.session + " at " + row.minutes + " min keeps the v7 estimate");
            }
            if (row.sample.recordedVersion.equals("ut5-fast-taper-v7") && row.sample.recordedFull != null) {
                recordedV7++;
                if (before != null && Math.abs(before - row.sample.recordedFull) <= .1) liveV7++;
                if (base[3].equals(row.sample.recordedSource)) liveSource++;
            }
            if (i >= v6.size()) continue;
            String[] old = v6.get(i);
            Double older = old[2].isEmpty() ? null : Double.valueOf(old[2]);
            if (row.sample.recordedVersion.equals("ut5-stock-pack-v6") && row.sample.recordedFull != null) {
                recorded++;
                check(older != null && Math.abs(older - row.sample.recordedFull) <= .05 && old[3].equals(row.sample.recordedSource),
                        "The v6 baseline replay reproduces the app's recorded prediction");
            }
            if (row.sample.charger.equals("STOCK") || row.sample.session == 4 && !row.ownTaper) {
                if (row.sample.charger.equals("STOCK")) stock++; else reference++;
                check(older == null ? row.full == null : row.full != null && Math.abs(older - row.full) <= 1e-6, "Unchanged estimate, session " + row.sample.session);
                check(old[3].equals(row.source) && Integer.parseInt(old[4]) == row.learned, "Unchanged source, session " + row.sample.session);
            }
        }
        check(stock == 5457 && reference == 343 && recorded == 1028, "Parity covers all standard-charger samples and the reference charge before its own taper");
        // Every high-speed sample that reaches its own taper keeps it (1,197). On standard-charger
        // samples, where the branch never applies, the recency check (RECENT_MS) clears the flag on
        // 59 samples of session 2 and the recent-pace check (SLOWER) on 145 of sessions 3 and 8-10.
        check(ownFast == 1197 && own == 1532 && changed == 1110, "Own-taper samples " + ownFast + " high-speed, " + own + " in all, changed from v7 " + changed);
        // Of the 921 own-taper samples that replaced past charges v7 showed, 459 plan with those longer
        // past charges, at most 4.58 minutes longer than the time shown.
        check(replaced == 921 && planned == 459 && Math.abs(earlier - 4.577) < .001,
                "Own taper over past charges: " + replaced + " samples, " + planned + " planned with them, up to " + earlier + " min longer");
        // expected-v7.csv replays what the app showed live (journal samples are 10 s apart, the app
        // re-evaluates every frame, so held countdowns and taper fits can start a sample apart).
        check(recordedV7 == 1167 && liveV7 >= 1100 && liveSource >= 1166, "The v7 baseline replay reproduces the app's recorded predictions");
        double[][] bounds = {{12, 0, 5, 12}, {12, 5, 0, 5}, {12, 10, -1.5, 1.5}, {11, 0, -3, 3}, {11, 5, -3, 3}, {11, 10, -3, 3}, {6, 5, -1, 3},
            {13, 40, -1, 1}, {13, 50, -1, 1}, {13, 60, -1.5, 0}};
        for (double[] b : bounds) {
            FastChargeTaperReplay.Row row = FastChargeTaperReplay.at(rows, (int) b[0], b[1]);
            double error = row.full - row.actual;
            check(error >= b[2] && error <= b[3], "Session " + (int) b[0] + " at " + b[1] + " min: " + error);
        }
        for (int session : new int[]{4, 6, 11, 12, 13, 14, 15}) {
            double sum = 0; int n = 0; boolean personal = false, ownTaper = false;
            for (FastChargeTaperReplay.Row row : rows) if (row.sample.session == session && row.actual != null && row.actual > 0) {
                double error = row.full - row.actual;
                sum += Math.abs(error); n++;
                if (row.source.equals("PERSONAL")) personal = true;
                else if (session == 12) check(!personal || row.ownTaper, "Once personal, session 12 leaves it only for its own taper");
                if (session == 12) check(!row.source.equals("CURRENT_RATE"), "Session 12 never shows the current rate");
                if (row.ownTaper) ownTaper = true;
                else check(!ownTaper, "Session " + session + " keeps its own taper to the end");
                if (row.ownTaper) check(row.source.equals("FAST_REFERENCE"), "Its own taper is recorded as the high-speed reference");
                // The cost: the last 15 minutes read slightly short, never by more than about a minute.
                if (row.actual <= 15) check(error >= -1.1 && error <= 1, "Session " + session + " with " + row.actual + " min left: " + error);
            }
            check(ownTaper, "Session " + session + " reaches its own taper");
            check(sum / n <= (session == 11 || session == 4 ? 1.0 : session == 13 ? 4.5 : 2.5), "Session " + session + " mean absolute error " + sum / n);
        }
        lowerLimit(data, rows);
    }

    /**
     * 95% and 80% charge limits: every sample's time to the limit, its source and Charge done by's
     * minutes match v7 (expected-v7-limits.csv). The charge's own taper changes only the time to 100%;
     * the one documented change is the label, which says High-speed reference instead of From N past
     * charges where past charges placed v7's time to 100% but not its time to the limit.
     */
    private static void lowerLimit(Path data, List<FastChargeTaperReplay.Row> full) throws Exception {
        List<String[]> v7 = baseline(data.resolve("expected-v7-limits.csv"));
        check(v7.size() == full.size() && Arrays.equals(FastChargeTaperReplay.LIMITS, new double[]{95, 80}), "The lower-limit baseline covers every sample");
        // Per limit: own-taper samples with a personal time to the limit, without one, and relabelled.
        int[][] want = {{453, 737, 494}, {494, 696, 453}};
        FastChargeTaperReplay.Row s15 = null;
        for (int k = 0; k < FastChargeTaperReplay.LIMITS.length; k++) {
            double target = FastChargeTaperReplay.LIMITS[k];
            List<FastChargeTaperReplay.Row> rows = FastChargeTaperReplay.replay(data.resolve("sessions.csv"), target);
            check(rows.size() == full.size(), "The " + target + "% replay covers every sample");
            int personal = 0, other = 0, relabelled = 0, column = 2 + 4 * k;
            for (int i = 0; i < rows.size(); i++) {
                FastChargeTaperReplay.Row row = rows.get(i), hundred = full.get(i);
                String[] base = v7.get(i);
                String where = "Session " + row.sample.session + " at " + row.minutes + " min, " + target + "% limit";
                check(base[0].equals(String.valueOf(row.sample.session)) && base[1].equals(String.format(Locale.ROOT, "%.4f", row.minutes)),
                        where + ": aligned with the v7 baseline");
                check(close(base[column], row.limit) && base[column + 1].equals(row.limitSource) && close(base[column + 2], row.planningLimit),
                        where + ": v7's time to the limit, its source and Charge done by minutes");
                int learnedV7 = Integer.parseInt(base[column + 3]);
                if (row.learned != learnedV7) {
                    relabelled++;
                    check(learnedV7 > 0 && row.learned == 0 && row.ownTaper && row.sample.charger.equals("HIGH_SPEED")
                            && hundred.source.equals("FAST_REFERENCE") && !row.limitSource.equals("PERSONAL"),
                            where + ": only the documented label change, past charges no longer counted once the own taper places 100%");
                }
                check(row.label.startsWith("From ") == row.learned > 0, where + ": the label names past charges exactly when they are counted");
                check(Objects.equals(row.full, hundred.full) && row.source.equals(hundred.source) && row.ownTaper == hundred.ownTaper,
                        where + ": a lower limit leaves the time to 100% alone");
                check(Objects.equals(row.planningLimit, row.limit), where + ": Charge done by plans with the displayed cutoff time");
                // Where the charge's own taper placed the time to 100% (elsewhere it is v7's path, untouched).
                if (!row.ownTaper || !row.sample.charger.equals("HIGH_SPEED") || !hundred.source.equals("FAST_REFERENCE") || row.limit == null) continue;
                check(row.limit <= row.full + 1e-9, where + ": the cutoff time never exceeds the time to 100%");
                if (row.personalLimit != null) {
                    personal++;
                    check(row.limitSource.equals("PERSONAL") && Math.abs(row.limit - Math.min(row.personalLimit, row.full)) <= 1e-9
                            && row.label.startsWith("From ") && row.learned > 0, where + ": its own taper keeps past charges' time to the cutoff");
                } else {
                    other++;
                    check(!row.limitSource.equals("PERSONAL") && !row.label.startsWith("From ") && row.learned == 0,
                            where + ": without one, the cutoff keeps its reference or current rate");
                }
                if (target == 95 && s15 == null && row.sample.session == 15 && row.sample.soc == 82) s15 = row;
            }
            // Without withPersonalLimit, the personal cutoff times moved to the reference or current rate.
            check(personal == want[k][0] && other == want[k][1] && relabelled == want[k][2],
                    target + "% limit: own-taper samples with a personal time " + personal + ", without " + other + ", relabelled " + relabelled);
        }
        check(s15 != null && s15.limitSource.equals("PERSONAL") && Math.abs(s15.limit - 19.39) < .01 && s15.planningLimit.equals(s15.limit),
                "Session 15 at 82% keeps v7's personal 19.4 minutes to 95%");

        // Very close to 100%, past charges' time to the limit can exceed this charge's time to 100%
        // (v7 showed it, at 99% on session 15); the time shown to the limit is capped there instead,
        // while Charge done by keeps planning with past charges' uncapped time (review of 59ac1d9).
        List<FastChargeTaperReplay.Row> near = FastChargeTaperReplay.replay(data.resolve("sessions.csv"), 99);
        int capped = 0; double most = 0;
        for (int i = 0; i < near.size(); i++) {
            FastChargeTaperReplay.Row row = near.get(i), hundred = full.get(i);
            String where = "Session " + row.sample.session + " at " + row.minutes + " min, 99% limit";
            boolean own = row.ownTaper && row.sample.charger.equals("HIGH_SPEED") && hundred.source.equals("FAST_REFERENCE") && row.limit != null;
            check(Objects.equals(row.full, hundred.full) && (own && row.personalLimit != null
                    ? Math.abs(row.planningLimit - Math.max(row.limit, row.personalLimit)) <= 1e-9 : Objects.equals(row.planningLimit, row.limit)),
                    where + ": time to 100% unchanged; Charge done by plans with the time shown, or under its own taper past charges' longer one");
            if (!own) continue;
            check(row.limit <= row.full + 1e-9, where + ": the cutoff time never exceeds the time to 100%");
            if (row.personalLimit == null) continue;
            check(row.limitSource.equals("PERSONAL") && Math.abs(row.limit - Math.min(row.personalLimit, row.full)) <= 1e-9, where + ": past charges' time, capped");
            if (row.personalLimit > row.full + 1e-9) { capped++; most = Math.max(most, row.personalLimit - row.full); check(row.sample.session == 15, where); }
        }
        check(capped == 18 && Math.abs(most - 2.045) < .001, "99% limit: " + capped + " capped samples, up to " + most + " min shorter than past charges' time");
    }

    /** A baseline value written with six decimals (empty for none) against the replayed one. */
    private static boolean close(String want, Double actual) {
        return want.isEmpty() ? actual == null : actual != null && Math.abs(Double.parseDouble(want) - actual) <= 1e-6;
    }

    private static List<String[]> baseline(Path csv) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) if (!line.startsWith("session")) rows.add(line.split(",", -1));
        return rows;
    }
}
