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
            check(!RideAutoStopText.notice(reason, s, "4:12 PM").contains("Bluetooth"), "never Bluetooth lost while parked");
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
}
