package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;

/**
 * The averaged charging current (ChargeCurrentAverage) and the time to full it gives, through the same chain the
 * service runs (ChargeProgressTracker, calculate, forScooter with the reference gate). The readings are synthesized.
 */
public final class ChargeCurrentAverageTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long T0 = 80_000_000L, WALL0 = 1_795_000_000_000L, READ_MS = 2_000L;
    private static final String SCOOTER = "ut5";

    /** What the charging view shows, in whole minutes, for each battery read: the service's estimate chain. */
    private static List<Long> shownMinutes(boolean averaged, int reads, double[] amps, double startVolts) {
        ChargeProgressTracker progress = new ChargeProgressTracker();
        ChargeReferenceGate gate = new ChargeReferenceGate();
        ChargeCurrentAverage current = new ChargeCurrentAverage();
        List<Long> shown = new ArrayList<>();
        for (int i = 0; i < reads; i++) {
            long mono = T0 + i * READ_MS, wall = WALL0 + i * READ_MS;
            double reading = -amps[i % amps.length], volts = startVolts + i * .0005;
            progress.observe(SCOOTER, true, 44d, reading, mono);
            current.observe(SCOOTER, true, reading, mono);
            ChargingEstimate.Value soc = new ChargingEstimate.Value(44d, true, wall), rated = new ChargingEstimate.Value(15.3, true, wall);
            ChargingEstimate.Value amp = new ChargingEstimate.Value(reading, true, wall);
            if (averaged) amp = current.steady(SCOOTER, amp, mono);
            ChargingEstimate.Result baseline = ChargingEstimate.calculate(new ChargingEstimate.Value(1d, true, wall),
                    new ChargingEstimate.Value(volts, true, wall), amp, soc, rated, new ChargingEstimate.Value(100d, true, wall));
            ChargingEstimate.Result result = ChargingEstimate.forScooter(baseline, soc, rated, Ut5MaxStockChargeReference.PRODUCT_ID,
                    ChargeSessionStore.Charger.STOCK, null, gate, SCOOTER + ":serial", wall, mono, progress.progress(SCOOTER, mono), null, null);
            check(result.stockPackModel, "The standard charger's bulk stage is the pack model's at read " + i);
            shown.add(Math.round(result.remainingMinutesToFull));
        }
        return shown;
    }

    private static int flips(List<Long> shown) {
        int flips = 0;
        for (int i = 2; i < shown.size(); i++)
            if (shown.get(i).equals(shown.get(i - 2)) && !shown.get(i).equals(shown.get(i - 1))) flips++;
        return flips;
    }

    private static long largestRise(List<Long> shown) {
        long rise = 0;
        for (int i = 1; i < shown.size(); i++) rise = Math.max(rise, shown.get(i) - shown.get(i - 1));
        return rise;
    }

    public static void main(String[] args) {
        // The owner's report (2.0.1): a UT5 MAX on the standard charger, the first recording after clearing them all (no
        // learned curve, so personal is null), its start unseen at 44%. Each read (every two seconds with Volt open), the
        // battery's current moves between two of its steps, 2.06 and 2.08 A. Ten minutes of reads.
        int reads = 300;
        double[] edge = {2.06, 2.08};
        List<Long> raw = shownMinutes(false, reads, edge, 47.60);
        check(flips(raw) > reads / 2 && largestRise(raw) >= 2,
                "The reading as it comes flips the time to full by two minutes or more at almost every read (the bug), flips "
                        + flips(raw) + ", largest rise " + largestRise(raw));
        List<Long> steady = shownMinutes(true, reads, edge, 47.60);
        check(flips(steady) == 0, "Averaged, the time to full never flips between two values: " + flips(steady) + " flips");
        check(largestRise(steady) == 0, "Averaged, the time to full never goes up while the charge is steady: rise " + largestRise(steady));
        long fell = steady.get(0) - steady.get(steady.size() - 1);
        check(fell >= 8 && fell <= 12, "Averaged, it still counts down about a minute a minute: " + fell + " minutes over ten");
        double rawMean = 0;
        for (long minutes : raw) rawMean += minutes;
        rawMean /= raw.size();
        double steadyMean = 0;
        for (long minutes : steady) steadyMean += minutes;
        steadyMean /= steady.size();
        check(Math.abs(rawMean - steadyMean) < 1, "Averaging centres the time between the two flipping values, never further off: "
                + rawMean + " against " + steadyMean);
        // Readings that sit on one step for a while, then the other: still no flipping, and still counting down.
        List<Long> runs = shownMinutes(true, reads, new double[]{2.06, 2.06, 2.06, 2.08, 2.08, 2.06, 2.08, 2.08, 2.08, 2.08}, 47.60);
        check(flips(runs) == 0 && largestRise(runs) <= 1, "Uneven runs on either step neither flip nor jump: flips " + flips(runs)
                + ", rise " + largestRise(runs));

        ChargeCurrentAverage average = new ChargeCurrentAverage();
        ChargingEstimate.Value none = average.steady(SCOOTER, new ChargingEstimate.Value(-2.07, true, WALL0), T0);
        check(none.number == -2.07, "With nothing held, a reading is used as it is");
        for (int i = 0; i < 30; i++) average.observe(SCOOTER, true, -(i % 2 == 0 ? 2.06 : 2.08), T0 + i * READ_MS);
        long last = T0 + 29 * READ_MS;
        ChargingEstimate.Value mean = average.steady(SCOOTER, new ChargingEstimate.Value(-2.08, true, WALL0 + 7), last);
        check(Math.abs(mean.number + 2.07) < 1e-9, "The newest reading stands for the minute's mean, with its sign: " + mean.number);
        check(mean.fresh && mean.observedMs == WALL0 + 7, "The mean keeps the reading's frame time, so its voltage stays paired");
        check(average.steady(SCOOTER, new ChargingEstimate.Value(-2.06, true, WALL0), last).number == -2.06,
                "A reading the mean doesn't end with (an older frame) is used as it is");
        check(average.steady("other", new ChargingEstimate.Value(-2.08, true, WALL0), last).number == -2.08,
                "Another scooter's reading is never averaged with this one's");
        check(!average.steady(SCOOTER, new ChargingEstimate.Value(-2.08, false, WALL0), last).fresh,
                "A stale reading passes through, still stale");
        check(average.steady(SCOOTER, new ChargingEstimate.Value(null, true, WALL0), last).number == null, "A missing reading stays missing");
        check(average.steady(SCOOTER, new ChargingEstimate.Value(-2.08, true, WALL0), last + ChargeCurrentAverage.WINDOW_MS + 1).number == -2.08,
                "A mean older than its window is never used");
        average.observe(SCOOTER, true, -2.08, last);
        check(average.held() == 30, "A frame already held counts once");

        // A real change shows at once: the charger stepping down, or a different charger.
        average.observe(SCOOTER, true, -1.50, last + READ_MS);
        check(average.steady(SCOOTER, new ChargingEstimate.Value(-1.50, true, WALL0), last + READ_MS).number == -1.50,
                "A step of more than " + ChargeCurrentAverage.STEP_A + " A and " + ChargeCurrentAverage.STEP_FRACTION
                        + " of the mean starts the mean again from it");
        average.observe(SCOOTER, true, -5.92, last + 2 * READ_MS);
        check(average.steady(SCOOTER, new ChargingEstimate.Value(-5.92, true, WALL0), last + 2 * READ_MS).number == -5.92,
                "A charger swapped for the high-speed one shows at once");
        // The high-speed charger's own steps (about 0.5% at 6 A) average out like the standard charger's.
        average.observe(SCOOTER, true, -5.89, last + 3 * READ_MS);
        check(Math.abs(average.steady(SCOOTER, new ChargingEstimate.Value(-5.89, true, WALL0), last + 3 * READ_MS).number + 5.905) < 1e-9,
                "Small steps on the high-speed charger average too");
        // A gradual change still shows, with about half the window's delay.
        ChargeCurrentAverage decay = new ChargeCurrentAverage();
        double amps = 0;
        for (int i = 0; i <= 900; i++) {
            amps = 2.3 * Math.exp(-i * READ_MS / (25 * 60_000d));
            decay.observe(SCOOTER, true, amps, T0 + i * READ_MS);
        }
        double followed = decay.steady(SCOOTER, new ChargingEstimate.Value(amps, true, WALL0), T0 + 900 * READ_MS).number;
        double halfWindowAgo = 2.3 * Math.exp(-(900 * READ_MS - ChargeCurrentAverage.WINDOW_MS / 2) / (25 * 60_000d));
        check(followed > amps && Math.abs(followed - halfWindowAgo) < .002, "A decaying current is followed half a window behind: "
                + followed + " for " + amps);

        // Starting afresh.
        ChargeCurrentAverage fresh = new ChargeCurrentAverage();
        for (int i = 0; i < 10; i++) fresh.observe(SCOOTER, true, 2.0, T0 + i * READ_MS);
        fresh.observe(SCOOTER, null, 2.5, T0 + 10 * READ_MS);
        check(fresh.held() == 10, "An unknown charging state changes nothing");
        fresh.observe(SCOOTER, true, null, T0 + 10 * READ_MS);
        check(fresh.held() == 10, "An unpaired reading changes nothing");
        fresh.observe(SCOOTER, false, 0d, T0 + 11 * READ_MS);
        check(fresh.held() == 0, "Charging off starts afresh");
        for (int i = 0; i < 10; i++) fresh.observe(SCOOTER, true, 2.0, T0 + i * READ_MS);
        fresh.observe(SCOOTER, true, 2.02, T0 + 9 * READ_MS + ChargeCurrentAverage.WINDOW_MS + 1);
        check(fresh.held() == 1, "A gap longer than the window starts afresh");
        fresh.observe(SCOOTER, true, 2.0, T0);
        check(fresh.held() == 1, "A clock running back starts afresh");
        fresh.observe("other", true, 2.0, T0 + READ_MS);
        check(fresh.held() == 1 && fresh.steady(SCOOTER, new ChargingEstimate.Value(2.0, true, WALL0), T0 + READ_MS).number == 2.0,
                "Another scooter's reading starts afresh, for that scooter");
        for (int i = 2; i < 2 + 3 * ChargeCurrentAverage.CAPACITY; i++) fresh.observe("other", true, i % 2 == 0 ? 2.0 : 2.02, T0 + i * 100L);
        check(fresh.held() <= ChargeCurrentAverage.CAPACITY, "Memory stays bounded however often readings come");
        ChargeCurrentAverage window = new ChargeCurrentAverage();
        for (int i = 0; i < 40; i++) window.observe(SCOOTER, true, i < 5 ? 2.08 : 2.06, T0 + i * READ_MS);
        check(window.held() == 31 && Math.abs(window.steady(SCOOTER, new ChargingEstimate.Value(2.06, true, WALL0), T0 + 39 * READ_MS).number - 2.06) < 1e-9,
                "Readings older than the window drop out of the mean");

        System.out.println("ChargeCurrentAverageTest: " + checks + " checks passed (averaged charging current: no flipping time to full)");
    }
}
