package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule 3 of the ride auto-stop: a connected scooter that shows no motion for 10 minutes ends the ride.
 * Deterministic clock-driven policy checks in RideAutoStopPolicyTest's style, then the store's token and
 * shown end, the text, and the service hooks read from source. Synthetic identities and data only.
 */
public final class RideNoMotionStopTest {
    private static int checks;
    private static final String RIDE = "ride-one", PROFILE = "one", SERIAL = "X2538SYNTHETIC0001";
    private static final long T0 = 1_000_000, MIN = 60_000, NO_MOTION = RideAutoStopPolicy.NO_MOTION_MS, WALL0 = 1800000000000L;
    private static final List<File> folders = new ArrayList<>();
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static RideAutoStopPolicy policy() { return new RideAutoStopPolicy(RIDE, PROFILE, SERIAL); }
    /** One fresh motion push received at {@code at} on transport 1, observed at the same instant. */
    private static void push(RideAutoStopPolicy p, long at, int speed, int driving) { p.connected(at, PROFILE, SERIAL, 1, at, at, speed, driving); }
    /** A battery-only frame at {@code at}: the link is alive, the last motion push is still {@code pushed}. */
    private static void battery(RideAutoStopPolicy p, long at, long pushed, int speed, int driving) { p.connected(at, PROFILE, SERIAL, 1, at, pushed, speed, driving); }
    /** Riding pushes at 1 Hz from {@code from} up to and including {@code to}. */
    private static void ride(RideAutoStopPolicy p, long from, long to, int speed) { for (long t = from; t <= to; t += 1000) push(p, t, speed, 1); }
    /** True when any 1 s step in (from, to] makes the rule due; battery-only frames throughout. */
    private static boolean dueDuring(RideAutoStopPolicy p, long from, long to, long pushed) {
        for (long t = from + 1000; t <= to; t += 1000) { battery(p, t, pushed, 0, 0); if (p.dueNoMotion(t)) return true; }
        return false;
    }

    public static void main(String[] args) throws Exception {
        try {
            T.install(null);
            ownerParkedPattern();
            zerosThenMotionJustInTime();
            trafficLights();
            walkModeCountsAsMotion();
            odometerAndTripOnly();
            heldSpeedIsNotMotion();
            noMotionAtAll();
            switchOff();
            disconnectHandsOver();
            shortBlipKeepsCount();
            carriedDeadlineWaitsForReadings();
            carriedDeadlineNeedsComparableReading();
            clockBackwards();
            silentLinkOrOtherScooter();
            storeShownEnd();
            text();
            serviceWiring();
        } finally {
            T.install(null);
            for (File f : folders) remove(f);
        }
        System.out.println("RideNoMotionStopTest: " + checks + " checks passed (no-motion auto-stop)");
    }

    /**
     * The owner's ride: riding, the last speed above 0 at 535 s with driving status 0, speed-0 pushes
     * until 547 s, then no speed, trip, odometer or driving status at all, only battery frames at 1 Hz.
     */
    private static void ownerParkedPattern() {
        RideAutoStopPolicy p = policy();
        ride(p, T0, T0 + 534_000, 180);
        long last = T0 + 535_000; push(p, last, 12, 0);
        for (long t = last + 1000; t <= T0 + 547_000; t += 1000) push(p, t, 0, 0);
        long pushed = T0 + 547_000;
        check(p.noMotionArmed() && p.noMotionSince() == last, "the countdown runs from the last speed above 0, not the last push");
        check(!dueDuring(p, pushed, last + NO_MOTION - 1000, pushed), "still recording at 9:59 of battery frames");
        battery(p, last + NO_MOTION, pushed, 0, 0);
        check(p.dueNoMotion(last + NO_MOTION), "ends 10 min after the last motion");
        check(!p.dueNoMotion(last + NO_MOTION + 1000) && !p.noMotionArmed(), "fires once");
        check(!p.armed() && !p.due(last + NO_MOTION + 2000) && !p.dueQuiet(last + NO_MOTION + 2000), "the link rules stay idle while connected");
    }

    private static void zerosThenMotionJustInTime() {
        RideAutoStopPolicy p = policy();
        ride(p, T0, T0 + 60_000, 200);
        long last = T0 + 60_000, t = last + 1000;
        for (; t < last + NO_MOTION; t += 1000) { push(p, t, 0, 0); check(!p.dueNoMotion(t), "speed-0 pushes: not due before 10 min"); }
        push(p, last + NO_MOTION - 1, 40, 1);
        check(!p.dueNoMotion(last + NO_MOTION) && p.noMotionSince() == last + NO_MOTION - 1, "motion at 9:59.999 resets the countdown");
        check(!dueDuring(p, last + NO_MOTION, last + 2 * NO_MOTION - 2000, last + NO_MOTION - 1), "and the next 10 min count from it");
    }

    /** Red lights of 30 s to 3 min, with speed-0 pushes or no pushes at all, over 40 minutes of riding. */
    private static void trafficLights() {
        RideAutoStopPolicy p = policy();
        long t = T0; int[] stops = {30, 90, 180, 45, 120, 179};
        for (int round = 0; round < 6; round++) {
            for (long end = t + 4 * MIN; t < end; t += 1000) { push(p, t, 220, 1); check(!p.dueNoMotion(t), "riding is never due"); }
            long pushed = t - 1000;
            for (int s = 0; s < stops[round]; s++, t += 1000) {
                if (round % 2 == 0) push(p, t, 0, 0); else battery(p, t, pushed, 220, 1);
                check(!p.dueNoMotion(t), "a red light of " + stops[round] + " s is not 10 min");
            }
        }
        check(p.noMotionArmed() && t - T0 > 30 * MIN, "still recording after the whole loop");
    }

