package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * The link is judged on the cadence it has actually been on, and the status poll moves to a new cadence at once
 * (StatusCadence.within/linked/silent/readingFreshMs and StatusCadence.Chain, used by BleController).
 *
 * Two faults from a tester's diagnostics: a healthy link was timed out 18 s after connecting whenever Volt was out of
 * sight by then (the connect timeout asked ready()'s 7 s window of a 15 s cadence), and after Volt came back into sight
 * the pending 15 s poll stayed, so ready() went false for several seconds and a schedule change in flight was
 * interrupted. The Rig below runs BleController's poll chain, connect timeout and silence check (source() pins those
 * lines) on a fake clock, with a scooter that answers each status read, and the real ChargeScheduleController on top.
 * Rig.old runs the code as it was, to show each scenario catches the fault. Listed in tests/l10n/link-freshness.sources.
 *
 * Two clocks: ages are on the elapsed clock, which counts deep sleep, and the Handler's timers wait on the awake clock,
 * which does not. Clock models both (sleep() moves only the elapsed one), and the sleep scenarios below show that the
 * link is judged on awake time while controls and live readings stay strict; Rig.HEAD runs the judgements as they were on
 * 5a4b1d02 (elapsed ages), where a phone asleep with a poll pending lost a healthy link on waking.
 */
