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
        check(saved.version.equals("ut5-fast-taper-v7"), "Algorithm changes carry a new capture version");
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
    }

    /** Owner charges: high-speed fixes and parity for everything the new step must not touch. */
    private static void replay(Path data) throws Exception {
        List<FastChargeTaperReplay.Row> rows = FastChargeTaperReplay.replay(data.resolve("sessions.csv"));
        List<String> expected = new ArrayList<>();
        for (String line : Files.readAllLines(data.resolve("expected-v6.csv"), StandardCharsets.UTF_8)) if (!line.startsWith("session")) expected.add(line);
        check(rows.size() == expected.size() && rows.size() == 6646, "Fixture and baseline cover every sample");
        int stock = 0, recorded = 0;
        for (int i = 0; i < rows.size(); i++) {
            FastChargeTaperReplay.Row row = rows.get(i);
            String[] base = expected.get(i).split(",", -1);
            Double before = base[2].isEmpty() ? null : Double.valueOf(base[2]);
            if (row.sample.recordedVersion.equals("ut5-stock-pack-v6") && row.sample.recordedFull != null) {
                recorded++;
                check(before != null && Math.abs(before - row.sample.recordedFull) <= .05 && base[3].equals(row.sample.recordedSource),
                        "The v6 baseline replay reproduces the app's recorded prediction");
            }
            if (row.sample.charger.equals("STOCK") || row.sample.session == 4) {
                stock++;
                check(before == null ? row.full == null : row.full != null && Math.abs(before - row.full) <= 1e-6, "Unchanged estimate, session " + row.sample.session);
                check(base[3].equals(row.source) && Integer.parseInt(base[4]) == row.learned, "Unchanged source, session " + row.sample.session);
            }
        }
        check(stock == 5990 && recorded == 1028, "Parity covers all standard-charger and calibration samples");
        double[][] bounds = {{12, 0, 5, 12}, {12, 5, 0, 5}, {12, 10, -1.5, 1.5}, {11, 5, -3, 3}, {11, 10, -3, 3}, {6, 5, -1, 3}};
        for (double[] b : bounds) {
            FastChargeTaperReplay.Row row = FastChargeTaperReplay.at(rows, (int) b[0], b[1]);
            double error = row.full - row.actual;
            check(error >= b[2] && error <= b[3], "Session " + (int) b[0] + " at " + b[1] + " min: " + error);
        }
        for (int session : new int[]{6, 11, 12}) {
            double sum = 0; int n = 0; boolean personal = false;
            for (FastChargeTaperReplay.Row row : rows) if (row.sample.session == session && row.actual != null && row.actual > 0) {
                sum += Math.abs(row.full - row.actual); n++;
                if (session == 12 && row.source.equals("PERSONAL")) personal = true;
                else if (session == 12) check(!personal, "Once personal, session 12 never drops back to a fallback");
                if (session == 12) check(!row.source.equals("CURRENT_RATE"), "Session 12 never shows the current rate");
            }
            check(sum / n <= 2.5, "Session " + session + " mean absolute error " + sum / n);
        }
    }
}
