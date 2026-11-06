package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Focus frame's choices in riding mode (riding-mode plan, owner Q10: Focus is the first frame),
 * kept Android-free so plain JVM tests run them: the arrangement, which cells show, the connection
 * state, what the range spot shows, battery segments and stopwatch digits. Riding mode only reads;
 * nothing here can send a scooter command, and there is no lock state (owner, Q16).
 */
final class RidingFocus {
    private RidingFocus() {}

    /** Text scale (Volt's text size times the system font scale) treated as large text. */
    static final float LARGE_TEXT = 1.3f;
    /** A wide window this tall keeps the stacked phone arrangement at large text (the plan's rule). */
    static final int STACKED_MIN_HEIGHT_DP = 560;
    /** The battery row: one wide cell with its segment bar. */
    static final String BATTERY_ROW = "tile_1";
    /** The bottom row, in order: trip, ride time and battery temperature (owner's Focus tweak). */
    static final List<String> BOTTOM_ROW = Collections.unmodifiableList(Arrays.asList("tile_2", "tile_3", "tile_4"));
    /** The bottom row's cell dropped at large text, so the others keep their full size. */
    static final String DROPPED_AT_LARGE_TEXT = "tile_3";
    static final int SEGMENTS = 10;
    /** The RIDE button keeps its word while it takes at most this share of Home's header row. */
    static final float RIDE_LABEL_SHARE = .34f;
    static final double KM_PER_MI = 1.609344;

    static boolean largeText(float textScale) { return textScale >= LARGE_TEXT - 1e-4f; }

    /**
     * Speed beside the readings (landscape, a wide or unfolded window) when the window is wider than
     * tall. Wide plus large text keeps the stacked phone arrangement when the window is tall enough.
     */
    static boolean sideBySide(int widthDp, int heightDp, boolean largeText) {
        if (widthDp <= heightDp) return false;
        return !(largeText && heightDp >= STACKED_MIN_HEIGHT_DP);
    }

    /** Labels grow with the text size up to this; a short side-by-side window (a phone sideways) caps them lower. */
    static final float LABEL_CAP = 1.4f, SHORT_LABEL_CAP = 1.15f;
    /** Below this height a side-by-side window is short. */
    static final int SHORT_HEIGHT_DP = 420;

    /** How far labels grow with the text size, so a short window's numbers keep their height. */
    static float labelCap(boolean sideBySide, int heightDp) {
        return sideBySide && heightDp < SHORT_HEIGHT_DP ? SHORT_LABEL_CAP : LABEL_CAP;
    }

    /** The bottom row's spots at this text size. */
    static List<String> bottomRow(boolean largeText) {
        List<String> out = new ArrayList<>(BOTTOM_ROW);
        if (largeText) out.remove(DROPPED_AT_LARGE_TEXT);
        return out;
    }

    /** The status pill's connection state. */
    enum Link { CONNECTED, RECONNECTING, NOT_CONNECTED }

    /**
     * Connected while the scooter's readings are current; Reconnecting while the link is up or being
     * re-made (the service's own reconnect does the work); otherwise Not connected.
     */
    static Link link(boolean ready, boolean reconnecting) {
        return ready ? Link.CONNECTED : reconnecting ? Link.RECONNECTING : Link.NOT_CONNECTED;
    }

    /** Filled battery segments: 72 % fills 7 of 10; any charge above zero lights at least one. */
    static int segments(double percent) {
        if (!Double.isFinite(percent) || percent <= 0) return 0;
        return (int) Math.max(1, Math.min(SEGMENTS, Math.round(percent / (100.0 / SEGMENTS))));
    }

    /** One tick when the battery drops into a lower band (amber below 30 %, red below 15 %), never on the way up. */
    static boolean droppedBand(RidingValues.Band before, RidingValues.Band now) {
        return before != null && now != null && now.ordinal() > before.ordinal();
    }

