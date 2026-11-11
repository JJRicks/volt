package com.jjricks.volt;

import java.time.ZoneId;
import java.util.*;

/**
 * Adding a reminder with no connection (owner, Nov 11): the add and edit sheets' save
 * ({@link MaintenanceView#add}, {@link MaintenanceView#edit}) and their note ({@link MaintenanceView#distanceNote})
 * on a saved scooter whose odometer was never read, and on one whose last reading is old. A custom name by
 * distance, by time (days, months) and Either; then an app restart, a backup and restore (exact and onto another
 * scooter) and the first connection afterwards: no duplicate, no reset, no instant "due". Synthesized values only.
 */
public final class MaintenanceOfflineAddTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, DAY = 86_400_000L;
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String SCOPE = "p1";
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final class Memory implements MaintenanceKeeper.Storage {
        final Map<String, String> map = new HashMap<>();
        public String read(String key) { return map.get(key); }
        public void write(String key, String value) { map.put(key, value); }
        public void remove(String key) { map.remove(key); }
    }

    public static void main(String[] args) {
        Locale phone = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            notes();
            neverReadDistance();
            neverReadTime();
            neverReadEither();
            restartRestoreAndFirstConnection();
            movedToAnotherScooter();
            staleReading();
            editGainsDistanceOffline();
            backdatedWithoutReading();
        } finally {
            Locale.setDefault(phone);
        }
        System.out.println("MaintenanceOfflineAddTest: " + checks + " checks passed (notes; custom distance, time and Either with no reading; restart, restore, merge, first connection; stale reading; edit gains distance; earlier date)");
    }

    /** Reminders on, nothing read: what the Maintenance page writes when the switch is turned on disconnected. */
    private static MaintenanceKeeper switchedOn(Memory memory) {
        MaintenanceKeeper keeper = new MaintenanceKeeper(memory);
        keeper.update(SCOPE, s -> s.on = true);
        return keeper;
    }

    private static MaintenancePolicy.Reminder only(MaintenanceKeeper keeper) {
        List<MaintenancePolicy.Reminder> all = keeper.load(SCOPE).reminders;
        check(all.size() == 1, "one reminder, not " + all.size());
        return all.get(0);
    }

    private static MaintenancePolicy.Status status(MaintenanceKeeper keeper, long wall) {
        MaintenanceStore.Settings s = keeper.load(SCOPE); return MaintenanceView.status(s, s.reminders.get(0), wall, UTC);
    }

    /** The add sheet's save as the page runs it (MaintenanceScreen.change: MaintenanceKeeper.update). */
    private static MaintenanceDistance.Backdate add(MaintenanceKeeper keeper, String name, Double km, int days, int months, long baseWall, double typedRawKm, long now) {
        MaintenanceDistance.Backdate[] refused = {null};
        keeper.update(SCOPE, x -> refused[0] = MaintenanceView.add(x, "", name, km, days, months, true, baseWall, typedRawKm, now));
        return refused[0];
    }

    private static void notes() {
        MaintenanceDistance never = new MaintenanceDistance();
        check(MaintenanceView.distanceNote(never, null, WALL) == MaintenanceView.DistanceNote.NEXT_CONNECTION, "never read: counts from the next connection");
        MaintenanceDistance fresh = new MaintenanceDistance(); fresh.observe(1000, MaintenanceDistance.BITS_32, WALL - DAY);
        check(MaintenanceView.distanceNote(fresh, null, WALL) == MaintenanceView.DistanceNote.NONE, "a reading a day old: no note");
        check(MaintenanceView.distanceNote(fresh, null, WALL - DAY + MaintenanceDistance.STALE_MS) == MaintenanceView.DistanceNote.NONE, "exactly 7 days: not stale yet");
        check(MaintenanceView.distanceNote(fresh, null, WALL - DAY + MaintenanceDistance.STALE_MS + 1) == MaintenanceView.DistanceNote.STALE, "past 7 days: the stale note");
        MaintenancePolicy.Reminder waiting = MaintenancePolicy.create("w", "", "Chain", 100.0, 0, 0, true, WALL, Double.NaN, WALL);
        check(MaintenanceView.distanceNote(fresh, waiting, WALL) == MaintenanceView.DistanceNote.NEXT_CONNECTION, "editing a reminder still waiting: next connection");
        MaintenancePolicy.Reminder based = MaintenancePolicy.create("b", "", "Chain", 100.0, 0, 0, true, WALL, 0, WALL);
        check(MaintenanceView.distanceNote(fresh, based, WALL) == MaintenanceView.DistanceNote.NONE, "editing a counting reminder: no note");
        MaintenanceDistance merged = fresh.copy(); merged.clearBaseline();
        check(MaintenanceView.distanceNote(merged, null, WALL) == MaintenanceView.DistanceNote.NONE, "a restore onto another scooter keeps the count: no next-connection note");
    }

    private static void neverReadDistance() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = switchedOn(memory);
        check(add(keeper, "Oil the hinge", 300.0, 0, 0, WALL, Double.NaN, WALL) == null, "a custom distance reminder saves with no reading");
        MaintenancePolicy.Reminder r = only(keeper);
        check("Oil the hinge".equals(r.name) && r.task.isEmpty() && r.km == 300.0 && !r.byTime(), "the typed name and distance are kept");
        check(MaintenancePolicy.pending(r) && r.baseWall == WALL, "it waits for its first reading");
        check(status(keeper, WALL + 400 * DAY).state == MaintenancePolicy.State.WAITING, "no reading: never due, however long");
        check(!keeper.summary(SCOPE, WALL + 400 * DAY, UTC).dot() && keeper.summary(SCOPE, WALL, UTC).total == 1, "counted on the row, no dot");
        check(memory.map.size() == 1 && memory.map.containsKey(MaintenanceStore.key(SCOPE)), "only this scooter's document is written");
    }

    private static void neverReadTime() {
        MaintenanceKeeper keeper = switchedOn(new Memory());
        add(keeper, "Check the bell", null, 14, 0, WALL, Double.NaN, WALL);
        MaintenancePolicy.Status now = status(keeper, WALL);
        check(now.state == MaintenancePolicy.State.OK && now.fraction == 0 && now.daysLeft == 14, "a time reminder starts now");
        check(status(keeper, WALL + 14 * DAY).state == MaintenancePolicy.State.DUE, "and is due in 14 days");
        check(!MaintenancePolicy.pending(only(keeper)), "a time reminder never waits for a reading");
        MaintenanceKeeper months = switchedOn(new Memory());
        add(months, "Grease", null, 0, 3, WALL, Double.NaN, WALL);
        check(status(months, WALL).state == MaintenancePolicy.State.OK && only(months).months == 3, "by months, too");
    }

    private static void neverReadEither() {
        MaintenanceKeeper keeper = switchedOn(new Memory());
        add(keeper, "Pads", 500.0, 0, 6, WALL, Double.NaN, WALL);
        MaintenancePolicy.Reminder r = only(keeper);
        check(MaintenancePolicy.pending(r) && r.months == 6, "Either: the time part counts, the distance part waits");
        check(status(keeper, WALL + DAY).state == MaintenancePolicy.State.OK, "not due a day later");
    }

    private static void restartRestoreAndFirstConnection() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = switchedOn(memory);
        add(keeper, "Oil the hinge", 300.0, 0, 0, WALL, Double.NaN, WALL);
        add(keeper, "Check the bell", null, 14, 0, WALL, Double.NaN, WALL + 1);
        String saved = memory.map.get(MaintenanceStore.key(SCOPE));
        // An app restart: a new keeper on the same preferences.
        MaintenanceKeeper restarted = new MaintenanceKeeper(memory);
        check(MaintenanceStore.encode(restarted.load(SCOPE)).equals(saved) && restarted.load(SCOPE).reminders.size() == 2, "a restart reads the same two reminders");
        // A backup and restore carries the document text as it is (MaintenanceBackupTest covers the archive).
        Memory phone = new Memory(); phone.map.put(MaintenanceStore.key(SCOPE), saved);
        MaintenanceKeeper restored = new MaintenanceKeeper(phone);
        MaintenanceStore.Settings back = restored.load(SCOPE);
        check(back.reminders.size() == 2 && MaintenancePolicy.pending(back.find(back.reminders.get(0).id)), "restored: two reminders, the distance one still waiting");
        // The first connection: odometer 1,234.5 km. It becomes the base: 300 km left, not due, nothing added twice.
        restored.observe(SCOPE, 1234.5, MaintenanceDistance.BITS_32, WALL + 2 * DAY);
        MaintenanceStore.Settings s = restored.load(SCOPE);
        MaintenancePolicy.Reminder distance = s.reminders.get(0);
        check(s.reminders.size() == 2 && !MaintenancePolicy.pending(distance) && distance.baseAccKm == s.distance.accumulatedKm, "the first reading is the base; no duplicate");
        MaintenancePolicy.Status first = MaintenanceView.status(s, distance, WALL + 2 * DAY, UTC);
        check(first.state == MaintenancePolicy.State.OK && first.kmLeft == 300, "not due at the first connection: 300 km left");
        check(distance.baseWall == WALL && distance.events.size() == 1, "its count start and history are unchanged");
        // The same reading again (a reconnect) changes nothing; 120 km of riding counts.
        restored.observe(SCOPE, 1234.5, MaintenanceDistance.BITS_32, WALL + 3 * DAY);
        restored.observe(SCOPE, 1354.5, MaintenanceDistance.BITS_32, WALL + 4 * DAY);
        s = restored.load(SCOPE);
        check(MaintenanceView.status(s, s.reminders.get(0), WALL + 4 * DAY, UTC).kmLeft == 180, "120 km later: 180 km left");
        check(MaintenanceView.status(s, s.reminders.get(1), WALL + 4 * DAY, UTC).daysLeft == 10, "the time reminder kept its own start");
    }

    private static void movedToAnotherScooter() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = switchedOn(memory);
        add(keeper, "Oil the hinge", 300.0, 0, 0, WALL, Double.NaN, WALL);
        String text = memory.map.get(MaintenanceStore.key(SCOPE));
        check(text.equals(MaintenanceStore.movedToAnotherScooter(MaintenanceStore.key(SCOPE), text)), "nothing read, nothing to drop: a merge keeps the document");
        Memory other = new Memory(); other.map.put(MaintenanceStore.key("p2"), text);
        MaintenanceKeeper there = new MaintenanceKeeper(other);
        there.observe("p2", 50, MaintenanceDistance.BITS_16, WALL + DAY);
        MaintenanceStore.Settings s = there.load("p2");
        check(MaintenanceView.status(s, s.reminders.get(0), WALL + DAY, UTC).kmLeft == 300, "its first reading there is its base");
    }

    private static void staleReading() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = switchedOn(memory);
        keeper.observe(SCOPE, 1000, MaintenanceDistance.BITS_32, WALL - 20 * DAY);
        keeper.observe(SCOPE, 1250, MaintenanceDistance.BITS_32, WALL - 10 * DAY);
        keeper.flush(SCOPE);
        MaintenanceStore.Settings before = keeper.load(SCOPE);
        check(MaintenanceView.distanceNote(before.distance, null, WALL) == MaintenanceView.DistanceNote.STALE, "a reading 10 days old: the stale note");
        add(keeper, "Tighten the stem", 300.0, 0, 0, WALL, Double.NaN, WALL);
        MaintenancePolicy.Reminder r = only(keeper);
        check(r.baseAccKm == 250 && !MaintenancePolicy.pending(r), "it counts from the last known reading");
        check(status(keeper, WALL).state == MaintenancePolicy.State.OK && status(keeper, WALL).kmLeft == 300, "300 km left when added");
        // Volt connects again: 150 km were ridden since the last reading (as the stale note says, they count).
        keeper.observe(SCOPE, 1400, MaintenanceDistance.BITS_32, WALL + DAY);
        check(status(keeper, WALL + DAY).kmLeft == 150 && status(keeper, WALL + DAY).state == MaintenancePolicy.State.OK, "150 km on: not due");
        keeper.observe(SCOPE, 1560, MaintenanceDistance.BITS_32, WALL + 2 * DAY);
        check(status(keeper, WALL + 2 * DAY).state == MaintenancePolicy.State.DUE, "310 km on: due");
        check(keeper.load(SCOPE).reminders.size() == 1, "still one reminder");
    }

    private static void editGainsDistanceOffline() {
        Memory memory = new Memory(); MaintenanceKeeper keeper = switchedOn(memory);
        add(keeper, "Check the bell", null, 30, 0, WALL, Double.NaN, WALL);
        String id = only(keeper).id;
        keeper.update(SCOPE, x -> MaintenanceView.edit(x, id, "", "Check the bell", 200.0, 30, 0, false));
        MaintenancePolicy.Reminder r = only(keeper);
        check(r.km == 200.0 && r.days == 30 && !r.notify && MaintenancePolicy.pending(r), "an edit adds a distance part with no reading: it waits");
        check(r.baseWall == WALL && r.events.size() == 1, "the edit keeps the count start and history");
        keeper.observe(SCOPE, 800, MaintenanceDistance.BITS_32, WALL + DAY);
        check(!MaintenancePolicy.pending(only(keeper)) && status(keeper, WALL + DAY).kmLeft == 200, "the first reading starts it");
        keeper.update(SCOPE, x -> MaintenanceView.edit(x, "missing", "", "Nothing", 1.0, 0, 0, true));
        check(keeper.load(SCOPE).reminders.size() == 1, "editing a reminder that is gone adds nothing");
    }

    private static void backdatedWithoutReading() {
        MaintenanceKeeper keeper = switchedOn(new Memory());
        // Counting from an earlier date with no odometer value typed (the field is hidden without a baseline).
        add(keeper, "Pads", 500.0, 0, 6, WALL - 30 * DAY, Double.NaN, WALL);
        MaintenancePolicy.Reminder r = only(keeper);
        check(r.baseWall == WALL - 30 * DAY && MaintenancePolicy.pending(r), "the date counts now, the distance from the next reading");
        check(add(keeper, "Pads again", 500.0, 0, 0, WALL, 900, WALL) == MaintenanceDistance.Backdate.NO_BASELINE, "a typed earlier reading with no baseline is refused, not guessed");
        check(keeper.load(SCOPE).reminders.size() == 1, "and adds nothing");
    }
}
