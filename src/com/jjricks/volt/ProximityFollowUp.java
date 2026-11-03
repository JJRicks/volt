package com.jjricks.volt;

import java.util.function.Consumer;

/**
 * Proximity unlock follow-ups that never write to the scooter. Pure (no Android), so the JVM tests cover the rules
 * BleController and More apply.
 *
 * Some NAVEE scooters (a GT3 Max capture) restart Bluetooth right after a 0x61 proximity change; the official app
 * expects that drop and reconnects. A change that was sent when the link dropped is therefore not "cancelled": a
 * Recheck remembers the value Volt sent, for this scooter only and for a bounded time, and the first status read after
 * the automatic reconnect (offset 13, bit 0) confirms it or not.
 *
 * Volt never undoes proximity by itself. The official app sends 0x61 00 when the phone is not paired; Volt only says
 * so and offers Pair again or turning it off.
 */
final class ProximityFollowUp {
    static final String KEY = "proximity_key";
    /** How long a recheck waits for the reconnect: the 1, 2, 4, 8 and 16 s retries and an 18 s connect timeout fit. */
    static final long RECHECK_WINDOW_MS = 60_000;

    /**
     * Drop wording. The scooter itself closing the link (NATIVE_DISCONNECT) may be the Bluetooth restart; a write error,
     * timeout or lost signal says nothing about the scooter, so those stay neutral.
     */
    static final String RECONNECTING_RESULT = "Sent; the connection dropped. Reconnecting to confirm";
    static final String RECONNECTING_NATIVE_RESULT = "Sent; the scooter may have restarted Bluetooth. Reconnecting to confirm";
    static final String RECONNECTING_NATIVE_NOTICE = "Scooter may have restarted Bluetooth · reconnecting to confirm";
    static final String RECONNECTING_NOTE = "The connection dropped after the change was sent; some scooters restart Bluetooth here. Reconnecting to confirm…";
    /** A sent change whose link closed with no reconnect to check it: the scooter may have taken it, so never "cancelled". */
    static final String NOT_CHECKED_RESULT = "Not confirmed: disconnected before the scooter could be checked";
    static final String NOT_CHECKED_SUFFIX = " · change not confirmed";
    static final String NOT_PAIRED_NOTE = "Proximity unlock is on, but this phone isn't paired. Pair it here, or turn proximity unlock off.";
    static final String PAIRING_ENDED_NOTE = "Pairing didn't finish, and proximity unlock is still on. Pair again, or turn proximity unlock off.";
    static final String PAIRING_INACTIVE = "Bluetooth pairing is not active.";

    private ProximityFollowUp() {}

    enum Verdict {
        /** The first status read after the reconnect shows the value sent. */
        CONFIRMED,
        /** It shows the other value. */
        NOT_CONFIRMED,
        /** The scooter reconnected, but its status does not report proximity unlock. */
        UNREADABLE,
        /** No reconnect within RECHECK_WINDOW_MS. */
        EXPIRED,
        /** The reconnect reached another scooter: the marker is dropped, never applied there. */
        OTHER_SCOOTER,
        /** Volt stopped connecting (disconnect, background pause, Bluetooth off) before it could check. */
        ABANDONED
    }

    /** The expected value of a proximity change whose link dropped before it was confirmed. */
    static final class Recheck {
        final String profileId, serial;
        final MutationGate.Request request;
        final int target;
        final boolean ack;
        /** Monotonic times: when the write went out and when the link dropped. */
        final long sentMs, droppedMs;
        /** The pending change's completion, carried across the reconnect so it fires once, truthfully. */
        Consumer<Boolean> completion;

        Recheck(String profileId, String serial, MutationGate.Request request, int target, boolean ack, long sentMs, long droppedMs,
                Consumer<Boolean> completion) {
            this.profileId = profileId; this.serial = serial == null ? "" : serial; this.request = request; this.target = target;
            this.ack = ack; this.sentMs = sentMs; this.droppedMs = droppedMs; this.completion = completion;
        }

        boolean expired(long nowMs) { return nowMs < droppedMs || nowMs - droppedMs > RECHECK_WINDOW_MS; }

        /**
         * The first status read after the reconnect. profileId/serial: the scooter now connected (its serial verified on
         * this transport); read: its proximity bit, or null when its status does not report it.
         */
        Verdict judge(String profileId, String serial, Integer read, long nowMs) {
            if (profileId == null || !profileId.equals(this.profileId)) return Verdict.OTHER_SCOOTER;
            if (!this.serial.isEmpty() && serial != null && !serial.isEmpty() && !serial.equals(this.serial)) return Verdict.OTHER_SCOOTER;
            if (expired(nowMs)) return Verdict.EXPIRED;
            if (read == null) return Verdict.UNREADABLE;
            return read == target ? Verdict.CONFIRMED : Verdict.NOT_CONFIRMED;
        }

        /** The control's result text (Command evidence, report diagnostics) for a verdict. */
        String result(Verdict v, Integer read) {
            switch (v) {
                case CONFIRMED: return "Confirmed after the scooter reconnected";
                case NOT_CONFIRMED: return "Not confirmed: after reconnecting the scooter reads " + onOff(read == null ? 1 - target : read);
                case UNREADABLE: return "Not confirmed: the scooter reconnected without reporting proximity unlock";
                case EXPIRED: return "Not confirmed: the scooter did not reconnect in time";
                case ABANDONED: return "Not confirmed: disconnected before the scooter could be checked";
                default: return null; // OTHER_SCOOTER: that scooter's results are already cleared
            }
        }

