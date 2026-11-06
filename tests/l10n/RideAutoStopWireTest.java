package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The went-quiet auto-stop as ScooterService wires it: the loss the service reports, the disconnect
 * code it saves, the tick order (parked first, then quiet), the stop through the rider's stop path,
 * and the notice and pill text. A scripted service loop drives the real policy and a real RideStore;
 * synthetic data only, no clock or sensor.
 */
public final class RideAutoStopWireTest {
    private static final String PROFILE = "profile-1", SERIAL = "N2213100000001";
    private static final long WALL0 = 1800000000000L;
    private static int checks;
    private static final List<File> folders = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            lossMapping();
            lossCode();
            walkingPowerOffEndsAfterAMinute();
            reconnectInsideTheWindowKeepsRecording();
            ridingSpeedWaitsFiveMinutes();
            phoneBluetoothOffEndsAfterFiveMinutes();
            parkedRuleStillWins();
            settingOffCancels();
            text();
            serviceWiring();
        } finally {
            T.install(null);
            for (File f : folders) remove(f);
        }
        System.out.println("RideAutoStopWireTest: " + checks + " checks passed (went-quiet auto-stop wiring)");
    }

    private static void lossMapping() {
        check(RideAutoStopPolicy.loss(true, true, true) == RideAutoStopPolicy.Loss.RECOVERABLE, "a retried transport loss is recoverable");
        check(RideAutoStopPolicy.loss(true, true, false) == RideAutoStopPolicy.Loss.UNRECOVERABLE, "owner rejected or unsupported is not");
        check(RideAutoStopPolicy.loss(true, false, false) == RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF, "Bluetooth switched off on the phone");
        check(RideAutoStopPolicy.loss(true, false, true) == RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF, "Bluetooth off wins over a stale retry flag");
        check(RideAutoStopPolicy.loss(false, false, true) == RideAutoStopPolicy.Loss.UNRECOVERABLE, "no permission cancels");
    }

    private static ConnectionEventLog.Record record(long elapsed, ConnectionEventLog.Reason reason, int code) {
        return new ConnectionEventLog.Record(WALL0 + elapsed, elapsed, reason, code, true, true, ConnectionEventLog.Phase.READY, -1, -1);
    }
    private static void lossCode() {
        List<ConnectionEventLog.Record> events = Arrays.asList(
                record(1000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 19),
                record(2000, ConnectionEventLog.Reason.AUTHENTICATED_READY, -1),
                record(60000, ConnectionEventLog.Reason.CHARGE_STOPPED, -1),
                record(61000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 8),
                record(62000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1),
                record(80000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 133));
        check(ConnectionEventLog.firstLossCode(events, 59000) == 8, "the drop after the last frame, not a later reconnect failure");
        check(ConnectionEventLog.firstLossCode(events, 61500) == -1, "a reconnect attempt first: unknown");
        check(ConnectionEventLog.firstLossCode(events, 1500) == -1, "a ready event first: unknown");
        check(ConnectionEventLog.firstLossCode(Arrays.asList(record(5000, ConnectionEventLog.Reason.READ_TIMEOUT, -1),
                record(6000, ConnectionEventLog.Reason.NATIVE_DISCONNECT, 8)), 4000) == -1, "a silent link has no native code");
        check(ConnectionEventLog.firstLossCode(events, 0) == -1 && ConnectionEventLog.firstLossCode(null, 1) == -1, "restart or no events: unknown");
    }

    /** A scripted ScooterService: 1 s ticks, the 12 s silent-link watchdog, the gap and heartbeats, the policy feed and the tick checks. */
    private static final class Service {
        final RideStore rides; final String id; RideAutoStopPolicy policy;
        boolean connected = true, permissions = true, bluetooth = true, recoverable = true, gapMarked, autoStop = true;
        long now, lastFrame, motion; int speedRaw, driving, transport = 1;
        String stoppedWith; long stoppedAt = -1; RideStore.Summary saved;
        Service(String label, long start) throws IOException {
            File dir = Files.createTempDirectory("volt-autostop-wire").toFile(); folders.add(dir);
            rides = new RideStore(dir); now = start; id = rides.start(PROFILE, label, WALL0 + now, now);
        }
        /** One push from the scooter: fresh readings and a motion frame. */
        void push(int raw, int drive) { lastFrame = motion = now; speedRaw = raw; driving = drive; }
        void tick() throws IOException {
            if (stoppedAt >= 0) { now += 1000; return; }
            // BleController's watchdog: a recording link with no frame for 12 s is lost (checked every 2 s).
            if (connected && lastFrame > 0 && now - lastFrame > 12000 && now % 2000 == 0) connected = false;
            if (connected) {
                gapMarked = false;
                Map<String, RideStore.Sample> v = new LinkedHashMap<>();
                v.put("speed_kmh", new RideStore.Sample(speedRaw / 10.0, motion)); v.put("voltage_v", new RideStore.Sample(50, lastFrame));
                v.put("current_a", new RideStore.Sample(1, lastFrame)); v.put("battery_percent", new RideStore.Sample(60, lastFrame));
                rides.sample(WALL0 + now, now, v);
            } else if (!gapMarked) { rides.markGap(WALL0 + now, now, "Bluetooth disconnected"); gapMarked = true; }
            else rides.heartbeat(WALL0 + now, now);
            observe();
            if (policy != null && policy.due(now)) stop(RideStore.STOP_AUTO_PARKED);
            else if (policy != null) {
                boolean phoneOff = policy.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF;
                if (policy.dueQuiet(now)) stop(RideStore.quietStopReason(8, phoneOff));
            }
            now += 1000;
        }
        void observe() {
            if (policy == null) policy = new RideAutoStopPolicy(id, PROFILE, SERIAL);
            if (!autoStop) { policy.reset(); return; }
            if (connected) policy.connected(now, PROFILE, SERIAL, transport, lastFrame, motion, speedRaw, driving);
            else policy.disconnected(now, RideAutoStopPolicy.loss(permissions, bluetooth, recoverable));
        }
        void stop(String reason) throws IOException { saved = rides.stop(WALL0 + now, now, reason); stoppedWith = reason; stoppedAt = now; policy = null; }
        void run(long untilMs) throws IOException { while (now < untilMs) tick(); }
        /** Riding pushes at 1 Hz until {@code untilMs}. */
        void ride(int raw, int drive, long untilMs) throws IOException { while (now < untilMs) { push(raw, drive); tick(); } }
    }

    private static void walkingPowerOffEndsAfterAMinute() throws IOException {
        Service s = new Service("Walk to the rack", 10000);
        s.ride(250, 1, 300000);
        s.ride(30, 1, 330000); // walking the scooter at 3 km/h
        long last = s.lastFrame; // switched off: no more frames
        s.run(last + 59000);
        check(s.stoppedAt < 0 && s.policy.quiet() == RideAutoStopPolicy.Quiet.SLOW, "still recording at 59 s, slow countdown");
        s.run(last + 62000);
        check(s.stoppedAt == last + 60000, "ends exactly 60 s after the last frame: " + (s.stoppedAt - last));
        RideStore.Summary r = s.saved;
        check(RideStore.STOP_AUTO_QUIET.equals(r.stopReason) && r.automaticStop && !r.stopPhoneBluetoothOff, "went-quiet stop token");
        // A value counts as a reading while it is at most RideStore.MAX_SENSOR_AGE_MS old, so the last sample that still holds one ends the ride.
        long reading = last + RideStore.MAX_SENSOR_AGE_MS;
        check(r.stopDisconnectCode == 8 && r.stopLastReadingMonotonicMs == reading, "code and last reading saved: " + (r.stopLastReadingMonotonicMs - last));
        check(r.shownEndWallMs == WALL0 + reading && r.shownElapsedMs == reading - 10000, "shown end is the last real reading");
        check(r.endWallMs == WALL0 + last + 60000 && r.shownGapCount == 0 && r.gapCount == 1, "raw end and gap kept for integrity checks");
        check(r.toJson().contains("\"stopReason\":\"automatic_quiet_disconnect\""), "analysis JSON names the stop");
    }

    private static void reconnectInsideTheWindowKeepsRecording() throws IOException {
        Service s = new Service("Short drop", 10000);
        s.ride(30, 1, 120000);
        long last = s.lastFrame;
        s.run(last + 40000);
        check(!s.connected && s.policy.quietArmed(), "lost at walking pace, countdown running");
        s.connected = true; s.transport = 2; s.ride(30, 1, last + 50000);
        check(!s.policy.armed(), "an authenticated reconnect cancels the countdown");
        s.run(s.lastFrame + 59000);
        check(s.stoppedAt < 0 && s.policy.quietArmed(), "a later loss starts its own countdown");
        check(s.policy.quietSince() == s.lastFrame, "timed from the new last frame, not the first drop");
        s.run(last + 130000);
        check(s.stoppedAt == s.lastFrame + 60000, "drops never add up: ends 60 s after the second loss");
        Service hand = new Service("Rider stop", 10000);
        hand.ride(30, 1, 60000); hand.run(hand.lastFrame + 30000);
        hand.stop("");
        check(!hand.saved.automaticStop && "".equals(hand.saved.stopReason) && hand.saved.shownEndWallMs == hand.saved.endWallMs, "a rider's stop keeps its raw end");
    }

    private static void ridingSpeedWaitsFiveMinutes() throws IOException {
        Service s = new Service("Radio trouble", 10000);
        s.ride(250, 1, 200000);
        long last = s.lastFrame;
        s.run(last + 299000);
        check(s.stoppedAt < 0 && s.policy.quiet() == RideAutoStopPolicy.Quiet.LONG, "riding speed takes the 5 min branch");
        s.run(last + 302000);
        check(s.stoppedAt == last + 300000, "ends 5 min after the last frame");
    }

    private static void phoneBluetoothOffEndsAfterFiveMinutes() throws IOException {
        Service s = new Service("Phone Bluetooth off", 10000);
        s.ride(250, 1, 200000);
        long last = s.lastFrame;
        // Switching Bluetooth off closes the link at once (BleController reports it as not retried).
        s.connected = false; s.bluetooth = false; s.recoverable = false;
        s.run(last + 299000);
        check(s.stoppedAt < 0 && s.policy.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF, "phone Bluetooth off counts down 5 min");
        s.run(last + 302000);
        check(s.stoppedAt == last + 300000 && s.saved.stopPhoneBluetoothOff, "ends 5 min after the last frame, marked phone Bluetooth off");
        Service walking = new Service("Walk, then Bluetooth off", 10000);
        walking.ride(30, 1, 100000); long walked = walking.lastFrame;
        walking.run(walked + 20000);
        walking.bluetooth = false; walking.recoverable = false;
        walking.run(walked + 62000);
        check(walking.stoppedAt == walked + 60000 && !walking.saved.stopPhoneBluetoothOff, "Bluetooth off never lengthens a running slow countdown");
    }

    private static void parkedRuleStillWins() throws IOException {
        Service s = new Service("Parked", 10000);
        s.ride(200, 1, 100000);
        s.ride(0, 0, 110000); // parked, pushes still arriving
        long last = s.lastFrame;
        s.run(last + 40000);
        check(RideStore.STOP_AUTO_PARKED.equals(s.stoppedWith), "a clean parked run ends through the parked rule");
        check(s.stoppedAt - last <= 25000, "after the silent link and the 10 s grace: " + (s.stoppedAt - last));
    }

    private static void settingOffCancels() throws IOException {
        Service s = new Service("Setting off", 10000);
        s.ride(30, 1, 100000);
        long last = s.lastFrame; s.run(last + 30000);
        s.autoStop = false; s.run(last + 400000);
        check(s.stoppedAt < 0, "End rides automatically off keeps recording");
    }

    private static void text() throws IOException {
        Service s = new Service("Evening loop", 10000);
        s.ride(30, 1, 100000); s.run(s.lastFrame + 61000);
        RideStore.Summary quiet = s.saved;
        check("Evening loop · Scooter turned off or out of range. Ended at 4:12 PM, its last reading.".equals(RideAutoStopText.notice(s.stoppedWith, quiet, "4:12 PM")),
                "the notice names the ride, the reason and its shown end: " + RideAutoStopText.notice(s.stoppedWith, quiet, "4:12 PM"));
        check("Scooter turned off or out of range".equals(RideAutoStopText.notice(RideStore.quietStopReason(-1, false), null, null)), "unreadable ride: the reason alone");
        check("Saved automatically after the scooter turned off or went out of range".equals(RideAutoStopText.description(quiet)), "pill description");
        Service p = new Service("", 10000);
        p.ride(250, 1, 100000); p.connected = false; p.bluetooth = false; p.recoverable = false; p.run(p.lastFrame + 301000);
        check("Phone Bluetooth stayed off. Ended at 16:12, its last reading.".equals(RideAutoStopText.notice(p.stoppedWith, p.saved, "16:12")), "phone Bluetooth notice, no name");
        check("Saved automatically after the phone's Bluetooth stayed off".equals(RideAutoStopText.description(p.saved)), "phone Bluetooth pill description");
        Service k = new Service("Campus", 10000);
        k.ride(200, 1, 100000); k.ride(0, 0, 110000); k.run(k.lastFrame + 40000);
        check("Campus · Bluetooth lost while parked".equals(RideAutoStopText.notice(k.stoppedWith, k.saved, "4:12 PM")), "parked notice unchanged");
        check("Saved automatically after Bluetooth was lost while parked".equals(RideAutoStopText.description(k.saved)), "parked pill unchanged");
        check("Bluetooth lost while parked".equals(RideAutoStopText.notice(RideStore.STOP_AUTO_PARKED, null, null)), "parked, ride unreadable");
        check("End rides automatically".equals(T.morePrefsAutoStop()) && "When the scooter turns off, or stays out of reach for 5 min".equals(T.morePrefsAutoStopSub()),
                "preference wording");
    }

    /** The service hooks, read from source: the tick order and one stop path shared with the rider's Stop and the auto-export hook. */
    private static void serviceWiring() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        check(src.contains("if (autoStop != null && autoStop.due(autoNow)) autoStopRide(autoStop);\n                    else autoStopQuiet(autoStop, autoNow);"),
                "the tick checks the parked rule, then the quiet countdown");
        check(src.contains("rideAutoStop.disconnected(mono,RideAutoStopPolicy.loss(ble.permissions(),ble.bluetoothEnabled(),ble.recoverableConnectionLoss()));"),
                "the service reports Bluetooth off as its own loss");
        String auto = between(src, "private void autoStopRide(RideAutoStopPolicy policy,String reason){", "\n    }");
        check(auto.contains("if(stopRide(reason)){") && auto.contains("policy!=rideAutoStop") && auto.contains("policy.owns(rides.activeId())") && auto.contains("ble.connected())return;"),
                "an automatic end goes through stopRide(reason) after the ownership checks");
        check(src.contains("public void stopRide(){stopRide(\"\");}"), "the rider's Stop uses the same stopRide(reason)");
        String stop = between(src, "private boolean stopRide(String reason){", "\n    /**");
        check(stop.contains("stopPhoneRecording();"), "stopRide finishes the phone journal");
        check(between(src, "private void stopPhoneRecording(){", "phoneLocationForeground=false;}").contains("rideFullySaved();"), "the finished phone journal reaches rideFullySaved");
        check(between(src, "private void rideFullySaved(){", "}").contains("startRideAutoExport();"), "rideFullySaved starts the automatic ride copy");
    }
    private static String between(String text, String start, String end) {
        int a = text.indexOf(start); check(a >= 0, "found " + start);
        int b = text.indexOf(end, a + start.length()); check(b > a, "end of " + start);
        return text.substring(a, b);
    }

    private static void remove(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) remove(k); f.delete(); }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }
}
