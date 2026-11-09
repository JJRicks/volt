package com.jjricks.volt;

/** The hardware sweep's live charging hold rule. Listed in tests/l10n/live-charging-hold-check.sources. */
public final class LiveChargingHoldCheckTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static LiveChargingHoldCheck.Observed base() {
        LiveChargingHoldCheck.Observed o = new LiveChargingHoldCheck.Observed();
        o.enabled = o.notificationsAllowed = o.connected = o.workAllowed = true;o.charging = true;o.nowMs = 1_000L;
        return o;
    }
    public static void main(String[] args) {
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

        // Not armed: conditions reported, no guess that "the scooter was not charging".
        LiveChargingHoldCheck.Observed off = base();off.enabled = false;
        LiveChargingHoldCheck.Observed unavailable = base();unavailable.charging = null;
        LiveChargingHoldCheck.Observed unlinked = base();unlinked.connected = false;unlinked.charging = null;
        LiveChargingHoldCheck.Observed stopped = base();stopped.stoppedForCharge = true;
        LiveChargingHoldCheck.Observed plain = base();plain.charging = false;
        for (LiveChargingHoldCheck.Observed o : new LiveChargingHoldCheck.Observed[] {off, unavailable, unlinked, stopped, plain}) {
            check(LiveChargingHoldCheck.problem(o) == null, "an unarmed hold is never a failure of the hold");
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
