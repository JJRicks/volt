package com.jjricks.volt;

/**
 * Whether a scooter's reported current really carries a direction: the one rule riding mode's
 * live power and a recording's summary share. Most scooters sign their current (in the value's
 * top bit, or through a 0x79 sign state of 0 or 1). A GT3 Max answers 0x79 with 254 and reports
 * an unsigned magnitude, so its regen can't be told from draw at all.
 *
 * <ul>
 * <li>A sign state of 0 or 1: signed.</li>
 * <li>Any negative current: signed (the value's own sign bit).</li>
 * <li>A sign state of anything else, and never a negative current: unsigned.</li>
 * <li>No sign state at all (UT5 MAX): signed, exactly as recordings treat it.</li>
 * </ul>
 *
 * A live view uses {@link #signedNow()}, a whole recording {@link #signedRide()}. They differ for
 * a scooter that gave a 0 or 1 and then something else: Telemetry goes on applying its old sign
 * to later currents, so a negative after that is no evidence, and live readings stay unknown
 * until a 0 or 1 returns; the ride as a whole was still measured with a real sign.
 */
final class PowerSign {
    private static final int PRESENT = 1, KNOWN = 2, LATEST_KNOWN = 4, NEGATIVE = 8;
    private int state;

    /** A finite current_sign_state reading. A null sign state (UT5 MAX's empty row) is not one. */
    void signState(double value) {
        boolean known = value == 0 || value == 1;
        state = (state | PRESENT | (known ? KNOWN | LATEST_KNOWN : 0)) & (known ? ~0 : ~LATEST_KNOWN);
    }
    /** A finite current or power reading. Only a negative one is evidence; -0.0 is not. */
    void current(double value) { if (value < 0) state |= NEGATIVE; }

    /** Whether a new live reading has a real sign: the latest sign state decides, then the no-state rules. */
    boolean signedNow() {
        if ((state & LATEST_KNOWN) != 0) return true;
        return (state & KNOWN) == 0 && ((state & PRESENT) == 0 || (state & NEGATIVE) != 0);
    }
    /** Whether a recording as a whole was signed: anything but a sign state that was never 0 or 1 with no negative current. */
    boolean signedRide() { return (state & KNOWN) != 0 || (state & NEGATIVE) != 0 || (state & PRESENT) == 0; }

    /** The whole state as one value, for a saved ride summary. */
    int state() { return state; }
    void restore(int saved) { state = saved; }
}
