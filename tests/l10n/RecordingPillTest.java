package com.jjricks.volt;

import java.util.Arrays;
import java.util.Locale;

/**
 * Riding mode's recording pill (owner, Nov 6): which look the status pill takes, its laddered text and
 * spoken text, the pulse rule, and that the recording timer stays apart from ride time.
 */
public final class RecordingPillTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            look();
            rungs();
            spoken();
            pulse();
            separateTimers();
            saved();
            rebootSafeTime();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RecordingPillTest: " + checks + " checks passed (look, rungs, alternating, spoken, pulse, separate timers, ride saved, time across a reboot)");
    }

    private static void look() {
        for (RidingFocus.Link link : RidingFocus.Link.values())
            check(RecordingPill.look(null, link) == RecordingPill.Look.STATUS, "no recording keeps the connection pill: " + link);
        check(RecordingPill.look(0L, RidingFocus.Link.CONNECTED) == RecordingPill.Look.RECORDING, "a recording that just started is red");
        check(RecordingPill.look(768_900L, RidingFocus.Link.CONNECTED) == RecordingPill.Look.RECORDING, "recording, connected");
        check(RecordingPill.look(768_900L, RidingFocus.Link.RECONNECTING) == RecordingPill.Look.RECORDING_PROBLEM, "reconnecting while recording");
        check(RecordingPill.look(768_900L, RidingFocus.Link.NOT_CONNECTED) == RecordingPill.Look.RECORDING_PROBLEM, "disconnected while recording");
    }

    private static void rungs() {
        RidingFocus.Link on = RidingFocus.Link.CONNECTED, re = RidingFocus.Link.RECONNECTING, off = RidingFocus.Link.NOT_CONNECTED;
        check(Arrays.equals(RecordingPill.rungs(768_900, on, false, 0, true), new String[]{"REC 12:48", "12:48"}),
                "connected: REC and the time, then the time alone");
        check(Arrays.equals(RecordingPill.rungs(3_768_000, on, true, 0, true), new String[]{"REC 1:02:48", "1:02:48"}), "past an hour");
        check(Arrays.equals(RecordingPill.rungs(768_900, re, true, 0, true),
                new String[]{"REC 12:48 · Reconnecting…", "12:48 · Reconnecting…", "Reconnecting…"}),
                "reconnecting with room: both, the problem's word last");
        check(Arrays.equals(RecordingPill.rungs(768_900, off, true, 0, true),
                new String[]{"REC 12:48 · Not connected", "12:48 · Not connected", "Not connected"}),
                "not connected with room: both, the problem's words last");
        check(RecordingPill.rungs(-5, on, true, 0, true)[0].equals("REC 0:00"), "never a negative time");
        // No room for both: the pill alternates every 3 s, starting with the time.
        check(Arrays.equals(RecordingPill.rungs(768_900, off, false, 0, true), new String[]{"REC 12:48", "12:48"}), "first half: the time");
        check(Arrays.equals(RecordingPill.rungs(768_900, off, false, 2_999, true), new String[]{"REC 12:48", "12:48"}), "still the time");
        check(Arrays.equals(RecordingPill.rungs(768_900, off, false, 3_000, true), new String[]{"Not connected"}), "second half: the problem");
        check(Arrays.equals(RecordingPill.rungs(768_900, re, false, 5_999, true), new String[]{"Reconnecting…"}), "reconnecting's second half");
        check(Arrays.equals(RecordingPill.rungs(768_900, off, false, 6_000, true), new String[]{"REC 12:48", "12:48"}), "back to the time");
        // With the system's animations off, no alternating: the problem's words hold.
        for (long t = 0; t < 12_000; t += 1_000)
            check(Arrays.equals(RecordingPill.rungs(768_900, off, false, t, false), new String[]{"Not connected"}), "holds the problem at " + t);
        check(RecordingPill.rungs(768_900, on, false, 3_000, false).length == 2, "connected never alternates");
        check(RecordingPill.ALTERNATE_MS == 3000, "3 s halves");
    }

    private static void spoken() {
        check(RecordingPill.spoken(1_400, RidingFocus.Link.CONNECTED, null).equals("Recording for 1 second"), "one second");
        check(RecordingPill.spoken(42_000, RidingFocus.Link.CONNECTED, null).equals("Recording for 42 seconds"), "seconds in the first minute");
        check(RecordingPill.spoken(60_000, RidingFocus.Link.CONNECTED, null).equals("Recording for 1 minute"), "one minute");
        check(RecordingPill.spoken(768_900, RidingFocus.Link.CONNECTED, null).equals("Recording for 12 minutes"), "whole minutes, never a clock time");
        check(RecordingPill.spoken(4_320_000, RidingFocus.Link.CONNECTED, null).equals("Recording for 72 minutes"), "minutes past an hour");
        check(RecordingPill.spoken(768_900, RidingFocus.Link.RECONNECTING, "0:42").equals("Recording for 12 minutes, Reconnecting…"),
                "reconnecting is spoken after the time");
        check(RecordingPill.spoken(768_900, RidingFocus.Link.NOT_CONNECTED, "0:42").equals("Recording for 12 minutes, Not connected · last seen 0:42"),
                "not connected says when the last reading came");
        check(RecordingPill.spoken(768_900, RidingFocus.Link.NOT_CONNECTED, null).equals("Recording for 12 minutes, Not connected"),
                "not connected without a reading yet");
    }

    private static void pulse() {
        check(RecordingPill.pulses(RecordingPill.Look.RECORDING, true), "the dot pulses while recording");
        check(!RecordingPill.pulses(RecordingPill.Look.RECORDING, false), "no pulse with the system's animations off");
        check(!RecordingPill.pulses(RecordingPill.Look.RECORDING_PROBLEM, true), "a link problem holds the dot still");
        check(!RecordingPill.pulses(RecordingPill.Look.STATUS, true), "no pulse without a recording");
    }

    private static void saved() {
        RidingFocus.Link on = RidingFocus.Link.CONNECTED, off = RidingFocus.Link.NOT_CONNECTED;
        check(RecordingPill.look(null, on, null, false) == RecordingPill.Look.STATUS, "no stop yet: the connection pill");
        check(RecordingPill.look(null, on, 0L, false) == RecordingPill.Look.SAVED, "just stopped: Ride saved");
        check(RecordingPill.look(null, off, 3_999L, false) == RecordingPill.Look.SAVED, "saved shows for 4 s, connected or not");
        check(RecordingPill.look(null, on, 4_000L, false) == RecordingPill.Look.STATUS, "then the connection pill returns");
        check(RecordingPill.look(null, off, 9_999L, true) == RecordingPill.Look.SAVED, "an automatic stop shows for 10 s");
        check(RecordingPill.look(null, off, 10_000L, true) == RecordingPill.Look.STATUS, "then the connection pill returns");
        check(RecordingPill.look(null, on, -500L, false) == RecordingPill.Look.STATUS, "a clock moved back never shows it");
        check(RecordingPill.look(5_000L, on, 0L, false) == RecordingPill.Look.RECORDING, "a new recording wins over the last save");
        check(!RecordingPill.pulses(RecordingPill.Look.SAVED, true), "the saved pill holds still");
        check(Arrays.equals(RecordingPill.savedRungs(false), new String[]{"Ride saved", "Saved"}), "the rider's stop");
        check(Arrays.equals(RecordingPill.savedRungs(true), new String[]{"Ride saved automatically", "Ride saved", "Saved"}), "an automatic stop");
        check(RecordingPill.savedSpoken(false, "SPORT · Connected").equals("Ride saved, SPORT · Connected"), "spoken with the connection");
        check(RecordingPill.savedSpoken(true, "Not connected · last seen 5:02").equals("Ride saved automatically, Not connected · last seen 5:02"), "automatic, not connected");
        check(RecordingPill.savedSpoken(true, null).equals("Ride saved automatically"), "nothing to add");
    }

    /** Codex #304: a recording recovered after a reboot has a start on the previous boot's monotonic clock. */
    private static void rebootSafeTime() {
        check(RecordingPill.elapsedMs(60_000, 1_000_000, 1_600_000) == 600_000, "wall time since the start");
        check(RecordingPill.elapsedMs(60_000, 1_000_000, 1_010_000) == 60_000, "a clock moved back keeps the recorded time");
        check(RecordingPill.elapsedMs(0, 1_000_000, 900_000) == 0, "never negative");
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("recording-pill").toFile();
            long wall = 1_767_225_600_000L, oldBoot = 5_000_000L;
            RideStore before = new RideStore(dir);
            before.start("p1", "Pill test", wall, oldBoot);
            before.heartbeat(wall + 60_000, oldBoot + 60_000);
            // The phone reboots: a new store recovers the unfinished recording while the monotonic clock restarts near zero.
            RideStore after = new RideStore(dir);
            RideStore.Summary s = after.activeSummary();
            check(s != null && s.active && s.recovered && s.startMonotonicMs == oldBoot, "the recording is recovered with the old boot's start");
            long newBootNow = 2_000, wallNow = wall + 600_000;
            check(Math.max(0, newBootNow - s.startMonotonicMs) == 0, "the old monotonic difference would show 0:00");
            long shown = RecordingPill.elapsedMs(s.elapsedMs, s.startWallMs, wallNow);
            check(shown == 600_000, "the pill shows 10:00 after the reboot: " + shown);
            check(RecordingPill.rungs(shown, RidingFocus.Link.CONNECTED, true, 0, true)[0].equals("REC 10:00"), "REC 10:00");
            check(after.lastStopWallMs() == -1, "no stop yet");
            after.stop(wallNow, newBootNow);
            check(after.lastStopWallMs() == wallNow && !after.lastStopAutomatic(), "the rider's stop is remembered by its wall time");
            after.start("p1", "Second", wallNow + 1_000, newBootNow + 1_000);
            after.stop(wallNow + 900_000, newBootNow + 900_000, RideStore.STOP_AUTO_PARKED);
            check(after.lastStopWallMs() == wallNow + 900_000 && after.lastStopAutomatic(), "an automatic stop is remembered as automatic");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void separateTimers() {
        RidingValues values = new RidingValues();
        check(values.recordingTimeMs(10_000) == null, "no recording, no recording time");
        values.setRecordingStart(4_000L);
        check(values.recordingTimeMs(10_000) == 6_000L, "the recording's own time");
        RidingSession session = new RidingSession("p1", null, 3_000);
        session.values.setRecordingStart(4_000L);
        check(session.rideTimeMs(10_000) == 7_000L, "ride time counts from riding mode's open, not the recording's start");
        values.setRecordingStart(null);
        check(values.recordingTimeMs(10_000) == null, "stopping clears only the recording time");
    }
}
