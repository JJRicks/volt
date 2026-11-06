package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.LongFunction;

/**
 * The owner's simpler live charging card (2026-11-06): "Done 10:42 PM · 412 W" with no time left, no countdown in
 * the header or on the lock screen, no scooter icon on the bar, and Disconnect instead of Stop on the card only.
 * Opening Volt ends that scooter's Disconnect, so leaving again brings the card back; a service recreated in the
 * background keeps it.
 * Listed in tests/l10n/charge-card-simpler.sources; tests/run.sh runs it from the repository root.
 */
public final class ChargeCardSimplerTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long MIN = 60_000L, H = 60 * MIN;
    /** 10:04 PM UTC on some day. */
    private static final long NOW = (20_000L * 24 + 22) * H + 4 * MIN;

    private static Calendar at(long wall) { Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.setTimeInMillis(wall);return c; }
    /** Volt's 12-hour and 24-hour clocks, as DisplayTime.clock prints them in English. */
    private static final LongFunction<String> CLOCK_12 = wall -> {
        Calendar c = at(wall);int h = c.get(Calendar.HOUR_OF_DAY) % 12;
        return (h == 0 ? 12 : h) + ":" + String.format("%02d", c.get(Calendar.MINUTE)) + (c.get(Calendar.HOUR_OF_DAY) < 12 ? " AM" : " PM");
    };
    private static final LongFunction<String> CLOCK_24 = wall -> {
        Calendar c = at(wall);return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    };

    private static ChargeLiveState.Input input(Double minutes, Double watts, ChargeLiveState.Detail detail) {
        ChargeLiveState.Input in = new ChargeLiveState.Input();
        in.percent = 72;in.limit = 80;in.minutesLeft = minutes;in.watts = watts;in.scooter = "UT5 MAX";in.nowWall = NOW;in.detail = detail;
        return in;
    }

    public static void main(String[] args) throws Exception {
        text();
        reposts();
        action();
        service();
        rearm();
        System.out.println("ChargeCardSimplerTest: " + checks + " checks passed");
    }

    private static void text() {
        ChargeLiveState.Detail D = ChargeLiveState.Detail.DETAILS, L = ChargeLiveState.Detail.LEVEL;
        // 38 minutes from 10:04 PM: done at 10:42 PM, or 22:42.
        ChargeLiveState p12 = ChargeLiveState.of(input(38.2, 121.6, D), CLOCK_12), p24 = ChargeLiveState.of(input(38.2, 121.6, D), CLOCK_24);
        check("Done 10:42 PM · 122 W".equals(p12.text), "12-hour, with power: " + p12.text);
        check("Done 22:42 · 122 W".equals(p24.text), "24-hour, with power: " + p24.text);
        ChargeLiveState n12 = ChargeLiveState.of(input(38.0, null, D), CLOCK_12), n24 = ChargeLiveState.of(input(38.0, null, D), CLOCK_24);
        check("Done 10:42 PM".equals(n12.text), "12-hour, no power reading: " + n12.text);
        check("Done 22:42".equals(n24.text), "24-hour, no power reading: " + n24.text);
        check("Done 22:42".equals(ChargeLiveState.of(input(38.0, 0.4, D), CLOCK_24).text), "under 1 W is no power reading");
        for (ChargeLiveState s : new ChargeLiveState[]{p12, p24, n12, n24}) {
            check(!s.text.contains("left") && !s.text.contains("≈") && s.text.startsWith("Done "), "no time left, a capital D: " + s.text);
            check(s.text.equals(s.publicText), "Details: the lock screen shows the same text: " + s.publicText);
        }
        // Over an hour away: still only the clock time, never "1h 25m".
        check("Done 23:29".equals(ChargeLiveState.of(input(85.0, null, D), CLOCK_24).text), "a finish over an hour away");

        // Level: the same finish time on the lock screen, without the power (and the name); unlocked still shows the power.
        ChargeLiveState l12 = ChargeLiveState.of(input(38.0, 412.0, L), CLOCK_12), l24 = ChargeLiveState.of(input(38.0, 412.0, L), CLOCK_24);
        check("Done 10:42 PM".equals(l12.publicText) && "Done 22:42".equals(l24.publicText), "Level: " + l12.publicText + " / " + l24.publicText);
        check("Done 10:42 PM · 412 W".equals(l12.text) && l12.publicSubText == null, "Level still shows the power once unlocked");

        // The other texts are unchanged.
        check("Estimating time left · 300 W".equals(ChargeLiveState.of(input(null, 300.0, D), CLOCK_24).text), "estimating, with power");
        check("Estimating time left".equals(ChargeLiveState.of(input(null, null, D), CLOCK_24).text), "estimating");
        ChargeLiveState.Input lost = input(38.0, 412.0, D);lost.reconnecting = true;
        check("Out of reach · reconnecting".equals(ChargeLiveState.of(lost, CLOCK_24).text), "reconnecting");
        ChargeLiveState.Input wait = input(null, null, D);wait.waiting = true;wait.startWallMs = NOW + 5 * H + 36 * MIN;wait.doneByWallMs = NOW + 8 * H + 56 * MIN;
        check("Charge done by 07:00".equals(ChargeLiveState.of(wait, CLOCK_24).text), "Charge done by waiting");
        wait.doneByWallMs = 0;
        check("Scheduled charging".equals(ChargeLiveState.of(wait, CLOCK_24).text), "Scheduled charging waiting");
    }

    /** With no countdown, a minute that passes with the same finish time posts nothing new. */
    private static void reposts() {
        ChargeLiveState.Input now = input(38.0, 400.0, ChargeLiveState.Detail.DETAILS), later = input(37.0, 400.0, ChargeLiveState.Detail.DETAILS);
        later.nowWall = NOW + MIN;
        ChargeLiveState a = ChargeLiveState.of(now, CLOCK_24), b = ChargeLiveState.of(later, CLOCK_24);
        check(a.text.equals(b.text) && a.dedupeKey.equals(b.dedupeKey), "a minute later, the same finish time: no repost");
        later.minutesLeft = 40.0;
        check(!a.dedupeKey.equals(ChargeLiveState.of(later, CLOCK_24).dedupeKey), "a later finish time reposts");
        check(!a.dedupeKey.equals(ChargeLiveState.of(input(null, 400.0, ChargeLiveState.Detail.DETAILS), CLOCK_24).dedupeKey), "losing the estimate reposts");
    }

    private static void action() {
        check("Disconnect".equals(ChargeLiveState.actionLabel()), "the card's button: " + ChargeLiveState.actionLabel());
        check("Stop".equals(T.serviceActionStop()), "the ride and alarm notices keep Stop");
        check(!T.chargeLiveWhen().contains("Stop") && T.chargeLiveWhen().contains("Tap Disconnect on the card"), "the sheet names the button: " + T.chargeLiveWhen());
        // The simplified sheet (owner, 2026-11-06): "Necessary information only. Like that the official app cant connect."
        check("While it shows, other apps \u2014 the official one included \u2014 can't connect. Tap Disconnect on the card to let them.".equals(T.chargeLiveWhen()), "the sheet's one paragraph: " + T.chargeLiveWhen());
        check("Shows the charge on the lock screen and in your notifications after you leave Volt.".equals(T.chargeLiveIntro()), "intro: " + T.chargeLiveIntro());
        check("Volt stays connected while charging, up to 12 hours".equals(T.chargeLiveSwitchDetail()), "switch detail: " + T.chargeLiveSwitchDetail());
        check("Details".equals(T.chargeLiveLockDetails()) && "Level".equals(T.chargeLiveLockLevel()) && "Hidden".equals(T.chargeLiveLockHidden()), "the lock screen choices keep their labels");
        String main;
        try { main = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MainActivity.java")), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        check(!main.contains("chargeLiveLockNote"), "the lock screen choices' paragraph is gone from the sheet");
    }

    /** The parts of ScooterService that only Android runs: read from the source. */
    private static void service() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8);
        String card = between(src, "private void chargeLiveCard(", "// ---- Live Update promotion");
        check(!card.contains("setChronometerCountDown") && !card.contains("setUsesChronometer") && !card.contains("setShowWhen(true)"), "no countdown on the card or its lock screen version");
        check(card.contains("builder.setShowWhen(false)") && card.contains("lock.setShowWhen(false)"), "neither shows a time in the header");
        check(!src.contains("setChronometerCountDown"), "nothing in the service counts down");
        String promote = between(src, "private boolean promoteChargeLive(", "private static int liveUpdateColor(");
        check(!promote.contains("setProgressStartIcon") && !src.contains("ic_scooter_small"), "no scooter icon at the bar's start");
        check(promote.contains("setProgressTrackerIcon") && promote.contains("setProgressPoints") && promote.contains("setProgressSegments"), "the tracker, the limit point and the segments stay");
        check(!Files.exists(Paths.get("res/drawable/ic_scooter_small.xml")), "the unused drawable is gone");
        check(src.contains("liveStop ? ChargeLiveState.actionLabel() : alarming ? T.serviceActionStop()"), "the card's action is Disconnect; the alarm's stays Stop");
        check(src.contains("T.serviceActionStop(),stop).build()"), "the alert notice's alarm keeps Stop");
    }
    private static ChargeLiveHold.Facts on(String profile, int pct) { return new ChargeLiveHold.Facts(true, true, true, false, pct, 80, profile); }

    /** Owner, 2026-11-06: "just open the app again and then swipe away to put it in the background again". */
    private static void rearm() {
        long t = 3 * H;
        // Disconnect, then leave (as from the lock screen back into another app): no card.
        ChargeLiveHold h = new ChargeLiveHold();
        check(h.leave(t, on("a", 60)), "A arms on leaving");
        h.stop(60, "a", t + MIN);
        check(!h.active(), "Disconnect ends the hold");
        check(!h.leave(t + 2 * MIN, on("a", 61)), "Disconnect, leave: no card");

        // Disconnect, open Volt, leave: the card again.
        h.opened("a");
        check(!h.active() && h.stopped(t + 3 * MIN).isEmpty(), "opening Volt ends A's Disconnect");
        check(h.leave(t + 4 * MIN, on("a", 62)), "Disconnect, open Volt, leave: the card arms");
        h.opened("a");
        check(h.leave(t + 5 * MIN, new ChargeLiveHold.Facts(true, true, false, false, 62, 80, "a")) == false, "the usual rules still apply: not charging, no card");
        check(!h.leave(t + 6 * MIN, new ChargeLiveHold.Facts(false, true, true, false, 62, 80, "a")), "the switch off still means no card");

        // Opening Volt on scooter B leaves A's Disconnect alone.
        ChargeLiveHold two = new ChargeLiveHold();
        two.stop(60, "a", t);
        two.opened("b");
        check(!two.leave(t + MIN, on("a", 60)), "B selected when Volt opened: A's Disconnect still counts");
        two.opened("a");
        check(two.leave(t + 2 * MIN, on("a", 60)), "then opened with A selected: A arms");

        // Disconnect, then the service is recreated in the background (no screen): still no card.
        ChargeLiveHold old = new ChargeLiveHold();
        old.leave(t, on("a", 60));
        old.stop(60, "a", t + MIN);
        long gap = 5 * MIN, mono = 40 * MIN;
        ChargeLiveHold fresh = new ChargeLiveHold();
        for (ChargeLiveHold.Stopped stop : old.stopped(t + 2 * MIN)) fresh.restoreStopped(stop.profile, stop.best, stop.ageMs + gap, mono);
        check(!fresh.leave(mono + MIN, on("a", 61)), "Disconnect, background recreate: still no card");
        check(fresh.step(mono + 2 * MIN, on("a", 62)) == ChargeLiveHold.End.NONE && !fresh.active(), "and the recreated service's checks don't bring it back");
        fresh.opened("a");
        check(fresh.leave(mono + 3 * MIN, on("a", 62)), "until the owner opens Volt");

        // In the service: only Volt's screen in front ends a Disconnect.
        String src;
        try { src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        check(src.split("chargeLiveOpened\\(\\);", -1).length == 2, "one call of chargeLiveOpened");
        check(src.contains("if(!value&&wasVisible)armChargeLive();else if(value)chargeLiveOpened();"), "applyVisibility calls it only with Volt's screen in front");
        check(src.split("chargeLive\\.opened\\(", -1).length == 2, "chargeLive.opened only from chargeLiveOpened");
        check(src.contains("visible=uiVisible;running=this;"), "a new service starts from uiVisible, false when it was recreated in the background");
    }

    private static String between(String text, String from, String to) {
        int a = text.indexOf(from), b = a < 0 ? -1 : text.indexOf(to, a);
        if (a < 0 || b < 0) throw new AssertionError("ScooterService no longer has " + from + " … " + to);
        return text.substring(a, b);
    }
}
