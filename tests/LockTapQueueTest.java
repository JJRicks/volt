package com.jjricks.volt;

public final class LockTapQueueTest {
    private static int checks;
    public static void main(String[] args) {
        LockTapQueue.Gate open = LockTapQueue.Gate.OPEN, wait = LockTapQueue.Gate.WAIT, refuse = LockTapQueue.Gate.REFUSE;
        LockTapQueue.Action none = LockTapQueue.Action.NONE, send = LockTapQueue.Action.SEND, revert = LockTapQueue.Action.REVERT;

        // The connection haptic just fired: a tap shows its side at once and waits for the gate.
        LockTapQueue q = new LockTapQueue();
        require(q.request(false, true, 1000) && q.shown(false) && q.waiting(), "An early lock tap shows locked immediately");
        require(q.step(1100, true, false, false, wait) == none && q.shown(false), "No motion report yet: it waits, still shown locked");
        require(q.step(1400, true, false, false, wait) == none, "An automatic write in flight: still waiting");
        require(q.step(1600, true, false, false, open) == send, "The gate opens: the remembered tap goes out once");
        q.sent();
        require(q.step(1700, true, false, true, open) == none && q.shown(false), "In flight: keep showing the requested side");
        require(q.step(1900, true, true, false, open) == none && !q.waiting() && q.shown(true), "Readback confirms: the request is done");
        require(q.takeFailure() == null, "A confirmed change has nothing to report");

        // With the gate already open the tap is sent straight away: instant switch, normal command.
        q = new LockTapQueue();
        q.request(true, false, 0);
        require(q.step(0, true, true, false, open) == send, "An open gate sends immediately");

        // The scooter refuses or never confirms: the switch goes back, with a reason.
        q = new LockTapQueue(); q.request(false, true, 0); q.step(0, true, false, false, open); q.sent();
        require(q.step(3000, true, false, false, open) == revert && !q.waiting() && Boolean.FALSE.equals(q.shown(false)), "No confirmation once the change settles: put back");
        require(q.takeFailure().contains("didn't confirm") && q.takeFailure() == null, "The reason is reported once");

        // The gate stays shut past the hold: give up rather than lock much later.
        q = new LockTapQueue(); q.request(false, true, 0);
        require(q.step(LockTapQueue.HOLD_MS, true, false, false, wait) == none, "Still inside the hold");
        require(q.step(LockTapQueue.HOLD_MS + 1, true, false, false, wait) == revert && q.takeFailure().contains("wasn't ready"), "A tap is never sent after the hold");

        // A refusing gate (riding, a warning) still sends, so the gate itself explains; a refused send clears.
        q = new LockTapQueue(); q.request(false, true, 0);
        require(q.step(10, true, false, false, refuse) == send, "Riding: let the gate refuse and say why");
        q.refused();
        require(!q.waiting() && Boolean.FALSE.equals(q.shown(false)), "A refused send puts the switch back");

        // A lost connection drops the request without sending.
        q = new LockTapQueue(); q.request(true, false, 0);
        require(q.step(100, false, true, false, wait) == revert && q.takeFailure().contains("Connection lost"), "Disconnect drops the waiting tap");

        // Tapping back before it went out cancels; nothing is sent.
        q = new LockTapQueue(); q.request(false, true, 0);
        require(q.request(false, false, 100) && !q.waiting() && q.step(200, true, false, false, open) == none, "Tapping back cancels the waiting tap");

        // Already on that side (readback arrived first), and no known state: nothing to do.
        q = new LockTapQueue(); q.request(false, true, 0);
        require(q.step(50, true, true, false, wait) == none && !q.waiting() && q.takeFailure() == null, "The scooter already reports the requested side");
        require(!new LockTapQueue().request(null, true, 0), "No known lock state: no optimistic tap");
        require(!new LockTapQueue().request(true, true, 0), "A tap to the shown side changes nothing");

        // One command at a time: further taps wait for the readback.
        q = new LockTapQueue(); q.request(false, true, 0); q.step(0, true, false, false, open); q.sent();
        require(!q.request(false, false, 10) && q.shown(false), "A tap during the sent change is ignored");
        // The gate opens only after the hold: the tap is put back, never sent late.
        q = new LockTapQueue(); q.request(false, true, 0);
        require(q.step(4999, true, false, false, wait) == none, "Inside the hold, waiting");
        require(q.step(6000, true, false, false, open) == revert && q.takeFailure().contains("wasn't ready"), "An open gate past the hold still reverts");

        // Gate mapping from the decision once the write in flight finishes.
        CapabilityResolver.Reason moving = CapabilityResolver.Reason.MOVING, warning = CapabilityResolver.Reason.WARNING_ACTIVE;
        require(LockTapQueue.gate(true, CapabilityResolver.Reason.NONE, false) == open, "Writable now: open");
        require(LockTapQueue.gate(true, CapabilityResolver.Reason.NONE, true) == wait, "Writable once the automatic write finishes: wait");
        require(LockTapQueue.gate(false, CapabilityResolver.Reason.NO_MOTION_REPORT, true) == wait, "First motion report pending: wait");
        require(LockTapQueue.gate(false, CapabilityResolver.Reason.STATUS_STALE, false) == wait, "Fresh status pending: wait");
        require(LockTapQueue.gate(false, moving, true) == refuse && LockTapQueue.gate(false, warning, true) == refuse,
                "Riding or a warning behind a busy write is refused, not held");
        require(LockTapQueue.gate(false, moving, false) == refuse, "Riding with nothing pending: the gate refuses and says why");

        // Riding while another write is in flight: rejected at once, so stopping later can't send it.
        q = new LockTapQueue(); q.request(false, true, 0);
        require(q.reject("Stop before locking") == revert && !q.waiting() && q.takeFailure().equals("Stop before locking"), "A hard refusal drops the tap now");
        require(q.step(1000, true, false, false, open) == none, "Nothing is left to send once the rider stops");
        q = new LockTapQueue(); q.request(false, true, 0); q.step(0, true, false, false, open); q.sent();
        require(q.reject("x") == none && q.waiting(), "A sent change is left to its readback");

        System.out.println("LockTapQueueTest: " + checks + " checks passed");
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
