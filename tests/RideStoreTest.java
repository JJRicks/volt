package com.jjricks.volt;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure Java verification; uses isolated temporary journals, no scooter mutation. */
public final class RideStoreTest {
    private static int assertions;
    private static final long WALL = 1791806400000L;
    public static void main(String[] args) throws Exception {
        throughputIsNotInventedConsumption();
        signedIntegrationSplitsZeroCrossings();
        reversedDirectionAndConfigurationPhases();
        pairedFreshMeasurementsAndGaps();
        movingAndStationaryEnergyHaveSeparateCoverage();
        disconnectedHeartbeatAddsExposureWithoutFakeSamples();
        efficiencyRequiresDistanceAndCoverage();
        csvEscapingAndRestartRecovery();
        damagedCommittedFrameIsNotPartiallyAccepted();
        clockChangesDoNotInventEnergy();
        sessionsAreIndependentAndManageable();
        highRateRecordingRemainsBoundedAndReplayable();
        renamePreservesMeasurements();
        fullDetailPreservesUnthinnedSamples();
        completedTrendReusesCachedPreview();
        serviceStartReadIsKept();
        analysisSnapshotsFillTheListCache();
        longHistoryNeverRereadsEveryRide();
        unfinishedJournalsAreNeverServedAsFinished();
        listReplayDoesNotBlockRecording();
        deferredStartReadsOnlyUnfinishedJournals();
        unsignedCurrentIsDirectionUnconfirmed();
        System.out.println("RideStoreTest: " + assertions + " assertions passed");
    }
    private static File directory() throws Exception { return Files.createTempDirectory("navee-rides-test-").toFile(); }
    private static Map<String, RideStore.Sample> snapshot(long mono, double watts, double speed, double odo) {
        Map<String, RideStore.Sample> values = new LinkedHashMap<String, RideStore.Sample>();
        values.put("voltage_v", new RideStore.Sample(50.0, "50 V", "V", 0x72, mono));
        values.put("current_a", new RideStore.Sample(watts / 50, "current", "A", 0x72, mono));
        values.put("power_w", new RideStore.Sample(watts, "power", "W", 0x72, mono));
        values.put("speed_kmh", new RideStore.Sample(speed, mono));
        values.put("odometer_km", new RideStore.Sample(odo, mono));
        values.put("battery_percent", new RideStore.Sample(80, mono));
        values.put("temperature_c", new RideStore.Sample(21, mono));
        return values;
    }
    private static RideStore.Summary signRide(RideStore store, RideStore.Sample sign, double firstWatts) throws Exception {
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        store.start("scooter", "Sign", WALL, 0);
        for (int i = 0; i <= 2; i++) {
            Map<String, RideStore.Sample> m = snapshot(i * 1000, i == 0 ? firstWatts : 360, 36, 10 + i * .1);
            if (sign != null) m.put("current_sign_state", sign);
            store.sample(WALL + i * 1000, i * 1000, m);
        }
        return store.stop(WALL + 2000, 2000);
    }
    /** A sign state that was never 0 or 1 and no negative current: no Draw/Input claim, however the ride was recorded. */
    private static void unsignedCurrentIsDirectionUnconfirmed() throws Exception {
        File dir = directory();
        RideStore store = new RideStore(dir);
        RideStore.Summary unsigned = signRide(store, new RideStore.Sample(254.0, "254 (raw)", "", 0x79, 0), 360);
        check(!unsigned.directionConfirmed, "A 254 sign state without a negative current is unconfirmed");
        check(unsigned.drawWh == null && unsigned.regeneratedWh == null && unsigned.returnedWh == null && unsigned.netConsumptionWh == null,
                "It claims no draw, input or net");
        check(unsigned.confirmedDirectionCoverageMs == 0 && unsigned.unconfirmedDirectionCoverageMs == 2000,
                "And its whole coverage counts as unconfirmed, so no partial-direction row shows");
        near(0.2, unsigned.measuredThroughputWh, "Throughput stays measured");
        near(0.2, unsigned.unsignedDrawWh, "Learned range keeps the unsigned energy");
        check(unsigned.netWhPerKm == null && unsigned.energyInterpretation.startsWith("Absolute measured"), "And per-km and the interpretation follow");
        String id = unsigned.id;
        RideStore reloaded = new RideStore(dir);
        RideStore.Summary replayed = reloaded.get(id);
        check(replayed != null && !replayed.directionConfirmed && replayed.drawWh == null && replayed.unsignedDrawWh != null,
                "A saved ride is judged the same when read back");

        RideStore.Summary negative = signRide(new RideStore(directory()), new RideStore.Sample(254.0, "254 (raw)", "", 0x79, 0), -360);
        check(negative.directionConfirmed && negative.regeneratedWh != null && negative.unsignedDrawWh == null, "Any negative current means the ride was signed");
        RideStore.Summary known = signRide(new RideStore(directory()), new RideStore.Sample(0.0, "0", "", 0x79, 0), 360);
        check(known.directionConfirmed && known.drawWh != null, "A sign state of 0 is confirmed");
        // UT5 MAX: the key exists in every frame but never has a value.
        RideStore.Summary empty = signRide(new RideStore(directory()), new RideStore.Sample(null, "", "", -1, -1), 360);
        check(empty.directionConfirmed && empty.drawWh != null && empty.unsignedDrawWh == null, "An empty sign state row is no sign state");
        RideStore.Summary missing = signRide(new RideStore(directory()), null, 360);
        check(missing.directionConfirmed && missing.drawWh != null, "No sign state at all is confirmed, as recordings always were");
    }
    private static void throughputIsNotInventedConsumption() throws Exception {
        RideStore store = new RideStore(directory());
        store.start("UT5", "Sport + boost", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 360, 36, 10));
        store.sample(WALL + 1000, 1000, snapshot(1000, 360, 36, 10.1));
        store.sample(WALL + 2000, 2000, snapshot(2000, 360, 36, 10.2));
        RideStore.Summary s = store.stop(WALL + 2000, 2000);
        near(0.2, s.measuredThroughputWh, "Unconfirmed direction records measured absolute throughput");
        check(s.drawWh == null && s.regeneratedWh == null && s.netConsumptionWh == null,
                "No battery draw or regeneration assertion without confirmed sign direction");
        near(0.2, s.distanceKm, "Odometer difference in km");
        near(0.2 / 1.609344, s.distanceMiles, "Exact metric to imperial distance conversion");
        near(1, s.throughputWhPerKm, "Throughput per sufficient positive distance");
        near(1.609344, s.throughputWhPerMile, "Wh/km to Wh/mile multiplies by km/mile");
        check(s.netWhPerKm == null && s.efficiencyStatus.contains("unconfirmed"), "Unconfirmed efficiency labelled honestly");
        near(36, s.averageSpeedKmh, "Time-weighted average speed");
        near(0.02, s.speedIntegratedDistanceKm, "Integrated speed distance explicitly separate from odometer");
        near(360, s.averageAbsolutePowerW, "Time-weighted absolute power");
        check(s.movingMs == 2000 && s.stationaryMs == 0 && s.electricalCoverage == 1,
                "Moving time and full power coverage");
        check(!s.toJson().contains("NaN") && s.toJson().contains("\"netConsumptionWh\":null"), "Unavailable JSON quantities are null");
    }
    private static void signedIntegrationSplitsZeroCrossings() throws Exception {
        RideStore store = new RideStore(directory());
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        store.start("UT5", "Sign test", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 100, 0, 0));
        store.sample(WALL + 1000, 1000, snapshot(1000, -100, 0, 0));
        store.sample(WALL + 2000, 2000, snapshot(2000, -100, 0, 0));
        RideStore.Summary s = store.stop(WALL + 2000, 2000);
        near(25.0 / 3600, s.drawWh, "Crossing positive triangle split at zero");
        near(125.0 / 3600, s.regeneratedWh, "Crossing negative triangle plus full negative interval");
        near(150.0 / 3600, s.measuredThroughputWh, "Zero crossing does not double count absolute trapezoid");
        near(-100.0 / 3600, s.netConsumptionWh, "Confirmed signed net energy");
        check(s.directionConfirmed && s.stationaryMs == 2000, "Confirmed sign and stationary classification");
    }
    private static void reversedDirectionAndConfigurationPhases() throws Exception {
        File dir = directory();
        RideStore store = new RideStore(dir);
        store.setCurrentDirection(RideStore.CurrentDirection.NEGATIVE_IS_DRAW);
        String id = store.start("UT5", "Reverse sign", WALL, 0);
        store.sample(WALL, 0, snapshot(0, -72, 10, 1));
        store.sample(WALL + 1000, 1000, snapshot(1000, -72, 10, 1));
        near(0.02, store.activeSummary().drawWh, "Negative configured as draw");
        store.setCurrentDirection(RideStore.CurrentDirection.UNCONFIRMED);
        store.sample(WALL + 2000, 2000, snapshot(2000, 72, 10, 1));
        store.sample(WALL + 3000, 3000, snapshot(3000, 72, 10, 1));
        RideStore.Summary s = store.stop(WALL + 3000, 3000);
        near(0.04, s.measuredThroughputWh, "Configuration change starts new anchor without rewriting history");
        check(s.confirmedDirectionCoverageMs == 1000 && s.unconfirmedDirectionCoverageMs == 1000,
                "Both interpretation phases retained");
        check(!s.directionConfirmed && s.netConsumptionWh == null, "Partial sign confirmation is not full net consumption");
        RideStore.Summary recovered = new RideStore(dir).get(id);
        near(s.measuredThroughputWh, recovered.measuredThroughputWh, "Interpretation phase recovery matches live state");
        check(s.toJson().equals(recovered.toJson()), "Stopped metadata and analysis replay exactly");
    }
    private static void pairedFreshMeasurementsAndGaps() throws Exception {
        RideStore store = new RideStore(directory());
        store.start("UT5", "Coverage", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 100, 0, 1));
        Map<String, RideStore.Sample> mismatched = snapshot(1000, 100, 0, 1);
        mismatched.put("current_a", new RideStore.Sample(2.0, "old current", "A", 0x72, 0));
        store.sample(WALL + 1000, 1000, mismatched);
        store.sample(WALL + 2000, 2000, snapshot(2000, 100, 0, 1));
        store.sample(WALL + 3000, 3000, snapshot(3000, 100, 0, 1));
        near(100.0 / 3600, store.activeSummary().measuredThroughputWh, "Mismatched V/A pair never combines old current with new voltage");
        store.sample(WALL + 4000, 4000, snapshot(0, 100, 0, 1));
        store.sample(WALL + 8000, 8000, snapshot(8000, 100, 0, 1));
        store.sample(WALL + 9000, 9000, snapshot(9000, 100, 0, 1));
        store.markGap(WALL + 10000, 10000, "Bluetooth disconnected");
        store.sample(WALL + 11000, 11000, snapshot(11000, 100, 0, 1));
        store.sample(WALL + 12000, 12000, snapshot(12000, 100, 0, 1));
        RideStore.Summary s = store.stop(WALL + 12000, 12000);
        near(300.0 / 3600, s.measuredThroughputWh, "Stale, long and explicit disconnected intervals excluded");
        check(s.electricalCoverageMs == 3000 && s.skippedElectricalMs == 9000 && s.gapCount == 2,
                "Missing exposure and discrete gaps visible");
        Map<String, RideStore.Sample> a = snapshot(15000, 100, 0, 1);
        a.put("current_a", new RideStore.Sample(2.0, "Other source", "A", 0x90, 15000));
        store.start("UT5", "Different reports", WALL + 15000, 15000);
        store.sample(WALL + 15000, 15000, a); store.sample(WALL + 16000, 16000, a);
        check(store.activeSummary().measuredThroughputWh == null, "Same timestamp from differing source reports is not a paired measurement");
        store.stop(WALL + 16000, 16000);
    }
    private static void efficiencyRequiresDistanceAndCoverage() throws Exception {
        RideStore store = new RideStore(directory());
        store.start("UT5", "Little distance", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 100, 36, 10));
        store.sample(WALL + 1000, 1000, snapshot(1000, 100, 36, 10.1));
        check(store.activeSummary().throughputWhPerKm == null, "Small odometer delta cannot create misleading efficiency");
        store.sample(WALL + 2000, 2000, snapshot(2000, 100, 36, 11));
        store.markGap(WALL + 10000, 10000, "Disconnected most of the ride");
        check(store.activeSummary().throughputWhPerKm == null && store.activeSummary().electricalCoverage == 0.2,
                "Poor coverage suppresses efficiency despite positive distance");
        store.sample(WALL + 11000, 11000, snapshot(11000, 100, 36, 9));
        check(store.activeSummary().odometerReset && store.activeSummary().distanceKm == null,
                "Odometer reversal doesn't masquerade as negative or absolute distance");
        store.stop(WALL + 11000, 11000);
    }
    private static void disconnectedHeartbeatAddsExposureWithoutFakeSamples() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("UT5", "Bluetooth gap", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 360, 20, 1));
        store.sample(WALL + 1000, 1000, snapshot(1000, 360, 20, 1));
        store.markGap(WALL + 2000, 2000, "Bluetooth disconnected");
        for (int i = 3; i <= 12; i++) store.heartbeat(WALL + i * 1000, i * 1000);
        RideStore.Summary live = store.activeSummary();
        check(live.elapsedMs == 12000 && live.sampleCount == 2 && live.gapCount == 1,
                "Heartbeat advances one disconnected interval without fake samples or extra gaps");
        near(0.1, live.measuredThroughputWh, "Missing interval never creates energy");
        check(live.electricalCoverageMs == 1000 && live.skippedElectricalMs == 11000,
                "Live missing exposure and coverage remain truthful during disconnect");
        store.sample(WALL + 13000, 13000, snapshot(13000, 360, 20, 1));
        near(0.1, store.activeSummary().measuredThroughputWh, "Reconnect establishes an anchor without bridging gap");
        store.sample(WALL + 14000, 14000, snapshot(14000, 360, 20, 1));
        RideStore.Summary stopped = store.stop(WALL + 14000, 14000);
        near(0.2, stopped.measuredThroughputWh, "Fresh adjacent post-gap samples resume energy integration");
        check(new RideStore(dir).get(id).toJson().equals(stopped.toJson()), "Heartbeat events replay to exactly the same analysis");
    }
    private static void movingAndStationaryEnergyHaveSeparateCoverage() throws Exception {
        RideStore store = new RideStore(directory());
        store.start("UT5", "Idle drain", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 36, 0, 10));
        store.sample(WALL + 1000, 1000, snapshot(1000, 36, 0, 10));
        store.sample(WALL + 2000, 2000, snapshot(2000, 36, 20, 10));
        Map<String, RideStore.Sample> unknown = snapshot(3000, 36, 20, 10);
        unknown.remove("speed_kmh");
        store.sample(WALL + 3000, 3000, unknown);
        RideStore.Summary s = store.stop(WALL + 3000, 3000);
        near(0.01, s.stationaryThroughputWh, "Stationary electrical throughput separated from moving intervals");
        near(0.01, s.movingThroughputWh, "Moving interval throughput distinguished");
        near(0.01, s.unknownMotionThroughputWh, "Missing speed does not imply stationary scooter");
        check(s.stationaryElectricalCoverageMs == 1000 && s.movingElectricalCoverageMs == 1000
                && s.unknownMotionElectricalCoverageMs == 1000, "Motion quality coverage reconciles to electrical coverage");
    }
    private static void csvEscapingAndRestartRecovery() throws Exception {
        File dir = directory();
        RideStore original = new RideStore(dir);
        String label = "Sport, \"boost\"\nHills 🛴";
        String id = original.start("Owner, scooter", label, WALL, 0);
        String notes = "Compare sport\nwith \"boost\", tomorrow";
        original.setNotes(notes);
        Map<String, RideStore.Sample> values = snapshot(0, 90, 20, 3);
        values.put("sensor_text", new RideStore.Sample(null, "Value, \"quoted\"\nsecond line", "unknown", 0x72, 0));
        values.put("owner_identity", new RideStore.Sample(null, "PRIVATE", "", 0x30, 0));
        values.put("api_token", new RideStore.Sample(null, "PRIVATE", "", 0x30, 0));
        original.sample(WALL, 0, values);
        original.sample(WALL + 1000, 1000, snapshot(1000, 90, 20, 3));
        File journal = new File(dir, id + ".csv");
        String truncated = "1,BEGIN,2026-10-12T12:00:02.000Z," + (WALL + 2000) + ",2000,3,,,,,,,,,\n"
                + "1,SENSOR,2026-10-12T12:00:02.000Z," + (WALL + 2000) + ",2000,3,battery_percent,5,\"interrupted";
        FileOutputStream append = new FileOutputStream(journal, true);
        append.write(truncated.getBytes(StandardCharsets.UTF_8)); append.close();
        RideStore recovered = new RideStore(dir);
        RideStore.Summary s = recovered.activeSummary();
        check(s.recovered && s.active && s.sampleCount == 2 && id.equals(recovered.activeId()), "Active ride recovered excluding unfinished sample");
        check(label.equals(s.label) && notes.equals(s.notes), "CSV multiline labels, quotes and Unicode survive recovery");
        near(90.0 / 3600, s.measuredThroughputWh, "Committed energy restored");
        recovered.sample(WALL + 3000, 3000, snapshot(3000, 90, 20, 3));
        near(90.0 / 3600, recovered.activeSummary().measuredThroughputWh, "Restart gap does not integrate across unobserved interval");
        recovered.sample(WALL + 4000, 4000, snapshot(4000, 90, 20, 3));
        RideStore.Summary stopped = recovered.stop(WALL + 4000, 4000);
        near(180.0 / 3600, stopped.measuredThroughputWh, "Recording resumes after anchor re-established");
        File csv = new File(dir, "export.csv"); recovered.exportCsv(id, csv);
        String content = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        check(content.startsWith("schema_version,event,recorded_utc") && content.contains("sensor_age_ms"), "Export includes schema version, date and measurement age");
        check(content.contains("\"Sport, \"\"boost\"\"\nHills 🛴\""), "CSV quote escaping is RFC4180 compatible");
        check(!content.contains("PRIVATE") && !content.contains("owner_identity") && !content.contains("api_token"), "Credentials never enter diagnostics exports");
        check(!content.contains("interrupted"), "Incomplete quoted tail removed before new frames append");
        check(new RideStore(dir).get(id).toJson().equals(stopped.toJson()), "Repaired journal replays the complete stopped ride");
    }
    private static void clockChangesDoNotInventEnergy() throws Exception {
        RideStore store = new RideStore(directory());
        store.start("UT5", "Clock", WALL, 10000);
        store.sample(WALL, 10000, snapshot(10000, 100, 10, 1));
        store.sample(WALL - 900000, 11000, snapshot(11000, 100, 10, 1));
        check(store.activeSummary().elapsedMs == 1000, "Wall-clock correction cannot affect measured duration");
        store.sample(WALL - 899000, 100, snapshot(100, 100, 10, 1));
        RideStore.Summary s = store.activeSummary();
        check(s.clockDiscontinuities == 1 && s.gapCount == 1 && s.elapsedMs == 2000,
                "Monotonic reboot counted as missing wall-time exposure");
        near(100.0 / 3600, s.measuredThroughputWh, "Clock discontinuity never creates energy");
        store.sample(WALL - 898000, 1100, snapshot(1100, 100, 10, 1));
        near(200.0 / 3600, store.activeSummary().measuredThroughputWh, "Fresh post-reboot interval recovers");
        store.stop(WALL - 898000, 1100);
    }
    private static void damagedCommittedFrameIsNotPartiallyAccepted() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("UT5", "Frame integrity", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 100, 0, 1));
        store.sample(WALL + 1000, 1000, snapshot(1000, 100, 0, 1));
        store.stop(WALL + 1000, 1000);
        check(store.get(id).sampleCount == 2, "Original committed frame complete");
        File source = new File(dir, id + ".csv");
        String original = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        StringBuilder changed = new StringBuilder();
        for (String line : original.split("\n")) if (!line.contains(",1000,2,voltage_v,")) changed.append(line).append('\n');
        check(changed.length() < original.length(), "Fixture removes one row while preserving commit");
        Files.write(source.toPath(), changed.toString().getBytes(StandardCharsets.UTF_8));
        RideStore.Summary s = store.get(id);
        check(s.sampleCount == 1 && s.measuredThroughputWh == null,
                "Changed file invalidates cache; declared sensor count rejects whole damaged frame");
    }
    private static void sessionsAreIndependentAndManageable() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String first = store.start("UT5-A", "Sport", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 100, 0, 1)); store.stop(WALL, 0);
        String second = store.start("UT5-B", "Boost", WALL + 10000, 10000);
        store.sample(WALL + 10000, 10000, snapshot(10000, -50, 0, 2));
        check(store.activeSummary().measuredThroughputWh == null && store.activeSummary().sampleCount == 1,
                "Session has independent integrator and metadata");
        boolean rejected = false;
        try { store.delete(second); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Active ride cannot be deleted while recording");
        store.stop(WALL + 10000, 10000);
        List<RideStore.Summary> list = store.list();
        check(list.size() == 2 && second.equals(list.get(0).id) && first.equals(list.get(1).id), "Sessions listed newest first");
        rejected = false; try { store.get("../private"); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Storage rejects path traversal IDs");
        rejected = false; try { list.get(0).fields().put("netConsumptionWh", 100.0); }
        catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, "Summary maps immutable");
        check(store.delete(first) && store.get(first) == null && !store.delete(first), "Delete affects only selected stopped journal");
        check(store.list().size() == 1, "Remaining ride preserved");
    }
    private static void highRateRecordingRemainsBoundedAndReplayable() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("UT5", "High-rate fixture", WALL, 0);
        final int frames = 7000;
        for (int i = 0; i < frames; i++) {
            long mono = i * 100L;
            Map<String, RideStore.Sample> values = new LinkedHashMap<String, RideStore.Sample>();
            values.put("voltage_v", new RideStore.Sample(50, mono));
            values.put("current_a", new RideStore.Sample(2, mono));
            values.put("speed_kmh", new RideStore.Sample(i % 2 == 0 ? 0 : 20, mono));
            store.sample(WALL + mono, mono, values);
        }
        RideStore.Summary s = store.stop(WALL + (frames - 1) * 100L, (frames - 1) * 100L);
        near((frames - 1) * 100.0 * 100 / 3600000, s.measuredThroughputWh, "High-rate integration counts each adjacent interval exactly once");
        check(s.sampleCount == frames && s.electricalCoverage == 1, "No hidden high-rate data loss");
        List<RideStore.Point> points = store.trend(id, 2000);
        check(points.size() <= RideStore.MAX_TREND_POINTS && points.size() >= 2, "In-memory trend bounded regardless of ride duration");
        check(points.get(0).elapsedMs == 0 && points.get(points.size() - 1).elapsedMs == s.elapsedMs,
                "Trend downsampling retains first and latest measurements");
        List<RideStore.Point> small = store.trend(id, 12);
        check(small.size() == 12 && small.get(11).elapsedMs == s.elapsedMs, "Requested chart resolution respected");
        RideStore.Summary replay = new RideStore(dir).get(id);
        check(s.toJson().equals(replay.toJson()), "High-rate persisted analysis matches live analysis");
    }
    private static void fullDetailPreservesUnthinnedSamples() throws Exception {
        RideStore store = new RideStore(directory()); String id = store.start("UT5", "Full detail", WALL, 0);
        final int frames = 4096, spike = 1235;
        for (int i = 0; i < frames; i++) {
            long mono = i * 1000L;
            if (i == 2048) store.markGap(WALL + mono - 500, mono - 500, "Disconnected");
            Map<String, RideStore.Sample> row = snapshot(mono, i == spike ? 4321 : 100, 20, i * .001);
            if (i == 3001) row.remove("speed_kmh");
            store.sample(WALL + mono, mono, row);
        }
        boolean rejected = false;
        try { store.fullTrend(id); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "Full-detail reader refuses an active journal");
        store.stop(WALL + frames * 1000L, frames * 1000L);
        List<RideStore.Point> preview = store.trend(id, 600), full = store.fullTrend(id);
        check(preview.size() <= 600 && full.size() == frames, "Preview bounded; full replay includes every committed frame");
        check(preview.stream().noneMatch(p -> p.elapsedMs == spike * 1000L), "Single-frame spike lies between preview samples");
        near(4321, full.get(spike).powerW, "Full-detail keeps the exact short spike");
        check(full.get(spike).elapsedMs == spike * 1000L && full.get(spike).wallMs == WALL + spike * 1000L
                && full.get(spike).monotonicMs == spike * 1000L, "Full-detail preserves source clocks");
        check(full.get(2047).segment != full.get(2048).segment, "Full-detail retains explicit gap edges");
        check(full.get(3001).speedKmh == null, "Full-detail retains missing values without filling them");
        check(full.get(0).elapsedMs == 0 && full.get(frames - 1).elapsedMs == (frames - 1) * 1000L, "Full-detail retains both endpoints");
        rejected = false; try { full.clear(); } catch (UnsupportedOperationException expected) { rejected = true; }
        check(rejected, "Full snapshot cannot be changed by the UI");
        Thread.currentThread().interrupt(); boolean canceled = false;
        try { store.fullTrend(id); } catch (java.io.InterruptedIOException expected) { canceled = true; } finally { Thread.interrupted(); }
        check(canceled && store.fullTrend(id).size() == frames, "Canceled full replay closes its reader and can be retried");
    }
    private static void renamePreservesMeasurements() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("UT5", "Original", WALL, 0);
        store.sample(WALL, 0, snapshot(0, 500, 20, 1)); store.sample(WALL + 1000, 1000, snapshot(1000, 500, 20, 1.005));
        RideStore.Summary original = store.stop(WALL + 2000, 2000);
        store.rename(id, "Campus, return \"ride\"");
        RideStore.Summary renamed = new RideStore(dir).get(id);
        check(renamed.label.equals("Campus, return \"ride\""), "Quoted rename survives replay after STOP");
        check(renamed.sampleCount == original.sampleCount && renamed.elapsedMs == original.elapsedMs, "Rename preserves time and samples");
        near(original.measuredThroughputWh, renamed.measuredThroughputWh, "Rename preserves energy");
        check(store.list().get(0).label.equals(renamed.label), "Cached list picks up new title");
        boolean rejected = false; try { store.rename(id, "   "); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Empty rename rejected");
        store.rename(id, "Second name"); check(new RideStore(dir).get(id).label.equals("Second name"), "Newest rename wins");
        String prefix=new String(new char[199]).replace('\0','a');
        store.rename(id,prefix+"🛴extra");check(new RideStore(dir).get(id).label.equals(prefix),"Name truncation never leaves half an emoji");
        prefix=prefix.substring(1);store.rename(id,prefix+"🛴extra");check(new RideStore(dir).get(id).label.equals(prefix+"🛴"),"Complete boundary emoji survives journal replay");
    }
    private static void completedTrendReusesCachedPreview() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir); String id = store.start("UT5", "Cached preview", WALL, 0);
        for (int i = 0; i < 1500; i++) store.sample(WALL + i * 1000L, i * 1000L, snapshot(i * 1000L, 100 + i % 7, 20, i * .001));
        check(store.trend(id, 600) != store.trend(id, 600), "An active ride's preview follows new samples instead of a cached copy");
        store.stop(WALL + 1500 * 1000L, 1500 * 1000L);
        List<RideStore.Point> first = store.trend(id, 600);
        check(store.trend(id, 600) == first && store.trend(id, 2000) == first, "Reopening a completed ride reuses its preview instead of replaying the journal");
        check(sameTrend(first, new RideStore(dir).trend(id, 600)), "The cached preview equals a fresh replay");
        check(store.trend(id, 12).size() == 12, "A smaller requested resolution still replays at that resolution");
        new RideStore(dir).rename(id, "Renamed elsewhere");
        List<RideStore.Point> changed = store.trend(id, 600);
        check(changed != first && sameTrend(first, changed), "A journal changed by another store is replayed again");
        store.delete(id);
        check(store.trend(id, 600).isEmpty(), "A deleted ride has no preview");
    }
    /** K finished one-sample-a-second rides, each stopped; returns their ids. */
    private static List<String> finishedRides(RideStore store, int count, int frames) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (int k = 0; k < count; k++) {
            long wall = WALL + k * 86400000L;
            String id = store.start("UT5", "Ride " + k, wall, 0);
            for (int i = 0; i < frames; i++) store.sample(wall + i * 1000L, i * 1000L, snapshot(i * 1000L, 100 + i % 5, 20, i * .001));
            store.stop(wall + frames * 1000L, frames * 1000L);
            ids.add(id);
        }
        return ids;
    }
    private static long replays() { return RideStore.replays.get(); }
    private static void serviceStartReadIsKept() throws Exception {
        File dir = directory(); List<String> ids = finishedRides(new RideStore(dir), 5, 120);
        long before = replays();
        RideStore store = new RideStore(dir);
        check(replays() - before == 5, "The service-start read replays each journal once");
        before = replays();
        List<RideStore.Summary> list = store.list();
        List<RideStore.Point> trend = store.trend(ids.get(2), 600);
        check(replays() == before && list.size() == 5, "After service start, list() and trend() replay nothing");
        for (RideStore.Summary s : list)
            check(s.toJson().equals(RideStore.inspectJournal(new File(dir, s.id + ".csv")).toJson()), "Kept summary equals a fresh replay");
        File copy = directory();
        for (String id : ids) Files.copy(new File(dir, id + ".csv").toPath(), new File(copy, id + ".csv").toPath());
        RideStore other = new RideStore(copy);
        File touched = new File(copy, ids.get(2) + ".csv");
        check(touched.setLastModified(touched.lastModified() + 5000), "Fixture changes the modified time");
        before = replays();
        check(sameTrend(trend, other.trend(ids.get(2), 600)) && replays() - before == 1, "A changed modified time replays, and the kept preview equals it");
        before = replays(); other.get(ids.get(2)); other.list();
        check(replays() == before, "The replayed journal is cached again");
        Files.write(touched.toPath(), "\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        before = replays(); other.get(ids.get(2));
        check(replays() - before == 1, "A changed length replays");
        store.rename(ids.get(0), "Renamed");
        before = replays();
        check(store.get(ids.get(0)).label.equals("Renamed") && replays() - before == 1, "A rename replays that ride once");
        check(store.delete(ids.get(1)) && store.get(ids.get(1)) == null && store.list().size() == 4, "A deleted ride leaves the cache");
    }
    private static void analysisSnapshotsFillTheListCache() throws Exception {
        RideStore store = new RideStore(directory()); finishedRides(store, 4, 30);
        long before = replays();
        check(store.analysisSnapshots("UT5", 0).size() == 4 && replays() - before == 4, "Rides recorded in this process are replayed once for analysis");
        before = replays();
        check(store.list().size() == 4 && replays() == before, "After analysisSnapshots(), list() replays nothing");
    }
    private static void longHistoryNeverRereadsEveryRide() throws Exception {
        File dir = directory(); List<String> ids = finishedRides(new RideStore(dir), 300, 2);
        RideStore store = new RideStore(dir);
        long before = replays();
        store.list(); store.list();
        check(replays() == before, "With 300 rides, list() replays nothing, even twice");
        for (String id : ids) store.trend(id, 600);
        long previews = replays() - before;
        check(previews >= 300 - RideStore.PREVIEW_CAP && previews <= 300, "Only previews past the cap are replayed (" + previews + ")");
        before = replays();
        check(store.list().size() == 300 && replays() == before, "Trimmed previews keep their summaries");
        String recent = ids.get(ids.size() - 1);
        before = replays(); store.trend(recent, 600);
        check(replays() == before, "The most recently used preview stays cached");
    }
    private static void unfinishedJournalsAreNeverServedAsFinished() throws Exception {
        File dir = directory(), older = directory(), newer = directory();
        RideStore first = new RideStore(older); String stale = first.start("UT5", "Older unfinished", WALL, 0);
        first.sample(WALL, 0, snapshot(0, 100, 20, 1));
        RideStore second = new RideStore(newer); String recent = second.start("UT5", "Newer unfinished", WALL + 60000, 0);
        second.sample(WALL + 60000, 0, snapshot(0, 100, 20, 1));
        Files.copy(new File(older, stale + ".csv").toPath(), new File(dir, stale + ".csv").toPath());
        Files.copy(new File(newer, recent + ".csv").toPath(), new File(dir, recent + ".csv").toPath());
        RideStore store = new RideStore(dir);
        check(recent.equals(store.activeId()), "The newest unfinished journal is recovered as the active ride");
        long before = replays();
        RideStore.Summary summary = store.get(stale); store.get(stale);
        check(summary != null && summary.active && replays() - before == 1, "An older unfinished journal's summary is cached by its file stamp");
        before = replays(); store.trend(stale, 600); store.trend(stale, 600);
        check(replays() - before == 2, "An unfinished journal's preview is never cached as finished");
        check(store.analysisSnapshots("UT5", 0).stream().noneMatch(s -> s.summary.id.equals(stale)), "Analysis never includes an unfinished journal");
        store.stop(WALL + 70000, 10000);
    }
    private static void listReplayDoesNotBlockRecording() throws Exception {
        File dir = directory(); RideStore setup = new RideStore(dir); String finished = finishedRides(setup, 1, 20).get(0);
        RideStore store = new RideStore(dir);
        File source = new File(dir, finished + ".csv");
        check(source.setLastModified(source.lastModified() + 5000), "Fixture forces one cache miss");
        store.start("UT5", "Recording", WALL + 9 * 86400000L, 0);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        Thread lister = new Thread(() -> { try { store.list(); } catch (Exception e) { throw new RuntimeException(e); } }, "lister");
        RideStore.replayHook = () -> {
            if (!Thread.currentThread().getName().equals("lister")) return;
            entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        try {
            lister.start();
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "list() is replaying");
            Thread recorder = new Thread(() -> { try { store.sample(WALL + 9 * 86400000L + 1000, 1000, snapshot(1000, 100, 20, 1)); } catch (Exception e) { throw new RuntimeException(e); } });
            recorder.start(); recorder.join(2000);
            check(!recorder.isAlive(), "A sample is written while list() replays a journal");
        } finally { release.countDown(); RideStore.replayHook = null; }
        lister.join(5000);
        check(!lister.isAlive() && store.activeSummary().sampleCount == 1, "list() finishes and the sample was kept");
        store.stop(WALL + 9 * 86400000L + 2000, 2000);
    }
    private static void deferredStartReadsOnlyUnfinishedJournals() throws Exception {
        File dir = directory(); RideStore setup = new RideStore(dir);
        List<String> ids = finishedRides(setup, 3, 400);
        File f0 = new File(dir, ids.get(0) + ".csv"), f1 = new File(dir, ids.get(1) + ".csv"), f2 = new File(dir, ids.get(2) + ".csv");
        check(f0.length() > 16384 && RideStore.endsFinished(f0), "A long finished journal ends finished");
        setup.rename(ids.get(1), "Campus loop");
        check(RideStore.endsFinished(f1), "Plain renames after STOP still end finished");
        setup.rename(ids.get(2), "Campus, return");
        check(!RideStore.endsFinished(f2), "A quoted rename is replayed instead of trusted");
        String auto = setup.start("UT5", "Parked", WALL + 5 * 86400000L, 0); setup.sample(WALL + 5 * 86400000L, 0, snapshot(0, 100, 20, 1));
        setup.stop(WALL + 5 * 86400000L + 1000, 1000, RideStore.STOP_AUTO_PARKED);
        check(RideStore.endsFinished(new File(dir, auto + ".csv")), "An automatic parked stop ends finished");
        String open = setup.start("UT5", "Still recording", WALL + 6 * 86400000L, 0);
        setup.sample(WALL + 6 * 86400000L, 0, snapshot(0, 100, 20, 1));
        File openFile = new File(dir, open + ".csv");
        check(!RideStore.endsFinished(openFile), "An unfinished journal never ends finished");
        File empty = new File(dir, java.util.UUID.randomUUID() + ".csv"); Files.write(empty.toPath(), new byte[0]);
        check(!RideStore.endsFinished(empty), "An empty journal is replayed");
        File cut = directory(); File partial = new File(cut, ids.get(0) + ".csv"); Files.copy(f0.toPath(), partial.toPath());
        Files.write(partial.toPath(), "1,RENAME,2026".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        check(!RideStore.endsFinished(partial), "A partial last row is replayed");
        long before = replays();
        RideStore deferred = new RideStore(dir, true);
        check(open.equals(deferred.activeId()) && replays() - before == 3, "A deferred start replays only the open, quoted and empty journals and recovers the same ride");
        check(empty.delete(), "Fixture removes the unreadable journal");
        before = replays();
        check(deferred.list().size() == 5 && replays() - before == 3, "The worker's list() reads the deferred journals once");
        before = replays(); deferred.list();
        check(replays() == before, "Then Rides opens from the cache");
        deferred.stop(WALL + 6 * 86400000L + 2000, 2000);
    }
    private static boolean sameTrend(List<RideStore.Point> a, List<RideStore.Point> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i).elapsedMs != b.get(i).elapsedMs || a.get(i).segment != b.get(i).segment || !a.get(i).powerW.equals(b.get(i).powerW)) return false;
        return true;
    }
    private static void near(double expected, Double actual, String message) {
        check(actual != null && Math.abs(expected - actual) <= Math.max(1e-8, Math.abs(expected) * 1e-10),
                message + " (expected " + expected + ", got " + actual + ")");
    }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
}
