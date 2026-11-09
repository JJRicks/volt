package com.jjricks.volt;

import java.util.function.LongFunction;

/**
 * The live charging card out of sight (owner's Pixel, UT5 MAX): with status asked for every 15 s, the card must stay
 * "Charging" while the link is up, and still show "reconnecting" and drop on ChargeLiveHold's schedule after a real loss.
 * Drives StatusCadence.linked() (what BleController.linked() and ScooterService's background facts use) with a fake
 * monotonic clock, into ChargeLiveHold, ChargeLiveState and ChargeAlertPolicy. Listed in tests/l10n/charge-live-background.sources.
 */
public final class ChargeLiveBackgroundTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long S = 1000L, T0 = 1_000_000L, STEP = 250L, REPLY = 300L, NOW_WALL = 1_800_000_000_000L;
    private static final LongFunction<String> CLOCK = wall -> "05:49";

    public static void main(String[] args) {
        cadence();
        foregroundUnchanged();
        backgroundStaysCharging();
        linkDownDrops();
        silentLinkDrops();
        unplugClearsAfterTheHold();
        briefDropoutKeepsTheCard();
        replugDuringTheHoldKeepsTheCard();
        alertsKeepWatching();
        System.out.println("ChargeLiveBackgroundTest: " + checks + " checks passed");
    }

    /** BleController.ready()'s expression, copied: statusMs>0&&now()-statusMs<=7000 on top of the link checks. */
    private static boolean readyBefore(boolean up, long statusMs, long now) { return up && statusMs > 0 && now - statusMs <= 7000; }

    private static void cadence() {
        check(StatusCadence.READY_AGE_MS == CapabilityResolver.STATUS_MAX_AGE_MS && StatusCadence.READY_AGE_MS == 7 * S, "the fast window is ready()'s 7 s");
        check(StatusCadence.pollMs(true) == 2 * S && StatusCadence.pollMs(false) == 15 * S, "poll() asks every 2 s in sight, 15 s out of sight");
        check(StatusCadence.silenceMs(true) == 12 * S && StatusCadence.silenceMs(false) == 35 * S, "poll()'s silence limits are 12 s and 35 s");
        check(StatusCadence.linkedAgeMs(false) >= StatusCadence.POLL_SLOW_MS + 5 * S, "out of sight a status read counts for more than one poll period plus margin");
        check(StatusCadence.linkedAgeMs(false) <= StatusCadence.silenceMs(false), "and never longer than poll() itself keeps the link");
        check(!StatusCadence.linked(true, 0, T0, false) && !StatusCadence.linked(false, T0, T0, false), "no status read, or the link down, is never linked");
    }

    /** In sight (or recording) linked() is ready(): the same answer for every status age and link state. */
    private static void foregroundUnchanged() {
        for (boolean up : new boolean[]{false, true})
            for (long age = 0; age <= 40 * S; age += 50)
                check(StatusCadence.linked(up, T0, T0 + age, true) == readyBefore(up, T0, T0 + age), "fast cadence matches ready() at age " + age + (up ? "" : " down"));
        check(StatusCadence.linked(true, 0, T0, true) == readyBefore(true, 0, T0), "no status read: same answer");
        // Status every 2 s in sight: always within ready()'s window, so the card never says reconnecting there either.
        for (long t = T0; t <= T0 + 60 * S; t += STEP) {
            long status = t < T0 + REPLY ? T0 - S : T0 + REPLY + (t - T0 - REPLY) / StatusCadence.POLL_FAST_MS * StatusCadence.POLL_FAST_MS;
            check(StatusCadence.linked(true, status, t, true), "in sight, 2 s status keeps the link at " + (t - T0));
        }
    }

    /** One step of what ScooterService does: chargeLiveFacts(), ChargeLiveHold.step() and chargeLiveState(). */
    private static final class Service {
        final ChargeLiveHold hold = new ChargeLiveHold();
        Integer lastPercent;
        /** The level read while fresh; below the finish level an unplug is CHARGER_OFF, not FINISHED. */
        int level = 99;
        ChargeLiveHold.End end = ChargeLiveHold.End.NONE;
        ChargeLiveState card;
        boolean connected;
        void step(long now, boolean up, long statusMs, long readingMs, boolean fast) { step(now, up, statusMs, readingMs, fast, Boolean.TRUE); }
        /** charging is the last reading's value; a stale or absent reading is null, as liveReading() makes it. */
        void step(long now, boolean up, long statusMs, long readingMs, boolean fast, Boolean charging) {
            connected = StatusCadence.linked(up, statusMs, now, fast);
            boolean fresh = connected && readingMs > 0 && now >= readingMs && now - readingMs <= ChargeAlertPolicy.FRESH_MS;
            ChargeLiveHold.Facts f = new ChargeLiveHold.Facts(true, connected, fresh ? charging : null, false, fresh ? level : null, null, "ut5");
            if (f.percent != null) lastPercent = f.percent;
            ChargeLiveHold.End e = hold.step(now, f);
            if (e != ChargeLiveHold.End.NONE) end = e;
            ChargeLiveState.Input in = new ChargeLiveState.Input();
            in.scooter = "UT5 MAX";in.nowWall = NOW_WALL;in.percent = f.percent != null ? f.percent : lastPercent;
            in.reconnecting = !connected;
            if (connected) { in.minutesLeft = 8.0;in.watts = 122.0; }
            card = ChargeLiveState.of(in, CLOCK);
        }
    }
    private static Service armed() {
        Service s = new Service();
        check(s.hold.leave(T0, new ChargeLiveHold.Facts(true, true, true, false, 99, null, "ut5")), "leaving Volt while charging arms the hold");
        return s;
    }
    /**
     * The last status read at t: Volt's last 2 s read 1 s before the owner left at T0, then the reply (REPLY later) to each
     * 15 s background poll from T0 + 15 s, the last of them sent at or before lastPoll.
     */
    private static long backgroundStatus(long t, long lastPoll) {
        long last = T0 - S;
        for (long p = T0 + StatusCadence.POLL_SLOW_MS; p <= lastPoll && p + REPLY <= t; p += StatusCadence.POLL_SLOW_MS) last = p + REPLY;
        return last;
    }

    /** The device bug: status every 15 s out of sight. The card stays Charging; the 7 s window flipped it half the time. */
    private static void backgroundStaysCharging() {
        Service s = armed();
        int oldReconnecting = 0, steps = 0;
        for (long t = T0; t <= T0 + 10 * 60 * S; t += STEP) {
            long status = backgroundStatus(t, Long.MAX_VALUE);
            s.step(t, true, status, status, false);
            steps++;
            check(s.connected && s.hold.active() && s.end == ChargeLiveHold.End.NONE, "background link up at " + (t - T0) + " ms: still held, connected");
            check(s.card.kind == ChargeLiveState.Kind.CHARGING && "Charging · 99%".equals(s.card.title), "the card says Charging at " + (t - T0) + " ms: " + s.card.title);
            check(s.card.text.startsWith("Done 05:49") && s.card.text.endsWith("122 W"), "with the estimate and power: " + s.card.text);
            if (!readyBefore(true, status, t)) oldReconnecting++;
        }
        // What the Pixel showed: about 8 s of every 15 s judged by ready()'s 7 s window.
        double share = oldReconnecting / (double) steps;
        check(share > 0.45 && share < 0.6, "the 7 s window would have said reconnecting for about half the time: " + share);
    }

    /** The link goes down out of sight: reconnecting at once with the last level, then the hold's 18 s and the card ends. */
    private static void linkDownDrops() {
        Service s = armed();
        long downAt = T0 + 2 * 60 * S + 7 * S;
        long t = T0;
        for (; t < downAt; t += STEP) { long status = backgroundStatus(t, Long.MAX_VALUE);s.step(t, true, status, status, false); }
        check(s.connected && s.card.kind == ChargeLiveState.Kind.CHARGING, "up until the drop");
        // BleController clears statusMs on a disconnect; up is false either way.
        s.step(downAt, false, 0, 0, false);
        check(!s.connected && s.card.kind == ChargeLiveState.Kind.RECONNECTING && "Last seen 99%".equals(s.card.title), "a drop shows Last seen at once: " + s.card.title);
        check("Out of reach · reconnecting".equals(s.card.text), "and reconnecting: " + s.card.text);
        for (t = downAt + STEP; t <= downAt + ChargeLiveHold.RECONNECT_MS; t += STEP) {
            s.step(t, false, 0, 0, false);
            check(s.hold.active() && s.end == ChargeLiveHold.End.NONE, "still reconnecting " + (t - downAt) + " ms after the drop");
        }
        s.step(downAt + ChargeLiveHold.RECONNECT_MS + STEP, false, 0, 0, false);
        check(!s.hold.active() && s.end == ChargeLiveHold.End.LOST, "the card ends just past the 18 s window, as before");
    }

    /** The link stays up but the scooter stops answering: linked until the background silence limit, then the same drop. */
    private static void silentLinkDrops() {
        Service s = armed();
        long lastPoll = T0 + 3 * StatusCadence.POLL_SLOW_MS, lastStatus = lastPoll + REPLY;
        long lostAt = -1, endAt = -1, failedAt = -1;
        for (long t = T0; t <= lastStatus + 2 * 60 * S && endAt < 0; t += STEP) {
            long status = backgroundStatus(t, lastPoll);
            // BleController.poll(): a tick past the silence limit fails the link (signal lost).
            if (failedAt < 0 && t > T0 && (t - T0) % StatusCadence.POLL_SLOW_MS == 0 && t - status > StatusCadence.silenceMs(false)) failedAt = t;
            s.step(t, failedAt < 0, failedAt < 0 ? status : 0, status, false);
            if (lostAt < 0 && !s.connected) lostAt = t;
            if (endAt < 0 && s.end == ChargeLiveHold.End.LOST) endAt = t;
        }
        check(lostAt - lastStatus > StatusCadence.SILENCE_SLOW_MS && lostAt - lastStatus <= StatusCadence.SILENCE_SLOW_MS + STEP, "no answer: linked through the background silence limit, then reconnecting (" + (lostAt - lastStatus) + " ms)");
        check(failedAt > lostAt && failedAt - lastStatus <= StatusCadence.silenceMs(false) + StatusCadence.POLL_SLOW_MS, "poll() ends the link on its next tick");
        check(endAt == lostAt + ChargeLiveHold.RECONNECT_MS + STEP, "the card ends 18 s after it started reconnecting");
        check(endAt - lastStatus <= 60 * S, "a silent scooter ends the card within a minute of its last answer");
    }

    /** Out of sight the owner's phone polls every 15 s: steps the service from now to end with this charging reading. */
    private static long run(Service s, long from, long end, Boolean charging) {
        for (long t = from; t <= end; t += STEP) {
            long status = backgroundStatus(t, Long.MAX_VALUE);
            s.step(t, true, status, status, false, charging);
            if (s.end != ChargeLiveHold.End.NONE) return t;
        }
        return -1;
    }

    /**
     * The reported bug's rule: unplugged with Volt out of sight, the card stays through ChargeLiveHold.STOP_MS (3 minutes)
     * and then ends on its own, with no one opening Volt; the first poll after that window ends it. A level below the limit
     * makes it CHARGER_OFF, the plain Connected notice again.
     */
    private static void unplugClearsAfterTheHold() {
        Service s = armed();
        s.level = 62;
        long unplug = T0 + 2 * 60 * S;
        check(run(s, T0, unplug - STEP, Boolean.TRUE) < 0 && s.hold.active(), "still held until the unplug");
        long ended = run(s, unplug, unplug + ChargeLiveHold.STOP_MS + 2 * StatusCadence.POLL_SLOW_MS, Boolean.FALSE);
        check(ended > 0 && s.end == ChargeLiveHold.End.CHARGER_OFF, "the hold ended by itself, out of sight: " + s.end);
        check(ended - unplug > ChargeLiveHold.STOP_MS && !s.hold.active(), "and not before the 3 minute hold");
        // The service's timer: step() must run at the end of the window even when no reading or tick arrives then.
        Service timed = armed();
        timed.level = 62;
        run(timed, T0, unplug - STEP, Boolean.TRUE);
        check(timed.hold.nextCheck() == T0 + ChargeLiveHold.HOLD_MS, "charging: only the hold limit is due");
        timed.step(unplug, true, backgroundStatus(unplug, Long.MAX_VALUE), backgroundStatus(unplug, Long.MAX_VALUE), false, Boolean.FALSE);
        check(timed.hold.nextCheck() == unplug + ChargeLiveHold.STOP_MS + 1, "unplugged: the check is due as the charger-off window ends");
        long at = timed.hold.nextCheck();
        timed.step(at, true, at, at, false, Boolean.FALSE);
        check(!timed.hold.active() && timed.end == ChargeLiveHold.End.CHARGER_OFF, "and that one check ends the hold");
        check(ended - unplug <= ChargeLiveHold.STOP_MS + StatusCadence.POLL_SLOW_MS + STEP, "within one poll of the hold's end: " + (ended - unplug) + " ms");
    }

    /** A charger that cuts out for 40 s and resumes keeps the hold, and a later unplug counts its full 3 minutes from scratch. */
    private static void briefDropoutKeepsTheCard() {
        Service s = armed();
        s.level = 62;
        long off = T0 + 30 * S, back = off + 40 * S;
        check(run(s, T0, off - STEP, Boolean.TRUE) < 0, "held before the dropout");
        check(run(s, off, back - STEP, Boolean.FALSE) < 0 && s.hold.active() && s.card.kind == ChargeLiveState.Kind.CHARGING, "a 40 s dropout never ends the hold or the card");
        check(run(s, back, back + 60 * S, Boolean.TRUE) < 0 && s.hold.active(), "charging again: still held");
        long unplug = back + 60 * S + STEP;
        long ended = run(s, unplug, unplug + ChargeLiveHold.STOP_MS + 2 * StatusCadence.POLL_SLOW_MS, Boolean.FALSE);
        check(ended - unplug > ChargeLiveHold.STOP_MS, "the dropout's seconds don't count toward a later unplug's 3 minutes");
    }

    /** Plugged back in just before the hold would have ended: the card stays, and nothing has started counting. */
    private static void replugDuringTheHoldKeepsTheCard() {
        Service s = armed();
        s.level = 62;
        long off = T0 + 15 * S, back = off + ChargeLiveHold.STOP_MS - 15 * S;
        check(run(s, T0, off - STEP, Boolean.TRUE) < 0, "held before the unplug");
        check(run(s, off, back - STEP, Boolean.FALSE) < 0 && s.hold.active(), "off for most of the hold: still held");
        check(run(s, back, back + 6 * 60 * S, Boolean.TRUE) < 0 && s.hold.active() && s.card.kind == ChargeLiveState.Kind.CHARGING, "replugged inside the hold: the card stays");
    }

    /**
     * Charge alerts out of sight: the service's link is CONNECTED from linked(), and a READY link has no recoverable
     * loss, so the 7 s window made it LOST (a pause) every 15 s. With linked() a watched charge keeps watching.
     */
    private static void alertsKeepWatching() {
        ChargeAlertPolicy.Settings settings = new ChargeAlertPolicy.Settings(true, 100, false, ChargeAlertPolicy.LOW_DEFAULT, true, false);
        ChargeAlertPolicy now = new ChargeAlertPolicy(settings, null), before = new ChargeAlertPolicy(settings, null);
        int pausedBefore = 0;
        for (long t = T0; t <= T0 + 10 * 60 * S; t += STEP) {
            long status = backgroundStatus(t, Long.MAX_VALUE);
            ChargeAlertPolicy.Link link = StatusCadence.linked(true, status, t, false) ? ChargeAlertPolicy.Link.CONNECTED : ChargeAlertPolicy.Link.LOST;
            ChargeAlertPolicy.Event e = now.step(t, NOW_WALL + t, true, true, link, 1d, status, 90d, status);
            check(e == ChargeAlertPolicy.Event.NONE && now.watching(), "a watched charge keeps watching out of sight at " + (t - T0) + " ms");
            ChargeAlertPolicy.Link old = readyBefore(true, status, t) ? ChargeAlertPolicy.Link.CONNECTED : ChargeAlertPolicy.Link.LOST;
            if (before.step(t, NOW_WALL + t, true, true, old, 1d, status, 90d, status) == ChargeAlertPolicy.Event.PAUSED) pausedBefore++;
        }
        check(pausedBefore > 20, "the 7 s window paused the watch on every background poll: " + pausedBefore);
    }
}
