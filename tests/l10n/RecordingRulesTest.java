package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The owner's recording rules (Nov 24 2026), on synthesized timelines modelled on his report: a ride recording, then the
 * scooter plugged into a charger (charging 1, current negative), then the charger unplugged and plugged back in (1, 0, 1 within seconds).
 * 1. A charge ends the ride at once, saved with its own end; no ride starts while charging; no loop with the reverse rule.
 * 2. A ride under 0.01 mi or 20 s is not kept: no journal, no summary row, nothing that reads it as saved.
 * Synthetic data only.
 */
public final class RecordingRulesTest {
    private static final String PROFILE = "profile-1";
    private static final long WALL0 = 1800000000000L;
    private static int checks;

    public static void main(String[] args) throws Exception {
        chargeEndsTheRide();
        readingBeforeTheStart();
        replugNeverRestartsARide();
        waitIsPerScooter();
        riderStartWhileCharging();
        noLoopWithTheReverseRule();
        chargingEndCode();
        keepRule();
        shortRidesAreNotKept();
        keptRidesStay();
        discardLeavesNoSummary();
        unmeasuredDistance();
        phoneTrackDistance();
        exemptionInTheJournal();
        serviceWiring();
        System.out.println("RecordingRulesTest: " + checks + " checks passed");
    }

    private static final String A = "scooter-a", B = "scooter-b";

    /** An automatic ride started at startMs (no exemption). */
    private static RideChargeRule auto(String ride, long startMs) { RideChargeRule r = new RideChargeRule(); r.rideStarted(ride, startMs, false, null); return r; }

    /** Hand stop at 16:10, a stray automatic ride from 16:11:30, plugged in at 16:11:54: the ride ends on that reading. */
    private static void chargeEndsTheRide() {
        long t = 1_000_000;
        RideChargeRule r = auto("ride", t);
        r.observe(A, Boolean.FALSE, t + 1000);
        check(!r.endsRide("ride", Boolean.FALSE, t + 1000, false), "not charging: the ride runs on");
        check(!r.endsRide("ride", null, -1, false), "a stale or missing charging reading never ends a ride");
        check(!r.endsRide(null, Boolean.TRUE, t + 2000, false), "nothing recording: nothing to end");
        check(!r.endsRide("other", Boolean.TRUE, t + 2000, false), "a ride the rule hasn't seen start isn't ended by it");
        r.observe(A, Boolean.TRUE, t + 24_000);
        check(!r.endsRide("ride", Boolean.TRUE, t + 24_000, true), "never while ridden faster than 3 mph: a charging flag mid-ride is not a charger");
        check(r.endsRide("ride", Boolean.TRUE, t + 24_000, false), "the first fresh charging reading ends the ride at once");
        check(r.blocksAutoRecord(A, t + 24_000, false), "while charging, Record rides automatically starts nothing");
    }

    /** #643 F06: a charging reading from before the ride's start (still fresh) never ends it. */
    private static void readingBeforeTheStart() {
        long t = 1_500_000;
        RideChargeRule r = auto("fast", t);
        check(!r.endsRide("fast", Boolean.TRUE, t - 2000, false), "unplugged and ridden off fast: the reading from before the start doesn't end it when slowing");
        check(!r.endsRide("fast", Boolean.TRUE, t, false), "nor one taken at the start itself");
        check(r.endsRide("fast", Boolean.TRUE, t + 1, false), "a reading taken after the start does");
    }

