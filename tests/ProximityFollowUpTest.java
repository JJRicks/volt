package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;

/** Proximity unlock after a Bluetooth restart, and the unpaired prompts (ProximityFollowUp). Never a write. */
public final class ProximityFollowUpTest {
    private static int checks;
    private static final String SCOOTER = "profile-a", SERIAL = "Q2418ZZ9ES7777777";
    private static final MutationGate.Request ENABLE = new MutationGate.Request(MutationGate.Kind.PROXIMITY, "proximity_key", 1, MutationGate.Origin.USER);
    private static final MutationGate.Request DISABLE = new MutationGate.Request(MutationGate.Kind.PROXIMITY, "proximity_key", 0, MutationGate.Origin.USER);

    public static void main(String[] args) {
        dropAfterEnable();
        confirmAfterReconnect();
        unacknowledgedThenDrop();
        noRecheck();
        pairedPrompt();
        offNeverBlocked();
        pairingEnded();
        System.out.println("ProximityFollowUpTest: " + checks + " checks passed");
    }

    private static ProximityFollowUp.Recheck drop(MutationGate.Request r, boolean ack, long now, List<Boolean> done) {
        return ProximityFollowUp.afterDrop("proximity_key", true, false, true, SCOOTER, SERIAL, r, r.value, ack, now - 300, now,
                done == null ? null : done::add);
    }

    /** The scooter took 0x61 01, acknowledged it and dropped the link: a recheck, not a cancellation. */
    private static void dropAfterEnable() {
        List<Boolean> done = new ArrayList<>();
        ProximityFollowUp.Recheck r = drop(ENABLE, true, 10_000, done);
        check(r != null && r.target == 1 && r.ack, "A sent enable that drops waits for the reconnect");
        check(r.completion != null && done.isEmpty(), "Its completion is carried, not fired with false at the drop");
        check(!ProximityFollowUp.RECONNECTING_RESULT.toLowerCase().contains("cancel") && ProximityFollowUp.RECONNECTING_RESULT.contains("Reconnecting to confirm"),
                "The row says it is confirming, never cancelled");
        check(ProximityFollowUp.RECONNECTING_NOTICE.contains("restarted Bluetooth") && ProximityFollowUp.RECONNECTING_NOTICE.contains("reconnecting to confirm"),
                "The notice says the scooter restarted Bluetooth and Volt reconnects to confirm");
        check(CommandLog.closed(true, true) == CommandLog.Outcome.UNCONFIRMED, "The drop itself is logged as acknowledged but unconfirmed");
        check(ProximityFollowUp.sectionNote(true, false, null, false, true, false) == ProximityFollowUp.RECONNECTING_NOTE,
                "The Proximity section shows the reconnect while it lasts");
    }

