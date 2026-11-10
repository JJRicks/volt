package com.jjricks.volt;

/** Which ends of a link count, and when riding mode shows Home for them. All times are synthetic monotonic ms. */
public final class RidingLinkTest {
    private static int checks;
    private static final long T = 5_000_000L, GRACE = RidingLink.PAUSE_GRACE_MS;

    public static void main(String[] args) {
        check(GRACE == 120_000, "the grace is 2 minutes");
        log();
        ownPause();
        realDrop();
        processDeath();
        clock();
        recording();
        activityRecreated();
        returnThenLeaveAgain();
        badState();
        System.out.println("RidingLinkTest: " + checks + " checks passed (which ends count, Volt's pause, real drops, process death, clock change, recordings, recreation, return and leave again, bad saved state)");
    }

    private static RidingLink.Log up() { RidingLink.Log log = new RidingLink.Log(7); log.observe(true, false); return log; }
    private static boolean lost(RidingLink.Leave leave, RidingLink.Log log, long at) { return RidingLink.lost(true, leave, log.now(), false, at); }
    private static RidingLink.Leave leave(RidingLink.Log log) { return new RidingLink.Leave(log.now(), T); }

    private static void log() {
        RidingLink.Log log = new RidingLink.Log(7);
        check(!log.now().connected(), "nothing has connected yet");
        log.observe(false, false);
        log.observe(true, false);
        check(log.now().connected(), "up");
        // A second look while already down changes nothing, so the pause's own later changed() calls can't count twice.
        RidingLink.Log paused = up();
        paused.observe(false, true);
        RidingLink.Leave before = new RidingLink.Leave(paused.now(), T);
        paused.observe(false, false);
        paused.observe(false, false);
        check(!lost(before, paused, T + 1000), "only the fall itself is classified: later looks while down add nothing");
        // A pause while a link wasn't up isn't a fall.
        RidingLink.Log down = new RidingLink.Log(7);
        down.observe(false, true);
        RidingLink.Leave leftDown = new RidingLink.Leave(down.now(), T);
        check(RidingLink.lost(true, leftDown, down.now(), false, T + 1000), "suspending a link that never came up isn't a pause of one");
        // Reconnecting clears the pause, so a later real drop is a real drop.
        RidingLink.Log again = up();
        again.observe(false, true);
        again.observe(true, false);
        RidingLink.Leave leftUp = new RidingLink.Leave(again.now(), T);
        again.observe(false, false);
        check(lost(leftUp, again, T + 1000), "a real drop after a pause and a reconnect counts at once");
    }

    private static void ownPause() {
        RidingLink.Log log = up();
        RidingLink.Leave left = leave(log);
        log.observe(false, true);
        check(!lost(left, log, T + 11_000), "paused at 10 s, back at 11 s: stays");
        check(!lost(left, log, T + GRACE - 1), "one millisecond inside the grace: stays");
        check(lost(left, log, T + GRACE), "at 2 minutes it counts");
        check(lost(left, log, T + 3_600_000), "an hour away counts");
        // The owner's case: the pause happened, and the scooter is back by the return all the same.
        log.observe(true, false);
        check(!lost(left, log, T + 30_000), "paused and already reconnected, inside the grace: stays");
        check(lost(left, log, T + GRACE), "paused and reconnected, past the grace: still counts");
        // No pause at all: a long leave with the link up (Stay connected on) is nothing.
        RidingLink.Log stayed = up();
        check(!lost(leave(stayed), stayed, T + 3_600_000), "connected throughout, an hour away: stays");
        // Under the pause's deadline nothing has happened yet.
        RidingLink.Log quick = up();
        check(!lost(leave(quick), quick, T + 5000), "back before the pause: stays");
    }

    private static void realDrop() {
        RidingLink.Log log = up();
        RidingLink.Leave left = leave(log);
        log.observe(false, false);
        check(lost(left, log, T + 1), "a real drop while away counts at once");
        log.observe(true, false);
        check(lost(left, log, T + 1), "even if it has reconnected by the return");
        // Down for real before the leave.
        RidingLink.Log down = up();
        down.observe(false, false);
        RidingLink.Leave leftDown = leave(down);
        check(lost(leftDown, down, T + 1), "dropped and still down when Volt left: counts");
        down.observe(true, false);
        check(lost(leftDown, down, T + 1), "and it stays counted with the link back");
        // On screen and reconnected before the leave.
        RidingLink.Log blip = up();
        blip.observe(false, false);
        blip.observe(true, false);
        check(!lost(leave(blip), blip, T + 1), "a drop and reconnect on screen counts for nothing");
        // A real drop inside the pause's 10 seconds, then the pause: the drop decides.
        RidingLink.Log both = up();
        RidingLink.Leave leftBoth = leave(both);
        both.observe(false, false);
        both.observe(false, true);
        check(lost(leftBoth, both, T + 12_000), "a real drop before the pause counts, though the pause followed");
        // Never seen connected: no rule applies.
        RidingLink.Log unseen = up();
        RidingLink.Leave leftUnseen = leave(unseen);
        unseen.observe(false, false);
        check(!RidingLink.lost(false, leftUnseen, unseen.now(), false, T + 1), "a riding mode that hasn't seen a connection stays");
        check(!RidingLink.lost(true, null, unseen.now(), false, T + 1), "on screen there's no leave to judge");
    }

