package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F2: the controller and compatibility consumers show the wave-2 display text, and keep the English where it is
 * data. Under a key-echo source (a stand-in for another language):
 *   - DisplayUnits.featureValue follows the source for a value that is not a speed (it shows valueText(v));
 *   - a guided test's review and the "still running" review show the source, and Review.text keeps the English
 *     for the event log and notices' log line;
 *   - a note built with variant.text(v) keeps the note built with variant.label(v) as its English (the journal and
 *     the report) and shows the shown label;
 *   - the Fields group's presence words are F2's keys, with the English the panel showed before.
 */
public final class ControllerConsumersTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            english();
            T.install(ECHO);
            echoed();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("ControllerConsumersTest: " + checks + " checks passed (F2 consumers show the display text and keep the English ids)");
    }

    private static void english() {
        equal("Absent", T.compatF2FieldAbsent(), "the Fields absent word");
        equal("Present", T.compatF2FieldPresent(), "the Fields present word");
        GuidedTest.Review running = new GuidedTest.Review(GuidedTest.ReviewState.WAITING, M.compatF2TestStillRunning(), null, false);
        equal("The test is still running.", running.text, "the running review's English");
        equal("The test is still running.", running.message.render(), "the running review shown in English");
    }

    private static void echoed() throws Exception {
        int followed = 0;
        for (ScooterCommands.Feature f : features()) {
            if (UnitCode.KMH.matches(f.unit)) continue;
            for (int v : f.optionValues) {
                String shown = DisplayUnits.featureValue(f, v, "km");
                equal(f.valueText(v).render(), shown, "featureValue(" + f.key + ", " + v + ") is valueText under another source");
                if (!shown.equals(f.valueLabel(v))) followed++;
            }
        }
        check(followed > 0, "featureValue follows the source for catalogued value labels (" + followed + ")");

        GuidedTest.Review running = new GuidedTest.Review(GuidedTest.ReviewState.WAITING, M.compatF2TestStillRunning(), null, false);
        equal("The test is still running.", running.text, "the running review keeps its English for the log");
        equal("⟦compat_f2_test_still_running⟧", running.message.render(), "the running review is shown from the source");
        equal("⟦compat_f2_field_absent⟧", T.compatF2FieldAbsent(), "the Fields absent word follows the source");

        // A note built the way GuidedTest builds it now: the value label inside is the display text. The note's own
        // text keeps its placeholder here, so the label filled in is visible.
        T.install(new T.Source() {
            @Override public String string(int id) { return T.key(id).equals("compat_note_now_shows") ? "→%1$s←" : ECHO.string(id); }
            @Override public String plural(int id, long quantity) { return ECHO.plural(id, quantity); }
            @Override public String language() { return "es"; }
        });
        int labelled = 0;
        for (FeatureVariant variant : ControlsEnglishTest.variants().values())
            for (int v : variant.values) {
                Msg note = M.compatNoteNowShows(variant.text(v));
                equal(M.compatNoteNowShows(variant.label(v)).english(), note.english(), variant.id + " " + v + ": the note keeps the English label for the journal");
                if (!variant.text(v).render().equals(variant.label(v))) {
                    equal("→" + variant.text(v).render() + "←", note.render(), variant.id + " " + v + ": the shown note carries the shown label");
                    labelled++;
                }
            }
        check(labelled > 0, "some variant labels follow the source inside a note (" + labelled + ")");
    }

    private static List<ScooterCommands.Feature> features() {
        List<ScooterCommands.Feature> out = new ArrayList<>(ScooterCommands.catalog());
        out.addAll(ScooterCommands.oemOnly());
        return out;
    }

    private static void equal(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
        checks++;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