    private static void confirmAfterReconnect() {
        ProximityFollowUp.Recheck r = drop(ENABLE, true, 10_000, null);
        // Value matches.
        ProximityFollowUp.Verdict v = r.judge(SCOOTER, SERIAL, 1, 14_000);
        check(v == ProximityFollowUp.Verdict.CONFIRMED && r.success(v), "Reads on after reconnecting: confirmed");
        check(r.outcome(v) == CommandLog.Outcome.READBACK_SAME && r.result(v, 1).startsWith("Confirmed"), "Logged as a matching readback; row says confirmed");
        check("Proximity unlock on, confirmed.".equals(r.notice(v)), "Notice says it is on and confirmed");
        // A command event with that readback is valid for the log.
        new CommandLog.Command(1, CommandLog.Kind.SET, MutationGate.Origin.USER, "proximity_key", 1, r.outcome(v), -1, null, 1, 4300);

        // Value does not match.
        v = r.judge(SCOOTER, SERIAL, 0, 14_000);
        check(v == ProximityFollowUp.Verdict.NOT_CONFIRMED && !r.success(v), "Reads off after reconnecting: not confirmed");
        check(r.outcome(v) == CommandLog.Outcome.READBACK_OTHER && r.result(v, 0).contains("Not confirmed") && r.result(v, 0).contains("reads off"),
                "Logged as another readback; row says what the scooter reads");
        check("Proximity unlock change not confirmed".equals(r.notice(v)), "Notice says not confirmed");
        new CommandLog.Command(1, CommandLog.Kind.SET, MutationGate.Origin.USER, "proximity_key", 1, r.outcome(v), -1, null, 0, 4300);

        // Field missing on the reconnect.
        v = r.judge(SCOOTER, SERIAL, null, 14_000);
        check(v == ProximityFollowUp.Verdict.UNREADABLE && r.outcome(v) == null && !r.success(v) && r.result(v, null).startsWith("Not confirmed"),
                "No proximity reading: not confirmed, no readback logged");

        // Marker expired.
        check(!r.expired(10_000 + ProximityFollowUp.RECHECK_WINDOW_MS) && r.expired(10_001 + ProximityFollowUp.RECHECK_WINDOW_MS), "The window is bounded");
        v = r.judge(SCOOTER, SERIAL, 1, 10_001 + ProximityFollowUp.RECHECK_WINDOW_MS);
        check(v == ProximityFollowUp.Verdict.EXPIRED && !r.success(v) && r.outcome(v) == null, "A late reconnect never confirms an expired marker");
        check(r.result(v, null).contains("did not reconnect in time") && r.notice(v) != null, "Expiry says the change was not confirmed");
        check(r.judge(SCOOTER, SERIAL, 1, 9_000) == ProximityFollowUp.Verdict.EXPIRED, "A clock that went backwards expires it too");

        // Different scooter: never carried over.
        v = r.judge("profile-b", "Q2418ZZ9ES8888888", 1, 12_000);
        check(v == ProximityFollowUp.Verdict.OTHER_SCOOTER && !r.success(v) && r.outcome(v) == null && r.result(v, 1) == null && r.notice(v) == null,
                "Another profile: dropped silently, nothing logged or shown there");
        check(r.judge(SCOOTER, "Q2418ZZ9ES8888888", 1, 12_000) == ProximityFollowUp.Verdict.OTHER_SCOOTER, "Another serial on the same profile: dropped");
        check(r.judge(null, SERIAL, 1, 12_000) == ProximityFollowUp.Verdict.OTHER_SCOOTER, "No profile: dropped");

        // Turning it off and dropping works the same way.
        ProximityFollowUp.Recheck off = drop(DISABLE, true, 0, null);
        check(off.judge(SCOOTER, SERIAL, 0, 3000) == ProximityFollowUp.Verdict.CONFIRMED && "Proximity unlock off, confirmed.".equals(off.notice(ProximityFollowUp.Verdict.CONFIRMED)),
                "An off change is confirmed by reading off");

        // Volt stopped connecting.
        check(r.result(ProximityFollowUp.Verdict.ABANDONED, null).startsWith("Not confirmed") && r.notice(ProximityFollowUp.Verdict.ABANDONED) == null
                && !r.success(ProximityFollowUp.Verdict.ABANDONED), "A user disconnect ends it as not confirmed, quietly");
    }

    /** Sent but not acknowledged when the link dropped: the scooter may still have taken it. */
    private static void unacknowledgedThenDrop() {
        ProximityFollowUp.Recheck r = drop(ENABLE, false, 5_000, null);
        check(r != null && !r.ack, "An unacknowledged sent change also waits for the reconnect");
        check(CommandLog.closed(true, false) == CommandLog.Outcome.INTERRUPTED, "The drop is logged as interrupted");
        check(r.judge(SCOOTER, SERIAL, 1, 7_000) == ProximityFollowUp.Verdict.CONFIRMED, "The readback still confirms it");
        check(r.judge(SCOOTER, SERIAL, 0, 7_000) == ProximityFollowUp.Verdict.NOT_CONFIRMED, "Or shows it did not take");
    }

    private static void noRecheck() {
        long t = 1000;
        check(ProximityFollowUp.afterDrop("proximity_key", false, false, true, SCOOTER, SERIAL, ENABLE, 1, false, 0, t, null) == null, "Never sent: cancelled as before");
        check(ProximityFollowUp.afterDrop("cruise_control", true, false, true, SCOOTER, SERIAL, ENABLE, 1, true, 0, t, null) == null, "Other controls keep their behavior");
        check(ProximityFollowUp.afterDrop("proximity_key", true, true, true, SCOOTER, SERIAL, ENABLE, 1, true, 0, t, null) == null, "Guided writes keep their own outcomes");
        check(ProximityFollowUp.afterDrop("proximity_key", true, false, false, SCOOTER, SERIAL, ENABLE, 1, true, 0, t, null) == null, "No reconnect follows: nothing to wait for");
        check(ProximityFollowUp.afterDrop("proximity_key", true, false, true, "", SERIAL, ENABLE, 1, true, 0, t, null) == null, "No scooter profile: nothing to carry");
    }

