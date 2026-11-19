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
            DETECTED = ChargeSessionStore.LabelSource.DETECTED, GUESS = ChargeSessionStore.LabelSource.GUESS,
            SETTING = ChargeSessionStore.LabelSource.SETTING;

    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        start();
        label();
        relabel();
        labelMatrix();
        speed();
        liveReadings();
        chargeOff();
        keep();
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
        storeSettingAudit();
        storeAuditUnderChoice();
        storeLearnsFinalLabel();
        storeOldFiles();
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
        check(l.charger == STOCK && l.source == DETECTED && l.setting == FAST, "Settled detection wins over the charger setting; the setting is kept for audit");
        l = ChargeAutoRecordPolicy.label(STOCK, STOCK, 0, 5);
        check(l.charger == STOCK && l.source == DETECTED && l.setting == STOCK, "Detection agreeing with the setting: detected");
        l = ChargeAutoRecordPolicy.label(null, FAST, 5, 0);
        check(l.charger == FAST && l.source == DETECTED && l.setting == null, "Detection with no setting (Automatic)");
        l = ChargeAutoRecordPolicy.label(FAST, null, 5, 0);
        check(l.charger == FAST && l.source == SETTING && l.setting == FAST, "No detection yet: the setting's label, marked as the setting's, over history");
        l = ChargeAutoRecordPolicy.label(STOCK, null, 0, 5);
        check(l.charger == STOCK && l.source == SETTING, "The setting's label over a guess from history");
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
        check(ChargeAutoRecordPolicy.relabel(SETTING, STOCK, FAST) == FAST, "The setting's label follows detection to the other family");
        check(ChargeAutoRecordPolicy.relabel(SETTING, STOCK, STOCK) == STOCK, "The setting's label that detection confirms becomes detected (same family)");
        check(ChargeAutoRecordPolicy.relabel(SETTING, FAST, null) == null, "No detection: the setting's label stays");
    }

    /**
     * Every combination an automatic recording meets, through the service's steps (label at the start, relabel on each
     * look): setting chosen or Automatic, detection settled at the start or not, settling later on the same or the other
     * family or never, and the owner choosing during the recording.
     */
    static void labelMatrix() {
        ChargeSessionStore.Charger[] families = {null, STOCK, FAST};
        for (ChargeSessionStore.Charger setting : families) for (ChargeSessionStore.Charger atStart : families) for (ChargeSessionStore.Charger later : families) {
            ChargeAutoRecordPolicy.Label l = ChargeAutoRecordPolicy.label(setting, atStart, 0, 0);
            ChargeSessionStore.Charger charger = l.charger; ChargeSessionStore.LabelSource source = l.source;
            check(l.setting == setting, "The setting at the start is kept whatever the label");
            // Detection stays settled on its family until a sustained contrary run forgets it, then may settle on another.
            for (ChargeSessionStore.Charger detected : new ChargeSessionStore.Charger[]{atStart, null, later, later}) {
                ChargeSessionStore.Charger to = ChargeAutoRecordPolicy.relabel(source, charger, detected);
                if (to != null) { charger = to; source = DETECTED; }
            }
            ChargeSessionStore.Charger lastSettled = later != null ? later : atStart;
            String what = "setting " + setting + ", detected at the start " + atStart + ", later " + later + ": " + charger + " " + source;
            if (lastSettled != null) check(charger == lastSettled && source == DETECTED, "The last settled detection labels it, family and source: " + what);
            else if (setting != null) check(charger == setting && source == SETTING, "Never settled: the setting's label, marked as the setting's: " + what);
            else check(charger == STOCK && source == GUESS, "Never settled, no setting: a guess: " + what);
            check(ChargeAutoRecordPolicy.countsAsCharger(source) == (source != GUESS), "Only a guess is not the charger in effect: " + what);
            // The owner's choice during the recording (only while the label is a guess or detected) wins from then on.
            if (ChargeAutoRecordPolicy.choiceAllowed(true, source)) {
                ChargeSessionStore.Charger chosen = charger == STOCK ? FAST : STOCK;
                check(ChargeAutoRecordPolicy.relabel(CHOSEN, chosen, charger) == null && ChargeAutoRecordPolicy.relabel(CHOSEN, chosen, chosen) == null,
                        "Detection never replaces the owner's choice: " + what);
            } else check(source == SETTING, "Only the setting's label is locked among automatic ones here: " + what);
        }
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
    static ChargeSessionStore store(File folder) throws Exception { return new ChargeSessionStore(folder, ChargeAutoRecordPolicy.RULES, MAX, ChargeSessionStore.BUDGET_BYTES); }
    /** The count these tests fill: the store's rules are the same at any count, and twenty keeps them quick. */
    static final int MAX = 20;
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
        auto.startAutomatic("one", STOCK, DETECTED, WALL, MONO); check(auto.active(), "An automatic recording starts");
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

    /** Owner, Nov 19 2026: "auto and manual recordings could be classed the same in that way. rotate away." */
    static void storeLimit() throws Exception {
        int max = MAX;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max, 0);
        String oldestManual = sortedByAge(f).get(0).getName();
        List<String> before = names(f);
        store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO);
        check(store.active() && saved(f).equals(before), "All manual at the limit: an automatic recording starts, and nothing is deleted yet");
        charge(store, 10, 5); check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "It is kept");
        check(saved(f).size() == max && !saved(f).contains(oldestManual), "Once kept, the oldest recording went, though it was one started by hand");
        File g = folder(); ChargeSessionStore mixed = store(g); fill(mixed, 2, max - 2);
        String oldest = sortedByAge(g).get(0).getName();
        check(ChargeSessionStore.labelSource(oldest) == null && names(g).size() == max, "Fixture: the oldest is manual, the rest automatic");
        mixed.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(mixed, 10, 5); mixed.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        check(saved(g).size() == max && !saved(g).contains(oldest), "Automatic and manual alike: the oldest goes, whichever kind it is");
        File h = folder(); ChargeSessionStore manualStore = store(h); fill(manualStore, max - 1, 1);
        String oldestOfAll = sortedByAge(h).get(0).getName();
        manualStore.start("one", STOCK, WALL, MONO);
        check(saved(h).size() == max, "A manual start deletes nothing either");
        charge(manualStore, 10, 5); manualStore.stop(ChargeSessionStore.Stop.STOPPED);
        check(saved(h).size() == max && !saved(h).contains(oldestOfAll), "Once saved, a manual recording makes room the same way");
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
        File bare = folder(); ChargeSessionStore plain = new ChargeSessionStore(bare);
        plain.startAutomatic("one", STOCK, GUESS, WALL, MONO); charge(plain, 1, 0);
        check(plain.stop(ChargeSessionStore.Stop.STOPPED) && saved(bare).size() == 1, "Without rules a store keeps every automatic recording");
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
        check(ChargeAutoRecordPolicy.countsAsCharger(SETTING), "The setting's label is the charger in effect, as the setting is");
        check(!ChargeAutoRecordPolicy.countsAsCharger(GUESS), "A guess does not stand in for the choice or detection");
        String plain = "00000000-0000-4000-8000-000000000000", guess = "00000000-0000-c000-8000-000000000000", detected = "00000000-0000-b000-8000-000000000000";
        check(ChargeSessionStore.learnsCurve(plain) && ChargeSessionStore.learnsCurve(detected) && !ChargeSessionStore.learnsCurve(guess + ".csv"), "The store's learning rule agrees");
        check(ChargeSessionStore.learnsCurve("00000000-0000-d000-1000-000000000000.csv"), "The setting's label learns");
    }

    static int automaticCount(File folder) { int n = 0; for (String name : names(folder)) if (ChargeSessionStore.labelSource(name) != null) n++; return n; }
    static String oldestAutomatic(File folder) {
        for (File file : sortedByAge(folder)) if (ChargeSessionStore.labelSource(file.getName()) != null) return file.getName();
        return null;
    }
    static List<String> saved(File folder) { List<String> out = new ArrayList<>(); for (String n : names(folder)) if (n.endsWith(".csv")) out.add(n); return out; }

    /** Terra #1: nothing saved is ever deleted for a recording that is not kept. */
    static void storeRoomOnlyWhenKept() throws Exception {
        int max = MAX;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 2, 2);
        List<String> before = saved(f);
        check(before.size() == max, "Fixture: a full list");
        store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO);
        check(store.active(), "A full list: an automatic start");
        check(saved(f).equals(before), "Starting deleted nothing: room is made only once it is kept");
        charge(store, 3, 1);
        check(!store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "A short automatic recording is not kept");
        check(saved(f).equals(before), "Discarded: the list is exactly as it was");
        String oldest = sortedByAge(f).get(0).getName();
        store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(store, 10, 5);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "A long enough one is kept");
        List<String> after = saved(f);
        check(after.size() == max && !after.contains(oldest), "Kept: exactly the oldest recording made room");
        List<String> gone = new ArrayList<>(before); gone.removeAll(after);
        check(gone.size() == 1 && gone.get(0).equals(oldest), "Only that one went: " + gone);
        check(automaticCount(f) == 3, "Three automatic recordings now (the oldest was manual)");
        File g = folder(); ChargeSessionStore manual = store(g); fill(manual, max, 0);
        List<String> manualBefore = saved(g);
        manual.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(manual, 3, 1);
        check(!manual.stop(ChargeSessionStore.Stop.CHARGING_ENDED) && saved(g).equals(manualBefore),
                "Every saved recording manual: a short automatic one is discarded and costs nothing");
    }

    /** Terra #1, process death: there is no moment when both the old recording and the new one are gone. */
    static void storeRoomAfterProcessDeath() throws Exception {
        int max = MAX;
        // Death mid-recording, too short to keep: the list is untouched.
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 1, 1);
        List<String> before = saved(f);
        store.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(store, 3, 1);
        store(f);
        check(saved(f).equals(before), "A short recording cut by a process death: the list is unchanged");
        // Death mid-recording, long enough to keep: recovery keeps it and only then the oldest goes.
        String oldest = sortedByAge(f).get(0).getName();
        ChargeSessionStore again = store(f);
        again.startAutomatic("one", STOCK, CHOSEN, WALL, MONO); charge(again, 20, 10);
        check(saved(f).equals(before), "While it runs, nothing has gone");
        store(f);
        List<String> after = saved(f);
        check(after.size() == max && !after.contains(oldest) && automaticCount(f) == 2, "Recovered and kept: the oldest one made room");
        // Death after the new one was saved but before room was made: one file too many; the next start trims the oldest.
        File g = folder(); ChargeSessionStore full = store(g); fill(full, max - 1, 1);
        String old = sortedByAge(g).get(0).getName();
        File newer = new File(g, "11111111-1111-a111-8111-111111111111.csv");
        Files.copy(new File(g, oldestAutomatic(g)).toPath(), newer.toPath());
        newer.setLastModified(System.currentTimeMillis() + 60_000);
        check(saved(g).size() == max + 1, "Fixture: saved, room not yet made");
        store(g);
        List<String> trimmed = saved(g);
        check(trimmed.size() == max && trimmed.contains(newer.getName()) && !trimmed.contains(old), "The next start removes the oldest one, keeps the new one");
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
        int max = MAX;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 1, 1);
        String olderAutomatic = sortedByAge(f).get(0).getName();
        store.startAutomatic("one", STOCK, CHOSEN, WALL + 5000 * MINUTE, MONO); charge(store, 20, 10);
        File journal = active(f); String id = journal.getName().substring(0, 36);
        append(journal, "end,CHARGING_ENDED\n"); // stop() wrote its end line, then the process died before the rename
        ChargeSessionStore after = store(f);
        boolean listed = false; for (ChargeSessionStore.HistorySession h : after.recordings("one")) if (h.automatic && h.charger == STOCK && h.labelSource == CHOSEN && h.points.size() > 60) listed = true;
        check(listed && names(f).contains(id + ".csv"), "Recovered with one end line: still a readable recording");
        int ends = 0; for (String line : Files.readAllLines(new File(f, id + ".csv").toPath())) if (line.startsWith("end,")) ends++;
        check(ends == 1, "Exactly one end line: " + ends);
        check(saved(f).size() == max && !saved(f).contains(olderAutomatic), "It is kept, and the oldest one made room for it, as a finished stop would");
    }

    /** Terra (8be6104f) #2: an automatic file Volt cannot read never costs another recording its place. */
    static void storeUnreadableNeverCostsAnother() throws Exception {
        int max = MAX;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 1, 1);
        String readable = oldestAutomatic(f);
        File broken = new File(f, "22222222-2222-a222-8222-222222222222.csv");
        Files.write(broken.toPath(), "charge-v2,one,STOCK,1,1\nend,STOPPED\nend,INTERRUPTED\n".getBytes(StandardCharsets.UTF_8));
        broken.setLastModified(System.currentTimeMillis() + 60_000);
        check(saved(f).size() == max + 1, "Fixture: one unreadable automatic file over the limit");
        store(f);
        check(!broken.exists() && saved(f).contains(readable) && saved(f).size() == max, "The unreadable file goes, every readable recording stays");
    }

    /** Terra (8be6104f) #3: the recording just saved is never the one trimmed, whatever the other files' dates. */
    static void storeJustSavedNeverTrimmed() throws Exception {
        int max = MAX;
        File f = folder(); ChargeSessionStore store = store(f); fill(store, max - 2, 2);
        // A restore dates recordings by their saved start: here both automatic ones look newer than anything else.
        for (File file : f.listFiles()) if (ChargeSessionStore.labelSource(file.getName()) != null) file.setLastModified(System.currentTimeMillis() + 86_400_000L);
        store.startAutomatic("one", STOCK, CHOSEN, WALL + 9000 * MINUTE, MONO); charge(store, 10, 5);
        String id = active(f).getName().substring(0, 36);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "The kept recording is reported saved");
        check(saved(f).contains(id + ".csv") && saved(f).size() == max && automaticCount(f) == 3, "It is still there; the oldest-dated one made room");
    }

    /** Terra (bd671732): the lock on the charger choice is only for a label the owner chose. */
    static void choiceAllowed() {
        check(ChargeAutoRecordPolicy.choiceAllowed(false, null), "No recording: the choice is open");
        check(!ChargeAutoRecordPolicy.choiceAllowed(true, null), "A manual recording: locked");
        check(!ChargeAutoRecordPolicy.choiceAllowed(true, CHOSEN), "An automatic recording with the chosen charger: locked");
        check(ChargeAutoRecordPolicy.choiceAllowed(true, GUESS), "An automatic recording with a guess: open");
        check(ChargeAutoRecordPolicy.choiceAllowed(true, DETECTED), "An automatic recording with a detected label: open (the owner overrides detection)");
        check(!ChargeAutoRecordPolicy.choiceAllowed(true, SETTING), "An automatic recording with the setting's label: locked, as the chosen setting was before (detection may still replace it)");
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

    /** The setting's provisional label: the id, the audit, detection replacing it with either family, export and a restart. */
    static void storeSettingAudit() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, SETTING, STOCK, WALL, MONO); check(store.active(), "Starts with the setting's label");
        String id = id(f);
        check(id.charAt(14) == 'd' && store.labelSource() == SETTING && store.charger() == STOCK, "The id marks the setting's label: " + id);
        check(id.charAt(19) == '1' && id.charAt(20) == '0', "The audit: setting stock, nothing detected: " + id);
        ChargeSessionStore.LabelAudit audit = store.labelAudit();
        check(audit != null && audit.setting == STOCK && audit.detected == null, "The audit reads back");
        check(names(f).get(0).matches("[a-f0-9-]{36}\\.(csv|active)")
                && names(f).get(0).matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.(csv|active)"),
                "Older Volt's store and backup patterns still accept the name");
        charge(store, 5, 3);
        // The service's look: note detection, then relabel by the policy.
        store.noteDetected(FAST);
        ChargeSessionStore.Charger to = ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), FAST);
        check(to == FAST && store.relabel(to, DETECTED), "Detection on the fast charger replaces the setting's stock label");
        String after = id(f);
        check(store.labelSource() == DETECTED && store.charger() == FAST && after.charAt(14) == 'b' && after.charAt(19) == '1' && after.charAt(20) == '2',
                "Now detected high-speed, the setting at the start still stock: " + after);
        check(after.substring(0, 14).equals(id.substring(0, 14)) && after.substring(15, 19).equals(id.substring(15, 19)) && after.substring(21).equals(id.substring(21)),
                "Only the marker and audit digits changed");
        check(names(f).size() == 1 && names(f).get(0).endsWith(".active"), "No temporary file is left");
        charge(store, 10, 6);
        check(store.stop(ChargeSessionStore.Stop.CHARGING_ENDED), "Kept");
        ChargeSessionStore.HistorySession saved = store.recordings("one").get(0);
        check(saved.charger == FAST && saved.labelSource == DETECTED && saved.labelAudit != null && saved.labelAudit.setting == STOCK && saved.labelAudit.detected == FAST,
                "Saved: detected high-speed, with the stock setting audited");
        // Never settles: the setting's label stays, marked as the setting's; Automatic is audited as such.
        store.startAutomatic("one", FAST, SETTING, FAST, WALL + 1000 * MINUTE, MONO); charge(store, 10, 5);
        check(ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), null) == null, "No detection: nothing to relabel");
        store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        store.startAutomatic("one", STOCK, GUESS, null, WALL + 2000 * MINUTE, MONO); charge(store, 10, 5); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        store.startAutomatic("one", STOCK, DETECTED, null, WALL + 3000 * MINUTE, MONO); charge(store, 10, 5); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        List<ChargeSessionStore.HistorySession> list = store.recordings("one");
        ChargeSessionStore.HistorySession detectedAtStart = list.get(0), guess = list.get(1), kept = list.get(2);
        check(kept.labelSource == SETTING && kept.charger == FAST && kept.labelAudit.setting == FAST && kept.labelAudit.detected == null, "The setting's label, saved as the setting's");
        check(guess.labelSource == GUESS && guess.labelAudit.setting == null && guess.labelAudit.detected == null, "A guess audits Automatic and no detection");
        check(detectedAtStart.labelSource == DETECTED && detectedAtStart.labelAudit.detected == STOCK && detectedAtStart.labelAudit.setting == null, "Detected at the start: its family is audited");
        StringWriter out = new StringWriter(); store.exportJson("one", out); String json = out.toString();
        check(json.contains("\"label_source\":\"setting\",\"label_audit\":{\"charger_setting_at_start\":\"HIGH_SPEED\",\"detected_charger\":null}"), "Export: the setting's label and its audit: " + json.substring(0, Math.min(400, json.length())));
        check(json.contains("\"label_source\":\"detected\",\"label_audit\":{\"charger_setting_at_start\":\"STOCK\",\"detected_charger\":\"HIGH_SPEED\"}"), "Export: detection over the setting, audited");
        check(json.contains("\"label_source\":\"guess\",\"label_audit\":{\"charger_setting_at_start\":\"automatic\",\"detected_charger\":null}"), "Export: Automatic, nothing detected");
        // A process death while the setting's label runs: recovered as the setting's.
        store.startAutomatic("one", STOCK, SETTING, STOCK, WALL + 4000 * MINUTE, MONO); charge(store, 20, 10);
        ChargeSessionStore again = store(f);
        ChargeSessionStore.HistorySession recovered = again.recordings("one").get(0);
        check(recovered.stop == ChargeSessionStore.Stop.INTERRUPTED && recovered.labelSource == SETTING && recovered.automatic && recovered.labelAudit.setting == STOCK,
                "Recovered after a process death with its label and audit");
    }

    /** Under the owner's choice detection changes nothing but the audit; without an audit nothing is renamed. */
    static void storeAuditUnderChoice() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        store.startAutomatic("one", STOCK, GUESS, null, WALL, MONO); charge(store, 10, 5);
        check(store.relabel(FAST, CHOSEN) && store.labelSource() == CHOSEN, "The owner chose high-speed during the guess");
        store.noteDetected(STOCK);
        check(ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), STOCK) == null, "Detection does not change the owner's choice");
        String id = id(f);
        check(store.charger() == FAST && store.labelSource() == CHOSEN && id.charAt(14) == 'a' && id.charAt(19) == '0' && id.charAt(20) == '1',
                "The disagreement is audited: " + id);
        store.noteDetected(STOCK); store.noteDetected(null);
        check(id(f).equals(id), "The same family, or none, renames nothing");
        store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        File g = folder(); ChargeSessionStore old = store(g);
        old.startAutomatic("one", STOCK, GUESS, WALL, MONO);
        String before = id(g);
        old.noteDetected(FAST);
        check(id(g).equals(before) && old.labelAudit() == null, "A recording started without the audit is never given one: " + before);
        old.stop(ChargeSessionStore.Stop.STOPPED);
        File h = folder(); ChargeSessionStore manual = store(h);
        manual.start("one", STOCK, WALL, MONO); String plain = id(h); manual.noteDetected(FAST);
        check(id(h).equals(plain) && manual.labelAudit() == null && ChargeSessionStore.labelAudit(plain) == null, "A manual recording carries no audit and gets none");
        manual.stop(ChargeSessionStore.Stop.STOPPED);
    }

    /** Learning uses the label a recording ends with: detection over the setting counts under the detected family. */
    static void storeLearnsFinalLabel() throws Exception {
        File f = folder(); ChargeSessionStore store = store(f);
        long at = WALL;
        // Setting stock, detection fast (relabelled), twice.
        for (int i = 0; i < 2; i++) {
            store.startAutomatic("one", STOCK, SETTING, STOCK, at += 1000 * MINUTE, MONO); charge(store, 20, 10);
            store.relabel(ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), FAST), DETECTED); charge(store, 40, 20);
            store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        }
        // Setting stock, detection never settles: stays the setting's stock label.
        store.startAutomatic("one", STOCK, SETTING, STOCK, at += 1000 * MINUTE, MONO); charge(store, 60, 30); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        // Setting fast, detection stock (relabelled).
        store.startAutomatic("one", FAST, SETTING, FAST, at += 1000 * MINUTE, MONO); charge(store, 20, 10);
        store.relabel(ChargeAutoRecordPolicy.relabel(store.labelSource(), store.charger(), STOCK), DETECTED); charge(store, 40, 20);
        store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        // Automatic, a guess that never settles: teaches nothing.
        store.startAutomatic("one", FAST, GUESS, null, at += 1000 * MINUTE, MONO); charge(store, 60, 30); store.stop(ChargeSessionStore.Stop.CHARGING_ENDED);
        ChargeSessionStore.Snapshot snapshot = store.snapshot();
        ChargeSessionStore.Curve fast = snapshot.curves.get(ChargeSessionStore.key("one", FAST)), stock = snapshot.curves.get(ChargeSessionStore.key("one", STOCK));
        check(snapshot.sessions == 5, "All five are saved recordings");
        check(fast != null && fast.qualifiedSessions == 2, "The two detected high-speed charges count under high-speed, not the stock setting: " + (fast == null ? 0 : fast.qualifiedSessions));
        check(stock != null && stock.qualifiedSessions == 2, "Stock: the setting's never-settled one and the one detected over a fast setting: " + (stock == null ? 0 : stock.qualifiedSessions));
        StringWriter out = new StringWriter(); store.exportJson("one", out); String json = out.toString();
        int qualified = json.split("\"qualified_for_learning\":true", -1).length - 1;
        check(qualified == 4 && json.contains("\"charger\":\"HIGH_SPEED\",\"started_automatically\":true,\"label_source\":\"detected\""), "Export agrees: four qualify, under their final labels");
    }

    /** Files saved before this change: an automatic id with no audit, and a manual one, read and export as before. */
    static void storeOldFiles() throws Exception {
        File f = folder();
        String journal = "charge-v2,one,HIGH_SPEED," + WALL + "," + MONO + "\n";
        StringBuilder lines = new StringBuilder(journal);
        for (int i = 0; i <= 360; i++) {
            long mono = MONO + i * 10_000L; double soc = 50 + Math.floor(30.0 * i / 360), volts = 52, amps = -2;
            lines.append("s,").append(WALL + i * 10_000L).append(',').append(mono).append(',').append(soc).append(',').append(volts).append(',').append(amps).append(',').append(volts * amps)
                    .append(",23.0,").append(i == 0 ? 1 : 0).append(",test-v1,,,,,,0\n");
        }
        lines.append("end,CHARGING_ENDED\n");
        String chosenOld = "11111111-1111-a111-9111-111111111111", manualOld = "33333333-3333-4333-b333-333333333333";
        Files.write(new File(f, chosenOld + ".csv").toPath(), lines.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(f, manualOld + ".csv").toPath(), lines.toString().replace("HIGH_SPEED", "STOCK").getBytes(StandardCharsets.UTF_8));
        ChargeSessionStore store = store(f);
        List<ChargeSessionStore.HistorySession> list = store.recordings("one");
        check(list.size() == 2, "Both old files read: " + names(f));
        for (ChargeSessionStore.HistorySession h : list) {
            if (h.id.equals(chosenOld)) check(h.automatic && h.labelSource == CHOSEN && h.labelAudit == null && h.charger == FAST, "An old automatic 'chosen' file reads as before, with no audit");
            else check(!h.automatic && h.labelSource == null && h.labelAudit == null && h.charger == STOCK, "An old manual file reads as before");
        }
        check(ChargeSessionStore.labelAudit("11111111-1111-a111-2111-111111111111") != null && ChargeSessionStore.labelAudit("11111111-1111-a111-3111-111111111111") == null
                && ChargeSessionStore.labelAudit("11111111-1111-a111-1311-111111111111") == null && ChargeSessionStore.labelAudit("short") == null, "Only 0-2 digits are an audit");
        ChargeSessionStore.Snapshot snapshot = store.snapshot();
        check(snapshot.curves.get(ChargeSessionStore.key("one", FAST)) != null && snapshot.curves.get(ChargeSessionStore.key("one", STOCK)) != null, "Both still teach their curves");
        StringWriter out = new StringWriter(); store.exportJson("one", out); String json = out.toString();
        check(json.contains("\"label_source\":\"chosen\",\"label_audit\":null") && json.contains("\"label_source\":null,\"label_audit\":null"), "Old files export a null audit");
        check(new File(f, chosenOld + ".csv").exists() && new File(f, manualOld + ".csv").exists(), "Nothing renamed or rewritten");
    }

    /** Terra (8be6104f) #4: any unfinished test that may still write back holds an automatic start. */
    static void testHolds() {
        for (GuidedTest.ReviewState state : GuidedTest.ReviewState.values())
            check(ChargeAutoRecordPolicy.testHolds(state) == (state != GuidedTest.ReviewState.ALREADY_RESTORED), "Test review " + state);
        check(ChargeAutoRecordPolicy.testHolds(GuidedTest.ReviewState.WAITING) && ChargeAutoRecordPolicy.testHolds(GuidedTest.ReviewState.FIRMWARE_CHANGED),
                "Waiting for a reading, or for the owner to confirm the firmware, still holds");
    }
}
