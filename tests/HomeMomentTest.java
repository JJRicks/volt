package com.jjricks.volt;

/** HomeMoment: what Clean Home's top area shows (hub layout plan 1.7, PR 6). */
public final class HomeMomentTest {
    private static int checks;
    private static final long MIN = 60_000L, END = 1_700_000_000_000L;

    public static void main(String[] args) {
        priority();
        window();
        dismissed();
        switches();
        recording();
        looking();
        keys();
        System.out.println("HomeMoment: " + checks + " checks passed");
    }

    private static int at(long now) { return HomeMoment.choose(true, false, true, false, "r1", END, null, now); }

    private static void priority() {
        require(HomeMoment.choose(true, false, true, false, null, 0, null, END) == HomeMoment.PARKED, "No ride, not charging: parked");
        require(HomeMoment.choose(true, true, true, false, "r1", END, null, END + MIN) == HomeMoment.CHARGING, "Charging wins over Just back");
        require(HomeMoment.choose(true, true, true, false, null, 0, null, END) == HomeMoment.CHARGING, "Charging shows by itself");
        require(HomeMoment.choose(true, false, true, false, "r1", END, null, END + MIN) == HomeMoment.JUST_BACK, "After unplugging Just back resumes inside its hour");
        require(HomeMoment.choose(true, false, true, true, "r1", END, null, END + MIN) == HomeMoment.PARKED, "Never while recording");
        require(HomeMoment.choose(true, true, true, true, "r1", END, null, END + MIN) == HomeMoment.CHARGING, "Charging still shows while recording");
    }

    private static void window() {
        require(at(END) == HomeMoment.JUST_BACK, "Right as the ride ends");
        require(at(END + 59 * MIN + 59_000L) == HomeMoment.JUST_BACK, "At 59:59");
        require(at(END + 60 * MIN) == HomeMoment.PARKED, "Gone at 60:00");
        require(at(END + 3 * 60 * MIN) == HomeMoment.PARKED, "Long after");
        require(at(END - 5 * MIN) == HomeMoment.JUST_BACK, "A ride end in the future (clock skew) counts as just ended");
        require(HomeMoment.choose(true, false, true, false, "r1", 0, null, END) == HomeMoment.PARKED, "No end time: parked");
        require(HomeMoment.choose(true, false, true, false, "", END, null, END) == HomeMoment.PARKED, "Empty ride id: parked");
        require(HomeMoment.minutesAgo(END, END + 12 * MIN + 30_000L) == 12, "Whole minutes ago");
        require(HomeMoment.minutesAgo(END, END - MIN) == 0 && HomeMoment.minutesAgo(END, END + 59_000L) == 0, "Under a minute or a future end is 0 (just now)");
    }

    private static void dismissed() {
        require(HomeMoment.choose(true, false, true, false, "r1", END, "r1", END + MIN) == HomeMoment.PARKED, "Closed: parked");
        require(HomeMoment.choose(true, false, true, false, "r2", END, "r1", END + MIN) == HomeMoment.JUST_BACK, "A newer ride shows again");
    }

    private static void switches() {
        require(HomeMoment.choose(false, true, true, false, "r1", END, null, END + MIN) == HomeMoment.JUST_BACK, "Charging switched off: Just back still shows");
        require(HomeMoment.choose(false, true, true, false, null, 0, null, END) == HomeMoment.PARKED, "Charging switched off: parked");
        require(HomeMoment.choose(true, false, false, false, "r1", END, null, END + MIN) == HomeMoment.PARKED, "Ride switched off: parked");
        require(HomeMoment.on(null) && HomeMoment.on(Boolean.TRUE) && HomeMoment.on("false"), "Absent or anything but false is on");
        require(!HomeMoment.on(Boolean.FALSE), "Only a stored false is off");
    }

