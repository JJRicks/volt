package com.jjricks.volt;

/**
 * Starting and stopping a ride recording: the checks, in their order, and the start or stop itself.
 * The Rides tab, the Home Ride recorder card and riding mode all use this one control, so a ride
 * starts the same way from each of them and none has to open Rides first. Pure Java:
 * {@link RideRecordingApp#of(ScreenHost)} connects it to the service and the activity, and tests
 * use fakes.
 */
final class RideRecordingControl {
    /** The recording side (ScooterService). */
    interface Recorder {
        boolean recording();
        boolean simulating();
        /** A previous ride's phone sensors are still being written. */
        boolean saving();
        boolean storageReady();
        boolean connected();
        void leaveSimulation();
        void start(String name);
        void stop();
    }

    /** The screen side (the activity): messages, the location prompt, haptics and redraws. */
    interface Screen {
        void toast(String text);
        /** Shows the app again after leaving simulation. */
        void rebuild();
        /** The phone GPS route setting: ask for location before the start. */
        boolean phoneGps();
        /** Runs ready once location is settled (granted or declined). It may run later, or never. */
        void requestLocation(Runnable ready);
        /** The app is going away: a start that waited for the location prompt no longer runs. */
        boolean gone();
        /** The new ride's name, made when it actually starts (the prompt can stay open a long time). */
        String rideName();
        /** A start or stop took effect. */
        void confirmed();
        /** Recording state may have changed: redraw what shows it. */
        void changed();
    }

    /** What a start or stop did. */
    static final int READY = 0, STARTED = 1, STARTING = 2, NOT_STARTED = 3, ALREADY_RECORDING = 4,
            LEFT_SIMULATION = 5, SIMULATING = 6, FINISHING_PREVIOUS = 7, NO_STORAGE = 8, NOT_CONNECTED = 9,
            STOPPED = 10, NOT_STOPPED = 11, NOT_RECORDING = 12;

    private final Recorder recorder;
    private final Screen screen;

    RideRecordingControl(Recorder recorder, Screen screen) {
        this.recorder = recorder;
        this.screen = screen;
    }

    /**
     * The start checks, in order: a ride already recording, simulation (left only when the caller
     * allows it), a previous ride still saving, storage, then the connection. READY when all pass.
     */
    static int startCheck(boolean recording, boolean simulating, boolean leaveSimulation,
            boolean saving, boolean storage, boolean connected) {
        if (recording) return ALREADY_RECORDING;
        if (simulating) return leaveSimulation ? LEFT_SIMULATION : SIMULATING;
        if (saving) return FINISHING_PREVIOUS;
        if (!storage) return NO_STORAGE;
        if (!connected) return NOT_CONNECTED;
        return READY;
    }

    /** The message a refused start shows; null when it shows none. */
    static String message(int outcome) {
        switch (outcome) {
            case SIMULATING: return T.ridesToastExitSimulation();
            case FINISHING_PREVIOUS: return T.ridesToastFinishingPrevious();
            case NO_STORAGE: return T.ridesToastStorageUnavailable();
            case NOT_CONNECTED: return T.ridesToastConnectFirst();
            case NOT_STOPPED: return T.ridesToastCouldNotStop();
            default: return null;
        }
    }

    boolean recording() { return recorder.recording(); }

    /**
     * The Rides record and end buttons: stops a recording ride; otherwise leaves simulation (the
     * button says so while simulating) or starts a ride.
     */
    int toggle() {
        return recorder.recording() ? stop() : start(true);
    }

    /**
     * Home's recorder card and riding mode: starts a ride where the rider is. It never stops one
     * and never leaves simulation; a refused start says why with a toast.
     * STARTED or NOT_STARTED once it ran, STARTING while the location prompt is open.
     */
    int start() { return start(false); }

    private int start(boolean leaveSimulation) {
        int check = startCheck(recorder.recording(), recorder.simulating(), leaveSimulation,
                recorder.saving(), recorder.storageReady(), recorder.connected());
        if (check == LEFT_SIMULATION) {
            recorder.leaveSimulation();
            screen.rebuild();
            return check;
        }
        if (check != READY) {
            String text = message(check);
            if (text != null) screen.toast(text);
            return check;
        }
        boolean[] ran = {false};
        // Location first, then capture; the ride is named at its actual start.
        Runnable begin = () -> {
            ran[0] = true;
            if (screen.gone() || recorder.recording()) return;
            recorder.start(screen.rideName());
            if (recorder.recording()) screen.confirmed();
            screen.changed();
        };
        if (screen.phoneGps()) screen.requestLocation(begin);
        else begin.run();
        if (!ran[0]) return STARTING;
        return recorder.recording() ? STARTED : NOT_STARTED;
    }

    /** Stops a recording ride: Home's disc (one tap), the Rides end button and riding mode. */
    int stop() {
        if (!recorder.recording()) return NOT_RECORDING;
        recorder.stop();
        int outcome = recorder.recording() ? NOT_STOPPED : STOPPED;
        if (outcome == NOT_STOPPED) screen.toast(message(NOT_STOPPED));
        else screen.confirmed();
        screen.changed();
        return outcome;
    }
}
