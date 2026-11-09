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
 * A live view uses {@link #signedNow()}, a whole recording {@link #signedRide()} for whatever it
 * could not settle interval by interval. They differ for a scooter that gave a 0 or 1 and then
 * something else: Telemetry goes on applying its old sign to later currents, so a negative after
 * that is no evidence, and live readings stay unknown until a 0 or 1 returns. A recording counts
 * those intervals as unconfirmed the same way (RideStore's Accumulator), whatever came before
 * or after them; the rules above then decide only a ride with no 0 or 1 at all.
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
    void current(double value) { if (value < 0 && !everKnown()) state |= NEGATIVE; }

    /** Whether any sign state has been read at all. */
    boolean present() { return (state & PRESENT) != 0; }
    /** Whether a sign state of 0 or 1 has ever been read: Telemetry's own sign is live from then on. */
    boolean everKnown() { return (state & KNOWN) != 0; }
    /** A sign state was read and the latest isn't 0 or 1: Telemetry's sign is stale or absent. */
    boolean latestUnknown() { return present() && (state & LATEST_KNOWN) == 0; }
    /** A negative current while no 0 or 1 had been read yet: the value's own sign bit, real evidence. */
    boolean sawNegative() { return (state & NEGATIVE) != 0; }

    /** Whether a new live reading has a real sign: the latest sign state decides, then the no-state rules. */
    boolean signedNow() {
        if ((state & LATEST_KNOWN) != 0) return true;
        return (state & KNOWN) == 0 && ((state & PRESENT) == 0 || (state & NEGATIVE) != 0);
    }
    /** Whether a recording's still-unsettled time counts as signed: anything but a sign state that was never 0 or 1 with no negative current. */
    boolean signedRide() { return (state & KNOWN) != 0 || (state & NEGATIVE) != 0 || (state & PRESENT) == 0; }

    /** The whole state as one value, for a saved ride summary. */
    int state() { return state; }
    void restore(int saved) { state = saved; }
}
