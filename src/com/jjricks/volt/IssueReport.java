package com.jjricks.volt;

/**
 * More › Report an issue: an email draft to Volt's report address for the owner to fill in. It
 * carries only what helps reproduce a problem: Volt's version, the Android version and phone
 * model, and the selected scooter's model name when Volt knows it. Never a serial, MAC, owner
 * key, PIN or name.
 *
 * The Language sheet's two drafts live here too, so they stay English whatever language Volt shows
 * (CatalogUsageTest keeps this file away from the catalog). Report a translation error carries
 * Volt's version, the Android version and the language Volt shows; Request a language carries
 * Volt's version and the phone's language. Neither carries anything else.
 */
final class IssueReport {
    private IssueReport() {}

    static String subject(String appVersion) { return "Volt issue report · Volt " + appVersion; }

    /**
     * Battery & charging › Charge history & recordings › Email to Volt: a fixed English subject for the developer's
     * inbox, naming Volt's version and the model the recordings are from (scooterModel "" when it isn't known).
     */
    static String chargeRecordingsSubject(String appVersion, String scooterModel) {
        return "Volt charge recordings · Volt " + appVersion + (scooterModel == null || scooterModel.isEmpty() ? "" : " · " + scooterModel);
    }

    /** scooterModel is ModelRegistry.modelLine for the selected scooter ("" when none is known). */
    static String body(String appVersion, String androidRelease, int sdk, String phone, String scooterModel, boolean simulated) {
        StringBuilder out = new StringBuilder("What happened, and what did you expect instead?\n\n\n\n");
        out.append("Volt ").append(appVersion).append(" · Android ").append(androidRelease).append(" (API ").append(sdk).append(") · ").append(phone.trim());
        if (scooterModel != null && !scooterModel.isEmpty()) out.append("\nScooter: ").append(scooterModel).append(simulated ? " (simulated)" : "");
        return out.append('\n').toString();
    }

    /** Report a translation error: language is the tag Volt shows ("es", "en"), or "" when unknown. */
    static String translationSubject(String language) {
        return "Volt translation error (" + tagOrUnknown(language) + ")";
    }

    /**
     * Version lines, then three prompts. beta: the shown language is a beta translation. systemDefault: the
     * Language choice is System default, so Volt follows the phone.
     */
    static String translationBody(String appVersion, String androidRelease, int sdk, String language, boolean beta, boolean systemDefault) {
        StringBuilder out = new StringBuilder();
        out.append("Volt ").append(appVersion).append(" · Android ").append(androidRelease).append(" (API ").append(sdk).append(")\n");
        out.append("App language: ").append(tagOrUnknown(language));
        if (beta) out.append(", beta");
        if (systemDefault) out.append(" (System default)");
        out.append("\n\nWhich screen?\n\n\nWhat does it say now?\n\n\nWhat should it say?\n\n");
        return out.toString();
    }

    static String languageRequestSubject() { return "Volt language request"; }

    /** Request a language: Volt's version and the phone's first language (a BCP 47 tag), then two prompts. */
    static String languageRequestBody(String appVersion, String phoneLanguage) {
        return "Volt " + appVersion + "\nPhone language: " + tagOrUnknown(phoneLanguage)
                + "\n\nWhich language would you like Volt in?\n\n\nCould you help check a translation into it?\n\n";
    }

    private static String tagOrUnknown(String tag) {
        return tag == null || tag.trim().isEmpty() ? "unknown" : tag.trim();
    }
}
