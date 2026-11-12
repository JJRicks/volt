package com.jjricks.volt;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * Report diagnostics' charging evidence, one private file per saved scooter (no_backup/charge-evidence/&lt;profile id&gt;):
 * the last ended charging intentions and schedule tests, a charging timeline, dense sample windows around a Volt-armed
 * start and after a test, the schedule operations with their result ids, and readbacks that changed with no Volt write
 * to explain them. Every value is a number or an enum ordinal: there is no field a name, serial, address, key, PIN,
 * place or path could go in. It never sends anything and never changes what the schedule does.
 * <p>
 * Threading: callers (the main thread) only hand over typed values; loading, the rules and writing run on one worker.
 * Durability: at most one write every 5 s per scooter; an event is on disk within about 5 s, a dense sample within
 * 15 s, and the last-seen reading within 5 min (it joins the timeline at the next start if the process was killed).
 * Pure Java: the service passes its clocks and the command log.
 */
final class ChargeEvidence implements ChargeScheduleController.Journal {
    static final int INTENTIONS = 10, TESTS = 5, TIMELINE = 300, WINDOWS = 3, WINDOW_SAMPLES = 180, OPERATIONS = 100, CHANGES = 100;
    /** Charging changes stored one by one per rolling minute; more are counted on the next stored row. */
    static final int CHANGES_PER_MINUTE = 30;
    static final long BEFORE_START_MS = 30_000, AFTER_START_MS = 5 * 60_000, AFTER_TEST_MS = 5 * 60_000, DENSE_GAP_MS = 2_000;
    /** Writes per scooter are at least SPACING_MS apart; within that, an event goes at once, a dense sample within 15 s, the last-seen reading within 5 min. */
    static final long SPACING_MS = 5_000, EVENT_WRITE_MS = 0, DENSE_WRITE_MS = 15_000, LAST_SEEN_WRITE_MS = 5 * 60_000;
    /** A lock reading this old no longer describes the moment of arming or of an outcome. */
    static final long LOCK_FRESH_MS = 30_000;
    static final int MAX_FILE_BYTES = 256 * 1024;
    static final long NONE = Long.MIN_VALUE;
    private static final int MAGIC = 0x56434556, VERSION = 1, CRC_BYTES = 8;
    private static final long MAX_WALL = 0xffffffffL * 1000L, MAX_ELAPSED = 1L << 50;

    // A sample, as the service captured it: only fresh readings from the current authenticated link; NONE is unknown.
    static final int S_WALL = 0, S_ELAPSED = 1, S_GENERATION = 2, S_CHARGING = 3, S_SOC = 4, S_MV = 5, S_MA = 6, S_W = 7, S_TEMP = 8,
            S_ENABLED = 9, S_WIRE = 10, S_LOCK = 11, S_LIMIT = 12, S_VISIBLE = 13, S_COLUMNS = 14;
    /** Timeline row kinds: a charging change, the first reading after a connection, the last before a disconnection, and a last reading saved before the process ended. */
    enum Kind { CHANGE, FIRST, LAST, LAST_SAVED }
    static final int T_KIND = 0, T_WALL = 1, T_ELAPSED = 2, T_CHARGING = 3, T_SOC = 4, T_MV = 5, T_MA = 6, T_W = 7, T_TEMP = 8, T_ENABLED = 9,
            T_WIRE = 10, T_LOCK = 11, T_VISIBLE = 12, T_SKIPPED = 13, T_COLUMNS = 14;
    /** Dense windows: around a Volt-armed start, and after a schedule test ended. */
    enum Window { START, TEST_END }
    static final int W_KIND = 0, W_ANCHOR_WALL = 1, W_ANCHOR_ELAPSED = 2, W_COLUMNS = 3;
    static final int D_WALL = 0, D_ELAPSED = 1, D_CHARGING = 2, D_SOC = 3, D_MV = 4, D_MA = 5, D_W = 6, D_ENABLED = 7, D_LOCK = 8, D_COLUMNS = 9;
    static final int O_WALL = 0, O_ELAPSED = 1, O_OP = 2, O_RESULT = 3, O_WHY = 4, O_DURATION = 5, O_TEST_WALL = 6, O_TEST_OUTCOME = 7, O_COLUMNS = 8;
    /** Readbacks watched for changes Volt did not make, with the command-log key that would explain each. */
    enum Watched {
        TIMED_CHARGING("timed_charging", S_ENABLED), CHARGE_START("charge_start", S_WIRE), LOCK("lock", S_LOCK), CHARGE_LIMIT("charge_limit", S_LIMIT);
        final String key; final int column;
        Watched(String key, int column) { this.key = key; this.column = column; }
    }
    static final int C_WALL = 0, C_ELAPSED = 1, C_KEY = 2, C_OLD = 3, C_NEW = 4, C_OLD_WALL = 5, C_ACROSS = 6, C_COLUMNS = 7;
    /** An ended intention: END_OPEN while it runs, an Outcome ordinal, or END_REPLACED when Volt armed another start first. */
    static final long END_OPEN = -1, END_REPLACED = 1000;
    static final int I_ARMED = 0, I_MODE = 1, I_START = 2, I_DEADLINE = 3, I_WIRE = 4, I_OFFSET = 5, I_ARMED_SOC = 6, I_TARGET = 7, I_STARTED = 8,
            I_END = 9, I_END_WALL = 10, I_END_MODE = 11, I_END_SOC = 12, I_END_ENABLED = 13, I_END_WIRE = 14, I_LOCK_ARMED = 15, I_LOCK_START = 16,
            I_LOCK_END = 17, I_START_SEEN = 18, I_COLUMNS = 19;
    static final int X_WALL = 0, X_START = 1, X_OUTCOME = 2, X_OFFSET = 3, X_PAUSED = 4, X_OFF = 5, X_WHY = 6, X_COLUMNS = 7;
    static final int F_VALUE = 0, F_WALL = 1, F_ELAPSED = 2, F_COLUMNS = 3;

