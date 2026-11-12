package com.jjricks.volt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 */
public final class LinkFreshnessTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long S = 1000L, T0 = 1_000_000L, REPLY = 300L, STEP = 50L, WALL0 = 1_792_472_400_000L;

    public static void main(String[] args) throws Exception {
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
        System.out.println("LinkFreshnessTest: " + checks + " checks passed");
    }

    // ---- The source lines the Rig mirrors ------------------------------------------------------------------------

    private static void source() throws Exception {
        String ble = read("src/com/jjricks/volt/BleController.java");
        check(ble.contains("now()-statusMs<=7000&&!serial.isEmpty();}"), "ready() keeps its plain 7 s: controls are admitted as before");
        check(ble.contains("if(session==generation&&gatt!=null&&!linkUp())failed(M.bleFailedTimedOut(),true,ConnectionEventLog.Reason.CONNECT_TIMEOUT,-1);},18000);"),
                "the connect timeout judges the link on its cadence and is still CONNECT_TIMEOUT");
        check(ble.contains("private boolean linkUp(){return StatusCadence.linked(workAllowed()&&state==State.READY&&authenticated&&gatt!=null&&!serial.isEmpty(),statusMs,now(),cadence.fast(),cadence.fastSinceMs());}"),
                "linkUp() is StatusCadence.linked on the chain's cadence");
        check(ble.contains("public boolean linked(){if(simulating())return ready();return linkUp();}"), "linked() is linkUp(); simulation unchanged");
        check(ble.contains("public long readingFreshMs(long baseMs){return simulating()?StatusCadence.readingFreshMs(baseMs,true):StatusCadence.readingFreshMs(baseMs,cadence.fast(),cadence.fastSinceMs(),now());}"),
                "reading freshness on the cadence the link has been on; simulation keeps the in-sight rule");
        check(ble.contains("boolean switched=cadence.cadence(v||r);if(started&&discovering)stopDiscovery();if(!workAllowed())suspend();else if(resumed&&authenticated)refresh(false);if(switched)cadence.retime();}"),
                "visibility(): the cadence switches, the resume refresh is queued, then the pending poll moves");
        check(ble.contains("private void poll(int session){if(simulating())return;cadence.start(()->pollTick(session));}"), "one chain per session, started at authentication");
        check(ble.contains("send(Protocol.frame(0x70,null));refresh(true);poll(generation);"), "the chain starts when the owner is authenticated");
        check(ble.contains("if(StatusCadence.silent(lastFrameMs,at,fast,since)||StatusCadence.silent(statusMs,at,fast,since)){failed(M.bleFailedSignalLost(),true,ConnectionEventLog.Reason.READ_TIMEOUT,-1);return;}"),
                "the silence check judges frames and status on the cadence the link has been on");
        check(ble.contains("lastBatteryPollMs=now();}changed();cadence.next();}"), "each tick re-arms the one chain");
        check(ble.contains("++generation;generationStartMs=now();++writeOperation;cadence.stop();"), "close() ends the chain (disconnect, suspend, simulation, service end)");
        check(ble.contains("if(frame.length==7&&(frame[3]&255)==0x70)cadence.requested();"), "every queued status read counts as the last request");
        check(!ble.contains("StatusCadence.pollMs(visible||recording)") && !ble.contains("StatusCadence.silenceMs(visible||recording)"),
                "no poll or silence rule reads the flags at scheduling time any more");
        String svc = read("src/com/jjricks/volt/ScooterService.java");
        check(svc.contains("c.ready=!destroyed&&!dataMaintenance&&ble.linked()&&"), "a schedule change in flight is judged by linked()");
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

    static final class Clock {
        long now;
        private long seq;
        private final List<long[]> keys = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();
        Clock(long now) { this.now = now; }
        void post(Runnable r, long delay) { keys.add(new long[]{now + Math.max(0, delay), seq++}); tasks.add(r); }
        void cancel(Runnable r) { for (int i = tasks.size() - 1; i >= 0; i--) if (tasks.get(i) == r) { tasks.remove(i); keys.remove(i); } }
        void runUntil(long t) {
            while (true) {
                int best = -1;
                for (int i = 0; i < keys.size(); i++)
                    if (keys.get(i)[0] <= t && (best < 0 || keys.get(i)[0] < keys.get(best)[0] || keys.get(i)[0] == keys.get(best)[0] && keys.get(i)[1] < keys.get(best)[1])) best = i;
                if (best < 0) break;
                now = keys.get(best)[0];keys.remove(best);tasks.remove(best).run();
            }
            now = t;
        }
    }

    /** The chain's tasks, to count pending polls. */
    static final class ChainTimer implements StatusCadence.Timer {
        final Clock clock;
        final Map<Runnable, Runnable> live = new IdentityHashMap<>();
        ChainTimer(Clock clock) { this.clock = clock; }
        public long now() { return clock.now; }
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
        check(ticks.equals(List.of(T0 + 15 * S, T0 + 30 * S, T0 + 45 * S)), "slow: every 15 s " + ticks);
        // In sight at T0 + 50 s with a refresh just queued: the next poll 2 s after it, then every 2 s.
        clock.runUntil(T0 + 50 * S);ticks.clear();
        check(chain.cadence(true) && chain.fastSinceMs() == T0 + 50 * S, "the switch is recorded");
        chain.requested();chain.retime();
        check(timer.live.size() == 1, "retime replaces the pending poll");
        clock.runUntil(T0 + 57 * S);
        check(ticks.equals(List.of(T0 + 52 * S, T0 + 54 * S, T0 + 56 * S)), "fast from the switch: " + ticks);
        // Out of sight: the 2 s chain does not continue; the next poll is 15 s after the last request.
        ticks.clear();clock.runUntil(T0 + 57_500);
        check(chain.cadence(false), "the switch to slow");chain.retime();
        clock.runUntil(T0 + 90 * S);
        check(ticks.equals(List.of(T0 + 71 * S, T0 + 86 * S)), "slow from the last request at 56 s: " + ticks);
        // A recording starts out of sight with an old last request: the retimed poll is due now; the ride start's own
        // refresh queued in the same instant moves it once, to 2 s after that refresh.
        ticks.clear();clock.runUntil(T0 + 95 * S);
        chain.cadence(true);chain.retime();chain.requested();
        clock.runUntil(T0 + 100 * S);
        check(ticks.equals(List.of(T0 + 97 * S, T0 + 99 * S)), "no doubled read after the ride start's refresh: " + ticks);
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

    /**
     * BleController's connect(), authentication, poll chain, silence check and visibility(), with a scooter that answers
     * each status read after REPLY ms while answering is set. old: the code as it was (ready() for the connect timeout,
     * flags read when each poll was scheduled, no retime, the in-sight rule at once).
     */
    static final class Rig {
        final boolean old;
        final Clock clock = new Clock(T0);
        final ChainTimer timer = new ChainTimer(clock);
        final StatusCadence.Chain chain = new StatusCadence.Chain(timer);
        boolean visible, recording, up, answering = true, authenticates = true, oldWouldTimeOut;
        int generation, maxPending;
        long statusMs, lastFrameMs, failedAt = -1;
        final List<String> log = new ArrayList<>();
        final List<Long> requests = new ArrayList<>();
        Rig(boolean old) { this.old = old; }
        long now() { return clock.now; }
        boolean fast() { return visible || recording; }
        boolean ready() { return readyBefore(up, statusMs, now()); }
        boolean linkUp() {
            if (old) return fast() ? ready() : StatusCadence.linked(up, statusMs, now(), false);
            return StatusCadence.linked(up, statusMs, now(), chain.fast(), chain.fastSinceMs());
        }
        void connect() {
            int gen = ++generation;chain.stop();up = false;statusMs = lastFrameMs = 0;log.add("CONNECT_ATTEMPT");
            if (authenticates) clock.post(() -> {
                if (gen != generation) return;
                up = true;lastFrameMs = now();requestStatus();
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
            if (answering) clock.post(() -> { if (gen == generation && up && answering) statusMs = lastFrameMs = now(); }, REPLY);
        }
        void tick(int gen) {
            if (gen != generation || !up) return;
            long since = chain.fastSinceMs();boolean f = chain.fast();
            if (StatusCadence.silent(lastFrameMs, now(), f, since) || StatusCadence.silent(statusMs, now(), f, since)) { fail("READ_TIMEOUT"); return; }
            requestStatus();chain.next();
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
        void fail(String reason) { log.add(reason);if (failedAt < 0) failedAt = now();++generation;chain.stop();up = false;statusMs = lastFrameMs = 0; }
        void visibility(boolean v, boolean r) {
            boolean resumed = v && !visible;visible = v;recording = r;
            boolean switched = chain.cadence(v || r);
            if (resumed && up) requestStatus();
            if (switched && !old) chain.retime();
        }
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

    /** ChargeScheduleController with ScooterService's context (ready = linked()) and BleController's gate (ready()). */
    static final class Schedule implements ChargeScheduleController.Store, ChargeScheduleController.Driver {
        final Rig rig;
        final Map<String, String> saved = new HashMap<>();
        final List<String> sent = new ArrayList<>(), refused = new ArrayList<>(), results = new ArrayList<>();
        final ChargeScheduleController controller = new ChargeScheduleController(this, this);
        final TimeZone zone = TimeZone.getTimeZone("America/Boise");
        long clockAck = 400, timeAck = 400, enableAck = 400;
        /** The switch as the scooter reads it back: on once an ON is acknowledged. */
        int switchRead = 0;
        Schedule(Rig rig) { this.rig = rig; }
        public ChargeScheduleController.Context context() {
            ChargeScheduleController.Context c = new ChargeScheduleController.Context();
            long t = rig.now();c.now = WALL0 + (t - T0);c.mono = t;c.zone = zone;c.scope = "owner-A";c.generation = rig.generation;
            c.ready = rig.linkUp();
            boolean gate = rig.ready();c.toggleReason = gate ? null : "Connect your scooter first";c.timeReason = c.toggleReason;
            if (c.ready) { c.enabled = switchRead;c.wireStart = (WALL0 + 3 * 3600 * S) / 1000 + zone.getOffset(WALL0) / 1000; }
            return c;
        }
        public String read(String key) { return saved.get(key); }
        public void write(String key, String value) { saved.put(key, value); }
        private void send(String what, long ack, Consumer<Boolean> done) {
            if (!rig.ready()) { refused.add(what);done.accept(false);return; }
            sent.add(what);int gen = rig.generation;
            rig.clock.post(() -> done.accept(gen == rig.generation), ack);
        }
        public void clock(Consumer<Boolean> done) { send("clock", clockAck, done); }
        public void time(long wall, int offset, Consumer<Boolean> done) { send("time", timeAck, done); }
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
                    check(s.sent.equals(List.of("clock", "time", "on")) && s.refused.isEmpty(), "clock, time and the switch all went out " + s.sent);
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
            if (old) { check(s.results.size() == 1 && s.results.get(0) != null && s.sent.equals(List.of("clock", "time")), "the old rule interrupted it at the return " + s.results); continue; }
            check(s.results.size() == 1 && s.results.get(0) == null && s.sent.equals(List.of("clock", "time", "on")), "not interrupted: it completed " + s.results + " " + s.sent);
        }
        // A genuinely lost link still interrupts and sends nothing more.
        Rig r = new Rig(false);Schedule s = new Schedule(r);r.visible = true;r.chain.cadence(true);r.connect();
        s.run(T0 + 10 * S);s.timeAck = 5 * S;s.controller.setManualEnabled(true, s.results::add);s.run(T0 + 11 * S);
        r.fail("NATIVE_DISCONNECT");s.run(T0 + 20 * S);
        check(s.results.size() == 1 && s.results.get(0) != null && s.sent.equals(List.of("clock", "time")), "a lost link interrupts, nothing more is sent " + s.results + " " + s.sent);
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
        check(slow.equals(List.of(last + 15 * S, last + 30 * S, last + 45 * S, last + 60 * S)), "out of sight: every 15 s from the last read " + slow);
        // A recording starts out of sight; the ride start's refresh goes in the same instant: one read, then every 2 s.
        long start = r.now();before = r.requests.size();
        r.visibility(false, true);r.requestStatus();
        r.run(start + 7 * S);
        List<Long> rec = r.requests.subList(before, r.requests.size());
        check(rec.equals(List.of(start, start + 2 * S, start + 4 * S, start + 6 * S)), "recording: the refresh, then every 2 s " + rec);
        check(r.maxPending <= 1 && !r.failed() && r.ready(), "still one chain, healthy");
        // Rotation: pause and resume in the same instant, repeatedly.
        for (int i = 0; i < 5; i++) { r.visibility(false, false);r.visibility(true, false); }
        r.run(r.now() + 10 * S);
        check(r.maxPending <= 1 && !r.failed() && r.ready() && r.now() - r.statusMs <= StatusCadence.POLL_FAST_MS + REPLY, "rotations: one chain, fresh status");
    }
}
