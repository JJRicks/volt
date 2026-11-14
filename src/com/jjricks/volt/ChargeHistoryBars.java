package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Battery & charging's HISTORY card (the owner's Layout Plan): the recent charges as a row of small bars, newest on
 * the right, and the last charge's summary line under them. Pure, so the JVM suite tests it; ChargeHistoryCard draws it.
 *
 * <p>Each bar is one saved charge recording (ChargeSessionStore), its fill the battery points it gained over the full
 * 0-100 track. As many bars as fit the card at a readable width, never more than {@link #MAX_BARS}; slots without a
 * charge show the empty track, so the card keeps its shape before the first recording.
 */
final class ChargeHistoryBars {
    private ChargeHistoryBars() { }

    /** The gap between bars and the narrowest bar, in dp (the Layout Plan: about 28 dp bars, 12-14 dp apart). */
    static final int GAP_DP = 12, MIN_BAR_DP = 28;
    /** Fewest and most bars a card shows, whatever its width. */
    static final int MIN_BARS = 4, MAX_BARS = 12;
    /** A charge's smallest drawn fill, so a short top-up still reads as a bar. */
    static final double MIN_FILL = 0.06;
    /** The track's height in dp; the Layout Plan's 66 px bars at 0.75 dp per px. */
    static final int TRACK_DP = 50;

    /** One saved charge: the first and last battery percent it recorded, and how long it ran. */
    static final class Charge {
        final double from, to; final long ms;
        Charge(double from, double to, long ms) { this.from = from; this.to = to; this.ms = Math.max(0, ms); }
        /** Points gained, 0 when the recording ended lower or has no battery samples. */
        double gained() { return Double.isFinite(from) && Double.isFinite(to) ? Math.max(0, to - from) : 0; }
        boolean measured() { return Double.isFinite(from) && Double.isFinite(to); }
    }

    /** A recording's charge: its first and last finite battery percent, and its first to last sample time (null for none). */
    static Charge of(ChargeSessionStore.HistorySession session) {
        if (session == null) return null;
        double first = Double.NaN, last = Double.NaN; long end = session.startedMono;
        for (ChargeSessionStore.HistoryPoint point : session.points) {
            if (Double.isFinite(point.batteryPercent)) { if (Double.isNaN(first)) first = point.batteryPercent; last = point.batteryPercent; }
            end = Math.max(end, point.mono);
        }
        return new Charge(first, last, end - session.startedMono);
    }

    /** How many bars a card with this much room inside its padding shows (dp): as many as fit at MIN_BAR_DP, within MIN..MAX. */
    static int maxBars(float innerWidthDp) {
        if (!(innerWidthDp > 0)) return MIN_BARS;
        int fit = (int) Math.floor((innerWidthDp + GAP_DP) / (MIN_BAR_DP + GAP_DP));
        return Math.max(MIN_BARS, Math.min(MAX_BARS, fit));
    }

    /** The Layout Plan's seven bars: the card keeps this many slots while there are fewer charges. */
    static final int PLAN_BARS = 7;

    /**
     * How many slots the card draws: one per charge, at least PLAN_BARS (empty tracks before the first charges), at
     * most what fits (maxBars). So a wide card with seven charges draws seven wider bars, not a row of empty tracks.
     */
    static int slots(float innerWidthDp, int charges) {
        return Math.min(maxBars(innerWidthDp), Math.max(PLAN_BARS, charges));
    }

    /** Each bar's width (dp) once `count` bars share the room with GAP_DP between them. */
    static float barWidth(float innerWidthDp, int count) {
        if (count <= 0 || !(innerWidthDp > 0)) return 0;
        return Math.max(0, (innerWidthDp - GAP_DP * (count - 1)) / count);
    }

    /**
     * The bars' fills, oldest to newest, `slots` long: the newest charges (newestFirst: the store's order) right-aligned,
     * each the points it gained over 100 (at least MIN_FILL, at most 1); -1 for a slot with no charge, drawn as the track.
     */
    static double[] fills(List<Charge> newestFirst, int slots) {
        double[] out = new double[Math.max(0, slots)];
        java.util.Arrays.fill(out, -1);
        if (newestFirst == null || slots <= 0) return out;
        List<Charge> shown = new ArrayList<>();
        for (Charge c : newestFirst) { if (c != null) shown.add(c); if (shown.size() == slots) break; }
        Collections.reverse(shown);
        int start = slots - shown.size();
        for (int i = 0; i < shown.size(); i++) out[start + i] = Math.max(MIN_FILL, Math.min(1, shown.get(i).gained() / 100d));
        return out;
    }

    /** The charge the summary line describes: the newest one with battery samples, or null (the line says there are none). */
    static Charge last(List<Charge> newestFirst) {
        if (newestFirst == null) return null;
        for (Charge c : newestFirst) if (c != null && c.measured()) return c;
        return null;
    }
}
