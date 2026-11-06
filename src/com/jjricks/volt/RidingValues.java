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

    static final String SPEED_KEY = "speed_kmh", POWER_KEY = "power_w", SIGN_KEY = "current_sign_state",
            TRIP_MINUTES_KEY = "trip_minutes", BATTERY_KEY = "battery_percent", SCOOTER_RANGE_KEY = "remaining_range_km";
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
    enum Direction { DRAW, REGEN, UNKNOWN }

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
        /** False when regen couldn't be told from draw for part of the window. */
        final boolean directionConfirmed;
        private Efficiency(Double whPerKm, double coverage, boolean directionConfirmed) { this.whPerKm = whPerKm; this.coverage = coverage; this.directionConfirmed = directionConfirmed; }
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
        private TracePoint(long t, double value, boolean gapBefore) { this.t = t; this.value = value; this.gapBefore = gapBefore; }
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
    private final ArrayDeque<long[]> speedSamples = new ArrayDeque<>(), powerSamples = new ArrayDeque<>();
    private final ArrayDeque<double[]> speedValues = new ArrayDeque<>(), powerValues = new ArrayDeque<>();
    private final PowerScale scale;
    private boolean scaleChanged;
    private double rideEnergyWh, peakDrawW, peakRegenW;
    private Double lastSpeedKmh;
    private boolean lastSpeedTenths;
    private Long recordingStartMs;
    /** Monotonic time the scooter's trip started, estimated from trip_minutes. */
    private Long tripStartMs;
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

    /** Feeds every reading in a Telemetry snapshot. Readings already seen are skipped. */
    void update(List<Telemetry.Metric> metrics) {
        // The sign first, so power in the same snapshot is read with the direction it was measured under.
        for (int pass = 0; pass < 2; pass++)
            for (Telemetry.Metric m : metrics)
                if (m.numericValue != null && m.updatedMs >= 0 && KEYS.contains(m.key) && (pass == 0) == m.key.equals(SIGN_KEY))
                    sample(m.key, m.numericValue, m.sourceCmd, m.updatedMs);
    }

    /** One reading. A sample no newer than the last one for its key is ignored. */
    void sample(String key, double value, int sourceCmd, long updatedMs) {
        if (!KEYS.contains(key) || !Double.isFinite(value) || updatedMs < 0) return;
        Track t = tracks.get(key);
        if (t == null) { t = new Track(); t.nominalMs = nominal(key); t.intervalMs = t.nominalMs; tracks.put(key, t); }
        else if (updatedMs <= t.updatedMs) return;
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
            append(speedSamples, speedValues, updatedMs, value, gapMs(t));
        } else if (key.equals(POWER_KEY)) {
            powerSample(value, updatedMs, previous, t);
        } else if (key.equals(TRIP_MINUTES_KEY)) {
            tripMinutes(value, updatedMs, t);
        }
    }
    private static double clampInterval(double ms) { return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, ms)); }
    private static long gapMs(Track t) { return Math.max(MIN_GAP_MS, (long) (GAP_INTERVALS * Math.max(t.intervalMs, t.nominalMs))); }

    private void powerSample(double watts, long updatedMs, long previousMs, Track t) {
        Direction direction = direction();
        if (!powerValues.isEmpty()) {
            double previousW = powerValues.peekLast()[0];
            long gap = updatedMs - previousMs;
            if (previousMs >= 0 && gap > 0 && gap <= gapMs(t)) rideEnergyWh += (previousW + watts) / 2 * gap / 3_600_000.0;
        }
        append(powerSamples, powerValues, updatedMs, watts, gapMs(t));
        // Peaks ignore the same decoding glitches the scale ignores.
        if (direction == Direction.REGEN && -watts <= PowerScale.REGEN_CAP_W) peakRegenW = Math.max(peakRegenW, -watts);
        else if (direction == Direction.DRAW && watts <= PowerScale.DRAW_CAP_W) peakDrawW = Math.max(peakDrawW, watts);
        // An unknown direction still sizes the draw scale: the bar shows its magnitude.
        scaleChanged |= scale.observe(watts, direction == Direction.UNKNOWN ? Direction.DRAW : direction);
    }

    /** Ride time follows the scooter's trip: anchored from trip_minutes, and refined when the minute ticks over. */
    private void tripMinutes(double minutes, long updatedMs, Track t) {
        if (minutes < 0) return;
        long whole = (long) Math.floor(minutes), earliest = updatedMs - (whole + 1) * 60_000L, latest = updatedMs - whole * 60_000L;
        long tolerance = (long) Math.max(2000, t.intervalMs);
        if (tripStartMs == null || tripStartMs < earliest - tolerance || tripStartMs > latest + tolerance) tripStartMs = latest;
        else tripStartMs = Math.max(earliest, Math.min(latest, tripStartMs));
    }

    private static void append(ArrayDeque<long[]> times, ArrayDeque<double[]> values, long t, double v, long gapMs) {
        boolean gap = !times.isEmpty() && t - times.peekLast()[0] > gapMs;
        times.addLast(new long[]{t, gap ? 1 : 0});
        values.addLast(new double[]{v});
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

    /** Regen only when the current's direction is confirmed by the 0x79 sign state. */
    Direction direction() {
        Track sign = tracks.get(SIGN_KEY);
        Track power = tracks.get(POWER_KEY);
        if (sign == null || (sign.value != 0 && sign.value != 1)) return Direction.UNKNOWN;
        return power != null && power.value < 0 ? Direction.REGEN : Direction.DRAW;
    }

    Power power(long nowMs) {
        Reading r = reading(POWER_KEY, nowMs);
        Direction direction = direction();
        Double watts = r == null || r.stale ? null : direction == Direction.UNKNOWN ? Math.abs(r.value) : r.value;
        return new Power(watts, direction, peakDrawW, peakRegenW);
    }

    PowerScale scale() { return scale; }
    /** True once since the last call when the scale grew, so its owner saves it. */
    boolean takeScaleChanged() { boolean changed = scaleChanged; scaleChanged = false; return changed; }

    static Band band(double percent) { return percent < BATTERY_RED_BELOW ? Band.RED : percent < BATTERY_AMBER_BELOW ? Band.AMBER : Band.GREEN; }
    /** Battery band, or null before the first reading. */
    Band batteryBand(long nowMs) { Reading r = reading(BATTERY_KEY, nowMs); return r == null ? null : band(r.value); }

    double rideEnergyWh() { return rideEnergyWh; }

    /**
     * Wh per km over the last 30 s. Power and speed each hold their value until the next sample,
     * at most one gap allowance; the result needs both to cover 80 % of the window.
     */
    Efficiency efficiency(long nowMs) {
        long from = nowMs - EFFICIENCY_WINDOW_MS;
        Track pt = tracks.get(POWER_KEY), st = tracks.get(SPEED_KEY);
        if (pt == null || st == null) return new Efficiency(null, 0, direction() != Direction.UNKNOWN);
        double[] energy = integrate(powerSamples, powerValues, from, nowMs, gapMs(pt));
        double[] distance = integrate(speedSamples, speedValues, from, nowMs, gapMs(st));
        double coverage = Math.min(energy[1], distance[1]) / EFFICIENCY_WINDOW_MS;
        boolean confirmed = direction() != Direction.UNKNOWN;
        double km = distance[0] / 3_600_000.0, wh = energy[0] / 3_600_000.0;
        if (coverage < EFFICIENCY_COVERAGE || km < EFFICIENCY_MIN_KM) return new Efficiency(null, coverage, confirmed);
        return new Efficiency(wh / km, coverage, confirmed);
    }

    /** {sum of value x ms, covered ms} for a sample-and-hold signal inside [from, to]. */
    private static double[] integrate(ArrayDeque<long[]> times, ArrayDeque<double[]> values, long from, long to, long holdMs) {
        double sum = 0, covered = 0;
        Iterator<long[]> ti = times.iterator();
        Iterator<double[]> vi = values.iterator();
        long[] previous = null;
        double previousValue = 0;
        while (true) {
            long[] next = ti.hasNext() ? ti.next() : null;
            double nextValue = next == null ? 0 : vi.next()[0];
            if (previous != null) {
                long end = Math.min(next == null ? to : next[0], previous[0] + holdMs);
                long a = Math.max(from, previous[0]), b = Math.min(to, end);
                if (b > a) { sum += previousValue * (b - a); covered += b - a; }
            }
            if (next == null) break;
            previous = next; previousValue = nextValue;
        }
        return new double[]{sum, covered};
    }

    /** Speed (km/h) points for the trace, oldest first, within the window. */
    List<TracePoint> speedTrace(long nowMs, long windowMs) { return trace(speedSamples, speedValues, nowMs, windowMs); }
    /** Power (W) points for the trace, signed only when the direction is confirmed. */
    List<TracePoint> powerTrace(long nowMs, long windowMs) {
        List<TracePoint> raw = trace(powerSamples, powerValues, nowMs, windowMs);
        if (direction() != Direction.UNKNOWN) return raw;
        List<TracePoint> out = new ArrayList<>(raw.size());
        for (TracePoint p : raw) out.add(new TracePoint(p.t, Math.abs(p.value), p.gapBefore));
        return out;
    }
    private static List<TracePoint> trace(ArrayDeque<long[]> times, ArrayDeque<double[]> values, long nowMs, long windowMs) {
        List<TracePoint> out = new ArrayList<>();
        Iterator<double[]> vi = values.iterator();
        boolean first = true;
        for (long[] t : times) {
            double v = vi.next()[0];
            if (t[0] < nowMs - windowMs || t[0] > nowMs) continue;
            out.add(new TracePoint(t[0], v, !first && t[1] == 1));
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
