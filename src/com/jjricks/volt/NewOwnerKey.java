package com.jjricks.volt;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;

/**
 * A new owner key for a scooter that has no owner yet: new, or removed from the NAVEE app. The owner check (0x30)
 * carries six key bytes and a role with no account or server step, and the official app's unbind leaves the scooter
 * without an owner, so it takes the next owner key it is given (docs/validation/new-scooter-setup.md). NAVEE's app
 * sends its account's numeric user ID as six big-endian bytes, with the top byte set to 0x88 when the ID leaves it
 * zero, which every ordinary account does. A new key keeps that 0x88 marker and fills the other five bytes at random,
 * so it has the shape of any account's key and can't be guessed from anything.
 */
final class NewOwnerKey {
    /** The top byte NAVEE's app puts on every ordinary account's key. */
    static final int MARKER = 0x88;

    private NewOwnerKey() { }

    /** Twelve upper-case hex digits: 88 and five random bytes. */
    static String generate() { return generate(new SecureRandom()); }

    static String generate(Random random) {
        byte[] key = new byte[6];
        byte[] rest = new byte[5];
        random.nextBytes(rest);
        key[0] = (byte) MARKER;
        System.arraycopy(rest, 0, key, 1, rest.length);
        return Protocol.hex(key).toUpperCase(Locale.ROOT);
    }

    /**
     * Whether setup may delete the scooter profile holding a key it made: only if Volt never offered the key to any
     * scooter and no scooter accepted it. An unowned scooter takes the first owner key it is sent and keeps it, and
     * the made key exists nowhere else, so once the owner check (0x30) carrying it has been queued for any scooter,
     * that scooter may hold it, even if the link dropped before its address was saved. A refusal later says nothing
     * about that earlier scooter, so it never licenses deletion. "Accepted" is a saved scooter address on the profile,
     * which Volt stores only after the owner check.
     */
    static boolean removable(boolean offered, boolean accepted) { return !offered && !accepted; }

    // ------------------------------------------------------------------ the made key's record

    /**
     * Every key Volt makes has a record on this phone, app_settings {@code PREFIX + profile ID}, written before its
     * profile exists and kept outside setup's own preferences, so it outlives setup (finished, skipped or started again
     * with Add a scooter). BackupRegistry keeps it on this phone. Its value is one of:
     * {@link #MADE} (never offered), {@link #OFFERED} (offered, tied to no scooter: the one it was tied to refused it),
     * or a scooter address: offered to that scooter, and tied to it. Only made keys have a record; a typed, imported
     * or captured key never does, so none of the rules below touch them.
     */
    static final String PREFIX = "made_owner_key";
    static final String MADE = "made", OFFERED = "offered";

    static String key(String profileId) { return PREFIX + profileId; }

    /** Whether the key with this record may be on a scooter (null: not a made key, nothing to say). */
    static boolean offered(String record) { return record != null && !MADE.equals(record); }

    /** The scooter address a made key is tied to, or "" when it is tied to none (never offered, or refused). */
    static String tie(String record) { return record == null || MADE.equals(record) || OFFERED.equals(record) ? "" : record; }

    /** What the connection controller does with a made key's record just before an owner check to {@code address}. */
    enum Offer {
        /** Send the owner check: not a made key, or a made key already tied to this scooter. */
        SEND,
        /** Commit the tie to this scooter (and with it the offer) in one write, then send; if the write fails, send nothing. */
        TIE,
        /** Send nothing: the key is tied to another scooter, which may hold it. No reconnect. */
        ELSEWHERE,
        /** Send nothing: the tie couldn't be saved (the controller's outcome of TIE when the write fails). No reconnect. */
        UNSAVED
    }

    /**
     * Once a made key is offered to the scooter at one address, it is offered to no other address until that scooter
     * refuses it ({@link #afterRefusal}): an unowned scooter keeps the first owner key it hears, so a second scooter
     * taking the same key would leave two scooters on one key in one profile, and forgetting or releasing one would
     * lock the other out. With no address to tie to, nothing is sent.
     */
    static Offer offer(String record, String address) {
        if (record == null) return Offer.SEND;
        if (address == null || address.isEmpty()) return Offer.ELSEWHERE;
        String tie = tie(record);
        if (tie.isEmpty()) return Offer.TIE;
        return tie.equalsIgnoreCase(address) ? Offer.SEND : Offer.ELSEWHERE;
    }

    /**
     * The record after an owner-check refusal (0x30 with an error) from {@code address}: the scooter the key is tied
     * to doesn't hold it, so the tie goes and the key may go to another scooter; it stays offered, so setup still never
     * deletes it. A refusal from any other address (or for a key tied to none) changes nothing.
     */
    static String afterRefusal(String record, String address) {
        String tie = tie(record);
        return !tie.isEmpty() && tie.equalsIgnoreCase(address) ? OFFERED : record;
    }

    /**
     * Where a connection for this profile goes without a scan: its saved scooter, else the scooter its made key is
     * tied to (a link that dropped before the address was saved). "" means scan and let the owner choose.
     */
    static String connectAddress(String savedMac, String record) {
        return savedMac != null && !savedMac.isEmpty() ? savedMac : tie(record);
    }
}
