package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #623 round 2: a ride Volt ended because its scooter was switched off (ScooterPowerState, answering but silent) is
 * recorded as such and says so: "Scooter switched off", never "Bluetooth lost while parked", and its shown end is its
 * last movement, not the answered polls' readings that carried on after the switch-off. Synthetic data only.
 */
public final class ScooterOffRideEndTest {
    private static final String PROFILE = "profile-1", SERIAL = "X2538SYNTHETIC0001";
    private static final long WALL0 = 1800000000000L, T0 = 1_000_000;
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            policyRemembersTheSwitchOff();
            stopReason();
            shownEndIsTheLastMovement();
            words();
            serviceWiring();
            startedWhileOff();
            noMovementNoMovementWords();
            pushlessReconnectKeepsTheSwitchOff();
            offThenPluggedInEndsSwitchedOff();
            withTheReconnectHold();
        } finally { T.install(null); }
        System.out.println("ScooterOffRideEndTest: " + checks + " checks passed");
    }

    /** One push at {@code at}, seen at once, on transport 1. */
    private static void push(RideAutoStopPolicy p, long at, int speed, int driving) { p.connected(at, PROFILE, SERIAL, 1, at, at, speed, driving); }

    private static void policyRemembersTheSwitchOff() {
        RideAutoStopPolicy p = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        push(p, T0, 150, 1); push(p, T0 + 1000, 0, 0); push(p, T0 + 2000, 0, 0); push(p, T0 + 5000, 0, 0);
        long last = T0 + 5000;
        check(!p.switchedOffCountdown(), "Nothing running: not a switch-off");
        p.scooterOff(last + 10_500, last, last + 10_000);
        check(p.armed() && p.switchedOffCountdown(), "The off verdict's countdown is a switch-off");
        p.disconnected(last + 20_000, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(p.switchedOffCountdown(), "A drop afterwards keeps it");
        check(p.due(last + 30_000) || p.dueQuiet(last + 400_000), "It comes due");
        check(p.switchedOffCountdown(), "Still known when the tick ends the ride");
        p = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        push(p, T0, 150, 1); push(p, T0 + 1000, 30, 1);
        p.disconnected(T0 + 3000, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(p.armed() && !p.switchedOffCountdown(), "A plain drop is not a switch-off");
        p = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        push(p, T0, 150, 1);
        p.scooterOff(T0 + 10_500, T0, T0 + 10_000);
        push(p, T0 + 12_000, 0, 0);
        check(!p.armed() && !p.switchedOffCountdown(), "Switched on again: its first push clears it");
        p.scooterOff(T0 + 30_000, T0 + 12_000, T0 + 30_000); p.reset();
        check(!p.switchedOffCountdown(), "A reset clears it");
    }

    private static void stopReason() {
        String quiet = RideStore.quietStopReason(8, false);
        String off = RideStore.switchedOffStopReason(quiet);
        check(RideStore.isQuietStop(off) && RideStore.isSwitchedOffStop(off) && !RideStore.isSwitchedOffStop(quiet), "A went-quiet stop, marked switched off: " + off);
        check(RideStore.isQuietStop(RideStore.switchedOffStopReason(RideStore.STOP_AUTO_PARKED)), "The parked rule's end becomes one too");
        check(OffWordsCheck.pauses(off), "Still a went-quiet stop for everything that reads that family");
    }

    /** A tiny stand-in that reads the stop family the way RideStore's own readers do. */
    private static final class OffWordsCheck { static boolean pauses(String reason) { return RideStore.isQuietStop(reason) && !reason.contains("phone_bt=off"); } }

    private static void shownEndIsTheLastMovement() throws IOException {
        File dir = Files.createTempDirectory("volt-off-ride-end").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            String id = rides.start(PROFILE, "Off at the rack", WALL0 + now, now);
            long lastMotion = 0;
            // Riding for a minute, then parked and switched off: the polls keep answering battery readings, no speed.
            for (; now < 70_000; now += 1000) { sample(rides, now, 15.0, now); lastMotion = now; }
            for (; now < 150_000; now += 1000) sample(rides, now, null, -1);
            RideStore.Summary s = rides.stop(WALL0 + now, now, RideStore.switchedOffStopReason(RideStore.quietStopReason(-1, false)));
            check(s.automaticStop && s.stopSwitchedOff, "Saved as an automatic switch-off stop");
            check(s.shownEndWallMs == WALL0 + lastMotion, "Shown end is the last movement, not the last answered reading: " + (s.shownEndWallMs - WALL0 - lastMotion));
            RideStore.Summary again = rides.get(id);
            check(again.stopSwitchedOff && again.shownEndWallMs == s.shownEndWallMs, "Read back the same");
            String plainId = rides.start(PROFILE, "Plain", WALL0 + now, now);
            for (long end = now + 30_000; now < end; now += 1000) sample(rides, now, 15.0, now);
            long lastReading = now - 1000;
            RideStore.Summary plain = rides.stop(WALL0 + now + 60_000, now + 60_000, RideStore.quietStopReason(8, false));
            check(!plain.stopSwitchedOff && plain.shownEndWallMs == WALL0 + lastReading && plainId.equals(plain.id), "A plain went-quiet stop is unchanged");
            check("Saved automatically".equals(RideAutoStopText.pill(plain)), "and its pill keeps the plain words");
        } finally { remove(dir); }
    }
    private static void sample(RideStore rides, long now, Double kmh, long motion) throws IOException {
        Map<String, RideStore.Sample> v = new LinkedHashMap<>();
        if (kmh != null) v.put("speed_kmh", new RideStore.Sample(kmh, motion));
        v.put("voltage_v", new RideStore.Sample(50, now)); v.put("current_a", new RideStore.Sample(0.1, now)); v.put("battery_percent", new RideStore.Sample(60, now));
        rides.sample(WALL0 + now, now, v);
    }

    private static void words() throws IOException {
        File dir = Files.createTempDirectory("volt-off-ride-words").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            rides.start(PROFILE, "Evening loop", WALL0 + now, now);
            for (; now < 40_000; now += 1000) sample(rides, now, 15.0, now);
            String reason = RideStore.switchedOffStopReason(RideStore.STOP_AUTO_PARKED);
            RideStore.Summary s = rides.stop(WALL0 + now + 20_000, now + 20_000, reason);
            check("Evening loop · Scooter switched off. Ended at 4:12 PM, its last movement.".equals(RideAutoStopText.notice(reason, s, "4:12 PM")),
                    "named ride: " + RideAutoStopText.notice(reason, s, "4:12 PM"));
            check("Scooter switched off".equals(RideAutoStopText.notice(reason, null, null)), "unreadable ride: the reason alone");
            check("Saved automatically after the scooter was switched off".equals(RideAutoStopText.description(s)), "pill description");
            // Council round 2 F07 on #628: the visible pill says it too, not only TalkBack.
            check("Saved automatically · Scooter switched off".equals(RideAutoStopText.pill(s)), "visible pill: " + RideAutoStopText.pill(s));
            check(!RideAutoStopText.notice(reason, s, "4:12 PM").contains("Bluetooth"), "never Bluetooth lost while parked");
            String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidesScreen.java")), StandardCharsets.UTF_8).replace("\r", "");
            check(screen.contains("TextView automatic = pill(RideAutoStopText.pill(s), ui.blue);"), "RidesScreen shows those words");
            rides.start(PROFILE, "", WALL0 + now + 30_000, now + 30_000);
            for (now += 30_000; now < 90_000; now += 1000) sample(rides, now, 15.0, now);
            RideStore.Summary u = rides.stop(WALL0 + now, now, reason);
            check("Scooter switched off. Ended at 16:12, its last movement.".equals(RideAutoStopText.notice(reason, u, "16:12")), "unnamed ride");
            check("Bluetooth lost while parked".equals(RideAutoStopText.notice(RideStore.STOP_AUTO_PARKED, null, null))
                    && "Scooter turned off or out of range".equals(RideAutoStopText.notice(RideStore.quietStopReason(-1, false), null, null)), "the other notices are unchanged");
        } finally { remove(dir); }
    }

    /** The service, read from source: an automatic end whose countdown came from the switch-off is saved as one. */
    private static void serviceWiring() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        int a = src.indexOf("private void autoStopRide(RideAutoStopPolicy policy,String reason){"); check(a >= 0, "autoStopRide found");
        String auto = src.substring(a, src.indexOf("\n    }", a));
        check(auto.contains("if(policy.switchedOffCountdown())reason=RideStore.switchedOffStopReason(reason);")
                && auto.indexOf("switchedOffStopReason") < auto.indexOf("if(stopRide(reason)){"), "the switch-off marks the stop before it is saved");
    }

    /** Council F06 on #624: a ride started while the scooter is already off (a fresh policy) still gets the marker. */
    private static void startedWhileOff() {
        RideAutoStopPolicy p = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        p.scooterOff(T0, 0, T0);
        check(p.armed() && p.switchedOffCountdown(), "The first observation already off: a switch-off countdown");
    }

    /** Council F07 on #624: with no movement in the ride, no "its last movement" wording. */
    private static void noMovementNoMovementWords() throws IOException {
        File dir = Files.createTempDirectory("volt-off-ride-still").toFile();
        try {
            RideStore rides = new RideStore(dir);
            long now = 10_000;
            rides.start(PROFILE, "Never moved", WALL0 + now, now);
            for (; now < 60_000; now += 1000) sample(rides, now, null, -1);
            String reason = RideStore.switchedOffStopReason(RideStore.quietStopReason(-1, false));
            RideStore.Summary s = rides.stop(WALL0 + now, now, reason);
            check(s.stopSwitchedOff && !s.stopEndsAtMotion, "Switched off, no movement recorded");
            check("Never moved · Scooter switched off".equals(RideAutoStopText.notice(reason, s, "4:12 PM")), "the plain words, named: " + RideAutoStopText.notice(reason, s, "4:12 PM"));
            check(!RideAutoStopText.notice(reason, s, "4:12 PM").contains("movement"), "never 'its last movement'");
        } finally { remove(dir); }
    }

    private static void remove(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) remove(k); f.delete(); }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }

    /**
     * Council round 2 F06 on #628 (timeline T4): off verdict, the link drops, Volt reconnects to the still-off scooter
     * (no push), and the link drops again before a new off verdict. Only a push proves it on, so the countdown that the
     * second drop starts is still a switch-off and the ride is saved as one. A reconnect that pushes clears it.
     */
    private static void pushlessReconnectKeepsTheSwitchOff() {
        RideAutoStopPolicy p = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        push(p, T0, 150, 1); push(p, T0 + 1000, 30, 1);
        long last = T0 + 1000;
        p.scooterOff(last + 10_500, last, last + 10_000);
        check(p.switchedOffCountdown(), "Off verdict on a live link");
        p.disconnected(last + 20_000, RideAutoStopPolicy.Loss.RECOVERABLE);
        p.observe(last + 30_000, RideAutoStopPolicy.Link.up(PROFILE, SERIAL, 2, last + 30_000, last, 0, 0, false, 0));
        check(p.switchedOffCountdown(), "A reconnect with no push keeps the switch-off");
        p.disconnected(last + 35_000, RideAutoStopPolicy.Loss.RECOVERABLE);
        check(p.armed() && p.switchedOffCountdown(), "The second drop's countdown is still a switch-off");
        boolean due = false;
        for (long t = last + 36_000; t < last + 400_000 && !due; t += 1000) due = p.dueQuiet(t);
        check(due && p.switchedOffCountdown(), "It comes due, and the ride is saved as switched off");
        RideAutoStopPolicy q = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        push(q, T0, 150, 1); push(q, T0 + 1000, 30, 1);
        q.scooterOff(last + 10_500, last, last + 10_000);
        q.disconnected(last + 20_000, RideAutoStopPolicy.Loss.RECOVERABLE);
        q.observe(last + 30_000, RideAutoStopPolicy.Link.up(PROFILE, SERIAL, 2, last + 30_000, last + 30_000, 30, 1, false, last + 30_000));
        check(!q.switchedOffCountdown(), "A reconnect that pushes: switched on, cleared");
    }

    /**
     * Council round 2 F04 on #628 (timeline T5): switched off at the end of a ride, then plugged in. The ride's auto-end
     * reads the raw verdict (ScooterService.autoStopLink, ble.scooterOff()), which stays off while charging, so the
     * countdown keeps running, the ride ends switched off on time, and the automatic charge recording, held back only
     * while a ride records, can start.
     */
    private static void offThenPluggedInEndsSwitchedOff() throws IOException {
        ScooterPowerState power = new ScooterPowerState(); power.start(T0);
        RideAutoStopPolicy p = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        long t = T0;
        for (; t < T0 + 30_000; t += 1000) { power.frame(0x92, t); push(p, t, t < T0 + 20_000 ? 150 : 0, t < T0 + 20_000 ? 1 : 0); }
        long last = t - 1000, plugged = last + 20_000, ended = -1;
        for (; t < last + 700_000 && ended < 0; t += 1000) {
            boolean charging = t >= plugged;
            ScooterPowerState.Result verdict = power.verdict(t, false, true, true, true, charging);
            RideAutoStopPolicy.Link link = RideAutoStopPolicy.Link.up(PROFILE, SERIAL, 1, t, last, 0, 0, verdict.off(), last);
            p.observe(t, link);
            if (charging) check(verdict.off() && !ScooterPowerState.shownOff(verdict, true) && p.armed() && RideAutoStopPolicy.mayEnd(link),
                    "Plugged in: words suspended, the countdown and the right to end kept at " + (t - last));
            if (p.due(t, link) == RideAutoStopPolicy.Stop.QUIET) ended = t;
        }
        check(ended > plugged && ended <= last + 120_000, "Ends on the switch-off's countdown, not rule 3's ten minutes: " + (ended - last));
        check(p.switchedOffCountdown(), "Saved as switched off");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, true, false, true, false, false, false), "While the ride records, charge recording waits (chargeCaptureBlocked)");
        check(ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, false, false, false), "Once it ends, automatic charge recording starts");
        String svc = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        check(svc.contains("ble.drivingStatus(),ble.scooterOff(),ble.awaitingPush(),ble.lastPushMs());") && svc.contains("if(isRecording()||phoneSaving())return M.serviceChargeFinishRide();"),
                "the service feeds the ride the raw verdict, and holds charge recording only while a ride records");
    }

    /**
     * The link as ScooterService.autoStopLink builds it after #627 merged: the raw off verdict, the first-push wait
     * (ScooterPowerState.awaitingPush) and the last push, from a real ScooterPowerState that BleController starts when the
     * link is ready and resets, with its last push, when the link closes. Polls every 2 s keep the status fresh.
     */
    private static final class Ride {
        final ScooterPowerState power = new ScooterPowerState();
        final RideAutoStopPolicy policy = new RideAutoStopPolicy("ride", PROFILE, SERIAL);
        long now = T0, lastFrame, lastMotion, lastPush, ended = -1; int transport = 1, speed; boolean up = true, powered, scooterOn = true;
        RideAutoStopPolicy.Stop stop = RideAutoStopPolicy.Stop.NONE;
        Ride() { power.start(now); powered = true; }
        RideAutoStopPolicy.Link link() {
            if (up && !powered) { power.start(now); powered = true; }
            if (!up && powered) { power.reset(); powered = false; lastPush = 0; }
            if (!up) return RideAutoStopPolicy.Link.down(RideAutoStopPolicy.Loss.RECOVERABLE);
            boolean fresh = lastFrame > 0 && now - lastFrame <= CapabilityResolver.STATUS_MAX_AGE_MS;
            ScooterPowerState.Result verdict = power.verdict(now, false, fresh, true, fresh, false);
            return RideAutoStopPolicy.Link.up(PROFILE, SERIAL, transport, lastFrame, lastMotion, speed, speed > 0 ? 1 : 0, verdict.off(), power.awaitingPush(verdict, true), lastPush);
        }
        /** One second: a push (scooter on) or an answered poll every 2 s (off), then the service's observe and due. */
        void tick() {
            if (ended >= 0) { now += 1000; return; }
            if (up && scooterOn) { lastFrame = lastMotion = lastPush = now; power.frame(0x92, now); }
            else if (up && now % 2000 == 0) lastFrame = now;
            RideAutoStopPolicy.Link l = link();
            policy.observe(now, l);
            RideAutoStopPolicy.Stop due = policy.due(now, l);
            if (due == RideAutoStopPolicy.Stop.PARKED || due == RideAutoStopPolicy.Stop.QUIET) { ended = now; stop = due; }
            now += 1000;
        }
        void until(long t) { while (now < t) tick(); }
        /** ScooterService.autoStopRide: the switch-off mark, then BleController.rideEndedAutomatically's hold. */
        boolean switchedOff() { return policy.switchedOffCountdown(); }
        boolean hold() {
            String reason = stop == RideAutoStopPolicy.Stop.PARKED ? RideStore.STOP_AUTO_PARKED : RideStore.quietStopReason(8, false);
            if (switchedOff()) reason = RideStore.switchedOffStopReason(reason);
            return OffReconnectHold.pauses(reason, true, policy.seenOffWhileLinked());
        }
    }

    /**
     * Main merge with #627: both PRs' rules at once. Switched off mid-ride, the link drops, Volt reconnects to the still-off
     * scooter (a first-push wait, not a reconnect), the link drops again, and the deadline comes: the ride ends switched
     * off, and the reconnect hold is set only when the off verdict was seen on a live link.
     */
    private static void withTheReconnectHold() {
        // A: the off verdict on the live link first (parked, switched off), then drop, pushless reconnect, drop.
        Ride a = new Ride();
        a.speed = 150; a.until(T0 + 60_000); a.speed = 0; a.until(T0 + 70_000);
        long last = a.lastPush;
        a.scooterOn = false; a.until(last + 15_000);
        check(a.policy.seenOffWhileLinked() && a.switchedOff() && a.policy.armed(), "A: off verdict on the live link: seen off, a switch-off countdown");
        a.up = false; a.until(last + 25_000);
        a.up = true; a.transport = 2; a.until(last + 31_000);
        check(a.link().awaitingPush && a.switchedOff() && a.policy.armed(), "A: the pushless reconnect is a first-push wait: the countdown and its mark stand");
        a.up = false; a.until(last + 400_000);
        check(a.ended > 0 && a.ended <= last + 60_000 + 1000, "A: ends on the switch-off's deadline: " + (a.ended - last));
        check(a.switchedOff() && a.hold(), "A: saved as switched off, and the hold is set (seen off on a live link)");
        // B: dropped at riding speed with no verdict, a pushless reconnect that drops again inside the grace: not off.
        Ride b = new Ride();
        b.speed = 250; b.until(T0 + 60_000);
        last = b.lastPush;
        b.scooterOn = false; b.up = false; b.until(last + 60_000);
        b.up = true; b.transport = 2; b.until(last + 66_000);
        check(b.link().awaitingPush && !b.policy.seenOffWhileLinked(), "B: answered inside the grace: awaiting, never seen off");
        b.up = false; b.until(last + 400_000);
        check(b.ended > 0 && b.ended <= last + 300_000 + 1000, "B: ends on the drop's five minutes: " + (b.ended - last));
        check(!b.switchedOff() && !b.hold(), "B: never seen off: no switch-off mark, no hold");
        // C: the same drop, then the reconnect stays up long enough for the off verdict: the original deadline, marked, held.
        Ride c = new Ride();
        c.speed = 250; c.until(T0 + 60_000);
        last = c.lastPush;
        c.scooterOn = false; c.up = false; c.until(last + 60_000);
        c.up = true; c.transport = 2; c.until(last + 75_000);
        check(c.policy.seenOffWhileLinked() && c.switchedOff(), "C: the off verdict on the reconnect: seen off, a switch-off");
        c.up = false; c.until(last + 85_000);
        c.up = true; c.transport = 3; c.until(last + 90_000);
        c.up = false; c.until(last + 400_000);
        check(c.ended > 0 && c.ended <= last + 300_000 + 1000, "C: keeps the original deadline: " + (c.ended - last));
        check(c.switchedOff() && c.hold(), "C: saved as switched off, and held");
    }
}
