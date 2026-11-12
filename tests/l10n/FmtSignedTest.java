package com.jjricks.volt;

import java.util.Locale;
import java.util.Random;

/**
 * Fmt.signedDecimal, the signed number the ride map's route-point popup prints ("≈+2.5% grade",
 * "Elevation -12 ft", "+0.25 m/s² GPS accel").
 *
 * While the resolved language is English (also a tag that will never ship and a pseudo-locale), it
 * prints exactly what String.format(legacy, "%+.Nf", value) printed, under four phone locales, at signs,
 * halves, negative zero, NaN, infinities and random values, with decimals clamped to 0..6 as
 * Fmt.decimal clamps them. With a shipped language it prints that language's format locale: the same
 * sign, the language's decimal separator and Latin digits.
 */
public final class FmtSignedTest {
    private static int checks;

    private FmtSignedTest() {}

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[] {Locale.US, Locale.UK, new Locale("en", "IN"), Locale.GERMANY}) {
                Locale.setDefault(phone);
                for (String language : new String[] {null, "en", "xx", "en-XA"}) {
                    T.install(language == null ? null : source(language));
                    legacy(phone.toLanguageTag() + " with " + (language == null ? "English" : "a source in " + language));
                }
            }
            shipped();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("FmtSignedTest: " + checks + " checks passed (signed decimals as %+.Nf while English, display locale when shipped)");
    }

    private static void legacy(String where) {
        check(Fmt.legacy(), where + ": legacy formats");
        double[] edges = {0, -0d, .04, -.04, .05, -.05, .25, -.25, .5, -.5, 1.5, 2.5, -2.5, 1.005, 12.345, -15.44, 99.95,
                1234.25, -1234567.891, 1e-7, -1e-7, 1e21, -1e21, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int decimals = -1; decimals <= 7; decimals++)
            for (double value : edges) parity(value, decimals, where);
        Random random = new Random(20261112L);
        for (int i = 0; i < 300; i++) {
            double value = (random.nextDouble() - .5) * Math.pow(10, random.nextInt(6));
            parity(value, random.nextInt(4), where);
            parity(Math.round(value * 10) / 10d + .05, 1, where);
            parity(Math.round(value * 100) / 100d + .005, 2, where);
        }
        // The popup's three uses, as their base String.format calls printed them.
        equal("+2.5", Fmt.signedDecimal(Locale.US, 2.5, 1), where, "grade");
        equal("-0.0", Fmt.signedDecimal(Locale.US, -.04, 1), where, "a small negative grade keeps its minus");
        equal("+0.25", Fmt.signedDecimal(Locale.US, .25, 2), where, "acceleration");
        equal("-12", Fmt.signedDecimal(Locale.US, -12.4, 0), where, "elevation");
        equal("+1235", Fmt.signedDecimal(Locale.US, 1234.5, 0), where, "half up, no grouping");
    }

    private static void parity(double value, int decimals, String where) {
        int precision = Math.max(0, Math.min(6, decimals));
        for (Locale legacy : new Locale[] {Locale.US, Locale.ROOT, Locale.getDefault()}) {
            String expected = String.format(legacy, "%+." + precision + "f", value);
            String actual = Fmt.signedDecimal(legacy, value, decimals);
            check(expected.equals(actual), where + ": " + value + " with " + decimals + " decimals in " + legacy.toLanguageTag()
                    + " is " + expected + ", not " + actual);
        }
    }

    /**
     * The languages whose display locale writes a decimal comma, named so the literal "+2,5" and "-0,25"
     * checks below run only for them. The locale comparison covers every shipped language, so a shipped
     * language that writes a decimal point needs no entry here, and a name here that is not shipped is
     * skipped like any other.
     */
    private static final java.util.Set<String> DECIMAL_COMMA = new java.util.HashSet<>(java.util.Arrays.asList(
            "de", "fr", "it", "nl", "pl", "pt-BR", "pt-PT", "sv", "tr", "es"));

    /**
     * Every shipped language other than English: the display locale's separator, the same sign, Latin
     * digits. The named decimal-comma languages (all of today's: de, fr, it, nl, pl, pt-BR, pt-PT, sv,
     * tr, es) also print the literal comma forms.
     */
    private static void shipped() {
        Locale.setDefault(Locale.US);
        int languages = 0, commaLanguages = 0;
        for (int i = 0; i < AppLanguages.size(); i++) {
            String language = AppLanguages.tag(i);
            if (AppLanguages.ENGLISH.equals(language) || !AppLanguages.shipped(i)) continue;
            languages++;
            T.install(source(language));
            String where = "a source in " + language;
            check(!Fmt.legacy(), where + ": not legacy");
            Locale display = Fmt.displayLocale();
            for (double value : new double[] {2.5, -2.5, .25, -.04, 0, 1234.5}) {
                for (int decimals = 0; decimals <= 2; decimals++) {
                    String actual = Fmt.signedDecimal(Locale.US, value, decimals);
                    equal(String.format(display, "%+." + decimals + "f", value), actual, where, value + " with " + decimals + " decimals");
                    for (char ch : actual.toCharArray()) check(!Character.isDigit(ch) || (ch >= '0' && ch <= '9'), where + ": " + actual + " has Latin digits");
                }
            }
            if (DECIMAL_COMMA.contains(language)) {
                commaLanguages++;
                equal("+2,5", Fmt.signedDecimal(Locale.US, 2.5, 1), where, "grade with a decimal comma");
                equal("-0,25", Fmt.signedDecimal(Locale.US, -.25, 2), where, "acceleration with a decimal comma");
            }
            equal("+12", Fmt.signedDecimal(Locale.US, 12, 0), where, "a whole number has no separator");
        }
        check(languages > 0, "at least one shipped language other than English");
        check(commaLanguages > 0, "at least one shipped decimal-comma language printed the literal comma forms");
    }

    private static T.Source source(String language) {
        return new T.Source() {
            public String string(int id) { return null; }
            public String plural(int id, long quantity) { return null; }
            public String language() { return language; }
        };
    }

    private static void equal(String expected, CharSequence actual, String where, String what) {
        check(expected.equals(actual.toString()), where + ": " + what + ": expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
