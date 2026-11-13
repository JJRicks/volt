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
        waits();
        labelInEffect();
        storeRoomOnlyWhenKept();
        storeRoomAfterProcessDeath();
        storeGuessTeachesNothing();
        storeConfirmedGuessLearns();
        storeStopCutShort();
        storeUnreadableNeverCostsAnother();
        storeJustSavedNeverTrimmed();
        testHolds();
        choiceAllowed();
        storeChoiceRelabels();
        storeChoiceCutShort();
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
        // Terra (8be6104f) #1: the source follows what is known. Detection confirming a guess makes it DETECTED.
        check(ChargeAutoRecordPolicy.relabel(GUESS, STOCK, STOCK) == STOCK, "A guess that detection confirms becomes detected (same family)");
        check(ChargeAutoRecordPolicy.relabel(DETECTED, STOCK, STOCK) == null, "Detected and still the same family: nothing to change");
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
            boolean clears = ChargeAutoRecordPolicy.clearsHold(live != null, live == null ? -1 : live ? 1 : 0, false);
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
        check(ChargeAutoRecordPolicy.clearsHold(true, 0, false), "A fresh not-charging reading clears the hold");
        check(!ChargeAutoRecordPolicy.clearsHold(true, 1, false), "Still charging keeps the hold");
        check(!ChargeAutoRecordPolicy.clearsHold(false, 0, false), "A stale reading keeps the hold");
        // Terra #2: a pause Volt holds itself (schedule, Charge done by, a schedule test) reads like an unplug but isn't one.
        check(!ChargeAutoRecordPolicy.clearsHold(true, 0, true), "A not-charging reading while Volt holds the current off keeps the hold");
        check(!ChargeAutoRecordPolicy.clearsHold(true, 1, true) && !ChargeAutoRecordPolicy.clearsHold(false, 0, true), "Held off, nothing else clears it either");
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
        List<String> before = names(g); String oldestAutomatic = oldestAutomatic(g);
        check(before.size() == max, "Fixture filled the store");
        check(mixed.startAutomatic("one", STOCK, CHOSEN, WALL, MONO), "At the limit with automatic ones saved: an automatic one starts");
        charge(mixed, 10, 5); mixed.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        List<String> after = names(g);
        check(!after.contains(oldestAutomatic) && after.size() == max, "Once kept, only the oldest automatic recording was replaced");
        int manualAfter = 0; for (String name : after) if (ChargeSessionStore.labelSource(name) == null) manualAfter++;
        check(manualAfter == max - 2, "Every manual recording is still there");
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

    /** Terra #3: an automatic start never arrives during a guided test, its write-back, or a compatibility check. */
    static void waits() {
        check(!ChargeAutoRecordPolicy.waits(false, false, false), "Nothing running: no wait");
        check(ChargeAutoRecordPolicy.waits(true, false, false), "A guided test is running: wait");
        check(ChargeAutoRecordPolicy.waits(false, true, false), "A test's write-back is outstanding: wait");
        check(ChargeAutoRecordPolicy.waits(false, false, true), "A compatibility check is running: wait");
        check(ChargeAutoRecordPolicy.waits(true, true, true), "All of them: wait");
    }

    /** Terra #4 and #5: only a chosen or detected label is a charger Volt acts on; a guess labels the file only. */
    static void labelInEffect() {
        check(ChargeAutoRecordPolicy.countsAsCharger(null), "A manual recording's charger is in effect, as before");
        check(ChargeAutoRecordPolicy.countsAsCharger(CHOSEN) && ChargeAutoRecordPolicy.countsAsCharger(DETECTED), "Chosen and detected labels count");
        check(!ChargeAutoRecordPolicy.countsAsCharger(GUESS), "A guess does not stand in for the choice or detection");
        String plain = "00000000-0000-4000-8000-000000000000", guess = "00000000-0000-c000-8000-000000000000", detected = "00000000-0000-b000-8000-000000000000";
        check(ChargeSessionStore.learnsCurve(plain) && ChargeSessionStore.learnsCurve(detected) && !ChargeSessionStore.learnsCurve(guess + ".csv"), "The store's learning rule agrees");
    }

    static int automaticCount(File folder) { int n = 0; for (String name : names(folder)) if (ChargeSessionStore.labelSource(name) != null) n++; return n; }
    static String oldestAutomatic(File folder) {
        for (File file : sortedByAge(folder)) if (ChargeSessionStore.labelSource(file.getName()) != null) return file.getName();
        return null;
    }
    static List<String> saved(File folder) { List<String> out = new ArrayList<>(); for (String n : names(folder)) if (n.endsWith(".csv")) out.add(n); return out; }

    /** Terra #1: nothing saved is ever deleted for a recording that is not kept. */
    static void storeRoomOnlyWhenKept() throws Exception {
        int max = ChargeSessionStore.MAX_SESSIONS;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 2, 2);
        List<String> before = saved(f);
        check(before.size() == max, "Fixture: a full list");
        check(store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO), "A full list with automatic recordings in it: an automatic start");
        check(saved(f).equals(before), "Starting deleted nothing: room is made only once it is kept");
        charge(store, 3, 1);
        check(!store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "A short automatic recording is not kept");
        check(saved(f).equals(before), "Discarded: the list is exactly as it was");
        String oldest = oldestAutomatic(f);
        store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(store, 10, 5);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "A long enough one is kept");
        List<String> after = saved(f);
        check(after.size() == max && !after.contains(oldest), "Kept: exactly the oldest automatic recording made room");
        List<String> gone = new ArrayList<>(before); gone.removeAll(after);
        check(gone.size() == 1 && gone.get(0).equals(oldest), "Only that one went: " + gone);
        check(automaticCount(f) == 2, "Still two automatic recordings (one old, the new one)");
        File g = folder(); ChargeSessionStore manual = store(g); fill(manual, max, 0);
        List<String> manualBefore = saved(g);
        check(!manual.startAutomatic("one", STOCK, CHOSEN, WALL, MONO) && !manual.active() && saved(g).equals(manualBefore),
                "Every saved recording manual: no start, nothing removed");
    }

    /** Terra #1, process death: there is no moment when both the old recording and the new one are gone. */
    static void storeRoomAfterProcessDeath() throws Exception {
        int max = ChargeSessionStore.MAX_SESSIONS;
        // Death mid-recording, too short to keep: the list is untouched.
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 1, 1);
        List<String> before = saved(f);
        store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(store, 3, 1);
        store(f);
        check(saved(f).equals(before), "A short recording cut by a process death: the list is unchanged");
        // Death mid-recording, long enough to keep: recovery keeps it and only then the oldest automatic one goes.
        String oldest = oldestAutomatic(f);
        ChargeSessionStore again = store(f);
        again.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(again, 20, 10);
        check(saved(f).equals(before), "While it runs, nothing has gone");
        store(f);
        List<String> after = saved(f);
        check(after.size() == max && !after.contains(oldest) && automaticCount(f) == 1, "Recovered and kept: the oldest automatic one made room");
        int manual = 0; for (String n : after) if (ChargeSessionStore.labelSource(n) == null) manual++;
        check(manual == max - 1, "Every manual recording is still there");
        // Death after the new one was saved but before room was made: one file too many; the next start trims an automatic one.
        File g = folder(); ChargeSessionStore full = store(g); fill(full, max - 1, 1);
        String old = oldestAutomatic(g);
        File newer = new File(g, "11111111-1111-a111-8111-111111111111.csv");
        Files.copy(new File(g, old).toPath(), newer.toPath());
        newer.setLastModified(System.currentTimeMillis() + 60_000);
        check(saved(g).size() == max + 1, "Fixture: saved, room not yet made");
        store(g);
        List<String> trimmed = saved(g);
        check(trimmed.size() == max && trimmed.contains(newer.getName()) && !trimmed.contains(old), "The next start removes the older automatic one, keeps the new one");
        int manualLeft = 0; for (String n : trimmed) if (ChargeSessionStore.labelSource(n) == null) manualLeft++;
        check(manualLeft == max - 1, "And no manual one");
    }

    /** Terra #5: a recording that ends with a guessed label teaches no curve; chosen and detected ones learn as before. */
    static void storeGuessTeachesNothing() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", FAST, GUESS, WALL, MONO); charge(store, 60, 30);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "A long guessed recording is kept in the list");
        ChargeSessionStore.Snapshot snapshot = store.snapshot();
        check(snapshot.sessions == 1, "It is counted as a saved recording");
        check(snapshot.curves.get(ChargeSessionStore.key("one", FAST)) == null, "But no high-speed curve is learned from a guess");
        StringWriter out = new StringWriter(); store.exportJson("one", out);
        check(out.toString().contains("\"label_source\":\"guess\"") && out.toString().contains("\"qualified_for_learning\":false"), "Export: a guess, not used for learning");
        check(store.recordings("one").get(0).labelSource == GUESS, "The list still marks it as a guess");
        store.startAutomatic("one", FAST, DETECTED, WALL + 1000 * MINUTE, MONO); charge(store, 60, 30); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        ChargeSessionStore.Curve learned = store.snapshot().curves.get(ChargeSessionStore.key("one", FAST));
        check(learned != null && learned.qualifiedSessions == 1, "A detected label learns as before, and only it counts");
        store.start("one", STOCK, WALL + 2000 * MINUTE, MONO); charge(store, 60, 30); store.stop(ChargeSessionStore.Stop.STOPPED);
        ChargeSessionStore.Curve manual = store.snapshot().curves.get(ChargeSessionStore.key("one", STOCK));
        check(manual != null && manual.qualifiedSessions == 1, "A manual recording learns as before");
    }

    /** Terra (8be6104f) #1: detection confirming a guessed family makes it DETECTED, and then it learns. */
    static void storeConfirmedGuessLearns() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 30, 15);
        // The service's step: relabel when the policy says so (ScooterService.observeAutoCharge).
        ChargeSessionStore.Charger to = ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), STOCK);
        check(to != null && store.relabel(to, DETECTED), "Detection settling on the guessed family relabels the source");
        check(store.labelSource() == DETECTED && store.charger() == STOCK && id(f).charAt(14) == 'b', "Same family, now detected: " + id(f));
        charge(store, 30, 15);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "Kept");
        ChargeSessionStore.Curve learned = store.snapshot().curves.get(ChargeSessionStore.key("one", STOCK));
        check(learned != null && learned.qualifiedSessions == 1, "A confirmed guess learns the stock curve");
        check(store.recordings("one").get(0).labelSource == DETECTED, "Listed as detected");
    }

    static File active(File folder) { for (File file : folder.listFiles()) if (file.getName().endsWith(".active")) return file; return null; }
    static void append(File file, String text) throws Exception { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND); }

    /** Terra (8be6104f) #2: a stop that a process death cut short after its end line: recovery adds no second end line. */
    static void storeStopCutShort() throws Exception {
        int max = ChargeSessionStore.MAX_SESSIONS;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 1, 1);
        String olderAutomatic = oldestAutomatic(f);
        store.startAutomatic("one", STOCK, CHOSEN, WALL + 5000 * MINUTE, MONO); charge(store, 20, 10);
        File journal = active(f); String id = journal.getName().substring(0, 36);
        append(journal, "end,CHARGING_ENDED\n"); // stop() wrote its end line, then the process died before the rename
        ChargeSessionStore after = store(f);
        boolean listed = false; for (ChargeSessionStore.HistorySession h : after.recordings("one")) if (h.automatic && h.charger == STOCK && h.labelSource == CHOSEN && h.points.size() > 60) listed = true;
        check(listed && names(f).contains(id + ".csv"), "Recovered with one end line: still a readable recording");
        int ends = 0; for (String line : Files.readAllLines(new File(f, id + ".csv").toPath())) if (line.startsWith("end,")) ends++;
        check(ends == 1, "Exactly one end line: " + ends);
        check(saved(f).size() == max && !saved(f).contains(olderAutomatic), "It is kept, and the older automatic one made room for it, as a finished stop would");
    }

    /** Terra (8be6104f) #2: an automatic file Volt cannot read never costs another recording its place. */
    static void storeUnreadableNeverCostsAnother() throws Exception {
        int max = ChargeSessionStore.MAX_SESSIONS;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 1, 1);
        String readable = oldestAutomatic(f);
        File broken = new File(f, "22222222-2222-a222-8222-222222222222.csv");
        Files.write(broken.toPath(), "charge-v2,one,STOCK,1,1\nend,STOPPED\nend,INTERRUPTED\n".getBytes(StandardCharsets.UTF_8));
        broken.setLastModified(System.currentTimeMillis() + 60_000);
        check(saved(f).size() == max + 1, "Fixture: one unreadable automatic file over the limit");
        store(f);
        check(!broken.exists() && saved(f).contains(readable) && saved(f).size() == max, "The unreadable file goes, the readable automatic recording stays");
    }

    /** Terra (8be6104f) #3: the recording just saved is never the one trimmed, whatever the other files' dates. */
    static void storeJustSavedNeverTrimmed() throws Exception {
        int max = ChargeSessionStore.MAX_SESSIONS;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 2, 2);
        // A restore dates recordings by their saved start: here both automatic ones look newer than anything else.
        for (File file : f.listFiles()) if (ChargeSessionStore.labelSource(file.getName()) != null) file.setLastModified(System.currentTimeMillis() + 86_400_000L);
        store.startAutomatic("one", STOCK, CHOSEN, WALL + 9000 * MINUTE, MONO); charge(store, 10, 5);
        String id = active(f).getName().substring(0, 36);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "The kept recording is reported saved");
        check(saved(f).contains(id + ".csv") && saved(f).size() == max && automaticCount(f) == 2, "It is still there; one older-dated automatic one made room");
    }

    /** Terra (bd671732): the lock on the charger choice is only for a label the owner chose. */
    static void choiceAllowed() {
        check(ChargeAutoRecordPolicy.choiceAllowed(false, null), "No recording: the choice is open");
        check(!ChargeAutoRecordPolicy.choiceAllowed(true, null), "A manual recording: locked");
        check(!ChargeAutoRecordPolicy.choiceAllowed(true, CHOSEN), "An automatic recording with the chosen charger: locked");
        check(ChargeAutoRecordPolicy.choiceAllowed(true, GUESS), "An automatic recording with a guess: open");
        check(ChargeAutoRecordPolicy.choiceAllowed(true, DETECTED), "An automatic recording with a detected label: open (the owner overrides detection)");
    }

    /** The owner's choice relabels a guess or a detected label CHOSEN, it learns that charger, and detection never changes it again. */
    static void storeChoiceRelabels() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 30, 15);
        check(ChargeAutoRecordPolicy.choiceAllowed(true, store.labelSource()) && store.relabel(FAST, CHOSEN), "The owner's choice relabels a guess");
        check(store.labelSource() == CHOSEN && store.charger() == FAST && id(f).charAt(14) == 'a', "Chosen high-speed: " + id(f));
        check(!ChargeAutoRecordPolicy.choiceAllowed(true, store.labelSource()), "Once chosen, the choice is locked again");
        check(ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), STOCK) == null, "Detection never overrides the owner's choice");
        charge(store, 30, 15);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "Kept");
        ChargeSessionStore.Curve learned = store.snapshot().curves.get(ChargeSessionStore.key("one", FAST));
        check(learned != null && learned.qualifiedSessions == 1, "The chosen recording teaches the high-speed curve");
        check(store.snapshot().curves.get(ChargeSessionStore.key("one", STOCK)) == null, "and not the guessed stock one");
        File g = folder(); ChargeSessionStore detected = store(g);
        detected.startAutomatic("one", STOCK, DETECTED, WALL, MONO); charge(detected, 30, 15);
        check(detected.relabel(STOCK, CHOSEN) && detected.labelSource() == CHOSEN && detected.charger() == STOCK && id(g).charAt(14) == 'a',
                "The owner confirming a detected family makes it chosen: " + id(g));
    }

    /** A process end between the header rewrite and the rename: one readable recording naming the owner's charger under its old source, which teaches nothing for a guess. */
    static void storeChoiceCutShort() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(store, 30, 15);
        File before = active(f);
        check(store.relabel(FAST, CHOSEN), "Relabelled");
        // The header is rewritten; put the old name back, as if the process ended before the rename.
        check(active(f).renameTo(before), "Name put back");
        ChargeSessionStore recovered = store(f);
        List<ChargeSessionStore.HistorySession> list = recovered.recordings("one");
        check(names(f).size() == 1 && list.size() == 1, "One recording: " + names(f));
        check(list.get(0).charger == FAST && list.get(0).labelSource == GUESS, "The owner's charger under the guess's name: " + list.get(0).charger + " " + list.get(0).labelSource);
        check(!ChargeSessionStore.learnsCurve(list.get(0).id) && recovered.snapshot().curves.get(ChargeSessionStore.key("one", FAST)) == null, "A recording still named a guess teaches nothing");
    }

    /** Terra (8be6104f) #4: any unfinished test that may still write back holds an automatic start. */
    static void testHolds() {
        for (GuidedTest.ReviewState state : GuidedTest.ReviewState.values())
            check(ChargeAutoRecordPolicy.testHolds(state) == (state != GuidedTest.ReviewState.ALREADY_RESTORED), "Test review " + state);
        check(ChargeAutoRecordPolicy.testHolds(GuidedTest.ReviewState.WAITING) && ChargeAutoRecordPolicy.testHolds(GuidedTest.ReviewState.FIRMWARE_CHANGED),
                "Waiting for a reading, or for the owner to confirm the firmware, still holds");
    }
}
