package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Riding mode's stopped Lock and Record controls (owner, Nov 8): they show under the layout picker's
 * own gate (under 1 mph, hidden after 2 s at 1 mph or more; owner, Nov 19), Record takes a tap as
 * Home's does while shown, a lock tap waits up to 5 s for a complete stop, and the screen reaches the
 * lock only through Home's control. Synthesized values only.
 */
public final class RidingStoppedControlsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws IOException {
        gate();
        record();
        lockTaps();
        latch();
        liveSpeed();
        source();
        System.out.println("RidingStoppedControlsTest: " + checks + " checks passed (the picker's gate, Record's taps, stopped-only lock taps, the latch, Home's lock path)");
    }

    private static void gate() {
        long hide = RidingFrames.HIDE_AFTER_MS;
        check(hide == 2000 && Math.abs(RidingFrames.STOPPED_KMH - 1.609344) < 1e-9, "1 mph, and 2 s before a hide");
        check(RidingFrames.slow(0.0) && RidingFrames.slow(null) && RidingFrames.slow(.9) && RidingFrames.slow(1.6), "under 1 mph, or no speed, is slow");
        check(!RidingFrames.slow(1.609344) && !RidingFrames.slow(20.0), "1 mph or more isn't");

        // The owner's case (Nov 19): stopped, the wheel turns a little (not stationary, under 1 mph): they stay.
        RidingFrames.StoppedGate g = new RidingFrames.StoppedGate();
        check(g.update(0, true, 0.0) && g.update(100, true, 1.2) && g.update(200, true, .3), "a roll under 1 mph keeps them, stationary flag or not");
        check(g.update(300, true, null), "a missing speed shows them");

        // In km/h units: 1.5 km/h is under 1 mph; 1.7 km/h is over it, but only 2 s of it hides them.
        g = new RidingFrames.StoppedGate();
        check(g.update(0, true, 0.0), "stopped: shown");
        check(g.update(1000, true, 1.7) && g.hideAtMs() == 1000 + hide, "1 mph or more: still shown, a hide pending");
        check(g.update(1000 + hide - 1, true, 5.0), "just under 2 s at speed: still shown");
        check(!g.update(1000 + hide, true, 6.0) && g.hideAtMs() == -1, "2 s at 1 mph or more: hidden");
        check(!g.update(1000 + hide + 500, true, 30.0), "riding on: hidden");
        check(g.update(1000 + hide + 600, true, 1.5), "back under 1 mph: shown at once");

        // A blip: 1 mph or more for under 2 s, then under it, restarts the count.
        g = new RidingFrames.StoppedGate();
        g.update(0, true, 0.0);
        check(g.update(500, true, 2.0) && g.update(2000, true, 2.4), "1.5 s at walking pace: shown");
        check(g.update(2100, true, 1.0) && g.hideAtMs() == -1, "a reading under 1 mph ends the pending hide");
        check(g.update(2200, true, 2.0) && g.update(4199, true, 2.0) && !g.update(4200, true, 2.0), "the 2 s start again from the next fast reading");

        // In mph units the gate is the same km/h: 0.9 mph (1.45 km/h) shows, 1.1 mph (1.77 km/h) counts toward the hide.
        g = new RidingFrames.StoppedGate();
        check(g.update(0, true, .9 * 1.609344) && g.update(10, true, 1.1 * 1.609344) && !g.update(10 + hide, true, 1.1 * 1.609344), "mph: under 1 shows, 2 s over 1 hides");

        // A speed that goes missing while hidden shows them (no reading to say it moves); disconnected shows them too.
        g = new RidingFrames.StoppedGate();
        check(!g.update(0, true, 20.0) && g.update(100, true, null), "no speed: shown");
        g = new RidingFrames.StoppedGate();
        check(!g.update(0, true, 20.0) && g.update(100, false, 20.0), "disconnected: shown");
        check(g.update(200, true, 20.0) && !g.update(200 + hide, true, 20.0), "reconnected at speed: hidden after the 2 s");

        // Complete stop, for a waiting lock tap: stationary and a fresh 0.
        check(RidingFrames.still(true, 0.0), "stationary at 0: still");
        check(!RidingFrames.still(false, 0.0) && !RidingFrames.still(true, .3) && !RidingFrames.still(true, null) && !RidingFrames.still(false, null), "rolling, not stationary or no fresh speed: not still");
    }

    private static void record() {
        check(RidingFrames.recordEnabled(false, true, false) && RidingFrames.recordEnabled(true, false, false), "connected, or recording, takes a tap");
        check(!RidingFrames.recordEnabled(false, true, true) && !RidingFrames.recordEnabled(true, true, true) && !RidingFrames.recordEnabled(false, false, false), "still saving, or not connected, doesn't");
        // recordAction(connected, shown, recording, ready, showingStop): no stop needed, only the disc showing (owner, Nov 19).
        check(RidingFrames.recordAction(true, true, false, true, false) == RecorderTap.START, "shown and idle starts, rolling or not");
        check(RidingFrames.recordAction(true, true, true, true, true) == RecorderTap.STOP, "shown and recording stops");
        check(RidingFrames.recordAction(true, false, true, true, true) == RecorderTap.NONE && RidingFrames.recordAction(true, false, false, true, false) == RecorderTap.NONE, "a tap that lands as the disc hides does nothing");
        check(RidingFrames.recordAction(true, true, true, false, true) == RecorderTap.STOP && RidingFrames.recordAction(true, false, true, false, true) == RecorderTap.NONE,
                "connected with stale settings: a stop still needs the disc shown");

        // Disconnected (owner, Nov 9): a recording stops; nothing else ever happens, neither a start nor a connection.
        check(RidingFrames.recordAction(false, false, true, false, true) == RecorderTap.STOP, "disconnected and recording: the tap stops it");
        check(RidingFrames.recordAction(false, true, true, false, true) == RecorderTap.STOP, "disconnected with a stationary report still fresh: stops too");
        for (boolean stopped : new boolean[]{false, true})
            for (boolean ready : new boolean[]{false, true})
                check(RidingFrames.recordAction(false, stopped, false, ready, false) == RecorderTap.NONE, "disconnected and idle: nothing (stopped " + stopped + ", ready " + ready + ")");
        check(RidingFrames.recordAction(true, true, false, false, false) == RecorderTap.NONE, "connected but not ready and idle: no connection attempt from here");

        // A tap acts on the mark it landed on: an automatic end (or start) since the last draw makes it do nothing.
        check(RidingFrames.recordAction(true, true, false, true, true) == RecorderTap.NONE, "ended automatically before the redraw: the stop mark starts nothing");
        check(RidingFrames.recordAction(false, false, false, false, true) == RecorderTap.NONE, "ended automatically while disconnected: nothing");
        check(RidingFrames.recordAction(true, true, true, true, false) == RecorderTap.NONE, "started automatically before the redraw: the record mark stops nothing");

        // Lock, Record and the tune button share one gate (RidingFrames.StoppedGate).
        check(new RidingFrames.StoppedGate().update(0, false, null) && new RidingFrames.StoppedGate().update(0, false, 30.0), "disconnected: shown, whatever the old readings say");
        check(new RidingFrames.StoppedGate().update(0, true, 0.0) && new RidingFrames.StoppedGate().update(0, true, null), "connected and stopped: shown");
        check(!new RidingFrames.StoppedGate().update(0, true, 1.7), "connected and moving at the first look: hidden");
    }

    private static void lockTaps() {
        LockTapQueue.Gate open = LockTapQueue.Gate.OPEN, wait = LockTapQueue.Gate.WAIT, refuse = LockTapQueue.Gate.REFUSE;
        LockTapQueue.Action none = LockTapQueue.Action.NONE, send = LockTapQueue.Action.SEND, revert = LockTapQueue.Action.REVERT;
        CharSequence why = "synthetic reason";

        // An unlock tapped while stopped waits behind another write, then the scooter starts moving: it goes back, unsent.
        LockTapQueue q = new LockTapQueue();
        check(q.request(true, false, 0, true) && q.stoppedOnly(), "a riding-mode tap is stopped-only");
        check(q.stoppedGateClosed(true, why) == none && q.step(10, true, true, false, wait) == none && q.waiting(), "still stopped: it waits behind the other write");
        check(q.stoppedGateClosed(false, why) == revert && !q.waiting() && Boolean.TRUE.equals(q.shown(true)), "the gate closes: the waiting unlock goes back");
        check(why.equals(q.takeFailureMessage()) && q.step(20, true, true, false, open) == none, "it says why, and nothing is left to send");

        // Still stopped when the other write ends: the tap goes out as usual.
        q = new LockTapQueue();
        q.request(true, false, 0, true);
        check(q.stoppedGateClosed(true, why) == none && q.step(10, true, true, false, open) == send, "stopped throughout: the unlock is sent");

        // Home's unlock keeps its recovery path: the stopped gate never touches it.
        q = new LockTapQueue();
        q.request(true, false, 0);
        check(!q.stoppedOnly() && q.stoppedGateClosed(false, why) == none && q.waiting() && q.step(10, true, true, false, open) == send, "Home's unlock still goes out while moving");

        // Already sent: only the scooter's readback can settle it, so the gate leaves it alone.
        q = new LockTapQueue();
        q.request(true, false, 0, true);
        q.step(0, true, true, false, open);
        q.sent();
        check(q.stoppedGateClosed(false, why) == none && q.waiting(), "a sent tap isn't recalled");

        // The toast from the contact sheet: a refused send redraws the header from inside set().
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        check(q.step(0, true, false, false, refuse) == send, "a refusing gate still sends, so it can say why");
        q.sending(true);
        q.sent();
        check(q.step(1, true, false, false, refuse) == none && q.waiting(), "a redraw inside the send doesn't judge it");
        q.sending(false);
        q.refused();
        check(!q.waiting() && q.takeFailure() == null, "a refused send is not reported as unconfirmed");

        ridingWaits();

        // A send that really went out and got no answer still reports it.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        q.step(0, true, false, false, open);
        q.sent();
        check(q.step(3000, true, false, false, open) == revert && q.takeFailure().contains("didn't confirm"), "an unanswered send is still unconfirmed");
    }

    /** One of MainActivity.pumpLockQueue's looks, in its order: the stopped gate, the complete stop, then the lock's own gate. */
    private static LockTapQueue.Action pump(LockTapQueue q, long now, boolean ready, boolean shown, boolean still, Boolean confirmed, LockTapQueue.Gate gate) {
        return pump(q, now, ready, shown, still, confirmed, gate, false);
    }
    /** @param lockPending the lock change is in flight (sent, its readback not in yet) */
    private static LockTapQueue.Action pump(LockTapQueue q, long now, boolean ready, boolean shown, boolean still, Boolean confirmed, LockTapQueue.Gate gate, boolean lockPending) {
        if (!q.waiting()) return LockTapQueue.Action.NONE;
        CharSequence moving = "Bring the scooter to a stop first";
        if (ready) {
            LockTapQueue.Action a = q.stoppedGateClosed(shown, moving);
            if (a == LockTapQueue.Action.REVERT) return a;
            a = q.untilStill(now, still, moving);
            if (a != LockTapQueue.Action.NONE) return a;
        }
        return q.step(now, ready, confirmed, lockPending, gate);
    }

    /** Riding mode's taps while the wheel still turns (owner, Nov 19): they wait for a complete stop, for up to 5 s. */
    private static void ridingWaits() {
        LockTapQueue.Gate open = LockTapQueue.Gate.OPEN, wait = LockTapQueue.Gate.WAIT, refuse = LockTapQueue.Gate.REFUSE;
        LockTapQueue.Action none = LockTapQueue.Action.NONE, send = LockTapQueue.Action.SEND, revert = LockTapQueue.Action.REVERT, hold = LockTapQueue.Action.HOLD;
        long limit = LockTapQueue.HOLD_MS;
        check(limit == 5000, "a riding tap waits 5 s at most");

        // Rolling under 1 mph: the tap shows its side and waits; the moment the scooter is still, it goes out.
        LockTapQueue q = new LockTapQueue();
        check(q.request(false, true, 1000, true) && Boolean.TRUE.equals(q.shown(false)), "a lock tap while rolling shows locked at once");
        check(pump(q, 1100, true, true, false, false, refuse) == hold && q.waiting(), "not completely stopped: it waits, even though the lock's own gate would refuse");
        check(pump(q, 3000, true, true, false, false, refuse) == hold, "still rolling after 2 s: waiting");
        check(pump(q, 4000, true, true, true, false, open) == send, "completely stopped: it goes out");
        q.sent();
        check(pump(q, 4100, true, true, false, false, open, true) == none && q.waiting(), "sent: the readback settles it, the stop is no longer looked at");
        check(pump(q, 4300, true, true, false, true, open) == none && !q.waiting(), "the readback confirms it");

        // Never still within 5 s of the tap: put back with the reason, nothing sent, then or later.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        check(pump(q, limit, true, true, false, false, refuse) == hold, "5 s exactly: still waiting");
        check(pump(q, limit + 1, true, true, false, false, refuse) == revert && !q.waiting() && Boolean.FALSE.equals(q.shown(false)), "past 5 s: the switch goes back");
        check("Bring the scooter to a stop first".contentEquals(q.takeFailureMessage()), "with the keep-it-stopped notice");
        check(pump(q, limit + 100, true, true, true, false, open) == none, "a stop after that sends nothing");

        // Still at the tap: no wait at all.
        q = new LockTapQueue();
        q.request(true, false, 0, true);
        check(pump(q, 0, true, true, true, true, open) == send, "a tap while still goes out straight away");

        // The controls hide (2 s at 1 mph or more): put back at once, before the hold runs out.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        check(pump(q, 500, true, true, false, false, refuse) == hold, "rolling: waiting");
        check(pump(q, 2500, true, false, false, false, refuse) == revert && !q.waiting(), "the controls hide: the waiting tap goes back");
        check(q.takeFailureMessage() != null, "and says why");

        // The connection drops while it waits: put back, never sent on a reconnect.
        q = new LockTapQueue();
        q.request(true, false, 0, true);
        check(pump(q, 300, true, true, false, true, refuse) == hold, "rolling: waiting");
        check(pump(q, 600, false, true, false, true, wait) == revert && q.takeFailure().contains("Connection lost"), "the connection drops: it goes back");
        check(pump(q, 900, true, true, true, true, open) == none, "a reconnect has nothing to send");

        // Tapping back to the confirmed side while it waits cancels it.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        check(pump(q, 100, true, true, false, false, refuse) == hold, "waiting");
        check(q.request(false, false, 200, true) && !q.waiting() && pump(q, 300, true, true, true, false, open) == none, "a tap back cancels it; nothing goes out at the stop");

        // Never sent while moving: every send comes from a look that saw the scooter still.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        for (long t = 0; t <= limit; t += 250) check(pump(q, t, true, true, false, false, open) == hold, "not still at " + t + " ms: no send, even with the lock's gate open");
        check(pump(q, limit + 1, true, true, false, false, open) == revert, "and it never went out");

        // Still, then the lock's own gate holds it (an automatic write in flight): the 5 s from the tap still bound it.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        check(pump(q, 4000, true, true, true, false, wait) == none && q.waiting(), "still, behind another write: the usual wait");
        check(pump(q, 4500, true, true, false, false, open) == hold, "rolling again before it went out: waiting again, not sent");
        check(pump(q, limit + 1, true, true, false, false, open) == revert, "past 5 s: back");

        // Home's taps never wait for a stop: untilStill leaves them alone (unlock's recovery path).
        q = new LockTapQueue();
        q.request(true, false, 0);
        check(q.untilStill(100, false, "x") == none && q.step(100, true, true, false, open) == send, "Home's unlock still goes out while moving");
    }

    /** The complete stop's speed: the newest reading while fresh (3 s), never an old one. */
    private static void liveSpeed() {
        Telemetry telemetry = new Telemetry();
        check(telemetry.liveSpeedKmh(1_000) == null, "no reading yet: no live speed");
        telemetry.accept(0x91, new byte[]{0, 72, 1, 2, 38, 3, 12, 30, 15, 100}, 1_000);
        check(Double.valueOf(2).equals(telemetry.liveSpeedKmh(1_500)), "a fresh 2 km/h");
        check(!RidingFrames.still(true, telemetry.liveSpeedKmh(1_500)), "rolling at 2 km/h: not still");
        telemetry.accept(0x91, new byte[]{0, 72, 1, 0, 38, 3, 12, 30, 15, 100}, 2_000);
        check(Double.valueOf(0).equals(telemetry.liveSpeedKmh(2_000 + AutoStartPolicy.SPEED_FRESH_MS)) && RidingFrames.still(true, telemetry.liveSpeedKmh(2_100)), "a fresh 0, stationary: still");
        check(telemetry.liveSpeedKmh(2_001 + AutoStartPolicy.SPEED_FRESH_MS) == null && !RidingFrames.still(true, telemetry.liveSpeedKmh(5_100)), "an old 0 is no stop");
        check(telemetry.liveSpeedKmh(1_999) == null, "a reading from later on the clock isn't live");
        telemetry.clear();
        check(telemetry.liveSpeedKmh(2_100) == null, "a new connection starts with none");
    }

    /** A held change: the rider's stopped state as each motion report and then the radio boundary see it. */
    private static void latch() {
        final boolean[] stopped = {true};
        LatchedCondition held = new LatchedCondition(() -> stopped[0]);
        check(held.holds(), "stopped throughout: the change may go out");
        check(held.holds(), "a look changes nothing while it holds");

        // Stop, move, stop, all before the frame goes out: a later stop can't reopen it.
        stopped[0] = false;
        check(!held.holds(), "motion closes it");
        stopped[0] = true;
        check(!held.holds(), "stopped again before transmission: still closed");

        // Moving at transmission: closed by the first look there is.
        stopped[0] = false;
        check(!new LatchedCondition(() -> stopped[0]).holds(), "still moving at transmission: closed");

        // Stale, then a fresh stationary report before the frame goes out: the look before the report closed it, and the fresh report can't reopen it.
        stopped[0] = true;
        LatchedCondition stale = new LatchedCondition(() -> stopped[0]);
        check(stale.holds(), "accepted on a sample still fresh enough");
        stopped[0] = false;
        check(!stale.holds(), "the sample aged out: the look before the next report closes it");
        stopped[0] = true;
        check(!stale.holds(), "a fresh stationary report doesn't reopen it");

        // Never moved: nothing closes it.
        stopped[0] = true;
        LatchedCondition staysOpen = new LatchedCondition(() -> stopped[0]);
        check(staysOpen.holds() && staysOpen.holds(), "no motion seen: it keeps holding");
    }

    private static void source() throws IOException {
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidingScreen.java")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MainActivity.java")), StandardCharsets.UTF_8);
        String stoppedMotion = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidingStoppedMotion.java")), StandardCharsets.UTF_8);
        check(screen.contains("new RidingStoppedMotion(stoppedControls, power, stoppedLock, recordDisc)") && screen.contains("stoppedMotion.stop();"), "Lock and Record share the motion, and it stops with the screen");
        check(stoppedMotion.contains("new RidingDiscAnimator(this::apply)") && stoppedMotion.contains("RidingDisc.scale(at)") && stoppedMotion.contains("RidingDisc.alpha(at)"), "the tune disc's own numbers, not a copy");
        check(stoppedMotion.contains("View.INVISIBLE") && !stoppedMotion.contains("readout.setVisibility(View.GONE)"), "the readout keeps its place for the power bar's zero line");
        check(screen.contains("if (stoppedMotion.takesTaps()) recordTap();") && screen.contains("onInterceptTouchEvent(MotionEvent event) { return !stoppedMotion.takesTaps()"), "taps stop the moment they start to hide");
        int cancelled = stoppedMotion.indexOf("if (!shown) cancelTouch();");
        check(cancelled > stoppedMotion.indexOf("if (!motion.want(shown)) return false;") && cancelled < stoppedMotion.indexOf("motion.run();") && stoppedMotion.contains("MotionEvent.ACTION_CANCEL") && stoppedMotion.contains("controls.dispatchTouchEvent(cancel);"), "a gesture already down is cancelled as the hide starts, only then, and through the row");
        check(screen.indexOf("if (stoppedMotion.show(shown) && !shown) {") < screen.indexOf("recordDisc.setEnabled(false);", screen.indexOf("if (stoppedMotion.show(shown) && !shown) {")), "Record is disabled only after that cancel, which a disabled view would ignore");
        check(screen.indexOf("stoppedMotion.dim(alpha);") < screen.indexOf("if (!shown) return;", screen.indexOf("stoppedMotion.dim(alpha);")), "the long-ride dim keeps up while they hide");
        check(screen.contains("stoppedLock = host.ridingLock(() -> controlsShown(SystemClock.elapsedRealtime()), this::still);") && screen.contains("boolean shownNow = controlsShown(now);")
                && screen.contains("disc.show(shownNow);") && screen.contains("showStopped(shownNow,"), "Lock and Record show by the tune button's one reading of the gate, and Lock's taps keep the stopped gate");
        check(main.contains("lockQueue.stoppedGateClosed(") && main.indexOf("lockQueue.stoppedGateClosed(") < main.indexOf("decisionOnceIdle(\"lock\",Boolean.TRUE"), "a waiting tap meets the stopped gate before the lock's own gate");
        check(main.indexOf("lockQueue.stoppedGateClosed(") < main.indexOf("lockQueue.untilStill(") && main.indexOf("lockQueue.untilStill(") < main.indexOf("decisionOnceIdle(\"lock\",Boolean.TRUE")
                && main.contains("if(still==LockTapQueue.Action.HOLD)return;"), "then the complete stop, holding it before the lock's own gate");
        check(screen.contains("RidingFrames.still(ble.stationary(), ble.telemetry.liveSpeedKmh(SystemClock.elapsedRealtime()))"), "the complete stop is the stationary flag and a fresh speed of 0");
        check(screen.contains("lookAgainAt(session.stoppedGate.hideAtMs(), now);") && screen.contains("clockHandler.removeCallbacks(hideCheck);"), "a pending hide lands on time, and stops with the screen");
        check(main.contains("stoppedGate!=null&&!stoppedGate.getAsBoolean()") && main.contains("lockQueue.sending(true);"), "a tap as motion starts is refused, and a send is held from redraws");
        check(screen.contains("RidingFrames.recordAction(service.ble.connected(), controlsShown(SystemClock.elapsedRealtime())") && screen.contains("recordGlyph.stop);")
                && screen.contains("RideRecordingApp.of(host)"), "Record is Home's control, re-checked at the tap against the connection and the mark drawn");
        check(main.contains("lockControl.update(shownLock,ready&&!editing&&lockOffered(ble,shownLock),ready?null:description);")
                && main.contains("saved?lastKnown:"), "with no live link the lock switch takes no tap and says its state is the last known");
        check(!screen.contains("ble.set("), "the lock's tap goes through MainActivity's queue, never straight to the scooter");
        String ble = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BleController.java")), StandardCharsets.UTF_8);
        check(main.contains("if(lockQueue.stoppedOnly())ble.holdUnsent(\"lock\",lockStillGate,"), "a riding tap's complete stop is carried past the queue (a read in flight can leave it unsent)");
        String admit = ble.substring(ble.indexOf("private boolean admitQueuedMutation()"), ble.indexOf("private void incoming(byte[] bytes)"));
        check(admit.indexOf("pending.sendCondition.holds()") > 0 && admit.contains("withReason(d,CapabilityResolver.Reason.MOVING)"), "the radio boundary re-checks the carried condition");
        check(admit.indexOf("pending.conditionClosed.run()") > 0 && admit.indexOf("pending.conditionClosed.run()") < admit.indexOf("refused(pending.request,d)"), "the tap is settled before the refusal's redraw (no 'did not confirm')");
        check(ble.contains("!mutation.sent&&mutation.key.equals(key)"), "only a queued change is held to the condition, and it never applies to one that went out");
        String inc = ble.substring(ble.indexOf("private void incoming(byte[] bytes)"), ble.indexOf("private boolean remember()"));
        int motion = inc.indexOf("motionMs=now();");
        int before = inc.indexOf("lookAtHeldChange();");
        int after = inc.indexOf("lookAtHeldChange();", motion);
        check(before > 0 && before < motion, "a motion report looks at the held condition before refreshing the sample, so a stale gate is seen");
        check(after > motion, "a motion report looks at the held condition after the new reading lands");
        check(ble.contains("lookAtHeldChange(){if(mutation!=null&&!mutation.sent&&mutation.sendCondition!=null)mutation.sendCondition.holds();}"), "motion doesn't touch a change that already went out");
        check(ble.contains("mutation.sendCondition=new LatchedCondition(condition);"), "the carried condition latches");
    }
}
