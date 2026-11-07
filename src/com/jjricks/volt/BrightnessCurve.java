package com.jjricks.volt;

/**
 * Riding mode's Volt auto brightness: ambient light (lux) to a screen level, with the rider's own
 * corrections learned per light band. The base curve is logarithmic (10 lux about 0.15, 10 000 lux
 * and brighter 1.0). Each − or + on the riding-mode slider stores an offset for the band the light
 * is in now, so the next time it is that bright the screen comes back to the rider's level. Pure
 * Java: levels are window brightness values (0.05..1.0); nothing here touches Android's setting.
 */
final class BrightnessCurve {
    /** The window levels Volt uses: never fully dark, at most full. */
    static final float FLOOR = 0.05f, CEILING = 1f;
    /** Notches on the slider: 0 is the floor, NOTCHES is full brightness. */
    static final int NOTCHES = 10;
    /** Lower edges (lux) of the light bands after the first: dark, dim, indoor, bright indoor, shade, daylight, sun. */
    static final float[] BAND_EDGES = {10, 50, 200, 1000, 5000, 20000};
    static final int BANDS = BAND_EDGES.length + 1;
    /** A learned offset never moves a band further than this from the base curve. */
    static final float MAX_OFFSET = 1f;

    private final float[] offsets = new float[BANDS];
    private final boolean[] learned = new boolean[BANDS];

    /** The light band for this lux. An edge belongs to the brighter band. */
    static int band(float lux) {
        int band = 0;
        for (float edge : BAND_EDGES) if (lux >= edge) band++;
        return band;
    }

    /** The untrained curve: logarithmic in lux, 1 lux or less at the floor, 10 000 lux or more at full. */
    static float base(float lux) {
        if (!(lux > 1)) return FLOOR;
        double decades = Math.log10(lux);
        float level = decades < 1 ? (float) (FLOOR + 0.10 * decades) : (float) (0.15 + 0.85 * (decades - 1) / 3);
        return clamp(level);
    }

    /** A notch's window level. */
    static float level(int notch) {
        int n = Math.max(0, Math.min(NOTCHES, notch));
        return FLOOR + (CEILING - FLOOR) * n / NOTCHES;
    }

    /** The nearest notch to a level. */
    static int notch(float level) {
        return Math.max(0, Math.min(NOTCHES, Math.round((clamp(level) - FLOOR) / (CEILING - FLOOR) * NOTCHES)));
    }

    static float clamp(float level) {
        if (Float.isNaN(level)) return FLOOR;
        return Math.max(FLOOR, Math.min(CEILING, level));
    }

    /** The trained level for this light: the base curve plus the band's learned offset. */
    float target(float lux) {
        return clamp(base(lux) + offsets[band(lux)]);
    }

    /** The rider chose this notch in this light: remember it for the band. Other bands keep theirs. */
    void correct(float lux, int notch) {
        int band = band(lux);
        float offset = level(notch) - base(lux);
        offsets[band] = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
        learned[band] = true;
    }

    float offset(int band) { return offsets[band]; }
    boolean learned(int band) { return learned[band]; }

    boolean learnedAny() {
        for (boolean b : learned) if (b) return true;
        return false;
    }

    /** Forgets every correction: back to the base curve. */
    void reset() {
        for (int i = 0; i < BANDS; i++) { offsets[i] = 0; learned[i] = false; }
    }

    /** Sets a band's stored offset (RidingBrightness reads them back from the settings). */
    void restore(int band, float offset) {
        if (band < 0 || band >= BANDS || Float.isNaN(offset) || Float.isInfinite(offset)) return;
        offsets[band] = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
        learned[band] = true;
    }

    /**
     * Moves the shown level toward the target for riding: brightening fast (about 0.5 s) when riding
     * into sun, dimming slowly (about 4 s), and ignoring changes within 8 % so a passing shadow
     * doesn't make the screen pump.
     */
    static final class Smoother {
        static final double BRIGHTEN_S = 0.5, DIM_S = 4.0;
        static final float HYSTERESIS = 0.08f;
        private float shown = -1, goal = -1;
        private long lastMs;

        /** The level now, or −1 before the first update. */
        float shown() { return shown; }
        float goal() { return goal; }
        boolean settled() { return shown >= 0 && shown == goal; }

        /** A new target from the light sensor (or a tick with the same target). Returns the level to show. */
        float update(float target, long nowMs) {
            target = clamp(target);
            if (shown < 0) { shown = goal = target; lastMs = nowMs; return shown; }
            // Settled: the glide starts now, not at the last update (the light sensor reports only on change).
            if (shown == goal) lastMs = nowMs;
            if (Math.abs(target - goal) > HYSTERESIS * goal || ((target == FLOOR || target == CEILING) && target != goal)) goal = target;
            double seconds = Math.max(0, nowMs - lastMs) / 1000.0;
            lastMs = nowMs;
            double tau = goal > shown ? BRIGHTEN_S : DIM_S;
            shown += (float) ((goal - shown) * (1 - Math.exp(-seconds / tau)));
            if (Math.abs(goal - shown) < 0.004f) shown = goal;
            return shown;
        }

        /** The rider picked a level: show it at once. */
        float jump(float level, long nowMs) {
            shown = goal = clamp(level);
            lastMs = nowMs;
            return shown;
        }
    }
}
