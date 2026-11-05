package com.jjricks.volt;

/**
 * Display text for BleController's English ids (batch B7, l10n/README.md). BleController keeps English
 * where it is data: message(), its shared constants (NOTHING_FOUND, OWNER_REJECTED, ...), the evidence
 * tokens of commandResults() and every event-log line, which MainActivity, OnboardingScreen, the
 * validations, ConnectionReport and the diagnostics read or compare. The screens show the catalog
 * message instead: BleController.displayMessage() (its notices pass through notice() here),
 * displayResult(key) and resultEvidence(key), and LockTapQueue.takeFailureMessage().
 *
 * Pure Java (no Android), so the JVM tests reach it. A message is matched only by its exact English:
 * anything this class does not know is shown as the English it is.
 */
final class ConnectionText {
    private ConnectionText() {}

    /**
     * What a notice shows. A Msg is shown as it is. Plain text (a dropped connection's notice, which
     * ProximityFollowUp builds in English from failure's English, or a shared id) becomes the catalog
     * message whose English it exactly is; anything else stays the text it is.
     */
    static CharSequence notice(Msg failure, CharSequence text) {
        if (text == null || text instanceof Msg) return text;
        String english = text.toString();
        if (failure != null) {
            Msg dropped = drop(failure, english);
            if (dropped != null) return dropped;
        }
        // Reasons that reach a notice as text: the shared ids (BleController.BLUETOOTH_OFF, ...), and pump()'s refused
        // write, which still passes "Send failed" as text (tests/ProximityFollowUpTest reads that line), and the PIN
        // builders' refusals, which setPin and enablePin catch as e.getMessage() (a Refused's getMessage is its Msg's English).
        for (Msg id : new Msg[]{M.bleNearbyNeeded(), M.bleBluetoothOff(), M.commonNoScooter(), M.bleNothingFound(),
                M.bleOwnerRejected(), M.bleWholeHour(), M.bleFailedSend(), M.controlsRefusalPinOwner(),
                M.controlsRefusalPinDigits(), M.controlsRefusalPinNotConfigured()}) {
            Msg shown = drop(id, english);
            if (shown != null) return shown;
        }
        return english;
    }

    /** The drop template (ProximityFollowUp.dropNotice's forms) around failure that prints shown, or null. */
    static Msg drop(Msg failure, String shown) {
        for (Msg form : new Msg[]{failure, M.bleDropRetrying(failure), M.bleDropChangeCancelled(failure),
                M.bleDropNotConfirmed(failure), M.bleDropReconnecting(failure), M.bleDropNative()})
            if (form.english().equals(shown)) return form;
        return null;
    }

    /** The English of a message or text: what message(), takeFailure() and the event log keep. */
    static String english(CharSequence text) {
        return text == null ? null : text instanceof Msg ? ((Msg) text).english() : text.toString();
    }
}
