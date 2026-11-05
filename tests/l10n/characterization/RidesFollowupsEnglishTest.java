package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for F5 (Rides follow-ups): the English RidesScreen builds at the merge base
 * (37713be), pinned before the batch.
 *
 * RidesScreen is an Android screen, so each method below is the base expression copied token for
 * token, with the state it reads passed in as parameters; the comment names the base line, and any
 * catalog call is written out as its English. F5's own suite (RidesFollowupsTest) compares the head
 * code with these copies over many values. The pins in main() spell the English out by hand and run
 * under several default locales: the base text never depends on them.
 */
public final class RidesFollowupsEnglishTest {
    private static int checks;

    private RidesFollowupsEnglishTest() {}

    /** RidesScreen.java:1625-1631, splitNumber(). */
    static String[] splitNumber(String value) {
        if (value == null) return null;
        int i = 0; while (i < value.length() && "+-−≈0123456789.,→".indexOf(value.charAt(i)) >= 0) i++;
        if (i == 0 || !Character.isDigit(value.charAt(i - 1))) return null;
        if (i == value.length()) return new String[]{value, ""};
        if (value.charAt(i) != ' ') return null;
        return new String[]{value.substring(0, i), value.substring(i + 1).trim()};
    }

    /** RidesScreen.java:1599-1603, the text of a tile's styled value (parts from splitNumber). */
    static String styled(String[] parts) { return parts[0] + (parts[1].isEmpty() ? "" : " " + parts[1]); }

    /** RidesScreen.java:1599, parts == null: a tile value shown as quiet text. */
    static boolean quiet(String value) {
        return value.isEmpty() || !hasDigit(value) && "+-−≈".indexOf(value.charAt(0)) < 0;
    }

