package com.jjricks.volt;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.LongFunction;

/**
 * Live charging (charging-live plan, PR 1): the hold's rules (ChargeLiveHold), the card's wording and bar
 * (ChargeLiveState), and the WorkReasons refactor, which must answer exactly as ScooterService's old
 * lists did for every existing reason. Listed in tests/l10n/charge-live.sources; tests/run.sh runs it.
 */
public final class ChargeLiveTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long S = 1000L, MIN = 60_000L, H = 60 * MIN;

    public static void main(String[] args) {
        workReasons();
        workReasonsFold();
        holdArming();
        holdDisconnect();
        holdCapAndEnd();
        holdStop();
        holdStopPerScooter();
        holdStopRestored();
        holdStopEachScooter();
        doneNoticeAnnounced();
        state();
        System.out.println("ChargeLiveTest: " + checks + " checks passed");
    }

    // ---- WorkReasons: behaviour-identical to the lists it replaced ------------------------------------

    private static void workReasons() {
        for (int bits = 0; bits < 1 << 5; bits++) {
            boolean recording = bit(bits, 0), chargeRecording = bit(bits, 1), holding = bit(bits, 2), alertHold = bit(bits, 3), phoneSaving = bit(bits, 4);
            for (boolean connected : new boolean[]{false, true}) {
                WorkReasons r = new WorkReasons(recording, chargeRecording, holding, alertHold, false, phoneSaving);
                String at = " for bits " + Integer.toBinaryString(bits) + (connected ? " connected" : "");
                // The old expressions, copied from ScooterService before the refactor.
                check(r.keepsWorking() == (recording || chargeRecording || holding || alertHold || phoneSaving), "updateRecordingWakeLock and FOREGROUND_SERVICE_IMMEDIATE" + at);
                check(r.ongoing(connected) == (recording || chargeRecording || holding || alertHold || phoneSaving || connected), "setOngoing" + at);
                // The tick's release branch runs only when not recording.
                if (!recording) check(!r.keepsWorking() == (!phoneSaving && !chargeRecording && !holding && !alertHold), "the tick's wake lock release" + at);
                // The live hold counts wherever the alert hold does.
                WorkReasons live = new WorkReasons(recording, chargeRecording, holding, alertHold, true, phoneSaving);
                check(live.keepsWorking() && live.ongoing(connected), "the live hold keeps the service like the alert hold" + at);
            }
        }
        WorkReasons none = new WorkReasons(false, false, false, false, false, false);
        check(!none.keepsWorking() && !none.ongoing(false) && none.ongoing(true), "an idle service: ongoing only while connected");
    }
    private static boolean bit(int bits, int i) { return (bits >> i & 1) == 1; }

    /**
     * The four places that once paired the old "background_connection" statement with a separate live-hold
     * statement (workAllowed, applyVisibility, backgroundStop, applyWorkPolicy). Every combination of the six
     * reasons, Volt on screen, the background connection, an automatic ride copy and a pending stop answers as
     * the old pair, copied from ScooterService at 5919dcb.
     */
    private static void workReasonsFold() {
        for (int bits = 0; bits < 1 << 10; bits++) {
            boolean recording = bit(bits, 0), chargeRecording = bit(bits, 1), holding = bit(bits, 2), alertHold = bit(bits, 3),
                    liveHold = bit(bits, 4), phoneSaving = bit(bits, 5), visible = bit(bits, 6), background = bit(bits, 7),
                    exportBusy = bit(bits, 8), deadlinePending = bit(bits, 9);
            WorkReasons r = new WorkReasons(recording, chargeRecording, holding, alertHold, liveHold, phoneSaving);
            String at = " for bits " + Integer.toBinaryString(bits);

            // workAllowed (past its destroyed and simulating returns).
            boolean oldAllowed = liveHold || (visible || recording || chargeRecording || holding || alertHold || phoneSaving
                    || exportBusy || background || deadlinePending);
            check((r.keepsServiceUp(visible, background) || exportBusy || deadlinePending) == oldAllowed, "workAllowed" + at);

            // backgroundStop: true cancels the deadline and returns.
            boolean oldStopCancels = liveHold || (visible || recording || chargeRecording || holding || alertHold || phoneSaving || background);
            check(r.keepsServiceUp(visible, background) == oldStopCancels, "backgroundStop" + at);

            // applyWorkPolicy: true cancels the deadline (the two old statements each cancelled it).
            boolean oldPolicyCancels = (visible || recording || chargeRecording || holding || alertHold || phoneSaving || background) || liveHold;
            check(r.keepsServiceUp(visible, background) == oldPolicyCancels, "applyWorkPolicy" + at);

            // applyVisibility's wasVisible branch, with value = Volt shown again or not. The old first statement armed
            // the stop unless one of its reasons held; the second then cancelled it on leaving with the live hold.
            // Arming and then cancelling leaves no stop pending, the same as cancelling.
            for (boolean value : new boolean[]{false, true}) {
                boolean oldArmed = !(recording || chargeRecording || holding || alertHold || background);
                if (!value && liveHold) oldArmed = false;
                check(!r.skipsTenSecondStop(!value, background) == oldArmed, "applyVisibility" + (value ? " shown again" : " leaving") + at);
            }

            // stopAfterChargeLive was already keepsWorking() || background and is unchanged.
        }
    }

    // ---- ChargeLiveHold ---------------------------------------------------------------------------------

    private static ChargeLiveHold.Facts facts(boolean enabled, boolean connected, Boolean charging, boolean scheduled, Integer pct, Integer limit) {
        return new ChargeLiveHold.Facts(enabled, connected, charging, scheduled, pct, limit);
    }
    private static ChargeLiveHold.Facts charging(int pct) { return facts(true, true, true, false, pct, 80); }

    private static void holdArming() {
        ChargeLiveHold h = new ChargeLiveHold();
        check(!h.active() && h.deadline() == 0 && h.nextCheck() == 0, "idle before anything happens");
        check(h.step(S, charging(50)) == ChargeLiveHold.End.NONE && !h.active(), "a charge seen in the app never arms by itself");
        check(h.leave(10 * S, charging(50)) && h.active(), "leaving while connected and charging arms");
        check(h.deadline() == 10 * S + ChargeLiveHold.HOLD_MS, "the hold is bounded by 12 hours from leaving");
        h.shown();
        check(!h.active(), "opening Volt disarms");
        check(!h.leave(20 * S, facts(false, true, true, false, 50, 80)), "the switch off: no hold");
        check(!h.leave(20 * S, facts(true, false, null, false, null, null)), "not connected when leaving: no hold");
        check(!h.leave(20 * S, facts(true, true, false, false, 50, 80)), "connected, not charging, nothing scheduled: no hold");
        check(!h.leave(20 * S, facts(true, true, null, false, 50, 80)), "no fresh charging reading: no hold");
        check(h.leave(20 * S, facts(true, true, false, true, 41, 80)), "a charge schedule waiting to start arms without charging");
        check(h.step(20 * S + 10 * MIN, facts(true, true, false, true, 41, 80)) == ChargeLiveHold.End.NONE && h.active(), "waiting for the schedule keeps the hold");
        check(h.step(20 * S + 11 * MIN, facts(true, true, true, true, 41, 80)) == ChargeLiveHold.End.NONE, "the scheduled start began charging");
        // Q47: no background watch. A charge that starts after leaving never arms the hold.
        ChargeLiveHold later = new ChargeLiveHold();
        check(!later.leave(S, facts(true, true, false, false, 40, 80)), "left while not charging");
        check(later.step(30 * S, charging(40)) == ChargeLiveHold.End.NONE && !later.active(), "charging that starts later doesn't arm it");
    }

    private static void holdDisconnect() {
        ChargeLiveHold h = new ChargeLiveHold();
        long t = 100 * S;
        h.leave(t, charging(60));
        ChargeLiveHold.Facts lost = facts(true, false, null, false, null, null);
        check(h.step(t + S, lost) == ChargeLiveHold.End.NONE && h.reconnecting(), "a drop starts the reconnect window");
        check(h.nextCheck() == t + S + ChargeLiveHold.RECONNECT_MS + 1, "the service checks again when the window ends");
        check(ChargeLiveHold.RECONNECT_MS >= 15 * S && ChargeLiveHold.RECONNECT_MS <= 20 * S, "the window is the owner's 15 to 20 seconds");
        check(h.step(t + 15 * S, lost) == ChargeLiveHold.End.NONE && h.active(), "still trying 14 s after the drop");
        check(h.step(t + 16 * S, charging(61)) == ChargeLiveHold.End.NONE && !h.reconnecting(), "a reconnect inside the window keeps the hold");
        check(h.step(t + 20 * S, lost) == ChargeLiveHold.End.NONE, "a second drop starts a fresh window");
        check(h.step(t + 20 * S + ChargeLiveHold.RECONNECT_MS, lost) == ChargeLiveHold.End.NONE, "exactly at the window's end it still holds");
        check(h.step(t + 21 * S + ChargeLiveHold.RECONNECT_MS, lost) == ChargeLiveHold.End.LOST && !h.active(), "past the window the hold ends");
        check(h.step(t + 60 * S, charging(62)) == ChargeLiveHold.End.NONE && !h.active(), "a later reconnect doesn't bring it back");
    }

    private static void holdCapAndEnd() {
        ChargeLiveHold h = new ChargeLiveHold();
        h.leave(0, charging(20));
        check(h.step(ChargeLiveHold.HOLD_MS - S, charging(70)) == ChargeLiveHold.End.NONE, "just under 12 hours still holds");
        check(h.step(ChargeLiveHold.HOLD_MS, charging(70)) == ChargeLiveHold.End.TIME_LIMIT && !h.active(), "12 hours ends it");
        check(ChargeLiveHold.HOLD_MS == 12 * H, "the cap is 12 hours");
        h.leave(5 * H, charging(20));
        check(h.step(4 * H, charging(20)) == ChargeLiveHold.End.TIME_LIMIT, "a clock that runs backwards ends it rather than holding forever");

        // Finished at the limit: one point of noise allowed, 99% counts as full without a limit.
        h.leave(0, charging(78));
        check(h.step(MIN, facts(true, true, false, false, 79, 80)) == ChargeLiveHold.End.FINISHED, "stopped at the limit: finished");
        h.leave(0, facts(true, true, true, false, 97, null));
        check(h.step(MIN, facts(true, true, false, false, 99, null)) == ChargeLiveHold.End.FINISHED, "no limit, stopped at 99%: finished");
        check(ChargeLiveHold.finishAt(80) == 79 && ChargeLiveHold.finishAt(null) == 99 && ChargeLiveHold.finishAt(100) == 99, "finish levels");

        // The charger off below the target: a fresh off reading ends it UNPLUG_MS later, unless charging comes back first.
        h.leave(0, charging(50));
        ChargeLiveHold.Facts off = facts(true, true, false, false, 50, 80);
        check(h.step(MIN, off) == ChargeLiveHold.End.NONE, "charger just went off");
        check(h.step(MIN + ChargeLiveHold.UNPLUG_MS, off) == ChargeLiveHold.End.NONE, "exactly the unplug window still holds");
        check(h.step(MIN + ChargeLiveHold.UNPLUG_MS, charging(50)) == ChargeLiveHold.End.NONE, "charging again at the end of the window keeps it");
        check(h.step(4 * MIN, off) == ChargeLiveHold.End.NONE, "off again: a new window");
        check(h.nextCheck() == 4 * MIN + ChargeLiveHold.UNPLUG_MS + 1, "the window's end is due");
        check(h.step(4 * MIN + ChargeLiveHold.UNPLUG_MS + S, off) == ChargeLiveHold.End.CHARGER_OFF && !h.active(), "off past the window ends it");
        check(ChargeLiveHold.UNPLUG_MS < ChargeLiveHold.STOP_MS && ChargeLiveHold.STOP_MS == ChargeAlertPolicy.STOP_MS && ChargeAlertPolicy.STOP_MS == 3 * MIN, "the short window is the hold's own; alerts keep 3 minutes");
        h.leave(0, charging(50));
        ChargeLiveHold.Facts stale = facts(true, true, null, false, null, 80);
        h.step(MIN, stale);
        check(h.step(MIN + ChargeLiveHold.STOP_MS, stale) == ChargeLiveHold.End.NONE, "no fresh reading is a gap, not an unplug: the longer tolerance");
        check(h.step(MIN + ChargeLiveHold.STOP_MS + S, stale) == ChargeLiveHold.End.CHARGER_OFF, "connected but no fresh reading for 3 minutes ends it too");
        h.leave(0, charging(50));
        h.step(MIN, off);
        h.step(MIN + S, stale);
        check(h.active() && h.nextCheck() == MIN + S + ChargeLiveHold.STOP_MS + 1, "an off reading then a gap: the longer tolerance");
        h.step(MIN + 2 * S, off);
        check(h.active() && h.nextCheck() == MIN + 2 * S + ChargeLiveHold.UNPLUG_MS + 1, "and the next off reading starts a full short window");

        h.leave(0, charging(50));
        check(h.step(MIN, facts(false, true, true, false, 51, 80)) == ChargeLiveHold.End.OFF && !h.active(), "turning the switch off ends it");
    }

    private static void holdStop() {
        ChargeLiveHold h = new ChargeLiveHold();
        h.leave(0, charging(50));
        h.stop(50);
        check(!h.active() && h.deadline() == 0, "Stop ends the hold at once");
        h.shown();
        check(!h.leave(MIN, charging(52)), "Stop holds for the rest of this charge: leaving again doesn't arm");
        h.step(2 * MIN, charging(55));
        check(!h.leave(3 * MIN, charging(56)), "still the same charge");
        // The charge ends (off past 3 minutes, nothing scheduled): the next charge arms as usual.
        ChargeLiveHold.Facts off = facts(true, true, false, false, 56, 80);
        h.step(4 * MIN, off);
        h.step(4 * MIN + ChargeLiveHold.STOP_MS + S, off);
        check(h.leave(10 * MIN, charging(56)), "a new charge arms again");

        ChargeLiveHold ride = new ChargeLiveHold();
        ride.leave(0, charging(70));
        ride.stop(70);
        ride.step(MIN, facts(true, true, true, false, 71, 80));
        ride.step(2 * H, facts(true, true, true, false, 40, 80));
        check(ride.leave(2 * H + S, charging(40)), "a level two points under this charge's best is a new charge");

        ChargeLiveHold brief = new ChargeLiveHold();
        brief.leave(0, charging(30));
        brief.stop(30);
        brief.step(MIN, facts(true, true, false, false, 30, 80));
        brief.step(2 * MIN, charging(30));
        brief.step(2 * MIN + ChargeLiveHold.STOP_MS + S, charging(31));
        check(!brief.leave(10 * MIN, charging(31)), "a short charger cut-out is still the stopped charge");
    }

    private static ChargeLiveHold.Facts on(String profile, int pct) { return new ChargeLiveHold.Facts(true, true, true, false, pct, 80, profile); }
    private static ChargeLiveHold.Facts off(String profile, int pct) { return new ChargeLiveHold.Facts(true, true, false, false, pct, 80, profile); }

    /** Round 2 (Codex): Stop belongs to the scooter it was pressed for; switching either way carries nothing over. */
    private static void holdStopPerScooter() {
        ChargeLiveHold h = new ChargeLiveHold();
        long t = 5 * H;
        check(h.leave(t, on("a", 70)), "scooter A arms");
        h.stop(70, "a", t);
        h.shown();
        // A -> B: B charging at 80% (A's level was 70%) arms; A's Stop doesn't reach it.
        h.step(t + MIN, on("b", 80));
        check(h.leave(t + 2 * MIN, on("b", 80)), "after Stop on A, a charging scooter B arms");
        h.shown();
        // B's readings, even off for long, don't end A's stopped charge.
        h.step(t + 3 * MIN, off("b", 81));
        h.step(t + 3 * MIN + ChargeLiveHold.STOP_MS + S, off("b", 81));
        check(only(h.stopped(t + 10 * MIN)) != null && "a".equals(only(h.stopped(t + 10 * MIN)).profile), "B's readings leave A's Stop in place");
        // B -> A: A's charge is still the stopped one.
        check(!h.leave(t + 11 * MIN, on("a", 72)), "back on A, its stopped charge still doesn't arm");
        // A's own level drop ends A's stopped charge.
        h.step(t + 12 * MIN, on("a", 50));
        check(only(h.stopped(t + 12 * MIN)) == null, "A's own two-point drop ends A's Stop");
        check(h.leave(t + 13 * MIN, on("a", 50)), "A's next charge arms");
        // Stop on B, then A arms and B still doesn't.
        h.stop(60, "b", t + 14 * MIN);
        check(h.leave(t + 15 * MIN, on("a", 51)), "after Stop on B, A arms");
        h.shown();
        check(!h.leave(t + 16 * MIN, on("b", 61)), "B stays stopped");
        // A Stop with no scooter (an old caller) only matches no scooter.
        ChargeLiveHold anon = new ChargeLiveHold();
        anon.stop(40);
        check(anon.leave(MIN, on("a", 40)), "a Stop for no scooter doesn't stop a named one");
    }

    /** Round 2 (Codex): the Stop outlives the service. A new hold restored from what the old one stored doesn't arm. */
    private static void holdStopRestored() {
        ChargeLiveHold old = new ChargeLiveHold();
        long t = 3 * H;
        old.leave(t, on("a", 64));
        old.stop(64, "a", t);
        old.step(t + 20 * MIN, on("a", 68));
        ChargeLiveHold.Stopped saved = only(old.stopped(t + 30 * MIN));
        check(saved != null && "a".equals(saved.profile) && saved.best == 68 && saved.ageMs == 30 * MIN, "the stored Stop: scooter, best level, age");
        // The service and its hold are recreated; the new monotonic clock reads something else entirely.
        long mono = 40 * MIN;
        ChargeLiveHold fresh = new ChargeLiveHold();
        check(fresh.leave(mono, on("a", 69)), "control: a fresh hold without the stored Stop arms");
        fresh = new ChargeLiveHold();
        fresh.restoreStopped(saved.profile, saved.best, saved.ageMs + 5 * MIN, mono);
        check(!fresh.leave(mono, on("a", 69)), "a recreated service doesn't arm the stopped charge again");
        check(fresh.leave(mono + S, on("b", 69)), "the restored Stop is still only A's");
        fresh.shown();
        // The restored best still decides a new charge: a two-point drop below 68 clears it.
        fresh.step(mono + 2 * S, on("a", 66));
        check(fresh.leave(mono + 3 * S, on("a", 66)), "after restore, a two-point drop is a new charge");
        // The documented end by charger off works after a restore too.
        ChargeLiveHold off = new ChargeLiveHold();
        off.restoreStopped("a", 70, MIN, mono);
        off.step(mono + S, off("a", 70));
        off.step(mono + ChargeLiveHold.STOP_MS + 2 * S, off("a", 70));
        check(off.leave(mono + ChargeLiveHold.STOP_MS + 3 * S, on("a", 70)), "after restore, charging off past 3 minutes ends the Stop");
        // Bounded: 12 hours at most, in memory or restored; a stored age from the future or nonsense is ignored.
        ChargeLiveHold aged = new ChargeLiveHold();
        aged.restoreStopped("a", 70, ChargeLiveHold.HOLD_MS, mono);
        check(only(aged.stopped(mono)) == null && aged.leave(mono, on("a", 70)), "a stored Stop 12 hours old is over");
        ChargeLiveHold future = new ChargeLiveHold();
        future.restoreStopped("a", 70, -MIN, mono);
        check(only(future.stopped(mono)) == null, "a stored Stop from the future is ignored");
        ChargeLiveHold nobody = new ChargeLiveHold();
        nobody.restoreStopped("", 70, MIN, mono);
        check(only(nobody.stopped(mono)) == null, "a stored Stop without a scooter is ignored");
        ChargeLiveHold mem = new ChargeLiveHold();
        mem.leave(t, on("a", 20));
        mem.stop(20, "a", t);
        check(!mem.leave(t + ChargeLiveHold.HOLD_MS - S, on("a", 21)), "a Stop counts just under 12 hours");
        check(mem.leave(t + ChargeLiveHold.HOLD_MS, on("a", 21)), "and ends at 12 hours");
        check(only(mem.stopped(t + ChargeLiveHold.HOLD_MS)) == null, "nothing left to store");
    }

    /** At most one stored Stop expected: it, or null. */
    private static ChargeLiveHold.Stopped only(java.util.List<ChargeLiveHold.Stopped> stopped) {
        check(stopped.size() <= 1, "expected at most one Stop, got " + stopped.size());
        return stopped.isEmpty() ? null : stopped.get(0);
    }
    /** What the service does on recreation: a fresh hold given back every stored Stop, aged by the time between. */
    private static ChargeLiveHold recreate(ChargeLiveHold old, long oldMono, long gapMs, long newMono) {
        ChargeLiveHold fresh = new ChargeLiveHold();
        for (ChargeLiveHold.Stopped stop : old.stopped(oldMono)) fresh.restoreStopped(stop.profile, stop.best, stop.ageMs + gapMs, newMono);
        return fresh;
    }

    /** Round 3 (Codex): each scooter keeps its own Stop; a second Stop on another scooter never replaces the first. */
    private static void holdStopEachScooter() {
        long t = 2 * H;
        ChargeLiveHold h = new ChargeLiveHold();
        check(h.leave(t, on("a", 60)), "A arms");
        h.stop(60, "a", t);
        h.shown();
        check(h.leave(t + MIN, on("b", 40)), "B arms after Stop on A");
        h.stop(40, "b", t + MIN);
        h.shown();
        check(h.stopped(t + 2 * MIN).size() == 2, "both Stops are kept");
        check(!h.leave(t + 3 * MIN, on("a", 62)), "A Stop, B Stop, back to A: A still doesn't arm");
        check(!h.leave(t + 4 * MIN, on("b", 42)), "and back to B: B still doesn't arm");
        check(h.leave(t + 5 * MIN, on("c", 50)), "a third scooter arms");
        h.shown();

        // Recreated: both Stops come back, each with its own age.
        long mono = 7 * MIN;
        ChargeLiveHold re = recreate(h, t + 6 * MIN, 2 * MIN, mono);
        java.util.List<ChargeLiveHold.Stopped> back = re.stopped(mono);
        check(back.size() == 2 && "a".equals(back.get(0).profile) && "b".equals(back.get(1).profile), "recreation keeps both Stops, oldest first");
        check(back.get(0).ageMs == 8 * MIN && back.get(1).ageMs == 7 * MIN, "each Stop keeps its own age");
        check(!re.leave(mono + S, on("a", 63)), "after recreation, A doesn't arm");
        check(!re.leave(mono + 2 * S, on("b", 43)), "after recreation, B doesn't arm");
        re.shown();

        // One scooter's own charge end clears only its own Stop.
        re.step(mono + 3 * S, on("b", 20));
        check(re.leave(mono + 4 * S, on("b", 20)), "B's own level drop ends B's Stop");
        re.shown();
        check(!re.leave(mono + 5 * S, on("a", 63)), "A's Stop outlives B's end");
        ChargeLiveHold.Facts offA = off("a", 64);
        re.step(mono + 6 * S, offA);
        re.step(mono + 7 * S + ChargeLiveHold.STOP_MS, offA);
        check(re.leave(mono + 8 * S + ChargeLiveHold.STOP_MS, on("a", 64)), "A's own charger-off end ends A's Stop");

        // Each Stop expires on its own 12 hours.
        ChargeLiveHold ages = new ChargeLiveHold();
        ages.stop(50, "a", t);
        ages.stop(50, "b", t + 6 * H);
        java.util.List<ChargeLiveHold.Stopped> left = ages.stopped(t + ChargeLiveHold.HOLD_MS);
        check(left.size() == 1 && "b".equals(left.get(0).profile), "A's Stop ends at its 12 hours, B's stays");
        check(ages.leave(t + ChargeLiveHold.HOLD_MS + S, on("a", 50)) , "A arms after its 12 hours");
        ages.shown();
        check(!ages.leave(t + ChargeLiveHold.HOLD_MS + 2 * S, on("b", 50)), "B still doesn't");
        ChargeLiveHold agedRe = recreate(ages, t + ChargeLiveHold.HOLD_MS + 3 * S, 6 * H, 10 * MIN);
        check(agedRe.stopped(10 * MIN).isEmpty(), "B's Stop is over 12 hours after it, across recreation too");

        // Bounded: the ninth Stop drops the oldest; a repeated Stop on a scooter moves it to newest without growing.
        ChargeLiveHold many = new ChargeLiveHold();
        for (int i = 0; i <= ChargeLiveHold.MAX_STOPS; i++) many.stop(30, "s" + i, t + i * S);
        java.util.List<ChargeLiveHold.Stopped> kept = many.stopped(t + MIN);
        check(kept.size() == ChargeLiveHold.MAX_STOPS && "s1".equals(kept.get(0).profile), "at most MAX_STOPS Stops; the oldest goes first");
        many.stop(31, "s1", t + 2 * MIN);
        kept = many.stopped(t + 3 * MIN);
        check(kept.size() == ChargeLiveHold.MAX_STOPS && "s2".equals(kept.get(0).profile) && "s1".equals(kept.get(kept.size() - 1).profile), "a repeated Stop replaces that scooter's own");
    }

    /** Round 2 (Codex): only a matching, current, still-showing completion alert stands in for the done notice. */
    private static void doneNoticeAnnounced() {
        // full, charge, alertLevel, doneLevel, sameScooter, thisCharge, showing
        check(ChargeLiveHold.alreadyAnnounced(true, false, 100, 100, true, true, true), "Full alert for this scooter and charge, showing: no repeat");
        check(ChargeLiveHold.alreadyAnnounced(false, true, 100, 100, true, true, true), "a charge alert at 100% announced a full finish");
        check(ChargeLiveHold.alreadyAnnounced(false, true, 85, 80, true, true, true), "a charge alert at or past the finish level announced it");
        check(!ChargeLiveHold.alreadyAnnounced(false, true, 80, 100, true, true, true), "a charge alert at 80% didn't announce a full finish");
        check(!ChargeLiveHold.alreadyAnnounced(false, false, 20, 100, true, true, true), "a low battery alert never stands in for completion");
        check(!ChargeLiveHold.alreadyAnnounced(true, false, 100, 100, false, true, true), "another scooter's alert doesn't count");
        check(!ChargeLiveHold.alreadyAnnounced(true, false, 100, 100, true, false, true), "an alert from an earlier charge doesn't count");
        check(!ChargeLiveHold.alreadyAnnounced(true, false, 100, 100, true, true, false), "an alert swiped away or gone doesn't count");
        for (int bits = 0; bits < 1 << 5; bits++) {
            boolean full = bit(bits, 0), charge = bit(bits, 1), same = bit(bits, 2), now = bit(bits, 3), showing = bit(bits, 4);
            boolean got = ChargeLiveHold.alreadyAnnounced(full, charge, 100, 100, same, now, showing);
            check(got == ((full || charge) && same && now && showing), "announced only with a completion alert, same scooter, this charge, showing: " + Integer.toBinaryString(bits));
        }
    }

    // ---- ChargeLiveState --------------------------------------------------------------------------------

    private static final LongFunction<String> CLOCK = wall -> {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.setTimeInMillis(wall);
        int h = c.get(Calendar.HOUR_OF_DAY) % 12;
        return (h == 0 ? 12 : h) + ":" + String.format("%02d", c.get(Calendar.MINUTE)) + (c.get(Calendar.HOUR_OF_DAY) < 12 ? " AM" : " PM");
    };
    /** 10:04 PM UTC on some day. */
    private static final long NOW = (20_000L * 24 + 22) * H + 4 * MIN;

    private static ChargeLiveState.Input input(Integer pct, Integer limit, Double minutes, Double watts) {
        ChargeLiveState.Input in = new ChargeLiveState.Input();
        in.percent = pct;in.limit = limit;in.minutesLeft = minutes;in.watts = watts;in.scooter = "UT5 MAX";in.nowWall = NOW;return in;
    }

    private static void state() {
        ChargeLiveState s = ChargeLiveState.of(input(72, 80, 38.2, 411.6), CLOCK);
        check(s.kind == ChargeLiveState.Kind.CHARGING, "charging");
        check("Charging to 80% · 72%".equals(s.title), "title with a limit: " + s.title);
        check("Done 10:42 PM · 412 W".equals(s.text), "text with the finish time and power: " + s.text);
        check("UT5 MAX".equals(s.subText), "the scooter's name is the header's subtext");
        check(s.max == 80 && s.progress == 72, "the bar runs 0 to the limit");
        check(s.title.equals(s.publicTitle) && s.text.equals(s.publicText) && "UT5 MAX".equals(s.publicSubText), "Details: the lock screen shows the whole card (owner, Q46 B)");

        ChargeLiveState.Input level = input(72, 80, 38.0, 412.0);level.detail = ChargeLiveState.Detail.LEVEL;
        ChargeLiveState l = ChargeLiveState.of(level, CLOCK);
        check("Done 10:42 PM".equals(l.publicText) && l.publicSubText == null, "Level: no name and no power on the lock screen: " + l.publicText);
        check(!l.publicTitle.contains("UT5") && !l.publicText.contains("UT5") && !l.publicText.contains("W"), "Level never names the scooter");
        check(l.text.contains("412 W") && "UT5 MAX".equals(l.subText), "Level still shows everything once unlocked");
        ChargeLiveState.Input hidden = input(72, 80, 38.0, 412.0);hidden.detail = ChargeLiveState.Detail.HIDDEN;
        ChargeLiveState hd = ChargeLiveState.of(hidden, CLOCK);
        check(hd.publicTitle == null && hd.publicText == null && hd.publicSubText == null, "Hidden: no public version");
        check(ChargeLiveState.Detail.of(null) == ChargeLiveState.Detail.DETAILS && ChargeLiveState.Detail.of("odd") == ChargeLiveState.Detail.DETAILS
                && ChargeLiveState.Detail.of("level") == ChargeLiveState.Detail.LEVEL && ChargeLiveState.Detail.of("hidden") == ChargeLiveState.Detail.HIDDEN, "stored choice ids");

        ChargeLiveState full = ChargeLiveState.of(input(63, null, 85.0, null), CLOCK);
        check("Charging · 63%".equals(full.title) && full.max == 100 && full.progress == 63, "no limit: to 100");
        check("Done 11:29 PM".equals(full.text), "a finish over an hour away, no power reading: " + full.text);
        check("Charging · 63%".equals(ChargeLiveState.of(input(63, 100, null, null), CLOCK).title), "a 100% limit reads as no limit");

        ChargeLiveState unknown = ChargeLiveState.of(input(50, 80, null, 300.0), CLOCK);
        check("Estimating time left · 300 W".equals(unknown.text), "no estimate: estimating");
        check("Estimating time left".equals(ChargeLiveState.of(input(50, 80, 0.0, null), CLOCK).text), "zero minutes left is no finish time either");
        check("Estimating time left".equals(ChargeLiveState.of(input(50, 80, Double.NaN, 0.4), CLOCK).text), "a broken estimate never shows a finish time");
        ChargeLiveState none = ChargeLiveState.of(input(null, 80, null, null), CLOCK);
        check("Charging to 80%".equals(none.title) && none.progress == -1, "no level read: no bar, the limit-only title");
        check(ChargeLiveState.of(input(85, 80, 5.0, null), CLOCK).progress == 80, "the bar never overfills");

        ChargeLiveState.Input wait = input(41, 80, null, null);wait.waiting = true;wait.startWallMs = NOW + 5 * H + 36 * MIN;wait.doneByWallMs = NOW + 8 * H + 56 * MIN;
        ChargeLiveState w = ChargeLiveState.of(wait, CLOCK);
        check(w.kind == ChargeLiveState.Kind.WAITING && "Starts 3:40 AM · 41%".equals(w.title) && "Charge done by 7:00 AM".equals(w.text), "Charge done by waiting: " + w.title + " / " + w.text);
        wait.doneByWallMs = 0;
        check("Scheduled charging".equals(ChargeLiveState.of(wait, CLOCK).text), "Scheduled charging waiting");
        wait.percent = null;
        check("Charging starts 3:40 AM".equals(ChargeLiveState.of(wait, CLOCK).title), "waiting before a level was read");

        ChargeLiveState.Input lost = input(72, 80, 38.0, 412.0);lost.reconnecting = true;
        ChargeLiveState r = ChargeLiveState.of(lost, CLOCK);
        check(r.kind == ChargeLiveState.Kind.RECONNECTING && "Last seen 72%".equals(r.title) && "Out of reach · reconnecting".equals(r.text), "reconnecting");
        check(r.progress == 72, "a stale level keeps its bar");

        // Reposts: only a whole percent, the finish time, the state or the limit changes the key.
        ChargeLiveState a = ChargeLiveState.of(input(72, 80, 38.2, 400.0), CLOCK), b = ChargeLiveState.of(input(72, 80, 37.9, 455.0), CLOCK);
        check(a.dedupeKey.equals(b.dedupeKey), "power and sub-minute changes don't repost");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(73, 80, 38.0, 400.0), CLOCK).dedupeKey), "a new percent reposts");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(72, 80, 37.0, 400.0), CLOCK).dedupeKey), "a new finish time reposts");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(72, 90, 38.0, 400.0), CLOCK).dedupeKey), "a new limit reposts");
        check(!a.dedupeKey.equals(r.dedupeKey), "reconnecting reposts");
        check(!a.dedupeKey.equals(l.dedupeKey), "a new lock screen choice reposts");
    }
}
