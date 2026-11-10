package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Riding mode's stopped Lock and Record controls (owner, Nov 8): they show under the layout picker's
 * own gate, Record takes a tap as Home's does but only while stopped, and the screen reaches the lock
 * only through Home's control. Synthesized values only.
 */
public final class RidingStoppedControlsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws IOException {
        gate();
        record();
        lockTaps();
        latch();
        source();
        System.out.println("RidingStoppedControlsTest: " + checks + " checks passed (the picker's gate, Record's taps, stopped-only lock taps, the latch, Home's lock path)");
    }

    private static void gate() {
        check(RidingFrames.pickerShown(true, 0.0) && RidingFrames.pickerShown(true, null) && RidingFrames.pickerShown(true, .9), "stopped shows them");
        check(!RidingFrames.pickerShown(true, 1.0) && !RidingFrames.pickerShown(true, 20.0), "1 km/h or more hides them");
        check(!RidingFrames.pickerShown(false, 0.0) && !RidingFrames.pickerShown(false, null), "no fresh stationary report hides them");
    }

    private static void record() {
        check(RidingFrames.recordEnabled(false, true, false) && RidingFrames.recordEnabled(true, false, false), "connected, or recording, takes a tap");
        check(!RidingFrames.recordEnabled(false, true, true) && !RidingFrames.recordEnabled(true, true, true) && !RidingFrames.recordEnabled(false, false, false), "still saving, or not connected, doesn't");
        // recordAction(connected, stopped, recording, ready, showingStop)
        check(RidingFrames.recordAction(true, true, false, true, false) == RecorderTap.START, "stopped and idle starts");
        check(RidingFrames.recordAction(true, true, true, true, true) == RecorderTap.STOP, "stopped and recording stops");
        check(RidingFrames.recordAction(true, false, true, true, true) == RecorderTap.NONE && RidingFrames.recordAction(true, false, false, true, false) == RecorderTap.NONE, "a tap that lands once moving does nothing");
        check(RidingFrames.recordAction(true, true, true, false, true) == RecorderTap.STOP && RidingFrames.recordAction(true, false, true, false, true) == RecorderTap.NONE,
                "connected with stale settings: a stop still needs stopped");
        check(RidingFrames.recordAction(true, false, true, false, true) == RecorderTap.NONE, "reconnected, no motion report yet: not stopped, so nothing");

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

        // Lock, Record and the tune button share one gate (RidingFrames.tuneShown).
        check(RidingFrames.tuneShown(false, false, null) && RidingFrames.tuneShown(false, false, 30.0) && RidingFrames.tuneShown(false, true, 0.0), "disconnected: shown, whatever the old readings say");
        check(RidingFrames.tuneShown(true, true, 0.0) && RidingFrames.tuneShown(true, true, null), "connected and stopped: shown");
        check(!RidingFrames.tuneShown(true, true, 1.0) && !RidingFrames.tuneShown(true, false, 0.0), "connected and moving: hidden");
        check(!RidingFrames.tuneShown(true, false, null), "reconnected with no motion report yet: hidden until the first stationary report");
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

        // A send that really went out and got no answer still reports it.
        q = new LockTapQueue();
        q.request(false, true, 0, true);
        q.step(0, true, false, false, open);
        q.sent();
        check(q.step(3000, true, false, false, open) == revert && q.takeFailure().contains("didn't confirm"), "an unanswered send is still unconfirmed");
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
        check(screen.contains("stoppedLock = host.ridingLock(() -> stopped(") && screen.contains("boolean shownNow = tuneShown(now);")
                && screen.contains("disc.show(shownNow);") && screen.contains("showStopped(shownNow,"), "Lock and Record show by the tune button's one reading of the gate, and Lock's taps keep the stopped gate");
        check(main.contains("lockQueue.stoppedGateClosed(") && main.indexOf("lockQueue.stoppedGateClosed(") < main.indexOf("decisionOnceIdle(\"lock\",Boolean.TRUE"), "a waiting tap meets the stopped gate before the lock's own gate");
        check(main.contains("stoppedGate!=null&&!stoppedGate.getAsBoolean()") && main.contains("lockQueue.sending(true);"), "a tap as motion starts is refused, and a send is held from redraws");
        check(screen.contains("RidingFrames.recordAction(service.ble.connected(), stopped(SystemClock.elapsedRealtime())") && screen.contains("recordGlyph.stop);")
                && screen.contains("RideRecordingApp.of(host)"), "Record is Home's control, re-checked at the tap against the connection and the mark drawn");
        check(main.contains("lockControl.update(shownLock,ready&&!editing&&lockOffered(ble,shownLock),ready?null:description);")
                && main.contains("saved?lastKnown:"), "with no live link the lock switch takes no tap and says its state is the last known");
        check(!screen.contains("ble.set("), "the lock's tap goes through MainActivity's queue, never straight to the scooter");
        String ble = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BleController.java")), StandardCharsets.UTF_8);
        check(main.contains("if(lockQueue.stoppedOnly())ble.holdUnsent(\"lock\",lockStoppedGate,"), "a riding tap's stopped gate is carried past the queue (a read in flight can leave it unsent)");
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