    /** RidesScreen.java:1620-1623, hasDigit(). */
    static boolean hasDigit(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isDigit(value.charAt(i))) return true;
        return false;
    }

    /** RidesScreen.java:2113-2117, distance(); units is the "units" preference. */
    static String distance(Double km, String units) {
        if (km == null) return "Not measured";
        String metric = String.format(Locale.US, "%.2f %s", km, "km"), miles = String.format(Locale.US, "%.2f %s", km / RideStore.KM_PER_MILE, "mi");
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }

    /** RidesScreen.java:2118-2122, speed(). */
    static String speed(Double kmh, String units) {
        if (kmh == null) return "Not measured";
        String metric = String.format(Locale.US, "%.1f %s", kmh, "km/h"), miles = String.format(Locale.US, "%.1f %s", kmh / RideStore.KM_PER_MILE, "mph");
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }

    /** RidesScreen.java:2151, quantity(). */
    static String quantity(Double value, String unit, int decimals) { return value == null ? "Not measured" : String.format(Locale.US, "%." + decimals + "f %s", value, unit); }

    /** RidesScreen.java:1438-1441, height(). */
    static String height(Double value, String units) {
        if (value == null) return "—";
        return units.equals("km") ? quantity(value, "m", 0) : units.equals("dual") ? quantity(value * 3.280839895, "ft", 0) + " / " + quantity(value, "m", 0) : quantity(value * 3.280839895, "ft", 0);
    }

    /** RidesScreen.java:1274, the ascent / descent tile's value. */
    static String ascent(Double ascent, Double descent, String units) {
        return (ascent == null ? "" : "≈") + height(ascent, units) + " / " + height(descent, units);
    }

    /** RidesScreen.java:2123-2131, efficiency(), from the summary's four numbers. */
    static String efficiency(Double netKm, Double throughputKm, Double netMile, Double throughputMile, String units) {
        Double km = netKm == null ? throughputKm : netKm;
        Double mi = netMile == null ? throughputMile : netMile;
        if (km == null || mi == null) return "—";
        String value = units.equals("dual") ? quantity(mi, "Wh/mi", 1) + " / " + quantity(km, "Wh/km", 1)
                : units.equals("km") ? quantity(km, "Wh/km", 1) : quantity(mi, "Wh/mi", 1);
        return netKm == null ? value + " · throughput" : value + " · net";
    }

    /** RidesScreen.java:344-346, the live card's distance number. */
    static String liveNumber(Double km, String units) {
        String[] distance = splitNumber(distance(km, units));
        return distance == null ? "—" : distance[0];
    }

    /** RidesScreen.java:344-346, the live card's distance unit. */
    static String liveUnit(Double km, String units) {
        String[] distance = splitNumber(distance(km, units));
        return distance == null ? ("km".equals(units) ? "km" : "mi") : distance[1];
    }

    /** RidesScreen.java:1435, dash(). */
    static String dash(String value) { return "Not measured".equals(value) ? "—" : value; }

    /** RidesScreen.java:1420, a route point's power or battery cell. */
    static String pointCell(Double value, String unit) { return dash(quantity(value, unit, 0)); }

    /** RidesScreen.java:1560, valueRow()'s quiet style. */
    static boolean rowQuiet(String value) {
        return value == null || value.startsWith("Not measured") || value.equals("—") || value.equals("Insufficient data");
    }

    /** RidesScreen.java:1003, the voltage row. */
    static String voltageRow(Double low, Double high) { return quantity(low, "V", 2) + " / " + quantity(high, "V", 2); }

    /** RidesScreen.java:1119-1122, battery(). */
    static String battery(Double drop) {
        return drop == null || !Double.isFinite(drop) ? "Not measured"
                : Math.round(Math.abs(drop)) + "%";
    }

    /** RidesScreen.java:2156-2160, change(). */
    static String change(Double drop) {
        if (drop == null) return "Not measured";
        long points = Math.round(Math.abs(drop));
        return points == 0 ? "0%" : (drop >= 0 ? "−" : "+") + points + "%";
    }

    /** RidesScreen.java:328-333, sensorSummary(). */
    static String sensorSummary(boolean gps, boolean motion) {
        java.util.List<String> on = new java.util.ArrayList<String>(); if (gps) { on.add("GPS"); on.add("elevation"); } if (motion) on.add("motion");
        String sub = on.isEmpty() ? "Scooter readings only" : String.join(" · ", on);
        return Character.toUpperCase(sub.charAt(0)) + sub.substring(1);
    }

    /** RidesScreen.java:1428-1430, a route point cell's spoken text; i == 2 is the GPS cell. */
    static String pointSpoken(int i, String label, String value, boolean speedKnown, String accuracy) {
        String spoken = label + ", " + ("—".equals(value) ? "not measured" : value);
        if (i == 2 && speedKnown) spoken += ", accuracy ±" + accuracy;
        return spoken;
    }

    /** RidesScreen.java:604-605, the storage size; "" when nothing is saved. */
    static String storage(long bytes) {
        String text = bytes < 1000 * 1000 ? String.format(Locale.US, "%.0f %s", Math.max(1, bytes / 1000.0), "KB") : String.format(Locale.US, "%.1f %s", bytes / 1e6, "MB");
        return bytes <= 0 ? "" : text;
    }

    /** RidesScreen.java:480, the history count with the storage size. */
    static String historyCount(String count, String storageText) { return storageText.isEmpty() ? count : count + " · " + storageText; }

    /** RidesScreen.java:620, the compare row before two rides exist. */
    static String compareRow(int savedCount) { return "Compare two rides, " + savedCount + " of 2 saved rides"; }

    /** RidesScreen.java:641, the compare picker's count. */
    static String chosen(int picked) { return picked + " of 2 rides chosen"; }

    /** RidesScreen.java:1812-1823 and 1856, the legend's drawn titles and missing key. */
    static final String SPEED = "Speed", GRADE = "Grade", ACCEL = "Accel", ELEVATION = "Elev.", POWER = "Power", MISSING = "missing";

    /** RidesScreen.java:870 and 890, a chart series' line mark before its name. */
    static String mark(int index) { return index % 3 == 0 ? "━ " : index % 3 == 1 ? "┄ " : "⋯ "; }

    /** Icon colours as 'o' (ok), 'w' (orange) and 'd' (dim): gps, barometer, motion. */
    static String iconColours(String status, String live) {
        boolean capturing = !status.startsWith("Saving") && !status.contains("idle") && !status.contains("storage");
        char gps = !capturing ? 'd' : status.startsWith("GPS recording") ? 'o'
                : status.startsWith("GPS signal lost") || status.startsWith("Waiting for GPS") ? 'w' : 'd';
        char barometer = capturing && status.contains("barometer") ? 'o' : 'd';
        boolean motionOn = capturing && (status.contains("experimental motion") || status.contains("raw motion"));
        boolean samplesArriving = !live.isEmpty() && !live.startsWith("Waiting");
        char motion = !capturing ? 'd' : status.contains("motion sensor unavailable") ? 'w'
                : motionOn ? (samplesArriving ? 'o' : 'w') : 'd';
        return "" + gps + barometer + motion;
    }

    private static void eq(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    private static void pair(String number, String unit, String[] parts) {
        eq(number, parts[0]); eq(unit, parts[1]);
    }

    private static void pins() {
        pair("6.80", "mi", splitNumber("6.80 mi")); pair("4.23", "mi / 6.80 km", splitNumber("4.23 mi / 6.80 km"));
        pair("≈12", "ft / 10 ft", splitNumber("≈12 ft / 10 ft")); pair("54.3", "Wh/mi · net", splitNumber("54.3 Wh/mi · net"));
        pair("-3.20", "mi", splitNumber("-3.20 mi"));
        for (String none : new String[]{"Not measured", "—", "— / —", "24m 05s", "5s", "42%", "NaN mi", "Infinity Wh", "-Infinity mi", "≈NaN ft / NaN ft"})
            eq("null", String.valueOf(splitNumber(none) == null ? null : "split"));
        eq("6.80 mi", styled(splitNumber("6.80 mi")));
        eq("Not measured", distance(null, "mi")); eq("1.24 mi", distance(2.0, "mi")); eq("2.00 km", distance(2.0, "km")); eq("1.24 mi / 2.00 km", distance(2.0, "dual"));
        eq("15.5 mph", speed(25.0, "mi")); eq("25.0 km/h", speed(25.0, "km")); eq("15.5 mph / 25.0 km/h", speed(25.0, "dual"));
        eq("Not measured", quantity(null, "Wh", 2)); eq("1.50 Wh", quantity(1.5, "Wh", 2)); eq("-3 W", quantity(-3.2, "W", 0)); eq("75 %", quantity(75.0, "%", 0));
        eq("—", height(null, "mi")); eq("10 ft", height(3.0, "mi")); eq("3 m", height(3.0, "km")); eq("10 ft / 3 m", height(3.0, "dual"));
        eq("≈10 ft / 7 ft", ascent(3.0, 2.0, "mi")); eq("— / —", ascent(null, null, "mi")); eq("≈10 ft / 3 m / 7 ft / 2 m", ascent(3.0, 2.0, "dual"));
        eq("54.3 Wh/mi · net", efficiency(33.7, null, 54.3, null, "mi")); eq("33.7 Wh/km · net", efficiency(33.7, null, 54.3, null, "km"));
        eq("54.3 Wh/mi / 33.7 Wh/km · throughput", efficiency(null, 33.7, null, 54.3, "dual")); eq("—", efficiency(null, null, null, null, "mi"));
        eq("1.24", liveNumber(2.0, "mi")); eq("mi", liveUnit(2.0, "mi")); eq("2.00", liveNumber(2.0, "km")); eq("km", liveUnit(2.0, "km"));
        eq("1.24", liveNumber(2.0, "dual")); eq("mi / 2.00 km", liveUnit(2.0, "dual"));
        eq("—", liveNumber(null, "km")); eq("km", liveUnit(null, "km")); eq("mi", liveUnit(null, "dual")); eq("—", liveNumber(Double.NaN, "mi")); eq("mi", liveUnit(Double.NaN, "mi"));
        eq("—", pointCell(null, "W")); eq("120 W", pointCell(120.0, "W")); eq("75 %", pointCell(75.0, "%"));
        eq("true", "" + rowQuiet("Not measured")); eq("true", "" + rowQuiet("Not measured / 3.00 V")); eq("false", "" + rowQuiet("3.00 V / Not measured")); eq("true", "" + rowQuiet("—")); eq("false", "" + rowQuiet("— / —"));
        eq("Not measured / Not measured", voltageRow(null, null)); eq("3.00 V / Not measured", voltageRow(3.0, null));
        eq("Not measured", battery(null)); eq("Not measured", battery(Double.NaN)); eq("6%", battery(6.2)); eq("3%", battery(-2.6)); eq("0%", battery(0.0));
        eq("Not measured", change(null)); eq("0%", change(0.2)); eq("−3%", change(3.0)); eq("+2%", change(-2.4)); eq("0%", change(Double.NaN));
        eq("GPS · elevation · motion", sensorSummary(true, true)); eq("GPS · elevation", sensorSummary(true, false)); eq("Motion", sensorSummary(false, true)); eq("Scooter readings only", sensorSummary(false, false));
        eq("GPS, 12.3 mph, accuracy ±12 ft", pointSpoken(2, "GPS", "12.3 mph", true, "12 ft")); eq("GPS, not measured", pointSpoken(2, "GPS", "—", false, "—")); eq("Scooter, 1 mph", pointSpoken(4, "Scooter", "1 mph", true, "9 ft"));
        eq("", storage(0)); eq("1 KB", storage(1)); eq("999 KB", storage(999_000)); eq("1.0 MB", storage(1_000_000)); eq("12.3 MB", storage(12_340_000));
        eq("2 rides · 1 KB", historyCount("2 rides", "1 KB")); eq("2 rides", historyCount("2 rides", ""));
        eq("Compare two rides, 0 of 2 saved rides", compareRow(0)); eq("Compare two rides, 1 of 2 saved rides", compareRow(1));
        eq("0 of 2 rides chosen", chosen(0)); eq("1 of 2 rides chosen", chosen(1)); eq("2 of 2 rides chosen", chosen(2));
        eq("Speed Grade Accel Elev. Power missing", String.join(" ", SPEED, GRADE, ACCEL, ELEVATION, POWER, MISSING));
        eq("━ ", mark(0)); eq("┄ ", mark(1)); eq("⋯ ", mark(2)); eq("━ ", mark(3));
        eq("ddd", iconColours("Saving phone sensors", "x")); eq("ddd", iconColours("Phone sensors idle", "x")); eq("ddd", iconColours("Phone sensor storage unavailable", "x"));
        eq("ood", iconColours("GPS recording · barometer", "")); eq("wdo", iconColours("GPS signal lost · experimental motion", "1 m/s²"));
        eq("wdw", iconColours("Waiting for GPS · raw motion", "Waiting for motion sensors")); eq("ddw", iconColours("GPS off · motion sensor unavailable", "")); eq("ddd", iconColours("Location is off", ""));
    }

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[] {Locale.US, Locale.GERMANY, new Locale("ar", "EG"), new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                pins();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("RidesFollowupsEnglishTest: " + checks + " checks passed");
    }
}