        /** The short notice for a verdict, or null to stay quiet. */
        String notice(Verdict v) {
            switch (v) {
                case CONFIRMED: return "Proximity unlock " + onOff(target) + ", confirmed.";
                case NOT_CONFIRMED: case UNREADABLE: case EXPIRED: return "Proximity unlock change not confirmed";
                default: return null;
            }
        }

        /**
         * The command log's second event for this change, or null. The drop itself was logged as CommandLog.closed
         * (UNCONFIRMED after an ACK, INTERRUPTED without one); only a readback adds evidence.
         */
        CommandLog.Outcome outcome(Verdict v) {
            return v == Verdict.CONFIRMED ? CommandLog.Outcome.READBACK_SAME : v == Verdict.NOT_CONFIRMED ? CommandLog.Outcome.READBACK_OTHER : null;
        }

        /** Whether the pending change succeeded. */
        boolean success(Verdict v) { return v == Verdict.CONFIRMED; }
    }

    private static String onOff(int value) { return value == 1 ? "on" : "off"; }

    /**
     * A link drop with this change pending: a Recheck when it was a sent, non-guided proximity change and Volt is about
     * to reconnect to the same scooter; otherwise null (the change is reported as before).
     */
    static Recheck afterDrop(String key, boolean sent, boolean guided, boolean reconnect, String profileId, String serial,
            MutationGate.Request request, int target, boolean ack, long sentMs, long nowMs, Consumer<Boolean> completion) {
        if (!KEY.equals(key) || !sent || guided || !reconnect || profileId == null || profileId.isEmpty() || request == null) return null;
        return new Recheck(profileId, serial, request, target, ack, sentMs, nowMs, completion);
    }

    /** A sent, non-guided proximity change: a drop never makes it "cancelled", since the scooter may have taken it. */
    static boolean unconfirmedOnDrop(String key, boolean sent, boolean guided) { return KEY.equals(key) && sent && !guided; }

    /**
     * The control's row result when the link closes with this change pending. reconnecting: a Recheck carries it to the
     * automatic reconnect; nativeDrop: the scooter closed the link (rather than a local write error or timeout).
     */
    static String closedResult(String key, boolean sent, boolean guided, boolean reconnecting, boolean nativeDrop) {
        if (!sent) return "Cancelled before sending";
        if (!unconfirmedOnDrop(key, sent, guided)) return "Cancelled after disconnection";
        if (reconnecting) return nativeDrop ? RECONNECTING_NATIVE_RESULT : RECONNECTING_RESULT;
        return NOT_CHECKED_RESULT;
    }

    /**
     * The short notice for a drop. text: the drop's own wording ("Send failed", "Bluetooth is off", ...); pending: a
     * change was in flight; retrying: Volt reconnects automatically.
     */
    static String dropNotice(String text, boolean pending, String key, boolean sent, boolean guided, boolean reconnecting, boolean nativeDrop, boolean retrying) {
        if (pending && unconfirmedOnDrop(key, sent, guided)) {
            if (reconnecting) return nativeDrop ? RECONNECTING_NATIVE_NOTICE : text + " · reconnecting to confirm";
            return text + NOT_CHECKED_SUFFIX;
        }
        return text + (pending ? " · change cancelled" : retrying ? " · retrying" : "");
    }

    /** set(): only turning proximity on waits for a fresh PIN read; turning it off is sent as soon as it is admitted. */
    static boolean pinReadFirst(int value, boolean pinFresh) { return value == 1 && !pinFresh; }

    /**
     * The plain line under the Proximity unlock row, or null. Shown only while connected as the owner with a fresh
     * reading, since pairing is unknown while disconnected.
     */
    static String sectionNote(boolean owner, boolean ready, Integer proximity, boolean paired, boolean rechecking, boolean pairingEnded) {
        if (rechecking) return RECONNECTING_NOTE;
        if (!owner || !ready || proximity == null || proximity != 1 || paired) return null;
        return pairingEnded ? PAIRING_ENDED_NOTE : NOT_PAIRED_NOTE;
    }

    /** The proximity switch: turning it off never needs the pairing (or the PIN); turning it on needs both. */
    static boolean switchEnabled(boolean owner, boolean busy, Integer proximity, boolean paired, boolean pinOn) {
        return owner && !busy && proximity != null && (proximity == 1 || paired && pinOn);
    }

    /** Pair this phone: after the PIN step, or whenever proximity reads on without a pairing (bond() reads the PIN itself). */
    static boolean pairEnabled(boolean owner, boolean busy, boolean paired, boolean pinOn, Integer proximity) {
        return owner && !busy && !paired && (pinOn || proximity != null && proximity == 1);
    }

    /** Bond state BOND_NONE for this scooter: what to say. Never a write. */
    static String pairingEndedNotice(boolean attempted, Integer proximity) {
        return attempted && proximity != null && proximity == 1 ? PAIRING_ENDED_NOTE : PAIRING_INACTIVE;
    }

    /** Bond state BOND_BONDED for this scooter. */
    static String pairedNotice(Integer proximity) {
        return proximity != null && proximity == 1 ? "Phone paired. Proximity unlock is on." : "Phone paired. You can now enable proximity unlock.";
    }
}
