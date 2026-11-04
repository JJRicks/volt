package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for B8 (the foreground service): the English ScooterService builds today for
 * every sentence that becomes a template, plural, choice or format row.
 *
 * ScooterService is an Android Service, so a plain JVM test cannot run it. Each method below is the
 * expression of the merge base (de86699) copied token for token, with the service state it reads
 * passed in as parameters; the comment names the base line. B8's own suite (ServiceTextTest) renders
 * the catalog calls that replace each one and compares them with these copies for every condition,
 * so this file, frozen by G5, is the reference. The pins in main() spell the English out by hand and
 * run under several default locales: the base text never depends on the phone's locale.
 */
public final class ServiceEnglishTest {
    private static int checks;

    private ServiceEnglishTest() {}

    /** ScooterService.java:350, chargeCaptureStatus() while a charge recording reconnects. */
    static String reconnecting(long remainingMs) {
        return "Reconnecting · "+Math.max(1,(remainingMs+999)/1000)+"s";
    }

    /** ScooterService.java:351-353, chargeCaptureStatus() while a charge recording runs. */
    static String recordingStatus(boolean stock, boolean chargerPaused, boolean paused, int sampleCount) {
        return (stock?"Stock charger":"High-speed charger")+" · "
                +(chargerPaused?"charger stopped · waiting for it to resume"
                        :paused?"paused until the scheduled start":sampleCount+" local samples");
    }

    /** ScooterService.java:365-366, chargeLearningSummary() once the saved recordings are loaded. */
    static String learningSummary(ChargeSessionStore.Charger charger, int qualified, boolean noSnapshot, int sessions) {
        return (charger==null?"Not applied until the charger is known":qualified+" qualified "+(charger==ChargeSessionStore.Charger.HIGH_SPEED?"high-speed":"stock")+" charge"+(qualified==1?"":"s"))
                +" · "+(noSnapshot?0:sessions)+" recordings stored";
    }

    /** ScooterService.java:508-510, the status a finished charge recording leaves. */
    static String stopped(ChargeSessionStore.Stop reason) {
        return reason==ChargeSessionStore.Stop.COMPLETE?"Charge recording saved · battery reached 100%"
                :reason==ChargeSessionStore.Stop.STOPPED?"Charge recording saved":reason==ChargeSessionStore.Stop.ERROR?"Charge recording stopped after a storage error"
                :"Charge recording stopped · "+reason.name().toLowerCase(Locale.ROOT).replace('_',' ');
    }

    /** ScooterService.java:576-579, missedStartText(): time is DisplayTime.clock of the planned start. */
    static String missedStart(boolean doneBy, boolean test, String time, int soc, int armedSoc, boolean inApp) {
        return (doneBy?"Charging was set to start at "+time:test?"The schedule test set it to resume at "+time:"It was set for "+time)
                +", and the battery is still at "+soc+"% ("+armedSoc+(test?"% at the test). ":"% when you set it). ")
                +"Check that the charger is plugged in and its light turns red at the start time. "
                +(inApp?"Volt can turn the schedule off so charging starts now.":"Open Volt to turn the schedule off so charging starts now.");
    }

    /** ScooterService.java:665-667, the text of the notice after Charge done by armed (or not) from measuring. */
    static String armed(boolean armed, boolean measured, String start, String done, String result) {
        return armed?(measured?"Measured this charge. ":"Planned for a fresh charge. ")+"Charging resumes at "
                +start+" to finish by "+done+"."
                :result==null?"Charge done by is off.":result+".";
    }

    /** ScooterService.java:674, endChargeMeasure(). */
    static String notSet(String reason) {
        return reason+". Charge done by is off.";
    }

    /** ScooterService.java:769, the toast chargeDoneNotice() shows in sight. */
    static String doneToast(boolean set, String text) {
        return (set?"Charge done by is set. ":"Charge done by wasn't set. ")+text;
    }

    /** ScooterService.java:712, scheduleTestStatus() while the test waits for the start. */
    static String resumesIn(long left) {
        long seconds=(left+999)/1000;return left>0?"Resumes in "+seconds/60+":"+String.format(Locale.ROOT,"%02d",seconds%60):"Waiting for charging to resume";
    }

    /** ScooterService.java:728. */
    static final String SET_CLOCK=" Volt set the scooter's clock right before this test, so the scooter may not be keeping the time it's given. Until that's solved, a smart plug is a safer timer. Report an issue with diagnostics so we can look into it.";

