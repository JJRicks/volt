package com.jjricks.volt;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Synthetic keys only; no Android needed (tests/l10n/new-owner-key.sources). */
public final class NewOwnerKeyTest {
    private static int assertions;

    public static void main(String[] args) {
        shape();
        randomness();
        acceptedEverywhereAKeyIs();
        removal();
        offered();
        lockoutChains();
        transportOrder();
        System.out.println("NewOwnerKeyTest: " + assertions + " assertions passed");
    }

    private static void shape() {
        for (int seed = 0; seed < 200; seed++) {
            String key = NewOwnerKey.generate(new Random(seed));
            check(key.length() == 12, "twelve digits: " + key);
            check(key.matches("[0-9A-F]{12}"), "upper-case hex: " + key);
            check(key.startsWith("88"), "NAVEE's account marker first: " + key);
            check(shaped(key), "shaped: " + key);
        }
        // Every byte after the marker can take any value, including 0x00 and 0xFF.
        Random zeros = new Random() { @Override public void nextBytes(byte[] b) { java.util.Arrays.fill(b, (byte) 0); } };
        check("880000000000".equals(NewOwnerKey.generate(zeros)), "all-zero tail kept");
        Random ones = new Random() { @Override public void nextBytes(byte[] b) { java.util.Arrays.fill(b, (byte) 0xff); } };
        check("88FFFFFFFFFF".equals(NewOwnerKey.generate(ones)), "all-0xFF tail kept");
        check(!shaped(null), "null not shaped");
        check(!shaped("860012345678"), "another top byte not shaped");
        check(!shaped("88001234567"), "eleven digits not shaped");
        check(!shaped("88001234567G"), "non-hex not shaped");
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
        check(NewOwnerKey.removable(false, false), "never offered, never accepted: removable");
        check(!NewOwnerKey.removable(true, false), "offered: kept, whatever happened after");
        check(!NewOwnerKey.removable(true, true), "offered and accepted: kept");
        check(!NewOwnerKey.removable(false, true), "accepted without a recorded offer (restored state): kept");
    }

    /** The transport's record (OnboardingScreen.keyOffered, called by BleController.authenticate before the 0x30). */
    private static void offered() {
        check(NewOwnerKey.marksOffered("made", "made", false), "setup's own key, first owner check: recorded");
        check(!NewOwnerKey.marksOffered("made", "made", true), "already recorded: no second write (3 rounds, reconnects)");
        check(!NewOwnerKey.marksOffered("other", "made", false), "another profile's owner check: not this key");
        check(!NewOwnerKey.marksOffered("other", "", false), "no made key in this setup: nothing to record");
        check(!NewOwnerKey.marksOffered("", "", false), "empty ids never match");
        check(!NewOwnerKey.marksOffered(null, "made", false), "no profile: nothing recorded");
    }

    /**
     * The council's lockout chains for #485 (F07, F17), as the record and the rule see them. The record is made by the
     * transport whatever started the connection, so none of these depend on setup's own pages; the emulator harness
     * (android-tests/NewOwnerKeyValidation.java) drives the screens and the real BleController for the same chains.
     */
    private static void lockoutChains() {
        // Nearby devices granted while the service still binds, then setup's auto-connect: the owner check goes out.
        boolean offered = offer(false, "made", "made");
        check(!NewOwnerKey.removable(offered, false), "auto-connect after a permission result: kept");
        // ScooterService.connectLast() reconnects with the made key without setup's Connect page.
        check(!NewOwnerKey.removable(offer(false, "made", "made"), false), "service's own reconnect: kept");
        // The link drops after the owner check, before remember() saves the address; then a typed key is saved.
        check(!NewOwnerKey.removable(offer(false, "made", "made"), false), "dropped before the address was saved: kept");
        // Scooter A may have taken it; Try again picks owned scooter B, which refuses; then Set up another way.
        boolean a = offer(false, "made", "made"), b = offer(a, "made", "made");
        check(b && !NewOwnerKey.removable(b, false), "refusal from B after A: kept");
        // The chooser is cancelled before any scooter is picked: no owner check, so nothing is recorded.
        check(NewOwnerKey.removable(false, false), "chooser cancelled, never offered: may go");
        // A scooter saved under another profile is refused before the owner check (BleController): never offered.
        check(NewOwnerKey.removable(false, false), "saved scooter refused before the owner check: may go");
        // Another profile's connection never marks the made key.
        check(NewOwnerKey.removable(offer(false, "typed", "made"), false), "another profile's owner check: may go");
    }

    private static boolean offer(boolean already, String profileId, String generatedId) {
        return already || NewOwnerKey.marksOffered(profileId, generatedId, already);
    }

    /** The order inside BleController.authenticate(): the saved-hardware refusal, then the record, then the 0x30. */
    private static void transportOrder() {
        String ble = read("src/com/jjricks/volt/BleController.java");
        int start = ble.indexOf("private void authenticate(){"), end = ble.indexOf("private void send(byte[] frame)", start);
        check(start >= 0 && end > start, "authenticate() found");
        String body = ble.substring(start, end);
        int sim = body.indexOf("if(simulating())return;"), saved = body.indexOf("profiles.hardwareMatch(profile.id,gatt.getDevice().getAddress(),null)");
        int hook = body.indexOf("OnboardingScreen.keyOffered(context,profile.id);"), auth = body.indexOf("send(Protocol.auth(");
        check(sim >= 0 && sim < saved, "simulation returns before anything (never reaches the record)");
        check(saved > 0 && saved < hook, "saved-hardware refusal before the record");
        check(hook > 0 && hook < auth, "record before the owner check is queued");
        check(body.indexOf("send(Protocol.auth(", auth + 1) < 0 && ble.indexOf("Protocol.auth(") == ble.indexOf("Protocol.auth(", start),
                "authenticate() is BleController's only owner check");
        String screen = read("src/com/jjricks/volt/OnboardingScreen.java");
        int connect = screen.indexOf("private void toConnect() {"), after = screen.indexOf("private void afterProfile()", connect);
        check(connect > 0 && !screen.substring(connect, after).contains("KEY_GENERATED_SENT"),
                "setup's Connect page does not decide whether the key was offered");
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError("cannot read " + path, e); }
    }

    /** Twelve hex digits starting 88: the shape of every key generate() makes. */
    private static boolean shaped(String key) { return key != null && key.matches("(?i)88[0-9a-f]{10}"); }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        assertions++;
    }
}
