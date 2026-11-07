package com.jjricks.volt;

import java.util.*;

/**
 * Riding mode's live values, derived from Telemetry readings (a number and when it was measured).
 * Android-free, with no clock of its own: every call takes the monotonic time.
 *
 * It tracks each reading's interval (an EMA of the gaps between new samples), staleness, the
 * 30 s efficiency with the recording rules' 80 % coverage gate, ride energy, peak draw and regen,
 * the power scale, a 120 s trace buffer, ride time and recording time. Range readings come from
 * pluggable sources. Nothing here extrapolates past the latest real reading or invents a zero.
 */
final class RidingValues {
    /** The ride push (0x91/0x92), assumed about 1 s; not measured yet. */
    static final long RIDE_NOMINAL_MS = 1000;
    /** The battery read while recording; otherwise the Telemetry refresh setting paces it. */
    static final long RECORDING_ELECTRICAL_MS = 2000;
    static final long MIN_INTERVAL_MS = 250, MAX_INTERVAL_MS = 30_000;
    static final double EMA_ALPHA = 0.25;
    /** A reading older than this many intervals is stale (drawn dim). */
    static final int STALE_INTERVALS = 3;
    /** Speed older than this is gone, matching BleController.stationary(). */
    static final long SPEED_GONE_MS = 6000;
    /** Battery bands (owner): amber below 30 %, red below 15 %. */
    static final double BATTERY_AMBER_BELOW = 30, BATTERY_RED_BELOW = 15;
    static final long EFFICIENCY_WINDOW_MS = 30_000;
    /** Same gate as recordings (RideStore.MIN_EFFICIENCY_COVERAGE). */
    static final double EFFICIENCY_COVERAGE = 0.8;
    /** Below this distance in the window the rider is effectively stopped and Wh/km means nothing. */
    static final double EFFICIENCY_MIN_KM = 0.02;
    static final long TRACE_KEEP_MS = 120_000;
    /** Samples further apart than this many expected intervals are a gap, never joined or integrated. */
    static final int GAP_INTERVALS = 3;
    /** Recordings never integrate across more than this; a slower refresh gets its own allowance. */
    static final long MIN_GAP_MS = 3000;
    /**
     * An unknown-direction power reading at least this strong is inferred draw (owner, Nov 6:
     * "if we're kicking past 1kW its probably safe to say its sipping battery"). Over the owner's
     * 38 rides with a confirmed direction, the strongest regen was 863 W (at about 42 km/h), the
     * 99.9th percentile of regen 741 W, and no regen sample reached 1 kW; draw had a median of
     * 857 W and a 90th percentile of 1,784 W, and 85 % of draw energy came from samples over 1 kW.
     * 1 kW is only 16 % above that peak regen, so the floor is 1,200 W.
     */
    static final double INFERRED_DRAW_MIN_W = 1200;
    /** A ride that regenerates harder raises the threshold to this multiple of its strongest confirmed regen. */
    static final double INFERRED_DRAW_REGEN_FACTOR = 1.5;

    static final String SPEED_KEY = "speed_kmh", POWER_KEY = "power_w", SIGN_KEY = "current_sign_state",
            TRIP_MINUTES_KEY = "trip_minutes", TRIP_KM_KEY = "trip_km", MAX_SPEED_KEY = "maximum_speed_kmh", BATTERY_KEY = "battery_percent", SCOOTER_RANGE_KEY = "remaining_range_km";
    /** Readings from the ride push. */
    private static final Set<String> RIDE_KEYS = new HashSet<>(Arrays.asList(SPEED_KEY, "trip_km", TRIP_MINUTES_KEY,
            "maximum_speed_kmh", "average_speed_kmh", "odometer_km"));
    /** Readings from the battery read (or the home report). */
    private static final Set<String> ELECTRICAL_KEYS = new HashSet<>(Arrays.asList(POWER_KEY, "voltage_v", "current_a", "temperature_c"));
    /** Every reading riding mode uses. Others are ignored. */
    static final Set<String> KEYS;
    static {
        Set<String> keys = new HashSet<>(RIDE_KEYS);
        keys.addAll(ELECTRICAL_KEYS);
        keys.addAll(Arrays.asList(BATTERY_KEY, SCOOTER_RANGE_KEY, "driving_mode", SIGN_KEY, "warning_code"));
        KEYS = Collections.unmodifiableSet(keys);
    }
    static final int EXPANDED_RIDE_REPORT = 0x92;

