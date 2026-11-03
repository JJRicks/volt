package com.jjricks.volt;

import java.util.Locale;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * G10, format legacy parity (plan sections 2 and 4): while the resolved language is English, Fmt
 * returns each call site's own legacy locale and pattern, so numbers, units and dates format exactly
 * as before localization. Every check runs with the phone set to en-US, en-GB, en-IN and de-DE (a
 * German phone before German ships), and with text sources whose language is English, unshipped
 * ("de", "ar"), a pseudo-locale ("en-XA") or a mangled pseudo l10n_tag.
 *
 * Covers: Fmt.locale and datePattern, Fmt.decimal against String.format(Locale.US, "%.Nf") over
 * random and edge values (negative zero, NaN, infinities, halves, huge and tiny values), Fmt.integer
 * against string concatenation, the unit symbols against the unit text used before, and every
 * structured (Formatted) API against the String method beside it, with its number and unit ranges.
 * A key-echo source with a reordered template checks that ranges follow positions, not assumptions.
 */
public final class FmtLegacyTest {
    private static int checks;
    private static final double KM_PER_MILE = 1.609344;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.UK, new Locale("en", "IN"), Locale.GERMANY}) {
                Locale.setDefault(phone);
                for (String language : new String[]{null, "en", "de", "ar", "en-XA", "[ëñ]", "»en«"}) {
                    T.install(language == null ? null : source(language, false));
                    String where = phone.toLanguageTag() + " with " + (language == null ? "English" : "a source in " + language);
                    locales(phone, where);
                    numbers(where);
                    units(where);
                    structured(where);
                }
            }
            reordered();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("FmtLegacyTest: " + checks + " checks passed (legacy formats under 4 phone locales and 7 resolved languages)");
    }

    private static void locales(Locale phone, String where) {
        check(Fmt.legacy(), where + ": legacy formats");
        check(Fmt.locale(Locale.US) == Locale.US, where + ": a Locale.US call site keeps Locale.US");
        check(Fmt.locale(Locale.ROOT) == Locale.ROOT, where + ": a Locale.ROOT call site keeps Locale.ROOT");
        check(Fmt.locale(Locale.ENGLISH) == Locale.ENGLISH, where + ": a Locale.ENGLISH call site keeps Locale.ENGLISH");
        check(Fmt.locale(Locale.getDefault()).equals(phone), where + ": a default-locale call site keeps the phone's locale");
        BiFunction<Locale, String, String> never = (locale, skeleton) -> { throw new AssertionError(where + ": no skeleton while legacy"); };
        check("MMM d".equals(Fmt.datePattern("MMM d", "MMMd", never)), where + ": the legacy date pattern");
        check("h:mm:ss a".equals(Fmt.datePattern("h:mm:ss a", "hms", never)), where + ": the legacy time pattern");
        check(String.format(Fmt.locale(Locale.getDefault()), "%,d", 1234567).equals(String.format(phone, "%,d", 1234567)), where + ": grouping as before");
    }

    private static void numbers(String where) {
        double[] edges = {0, -0d, .05, .25, .35, -.25, -.04, .5, 1.5, 2.5, -.5, 1.005, 12.345, 1234.25, 1234567.891, 9.999999,
                1e-7, -1e-7, 1e15, 1e21, -1e21, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int decimals = -1; decimals <= 7; decimals++) {
            for (double value : edges) decimalParity(value, decimals, where);
        }
        Random random = new Random(20261103L);
        for (int i = 0; i < 400; i++) {
            double value = (random.nextDouble() - .5) * Math.pow(10, random.nextInt(9));
            // Values on an exact half of the last decimal are the ones a half-even formatter would change.
            double half = Math.round(value * 100) / 100d + .005;
            decimalParity(value, random.nextInt(4), where);
            decimalParity(half, 2, where);
            decimalParity(Math.round(value * 10) / 10d + .05, 1, where);
        }
        for (long value : new long[]{0, 1, -1, 7, 1234567, -1234567, Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE}) {
            check(Fmt.integer(value).equals("" + value), where + ": " + value + " as concatenation writes it");
            check(Fmt.integerNumber(value).toString().equals("" + value), where + ": " + value + " as structured text");
        }
        for (int i = 0; i < 200; i++) {
            int value = random.nextInt();
            check(Fmt.integer(value).equals("" + value), where + ": " + value + " as concatenation writes it");
        }
    }

    private static void decimalParity(double value, int decimals, String where) {
        int precision = Math.max(0, Math.min(6, decimals));
        String expected = String.format(Locale.US, "%." + precision + "f", value);
        check(Fmt.decimal(Locale.US, value, decimals).equals(expected), where + ": " + value + " with " + decimals + " decimals is " + expected
                + ", not " + Fmt.decimal(Locale.US, value, decimals));
        Formatted number = Fmt.decimalNumber(Locale.US, value, decimals);
        check(number.toString().equals(expected) && number.numbers() == 1 && number.numberStart(0) == 0
                && number.numberEnd(0) == expected.length() && number.units() == 0, where + ": " + value + " as one number range");
        String root = String.format(Locale.ROOT, "%." + precision + "f", value);
        check(Fmt.decimal(Locale.ROOT, value, decimals).equals(root), where + ": a Locale.ROOT site keeps Locale.ROOT for " + value);
    }

    private static void units(String where) {
        String[][] expected = {{"KMH", "km/h"}, {"MPH", "mph"}, {"KM", "km"}, {"MI", "mi"}, {"VOLT", "V"}, {"AMP", "A"}, {"WATT", "W"},
                {"WATT_HOUR", "Wh"}, {"AMP_HOUR", "Ah"}, {"CELSIUS", "°C"}, {"FAHRENHEIT", "°F"}, {"MINUTE", "min"}};
        check(expected.length == UnitCode.values().length, where + ": every unit code has its English symbol here");
        for (String[] unit : expected) {
            UnitCode code = UnitCode.valueOf(unit[0]);
            check(code.symbol().equals(unit[1]), where + ": " + code + " is " + unit[1] + ", not " + code.symbol());
            Formatted symbol = Fmt.unit(code);
            check(symbol.toString().equals(unit[1]) && symbol.units() == 1 && symbol.unitStart(0) == 0
                    && symbol.unitEnd(0) == unit[1].length() && symbol.numbers() == 0, where + ": " + code + " as one unit range");
        }
        // The templates: as the + chains they replace wrote them.
        Formatted quantity = Fmt.quantity(Fmt.number("12.5"), Fmt.unit(UnitCode.KM));
        equal("12.5 km", quantity, where, "quantity");
        ranges(quantity, new int[]{0, 4}, new int[]{5, 7}, where, "quantity");
        Formatted range = Fmt.range(Fmt.number("10"), Fmt.number("25"), Fmt.unit(UnitCode.KMH));
        equal("10–25 km/h", range, where, "range");
        ranges(range, new int[]{0, 2, 3, 5}, new int[]{6, 10}, where, "range");
        Formatted dual = Fmt.dual(Fmt.quantity(Fmt.number("7.8"), Fmt.unit(UnitCode.MI)), quantity);
        equal("7.8 mi / 12.5 km", dual, where, "dual");
        ranges(dual, new int[]{0, 3, 9, 13}, new int[]{4, 6, 14, 16}, where, "dual");
        equal("plain", Formatted.plain("plain"), where, "plain text");
        check(Formatted.plain("plain").numbers() == 0 && Formatted.plain("plain").units() == 0, where + ": plain text has no ranges");
    }

    private static void structured(String where) {
        Random random = new Random(7);
        String[] preferences = {"km", "mi", "dual", null, "other"};
        double[] speeds = {0, -0d, .04, -.04, .25, .05, 12.345, 25, 31.1, 1234.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (String units : preferences) {
            for (double kmh : speeds) speed(kmh, units, where);
            for (int i = 0; i < 60; i++) speed((random.nextDouble() - .2) * 140, units, where);
            int[][] rangesKmh = {{10, 25}, {0, 0}, {-3, 1000}, {25, 10}, {Integer.MIN_VALUE, Integer.MAX_VALUE}};
            for (int[] r : rangesKmh) speedRange(r[0], r[1], units, where);
            for (int i = 0; i < 30; i++) speedRange(random.nextInt(60) - 5, random.nextInt(130), units, where);
            for (double kmh : speeds) label(kmh, units, where);
            for (int i = 0; i < 60; i++) label(random.nextDouble() * 130, units, where);
        }
        String[] temperatures = {"c", "f", null, "F", ""};
        Double[] celsius = {null, 0d, -.01, -.04, -.06, .25, 23.456, -40d, 100d, 2000d, Double.NaN, Double.MAX_VALUE, Double.NEGATIVE_INFINITY};
        for (String preference : temperatures) {
            for (int decimals = -1; decimals <= 7; decimals++) {
                for (Double value : celsius) {
                    temperature(value, preference, decimals, where);
                    for (Double high : new Double[]{null, 23.5, -.04, Double.MAX_VALUE}) temperatureRange(value, high, preference, decimals, where);
                }
                for (int i = 0; i < 10; i++) temperature((random.nextDouble() - .3) * 120, preference, decimals, where);
            }
        }
    }

    private static void speed(double kmh, String units, String where) {
        Formatted shown = DisplayUnits.speedQuantity(kmh, units);
        equal(DisplayUnits.speed(kmh, units), shown, where, "speed " + kmh + " in " + units);
        String metric = String.format(Locale.US, "%.1f", kmh), imperial = String.format(Locale.US, "%.1f", kmh / KM_PER_MILE);
        numbersAndUnits(shown, units, imperial, "mph", metric, "km/h", where, "speed " + kmh);
    }

    private static void speedRange(int low, int high, String units, String where) {
        Formatted shown = DisplayUnits.speedRangeQuantity(low, high, units);
        equal(DisplayUnits.speedRange(low, high, units), shown, where, "speed range " + low + "–" + high + " in " + units);
        String[] metric = {"" + low, "" + high};
        String[] imperial = {String.format(Locale.US, "%.1f", low / KM_PER_MILE), String.format(Locale.US, "%.1f", high / KM_PER_MILE)};
        String[] expectedNumbers = "km".equals(units) ? metric : "dual".equals(units)
                ? new String[]{imperial[0], imperial[1], metric[0], metric[1]} : imperial;
        String[] expectedUnits = "km".equals(units) ? new String[]{"km/h"} : "dual".equals(units) ? new String[]{"mph", "km/h"} : new String[]{"mph"};
        texts(shown, expectedNumbers, expectedUnits, where, "speed range " + low + "–" + high + " in " + units);
    }

    private static void label(double kmh, String units, String where) {
        Formatted shown = RouteSpeedScale.labelQuantity(kmh, units);
        equal(RouteSpeedScale.label(kmh, units), shown, where, "route label " + kmh + " in " + units);
        String metric = String.format(Locale.US, "%.0f", kmh), imperial = String.format(Locale.US, "%.0f", kmh / RideStore.KM_PER_MILE);
        numbersAndUnits(shown, units, imperial, "mph", metric, "km/h", where, "route label " + kmh);
    }

    private static void temperature(Double celsius, String preference, int decimals, String where) {
        String legacy = DisplayTemperature.format(celsius, preference, decimals);
        Formatted shown = DisplayTemperature.quantity(celsius, preference, decimals);
        String what = "temperature " + celsius + " " + preference + " " + decimals;
        if (shown == null) {
            check("—".equals(legacy), where + ": " + what + " is unavailable in both");
            return;
        }
        equal(legacy, shown, where, what);
        String unit = DisplayTemperature.unit(preference);
        texts(shown, new String[]{legacy.substring(0, legacy.length() - unit.length() - 1)}, new String[]{unit}, where, what);
    }

    private static void temperatureRange(Double low, Double high, String preference, int decimals, String where) {
        String legacy = DisplayTemperature.range(low, high, preference, decimals);
        Formatted shown = DisplayTemperature.rangeQuantity(low, high, preference, decimals);
        String what = "temperature range " + low + "–" + high + " " + preference + " " + decimals;
        if (shown == null) {
            check("—".equals(legacy), where + ": " + what + " is unavailable in both");
            return;
        }
        equal(legacy, shown, where, what);
        check(shown.numbers() == 2 && shown.units() == 1 && shown.unitEnd(0) == legacy.length()
                && shown.toString().substring(shown.unitStart(0)).equals(DisplayTemperature.unit(preference)), where + ": " + what + " ranges");
    }

    /** One number and unit per system, in the order the String method writes them. */
    private static void numbersAndUnits(Formatted shown, String units, String imperial, String imperialUnit, String metric,
            String metricUnit, String where, String what) {
        if ("km".equals(units)) texts(shown, new String[]{metric}, new String[]{metricUnit}, where, what + " in " + units);
        else if ("dual".equals(units)) texts(shown, new String[]{imperial, metric}, new String[]{imperialUnit, metricUnit}, where, what + " in dual");
        else texts(shown, new String[]{imperial}, new String[]{imperialUnit}, where, what + " in " + units);
    }

    private static void texts(Formatted shown, String[] numbers, String[] units, String where, String what) {
        String text = shown.toString();
        check(shown.numbers() == numbers.length && shown.units() == units.length, where + ": " + what + ": range counts");
        for (int i = 0; i < numbers.length; i++)
            check(text.substring(shown.numberStart(i), shown.numberEnd(i)).equals(numbers[i]), where + ": " + what + ": number " + i);
        for (int i = 0; i < units.length; i++)
            check(text.substring(shown.unitStart(i), shown.unitEnd(i)).equals(units[i]), where + ": " + what + ": unit " + i);
    }

    private static void ranges(Formatted shown, int[] numbers, int[] units, String where, String what) {
        check(java.util.Arrays.equals(shown.numberRanges(), numbers) && java.util.Arrays.equals(shown.unitRanges(), units),
                where + ": " + what + " ranges " + java.util.Arrays.toString(shown.numberRanges()) + " " + java.util.Arrays.toString(shown.unitRanges()));
    }

    /**
     * A source that answers in English but puts the unit before the number ("%2$s %1$s") and marks every
     * symbol: the ranges must follow where the parts land, not where English puts them.
     */
    private static void reordered() {
        T.install(source("en", true));
        Formatted quantity = Fmt.quantity(Fmt.number("12.5"), Fmt.unit(UnitCode.KMH));
        equal("<km/h> 12.5", quantity, "reordered", "quantity");
        ranges(quantity, new int[]{7, 11}, new int[]{0, 6}, "reordered", "quantity");
        Formatted dual = Fmt.dual(Fmt.quantity(Fmt.number("7.8"), Fmt.unit(UnitCode.MPH)), quantity);
        equal("<km/h> 12.5 | <mph> 7.8", dual, "reordered", "dual, metric first");
        ranges(dual, new int[]{7, 11, 20, 23}, new int[]{0, 6, 14, 19}, "reordered", "dual");
        Formatted range = Fmt.range(Fmt.number("1"), Fmt.number("2"), Fmt.unit(UnitCode.KM));
        equal("<km>: 1 … 2 (1)", range, "reordered", "range repeating its low end");
        ranges(range, new int[]{6, 7, 10, 11, 13, 14}, new int[]{0, 4}, "reordered", "range");
        T.install(null);
    }

    private static T.Source source(String language, boolean reorder) {
        return new T.Source() {
            public String string(int id) {
                if (!reorder) return null;
                if (id == T.FMT_QUANTITY) return "%2$s %1$s";
                if (id == T.FMT_DUAL) return "%2$s | %1$s";
                if (id == T.FMT_RANGE) return "%3$s: %1$s … %2$s (%1$s)";
                for (UnitCode unit : UnitCode.values()) if (id == unit.symbolId) return "<" + EnglishText.INSTANCE.string(id) + ">";
                return null;
            }
            public String plural(int id, long quantity) { return null; }
            public String language() { return language; }
        };
    }

    private static void equal(String expected, CharSequence actual, String where, String what) {
        check(expected.equals(actual.toString()), where + ": " + what + ": expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
