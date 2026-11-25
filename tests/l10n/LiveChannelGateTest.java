package com.jjricks.volt;

/**
 * #645 F03: whether the live card (and a ride's notice) can show asks about the channel the notice will really post on
 * (LiveNotices.cardCanShow): Rides and charging while it is open, else Scooter connection & recording. Listed in
 * tests/l10n/live-channel-gate.sources; tests/run.sh runs it.
 */
public final class LiveChannelGateTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        check(LiveNotices.cardCanShow(true, true, true), "both channels on: the card shows (on Rides and charging)");
        check(LiveNotices.cardCanShow(true, true, false), "only Scooter connection & recording off: the card still shows, on Rides and charging");
        check(LiveNotices.cardCanShow(true, false, true), "only Rides and charging off: the card shows on the connection channel, as before");
        check(!LiveNotices.cardCanShow(true, false, false), "both channels off: no card");
        check(!LiveNotices.cardCanShow(false, true, true), "Volt's notifications off: no card, whatever the channels");
        // The gate and the channel the notice uses agree in every state.
        for (boolean quiet : new boolean[]{false, true}) for (boolean scooter : new boolean[]{false, true}) {
            String used = LiveNotices.channel(false, true, quiet);
            boolean open = LiveNotices.QUIET_CHANNEL.equals(used) ? quiet : scooter;
            check(LiveNotices.cardCanShow(true, quiet, scooter) == open, "the gate is the open state of the channel used (" + quiet + ", " + scooter + ")");
        }
        System.out.println("LiveChannelGateTest: " + checks + " checks passed");
    }
}
