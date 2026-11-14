package com.jjricks.volt;

/**
 * Release this scooter's quick drop (OwnerRelease.afterDrop, owner's pick, Nov 14, 2026): the UT5 MAX answers the
 * release by ending the link about 88 ms after it, with no status reply. A drop the scooter makes within a second of
 * the send counts as released; any other ending after the send holds Volt's reconnects to it (OwnerRelease.holds), and
 * an ending before the send changes nothing. Synthesized times and codes only; nothing here talks to a scooter.
 */
public final class OwnerReleaseDropTest {
    private static int checks;
    private static final long SENT = 500000;
    /** Android's GATT statuses for a link the other side ended: 19 (remote user terminated) and 257 (the UT5 MAX's). */
    private static final int REMOTE_ENDED = 19, UT5_MAX_DROP = 257;

    public static void main(String[] args) {
        quickDrop();
        notQuick();
        notSent();
        holds();
        System.out.println("OwnerReleaseDropTest: " + checks + " checks");
    }

    private static OwnerRelease.Outcome drop(int code, long afterMs) {
        return OwnerRelease.afterDrop(true, true, true, code, SENT, SENT + afterMs);
    }

    /** The scooter ends the link within QUICK_DROP_MS of the send: released, as a status-0 reply. */
    private static void quickDrop() {
        check(OwnerRelease.QUICK_DROP_MS == 1000, "the quick-drop window is one second");
        check(drop(UT5_MAX_DROP, 88) == OwnerRelease.Outcome.RELEASED, "the UT5 MAX's drop 88 ms after the send is released");
        for (long after : new long[] {0, 1, 500, 999, 1000})
            check(drop(UT5_MAX_DROP, after) == OwnerRelease.Outcome.RELEASED, "a drop " + after + " ms after the send is released");
        for (int code : new int[] {0, REMOTE_ENDED, 133, UT5_MAX_DROP})
            check(drop(code, 88) == OwnerRelease.Outcome.RELEASED, "code " + code + " ends the link from the scooter's side: released");
        check(OwnerRelease.forgetsProfile(drop(UT5_MAX_DROP, 88)), "a quick drop removes the profile");
        check(!OwnerRelease.holds(drop(UT5_MAX_DROP, 88)), "and holds nothing: the profile is gone");
    }

    /** A later drop, a lost link, a link the phone ended, Bluetooth off or no drop at all: not confirmed. */
    private static void notQuick() {
        for (long after : new long[] {1001, 1500, 3200, 6499, 60000})
            check(drop(UT5_MAX_DROP, after) == OwnerRelease.Outcome.NOT_CONFIRMED, "a drop " + after + " ms after the send isn't confirmed");
        check(OwnerRelease.LINK_LOST == 8 && OwnerRelease.PHONE_ENDED == 22, "Android's codes for a lost link and a phone-ended one");
        check(drop(OwnerRelease.LINK_LOST, 88) == OwnerRelease.Outcome.NOT_CONFIRMED, "a supervision timeout is a lost link, never the scooter's answer");
        check(drop(OwnerRelease.PHONE_ENDED, 88) == OwnerRelease.Outcome.NOT_CONFIRMED, "a link this phone ended is never the scooter's answer");
        check(OwnerRelease.afterDrop(true, true, false, UT5_MAX_DROP, SENT, SENT + 88) == OwnerRelease.Outcome.NOT_CONFIRMED,
                "Bluetooth turned off on the phone is never the scooter's answer, however quick");
        check(OwnerRelease.afterDrop(true, false, true, UT5_MAX_DROP, SENT, SENT + 88) == OwnerRelease.Outcome.NOT_CONFIRMED,
                "a timeout, failed write or Volt's own close isn't a drop");
        check(drop(UT5_MAX_DROP, -1) == OwnerRelease.Outcome.NOT_CONFIRMED, "a drop timed before the send (clock trouble) isn't confirmed");
        check(drop(UT5_MAX_DROP, Long.MIN_VALUE / 2) == OwnerRelease.Outcome.NOT_CONFIRMED, "nor one far before it");
        for (long after : new long[] {1001, 6500}) {
            OwnerRelease.Outcome o = drop(UT5_MAX_DROP, after);
            check(!OwnerRelease.forgetsProfile(o) && OwnerRelease.holds(o), "a late drop keeps the profile and holds its reconnects");
        }
        check(OwnerRelease.holds(OwnerRelease.unanswered(true)), "the confirmation timeout holds its reconnects too");
    }

    /** A drop before the frame went out: nothing reached the scooter, so nothing is held and Volt reconnects as usual. */
    private static void notSent() {
        for (int code : new int[] {0, OwnerRelease.LINK_LOST, OwnerRelease.PHONE_ENDED, UT5_MAX_DROP})
            for (long after : new long[] {0, 88, 5000})
                check(OwnerRelease.afterDrop(false, true, true, code, SENT, SENT + after) == OwnerRelease.Outcome.NOT_SENT,
                        "never sent (code " + code + ", " + after + " ms): not sent");
        check(OwnerRelease.afterDrop(false, false, false, UT5_MAX_DROP, SENT, SENT) == OwnerRelease.Outcome.NOT_SENT, "Bluetooth off before the send: not sent");
        check(!OwnerRelease.holds(OwnerRelease.Outcome.NOT_SENT) && !OwnerRelease.forgetsProfile(OwnerRelease.Outcome.NOT_SENT), "not sent: no hold, profile kept");
    }

    /** Only a release that went out unconfirmed holds reconnects; a refusal is an answer, so nothing is held. */
    private static void holds() {
        for (OwnerRelease.Outcome o : OwnerRelease.Outcome.values())
            check(OwnerRelease.holds(o) == (o == OwnerRelease.Outcome.NOT_CONFIRMED), o + (o == OwnerRelease.Outcome.NOT_CONFIRMED ? " holds" : " holds nothing"));
        check(!OwnerRelease.holds(OwnerRelease.reply(1)), "a refusal holds nothing");
        check(!OwnerRelease.holds(OwnerRelease.reply(0)), "a status-0 reply holds nothing: the profile is gone");
        for (OwnerRelease.Outcome o : OwnerRelease.Outcome.values())
            check(!(OwnerRelease.holds(o) && OwnerRelease.forgetsProfile(o)), o + " never both removes and holds");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
