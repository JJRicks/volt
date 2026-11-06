package com.jjricks.volt;

/**
 * The work that keeps ScooterService going out of sight, in one place. Pure and Android-free. The wake
 * lock, the foreground notice's immediacy, its ongoing flag and the tick's wake lock release each once
 * repeated this list; they now read it here. The live charging hold (ChargeLiveHold) is listed wherever
 * the battery alert hold already was. With the live hold off, every answer is the old expression
 * unchanged (ChargeLiveTest checks all combinations).
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
}