    private static final long[][] SAMPLE_RANGE = {{0, MAX_WALL}, {0, MAX_ELAPSED}, {0, Integer.MAX_VALUE}, {0, 1}, {0, 100}, {0, 250_000}, {-1_000_000, 1_000_000},
            {-100_000, 100_000}, {-80, 200}, {0, 1}, {0, 0xffffffffL}, {0, 1}, {0, 100}, {0, 1}};
    private static final long[][] TIMELINE_RANGE = {{0, Kind.values().length - 1}, {0, MAX_WALL}, {0, MAX_ELAPSED}, {0, 1}, {0, 100}, {0, 250_000}, {-1_000_000, 1_000_000},
            {-100_000, 100_000}, {-80, 200}, {0, 1}, {0, 0xffffffffL}, {0, 1}, {0, 1}, {0, Integer.MAX_VALUE}};
    private static final long[][] WINDOW_RANGE = {{0, Window.values().length - 1}, {0, MAX_WALL}, {0, MAX_ELAPSED}};
    private static final long[][] DENSE_RANGE = {{0, MAX_WALL}, {0, MAX_ELAPSED}, {0, 1}, {0, 100}, {0, 250_000}, {-1_000_000, 1_000_000}, {-100_000, 100_000}, {0, 1}, {0, 1}};
    private static final long[][] OPERATION_RANGE = {{0, MAX_WALL}, {0, MAX_ELAPSED}, {0, ChargeScheduleController.Op.values().length - 1},
            {0, ChargeScheduleController.Result.values().length - 1}, {0, ChargeScheduleController.Interruption.values().length - 1}, {0, 7L * 24 * 3600_000},
            {0, MAX_WALL}, {0, ChargeScheduleTest.Outcome.values().length - 1}};
    private static final long[][] CHANGE_RANGE = {{0, MAX_WALL}, {0, MAX_ELAPSED}, {0, Watched.values().length - 1}, {0, 0xffffffffL}, {0, 0xffffffffL}, {0, MAX_WALL}, {0, 1}};
    private static final long[][] INTENTION_RANGE = {{0, MAX_WALL}, {0, 2}, {0, MAX_WALL}, {0, MAX_WALL}, {0, 0xffffffffL}, {-18 * 3600, 18 * 3600}, {0, 100}, {0, 100}, {0, 1},
            {END_OPEN, END_REPLACED}, {0, MAX_WALL}, {0, 2}, {0, 100}, {0, 1}, {0, 0xffffffffL}, {0, 1}, {0, 1}, {0, 1}, {0, 1}};
    private static final long[][] TEST_RANGE = {{0, MAX_WALL}, {0, MAX_WALL}, {0, ChargeScheduleTest.Outcome.values().length - 1}, {-86_400, 86_400}, {0, 1}, {0, 1},
            {0, ChargeScheduleController.Interruption.values().length - 1}};
    private static final long[][] FRESH_RANGE = {{0, 0xffffffffL}, {0, MAX_WALL}, {0, MAX_ELAPSED}};

