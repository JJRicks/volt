package com.jjricks.volt;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spaces Volt's notification posts so Android never drops one. NotificationManagerService silently sheds an app's
 * notification update while its estimated enqueue rate is above 5 a second. The estimate is a ⍺·previous + (1−⍺)·gap
 * average (⍺ 0.7, or 0.8 on Android 11), so a burst such as a charge alert firing while the connection notice changes
 * trips it and can drop the alert's Stop. With every post at least GAP_MS apart, the estimate stays above 0.3 s.
 * Even after an unpaced startForeground, the next spaced post estimates about 4 a second.
 * Each id keeps only its latest notice, and one id (the charge alert) goes ahead of the rest.
 * Callers build notices when they are taken, so a late one is never stale.
 */
final class NotificationPacer<T> {
    static final long GAP_MS = 300;
    private final int first;
    private final LinkedHashMap<Integer, T> pending = new LinkedHashMap<>();
    private long last;
    private boolean sent;

    NotificationPacer(int first) { this.first = first; }

    /** Queues id's latest notice, replacing one still waiting. */
    synchronized void offer(int id, T notice) { pending.remove(id); pending.put(id, notice); }
    /** A notice posted outside the pacer (startForeground) still counts toward the spacing; id's waiting one is dropped. */
    synchronized void posted(int id, long now) { pending.remove(id); last = now; sent = true; }
    /** A cancelled or no longer wanted notice never posts later. */
    synchronized void cancel(int id) { pending.remove(id); }
    synchronized void clear() { pending.clear(); }
    synchronized boolean waiting(int id) { return pending.containsKey(id); }

    /** The notice to post now, counted as posted, or null while none waits or the gap hasn't passed. */
    synchronized Map.Entry<Integer, T> take(long now) {
        if (pending.isEmpty() || (sent && now - last < GAP_MS)) return null;
        Integer id = pending.containsKey(first) ? first : pending.keySet().iterator().next();
        T notice = pending.remove(id);
        last = now; sent = true;
        return new AbstractMap.SimpleImmutableEntry<>(id, notice);
    }
    /** Everything still waiting, the first id ahead, emptying the queue: the service is ending, so nothing waits. */
    synchronized List<Map.Entry<Integer, T>> takeAll() {
        List<Map.Entry<Integer, T>> all = new ArrayList<>();
        if (pending.containsKey(first)) all.add(new AbstractMap.SimpleImmutableEntry<>(first, pending.remove(first)));
        for (Map.Entry<Integer, T> e : pending.entrySet()) all.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        pending.clear();
        return all;
    }
    /** Milliseconds until take() can return the next notice, or -1 with nothing waiting. */
    synchronized long delay(long now) {
        if (pending.isEmpty()) return -1;
        return sent ? Math.max(0, last + GAP_MS - now) : 0;
    }
}
