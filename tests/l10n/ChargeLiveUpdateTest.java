package com.jjricks.volt;

import com.jjricks.volt.ChargeLiveUpdate.Role;
import com.jjricks.volt.ChargeLiveUpdate.Status;

/**
 * Live charging (charging-live plan, PR 2): the Live Update bar, chip and promotion decisions in ChargeLiveUpdate.
 * Listed in tests/l10n/charge-live-update.sources; tests/run.sh runs it.
 */
public final class ChargeLiveUpdateTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        bar();
        promote();
        status();
        reopen();
        System.out.println("ChargeLiveUpdateTest: " + checks + " checks passed");
    }

    private static ChargeLiveState live(Integer pct, Integer limit, boolean waiting, boolean reconnecting) {
        ChargeLiveState.Input in = new ChargeLiveState.Input();
        in.percent = pct;in.limit = limit;in.minutesLeft = 30.0;in.scooter = "UT5 MAX";in.nowWall = 1_000_000L;
        in.waiting = waiting;in.startWallMs = 2_000_000L;in.reconnecting = reconnecting;
        return ChargeLiveState.of(in, t -> "3:40 AM");
    }
    private static int total(ChargeLiveUpdate u) { int sum = 0; for (ChargeLiveUpdate.Segment s : u.segments) sum += s.length; return sum; }

    private static void bar() {
        ChargeLiveUpdate capped = ChargeLiveUpdate.of(live(72, 80, false, false), null);
        check(total(capped) == ChargeLiveUpdate.MAX, "segments fill the bar");
        check(capped.segments.size() == 2 && capped.segments.get(0).length == 80 && capped.segments.get(0).role == Role.CHARGING
                && capped.segments.get(1).length == 20 && capped.segments.get(1).role == Role.PAST_LIMIT, "red to the limit, grey past it");
        check(capped.points.size() == 1 && capped.points.get(0).position == 80 && capped.points.get(0).role == Role.LIMIT, "a point at the limit");
        check(capped.progress == 72 && !capped.indeterminate && "72%".equals(capped.shortCritical), "the tracker at the level, the chip's percent");
        check(ChargeLiveUpdate.of(live(72, 80, false, false), 50).points.size() == 1, "a charge limit wins over an alert point");

        ChargeLiveUpdate full = ChargeLiveUpdate.of(live(63, null, false, false), null);
        check(full.segments.size() == 1 && full.segments.get(0).length == 100 && full.points.isEmpty(), "no limit: one segment, no points");
        ChargeLiveUpdate alert = ChargeLiveUpdate.of(live(63, null, false, false), 90);
        check(alert.points.size() == 1 && alert.points.get(0).position == 90 && alert.points.get(0).role == Role.ALERT, "a battery alert's level as a point");
        check(ChargeLiveUpdate.of(live(63, null, false, false), 100).points.isEmpty()
                && ChargeLiveUpdate.of(live(63, null, false, false), 0).points.isEmpty(), "no point at either end");
        check(ChargeLiveUpdate.of(live(63, 100, false, false), null).segments.size() == 1, "a 100% limit is no limit");

        check(ChargeLiveUpdate.of(live(41, 80, true, false), null).segments.get(0).role == Role.HELD, "waiting: orange");
        check(ChargeLiveUpdate.of(live(72, 80, false, true), null).segments.get(0).role == Role.STALE, "out of reach: greyed");

        ChargeLiveUpdate none = ChargeLiveUpdate.of(live(null, 80, false, false), null);
        check(none.indeterminate && none.progress == 0 && none.shortCritical == null, "no level: indeterminate, no chip text");
        ChargeLiveUpdate over = ChargeLiveUpdate.of(live(85, 80, false, false), null);
        check(over.progress == 85 && "85%".equals(over.shortCritical), "past the limit the tracker shows the real level");
    }

    private static void promote() {
        check(ChargeLiveUpdate.promote(36, true, true, false), "Android 16, a request, allowed: asks");
        check(ChargeLiveUpdate.promote(37, true, true, false), "later Androids too");
        check(!ChargeLiveUpdate.promote(35, true, true, false), "never before Android 16");
        check(!ChargeLiveUpdate.promote(36, false, true, false), "no request on this Android: plain card");
        check(!ChargeLiveUpdate.promote(36, true, false, false), "not allowed: plain card");
        check(!ChargeLiveUpdate.promote(36, true, true, true), "a charge recording keeps the plain card's summary");
    }

    private static void status() {
        check(ChargeLiveUpdate.status(35, true, true, true, true, true, Status.PROMOTED) == Status.OLD_ANDROID, "old Android first");
        check(ChargeLiveUpdate.status(36, false, true, true, true, true, null) == Status.UNAVAILABLE, "no request");
        check(ChargeLiveUpdate.status(36, true, false, true, true, true, Status.PROMOTED) == Status.NOT_ALLOWED, "turned off in settings beats the last outcome");
        check(ChargeLiveUpdate.status(36, true, true, false, false, false, null) == Status.READY, "allowed, nothing shown yet");
        check(ChargeLiveUpdate.status(36, true, true, true, true, true, null) == Status.PROMOTED, "promoted");
        check(ChargeLiveUpdate.status(36, true, true, true, false, true, null) == Status.NOT_PROMOTED, "promotable but Android declined");
        check(ChargeLiveUpdate.status(36, true, true, true, false, false, null) == Status.NOT_PROMOTABLE, "not promotable");
        check(ChargeLiveUpdate.status(36, true, true, false, false, false, Status.PROMOTED) == Status.PROMOTED, "the last card's outcome while none shows");
        check(ChargeLiveUpdate.status(36, true, true, false, false, false, Status.NOT_ALLOWED) == Status.READY, "a stale refusal isn't an outcome");
        for (Status s : Status.values()) check(ChargeLiveUpdate.label(s) != null && !ChargeLiveUpdate.label(s).isEmpty(), "a label for " + s);
        check("Promoted".equals(ChargeLiveUpdate.label(Status.PROMOTED)), "English label");
    }

    // ---- Round 2: the verdict outlives the card (opening Volt ends the hold before the row can be opened) ----

    /**
     * The service's order of calls: notification() builds the card (built), notifyNow posts it and the probe reads it
     * back (observed), opening Volt captures once more and ends the hold (applyVisibility(true): observed, then
     * chargeLive.shown()), and the next notice is the plain connection notice (built(false, ...)). The row is read last.
     */
    private static void reopen() {
        ChargeLiveUpdate.Last none = new ChargeLiveUpdate.Last();
        check(none.row(36, true, true) == Status.READY && none.status() == null, "before any card: Allowed, shows with the next card");

        ChargeLiveUpdate.Last promoted = new ChargeLiveUpdate.Last();
        promoted.built(true, true);
        check(promoted.observed(true, 1000L), "the probe after the background post reads FLAG_PROMOTED_ONGOING");
        check(!promoted.observed(true, 2000L), "the capture just before the hold ends reads the same: no store");
        promoted.built(false, false);
        check(!promoted.observed(false, 3000L), "the plain notice after reopening never overwrites the verdict");
        check(promoted.row(36, true, true) == Status.PROMOTED && promoted.wallMs() == 2000L, "reopen, Developer options: Promoted");

        ChargeLiveUpdate.Last declined = new ChargeLiveUpdate.Last();
        declined.built(true, true);declined.observed(false, 1000L);declined.built(false, false);
        check(declined.row(36, true, true) == Status.NOT_PROMOTED, "reopen after Android declined a promotable card: Not promoted by Android");

        ChargeLiveUpdate.Last refused = new ChargeLiveUpdate.Last();
        refused.built(true, false);refused.observed(false, 1000L);refused.built(false, false);
        check(refused.row(36, true, true) == Status.NOT_PROMOTABLE, "reopen after an unpromotable card: Not promotable");
        check(refused.row(36, true, false) == Status.NOT_ALLOWED, "Live updates turned off since: Not allowed beats the old verdict");

        ChargeLiveUpdate.Last later = new ChargeLiveUpdate.Last();
        later.built(true, true);later.observed(false, 1000L);
        later.built(true, true);later.observed(true, 5000L);
        check(later.row(36, true, true) == Status.PROMOTED, "a later card's verdict replaces an earlier one");

        ChargeLiveUpdate.Last recording = new ChargeLiveUpdate.Last();
        recording.built(true, true);recording.observed(true, 1000L);
        recording.built(false, false);
        check(!recording.observed(false, 2000L) && recording.row(36, true, true) == Status.PROMOTED, "a charge recording's plain card doesn't count");

        ChargeLiveUpdate.Last restored = new ChargeLiveUpdate.Last();
        restored.restore(Status.PROMOTED, 1234L);
        check(restored.row(36, true, true) == Status.PROMOTED && restored.wallMs() == 1234L, "a new service restores the stored verdict");
        ChargeLiveUpdate.Last odd = new ChargeLiveUpdate.Last();
        odd.restore(Status.NOT_ALLOWED, 1L);odd.restore(Status.READY, 1L);
        check(odd.status() == null && odd.row(36, true, true) == Status.READY, "only a card verdict is restored");
        check(!new ChargeLiveUpdate.Last().observed(true, 1L), "no reading counts before a card asked");
    }
}
