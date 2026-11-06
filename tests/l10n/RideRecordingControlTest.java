package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;

/** The shared ride start and stop (RideRecordingControl): check order, messages and the location step, with a fake service. */
public final class RideRecordingControlTest {
    private static int checks;

    public static void main(String[] args) {
        // Every combination of the five start conditions: the first failing one, in order, decides.
        for (int bits = 0; bits < 32; bits++) {
            boolean recording = (bits & 1) != 0, simulating = (bits & 2) != 0, saving = (bits & 4) != 0,
                    storage = (bits & 8) == 0, connected = (bits & 16) == 0;
            for (boolean leave : new boolean[] {true, false}) {
                int expected = recording ? RideRecordingControl.ALREADY_RECORDING
                        : simulating ? (leave ? RideRecordingControl.LEFT_SIMULATION : RideRecordingControl.SIMULATING)
                        : saving ? RideRecordingControl.FINISHING_PREVIOUS
                        : !storage ? RideRecordingControl.NO_STORAGE
                        : !connected ? RideRecordingControl.NOT_CONNECTED
                        : RideRecordingControl.READY;
                require(RideRecordingControl.startCheck(recording, simulating, leave, saving, storage, connected) == expected,
                        "Start checks run in order: recording, simulation, saving, storage, connection (" + bits + ", " + leave + ")");

                // The real start path with a fake service: Home's start never leaves simulation and never stops.
                Fake fake = new Fake(recording, simulating, saving, storage, connected);
                int outcome = fake.control.start();
                int startExpected = expected == RideRecordingControl.LEFT_SIMULATION ? -1 : expected;
                if (simulating && !recording) startExpected = RideRecordingControl.SIMULATING;
                if (startExpected == RideRecordingControl.READY) {
                    require(outcome == RideRecordingControl.STARTED && fake.starts == 1 && fake.locationAsks == 1,
                            "All checks pass: location is asked, then the ride starts once");
                    require(fake.log.indexOf("location") < fake.log.indexOf("name") && fake.log.indexOf("name") < fake.log.indexOf("start"),
                            "Location comes first, and the ride is named when it actually starts");
                    require(fake.toasts.isEmpty() && fake.confirms == 1 && fake.changes == 1, "A start confirms and redraws, with no message");
                } else {
                    require(outcome == startExpected, "A refused start reports its first failing check");
                    require(fake.starts == 0 && fake.stops == 0 && fake.locationAsks == 0 && fake.leaves == 0 && fake.rebuilds == 0,
                            "A refused start never asks for location, never starts, stops or leaves simulation");
                    String message = RideRecordingControl.message(startExpected);
                    require(startExpected == RideRecordingControl.ALREADY_RECORDING ? message == null && fake.toasts.isEmpty()
                            : message != null && fake.toasts.size() == 1 && fake.toasts.get(0).equals(message),
                            "A refused start says why (a recording ride needs no message)");
                }

                // The Rides buttons: toggle stops a recording ride and leaves simulation instead of refusing.
                fake = new Fake(recording, simulating, saving, storage, connected);
                outcome = fake.control.toggle();
                if (recording) require(outcome == RideRecordingControl.STOPPED && fake.stops == 1 && fake.starts == 0, "Toggle stops a recording ride");
                else if (simulating) require(outcome == RideRecordingControl.LEFT_SIMULATION && fake.leaves == 1 && fake.rebuilds == 1
                        && fake.starts == 0 && fake.toasts.isEmpty(), "Toggle while simulating leaves simulation and starts nothing");
                else require(outcome == startExpected || (startExpected == RideRecordingControl.READY && outcome == RideRecordingControl.STARTED),
                        "Toggle starts with the same checks as Home");
            }
        }

        // The exact messages are the Rides tab's existing ones.
        require(RideRecordingControl.message(RideRecordingControl.SIMULATING).equals(T.ridesToastExitSimulation()), "Simulating: the Rides exit-simulation message");
        require(RideRecordingControl.message(RideRecordingControl.FINISHING_PREVIOUS).equals(T.ridesToastFinishingPrevious()), "Saving: finishing the previous ride");
        require(RideRecordingControl.message(RideRecordingControl.NO_STORAGE).equals(T.ridesToastStorageUnavailable()), "No storage");
        require(RideRecordingControl.message(RideRecordingControl.NOT_CONNECTED).equals(T.ridesToastConnectFirst()), "Not connected: connect first");
        require(RideRecordingControl.message(RideRecordingControl.STARTED) == null && RideRecordingControl.message(RideRecordingControl.STOPPED) == null,
                "Success shows no message");

        // GPS route off: no location prompt.
        Fake fake = Fake.ready(); fake.gps = false;
        require(fake.control.start() == RideRecordingControl.STARTED && fake.locationAsks == 0 && fake.starts == 1, "GPS route off: starts without asking for location");

        // The prompt stays open: STARTING, and nothing starts until it settles.
        fake = Fake.ready(); fake.holdLocation = true;
        require(fake.control.start() == RideRecordingControl.STARTING && fake.starts == 0 && fake.changes == 0, "Location prompt open: still starting");
        fake.connectedNow = false;
        fake.held.run();
        require(fake.starts == 1, "The prompt settling runs the start (the service makes its own final checks)");

        // The prompt settles after the app went away, or after another start got there first.
        fake = Fake.ready(); fake.holdLocation = true; fake.control.start(); fake.gone = true; fake.held.run();
        require(fake.starts == 0 && fake.changes == 0, "App gone before the prompt settles: no start");
        fake = Fake.ready(); fake.holdLocation = true; fake.control.start(); fake.recordingNow = true; fake.held.run();
        require(fake.starts == 0, "Already recording when the prompt settles: no second start");

        // The service refuses (a charge recording, a storage error): reported as not started, with no confirm.
        fake = Fake.ready(); fake.serviceRefuses = true;
        require(fake.control.start() == RideRecordingControl.NOT_STARTED && fake.confirms == 0 && fake.changes == 1,
                "A start the service refused is not started, and the card redraws");

        // Stop: one call, a confirm, or the could-not-stop message.
        fake = new Fake(true, false, false, true, true);
        require(fake.control.stop() == RideRecordingControl.STOPPED && fake.stops == 1 && fake.confirms == 1 && fake.toasts.isEmpty(), "Stop stops once and confirms");
        require(fake.control.stop() == RideRecordingControl.NOT_RECORDING && fake.stops == 1, "Stop with nothing recording does nothing");
        fake = new Fake(true, false, false, true, true); fake.stopFails = true;
        require(fake.control.stop() == RideRecordingControl.NOT_STOPPED && fake.confirms == 0
                && fake.toasts.size() == 1 && fake.toasts.get(0).equals(T.ridesToastCouldNotStop()), "A stop that failed says so");
        fake = Fake.ready();
        require(fake.control.stop() == RideRecordingControl.NOT_RECORDING && fake.stops == 0 && fake.starts == 0, "Stop never starts a ride");
        fake = new Fake(true, false, false, true, true);
        require(fake.control.start() == RideRecordingControl.ALREADY_RECORDING && fake.stops == 0, "Start never stops a ride");

        System.out.println("RideRecordingControlTest: " + checks + " checks passed");
    }

