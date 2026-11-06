package com.jjricks.volt;

import java.util.*;

/**
 * Maintenance plan, PR 4: the manual section. The two new task names, case-blind model lookup, a
 * manual interval read as a reminder ("Every 6 months or 500 km"), its source caption and the
 * Added state, using a fixture table (the shipped table is reviewed in its own pull request).
 */
public final class MaintenanceManualTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void same(String expected, String actual, String message) {
        String shown = actual.replace(' ', ' ').replace(' ', ' ');
        check(expected.equals(shown), message + ": expected <" + expected + "> but was <" + shown + ">");
    }

    public static void main(String[] args) {
        Locale phone = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            names();
            lookup();
            suggestions();
            added();
            beforeFirstReading();
            timeFirstBeforeReading();
            pendingSurvivesRestore();
            baselinesKept();
            noSecondNotice();
        } finally {
            Locale.setDefault(phone);
        }
        System.out.println("MaintenanceManualTest: " + checks + " checks passed (task names, case-blind lookup, suggestion text, source, added, distance before the first reading)");
    }

    private static final MaintenanceDefaults.Source PAGE_9 = new MaintenanceDefaults.Source("Fixture manual", "2026-03", "09");

    private static List<MaintenanceDefaults.Interval> fixture() {
        return Arrays.asList(
                new MaintenanceDefaults.Interval("GT3 Max", MaintenanceDefaults.BRAKES, 500.0, 0, 6, PAGE_9),
                new MaintenanceDefaults.Interval("GT3 Max", MaintenanceDefaults.BATTERY_STORAGE, null, 60, 0, PAGE_9),
                new MaintenanceDefaults.Interval("GT3 Max", MaintenanceDefaults.BOLTS_FOLDING, null, 0, 3, PAGE_9));
    }

    private static void names() {
        same("Tire tread", MaintenanceView.taskName(MaintenanceDefaults.TIRE_TREAD), "tire tread");
        same("Steering", MaintenanceView.taskName(MaintenanceDefaults.STEERING), "steering");
        check(MaintenanceDefaults.task(MaintenanceDefaults.TIRE_TREAD) && MaintenanceDefaults.task(MaintenanceDefaults.STEERING), "the new ids are suggested tasks");
    }

    private static void lookup() {
        List<MaintenanceDefaults.Interval> table = fixture();
        check(MaintenanceDefaults.forModel("GT3 MAX", table).kind == MaintenanceDefaults.Kind.LISTED, "a scooter's upper-case name matches");
        check(MaintenanceDefaults.forModel(" gt3 max ", table).intervals.size() == 3, "case and spaces don't matter");
        check(MaintenanceDefaults.forModel("GT3 Pro", table).kind == MaintenanceDefaults.Kind.NOT_LISTED, "another model is not listed");
        check(MaintenanceDefaults.forModel("", table).kind == MaintenanceDefaults.Kind.UNKNOWN_MODEL, "no name is an unknown model");
    }

    private static void suggestions() {
        List<MaintenanceDefaults.Interval> table = fixture();
        MaintenancePolicy.Reminder brakes = MaintenanceView.suggestion(table.get(0));
        check(MaintenanceDefaults.BRAKES.equals(brakes.task) && brakes.name.isEmpty() && brakes.notify, "a suggestion keeps the task id, no typed name");
        same("Brakes", MaintenanceView.name(brakes), "a suggestion's name");
        same("Every 6 months or 500 km", MaintenanceView.interval(brakes, "km"), "months or km");
        same("Every 6 months or 311 mi", MaintenanceView.interval(brakes, "mi"), "months or miles");
        same("Every 60 days", MaintenanceView.interval(MaintenanceView.suggestion(table.get(1)), "km"), "days");
        same("Every 3 months", MaintenanceView.interval(MaintenanceView.suggestion(table.get(2)), "km"), "months");
        same("Fixture manual (2026-03), p. 09", MaintenanceView.source(PAGE_9), "source caption");
    }

    private static void added() {
        MaintenanceStore.Settings s = MaintenanceStore.decode("");
        check(!MaintenanceView.hasTask(s, MaintenanceDefaults.BRAKES), "nothing added yet");
        MaintenancePolicy.Reminder r = MaintenanceView.suggestion(fixture().get(0)); r.id = "r1";
        s.reminders.add(r);
        check(MaintenanceView.hasTask(s, MaintenanceDefaults.BRAKES), "a reminder with the task reads Added");
        check(!MaintenanceView.hasTask(s, MaintenanceDefaults.STEERING), "other tasks still offer Add");
    }

    // ---- A distance-or-time interval saved before any odometer reading (Codex round 1, #277) ----------

    private static final long WALL = 1_800_000_000_000L, DAY = 86_400_000L;
    private static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");
    private static final String SCOPE = "p1";

    private static final class Memory implements MaintenanceKeeper.Storage {
        final Map<String, String> map = new HashMap<>();
        public String read(String key) { return map.get(key); }
        public void write(String key, String value) { map.put(key, value); }
        public void remove(String key) { map.remove(key); }
    }

    /** The add sheet's save with no reading yet: Either 500 km or 6 months, no distance base. */
    private static MaintenanceKeeper earlyEither(Memory memory) {
        MaintenanceKeeper keeper = new MaintenanceKeeper(memory);
        keeper.update(SCOPE, s -> {
            s.on = true;
            MaintenancePolicy.Reminder r = MaintenancePolicy.create("tread", MaintenanceDefaults.BRAKES, "", 500.0, 0, 6, true, WALL,
                    MaintenanceView.baseline(s.distance, Double.NaN).accKm, WALL);
            MaintenancePolicy.add(s.reminders, r);
        });
        return keeper;
    }

    private static MaintenancePolicy.Reminder only(MaintenanceKeeper keeper) { return keeper.load(SCOPE).reminders.get(0); }
    private static MaintenancePolicy.State state(MaintenanceKeeper keeper, long wall) {
        MaintenanceStore.Settings s = keeper.load(SCOPE); return MaintenanceView.status(s, s.reminders.get(0), wall, UTC).state;
    }

    private static void beforeFirstReading() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = earlyEither(memory);
        MaintenancePolicy.Reminder r = only(keeper);
        check(r.byDistance() && r.byTime() && r.km == 500.0 && r.months == 6, "the whole Either interval is kept without a reading");
        check(MaintenancePolicy.pending(r), "its distance part waits for a reading");
        check(state(keeper, WALL + DAY) == MaintenancePolicy.State.OK, "the time part runs from the day it was added");
        // The first connection: odometer 1,234 km. That reading becomes the distance base.
        keeper.observe(SCOPE, 1234.0, MaintenanceDistance.BITS_32, WALL + 2 * DAY);
        r = only(keeper);
        check(!MaintenancePolicy.pending(r) && r.baseAccKm == keeper.load(SCOPE).distance.accumulatedKm, "the first reading is the base");
        check(memory.map.get(MaintenanceStore.key(SCOPE)).contains("\"baseAccKm\""), "the base is written at once");
        // Ride past 500 km well before 6 months.
        keeper.observe(SCOPE, 1500.0, MaintenanceDistance.BITS_32, WALL + 10 * DAY);
        check(state(keeper, WALL + 10 * DAY) == MaintenancePolicy.State.OK, "266 of 500 km: not due yet");
        keeper.observe(SCOPE, 1740.0, MaintenanceDistance.BITS_32, WALL + 30 * DAY);
        MaintenanceStore.Settings s = keeper.load(SCOPE);
        MaintenancePolicy.Status status = MaintenanceView.status(s, s.reminders.get(0), WALL + 30 * DAY, UTC);
        check(status.state == MaintenancePolicy.State.DUE && status.kmLeft <= 0, "506 km after the first reading, a month in: due by distance");
    }

    private static void timeFirstBeforeReading() {
        MaintenanceKeeper keeper = earlyEither(new Memory());
        long sixMonths = java.time.Instant.ofEpochMilli(WALL).atZone(UTC).plusMonths(6).toInstant().toEpochMilli();
        check(state(keeper, sixMonths - 2 * DAY) != MaintenancePolicy.State.DUE, "not due before six months");
        check(state(keeper, sixMonths + DAY) == MaintenancePolicy.State.DUE, "six months with no reading yet: due by time");
        check(MaintenanceView.hasTask(keeper.load(SCOPE), MaintenanceDefaults.BRAKES), "a pending reminder still reads Added");
    }

    private static void pendingSurvivesRestore() {
        Memory memory = new Memory(); earlyEither(memory);
        String backup = memory.map.get(MaintenanceStore.key(SCOPE));
        check(!backup.contains("\"baseAccKm\""), "a pending reminder is backed up without a base");
        Memory restored = new Memory(); restored.map.put(MaintenanceStore.key(SCOPE), backup);
        MaintenanceKeeper keeper = new MaintenanceKeeper(restored);
        check(MaintenancePolicy.pending(only(keeper)), "restored, it still waits");
        keeper.observe(SCOPE, 900.0, MaintenanceDistance.BITS_32, WALL + DAY);
        keeper.observe(SCOPE, 1405.0, MaintenanceDistance.BITS_32, WALL + 20 * DAY);
        check(state(keeper, WALL + 20 * DAY) == MaintenancePolicy.State.DUE, "after a restore, 505 km from the first reading is due");
    }

    private static void baselinesKept() {
        List<MaintenancePolicy.Reminder> list = new ArrayList<>();
        MaintenancePolicy.Reminder based = MaintenancePolicy.create("a", MaintenanceDefaults.BRAKES, "", 500.0, 0, 0, true, WALL, 40.0, WALL);
        MaintenancePolicy.Reminder time = MaintenancePolicy.create("b", MaintenanceDefaults.BRAKES, "", null, 0, 3, true, WALL, Double.NaN, WALL);
        list.add(based); list.add(time);
        check(!MaintenancePolicy.settle(list, 120.0) && based.baseAccKm == 40.0 && Double.isNaN(time.baseAccKm), "a reminder with a base, or without distance, is untouched");
        check(!MaintenancePolicy.settle(list, Double.NaN), "no reading, no base");
    }

    private static void noSecondNotice() {
        MaintenancePolicy.Reminder r = MaintenancePolicy.create("c", MaintenanceDefaults.BRAKES, "", 500.0, 0, 6, true, WALL, Double.NaN, WALL);
        MaintenanceNotify.Cycle before = MaintenanceNotify.cycle(r);
        MaintenancePolicy.settle(Collections.singletonList(r), 7.0);
        check(MaintenanceNotify.continues(before, MaintenanceNotify.cycle(r)), "setting the base starts no new notice cycle");
        r.baseWall += DAY;
        check(!MaintenanceNotify.continues(before, MaintenanceNotify.cycle(r)), "a new count start does");
        check(!MaintenanceNotify.continues(null, MaintenanceNotify.cycle(r)), "nothing seen yet is new");
    }
}
