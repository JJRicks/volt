package com.jjricks.volt;

import java.util.Locale;

/**
 * Clean Home's Ride clock: the follow-up rules to the recording moment (#638/#639 review). While the service has paused
 * saving the ride (ScooterService.recordingPause) the moment takes the problem look and shows Home's own words for it,
 * full and short by the narrow-label ladder (RecordingFit.pausedHead), never the service's English literal, and its
 * description says the full words after the time; the ribbon's speed now follows riding mode's 6 s; the Open riding mode
 * button counts each part's width once.
 */
public final class RecordingClockTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            pausedLook();
            speedNow();
            buttonParts();
            pausedHead();
            words();
            spoken();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RecordingClockTest: " + checks + " checks");
    }

    /**
     * Saving paused (ScooterService.recordingError, storage full): the moment takes the problem look, as for a lost
     * link, so its dot stops pulsing; every way in and out (a good link, a lost one, switched off, paused cleared).
     */
    private static void pausedLook() {
        RidingFocus.Link good = RidingFocus.Link.CONNECTED, lost = RidingFocus.Link.NOT_CONNECTED, back = RidingFocus.Link.RECONNECTING;
        check(RecordingFit.look(878_400L, good, false, false) == RecordingPill.Look.RECORDING, "a good link, saving: the pulsing Recording");
        check(RecordingFit.look(878_400L, good, false, true) == RecordingPill.Look.RECORDING_PROBLEM, "storage full with a good link: the problem look");
        check(RecordingFit.look(0, good, false, true) == RecordingPill.Look.RECORDING_PROBLEM, "paused in the first second");
        check(RecordingFit.look(878_400L, lost, false, true) == RecordingPill.Look.RECORDING_PROBLEM
                && RecordingFit.look(878_400L, back, false, true) == RecordingPill.Look.RECORDING_PROBLEM
                && RecordingFit.look(878_400L, good, true, true) == RecordingPill.Look.RECORDING_PROBLEM, "paused with a link problem or switched off: still the problem look");
        check(RecordingFit.look(878_400L, lost, false, false) == RecordingPill.Look.RECORDING_PROBLEM
                && RecordingFit.look(878_400L, good, true, false) == RecordingPill.Look.RECORDING_PROBLEM, "not paused: the link's own look, as before");
        check(!RecordingPill.pulses(RecordingFit.look(878_400L, good, false, true), true), "paused: the dot does not pulse");
        check(RecordingPill.pulses(RecordingFit.look(878_400L, good, false, false), true), "cleared (a new ride, or the save retried): it pulses again");
    }

    /** The ribbon's speed now: only a reading riding mode would still show (6 s), never one up to 15 s old, never paused. */
    private static void speedNow() {
        long now = 1_700_000_000_000L, gone = RidingValues.SPEED_GONE_MS;
        check(gone == 6000, "riding mode's speed is gone after 6 s");
        check(RecordingFit.speedNowShown(true, now - 1000, now, true, false), "a reading 1 s old: now");
        check(RecordingFit.speedNowShown(true, now - gone, now, true, false), "at riding mode's limit: still now");
        check(!RecordingFit.speedNowShown(true, now - gone - 1, now, true, false), "just past it: gone");
        check(!RecordingFit.speedNowShown(true, now - 14_000, now, true, false), "14 s old, still live by the 15 s freshness: not now");
        check(!RecordingFit.speedNowShown(false, now - 1000, now, true, false), "a last-seen reading: not now");
        check(!RecordingFit.speedNowShown(true, now - 1000, now, false, false), "a lost link or a scooter off: not now");
        check(!RecordingFit.speedNowShown(true, now - 1000, now, true, true), "saving paused: not now");
        check(!RecordingFit.speedNowShown(true, 0, now, true, false), "no time for the reading: not now");
        check(RecordingFit.speedNowShown(true, now + 500, now, true, false) && !RecordingFit.speedNowShown(true, now + 60_000, now, true, false),
                "a moment ahead (clock jitter) is now; a minute ahead (the clock moved back) is not");
    }

    /** The button's gauge and chevron give way only when they no longer fit: each part's width counted once. */
    private static void buttonParts() {
        float icon = 20, gap = 10, chevron = 18, words = 130;
        float all = words + icon + gap + gap + chevron;
        check(RecordingFit.buttonParts(words, all, icon, gap, chevron) == 2, "exactly room for all: the gauge and the chevron (the old count took 20 dp more)");
        check(RecordingFit.buttonParts(words, all + 19, icon, gap, chevron) == 2, "19 dp spare: all of it, where the old count dropped the chevron");
        check(RecordingFit.buttonParts(words, all - 1, icon, gap, chevron) == 1, "1 dp short of the chevron: the gauge alone");
        check(RecordingFit.buttonParts(words, words + icon + gap, icon, gap, chevron) == 1, "exactly room for the gauge");
        check(RecordingFit.buttonParts(words, words + icon + gap - 1, icon, gap, chevron) == 0, "short of the gauge: the words alone");
        check(RecordingFit.buttonParts(words, words - 10, icon, gap, chevron) == 0, "not even the words: the words alone (then an ellipsis)");
    }

    /**
     * The paused words by the narrow-label ladder: full beside "Recording", then short, then "Recording" gives way to the
     * dot alone with the full words, then the short ones; an ellipsis only where even those don't fit. Widths in px.
     */
    private static void pausedHead() {
        float dot = 9, label = 90, gap = 12, full = 150, shorter = 90;
        check(RecordingFit.pausedHead(dot, label, gap, full, shorter, dot + label + gap + full) == 0, "room for all: Recording and the full words");
        check(RecordingFit.pausedHead(dot, label, gap, full, shorter, dot + label + gap + full - 1) == 1, "1 px short: Recording and the short words");
        check(RecordingFit.pausedHead(dot, label, gap, full, shorter, dot + label + gap + shorter) == 1, "exactly room for the short words beside Recording");
        check(RecordingFit.pausedHead(dot, label, gap, full, shorter, dot + label + gap + shorter - 1) == 2, "short of that: the dot and the full words");
        check(RecordingFit.pausedHead(dot, label, gap, 300, shorter, dot + label + gap + shorter - 1) == 3, "long full words: the dot and the short words");
        check(RecordingFit.pausedHead(dot, label, gap, full, shorter, dot + gap + shorter - 1) == 4, "not even the short words: the last resort");
    }

    /** Home's own words for each paused state, full and short. */
    private static void words() {
        equal("Paused: storage full", HubText.recordingPaused(RecordingFit.Pause.STORAGE_FULL, true), "storage full while recording");
        equal("Storage full", HubText.recordingPaused(RecordingFit.Pause.STORAGE_FULL, false), "its short rung");
        equal("Storage full: free space, then Retry save", HubText.recordingPaused(RecordingFit.Pause.SAVE_FAILED, true), "the failed save, with Retry");
        equal("Not saved: storage full", HubText.recordingPaused(RecordingFit.Pause.SAVE_FAILED, false), "its short rung");
        check(HubText.recordingPaused(RecordingFit.Pause.NONE, true) == null && HubText.recordingPaused(null, false) == null, "saving: no paused words");
        for (RecordingFit.Pause pause : new RecordingFit.Pause[] {RecordingFit.Pause.STORAGE_FULL, RecordingFit.Pause.SAVE_FAILED})
            check(HubText.recordingPaused(pause, false).render().length() < HubText.recordingPaused(pause, true).render().length(), "the short rung is shorter: " + pause);
    }

    /** The description while paused: the full words after the time, in place of any link problem; else as before. */
    private static void spoken() {
        equal("Recording for 14 minutes, Paused: storage full. Trip, 2.5 mi. Battery, 73%. Opens the Ride page.",
                HubText.recordingSpokenPaused(878_400L, RidingFocus.Link.CONNECTED, false, RecordingFit.Pause.STORAGE_FULL,
                        "Trip", "2.5 mi", "Battery", "73%", "Range (est.)", null), "storage full, spoken with the readings");
        equal("Recording for 14 minutes, Storage full: free space, then Retry save. Opens the Ride page.",
                HubText.recordingSpokenPaused(878_400L, RidingFocus.Link.NOT_CONNECTED, true, RecordingFit.Pause.SAVE_FAILED),
                "the failed save, in place of the link problem");
        equal("Recording for less than a minute, Paused: storage full. Opens the Ride page.",
                HubText.recordingSpokenPaused(30_000L, RidingFocus.Link.RECONNECTING, false, RecordingFit.Pause.STORAGE_FULL), "in the first minute");
        equal(HubText.recordingSpoken(878_400L, RidingFocus.Link.RECONNECTING, false, "Trip", "2.5 mi").render(),
                HubText.recordingSpokenPaused(878_400L, RidingFocus.Link.RECONNECTING, false, RecordingFit.Pause.NONE, "Trip", "2.5 mi"),
                "not paused: exactly as before");
        equal(HubText.recordingSpoken(878_400L, RidingFocus.Link.CONNECTED, false).render(),
                HubText.recordingSpokenPaused(878_400L, RidingFocus.Link.CONNECTED, false, null), "null: not paused");
    }

    private static void equal(String expected, Object actual, String message) {
        String shown = actual instanceof Msg ? ((Msg) actual).render() : String.valueOf(actual);
        check(actual != null && expected.equals(shown), message + ": expected <" + expected + "> but was <" + shown + ">");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
