package com.jjricks.volt;

import com.jjricks.volt.MaintenancePolicy.Reminder;
import com.jjricks.volt.MaintenanceStore.Settings;

import java.time.ZoneId;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Maintenance plan, PR 3: when a reminder notifies. The daily job's scheduling rules, one notice per due
 * cycle, a snooze's end, a restart (a new process reading the same bookkeeping), a quiet page change, and
 * a check that is stopped or whose reminder is turned off while it runs.
 */
public final class MaintenanceNotifyTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, DAY = 86_400_000L;
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final BooleanSupplier LIVE = () -> true;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    /** app_settings in memory; a hook can change the document on a given read, as another thread would. */
    private static final class Memory implements MaintenanceKeeper.Storage {
        final Map<String, String> map = new HashMap<>();
        int reads, seenWrites;
        Runnable onRead; int hookAt = -1;
        public String read(String key) {
            if (key.startsWith(MaintenanceStore.PREFIX) && ++reads == hookAt && onRead != null) onRead.run();
            return map.get(key);
        }
        public void write(String key, String value) { if (key.startsWith(MaintenanceStore.SEEN_PREFIX)) seenWrites++; map.put(key, value); }
        public void remove(String key) { map.remove(key); }
    }

    /** Records what would post. */
    private static final class Shade implements MaintenanceNotify.Poster {
        final List<List<String>> posts = new ArrayList<>(); final List<String> cancels = new ArrayList<>();
        public void post(String scope, List<Reminder> due) { List<String> ids = new ArrayList<>(); for (Reminder r : due) ids.add(r.id); posts.add(ids); }
        public void cancel(String scope) { cancels.add(scope); }
    }

    public static void main(String[] args) {
        scheduling();
        oncePerCycle();
        snoozeEnds();
        restart();
        quietPageChange();
        grouped();
        stoppedOrTurnedOff();
        simulationAndForget();
        bookkeeping();
        replanAfterSnooze();
        taps();
        System.out.println("MaintenanceNotifyTest: " + checks + " checks passed (job rules, once per cycle, snooze end, restart, quiet page change, grouped notice, stop and opt-out races, simulation, forget, bookkeeping, re-plan after a snooze, notice taps)");
    }

    private static Reminder time(String id, int days, long base, boolean notify) {
        return MaintenancePolicy.create(id, "", "Check " + id, null, days, 0, notify, base, Double.NaN, base);
    }

    private static Reminder distance(String id, double km, double baseAcc, boolean notify) {
        return MaintenancePolicy.create(id, "", "Check " + id, km, 0, 0, notify, WALL - DAY, baseAcc, WALL - DAY);
    }

    private static MaintenanceKeeper keeper(Memory memory, String scope, Reminder... reminders) {
        MaintenanceKeeper keeper = new MaintenanceKeeper(memory);
        keeper.update(scope, s -> { s.on = true; for (Reminder r : reminders) MaintenancePolicy.add(s.reminders, r); });
        return keeper;
    }

    private static void scheduling() {
        Settings off = new Settings();
        check(!MaintenanceNotify.needsJob(off, WALL), "off: no job");
        Settings none = new Settings(); none.on = true;
        check(!MaintenanceNotify.needsJob(none, WALL), "on with no reminders: no job");
        Settings dist = new Settings(); dist.on = true; dist.reminders.add(distance("d", 100, 0, true));
        check(!MaintenanceNotify.needsJob(dist, WALL), "a distance reminder becomes due only while connected: no job");
        Settings timed = new Settings(); timed.on = true; timed.reminders.add(time("t", 14, WALL, true));
        check(MaintenanceNotify.needsJob(timed, WALL), "a notify time reminder: the job");
        timed.reminders.get(0).notify = false;
        check(!MaintenanceNotify.needsJob(timed, WALL), "a time reminder that doesn't notify: no job");
        timed.reminders.get(0).notify = true; timed.on = false;
        check(!MaintenanceNotify.needsJob(timed, WALL), "reminders off: no job");
        dist.reminders.get(0).snoozeUntil = WALL + DAY;
        check(MaintenanceNotify.needsJob(dist, WALL), "a running snooze can end by time: the job");
        check(!MaintenanceNotify.needsJob(dist, WALL + 2 * DAY), "after the snooze: no job");
        timed.on = true;
        check(MaintenanceNotify.needsJob(Arrays.asList(off, dist, timed), WALL + 2 * DAY), "any scooter that needs it");
        check(!MaintenanceNotify.needsJob(Arrays.asList(off, none, dist), WALL + 2 * DAY), "no scooter needs it");
        check(MaintenanceNotify.PERIOD_MS == DAY && MaintenanceNotify.FLEX_MS > 0 && MaintenanceNotify.FLEX_MS < DAY, "about once a day");
    }

    private static void oncePerCycle() {
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", time("tires", 7, WALL - 8 * DAY, true), time("bolts", 30, WALL - DAY, true));
        check(MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, LIVE, shade), "a due reminder posts");
        check(shade.posts.size() == 1 && shade.posts.get(0).equals(Collections.singletonList("tires")), "only the due one: " + shade.posts);
        check(memory.map.containsKey(MaintenanceStore.seenKey("p1")), "bookkeeping in maintenance_seen_");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 3_600_000, UTC, false, LIVE, shade), "the same cycle never posts again");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 2 * DAY, UTC, false, LIVE, shade) && shade.posts.size() == 1, "nor days later");
        keeper.update("p1", s -> MaintenancePolicy.markDone(s.find("tires"), WALL + 2 * DAY, Double.NaN, WALL + 2 * DAY));
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 5 * DAY, UTC, false, LIVE, shade), "not due yet in the new cycle");
        check(!MaintenanceNotify.decodeSeen(memory.map.get(MaintenanceStore.seenKey("p1"))).containsKey("tires"), "done: the entry goes");
        check(MaintenanceNotify.check(keeper, "p1", WALL + 9 * DAY, UTC, false, LIVE, shade), "the next cycle posts once");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 9 * DAY + 60_000, UTC, false, LIVE, shade), "once");
        Memory quietOff = new Memory();
        MaintenanceKeeper muted = keeper(quietOff, "p2", time("tires", 7, WALL - 8 * DAY, false));
        Shade none = new Shade();
        check(!MaintenanceNotify.check(muted, "p2", WALL, UTC, false, LIVE, none) && none.posts.isEmpty(), "notify off: no notice");
        muted.listen((scope, forgotten) -> MaintenanceNotify.check(muted, scope, WALL, UTC, true, LIVE, none));
        muted.update("p2", s -> s.on = false);
        muted.update("p2", s -> { s.on = true; s.find("tires").notify = true; });
        check(!MaintenanceNotify.check(muted, "p2", WALL, UTC, false, LIVE, none), "turned on from the page while due: seen, no notice");
        Memory offMemory = new Memory(); Shade offShade = new Shade();
        MaintenanceKeeper off = keeper(offMemory, "p3", time("tires", 7, WALL - 8 * DAY, true));
        off.update("p3", s -> s.on = false);
        check(!MaintenanceNotify.check(off, "p3", WALL, UTC, false, LIVE, offShade) && offShade.posts.isEmpty() && offShade.cancels.contains("p3"), "reminders off: no notice, and any notice goes");
    }

    private static void snoozeEnds() {
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", time("tires", 7, WALL - 8 * DAY, true));
        check(MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, LIVE, shade), "due posts");
        keeper.update("p1", s -> MaintenancePolicy.snooze(s.find("tires"), 3, WALL + 1000, Double.NaN, UTC));
        check(!MaintenanceNotify.check(keeper, "p1", WALL + DAY, UTC, false, LIVE, shade), "no notice while snoozed");
        check(MaintenanceNotify.decodeSeen(memory.map.get(MaintenanceStore.seenKey("p1"))).containsKey("tires"), "snoozed: the entry stays");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 3 * DAY, UTC, false, LIVE, shade), "still snoozed just before its end");
        check(MaintenanceNotify.check(keeper, "p1", WALL + 3 * DAY + 2000, UTC, false, LIVE, shade), "the snooze ended: one more notice");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 4 * DAY, UTC, false, LIVE, shade) && shade.posts.size() == 2, "and only one");
        // Snoozed before any notice (from the page): the end still notifies once.
        Memory early = new Memory(); Shade earlyShade = new Shade();
        MaintenanceKeeper page = keeper(early, "p2", time("brakes", 7, WALL - 8 * DAY, true));
        page.update("p2", s -> MaintenancePolicy.snooze(s.find("brakes"), 1, WALL, Double.NaN, UTC));
        check(!MaintenanceNotify.check(page, "p2", WALL + 3_600_000, UTC, false, LIVE, earlyShade), "snoozed from the page: quiet");
        check(MaintenanceNotify.check(page, "p2", WALL + DAY + 1000, UTC, false, LIVE, earlyShade), "its end notifies");
    }

    private static void restart() {
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", time("tires", 7, WALL - 8 * DAY, true));
        check(MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, LIVE, shade), "posts before a restart");
        // After a restart: a new process (the daily job) reads the same app_settings.
        MaintenanceKeeper booted = new MaintenanceKeeper(memory);
        check(!MaintenanceNotify.check(booted, "p1", WALL + DAY, UTC, false, LIVE, shade) && shade.posts.size() == 1, "no repeat after a restart");
        // A restored backup has no bookkeeping (never backed up): what is due notifies once on this phone.
        memory.map.remove(MaintenanceStore.seenKey("p1"));
        check(MaintenanceNotify.check(booted, "p1", WALL + DAY, UTC, false, LIVE, shade), "a restored phone notifies once");
        check(!MaintenanceNotify.check(booted, "p1", WALL + DAY, UTC, false, LIVE, shade), "once");
    }

    private static void quietPageChange() {
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", time("tires", 7, WALL - 8 * DAY, true));
        List<String> heard = new ArrayList<>();
        keeper.listen((scope, forgotten) -> { heard.add(scope + forgotten); MaintenanceNotify.check(keeper, scope, WALL, UTC, true, LIVE, shade); });
        keeper.update("p1", s -> s.find("tires").name = "Tire pressure");
        check(heard.size() == 1 && shade.posts.isEmpty() && shade.cancels.contains("p1"), "a page change: quiet, and the notice goes");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 60_000, UTC, false, LIVE, shade), "what the page showed counts as seen");
    }

    private static void grouped() {
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", time("a", 7, WALL - 8 * DAY, true), time("b", 10, WALL - 8 * DAY, true), time("c", 7, WALL - 8 * DAY, false));
        check(MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, LIVE, shade) && shade.posts.get(0).equals(Collections.singletonList("a")), "one due");
        check(MaintenanceNotify.check(keeper, "p1", WALL + 3 * DAY, UTC, false, LIVE, shade) && shade.posts.get(1).equals(Arrays.asList("a", "b")), "a second one: one notice listing both");
        check(!MaintenanceNotify.check(keeper, "p1", WALL + 4 * DAY, UTC, false, LIVE, shade), "then quiet");
    }

    private static void stoppedOrTurnedOff() {
        // Stopped before it read anything.
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", time("tires", 7, WALL - 8 * DAY, true));
        check(!MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, () -> false, shade) && shade.posts.isEmpty() && memory.seenWrites == 0, "a stopped run does nothing");
        // Stopped while it ran: the token ends after the first ask.
        int[] asks = {0};
        check(!MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, () -> asks[0]++ == 0, shade), "stopped mid-run: no notice");
        check(shade.posts.isEmpty() && shade.cancels.isEmpty() && memory.seenWrites == 0, "and nothing written");
        check(MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, LIVE, shade), "the next run still posts it");
        // Reminders turned off while it ran (another thread writes between its two reads).
        Memory racing = new Memory(); Shade raceShade = new Shade();
        MaintenanceKeeper raced = keeper(racing, "p2", time("tires", 7, WALL - 8 * DAY, true));
        racing.reads = 0; racing.hookAt = 2;
        racing.onRead = () -> { Settings s = MaintenanceStore.decode(racing.map.get(MaintenanceStore.key("p2"))); s.on = false; racing.map.put(MaintenanceStore.key("p2"), MaintenanceStore.encode(s)); };
        check(!MaintenanceNotify.check(raced, "p2", WALL, UTC, false, LIVE, raceShade) && raceShade.posts.isEmpty() && racing.seenWrites == 0, "turned off mid-run: no notice");
        // The reminder's own notify switch turned off mid-run.
        Memory muting = new Memory(); Shade muteShade = new Shade();
        MaintenanceKeeper muted = keeper(muting, "p3", time("tires", 7, WALL - 8 * DAY, true));
        muting.reads = 0; muting.hookAt = 2;
        muting.onRead = () -> { Settings s = MaintenanceStore.decode(muting.map.get(MaintenanceStore.key("p3"))); s.find("tires").notify = false; muting.map.put(MaintenanceStore.key("p3"), MaintenanceStore.encode(s)); };
        check(!MaintenanceNotify.check(muted, "p3", WALL, UTC, false, LIVE, muteShade) && muteShade.posts.isEmpty(), "its notify switch turned off mid-run: no notice");
        // Marked done mid-run.
        Memory doing = new Memory(); Shade doneShade = new Shade();
        MaintenanceKeeper done = keeper(doing, "p4", time("tires", 7, WALL - 8 * DAY, true));
        doing.reads = 0; doing.hookAt = 2;
        doing.onRead = () -> { Settings s = MaintenanceStore.decode(doing.map.get(MaintenanceStore.key("p4"))); MaintenancePolicy.markDone(s.find("tires"), WALL, Double.NaN, WALL); doing.map.put(MaintenanceStore.key("p4"), MaintenanceStore.encode(s)); };
        check(!MaintenanceNotify.check(done, "p4", WALL, UTC, false, LIVE, doneShade) && doneShade.posts.isEmpty(), "marked done mid-run: no notice");
    }

    private static void simulationAndForget() {
        Memory memory = new Memory(); Shade shade = new Shade();
        String sim = MaintenanceKeeper.simulationScope("p1");
        MaintenanceKeeper keeper = keeper(memory, sim, distance("brakes", 100, 0, true));
        keeper.observe(sim, 1000, MaintenanceDistance.BITS_32, WALL);
        check(!MaintenanceNotify.check(keeper, sim, WALL, UTC, false, LIVE, shade), "simulation: not due yet");
        keeper.observe(sim, 1100, MaintenanceDistance.BITS_32, WALL + 60_000);
        check(MaintenanceNotify.check(keeper, sim, WALL + 60_000, UTC, false, LIVE, shade), "simulation: due by distance posts");
        check(!MaintenanceNotify.check(keeper, sim, WALL + 120_000, UTC, false, LIVE, shade), "once");
        check(memory.map.isEmpty(), "the simulator writes nothing to app_settings: " + memory.map.keySet());
        check(MaintenanceKeeper.profileOf(sim).equals("p1") && MaintenanceKeeper.profileOf("p1").equals("p1"), "a scope's profile");
        Memory real = new Memory();
        MaintenanceKeeper forget = keeper(real, "p9", time("tires", 7, WALL - 8 * DAY, true));
        MaintenanceNotify.check(forget, "p9", WALL, UTC, false, LIVE, shade);
        List<String> heard = new ArrayList<>();
        forget.listen((scope, forgotten) -> heard.add(scope + forgotten));
        forget.forget("p9");
        check(!real.map.containsKey(MaintenanceStore.seenKey("p9")) && heard.equals(Collections.singletonList("p9true")), "forget: the bookkeeping goes and the listener hears it");
    }

    private static void bookkeeping() {
        Map<String, MaintenanceNotify.Cycle> seen = new LinkedHashMap<>();
        seen.put("r1", new MaintenanceNotify.Cycle(WALL, Double.NaN, 0));
        seen.put("r2", new MaintenanceNotify.Cycle(WALL - DAY, 1661.8, WALL + DAY));
        check(MaintenanceNotify.decodeSeen(MaintenanceNotify.encodeSeen(seen)).equals(seen), "round trip, NaN distance kept");
        check(MaintenanceNotify.decodeSeen(null).isEmpty() && MaintenanceNotify.decodeSeen("").isEmpty() && MaintenanceNotify.decodeSeen("{not json").isEmpty()
                && MaintenanceNotify.decodeSeen("[1,2]").isEmpty(), "unreadable means nothing notified");
        check(MaintenanceNotify.decodeSeen("{\"r1\":{\"base\":5},\"r2\":{\"base\":1,\"snooze\":0}}").keySet().equals(Collections.singleton("r2")), "an incomplete entry is skipped");
        check(!new MaintenanceNotify.Cycle(1, 2, 3).equals(new MaintenanceNotify.Cycle(1, 2, 4)) && new MaintenanceNotify.Cycle(1, Double.NaN, 3).equals(new MaintenanceNotify.Cycle(1, Double.NaN, 3)), "cycle equality");
    }

    /**
     * #267 review: a distance reminder is snoozed while the scooter is away, so the daily job is planned for
     * the snooze. The snooze ends, and Volt starts (re-plans) before the job runs: the re-plan checks first,
     * posts the one notice and only then lets the job go.
     */
    private static void replanAfterSnooze() {
        Memory memory = new Memory(); Shade shade = new Shade();
        MaintenanceKeeper keeper = keeper(memory, "p1", distance("chain", 100, 0, true));
        keeper.observe("p1", 1000, MaintenanceDistance.BITS_32, WALL - DAY);
        keeper.observe("p1", 1100, MaintenanceDistance.BITS_32, WALL);
        check(MaintenanceNotify.check(keeper, "p1", WALL, UTC, false, LIVE, shade), "due by distance while connected");
        keeper.update("p1", s -> MaintenancePolicy.snooze(s.find("chain"), 1, WALL + 1000, 1100, UTC));
        MaintenanceNotify.check(keeper, "p1", WALL + 2000, UTC, true, LIVE, shade);
        List<String> scopes = Arrays.asList("p1", "p2");
        check(MaintenanceNotify.replan(keeper, scopes, WALL + DAY / 2, UTC, LIVE, shade) && shade.posts.size() == 1, "snoozed and disconnected: the job is planned, nothing posts");
        check(!MaintenanceNotify.unchecked(keeper.load("p1"), MaintenanceNotify.decodeSeen(keeper.seen("p1")), WALL + DAY / 2), "a running snooze is not unchecked");
        long after = WALL + 2 * DAY;
        check(MaintenanceNotify.unchecked(keeper.load("p1"), MaintenanceNotify.decodeSeen(keeper.seen("p1")), after), "the snooze ended and no check saw it");
        // A stopped re-plan (backup started, service ending) checks nothing.
        check(MaintenanceNotify.replan(keeper, scopes, after, UTC, () -> false, shade) == false && shade.posts.size() == 1 && MaintenanceNotify.unchecked(keeper.load("p1"), MaintenanceNotify.decodeSeen(keeper.seen("p1")), after), "a stopped re-plan posts and writes nothing");
        // App start re-plans before the delayed job runs.
        check(!MaintenanceNotify.replan(keeper, scopes, after, UTC, LIVE, shade), "then the job is no longer needed");
        check(shade.posts.size() == 2 && shade.posts.get(1).equals(Collections.singletonList("chain")), "but first exactly one notice: " + shade.posts);
        check(!MaintenanceNotify.unchecked(keeper.load("p1"), MaintenanceNotify.decodeSeen(keeper.seen("p1")), after), "the check saw the end");
        check(!MaintenanceNotify.replan(keeper, scopes, after + 60_000, UTC, LIVE, shade) && shade.posts.size() == 2, "a second re-plan posts nothing");
        check(!MaintenanceNotify.check(keeper, "p1", after + DAY, UTC, false, LIVE, shade) && shade.posts.size() == 2, "nor does the late job, if it still runs");
        // Seen on the page after the end (a quiet check): the re-plan has nothing to post.
        Memory paged = new Memory(); Shade pageShade = new Shade();
        MaintenanceKeeper page = keeper(paged, "p3", distance("chain", 100, 0, true));
        page.observe("p3", 1000, MaintenanceDistance.BITS_32, WALL - DAY);
        page.observe("p3", 1100, MaintenanceDistance.BITS_32, WALL);
        page.update("p3", s -> MaintenancePolicy.snooze(s.find("chain"), 1, WALL, 1100, UTC));
        MaintenanceNotify.check(page, "p3", WALL + 2 * DAY, UTC, true, LIVE, pageShade);
        check(!MaintenanceNotify.replan(page, Collections.singletonList("p3"), WALL + 2 * DAY, UTC, LIVE, pageShade) && pageShade.posts.isEmpty(), "seen on the page: nothing to post");
        // Another scooter's time reminder still keeps the job.
        Memory both = new Memory(); Shade bothShade = new Shade();
        MaintenanceKeeper two = keeper(both, "p4", time("tires", 30, WALL, true));
        check(MaintenanceNotify.replan(two, Arrays.asList("p4", "p5"), WALL + DAY, UTC, LIVE, bothShade) && bothShade.posts.isEmpty(), "a time reminder keeps the job");
    }

    /**
     * #267 review: a notice names its scooter. MainActivity routes a cold start (onCreate) and a warm tap
     * (onNewIntent) through the same route(), with two saved scooters.
     */
    private static void taps() {
        List<String> saved = Arrays.asList("a", "b");
        check(MaintenanceNotify.route("a", "a", saved) == MaintenanceNotify.Route.OPEN_PAGE, "the active scooter's notice opens its Maintenance page");
        check(MaintenanceNotify.route("b", "a", saved) == MaintenanceNotify.Route.OTHER, "another saved scooter's notice: a note naming it, no switch");
        check(MaintenanceNotify.route("b", "b", saved) == MaintenanceNotify.Route.OPEN_PAGE, "once the owner selects it, its notice opens the page");
        check(MaintenanceNotify.route(MaintenanceKeeper.simulationScope("a"), "a", saved) == MaintenanceNotify.Route.OPEN_PAGE, "the simulator's notice belongs to its scooter");
        check(MaintenanceNotify.route(MaintenanceKeeper.simulationScope("b"), "a", saved) == MaintenanceNotify.Route.OTHER, "and not to another");
        check(MaintenanceNotify.route("c", "a", saved) == MaintenanceNotify.Route.NONE, "a forgotten scooter's stale notice just opens Volt");
        check(MaintenanceNotify.route("b", "a", Collections.singletonList("a")) == MaintenanceNotify.Route.NONE, "b forgotten after its notice posted");
        check(MaintenanceNotify.route(null, "a", saved) == MaintenanceNotify.Route.NONE && MaintenanceNotify.route("", "a", saved) == MaintenanceNotify.Route.NONE
                && MaintenanceNotify.route(MaintenanceKeeper.simulationScope(""), "a", saved) == MaintenanceNotify.Route.NONE, "no scooter named: just Volt");
        check(MaintenanceNotify.route("a", null, saved) == MaintenanceNotify.Route.OTHER, "no active scooter: never the page");
        check(MaintenanceNotify.route("a", "a", Collections.emptyList()) == MaintenanceNotify.Route.NONE, "no saved scooters: just Volt");
    }
}
