package com.jjricks.volt;

import java.util.Locale;

/**
 * F7's own suite: the new catalog text against the base English HardcodedTextEnglishTest.Base pinned
 * before the batch (G5), in English and under a key-echo text source.
 *
 * G2 (LedgerTest) proves each ledger row's template; this adds what the rows cannot carry:
 *  - the Live Stats tab labels, whose section ids stay in StatsScreen (no ledger row moves them), in
 *    SECTIONS order, as StatsScreen.SECTION_LABELS maps them;
 *  - labModel's three outcomes inside each sentence that takes the model phrase;
 *  - both branches of the Try sheet heading;
 *  - each key follows the installed text source.
 */
public final class HardcodedTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("en", "IN"), new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                english();
            }
            T.install(ECHO);
            follows();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HardcodedTextTest: " + checks + " checks passed (Live Stats tabs and the lab card's "
                + "model sentences against the base English, and the key-echo source)");
    }

    private static void english() {
        int[] tabs = {T.STATS_TAB_OVERVIEW, T.STATS_TAB_READINGS, T.STATS_TAB_CHARTS, T.STATS_TAB_DIAGNOSTICS};
        String[] base = HardcodedTextEnglishTest.Base.SECTIONS;
        check(tabs.length == base.length, "one tab label per section");
        for (int i = 0; i < tabs.length; i++) equal(base[i], T.render(tabs[i]), "tab " + base[i]);

        String[][] models = {{"UT5 MAX", "UT5 MAX"}, {"N65i", ""}, {"", "Model 24431"}, {"", ""}};
        for (String[] m : models) {
            String model = HardcodedTextEnglishTest.Base.labModel(m[0], m[1]);
            String shown = !m[0].isEmpty() ? T.moreLabModelNamed(m[0]) : m[1].isEmpty() ? T.moreLabModelYours() : m[1];
            equal(model, shown, "model phrase " + model);
            equal(HardcodedTextEnglishTest.Base.labIntro(true, true, model), T.moreLabIntroUntested(shown), "untested intro " + model);
            equal(HardcodedTextEnglishTest.Base.labIntro(false, true, model), T.moreLabIntroVerify(shown), "verify intro " + model);
            equal(HardcodedTextEnglishTest.Base.labNothingOem(model), T.moreLabEmptyOem(shown), "nothing to try " + model);
            equal(HardcodedTextEnglishTest.Base.sheetHeading(true, model), T.moreLabSheetUnverified(shown), "unverified " + model);
            equal(HardcodedTextEnglishTest.Base.sheetHeading(false, model), T.moreLabSheetUntested(shown), "untested " + model);
        }
    }

    private static void follows() {
        String[][] shown = {
                {T.render(T.STATS_TAB_OVERVIEW), "stats_tab_overview"}, {T.render(T.STATS_TAB_READINGS), "stats_tab_readings"},
                {T.render(T.STATS_TAB_CHARTS), "stats_tab_charts"}, {T.render(T.STATS_TAB_DIAGNOSTICS), "stats_tab_diagnostics"},
                {T.moreLabModelNamed("x"), "more_lab_model_named"}, {T.moreLabModelYours(), "more_lab_model_yours"},
                {T.moreLabIntroUntested("x"), "more_lab_intro_untested"}, {T.moreLabIntroVerify("x"), "more_lab_intro_verify"},
                {T.moreLabEmptyOem("x"), "more_lab_empty_oem"},
                {T.moreLabSheetUnverified("x"), "more_lab_sheet_unverified"}, {T.moreLabSheetUntested("x"), "more_lab_sheet_untested"},
        };
        for (String[] s : shown) check(s[0].startsWith("⟦" + s[1] + "⟧"), s[1] + " follows the source: " + s[0]);
    }

    private static void equal(String want, CharSequence got, String what) {
        check(want.equals(String.valueOf(got)), what + ": want \"" + want + "\", got \"" + got + "\" (" + Locale.getDefault() + ")");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
