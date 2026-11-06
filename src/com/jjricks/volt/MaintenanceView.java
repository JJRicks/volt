package com.jjricks.volt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.LongFunction;

/**
 * What the Maintenance page, its row and the Scooter tab's dot show (maintenance plan, PR 2), as pure
 * functions of a scooter's {@link MaintenanceStore.Settings}: the counts, the order of the lists, and the
 * text of names, intervals, distances and what is left. Distances are stored in km and shown in the
 * rider's units ("km", "mi", or "dual", which reads as miles with km beside it); every number goes
 * through {@link Fmt}. Dates come from the caller's formatter, so this class stays free of Android.
 */
final class MaintenanceView {
    static final double KM_PER_MILE = 1.609344;
    /** Stored unit setting values (MainActivity's units preference). */
    static final String UNITS_KM = "km", UNITS_DUAL = "dual";
    /** Distance shortcut chips, in the rider's units, and time shortcut chips: neutral, never recommendations. */
    static final int[] DISTANCE_CHIPS = {100, 300, 500, 1000};
    /** Time chips as {count, unit}: 1 and 2 weeks, then 1, 3 and 6 months. */
    static final int[][] TIME_CHIPS = {{1, 7}, {2, 7}, {1, 30}, {3, 30}, {6, 30}};
    /** Time units in the add sheet: a day, a week (7 days) or a month (30 stands for a calendar month). */
    static final int DAY = 1, WEEK = 7, MONTH = 30;
    private static final String REMINDER_ID = "reminder";
    /** The telemetry key of the scooter's total odometer, in km. */
    static final String ODOMETER = "odometer_km";

    private MaintenanceView() { }

    /** How many reminders a scooter has, and how many are due or nearly due. Off: all zero. */
    static final class Summary {
        final boolean on; final int total, due, soon;
        Summary(boolean on, int total, int due, int soon) { this.on = on; this.total = total; this.due = due; this.soon = soon; }
        /** The Scooter tab's dot. */
        boolean dot() { return due > 0; }
    }

    static Summary summary(MaintenanceStore.Settings s, long nowWall, ZoneId zone) {
        if (s == null || !s.on) return new Summary(false, 0, 0, 0);
        int due = 0, soon = 0;
        for (MaintenancePolicy.Reminder r : s.reminders) {
            MaintenancePolicy.State state = status(s, r, nowWall, zone).state;
            if (state == MaintenancePolicy.State.DUE) due++;
            else if (state == MaintenancePolicy.State.SOON) soon++;
        }
        return new Summary(true, s.reminders.size(), due, soon);
    }

    /** A reminder's status against the scooter's count (NaN while the odometer was never read). */
    static MaintenancePolicy.Status status(MaintenanceStore.Settings s, MaintenancePolicy.Reminder r, long nowWall, ZoneId zone) {
        return MaintenancePolicy.status(r, nowWall, s.distance.everRead() ? s.distance.accumulatedKm : Double.NaN, zone);
    }

    /** The Due now list: due reminders, the most overdue first. */
    static List<MaintenancePolicy.Reminder> dueNow(MaintenanceStore.Settings s, long nowWall, ZoneId zone) {
        return sorted(s, nowWall, zone, true);
    }

    /** The Coming up list: everything else (snoozed and waiting included), the nearest first. */
    static List<MaintenancePolicy.Reminder> comingUp(MaintenanceStore.Settings s, long nowWall, ZoneId zone) {
        return sorted(s, nowWall, zone, false);
    }

    private static List<MaintenancePolicy.Reminder> sorted(MaintenanceStore.Settings s, long nowWall, ZoneId zone, boolean due) {
        List<MaintenancePolicy.Reminder> out = new ArrayList<>();
        Map<MaintenancePolicy.Reminder, Double> order = new IdentityHashMap<>();
        for (MaintenancePolicy.Reminder r : s.reminders) {
            MaintenancePolicy.Status status = status(s, r, nowWall, zone);
            if ((status.state == MaintenancePolicy.State.DUE) != due) continue;
            out.add(r);
            // Due: the most overdue first. Otherwise the nearest first; waiting goes last.
            order.put(r, due ? -overdue(r, status) : status.state == MaintenancePolicy.State.WAITING ? 2.0 : -status.fraction);
        }
        Collections.sort(out, (a, b) -> Double.compare(order.get(a), order.get(b)));
        return out;
    }