    /** A stopwatch's digits, {hours, minutes, seconds}: hours is 0 under an hour. */
    static long[] clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return new long[]{s / 3600, s >= 3600 ? s % 3600 / 60 : s / 60, s % 60};
    }

    /** A stopwatch time, "12:48" or "1:02:48", in the app language's digits. */
    static String clockText(long ms) {
        long[] c = clock(ms);
        return c[0] > 0 ? T.ridingClockHours(Fmt.integer(c[0]), two(c[1]), two(c[2])) : T.ridingClockMinutes(Fmt.integer(c[1]), two(c[2]));
    }
    private static String two(long n) { return n < 10 ? Fmt.integer(0) + Fmt.integer(n) : Fmt.integer(n); }

    /** A value rounded to tenths, as {whole, tenth}: 18.96 is {19, 0}, never {18, 10}. */
    static long[] tenths(double value) {
        long t = Math.round(Math.abs(value) * 10);
        return new long[]{t / 10, t % 10};
    }

    /** Whether the RIDE button shows its word, or only the speedometer in its red disc. */
    static boolean rideLabelFits(float labelledWidthPx, float headerWidthPx) {
        return headerWidthPx > 0 && labelledWidthPx <= RIDE_LABEL_SHARE * headerWidthPx;
    }

    /** km in the rider's distance unit: miles for "mi" and "dual", km for "km". */
    static double distance(double km, String units) { return "km".equals(units) ? km : km / KM_PER_MI; }

    /** What the range spot shows. */
    enum RangeKind {
        /** Volt's Range at this pace (live range). */
        AT_PACE,
        /** Volt's estimate from past rides, while there's no live energy model: approximate, whole numbers. */
        VOLT,
        /** The scooter's own estimate, labelled as the scooter's. */
        SCOOTER,
        /** The battery is nearly flat: words, no number. */
        ALMOST_EMPTY,
        /** Nothing to show yet. */
        NONE
    }

    static final class Range {
        final RangeKind kind;
        /** In the rider's distance unit; null for ALMOST_EMPTY and NONE. */
        final Double value;
        final int decimals;
        final boolean approx, amber, dim;
        Range(RangeKind kind, Double value, int decimals, boolean approx, boolean amber, boolean dim) {
            this.kind = kind; this.value = value; this.decimals = decimals; this.approx = approx; this.amber = amber; this.dim = dim;
        }
    }

    /**
     * The range spot for its chosen content (Range at this pace is the default, owner Q25 A). Until
     * Volt has a live energy model for this scooter, Range at this pace falls back to Volt's distance
     * estimate when there is one, then to the scooter's own estimate, labelled as the scooter's.
     *
     * @param live the live-range readout, or null before it has run
     * @param scooterKm the scooter's remaining range, or null
     * @param scooterStale true when that reading is older than its interval allows
     */
    static Range range(String content, RangeReadout.Value live, Double scooterKm, boolean scooterStale, String units) {
        if (RidingLayout.RANGE_SCOOTER.equals(content) || RidingLayout.RANGE_VOLT.equals(content)) return scooter(scooterKm, scooterStale, units);
        if (live != null) {
            switch (live.state) {
                case ALMOST_EMPTY: return new Range(RangeKind.ALMOST_EMPTY, null, 0, false, true, live.stale);
                case LIVE: case SETTLING: case LOW:
                    if (live.primary != null)
                        return new Range(RangeKind.AT_PACE, live.primary, live.decimals, live.approx, live.amber, live.held || live.stale);
                    break;
                case NO_HISTORY:
                    if (live.primary != null) return new Range(RangeKind.VOLT, live.primary, 0, true, false, live.stale);
                    break;
                default: break;
            }
        }
        return scooter(scooterKm, scooterStale, units);
    }

    private static Range scooter(Double km, boolean stale, String units) {
        if (km == null || !Double.isFinite(km) || km < 0) return new Range(RangeKind.NONE, null, 0, false, false, false);
        return new Range(RangeKind.SCOOTER, (double) Math.round(distance(km, units)), 0, false, false, stale);
    }
}
