package com.jjricks.volt;

import java.util.*;

/** Riding mode's live values from real Telemetry frames: tenths, sign, staleness, intervals, efficiency, energy, scale, trace and timers. */
public final class RidingValuesTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static boolean near(double a, double b, double tolerance) { return Math.abs(a - b) <= tolerance; }

    public static void main(String[] args) {
        tenths();
        sign();
        staleness();
        intervals();
        signCorrection();
        efficiency();
        sharedCoverage();
        energyAndScale();
        trace();
        directionHistory();
        unknownAfterRegen();
        inferredDraw();
        inferredThreshold();
        inferredWindow();
        inferredFromFrames();
        inferredThenStrongerRegen();
        meterMatchesRecording();
        unknownUntilSign();
        timers();
        rideTimeNeverFreezes();
        rideTimeAcrossReopen();
        tripRestartEvidence();
        battery();
        ranges();
        System.out.println("RidingValuesTest: " + checks + " checks passed (0x91/0x92, sign, sign corrections, shared coverage, direction history, unknown after regen, inferred draw, meter vs recording, unknown until sign, staleness, interval EMA, coverage, energy, scale, trace gaps, timers, ride time never freezes or snaps back, trip restarts vs reconnects, bands, ranges)");
    }

    /** 0x92: battery, status, speed tenths (LE16), range, trip tenths (LE16), trip minutes, max, avg, odometer. */
    private static byte[] expanded(int speedTenths, int tripTenths, int minutes) {
        return new byte[]{0, 72, 1, (byte) speedTenths, (byte) (speedTenths >> 8), 38, (byte) tripTenths, (byte) (tripTenths >> 8), (byte) minutes, 0, 1, 0, 1, 0, 0};
    }
    /** 0x91: whole units. */
    private static byte[] whole(int speed, int trip, int minutes) { return new byte[]{0, 72, 1, (byte) speed, 38, (byte) trip, (byte) minutes, 30, 15, 100}; }
    /** 0x72: status, percent, voltage mV (LE32), current mA (LE32). */
    private static byte[] batteryRead(int percent, int millivolts, int milliamps) {
        byte[] b = new byte[11];
        b[1] = 0; b[2] = (byte) percent;
        for (int i = 0; i < 4; i++) { b[3 + i] = (byte) (millivolts >> (8 * i)); b[7 + i] = (byte) (milliamps >> (8 * i)); }
        return b;
    }
    private static byte[] signs(int temperature, int current) { return new byte[]{0, (byte) temperature, (byte) current}; }

    private static void tenths() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        telemetry.accept(0x92, expanded(184, 37, 12), 1000);
        values.update(telemetry.metrics());
        RidingValues.Speed speed = values.speed(1200);
        check(speed.kmh != null && near(speed.kmh, 18.4, 1e-9) && speed.tenths && !speed.stale, "0x92 speed keeps its tenths: " + speed.kmh);
        Telemetry older = new Telemetry();
        RidingValues wholeValues = new RidingValues();
        older.accept(0x91, whole(18, 3, 12), 1000);
        wholeValues.update(older.metrics());
        speed = wholeValues.speed(1200);
        check(speed.kmh != null && speed.kmh == 18 && !speed.tenths, "0x91 speed shows no tenths it didn't receive");
        values.update(telemetry.metrics());
        check(values.reading("speed_kmh", 1200).updatedMs == 1000, "a snapshot seen twice adds nothing");
        values.sample("lock_status", 1, 0x70, 1100);
        check(values.reading("lock_status", 1200) == null, "lock state isn't tracked at all");
    }

    private static void sign() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        telemetry.accept(0x72, batteryRead(72, 50000, 3600), 1000);
        values.update(telemetry.metrics());
        RidingValues.Power power = values.power(1100);
        check(power.direction == RidingValues.Direction.UNKNOWN && power.watts != null && near(power.watts, 180, 0.01), "without 0x79 the direction is unknown and the bar shows magnitude");
        check(power.peakRegenW == 0 && power.peakDrawW == 0, "no peak draw or regen is claimed without a sign");
        telemetry.accept(0x79, signs(0, 1), 1500);
        telemetry.accept(0x72, batteryRead(72, 50000, 3600), 3000);
        values.update(telemetry.metrics());
        power = values.power(3100);
        check(power.direction == RidingValues.Direction.REGEN && near(power.watts, -180, 0.01) && near(power.peakRegenW, 180, 0.01), "a confirmed negative current is regen: " + power.watts);
        telemetry.accept(0x79, signs(0, 0), 3500);
        telemetry.accept(0x72, batteryRead(70, 50000, 12800), 5000);
        values.update(telemetry.metrics());
        power = values.power(5100);
        check(power.direction == RidingValues.Direction.DRAW && near(power.watts, 640, 0.01) && near(power.peakDrawW, 640, 0.01), "a confirmed positive current is draw");
        telemetry.accept(0x79, signs(254, 254), 6000);
        values.update(telemetry.metrics());
        // GT3 Max reported 254: Telemetry keeps it raw but riding mode treats it as unknown.
        values.sample("current_sign_state", 254, 0x79, 6500);
        check(values.signState() == RidingValues.Direction.UNKNOWN, "a sign byte other than 0/1 is unknown, never a guess");
        check(values.power(5100).direction == RidingValues.Direction.DRAW, "and it doesn't reinterpret the draw already measured");
        values.sample("power_w", 300, 0x72, 7000);
        check(values.power(7100).direction == RidingValues.Direction.UNKNOWN, "the next reading after it is unknown");
    }

    private static void staleness() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        values.sample("speed_kmh", 18.4, 0x92, 10_000);
        check(!values.speed(12_900).stale, "speed is fresh within 3 intervals");
        RidingValues.Speed dim = values.speed(13_500);
        check(dim.stale && dim.kmh != null, "older than 3 intervals: dim but still shown");
        RidingValues.Speed gone = values.speed(16_100);
        check(gone.kmh == null && gone.lastKmh != null && near(gone.lastKmh, 18.4, 1e-9) && gone.tenths, "after 6 s speed is gone, with the last value for \"last 18.4\"");
        check(new RidingValues().speed(0).kmh == null && new RidingValues().speed(0).stale, "no speed before the first reading");
        values.sample("power_w", 500, 0x72, 10_000);
        check(values.power(15_000).watts != null && values.power(16_100).watts == null, "a stale power bar falls back to zero (null watts) after 3 x 2 s");
    }

    private static void intervals() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 5000);
        check(values.intervalMs("speed_kmh") == 1000 && values.intervalMs("power_w") == 5000, "nominal intervals before any reading");
        long t = 0;
        for (int i = 0; i < 40; i++) { values.sample("speed_kmh", 10, 0x92, t); t += 1500; }
        check(near(values.intervalMs("speed_kmh"), 1500, 20), "the EMA learns a slower push: " + values.intervalMs("speed_kmh"));
        values.sample("speed_kmh", 10, 0x92, t + 30_000);
        check(near(values.intervalMs("speed_kmh"), 1500, 20), "a dropout doesn't stretch the interval");
        for (int i = 0; i < 40; i++) values.sample("power_w", 100, 0x72, 1 + i * 5000L);
        check(near(values.intervalMs("power_w"), 5000, 1), "the battery read follows the refresh setting");
        values.setNominalIntervals(1000, 2000);
        check(values.intervalMs("power_w") == 2000 && values.intervalMs("speed_kmh") == 1500, "a setting change (recording) restarts only the affected readings");
        values.sample("speed_kmh", 10, 0x92, t + 30_000 - 10);
        check(values.reading("speed_kmh", t + 30_000).updatedMs == t + 30_000, "an older sample than the latest is ignored");
    }

    private static void efficiency() {
        // 20 km/h at 400 W for 40 s, 1 s speed and 2 s power: 20 Wh/km.
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        values.sample("current_sign_state", 0, 0x79, 0);
        for (long t = 0; t <= 40_000; t += 1000) {
            values.sample("speed_kmh", 20, 0x92, t);
            if (t % 2000 == 0) values.sample("power_w", 400, 0x72, t);
        }
        RidingValues.Efficiency e = values.efficiency(40_000);
        check(e.whPerKm != null && near(e.whPerKm, 20, 0.01) && e.coverage >= 0.99 && e.directionConfirmed, "30 s efficiency: " + e.whPerKm + " coverage " + e.coverage);
        // A 10 s power dropout inside the window: coverage falls below 80 %.
        RidingValues gap = new RidingValues();
        gap.setNominalIntervals(1000, 2000);
        for (long t = 0; t <= 40_000; t += 1000) {
            gap.sample("speed_kmh", 20, 0x92, t);
            if (t % 2000 == 0 && (t < 20_000 || t > 30_000)) gap.sample("power_w", 400, 0x72, t);
        }
        RidingValues.Efficiency partial = gap.efficiency(40_000);
        check(partial.whPerKm == null && partial.coverage < RidingValues.EFFICIENCY_COVERAGE && partial.coverage > 0.5, "a dropout gates efficiency off: coverage " + partial.coverage);
        check(!partial.directionConfirmed, "and says the direction wasn't confirmed");
        // Stopped: covered but no distance.
        RidingValues stopped = new RidingValues();
        for (long t = 0; t <= 40_000; t += 1000) { stopped.sample("speed_kmh", 0, 0x92, t); stopped.sample("power_w", 15, 0x72, t); }
        check(stopped.efficiency(40_000).whPerKm == null, "no Wh/km while stopped");
        // Readings that stop arriving age out of the window rather than holding forever.
        check(values.efficiency(80_000).whPerKm == null, "an old window isn't reused");
        check(new RidingValues().efficiency(0).whPerKm == null, "nothing before readings");
        // A slow refresh (10 s) still covers its window: holds follow the expected interval.
        RidingValues slow = new RidingValues();
        slow.setNominalIntervals(1000, 10_000);
        for (long t = 0; t <= 60_000; t += 1000) { slow.sample("speed_kmh", 20, 0x92, t); if (t % 10_000 == 0) slow.sample("power_w", 400, 0x72, t); }
        check(slow.efficiency(60_000).whPerKm != null, "a 10 s Telemetry refresh still gives efficiency");
    }

    private static void energyAndScale() {
        RidingValues.PowerScale scale = new RidingValues.PowerScale();
        RidingValues values = new RidingValues(scale);
        values.setNominalIntervals(1000, 2000);
        values.sample("current_sign_state", 0, 0x79, 0);
        for (long t = 0; t <= 3_600_000; t += 2000) values.sample("power_w", 360, 0x72, t);
        check(near(values.rideEnergyWh(), 360, 0.01), "an hour at 360 W is 360 Wh: " + values.rideEnergyWh());
        values.sample("power_w", 360, 0x72, 3_700_000);
        check(near(values.rideEnergyWh(), 360, 0.01), "a gap adds no invented energy");
        check(scale.drawW == 1000 && scale.regenW == 500 && !values.takeScaleChanged(), "the scale starts at 1,000 W draw and 500 W regen");
        values.sample("power_w", 1130, 0x72, 3_702_000);
        check(scale.drawW == 1250 && values.takeScaleChanged() && !values.takeScaleChanged(), "the scale grows to the peak rounded up to 250 W, flagged once to save");
        values.sample("power_w", 900, 0x72, 3_704_000);
        check(scale.drawW == 1250, "the scale never shrinks during a ride");
        values.sample("current_sign_state", 1, 0x79, 3_705_000);
        values.sample("power_w", -620, 0x72, 3_706_000);
        check(scale.regenW == 750 && scale.drawW == 1250, "regen has its own scale");
        values.sample("power_w", 40_000, 0x72, 3_708_000);
        values.sample("current_sign_state", 0, 0x79, 3_709_000);
        values.sample("power_w", 40_000, 0x72, 3_710_000);
        check(scale.drawW == 1250 && scale.regenW == 750, "a glitch reading never stretches the scale");
        check(values.power(3_710_000).peakDrawW == 1130 && values.power(3_710_000).peakRegenW == 620, "nor the peaks");
        RidingValues.PowerScale restored = new RidingValues.PowerScale(Double.NaN, 99_999);
        check(restored.drawW == 1000 && restored.regenW == RidingValues.PowerScale.REGEN_CAP_W, "a saved scale is clamped");
        check(new RidingValues.PowerScale(10, 10).drawW == 1000, "a saved scale never falls below the start");
    }

    private static void trace() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        for (long t = 0; t <= 150_000; t += 1000) if (t < 100_000 || t > 110_000) values.sample("speed_kmh", t / 1000.0, 0x92, t);
        List<RidingValues.TracePoint> points = values.speedTrace(150_000, 60_000);
        check(points.get(0).t == 90_000 && points.get(points.size() - 1).t == 150_000, "the trace shows the last 60 s");
        check(!points.get(0).gapBefore, "the first point never starts with a gap");
        int gaps = 0;
        for (RidingValues.TracePoint p : points) if (p.gapBefore) { gaps++; check(p.t == 111_000, "the gap is drawn where the data stopped"); }
        check(gaps == 1, "one gap, drawn as a gap and never as zero");
        for (RidingValues.TracePoint p : points) check(p.value != 0, "no invented zeros");
        check(values.speedTrace(150_000, 200_000).get(0).t >= 30_000, "the buffer keeps about 120 s");
        values.sample("power_w", -200, 0x72, 150_000);
        check(values.powerTrace(150_000, 60_000).get(0).value == 200, "power trace is unsigned while the direction is unknown");
        values.sample("current_sign_state", 1, 0x79, 150_500);
        check(values.powerTrace(150_000, 60_000).get(0).value == 200, "a later sign alone doesn't reinterpret it");
        values.sample("power_w", -200, 0x72, 150_000);
        check(values.powerTrace(150_000, 60_000).get(0).value == -200, "Telemetry re-signing that same measurement does");
    }

    /** Telemetry re-signs current and power after 0x79 but keeps the measurement time (Codex #228 review). */
    private static void signCorrection() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        telemetry.accept(0x72, batteryRead(72, 50000, 3600), 1000);
        values.update(telemetry.metrics());
        long interval = values.intervalMs("power_w");
        telemetry.accept(0x79, signs(0, 1), 1500);
        values.update(telemetry.metrics());
        double telemetryW = 0;
        for (Telemetry.Metric m : telemetry.metrics()) if (m.key.equals("power_w")) telemetryW = m.numericValue;
        RidingValues.Power power = values.power(1600);
        check(near(telemetryW, -180, 0.01) && power.watts != null && near(power.watts, telemetryW, 0.01) && power.direction == RidingValues.Direction.REGEN,
                "a sign correction follows Telemetry: " + telemetryW + " vs " + power.watts + " " + power.direction);
        check(near(power.peakRegenW, 180, 0.01) && power.peakDrawW == 0, "the corrected reading is regen, not draw, in the peaks");
        RidingValues.Reading r = values.reading("power_w", 1600);
        check(r.updatedMs == 1000 && values.intervalMs("power_w") == interval, "a correction isn't a new measurement: time and cadence stay");
        check(values.powerTrace(1600, 60_000).size() == 1 && values.powerTrace(1600, 60_000).get(0).value == -180, "nor a new trace point");
        values.update(telemetry.metrics());
        check(values.rideEnergyWh() == 0, "a lone sample has no energy, corrected or not");

        // Energy: a correction redoes its own segment once, never adds a second one.
        Telemetry ride = new Telemetry();
        RidingValues energy = new RidingValues();
        energy.setNominalIntervals(1000, 2000);
        ride.accept(0x79, signs(0, 0), 0);
        ride.accept(0x72, batteryRead(72, 50000, 3600), 1000);
        energy.update(ride.metrics());
        ride.accept(0x72, batteryRead(72, 50000, 3600), 3000);
        energy.update(ride.metrics());
        check(near(energy.rideEnergyWh(), 180 * 2 / 3600.0, 1e-9), "two seconds at 180 W: " + energy.rideEnergyWh());
        ride.accept(0x79, signs(0, 1), 3500);
        energy.update(ride.metrics());
        energy.update(ride.metrics());
        check(near(energy.rideEnergyWh(), 0, 1e-9), "re-signed to -180 W the segment averages to 0, counted once: " + energy.rideEnergyWh());
        check(energy.power(3600).peakDrawW == 180 && near(energy.power(3600).peakRegenW, 180, 0.01), "the earlier draw stays draw; the corrected one is regen");
        check(energy.reading("power_w", 3600).updatedMs == 3000, "still measured at 3 s");

        // The same value re-fed is not a correction; neither is an unknown sign.
        RidingValues same = new RidingValues();
        same.sample("current_sign_state", 0, 0x79, 0);
        same.sample("power_w", 500, 0x72, 1000);
        same.sample("power_w", 500, 0x72, 1000);
        check(same.power(1100).peakDrawW == 500 && same.rideEnergyWh() == 0, "a repeated sample changes nothing");
        same.sample("current_sign_state", 254, 0x79, 1500);
        same.sample("power_w", 500, 0x72, 1000);
        check(same.power(1600).direction == RidingValues.Direction.DRAW, "an unknown sign never demotes a confirmed reading");
        // A corrected speed (same time, new value) updates the reading and its trace point only.
        same.sample("speed_kmh", 18, 0x92, 1000);
        same.sample("speed_kmh", 18.4, 0x92, 1000);
        check(near(same.speed(1100).kmh, 18.4, 1e-9) && same.speedTrace(1100, 60_000).size() == 1 && near(same.speedTrace(1100, 60_000).get(0).value, 18.4, 1e-9), "a corrected speed replaces its point");
    }

    /** Energy and distance come from the same covered stretches (Codex #228 review). */
    private static void sharedCoverage() {
        // 400 W at 20 km/h with power reports at 8-16 s dropped: 20 Wh/km over what was observed.
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        values.sample("current_sign_state", 0, 0x79, 0);
        for (long t = 0; t <= 30_000; t += 1000) {
            values.sample("speed_kmh", 20, 0x92, t);
            if (t % 2000 == 0 && (t < 8000 || t > 16_000)) values.sample("power_w", 400, 0x72, t);
        }
        RidingValues.Efficiency e = values.efficiency(30_000);
        check(e.whPerKm != null && near(e.whPerKm, 20, 1e-9) && near(e.coverage, 0.8, 1e-9), "unequal gaps: " + e.whPerKm + " at " + e.coverage);
        // Power is uncovered 12-18 s and speed 20-26 s: each alone covers 80 %, together only 60 %.
        RidingValues disjoint = new RidingValues();
        disjoint.setNominalIntervals(1000, 2000);
        disjoint.sample("current_sign_state", 0, 0x79, 0);
        for (long t = 0; t <= 30_000; t += 1000) {
            if (t < 18_000 || t >= 26_000) disjoint.sample("speed_kmh", 20, 0x92, t);
            if (t % 2000 == 0 && (t < 8000 || t > 16_000)) disjoint.sample("power_w", 400, 0x72, t);
        }
        RidingValues.Efficiency d = disjoint.efficiency(30_000);
        check(d.whPerKm == null && near(d.coverage, 0.6, 1e-9), "disjoint gaps gate on the shared 60 %: " + d.coverage);
        // Changing speed and power in step: the ratio uses matching stretches only.
        RidingValues varying = new RidingValues();
        varying.setNominalIntervals(1000, 1000);
        varying.sample("current_sign_state", 0, 0x79, 0);
        for (long t = 0; t <= 30_000; t += 1000) {
            boolean fast = t >= 15_000;
            varying.sample("speed_kmh", fast ? 30 : 10, 0x92, t);
            // Power drops out for 4 s in the slow half.
            if (t < 4000 || t > 8000) varying.sample("power_w", fast ? 900 : 100, 0x72, t);
        }
        RidingValues.Efficiency v = varying.efficiency(30_000);
        // Power holds 3 s, so it covers 0-6 s and 9-30 s: 12 s slow (100 W, 10 km/h) and 15 s fast (900 W, 30 km/h).
        double wh = (100 * 12 + 900 * 15) / 3600.0, km = (10 * 12 + 30 * 15) / 3600.0;
        check(near(v.coverage, 0.9, 1e-9), "shared coverage 27 of 30 s: " + v.coverage);
        check(v.whPerKm != null && near(v.whPerKm, wh / km, 1e-9), "matching stretches: " + v.whPerKm + " vs " + wh / km);
    }

    /** Each sample keeps the direction it was measured under (Codex #228 review). */
    private static void directionHistory() {
        RidingValues late = new RidingValues();
        late.setNominalIntervals(1000, 2000);
        for (long t = 0; t <= 40_000; t += 1000) { late.sample("speed_kmh", 20, 0x92, t); if (t % 2000 == 0) late.sample("power_w", 400, 0x72, t); }
        check(!late.efficiency(40_000).directionConfirmed, "no sign: unconfirmed");
        late.sample("current_sign_state", 0, 0x79, 40_001);
        check(!late.efficiency(40_001).directionConfirmed, "a late first sign doesn't confirm the window measured before it");
        for (long t = 41_000; t <= 80_000; t += 1000) { late.sample("speed_kmh", 20, 0x92, t); if (t % 2000 == 0) late.sample("power_w", 400, 0x72, t); }
        check(late.efficiency(80_000).directionConfirmed, "a window measured under it is confirmed");
        check(!late.efficiency(60_000).directionConfirmed, "a window straddling it isn't");

        RidingValues regen = new RidingValues();
        regen.setNominalIntervals(1000, 2000);
        regen.sample("current_sign_state", 1, 0x79, 0);
        for (long t = 0; t <= 10_000; t += 2000) regen.sample("power_w", -300, 0x72, t);
        regen.sample("current_sign_state", 254, 0x79, 10_500);
        for (long t = 12_000; t <= 20_000; t += 2000) regen.sample("power_w", 300, 0x72, t);
        List<RidingValues.TracePoint> points = regen.powerTrace(20_000, 60_000);
        check(points.get(0).value == -300 && points.get(5).value == -300, "an unknown sign leaves earlier confirmed regen signed");
        check(points.get(6).value == 300 && regen.power(20_000).direction == RidingValues.Direction.UNKNOWN, "later readings are unsigned magnitudes");
        check(regen.power(20_000).peakRegenW == 300, "and earlier regen peaks stay");
    }

    /**
     * Telemetry keeps its last known sign after an invalid 0x79 byte and decodes later current with
     * it; riding mode reads those samples as magnitude, never as regen (Codex #228 round 3).
     */
    private static void unknownAfterRegen() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        telemetry.accept(0x79, signs(0, 1), 0);
        values.update(telemetry.metrics());
        for (long t = 1000; t <= 60_000; t += 1000) {
            if (t == 21_000) { telemetry.accept(0x79, signs(0, 254), 20_500); values.update(telemetry.metrics()); }
            telemetry.accept(0x92, expanded(200, 0, 0), t);
            if (t % 2000 == 0) telemetry.accept(0x72, batteryRead(70, 50000, 8000), t);
            values.update(telemetry.metrics());
        }
        double telemetryW = 0;
        for (Telemetry.Metric m : telemetry.metrics()) if (m.key.equals("power_w")) telemetryW = m.numericValue;
        check(near(telemetryW, -400, 0.01), "Telemetry still decodes with the old regen sign: " + telemetryW);
        RidingValues.Power power = values.power(60_000);
        check(values.signState() == RidingValues.Direction.UNKNOWN && power.direction == RidingValues.Direction.UNKNOWN && near(power.watts, 400, 0.01),
                "the bar shows the fresh reading's magnitude, unknown: " + power.watts + " " + power.direction);
        check(near(power.peakRegenW, 400, 0.01) && power.peakDrawW == 0, "only the confirmed readings set a peak");
        List<RidingValues.TracePoint> points = values.powerTrace(60_000, 120_000);
        check(points.get(0).t == 2000 && points.get(0).value == -400 && points.get(9).t == 20_000 && points.get(9).value == -400,
                "older samples keep their confirmed regen");
        check(points.get(10).t == 22_000 && points.get(10).value == 400, "the first after the invalid sign is a magnitude");

        RidingValues.Efficiency after = values.efficiency(60_000);
        check(after.whPerKm != null && near(after.whPerKm, 20, 1e-9) && near(after.coverage, 1, 1e-9) && !after.directionConfirmed,
                "an all-unknown window counts magnitude, unconfirmed: " + after.whPerKm);
        // 10-40 s: regen held 10-22 s (12 s), unknown 22-40 s (18 s), all at 20 km/h.
        RidingValues.Efficiency mixed = values.efficiency(40_000);
        double wh = (-400 * 12 + 400 * 18) / 3600.0, km = 20 * 30 / 3600.0;
        check(mixed.whPerKm != null && near(mixed.whPerKm, wh / km, 1e-9) && !mixed.directionConfirmed,
                "a mixed window keeps the known regen signed and the rest as magnitude: " + mixed.whPerKm + " vs " + wh / km);
        RidingValues.Efficiency known = values.efficiency(20_000);
        check(known.directionConfirmed, "the stretch measured under the sign stays confirmed");

        // Energy: 2-20 s regen is signed and confirmed; 20-22 s touches the unknown, so 20-60 s is magnitude.
        double confirmedWh = -400 * 18 / 3600.0, unconfirmedWh = 400 * 40 / 3600.0;
        check(near(values.unconfirmedEnergyWh(), unconfirmedWh, 1e-9), "the unconfirmed part is a magnitude: " + values.unconfirmedEnergyWh());
        check(near(values.rideEnergyWh(), confirmedWh + unconfirmedWh, 1e-9), "ride energy never gains from an unconfirmed sign: " + values.rideEnergyWh());
        check(near(values.rideEnergyWh() - values.unconfirmedEnergyWh(), confirmedWh, 1e-9), "and the confirmed net is still regen");

        // A known sign again: new readings are signed and confirmed, the unknown ones stay magnitude.
        telemetry.accept(0x79, signs(0, 0), 60_500);
        values.update(telemetry.metrics());
        check(values.powerTrace(60_500, 120_000).get(28).value == 400 && near(values.unconfirmedEnergyWh(), unconfirmedWh, 1e-9), "a later sign never reinterprets the unknown samples");
    }

    /** A strong reading without a known sign is inferred draw; a weaker one stays unknown (owner, Nov 6). */
    private static void inferredDraw() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        check(values.inferredDrawThresholdW() == 1200, "the threshold starts at 1,200 W");
        for (long t = 0; t <= 10_000; t += 2000) values.sample("power_w", 1500, 0x72, t);
        RidingValues.Power power = values.power(10_000);
        check(power.direction == RidingValues.Direction.DRAW_INFERRED && near(power.watts, 1500, 1e-9), "unknown at 1,500 W is inferred draw: " + power.direction);
        check(power.peakDrawW == 1500 && values.scale().drawW == 1500, "it sets the draw peak and the bar scale");
        check(near(values.rideEnergyWh(), 1500 * 10 / 3600.0, 1e-9) && near(values.inferredDrawWh(), values.rideEnergyWh(), 1e-9), "it counts as draw, reported as inferred");
        check(values.unconfirmedEnergyWh() == 0, "and not as unconfirmed");
        check(values.powerTrace(10_000, 60_000).get(0).value == 1500, "its trace point is positive");

        RidingValues weak = new RidingValues();
        weak.setNominalIntervals(1000, 2000);
        for (long t = 0; t <= 10_000; t += 2000) weak.sample("power_w", 900, 0x72, t);
        power = weak.power(10_000);
        check(power.direction == RidingValues.Direction.UNKNOWN && power.peakDrawW == 0, "unknown at 900 W stays unknown");
        check(near(weak.unconfirmedEnergyWh(), 900 * 10 / 3600.0, 1e-9) && weak.inferredDrawWh() == 0, "with the round-3 magnitude treatment");
        weak.sample("power_w", -1199, 0x72, 12_000);
        check(weak.power(12_000).direction == RidingValues.Direction.UNKNOWN, "just under the threshold is unknown");
        weak.sample("power_w", -1200, 0x72, 14_000);
        check(weak.power(14_000).direction == RidingValues.Direction.DRAW_INFERRED && near(weak.power(14_000).watts, 1200, 1e-9), "at it, inferred draw, whatever Telemetry's stale sign says");
        weak.sample("power_w", 40_000, 0x72, 16_000);
        check(weak.power(16_000).direction == RidingValues.Direction.UNKNOWN && weak.power(16_000).peakDrawW == 1200, "a decoding glitch is never inferred");

        // History stays put: a later confirmed sign leaves inferred samples inferred.
        values.sample("current_sign_state", 1, 0x79, 10_500);
        values.sample("power_w", -300, 0x72, 12_000);
        List<RidingValues.TracePoint> points = values.powerTrace(12_000, 60_000);
        check(points.get(0).value == 1500 && points.get(5).value == 1500 && points.get(6).value == -300, "a later regen sign never re-signs earlier inferred draw");
        // A same-time correction is the reading's own sign, so it still applies (round 2).
        RidingValues corrected = new RidingValues();
        corrected.sample("power_w", 1500, 0x72, 1000);
        corrected.sample("current_sign_state", 1, 0x79, 1500);
        corrected.sample("power_w", -1500, 0x72, 1000);
        check(corrected.power(1600).direction == RidingValues.Direction.REGEN && corrected.power(1600).peakRegenW == 1500 && corrected.power(1600).peakDrawW == 0,
                "Telemetry re-signing the same measurement still corrects it: " + corrected.power(1600).direction);
    }

    /** The threshold rises to 1.5 x the strongest confirmed regen this ride. */
    private static void inferredThreshold() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        values.sample("current_sign_state", 1, 0x79, 0);
        values.sample("power_w", -1000, 0x72, 1000);
        check(values.inferredDrawThresholdW() == 1500, "a confirmed 1,000 W regen raises it to 1,500 W: " + values.inferredDrawThresholdW());
        values.sample("current_sign_state", 254, 0x79, 1500);
        values.sample("power_w", -1400, 0x72, 3000);
        check(values.power(3000).direction == RidingValues.Direction.UNKNOWN, "so 1,400 W is unknown");
        values.sample("power_w", -1500, 0x72, 5000);
        check(values.power(5000).direction == RidingValues.Direction.DRAW_INFERRED, "and 1,500 W inferred draw");
        check(values.inferredDrawThresholdW() == 1500, "inferred draw never moves the threshold");
        values.sample("power_w", 1600, 0x72, 7000);
        check(values.inferredDrawThresholdW() == 1500 && values.power(7000).peakRegenW == 1000, "nor does it ever infer regen");
        RidingValues gentle = new RidingValues();
        gentle.sample("current_sign_state", 1, 0x79, 0);
        gentle.sample("power_w", -600, 0x72, 1000);
        check(gentle.inferredDrawThresholdW() == 1200, "gentle regen leaves the 1,200 W floor");
    }

    /** A window of confirmed, inferred and unknown stretches. */
    private static void inferredWindow() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        values.sample("current_sign_state", 0, 0x79, 0);
        for (long t = 0; t <= 60_000; t += 1000) {
            if (t == 21_000) values.sample("current_sign_state", 254, 0x79, 20_500);
            values.sample("speed_kmh", 20, 0x92, t);
            // Confirmed 400 W to 20 s; then unknown 1,600 W to 40 s, and unknown 400 W after.
            if (t % 2000 == 0) values.sample("power_w", t <= 20_000 ? 400 : t <= 40_000 ? -1600 : 400, 0x72, t);
        }
        RidingValues.Efficiency confirmed = values.efficiency(20_000);
        check(confirmed.directionConfirmed && confirmed.directionKnownOrInferred, "confirmed stretches only: both");
        // 5-35 s: confirmed 5-22 s (17 s), inferred 22-35 s (13 s).
        RidingValues.Efficiency inferred = values.efficiency(35_000);
        double wh = (400 * 17 + 1600 * 13) / 3600.0, km = 20 * 30 / 3600.0;
        check(!inferred.directionConfirmed && inferred.directionKnownOrInferred, "confirmed and inferred: known or inferred, not confirmed");
        check(inferred.whPerKm != null && near(inferred.whPerKm, wh / km, 1e-9), "inferred stretches count as draw: " + inferred.whPerKm + " vs " + wh / km);
        // 20-50 s: confirmed 20-22, inferred 22-42, unknown 42-50.
        RidingValues.Efficiency mixed = values.efficiency(50_000);
        wh = (400 * 2 + 1600 * 20 + 400 * 8) / 3600.0;
        check(!mixed.directionConfirmed && !mixed.directionKnownOrInferred && near(mixed.whPerKm, wh / km, 1e-9), "an unknown stretch clears both: " + mixed.whPerKm);
        // Energy: 0-20 s confirmed, 20-40 s inferred (one end inferred), 40-42 s and on unknown.
        double confirmedWh = 400 * 20 / 3600.0, inferredWh = ((400 + 1600) / 2.0 * 2 + 1600 * 18) / 3600.0, unknownWh = ((1600 + 400) / 2.0 * 2 + 400 * 18) / 3600.0;
        check(near(values.inferredDrawWh(), inferredWh, 1e-9), "inferred energy: " + values.inferredDrawWh() + " vs " + inferredWh);
        check(near(values.unconfirmedEnergyWh(), unknownWh, 1e-9), "unconfirmed energy: " + values.unconfirmedEnergyWh() + " vs " + unknownWh);
        check(near(values.rideEnergyWh(), confirmedWh + inferredWh + unknownWh, 1e-9), "ride energy is all three");
        check(values.power(60_000).peakDrawW == 1600, "the draw peak includes inferred draw");
    }

    /** Real frames: a 254 sign byte, then 30 A at 50 V (1,500 W) and 10 A (500 W). */
    private static void inferredFromFrames() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        telemetry.accept(0x79, signs(0, 1), 0);
        telemetry.accept(0x72, batteryRead(80, 50000, 6000), 1000);
        values.update(telemetry.metrics());
        check(values.power(1000).direction == RidingValues.Direction.REGEN && near(values.power(1000).peakRegenW, 300, 0.01), "confirmed 300 W regen first");
        telemetry.accept(0x79, signs(0, 254), 1500);
        telemetry.accept(0x72, batteryRead(80, 50000, 30000), 3000);
        values.update(telemetry.metrics());
        double telemetryW = 0;
        for (Telemetry.Metric m : telemetry.metrics()) if (m.key.equals("power_w")) telemetryW = m.numericValue;
        RidingValues.Power power = values.power(3000);
        check(near(telemetryW, -1500, 0.01), "Telemetry decodes it with the old regen sign: " + telemetryW);
        check(power.direction == RidingValues.Direction.DRAW_INFERRED && near(power.watts, 1500, 0.01) && near(power.peakDrawW, 1500, 0.01),
                "riding mode reads 1,500 W without a sign as inferred draw: " + power.direction + " " + power.watts);
        telemetry.accept(0x72, batteryRead(80, 50000, 10000), 5000);
        values.update(telemetry.metrics());
        check(values.power(5000).direction == RidingValues.Direction.UNKNOWN && near(values.power(5000).watts, 500, 0.01), "500 W after it is unknown");
        check(values.power(5000).peakRegenW == values.power(1000).peakRegenW, "no regen is ever inferred");
        // 1-3 s: confirmed regen to inferred draw changes direction at an unknown moment, so magnitude; 3-5 s touches the unknown 500 W.
        check(values.inferredDrawWh() == 0 && near(values.unconfirmedEnergyWh(), (900 + 1000) * 2 / 3600.0, 1e-9),
                "regen to inferred draw is unconfirmed magnitude: " + values.inferredDrawWh() + " " + values.unconfirmedEnergyWh());
    }

    /**
     * Inferred draw, then a first confirmed regen stronger than the threshold it was inferred under
     * (Codex #238 round 2). That stretch changes direction at an unknown moment, so it is unconfirmed
     * magnitude: inferred draw never goes negative and the confirmed regen after it still counts.
     */
    private static void inferredThenStrongerRegen() {
        for (int variant = 0; variant < 2; variant++) {
            boolean correction = variant == 1;
            String label = correction ? " (same-time correction)" : "";
            Telemetry telemetry = new Telemetry();
            RidingValues values = new RidingValues();
            values.setNominalIntervals(1000, 2000);
            telemetry.accept(0x79, signs(0, 254), 0);
            telemetry.accept(0x72, batteryRead(80, 50000, 30000), 1000);
            values.update(telemetry.metrics());
            check(values.power(1000).direction == RidingValues.Direction.DRAW_INFERRED, "1,500 W without a sign is inferred draw" + label);
            if (correction) {
                // The 3 s reading first arrives without a sign (inferred), then Telemetry re-signs it.
                telemetry.accept(0x72, batteryRead(80, 50000, 36000), 3000);
                values.update(telemetry.metrics());
                check(values.power(3000).direction == RidingValues.Direction.DRAW_INFERRED, "1,800 W unsigned is inferred first");
                telemetry.accept(0x79, signs(0, 1), 3500);
            } else {
                // The sign and the next reading in one update: the reading is measured under it.
                telemetry.accept(0x79, signs(0, 1), 2500);
                telemetry.accept(0x72, batteryRead(80, 50000, 36000), 3000);
            }
            values.update(telemetry.metrics());
            RidingValues.Power power = values.power(3000);
            check(power.direction == RidingValues.Direction.REGEN && near(power.watts, -1800, 0.01) && values.inferredDrawThresholdW() == 2700,
                    "a stronger confirmed regen follows" + label + ": " + power.direction + " " + power.watts);
            check(values.powerTrace(3000, 60_000).get(0).value == 1500, "the earlier inference is kept" + label);
            check(values.inferredDrawWh() >= 0, "inferred draw is never negative" + label + ": " + values.inferredDrawWh());
            check(near(values.unconfirmedEnergyWh(), (1500 + 1800) / 2.0 * 2 / 3600.0, 1e-9) && values.inferredDrawWh() == 0,
                    "the direction change is unconfirmed magnitude" + label + ": " + values.unconfirmedEnergyWh());
            // A second confirmed regen: that stretch is confirmed and signed.
            telemetry.accept(0x72, batteryRead(80, 50000, 36000), 5000);
            values.update(telemetry.metrics());
            double confirmedWh = values.rideEnergyWh() - values.unconfirmedEnergyWh() - values.inferredDrawWh();
            check(near(confirmedWh, -1800 * 2 / 3600.0, 1e-9), "confirmed regen still counts" + label + ": " + confirmedWh);
            check(near(values.rideEnergyWh(), confirmedWh + values.inferredDrawWh() + values.unconfirmedEnergyWh(), 1e-12) && values.inferredDrawWh() >= 0,
                    "total = confirmed + inferred + unconfirmed" + label);
        }
    }

    /** The recording's power for the latest frame: voltage x current from the same report (RideStore.Accumulator.accept). */
    private static Double recordingW(Telemetry telemetry) {
        Telemetry.Metric v = null, a = null;
        for (Telemetry.Metric m : telemetry.metrics()) { if (m.key.equals("voltage_v")) v = m; if (m.key.equals("current_a")) a = m; }
        return v == null || a == null || v.numericValue == null || a.numericValue == null || v.updatedMs != a.updatedMs || v.sourceCmd != a.sourceCmd
                ? null : v.numericValue * a.numericValue;
    }

    /** One screen refresh: RidingScreen.showPower's call into the bar's GaugeSmoother. */
    private static long barSampleMs = -1;
    private static void refreshBar(RidingValues values, GaugeSmoother bar, long now) {
        RidingValues.Power p = values.power(now);
        RidingValues.Reading r = values.reading("power_w", now);
        if (p.watts == null || r == null) return;
        boolean fresh = r.updatedMs != barSampleMs;
        barSampleMs = r.updatedMs;
        bar.follow(p.watts, r.intervalMs, fresh, now);
    }

    /**
     * Owner, Nov 6: the live power meter got confused on regen while the ride graphs were right.
     * UT5 MAX polls 0x72 (current magnitude) then 0x79 (its sign); 0x72 lands signed with the
     * previous sign, and 0x79 re-signs it in place. Draw, a brake into regen, and back: after every
     * poll the meter's reading, direction and bar match the recording's voltage x current.
     */
    private static void meterMatchesRecording() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        GaugeSmoother bar = new GaugeSmoother();
        barSampleMs = -1;
        // Amps per 2 s poll: draw, a two-poll brake, draw again. The scooter sends the magnitude and the sign apart.
        double[] amps = {10, 12, 11, -4, -3.5, 9, 10};
        telemetry.accept(0x79, signs(0, 0), 0);
        values.update(telemetry.metrics());
        long t = 2000;
        for (int i = 0; i < amps.length; i++, t += 2000) {
            telemetry.accept(0x72, batteryRead(70, 50000, (int) Math.round(Math.abs(amps[i]) * 1000)), t);
            values.update(telemetry.metrics());
            refreshBar(values, bar, t + 10);
            // Before 0x79 the reading carries the previous sign, in the recording just the same.
            check(near(values.power(t + 10).watts, recordingW(telemetry), 1e-6), "between 0x72 and 0x79 the meter reads what the recording reads, poll " + i);
            telemetry.accept(0x79, signs(0, amps[i] < 0 ? 1 : 0), t + 80);
            values.update(telemetry.metrics());
            refreshBar(values, bar, t + 90);
            double recorded = recordingW(telemetry), expected = 50 * amps[i];
            RidingValues.Power p = values.power(t + 90);
            check(near(recorded, expected, 1e-6), "the recording has V x A signed by 0x79, poll " + i + ": " + recorded);
            check(near(p.watts, recorded, 1e-6), "the meter shows the recording's watts, poll " + i + ": " + p.watts + " vs " + recorded);
            check(p.direction == (recorded < 0 ? RidingValues.Direction.REGEN : RidingValues.Direction.DRAW), "negative is regen, as in the recording, poll " + i + ": " + p.direction);
            check(bar.target() == p.watts && Math.signum(bar.value(t + 106)) == Math.signum(recorded),
                    "the bar is on the recording's side one frame after 0x79, poll " + i + ": " + bar.value(t + 106));
            for (long f = t + 90; f < t + 2000; f += 16)
                check(Math.signum(bar.value(f)) != -Math.signum(recorded), "the bar never shows the other direction while it glides, poll " + i + " at " + f);
        }
        check(near(values.power(t).peakRegenW, 200, 1e-6) && near(values.power(t).peakDrawW, 600, 1e-6), "peaks from the corrected readings");
    }

    /**
     * No sign yet (riding mode opened before the first 0x79): the recording can't tell either, and
     * keeps the unsigned magnitude; the meter shows that magnitude as unknown (grey), never as draw
     * or regen. The first sign re-signs that same reading, and the meter and bar follow at once.
     */
    private static void unknownUntilSign() {
        Telemetry telemetry = new Telemetry();
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        GaugeSmoother bar = new GaugeSmoother();
        barSampleMs = -1;
        telemetry.accept(0x72, batteryRead(70, 50000, 4000), 1000);
        values.update(telemetry.metrics());
        refreshBar(values, bar, 1010);
        RidingValues.Power p = values.power(1010);
        check(near(recordingW(telemetry), 200, 1e-6), "the recording holds the magnitude before any sign");
        check(p.direction == RidingValues.Direction.UNKNOWN && near(p.watts, 200, 1e-6) && p.peakDrawW == 0 && p.peakRegenW == 0,
                "the meter shows it as unknown, claiming neither draw nor regen: " + p.direction);
        telemetry.accept(0x79, signs(0, 1), 1080);
        values.update(telemetry.metrics());
        refreshBar(values, bar, 1090);
        p = values.power(1090);
        check(near(recordingW(telemetry), -200, 1e-6) && near(p.watts, -200, 1e-6) && p.direction == RidingValues.Direction.REGEN,
                "the first sign makes it regen in both: " + p.watts + " " + p.direction);
        check(bar.target() == -200 && bar.value(1106) < 0, "and the bar moves to regen at once: " + bar.value(1106));
    }

    private static void timers() {
        RidingValues values = new RidingValues();
        check(values.rideTimeMs(0) == null && values.recordingTimeMs(0) == null, "no timers before data");
        values.sample("trip_minutes", 12, 0x92, 100_000);
        long first = values.rideTimeMs(100_000);
        check(first >= 12 * 60_000L && first < 13 * 60_000L, "ride time is anchored from the scooter's trip minutes: " + first);
        values.sample("trip_minutes", 12, 0x92, 110_000);
        values.sample("trip_minutes", 13, 0x92, 130_000);
        check(near(values.rideTimeMs(130_000), 13 * 60_000L, 2000), "the minute ticking over refines it: " + values.rideTimeMs(130_000));
        check(values.rideTimeMs(131_000) - values.rideTimeMs(130_000) == 1000, "and it ticks every second between reports");
        values.sample("trip_minutes", 0, 0x92, 200_000);
        check(values.rideTimeMs(200_000) < 60_000, "a new trip (scooter powered on) re-anchors");
        values.setRecordingStart(150_000L);
        check(values.recordingTimeMs(200_000) == 50_000 && values.rideTimeMs(200_000) != 50_000, "recording time is its own timer and may differ from ride time");
        values.sample("trip_km", 3.7, 0x92, 200_000);
        RidingValues.Trip trip = values.trip(1.2, 200_000);
        check(trip.fromRecording && trip.km == 1.2, "while recording, the trip spot shows the recording's distance");
        values.setRecordingStart(null);
        trip = values.trip(1.2, 200_000);
        check(!trip.fromRecording && near(trip.km, 3.7, 1e-9) && values.recordingTimeMs(200_000) == null, "otherwise the scooter's trip");
    }

    /**
     * Owner, Nov 7: ride time froze at 2:00 or 3:00. The scooter's whole-minute trip counter can stand still
     * (a stop early in a trip) while its reports keep coming every second; ride time kept counting past it
     * and then pinned at the next whole minute. It must keep counting and never run backwards.
     */
    private static void rideTimeNeverFreezes() {
        RidingValues values = new RidingValues();
        long t = 1_000_000;
        values.sample("trip_minutes", 2, 0x92, t);
        long previous = values.rideTimeMs(t);
        check(previous == 2 * 60_000L, "anchored at the scooter's whole minutes: " + previous);
        for (int s = 1; s <= 300; s++) {
            long now = t + s * 1000L;
            values.sample("trip_minutes", 2, 0x92, now);
            long shown = values.rideTimeMs(now);
            check(shown - previous == 1000, "ride time keeps counting while the scooter's minute stands still, at "
                    + s + " s: " + previous + " -> " + shown);
            previous = shown;
        }
        // The scooter's minute moves on again: ride time doesn't jump back to it.
        long now = t + 301_000;
        values.sample("trip_minutes", 3, 0x92, now);
        check(values.rideTimeMs(now) - previous == 1000, "and doesn't snap back when the minute ticks over: " + values.rideTimeMs(now));
        // A scooter minute ahead of ours still pulls ride time forward.
        now += 1000;
        values.sample("trip_minutes", 9, 0x92, now);
        check(values.rideTimeMs(now) == 9 * 60_000L, "a scooter count ahead of ours moves it forward: " + values.rideTimeMs(now));
        // Power cycled: the trip starts over.
        now += 1000;
        values.sample("trip_minutes", 0, 0x92, now);
        check(values.rideTimeMs(now) == 0, "a lower count is a new trip: " + values.rideTimeMs(now));
        // A long silence and then a higher count: a different trip, not hours of this one.
        values.sample("trip_minutes", 1, 0x92, now + 1000);
        now += 2 * 3_600_000L;
        values.sample("trip_minutes", 4, 0x92, now);
        check(values.rideTimeMs(now) == 4 * 60_000L, "after a long gap the scooter's count re-anchors: " + values.rideTimeMs(now));
    }

    /** Leaving riding mode and coming back keeps the count instead of snapping back to the scooter's whole minute. */
    private static void rideTimeAcrossReopen() {
        RidingValues first = new RidingValues();
        long t = 5_000_000;
        for (int s = 0; s <= 150; s++) first.sample("trip_minutes", 2, 0x92, t + s * 1000L);
        long before = first.rideTimeMs(t + 150_000);
        RidingValues.TripClock clock = first.tripClock();
        check(clock != null, "a trip clock to carry over");
        RidingValues reopened = new RidingValues();
        reopened.resumeTrip(clock);
        long now = t + 160_000;
        check(reopened.rideTimeMs(now) == null, "nothing shows before this session's first trip report");
        reopened.sample("trip_minutes", 2, 0x92, now);
        check(reopened.rideTimeMs(now) == before + 10_000, "reopened, ride time carries on: " + before
                + " then " + reopened.rideTimeMs(now));
        RidingValues newTrip = new RidingValues();
        newTrip.resumeTrip(clock);
        newTrip.sample("trip_minutes", 0, 0x92, now);
        check(newTrip.rideTimeMs(now) == 0, "a new trip since then starts over");
        RidingValues later = new RidingValues();
        later.resumeTrip(clock);
        now = t + 3_600_000L;
        later.sample("trip_minutes", 7, 0x92, now);
        check(later.rideTimeMs(now) == 7 * 60_000L, "a carried clock that's long out of date is dropped");
        check(new RidingValues().tripClock() == null, "no clock before a trip report");
        new RidingValues().resumeTrip(null);
    }

    /** One ride report's trip fields at one time; minutesFirst sets the order they land in. */
    private static void report(RidingValues v, double minutes, double km, double max, long t, boolean minutesFirst) {
        report(v, minutes, km, max, t, minutesFirst, 0x92);
    }
    private static void report(RidingValues v, double minutes, double km, double max, long t, boolean minutesFirst, int type) {
        if (minutesFirst) v.sample("trip_minutes", minutes, type, t);
        v.sample("trip_km", km, type, t);
        v.sample("maximum_speed_kmh", max, type, t);
        if (!minutesFirst) v.sample("trip_minutes", minutes, type, t);
    }

    /**
     * Codex #319: a power cycle can show the same minute as before (0 while the old trip's counter stood
     * still), so the minute alone can't tell a restart from a reconnect. The trip's distance and top speed
     * start over at 0 on a power cycle; a dropped link that comes back keeps them.
     */
    private static void tripRestartEvidence() {
        for (boolean minutesFirst : new boolean[]{true, false}) {
            String order = minutesFirst ? " (minutes first)" : " (distance first)";
            // Live: three minutes at a standing-still 0, 0.4 km and 12 km/h top speed so far.
            RidingValues live = new RidingValues();
            long t = 2_000_000;
            for (int s = 0; s <= 180; s++) report(live, 0, 0.4, 12, t + s * 1000L, minutesFirst);
            long now = t + 195_000;
            report(live, 0, 0, 0, now, minutesFirst);
            check(live.rideTimeMs(now) == 0, "a same-minute power cycle with the trip reset starts over" + order + ": " + live.rideTimeMs(now));
            report(live, 0, 0, 0, now + 1000, minutesFirst);
            check(live.rideTimeMs(now + 1000) == 1000, "and counts the new trip from there" + order);

            RidingValues link = new RidingValues();
            for (int s = 0; s <= 180; s++) report(link, 0, 0.4, 12, t + s * 1000L, minutesFirst);
            report(link, 0, 0.4, 12, now, minutesFirst);
            check(link.rideTimeMs(now) == 195_000, "a dropped link that comes back without a reset carries on" + order + ": " + link.rideTimeMs(now));

            // Reopened riding mode with the saved anchor.
            RidingValues.TripClock clock = link.tripClock();
            RidingValues restarted = new RidingValues();
            restarted.resumeTrip(clock);
            long later = now + 15_000;
            report(restarted, 0, 0, 0, later, minutesFirst);
            check(restarted.rideTimeMs(later) == 0, "reopened after a same-minute power cycle starts over" + order + ": " + restarted.rideTimeMs(later));
            RidingValues reconnected = new RidingValues();
            reconnected.resumeTrip(clock);
            report(reconnected, 0, 0.5, 12, later, minutesFirst);
            check(reconnected.rideTimeMs(later) == 210_000, "reopened after a reconnect without a reset carries on" + order + ": " + reconnected.rideTimeMs(later));

            // Codex #319: a whole-unit (0x91) report shows 0.4 km as 0. Same trip, top speed unchanged: carries on.
            RidingValues formats = new RidingValues();
            for (int s = 0; s <= 180; s++) report(formats, 0, 0.4, 12, t + s * 1000L, minutesFirst);
            report(formats, 0, 0, 12, now, minutesFirst, 0x91);
            check(formats.rideTimeMs(now) == 195_000, "0.4 km in tenths then 0 in whole units is the same trip" + order + ": " + formats.rideTimeMs(now));
            RidingValues reopenedFormats = new RidingValues();
            reopenedFormats.resumeTrip(clock);
            report(reopenedFormats, 0, 0, 12, later, minutesFirst, 0x91);
            check(reopenedFormats.rideTimeMs(later) == 210_000, "and after reopening" + order + ": " + reopenedFormats.rideTimeMs(later));
            // A real reset in whole units: the top speed falls from 12 to 0.
            RidingValues formatsReset = new RidingValues();
            for (int s = 0; s <= 180; s++) report(formatsReset, 0, 0.4, 12, t + s * 1000L, minutesFirst);
            report(formatsReset, 0, 0, 0, now, minutesFirst, 0x91);
            check(formatsReset.rideTimeMs(now) == 0, "a whole-unit report of a real reset still starts over" + order + ": " + formatsReset.rideTimeMs(now));
            // Distance alone is evidence once it falls by a whole kilometre.
            RidingValues farther = new RidingValues();
            for (int s = 0; s <= 180; s++) report(farther, 0, 1.4, 12, t + s * 1000L, minutesFirst);
            report(farther, 0, 0, 12, now, minutesFirst, 0x91);
            check(farther.rideTimeMs(now) == 0, "a distance drop of a whole kilometre starts over" + order + ": " + farther.rideTimeMs(now));
        }
        // Whole-unit (v0) reports: a small rounding wobble is not a reset.
        RidingValues v0 = new RidingValues();
        report(v0, 1, 3.7, 27.9, 0, true);
        report(v0, 1, 3, 27, 1000, true);
        check(v0.rideTimeMs(1000) == 61_000, "a rounding drop under a whole unit keeps the trip: " + v0.rideTimeMs(1000));
        // Documented ambiguity: a power cycle before the old trip moved (under 1 km and 1 km/h) carries on.
        RidingValues still = new RidingValues();
        for (int s = 0; s <= 60; s++) report(still, 0, 0, 0, s * 1000L, true);
        report(still, 0, 0, 0, 75_000, true);
        check(still.rideTimeMs(75_000) == 75_000, "a restart that looks identical can't be told apart (documented)");
    }

    private static void battery() {
        check(RidingValues.band(30) == RidingValues.Band.GREEN && RidingValues.band(29.9) == RidingValues.Band.AMBER, "amber below 30 % (owner)");
        check(RidingValues.band(15) == RidingValues.Band.AMBER && RidingValues.band(14) == RidingValues.Band.RED && RidingValues.band(0) == RidingValues.Band.RED, "red below 15 % (owner)");
        RidingValues values = new RidingValues();
        check(values.batteryBand(0) == null, "no band before a reading");
        values.sample("battery_percent", 22, 0x91, 0);
        check(values.batteryBand(0) == RidingValues.Band.AMBER, "the reading's band");
    }

    private static void ranges() {
        RidingValues values = new RidingValues();
        RidingValues.Range pace = values.range(RidingLayout.RANGE_AT_PACE, 0);
        check(pace.km == null && RidingValues.Range.PLACEHOLDER.equals(pace.source), "Range at this pace is a placeholder until its estimator lands");
        check(values.range(RidingLayout.RANGE_SCOOTER, 0).km == null, "no scooter range before a report");
        values.sample("remaining_range_km", 38, 0x92, 1000);
        RidingValues.Range scooter = values.range(RidingLayout.RANGE_SCOOTER, 1500);
        check(scooter.km == 38 && RidingValues.Range.SCOOTER.equals(scooter.source) && scooter.confidence == 1, "the scooter's own estimate");
        check(values.range(RidingLayout.RANGE_SCOOTER, 60_000).confidence == 0, "a stale scooter estimate has no confidence");
        check(values.range(RidingLayout.RANGE_VOLT, 0).km == null, "Volt's estimate needs its source plugged in");
        values.setRangeSource(RidingLayout.RANGE_VOLT, now -> new RidingValues.Range(41.5, 0.7, RidingValues.Range.VOLT));
        values.setRangeSource(RidingLayout.RANGE_AT_PACE, now -> new RidingValues.Range(23.6, 2, RidingValues.Range.PACE));
        check(values.range(RidingLayout.RANGE_VOLT, 0).km == 41.5 && values.range(RidingLayout.RANGE_AT_PACE, 0).confidence == 1, "sources plug in, with confidence clamped to 0..1");
        values.setRangeSource(RidingLayout.SPEED, now -> null);
        check(values.range(RidingLayout.SPEED, 0) == null, "only range contents take a range source");
    }
}
