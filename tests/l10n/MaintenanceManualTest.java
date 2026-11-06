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
        } finally {
            Locale.setDefault(phone);
        }
        System.out.println("MaintenanceManualTest: " + checks + " checks passed (task names, case-blind lookup, suggestion text, source, added)");
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
        for (String task : MaintenanceDefaults.TASKS) check(!task.equals(MaintenanceDefaults.TIRE_TREAD) && !task.equals(MaintenanceDefaults.STEERING), "the new ids are distinct from the existing ones: " + task);
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
}
