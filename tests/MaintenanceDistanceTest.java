package com.jjricks.volt;

import com.jjricks.volt.MaintenanceDistance.Kind;
import com.jjricks.volt.MaintenancePolicy.Reminder;

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
        // Codex round 1: a lower reading that isn't near the bottom of the field is a reset, not an almost-full lap.
        MaintenanceDistance ambiguous = new MaintenanceDistance();
        ambiguous.observe(250, 8, WALL);
        o = ambiguous.observe(240, 8, WALL + 1);
        check(o.kind == Kind.RESET && o.addedKm == 0 && ambiguous.accumulatedKm == 0 && ambiguous.resetWall == WALL + 1, "1-byte 250 to 240 is a reset: nothing added, the reset recorded");
        MaintenanceDistance outside = new MaintenanceDistance();
        outside.observe(250, 8, WALL);
        check(outside.observe(20, 8, WALL + 1).kind == Kind.RESET && outside.accumulatedKm == 0, "250 to 20 (outside the bottom 5 %) is a reset");
        MaintenanceDistance midField = new MaintenanceDistance();
        midField.observe(6550.0, 16, WALL);
        check(midField.observe(400.0, 16, WALL + 1).kind == Kind.RESET && midField.accumulatedKm == 0, "16-bit 6,550 to 400 is a reset");
        MaintenanceDistance bottom = new MaintenanceDistance();
        bottom.observe(250, 8, WALL);
        check(bottom.observe(12, 8, WALL + 1).kind == Kind.WRAP && near(bottom.accumulatedKm, 18), "250 to 12 (inside the bottom 5 %) is a wrap");
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
        // Codex round 1: a service before the first reading counts its whole distance, never clamped to the count's start.
        MaintenanceDistance first = new MaintenanceDistance();
        first.observe(2000, 32, WALL);
        MaintenanceDistance.AtRaw at = first.accumulatedAtRaw(1000);
        check(at.kind == MaintenanceDistance.Backdate.OK && near(at.accKm, -1000), "done at 1,000 km, first read at 2,000 km: 1,000 km before the count");
        Reminder r = MaintenancePolicy.create("b", MaintenanceDefaults.BRAKES, "", 500.0, 0, 0, true, WALL, at.accKm, WALL);
        check(MaintenancePolicy.status(r, WALL, first.accumulatedKm, java.time.ZoneOffset.UTC).state == MaintenancePolicy.State.DUE, "so a 500 km reminder from then is already due");
        check(first.accumulatedAtRaw(0).kind == MaintenanceDistance.Backdate.OK && near(first.accumulatedAtRaw(0).accKm, -2000), "the whole odometer since new is this scooter's");
        first.observe(2400, 32, WALL + 1);
        check(near(first.accumulatedAtRaw(1100).accKm, -900) && near(first.accumulatedAtRaw(2400).accKm, 400), "a past odometer value maps onto the count");
        check(first.accumulatedAtRaw(2500).kind == MaintenanceDistance.Backdate.AHEAD, "a value ahead of the reading is declined");
        check(first.accumulatedAtRaw(-1).kind == MaintenanceDistance.Backdate.INVALID && first.accumulatedAtRaw(Double.NaN).kind == MaintenanceDistance.Backdate.INVALID, "bad input is declined");
        check(new MaintenanceDistance().accumulatedAtRaw(10).kind == MaintenanceDistance.Backdate.NO_BASELINE && Double.isNaN(new MaintenanceDistance().accumulatedAtRaw(10).accKm), "no baseline is declined");
        // After a reset only the new odometer's values can be tied to the count.
        first.observe(30, 32, WALL + 2);
        check(first.accumulatedAtRaw(10).kind == MaintenanceDistance.Backdate.BEFORE_RESET, "a value from before the reset is declined");
        first.observe(80, 32, WALL + 3);
        check(first.accumulatedAtRaw(30).kind == MaintenanceDistance.Backdate.OK && near(first.accumulatedAtRaw(30).accKm, 400) && near(first.accumulatedAtRaw(50).accKm, 420), "values since the reset map onto the count");
        // After a wrap the current lap is usable; the earlier lap is ambiguous and declined.
        MaintenanceDistance lap = new MaintenanceDistance();
        lap.observe(6500, 16, WALL); lap.observe(6550, 16, WALL + 1); lap.observe(100, 16, WALL + 2);
        check(lap.accumulatedAtRaw(20).kind == MaintenanceDistance.Backdate.OK && near(lap.accumulatedAtRaw(20).accKm, lap.accumulatedKm - 80), "a value in the current lap maps");
        check(lap.accumulatedAtRaw(6520).kind == MaintenanceDistance.Backdate.AHEAD, "a value from the lap before the wrap is declined");
        MaintenanceDistance width = new MaintenanceDistance();
        width.observe(1000, 16, WALL); width.observe(7554.6, 32, WALL + 1);
        check(width.accumulatedAtRaw(7000).kind == MaintenanceDistance.Backdate.BEFORE_RESET && width.accumulatedAtRaw(7554.6).kind == MaintenanceDistance.Backdate.OK, "after a field-width change only the new values map");
        MaintenanceDistance merged = new MaintenanceDistance();
        merged.observe(100, 32, WALL); merged.observe(400, 32, WALL + 1); merged.observe(50, 32, WALL + 2); merged.clearBaseline(); merged.observe(9000, 32, WALL + 3);
        check(merged.accumulatedAtRaw(8000).kind == MaintenanceDistance.Backdate.OK && near(merged.accumulatedAtRaw(8000).accKm, 300 - 1000), "after a merge, the new scooter's whole odometer maps");
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
        // Codex round 1: a scooter that sits still must still save its freshness.
        MaintenanceDistance still = new MaintenanceDistance();
        still.observe(500, 32, WALL);
        MaintenanceDistance disk = still.copy();
        still.observe(500, 32, WALL + 5 * 3_600_000L);
        check(!still.worthSaving(disk), "the same reading within 6 h: no write");
        still.observe(500, 32, WALL + 8 * DAY);
        check(still.worthSaving(disk), "the same reading 8 days later is written");
        disk = still.copy();
        check(!disk.stale(WALL + 8 * DAY + 1000), "so the saved copy is not stale after a restart");
        still.observe(500.05, 32, WALL + 8 * DAY + 2000);
        check(!still.worthSaving(disk), "and the next poll right after is not written again");
    }
}