    /** Unplugged and plugged back in (his log: 1, 0, 1 within 5 to 8 s, as he replugged): no ride starts on the 0, even with the scooter jostled. */
    private static void replugNeverRestartsARide() {
        RideChargeRule r = new RideChargeRule();
        long t = 2_000_000;
        r.observe(A, Boolean.TRUE, t);
        r.observe(A, Boolean.FALSE, t + 5000);
        check(r.blocksAutoRecord(A, t + 5000, false), "a 0 five seconds after charging: still no automatic ride");
        r.observe(A, Boolean.TRUE, t + 8000);
        r.observe(A, null, t + 20_000);
        check(r.blocksAutoRecord(A, t + 20_000, false), "a stale reading neither starts nor clears anything");
        check(r.blocksAutoRecord(A, t + 8000 + RideChargeRule.SETTLE_MS - 1, false), "within SETTLE_MS of the last charging reading: still waits");
        check(!r.blocksAutoRecord(A, t + 8000 + RideChargeRule.SETTLE_MS, false), "SETTLE_MS after it: auto-record may start again");
        check(!r.blocksAutoRecord(A, t + 9000, true), "unplugged and ridden off faster than 3 mph: starts at once");
        RideChargeRule never = new RideChargeRule();
        check(!never.blocksAutoRecord(A, 5, false), "never charging: no wait");
    }

    /** #643 F04: the wait belongs to the scooter that charged. */
    private static void waitIsPerScooter() {
        RideChargeRule r = new RideChargeRule();
        long t = 2_500_000;
        r.observe(A, Boolean.TRUE, t);
        check(!r.blocksAutoRecord(B, t + 5000, false), "scooter B selected 5 s after A charged: B doesn't wait");
        r.observe(B, Boolean.FALSE, t + 5000);
        check(!r.blocksAutoRecord(B, t + 6000, false) && !r.blocksAutoRecord(A, t + 6000, false), "the switch ends A's wait too");
    }

    /** A ride the rider starts by hand while charging is the rider's choice, while that charge lasts (#643 finding 2, F05). */
    private static void riderStartWhileCharging() {
        long t = 3_000_000;
        RideChargeRule r = new RideChargeRule();
        check(r.rideStarted("hand", t, true, Boolean.TRUE) == RideChargeRule.Exempt.YES, "started by hand while charging: exempt");
        check(!r.endsRide("hand", Boolean.TRUE, t + 1000, false), "the charge it started in doesn't end it");
        // Tap Record, unplug, ride, plug in: the plug-in at the end ends it.
        check(!r.endsRide("hand", Boolean.FALSE, t + 10_000, false) && r.exempt("hand") == RideChargeRule.Exempt.NO, "unplugged: the exemption ends");
        check(r.endsRide("hand", Boolean.TRUE, t + 20 * 60_000, false), "plugged in at the end of the ride: it ends");
        // Ridden off without a not-charging reading in between: the speed ends the exemption.
        check(r.rideStarted("off", t, true, Boolean.TRUE) == RideChargeRule.Exempt.YES, "exempt again");
        check(!r.endsRide("off", Boolean.TRUE, t + 2000, true) && r.exempt("off") == RideChargeRule.Exempt.NO, "ridden off above 3 mph: the exemption ends");
        check(r.endsRide("off", Boolean.TRUE, t + 30 * 60_000, false), "the next plug-in ends it");
        // F05: no fresh reading at the start: the first fresh one decides.
        check(r.rideStarted("blind", t, true, null) == RideChargeRule.Exempt.UNDECIDED, "no reading at the start: undecided");
        check(!r.endsRide("blind", null, -1, false), "still no reading: nothing ends");
        check(!r.endsRide("blind", Boolean.TRUE, t + 3000, false) && r.exempt("blind") == RideChargeRule.Exempt.YES,
                "the first fresh reading says charging: exempt, not ended");
        check(r.rideStarted("blind2", t, true, null) == RideChargeRule.Exempt.UNDECIDED
                && !r.endsRide("blind2", Boolean.FALSE, t + 3000, false) && r.exempt("blind2") == RideChargeRule.Exempt.NO,
                "the first fresh reading says not charging: an ordinary ride");
        check(r.endsRide("blind2", Boolean.TRUE, t + 60_000, false), "which a later plug-in ends");
        check(r.rideStarted("later", t, true, Boolean.FALSE) == RideChargeRule.Exempt.NO && r.endsRide("later", Boolean.TRUE, t + 1000, false),
                "started by hand before charging: the charge ends it");
        // F09: a recovered ride takes its journal's standing.
        RideChargeRule fresh = new RideChargeRule();
        fresh.rideRecovered("hand2", -1, RideChargeRule.Exempt.of("YES"));
        check(fresh.tracks("hand2") && !fresh.endsRide("hand2", Boolean.TRUE, 5, false), "recovered while still charging: still exempt");
        check(RideChargeRule.Exempt.of(null) == RideChargeRule.Exempt.NO && RideChargeRule.Exempt.of("junk") == RideChargeRule.Exempt.NO, "no row, or junk: an ordinary ride");
    }

