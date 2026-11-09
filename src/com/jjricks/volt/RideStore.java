package com.jjricks.volt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Offline ride journal and conservative analysis. No Android or network dependency.
 * Every sample is a committed CSV frame: interrupted writes are ignored on recovery.
 * V/A must be from the same report and <=3 seconds old. Missing data is never zero.
 * UNCONFIRMED integrates absolute electrical throughput, not battery consumption.
 */
public final class RideStore {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_SENSOR_AGE_MS = 3000;
    public static final long MAX_INTEGRATION_INTERVAL_MS = 3000;
    public static final double KM_PER_MILE = 1.609344;
    public static final int MAX_TREND_POINTS = 600;
    public static final double MIN_EFFICIENCY_DISTANCE_KM = 0.2;
    public static final double MIN_EFFICIENCY_COVERAGE = 0.8;
    /** STOP details token for a ride Volt ended after a parked Bluetooth loss. An empty value means
     *  the rider stopped it; Volt 1.3.1 and earlier ignore the column, so old builds still load it. */
    public static final String STOP_AUTO_PARKED = "AUTO_PARKED_DISCONNECT";
    /** STOP details token for a ride Volt ended after the scooter went quiet (switched off or out of
     *  range) or the phone's Bluetooth stayed off. The journal stores it with parameters:
     *  {@code AUTO_QUIET_DISCONNECT;last=<monotonic ms of the last real reading, -1 none>;code=<native
     *  disconnect code, -1 unknown>} and {@code ;phone_bt=off} when the phone's Bluetooth was off.
     *  Older builds read it as a rider's stop and ignore the parameters. */
    public static final String STOP_AUTO_QUIET = "AUTO_QUIET_DISCONNECT";
    /** STOP details token for a ride Volt ended because the connected scooter showed no motion for
     *  {@link RideAutoStopPolicy#NO_MOTION_MS} (rule 3). It takes no parameters: the shown end, the last
     *  motion, comes from the journal's own samples. Older builds read it as a rider's stop. */
    public static final String STOP_AUTO_NO_MOTION = "automatic_no_motion";
    private static final int MAX_STOP_DETAILS = 80;
    private static final int MAX_METRICS = 160;
    private static final String HEADER = "schema_version,event,recorded_utc,wall_ms,monotonic_ms,frame,key,numeric,text,unit,source_command,sensor_updated_monotonic_ms,sensor_age_ms,current_direction,details\n";

    public enum CurrentDirection { UNCONFIRMED, POSITIVE_IS_DRAW, NEGATIVE_IS_DRAW }
    /** Full detail must either fit in memory or fail explicitly, never silently thin data. */
    public static final class DetailMemoryException extends RuntimeException { DetailMemoryException() { super("Not enough memory to load all ride samples"); } }
    static void checkDetailMemory() {
        Runtime runtime = Runtime.getRuntime();
        long headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        long reserve = Math.min(32L * 1024 * 1024, runtime.maxMemory() / 8);
        if (headroom < reserve) throw new DetailMemoryException();
    }

    public static final class Sample {
        public final Double number;
        public final String text, unit;
        public final int sourceCmd;
        public final long updatedMonoMs;
        public Sample(Double number, String text, String unit, int sourceCmd, long updatedMonoMs) {
            this.number = finite(number) ? number : null;
            this.text = trim(text, 4096);
            this.unit = trim(unit, 80);
            this.sourceCmd = sourceCmd;
            this.updatedMonoMs = updatedMonoMs;
        }
        public Sample(double number, long updatedMonoMs) {
            this(number, null, null, -1, updatedMonoMs);
        }
    }

