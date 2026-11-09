package com.jjricks.volt;

import java.io.File;
import java.util.Locale;

/**
 * The electronic lock reads as its state on the Setting tile and Quick access button: DisplayUnits.lockStateText
 * gives both readback values, and QuickAccessGrid's chain puts it ahead of the shared variant's and the UT5 route's
 * generic On/Off. Other on/off features keep On/Off, and the English labels stay Off/On for logs and reports.
 * Fixtures are synthesized.
 */
public final class LockStateTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            tileChain();
            T.install(null);
            lockReads("Locked", "Unlocked", "On", "Off");
            T.install(ECHO);
            lockReads("⟦main_lock_locked⟧", "⟦main_lock_unlocked⟧", "⟦common_on⟧", "⟦common_off⟧");
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("LockStateTextTest: " + checks + " checks passed (the lock reads as its state; other features keep On/Off)");
    }

    private static void lockReads(String locked, String unlocked, String on, String off) {
        ScooterCommands.Feature lock = ScooterCommands.find("lock");
        equal(locked, DisplayUnits.lockStateText(lock, 1), "lock 1");
        equal(unlocked, DisplayUnits.lockStateText(lock, 0), "lock 0");
        check(DisplayUnits.lockStateText(lock, 2) == null, "a value the lock does not define is not a lock state");

        // Both routes reach the tile's chain with the generic words, which is why the lock's line comes first there.
        CapabilityResolver.Decision shared = decide("lock", ModelRegistryTest.advertised("39991"));
        check(shared.variant == ModelRegistry.SHARED_LOCK, "another model's lock uses the shared variant");
        equal(on, shared.oemText(1).render(), "the shared variant's own label, 1");
        equal(off, shared.oemText(0).render(), "the shared variant's own label, 0");
        CapabilityResolver.Decision ut5 = decide("lock", ModelRegistryTest.serial("Q2538ZZ9US7777777"));
        check(ut5.variant != null && ut5.variant.decode == FeatureVariant.Decode.UT5_FEATURE && ut5.oemText(1) == null,
                "the UT5 MAX lock has no variant label of its own");
        equal(on, DisplayUnits.featureValueText(lock, 1, "km"), "the UT5 route's generic label, 1");
        equal(off, DisplayUnits.featureValueText(lock, 0, "km"), "the UT5 route's generic label, 0");

        // The English ids stay data.
        equal("On", DisplayUnits.featureValue(lock, 1, "km"), "featureValue keeps the English label, 1");
        equal("Off", DisplayUnits.featureValue(lock, 0, "km"), "featureValue keeps the English label, 0");

        for (String key : new String[]{"cruise_control", "headlight", "proximity_key"}) {
            ScooterCommands.Feature feature = ScooterCommands.find(key);
            check(DisplayUnits.lockStateText(feature, 1) == null && DisplayUnits.lockStateText(feature, 0) == null, key + " is not a lock state");
            equal(on, DisplayUnits.featureValueText(feature, 1, "km"), key + " keeps On");
            equal(off, DisplayUnits.featureValueText(feature, 0, "km"), key + " keeps Off");
        }
        check(DisplayUnits.lockStateText(ScooterCommands.find("pin"), 1) == null, "pin is not a lock state");
        FeatureVariant cruise = decide("cruise_control", ModelRegistryTest.serial("Q2538ZZ9US7777777")).variant;
        equal(on, cruise.text(1).render(), "cruise_control's variant keeps On");
        equal(off, cruise.text(0).render(), "cruise_control's variant keeps Off");
    }

    /** QuickAccessGrid is an Android view, so its chain is read: the lock's line has to come before the variant's label. */
    private static void tileChain() {
        String grid = L10nFiles.read(new File("src/com/jjricks/volt/QuickAccessGrid.java"));
        int lock = grid.indexOf(": DisplayUnits.lockStateText(feature, value) != null ? DisplayUnits.lockStateText(feature, value)");
        int variant = grid.indexOf(": decision != null && decision.oemText(value) != null ? decision.oemText(value).render()");
        int pending = grid.indexOf(": unmapped ? NO_VALUE : pending ? T.dashboardModelTileUpdating() : value == null ? NO_VALUE");
        check(pending >= 0 && lock > pending && variant > lock, "pending and no-value come first, then the lock's state, then the variant's label");
    }

    private static CapabilityResolver.Decision decide(String key, DeviceDescriptor descriptor) {
        return CapabilityResolver.decide(key, descriptor, StatusFields.NONE, new CapabilityResolver.Runtime());
    }

    private static void equal(String expected, String actual, String what) {
        check(expected.equals(actual), what + ": expected [" + expected + "], got [" + actual + "]");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
