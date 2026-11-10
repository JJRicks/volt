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
        unsignedCurrent();
        inferenceKeepsANewConnectionsFirstReadings();
        screenPassesTheConnectionsTrueStart();
        inferenceStopsAtANewConnection();
        inferredDraw();
        inferredThreshold();
        inferredWindow();
        inferredFromFrames();
        inferredRegen();
        inferredRegenUsesOnlyEarlierSpeed();
        inferredRegenCorrectedToDraw();
        signedNeverInfers();
        inferredThenStrongerRegen();
        meterMatchesRecording();
        unknownUntilSign();
        timers();
        rideTimeFromOpen();
        distanceFromOpen();
        distanceSurvivesCounterChanges();
        distanceAcrossReportPrecision();
        distanceWhileRecording();
        distanceWhileThePhoneLooksAway();
        reopenAndRecreate();
        battery();
        ranges();
        System.out.println("RidingValuesTest: " + checks + " checks passed (0x91/0x92, sign, sign corrections, shared coverage, direction history, unknown after regen, inferred draw, meter vs recording, unknown until sign, staleness, interval EMA, coverage, energy, scale, trace gaps, timers, ride time and distance from each open, whole-km to tenths, unseen distance across a counter switch, reopen vs recreation, bands, ranges)");
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
        check(power.direction == RidingValues.Direction.DRAW && power.watts != null && near(power.watts, 180, 0.01), "with no sign state (UT5 MAX) current is signed, as in recordings: draw");
        check(power.peakRegenW == 0 && near(power.peakDrawW, 180, 0.01), "and it counts as draw in the peak");
        RidingValues noSignState = new RidingValues();
        noSignState.sample("power_w", -240, 0x90, 1000);
        check(noSignState.power(1100).direction == RidingValues.Direction.REGEN && near(noSignState.power(1100).watts, -240, 0.01)
                && near(noSignState.power(1100).peakRegenW, 240, 0.01), "and a negative reading is regen, not unknown");
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
        gap.sample("current_sign_state", 254, 0x79, 0);
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
        values.sample("current_sign_state", 254, 0x79, 149_000);
        values.sample("power_w", 200, 0x72, 150_000);
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
        late.sample("current_sign_state", 254, 0x79, 0);
        for (long t = 0; t <= 40_000; t += 1000) { late.sample("speed_kmh", 20, 0x92, t); if (t % 2000 == 0) late.sample("power_w", 400, 0x72, t); }
        check(!late.efficiency(40_000).directionConfirmed, "a sign state that isn't 0 or 1: unconfirmed");
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

    /** The GT3 Max's unsigned current stays unknown; a negative reading proves a signed one (PowerSign). */
    /**
     * Ride mode's view of a short Bluetooth drop: a new connection between two readings means they
     * can't be compared, even when the drop is shorter than the staleness gap. A pre-drop speed
     * paired with a new power reading isn't either.
     */
    private static void inferenceStopsAtANewConnection() {
        for (boolean reconnect : new boolean[]{false, true}) {
            RidingValues v = new RidingValues();
            v.setNominalIntervals(1000, 2000);
            v.sample("current_sign_state", 254, 0x79, 0);
            v.setLinkGeneration(7, 0);
            unsignedBrake(v, 0);
            long t = 28_000;
            double volts = 50.24;
            if (reconnect) v.setLinkGeneration(8, 28_000);
            t += 1000; // one second missed
            v.sample("voltage_v", volts, 0x72, t); v.sample("speed_kmh", 9, 0x92, t); v.sample("power_w", 3 * volts, 0x72, t);
            RidingValues.Direction direction = v.power(t + 100).direction;
            if (reconnect) check(direction != RidingValues.Direction.REGEN_INFERRED, "the speed before a new connection isn't a slowing: " + direction);
            else check(direction == RidingValues.Direction.REGEN_INFERRED, "the same readings on one connection are: " + direction);
        }
        RidingValues first = new RidingValues();
        first.setLinkGeneration(3, 100);
        first.setLinkGeneration(3, 200);
        first.setNominalIntervals(1000, 2000);
        first.sample("current_sign_state", 254, 0x79, 0);
        unsignedBrake(first, 1000);
        check(first.power(29_000).direction == RidingValues.Direction.REGEN_INFERRED, "learning the first connection, and seeing it again, is no interruption");
    }

    /**
     * A snapshot can hold readings from a new connection before any screen refresh sees that
     * connection: the floor is where the connection began, not when it was first noticed.
     */
    private static void inferenceKeepsANewConnectionsFirstReadings() {
        for (boolean screenTime : new boolean[]{false, true}) {
            RidingValues v = new RidingValues();
            v.setNominalIntervals(1000, 2000);
            v.sample("current_sign_state", 254, 0x79, 0);
            v.setLinkGeneration(7, 0);
            unsignedBrake(v, 0);
            long reconnect = 30_000, firstReading = reconnect + 500, refresh = reconnect + 1000, brake = reconnect + 1500;
            double volts = 50.64;
            // The reconnect and its first reading both land before the screen's next refresh.
            v.setLinkGeneration(8, screenTime ? refresh : reconnect);
            v.sample("voltage_v", volts, 0x72, firstReading); v.sample("speed_kmh", 19, 0x92, firstReading); v.sample("power_w", 8 * volts, 0x72, firstReading);
            v.sample("voltage_v", volts, 0x72, brake); v.sample("speed_kmh", 15.5, 0x92, brake); v.sample("power_w", 8 * volts, 0x72, brake);
            RidingValues.Direction direction = v.power(brake + 100).direction;
            if (screenTime) check(direction != RidingValues.Direction.REGEN_INFERRED, "a floor at the screen's refresh loses the new connection's first reading: " + direction);
            else check(direction == RidingValues.Direction.REGEN_INFERRED, "the new connection's first reading counts toward the next one: " + direction);
        }
        // The old link's speed in that same snapshot still can't pair with a new power reading.
        RidingValues old = new RidingValues();
        old.setNominalIntervals(1000, 2000);
        old.sample("current_sign_state", 254, 0x79, 0);
        old.setLinkGeneration(7, 0);
        unsignedBrake(old, 0);
        long reconnect = 30_000, firstReading = reconnect + 500, brake = reconnect + 1500;
        old.sample("speed_kmh", 19, 0x92, reconnect - 100);
        old.setLinkGeneration(8, reconnect);
        old.sample("voltage_v", 50.64, 0x72, firstReading); old.sample("power_w", 8 * 50.64, 0x72, firstReading);
        old.sample("voltage_v", 50.64, 0x72, brake); old.sample("power_w", 8 * 50.64, 0x72, brake);
        check(old.power(brake + 100).direction != RidingValues.Direction.REGEN_INFERRED, "the old connection's speed isn't compared with a new reading");
    }
    /** The floor above is only as good as the call: the screen must hand over the connection's own start. */
    private static void screenPassesTheConnectionsTrueStart() {
        try {
            String screen = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/RidingScreen.java")), java.nio.charset.StandardCharsets.UTF_8);
            check(screen.contains("setLinkGeneration(ble.connectionGeneration(), ble.connectionStartMs())"), "RidingScreen hands over the connection's own start");
            String controller = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/BleController.java")), java.nio.charset.StandardCharsets.UTF_8);
            check(controller.contains("++generation;generationStartMs=now();"), "BleController dates each new generation");
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private static void unsignedCurrent() {
        RidingValues gt3 = new RidingValues();
        gt3.setNominalIntervals(1000, 2000);
        gt3.sample("current_sign_state", 254, 0x79, 0);
        gt3.sample("power_w", 400, 0x72, 1000);
        check(gt3.power(1100).direction == RidingValues.Direction.UNKNOWN && gt3.power(1100).peakRegenW == 0, "254 with positive current stays unknown, no regen claimed");
        gt3.sample("power_w", 1500, 0x72, 3000);
        check(gt3.power(3100).direction == RidingValues.Direction.DRAW_INFERRED, "and strong readings are still inferred draw");
        check(gt3.signState() == RidingValues.Direction.UNKNOWN, "the scooter stays unsigned");
        gt3.sample("power_w", -300, 0x72, 5000);
        check(gt3.power(5100).direction == RidingValues.Direction.REGEN && near(gt3.power(5100).peakRegenW, 300, 0.01), "a negative current proves the scooter signs it: regen");
        check(gt3.signState() != RidingValues.Direction.UNKNOWN, "and from then on it is signed");
    }

    /** Feeds an unsigned ride: rest, acceleration, cruise, then a brake where the voltage rises. */
    private static void unsignedBrake(RidingValues v, long t0) {
        double rest = 50, ohms = 0.08, kmh = 0;
        long t = t0;
        for (int i = 0; i < 8; i++) { v.sample("voltage_v", rest, 0x72, t); v.sample("speed_kmh", 0, 0x92, t); v.sample("power_w", 10, 0x72, t); t += 1000; }
        for (int i = 0; i < 8; i++) { kmh += 3; double volts = rest - 18 * ohms; v.sample("voltage_v", volts, 0x72, t); v.sample("speed_kmh", kmh, 0x92, t); v.sample("power_w", 18 * volts, 0x72, t); t += 1000; }
        for (int i = 0; i < 8; i++) { v.sample("voltage_v", rest, 0x72, t); v.sample("speed_kmh", kmh, 0x92, t); v.sample("power_w", 15, 0x72, t); t += 1000; }
        for (int i = 0; i < 4; i++) { kmh -= 3.2; double volts = rest + 8 * ohms; v.sample("voltage_v", volts, 0x72, t); v.sample("speed_kmh", kmh, 0x92, t); v.sample("power_w", 8 * volts, 0x72, t); t += 1000; }
    }

    /** An unsigned brake reads as inferred regen everywhere a screen looks, but never moves the energy (owner, Nov 9). */
    private static void inferredRegen() {
        RidingValues v = new RidingValues();
        v.setNominalIntervals(1000, 2000);
        v.sample("current_sign_state", 254, 0x79, 0);
        unsignedBrake(v, 0);
        long now = 27_500;
        RidingValues.Power p = v.power(now);
        check(p.direction == RidingValues.Direction.REGEN_INFERRED, "the brake is inferred regen: " + p.direction);
        check(p.watts != null && p.watts < 0 && p.direction.regen(), "and shown on the regen side");
        check(p.countedWatts > 0 && near(p.countedWatts, -p.watts, 1e-9), "range still gets the magnitude");
        check(p.peakRegenW > 0, "the inferred regen peak is there for the caption");
        check(v.inferredDrawThresholdW() == 1200, "and an inference never raises the threshold");
        check(!p.direction.knownOrInferred() && p.direction.unsigned(), "for energy and efficiency it counts like an unknown reading");
        check(v.direction() == RidingValues.Direction.REGEN_INFERRED, "the screen can tell it from confirmed regen");
        boolean negative = false;
        for (RidingValues.TracePoint point : v.powerTrace(now, 60_000)) {
            negative |= point.value < 0;
            check(!point.directionUnknown || point.value >= 0, "unknown points stay magnitudes");
            check(point.value >= 0 || point.directionUnconfirmed, "a trace point below zero that was only inferred isn't drawn as confirmed regen");
        }
        check(negative, "the live trace goes below zero");
        RidingValues compare = new RidingValues();
        compare.setNominalIntervals(1000, 2000);
        compare.sample("current_sign_state", 254, 0x79, 0);
        for (long t = 0; t < 28_000; t += 1000) compare.sample("power_w", v.powerTrace(now, 60_000).get((int) (t / 1000)).value < 0 ? -v.powerTrace(now, 60_000).get((int) (t / 1000)).value : v.powerTrace(now, 60_000).get((int) (t / 1000)).value, 0x72, t);
        check(near(v.rideEnergyWh(), compare.rideEnergyWh(), 0.01), "energy is the sum of magnitudes, as before: " + v.rideEnergyWh() + " vs " + compare.rideEnergyWh());
        check(v.inferredDrawWh() == 0 && near(v.unconfirmedEnergyWh(), v.rideEnergyWh(), 1e-6), "and all of it unconfirmed, none inferred draw");
    }

    /** A refresh can bring a later speed before the power reading it follows: only a speed from before counts. */
    private static void inferredRegenUsesOnlyEarlierSpeed() {
        for (boolean laterSpeed : new boolean[]{false, true}) {
            RidingValues v = new RidingValues();
            v.setNominalIntervals(1000, 2000);
            v.sample("current_sign_state", 254, 0x79, 0);
            long t = 0;
            for (int i = 0; i < 8; i++) { v.sample("voltage_v", 50, 0x72, t); v.sample("speed_kmh", 30, 0x92, t); v.sample("power_w", 10, 0x72, t); t += 1000; }
            v.sample("voltage_v", 50.4, 0x72, t);
            if (laterSpeed) v.sample("speed_kmh", 20, 0x92, t + 1000);
            v.sample("power_w", 150, 0x72, t);
            check(v.power(t + 100).direction == RidingValues.Direction.UNKNOWN, "the latest speed before it was steady, so this stays unknown (later speed " + laterSpeed + "): " + v.power(t + 100).direction);
        }
        RidingValues v = new RidingValues();
        v.setNominalIntervals(1000, 2000);
        v.sample("current_sign_state", 254, 0x79, 0);
        unsignedBrake(v, 0);
        long t = 40_000; // no speed from the last 3 seconds, only a later one
        v.sample("voltage_v", 50.6, 0x72, t);
        v.sample("speed_kmh", 5, 0x92, t + 1000);
        v.sample("power_w", 150, 0x72, t);
        check(v.power(t + 100).direction == RidingValues.Direction.UNKNOWN, "with no speed from before it, the direction stays unknown: " + v.power(t + 100).direction);
    }

    /** A same-time sign correction takes back the sample's inferred regen peak with everything else. */
    private static void inferredRegenCorrectedToDraw() {
        RidingValues v = new RidingValues();
        v.setNominalIntervals(1000, 2000);
        v.sample("current_sign_state", 254, 0x79, 0);
        unsignedBrake(v, 0);
        double before = v.power(27_500).peakRegenW;
        check(before > 0, "the brake left an inferred peak: " + before);
        long t = 28_000;
        double volts = 50.72, watts = 9 * volts;
        v.sample("voltage_v", volts, 0x72, t);
        v.sample("speed_kmh", 8, 0x92, t);
        v.sample("power_w", watts, 0x72, t);
        check(v.power(t + 100).direction == RidingValues.Direction.REGEN_INFERRED && near(v.power(t + 100).peakRegenW, watts, 1e-6), "a stronger inferred sample raises the peak: " + v.power(t + 100).peakRegenW);
        v.sample("current_sign_state", 0, 0x79, t + 200);
        v.sample("power_w", watts, 0x72, t);
        RidingValues.Power corrected = v.power(t + 300);
        check(corrected.direction == RidingValues.Direction.DRAW && near(corrected.watts, watts, 1e-9), "the correction made it confirmed draw: " + corrected.direction);
        check(near(corrected.peakRegenW, before, 1e-9), "and took its inferred peak back: " + corrected.peakRegenW + " vs " + before);
    }

    /** A signed scooter reads exactly as before: no inference runs, whatever it does. */
    private static void signedNeverInfers() {
        RidingValues v = new RidingValues();
        v.setNominalIntervals(1000, 2000);
        v.sample("current_sign_state", 0, 0x79, 0);
        unsignedBrake(v, 0);
        RidingValues.Power p = v.power(27_500);
        check(p.direction == RidingValues.Direction.DRAW && p.watts > 0, "a signed scooter's positive reading stays draw: " + p.direction);
        for (RidingValues.TracePoint point : v.powerTrace(27_500, 60_000)) check(point.value >= 0, "and nothing goes below zero");
        check(p.peakRegenW == 0, "no regen peak appears");
    }

    /** A strong reading without a known sign is inferred draw; a weaker one stays unknown (owner, Nov 6). */
    private static void inferredDraw() {
        RidingValues values = new RidingValues();
        values.setNominalIntervals(1000, 2000);
        check(values.inferredDrawThresholdW() == 1200, "the threshold starts at 1,200 W");
        values.sample("current_sign_state", 254, 0x79, 0);
        for (long t = 0; t <= 10_000; t += 2000) values.sample("power_w", 1500, 0x72, t);
        RidingValues.Power power = values.power(10_000);
        check(power.direction == RidingValues.Direction.DRAW_INFERRED && near(power.watts, 1500, 1e-9), "unknown at 1,500 W is inferred draw: " + power.direction);
        check(power.peakDrawW == 1500 && values.scale().drawW == 1500, "it sets the draw peak and the bar scale");
        check(near(values.rideEnergyWh(), 1500 * 10 / 3600.0, 1e-9) && near(values.inferredDrawWh(), values.rideEnergyWh(), 1e-9), "it counts as draw, reported as inferred");
        check(values.unconfirmedEnergyWh() == 0, "and not as unconfirmed");
        check(values.powerTrace(10_000, 60_000).get(0).value == 1500, "its trace point is positive");

        RidingValues weak = new RidingValues();
        weak.setNominalIntervals(1000, 2000);
        // A sign that went to 254: Telemetry keeps its stale sign, so negatives here are not evidence.
        weak.sample("current_sign_state", 1, 0x79, 0);
        weak.sample("current_sign_state", 254, 0x79, 1);
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
        corrected.sample("current_sign_state", 254, 0x79, 0);
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
     * No sign state yet (riding mode opened before the first 0x79, or a scooter like the UT5 MAX
     * that never sends one): the recording treats positive as draw, and the meter now does the
     * same (owner, Nov 8). A first sign re-signs that same reading, and meter and bar follow at once.
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
        check(near(recordingW(telemetry), 200, 1e-6), "the recording holds the positive reading before any sign");
        check(p.direction == RidingValues.Direction.DRAW && near(p.watts, 200, 1e-6) && p.peakRegenW == 0,
                "the meter shows it as the recording does, as draw: " + p.direction);
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
        check(values.recordingTimeMs(0) == null, "no recording time before a recording");
        values.setRecordingStart(150_000L);
        check(values.recordingTimeMs(200_000) == 50_000, "recording time is its own timer");
        values.setRecordingStart(null);
        check(values.recordingTimeMs(200_000) == null, "and stops with the recording");
    }

    private static final String PROFILE = "p1";
    private static RidingSession open(long nowMs) { return new RidingSession(PROFILE, null, nowMs); }
    private static void feed(RidingSession session, long nowMs) { session.feed(Collections.emptyList(), nowMs, false, "km", true); }
    /** A trip report arriving now, then the screen's refresh. */
    private static void trip(RidingSession session, double km, long nowMs) { trip(session, km, 0x92, nowMs); }
    private static void trip(RidingSession session, double km, int type, long nowMs) {
        session.values.sample("trip_km", km, type, nowMs);
        feed(session, nowMs);
    }

    /** Ride time is the phone's clock since this open: the scooter's trip minutes no longer touch it (owner, Nov 8). */
    private static void rideTimeFromOpen() {
        long t = 5_000_000;
        RidingSession session = open(t);
        check(session.rideTimeMs(t) == 0, "ride time starts at zero when riding mode opens");
        session.values.sample("trip_minutes", 23, 0x92, t);
        feed(session, t);
        check(session.rideTimeMs(t) == 0, "even with the scooter's trip already 23 minutes old: " + session.rideTimeMs(t));
        for (int s = 1; s <= 300; s++) {
            long now = t + s * 1000L;
            session.values.sample("trip_minutes", 23, 0x92, now);
            feed(session, now);
            check(session.rideTimeMs(now) == s * 1000L, "counts every second while the scooter's minute stands still, at " + s + " s");
        }
        long now = t + 301_000;
        session.values.sample("trip_minutes", 2, 0x92, now);
        feed(session, now);
        check(session.rideTimeMs(now) == 301_000, "a scooter trip restarting mid-session doesn't move it");
        check(open(t).rideTimeMs(t + 7_000) == 7_000, "and it needs no reading at all");
        check(open(t).rideTimeMs(t - 5) == 0, "never negative");
    }

    private static void distanceFromOpen() {
        long t = 5_000_000;
        RidingSession session = open(t);
        check(session.trip().km == null, "no distance before a reading");
        trip(session, 12.4, t + 1000);
        check(session.trip().km == 0 && !session.trip().fromRecording, "distance starts at 0 with the scooter's trip already at 12.4 km");
        trip(session, 12.9, t + 2000);
        check(near(session.trip().km, 0.5, 1e-9), "and counts what's ridden since: " + session.trip().km);

        // The reading left in Telemetry from before this open.
        RidingSession late = open(t);
        late.values.sample("trip_km", 3, 0x92, t - 60_000);
        feed(late, t);
        check(late.trip().km == null, "a reading left over from a minute before isn't a baseline");
        trip(late, 8.2, t + 1000);
        check(late.trip().km == 0, "the first reading after opening is: " + late.trip().km);
        RidingSession live = open(t);
        live.values.sample("trip_km", 3, 0x92, t - 800);
        feed(live, t);
        check(live.trip().km != null && live.trip().km == 0, "a report from a moment ago (a scooter already connected) shows 0 at once");
    }

    /** The scooter's counter restarting, or a coarser report rounding it down, never takes distance back. */
    private static void distanceSurvivesCounterChanges() {
        long t = 5_000_000;
        RidingSession session = open(t);
        trip(session, 10, t);
        trip(session, 12, t + 1000);
        check(near(session.trip().km, 2, 1e-9), "2 km ridden");
        double shown = session.trip().km;
        trip(session, 0, t + 2000);
        check(session.trip().km >= 0 && near(session.trip().km, 2, 1e-9), "a trip counter that restarts keeps what was ridden, never negative: " + session.trip().km);
        for (int i = 1; i <= 8; i++) {
            trip(session, i * 0.1, t + 2000 + i * 1000L);
            check(session.trip().km >= shown, "and never goes backwards: " + session.trip().km);
            shown = session.trip().km;
        }
        check(near(shown, 2.8, 1e-9), "then adds the new trip: " + shown);

        // A whole-unit report rounds down the tenths one before it (0x91 after 0x92).
        RidingSession rounding = open(t);
        trip(rounding, 3.7, t);
        trip(rounding, 3.9, 0x92, t + 1000);
        check(near(rounding.trip().km, 0.2, 1e-9), "0.2 km ridden");
        trip(rounding, 3, 0x91, t + 2000);
        trip(rounding, 3.9, 0x92, t + 3000);
        check(near(rounding.trip().km, 0.2, 1e-9), "a rounded report neither subtracts nor counts twice: " + rounding.trip().km);
        trip(rounding, 4.1, 0x92, t + 4000);
        check(near(rounding.trip().km, 0.4, 1e-9), "and the count carries on: " + rounding.trip().km);
    }

    /** A real report through Telemetry and into the session, as the screen's snapshot delivers it. */
    private static void report(Telemetry telemetry, RidingSession session, int type, byte[] data, long nowMs) {
        telemetry.accept(type, data, nowMs);
        session.feed(telemetry.metrics(), nowMs, false, "km", true);
    }

    /**
     * A whole-km report (0x91) says the trip is somewhere in that kilometre, so the tenths that follow it
     * for the same trip aren't distance ridden (Codex #330), in either order, and riding afterwards counts.
     */
    private static void distanceAcrossReportPrecision() {
        long t = 5_000_000;
        Telemetry telemetry = new Telemetry();
        RidingSession wholeFirst = open(t);
        report(telemetry, wholeFirst, 0x91, whole(0, 3, 12), t);
        check(wholeFirst.trip().km == 0, "a whole-km baseline shows 0: " + wholeFirst.trip().km);
        report(telemetry, wholeFirst, 0x92, expanded(0, 37, 12), t + 1000);
        check(wholeFirst.trip().km == 0, "the tenths of that same trip aren't ridden distance: " + wholeFirst.trip().km);
        report(telemetry, wholeFirst, 0x92, expanded(180, 39, 12), t + 2000);
        check(near(wholeFirst.trip().km, 0.2, 1e-9), "riding after them counts: " + wholeFirst.trip().km);
        report(telemetry, wholeFirst, 0x91, whole(18, 3, 12), t + 3000);
        report(telemetry, wholeFirst, 0x92, expanded(180, 39, 12), t + 4000);
        check(near(wholeFirst.trip().km, 0.2, 1e-9), "and later rounding still neither subtracts nor counts twice: " + wholeFirst.trip().km);
        report(telemetry, wholeFirst, 0x92, expanded(180, 41, 12), t + 5000);
        check(near(wholeFirst.trip().km, 0.4, 1e-9), "then the count carries on: " + wholeFirst.trip().km);

        Telemetry tenthsTelemetry = new Telemetry();
        RidingSession tenthsFirst = open(t);
        report(tenthsTelemetry, tenthsFirst, 0x92, expanded(0, 37, 12), t);
        report(tenthsTelemetry, tenthsFirst, 0x91, whole(0, 3, 12), t + 1000);
        report(tenthsTelemetry, tenthsFirst, 0x92, expanded(0, 37, 12), t + 2000);
        check(tenthsFirst.trip().km == 0, "the other order adds nothing either: " + tenthsFirst.trip().km);
        report(tenthsTelemetry, tenthsFirst, 0x92, expanded(180, 39, 12), t + 3000);
        check(near(tenthsFirst.trip().km, 0.2, 1e-9), "and counts riding too: " + tenthsFirst.trip().km);

        RidingSession ridden = open(t);
        trip(ridden, 3, 0x91, t);
        trip(ridden, 4, 0x91, t + 60_000);
        check(near(ridden.trip().km, 1, 1e-9), "whole-km reports count as they climb: " + ridden.trip().km);
        trip(ridden, 4.7, 0x92, t + 61_000);
        check(near(ridden.trip().km, 1, 1e-9), "and the first tenths keep that count: " + ridden.trip().km);
        trip(ridden, 5.0, 0x92, t + 90_000);
        check(near(ridden.trip().km, 1.3, 1e-9), "then ride on from them: " + ridden.trip().km);

        RidingSession jump = open(t);
        trip(jump, 3, 0x91, t);
        trip(jump, 5.4, 0x92, t + 300_000);
        check(near(jump.trip().km, 1.4, 1e-9), "a first tenths report past that kilometre counts only what was certainly ridden: " + jump.trip().km);

        // The restart and recording rules start a stretch that can be whole-km too.
        RidingSession restarted = open(t);
        trip(restarted, 10, 0x92, t);
        trip(restarted, 12, 0x92, t + 1000);
        trip(restarted, 0, 0x91, t + 2000);
        check(near(restarted.trip().km, 2, 1e-9), "a restart banks: " + restarted.trip().km);
        trip(restarted, 0.7, 0x92, t + 3000);
        check(near(restarted.trip().km, 2, 1e-9), "and the restarted counter's first tenths add nothing: " + restarted.trip().km);
        trip(restarted, 1.0, 0x92, t + 4000);
        check(near(restarted.trip().km, 2.3, 1e-9), "then it counts: " + restarted.trip().km);

        RidingSession stopped = open(t);
        stopped.values.setRecordingStart(t - 1000);
        stopped.setRecordingKm(5.0);
        trip(stopped, 3, 0x91, t);
        stopped.setRecordingKm(5.5);
        trip(stopped, 3, 0x91, t + 1000);
        check(near(stopped.trip().km, 0.5, 1e-9) && stopped.trip().fromRecording, "whole-km trip reports don't touch a recording's count");
        stopped.values.setRecordingStart(null);
        stopped.setRecordingKm(null);
        trip(stopped, 3, 0x91, t + 2000);
        trip(stopped, 3.7, 0x92, t + 3000);
        check(near(stopped.trip().km, 0.5, 1e-9) && !stopped.trip().fromRecording, "and the trip it falls back to starts whole-km the same way: " + stopped.trip().km);

        // Rotation between the two reports: the rebuilt session has an empty RidingValues.
        RidingSession before = open(t);
        trip(before, 3, 0x91, t);
        RidingSession.Resume saved = RidingSession.Resume.of(before.resume().profileId(), before.resume().openedMs(), before.resume().distance());
        check(saved != null, "the saved state is the one it wrote");
        RidingSession after = new RidingSession(PROFILE, null, t + 1000, saved);
        trip(after, 3.7, 0x92, t + 1000);
        check(after.trip().km == 0, "a recreated session remembers its baseline was whole km: " + after.trip().km);
        trip(after, 3.9, 0x92, t + 2000);
        check(near(after.trip().km, 0.2, 1e-9), "and counts on from the tenths: " + after.trip().km);
    }

    /** While a recording runs the spot follows its distance, from its value at this open or at the switch. */
    private static void distanceWhileRecording() {
        long t = 5_000_000;
        RidingSession session = open(t);
        session.values.setRecordingStart(t - 600_000);
        session.setRecordingKm(5.0);
        session.values.sample("trip_km", 40, 0x92, t);
        feed(session, t);
        check(session.trip().km == 0 && session.trip().fromRecording, "a recording already at 5 km starts the spot at 0, labelled Ride");
        session.setRecordingKm(5.5);
        trip(session, 40.5, t + 1000);
        check(near(session.trip().km, 0.5, 1e-9) && session.values.recordingTimeMs(t + 1000) == 601_000, "follows the recording; REC keeps its own time");
        session.values.setRecordingStart(null);
        session.setRecordingKm(null);
        trip(session, 40.6, t + 2000);
        check(near(session.trip().km, 0.6, 1e-9) && !session.trip().fromRecording, "the recording stopping banks it, plus the 0.1 ridden since the last refresh, without a jump to the trip's value: " + session.trip().km);
        trip(session, 41.0, t + 3000);
        check(near(session.trip().km, 1.0, 1e-9), "and carries on from the trip: " + session.trip().km);

        session.values.setRecordingStart(t + 4000);
        session.setRecordingKm(0.0);
        feed(session, t + 4000);
        check(near(session.trip().km, 1.0, 1e-9) && session.trip().fromRecording, "a recording starting mid-session doesn't snap back to its 0");
        session.setRecordingKm(0.2);
        feed(session, t + 5000);
        check(near(session.trip().km, 1.2, 1e-9), "and adds to the count: " + session.trip().km);
        session.setRecordingKm(Double.NaN);
        session.values.sample("trip_km", 41.2, 0x92, t + 6000);
        feed(session, t + 6000);
        check(!session.trip().fromRecording && session.trip().km >= 1.2, "a recording without a usable distance falls back to the trip");
    }

    /** A rider who keeps going while riding mode isn't on screen: the session sees only what its last refresh saw. */
    private static void distanceWhileThePhoneLooksAway() {
        long t = 5_000_000;

        // Backgrounded, rode on, stopped the recording from the notification, came back.
        RidingSession away = recording(t, 5.0, 40.0);
        step(away, 5.5, 40.5, t + 1000);
        check(near(away.trip().km, 0.5, 1e-9), "the last refresh before the phone looks away: " + away.trip().km);
        stopRecording(away);
        trip(away, 41.5, t + 600_000);
        check(near(away.trip().km, 1.5, 1e-9) && !away.trip().fromRecording, "the unseen stretch comes back when the recording has stopped: " + away.trip().km);
        trip(away, 41.7, t + 601_000);
        check(near(away.trip().km, 1.7, 1e-9), "and the trip counts on from there: " + away.trip().km);

        // An ordinary stop on screen: nothing unseen, nothing added twice.
        RidingSession onScreen = recording(t, 5.0, 40.0);
        step(onScreen, 5.5, 40.5, t + 1000);
        stopRecording(onScreen);
        trip(onScreen, 40.5, t + 2000);
        check(near(onScreen.trip().km, 0.5, 1e-9), "a stop with no riding since the last refresh adds nothing: " + onScreen.trip().km);
        trip(onScreen, 40.7, t + 3000);
        check(near(onScreen.trip().km, 0.7, 1e-9), "and then counts normally: " + onScreen.trip().km);
        trip(onScreen, 40.7, t + 4000);
        check(near(onScreen.trip().km, 0.7, 1e-9), "with no second helping: " + onScreen.trip().km);

        // The scooter's trip restarted while nobody was looking: that stretch is lost, never invented.
        RidingSession restarted = recording(t, 5.0, 40.0);
        step(restarted, 5.5, 40.5, t + 1000);
        stopRecording(restarted);
        trip(restarted, 0.4, t + 600_000);
        check(near(restarted.trip().km, 0.5, 1e-9), "a trip that restarted during the pause adds nothing: " + restarted.trip().km);
        trip(restarted, 0.6, t + 601_000);
        check(near(restarted.trip().km, 0.7, 1e-9), "and counts on from its new value: " + restarted.trip().km);

        // No live trip beside the recording's last value: the two can't be paired, so nothing is added.
        RidingSession unpaired = recording(t, 5.0, 40.0);
        unpaired.setRecordingKm(8.0);
        feed(unpaired, t + 600_000);
        check(near(unpaired.trip().km, 3, 1e-9), "the recording kept counting while the trip went quiet: " + unpaired.trip().km);
        stopRecording(unpaired);
        trip(unpaired, 43.0, t + 601_000);
        check(near(unpaired.trip().km, 3, 1e-9), "so the trip's catch-up isn't counted again on top: " + unpaired.trip().km);

        // A whole-km trip could have meant anywhere in that km.
        RidingSession whole = recording(t, 5.0, 40.0, 0x91);
        step(whole, 5.5, 40.0, 0x91, t + 1000);
        stopRecording(whole);
        trip(whole, 40.7, t + 2000);
        check(near(whole.trip().km, 0.5, 1e-9), "tenths arriving after a whole-km trip add no precision as distance: " + whole.trip().km);
        RidingSession wholeFar = recording(t, 5.0, 40.0, 0x91);
        step(wholeFar, 5.5, 40.0, 0x91, t + 1000);
        stopRecording(wholeFar);
        trip(wholeFar, 42.3, t + 600_000);
        check(near(wholeFar.trip().km, 1.8, 1e-9), "only what must have been ridden counts: " + wholeFar.trip().km);

        // A recording that started while the phone was away, and a recording replaced by another.
        RidingSession started = open(t);
        trip(started, 40.0, t);
        trip(started, 40.5, t + 1000);
        started.values.setRecordingStart(t + 300_000);
        started.setRecordingKm(0.3);
        started.values.sample("trip_km", 41.5, 0x92, t + 600_000);
        feed(started, t + 600_000);
        check(near(started.trip().km, 1.5, 1e-9) && started.trip().fromRecording, "riding before an unseen recording start still counts, once: " + started.trip().km);
        step(started, 0.5, 41.7, t + 601_000);
        check(near(started.trip().km, 1.7, 1e-9), "and the recording counts on from where it was first seen: " + started.trip().km);

        RidingSession replaced = recording(t, 5.0, 40.0);
        step(replaced, 5.5, 40.5, t + 1000);
        replaced.setRecordingKm(0.2);
        replaced.values.sample("trip_km", 41.5, 0x92, t + 600_000);
        feed(replaced, t + 600_000);
        check(near(replaced.trip().km, 1.5, 1e-9), "a recording stopped and started again while away keeps the stretch between: " + replaced.trip().km);

        // The activity rebuilt while away: the pairing is saved with the rest.
        RidingSession before = recording(t, 5.0, 40.0);
        step(before, 5.5, 40.5, t + 1000);
        RidingSession.Resume saved = RidingSession.Resume.of(before.resume().profileId(), before.resume().openedMs(), before.resume().distance());
        check(saved != null, "the saved state is the one it wrote");
        check(RidingSession.Resume.of(PROFILE, t, new double[5]) == null, "the older saved shape starts fresh");
        RidingSession after = new RidingSession(PROFILE, null, t + 600_000, saved);
        trip(after, 41.5, t + 600_000);
        check(near(after.trip().km, 1.5, 1e-9), "recreated while away, the unseen stretch still comes back: " + after.trip().km);
    }
    private static RidingSession recording(long t, double recordingKm, double tripKm) { return recording(t, recordingKm, tripKm, 0x92); }
    private static RidingSession recording(long t, double recordingKm, double tripKm, int type) {
        RidingSession session = open(t);
        session.values.setRecordingStart(t - 600_000);
        step(session, recordingKm, tripKm, type, t);
        return session;
    }
    /** One refresh of a running recording, with the trip counter reported beside it. */
    private static void step(RidingSession session, double recordingKm, double tripKm, long nowMs) { step(session, recordingKm, tripKm, 0x92, nowMs); }
    private static void step(RidingSession session, double recordingKm, double tripKm, int type, long nowMs) {
        session.setRecordingKm(recordingKm);
        trip(session, tripKm, type, nowMs);
    }
    private static void stopRecording(RidingSession session) {
        session.values.setRecordingStart(null);
        session.setRecordingKm(null);
    }

    private static void reopenAndRecreate() {
        long t = 5_000_000;
        RidingSession first = open(t);
        trip(first, 7, t);
        trip(first, 8, t + 90_000);
        // Back, then reopen with the same scooter trip still counting.
        long later = t + 120_000;
        RidingSession reopened = open(later);
        trip(reopened, 8.2, later);
        check(reopened.rideTimeMs(later) == 0 && reopened.trip().km == 0, "reopening starts both over: " + reopened.rideTimeMs(later) + " ms, " + reopened.trip().km + " km");
        trip(reopened, 8.5, later + 1000);
        check(near(reopened.trip().km, 0.3, 1e-9) && reopened.rideTimeMs(later + 1000) == 1000, "and counts this open only");

        // The activity recreated: a new session from saved state, with a new, empty RidingValues.
        RidingSession.Resume saved = RidingSession.Resume.of(first.resume().profileId(), first.resume().openedMs(), first.resume().distance());
        long back = t + 95_000;
        RidingSession recreated = new RidingSession(PROFILE, null, back, saved);
        check(recreated.rideTimeMs(back) == 95_000 && recreated.openedMs == t, "recreation keeps ride time: " + recreated.rideTimeMs(back));
        check(near(recreated.trip().km, 1, 1e-9), "and distance before the next report: " + recreated.trip().km);
        trip(recreated, 8.4, back);
        check(near(recreated.trip().km, 1.4, 1e-9), "counting what the scooter covered meanwhile: " + recreated.trip().km);

        RidingSession noReading = new RidingSession(PROFILE, null, back, open(t).resume());
        check(noReading.rideTimeMs(back) == 95_000 && noReading.trip().km == null, "recreated before any reading: still waiting for the baseline");
        trip(noReading, 30, back + 1000);
        check(noReading.trip().km == 0, "which is taken as usual");

        check(new RidingSession("p2", null, back, saved).rideTimeMs(back) == 0, "another scooter doesn't inherit it");
        check(new RidingSession(PROFILE, null, t - 1, saved).rideTimeMs(t - 1) == 0, "nor does an open time from the future (a clock from another boot)");
        check(RidingSession.Resume.of(null, t, new double[4]) == null && RidingSession.Resume.of(PROFILE, t, new double[3]) == null, "unusable saved state is refused");
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
