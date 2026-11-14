package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Halo and Trace frames' choices in riding mode (riding-mode plan PR 3), kept Android-free so
 * plain JVM tests run them: the speed ring's scale, the live trace's runs and axes, which optional
 * parts show at large text, and when the frame picker shows. Focus stays the default frame (owner,
 * Q10). Nothing here sends a scooter command. Stopped, riding mode also shows Home's Lock and Record
 * (owner, Nov 8, reversing Q12 A and Q16); this class only says when.
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
    /** Trace's first two chips, as they always were; its whole column, a third included, is TRACE_COLUMN. */
    static final List<String> TRACE_CHIPS = Collections.unmodifiableList(Arrays.asList("chip_1", "chip_2"));
    /** Trace's whole column of chips, stacked beside the speed: its two, then a third (a tester, Nov 14), None by default. */
    static final List<String> TRACE_COLUMN = Collections.unmodifiableList(Arrays.asList("chip_1", "chip_2", "chip_3"));
    /** Focus's chips beside the speed, stacked; every one None until the rider picks a reading. */
    static final List<String> FOCUS_CHIPS = Collections.unmodifiableList(Arrays.asList("chip_1", "chip_2", "chip_3"));
    static final List<String> TILES = Collections.unmodifiableList(Arrays.asList("tile_1", "tile_2"));
    /** Below this speed (km/h) the rider counts as stopped for the picker, with the scooter's own stationary state. */
    static final double STOPPED_KMH = 1;
    /** 4 mph in km/h: at this speed or more, an open tune menu closes, in any display unit (owner, Nov 9). */
    static final double MENU_CLOSE_KMH = 6.437;
    /**
     * The stopped controls' widest Lock and Record, dp, so the lock's slide stays a sensible length on a tablet. Side by
     * side, a wider slot keeps Record at its far end (the row spans its slot); stacked, the column is no wider than this.
     */
    static final float STOPPED_CONTROLS_MAX_DP = 560;

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
     * says it's stationary, and the latest speed is missing or under 1 km/h. The tune button, Lock and
     * Record use it through {@link #tuneShown}, and Lock's own taps through it alone.
     */
    static boolean pickerShown(boolean stationary, Double speedKmh) {
        return stationary && (speedKmh == null || speedKmh < STOPPED_KMH);
    }

    /**
     * The tune button, Lock and Record: hidden while the scooter moves, as {@link #pickerShown} says,
     * and also shown with no scooter connected (owner, Nov 9), so everything a tap can reach goes
     * together. Connected with no motion report yet (just after a reconnect) is not stopped: all three
     * wait for the first stationary report.
     */
    static boolean tuneShown(boolean connected, boolean stationary, Double speedKmh) {
        return !connected || pickerShown(stationary, speedKmh);
    }

    /** The speed the tune menu's close rule uses: the peak of this connection's newest fresh readings (Telemetry.takeSpeedPeak), or null with no scooter connected. */
    static Double menuKmh(boolean connected, Double peakKmh) { return connected ? peakKmh : null; }

    /**
     * A switch whose turn-on may ask something first: the speed check runs before the question opens, and
     * again when the answer arrives (inside save). Refused, the switch shows what's stored and nothing opens.
     */
    static void askBeforeSave(BooleanSupplier may, Consumer<Runnable> ask, Runnable save, Runnable showStored) {
        if (may.getAsBoolean()) ask.accept(save); else showStored.run();
    }

    /** The Record disc takes a tap as Home's does: connected or already recording, and no ride still saving. */
    static boolean recordEnabled(boolean recording, boolean ready, boolean saving) { return (recording || ready) && !saving; }

    /**
     * What a tap on the Record disc does (RecorderTap's START, STOP or NONE): Home's rule, but only
     * while stopped, so a tap that lands as the scooter starts moving does nothing. With no scooter
     * connected (owner, Nov 9) a recording still stops; otherwise nothing happens, never a start and
     * never a connection. A tap acts on the mark it landed on: when the recording started or ended
     * since the disc was drawn (an automatic end in the same moment), it does nothing, so one tap
     * can't stop one ride and start the next.
     *
     * @param showingStop the disc shows its stop mark
     */
    static int recordAction(boolean connected, boolean stopped, boolean recording, boolean ready, boolean showingStop) {
        if (recording != showingStop) return RecorderTap.NONE;
        int action = RecorderTap.lead(recording, ready, false);
        if (!connected) return action == RecorderTap.STOP ? action : RecorderTap.NONE;
        return stopped && action != RecorderTap.CONNECT ? action : RecorderTap.NONE;
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
     * The tune menu's life, owned by its screen. It opens only when the tune button would show and
     * on an open screen, and it auto-saves. Once open, only a live speed of 4 mph (6.437 km/h) or more
     * closes it, in any display unit; losing the stationary flag or the readings doesn't. A screen
     * close (rotation, folding, any rebuild) ends it, and a change counts only on the screen that
     * opened it and below that speed.
     */
    static final class PickerSheet {
        private boolean open, closed;

        /** Opens the menu; false when the screen is closed or the tune button isn't showing. */
        boolean open(boolean allowed) {
            if (closed || !allowed) return false;
            open = true;
            return true;
        }

        boolean isOpen() { return open; }

        /** The menu went away by itself (its close button, Back or a tap outside). */
        void dismissed() { open = false; }

        /** @param speedKmh the live speed, or null with none: true when an open menu must close. */
        boolean update(Double speedKmh) {
            if (!open || !closing(speedKmh)) return false;
            open = false;
            return true;
        }

        /** The screen closes: true when an open menu must be dismissed with it. */
        boolean close() {
            closed = true;
            boolean was = open;
            open = false;
            return was;
        }

        /** A change in the menu: true only on an open menu of an open screen below the close speed. The menu stays open. */
        boolean accept(Double speedKmh) { return open && !closed && !closing(speedKmh); }

        private static boolean closing(Double speedKmh) { return speedKmh != null && speedKmh >= MENU_CLOSE_KMH; }
    }
}
