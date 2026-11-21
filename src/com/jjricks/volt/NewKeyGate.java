package com.jjricks.volt;

/**
 * When the "Make a new owner key" page's Continue works (OnboardingScreen.newKey). A scooter that is still in the
 * NAVEE app has an owner and refuses a key Volt makes, so Continue waits for the rider to tick "Scooter isn't in the
 * NAVEE app", as the typed-key page's Save waits for a whole key. The page's other tick, the parked reminder, is
 * not an input: it never gates. Pure, so the host tests run it.
 */
final class NewKeyGate {
    private NewKeyGate() {}

    /**
     * True when Continue may make the key: the scooter store is ready (setup's local service has started) and the
     * "isn't in the NAVEE app" tick is checked.
     */
    static boolean continueEnabled(boolean storeReady, boolean notInNaveeTicked) {
        return storeReady && notInNaveeTicked;
    }
}
