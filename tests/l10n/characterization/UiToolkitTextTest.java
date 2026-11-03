package com.jjricks.volt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * G5 characterization for B2 (the shared UI toolkit), committed before the batch changes anything.
 *
 * Ui, its charts and the other toolkit views need Android, so a plain JVM test cannot call them; G2
 * (LedgerTest) proves each new template renders the original concatenation, and G7's census covers
 * the screens. What this pins is the pure arithmetic and formatting those views do today, which the
 * batch moves onto Fmt:
 *   - the chart's eight date and time patterns with the phone's locale (Ui.ChartView), and that
 *     Fmt.locale(Locale.getDefault()) and Fmt.datePattern give back exactly that locale and pattern;
 *   - the chart's numbers: "%.0fk", "%.1fk", "%.0f", "%.1f" and the axis's "%.<n>f%s" with
 *     Locale.US, byte for byte against Fmt.decimal and Fmt.locale(Locale.US), over rounding halves,
 *     negative zero, negatives, NaN and infinities;
 *   - whole numbers written by string concatenation (ReorderList's positions, the chart's sample
 *     count, the battery percent) against Fmt.integer;
 *   - Ui.sectionLabel's upper-casing with Locale.ROOT, also on a Turkish phone;
 *   - LockSliderGeometry's left-to-right geometry (the lock switch).
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR.
 */
public final class UiToolkitTextTest {
    private static int checks;

