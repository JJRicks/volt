package com.jjricks.volt;

import com.jjricks.volt.MaintenanceDistance.Kind;

/** The forward-only maintenance odometer: forward, wrap, reset, swap, merge and the write throttle. */
public final class MaintenanceDistanceTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, DAY = 86_400_000L;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] args) {
        forward();
        wraps();
        resets();
        swapAndMerge();
        oddInput();
        pastOdometer();
        throttle();
        System.out.println("MaintenanceDistanceTest: " + checks + " checks passed (forward, 8/16-bit wraps, resets, swaps, merges, throttle)");
    }

    private static void forward() {
        MaintenanceDistance d = new MaintenanceDistance();
        check(!d.hasBaseline() && !d.everRead() && !d.stale(WALL), "a new scooter has no baseline and is not stale");
        check(d.observe(2009.4, 32, WALL).kind == Kind.FIRST && d.accumulatedKm == 0 && d.everRead(), "the first read baselines without adding");
        MaintenanceDistance.Outcome o = d.observe(2012.1, 32, WALL + 1000);
        check(o.kind == Kind.FORWARD && near(o.addedKm, 2.7) && near(d.accumulatedKm, 2.7), "a forward reading adds its delta");
        check(d.observe(2012.1, 32, WALL + 2000).kind == Kind.SAME && near(d.accumulatedKm, 2.7), "the same reading adds nothing");
        o = d.observe(2412.1, 32, WALL + 30 * DAY);
        check(o.kind == Kind.FORWARD && near(d.accumulatedKm, 402.7), "a big jump (rides without Volt) is real distance and counted in full");
        for (int i = 0; i < 1000; i++) d.observe(2412.1 + (i + 1) * 0.1, 32, WALL + 31 * DAY + i);
        check(near(d.accumulatedKm, 502.7), "a thousand tenths sum without binary noise: " + d.accumulatedKm);
        check(d.stale(WALL + 31 * DAY + 999 + 8 * DAY) && !d.stale(WALL + 31 * DAY + 999 + 6 * DAY), "the reading goes stale after 7 days");
    }

    private static void wraps() {
        MaintenanceDistance d = new MaintenanceDistance();
        d.observe(6550.0, 16, WALL);
        MaintenanceDistance.Outcome o = d.observe(2.5, 16, WALL + 1);
        check(o.kind == Kind.WRAP && near(o.addedKm, 6.1) && near(d.accumulatedKm, 6.1) && d.resetWall == 0, "a 16-bit field wraps at 6,553.6: 6,550.0 to 2.5 is 6.1 km");
        check(d.observe(10.0, 16, WALL + 2).kind == Kind.FORWARD && near(d.accumulatedKm, 13.6), "counting goes on after the wrap");
        MaintenanceDistance v0 = new MaintenanceDistance();
        v0.observe(250, 8, WALL);
        o = v0.observe(4, 8, WALL + 1);
        check(o.kind == Kind.WRAP && near(o.addedKm, 10) && near(v0.accumulatedKm, 10), "a 1-byte v0 field wraps at 256: 250 to 4 is 10 km");
        MaintenanceDistance edge = new MaintenanceDistance();
        edge.observe(6553.6 * 0.95, 16, WALL);
        check(edge.observe(1.0, 16, WALL + 1).kind == Kind.WRAP, "the top 5 % of the field counts as near the wrap");
        MaintenanceDistance wide = new MaintenanceDistance();
        wide.observe(6550.0, 32, WALL);
        check(wide.observe(2.5, 32, WALL + 1).kind == Kind.RESET && wide.accumulatedKm == 0, "a 32-bit field does not wrap in practice: backwards is a reset");
    }

    private static void resets() {
        MaintenanceDistance d = new MaintenanceDistance();
        d.observe(3000.0, 16, WALL);
        d.observe(3100.0, 16, WALL + 1);
        MaintenanceDistance.Outcome o = d.observe(12.0, 16, WALL + 2);
        check(o.kind == Kind.RESET && o.addedKm == 0 && near(d.accumulatedKm, 100) && d.resetWall == WALL + 2, "backwards far from the wrap is a reset: nothing added, the time kept");
        check(d.observe(20.0, 16, WALL + 3).kind == Kind.FORWARD && near(d.accumulatedKm, 108), "counting goes on from the new baseline");
        MaintenanceDistance glitch = new MaintenanceDistance();
        glitch.observe(500.0, 32, WALL); glitch.observe(499.9, 32, WALL + 1);
        check(glitch.resetWall == 0 && glitch.lastRawKm == 500.0 && glitch.accumulatedKm == 0, "a 0.1 km backward glitch is ignored, not a reset");
        glitch.observe(500.5, 32, WALL + 2);
        check(near(glitch.accumulatedKm, 0.5), "and the next reading counts from the higher value");
        MaintenanceDistance firmware = new MaintenanceDistance();
        firmware.observe(1000.0, 16, WALL); firmware.observe(1001.0, 16, WALL + 1);
        check(firmware.observe(7554.6, 32, WALL + 2).kind == Kind.REBASELINE && near(firmware.accumulatedKm, 1) && firmware.bits == 32, "a field width change re-baselines without adding");
    }

    private static void swapAndMerge() {
        // A new dashboard or controller on the same profile: the odometer starts over at a low value.
        MaintenanceDistance d = new MaintenanceDistance();
        d.observe(1200.0, 32, WALL); d.observe(1500.0, 32, WALL + 1);
        d.observe(0.4, 32, WALL + 2);
        check(near(d.accumulatedKm, 300) && d.resetWall == WALL + 2, "a swapped dashboard keeps the counted distance");
        // A backup merged onto another physical scooter: the baseline is cleared, the counted distance stays.
        MaintenanceDistance moved = d.copy();
        moved.clearBaseline();
        check(!moved.hasBaseline() && moved.everRead() && near(moved.accumulatedKm, 300), "a cleared baseline keeps the count");
        check(moved.observe(9000.0, 32, WALL + 3).kind == Kind.REBASELINE && near(moved.accumulatedKm, 300) && moved.resetWall == WALL + 2,
                "the other scooter's first reading re-baselines: no bogus 9,000 km and no reset notice");
        check(moved.observe(9001.0, 32, WALL + 4).kind == Kind.FORWARD && near(moved.accumulatedKm, 301), "then it counts normally");
        check(d.lastRawKm == 0.4, "copy() is independent");
    }

    private static void oddInput() {
        MaintenanceDistance d = new MaintenanceDistance();
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1})
            check(d.observe(bad, 32, WALL).kind == Kind.IGNORED && !d.everRead(), "an invalid reading is ignored: " + bad);
        d.observe(100, 32, WALL + 10);
        d.observe(101, 32, WALL);
        check(d.lastWall == WALL + 10 && near(d.accumulatedKm, 1), "a reading with an older clock still counts, and lastWall never goes back");
    }

    private static void pastOdometer() {
        MaintenanceDistance d = new MaintenanceDistance();
        d.observe(1000, 32, WALL); d.observe(1400, 32, WALL + 1);
        check(near(d.accumulatedAtRaw(1100), 100) && near(d.accumulatedAtRaw(1400), 400), "a past odometer value maps onto the count");
        check(d.accumulatedAtRaw(0) == 0, "never below zero");
        check(Double.isNaN(d.accumulatedAtRaw(1500)) && Double.isNaN(new MaintenanceDistance().accumulatedAtRaw(10)), "a future value or no baseline gives NaN");
    }

    private static void throttle() {
        MaintenanceDistance d = new MaintenanceDistance();
        check(d.worthSaving(null), "nothing saved yet: save");
        d.observe(100.0, 32, WALL);
        MaintenanceDistance saved = d.copy();
        check(!d.worthSaving(saved), "unchanged: no write");
        d.observe(100.05, 32, WALL + 1);
        check(!d.worthSaving(saved), "under 0.1 km: no write");
        d.observe(100.1, 32, WALL + 2);
        check(d.worthSaving(saved), "0.1 km: write");
        saved = d.copy(); d.observe(3.0, 32, WALL + 3);
        check(d.worthSaving(saved), "a reset is always written");
        saved = d.copy(); d.clearBaseline();
        check(d.worthSaving(saved), "a cleared baseline is written");
    }
}