    enum Band { GREEN, AMBER, RED }
    /**
     * How a power reading's direction is known. DRAW and REGEN were measured under a known 0x79
     * sign. DRAW_INFERRED had no known sign but was too strong to be regen (inferredDrawThresholdW());
     * it counts as draw everywhere, yet stays apart from confirmed so a screen can show the difference.
     * UNKNOWN is a weaker reading without a sign: only its magnitude is known.
     */
    enum Direction { DRAW, REGEN, UNKNOWN, DRAW_INFERRED;
        /** True for DRAW_INFERRED and UNKNOWN: the reading's sign can't be trusted, so it counts as a magnitude. */
        boolean unsigned() { return this == UNKNOWN || this == DRAW_INFERRED; }
        /** True when the reading counts as draw or regen with a known sense: everything but UNKNOWN. */
        boolean knownOrInferred() { return this != UNKNOWN; }
    }

    /** One reading as riding mode sees it. */
    static final class Reading {
        final double value;
        final long updatedMs, intervalMs, ageMs;
        final int sourceCmd;
        final boolean stale;
        private Reading(double value, long updatedMs, long intervalMs, long ageMs, int sourceCmd) {
            this.value = value; this.updatedMs = updatedMs; this.intervalMs = intervalMs; this.ageMs = ageMs; this.sourceCmd = sourceCmd;
            this.stale = ageMs > STALE_INTERVALS * intervalMs;
        }
    }

    /** Speed: null kmh once it's gone, with the last value kept for "last 18.4". */
    static final class Speed {
        final Double kmh, lastKmh;
        /** True only for the expanded ride report (0x92); 0x91 sends whole units. */
        final boolean tenths;
        final boolean stale;
        private Speed(Double kmh, Double lastKmh, boolean tenths, boolean stale) { this.kmh = kmh; this.lastKmh = lastKmh; this.tenths = tenths; this.stale = stale; }
    }

    /** Power for the bar: null watts when stale, so the bar falls back to an outline at zero. */
    static final class Power {
        final Double watts;
        final Direction direction;
        final double peakDrawW, peakRegenW;
        private Power(Double watts, Direction direction, double peakDrawW, double peakRegenW) {
            this.watts = watts; this.direction = direction; this.peakDrawW = peakDrawW; this.peakRegenW = peakRegenW;
        }
    }

    static final class Efficiency {
        /** Wh per km, or null when the window isn't covered well enough or the rider is stopped. */
        final Double whPerKm;
        final double coverage;
        /**
         * True only when every stretch of the window was measured under a known sign. Otherwise
         * those stretches count their magnitude as draw, so an unconfirmed figure is an upper
         * bound, never a regen gain.
         */
        final boolean directionConfirmed;
        /**
         * True when every stretch was confirmed or inferred draw (Direction.DRAW_INFERRED), so the
         * figure has no stretch of genuinely unknown direction. Implied by directionConfirmed.
         */
        final boolean directionKnownOrInferred;
        private Efficiency(Double whPerKm, double coverage, boolean directionConfirmed, boolean directionKnownOrInferred) {
            this.whPerKm = whPerKm; this.coverage = coverage; this.directionConfirmed = directionConfirmed; this.directionKnownOrInferred = directionKnownOrInferred;
        }
    }

    /** A range reading: km (null when unavailable), how sure (0 to 1) and which estimator. */
    static final class Range {
        static final String PACE = "pace", SCOOTER = "scooter", VOLT = "volt", PLACEHOLDER = "placeholder";
        final Double km;
        final double confidence;
        final String source;
        Range(Double km, double confidence, String source) { this.km = km; this.confidence = Math.max(0, Math.min(1, confidence)); this.source = source; }
    }
    interface RangeSource { Range range(long nowMs); }
    /** Until the live-range estimator lands, Range at this pace shows as unavailable, never as a guess. */
    static final RangeSource PACE_PLACEHOLDER = now -> new Range(null, 0, Range.PLACEHOLDER);

    static final class TracePoint {
        final long t;
        final double value;
        /** True when this point follows a gap: draw a break, never a line through zero. */
        final boolean gapBefore;
        /** True for a power point measured without a known direction: a magnitude, drawn neutral, never as draw or regen. */
        final boolean directionUnknown;
        private TracePoint(long t, double value, boolean gapBefore, boolean directionUnknown) {
            this.t = t; this.value = value; this.gapBefore = gapBefore; this.directionUnknown = directionUnknown;
        }
    }

