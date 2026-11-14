package com.jjricks.volt;

/**
 * Where the charge track's parts go (ChargeTrack): a full rounded track, filled to the charge, with a tick at the charge
 * limit. Pure, so the host tests run it. Widths and positions are pixels from the track's start.
 */
final class ChargeTrackShape {
    private ChargeTrackShape() {}

    /**
     * The fill's end for a level in percent, or NaN when there is no fill (an unknown or empty level). A small level
     * still draws a round dot as wide as the track is thick, and a level over 100 stops at the end.
     */
    static float fillEnd(float width, float thick, double level) {
        if (Double.isNaN(level) || level <= 0 || width <= 0) return Float.NaN;
        return Math.min(width, Math.max(thick, width * (float) (Math.min(100, level) / 100)));
    }

    /** The limit tick's centre for a charge limit in percent, or NaN when there is none: unknown, 100% or above, or below 0. */
    static float tickAt(float width, double limit) {
        if (Double.isNaN(limit) || limit >= 100 || limit < 0 || width <= 0) return Float.NaN;
        return width * (float) (limit / 100);
    }

    /**
     * The limit tick's top and bottom for a track from trackTop, thick tall: it reaches above pixels over the track and
     * below pixels under it (Clean's charging view: ChargingFit.TICK_DP each way; the summary card: the plan's 4.5 dp each way).
     */
    static float[] tickSpan(float trackTop, float thick, float above, float below) {
        return new float[]{trackTop - Math.max(0, above), trackTop + thick + Math.max(0, below)};
    }
}