    /** ScooterService.java:729-749, scheduleTestText(), with the result's fields as parameters. */
    static String testText(ChargeScheduleTest.Outcome outcome, Integer offsetSeconds, boolean scheduleOff, String failure, String cleanup) {
        int seconds=offsetSeconds==null?0:Math.abs(offsetSeconds),minutes=Math.max(1,Math.round(seconds/60f));
        String about="about "+minutes+(minutes==1?" minute":" minutes");
        String text;
        switch(outcome){
            case PASSED:text="The scooter's clock and schedule work. Charging resumed "+(seconds==0?"at the start.":seconds+(seconds==1?" second ":" seconds ")+(offsetSeconds<0?"before":"after")+" the start.");break;
            case RESUMED_EARLY:text="Charging resumed "+about+" before the start, so the scooter's clock may be ahead by "+about+"."+SET_CLOCK;break;
            case RESUMED_LATE:text="Charging resumed "+about+" after the start, so the scooter's clock may be behind by "+about+"."+SET_CLOCK;break;
            case NO_PAUSE:text="The scooter didn't pause. Its clock may be ahead, or it ignored the schedule."+SET_CLOCK;break;
            case NO_RESUME:text="Charging was still paused 5 minutes after the start. The scooter's clock may be behind by more than 5 minutes, or its schedule didn't release. "
                    +"If the charger was unplugged, test again."+SET_CLOCK;break;
            case LIMIT_REACHED:text="The battery reached its charge limit during the test, so it couldn't tell. Test again further below the limit.";break;
            case INTERRUPTED:return "The test was interrupted. Check that charging resumed. If it didn't, turn Scheduled charging off.";
            case CANCELLED:text="";break;
            default:text="The test couldn't start"+(failure==null?".":": "+failure+".");
        }
        if(!scheduleOff)text+=" Volt couldn't confirm turning the schedule off. Turn Scheduled charging off so charging continues.";
        else if(cleanup!=null)text+=" "+cleanup+".";
        return text.trim();
    }

    /** ScooterService.java:1236, the automatic-stop notice's text. */
    static String autoStopped(String label) {
        return label==null||label.isEmpty()?"Bluetooth lost while parked":label+" · Bluetooth lost while parked";
    }

    /** ScooterService.java:1909, the connection notice while Charge done by measures (not yet arming). */
    static String measuringText(int minutesLeft) {
        return "Sets the schedule within "+minutesLeft+" min · keep the charger connected";
    }

    /** ScooterService.java:1910, the connection notice while a schedule test runs. */
    static String testingText(String testStatus) {
        return testStatus+" · keep the phone near the scooter";
    }

    /** ScooterService.java:1952-1953, connectedText(). */
    static String connected(String profileName, Double battery) {
        String name = profileName == null ? "Scooter" : profileName;
        return battery == null ? name : name + " · " + Math.round(battery) + "%";
    }

