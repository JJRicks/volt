package com.jjricks.volt;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * F1 (ScooterService's consumers of the wave-2 display accessors), pinned before the batch (G5). F1 changes
 * where ScooterService reads two kinds of English, not the English itself, so this pins what the merge base
 * showed for each:
 * - the reason a charge-schedule control is unavailable: ScooterService put Decision.reasonText()
 *   (CapabilityResolver.text) into ChargeScheduleController.Context, whose saved status and refusals keep it;
 * - a battery alert's title and text: ScooterService rendered ChargeAlertPolicy.alertTitle and alertText once,
 *   when the alert fired, and showed that String in the notice, the connection notice and the in-app dialog.
 * The pins spell the English out, under several phone locales.
 */
public final class ServiceConsumersEnglishTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("tr", "TR"),
                    Locale.forLanguageTag("ar-EG-u-nu-arab"), Locale.forLanguageTag("th-TH-u-nu-thai")}) {
                Locale.setDefault(phone);
                reasons();
                alerts();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("ServiceConsumersEnglishTest: " + checks + " checks passed (schedule control reasons and battery alert "
                + "wording, 6 phone locales)");
    }

    /** Every reason the charge-schedule context can carry, as Decision.reasonText() printed it. */
    static final Map<CapabilityResolver.Reason, String> REASONS = new LinkedHashMap<>();
    static {
        REASONS.put(CapabilityResolver.Reason.NONE, "");
        REASONS.put(CapabilityResolver.Reason.NOT_CONNECTED, "Connect first");
        REASONS.put(CapabilityResolver.Reason.STATUS_STALE, "Waiting for fresh status");
        REASONS.put(CapabilityResolver.Reason.FIELD_ABSENT, "Not reported");
        REASONS.put(CapabilityResolver.Reason.VALUE_UNKNOWN, "Unknown value · can't change safely");
        REASONS.put(CapabilityResolver.Reason.NO_CHOICES, "No choices reported");
        REASONS.put(CapabilityResolver.Reason.BUSY, "Wait for the current change");
        REASONS.put(CapabilityResolver.Reason.MOVING, "Stop the scooter first");
        REASONS.put(CapabilityResolver.Reason.NO_MOTION_REPORT, "No motion report yet");
        REASONS.put(CapabilityResolver.Reason.WARNING_ACTIVE, "Resolve scooter warning first");
        REASONS.put(CapabilityResolver.Reason.LOCKED, "Unlock first");
        REASONS.put(CapabilityResolver.Reason.OWNER_ONLY, "Owner only");
        REASONS.put(CapabilityResolver.Reason.NEEDS_BOND, "Pair this phone first");
        REASONS.put(CapabilityResolver.Reason.NEEDS_PIN, "Enable the handlebar PIN first");
        REASONS.put(CapabilityResolver.Reason.FIRMWARE_UNKNOWN, "Firmware not read yet");
        REASONS.put(CapabilityResolver.Reason.FIRMWARE_OLD, "Needs newer scooter firmware");
        REASONS.put(CapabilityResolver.Reason.FULL_PID_NEEDED, "Identify model first");
        REASONS.put(CapabilityResolver.Reason.IDENTITY_CONFLICT, "Model ID mismatch");
        REASONS.put(CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED, "Identify model first");
        REASONS.put(CapabilityResolver.Reason.MODEL_NOT_MAPPED, "Not mapped for this model");
        REASONS.put(CapabilityResolver.Reason.MODEL_EXCLUDED, "Not on this model");
        REASONS.put(CapabilityResolver.Reason.REGION_RULE, "Not offered for this region");
        REASONS.put(CapabilityResolver.Reason.PRODUCT_UNSUPPORTED, "Not supported");
        REASONS.put(CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED, "Not supported");
        REASONS.put(CapabilityResolver.Reason.DISCOVERY_ACTIVE, "Checking compatibility…");
        REASONS.put(CapabilityResolver.Reason.TEST_ACTIVE, "Test in progress");
        REASONS.put(CapabilityResolver.Reason.NOT_TESTABLE, "Not offered as a test");
        REASONS.put(CapabilityResolver.Reason.RECORDING, "Stop recording first");
        REASONS.put(CapabilityResolver.Reason.NEEDS_REAL_SCOOTER, "Needs a real scooter");
        REASONS.put(CapabilityResolver.Reason.PARKED_UNCONFIRMED, "Confirm the scooter is parked");
        REASONS.put(CapabilityResolver.Reason.NO_TEST, "No test running");
    }

    private static void reasons() {
        check(REASONS.size() == CapabilityResolver.Reason.values().length, "every Reason is pinned");
        for (CapabilityResolver.Reason reason : CapabilityResolver.Reason.values()) {
            String english = REASONS.get(reason);
            check(english != null, "pinned: " + reason);
            same(english, CapabilityResolver.text(reason), "reasonText() for " + reason);
        }
    }

    /** One alert as the base showed it: the event, the level and percent it was built with, and its English. */
    private static void alert(ChargeAlertPolicy.Event event, int level, String scooter, int percent, String title, String text) {
        same(title, ChargeAlertPolicy.alertTitle(event, level), "alert title " + event + " " + level);
        same(text, ChargeAlertPolicy.alertText(event, scooter, percent), "alert text " + event + " " + scooter + " " + percent);
    }

    private static void alerts() {
        alert(ChargeAlertPolicy.Event.CHARGE, 80, "Synthetic GT3 Max", 81, "Charged to 80%", "Synthetic GT3 Max is at 81%.");
        alert(ChargeAlertPolicy.Event.CHARGE, 5, "Scooter", 5, "Charged to 5%", "Scooter is at 5%.");
        alert(ChargeAlertPolicy.Event.CHARGE, 100, "My %1$s 100%", 100, "Charged to 100%", "My %1$s 100% is at 100%.");
        alert(ChargeAlertPolicy.Event.FULL, 100, "UT5 MAX", 100, "Fully charged", "UT5 MAX is at 100%.");
        alert(ChargeAlertPolicy.Event.LOW, 20, "Synthetic GT3 Max", 19, "Battery low", "Synthetic GT3 Max is at 19%.");
        alert(ChargeAlertPolicy.Event.LOW, 20, "", 0, "Battery low", " is at 0%.");
        alert(ChargeAlertPolicy.Event.PAUSED, 0, "Scooter", 0, "Charge alert paused",
                "Volt lost the connection to Scooter. Open Volt to keep watching this charge.");
        alert(ChargeAlertPolicy.Event.TIME_LIMIT, 0, "Scooter", 0, "Charge alert paused",
                "Volt stopped watching after 12 hours. Open Volt to keep watching this charge.");
    }

    private static void same(String expected, String actual, String what) {
        check(expected.equals(actual), what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
