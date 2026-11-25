package com.jjricks.volt;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Live Updates for 2.0.6 (owner's picks, Nov 24 2026): the live charging chip's choice (ChargeLiveState.Chip,
 * ChargeLiveUpdate.of), the finish time it shows and its fallback to the level, the connection notice's channel and the
 * ride notice's promotion (LiveNotices), a dismissed ride notice staying away for that ride, and the chip choice's backup
 * round trip. Listed in tests/l10n/live-updates.sources; tests/run.sh runs it.
 */
public final class LiveUpdatesTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        chipChoice();
        chipText();
        channel();
        ridePromotion();
        dismissal();
        backup();
        System.out.println("LiveUpdatesTest: " + checks + " checks passed");
    }

    /** The clock the service passes (DisplayTime.clock): here a label naming the wall time, so each time is told apart. */
    private static String clock(long wall) { return "clock@" + wall; }

    private static ChargeLiveState live(Integer pct, Double minutes, boolean waiting, long doneBy, boolean reconnecting) {
        ChargeLiveState.Input in = new ChargeLiveState.Input();
        in.percent = pct;in.limit = 80;in.minutesLeft = minutes;in.scooter = "Synthetic";in.nowWall = 1_000_000L;
        in.waiting = waiting;in.startWallMs = 2_000_000L;in.doneByWallMs = doneBy;in.reconnecting = reconnecting;
        return ChargeLiveState.of(in, LiveUpdatesTest::clock);
    }

    private static void chipChoice() {
        check(ChargeLiveState.Chip.of(null) == ChargeLiveState.Chip.LEVEL, "no choice saved: the level, as 2.0 ships it");
        check(ChargeLiveState.Chip.of("level") == ChargeLiveState.Chip.LEVEL && ChargeLiveState.Chip.of("finish") == ChargeLiveState.Chip.FINISH,
                "each stored id reads back as its choice");
        check(ChargeLiveState.Chip.of("FINISH") == ChargeLiveState.Chip.LEVEL && ChargeLiveState.Chip.of("") == ChargeLiveState.Chip.LEVEL,
                "anything else (a damaged or future value) is the default");
        check(ChargeLiveState.Chip.LEVEL.id.equals("level") && ChargeLiveState.Chip.FINISH.id.equals("finish"), "the stored ids");
    }

    private static void chipText() {
        ChargeLiveState charging = live(62, 30.0, false, 0, false);
        long done = 1_000_000L + 30 * 60_000L;
        check(clock(done).equals(charging.finish), "charging with an estimate: the card's finish time is the chip's: " + charging.finish);
        check(charging.text.contains(clock(done)), "the same time the card's text shows: " + charging.text);
        check("62%".equals(ChargeLiveUpdate.of(charging, null).shortCritical), "the two-argument bar keeps the level chip");
        check("62%".equals(ChargeLiveUpdate.of(charging, null, ChargeLiveState.Chip.LEVEL).shortCritical), "Level: the percent");
        check(clock(done).equals(ChargeLiveUpdate.of(charging, null, ChargeLiveState.Chip.FINISH).shortCritical), "Finish time: the finish time");
        ChargeLiveUpdate a = ChargeLiveUpdate.of(charging, 50, ChargeLiveState.Chip.LEVEL), b = ChargeLiveUpdate.of(charging, 50, ChargeLiveState.Chip.FINISH);
        check(a.progress == b.progress && a.indeterminate == b.indeterminate && a.segments.size() == b.segments.size() && a.points.size() == b.points.size(),
                "the choice changes the chip only, never the bar");

        // No estimate yet ("Estimating time left"): the level stands in.
        ChargeLiveState estimating = live(62, null, false, 0, false);
        check(estimating.finish == null, "estimating: no finish time");
        check("62%".equals(ChargeLiveUpdate.of(estimating, null, ChargeLiveState.Chip.FINISH).shortCritical), "Finish time without an estimate: the level");
        check("62%".equals(ChargeLiveUpdate.of(live(62, 0.0, false, 0, false), null, ChargeLiveState.Chip.FINISH).shortCritical),
                "a zero-minute estimate shows no finish time on the card, so the chip has the level too");
        // Out of reach: the card shows "Out of reach · reconnecting", not a time.
        ChargeLiveState away = live(62, 30.0, false, 0, true);
        check(away.finish == null && "62%".equals(ChargeLiveUpdate.of(away, null, ChargeLiveState.Chip.FINISH).shortCritical), "reconnecting: the last level");
        // A schedule waits: Charge done by's finish is the card's time; Scheduled charging alone has none.
        ChargeLiveState doneBy = live(41, null, true, 3_000_000L, false);
        check(clock(3_000_000L).equals(doneBy.finish) && doneBy.text.contains(clock(3_000_000L)), "Charge done by: its finish time, as the card says");
        check(clock(3_000_000L).equals(ChargeLiveUpdate.of(doneBy, null, ChargeLiveState.Chip.FINISH).shortCritical), "Finish time while Charge done by waits");
        check("41%".equals(ChargeLiveUpdate.of(doneBy, null, ChargeLiveState.Chip.LEVEL).shortCritical), "Level while it waits: the percent");
        ChargeLiveState scheduled = live(41, null, true, 0, false);
        check(scheduled.finish == null && "41%".equals(ChargeLiveUpdate.of(scheduled, null, ChargeLiveState.Chip.FINISH).shortCritical),
                "Scheduled charging has no finish time: the level");
        // Neither a level nor a time: no chip text (Android shows the icon).
        check(ChargeLiveUpdate.of(live(null, null, false, 0, false), null, ChargeLiveState.Chip.FINISH).shortCritical == null, "nothing to show: no text");
        check(clock(done).equals(ChargeLiveUpdate.of(live(null, 30.0, false, 0, false), null, ChargeLiveState.Chip.FINISH).shortCritical),
                "a finish time without a level still shows");
        // A finish time that moves reposts the card (its dedupe key holds the time as shown).
        check(!live(62, 30.0, false, 0, false).dedupeKey.equals(live(62, 31.0, false, 0, false).dedupeKey), "a new finish time is a change");
    }

    private static void channel() {
        String quiet = LiveNotices.QUIET_CHANNEL, scooter = LiveNotices.SCOOTER_CHANNEL;
        check(quiet.equals("rides_charging") && scooter.equals("scooter"), "the channel ids");
        check(quiet.equals(LiveNotices.channel(true, false, true)), "a recording ride: the quiet channel");
        check(quiet.equals(LiveNotices.channel(false, true, true)), "the live charging card: the quiet channel");
        check(scooter.equals(LiveNotices.channel(false, false, true)), "Scooter connected and every other notice: the connection channel");
        check(scooter.equals(LiveNotices.channel(true, false, false)) && scooter.equals(LiveNotices.channel(false, true, false)),
                "the quiet channel turned off: back on the connection channel, so Stop recording and Disconnect still show");
        check(scooter.equals(LiveNotices.channel(false, false, false)), "neither: the connection channel");
    }

    private static void ridePromotion() {
        check(LiveNotices.promoteRide(36, true, true, true) && LiveNotices.promoteRide(37, true, true, true), "Android 16 and later, allowed, the clock showing: asks");
        check(!LiveNotices.promoteRide(35, true, true, true), "never before Android 16");
        check(!LiveNotices.promoteRide(36, false, true, true), "not without a promotion request on this Android");
        check(!LiveNotices.promoteRide(36, true, false, true), "not with Live updates off for Volt");
        check(!LiveNotices.promoteRide(36, true, true, false), "a ride notice without its clock (a recording error) stays plain");
    }

    private static void dismissal() {
        LiveNotices.Dismissal d = new LiveNotices.Dismissal();
        check(!d.holds(true) && !d.active(), "a ride's notice posts until it is dismissed");
        d.dismissed();
        check(d.holds(true) && d.holds(true) && d.active(), "swiped away: the ride's notice stays away for the rest of the ride");
        check(!d.holds(false) && !d.active(), "the ride ended (saving, Connected): the next notice posts, and the mark is gone");
        check(!d.holds(true), "so the next ride's notice shows again");
        d.dismissed();
        check(!d.holds(false), "a notice that isn't a ride's is never held back");
        // #644 F08: after a swipe, a recording error or a sensor warning (not the ride clock's Live Update) always posts,
        // and the hold is over: the clock's Live Update that follows posts too.
        d.dismissed();
        check(d.holds(true), "swiped: the ride clock's Live Update is held");
        check(!d.holds(false) && !d.active(), "a recording error or a sensor warning posts and ends the hold");
        check(!d.holds(true), "the ride clock that follows the error posts again");
        // #644 F06: opening Volt shows the notice again (startForeground); from then on its updates post.
        d.dismissed();
        check(d.holds(true), "swiped again: held");
        d.reshown();
        check(!d.active() && !d.holds(true) && !d.holds(true), "reshown by the foreground start: every later update posts");
        // The live charging card (owner, Nov 24): swiped, it stays gone for the rest of that charge, as the service combines
        // the two (LiveNotices.held).
        LiveNotices.Dismissal card = new LiveNotices.Dismissal(), ride = new LiveNotices.Dismissal();
        check(!LiveNotices.held(card, true, ride, false), "the card's Live Update posts until it is swiped");
        card.dismissed();
        check(LiveNotices.held(card, true, ride, false) && LiveNotices.held(card, true, ride, false), "swiped: the card stays gone while its values change");
        check(!ride.active(), "the card's swipe isn't the ride's");
        check(!LiveNotices.held(card, false, ride, false) && !card.active(), "unplugged (Scooter connected), a stopped charger's ordinary card or an alert: it posts and ends the hold");
        check(!LiveNotices.held(card, true, ride, false), "so the next charge's card shows");
        card.dismissed();
        check(LiveNotices.held(card, true, ride, false), "swiped again: held");
        card.reshown();
        check(!LiveNotices.held(card, true, ride, false), "opening Volt shows it again (startForeground): later updates post");
        ride.dismissed();
        check(!LiveNotices.held(card, true, ride, false) && !ride.active(), "a card that shows ends a ride's hold, as any other notice does");
        check(LiveNotices.held(card, false, ride, true) == false, "and the ride that follows posts");
    }

    private static void backup() throws Exception {
        String key = "charge_live_chip";
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", key);
        check(rule != null && BackupRegistry.PREFERENCES.equals(rule.category) && rule.type == String.class && !rule.perProfile,
                "the chip choice travels with App preferences, a choice for the whole app");
        int ours = DiagnosticsSettings.KEYS.indexOf(key), lock = DiagnosticsSettings.KEYS.indexOf("charge_live_lock_detail");
        check(ours >= 0 && ours == lock + 1, "the diagnostics settings list it next to the lock screen choice");
        File root = Files.createTempDirectory("live-updates").toFile();
        try {
            String[] states = {null, "level", "finish"};
            int n = 0;
            for (String backed : states) for (String phone : states) {
                BackupTestStore source = new BackupTestStore(new File(root, "source-" + n));
                if (backed != null) source.put("app_settings", key, backed);
                source.put("app_settings", "charge_live_lock_detail", "level");
                BackupTestStore target = new BackupTestStore(new File(root, "phone-" + n++));
                if (phone != null) target.put("app_settings", key, phone);
                Set<String> chosen = Collections.singleton(BackupRegistry.PREFERENCES);
                BackupArchive.Plan plan = target.plan(target.stage(source.export(chosen)), chosen, false, null);
                BackupTransaction.apply(target, plan);
                Map<String, Object> after = target.preferences().get("app_settings");
                check(Objects.equals(after.get(key), backed), "the chip choice restores as backed up (" + backed + " over " + phone + "): " + after.get(key));
                check("level".equals(after.get("charge_live_lock_detail")), "beside the lock screen choice");
                check(ChargeLiveState.Chip.of((String) after.get(key)) == (backed == null || backed.equals("level") ? ChargeLiveState.Chip.LEVEL : ChargeLiveState.Chip.FINISH),
                        "and reads back as the backed-up choice");
            }
        } finally { delete(root); }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