    private static void processDeath() {
        RidingLink.Log before = up();
        RidingLink.Leave left = leave(before);
        RidingLink.Log after = new RidingLink.Log(8);
        check(lost(left, after, T + 1), "a new process counts, however soon the rider is back");
        after.observe(true, false);
        check(lost(left, after, T + 1), "and with a link up already");
        check(lost(left, after, T - 500_000), "after a reboot the monotonic clock restarts: still the new process");
        RidingLink.Log paused = up();
        paused.observe(false, true);
        check(lost(new RidingLink.Leave(paused.now(), T), new RidingLink.Log(8), T + 1000), "a pause that a process death followed counts");
    }

    private static void clock() {
        RidingLink.Log log = up();
        RidingLink.Leave left = leave(log);
        log.observe(false, true);
        // The only input is the monotonic pair: there is no wall-clock parameter for a change to move.
        check(!lost(left, log, T + 60_000), "a wall clock changed by days can't matter: 60 s of monotonic time is 60 s");
        check(lost(left, log, T - 1), "a monotonic clock that ran backwards can only be a reboot: counts");
    }

    private static void recording() {
        RidingLink.Log log = up();
        RidingLink.Leave left = leave(log);
        log.observe(false, false);
        check(!RidingLink.lost(true, left, log.now(), true, T + 1), "a recording still running when the connection is lost: stays");
        check(RidingLink.lost(true, left, log.now(), false, T + 1), "the same return with the recording ended while away: Home");
        RidingLink.Log paused = up();
        RidingLink.Leave leftPaused = leave(paused);
        paused.observe(false, true);
        check(!RidingLink.lost(true, leftPaused, paused.now(), true, T + GRACE), "past the grace, a recording still holds it");
        check(RidingLink.lost(true, leftPaused, paused.now(), false, T + GRACE), "a recording stopped while away, past the grace: Home");
        check(!RidingLink.lost(true, leftPaused, paused.now(), false, T + 30_000), "inside the grace it's still only a pause");
        check(!RidingLink.lost(true, leave(paused), paused.now(), true, T + 1), "process-side: a running recording wins over a down link at the leave");
        check(!RidingLink.lost(true, left, new RidingLink.Log(8).now(), true, T + 1), "and over a process death");
    }

    private static void activityRecreated() {
        RidingLink.Log log = up();
        RidingLink.Leave saved = RidingLink.Leave.of(leave(log).state());
        log.observe(false, true);
        check(!lost(saved, log, T + GRACE - 1), "the saved leave's time away carries through a recreation");
        check(lost(saved, log, T + GRACE), "past the grace, through a recreation");
        // A rotation on screen never makes a leave: nothing saved, nothing to judge.
        check(RidingLink.Leave.of(null) == null, "no leave saved");
        check(!RidingLink.lost(true, RidingLink.Leave.of(null), log.now(), false, T + 3_600_000), "a rotation has no leave, however long");
    }

    private static void returnThenLeaveAgain() {
        // Back within 2 minutes, link still paused, off again: a fresh 2 minutes from the second leave.
        RidingLink.Log log = up();
        log.observe(false, true);
        long second = T + 100_000;
        RidingLink.Leave again = new RidingLink.Leave(log.now(), second);
        check(!lost(again, log, second + GRACE - 1), "a second leave with the pause still in force gets its own 2 minutes");
        check(lost(again, log, second + GRACE), "and past them it counts, with no new pause to observe");
        // The rider came back and it reconnected: an ordinary leave again.
        log.observe(true, false);
        RidingLink.Leave fresh = new RidingLink.Leave(log.now(), second);
        check(!lost(fresh, log, second + 3_600_000), "reconnected before the second leave: nothing pending");
        log.observe(false, true);
        check(lost(fresh, log, second + GRACE), "and a new pause runs on the new leave's time");
    }

    private static void badState() {
        check(RidingLink.Leave.of(new long[4]) == null, "wrong shape");
        check(RidingLink.Leave.of(new long[]{1, 0, 0, 3, 0}) == null, "unknown state");
        check(RidingLink.Leave.of(new long[]{1, 0, 0, -1, 0}) == null, "negative state");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
