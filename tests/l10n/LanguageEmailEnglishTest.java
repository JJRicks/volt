package com.jjricks.volt;

/**
 * The Language sheet's two email drafts (Report a translation error, Request a language) stay English whatever
 * language Volt shows: built with a text source that fails on any use, and with one that answers in another
 * language, they come out exactly as with the built-in English.
 */
public final class LanguageEmailEnglishTest {
    private static int checks;

    public static void main(String[] args) {
        String[] english = drafts();
        T.install(new T.Source() {
            public String string(int id) { throw new AssertionError("a language email read catalog text " + T.key(id)); }
            public String plural(int id, long quantity) { throw new AssertionError("a language email read catalog plural " + T.key(id)); }
            public String language() { throw new AssertionError("a language email asked for the language"); }
        });
        String[] guarded;
        try { guarded = drafts(); } finally { T.install(null); }
        T.install(new T.Source() {
            public String string(int id) { return "¿Texto?"; }
            public String plural(int id, long quantity) { return "¿Texto?"; }
            public String language() { return "es"; }
        });
        String[] spanish;
        try { spanish = drafts(); } finally { T.install(null); }
        for (int i = 0; i < english.length; i++) {
            check(guarded[i].equals(english[i]), "draft " + i + " is identical without the catalog");
            check(spanish[i].equals(english[i]), "draft " + i + " is identical while Volt shows another language");
            check(english[i].chars().allMatch(c -> c < 0x80 || c == 0xB7), "draft " + i + " is plain English: " + english[i]);
        }
        System.out.println("LanguageEmailEnglishTest: " + checks + " checks passed (" + english.length + " draft texts)");
    }

    private static String[] drafts() {
        return new String[]{
                IssueReport.translationSubject("es"),
                IssueReport.translationBody("1.12.0", "16", 36, "es", true, false),
                IssueReport.translationSubject("en"),
                IssueReport.translationBody("1.12.0", "16", 36, "en", false, true),
                IssueReport.languageRequestSubject(),
                IssueReport.languageRequestBody("1.12.0", "de-DE")};
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
