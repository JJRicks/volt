package com.jjricks.volt;

/**
 * G5 characterization for B17 (first-run setup, OnboardingScreen), committed before the batch changes
 * anything.
 *
 * OnboardingScreen needs Android, so a plain JVM test cannot draw it; G2 (LedgerTest) proves its new
 * templates and choices render the original concatenations, and G7's first-connection, launch,
 * owner-model and model-home steps census its screens. What this pins is the English the setup screens
 * show that comes from pure code, so that switching those reads to their display accessors keeps it:
 *   - the toast after a refused typed identity: each refusal's getMessage() (what the screen toasted)
 *     against SetupException.shown(...) (what it toasts after the batch), for the parser's refusals,
 *     ScooterProfiles' own SetupExceptions and a plain IllegalArgumentException;
 *   - the connection status line: for each shared connection id BleController's message() can hold,
 *     the English id against the notice it stores (displayMessage()), and that the nothing-found id
 *     still starts with "No scooter found";
 *   - the owner and shared roles the parser reports, which pick "owner role" and "shared role".
 */
public final class OnboardingEnglishTest {
    private static int checks;

    public static void main(String[] args) {
        refusalToasts();
        connectionStatus();
        roles();
        System.out.println("OnboardingEnglishTest: " + checks + " checks passed (refusal toasts, connection status, roles)");
    }

    private static void refusalToasts() {
        String[] inputs = {"", "identity: zz", "hello", "identity: 010203040506\nrole: 2",
                "identity: 010203040506\nidentity: 0A0B0C0D0E0F"};
        String[] english = {"Setup file is empty or too large.", "Need 12 hex characters", "No owner identity in file",
                "Unsupported owner role", "This file contains conflicting owner identities or roles. "
                + "Capture one NAVEE account connection or import one private setup backup."};
        for (int i = 0; i < inputs.length; i++) {
            try {
                OwnerIdentityParser.parse(inputs[i]);
                throw new AssertionError("parser accepted input " + i);
            } catch (IllegalArgumentException refused) {
                equal(english[i], refused.getMessage(), "parser refusal " + i);
                equal(english[i], String.valueOf(SetupException.shown(refused)), "toast for parser refusal " + i);
            }
        }
        SetupException typed = new SetupException(M.ownerSetupEnterIdentity());
        equal(typed.getMessage(), String.valueOf(SetupException.shown(typed)), "toast for a refused typed identity");
        SetupException imported = new SetupException(M.ownerSetupImportFailed());
        equal(imported.getMessage(), String.valueOf(SetupException.shown(imported)), "toast for a refused import");
        IllegalArgumentException plain = new IllegalArgumentException("Plain refusal");
        equal("Plain refusal", String.valueOf(SetupException.shown(plain)), "a plain refusal is toasted as it is");
    }

    private static void connectionStatus() {
        String[] ids = {"No scooter found", "Bluetooth off", "Nearby devices permission needed", "No scooter set up",
                "Owner identity rejected", "Connection failed", "Timed out"};
        for (String id : ids) {
            CharSequence shown = ConnectionText.notice(null, id);
            equal(id, ConnectionText.english(shown), "message() of " + id);
            equal(id, String.valueOf(shown), "displayMessage() of " + id);
        }
        check(ConnectionText.english(ConnectionText.notice(null, "No scooter found")).startsWith("No scooter found"),
                "the nothing-found id starts with No scooter found");
        check(ConnectionText.notice(null, null) == null, "no message stays null");
    }

    private static void roles() {
        equal(0, OwnerIdentityParser.parse("identity: 010203040506").role, "an owner file is the owner role");
        equal(1, OwnerIdentityParser.parse("identity: 010203040506\nrole: 1").role, "role 1 is the shared role");
        equal("010203040506", OwnerIdentityParser.parse("010203040506").identity, "a typed 12-character code");
    }

    private static void equal(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
