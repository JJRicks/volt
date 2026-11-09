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
        check(RidingFrames.recordAction(true, false, true) == RecorderTap.START, "stopped and idle starts");
        check(RidingFrames.recordAction(true, true, true) == RecorderTap.STOP, "stopped and recording stops");
        check(RidingFrames.recordAction(true, false, false) == RecorderTap.NONE, "not connected does nothing here");
        check(RidingFrames.recordAction(false, true, true) == RecorderTap.NONE && RidingFrames.recordAction(false, false, true) == RecorderTap.NONE, "a tap that lands once moving does nothing");
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

        // Never moved: nothing closes it.
        stopped[0] = true;
        LatchedCondition staysOpen = new LatchedCondition(() -> stopped[0]);
        check(staysOpen.holds() && staysOpen.holds(), "no motion seen: it keeps holding");
    }

    private static void source() throws IOException {
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidingScreen.java")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MainActivity.java")), StandardCharsets.UTF_8);
        check(screen.contains("stoppedLock = host.ridingLock(() -> stopped(") && screen.contains("showStopped(stoppedNow,"), "Lock and Record follow the stopped gate");
        check(main.contains("lockQueue.stoppedGateClosed(") && main.indexOf("lockQueue.stoppedGateClosed(") < main.indexOf("decisionOnceIdle(\"lock\",Boolean.TRUE"), "a waiting tap meets the stopped gate before the lock's own gate");
        check(main.contains("stoppedGate!=null&&!stoppedGate.getAsBoolean()") && main.contains("lockQueue.sending(true);"), "a tap as motion starts is refused, and a send is held from redraws");
        check(screen.contains("RidingFrames.recordAction(stopped(SystemClock.elapsedRealtime())") && screen.contains("RideRecordingApp.of(host)"), "Record is Home's control, re-checked at the tap");
        check(!screen.contains("ble.set("), "the lock's tap goes through MainActivity's queue, never straight to the scooter");
        String ble = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/BleController.java")), StandardCharsets.UTF_8);
        check(main.contains("if(lockQueue.stoppedOnly())ble.holdUnsent(\"lock\",lockStoppedGate,"), "a riding tap's stopped gate is carried past the queue (a read in flight can leave it unsent)");
        String admit = ble.substring(ble.indexOf("private boolean admitQueuedMutation()"), ble.indexOf("private void incoming(byte[] bytes)"));
        check(admit.indexOf("pending.sendCondition.holds()") > 0 && admit.contains("withReason(d,CapabilityResolver.Reason.MOVING)"), "the radio boundary re-checks the carried condition");
        check(admit.indexOf("pending.conditionClosed.run()") > 0 && admit.indexOf("pending.conditionClosed.run()") < admit.indexOf("refused(pending.request,d)"), "the tap is settled before the refusal's redraw (no 'did not confirm')");
        check(ble.contains("!mutation.sent&&mutation.key.equals(key)"), "only a queued change is held to the condition, and it never applies to one that went out");
        String inc = ble.substring(ble.indexOf("private void incoming(byte[] bytes)"), ble.indexOf("private boolean remember()"));
        int motion = inc.indexOf("motionMs=now();"), look = inc.indexOf("mutation.sendCondition.holds()");
        check(motion > 0 && look > motion, "a motion report looks at the held condition after the new reading lands");
        check(inc.lastIndexOf("!mutation.sent&&mutation.sendCondition!=null", look) >= motion, "motion doesn't touch a change that already went out");
        check(ble.contains("mutation.sendCondition=new LatchedCondition(condition);"), "the carried condition latches");
    }
}
