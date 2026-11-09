package com.jjricks.volt;

/** The direction rule shared by riding mode's live power and a recording's summary. */
public final class PowerSignTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        // No sign state at all (UT5 MAX): signed, like a recording.
        PowerSign none = new PowerSign();
        check(none.signedNow() && none.signedRide(), "no sign state is signed");
        none.current(5); check(none.signedNow() && none.signedRide(), "positive current doesn't change that");

        // A sign state of 0 or 1: signed.
        for (double state : new double[]{0, 1}) {
            PowerSign known = new PowerSign(); known.signState(state);
            check(known.signedNow() && known.signedRide(), "sign state " + state + " is signed");
        }

        // A sign state of anything else, never negative (GT3 Max): unsigned.
        PowerSign gt3 = new PowerSign(); gt3.signState(254);
        check(!gt3.signedNow() && !gt3.signedRide(), "254 without a negative current is unsigned");
        gt3.current(12.5); gt3.current(0); gt3.current(-0.0);
        check(!gt3.signedNow() && !gt3.signedRide(), "positive currents, and -0, leave it unsigned");

        // Any negative current: signed, even under an unknown sign state and in either order.
        PowerSign negative = new PowerSign(); negative.current(-3); negative.signState(254);
        check(negative.signedNow() && negative.signedRide(), "a negative current before 254 is signed");
        PowerSign later = new PowerSign(); later.signState(254); later.current(-0.5);
        check(later.signedNow() && later.signedRide(), "a negative current after 254 is signed");

        // A real sign once, then something else: live waits for a known byte; the ride had a real sign.
        PowerSign invalid = new PowerSign(); invalid.signState(1); invalid.current(-8); invalid.signState(254);
        check(!invalid.signedNow(), "a known sign going to 254 leaves new readings unknown, whatever Telemetry's stale sign decodes");
        check(invalid.signedRide(), "the ride was still measured under a real sign");
        invalid.current(-9); check(!invalid.signedNow(), "a negative under a stale sign is no evidence");
        invalid.signState(0); check(invalid.signedNow(), "a known sign returning is signed again");

        // A summary's saved state restores the same answers.
        for (PowerSign original : new PowerSign[]{gt3, later, invalid, none}) {
            PowerSign restored = new PowerSign(); restored.restore(original.state());
            check(restored.state() == original.state() && restored.signedRide() == original.signedRide()
                    && restored.signedNow() == original.signedNow(), "state round-trips");
        }
        System.out.println("PowerSignTest: " + checks + " checks passed");
    }
}