public final class LinkFreshnessTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long S = 1000L, T0 = 1_000_000L, REPLY = 300L, STEP = 50L, WALL0 = 1_792_472_400_000L;

    public static void main(String[] args) throws Exception {
        // Names run only those parts of the sleep audit (to show each one failing against older sources).
        if (args.length > 0) { for (String a : args) audit(a);System.out.println("LinkFreshnessTest " + Arrays.toString(args) + ": " + checks + " checks passed");return; }
        source();
        rules();
        readingRuleIsExact();
        chainScheduling();
        connectOutOfSight();
        neverReadyStillTimesOut();
        returnToSight();
        scheduleAfterReturn();
        scheduleInFlightAtResume();
        silenceStillEndsTheLink();
        rapidFlips();
        wakeAfterSleep();
        goneDuringSleep();
        repeatedSleeps();
        sleepDuringCadenceChange();
        sleepDuringConnect();
        scheduleAcrossSleep();
        wakeWindowEdges();
        noRenewal();
        for (String a : AUDIT) audit(a);
        System.out.println("LinkFreshnessTest: " + checks + " checks passed");
    }

    // ---- The source lines the Rig mirrors ------------------------------------------------------------------------

    private static void source() throws Exception {
        String ble = read("src/com/jjricks/volt/BleController.java");
        check(ble.contains("now()-statusMs<=7000&&!serial.isEmpty();}"), "ready() keeps its plain 7 s: controls are admitted as before");
        check(ble.contains("if(session==generation&&gatt!=null&&!linkUp())failed(M.bleFailedTimedOut(),true,ConnectionEventLog.Reason.CONNECT_TIMEOUT,-1);},18000);"),
                "the connect timeout judges the link on its cadence and is still CONNECT_TIMEOUT");
        check(ble.contains("private boolean linkUp(){return cadence.linked(workAllowed()&&state==State.READY&&authenticated&&gatt!=null&&!serial.isEmpty(),statusMs,statusSleptMs);}"),
                "linkUp() is the chain's linked(): the cadence the link has been on, in awake time");
        check(ble.contains("@Override public long awake(){return SystemClock.uptimeMillis();}"), "the chain's awake clock is the one Handler timers wait on");
        check(ble.contains("private long statusSleptMs=cadence.slept(),frameSleptMs=statusSleptMs,pendingStatusSleptMs=statusSleptMs;"), "the sleep marks start at the current total, never 0");
        check(ble.contains("lastFrameMs=now();frameSleptMs=cadence.slept();"), "every frame is stamped with the sleep total");
        check(ble.contains("statusFrame=f;statusMs=now();statusSleptMs=cadence.slept();}else{pendingStatusFrame=f;pendingStatusMs=now();pendingStatusSleptMs=cadence.slept();}"),
                "every status read is stamped with the sleep total, a pending one too");
        check(ble.contains("statusMs=pendingStatusMs;statusSleptMs=pendingStatusSleptMs;"), "a pending status keeps its own mark when it is promoted");
        check(ble.contains("statusMs=motionMs=warningMs=lastFrameMs=0;statusSleptMs=frameSleptMs=pendingStatusSleptMs=cadence.slept();"), "close() resets the marks to the current total");
        check(ble.contains("private boolean silentLink(){return cadence.silent(lastFrameMs,frameSleptMs)||cadence.silent(statusMs,statusSleptMs);}"),
                "silence is the chain's silent(): frames and status, in awake time");
        check(ble.contains("public boolean linked(){if(simulating())return ready();return linkUp();}"), "linked() is linkUp(); simulation unchanged");
        check(ble.contains("public long readingFreshMs(long baseMs){return simulating()?StatusCadence.readingFreshMs(baseMs,true):StatusCadence.readingFreshMs(baseMs,cadence.fast(),cadence.fastSinceMs(),now());}"),
                "reading freshness on the cadence the link has been on; simulation keeps the in-sight rule");
        check(ble.contains("boolean switched=cadence.cadence(v||r);if(started&&discovering)stopDiscovery();if(!workAllowed())suspend();else if(switched&&authenticated&&gatt!=null&&lostToSilence())return;else if(resumed&&authenticated)refresh(false);if(switched)cadence.retime();}"),
                "visibility(): the cadence switches, silence is checked (READ_TIMEOUT), the resume refresh is queued, then the pending poll moves");
        check(ble.contains("private void poll(int session){if(simulating())return;cadence.start(()->pollTick(session));}"), "one chain per session, started at authentication");
        check(ble.contains("send(Protocol.frame(0x70,null));refresh(true);poll(generation);"), "the chain starts when the owner is authenticated");
        check(ble.contains("private boolean lostToSilence(){if(!silentLink())return false;failed(M.bleFailedSignalLost(),true,ConnectionEventLog.Reason.READ_TIMEOUT,-1);return true;}"),
                "a silent link ends as READ_TIMEOUT, as before");
        check(ble.contains("gatt==null)return;if(lostToSilence())return;"),
                "the poll's silence check judges frames and status on the cadence the link has been on, in awake time");
        check(ble.contains("public long readingFreshMs(long baseMs){return simulating()?StatusCadence.readingFreshMs(baseMs,true):StatusCadence.readingFreshMs(baseMs,cadence.fast(),cadence.fastSinceMs(),now());}")
                && ble.contains("public long statusAge(){return statusMs==0?Long.MAX_VALUE:now()-statusMs;}"), "readings and statusAge() stay on the elapsed clock");
        check(ble.contains("lastBatteryPollMs=now();}changed();cadence.next();}"), "each tick re-arms the one chain");
        check(ble.contains("++generation;generationStartMs=now();++writeOperation;cadence.stop();"), "close() ends the chain (disconnect, suspend, simulation, service end)");
        check(ble.contains("if(frame.length==7&&(frame[3]&255)==0x70)cadence.requested();"), "every queued status read counts as the last request");
        check(!ble.contains("StatusCadence.pollMs(visible||recording)") && !ble.contains("StatusCadence.silenceMs(visible||recording)"),
                "no poll or silence rule reads the flags at scheduling time any more");
        String svc = read("src/com/jjricks/volt/ScooterService.java");
        check(svc.contains("c.linked=same&&ble.linked();c.ready=c.linked&&ble.ready();"), "a schedule change in flight is judged by linked() (the context's linked; its ready is the write gate's)");
    }
    private static String read(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- The rules -----------------------------------------------------------------------------------------------

    private static boolean readyBefore(boolean up, long statusMs, long now) { return up && statusMs > 0 && now - statusMs <= 7000; }

    private static void rules() {
        for (long age = 0; age <= 60 * S; age += 250)
            for (boolean fast : new boolean[]{true, false}) {
                check(StatusCadence.linked(true, T0, T0 + age, fast, 0) == StatusCadence.linked(true, T0, T0 + age, fast), "never switched: the old rule at " + age);
                check(StatusCadence.linked(true, T0, T0 + age, fast, T0 - S) == StatusCadence.linked(true, T0, T0 + age, fast), "status after the switch: the old rule at " + age);
                check(StatusCadence.silent(T0, T0 + age, fast, 0) == (age > StatusCadence.silenceMs(fast)), "never switched: the old silence limit at " + age);
            }
        check(StatusCadence.linked(true, T0, T0 + 7 * S, true, 0) == readyBefore(true, T0, T0 + 7 * S), "in sight, settled: ready()'s 7 s exactly");
        // Status 14 s old when Volt comes into sight at R: still linked for 7 s after R, never past the slow rule.
        long r = T0 + 14 * S;
        check(!readyBefore(true, T0, r), "at the return a control is still refused (ready() unchanged)");
        check(StatusCadence.linked(true, T0, r, true, r) && StatusCadence.linked(true, T0, r + 7 * S, true, r), "the link is up for 7 s after the switch");
        check(!StatusCadence.linked(true, T0, r + 7 * S + 1, true, r), "then the fast rule applies: no fresh status, not linked");
        long late = T0 + 33 * S;
        check(StatusCadence.linked(true, T0, late + 2 * S, true, late) && !StatusCadence.linked(true, T0, T0 + StatusCadence.SILENCE_SLOW_MS + 1, true, late),
                "a return never stretches past the background limit");
        check(!StatusCadence.linked(false, T0, r, true, r) && !StatusCadence.linked(true, 0, r, true, r), "down, or no status: never linked");
        check(!StatusCadence.silent(T0, r + 12 * S, true, r) && StatusCadence.silent(T0, r + 12 * S + 1, true, r), "silence after a return: 12 s from the switch");
        check(StatusCadence.silent(T0, T0 + StatusCadence.SILENCE_SLOW_MS + 1, true, late), "or the background limit, whichever comes first");
        check(!StatusCadence.silent(0, r, true, r), "nothing yet is not silence");
        // The poll after a cadence change: one period after the last request, never later than one period from now.
        check(StatusCadence.nextPollMs(0, r, true) == 0, "no request yet: poll now");
        check(StatusCadence.nextPollMs(r - 10 * S, r, true) == 0, "last request 10 s ago, now fast: poll now");
        check(StatusCadence.nextPollMs(r, r, true) == 2 * S, "the resume refresh just went: the next poll 2 s after it");
        check(StatusCadence.nextPollMs(r - 1500, r, false) == 13_500, "fast to slow: 15 s after the last request");
        check(StatusCadence.nextPollMs(r + S, r, true) == 2 * S, "never later than one period from now");
    }

    /** readingFreshMs(base, fast, fastSince, now) is exactly within() for every reading time. */
    private static void readingRuleIsExact() {
        for (long base : new long[]{0, 7 * S, 10 * S, 15 * S, 20 * S, 60 * S})
            for (long since : new long[]{0, T0, T0 + 5 * S, T0 + 20 * S})
                for (long now = T0; now <= T0 + 70 * S; now += 500)
                    for (long read = T0 - 40 * S; read <= now; read += 500)
                        for (boolean fast : new boolean[]{true, false}) {
                            if (since > now) continue;
                            long bound = StatusCadence.readingFreshMs(base, fast, since, now);
                            boolean exact = StatusCadence.within(read, now, fast, since, base, StatusCadence.readingFreshMs(base, false));
                            check((read > 0 && now - read <= bound) == exact, "reading rule base " + base + " since " + since + " now " + now + " read " + read);
                        }
        check(StatusCadence.readingFreshMs(10 * S, true, 0, T0) == 10 * S && StatusCadence.readingFreshMs(10 * S, true, T0 - 11 * S, T0) == 10 * S,
                "in sight and settled: the reading's own rule");
        check(StatusCadence.readingFreshMs(10 * S, true, T0, T0 + 3 * S) == 20 * S, "just back in sight: the background allowance");
    }

    // ---- A fake main-thread Handler ----------------------------------------------------------------------------

    /**
     * now: the elapsed clock (SystemClock.elapsedRealtime, ages); up: the awake clock (uptimeMillis), which the Handler's
     * delays wait on. Both move together while awake; sleep() moves only now, and nothing runs during it. Up starts well
     * below now (a phone that has slept before), so a mixed-up clock shows.
     */
    static final class Clock {
        long now, up;
        private long seq;
        private final List<long[]> keys = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();
        Clock(long now) { this.now = now;this.up = now - 600 * S; }
        void post(Runnable r, long delay) { keys.add(new long[]{up + Math.max(0, delay), seq++}); tasks.add(r); }
        void cancel(Runnable r) { for (int i = tasks.size() - 1; i >= 0; i--) if (tasks.get(i) == r) { tasks.remove(i); keys.remove(i); } }
        /** Awake until the elapsed clock reads t. */
        void runUntil(long t) {
            long target = up + (t - now);
            while (true) {
                int best = -1;
                for (int i = 0; i < keys.size(); i++)
                    if (keys.get(i)[0] <= target && (best < 0 || keys.get(i)[0] < keys.get(best)[0] || keys.get(i)[0] == keys.get(best)[0] && keys.get(i)[1] < keys.get(best)[1])) best = i;
                if (best < 0) break;
                long at = keys.get(best)[0];now += at - up;up = at;keys.remove(best);tasks.remove(best).run();
            }
            now = t;up = target;
        }
        /** Deep sleep: the elapsed clock moves, the awake clock and every timer stand still. */
        void sleep(long ms) { now += ms; }
    }

    /** The chain's tasks, to count pending polls. */
    static final class ChainTimer implements StatusCadence.Timer {
        final Clock clock;
        final Map<Runnable, Runnable> live = new IdentityHashMap<>();
        ChainTimer(Clock clock) { this.clock = clock; }
        public long now() { return clock.now; }
        public long awake() { return clock.up; }
        public void post(Runnable task, long delayMs) {
            Runnable wrapped = () -> { live.remove(task); task.run(); };
            live.put(task, wrapped);clock.post(wrapped, delayMs);
        }
        public void cancel(Runnable task) { Runnable w = live.remove(task); if (w != null) clock.cancel(w); }
    }

    private static void chainScheduling() {
        Clock clock = new Clock(T0);ChainTimer timer = new ChainTimer(clock);StatusCadence.Chain chain = new StatusCadence.Chain(timer);
        List<Long> ticks = new ArrayList<>();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> { ticks.add(clock.now);chain.requested();chain.next(); };
        chain.start(tick[0]);
        check(timer.live.size() == 1 && chain.running(), "start: one pending poll");
        clock.runUntil(T0 + 46 * S);
        check(ticks.equals(Arrays.asList(T0 + 15 * S, T0 + 30 * S, T0 + 45 * S)), "slow: every 15 s " + ticks);
        // In sight at T0 + 50 s with a refresh just queued: the next poll 2 s after it, then every 2 s.
        clock.runUntil(T0 + 50 * S);ticks.clear();
        check(chain.cadence(true) && chain.fastSinceMs() == T0 + 50 * S, "the switch is recorded");
        chain.requested();chain.retime();
        check(timer.live.size() == 1, "retime replaces the pending poll");
        clock.runUntil(T0 + 57 * S);
        check(ticks.equals(Arrays.asList(T0 + 52 * S, T0 + 54 * S, T0 + 56 * S)), "fast from the switch: " + ticks);
        // Out of sight: the 2 s chain does not continue; the next poll is 15 s after the last request.
        ticks.clear();clock.runUntil(T0 + 57_500);
        check(chain.cadence(false), "the switch to slow");chain.retime();
        clock.runUntil(T0 + 90 * S);
        check(ticks.equals(Arrays.asList(T0 + 71 * S, T0 + 86 * S)), "slow from the last request at 56 s: " + ticks);
        // A recording starts out of sight with an old last request: the retimed poll is due now; the ride start's own
        // refresh queued in the same instant moves it once, to 2 s after that refresh.
        ticks.clear();clock.runUntil(T0 + 95 * S);
        chain.cadence(true);chain.retime();chain.requested();
        clock.runUntil(T0 + 100 * S);
        check(ticks.equals(Arrays.asList(T0 + 97 * S, T0 + 99 * S)), "no doubled read after the ride start's refresh: " + ticks);
        // Stop: nothing pending, nothing runs, retime does nothing.
        chain.stop();ticks.clear();
        check(!chain.running() && timer.live.isEmpty(), "stop leaves nothing pending");
        chain.cadence(false);chain.retime();chain.cadence(true);chain.retime();chain.next();
        clock.runUntil(T0 + 200 * S);
        check(ticks.isEmpty() && timer.live.isEmpty(), "a stopped chain never restarts by itself");
        chain.start(tick[0]);chain.start(tick[0]);
        check(timer.live.size() == 1, "starting twice still leaves one chain");
    }

    // ---- BleController's link, on a fake clock ------------------------------------------------------------------

    static final int NEW = 0, MAIN = 1, HEAD = 2;

    /**
     * BleController's connect(), authentication, poll chain, silence check and visibility(), with a scooter that answers
     * each status read after reply ms (on the awake clock) while answering is set. Modes: NEW, this code; MAIN (old): the
     * code before this pull request (ready() for the connect timeout, flags read when each poll was scheduled, no retime,
     * the in-sight rule at once); HEAD: 5a4b1d02, this chain with the link judged on elapsed ages and no check at a switch.
     */
    static final class Rig {
        final boolean old, head;
        final Clock clock = new Clock(T0);
        final ChainTimer timer = new ChainTimer(clock);
        final StatusCadence.Chain chain = new StatusCadence.Chain(timer);
        boolean visible, recording, up, answering = true, authenticates = true, oldWouldTimeOut;
        int generation, maxPending;
        long statusMs, lastFrameMs, statusSlept, frameSlept, failedAt = -1, failedUp = -1, statusUp, reply = REPLY;
        final List<String> log = new ArrayList<>();
        final List<Long> requests = new ArrayList<>();
        /** Runs on every status reply (a step's readback). */
        final List<Runnable> onStatus = new ArrayList<>();
        /**
         * Battery reads, when batteryPolicy is set: BleController.pollTick's and refresh(false)'s periodicBattery, through
         * ReadPolicy on the awake clock (sourceAudit4 pins the lines); out of sight with no battery watch (watch) and no
         * recording, pollTick's noticeBattery, sent quietly. batteryOutOfSight false is fde6403f's rule (in sight, recording
         * or watching only); noticeOnly false is 4164feba's (periodicBattery with every poll out of sight, counted as any
         * read); batteryAnswers false is a scooter that ignores the battery reads, ignored one that ignores those opcodes.
         */
        ReadPolicy batteryPolicy;
        boolean batteryOutOfSight = true, batteryAnswers = true, noticeOnly = true, watch;
        final Set<Integer> ignored = new HashSet<>();
        long batteryMs, lastBatteryPoll;
        final List<Long> batteryReads = new ArrayList<>();
        final List<Integer> batteryCmds = new ArrayList<>();
        void readBattery() { readBattery(false); }
        void readBattery(boolean notice) {
            for (int cmd : notice ? batteryPolicy.noticeBattery() : batteryPolicy.periodicBattery()) {
                batteryPolicy.requested(cmd, clock.up, notice);batteryReads.add(now());batteryCmds.add(cmd);int gen = generation;
                if (answering && batteryAnswers && !ignored.contains(cmd)) clock.post(() -> { if (gen == generation && up && answering && batteryAnswers) { batteryPolicy.responded(cmd, 0, cmd == 0x79 ? 2 : 36, clock.up);if (cmd != 0x79) batteryMs = now(); } }, reply);
            }
        }
        /** The connected notice's level: linked, and the battery reading live by its own rule (BleController.liveNumber, 15 s). */
        boolean levelShown() { return linkUp() && StatusCadence.live(batteryMs, now(), StatusCadence.readingFreshMs(15 * S, chain.fast(), chain.fastSinceMs(), now())); }
        Rig(boolean old) { this(old ? MAIN : NEW); }
        Rig(int mode) { old = mode == MAIN;head = mode == HEAD; }
        long now() { return clock.now; }
        boolean fast() { return visible || recording; }
        boolean ready() { return readyBefore(up, statusMs, now()); }
        boolean linkUp() {
            if (old) return fast() ? ready() : StatusCadence.linked(up, statusMs, now(), false);
            if (head) return StatusCadence.linked(up, statusMs, now(), chain.fast(), chain.fastSinceMs());
            return chain.linked(up, statusMs, statusSlept);
        }
        /** A reading taken at readMs is live under BleController.readingFreshMs (elapsed clock). */
        boolean readingLive(long readMs, long baseMs) { return readMs > 0 && now() - readMs <= StatusCadence.readingFreshMs(baseMs, chain.fast(), chain.fastSinceMs(), now()); }
        boolean silentNow() {
            if (head) {
                long since = chain.fastSinceMs();boolean f = chain.fast();
                return StatusCadence.silent(lastFrameMs, now(), f, since) || StatusCadence.silent(statusMs, now(), f, since);
            }
            return chain.silent(lastFrameMs, frameSlept) || chain.silent(statusMs, statusSlept);
        }
        void status() {
            statusMs = lastFrameMs = now();statusSlept = frameSlept = chain.slept();statusUp = clock.up;
            for (Runnable r : new ArrayList<>(onStatus)) r.run();
        }
        /** A pushed frame (motion, say): not a status read. */
        void frame() { if (up) { lastFrameMs = now();frameSlept = chain.slept(); } }
        void connect() {
            int gen = ++generation;chain.stop();up = false;statusMs = lastFrameMs = 0;statusSlept = frameSlept = chain.slept();log.add("CONNECT_ATTEMPT");
            if (authenticates) clock.post(() -> {
                if (gen != generation) return;
                up = true;frame();requestStatus();if (batteryPolicy != null) readBattery();
                if (old) oldPoll(gen); else chain.start(() -> tick(gen));
            }, 2500);
            clock.post(() -> {
                if (gen != generation) return;
                oldWouldTimeOut = !ready();
                if (old ? !ready() : !linkUp()) fail("CONNECT_TIMEOUT");
            }, 18_000);
        }
        void requestStatus() {
            chain.requested();requests.add(now());int gen = generation;
            if (answering) clock.post(() -> { if (gen == generation && up && answering) status(); }, reply);
        }
        void tick(int gen) {
            if (gen != generation || !up) return;
            if (silentNow()) { fail("READ_TIMEOUT"); return; }
            if (batteryPolicy != null) batteryPolicy.tick(clock.up);
            requestStatus();
            if (batteryPolicy != null && (batteryOutOfSight || fast() || watch) && now() - lastBatteryPoll >= 2000) { readBattery(noticeOnly && !fast() && !watch);lastBatteryPoll = now(); }
            chain.next();
        }
        /** The old poll(): the flags read when each poll is scheduled, the silence limit when it runs. */
        void oldPoll(int gen) {
            clock.post(() -> {
                if (gen != generation || !up) return;
                long limit = StatusCadence.silenceMs(fast());
                if (lastFrameMs > 0 && now() - lastFrameMs > limit || statusMs > 0 && now() - statusMs > limit) { fail("READ_TIMEOUT"); return; }
                requestStatus();oldPoll(gen);
            }, StatusCadence.pollMs(fast()));
        }
        void fail(String reason) { log.add(reason);if (failedAt < 0) { failedAt = now();failedUp = clock.up; }++generation;chain.stop();up = false;statusMs = lastFrameMs = 0; }
        void visibility(boolean v, boolean r) {
            boolean resumed = v && !visible;visible = v;recording = r;
            boolean switched = chain.cadence(v || r);
            if (switched && !old && !head && up && silentNow()) { fail("READ_TIMEOUT"); return; }
            if (resumed && up) { requestStatus();if (batteryPolicy != null) readBattery(); }
            if (switched && !old) chain.retime();
        }
        void sleep(long ms) { clock.sleep(ms); }
        void run(long until) { for (long t = now(); t < until; ) { t = Math.min(until, t + STEP);clock.runUntil(t);maxPending = Math.max(maxPending, timer.live.size()); } }
        boolean failed() { return log.contains("CONNECT_TIMEOUT") || log.contains("READ_TIMEOUT"); }
    }

    /** (a) Connect, then Volt goes out of sight at any moment before 18 s (or was never in sight): no timeout. */
    private static void connectOutOfSight() {
        int oldTimeouts = 0;
        for (long leave = -1; leave <= 18 * S; leave += 250) {
            for (boolean old : new boolean[]{false, true}) {
                Rig r = new Rig(old);r.visible = leave >= 0;r.chain.cadence(r.visible);
                r.connect();
                if (leave >= 0) { r.run(T0 + leave);r.visibility(false, false); }
                r.run(T0 + 18 * S + 1);
                if (old) { if (r.log.contains("CONNECT_TIMEOUT")) oldTimeouts++; continue; }
                check(!r.failed(), "left at " + leave + ": no timeout at 18 s " + r.log);
                check(r.linkUp(), "and the link is up at 18 s (left at " + leave + ")");
                r.run(T0 + 5 * 60 * S);
                check(!r.failed() && r.linkUp() && r.maxPending <= 1, "healthy for five minutes out of sight, one chain (left at " + leave + ") " + r.log);
            }
        }
        check(oldTimeouts > 20, "the old rule timed a healthy link out in many of these: " + oldTimeouts);
        // In sight throughout: unchanged, ready at 18 s.
        Rig in = new Rig(false);in.visible = true;in.chain.cadence(true);in.connect();in.run(T0 + 18 * S + 1);
        check(!in.failed() && in.ready(), "in sight: ready at 18 s, no timeout");
        // Out of sight, back in sight at 17 s: no timeout either.
        Rig back = new Rig(false);back.connect();back.run(T0 + 17 * S);back.visibility(true, false);back.run(T0 + 60 * S);
        check(!back.failed() && back.ready(), "back in sight just before the timeout: no timeout " + back.log);
    }

    /**
     * A link that never becomes ready still ends by 18 s, in sight, out of sight, or leaving in between, exactly as
     * before: CONNECT_TIMEOUT at 18 s, or (in sight with frames but no status) the silence check a little earlier.
     */
    private static void neverReadyStillTimesOut() {
        for (int how = 0; how < 2; how++)
            for (long leave : new long[]{-1, 0, 5 * S, 17 * S}) {
                Rig[] both = {new Rig(false), new Rig(true)};
                for (Rig r : both) {
                    r.visible = leave >= 0;r.chain.cadence(r.visible);
                    if (how == 0) r.authenticates = false; else r.answering = false;
                    r.connect();
                    if (leave >= 0) { r.run(T0 + leave);r.visibility(false, false); }
                    r.run(T0 + 18 * S);
                }
                Rig r = both[0];
                String what = (how == 0 ? "authentication stalls" : "no status ever") + ", left at " + leave;
                check(r.failed() && r.failedAt <= T0 + 18 * S, what + ": the link ends by 18 s " + r.log);
                check(r.log.equals(both[1].log) && r.failedAt == both[1].failedAt, what + ": as before " + r.log + " " + both[1].log);
                if (how == 0 || leave <= 5 * S) check(r.log.contains("CONNECT_TIMEOUT") && r.failedAt == T0 + 18 * S, what + ": CONNECT_TIMEOUT at 18 s " + r.log);
            }
        Rig never = new Rig(false);never.visible = true;never.chain.cadence(true);never.authenticates = false;never.connect();never.run(T0 + 18 * S);
        check(never.log.contains("CONNECT_TIMEOUT"), "in sight throughout: CONNECT_TIMEOUT at 18 s");
    }

    /** (b) Out of sight for more than 15 s, then in sight at every phase of the 15 s poll. */
    private static void returnToSight() {
        int oldGaps = 0;
        for (long phase = 0; phase < StatusCadence.POLL_SLOW_MS; phase += 250) {
            for (boolean old : new boolean[]{false, true}) {
                Rig r = new Rig(old);r.connect();
                long back = T0 + 60 * S + phase;
                r.run(back);
                long age = r.now() - r.statusMs;
                int before = r.requests.size();
                r.visibility(true, false);
                if (old) {
                    for (long t = back + REPLY; t <= back + 30 * S; t += STEP) { r.run(t);if (!r.ready()) { oldGaps++;break; } }
                    continue;
                }
                check(r.linkUp(), "linked at the instant of the return (phase " + phase + ")");
                check(r.ready() == (age <= 7000), "a control at the instant of the return is admitted only on a 7 s status (phase " + phase + ")");
                for (long t = back + STEP; t <= back + 60 * S; t += STEP) {
                    r.run(t);
                    check(r.linkUp(), "linked after the return at +" + (t - back) + " (phase " + phase + ")");
                    if (t >= back + REPLY) {
                        check(r.ready(), "ready at +" + (t - back) + " (phase " + phase + ")");
                        check(r.now() - r.statusMs <= StatusCadence.POLL_FAST_MS + REPLY, "status never past 2.3 s at +" + (t - back));
                    }
                }
                long inFirstPeriod = r.requests.subList(before, r.requests.size()).stream().filter(t -> t < back + StatusCadence.POLL_FAST_MS).count();
                check(inFirstPeriod == 1, "one status read in the first 2 s: the resume refresh (phase " + phase + ")");
                check(!r.failed() && r.maxPending <= 1, "no failure, one chain (phase " + phase + ")");
            }
        }
        check(oldGaps > 20, "the old chain let ready() lapse after a return: " + oldGaps);
    }

    // ---- The schedule on top ------------------------------------------------------------------------------------

    /**
     * ChargeScheduleController with ScooterService's context and BleController's gate (ready()). The context has two
     * answers: linked = linked(), ready = linked() && ready(); the switch and the readings come from the last status, each
     * only while live (readingFreshMs(10 s)). oneAnswer is fde6403f's context: ready = linked(), and the switch read with no
     * age check.
     */
    static final class Schedule implements ChargeScheduleController.Store, ChargeScheduleController.Driver {
        final Rig rig;
        boolean oneAnswer, chargingReadings;
        /** With chargingReadings: the start the scooter holds, as last written. */
        Long wireSent;
        final Map<String, String> saved = new HashMap<>();
        final List<String> sent = new ArrayList<>(), refused = new ArrayList<>(), results = new ArrayList<>();
        final ChargeScheduleController controller = new ChargeScheduleController(this, this);
        final TimeZone zone = TimeZone.getTimeZone("America/Boise");
        long clockAck = 400, timeAck = 400, enableAck = 400;
        /** The switch as the scooter reads it back: on once an ON is acknowledged. */
        int switchRead = 0;
        /** Each step is confirmed by the next status read after it (its readback), not by a timer. */
        boolean ackOnStatus;
        private final List<Runnable> readbacks = new ArrayList<>();
        Schedule(Rig rig) {
            this.rig = rig;
            rig.onStatus.add(() -> { List<Runnable> due = new ArrayList<>(readbacks);readbacks.clear();for (Runnable r : due) r.run(); });
        }
        public ChargeScheduleController.Context context() {
            ChargeScheduleController.Context c = new ChargeScheduleController.Context();
            long t = rig.now();c.now = WALL0 + (t - T0);c.mono = t;c.zone = zone;c.scope = "owner-A";c.generation = rig.generation;
            boolean linked = rig.linkUp(), live = rig.readingLive(rig.statusMs, 10 * S);
            c.linked = !oneAnswer && linked;c.ready = oneAnswer ? linked : linked && rig.ready();
            boolean gate = rig.ready();c.toggleReason = gate ? null : "Connect your scooter first";c.timeReason = c.toggleReason;
            if (!linked) return c;
            c.enabled = oneAnswer || live ? switchRead : null;
            if (live) c.wireStart = chargingReadings && wireSent != null ? wireSent : (WALL0 + 3 * 3600 * S) / 1000 + zone.getOffset(WALL0) / 1000;
            // Native scheduled charging pauses the current: charging until the switch is on.
            if (live && chargingReadings) { c.charging = switchRead == 1 ? 0 : 1;c.soc = 79d;c.target = 100d;c.estimateMinutes = 90d;c.sampleMs = rig.statusMs; }
            return c;
        }
        public String read(String key) { return saved.get(key); }
        public void write(String key, String value) { saved.put(key, value); }
        private void send(String what, long ack, Consumer<Boolean> done) {
            if (!rig.ready()) { refused.add(what);done.accept(false);return; }
            sent.add(what);int gen = rig.generation;
            if (ackOnStatus) { readbacks.add(() -> done.accept(gen == rig.generation));return; }
            rig.clock.post(() -> done.accept(gen == rig.generation), ack);
        }
        public void clock(Consumer<Boolean> done) { send("clock", clockAck, done); }
        public void time(long wall, int offset, Consumer<Boolean> done) { send("time", timeAck, ok -> { if (ok) wireSent = wall / 1000 + offset;done.accept(ok); }); }
        public void enabled(boolean enabled, Consumer<Boolean> done) { send(enabled ? "on" : "off", enableAck, ok -> { if (ok) switchRead = enabled ? 1 : 0;done.accept(ok); }); }
        /** Runs the rig, observing on every step as the service does on each change. */
        void run(long until) { for (long t = rig.now(); t < until; ) { t = Math.min(until, t + STEP);rig.clock.runUntil(t);controller.observe(); } }
    }

    /** A Scheduled charging change started 6 to 8 s after the return completes. */
    private static void scheduleAfterReturn() {
        int oldInterrupted = 0;
        for (long phase = 0; phase < StatusCadence.POLL_SLOW_MS; phase += 1000)
            for (long after = 6 * S; after <= 8 * S; after += 500)
                for (boolean old : new boolean[]{false, true}) {
                    Rig r = new Rig(old);Schedule s = new Schedule(r);r.connect();
                    long back = T0 + 60 * S + phase;
                    s.run(back);r.visibility(true, false);s.run(back + after);
                    s.controller.setManualEnabled(true, s.results::add);
                    s.run(back + after + 10 * S);
                    if (old) { if (!s.results.isEmpty() && s.results.get(0) != null) oldInterrupted++; continue; }
                    check(s.results.size() == 1 && s.results.get(0) == null, "started " + after + " ms after the return (phase " + phase + "): completed " + s.results + " " + s.refused);
                    check(s.sent.equals(Arrays.asList("clock", "time", "on")) && s.refused.isEmpty(), "clock, time and the switch all went out " + s.sent);
                    check(s.controller.view().manualEnabled && !s.controller.busy(), "Scheduled charging is on");
                }
        check(oldInterrupted > 0, "the old chain refused or interrupted some of these: " + oldInterrupted);
    }

    /** A change started out of sight is still in flight when Volt is opened, with the last status 8 s old. */
    private static void scheduleInFlightAtResume() {
        for (boolean old : new boolean[]{false, true}) {
            Rig r = new Rig(old);Schedule s = new Schedule(r);r.connect();
            // The reply to the background poll at 2.5 + 4 x 15 s arrives at REPLY after it.
            long reply = T0 + 2500 + 4 * StatusCadence.POLL_SLOW_MS + REPLY;
            s.run(reply + 500);
            s.timeAck = 8 * S;// a slow readback keeps the time step in flight across the return
            s.controller.setManualEnabled(true, s.results::add);
            s.run(reply + 8 * S);
            check(s.controller.busy() && r.now() - r.statusMs == 8 * S, "in flight, last status 8 s old");
            r.visibility(true, false);s.controller.observe();
            s.run(reply + 20 * S);
            if (old) { check(s.results.size() == 1 && s.results.get(0) != null && s.sent.equals(Arrays.asList("clock", "time")), "the old rule interrupted it at the return " + s.results); continue; }
            check(s.results.size() == 1 && s.results.get(0) == null && s.sent.equals(Arrays.asList("clock", "time", "on")), "not interrupted: it completed " + s.results + " " + s.sent);
        }
        // A genuinely lost link still interrupts and sends nothing more.
        Rig r = new Rig(false);Schedule s = new Schedule(r);r.visible = true;r.chain.cadence(true);r.connect();
        s.run(T0 + 10 * S);s.timeAck = 5 * S;s.controller.setManualEnabled(true, s.results::add);s.run(T0 + 11 * S);
        r.fail("NATIVE_DISCONNECT");s.run(T0 + 20 * S);
        check(s.results.size() == 1 && s.results.get(0) != null && s.sent.equals(Arrays.asList("clock", "time")), "a lost link interrupts, nothing more is sent " + s.results + " " + s.sent);
    }

    /** Silence is still caught within the existing limits, also across a return and while pushed frames continue. */
    private static void silenceStillEndsTheLink() {
        for (int where = 0; where < 3; where++) {
            Rig r = new Rig(false);r.visible = where == 0;r.chain.cadence(r.visible);r.connect();
            r.run(T0 + 60 * S);
            long lastStatus = r.statusMs;r.answering = false;
            // Pushed frames (motion) keep coming every second; only the status stops.
            long[] push = {0};
            Runnable[] p = new Runnable[1];
            p[0] = () -> { if (r.up) { r.lastFrameMs = r.now();push[0]++;r.clock.post(p[0], S); } };
            r.clock.post(p[0], S);
            long back = r.now() + 5 * S;// the last status is then about 17 s old
            if (where == 2) { r.run(back);r.visibility(true, false); }
            r.run(T0 + 5 * 60 * S);
            check(r.log.contains("READ_TIMEOUT") && push[0] > 5, "status silent while frames continue: lost " + r.log);
            long lostAfter = r.failedAt - lastStatus;
            if (where == 0) check(lostAfter > StatusCadence.SILENCE_FAST_MS && lostAfter <= StatusCadence.SILENCE_FAST_MS + StatusCadence.POLL_FAST_MS, "in sight: lost after 12 s, by the next poll: " + lostAfter);
            if (where == 1) check(lostAfter > StatusCadence.SILENCE_SLOW_MS && lostAfter <= StatusCadence.SILENCE_SLOW_MS + StatusCadence.POLL_SLOW_MS, "out of sight: after 35 s, by the next poll: " + lostAfter);
            if (where == 2) check(r.failedAt - back > StatusCadence.SILENCE_FAST_MS && r.failedAt - back <= StatusCadence.SILENCE_FAST_MS + StatusCadence.POLL_FAST_MS,
                    "back in sight: 12 s after the return, by the next poll: " + (r.failedAt - back));
        }
    }

    /** Rapid flips, a rotation, and a recording starting out of sight: one chain at the right rate, no doubled reads. */
    private static void rapidFlips() {
        Rig r = new Rig(false);r.connect();r.run(T0 + 60 * S);
        long f = r.now();int before = r.requests.size();
        r.visibility(true, false);r.run(f + 300);r.visibility(false, false);r.run(f + 600);r.visibility(true, false);
        r.run(f + 900);r.visibility(false, false);r.run(f + 1000);r.visibility(true, false);
        r.run(f + 11 * S);
        List<Long> after = r.requests.subList(before, r.requests.size());
        // Three resumes, three refreshes (as today); then one poll every 2 s from the last one.
        check(after.size() == 3 + 5, "three refreshes and five polls in 11 s: " + after);
        for (int i = 4; i < after.size(); i++) check(after.get(i) - after.get(i - 1) == StatusCadence.POLL_FAST_MS, "2 s apart: " + after);
        check(r.maxPending <= 1 && !r.failed() && r.ready(), "one chain, healthy");
        // Out of sight again: the next read 15 s after the last one, not a 2 s chain.
        long last = r.requests.get(r.requests.size() - 1);r.visibility(false, false);before = r.requests.size();
        r.run(last + 61 * S);
        List<Long> slow = r.requests.subList(before, r.requests.size());
        check(slow.equals(Arrays.asList(last + 15 * S, last + 30 * S, last + 45 * S, last + 60 * S)), "out of sight: every 15 s from the last read " + slow);
        // A recording starts out of sight; the ride start's refresh goes in the same instant: one read, then every 2 s.
        long start = r.now();before = r.requests.size();
        r.visibility(false, true);r.requestStatus();
        r.run(start + 7 * S);
        List<Long> rec = r.requests.subList(before, r.requests.size());
        check(rec.equals(Arrays.asList(start, start + 2 * S, start + 4 * S, start + 6 * S)), "recording: the refresh, then every 2 s " + rec);
        check(r.maxPending <= 1 && !r.failed() && r.ready(), "still one chain, healthy");
        // Rotation: pause and resume in the same instant, repeatedly.
        for (int i = 0; i < 5; i++) { r.visibility(false, false);r.visibility(true, false); }
        r.run(r.now() + 10 * S);
        check(r.maxPending <= 1 && !r.failed() && r.ready() && r.now() - r.statusMs <= StatusCadence.POLL_FAST_MS + REPLY, "rotations: one chain, fresh status");
    }

    // ---- Sleep: the link judged on awake time -----------------------------------------------------------------------

    private static final long[] GAPS = {60 * S, 10 * 60 * S, 3 * 3600 * S};
    private static final long[] WAKE_REPLIES = {100, 1000, 3000, 6000};
    /** When the phone sleeps: just after the background reply at 47.8 s, mid-cycle, and just before the poll at 62.5 s. */
    private static final long[] SLEEP_AT = {48 * S, 55 * S, 62_400};
    private static final long NEXT_POLL = 62_500;

    /**
     * A healthy scooter, background connection on, Volt out of sight: the phone sleeps with the slow poll pending, for 1
     * minute, 10 minutes or 3 hours, and wakes. Either the poll finally fires out of sight, or Volt comes back into sight
     * first. The scooter answers the first request after the wake in 0.1, 1, 3 or 6 s: the link survives and stays linked,
     * a control is refused until that answer (ready() stays strict) and admitted from it, and a reading from before the
     * sleep is not live. On 5a4b1d02 the late poll ended every one of these links, and the return ended those whose answer
     * took longer than the first 2 s poll.
     */
    private static void wakeAfterSleep() {
        int headLost = 0, headCases = 0;
        for (long gap : GAPS) for (long sleepAt : SLEEP_AT) for (boolean back : new boolean[]{false, true}) for (long reply : WAKE_REPLIES)
            for (int mode : new int[]{NEW, HEAD}) {
                String what = "asleep at " + sleepAt + " for " + gap + (back ? ", back in sight" : ", out of sight") + ", answer in " + reply;
                Rig r = new Rig(mode);r.connect();r.run(T0 + sleepAt);
                long before = r.statusMs;int sent = r.requests.size();
                check(before == T0 + 47_800 && r.linkUp() && r.timer.live.size() == 1, "a healthy background link, a poll pending: " + what);
                r.sleep(gap);r.reply = reply;
                long wake = r.now();
                if (back) r.visibility(true, false);
                if (mode == HEAD) {
                    r.run(wake + 60 * S);headCases++;
                    check(r.failed() == (!back || reply > StatusCadence.POLL_FAST_MS), "5a4b1d02: " + what + " " + r.log);
                    if (r.failed()) headLost++;
                    continue;
                }
                check(r.linkUp() && !r.ready(), "at the wake: linked, but a control is refused on the old status: " + what);
                check(!r.readingLive(before, 10 * S) && !r.readingLive(before, 15 * S), "a reading from before the sleep is not live: " + what);
                long first = back ? wake : wake + NEXT_POLL - sleepAt, answer = first + reply;
                boolean admitted = false;
                for (long t = wake + STEP; t <= wake + 60 * S; t += STEP) {
                    r.run(t);
                    check(r.linkUp(), "linked at +" + (t - wake) + ": " + what + " " + r.log);
                    if (t < answer) check(!r.ready() && !r.readingLive(before, 10 * S), "no control and no live reading before the answer, at +" + (t - wake) + ": " + what);
                    if (t == answer) admitted = r.ready() && r.statusMs == answer;
                    if (back && t >= answer) check(r.ready(), "in sight, ready from the answer on, at +" + (t - wake) + ": " + what);
                }
                check(admitted, "ready when the answer arrives: " + what);
                check(!r.failed() && r.maxPending <= 1, "no failure, one chain: " + what + " " + r.log);
                List<Long> after = r.requests.subList(sent, r.requests.size());
                check(after.get(0) == first, "the first request after the wake: " + what + " " + after);
                if (!back) check(after.equals(Arrays.asList(first, first + 15 * S, first + 30 * S, first + 45 * S)), "out of sight: the pending poll, then every 15 s, nothing extra: " + what + " " + after);
                else check(after.subList(1, after.size()).stream().allMatch(t -> (t - wake) % StatusCadence.POLL_FAST_MS == 0) && after.size() == 31,
                        "in sight: the resume refresh, then a poll every 2 s: " + what + " " + after);
            }
        check(headLost == 54 && headCases == 72, "on 5a4b1d02 the wake lost " + headLost + " of " + headCases + " healthy links");
    }

    /**
     * The scooter is gone when the phone wakes (it stopped answering during the sleep, the radio link still up), with or
     * without pushed frames still arriving, one even waking the phone mid-sleep: the link is still called lost, as
     * READ_TIMEOUT, within the bound in awake time. In sight: 12 s after the return, by the next 2 s poll. Out of sight: the
     * first poll after 35 s of awake silence since its last answer, at most one 15 s poll later. A frame never stands in
     * for the status.
     */
    private static void goneDuringSleep() {
        for (long gap : GAPS) for (long sleepAt : SLEEP_AT) for (boolean back : new boolean[]{false, true}) for (boolean pushes : new boolean[]{false, true}) {
            String what = "asleep at " + sleepAt + " for " + gap + (back ? ", back in sight" : ", out of sight") + (pushes ? ", frames pushed" : "");
            Rig r = new Rig(NEW);r.connect();r.run(T0 + sleepAt);
            long answeredUp = r.statusUp;
            r.sleep(gap / 2);
            if (pushes) r.frame();// a pushed frame wakes the phone for a moment
            r.sleep(gap - gap / 2);
            r.answering = false;
            long wakeUp = r.clock.up;
            if (pushes) {
                Runnable[] p = new Runnable[1];
                p[0] = () -> { r.frame();if (r.up) r.clock.post(p[0], S); };
                r.clock.post(p[0], S);
            }
            if (back) r.visibility(true, false);
            r.run(r.now() + 120 * S);
            check(r.log.equals(Arrays.asList("CONNECT_ATTEMPT", "READ_TIMEOUT")), "lost, as READ_TIMEOUT: " + what + " " + r.log);
            if (back) check(r.failedUp - wakeUp > StatusCadence.SILENCE_FAST_MS && r.failedUp - wakeUp <= StatusCadence.SILENCE_FAST_MS + StatusCadence.POLL_FAST_MS,
                    "in sight: 12 s after the return, by the next poll (" + (r.failedUp - wakeUp) + "): " + what);
            else check(r.failedUp - answeredUp > StatusCadence.SILENCE_SLOW_MS && r.failedUp - answeredUp <= StatusCadence.SILENCE_SLOW_MS + StatusCadence.POLL_SLOW_MS,
                    "out of sight: 35 s of awake silence, by the next poll (" + (r.failedUp - answeredUp) + "): " + what);
        }
    }

    /**
     * The first request after a wake gets exactly the window any request gets when the phone stays awake: in sight, an
     * answer before the poll 14 s after the return (silence over 12 s) keeps the link and one after it does not; out of
     * sight, an answer before the poll at which the awake silence passes 35 s (here 30 s after the request).
     */
    private static void wakeWindowEdges() {
        for (long gap : GAPS) for (boolean back : new boolean[]{false, true}) for (boolean inside : new boolean[]{true, false}) {
            Rig r = new Rig(NEW);r.connect();r.run(T0 + 48 * S);r.sleep(gap);
            long wake = r.now();
            r.reply = back ? (inside ? 13_950 : 14_050) : (inside ? 29_950 : 30_050);
            if (back) r.visibility(true, false);
            r.run(wake + 90 * S);
            String what = (back ? "in sight" : "out of sight") + ", asleep " + gap + ", answer in " + r.reply;
            check(r.failed() != inside, "the window's edge: " + what + " " + r.log);
            if (!inside) check(r.failedAt - wake == (back ? 14 * S : 14_500 + 30 * S), "lost at the poll that passes the limit: " + what + " " + (r.failedAt - wake));
        }
    }

    /**
     * Several sleeps in a row, 1 or 5 minutes each, with 0.5, 3 or 20 s awake between them, on the background cadence or
     * the fast one (a hold or charge recording out of sight): only the awake time counts, and it adds up. A healthy link
     * survives them all; a gone scooter is called lost after 12 s (fast) or 35 s (slow) of awake silence, by the next poll.
     */
    private static void repeatedSleeps() {
        int headLost = 0, headCases = 0;
        for (long awakeFor : new long[]{500, 3 * S, 20 * S}) for (long gap : new long[]{60 * S, 5 * 60 * S}) for (boolean fast : new boolean[]{false, true})
            for (boolean healthy : new boolean[]{true, false}) for (int mode : new int[]{NEW, HEAD}) {
                if (mode == HEAD && !healthy) continue;
                String what = (fast ? "fast" : "slow") + ", asleep " + gap + " with " + awakeFor + " awake between" + (healthy ? "" : ", scooter gone");
                Rig r = new Rig(mode);r.connect();r.run(T0 + 50 * S);
                if (fast) { r.visibility(false, true);r.run(T0 + 51 * S); }
                if (!healthy) r.answering = false;
                long answeredUp = r.statusUp, awake = 0;
                int sleeps = 0;
                while (awake < 150 * S && !r.failed()) { r.sleep(gap);r.run(r.now() + awakeFor);awake += awakeFor;sleeps++; }
                if (mode == HEAD) { headCases++;if (r.failed()) headLost++;continue; }
                if (healthy) check(!r.failed() && r.maxPending <= 1 && sleeps >= 7, "a healthy link survives " + sleeps + " sleeps: " + what + " " + r.log);
                else {
                    long limit = fast ? StatusCadence.SILENCE_FAST_MS : StatusCadence.SILENCE_SLOW_MS, poll = fast ? StatusCadence.POLL_FAST_MS : StatusCadence.POLL_SLOW_MS;
                    check(r.log.equals(Arrays.asList("CONNECT_ATTEMPT", "READ_TIMEOUT")) && r.failedUp - answeredUp > limit && r.failedUp - answeredUp <= limit + poll,
                            "lost after " + (r.failedUp - answeredUp) + " ms of awake silence: " + what + " " + r.log);
                }
            }
        check(headLost == headCases && headCases == 12, "on 5a4b1d02 every one of these healthy links was lost: " + headLost + "/" + headCases);
    }

    /**
     * The phone sleeps while a cadence change is in flight: a hold or charge recording starting out of sight (the retimed
     * 2 s poll pending), Volt shown and hidden again (the retimed 15 s poll pending), and a ride start's own refresh (the
     * settling poll pending). On waking the link survives and the chain goes on at the right rate, one poll at a time.
     */
    private static void sleepDuringCadenceChange() {
        int headLost = 0, headCases = 0;
        for (long gap : GAPS) for (int how = 0; how < 3; how++) for (int mode : new int[]{NEW, HEAD}) {
            String what = new String[]{"hold out of sight", "shown and hidden", "ride start's refresh"}[how] + ", asleep " + gap;
            Rig r = new Rig(mode);r.connect();r.run(T0 + 60 * S);
            if (how == 0) r.visibility(false, true);
            else if (how == 1) { r.visibility(true, false);r.run(T0 + 60_400);r.visibility(false, false); }
            else { r.visibility(false, true);r.requestStatus(); }
            r.run(r.now() + 400);
            check(r.timer.live.size() == 1 && r.ready(), "the retimed poll is pending, the replies are in: " + what);
            r.sleep(gap);r.reply = S;
            long wake = r.now();int sent = r.requests.size();
            r.run(wake + 60 * S);
            if (mode == HEAD) { headCases++;if (r.failed()) headLost++;continue; }
            check(!r.failed() && r.linkUp() && (how == 1 || r.ready()) && r.maxPending <= 1, "the link survives (ready on the fast cadence): " + what + " " + r.log);
            List<Long> after = r.requests.subList(sent, r.requests.size());
            long period = how == 1 ? StatusCadence.POLL_SLOW_MS : StatusCadence.POLL_FAST_MS;
            check(after.size() >= 60 * S / period - 1, "polls go on: " + what + " " + after);
            for (int i = 1; i < after.size(); i++) check(after.get(i) - after.get(i - 1) == period, "at the cadence's rate: " + what + " " + after);
            check(after.get(0) - wake < period, "the pending poll first: " + what + " " + after);
        }
        check(headLost == headCases && headCases == 9, "on 5a4b1d02 every one of these was lost: " + headLost + "/" + headCases);
    }

    /**
     * The phone sleeps during the connect sequence: before authentication, or after it with the first status in, Volt out
     * of sight from the start or leaving at 0 or 3 s. A healthy link is not timed out at 18 s of awake time and stays up.
     * One that never becomes ready (authentication stalls, or no status ever) still ends with CONNECT_TIMEOUT exactly 18 s
     * of awake time after the attempt, whatever the sleep.
     */
    private static void sleepDuringConnect() {
        int headLost = 0, headCases = 0;
        for (long gap : GAPS) for (long sleepAt : new long[]{S, 2_900, 4 * S, 10 * S, 17_400}) for (long leave : new long[]{-1, 0, 3 * S}) for (int mode : new int[]{NEW, HEAD}) {
            if (leave >= sleepAt) continue;
            String what = "asleep at " + sleepAt + " for " + gap + ", left at " + leave;
            Rig r = new Rig(mode);r.visible = leave >= 0;r.chain.cadence(r.visible);r.connect();
            if (leave >= 0) { r.run(T0 + leave);r.visibility(false, false); }
            r.run(T0 + sleepAt);r.sleep(gap);
            long wake = r.now();
            r.run(wake + 120 * S);
            if (mode == HEAD) {
                headCases++;if (r.failed()) headLost++;
                check(r.failed() == (sleepAt > 2_800), "5a4b1d02 lost the link asleep after its first status: " + what + " " + r.log);
                continue;
            }
            check(!r.failed() && r.linkUp() && r.maxPending <= 1, "no timeout, the link stays up: " + what + " " + r.log);
        }
        check(headLost > 0 && headLost < headCases, "5a4b1d02: " + headLost + "/" + headCases);
        long attemptUp = new Clock(T0).up;
        for (int how = 0; how < 2; how++) for (long gap : GAPS) for (long sleepAt : new long[]{S, 10 * S, 17_900}) for (long leave : new long[]{-1, 0}) {
            String what = (how == 0 ? "authentication stalls" : "no status ever") + ", asleep at " + sleepAt + " for " + gap + ", left at " + leave;
            Rig r = new Rig(NEW);r.visible = leave >= 0;r.chain.cadence(r.visible);
            if (how == 0) r.authenticates = false; else r.answering = false;
            r.connect();
            if (leave >= 0) { r.run(T0 + leave);r.visibility(false, false); }
            r.run(T0 + sleepAt);r.sleep(gap);r.run(r.now() + 30 * S);
            check(r.log.equals(Arrays.asList("CONNECT_ATTEMPT", "CONNECT_TIMEOUT")) && r.failedUp - attemptUp == 18 * S, "CONNECT_TIMEOUT at 18 s awake: " + what + " " + r.log + " " + (r.failedUp - attemptUp));
        }
    }

    /**
     * A Scheduled charging change is in flight when the phone sleeps, each step confirmed by the next status read: out of
     * sight it completes with the readbacks after the wake; back in sight, with the resume refresh's answer 3 s later.
     * Never interrupted, nothing refused: every step went out on a fresh status. On 5a4b1d02 the wake ended the link and
     * interrupted the change.
     */
    private static void scheduleAcrossSleep() {
        // Up to an hour: a longer sleep passes the change's own start time (3 h from now), which is the controller's business.
        for (long gap : new long[]{60 * S, 10 * 60 * S, 3600 * S}) for (boolean back : new boolean[]{false, true}) for (int mode : new int[]{NEW, HEAD}) {
            String what = (back ? "back in sight" : "out of sight") + " after " + gap;
            Rig r = new Rig(mode);Schedule s = new Schedule(r);s.ackOnStatus = true;r.connect();
            s.run(T0 + 48 * S);
            s.controller.setManualEnabled(true, s.results::add);
            s.run(T0 + 50 * S);
            check(s.controller.busy() && s.sent.equals(Arrays.asList("clock")), "in flight: the clock step waits for its readback " + s.sent);
            r.sleep(gap);r.reply = 3 * S;
            if (back) { r.visibility(true, false);s.controller.observe(); }
            s.run(r.now() + 90 * S);
            if (mode == HEAD) {
                check(r.failed() && s.results.size() == 1 && s.results.get(0) != null && s.sent.equals(Arrays.asList("clock")), "5a4b1d02 interrupted it: " + what + " " + s.results + " " + r.log);
                continue;
            }
            check(!r.failed() && s.results.size() == 1 && s.results.get(0) == null, "completed: " + what + " " + s.results + " " + r.log);
            check(s.sent.equals(Arrays.asList("clock", "time", "on")) && s.refused.isEmpty() && s.controller.view().manualEnabled, "every step went out on a fresh status: " + what + " " + s.sent + " " + s.refused);
        }
    }

    /**
     * No path renews the window without an answer. A gone scooter while Volt flips in and out of sight every 1 or 1.5 s
     * (each return queues a refresh, each switch moves the pending poll, so the poll never fires): the switch checks the
     * silence itself, so the link is called lost at the first switch after 35 s of awake silence, with or without a sleep
     * first. On 5a4b1d02 the flips kept moving the only silence check away, and the link was never called lost.
     */
    private static void noRenewal() {
        for (long gap : new long[]{0, 10 * 60 * S}) for (long every : new long[]{S, 1_500}) for (int mode : new int[]{NEW, HEAD}) {
            String what = "flips every " + every + (gap > 0 ? " after a sleep" : "");
            Rig r = new Rig(mode);r.connect();r.run(T0 + 60 * S);
            long answeredUp = r.statusUp;
            r.sleep(gap);r.answering = false;
            boolean in = false;long end = r.now() + 300 * S;int ticks = 0;
            while (r.now() < end && !r.failed()) { in = !in;r.visibility(in, false);int before = r.requests.size();r.run(r.now() + every);ticks += r.requests.size() - before; }
            if (mode == HEAD) { check(!r.failed(), "5a4b1d02 never called it lost: " + what + " " + r.log);continue; }
            check(r.log.equals(Arrays.asList("CONNECT_ATTEMPT", "READ_TIMEOUT")) && r.failedUp - answeredUp > StatusCadence.SILENCE_SLOW_MS
                    && r.failedUp - answeredUp <= StatusCadence.SILENCE_SLOW_MS + every, "lost at the first switch past 35 s of awake silence: " + what + " " + (r.failedUp - answeredUp) + " " + r.log);
            check(ticks == 0, "no poll ever fired in between: " + what);
        }
    }

    // ---- The sleep audit (the session now outlives a sleep) -----------------------------------------------------
    //
    // The rule: silence that judges the link, or a request's own timeout, counts awake time only; anything that shows or
    // acts on a value judges it by that value's own freshness on the elapsed clock. "Before" below is 5f27730a, which fed
    // the elapsed clock to ReadPolicy and the compatibility check, showed any stored battery level in the notice, and
    // saved the calculated-start marker before a time write the gate could still refuse. Run one part with its name as
    // an argument; sourceAudit pins the BleController and ScooterService lines the parts mirror.

    static final String[] AUDIT = {"sourceAudit", "readsAcrossSleep", "checkAcrossSleep", "noticeBattery", "scheduleMarker",
            "sourceAudit4", "doneTimeAfterWake", "switchAfterWake", "noticeKeepsLevel", "noticeDropsLevel", "batteryReadsOnlyOutOfSight",
            "backgroundSilence", "inSightSilenceUnchanged", "watchReads"};

    private static void audit(String name) throws Exception {
        switch (name) {
            case "sourceAudit": sourceAudit();break;
            case "readsAcrossSleep": readsAcrossSleep();break;
            case "checkAcrossSleep": checkAcrossSleep();break;
            case "noticeBattery": noticeBattery();break;
            case "scheduleMarker": scheduleMarker();break;
            case "sourceAudit4": sourceAudit4();break;
            case "doneTimeAfterWake": doneTimeAfterWake();break;
            case "switchAfterWake": switchAfterWake();break;
            case "noticeKeepsLevel": noticeKeepsLevel();break;
            case "noticeDropsLevel": noticeDropsLevel();break;
            case "batteryReadsOnlyOutOfSight": batteryReadsOnlyOutOfSight();break;
            case "backgroundSilence": backgroundSilence();break;
            case "inSightSilenceUnchanged": inSightSilenceUnchanged();break;
            case "watchReads": watchReads();break;
            default: throw new IllegalArgumentException(name);
        }
    }

    private static void sourceAudit() throws Exception {
        String ble = read("src/com/jjricks/volt/BleController.java");
        check(ble.contains("private static long awake(){return SystemClock.uptimeMillis();}"), "BleController has the awake clock for request timeouts");
        check(ble.contains("readPolicy.requested(cmd,awake(),quiet&&!(visible||recording||batteryWatch()));") && ble.contains("readPolicy.responded(f.cmd,f.error(),f.data.length-1,awake());")
                && ble.contains("if(readPolicy!=null)readPolicy.tick(awake());"), "ReadPolicy hears every request, reply and tick on the awake clock");
        check(!ble.contains("readPolicy.requested(cmd,now())") && !ble.contains("readPolicy.tick(now())") && !ble.contains("f.data.length-1,now())"),
                "no ReadPolicy or check call is left on the elapsed clock");
        check(ble.contains("discovery=new DiscoveryCoordinator(awake(),") && ble.contains("owner.transmitted(cmd,awake())")
                && ble.contains("discovery.onFrame(f.cmd,f.error(),f.data.length-1,awake());") && ble.contains("discovery.onDisconnect(awake());")
                && ble.contains("discovery.interrupt(awake());") && ble.contains("if(!discovering||session!=generation)return;\n        long t=awake();"),
                "the compatibility check starts, sends, hears, ticks and ends on the awake clock");
        check(ble.contains("public Double liveNumber(String key){for(Telemetry.Metric m:telemetry.metrics())if(m.key.equals(key))return StatusCadence.live(m.updatedMs,now(),readingFreshMs(TelemetryHistory.freshnessMs(key)))?m.numericValue:null;return null;}"),
                "liveNumber: a reading only while live by its own rule, on the elapsed clock");
        int refused = ble.indexOf("if(result!=0){writing=false;if(mutation!=null&&Arrays.equals(frame,mutation.bytes))mutation.sent=false;");
        String run = "if(mutation!=null&&Arrays.equals(frame,mutation.bytes)&&mutation.sentHook!=null){Runnable hook=mutation.sentHook;mutation.sentHook=null;hook.run();}";
        int hook = ble.indexOf(run);
        check(refused > 0 && hook > refused && ble.indexOf("sentHook.run()") < 0 && ble.indexOf("hook.run()", hook + run.length()) < 0, "the sent hook runs once, only after Bluetooth took the frame");
        check(ble.contains("mutation.completion=completion;mutation.sentHook=sent;") && ble.contains("begin(request,null,0,ScooterCommands.setChargeStart(seconds,offset),seconds+offset,completion,sent);")
                && ble.contains("simulation.setChargeStart(seconds,offset);if(sent!=null)sent.run();"), "a charge start write carries its hook; the simulator's write counts as sent");
        String svc = read("src/com/jjricks/volt/ScooterService.java");
        check(svc.contains("Double battery = ble.liveNumber(\"battery_percent\");") && !svc.contains("ble.telemetry.number(\"battery_percent\")"),
                "the connected notice shows the battery only while that reading is live");
        check(svc.contains("@Override public void time(long wallMs,int offsetSeconds,Runnable sent,Consumer<Boolean> completion){ble.setChargeStart(wallMs/1000,offsetSeconds,completion,sent);}"),
                "the service passes the hook to the radio");
    }

    /** ReadPolicy fed the awake clock (now) or the elapsed one (before), with a scooter that answers after REPLY awake ms. */
    static final class Reads {
        final Clock clock = new Clock(T0);
        final boolean before;
        final ReadPolicy policy = ReadPolicy.forLayer(ModelRegistry.Layer.OEM_GROUP);
        boolean answering = true;
        Reads(boolean before) { this.before = before; }
        long t() { return before ? clock.now : clock.up; }
        void request(int cmd) {
            policy.requested(cmd, t());
            if (answering) clock.post(() -> policy.responded(cmd, 0, 36, t()), REPLY);
        }
        void awake(long ms) { clock.runUntil(clock.now + ms); }
    }

    /**
     * Finding 1. Volt is glanced at twice (on for 150 ms each time, the phone asleep in between) while a battery read is
     * on its way: the resume refresh asks again at each wake, which counts whatever is outstanding. Before, each sleep
     * counted as one 2.5 s silence, so the second wake suppressed 0x72 for the session, though the scooter answered
     * every read 300 ms after it. Now only awake time counts. A read really ignored for 2.5 s awake twice, even with
     * sleeps in between, is still suppressed, and stays so when a late answer comes (ReadPolicyTest pins that).
     */
    private static void readsAcrossSleep() {
        for (long sleep : new long[]{5 * S, 10 * 60 * S}) for (boolean before : new boolean[]{false, true}) {
            Reads r = new Reads(before);
            r.request(0x72);r.awake(150);
            r.clock.sleep(sleep);r.request(0x72);r.awake(100);
            r.clock.sleep(sleep);r.request(0x72);r.awake(5 * S);r.policy.tick(r.t());
            if (before) { check(!r.policy.allowed(0x72) && r.policy.suppressed().contains(0x72), "5f27730a suppressed an answered battery read after a " + sleep + " sleep");continue; }
            check(r.policy.allowed(0x72) && r.policy.suppressed().isEmpty() && r.policy.answered(0x72), "answered reads stay allowed across a " + sleep + " sleep");
        }
        // Really ignored: 2.6 s awake per request, split by sleeps; the second silence suppresses it.
        for (long sleep : new long[]{0, 10 * 60 * S}) {
            Reads r = new Reads(false);r.answering = false;
            r.request(0x72);r.awake(S);r.clock.sleep(sleep);r.awake(1_600);r.policy.tick(r.t());
            check(r.policy.allowed(0x72), "one silence is not enough");
            r.request(0x72);r.awake(2 * S);r.clock.sleep(sleep);r.awake(600);r.request(0x72);
            check(!r.policy.allowed(0x72), "ignored twice while awake: suppressed (sleep " + sleep + ")");
            r.policy.responded(0x72, 0, 36, r.t());
            check(!r.policy.allowed(0x72), "and a late answer does not bring it back this session");
        }
    }

    /** One compatibility check, as discoveryTick runs it every 100 ms awake, with a sleep of sleepMs at sleepAt awake ms. */
    private static List<String> runCheck(boolean before, long sleepAt, long sleepMs) {
        Clock clock = new Clock(T0);
        long[] t = new long[1];
        Runnable stamp = () -> t[0] = before ? clock.now : clock.up;
        stamp.run();
        DiscoveryCoordinator d = new DiscoveryCoordinator(t[0]);
        long startUp = clock.up;boolean slept = sleepAt < 0;
        for (int i = 0; i < 2000; i++) {
            stamp.run();
            Integer cmd = d.nextRead(t[0]);
            if (cmd != null && d.transmitted(cmd, t[0])) {
                // Lengths as BleController.simulatedReply; 0x71 and 0x75 stay silent.
                int c = cmd, length = c == 0x74 ? 17 : c == 0x70 ? 43 : c == 0x73 ? 40 : c == 0x72 ? 36 : c == 0x76 ? 16 : c == 0x7b ? 8 : c == 0x79 ? 2 : -1;
                if (length >= 0) clock.post(() -> { stamp.run();d.onFrame(c, 0, length, t[0]); }, REPLY);
            }
            if (d.finished(t[0])) break;
            clock.runUntil(clock.now + 100);
            if (!slept && clock.up - startUp >= sleepAt) { clock.sleep(sleepMs);slept = true; }
        }
        List<String> out = new ArrayList<>();
        for (DiscoveryCoordinator.Read r : d.reads()) {
            boolean suppress = r.outcome == DiscoveryCoordinator.Outcome.NO_RESPONSE && r.attempts >= DiscoveryCoordinator.maxAttempts(r.cls);
            out.add(r + " x" + r.attempts + (r.late ? " late" : "") + (suppress ? " suppressed" : ""));
        }
        return out;
    }

    /**
     * Audit fix. The compatibility check is not stopped by leaving sight, so the phone can sleep during one. Before, the
     * sleep counted against the read in flight (and the 30 s cap): a 5 s sleep ended a one-try probe as NO_RESPONSE,
     * which also suppressed that read for the session (BleController.finishDiscovery), and a long sleep ended the whole
     * check with reads the scooter answers marked NO_RESPONSE or INTERRUPTED. Now a sleep at any point changes nothing.
     */
    private static void checkAcrossSleep() {
        List<String> plain = runCheck(false, -1, 0);
        check(plain.equals(runCheck(true, -1, 0)), "no sleep: the two clocks agree " + plain);
        for (String r : plain) check(r.startsWith("0x71") || r.startsWith("0x75") ? r.contains("NO_RESPONSE x1 suppressed") : r.contains("READ_SUPPORTED x1") && !r.contains("late"), "answered once, on time: " + r);
        int wrong = 0, falseSuppressed = 0;
        for (long sleepAt = 0; sleepAt <= 12 * S; sleepAt += 100) for (long sleep : new long[]{5 * S, 10 * 60 * S}) {
            List<String> now = runCheck(false, sleepAt, sleep);
            check(now.equals(plain), "a " + sleep + " sleep at " + sleepAt + " changes nothing " + now);
            List<String> old = runCheck(true, sleepAt, sleep);
            if (!old.equals(plain)) wrong++;
            for (int i = 0; i < old.size(); i++) if (old.get(i).contains("suppressed") && !plain.get(i).contains("suppressed")) falseSuppressed++;
        }
        check(wrong > 50 && falseSuppressed > 0, "5f27730a: a sleep changed the outcome in " + wrong + " runs, suppressing an answered read in " + falseSuppressed);
    }

    /**
     * Finding 2. The connected notice, "UT5 MAX · 82%", with the battery read only in sight (fde6403f's rule; since the
     * owner's 2026-11-12 decision it is read out of sight too, noticeKeepsLevel, and this is the case of a read that stopped). The link outlives a ten-minute sleep; on the wake the notice names the scooter alone until
     * a fresh reading, by the reading's own rule (20 s out of sight, BleController.liveNumber). Before, the level from
     * before the sleep was shown as current. Back in sight, the resume refresh's reading brings it back.
     */
    private static void noticeBattery() {
        for (boolean before : new boolean[]{false, true}) {
            Rig r = new Rig(NEW);long[] battery = new long[1];
            r.onStatus.add(() -> { if (r.fast()) battery[0] = r.now(); });
            r.visibility(true, false);r.connect();r.run(T0 + 60 * S);
            r.visibility(false, false);long left = r.now();
            java.util.function.BooleanSupplier shown = () -> before || StatusCadence.live(battery[0], r.now(), StatusCadence.readingFreshMs(15 * S, r.chain.fast(), r.chain.fastSinceMs(), r.now()));
            r.run(left + 17 * S);
            check(r.linkUp() && shown.getAsBoolean(), "just out of sight: name and battery");
            r.sleep(10 * 60 * S);r.run(r.now() + S);
            check(r.linkUp() && !r.failed(), "the link outlived the sleep");
            if (before) { check(shown.getAsBoolean(), "5f27730a showed a level from before the sleep as current");continue; }
            check(!shown.getAsBoolean(), "after the sleep: the scooter's name alone");
            long back = r.now();r.visibility(true, false);r.run(back + S);
            check(shown.getAsBoolean() && battery[0] > back, "back in sight: the fresh level again");
        }
    }

    /** ChargeScheduleController's Done by, with a driver that sends only through BleController's 7 s gate. */
    static final class Marker implements ChargeScheduleController.Store, ChargeScheduleController.Driver {
        final Clock clock = new Clock(T0);
        final Map<String, String> saved = new HashMap<>();
        final ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        final ChargeScheduleController controller;
        final List<String> sent = new ArrayList<>(), results = new ArrayList<>();
        long statusMs = T0;boolean confirms = true;
        Consumer<Boolean> pending;
        Marker() {
            c.scope = "owner-A";c.ready = true;c.generation = 1;c.now = java.time.Instant.parse("2026-10-20T05:00:00Z").toEpochMilli();c.zone = TimeZone.getTimeZone("America/Boise");
            c.charging = 1;c.enabled = 0;c.soc = 79d;c.target = 100d;c.estimateMinutes = 90d;c.sampleMs = 100_000;
            controller = new ChargeScheduleController(this, this);
        }
        boolean gate() { return clock.now - statusMs <= 7000; }
        long marker() { String s = saved.get("owner-A");return s == null ? 0 : ChargeScheduleController.decode(s).calculatedWire; }
        public String read(String key) { return saved.get(key); }
        public void write(String key, String value) { saved.put(key, value); }
        public ChargeScheduleController.Context context() { return c; }
        public void clock(Consumer<Boolean> done) { if (!gate()) { done.accept(false);return; } sent.add("clock");pending = done; }
        public void time(long wall, int offset, Consumer<Boolean> done) { time(wall, offset, () -> {}, done); }
        /** BleController.setChargeStart: refused at the gate before anything goes out, or sent (the hook) and then confirmed or not. */
        public void time(long wall, int offset, Runnable hook, Consumer<Boolean> done) {
            if (!gate()) { done.accept(false);return; }
            sent.add("time");hook.run();pending = done;
        }
        public void enabled(boolean on, Consumer<Boolean> done) { if (!gate()) { done.accept(false);return; } sent.add(on ? "on" : "off");pending = done; }
        void reply(boolean ok) { Consumer<Boolean> done = pending;pending = null;done.accept(ok); }
    }

    /**
     * Finding 3. A Done by start is being sent out of sight: the clock write goes on a fresh status, the phone sleeps
     * before its confirmation, and on the wake (link still up, so the change carries on) the time write meets a status
     * from before the sleep and is refused before anything is sent. Before, the calculated-start marker was saved first
     * and stayed for a write that never went out. Now it is saved when the frame is handed to Bluetooth: none for the
     * refused write; a sent write keeps it even when its confirmation is lost, as ChargeScheduleController requires.
     */
    private static void scheduleMarker() {
        Marker m = new Marker();
        m.controller.setDoneEnabled(true, m.results::add);
        check(m.sent.equals(Arrays.asList("clock")), "the clock write went out " + m.sent);
        m.clock.sleep(10 * 60 * S);m.reply(true);
        check(m.sent.equals(Arrays.asList("clock")) && m.results.size() == 1 && m.results.get(0) != null, "the time write was refused at the gate " + m.sent + " " + m.results);
        check(m.marker() == 0, "no marker for a write that never went out (5f27730a kept " + m.marker() + ")");
        // On a fresh status: the time write goes out and its confirmation is lost.
        Marker lost = new Marker();
        lost.controller.setDoneEnabled(true, lost.results::add);lost.reply(true);
        check(lost.sent.equals(Arrays.asList("clock", "time")) && lost.marker() > 0, "a sent time write is marked " + lost.sent);
        long marked = lost.marker();lost.reply(false);
        check(lost.marker() == marked && lost.results.size() == 1 && lost.results.get(0) != null, "and keeps its marker when its confirmation is lost");
    }

    // ---- Round 4 (#418): the context's two answers, and the notice's battery out of sight ---------------------------

    private static void sourceAudit4() throws Exception {
        String svc = read("src/com/jjricks/volt/ScooterService.java");
        check(svc.contains("c.linked=same&&ble.linked();c.ready=c.linked&&ble.ready();"), "the schedule context has two answers: the same live connection, and can write now");
        check(svc.contains("c.enabled=simulation||ble.statusAge()<=ble.readingFreshMs(10_000)?ble.featureValue(\"timed_charging\"):null;"), "the switch is read only from a live status");
        check(svc.contains("chargeMeasure.evaluate(mono,activeProfileId(),c.linked,c.ready,c.charging,known)"), "the measuring window is given both answers");
        String ctl = read("src/com/jjricks/volt/ChargeScheduleController.java");
        check(ctl.contains("private static boolean alive(Context c){return c.ready||c.linked;}") && ctl.contains("if(c.ready&&view.doneByEnabled){enableDone("),
                "the controller carries on with alive and reprograms a Done by edit only when ready");
        String ble = read("src/com/jjricks/volt/BleController.java");
        check(ble.contains("if(!discovering&&!testing()&&now()-lastBatteryPollMs>=(recording?2000:interval)){if(visible||recording||batteryWatch())for(int cmd:policy().periodicBattery())send(Protocol.frame(cmd,null));else for(int cmd:policy().noticeBattery())sendQuietly(Protocol.frame(cmd,null));lastBatteryPollMs=now();}"),
                "the poll reads the battery through ReadPolicy at its interval, out of sight too: periodicBattery in sight, recording or watching, else noticeBattery, quietly");
        check(ble.contains("private void sendQuietly(byte[] frame){quietReads.add(frame);send(frame);boolean queued=false;for(byte[] q:queue)queued|=q==frame;if(!queued)quietReads.remove(frame);}")
                && ble.contains("DiscoveryCoordinator owner=discoveryQueue.remove(frame);boolean quiet=quietReads.remove(frame);")
                && ble.contains("queue.clear();discoveryQueue.clear();quietReads.clear();outstandingStatusReads=0;")
                && ble.contains("if(simulating()){queue.clear();discoveryQueue.clear();quietReads.clear();writing=false;return;}"),
                "a quiet read is marked per queued frame, taken off as it goes out, and cleared with the queue");
        check(!ble.contains("(visible||recording||batteryWatch())&&now()-lastBatteryPollMs"), "no longer only in sight, recording or watching");
        check(ble.contains("send(Protocol.frame(0x70,null));for(int cmd:policy.periodicBattery())send(Protocol.frame(cmd,null));}"), "the resume refresh reads it as before");
        int tick = ble.indexOf("private void pollTick(int session){");
        String body = ble.substring(tick, ble.indexOf("\n", tick));
        check(body.startsWith("private void pollTick(int session){if(simulating()||!workAllowed()||session!=generation||!authenticated||gatt==null)return;") && !body.contains("acquire") && !body.contains("WakeLock"),
                "only on a link that is up, with no wake lock of its own");
    }

    /** Out of sight, Done by on; the phone sleeps ten minutes right after a status; s.run to `until`. */
    private static Schedule doneByThenSleep(boolean oneAnswer) {
        Rig r = new Rig(NEW);Schedule s = new Schedule(r);s.oneAnswer = oneAnswer;s.chargingReadings = true;r.connect();
        s.run(T0 + 18 * S);// the status from the 17.5 s poll is 0.2 s old
        s.controller.setDoneEnabled(true, s.results::add);
        s.run(T0 + 33 * S);// and the next one, at 32.8 s
        check(s.results.size() == 1 && s.results.get(0) == null && s.sent.equals(Arrays.asList("clock", "time", "on")) && s.controller.view().doneByEnabled,
                "Done by went on out of sight " + s.results + " " + s.sent + " " + s.refused);
        r.sleep(10 * 60 * S);s.run(r.now() + 500);
        check(r.linkUp() && !r.ready() && !r.failed(), "just after the wake: linked, not ready");
        return s;
    }

    /**
     * Round 4 finding. A Done by time is edited just after a wake: the link is up (linked()) but the status is from before
     * the sleep, so the write gate refuses. fde6403f's context gave ready = linked(), so setDoneTime chose to reprogram,
     * the gate refused, and the new time was lost. Now ready is the gate's own answer: the time is saved for the next
     * plan, nothing is sent or refused, and an edit on the next fresh status reprograms the scooter.
     */
    private static void doneTimeAfterWake() {
        for (boolean one : new boolean[]{false, true}) {
            Schedule s = doneByThenSleep(one);
            int was = s.controller.view().doneByMinuteOfDay, sent = s.sent.size();
            int minute = was == 570 ? 600 : 570;
            s.controller.setDoneTime(minute / 60, minute % 60, s.results::add);
            if (one) {
                check(s.results.size() == 2 && s.results.get(1) != null && s.controller.view().doneByMinuteOfDay == was && s.sent.size() == sent,
                        "fde6403f chose a write the gate refused, and the time was lost " + s.results + " " + s.refused);
                continue;
            }
            check(s.results.size() == 2 && s.results.get(1) == null && s.controller.view().doneByMinuteOfDay == minute, "the edit is saved " + s.results);
            check(s.sent.size() == sent && s.refused.isEmpty() && s.controller.view().doneByEnabled && !s.controller.busy(), "nothing sent or refused, the plan stays on " + s.sent + " " + s.refused);
            s.run(s.rig.now() + 15 * S);
            check(s.rig.ready(), "the next poll's status is fresh");
            s.controller.setDoneTime(minute / 60, minute % 60 + 15, s.results::add);
            check(s.sent.size() == sent + 1 && s.sent.get(sent).equals("clock"), "and an edit then reprograms the scooter " + s.sent);
        }
    }

    /**
     * Audit. The Scheduled charging switch shown just after a wake: fde6403f read it from the status before the sleep with no
     * age check, so a switch changed on the scooter meanwhile showed as current (and Charging hold took it as scheduled).
     * Now it is unknown until a live status, as every other reading.
     */
    private static void switchAfterWake() {
        for (boolean one : new boolean[]{false, true}) {
            Rig r = new Rig(NEW);Schedule s = new Schedule(r);s.oneAnswer = one;s.switchRead = 1;r.connect();
            s.run(T0 + 33 * S);
            check(s.context().enabled == 1 && s.controller.diagnostics().readEnabled == 1, "a live status shows the switch");
            r.sleep(10 * 60 * S);s.run(r.now() + 500);
            check(r.linkUp() && !r.ready(), "linked, not ready");
            if (one) { check(s.controller.diagnostics().readEnabled == 1, "fde6403f showed the switch from before the sleep");continue; }
            check(s.context().enabled == null && s.controller.diagnostics().readEnabled == null, "after the sleep the switch is unknown");
            s.run(r.now() + 15 * S);
            check(s.controller.diagnostics().readEnabled == 1, "and shown again from the next status");
        }
    }

    private static Rig batteryRig(ModelRegistry.Layer layer) {
        Rig r = new Rig(NEW);r.batteryPolicy = layer == null ? ReadPolicy.legacy() : ReadPolicy.forLayer(layer);return r;
    }

    /**
     * Owner, 2026-11-12: keep the notice's battery level current. Connected out of sight (from the start, or after leaving
     * at any phase), the battery is read with each 15 s poll, so the level never lapses while the link is up, on a model
     * that answers through ReadPolicy (OEM_GROUP) and on the UT5 MAX (legacy, never suppressed). fde6403f read it only in
     * sight, so the level dropped 20 s after leaving. After a sleep it is back with the first poll after waking.
     */
    private static void noticeKeepsLevel() {
        for (ModelRegistry.Layer layer : new ModelRegistry.Layer[]{ModelRegistry.Layer.OEM_GROUP, null})
            for (long leave = -1; leave < StatusCadence.POLL_SLOW_MS; leave += leave < 0 ? 1 : 750)
                for (boolean before : new boolean[]{false, true}) {
                    Rig r = batteryRig(layer);r.batteryOutOfSight = !before;
                    if (leave >= 0) r.visibility(true, false);
                    r.connect();
                    long out = T0 + 30 * S + Math.max(0, leave);
                    if (leave >= 0) { r.run(out);r.visibility(false, false); } else r.run(T0 + 3 * S);
                    int gaps = 0;
                    for (long t = r.now() + STEP; t <= T0 + 5 * 60 * S; t += STEP) { r.run(t);if (r.linkUp() && !r.levelShown()) gaps++; }
                    String what = (layer == null ? "legacy" : "OEM_GROUP") + ", left at " + leave;
                    if (before) { check(gaps > 1000, "fde6403f dropped the level out of sight: " + what);continue; }
                    check(gaps == 0 && !r.failed() && r.batteryPolicy.suppressed().isEmpty(), "the level stays current out of sight: " + what + " (" + gaps + " gaps)");
                    // A ten-minute sleep: name alone on the wake, the level back with the first poll after it.
                    r.sleep(10 * 60 * S);long wake = r.now();r.run(wake + STEP);
                    check(r.linkUp() && !r.levelShown(), "just after the sleep: the name alone (" + what + ")");
                    long back = -1;
                    for (long t = wake + STEP; t <= wake + 20 * S && back < 0; t += STEP) { r.run(t);if (r.levelShown()) back = t - wake; }
                    check(back > 0 && back <= StatusCadence.POLL_SLOW_MS + REPLY, "and the level is back by the first poll after waking: " + back + " (" + what + ")");
                }
    }

    /**
     * The level is still dropped when it isn't current: a model that never answers the battery read is not asked again out
     * of sight (noticeBattery needs an answer this connection), so the notice names the scooter alone and the one silence
     * from the connect bundle switches nothing off (round 5); a scooter that stops answering loses the level 20 s after its
     * last answer, before the link ends at 35 s.
     */
    private static void noticeDropsLevel() {
        Rig ignored = batteryRig(ModelRegistry.Layer.OEM_GROUP);ignored.batteryAnswers = false;ignored.connect();ignored.run(T0 + 60 * S);
        int reads = ignored.batteryReads.size();
        check(ignored.batteryPolicy.allowed(0x72) && ignored.batteryPolicy.suppressed().isEmpty() && ignored.batteryReads.size() == 1, "an ignored battery read is not asked again out of sight, nor switched off there " + ignored.batteryReads.size());
        ignored.run(T0 + 5 * 60 * S);
        check(ignored.batteryReads.size() == reads && reads <= 3 && !ignored.levelShown() && ignored.linkUp() && !ignored.failed(), "then no more reads and the name alone, on a healthy link " + reads);
        Rig silent = batteryRig(ModelRegistry.Layer.OEM_GROUP);silent.connect();silent.run(T0 + 60 * S);
        check(silent.levelShown(), "answering: the level shown");
        silent.answering = false;long last = silent.batteryMs;
        for (long t = silent.now() + STEP; t <= T0 + 3 * 60 * S; t += STEP) {
            silent.run(t);
            if (silent.levelShown()) check(silent.now() - last <= 20 * S, "never shown more than 20 s after its last answer");
        }
        check(silent.failed() && !silent.levelShown(), "the scooter went silent: the link ended and the level is gone " + silent.log);
    }

    /**
     * The read joins only the out-of-sight polls: in sight and recording the battery reads are exactly fde6403f's; out of
     * sight one per 15 s poll (0x72 alone, round 5); none once the link is lost.
     */
    private static void batteryReadsOnlyOutOfSight() {
        for (int how = 0; how < 2; how++) {
            List<List<Long>> both = new ArrayList<>();
            for (boolean before : new boolean[]{false, true}) {
                Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.batteryOutOfSight = !before;
                if (how == 0) r.visibility(true, false); else r.visibility(false, true);
                r.connect();r.run(T0 + 2 * 60 * S);both.add(r.batteryReads);
            }
            check(both.get(0).equals(both.get(1)) && both.get(0).size() > 50, (how == 0 ? "in sight" : "recording") + ": the same battery reads as before " + both.get(0).size());
        }
        List<Long> newReads = null;int oldReads = -1;
        for (boolean before : new boolean[]{false, true}) {
            Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.batteryOutOfSight = !before;r.connect();r.run(T0 + 60 * S);
            int at = r.batteryReads.size();r.run(T0 + 5 * 60 * S);
            if (before) { oldReads = r.batteryReads.size() - at;continue; }
            newReads = r.batteryReads.subList(at, r.batteryReads.size());
            // Polls at 2.5 + 15k s: 0x72 at each, never 0x79 (backgroundSilence has a scooter whose 0x79 has answered).
            check(newReads.size() == 16 && newReads.stream().allMatch(t -> (t - T0 - 2500) % StatusCadence.POLL_SLOW_MS == 0), "out of sight: one battery read per 15 s poll " + newReads);
            r.fail("NATIVE_DISCONNECT");int lost = r.batteryReads.size();r.run(T0 + 10 * 60 * S);
            check(r.batteryReads.size() == lost, "nothing once the link is lost");
        }
        check(oldReads == 0, "fde6403f read no battery out of sight: " + oldReads);
    }

    // ---- Round 5 (#418): the notice's background read never switches a read off ---------------------------------------

    /** The connect bundle's 0x79 probe, answered (ReadPolicy then polls 0x79 with 0x72 where periodicBattery is used). */
    private static void probe79(Rig r) { r.batteryPolicy.requested(0x79, r.clock.up);r.batteryPolicy.responded(0x79, 0, 2, r.clock.up); }
    private static boolean has(int[] values, int v) { for (int x : values) if (x == v) return true;return false; }

    /**
     * Round 5 finding. 4164feba sent periodicBattery with every poll out of sight and ReadPolicy counted those silences, so
     * a parked scooter leaving the direction read (0x79) unanswered twice switched it off for the rest of the connection,
     * and back in sight every current reading kept the last direction (the same for 0x72, the battery read itself). Now
     * out of sight, with no battery watch and no recording, only 0x72 goes out, quietly: its silence is never counted.
     */
    private static void backgroundSilence() {
        for (int ignore = 0; ignore < 2; ignore++) for (boolean before : new boolean[]{false, true}) {
            Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.noticeOnly = !before;
            r.visibility(true, false);r.connect();r.run(T0 + 5 * S);probe79(r);r.run(T0 + 30 * S);
            check(has(r.batteryPolicy.periodicBattery(), 0x79), "in sight: the direction read is polled once it has answered");
            String what = ignore == 1 ? "0x79 and 0x72 ignored out of sight" : "0x79 ignored out of sight";
            r.visibility(false, false);r.ignored.add(0x79);if (ignore == 1) r.ignored.add(0x72);
            int at = r.batteryCmds.size();r.run(T0 + 5 * 60 * S);
            List<Integer> out = new ArrayList<>(r.batteryCmds.subList(at, r.batteryCmds.size()));
            check(r.linkUp() && !r.failed(), "the link stays up: " + what);
            r.ignored.clear();r.visibility(true, false);r.run(r.now() + 10 * S);
            if (before) {
                check(r.batteryPolicy.suppressed().contains(0x79) && !has(r.batteryPolicy.periodicBattery(), 0x79) && (ignore == 0) == r.batteryPolicy.allowed(0x72),
                        "4164feba switched a read off from the background (" + what + "): " + r.batteryPolicy.suppressed());
                continue;
            }
            check(out.size() >= 17 && !out.contains(0x79) && out.stream().allMatch(c -> c == 0x72), "out of sight: 0x72 alone (" + what + ") " + out);
            check(r.batteryPolicy.suppressed().isEmpty() && Arrays.equals(r.batteryPolicy.periodicBattery(), new int[]{0x72, 0x79}), "back in sight both reads go out again (" + what + ")");
            check(r.levelShown() && r.batteryCmds.subList(r.batteryCmds.size() - 4, r.batteryCmds.size()).contains(0x79), "and are read (" + what + ")");
        }
    }

    /**
     * ReadPolicy in sight and recording is main's: a read ignored there is switched off exactly as with 4164feba's rule and
     * fde6403f's. And back in sight after a background spell, a read the scooter now ignores is switched off at the same
     * moment as under fde6403f's rule (no read out of sight), wherever the return falls against the last quiet read.
     */
    private static void inSightSilenceUnchanged() {
        for (int how = 0; how < 2; how++) {
            List<String> runs = new ArrayList<>();
            for (int rule = 0; rule < 3; rule++) {
                Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.noticeOnly = rule != 1;r.batteryOutOfSight = rule != 2;
                if (how == 0) r.visibility(true, false); else r.visibility(false, true);
                r.connect();r.run(T0 + 5 * S);probe79(r);r.run(T0 + 20 * S);r.ignored.add(0x79);r.run(T0 + 60 * S);
                runs.add(r.batteryCmds + " " + r.batteryReads + " " + r.batteryPolicy.suppressed());
            }
            check(runs.get(0).equals(runs.get(1)) && runs.get(0).equals(runs.get(2)) && runs.get(0).endsWith("[121]"),
                    (how == 0 ? "in sight" : "recording") + ": an ignored read is switched off as before " + runs.get(0).substring(Math.max(0, runs.get(0).length() - 40)));
        }
        int compared = 0;
        for (long back = 60 * S; back <= 76 * S; back += 250) {
            long[] off = new long[2];
            for (int rule = 0; rule < 2; rule++) {
                Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.batteryOutOfSight = rule == 0;r.connect();
                r.run(T0 + back - S);r.ignored.add(0x72);r.run(T0 + back);r.visibility(true, false);
                for (long t = r.now() + STEP; t <= T0 + back + 30 * S && off[rule] == 0; t += STEP) { r.run(t);if (!r.batteryPolicy.allowed(0x72)) off[rule] = t - T0 - back; }
            }
            check(off[0] > 0 && off[0] == off[1], "back in sight at " + back + ": switched off " + off[0] + " ms after, as under fde6403f's rule (" + off[1] + ")");
            compared++;
        }
        check(compared == 65, "every return compared " + compared);
    }

    /**
     * Which reads go out of sight. With a battery watch: periodicBattery with each poll, counted as any read, exactly as
     * fde6403f (and main) send them. Without one: 0x72 alone, quietly.
     */
    private static void watchReads() {
        List<String> runs = new ArrayList<>();
        for (int rule = 0; rule < 2; rule++) {
            Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.batteryOutOfSight = rule == 0;r.watch = true;
            r.connect();r.run(T0 + 5 * S);probe79(r);r.run(T0 + 60 * S);
            int at = r.batteryCmds.size();r.ignored.add(0x79);r.run(T0 + 3 * 60 * S);
            List<Integer> later = r.batteryCmds.subList(at, r.batteryCmds.size());
            check(later.contains(0x79) && !r.batteryPolicy.allowed(0x79), "watching: the direction read goes out and is counted as before " + later);
            runs.add(r.batteryCmds + " " + r.batteryReads + " " + r.batteryPolicy.suppressed());
        }
        check(runs.get(0).equals(runs.get(1)), "watching: the same reads as fde6403f " + runs.get(0).length());
        Rig r = batteryRig(ModelRegistry.Layer.OEM_GROUP);r.connect();r.run(T0 + 5 * S);probe79(r);
        int at = r.batteryCmds.size();r.run(T0 + 3 * 60 * S);
        List<Integer> later = r.batteryCmds.subList(at, r.batteryCmds.size());
        check(later.size() >= 11 && later.stream().allMatch(c -> c == 0x72) && Arrays.equals(r.batteryPolicy.periodicBattery(), new int[]{0x72, 0x79}),
                "no watch: 0x72 alone, the direction read left as it was " + later);
    }
}
