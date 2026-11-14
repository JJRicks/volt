package com.jjricks.volt;

/**
 * Release this scooter (owner, 2026-11-13): the connected scooter forgets its owner key, as it does after the official
 * app's unbind, so it behaves like a new or reset scooter and the NAVEE app or another Volt owner key can add it again.
 * The official app sends the same frame over its owner-authenticated link once its server unbind succeeds; Volt has no
 * server step, so a NAVEE account that lists the scooter keeps listing it. On the owner's UT5 MAX (Nov 14, 2026) the
 * scooter took it and answered by ending the link about 88 ms later, with no 0x50 reply (docs/validation/owner-release.md).
 *
 * A fresh scooter takes the first owner key it hears as its owner, so a release that worked must end the link and
 * remove the profile at once (ScooterService.releaseScooter): reconnecting with that profile would claim it again. So
 * a status-0 reply or a quick drop ({@link #afterDrop}) counts as released. Any other ending after the frame went out
 * keeps the profile but holds Volt's automatic reconnects to it ({@link #holds}) until the owner chooses Remove from
 * Volt (Forget, with its backup rule) or Reconnect, which claims the scooter back if it did forget its owner. Pure:
 * BleController sends the frame, the gate is CapabilityResolver.decideRelease.
 */
final class OwnerRelease {
    /** The gate's feature and the command log's key: a lower-case id, never shown. */
    static final String KEY = "owner_release";
    /** The command (Protocol.ownerRelease builds the only frame with it: data byte 1). */
    static final int CMD = 0x50;

    /** How a release ended. */
    enum Outcome {
        /**
         * The scooter answered status 0, or ended the link itself within QUICK_DROP_MS of the send: it forgot its owner
         * key; Volt closed the link and removed the profile.
         */
        RELEASED,
        /** The scooter answered another status: nothing changed, the profile stays. */
        REJECTED,
        /**
         * Sent, then no answer (timeout) or the link closed other than by a quick drop: it may or may not have taken it;
         * the profile stays and Volt doesn't reconnect to it by itself until the owner chooses.
         */
        NOT_CONFIRMED,
        /** Refused or cancelled before it was sent (moving, busy, link lost while queued): nothing changed. */
        NOT_SENT
    }

    /**
     * How long after the frame went out a link the scooter ends still counts as its answer (owner, Nov 14, 2026): the
     * UT5 MAX ends it about 88 ms after the release, with no status reply.
     */
    static final long QUICK_DROP_MS = 1000;
    /** Android's GATT status for a supervision timeout: the link was lost (out of range, interference), not ended. */
    static final int LINK_LOST = 8;
    /** Android's GATT status for a link this phone ended. */
    static final int PHONE_ENDED = 22;

    private OwnerRelease() {}

    /** Offered only for an owner profile (role 0): a shared user's code cannot release a scooter. */
    static boolean offered(int role) { return role == 0; }

    /** The scooter's reply: status 0 released it, anything else is a refusal. */
    static Outcome reply(int status) { return status == 0 ? Outcome.RELEASED : Outcome.REJECTED; }

    /** A release that ended without the scooter's reply (timeout, link closed, cancelled while queued). */
    static Outcome unanswered(boolean sent) { return sent ? Outcome.NOT_CONFIRMED : Outcome.NOT_SENT; }

    /**
     * A release whose link ended before any reply. sent: the frame went out (Mutation.sent); nativeDrop: Android
     * reported the link closed (ConnectionEventLog.Reason.NATIVE_DISCONNECT), not a timeout, a failed write or Volt
     * closing it; adapterOn: this phone's Bluetooth is still on (turning it off is the phone's doing, never the
     * scooter's answer); code: the GATT status; sentMs and dropMs on one clock. Only the scooter ending the link within
     * {@link #QUICK_DROP_MS} of the send counts as released; anything else after the send is not confirmed.
     */
    static Outcome afterDrop(boolean sent, boolean nativeDrop, boolean adapterOn, int code, long sentMs, long dropMs) {
        if (!sent) return Outcome.NOT_SENT;
        if (!nativeDrop || !adapterOn || code == LINK_LOST || code == PHONE_ENDED) return Outcome.NOT_CONFIRMED;
        long after = dropMs - sentMs;
        return after >= 0 && after <= QUICK_DROP_MS ? Outcome.RELEASED : Outcome.NOT_CONFIRMED;
    }

    /** Only a confirmed release removes the profile; every other ending keeps it, so nothing changes in Volt. */
    static boolean forgetsProfile(Outcome outcome) { return outcome == Outcome.RELEASED; }

    /**
     * Whether the ending holds Volt's automatic reconnects to that scooter until the owner chooses: only a release that
     * went out unconfirmed. The scooter may have forgotten its owner, and a reconnect would claim it straight back. A
     * refusal (the scooter answered, nothing changed) and a release never sent reconnect as before.
     */
    static boolean holds(Outcome outcome) { return outcome == Outcome.NOT_CONFIRMED; }
}