    /** One scooter's evidence. Guarded by the ChargeEvidence monitor; changed only on the worker. */
    static final class Book {
        final List<long[]> intentions = new ArrayList<>(), tests = new ArrayList<>(), timeline = new ArrayList<>(), operations = new ArrayList<>(), changes = new ArrayList<>();
        final List<long[]> windows = new ArrayList<>();
        final List<List<long[]>> windowSamples = new ArrayList<>();
        long[] open, lastSeen;
        boolean lastSeenPending;
        final long[][] fresh = new long[Watched.values().length][];
        // Memory only: the live connection, the last charging state, recent change times, a test's end.
        int liveGeneration = -1;
        final int[] freshGeneration = {-1, -1, -1, -1};
        long lastCharging = NONE, skipped, testEndWall = NONE, testEndElapsed = NONE;
        final ArrayDeque<Long> recentChanges = new ArrayDeque<>();
        boolean healthy = true;
        Book copy() {
            Book b = new Book();
            for (long[] r : intentions) b.intentions.add(r.clone()); for (long[] r : tests) b.tests.add(r.clone());
            for (long[] r : timeline) b.timeline.add(r.clone()); for (long[] r : operations) b.operations.add(r.clone());
            for (long[] r : changes) b.changes.add(r.clone()); for (long[] r : windows) b.windows.add(r.clone());
            for (List<long[]> w : windowSamples) { List<long[]> c = new ArrayList<>(); for (long[] r : w) c.add(r.clone()); b.windowSamples.add(c); }
            b.open = open == null ? null : open.clone(); b.lastSeen = lastSeen == null ? null : lastSeen.clone(); b.lastSeenPending = lastSeenPending;
            for (int i = 0; i < fresh.length; i++) b.fresh[i] = fresh[i] == null ? null : fresh[i].clone();
            b.healthy = healthy; return b;
        }
    }
    /** What the diagnostics file shows for one scooter. */
    static final class Snapshot {
        final boolean loaded; final Book book;
        Snapshot(boolean loaded, Book book) { this.loaded = loaded; this.book = book; }
    }

