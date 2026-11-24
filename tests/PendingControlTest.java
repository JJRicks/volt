package com.jjricks.volt;

/**
 * The pending indicator's timing (PendingControl, owner Nov 23): nothing for the first second of a wait, a spinner after
 * it, and none once the scooter confirms, the change times out or the link drops; never stuck, never announced twice.
 */
public final class PendingControlTest {
    private static int checks;

    public static void main(String[] args) {
        long t0 = 50_000;

        // A fast scooter: confirmed inside the second, the spinner never shows (no flicker).
        PendingControl p = new PendingControl();
        p.look(true, t0, t0);
        require(!p.showing(t0), "No spinner at the tap");
        require(!p.showing(t0 + 999), "No spinner under 1 s");
        require(p.nextLookIn(t0 + 400) == 600, "Look again when the second is up");
        p.look(false, 0, t0 + 300);
        require(!p.showing(t0 + 1000) && !p.showing(t0 + 5000), "A quick confirm shows nothing, then or later");
        require(p.nextLookIn(t0 + 1000) == -1, "Nothing to look at once confirmed");

        // A slow scooter: the spinner starts at 1 s and stops on the confirmation.
        p = new PendingControl();
        p.look(true, t0, t0);
        p.look(true, t0, t0 + 1000);
        require(p.showing(t0 + 1000), "The spinner shows at 1 s");
        require(p.showing(t0 + 4000), "And keeps showing while the scooter hasn't answered");
        p.look(false, 0, t0 + 4200);
        require(!p.showing(t0 + 4200), "The confirmation stops it");

        // The existing command timeout ends the wait: it stops then, at the same look.
        p = new PendingControl();
        p.look(true, t0, t0 + 6000);
        require(p.showing(t0 + 6000), "Spinning before the timeout");
        p.look(false, 0, t0 + 6500);
        require(!p.showing(t0 + 6500), "The timeout stops it");

        // A wait nobody ends (a bug elsewhere) never spins forever: the backstop is past every existing timeout.
        require(PendingControl.LIMIT_MS > LockTapQueue.HOLD_MS + 3500 + 6500, "The backstop is past the lock's longest wait");
        p = new PendingControl();
        p.look(true, t0, t0);
        require(p.showing(t0 + PendingControl.LIMIT_MS - 1), "Still spinning just inside the backstop");
        require(!p.showing(t0 + PendingControl.LIMIT_MS), "Stopped at the backstop");
        require(p.nextLookIn(t0 + 2000) == PendingControl.LIMIT_MS - 2000, "A look is due at the backstop");
        require(p.nextLookIn(t0 + PendingControl.LIMIT_MS) == -1, "Nothing due after it");

        // A disconnect mid-wait clears it; the next wait (after a reconnect) starts its own second.
        p = new PendingControl();
        p.look(true, t0, t0 + 1500);
        require(p.showing(t0 + 1500), "Spinning when the link drops");
        p.look(false, 0, t0 + 1600);
        require(!p.showing(t0 + 1600), "The drop stops it");
        long t1 = t0 + 9000;
        p.look(true, 0, t1);
        require(!p.showing(t1 + 500), "A new wait after the drop starts from nothing");
        require(p.showing(t1 + 1000), "And spins after its own second");

        // A redrawn control (fold, unfold, theme change) given the wait's own start keeps its timing: no new second.
        PendingControl rebuilt = new PendingControl();
        rebuilt.look(true, t0, t0 + 1500);
        require(rebuilt.showing(t0 + 1500), "A rebuilt control spins at once for a wait already past 1 s");
        rebuilt = new PendingControl();
        rebuilt.look(true, t0, t0 + 300);
        require(!rebuilt.showing(t0 + 300) && rebuilt.nextLookIn(t0 + 300) == 700, "And waits out the rest of the second otherwise");

        // Without a start the first look is the start, and later looks keep it.
        p = new PendingControl();
        p.look(true, 0, t0);
        p.look(true, 0, t0 + 800);
        require(!p.showing(t0 + 800), "The first look starts the second");
        p.look(true, 0, t0 + 1000);
        require(p.showing(t0 + 1000), "Later looks don't restart it");

        // A new wait with its own start (a second tap after the first settled) restarts the second even unseen in between.
        p = new PendingControl();
        p.look(true, t0, t0 + 2000);
        require(p.showing(t0 + 2000), "First wait spinning");
        p.look(true, t0 + 2100, t0 + 2100);
        require(!p.showing(t0 + 2100), "A newer wait starts its own second");

        // A start in the future (a clock mix-up) counts from now.
        p = new PendingControl();
        p.look(true, t0 + 5000, t0);
        require(!p.showing(t0 + 999) && p.showing(t0 + 1000), "A future start is clamped to now");

        // Screen readers hear it once per wait, from one control only, however often it is redrawn.
        PendingControl.forgetAnnouncements();
        p = new PendingControl();
        p.look(true, t0, t0 + 500);
        require(!p.announce("lock", t0 + 500), "Nothing to announce before it shows");
        p.look(true, t0, t0 + 1000);
        require(p.announce("lock", t0 + 1000), "Announced when it first shows");
        require(!p.announce("lock", t0 + 1100) && !p.announce("lock", t0 + 3000), "Never again for the same wait");
        PendingControl twin = new PendingControl();
        twin.look(true, t0, t0 + 1200);
        require(!twin.announce("lock", t0 + 1200), "A second lock switch (two panes) doesn't repeat it");
        PendingControl row = new PendingControl();
        row.look(true, t0 + 100, t0 + 1200);
        require(row.announce("headlight", t0 + 1200), "Another control's wait is its own");
        p.look(false, 0, t0 + 4000);
        p.look(true, t0 + 5000, t0 + 6000);
        require(p.announce("lock", t0 + 6000), "The next wait is announced again");

        // An operation of several writes (a charge schedule: clock, time, switch): the backstop counts from the latest
        // write, so a long but healthy operation keeps its spinner; the second still counts from the operation's start.
        p = new PendingControl();
        p.look(true, t0, t0, t0 + 500);
        require(!p.showing(t0 + 500), "Still the operation's first second");
        p.look(true, t0, t0 + 12_000, t0 + 12_000);
        p.look(true, t0, 0, t0 + 13_000);
        require(p.showing(t0 + PendingControl.LIMIT_MS + 5000), "A later write moves the backstop on");
        require(p.nextLookIn(t0 + 20_000) == 12_000 + PendingControl.LIMIT_MS - 20_000, "The backstop's look is due from the latest write");
        require(!p.showing(t0 + 12_000 + PendingControl.LIMIT_MS), "Stopped a backstop after the latest write");
        p.look(true, t0, t0 + 40_000, t0 + 39_000);
        require(p.showing(t0 + 39_000), "A write start in the future counts from now");
        p.look(false, 0, 0, t0 + 41_000);
        p.look(true, t0 + 50_000, 0, t0 + 50_000);
        require(!p.showing(t0 + 50_500) && p.showing(t0 + 51_000) && !p.showing(t0 + 50_000 + PendingControl.LIMIT_MS),
                "The next operation starts with no write remembered from the last");
        p.look(true, t0 + 50_000, 0, t0 + 52_000);
        p.look(true, t0 + 60_000, 0, t0 + 60_000);
        require(!p.showing(t0 + 60_500), "A new start mid-wait forgets the old operation's writes");

        System.out.println("PendingControlTest: " + checks + " checks passed");
    }

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