    /** A fake service and screen that record what the control asked for. */
    private static final class Fake implements RideRecordingControl.Recorder, RideRecordingControl.Screen {
        boolean recordingNow, simulatingNow, savingNow, storageNow, connectedNow;
        boolean gps = true, holdLocation, gone, serviceRefuses, stopFails;
        int starts, stops, leaves, rebuilds, locationAsks, confirms, changes;
        Runnable held;
        final List<String> toasts = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final RideRecordingControl control = new RideRecordingControl(this, this);

        Fake(boolean recording, boolean simulating, boolean saving, boolean storage, boolean connected) {
            recordingNow = recording; simulatingNow = simulating; savingNow = saving; storageNow = storage; connectedNow = connected;
        }
        static Fake ready() { return new Fake(false, false, false, true, true); }

        @Override public boolean recording() { return recordingNow; }
        @Override public boolean simulating() { return simulatingNow; }
        @Override public boolean saving() { return savingNow; }
        @Override public boolean storageReady() { return storageNow; }
        @Override public boolean connected() { return connectedNow; }
        @Override public void leaveSimulation() { leaves++; simulatingNow = false; }
        @Override public void start(String name) {
            log.add("start"); starts++;
            require("Ride".equals(name), "The control passes the ride's name to the service");
            if (!serviceRefuses) recordingNow = true;
        }
        @Override public void stop() { stops++; if (!stopFails) recordingNow = false; }

        @Override public void toast(String text) { toasts.add(text); }
        @Override public void rebuild() { rebuilds++; }
        @Override public boolean phoneGps() { return gps; }
        @Override public void requestLocation(Runnable ready) {
            log.add("location"); locationAsks++;
            if (holdLocation) held = ready; else ready.run();
        }
        @Override public boolean gone() { return gone; }
        @Override public String rideName() { log.add("name"); return "Ride"; }
        @Override public void confirmed() { confirms++; }
        @Override public void changed() { changes++; }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
