package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GPS accuracy: the radius, in metres, that each phone fix reports about itself (Location.getAccuracy). Lower is
 * better. It is a distance only, never a position, and nothing here reads or keeps one. Pure Java: the ride graph's
 * line (its unit, values and scale) and the riding-mode reading's state, so both can be tested without a phone.
 */
public final class GpsAccuracy {
    private GpsAccuracy() { }

    /** The graph line's key, remembered in ride_chart_series like the other lines (off unless chosen). */
    public static final String KEY = "gps_accuracy";
    /** The line's data units: feet for miles and Both, metres for kilometres (as elevation). */
    public static final String FEET = "ft", METRES = "m";
    public static final double FEET_PER_METRE = 3.280839895;
    /** A fix older than this is never shown as current: the recorder's own "signal lost" rule. */
    public static final long STALE_MS = 10000;
    /** The line's colour: a violet none of the eight ride lines use; its pattern is dotted (chart index 8). */
    public static final int COLOR = 0xff9f7fe0;
    public static final int CHART_INDEX = 8;
    /** The lowest top the graph scale uses (10 m, 30 ft), so a ride of good fixes doesn't look noisy. */
    static final double FLOOR_METRES = 10, FLOOR_FEET = 30;

    /** Feet unless the units setting is kilometres: the rule the ride graph and riding mode share. */
    public static boolean feet(String units) { return !"km".equals(units); }
    public static String unit(boolean feet) { return feet ? FEET : METRES; }
    /** A reported accuracy in the shown unit. */
    public static double shown(double metres, boolean feet) { return feet ? metres * FEET_PER_METRE : metres; }

    /**
     * The whole number shown after "±": rounded, but never 0 for a fix that reported any radius at all (a fix is
     * never exactly right; 0 only for a fix that said 0, as test providers do).
     */
    public static long whole(double value) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        return Math.max(1, Math.round(value));
    }

    /**
     * The graph's scale for these shown values: 0 at the bottom, so lower always reads as better, and a top that
     * the typical fix decides, not the worst one. The top covers the largest value unless that is more than half
     * again the 95th percentile; then it stops at 1.5 x the 95th percentile and the few worse fixes draw at the top
     * edge (inspecting one still shows its real value, and the top tick reads as "at least"). It is never below the
     * floor (10 m, 30 ft) and rounds up to four even steps of 1, 2, 3 or 5 x 10^n.
     */
    public static ChartScale.Range scale(List<Double> shownValues, boolean feet) {
        List<Double> sorted = new ArrayList<>();
        if (shownValues != null) for (Double v : shownValues) if (v != null && Double.isFinite(v) && v >= 0) sorted.add(v);
        Collections.sort(sorted);
        double floor = feet ? FLOOR_FEET : FLOOR_METRES, wanted = floor;
        if (!sorted.isEmpty()) {
            double max = sorted.get(sorted.size() - 1);
            double p95 = sorted.get((int) Math.floor(0.95 * (sorted.size() - 1)));
            wanted = Math.max(floor, Math.min(max, Math.max(floor, p95 * 1.5)));
        }
        return new ChartScale.Range(0, niceTop(wanted * 1.05));
    }

    /** The smallest top of four whole, even steps (1, 2, 3 or 5 x 10^n each) that reaches value. */
    static double niceTop(double value) {
        if (!Double.isFinite(value) || value <= 4) return 4;
        double step = value / 4, magnitude = Math.pow(10, Math.floor(Math.log10(step))), scaled = step / magnitude;
        double nice = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 3 ? 3 : scaled <= 5 ? 5 : 10;
        double top = 4 * nice * magnitude;
        return Double.isFinite(top) ? top : Double.MAX_VALUE;
    }

    // The riding-mode reading.

    /** What the phone recorder's GPS is doing, read from it without changing it. */
    public enum Phase {
        /** No phone recording, or one without GPS (switched off, no permission, Location off, simulation). */
        OFF,
        /** GPS is starting or waiting for its first fix. */
        WAITING,
        /** GPS has delivered a fix; whether it is still current is the reading's call (STALE_MS). */
        LIVE,
        /** GPS ran and then went quiet or its provider went away. */
        LOST
    }

    /** The latest fix: its own elapsed-realtime time and reported accuracy. No position. */
    public static final class Fix {
        public final long monoMs;
        public final double metres;
        public Fix(long monoMs, double metres) { this.monoMs = monoMs; this.metres = metres; }
    }

    /** The plain states the reading shows instead of a value. */
    public enum State { NOT_RECORDING, OFF, WAITING, NO_FIX, FIX }

    public static final class Reading {
        public final State state;
        /** The fix's accuracy in metres, for FIX only. */
        public final Double metres;
        private Reading(State state, Double metres) { this.state = state; this.metres = metres; }
    }

    /**
     * The reading now. A fix shows only while a ride records with GPS and the fix is at most STALE_MS old by its own
     * time (nowMs is SystemClock.elapsedRealtime, the fix's clock); an older one reads as no fix, never as current.
     */
    public static Reading reading(boolean recording, Phase phase, Fix fix, long nowMs) {
        if (!recording) return new Reading(State.NOT_RECORDING, null);
        if (phase == null || phase == Phase.OFF) return new Reading(State.OFF, null);
        if (phase == Phase.LIVE && fix != null && Double.isFinite(fix.metres) && fix.metres >= 0
                && nowMs - fix.monoMs <= STALE_MS && fix.monoMs - nowMs <= 1000)
            return new Reading(State.FIX, fix.metres);
        if (phase == Phase.WAITING) return new Reading(State.WAITING, null);
        return new Reading(State.NO_FIX, null);
    }
}
