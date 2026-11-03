package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B1 (formatting core): DisplayUnits' English output before the batch, byte for
 * byte. DisplayUnits had no tests. Every case also runs with the phone set to de-DE, en-GB and en-IN,
 * because the output must not follow the phone's region while Volt is English.
 */
public final class DisplayUnitsTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN")}) {
                Locale.setDefault(phone);
                features(phone);
                speeds(phone);
                ranges(phone);
                metricText(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("DisplayUnitsTest: " + checks + " checks passed (DisplayUnits English output under 4 phone locales)");
    }

    private static void features(Locale phone) {
        ScooterCommands.Feature cap = ScooterCommands.find("speed_limit");
        ScooterCommands.Feature top = ScooterCommands.find("maximum_speed");
        ScooterCommands.Feature kick = ScooterCommands.find("start_speed");
        for (String units : new String[]{"km", "mi", "dual", null, "other"})
            equal("Off", DisplayUnits.featureValue(cap, 0, units), phone, "a speed cap of 0 reads Off in " + units);
        equal("25 km/h", DisplayUnits.featureValue(cap, 25, "km"), phone, "metric speed cap");
        equal("15.5 mph", DisplayUnits.featureValue(cap, 25, "mi"), phone, "imperial speed cap");
        equal("15.5 mph / 25 km/h", DisplayUnits.featureValue(cap, 25, "dual"), phone, "dual speed cap");
        equal("15.5 mph", DisplayUnits.featureValue(cap, 25, null), phone, "no unit preference reads imperial");
        equal("15.5 mph", DisplayUnits.featureValue(cap, 25, "other"), phone, "an unknown preference reads imperial");
        equal("31.1 mph", DisplayUnits.featureValue(top, 50, "mi"), phone, "imperial maximum speed");
        equal("0 km/h", DisplayUnits.featureValue(kick, 0, "km"), phone, "0 is a speed when it does not mean Off");
        equal("0.0 mph / 0 km/h", DisplayUnits.featureValue(kick, 0, "dual"), phone, "dual zero kick-start speed");
        equal("3.1 mph", DisplayUnits.featureValue(kick, 5, "mi"), phone, "imperial kick-start speed");
        equal("-1.2 mph / -2 km/h", DisplayUnits.featureValue(kick, -2, "dual"), phone, "a negative speed keeps its sign");
        // Features measured in anything else show their own label, whatever the units.
        for (String key : new String[]{"charge_limit", "display_units", "lock_expiry"}) {
            ScooterCommands.Feature feature = ScooterCommands.find(key);
            for (String units : new String[]{"km", "mi", "dual"}) {
                int value = feature.optionValues.length > 0 ? feature.optionValues[0] : feature.max;
                equal(feature.valueLabel(value), DisplayUnits.featureValue(feature, value, units), phone, key + " in " + units);
            }
        }
    }

    private static void speeds(Locale phone) {
        equal("12.3 km/h", DisplayUnits.speed(12.345, "km"), phone, "metric speed, one decimal");
        equal("7.7 mph", DisplayUnits.speed(12.345, "mi"), phone, "imperial speed");
        equal("7.7 mph / 12.3 km/h", DisplayUnits.speed(12.345, "dual"), phone, "dual speed");
        equal("7.7 mph", DisplayUnits.speed(12.345, null), phone, "no preference reads imperial");
        equal("0.3 km/h", DisplayUnits.speed(.25, "km"), phone, "a half rounds up");
        equal("0.1 km/h", DisplayUnits.speed(.05, "km"), phone, "0.05 rounds up");
        equal("-0.0 km/h", DisplayUnits.speed(-.04, "km"), phone, "a small negative speed keeps its minus sign");
        equal("-0.0 mph / -0.0 km/h", DisplayUnits.speed(-0d, "dual"), phone, "negative zero");
        equal("1234.5 km/h", DisplayUnits.speed(1234.5, "km"), phone, "no grouping separator");
        equal("767.1 mph", DisplayUnits.speed(1234.5, "mi"), phone, "large imperial speed");
        equal("NaN km/h", DisplayUnits.speed(Double.NaN, "km"), phone, "NaN");
        equal("Infinity mph / Infinity km/h", DisplayUnits.speed(Double.POSITIVE_INFINITY, "dual"), phone, "infinity");
        equal("0.0 mph", DisplayUnits.speed(0, "mi"), phone, "zero imperial speed");
    }

    private static void ranges(Locale phone) {
        equal("10–25 km/h", DisplayUnits.speedRange(10, 25, "km"), phone, "metric range");
        equal("6.2–15.5 mph", DisplayUnits.speedRange(10, 25, "mi"), phone, "imperial range");
        equal("6.2–15.5 mph / 10–25 km/h", DisplayUnits.speedRange(10, 25, "dual"), phone, "dual range");
        equal("0.0–0.0 mph", DisplayUnits.speedRange(0, 0, null), phone, "zero range, no preference");
        equal("-3–1000 km/h", DisplayUnits.speedRange(-3, 1000, "km"), phone, "a negative endpoint and no grouping");
    }

    private static void metricText(Locale phone) {
        check(DisplayUnits.metricText(null, "mi") == null, phone + ": no text stays no text");
        String trip = "Trip 12.5 km at 20 km/h";
        check(DisplayUnits.metricText(trip, "km") == trip, phone + ": metric text is returned as it is");
        equal("Trip 7.77 mi at 12.43 mph", DisplayUnits.metricText(trip, "mi"), phone, "imperial trip text");
        equal("Trip 7.77 mi / 12.5 km at 12.43 mph / 20 km/h", DisplayUnits.metricText(trip, "dual"), phone, "dual trip text");
        equal("Trip 7.77 mi at 12.43 mph", DisplayUnits.metricText(trip, null), phone, "no preference reads imperial");
        equal("5 kms, 12 kmh, 3km", DisplayUnits.metricText("5 kms, 12 kmh, 3km", "mi"), phone, "only whole units with a space convert");
        equal("0.00 mi, 621.37 mi", DisplayUnits.metricText("0 km, 1000 km", "mi"), phone, "zero and three digits");
        equal("3 trips: 1.24 mi / 2 km; $1 \\ 0.62 mi / 1.0 km", DisplayUnits.metricText("3 trips: 2 km; $1 \\ 1.0 km", "dual"), phone,
                "replacement text is literal");
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        check(expected.equals(actual), phone.toLanguageTag() + ": " + what + ": expected \"" + expected + "\", got \"" + actual + "\"");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
