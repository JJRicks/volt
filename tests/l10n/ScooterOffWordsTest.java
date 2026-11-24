package com.jjricks.volt;

/**
 * 2.0.5 item 5: the words for a scooter switched off but still answering, now in the catalog (ScooterOffWords), and the
 * refusal they bring: every change but the charging settings says "Turn the scooter on first" (SCOOTER_OFF), the UT5 MAX
 * says it is waiting for the scooter's speed before the first motion report, and a moving scooter keeps "Bring the
 * scooter to a stop first" (BleController, unchanged). English only; synthetic scooters.
 */
public final class ScooterOffWordsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void same(String expected, Object actual, String what) {
        check(expected.equals(String.valueOf(actual)), what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    public static void main(String[] args) {
        words();
        refusals();
        System.out.println("ScooterOffWordsTest: " + checks + " checks passed");
    }

    private static void words() {
        same("Scooter off · Turn the scooter on to use it", ScooterOffWords.status(), "Home's status line");
        same("Scooter off", ScooterOffWords.statusShort(), "its short rung");
        same("Scooter off. Turn the scooter on to use it. Connection details", ScooterOffWords.spoken(), "spoken");
        same("Scooter off", ScooterOffWords.state(), "the connection state word");
        same("Turn the scooter on to use it", ScooterOffWords.detail(), "the line under it");
        same("Waiting for the scooter to report its speed", ScooterOffWords.waitingForSpeed(), "before the first motion report");
        same("Turn the scooter on first", CapabilityResolver.text(CapabilityResolver.Reason.SCOOTER_OFF), "the refusal");
    }

    private static void refusals() {
        check(CapabilityResolver.OFF_REASON == CapabilityResolver.Reason.SCOOTER_OFF, "Switched off refuses with its own reason");
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        StatusFields s = CapabilityResolverTest.status(CapabilityResolverTest.full(), 100000);
        CapabilityResolver.Runtime rt = CapabilityResolverTest.parked(); rt.stationary = false; rt.motionSeen = false; rt.scooterOff = true;
        for (String key : new String[]{"cruise_control", "headlight", "range_algorithm", "maximum_speed"}) {
            CapabilityResolver.Decision d = CapabilityResolver.decide(key, ut5, s, rt);
            check(d.reason == CapabilityResolver.Reason.SCOOTER_OFF && d.blockedByRide(), "Off refuses " + key + " and keeps it tappable");
            same("Turn the scooter on first", d.reasonText(), "the tap's words for " + key);
        }
        check(CapabilityResolver.admit("lock", 1, ut5, s, rt).reason == CapabilityResolver.Reason.SCOOTER_OFF, "Locking is refused while off");
        check(LockTapQueue.gate(false, CapabilityResolver.Reason.SCOOTER_OFF, false) == LockTapQueue.Gate.REFUSE,
                "A lock tap while off goes back at once with the words, never waits");
        check(CapabilityResolver.decide("charge_limit", ut5, s, rt).available(), "The charge limit stays usable while off");
        // Before the first motion report (not off): the UT5 MAX waits for the scooter's speed; other models keep theirs.
        rt.scooterOff = false;
        CapabilityResolver.Decision none = CapabilityResolver.decide("cruise_control", ut5, s, rt);
        same("Waiting for the scooter to report its speed", CapabilityResolver.noMotionNotice(none, true), "UT5 MAX, no motion report yet");
        same("No motion report yet", CapabilityResolver.noMotionNotice(none, false), "another model, no motion report yet");
        same("Bring the scooter to a stop first", M.bleStopScooterFirst(), "moving keeps its words");
    }
}