    /** Proximity reads on and this phone is not paired: said plainly, Pair offered, Off available. */
    private static void pairedPrompt() {
        check(ProximityFollowUp.NOT_PAIRED_NOTE.startsWith("Proximity unlock is on, but this phone isn't paired"), "The plain wording");
        check(ProximityFollowUp.sectionNote(true, true, 1, false, false, false) == ProximityFollowUp.NOT_PAIRED_NOTE, "On and unpaired: shown");
        check(ProximityFollowUp.sectionNote(true, true, 1, true, false, false) == null, "Paired: nothing to say");
        check(ProximityFollowUp.sectionNote(true, true, 0, false, false, false) == null, "Off: nothing to say");
        check(ProximityFollowUp.sectionNote(true, false, 1, false, false, false) == null, "Disconnected: the pairing is unknown, so nothing is claimed");
        check(ProximityFollowUp.sectionNote(false, true, 1, false, false, false) == null, "Not the owner: no prompt");
        check(ProximityFollowUp.sectionNote(true, true, null, false, false, false) == null, "Not reported: no prompt");
        check(ProximityFollowUp.pairEnabled(true, false, false, false, 1), "Pair is offered while proximity is on, even before the PIN state is read");
        check(ProximityFollowUp.pairEnabled(true, false, false, true, 0) && !ProximityFollowUp.pairEnabled(true, false, false, false, 0), "Otherwise pairing follows the PIN step");
        check(!ProximityFollowUp.pairEnabled(true, false, true, true, 1) && !ProximityFollowUp.pairEnabled(true, true, false, true, 1) && !ProximityFollowUp.pairEnabled(false, false, false, true, 1),
                "Not when already paired, busy, or not the owner");
    }

    private static void offNeverBlocked() {
        for (boolean paired : new boolean[]{false, true}) for (boolean pin : new boolean[]{false, true})
            check(ProximityFollowUp.switchEnabled(true, false, 1, paired, pin), "Off is available: paired=" + paired + " pin=" + pin);
        check(!ProximityFollowUp.switchEnabled(true, false, 0, false, true) && !ProximityFollowUp.switchEnabled(true, false, 0, true, false)
                && ProximityFollowUp.switchEnabled(true, false, 0, true, true), "On still needs the pairing and the PIN");
        check(!ProximityFollowUp.switchEnabled(true, true, 1, false, false) && !ProximityFollowUp.switchEnabled(false, false, 1, true, true)
                && !ProximityFollowUp.switchEnabled(true, false, null, true, true), "Busy, not the owner, or unreported: disabled as before");
    }

    /** A pairing attempt ended without a bond while proximity reads on: said, with Pair again and Off. */
    private static void pairingEnded() {
        check(ProximityFollowUp.pairingEndedNotice(true, 1) == ProximityFollowUp.PAIRING_ENDED_NOTE, "Attempt ended, proximity on: say so");
        check(ProximityFollowUp.PAIRING_ENDED_NOTE.contains("Pair again") && ProximityFollowUp.PAIRING_ENDED_NOTE.contains("turn proximity unlock off"), "Both offers are named");
        check(ProximityFollowUp.pairingEndedNotice(true, 0) == ProximityFollowUp.PAIRING_INACTIVE, "Proximity off: the usual message");
        check(ProximityFollowUp.pairingEndedNotice(false, 1) == ProximityFollowUp.PAIRING_INACTIVE, "A bond removed elsewhere is not a failed attempt");
        check(ProximityFollowUp.pairingEndedNotice(true, null) == ProximityFollowUp.PAIRING_INACTIVE, "Unknown proximity (link down): no claim");
        check(ProximityFollowUp.sectionNote(true, true, 1, false, false, true) == ProximityFollowUp.PAIRING_ENDED_NOTE, "The section keeps the pairing outcome");
        check(ProximityFollowUp.switchEnabled(true, false, 1, false, true) && ProximityFollowUp.pairEnabled(true, false, false, true, 1), "Off and Pair again are both available");
        check(ProximityFollowUp.pairedNotice(1).equals("Phone paired. Proximity unlock is on.") && ProximityFollowUp.pairedNotice(0).contains("You can now enable"), "Paired notices");
    }

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
}
