package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Halo and Trace frames' choices in riding mode (riding-mode plan PR 3), kept Android-free so
 * plain JVM tests run them: the speed ring's scale, the live trace's runs and axes, which optional
 * parts show at large text, and when the frame picker shows. Focus stays the default frame (owner,
 * Q10). Riding mode only reads; nothing here sends a scooter command, and there is no lock state (Q16).
 */
final class RidingFrames {
    private RidingFrames() {}

    /** The speed ring's sweep: a 270° arc open at the bottom, as the dashboard's gauge draws it. */
    static final float RING_START_DEG = 135, RING_SWEEP_DEG = 270;
    /** The ring's smallest top, in the rider's unit: 20 mph, or 30 km/h. */
    static final double RING_MIN_MPH = 20, RING_MIN_KMH = 30, RING_STEP = 5;
    /** The live trace shows the last minute (RidingValues keeps two). */
    static final long TRACE_WINDOW_MS = 60_000;
    /** Where the trace's zero power line sits, as a share of the chart's height from the top. */
    static final float TRACE_ZERO = .72f;
    /** Halo's strip of small readings, in order. */
    static final List<String> HALO_STRIP = Collections.unmodifiableList(Arrays.asList("strip_1", "strip_2"));
    static final List<String> HALO_CHIPS = Collections.unmodifiableList(Arrays.asList("chip_1", "chip_2", "chip_3"));
    static final List<String> TRACE_CHIPS = Collections.unmodifiableList(Arrays.asList("chip_1", "chip_2"));
    static final List<String> TILES = Collections.unmodifiableList(Arrays.asList("tile_1", "tile_2"));
    /** Below this speed (km/h) the rider counts as stopped for the picker, with the scooter's own stationary state. */
    static final double STOPPED_KMH = 1;

    /**
     * The ring's top in the rider's unit: the larger of 20 mph (30 km/h) and the fastest speed seen,
     * rounded up to 5. It follows what the scooter reported; it never implies a faster limit.
     *
     * @param fastestKmh the fastest speed seen this ride, km/h, or null
     */
    static double ringTop(Double fastestKmh, String units) {
        double floor = "km".equals(units) ? RING_MIN_KMH : RING_MIN_MPH;
        if (fastestKmh == null || !Double.isFinite(fastestKmh) || fastestKmh <= 0) return floor;
        double seen = RidingFocus.distance(fastestKmh, units);
        return Math.max(floor, Math.ceil(seen / RING_STEP - 1e-9) * RING_STEP);
    }

    /** A value's share of a scale, 0 to 1; 0 for a missing or unusable value. */
    static float share(Double value, double top) {
        if (value == null || !Double.isFinite(value) || !(top > 0)) return 0;
        return (float) Math.max(0, Math.min(1, value / top));
    }

    /** The ring's angle in degrees for a share of its scale. */
    static float ringAngle(float share) { return RING_START_DEG + RING_SWEEP_DEG * Math.max(0, Math.min(1, share)); }

    /**
     * The trace's unbroken runs, oldest first. A point after a gap starts a new run, so a gap is drawn
     * as a gap and never as a line through zero; a run of one point is a dot.
     */
    static List<List<RidingValues.TracePoint>> runs(List<RidingValues.TracePoint> points) {
        List<List<RidingValues.TracePoint>> out = new ArrayList<>();
        List<RidingValues.TracePoint> run = null;
        for (RidingValues.TracePoint p : points) {
            if (run == null || p.gapBefore) out.add(run = new ArrayList<>());
            run.add(p);
        }
        return out;
    }

    /** A point's x as a share of the chart's width: 0 a minute ago, 1 now. */
    static float x(long t, long nowMs) {
        return Math.max(0, Math.min(1, 1 - (nowMs - t) / (float) TRACE_WINDOW_MS));
    }

    /**
     * A power point's y as a share of the chart's height: draw rises from the zero line to the top at
     * the draw scale, and regen falls below it to the bottom at the regen scale.
     */
    static float powerY(double watts, RidingValues.PowerScale scale) {
        if (watts >= 0) return TRACE_ZERO * (1 - share(watts, scale.drawW));
        return TRACE_ZERO + (1 - TRACE_ZERO) * share(-watts, scale.regenW);
    }

    /** A speed point's y as a share of the chart's height: 0 at the bottom, the ring's top at the top. */
    static float speedY(double kmh, double top, String units) {
        return 1 - share(RidingFocus.distance(kmh, units), top);
    }

    /** The fastest speed in the trace, km/h, or null when it's empty. */
    static Double fastest(List<RidingValues.TracePoint> speeds, Double alsoKmh) {
        Double best = alsoKmh != null && Double.isFinite(alsoKmh) ? alsoKmh : null;
        for (RidingValues.TracePoint p : speeds) if (best == null || p.value > best) best = p.value;
        return best;
    }

    /** Halo's strip: shown in full at normal text; at large text it leaves the tiles their room. */
    static List<String> haloStrip(boolean largeText) {
        return largeText ? Collections.<String>emptyList() : HALO_STRIP;
    }

    /**
     * The frame picker shows only while stopped (owner Q17 A, like the editor's pencil): the scooter
     * says it's stationary, and the latest speed is missing or under 1 km/h.
     */
    static boolean pickerShown(boolean stationary, Double speedKmh) {
        return stationary && (speedKmh == null || speedKmh < STOPPED_KMH);
    }

    /**
     * Chooses a frame in the saved layout; the other frames keep their choices. False for an unknown
     * frame or the one already shown, when there is nothing to save.
     */
    static boolean choose(RidingLayout layout, String frameId) {
        if (RidingLayout.frameDef(frameId) == null || frameId.equals(layout.frame())) return false;
        return layout.setFrame(frameId);
    }

    /**
     * The picker sheet's life, owned by its screen. It opens only while stopped and on an open
     * screen; it must close when riding resumes or the screen is closed (rotation, folding, any
     * rebuild), and a choice counts only on the screen that opened it while still stopped.
     */
    static final class PickerSheet {
        private boolean open, closed;

        /** Opens the sheet; false when the screen is closed or the scooter isn't stopped. */
        boolean open(boolean stopped) {
            if (closed || !stopped) return false;
            open = true;
            return true;
        }

        boolean isOpen() { return open; }

        /** The sheet went away by itself (its close button, Back or a tap outside). */
        void dismissed() { open = false; }

        /** On each refresh: true when an open sheet must be dismissed because riding resumed. */
        boolean update(boolean stopped) {
            if (!open || stopped) return false;
            open = false;
            return true;
        }

        /** The screen closes: true when an open sheet must be dismissed with it. */
        boolean close() {
            closed = true;
            boolean was = open;
            open = false;
            return was;
        }

        /** A tapped choice: true only on an open sheet of an open screen while still stopped. The sheet closes either way. */
        boolean accept(boolean stopped) {
            boolean ok = open && !closed && stopped;
            open = false;
            return ok;
        }
    }
}
