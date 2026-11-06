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
        efficiency();
        energyAndScale();
        trace();
        timers();
        battery();
        ranges();
        System.out.println("RidingValuesTest: " + checks + " checks passed (0x91/0x92, sign, staleness, interval EMA, coverage, energy, scale, trace gaps, timers, bands, ranges)");
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
        check(values.direction() == RidingValues.Direction.UNKNOWN, "a sign byte other than 0/1 is unknown, never a guess");
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
        check(values.powerTrace(150_000, 60_000).get(0).value == -200, "and signed once confirmed");
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
