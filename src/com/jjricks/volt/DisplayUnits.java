package com.jjricks.volt;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display conversions only. Scooter commands continue to use their original metric values. */
public final class DisplayUnits {
    private static final double KM_PER_MILE = 1.609344;
    private static final Pattern METRIC_TEXT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?) (km/h|km)\\b");
    private DisplayUnits() {}

    /**
     * Button labels: the shown km/h and mph units each kept on one line when large text wraps the label, with a word
     * joiner after each punctuation mark, symbol or space inside the unit ("km/\u2060h" in English; "mph" has none, so
     * it stays as it is). The units are the language's units_kmh and units_mph, so the joiner follows the wording a
     * language shows, never a fixed English spelling. Display only: a report or log never holds a joiner.
     */
    public static String unbroken(String text) {
        return unbroken(unbroken(text, T.unitsKmh()), T.unitsMph());
    }

    private static String unbroken(String text, String unit) {
        StringBuilder joined = new StringBuilder(unit.length() + 2);
        for (int i = 0; i < unit.length(); ) {
            int c = unit.codePointAt(i);
            joined.appendCodePoint(c);
            i += Character.charCount(c);
            if (i < unit.length() && breaksAfter(c)) joined.append(WORD_JOINER);
        }
        return unit.isEmpty() ? text : text.replace(unit, joined);
    }

    /** Punctuation, symbols and spaces: never a combining mark or a joiner, which belong to the letter they follow. */
    private static boolean breaksAfter(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION: case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION: case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION: case Character.MATH_SYMBOL: case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL: case Character.OTHER_SYMBOL: case Character.SPACE_SEPARATOR:
                return true;
            default:
                return false;
        }
    }

    /** U+2060 WORD JOINER, built from its code point: it is not catalog text (GlyphCoverageTest: Saira has no glyph for it). */
    private static final char WORD_JOINER = (char) 0x2060;

    /**
     * The value with speeds in the rider's units, other values as their English label (feature.valueLabel): callers
     * compare and trim this text as English (MoreScreen's labId into CompatibilityPanel.question, MainActivity's
     * " hours" shortening), so it stays English. Show {@link #featureValueText} instead.
     */
    public static String featureValue(ScooterCommands.Feature feature, int value, String units) {
        if (!UnitCode.KMH.matches(feature.unit) || (value == 0 && feature.zeroIsOff())) return feature.valueLabel(value);
        String metric = T.fmtQuantity(Fmt.integer(value), T.unitsKmh());
        String imperial = T.fmtQuantity(Fmt.decimal(Locale.US, value / KM_PER_MILE, 1), T.unitsMph());
        return choose(metric, imperial, units);
    }

    /**
     * A slider sheet's two range ends, {low, high}, built from the numbers, for display: the high end is
     * featureValueText(max), and a speed's low end is its number alone ("0.0", "0", or "0.0 mph / 0" in dual units),
     * because the high end beside it names the unit; an Off low end is featureValueText(min). This replaces cutting
     * the high end's last word off the shown low end, which only worked where a language puts the unit last.
     */
    public static String[] sliderEnds(ScooterCommands.Feature feature, String units) {
        String high = featureValueText(feature, feature.max, units);
        if (!UnitCode.KMH.matches(feature.unit) || (feature.min == 0 && feature.zeroIsOff())) return new String[]{featureValueText(feature, feature.min, units), high};
        String metric = Fmt.integer(feature.min), imperial = Fmt.decimal(Locale.US, feature.min / KM_PER_MILE, 1);
        return new String[]{choose(metric, "dual".equals(units) ? T.fmtQuantity(imperial, T.unitsMph()) : imperial, units), high};
    }

    /** featureValue in the shown language: a value that is not a speed is feature.valueText(value). Display only. */
    public static String featureValueText(ScooterCommands.Feature feature, int value, String units) {
        return !UnitCode.KMH.matches(feature.unit) || (value == 0 && feature.zeroIsOff()) ? feature.valueText(value).toString() : featureValue(feature, value, units);
    }

    public static String speed(double kmh, String units) {
        return choose(T.fmtQuantity(Fmt.decimal(Locale.US, kmh, 1), T.unitsKmh()),
                T.fmtQuantity(Fmt.decimal(Locale.US, kmh / KM_PER_MILE, 1), T.unitsMph()), units);
    }

    public static String speedRange(int minimumKmh, int maximumKmh, String units) {
        return choose(T.fmtRange(Fmt.integer(minimumKmh), Fmt.integer(maximumKmh), T.unitsKmh()),
                T.fmtRange(Fmt.decimal(Locale.US, minimumKmh / KM_PER_MILE, 1),
                        Fmt.decimal(Locale.US, maximumKmh / KM_PER_MILE, 1), T.unitsMph()), units);
    }

    /** For decoded stored-trip summaries and distance hints; never used for raw payloads. */
    public static String metricText(String text, String units) {
        // While Volt is English the km text is the stored text, so it is returned at once, as before.
        if (text != null && "km".equals(units) && !Fmt.legacy()) return metricOnly(text);
        if (text == null || "km".equals(units)) return text;
        Matcher matches = METRIC_TEXT.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matches.find()) {
            double amount = Double.parseDouble(matches.group(1)) / KM_PER_MILE;
            String converted = T.fmtQuantity(Fmt.decimal(Locale.US, amount, 2),
                    matches.group(2).equals("km/h") ? T.unitsMph() : T.unitsMi());
            matches.appendReplacement(result, Matcher.quoteReplacement(choose(metric(matches), converted, units)));
        }
        matches.appendTail(result);
        return result.toString();
    }

    /** metricText in km for a shipped language: each metric quantity written again by {@link #metric}, the rest unchanged. */
    private static String metricOnly(String text) {
        Matcher matches = METRIC_TEXT.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matches.find()) matches.appendReplacement(result, Matcher.quoteReplacement(metric(matches)));
        matches.appendTail(result);
        return result.toString();
    }

    /**
     * A matched metric quantity ("12.5 km", "15.0 km/h" in Telemetry's stored-trip text) from its number and
     * UnitCode through Fmt, so a shipped language writes it with its own separator, as it does the converted
     * miles beside it. Only a number Telemetry wrote (Telemetry.format with that many decimals) is written again;
     * anything else ("012.5") stays as matched. While Volt is English, Fmt keeps Locale.US, so the result is the
     * matched text byte for byte.
     */
    private static String metric(Matcher matches) {
        String digits = matches.group(1);
        UnitCode unit = UnitCode.KMH.matches(matches.group(2)) ? UnitCode.KMH : UnitCode.KM;
        double amount = Double.parseDouble(digits);
        for (int decimals = 0; decimals <= 6; decimals++)
            if (Telemetry.format(amount, decimals).equals(digits))
                return Fmt.quantity(Fmt.decimalNumber(Locale.US, amount, decimals), Fmt.unit(unit)).toString();
        return matches.group();
    }

    /** {@link #speed} as structured text: the same text, with the number and unit ranges. */
    public static Formatted speedQuantity(double kmh, String units) {
        return choose(Fmt.quantity(Fmt.decimalNumber(Locale.US, kmh, 1), Fmt.unit(UnitCode.KMH)),
                Fmt.quantity(Fmt.decimalNumber(Locale.US, kmh / KM_PER_MILE, 1), Fmt.unit(UnitCode.MPH)), units);
    }

    /** {@link #speedRange} as structured text: the same text, with the number and unit ranges. */
    public static Formatted speedRangeQuantity(int minimumKmh, int maximumKmh, String units) {
        return choose(Fmt.range(Fmt.integerNumber(minimumKmh), Fmt.integerNumber(maximumKmh), Fmt.unit(UnitCode.KMH)),
                Fmt.range(Fmt.decimalNumber(Locale.US, minimumKmh / KM_PER_MILE, 1),
                        Fmt.decimalNumber(Locale.US, maximumKmh / KM_PER_MILE, 1), Fmt.unit(UnitCode.MPH)), units);
    }

    private static Formatted choose(Formatted metric, Formatted imperial, String units) {
        if ("km".equals(units)) return metric;
        return "dual".equals(units) ? Fmt.dual(imperial, metric) : imperial;
    }

    private static String choose(String metric, String imperial, String units) {
        if ("km".equals(units)) return metric;
        return "dual".equals(units) ? T.fmtDual(imperial, metric) : imperial;
    }

    /** Lock duration's hours for an option value (the same table on every model), or 0 for a value that has none. */
    static int lockHours(int value) {
        return value == 1 ? 6 : value == 2 ? 12 : value == 3 ? 24 : 0;
    }
}
