package com.jjricks.volt;

import com.jjricks.volt.MaintenancePolicy.Reminder;
import com.jjricks.volt.MaintenanceStore.Settings;

import java.time.ZoneId;
import java.util.*;

/**
 * Maintenance plan, PR 2: the keeper that feeds odometer readings into each scooter's document (off
 * ignores, throttled writes, flush, the simulator's memory-only scope, a restore dropping unwritten
 * distance, page changes keeping it, forget), and the page's text and order in km, mi and dual.
 */
public final class MaintenancePageTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, DAY = 86_400_000L;
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void same(String expected, String actual, String message) {
        String shown = actual.replace(' ', ' ').replace(' ', ' ');
        check(expected.equals(shown), message + ": expected <" + expected + "> but was <" + shown + ">");
    }

    /** app_settings in memory. */
    private static final class Memory implements MaintenanceKeeper.Storage {
        final Map<String, String> map = new HashMap<>();
        int writes;
        public String read(String key) { return map.get(key); }
        public void write(String key, String value) { writes++; map.put(key, value); }
        public void remove(String key) { map.remove(key); }
    }

    public static void main(String[] args) {
        Locale phone = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            keeperOff();
            keeperThrottleAndFlush();
            keeperRestoreDropsPending();
            keeperUpdateKeepsDistance();
            keeperSimulation();
            keeperForget();
            savedWhileReadingsArrive();
            distances();
            intervals();
            summaryAndOrder();
            history();
            names();
        } finally {
            Locale.setDefault(phone);
        }
        System.out.println("MaintenancePageTest: " + checks + " checks passed (keeper off, throttle, flush, restore, update, simulation, forget, save after a reading; km/mi/dual text, intervals, summary, order, history, names)");
    }

    private static MaintenanceKeeper on(Memory memory, String scope) {
        MaintenanceKeeper keeper = new MaintenanceKeeper(memory);
        keeper.update(scope, s -> s.on = true);
        return keeper;
    }

    private static void keeperOff() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = new MaintenanceKeeper(memory);
        check(keeper.observe("p1", 100, MaintenanceDistance.BITS_32, WALL) == null, "off: a reading is ignored");
        check(memory.map.isEmpty() && !keeper.unwritten("p1"), "off: nothing is written or counted");
        check(!keeper.summary("p1", WALL, UTC).on && !keeper.summary("p1", WALL, UTC).dot(), "off: no dot");
    }

    private static void keeperThrottleAndFlush() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = on(memory, "p1");
        int before = memory.writes;
        check(keeper.observe("p1", 100, MaintenanceDistance.BITS_32, WALL) != null, "the first reading counts");
        check(memory.writes == before + 1 && MaintenanceStore.decode(memory.map.get(MaintenanceStore.key("p1"))).distance.hasBaseline(), "the baseline is written at once");
        keeper.observe("p1", 100.05, MaintenanceDistance.BITS_32, WALL + 1000);
        check(memory.writes == before + 1 && keeper.unwritten("p1"), "0.05 km more waits in memory");
        check(Math.abs(keeper.load("p1").distance.accumulatedKm - 0.05) < 1e-9, "the page sees the unwritten distance");
        check(MaintenanceStore.decode(memory.map.get(MaintenanceStore.key("p1"))).distance.accumulatedKm == 0, "the stored count is the old one");
        keeper.observe("p1", 100.2, MaintenanceDistance.BITS_32, WALL + 2000);
        check(memory.writes == before + 2 && !keeper.unwritten("p1"), "0.1 km more is written");
        keeper.observe("p1", 100.25, MaintenanceDistance.BITS_32, WALL + 3000);
        keeper.flush("p1");
        check(!keeper.unwritten("p1") && Math.abs(MaintenanceStore.decode(memory.map.get(MaintenanceStore.key("p1"))).distance.accumulatedKm - 0.25) < 1e-9, "flush writes the rest");
        int writes = memory.writes; keeper.flush("p1");
        check(memory.writes == writes, "a second flush writes nothing");
    }

    private static void keeperRestoreDropsPending() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = on(memory, "p1");
        keeper.observe("p1", 100, MaintenanceDistance.BITS_32, WALL);
        keeper.observe("p1", 100.05, MaintenanceDistance.BITS_32, WALL + 1000);
        Settings restored = new Settings(); restored.on = true; restored.reminders.add(MaintenancePolicy.create("r1", MaintenanceDefaults.BRAKES, "", 300.0, 0, 0, true, WALL, 0, WALL));
        memory.map.put(MaintenanceStore.key("p1"), MaintenanceStore.encode(restored));
        Settings now = keeper.load("p1");
        check(now.find("r1") != null && !now.distance.everRead() && !keeper.unwritten("p1"), "a restored document drops distance counted on the old one");
        keeper.flush("p1");
        check(MaintenanceStore.decode(memory.map.get(MaintenanceStore.key("p1"))).find("r1") != null, "flush never writes the old document back");
    }

    private static void keeperUpdateKeepsDistance() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = on(memory, "p1");
        keeper.observe("p1", 100, MaintenanceDistance.BITS_32, WALL);
        keeper.observe("p1", 100.05, MaintenanceDistance.BITS_32, WALL + 1000);
        keeper.update("p1", s -> MaintenancePolicy.add(s.reminders, MaintenancePolicy.create("r1", "", "Chain", null, 7, 0, true, WALL, Double.NaN, WALL)));
        Settings stored = MaintenanceStore.decode(memory.map.get(MaintenanceStore.key("p1")));
        check(stored.find("r1") != null && Math.abs(stored.distance.accumulatedKm - 0.05) < 1e-9 && !keeper.unwritten("p1"), "a page change keeps the reading that came in meanwhile");
    }

    private static void keeperSimulation() {
        Memory memory = new Memory(); String scope = MaintenanceKeeper.simulationScope("p1");
        check(MaintenanceKeeper.simulation(scope) && !MaintenanceKeeper.simulation("p1") && !MaintenanceKeeper.simulation("3f2a9c1e-0b7d-4e55-9a10-2c4d5e6f7a8b"), "simulation scopes are told apart from profile ids");
        MaintenanceKeeper keeper = on(memory, scope);
        keeper.observe(scope, 248.36, MaintenanceDistance.BITS_32, WALL);
        keeper.update(scope, s -> MaintenancePolicy.add(s.reminders, MaintenancePolicy.create("r1", "", "Chain", 5.0, 0, 0, true, WALL, s.distance.accumulatedKm, WALL)));
        keeper.observe(scope, 253, MaintenanceDistance.BITS_32, WALL + 1000);
        check(memory.map.isEmpty() && memory.writes == 0, "the simulator never writes app_settings");
        check(keeper.load(scope).find("r1") != null && keeper.summary(scope, WALL + 1000, UTC).soon == 1, "simulated reminders live in memory");
        check(!keeper.load("p1").on, "the real scooter's document is untouched");
    }

    private static void keeperForget() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = on(memory, "p1");
        memory.map.put(MaintenanceStore.seenKey("p1"), "{}");
        memory.map.put(MaintenanceStore.key("p2"), "{}");
        keeper.observe("p1", 100, MaintenanceDistance.BITS_32, WALL);
        keeper.observe("p1", 100.05, MaintenanceDistance.BITS_32, WALL + 1000);
        keeper.forget("p1");
        check(!memory.map.containsKey(MaintenanceStore.key("p1")) && !memory.map.containsKey(MaintenanceStore.seenKey("p1")) && !keeper.unwritten("p1"), "forget removes the document and its bookkeeping");
        keeper.flush("p1");
        check(!memory.map.containsKey(MaintenanceStore.key("p1")) && memory.map.containsKey(MaintenanceStore.key("p2")), "nothing comes back, other scooters stay");
    }

    /** Round 2 (#257): a sheet opened before a reading must not start its reminder partway through. */
    private static void savedWhileReadingsArrive() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = on(memory, "p1");
        keeper.observe("p1", 1000, MaintenanceDistance.BITS_32, WALL);
        keeper.update("p1", x -> MaintenancePolicy.add(x.reminders, MaintenancePolicy.create("brakes", MaintenanceDefaults.BRAKES, "", 100.0, 0, 0, true, WALL, x.distance.accumulatedKm, WALL)));
        Settings sheet = keeper.load("p1");
        // 10 km arrive while the Add reminder and Mark done sheets are open.
        keeper.observe("p1", 1010, MaintenanceDistance.BITS_32, WALL + 60_000);
        long now = WALL + 120_000;
        keeper.update("p1", x -> {
            MaintenanceDistance.AtRaw at = MaintenanceView.baseline(x.distance, Double.NaN);
            MaintenancePolicy.add(x.reminders, MaintenancePolicy.create("tires", "", "Tires", 100.0, 0, 0, true, now, at.accKm, now));
        });
        Settings after = keeper.load("p1");
        MaintenancePolicy.Status fresh = MaintenanceView.status(after, after.find("tires"), now, UTC);
        check(sheet.distance.accumulatedKm == 0 && Math.abs(fresh.kmLeft - 100) < 1e-9 && fresh.fraction == 0, "a new Now reminder has its full 100 km left: " + fresh.kmLeft);
        keeper.update("p1", x -> MaintenancePolicy.markDone(x.find("brakes"), now, MaintenanceView.baseline(x.distance, Double.NaN).accKm, now));
        after = keeper.load("p1");
        check(Math.abs(MaintenanceView.status(after, after.find("brakes"), now, UTC).kmLeft - 100) < 1e-9, "Mark done today counts from the latest reading");
        // An earlier reading is placed on the latest count: 1,005 km was 5 km before the latest reading.
        MaintenanceDistance.AtRaw earlier = MaintenanceView.baseline(after.distance, 1005);
        check(earlier.kind == MaintenanceDistance.Backdate.OK && Math.abs(earlier.accKm - 5) < 1e-9, "an earlier reading on the latest count");
        check(MaintenanceView.baseline(sheet.distance, 1005).kind == MaintenanceDistance.Backdate.AHEAD
                && MaintenanceView.baseline(after.distance, 1005).kind == MaintenanceDistance.Backdate.OK, "the sheet's copy would refuse a reading the latest one accepts");
        check(MaintenanceView.baseline(after.distance, 1020).kind == MaintenanceDistance.Backdate.AHEAD, "ahead of the latest reading is refused");
        Settings blind = new Settings();
        check(Double.isNaN(MaintenanceView.baseline(blind.distance, Double.NaN).accKm) && MaintenanceView.baseline(blind.distance, Double.NaN).kind == MaintenanceDistance.Backdate.OK, "never read: a NaN count");
    }

    private static void distances() {
        double threeHundredMiles = 300 * MaintenanceView.KM_PER_MILE;
        same("300 mi", MaintenanceView.distance(threeHundredMiles, "mi"), "an interval typed in miles reads back exactly");
        same("483 km", MaintenanceView.distance(threeHundredMiles, "km"), "from 100 on, no decimals");
        same("300 mi / 483 km", MaintenanceView.distance(threeHundredMiles, "dual"), "dual: miles with km beside them");
        same("12.3 km", MaintenanceView.distance(12.34, "km"), "below 100, one decimal");
        same("500 km", MaintenanceView.distance(500, "km"), "whole km");
        same("1,234.5 km", MaintenanceView.odometer(1234.5, "km"), "an odometer reading is grouped with one decimal");
        same("767.1 mi", MaintenanceView.odometer(1234.5, "mi"), "an odometer reading in miles");
        check(Math.abs(MaintenanceView.toKm(300, "mi") - threeHundredMiles) < 1e-9 && MaintenanceView.toKm(300, "km") == 300 && Math.abs(MaintenanceView.toKm(300, "dual") - threeHundredMiles) < 1e-9, "typed units");
        same("300", MaintenanceView.editNumber(MaintenanceView.fromKm(threeHundredMiles, "mi")), "the edit field shows the typed number");
        same("2.5", MaintenanceView.editNumber(2.5), "one decimal in the edit field");
        same("mi", MaintenanceView.unit("dual"), "dual types miles");
        same("km", MaintenanceView.unit("km"), "km types km");
    }

    private static Reminder reminder(Double km, int days, int months) {
        return MaintenancePolicy.create("r", "", "Chain", km, days, months, true, WALL, 0, WALL);
    }

    private static void intervals() {
        double miles = 250 * MaintenanceView.KM_PER_MILE;
        same("Every 250 mi", MaintenanceView.interval(reminder(miles, 0, 0), "mi"), "distance");
        same("Every week", MaintenanceView.interval(reminder(null, 7, 0), "mi"), "one week");
        same("Every 2 weeks", MaintenanceView.interval(reminder(null, 14, 0), "mi"), "weeks");
        same("Every 3 days", MaintenanceView.interval(reminder(null, 3, 0), "mi"), "days");
        same("Every day", MaintenanceView.interval(reminder(null, 1, 0), "mi"), "one day");
        same("Every 6 months", MaintenanceView.interval(reminder(null, 0, 6), "mi"), "months");
        same("Every month or 250 mi", MaintenanceView.interval(reminder(miles, 0, 1), "mi"), "either, one month");
        same("Every 3 months or 402 km", MaintenanceView.interval(reminder(miles, 0, 3), "km"), "either in km");
        same("1 week", MaintenanceView.count(1, MaintenanceView.WEEK), "a time chip");
        same("3 days", MaintenanceView.count(3, MaintenanceView.DAY), "a snooze choice");
    }

    private static Settings sample() {
        Settings s = new Settings(); s.on = true;
        s.distance.observe(1000, MaintenanceDistance.BITS_32, WALL - 20 * DAY);
        s.distance.observe(1090, MaintenanceDistance.BITS_32, WALL);
        // Soon: 90 of 100 km. Due: 90 of 50 km. Due: a week, ten days ago. Waiting cannot happen once read; a time one is fine.
        s.reminders.add(MaintenancePolicy.create("soon", "", "Tires", 100.0, 0, 0, true, WALL - 20 * DAY, 0, WALL - 20 * DAY));
        s.reminders.add(MaintenancePolicy.create("far", MaintenanceDefaults.BRAKES, "", 1000.0, 0, 0, true, WALL - 20 * DAY, 0, WALL - 20 * DAY));
        s.reminders.add(MaintenancePolicy.create("over", "", "Chain", 50.0, 0, 0, true, WALL - 20 * DAY, 0, WALL - 20 * DAY));
        s.reminders.add(MaintenancePolicy.create("week", "", "Wash", null, 7, 0, true, WALL - 10 * DAY, Double.NaN, WALL - 10 * DAY));
        return s;
    }

    private static void summaryAndOrder() {
        Settings s = sample();
        MaintenanceView.Summary summary = MaintenanceView.summary(s, WALL, UTC);
        check(summary.on && summary.total == 4 && summary.due == 2 && summary.soon == 1 && summary.dot(), "two due, one soon, the dot shows");
        same("2 due", MaintenanceView.rowValue(summary), "the row says how many are due");
        same("1 reminder", MaintenanceView.rowValue(new MaintenanceView.Summary(true, 1, 0, 0)), "the row counts reminders");
        same("Off", MaintenanceView.rowValue(MaintenanceView.summary(new Settings(), WALL, UTC)), "the row says Off");
        List<Reminder> due = MaintenanceView.dueNow(s, WALL, UTC), coming = MaintenanceView.comingUp(s, WALL, UTC);
        check(due.size() == 2 && "over".equals(due.get(0).id) && "week".equals(due.get(1).id), "Due now: the most overdue first");
        check(coming.size() == 2 && "soon".equals(coming.get(0).id) && "far".equals(coming.get(1).id), "Coming up: the nearest first");
        Reminder soon = s.find("soon"), over = s.find("over"), week = s.find("week"), far = s.find("far");
        same("10 km left", MaintenanceView.left(soon, MaintenanceView.status(s, soon, WALL, UTC), "km", WALL, wall -> "x"), "distance left in km");
        same("6.2 mi left", MaintenanceView.left(soon, MaintenanceView.status(s, soon, WALL, UTC), "mi", WALL, wall -> "x"), "distance left in miles");
        same("40 km over", MaintenanceView.left(over, MaintenanceView.status(s, over, WALL, UTC), "km", WALL, wall -> "x"), "distance over");
        same("3 days overdue", MaintenanceView.left(week, MaintenanceView.status(s, week, WALL, UTC), "km", WALL, wall -> "x"), "days overdue");
        same("Brakes", MaintenanceView.name(far), "a suggested task's name");
        check(MaintenanceView.dueOdometer(s, MaintenanceView.status(s, far, WALL, UTC)) == 2000, "due at the odometer reading");
        MaintenancePolicy.snooze(week, 3, WALL, s.distance.accumulatedKm, UTC);
        MaintenancePolicy.Status snoozed = MaintenanceView.status(s, week, WALL, UTC);
        check(snoozed.state == MaintenancePolicy.State.SNOOZED && MaintenanceView.summary(s, WALL, UTC).due == 1, "snoozed leaves Due now");
        same("Snoozed until x", MaintenanceView.left(week, snoozed, "km", WALL, wall -> "x"), "snoozed until a date");
        Settings waiting = new Settings(); waiting.on = true;
        Reminder blind = MaintenancePolicy.create("blind", "", "Tires", 100.0, 0, 0, true, WALL, Double.NaN, WALL); waiting.reminders.add(blind);
        same("Waiting for the odometer", MaintenanceView.left(blind, MaintenanceView.status(waiting, blind, WALL, UTC), "mi", WALL, wall -> "x"), "never read");
        check(Double.isNaN(MaintenanceView.dueOdometer(waiting, MaintenanceView.status(waiting, blind, WALL, UTC))), "no due reading before the odometer");
    }

    private static void history() {
        Reminder r = MaintenancePolicy.create("r", "", "Chain", 100.0, 0, 0, true, WALL - 30 * DAY, 0, WALL - 30 * DAY);
        MaintenancePolicy.markDone(r, WALL - 20 * DAY, 95, WALL - 20 * DAY);
        MaintenancePolicy.markDone(r, WALL, 210.5, WALL);
        List<MaintenanceView.Line> lines = MaintenanceView.history(r);
        check(lines.size() == 3 && MaintenancePolicy.DONE.equals(lines.get(0).type) && lines.get(0).wall == WALL, "newest first");
        check(Math.abs(lines.get(0).kmSincePreviousDone - 115.5) < 1e-9 && Double.isNaN(lines.get(1).kmSincePreviousDone), "distance since the Done before");
        same("Added", MaintenanceView.eventName(lines.get(2).type), "the first event");
        same("", MaintenanceView.eventName("future_type"), "an unknown event shows no id");
        Settings s = new Settings(); s.reminders.add(r);
        check(MaintenanceView.doneCount(s) == 2, "two Done events");
    }

    private static void names() {
        Settings s = new Settings();
        String first = MaintenanceView.newId(s, WALL);
        s.reminders.add(MaintenancePolicy.create(first, "", "Chain", null, 7, 0, true, WALL, Double.NaN, WALL));
        String second = MaintenanceView.newId(s, WALL);
        check(!first.equals(second) && s.find(second) == null, "ids are unique");
        for (String task : MaintenanceDefaults.TASKS) check(!MaintenanceView.taskName(task).isEmpty(), "every suggested task has a name: " + task);
        same("", MaintenanceView.taskName("not_a_task"), "an unknown task has no name");
        check(MaintenanceView.miles("mi") && MaintenanceView.miles("dual") && !MaintenanceView.miles("km"), "miles");
    }
}