    public static final class Point {
        public final long wallMs, monotonicMs, elapsedMs;
        public final int segment;
        /** Journal-derived cumulative coverage at this point, excluding later partial readings. */
        final long speedCoverageMs;
        public final Double voltageV, powerW, speedKmh, batteryPercent, odometerKm, chargingState;
        private Point(long wallMs, long monotonicMs, long elapsedMs, int segment, Double voltageV, Double powerW,
                Double speedKmh, Double batteryPercent, Double odometerKm, Double chargingState, long speedCoverageMs) {
            this.wallMs = wallMs; this.monotonicMs = monotonicMs; this.elapsedMs = elapsedMs; this.segment = segment;
            this.voltageV = voltageV; this.powerW = powerW; this.speedKmh = speedKmh;
            this.batteryPercent = batteryPercent; this.odometerKm = odometerKm; this.chargingState = chargingState;
            this.speedCoverageMs = speedCoverageMs;
        }
        public Map<String, Object> fields() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("wallMs", wallMs); result.put("monotonicMs", monotonicMs); result.put("elapsedMs", elapsedMs); result.put("segment", segment);
            result.put("voltageV", voltageV); result.put("powerW", powerW);
            result.put("speedKmh", speedKmh); result.put("batteryPercent", batteryPercent);
            result.put("odometerKm", odometerKm); return Collections.unmodifiableMap(result);
        }
        public String toJson() { return json(fields()); }
    }

    /** Immutable, unit-explicit values; JSON null means insufficient evidence. */
    public static final class Summary {
        public final String id, scooterId, label, notes, currentDirection, energyInterpretation;
        /** START record clocks; with the scooter they identify a recording across UUIDs. */
        public final long startMonotonicMs;
        public final long startWallMs, endWallMs, elapsedMs, electricalCoverageMs,
                skippedElectricalMs, speedCoverageMs, movingMs, stationaryMs;
        public final long sampleCount, gapCount, clockDiscontinuities, directionChanges;
        public final boolean active, recovered, odometerReset, directionConfirmed;
        public final Double startBatteryPercent, endBatteryPercent, batteryDropPercent,
                startVoltageV, minimumVoltageV, maximumVoltageV, observedVoltageDropV,
                minimumTemperatureC, maximumTemperatureC, startOdometerKm, endOdometerKm,
                distanceKm, distanceMiles, speedIntegratedDistanceKm, averageSpeedKmh,
                movingAverageSpeedKmh, maximumSpeedKmh, averageAbsolutePowerW,
                peakAbsolutePowerW, maximumAbsoluteCurrentA, measuredThroughputWh,
                drawWh, regeneratedWh, returnedWh, netConsumptionWh, throughputWhPerKm,
                throughputWhPerMile, netWhPerKm, netWhPerMile;
        public final double electricalCoverage, speedCoverage;
        public final long confirmedDirectionCoverageMs, unconfirmedDirectionCoverageMs;
        /**
         * For a ride whose current is unsigned (PowerSign), the energy as a magnitude, drawn the
         * whole ride and with no regen ever measured; null otherwise. Not a confirmed Draw (the
         * summary's drawWh and netConsumptionWh are null then), but what learned range can still use.
         */
        final Double unsignedDrawWh;
        public final long movingElectricalCoverageMs, stationaryElectricalCoverageMs,
                unknownMotionElectricalCoverageMs;
        public final Double movingThroughputWh, stationaryThroughputWh, unknownMotionThroughputWh;
        public final String efficiencyStatus;
        /** Null while recording; "" for a rider's stop (and every older journal); otherwise a STOP token. */
        public final String stopReason;
        public final boolean automaticStop;
        /**
         * Volt ended it for no motion while connected ({@link RideStore#STOP_AUTO_NO_MOTION}). Kept apart
         * from {@link #automaticStop}, which means an end after a Bluetooth loss (the parked and quiet
         * rules); a screen showing any automatic end checks both. The analysis JSON names it by its token.
         */
        public final boolean noMotionStop;
        /**
         * What a rider sees as the end. For an automatic stop it is the last real reading (any fresh
         * scooter value), so the silent link, gap and wait after it never count; otherwise, and when
         * the ride has no reading, it equals {@link #endWallMs}, {@link #elapsedMs} and
         * {@link #gapCount}. Integrity checks and exports keep using the raw values.
         */
        public final long shownEndWallMs, shownElapsedMs, shownGapCount;
        /** Parameters of an {@link RideStore#STOP_AUTO_QUIET} stop: -1 when absent or unreadable. */
        public final long stopLastReadingMonotonicMs;
        public final int stopDisconnectCode;
        public final boolean stopPhoneBluetoothOff;
        private Summary(Accumulator a) {
            id = a.id; scooterId = a.scooterId; label = a.label; notes = a.notes;
            currentDirection = a.direction.name(); startWallMs = a.startWall; startMonotonicMs = a.startMono;
            endWallMs = a.lastWall; elapsedMs = a.elapsed;
            electricalCoverageMs = a.electricalMs;
            skippedElectricalMs = Math.max(0, a.elapsed - a.electricalMs);
            speedCoverageMs = a.speedMs; movingMs = a.movingMs;
            stationaryMs = a.stationaryMs; sampleCount = a.samples;
            gapCount = a.gaps; clockDiscontinuities = a.clockDiscontinuities; directionChanges = a.directionChanges;
            active = !a.stopped; recovered = a.recovered; odometerReset = a.odometerReset;
            String details = a.stopped ? a.stopReason : null;
            boolean quiet = isQuietStop(details);
            Map<String, String> params = quiet ? stopParameters(details) : Collections.<String, String>emptyMap();
            stopReason = quiet ? STOP_AUTO_QUIET : details;
            noMotionStop = STOP_AUTO_NO_MOTION.equals(stopReason);
            automaticStop = quiet || STOP_AUTO_PARKED.equals(stopReason);
            long last = parseLong(params.get("last"), -1);
            stopLastReadingMonotonicMs = last < -1 ? -1 : last;
            stopDisconnectCode = disconnectCode(params);
            stopPhoneBluetoothOff = "off".equals(params.get("phone_bt"));
            boolean trim = (automaticStop || noMotionStop) && a.readingElapsed >= 0 && a.readingElapsed <= a.elapsed;
            // A no-motion stop ends at its last motion, so the ten idle minutes never count; with no motion at all, as other automatic stops.
            boolean still = noMotionStop && a.motionElapsed >= 0 && a.motionElapsed <= a.elapsed;
            shownEndWallMs = still ? a.motionWall : trim ? a.readingWall : a.lastWall;
            shownElapsedMs = still ? a.motionElapsed : trim ? a.readingElapsed : a.elapsed;
            shownGapCount = still ? a.motionGaps : trim ? a.readingGaps : a.gaps;
            // An unsigned current (PowerSign) has no measured direction, whatever convention the ride was recorded under.
            // Time recorded while the sign state wasn't 0 or 1, and never settled by a 0 or 1 (see Accumulator.heldMs):
            // the whole-ride rule decides it. A negative current means a real sign bit; otherwise it is unsigned.
            boolean signedHeld = a.currentSign.signedRide();
            confirmedDirectionCoverageMs = a.confirmedMs + (signedHeld ? a.heldMs : 0);
            unconfirmedDirectionCoverageMs = a.unconfirmedMs + (signedHeld ? 0 : a.heldMs);
            movingElectricalCoverageMs = a.movingElectricalMs;
            stationaryElectricalCoverageMs = a.stationaryElectricalMs;
            unknownMotionElectricalCoverageMs = a.unknownMotionElectricalMs;
            movingThroughputWh = a.movingElectricalMs == 0 ? null : a.movingThroughputWh;
            stationaryThroughputWh = a.stationaryElectricalMs == 0 ? null : a.stationaryThroughputWh;
            unknownMotionThroughputWh = a.unknownMotionElectricalMs == 0 ? null : a.unknownMotionThroughputWh;
            directionConfirmed = confirmedDirectionCoverageMs > 0 && unconfirmedDirectionCoverageMs == 0;
            unsignedDrawWh = !signedHeld && a.heldMs > 0 && a.unconfirmedMs == 0 ? a.heldDrawWh - a.heldRegeneratedWh : null;
            startBatteryPercent = a.startSoc; endBatteryPercent = a.endSoc;
            batteryDropPercent = subtract(a.startSoc, a.endSoc);
            startVoltageV = a.startVoltage; minimumVoltageV = a.minVoltage;
            maximumVoltageV = a.maxVoltage;
            Double voltageDrop = subtract(a.startVoltage, a.minVoltage);
            observedVoltageDropV = voltageDrop == null ? null : Math.max(0, voltageDrop);
            minimumTemperatureC = a.minTemperature; maximumTemperatureC = a.maxTemperature;
            startOdometerKm = a.startOdo; endOdometerKm = a.endOdo;
            Double odoDelta = subtract(a.endOdo, a.startOdo);
            distanceKm = !a.odometerReset && odoDelta != null && odoDelta >= 0 ? odoDelta : null;
            distanceMiles = distanceKm == null ? null : distanceKm / KM_PER_MILE;
            speedIntegratedDistanceKm = a.speedMs == 0 ? null : a.speedDistance;
            averageSpeedKmh = a.speedMs == 0 ? null : a.speedDistance * 3600000 / a.speedMs;
            movingAverageSpeedKmh = a.movingMs == 0 ? null : a.movingDistance * 3600000 / a.movingMs;
            maximumSpeedKmh = a.maxSpeed;
            averageAbsolutePowerW = a.electricalMs == 0 ? null : a.throughputWh * 3600000 / a.electricalMs;
            peakAbsolutePowerW = a.peakPower; maximumAbsoluteCurrentA = a.maxCurrent;
            measuredThroughputWh = a.electricalMs == 0 ? null : a.throughputWh;
            double confirmedDrawWh = a.drawWh + (signedHeld ? a.heldDrawWh : 0), confirmedRegenWh = a.regeneratedWh + (signedHeld ? a.heldRegeneratedWh : 0);
            drawWh = confirmedDirectionCoverageMs == 0 ? null : confirmedDrawWh;
            regeneratedWh = confirmedDirectionCoverageMs == 0 ? null : confirmedRegenWh;
            returnedWh = regeneratedWh;
            netConsumptionWh = directionConfirmed ? confirmedDrawWh - confirmedRegenWh : null;
            energyInterpretation = !directionConfirmed
                    ? "Absolute measured electrical throughput. Current direction is not confirmed for the full recording."
                    : "Configured current direction. Battery input can include charger input; it is not proof of regenerative braking.";
            electricalCoverage = a.elapsed == 0 ? 0 : Math.min(1, a.electricalMs / (double) a.elapsed);
            speedCoverage = a.elapsed == 0 ? 0 : Math.min(1, a.speedMs / (double) a.elapsed);
            boolean adequateDistance = distanceKm != null && distanceKm + 1e-9 >= MIN_EFFICIENCY_DISTANCE_KM;
            boolean adequateCoverage = electricalCoverage >= MIN_EFFICIENCY_COVERAGE;
            throughputWhPerKm = adequateDistance && adequateCoverage && measuredThroughputWh != null
                    ? measuredThroughputWh / distanceKm : null;
            throughputWhPerMile = throughputWhPerKm == null ? null : throughputWhPerKm * KM_PER_MILE;
            netWhPerKm = adequateDistance && adequateCoverage && netConsumptionWh != null
                    ? netConsumptionWh / distanceKm : null;
            netWhPerMile = netWhPerKm == null ? null : netWhPerKm * KM_PER_MILE;
            efficiencyStatus = odometerReset ? "Odometer changed backwards; distance unavailable"
                    : !adequateDistance ? "Record at least 0.2 km of odometer distance"
                    : !adequateCoverage ? "Electrical coverage below 80%; efficiency unavailable"
                    : !directionConfirmed ? "Measured throughput per distance; current direction unconfirmed"
                    : "Measured net energy per distance; coverage is reported separately";
        }
        public Map<String, Object> fields() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("schemaVersion", SCHEMA_VERSION);
            m.put("id", id); m.put("scooterId", scooterId); m.put("label", label); m.put("notes", notes);
            m.put("currentDirection", currentDirection); m.put("directionConfirmed", directionConfirmed);
            m.put("active", active); m.put("recovered", recovered); m.put("odometerReset", odometerReset);
            m.put("startWallMs", startWallMs); m.put("endWallMs", endWallMs); m.put("elapsedMs", elapsedMs);
            m.put("sampleCount", sampleCount); m.put("gapCount", gapCount); m.put("directionChanges", directionChanges);
            m.put("clockDiscontinuities", clockDiscontinuities);
            m.put("electricalCoverageMs", electricalCoverageMs); m.put("skippedElectricalMs", skippedElectricalMs);
            m.put("electricalCoverage", electricalCoverage); m.put("speedCoverageMs", speedCoverageMs);
            m.put("speedCoverage", speedCoverage); m.put("movingMs", movingMs); m.put("stationaryMs", stationaryMs);
            m.put("confirmedDirectionCoverageMs", confirmedDirectionCoverageMs);
            m.put("unconfirmedDirectionCoverageMs", unconfirmedDirectionCoverageMs);
            m.put("movingElectricalCoverageMs", movingElectricalCoverageMs);
            m.put("stationaryElectricalCoverageMs", stationaryElectricalCoverageMs);
            m.put("unknownMotionElectricalCoverageMs", unknownMotionElectricalCoverageMs);
            m.put("movingThroughputWh", movingThroughputWh);
            m.put("stationaryThroughputWh", stationaryThroughputWh);
            m.put("unknownMotionThroughputWh", unknownMotionThroughputWh);
            m.put("startBatteryPercent", startBatteryPercent); m.put("endBatteryPercent", endBatteryPercent);
            m.put("batteryDropPercent", batteryDropPercent); m.put("startVoltageV", startVoltageV);
            m.put("minimumVoltageV", minimumVoltageV); m.put("maximumVoltageV", maximumVoltageV);
            m.put("observedVoltageDropV", observedVoltageDropV);
            m.put("minimumTemperatureC", minimumTemperatureC); m.put("maximumTemperatureC", maximumTemperatureC);
            m.put("startOdometerKm", startOdometerKm); m.put("endOdometerKm", endOdometerKm);
            m.put("distanceKm", distanceKm); m.put("distanceMiles", distanceMiles);
            m.put("speedIntegratedDistanceKm", speedIntegratedDistanceKm);
            m.put("averageSpeedKmh", averageSpeedKmh); m.put("movingAverageSpeedKmh", movingAverageSpeedKmh);
            m.put("maximumSpeedKmh", maximumSpeedKmh); m.put("averageAbsolutePowerW", averageAbsolutePowerW);
            m.put("peakAbsolutePowerW", peakAbsolutePowerW); m.put("maximumAbsoluteCurrentA", maximumAbsoluteCurrentA);
            m.put("measuredThroughputWh", measuredThroughputWh); m.put("drawWh", drawWh);
            m.put("regeneratedWh", regeneratedWh); m.put("netConsumptionWh", netConsumptionWh);
            m.put("returnedWh", returnedWh); m.put("energyInterpretation", energyInterpretation);
            m.put("throughputWhPerKm", throughputWhPerKm); m.put("throughputWhPerMile", throughputWhPerMile);
            m.put("netWhPerKm", netWhPerKm); m.put("netWhPerMile", netWhPerMile);
            m.put("efficiencyStatus", efficiencyStatus);
            m.put("stopReason", stopReason == null ? null : STOP_AUTO_QUIET.equals(stopReason) ? "automatic_quiet_disconnect"
                    : automaticStop ? "automatic_parked_disconnect" : stopReason.isEmpty() ? "rider" : stopReason);
            m.put("shownEndWallMs", shownEndWallMs); m.put("shownElapsedMs", shownElapsedMs);
            return Collections.unmodifiableMap(m);
        }
        public String toJson() { return json(fields()); }
    }

    private final File directory;
    private Accumulator active;
    private CurrentDirection defaultDirection = CurrentDirection.UNCONFIRMED;
    /** Unfinished or recovered journals only; a finished journal's summary lives in analysisCache. */
    private final LinkedHashMap<String, CachedSummary> summaryCache = new LinkedHashMap<String, CachedSummary>(16, .75f, true);
    /** Consistent immutable summary/trend pair for off-thread display analysis. */
    public static final class AnalysisSnapshot {
        public final Summary summary;
        public final List<Point> points;
        private AnalysisSnapshot(Accumulator accumulator) {
            summary = new Summary(accumulator);
            points = trendPoints(accumulator, MAX_TREND_POINTS);
        }
        /** A saved summary with its saved preview (RideSummaryStore). */
        private AnalysisSnapshot(Summary summary, List<Point> points) { this.summary = summary; this.points = points; }
    }
    /**
     * One cache for every finished journal, keyed by its length and modified time: get(), list(), trend() and
     * analysisSnapshots() all read it, and the constructor fills it from the read it already does at service start.
     * Summaries are small and kept for every ride. Only the 600-point previews are capped: past PREVIEW_CAP, the least
     * recently used entries drop their preview and keep their summary, so a long history never rereads every ride.
     */
    private final LinkedHashMap<String, CachedAnalysis> analysisCache = new LinkedHashMap<>(16, .75f, true);
    static final int PREVIEW_CAP = 128;
    private int cachedPreviews;
    private static final class CachedAnalysis {
        final long length, modified;
        final Summary summary;
        /** Null once the preview is trimmed; the summary stays. */
        volatile AnalysisSnapshot snapshot;
        CachedAnalysis(long length, long modified, AnalysisSnapshot snapshot) {
            this.length = length; this.modified = modified; this.snapshot = snapshot; this.summary = snapshot.summary;
        }
        /** A summary read from the saved index; its preview stays on disk until a chart asks for it. */
        CachedAnalysis(long length, long modified, Summary summary) {
            this.length = length; this.modified = modified; this.snapshot = null; this.summary = summary;
        }
        boolean matches(long length, long modified) { return this.length == length && this.modified == modified; }
    }
    /** Stores a finished journal's analysis and trims the least recently used previews past PREVIEW_CAP. */
    private synchronized void putAnalysis(String id, CachedAnalysis entry) {
        CachedAnalysis old = analysisCache.put(id, entry);
        if (old != null && old.snapshot != null) cachedPreviews--;
        if (entry.snapshot != null) cachedPreviews++;
        if (cachedPreviews <= PREVIEW_CAP) return;
        for (CachedAnalysis eldest : analysisCache.values()) {
            if (cachedPreviews <= PREVIEW_CAP) break;
            if (eldest != entry && eldest.snapshot != null) { eldest.snapshot = null; cachedPreviews--; }
        }
    }
    private synchronized void forget(String id) {
        CachedAnalysis old = analysisCache.remove(id);
        if (old != null && old.snapshot != null) cachedPreviews--;
        summaryCache.remove(id);
    }
    private synchronized void retainCached(java.util.Set<String> existing) {
        summaryCache.keySet().retainAll(existing);
        analysisCache.keySet().retainAll(existing);
        cachedPreviews = 0;
        for (CachedAnalysis entry : analysisCache.values()) if (entry.snapshot != null) cachedPreviews++;
    }
    /** Package-private for tests: how many journal replays this process has done. */
    static final java.util.concurrent.atomic.AtomicLong replays = new java.util.concurrent.atomic.AtomicLong();
    /** Test-only: runs at the start of each replay (for example, to hold one open). Always null in the app. */
    static volatile Runnable replayHook;

    private static final class CachedSummary {
        final long length, modified;
        final Summary summary;
        CachedSummary(long length, long modified, Summary summary) {
            this.length = length; this.modified = modified; this.summary = summary;
        }
        boolean matches(long length, long modified) { return this.length == length && this.modified == modified; }
    }

    /**
     * Saved summaries (RideSummaryStore), or null: the in-memory cache alone, as before. Only finished journals are
     * saved, and an entry is used only while the journal's length and modified time still match.
     */
    private final RideSummaryStore index;

    public RideStore(File directory) throws IOException { this(directory, false); }
    public RideStore(File directory, boolean deferFinished) throws IOException { this(directory, deferFinished, null); }
    /**
     * deferFinished: replay only the journals that may be unfinished, so the service can start (and connect) without
     * reading every saved ride first. A journal whose tail is a STOP row followed by whole RENAME rows is finished and
     * is left for later; list() on a worker then reads it into the cache. Any other tail is replayed now, as before.
     *
     * indexDirectory: where finished rides' summaries are saved (no-backup storage; null for none). With deferFinished,
     * the index's own thread then seeds the cache from each deferred journal's saved entry that still matches (a few
     * hundred bytes, never the journal), so the first Rides open after a restart replays only new or changed rides.
     * The seed never runs on the caller's thread: the service start does exactly the work it did before.
     */
    public RideStore(File directory, boolean deferFinished, File indexDirectory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("Ride directory required");
        this.directory = directory;
        this.index = indexDirectory == null ? null : new RideSummaryStore(indexDirectory, MAX_TREND_POINTS);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create ride directory");
        // A process restart never bridges a lost interval with stale measurements.
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list ride directory");
        java.util.HashSet<String> journals = new java.util.HashSet<>();
        final ArrayList<File> deferred = new ArrayList<>();
        for (File file : files) if (isJournal(file)) {
            journals.add(journalId(file));
            if (deferFinished && endsFinished(file)) { deferred.add(file); continue; }
            long length = file.length(), modified = file.lastModified();
            Accumulator candidate = read(file);
            if (candidate == null) continue;
            // Keep this read: Rides opens from it instead of replaying every journal again.
            if (candidate.stopped) {
                if (file.length() == length && file.lastModified() == modified) cacheFinished(candidate.id, length, modified, candidate);
            } else if (active == null || candidate.startWall > active.startWall) active = candidate;
        }
        if (index != null) {
            if (!deferred.isEmpty()) index.submit(() -> { for (File file : deferred) seedSaved(file); });
            index.prune(journals);
        }
        if (active != null) {
            repairTail(file(active.id));
            append(file(active.id), row("RECOVER", active.lastWall, active.lastMono, "", "", "", "", "", "", "", "", "", "Recorder process restarted"));
            active.recover();
        }
    }

    /** Seeds the cache from a deferred (finished-looking) journal's saved summary when it matches. */
    private void seedSaved(File file) {
        String id = journalId(file);
        long length = file.length(), modified = file.lastModified();
        synchronized (this) {
            CachedAnalysis cached = analysisCache.get(id);
            if (cached != null && cached.matches(length, modified)) return;
        }
        readSaved(id, length, modified);
    }
    /**
     * A cache entry (summary only) from the index for this journal state, or null: no index, no matching entry, or a
     * damaged one. Callers check the journal ends finished first, so an unfinished journal is never served from disk.
     */
    private CachedAnalysis readSaved(String id, long length, long modified) {
        if (index == null) return null;
        byte[] state = index.readState(id, length, modified);
        if (state == null) return null;
        Accumulator saved;
        try { saved = decodeState(state, id); } catch (IOException | RuntimeException damaged) { return null; }
        if (saved == null) return null;
        CachedAnalysis entry = new CachedAnalysis(length, modified, new Summary(saved));
        putAnalysis(id, entry);
        index.markSaved(id, length, modified);
        return entry;
    }
    /** readSaved() for a journal a worker is about to replay, once its tail shows it finished. */
    private CachedAnalysis savedEntry(String id, File source, long length, long modified) {
        return index == null || !endsFinished(source) ? null : readSaved(id, length, modified);
    }
    /**
     * Caches a finished journal's analysis, read while its length and modified time stayed as given, and saves it to
     * the index unless that exact entry is already there. Encoding and the write run on the index's own thread.
     */
    private CachedAnalysis cacheFinished(String id, long length, long modified, Accumulator parsed) {
        CachedAnalysis entry = new CachedAnalysis(length, modified, new AnalysisSnapshot(parsed));
        putAnalysis(id, entry);
        if (index != null) {
            final List<Point> preview = entry.snapshot.points;
            index.write(id, length, modified, new RideSummaryStore.Encoder() {
                public byte[] state() throws IOException { return encodeState(parsed); }
                public byte[] preview() throws IOException { return encodePoints(preview); }
            });
        }
        return entry;
    }
    /**
     * A cached summary's preview from the index when memory has only the summary (seeded at start, or trimmed past
     * PREVIEW_CAP); null when the index has none for this journal state.
     */
    private AnalysisSnapshot savedPreview(String id, CachedAnalysis cached) {
        if (index == null || cached == null) return null;
        byte[] saved = index.readPreview(id, cached.length, cached.modified);
        List<Point> points = null;
        if (saved != null) {
            try { points = decodePoints(RideSummaryStore.inflate(saved)); } catch (IOException | RuntimeException damaged) { points = null; }
        }
        // An unusable preview: the replay that follows saves the whole entry again.
        if (points == null) { index.forgetSaved(id, cached.length, cached.modified); return null; }
        AnalysisSnapshot snapshot = new AnalysisSnapshot(cached.summary, points);
        synchronized (this) {
            if (analysisCache.get(id) == cached) putAnalysis(id, new CachedAnalysis(cached.length, cached.modified, snapshot));
        }
        return snapshot;
    }
    /** Test-only: waits for queued index writes. */
    void flushIndex() throws InterruptedException { if (index != null) index.flush(); }
    private static String journalId(File file) { return file.getName().substring(0, file.getName().length() - 4); }

    public synchronized String start(String scooterId, String label, long wallMillis,
            long monotonicMillis) throws IOException {
        return start(scooterId, label, wallMillis, monotonicMillis, null);
    }
    /**
     * As above; timeZone is the phone's time zone id when the ride began. A valid id is kept in START's details as
     * time_zone=&lt;id&gt; (the Everything export reads it back for local clock times); null or anything else
     * leaves details empty, as rides recorded before it were.
     */
    public synchronized String start(String scooterId, String label, long wallMillis,
            long monotonicMillis, String timeZone) throws IOException {
        if (active != null) throw new IllegalStateException("A ride is already recording");
        if (wallMillis < 0 || monotonicMillis < 0) throw new IllegalArgumentException("Invalid clock");
        String id = UUID.randomUUID().toString();
        Accumulator candidate = new Accumulator(id, trim(scooterId, 160), trim(label, 200), wallMillis, monotonicMillis);
        candidate.direction = defaultDirection;
        String start = row("START", wallMillis, monotonicMillis, "", candidate.scooterId, "", candidate.label,
                "", "", "", "", candidate.direction.name(), timeZoneDetail(timeZone));
        writeNew(file(id), HEADER + start);
        active = candidate;
        return id;
    }

    /** START's details for a time zone id: empty unless it looks like a zone id (letters, digits and _+-/ only). */
    static String timeZoneDetail(String timeZone) {
        return timeZone != null && timeZone.matches("[A-Za-z0-9_+/-]{1,64}") ? RideExportEverything.TIME_ZONE_DETAIL + timeZone : "";
    }

    /** The map is a current snapshot, including unavailable/stale fields if desired. */
    public synchronized void sample(long wallMillis, long monotonicMillis,
            Map<String, Sample> values) throws IOException {
        if (active == null) return;
        Map<String, Sample> bounded = new LinkedHashMap<String, Sample>();
        if (values != null) for (Map.Entry<String, Sample> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > 120 || isSecret(key) || entry.getValue() == null) continue;
            bounded.put(key, entry.getValue());
            if (bounded.size() >= MAX_METRICS) break;
        }
        String frame = Long.toString(active.samples + 1);
        StringBuilder rows = new StringBuilder(row("BEGIN", wallMillis, monotonicMillis, frame,
                "", Integer.toString(bounded.size()), "", "", "", "", "", "", ""));
        for (Map.Entry<String, Sample> entry : bounded.entrySet()) {
            Sample s = entry.getValue();
            String age = s.updatedMonoMs < 0 || monotonicMillis < s.updatedMonoMs ? ""
                    : Long.toString(monotonicMillis - s.updatedMonoMs);
            rows.append(row("SENSOR", wallMillis, monotonicMillis, frame, entry.getKey(),
                    s.number == null ? "" : Double.toString(s.number), s.text, s.unit,
                    Integer.toString(s.sourceCmd), Long.toString(s.updatedMonoMs), age, "", ""));
        }
        rows.append(row("COMMIT", wallMillis, monotonicMillis, frame, "", Integer.toString(bounded.size()), "", "", "", "", "", "", ""));
        append(file(active.id), rows.toString());
        active.accept(wallMillis, monotonicMillis, bounded);
    }

    public synchronized void markGap(long wallMillis, long monotonicMillis, String reason) throws IOException {
        if (active == null) return;
        append(file(active.id), row("GAP", wallMillis, monotonicMillis, "", "", "", "", "", "", "", "", "", trim(reason, 500)));
        active.gap(wallMillis, monotonicMillis);
    }

    /** Advance missing exposure during one ongoing gap without inventing samples or new gaps. */
    public synchronized void heartbeat(long wallMillis, long monotonicMillis) throws IOException {
        if (active == null) return;
        append(file(active.id), row("HEARTBEAT", wallMillis, monotonicMillis, "", "", "", "", "", "", "", "", "", ""));
        active.heartbeat(wallMillis, monotonicMillis);
    }

    public synchronized Summary stop(long wallMillis, long monotonicMillis) throws IOException {
        return stop(wallMillis, monotonicMillis, "");
    }
    /** Stops at the given (current) time; the reason never moves the end or adds samples. */
    public synchronized Summary stop(long wallMillis, long monotonicMillis, String reason) throws IOException {
        if (active == null) return null;
        String details = STOP_AUTO_PARKED.equals(reason) || STOP_AUTO_NO_MOTION.equals(reason) ? reason
                : isQuietStop(reason) ? quietDetails(reason, active.readingElapsed >= 0 ? active.readingMono : -1) : "";
        append(file(active.id), row("STOP", wallMillis, monotonicMillis, "", "", "", "", "", "", "", "", "", details));
        active.stop(wallMillis, monotonicMillis); active.stopReason = details;
        Summary result = new Summary(active); active = null;
        lastStopWallMs = wallMillis;
        lastStopAutomatic = result.automaticStop || result.noMotionStop;
        // Save the finished ride now, so a restart before Rides opens still finds it. The save replays the journal
        // once on the index's low-priority thread, so the entry is exactly what any later replay gives.
        if (index != null) { final String id = result.id; index.submit(() -> { try { get(id); } catch (IOException | RuntimeException ignored) { } }); }
        return result;
    }

    /** The stop reason for a went-quiet end; {@link #stop} adds the last reading itself. */
    public static String quietStopReason(int disconnectCode, boolean phoneBluetoothOff) {
        return STOP_AUTO_QUIET + ";code=" + disconnectCode + (phoneBluetoothOff ? ";phone_bt=off" : "");
    }
    static boolean isQuietStop(String details) {
        return details != null && (details.equals(STOP_AUTO_QUIET) || details.startsWith(STOP_AUTO_QUIET + ";"));
    }
    /** Known parameters only, in a fixed order; anything malformed or too long degrades to the bare token. */
    private static String quietDetails(String reason, long lastReadingMono) {
        Map<String, String> params = stopParameters(reason);
        String details = STOP_AUTO_QUIET + ";last=" + lastReadingMono + ";code=" + disconnectCode(params)
                + ("off".equals(params.get("phone_bt")) ? ";phone_bt=off" : "");
        return details.length() <= MAX_STOP_DETAILS ? details : STOP_AUTO_QUIET;
    }
    /** The native disconnect code, or -1 when absent, malformed or outside an int: the writer and reader agree. */
    private static int disconnectCode(Map<String, String> params) {
        long code = parseLong(params.get("code"), -1);
        return code < Integer.MIN_VALUE || code > Integer.MAX_VALUE ? -1 : (int) code;
    }
    private static Map<String, String> stopParameters(String details) {
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        if (details == null) return params;
        String[] parts = details.split(";", -1);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0 && !params.containsKey(parts[i].substring(0, eq))) params.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return params;
    }
    private static long parseLong(String value, long fallback) {
        if (value == null || !value.matches("-?[0-9]{1,19}")) return fallback;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    /** Direction changes affect future intervals only; do not reinterpret earlier data. */
    public synchronized void setCurrentDirection(CurrentDirection direction) throws IOException {
        if (direction == null) direction = CurrentDirection.UNCONFIRMED;
        if (active != null && direction != active.direction) {
            append(file(active.id), row("DIRECTION", active.lastWall, active.lastMono, "", "", "", "", "", "", "", "", direction.name(), ""));
            if (active.samples > 0) active.directionChanges++; active.direction = direction; active.breakAnchor();
        }
        defaultDirection = direction;
    }

    public synchronized void setNotes(String notes) throws IOException {
        if (active == null) return;
        String clean = trim(notes, 2000);
        append(file(active.id), row("NOTE", active.lastWall, active.lastMono, "", "", "", "", "", "", "", "", "", clean));
        active.notes = clean;
    }

    /** Append-only rename: original measurements and their clock/energy interpretation stay intact. */
    public synchronized void rename(String id, String name) throws IOException {
        String clean = trim(name == null ? "" : name.trim(), 200);
        if (clean.isEmpty()) throw new IllegalArgumentException("A ride needs a name");
        Accumulator ride = active != null && active.id.equals(id) ? active : read(file(id));
        if (ride == null) throw new IOException("Ride does not exist");
        repairTail(file(id));
        append(file(id), row("RENAME", ride.lastWall, ride.lastMono, "", "", "", "", "", "", "", "", "", clean));
        ride.label = clean;
        forget(id);
    }

    public synchronized String activeId() { return active == null ? null : active.id; }
    /** When this process last stopped and saved a ride (the stop's wall time, not the ride's trimmed end), or -1. */
    private long lastStopWallMs = -1;
    private boolean lastStopAutomatic;
    public synchronized long lastStopWallMs() { return lastStopWallMs; }
    /** Whether that stop was automatic (parked, went quiet or no motion) rather than the rider's. */
    public synchronized boolean lastStopAutomatic() { return lastStopAutomatic; }
    public synchronized Summary activeSummary() { return active == null ? null : new Summary(active); }
    /**
     * The active ride is answered from memory. A finished journal comes from the shared cache while its length and
     * modified time match. A miss replays outside the recorder monitor, so sampling continues meanwhile.
     */
    public Summary get(String id) throws IOException {
        File source = file(id);
        synchronized (this) {
            if (active != null && active.id.equals(id)) return new Summary(active);
        }
        long length = source.length(), modified = source.lastModified();
        if (!source.isFile()) { forget(id); return null; }
        synchronized (this) {
            CachedAnalysis analysis = analysisCache.get(id);
            if (analysis != null && analysis.matches(length, modified)) return analysis.summary;
            CachedSummary cached = summaryCache.get(id);
            if (cached != null && cached.matches(length, modified)) return cached.summary;
        }
        CachedAnalysis saved = savedEntry(id, source, length, modified);
        if (saved != null) return saved.summary;
        Accumulator parsed;
        try { parsed = read(source); }
        catch (IOException e) { if (!source.exists()) { forget(id); return null; } throw e; }
        if (parsed == null) return null;
        // Only an unchanged journal is cached; one that changed during the replay just misses next time.
        if (source.length() != length || source.lastModified() != modified) return new Summary(parsed);
        if (parsed.stopped) return cacheFinished(id, length, modified, parsed).summary;
        Summary summary = new Summary(parsed);
        synchronized (this) { summaryCache.put(id, new CachedSummary(length, modified, summary)); }
        return summary;
    }
    /** Not synchronized as a whole: each get() takes the monitor only to read or fill the cache. */
    public List<Summary> list() throws IOException {
        ArrayList<Summary> result = new ArrayList<Summary>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list rides");
        java.util.HashSet<String> existing = new java.util.HashSet<>();
        for (File f : files) if (isJournal(f)) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            existing.add(id);
            Summary summary = get(id); if (summary != null) result.add(summary);
        }
        retainCached(existing);
        Collections.sort(result, NEWEST_FIRST);
        return Collections.unmodifiableList(result);
    }
    private static final Comparator<Summary> NEWEST_FIRST = new Comparator<Summary>() { public int compare(Summary a, Summary b) {
        return a.startWallMs < b.startWallMs ? 1 : a.startWallMs == b.startWallMs ? a.id.compareTo(b.id) : -1;
    }};
    /**
     * list() from memory alone, for the UI thread: null when any journal would need a replay (a cold cache, a
     * changed file, or a journal that can't be read) or the folder can't be listed. No I/O beyond listing the folder and each file's length and
     * modified time; it never fills or trims the cache, so a miss leaves the work to list() on a worker.
     */
    public List<Summary> listCached() {
        File[] files = directory.listFiles();
        // list() reports a folder that can't be listed.
        if (files == null) return null;
        ArrayList<Summary> result = new ArrayList<Summary>();
        for (File f : files) if (isJournal(f)) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            synchronized (this) { if (active != null && active.id.equals(id)) { result.add(new Summary(active)); continue; } }
            long length = f.length(), modified = f.lastModified();
            if (!f.isFile()) continue;
            Summary hit = null;
            synchronized (this) {
                CachedAnalysis analysis = analysisCache.get(id);
                if (analysis != null && analysis.matches(length, modified)) hit = analysis.summary;
                CachedSummary cached = hit == null ? summaryCache.get(id) : null;
                if (cached != null && cached.matches(length, modified)) hit = cached.summary;
            }
            if (hit == null) return null;
            result.add(hit);
        }
        Collections.sort(result, NEWEST_FIRST);
        return Collections.unmodifiableList(result);
    }
    /**
     * Call from a worker. Active state is copied atomically without disk I/O; completed
     * journals are immutable and replay outside the recorder monitor so sampling can continue.
     * Unlike constructing a second RideStore, this operation never recovers or modifies a journal.
     */
    public List<AnalysisSnapshot> analysisSnapshots(String scooterId, long sinceWallMs) throws IOException {
        ArrayList<AnalysisSnapshot> result = new ArrayList<>();
        String copiedActiveId;
        synchronized (this) {
            copiedActiveId = active == null ? null : active.id;
            if (active != null && active.scooterId.equals(scooterId) && active.lastWall >= sinceWallMs)
                result.add(new AnalysisSnapshot(active));
        }
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list rides");
        java.util.HashSet<String> existing = new java.util.HashSet<>();
        for (File source : files) if (isJournal(source)) {
            String id = source.getName().substring(0, source.getName().length() - 4);
            existing.add(id);
            if (id.equals(copiedActiveId)) continue;
            CachedAnalysis cached;
            synchronized (this) {
                if (active != null && active.id.equals(id)) continue;
                cached = analysisCache.get(id);
            }
            long length = source.length(), modified = source.lastModified();
            boolean matches = cached != null && cached.matches(length, modified);
            if (!matches && (cached = savedEntry(id, source, length, modified)) != null) matches = true;
            AnalysisSnapshot snapshot = matches ? cached.snapshot : null;
            if (snapshot == null && matches) snapshot = savedPreview(id, cached);
            if (snapshot == null) {
                Accumulator parsed;
                try { parsed = read(source); }
                catch (IOException e) { if (!source.exists()) continue; throw e; }
                // A new active journal or an incomplete tail is not a completed snapshot.
                if (parsed == null || !parsed.stopped) continue;
                if (source.length() == length && source.lastModified() == modified)
                    snapshot = cacheFinished(id, length, modified, parsed).snapshot;
                else snapshot = new AnalysisSnapshot(parsed);
            }
            if (snapshot.summary.scooterId.equals(scooterId)
                    && snapshot.summary.endWallMs >= sinceWallMs) result.add(snapshot);
        }
        retainCached(existing);
        return Collections.unmodifiableList(result);
    }

    /** A concurrent file change may be retried once; permanent storage errors are not retried. */
    static final class JournalChangedException extends IOException {
        JournalChangedException() { super("Ride history changed while reading"); }
    }
    /**
     * Analyze committed samples in a single streaming pass per completed journal. The bounded
     * preview is never used for quality decisions, and no full-detail list or raw routes survive.
     * This is read-only: an unfinished/invalid journal is omitted, never recovered or rewritten.
     */
    LearnedRangeEstimate.Result learnedRange(String profile, long now) throws IOException {
        List<LearnedRangeEstimate.Evidence> evidence = new ArrayList<>();
        int unreadable = 0;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list rides");
        for (File source : files) if (isJournal(source)) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Range analysis canceled");
            String id = source.getName().substring(0, source.getName().length() - 4);
            synchronized (this) { if (active != null && active.id.equals(id)) continue; }
            long length = source.length(), modified = source.lastModified();
            LearnedRangeEstimate.Inspector inspector = new LearnedRangeEstimate.Inspector();
            Accumulator parsed;
            try { parsed = read(source, false, inspector); }
            catch (java.io.InterruptedIOException canceled) { throw canceled; }
            catch (IOException error) {
                if (!source.isFile() || source.length() != length || source.lastModified() != modified)
                    throw new JournalChangedException();
                unreadable++; continue;
            }
            if (!source.isFile() || source.length() != length || source.lastModified() != modified)
                throw new JournalChangedException();
            if (parsed == null) { unreadable++; continue; }
            if (!profile.equals(parsed.scooterId) || parsed.lastWall < now - LearnedRangeEstimate.WINDOW_DAYS * 86400000L) continue;
            if (!parsed.stopped) { unreadable++; continue; }
            evidence.add(inspector.finish(new Summary(parsed)));
        }
        return LearnedRangeEstimate.calculate(profile, evidence, now, unreadable);
    }

    public synchronized boolean delete(String id) throws IOException {
        if (active != null && active.id.equals(id)) throw new IllegalStateException("Stop recording before deleting");
        File f = file(id); if (!f.exists()) return false;
        if (!f.delete()) throw new IOException("Could not delete ride"); forget(id);
        if (index != null) index.remove(id);
        return true;
    }
    /** The ride's journal file, for exports that stream it (RideExportEverything); it may not exist. */
    File journal(String id) { return file(id); }
    public synchronized void exportCsv(String id, File destination) throws IOException {
        File source = file(id);
        if (!source.isFile()) throw new IOException("Ride does not exist");
        if (source.getCanonicalFile().equals(destination.getCanonicalFile())) throw new IOException("Export destination is source");
        FileInputStream input = new FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(destination);
            try { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n); }
            finally { output.close(); }
        } finally { input.close(); }
    }
    /**
     * Chart preview. A completed ride's full-resolution preview is its cached analysis snapshot, so
     * reopening a long ride (the wide Rides pane rebuilds on every visit) never replays the journal
     * again. A miss replays outside the recorder monitor, as analysisSnapshots does.
     */
    public List<Point> trend(String id, int maxPoints) throws IOException {
        File source = file(id);
        CachedAnalysis cached;
        synchronized (this) {
            if (active != null && active.id.equals(id)) return trendPoints(active, maxPoints);
            cached = analysisCache.get(id);
        }
        long length = source.length(), modified = source.lastModified();
        boolean preview = maxPoints >= MAX_TREND_POINTS;
        boolean matches = preview && cached != null && cached.matches(length, modified);
        if (preview && !matches && (cached = savedEntry(id, source, length, modified)) != null) matches = true;
        AnalysisSnapshot hit = matches ? cached.snapshot : null;
        if (hit == null && matches) hit = savedPreview(id, cached);
        if (hit != null) return hit.points;
        Accumulator parsed = read(source);
        // Only an unchanged, completed journal is cached; a rename during the replay just misses next time.
        if (parsed != null && parsed.stopped && source.length() == length && source.lastModified() == modified) {
            AnalysisSnapshot replayed = cacheFinished(id, length, modified, parsed).snapshot;
            if (preview) return replayed.points;
        }
        return trendPoints(parsed, maxPoints);
    }
    /** trend(id, MAX_TREND_POINTS) from memory alone, for the UI thread: null when it would need a replay. */
    public List<Point> trendCached(String id) {
        File source = file(id);
        CachedAnalysis cached;
        synchronized (this) {
            if (active != null && active.id.equals(id)) return trendPoints(active, MAX_TREND_POINTS);
            cached = analysisCache.get(id);
        }
        if (cached == null || !cached.matches(source.length(), source.lastModified())) return null;
        AnalysisSnapshot hit = cached.snapshot;
        return hit == null ? null : hit.points;
    }
    /** Replay a finished journal without the preview cap or holding the recorder monitor. */
    public List<Point> fullTrend(String id) throws IOException {
        File source = file(id);
        synchronized (this) {
            if (active != null && active.id.equals(id)) throw new IOException("Ride is still recording");
        }
        long length = source.length(), modified = source.lastModified();
        Accumulator replay = read(source, true);
        if (replay == null || !replay.stopped) throw new IOException("Finished ride unavailable");
        if (!source.isFile() || source.length() != length || source.lastModified() != modified)
            throw new IOException("Ride changed while loading; try again");
        return Collections.unmodifiableList(replay.points);
    }
    private static List<Point> trendPoints(Accumulator a, int maxPoints) {
        if (a == null || maxPoints <= 0) return Collections.emptyList();
        List<Point> source = new ArrayList<Point>(a.points);
        if (a.lastPoint != null && (source.isEmpty() || source.get(source.size() - 1) != a.lastPoint))
            source.add(a.lastPoint);
        ArrayList<Point> result = new ArrayList<Point>();
        int count = Math.min(Math.min(maxPoints, MAX_TREND_POINTS), source.size());
        for (int i = 0; i < count; i++) {
            int index = count <= 1 ? source.size() - 1 : (int) ((long) i * (source.size() - 1) / (count - 1));
            result.add(source.get(index));
        }
        return Collections.unmodifiableList(result);
    }

    private static final class Accumulator {
        final String id, scooterId;
        String label;
        String notes = "";
        final long startWall, startMono;
        long lastWall, lastMono, elapsed, electricalMs, speedMs, movingMs, stationaryMs;
        long samples, gaps, clockDiscontinuities, directionChanges, confirmedMs, unconfirmedMs, pointStride = 1;
        long movingElectricalMs, stationaryElectricalMs, unknownMotionElectricalMs;
        boolean stopped, recovered, odometerReset;
        String stopReason = "";
        /** The last committed sample with any fresh scooter reading; elapsed -1 until one arrives. */
        long readingWall, readingMono, readingElapsed = -1, readingGaps;
        /** The last committed sample with motion (a fresh speed above 0, driving status 1, odometer or trip going up); elapsed -1 until one. */
        long motionWall, motionElapsed = -1, motionGaps, motionPush = -1;
        Double previousTrip;
        CurrentDirection direction = CurrentDirection.UNCONFIRMED;
        Double startSoc, endSoc, startVoltage, minVoltage, maxVoltage, minTemperature, maxTemperature;
        Double startOdo, endOdo, maxSpeed, peakPower, maxCurrent;
        Double previousPower, previousSpeed;
        final PowerSign currentSign = new PowerSign();
        /**
         * Configured-direction time and energy recorded while the sign state wasn't 0 or 1 (either end of the interval)
         * and before any 0 or 1: unsettled until a first 0 or 1 arrives or the ride ends. Intervals like that after a
         * 0 or 1 had been read carry Telemetry's stale sign, so they count unconfirmed at once and add no energy.
         */
        long heldMs;
        double heldDrawWh, heldRegeneratedWh;
        boolean previousSignUnknown;
        Point lastPoint;
        double throughputWh, drawWh, regeneratedWh, speedDistance, movingDistance;
        double movingThroughputWh, stationaryThroughputWh, unknownMotionThroughputWh;
        final ArrayList<Point> points = new ArrayList<Point>();
        boolean keepAllPoints;
        LearnedRangeEstimate.Inspector rangeInspector;
        Accumulator(String id, String scooterId, String label, long wall, long mono) {
            this.id = id; this.scooterId = scooterId; this.label = label;
            startWall = lastWall = wall; lastMono = startMono = mono;
        }
        void breakAnchor() { previousPower = null; previousSpeed = null; }
        void recover() { recovered = true; gaps++; breakAnchor(); }
        long advance(long wall, long mono) {
            long dt = mono >= lastMono ? mono - lastMono : 0;
            if (mono < lastMono) { clockDiscontinuities++; gaps++; breakAnchor(); }
            // On a reboot use wall time only for the missing coverage, never for energy.
            if (mono < lastMono && wall > lastWall) dt = wall - lastWall;
            elapsed += dt; lastWall = wall; lastMono = mono; return dt;
        }
        void gap(long wall, long mono) { advance(wall, mono); gaps++; breakAnchor(); }
        void heartbeat(long wall, long mono) { advance(wall, mono); breakAnchor(); }
        void stop(long wall, long mono) { advance(wall, mono); stopped = true; breakAnchor(); }
        void accept(long wall, long mono, Map<String, Sample> m) {
            long previousMono = lastMono;
            long dt = advance(wall, mono);
            boolean sameClock = mono >= previousMono;
            Double voltage = number(m, "voltage_v", mono), current = number(m, "current_a", mono);
            if (voltage != null && (voltage <= 0 || voltage > 200)) voltage = null;
            if (current != null && Math.abs(current) > 500) current = null;
            boolean signPresentBefore = currentSign.present(), signKnownBefore = currentSign.everKnown();
            Sample signSample = m.get("current_sign_state");
            if (signSample != null && finite(signSample.number)) currentSign.signState(signSample.number);
            if (current != null) currentSign.current(current);
            // Time before a first sign state belongs to it: a scooter that starts answering 254 was never confirmed.
            if (!signPresentBefore && currentSign.latestUnknown()) {
                heldMs += confirmedMs; heldDrawWh += drawWh; heldRegeneratedWh += regeneratedWh;
                confirmedMs = 0; drawWh = regeneratedWh = 0;
            }
            boolean signUnknown = currentSign.latestUnknown();
            Sample vs = m.get("voltage_v"), cs = m.get("current_a");
            Double power = voltage != null && current != null && vs.updatedMonoMs == cs.updatedMonoMs
                    && vs.sourceCmd == cs.sourceCmd ? voltage * current : null;
            Double speed = number(m, "speed_kmh", mono);
            if (speed != null && (speed < 0 || speed > 200)) speed = null;
            Double soc = number(m, "battery_percent", mono);
            if (soc != null && (soc < 0 || soc > 100)) soc = null;
            Double odo = number(m, "odometer_km", mono);
            if (odo != null && odo < 0) odo = null;
            Double temperature = number(m, "temperature_c", mono);
            boolean moved = motion(this, m, mono, odo);
            if (temperature != null && (temperature < -60 || temperature > 150)) temperature = null;
            if (soc != null) { if (startSoc == null) startSoc = soc; endSoc = soc; }
            if (voltage != null) { if (startVoltage == null) startVoltage = voltage;
                minVoltage = min(minVoltage, voltage); maxVoltage = max(maxVoltage, voltage); }
            if (temperature != null) { minTemperature = min(minTemperature, temperature); maxTemperature = max(maxTemperature, temperature); }
            if (odo != null) { if (startOdo == null) startOdo = odo;
                if (endOdo != null && odo + 0.000001 < endOdo) odometerReset = true; endOdo = odo; }
            if (speed != null) maxSpeed = max(maxSpeed, speed);
            if (power != null) peakPower = max(peakPower, Math.abs(power));
            if (current != null) maxCurrent = max(maxCurrent, Math.abs(current));
            boolean shortInterval = sameClock && dt > 0 && dt <= MAX_INTEGRATION_INTERVAL_MS;
            if (shortInterval && previousPower != null && power != null) {
                double[] energy = segmentEnergy(previousPower, power, dt);
                double throughput = energy[0] + energy[1];
                throughputWh += throughput; electricalMs += dt;
                if (previousSpeed == null || speed == null) {
                    unknownMotionThroughputWh += throughput; unknownMotionElectricalMs += dt;
                } else if ((previousSpeed + speed) / 2 > 0.5) {
                    movingThroughputWh += throughput; movingElectricalMs += dt;
                } else { stationaryThroughputWh += throughput; stationaryElectricalMs += dt; }
                double draw = direction == CurrentDirection.POSITIVE_IS_DRAW ? energy[0] : energy[1];
                double regen = direction == CurrentDirection.POSITIVE_IS_DRAW ? energy[1] : energy[0];
                if (direction == CurrentDirection.UNCONFIRMED) unconfirmedMs += dt;
                else if (previousSignUnknown || signUnknown) {
                    // Into or out of an unknown state: the interval's energy mixes readings from it.
                    if (signKnownBefore) unconfirmedMs += dt;
                    else { heldMs += dt; heldDrawWh += draw; heldRegeneratedWh += regen; }
                } else { confirmedMs += dt; drawWh += draw; regeneratedWh += regen; }
            }
            // A first 0 or 1 settles what was held: confirmed only if the value's own sign bit had shown a negative.
            if (!signKnownBefore && currentSign.everKnown() && heldMs > 0) {
                if (currentSign.sawNegative()) { confirmedMs += heldMs; drawWh += heldDrawWh; regeneratedWh += heldRegeneratedWh; }
                else unconfirmedMs += heldMs;
                heldMs = 0; heldDrawWh = heldRegeneratedWh = 0;
            }
            previousSignUnknown = signUnknown;
            if (shortInterval && previousSpeed != null && speed != null) {
                double distance = (previousSpeed + speed) / 2 * dt / 3600000.0;
                speedDistance += distance; speedMs += dt;
                if ((previousSpeed + speed) / 2 > 0.5) { movingMs += dt; movingDistance += distance; }
                else stationaryMs += dt;
            }
            if (dt > MAX_INTEGRATION_INTERVAL_MS && samples > 0) gaps++;
            previousPower = power; previousSpeed = speed; samples++;
            Double chargingState = number(m, "charging_state", mono);
            // Any fresh, valid scooter value this ride records moves the shown end, not only the energy inputs.
            if (voltage != null || current != null || speed != null || soc != null || odo != null
                    || temperature != null || chargingState != null) {
                readingWall = wall; readingMono = mono; readingElapsed = elapsed; readingGaps = gaps;
            }
            if (moved) { motionWall = wall; motionElapsed = elapsed; motionGaps = gaps; }
            Point point = new Point(wall, mono, elapsed, (int)Math.min(Integer.MAX_VALUE, gaps), voltage, power, speed, soc, odo, chargingState, speedMs);
            lastPoint = point;
            if (rangeInspector != null) rangeInspector.accept(point);
            if (keepAllPoints) points.add(point);
            else if ((samples - 1) % pointStride == 0) {
                if (points.size() >= MAX_TREND_POINTS) {
                    for (int i = points.size() - 2; i > 0; i -= 2) points.remove(i);
                    pointStride *= 2;
                }
                points.add(point);
            }
        }
    }

    /**
     * Rule 3's motion, judged from the journal so a reload agrees: a new speed or driving-status push
     * (speed above 0 or status 1), or the odometer or trip above its previous fresh value. Call before
     * endOdo moves.
     */
    private static boolean motion(Accumulator a, Map<String, Sample> m, long mono, Double odo) {
        boolean moved = false;
        Double speed = number(m, SPEED_KEY, mono), driving = number(m, DRIVING_KEY, mono);
        Sample push = m.get(speed != null ? SPEED_KEY : DRIVING_KEY);
        if (push != null && (speed != null || driving != null) && push.updatedMonoMs != a.motionPush) {
            a.motionPush = push.updatedMonoMs;
            moved = speed != null && speed > 0 && speed <= 200 || driving != null && driving == 1;
        }
        if (odo != null && a.endOdo != null && odo > a.endOdo + 0.000001) moved = true;
        Double trip = number(m, TRIP_KEY, mono);
        if (trip != null && trip >= 0) { if (a.previousTrip != null && trip > a.previousTrip + 0.000001) moved = true; a.previousTrip = trip; }
        return moved;
    }
    private static final String SPEED_KEY = "speed_kmh", DRIVING_KEY = "driving_status";
    /** Journal keys rule 3 also reads live (ScooterService), so both sides judge the same readings. */
    static final String ODOMETER_KEY = "odometer_km", TRIP_KEY = "trip_km";

    /*
     * The saved form of a finished journal's analysis (RideSummaryStore). The summary is kept as the replay's final
     * accumulator state, so new Summary(state) derives every field exactly as a replay does; the preview is the
     * replay's own 600 points. Fields are written in a fixed order: add new state to both methods together and bump
     * RideSummaryStore.ANALYSIS_VERSION. RideSummaryStoreTest checks every Summary and Point field against a replay.
     */
    private static byte[] encodeState(Accumulator a) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(640);
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeUTF(a.id); out.writeUTF(a.scooterId); out.writeUTF(a.label); out.writeUTF(a.notes);
        out.writeUTF(a.stopReason == null ? "" : a.stopReason); out.writeUTF(a.direction.name());
        out.writeBoolean(a.stopped); out.writeBoolean(a.recovered); out.writeBoolean(a.odometerReset);
        out.writeByte(a.currentSign.state());
        for (long value : new long[] {a.startWall, a.startMono, a.lastWall, a.lastMono, a.elapsed, a.electricalMs, a.speedMs,
                a.movingMs, a.stationaryMs, a.samples, a.gaps, a.clockDiscontinuities, a.directionChanges, a.confirmedMs,
                a.unconfirmedMs, a.pointStride, a.movingElectricalMs, a.stationaryElectricalMs, a.unknownMotionElectricalMs,
                a.readingWall, a.readingMono, a.readingElapsed, a.readingGaps, a.heldMs}) out.writeLong(value);
        for (Double value : new Double[] {a.startSoc, a.endSoc, a.startVoltage, a.minVoltage, a.maxVoltage, a.minTemperature,
                a.maxTemperature, a.startOdo, a.endOdo, a.maxSpeed, a.peakPower, a.maxCurrent}) writeOptional(out, value);
        for (double value : new double[] {a.throughputWh, a.drawWh, a.regeneratedWh, a.speedDistance, a.movingDistance,
                a.movingThroughputWh, a.stationaryThroughputWh, a.unknownMotionThroughputWh, a.heldDrawWh, a.heldRegeneratedWh}) out.writeDouble(value);
        // Rule 3's last motion (the no-motion stop's shown end); a finished journal needs no other motion state.
        for (long value : new long[] {a.motionWall, a.motionElapsed, a.motionGaps}) out.writeLong(value);
        out.flush(); return bytes.toByteArray();
    }
    /** The finished state saved by encodeState, or null when it is for another ride or not finished. */
    private static Accumulator decodeState(byte[] data, String expectedId) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
        String id = in.readUTF(), scooterId = in.readUTF(), label = in.readUTF(), notes = in.readUTF(), stopReason = in.readUTF();
        CurrentDirection direction = CurrentDirection.valueOf(in.readUTF());
        boolean stopped = in.readBoolean(), recovered = in.readBoolean(), odometerReset = in.readBoolean();
        int currentSignState = in.readByte();
        long[] l = new long[24];
        for (int i = 0; i < l.length; i++) l[i] = in.readLong();
        Double[] d = new Double[12];
        for (int i = 0; i < d.length; i++) d[i] = readOptional(in);
        double[] e = new double[10];
        for (int i = 0; i < e.length; i++) e[i] = in.readDouble();
        long[] still = new long[3];
        for (int i = 0; i < still.length; i++) still[i] = in.readLong();
        if (in.read() != -1) throw new IOException("trailing");
        if (!id.equals(expectedId) || !stopped) return null;
        Accumulator a = new Accumulator(id, scooterId, label, l[0], l[1]);
        a.notes = notes; a.stopReason = stopReason; a.direction = direction; a.currentSign.restore(currentSignState);
        a.stopped = true; a.recovered = recovered; a.odometerReset = odometerReset;
        a.lastWall = l[2]; a.lastMono = l[3]; a.elapsed = l[4]; a.electricalMs = l[5]; a.speedMs = l[6]; a.movingMs = l[7];
        a.stationaryMs = l[8]; a.samples = l[9]; a.gaps = l[10]; a.clockDiscontinuities = l[11]; a.directionChanges = l[12];
        a.confirmedMs = l[13]; a.unconfirmedMs = l[14]; a.pointStride = l[15]; a.movingElectricalMs = l[16];
        a.stationaryElectricalMs = l[17]; a.unknownMotionElectricalMs = l[18]; a.readingWall = l[19]; a.readingMono = l[20];
        a.readingElapsed = l[21]; a.readingGaps = l[22]; a.heldMs = l[23];
        a.startSoc = d[0]; a.endSoc = d[1]; a.startVoltage = d[2]; a.minVoltage = d[3]; a.maxVoltage = d[4];
        a.minTemperature = d[5]; a.maxTemperature = d[6]; a.startOdo = d[7]; a.endOdo = d[8]; a.maxSpeed = d[9];
        a.peakPower = d[10]; a.maxCurrent = d[11];
        a.throughputWh = e[0]; a.drawWh = e[1]; a.regeneratedWh = e[2]; a.speedDistance = e[3]; a.movingDistance = e[4];
        a.movingThroughputWh = e[5]; a.stationaryThroughputWh = e[6]; a.unknownMotionThroughputWh = e[7]; a.heldDrawWh = e[8]; a.heldRegeneratedWh = e[9];
        a.motionWall = still[0]; a.motionElapsed = still[1]; a.motionGaps = still[2];
        return a;
    }
    /**
     * Column by column, so deflate finds the repeats: each clock is a difference from the previous point, and each
     * reading is its bits XORed with the previous point's reading of the same kind (exact, unlike rounding).
     */
    private static byte[] encodePoints(List<Point> points) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(64 + points.size() * 64);
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        int n = points.size();
        out.writeInt(n);
        long previous = 0;
        for (Point p : points) { out.writeLong(p.wallMs - previous); previous = p.wallMs; }
        previous = 0;
        for (Point p : points) { out.writeLong(p.monotonicMs - previous); previous = p.monotonicMs; }
        previous = 0;
        for (Point p : points) { out.writeLong(p.elapsedMs - previous); previous = p.elapsedMs; }
        previous = 0;
        for (Point p : points) { out.writeLong(p.speedCoverageMs - previous); previous = p.speedCoverageMs; }
        int segment = 0;
        for (Point p : points) { out.writeInt(p.segment - segment); segment = p.segment; }
        for (int k = 0; k < 6; k++) {
            long bits = 0;
            for (Point p : points) {
                Double value = reading(p, k);
                out.writeBoolean(value != null);
                if (value != null) { long now = Double.doubleToRawLongBits(value); out.writeLong(now ^ bits); bits = now; }
            }
        }
        out.flush(); return bytes.toByteArray();
    }
    private static Double reading(Point p, int k) {
        return k == 0 ? p.voltageV : k == 1 ? p.powerW : k == 2 ? p.speedKmh : k == 3 ? p.batteryPercent : k == 4 ? p.odometerKm : p.chargingState;
    }
    private static List<Point> decodePoints(byte[] data) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
        int n = in.readInt();
        if (n < 0 || n > MAX_TREND_POINTS) throw new IOException("size");
        long[][] clocks = new long[4][n];
        for (long[] column : clocks) { long value = 0; for (int i = 0; i < n; i++) column[i] = value += in.readLong(); }
        int[] segments = new int[n];
        int segment = 0;
        for (int i = 0; i < n; i++) segments[i] = segment += in.readInt();
        Double[][] readings = new Double[6][n];
        for (Double[] column : readings) {
            long bits = 0;
            for (int i = 0; i < n; i++) if (in.readBoolean()) { bits ^= in.readLong(); column[i] = Double.longBitsToDouble(bits); }
        }
        if (in.read() != -1) throw new IOException("trailing");
        ArrayList<Point> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            points.add(new Point(clocks[0][i], clocks[1][i], clocks[2][i], segments[i], readings[0][i], readings[1][i],
                    readings[2][i], readings[3][i], readings[4][i], readings[5][i], clocks[3][i]));
        return Collections.unmodifiableList(points);
    }
    private static void writeOptional(java.io.DataOutputStream out, Double value) throws IOException {
        out.writeBoolean(value != null); if (value != null) out.writeDouble(value);
    }
    private static Double readOptional(java.io.DataInputStream in) throws IOException {
        return in.readBoolean() ? Double.valueOf(in.readDouble()) : null;
    }

    // Exact trapezoidal signed energy, split at zero when direction reverses.
    private static double[] segmentEnergy(double a, double b, long dt) {
        double factor = dt / 3600000.0;
        if (a >= 0 && b >= 0) return new double[] {(a + b) * factor / 2, 0};
        if (a <= 0 && b <= 0) return new double[] {0, -(a + b) * factor / 2};
        double magnitude = Math.abs(a) + Math.abs(b);
        double first = a * a / magnitude * factor / 2;
        double second = b * b / magnitude * factor / 2;
        return a > 0 ? new double[] {first, second} : new double[] {second, first};
    }
    private static Double number(Map<String, Sample> m, String key, long mono) {
        Sample sample = m.get(key);
        return sample != null && finite(sample.number) && sample.updatedMonoMs >= 0
                && mono >= sample.updatedMonoMs && mono - sample.updatedMonoMs <= MAX_SENSOR_AGE_MS
                ? sample.number : null;
    }

    private static Accumulator read(File file) throws IOException { return read(file, false); }
    private static Accumulator read(File file, boolean keepAll) throws IOException { return read(file, keepAll, null); }
    private static Accumulator read(File file, boolean keepAll, LearnedRangeEstimate.Inspector inspector) throws IOException {
        if (!file.isFile()) return null;
        replays.incrementAndGet();
        Runnable hook = replayHook; if (hook != null) hook.run();
        String id = file.getName().substring(0, file.getName().length() - 4);
        Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        Accumulator a = null;
        Map<String, Sample> pending = null;
        String pendingFrame = null;
        long pendingWall = 0, pendingMono = 0;
        int expectedSensors = -1;
        try {
            List<String> r;
            while ((r = csvRecord(reader)) != null) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Ride loading canceled");
                if (keepAll) checkDetailMemory();
                if (r.size() != 15 || !"1".equals(r.get(0))) { pending = null; continue; }
                String event = r.get(1);
                try {
                    long wall = Long.parseLong(r.get(3)), mono = Long.parseLong(r.get(4));
                    if (a == null && acceptedStartRecord(r)) {
                        a = new Accumulator(id, r.get(6), r.get(8), wall, mono);
                        a.keepAllPoints = keepAll;
                        a.rangeInspector = inspector;
                        a.direction = direction(r.get(13));
                    } else if (a != null && "RENAME".equals(event)) {
                        a.label = trim(r.get(14), 200);
                    } else if (a != null && !a.stopped) {
                        if ("BEGIN".equals(event)) {
                            pending = new LinkedHashMap<String, Sample>(); pendingFrame = r.get(5);
                            pendingWall = wall; pendingMono = mono;
                            expectedSensors = r.get(7).length() == 0 ? -1 : Integer.parseInt(r.get(7));
                        } else if ("SENSOR".equals(event) && pending != null && pendingFrame.equals(r.get(5))
                                && pendingWall == wall && pendingMono == mono && pending.size() < MAX_METRICS) {
                            Double value = r.get(7).length() == 0 ? null : Double.valueOf(r.get(7));
                            pending.put(r.get(6), new Sample(value, r.get(8), r.get(9), Integer.parseInt(r.get(10)), Long.parseLong(r.get(11))));
                        } else if ("COMMIT".equals(event) && pending != null && pendingFrame.equals(r.get(5))
                                && pendingWall == wall && pendingMono == mono
                                && (expectedSensors < 0 || expectedSensors == pending.size())
                                && (r.get(7).length() == 0 || Integer.parseInt(r.get(7)) == pending.size())) {
                            a.accept(wall, mono, pending); pending = null;
                        } else if ("GAP".equals(event)) { a.gap(wall, mono); pending = null; }
                        else if ("HEARTBEAT".equals(event)) { a.heartbeat(wall, mono); pending = null; }
                        else if ("RECOVER".equals(event)) { a.recover(); pending = null; }
                        else if ("STOP".equals(event)) { a.stop(wall, mono); a.stopReason = trim(r.get(14), MAX_STOP_DETAILS); pending = null; }
                        else if ("NOTE".equals(event)) a.notes = r.get(14);
                        else if ("DIRECTION".equals(event)) { CurrentDirection changed = direction(r.get(13)); if (changed != a.direction && a.samples > 0) a.directionChanges++; a.direction = changed; a.breakAnchor(); }
                    }
                } catch (NumberFormatException ignored) { pending = null; }
            }
        } finally { reader.close(); }
        return a;
    }
    /**
     * True only when the journal ends with a STOP row followed by nothing but whole RENAME rows, the only rows Volt
     * appends after STOP. A full replay of such a journal finds it finished (or unreadable without a START), never
     * unfinished. Quotes, carriage returns, a partial last row or any other event return false, so the caller replays.
     */
    static boolean endsFinished(File file) {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
            long length = in.length();
            int window = (int) Math.min(length, 16384);
            if (window == 0) return false;
            byte[] tail = new byte[window];
            in.seek(length - window); in.readFully(tail);
            String text = new String(tail, "UTF-8");
            if (!text.endsWith("\n")) return false;
            String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
            // The window's first line may start mid-row; it is never trusted.
            int first = window < length ? 1 : 0;
            for (int i = lines.length - 1; i >= first; i--) {
                String line = lines[i];
                if (line.indexOf('"') >= 0 || line.indexOf('\r') >= 0) return false;
                String[] f = line.split(",", -1);
                if (f.length != 15 || !"1".equals(f[0])) return false;
                if ("RENAME".equals(f[1])) continue;
                if (!"STOP".equals(f[1])) return false;
                Long.parseLong(f[3]); Long.parseLong(f[4]);
                return true;
            }
            return false;
        } catch (IOException | RuntimeException unreadable) { return false; }
    }
    /** Read a staged journal without recovery or any writes to its containing folder. */
    static Summary inspectJournal(File source) throws IOException {
        Accumulator parsed = read(source);
        if (parsed == null || !parsed.stopped) throw new IOException("The backup contains an unfinished or unreadable ride.");
        return new Summary(parsed);
    }

    /** A journal's scooter and START clocks, read from its first records only; null if unreadable.
     * With the scooter, the two clocks identify one recording even under another UUID. */
    static final class Start { final String scooterId; final long wallMs, monotonicMs;
        Start(String scooter, long wall, long mono) { scooterId = scooter; wallMs = wall; monotonicMs = mono; } }
    static Start start(File source) {
        try (Reader in = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"))) {
            List<String> record;
            for (int i = 0; i < 4 && (record = csvRecord(in)) != null; i++)
                if (acceptedStartRecord(record)) return new Start(record.get(6), Long.parseLong(record.get(3)), Long.parseLong(record.get(4)));
        } catch (IOException | RuntimeException unreadable) { return null; }
        return null;
    }

    /** The reader ignores unsupported schemas and START records with invalid clocks. */
    private static boolean acceptedStartRecord(List<String> record) {
        if (record.size() != 15 || !"1".equals(record.get(0)) || !"START".equals(record.get(1))) return false;
        try {
            Long.parseLong(record.get(3)); Long.parseLong(record.get(4));
            return true;
        } catch (NumberFormatException ignored) { return false; }
    }

    /** Only used on private staging after the rider explicitly chooses a destination scooter. */
    static void reassignJournal(File source, String profile) throws IOException {
        if (profile == null || profile.isEmpty() || profile.length() > 160)
            throw new IOException("Choose a destination scooter.");
        File temporary = new File(source.getPath() + ".mapping");
        boolean mapped = false;
        try (Reader in = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"));
                FileOutputStream bytes = new FileOutputStream(temporary);
                Writer out = new BufferedWriter(new OutputStreamWriter(bytes, "UTF-8"))) {
            List<String> record;
            while ((record = csvRecord(in)) != null) {
                if (!mapped && acceptedStartRecord(record)) {
                    record.set(6, profile); mapped = true;
                }
                out.write(csvRow(record));
            }
            out.flush(); bytes.getFD().sync();
        } catch (IOException failure) { temporary.delete(); throw failure; }
        try {
            if (!mapped || !profile.equals(inspectJournal(temporary).scooterId) || !temporary.renameTo(source))
                throw new IOException("Could not associate the restored ride.");
        } catch (IOException failure) { temporary.delete(); throw failure; }
    }

    private static CurrentDirection direction(String value) {
        try { return CurrentDirection.valueOf(value); }
        catch (IllegalArgumentException ignored) { return CurrentDirection.UNCONFIRMED; }
    }
    private File file(String id) {
        if (id == null || !id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("Invalid ride ID");
        return new File(directory, id + ".csv");
    }
    private static boolean isJournal(File file) {
        return file.isFile() && file.getName().matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.csv");
    }
    /** Remove an incomplete trailing frame before appending after a crash. */
    private static void repairTail(File file) throws IOException {
        File replacement = new File(file.getParentFile(), file.getName() + ".repair");
        Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(replacement), "UTF-8"));
        List<List<String>> pending = null;
        try {
            List<String> row;
            while ((row = csvRecord(reader)) != null) {
                if (row.size() != 15) continue;
                if ("BEGIN".equals(row.get(1))) { pending = new ArrayList<List<String>>(); pending.add(row); }
                else if ("SENSOR".equals(row.get(1))) { if (pending != null && pending.size() <= MAX_METRICS) pending.add(row); }
                else if ("COMMIT".equals(row.get(1))) {
                    if (pending != null && pending.get(0).get(5).equals(row.get(5))) {
                        for (List<String> r : pending) writer.write(csvRow(r)); writer.write(csvRow(row));
                    }
                    pending = null;
                } else { pending = null; writer.write(csvRow(row)); }
            }
        } finally { reader.close(); writer.close(); }
        if (!replacement.renameTo(file)) throw new IOException("Could not recover interrupted ride journal");
    }
    private static void writeNew(File file, String text) throws IOException {
        if (file.exists()) throw new IOException("Ride ID already exists");
        write(file, text, false);
    }
    private static void append(File file, String text) throws IOException { write(file, text, true); }
    private static void write(File file, String text, boolean append) throws IOException {
        FileOutputStream stream = new FileOutputStream(file, append);
        try { stream.write(text.getBytes("UTF-8")); stream.flush(); }
        finally { stream.close(); }
    }
    private static String row(String event, long wall, long mono, String frame, String key,
            String value, String text, String unit, String cmd, String updated, String age,
            String direction, String details) {
        ArrayList<String> columns = new ArrayList<String>();
        Collections.addAll(columns, Integer.toString(SCHEMA_VERSION), event, utc(wall),
                Long.toString(wall), Long.toString(mono), frame, key, value, text, unit,
                cmd, updated, age, direction, details);
        return csvRow(columns);
    }
    private static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date(millis));
    }
    private static String csvRow(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            String v = value == null ? "" : value;
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
                out.append('"').append(v.replace("\"", "\"\"")).append('"');
            else out.append(v);
        }
        return out.append('\n').toString();
    }
    /** Streaming RFC4180 reader. A partial final row or quoted field is discarded. */
    static List<String> csvRecord(Reader reader) throws IOException {
        ArrayList<String> result = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, afterQuote = false, began = false;
        int size = 0, c;
        while ((c = reader.read()) != -1) {
            began = true;
            if (++size > 65536) throw new IOException("Ride journal record too large");
            if (quoted) {
                if (c == '"') { quoted = false; afterQuote = true; }
                else field.append((char) c);
            } else if (afterQuote && c == '"') { field.append('"'); quoted = true; afterQuote = false; }
            else if (c == ',' ) { result.add(field.toString()); field.setLength(0); afterQuote = false; }
            else if (c == '\n') { result.add(field.toString()); return result; }
            else if (c == '\r') { /* CRLF; CR inside quotes was retained above. */ }
            else if (c == '"' && field.length() == 0 && !afterQuote) quoted = true;
            else { field.append((char) c); afterQuote = false; }
        }
        return null; // Journals always end each committed row with a newline.
    }
    private static boolean isSecret(String key) {
        String lower = key.toLowerCase(Locale.US);
        return lower.contains("password") || lower.contains("token") || lower.contains("credential")
                || lower.contains("owner_identity") || lower.contains("auth_identity") || lower.equals("pin");
    }
    private static String trim(String s, int max) {
        if (s == null) return ""; if(s.length()<=max)return s;
        int end=max;if(end>0&&Character.isHighSurrogate(s.charAt(end-1))&&Character.isLowSurrogate(s.charAt(end)))end--;
        return s.substring(0,end);
    }
    private static boolean finite(Double value) { return value != null && !value.isNaN() && !value.isInfinite(); }
    private static Double subtract(Double a, Double b) { return a == null || b == null ? null : a - b; }
    private static Double min(Double a, double b) { return a == null ? b : Math.min(a, b); }
    private static Double max(Double a, double b) { return a == null ? b : Math.max(a, b); }
    private static String json(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (out.length() > 1) out.append(','); out.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            out.append(v == null ? "null" : v instanceof Number || v instanceof Boolean ? v.toString() : quote(v.toString()));
        }
        return out.append('}').toString();
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 32) out.append(String.format(Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