    /** The power bar's scale: 1,000 W draw to start, growing to the observed peak rounded up to 250 W. Regen has its own. */
    static final class PowerScale {
        static final double DRAW_START_W = 1000, REGEN_START_W = 500, STEP_W = 250;
        /** A reading above these is a decoding glitch, not a ride; it never stretches the scale. */
        static final double DRAW_CAP_W = 6000, REGEN_CAP_W = 3000;
        double drawW = DRAW_START_W, regenW = REGEN_START_W;
        PowerScale() { }
        PowerScale(double drawW, double regenW) {
            this.drawW = clamp(drawW, DRAW_START_W, DRAW_CAP_W);
            this.regenW = clamp(regenW, REGEN_START_W, REGEN_CAP_W);
        }
        private static double clamp(double v, double lo, double hi) { return !Double.isFinite(v) ? lo : Math.max(lo, Math.min(hi, v)); }
        /** Grows the scale for this reading; true when it changed and should be saved. */
        boolean observe(double watts, Direction direction) {
            if (!Double.isFinite(watts)) return false;
            double magnitude = Math.abs(watts);
            if (direction == Direction.REGEN) {
                if (magnitude <= regenW || magnitude > REGEN_CAP_W) return false;
                regenW = Math.min(REGEN_CAP_W, Math.ceil(magnitude / STEP_W) * STEP_W);
            } else {
                if (magnitude <= drawW || magnitude > DRAW_CAP_W) return false;
                drawW = Math.min(DRAW_CAP_W, Math.ceil(magnitude / STEP_W) * STEP_W);
            }
            return true;
        }
    }

    private static final class Track {
        double value;
        long updatedMs = -1;
        int sourceCmd = -1;
        double intervalMs;
        long nominalMs;
    }

    private final Map<String, Track> tracks = new HashMap<>();
    private long rideNominalMs = RIDE_NOMINAL_MS, electricalNominalMs = RECORDING_ELECTRICAL_MS;
    /** Times as {ms, 1 after a gap}; values as {value} for speed and {watts, Direction ordinal} for power. */
    private final ArrayDeque<long[]> speedSamples = new ArrayDeque<>(), powerSamples = new ArrayDeque<>();
    private final ArrayDeque<double[]> speedValues = new ArrayDeque<>(), powerValues = new ArrayDeque<>();
    private final PowerScale scale;
    private boolean scaleChanged;
    /**
     * Ride energy; the part of it from stretches touching an unknown direction (counted as
     * magnitude); and the part from stretches touching inferred draw (and no unknown end).
     */
    private double rideEnergyWh, unconfirmedEnergyWh, inferredDrawWh, peakDrawW, peakRegenW;
    /** What the latest power sample added, so a sign correction can redo it without double-counting. */
    private double lastSegmentWh, lastSegmentUnconfirmedWh, lastSegmentInferredWh, peakDrawBeforeW, peakRegenBeforeW, scaleDrawBeforeW, scaleRegenBeforeW;
    private double lastSegmentPreviousW;
    private Direction lastSegmentPreviousDirection = Direction.UNKNOWN;
    private long lastSegmentPreviousMs = -1;
    private Double lastSpeedKmh;
    private boolean lastSpeedTenths;
    private Long recordingStartMs;
    /** Monotonic time the scooter's trip started, estimated from trip_minutes. */
    private Long tripStartMs;
    /** The trip_minutes reading that last anchored it, and when it was measured. */
    private double tripMinutesSeen;
    private long tripSeenMs;
    /** A previous riding session's trip clock, used by this session's first trip report if it still fits. */
    private TripClock resumeTrip;
    /**
     * The trip's distance and top speed so far (NaN before a reading). Both only grow within a trip and start
     * over at 0 when the scooter powers on, so a drop is a new trip even when the minute looks the same.
     */
    private double tripKmMark = Double.NaN, tripMaxMark = Double.NaN;
    /** A drop arrived before this report's trip_minutes: the next one starts a new trip. */
    private boolean tripRestarted;
    private final Map<String, RangeSource> ranges = new HashMap<>();

    RidingValues() { this(new PowerScale()); }
    RidingValues(PowerScale scale) {
        this.scale = scale;
        ranges.put(RidingLayout.RANGE_AT_PACE, PACE_PLACEHOLDER);
        ranges.put(RidingLayout.RANGE_SCOOTER, now -> {
            Reading r = reading(SCOOTER_RANGE_KEY, now);
            return new Range(r == null ? null : r.value, r == null || r.stale ? 0 : 1, Range.SCOOTER);
        });
        ranges.put(RidingLayout.RANGE_VOLT, now -> new Range(null, 0, Range.VOLT));
    }