    /** How far past due, as a fraction of the interval (the larger part for Either). */
    private static double overdue(MaintenancePolicy.Reminder r, MaintenancePolicy.Status status) {
        double over = 0;
        if (r.byDistance() && !Double.isNaN(status.kmLeft)) over = Math.max(over, -status.kmLeft / r.km);
        if (r.byTime()) over = Math.max(over, -status.daysLeft / (double) Math.max(1, r.months > 0 ? r.months * 30 : r.days));
        return over;
    }

    /** Done events, across a scooter's reminders. */
    static int doneCount(MaintenanceStore.Settings s) {
        int count = 0;
        for (MaintenancePolicy.Reminder r : s.reminders) for (MaintenancePolicy.Event e : r.events) if (MaintenancePolicy.DONE.equals(e.type)) count++;
        return count;
    }

    // ---- Names --------------------------------------------------------------------------------

    /** A suggested task's catalog name; empty for an unknown id. */
    static String taskName(String task) {
        switch (task) {
            case MaintenanceDefaults.TIRE_PRESSURE: return T.maintenanceTaskTirePressure();
            case MaintenanceDefaults.TIRE_TREAD: return T.maintenanceTaskTireTread();
            case MaintenanceDefaults.BRAKES: return T.maintenanceTaskBrakes();
            case MaintenanceDefaults.BOLTS_FOLDING: return T.maintenanceTaskBoltsFolding();
            case MaintenanceDefaults.STEERING: return T.maintenanceTaskSteering();
            case MaintenanceDefaults.LIGHTS: return T.maintenanceTaskLights();
            case MaintenanceDefaults.CLEAN_DRY: return T.maintenanceTaskCleanDry();
            case MaintenanceDefaults.BATTERY_STORAGE: return T.maintenanceTaskBatteryStorage();
            default: return "";
        }
    }

    /** A reminder's name: its suggested task in the current language, or the owner's own words. */
    static String name(MaintenancePolicy.Reminder r) {
        String task = taskName(r.task);
        return task.isEmpty() ? r.name : task;
    }

    // ---- Distances ----------------------------------------------------------------------------

    /** Whether distances read in miles: "mi" and "dual" do, "km" doesn't. */
    static boolean miles(String units) { return !UNITS_KM.equals(units); }

    /** A typed distance in the rider's units, as km at full precision (300 mi is 482.8032 km). */
    static double toKm(double typed, String units) { return miles(units) ? typed * KM_PER_MILE : typed; }

    /** A km distance in the rider's units, for an edit field. */
    static double fromKm(double km, String units) { return miles(units) ? km / KM_PER_MILE : km; }

    /** The unit symbol of the rider's distances. */
    static String unit(String units) { return miles(units) ? T.unitsMi() : T.unitsKm(); }

    /**
     * A distance with its unit. Whole values show no decimals; others one, or none from 100 on. Dual
     * shows miles with km beside it. An interval typed as 300 mi reads back as 300 mi.
     */
    static String distance(double km, String units) {
        String metric = T.fmtQuantity(number(km), T.unitsKm());
        String imperial = T.fmtQuantity(number(km / KM_PER_MILE), T.unitsMi());
        if (UNITS_KM.equals(units)) return metric;
        return UNITS_DUAL.equals(units) ? T.fmtDual(imperial, metric) : imperial;
    }

    /** An odometer reading: always one decimal, grouped. */
    static String odometer(double km, String units) {
        String metric = T.fmtQuantity(Fmt.grouped(Locale.US, km, 1), T.unitsKm());
        String imperial = T.fmtQuantity(Fmt.grouped(Locale.US, km / KM_PER_MILE, 1), T.unitsMi());
        if (UNITS_KM.equals(units)) return metric;
        return UNITS_DUAL.equals(units) ? T.fmtDual(imperial, metric) : imperial;
    }

    private static String number(double value) {
        double abs = Math.abs(value);
        boolean whole = Math.abs(abs - Math.rint(abs)) < 0.05;
        return Fmt.grouped(Locale.US, abs, whole || abs >= 100 ? 0 : 1);
    }

    /** A number for an edit field: no grouping, no decimals when whole. */
    static String editNumber(double value) {
        boolean whole = Math.abs(value - Math.rint(value)) < 0.05;
        return Fmt.decimal(Locale.US, value, whole ? 0 : 1);
    }

