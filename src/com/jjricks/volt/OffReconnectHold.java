package com.jjricks.volt;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The scooters Volt stops reconnecting to by itself, out of sight, after a ride ended automatically with the scooter
 * seen switched off on a live link (2.0.5 item 5 follow-up). One owner's UT5 MAX, switched off with a ride recording, kept answering
 * Volt's reconnects; one guess (not a fact) is that the attempts kept its Bluetooth half awake. So once such a ride has
 * ended, Volt leaves that scooter alone until one of:
 * <ul>
 *   <li>the rider connects (Connect, a scan pick, or any other connection the rider starts),</li>
 *   <li>Volt is opened again (it comes into sight),</li>
 *   <li>the scooter is seen pushing live data (a link still up to it, switched on again).</li>
 * </ul>
 * No hold applies while Volt is in sight ({@link #held(String, boolean)}): there the retry loop and auto-connect run as
 * before, so a power-cycle at a stop with Volt in front reconnects. A ride that ended because the link dropped and never
 * came back (a walk-away out of range) sets none, so background reconnect and auto-record carry on. In this process only:
 * a service or process restart has no hold. When a hold starts out of sight, a reconnect attempt that has not
 * authenticated yet is closed ({@link #closesAttempt}); an authenticated link is never closed by a hold. Pure:
 * BleController asks it before each automatic reconnect.
 */
final class OffReconnectHold {
    private final Set<String> held = new LinkedHashSet<>();

    /**
     * Whether this automatic end pauses reconnects: rules 1 and 2 (lost while parked, or went quiet, the switched-off
     * case included) on a scooter ScooterPowerState judges. Never the phone's own Bluetooth off, the no-motion rule, the
     * rider's stop, or a scooter that is never judged (other models that never pushed, the simulator).
     */
    static boolean pauses(String stopReason, boolean judged, boolean seenOff) { return seenOff && pauses(stopReason, judged); }

    /**
     * The stop's part of {@link #pauses(String, boolean, boolean)}: rules 1 and 2 on a judged scooter. A hold also needs
     * the scooter seen off on a live link during this end (RideAutoStopPolicy.seenOffWhileLinked).
     */
    static boolean pauses(String stopReason, boolean judged) {
        if (!judged || stopReason == null) return false;
        if (RideStore.STOP_AUTO_PARKED.equals(stopReason)) return true;
        return RideStore.isQuietStop(stopReason) && !stopReason.contains(";phone_bt=off");
    }

    synchronized void hold(String profileId) { if (profileId != null && !profileId.isEmpty()) held.add(profileId); }
    /** Whether a hold is recorded for this profile (in sight or not). */
    synchronized boolean held(String profileId) { return profileId != null && held.contains(profileId); }
    /** Whether Volt holds automatic reconnects to this profile now: never while Volt is in sight. */
    synchronized boolean held(String profileId, boolean visible) { return !visible && held(profileId); }
    /** When a hold starts out of sight: close an attempt whose GATT client is open but not yet authenticated. */
    static boolean closesAttempt(boolean gattOpen, boolean authenticated) { return gattOpen && !authenticated; }
    /** The rider connected, or the scooter pushed: true when a hold ended. */
    synchronized boolean release(String profileId) { return profileId != null && held.remove(profileId); }
    /** Volt opened again: true when any hold ended. */
    synchronized boolean releaseAll() { boolean any = !held.isEmpty(); held.clear(); return any; }
}