    /** The owner's walk-mode test: about 2 mph, speed pushes 0.2 to 3 km/h, driving status 0 throughout. */
    private static void walkModeCountsAsMotion() {
        RideAutoStopPolicy p = policy();
        int[] raw = {2, 7, 9, 4, 9, 0, 30, 30};
        long t = T0;
        for (int i = 0; t <= T0 + 15 * MIN; i++, t += 1000) { push(p, t, raw[i % raw.length], 0); check(!p.dueNoMotion(t), "walking the scooter is motion"); }
        check(p.noMotionSince() >= t - 3000, "each push above 0 resets the countdown, driving status 0 or not");
        long last = t - 1000;
        RideAutoStopPolicy q = policy();
        for (long u = T0; u <= T0 + 11 * MIN; u += 1000) push(q, u, 2, 0);
        check(!q.dueNoMotion(T0 + 11 * MIN), "0.2 km/h counts: no noise floor");
        for (long u = last + 1000; u < last + NO_MOTION; u += 1000) { push(p, u, 0, 0); check(!p.dueNoMotion(u), "stopped after walking: counting"); }
        push(p, last + NO_MOTION, 0, 0);
        check(p.dueNoMotion(last + NO_MOTION), "10 min after the last walking push");
        RideAutoStopPolicy d = policy();
        for (long u = T0; u <= T0 + 11 * MIN; u += 1000) push(d, u, 0, 1);
        check(!d.dueNoMotion(T0 + 11 * MIN), "driving status 1 still counts as motion");
    }

    /** No speed pushes ever (motion time 0), only the odometer or trip going up. */
    private static void odometerAndTripOnly() {
        RideAutoStopPolicy p = policy();
        double odo = 1234.5; long t = T0, lastRise = T0;
        for (; t <= T0 + 30 * MIN; t += 1000) {
            if ((t - T0) % MIN == 0 && t > T0) { odo += 0.1; lastRise = t; }
            p.connected(t, PROFILE, SERIAL, 1, t, 0, -1, -1, odo, Double.NaN);
            check(!p.dueNoMotion(t), "the odometer going up every minute is motion");
        }
        check(p.noMotionSince() == lastRise, "timed from the last rise");
        for (; t < lastRise + NO_MOTION; t += 1000) { p.connected(t, PROFILE, SERIAL, 1, t, 0, -1, -1, odo, Double.NaN); check(!p.dueNoMotion(t), "steady odometer: counting"); }
        p.connected(t, PROFILE, SERIAL, 1, t, 0, -1, -1, odo, Double.NaN);
        check(p.dueNoMotion(t), "10 min after the odometer stopped rising");

        RideAutoStopPolicy q = policy();
        q.connected(T0, PROFILE, SERIAL, 1, T0, 0, -1, -1, Double.NaN, 4.9);
        q.connected(T0 + 300_000, PROFILE, SERIAL, 1, T0 + 300_000, 0, -1, -1, Double.NaN, 5.0);
        check(q.noMotionSince() == T0 + 300_000, "the trip going up is motion");
        q.connected(T0 + 400_000, PROFILE, SERIAL, 1, T0 + 400_000, 0, -1, -1, Double.NaN, 0.0);
        check(q.noMotionSince() == T0 + 300_000, "a trip reset to 0 is not motion");
        q.connected(T0 + 500_000, PROFILE, SERIAL, 1, T0 + 500_000, 0, -1, -1, Double.NaN, Double.NaN);
        q.connected(T0 + 600_000, PROFILE, SERIAL, 1, T0 + 600_000, 0, -1, -1, Double.NaN, 0.1);
        check(q.noMotionSince() == T0 + 600_000, "after the reset, missing readings keep the new baseline; the next rise counts");
        q.connected(T0 + 700_000, PROFILE, SERIAL, 1, T0 + 700_000, 0, -1, -1, 900.0, 0.1);
        q.connected(T0 + 800_000, PROFILE, SERIAL, 1, T0 + 800_000, 0, -1, -1, 899.9, 0.1);
        check(q.noMotionSince() == T0 + 600_000, "a first odometer reading or a drop is a baseline, not motion");
        q.connected(T0 + 900_000, PROFILE, SERIAL, 2, T0 + 900_000, 0, -1, -1, 950.0, 3.0);
        check(q.noMotionSince() == T0 + 900_000, "a new connection starts its own baselines and countdown");
    }

    /** A speed above 0 that is never pushed again (the transport's held value) is not motion. */
    private static void heldSpeedIsNotMotion() {
        RideAutoStopPolicy p = policy();
        push(p, T0, 250, 1);
        check(!dueDuring(p, T0, T0 + NO_MOTION - 1000, T0), "held speed: counting");
        battery(p, T0 + NO_MOTION, T0, 250, 1);
        check(p.dueNoMotion(T0 + NO_MOTION), "a stale 25 km/h never holds the ride open");
        RideAutoStopPolicy late = policy();
        battery(late, T0, T0 - RideAutoStopPolicy.MOTION_FRESH_MS - 1, 250, 1);
        check(late.noMotionSince() == T0, "a push already stale when first seen is not motion");
    }

