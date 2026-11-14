package com.jjricks.volt;

/**
 * How Home draws attention to an odometer number after a carry-over (OdometerCarry) the rider hasn't opened yet: a
 * gentle pulse of the number's colour toward the accent, two soft pulses then a pause, well under three flashes a
 * second. With animations off it is the accent colour and a small dot, still. Never while riding or moving: only on
 * Home while the scooter is stopped. Pure Java; DashboardScreen draws it.
 */
final class OdometerAttention {
    enum Style { NONE, PULSE, STILL }

    /** One pulse, and the whole cycle: two pulses then a pause as long as both. */
    static final long PULSE_MS = 900, CYCLE_MS = 3600;

    private OdometerAttention() { }

    /**
     * The style for an odometer number. {@code unacknowledged}: a carry-over the rider hasn't opened; {@code motion}:
     * the system lets apps animate (animator duration scale above 0, Remove animations off); {@code riding}: riding mode
     * or the number isn't on screen; {@code moving}: the scooter reports speed.
     */
    static Style style(boolean unacknowledged, boolean motion, boolean riding, boolean moving) {
        if (!unacknowledged || riding || moving) return Style.NONE;
        return motion ? Style.PULSE : Style.STILL;
    }

    /** The pulse's strength, 0 to 1, at a time into the cycle: a raised cosine for each of the two pulses, 0 in the pause. */
    static double pulse(long ms) {
        long t = ((ms % CYCLE_MS) + CYCLE_MS) % CYCLE_MS;
        if (t >= 2 * PULSE_MS) return 0;
        double phase = (t % PULSE_MS) / (double) PULSE_MS;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * phase);
    }
}
