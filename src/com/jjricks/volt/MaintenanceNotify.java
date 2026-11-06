package com.jjricks.volt;

import com.jjricks.volt.MaintenancePolicy.Reminder;
import com.jjricks.volt.MaintenanceStore.Settings;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * When a maintenance reminder notifies (maintenance plan, PR 3; owner answer Q40 A). Kept light: no push
 * and no Firebase. A check runs when Volt connects to a scooter, on each new odometer reading while
 * reminders are on, when a ride ends, when the connection ends, and from one daily
 * {@link MaintenanceJobService} run while a reminder needs it. Each check is one read of the reminders.
 *
 * <ul>
 * <li>One notice per scooter, listing its due checks whose notify choice is on.</li>
 * <li>A reminder notifies once per due cycle: a cycle is its count start (base time and distance) and its
 *     snooze end, so Mark done starts a new cycle and the end of a snooze notifies once more. The cycles
 *     already notified live in {@code maintenance_seen_<profileId>}, device-only and never backed up.</li>
 * <li>A change from the Maintenance page is a quiet check: the owner is looking at the page, so what is
 *     due now counts as seen, nothing posts and the scooter's notice goes.</li>
 * <li>A check that was stopped (its run token ended), or whose reminder was turned off while it ran,
 *     posts nothing and writes nothing.</li>
 * <li>The daily job exists only while some scooter has reminders on and a notify reminder that time
 *     can make due (a time part, or a snooze still running).</li>
 * </ul>
 * Pure Java: {@link MaintenanceNotices} posts on Android.
 */
final class MaintenanceNotify {
    static final String CHANNEL = "maintenance", OPEN = "open_maintenance";
    /** The daily job: about once a day, at a time Android picks within the last few hours of each day. */
    static final long PERIOD_MS = 86_400_000L, FLEX_MS = 6 * 3_600_000L;
    /** Every check and every quiet check takes it, in the service and the daily job alike. */
    static final Object LOCK = new Object();

    /** Posts and removes a scooter's notice. */
    interface Poster {
        void post(String scope, List<Reminder> due);
        void cancel(String scope);
    }

    /** One due cycle of a reminder. */
    static final class Cycle {
        final long base, snooze; final double acc;
        Cycle(long base, double acc, long snooze) { this.base = base; this.acc = acc; this.snooze = snooze; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Cycle)) return false;
            Cycle c = (Cycle) o;
            return base == c.base && snooze == c.snooze && Double.compare(acc, c.acc) == 0;
        }
        @Override public int hashCode() { return Objects.hash(base, snooze, Double.doubleToLongBits(acc)); }
    }

    /** What a check found: the due notify reminders, those new this cycle, and the bookkeeping to keep. */
    static final class Result {
        final List<Reminder> due = new ArrayList<>(), fresh = new ArrayList<>();
        final Map<String, Cycle> seen = new LinkedHashMap<>();
        boolean post() { return !fresh.isEmpty(); }
    }

    private MaintenanceNotify() { }

    static Cycle cycle(Reminder r) { return new Cycle(r.baseWall, r.baseAccKm, r.snoozeUntil); }

    /**
     * The check itself. With reminders off nothing is due and the bookkeeping stays as it was. A snoozed
     * reminder keeps its entry; one that is not due (or no longer notifies) loses it.
     */
    static Result evaluate(Settings s, Map<String, Cycle> seen, long nowWall, ZoneId zone, boolean quiet) {
        Result out = new Result();
        if (!s.on) { out.seen.putAll(seen); return out; }
        for (Reminder r : s.reminders) {
            MaintenancePolicy.State state = MaintenanceView.status(s, r, nowWall, zone).state;
            Cycle was = seen.get(r.id), current = cycle(r);
            if (state == MaintenancePolicy.State.DUE && r.notify) {
                out.due.add(r);
                if (!quiet && !current.equals(was)) out.fresh.add(r);
                out.seen.put(r.id, current);
            } else if (state == MaintenancePolicy.State.SNOOZED && r.notify && was != null) {
                out.seen.put(r.id, was);
            }
        }
        return out;
    }

    /** Whether the daily job is needed for this scooter: reminders on and a notify reminder that time can make due. */
    static boolean needsJob(Settings s, long nowWall) {
        if (!s.on) return false;
        for (Reminder r : s.reminders) if (r.notify && (r.byTime() || r.snoozeUntil > nowWall)) return true;
        return false;
    }

    static boolean needsJob(Collection<Settings> scooters, long nowWall) {
        for (Settings s : scooters) if (needsJob(s, nowWall)) return true;
        return false;
    }

    /**
     * One check of a scope, under {@link #LOCK}. {@code live} is the caller's run token (and anything
     * else that must still hold, such as no backup running); it is asked before reading and again before
     * posting. Before posting, the latest document is read again: a reminder turned off, deleted or
     * marked done meanwhile is not posted. A page change that lands later still runs its own quiet check,
     * which removes the notice. Returns whether a notice was posted.
     */
    static boolean check(MaintenanceKeeper keeper, String scope, long nowWall, ZoneId zone, boolean quiet,
                         BooleanSupplier live, Poster poster) {
        synchronized (LOCK) {
            if (scope == null || scope.isEmpty() || !live.getAsBoolean()) return false;
            String before = keeper.seen(scope);
            Map<String, Cycle> seen = decodeSeen(before);
            Result result = evaluate(keeper.load(scope), seen, nowWall, zone, quiet);
            // Read once more just before posting: the latest document decides what posts.
            if (result.post()) result = evaluate(keeper.load(scope), seen, nowWall, zone, false);
            if (!live.getAsBoolean()) return false;
            if (!result.seen.equals(seen)) keeper.seen(scope, encodeSeen(result.seen));
            if (result.post()) poster.post(scope, result.due);
            else if (quiet || result.due.isEmpty()) poster.cancel(scope);
            return result.post();
        }
    }

    // ---- The device-only bookkeeping ---------------------------------------------------------

    /** Never throws: absent or unreadable text means nothing was notified yet. */
    static Map<String, Cycle> decodeSeen(String text) {
        Map<String, Cycle> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty() || text.length() > MaintenanceStore.MAX_LENGTH) return out;
        try {
            Map<String, Object> o = BackupJson.object(BackupJson.parse(text, MaintenanceStore.MAX_DEPTH));
            for (Map.Entry<String, Object> e : o.entrySet()) {
                if (!(e.getValue() instanceof Map) || out.size() >= MaintenancePolicy.MAX_REMINDERS) continue;
                Map<?, ?> c = (Map<?, ?>) e.getValue();
                Object base = c.get("base"), acc = c.get("acc"), snooze = c.get("snooze");
                if (!(base instanceof Number) || !(snooze instanceof Number)) continue;
                double km = acc instanceof Number ? ((Number) acc).doubleValue() : Double.NaN;
                out.put(e.getKey(), new Cycle(((Number) base).longValue(), km, ((Number) snooze).longValue()));
            }
        } catch (IOException | RuntimeException unreadable) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    static String encodeSeen(Map<String, Cycle> seen) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (Map.Entry<String, Cycle> e : seen.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("base", e.getValue().base);
            if (!Double.isNaN(e.getValue().acc)) c.put("acc", e.getValue().acc);
            c.put("snooze", e.getValue().snooze);
            o.put(e.getKey(), c);
        }
        return MaintenanceStore.json(o);
    }
}
