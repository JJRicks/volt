package com.jjricks.volt;

/** ChargeSend: Email to Volt asks the owner to stop a recording only while one is running (council review of #396, P3). */
public final class ChargeSendTest {
    private static int checks;

    public static void main(String[] args) {
        require(ChargeSend.refusal(true, false) == ChargeSend.Refusal.STOP_RECORDING, "A charge being recorded asks to stop it");
        require(ChargeSend.refusal(true, true) == ChargeSend.Refusal.STOP_RECORDING, "Recording while an export runs still asks to stop it");
        require(ChargeSend.refusal(false, true) == ChargeSend.Refusal.QUIET, "An earlier export still being written stays quiet");
        require(ChargeSend.refusal(false, false) == ChargeSend.Refusal.QUIET, "Any other refusal never mentions a recording");
        System.out.println("ChargeSend: " + checks + " checks passed");
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
