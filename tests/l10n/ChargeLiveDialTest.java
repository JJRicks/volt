package com.jjricks.volt;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.function.LongFunction;

/**
 * The promoted live card's dial (the owner's pick A, 2026-11-08): when a level may go on it and what the title says then
 * (ChargeLiveState). Listed in tests/l10n/charge-live-dial.sources; tests/run.sh runs it.
 */
public final class ChargeLiveDialTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long MIN = 60_000L, H = 60 * MIN;
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

    /** The promoted card's dial and target-only title (owner's pick A, 2026-11-08): a level read just now, or no dial. */
    public static void main(String[] args) {
        ChargeLiveState a = ChargeLiveState.of(input(72, 80, 38.0, 412.0), CLOCK);
        check(Integer.valueOf(72).equals(a.dialPercent) && "Charging to 80%".equals(a.dialTitle), "with a limit: the level on the dial, the target in the title: " + a.dialTitle);
        check("Charging to 80% · 72%".equals(a.title), "the plain card's title doesn't change");
        ChargeLiveState full = ChargeLiveState.of(input(63, null, 85.0, null), CLOCK);
        check(Integer.valueOf(63).equals(full.dialPercent) && "Charging to full".equals(full.dialTitle), "no limit: charging to full: " + full.dialTitle);
        check("Charging to full".equals(ChargeLiveState.of(input(100, null, null, null), CLOCK).dialTitle)
                && Integer.valueOf(100).equals(ChargeLiveState.of(input(100, null, null, null), CLOCK).dialPercent), "a full battery fits the dial");
        check("Charging to full".equals(ChargeLiveState.of(input(63, 100, null, null), CLOCK).dialTitle), "a 100% limit reads as no limit");
        ChargeLiveState none = ChargeLiveState.of(input(null, 80, null, null), CLOCK);
        check(none.dialPercent == null && none.title.equals(none.dialTitle), "no level ever read: no dial, the plain title");

        ChargeLiveState.Input stale = input(72, 80, 38.0, 412.0);stale.percentStale = true;
        ChargeLiveState old = ChargeLiveState.of(stale, CLOCK);
        check(old.dialPercent == null && "Charging to 80% · 72%".equals(old.dialTitle), "an old level never goes on the dial; the title still says it: " + old.dialTitle);
        check(!old.dedupeKey.equals(a.dedupeKey), "a level going stale reposts the card");

        ChargeLiveState.Input reconnect = input(72, 80, null, null);reconnect.reconnecting = true;
        ChargeLiveState r = ChargeLiveState.of(reconnect, CLOCK);
        check(r.kind == ChargeLiveState.Kind.RECONNECTING && r.dialPercent == null && "Last seen 72%".equals(r.dialTitle), "reconnecting: no dial, last seen stays in the title: " + r.dialTitle);

        ChargeLiveState.Input wait = input(41, 80, null, null);wait.waiting = true;wait.startWallMs = NOW + 5 * H + 36 * MIN;
        ChargeLiveState w = ChargeLiveState.of(wait, CLOCK);
        check(Integer.valueOf(41).equals(w.dialPercent) && "Charging starts 3:40 AM".equals(w.dialTitle) && "Starts 3:40 AM · 41%".equals(w.title), "waiting: the level on the dial, the start time in the title: " + w.dialTitle);
        wait.percentStale = true;
        ChargeLiveState wo = ChargeLiveState.of(wait, CLOCK);
        check(wo.dialPercent == null && "Starts 3:40 AM · 41%".equals(wo.dialTitle), "waiting without a fresh level: no dial");

        java.util.function.ToDoubleFunction<String> width = text -> text.length() * 40.0;
        ChargeLiveState.DialFit roomy = ChargeLiveState.dialFit("72%", "72", 64f, 50f, 133f, width);
        check("72%".equals(roomy.text) && roomy.size == 64f, "room to spare: full size");
        ChargeLiveState.DialFit tight = ChargeLiveState.dialFit("100%", "100", 64f, 50f, 133f, width);
        check("100%".equals(tight.text) && tight.size >= 50f && tight.size < 64f, "100%: smaller, not below the floor: " + tight.size);
        ChargeLiveState.DialFit wide = ChargeLiveState.dialFit("100 %", "100", 64f, 50f, 133f, text -> text.length() * 60.0);
        check("100".equals(wide.text) && wide.size >= 40f, "too wide for the floor: the bare number instead of tiny text: " + wide.text + " at " + wide.size);
        System.out.println("ChargeLiveDialTest: " + checks + " checks passed");
    }

}
