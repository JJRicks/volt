package com.jjricks.volt;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display conversions only. Scooter commands continue to use their original metric values. */
public final class DisplayUnits {
    private static final double KM_PER_MILE = 1.609344;
    private static final Pattern METRIC_TEXT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?) (km/h|km)\\b");
    private DisplayUnits() {}

    public static String featureValue(ScooterCommands.Feature feature, int value, String units) {
        if (!"km/h".equals(feature.unit) || (value == 0 && feature.zeroIsOff())) return feature.valueLabel(value);
        String metric = T.fmtQuantity(Fmt.integer(value), T.unitsKmh());
        String imperial = T.fmtQuantity(Fmt.decimal(Locale.US, value / KM_PER_MILE, 1), T.unitsMph());
        return choose(metric, imperial, units);
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
        if (text == null || "km".equals(units)) return text;
        Matcher matches = METRIC_TEXT.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matches.find()) {
            double amount = Double.parseDouble(matches.group(1)) / KM_PER_MILE;
            String converted = T.fmtQuantity(Fmt.decimal(Locale.US, amount, 2),
                    matches.group(2).equals("km/h") ? T.unitsMph() : T.unitsMi());
            matches.appendReplacement(result, Matcher.quoteReplacement(choose(matches.group(), converted, units)));
        }
        matches.appendTail(result);
        return result.toString();
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
}
