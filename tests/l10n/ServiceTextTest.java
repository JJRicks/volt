package com.jjricks.volt;

import java.util.Locale;

/**
 * B8 (the foreground service), beyond what G1 and G2 prove row by row: every sentence ScooterService
 * now builds from the catalog, rendered through EnglishText exactly as the head code builds it, against
 * the merge base's expression (ServiceEnglishTest, frozen by G5) for every condition and a spread of
 * values. That covers what the ledger cannot: the "as" holes (Fmt.integer for the numbers, stopLabel for
 * the stop reason, T.serviceTestSetClock() for the deleted SET_CLOCK constant), the deferred status
 * messages after render, the appended sentences of the schedule test result, and the plural
 * service_test_about at every quantity. Each head expression below is copied from ScooterService.
 */
public final class ServiceTextTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"),
                    Locale.forLanguageTag("ar-EG-u-nu-arab"), Locale.forLanguageTag("th-TH-u-nu-thai")}) {
                Locale.setDefault(phone);
                run();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("ServiceTextTest: " + checks + " checks passed (ScooterService's catalog text against the base, 5 phone locales)");
    }

    private static final String[] TEXTS = {"", "x", "Connect your scooter first", "\u0001A", "%1$s", "50%", "a  b", "Commute · home"};

    private static void run() {
        for (long ms : new long[]{Long.MIN_VALUE / 4, -5000, -1, 0, 1, 999, 1000, 1001, 45_001, 1_200_000, Long.MAX_VALUE / 4})
            same(ServiceEnglishTest.reconnecting(ms), T.serviceChargeReconnecting(Fmt.integer(Math.max(1, (ms + 999) / 1000))), "reconnecting");
        for (boolean stock : new boolean[]{true, false})
            for (boolean chargerPaused : new boolean[]{true, false})
                for (boolean paused : new boolean[]{true, false})
                    for (int count : new int[]{0, 1, 2, 21, 12345, -1})
                        same(ServiceEnglishTest.recordingStatus(stock, chargerPaused, paused, count),
                                recordingStatus(stock, chargerPaused, paused, count), "recording status");
        for (ChargeSessionStore.Charger charger : new ChargeSessionStore.Charger[]{null, ChargeSessionStore.Charger.STOCK, ChargeSessionStore.Charger.HIGH_SPEED})
            for (int qualified : new int[]{0, 1, 2, 21, 100})
                for (boolean noSnapshot : new boolean[]{true, false})
                    for (int sessions : new int[]{0, 1, 2, 1000})
                        same(ServiceEnglishTest.learningSummary(charger, qualified, noSnapshot, sessions),
                                learningSummary(charger, qualified, noSnapshot ? null : sessions), "learning summary");
        for (ChargeSessionStore.Stop stop : ChargeSessionStore.Stop.values())
            same(ServiceEnglishTest.stopped(stop), stopped(stop).toString(), "stop status " + stop);
        for (String time : new String[]{"1:00 AM", "23:30", "%1$s"})
            for (int soc : new int[]{0, 5, 99, 100, -1})
                for (int armed : new int[]{0, 38, 100})
                    for (boolean doneBy : new boolean[]{true, false})
                        for (boolean test : new boolean[]{true, false})
                            for (boolean inApp : new boolean[]{true, false})
                                same(ServiceEnglishTest.missedStart(doneBy, test, time, soc, armed, inApp),
                                        missedStart(doneBy, test, time, soc, armed, inApp), "missed start");
        for (String result : new String[]{null, "", "Connect your scooter first", "%1$s"})
            for (boolean armed : new boolean[]{true, false})
                for (boolean measured : new boolean[]{true, false})
                    same(ServiceEnglishTest.armed(armed, measured, "2:10 AM", "6:00 AM", result),
                            armed ? (measured ? T.serviceMeasureMeasured("2:10 AM", "6:00 AM") : T.serviceMeasurePlanned("2:10 AM", "6:00 AM"))
                                    : result == null ? T.serviceMeasureOff() : T.serviceMeasureResult(result), "Charge done by armed");
        for (String text : TEXTS) {
            same(ServiceEnglishTest.notSet(text), T.serviceMeasureNotSet(text), "not set");
            same(ServiceEnglishTest.doneToast(true, text), T.serviceDoneToastSet(text), "done toast");
            same(ServiceEnglishTest.doneToast(false, text), T.serviceDoneToastNotSet(text), "done toast");
            same(ServiceEnglishTest.autoStopped(text), text.isEmpty() ? T.serviceRideBluetoothLost() : T.serviceRideBluetoothLostLabel(text), "auto stop");
            same(ServiceEnglishTest.testingText(text), T.serviceNoticeTestingText(text), "testing text");
            same(ServiceEnglishTest.connected(text, null), text, "connected, no battery");
        }
        same(ServiceEnglishTest.autoStopped(null), T.serviceRideBluetoothLost(), "auto stop, no ride");
        same(ServiceEnglishTest.notSet("Bluetooth permission was removed while measuring"),
                T.serviceMeasureNotSet(T.serviceMeasureBluetoothRemoved()), "permission removed");
        same(ServiceEnglishTest.doneToast(false, "Volt closed while measuring. Charge done by is off."),
                T.serviceDoneToastNotSet(T.serviceMeasureClosed()), "closed while measuring");
        for (long left : new long[]{-60_000, -1, 0, 1, 999, 1000, 1001, 59_999, 60_000, 119_001, 725_000, 6_000_000})
            same(ServiceEnglishTest.resumesIn(left), resumesIn(left), "resumes in");
        for (ChargeScheduleTest.Outcome outcome : ChargeScheduleTest.Outcome.values())
            for (Integer offset : new Integer[]{null, 0, 1, -1, 2, -29, 29, -30, 30, -45, 89, 90, -150, 180, -1260, 3600})
                for (boolean off : new boolean[]{true, false})
                    for (String failure : new String[]{null, "Connect your scooter first"})
                        for (String cleanup : new String[]{null, "Charge start not restored", "%1$s"}) {
                            same(ServiceEnglishTest.testText(outcome, offset, off, failure, cleanup), testText(outcome, offset, off, failure, cleanup),
                                    "test result " + outcome + " " + offset);
                        }
        for (long minutes : new long[]{0, 1, 2, 3, 4, 5, 11, 12, 21, 22, 25, 100, 101, 111, 1_000_000})
            same("about " + minutes + (minutes == 1 ? " minute" : " minutes"), T.serviceTestAbout(minutes, minutes), "about " + minutes);
        for (int minutes : new int[]{0, 1, 2, 10, 60})
            same(ServiceEnglishTest.measuringText(minutes), T.serviceNoticeMeasuringText(Fmt.integer(minutes)), "measuring text");
        for (String name : new String[]{null, "UT5 MAX", "%1$s", ""})
            for (Double battery : new Double[]{null, -0.2, 0d, 0.5, 82.4, 82.5, 99.5, 100d})
                same(ServiceEnglishTest.connected(name, battery), connected(name, battery), "connected");
        for (String units : new String[]{null, "mi", "km", "dual", "x"})
            for (double km : new double[]{0, 0.04, 0.05, 0.25, 1.609344, 12.5, 1234.56, 99999.95})
                same(ServiceEnglishTest.distance(km, units), distance(km, units), "distance");
        same("Battery is already at 100%", T.serviceChargeBatteryFull(), "battery full");
        same(ServiceEnglishTest.SET_CLOCK, T.serviceTestSetClock(), "set clock");
    }

    /** ScooterService.chargeCaptureStatus() at head, while recording. */
    private static String recordingStatus(boolean stock, boolean chargerPaused, boolean paused, int sampleCount) {
        return (stock ? (chargerPaused ? T.serviceChargeStockChargerPaused()
                        : paused ? T.serviceChargeStockSchedulePaused() : T.serviceChargeStockSamples(Fmt.integer(sampleCount)))
                : (chargerPaused ? T.serviceChargeFastChargerPaused()
                        : paused ? T.serviceChargeFastSchedulePaused() : T.serviceChargeFastSamples(Fmt.integer(sampleCount))));
    }

    /** ScooterService.chargeLearningSummary() at head; sessions null for no snapshot. */
    private static String learningSummary(ChargeSessionStore.Charger charger, int qualified, Integer snapshotSessions) {
        int sessions = snapshotSessions == null ? 0 : snapshotSessions;
        return charger == null ? T.serviceChargeLearningUnknown(Fmt.integer(sessions))
                : charger == ChargeSessionStore.Charger.HIGH_SPEED ? (qualified == 1 ? T.serviceChargeLearningFastOne(Fmt.integer(qualified), Fmt.integer(sessions))
                        : T.serviceChargeLearningFastOther(Fmt.integer(qualified), Fmt.integer(sessions)))
                : (qualified == 1 ? T.serviceChargeLearningStockOne(Fmt.integer(qualified), Fmt.integer(sessions))
                        : T.serviceChargeLearningStockOther(Fmt.integer(qualified), Fmt.integer(sessions)));
    }

    /** ScooterService.finishChargeCapture()'s status at head (a Msg kept in chargeStatus). */
    private static CharSequence stopped(ChargeSessionStore.Stop reason) {
        return reason == ChargeSessionStore.Stop.COMPLETE ? M.serviceChargeSavedFull()
                : reason == ChargeSessionStore.Stop.STOPPED ? M.serviceChargeSaved() : reason == ChargeSessionStore.Stop.ERROR ? M.serviceChargeStorageError()
                : M.serviceChargeStoppedReason(stopLabel(reason, reason.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
    }

    /** ScooterService.stopLabel at head. */
    private static CharSequence stopLabel(ChargeSessionStore.Stop reason, String english) {
        switch (reason) {
            case CHARGING_ENDED: return M.serviceChargeStopChargingEnded();
            case DISCONNECTED: return M.serviceChargeStopDisconnected();
            case PROFILE_CHANGED: return M.serviceChargeStopProfileChanged();
            case READINGS_STOPPED: return M.serviceChargeStopReadingsStopped();
            case LIMIT: return M.serviceChargeStopLimit();
            case PROCESS_ENDED: return M.serviceChargeStopProcessEnded();
            case INTERRUPTED: return M.serviceChargeStopInterrupted();
            case RECOVERY_TIMEOUT: return M.serviceChargeStopRecoveryTimeout();
            case CONNECTION_FAILED: return M.serviceChargeStopConnectionFailed();
            case BLUETOOTH_OFF: return M.serviceChargeStopBluetoothOff();
            case PERMISSION_LOST: return M.serviceChargeStopPermissionLost();
            case IDENTITY_CHANGED: return M.serviceChargeStopIdentityChanged();
            default: return english;
        }
    }

    /** ScooterService.missedStartText() at head. */
    private static String missedStart(boolean doneBy, boolean test, String time, int soc, int armedSoc, boolean inApp) {
        return doneBy ? (test ? (inApp ? T.serviceMissedDoneByTestDialog(time, Fmt.integer(soc), Fmt.integer(armedSoc))
                                : T.serviceMissedDoneByTestNotice(time, Fmt.integer(soc), Fmt.integer(armedSoc)))
                        : (inApp ? T.serviceMissedDoneByDialog(time, Fmt.integer(soc), Fmt.integer(armedSoc))
                                : T.serviceMissedDoneByNotice(time, Fmt.integer(soc), Fmt.integer(armedSoc))))
                : test ? (inApp ? T.serviceMissedTestDialog(time, Fmt.integer(soc), Fmt.integer(armedSoc))
                                : T.serviceMissedTestNotice(time, Fmt.integer(soc), Fmt.integer(armedSoc)))
                        : (inApp ? T.serviceMissedSetDialog(time, Fmt.integer(soc), Fmt.integer(armedSoc))
                                : T.serviceMissedSetNotice(time, Fmt.integer(soc), Fmt.integer(armedSoc)));
    }

    /** ScooterService.scheduleTestStatus() at head, while waiting for the start. */
    private static String resumesIn(long left) {
        long seconds = (left + 999) / 1000;
        return left > 0 ? T.serviceTestResumesIn(Fmt.integer(seconds / 60), String.format(Locale.ROOT, "%02d", seconds % 60)) : T.serviceTestWaiting();
    }

    /** ScooterService.scheduleTestText() at head. */
    private static String testText(ChargeScheduleTest.Outcome outcome, Integer offsetSeconds, boolean scheduleOff, String failure, String cleanup) {
        int seconds = offsetSeconds == null ? 0 : Math.abs(offsetSeconds), minutes = Math.max(1, Math.round(seconds / 60f));
        String about = T.serviceTestAbout(minutes, minutes);
        String text;
        switch (outcome) {
            case PASSED: text = seconds == 0 ? T.serviceTestPassedOnTime() : seconds == 1 ? (offsetSeconds < 0 ? T.serviceTestPassedSecondBefore(Fmt.integer(seconds)) : T.serviceTestPassedSecondAfter(Fmt.integer(seconds))) : (offsetSeconds < 0 ? T.serviceTestPassedSecondsBefore(Fmt.integer(seconds)) : T.serviceTestPassedSecondsAfter(Fmt.integer(seconds))); break;
            case RESUMED_EARLY: text = T.serviceTestEarly(about, about, T.serviceTestSetClock()); break;
            case RESUMED_LATE: text = T.serviceTestLate(about, about, T.serviceTestSetClock()); break;
            case NO_PAUSE: text = T.serviceTestNoPause(T.serviceTestSetClock()); break;
            case NO_RESUME: text = T.serviceTestNoResume(T.serviceTestSetClock()); break;
            case LIMIT_REACHED: text = T.serviceTestLimit(); break;
            case INTERRUPTED: return T.serviceTestInterrupted();
            case CANCELLED: text = ""; break;
            default: text = failure == null ? T.serviceTestNotStarted() : T.serviceTestNotStartedReason(failure);
        }
        if (!scheduleOff) text += T.serviceTestOffUnconfirmed();
        else if (cleanup != null) text += T.serviceTestCleanup(cleanup);
        return text.trim();
    }

    /** ScooterService.connectedText() at head. */
    private static String connected(String profileName, Double battery) {
        String name = profileName == null ? T.serviceNoticeScooter() : profileName;
        return battery == null ? name : T.serviceNoticeScooterBattery(name, Fmt.integer(Math.round(battery)));
    }

    /** ScooterService.rideDistanceText() at head. */
    private static String distance(double distanceKm, String units) {
        String km = T.fmtQuantity(Fmt.decimal(Locale.US, distanceKm, 1), T.unitsKm()), mi = T.fmtQuantity(Fmt.decimal(Locale.US, distanceKm / 1.609344, 1), T.unitsMi());
        return "km".equals(units) ? km : "dual".equals(units) ? T.fmtDual(mi, km) : mi;
    }

    private static void same(String base, CharSequence head, String what) {
        checks++;
        String shown = head == null ? null : head.toString();
        if (!base.equals(shown))
            throw new AssertionError(what + " under " + Locale.getDefault() + ": the base printed <" + base + "> but the catalog <" + shown + ">");
    }
}
