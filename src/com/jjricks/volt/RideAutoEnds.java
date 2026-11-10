package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.BitSet;

/**
 * The count of recordings that ended by themselves, which a riding mode compares with the count when it
 * opened. The journal's STOP row is the record of an automatic end: its reason says automatic or the
 * rider's, and it is on disk before anything here is. So the stop writes only the count, and a load counts
 * any automatic STOP not yet counted, so a death or failed write between the two can't lose the end or count
 * it twice. Android-free, so the JVM test can interrupt it at each step and fail any write.
 *
 * A counted end is known by its ride's id, which each journal has exactly one of, and never by the STOP's
 * time: a phone's clock can be corrected backwards, or give two STOPs the same millisecond. The ids live in
 * a fixed-size Bloom filter, so the record never grows however many rides there are, and nothing is ever
 * dropped from it to make room: a counted ride can never look new and be counted again, which is what would
 * close a riding mode for no reason. Its one possible error is the harmless way round. An end that was never
 * counted can look counted (under 0.1% at 500 rides that ended by themselves, about 2% at 1000), and only
 * matters if the process also died in the moment between the STOP and the count.
 */
final class RideAutoEnds {
    /**
     * The saved count and the filter of the rides counted, written together. As with SharedPreferences'
     * commit, a save that fails on disk still changes what they read in this process, and the next save
     * that reaches the disk carries it.
     */
    interface Store { long count(); String counted(); void save(long count, String counted); }

    private static final int BITS = 1 << 13, PROBES = 6;

    private final Store store;
    RideAutoEnds(Store store) { this.store = store; }

    long count() { return store.count(); }

    /** The journal has written this ride's automatic STOP. Always one more, whatever the filter says. */
    void stopped(String rideId) {
        BitSet filter = filter();
        mark(filter, rideId);
        store.save(store.count() + 1, encode(filter));
    }

    /** When the service loads: counts the journals' automatic STOPs for rides not counted yet. */
    void reconcile(String[] automaticStops) {
        BitSet filter = filter(), updated = (BitSet) filter.clone();
        int found = 0;
        for (String rideId : automaticStops) if (!counted(filter, rideId)) { found++; mark(updated, rideId); }
        if (found > 0) store.save(store.count() + found, encode(updated));
    }

    private BitSet filter() {
        String saved = store.counted();
        if (saved == null || saved.isEmpty()) return new BitSet(BITS);
        try { return BitSet.valueOf(Base64.getDecoder().decode(saved)); }
        catch (IllegalArgumentException damaged) { return new BitSet(BITS); }
    }
    private static String encode(BitSet filter) { return Base64.getEncoder().encodeToString(filter.toByteArray()); }
    private static boolean counted(BitSet filter, String rideId) {
        for (int bit : probes(rideId)) if (!filter.get(bit)) return false;
        return true;
    }
    private static void mark(BitSet filter, String rideId) { for (int bit : probes(rideId)) filter.set(bit); }

    /** The filter positions for an id, from its SHA-256, so they stay the same on every phone and version. */
    private static int[] probes(String rideId) {
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(rideId.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        int[] bits = new int[PROBES];
        for (int i = 0; i < PROBES; i++) bits[i] = (((digest[2 * i] & 0xff) << 8) | (digest[2 * i + 1] & 0xff)) & (BITS - 1);
        return bits;
    }
}
