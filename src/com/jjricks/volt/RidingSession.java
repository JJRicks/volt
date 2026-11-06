package com.jjricks.volt;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One open riding mode: its live values, the live range ("Range at this pace") and the battery
 * band, kept across rotations and rebuilds while the screen around it is re-laid. Android-free and
 * clock-free: every call takes the monotonic time, so the JVM tests drive it with synthetic readings.
 *
 * It only reads telemetry. It never asks the scooter for anything, never starts a recording (owner,
 * Q12 A) and never touches the lock.
 */
final class RidingSession {
    final String profileId;
    final RidingValues values;
    final long openedMs;
    private final LiveRangeEstimator estimator = new LiveRangeEstimator();
    private final RangeReadout readout = new RangeReadout();
    private RangeReadout.Value range;
    private long fedMs = -1;
    private RidingValues.Band band;
    private boolean bandDropped, priorSet, closed;
    private LiveRangeEstimator.Prior prior;

    RidingSession(String profileId, RidingValues.PowerScale scale, long nowMs) {
        this.profileId = profileId == null ? "" : profileId;
        values = new RidingValues(scale == null ? new RidingValues.PowerScale() : scale);
        openedMs = nowMs;
    }

    /** The learned model's battery table and starting energy per km; null leaves the live range without a model. */
    void setPrior(LiveRangeEstimator.Prior prior) { this.prior = prior; estimator.reset(prior); priorSet = true; }

    /** Volt's range: the learned model's remaining km at this battery reading, from past rides; null without either. */
    Double learnedKm(Double batteryPercent) { return prior == null ? null : prior.learnedKm(batteryPercent); }
    boolean priorSet() { return priorSet; }

    /**
     * Asks once for the learned model's prior. The answer may come at once (a cached result) or later;
     * either way it lands on this session, unless the session has closed, already has a prior, or the
     * active scooter is no longer this session's.
     *
     * @param request hands a callback to whatever computes the prior
     * @param activeProfile the active scooter's profile id now, or null when nothing should land
     */
    void requestPrior(Consumer<Consumer<LiveRangeEstimator.Prior>> request, Supplier<String> activeProfile) {
        request.accept(prior -> { if (!closed && !priorSet && profileId.equals(activeProfile.get())) setPrior(prior); });
    }

    /** Riding mode left: a late prior is dropped. */
    void close() { closed = true; }
    boolean closed() { return closed; }

    /**
     * Feeds a telemetry snapshot. The live range learns only from new readings, timed by when they were
     * measured; its readout is updated every call, so it settles and goes stale on time.
     *
     * @param units the rider's units ("mi", "km" or "dual")
     * @param tenths the range's tenths (on by default, owner Q23 B)
     */
    void feed(List<Telemetry.Metric> metrics, long nowMs, boolean charging, String units, boolean tenths) {
        values.update(metrics);
        long newest = -1;
        for (String key : new String[]{RidingValues.SPEED_KEY, RidingValues.POWER_KEY, RidingValues.BATTERY_KEY}) {
            RidingValues.Reading r = values.reading(key, nowMs);
            if (r != null) newest = Math.max(newest, r.updatedMs);
        }
        if (newest > fedMs) {
            RidingValues.Speed speed = values.speed(nowMs);
            RidingValues.Power power = values.power(nowMs);
            RidingValues.Reading battery = values.reading(RidingValues.BATTERY_KEY, nowMs);
            RidingValues.Reading odometer = values.reading("odometer_km", nowMs);
            estimator.accept(newest, speed.kmh, power.watts, battery == null || battery.stale ? null : battery.value,
                    odometer == null ? null : odometer.value, charging);
            fedMs = newest;
        }
        range = readout.update(estimator.snapshot(nowMs), units, tenths);
        RidingValues.Band now = values.batteryBand(nowMs);
        if (RidingFocus.droppedBand(band, now)) bandDropped = true;
        if (now != null) band = now;
    }

    /** The live range readout, or null before the first feed. */
    RangeReadout.Value range() { return range; }

    /** True once after the battery dropped into a lower band, for the one haptic tick (plan: low battery). */
    boolean takeBandDrop() { boolean dropped = bandDropped; bandDropped = false; return dropped; }

    /** When the newest reading riding mode uses was measured, or -1 before any: the "last seen" clock. */
    long lastReadingMs(long nowMs) {
        long newest = -1;
        for (String key : RidingValues.KEYS) {
            RidingValues.Reading r = values.reading(key, nowMs);
            if (r != null) newest = Math.max(newest, r.updatedMs);
        }
        return newest;
    }
}
