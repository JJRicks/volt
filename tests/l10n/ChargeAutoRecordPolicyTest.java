package com.jjricks.volt;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Record charges automatically: ChargeAutoRecordPolicy's rules and the store's automatic recordings. Synthesized data only. */
public final class ChargeAutoRecordPolicyTest {
    static int checks;
    static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L, MINUTE = 60_000L;
    static final ChargeSessionStore.Charger STOCK = ChargeSessionStore.Charger.STOCK, FAST = ChargeSessionStore.Charger.HIGH_SPEED;
    static final ChargeSessionStore.LabelSource CHOSEN = ChargeSessionStore.LabelSource.CHOSEN,
            DETECTED = ChargeSessionStore.LabelSource.DETECTED, GUESS = ChargeSessionStore.LabelSource.GUESS;

    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        start();
        label();
        relabel();
        speed();
        liveReadings();
        chargeOff();
        keep();
        limit();
        hold();
        storeIds();
        storeKeep();
        storeLimit();
        storeRelabel();
        storeExport();
        storeRecovery();
        storeChargeOff();
        System.out.println("ChargeAutoRecordPolicyTest: " + checks + " checks");
    }

    static void start() {
        check(ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, false, false, false), "On, connected to the selected scooter, charging: start");
        check(!ChargeAutoRecordPolicy.shouldStart(false, true, true, false, false, false, false, false), "Off by default: nothing starts");
        check(!ChargeAutoRecordPolicy.shouldStart(true, false, true, false, false, false, false, false), "Not connected to the selected scooter: no start");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, false, false, false, false, false, false), "Not charging: no start");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, true, true, false, false, false, false), "Never in simulation");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, true, false, true, false, false, false), "A manual start would be refused: no start");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, true, false, false), "A recording already runs: no second one");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, false, true, false), "A hand stop's hold: no start");
        check(!ChargeAutoRecordPolicy.shouldStart(true, true, true, false, false, false, false, true), "Moving above the end speed: no start");
        check(ChargeAutoRecordPolicy.KEY.equals("charge_auto_record") && ChargeAutoRecordPolicy.HOLD_KEY.equals("charge_auto_record_hold"), "Stored keys");
    }

    static void label() {
        ChargeAutoRecordPolicy.Label l = ChargeAutoRecordPolicy.label(FAST, STOCK, 5, 0);
        check(l.charger == FAST && l.source == CHOSEN, "The chosen charger wins over detection and history");
        l = ChargeAutoRecordPolicy.label(null, FAST, 5, 0);
        check(l.charger == FAST && l.source == DETECTED, "Detection for this charge comes next");
        l = ChargeAutoRecordPolicy.label(null, null, 1, 3);
        check(l.charger == FAST && l.source == GUESS, "Guess: the family with more qualified recordings");
        l = ChargeAutoRecordPolicy.label(null, null, 3, 1);
        check(l.charger == STOCK && l.source == GUESS, "Guess follows the larger stock history");
        l = ChargeAutoRecordPolicy.label(null, null, 2, 2);
        check(l.charger == STOCK && l.source == GUESS, "A tie guesses the stock charger");
        l = ChargeAutoRecordPolicy.label(null, null, 0, 0);
        check(l.charger == STOCK && l.source == GUESS, "No history guesses the stock charger every scooter ships with");
    }

    static void relabel() {
        check(ChargeAutoRecordPolicy.relabel(CHOSEN, STOCK, FAST) == null, "A chosen label never follows detection");
        check(ChargeAutoRecordPolicy.relabel(DETECTED, STOCK, FAST) == FAST, "A detected label follows detection to the other family");
        check(ChargeAutoRecordPolicy.relabel(GUESS, STOCK, FAST) == FAST, "A guess follows detection once it settles");
        check(ChargeAutoRecordPolicy.relabel(GUESS, STOCK, STOCK) == null, "Same family: nothing to change");
        check(ChargeAutoRecordPolicy.relabel(GUESS, FAST, null) == null, "No detection: keep the first label");
        check(ChargeAutoRecordPolicy.relabel(null, STOCK, FAST) == null, "A manual recording is never relabelled");
    }

    static void speed() {
        long now = 50_000;
        check(ChargeAutoRecordPolicy.END_SPEED_KMH == 4.8, "About 3 mph, named once in km/h");
        check(!ChargeAutoRecordPolicy.movedAway(true, 48, now, now), "Exactly 4.8 km/h does not end it");
        check(ChargeAutoRecordPolicy.movedAway(true, 49, now, now), "4.9 km/h ends it");
        check(!ChargeAutoRecordPolicy.movedAway(true, 30, now, now), "Jostling below the threshold does not end it");
        check(!ChargeAutoRecordPolicy.movedAway(true, 0, now, now), "Stopped does not end it");
        check(ChargeAutoRecordPolicy.movedAway(true, 200, now - AutoStartPolicy.SPEED_FRESH_MS, now), "A reading exactly 3 s old is live");
        check(!ChargeAutoRecordPolicy.movedAway(true, 200, now - AutoStartPolicy.SPEED_FRESH_MS - 1, now), "An older reading is not live");
        check(!ChargeAutoRecordPolicy.movedAway(false, 200, now, now), "Needs a connection");
        check(!ChargeAutoRecordPolicy.movedAway(true, 200, 0, now), "Needs a reading");
        check(!ChargeAutoRecordPolicy.movedAway(true, 200, now + 1, now), "A reading from the future is not trusted");
    }

    /** #418's rule: the link being kept decides nothing; only a reading live by its own rule starts or clears anything. */
    static void liveReadings() {
        long now = 500_000;
        long age = ChargeSessionStore.MAX_FRAME_AGE_MS;
        check(age == 5000, "The charge recording's frame rule is 5 s");
        check(Boolean.TRUE.equals(ChargeAutoRecordPolicy.liveCharging(value(1, now), now)), "A charging reading taken now is live");
        check(Boolean.TRUE.equals(ChargeAutoRecordPolicy.liveCharging(value(1, now - age), now)), "Exactly 5 s old is live");
        check(ChargeAutoRecordPolicy.liveCharging(value(1, now - age - 1), now) == null, "Older than 5 s: no answer, so no start");
        check(ChargeAutoRecordPolicy.liveCharging(value(1, now - 15_000), now) == null, "The last 15 s background read, once the next is due, starts nothing");
        check(ChargeAutoRecordPolicy.liveCharging(value(1, now - 3_600_000), now) == null, "A reading from before an hour's sleep starts nothing");
        check(Boolean.FALSE.equals(ChargeAutoRecordPolicy.liveCharging(value(0, now - 1000), now)), "A live not-charging reading");
        check(ChargeAutoRecordPolicy.liveCharging(value(0, now - age - 1), now) == null, "A stale not-charging reading clears nothing");
        check(ChargeAutoRecordPolicy.liveCharging(value(1, now + 1), now) == null, "A reading from the future is not trusted");
        check(ChargeAutoRecordPolicy.liveCharging(value(2, now), now) == null, "An unknown raw state is neither");
        check(ChargeAutoRecordPolicy.liveCharging(null, now) == null, "No reading");
        check(ChargeAutoRecordPolicy.liveCharging(new ChargeSessionStore.Value(null, now, 0x72), now) == null, "No value");
        check(ChargeAutoRecordPolicy.liveCharging(new ChargeSessionStore.Value(1d, now, -1), now) == null, "A value with no source read");
        check(ChargeAutoRecordPolicy.liveCharging(new ChargeSessionStore.Value(Double.NaN, now, 0x72), now) == null, "Not a number");
        check(ChargeAutoRecordPolicy.liveCharging(value(1, 0), now) == null, "Never read");
        // What the service does with the answer: start only on TRUE, clear a hold only on FALSE.
        for (Boolean live : new Boolean[]{null, Boolean.TRUE, Boolean.FALSE}) {
            boolean start = ChargeAutoRecordPolicy.shouldStart(true, true, Boolean.TRUE.equals(live), false, false, false, false, false);
            boolean clears = ChargeAutoRecordPolicy.clearsHold(live != null, live == null ? -1 : live ? 1 : 0);
            check(start == Boolean.TRUE.equals(live), "Starts only on a live charging reading: " + live);
            check(clears == Boolean.FALSE.equals(live), "Clears only on a live not-charging reading: " + live);
        }
    }

    static void chargeOff() {
        check(ChargeAutoRecordPolicy.chargerOffWaitMs(true) == 2 * MINUTE, "Automatic recordings end two minutes after charging stops");
        check(ChargeAutoRecordPolicy.chargerOffWaitMs(false) == ChargeSessionStore.CHARGER_PAUSE_MAX_MS
                && ChargeSessionStore.CHARGER_PAUSE_MAX_MS == 30 * MINUTE, "Manual recordings keep the 30-minute charger-pause wait");
        check(ChargeAutoRecordPolicy.chargeOffEnds(true, false, 2 * MINUTE) && !ChargeAutoRecordPolicy.chargeOffEnds(true, false, 2 * MINUTE - 1), "Two minutes exactly ends an automatic recording");
        check(!ChargeAutoRecordPolicy.chargeOffEnds(true, true, 10 * 60 * MINUTE), "A charging schedule holding the current off never ends it");
        check(!ChargeAutoRecordPolicy.chargeOffEnds(false, false, 29 * MINUTE) && ChargeAutoRecordPolicy.chargeOffEnds(false, false, 30 * MINUTE), "Manual: 30 minutes");
    }

    static void keep() {
        check(ChargeAutoRecordPolicy.keep(10 * MINUTE, 5), "Exactly 10 minutes and exactly 5 points is kept");
        check(!ChargeAutoRecordPolicy.keep(10 * MINUTE - 1, 5), "Just under 10 minutes is not kept");
        check(!ChargeAutoRecordPolicy.keep(10 * MINUTE, 4.999), "Just under 5 points is not kept");
        check(!ChargeAutoRecordPolicy.keep(60 * MINUTE, 0), "A long recording with no gain is not kept");
        check(ChargeAutoRecordPolicy.keep(60 * MINUTE, 40), "A long charge is kept");
    }

    static void limit() {
        int max = ChargeSessionStore.MAX_SESSIONS;
        check(ChargeAutoRecordPolicy.automaticToRemove(max - 1, 0, max) == 0, "Room for one more: nothing removed");
        check(ChargeAutoRecordPolicy.automaticToRemove(max, 1, max) == 1, "At the limit: replace the oldest automatic one");
        check(ChargeAutoRecordPolicy.automaticToRemove(max, 0, max) == -1, "All manual at the limit: the automatic one is not kept");
        check(ChargeAutoRecordPolicy.automaticToRemove(max + 1, 1, max) == -1, "Never a manual one to make room");
        check(ChargeAutoRecordPolicy.automaticToRemove(max + 1, 2, max) == 2, "Over the limit (a restore): only automatic ones go");
    }

    static void hold() {
        check(ChargeAutoRecordPolicy.holdsAfterHandStop(true, true) && ChargeAutoRecordPolicy.holdsAfterHandStop(false, true), "A hand stop of an automatic recording holds, the switch on or since turned off");
        check(ChargeAutoRecordPolicy.holdsAfterHandStop(true, false), "A hand stop of a recording the owner started holds while the switch is on: Volt doesn't record the charge he just stopped");
        check(!ChargeAutoRecordPolicy.holdsAfterHandStop(false, false), "Switch off, a manual recording: nothing stored, as before");
        check(ChargeAutoRecordPolicy.rideEndsRecording(true) && !ChargeAutoRecordPolicy.rideEndsRecording(false), "A ride ends an automatic recording; a manual one still refuses");
        check(ChargeAutoRecordPolicy.clearsHold(true, 0), "A fresh not-charging reading clears the hold");
        check(!ChargeAutoRecordPolicy.clearsHold(true, 1), "Still charging keeps the hold");
        check(!ChargeAutoRecordPolicy.clearsHold(false, 0), "A stale reading keeps the hold");
        Set<String> holds = ChargeAutoRecordPolicy.holds("one  two bad,id ");
        check(holds.equals(new LinkedHashSet<>(Arrays.asList("one", "two"))), "Stored holds parse; malformed entries are dropped: " + holds);
        check(ChargeAutoRecordPolicy.holds(null).isEmpty() && ChargeAutoRecordPolicy.holds("").isEmpty(), "Absent: no hold");
        check(ChargeAutoRecordPolicy.holds(ChargeAutoRecordPolicy.store(holds)).equals(holds), "Holds round-trip");
    }

    // The store, with synthesized charges.

    static File folder() throws Exception { return Files.createTempDirectory("charge-auto").toFile(); }
    static ChargeSessionStore store(File folder) throws Exception { return new ChargeSessionStore(folder, ChargeAutoRecordPolicy.RULES); }
    static ChargeSessionStore.Value value(double v, long mono) { return new ChargeSessionStore.Value(v, mono, 0x72); }
    static ChargeSessionStore.Frame frame(long offset, double soc) {
        long mono = MONO + offset;
        return new ChargeSessionStore.Frame(WALL + offset, mono, value(1, mono), value(soc, mono), value(52, mono), value(-2, mono), value(23, mono));
    }
    /** Samples every 10 s for minutes, gaining points evenly from 50%. */
    static void charge(ChargeSessionStore store, long minutes, int points) throws Exception {
        long steps = minutes * 6;
        for (long i = 0; i <= steps; i++) store.sample(frame(i * 10_000, 50 + Math.floor((double) points * i / steps)));
    }
    static List<String> names(File folder) {
        String[] names = folder.list(); List<String> result = new ArrayList<>(Arrays.asList(names == null ? new String[0] : names));
        Collections.sort(result); return result;
    }
    static String id(File folder) { return names(folder).get(0).substring(0, 36); }

    static void storeIds() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.start("one", STOCK, WALL, MONO);
        check(id(f).charAt(14) == '4' && !store.automatic() && store.labelSource() == null, "A manual recording keeps the plain UUID id");
        store.stop(ChargeSessionStore.Stop.STOPPED);
        File g = folder(); ChargeSessionStore auto = store(g);
        check(auto.startAutomatic("one", STOCK, DETECTED, WALL, MONO), "An automatic recording starts");
        String id = id(g);
        check(id.charAt(14) == 'b' && auto.automatic() && auto.labelSource() == DETECTED, "The id carries how the label was chosen: " + id);
        check(names(g).get(0).matches("[a-f0-9-]{36}\\.(csv|active)")
                && names(g).get(0).matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.(csv|active)"),
                "Older Volt's store and backup patterns still accept the name");
        check(ChargeSessionStore.labelSource(id) == DETECTED && ChargeSessionStore.labelSource(id.substring(0, 14) + "4" + id.substring(15)) == null,
                "Only the marker digit tells automatic from manual");
        check(ChargeSessionStore.labelSource("short") == null && ChargeSessionStore.labelSource(null) == null, "Malformed ids are manual");
    }

    static void storeKeep() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 9, 8);
        check(!store.stop(ChargeSessionStore.Stop.CHARGING_ENDED) && names(f).isEmpty(), "A 9-minute automatic recording is not kept");
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 30, 4);
        check(!store.stop(ChargeSessionStore.Stop.CHARGING_ENDED) && names(f).isEmpty(), "Under 5 points gained is not kept");
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO);
        check(!store.stop(ChargeSessionStore.Stop.STOPPED) && names(f).isEmpty(), "No samples is not kept");
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 10, 5);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED) && names(f).size() == 1, "Exactly 10 minutes and 5 points is kept");
        List<ChargeSessionStore.HistorySession> saved = store.recordings("one");
        check(saved.size() == 1 && saved.get(0).automatic && saved.get(0).labelSource == GUESS, "History knows it started automatically");
        store.start("one", STOCK, WALL, MONO); charge(store, 1, 0);
        check(store.stop(ChargeSessionStore.Stop.STOPPED) && names(f).size() == 2, "A short manual recording is still kept");
        check(!store.recordings("one").get(0).automatic && store.recordings("one").get(0).labelSource == null, "Manual history has no label source");
    }

    static void fill(ChargeSessionStore store, int manual, int automatic) throws Exception {
        for (int i = 0; i < manual + automatic; i++) {
            if (i < manual) store.start("one", STOCK, WALL + i * 100 * MINUTE, MONO); else store.startAutomatic("one", STOCK, CHOSEN, WALL + i * 100 * MINUTE, MONO);
            charge(store, 10, 5); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        }
    }

    static void storeLimit() throws Exception {
        int max = ChargeSessionStore.MAX_SESSIONS;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max, 0);
        check(!store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO) && !store.active() && names(f).size() == max, "All manual at the limit: no automatic recording, nothing deleted");
        File g = folder(); ChargeSessionStore mixed = store(g); fill(mixed, max - 2, 2);
        List<String> before = names(g); String oldestAutomatic = null;
        for (File file : sortedByAge(g)) if (ChargeSessionStore.labelSource(file.getName()) != null) { oldestAutomatic = file.getName(); break; }
        check(mixed.startAutomatic("one", STOCK, CHOSEN, WALL, MONO), "At the limit an automatic one makes room");
        List<String> after = names(g);
        check(!after.contains(oldestAutomatic) && after.size() == max, "Only the oldest automatic recording was replaced");
        int manualAfter = 0; for (String name : after) if (ChargeSessionStore.labelSource(name) == null) manualAfter++;
        check(manualAfter == max - 2, "Every manual recording is still there");
        mixed.stop(ChargeSessionStore.Stop.STOPPED);
        check(before.size() == max, "Fixture filled the store");
        File h = folder(); ChargeSessionStore manualStore = store(h); fill(manualStore, max - 1, 1);
        manualStore.start("one", STOCK, WALL, MONO); charge(manualStore, 10, 5); manualStore.stop(ChargeSessionStore.Stop.STOPPED);
        check(names(h).size() == max, "A manual start keeps today's pruning (it may remove an automatic one)");
    }

    static List<File> sortedByAge(File folder) {
        File[] files = folder.listFiles(); List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName)); return list;
    }

    static void storeRelabel() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 5, 3);
        String before = id(f);
        check(store.relabel(FAST, DETECTED) && store.charger() == FAST && store.labelSource() == DETECTED, "A guess relabels to the detected family");
        String after = id(f);
        check(after.charAt(14) == 'b' && after.substring(0, 14).equals(before.substring(0, 14)) && after.substring(15).equals(before.substring(15)), "Only the marker digit changed");
        check(names(f).size() == 1 && names(f).get(0).endsWith(".active"), "No temporary file is left");
        charge(store, 10, 6);
        check(store.relabel(STOCK, CHOSEN) && id(f).charAt(14) == 'a', "Choosing a charger relabels as chosen");
        store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        ChargeSessionStore.HistorySession saved = store.recordings("one").get(0);
        check(saved.charger == STOCK && saved.labelSource == CHOSEN && saved.points.size() > 60, "The saved header and samples survive the relabel");
        check(!store.relabel(FAST, DETECTED), "Nothing to relabel once stopped");
        store.start("one", STOCK, WALL, MONO);
        check(!store.relabel(FAST, DETECTED) && store.charger() == STOCK, "A manual recording is never relabelled");
        store.stop(ChargeSessionStore.Stop.STOPPED);
    }

    static void storeExport() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", FAST, DETECTED, WALL, MONO); charge(store, 10, 5); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        store.start("one", STOCK, WALL + 1000 * MINUTE, MONO); charge(store, 10, 5); store.stop(ChargeSessionStore.Stop.STOPPED);
        StringWriter out = new StringWriter(); store.exportJson("one", out); String json = out.toString();
        check(json.contains("\"started_automatically\":true,\"label_source\":\"detected\""), "Export says it started automatically and how it was labelled");
        check(json.contains("\"started_automatically\":false,\"label_source\":null"), "Manual recordings export false and null");
        check(json.contains("\"charger_selection\":\"manual\""), "The old top-level field stays for older readers");
    }

    static void storeRecovery() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 3, 1);
        // Process death: a new store over the same folder.
        ChargeSessionStore again = store(f);
        check(names(f).isEmpty() && !again.active(), "A short interrupted automatic recording is not kept");
        again.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(again, 20, 10);
        ChargeSessionStore third = store(f);
        List<ChargeSessionStore.HistorySession> saved = third.recordings("one");
        check(saved.size() == 1 && saved.get(0).stop == ChargeSessionStore.Stop.INTERRUPTED && saved.get(0).automatic, "A long interrupted automatic recording is kept as interrupted");
        third.start("one", STOCK, WALL, MONO); charge(third, 1, 0);
        store(f);
        check(names(f).size() == 2, "An interrupted manual recording is kept as today");
        File stale = new File(f, "00000000-0000-c000-8000-000000000000.active.mapping");
        Files.write(stale.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        store(f);
        check(!stale.exists() && names(f).size() == 2, "A relabel's leftover temporary file is removed at start");
        ChargeSessionStore plain = new ChargeSessionStore(folder());
        check(!plain.startAutomatic("one", STOCK, GUESS, WALL, MONO) && !plain.active(), "Without rules a store never starts an automatic recording");
    }

    static void storeChargeOff() throws Exception {
        ChargeSessionStore store = store(folder());
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 1, 0);
        long wait = ChargeAutoRecordPolicy.chargerOffWaitMs(true), at = MONO + 2 * MINUTE;
        check(store.chargerStopped(WALL + at, at, wait) == ChargeSessionStore.ChargerOff.PAUSED, "Charging stopped: the pause opens");
        check(store.chargerStopped(WALL + at + wait - 1, at + wait - 1, wait) == ChargeSessionStore.ChargerOff.WAITING, "Just under two minutes still waits");
        check(store.chargerStopped(WALL + at + wait, at + wait, wait) == ChargeSessionStore.ChargerOff.ENDED, "Two minutes ends it");
        store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        ChargeSessionStore manual = store(folder());
        manual.start("one", STOCK, WALL, MONO); charge(manual, 1, 0);
        check(manual.chargerStopped(WALL + at, at) == ChargeSessionStore.ChargerOff.PAUSED
                && manual.chargerStopped(WALL + at + wait, at + wait) == ChargeSessionStore.ChargerOff.WAITING, "A manual recording still waits past two minutes");
        manual.stop(ChargeSessionStore.Stop.STOPPED);
    }
}
