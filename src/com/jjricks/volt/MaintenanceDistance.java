package com.jjricks.volt;

/**
 * A forward-only maintenance odometer for one scooter profile (maintenance plan, section 3).
 * Reminders count from {@link #accumulatedKm}, never from the raw odometer, so a wrapped
 * odometer field, a new dashboard or controller, or a reset never breaks them.
 *
 * <ul>
 * <li>Forward readings are added in full, big jumps included: rides with the official app or
 *     with no phone are real distance.</li>
 * <li>A backward reading whose previous value was in the top 5 % of the field is a wrap, and
 *     the wrapped distance is added. Any other backward reading is a reset or swap: Volt
 *     re-baselines without adding and records {@link #resetWall}.</li>
 * <li>A backward step of at most {@link #JITTER_KM} is ignored, so a one-off glitch neither adds
 *     distance nor reads as a reset.</li>
 * <li>A change of field width (say 16-bit to 32-bit after a firmware update) re-baselines without
 *     adding: the two values are not comparable.</li>
 * </ul>
 * Pure Java; nothing in the app feeds it yet.
 */
final class MaintenanceDistance {
    /** Field widths Telemetry decodes: v0 1-byte whole km, v1 16-bit and 32-bit tenths. 0 is unknown. */
    static final int BITS_UNKNOWN = 0, BITS_8 = 8, BITS_16 = 16, BITS_32 = 32;
    static final double WRAP_WINDOW = 0.05, JITTER_KM = 0.2, WRITE_STEP_KM = 0.1;
    static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;

    /** REBASELINE: a new baseline without adding (after a merge cleared it, or the field width changed). */
    enum Kind { FIRST, REBASELINE, FORWARD, SAME, WRAP, RESET, IGNORED }

    /** What one reading did: its kind and the distance it added. */
    static final class Outcome {
        final Kind kind; final double addedKm;
        Outcome(Kind kind, double addedKm) { this.kind = kind; this.addedKm = addedKm; }
    }

    /** The last raw odometer value, or NaN when there is no baseline (never read, or cleared by a merge). */
    double lastRawKm = Double.NaN;
    /** Wall time of the last accepted reading; 0 when never read. */
    long lastWall;
    int bits = BITS_UNKNOWN;
    double accumulatedKm;
    /** Wall time of the last reset or swap; 0 when none. */
    long resetWall;

    MaintenanceDistance copy() {
        MaintenanceDistance d = new MaintenanceDistance();
        d.lastRawKm = lastRawKm; d.lastWall = lastWall; d.bits = bits; d.accumulatedKm = accumulatedKm; d.resetWall = resetWall;
        return d;
    }

    boolean hasBaseline() { return !Double.isNaN(lastRawKm); }
    /** Volt has read this scooter's odometer at least once, so distance reminders can be offered. */
    boolean everRead() { return lastWall > 0; }
    /** The last reading is more than 7 days old (the stale-odometer note). */
    boolean stale(long nowWall) { return everRead() && nowWall - lastWall > STALE_MS; }

    /** The size of one turn of the odometer field in km, or 0 when it can't wrap in practice. */
    static double period(int bits) {
        if (bits == BITS_8) return 256;
        if (bits == BITS_16) return 6553.6;
        return 0;
    }

    /** Feeds one odometer reading. Invalid values (NaN, infinite, negative) change nothing. */
    Outcome observe(double rawKm, int readingBits, long wall) {
        if (Double.isNaN(rawKm) || Double.isInfinite(rawKm) || rawKm < 0) return new Outcome(Kind.IGNORED, 0);
        if (!hasBaseline() || readingBits != bits) {
            boolean first = !hasBaseline() && !everRead();
            baseline(rawKm, readingBits, wall);
            return new Outcome(first ? Kind.FIRST : Kind.REBASELINE, 0);
        }
        double delta = round(rawKm - lastRawKm);
        if (delta >= 0) {
            accumulatedKm = round(accumulatedKm + delta); lastRawKm = rawKm; lastWall = Math.max(lastWall, wall);
            return new Outcome(delta == 0 ? Kind.SAME : Kind.FORWARD, delta);
        }
        if (-delta <= JITTER_KM) { lastWall = Math.max(lastWall, wall); return new Outcome(Kind.IGNORED, 0); }
        double period = period(bits);
        if (period > 0 && lastRawKm >= period * (1 - WRAP_WINDOW) && rawKm < period) {
            double added = round(period - lastRawKm + rawKm);
            accumulatedKm = round(accumulatedKm + added); lastRawKm = rawKm; lastWall = Math.max(lastWall, wall);
            return new Outcome(Kind.WRAP, added);
        }
        baseline(rawKm, readingBits, wall);
        resetWall = wall;
        return new Outcome(Kind.RESET, 0);
    }

    /** Odometer values are whole km or tenths; a metre keeps sums of tenths free of binary noise. */
    static double round(double km) { return Math.round(km * 1000) / 1000.0; }

    private void baseline(double rawKm, int readingBits, long wall) {
        lastRawKm = rawKm; bits = readingBits; lastWall = Math.max(lastWall, wall);
    }

    /** Forgets the raw baseline, keeping the counted distance; the next reading re-baselines without adding. */
    void clearBaseline() { lastRawKm = Double.NaN; }

    /**
     * The maintenance distance at a past odometer value, for "I changed the tires at 1,000 km":
     * the counted distance less what the odometer went since then. NaN when there's no baseline
     * or the value is ahead of the last reading. Never below 0.
     */
    double accumulatedAtRaw(double rawKm) {
        if (!hasBaseline() || Double.isNaN(rawKm) || rawKm < 0 || rawKm > lastRawKm) return Double.NaN;
        return Math.max(0, accumulatedKm - (lastRawKm - rawKm));
    }

    /** Whether this state differs enough from the saved one to be worth a preferences write. */
    boolean worthSaving(MaintenanceDistance saved) {
        if (saved == null) return true;
        if (saved.bits != bits || saved.resetWall != resetWall || saved.hasBaseline() != hasBaseline()) return true;
        return Math.abs(accumulatedKm - saved.accumulatedKm) >= WRITE_STEP_KM - 1e-9;
    }
}
