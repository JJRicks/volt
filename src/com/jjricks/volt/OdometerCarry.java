package com.jjricks.volt;

import java.io.IOException;
import java.util.*;

/**
 * Each scooter's lifetime odometer record, kept so a reset can't take the distance away (owner's ask, November 14, 2026:
 * an unbind or reset might zero the scooter's odometer). It is always on, whatever the maintenance reminders say, and
 * only real scooters feed it: never the simulator.
 *
 * <ul>
 * <li>It reads each reading as the maintenance count does ({@link MaintenanceDistance#step}): forward, the same, a
 *     backward step of at most {@link MaintenanceDistance#JITTER_KM} (ignored), a wrap of an 8- or 16-bit field, or a
 *     reset.</li>
 * <li>A wrap or a reset counts only once two valid readings in a row, from separate frames, are both below the last
 *     saved one; a single low reading changes nothing, and the shown total holds the last value until then.</li>
 * <li>A confirmed reset carries {@code last saved - first reading after the drop} over: Volt picks up from the last
 *     number it saw, which never over-counts, even when a used part with its own mileage is fitted.</li>
 * <li>A confirmed wrap adds one turn of the field to {@link #wrappedKm}, so the shown total never drops at a wrap, and
 *     is no carry-over: nothing to explain or undo.</li>
 * <li>The 16- and 32-bit fields are one odometer, in tenths, from the same v1 report: Telemetry reads the 32-bit field
 *     only while it is above zero, so a reset to 0 arrives as a 16-bit reading. A record that has seen the 32-bit field
 *     keeps judging every v1 reading on it ({@link #effectiveBits}), so that 0 is an ordinary drop and carries over;
 *     a 16-bit record that starts seeing the 32-bit field moves to it with its laps folded into the baseline
 *     ({@link #widen}), so the shown total stays the same.</li>
 * <li>Only a change between the v0 field (8-bit whole km) and the v1 fields (tenths) re-baselines, without a carry-over
 *     and clearing {@link #wrappedKm}: the new field's own value stands, and laps of the old field may already be in
 *     it.</li>
 * </ul>
 * Only the odometer Volt shows the rider (Home's odometer, riding mode's) is the raw reading plus
 * {@link #offsetKm}; rides, range, maintenance, exports and diagnostics keep the raw readings. Pure Java.
 */
final class OdometerCarry {
    /** app_settings {@code PREFIX + profile ID}; travels with its profile in backups (Scooters & setup keys). */
    static final String PREFIX = "odometer_record";
    static final long FRESHNESS_WRITE_MS = 5L * 60 * 1000;
    static final int MAX_CARRIES = 50, MAX_LENGTH = 16 * 1024, MAX_DEPTH = 4;

    static String key(String profileId) { return PREFIX + profileId; }

    /** A forgotten profile's record, by the scooter's serial (Keeper.forget): device only, never backed up. */
    static final String KEPT_PREFIX = "odometer_kept";

    static String keptKey(String serial) { return KEPT_PREFIX + serial; }

    /** What one reading did. PENDING: the first low reading, waiting for a second to confirm it. */
    enum Step { IGNORED, FIRST, REBASELINE, FORWARD, SAME, JITTER, PENDING, WRAP, CARRIED }

    /** One carry-over: when, and the last saved and first new readings it bridged. seen: the rider opened its explanation. */
    static final class Carry {
        final long wall; final double fromKm, toKm; boolean seen;
        Carry(long wall, double fromKm, double toKm, boolean seen) { this.wall = wall; this.fromKm = fromKm; this.toKm = toKm; this.seen = seen; }
        double km() { return MaintenanceDistance.round(fromKm - toKm); }
        boolean same(Carry o) { return wall == o.wall && fromKm == o.fromKm && toKm == o.toKm; }
    }

    /** The last accepted raw reading, or NaN when there is none. */
    double lastRawKm = Double.NaN;
    int bits = MaintenanceDistance.BITS_UNKNOWN;
    /** Wall time of the last accepted reading; 0 when never read. A restore keeps the record with the newer one. */
    long lastWall;
    /** Whole turns of a wrapping field since Volt first read it. */
    double wrappedKm;
    /** The carry-overs, oldest first. */
    final List<Carry> carries = new ArrayList<>();

