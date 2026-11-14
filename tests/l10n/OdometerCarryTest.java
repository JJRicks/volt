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
        resetArrivesAs16BitZero();
        telemetryReset();
        sixteenToThirtyTwo();
        confirmComparesLowReadings();
        adoptKeepsNewerRecord();
        cachedReadingOlderThanRecord();
        gaugeCue();
        cleanHomeStopsRings();
        wholeKmAndTenthsReset();
        alternatingReportsKeepTotal();
        wholeKmMeetsTenths();
        shortReportsOnThirtyTwoBit();
        adoptComparesTotals();
        restoredTotalDated();
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
        check(w.observe(2, 8, 3, WALL + 2) == OdometerCarry.Step.WRAP, "two readings confirm the wrap");
        check(w.carries.isEmpty() && near(w.carriedKm(), 0) && near(w.wrappedKm, 256), "a wrap is no carry-over: one turn of the field is counted");
        check(near(w.displayKm(3), 259) && w.displayKm(3) > 250, "the shown total never drops at a wrap");
        check(!w.unacknowledged(), "a wrap asks for no attention");
        // A 16-bit field (v1 tenths) wraps at 6553.6.
        OdometerCarry s = new OdometerCarry();
        s.observe(6500.0, 16, 1, WALL);
        s.observe(10.0, 16, 2, WALL + 1); s.observe(10.2, 16, 3, WALL + 2);
        check(s.carries.isEmpty() && near(s.wrappedKm, 6553.6) && near(s.displayKm(10.4), 6564.0), "a 16-bit wrap from near the top to near zero keeps counting");
        // The same classification as maintenance (one shared helper).
        check(MaintenanceDistance.step(250, 8, 2) == MaintenanceDistance.Kind.WRAP && MaintenanceDistance.step(250, 8, 240) == MaintenanceDistance.Kind.RESET
                && MaintenanceDistance.step(500, 32, 499.8) == MaintenanceDistance.Kind.IGNORED && MaintenanceDistance.step(500, 32, 499.7) == MaintenanceDistance.Kind.RESET,
                "MaintenanceDistance.step: wrap, reset, jitter");
        // 250 to 240 on a 1-byte field is a reset, not a wrap.
        OdometerCarry r = new OdometerCarry();
        r.observe(250, 8, 1, WALL); r.observe(240, 8, 2, WALL + 1); r.observe(240, 8, 3, WALL + 2);
        check(r.carries.size() == 1 && near(r.carriedKm(), 10) && near(r.wrappedKm, 0), "a drop outside the wrap window is a reset");
    }

    // 3. The carry-over is last saved minus the first reading after the drop.
    private static void carryAmount() {
        OdometerCarry zero = at(1234.5);
        zero.observe(0, 32, 2, WALL + MIN); zero.observe(0, 32, 3, WALL + 2 * MIN);
        check(near(zero.carriedKm(), 1234.5) && near(zero.displayKm(0), 1234.5), "a reset to 0 carries the full last value");
        check(zero.last().wall == WALL + 2 * MIN && near(zero.last().fromKm, 1234.5) && near(zero.last().toKm, 0), "the carry-over records when and what");
        OdometerCarry used = at(1234.5);
        used.observe(500, 32, 2, WALL + MIN); used.observe(500.2, 32, 3, WALL + 2 * MIN);
        check(near(used.carriedKm(), 734.5) && near(used.displayKm(500.3), 1234.8), "a used part at 500: carries only what keeps the total going, never more");
        check(used.displayKm(500.3) < 1234.5 + 500.3, "a used part's own mileage is never added");
    }

    // 4. Multiple resets accumulate.
    private static void multipleResets() {
        OdometerCarry c = at(1000);
        c.observe(0, 32, 2, WALL + 1); c.observe(0, 32, 3, WALL + 2);
        c.observe(300, 32, 4, WALL + 3);
        c.observe(0, 32, 5, WALL + 4); c.observe(0.2, 32, 6, WALL + 5);
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
        w.observe(250, 8, 1, WALL); w.observe(2, 8, 2, WALL + 1); w.observe(2, 8, 3, WALL + 2);
        check(w.observe(259.0, 16, 4, WALL + 3) == OdometerCarry.Step.SWITCHED && w.carries.isEmpty() && near(w.wrappedKm, 0) && near(w.displayKm(259), 259) && w.bits == 16,
                "a v0-to-v1 change (8-bit whole km to tenths) above the whole-km total moves to tenths without a carry-over; the tenths stand");
        check(c.observe(10, 8, 4, WALL + 3) == OdometerCarry.Step.IGNORED && near(c.carriedKm(), 1234.5) && near(c.lastRawKm, 0) && c.bits == 32,
                "a whole-km reading on a tenths record is left out, keeping what was carried over");
    }

    // The cue: the reduced-motion choice, the still dot while moving, nothing in riding mode, re-armed by a new carry-over.
    private static void attention() {
        check(OdometerAttention.style(true, true, true, false, false) == OdometerAttention.Style.SONAR, "unacknowledged, animations on: the dot sends out rings");
        check(OdometerAttention.style(true, true, false, false, false) == OdometerAttention.Style.DOT, "animations off: the still dot only");
        check(OdometerAttention.style(true, true, true, false, true) == OdometerAttention.Style.DOT && OdometerAttention.style(true, true, false, false, true) == OdometerAttention.Style.DOT,
                "while the scooter reports speed: the still dot, no ring");
        check(OdometerAttention.style(true, true, true, true, false) == OdometerAttention.Style.NONE && OdometerAttention.style(true, true, false, true, false) == OdometerAttention.Style.NONE,
                "never in riding mode or off screen");
        check(OdometerAttention.style(false, true, true, false, false) == OdometerAttention.Style.NONE, "acknowledged: plain");
        // No cue where a tap won't open the explanation: not the pulse, the still accent and dot, or the spoken hint.
        for (boolean motion : new boolean[]{true, false})
            check(OdometerAttention.style(true, false, motion, false, false) == OdometerAttention.Style.NONE, "a cell whose tap does something else gets no cue");
        String home = read("src/com/jjricks/volt/DashboardScreen.java");
        String mark = between(home, "private void markOdometer(", "void sonarFrame(");
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
        // The number stays plain: no colour change, and the dot is drawn over the tile, never in the number's own view.
        String sonar = between(home, "private final class OdometerSonar", "/** A distance as the maintenance page writes an odometer");
        check(!mark.contains("setTextColor") && !mark.contains("setBackground") && mark.contains("getOverlay().add(sonar)"),
                "the cue never recolours, re-backgrounds or moves the number: it is an overlay");
        check(sonar.contains("fill.setColor(ui.orange)") && sonar.contains("ring.setColor(ui.orange)") && sonar.contains("dot = ui.dp(4)"),
                "an orange dot about 8 dp across, and an orange ring");
        check(sonar.contains("LAYOUT_DIRECTION_RTL") && sonar.contains("rtl ? left - gap - dot : right + gap + dot"), "the dot sits at the number's end, mirrored right to left");
        check(sonar.contains("host.getWidth() - cx") && sonar.contains("Math.min(dot * OdometerAttention.RING_REACH, room)"), "the ring stops short of the tile's edge, never clipped");
        check(sonar.contains("if (ringing)"), "the still dot draws no ring");
        // The ring: from the dot's edge out to three times its radius, easing out and fading to nothing, under three a second.
        check(1000.0 / OdometerAttention.RING_MS < 3, "under three rings a second");
        check(OdometerAttention.ring(0) == 0 && near(OdometerAttention.ring(OdometerAttention.RING_MS / 2), .5) && OdometerAttention.ring(OdometerAttention.RING_MS) == 0
                && near(OdometerAttention.ring(OdometerAttention.RING_MS + OdometerAttention.RING_MS / 4), .25), "it repeats");
        check(near(OdometerAttention.ringRadius(4, 12, 0), 4) && near(OdometerAttention.ringRadius(4, 12, 1), 12), "from the dot's edge to three times its radius");
        check(OdometerAttention.ringRadius(4, 12, .5f) > 8, "easing out: most of the growth comes early");
        check(OdometerAttention.ringRadius(4, 2, .5f) == 4, "no room: the ring stays at the dot");
        float lastRadius = 0, lastAlpha = 2;
        for (int i = 0; i <= 100; i++) {
            float p = i / 100f, r = OdometerAttention.ringRadius(4, 12, p), a = OdometerAttention.ringAlpha(p);
            check(r >= lastRadius && a <= lastAlpha && a >= 0 && a <= 1, "the ring only grows and fades");
            lastRadius = r; lastAlpha = a;
        }
        check(OdometerAttention.ringAlpha(0) == 1 && OdometerAttention.ringAlpha(1) == 0, "it fades to transparent");
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
        int guard = feed.indexOf("if (!simulating() && odometerRecords != null) {"), observe = feed.indexOf("odometerRecords.observe(scope, odometer.numericValue, bits, ble.telemetry.odometerExtended(), odometer.updatedMs,");
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

    // Council 5903733062 #1: Telemetry reads the 32-bit odometer only while it is above zero, so a reset to 0 arrives as
    // a 16-bit reading. The same tenths odometer: an ordinary drop that carries over, never a fresh start.
    private static void resetArrivesAs16BitZero() {
        OdometerCarry c = at(1234.5);
        check(c.observe(0, 16, 2, WALL + 1) == OdometerCarry.Step.PENDING, "a 16-bit 0.0 after 32-bit readings is a drop that waits");
        check(near(c.lastRawKm, 1234.5) && c.bits == 32 && c.carries.isEmpty() && near(c.displayKm(0), 1234.5), "nothing saved changes; the total holds");
        check(c.observe(0, 16, 3, WALL + 2) == OdometerCarry.Step.CARRIED && near(c.carriedKm(), 1234.5) && c.bits == 32, "a second 16-bit 0.0 confirms it");
        check(near(c.displayKm(0), 1234.5) && c.unacknowledged(), "the total carries on, with the cue");
        check(c.observe(0.1, 32, 4, WALL + 3) == OdometerCarry.Step.FORWARD && c.carries.size() == 1 && near(c.displayKm(0.1), 1234.6),
                "the 32-bit field coming back at 0.1 is the same odometer moving on");
        // Confirmed by the scooter moving: 16-bit 0.0, then 32-bit 0.1.
        OdometerCarry moved = at(1234.5);
        moved.observe(0, 16, 2, WALL + 1);
        check(moved.observe(0.1, 32, 3, WALL + 2) == OdometerCarry.Step.CARRIED && near(moved.carriedKm(), 1234.5) && near(moved.displayKm(0.1), 1234.6),
                "a 32-bit 0.1 confirms a 16-bit 0.0");
        // A single 16-bit zero, then the 32-bit field again: a glitch, nothing carried, nothing re-baselined.
        OdometerCarry glitch = at(1234.5);
        glitch.observe(0, 16, 2, WALL + 1);
        check(glitch.observe(1234.6, 32, 3, WALL + 2) == OdometerCarry.Step.FORWARD && glitch.carries.isEmpty() && near(glitch.lastRawKm, 1234.6), "one 16-bit zero is a glitch");
        // Through the Keeper: the stored record is never overwritten by the 16-bit zero.
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        keeper.observe(P, 1234.5, 32, 1, WALL);
        check(keeper.observe(P, 0, 16, 2, WALL + MIN) == OdometerCarry.Step.PENDING, "the Keeper: pending");
        OdometerCarry stored = OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P)));
        check(near(stored.lastRawKm, 1234.5) && stored.bits == 32, "the stored record still holds 1,234.5 km on the 32-bit field");
        check(keeper.observe(P, 0, 16, 3, WALL + 2 * MIN) == OdometerCarry.Step.CARRIED, "the Keeper: carried");
        stored = OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P)));
        check(near(stored.carriedKm(), 1234.5) && stored.bits == 32 && near(stored.displayKm(0), 1234.5), "written at once, still on the 32-bit field");
        // The serial hand-off: the kept record is stored at 32 bits; the new profile's next readings are 16-bit zeros.
        Prefs handoff = new Prefs();
        OdometerCarry.Keeper again = new OdometerCarry.Keeper(handoff);
        again.observe(P, 1234.5, 32, 1, WALL);
        again.forget(P, "N2242SERIAL");
        check(again.adopt(Q, "N2242SERIAL") && again.load(Q).bits == 32, "the kept record moves over at 32 bits");
        check(again.observe(Q, 0, 16, 2, WALL + MIN) == OdometerCarry.Step.PENDING && again.observe(Q, 0, 16, 3, WALL + 2 * MIN) == OdometerCarry.Step.CARRIED
                        && near(again.load(Q).carriedKm(), 1234.5), "set up again after Release: two 16-bit zeros carry the old odometer over");
        // Only v0 (8-bit whole km) against v1 (tenths) is another field.
        check(at(500).observe(244, 8, 2, WALL + 1) == OdometerCarry.Step.IGNORED, "32 to 8: left out");
        OdometerCarry sixteen = new OdometerCarry();
        sixteen.observe(500, 16, 1, WALL);
        check(sixteen.observe(244, 8, 2, WALL + 1) == OdometerCarry.Step.IGNORED && near(sixteen.lastRawKm, 500), "16 to 8: left out");
        check(OdometerCarry.effectiveBits(32, 16) == 32 && OdometerCarry.effectiveBits(16, 16) == 16 && OdometerCarry.effectiveBits(32, 8) == 8
                && OdometerCarry.effectiveBits(8, 16) == 16, "a 16-bit reading is judged on the 32-bit field once the record has it");
    }

    /** A v1 ride report (0x92) with this 16-bit and 32-bit odometer, in tenths of a km. */
    private static byte[] ride(int legacyTenths, long extendedTenths) {
        byte[] data = new byte[19];
        data[1] = 80; data[2] = 1;
        data[13] = (byte) legacyTenths; data[14] = (byte) (legacyTenths >>> 8);
        for (int i = 0; i < 4; i++) data[15 + i] = (byte) (extendedTenths >>> (8 * i));
        return data;
    }

    // The same reset as Telemetry really reports it: a 0x92 frame whose 32-bit odometer is zero.
    private static void telemetryReset() {
        Telemetry model = new Telemetry();
        OdometerCarry c = new OdometerCarry();
        model.accept(0x92, ride(0, 12345), 10);
        check(model.odometerBits() == 32 && near(model.number("odometer_km"), 1234.5), "the 32-bit odometer: 1,234.5 km");
        check(c.observe(model.number("odometer_km"), model.odometerBits(), 10, WALL) == OdometerCarry.Step.FIRST, "first reading");
        model.accept(0x92, ride(0, 0), 20);
        check(model.odometerBits() == 16 && near(model.number("odometer_km"), 0), "after a reset Telemetry reports the 16-bit field: 0.0 km at 16 bits");
        check(c.observe(model.number("odometer_km"), model.odometerBits(), 20, WALL + 1) == OdometerCarry.Step.PENDING, "Telemetry's reset reading waits");
        model.accept(0x92, ride(0, 0), 30);
        check(c.observe(model.number("odometer_km"), model.odometerBits(), 30, WALL + 2) == OdometerCarry.Step.CARRIED && near(c.carriedKm(), 1234.5),
                "and the next frame carries it over");
        model.accept(0x92, ride(1, 1), 40);
        check(model.odometerBits() == 32 && c.observe(model.number("odometer_km"), model.odometerBits(), 40, WALL + 3) == OdometerCarry.Step.FORWARD
                && near(c.displayKm(0.1), 1234.6), "back on the 32-bit field at 0.1 km: forward");
    }

    // A 16-bit-only record whose report starts carrying a nonzero 32-bit value: the same counter, read whole.
    private static void sixteenToThirtyTwo() {
        OdometerCarry s = new OdometerCarry();
        s.observe(6000.0, 16, 1, WALL);
        check(s.observe(6000.5, 32, 2, WALL + 1) == OdometerCarry.Step.FORWARD && s.bits == 32 && near(s.displayKm(6000.5), 6000.5), "16 to 32: forward, no re-baseline");
        // With a 16-bit lap counted, the laps fold into the baseline and the shown total stays put.
        OdometerCarry w = new OdometerCarry();
        w.observe(6500.0, 16, 1, WALL); w.observe(10.0, 16, 2, WALL + 1); w.observe(10.0, 16, 3, WALL + 2);
        double before = w.displayKm(10.0);
        check(near(before, 6563.6) && w.observe(6563.7, 32, 4, WALL + 3) == OdometerCarry.Step.FORWARD && near(w.wrappedKm, 0) && near(w.displayKm(6563.7), 6563.7),
                "16 to 32 after a lap: the 32-bit value takes over with the same total");
        check(w.observe(0, 16, 5, WALL + 4) == OdometerCarry.Step.PENDING && w.observe(0, 16, 6, WALL + 5) == OdometerCarry.Step.CARRIED && near(w.displayKm(0), 6563.7),
                "and a later reset still carries everything over");
        // 32 bits lower than the 16-bit total (Volt took a reset for a lap): a drop that waits, the total held.
        OdometerCarry odd = new OdometerCarry();
        odd.observe(6500.0, 16, 1, WALL); odd.observe(10.0, 16, 2, WALL + 1); odd.observe(10.0, 16, 3, WALL + 2);
        check(odd.observe(10.1, 32, 4, WALL + 3) == OdometerCarry.Step.PENDING && near(odd.displayKm(10.1), 6563.6), "a lower 32-bit value holds the total while it waits");
    }

    // Council 5903733062 #6: the second low reading confirms only a drop to about the same number.
    private static void confirmComparesLowReadings() {
        OdometerCarry c = at(1000);
        check(c.observe(0, 32, 2, WALL + 1) == OdometerCarry.Step.PENDING, "a bogus 0");
        check(c.observe(800, 32, 3, WALL + 2) == OdometerCarry.Step.PENDING && c.carries.isEmpty() && near(c.displayKm(800), 1000),
                "a real 800 after it is not a confirmation: it waits in its place");
        check(c.observe(800, 32, 4, WALL + 3) == OdometerCarry.Step.CARRIED && near(c.carriedKm(), 200) && near(c.displayKm(800), 1000),
                "a second 800 confirms: 200 km carried over, 1,000 km shown");
        OdometerCarry close = at(1000);
        close.observe(0, 32, 2, WALL + 1);
        check(close.observe(0.2, 32, 3, WALL + 2) == OdometerCarry.Step.CARRIED && near(close.carriedKm(), 1000), "within 0.2 km of the first: confirmed");
        OdometerCarry far = at(1000);
        far.observe(0, 32, 2, WALL + 1);
        check(far.observe(0.3, 32, 3, WALL + 2) == OdometerCarry.Step.PENDING && far.carries.isEmpty(), "0.3 km away: waits again");
    }

    // Council 5903733062 #8: the kept copy moves over only when the profile's own record is not the newer one.
    private static void adoptKeepsNewerRecord() {
        // Forget on phone A at 500 km; phone B rode on to 700 km; its backup restored onto A, newer.
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        keeper.observe(P, 500, 32, 1, WALL);
        keeper.forget(P, "N2242SERIAL");
        OdometerCarry fromB = new OdometerCarry();
        fromB.observe(700, 32, 1, WALL + 60 * MIN);
        prefs.values.put(OdometerCarry.key(Q), OdometerCarry.encode(fromB));
        check(!keeper.adopt(Q, "N2242SERIAL") && !prefs.values.containsKey(OdometerCarry.keptKey("N2242SERIAL")), "the older kept copy is dropped");
        check(near(keeper.load(Q).lastRawKm, 700), "the restored, newer record stays");
        keeper.observe(Q, 0, 32, 2, WALL + 61 * MIN); keeper.observe(Q, 0, 32, 3, WALL + 62 * MIN);
        check(near(keeper.load(Q).carriedKm(), 700) && near(keeper.load(Q).displayKm(0), 700), "a reset then carries 700 km, not 500");
        // The profile's record is older than the kept copy: the kept copy moves over.
        Prefs older = new Prefs();
        OdometerCarry.Keeper k2 = new OdometerCarry.Keeper(older);
        OdometerCarry old = new OdometerCarry();
        old.observe(300, 32, 1, WALL - 60 * MIN);
        older.values.put(OdometerCarry.key(Q), OdometerCarry.encode(old));
        k2.observe(P, 500, 32, 1, WALL);
        k2.forget(P, "S2");
        check(k2.adopt(Q, "S2") && near(k2.load(Q).lastRawKm, 500), "an older profile record: the kept copy moves over");
        check(OdometerCarry.Keeper.keepsOwn(at(700), at(500)) && !OdometerCarry.Keeper.keepsOwn(new OdometerCarry(), at(500)), "no baseline: the kept copy moves over");
        // Newer but below the kept reading: began after the reset (readings before the serial check): the kept copy moves over.
        OdometerCarry afterReset = new OdometerCarry();
        afterReset.observe(0, 16, 1, WALL + MIN);
        check(!OdometerCarry.Keeper.keepsOwn(afterReset, at(500)), "a newer record below the kept reading began after the reset");
    }

    // Council 5903733062 #9: a cached reading older than the record shows the record's own reading.
    private static void cachedReadingOlderThanRecord() {
        // Phone A last saw 1,000 km; a newer backup restored in which the scooter reset and 1,000 km carried over.
        OdometerCarry restored = at(1000);
        restored.observe(0, 32, 2, WALL + 10 * MIN); restored.observe(0, 32, 3, WALL + 11 * MIN);
        check(near(restored.shownKm(1000, WALL, false), 1000), "the cached 1,000 km from before the reset is not counted twice");
        check(near(restored.shownKm(0, WALL + 12 * MIN, false), 1000) && near(restored.shownKm(0.4, WALL, true), 1000.4), "a newer cached reading, or a live one, as before");
        check(!near(restored.displayKm(1000), 1000), "(displayKm alone would show 2,000)");
        String service = read("src/com/jjricks/volt/ScooterService.java");
        String with = between(service, "private Map<String, TelemetryHistory.Reading> withShownOdometer(", "private LearnedRangeEstimate.Result currentRangeCache");
        check(with.contains("record.shownKm(odometer.number, odometer.capturedWallMs, odometer.live)") && !with.contains("record.displayKm("),
                "Home's odometer goes through shownKm with the reading's age and liveness");
    }

    // Council 5903733062 #4: an odometer gauge gets the dot and the tap, by the number's rules.
    private static void gaugeCue() {
        String home = read("src/com/jjricks/volt/DashboardScreen.java");
        String mark = between(home, "private void markOdometer(", "void sonarFrame(");
        check(mark.contains("Gauge dial = number == null && gauge != null && MaintenanceView.ODOMETER.equals(widget.metric) ? gauge : null;")
                        && mark.contains("View target = dial != null ? dial : number == big ? big : odometerTarget(number);")
                        && mark.contains("if (dial != null && target == dial && dial.hasOnClickListeners() && dial != odometerTarget) target = null;"),
                "an odometer gauge is the tap target, unless it already acts on a tap");
        check(mark.contains("sonar = dial != null ? new OdometerSonar(this, dial) : new OdometerSonar(this, number);")
                        && mark.contains("dial.setContentDescription(T.dashboardOdometerCarrySpoken(dial.getContentDescription()))"),
                "the gauge gets the same overlay and spoken hint");
        String sonar = between(home, "private final class OdometerSonar", "/** A distance as the maintenance page writes an odometer");
        check(sonar.contains("if (gauge != null) { placeOnGauge(); return; }") && sonar.contains("OdometerAttention.gaugeSpot(") && sonar.contains("gauge.arcMoved = replace;"),
                "on a gauge the dot is placed from the ring as drawn");
        String dial = between(home, "private final class Gauge extends View {", "private void splitReading(");
        check(dial.contains("Runnable arcMoved;"), "the gauge tells the cue when its ring moves");
        // The spot: outside the ring by the margin, inside the gauge, mirrored right to left.
        float cx = 150, cy = 66, outer = 60, width = 300, margin = 4;
        float[] spot = OdometerAttention.gaugeSpot(cx, cy, outer, width, false, margin);
        double toRing = Math.hypot(spot[0] - cx, spot[1] - cy) - outer;
        check(spot[0] > cx && spot[1] < cy && spot[2] > 4, "top-end corner, with room for a ring");
        check(toRing >= spot[2] + margin - 1e-3 && spot[1] >= spot[2] + margin - 1e-3 && width - spot[0] >= spot[2] + margin - 1e-3,
                "the ring at its reach touches neither the gauge's ring nor its top and end edges");
        float[] rtl = OdometerAttention.gaugeSpot(cx, cy, outer, width, true, margin);
        check(Math.abs(rtl[0] - (cx - (spot[0] - cx))) < 1e-3 && Math.abs(rtl[1] - spot[1]) < 1e-3 && Math.abs(rtl[2] - spot[2]) < 1e-3, "mirrored right to left");
        // A narrow gauge, the ring as wide as the view: still outside the ring and inside the view.
        float[] narrow = OdometerAttention.gaugeSpot(70, 76, 64, 140, false, margin);
        check(Math.hypot(narrow[0] - 70, narrow[1] - 76) > 64 && narrow[0] < 140 && narrow[1] > 0, "a narrow gauge: between the ring and the corner");
    }

    // Council 5903733062 #2: leaving Clean Home stops the rings; the animator also stops itself.
    private static void cleanHomeStopsRings() {
        String home = read("src/com/jjricks/volt/DashboardScreen.java");
        String clean = between(home, "final class CleanParts {", "public View view() {");
        String close = between(clean, "void close() {", "\n        }");
        check(close.contains("closed = true;") && close.contains("pulsing.clear();") && close.contains("runOdometerPulse();")
                        && close.indexOf("runOdometerPulse();") > close.indexOf("closed = true;"),
                "Clean Home's close clears the ringing tiles and stops the animator, as Home's close does");
        String run = between(home, "private void runOdometerPulse() {", "private boolean anyPulsingAttached() {");
        check(run.contains("if (closed || !anyPulsingAttached()) {") && run.contains("a.cancel();") && run.contains("if (odometerPulse == a) odometerPulse = null;"),
                "the animator cancels itself once the page is closed or no ringing tile is attached");
        check(between(home, "private boolean anyPulsingAttached() {", "\n    }").contains("tile.isAttachedToWindow()"), "attached means on a window");
        check(between(home, "private final class OdometerSonar", "/** A distance as the maintenance page writes an odometer").contains("public void onViewAttachedToWindow(View v) { runOdometerPulse(); }"),
                "a ringing tile back on screen restarts the rings");
    }

    /** A v0 ride report (0x91) with this one-byte odometer in whole km. */
    private static byte[] rideWhole(int wholeKm) {
        byte[] data = new byte[10];
        data[1] = 80; data[2] = 1;
        data[9] = (byte) wholeKm;
        return data;
    }

    /** A short v1 ride report (0x92) that ends after the 16-bit odometer: no 32-bit bytes. */
    private static byte[] rideShort(int legacyTenths) {
        byte[] data = new byte[15];
        data[1] = 80; data[2] = 1;
        data[13] = (byte) legacyTenths; data[14] = (byte) (legacyTenths >>> 8);
        return data;
    }

    /** One frame through Telemetry into a record, as ScooterService feeds it (the frame's time tells frames apart). */
    private static OdometerCarry.Step feed(Telemetry model, OdometerCarry c, int cmd, byte[] frame, long time) {
        model.accept(cmd, frame, time);
        return c.observe(model.number("odometer_km"), model.odometerBits(), model.odometerExtended(), time, WALL + time);
    }

    private static OdometerCarry.Step feed(Telemetry model, OdometerCarry.Keeper keeper, int cmd, byte[] frame, long time) {
        model.accept(cmd, frame, time);
        return keeper.observe(P, model.number("odometer_km"), model.odometerBits(), model.odometerExtended(), time, WALL + time);
    }

    // Council 5903942765 #1: a scooter sending whole-km (0x91) and tenths (0x92) reports side by side. After a reset the
    // tenths carry it over, whichever report's zero comes first; the whole-km zero never erases the record.
    private static void wholeKmAndTenthsReset() {
        // The 0x91 zero first, through the Keeper: the stored record is never overwritten by it.
        Telemetry model = new Telemetry();
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        check(feed(model, keeper, 0x92, ride(0, 12345), 10) == OdometerCarry.Step.FIRST, "0x92: 1,234.5 km");
        check(feed(model, keeper, 0x91, rideWhole(1234 & 255), 20) == OdometerCarry.Step.IGNORED && model.odometerBits() == 8, "0x91 alongside: left out");
        check(feed(model, keeper, 0x91, rideWhole(0), 30) == OdometerCarry.Step.IGNORED, "the reset's 0x91 zero, first: left out");
        OdometerCarry stored = OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P)));
        check(near(stored.lastRawKm, 1234.5) && stored.bits == 32 && near(keeper.load(P).totalKm(), 1234.5), "the stored record still holds 1,234.5 km on the 32-bit field");
        check(feed(model, keeper, 0x92, ride(0, 0), 40) == OdometerCarry.Step.PENDING, "the 0x92 zero waits");
        check(feed(model, keeper, 0x91, rideWhole(0), 50) == OdometerCarry.Step.IGNORED && keeper.load(P).dropPending(), "another 0x91 zero neither confirms nor breaks the run");
        check(feed(model, keeper, 0x92, ride(0, 0), 60) == OdometerCarry.Step.CARRIED, "the second 0x92 zero confirms");
        stored = OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P)));
        check(near(stored.carriedKm(), 1234.5) && near(stored.displayKm(0), 1234.5) && stored.bits == 32, "1,234.5 km carried over and written");
        check(feed(model, keeper, 0x91, rideWhole(0), 70) == OdometerCarry.Step.IGNORED && feed(model, keeper, 0x92, ride(1, 1), 80) == OdometerCarry.Step.FORWARD
                && near(keeper.load(P).displayKm(0.1), 1234.6), "riding on: the tenths count on from the carried total");
        // The 0x92 zero first.
        Telemetry other = new Telemetry();
        OdometerCarry c = new OdometerCarry();
        feed(other, c, 0x92, ride(0, 12345), 10); feed(other, c, 0x91, rideWhole(1234 & 255), 20);
        check(feed(other, c, 0x92, ride(0, 0), 30) == OdometerCarry.Step.PENDING, "the 0x92 zero first: waits");
        check(feed(other, c, 0x91, rideWhole(0), 40) == OdometerCarry.Step.IGNORED && near(c.displayKm(0), 1234.5), "the 0x91 zero: left out, the total held");
        check(feed(other, c, 0x92, ride(0, 0), 50) == OdometerCarry.Step.CARRIED && near(c.carriedKm(), 1234.5) && near(c.displayKm(0), 1234.5),
                "the next 0x92 zero carries 1,234.5 km over");
    }

    // Council 5903942765 #1: an alternating scooter without a reset: nothing re-baselines and the total stays put.
    private static void alternatingReportsKeepTotal() {
        Telemetry model = new Telemetry();
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper keeper = new OdometerCarry.Keeper(prefs);
        feed(model, keeper, 0x92, ride(0, 12345), 1);
        int writes = prefs.writes;
        for (int i = 0; i < 20; i++) {
            long t = 10 + 20L * i;
            OdometerCarry.Step whole = feed(model, keeper, 0x91, rideWhole(1234 & 255), t), tenths = feed(model, keeper, 0x92, ride(0, 12345), t + 10);
            OdometerCarry r = keeper.load(P);
            check(whole == OdometerCarry.Step.IGNORED && tenths == OdometerCarry.Step.SAME, "alternation " + i + ": whole km left out, tenths the same");
            check(near(r.totalKm(), 1234.5) && near(r.lastRawKm, 1234.5) && r.bits == 32 && r.carries.isEmpty() && near(r.wrappedKm, 0) && !r.dropPending(),
                    "alternation " + i + ": the total stays at 1,234.5 km on the 32-bit field");
        }
        OdometerCarry stored = OdometerCarry.decode(prefs.values.get(OdometerCarry.key(P)));
        check(prefs.writes == writes && near(stored.lastRawKm, 1234.5) && stored.bits == 32, "nothing is written as a fresh baseline");
        // A record started from a 0x91 report moves to the tenths once, at the first 0x92, and stays there.
        Telemetry first = new Telemetry();
        OdometerCarry c = new OdometerCarry();
        check(feed(first, c, 0x91, rideWhole(1234 & 255), 1) == OdometerCarry.Step.FIRST && c.bits == 8, "a first 0x91 report starts a whole-km record");
        check(feed(first, c, 0x92, ride(0, 12345), 2) == OdometerCarry.Step.SWITCHED && c.bits == 32 && near(c.totalKm(), 1234.5), "the first 0x92 moves it to tenths");
        for (int i = 0; i < 20; i++) {
            feed(first, c, 0x91, rideWhole(1234 & 255), 10 + 20L * i); feed(first, c, 0x92, ride(0, 12345), 20 + 20L * i);
            check(near(c.totalKm(), 1234.5) && c.bits == 32, "and then stays put");
        }
    }

    // Council 5903942765 #1: a whole-km record meets its first tenths reading. The same counter: tenths, total kept;
    // far below: a drop, confirmed by a second tenths reading, carried over.
    private static void wholeKmMeetsTenths() {
        // Agrees within 1 km, with a carry-over: kept.
        OdometerCarry carried = new OdometerCarry();
        carried.observe(200, 8, 1, WALL); carried.observe(100, 8, 2, WALL + 1); carried.observe(100, 8, 3, WALL + 2);
        check(near(carried.carriedKm(), 100) && carried.observe(100.3, 16, 4, WALL + 3) == OdometerCarry.Step.SWITCHED
                && carried.carries.size() == 1 && near(carried.displayKm(100.3), 200.3) && carried.bits == 16, "same counter: tenths, the carry-over kept");
        // Agrees modulo 256 after a counted lap: the lap folds into the total, which never drops.
        OdometerCarry lap = new OdometerCarry();
        lap.observe(250, 8, 1, WALL); lap.observe(2, 8, 2, WALL + 1); lap.observe(2, 8, 3, WALL + 2);
        check(near(lap.totalKm(), 258) && lap.observe(2.4, 16, 4, WALL + 3) == OdometerCarry.Step.SWITCHED, "the tenths agree with the one byte modulo 256");
        check(near(lap.displayKm(2.4), 258) && lap.carries.isEmpty() && !lap.unacknowledged(), "the shown total holds at 258 km, with nothing to explain");
        check(lap.observe(3.4, 16, 5, WALL + 4) == OdometerCarry.Step.FORWARD && near(lap.displayKm(3.4), 259), "and counts on");
        // The whole-km report rounded up: a tenths reading just under the whole-km total never lowers it.
        OdometerCarry under = new OdometerCarry();
        under.observe(200, 8, 1, WALL);
        check(under.observe(199.6, 32, 2, WALL + 1) == OdometerCarry.Step.SWITCHED && near(under.displayKm(199.6), 200) && under.observe(200.6, 32, 3, WALL + 2) == OdometerCarry.Step.FORWARD
                && near(under.displayKm(200.6), 201), "0.4 km under: held, then counting on");
        // Far below and no agreement: a drop, confirmed by a second tenths reading; whole km between them are left out.
        OdometerCarry drop = new OdometerCarry();
        drop.observe(200, 8, 1, WALL);
        check(drop.observe(0, 16, 2, WALL + 1) == OdometerCarry.Step.PENDING && drop.bits == 8 && near(drop.displayKm(0), 200), "far below: a drop that waits, the total held");
        check(drop.observe(0, 8, 3, WALL + 2) == OdometerCarry.Step.IGNORED && drop.dropPending(), "a whole-km reading meanwhile is left out");
        check(drop.observe(0, 16, 2, WALL + 3) == OdometerCarry.Step.PENDING, "the same frame twice is one reading");
        check(drop.observe(0.1, 16, 4, WALL + 4) == OdometerCarry.Step.CARRIED && near(drop.carriedKm(), 200) && near(drop.last().fromKm, 200) && near(drop.last().toKm, 0)
                && drop.bits == 16 && near(drop.displayKm(0.1), 200.1) && drop.unacknowledged(), "confirmed: 200 km carried over, the record is tenths");
        check(drop.observe(190, 8, 5, WALL + 5) == OdometerCarry.Step.IGNORED, "and leaves whole km out from then on");
        // A carried lap and a far drop: the whole-km total (raw plus laps) is what carries.
        OdometerCarry lapDrop = new OdometerCarry();
        lapDrop.observe(250, 8, 1, WALL); lapDrop.observe(2, 8, 2, WALL + 1); lapDrop.observe(2, 8, 3, WALL + 2); lapDrop.observe(100, 8, 4, WALL + 3);
        lapDrop.observe(0, 32, 5, WALL + 4); lapDrop.observe(0, 16, 6, WALL + 5);
        check(near(lapDrop.carriedKm(), 356) && near(lapDrop.wrappedKm, 0) && near(lapDrop.displayKm(0), 356), "laps and raw carry together");
        // One low tenths reading, then the right one: no carry-over.
        OdometerCarry glitch = new OdometerCarry();
        glitch.observe(200, 8, 1, WALL); glitch.observe(0, 16, 2, WALL + 1);
        check(glitch.observe(200.3, 16, 3, WALL + 2) == OdometerCarry.Step.SWITCHED && glitch.carries.isEmpty() && near(glitch.displayKm(200.3), 200.3), "a single low tenths reading is a glitch");
        check(OdometerCarry.sameCounter(1324.5, 44, 44) && OdometerCarry.sameCounter(199.6, 200, 200) && OdometerCarry.sameCounter(255.9, 0, 256)
                && !OdometerCarry.sameCounter(0, 200, 200) && !OdometerCarry.sameCounter(100, 2, 258), "sameCounter: above, just under, modulo 256; not a drop");
    }

    // Council 5903942765 #5: Telemetry reports 16 bits both for a full report whose 32-bit field is zero and for a short
    // report without it. Only the first says anything about the 32-bit field.
    private static void shortReportsOnThirtyTwoBit() {
        Telemetry model = new Telemetry();
        OdometerCarry c = new OdometerCarry();
        check(feed(model, c, 0x92, ride(0, 12345), 10) == OdometerCarry.Step.FIRST && model.odometerExtended(), "full reports: 1,234.5 km in the 32-bit field, 0 in the 16-bit one");
        check(feed(model, c, 0x92, rideShort(0), 20) == OdometerCarry.Step.IGNORED && model.odometerBits() == 16 && !model.odometerExtended(), "a short report's 16-bit 0: left out");
        check(feed(model, c, 0x92, rideShort(0), 30) == OdometerCarry.Step.IGNORED && !c.dropPending() && c.carries.isEmpty(), "and again: no drop");
        check(feed(model, c, 0x92, ride(0, 12345), 40) == OdometerCarry.Step.SAME && c.carries.isEmpty() && near(c.displayKm(1234.5), 1234.5),
                "full reports again: nothing carried over, 1,234.5 km, never 2,469");
        // Present but zero: the 32-bit field read 0, a reset as before.
        check(feed(model, c, 0x92, ride(0, 0), 50) == OdometerCarry.Step.PENDING && model.odometerBits() == 16 && model.odometerExtended(), "the 32-bit field at zero: a drop");
        check(feed(model, c, 0x92, rideShort(0), 60) == OdometerCarry.Step.IGNORED && c.dropPending(), "a short report between them changes nothing");
        check(feed(model, c, 0x92, ride(0, 0), 70) == OdometerCarry.Step.CARRIED && near(c.carriedKm(), 1234.5), "the second confirms: carried over");
        // A 16-bit-only scooter (short reports throughout) counts as before.
        Telemetry shortOnly = new Telemetry();
        OdometerCarry s = new OdometerCarry();
        check(feed(shortOnly, s, 0x92, rideShort(12345), 10) == OdometerCarry.Step.FIRST && s.bits == 16 && feed(shortOnly, s, 0x92, rideShort(12346), 20) == OdometerCarry.Step.FORWARD,
                "short reports on a 16-bit record count");
        shortOnly.accept(0x92, ride(0, 12345), 30);
        check(shortOnly.odometerExtended(), "a full report has the 32-bit bytes");
        shortOnly.clear();
        check(!shortOnly.odometerExtended(), "a new connection forgets it");
    }

    // Council 5903942765 #2, #6, #7 and the owner's rule: the record showing the higher total wins the serial hand-off.
    private static void adoptComparesTotals() {
        // A carry-over of 1,000 km, then Forget and set up again (one reading before the serial check): still 1,234.5.
        Prefs prefs = new Prefs();
        OdometerCarry.Keeper k = new OdometerCarry.Keeper(prefs);
        k.observe(P, 1000, 32, 1, WALL);
        k.observe(P, 0, 16, 2, WALL + MIN); k.observe(P, 0, 16, 3, WALL + 2 * MIN);
        k.observe(P, 234.5, 32, 4, WALL + 3 * MIN);
        check(near(k.load(P).totalKm(), 1234.5), "1,234.5 km shown before Forget");
        k.forget(P, "N2242SERIAL");
        k.observe(Q, 234.5, 32, 5, WALL + 30 * MIN);
        check(k.adopt(Q, "N2242SERIAL") && !prefs.values.containsKey(OdometerCarry.keptKey("N2242SERIAL")), "the kept copy's higher total wins and is removed");
        check(near(k.load(Q).displayKm(234.5), 1234.5) && near(k.load(Q).carriedKm(), 1000), "the carry-over survives");
        check(k.observe(Q, 234.5, 32, 6, WALL + 31 * MIN) == OdometerCarry.Step.SAME && near(k.load(Q).displayKm(234.5), 1234.5), "the unchanged odometer reads the same");
        // A 16-bit record with a counted lap.
        Prefs p2 = new Prefs();
        OdometerCarry.Keeper k2 = new OdometerCarry.Keeper(p2);
        k2.observe(P, 6500, 16, 1, WALL); k2.observe(P, 10, 16, 2, WALL + MIN); k2.observe(P, 10.1, 16, 3, WALL + 2 * MIN);
        k2.forget(P, "S2");
        k2.observe(Q, 10.1, 16, 4, WALL + 30 * MIN);
        check(k2.adopt(Q, "S2") && near(k2.load(Q).displayKm(10.1), 6563.7) && near(k2.load(Q).wrappedKm, 6553.6), "laps kept");
        // An older backup with a carry restored over a newer, higher kept copy: the kept copy wins (800, not 700).
        Prefs p3 = new Prefs();
        OdometerCarry.Keeper k3 = new OdometerCarry.Keeper(p3);
        k3.observe(P, 500, 32, 1, WALL); k3.observe(P, 0, 16, 2, WALL + 1); k3.observe(P, 0, 16, 3, WALL + 2); k3.observe(P, 100, 32, 4, WALL + 3);
        OdometerCarry backup = k3.load(P);
        k3.observe(P, 0, 16, 5, WALL + 10 * MIN); k3.observe(P, 0, 16, 6, WALL + 11 * MIN); k3.observe(P, 200, 32, 7, WALL + 12 * MIN);
        check(near(backup.totalKm(), 600) && near(k3.load(P).totalKm(), 800), "backup 600 km (raw 100 + 500), kept 800 km (raw 200 + 600)");
        k3.forget(P, "S3");
        p3.values.put(OdometerCarry.key(Q), OdometerCarry.encode(backup));
        check(k3.adopt(Q, "S3") && k3.observe(Q, 200, 32, 8, WALL + 20 * MIN) == OdometerCarry.Step.SAME && near(k3.load(Q).displayKm(200), 800), "800 km, not 700");
        // A newer restored record that counted a lap (266) over an older kept copy at 200: its own wins.
        Prefs p4 = new Prefs();
        OdometerCarry.Keeper k4 = new OdometerCarry.Keeper(p4);
        k4.observe(P, 200, 8, 1, WALL);
        k4.forget(P, "S4");
        OdometerCarry fromB = new OdometerCarry();
        fromB.observe(250, 8, 1, WALL + MIN); fromB.observe(10, 8, 2, WALL + 2 * MIN); fromB.observe(10, 8, 3, WALL + 3 * MIN);
        p4.values.put(OdometerCarry.key(Q), OdometerCarry.encode(fromB));
        check(!k4.adopt(Q, "S4") && !p4.values.containsKey(OdometerCarry.keptKey("S4")), "the own 266 km beats the kept 200 km; the kept copy goes");
        k4.observe(Q, 10, 8, 4, WALL + 4 * MIN); k4.observe(Q, 10, 8, 5, WALL + 5 * MIN);
        check(near(k4.load(Q).displayKm(10), 266) && k4.load(Q).carries.isEmpty(), "266 km, nothing carried over");
        // Release with a reset (the scooter's odometer zeroed): a pre-serial 16-bit zero, then the kept copy carries over.
        Prefs p5 = new Prefs();
        OdometerCarry.Keeper k5 = new OdometerCarry.Keeper(p5);
        k5.observe(P, 1234.5, 32, 1, WALL);
        k5.forget(P, "S5");
        k5.observe(Q, 0, 16, 2, WALL + MIN);
        check(k5.adopt(Q, "S5") && k5.load(Q).bits == 32, "the kept copy (1,234.5) beats the reset 0");
        k5.observe(Q, 0, 16, 3, WALL + 2 * MIN); k5.observe(Q, 0, 16, 4, WALL + 3 * MIN);
        check(near(k5.load(Q).carriedKm(), 1234.5) && near(k5.load(Q).displayKm(0), 1234.5), "Release with a reset: 1,234.5 km carried over");
        // Within the jitter the profile keeps its own; totals, not raw readings or times.
        OdometerCarry own = at(1000.1), kept = at(1000.3);
        check(OdometerCarry.Keeper.keepsOwn(own, kept) && !OdometerCarry.Keeper.keepsOwn(own, at(1000.4)), "kept wins only more than 0.2 km above");
    }

    // Council 5903942765 #8: a restored total shown for an older cached reading is dated by the record's last reading.
    private static void restoredTotalDated() {
        OdometerCarry restored = at(1000);
        restored.observe(0, 32, 2, WALL + 10 * MIN); restored.observe(0, 32, 3, WALL + 11 * MIN);
        check(restored.shownWallMs(WALL, false) == WALL + 11 * MIN && near(restored.shownKm(1000, WALL, false), 1000), "the January cache shows the February total, dated February");
        check(restored.shownWallMs(WALL + 12 * MIN, false) == WALL + 12 * MIN && restored.shownWallMs(WALL, true) == WALL, "a newer cached or a live reading keeps its own time");
        String service = read("src/com/jjricks/volt/ScooterService.java");
        String with = between(service, "private Map<String, TelemetryHistory.Reading> withShownOdometer(", "private LearnedRangeEstimate.Result currentRangeCache");
        check(with.contains("long seen = record.shownWallMs(odometer.capturedWallMs, odometer.live);") && with.contains("odometer.unit, shown, seen, odometer.live)")
                        && with.contains("if (shown == odometer.number && seen == odometer.capturedWallMs) return readings;"),
                "Home's odometer reading carries that time");
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
