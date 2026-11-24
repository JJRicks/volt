package com.jjricks.volt;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fmt.unitsJoined: Battery &amp; charging's range line ("about 38 km left", HubBatterySummary.range) wraps at large text,
 * and with fmt_quantity's plain space it split the pair, "about 38 / km left" (#452, UT5 MAX at 344 dp, 1.4x). Each
 * number now keeps its unit with a no-break space (U+00A0), in either order a language's template puts them; every
 * other space still breaks, a template that already joins them is left alone, and the ranges stay where they were.
 */
public final class UnitsJoinedTest {
    private static int checks;
    private static final char NBSP = ' ', NNBSP = ' ';

    private UnitsJoinedTest() {}

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            english();
            T.install(german());
            german("German");
            T.install(language("xx", T.FMT_QUANTITY, "%2$s %1$s"));
            check(Fmt.unitsJoined(km(38)).toString().equals("km" + NBSP + "38"), "A unit-first template joins too: " + Fmt.unitsJoined(km(38)));
            T.install(language("fr", T.FMT_QUANTITY, "%1$s" + NNBSP + "%2$s"));
            check(Fmt.unitsJoined(km(38)).toString().equals("38" + NNBSP + "km"), "A template that already joins them is unchanged");
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("UnitsJoinedTest: " + checks + " checks passed");
    }

    private static void english() {
        Formatted joined = Fmt.unitsJoined(km(38));
        check(joined.toString().equals("38" + NBSP + "km"), "38 and km are joined: " + visible(joined));
        check(joined.numbers() == 1 && joined.numberStart(0) == 0 && joined.numberEnd(0) == 2 && joined.unitStart(0) == 3 && joined.unitEnd(0) == 5,
                "The number and unit ranges stay where they were");
        String line = T.dashboardRangeLeft(T.dashboardAbout(joined));
        check(line.equals("about 38" + NBSP + "km left"), "The drawn line breaks only around the pair: " + visible(line));
        Formatted dual = Fmt.unitsJoined(Fmt.dual(mi(24), km(38)));
        check(dual.toString().equals("24" + NBSP + "mi / 38" + NBSP + "km"), "Both pairs of a dual range join; the slash can break: " + visible(dual));
        check(dual.numbers() == 2 && dual.units() == 2, "Both numbers and both units are still marked");
        Formatted plain = Fmt.unitsJoined(Fmt.decimalNumber(Locale.US, 1234.5, 1));
        check(plain.toString().equals(Fmt.decimalNumber(Locale.US, 1234.5, 1).toString()), "A number with no unit is unchanged");
        Formatted unit = Fmt.unitsJoined(Fmt.unit(UnitCode.KM));
        check(unit.toString().equals("km"), "A unit alone is unchanged");
    }

    private static void german(String where) {
        String line = T.dashboardRangeLeft(T.dashboardAbout(Fmt.unitsJoined(km(38))));
        check(line.equals("Noch ca. 38" + NBSP + "km"), where + ": the pair joins inside the language's own line: " + visible(line));
    }

    private static Formatted km(double value) { return Fmt.quantity(Fmt.decimalNumber(Locale.US, value, 0), Fmt.unit(UnitCode.KM)); }
    private static Formatted mi(double value) { return Fmt.quantity(Fmt.decimalNumber(Locale.US, value, 0), Fmt.unit(UnitCode.MI)); }

    /** German's own words for the line (res/values-de): "Noch %1$s", "ca. %1$s", and its fmt_quantity "%1$s %2$s". */
    private static T.Source german() {
        Map<Integer, String> text = new HashMap<>();
        text.put(T.FMT_QUANTITY, "%1$s %2$s");
        text.put(T.DASHBOARD_ABOUT, "ca. %1$s");
        text.put(T.DASHBOARD_RANGE_LEFT, "Noch %1$s");
        return source("de", text);
    }

    private static T.Source language(String tag, int id, String value) {
        Map<Integer, String> text = new HashMap<>();
        text.put(id, value);
        return source(tag, text);
    }

    private static T.Source source(String tag, Map<Integer, String> text) {
        return new T.Source() {
            public String string(int id) { return text.get(id); }
            public String plural(int id, long quantity) { return null; }
            public String language() { return tag; }
        };
    }

    private static String visible(CharSequence text) { return text.toString().replace(NBSP, '·'); }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
