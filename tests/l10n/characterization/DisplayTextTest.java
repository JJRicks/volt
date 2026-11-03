package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B1 (formatting core): the English text DisplayTemperature and RouteSpeedScale
 * build before the batch, byte for byte, beyond what DisplayTemperatureTest and RouteSpeedScaleTest
 * already pin: precision limits, rounding, negative zero, and the route legend's title, labels and
 * watts. Every case also runs with the phone set to de-DE, en-GB and en-IN.
 */
public final class DisplayTextTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN")}) {
                Locale.setDefault(phone);
                temperature(phone);
                route(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("DisplayTextTest: " + checks + " checks passed (DisplayTemperature and RouteSpeedScale English under 4 phone locales)");
    }

    private static void temperature(Locale phone) {
        equal("23.46 °C", DisplayTemperature.format(23.456, "c", 2), phone, "two decimals");
        equal("23 °C", DisplayTemperature.format(23.456, "c", -1), phone, "negative decimals clamp to none");
        equal("23.456000 °C", DisplayTemperature.format(23.456, "c", 9), phone, "decimals clamp to six");
        equal("0.3 °C", DisplayTemperature.format(.25, "c", 1), phone, "a half rounds up");
        equal("0.0 °C", DisplayTemperature.format(-.04, "c", 1), phone, "no negative zero");
        equal("-0.1 °C", DisplayTemperature.format(-.06, "c", 1), phone, "a real negative keeps its sign");
        equal("0 °C", DisplayTemperature.format(-.4, "c", 0), phone, "no negative zero without decimals");
        equal("-1 °C", DisplayTemperature.format(-.5, "c", 0), phone, "minus a half rounds away from zero");
        equal("-40.0 °F", DisplayTemperature.format(-40d, "f", 1), phone, "negative Fahrenheit");
        equal("2000 °C", DisplayTemperature.format(2000d, "c", 0), phone, "no grouping separator");
        equal("100 °C", DisplayTemperature.format(100d, "F", 0), phone, "an upper-case F is not Fahrenheit");
        equal("100 °C", DisplayTemperature.format(100d, null, 0), phone, "no preference is Celsius");
        equal("0.0–0.0 °C", DisplayTemperature.range(-.01, .04, "c", 1), phone, "range endpoints never show negative zero");
        equal("-40.0–212.0 °F", DisplayTemperature.range(-40d, 100d, "f", 1), phone, "Fahrenheit range");
        equal("30–20 °C", DisplayTemperature.range(30d, 20d, "c", 0), phone, "a range keeps its endpoint order");
        equal("—", DisplayTemperature.range(0d, Double.MAX_VALUE, "f", 0), phone, "an overflowing endpoint is unavailable");
    }

    private static void route(Locale phone) {
        Double[] none = new Double[3];
        equal("Speed", RouteSpeedScale.of(none, none).title(), phone, "no speed source");
        Double[] gps = {10d, 20d, null};
        equal("Speed · GPS", RouteSpeedScale.of(gps, none).title(), phone, "GPS source");
        equal("Speed · scooter", RouteSpeedScale.of(gps, gps).title(), phone, "scooter source");
        equal("0 km/h", RouteSpeedScale.label(.4, "km"), phone, "label rounds to whole km/h");
        equal("1 km/h", RouteSpeedScale.label(.5, "km"), phone, "a half rounds up");
        equal("75 mph / 120 km/h", RouteSpeedScale.label(120, "dual"), phone, "dual label");
        equal("75 mph", RouteSpeedScale.label(120, null), phone, "no preference reads imperial");
        equal("1250 km/h", RouteSpeedScale.label(1250, "km"), phone, "labels have no grouping separator");
        equal("1,000 W", RouteSpeedScale.wattsLabel(1000), phone, "watts are grouped");
        equal("20,000 W", RouteSpeedScale.wattsLabel(20000), phone, "the largest power top");
        equal("1 W", RouteSpeedScale.wattsLabel(.5), phone, "a half watt rounds up");
        equal("999 W", RouteSpeedScale.wattsLabel(999), phone, "under a thousand");
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        check(expected.equals(actual), phone.toLanguageTag() + ": " + what + ": expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
