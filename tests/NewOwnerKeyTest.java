package com.jjricks.volt;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Synthetic keys only. Compile with Protocol, OwnerIdentityParser and NewOwnerKey; no Android needed. */
public final class NewOwnerKeyTest {
    private static int assertions;

    public static void main(String[] args) {
        shape();
        randomness();
        acceptedEverywhereAKeyIs();
        removal();
        System.out.println("NewOwnerKeyTest: " + assertions + " assertions passed");
    }

    private static void shape() {
        for (int seed = 0; seed < 200; seed++) {
            String key = NewOwnerKey.generate(new Random(seed));
            check(key.length() == 12, "twelve digits: " + key);
            check(key.matches("[0-9A-F]{12}"), "upper-case hex: " + key);
            check(key.startsWith("88"), "NAVEE's account marker first: " + key);
            check(NewOwnerKey.shaped(key), "shaped: " + key);
        }
        // Every byte after the marker can take any value, including 0x00 and 0xFF.
        Random zeros = new Random() { @Override public void nextBytes(byte[] b) { java.util.Arrays.fill(b, (byte) 0); } };
        check("880000000000".equals(NewOwnerKey.generate(zeros)), "all-zero tail kept");
        Random ones = new Random() { @Override public void nextBytes(byte[] b) { java.util.Arrays.fill(b, (byte) 0xff); } };
        check("88FFFFFFFFFF".equals(NewOwnerKey.generate(ones)), "all-0xFF tail kept");
        check(!NewOwnerKey.shaped(null), "null not shaped");
        check(!NewOwnerKey.shaped("860012345678"), "another top byte not shaped");
        check(!NewOwnerKey.shaped("88001234567"), "eleven digits not shaped");
        check(!NewOwnerKey.shaped("88001234567G"), "non-hex not shaped");
    }

    private static void randomness() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) seen.add(NewOwnerKey.generate());
        check(seen.size() == 500, "500 SecureRandom keys all differ, got " + seen.size());
        // The five random bytes each vary (not a counter or a fixed byte).
        for (int position = 2; position < 12; position += 2) {
            Set<String> values = new HashSet<>();
            for (String key : seen) values.add(key.substring(position, position + 2));
            check(values.size() > 100, "byte at " + position / 2 + " varies, saw " + values.size());
        }
    }

    private static void acceptedEverywhereAKeyIs() {
        for (int seed = 0; seed < 50; seed++) {
            String key = NewOwnerKey.generate(new Random(seed));
            // The typed-key path and owner files read it back unchanged, as an owner (role 0).
            OwnerIdentityParser.Result parsed = OwnerIdentityParser.parse(key);
            check(parsed.identity.equalsIgnoreCase(key) && parsed.role == 0, "parser keeps it: " + key);
            // The owner check carries it as bytes 7-12 of a 17-byte 0x30 frame.
            byte[] frame = Protocol.auth(Protocol.fromHex(key), 1, 0);
            check(frame.length == 17 && (frame[3] & 255) == 0x30, "0x30 frame");
            check(Protocol.hex(java.util.Arrays.copyOfRange(frame, 7, 13)).equalsIgnoreCase(key), "key bytes in the frame");
            check(frame[6] == 0, "role 0 (owner)");
        }
    }

    private static void removal() {
        check(NewOwnerKey.removable(false, false, false), "never sent: removable");
        check(NewOwnerKey.removable(true, true, false), "refused by the scooter: removable");
        check(!NewOwnerKey.removable(true, false, false), "sent and not refused: kept");
        check(NewOwnerKey.removable(false, true, false), "refused without a send cannot happen, but stays removable");
        // A scooter took the key once (an earlier attempt): kept even if a later scooter refused it.
        check(!NewOwnerKey.removable(true, true, true), "accepted earlier, refused later: kept");
        check(!NewOwnerKey.removable(true, false, true), "accepted: kept");
        check(!NewOwnerKey.removable(false, false, true), "accepted without a recorded send (restored state): kept");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        assertions++;
    }
}
