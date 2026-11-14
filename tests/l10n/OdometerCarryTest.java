package com.jjricks.volt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lifetime odometer record (OdometerCarry) and Home's carry-over cue (OdometerAttention): made-up readings only, no
 * Android (tests/l10n/odometer-carry.sources; BackupTestStore from tests/run.sh).
 */
public final class OdometerCarryTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MIN = 60_000L;
    private static final String P = "a0000000-0000-4000-8000-00000000000a", Q = "b0000000-0000-4000-8000-00000000000b";

    public static void main(String[] args) throws Exception {
        singleLowNeverCarries();
        jitterAndWraps();
        carryAmount();
        multipleResets();
        undo();
        widthChange();
        attention();
        storage();
        simulatorNeverWrites();
        remindersOff();
        setUpAgain();
        merge();
        backups();
        rawConsumers();
        System.out.println("OdometerCarryTest: " + checks + " checks passed");
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    /** A record that last read {@code km} on a 32-bit field. */
    private static OdometerCarry at(double km) {
        OdometerCarry c = new OdometerCarry();
        c.observe(km, 32, 1, WALL);
        return c;
    }

    // 1. A single low or zero reading never carries over; two consecutive ones confirm it.
    private static void singleLowNeverCarries() {
        OdometerCarry c = at(1234.5);
        check(c.observe(0, 32, 2, WALL + 1) == OdometerCarry.Step.PENDING, "one zero waits");
        check(c.carries.isEmpty() && near(c.lastRawKm, 1234.5), "one zero changes nothing saved");
        check(near(c.displayKm(0), 1234.5), "while a drop is unconfirmed, the shown odometer holds the last value");
        check(c.observe(1234.6, 32, 3, WALL + 2) == OdometerCarry.Step.FORWARD && c.carries.isEmpty() && !c.dropPending(),
                "a normal reading after one glitch: nothing carried, the drop forgotten");
        check(c.observe(0, 32, 4, WALL + 3) == OdometerCarry.Step.PENDING, "a later zero waits again");
        check(c.observe(0, 32, 4, WALL + 4) == OdometerCarry.Step.PENDING && c.carries.isEmpty(), "the same frame twice is one reading");
        check(c.observe(1234.5, 32, 5, WALL + 5) == OdometerCarry.Step.JITTER && c.carries.isEmpty() && !c.dropPending(),
                "a reading within the jitter breaks the run");
        check(c.observe(0, 32, 6, WALL + 6) == OdometerCarry.Step.PENDING, "first low reading");
        check(c.observe(0.1, 32, 7, WALL + 7) == OdometerCarry.Step.CARRIED && c.carries.size() == 1, "two consecutive low readings confirm");
        check(near(c.carriedKm(), 1234.6) && near(c.displayKm(0.1), 1234.7), "it picks up from the last number it saw");
        check(c.observe(Double.NaN, 32, 8, WALL + 8) == OdometerCarry.Step.IGNORED && c.observe(-1, 32, 9, WALL + 9) == OdometerCarry.Step.IGNORED,
                "invalid readings change nothing");
    }

    // 2. Jitter of at most 0.2 km and real wraps never carry over.
    private static void jitterAndWraps() {
        OdometerCarry c = at(500.0);
        for (int i = 0; i < 4; i++) check(c.observe(499.8, 32, 2 + i, WALL + i) == OdometerCarry.Step.JITTER, "0.2 km back is jitter");
        check(c.carries.isEmpty() && near(c.lastRawKm, 500.0), "jitter carries nothing over and keeps the baseline");
        // An 8-bit field (v0, whole km) wraps at 256.
        OdometerCarry w = new OdometerCarry();
        w.observe(250, 8, 1, WALL);
        check(w.observe(2, 8, 2, WALL + 1) == OdometerCarry.Step.PENDING && near(w.displayKm(2), 250), "a wrap waits for a second reading too, holding the total");
        check(w.observe(3, 8, 3, WALL + 2) == OdometerCarry.Step.WRAP, "two readings confirm the wrap");
        check(w.carries.isEmpty() && near(w.carriedKm(), 0) && near(w.wrappedKm, 256), "a wrap is no carry-over: one turn of the field is counted");
        check(near(w.displayKm(3), 259) && w.displayKm(3) > 250, "the shown total never drops at a wrap");
        check(!w.unacknowledged(), "a wrap asks for no attention");
        // A 16-bit field (v1 tenths) wraps at 6553.6.
        OdometerCarry s = new OdometerCarry();
        s.observe(6500.0, 16, 1, WALL);
        s.observe(10.0, 16, 2, WALL + 1); s.observe(10.4, 16, 3, WALL + 2);
        check(s.carries.isEmpty() && near(s.wrappedKm, 6553.6) && near(s.displayKm(10.4), 6564.0), "a 16-bit wrap keeps counting");
        // The same classification as maintenance (one shared helper).
        check(MaintenanceDistance.step(250, 8, 2) == MaintenanceDistance.Kind.WRAP && MaintenanceDistance.step(250, 8, 240) == MaintenanceDistance.Kind.RESET
                && MaintenanceDistance.step(500, 32, 499.8) == MaintenanceDistance.Kind.IGNORED && MaintenanceDistance.step(500, 32, 499.7) == MaintenanceDistance.Kind.RESET,
                "MaintenanceDistance.step: wrap, reset, jitter");
        // 250 to 240 on a 1-byte field is a reset, not a wrap.
        OdometerCarry r = new OdometerCarry();
        r.observe(250, 8, 1, WALL); r.observe(240, 8, 2, WALL + 1); r.observe(241, 8, 3, WALL + 2);
        check(r.carries.size() == 1 && near(r.carriedKm(), 10) && near(r.wrappedKm, 0), "a drop outside the wrap window is a reset");
    }

    // 3. The carry-over is last saved minus the first reading after the drop.
    private static void carryAmount() {
        OdometerCarry zero = at(1234.5);
        zero.observe(0, 32, 2, WALL + MIN); zero.observe(0, 32, 3, WALL + 2 * MIN);
        check(near(zero.carriedKm(), 1234.5) && near(zero.displayKm(0), 1234.5), "a reset to 0 carries the full last value");
        check(zero.last().wall == WALL + 2 * MIN && near(zero.last().fromKm, 1234.5) && near(zero.last().toKm, 0), "the carry-over records when and what");
        OdometerCarry used = at(1234.5);
        used.observe(500, 32, 2, WALL + MIN); used.observe(500.3, 32, 3, WALL + 2 * MIN);
        check(near(used.carriedKm(), 734.5) && near(used.displayKm(500.3), 1234.8), "a used part at 500: carries only what keeps the total going, never more");
        check(used.displayKm(500.3) < 1234.5 + 500.3, "a used part's own mileage is never added");
    }

    // 4. Multiple resets accumulate.
    private static void multipleResets() {
        OdometerCarry c = at(1000);
        c.observe(0, 32, 2, WALL + 1); c.observe(0, 32, 3, WALL + 2);
        c.observe(300, 32, 4, WALL + 3);
        c.observe(0, 32, 5, WALL + 4); c.observe(0.5, 32, 6, WALL + 5);
        check(c.carries.size() == 2 && near(c.carriedKm(), 1300) && near(c.displayKm(0.5), 1300.5), "two resets add up");
        // The cap folds the oldest together and keeps the total.
        OdometerCarry many = at(10);
        long frame = 2;
        for (int i = 0; i < OdometerCarry.MAX_CARRIES + 5; i++) {
            many.observe(20, 32, frame++, WALL + frame); many.observe(0, 32, frame++, WALL + frame); many.observe(0, 32, frame++, WALL + frame);
        }
        check(many.carries.size() == OdometerCarry.MAX_CARRIES && near(many.carriedKm(), 20 * (OdometerCarry.MAX_CARRIES + 5)), "many resets: capped, total kept");
    }

    // 5. Undo restores exactly the earlier total.
    private static void undo() {
        OdometerCarry c = at(1000);
        c.observe(0, 32, 2, WALL + 1); c.observe(0, 32, 3, WALL + 2);
        c.observe(220.4, 32, 4, WALL + 3);
        double before = c.displayKm(220.4);
        c.observe(0, 32, 5, WALL + 4); c.observe(0, 32, 6, WALL + 5);
        check(near(c.displayKm(0), before), "the second reset carried over");
        check(c.undo() && c.carries.size() == 1 && near(c.carriedKm(), 1000), "Undo removes only the latest carry-over");
        check(near(c.displayKm(0), 1000), "after Undo the total is the earlier carried distance plus the raw reading");
        check(c.undo() && near(c.displayKm(0), 0) && !c.undo(), "Undo again removes the first; then nothing is left");
    }

    private static void widthChange() {
        OdometerCarry c = at(1234.5);
        c.observe(0, 32, 2, WALL + 1); c.observe(0, 32, 3, WALL + 2);
        OdometerCarry w = new OdometerCarry();
        w.observe(250, 8, 1, WALL); w.observe(2, 8, 2, WALL + 1); w.observe(3, 8, 3, WALL + 2);
        check(w.observe(259.0, 16, 4, WALL + 3) == OdometerCarry.Step.REBASELINE && w.carries.isEmpty() && near(w.wrappedKm, 0) && near(w.displayKm(259), 259),
                "a field-width change re-baselines without a carry-over; the new field's own value stands");
        check(c.observe(10, 16, 4, WALL + 3) == OdometerCarry.Step.REBASELINE && near(c.carriedKm(), 1234.5), "a width change keeps what was carried over");
    }

    // The cue: the reduced-motion choice, never while moving or riding, re-armed by a new carry-over.
    private static void attention() {
        check(OdometerAttention.style(true, true, true, false, false) == OdometerAttention.Style.PULSE, "unacknowledged, animations on: pulse");
        check(OdometerAttention.style(true, true, false, false, false) == OdometerAttention.Style.STILL, "animations off: still accent and dot");
        check(OdometerAttention.style(true, true, true, false, true) == OdometerAttention.Style.NONE && OdometerAttention.style(true, true, false, false, true) == OdometerAttention.Style.NONE,
                "never while the scooter reports speed");
        check(OdometerAttention.style(true, true, true, true, false) == OdometerAttention.Style.NONE && OdometerAttention.style(true, true, false, true, false) == OdometerAttention.Style.NONE,
                "never in riding mode or off screen");
        check(OdometerAttention.style(false, true, true, false, false) == OdometerAttention.Style.NONE, "acknowledged: plain");
        // No cue where a tap won't open the explanation: not the pulse, the still accent and dot, or the spoken hint.
        for (boolean motion : new boolean[]{true, false})
            check(OdometerAttention.style(true, false, motion, false, false) == OdometerAttention.Style.NONE, "a cell whose tap does something else gets no cue");
        String home = read("src/com/jjricks/volt/DashboardScreen.java");
        String mark = between(home, "private void markOdometer(", "void pulseOdometer(");
        check(mark.contains("boolean opens = carried && target != null;")
                        && mark.contains("OdometerAttention.style(record != null && record.unacknowledged(), opens,")
                        && mark.contains("if (target == big && big.hasOnClickListeners() && big != odometerTarget) target = null;")
                        && mark.contains("return holder.hasOnClickListeners() && holder != odometerTarget ? null : holder;"),
                "Home cues only a number whose tap opens the explanation; a cell or number with its own tap keeps it, uncued");
        String sheet = between(home, "private void showOdometerCarry() {", "private LinearLayout carryRow(");
        check(sheet.contains("carryRow(T.dashboardOdometerCarryScooter(), shown(own).toString())") && sheet.contains("carryRow(T.dashboardOdometerCarryShown(), shown(total).toString())")
                        && sheet.contains("service.displayReadings().get(MaintenanceView.ODOMETER)") && sheet.contains("service.dashboardReadings().get(MaintenanceView.ODOMETER)"),
                "the sheet's two rows use Home's own formatter on Home's own readings, so they match the tile exactly");
        check(mark.indexOf("dashboardOdometerCarrySpoken") > mark.indexOf("if (style == OdometerAttention.Style.NONE)"),
                "the spoken hint comes only with a cue");
        // Gentle: two pulses then a pause, well under three a second.
        int peaks = 0; double previous = 0, max = 0; boolean rising = true;
        for (long t = 0; t < OdometerAttention.CYCLE_MS; t += 10) {
            double v = OdometerAttention.pulse(t);
            check(v >= 0 && v <= 1, "pulse strength in 0..1");
            if (rising && v < previous) { peaks++; rising = false; } else if (v > previous) rising = true;
            previous = v; max = Math.max(max, v);
        }
        check(peaks == 2 && near(max, 1), "two pulses a cycle, each reaching full strength: " + peaks);
        check(2.0 * 1000 / OdometerAttention.CYCLE_MS < 3 && 1000.0 / OdometerAttention.PULSE_MS < 3, "under three flashes a second");
        check(OdometerAttention.pulse(2 * OdometerAttention.PULSE_MS + 100) == 0 && OdometerAttention.pulse(OdometerAttention.CYCLE_MS - 10) == 0, "then a pause");
        check(near(OdometerAttention.pulse(OdometerAttention.PULSE_MS / 2), 1) && near(OdometerAttention.pulse(OdometerAttention.CYCLE_MS + OdometerAttention.PULSE_MS / 2), 1), "it repeats");
        OdometerCarry c = at(1000);
        c.observe(0, 32, 2, WALL + 1); c.observe(0, 32, 3, WALL + 2);
        check(c.unacknowledged(), "a carry-over asks for attention");
        check(c.acknowledge() && !c.unacknowledged() && !c.acknowledge(), "opening the explanation acknowledges it");
        c.observe(300, 32, 4, WALL + 3); c.observe(0, 32, 5, WALL + 4); c.observe(0, 32, 6, WALL + 5);
        check(c.unacknowledged() && c.carries.get(0).seen && !c.carries.get(1).seen, "a new carry-over re-arms the cue");
        OdometerCarry back = OdometerCarry.decode(OdometerCarry.encode(c));
        check(back.carries.get(0).seen && !back.carries.get(1).seen && back.unacknowledged(), "acknowledgement is saved per carry-over");
    }

    /** Preferences as a map. */
    private static final class Prefs implements MaintenanceKeeper.Storage {
        final Map<String, String> values = new HashMap<>();
        int writes;
        @Override public String read(String key) { return values.get(key); }
        @Override public void write(String key, String value) { writes++; values.put(key, value); }
        @Override public void remove(String key) { writes++; values.remove(key); }
    }

    private static void storage() {
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        check(keeper.observe(P, 1234.5, 32, 1, WALL) == OdometerCarry.Step.FIRST && prefs.values.containsKey(OdometerCarry.key(P)), "the first reading is written");
        int writes = prefs.writes;
        keeper.observe(P, 1234.55, 32, 2, WALL + 1000);
        check(prefs.writes == writes && keeper.unwritten(P) && near(keeper.load(P).lastRawKm, 1234.55), "under 0.1 km: counted in memory, not written yet");
        keeper.observe(P, 1234.6, 32, 3, WALL + 2000);
        check(prefs.writes == writes + 1 && !keeper.unwritten(P), "0.1 km more: written");
        keeper.observe(P, 1234.6, 32, 4, WALL + 2000 + OdometerCarry.FRESHNESS_WRITE_MS);
        check(prefs.writes == writes + 2, "a few minutes newer: written, so a restore can tell which record is newer");
        keeper.observe(P, 1234.65, 32, 5, WALL + 3000 + OdometerCarry.FRESHNESS_WRITE_MS);
        keeper.flush(P);
        check(near(OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P))).lastRawKm, 1234.65) && !keeper.unwritten(P), "the connection ended: flushed");
        // A drop: the pending first reading lives in memory between readings.
        keeper.observe(P, 0, 32, 6, WALL + 4000 + OdometerCarry.FRESHNESS_WRITE_MS);
        check(OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P))).carries.isEmpty(), "one low reading writes no carry-over");
        check(keeper.observe(P, 0, 32, 7, WALL + 5000 + OdometerCarry.FRESHNESS_WRITE_MS) == OdometerCarry.Step.CARRIED, "the second confirms");
        OdometerCarry saved = OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P)));
        check(saved.carries.size() == 1 && near(saved.carriedKm(), 1234.65) && saved.unacknowledged(), "the carry-over is written at once");
        check(keeper.acknowledge(P) && !OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P))).unacknowledged(), "acknowledged and written");
        check(keeper.undo(P) && OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P))).carries.isEmpty() && !keeper.undo(P), "Undo written");
        // A restore replaced the record meanwhile: memory made on the old text is dropped.
        keeper.observe(P, 0.05, 32, 8, WALL + 6000 + OdometerCarry.FRESHNESS_WRITE_MS);
        OdometerCarry restored = at(42);
        prefs.values.put(OdometerCarry.key(P), OdometerCarry.encode(restored));
        check(near(keeper.load(P).lastRawKm, 42), "a restored record wins over unwritten memory");
        keeper.forget(P, "");
        check(!prefs.values.containsKey(OdometerCarry.key(P)), "Forget removes the record");
        check(OdometerCarry.read("not json") == null && OdometerCarry.decode("{").carries.isEmpty() && !OdometerCarry.decode(null).hasBaseline(), "unreadable text reads as no record");
    }

    // 8. The simulator never writes the record.
    private static void simulatorNeverWrites() {
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        String sim = MaintenanceKeeper.simulationScope(P);
        check(keeper.observe(sim, 248.36, 32, 1, WALL) == null && keeper.observe(sim, 0, 32, 2, WALL) == null && keeper.observe(sim, 0, 32, 3, WALL) == null,
                "simulation scopes are refused");
        check(!keeper.acknowledge(sim) && !keeper.undo(sim) && !keeper.adopt(sim, "SERIAL") && prefs.writes == 0 && prefs.values.isEmpty(), "nothing written for the simulator");
        String service = read("src/com/jjricks/volt/ScooterService.java");
        String feed = between(service, "private void observeMaintenance() {", "/** One maintenance check");
        int guard = feed.indexOf("if (!simulating() && odometerRecords != null) {"), observe = feed.indexOf("odometerRecords.observe(scope, odometer.numericValue, bits, odometer.updatedMs,");
        check(guard > 0 && observe > guard && feed.indexOf("odometerRecords.observe(") == observe, "the service feeds the record only when not simulating, with the raw reading");
        check(between(service, "public OdometerCarry odometerRecord() {", "}").contains("simulating()"), "while simulating there is no record to show");
    }

    // 9. With maintenance reminders off, the record still updates.
    private static void remindersOff() {
        Prefs prefs = new Prefs();
        MaintenanceKeeper maintenance = new MaintenanceKeeper(prefs);
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        check(!maintenance.load(P).on, "reminders are off");
        check(maintenance.observe(P, 1234.5, 32, WALL) == null, "maintenance ignores the reading");
        check(keeper.observe(P, 1234.5, 32, 1, WALL) == OdometerCarry.Step.FIRST && near(keeper.load(P).lastRawKm, 1234.5), "the record still takes it");
        maintenance.observe(P, 0, 32, WALL + 1); keeper.observe(P, 0, 32, 2, WALL + 1);
        maintenance.observe(P, 0, 32, WALL + 2); keeper.observe(P, 0, 32, 3, WALL + 2);
        check(near(keeper.load(P).carriedKm(), 1234.5) && !prefs.values.containsKey(MaintenanceStore.key(P)), "a reset carries over with reminders off");
        String service = read("src/com/jjricks/volt/ScooterService.java");
        String feed = between(service, "private void observeMaintenance() {", "/** One maintenance check");
        check(feed.indexOf("odometerRecords.observe(") < feed.indexOf("maintenance.observe(scope,"), "the record is fed before (and apart from) the reminders");
    }

    // The owner's flow: Release this scooter (the profile goes), then set the same scooter up again under a new profile.
    private static void setUpAgain() {
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        keeper.observe(P, 1234.5, 32, 1, WALL);
        keeper.forget(P, "N2242SERIAL");
        check(!prefs.values.containsKey(OdometerCarry.key(P)) && prefs.values.containsKey(OdometerCarry.keptKey("N2242SERIAL")), "a forgotten scooter's record is kept by serial");
        check(!keeper.adopt(Q, "OTHERSERIAL") && prefs.values.containsKey(OdometerCarry.keptKey("N2242SERIAL")), "another scooter's serial adopts nothing");
        keeper.observe(Q, 0, 32, 2, WALL + MIN);
        check(keeper.adopt(Q, "N2242SERIAL") && !prefs.values.containsKey(OdometerCarry.keptKey("N2242SERIAL")), "the same scooter's new profile adopts it");
        keeper.observe(Q, 0, 32, 3, WALL + 2 * MIN);
        check(keeper.observe(Q, 0, 32, 4, WALL + 3 * MIN) == OdometerCarry.Step.CARRIED && near(keeper.load(Q).carriedKm(), 1234.5),
                "two zero readings on the new profile carry the old odometer over");
        check(!keeper.adopt(Q, "N2242SERIAL"), "nothing left to adopt");
        Prefs none = new Prefs();
        OdometerCarry.Keeper unread = new OdometerCarry.Keeper(none);
        unread.forget(P, "N2242SERIAL");
        check(none.values.isEmpty(), "a profile that never read the odometer keeps nothing");
        check(BackupRegistry.rule("app_settings", OdometerCarry.keptKey("N2242SERIAL")).excluded(), "the kept record stays on this phone");
    }

    // 7. The merge rule: newer wins, this phone wins ties, unreadable text never wins, seen stays seen.
    private static void merge() {
        OdometerCarry older = at(1000), newer = new OdometerCarry();
        newer.observe(1100, 32, 1, WALL + MIN);
        String o = OdometerCarry.encode(older), n = OdometerCarry.encode(newer);
        check(n.equals(OdometerCarry.merged(o, n)) && n.equals(OdometerCarry.merged(n, o)), "the newer last reading wins, from either side");
        OdometerCarry tie = at(900);
        String t = OdometerCarry.encode(tie);
        check(o.equals(OdometerCarry.merged(o, t)) && t.equals(OdometerCarry.merged(t, o)), "a tie keeps this phone's");
        check(o.equals(OdometerCarry.merged(o, null)) && o.equals(OdometerCarry.merged(null, o)) && OdometerCarry.merged(null, null) == null, "one side only: that one");
        check(o.equals(OdometerCarry.merged(o, "garbage")) && o.equals(OdometerCarry.merged("garbage", o)), "unreadable text never wins");
        // The same carry-over seen on one side stays seen whichever record wins.
        OdometerCarry here = at(1000);
        here.observe(0, 32, 2, WALL + 1); here.observe(0, 32, 3, WALL + 2);
        OdometerCarry seen = here.copy(); seen.acknowledge();
        here.observe(5, 32, 4, WALL + 10 * MIN);
        OdometerCarry kept = OdometerCarry.decode(OdometerCarry.merged(OdometerCarry.encode(here), OdometerCarry.encode(seen)));
        check(near(kept.lastRawKm, 5) && !kept.unacknowledged(), "this phone's newer record, the backup's acknowledgement");
    }

    // 7. Backups: the round trip, the merge rule and the same profile only, through the real BackupArchive.
    private static void backups() throws Exception {
        File root = Files.createTempDirectory("odometer-carry-backup").toFile();
        try {
            Set<String> scooters = Collections.singleton(BackupRegistry.SCOOTERS);
            String key = OdometerCarry.key(P);
            check(BackupRegistry.SCOOTERS.equals(BackupRegistry.category("app_settings", key)) && BackupRegistry.rule("app_settings", key).perProfile
                    && P.equals(BackupRegistry.rule("app_settings", key).profile(key)), "the record: Scooters & setup keys, per profile");
            OdometerCarry carried = at(1234.5);
            carried.observe(0, 32, 2, WALL + MIN); carried.observe(0, 32, 3, WALL + 2 * MIN);
            carried.acknowledge();
            String record = OdometerCarry.encode(carried);
            byte[] backup = store(root, "source", P, "SERIAL-S", record).export(scooters);

            // A fresh phone: the profile and its record arrive together, acknowledgement included.
            BackupTestStore fresh = new BackupTestStore(new File(root, "fresh"));
            fresh.restore(backup, scooters, false, null);
            OdometerCarry back = OdometerCarry.decode((String) fresh.preferences().get("app_settings").get(key));
            check(near(back.carriedKm(), 1234.5) && !back.unacknowledged() && near(back.lastRawKm, 0), "round trip: carried distance and acknowledgement");

            // An older backup (before the reset) onto a phone that has none: the record arrives, so a later drop carries over.
            byte[] before = store(root, "before", P, "SERIAL-S", OdometerCarry.encode(at(1234.5))).export(scooters);
            BackupTestStore none = store(root, "none", P, "SERIAL-S", null);
            none.restore(before, scooters, false, null);
            Prefs prefs = new Prefs();
            prefs.values.put(key, (String) none.preferences().get("app_settings").get(key));
            OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
            keeper.observe(P, 0, 32, 10, WALL + 3 * MIN); keeper.observe(P, 0, 32, 11, WALL + 4 * MIN);
            check(near(keeper.load(P).carriedKm(), 1234.5), "an older backup on a phone with none: a later reset still carries over");

            // Same profile: newer wins, this phone wins ties.
            OdometerCarry newer = at(2000); newer.lastWall = WALL + 60 * MIN;
            check(merged(root, "newer-here", OdometerCarry.encode(newer), record).equals(OdometerCarry.encode(newer)), "this phone's newer record stays");
            OdometerCarry older = at(10); older.lastWall = WALL;
            check(merged(root, "older-here", OdometerCarry.encode(older), record).equals(record), "the backup's newer record wins");
            OdometerCarry tied = at(77); tied.lastWall = carried.lastWall;
            check(merged(root, "tie", OdometerCarry.encode(tied), record).equals(OdometerCarry.encode(tied)), "a tie keeps this phone's");
            check(merged(root, "no-backup-record", OdometerCarry.encode(older), null).equals(OdometerCarry.encode(older)), "a backup without a record leaves this phone's");

            // Never another profile: the same scooter (serial) under another profile ID gets nothing.
            BackupTestStore other = store(root, "other", Q, "SERIAL-S", null);
            other.restore(backup, scooters, false, null);
            check(!other.preferences().get("app_settings").containsKey(OdometerCarry.key(Q)) && !other.preferences().get("app_settings").containsKey(key),
                    "the same scooter under another profile ID: no record copied");

            // Replace everything: the same ID merges by the same rule.
            Set<String> all = BackupTestStore.all();
            BackupTestStore keeps = store(root, "replace-keeps", P, "SERIAL-S", OdometerCarry.encode(newer));
            keeps.restore(store(root, "replace-src", P, "SERIAL-S", record).export(all), all, true, null);
            check(OdometerCarry.encode(newer).equals(keeps.preferences().get("app_settings").get(key)), "Replace everything with an older record: this phone's newer one stays");
            BackupTestStore takes = store(root, "replace-takes", P, "SERIAL-S", OdometerCarry.encode(older));
            takes.restore(store(root, "replace-src2", P, "SERIAL-S", record).export(all), all, true, null);
            check(record.equals(takes.preferences().get("app_settings").get(key)), "Replace everything with a newer record: the backup's");
        } finally { AppDataArchive.deleteTree(root); }
    }

    private static String item(String id, String serial) {
        return "{\"id\":\"" + id + "\",\"name\":\"Scooter\",\"identity\":\"88AA00000001\",\"role\":0,\"mac\":\"AA:BB:CC:DD:EE:01\",\"serial\":\"" + serial + "\",\"lastConnected\":0}";
    }

    private static BackupTestStore store(File root, String name, String id, String serial, String record) throws java.io.IOException {
        BackupTestStore s = new BackupTestStore(new File(root, name));
        s.put("profiles", "items", "[" + item(id, serial) + "]"); s.put("profiles", "active", id); s.put("onboarding", "onboarding_done", true);
        if (record != null) s.put("app_settings", OdometerCarry.key(id), record);
        return s;
    }

    /** Profile P's record after a Scooters merge of a backup holding {@code incoming} onto a phone holding {@code here}. */
    private static String merged(File root, String name, String here, String incoming) throws java.io.IOException {
        BackupTestStore phone = store(root, name + "-phone", P, "SERIAL-S", here);
        phone.restore(store(root, name + "-backup", P, "SERIAL-S", incoming).export(Collections.singleton(BackupRegistry.SCOOTERS)), Collections.singleton(BackupRegistry.SCOOTERS), false, null);
        Object kept = phone.preferences().get("app_settings").get(OdometerCarry.key(P));
        return kept == null ? "" : (String) kept;
    }

    // 6. Raw consumers are unchanged: only the shown odometer adds the carried distance.
    private static void rawConsumers() {
        String[] raw = {"RideStore", "LearnedRangeEstimate", "LiveRangeEstimator", "RideAutoStopPolicy", "RidingSession", "RidingValues",
                "MaintenanceKeeper", "MaintenanceDistance", "MaintenanceView", "MaintenanceScreen", "MaintenanceNotify", "Telemetry", "TelemetryHistory",
                "StatsScreen", "StatsCharts", "RidesScreen", "DiagnosticsBundle", "IssueReport"};
        for (String name : raw) {
            String source = read("src/com/jjricks/volt/" + name + ".java");
            // Live Stats takes only the learned range from Home's readings, never the odometer.
            String rest = source.replace("service.dashboardReadings().get(\"remaining_range_km\")", "");
            check(!source.contains("OdometerCarry") && !source.contains("shownOdometerKm") && !source.contains("odometerRecord") && !rest.contains("dashboardReadings"),
                    name + " reads the raw odometer");
        }
        String service = read("src/com/jjricks/volt/ScooterService.java");
        check(!between(service, "public Map<String, TelemetryHistory.Reading> displayReadings() {", "/** Local choice").contains("withShownOdometer"),
                "displayReadings (charts, stats, charging, diagnostics) stays raw");
        check(between(service, "public Map<String, TelemetryHistory.Reading> dashboardReadings() {", "private LearnedRangeEstimate.Result currentRangeCache").contains("withShownOdometer(displayReadings())"),
                "Home's glanceable readings show the carried total");
        check(count(service, "withShownOdometer(") == 2 && count(service, "shownOdometerKm(") == 1, "nowhere else in the service");
        check(between(service, "private double rideDistance(", "\n    }").indexOf("Odometer") < 0, "rides' distance stays raw");
        check(service.contains("MaintenanceDistance.Outcome outcome = maintenance.observe(scope, odometer.numericValue, bits, System.currentTimeMillis());"), "maintenance reads the raw odometer");
        String riding = read("src/com/jjricks/volt/RidingScreen.java");
        check(count(riding, "shownOdometerKm(") == 1 && riding.contains("RidingFocus.distance(service == null ? r.value : service.shownOdometerKm(r.value), units)"),
                "riding mode shows the carried total in its odometer slot only");
        String home = read("src/com/jjricks/volt/DashboardScreen.java");
        check(home.contains("for (Tile tile : tiles) tile.refresh(tile.widget.type.equals(\"live\") || tile.widget.type.equals(\"history\")\n                ? readings : forecast);"),
                "Home's charts keep the raw readings");
    }

    private static int count(String text, String part) { int n = 0; for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++; return n; }

    private static String between(String text, String start, String end) {
        int a = text.indexOf(start);
        if (a < 0) throw new AssertionError("missing: " + start);
        int b = text.indexOf(end, a + start.length());
        return text.substring(a, b < 0 ? text.length() : b);
    }

    private static String read(String path) {
        try { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
