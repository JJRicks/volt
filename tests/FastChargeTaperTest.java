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
    private static ChargeSessionStore.Curve history(Path dir, double amps) throws Exception {
        for (int h = 0; h < 2; h++) {
            List<FastChargeTaperReplay.Sample> samples = new ArrayList<>();
            for (int i = 0; i <= 250 * 6; i++) {
                FastChargeTaperReplay.Sample s = new FastChargeTaperReplay.Sample();
                s.session = h; s.charger = "HIGH_SPEED"; s.stop = "COMPLETE"; s.elapsed = i * 10 * SEC;
                s.soc = Math.min(100, 50 + Math.floor(i / 30d)); s.volts = 54.0; s.amps = -amps;
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
    private static Case decaying() {
        Case c = new Case();
        for (int i = 0; i <= 39; i++) c.at(Math.round(800 * Math.exp(-(i / 6d) / 11)) / 100d, T0 + MIN + i * 10 * SEC);
        return c;
    }

    /**
     * Review of 4da6840 (Codex): a flat or stale decay must not take over as this charge's own
     * taper. Each case is pinned at 75% with two matching synthetic past charges, at a 100% and a
     * 95% limit, to the time, sources, label and Charge done by minutes that v7 (f529f43) gives for
     * the same readings; 4da6840 showed 34.7, 34.6 and 20.6 minutes from FAST_REFERENCE instead of
     * 122.5 from past charges in the first three.
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

            check(Math.abs(flatStale.taper.tau() - FastChargeTaper.TAU_MINUTES) < 1e-12 && Math.abs(flat.taper.tau() - FastChargeTaper.TAU_MINUTES) < 1e-12,
                    "A flat fit falls back to the reference tau");
            check(Math.abs(stale.taper.tau() - 11) < .05 && Math.abs(own.taper.tau() - 11) < .05, "A stopped decay keeps its old fit");
            check(flatStale.taper.tapering(54.0, 7.97) && flat.taper.tapering(54.0, 7.93) && stale.taper.tapering(54.0, stale.amps),
                    "All three are still tapering by the old rule");
            check(!flatStale.taper.ownTaper(54.0, 7.97, flatStale.now), "Codex's plateau is not this charge's own taper");
            check(!flat.taper.ownTaper(54.0, 7.93, flat.now), "A flat fit is not, however recent");
            check(!stale.taper.ownTaper(54.0, stale.amps, stale.now), "A stopped decay is not, however good its fit");
            check(own.taper.ownTaper(54.0, own.amps, own.now), "A recent, usable decay is");

            Object[][] cases = {
                // name, case, v7 time to 100%, source, v7 time to 95%
                {"Codex's plateau", flatStale, 122.5, "PERSONAL", 100.0},
                {"A flat fit", flat, 122.5, "PERSONAL", 100.0},
                {"A stopped decay", stale, 122.5, "PERSONAL", 100.0},
            };
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

            // The positive case keeps v8: its own taper replaces the past charges' 122.5 minutes.
            ChargeSessionStore.Curve curve = history(Files.createTempDirectory(tmp, "history-"), own.amps);
            double mine = own.taper.tau() * Math.log(own.amps / FastChargeTaper.END_AMPS);
            ChargingEstimate.Result full = scheduled(75, own.amps, 100, curve, own.taper, own.now);
            near(20.631, full.remainingMinutesToFull, .001, "Its own taper places 100%");
            near(mine, full.remainingMinutesToFull, 1e-9, "from this charge's tau");
            check(full.remainingMinutesToLimit.equals(full.remainingMinutesToFull) && full.planningMinutesToLimit.equals(full.remainingMinutesToFull),
                    "Charge done by plans with the displayed time");
            check(FastChargeTaperReplay.source(full).equals("FAST_REFERENCE") && FastChargeTaperReplay.limitSource(full).equals("FAST_REFERENCE")
                    && full.learnedSessions == 0 && full.label.equals("High-speed reference"), "Shown as the high-speed estimate");
            ChargingEstimate.Result lower = scheduled(75, own.amps, 95, curve, own.taper, own.now);
            check(Objects.equals(lower.remainingMinutesToFull, full.remainingMinutesToFull) && Objects.equals(lower.remainingMinutesToLimit, full.remainingMinutesToFull)
                    && Objects.equals(lower.planningMinutesToLimit, full.remainingMinutesToFull) && FastChargeTaperReplay.limitSource(lower).equals("PERSONAL")
                    && lower.learnedSessions == 2 && lower.label.equals("From 2 past charges"),
                    "At 95%, past charges' 100 minutes are capped at its own time to 100%");
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(tmp)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
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
        int stock = 0, reference = 0, recorded = 0, recordedV7 = 0, liveV7 = 0, liveSource = 0, own = 0, changed = 0;
        for (int i = 0; i < rows.size(); i++) {
            FastChargeTaperReplay.Row row = rows.get(i);
            String[] base = v7.get(i);
            Double before = base[2].isEmpty() ? null : Double.valueOf(base[2]);
            boolean same = before == null ? row.full == null : row.full != null && Math.abs(before - row.full) <= 1e-6;
            same = same && base[3].equals(row.source) && Integer.parseInt(base[4]) == row.learned;
            // v8 changes only samples where this charge's own taper is established.
            if (row.ownTaper) own++;
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
        // The recency check (RECENT_MS) clears the flag on 59 samples of standard-charger session 2,
        // where the branch never applies; every high-speed own-taper sample keeps it.
        check(own == 1677 && changed == 1110, "Own-taper samples " + own + ", changed from v7 " + changed);
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
        // (v7 showed it, at 99% on session 15); the time to the limit is capped there instead.
        List<FastChargeTaperReplay.Row> near = FastChargeTaperReplay.replay(data.resolve("sessions.csv"), 99);
        int capped = 0; double most = 0;
        for (int i = 0; i < near.size(); i++) {
            FastChargeTaperReplay.Row row = near.get(i), hundred = full.get(i);
            String where = "Session " + row.sample.session + " at " + row.minutes + " min, 99% limit";
            check(Objects.equals(row.full, hundred.full) && Objects.equals(row.planningLimit, row.limit), where + ": time to 100% and Charge done by unchanged");
            if (!row.ownTaper || !row.sample.charger.equals("HIGH_SPEED") || !hundred.source.equals("FAST_REFERENCE") || row.limit == null) continue;
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
