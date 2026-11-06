package com.jjricks.volt;

import com.jjricks.volt.MaintenancePolicy.Reminder;
import com.jjricks.volt.MaintenancePolicy.State;
import com.jjricks.volt.MaintenancePolicy.Status;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Reminder status (distance, time, either), snooze, done, the caps, and calendar arithmetic. */
public final class MaintenancePolicyTest {
    private static int checks;
    private static final ZoneId DENVER = ZoneId.of("America/Denver"), UTC = ZoneId.of("UTC");
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }
    private static long at(ZoneId zone, int y, int mo, int d, int h, int mi) { return LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli(); }
    private static void rejects(Runnable r, String message) { checks++; try { r.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { } }

    public static void main(String[] args) {
        distance();
        time();
        either();
        snooze();
        done();
        calendar();
        caps();
        System.out.println("MaintenancePolicyTest: " + checks + " checks passed (distance/time/either, SOON at 80 %, snooze, done, calendar, caps)");
    }

    private static Reminder distanceReminder(long now) { return MaintenancePolicy.create("r1", MaintenanceDefaults.BRAKES, "", 300.0, 0, 0, true, now, 1000, now); }

    private static void distance() {
        long now = at(DENVER, 2026, 11, 6, 9, 0);
        Reminder r = distanceReminder(now);
        check(r.byDistance() && !r.byTime() && r.events.size() == 1 && MaintenancePolicy.ADDED.equals(r.events.get(0).type), "a new reminder records Added");
        Status s = MaintenancePolicy.status(r, now, 1000, DENVER);
        check(s.state == State.OK && s.fraction == 0 && near(s.kmLeft, 300) && s.dueWall == 0, "fresh: OK with the full distance left");
        s = MaintenancePolicy.status(r, now, 1239.9, DENVER);
        check(s.state == State.OK && near(s.kmLeft, 60.1), "under 80 %: OK");
        s = MaintenancePolicy.status(r, now, 1240, DENVER);
        check(s.state == State.SOON && near(s.fraction, 0.8), "80 %: SOON");
        s = MaintenancePolicy.status(r, now, 1300, DENVER);
        check(s.state == State.DUE && s.fraction == 1 && s.kmLeft == 0, "the interval reached: DUE");
        s = MaintenancePolicy.status(r, now, 1450, DENVER);
        check(s.state == State.DUE && s.fraction == 1 && near(s.kmLeft, -150), "past it: still DUE, with the overrun");
        s = MaintenancePolicy.status(r, now, Double.NaN, DENVER);
        check(s.state == State.WAITING, "the odometer never read: waiting");
        Reminder miles = MaintenancePolicy.create("m", "", "Chain-free belt look", 300 * 1.609344, 0, 0, false, now, 0, now);
        check(MaintenancePolicy.status(miles, now, 300 * 1.609344, DENVER).state == State.DUE, "an interval typed in miles is exact km and comes due exactly");
        Reminder before = MaintenancePolicy.create("bd", MaintenanceDefaults.BRAKES, "", 500.0, 0, 0, true, now, -1000, now);
        Status sb = MaintenancePolicy.status(before, now, 0, DENVER);
        check(sb.state == State.DUE && near(sb.kmLeft, -500), "a base before the count (backdated service) counts its whole distance");
    }

    private static void time() {
        long base = at(DENVER, 2026, 11, 1, 18, 30);
        Reminder r = MaintenancePolicy.create("t", MaintenanceDefaults.TIRE_PRESSURE, "", null, 14, 0, true, base, Double.NaN, base);
        long due = at(DENVER, 2026, 11, 15, 0, 0);
        check(MaintenancePolicy.dueWall(r, DENVER) == due, "due from the start of the due day, 14 calendar days on");
        Status s = MaintenancePolicy.status(r, base, Double.NaN, DENVER);
        check(s.state == State.OK && s.daysLeft == 14 && s.dueWall == due && Double.isNaN(s.kmLeft), "a time reminder works without the odometer");
        s = MaintenancePolicy.status(r, at(DENVER, 2026, 11, 12, 12, 0), Double.NaN, DENVER);
        check(s.state == State.SOON && s.daysLeft == 3, "past 80 % of the time: SOON");
        s = MaintenancePolicy.status(r, due - 1, Double.NaN, DENVER);
        check(s.state == State.SOON && s.daysLeft == 1, "the last moment of the day before: not yet due");
        s = MaintenancePolicy.status(r, due, Double.NaN, DENVER);
        check(s.state == State.DUE && s.daysLeft == 0, "midnight of the due day: DUE");
        s = MaintenancePolicy.status(r, due + 3 * DAY + HOUR, Double.NaN, DENVER);
        check(s.state == State.DUE && s.daysLeft == -3, "overdue by three days");
    }

    private static void either() {
        long base = at(UTC, 2026, 1, 10, 12, 0);
        Reminder r = MaintenancePolicy.create("e", MaintenanceDefaults.BOLTS_FOLDING, "", 250.0, 0, 1, true, base, 100, base);
        check(r.byDistance() && r.byTime(), "Either has both parts");
        Status s = MaintenancePolicy.status(r, base + DAY, 350, UTC);
        check(s.state == State.DUE && s.daysLeft > 0, "the distance part first: DUE");
        s = MaintenancePolicy.status(r, at(UTC, 2026, 2, 10, 0, 0), 120, UTC);
        check(s.state == State.DUE && near(s.kmLeft, 230), "the month first: DUE with distance left");
        s = MaintenancePolicy.status(r, base + DAY, 310, UTC);
        check(s.state == State.SOON && near(s.fraction, 0.84), "the larger part sets the progress");
        s = MaintenancePolicy.status(r, base + DAY, Double.NaN, UTC);
        check(s.state == State.OK && Double.isNaN(s.kmLeft), "without the odometer, Either still counts its time");
    }

    private static void snooze() {
        long now = at(DENVER, 2026, 11, 6, 9, 0);
        Reminder r = distanceReminder(now);
        check(MaintenancePolicy.status(r, now, 1300, DENVER).state == State.DUE, "due before snoozing");
        MaintenancePolicy.snooze(r, 3, now, 1300, DENVER);
        check(r.snoozeUntil == at(DENVER, 2026, 11, 9, 9, 0) && MaintenancePolicy.SNOOZED.equals(r.events.get(r.events.size() - 1).type), "snoozed 3 calendar days, recorded");
        check(MaintenancePolicy.status(r, now + DAY, 1350, DENVER).state == State.SNOOZED, "snooze hides DUE, by time even for distance");
        check(MaintenancePolicy.status(r, r.snoozeUntil, 1350, DENVER).state == State.DUE, "DUE again when the snooze ends");
        Reminder soon = distanceReminder(now);
        MaintenancePolicy.snooze(soon, 1, now, 1000, DENVER);
        check(MaintenancePolicy.status(soon, now + HOUR, 1250, DENVER).state == State.SOON, "a snooze never hides SOON progress");
        check(MaintenancePolicy.status(soon, now + 2 * HOUR, 1300, DENVER).state == State.SNOOZED, "coming due during a snooze stays hidden");
        long beforeDst = at(DENVER, 2026, 10, 31, 20, 0);
        Reminder dst = distanceReminder(beforeDst);
        MaintenancePolicy.snooze(dst, 7, beforeDst, 1300, DENVER);
        check(dst.snoozeUntil == at(DENVER, 2026, 11, 7, 20, 0) && dst.snoozeUntil - beforeDst == 7 * DAY + HOUR, "a week's snooze across the DST change ends at the same clock time");
        for (int bad : new int[]{0, 2, 14, -1}) rejects(() -> MaintenancePolicy.snooze(dst, bad, beforeDst, 0, DENVER), "only 1, 3 or 7 days: " + bad);
    }

    private static void done() {
        long now = at(DENVER, 2026, 11, 6, 9, 0);
        Reminder r = distanceReminder(now);
        MaintenancePolicy.snooze(r, 1, now, 1300, DENVER);
        MaintenancePolicy.markDone(r, now + HOUR, 1305, now + HOUR);
        check(r.baseAccKm == 1305 && r.baseWall == now + HOUR && r.snoozeUntil == 0, "done restarts the count and ends the snooze");
        check(MaintenancePolicy.status(r, now + HOUR, 1305, DENVER).state == State.OK, "OK straight after");
        check(MaintenancePolicy.DONE.equals(r.events.get(r.events.size() - 1).type), "done is recorded");
        Reminder t = MaintenancePolicy.create("t", MaintenanceDefaults.TIRE_PRESSURE, "", null, 14, 0, true, now, Double.NaN, now);
        long august = at(DENVER, 2026, 8, 20, 10, 0);
        MaintenancePolicy.markDone(t, august, Double.NaN, now);
        check(t.baseWall == august && MaintenancePolicy.status(t, now, Double.NaN, DENVER).state == State.DUE, "a backdated done counts from its date");
        check(t.events.get(0).wall == august && MaintenancePolicy.DONE.equals(t.events.get(0).type), "history stays in time order");
        MaintenancePolicy.markDone(t, now + 10 * DAY, Double.NaN, now);
        check(t.baseWall == now, "a done in the future is taken as now");
        Reminder past = MaintenancePolicy.create("p", "", "New tires", null, 0, 6, true, august, Double.NaN, now);
        check(past.baseWall == august && past.events.get(0).wall == now, "counting from a past date; Added is when it was added");
    }

    private static void calendar() {
        Reminder jan31 = MaintenancePolicy.create("m", MaintenanceDefaults.LIGHTS, "", null, 0, 1, true, at(DENVER, 2026, 1, 31, 15, 0), Double.NaN, at(DENVER, 2026, 1, 31, 15, 0));
        check(MaintenancePolicy.dueWall(jan31, DENVER) == at(DENVER, 2026, 2, 28, 0, 0), "a month from Jan 31 is Feb 28");
        Reminder leap = MaintenancePolicy.create("m", MaintenanceDefaults.LIGHTS, "", null, 0, 1, true, at(DENVER, 2028, 1, 31, 15, 0), Double.NaN, at(DENVER, 2028, 1, 31, 15, 0));
        check(MaintenancePolicy.dueWall(leap, DENVER) == at(DENVER, 2028, 2, 29, 0, 0), "and Feb 29 in a leap year");
        Reminder quarter = MaintenancePolicy.create("q", MaintenanceDefaults.CLEAN_DRY, "", null, 0, 3, true, at(DENVER, 2026, 11, 30, 8, 0), Double.NaN, at(DENVER, 2026, 11, 30, 8, 0));
        check(MaintenancePolicy.dueWall(quarter, DENVER) == at(DENVER, 2027, 2, 28, 0, 0), "three months from Nov 30 is Feb 28");
        long march = at(DENVER, 2026, 3, 1, 23, 30);
        Reminder spring = MaintenancePolicy.create("s", MaintenanceDefaults.BRAKES, "", null, 14, 0, true, march, Double.NaN, march);
        check(MaintenancePolicy.dueWall(spring, DENVER) == at(DENVER, 2026, 3, 15, 0, 0), "two weeks across spring forward lands on the calendar day");
        check(MaintenancePolicy.status(spring, at(DENVER, 2026, 3, 14, 23, 59), Double.NaN, DENVER).daysLeft == 1, "days left are calendar days in the phone's zone");
        check(MaintenancePolicy.dueWall(spring, UTC) != MaintenancePolicy.dueWall(spring, DENVER), "the zone decides the due day");
    }

    private static void caps() {
        long now = at(UTC, 2026, 11, 6, 9, 0);
        Reminder r = MaintenancePolicy.create("h", MaintenanceDefaults.TIRE_PRESSURE, "", null, 7, 0, true, now, Double.NaN, now);
        for (int i = 0; i < 40; i++) MaintenancePolicy.markDone(r, now + i * DAY, Double.NaN, now + i * DAY);
        check(r.events.size() == MaintenancePolicy.MAX_EVENTS && r.events.get(0).wall == now + 20 * DAY && r.events.get(19).wall == now + 39 * DAY,
                "history keeps the last 20 events, oldest dropped first");
        List<Reminder> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) check(MaintenancePolicy.add(list, MaintenancePolicy.create("r" + i, MaintenanceDefaults.LIGHTS, "", null, 30, 0, true, now, Double.NaN, now)), "reminder " + i + " fits");
        check(!MaintenancePolicy.add(list, MaintenancePolicy.create("r30", MaintenanceDefaults.LIGHTS, "", null, 30, 0, true, now, Double.NaN, now)) && list.size() == 30, "a 31st reminder is refused");
        list.remove(29);
        check(!MaintenancePolicy.add(list, MaintenancePolicy.create("r0", "", "Duplicate id", null, 30, 0, true, now, Double.NaN, now)), "a duplicate id is refused");
        String longName = new String(new char[79]).replace('\0', 'a') + "🛴" + "tail";
        Reminder named = MaintenancePolicy.create("n", "", longName, null, 30, 0, true, now, Double.NaN, now);
        check(named.name.length() == 79 && named.name.equals(longName.substring(0, 79)), "a long name is cut without splitting an emoji");
        rejects(() -> MaintenancePolicy.create("x", "", "  ", null, 30, 0, true, now, Double.NaN, now), "a custom reminder needs a name");
        rejects(() -> MaintenancePolicy.create("x", MaintenanceDefaults.BRAKES, "", null, 0, 0, true, now, Double.NaN, now), "a reminder needs a distance or a time");
        rejects(() -> MaintenancePolicy.create("x", MaintenanceDefaults.BRAKES, "", null, 14, 1, true, now, Double.NaN, now), "days or months, not both");
        rejects(() -> MaintenancePolicy.create("x", MaintenanceDefaults.BRAKES, "", -5.0, 0, 0, true, now, 0, now), "a negative distance is refused");
        rejects(() -> MaintenancePolicy.create("x", MaintenanceDefaults.BRAKES, "", Double.NaN, 0, 0, true, now, 0, now), "a NaN distance is refused");
        rejects(() -> MaintenancePolicy.create("", MaintenanceDefaults.BRAKES, "", 10.0, 0, 0, true, now, 0, now), "a reminder needs an id");
    }
}
