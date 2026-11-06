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
        holdArming();
        holdDisconnect();
        holdCapAndEnd();
        holdStop();
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

        // The charger off below the target: a short cut-out keeps the hold, more than 3 minutes ends it.
        h.leave(0, charging(50));
        ChargeLiveHold.Facts off = facts(true, true, false, false, 50, 80);
        check(h.step(MIN, off) == ChargeLiveHold.End.NONE, "charger just went off");
        check(h.step(3 * MIN, charging(50)) == ChargeLiveHold.End.NONE, "it came back inside 3 minutes");
        check(h.step(4 * MIN, off) == ChargeLiveHold.End.NONE, "off again: a new 3 minutes");
        check(h.step(4 * MIN + ChargeLiveHold.STOP_MS, off) == ChargeLiveHold.End.NONE, "exactly 3 minutes still holds");
        check(h.step(4 * MIN + ChargeLiveHold.STOP_MS + S, off) == ChargeLiveHold.End.CHARGER_OFF && !h.active(), "more than 3 minutes off ends it");
        h.leave(0, charging(50));
        ChargeLiveHold.Facts stale = facts(true, true, null, false, null, 80);
        h.step(MIN, stale);
        check(h.step(MIN + ChargeLiveHold.STOP_MS + S, stale) == ChargeLiveHold.End.CHARGER_OFF, "connected but no fresh reading for 3 minutes ends it too");

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
        check("≈ 38m left · done 10:42 PM · 412 W".equals(s.text), "text with time left, finish time and power: " + s.text);
        check("UT5 MAX".equals(s.subText), "the scooter's name is the header's subtext");
        check(s.max == 80 && s.progress == 72, "the bar runs 0 to the limit");
        check(s.countdownEndWallMs == NOW + 38 * MIN, "Android's countdown ends at the estimate");
        check(s.title.equals(s.publicTitle) && s.text.equals(s.publicText) && "UT5 MAX".equals(s.publicSubText), "Details: the lock screen shows the whole card (owner, Q46 B)");

        ChargeLiveState.Input level = input(72, 80, 38.0, 412.0);level.detail = ChargeLiveState.Detail.LEVEL;
        ChargeLiveState l = ChargeLiveState.of(level, CLOCK);
        check("≈ 38m left · done 10:42 PM".equals(l.publicText) && l.publicSubText == null, "Level: no name and no power on the lock screen: " + l.publicText);
        check(!l.publicTitle.contains("UT5") && !l.publicText.contains("UT5") && !l.publicText.contains("W"), "Level never names the scooter");
        check(l.text.contains("412 W") && "UT5 MAX".equals(l.subText), "Level still shows everything once unlocked");
        ChargeLiveState.Input hidden = input(72, 80, 38.0, 412.0);hidden.detail = ChargeLiveState.Detail.HIDDEN;
        ChargeLiveState hd = ChargeLiveState.of(hidden, CLOCK);
        check(hd.publicTitle == null && hd.publicText == null && hd.publicSubText == null, "Hidden: no public version");
        check(ChargeLiveState.Detail.of(null) == ChargeLiveState.Detail.DETAILS && ChargeLiveState.Detail.of("odd") == ChargeLiveState.Detail.DETAILS
                && ChargeLiveState.Detail.of("level") == ChargeLiveState.Detail.LEVEL && ChargeLiveState.Detail.of("hidden") == ChargeLiveState.Detail.HIDDEN, "stored choice ids");

        ChargeLiveState full = ChargeLiveState.of(input(63, null, 85.0, null), CLOCK);
        check("Charging · 63%".equals(full.title) && full.max == 100 && full.progress == 63, "no limit: to 100");
        check("≈ 1h 25m left · done 11:29 PM".equals(full.text), "hours and minutes, no power reading: " + full.text);
        check("Charging · 63%".equals(ChargeLiveState.of(input(63, 100, null, null), CLOCK).title), "a 100% limit reads as no limit");

        ChargeLiveState unknown = ChargeLiveState.of(input(50, 80, null, 300.0), CLOCK);
        check("Estimating time left · 300 W".equals(unknown.text) && unknown.countdownEndWallMs == 0, "no estimate: no countdown");
        check("Estimating time left".equals(ChargeLiveState.of(input(50, 80, 0.0, null), CLOCK).text), "zero minutes left is no countdown either");
        check(ChargeLiveState.of(input(50, 80, Double.NaN, 0.4), CLOCK).countdownEndWallMs == 0, "a broken estimate never counts down");
        ChargeLiveState none = ChargeLiveState.of(input(null, 80, null, null), CLOCK);
        check("Charging to 80%".equals(none.title) && none.progress == -1, "no level read: no bar, the limit-only title");
        check(ChargeLiveState.of(input(85, 80, 5.0, null), CLOCK).progress == 80, "the bar never overfills");

        ChargeLiveState.Input wait = input(41, 80, null, null);wait.waiting = true;wait.startWallMs = NOW + 5 * H + 36 * MIN;wait.doneByWallMs = NOW + 8 * H + 56 * MIN;
        ChargeLiveState w = ChargeLiveState.of(wait, CLOCK);
        check(w.kind == ChargeLiveState.Kind.WAITING && "Starts 3:40 AM · 41%".equals(w.title) && "Charge done by 7:00 AM".equals(w.text), "Charge done by waiting: " + w.title + " / " + w.text);
        check(w.countdownEndWallMs == 0, "no countdown while waiting");
        wait.doneByWallMs = 0;
        check("Scheduled charging".equals(ChargeLiveState.of(wait, CLOCK).text), "Scheduled charging waiting");
        wait.percent = null;
        check("Charging starts 3:40 AM".equals(ChargeLiveState.of(wait, CLOCK).title), "waiting before a level was read");

        ChargeLiveState.Input lost = input(72, 80, 38.0, 412.0);lost.reconnecting = true;
        ChargeLiveState r = ChargeLiveState.of(lost, CLOCK);
        check(r.kind == ChargeLiveState.Kind.RECONNECTING && "Last seen 72%".equals(r.title) && "Out of reach · reconnecting".equals(r.text), "reconnecting");
        check(r.countdownEndWallMs == 0 && r.progress == 72, "a stale level keeps its bar but no countdown");

        // Reposts: only a whole percent, a whole minute, the state or the limit changes the key.
        ChargeLiveState a = ChargeLiveState.of(input(72, 80, 38.2, 400.0), CLOCK), b = ChargeLiveState.of(input(72, 80, 37.9, 455.0), CLOCK);
        check(a.dedupeKey.equals(b.dedupeKey), "power and sub-minute changes don't repost");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(73, 80, 38.0, 400.0), CLOCK).dedupeKey), "a new percent reposts");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(72, 80, 37.0, 400.0), CLOCK).dedupeKey), "a new minute reposts");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(72, 90, 38.0, 400.0), CLOCK).dedupeKey), "a new limit reposts");
        check(!a.dedupeKey.equals(r.dedupeKey), "reconnecting reposts");
        check(!a.dedupeKey.equals(l.dedupeKey), "a new lock screen choice reposts");
    }
}
