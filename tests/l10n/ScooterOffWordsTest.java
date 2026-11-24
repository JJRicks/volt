package com.jjricks.volt;

/**
 * 2.0.5 item 5: the words for a scooter switched off but still answering, now in the catalog (ScooterOffWords), and the
 * refusal they bring: every change but the charging settings says "Turn the scooter on first" (a decision marked
 * switchedOff, whose reason stays NO_MOTION_REPORT so no Reason or pinned reason English changes), the UT5 MAX
 * says it is waiting for the scooter's speed before the first motion report, and a moving scooter keeps "Bring the
 * scooter to a stop first" (BleController, unchanged). English only; synthetic scooters.
 */
public final class ScooterOffWordsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void same(String expected, Object actual, String what) {
        check(expected.equals(String.valueOf(actual)), what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    public static void main(String[] args) {
        words();
        refusals();
        chargingSuspendsTheOffWords();
        offAndPluggedInKeepsTheChargingSettings();
        unlockStaysTheRecoveryPath();
        ridingPill();
        recordingPillSaysOff();
        detailsFollowTheState();
        notificationTitle();
        guide();
        System.out.println("ScooterOffWordsTest: " + checks + " checks passed");
    }

    private static void words() {
        same("Scooter off · Turn the scooter on to use it", ScooterOffWords.status(), "Home's status line");
        same("Scooter off", ScooterOffWords.statusShort(), "its short rung");
        same("Scooter off. Turn the scooter on to use it. Connection details", ScooterOffWords.spoken(), "spoken");
        same("Scooter off", ScooterOffWords.state(), "the connection state word");
        same("Turn the scooter on to use it", ScooterOffWords.detail(), "the line under it");
        same("Waiting for the scooter to report its speed", ScooterOffWords.waitingForSpeed(), "before the first motion report");
        same("Turn the scooter on first", ScooterOffWords.refusal(), "the refusal");
        same("Turn the scooter on first", ScooterOffWords.refusalMessage(), "the refusal, deferred");
        same("No motion report yet", CapabilityResolver.text(CapabilityResolver.Reason.NO_MOTION_REPORT), "the reason's own words are unchanged");
    }

    private static void refusals() {
        check(CapabilityResolver.OFF_REASON == CapabilityResolver.Reason.NO_MOTION_REPORT, "Switched off refuses with no motion report, in its own words");
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        StatusFields s = CapabilityResolverTest.status(CapabilityResolverTest.full(), 100000);
        CapabilityResolver.Runtime rt = CapabilityResolverTest.parked(); rt.stationary = false; rt.motionSeen = false; rt.scooterOff = true;
        for (String key : new String[]{"cruise_control", "headlight", "range_algorithm", "maximum_speed"}) {
            CapabilityResolver.Decision d = CapabilityResolver.decide(key, ut5, s, rt);
            check(d.reason == CapabilityResolver.OFF_REASON && d.switchedOff && d.blockedByRide() && !d.parkedOnly(), "Off refuses " + key + " and keeps it tappable");
            same("Turn the scooter on first", d.reasonText(), "the tap's words for " + key);
            same("Turn the scooter on first", d.reasonMessage(), "the deferred words for " + key);
            same("Turn the scooter on first", d.rowRefusal(), "the row's words for " + key);
            same("Turn the scooter on first", CapabilityResolver.noMotionNotice(d, true), "BleController's notice for " + key + " (UT5 MAX)");
            same("Turn the scooter on first", CapabilityResolver.noMotionNotice(d, false), "BleController's notice for " + key + " (another model)");
        }
        CapabilityResolver.Decision lock = CapabilityResolver.admit("lock", 1, ut5, s, rt);
        check(lock.switchedOff && LockTapQueue.gate(lock.available(), lock.reason, lock.switchedOff, false) == LockTapQueue.Gate.REFUSE,
                "Locking is refused while off, and a lock tap goes back at once with the words, never waits");
        same("Turn the scooter on first", lock.reasonText(), "the lock's words");
        check(CapabilityResolver.decide("charge_limit", ut5, s, rt).available(), "The charge limit stays usable while off");
        // Before the first motion report (not off): the UT5 MAX waits for the scooter's speed; other models keep theirs.
        rt.scooterOff = false;
        CapabilityResolver.Decision none = CapabilityResolver.decide("cruise_control", ut5, s, rt);
        same("Waiting for the scooter to report its speed", CapabilityResolver.noMotionNotice(none, true), "UT5 MAX, no motion report yet");
        same("No motion report yet", CapabilityResolver.noMotionNotice(none, false), "another model, no motion report yet");
        same("Bring the scooter to a stop first", M.bleStopScooterFirst(), "moving keeps its words");
    }

    /**
     * Council F02 on #624: switched off, then plugged in (the usual way to charge). A charging reading suspends the off
     * words, so "Scooter off" never sits over the Charging view; the verdict itself stays off until a push, and the logic
     * (the charging settings' waiver, the ride's auto-end) keeps reading the verdict (council round 2 F05, F04 on #628).
     */
    private static void chargingSuspendsTheOffWords() {
        ScooterPowerState p = new ScooterPowerState(); p.start(1000);
        ScooterPowerState.Result off = p.verdict(20_000, false, true, true, true, false);
        check(off.off() && ScooterPowerState.shownOff(off, false), "Off and not charging: shown off");
        ScooterPowerState.Result plugged = p.verdict(30_000, false, true, true, true, true);
        check(plugged.off() && !ScooterPowerState.shownOff(plugged, true), "Plugged in afterwards: the verdict stays off, but nothing says Scooter off");
        check(ScooterPowerState.shownOff(p.verdict(40_000, false, true, true, true, false), false), "Unplugged again with no push: off again");
        check(!p.shownOff(p.verdict(50_000, false, true, true, true, true)) && p.shownOff(p.verdict(60_000, false, true, true, true, false)), "The verdict's own charging reading decides");
        String ble = src("BleController");
        check(ble.contains("public boolean scooterOff(){return powerVerdict().off();}"), "BleController.scooterOff is the raw verdict, for logic");
        check(ble.contains("public boolean scooterOffShown(){ScooterPowerState.Result verdict=powerVerdict();return power.shownOff(verdict);}"), "scooterOffShown follows the charging reading, for words");
    }

    /**
     * Council round 2 F05 on #628 (timeline T3): switched off, then plugged in. The resolver still sees the scooter off
     * (rt.scooterOff, the raw verdict), so the charging settings keep their waiver and go out with no motion report; the
     * other rows stay refused, in the plain no-motion words while the off words are suspended (rt.offWordsSuspended).
     */
    private static void offAndPluggedInKeepsTheChargingSettings() {
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        StatusFields s = CapabilityResolverTest.status(CapabilityResolverTest.full(), 100000);
        CapabilityResolver.Runtime rt = CapabilityResolverTest.parked(); rt.stationary = false; rt.motionSeen = false; rt.scooterOff = true; rt.offWordsSuspended = true;
        for (String key : new String[]{"charge_limit", "timed_charging"}) check(CapabilityResolver.decide(key, ut5, s, rt).available(), "Off and plugged in: " + key + " sends");
        check(MutationGate.check(new MutationGate.Request(MutationGate.Kind.CHARGE_START, "charge_start", 0, MutationGate.Origin.USER), ut5, s, rt).available(), "and so does the scheduled charge start");
        CapabilityResolver.Decision cruise = CapabilityResolver.decide("cruise_control", ut5, s, rt);
        check(cruise.reason == CapabilityResolver.OFF_REASON && !cruise.switchedOff, "Other rows stay refused, without the off words");
        same("Waiting for the scooter to report its speed", CapabilityResolver.noMotionNotice(cruise, true), "their notice while charging");
        rt.offWordsSuspended = false;
        check(CapabilityResolver.decide("cruise_control", ut5, s, rt).switchedOff, "Unplugged: the off words are back");
        CapabilityResolver.Runtime fresh = CapabilityResolverTest.parked();
        check(!fresh.offWordsSuspended, "Not suspended by default");
        String ble = src("BleController");
        check(ble.contains("rt.scooterOff=scooterOff();rt.offWordsSuspended=rt.scooterOff&&!scooterOffShown();"), "BleController.runtime gives the resolver the raw verdict, and the words' suspension apart");
    }

    /**
     * Council F03 on #624: Unlock is never refused while off, deliberately: it is the owner's recovery path (an owner can
     * always unlock), and a switched-off scooter that answers takes it. Every other lock path refuses with the off words.
     */
    private static void unlockStaysTheRecoveryPath() {
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        StatusFields s = CapabilityResolverTest.status(CapabilityResolverTest.full(), 100000);
        CapabilityResolver.Runtime rt = CapabilityResolverTest.parked(); rt.scooterOff = true;
        check(CapabilityResolver.admit("lock", 0, ut5, s, rt).available(), "Unlock while off: the recovery path, never refused");
        check(src("CapabilityResolver").contains("Deliberately not refused while the scooter is switched off"), "and the code says why");
    }

    /** Council F09 on #624: riding mode's status pill says Scooter off (orange, no green Connected) while Home does. */
    private static void ridingPill() {
        check(RidingFocus.showsOff(RidingFocus.Link.CONNECTED, true), "Ready but switched off: the pill shows off");
        check(!RidingFocus.showsOff(RidingFocus.Link.CONNECTED, false) && !RidingFocus.showsOff(RidingFocus.Link.RECONNECTING, true), "Otherwise as before");
        String riding = src("RidingScreen");
        check(riding.contains("boolean off = RidingFocus.showsOff(link, ble != null && ble.scooterOffShown());")
                && riding.contains("ui.statusRungs(status, ScooterOffWords.state(), \"\");") && riding.contains("statusPill.setContentDescription(ScooterOffWords.state());"),
                "RidingScreen.showStatus uses it, words (the orange dot alone where they don't fit: round 2 F11) and spoken state");
    }

    /**
     * Council round 2 F12 on #628 (timeline T6): while a ride records, the red recording pill replaces the connection
     * words, so it carries the switch-off itself: the problem look (no healthy pulse), the time alternating with "Scooter
     * off" (held on the words with animations off), and TalkBack hears both.
     */
    private static void recordingPillSaysOff() {
        long rec = 768_900L;
        check(RecordingPill.look(rec, RidingFocus.Link.CONNECTED, null, false, true) == RecordingPill.Look.RECORDING_PROBLEM, "Recording, switched off: the problem look");
        check(RecordingPill.look(rec, RidingFocus.Link.CONNECTED, null, false, false) == RecordingPill.Look.RECORDING, "Recording, on: as before");
        check(RecordingPill.look(null, RidingFocus.Link.CONNECTED, null, false, true) == RecordingPill.Look.STATUS, "Not recording: the status pill says it");
        check(!RecordingPill.pulses(RecordingPill.Look.RECORDING_PROBLEM, true), "No healthy pulse while off");
        String[] time = RecordingPill.offRungs(rec, 0, true), words = RecordingPill.offRungs(rec, RecordingPill.ALTERNATE_MS, true);
        check(time.length == 2 && time[0].equals(T.ridingStatusRecording("12:48")) && time[1].equals("12:48"), "First half: the time: " + String.join("|", time));
        check(words.length == 1 && words[0].equals(ScooterOffWords.state()), "Second half: Scooter off");
        String[] still = RecordingPill.offRungs(rec, 0, false);
        check(still.length == 1 && still[0].equals(ScooterOffWords.state()), "Animations off: held on the words");
        same(T.ridingReadingSpoken(RecordingPill.spoken(rec, RidingFocus.Link.CONNECTED, null), ScooterOffWords.state()), RecordingPill.offSpoken(rec), "spoken: the time, then Scooter off");
        String riding = src("RidingScreen");
        check(riding.contains("RecordingPill.Look look = RecordingPill.look(recordingMs, link, savedAgoMs, savedAutomatic, off);"), "RidingScreen passes the off state");
        check(riding.contains("if (off) {\n                ui.statusRungs(status, RecordingPill.offRungs(recordingMs, now, Ui.motionEnabled()));\n                statusPill.setContentDescription(RecordingPill.offSpoken(recordingMs));"),
                "and the recording branch shows and speaks it");
    }

    /** Council F05 on #624: the connection details, built once, are rebuilt when the off state changes while open. */
    private static void detailsFollowTheState() {
        String main = src("MainActivity");
        check(main.contains("private void refreshConnectionDetails(){") && main.contains("maybeLaunchSave();refreshConnectionDetails();"),
                "the screen's 1 s pulse looks at the open details");
        check(main.contains("connectionDetails=controlDialog;connectionDetailsOff=ready&&ble.scooterOffShown();"), "the details remember what they showed");
        int raw = 0;
        for (String screen : new String[]{"MainActivity", "DashboardScreen", "RidingScreen"}) raw += src(screen).split("ble\\.scooterOff\\(\\)", -1).length - 1;
        check(raw == 0, "Every screen's words read scooterOffShown, never the raw verdict: " + raw);
    }

    /** Council round 2 F14 on #628: the ongoing connection notification says Scooter off while Home does. */
    private static void notificationTitle() {
        check(src("ScooterService").contains(": ble.ready() && ble.scooterOffShown() ? ScooterOffWords.state()\n                                : ble.linked() ? T.serviceNoticeConnected() : \"Volt\";"),
                "the notification's title while connected and switched off");
    }

    /** Council round 2 F01 on #628: the guide lists only what is refused; starting a ride is not. */
    private static void guide() {
        String guide;
        try { guide = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("docs/user-guide.md")), java.nio.charset.StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        int a = guide.indexOf("- **Scooter off.**"); check(a >= 0, "the guide's Scooter off entry");
        String entry = guide.substring(a, guide.indexOf('\n', a));
        check(entry.contains("Until the scooter is switched on, locking, lights,") && !entry.contains("starting a ride"), "starting a ride is not refused while off");
    }

    private static String src(String name) {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/" + name + ".java")), java.nio.charset.StandardCharsets.UTF_8).replace("\r", ""); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
