package com.jjricks.volt;

import com.jjricks.volt.MaintenancePolicy.Reminder;
import com.jjricks.volt.MaintenanceStore.Settings;

import java.util.*;

/** The per-scooter maintenance JSON: round trip, tolerant decoding, kept unknown fields, caps, and the merge clear. */
public final class MaintenanceStoreTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, DAY = 86_400_000L;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        keys();
        roundTrip();
        tolerant();
        unknownFields();
        caps();
        merge();
        defaults();
        System.out.println("MaintenanceStoreTest: " + checks + " checks passed (keys, round trip, tolerant decode, unknown fields, caps, merge clear, defaults)");
    }

    private static void keys() {
        check("maintenance_v1_abc".equals(MaintenanceStore.key("abc")) && "maintenance_seen_abc".equals(MaintenanceStore.seenKey("abc")), "keys");
        BackupRegistry.Rule main = BackupRegistry.rule("app_settings", MaintenanceStore.key("abc-1")), seen = BackupRegistry.rule("app_settings", MaintenanceStore.seenKey("abc-1"));
        check(main != null && BackupRegistry.SCOOTER_PREFERENCES.equals(main.category) && main.type == String.class && "abc-1".equals(main.profile(MaintenanceStore.key("abc-1")))
                && MaintenanceStore.key("new").equals(main.rename(MaintenanceStore.key("abc-1"), "new")), "reminders are Scooter preferences and remap with the scooter");
        check(seen != null && seen.excluded() && "abc-1".equals(seen.profile(MaintenanceStore.seenKey("abc-1"))), "the notified bookkeeping stays on this phone");
    }

    private static Settings sample() {
        Settings s = new Settings();
        s.on = true;
        s.distance.observe(2009.4, 32, WALL - 40 * DAY);
        s.distance.observe(2347.5, 32, WALL);
        s.distance.resetWall = WALL - 50 * DAY;
        Reminder brakes = MaintenancePolicy.create("r1", MaintenanceDefaults.BRAKES, "", 300 * 1.609344, 0, 0, true, WALL - 30 * DAY, 100.25, WALL - 30 * DAY);
        MaintenancePolicy.markDone(brakes, WALL - 10 * DAY, 250.5, WALL - 10 * DAY);
        MaintenancePolicy.snooze(brakes, 7, WALL, 338.1, java.time.ZoneId.of("UTC"));
        Reminder custom = MaintenancePolicy.create("r2", "", "Kickstand \"spring\" \\ check 🛴\n", null, 0, 3, false, WALL, Double.NaN, WALL);
        MaintenancePolicy.add(s.reminders, brakes); MaintenancePolicy.add(s.reminders, custom);
        return s;
    }

    private static void roundTrip() {
        Settings s = sample();
        String json = MaintenanceStore.encode(s);
        Settings back = MaintenanceStore.decode(json);
        check(json.equals(MaintenanceStore.encode(back)), "encode(decode(encode(s))) is stable: " + json);
        check(back.on && back.distance.lastRawKm == 2347.5 && back.distance.bits == 32 && back.distance.accumulatedKm == s.distance.accumulatedKm
                && back.distance.lastWall == WALL && back.distance.resetWall == WALL - 50 * DAY, "the distance state round-trips");
        Reminder b = back.find("r1"), c = back.find("r2");
        check(b != null && c != null && back.reminders.size() == 2, "both reminders");
        check(b.km == 300 * 1.609344 && b.baseAccKm == 250.5 && b.baseWall == WALL - 10 * DAY && b.snoozeUntil == WALL + 7 * DAY && b.notify && MaintenanceDefaults.BRAKES.equals(b.task),
                "km stay exact (300 mi), with the count start and snooze");
        check(b.events.size() == 3 && MaintenancePolicy.ADDED.equals(b.events.get(0).type) && b.events.get(0).accKm == 100.25 && MaintenancePolicy.SNOOZED.equals(b.events.get(2).type), "history round-trips");
        check(c.km == null && c.months == 3 && !c.notify && Double.isNaN(c.baseAccKm) && c.name.equals("Kickstand \"spring\" \\ check 🛴\n") && c.task.isEmpty(), "a free-text name is kept verbatim");
        Settings off = MaintenanceStore.decode(MaintenanceStore.encode(new Settings()));
        check(!off.on && off.reminders.isEmpty() && !off.distance.hasBaseline() && !off.distance.everRead(), "an empty document stays empty");
        check(!MaintenanceStore.encode(new Settings()).contains("lastRawKm"), "no baseline, no lastRawKm");
    }

    private static void tolerant() {
        for (String bad : new String[]{null, "", "garbage", "[]", "{\"on\":true", "{\"on\":true,\"on\":false}", "\"text\"", "7", "{\"on\":true}x",
                "{" + new String(new char[20]).replace("\0", "\"a\":{") + "1" + new String(new char[20]).replace("\0", "}") + "}"}) {
            Settings s = MaintenanceStore.decode(bad);
            check(!s.on && s.reminders.isEmpty(), "unreadable is off with no reminders: " + bad);
        }
        StringBuilder huge = new StringBuilder("{\"on\":true,\"pad\":\""); while (huge.length() <= MaintenanceStore.MAX_LENGTH) huge.append("xxxxxxxxxx"); huge.append("\"}");
        check(!MaintenanceStore.decode(huge.toString()).on, "an oversized document is unreadable");
        Settings typed = MaintenanceStore.decode("{\"on\":\"yes\",\"distance\":{\"lastRawKm\":\"5\",\"accKm\":-3,\"bits\":12,\"lastWall\":-9},\"reminders\":7}");
        check(!typed.on && !typed.distance.hasBaseline() && typed.distance.accumulatedKm == 0 && typed.distance.bits == 0 && typed.distance.lastWall == 0 && typed.reminders.isEmpty(),
                "wrong field types fall back field by field");
        String good = "{\"id\":\"ok\",\"task\":\"brakes\",\"name\":\"\",\"km\":100.0,\"days\":0,\"months\":0,\"notify\":true,\"baseAccKm\":5.0,\"baseWall\":" + WALL + ",\"snoozeUntil\":0,\"events\":["
                + "{\"t\":\"done\",\"wall\":" + WALL + "},{\"t\":\"\",\"wall\":5},{\"t\":\"done\",\"wall\":-1},7,{\"t\":\"done\",\"wall\":" + (WALL + 1) + ",\"accKm\":\"x\"}]}";
        String[] badReminders = {"7", "{}", "{\"id\":\"x\",\"task\":\"\",\"name\":\"\",\"km\":10.0,\"baseWall\":" + WALL + "}",
                "{\"id\":\"x\",\"task\":\"brakes\",\"km\":-1.0,\"baseWall\":" + WALL + "}",
                "{\"id\":\"x\",\"task\":\"brakes\",\"km\":\"10\",\"baseWall\":" + WALL + "}",
                "{\"id\":\"x\",\"task\":\"brakes\",\"days\":3,\"months\":2,\"baseWall\":" + WALL + "}",
                "{\"id\":\"x\",\"task\":\"brakes\",\"days\":3}",
                "{\"id\":\"x\",\"task\":\"brakes\",\"days\":3.5,\"baseWall\":" + WALL + "}"};
        Settings mixed = MaintenanceStore.decode("{\"v\":1,\"on\":true,\"reminders\":[" + String.join(",", badReminders) + "," + good + "," + good + "]}");
        check(mixed.on && mixed.reminders.size() == 1 && "ok".equals(mixed.reminders.get(0).id), "a malformed reminder is dropped, the good one (once) kept");
        Reminder ok = mixed.reminders.get(0);
        check(ok.events.size() == 2 && Double.isNaN(ok.events.get(1).accKm), "a malformed event is dropped; an unreadable distance in one becomes unknown");
        Settings unknownTask = MaintenanceStore.decode("{\"on\":true,\"reminders\":[{\"id\":\"f\",\"task\":\"chain_lube\",\"name\":\"\",\"days\":30,\"baseWall\":" + WALL + "}]}");
        check(unknownTask.reminders.size() == 1 && "chain_lube".equals(unknownTask.reminders.get(0).task), "a task id from a newer version is kept");
    }

    private static void unknownFields() {
        String newer = "{\"v\":2,\"on\":true,\"future\":{\"deep\":[1,2.5,null,true,\"x\"]},\"distance\":{\"accKm\":12.5,\"lastRawKm\":99.9,\"bits\":16,\"lastWall\":" + WALL + ",\"source\":\"uwb\"},"
                + "\"reminders\":[{\"id\":\"a\",\"task\":\"lights\",\"name\":\"\",\"days\":7,\"baseWall\":" + WALL + ",\"colour\":\"red\",\"events\":[{\"t\":\"skipped\",\"wall\":" + WALL + ",\"why\":\"rain\"}]}]}";
        Settings s = MaintenanceStore.decode(newer);
        check(s.on && s.reminders.size() == 1 && s.distance.accumulatedKm == 12.5, "a newer document is read for what this version knows");
        String again = MaintenanceStore.encode(s);
        check(again.contains("\"future\":{\"deep\":[1,2.5,null,true,\"x\"]}") && again.contains("\"colour\":\"red\"") && again.contains("\"why\":\"rain\"") && again.contains("\"source\":\"uwb\"") && again.contains("\"t\":\"skipped\""),
                "unknown top-level, distance, reminder and event fields are kept: " + again);
        check(MaintenanceStore.encode(MaintenanceStore.decode(again)).equals(again), "and stay stable");
    }

    private static void caps() {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 45; i++) {
            if (i > 0) list.append(',');
            list.append("{\"id\":\"r").append(i).append("\",\"task\":\"lights\",\"name\":\"\",\"days\":7,\"baseWall\":").append(WALL).append(",\"events\":[");
            for (int e = 0; e < 30; e++) list.append(e > 0 ? "," : "").append("{\"t\":\"done\",\"wall\":").append(WALL + e).append('}');
            list.append("]}");
        }
        Settings s = MaintenanceStore.decode("{\"on\":true,\"reminders\":[" + list + "]}");
        check(s.reminders.size() == MaintenancePolicy.MAX_REMINDERS && "r29".equals(s.reminders.get(29).id), "decoding keeps the first 30 reminders");
        check(s.reminders.get(0).events.size() == MaintenancePolicy.MAX_EVENTS && s.reminders.get(0).events.get(0).wall == WALL + 10, "and the last 20 events of each");
        String json = MaintenanceStore.encode(s);
        check(json.length() < 64 * 1024, "a full document stays small: " + json.length());
        Settings over = MaintenanceStore.decode(json);
        for (int i = 0; i < 5; i++) over.reminders.add(MaintenancePolicy.create("x" + i, "", "Extra", null, 7, 0, true, WALL, Double.NaN, WALL));
        check(MaintenanceStore.decode(MaintenanceStore.encode(over)).reminders.size() == MaintenancePolicy.MAX_REMINDERS, "encoding never writes past the cap");
    }

    private static void merge() {
        String json = MaintenanceStore.encode(sample());
        String moved = (String) MaintenanceStore.movedToAnotherScooter(MaintenanceStore.key("p"), json);
        Settings s = MaintenanceStore.decode(moved), before = MaintenanceStore.decode(json);
        check(!s.distance.hasBaseline() && s.distance.everRead() && s.distance.accumulatedKm == before.distance.accumulatedKm && s.reminders.size() == 2,
                "moved onto another scooter: the raw baseline goes, the count and reminders stay");
        check(moved.equals(json.replace("\"lastRawKm\":2347.5,", "")), "nothing else changes: " + moved);
        check(MaintenanceStore.movedToAnotherScooter(MaintenanceStore.key("p"), moved).equals(moved), "no baseline: unchanged");
        check(MaintenanceStore.movedToAnotherScooter(MaintenanceStore.key("p"), "garbage").equals("garbage"), "unreadable text is passed on unchanged");
        check(MaintenanceStore.movedToAnotherScooter("charge_alerts_p", json) == json && MaintenanceStore.movedToAnotherScooter(MaintenanceStore.key("p"), 7).equals(7), "other keys and types are untouched");
        s.distance.observe(50.0, 32, WALL + DAY);
        check(s.distance.accumulatedKm == before.distance.accumulatedKm, "the next reading on the new scooter adds nothing");
    }

    private static void defaults() {
        check(MaintenanceDefaults.table().isEmpty(), "no manual reviewed yet: the table ships empty");
        for (String model : NaveeModels.all()) check(MaintenanceDefaults.forModel(model).kind == MaintenanceDefaults.Kind.NOT_LISTED, "no intervals listed yet: " + model);
        check(MaintenanceDefaults.forModel(null).kind == MaintenanceDefaults.Kind.UNKNOWN_MODEL && MaintenanceDefaults.forModel(" ").kind == MaintenanceDefaults.Kind.UNKNOWN_MODEL, "no model name: unknown model");
        MaintenanceDefaults.Source fixture = new MaintenanceDefaults.Source("Test manual", "2024-03", "p. 18");
        List<MaintenanceDefaults.Interval> table = Collections.singletonList(new MaintenanceDefaults.Interval("XT5 Pro", MaintenanceDefaults.BRAKES, 500.0, 90, 0, fixture));
        MaintenanceDefaults.Result listed = MaintenanceDefaults.forModel("XT5 Pro", table);
        check(listed.kind == MaintenanceDefaults.Kind.LISTED && listed.intervals.size() == 1 && listed.intervals.get(0).source.page.equals("p. 18"), "a sourced fixture entry is listed with its citation");
        check(MaintenanceDefaults.forModel("UT5 Max", table).kind == MaintenanceDefaults.Kind.NOT_LISTED, "other models stay not listed");
        refused(() -> new MaintenanceDefaults.Interval("UT5 Max", MaintenanceDefaults.TIRE_PRESSURE, null, 14, 0, null), "an interval without a source is refused");
        for (String[] parts : new String[][]{{"", "2024", "p. 1"}, {"Manual", " ", "p. 1"}, {"Manual", "2024", null}})
            refused(() -> new MaintenanceDefaults.Source(parts[0], parts[1], parts[2]), "a source needs its title, edition and page");
        refused(() -> new MaintenanceDefaults.Interval("UT5 Max", "made_up", 100.0, 0, 0, fixture), "an unknown task id is refused");
        refused(() -> new MaintenanceDefaults.Interval("UT5 Max", MaintenanceDefaults.BRAKES, null, 0, 0, fixture), "an interval needs a distance or a time");
        refused(() -> new MaintenanceDefaults.Interval("UT5 Max", MaintenanceDefaults.BRAKES, -1.0, 0, 0, fixture), "a negative distance is refused");
        check(MaintenanceDefaults.TASKS.size() == 6 && new HashSet<>(MaintenanceDefaults.TASKS).size() == 6, "six stable suggested task ids");
        for (String task : MaintenanceDefaults.TASKS) check(task.matches("[a-z_]{1,16}"), "task ids are short stable values: " + task);
    }

    private static void refused(Runnable r, String message) { checks++; try { r.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { } }
}
