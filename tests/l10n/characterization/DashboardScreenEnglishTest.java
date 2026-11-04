package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B13 (the Home dashboard screen), committed before the batch changes anything.
 *
 * DashboardScreen is an Android screen: a plain JVM test cannot call it. G2 (LedgerTest) proves each new
 * template renders the original concatenation, B13's own suite checks the whole templates a ternary picks,
 * and G7's census covers the screen. What this pins is the pure formatting the screen relies on today,
 * which the batch moves onto Fmt, the unit symbols and the producers' display accessors:
 *   - every String.format(Locale.US, ...) number the screen shows ("%.0f W", "%.2f km", "%.1f Ah",
 *     "%.0f%%", "%.0f–%.0f Wh", ...), byte for byte against Fmt.decimal, Fmt.quantity, Fmt.range and
 *     Fmt.percent with the unit's symbol, over rounding halves, negative zero, negatives, NaN, the
 *     infinities and large values;
 *   - whole numbers written by string concatenation (counts, slot numbers, percents, minutes) against
 *     Fmt.integer;
 *   - the reading names the screen shows: its label() is Telemetry's label with " (signed)" taken out,
 *     which is ScooterReadingLabels.full for every Telemetry reading but the two signed ones (current_a
 *     and power_w), whose shortened names are pinned here;
 *   - DisplayTemperature.unit (data) against symbol (display), and ChargingEstimate.LOW_CURRENT's English.
 * Every case runs with the phone set to en-US, de-DE, tr-TR, ar-EG and hi-IN.
 */
public final class DashboardScreenEnglishTest {
    private static int checks;

    private static final double[] VALUES = {
            0, -0.0, 0.004, 0.005, 0.05, 0.25, 0.5, 1, 1.25, 1.5, 2.5, -0.5, -0.004, -1.5, 9.95, 12.345, 18.75,
            42, 79.5, 99.4, 99.5, 99.95, 100, 412.5, 716, 999.5, 1234.5, 10000, 1e7, 123456789.125,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG"),
                    new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                quantities(phone);
                integers(phone);
                labels(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("DashboardScreenEnglishTest: " + checks + " checks passed (numbers, units, percents, "
                + "whole numbers and reading names under 5 phone locales)");
    }

    private static void quantities(Locale phone) {
        Object[][] units = {
                {2, "km", UnitCode.KM}, {0, "km", UnitCode.KM}, {0, "mi", UnitCode.MI}, {1, "km", UnitCode.KM},
                {1, "mi", UnitCode.MI}, {0, "W", UnitCode.WATT}, {0, "Wh", UnitCode.WATT_HOUR},
                {1, "Ah", UnitCode.AMP_HOUR}, {2, "Wh", UnitCode.WATT_HOUR}};
        for (double v : VALUES) {
            for (int decimals = 0; decimals <= 2; decimals++)
                same(String.format(Locale.US, "%." + decimals + "f", v), Fmt.decimal(Locale.US, v, decimals), phone,
                        "%." + decimals + "f of " + v);
            for (Object[] unit : units) {
                int decimals = (Integer) unit[0];
                UnitCode code = (UnitCode) unit[2];
                String legacy = String.format(Locale.US, "%." + decimals + "f " + unit[1], v);
                same(legacy, Fmt.quantity(Fmt.decimalNumber(Locale.US, v, decimals), Fmt.unit(code)).toString(), phone,
                        "Fmt.quantity " + legacy);
                same(legacy, T.fmtQuantity(Fmt.decimal(Locale.US, v, decimals), code.symbol()), phone,
                        "fmt_quantity " + legacy);
                same(unit[1], code.symbol(), phone, "the symbol of " + code);
            }
            same(String.format(Locale.US, "%.0f%%", v), Fmt.percent(Fmt.decimalNumber(Locale.US, v, 0)).toString(),
                    phone, "percent of " + v);
            same(String.format(Locale.US, "%.0f%%", v * 100), Fmt.percent(Fmt.decimalNumber(Locale.US, v * 100, 0))
                    .toString(), phone, "coverage percent of " + v);
            for (double high : new double[]{0, 1.04 * v, 742.5, Double.NaN}) {
                same(String.format(Locale.US, "%.0f–%.0f Wh", v, high),
                        Fmt.range(Fmt.decimalNumber(Locale.US, v, 0), Fmt.decimalNumber(Locale.US, high, 0),
                                Fmt.unit(UnitCode.WATT_HOUR)).toString(), phone, "range " + v + " " + high);
                same(String.format(Locale.US, "%.0f–%.0f Wh", v, high),
                        T.fmtRange(Fmt.decimal(Locale.US, v, 0), Fmt.decimal(Locale.US, high, 0), UnitCode.WATT_HOUR.symbol()),
                        phone, "fmt_range " + v + " " + high);
            }
            same(String.format(Locale.US, "≈ %.0f Wh", v),
                    "≈ " + Fmt.quantity(Fmt.decimalNumber(Locale.US, v, 0), Fmt.unit(UnitCode.WATT_HOUR)), phone,
                    "approximate energy " + v);
        }
    }

    private static void integers(Locale phone) {
        long[] values = {0, 1, 2, 5, 9, 10, 11, 21, 59, 60, 61, 99, 100, 101, 1439, 1440, 10000, -1, -60,
                Integer.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long n : values) {
            same("" + n, Fmt.integer(n), phone, "whole number " + n);
            same(n + "%", Fmt.percent(Fmt.integerNumber(n)).toString(), phone, "whole percent " + n);
            same(Long.toString(n), Fmt.integerNumber(n).toString(), phone, "structured whole number " + n);
        }
        for (int n : new int[]{0, 1, 2, 3, 8, 32}) same("" + (n + 1), Fmt.integer(n + 1), phone, "slot number " + n);
    }

    private static void labels(Locale phone) {
        StringBuilder signed = new StringBuilder();
        for (Telemetry.Metric m : new Telemetry().metrics()) {
            // The two signed readings: the screen drops the marker, so they need names of their own.
            if (m.label.contains(" (signed)"))
                signed.append(signed.length() == 0 ? "" : ",").append(m.key).append('=').append(m.label.replace(" (signed)", ""));
            same(m.label, ScooterReadingLabels.full(m.key, m.key), phone, "the full name of " + m.key);
        }
        same("current_a=Battery current,power_w=Battery power", signed.toString(), phone, "the signed readings");
        for (String preference : new String[]{"c", "f", null, "", "x", "F"})
            same(DisplayTemperature.unit(preference), DisplayTemperature.symbol(preference), phone,
                    "the temperature symbol for " + preference);
        same("Low current", ChargingEstimate.LOW_CURRENT, phone, "ChargingEstimate.LOW_CURRENT");
    }

    private static void same(Object expected, Object actual, Locale phone, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(phone.toLanguageTag() + ": " + what + ": expected \"" + expected + "\" but was \""
                    + actual + "\"");
    }
}
