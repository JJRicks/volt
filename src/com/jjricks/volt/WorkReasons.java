package com.jjricks.volt;

/**
 * The work that keeps ScooterService going out of sight, in one place. Pure and Android-free. The wake
 * lock, the foreground notice's immediacy, its ongoing flag and the tick's wake lock release each once
 * repeated this list; they now read it here. The live charging hold (ChargeLiveHold) is listed wherever
 * the battery alert hold already was. With the live hold off, every answer is the old expression
 * unchanged, and the background-stop helpers answer as the paired statements they replaced
 * (ChargeLiveTest checks all combinations).
 */
public final class WorkReasons {
    public final boolean recording, chargeRecording, holding, chargeAlertHold, chargeLiveHold, phoneSaving;

    public WorkReasons(boolean recording, boolean chargeRecording, boolean holding, boolean chargeAlertHold, boolean chargeLiveHold, boolean phoneSaving) {
        this.recording = recording;this.chargeRecording = chargeRecording;this.holding = holding;
        this.chargeAlertHold = chargeAlertHold;this.chargeLiveHold = chargeLiveHold;this.phoneSaving = phoneSaving;
    }

    /** Keeps the CPU awake, asks Android to show the foreground notice at once, and keeps that notice ongoing. */
    public boolean keepsWorking() {
        return recording || chargeRecording || holding || chargeAlertHold || chargeLiveHold || phoneSaving;
    }
    /** The connection notice can't be swiped away: active work, or a connected scooter. */
    public boolean ongoing(boolean connected) {
        return keepsWorking() || connected;
    }
    /**
     * The service keeps going and no ten-second stop is pending: Volt is on screen, work is running, or the owner
     * turned on the background connection. workAllowed adds an automatic ride copy and a pending stop to this.
     */
    public boolean keepsServiceUp(boolean visible, boolean backgroundConnection) {
        return visible || keepsWorking() || backgroundConnection;
    }
    /**
     * Leaving Volt (or showing it again) arms no ten-second stop. The live hold counts only on leaving. Saving a
     * phone ride was never on this list: the stop itself waits for the save (keepsServiceUp counts it).
     */
    public boolean skipsTenSecondStop(boolean leaving, boolean backgroundConnection) {
        return recording || chargeRecording || holding || chargeAlertHold || leaving && chargeLiveHold || backgroundConnection;
    }
}
