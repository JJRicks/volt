package com.jjricks.volt;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * F3's text logic, built from numbers and ids instead of read back from shown text:
 *   - DisplayUnits.sliderEnds prints, in English, exactly what MainActivity's shared-unit trim printed
 *     (TextLogicEnglishTest.SLIDER_ENDS and its baseEnds), for every slider control and units setting, and in a
 *     language that words the units another way the low end is still the bare number (no unit is cut off it);
 *   - DisplayUnits.unbroken writes the same bytes CompatibilityPanel.unbroken wrote in English, and joins a
 *     language's own km/h wording;
 *   - StatsLabels.RAW_MARK is the marker StatsScreen's row name used to cut off the shown name.
 * The phone is set to en-US, tr-TR, de-DE and ar-EG in turn.
 */
public final class TextLogicTest {
    private static int checks;
    private static final String[] UNITS = {"mi", "km", "dual", "x"};
    private static final char WJ = (char) 0x2060;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, new Locale("tr", "TR"), Locale.GERMANY, new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                T.install(null);
                englishSliderEnds(phone);
                englishUnbroken(phone);
                equal(" (raw)", StatsLabels.RAW_MARK, phone + " raw marker");
                translated(phone);
            }
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("TextLogicTest: " + checks + " checks passed (F3's slider ends and word-joined unit, under 4 phone locales)");
    }

    private static void englishSliderEnds(Locale phone) {
        int seen = 0;
        for (ScooterCommands.Feature f : TextLogicEnglishTest.sliders()) {
            for (String units : UNITS) {
                String[] ends = DisplayUnits.sliderEnds(f, units), base = TextLogicEnglishTest.baseEnds(f, units);
                equal(base[0], ends[0], phone + " " + f.key + " " + units + " low end as the base trimmed it");
                equal(base[1], ends[1], phone + " " + f.key + " " + units + " high end as the base showed it");
                String[] pinned = null;
                for (String[] row : TextLogicEnglishTest.SLIDER_ENDS) if (row[0].equals(f.key) && row[1].equals(units)) pinned = row;
                check(pinned != null, phone + " " + f.key + " " + units + " is pinned");
                equal(pinned[2], ends[0], phone + " " + f.key + " " + units + " pinned low end");
                equal(pinned[3], ends[1], phone + " " + f.key + " " + units + " pinned high end");
                seen++;
            }
        }
        equal(String.valueOf(TextLogicEnglishTest.SLIDER_ENDS.length), String.valueOf(seen), phone + " slider ends");
    }

    private static void englishUnbroken(Locale phone) {
        for (String text : new String[]{T.compatKeepValue("25 km/h"), T.compatRestoreTo("15.5 mph"), T.compatSetValue("5 km/h / 3.1 mph"),
                "km/h km/h", "", "kmh", "km/hr"}) {
            equal(text.replace("km/h", "km/" + WJ + "h"), DisplayUnits.unbroken(text), phone + " unbroken \"" + text + "\" as the base joined it");
        }
    }

    /** A language that writes the units "u-kmh" and "u-mph", with the quantity first ("{unit} {number}"). */
    private static void translated(Locale phone) {
        Map<Integer, String> text = new HashMap<>();
        text.put(T.UNITS_KMH, "км/ч");
        text.put(T.UNITS_MPH, "миль/ч");
        text.put(T.FMT_QUANTITY, "%2$s %1$s");
        T.install(new T.Source() {
            public String string(int id) { return text.get(id); }
            public String plural(int id, long quantity) { return null; }
            public String language() { return "ru"; }
        });
        try {
            equal("Keep 25 км/" + WJ + "ч", DisplayUnits.unbroken("Keep 25 км/ч"), phone + " a language's own km/h is joined");
            equal("Keep 25 km/h", DisplayUnits.unbroken("Keep 25 km/h"), phone + " the English spelling is not that language's unit");
            ScooterCommands.Feature start = ScooterCommands.find("start_speed");
            String[] km = DisplayUnits.sliderEnds(start, "km"), mi = DisplayUnits.sliderEnds(start, "mi"), dual = DisplayUnits.sliderEnds(start, "dual");
            equal("0", km[0], phone + " km low end is the bare number with the unit first");
            equal("км/ч 5", km[1], phone + " km high end names the unit its way");
            equal("0.0", mi[0], phone + " mph low end is the bare number with the unit first");
            equal("миль/ч 3.1", mi[1], phone + " mph high end names the unit its way");
            check(dual[0].startsWith("миль/ч 0.0") && dual[0].endsWith("0") && !dual[0].contains("км/ч"), phone + " dual low end names mph once and km/h not at all: " + dual[0]);
        } finally {
            T.install(null);
        }
    }

    static void equal(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
        checks++;
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
