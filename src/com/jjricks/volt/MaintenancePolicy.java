package com.jjricks.volt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Maintenance reminders and when they are due (maintenance plan, section 4). Due-ness is a pure
 * function of the reminder, the wall clock and the scooter's {@link MaintenanceDistance} count; no
 * timers are needed for an in-app badge.
 *
 * <ul>
 * <li>A reminder counts by distance, by time, or by either (whichever comes first).</li>
 * <li>Time intervals are calendar days or months in the phone's zone. A reminder is due from the
 *     start of its due day, so month ends and DST changes never shift it by a day.</li>
 * <li>SOON from 80 % of the interval, DUE at 100 %.</li>
 * <li>Snooze (owner answer Q43) is 1 day, 3 days or 1 week, by time even for distance reminders.
 *     It hides DUE until it ends; it never pretends the check was done.</li>
 * <li>Mark done restarts the count from the done date and distance, which may be in the past.</li>
 * <li>Each reminder keeps its last {@link #MAX_EVENTS} events; a scooter has at most {@link #MAX_REMINDERS}.</li>
 * </ul>
 * Pure Java; nothing in the app calls it yet.
 */
final class MaintenancePolicy {
    static final int MAX_REMINDERS = 30, MAX_EVENTS = 20, MAX_NAME = 80, MAX_ID = 64, MAX_DAYS = 3650, MAX_MONTHS = 120;
    static final double MAX_KM = 100_000, SOON = 0.8, MAX_ODOMETER_KM = 1e9;
    /** The snooze choices, in days (owner answer Q43). */
    static final int[] SNOOZE_DAYS = {1, 3, 7};
    static final String ADDED = "added", DONE = "done", SNOOZED = "snoozed";

    /** One history line: Added, Done or Snoozed, when, and the maintenance distance then (NaN if unknown). */
    static final class Event {
        final String type; final long wall; final double accKm;
        /** Fields a newer version wrote; kept as they are. */
        final Map<String, Object> extra;
        Event(String type, long wall, double accKm) { this(type, wall, accKm, new LinkedHashMap<String, Object>()); }
        Event(String type, long wall, double accKm, Map<String, Object> extra) { this.type = type; this.wall = wall; this.accKm = accKm; this.extra = extra; }
    }

    /**
     * One reminder. {@code task} is a {@link MaintenanceDefaults#TASKS} id, or empty with the
     * owner's own {@code name}, stored verbatim. {@code km} null means no distance part;
     * {@code days} and {@code months} (at most one nonzero) are the time part.
     */
    static final class Reminder {
        String id, task, name;
        Double km;
        int days, months;
        boolean notify;
        /** Where the count restarts: maintenance distance (NaN until known) and wall time. */
        double baseAccKm;
        long baseWall;
        /** DUE is hidden until this wall time; 0 when not snoozed. */
        long snoozeUntil;
        final List<Event> events = new ArrayList<>();
        final Map<String, Object> extra = new LinkedHashMap<>();
        boolean byDistance() { return km != null; }
        boolean byTime() { return days > 0 || months > 0; }
    }

    enum State { OK, SOON, DUE, SNOOZED, WAITING }

    /**
     * A reminder's status. fraction is how far through the interval it is (the larger part for
     * Either). kmLeft is NaN without a known distance part; dueWall and daysLeft are 0 without a
     * time part (daysLeft is negative once overdue).
     */
    static final class Status {
        final State state; final double fraction, kmLeft; final long dueWall; final int daysLeft;
        Status(State state, double fraction, double kmLeft, long dueWall, int daysLeft) {
            this.state = state; this.fraction = fraction; this.kmLeft = kmLeft; this.dueWall = dueWall; this.daysLeft = daysLeft;
        }
    }

    private MaintenancePolicy() { }

    /** Whether a reminder's fields are within bounds. Used by create and by the tolerant decoder. */
    static boolean valid(Reminder r) {
        if (r.id == null || r.id.isEmpty() || r.id.length() > MAX_ID || r.task == null || r.name == null || r.name.length() > MAX_NAME) return false;
        if (r.task.isEmpty() && r.name.trim().isEmpty()) return false;
        if (r.km != null && !(r.km > 0 && r.km <= MAX_KM)) return false;
        if (r.days < 0 || r.months < 0 || r.days > MAX_DAYS || r.months > MAX_MONTHS || r.days > 0 && r.months > 0) return false;
        if (!r.byDistance() && !r.byTime()) return false;
        // Negative when a backdated service predates the count (MaintenanceDistance.accumulatedAtRaw).
        if (Double.isInfinite(r.baseAccKm) || Math.abs(r.baseAccKm) > MAX_ODOMETER_KM) return false;
        return r.baseWall > 0 && r.snoozeUntil >= 0;
    }

    /**
     * A new reminder counting from baseWall and baseAccKm (now, or a past date or odometer value),
     * with its Added event. A name longer than {@link #MAX_NAME} is cut at a character boundary.
     * @throws IllegalArgumentException for out-of-range values
     */
    static Reminder create(String id, String task, String name, Double km, int days, int months, boolean notify,
                           long baseWall, double baseAccKm, long nowWall) {
        Reminder r = new Reminder();
        r.id = id; r.task = task == null ? "" : task; r.name = cut(name == null ? "" : name); r.km = km; r.days = days; r.months = months;
        r.notify = notify; r.baseWall = Math.min(baseWall, nowWall); r.baseAccKm = baseAccKm;
        if (!valid(r)) throw new IllegalArgumentException("reminder");
        record(r, new Event(ADDED, nowWall, baseAccKm));
        return r;
    }

    /** Adds a reminder unless the scooter already has {@link #MAX_REMINDERS} or one with its id. */
    static boolean add(List<Reminder> reminders, Reminder r) {
        if (reminders.size() >= MAX_REMINDERS) return false;
        for (Reminder other : reminders) if (other.id.equals(r.id)) return false;
        reminders.add(r);
        return true;
    }

    /** The status at nowWall with the scooter's maintenance distance (NaN when the odometer was never read). */
    static Status status(Reminder r, long nowWall, double accumulatedKm, ZoneId zone) {
        double distanceFraction = Double.NaN, kmLeft = Double.NaN, timeFraction = Double.NaN;
        long dueWall = 0; int daysLeft = 0;
        if (r.byDistance() && !Double.isNaN(accumulatedKm) && !Double.isNaN(r.baseAccKm)) {
            double gone = Math.max(0, accumulatedKm - r.baseAccKm);
            kmLeft = MaintenanceDistance.round(r.km - gone);
            distanceFraction = kmLeft <= 0 ? 1 : gone / r.km;
        }
        if (r.byTime()) {
            dueWall = dueWall(r, zone);
            daysLeft = (int) ChronoUnit.DAYS.between(day(nowWall, zone), day(dueWall, zone));
            timeFraction = nowWall >= dueWall ? 1 : Math.max(0, (double) (nowWall - r.baseWall) / (dueWall - r.baseWall));
        }
        double fraction = Double.isNaN(distanceFraction) ? timeFraction : Double.isNaN(timeFraction) ? distanceFraction : Math.max(distanceFraction, timeFraction);
        if (Double.isNaN(fraction)) return new Status(State.WAITING, 0, kmLeft, dueWall, daysLeft);
        fraction = Math.min(1, fraction);
        State state = fraction >= 1 ? State.DUE : fraction >= SOON ? State.SOON : State.OK;
        if (state == State.DUE && nowWall < r.snoozeUntil) state = State.SNOOZED;
        return new Status(state, fraction, kmLeft, dueWall, daysLeft);
    }

    /** The start of the due day in the zone: the base day plus the interval's calendar days or months. */
    static long dueWall(Reminder r, ZoneId zone) {
        LocalDate base = day(r.baseWall, zone);
        LocalDate due = r.months > 0 ? base.plusMonths(r.months) : base.plusDays(r.days);
        return due.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Mark done at doneWall (now or earlier; a future time is taken as now) with the maintenance distance then. */
    static void markDone(Reminder r, long doneWall, double accKmThen, long nowWall) {
        long when = Math.min(doneWall, nowWall);
        r.baseWall = when; r.baseAccKm = accKmThen; r.snoozeUntil = 0;
        record(r, new Event(DONE, when, accKmThen));
    }

    /**
     * Snooze for one of {@link #SNOOZE_DAYS}: calendar days in the zone from now.
     * @throws IllegalArgumentException for any other length
     */
    static void snooze(Reminder r, int days, long nowWall, double accKmNow, ZoneId zone) {
        boolean offered = false; for (int d : SNOOZE_DAYS) offered |= d == days;
        if (!offered) throw new IllegalArgumentException("days");
        r.snoozeUntil = Instant.ofEpochMilli(nowWall).atZone(zone).plusDays(days).toInstant().toEpochMilli();
        record(r, new Event(SNOOZED, nowWall, accKmNow));
    }

    /** Adds an event in time order and drops the oldest past {@link #MAX_EVENTS}. */
    static void record(Reminder r, Event e) {
        int at = r.events.size();
        while (at > 0 && r.events.get(at - 1).wall > e.wall) at--;
        r.events.add(at, e);
        while (r.events.size() > MAX_EVENTS) r.events.remove(0);
    }

    /** Text cut to {@link #MAX_NAME} chars without splitting a surrogate pair. */
    static String cut(String text) {
        if (text.length() <= MAX_NAME) return text;
        int end = MAX_NAME;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static LocalDate day(long wall, ZoneId zone) { return Instant.ofEpochMilli(wall).atZone(zone).toLocalDate(); }
}
