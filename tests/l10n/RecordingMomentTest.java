package com.jjricks.volt;

import java.util.Locale;

/**
 * Clean Home's recording moment, the Ride clock (Ollie's mock-up, Nov 24 2026), in English: its words and values as RecordingView
 * shows them. The range cell follows the scooter's range source (RangeSource.learnedKm, the choice
 * ScooterService.dashboardReadings makes for the parked card too) and the units setting, from the same reading and
 * rounding as the parked card's "about 31 mi left" (HubText.recordingRangeSpoken is that card's spoken form), marked as an
 * estimate as riding mode's range chips mark one. The time is riding mode's REC pill's and the Ride row's, and a lost
 * link uses the pill's words.
 */
public final class RecordingMomentTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            range();
            words();
            spoken();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RecordingMomentTest: " + checks + " checks");
    }

    /** The Range cell under both range sources and the three unit settings. */
    private static void range() {
        double scooterKm = 37.2, learnedKm = 11;
        // The scooter's own figure, as the parked card shows it by default.
        Double source = RangeSource.learnedKm(false, false, 73d, learnedKm);
        double shown = source == null ? scooterKm : source;
        equal("≈ 23 mi", HubText.recordingRangeValue(shown, "mi"), "scooter source, miles");
        equal("about 23 mi", HubText.recordingRangeSpoken(shown, null, "mi").render(), "the parked card's figure, the same 23");
        equal("≈ 37 km", HubText.recordingRangeValue(shown, "km"), "scooter source, km");
        equal("≈ 23 mi / 37 km", HubText.recordingRangeValue(shown, "dual"), "scooter source, both units");
        equal("about 23 mi / 37 km", HubText.recordingRangeSpoken(shown, null, "dual").render(), "both units, as the parked card says them");
        // Learned: Volt's estimate at this battery reading replaces it, in the same units.
        source = RangeSource.learnedKm(true, false, 73d, learnedKm);
        shown = source == null ? scooterKm : source;
        equal("≈ 7 mi", HubText.recordingRangeValue(shown, "mi"), "learned source, miles");
        equal("≈ 11 km", HubText.recordingRangeValue(shown, "km"), "learned source, km");
        equal("≈ 7 mi / 11 km", HubText.recordingRangeValue(shown, "dual"), "learned source, both units");
        // In simulation the learned choice keeps the scooter's figure, as the parked card does.
        source = RangeSource.learnedKm(true, true, 73d, learnedKm);
        equal("≈ 23 mi", HubText.recordingRangeValue(source == null ? scooterKm : source, "mi"), "learned in simulation: the scooter's");
        check(HubText.recordingRangeValue(null, "mi") == null && HubText.recordingRangeValue(Double.NaN, "km") == null, "no reading: none (a dash)");
        // The value keeps its number and unit apart, for the small muted unit.
        Formatted dual = HubText.recordingRangeValue(37.2, "dual");
        check(dual.numbers() == 2 && dual.units() == 2 && "23".contentEquals(dual.subSequence(dual.numberStart(0), dual.numberEnd(0)))
                && "mi".contentEquals(dual.subSequence(dual.unitStart(0), dual.unitEnd(0))), "number and unit ranges: " + dual);
        // Rounding as the parked card's: 12.87 km is 8 mi (8.0), 1.6 km is 1 mi.
        equal("≈ 8 mi", HubText.recordingRangeValue(12.87, "mi"), "rounds as the parked card");
        equal("≈ 1 mi", HubText.recordingRangeValue(1.6, "mi"), "a short range");
        // Exactly the parked card's figure, by construction: both take RangeFigure, the card wrapping it in "about".
        for (String units : new String[] {"mi", "km", "dual"})
            equal("about " + RangeFigure.of(37.2, units), HubText.recordingRangeSpoken(37.2, null, units).render(), "the card's words, " + units);
        // A reading with text but no number shows its text, as the parked card does, still marked as an estimate.
        equal("≈ " + DisplayUnits.metricText("30 km", "mi"), HubText.recordingRangeValue(null, "30 km", "mi"), "a text-only reading");
        equal("about " + DisplayUnits.metricText("30 km", "mi"), HubText.recordingRangeSpoken(null, "30 km", "mi").render(), "spoken as the card says it");
        // The short rung with both units: the first unit alone.
        equal("≈ 23 mi", HubText.recordingRangeShort(37.2, "dual"), "both units' short rung");
    }

    private static void words() {
        equal("Recording", HubText.recordingLabel().render(), "the head row's word, the Record quick action's");
        equal("Started 9:24 AM", HubText.recordingStarted("9:24 AM").render(), "the start time");
        equal("Speed, this ride", HubText.recordingSpeed(true).render(), "the ribbon's label");
        equal("Speed", HubText.recordingSpeed(false).render(), "its short rung");
        equal("16.8 mph now", HubText.recordingSpeedNow(DisplayUnits.speedQuantity(27.04, "mi")).render(), "the speed now in mph");
        equal("Trip", HubText.recordingTrip().render(), "Trip");
        equal("Battery", HubText.recordingBattery().render(), "Battery");
        equal("Range (est.)", HubText.recordingRange(true).render(), "the range's name, as the mock-up's");
        equal("Range", HubText.recordingRange(false).render(), "its short rung");
        equal("Open riding mode", HubText.recordingOpenRiding().render(), "the button");
        equal("Show the ride while recording", HubText.momentRecording().render(), "the switch in Settings › Home style");
        check(HubText.recordingProblem(RidingFocus.Link.CONNECTED, false) == null, "a good link: no problem words");
        equal("Reconnecting…", HubText.recordingProblem(RidingFocus.Link.RECONNECTING, false).render(), "reconnecting, riding mode's words");
        equal("Not connected", HubText.recordingProblem(RidingFocus.Link.NOT_CONNECTED, false).render(), "not connected, riding mode's words");
        equal("Scooter off", HubText.recordingProblem(RidingFocus.Link.CONNECTED, true).render(), "switched off, Home's words");
        // The look riding mode's REC pill takes, and the time it shows: the moment's dot and words follow them.
        check(RecordingPill.look(1000L, RidingFocus.Link.CONNECTED, null, false, false) == RecordingPill.Look.RECORDING
                && RecordingPill.look(1000L, RidingFocus.Link.RECONNECTING, null, false, false) == RecordingPill.Look.RECORDING_PROBLEM
                && RecordingPill.look(1000L, RidingFocus.Link.CONNECTED, null, false, true) == RecordingPill.Look.RECORDING_PROBLEM, "the pill's looks");
        equal("14:38", RidingFocus.clockText(878_400L), "the time as the Ride row and the REC pill write it");
        equal("1:02:48", RidingFocus.clockText(3_768_000L), "over an hour");
        equal("Recording · 14:38", HubText.rideRecording(RidingFocus.clockText(878_400L)).render(), "the Ride row's line, the same time");
    }

    private static void spoken() {
        equal("Recording for 14 minutes. Trip, 2.5 mi. Battery, 73%. Range (est.), about 23 mi. Opens the Ride page.",
                HubText.recordingSpoken(878_400L, RidingFocus.Link.CONNECTED, false, "Trip", "2.5 mi", "Battery", "73%",
                        "Range (est.)", "about 23 mi").render(), "the whole moment, once");
        // By the minute: the same words all through a minute, so the description is not set again every second.
        check(HubText.recordingTimeSpoken(840_000L, RidingFocus.Link.CONNECTED, false).equals(HubText.recordingTimeSpoken(899_999L, RidingFocus.Link.CONNECTED, false)),
                "the same words through the 14th minute");
        equal("Recording for less than a minute", HubText.recordingTimeSpoken(0, RidingFocus.Link.CONNECTED, false), "the first second");
        check(HubText.recordingTimeSpoken(0, RidingFocus.Link.CONNECTED, false).equals(HubText.recordingTimeSpoken(59_999L, RidingFocus.Link.CONNECTED, false)),
                "the same words through the first minute");
        equal("Recording for 1 minute", HubText.recordingTimeSpoken(60_000L, RidingFocus.Link.CONNECTED, false), "then whole minutes");
        equal("Recording for 72 minutes", HubText.recordingTimeSpoken(4_320_000L, RidingFocus.Link.CONNECTED, false), "past an hour, as the REC pill says it");
        equal("Recording for less than a minute, Reconnecting…. Battery, 73%. Opens the Ride page.",
                HubText.recordingSpoken(38_000L, RidingFocus.Link.RECONNECTING, false, "Trip", null, "Battery", "73%").render(),
                "a link problem, and a reading with no value left out");
        equal("Recording for 14 minutes, Not connected. Opens the Ride page.",
                HubText.recordingSpoken(878_400L, RidingFocus.Link.NOT_CONNECTED, false).render(), "not connected");
        equal("Recording for 14 minutes, Scooter off. Opens the Ride page.",
                HubText.recordingSpoken(878_400L, RidingFocus.Link.CONNECTED, true).render(), "switched off");
        // Saving paused (ScooterService.recordingError, storage full): the service's words after the time, as the Ride
        // page's live card shows them, in place of a link problem; the readings as before.
        String paused = "Paused: storage full";
        equal("Recording for 14 minutes, Paused: storage full", HubText.recordingTimeSpoken(878_400L, RidingFocus.Link.CONNECTED, false, paused), "saving paused");
        equal("Recording for 14 minutes, Paused: storage full", HubText.recordingTimeSpoken(878_400L, RidingFocus.Link.NOT_CONNECTED, true, paused),
                "paused, said in place of a link problem");
        equal("Recording for 14 minutes, Not connected", HubText.recordingTimeSpoken(878_400L, RidingFocus.Link.NOT_CONNECTED, false, null), "not paused: the link's words");
        equal("Recording for 14 minutes", HubText.recordingTimeSpoken(878_400L, RidingFocus.Link.CONNECTED, false, ""), "empty words: not paused");
        equal("Recording for 14 minutes, Paused: storage full. Trip, 2.5 mi. Battery, 73%. Opens the Ride page.",
                HubText.recordingSpoken(878_400L, RidingFocus.Link.CONNECTED, false, paused, new CharSequence[] {"Trip", "2.5 mi", "Battery", "73%", "Range (est.)", null}).render(),
                "the whole moment, paused");
        equal(HubText.recordingSpoken(878_400L, RidingFocus.Link.CONNECTED, false, "Trip", "2.5 mi").render(),
                HubText.recordingSpoken(878_400L, RidingFocus.Link.CONNECTED, false, null, new CharSequence[] {"Trip", "2.5 mi"}).render(), "not paused: as before");
    }

    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