    // An unconfirmed drop: in memory only, never written, so a restart forgets it (the safe outcome).
    private double dropKm = Double.NaN;
    private long dropFrame;
    private MaintenanceDistance.Kind dropKind;

    OdometerCarry copy() {
        OdometerCarry c = new OdometerCarry();
        c.lastRawKm = lastRawKm; c.bits = bits; c.lastWall = lastWall; c.wrappedKm = wrappedKm;
        for (Carry x : carries) c.carries.add(new Carry(x.wall, x.fromKm, x.toKm, x.seen));
        c.dropKm = dropKm; c.dropFrame = dropFrame; c.dropKind = dropKind;
        return c;
    }

    boolean hasBaseline() { return !Double.isNaN(lastRawKm); }
    boolean dropPending() { return !Double.isNaN(dropKm); }

    /** All the distance carried over from before resets. */
    double carriedKm() {
        double sum = 0;
        for (Carry c : carries) sum += c.km();
        return MaintenanceDistance.round(sum);
    }

    /** What the shown odometer adds to the raw reading. */
    double offsetKm() { return MaintenanceDistance.round(wrappedKm + carriedKm()); }

    /** The odometer to show for a raw reading: held at the last saved value while a drop is unconfirmed. */
    double displayKm(double rawKm) {
        double raw = dropPending() && hasBaseline() ? lastRawKm : rawKm;
        return MaintenanceDistance.round(raw + offsetKm());
    }

    /**
     * The odometer Home shows for a reading it holds. A live reading reads as {@link #displayKm}. A cached reading older
     * than this record's last reading (a record restored from another phone, say, that has carried a reset over since)
     * shows the record's own last reading instead, so a carry-over is never added to a number from before it.
     */
    double shownKm(double rawKm, long capturedWallMs, boolean live) {
        if (!live && hasBaseline() && capturedWallMs < lastWall) return displayKm(lastRawKm);
        return displayKm(rawKm);
    }

    /** The latest carry-over, or null. */
    Carry last() { return carries.isEmpty() ? null : carries.get(carries.size() - 1); }

    /** A carry-over the rider hasn't opened the explanation of yet. */
    boolean unacknowledged() { for (Carry c : carries) if (!c.seen) return true; return false; }

    /** The rider opened the explanation: every carry-over so far counts as seen. Whether anything changed. */
    boolean acknowledge() {
        boolean changed = false;
        for (Carry c : carries) if (!c.seen) { c.seen = true; changed = true; }
        return changed;
    }

    /** Undo: removes the latest carry-over, so the total is exactly what it was before it. Whether there was one. */
    boolean undo() {
        if (carries.isEmpty()) return false;
        carries.remove(carries.size() - 1);
        return true;
    }

