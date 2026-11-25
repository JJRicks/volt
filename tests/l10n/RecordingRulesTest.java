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
        failedStopIsTriedOnce();
        chargingEndCode();
        keepRule();
        shortRidesAreNotKept();
        keptRidesStay();
        discardLeavesNoSummary();
        unmeasuredDistance();
        coveredRides();
        keepGrid();
        savedPhoneTrack();
        exemptionInTheJournal();
        discardNotedFirst();
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
        // #643 round 2, F04: ridden off fast before any fresh charging reading: not exempt, so the plug-in ends it.
        check(r.rideStarted("blind3", t, true, null) == RideChargeRule.Exempt.UNDECIDED
                && !r.endsRide("blind3", null, -1, true) && r.exempt("blind3") == RideChargeRule.Exempt.NO,
                "undecided, then a fresh speed above 3 mph: an ordinary ride");
        check(r.endsRide("blind3", Boolean.TRUE, t + 60_000, false), "which the plug-in ends");
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

    /**
     * #643 round 2, F07: storage full when the charge ends the ride. The service marks the stop tried before stopping, and
     * the failed stop's own change notice (and every one after) asks again: exactly one try, so one failed write and one toast.
     */
    private static void failedStopIsTriedOnce() {
        long t = 4_000_000;
        RideChargeRule r = auto("full", t - 60_000);
        int tries = 0;
        for (long now = t; now < t + 5 * 60_000; now += 500) {
            r.observe(A, Boolean.TRUE, now);
            if (r.endsRide("full", Boolean.TRUE, now, false)) { r.stopTried("full"); tries++; } // stopRide fails: still recording
        }
        check(tries == 1, "storage full and plugged in for 5 minutes: one stop tried, not one per change: " + tries);
        check(r.exempt("full") == RideChargeRule.Exempt.NO, "the ride stays an ordinary one; the rider's Stop (Retry save) is the retry");
        RideChargeRule next = auto("next", t + 6 * 60_000);
        next.stopTried("full");
        check(next.endsRide("next", Boolean.TRUE, t + 7 * 60_000, false), "the next ride ends on a plug-in as usual");
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

    /** A stop under the rule with the phone's distance (km, null for none). */
    private static RideStore.Summary stop(RideStore rides, long t, String reason, Double phoneKm) throws IOException {
        return rides.stop(WALL0 + t, t, reason, true, phoneKm == null ? null : () -> phoneKm, null);
    }

    /**
     * #643 round 1, finding 3, and round 3: a link lost right after the start leaves the scooter's evidence incomplete, so
     * only the whole recording's time can discard it; the phone's track keeps it either way.
     */
    private static void unmeasuredDistance() throws IOException {
        File dir = Files.createTempDirectory("volt-unmeasured").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            rides.start(PROFILE, "", WALL0 + now, now);
            sample(rides, now, 0.0, 100.0);          // the one reading before the link went
            long end = now + 40_000;                  // 40 s more, disconnected: no samples
            RideStore.Summary s = stop(rides, end, "", 0.3);
            check(!rides.lastStopDiscarded() && rides.get(s.id) != null, "link lost at the start, a 300 m phone track: kept");
            rides.start(PROFILE, "", WALL0 + end, end);
            sample(rides, end, 0.0, 100.0);
            stop(rides, end + 40_000, "", null);
            check(!rides.lastStopDiscarded(), "link lost at the start, no phone track, 40 s: kept (not covered, over 20 s)");
            long t = end + 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            sample(rides, t, 0.0, 100.0);
            stop(rides, t + 12_000, "", null);
            check(rides.lastStopDiscarded(), "12 s in all, no phone track: not kept");
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            sample(rides, t, 0.0, 100.0);
            stop(rides, t + 12_000, "", 0.3);
            check(!rides.lastStopDiscarded(), "12 s with 300 m on the phone's track: kept (phone first)");
            // Covered and short; the phone's track decides between kept and not.
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 30_000, "", 0.030);
            check(!rides.lastStopDiscarded(), "the scooter measured 0 m but the phone's track has 30 m: kept");
            t += 100_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 30_000, "", 0.010);
            check(rides.lastStopDiscarded(), "the scooter measured 0 m and the phone 10 m: not kept");
        } finally { remove(dir); }
    }

    /**
     * #643 round 3: covered means scooter readings from the start to the stop with no gap over COVERED_GAP_MS (10 s, above
     * the 2 s recording poll). A gap anywhere, at the start, in the middle or before the stop, leaves only the whole
     * recording's time as evidence.
     */
    private static void coveredRides() throws IOException {
        File dir = Files.createTempDirectory("volt-covered").toFile();
        try {
            RideStore rides = new RideStore(dir);
            check(RideStore.COVERED_GAP_MS == 10_000 && RideStore.COVERED_GAP_MS > StatusCadence.POLL_FAST_MS
                    && RideStore.COVERED_GAP_MS < StatusCadence.SILENCE_FAST_MS, "10 s: over the recording poll, under the link's silence limit");
            check(RideStore.covered(0, 0, 10_000) && !RideStore.covered(0, 0, 10_001) && !RideStore.covered(10_001, 50_000, 50_000)
                    && !RideStore.covered(0, -1, 5_000), "a 10 s wait is covered, longer isn't, and no reading never is");
            long t = 10_000;
            // Readings to 60 s, stopped at 75 s: the last 15 s unseen.
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 60_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 75_000, "", null);
            check(!rides.lastStopDiscarded(), "0 m to 60 s, stopped at 75 s: not covered, kept");
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 60_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 69_000, "", null);
            check(rides.lastStopDiscarded(), "0 m to 60 s, stopped at 69 s: covered, not kept");
            // The first reading 12 s after the start.
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t + 12_000; u <= t + 40_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 40_000, "", null);
            check(!rides.lastStopDiscarded(), "no reading for the first 12 s, then 0 m: not covered, kept");
            // Speed only, to the stop: 6 m covered.
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 40_000; u += 1000) sample(rides, u, 0.5, null);
            stop(rides, t + 41_000, "", null);
            check(rides.lastStopDiscarded(), "speed readings to the stop, 6 m: covered, not kept");
            // Readings with no distance at all (no odometer, no speed): no evidence against it.
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, null, null);
            stop(rides, t + 30_000, "", null);
            check(!rides.lastStopDiscarded(), "covered for 30 s with no distance reading: kept");
        } finally { remove(dir); }
    }

    /** #643 round 3's grid: discard only on positive evidence, otherwise keep. */
    private static void keepGrid() throws IOException {
        File dir = Files.createTempDirectory("volt-keep-grid").toFile();
        try {
            RideStore rides = new RideStore(new File(dir, "rides"));
            // Jostled with the link up, ended by the no-motion stop: 5 s of 3 km/h, ten still minutes.
            long t = 10_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u < t + 5_000; u += 1000) sample(rides, u, 3.0, 100.0);
            for (long u = t + 5_000; u <= t + 605_000; u += 1000) sample(rides, u, 0.0, 100.0);
            RideStore.Summary jostle = stop(rides, t + 605_000, RideStore.STOP_AUTO_NO_MOTION, null);
            check(jostle.stopEndsAtMotion && jostle.shownElapsedMs < RideStore.MIN_KEEP_MS && rides.lastStopDiscarded(),
                    "a jostle with the link up, ended by no motion: covered, shown " + jostle.shownElapsedMs + " ms, not kept");
            // A 6 s hand stop.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 6_000; u += 1000) sample(rides, u, 8.0, 100.0);
            stop(rides, t + 6_000, "", null);
            check(rides.lastStopDiscarded(), "a 6 s hand stop: not kept");
            // Bluetooth lost 5 s in, the phone recording 5 minutes (1.2 km), the quiet stop: kept; and with no GPS.
            for (Double phone : new Double[]{1.2, null}) {
                t += 1_000_000;
                rides.start(PROFILE, "", WALL0 + t, t);
                for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 15.0, 100.0);
                RideStore.Summary quiet = stop(rides, t + 300_000, RideStore.quietStopReason(8, false), phone);
                check(!rides.lastStopDiscarded() && quiet.shownElapsedMs <= 5_000,
                        "Bluetooth lost 5 s in, quiet stop at 5 min, " + (phone == null ? "no GPS" : "1.2 km of GPS") + ": kept (shown " + quiet.shownElapsedMs + " ms)");
            }
            // A gap with movement (30 s), then 60 s stationary with the link back, then a hand stop.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 3_000; u += 1000) sample(rides, u, 12.0, 100.0);
            for (long u = t + 33_000; u <= t + 93_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 93_000, "", null);
            check(!rides.lastStopDiscarded(), "moving into a 30 s gap, then a still minute: kept");
            // A 25 s covered ride of 10 m.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 25_000; u += 1000) sample(rides, u, 1.44, 100.0);
            RideStore.Summary ten = stop(rides, t + 25_000, "", null);
            check(rides.lastStopDiscarded() && Math.abs(ten.speedIntegratedDistanceKm - 0.010) < 0.001, "a covered 25 s ride of 10 m: not kept");
        } finally { remove(dir); }
    }

    /**
     * #643 round 3: the phone's evidence is its saved journal, read at the stop (PhoneRideStore.load's own filter: moving
     * steps, speeds no less sure than 1.5 m/s), so a ride recovered after a restart counts its whole track.
     */
    private static void savedPhoneTrack() throws IOException {
        File dir = Files.createTempDirectory("volt-saved-phone").toFile();
        try {
            PhoneRideStore phone = new PhoneRideStore(new File(dir, "phone-rides"));
            RideStore rides = new RideStore(new File(dir, "rides"));
            long t = 10_000;
            String id = rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 15.0, 100.0);
            // The phone: 60 fixes 5 m apart at 5 m/s, a 300 m track.
            phone.start(id, WALL0 + t, t);
            StringBuilder rows = new StringBuilder();
            double lat = 40.0;
            for (int i = 1; i <= 60; i++) { lat += 5 / 111_320.0; rows.append(PhoneRideStore.row("GPS", WALL0 + t + i * 1000L, t + i * 1000L, lat, -105.0, 5.0, 5.0, 0.5, null, null)); }
            phone.appendBatch(id, rows.toString(), "");
            // The process dies; a new store recovers the ride, and it is stopped 15 s in with no reading since 5 s.
            RideStore recovered = new RideStore(new File(dir, "rides"));
            check(id.equals(recovered.activeId()), "the ride was recovered");
            recovered.stop(WALL0 + t + 15_000, t + 15_000, "", true, () -> km(phone, id), null);
            check(!recovered.lastStopDiscarded() && recovered.get(id) != null && km(phone, id) > 0.29,
                    "recovered after a restart, 15 s, with a 300 m saved phone track: kept (" + km(phone, id) + " km)");
            // The same track with speeds no surer than 3 m/s: no moving steps, so no phone evidence.
            String unsure = recovered.start(PROFILE, "", WALL0 + t + 100_000, t + 100_000);
            phone.start(unsure, WALL0 + t + 100_000, t + 100_000);
            rows.setLength(0);
            for (int i = 1; i <= 10; i++) { lat += 5 / 111_320.0; rows.append(PhoneRideStore.row("GPS", WALL0 + t + 100_000 + i * 1000L, t + 100_000 + i * 1000L, lat, -105.0, 5.0, 5.0, 3.0, null, null)); }
            phone.appendBatch(unsure, rows.toString(), "");
            RideStore third = new RideStore(new File(dir, "rides"));
            third.stop(WALL0 + t + 112_000, t + 112_000, "", true, () -> km(phone, unsure), null);
            check(third.lastStopDiscarded(), "12 s, 50 m of fixes whose speed is no surer than 3 m/s: no phone evidence, not kept");
        } finally { remove(dir); }
    }
    private static Double km(PhoneRideStore phone, String id) {
        try { Double m = phone.load(id).distanceMeters; return m == null ? null : m / 1000.0; } catch (IOException e) { return null; }
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

    /** #643 round 2, F06: the caller hears of a discard before the journal goes, and never of a kept ride. */
    private static void discardNotedFirst() throws IOException {
        File dir = Files.createTempDirectory("volt-discard-first").toFile();
        try {
            RideStore rides = new RideStore(dir);
            final java.util.List<String> heard = new java.util.ArrayList<>();
            final boolean[] journalThen = {false};
            long now = 10_000;
            String id = rides.start(PROFILE, "", WALL0 + now, now);
            for (; now < 18_000; now += 1000) sample(rides, now, 3.0, 100.0);
            rides.stop(WALL0 + now, now, "", true, null, rid -> { heard.add(rid); journalThen[0] = rides.journal(rid).exists(); return true; });
            check(heard.size() == 1 && heard.get(0).equals(id) && journalThen[0] && rides.lastStopDiscarded() && !rides.journal(id).exists(),
                    "an 8 s ride: noted while its journal was still there, then deleted");
            heard.clear();
            String kept = rides.start(PROFILE, "", WALL0 + now, now);
            for (long end = now + 60_000; now < end; now += 1000) sample(rides, now, 15.0, 100.0 + now / 1e6);
            rides.stop(WALL0 + now, now, "", true, null, rid -> heard.add(rid));
            check(heard.isEmpty() && !rides.lastStopDiscarded() && rides.get(kept) != null, "a kept ride: nothing noted");
            // #643 round 3, F08: the note couldn't be saved (commit() false): the short ride is kept, journal and all.
            long savedBefore = rides.lastStopWallMs();
            String unnoted = rides.start(PROFILE, "", WALL0 + now, now);
            for (long end = now + 8_000; now < end; now += 1000) sample(rides, now, 3.0, 100.0);
            rides.stop(WALL0 + now, now, "", true, null, rid -> false);
            check(!rides.lastStopDiscarded() && rides.journal(unnoted).exists() && rides.get(unnoted) != null
                    && rides.lastStopWallMs() == WALL0 + now && rides.lastStopWallMs() != savedBefore, "an 8 s ride whose note failed: kept as a saved ride");
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
        check(stop.contains("rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime(),reason,RideStore.discardsShort(),()->phoneSavedKm(stopping),id->pendingPhoneDiscard(id,true))") && RideStore.discardsShort(),
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
        check(src.contains("private boolean pendingPhoneDiscard(String id,boolean add){") && src.contains("return settings.edit().putString(RideStore.DISCARD_PENDING_KEY,String.join(\" \",ids)).commit();"),
                "the note reports whether commit() saved it, so a failed one keeps the ride (round 3 F08)");
        check(src.contains("PhoneRideStore.Track track=phoneRides.load(id);"), "the phone's evidence is its saved journal (round 3)");
        check(src.contains("finishPhoneDiscards();") && stop.contains("id->pendingPhoneDiscard(id,true)") && stop.contains("if(!discarded)pendingPhoneDiscard(stopping,false);"),
                "pending phone-file deletes are noted before the journal goes and outlive a process death (F11, round 2 F06)");
        check(ends.indexOf("rideCharge.stopTried(id);") > 0 && ends.indexOf("rideCharge.stopTried(id);") < ends.indexOf("stopRide(RideStore.STOP_CHARGING);"),
                "a charge-ended stop is marked tried before it runs, so a failed one isn't retried in a loop (round 2 F07)");
        check(auto.indexOf("autoStart.observeSpeed(linkUp,ble.speedRaw(),ble.motionSampleMs(),mono);") > auto.indexOf("autoStart.observeLink("),
                "riding off after Volt's own pause ends the holds (round 2 F01)");
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
