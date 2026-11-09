package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Riding mode's stopped Lock and Record controls (owner, Nov 8): they show under the layout picker's
 * own gate, Record takes a tap as Home's does but only while stopped, and the screen reaches the lock
 * only through Home's control. Synthesized values only.
 */
public final class RidingStoppedControlsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws IOException {
        gate();
        record();
        source();
        System.out.println("RidingStoppedControlsTest: " + checks + " checks passed (the picker's gate, Record's taps, Home's lock path)");
    }

    private static void gate() {
        check(RidingFrames.pickerShown(true, 0.0) && RidingFrames.pickerShown(true, null) && RidingFrames.pickerShown(true, .9), "stopped shows them");
        check(!RidingFrames.pickerShown(true, 1.0) && !RidingFrames.pickerShown(true, 20.0), "1 km/h or more hides them");
        check(!RidingFrames.pickerShown(false, 0.0) && !RidingFrames.pickerShown(false, null), "no fresh stationary report hides them");
    }

    private static void record() {
        check(RidingFrames.recordEnabled(false, true, false) && RidingFrames.recordEnabled(true, false, false), "connected, or recording, takes a tap");
        check(!RidingFrames.recordEnabled(false, true, true) && !RidingFrames.recordEnabled(true, true, true) && !RidingFrames.recordEnabled(false, false, false), "still saving, or not connected, doesn't");
        check(RidingFrames.recordAction(true, false, true) == RecorderTap.START, "stopped and idle starts");
        check(RidingFrames.recordAction(true, true, true) == RecorderTap.STOP, "stopped and recording stops");
        check(RidingFrames.recordAction(true, false, false) == RecorderTap.NONE, "not connected does nothing here");
        check(RidingFrames.recordAction(false, true, true) == RecorderTap.NONE && RidingFrames.recordAction(false, false, true) == RecorderTap.NONE, "a tap that lands once moving does nothing");
    }

    private static void source() throws IOException {
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidingScreen.java")), StandardCharsets.UTF_8);
        check(screen.contains("stoppedLock = host.ridingLock();") && screen.contains("showStopped(stoppedNow,"), "Lock and Record follow the stopped gate");
        check(screen.contains("RidingFrames.recordAction(stopped(SystemClock.elapsedRealtime())") && screen.contains("RideRecordingApp.of(host)"), "Record is Home's control, re-checked at the tap");
        check(!screen.contains("ble.set("), "the lock's tap goes through MainActivity's queue, never straight to the scooter");
    }
}