    /**
     * Feeds one odometer reading. {@code frame} tells frames apart (the reading's update time): a drop is confirmed only
     * by a second low reading from another frame. Invalid values (NaN, infinite, negative) change nothing.
     */
    Step observe(double rawKm, int readingBits, long frame, long wall) {
        if (Double.isNaN(rawKm) || Double.isInfinite(rawKm) || rawKm < 0) return Step.IGNORED;
        // 16 and 32 bits are the same tenths odometer: judged on the record's own field, never a fresh start.
        if (hasBaseline() && readingBits == MaintenanceDistance.BITS_32 && bits == MaintenanceDistance.BITS_16) widen();
        readingBits = effectiveBits(bits, readingBits);
        if (!hasBaseline() || readingBits != bits) {
            boolean first = !hasBaseline();
            if (!first) wrappedKm = 0;
            baseline(rawKm, readingBits, wall);
            return first ? Step.FIRST : Step.REBASELINE;
        }
        MaintenanceDistance.Kind kind = MaintenanceDistance.step(lastRawKm, bits, rawKm);
        if (kind == MaintenanceDistance.Kind.FORWARD || kind == MaintenanceDistance.Kind.SAME) {
            baseline(rawKm, bits, wall);
            return kind == MaintenanceDistance.Kind.SAME ? Step.SAME : Step.FORWARD;
        }
        if (kind == MaintenanceDistance.Kind.IGNORED) {
            // Within the jitter: not below the saved value by more than it, so it breaks a pending drop's run.
            clearDrop(); lastWall = Math.max(lastWall, wall);
            return Step.JITTER;
        }
        if (!dropPending()) { pending(rawKm, frame, kind); return Step.PENDING; }
        if (frame == dropFrame) return Step.PENDING;
        // The second low reading confirms only a drop to about the same number; a different one (a glitch, then the
        // real reading) becomes the drop that waits, so the carry-over is worked out from a reading that repeated.
        if (Math.abs(MaintenanceDistance.round(rawKm - dropKm)) > MaintenanceDistance.JITTER_KM) { pending(rawKm, frame, kind); return Step.PENDING; }
        // Confirmed, as the first low reading read it.
        Step step;
        if (dropKind == MaintenanceDistance.Kind.WRAP) {
            wrappedKm = MaintenanceDistance.round(wrappedKm + MaintenanceDistance.period(bits));
            step = Step.WRAP;
        } else {
            carries.add(new Carry(wall, lastRawKm, dropKm, false));
            while (carries.size() > MAX_CARRIES) {
                // Too many to keep: the two oldest fold into one, so the total stays the same.
                Carry a = carries.remove(0), b = carries.remove(0);
                carries.add(0, new Carry(b.wall, MaintenanceDistance.round(a.km() + b.km()), 0, a.seen && b.seen));
            }
            step = Step.CARRIED;
        }
        baseline(rawKm, bits, wall);
        return step;
    }

    private void baseline(double rawKm, int readingBits, long wall) {
        lastRawKm = rawKm; bits = readingBits; lastWall = Math.max(lastWall, wall); clearDrop();
    }

    private void clearDrop() { dropKm = Double.NaN; dropFrame = 0; dropKind = null; }

    private void pending(double rawKm, long frame, MaintenanceDistance.Kind kind) { dropKm = rawKm; dropFrame = frame; dropKind = kind; }

    /**
     * The field a reading is judged on. v1's 16- and 32-bit fields are one odometer, so once a record has seen the
     * 32-bit field a 16-bit reading (Telemetry's fallback when the 32-bit value is 0) is judged on the 32-bit field's
     * period: a drop, never a wrap or a fresh start. Only v0 (8) against v1 (16, 32) is another field.
     */
    static int effectiveBits(int recordBits, int readingBits) {
        if (recordBits == MaintenanceDistance.BITS_32 && readingBits == MaintenanceDistance.BITS_16) return MaintenanceDistance.BITS_32;
        return readingBits;
    }

    /**
     * A 16-bit record meets the 32-bit field (the report started carrying it): the same counter, read whole. The laps
     * the record counted are folded into the baseline, so the shown total is unchanged and the 32-bit reading is
     * judged against everything the 16-bit field had reached.
     */
    private void widen() {
        lastRawKm = MaintenanceDistance.round(lastRawKm + wrappedKm);
        wrappedKm = 0;
        bits = MaintenanceDistance.BITS_32;
        clearDrop();
    }

    /** Whether this differs enough from the saved record for a write: 0.1 km, a new baseline, a carry-over, a wrap, or 5 min. */
    boolean worthSaving(OdometerCarry saved) {
        if (saved == null) return true;
        if (saved.bits != bits || saved.hasBaseline() != hasBaseline() || saved.wrappedKm != wrappedKm || !sameCarries(saved)) return true;
        if (lastWall - saved.lastWall >= FRESHNESS_WRITE_MS) return true;
        return hasBaseline() && Math.abs(lastRawKm - saved.lastRawKm) >= MaintenanceDistance.WRITE_STEP_KM - 1e-9;
    }

    private boolean sameCarries(OdometerCarry o) {
        if (o.carries.size() != carries.size()) return false;
        for (int i = 0; i < carries.size(); i++) if (!carries.get(i).same(o.carries.get(i)) || carries.get(i).seen != o.carries.get(i).seen) return false;
        return true;
    }

    // ------------------------------------------------------------------ the stored text

