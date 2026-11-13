package com.jjricks.volt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * Charge done by / Scheduled charging and reading freshness out of sight, where status is asked for every 15 s.
 * Drives the real ChargeScheduleController with a context built the way ScooterService.chargeScheduleContext() builds it
 * (linked from StatusCadence.linked(), ready from linked() and ready()'s 7 s window since #418, readings and the switch by
 * StatusCadence.readingFreshMs(), toggleReason and timeReason from BleController's own control gate), on a fake monotonic clock. Listed in
 * tests/l10n/background-schedule.sources.
 */
public final class BackgroundScheduleTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long S = 1000L, T0 = 1_000_000L, STEP = 250L, REPLY = 300L, WALL0 = 1_792_472_400_000L;
    private static final String GATE = "Connect your scooter first";
    /** TelemetryHistory.freshnessMs for voltage_v and current_a (TelemetryHistory needs Android; source() checks the rule). */
    private static final long VOLTAGE_RULE_MS = 15_000L;

    public static void main(String[] args) throws Exception {
        source();
        readingAllowance();
        readingTimeline();
        scheduleViewStaysLinked();
        writesKeepTheControlGate();
        realLossBlocks();
        foregroundUnchanged();
        System.out.println("BackgroundScheduleTest: " + checks + " checks passed");
    }

    /** ready()'s age check, copied: statusMs>0&&now()-statusMs<=7000. BleController's control gate uses it. */
    private static boolean readyBefore(boolean up, long statusMs, long now) { return up && statusMs > 0 && now - statusMs <= 7000; }
    /** Volt's last 2 s read 1 s before the owner left at T0, then the reply to each 15 s poll from T0 + 15 s up to lastPoll. */
    private static long backgroundStatus(long t, long lastPoll) {
        long last = T0 - S;
        for (long p = T0 + StatusCadence.POLL_SLOW_MS; p <= lastPoll && p + REPLY <= t; p += StatusCadence.POLL_SLOW_MS) last = p + REPLY;
        return last;
    }

    /** The service sites this test models, as the source has them. */
    private static void source() throws Exception {
        String svc = read("src/com/jjricks/volt/ScooterService.java"), hist = read("src/com/jjricks/volt/TelemetryHistory.java");
        check(svc.contains("c.linked=same&&ble.linked();c.ready=c.linked&&ble.ready();"), "the schedule context's linked is linked(), its ready also needs ready()");
        check(svc.contains("c.enabled=simulation||ble.statusAge()<=ble.readingFreshMs(10_000)?ble.featureValue(\"timed_charging\"):null;"), "the switch, like the readings, only from a live status");
        check(svc.contains("now-metric.updatedMs<=ble.readingFreshMs(10_000)?metric.numericValue:null;"), "the schedule's readings use the cadence allowance");
        check(svc.contains("now - source <= ble.readingFreshMs(TelemetryHistory.freshnessMs(metric.key));"), "displayReadings' live flag too");
        check(svc.contains("now-metric.updatedMs>ble.readingFreshMs(TelemetryHistory.freshnessMs(key)))return false;"), "and the charge reference gate's");
        check(hist.contains("        return 15000;\n    }"), "TelemetryHistory's default rule (voltage, current) is 15 s");
    }
    private static String read(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- (b) reading freshness ----------------------------------------------------------------------------

    private static void readingAllowance() {
        for (long base : new long[]{0, 10 * S, 15 * S, 20 * S, 60 * S, 300 * S})
            check(StatusCadence.readingFreshMs(base, true) == base, "in sight a reading keeps its own rule: " + base);
        check(StatusCadence.readingFreshMs(15 * S, false) == 20 * S, "voltage and current: 15 s becomes one background poll plus 5 s");
        check(StatusCadence.readingFreshMs(10 * S, false) == 20 * S, "the schedule's 10 s rule too");
        check(StatusCadence.readingFreshMs(60 * S, false) == 60 * S && StatusCadence.readingFreshMs(300 * S, false) == 300 * S, "longer rules stay");
        check(StatusCadence.readingFreshMs(15 * S, false) < StatusCadence.linkedAgeMs(false), "a reading never outlives the link it came from");
        check(StatusCadence.readingFreshMs(VOLTAGE_RULE_MS, false) == 20 * S, "TelemetryHistory's voltage and current rule, out of sight");
        // The boundary: exactly at the allowance still live, one millisecond past it not.
        long allowed = StatusCadence.readingFreshMs(VOLTAGE_RULE_MS, false);
        check(fresh(T0, T0 + allowed, allowed) && !fresh(T0, T0 + allowed + 1, allowed), "20 000 ms is live, 20 001 ms is not");
    }
    private static boolean fresh(long readMs, long now, long allowedMs) { return readMs > 0 && now >= readMs && now - readMs <= allowedMs; }

    /** ChargeLiveBackgroundTest's timeline: the 15 s rule lapses before the next reply, the background allowance never does. */
    private static void readingTimeline() {
        long base = VOLTAGE_RULE_MS, lapsed = 0;
        for (long t = T0; t <= T0 + 10 * 60 * S; t += STEP) {
            long read = backgroundStatus(t, Long.MAX_VALUE);
            check(fresh(read, t, StatusCadence.readingFreshMs(base, false)), "voltage stays live out of sight at " + (t - T0) + " ms");
            if (!fresh(read, t, base)) lapsed++;
        }
        check(lapsed > 0, "the 15 s rule alone lapsed before a reply (the rare Estimating time left)");
        // A reply up to the margin late still counts; past it the reading is stale, before poll() ends the link.
        long read = T0, late = T0 + StatusCadence.POLL_SLOW_MS + StatusCadence.READING_MARGIN_MS;
        check(fresh(read, late, StatusCadence.readingFreshMs(base, false)) && !fresh(read, late + 1, StatusCadence.readingFreshMs(base, false)), "a reply 5 s late is the limit");
    }

    // ---- (a) the schedule context -------------------------------------------------------------------------

    /** The controller's store and driver; the driver stands in for BleController, whose own gate refuses on a stale status. */
    private static final class Rig implements ChargeScheduleController.Store, ChargeScheduleController.Driver {
        final Map<String, String> saved = new HashMap<>();
        final List<String> sent = new ArrayList<>(), refused = new ArrayList<>(), results = new ArrayList<>();
        final ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        final ChargeScheduleController controller = new ChargeScheduleController(this, this);
        boolean up = true, fast;
        long now = T0, statusMs = T0 - S;
        Consumer<Boolean> pending;
        Rig() { c.scope = "owner-A";c.generation = 1;c.zone = TimeZone.getTimeZone("America/Boise");at(T0); }
        /** chargeScheduleContext() at t: linked from linked(), ready also from ready(); the gate's reasons from ready(); readings from the status read. */
        void at(long t) {
            now = t;c.now = WALL0 + (t - T0);c.mono = t;
            c.linked = StatusCadence.linked(up, statusMs, t, fast);c.ready = c.linked && readyBefore(up, statusMs, t);
            boolean gate = up && (fast ? StatusCadence.linked(true, statusMs, t, true) : readyBefore(true, statusMs, t));
            c.toggleReason = gate ? null : GATE;c.timeReason = gate ? null : GATE;
            c.enabled = null;c.charging = null;c.soc = c.target = c.estimateMinutes = null;c.wireStart = null;
            if (!c.linked) return;
            boolean live = fresh(statusMs, t, StatusCadence.readingFreshMs(10_000, fast));
            c.enabled = live ? 1 : null;
            c.charging = live ? 1 : null;c.sampleMs = statusMs;
            c.soc = live ? 62d : null;c.target = live ? 100d : null;c.estimateMinutes = live ? 90d : null;
        }
        public String read(String key) { return saved.get(key); }
        public void write(String key, String value) { saved.put(key, value); }
        public ChargeScheduleController.Context context() { return c; }
        private void send(String what, Consumer<Boolean> done) {
            if (readyBefore(up, statusMs, now)) { sent.add(what);pending = done; }
            else { refused.add(what);done.accept(false); }
        }
        public void clock(Consumer<Boolean> done) { send("clock", done); }
        public void time(long wall, int offset, Consumer<Boolean> done) { send("time", done); }
        public void enabled(boolean enabled, Consumer<Boolean> done) { send(enabled ? "on" : "off", done); }
    }

    /** A schedule set on the scooter (the OEM app's), seen out of sight: Volt shows it as on at every step, charger and all. */
    private static void scheduleViewStaysLinked() {
        Rig r = new Rig();
        int oldOff = 0;
        for (long t = T0; t <= T0 + 10 * 60 * S; t += STEP) {
            r.statusMs = backgroundStatus(t, Long.MAX_VALUE);r.at(t);r.controller.observe();
            ChargeScheduleController.View v = r.controller.view();
            check(v.manualEnabled, "the scooter's schedule stays on out of sight at " + (t - T0) + " ms");
            check(v.chargerConnected, "and the charger stays connected at " + (t - T0) + " ms");
            if (!readyBefore(true, r.statusMs, t)) oldOff++;
        }
        check(oldOff > 1000, "ready()'s window read the scooter as gone for about half of those steps: " + oldOff);
        check(r.sent.isEmpty() && r.refused.isEmpty(), "watching the schedule never touches the radio");
    }

    /** Scooter writes keep the control gate: in a gap a write is refused before anything is sent; on a fresh status it goes out. */
    private static void writesKeepTheControlGate() {
        Rig r = new Rig();
        long gap = T0 + StatusCadence.POLL_SLOW_MS + REPLY + 10 * S;// 10 s after a reply: linked, but past ready()'s 7 s
        r.statusMs = backgroundStatus(gap, Long.MAX_VALUE);r.at(gap);
        check(r.c.linked && !r.c.ready && r.c.toggleReason != null, "the gap: the schedule sees the scooter, the control gate does not");
        r.controller.setDoneEnabled(true, r.results::add);
        // Since #418 the context's own ready answers before the gate is asked: the same refusal, as when not connected.
        check(r.sent.isEmpty() && r.refused.isEmpty() && "Connect the scooter first".equals(r.results.get(0)), "a write in the gap is refused before the radio, nothing sent: " + r.results);
        long freshAt = T0 + 2 * StatusCadence.POLL_SLOW_MS + REPLY + S;
        r.statusMs = backgroundStatus(freshAt, Long.MAX_VALUE);r.at(freshAt);
        r.controller.setDoneEnabled(true, r.results::add);
        check(r.sent.size() == 1 && "clock".equals(r.sent.get(0)), "on a fresh status the write goes out: " + r.sent);
        // The status ages into a gap mid-transaction: the controller doesn't call it a connection change, and the next
        // step is refused by the gate, so nothing more is sent and the owner hears it once.
        r.at(freshAt + 8 * S);r.controller.observe();
        check(r.controller.busy(), "a gap is not a connection change");
        Consumer<Boolean> done = r.pending;r.pending = null;done.accept(true);
        check(r.sent.size() == 1 && r.refused.size() == 1 && !r.controller.busy() && r.results.size() == 2 && r.results.get(1) != null,
                "the next step is refused, the transaction ends, reported once: " + r.results);
        check(!r.controller.view().doneByEnabled, "and Charge done by is not claimed");
    }

    /** A real loss (the link down, or no answer past the background limit) still reads as no scooter and blocks writes. */
    private static void realLossBlocks() {
        Rig r = new Rig();
        long t = T0 + 2 * 60 * S;
        r.statusMs = backgroundStatus(t, Long.MAX_VALUE);r.at(t);
        r.up = false;r.statusMs = 0;r.at(t + STEP);
        check(!r.c.linked && !r.c.ready && !r.controller.view().manualEnabled && !r.controller.view().chargerConnected, "link down: no scooter schedule, no charger");
        r.controller.setDoneEnabled(true, r.results::add);
        check(r.sent.isEmpty() && r.refused.isEmpty() && r.results.size() == 1 && r.results.get(0) != null, "link down: a write is refused before the radio: " + r.results);
        Rig silent = new Rig();
        long last = T0 + StatusCadence.POLL_SLOW_MS + REPLY;
        silent.statusMs = last;silent.at(last + StatusCadence.SILENCE_SLOW_MS);
        check(silent.c.linked, "silent but inside the background limit: still linked");
        silent.at(last + StatusCadence.SILENCE_SLOW_MS + 1);
        check(!silent.c.linked && !silent.c.ready && !silent.controller.view().manualEnabled, "silent past the background limit: no scooter");
        silent.controller.setManualEnabled(true, silent.results::add);
        check(silent.sent.isEmpty() && silent.refused.isEmpty() && silent.results.get(0) != null, "and nothing is sent");
    }

    /** In sight or recording: the context's ready and reading windows are ready()'s 7 s and the 10 s rule, as before. */
    private static void foregroundUnchanged() {
        for (long age = 0; age <= 40 * S; age += 50) {
            Rig r = new Rig();r.fast = true;r.statusMs = T0;r.at(T0 + age);
            check(r.c.ready == readyBefore(true, T0, T0 + age), "in sight ready is ready() at age " + age);
            check((r.c.charging != null) == (r.c.ready && age <= 10_000), "in sight readings keep the 10 s rule at age " + age);
        }
    }
}
