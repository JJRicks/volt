package com.jjricks.volt;

/**
 * How Home draws attention to an odometer number after a carry-over (OdometerCarry) the rider hasn't opened yet: a
 * small orange dot at the number's top-end corner, like a notification badge, with a ring that grows out of it and
 * fades away, sonar-like, one ring every {@link #RING_MS}. The number itself stays plain. With animations off, or
 * while the scooter reports speed, only the still dot; in riding mode or off screen, nothing. Pure Java;
 * DashboardScreen draws it.
 */
final class OdometerAttention {
    enum Style { NONE, SONAR, DOT }

    /** One ring's life, from the dot's edge to gone: well under three a second. */
    static final long RING_MS = 1400;
    /** The ring grows to this many times the dot's radius (less where the card has no room). */
    static final float RING_REACH = 3f;

    private OdometerAttention() { }

    /**
     * The style for an odometer number. {@code unacknowledged}: a carry-over the rider hasn't opened; {@code opens}: a
     * tap on the number's area opens the explanation (a cell with its own tap action keeps it, and gets no cue, since
     * the cue and "double-tap for details" would promise something the tap doesn't do); {@code motion}: the system
     * lets apps animate (animator duration scale above 0, Remove animations off); {@code riding}: riding mode or the
     * number isn't on screen; {@code moving}: the scooter reports speed, when only the still dot shows.
     */
    static Style style(boolean unacknowledged, boolean opens, boolean motion, boolean riding, boolean moving) {
        if (!unacknowledged || !opens || riding) return Style.NONE;
        return motion && !moving ? Style.SONAR : Style.DOT;
    }

    /** How far through its life the ring is, 0 to 1, at a time into the animation. */
    static float ring(long ms) {
        return (((ms % RING_MS) + RING_MS) % RING_MS) / (float) RING_MS;
    }

    /** The ring's radius at {@code progress}: from the dot's edge out to {@code reach}, easing out. */
    static float ringRadius(float dot, float reach, float progress) {
        float p = Math.max(0, Math.min(1, progress)), out = 1 - (1 - p) * (1 - p) * (1 - p);
        return dot + (Math.max(dot, reach) - dot) * out;
    }

    /**
     * Where the cue sits on an odometer gauge (a 270-degree ring centred at {@code cx, cy} with outer radius
     * {@code outer}, in a gauge {@code width} wide): at the top-end corner of the ring's square, on its diagonal, as far
     * from the ring as from the gauge's top and end edges. Returns {x, y, room}: the dot's centre in the gauge's
     * coordinates, and how far the ring may reach from it, {@code margin} short of the ring and of those edges.
     */
    static float[] gaugeSpot(float cx, float cy, float outer, float width, boolean rtl, float margin) {
        float edge = Math.min(cy, rtl ? cx : width - cx), root2 = (float) Math.sqrt(2);
        // t along each axis: the gap to the ring is t * sqrt(2) - outer, to the edges edge - t; equal here.
        float t = Math.max(0, (edge + outer) / (1 + root2));
        return new float[]{rtl ? cx - t : cx + t, cy - t, edge - t - margin};
    }

    /** The ring's opacity at {@code progress}, 0 to 1: strongest leaving the dot, transparent at the end. */
    static float ringAlpha(float progress) {
        return 1 - Math.max(0, Math.min(1, progress));
    }
}