    // ---- Intervals and what is left -----------------------------------------------------------

    /** "Every 300 mi", "Every 2 weeks", "Every month or 250 mi". */
    static String interval(MaintenancePolicy.Reminder r, String units) {
        String distance = r.byDistance() ? distance(r.km, units) : "";
        if (!r.byTime()) return T.maintenanceEveryDistance(distance);
        int count = r.months > 0 ? r.months : r.days % WEEK == 0 ? r.days / WEEK : r.days;
        int unit = r.months > 0 ? MONTH : r.days % WEEK == 0 ? WEEK : DAY;
        if (r.byDistance()) {
            if (count == 1) return unit == MONTH ? T.maintenanceEveryMonthOr(distance) : unit == WEEK ? T.maintenanceEveryWeekOr(distance) : T.maintenanceEveryDayOr(distance);
            return unit == MONTH ? T.maintenanceEveryMonthsOr(count, count, distance) : unit == WEEK ? T.maintenanceEveryWeeksOr(count, count, distance)
                    : T.maintenanceEveryDaysOr(count, count, distance);
        }
        if (count == 1) return unit == MONTH ? T.maintenanceEveryMonth() : unit == WEEK ? T.maintenanceEveryWeek() : T.maintenanceEveryDay();
        return unit == MONTH ? T.maintenanceEveryMonths(count, count) : unit == WEEK ? T.maintenanceEveryWeeks(count, count) : T.maintenanceEveryDays(count, count);
    }

    // ---- Manual intervals ---------------------------------------------------------------------

    /** A manual interval as an unsaved reminder: the add sheet's prefill, and its "Every 6 months or 500 km" text. */
    static MaintenancePolicy.Reminder suggestion(MaintenanceDefaults.Interval interval) {
        MaintenancePolicy.Reminder r = new MaintenancePolicy.Reminder();
        r.task = interval.task; r.name = ""; r.km = interval.km; r.days = interval.days; r.months = interval.months; r.notify = true;
        return r;
    }

    /** Where an interval comes from: "NAVEE GT3 user manual (online PDF, 2026-03), p. 10". */
    static String source(MaintenanceDefaults.Source source) { return T.maintenanceManualSource(source.title, source.edition, source.page); }

    /** Whether a scooter already has a reminder for this suggested task, so the manual's row reads Added. */
    static boolean hasTask(MaintenanceStore.Settings s, String task) {
        for (MaintenancePolicy.Reminder r : s.reminders) if (task.equals(r.task)) return true;
        return false;
    }

    /** A time choice's label: "1 week", "3 months". */
    static String count(int count, int unit) {
        return unit == MONTH ? T.maintenanceCountMonths(count, count) : unit == WEEK ? T.maintenanceCountWeeks(count, count) : T.maintenanceCountDays(count, count);
    }

    /**
     * What is left: "84 mi left", "18 days left", "Due today", "3 days overdue", "Waiting for the odometer",
     * "Snoozed until Nov 9". For Either it names the part that is nearer (or further past) due.
     */
    static String left(MaintenancePolicy.Reminder r, MaintenancePolicy.Status status, String units, long nowWall, LongFunction<String> date) {
        if (status.state == MaintenancePolicy.State.WAITING) return T.maintenanceWaiting();
        if (status.state == MaintenancePolicy.State.SNOOZED) return T.maintenanceSnoozedUntil(date.apply(r.snoozeUntil));
        boolean distanceKnown = r.byDistance() && !Double.isNaN(status.kmLeft);
        double distanceFraction = distanceKnown ? 1 - status.kmLeft / r.km : Double.NEGATIVE_INFINITY;
        double timeFraction = r.byTime() ? timeFraction(r, status, nowWall) : Double.NEGATIVE_INFINITY;
        if (distanceKnown && distanceFraction >= timeFraction) {
            return status.kmLeft > 0 ? T.maintenanceDistanceLeft(distance(status.kmLeft, units)) : T.maintenanceDistanceOver(distance(-status.kmLeft, units));
        }
        int days = status.daysLeft;
        if (days > 0) return T.maintenanceDaysLeft(days, days);
        if (days == 0) return T.maintenanceDueToday();
        int over = -days;
        return T.maintenanceDaysOver(over, over);
    }

