package com.jjricks.volt;

/**
 * Release this scooter (owner, 2026-11-13): the connected scooter forgets its owner code, as it does after the official
 * app's unbind, so it behaves like a new or reset scooter and the NAVEE app or another Volt owner code can add it again.
 * The official app sends the same frame over its owner-authenticated link once its server unbind succeeds; Volt has no
 * server step, so a NAVEE account that lists the scooter keeps listing it. Not yet tried on a scooter
 * (docs/validation/owner-release.md).
 *
 * A fresh scooter takes the first owner code it hears as its owner, so a release that worked must end the link and
 * remove the profile at once (ScooterService.releaseScooter): reconnecting with that profile would claim it again. Any
 * other ending keeps the profile, and a reconnect claims the scooter back if it did forget its owner after all, which
 * leaves Volt and the scooter agreeing. Pure: BleController sends the frame, the gate is CapabilityResolver.decideRelease.
 */
final class OwnerRelease {
    /** The gate's feature and the command log's key: a lower-case id, never shown. */
    static final String KEY = "owner_release";
    /** The command (Protocol.ownerRelease builds the only frame with it: data byte 1). */
    static final int CMD = 0x50;

    /** How a release ended. */
    enum Outcome {
        /** The scooter answered status 0: it forgot its owner code; Volt closed the link and removed the profile. */
        RELEASED,
        /** The scooter answered another status: nothing changed, the profile stays. */
        REJECTED,
        /** Sent, then no answer (timeout) or the link closed first: it may or may not have taken it; the profile stays. */
        NOT_CONFIRMED,
        /** Refused or cancelled before it was sent (moving, busy, link lost while queued): nothing changed. */
        NOT_SENT
    }

    private OwnerRelease() {}

    /** Offered only for an owner profile (role 0): a shared user's code cannot release a scooter. */
    static boolean offered(int role) { return role == 0; }

    /** The scooter's reply: status 0 released it, anything else is a refusal. */
    static Outcome reply(int status) { return status == 0 ? Outcome.RELEASED : Outcome.REJECTED; }

    /** A release that ended without the scooter's reply (timeout, link closed, cancelled while queued). */
    static Outcome unanswered(boolean sent) { return sent ? Outcome.NOT_CONFIRMED : Outcome.NOT_SENT; }

    /** Only a confirmed release removes the profile; every other ending keeps it, so nothing changes in Volt. */
    static boolean forgetsProfile(Outcome outcome) { return outcome == Outcome.RELEASED; }
}
