package com.jjricks.volt;

/**
 * The rider's lock tap once a connection is ready. The switch shows the requested side at once (the remembered lock
 * state is reliable); the command waits for the scooter to accept writes, such as its first motion report or an
 * automatic write already in flight after connecting, then goes out once. The scooter's readback still decides: a
 * refusal, an unconfirmed change, a lost connection or a gate that stays shut puts the switch back.
 */
final class LockTapQueue {
    enum Action { NONE, SEND, REVERT }
    /** OPEN sends now; WAIT is a short post-connect hold (busy, stale status, no motion report yet); REFUSE lets the gate say why. */
    enum Gate { OPEN, WAIT, REFUSE }
    static final long HOLD_MS = 5000;
    private Boolean target;
    private long requestedAt;
    private boolean sent;
    /** Inside BleController.set: its refusal notice redraws the header, which must not read the unanswered send as unconfirmed. */
    private boolean sending;
    /** A riding-mode tap: it goes out only while the scooter is still stopped (Home's unlock keeps its recovery path). */
    private boolean stoppedOnly;
    private CharSequence failure;

    /** A tap toward target; a tap back to the confirmed side cancels a waiting one. False when nothing changes. */
    boolean request(Boolean confirmed, boolean target, long now) { return request(confirmed, target, now, false); }
    /** stoppedOnly: riding mode's switch, whose tap must not outlive the stopped gate (stoppedGateClosed). */
    boolean request(Boolean confirmed, boolean target, long now, boolean stoppedOnly) {
        if (confirmed == null) return false;
        if (sent) return false; // One command at a time; the readback settles it.
        if (this.target == null && confirmed == target) return false;
        if (this.target != null && confirmed == target) { clear(); return true; }
        this.target = target; requestedAt = now; failure = null; this.stoppedOnly = stoppedOnly; return true;
    }
    /** The side the switch shows: a requested one until the scooter settles it. */
    Boolean shown(Boolean confirmed) { return target != null ? target : confirmed; }
    boolean waiting() { return target != null; }
    boolean stoppedOnly() { return stoppedOnly; }
    Boolean target() { return target; }
    /** Why the last request was put back, once; null when nothing to say. */
    String takeFailure() { CharSequence text = failure; failure = null; return ConnectionText.english(text); }
    void sent() { sent = true; }
    /** Marks the send and holds step() until endSend(), so a redraw from inside the send can't judge it. */
    void beginSend() { sent = true; sending = true; }
    void endSend() { sending = false; }
    /** The send was refused before going out (the gate gives the reason itself). */
    void refused() { clear(); }

    /**
     * The gate for a waiting tap from the decision it would get once any write in flight finishes. Only the short post-connect
     * holds wait; a hard refusal (riding, a warning) is never held behind another write, so a tap made while moving can't
     * go out once the rider stops.
     */
    static Gate gate(boolean availableOnceIdle, CapabilityResolver.Reason reasonOnceIdle, boolean otherWritePending) {
        if (availableOnceIdle) return otherWritePending ? Gate.WAIT : Gate.OPEN;
        return reasonOnceIdle == CapabilityResolver.Reason.STATUS_STALE || reasonOnceIdle == CapabilityResolver.Reason.NO_MOTION_REPORT ? Gate.WAIT : Gate.REFUSE;
    }
    /** A hard refusal while another write is in flight: put the switch back now, with the gate's reason. */
    Action reject(CharSequence why) { return target == null || sent ? Action.NONE : revert(why); }
    /** Riding mode's stopped gate, looked at before any send: a waiting stopped-only tap is put back the moment it closes. */
    Action stoppedGateClosed(boolean stopped, CharSequence why) { return !stoppedOnly || stopped ? Action.NONE : reject(why); }
    /** One look at the connection: what to do with a waiting or sent tap. lockPending is this lock change in flight. */
    Action step(long now, boolean ready, Boolean confirmed, boolean lockPending, Gate gate) {
        if (target == null || sending) return Action.NONE;
        if (!ready) return revert(M.bleLockConnectionLost());
        if (target.equals(confirmed)) { clear(); return Action.NONE; }
        if (sent) return lockPending ? Action.NONE : revert(M.bleLockNotConfirmed());
        // Expiry comes before any gate: a tap is never sent late, even when the gate opens on the first look past the hold.
        if (now - requestedAt > HOLD_MS) return revert(M.bleLockNotReady());
        return gate == Gate.WAIT ? Action.NONE : Action.SEND;
    }
    private Action revert(CharSequence why) { clear(); failure = why; return Action.REVERT; }
    private void clear() { target = null; sent = false; sending = false; stoppedOnly = false; requestedAt = 0; }

    // ---- Display text (l10n batch B7): takeFailure() stays the English that MainActivity and the tests read. ----

    /** Why the last request was put back. */
    enum Failure {
        /** The connection dropped before the lock changed. */
        CONNECTION_LOST,
        /** The command went out and the scooter did not confirm it. */
        NOT_CONFIRMED,
        /** The scooter did not accept writes within the hold; nothing was sent. */
        NOT_READY,
        /** A hard refusal (reject): the gate's own reason. */
        REFUSED
    }
    /** What takeFailure() would return, to show in the current language; the same once-only take. Null when nothing to say. */
    CharSequence takeFailureMessage() { CharSequence text = failure; failure = null; return text; }
    /** The kind of the waiting reason, without taking it; null when nothing to say. */
    Failure failureKind() {
        if (failure == null) return null;
        int id = failure instanceof Msg ? ((Msg) failure).id() : Msg.VERBATIM;
        return id == T.BLE_LOCK_CONNECTION_LOST ? Failure.CONNECTION_LOST : id == T.BLE_LOCK_NOT_CONFIRMED ? Failure.NOT_CONFIRMED
                : id == T.BLE_LOCK_NOT_READY ? Failure.NOT_READY : Failure.REFUSED;
    }
}
