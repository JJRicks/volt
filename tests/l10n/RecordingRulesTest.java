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
        replugNeverRestartsARide();
        riderStartWhileCharging();
        noLoopWithTheReverseRule();
        chargingEndCode();
        keepRule();
        shortRidesAreNotKept();
        keptRidesStay();
        discardLeavesNoSummary();
        serviceWiring();
        System.out.println("RecordingRulesTest: " + checks + " checks passed");
    }

    /** Hand stop at 16:10, a stray automatic ride from 16:11:30, plugged in at 16:11:54: the ride ends on that reading. */
    private static void chargeEndsTheRide() {
        RideChargeRule r = new RideChargeRule();
        long t = 1_000_000;
        r.observe(Boolean.FALSE, t);
        check(!r.endsRide("ride", Boolean.FALSE, false), "not charging: the ride runs on");
        check(!r.endsRide("ride", null, false), "a stale or missing charging reading never ends a ride");
        check(!r.endsRide(null, Boolean.TRUE, false), "nothing recording: nothing to end");
        r.observe(Boolean.TRUE, t + 24_000);
        check(r.endsRide("ride", Boolean.TRUE, false), "the first fresh charging reading ends the ride at once");
        check(r.endsRide("auto-ride", Boolean.TRUE, false), "an automatic ride the same");
        check(!r.endsRide("ride", Boolean.TRUE, true), "never while ridden faster than 3 mph: a charging flag mid-ride is not a charger");
        check(r.blocksAutoRecord(t + 24_000, false), "while charging, Record rides automatically starts nothing");
    }

    /** Unplugged and plugged back in (his log: 1, 0, 1 within 5 to 8 s, as he replugged): no ride starts on the 0, even with the scooter jostled. */
    private static void replugNeverRestartsARide() {
        RideChargeRule r = new RideChargeRule();
        long t = 2_000_000;
        r.observe(Boolean.TRUE, t);
        r.observe(Boolean.FALSE, t + 5000);
        check(r.blocksAutoRecord(t + 5000, false), "a 0 five seconds after charging: still no automatic ride");
        r.observe(Boolean.TRUE, t + 8000);
        r.observe(null, t + 20_000);
        check(r.blocksAutoRecord(t + 20_000, false), "a stale reading neither starts nor clears anything");
        check(r.blocksAutoRecord(t + 8000 + RideChargeRule.SETTLE_MS - 1, false), "within SETTLE_MS of the last charging reading: still waits");
        check(!r.blocksAutoRecord(t + 8000 + RideChargeRule.SETTLE_MS, false), "SETTLE_MS after it: auto-record may start again");
        check(!r.blocksAutoRecord(t + 9000, true), "unplugged and ridden off faster than 3 mph: starts at once");
        RideChargeRule never = new RideChargeRule();
        check(!never.blocksAutoRecord(5, false), "never charging: no wait");
    }

    /** A ride the rider starts by hand while charging is the rider's choice: charging doesn't end it. */
    private static void riderStartWhileCharging() {
        RideChargeRule r = new RideChargeRule();
        r.riderStarted("hand", Boolean.TRUE);
        check(!r.endsRide("hand", Boolean.TRUE, false), "started by hand while charging: runs on");
        r.riderStarted("later", Boolean.FALSE);
        check(r.endsRide("later", Boolean.TRUE, false), "started by hand before charging: the charge ends it");
        r.riderStarted(null, null);
        check(r.endsRide("hand", Boolean.TRUE, false), "the exemption belongs to that one ride");
    }

    /**
     * The reverse rule stays: a ride started by hand ends an AUTOMATIC charge recording. The two can't loop: that ride is
     * exempt from the charge rule, auto-record never starts while charging (so it never ends a charge recording), and a
     * charge ending a ride sets no hold, so the charge recording starts on its normal path.
     */
    private static void noLoopWithTheReverseRule() {
        check(ChargeAutoRecordPolicy.rideEndsRecording(true) && !ChargeAutoRecordPolicy.rideEndsRecording(false), "reverse rule unchanged");
        RideChargeRule r = new RideChargeRule();
        long t = 3_000_000;
        // Ride ended by the charge; the charge recording starts (nothing holds it); auto-record waits throughout.
        r.observe(Boolean.TRUE, t);
        boolean ended = r.endsRide("ride", Boolean.TRUE, false);
        boolean chargeStarts = ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, false, false, false);
        check(ended && chargeStarts, "the ride ends, and Record charges automatically starts on its own path");
        int rideStarts = 0;
        for (long now = t; now < t + 10 * 60_000; now += 1000) {
            Boolean live = (now / 1000) % 97 == 0 ? Boolean.FALSE : Boolean.TRUE; // a replug now and then
            r.observe(live, now);
            if (!r.blocksAutoRecord(now, false)) rideStarts++;
        }
        check(rideStarts == 0, "ten minutes on the charger with replugs: no automatic ride ever starts, so nothing ends the charge recording");
        // The rider starts a ride by hand: the automatic charge recording ends (and is held), the ride runs on.
        r.riderStarted("hand", Boolean.TRUE);
        check(!r.endsRide("hand", Boolean.TRUE, false), "and the charge never ends that ride back");
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

    private static RideStore.Summary summary(RideStore rides, long seconds, Double startOdo, Double endOdo, Double kmh) throws IOException {
        long now = 10_000;
        rides.start(PROFILE, "", WALL0 + now, now);
        long end = now + seconds * 1000;
        for (; now <= end; now += 1000) {
            Double odo = startOdo == null ? null : startOdo + (endOdo - startOdo) * (now - 10_000) / Math.max(1, end - 10_000);
            sample(rides, now, kmh, odo);
        }
        return rides.stop(WALL0 + end, end, "", false);
    }

    private static void keepRule() throws IOException {
        File dir = Files.createTempDirectory("volt-keep-rule").toFile();
        try {
            RideStore rides = new RideStore(dir);
            check(Math.abs(RideStore.MIN_KEEP_KM - 0.01609344) < 1e-12 && RideStore.MIN_KEEP_MS == 20_000, "0.01 mi and 20 s");
            check(!RideStore.kept(summary(rides, 19, 100.0, 101.0, 20.0)), "19 s, a long way: not kept (duration)");
            check(RideStore.kept(summary(rides, 20, 100.0, 100.1, 20.0)), "20 s and 0.1 km: kept");
            // Rolled indoors: 24 s at walking pace on the odometer's tenths, the odometer never ticks.
            check(!RideStore.kept(summary(rides, 24, 100.0, 100.0, 0.0)), "24 s, nothing on the odometer or the speed: not kept (distance)");
            check(!RideStore.kept(summary(rides, 60, 100.0, 100.0, 0.9)), "a minute shuffled at 0.9 km/h is 15 m: not kept");
            check(RideStore.kept(summary(rides, 60, 100.0, 100.0, 6.0)), "the odometer's tenths say 0 but 60 s at 6 km/h is 100 m: kept");
            check(RideStore.kept(summary(rides, 60, null, null, null)), "no distance known: the duration decides alone");
            check(!RideStore.kept(summary(rides, 10, null, null, null)), "no distance known and 10 s: not kept");
            check(RideStore.kept(summary(rides, 3600, 100.0, 120.0, 20.0)), "an ordinary ride: kept");
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
        check(stop.contains("rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime(),reason,true)"), "every stop, the rider's and the automatic ones, discards a short ride");
        check(stop.contains("if(!discarded)RideAutoExportFolder.queue(settings,stopping);"), "a discarded ride is never copied out (ride auto-export)");
        check(stop.contains("phoneDiscard=discarded?stopping:null;stopPhoneRecording();"), "its phone GPS files go once saved");
        int changed = src.indexOf("private void changed(){");
        String pass = src.substring(changed, src.indexOf("\n", changed));
        check(pass.indexOf("observeChargeEndsRide(") > 0 && pass.indexOf("observeChargeEndsRide(") < pass.indexOf("observeAutoCharge(")
                && pass.indexOf("observeAutoCharge(") < pass.indexOf("observeAutoStart("), "a charge ends the ride before Record charges automatically and auto-record look");
        int o = src.indexOf("private void observeAutoStart(long mono){");
        String auto = src.substring(o, src.indexOf("\n    }", o));
        check(auto.contains("rideCharge.blocksAutoRecord(") && auto.indexOf("rideCharge.blocksAutoRecord(") < auto.indexOf("beginRide("), "auto-record waits while charging");
        check(auto.contains("ble.scooterOff(),ble.intentionalDisconnectSequence()"), "the holds see the switch-off and the rider's Disconnect");
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
