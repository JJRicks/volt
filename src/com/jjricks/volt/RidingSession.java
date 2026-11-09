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
     * The scooter's trip counter at the last feed that could pair it with the distance spot's count, and
     * whether it was a whole-km report. NaN when unknown, or when a recording kept counting beside a trip
     * that had gone quiet (the trip's catch-up would repeat what the recording already covered).
     */
    private double tripSeenKm = Double.NaN;
    private boolean tripSeenWhole;
    /** Top speed and the moving average since this riding mode opened, from the speed readings it saw. */
    private boolean speedSeen;
    private double topKmh, movingMs, movingKmhMs;
    /** When the newest speed reading counted was measured, or -1 before any. */
    private long speedSeenMs = -1;
    /** ScooterService.rideAutoEnds() when this riding mode opened: any change means its recording ended by itself. */
    private final long autoEnds;
    /** This riding mode has seen a scooter connected. Until it has, a missing connection is just how it was opened. */
    private boolean linkSeen;
    /** ScooterService.linkLosses() when Volt went off screen, or -1 while it's on screen. */
    private long leftLosses = -1;
    /** The connection had dropped and was still down when Volt went off screen. */
    private boolean leftDown;

    /**
     * The distance spot: how far since riding mode opened. km is null before the first usable reading.
     * fromRecording labels it "Ride" rather than "Trip".
     */
    static final class Trip {
        final Double km;
        final boolean fromRecording;
        private Trip(Double km, boolean fromRecording) { this.km = km; this.fromRecording = fromRecording; }
    }

    /** What a recreated activity needs to keep counting: this boot's open time, the distance and speed so far, and which recordings had auto-ended by then. */
    static final class Resume {
        private final String profileId;
        private final long openedMs;
        private final double bankedKm, baseKm, peakKm, tripSeenKm;
        private final boolean fromRecording, wholeKm, tripSeenWhole, speedSeen;
        private final double topKmh, movingMs, movingKmhMs;
        private final long speedSeenMs, autoEnds, leftLosses;
        private final boolean linkSeen, leftDown;
        private Resume(String profileId, long openedMs, double bankedKm, double baseKm, double peakKm, boolean fromRecording, boolean wholeKm, double tripSeenKm, boolean tripSeenWhole,
                boolean speedSeen, double topKmh, double movingMs, double movingKmhMs, long speedSeenMs, long autoEnds, boolean linkSeen, long leftLosses, boolean leftDown) {
            this.linkSeen = linkSeen; this.leftLosses = leftLosses; this.leftDown = leftDown;
            this.profileId = profileId; this.openedMs = openedMs; this.bankedKm = bankedKm; this.baseKm = baseKm; this.peakKm = peakKm; this.fromRecording = fromRecording; this.wholeKm = wholeKm;
            this.tripSeenKm = tripSeenKm; this.tripSeenWhole = tripSeenWhole;
            this.speedSeen = speedSeen; this.topKmh = topKmh; this.movingMs = movingMs; this.movingKmhMs = movingKmhMs; this.speedSeenMs = speedSeenMs; this.autoEnds = autoEnds;
        }
        String profileId() { return profileId; }
        long openedMs() { return openedMs; }
        double[] distance() { return new double[]{bankedKm, baseKm, peakKm, fromRecording ? 1 : 0, wholeKm ? 1 : 0, tripSeenKm, tripSeenWhole ? 1 : 0}; }
        double[] speed() { return new double[]{speedSeen ? 1 : 0, topKmh, movingMs, movingKmhMs, speedSeenMs}; }
        long autoEnds() { return autoEnds; }
        boolean linkSeen() { return linkSeen; }
        long leftLosses() { return leftLosses; }
        boolean leftDown() { return leftDown; }
        /** True when a recording ended by itself after this riding mode opened, so it must not come back. Unknown (a save from before this existed) never counts. */
        boolean autoEnded(long autoEndsNow) { return autoEnds >= 0 && autoEnds != autoEndsNow; }
        /** The same answer as RidingSession.lostOnReturn, for a riding mode the activity is about to restore. */
        boolean lostOnReturn(boolean connected, long lossesNow, boolean recording) { return lost(linkSeen, leftLosses, leftDown, connected, lossesNow, recording); }
        /** Rebuilds saved state, or null when the distance array isn't the one distance() wrote. */
        static Resume of(String profileId, long openedMs, double[] distance) { return of(profileId, openedMs, distance, null, -1); }
        /** As above, with Max and Avg (a speed array of any other shape starts them over) and the auto-end count, -1 when unknown. */
        static Resume of(String profileId, long openedMs, double[] distance, double[] speed, long autoEnds) { return of(profileId, openedMs, distance, speed, autoEnds, false, -1, false); }
        /** As above, with what it knew about the connection when Volt went off screen; a save from before this counts as never connected. */
        static Resume of(String profileId, long openedMs, double[] distance, double[] speed, long autoEnds, boolean linkSeen, long leftLosses, boolean leftDown) {
            if (profileId == null || distance == null || distance.length != 7) return null;
            boolean hasSpeed = speed != null && speed.length == 5;
            return new Resume(profileId, openedMs, distance[0], distance[1], distance[2], distance[3] != 0, distance[4] != 0, distance[5], distance[6] != 0,
                    hasSpeed && speed[0] != 0, hasSpeed ? speed[1] : 0, hasSpeed ? speed[2] : 0, hasSpeed ? speed[3] : 0, hasSpeed ? (long) speed[4] : -1, autoEnds, linkSeen, leftLosses, leftDown);
        }
    }

    /** A fresh riding mode: ride time and distance start from zero. */
    RidingSession(String profileId, RidingValues.PowerScale scale, long nowMs) { this(profileId, scale, nowMs, null); }

    /**
     * @param resume this riding mode's own state when the activity was recreated, so rotation or a
     *               fold never restarts the count; ignored for another scooter or a later open time
     */
    RidingSession(String profileId, RidingValues.PowerScale scale, long nowMs, Resume resume) { this(profileId, scale, nowMs, resume, 0); }

    /** @param autoEndsNow ScooterService.rideAutoEnds() at this open; the resumed value wins when there is one */
    RidingSession(String profileId, RidingValues.PowerScale scale, long nowMs, Resume resume, long autoEndsNow) {
        this.profileId = profileId == null ? "" : profileId;
        values = new RidingValues(scale == null ? new RidingValues.PowerScale() : scale);
        boolean resumes = resume != null && this.profileId.equals(resume.profileId) && resume.openedMs <= nowMs;
        openedMs = resumes ? resume.openedMs : nowMs;
        if (resumes) { bankedKm = resume.bankedKm; baseKm = resume.baseKm; peakKm = resume.peakKm; fromRecording = resume.fromRecording; wholeKm = resume.wholeKm; tripSeenKm = resume.tripSeenKm; tripSeenWhole = resume.tripSeenWhole;
            speedSeen = resume.speedSeen; topKmh = resume.topKmh; movingMs = resume.movingMs; movingKmhMs = resume.movingKmhMs; speedSeenMs = resume.speedSeenMs; linkSeen = resume.linkSeen; }
        autoEnds = resumes && resume.autoEnds >= 0 ? resume.autoEnds : autoEndsNow;
    }

    /** Ride time: since this riding mode opened, counted on the phone's clock. */
    Long rideTimeMs(long nowMs) { return Math.max(0, nowMs - openedMs); }

    /** This riding mode's state, to hand to the session that replaces it when the activity is recreated. */
    Resume resume() { return new Resume(profileId, openedMs, bankedKm, baseKm, peakKm, fromRecording, wholeKm, tripSeenKm, tripSeenWhole, speedSeen, topKmh, movingMs, movingKmhMs, speedSeenMs, autoEnds, linkSeen, leftLosses, leftDown); }

    /** True when a recording ended by itself after this riding mode opened: riding mode closes with it. */
    boolean autoEnded(long autoEndsNow) { return autoEnds != autoEndsNow; }

    /** The connection as an on-screen riding mode sees it. Off screen, only what left() and lostOnReturn() compare counts. */
    void observeLink(boolean connected) { if (connected && leftLosses < 0) linkSeen = true; }

    /** Volt went off screen (onStop, not onPause). The first call of a leave counts: Android may save state before stopping. */
    void left(boolean connected, long lossesNow) {
        if (leftLosses >= 0) return;
        observeLink(connected);
        leftLosses = Math.max(0, lossesNow);
        leftDown = linkSeen && !connected;
    }

    /** Off screen since left(). */
    boolean away() { return leftLosses >= 0; }

    /**
     * Volt is back on screen. True when riding mode should close to Home: it had seen a scooter, the
     * connection was lost while Volt was away or was already down when it left, and no recording is running.
     * Ends the leave either way, so a drop while on screen never counts.
     */
    boolean lostOnReturn(boolean connected, long lossesNow, boolean recording) {
        boolean lost = lost(linkSeen, leftLosses, leftDown, connected, lossesNow, recording);
        leftLosses = -1;
        leftDown = false;
        return lost;
    }

    /** A count rather than a time: a loss while away shows even if the connection is back by the return. */
    private static boolean lost(boolean linkSeen, long leftLosses, boolean leftDown, boolean connected, long lossesNow, boolean recording) {
        return linkSeen && leftLosses >= 0 && !recording && (leftDown || !connected || lossesNow != leftLosses);
    }

    /** Top speed since this riding mode opened: null before the first speed reading, then 0 until something has moved. */
    Double maxKmh() { return speedSeen ? topKmh : null; }

    /** Time-weighted average of speed while moving, since this riding mode opened: null before the first speed reading, then 0 until something has moved. */
    Double averageKmh() { return !speedSeen ? null : movingMs > 0 ? movingKmhMs / movingMs : 0; }

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
     *
     * This only sees what a refresh saw, and refreshes stop while the screen is paused. So when the
     * counter being followed changes (a recording starts, stops or is replaced), whatever the trip
     * counter has gained since the last feed is banked too, under the same whole-km rule. A trip that
     * fell or was never paired adds nothing: that stretch is lost, not guessed.
     */
    private void trackDistance(long nowMs) {
        boolean recording = recordingKm != null && values.recordingTimeMs(nowMs) != null;
        RidingValues.Reading r = values.reading(RidingValues.TRIP_KM_KEY, nowMs);
        boolean tripWhole = r != null && r.sourceCmd != RidingValues.EXPANDED_RIDE_REPORT;
        double km;
        boolean whole = false;
        if (recording) km = recordingKm;
        else {
            // A leftover from before this open isn't a baseline: the scooter may have moved since.
            if (r == null || r.stale && r.updatedMs < openedMs) return;
            km = r.value;
            whole = tripWhole;
        }
        if (km < 0) return;
        if (Double.isNaN(peakKm)) { baseKm = peakKm = km; fromRecording = recording; wholeKm = whole; }
        else if (recording != fromRecording || peakKm - km >= COUNTER_RESET_KM) {
            bankedKm += peakKm - baseKm + unseenTripKm(r);
            baseKm = peakKm = km;
            fromRecording = recording;
            wholeKm = whole;
        } else if (wholeKm && !whole) {
            bankedKm += peakKm - baseKm + Math.max(0, km - peakKm - 1);
            baseKm = peakKm = km;
            wholeKm = false;
        } else peakKm = Math.max(peakKm, km);
        // A recording needs a live trip beside it to pair with; a stale one would be behind what it has counted.
        tripSeenKm = r == null || r.value < 0 || recording && r.stale ? Double.NaN : r.value;
        tripSeenWhole = tripWhole;
    }

    /**
     * Follows the speed reading for Max and Avg, which count from this open and hold through a stop.
     * Each new reading at or above RidingFrames.STOPPED_KMH counts toward Max, and toward Avg for the time
     * since the one before it. Time across a dropout isn't counted, the same gap rule as the speed trace.
     */
    private void trackSpeed(long nowMs) {
        RidingValues.Reading r = values.reading(RidingValues.SPEED_KEY, nowMs);
        // A leftover from before this open isn't this ride's: the same rule as the distance baseline.
        if (r == null || r.updatedMs <= speedSeenMs || r.stale && r.updatedMs < openedMs) return;
        long previousMs = speedSeenMs;
        speedSeen = true;
        speedSeenMs = r.updatedMs;
        if (r.value < RidingFrames.STOPPED_KMH) return;
        topKmh = Math.max(topKmh, r.value);
        long stepMs = r.updatedMs - previousMs;
        if (previousMs >= 0 && stepMs <= Math.max(RidingValues.MIN_GAP_MS, RidingValues.GAP_INTERVALS * r.intervalMs)) {
            movingMs += stepMs;
            movingKmhMs += r.value * stepMs;
        }
    }

    /** What the scooter's trip counter must have gained since the last feed; 0 when it's unknown or fell. */
    private double unseenTripKm(RidingValues.Reading trip) {
        if (trip == null || trip.value < 0 || Double.isNaN(tripSeenKm)) return 0;
        return Math.max(0, trip.value - tripSeenKm - (tripSeenWhole ? 1 : 0));
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
        trackSpeed(nowMs);
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
