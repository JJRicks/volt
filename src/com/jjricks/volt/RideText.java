package com.jjricks.volt;

import java.util.Locale;

/**
 * The Rides screen's measured values as structured text (plan section 4.2): each value says where its
 * number and unit sit, so the screen styles and splits it without reading the text, whatever order and
 * spacing a language's fmt_quantity gives it. Display only: nothing here is stored, sent or logged.
 *
 * Plain Java with no Android, so tests/l10n/RidesFollowupsTest runs it for real against the base English.
 * A reading that was not measured never gets here: the screen decides that from the number.
 */
final class RideText {
    private RideText() {}

    /** A distance in the "units" preference: "1.24 mi", "2.00 km" or "1.24 mi / 2.00 km". */
    static Formatted distance(double km, String units) {
        return distance(km, units, 2);
    }

    /** The same with decimals places ("6.2 mi" with one, as Clean's Just back and Ride row show it). */
    static Formatted distance(double km, String units, int decimals) {
        Formatted metric = Fmt.quantity(Fmt.decimalNumber(Locale.US, km, decimals), Fmt.unit(UnitCode.KM));
        Formatted miles = Fmt.quantity(Fmt.decimalNumber(Locale.US, km / RideStore.KM_PER_MILE, decimals), Fmt.unit(UnitCode.MI));
        return units.equals("dual") ? Fmt.dual(miles, metric) : units.equals("km") ? metric : miles;
    }

    /** A speed in the "units" preference: "15.5 mph", "25.0 km/h" or "15.5 mph / 25.0 km/h". */
    static Formatted speed(double kmh, String units) {
        Formatted metric = Fmt.quantity(Fmt.decimalNumber(Locale.US, kmh, 1), Fmt.unit(UnitCode.KMH));
        Formatted miles = Fmt.quantity(Fmt.decimalNumber(Locale.US, kmh / RideStore.KM_PER_MILE, 1), Fmt.unit(UnitCode.MPH));
        return units.equals("dual") ? Fmt.dual(miles, metric) : units.equals("km") ? metric : miles;
    }

    /** A reading with a unit symbol the screen names ("Wh", "V", "ft"). */
    static Formatted quantity(double value, CharSequence unit, int decimals) {
        return Fmt.quantity(Fmt.decimalNumber(Locale.US, value, decimals), Fmt.unitText(unit));
    }

    /** Energy per distance with the word for how it was worked out: "54.3 Wh/mi · net". */
    static Formatted efficiency(double perKm, double perMile, boolean net, String units) {
        Formatted metric = quantity(perKm, "Wh/km", 1), miles = quantity(perMile, "Wh/mi", 1);
        Formatted value = units.equals("dual") ? Fmt.dual(miles, metric) : units.equals("km") ? metric : miles;
        return Fmt.compose(net ? T.RIDES_EFFICIENCY_NET : T.RIDES_EFFICIENCY_THROUGHPUT, value);
    }

    /** text with number in it: a mark (≈) straight before the number at the very start stays with it. */
    static Formatted approximate(String text, String number) {
        int start = text.indexOf(number), end = start + number.length();
        return new Formatted(text, new int[] {start == 1 ? 0 : start, end}, new int[0]);
    }

    /**
     * A value's first number and everything beside it, {"6.80", "mi"}, {"4.23", "mi / 6.80 km"}, or null
     * when its number has no digit ("NaN", "Infinity"): such a reading has nothing to show large.
     */
    static String[] numberAndRest(Formatted value) {
        if (value.numbers() == 0) return null;
        String text = value.toString();
        String number = text.substring(value.numberStart(0), value.numberEnd(0));
        boolean digit = false;
        for (int i = 0; i < number.length(); i++) digit |= Character.isDigit(number.charAt(i));
        if (!digit) return null;
        return new String[] {number, strip(text.substring(0, value.numberStart(0)) + text.substring(value.numberEnd(0)))};
    }

    /** Cut spaces of any kind (a template may use a no-break or narrow space) from both ends. */
    private static String strip(String text) {
        int start = 0, end = text.length();
        while (start < end && isSpace(text.charAt(start))) start++;
        while (end > start && isSpace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
}