    /** ScooterService.java:1958-1960, rideDistanceText() once the odometer has moved. */
    static String distance(double distanceKm, String units) {
        String km = String.format(Locale.US, "%.1f km", distanceKm), mi = String.format(Locale.US, "%.1f mi", distanceKm / 1.609344);
        return "km".equals(units) ? km : "dual".equals(units) ? mi + " / " + km : mi;
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR"), Locale.forLanguageTag("ar-EG-u-nu-arab"), Locale.forLanguageTag("th-TH-u-nu-thai")}) {
                Locale.setDefault(phone);
                pins(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("ServiceEnglishTest: " + checks + " checks passed (ScooterService English under 7 phone locales)");
    }

    private static void pins(Locale phone) {
        equal("Reconnecting · 1s", reconnecting(0), phone);
        equal("Reconnecting · 1s", reconnecting(-5000), phone);
        equal("Reconnecting · 46s", reconnecting(45_001), phone);
        equal("Reconnecting · 1200s", reconnecting(1_200_000), phone);

        equal("Stock charger · charger stopped · waiting for it to resume", recordingStatus(true, true, true, 3), phone);
        equal("High-speed charger · paused until the scheduled start", recordingStatus(false, false, true, 3), phone);
        equal("Stock charger · 1 local samples", recordingStatus(true, false, false, 1), phone);
        equal("High-speed charger · 12345 local samples", recordingStatus(false, false, false, 12345), phone);

        equal("Not applied until the charger is known · 0 recordings stored", learningSummary(null, 0, true, 9), phone);
        equal("1 qualified high-speed charge · 1 recordings stored", learningSummary(ChargeSessionStore.Charger.HIGH_SPEED, 1, false, 1), phone);
        equal("0 qualified stock charges · 2 recordings stored", learningSummary(ChargeSessionStore.Charger.STOCK, 0, false, 2), phone);
        equal("21 qualified stock charges · 1000 recordings stored", learningSummary(ChargeSessionStore.Charger.STOCK, 21, false, 1000), phone);

        equal("Charge recording saved · battery reached 100%", stopped(ChargeSessionStore.Stop.COMPLETE), phone);
        equal("Charge recording saved", stopped(ChargeSessionStore.Stop.STOPPED), phone);
        equal("Charge recording stopped after a storage error", stopped(ChargeSessionStore.Stop.ERROR), phone);
        equal("Charge recording stopped · profile changed", stopped(ChargeSessionStore.Stop.PROFILE_CHANGED), phone);
        equal("Charge recording stopped · recovery timeout", stopped(ChargeSessionStore.Stop.RECOVERY_TIMEOUT), phone);
        equal("Charge recording stopped · identity changed", stopped(ChargeSessionStore.Stop.IDENTITY_CHANGED), phone);
        equal("Charge recording stopped · limit", stopped(ChargeSessionStore.Stop.LIMIT), phone);

        equal("Charging was set to start at 1:00 AM, and the battery is still at 40% (38% when you set it). Check that the charger "
                + "is plugged in and its light turns red at the start time. Volt can turn the schedule off so charging starts now.",
                missedStart(true, false, "1:00 AM", 40, 38, true), phone);
        equal("The schedule test set it to resume at 01:00, and the battery is still at 5% (5% at the test). Check that the charger "
                + "is plugged in and its light turns red at the start time. Open Volt to turn the schedule off so charging starts now.",
                missedStart(false, true, "01:00", 5, 5, false), phone);
        equal("It was set for 23:30, and the battery is still at 100% (0% when you set it). Check that the charger is plugged in "
                + "and its light turns red at the start time. Volt can turn the schedule off so charging starts now.",
                missedStart(false, false, "23:30", 100, 0, true), phone);
        equal("Charging was set to start at 7:05 PM, and the battery is still at 61% (60% at the test). Check that the charger "
                + "is plugged in and its light turns red at the start time. Open Volt to turn the schedule off so charging starts now.",
                missedStart(true, true, "7:05 PM", 61, 60, false), phone);

        equal("Measured this charge. Charging resumes at 2:10 AM to finish by 6:00 AM.", armed(true, true, "2:10 AM", "6:00 AM", "x"), phone);
        equal("Planned for a fresh charge. Charging resumes at 02:10 to finish by 06:00.", armed(true, false, "02:10", "06:00", null), phone);
        equal("Charge done by is off.", armed(false, true, "a", "b", null), phone);
        equal("Connect your scooter first.", armed(false, false, "a", "b", "Connect your scooter first"), phone);
        equal("Bluetooth permission was removed while measuring. Charge done by is off.",
                notSet("Bluetooth permission was removed while measuring"), phone);
        equal("Charge done by is set. Measured this charge.", doneToast(true, "Measured this charge."), phone);
        equal("Charge done by wasn't set. Volt closed while measuring. Charge done by is off.",
                doneToast(false, "Volt closed while measuring. Charge done by is off."), phone);

        equal("Resumes in 0:01", resumesIn(1), phone);
        equal("Resumes in 2:00", resumesIn(119_001), phone);
        equal("Resumes in 12:05", resumesIn(725_000), phone);
        equal("Waiting for charging to resume", resumesIn(0), phone);
        equal("Waiting for charging to resume", resumesIn(-999), phone);

        String setClock = "Volt set the scooter's clock right before this test, so the scooter may not be keeping the time it's given. "
                + "Until that's solved, a smart plug is a safer timer. Report an issue with diagnostics so we can look into it.";
        equal("The scooter's clock and schedule work. Charging resumed at the start.",
                testText(ChargeScheduleTest.Outcome.PASSED, 0, true, null, null), phone);
        equal("The scooter's clock and schedule work. Charging resumed at the start.",
                testText(ChargeScheduleTest.Outcome.PASSED, null, true, null, null), phone);
        equal("The scooter's clock and schedule work. Charging resumed 1 second before the start.",
                testText(ChargeScheduleTest.Outcome.PASSED, -1, true, null, null), phone);
        equal("The scooter's clock and schedule work. Charging resumed 21 seconds after the start. Charge start not restored.",
                testText(ChargeScheduleTest.Outcome.PASSED, 21, true, null, "Charge start not restored"), phone);
        equal("Charging resumed about 1 minute before the start, so the scooter's clock may be ahead by about 1 minute. " + setClock,
                testText(ChargeScheduleTest.Outcome.RESUMED_EARLY, -45, true, null, null), phone);
        equal("Charging resumed about 1 minute after the start, so the scooter's clock may be behind by about 1 minute. " + setClock,
                testText(ChargeScheduleTest.Outcome.RESUMED_LATE, 30, true, null, null), phone);
        equal("Charging resumed about 3 minutes after the start, so the scooter's clock may be behind by about 3 minutes. " + setClock
                + " Volt couldn't confirm turning the schedule off. Turn Scheduled charging off so charging continues.",
                testText(ChargeScheduleTest.Outcome.RESUMED_LATE, 180, false, null, null), phone);
        equal("Charging resumed about 21 minutes before the start, so the scooter's clock may be ahead by about 21 minutes. " + setClock,
                testText(ChargeScheduleTest.Outcome.RESUMED_EARLY, -1260, true, null, null), phone);
        equal("The scooter didn't pause. Its clock may be ahead, or it ignored the schedule. " + setClock,
                testText(ChargeScheduleTest.Outcome.NO_PAUSE, null, true, null, null), phone);
        equal("Charging was still paused 5 minutes after the start. The scooter's clock may be behind by more than 5 minutes, or its "
                + "schedule didn't release. If the charger was unplugged, test again. " + setClock,
                testText(ChargeScheduleTest.Outcome.NO_RESUME, null, true, null, null), phone);
        equal("The battery reached its charge limit during the test, so it couldn't tell. Test again further below the limit.",
                testText(ChargeScheduleTest.Outcome.LIMIT_REACHED, null, true, null, null), phone);
        equal("The test was interrupted. Check that charging resumed. If it didn't, turn Scheduled charging off.",
                testText(ChargeScheduleTest.Outcome.INTERRUPTED, null, false, null, "x"), phone);
        equal("", testText(ChargeScheduleTest.Outcome.CANCELLED, null, true, null, null), phone);
        equal("Charge start not restored.", testText(ChargeScheduleTest.Outcome.CANCELLED, null, true, null, "Charge start not restored"), phone);
        equal("The test couldn't start.", testText(ChargeScheduleTest.Outcome.NOT_STARTED, null, true, null, null), phone);
        equal("The test couldn't start: Connect your scooter first.",
                testText(ChargeScheduleTest.Outcome.NOT_STARTED, null, true, "Connect your scooter first", null), phone);

        equal("Bluetooth lost while parked", autoStopped(""), phone);
        equal("Bluetooth lost while parked", autoStopped(null), phone);
        equal("Commute · Bluetooth lost while parked", autoStopped("Commute"), phone);
        equal("Sets the schedule within 10 min · keep the charger connected", measuringText(10), phone);
        equal("Sets the schedule within 0 min · keep the charger connected", measuringText(0), phone);
        equal("Resumes in 1:59 · keep the phone near the scooter", testingText("Resumes in 1:59"), phone);

        equal("Scooter", connected(null, null), phone);
        equal("UT5 MAX · 82%", connected("UT5 MAX", 82.4), phone);
        equal("UT5 MAX · 83%", connected("UT5 MAX", 82.5), phone);
        equal("Scooter · 100%", connected(null, 100d), phone);
        equal("Scooter · 0%", connected(null, -0.2), phone);

        equal("7.8 mi", distance(12.5, "mi"), phone);
        equal("12.5 km", distance(12.5, "km"), phone);
        equal("7.8 mi / 12.5 km", distance(12.5, "dual"), phone);
        equal("0.0 mi", distance(0.04, null), phone);
        equal("1234.6 km", distance(1234.56, "km"), phone);
        equal("0.3 km", distance(0.25, "km"), phone);
        // Fmt.decimal(Locale.US, v, 1) is what the format rows pass; it must print like "%.1f" in Locale.US.
        for (double v : new double[]{0, 0.04, 0.05, 0.25, 0.35, 1.45, 12.5, 7.767, 1234.56, -0.04, 99999.95})
            equal(String.format(Locale.US, "%.1f", v), Fmt.decimal(Locale.US, v, 1), phone);
    }

    private static void equal(String expected, String actual, Locale phone) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError("under " + phone + ": expected <" + expected + "> but was <" + actual + ">");
    }
}