    /** Ui.ChartView's legacy patterns, 12- and 24-hour. */
    private static final String[] CHART_PATTERNS = {
            "HH:mm:ss", "h:mm:ss a", "HH:mm", "h:mm a", "MMM d", "MMM d HH:mm", "MMM d h:mm a",
            "MMM d, yyyy HH:mm:ss", "MMM d, yyyy h:mm:ss a"};

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        TimeZone savedZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                dates(phone);
                numbers(phone);
                integers(phone);
                upperCase(phone);
                geometry(phone);
            }
        } finally {
            Locale.setDefault(saved);
            TimeZone.setDefault(savedZone);
        }
        System.out.println("UiToolkitTextTest: " + checks + " checks passed (chart dates and numbers, positions, "
                + "section labels and lock geometry under 5 phone locales)");
    }

    private static void dates(Locale phone) {
        same(Locale.getDefault(), Fmt.locale(Locale.getDefault()), phone, "the chart keeps the phone's locale");
        long[] instants = {0L, 1700000000000L, 1767225599000L, 1751350245000L};
        java.util.function.BiFunction<Locale, String, String> never = (locale, skeleton) -> {
            throw new AssertionError("an English chart asked for a best pattern");
        };
        for (String pattern : CHART_PATTERNS) {
            equal(pattern, Fmt.datePattern(pattern, pattern, never), phone, "the legacy pattern " + pattern);
            for (long instant : instants) {
                String before = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(instant));
                String after = new SimpleDateFormat(Fmt.datePattern(pattern, pattern, never),
                        Fmt.locale(Locale.getDefault())).format(new Date(instant));
                equal(before, after, phone, pattern + " at " + instant);
            }
        }
    }

    private static void numbers(Locale phone) {
        double[] values = {0, -0d, .05, -.05, .25, -.25, .5, 1, 9.95, 99.95, 99.96, 100, 100.5, 999.5, 999.96,
                1000, 1049.99, 1050, 1250, 9949.99, 9950, 9999.5, 10000, 10500, 12345.678, 99999, -1500, -12500,
                -99.95, 1e9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double value : values) {
            equal(String.format(Locale.US, "%.0fk", value / 1000), Fmt.decimal(Locale.US, value / 1000, 0) + "k",
                    phone, "%.0fk of " + value);
            equal(String.format(Locale.US, "%.1fk", value / 1000), Fmt.decimal(Locale.US, value / 1000, 1) + "k",
                    phone, "%.1fk of " + value);
            equal(String.format(Locale.US, "%.0f", value), String.format(Fmt.locale(Locale.US), "%.0f", value),
                    phone, "%.0f of " + value);
            double shown = value == 0 ? 0 : value;
            equal(String.format(Locale.US, "%.1f", shown), String.format(Fmt.locale(Locale.US), "%.1f", shown),
                    phone, "%.1f of " + value);
            for (int decimals = 0; decimals <= 6; decimals++) {
                for (String suffix : new String[]{"", "k"}) {
                    equal(String.format(Locale.US, "%." + decimals + "f%s", value, suffix),
                            String.format(Fmt.locale(Locale.US), "%." + decimals + "f%s", value, suffix),
                            phone, "axis %." + decimals + "f" + suffix + " of " + value);
                }
            }
        }
    }

    private static void integers(Locale phone) {
        long[] values = {0, 1, 2, 9, 10, 99, 100, 1000, 1600, 12345, -1, -1000, Integer.MAX_VALUE, Long.MIN_VALUE};
        for (long value : values) {
            equal("" + value, Fmt.integer(value), phone, "whole number " + value);
            equal(Math.round((double) value) + " percent", Fmt.integer(Math.round((double) value)) + " percent",
                    phone, "rounded percent " + value);
        }
        int position = 3;
        equal("Speed moved to position " + (position + 1) + " of " + 12,
                "Speed moved to position " + Fmt.integer(position + 1) + " of " + Fmt.integer(12), phone,
                "a reorder announcement");
    }

    private static void upperCase(Locale phone) {
        for (String label : new String[]{"Ride", "Battery", "distance & speed", "lights", "Digital instrument",
                "Info", "ﬁt", "straße"}) {
            equal(label.toUpperCase(Locale.ROOT), label.toUpperCase(Fmt.locale(Locale.ROOT)), phone,
                    "section label " + label);
        }
        equal("RIDE", "Ride".toUpperCase(Locale.ROOT), phone, "RIDE");
        equal("LIGHTS", "lights".toUpperCase(Locale.ROOT), phone, "a dotted i stays I on a Turkish phone");
    }

    private static void geometry(Locale phone) {
        for (float density : new float[]{1, 2.75f, 3.5f}) {
            for (int[] size : new int[][]{{117, 182}, {150, 182}, {312, 374}, {312, 54}, {48, 48}, {144, 54}, {0, 0}}) {
                float width = size[0] * density, height = size[1] * density;
                float maximum = 58 * density, inset = 5 * density;
                LockSliderGeometry g = new LockSliderGeometry(width, height, maximum, inset);
                float diameter = Math.max(0, Math.min(Math.min(height, maximum), width / 2f));
                same(diameter, g.diameter, phone, "diameter " + size[0] + "x" + size[1]);
                same(Math.max(0, diameter / 2f - inset), g.radius, phone, "radius");
                same(diameter / 2f, g.firstCenter, phone, "first centre");
                same(Math.max(0, width - diameter), g.travel, phone, "travel");
                for (float position : new float[]{-1, 0, .25f, .5f, .75f, 1, 2}) {
                    same(diameter / 2f + Math.max(0, width - diameter) * Math.max(0, Math.min(1, position)),
                            g.centerFor(position), phone, "centre at " + position);
                }
                for (float x : new float[]{-100, 0, diameter / 2f, width / 2f, width - diameter / 2f, width, width + 100}) {
                    same(Math.max(0, Math.min(1, (x - diameter / 2f) / Math.max(1, Math.max(0, width - diameter)))),
                            g.positionFor(x), phone, "position at " + x);
                }
            }
        }
        LockSliderGeometry ordinary = new LockSliderGeometry(312, 54, 58, 5);
        same(27f, ordinary.centerFor(0), phone, "locked end");
        same(285f, ordinary.centerFor(1), phone, "unlocked end");
        same(0f, ordinary.positionFor(27), phone, "touch at the locked end");
        same(1f, ordinary.positionFor(285), phone, "touch at the unlocked end");
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(phone + ": " + what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    private static void same(Object expected, Object actual, Locale phone, String what) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(phone + ": " + what + ": expected " + expected + " but was " + actual);
        }
    }
}