    /**
     * The reverse rule stays: a ride started by hand ends an AUTOMATIC charge recording. The two can't loop: that ride is
     * exempt from the charge rule while the charge lasts, auto-record never starts while charging (so it never ends a charge
     * recording), and a charge ending a ride sets no hold, so the charge recording starts on its normal path.
     */
    private static void noLoopWithTheReverseRule() {
        check(ChargeAutoRecordPolicy.rideEndsRecording(true) && !ChargeAutoRecordPolicy.rideEndsRecording(false), "reverse rule unchanged");
        long t = 3_000_000;
        RideChargeRule r = auto("ride", t - 60_000);
        r.observe(A, Boolean.TRUE, t);
        boolean ended = r.endsRide("ride", Boolean.TRUE, t, false);
        boolean chargeStarts = ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, false, false, false);
        check(ended && chargeStarts, "the ride ends, and Record charges automatically starts on its own path");
        int rideStarts = 0;
        for (long now = t; now < t + 10 * 60_000; now += 1000) {
            Boolean live = (now / 1000) % 97 == 0 ? Boolean.FALSE : Boolean.TRUE; // a replug now and then
            r.observe(A, live, now);
            if (!r.blocksAutoRecord(A, now, false)) rideStarts++;
        }
        check(rideStarts == 0, "ten minutes on the charger with replugs: no automatic ride ever starts, so nothing ends the charge recording");
        r.rideStarted("hand", t + 11 * 60_000, true, Boolean.TRUE);
        check(!r.endsRide("hand", Boolean.TRUE, t + 11 * 60_000 + 1000, false), "a ride by hand ends the charge recording, and the charge never ends that ride back");
    }

    private static void chargingEndCode() throws IOException {
        File dir = Files.createTempDirectory("volt-charge-end").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            String id = rides.start(PROFILE, "Home", WALL0 + now, now);
            for (; now < 70_000; now += 1000) sample(rides, now, 15.0, 100.0 + (now - 10_000) / 1_000_000.0 * 15);
            RideStore.Summary s = rides.stop(WALL0 + now, now, RideStore.STOP_CHARGING, true);
            check(!rides.lastStopDiscarded() && s.chargingStop && !s.automaticStop && !s.noMotionStop && s.endedByItself(), "saved, ended by charging, by itself");
            check(rides.lastStopAutomatic(), "riding mode reads it as an end by itself");
            check("automatic_charging".equals(s.fields().get("stopReason")), "the analysis JSON names it: " + s.fields().get("stopReason"));
            check("automatic_charging".equals(DiagnosticsBundle.rideEnd(s.stopReason)), "diagnostics name it");
            RideStore.Summary again = rides.get(id);
            check(again.chargingStop && again.shownEndWallMs == s.shownEndWallMs, "read back the same");
            RideStore reloaded = new RideStore(dir);
            boolean counted = false;
            for (String r : reloaded.automaticStopsAtLoad()) counted |= r.equals(id);
            check(counted && reloaded.get(id).chargingStop, "a reload counts it among the automatic ends (RideAutoEnds)");
            check(DiagnosticsBundle.rideEnd("").equals("rider") && DiagnosticsBundle.rideEnd(RideStore.STOP_AUTO_NO_MOTION).equals("automatic_no_motion"), "existing ends unchanged");
        } finally { remove(dir); }
    }

    /** Records a ride with samples each second and stops it under the rule; whether it was kept. */
    private static boolean keeps(RideStore rides, long seconds, Double startOdo, Double endOdo, Double kmh) throws IOException {
        long now = 10_000;
        rides.start(PROFILE, "", WALL0 + now, now);
        long end = now + seconds * 1000;
        for (; now <= end; now += 1000) {
            Double odo = startOdo == null ? null : startOdo + (endOdo - startOdo) * (now - 10_000) / Math.max(1, end - 10_000);
            sample(rides, now, kmh, odo);
        }
        rides.stop(WALL0 + end, end, "", true);
        return !rides.lastStopDiscarded();
    }

    private static void keepRule() throws IOException {
        File dir = Files.createTempDirectory("volt-keep-rule").toFile();
        try {
            RideStore rides = new RideStore(dir);
            check(Math.abs(RideStore.MIN_KEEP_KM - 0.01609344) < 1e-12 && RideStore.MIN_KEEP_MS == 20_000, "0.01 mi and 20 s");
            check(!keeps(rides, 19, 100.0, 101.0, 20.0), "19 s, a long way: not kept (duration)");
            check(keeps(rides, 20, 100.0, 100.1, 20.0), "20 s and 0.1 km: kept");
            // Rolled indoors: 24 s at walking pace on the odometer's tenths, the odometer never ticks.
            check(!keeps(rides, 24, 100.0, 100.0, 0.0), "24 s, nothing on the odometer or the speed: not kept (distance)");
            check(!keeps(rides, 60, 100.0, 100.0, 0.9), "a minute shuffled at 0.9 km/h is 15 m: not kept");
            check(keeps(rides, 60, 100.0, 100.0, 6.0), "the odometer's tenths say 0 but 60 s at 6 km/h is 100 m: kept");
            check(keeps(rides, 60, null, null, null), "no distance known: the duration decides alone");
            check(!keeps(rides, 10, null, null, null), "no distance known and 10 s: not kept");
            check(keeps(rides, 3600, 100.0, 120.0, 20.0), "an ordinary ride: kept");
        } finally { remove(dir); }
    }

    /**
     * #643 round 1, finding 3: a link lost right after the start leaves one odometer reading and no speed interval. That
     * distance wasn't measured, so the duration decides, and the phone's track counts when it has one.
     */
    private static void unmeasuredDistance() throws IOException {
        File dir = Files.createTempDirectory("volt-unmeasured").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            rides.start(PROFILE, "", WALL0 + now, now);
            sample(rides, now, 0.0, 100.0);          // the one reading before the link went
            long end = now + 40_000;                  // 40 s more, disconnected: no samples
            RideStore.Summary s = rides.stop(WALL0 + end, end, "", true, 0.3);
            check(!rides.lastStopDiscarded() && rides.get(s.id) != null, "link lost at the start, a 300 m phone track: kept");
            rides.start(PROFILE, "", WALL0 + end, end);
            sample(rides, end, 0.0, 100.0);
            rides.stop(WALL0 + end + 40_000, end + 40_000, "", true, null);
            check(!rides.lastStopDiscarded(), "link lost at the start, no phone track, over 20 s: kept (distance unknown)");
            long t = end + 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            sample(rides, t, 0.0, 100.0);
            rides.stop(WALL0 + t + 12_000, t + 12_000, "", true, 0.3);
            check(rides.lastStopDiscarded(), "12 s: not kept, whatever the phone says");
            // Measured over most of the ride and short; the phone's track decides between kept and not.
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, 0.0, 100.0);
            rides.stop(WALL0 + t + 30_000, t + 30_000, "", true, 0.030);
            check(!rides.lastStopDiscarded(), "the scooter measured 0 m but the phone's track has 30 m: kept");
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, 0.0, 100.0);
            rides.stop(WALL0 + t + 30_000, t + 30_000, "", true, 0.010);
            check(rides.lastStopDiscarded(), "the scooter measured 0 m and the phone 10 m: not kept");
            // Half the ride measured: the odometer's two readings span exactly half.
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            sample(rides, t, null, 100.0); sample(rides, t + 20_000, null, 100.0);
            rides.stop(WALL0 + t + 40_000, t + 40_000, "", true, null);
            check(rides.lastStopDiscarded(), "two odometer readings over half the ride, 0 m: measured, not kept");
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            sample(rides, t, null, 100.0); sample(rides, t + 15_000, null, 100.0);
            rides.stop(WALL0 + t + 40_000, t + 40_000, "", true, null);
            check(!rides.lastStopDiscarded(), "two odometer readings over under half of it: not measured, kept");
        } finally { remove(dir); }
    }

    /** The recorder's running GPS distance counts as the saved track's: Track.distanceMeters, by the same steps. */
    private static void phoneTrackDistance() throws IOException {
        File dir = Files.createTempDirectory("volt-phone-distance").toFile();
        try {
            PhoneRideStore store = new PhoneRideStore(dir);
            PhoneRideStore.MovingDistance live = new PhoneRideStore.MovingDistance();
            check(live.meters() == null, "no fixes: no distance");
            store.start("p1", WALL0, 1000);
            StringBuilder rows = new StringBuilder();
            double lat = 40.0, lon = -105.0;
            // 40 s riding north at 5 m/s, a stationary minute of jitter (speed 0), a jump the rule rejects, a 12 s gap.
            long mono = 1000;
            Object[][] fixes = new Object[150][];
            int n = 0;
            for (int i = 0; i < 40; i++) { mono += 1000; lat += 5 / 111_320.0; fixes[n++] = new Object[]{mono, lat, lon, 5.0, 5.0}; }
            for (int i = 0; i < 60; i++) { mono += 1000; fixes[n++] = new Object[]{mono, lat + (i % 2) * 3 / 111_320.0, lon, 8.0, 0.0}; }
            mono += 1000; fixes[n++] = new Object[]{mono, lat + 2000 / 111_320.0, lon, 5.0, 5.0};
            for (int i = 0; i < 10; i++) { mono += 1000; lat += 5 / 111_320.0; fixes[n++] = new Object[]{mono, lat, lon, 5.0, 5.0}; }
            mono += 12_000; lat += 60 / 111_320.0; fixes[n++] = new Object[]{mono, lat, lon, 5.0, 5.0};
            for (int i = 0; i < 5; i++) { mono += 1000; lat += 5 / 111_320.0; fixes[n++] = new Object[]{mono, lat, lon, 5.0, 5.0}; }
            for (int i = 0; i < n; i++) {
                Object[] f = fixes[i];
                long m = (Long) f[0];
                live.fix(m, (Double) f[1], (Double) f[2], (Double) f[3], (Double) f[4]);
                rows.append(PhoneRideStore.row("GPS", WALL0 + m, m, f[1], f[2], f[3], f[4], null, null, null));
            }
            store.appendBatch("p1", rows.toString(), "");
            store.stop("p1", WALL0 + mono, mono);
            Double saved = store.load("p1").distanceMeters;
            check(saved != null && live.meters() != null && Math.abs(saved - live.meters()) < 0.5,
                    "the running distance matches the saved track's: " + live.meters() + " vs " + saved);
            check(live.meters() > 200 && live.meters() < 300, "about 40 + 10 + 5 steps of 5 m (jitter, the jump and the gap add nothing): " + live.meters());
        } finally { remove(dir); }
    }

    /** #643 F09: a ride's standing against a charge is in its journal, so a recovered ride keeps it; older readers skip it. */
    private static void exemptionInTheJournal() throws IOException {
        File dir = Files.createTempDirectory("volt-charge-exempt").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            String id = rides.start(PROFILE, "", WALL0 + now, now);
            check(rides.activeChargeExemption() == null, "a new ride has no CHARGE row");
            rides.markChargeExemption("YES");
            for (; now < 40_000; now += 1000) sample(rides, now, 10.0, 100.0 + now / 1e6);
            RideStore recovered = new RideStore(dir); // a process restart mid-ride
            check(id.equals(recovered.activeId()) && "YES".equals(recovered.activeChargeExemption()), "recovered with its standing");
            recovered.markChargeExemption("NO");
            RideStore again = new RideStore(dir);
            check("NO".equals(again.activeChargeExemption()), "the last row wins");
            RideStore.Summary s = again.stop(WALL0 + now, now, "", true);
            check(!again.lastStopDiscarded() && again.get(s.id).sampleCount == s.sampleCount && again.activeChargeExemption() == null, "the row changes nothing else");
        } finally { remove(dir); }
    }

    /** Every end reason, a hand stop included: a short ride is removed, with no journal and no summary row. */
    private static void shortRidesAreNotKept() throws Exception {
        File dir = Files.createTempDirectory("volt-short-rides").toFile(), index = new File(dir, "index");
        try {
            RideStore rides = new RideStore(new File(dir, "rides"), false, index);
            long before = rides.lastStopWallMs();
            for (String reason : new String[]{"", RideStore.STOP_CHARGING, RideStore.STOP_AUTO_NO_MOTION, RideStore.STOP_AUTO_PARKED, RideStore.quietStopReason(8, false)}) {
                long now = 10_000;
                String id = rides.start(PROFILE, "", WALL0 + now, now);
                for (; now < 25_000; now += 1000) sample(rides, now, 3.0, 100.0);
                RideStore.Summary s = rides.stop(WALL0 + now, now, reason, true);
                check(s != null && id.equals(s.id) && rides.lastStopDiscarded(), "15 s ride, end '" + reason + "': discarded");
                check(rides.activeId() == null && rides.get(id) == null && rides.list().isEmpty(), "no journal, no summary row");
                check(rides.lastStopWallMs() == before, "nothing reads it as the last saved ride");
            }
            rides.flushIndex();
            String[] left = index.list();
            check(left == null || left.length == 0, "no saved summary left in the index");
        } finally { remove(dir); }
    }

    private static void keptRidesStay() throws Exception {
        File dir = Files.createTempDirectory("volt-kept-rides").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            String id = rides.start(PROFILE, "", WALL0 + now, now);
            for (; now < 70_000; now += 1000) sample(rides, now, 15.0, 100.0 + (now - 10_000) / 60_000.0 * 0.3);
            RideStore.Summary s = rides.stop(WALL0 + now, now, "", true);
            check(!rides.lastStopDiscarded() && rides.get(id) != null && rides.list().size() == 1 && rides.lastStopWallMs() == WALL0 + now, "a 60 s, 0.3 km ride is kept");
            // Off by default for every other caller: existing short rides and the store's own stop are untouched.
            String shortId = rides.start(PROFILE, "", WALL0 + now, now);
            rides.stop(WALL0 + now + 5000, now + 5000, "");
            check(!rides.lastStopDiscarded() && rides.get(shortId) != null, "stop without discardShort keeps even a 5 s ride");
            check(s.id.equals(id), "summary returned");
        } finally { remove(dir); }
    }

    /** After a discard, a reload finds nothing; the earlier kept ride is still the newest (Home's Just back reads it). */
    private static void discardLeavesNoSummary() throws Exception {
        File dir = Files.createTempDirectory("volt-discard-reload").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            String kept = rides.start(PROFILE, "Commute", WALL0 + now, now);
            for (; now < 600_000; now += 1000) sample(rides, now, 18.0, 100.0 + (now - 10_000) / 590_000.0 * 3);
            rides.stop(WALL0 + now, now, "", true);
            long savedAt = rides.lastStopWallMs();
            String stray = rides.start(PROFILE, "", WALL0 + now + 90_000, now + 90_000);
            for (long end = now + 114_000, t = now + 90_000; t < end; t += 1000) sample(rides, t, 2.0, 103.0);
            rides.stop(WALL0 + now + 114_000, now + 114_000, RideStore.STOP_CHARGING, true);
            check(rides.lastStopDiscarded() && rides.lastStopWallMs() == savedAt, "the stray 24 s indoor ride is gone; the last save is still the commute");
            RideStore reloaded = new RideStore(dir);
            check(reloaded.list().size() == 1 && reloaded.list().get(0).id.equals(kept) && reloaded.get(stray) == null, "a reload lists the commute only");
        } finally { remove(dir); }
    }

    /** The service applies the rules on its one stop path and in its one change pass. */
    private static void serviceWiring() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        int a = src.indexOf("private boolean stopRide(String reason){"); check(a >= 0, "stopRide found");
        String stop = src.substring(a, src.indexOf("\n    /**", a));
        check(stop.contains("rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime(),reason,RideStore.discardsShort(),phoneTrackKm())") && RideStore.discardsShort(),
                "every stop, the rider's and the automatic ones, discards a short ride (the validations' hold is off in the app)");
        check(stop.contains("if(!discarded)RideAutoExportFolder.queue(settings,stopping);"), "a discarded ride is never copied out (ride auto-export)");
        check(stop.contains("phoneDiscard=discarded?stopping:null;stopPhoneRecording();"), "its phone GPS files go once saved");
        int changed = src.indexOf("private void changed(){");
        String pass = src.substring(changed, src.indexOf("\n", changed));
        check(pass.indexOf("observeChargeEndsRide(") > 0 && pass.indexOf("observeChargeEndsRide(") < pass.indexOf("observeAutoCharge(")
                && pass.indexOf("observeAutoCharge(") < pass.indexOf("observeAutoStart("), "a charge ends the ride before Record charges automatically and auto-record look");
        int o = src.indexOf("private void observeAutoStart(long mono){");
        String auto = src.substring(o, src.indexOf("\n    }", o));
        check(auto.contains("rideCharge.blocksAutoRecord(") && auto.indexOf("rideCharge.blocksAutoRecord(") < auto.indexOf("beginRide("), "auto-record waits while charging");
        check(auto.contains("ble.scooterOff(),ble,ble.intentionalDisconnectSequence(),!linkUp&&!visible&&LINK_LOG.now().paused()"),
                "the holds see the switch-off, the transport's Disconnects and Volt's own pause out of sight");
        int e = src.indexOf("private void observeChargeEndsRide(long mono){");
        String ends = src.substring(e, src.indexOf("\n    }\n", e));
        check(ends.indexOf("chargeEndKeepUntil=now+CHARGE_END_KEEP_MS;") > 0 && ends.indexOf("chargeEndKeepUntil=now+CHARGE_END_KEEP_MS;") < ends.indexOf("stopRide(RideStore.STOP_CHARGING);"),
                "the link is held for the charge recording before the stop's work policy runs (#643 finding 4)");
        check(src.contains("private boolean holding(){return chargeMeasure.active()||scheduleTesting()||chargeEndHolds();}"), "and the hold keeps the transport up out of sight");
        check(src.contains("finishPhoneDiscards();") && src.contains("if(discarded)pendingPhoneDiscard(stopping,true);"), "pending phone-file deletes outlive a process death (F11)");
        check(src.contains("stopRide(RideStore.STOP_CHARGING)"), "the charge end saves with its own code");
        check(src.contains("private static final AutoStartPolicy AUTO_START=new AutoStartPolicy();"), "one hold for the process, so a new service keeps it");
        int n = src.indexOf("private void autoStopNoMotion(RideAutoStopPolicy policy){");
        check(src.substring(n, src.indexOf("\n    }", n)).contains("if(rides.lastStopDiscarded())return;"), "no 'saved automatically' notice for a discarded ride");
    }

    private static void sample(RideStore rides, long now, Double kmh, Double odometer) throws IOException {
        Map<String, RideStore.Sample> v = new LinkedHashMap<>();
        if (kmh != null) v.put("speed_kmh", new RideStore.Sample(kmh, now));
        if (odometer != null) v.put("odometer_km", new RideStore.Sample(odometer, now));
        v.put("voltage_v", new RideStore.Sample(50, now)); v.put("current_a", new RideStore.Sample(0.1, now)); v.put("battery_percent", new RideStore.Sample(60, now));
        rides.sample(WALL0 + now, now, v);
    }
    private static void remove(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) remove(k); f.delete(); }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }
}
