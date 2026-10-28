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
    private String failure;

    /** A tap toward target; a tap back to the confirmed side cancels a waiting one. False when nothing changes. */
    boolean request(Boolean confirmed, boolean target, long now) {
        if (confirmed == null) return false;
        if (sent) return false; // One command at a time; the readback settles it.
        if (this.target == null && confirmed == target) return false;
        if (this.target != null && confirmed == target) { clear(); return true; }
        this.target = target; requestedAt = now; failure = null; return true;
    }
    /** The side the switch shows: a requested one until the scooter settles it. */
    Boolean shown(Boolean confirmed) { return target != null ? target : confirmed; }
    boolean waiting() { return target != null; }
    Boolean target() { return target; }
    /** Why the last request was put back, once; null when nothing to say. */
    String takeFailure() { String text = failure; failure = null; return text; }
    void sent() { sent = true; }
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
    Action reject(String why) { return target == null || sent ? Action.NONE : revert(why); }
    /** One look at the connection: what to do with a waiting or sent tap. lockPending is this lock change in flight. */
    Action step(long now, boolean ready, Boolean confirmed, boolean lockPending, Gate gate) {
        if (target == null) return Action.NONE;
        if (!ready) return revert("Connection lost before the lock changed");
        if (target.equals(confirmed)) { clear(); return Action.NONE; }
        if (sent) return lockPending ? Action.NONE : revert("The scooter didn't confirm the lock change");
        // Expiry comes before any gate: a tap is never sent late, even when the gate opens on the first look past the hold.
        if (now - requestedAt > HOLD_MS) return revert("The scooter wasn't ready to change the lock; try again");
        return gate == Gate.WAIT ? Action.NONE : Action.SEND;
    }
    private Action revert(String why) { clear(); failure = why; return Action.REVERT; }
    private void clear() { target = null; sent = false; requestedAt = 0; }
}
