package com.jjricks.volt;

/** The hardware sweep's live charging hold rule. Listed in tests/l10n/live-charging-hold-check.sources. */
public final class LiveChargingHoldCheckTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static LiveChargingHoldCheck.Observed base() {
        LiveChargingHoldCheck.Observed o = new LiveChargingHoldCheck.Observed();
        o.notificationsAllowed = o.workAllowed = true;o.before.enabled = o.before.connected = true;o.before.charging = true;o.nowMs = 1_000L;
        return o;
    }
    /** Conditions.armable() must stay ChargeLiveHold.leave's rule: every combination, against the real thing. */
    private static void armableMatchesTheHold() {
        for (int bits = 0; bits < 32; bits++) {
            LiveChargingHoldCheck.Conditions c = new LiveChargingHoldCheck.Conditions();
            c.enabled = (bits & 1) != 0;c.connected = (bits & 2) != 0;c.scheduled = (bits & 4) != 0;c.stoppedForCharge = (bits & 8) != 0;
            c.charging = (bits & 16) != 0 ? Boolean.TRUE : c.scheduled ? null : Boolean.FALSE;
            ChargeLiveHold hold = new ChargeLiveHold();
            if (c.stoppedForCharge) hold.stop(50, "p", 1_000L);
            boolean armed = hold.leave(2_000L, new ChargeLiveHold.Facts(c.enabled, c.connected, c.charging, c.scheduled, 50, 100, "p"));
            check(armed == c.armable(), "armable() differs from ChargeLiveHold.leave at combination " + bits);
        }
    }
    public static void main(String[] args) {
        armableMatchesTheHold();
        LiveChargingHoldCheck.Observed held = base();
        held.armed = held.effective = true;
        check(LiveChargingHoldCheck.problem(held) == null, "an effective hold with no grace is right");
        check(LiveChargingHoldCheck.evidence(held).startsWith("Hold effective"), "and is reported as effective");
        LiveChargingHoldCheck.Observed noHold = base();
        noHold.armed = noHold.effective = true;noHold.graceDeadlineMs = 11_000L;
        check(LiveChargingHoldCheck.problem(noHold) != null && LiveChargingHoldCheck.problem(noHold).contains("did not keep"), "an effective hold must not leave a grace running");
        LiveChargingHoldCheck.Observed hiddenOff = base();
        hiddenOff.armed = hiddenOff.effective = true;hiddenOff.workAllowed = false;
        check(LiveChargingHoldCheck.problem(hiddenOff) != null, "an effective hold must keep hidden work allowed");

        // Codex [P2], October 2026 review: armed internally, notifications blocked, the ordinary grace is correct.
        LiveChargingHoldCheck.Observed blocked = base();
        blocked.armed = true;blocked.notificationsAllowed = false;blocked.graceDeadlineMs = 11_000L;
        check(LiveChargingHoldCheck.problem(blocked) == null, "armed with notifications blocked is not a failure while the grace runs");
        String evidence = LiveChargingHoldCheck.evidence(blocked);
        check(evidence.startsWith("Armed but not effective") && evidence.contains("notifications blocked") && evidence.contains("ordinary 10 s grace"), evidence);
        LiveChargingHoldCheck.Observed blockedNoGrace = base();
        blockedNoGrace.armed = true;blockedNoGrace.notificationsAllowed = false;
        check(LiveChargingHoldCheck.problem(blockedNoGrace) != null, "blocked with neither hold nor grace is a failure");

        // Codex [P2], November 2026 review: the conditions held on leaving, so a hold that didn't arm is a failure.
        LiveChargingHoldCheck.Observed eligible = base();eligible.graceDeadlineMs = 11_000L;
        check(LiveChargingHoldCheck.problem(eligible) != null && LiveChargingHoldCheck.problem(eligible).contains("did not arm"), "charging, connected, on and not stopped must arm");
        LiveChargingHoldCheck.Observed eligibleScheduled = base();eligibleScheduled.before.charging = false;eligibleScheduled.before.scheduled = true;
        check(LiveChargingHoldCheck.problem(eligibleScheduled) != null, "a waiting Volt schedule must arm too");
        LiveChargingHoldCheck.Observed eligibleBlocked = base();eligibleBlocked.notificationsAllowed = false;eligibleBlocked.graceDeadlineMs = 11_000L;
        check(LiveChargingHoldCheck.problem(eligibleBlocked) != null, "blocked notifications don't excuse not arming: the hold arms regardless");
        LiveChargingHoldCheck.Observed armedNotHeld = base();armedNotHeld.armed = true;armedNotHeld.graceDeadlineMs = 11_000L;
        check(LiveChargingHoldCheck.problem(armedNotHeld) != null, "armed with notifications allowed must be in effect");

        // Not armed because the conditions weren't met: reported as observed, no guess that "the scooter was not charging".
        LiveChargingHoldCheck.Observed off = base();off.before.enabled = false;
        LiveChargingHoldCheck.Observed unavailable = base();unavailable.before.charging = null;
        LiveChargingHoldCheck.Observed unlinked = base();unlinked.before.connected = false;unlinked.before.charging = null;
        LiveChargingHoldCheck.Observed stopped = base();stopped.before.stoppedForCharge = true;
        LiveChargingHoldCheck.Observed plain = base();plain.before.charging = false;
        for (LiveChargingHoldCheck.Observed o : new LiveChargingHoldCheck.Observed[] {off, unavailable, unlinked, stopped, plain}) {
            check(LiveChargingHoldCheck.problem(o) == null, "an unarmed hold whose conditions weren't met is not a failure");
            String e = LiveChargingHoldCheck.evidence(o);
            check(e.startsWith("Not armed (") && !e.contains("was not charging"), e);
        }
        check(LiveChargingHoldCheck.evidence(off).contains("Live charging off"), "the switch is reported");
        check(LiveChargingHoldCheck.evidence(unavailable).contains("charging reading unavailable"), "no reading is reported");
        check(LiveChargingHoldCheck.evidence(unlinked).contains("scooter not connected"), "no link is reported");
        check(LiveChargingHoldCheck.evidence(stopped).contains("stopped for this charge yes"), "a Stop is reported");
        check(LiveChargingHoldCheck.evidence(plain).contains("charging reading 0"), "a 0 reading is reported as what it is");
        System.out.println("LiveChargingHoldCheckTest: " + checks + " checks passed");
    }
}
