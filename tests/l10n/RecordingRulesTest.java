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
        waitSurvivesRestart();
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
        roundSevenCoverage();
        heldForUndo();
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

    /** #643 round 4, F05: the service is recreated inside the 30 s wait; the new one still waits, in the same boot only. */
    private static void waitSurvivesRestart() {
        long t = 2_000_000, last = t + 58_000;
        RideChargeRule before = new RideChargeRule();
        long saved = -1;
        String stored = null;
        // Charging readings every 2 s for most of a minute, saved at most every 5 s, then unplugged.
        for (long now = t; now <= last; now += 2000) {
            before.observe(A, Boolean.TRUE, now);
            if (RideChargeRule.saveDue(before.chargingSeenMs(), saved, saved >= 0, Boolean.TRUE)) { stored = before.savedWait(7, true); saved = before.chargingSeenMs(); }
        }
        check(saved < last && last - saved <= RideChargeRule.SAVE_EVERY_MS, "while charging, saved at most 5 s behind the last reading: " + (last - saved));
        before.observe(A, Boolean.FALSE, last + 2000);
        check(RideChargeRule.saveDue(before.chargingSeenMs(), saved, true, Boolean.FALSE), "the reading that isn't charging saves the exact last one");
        String midCharge = stored;
        long midSaved = saved;
        stored = before.savedWait(7, false); saved = before.chargingSeenMs();
        check(saved == last && !RideChargeRule.saveDue(before.chargingSeenMs(), saved, false, Boolean.FALSE), "saved once");
        // Round 6, F01: plugged back in, the change back to charging saves at once, not 5 s later.
        RideChargeRule replug = new RideChargeRule();
        replug.observe(A, Boolean.TRUE, last);
        replug.observe(A, Boolean.TRUE, last + 3000);
        check(RideChargeRule.saveDue(replug.chargingSeenMs(), last, false, Boolean.TRUE), "charging again 3 s after the exact save: saved at once");
        check(!RideChargeRule.saveDue(replug.chargingSeenMs(), last, true, Boolean.TRUE), "while it keeps charging, 3 s after a mid-charge save: not yet");
        check(RideChargeRule.saveDue(replug.chargingSeenMs(), last + 3000, true, Boolean.FALSE), "the first reading that isn't charging, even right after a save: saved, exact");
        // The service is recreated 10 s after the last charging reading.
        RideChargeRule after = new RideChargeRule();
        check(after.restoreWait(stored, 7, last + 10_000), "the new service takes the saved wait");
        after.observe(A, Boolean.FALSE, last + 10_000);
        check(after.blocksAutoRecord(A, last + 10_000, false) && after.blocksAutoRecord(A, last + 29_999, false), "and still waits out the 30 s");
        check(!after.blocksAutoRecord(A, last + 30_000, false), "until 30 s after the last charging reading");
        check(!after.blocksAutoRecord(B, last + 15_000, false), "another scooter doesn't wait");
        check(!new RideChargeRule().restoreWait(stored, 8, last + 10_000), "another boot: nothing restored");
        check(!new RideChargeRule().restoreWait(stored, -1, last + 10_000), "no boot count: nothing restored");
        check(!new RideChargeRule().restoreWait(stored, 7, last - 1), "a time from the future: nothing restored");
        check(!new RideChargeRule().restoreWait("junk", 7, last) && !new RideChargeRule().restoreWait(null, 7, last), "junk or none: nothing restored");
        RideChargeRule seen = new RideChargeRule();
        seen.observe(A, Boolean.TRUE, last + 9_000);
        check(!seen.restoreWait(stored, 7, last + 10_000) && seen.chargingSeenMs() == last + 9_000, "a reading already seen wins over the saved one");
        // A process death mid-charge (round 5, F01): the last save was taken mid-charge, so the restore takes the latest
        // time a charging reading could have come before the death (one save interval later), never an earlier one.
        RideChargeRule died = new RideChargeRule();
        check(died.restoreWait(midCharge, 7, midSaved + 20_000) && died.chargingSeenMs() == midSaved + RideChargeRule.SAVE_EVERY_MS,
                "a save made mid-charge restores as " + RideChargeRule.SAVE_EVERY_MS + " ms later");
        check(died.blocksAutoRecord(A, midSaved + RideChargeRule.SAVE_EVERY_MS + 29_999, false), "so the wait never ends early");
        RideChargeRule soon = new RideChargeRule();
        check(soon.restoreWait(midCharge, 7, midSaved + 1000) && soon.chargingSeenMs() == midSaved + 1000, "never later than now");
        check(RideChargeRule.WAIT_KEY.equals("ride_charge_wait"), "key");
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
            RideStore.EndKind stop = RideStore.EndKind.AT_STOP, wait = RideStore.EndKind.AFTER_WAIT, quiet = RideStore.EndKind.QUIET;
            check(RideStore.covered(stop, 23_000, 0, 20_000, false) && !RideStore.covered(stop, 23_001, 0, 20_000, false),
                    "a rider's stop: the last speed reading still fresh (3 s) at the stop");
            check(RideStore.covered(wait, 600_000, 0, 20_000, false), "a verdict's wait after the window is no gap");
            check(!RideStore.covered(quiet, 20_000, 0, 20_000, false), "a quiet end is never covered");
            check(!RideStore.covered(stop, 20_000, 0, 20_000, true), "an interval the integrator skipped breaks coverage");
            check(!RideStore.covered(stop, 20_000, -1, -1, false) && !RideStore.covered(stop, 20_000, 3_001, 20_000, false)
                    && RideStore.covered(stop, 20_000, 3_000, 20_000, false), "no speed reading, or the first after 3 s: not covered");
            long t = 10_000;
            // Readings to 60 s, stopped at 75 s: the last 15 s unseen.
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 60_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 75_000, "", null);
            check(!rides.lastStopDiscarded(), "0 m to 60 s, stopped at 75 s: not covered, kept");
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 60_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 61_000, "", null);
            check(rides.lastStopDiscarded(), "0 m to 60 s, stopped at 61 s: covered, not kept");
            // The first reading 12 s after the start.
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t + 12_000; u <= t + 40_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 40_000, "", null);
            check(!rides.lastStopDiscarded(), "no reading for the first 12 s, then 0 m: not covered, kept");
            // Odometer readings alone, flat: they prove nothing.
            t += 200_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, null, 100.0);
            stop(rides, t + 31_000, "", null);
            check(!rides.lastStopDiscarded(), "30 s of a flat odometer and no speed: not covered, kept");
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
            stop(rides, t + 31_000, "", null);
            check(!rides.lastStopDiscarded(), "30 s of battery readings, no speed or odometer: not covered, kept");
        } finally { remove(dir); }
    }

    /** Speed at 1 s samples, pushed every pushMs, each push lead ms before a sample's whole second; the odometer flat. */
    private static void pushed(RideStore rides, long from, long to, double kmh, long pushMs, long lead) throws IOException {
        for (long u = from; u <= to; u += 1000) {
            long push = from - lead + ((u - from + lead) / pushMs) * pushMs;
            Map<String, RideStore.Sample> v = new LinkedHashMap<>();
            v.put("speed_kmh", new RideStore.Sample(kmh, Math.min(u, push)));
            v.put("odometer_km", new RideStore.Sample(100.0, u));
            v.put("voltage_v", new RideStore.Sample(50, u)); v.put("current_a", new RideStore.Sample(0.1, u)); v.put("battery_percent", new RideStore.Sample(60, u));
            rides.sample(WALL0 + u, u, v);
        }
    }
    /** Moving at kmh to the end time, ended by cause with its real wait; returns whether the ride was kept. */
    private static boolean endedAt(RideStore rides, long t, long seconds, double kmh, String cause) throws IOException {
        rides.start(PROFILE, "", WALL0 + t, t);
        long end = t + seconds * 1000;
        for (long u = t; u <= end; u += 1000) sample(rides, u, kmh, 100.0 + (u - t) / 3.6e9 * kmh * 1000);
        long stopAt = end;
        if (cause.equals(RideStore.STOP_AUTO_NO_MOTION)) {            // ten still minutes, speed 0, the link up
            for (long u = end + 1000; u <= end + 600_000; u += 1000) sample(rides, u, 0.0, null);
            stopAt = end + 600_000;
        } else if (RideStore.isSwitchedOffStop(cause)) {               // switched off: battery polls, no speed; 10 s verdict, 10 s grace
            for (long u = end + 1000; u <= end + 20_000; u += 1000) sample(rides, u, null, null);
            stopAt = end + 20_000;
        }
        stop(rides, stopAt, cause, null);
        return !rides.lastStopDiscarded();
    }

    /**
     * The keep rule's grid (#643 rounds 3 to 5): discard only on positive evidence, otherwise keep. Every stop goes through
     * RideStore.stop with its end cause's real wait, never at the instant of the last sample.
     */
    private static void keepGrid() throws IOException {
        File dir = Files.createTempDirectory("volt-keep-grid").toFile();
        try {
            RideStore rides = new RideStore(new File(dir, "rides"));
            String off = RideStore.switchedOffStopReason(RideStore.STOP_AUTO_QUIET), quiet = RideStore.quietStopReason(8, false);
            long t = 10_000;
            // Round 5's triggers. F02: the parked end is a link loss, so a short scooter time with a long GPS track is kept.
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 12.0, 100.0);
            stop(rides, t + 14_000, RideStore.STOP_AUTO_PARKED, 0.3);
            check(!rides.lastStopDiscarded(), "the parked end after 4 s of scooter readings, 300 m of GPS: kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 12.0, 100.0);
            stop(rides, t + 14_000, RideStore.STOP_AUTO_PARKED, null);
            check(rides.lastStopDiscarded(), "the same with no GPS, 14 s in all: not kept");
            // F03: speed frames every 8 s at 4.5 km/h for 30 s, no GPS, the odometer flat: the integrator skips, not covered.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            pushed(rides, t, t + 30_000, 4.5, 8_000, 0);
            RideStore.Summary sparse = stop(rides, t + 30_000, "", null);
            check(!rides.lastStopDiscarded() && sparse.speedIntegratedDistanceKm < RideStore.MIN_KEEP_KM,
                    "speed every 8 s at 4.5 km/h for 30 s (37 m, " + Math.round(sparse.speedIntegratedDistanceKm * 1000) + " m integrated): not covered, kept");
            // F04: Record tapped while parked, no movement (speed 0), then switched off after 25 s; and the no-motion end.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 25_000; u += 1000) sample(rides, u, 0.0, 100.0);
            for (long u = t + 26_000; u <= t + 45_000; u += 1000) sample(rides, u, null, null);
            stop(rides, t + 45_000, off, null);
            check(rides.lastStopDiscarded(), "Record tapped while parked, switched off after 25 s: not kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 600_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 600_000, RideStore.STOP_AUTO_NO_MOTION, null);
            check(rides.lastStopDiscarded(), "Record tapped while parked, the no-motion end: not kept");
            // Each known end at 19 s and 21 s, covered (15 km/h: 79 m and 87 m).
            for (String cause : new String[]{"", RideStore.STOP_CHARGING, RideStore.STOP_AUTO_NO_MOTION, off}) {
                t += 2_000_000;
                check(!endedAt(rides, t, 19, 15.0, cause), "end '" + cause + "' at 19 s: not kept");
                t += 2_000_000;
                check(endedAt(rides, t, 21, 15.0, cause), "end '" + cause + "' at 21 s: kept");
            }
            // A covered ride of 15 m and of 17 m (30 s, a hand stop), no GPS.
            t += 2_000_000;
            check(!endedAt(rides, t, 30, 1.8, ""), "a covered 30 s ride of 15 m: not kept");
            t += 2_000_000;
            check(endedAt(rides, t, 30, 2.04, ""), "a covered 30 s ride of 17 m: kept");
            // A quiet end at 19 s and 21 s with no track, and at 15 s with a track at the 0.01 mi line (16.09 m).
            for (long seconds : new long[]{19, 21}) {
                t += 1_000_000;
                rides.start(PROFILE, "", WALL0 + t, t);
                for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 15.0, 100.0);
                stop(rides, t + seconds * 1000, quiet, null);
                check(rides.lastStopDiscarded() == (seconds < 20), "a quiet end at " + seconds + " s, no track: " + (seconds < 20 ? "not kept" : "kept"));
            }
            for (double phone : new double[]{0.0161, 0.0160}) {
                t += 1_000_000;
                rides.start(PROFILE, "", WALL0 + t, t);
                for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 15.0, 100.0);
                stop(rides, t + 15_000, quiet, phone);
                check(rides.lastStopDiscarded() == (phone < RideStore.MIN_KEEP_KM), "a quiet end at 15 s with " + phone * 1000 + " m of GPS: " + (phone < RideStore.MIN_KEEP_KM ? "not kept" : "kept"));
            }
            // A gap of exactly the freshness limit: pushes every 4 s, the oldest sample 3000 ms after its push, is covered; 3001 isn't.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            pushed(rides, t, t + 25_000, 1.44, 4_000, 0);
            stop(rides, t + 25_000, "", null);
            check(rides.lastStopDiscarded(), "speed pushed every 4 s (3000 ms old at most), 25 s, 10 m: covered, not kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            pushed(rides, t, t + 25_000, 1.44, 4_000, 1);
            stop(rides, t + 25_000, "", null);
            check(!rides.lastStopDiscarded(), "the same with samples 3001 ms after a push: skipped, not covered, kept");

            // Round 6, F03: Bluetooth lost while riding on (90 s), back when stopped, then the switch-off or the no-motion end:
            // the reconnected gap can hide motion, so the ride isn't covered and its 450 m GPS track keeps it.
            for (String cause : new String[]{off, RideStore.STOP_AUTO_NO_MOTION}) {
                t += 2_000_000;
                rides.start(PROFILE, "", WALL0 + t, t);
                for (long u = t; u <= t + 8_000; u += 1000) sample(rides, u, 15.0, 100.0);
                rides.markGap(WALL0 + t + 9_000, t + 9_000, "Bluetooth disconnected");
                long back = t + 100_000, still = cause.equals(off) ? 30_000 : 600_000;
                for (long u = back; u <= back + still; u += 1000) sample(rides, u, 0.0, 100.0);
                long stopAt = back + still;
                if (cause.equals(off)) { for (long u = stopAt + 1000; u <= stopAt + 20_000; u += 1000) sample(rides, u, null, null); stopAt += 20_000; }
                RideStore.Summary hidden = stop(rides, stopAt, cause, 0.45);
                check(!rides.lastStopDiscarded() && hidden.shownElapsedMs < RideStore.MIN_KEEP_MS,
                        "a link gap while riding, back when stopped, then end '" + cause + "': not covered, kept with its 450 m track (shown " + hidden.shownElapsedMs + " ms)");
            }
            // Round 6, F02: the end intervals held. First speed 3 s after the start, a hand stop 3 s after the last: 17 s
            // integrated at 2.9 km/h is 13.7 m, the 6 s held add 4.8 m, so the ride's 18.5 m keeps it.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t + 3_000; u <= t + 20_000; u += 1000) sample(rides, u, 2.9, 100.0);
            stop(rides, t + 23_000, "", null);
            check(!rides.lastStopDiscarded(), "a covered 23 s ride at 2.9 km/h, its end intervals held (18.5 m): kept");

            // The earlier grid.
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u < t + 4_000; u += 1000) sample(rides, u, 3.0, 100.0);
            for (long u = t + 4_000; u <= t + 24_000; u += 1000) sample(rides, u, null, null);
            RideStore.Summary offed = stop(rides, t + 24_000, off, null);
            check(offed.stopSwitchedOff && rides.lastStopDiscarded(), "a 4 s jostle, then switched off (20 s of verdict and grace): not kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u < t + 5_000; u += 1000) sample(rides, u, 3.0, 100.0);
            for (long u = t + 5_000; u <= t + 605_000; u += 1000) sample(rides, u, 0.0, 100.0);
            RideStore.Summary jostle = stop(rides, t + 605_000, RideStore.STOP_AUTO_NO_MOTION, null);
            check(jostle.stopEndsAtMotion && rides.lastStopDiscarded(), "a jostle ended by no motion: not kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 8.0, 100.0);
            stop(rides, t + 6_000, "", null);
            check(rides.lastStopDiscarded(), "a 6 s hand stop: not kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 14_000; u += 1000) sample(rides, u, 12.0, 100.0);
            stop(rides, t + 15_000, "", 0.05);
            check(rides.lastStopDiscarded(), "a covered 15 s ride with 50 m of GPS: not kept");
            for (Double phone : new Double[]{1.2, null}) {
                t += 1_000_000;
                rides.start(PROFILE, "", WALL0 + t, t);
                for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 15.0, 100.0);
                stop(rides, t + 300_000, quiet, phone);
                check(!rides.lastStopDiscarded(), "Bluetooth lost 5 s in, quiet end at 5 min, " + (phone == null ? "no GPS" : "1.2 km of GPS") + ": kept");
            }
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 3_000; u += 1000) sample(rides, u, 1.0, 100.0);
            for (long u = t + 4_000; u <= t + 33_000; u += 1000) sample(rides, u, null, null);
            for (long u = t + 34_000; u <= t + 44_000; u += 1000) sample(rides, u, 1.0, 100.0);
            stop(rides, t + 45_000, "", null);
            check(!rides.lastStopDiscarded(), "30 s of battery readings with no speed or odometer, then travel: not covered, kept");
            t += 1_000_000;
            check(!endedAt(rides, t, 25, 1.44, ""), "a covered 25 s ride of 10 m, no GPS: not kept");
            t += 1_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 25_000; u += 1000) sample(rides, u, 1.44, 100.0);
            stop(rides, t + 26_000, "", 0.020);
            check(!rides.lastStopDiscarded(), "a covered 25 s ride of 10 m with 20 m of GPS: kept");
        } finally { remove(dir); }
    }

    /** One recording tick: the speed as last pushed at pushAt (null for none), a flat odometer when odometer is set, battery values fresh. */
    private static void tick(RideStore rides, long u, Double kmh, long pushAt, Double odometer) throws IOException {
        Map<String, RideStore.Sample> v = new LinkedHashMap<>();
        if (kmh != null) v.put("speed_kmh", new RideStore.Sample(kmh, pushAt));
        if (odometer != null) v.put("odometer_km", new RideStore.Sample(odometer, u));
        v.put("voltage_v", new RideStore.Sample(50, u)); v.put("current_a", new RideStore.Sample(0.1, u)); v.put("battery_percent", new RideStore.Sample(60, u));
        rides.sample(WALL0 + u, u, v);
    }
    /**
     * The switch-off as the service records it: 10 s of readings with no new push (the last speed, pushed at lastPush, goes
     * stale), then the off verdict's GAP marked as the switch-off (ScooterService's tick) and its 10 s grace in heartbeats.
     * Returns the stop time.
     */
    private static long switchOff(RideStore rides, long from, double kmh, long lastPush) throws IOException {
        for (long u = from; u < from + 10_000; u += 1000) tick(rides, u, kmh, lastPush, 100.0);
        rides.markGap(WALL0 + from + 10_000, from + 10_000, "Scooter switched off");
        rides.markGapSwitchedOff();
        for (long u = from + 11_000; u <= from + 20_000; u += 1000) rides.heartbeat(WALL0 + u, u);
        return from + 20_000;
    }
    /**
     * Still at 0 km/h with a push just before every tick, except one push stall of 3.5 s: the last push before it is 100 ms
     * before stallAt and the next 3.4 s after, so the tick at stallAt + 3 s holds a speed 3.1 s old (stale) and the next one is
     * a skipped interval.
     */
    private static void stillWithStall(RideStore rides, long from, long to, long stallAt) throws IOException {
        for (long u = from; u <= to; u += 1000) {
            long push = u < stallAt ? u : u < stallAt + 3_400 ? stallAt - 100 : u - 600;
            tick(rides, u, 0.0, push, 100.0);
        }
    }

    /**
     * #643 round 7 (F02 + F03, release-gated, one fix; F04 and F05): a link gap counts from its GAP row, whether or not a
     * speed reading came after the reconnect, the switch-off verdict's own drop excepted; the integrator's own skips (a late
     * push or tick) count only inside the window; the window's last speed is timed when it was measured; a phone track
     * whose last fixes never flushed can't delete a ride. Every stop goes through RideStore.stop with its end's real wait.
     */
    private static void roundSevenCoverage() throws IOException {
        File dir = Files.createTempDirectory("volt-round-7").toFile();
        try {
            File ridesDir = new File(dir, "rides");
            RideStore rides = new RideStore(ridesDir);
            String off = RideStore.switchedOffStopReason(RideStore.STOP_AUTO_QUIET);
            long t = 10_000;
            // F03, the seat's StallProbe: a 4 s jostle, then the ten-minute no-motion wait with one 3.5 s push stall mid-wait.
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 3.0, 100.0);
            stillWithStall(rides, t + 5_000, t + 605_000, t + 300_000);
            stop(rides, t + 605_000, RideStore.STOP_AUTO_NO_MOTION, 0.0);
            check(rides.lastStopDiscarded(), "jostle + no-motion, one 3.5 s push stall mid-wait: not kept (the stall is outside the window)");
            // The same stall's samples really are skipped: a hand stop counts it (the window is the whole recording).
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            stillWithStall(rides, t, t + 30_000, t + 15_000);
            stop(rides, t + 30_000, "", null);
            check(!rides.lastStopDiscarded(), "a hand stop with the same push stall inside its window: not covered, kept (30 s)");
            // StallProbe's other case: one tick 3.2 s late in the wait.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 3.0, 100.0);
            for (long u = t + 5_000; u <= t + 300_000; u += 1000) sample(rides, u, 0.0, 100.0);
            for (long u = t + 303_200; u <= t + 605_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 605_000, RideStore.STOP_AUTO_NO_MOTION, 0.0);
            check(rides.lastStopDiscarded(), "jostle + no-motion, one tick 3.2 s late: not kept");
            // The judge's trigger: Record tapped while parked (no movement at all), one push 3.5 s late in the wait.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            stillWithStall(rides, t, t + 600_000, t + 300_000);
            stop(rides, t + 600_000, RideStore.STOP_AUTO_NO_MOTION, 0.0);
            check(rides.lastStopDiscarded(), "Record tapped while parked, one push 3.5 s late, the no-motion end: not kept");
            // And a jostle, a stall while still, then the switch-off.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 3.0, 100.0);
            stillWithStall(rides, t + 5_000, t + 40_000, t + 20_000);
            stop(rides, switchOff(rides, t + 41_000, 0.0, t + 40_000), off, 0.0);
            check(rides.lastStopDiscarded(), "jostle, a push stall while still, then switched off: not kept");
            // The parked accidental recording, no stall: still not kept, by either end.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 600_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 600_000, RideStore.STOP_AUTO_NO_MOTION, 0.0);
            check(rides.lastStopDiscarded(), "the parked accidental recording, the no-motion end: not kept");
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 25_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, switchOff(rides, t + 26_000, 0.0, t + 25_000), off, 0.0);
            check(rides.lastStopDiscarded(), "the parked accidental recording, then switched off (the verdict's gap): not kept");

            // A switch-off with no other gap stays covered: a 4 s jostle is not kept even with a 450 m track (a covered ride
            // under 20 s shown goes whatever the phone says), and a covered 25 s ride is kept.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 3.0, 100.0);
            stop(rides, switchOff(rides, t + 5_000, 3.0, t + 4_000), off, 0.45);
            check(rides.lastStopDiscarded(), "a 4 s jostle, then switched off with its verdict's gap: covered, not kept even with 450 m");
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 25_000; u += 1000) sample(rides, u, 15.0, 100.0 + (u - t) / 3.6e9 * 15_000);
            stop(rides, switchOff(rides, t + 26_000, 15.0, t + 25_000), off, null);
            check(!rides.lastStopDiscarded(), "a covered 25 s ride at 15 km/h, then switched off: kept");

            // F02: 8 s at 15 km/h, Bluetooth lost, back with battery readings but no speed frame, then the switch-off: the
            // reconnected gap hides what happened, so the ride isn't covered and its 450 m track keeps it.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 8_000; u += 1000) sample(rides, u, 15.0, 100.0);
            rides.markGap(WALL0 + t + 9_000, t + 9_000, "Bluetooth disconnected");
            for (long u = t + 10_000; u < t + 100_000; u += 1000) rides.heartbeat(WALL0 + u, u);
            for (long u = t + 100_000; u <= t + 101_000; u += 1000) sample(rides, u, null, null);
            rides.markGap(WALL0 + t + 102_000, t + 102_000, "Scooter switched off");
            rides.markGapSwitchedOff();
            for (long u = t + 103_000; u <= t + 112_000; u += 1000) rides.heartbeat(WALL0 + u, u);
            RideStore.Summary noSpeed = stop(rides, t + 112_000, off, 0.45);
            check(!rides.lastStopDiscarded() && noSpeed.shownElapsedMs < RideStore.MIN_KEEP_MS,
                    "a reconnect with no speed frame, then the switch-off, 450 m track: not covered, kept (shown " + noSpeed.shownElapsedMs + " ms)");
            // The same with nothing at all after the reconnect (no push before the off verdict): the Bluetooth gap is still open
            // at the stop, and it isn't the verdict's own, so it counts.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 8_000; u += 1000) sample(rides, u, 15.0, 100.0);
            rides.markGap(WALL0 + t + 9_000, t + 9_000, "Bluetooth disconnected");
            for (long u = t + 10_000; u <= t + 120_000; u += 1000) rides.heartbeat(WALL0 + u, u);
            stop(rides, t + 120_000, off, 0.45);
            check(!rides.lastStopDiscarded(), "Bluetooth lost, back with no push, then the off verdict, 450 m track: not covered, kept");
            // GapThenOff: 8 s at 15 km/h, the gap, back stopped (speed 0 for 30 s), then the switch-off: still kept.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 8_000; u += 1000) sample(rides, u, 15.0, 100.0);
            rides.markGap(WALL0 + t + 9_000, t + 9_000, "Bluetooth disconnected");
            for (long u = t + 10_000; u < t + 100_000; u += 1000) rides.heartbeat(WALL0 + u, u);
            for (long u = t + 100_000; u <= t + 130_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, switchOff(rides, t + 131_000, 0.0, t + 130_000), off, 0.45);
            check(!rides.lastStopDiscarded(), "GapThenOff: 8 s at 15 km/h, a gap, back stopped, switched off, 450 m track: kept");
            // A process restart mid-ride is a gap too, though the integrator now only counts its own skips in the window.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 8_000; u += 1000) sample(rides, u, 15.0, 100.0);
            rides = new RideStore(ridesDir);
            for (long u = t + 20_000; u <= t + 50_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, switchOff(rides, t + 51_000, 0.0, t + 50_000), off, 0.45);
            check(!rides.lastStopDiscarded(), "8 s at 15 km/h, a process restart, then still and switched off, 450 m track: kept");
            // Switched off and on again while parked: the verdict's gap reconnected, so it counts like any other (kept).
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 4_000; u += 1000) sample(rides, u, 3.0, 100.0);
            switchOff(rides, t + 5_000, 3.0, t + 4_000);
            for (long u = t + 30_000; u <= t + 630_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 630_000, RideStore.STOP_AUTO_NO_MOTION, null);
            check(!rides.lastStopDiscarded(), "switched off and on again, then the no-motion end: the gap reconnected, not covered, kept");

            // F04: a zero-speed push at 19 s, then battery readings only (the snapshots reuse it to 22 s), a hand stop at 25 s:
            // the reading was measured at 19 s, 6 s before the stop, so the ride isn't covered and its 25 s keep it.
            for (long stopAt : new long[]{25_000, 22_000}) {
                t += 2_000_000;
                rides.start(PROFILE, "", WALL0 + t, t);
                for (long u = t; u <= t + 19_000; u += 1000) sample(rides, u, 0.0, 100.0);
                for (long u = t + 20_000; u <= t + stopAt; u += 1000) tick(rides, u, 0.0, t + 19_000, 100.0);
                stop(rides, t + stopAt, "", null);
                check(rides.lastStopDiscarded() == (stopAt == 22_000), "speed last measured at 19 s, a hand stop at " + stopAt / 1000 + " s: "
                        + (stopAt == 22_000 ? "covered (3 s), 0 m, not kept" : "not covered (6 s), kept"));
            }

            // F05: the phone's track unknown (its last fixes never flushed) can't delete a ride on the track test.
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            sample(rides, t, 0.0, 100.0);
            stop(rides, t + 12_000, "", RideStore.PHONE_TRACK_UNKNOWN);
            check(!rides.lastStopDiscarded(), "not covered, 12 s, the phone's track unknown: kept");
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 30_000; u += 1000) sample(rides, u, 0.0, 100.0);
            stop(rides, t + 30_000, "", RideStore.PHONE_TRACK_UNKNOWN);
            check(!rides.lastStopDiscarded(), "covered, 30 s and 0 m, the phone's track unknown: kept");
            t += 2_000_000;
            rides.start(PROFILE, "", WALL0 + t, t);
            for (long u = t; u <= t + 6_000; u += 1000) sample(rides, u, 8.0, 100.0);
            stop(rides, t + 6_000, "", RideStore.PHONE_TRACK_UNKNOWN);
            check(rides.lastStopDiscarded(), "covered and 6 s shown, the phone's track unknown: still not kept (the duration test)");
        } finally { remove(dir); }
    }

    private static byte[] bytes(File f) throws IOException { return Files.readAllBytes(f.toPath()); }
    /** A short ride with phone files, discarded through RideStore.stop into the hold; returns its id. */
    private static String discardWithPhone(RideStore rides, RideHold hold, PhoneRideStore phone, long t) throws IOException {
        rides.start(PROFILE, "", WALL0 + t, t);
        String id = rides.activeId();
        phone.start(id, WALL0 + t, t); phone.appendBatch(id, "FIX,1,2\n", "1,2,3\n"); phone.stop(id, WALL0 + t + 6_000, t + 6_000);
        for (long u = t; u <= t + 5_000; u += 1000) sample(rides, u, 8.0, 100.0);
        stop(rides, t + 6_000, "", null);
        check(rides.lastStopDiscarded() && !rides.journal(id).exists() && hold.holds(id), "a 6 s ride is discarded into the hold, not deleted");
        // The service's discardPhoneRide, once the recorder is done.
        check(hold.add(id, phone.rawCsv(id), phone.motionCsv(id)) && !phone.rawCsv(id).exists() && !phone.motionCsv(id).exists(), "its phone files join it");
        return id;
    }

    /**
     * The owner's Undo (Nov 24 2026): a discarded ride is held a day (RideHold), its journal and phone files byte for byte,
     * outside the ride list, and Undo brings it back as if kept; a process death in between loses nothing; after the day it
     * is gone, and Undo then changes nothing; two discards back to back both stay restorable.
     */
    private static void heldForUndo() throws IOException {
        File dir = Files.createTempDirectory("volt-held").toFile();
        try {
            File ridesDir = new File(dir, "rides"), heldDir = new File(dir, "rides_held");
            RideStore rides = new RideStore(ridesDir);
            RideHold hold = new RideHold(heldDir);
            rides.holdDiscards(hold);
            PhoneRideStore phone = new PhoneRideStore(new File(dir, "phone-rides"));
            long t = 10_000;
            String id = discardWithPhone(rides, hold, phone, t);
            File heldEntry = new File(heldDir, id);
            byte[] journal = bytes(new File(heldEntry, id + ".csv")), raw = bytes(new File(heldEntry, id + "-phone.csv")), motion = bytes(new File(heldEntry, id + "-motion.csv"));
            check(rides.list().isEmpty() && rides.get(id) == null, "a held ride isn't in the ride list");
            // A process death: a new store and hold on the same folders still have it.
            rides = new RideStore(ridesDir); hold = new RideHold(heldDir); rides.holdDiscards(hold);
            check(hold.holds(id) && rides.list().isEmpty(), "held on disk: a process death keeps it, still out of the list");
            check(hold.purge(WALL0 + t + 6_000 + RideHold.KEEP_MS - 60_000).isEmpty(), "inside the day nothing is purged");
            check(rides.restoreHeld(id, phone.rawCsv(id), phone.motionCsv(id)), "Undo restores it");
            check(java.util.Arrays.equals(bytes(rides.journal(id)), journal) && java.util.Arrays.equals(bytes(phone.rawCsv(id)), raw)
                    && java.util.Arrays.equals(bytes(phone.motionCsv(id)), motion), "byte for byte: the journal and both phone files");
            check(!hold.holds(id) && !heldEntry.exists(), "nothing left in the hold");
            RideStore.Summary back = rides.get(id);
            check(back != null && rides.list().size() == 1 && rides.list().get(0).id.equals(id) && back.shownElapsedMs == 6_000, "the ride lists as a kept one, its summary as recorded");
            check(phone.load(id) != null, "its phone track reads again");
            check(!rides.restoreHeld(id, phone.rawCsv(id), phone.motionCsv(id)) && rides.list().size() == 1, "a second Undo changes nothing");
            // A reload agrees.
            RideStore reloaded = new RideStore(ridesDir);
            check(reloaded.list().size() == 1 && reloaded.get(id).shownElapsedMs == 6_000, "a reload lists it too");

            // Two discards back to back: both held, either restorable.
            t += 1_000_000;
            String first = discardWithPhone(rides, hold, phone, t);
            t += 100_000;
            String second = discardWithPhone(rides, hold, phone, t);
            check(hold.holds(first) && hold.holds(second), "two discards back to back: both held");
            check(rides.restoreHeld(first, phone.rawCsv(first), phone.motionCsv(first)) && hold.holds(second), "the older one restores, the newer stays held");
            check(rides.restoreHeld(second, phone.rawCsv(second), phone.motionCsv(second)) && rides.list().size() == 3, "and the newer one too");

            // After the day: purged, and Undo then changes nothing.
            t += 1_000_000;
            String old = discardWithPhone(rides, hold, phone, t);
            long heldAt = WALL0 + t + 6_000;
            check(hold.purge(heldAt + RideHold.KEEP_MS - 1_000).isEmpty() && hold.holds(old), "a second under the day: still held");
            check(hold.purge(heldAt + RideHold.KEEP_MS + 1_000).contains(old) && !hold.holds(old) && !new File(heldDir, old).exists(), "past the day: purged, files and all");
            check(!rides.restoreHeld(old, phone.rawCsv(old), phone.motionCsv(old)) && !rides.journal(old).exists() && !phone.rawCsv(old).exists()
                    && rides.list().size() == 3, "Undo after the purge does nothing (no file, no list change)");
            // A clock set back a day and more: the stamp is in the future, so it goes too.
            t += 1_000_000;
            String future = discardWithPhone(rides, hold, phone, t);
            check(hold.purge(WALL0 + t - RideHold.KEEP_MS - 60_000).contains(future), "a stamp more than a day ahead (clock set back) is purged");
            // A held ride whose place is taken is left held.
            t += 1_000_000;
            String taken = discardWithPhone(rides, hold, phone, t);
            Files.write(phone.rawCsv(taken).toPath(), new byte[]{1});
            check(!rides.restoreHeld(taken, phone.rawCsv(taken), phone.motionCsv(taken)) && hold.holds(taken) && !rides.journal(taken).exists(),
                    "a target already there: nothing moves, the ride stays held");
            // Without a hold (the store as tests and old callers use it) a discard still deletes at once.
            RideStore plain = new RideStore(new File(dir, "plain"));
            plain.start(PROFILE, "", WALL0, 0);
            String gone = plain.activeId();
            for (long u = 0; u <= 5_000; u += 1000) sample(plain, u, 8.0, 100.0);
            plain.stop(WALL0 + 6_000, 6_000, "", true);
            check(plain.lastStopDiscarded() && !plain.journal(gone).exists() && !plain.restoreHeld(gone), "no hold: deleted, nothing to restore");
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
            for (long u = t; u <= t + 2_000; u += 1000) sample(rides, u, 15.0, 100.0);
            // The phone: 60 fixes 5 m apart at 5 m/s, a 300 m track.
            phone.start(id, WALL0 + t, t);
            StringBuilder rows = new StringBuilder();
            double lat = 40.0;
            for (int i = 1; i <= 60; i++) { lat += 5 / 111_320.0; rows.append(PhoneRideStore.row("GPS", WALL0 + t + i * 1000L, t + i * 1000L, lat, -105.0, 5.0, 5.0, 0.5, null, null)); }
            phone.appendBatch(id, rows.toString(), "");
            // The process dies; a new store recovers the ride, and it is stopped 15 s in with no reading since 2 s.
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
        int k = src.indexOf("private Double phoneSavedKm(String id){");
        String phoneKm = src.substring(k, src.indexOf("\n    }", k));
        check(phoneKm.indexOf("phoneRecorder.flushAndWait(") > 0 && phoneKm.indexOf("phoneRecorder.flushAndWait(") < phoneKm.indexOf("phoneRides.load(id)"),
                "the recorder's last fixes are flushed before the track is read (round 4 F06)");
        check(src.contains("rideCharge.restoreWait(settings.getString(RideChargeRule.WAIT_KEY,null),bootCount(),SystemClock.elapsedRealtime());")
                && src.contains("rideCharge.observe(activeProfileId(),live,mono);keepChargeWait(live);")
                && src.contains("rideCharge.observe(activeProfileId(),liveCharging,mono);keepChargeWait(liveCharging);"),
                "the charging wait is saved where it is observed and restored when a service starts (round 4 F05)");
        check(src.contains("finishPhoneDiscards();") && stop.contains("id->pendingPhoneDiscard(id,true)") && stop.contains("if(!discarded)pendingPhoneDiscard(stopping,false);"),
                "pending phone-file deletes are noted before the journal goes and outlive a process death (F11, round 2 F06)");
        check(ends.indexOf("rideCharge.stopTried(id);") > 0 && ends.indexOf("rideCharge.stopTried(id);") < ends.indexOf("stopRide(RideStore.STOP_CHARGING);"),
                "a charge-ended stop is marked tried before it runs, so a failed one isn't retried in a loop (round 2 F07)");
        check(auto.indexOf("autoStart.observeSpeed(linkUp,ble.speedRaw(),ble.motionSampleMs(),mono);") > auto.indexOf("autoStart.observeLink("),
                "riding off after Volt's own pause ends the holds (round 2 F01)");
        check(src.contains("stopRide(RideStore.STOP_CHARGING)"), "the charge end saves with its own code");
        check(src.contains("private static final AutoStartPolicy AUTO_START=new AutoStartPolicy();"), "one hold for the process, so a new service keeps it");
        check(stop.contains("if(discarded)offerUndo(stopping);") && stop.indexOf("offerUndo(") == stop.lastIndexOf("offerUndo(")
                && stop.indexOf("purgeHeldRides();") > stop.indexOf("offerUndo(stopping);") && src.contains("finishPhoneDiscards();purgeHeldRides();"),
                "a discarded ride is offered back once from the one stop path; held rides past the day go after each stop and at start");
        int u = src.indexOf("private void offerUndo(String id){");
        String offer = src.substring(u, src.indexOf("\n    }", u));
        check(offer.contains("undoOfferedAt=visible?SystemClock.elapsedRealtime():-1;") && offer.contains("if(visible)return;")
                && offer.contains("LiveNotices.channel(true,false,channelAllowed(LiveNotices.QUIET_CHANNEL))") && offer.contains(".setTimeoutAfter(UNDO_NOTICE_MS)")
                && offer.contains("post(UNDO_NOTICE,") && offer.contains("cancelNotice(UNDO_NOTICE);")
                && !offer.contains("Vibrat") && !offer.contains("setSound") && !offer.contains("Toast"),
                "on screen the bar, out of sight one silent notice on the quiet channel (Low where it is off) that times out; a newer one replaces it");
        check(src.contains("static final long UNDO_NOTICE_MS=10*60_000L;") && RideHold.KEEP_MS == 24L * 3_600_000, "the notice goes after 10 minutes, the ride after a day");
        int d = src.indexOf("private void discardPhoneRide(String id){");
        String phoneGo = src.substring(d, src.indexOf("\n    }", d));
        check(phoneGo.contains("undoneRides.remove(id))return;") && phoneGo.indexOf("rideHold.add(id,") < phoneGo.indexOf("phoneRides.delete(id)"),
                "a discarded ride's phone files join it in holding, and an Undo that came first leaves them in place");
        check(src.contains("rideHold=new RideHold(new File(getCacheDir(),\"rides_held\"));rides.holdDiscards(rideHold);"),
                "held rides live in the app's cache, outside every backup and export");
        check(phoneKm.contains("phoneRecorder.active()&&!phoneRecorder.flushAndWait(PHONE_FLUSH_WAIT_MS)&&!phoneRecorder.flushAndWait(PHONE_FLUSH_WAIT_MS))return RideStore.PHONE_TRACK_UNKNOWN;"),
                "both flush waits timed out: the track is unknown and keeps the ride (round 7 F05)");
        int g = src.indexOf("rides.markGap(wall, mono, ble.scooterOff() ? \"Scooter switched off\" : \"Bluetooth disconnected\");");
        check(g > 0 && src.indexOf("if (ble.scooterOff()) rides.markGapSwitchedOff();", g) > g
                && src.indexOf("if (ble.scooterOff()) rides.markGapSwitchedOff();", g) < src.indexOf("gapMarked = true;", g),
                "the off verdict's own gap is marked as the end cause's, right after it (round 7 F02)");
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
