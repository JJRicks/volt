package com.jjricks.volt;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * One value per key, made once, with both held weakly. A narrow-label ladder refers back to its view, so a
 * WeakHashMap holding the ladder itself would keep every view, and the card tree around it, alive for as long
 * as the map lives. The key keeps its value alive instead (a view holds its ladder as a layout listener), so a
 * value lasts exactly as long as its key and both go once nothing else holds the key.
 */
final class LadderCache<K, V> {
    private final WeakHashMap<K, WeakReference<V>> values = new WeakHashMap<>();

    /** The value made for key, or null when there is none. */
    V get(K key) {
        WeakReference<V> value = values.get(key);
        return value == null ? null : value.get();
    }

    /** The value made for key, making it first when there is none; make must leave key holding the value. */
    V of(K key, Function<K, V> make) {
        V value = get(key);
        if (value == null) {
            value = make.apply(key);
            values.put(key, new WeakReference<>(value));
        }
        return value;
    }
}