    /** No motion at all: counted from the first observation of this ride's scooter, in practice the ride's start. */
    private static void noMotionAtAll() {
        RideAutoStopPolicy p = policy();
        p.connected(T0, PROFILE, SERIAL, 1, T0, 0, -1, -1);
        check(p.noMotionArmed() && p.noMotionSince() == T0, "counted from the first observation");
        check(!dueDuring(p, T0, T0 + NO_MOTION - 1000, 0), "not due early");
        battery(p, T0 + NO_MOTION, 0, -1, -1);
        check(p.dueNoMotion(T0 + NO_MOTION), "due 10 min after the start");
    }

    /** End rides automatically off: the service resets the policy on every observation, so the rule never counts. */
    private static void switchOff() throws IOException {
        Service s = new Service("Switch off", 10_000);
        s.autoStop = false;
        s.ride(200, 1, 60_000);
        s.battery(60_000 + 30 * MIN);
        check(s.stoppedAt < 0 && !s.policy.noMotionArmed(), "End rides automatically off never ends it");
        s.autoStop = true; s.battery(s.now + NO_MOTION - 2000);
        check(s.stoppedAt < 0, "switched back on: counts from then, never from the old motion");
        s.battery(s.now + 4000);
        check(RideStore.STOP_AUTO_NO_MOTION.equals(s.stoppedWith), "and ends 10 min after the switch came back on");
    }

    /** A disconnect mid-countdown: the link rules take over exactly as before; a reconnect after a long gap counts afresh. */
    private static void disconnectHandsOver() {
        // Speed-0 pushes, then the drop: the parked rule's grace, as before.
        RideAutoStopPolicy p = policy();
        ride(p, T0, T0 + 60_000, 200);
        long last = T0 + 60_000, t = last + 1000;
        for (; t <= last + 5 * MIN; t += 1000) push(p, t, 0, 0);
        long lastPush = t - 1000, lost = lastPush + 2000;
        p.disconnected(lost, true);
        check(!p.noMotionArmed() && p.parkedArmed(), "a parked loss mid-countdown takes the parked grace, as before");
        check(!p.dueNoMotion(lost + 5000) && p.due(lost + RideAutoStopPolicy.DISCONNECT_GRACE_MS), "and the parked rule ends it");
        check(!p.dueNoMotion(last + NO_MOTION) && !p.dueNoMotion(last + NO_MOTION + 5000), "rule 3 never fires after it");
        // The owner's pattern, battery frames only, then the drop: no fresh speed, so the 5 min quiet countdown.
        RideAutoStopPolicy q = policy();
        ride(q, T0, T0 + 60_000, 200);
        for (t = last + 1000; t <= last + 5 * MIN; t += 1000) battery(q, t, last, 200, 1);
        long frame = t - 1000;
        q.disconnected(frame + 13_000, true);
        check(!q.noMotionArmed() && q.quiet() == RideAutoStopPolicy.Quiet.LONG && q.quietSince() == frame, "the quiet rule takes over from the last frame");
        check(!q.dueNoMotion(frame + 20_000) && !q.dueQuiet(frame + RideAutoStopPolicy.QUIET_LONG_MS - 1), "nothing is due before the quiet deadline");
        check(q.dueQuiet(frame + RideAutoStopPolicy.QUIET_LONG_MS), "the quiet rule ends it on its own schedule");

        RideAutoStopPolicy r = policy();
        ride(r, T0, T0 + 60_000, 200);
        for (t = last + 1000; t <= last + 8 * MIN; t += 1000) push(r, t, 0, 0);
        r.disconnected(t + 2000, true);
        long back = t - 1000 + RideAutoStopPolicy.RECONNECT_CARRY_MS + 1; r.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1);
        check(!r.armed() && r.noMotionSince() == back, "a reconnect more than a minute after the last frame cancels the quiet countdown and starts rule 3 again from the reconnect");
        boolean early = false;
        for (long u = back + 1000; u < back + NO_MOTION; u += 1000) { r.connected(u, PROFILE, SERIAL, 2, u, 0, -1, -1); early |= r.dueNoMotion(u); }
        check(!early, "the 8 min before the drop never count");
        r.connected(back + NO_MOTION, PROFILE, SERIAL, 2, back + NO_MOTION, 0, -1, -1);
        check(r.dueNoMotion(back + NO_MOTION), "10 min after the reconnect");