    /**
     * The recording moment (the Ride clock; Ollie's mock-up, Nov 24 2026): while a ride records and its switch is on, after charging
     * and before Just back; with its switch off (or through the call without it) a recording shows the battery card.
     */
    private static void recording() {
        require(HomeMoment.choose(true, false, true, true, true, null, 0, null, END) == HomeMoment.RECORDING, "Recording: the recording moment");
        require(HomeMoment.choose(true, false, true, true, true, "r1", END, null, END + MIN) == HomeMoment.RECORDING,
                "Recording right after a ride: the recording, not Just back");
        require(HomeMoment.choose(true, true, true, true, true, "r1", END, null, END + MIN) == HomeMoment.CHARGING, "Charging still wins while recording");
        require(HomeMoment.choose(false, true, true, true, true, null, 0, null, END) == HomeMoment.RECORDING, "Charging switched off: the recording shows");
        require(HomeMoment.choose(true, false, false, true, true, "r1", END, null, END + MIN) == HomeMoment.PARKED, "Recording switched off: parked, as before");
        require(HomeMoment.choose(true, false, true, false, true, null, 0, null, END) == HomeMoment.RECORDING, "The ride switch does not hide the recording");
        require(HomeMoment.choose(true, false, true, true, false, "r1", END, null, END + MIN) == HomeMoment.JUST_BACK,
                "The recording stopped: Just back for the ride just saved");
        require(HomeMoment.choose(true, false, true, true, false, null, 0, null, END) == HomeMoment.PARKED, "Not recording, no ride: parked");
        require(HomeMoment.choose(true, false, true, true, "r1", END, null, END + MIN)
                == HomeMoment.choose(true, false, false, true, true, "r1", END, null, END + MIN), "The call without the switch is the switch off");
        require(HomeMoment.RECORDING_KEY.equals("home_moment_recording"), "The switch's key");
        // The change to Just back when the ride stops: no battery card in between while the ride just saved is read.
        require(HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.PARKED, "r2", "r1", 300), "Stopped, the saved ride not read yet: the recording stays");
        require(HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.JUST_BACK, "r2", "r1", 300), "Not an older ride's Just back either");
        require(!HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.JUST_BACK, "r2", "r2", 300), "Found: Just back for it at once");
        require(!HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.PARKED, "r2", null, HomeMoment.HANDOFF_MS), "Never past the hand-off's limit");
        require(!HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.PARKED, "r2", null, 30 * MIN),
                "A stop older than the hand-off (Home back from the background): nothing held");
        require(!HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.PARKED, "r2", null, -1), "No stop known: nothing held");
        require(!HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.CHARGING, "r2", null, 300), "Charging still takes over");
        require(!HomeMoment.holdRecording(HomeMoment.PARKED, HomeMoment.PARKED, "r2", null, 300), "Only the recording moment is held");
        require(!HomeMoment.holdRecording(HomeMoment.RECORDING, HomeMoment.PARKED, null, null, 300), "No ride ended: nothing to hold");
        require(HomeMoment.RECORDING != HomeMoment.PARKED && HomeMoment.RECORDING != HomeMoment.CHARGING && HomeMoment.RECORDING != HomeMoment.JUST_BACK,
                "A moment of its own");
    }

    /** When Clean looks at the saved rides again (PR 9). */
    private static void looking() {
        long t = 5_000_000L;
        require(HomeMoment.lookAgain(-1, t, false), "The first refresh looks");
        require(!HomeMoment.lookAgain(t, t + 1_000L, false), "A telemetry refresh soon after does not");
        require(HomeMoment.lookAgain(t, t + 1_000L, true), "Recording starting or stopping looks at once");
        require(!HomeMoment.lookAgain(t, t + HomeMoment.RIDES_RECHECK_MS - 1, false), "Not before the half minute");
        require(HomeMoment.lookAgain(t, t + HomeMoment.RIDES_RECHECK_MS, false), "Then again every half minute");
        require(HomeMoment.lookAgain(t, t - 1, false), "A clock that went back looks again");
    }

    private static void keys() {
        require(BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", HomeMoment.CHARGING_KEY))
                && BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings", HomeMoment.RIDE_KEY)), "Both switches travel as App preferences");
        BackupRegistry.Rule recording = BackupRegistry.rule("app_settings", HomeMoment.RECORDING_KEY);
        require(recording != null && BackupRegistry.PREFERENCES.equals(recording.category) && recording.type == Boolean.class && !recording.perProfile,
                "The recording moment's switch travels as App preferences too, a switch for the whole app");
        require(DiagnosticsSettings.KEYS.indexOf(HomeMoment.RECORDING_KEY) == DiagnosticsSettings.KEYS.indexOf(HomeMoment.RIDE_KEY) + 1,
                "The diagnostics settings list it next to the ride moment's switch");
        BackupRegistry.Rule dismissed = BackupRegistry.rule("app_settings", HomeMoment.DISMISSED_KEY);
        require(dismissed != null && dismissed.excluded(), "A closed card names a ride on this phone and stays here");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
