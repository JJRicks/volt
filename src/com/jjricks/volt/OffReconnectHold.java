package com.jjricks.volt;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The scooters Volt stops reconnecting to by itself after a ride ended automatically because the scooter went quiet or
 * was switched off (2.0.5 item 5 follow-up). One owner's UT5 MAX, switched off with a ride recording, kept answering
 * Volt's reconnects; one guess (not a fact) is that the attempts kept its Bluetooth half awake. So once such a ride has
 * ended, Volt leaves that scooter alone until one of:
 * <ul>
 *   <li>the rider connects (Connect, a scan pick, or any other connection the rider starts),</li>
 *   <li>Volt is opened again (it comes into sight),</li>
 *   <li>the scooter is seen pushing live data (a link still up to it, switched on again).</li>
 * </ul>
 * In this process only: a service or process restart has no hold, and then reconnects on its usual triggers. A link
 * already up is never closed by a hold. Pure: BleController asks it before each automatic reconnect.
 */
final class OffReconnectHold {
    private final Set<String> held = new LinkedHashSet<>();

    /**
     * Whether this automatic end pauses reconnects: rules 1 and 2 (lost while parked, or went quiet, the switched-off
     * case included) on a scooter ScooterPowerState judges. Never the phone's own Bluetooth off, the no-motion rule, the
     * rider's stop, or a scooter that is never judged (other models that never pushed, the simulator).
     */
    static boolean pauses(String stopReason, boolean judged) {
        if (!judged || stopReason == null) return false;
        if (RideStore.STOP_AUTO_PARKED.equals(stopReason)) return true;
        return RideStore.isQuietStop(stopReason) && !stopReason.contains(";phone_bt=off");
    }

    synchronized void hold(String profileId) { if (profileId != null && !profileId.isEmpty()) held.add(profileId); }
    /** Whether Volt holds automatic reconnects to this profile. */
    synchronized boolean held(String profileId) { return profileId != null && held.contains(profileId); }
    /** The rider connected, or the scooter pushed: true when a hold ended. */
    synchronized boolean release(String profileId) { return profileId != null && held.remove(profileId); }
    /** Volt opened again: true when any hold ended. */
    synchronized boolean releaseAll() { boolean any = !held.isEmpty(); held.clear(); return any; }
}
