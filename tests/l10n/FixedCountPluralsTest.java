package com.jjricks.volt;

import java.util.Locale;

/**
 * The fixed-count strings that became plurals so languages with several plural forms can follow the
 * number (Home, the service and Rides). English must print exactly what it printed before:
 * each method below is the head expression of its consumer, compared with the English the old
 * strings built (the characterization copies where one exists, the old concatenation otherwise) at
 * every quantity where an English or Slavic plural rule changes form.
 */
public final class FixedCountPluralsTest {
    private static int checks;
    private static final long[] COUNTS = {0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 100, 101, 111, 1234, 12345};

    private FixedCountPluralsTest() {}

    // ScooterService.chargeCaptureStatus() while recording, at head.
    static String recordingStatus(boolean stock, int sampleCount) {
        return stock ? T.serviceChargeStockSamplesCount(sampleCount, Fmt.integer(sampleCount))
                : T.serviceChargeFastSamplesCount(sampleCount, Fmt.integer(sampleCount));
    }

    // ScooterService.chargeLearningSummary() once loaded, at head.
    static String learningSummary(ChargeSessionStore.Charger charger, int qualified, Integer snapshotSessions) {
        int sessions = snapshotSessions == null ? 0 : snapshotSessions;
        return charger == null ? T.serviceChargeLearningUnknownCount(sessions, Fmt.integer(sessions))
                : charger == ChargeSessionStore.Charger.HIGH_SPEED ? T.serviceChargeLearningFastCount(qualified, Fmt.integer(qualified), T.serviceChargeRecordingsStored(sessions, Fmt.integer(sessions)))
                : T.serviceChargeLearningStockCount(qualified, Fmt.integer(qualified), T.serviceChargeRecordingsStored(sessions, Fmt.integer(sessions)));
    }

    // ScooterService.scheduleTestText()'s PASSED text, at head (schedule turned off, no cleanup).
    static String passed(int offsetSeconds) {
        int seconds = Math.abs(offsetSeconds);
        String text = seconds == 0 ? T.serviceTestPassedOnTime() : (offsetSeconds < 0 ? T.serviceTestPassedBeforeCount(seconds, Fmt.integer(seconds)) : T.serviceTestPassedAfterCount(seconds, Fmt.integer(seconds)));
        return text.trim();
    }

    // RidesScreen.buildOverview()'s battery description, at head.
    static String battery(boolean batteryGain, double batteryDropPercent) {
        return (batteryGain ? T.ridesBatteryGainDescriptionCount(Math.round(Math.abs(batteryDropPercent)), Math.round(Math.abs(batteryDropPercent)))
                : T.ridesBatteryDropDescriptionCount(Math.round(Math.abs(batteryDropPercent)), Math.round(Math.abs(batteryDropPercent))));
    }

    // DashboardScreen's charging hero stage description, at head.
    static String hero(double level, boolean hasLimit, double limit) {
        return Double.isNaN(level)
                ? (hasLimit ? T.dashboardHeroChargeNotReportedLimitCount(Math.round(limit), Fmt.integer(Math.round(limit))) : T.dashboardHeroChargeNotReported())
                : (hasLimit ? T.dashboardHeroChargeLimitCount(Math.round(level), Fmt.integer(Math.round(level)), T.dashboardHeroLimitPercent(Math.round(limit), Fmt.integer(Math.round(limit)))) : T.dashboardHeroChargeCount(Math.round(level), Fmt.integer(Math.round(level))));
    }

    // DashboardScreen's battery overview spoken text, at head (level not null).
    static String overview(double level, String line, String when) {
        String amount = Long.toString(Math.round(level));
        boolean seen = when != null;
        return (line.isEmpty()
                ? (seen ? M.dashboardOverviewPercentSeenCount(Math.round(level), amount, when) : M.dashboardOverviewPercentCount(Math.round(level), amount))
                : (seen ? M.dashboardOverviewPercentLineSeenCount(Math.round(level), amount, line, when) : M.dashboardOverviewPercentLineCount(Math.round(level), amount, line))).toString();
    }

