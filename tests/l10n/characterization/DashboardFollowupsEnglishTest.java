package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * G5 characterization for F6 (the Home dashboard's follow-ups), committed before the batch changes anything.
 *
 * DashboardScreen is an Android screen: a plain JVM test cannot call it. So each private expression F6 turns into
 * templates, plurals or number parts is copied here exactly as it stands at the merge base, as a static method, and
 * pinned against literal English. F6's own suite (tests/l10n/DashboardFollowupsTest.java) then compares the catalog
 * with these same methods over many more inputs, including the known English bugs ("1 rides").
 * Every case runs with the phone set to en-US, de-DE, tr-TR, ar-EG and hi-IN.
 */
public final class DashboardFollowupsEnglishTest {
    private static int checks;

    // The base's expressions, token for token apart from the names of what they read.

    /** The header status line (DashboardScreen :792-805); recent is null without a last seen time. */
    static String statusLine(boolean ready, boolean connecting, String mode, long lastConnected, String recent) {
        String word = ready ? "Connected" : connecting ? "Connecting" : "Disconnected";
        return word
                + (mode != null ? " · " + mode : "")
                + (!ready && lastConnected > 0
                        ? " · last seen "
                                + recent
                        : "");
    }

    /** The header's spoken form (:806-813). */
    static String statusSpoken(boolean ready, boolean connecting, String mode, long lastConnected, String dateTime) {
        String word = ready ? "Connected" : connecting ? "Connecting" : "Disconnected";
        return word
                + (mode != null ? ", " + mode + " mode" : "")
                + (!ready && !connecting && lastConnected > 0
                        ? ", last seen "
                                + dateTime
                        : "")
                + ". Connection details";
    }

    /** The estimate details' Window value (:3803-3804). */
    static String windowValue(int windowDays, boolean includesActive) {
        return windowDays + (windowDays == 1 ? " day" : " days")
                + (includesActive ? " · live" : "");
    }

    /** The column choices' spoken text (:6245-6246), with i above 0 (0 is its own exact key). */
    static String columnSpoken(int i, boolean available) {
        return i + (i == 1 ? " column" : " columns")
                + (available ? "" : ", needs a wider screen");
    }

    /** A saved slot's summary (:6558-6562); saved false for an unreadable or empty slot. */
    static String slotSummary(boolean saved, boolean occupied, int widgetCount, String recent) {
        return !saved
                ? (occupied ? "Unreadable" : "Empty")
                : (widgetCount == 1 ? "1 widget" : widgetCount + " widgets")
                        + " · " + recent;
    }

    /** The advertised capacity row's value (:5726). */
    static String ratingValue(double value, double advertisedWh) {
        return String.format(Locale.US, "%.2f Wh%s", value, advertisedWh == 0 ? " · model" : "");
    }

    /** The capacity widget's summary with a range (:3727). */
    static String ridesRange(int rides, double low, double high) {
        return T.dashboardRidesRange(Fmt.integer(rides), Fmt.decimal(Locale.US, low, 0), Fmt.decimal(Locale.US, high, 0), T.unitsWattHour());
    }

    /** The capacity widget's summary without one (:3728). */
    static String ridesPoints(int rides, double points) {
        return T.dashboardRidesPoints(Fmt.integer(rides), Fmt.decimal(Locale.US, points, 0));
    }

    /** The Choose readings count (:5879, :5947). */
    static String countOfSelected(int chosen, int most) {
        return T.dashboardCountOfSelected(Fmt.integer(chosen), Fmt.integer(most));
    }

    /** The estimate details' Rejected value (:3807-3808). */
    static String rejected(int rejectedRides, String reason) {
        return rejectedRides
                + (reason == null ? "" : " · " + reason);
    }

    /** A window's long name (:6055-6058). */
    static String windowName(long ms) {
        long count = ms / (ms < 3600000 ? 60000 : ms < 86400000 ? 3600000 : 86400000);
        String unit = ms < 3600000 ? "minute" : ms < 86400000 ? "hour" : "day";
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    /** The recording button's spoken text (:3560-3561), with android.text.TextUtils.join written out. */
    static String recordingSpoken(String clock, List<String> parts) {
        return "Recording " + clock
                + (parts.isEmpty() ? "" : ", " + String.join(", ", parts)) + ". Stop & save";
    }

    /** The recorder clock (:3549-3551). */
    static String clock(long seconds) {
        return seconds >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The scale row's value when the amount is not a speed's (:6061-6066). */
    static String scaleSummary(double displayed, String unit) {
        String amount = String.format(Locale.US, "%.2f", displayed);
        return amount + " " + unit;
    }

    /** The scale dialog's spoken input (:6081-6082). */
    static String scaleSpoken(boolean maximum, String unitName) {
        return (maximum ? "Scale maximum" : "Scale minimum")
                + (unitName.isEmpty() ? "" : " in " + unitName);
    }

    /** The capacity details' Points value (:3781-3783). */
    static String pointsValue(boolean plain, double points, double progressPoints, double minimum) {
        return plain
                ? String.format(Locale.US, "%.0f", points)
                : String.format(Locale.US, "%.0f / %.0f", Math.floor(progressPoints), minimum);
    }

    /** The shortcut search's test (:5434). */
    static boolean shortcutMatch(String typed, String label, String group) {
        String query = typed.toLowerCase(Locale.ROOT).trim();
        return query.isEmpty() || (label + " " + group).toLowerCase(Locale.ROOT).contains(query);
    }

    /** {start, end} of the part withUnit draws smaller (:929-943), or null when it draws none. */
    static int[] unitSpan(String value) {
        int space = value.lastIndexOf(' ');
        if (space <= 0 || space >= value.length() - 1 || value.contains("/")) return null;
        String unit = value.substring(space + 1);
        char first = unit.charAt(0);
        if (unit.length() > 5 || !(Character.isLetter(first) || first == '%' || first == '°'))
            return null;
        if (!Character.isDigit(value.charAt(space - 1))) return null;
        return new int[]{space, value.length()};
    }

    /** {amount, unit, secondary} as Gauge.set splits a reading with a number (:4218-4228). */
    static String[] gaugeParts(String formatted) {
        String amount = formatted, unit = "", secondary = "";
        int split = formatted.indexOf(' ');
        if (split > 0 && Character.isDigit(formatted.charAt(split - 1))) {
            amount = formatted.substring(0, split);
            unit = formatted.substring(split + 1).trim();
            int dual = unit.indexOf(" / ");
            if (dual >= 0) {
                secondary = unit.substring(dual + 3);
                unit = unit.substring(0, dual);
            }
        }
        return new String[]{amount, unit, secondary};
    }

    static final long MINUTE = 60000, HOUR = 3600000, DAY = 86400000;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG"),
                    new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                pinned();
                quantities();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("DashboardFollowupsEnglishTest: " + checks + " checks passed (the base's status, count, "
                + "capacity, window, recorder, scale, search and unit-split expressions under 5 phone locales)");
    }

    private static void pinned() {
        same("Connected", statusLine(true, false, null, 0, null));
        same("Connected · Sport", statusLine(true, false, "Sport", 5, "ignored"));
        same("Disconnected", statusLine(false, false, null, 0, null));
        same("Disconnected · last seen 14:05", statusLine(false, false, null, 5, "14:05"));
        same("Connected. Connection details", statusSpoken(true, false, null, 0, null));
        same("Connected, Sport mode. Connection details", statusSpoken(true, false, "Sport", 5, "x"));
        same("Connecting. Connection details", statusSpoken(false, true, null, 5, "x"));
        same("Disconnected. Connection details", statusSpoken(false, false, null, 0, null));
        same("Disconnected, last seen 3 Nov 14:05. Connection details", statusSpoken(false, false, null, 5, "3 Nov 14:05"));
        same("1 day", windowValue(1, false));
        same("7 days · live", windowValue(7, true));
        same("0 days", windowValue(0, false));
        same("1 column", columnSpoken(1, true));
        same("2 columns, needs a wider screen", columnSpoken(2, false));
        same("Empty", slotSummary(false, false, 0, null));
        same("Unreadable", slotSummary(false, true, 0, null));
        same("1 widget · 14:05", slotSummary(true, true, 1, "14:05"));
        same("3 widgets · 03 NOV · 14:05", slotSummary(true, true, 3, "03 NOV · 14:05"));
        same("716.04 Wh · model", ratingValue(716.04, 0));
        same("500.00 Wh", ratingValue(500, 500));
        same("1 rides · 500–600 Wh", ridesRange(1, 500, 600));
        same("4 rides · 80 points used", ridesPoints(4, 79.5));
        same("1 rides · 1 points used", ridesPoints(1, 1));
        same("3 of 8 selected", countOfSelected(3, 8));
        same("2", rejected(2, null));
        same("2 · Clock changed", rejected(2, "Clock changed"));
        same("1 minute", windowName(MINUTE));
        same("10 minutes", windowName(10 * MINUTE));
        same("1 hour", windowName(HOUR));
        same("6 hours", windowName(6 * HOUR));
        same("1 day", windowName(DAY));
        same("90 days", windowName(90 * DAY));
        same("0 minutes", windowName(0));
        same("Recording 0:42. Stop & save", recordingSpoken("0:42", new ArrayList<String>()));
        same("Recording 0:42, 1.2 mi, 15 Wh. Stop & save", recordingSpoken("0:42", Arrays.asList("1.2 mi", "15 Wh")));
        same("0:42", clock(42));
        same("1:01:05", clock(3665));
        same("25.00 mph", scaleSummary(25, "mph"));
        same("25.00 ", scaleSummary(25, ""));
        same("Scale maximum in mph", scaleSpoken(true, "mph"));
        same("Scale minimum", scaleSpoken(false, ""));
        same("80", pointsValue(true, 79.5, 0, 0));
        same("12 / 60", pointsValue(false, 0, 12.9, 60));
        same(true, shortcutMatch("LOCK c", "Lock", "Controls"));
        same(false, shortcutMatch("lockc", "Lock", "Controls"));
    }

    /** The reading texts withUnit and Gauge.set split, and the structured forms of the two that have one. */
    private static void quantities() {
        for (String units : new String[]{"mi", "km", "dual"})
            for (double kmh : new double[]{0, -0.0, 0.5, 12.5, 25, 99.95, -3, 1234.5, Double.NaN, Double.POSITIVE_INFINITY})
                same(DisplayUnits.speed(kmh, units), DisplayUnits.speedQuantity(kmh, units).toString());
        for (String unit : new String[]{"c", "f", "C", "F", "celsius", "fahrenheit", ""})
            for (double c : new double[]{0, -0.0, 21.5, -40, 100, 1e6})
                same(DisplayTemperature.format(c, unit, 0), DisplayTemperature.quantity(c, unit, 0).toString());
        same(null, DisplayTemperature.quantity(Double.NaN, "c", 0));
        same("—", DisplayTemperature.format(Double.NaN, "c", 0));
        span("412 W", 3, 5);
        span("25.0 mph", 4, 8);
        span("21 °C", 2, 5);
        span("80 %", 2, 4);
        span("12.0 km/h", -1, -1);
        span("7.8 mph / 12.5 km/h", -1, -1);
        span("NaN mph", -1, -1);
        span("no unit here", -1, -1);
        span("12", -1, -1);
        parts("7.8 mph / 12.5 km/h", "7.8", "mph", "12.5 km/h");
        parts("25.0 mph", "25.0", "mph", "");
        parts("NaN mph", "NaN mph", "", "");
        parts("12", "12", "", "");
    }

    private static void span(String value, int start, int end) {
        int[] found = unitSpan(value);
        checks++;
        if (start < 0 ? found != null : found == null || found[0] != start || found[1] != end)
            throw new AssertionError(value + ": " + Arrays.toString(found));
    }

    private static void parts(String formatted, String amount, String unit, String secondary) {
        same(Arrays.asList(amount, unit, secondary), Arrays.asList(gaugeParts(formatted)));
    }

    private static void same(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }
}
