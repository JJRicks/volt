package com.jjricks.volt;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Whether a rebuilt connection notice is the one Android already shows. The service's tick rebuilt and re-posted it
 * about every 5 s whatever changed; each post is a NotificationManager enqueue that the system, SystemUI and every
 * notification listener process again. Now a rebuilt notice posts only when something in it differs from the last
 * post, or when that post is no longer showing (swiped away on Android 14 and later, or removed by the system), so a
 * person sees exactly what they saw before.
 *
 * The notice's header shows when it was posted ("now"; Android counts it up to "1m" by itself). The builder stamps every
 * rebuild with the current time, so that time is left out of the key and an unchanged notice that shows it posts again
 * once its last post is REFRESH_MS old: with the tick's spacing (15 s at most) the header still never gets past "now".
 * A ride's running clock (a chronometer from the ride's start) is part of the key and needs no refresh.
 *
 * A notice is compared through key(): the service flattens everything the notice carries (title, text, progress,
 * actions, icon, flags, extras, its lock screen version, how soon Android shows it) into lists, maps and plain values.
 * Anything it can't compare by content (a bitmap, an intent) is compared by identity, so a new object always counts as
 * a change: the comparison may post an unchanged notice again, but never skips a changed one. Pure and Android-free.
 */
final class NoticeRepeat {
    /** How old an unchanged notice that shows its post time may get before it posts again (Android shows "now" below 60 s). */
    static final long REFRESH_MS = 40_000L;
    private Object shown;
    private long postedMs;

    /**
     * True when this notice should post: nothing posted yet (or reset), it differs from the last post, it shows its post
     * time and the last post is REFRESH_MS old, or that post is no longer showing. showing is asked only for an otherwise
     * unchanged notice (it costs Android a call). nowMs is a monotonic clock (elapsedRealtime).
     */
    boolean due(Object key, boolean showsPostTime, long nowMs, BooleanSupplier showing) {
        return shown == null || !shown.equals(key) || showsPostTime && nowMs - postedMs >= REFRESH_MS || !showing.getAsBoolean();
    }
    /** The notice with this key went out at nowMs (a post or startForeground): the new baseline. */
    void posted(Object key, long nowMs) { shown = key; postedMs = nowMs; }
    /** Nothing is known to show: the next notice posts whatever it carries. */
    void reset() { shown = null; }

    /**
     * A comparable copy of a notice's parts: Strings, numbers, booleans, characters and enums as they are, any other
     * text as its String, maps, sets and lists (and arrays, as lists) element by element, and anything else by
     * identity. The copy holds no reference to a mutable list, array or map it was given.
     */
    static Object key(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum) return value;
        if (value instanceof CharSequence) return value.toString();
        if (value instanceof Map) {
            Map<Object, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) copy.put(key(entry.getKey()), key(entry.getValue()));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Set) {
            Set<Object> copy = new HashSet<>();
            for (Object item : (Set<?>) value) copy.add(key(item));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Collection<?>) value) copy.add(key(item));
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) copy.add(key(Array.get(value, i)));
            return Collections.unmodifiableList(copy);
        }
        return new Same(value);
    }

    /** A part compared by identity: equal only to the same object. */
    static final class Same {
        private final Object value;
        Same(Object value) { this.value = value; }
        @Override public boolean equals(Object other) { return other instanceof Same && ((Same) other).value == value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
        @Override public String toString() { return "Same(" + value.getClass().getName() + ")"; }
    }
}
