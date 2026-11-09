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
    /** A counter that falls by at least this much has restarted; a smaller fall is rounding. */
    static final double COUNTER_RESET_KM = 1;
    private final LiveRangeEstimator estimator = new LiveRangeEstimator();
    private final RangeReadout readout = new RangeReadout();
    private RangeReadout.Value range;
    private long fedMs = -1;
    private RidingValues.Band band;
    private boolean bandDropped, priorSet, closed;
    private LiveRangeEstimator.Prior prior;
    /** The recording's distance so far, or null without a recording that has one. */
    private Double recordingKm;
    /** Distance banked from earlier stretches of this session. */
    private double bankedKm;
    /** The current stretch: its counter's first and highest value (NaN before the first reading), and which counter it follows. */
    private double baseKm = Double.NaN, peakKm = Double.NaN;
    private boolean fromRecording;
    /** The current stretch's counter has only reported whole km so far, so its true value is unknown within the km. */
    private boolean wholeKm;

    /**
     * The distance spot: how far since riding mode opened. km is null before the first usable reading.
     * fromRecording labels it "Ride" rather than "Trip".
     */
    static final class Trip {
        final Double km;
        final boolean fromRecording;
        private Trip(Double km, boolean fromRecording) { this.km = km; this.fromRecording = fromRecording; }
    }

    /** What a recreated activity needs to keep counting: this boot's open time and the distance so far. */
    static final class Resume {
        private final String profileId;
        private final long openedMs;
        private final double bankedKm, baseKm, peakKm;
        private final boolean fromRecording, wholeKm;
        private Resume(String profileId, long openedMs, double bankedKm, double baseKm, double peakKm, boolean fromRecording, boolean wholeKm) {
            this.profileId = profileId; this.openedMs = openedMs; this.bankedKm = bankedKm; this.baseKm = baseKm; this.peakKm = peakKm; this.fromRecording = fromRecording; this.wholeKm = wholeKm;
        }
        String profileId() { return profileId; }
        long openedMs() { return openedMs; }
        double[] distance() { return new double[]{bankedKm, baseKm, peakKm, fromRecording ? 1 : 0, wholeKm ? 1 : 0}; }
        /** Rebuilds saved state, or null when the distance array isn't the one distance() wrote. */
        static Resume of(String profileId, long openedMs, double[] distance) {
            if (profileId == null || distance == null || distance.length != 5) return null;
            return new Resume(profileId, openedMs, distance[0], distance[1], distance[2], distance[3] != 0, distance[4] != 0);
        }
    }

    /** A fresh riding mode: ride time and distance start from zero. */
    RidingSession(String profileId, RidingValues.PowerScale scale, long nowMs) { this(profileId, scale, nowMs, null); }

    /**
     * @param resume this riding mode's own state when the activity was recreated, so rotation or a
     *               fold never restarts the count; ignored for another scooter or a later open time
     */
    RidingSession(String profileId, RidingValues.PowerScale scale, long nowMs, Resume resume) {
        this.profileId = profileId == null ? "" : profileId;
        values = new RidingValues(scale == null ? new RidingValues.PowerScale() : scale);
        boolean resumes = resume != null && this.profileId.equals(resume.profileId) && resume.openedMs <= nowMs;
        openedMs = resumes ? resume.openedMs : nowMs;
        if (resumes) { bankedKm = resume.bankedKm; baseKm = resume.baseKm; peakKm = resume.peakKm; fromRecording = resume.fromRecording; wholeKm = resume.wholeKm; }
    }

    /** Ride time: since this riding mode opened, counted on the phone's clock. */
    Long rideTimeMs(long nowMs) { return Math.max(0, nowMs - openedMs); }

    /** This riding mode's state, to hand to the session that replaces it when the activity is recreated. */
    Resume resume() { return new Resume(profileId, openedMs, bankedKm, baseKm, peakKm, fromRecording, wholeKm); }

    /** The recording's distance so far (null without one); the next feed folds it in. */
    void setRecordingKm(Double km) { recordingKm = km == null || !Double.isFinite(km) ? null : km; }

    /** How far since this riding mode opened. Never negative. */
    Trip trip() { return new Trip(Double.isNaN(peakKm) ? null : bankedKm + peakKm - baseKm, fromRecording); }

    /**
     * Follows the counter the distance spot reads: the recording's while one is running (it may have
     * started before this open), otherwise the scooter's trip. The first usable reading is the
     * baseline, so the spot starts at 0 whatever the counter already says.
     *
     * The count is kept in stretches, so a counter that restarts or a recording that starts or stops
     * banks what has been covered rather than snapping back. Only a fall of COUNTER_RESET_KM or more
     * is a restart: a whole-unit (0x91) report rounds down what a tenths (0x92) report showed, and
     * must neither subtract nor be counted twice when the tenths come back. The cost is a restart
     * from under that distance, which is only counted once it passes the old value.
     *
     * The other way round, a stretch that has only had whole-km reports may be anywhere in that km, so
     * the first tenths report sets its precision rather than adding its fraction. It adds only what
     * must have been ridden: anything beyond the km the last whole report could have meant.
     */
    private void trackDistance(long nowMs) {
        boolean recording = recordingKm != null && values.recordingTimeMs(nowMs) != null;
        double km;
        boolean whole = false;
        if (recording) km = recordingKm;
        else {
            RidingValues.Reading r = values.reading(RidingValues.TRIP_KM_KEY, nowMs);
            // A leftover from before this open isn't a baseline: the scooter may have moved since.
            if (r == null || r.stale && r.updatedMs < openedMs) return;
            km = r.value;
            whole = r.sourceCmd != RidingValues.EXPANDED_RIDE_REPORT;
        }
        if (km < 0) return;
        if (Double.isNaN(peakKm)) { baseKm = peakKm = km; fromRecording = recording; wholeKm = whole; }
        else if (recording != fromRecording || peakKm - km >= COUNTER_RESET_KM) {
            bankedKm += peakKm - baseKm;
            baseKm = peakKm = km;
            fromRecording = recording;
            wholeKm = whole;
        } else if (wholeKm && !whole) {
            bankedKm += peakKm - baseKm + Math.max(0, km - peakKm - 1);
            baseKm = peakKm = km;
            wholeKm = false;
        } else peakKm = Math.max(peakKm, km);
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
        trackDistance(nowMs);
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