    // DashboardScreen's battery cell description, at head (percent not NaN).
    static String cell(double percent, String detail, boolean charging, boolean live) {
        return detail.isEmpty()
                ? (charging
                    ? (live ? T.dashboardCellPercentChargingCount(Math.round(percent), Fmt.integer(Math.round(percent)))
                            : T.dashboardCellPercentChargingStaleCount(Math.round(percent), Fmt.integer(Math.round(percent))))
                    : (live ? T.dashboardCellPercentCount(Math.round(percent), Fmt.integer(Math.round(percent)))
                            : T.dashboardCellPercentStaleCount(Math.round(percent), Fmt.integer(Math.round(percent)))))
                : (charging
                    ? (live ? T.dashboardCellPercentLineChargingCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail)
                            : T.dashboardCellPercentLineChargingStaleCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail))
                    : (live ? T.dashboardCellPercentLineCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail)
                            : T.dashboardCellPercentLineStaleCount(Math.round(percent), Fmt.integer(Math.round(percent)), detail)));
    }

    // The battery cell description before the plurals, through the old strings.
    static String oldCell(double percent, String detail, boolean charging, boolean live) {
        String level = Fmt.integer(Math.round(percent));
        return detail.isEmpty()
                ? (charging ? (live ? T.dashboardCellPercentCharging(level) : T.dashboardCellPercentChargingStale(level))
                    : (live ? T.dashboardCellPercent(level) : T.dashboardCellPercentStale(level)))
                : (charging ? (live ? T.dashboardCellPercentLineCharging(level, detail) : T.dashboardCellPercentLineChargingStale(level, detail))
                    : (live ? T.dashboardCellPercentLine(level, detail) : T.dashboardCellPercentLineStale(level, detail)));
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("pl", "PL"), new Locale("ru", "RU")}) {
                Locale.setDefault(phone);
                run();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("FixedCountPluralsTest: " + checks + " checks passed (fixed-count plurals keep their English)");
    }

    private static void run() {
        for (long n : COUNTS) {
            int i = (int) n;
            for (boolean stock : new boolean[]{true, false})
                same(ServiceEnglishTest.recordingStatus(stock, false, false, i), recordingStatus(stock, i), "recording " + n);
            for (ChargeSessionStore.Charger charger : new ChargeSessionStore.Charger[]{null, ChargeSessionStore.Charger.HIGH_SPEED, ChargeSessionStore.Charger.STOCK})
                for (int qualified : new int[]{0, 1, 2, 5, 21}) {
                    same(ServiceEnglishTest.learningSummary(charger, qualified, false, i), learningSummary(charger, qualified, i), "learning " + qualified + "/" + n);
                    same(ServiceEnglishTest.learningSummary(charger, qualified, true, i), learningSummary(charger, qualified, null), "learning no snapshot " + qualified);
                }
            for (int sign : new int[]{-1, 1})
                same(ServiceEnglishTest.testText(ChargeScheduleTest.Outcome.PASSED, sign * i, true, null, null), passed(sign * i), "passed " + sign * n);
            for (boolean gain : new boolean[]{true, false})
                for (double delta : new double[]{n, n + 0.4, n - 0.6, -n})
                    same(RidesEnglishTest.battery(gain, delta), battery(gain, delta), "battery " + delta);
        }
        for (int whole = 0; whole <= 100; whole++)
            for (double level : new double[]{whole, whole + 0.4, whole - 0.5}) {
                if (level < 0 || level > 100) continue;
                long shown = Math.round(level);
                for (double limit : new double[]{1, 21, 50, 80, 100}) {
                    same("Charge " + shown + " percent", hero(level, false, limit), "hero " + level);
                    same("Charge " + shown + " percent, charge limit " + Math.round(limit) + " percent", hero(level, true, limit), "hero limit " + level);
                    same("Charge not reported, charge limit " + Math.round(limit) + " percent", hero(Double.NaN, true, limit), "hero not reported " + limit);
                }
                for (String line : new String[]{"", "about 31 mi left", "Charging"})
                    for (String when : new String[]{null, "today 14:05"})
                        same("Battery " + shown + " percent" + (line.isEmpty() ? "" : ", " + line) + (when == null ? "" : ", last seen " + when),
                                overview(level, line, when), "overview " + level);
                for (String detail : new String[]{"", "Full in 40 min"})
                    for (boolean charging : new boolean[]{false, true})
                        for (boolean live : new boolean[]{false, true})
                            same("Battery " + shown + " percent" + (detail.isEmpty() ? "" : ", " + detail) + (charging ? ", charging" : "")
                                    + (live ? "" : ", last seen reading"), cell(level, detail, charging, live), "cell " + level);
                for (String detail : new String[]{"", "Full in 40 min"})
                    for (boolean charging : new boolean[]{false, true})
                        for (boolean live : new boolean[]{false, true})
                            same(oldCell(level, detail, charging, live), cell(level, detail, charging, live), "old cell " + level);
            }
    }

    private static void same(String expected, String actual, String what) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}
