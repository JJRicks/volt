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
            bluetoothBackOnBeforeTheScooter();
            parkedRuleStillWins();
            switchedOffWhileAnsweringEndsWithTheLinkUp();
            pushlessReconnectKeepsTheCountdown();
            offThenReconnectCycles();
            walkAwayKeepsBackgroundReconnect();
            pushlessReconnectEndsAtTheLastReading();
            settingOffCancels();
            text();
            serviceWiring();
            reconnectHoldWiring();
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
        check(RideAutoStopPolicy.loss(true, true, false, true) == RideAutoStopPolicy.Loss.RECOVERABLE, "Bluetooth back on: the adapter-off flag is not a verdict");
        check(RideAutoStopPolicy.loss(false, true, false, true) == RideAutoStopPolicy.Loss.UNRECOVERABLE, "no permission still cancels after adapter off");
        check(RideAutoStopPolicy.loss(true, false, false, true) == RideAutoStopPolicy.Loss.PHONE_BLUETOOTH_OFF, "still off is still phone Bluetooth off");
        List<ConnectionEventLog.Record> log = new ArrayList<>();
        check(!ConnectionEventLog.adapterOffLast(log) && !ConnectionEventLog.adapterOffLast(null), "no events, no adapter off");
        log.add(record(1000, ConnectionEventLog.Reason.AUTHENTICATED_READY, -1)); log.add(record(2000, ConnectionEventLog.Reason.ADAPTER_OFF, 10));
        log.add(record(3000, ConnectionEventLog.Reason.CHARGE_STOPPED, -1)); log.add(record(4000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1));
        check(ConnectionEventLog.adapterOffLast(log), "adapter off stays latest through charge events and reconnect attempts");
        log.add(record(5000, ConnectionEventLog.Reason.AUTHENTICATION_FAILED, -1));
        check(!ConnectionEventLog.adapterOffLast(log), "a failed reconnect is the latest event");
        log.add(record(6000, ConnectionEventLog.Reason.CONNECT_ATTEMPT, -1)); log.add(record(7000, ConnectionEventLog.Reason.BACKGROUND_PAUSE, -1));
        check(!ConnectionEventLog.adapterOffLast(log), "a background pause is the latest event");
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

    /**
     * A scripted ScooterService: 1 s ticks, the 12 s silent-link watchdog, the gap and heartbeats, the policy feed and the
     * tick checks. A real ScooterPowerState gives the switched-off verdict as BleController does, judged only when
     * {@code armed} (a UT5 MAX); status counts as fresh within its 7 s window.
     */
    private static final class Service {
        final RideStore rides; final String id; RideAutoStopPolicy policy;
        boolean connected = true, permissions = true, bluetooth = true, recoverable = true, gapMarked, autoStop = true, armed;
        long now, lastFrame, motion, lastPush; int speedRaw, driving, transport = 1;
        final ScooterPowerState power = new ScooterPowerState(); boolean powered;
        RideAutoStopPolicy.Link link;
        /** BleController's reconnect hold, and whether Volt is in sight (the hold governs background reconnects only). */
        final OffReconnectHold hold = new OffReconnectHold(); boolean visible;
        String stoppedWith; long stoppedAt = -1; RideStore.Summary saved;
        /** BleController's connection event log, oldest first. */
        final List<ConnectionEventLog.Record> events = new ArrayList<>();
        Service(String label, long start) throws IOException {
            File dir = Files.createTempDirectory("volt-autostop-wire").toFile(); folders.add(dir);
            rides = new RideStore(dir); now = start; id = rides.start(PROFILE, label, WALL0 + now, now);
        }
        /** One push from the scooter: fresh readings and a motion frame. */
        void push(int raw, int drive) { syncPower(); lastFrame = motion = lastPush = now; speedRaw = raw; driving = drive; power.frame(0x92, now); }
        /** An answered poll: a frame but no push (a switched-off scooter still answers these). */
        void poll() { lastFrame = now; }
        /** BleController starts ScooterPowerState when the link is ready and resets it, and its last push, when the link closes. */
        void syncPower() {
            if (connected && !powered) { power.start(now); powered = true; }
            else if (!connected && powered) { power.reset(); powered = false; lastPush = 0; }
        }
        void tick() throws IOException {
            if (stoppedAt >= 0) { now += 1000; return; }
            // BleController's watchdog: a recording link with no frame for 12 s is lost (checked every 2 s).
            if (connected && lastFrame > 0 && now - lastFrame > 12000 && now % 2000 == 0) connected = false;
            link = current();
            // ScooterService's recording tick: readings only from a link that shows the scooter on (Link.recordsReadings).
            if (link.recordsReadings()) {
                gapMarked = false;
                Map<String, RideStore.Sample> v = new LinkedHashMap<>();
                v.put("speed_kmh", new RideStore.Sample(speedRaw / 10.0, motion)); v.put("voltage_v", new RideStore.Sample(50, lastFrame));
                v.put("current_a", new RideStore.Sample(1, lastFrame)); v.put("battery_percent", new RideStore.Sample(60, lastFrame));
                rides.sample(WALL0 + now, now, v);
            } else if (!gapMarked) { rides.markGap(WALL0 + now, now, connected ? "Scooter switched off" : "Bluetooth disconnected"); gapMarked = true; }
            else rides.heartbeat(WALL0 + now, now);
            observe();
            // ScooterService.autoStopDue (serviceWiring checks the service lines).
            if (policy != null && autoStop) {
                boolean phoneOff = policy.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF;
                switch (policy.due(now, link)) {
                    case PARKED: stop(RideStore.STOP_AUTO_PARKED); break;
                    case QUIET: stop(RideStore.quietStopReason(8, phoneOff)); break;
                    case NO_MOTION: stop(RideStore.STOP_AUTO_NO_MOTION); break;
                    default: break;
                }
            }
            now += 1000;
        }
        /** The link as ScooterService.autoStopLink builds it from BleController. */
        RideAutoStopPolicy.Link current() {
            syncPower();
            boolean fresh = connected && lastFrame > 0 && now - lastFrame <= CapabilityResolver.STATUS_MAX_AGE_MS;
            ScooterPowerState.Result verdict = power.verdict(now, false, fresh, armed, fresh, false);
            boolean off = connected && verdict.off(), awaiting = connected && power.awaitingPush(verdict, armed);
            return connected ? RideAutoStopPolicy.Link.up(PROFILE, SERIAL, transport, lastFrame, motion, speedRaw, driving, off, awaiting, awaiting && fresh, lastPush)
                    : RideAutoStopPolicy.Link.down(loss());
        }
        void observe() {
            if (policy == null) policy = new RideAutoStopPolicy(id, PROFILE, SERIAL);
            if (!autoStop) { policy.reset(); return; }
            if (link == null) link = current();
            policy.observe(now, link);
        }
        /** The loss ScooterService.observeRideAutoStop hands the policy (serviceWiring checks the service line). */
        RideAutoStopPolicy.Loss loss() { return RideAutoStopPolicy.loss(permissions, bluetooth, recoverable, ConnectionEventLog.adapterOffLast(events)); }
        /** BleController.failed(...): sets the retry flag and records the event. */
        void failed(ConnectionEventLog.Reason reason, boolean retry) { connected = false; recoverable = retry; event(reason); }
        void event(ConnectionEventLog.Reason reason) { events.add(record(now, reason, -1)); }
        void stop(String reason) throws IOException {
            saved = rides.stop(WALL0 + now, now, reason); stoppedWith = reason; stoppedAt = now;
            // ScooterService.autoStopRide → BleController.rideEndedAutomatically.
            if (policy != null && OffReconnectHold.pauses(reason, armed, policy.seenOffWhileLinked())) hold.hold(PROFILE);
            policy = null;
        }
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

    /**
     * Phone Bluetooth off, then on again while the scooter is still unavailable. BleController's
     * STATE_OFF failure leaves its retry flag false while STATE_ON reconnects; that leftover flag
     * must not read as an unrecoverable loss and clear the 5 min countdown.
     */
    private static void bluetoothBackOnBeforeTheScooter() throws IOException {
        for (String outcome : new String[]{"retry fails", "owner check fails", "permission revoked", "reconnects", "nothing"}) {
            Service s = new Service("Bluetooth back on: " + outcome, 10000);
            s.ride(250, 1, 200000);
            long last = s.lastFrame;
            s.bluetooth = false; s.failed(ConnectionEventLog.Reason.ADAPTER_OFF, false);
            s.run(last + 30000);
            check(s.policy.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF, outcome + ": phone Bluetooth off counts down 5 min");
            // STATE_ON: BleController.connectLast() starts a reconnect; recoverableConnectionLoss stays false.
            s.bluetooth = true; s.event(ConnectionEventLog.Reason.CONNECT_ATTEMPT);
            s.run(last + 60000);
            check(s.stoppedAt < 0 && s.policy.quiet() == RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF && s.policy.quietSince() == last,
                    outcome + ": Bluetooth back on keeps the countdown while the reconnect runs");
            switch (outcome) {
                case "retry fails": s.failed(ConnectionEventLog.Reason.CONNECT_TIMEOUT, true); break;
                case "owner check fails": s.failed(ConnectionEventLog.Reason.AUTHENTICATION_FAILED, false); break;
                case "permission revoked": s.permissions = false; break;
                case "reconnects": s.connected = true; s.transport = 2; s.event(ConnectionEventLog.Reason.AUTHENTICATED_READY); s.ride(250, 1, last + 302000); break;
                default: break;
            }
            s.run(last + 302000);
            if (outcome.equals("retry fails") || outcome.equals("nothing"))
                check(s.stoppedAt == last + 300000 && s.saved.stopPhoneBluetoothOff, outcome + ": ends 5 min after the last frame, marked phone Bluetooth off");
            else if (outcome.equals("reconnects"))
                check(s.stoppedAt < 0 && s.connected && !s.policy.armed(), "reconnects: the authenticated reconnect cancels it, riding on past the old deadline");
            else {
                s.run(last + 900000);
                check(s.stoppedAt < 0 && !s.policy.armed(), outcome + ": a real unrecoverable loss still cancels");
            }
        }
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

    /**
     * Review of #623: a UT5 MAX switched off with its button while it keeps answering polls (link up, no pushes) ends the
     * ride a minute after its last push, with the link still up, through the same stop as a drop. Status replies lapsing
     * past their 7 s window mid-countdown change nothing; a push before the minute (switched on again) rides on.
     */
    private static void switchedOffWhileAnsweringEndsWithTheLinkUp() throws IOException {
        Service s = new Service("Off at the rack", 10000); s.armed = true;
        s.ride(250, 1, 200000); s.ride(30, 1, 230000); // walking the scooter at 3 km/h
        long last = s.lastPush;
        // Switched off: no pushes; a poll answered every 2 s, except none for 10 s (status stale for 3 s of it).
        while (s.now < last + 59000) { if (s.now % 2000 == 0 && !(s.now > last + 24000 && s.now < last + 34000)) s.poll(); s.tick(); }
        check(s.stoppedAt < 0 && s.connected && s.policy.quiet() == RideAutoStopPolicy.Quiet.SLOW && s.policy.quietSince() == last,
                "still recording at 59 s with the link up, the slow countdown from the last push");
        while (s.now < last + 62000) { if (s.now % 2000 == 0) s.poll(); s.tick(); }
        check(s.stoppedAt == last + 60000 && s.connected, "ends 60 s after the last push, the link still up: " + (s.stoppedAt - last));
        check(RideStore.STOP_AUTO_QUIET.equals(s.saved.stopReason) && s.saved.automaticStop, "the went-quiet stop, through the rider's stop path");

        Service on = new Service("Back on", 10000); on.armed = true;
        on.ride(30, 1, 100000); long walked = on.lastPush;
        while (on.now < walked + 30000) { if (on.now % 2000 == 0) on.poll(); on.tick(); }
        check(on.stoppedAt < 0 && on.policy.quietArmed(), "off and counting down");
        on.ride(30, 1, walked + 400000);
        check(on.stoppedAt < 0 && !on.policy.armed(), "switched on again: its first push cancels the countdown and the ride goes on");

        Service other = new Service("Another model", 10000);
        other.ride(30, 1, 100000); long quiet = other.lastPush;
        while (other.now < quiet + 400000) { if (other.now % 2000 == 0) other.poll(); other.tick(); }
        check(other.stoppedAt < 0 && !other.policy.armed(), "a scooter that is never judged keeps main's behaviour: polls answered, nothing ends before rule 3");
    }

    /**
     * The owner's 17:34 ride: switched off at riding speed, the link dropped, and Volt reconnected four minutes later to a
     * scooter that answered but never pushed. That reconnect no longer cancels the countdown: the ride ends five minutes
     * after the last frame, the new link still up. A scooter that is never judged keeps today's behaviour.
     */
    private static void pushlessReconnectKeepsTheCountdown() throws IOException {
        for (boolean judged : new boolean[]{true, false}) {
            Service s = new Service(judged ? "Off, then reconnected" : "Never judged", 10000); s.armed = judged;
            s.ride(250, 1, 200000);
            long last = s.lastPush;
            s.run(last + 240000);
            check(!s.connected && s.policy.quiet() == RideAutoStopPolicy.Quiet.LONG && s.policy.quietSince() == last, "dropped at riding speed: five minutes");
            s.connected = true; s.transport = 2;
            while (s.now < last + 302000) { if (s.now % 2000 == 0) s.poll(); s.tick(); }
            if (judged) check(s.stoppedAt == last + 300000 && s.connected && RideStore.STOP_AUTO_QUIET.equals(s.saved.stopReason),
                    "a reconnect without a push keeps the countdown: ends five minutes after the last frame, the link up: " + (s.stoppedAt - last));
            else check(s.stoppedAt < 0 && !s.policy.armed(), "never judged: the reconnect cancels it, as today");
        }
    }

    /**
     * #623 round 2: off verdict with the link up, then the link drops and comes back twice while the scooter stays off
     * (polls answered, no pushes): the ride ends on the original deadline, a minute after the last push.
     */
    private static void offThenReconnectCycles() throws IOException {
        Service s = new Service("Off, link flapping", 10000); s.armed = true;
        s.ride(30, 1, 100000);
        long last = s.lastPush;
        long[][] down = {{last + 15000, last + 20000}, {last + 36000, last + 41000}};
        while (s.now < last + 62000) {
            boolean gone = false;
            for (long[] d : down) gone |= s.now >= d[0] && s.now < d[1];
            if (gone && s.connected) s.connected = false;
            else if (!gone && !s.connected && s.stoppedAt < 0) { s.connected = true; s.transport++; }
            if (s.connected && s.now % 2000 == 0) s.poll();
            s.tick();
            if (s.now == last + 59000) check(s.stoppedAt < 0 && s.policy.quietSince() == last, "still counting from the last push through two reconnects");
        }
        check(s.stoppedAt == last + 60000, "ends on the original deadline, a minute after the last push: " + (s.stoppedAt - last));
    }

    /**
     * Council F05 on #627: the walk-away. Parked, the rider walks out of range with the scooter still on; the link drops
     * and never comes back, so nothing shows the scooter off. Today's background reconnect stays: no hold, and when the
     * rider comes back the reconnect (and background auto-record) can start the next ride. The off timeline holds.
     */
    private static void walkAwayKeepsBackgroundReconnect() throws IOException {
        Service s = new Service("Walk away", 10000); s.armed = true;
        s.ride(30, 1, 100000); s.ride(0, 0, 104000);
        s.run(s.lastFrame + 400000);
        check(s.stoppedAt > 0 && s.saved.automaticStop, "the walk-away ride ends on its own");
        check(!s.hold.held(PROFILE, false), "nothing showed the scooter off: no hold, background reconnect as today");
        // The rider comes back: the background reconnect goes ahead and the next ride's pushes arrive.
        s.connected = true; s.transport++;
        Service next = new Service("Next ride", s.now);
        next.armed = true; next.ride(250, 1, s.now + 60000);
        check(next.stoppedAt < 0 && !next.hold.held(PROFILE, false), "the next ride records");

        Service off = new Service("Off at the rack", 10000); off.armed = true;
        off.ride(30, 1, 100000);
        long last = off.lastPush;
        while (off.stoppedAt < 0 && off.now < last + 400000) { if (off.now % 2000 == 0) off.poll(); off.tick(); }
        check(off.stoppedAt > 0 && off.hold.held(PROFILE, false), "seen off on a live link: the hold is set");
        check(!off.hold.held(PROFILE, true), "but never while Volt is in sight (F03)");

        Service back = new Service("Off, reconnected", 10000); back.armed = true;
        back.ride(250, 1, 200000); long dropped = back.lastPush;
        back.run(dropped + 60000);
        back.connected = true; back.transport = 2;
        while (back.stoppedAt < 0 && back.now < dropped + 400000) { if (back.now % 2000 == 0) back.poll(); back.tick(); }
        check(back.stoppedAt > 0 && back.hold.held(PROFILE, false), "a reconnect that answered with no pushes is seen off too: hold");
    }

    /**
     * Council F06 on #627: switched off at speed, the link drops, and Volt reconnects to the off scooter, which answers
     * polls. Those answers are not ride readings (a gap, as for a drop), so the saved ride ends at its last real reading,
     * not at the countdown's deadline.
     */
    private static void pushlessReconnectEndsAtTheLastReading() throws IOException {
        Service s = new Service("Off at speed", 10000); s.armed = true;
        s.ride(250, 1, 200000);
        long last = s.lastPush;
        s.run(last + 240000);
        s.connected = true; s.transport = 2;
        while (s.now < last + 302000) { if (s.now % 2000 == 0) s.poll(); s.tick(); }
        check(s.stoppedAt == last + 300000, "ends on the deadline");
        long reading = last + RideStore.MAX_SENSOR_AGE_MS;
        check(s.saved.shownEndWallMs <= WALL0 + reading, "the shown end is the last real reading, not the answered polls': " + (s.saved.shownEndWallMs - WALL0 - last));
        check(s.saved.shownElapsedMs <= reading - 10000, "and so is the shown duration");
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
        check("End rides automatically".equals(T.morePrefsAutoStop()) && "When the scooter turns off, stays out of reach for 5 min, or doesn't move for 10 min".equals(T.morePrefsAutoStopSub()),
                "preference wording");
    }

    /** The service hooks, read from source: the tick order and one stop path shared with the rider's Stop and the auto-export hook. */
    private static void serviceWiring() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        check(src.contains("observeRideAutoStop(autoNow);\n                    autoStopDue(rideAutoStop, autoNow);"), "the tick observes, then checks the rules");
        String due = between(src, "private void autoStopDue(RideAutoStopPolicy policy,long now){", "\n    }");
        check(due.contains("switch(policy.due(now,autoStopLink())){") && due.contains("case PARKED:autoStopRide(policy);break;")
                && due.contains("case QUIET:autoStopRide(policy,RideStore.quietStopReason(") && due.contains("case NO_MOTION:autoStopNoMotion(policy);break;"),
                "the tick ends the ride by the rule that is due: parked, then the quiet countdown, then no motion");
        String observe = between(src, "private void observeRideAutoStop(long mono){", "\n    }");
        check(observe.contains("RideAutoStopPolicy.Link link=autoStopLink();rideAutoStop.observe(mono,link);"), "the service feeds the policy one Link per observation");
        String link = between(src, "private RideAutoStopPolicy.Link autoStopLink(){", "\n    }");
        check(link.contains("return RideAutoStopPolicy.Link.down(RideAutoStopPolicy.loss(ble.permissions(),ble.bluetoothEnabled(),ble.recoverableConnectionLoss(),ConnectionEventLog.adapterOffLast(ble.diagnosticEvents())));"),
                "the service reports Bluetooth off as its own loss");
        check(link.contains("ble.scooterOff(),ble.awaitingPush(),ble.ready(),ble.lastPushMs());"), "and a scooter switched off but answering, or still awaiting its first push (answered once ready), with its last push");
        String auto = between(src, "private void autoStopRide(RideAutoStopPolicy policy,String reason){", "\n    }");
        check(auto.contains("if(stopRide(reason)){") && auto.contains("policy!=rideAutoStop") && auto.contains("policy.owns(rides.activeId())") && auto.contains("!RideAutoStopPolicy.mayEnd(autoStopLink()))return;"),
                "an automatic end goes through stopRide(reason) after the ownership checks, with the scooter gone or switched off");
        check(src.contains("public void stopRide(){stopRide(\"\");}"), "the rider's Stop uses the same stopRide(reason)");
        String stop = between(src, "private boolean stopRide(String reason){", "\n    /**");
        check(stop.contains("stopPhoneRecording();"), "stopRide finishes the phone journal");
        check(between(src, "private void stopPhoneRecording(){", "phoneLocationForeground=false;}").contains("rideFullySaved();"), "the finished phone journal reaches rideFullySaved");
        check(between(src, "private void rideFullySaved(){", "}").contains("startRideAutoExport();"), "rideFullySaved starts the automatic ride copy");
    }
    /**
     * The reconnect hold after a switched-off or gone scooter's ride ended (OffReconnectHold), read from source: set by
     * the automatic stop, asked by every automatic reconnect, released by the rider's connect, Volt opening, or a push.
     */
    private static void reconnectHoldWiring() throws IOException {
        String svc = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        String ble = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BleController.java")), StandardCharsets.UTF_8).replace("\r", "");
        String auto = between(svc, "private void autoStopRide(RideAutoStopPolicy policy,String reason){", "\n    }");
        check(auto.indexOf("ble.rideEndedAutomatically(policy.profile,reason,policy.seenOffWhileLinked());") > auto.indexOf("if(stopRide(reason)){"), "a saved automatic end of rules 1 and 2 may pause reconnects");
        check(!between(svc, "private void autoStopNoMotion(RideAutoStopPolicy policy){", "\n    }").contains("rideEndedAutomatically"), "rule 3 (connected and on) never does");
        check(svc.contains("&& ble.state() == BleController.State.DISCONNECTED\n                && !ble.reconnectPaused()) ble.connectLast();"), "auto-connect asks first");
        String failed = between(ble, "private void failed(String text,boolean retry,ConnectionEventLog.Reason reason,int code){", "\n    }");
        check(failed.contains("if(profile!=null&&offHold.held(profile.id,visible))reconnect=false;") && failed.contains("workAllowed()&&!offHold.held(profile.id,visible))connect(profile,profile.mac);"),
                "the retry loop neither schedules nor runs a reconnect while paused");
        check(ble.contains("STATE_ON&&wanted&&gatt==null&&workAllowed()&&!reconnectPaused())connectLast();"), "Bluetooth back on asks first");
        check(ble.contains("if(resumed&&offHold.releaseAll())"), "Volt opening again releases every pause");
        String ended = between(ble, "public void rideEndedAutomatically(", "\n    }");
        check(ended.contains("if(OffReconnectHold.closesAttempt(gatt!=null,authenticated))"), "an attempt not yet authenticated is closed when the hold starts (F01)");
        check(auto.contains("ble.rideEndedAutomatically(policy.profile,reason,policy.seenOffWhileLinked());"), "the hold needs the scooter seen off on a live link (F05)");
        String tick = between(svc, "if (isRecording()) {\n                        recordPhoneTelemetry(mono);", "rides.sample(wall, mono, values);");
        check(tick.contains("if (!autoStopLink().recordsReadings()) {"), "the recording tick takes readings only from a link that shows the scooter on (F06)");
        check(between(ble, "private void pushed(){", "\n    }").contains("offHold.release(p.id)"), "a push releases its scooter");
        check(between(ble, "public void connectLast(){", "\n").contains("if(heldBack(p))return;resumeReconnects(p.id);"), "the rider's connect releases it");
        check(ble.contains("resumeReconnects(p.id);wanted=true;retries=0;"), "so does a scan pick");
    }
    private static String between(String text, String start, String end) {
        int a = text.indexOf(start); check(a >= 0, "found " + start);
        int b = text.indexOf(end, a + start.length()); check(b > a, "end of " + start);
        return text.substring(a, b);
    }

    private static void remove(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) remove(k); f.delete(); }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }
}
