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
     * Whether setup may delete the scooter profile holding a key it made. Once Volt has tried the key on a scooter,
     * that scooter may have taken it as its owner key, and deleting the only copy would leave the scooter with an
     * owner nobody holds. So only a key that was never sent, or one the scooter refused outright, may go, and never
     * one a scooter accepted (its profile has a saved scooter address, which Volt stores only after the owner check).
     */
    static boolean removable(boolean sent, boolean refused, boolean accepted) { return !accepted && (!sent || refused); }
}
