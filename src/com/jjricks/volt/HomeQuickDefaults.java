package com.jjricks.volt;

import java.util.List;

/**
 * Clean Home's default quick actions for the connected scooter (owner's answer 11 on #383): the scooter-side
 * questions HomeQuickActions.defaults asks, answered with the same gates the Battery and Controls pages use.
 */
final class HomeQuickDefaults {
    private HomeQuickDefaults() {}

    /** The defaults for the active scooter; with no service, the UT5 MAX catalogue's (what the pages show then). */
    static List<String> forScooter(ScooterService service) {
        BleController ble = service == null ? null : service.ble;
        // The Battery page's Charge done by rule (MainActivity.chargeDoneOffered): scheduled charging exists.
        boolean doneBy = ble == null || ble.controlOffered("timed_charging") && ble.controlOffered("charge_start");
        boolean alerts = service != null && service.chargeAlertsOffered();
        return HomeQuickActions.defaults(doneBy, alerts, key -> quickControl(ble, key));
    }

    /**
     * A control the scooter offers as an ordinary row: CapabilityResolver's offered rule (BleController.controlOffered),
     * never a Lab mapping, and a UT5 MAX experimental control only where another model maps it normally
     * (the Controls page's ordinaryOemRow). Sensitive controls never become a default.
     */
    static boolean quickControl(BleController ble, String key) {
        ScooterCommands.Feature feature = ScooterCommands.find(key);
        if (feature == null || feature.sensitive) return false;
        if (ble == null) return !feature.experimental;
        if (!ble.controlOffered(key)) return false;
        CapabilityResolver.Decision decision = ble.decision(key);
        FeatureVariant variant = decision.variant;
        if (variant != null && variant.lab) return false;
        if (!feature.experimental) return true;
        return variant != null && variant.decode != FeatureVariant.Decode.UT5_FEATURE
                && decision.support == CapabilityResolver.Support.KNOWN_ENCODING;
    }
}
