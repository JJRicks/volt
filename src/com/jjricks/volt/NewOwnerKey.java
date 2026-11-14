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
     * Every key Volt makes has a record, app_settings {@code PREFIX + profile ID}, written before its profile exists and
     * kept outside setup's own preferences, so it outlives setup (finished, skipped or started again with Add a scooter).
     * It travels with its profile in backups (Scooters & setup keys; BackupRegistry, BackupArchive's {@link #merged}).
     * Its value is one of: {@link #MADE} (never offered), {@link #OFFERED} (offered, tied to no scooter: the one it was
     * tied to refused it), or a scooter address: offered to that scooter, and tied to it. Only made keys have a record;
     * a typed, imported or captured key never does, so none of the rules below touch them.
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
        /** Send the owner check: not a made key, a saved scooter's key, or a made key already tied to this scooter. */
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
     * lock the other out. With no address to tie to, nothing is sent. The tie governs only while the profile has no
     * saved scooter address ({@code savedMac}): once a scooter accepted the key and its serial checked (remember()),
     * the profile's saved address and serial govern, as for any key, so a scooter whose Bluetooth address changed
     * still reconnects and remember() follows it on a serial match. The record stays (it still marks a made key).
     */
    static Offer offer(String record, String savedMac, String address) {
        if (record == null || (savedMac != null && !savedMac.isEmpty())) return Offer.SEND;
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
     * The record a restore keeps for one profile whose key is the same here and in the backup (BackupArchive): a
     * restore never loosens what this phone knows. A record that says the key may be on a scooter (offered, or tied)
     * wins over one that says it never was, or over none; between two such records this phone's stays, since it
     * governs this phone's connections; otherwise whichever exists. An older backup with no record changes nothing
     * here, and adds no record where there is none.
     */
    static String merged(String here, String incoming) {
        if (offered(here)) return here;
        if (offered(incoming)) return incoming;
        return here != null ? here : incoming;
    }

    // ------------------------------------------------------------------ writing the record

    /**
     * The made keys' records as Android's SharedPreferences holds them: an edit changes the in-memory map first, then
     * commit() reports whether it reached the disk, so after a failed write the next read still sees the new value.
     */
    interface Records {
        String get(String key);
        /** put (value non-null) or remove (null), then commit(): whether it reached the disk. Memory changes either way. */
        boolean write(String key, String value);
    }

    /**
     * Writes {@code value} over {@code before} (what {@link Records#get} just read). If the write fails (false, or a
     * throw), puts {@code before} back in memory with a second write, whose own result doesn't matter (memory is
     * restored either way; the disk never took the new value), so nothing reads a value the disk doesn't hold. Only
     * ever writes {@code value} or {@code before}.
     */
    static boolean save(Records records, String key, String before, String value) {
        boolean saved;
        try { saved = records.write(key, value); } catch (RuntimeException failed) { saved = false; }
        if (!saved) try { records.write(key, before); } catch (RuntimeException ignored) { }
        return saved;
    }

    /**
     * Just before an owner check for {@code profileId} to {@code address} ({@link #offer}): a TIE is written, and
     * becomes SEND only once it is on disk, else UNSAVED with the old record back in memory, so a retry ties again (and
     * again sends nothing if that fails), never SEND on a tie only memory holds. A read that throws reaches the caller.
     */
    static Offer offerTo(Records records, String profileId, String savedMac, String address) {
        String key = key(profileId), record = records.get(key);
        Offer offer = offer(record, savedMac, address);
        if (offer != Offer.TIE) return offer;
        return save(records, key, record, address) ? Offer.SEND : Offer.UNSAVED;
    }

    /** The scooter at {@code address} refused {@code profileId}'s owner check ({@link #afterRefusal}); false if that write failed. */
    static boolean refusedBy(Records records, String profileId, String address) {
        String key = key(profileId), record = records.get(key), after = afterRefusal(record, address);
        return after == null || after.equals(record) || save(records, key, record, after);
    }

    /** The record of a key about to be made under a new profile ID: {@link #MADE}, on disk, or nothing at all. */
    static boolean recordNew(Records records, String profileId) {
        String key = key(profileId);
        return save(records, key, records.get(key), MADE);
    }
}