        RideAutoStopPolicy u = policy();
        ride(u, T0, T0 + 60_000, 200);
        u.disconnected(T0 + 70_000, false);
        check(!u.armed() && !u.noMotionArmed(), "an unrecoverable loss cancels everything, rule 3 too");
    }

    /**
     * The owner's ride of 2026-11-06: parked with the link up (battery frames only), then a native
     * disconnect about 6 s after the last frame and an authenticated reconnect about 10 s after it. The
     * blip must not restart the 10 minutes, and repeated blips must not keep the ride open forever.
     */
    private static void shortBlipKeepsCount() {
        long carry = RideAutoStopPolicy.RECONNECT_CARRY_MS;
        RideAutoStopPolicy p = policy();
        ride(p, T0, T0 + 60_000, 200);
        long last = T0 + 60_000;
        for (long t = last + 1000; t <= last + 3 * MIN; t += 1000) battery(p, t, last, 200, 1);
        long frame = last + 3 * MIN;
        p.disconnected(frame + 6000, true);
        check(p.quiet() == RideAutoStopPolicy.Quiet.LONG && !p.noMotionArmed(), "the drop hands over to the quiet rule");
        // Reconnect: the serial is not read yet on the first observation, then it is.
        p.connected(frame + 9000, PROFILE, "", 2, frame + 9000, 0, -1, -1);
        check(!p.armed() && !p.noMotionArmed(), "the reconnect cancels the quiet countdown; no serial yet, no count");
        p.connected(frame + 10_000, PROFILE, SERIAL, 2, frame + 10_000, 0, -1, -1);
        check(p.noMotionSince() == last, "a reconnect within a minute keeps counting from the motion before the drop");
        check(!dueDuring2(p, frame + 10_000, last + NO_MOTION - 1000), "still recording until 10 min after the last motion");
        p.connected(last + NO_MOTION, PROFILE, SERIAL, 2, last + NO_MOTION, 0, -1, -1);
        check(p.dueNoMotion(last + NO_MOTION), "ends 10 min after the last motion, not 10 min after the blip");

        // A blip every 4 minutes would have restarted the count forever; now it ends on time.
        RideAutoStopPolicy b = policy();
        ride(b, T0, T0 + 60_000, 200);
        int transport = 1; long t = last; boolean fired = false;
        while (t < last + NO_MOTION + MIN && !fired) {
            t += 1000;
            if ((t - last) % (4 * MIN) == 0) { b.disconnected(t + 6000, true); t += 10_000; transport++; }
            b.connected(t, PROFILE, SERIAL, transport, t, 0, -1, -1);
            fired = b.dueNoMotion(t);
        }
        check(fired && t >= last + NO_MOTION && t <= last + NO_MOTION + 1000, "repeated short blips do not keep a parked ride open");

        // Exactly at the bound carries; a millisecond later counts afresh.
        RideAutoStopPolicy e = policy();
        ride(e, T0, T0 + 60_000, 200);
        e.disconnected(last + 6000, true);
        e.connected(last + carry, PROFILE, SERIAL, 2, last + carry, 0, -1, -1);
        check(e.noMotionSince() == last, "a reconnect exactly a minute after the last frame still carries");
        RideAutoStopPolicy f = policy();
        ride(f, T0, T0 + 60_000, 200);
        f.disconnected(last + 6000, true);
        f.connected(last + carry + 1, PROFILE, SERIAL, 2, last + carry + 1, 0, -1, -1);
        check(f.noMotionSince() == last + carry + 1, "a longer gap counts afresh from the reconnect");

        // Distance gained during the gap still counts as motion, against the baseline from before the drop.
        RideAutoStopPolicy d = policy();
        push(d, T0, 0, 0); d.distance(T0, 100.0, 5.0);
        long since = d.noMotionSince();
        battery(d, T0 + 5 * MIN, T0, 0, 0); d.distance(T0 + 5 * MIN, 100.0, 5.0);
        d.disconnected(T0 + 5 * MIN + 6000, true);
        long back = T0 + 5 * MIN + 20_000;
        d.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1);
        check(d.noMotionSince() == since, "carried before the first distance reading");
        d.distance(back, 100.1, 5.1);
        check(d.noMotionSince() == back, "the odometer gained across the gap is motion");
        // A fresh speed push after the reconnect is motion as before.
        RideAutoStopPolicy m = policy();
        ride(m, T0, T0 + 60_000, 200);
        m.disconnected(last + 6000, true);
        push2(m, last + 10_000, 150, 1);
        check(m.noMotionSince() == last + 10_000, "riding again after the blip counts as motion");

        // Another scooter, a reset or an unrecoverable loss in between drops the held count.
        RideAutoStopPolicy o = policy();
        ride(o, T0, T0 + 60_000, 200);
        o.disconnected(last + 6000, true);
        o.connected(last + 8000, PROFILE, "OTHERSERIAL0000", 2, last + 8000, 0, -1, -1);
        o.connected(last + 10_000, PROFILE, SERIAL, 3, last + 10_000, 0, -1, -1);
        check(o.noMotionSince() == last + 10_000, "another scooter in between drops the held count");
        RideAutoStopPolicy r = policy();
        ride(r, T0, T0 + 60_000, 200);
        r.disconnected(last + 6000, true); r.reset();
        r.connected(last + 10_000, PROFILE, SERIAL, 2, last + 10_000, 0, -1, -1);
        check(r.noMotionSince() == last + 10_000, "a reset in between drops the held count");
        RideAutoStopPolicy u = policy();
        ride(u, T0, T0 + 60_000, 200);
        u.disconnected(last + 6000, false);
        u.connected(last + 10_000, PROFILE, SERIAL, 2, last + 10_000, 0, -1, -1);
        check(u.noMotionSince() == last + 10_000, "an unrecoverable loss drops the held count");
    }
    /**
     * Codex's #303 review: a carried count whose 10 minutes ran out during the gap must not end the ride on
     * the reconnect's first frame, before a fresh distance reading could show the scooter moved meanwhile.
     */
    private static void carriedDeadlineWaitsForReadings() {
        long wait = RideAutoStopPolicy.CARRY_EVIDENCE_MS;
        // Codex's repro: the count starts at 1,000 ms, the link drops just before 600,001 ms, back at 601,001 ms, no readings.
        RideAutoStopPolicy p = policy();
        p.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); p.distance(1000, Double.NaN, Double.NaN);
        check(p.noMotionSince() == 1000, "the count starts at the first observation");
        p.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); p.distance(600_000, Double.NaN, Double.NaN);
        p.disconnected(600_000, true);
        long back = 601_001;
        p.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); p.distance(back, Double.NaN, Double.NaN);
        check(p.noMotionSince() == 1000 && !p.dueNoMotion(back), "the reconnect's first frame does not end the ride");
        boolean early = false;
        for (long t = back + 1000; t < back + wait; t += 1000) { p.connected(t, PROFILE, SERIAL, 2, t, 0, -1, -1); p.distance(t, Double.NaN, Double.NaN); early |= p.dueNoMotion(t); }
        check(!early, "no distance reading: still recording for the whole wait");
        p.connected(back + wait, PROFILE, SERIAL, 2, back + wait, 0, -1, -1); p.distance(back + wait, Double.NaN, Double.NaN);
        check(p.dueNoMotion(back + wait), "but no longer: a scooter that never reports distance cannot keep the ride open");

        // The first fresh reading after the drop shows the scooter moved: the ride keeps recording, counted from then.
        RideAutoStopPolicy m = policy();
        m.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); m.distance(1000, 100.0, 5.0);
        m.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); m.distance(600_000, 100.0, 5.0);
        m.disconnected(600_000, true);
        m.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); m.distance(back, Double.NaN, Double.NaN);
        check(!m.dueNoMotion(back), "nothing judged yet");
        long read = back + 2000;
        m.connected(read, PROFILE, SERIAL, 2, read, 0, -1, -1); m.distance(read, 100.3, 5.3);
        check(!m.dueNoMotion(read) && m.noMotionSince() == read, "distance gained in the gap is motion: the count restarts and the ride goes on");
        check(!dueDuring2(m, read, read + NO_MOTION - 1000), "a full 10 min from that reading");
        // A riding push after the reconnect does the same.
        RideAutoStopPolicy r = policy();
        r.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1);
        r.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); r.disconnected(600_000, true);
        r.connected(back, PROFILE, SERIAL, 2, back, back, 180, 1);
        check(!r.dueNoMotion(back) && r.noMotionSince() == back, "riding again after the gap keeps the ride");

        // The first fresh reading shows no movement: the carried count ends the ride at once.
        RideAutoStopPolicy n = policy();
        n.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); n.distance(1000, 100.0, 5.0);
        n.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); n.distance(600_000, 100.0, 5.0);
        n.disconnected(600_000, true);
        n.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); n.distance(back, Double.NaN, Double.NaN);
        // A reading no newer than the last frame plus its 3 s age may predate the drop: it decides nothing.
        long stale = 600_000 + RideAutoStopPolicy.READING_AGE_MS;
        RideAutoStopPolicy o = policy();
        o.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); o.distance(1000, 100.0, 5.0);
        o.connected(598_000, PROFILE, SERIAL, 1, 598_000, 0, -1, -1); o.distance(598_000, 100.0, 5.0);
        o.disconnected(600_000, true);
        o.connected(stale - 2000, PROFILE, SERIAL, 2, stale - 2000, 0, -1, -1); o.distance(stale - 2000, 100.0, 5.0);
        check(!o.dueNoMotion(stale - 2000), "a reading that may predate the drop does not release the wait");
        long fresh = back + 3000;
        n.connected(fresh, PROFILE, SERIAL, 2, fresh, 0, -1, -1); n.distance(fresh, 100.0, 5.0);
        check(n.dueNoMotion(fresh) && n.noMotionSince() == 0, "an unchanged fresh reading after the drop ends the ride at once");
    }
    /**
     * Codex's #303 round 3: only a fresh reading of a metric that had a value before the gap, and has not gone
     * down since, can show the scooter did not move; a metric with no baseline or a reset trip decides nothing.
     */
    private static void carriedDeadlineNeedsComparableReading() {
        long wait = RideAutoStopPolicy.CARRY_EVIDENCE_MS;
        double NaN = Double.NaN;
        // Codex's repro: odometer 100 km known, trip unknown; back at 604 s with no odometer and a first trip of 0 km.
        RideAutoStopPolicy p = policy();
        p.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); p.distance(1000, 100.0, NaN);
        p.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); p.distance(600_000, 100.0, NaN);
        p.disconnected(600_000, true);
        long back = 604_000;
        p.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); p.distance(back, NaN, 0.0);
        check(!p.dueNoMotion(back), "a trip with no value before the gap does not show the scooter stood still");
        long t = back + 1000;
        p.connected(t, PROFILE, SERIAL, 2, t, 0, -1, -1); p.distance(t, NaN, 0.0);
        check(!p.dueNoMotion(t), "nor does the same trip a second later (its baseline is from after the gap)");
        // Then the odometer arrives, unchanged: comparable with its pre-gap value, so the carried count ends it.
        long odo = back + 4000;
        p.connected(odo, PROFILE, SERIAL, 2, odo, 0, -1, -1); p.distance(odo, 100.0, 0.0);
        check(p.dueNoMotion(odo), "a later odometer reading equal to its pre-gap value releases the hold");
        // The same, but the odometer comes back higher: motion, the ride goes on.
        RideAutoStopPolicy q = policy();
        q.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); q.distance(1000, 100.0, NaN);
        q.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); q.distance(600_000, 100.0, NaN);
        q.disconnected(600_000, true);
        q.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); q.distance(back, NaN, 0.0);
        q.connected(odo, PROFILE, SERIAL, 2, odo, 0, -1, -1); q.distance(odo, 100.4, 0.1);
        check(!q.dueNoMotion(odo) && q.noMotionSince() == odo, "an odometer gained over the gap is motion");

        // A trip reset across the gap (5 km before, 0 km after; odometer unavailable) decides nothing.
        RideAutoStopPolicy r = policy();
        r.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); r.distance(1000, NaN, 5.0);
        r.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); r.distance(600_000, NaN, 5.0);
        r.disconnected(600_000, true);
        r.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); r.distance(back, NaN, 0.0);
        check(!r.dueNoMotion(back) && r.noMotionSince() == 1000, "a reset trip is neither motion nor proof of standing still");
        boolean early = false;
        for (t = back + 1000; t < back + wait; t += 1000) { r.connected(t, PROFILE, SERIAL, 2, t, 0, -1, -1); r.distance(t, NaN, 0.0); early |= r.dueNoMotion(t); }
        check(!early, "a reset trip keeps the hold for the whole wait, even as it repeats 0 km");
        r.connected(back + wait, PROFILE, SERIAL, 2, back + wait, 0, -1, -1); r.distance(back + wait, NaN, 0.0);
        check(r.dueNoMotion(back + wait), "then the 30 s bound ends it");
        // A reset trip with a comparable odometer: the odometer decides.
        RideAutoStopPolicy s2 = policy();
        s2.connected(1000, PROFILE, SERIAL, 1, 1000, 0, -1, -1); s2.distance(1000, 100.0, 5.0);
        s2.connected(600_000, PROFILE, SERIAL, 1, 600_000, 0, -1, -1); s2.distance(600_000, 100.0, 5.0);
        s2.disconnected(600_000, true);
        s2.connected(back, PROFILE, SERIAL, 2, back, 0, -1, -1); s2.distance(back, 100.0, 0.0);
        check(s2.dueNoMotion(back), "an unchanged odometer beside a reset trip shows the scooter stood still");
    }
    /** As dueDuring, on transport 2 with no motion push on it. */
    private static boolean dueDuring2(RideAutoStopPolicy p, long from, long to) {
        for (long t = from + 1000; t <= to; t += 1000) { p.connected(t, PROFILE, SERIAL, 2, t, 0, -1, -1); if (p.dueNoMotion(t)) return true; }
        return false;
    }
    /** One fresh motion push on transport 2. */
    private static void push2(RideAutoStopPolicy p, long at, int speed, int driving) { p.connected(at, PROFILE, SERIAL, 2, at, at, speed, driving); }

    private static void clockBackwards() {
        RideAutoStopPolicy p = policy();
        push(p, T0, 200, 1);
        check(!dueDuring(p, T0, T0 + 9 * MIN, T0), "nine still minutes");
        battery(p, T0 + 9 * MIN - 5000, T0, 0, 0);
        check(!p.noMotionArmed(), "a clock that moves backwards clears the countdown");
        check(!p.dueNoMotion(T0 + NO_MOTION) && !p.dueNoMotion(T0 + NO_MOTION + 1000), "and the old start never counts again");
        long again = T0 + NO_MOTION + 2000;
        battery(p, again, T0, 0, 0);
        check(p.noMotionSince() == again, "the next observation counts afresh");
        check(!dueDuring(p, again, again + NO_MOTION - 1000, T0), "a full 10 min from then");
        battery(p, again + NO_MOTION, T0, 0, 0);
        check(p.dueNoMotion(again + NO_MOTION), "then due");
        RideAutoStopPolicy q = policy();
        push(q, T0, 200, 1);
        check(!q.dueNoMotion(T0 - 1) && !q.noMotionArmed(), "a backwards clock in the due check itself fails closed");
    }

    private static void silentLinkOrOtherScooter() {
        RideAutoStopPolicy p = policy();
        push(p, T0, 200, 1);
        // The link stays "connected" with no frames (the watchdog has not reported yet).
        p.connected(T0 + NO_MOTION + 1000, PROFILE, SERIAL, 1, T0 + NO_MOTION + 1000 - RideAutoStopPolicy.LOSS_WINDOW_MS - 1, T0, 200, 1);
        check(!p.dueNoMotion(T0 + NO_MOTION + 1000), "no recent frame: the loss watchdog decides, not rule 3");
        RideAutoStopPolicy o = policy();
        o.connected(T0, PROFILE, "OTHERSERIAL0000", 1, T0, 0, -1, -1);
        o.connected(T0 + NO_MOTION, PROFILE, "OTHERSERIAL0000", 1, T0 + NO_MOTION, 0, -1, -1);
        check(!o.noMotionArmed() && !o.dueNoMotion(T0 + NO_MOTION + 1000), "another scooter's link never counts for this ride");
        RideAutoStopPolicy n = policy();
        n.connected(T0, PROFILE, "", 1, T0, 0, -1, -1);
        check(!n.noMotionArmed(), "a link whose serial is not read yet does not count");
    }

    // ---- The store: token, shown end and analysis JSON --------------------------------------------

    /** A scripted ScooterService loop: 1 s ticks feeding a real RideStore and the real policy, as RideAutoStopWireTest. */
    private static final class Service {
        final RideStore rides; final File dir; final String id; RideAutoStopPolicy policy;
        boolean autoStop = true; long now, lastFrame, motion; int speedRaw = -1, driving = -1; double odo = 100.0;
        String stoppedWith; long stoppedAt = -1; RideStore.Summary saved;
        Service(String label, long start) throws IOException {
            dir = Files.createTempDirectory("volt-no-motion").toFile(); folders.add(dir);
            rides = new RideStore(dir); now = start; id = rides.start(PROFILE, label, WALL0 + now, now);
        }
        void tick(boolean speedFrame) throws IOException {
            if (stoppedAt >= 0) { now += 1000; return; }
            lastFrame = now;
            Map<String, RideStore.Sample> v = new LinkedHashMap<>();
            if (motion > 0) {
                v.put("speed_kmh", new RideStore.Sample(speedRaw / 10.0, motion)); v.put("driving_status", new RideStore.Sample(driving, motion));
                v.put("odometer_km", new RideStore.Sample(odo, motion));
            }
            v.put("voltage_v", new RideStore.Sample(50, now)); v.put("current_a", new RideStore.Sample(0.04, now)); v.put("battery_percent", new RideStore.Sample(60, now));
            rides.sample(WALL0 + now, now, v);
            if (policy == null) policy = new RideAutoStopPolicy(id, PROFILE, SERIAL);
            if (!autoStop) policy.reset();
            else policy.connected(now, PROFILE, SERIAL, 1, lastFrame, motion, speedRaw, driving);
            if (autoStop && policy.dueNoMotion(now)) { saved = rides.stop(WALL0 + now, now, RideStore.STOP_AUTO_NO_MOTION); stoppedWith = RideStore.STOP_AUTO_NO_MOTION; stoppedAt = now; }
            now += 1000;
        }
        void ride(int raw, int drive, long until) throws IOException {
            while (now < until) { motion = now; speedRaw = raw; driving = drive; if (raw > 0) odo += raw / 36000.0; tick(true); }
        }
        void battery(long until) throws IOException { while (now < until) tick(false); }
    }

    private static void storeShownEnd() throws Exception {
        Service s = new Service("Parked at the cafe", 10_000);
        s.ride(180, 1, 545_000);
        long lastMotion = s.motion;
        s.ride(0, 0, 557_000);
        s.battery(lastMotion + NO_MOTION + 5000);
        check(s.stoppedAt == lastMotion + NO_MOTION, "the loop ends it 10 min after the last motion: " + (s.stoppedAt - lastMotion));
        RideStore.Summary r = s.saved;
        check(r.noMotionStop && !r.automaticStop && RideStore.STOP_AUTO_NO_MOTION.equals(r.stopReason), "no-motion stop token, apart from the Bluetooth-loss stops");
        check(r.shownEndWallMs == WALL0 + lastMotion && r.shownElapsedMs == lastMotion - 10_000, "shown end is the last motion: " + (r.shownElapsedMs - (lastMotion - 10_000)));
        check(r.endWallMs == WALL0 + s.stoppedAt && r.elapsedMs == s.stoppedAt - 10_000 && r.gapCount == 0 && r.shownGapCount == 0, "raw end kept for integrity checks");
        check(r.toJson().contains("\"stopReason\":\"automatic_no_motion\"") && r.toJson().contains("\"shownElapsedMs\":" + (lastMotion - 10_000)), "analysis JSON names the stop and the shown end");
        RideStore.Summary reread = new RideStore(s.dir).get(s.id);
        check(reread.noMotionStop && reread.shownElapsedMs == r.shownElapsedMs && reread.shownEndWallMs == r.shownEndWallMs, "a reload judges the same last motion from the journal");
        File index = Files.createTempDirectory("volt-no-motion-index").toFile(); folders.add(index);
        RideStore indexed = new RideStore(s.dir, true, index);
        indexed.list(); indexed.flushIndex();
        RideStore cold = new RideStore(s.dir, true, index);
        cold.flushIndex();
        List<RideStore.Summary> cached = cold.listCached();
        check(cached != null && cached.size() == 1 && cached.get(0).noMotionStop && cached.get(0).shownElapsedMs == r.shownElapsedMs
                && cached.get(0).shownEndWallMs == r.shownEndWallMs && cached.get(0).shownGapCount == r.shownGapCount, "the saved summary cache keeps the last motion");
        String journal = new String(Files.readAllBytes(new File(s.dir, s.id + ".csv").toPath()), StandardCharsets.UTF_8);
        check(journal.trim().endsWith(",automatic_no_motion"), "the STOP row carries the bare token");

        Service w = new Service("Walk it home", 10_000);
        w.ride(200, 1, 60_000); w.ride(9, 0, 300_000);
        long walked = w.motion; w.battery(walked + NO_MOTION + 2000);
        check(w.saved != null && w.saved.shownEndWallMs == WALL0 + walked, "walk-mode speed with driving status 0 moves the shown end");

        Service n = new Service("Never moved", 10_000);
        n.battery(10_000 + NO_MOTION + 2000);
        check(n.saved != null && n.saved.noMotionStop && n.saved.shownElapsedMs == n.saved.elapsedMs, "no motion at all: the shown end is the last reading, the stop itself");

        File dir = Files.createTempDirectory("volt-no-motion-odo").toFile(); folders.add(dir);
        RideStore store = new RideStore(dir); String id = store.start(PROFILE, "", WALL0, 0);
        for (long t = 0; t <= 900_000; t += 1000) {
            Map<String, RideStore.Sample> v = new LinkedHashMap<>();
            double odo = 50 + Math.min(t, 240_000) / 60_000 * 0.1;
            v.put("odometer_km", new RideStore.Sample(odo, t)); v.put("trip_km", new RideStore.Sample(odo - 50, t)); v.put("battery_percent", new RideStore.Sample(70, t));
            store.sample(WALL0 + t, t, v);
        }
        RideStore.Summary o = store.stop(WALL0 + 900_000, 900_000, RideStore.STOP_AUTO_NO_MOTION);
        check(o.shownElapsedMs == 240_000, "odometer and trip rises alone move the shown end: " + o.shownElapsedMs);

        Service q = new Service("Rider", 10_000); q.autoStop = false;
        q.ride(200, 1, 60_000); q.battery(120_000);
        RideStore.Summary hand = q.rides.stop(WALL0 + q.now, q.now);
        check(!hand.automaticStop && !hand.noMotionStop && hand.shownElapsedMs == hand.elapsedMs, "a rider's stop keeps its raw end");
        Service k = new Service("Parked token", 10_000);
        k.ride(200, 1, 60_000); k.battery(80_000);
        RideStore.Summary parked = k.rides.stop(WALL0 + k.now, k.now, RideStore.STOP_AUTO_PARKED);
        check(parked.automaticStop && !parked.noMotionStop && parked.toJson().contains("\"stopReason\":\"automatic_parked_disconnect\""), "the parked token reads as before");
    }

    private static void text() throws IOException {
        Service s = new Service("Evening loop", 10_000);
        s.ride(200, 1, 60_000); s.battery(60_000 + NO_MOTION + 2000);
        check("Evening loop · No movement for 10 minutes. Ended at 4:12 PM, its last movement.".equals(RideAutoStopText.notice(s.stoppedWith, s.saved, "4:12 PM")),
                "the notice names the ride, the reason and the shown end: " + RideAutoStopText.notice(s.stoppedWith, s.saved, "4:12 PM"));
        check("No movement for 10 minutes".equals(RideAutoStopText.notice(RideStore.STOP_AUTO_NO_MOTION, null, null)), "unreadable ride: the reason alone");
        check("No movement for 10 minutes".equals(RideAutoStopText.notice(RideStore.STOP_AUTO_NO_MOTION, s.saved, "")), "no clock time: the reason alone");
        check("Saved automatically after 10 minutes with no movement".equals(RideAutoStopText.description(s.saved)), "pill description");
        Service u = new Service("", 10_000);
        u.ride(200, 1, 60_000); u.battery(60_000 + NO_MOTION + 2000);
        check("No movement for 10 minutes. Ended at 16:12, its last movement.".equals(RideAutoStopText.notice(u.stoppedWith, u.saved, "16:12")), "unnamed ride");
        check("Bluetooth lost while parked".equals(RideAutoStopText.notice(RideStore.STOP_AUTO_PARKED, null, null))
                && "Scooter turned off or out of range".equals(RideAutoStopText.notice(RideStore.quietStopReason(-1, false), null, null)), "the other notices are unchanged");
        check(RideAutoStopPolicy.NO_MOTION_MS == 10 * MIN, "the text's 10 minutes match the rule");
    }

    /** The service hooks, read from source: the tick order, the switch, the shared stop path and the notice. */
    private static void serviceWiring() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8).replace("\r", "");
        check(src.contains("else autoStopQuiet(autoStop, autoNow);\n                    if (autoStop != null && autoStop == rideAutoStop) autoStopNoMotion(autoStop, autoNow);"),
                "the tick checks rule 3 after the parked and quiet rules, only while the same policy is current");
        String observe = between(src, "private void observeRideAutoStop(long mono){", "\n    /**");
        check(observe.indexOf("settings.getBoolean(RIDE_AUTO_STOP,true)") < observe.indexOf("rideAutoStop.connected(") && observe.contains("rideAutoStop.reset();"),
                "End rides automatically off resets the policy before any connected observation");
        check(observe.contains("if(destroyed||simulating()||!isRecording()){rideAutoStop=null;return;}"), "charges and simulations never reach the policy");
        check(observe.contains("if(ble.connected())rideAutoStop.distance(mono,rideDistance(RideStore.ODOMETER_KEY,mono),rideDistance(RideStore.TRIP_KEY,mono));")
                && observe.indexOf("rideAutoStop.distance(") > observe.indexOf("rideAutoStop.connected("), "the service passes the odometer and trip right after the connected observation");
        String stop = between(src, "private void autoStopNoMotion(RideAutoStopPolicy policy,long now){", "\n    }");
        check(stop.contains("policy.dueNoMotion(now)") && stop.contains("policy!=rideAutoStop") && stop.contains("policy.owns(rides.activeId())") && stop.contains("!isRecording()"),
                "rule 3 checks ownership before stopping");
        check(stop.contains("if(stopRide(RideStore.STOP_AUTO_NO_MOTION)){") && stop.contains("showAutoStopNotice(id,RideStore.STOP_AUTO_NO_MOTION);"),
                "it ends through stopRide(reason) and posts the automatic-stop notice");
        check(!stop.contains("ble."), "Volt sends the scooter nothing for this rule");
        String distance = between(src, "private double rideDistance(String key,long mono){", "\n    }");
        check(!distance.contains("ble.write") && !distance.contains("send("), "reading the odometer sends nothing");
    }
    private static String between(String text, String start, String end) {
        int a = text.indexOf(start); check(a >= 0, "found " + start);
        int b = text.indexOf(end, a + start.length()); check(b > a, "end of " + start);
        return text.substring(a, b);
    }

    private static void remove(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) remove(k); f.delete(); }
}