    static String encode(OdometerCarry c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("version", 1L);
        if (c.hasBaseline()) o.put("last_raw_km", c.lastRawKm);
        o.put("bits", (long) c.bits);
        o.put("last_wall", c.lastWall);
        o.put("wrapped_km", c.wrappedKm);
        List<Object> list = new ArrayList<>();
        for (Carry x : c.carries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wall", x.wall); m.put("from_km", x.fromKm); m.put("to_km", x.toKm); m.put("seen", x.seen);
            list.add(m);
        }
        o.put("carries", list);
        StringBuilder out = new StringBuilder();
        MaintenanceStore.write(out, o);
        return out.toString();
    }

    /** The record in this text, or a fresh one when there is none or it can't be read. */
    static OdometerCarry decode(String text) {
        OdometerCarry c = read(text);
        return c == null ? new OdometerCarry() : c;
    }

    /** The record in this text, or null when there is none or it can't be read. */
    static OdometerCarry read(String text) {
        if (text == null || text.length() > MAX_LENGTH) return null;
        try {
            Map<String, Object> o = BackupJson.object(BackupJson.parse(text, MAX_DEPTH));
            OdometerCarry c = new OdometerCarry();
            double raw = number(o.get("last_raw_km"), Double.NaN);
            c.lastRawKm = raw >= 0 && !Double.isInfinite(raw) ? raw : Double.NaN;
            long bits = (long) number(o.get("bits"), 0);
            c.bits = bits == 8 || bits == 16 || bits == 32 ? (int) bits : MaintenanceDistance.BITS_UNKNOWN;
            c.lastWall = Math.max(0, (long) number(o.get("last_wall"), 0));
            double wrapped = number(o.get("wrapped_km"), 0);
            c.wrappedKm = wrapped >= 0 && !Double.isInfinite(wrapped) ? wrapped : 0;
            Object list = o.get("carries");
            if (list instanceof List) for (Object item : (List<?>) list) {
                if (!(item instanceof Map) || c.carries.size() >= MAX_CARRIES) continue;
                Map<?, ?> m = (Map<?, ?>) item;
                double from = number(m.get("from_km"), Double.NaN), to = number(m.get("to_km"), Double.NaN);
                if (!(from >= to) || to < 0 || Double.isInfinite(from)) continue;
                c.carries.add(new Carry(Math.max(0, (long) number(m.get("wall"), 0)), from, to, Boolean.TRUE.equals(m.get("seen"))));
            }
            return c;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    /**
     * The record a restore keeps for one profile (BackupArchive): the one whose last reading is newer, this phone's on a
     * tie or when the backup's can't be read. A carry-over either side has seen stays seen, so a restore never asks
     * again about one the rider already opened. With a record on one side only, that one: an older backup brings its
     * record to a phone that has none, so a later drop still carries over.
     */
    static String merged(String here, String incoming) {
        OdometerCarry mine = read(here), theirs = read(incoming);
        if (theirs == null) return here;
        if (mine == null) return incoming;
        OdometerCarry kept = theirs.lastWall > mine.lastWall ? theirs : mine, other = kept == mine ? theirs : mine;
        boolean changed = false;
        for (Carry c : kept.carries)
            if (!c.seen) for (Carry o : other.carries) if (o.seen && o.same(c)) { c.seen = true; changed = true; }
        if (changed) return encode(kept);
        return kept == mine ? here : incoming;
    }

    // ------------------------------------------------------------------ keeping it in app_settings

    /**
     * Reads and writes the records for the service. Each reading updates the record in memory; it is written when
     * {@link #worthSaving} says so, and by {@link #flush} when the connection ends. Simulation scopes never write.
     */
    static final class Keeper {
        private final MaintenanceKeeper.Storage storage;
        private final Map<String, OdometerCarry> memory = new HashMap<>();
        /** The stored text each memory copy was made on: a record replaced meanwhile (a restore) drops the copy. */
        private final Map<String, String> memoryOn = new HashMap<>();
        private final Set<String> unwritten = new HashSet<>();

        Keeper(MaintenanceKeeper.Storage storage) { this.storage = storage; }

        private static boolean real(String profileId) {
            return profileId != null && !profileId.isEmpty() && !MaintenanceKeeper.simulation(profileId);
        }

        /** The latest record for a profile, with what was counted since the last write. A copy. */
        OdometerCarry load(String profileId) {
            if (!real(profileId)) return new OdometerCarry();
            String text = storage.read(key(profileId));
            OdometerCarry kept = memory.get(profileId);
            if (kept != null && Objects.equals(text, memoryOn.get(profileId))) return kept.copy();
            drop(profileId);
            return decode(text);
        }

        /** One reading for a real scooter's profile; null (and nothing written) for a simulation scope. */
        Step observe(String profileId, double rawKm, int bits, long frame, long wall) {
            if (!real(profileId)) return null;
            String text = storage.read(key(profileId));
            OdometerCarry c = load(profileId);
            Step step = c.observe(rawKm, bits, frame, wall);
            if (c.worthSaving(read(text))) store(profileId, c);
            else { memory.put(profileId, c); memoryOn.put(profileId, text); unwritten.add(profileId); }
            return step;
        }

        /** Writes what was counted since the last write (the connection ended). */
        void flush(String profileId) {
            if (!unwritten.contains(profileId)) return;
            OdometerCarry c = load(profileId);
            if (unwritten.contains(profileId)) store(profileId, c);
            unwritten.remove(profileId);
        }

        boolean unwritten(String profileId) { return unwritten.contains(profileId); }

        /** The rider opened the explanation. */
        boolean acknowledge(String profileId) { return change(profileId, OdometerCarry::acknowledge); }

        /** Removes the latest carry-over. */
        boolean undo(String profileId) { return change(profileId, OdometerCarry::undo); }

        private boolean change(String profileId, java.util.function.Predicate<OdometerCarry> edit) {
            if (!real(profileId)) return false;
            OdometerCarry c = load(profileId);
            if (!edit.test(c)) return false;
            store(profileId, c);
            return true;
        }

        /**
         * A forgotten scooter (Forget, or Release this scooter): its record goes, but one that has read the odometer is
         * kept on this phone under the scooter's serial ({@link #KEPT_PREFIX}, never backed up), so setting the same
         * scooter up again (a new profile, say with a new owner key) still carries a reset over ({@link #adopt}).
         */
        void forget(String profileId, String serial) {
            if (!real(profileId)) return;
            OdometerCarry c = load(profileId);
            if (serial != null && !serial.isEmpty() && c.hasBaseline()) storage.write(keptKey(serial), encode(c));
            drop(profileId);
            storage.remove(key(profileId));
        }

        /**
         * The connected scooter's serial is known and matched (ScooterProfiles keeps it only after the serial check): a
         * record kept when a profile for this same scooter was forgotten moves to this profile, unless this profile has
         * carried something over already or holds the newer record ({@link #keepsOwn}). Its next two low readings then
         * carry the reset over. The kept copy goes either way. Whether one moved.
         */
        boolean adopt(String profileId, String serial) {
            if (!real(profileId) || serial == null || serial.isEmpty()) return false;
            String text = storage.read(keptKey(serial));
            if (text == null) return false;
            OdometerCarry kept = read(text);
            storage.remove(keptKey(serial));
            if (kept == null || !load(profileId).carries.isEmpty()) return false;
            if (keepsOwn(load(profileId), kept)) return false;
            store(profileId, kept);
            return true;
        }

        /**
         * Whether a profile's own record outranks the copy kept by serial: it has read the odometer more recently (a
         * backup restored from another phone that kept riding it, say) and is not below the kept reading. A newer
         * record that is below it began after a reset the kept copy saw (this connection's readings before the serial
         * check, or another phone's record started on the reset odometer), so the kept copy still moves over and the
         * distance before the reset carries over.
         */
        static boolean keepsOwn(OdometerCarry own, OdometerCarry kept) {
            if (!kept.hasBaseline()) return own.hasBaseline();
            if (!own.hasBaseline() || own.lastWall < kept.lastWall) return false;
            return MaintenanceDistance.round(kept.lastRawKm - own.lastRawKm) <= MaintenanceDistance.JITTER_KM;
        }

        private void store(String profileId, OdometerCarry c) {
            String text = encode(c);
            storage.write(key(profileId), text);
            memory.put(profileId, c); memoryOn.put(profileId, text); unwritten.remove(profileId);
        }

        private void drop(String profileId) { memory.remove(profileId); memoryOn.remove(profileId); unwritten.remove(profileId); }
    }
}
