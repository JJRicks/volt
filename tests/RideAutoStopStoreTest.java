package com.jjricks.volt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Stop reason in the ride journal and the learned-range view of automatically ended rides. Synthetic data only. */
public final class RideAutoStopStoreTest {
    private static final long NOW = 1800000000000L;
    private static final List<File> directories = new ArrayList<>();
    private static int checks;
    public static void main(String[] args) throws Exception {
        try {
            stopReasonIsBackwardCompatible();
            learnedRangeUsesMotionNotTail();
            speedOnlyTailCannotRepairMissingMovement();
            failedSaveKeepsRecording();
            quietTokenRoundTrips();
            shownEndIsTheLastReading();
            shownEndCountsEveryRecordedReading();
            importedStopParametersAreRangeChecked();
            learnedRangeAcceptsQuietTail();
            System.out.println("RideAutoStopStoreTest: " + checks + " assertions passed");
        } finally { for (File file : directories) remove(file); }
    }

    private static void stopReasonIsBackwardCompatible() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String manual = store.start("A", "Manual", NOW, 1000); store.stop(NOW + 5000, 6000);
        String auto = store.start("A", "Auto", NOW + 10000, 11000); store.stop(NOW + 15000, 16000, RideStore.STOP_AUTO_PARKED);
        String unknown = store.start("A", "Odd", NOW + 20000, 21000); store.stop(NOW + 25000, 26000, "SOMETHING_ELSE");
        String journal = new String(Files.readAllBytes(new File(dir, manual + ".csv").toPath()), StandardCharsets.UTF_8);
        check(journal.trim().endsWith(",,"), "A rider's stop writes the same empty details column as before");
        journal = new String(Files.readAllBytes(new File(dir, auto + ".csv").toPath()), StandardCharsets.UTF_8);
        check(journal.trim().endsWith("," + RideStore.STOP_AUTO_PARKED), "Automatic stop stores its reason in the existing details column");
        check(new String(Files.readAllBytes(new File(dir, unknown + ".csv").toPath()), StandardCharsets.UTF_8).trim().endsWith(",,"), "Unknown reasons are not written");
        RideStore reopened = new RideStore(dir);
        check(reopened.activeId() == null, "Stopped rides are not recovered as active after a restart");
        RideStore.Summary m = reopened.get(manual), a = reopened.get(auto);
        check("".equals(m.stopReason) && !m.automaticStop && !m.active, "Rider stop reads back as the rider's");
        check(RideStore.STOP_AUTO_PARKED.equals(a.stopReason) && a.automaticStop && !a.active, "Automatic stop reads back");
        check(a.toJson().contains("\"stopReason\":\"automatic_parked_disconnect\"") && m.toJson().contains("\"stopReason\":\"rider\""), "Analysis JSON names the stop");
        check(a.elapsedMs == 5000 && a.endWallMs == NOW + 15000, "Stop time is the real stop time, not backdated");
        check(RideStore.inspectJournal(new File(dir, auto + ".csv")).automaticStop, "Backup staging inspection accepts the journal");
        // A journal written by 1.3.1 (STOP with an empty details column) still reads as stopped by the rider.
        File legacy = new File(dir, "00000000-0000-4000-8000-000000000001.csv");
        String header = journalHeader(new File(dir, manual + ".csv"));
        Files.write(legacy.toPath(), (header
                + "1,START,2027-01-15T08:00:00.000Z,1800000000000,1000,,A,,Legacy,,,,,UNCONFIRMED,\n"
                + "1,STOP,2027-01-15T08:00:05.000Z,1800000005000,6000,,,,,,,,,,\n").getBytes(StandardCharsets.UTF_8));
        RideStore.Summary old = new RideStore(dir).get("00000000-0000-4000-8000-000000000001");
        check(old != null && !old.active && "".equals(old.stopReason) && !old.automaticStop, "Legacy journal unchanged in meaning");
        check(new RideStore(dir).get(auto).stopReason.equals(RideStore.STOP_AUTO_PARKED), "Still readable after repeated reopen");
        check(store.activeSummary() == null, "No ride left active");
    }

    /** A realistic automatic end: ride, park, 12 s silent link, gap, 10 s grace, STOP. */
    private static void learnedRangeUsesMotionNotTail() throws Exception {
        Ride normal = ride("normal"), auto = ride("auto"), manualTail = ride("manual_tail"), longTail = ride("long_tail"), midGap = ride("mid_gap");
        LearnedRangeEstimate.Evidence n = normal.evidence(), a = auto.evidence();
        check(n.rejection == null, "Control ride trains: " + n.rejection);
        check(a.rejection == null, "Automatically ended ride still trains on its motion: " + a.rejection);
        check(a.summary.gapCount == 1 && a.summary.elapsedMs > n.summary.elapsedMs + 20000, "The archive keeps the real gap and stop time");
        check(Math.abs(a.summary.distanceKm - n.summary.distanceKm) < 1e-9 && Math.abs(a.summary.batteryDropPercent - n.summary.batteryDropPercent) < 1e-9,
                "Post-ride idle adds no distance or battery use");
        check(manualTail.evidence().rejection != null, "The same tail stopped by hand keeps today's conservative rejection");
        check(longTail.evidence().rejection != null, "A tail far longer than the auto-stop path is not trimmed");
        check(midGap.evidence().rejection != null, "An earlier gap still rejects an automatic ride");
        LearnedRangeEstimate.Result streamed = auto.store.learnedRange("A", NOW + 3_600_000L);
        LearnedRangeEstimate.Result full = LearnedRangeEstimate.calculate("A", Arrays.asList(a), NOW + 3_600_000L);
        check(streamed.rides == 1 && full.rides == 1 && streamed.distanceKm == full.distanceKm && streamed.batteryPoints == full.batteryPoints,
                "Streaming analysis matches full-detail inspection");
    }

    private static void failedSaveKeepsRecording() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("A", "Storage", NOW, 1000);
        File journal = new File(dir, id + ".csv");
        check(journal.delete() && journal.mkdir(), "Synthetic unwritable journal");
        boolean failed = false;
        try { store.stop(NOW + 5000, 6000, RideStore.STOP_AUTO_PARKED); } catch (IOException expected) { failed = true; }
        check(failed && id.equals(store.activeId()), "A failed automatic save leaves the ride recording for Retry save");
        journal.delete();
    }

    private static void speedOnlyTailCannotRepairMissingMovement() throws Exception {
        for (boolean tail : new boolean[]{false, true}) {
            RideStore store = new RideStore(directory());
            String id = store.start("A", "Partial telemetry", NOW, 0);
            RideStore.Summary completePrefix = null;
            for (int sec = 0; sec <= (tail ? 130 : 120); sec++) {
                long mono = sec * 1000L, batteryUpdated = sec > 120 ? 120000L : mono;
                Map<String, RideStore.Sample> readings = values(sec < 100 ? 24 : 0,
                        80 - 8 * Math.min(sec / 100.0, 1), 100 + 24 * Math.min(sec, 100) / 3600.0, batteryUpdated);
                if (sec >= 30 && sec <= 39) readings.remove("speed_kmh");
                else readings.put("speed_kmh", new RideStore.Sample(sec < 100 ? 24 : 0, mono));
                readings.put("charging_state", new RideStore.Sample(0, mono));
                store.sample(NOW + mono, mono, readings);
                if (sec == (tail ? 123 : 120)) completePrefix = store.activeSummary();
            }
            if (tail) {
                store.markGap(NOW + 131000, 131000, "Bluetooth disconnected");
                store.heartbeat(NOW + 140000, 140000);
            }
            store.stop(NOW + (tail ? 140000 : 120000), tail ? 140000 : 120000, tail ? RideStore.STOP_AUTO_PARKED : "");
            RideStore.Summary summary = store.get(id);
            check(completePrefix.speedCoverage < .95, "Fixture has less than 95% movement coverage");
            if (tail) check(summary.speedCoverageMs / (double) completePrefix.elapsedMs > .95,
                    "Whole-journal numerator would incorrectly admit this fixture");
            LearnedRangeEstimate.Evidence evidence = LearnedRangeEstimate.inspect(summary, store.fullTrend(id));
            check("Movement coverage below 95%".equals(evidence.rejection), "Partial telemetry remains rejected, tail=" + tail);
            check(store.learnedRange("A", NOW + 3600000).rides == 0, "Streaming reader also rejects the same partial ride");
        }
    }

    private static final class Ride {
        final RideStore store; final String id;
        Ride(RideStore store, String id) { this.store = store; this.id = id; }
        LearnedRangeEstimate.Evidence evidence() throws Exception { return LearnedRangeEstimate.inspect(store.get(id), store.fullTrend(id)); }
    }
    private static Ride ride(String mode) throws Exception {
        RideStore store = new RideStore(directory());
        int seconds = 600; long start = NOW, mono = 0;
        String id = store.start("A", "Synthetic", start, 0);
        for (int sec = 0; sec <= seconds; sec++) {
            mono = sec * 1000L;
            boolean parked = sec > seconds - 30;
            double speed = parked ? 0 : 24, soc = 80 - 8 * Math.min(1, sec / (double) (seconds - 30)), odo = 100 + 4 * Math.min(1, sec / (double) (seconds - 30));
            if (mode.equals("mid_gap") && sec == 300) store.markGap(start + mono, mono, "Synthetic gap");
            store.sample(start + mono, mono, values(speed, soc, odo, mono));
        }
        if (!mode.equals("normal")) {
            long last = mono;
            // Silent link: the tick still commits frames, but every reading is older than 3 s by now.
            for (int i = 1; i <= 12; i++) { mono = last + i * 1000L; store.sample(start + mono, mono, values(0, 72, 104, last)); }
            store.markGap(start + mono + 1000, mono + 1000, "Bluetooth disconnected");
            int tail = mode.equals("long_tail") ? 120 : mode.equals("quiet_too_long") ? 330
                    : mode.startsWith("quiet") || mode.equals("manual_quiet_tail") || mode.equals("parked_quiet_tail") ? 290 : 10;
            for (int i = 1; i <= tail; i++) store.heartbeat(start + mono + 1000 + i * 1000L, mono + 1000 + i * 1000L);
            mono += 1000 + tail * 1000L;
        }
        String reason = mode.equals("manual_tail") || mode.equals("manual_quiet_tail") || mode.equals("normal") ? ""
                : mode.startsWith("quiet") ? RideStore.quietStopReason(8, false) : RideStore.STOP_AUTO_PARKED;
        store.stop(start + mono, mono, reason);
        return new Ride(store, id);
    }
    private static Map<String, RideStore.Sample> values(double speed, double soc, double odo, long updated) {
        Map<String, RideStore.Sample> values = new LinkedHashMap<>();
        values.put("battery_percent", new RideStore.Sample(soc, updated));
        values.put("odometer_km", new RideStore.Sample(odo, updated));
        values.put("speed_kmh", new RideStore.Sample(speed, updated));
        values.put("temperature_c", new RideStore.Sample(20, updated));
        values.put("charging_state", new RideStore.Sample(0, updated));
        return values;
    }
    private static String journalHeader(File journal) throws IOException {
        String text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
        return text.substring(0, text.indexOf('\n') + 1);
    }
    private static File directory() throws IOException { File dir = Files.createTempDirectory("volt-autostop-").toFile(); directories.add(dir); return dir; }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }

    private static String tail(File journal) throws IOException {
        String[] lines = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8).trim().split("\n");
        return lines[lines.length - 1];
    }

    /** The went-quiet STOP token, its parameters, and how odd values degrade. */
    private static void quietTokenRoundTrips() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String quiet = store.start("A", "Quiet", NOW, 1000);
        store.sample(NOW + 1000, 2000, values(5, 80, 100, 2000));
        store.markGap(NOW + 14000, 15000, "Bluetooth disconnected");
        store.heartbeat(NOW + 30000, 31000);
        store.stop(NOW + 62000, 63000, RideStore.quietStopReason(8, false));
        check(tail(new File(dir, quiet + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=2000;code=8"), "The journal stores the token, the last reading and the code");
        String off = store.start("A", "Bluetooth off", NOW + 100000, 101000);
        store.sample(NOW + 101000, 102000, values(20, 80, 100, 102000));
        store.stop(NOW + 401000, 402000, RideStore.quietStopReason(-1, true));
        check(tail(new File(dir, off + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=102000;code=-1;phone_bt=off"), "Phone Bluetooth off is recorded");
        String bare = store.start("A", "Bare", NOW + 500000, 501000);
        store.stop(NOW + 560000, 561000, RideStore.STOP_AUTO_QUIET);
        check(tail(new File(dir, bare + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=-1;code=-1"), "No reading and no code are written as -1");
        String odd = store.start("A", "Odd", NOW + 600000, 601000);
        store.stop(NOW + 660000, 661000, RideStore.STOP_AUTO_QUIET + ";code=abc;phone_bt=maybe;last=5;" + new String(new char[200]).replace('\0', 'x'));
        check(tail(new File(dir, odd + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=-1;code=-1"), "Malformed or unknown parameters are dropped; the caller cannot set the last reading");
        String huge = store.start("A", "Huge", NOW + 700000, 701000);
        store.stop(NOW + 760000, 761000, RideStore.STOP_AUTO_QUIET + ";code=99999999999");
        check(tail(new File(dir, huge + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=-1;code=-1"), "An out-of-range code is unknown");
        String lookalike = store.start("A", "Lookalike", NOW + 800000, 801000);
        store.stop(NOW + 860000, 861000, RideStore.STOP_AUTO_QUIET + "_LATER");
        check(tail(new File(dir, lookalike + ".csv")).endsWith(",,"), "A different token that only shares the prefix is not written");

        RideStore reopened = new RideStore(dir);
        RideStore.Summary q = reopened.get(quiet), b = reopened.get(off), n = reopened.get(bare), l = reopened.get(lookalike);
        check(RideStore.STOP_AUTO_QUIET.equals(q.stopReason) && q.automaticStop && !q.active, "Quiet stop reads back as the bare token");
        check(q.stopLastReadingMonotonicMs == 2000 && q.stopDisconnectCode == 8 && !q.stopPhoneBluetoothOff, "Its parameters read back");
        check(b.stopPhoneBluetoothOff && b.stopDisconnectCode == -1 && b.automaticStop, "Phone Bluetooth off reads back");
        check(n.automaticStop && n.stopLastReadingMonotonicMs == -1 && n.stopDisconnectCode == -1, "Bare token reads back as an automatic stop");
        check(!l.automaticStop && "".equals(l.stopReason), "The lookalike was stored as a rider's stop");
        check(q.toJson().contains("\"stopReason\":\"automatic_quiet_disconnect\""), "Analysis JSON names the quiet stop");
        check(q.toJson().contains("\"shownEndWallMs\":" + (NOW + 1000) + ",\"shownElapsedMs\":1000"), "Analysis JSON carries the shown end");
        check(q.elapsedMs == 62000 && q.endWallMs == NOW + 62000, "The raw stop time stays the real stop time");

        // Journals written by hand (a future or damaged build): every odd value degrades to a plain automatic stop.
        String header = journalHeader(new File(dir, quiet + ".csv"));
        String[] details = {"AUTO_QUIET_DISCONNECT;last=;code=", "AUTO_QUIET_DISCONNECT;last=x;code=1.5;phone_bt=OFF",
                "AUTO_QUIET_DISCONNECT;;=;last", "AUTO_QUIET_DISCONNECT;code=" + new String(new char[120]).replace('\0', '9'),
                "AUTO_QUIET_DISCONNECT;future=1"};
        for (int i = 0; i < details.length; i++) {
            String id = "00000000-0000-4000-8000-00000000010" + i;
            Files.write(new File(dir, id + ".csv").toPath(), (header
                    + "1,START,2027-01-15T08:00:00.000Z,1800000000000,1000,,A,,Hand,,,,,UNCONFIRMED,\n"
                    + "1,STOP,2027-01-15T08:00:05.000Z,1800000005000,6000,,,,,,,,,," + details[i] + "\n").getBytes(StandardCharsets.UTF_8));
            RideStore.Summary h = new RideStore(dir).get(id);
            check(h != null && h.automaticStop && RideStore.STOP_AUTO_QUIET.equals(h.stopReason) && h.stopLastReadingMonotonicMs == -1
                    && h.stopDisconnectCode == -1 && !h.stopPhoneBluetoothOff, "Odd details degrade to a plain automatic stop: " + i);
        }
        // Backup and .voltrides staging validate and inspect each journal the same way.
        for (String id : new String[]{quiet, off, bare}) {
            File journal = new File(dir, id + ".csv");
            RideArchive.validateText(journal, true, false);
            check(RideStore.inspectJournal(journal).automaticStop, "Staging accepts the quiet journal");
        }
    }

    /** Rider-facing end: the last real reading for an automatic stop, the raw end otherwise. */
    private static void shownEndIsTheLastReading() throws Exception {
        for (String reason : new String[]{RideStore.quietStopReason(8, false), RideStore.STOP_AUTO_PARKED, ""}) {
            File dir = directory(); RideStore store = new RideStore(dir);
            String id = store.start("A", "Shown end", NOW, 0);
            for (int sec = 1; sec <= 30; sec++) store.sample(NOW + sec * 1000L, sec * 1000L, values(sec < 25 ? 5 : 0, 80, 100, sec * 1000L));
            // Silent link: the tick still commits frames, but every reading is older than 3 s by then.
            for (int sec = 31; sec <= 42; sec++) store.sample(NOW + sec * 1000L, sec * 1000L, values(0, 80, 100, 30000));
            store.markGap(NOW + 43000, 43000, "Bluetooth disconnected");
            for (int sec = 44; sec <= 90; sec++) store.heartbeat(NOW + sec * 1000L, sec * 1000L);
            store.stop(NOW + 90000, 90000, reason);
            RideStore.Summary live = store.get(id), s = new RideStore(dir).get(id);
            for (RideStore.Summary r : new RideStore.Summary[]{live, s}) {
                check(r.endWallMs == NOW + 90000 && r.elapsedMs == 90000 && r.gapCount == 1, "Raw end and gap count unchanged, reason=" + reason);
                if (reason.isEmpty()) check(r.shownEndWallMs == r.endWallMs && r.shownElapsedMs == r.elapsedMs && r.shownGapCount == r.gapCount,
                        "A rider's stop shows its real end");
                else check(r.shownEndWallMs == NOW + 33000 && r.shownElapsedMs == 33000 && r.shownGapCount == 0,
                        "An automatic stop shows its last real reading (last fresh value + 3 s) and drops the trailing gap, reason=" + reason);
            }
        }
        RideStore store = new RideStore(directory());
        String id = store.start("A", "No readings", NOW, 0);
        store.markGap(NOW + 1000, 1000, "Bluetooth disconnected");
        store.stop(NOW + 61000, 61000, RideStore.quietStopReason(-1, false));
        RideStore.Summary none = store.get(id);
        check(none.shownEndWallMs == none.endWallMs && none.shownElapsedMs == none.elapsedMs && none.shownGapCount == none.gapCount,
                "A ride with no reading keeps its raw end");
        store = new RideStore(directory()); store.start("A", "Active", NOW, 0);
        store.sample(NOW + 1000, 1000, values(5, 80, 100, 1000)); store.heartbeat(NOW + 9000, 9000);
        RideStore.Summary active = store.activeSummary();
        check(active.stopReason == null && active.shownElapsedMs == active.elapsedMs, "A recording ride shows its live end");
    }

    /** Any fresh recorded scooter value moves the shown end, not only the energy and range inputs. */
    private static void shownEndCountsEveryRecordedReading() throws Exception {
        for (String only : new String[]{"temperature_c", "charging_state"}) {
            File dir = directory(); RideStore store = new RideStore(dir);
            String id = store.start("A", "Partial", NOW, 0);
            Map<String, RideStore.Sample> first = new LinkedHashMap<>();
            first.put("battery_percent", new RideStore.Sample(80, 1000));
            store.sample(NOW + 1000, 1000, first);
            for (int sec = 2; sec <= 30; sec++) {
                Map<String, RideStore.Sample> m = new LinkedHashMap<>();
                m.put("battery_percent", new RideStore.Sample(80, 1000));
                m.put(only, new RideStore.Sample(only.equals("temperature_c") ? 21 : 0, sec * 1000L));
                store.sample(NOW + sec * 1000L, sec * 1000L, m);
            }
            store.markGap(NOW + 43000, 43000, "Bluetooth disconnected");
            for (int sec = 44; sec <= 90; sec++) store.heartbeat(NOW + sec * 1000L, sec * 1000L);
            store.stop(NOW + 90000, 90000, RideStore.quietStopReason(8, false));
            check(tail(new File(dir, id + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=30000;code=8"), "STOP names the last fresh reading of any field: " + only);
            for (RideStore.Summary r : new RideStore.Summary[]{store.get(id), new RideStore(dir).get(id)}) {
                check(r.shownElapsedMs == 30000 && r.shownEndWallMs == NOW + 30000 && r.shownGapCount == 0,
                        "A partial-telemetry ride ends at its last fresh reading: " + only + " shown=" + r.shownElapsedMs);
                check(r.stopLastReadingMonotonicMs == 30000 && r.elapsedMs == 90000, "Parameters and raw end read back: " + only);
            }
        }
        // Temperature only, then fresh-looking rows with only stale or invalid values.
        File dir = directory(); RideStore store = new RideStore(dir);
        String id = store.start("A", "Temperature only", NOW, 0);
        for (int sec = 1; sec <= 20; sec++) {
            Map<String, RideStore.Sample> m = new LinkedHashMap<>();
            m.put("temperature_c", new RideStore.Sample(sec <= 10 ? 22 : 500, sec * 1000L));
            if (sec > 10) m.put("voltage_v", new RideStore.Sample(48, 1000));
            store.sample(NOW + sec * 1000L, sec * 1000L, m);
        }
        store.markGap(NOW + 25000, 25000, "Bluetooth disconnected");
        store.stop(NOW + 80000, 80000, RideStore.quietStopReason(-1, false));
        check(tail(new File(dir, id + ".csv")).endsWith(",AUTO_QUIET_DISCONNECT;last=10000;code=-1"), "A temperature-only ride names its last valid reading");
        for (RideStore.Summary r : new RideStore.Summary[]{store.get(id), new RideStore(dir).get(id)})
            check(r.shownElapsedMs == 10000 && r.shownGapCount == 0 && r.stopLastReadingMonotonicMs == 10000,
                    "Out-of-range and stale values never move the shown end, shown=" + r.shownElapsedMs);
    }

    /** Hand-written STOP parameters outside the writer's range read as unknown, never clamped. */
    private static void importedStopParametersAreRangeChecked() throws Exception {
        File dir = directory(); RideStore store = new RideStore(dir);
        String seed = store.start("A", "Seed", NOW, 1000); store.stop(NOW + 1000, 2000);
        String header = journalHeader(new File(dir, seed + ".csv"));
        String[] details = {"AUTO_QUIET_DISCONNECT;last=5;code=2147483648", "AUTO_QUIET_DISCONNECT;last=5;code=-2147483649",
                "AUTO_QUIET_DISCONNECT;last=-2;code=8", "AUTO_QUIET_DISCONNECT;last=-9223372036854775808;code=8"};
        long[] last = {5, 5, -1, -1}; int[] code = {-1, -1, 8, 8};
        for (int i = 0; i < details.length; i++) {
            String id = "00000000-0000-4000-8000-00000000020" + i;
            File journal = new File(dir, id + ".csv");
            Files.write(journal.toPath(), (header
                    + "1,START,2027-01-15T08:00:00.000Z,1800000000000,1000,,A,,Hand,,,,,UNCONFIRMED,\n"
                    + "1,STOP,2027-01-15T08:00:05.000Z,1800000005000,6000,,,,,,,,,," + details[i] + "\n").getBytes(StandardCharsets.UTF_8));
            RideArchive.validateText(journal, true, false);
            for (RideStore.Summary h : new RideStore.Summary[]{new RideStore(dir).get(id), RideStore.inspectJournal(journal)})
                check(h.automaticStop && h.stopLastReadingMonotonicMs == last[i] && h.stopDisconnectCode == code[i] && !h.stopPhoneBluetoothOff,
                        "Out-of-range parameters read as unknown: " + details[i] + " -> last=" + h.stopLastReadingMonotonicMs + " code=" + h.stopDisconnectCode);
        }
        // The boundaries themselves still round-trip.
        String id = "00000000-0000-4000-8000-000000000210";
        Files.write(new File(dir, id + ".csv").toPath(), (header
                + "1,START,2027-01-15T08:00:00.000Z,1800000000000,1000,,A,,Hand,,,,,UNCONFIRMED,\n"
                + "1,STOP,2027-01-15T08:00:05.000Z,1800000005000,6000,,,,,,,,,,AUTO_QUIET_DISCONNECT;last=-1;code=-2147483648\n").getBytes(StandardCharsets.UTF_8));
        RideStore.Summary b = new RideStore(dir).get(id);
        check(b.stopLastReadingMonotonicMs == -1 && b.stopDisconnectCode == Integer.MIN_VALUE, "In-range extremes still read back");
        id = "00000000-0000-4000-8000-000000000211";
        Files.write(new File(dir, id + ".csv").toPath(), (header
                + "1,START,2027-01-15T08:00:00.000Z,1800000000000,1000,,A,,Hand,,,,,UNCONFIRMED,\n"
                + "1,STOP,2027-01-15T08:00:05.000Z,1800000005000,6000,,,,,,,,,,AUTO_QUIET_DISCONNECT;last=0;code=2147483647\n").getBytes(StandardCharsets.UTF_8));
        b = new RideStore(dir).get(id);
        check(b.stopLastReadingMonotonicMs == 0 && b.stopDisconnectCode == Integer.MAX_VALUE, "In-range maximum code still reads back");
    }

    /** Quiet-stopped rides train at their last complete reading; the same tail by hand does not. */
    private static void learnedRangeAcceptsQuietTail() throws Exception {
        LearnedRangeEstimate.Evidence q = ride("quiet").evidence(), n = ride("normal").evidence();
        check(q.rejection == null, "A five-minute quiet tail still trains: " + q.rejection);
        check(Math.abs(q.summary.distanceKm - n.summary.distanceKm) < 1e-9, "The tail adds no distance");
        check(ride("manual_quiet_tail").evidence().rejection != null, "The same tail stopped by hand is still rejected");
        check(ride("parked_quiet_tail").evidence().rejection != null, "A five-minute tail under the parked token is still rejected");
        check(ride("quiet_too_long").evidence().rejection != null, "A tail longer than the quiet wait is not trimmed");
    }
}
