package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for F7 (hard-coded text the Spanish beta still showed in English), committed
 * before the batch changes anything.
 *
 * StatsScreen and MoreScreen need Android, so a plain JVM test cannot build them; G2 (LedgerTest)
 * proves each new template renders the original concatenation. What this pins, copied from the
 * merge base c79f39f:
 *   - the Live Stats tab labels, which are StatsScreen's section ids shown as they are;
 *   - the Experimental controls card: the model phrase (labModel), both intro sentences, the
 *     nothing-to-try sentence for an OEM group and the Try sheet's heading.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR.
 */
public final class HardcodedTextEnglishTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                cases(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("HardcodedTextEnglishTest: " + checks + " checks passed (Live Stats tabs and the "
                + "Experimental controls card's model sentences under 5 phone locales)");
    }

    private static void cases(Locale phone) {
        equal("Overview|Readings|Charts|Diagnostics", String.join("|", Base.SECTIONS), phone, "tabs");
        equal("Overview|Charts|Diagnostics", String.join("|", Base.WIDE_SECTIONS), phone, "wide tabs");
        equal("the UT5 MAX", Base.labModel("UT5 MAX", "UT5 MAX"), phone, "named model");
        equal("the N65i", Base.labModel("N65i", ""), phone, "named model, no line");
        equal("Model 24431", Base.labModel("", "Model 24431"), phone, "model line");
        equal("your model", Base.labModel("", ""), phone, "no model");
        equal("The official app doesn't offer these for the N65i. Try one while parked; Volt reads it back.",
                Base.labIntro(true, true, "the N65i"), phone, "untested intro");
        equal("The official app doesn't offer these for your model. Try one while parked; Volt reads it back.",
                Base.labIntro(true, false, "your model"), phone, "untested intro, no rows");
        equal("The official app offers these for Model 24431, but Volt hasn't tested them there. Try one while "
                + "parked; Volt reads it back.", Base.labIntro(false, true, "Model 24431"), phone, "verify intro");
        equal("", Base.labIntro(false, false, "your model"), phone, "no intro (labEmpty shows instead)");
        equal("Nothing to try on the N65i: the official app already offers the controls Volt could try. They're "
                + "in Scooter settings.", Base.labNothingOem("the N65i"), phone, "nothing to try");
        equal("Unverified on the UT5 MAX", Base.sheetHeading(true, "the UT5 MAX"), phone, "unverified heading");
        equal("Untested on your model", Base.sheetHeading(false, "your model"), phone, "untested heading");
    }

    /** Copies of the base's expressions at c79f39f. */
    static final class Base {
        private Base() {}
        /** StatsScreen.SECTIONS and WIDE_SECTIONS, shown as the tab labels. */
        static final String[] SECTIONS = {"Overview", "Readings", "Charts", "Diagnostics"};
        static final String[] WIDE_SECTIONS = {"Overview", "Charts", "Diagnostics"};
        /** MoreScreen.labModel, with ModelRegistry.modelName and modelLine passed in. */
        static String labModel(String name, String line) {
            if (!name.isEmpty()) return "the " + name;
            return line.isEmpty() ? "your model" : line;
        }
        /** MoreScreen.refreshLab's labIntro text; "" stands for labEmpty, which is already catalog text. */
        static String labIntro(boolean experimental, boolean rows, String model) {
            return experimental ? "The official app doesn't offer these for " + model + ". Try one while parked; Volt reads it back."
                    : rows ? "The official app offers these for " + model + ", but Volt hasn't tested them there. Try one while parked; Volt reads it back." : "";
        }
        /** MoreScreen.labEmpty for ModelRegistry.Layer.OEM_GROUP. */
        static String labNothingOem(String model) {
            return "Nothing to try on " + model + ": the official app already offers the controls Volt could try. They're in Scooter settings.";
        }
        /** The Try sheet's caution heading in MoreScreen. */
        static String sheetHeading(boolean verify, String model) {
            return (verify ? "Unverified on " : "Untested on ") + model;
        }
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + " on a " + phone + " phone: expected <" + expected + "> but was <" + actual + ">");
        }
        checks++;
    }
}