    private final File dir;
    private final Supplier<List<CommandLog.Record>> commands;
    private final LongSupplier wall, elapsed;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "charge-diagnostics"); thread.setDaemon(true); return thread;
    });
    private final Map<String, Book> books = new HashMap<>();
    /** When each scooter last went to disk (elapsed), and when its next write is due. */
    private final Map<String, Long> lastWrite = new HashMap<>(), due = new HashMap<>();
    // Main thread only: the last sample handed over, to skip repeats of an unchanged reading.
    private String offeredScope; private long[] offered;

    ChargeEvidence(File dir, Supplier<List<CommandLog.Record>> commands, LongSupplier wall, LongSupplier elapsed) {
        this.dir = dir; this.commands = commands; this.wall = wall; this.elapsed = elapsed;
    }

    /** A saved scooter's id: the file name, never shown in the file. Simulated scopes ("simulation:…") never qualify. */
    static boolean scope(String scope) { return scope != null && scope.matches("[0-9A-Za-z-]{1,64}"); }

    // ---- Called on the main thread: hand typed values to the worker ----

    /**
     * One reading from the current authenticated link (S_* columns, NONE for unknown); pending is the write in flight
     * (BleController.pending()), or null. Never called for a simulated link. Out-of-range values become unknown.
     */
    void offer(String scope, long[] sample, String pending) {
        if (!scope(scope) || sample == null || sample.length != S_COLUMNS) return;
        long[] s = sample.clone();
        for (int i = 0; i < S_COLUMNS; i++) if (s[i] != NONE && (s[i] < SAMPLE_RANGE[i][0] || s[i] > SAMPLE_RANGE[i][1])) s[i] = NONE;
        if (s[S_WALL] == NONE || s[S_ELAPSED] == NONE || s[S_GENERATION] == NONE) return;
        // An unchanged reading within a second adds nothing: skip it before it reaches the worker.
        if (pending == null && scope.equals(offeredScope) && offered != null && s[S_GENERATION] == offered[S_GENERATION]
                && s[S_ELAPSED] >= offered[S_ELAPSED] && s[S_ELAPSED] - offered[S_ELAPSED] < 1000 && same(s, offered)) return;
        offeredScope = scope; offered = s;
        final String key = pending != null && pending.matches("[a-z_]{1,40}") ? pending : null;
        run(() -> sample(book(scope), scope, s, key));
    }
    /** The link to this scooter ended (or stopped being authenticated): its last reading closes the connection on the timeline. */
    void unlinked(String scope) {
        if (!scope(scope)) return;
        offered = null;
        run(() -> { Book b = book(scope); synchronized (this) { if (b.liveGeneration < 0) return; closeLink(b, Kind.LAST); b.liveGeneration = -1; } dirty(scope, EVENT_WRITE_MS); });
    }
    /** Loads a scooter's file ahead of a report, so the file can show it. */
    void prefetch(String scope) { if (scope(scope)) run(() -> book(scope)); }
    @Override public void saved(String scope, ChargeScheduleController.Diagnostics d) {
        if (!scope(scope) || d == null) return;
        long now = wall.getAsLong(), mono = elapsed.getAsLong();
        run(() -> { Book b = book(scope); boolean changed; synchronized (this) { changed = record(b, d, now, mono); } if (changed) dirty(scope, EVENT_WRITE_MS); });
    }
    @Override public void operation(String scope, ChargeScheduleController.Op op, ChargeScheduleController.Result result, ChargeScheduleController.Interruption why,
            long durationMs, long testWallMs, ChargeScheduleTest.Outcome testOutcome) {
        if (!scope(scope) || op == null || result == null) return;
        long now = wall.getAsLong(), mono = elapsed.getAsLong();
        long[] row = {now, mono, op.ordinal(), result.ordinal(), why == null ? NONE : why.ordinal(), Math.min(Math.max(0, durationMs), OPERATION_RANGE[O_DURATION][1]),
                testWallMs > 0 ? testWallMs : NONE, testOutcome == null ? NONE : testOutcome.ordinal()};
        if (!valid(row, OPERATION_RANGE)) return;
        run(() -> {
            Book b = book(scope);
            synchronized (this) {
                add(b.operations, row, OPERATIONS);
                if (op == ChargeScheduleController.Op.TEST_END) {
                    for (long[] t : b.tests) if (t[X_WALL] == testWallMs && why != null) t[X_WHY] = why.ordinal();
                    b.testEndWall = now; b.testEndElapsed = mono;
                }
            }
            dirty(scope, EVENT_WRITE_MS);
        });
    }
    /** Forgetting a scooter removes its evidence. */
    void forget(String scope) {
        if (!scope(scope)) return;
        run(() -> { synchronized (this) { books.remove(scope); } due.remove(scope); lastWrite.remove(scope); delete(scope); });
    }
    /** Removes files of scooters no longer saved (keep: the saved profile ids). */
    void prune(Collection<String> keep) {
        Set<String> kept = new HashSet<>(keep);
        run(() -> {
            String[] names = dir.list(); if (names == null) return;
            for (String name : names) {
                String scope = name.endsWith(".tmp") ? name.substring(0, name.length() - 4) : name;
                if (kept.contains(scope)) continue;
                synchronized (this) { books.remove(scope); } due.remove(scope);
                try { Files.deleteIfExists(new File(dir, name).toPath()); } catch (IOException | SecurityException ignored) {}
            }
        });
    }
    /** A nonblocking copy for the report file; loaded is false until the worker has read this scooter's file. */
    synchronized Snapshot snapshot(String scope) {
        Book b = scope(scope) ? books.get(scope) : null;
        return b == null ? new Snapshot(false, new Book()) : new Snapshot(true, b.copy());
    }
    /** Writes everything pending now. Tests and process end only; never wait for this on the main thread. */
    Future<?> flush() { return worker.submit(() -> { for (String scope : new ArrayList<>(due.keySet())) write(scope); }); }
    /** Service end: everything handed over before this is written, then the worker stops; nothing waits for it. Later calls do nothing. */
    void close() {
        try { worker.execute(() -> { try { for (String scope : new ArrayList<>(due.keySet())) write(scope); } finally { worker.shutdownNow(); } }); }
        catch (RejectedExecutionException closed) { /* already closed */ }
    }

    private void run(Runnable task) {
        try { worker.execute(() -> { try { task.run(); } catch (RuntimeException ignored) { /* diagnostics never change behaviour */ } }); }
        catch (RejectedExecutionException closed) { /* shut down */ }
    }
    private static boolean same(long[] a, long[] b) {
        for (int i = S_CHARGING; i < S_COLUMNS; i++) if (a[i] != b[i]) return false;
        return true;
    }

    // ---- Worker ----

    private Book book(String scope) {
        synchronized (this) { Book b = books.get(scope); if (b != null) return b; }
        Book loaded = read(new File(dir, scope));
        synchronized (this) {
            Book b = books.get(scope); if (b != null) return b;
            // A last reading saved before the process ended joins the timeline now.
            if (loaded.lastSeenPending && loaded.lastSeen != null) { add(loaded.timeline, row(Kind.LAST_SAVED, loaded.lastSeen, 0), TIMELINE); loaded.lastSeenPending = false; }
            books.put(scope, loaded); return loaded;
        }
    }

    private void sample(Book b, String scope, long[] s, String pending) {
        long delay = LAST_SEEN_WRITE_MS;
        synchronized (this) {
            int generation = (int) s[S_GENERATION];
            if (b.liveGeneration != generation) {
                if (b.liveGeneration >= 0) closeLink(b, Kind.LAST);
                b.liveGeneration = generation; b.recentChanges.clear();
                add(b.timeline, row(Kind.FIRST, s, takeSkipped(b)), TIMELINE); b.lastCharging = s[S_CHARGING]; delay = EVENT_WRITE_MS;
            } else if (s[S_CHARGING] != NONE && s[S_CHARGING] != b.lastCharging) {
                b.lastCharging = s[S_CHARGING];
                long now = s[S_ELAPSED];
                while (!b.recentChanges.isEmpty() && (now - b.recentChanges.peekFirst() >= 60_000 || now < b.recentChanges.peekFirst())) b.recentChanges.removeFirst();
                if (b.recentChanges.size() < CHANGES_PER_MINUTE) { b.recentChanges.addLast(now); add(b.timeline, row(Kind.CHANGE, s, takeSkipped(b)), TIMELINE); delay = EVENT_WRITE_MS; }
                else b.skipped++;
            }
            b.lastSeen = s; b.lastSeenPending = true;
            // The lock as the Volt-armed start passed.
            if (b.open != null && b.open[I_START_SEEN] == 0 && s[S_WALL] >= b.open[I_START]) { b.open[I_START_SEEN] = 1; b.open[I_LOCK_START] = s[S_LOCK]; delay = Math.min(delay, EVENT_WRITE_MS); }
            if (dense(b, s)) delay = Math.min(delay, DENSE_WRITE_MS);
        }
        if (changes(b, s, pending)) delay = EVENT_WRITE_MS;
        dirty(scope, delay);
    }
    private static long takeSkipped(Book b) { long n = b.skipped; b.skipped = 0; return n; }
    /** The last reading of a connection that ended, once. */
    private static void closeLink(Book b, Kind kind) {
        if (b.lastSeen != null && b.lastSeenPending) add(b.timeline, row(kind, b.lastSeen, takeSkipped(b)), TIMELINE);
        b.lastSeenPending = false;
    }
    private static long[] row(Kind kind, long[] s, long skipped) {
        return new long[]{kind.ordinal(), s[S_WALL], s[S_ELAPSED], s[S_CHARGING], s[S_SOC], s[S_MV], s[S_MA], s[S_W], s[S_TEMP], s[S_ENABLED], s[S_WIRE], s[S_LOCK], s[S_VISIBLE], skipped};
    }
    /** One sample per existing status poll, at least DENSE_GAP_MS apart, into each window open now. */
    private static boolean dense(Book b, long[] s) {
        boolean added = false;
        if (b.open != null && b.open[I_MODE] != 0 && b.open[I_START] > 0 && s[S_WALL] >= b.open[I_START] - BEFORE_START_MS && s[S_WALL] <= b.open[I_START] + AFTER_START_MS)
            added |= denseInto(b, Window.START, b.open[I_START], NONE, s);
        if (b.testEndElapsed != NONE && s[S_ELAPSED] >= b.testEndElapsed && s[S_ELAPSED] - b.testEndElapsed <= AFTER_TEST_MS)
            added |= denseInto(b, Window.TEST_END, b.testEndWall, b.testEndElapsed, s);
        return added;
    }
    private static boolean denseInto(Book b, Window kind, long anchorWall, long anchorElapsed, long[] s) {
        int index = -1;
        for (int i = 0; i < b.windows.size(); i++) if (b.windows.get(i)[W_KIND] == kind.ordinal() && b.windows.get(i)[W_ANCHOR_WALL] == anchorWall) index = i;
        if (index < 0) {
            b.windows.add(new long[]{kind.ordinal(), anchorWall, anchorElapsed}); b.windowSamples.add(new ArrayList<>());
            while (b.windows.size() > WINDOWS) { b.windows.remove(0); b.windowSamples.remove(0); }
            index = b.windows.size() - 1;
        }
        List<long[]> samples = b.windowSamples.get(index);
        if (samples.size() >= WINDOW_SAMPLES) return false;
        if (!samples.isEmpty()) { long last = samples.get(samples.size() - 1)[D_ELAPSED]; if (s[S_ELAPSED] >= last && s[S_ELAPSED] - last < DENSE_GAP_MS) return false; }
        samples.add(new long[]{s[S_WALL], s[S_ELAPSED], s[S_CHARGING], s[S_SOC], s[S_MV], s[S_MA], s[S_W], s[S_ENABLED], s[S_LOCK]});
        return true;
    }
    /** Changes Volt did not make: each watched fresh readback against the last fresh one. */
    private boolean changes(Book b, long[] s, String pending) {
        List<long[]> found = new ArrayList<>();
        List<CommandLog.Record> log = null;
        synchronized (this) {
            for (Watched w : Watched.values()) {
                long value = s[w.column]; if (value == NONE) continue;
                long[] last = b.fresh[w.ordinal()];
                if (last != null && last[F_VALUE] != value && !w.key.equals(pending)) {
                    boolean across = b.freshGeneration[w.ordinal()] != (int) s[S_GENERATION];
                    // A phone clock change between the two readings moves the command log's wall times; widen the search by it.
                    long drift = !across && s[S_ELAPSED] >= last[F_ELAPSED] ? Math.abs((s[S_WALL] - last[F_WALL]) - (s[S_ELAPSED] - last[F_ELAPSED])) : 0;
                    found.add(new long[]{s[S_WALL], s[S_ELAPSED], w.ordinal(), last[F_VALUE], value, last[F_WALL], across ? 1 : 0, last[F_WALL] - 5_000 - drift});
                }
                b.fresh[w.ordinal()] = new long[]{value, s[S_WALL], s[S_ELAPSED]}; b.freshGeneration[w.ordinal()] = (int) s[S_GENERATION];
            }
        }
        if (found.isEmpty()) return false;
        boolean any = false;
        for (long[] f : found) {
            if (log == null) { try { log = commands.get(); } catch (RuntimeException unavailable) { log = Collections.emptyList(); } if (log == null) log = Collections.emptyList(); }
            Watched w = Watched.values()[(int) f[C_KEY]];
            if (voltWrote(log, w, f[C_NEW], f[7])) continue;
            synchronized (this) { add(b.changes, Arrays.copyOf(f, C_COLUMNS), CHANGES); }
            any = true;
        }
        return any;
    }
    /**
     * Whether the command log holds a Volt write of this control and value at or after sinceWallMs that the scooter may
     * have applied: sent (not refused, rejected or cancelled before sending), from a real connection. A write whose
     * confirmation was lost (timeout, unconfirmed, interrupted) counts: it can read back later.
     */
    static boolean voltWrote(List<CommandLog.Record> log, Watched w, long value, long sinceWallMs) {
        boolean simulated = false;
        if (log == null) return false;
        for (CommandLog.Record r : log) {
            if (r instanceof CommandLog.Session) { simulated = ((CommandLog.Session) r).simulated; continue; }
            CommandLog.Command c = (CommandLog.Command) r;
            if (simulated || c.wallMs < sinceWallMs || !c.key.equals(w.key) || c.kind == CommandLog.Kind.READ) continue;
            if (c.outcome == CommandLog.Outcome.REFUSED || c.outcome == CommandLog.Outcome.REJECTED || c.outcome == CommandLog.Outcome.CANCELLED) continue;
            if (w == Watched.CHARGE_START) { if (c.time == null || c.time == value) return true; }
            else if (c.value == null) return true;
            else if (w == Watched.LOCK ? (c.value != 0 ? 1 : 0) == value : c.value == value) return true;
        }
        return false;
    }
    /** Intentions and tests from a saved schedule record; true when anything changed. */
    private boolean record(Book b, ChargeScheduleController.Diagnostics d, long now, long mono) {
        boolean changed = false;
        if (d.mode != 0 && d.startWallMs > 0) {
            if (b.open != null && b.open[I_START] != d.startWallMs) { b.open[I_END] = END_REPLACED; b.open[I_END_WALL] = now; b.open[I_LOCK_END] = lock(b, mono); add(b.intentions, b.open, INTENTIONS); b.open = null; }
            if (b.open == null) {
                b.open = new long[I_COLUMNS]; Arrays.fill(b.open, NONE); b.open[I_END] = END_OPEN; b.open[I_START_SEEN] = 0;
                b.open[I_ARMED] = d.armedWallMs > 0 ? d.armedWallMs : now; b.open[I_LOCK_ARMED] = lock(b, mono);
            }
            long[] o = b.open, before = o.clone();
            o[I_MODE] = d.mode; o[I_START] = d.startWallMs; o[I_DEADLINE] = d.deadlineWallMs > 0 ? d.deadlineWallMs : NONE; o[I_WIRE] = d.wireStart > 0 ? d.wireStart : NONE;
            o[I_OFFSET] = d.clockOffsetSeconds == null ? NONE : d.clockOffsetSeconds; o[I_ARMED_SOC] = d.armedSoc == null ? NONE : d.armedSoc; o[I_TARGET] = d.target;
            o[I_STARTED] = d.started ? 1 : 0;
            changed = !Arrays.equals(before, o) || changed;
            if (!valid(o, INTENTION_RANGE)) { b.open = null; return changed; }
        }
        if (d.outcome != ChargeScheduleController.Outcome.NONE && d.outcomeWallMs > 0) {
            boolean seen = false;
            for (long[] r : b.intentions) seen |= r[I_END] != END_REPLACED && r[I_END_WALL] == d.outcomeWallMs;
            if (!seen) {
                long[] o = b.open != null && b.open[I_START] == d.outcomeStartWallMs ? b.open : null;
                if (o == null) { o = new long[I_COLUMNS]; Arrays.fill(o, NONE); o[I_START] = d.outcomeStartWallMs > 0 ? d.outcomeStartWallMs : NONE; o[I_START_SEEN] = 0; o[I_TARGET] = d.target;
                    o[I_ARMED_SOC] = d.outcomeArmedSoc == null ? NONE : d.outcomeArmedSoc; }
                o[I_END] = d.outcome.ordinal(); o[I_END_WALL] = d.outcomeWallMs; o[I_END_MODE] = d.outcomeMode; o[I_END_SOC] = d.outcomeSoc == null ? NONE : d.outcomeSoc;
                o[I_END_ENABLED] = d.outcomeEnabled == null ? NONE : d.outcomeEnabled; o[I_END_WIRE] = d.outcomeWireStart == null ? NONE : d.outcomeWireStart; o[I_LOCK_END] = lock(b, mono);
                if (o == b.open) b.open = null;
                if (valid(o, INTENTION_RANGE)) add(b.intentions, o, INTENTIONS);
                changed = true;
            }
        }
        if (d.testOutcome != ChargeScheduleTest.Outcome.NONE && d.testWallMs > 0) {
            long[] t = null;
            for (long[] r : b.tests) if (r[X_WALL] == d.testWallMs) t = r;
            long[] next = {d.testWallMs, d.testStartWallMs, d.testOutcome.ordinal(), d.testOffsetSeconds == null ? NONE : d.testOffsetSeconds, d.testPaused ? 1 : 0,
                    d.testScheduleOff == null ? NONE : d.testScheduleOff, t == null ? NONE : t[X_WHY]};
            if (valid(next, TEST_RANGE) && (t == null || !Arrays.equals(t, next))) {
                if (t == null) add(b.tests, next, TESTS); else System.arraycopy(next, 0, t, 0, X_COLUMNS);
                changed = true;
            }
        }
        return changed;
    }
    /** The lock from the latest reading of the live connection, if recent; else unknown. */
    private static long lock(Book b, long mono) {
        return b.liveGeneration >= 0 && b.lastSeen != null && mono >= b.lastSeen[S_ELAPSED] && mono - b.lastSeen[S_ELAPSED] <= LOCK_FRESH_MS ? b.lastSeen[S_LOCK] : NONE;
    }
    private static void add(List<long[]> list, long[] row, int cap) { list.add(row); while (list.size() > cap) list.remove(0); }

    // ---- Storage ----

    /** Schedules this scooter's write: no sooner than 5 s after its last one, and no later than delayMs from now. */
    private void dirty(String scope, long delayMs) {
        long now = elapsed.getAsLong();
        Long last = lastWrite.get(scope), current = due.get(scope);
        long at = Math.max(now + delayMs, last == null ? now : last + SPACING_MS);
        if (current != null && current <= at) return;
        due.put(scope, at);
        try { worker.schedule(() -> { Long when = due.get(scope); if (when != null && when == at) write(scope); }, Math.max(0, at - now), TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException closed) { /* shut down */ }
    }
    private void write(String scope) {
        due.remove(scope); lastWrite.put(scope, elapsed.getAsLong());
        byte[] bytes;
        synchronized (this) { Book b = books.get(scope); if (b == null) return; bytes = encode(b); }
        boolean ok = true;
        try { store(new File(dir, scope), bytes); } catch (IOException | SecurityException unavailable) { ok = false; }
        synchronized (this) { Book b = books.get(scope); if (b != null) b.healthy = ok; }
    }
    private void delete(String scope) {
        try { Files.deleteIfExists(new File(dir, scope).toPath()); Files.deleteIfExists(new File(dir, scope + ".tmp").toPath()); } catch (IOException | SecurityException ignored) {}
    }

    static byte[] encode(Book b) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC); out.writeInt(VERSION);
            List<long[]> intentions = new ArrayList<>(b.intentions); if (b.open != null) intentions.add(b.open);
            rows(out, intentions, I_COLUMNS); rows(out, b.tests, X_COLUMNS); rows(out, b.timeline, T_COLUMNS); rows(out, b.operations, O_COLUMNS); rows(out, b.changes, C_COLUMNS);
            rows(out, b.windows, W_COLUMNS); for (List<long[]> w : b.windowSamples) rows(out, w, D_COLUMNS);
            List<long[]> fresh = new ArrayList<>();
            for (long[] f : b.fresh) { long[] r = new long[F_COLUMNS]; Arrays.fill(r, NONE); if (f != null) System.arraycopy(f, 0, r, 0, F_COLUMNS); fresh.add(r); }
            rows(out, fresh, F_COLUMNS);
            rows(out, b.lastSeen == null ? Collections.<long[]>emptyList() : Collections.singletonList(b.lastSeen), S_COLUMNS);
            out.writeBoolean(b.lastSeenPending);
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        byte[] body = buffer.toByteArray();
        CRC32 crc = new CRC32(); crc.update(body);
        byte[] all = Arrays.copyOf(body, body.length + CRC_BYTES);
        long v = crc.getValue(); for (int i = 0; i < CRC_BYTES; i++) all[body.length + i] = (byte) (v >>> (56 - 8 * i));
        return all;
    }
    private static void rows(DataOutputStream out, List<long[]> rows, int columns) throws IOException {
        out.writeShort(rows.size()); out.writeByte(columns);
        for (long[] r : rows) for (int i = 0; i < columns; i++) out.writeLong(r[i]);
    }
    /** A missing file is an empty book; a corrupt, oversized or unknown one reads as empty and unhealthy. Never throws. */
    static Book read(File file) {
        Book b = new Book();
        try {
            if (!file.exists()) return b;
            if (file.length() > MAX_FILE_BYTES || file.length() < 8 + CRC_BYTES) throw new IOException("Invalid charge evidence size");
            return decode(Files.readAllBytes(file.toPath()));
        } catch (IOException | RuntimeException corrupt) { Book empty = new Book(); empty.healthy = false; return empty; }
    }
    static Book decode(byte[] all) throws IOException {
        if (all.length > MAX_FILE_BYTES || all.length < 8 + CRC_BYTES) throw new IOException("Invalid charge evidence size");
        CRC32 crc = new CRC32(); crc.update(all, 0, all.length - CRC_BYTES);
        long v = 0; for (int i = 0; i < CRC_BYTES; i++) v = v << 8 | (all[all.length - CRC_BYTES + i] & 255);
        if (v != crc.getValue()) throw new IOException("Corrupt charge evidence");
        Book b = new Book();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(all, 0, all.length - CRC_BYTES))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unknown charge evidence");
            List<long[]> intentions = rows(in, I_COLUMNS, INTENTIONS + 1, INTENTION_RANGE);
            for (long[] r : intentions) { if (r[I_END] == END_OPEN) { if (b.open != null) throw new IOException("Two open intentions"); b.open = r; } else b.intentions.add(r); }
            if (b.intentions.size() > INTENTIONS) throw new IOException("Too many intentions");
            b.tests.addAll(rows(in, X_COLUMNS, TESTS, TEST_RANGE)); b.timeline.addAll(rows(in, T_COLUMNS, TIMELINE, TIMELINE_RANGE));
            b.operations.addAll(rows(in, O_COLUMNS, OPERATIONS, OPERATION_RANGE)); b.changes.addAll(rows(in, C_COLUMNS, CHANGES, CHANGE_RANGE));
            b.windows.addAll(rows(in, W_COLUMNS, WINDOWS, WINDOW_RANGE));
            for (int i = 0; i < b.windows.size(); i++) b.windowSamples.add(rows(in, D_COLUMNS, WINDOW_SAMPLES, DENSE_RANGE));
            List<long[]> fresh = rows(in, F_COLUMNS, Watched.values().length, FRESH_RANGE);
            if (fresh.size() != Watched.values().length) throw new IOException("Invalid readbacks");
            for (int i = 0; i < fresh.size(); i++) b.fresh[i] = fresh.get(i)[F_VALUE] == NONE ? null : fresh.get(i);
            List<long[]> last = rows(in, S_COLUMNS, 1, SAMPLE_RANGE);
            b.lastSeen = last.isEmpty() ? null : last.get(0); b.lastSeenPending = in.readBoolean() && b.lastSeen != null;
            if (in.read() != -1) throw new IOException("Unexpected charge evidence data");
        }
        return b;
    }
    private static List<long[]> rows(DataInputStream in, int columns, int cap, long[][] ranges) throws IOException {
        int count = in.readUnsignedShort(), stored = in.readUnsignedByte();
        if (stored != columns || count > cap) throw new IOException("Invalid charge evidence section");
        List<long[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long[] r = new long[columns]; for (int c = 0; c < columns; c++) r[c] = in.readLong();
            if (!valid(r, ranges)) throw new IOException("Charge evidence value out of range");
            rows.add(r);
        }
        return rows;
    }
    /** Each value unknown (NONE) or inside its column's range. */
    static boolean valid(long[] row, long[][] ranges) {
        if (row.length != ranges.length) return false;
        for (int i = 0; i < row.length; i++) if (row[i] != NONE && (row[i] < ranges[i][0] || row[i] > ranges[i][1])) return false;
        return true;
    }
    private static void store(File file, byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Charge evidence too large");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Storage unavailable");
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) { stream.write(bytes); stream.flush(); stream.getFD().sync(); }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
