package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B15 (the Rides screen): the English RidesScreen builds at the merge base
 * (93c47a0), pinned before the batch.
 *
 * RidesScreen is an Android screen, so each method below is the base expression copied token for
 * token, with the state it reads passed in as parameters; the comment names the base line. B15's
 * own suite (RidesTextTest) renders the catalog calls that replace each one and compares them with
 * these copies, so this file, frozen by G5, is the reference. The pins in main() spell the English
 * out by hand and run under several default locales: the base text never depends on them.
 *
 * phoneStatus() is the base text that ScooterService.phoneStatus() and PhoneRideRecorder.status()
 * give the sensor icons' description (RidesScreen:383), as a function of the recorder state that
 * PhoneRideRecorder.statusFlags() reports.
 */
public final class RidesEnglishTest {
    private static int checks;

    private RidesEnglishTest() {}

    /** RidesScreen.java:2112-2116, duration(). */
    static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%dh %02dm", seconds / 3600, seconds % 3600 / 60)
                : seconds >= 60 ? String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60) : seconds + "s";
    }

    /** RidesScreen.java:2111, percent(). */
    static String percent(double coverage) { return String.format(Locale.US, "%.0f%%", coverage * 100); }

    /** RidesScreen.java:2101, quantity(). */
    static String quantity(Double value, String unit, int decimals) { return value == null ? "Not measured" : String.format(Locale.US, "%." + decimals + "f %s", value, unit); }

    /** RidesScreen.java:2075-2078, distance(); units is the app_settings "units" preference. */
    static String distance(Double km, String units) {
        if (km == null) return "Not measured";
        String metric = String.format(Locale.US, "%.2f km", km), miles = String.format(Locale.US, "%.2f mi", km / RideStore.KM_PER_MILE);
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }

    /** RidesScreen.java:2080-2083, speed(). */
    static String speed(Double kmh, String units) {
        if (kmh == null) return "Not measured";
        String metric = String.format(Locale.US, "%.1f km/h", kmh), miles = String.format(Locale.US, "%.1f mph", kmh / RideStore.KM_PER_MILE);
        return units.equals("dual") ? miles + " / " + metric : units.equals("km") ? metric : miles;
    }

    /** RidesScreen.java:1415, accel(). */
    static String accel(Double value) { return value == null ? "—" : String.format(Locale.US, "%.2f m/s²", value); }

    /** RidesScreen.java:1416, acceleration(). */
    static String acceleration(Double value) { return value == null ? "—" : String.format(Locale.US, "%.2f m/s² (%.2f g)", value, value / 9.80665); }

    /** RidesScreen.java:325, the phone sensors row's value. */
    static String sensorsOn(int on) { return on == 0 ? "Off" : on + " on"; }

    /** RidesScreen.java:307, the notes dialog's title. */
    static String notesTitle(String label) { return "Notes for " + (label.isEmpty() ? "this ride" : label); }

    /** RidesScreen.java:338-339, the live card's title. */
    static String activeTitle(String error, String label) {
        String name = label.isEmpty() ? "Ride" : label;
        return (error == null ? "Recording · " : "Recording needs attention · ") + name;
    }

    /** RidesScreen.java:343, the elapsed clock's description. */
    static String elapsed(long elapsed) { return "Elapsed " + duration(elapsed); }

    /** RidesScreen.java:359, the gap chip. */
    static String gapChip(long gapCount) { return gapCount == 1 ? "1 gap" : gapCount + " gaps"; }

    /** RidesScreen.java:362, the coverage caption. */
    static String coverage(double electricalCoverage) { return percent(electricalCoverage) + " coverage"; }

    /** RidesScreen.java:363, the coverage caption's description. */
    static String coverageDescription(double electricalCoverage, long electricalCoverageMs, long elapsedMs) {
        return "Power coverage " + percent(electricalCoverage) + ", " + duration(electricalCoverageMs) + " observed of " + duration(elapsedMs);
    }

    /** RidesScreen.java:458-459, updateHistoryCount(). */
    static String historyCount(int savedCount, String storageText) {
        String count = savedCount == 0 ? "No saved rides" : savedCount + (savedCount == 1 ? " ride" : " rides");
        return storageText.isEmpty() ? count : count + " · " + storageText;
    }

    /** RidesScreen.java:583, measureStorage()'s size text. */
    static String storage(long bytes) {
        return bytes < 1000 * 1000 ? String.format(Locale.US, "%.0f KB", Math.max(1, bytes / 1000.0)) : String.format(Locale.US, "%.1f MB", bytes / 1e6);
    }

    /** RidesScreen.java:489 and 493, a history row's description. */
    static String historyRow(String spoken, boolean wide) {
        return wide ? spoken + ". Shows its details beside the list." : spoken + ". Opens ride details.";
    }

    /** RidesScreen.java:520, the detail pane's announcement. */
    static String showing(CharSequence title) { return "Showing " + title; }

    /** RidesScreen.java:599, the compare row before two rides exist. */
    static String compareRow(int savedCount) { return "Compare two rides, " + savedCount + " of 2 saved rides"; }

    /** RidesScreen.java:620, the compare picker's count. */
    static String chosen(int picked) { return picked + " of 2 rides chosen"; }

    /** RidesScreen.java:696-697, the battery tile's description. */
    static String battery(boolean batteryGain, double batteryDropPercent) {
        return (batteryGain ? "Battery gain, " : "Battery drop, ")
                + Math.round(Math.abs(batteryDropPercent)) + " percentage points";
    }

    /** RidesScreen.java:1051, 1052 and 1054, the direction split. */
    static String[] direction(long confirmedMs, long unconfirmedMs) {
        return new String[] {"Confirmed " + duration(confirmedMs), "Unconfirmed " + duration(unconfirmedMs),
                "Current direction confirmed for " + duration(confirmedMs) + ", unconfirmed for " + duration(unconfirmedMs)};
    }

    /** RidesScreen.java:1185, the motion switch's description. */
    static String experimental(String title) { return title + ", experimental"; }

    /** RidesScreen.java:1249, the GPS fixes caption. */
    static String fixes(long locationCount, long dropped) {
        return locationCount == 0 ? "No GPS fixes" : String.format(Locale.US, "%,d fixes", locationCount) + (dropped > 0 ? String.format(Locale.US, " · %,d dropped", dropped) : "");
    }

    /** RidesScreen.java:1263, the motion note. */
    static String placement(String placement, long motionSampleCount) {
        return ("mounted".equals(placement) ? "Mounted" : "Pocket") + " · " + String.format(Locale.US, "%,d", motionSampleCount) + " samples";
    }

    /** RidesScreen.java:1449, the delete dialog's title. */
    static String deleteTitle(String label) { return "Delete " + (label.isEmpty() ? "this ride" : label) + "?"; }

    /** RidesScreen.java:1485, the compare buttons' descriptions. */
    static String viewRoute(String name) { return "View route of " + name; }

    /** RidesScreen.java:1523, a compare row's description. */
    static String compareLine(String title, String spokenFirst, String spokenSecond) {
        return title + ": first ride " + spokenFirst + ", second ride " + spokenSecond;
    }

    /**
     * ScooterService.phoneStatus() with PhoneRideRecorder.status(): storageError is null or
     * "Phone sensor storage unavailable" (the only text PhoneRideRecorder sets), gpsText one of the
     * Gps texts.
     */
    static String phoneStatus(boolean saving, boolean noStore, String storageError, boolean closing, boolean active,
            String gpsText, boolean hasPressure, boolean motionEnabled, boolean hasLinear, boolean hasRaw) {
        if (saving) return "Saving phone sensors";
        if (noStore) return "Phone sensor storage unavailable";
        if (storageError != null) return storageError;
        if (closing) return "Saving phone sensors";
        if (!active) return "Phone sensors idle";
        return gpsText
                + (hasPressure ? " · barometer" : "")
                + (motionEnabled
                        ? (hasLinear
                                ? " · experimental motion"
                                : hasRaw ? " · raw motion" : " · motion sensor unavailable")
                        : "");
    }

    private static void eq(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    private static void pins() {
        eq("0s", duration(-5)); eq("59s", duration(59_999)); eq("1m 00s", duration(60_000)); eq("59m 59s", duration(3_599_000));
        eq("1h 00m", duration(3_600_000)); eq("12h 05m", duration(43_500_000));
        eq("0%", percent(0)); eq("80%", percent(.8)); eq("100%", percent(1)); eq("13%", percent(.125));
        eq("Not measured", quantity(null, "Wh", 2)); eq("1.50 Wh", quantity(1.5, "Wh", 2)); eq("-3 W", quantity(-3.2, "W", 0));
        eq("Not measured", distance(null, "mi")); eq("1.24 mi", distance(2.0, "mi")); eq("2.00 km", distance(2.0, "km"));
        eq("1.24 mi / 2.00 km", distance(2.0, "dual"));
        eq("15.5 mph", speed(25.0, "mi")); eq("25.0 km/h", speed(25.0, "km")); eq("15.5 mph / 25.0 km/h", speed(25.0, "dual"));
        eq("—", accel(null)); eq("-1.25 m/s²", accel(-1.25)); eq("9.81 m/s² (1.00 g)", acceleration(9.80665));
        eq("Off", sensorsOn(0)); eq("2 on", sensorsOn(2));
        eq("Notes for this ride", notesTitle("")); eq("Notes for Hill", notesTitle("Hill"));
        eq("Recording · Ride", activeTitle(null, "")); eq("Recording needs attention · Hill", activeTitle("x", "Hill"));
        eq("Elapsed 1m 05s", elapsed(65_000));
        eq("1 gap", gapChip(1)); eq("0 gaps", gapChip(0)); eq("3 gaps", gapChip(3));
        eq("80% coverage", coverage(.8));
        eq("Power coverage 80%, 4m 00s observed of 5m 00s", coverageDescription(.8, 240_000, 300_000));
        eq("No saved rides", historyCount(0, "")); eq("1 ride · 12 KB", historyCount(1, "12 KB")); eq("2 rides", historyCount(2, ""));
        eq("1 KB", storage(1)); eq("999 KB", storage(999_000)); eq("1.0 MB", storage(1_000_000)); eq("12.3 MB", storage(12_340_000));
        eq("Ride, May 1. Shows its details beside the list.", historyRow("Ride, May 1", true));
        eq("Ride, May 1. Opens ride details.", historyRow("Ride, May 1", false));
        eq("Showing Hill", showing("Hill"));
        eq("Compare two rides, 1 of 2 saved rides", compareRow(1));
        eq("0 of 2 rides chosen", chosen(0)); eq("1 of 2 rides chosen", chosen(1));
        eq("Battery gain, 3 percentage points", battery(true, -2.6)); eq("Battery drop, 1 percentage points", battery(false, 1.2));
        String[] split = direction(65_000, 3_600_000);
        eq("Confirmed 1m 05s", split[0]); eq("Unconfirmed 1h 00m", split[1]);
        eq("Current direction confirmed for 1m 05s, unconfirmed for 1h 00m", split[2]);
        eq("Motion sensors, experimental", experimental("Motion sensors"));
        eq("No GPS fixes", fixes(0, 3)); eq("1 fixes", fixes(1, 0)); eq("1,234 fixes · 1,001 dropped", fixes(1234, 1001));
        eq("Mounted · 1,200 samples", placement("mounted", 1200)); eq("Pocket · 1 samples", placement("pocket", 1));
        eq("Delete this ride?", deleteTitle("")); eq("Delete Hill?", deleteTitle("Hill"));
        eq("View route of Ride", viewRoute("Ride"));
        eq("Distance: first ride 1 km, second ride Not measured", compareLine("Distance", "1 km", "Not measured"));
        eq("Saving phone sensors", phoneStatus(true, false, null, false, true, "GPS recording", true, true, true, false));
        eq("Phone sensor storage unavailable", phoneStatus(false, true, null, false, true, "GPS off", false, false, false, false));
        eq("Phone sensors idle", phoneStatus(false, false, null, false, false, "GPS off", true, true, true, true));
        eq("GPS recording · barometer · experimental motion", phoneStatus(false, false, null, false, true, "GPS recording", true, true, true, false));
        eq("Waiting for GPS · raw motion", phoneStatus(false, false, null, false, true, "Waiting for GPS", false, true, false, true));
        eq("GPS signal lost · motion sensor unavailable", phoneStatus(false, false, null, false, true, "GPS signal lost", false, true, false, false));
        eq("GPS off", phoneStatus(false, false, null, false, true, "GPS off", false, false, true, true));
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
        System.out.println("RidesEnglishTest: " + checks + " checks passed");
    }
}
