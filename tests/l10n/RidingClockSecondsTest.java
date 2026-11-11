package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Riding mode's clock with seconds (RidingClockSeconds, a tester's request): the stored switch reads as off
 * unless it's a stored true, the tick lands just after each second turns and runs only while the switch is
 * on, a clock is on the frame and the activity is resumed, and the AM/PM leaves a tight spot before the
 * seconds would. Source checks tie RidingScreen, the tune menu and BackupRegistry to it.
 * Listed in tests/l10n/riding-clock-seconds.sources; tests/run.sh runs it from the repository root.
 */
public final class RidingClockSecondsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws IOException {
        stored();
        delays();
        tickLifecycle();
        withoutMarker();
        marker();
        wiring();
        System.out.println("RidingClockSecondsTest: " + checks + " checks passed");
    }

    /** A 1.13.0 backup or saved state has no key; a null or malformed value reads as off too. */
    private static void stored() {
        check(!RidingClockSeconds.on(null), "absent or null: off");
        check(!RidingClockSeconds.on(Boolean.FALSE), "false: off");
        check(RidingClockSeconds.on(Boolean.TRUE), "true: on");
        check(!RidingClockSeconds.on(String.valueOf(true)), "a string: off");
        check(!RidingClockSeconds.on(1), "a number: off");
        check(!RidingClockSeconds.on(1L), "a long: off");
    }

    private static void delays() {
        long late = RidingClockSeconds.LATE_MS;
        check(RidingClockSeconds.delayMs(1_700_000_000_000L) == 1000 + late, "on a whole second: wait for the next one");
        check(RidingClockSeconds.delayMs(1_700_000_000_999L) == 1 + late, "a millisecond before: just after it turns");
        check(RidingClockSeconds.delayMs(1_700_000_000_400L) == 600 + late, "mid-second: the rest of it");
        check(RidingClockSeconds.delayMs(-1) == 1 + late, "before 1970: still the next whole second");
        check(RidingClockSeconds.delayMs(-1000) == 1000 + late, "a whole second before 1970");
        for (long wall = -3000; wall < 3000; wall += 7) {
            long delay = RidingClockSeconds.delayMs(wall);
            check(delay > late && delay <= 1000 + late, "the wait stays within a second: " + wall);
            check(Math.floorMod(wall + delay, 1000L) == late, "it lands just after a second turns: " + wall);
        }
    }

    private static final class FakeTimer implements RidingClockSeconds.Timer {
        final List<Long> posts = new ArrayList<>();
        int cancels;
        boolean waiting;
        @Override public void post(long delayMs) {
            if (waiting) throw new AssertionError("a second tick posted while one waits");
            posts.add(delayMs);
            waiting = true;
        }
        @Override public void cancel() { cancels++; waiting = false; }
        /** The handler runs the waiting tick. */
        void run() { waiting = false; }
    }

    private static void tickLifecycle() {
        // Switch off, or no clock on the frame: nothing ever posts.
        for (boolean[] c : new boolean[][]{{false, true}, {true, false}, {false, false}}) {
            FakeTimer t = new FakeTimer();
            RidingClockSeconds.Tick tick = new RidingClockSeconds.Tick(c[0], c[1], true, t);
            tick.sync(1000);
            tick.resumed(false, 0);
            tick.resumed(true, 2000);
            check(t.posts.isEmpty() && !tick.live() && !tick.posted(), "off or no clock: no timer at all");
            tick.close();
            check(t.posts.isEmpty(), "closing posts nothing");
        }

        // Built while resumed: the first refresh posts one tick, aligned; more refreshes don't add one.
        FakeTimer t = new FakeTimer();
        RidingClockSeconds.Tick tick = new RidingClockSeconds.Tick(true, true, true, t);
        check(tick.live() && !tick.posted(), "built resumed: wanted, not yet posted");
        tick.sync(10_250);
        tick.sync(10_400);
        tick.sync(10_900);
        check(t.posts.size() == 1 && t.posts.get(0) == 750 + RidingClockSeconds.LATE_MS, "one aligned tick however often refresh runs");

        // It fires: redraw, and the next one waits from the wall clock read then.
        t.run();
        check(tick.fired(11_015), "the tick redraws");
        check(t.posts.size() == 2 && t.posts.get(1) == 985 + RidingClockSeconds.LATE_MS, "the next tick is posted from the new wall time");

        // The wall clock jumps back an hour, then forward a day: still one tick, under a second away.
        t.run();
        check(tick.fired(11_500 - 3_600_000), "a tick after the clock went back still redraws");
        check(t.posts.get(2) == 500 + RidingClockSeconds.LATE_MS, "and waits for the next whole second of the new time");
        t.run();
        check(tick.fired(86_400_000L + 123), "a tick after the clock went forward");
        check(t.posts.get(3) == 877 + RidingClockSeconds.LATE_MS, "aligned to the new time, no drift");

        // Pause stops it at once; a refresh while paused doesn't restart it.
        tick.resumed(false, 0);
        check(!t.waiting && t.cancels == 1 && !tick.posted() && !tick.live(), "pause cancels the waiting tick");
        tick.sync(90_000_000L);
        check(t.posts.size() == 4, "a refresh while paused posts nothing");
        // A tick already handed to the looper before the pause: it neither redraws nor reposts.
        check(!tick.fired(90_000_100L), "a stale tick after pause doesn't redraw");
        check(t.posts.size() == 4, "nor post another");

        // Resume restarts it, aligned to the time then.
        tick.resumed(true, 90_000_300L);
        check(t.waiting && t.posts.size() == 5 && t.posts.get(4) == 700 + RidingClockSeconds.LATE_MS, "resume posts one aligned tick");
        tick.resumed(true, 90_000_500L);
        check(t.posts.size() == 5, "a second resume adds none");

        // Close (leaving, a rebuild for a frame without a clock, the activity going): stopped for good.
        tick.close();
        check(!t.waiting && !tick.posted() && !tick.live(), "close cancels");
        tick.resumed(true, 91_000_000L);
        tick.sync(91_000_000L);
        check(t.posts.size() == 5 && !tick.fired(91_000_100L), "nothing restarts a closed tick");

        // Built while paused (rebuilt in the background): nothing until the resume.
        FakeTimer p = new FakeTimer();
        RidingClockSeconds.Tick background = new RidingClockSeconds.Tick(true, true, false, p);
        background.sync(5_000);
        check(p.posts.isEmpty() && !background.live(), "built paused: no tick");
        background.resumed(true, 5_000);
        check(p.posts.size() == 1 && p.posts.get(0) == 1000 + RidingClockSeconds.LATE_MS, "resume starts it");
        background.close();
        check(!p.waiting, "close while waiting cancels");
    }

    private static String without(String pattern, Locale locale, long wall) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return RidingClockSeconds.without(format.formatToCharacterIterator(new Date(wall)), DateFormat.Field.AM_PM);
    }

    private static void withoutMarker() {
        long afternoon = ((13 * 60 + 48) * 60 + 5) * 1000L;
        check("1:48:05".equals(without("h:mm:ss a", Locale.US, afternoon)), "marker after: dropped with its space");
        check("1:48:05".equals(without("a h:mm:ss", Locale.KOREA, afternoon)), "marker before: dropped with its space");
        check("1:48:05".equals(without("ah:mm:ss", Locale.CHINA, afternoon)), "marker touching the time");
        check("1:48:05".equals(without("h:mm:ss a", Locale.US, afternoon)), "a narrow no-break space goes too");
        check("at 01:48:05".equals(without("'at' a hh:mm:ss", Locale.US, afternoon)), "a quoted literal stays; the doubled space closes up");
        check(without("HH:mm:ss", Locale.US, afternoon) == null, "24-hour: nothing to drop");
        check(without("a", Locale.US, afternoon) == null, "a marker alone leaves nothing to show");
        String full = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(afternoon));
        check(full.contains(":") && full.length() > 7, "the full form keeps the marker: " + full);
    }

    private static void marker() {
        float min = RidingClockSeconds.MARKER_MIN_DP;
        check(RidingClockSeconds.keepsMarker(100, 100, min), "fits at the spot's largest size: kept");
        check(RidingClockSeconds.keepsMarker(20, 20, min), "a small spot showing it at its own largest size: kept");
        check(RidingClockSeconds.keepsMarker(min, 100, min), "at the floor: kept");
        check(!RidingClockSeconds.keepsMarker(min - .5f, 100, min), "below the floor: the marker goes first");
        check(!RidingClockSeconds.keepsMarker(19, 20, min), "a small spot shrinking: the marker goes");
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void wiring() throws IOException {
        String backup = read("src/com/jjricks/volt/BackupRegistry.java");
        check(backup.contains("add(app, RidingClockSeconds.KEY, Boolean.class, PREFERENCES);"), "backed up with the riding preferences");
        String more = read("src/com/jjricks/volt/MoreScreen.java");
        check(more.contains("toggle(T.ridingDisplayClockSeconds(), T.ridingDisplayClockSecondsSub(), RidingClockSeconds.KEY,"), "the tune menu's switch");
        check(more.contains("RidingClockSeconds.on(preferences.getAll().get(key))"), "the menu reads a malformed value as off");
        String screen = read("src/com/jjricks/volt/RidingScreen.java");
        check(screen.contains("RidingClockSeconds.on(preferences.getAll().get(RidingClockSeconds.KEY))"), "riding mode reads it the same way");
        check(screen.contains("clockTick.close();"), "closing the screen closes the tick");
        check(screen.contains("unregisterActivityLifecycleCallbacks(clockLifecycle)"), "and stops listening for pause and resume");
        check(screen.contains("clockTick.sync(wallNow);"), "every refresh syncs it");
        check(screen.contains("setFontFeatureSettings(seconds ? \"tnum\" : null)"), "cells use tabular figures");
        check(count(screen, "String now = spokenNow(content);") == 2, "cells and chips speak the seconds only when reached");
        String strings = read("res/values/strings_riding_display.xml");
        check(strings.contains("name=\"riding_display_clock_seconds\"") && strings.contains("name=\"riding_display_clock_seconds_sub\""), "the switch's English text");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
