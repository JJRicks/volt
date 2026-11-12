package com.jjricks.volt;

/**
 * Email to Volt on Charge history & recordings (hub layout plan, PR 4): what to tell the owner when the service refuses
 * the export behind it (ScooterService.exportChargeRecordings). Pure, so the JVM suite tests it (tests/ChargeSendTest.java).
 * The council's review of #396 (P3): only a charge being recorded is worth a toast ("Stop charge recording before
 * sending the recordings"); an earlier export still being written (a double tap) stays quiet, so the owner is never
 * told to stop a recording that isn't running.
 */
final class ChargeSend {
    private ChargeSend() { }

    enum Refusal { STOP_RECORDING, QUIET }

    /** recording: a charge is being recorded now; exporting: an earlier export is still being written. */
    static Refusal refusal(boolean recording, boolean exporting) {
        return recording ? Refusal.STOP_RECORDING : Refusal.QUIET;
    }
}