    /** The time part's progress, past 1 once overdue (so the more overdue part is named). */
    private static double timeFraction(MaintenancePolicy.Reminder r, MaintenancePolicy.Status status, long nowWall) {
        long span = status.dueWall - r.baseWall;
        return span <= 0 ? 1 : (double) (nowWall - r.baseWall) / span;
    }

    /**
     * The raw odometer reading at which a distance reminder is due, or NaN: the last reading plus what is
     * left. Only meaningful while the odometer was read and the reminder knows its distance.
     */
    static double dueOdometer(MaintenanceStore.Settings s, MaintenancePolicy.Status status) {
        if (!s.distance.hasBaseline() || Double.isNaN(status.kmLeft)) return Double.NaN;
        return MaintenanceDistance.round(s.distance.lastRawKm + status.kmLeft);
    }

    /**
     * Where a new reminder or a Done counts from, taken from the document being written (never a sheet's
     * older copy, so a reading that came in while the sheet was open is not counted against it): the latest
     * count, or with typedRawKm (not NaN) the count at that earlier odometer reading. NaN count while the
     * odometer was never read.
     */
    static MaintenanceDistance.AtRaw baseline(MaintenanceDistance d, double typedRawKm) {
        if (Double.isNaN(typedRawKm)) return new MaintenanceDistance.AtRaw(MaintenanceDistance.Backdate.OK, d.everRead() ? d.accumulatedKm : Double.NaN);
        return d.accumulatedAtRaw(typedRawKm);
    }

    /** The calendar-day due date of a time reminder, or 0. */
    static long dueDate(MaintenancePolicy.Reminder r, ZoneId zone) { return r.byTime() ? MaintenancePolicy.dueWall(r, zone) : 0; }

    /** Days between two walls in the zone (calendar days). */
    static long days(long fromWall, long toWall, ZoneId zone) {
        return java.time.temporal.ChronoUnit.DAYS.between(day(fromWall, zone), day(toWall, zone));
    }

    private static LocalDate day(long wall, ZoneId zone) { return Instant.ofEpochMilli(wall).atZone(zone).toLocalDate(); }

    // ---- History ------------------------------------------------------------------------------

    /** One history line: the event's name, its date, and for a Done the distance since the Done before it. */
    static final class Line {
        final String type; final long wall; final double kmSincePreviousDone;
        Line(String type, long wall, double kmSincePreviousDone) { this.type = type; this.wall = wall; this.kmSincePreviousDone = kmSincePreviousDone; }
    }

    /** The reminder's events, newest first. kmSincePreviousDone is NaN when either distance is unknown. */
    static List<Line> history(MaintenancePolicy.Reminder r) {
        List<Line> lines = new ArrayList<>();
        double lastDone = Double.NaN; boolean anyDone = false;
        for (MaintenancePolicy.Event e : r.events) {
            double since = Double.NaN;
            if (MaintenancePolicy.DONE.equals(e.type)) {
                if (anyDone && !Double.isNaN(lastDone) && !Double.isNaN(e.accKm)) since = MaintenanceDistance.round(e.accKm - lastDone);
                anyDone = true; lastDone = e.accKm;
            }
            lines.add(0, new Line(e.type, e.wall, since));
        }
        return lines;
    }

    /** An event type's name, or empty for a type a newer version wrote (an id is never shown). */
    static String eventName(String type) {
        if (MaintenancePolicy.DONE.equals(type)) return T.maintenanceEventDone();
        if (MaintenancePolicy.SNOOZED.equals(type)) return T.maintenanceEventSnoozed();
        if (MaintenancePolicy.ADDED.equals(type)) return T.maintenanceEventAdded();
        return "";
    }

    // ---- The Scooter tab row ------------------------------------------------------------------

    /** The Reminders row's value: Off, "3 reminders" or "1 due". */
    static String rowValue(Summary summary) {
        if (!summary.on) return T.commonOff();
        int due = summary.due, total = summary.total;
        return due > 0 ? T.maintenanceRowDue(due, due) : T.maintenanceRowCount(total, total);
    }

    /** A new reminder id, unique among the scooter's. */
    static String newId(MaintenanceStore.Settings s, long nowWall) {
        long n = Math.max(1, nowWall);
        while (s.find(REMINDER_ID + n) != null) n++;
        return REMINDER_ID + n;
    }
}
