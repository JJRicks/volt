package com.jjricks.volt;

/**
 * 2.0.5 item 5: the words for a scooter switched off but still answering, now in the catalog (ScooterOffWords), and the
 * refusal they bring: every change but the charging settings says "Turn the scooter on first" (a decision marked
 * switchedOff, whose reason stays NO_MOTION_REPORT so no Reason or pinned reason English changes), the UT5 MAX
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
        same("Turn the scooter on first", ScooterOffWords.refusal(), "the refusal");
        same("Turn the scooter on first", ScooterOffWords.refusalMessage(), "the refusal, deferred");
        same("No motion report yet", CapabilityResolver.text(CapabilityResolver.Reason.NO_MOTION_REPORT), "the reason's own words are unchanged");
    }

    private static void refusals() {
        check(CapabilityResolver.OFF_REASON == CapabilityResolver.Reason.NO_MOTION_REPORT, "Switched off refuses with no motion report, in its own words");
        DeviceDescriptor ut5 = ModelRegistryTest.serial("Q2538ZZ9US7777777");
        StatusFields s = CapabilityResolverTest.status(CapabilityResolverTest.full(), 100000);
        CapabilityResolver.Runtime rt = CapabilityResolverTest.parked(); rt.stationary = false; rt.motionSeen = false; rt.scooterOff = true;
        for (String key : new String[]{"cruise_control", "headlight", "range_algorithm", "maximum_speed"}) {
            CapabilityResolver.Decision d = CapabilityResolver.decide(key, ut5, s, rt);
            check(d.reason == CapabilityResolver.OFF_REASON && d.switchedOff && d.blockedByRide() && !d.parkedOnly(), "Off refuses " + key + " and keeps it tappable");
            same("Turn the scooter on first", d.reasonText(), "the tap's words for " + key);
            same("Turn the scooter on first", d.reasonMessage(), "the deferred words for " + key);
            same("Turn the scooter on first", d.rowRefusal(), "the row's words for " + key);
            same("Turn the scooter on first", CapabilityResolver.noMotionNotice(d, true), "BleController's notice for " + key + " (UT5 MAX)");
            same("Turn the scooter on first", CapabilityResolver.noMotionNotice(d, false), "BleController's notice for " + key + " (another model)");
        }
        CapabilityResolver.Decision lock = CapabilityResolver.admit("lock", 1, ut5, s, rt);
        check(lock.switchedOff && LockTapQueue.gate(lock.available(), lock.reason, lock.switchedOff, false) == LockTapQueue.Gate.REFUSE,
                "Locking is refused while off, and a lock tap goes back at once with the words, never waits");
        same("Turn the scooter on first", lock.reasonText(), "the lock's words");
        check(CapabilityResolver.decide("charge_limit", ut5, s, rt).available(), "The charge limit stays usable while off");
        // Before the first motion report (not off): the UT5 MAX waits for the scooter's speed; other models keep theirs.
        rt.scooterOff = false;
        CapabilityResolver.Decision none = CapabilityResolver.decide("cruise_control", ut5, s, rt);
        same("Waiting for the scooter to report its speed", CapabilityResolver.noMotionNotice(none, true), "UT5 MAX, no motion report yet");
        same("No motion report yet", CapabilityResolver.noMotionNotice(none, false), "another model, no motion report yet");
        same("Bring the scooter to a stop first", M.bleStopScooterFirst(), "moving keeps its words");
    }
}