    /**
     * Expected intervals: the ride push, and the battery read (2 s while recording, otherwise the
     * Telemetry refresh setting). A change restarts the measured interval of the affected readings.
     */
    void setNominalIntervals(long rideMs, long electricalMs) {
        rideNominalMs = Math.max(MIN_INTERVAL_MS, rideMs);
        electricalNominalMs = Math.max(MIN_INTERVAL_MS, electricalMs);
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            long nominal = nominal(e.getKey());
            if (e.getValue().nominalMs != nominal) { e.getValue().nominalMs = nominal; e.getValue().intervalMs = nominal; }
        }
    }
    private long nominal(String key) {
        return RIDE_KEYS.contains(key) ? rideNominalMs : ELECTRICAL_KEYS.contains(key) ? electricalNominalMs : Math.max(rideNominalMs, electricalNominalMs);
    }

    /** Plugs in a range estimator for RANGE_AT_PACE or RANGE_VOLT (or replaces the scooter's). */
    void setRangeSource(String contentId, RangeSource source) {
        if (source != null && (contentId.equals(RidingLayout.RANGE_AT_PACE) || contentId.equals(RidingLayout.RANGE_SCOOTER) || contentId.equals(RidingLayout.RANGE_VOLT)))
            ranges.put(contentId, source);
    }
    /** The reading a range spot shows, or null for a content that isn't a range. */
    Range range(String contentId, long nowMs) { RangeSource s = ranges.get(contentId); return s == null ? null : s.range(nowMs); }

    /** Feeds every reading in a Telemetry snapshot. Readings already seen add nothing; a re-signed one is a correction. */
    void update(List<Telemetry.Metric> metrics) {
        // The sign first, so power in the same snapshot is read with the direction it was measured under.
        for (int pass = 0; pass < 2; pass++)
            for (Telemetry.Metric m : metrics)
                if (m.numericValue != null && m.updatedMs >= 0 && KEYS.contains(m.key) && (pass == 0) == m.key.equals(SIGN_KEY))
                    sample(m.key, m.numericValue, m.sourceCmd, m.updatedMs);
    }

    /**
     * One reading. A sample older than the last one for its key is ignored. One with the same
     * time is a correction (Telemetry re-signs current and power after a 0x79 reply but keeps the
     * measurement time): it replaces the value without counting as a new measurement.
     */
    void sample(String key, double value, int sourceCmd, long updatedMs) {
        if (!KEYS.contains(key) || !Double.isFinite(value) || updatedMs < 0) return;
        Track t = tracks.get(key);
        if (t == null) { t = new Track(); t.nominalMs = nominal(key); t.intervalMs = t.nominalMs; tracks.put(key, t); }
        else if (updatedMs < t.updatedMs) return;
        else if (updatedMs == t.updatedMs) { correct(key, value, t); return; }
        long previous = t.updatedMs;
        if (previous >= 0) {
            long gap = updatedMs - previous;
            // A dropout is staleness, not cadence: it doesn't stretch the interval.
            if (gap <= GAP_INTERVALS * Math.max(t.intervalMs, t.nominalMs))
                t.intervalMs = clampInterval(t.intervalMs * (1 - EMA_ALPHA) + gap * EMA_ALPHA);
        }
        t.value = value; t.updatedMs = updatedMs; t.sourceCmd = sourceCmd;
        if (key.equals(SPEED_KEY)) {
            lastSpeedKmh = value; lastSpeedTenths = sourceCmd == EXPANDED_RIDE_REPORT;
            append(speedSamples, speedValues, updatedMs, new double[]{value}, gapMs(t));
        } else if (key.equals(POWER_KEY)) {
            powerSample(value, updatedMs, previous, t);
        } else if (key.equals(TRIP_MINUTES_KEY)) {
            tripMinutes(value, updatedMs, t);
        } else if (key.equals(TRIP_KM_KEY) || key.equals(MAX_SPEED_KEY)) {
            tripEvidence(key.equals(TRIP_KM_KEY), value, updatedMs);
        }
    }
    private static double clampInterval(double ms) { return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, ms)); }
    private static long gapMs(Track t) { return Math.max(MIN_GAP_MS, (long) (GAP_INTERVALS * Math.max(t.intervalMs, t.nominalMs))); }

    /**
     * A same-time sample. For power it is a correction only under a known sign, when the value or
     * its direction changed; it redoes that one sample's energy, peaks and scale, and never moves
     * the reading's time or interval. An unknown sign corrects nothing. Other keys take a changed value.
     */
    private void correct(String key, double value, Track t) {
        if (key.equals(POWER_KEY)) {
            if (powerValues.isEmpty() || powerSamples.peekLast()[0] != t.updatedMs) return;
            double[] last = powerValues.peekLast();
            Direction now = signDirection(value);
            if (now == Direction.UNKNOWN || (value == last[0] && now.ordinal() == (int) last[1])) return;
            t.value = value;
            last[0] = value;
            last[1] = now.ordinal();
            rideEnergyWh -= lastSegmentWh;
            unconfirmedEnergyWh -= lastSegmentUnconfirmedWh;
            inferredDrawWh -= lastSegmentInferredWh;
            peakDrawW = peakDrawBeforeW; peakRegenW = peakRegenBeforeW;
            double drawW = scale.drawW, regenW = scale.regenW;
            scale.drawW = scaleDrawBeforeW; scale.regenW = scaleRegenBeforeW;
            applyPower(value, now, t.updatedMs, lastSegmentPreviousMs, lastSegmentPreviousW, lastSegmentPreviousDirection);
            scaleChanged |= scale.drawW != drawW || scale.regenW != regenW;
            return;
        }
        if (value == t.value) return;
        t.value = value;
        if (key.equals(SPEED_KEY)) {
            lastSpeedKmh = value;
            if (!speedValues.isEmpty() && speedSamples.peekLast()[0] == t.updatedMs) speedValues.peekLast()[0] = value;
        }
    }

    private void powerSample(double watts, long updatedMs, long previousMs, Track t) {
        Direction direction = signDirection(watts);
        // Decided once, from what's known now; a later sign or threshold never revisits it.
        if (direction == Direction.UNKNOWN && Math.abs(watts) >= inferredDrawThresholdW() && Math.abs(watts) <= PowerScale.DRAW_CAP_W)
            direction = Direction.DRAW_INFERRED;
        boolean joined = !powerValues.isEmpty() && previousMs >= 0 && updatedMs - previousMs > 0 && updatedMs - previousMs <= gapMs(t);
        double previousW = powerValues.isEmpty() ? 0 : powerValues.peekLast()[0];
        Direction previousDirection = direction();
        append(powerSamples, powerValues, updatedMs, new double[]{watts, direction.ordinal()}, gapMs(t));
        peakDrawBeforeW = peakDrawW; peakRegenBeforeW = peakRegenW;
        scaleDrawBeforeW = scale.drawW; scaleRegenBeforeW = scale.regenW;
        lastSegmentPreviousW = previousW;
        lastSegmentPreviousDirection = previousDirection;
        applyPower(watts, direction, updatedMs, joined ? previousMs : -1, previousW, previousDirection);
    }
    /**
     * The inferred-draw threshold right now: 1,200 W, or 1.5 x the strongest confirmed regen this
     * ride when that's higher, so a rider who regenerates hard never has regen read as draw.
     */
    double inferredDrawThresholdW() { return Math.max(INFERRED_DRAW_MIN_W, INFERRED_DRAW_REGEN_FACTOR * peakRegenW); }

    /** A sample's watts as riding mode counts them: signed when confirmed, magnitude (as draw) otherwise. */
    private static double counted(double watts, Direction direction) { return direction.unsigned() ? Math.abs(watts) : watts; }

    /**
     * Energy since the previous sample (when joined), peaks and scale for one power sample. A
     * segment with an unknown end adds both ends' magnitudes, as draw, and counts as unconfirmed.
     * So does one between inferred draw and confirmed regen: the direction changed at a moment
     * nobody measured (and the regen may be stronger than the threshold the draw was inferred
     * under). A segment between inferred draw and inferred or confirmed draw counts as inferred
     * draw; both ends are positive, so it is never negative.
     */
    private void applyPower(double watts, Direction direction, long updatedMs, long previousMs, double previousW, Direction previousDirection) {
        lastSegmentPreviousMs = previousMs;
        boolean anyInferred = direction == Direction.DRAW_INFERRED || previousDirection == Direction.DRAW_INFERRED;
        boolean unknown = direction == Direction.UNKNOWN || previousDirection == Direction.UNKNOWN
                || anyInferred && (direction == Direction.REGEN || previousDirection == Direction.REGEN);
        boolean inferred = !unknown && anyInferred;
        double average = unknown ? (Math.abs(previousW) + Math.abs(watts)) / 2 : (counted(previousW, previousDirection) + counted(watts, direction)) / 2;
        lastSegmentWh = previousMs < 0 ? 0 : average * (updatedMs - previousMs) / 3_600_000.0;
        lastSegmentUnconfirmedWh = unknown ? lastSegmentWh : 0;
        lastSegmentInferredWh = inferred ? lastSegmentWh : 0;
        rideEnergyWh += lastSegmentWh;
        unconfirmedEnergyWh += lastSegmentUnconfirmedWh;
        inferredDrawWh += lastSegmentInferredWh;
        // Peaks ignore the same decoding glitches the scale ignores. Inferred draw is draw here too.
        if (direction == Direction.REGEN && -watts <= PowerScale.REGEN_CAP_W) peakRegenW = Math.max(peakRegenW, -watts);
        else if (direction == Direction.DRAW && watts <= PowerScale.DRAW_CAP_W) peakDrawW = Math.max(peakDrawW, watts);
        else if (direction == Direction.DRAW_INFERRED && Math.abs(watts) <= PowerScale.DRAW_CAP_W) peakDrawW = Math.max(peakDrawW, Math.abs(watts));
        // An unknown or inferred direction still sizes the draw scale: the bar shows its magnitude.
        scaleChanged |= scale.observe(watts, direction == Direction.REGEN ? Direction.REGEN : Direction.DRAW);
    }

    /**
     * Ride time follows the scooter's trip: anchored from trip_minutes, then counted on the phone's clock.
     * The scooter's whole-minute counter can stand still while its reports keep coming (a stop, a slow
     * scooter clock), so a count ahead of it is kept: pulling it back pinned ride time at the next whole
     * minute (owner, Nov 7: frozen at 2:00 or 3:00). A count behind the scooter's moves forward to it. A
     * lower count, a higher one than the time since the last report allows, or a drop in the trip's
     * distance or top speed (tripEvidence) is a new trip. A dropped and restored link alone is not.
     *
     * Still ambiguous: a power cycle within TRIP_RESUME_MS that shows the same minute before the old trip
     * had moved (distance and top speed 0 on both sides) carries on the old count, at most a few minutes.
     */
    private void tripMinutes(double minutes, long updatedMs, Track t) {
        if (minutes < 0) return;
        if (tripStartMs == null && resumeTrip != null) {
            tripStartMs = resumeTrip.startMs; tripMinutesSeen = resumeTrip.minutes; tripSeenMs = resumeTrip.seenMs;
        }
        resumeTrip = null;
        long whole = (long) Math.floor(minutes), latest = updatedMs - whole * 60_000L;
        long tolerance = (long) Math.max(2000, t.intervalMs), gap = updatedMs - tripSeenMs;
        boolean sameTrip = tripStartMs != null && !tripRestarted && minutes >= tripMinutesSeen && gap >= 0 && gap <= TRIP_RESUME_MS
                && (whole - (long) Math.floor(tripMinutesSeen)) * 60_000L <= gap + 60_000L + tolerance;
        tripRestarted = false;
        // A new trip's marks start over (this report's distance and top speed land again with the next one).
        if (!sameTrip && tripStartMs != null) tripKmMark = tripMaxMark = Double.NaN;
        tripStartMs = sameTrip ? Math.min(tripStartMs, latest) : latest;
        tripMinutesSeen = minutes;
        tripSeenMs = updatedMs;
    }

    /**
     * The trip's distance or top speed. Falling to 0, or by a whole unit (whole-unit v0 reports round down),
     * means the scooter started a new trip: drop any carried clock and re-anchor on this report's minute,
     * or on the next one if it hasn't arrived yet.
     */
    private void tripEvidence(boolean km, double value, long updatedMs) {
        double mark = km ? tripKmMark : tripMaxMark;
        if (!Double.isNaN(mark) && value < mark && (value == 0 || mark - value >= 1)) {
            resumeTrip = null;
            if (tripStartMs != null && tripSeenMs == updatedMs) tripStartMs = updatedMs - (long) Math.floor(tripMinutesSeen) * 60_000L;
            else tripRestarted = true;
            tripKmMark = tripMaxMark = Double.NaN;
        }
        if (km) tripKmMark = Double.isNaN(tripKmMark) ? value : Math.max(tripKmMark, value);
        else tripMaxMark = Double.isNaN(tripMaxMark) ? value : Math.max(tripMaxMark, value);
    }

    /** Longest silence after which a trip report still continues the same count (rather than re-anchoring). */
    static final long TRIP_RESUME_MS = 5 * 60_000L;

    /** Ride time's anchor, carried from one riding session to the next so reopening doesn't snap it back. */
    static final class TripClock {
        final long startMs, seenMs;
        /** kmMark and maxSpeedMark are the trip's distance and top speed so far, NaN when unknown. */
        final double minutes, kmMark, maxSpeedMark;
        TripClock(long startMs, double minutes, long seenMs, double kmMark, double maxSpeedMark) {
            this.startMs = startMs; this.minutes = minutes; this.seenMs = seenMs; this.kmMark = kmMark; this.maxSpeedMark = maxSpeedMark;
        }
    }
    /** This session's ride-time anchor, or null before a trip report. */
    TripClock tripClock() { return tripStartMs == null ? null : new TripClock(tripStartMs, tripMinutesSeen, tripSeenMs, tripKmMark, tripMaxMark); }
    /**
     * Offers a previous session's anchor, before this session's first reading. The first trip report keeps
     * it only if it is the same trip, and a drop in distance or top speed from its marks drops it.
     */
    void resumeTrip(TripClock clock) {
        if (tripStartMs != null || clock == null) return;
        resumeTrip = clock;
        if (Double.isNaN(tripKmMark)) tripKmMark = clock.kmMark;
        if (Double.isNaN(tripMaxMark)) tripMaxMark = clock.maxSpeedMark;
    }

    private static void append(ArrayDeque<long[]> times, ArrayDeque<double[]> values, long t, double[] v, long gapMs) {
        boolean gap = !times.isEmpty() && t - times.peekLast()[0] > gapMs;
        times.addLast(new long[]{t, gap ? 1 : 0});
        values.addLast(v);
        while (!times.isEmpty() && t - times.peekFirst()[0] > TRACE_KEEP_MS) { times.removeFirst(); values.removeFirst(); }
    }

    /** The latest reading of a key, with its interval and staleness, or null before the first. */
    Reading reading(String key, long nowMs) {
        Track t = tracks.get(key);
        if (t == null || t.updatedMs < 0) return null;
        return new Reading(t.value, t.updatedMs, Math.round(t.intervalMs), Math.max(0, nowMs - t.updatedMs), t.sourceCmd);
    }
    /** The measured interval of a key, or its nominal before any reading. */
    long intervalMs(String key) { Track t = tracks.get(key); return t == null ? nominal(key) : Math.round(t.intervalMs); }

    Speed speed(long nowMs) {
        Reading r = reading(SPEED_KEY, nowMs);
        if (r == null) return new Speed(null, null, false, true);
        boolean gone = r.ageMs > SPEED_GONE_MS;
        return new Speed(gone ? null : r.value, lastSpeedKmh, lastSpeedTenths, r.stale || gone);
    }

    /** True while the latest 0x79 sign state is a known 0 or 1. */
    private boolean signKnown() {
        Track sign = tracks.get(SIGN_KEY);
        return sign != null && (sign.value == 0 || sign.value == 1);
    }
    private Direction signDirection(double watts) { return !signKnown() ? Direction.UNKNOWN : watts < 0 ? Direction.REGEN : Direction.DRAW; }

    /**
     * The direction of the latest power reading, as stored when it was measured (or corrected).
     * A later sign never reinterprets it. UNKNOWN before any power or without a 0x79 sign;
     * DRAW_INFERRED for a strong reading without one.
     */
    Direction direction() {
        return powerValues.isEmpty() ? Direction.UNKNOWN : Direction.values()[(int) powerValues.peekLast()[1]];
    }
    /** The direction a new power reading would be read under: UNKNOWN until the 0x79 sign is a known 0 or 1. */
    Direction signState() { return signDirection(0); }

    Power power(long nowMs) {
        Reading r = reading(POWER_KEY, nowMs);
        Direction direction = direction();
        Double watts = r == null || r.stale ? null : counted(r.value, direction);
        return new Power(watts, direction, peakDrawW, peakRegenW);
    }

    PowerScale scale() { return scale; }
    /** True once since the last call when the scale grew, so its owner saves it. */
    boolean takeScaleChanged() { boolean changed = scaleChanged; scaleChanged = false; return changed; }

    static Band band(double percent) { return percent < BATTERY_RED_BELOW ? Band.RED : percent < BATTERY_AMBER_BELOW ? Band.AMBER : Band.GREEN; }
    /** Battery band, or null before the first reading. */
    Band batteryBand(long nowMs) { Reading r = reading(BATTERY_KEY, nowMs); return r == null ? null : band(r.value); }

    /**
     * Net ride energy in Wh: signed where both ends of a stretch had a known direction, magnitude
     * (as draw) where either was unknown, and inferred draw counted as draw. unconfirmedEnergyWh()
     * is the magnitude part and inferredDrawWh() the inferred part, so a consumer can take the
     * confirmed net as rideEnergyWh() - unconfirmedEnergyWh() - inferredDrawWh(), or the known-or-
     * inferred net as rideEnergyWh() - unconfirmedEnergyWh().
     */
    double rideEnergyWh() { return rideEnergyWh; }
    /** The part of rideEnergyWh() integrated with an unknown direction: magnitude, never negative. Excludes inferred draw. */
    double unconfirmedEnergyWh() { return unconfirmedEnergyWh; }
    /** The part of rideEnergyWh() from stretches between inferred draw and inferred or confirmed draw: never negative. */
    double inferredDrawWh() { return inferredDrawWh; }

    /**
     * Wh per km over the last 30 s. Power and speed each hold their value until the next sample,
     * at most one gap allowance. Energy and distance are both integrated only where both signals
     * are held, and that shared time must cover 80 % of the window. The direction is confirmed
     * only when every power sample in that shared time was measured under a known sign; a sample
     * without one counts its magnitude, so an unconfirmed old sign never reads as a regen gain.
     * Known-or-inferred also accepts inferred-draw samples.
     */
    Efficiency efficiency(long nowMs) {
        long from = nowMs - EFFICIENCY_WINDOW_MS;
        Track pt = tracks.get(POWER_KEY), st = tracks.get(SPEED_KEY);
        if (pt == null || st == null) return new Efficiency(null, 0, false, false);
        List<double[]> power = held(powerSamples, powerValues, from, nowMs, gapMs(pt));
        List<double[]> speed = held(speedSamples, speedValues, from, nowMs, gapMs(st));
        double energy = 0, distance = 0, covered = 0;
        boolean confirmed = true, knownOrInferred = true;
        for (int i = 0, j = 0; i < power.size() && j < speed.size(); ) {
            double[] p = power.get(i), s = speed.get(j);
            double a = Math.max(p[0], s[0]), b = Math.min(p[1], s[1]);
            if (b > a) {
                Direction d = Direction.values()[(int) p[3]];
                energy += counted(p[2], d) * (b - a); distance += s[2] * (b - a); covered += b - a;
                confirmed &= !d.unsigned();
                knownOrInferred &= d.knownOrInferred();
            }
            if (p[1] <= s[1]) i++; else j++;
        }
        confirmed &= covered > 0;
        knownOrInferred &= covered > 0;
        double coverage = covered / EFFICIENCY_WINDOW_MS;
        double km = distance / 3_600_000.0, wh = energy / 3_600_000.0;
        if (coverage < EFFICIENCY_COVERAGE || km < EFFICIENCY_MIN_KM) return new Efficiency(null, coverage, confirmed, knownOrInferred);
        return new Efficiency(wh / km, coverage, confirmed, knownOrInferred);
    }

    /**
     * The held stretches of a sample-and-hold signal inside [from, to], oldest first, as
     * {start, end, value, extra}: extra is the sample's second field (the power direction) or -1.
     */
    private static List<double[]> held(ArrayDeque<long[]> times, ArrayDeque<double[]> values, long from, long to, long holdMs) {
        List<double[]> out = new ArrayList<>();
        Iterator<long[]> ti = times.iterator();
        Iterator<double[]> vi = values.iterator();
        long[] previous = null;
        double[] previousValue = null;
        while (true) {
            long[] next = ti.hasNext() ? ti.next() : null;
            double[] nextValue = next == null ? null : vi.next();
            if (previous != null) {
                long end = Math.min(next == null ? to : next[0], previous[0] + holdMs);
                long a = Math.max(from, previous[0]), b = Math.min(to, end);
                if (b > a) out.add(new double[]{a, b, previousValue[0], previousValue.length > 1 ? previousValue[1] : -1});
            }
            if (next == null) break;
            previous = next; previousValue = nextValue;
        }
        return out;
    }

    /** Speed (km/h) points for the trace, oldest first, within the window. */
    List<TracePoint> speedTrace(long nowMs, long windowMs) { return trace(speedSamples, speedValues, nowMs, windowMs); }
    /** Power (W) points for the trace: each signed only when its own sample's direction was confirmed (inferred draw is positive). */
    List<TracePoint> powerTrace(long nowMs, long windowMs) { return trace(powerSamples, powerValues, nowMs, windowMs); }
    private static List<TracePoint> trace(ArrayDeque<long[]> times, ArrayDeque<double[]> values, long nowMs, long windowMs) {
        List<TracePoint> out = new ArrayList<>();
        Iterator<double[]> vi = values.iterator();
        boolean first = true;
        for (long[] t : times) {
            double[] sample = vi.next();
            Direction d = sample.length > 1 ? Direction.values()[(int) sample[1]] : null;
            double v = d != null ? counted(sample[0], d) : sample[0];
            if (t[0] < nowMs - windowMs || t[0] > nowMs) continue;
            out.add(new TracePoint(t[0], v, !first && t[1] == 1, d == Direction.UNKNOWN));
            first = false;
        }
        return out;
    }

    /** The trip spot: the recording's distance while recording, otherwise the scooter's trip (Q15 B). */
    static final class Trip {
        final Double km;
        /** Labels it "Ride" rather than "Trip". */
        final boolean fromRecording;
        private Trip(Double km, boolean fromRecording) { this.km = km; this.fromRecording = fromRecording; }
    }
    Trip trip(Double recordingKm, long nowMs) {
        if (recordingStartMs != null && recordingKm != null && Double.isFinite(recordingKm)) return new Trip(recordingKm, true);
        Reading r = reading("trip_km", nowMs);
        return new Trip(r == null ? null : r.value, false);
    }

    /** Ride time: since the scooter's trip started, or null before trip_minutes arrives. */
    Long rideTimeMs(long nowMs) { return tripStartMs == null ? null : Math.max(0, nowMs - tripStartMs); }

    /** Recording time is separate from ride time (owner): a rider may record only part of a ride. */
    void setRecordingStart(Long startMs) { recordingStartMs = startMs; }
    Long recordingTimeMs(long nowMs) { return recordingStartMs == null ? null : Math.max(0, nowMs - recordingStartMs); }
}
