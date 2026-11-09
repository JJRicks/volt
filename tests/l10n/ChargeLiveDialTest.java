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

    /** Asking isn't being promoted (Codex on #336): the dial follows what Android did, and the card settles. */
    private static void look() {
        ChargeLiveUpdate.Look look = new ChargeLiveUpdate.Look();
        check(!look.dial(), "before any read-back: the plain card");
        for (int i = 0; i < 5; i++) check(!look.observed(false) && !look.dial(), "a request Android keeps declining stays plain and never reposts");
        check(look.observed(true) && look.dial(), "read back promoted: one rebuild, with the dial");
        check(!look.observed(true) && look.dial(), "still promoted: no repost");
        check(look.observed(false) && !look.dial(), "dropped later: one rebuild, plain again");
        for (int i = 0; i < 5; i++) {
            check(!look.observed(true) && !look.dial(), "plain for the rest of the hold, even if Android promotes it again");
            check(!look.observed(false) && !look.dial(), "and no repost either way");
        }
        look.reset();
        check(!look.dial() && look.observed(true) && look.dial(), "the next hold starts fresh");
    }

    /** The parts of ScooterService that only Android runs: read from the source. */
    private static void service() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/ScooterService.java")), java.nio.charset.StandardCharsets.UTF_8);
        int from = src.indexOf("private void chargeLiveCard("), to = src.indexOf("// ---- Live Update promotion");
        check(from > 0 && to > from, "found chargeLiveCard");
        String card = src.substring(from, to);
        check(card.contains("asked&&chargeLiveLook.dial()&&live.dialPercent!=null"), "the dial needs the request and Android's promotion");
        check(card.contains(".setContentTitle(live.publicTitle)") && !card.substring(card.indexOf("Notification.Builder lock=")).contains("dialTitle"), "the lock screen keeps its title, with the level in words");
        check(src.contains("chargeLiveLook.observed(promoted)&&chargeLiveShown())updateNotification()"), "a changed verdict rebuilds a card that's still showing");
        check(src.contains("chargeLiveReposts.reset();chargeLiveLook.reset();"), "each hold starts fresh");
    }

    private static ChargeLiveState.Input input(Integer pct, Integer limit, Double minutes, Double watts) {
        ChargeLiveState.Input in = new ChargeLiveState.Input();
        in.percent = pct;in.limit = limit;in.minutesLeft = minutes;in.watts = watts;in.scooter = "UT5 MAX";in.nowWall = NOW;return in;
    }

    /** The promoted card's dial and target-only title (owner's pick A, 2026-11-08): a level read just now, or no dial. */
    public static void main(String[] args) throws Exception {
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
        look();
        service();
        System.out.println("ChargeLiveDialTest: " + checks + " checks passed");
    }

}
