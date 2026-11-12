package com.jjricks.volt;

/** The Report an issue draft: a prompt for the owner, then only versions, phone model and the scooter's model name. */
public final class IssueReportTest {
    private static int assertions;
    public static void main(String[] args) {
        check(IssueReport.subject("1.5.1").equals("Volt issue report · Volt 1.5.1"), "Subject names the Volt version");
        check(IssueReport.chargeRecordingsSubject("1.5.1", "UT5 MAX").equals("Volt charge recordings · Volt 1.5.1 · UT5 MAX")
                && IssueReport.chargeRecordingsSubject("1.5.1", "").equals("Volt charge recordings · Volt 1.5.1"), "Email to Volt's subject names the version and model");
        String ut5 = IssueReport.body("1.5.1", "16", 36, "Google Pixel 9 ", "UT5 MAX", false);
        check(ut5.equals("What happened, and what did you expect instead?\n\n\n\nVolt 1.5.1 · Android 16 (API 36) · Google Pixel 9\nScooter: UT5 MAX\n"), "Body: " + ut5);
        check(IssueReport.body("1.5.1", "16", 36, "Google Pixel 9", "", false).endsWith("· Google Pixel 9\n")
                && !IssueReport.body("1.5.1", "16", 36, "Google Pixel 9", null, false).contains("Scooter"), "No scooter line without a known model");
        check(IssueReport.body("1.5.1", "16", 36, "x", "Model 39991", true).endsWith("Scooter: Model 39991 (simulated)\n"), "A simulated scooter says so");
        // The Language sheet's drafts: Report a translation error and Request a language.
        check(IssueReport.translationSubject("es").equals("Volt translation error (es)"), "Translation subject names the language");
        check(IssueReport.translationSubject("en").equals("Volt translation error (en)"), "System default names the language Volt shows");
        check(IssueReport.translationSubject("").equals("Volt translation error (unknown)") && IssueReport.translationSubject(null).equals("Volt translation error (unknown)"), "No language reads unknown");
        String es = IssueReport.translationBody("1.12.0", "16", 36, "es", true, false);
        check(es.equals("Volt 1.12.0 · Android 16 (API 36)\nApp language: es, beta\n\nWhich screen?\n\n\nWhat does it say now?\n\n\nWhat should it say?\n\n"), "Translation body for es: " + es);
        String system = IssueReport.translationBody("1.12.0", "13", 33, "en", false, true);
        check(system.equals("Volt 1.12.0 · Android 13 (API 33)\nApp language: en (System default)\n\nWhich screen?\n\n\nWhat does it say now?\n\n\nWhat should it say?\n\n"), "Translation body for System default: " + system);
        check(IssueReport.translationBody("1.12.0", "16", 36, "es", true, true).contains("\nApp language: es, beta (System default)\n"), "A beta language Volt follows from the phone says both");
        check(IssueReport.languageRequestSubject().equals("Volt language request"), "Request subject");
        String request = IssueReport.languageRequestBody("1.12.0", "de-DE");
        check(request.equals("Volt 1.12.0\nPhone language: de-DE\n\nWhich language would you like Volt in?\n\n\nCould you help check a translation into it?\n\n"), "Request body: " + request);
        check(IssueReport.languageRequestBody("1.12.0", "").contains("Phone language: unknown\n"), "No phone language reads unknown");
        for (String text : new String[]{es, system, request})
            check(!text.contains("Google") && !text.contains("Scooter") && !text.contains("diagnostics"), "Only the listed fields: " + text);
        System.out.println("IssueReport tests passed: " + assertions + " assertions");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
