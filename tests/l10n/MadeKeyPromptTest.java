package com.jjricks.volt;

import java.util.Random;

/** Synthetic keys only; no Android needed (tests/l10n/made-key-prompt.sources). */
public final class MadeKeyPromptTest {
    private static int assertions;
    private static final String KEY = "886B33AF01C2", ID = "profile-1", MAC = "AA:BB:CC:DD:EE:FF";

    public static void main(String[] args) {
        when();
        shownOnce();
        waiting();
        written();
        readBack();
        neverInMessages();
        System.out.println("MadeKeyPromptTest: " + assertions + " assertions passed");
    }

    /** Failure mode 1: only a key this setup made, and only once the scooter accepted it (a saved address). */
    private static void when() {
        check(MadeKeyPrompt.due(true, ID, KEY, MAC, ""), "a made key the scooter accepted");
        check(MadeKeyPrompt.due(true, ID, KEY, MAC, null), "no record yet");
        check(!MadeKeyPrompt.due(false, ID, KEY, MAC, ""), "a captured, typed or imported key never");
        check(!MadeKeyPrompt.due(true, ID, KEY, "", ""), "not before the scooter accepted the key");
        check(!MadeKeyPrompt.due(true, ID, KEY, null, ""), "no saved address");
        check(!MadeKeyPrompt.due(true, "", KEY, MAC, ""), "no profile");
        check(!MadeKeyPrompt.due(true, null, KEY, MAC, ""), "no profile ID");
        check(!MadeKeyPrompt.due(true, ID, null, MAC, ""), "no key");
        check(!MadeKeyPrompt.due(true, ID, "886B33AF01", MAC, ""), "not a whole key");
        check(!MadeKeyPrompt.due(true, ID, "886B33AF01ZZ", MAC, ""), "not hex");
    }

    /** Failure mode 3: the record names the profile once dismissed; until then (a new activity, process death) it shows. */
    private static void shownOnce() {
        check(!MadeKeyPrompt.due(true, ID, KEY, MAC, ID), "dismissed for this profile: never again");
        check(MadeKeyPrompt.due(true, ID, KEY, MAC, "profile-0"), "a record for another profile's key doesn't count");
        check(MadeKeyPrompt.SHOWN.equals("made_key_shown"), "setup's preference name");
    }

    /** Failure modes 2 and 4: Done waits ten seconds, unless a backup holding the key was saved. */
    private static void waiting() {
        check(MadeKeyPrompt.WAIT_SECONDS == 10, "ten seconds");
        check(MadeKeyPrompt.secondsLeft(0) == 10, "10 at first");
        check(MadeKeyPrompt.secondsLeft(-5000) == 10, "a clock oddity never shortens the wait");
        check(MadeKeyPrompt.secondsLeft(1) == 10, "still 10 just after");
        check(MadeKeyPrompt.secondsLeft(1000) == 9, "9 after a second");
        check(MadeKeyPrompt.secondsLeft(9000) == 1, "1 near the end");
        check(MadeKeyPrompt.secondsLeft(9999) == 1, "1 until the full ten seconds");
        check(MadeKeyPrompt.secondsLeft(10000) == 0, "0 at ten seconds");
        check(MadeKeyPrompt.secondsLeft(Long.MAX_VALUE) == 0, "0 long after");
        for (long ms = 0; ms < 10000; ms += 250) check(!MadeKeyPrompt.doneEnabled(ms, false), "Done waits at " + ms);
        check(MadeKeyPrompt.doneEnabled(10000, false), "Done works after ten seconds");
        check(MadeKeyPrompt.doneEnabled(0, true), "a saved backup enables Done at once");
        check(MadeKeyPrompt.doneEnabled(4000, true), "a saved backup enables Done mid-wait");
        // A failed or cancelled save leaves no record, so the button keeps waiting.
        check(!MadeKeyPrompt.doneEnabled(4000, false), "no saved backup, still waiting");
    }

    /** K2: upper-case hex, a space between bytes, one row or two rows of three; spoken a character at a time. */
    private static void written() {
        check(MadeKeyPrompt.oneRow(KEY).equals("88 6B 33 AF 01 C2"), "one row: " + MadeKeyPrompt.oneRow(KEY));
        check(MadeKeyPrompt.oneRow("886b33af01c2").equals("88 6B 33 AF 01 C2"), "upper-case from lower-case");
        String two = MadeKeyPrompt.twoRows(KEY);
        String[] rows = two.split("\\R");
        check(rows.length == 2 && rows[0].equals("88 6B 33") && rows[1].equals("AF 01 C2"), "two rows of three: " + two);
        check(MadeKeyPrompt.firstRow(KEY).equals("88 6B 33"), "the first row");
        check(MadeKeyPrompt.spoken(KEY).equals("8 8, 6 B, 3 3, A F, 0 1, C 2"), "spoken: " + MadeKeyPrompt.spoken(KEY));
        check(MadeKeyPrompt.oneRow("000000000000").equals("00 00 00 00 00 00"), "leading zeros kept");
        check(MadeKeyPrompt.oneRow("FFFFFFFFFFFF").equals("FF FF FF FF FF FF"), "FF");
        check(MadeKeyPrompt.spoken("880000FF0A00").equals("8 8, 0 0, 0 0, F F, 0 A, 0 0"), "zeros spoken");
        Random random = new Random(7);
        for (int i = 0; i < 300; i++) {
            String key = NewOwnerKey.generate(random);
            String one = MadeKeyPrompt.oneRow(key);
            check(one.matches("[0-9A-F]{2}( [0-9A-F]{2}){5}"), "bytes kept whole: " + one);
            check(one.replace(" ", "").equals(key), "same digits");
            for (String row : MadeKeyPrompt.twoRows(key).split("\\R")) check(row.matches("[0-9A-F]{2}( [0-9A-F]{2}){2}"), "row of three: " + row);
        }
        for (String bad : new String[] {null, "", "886B33AF01", "886B33AF01C2D4", "886B33AF01GG", "88 6B 33 AF", "\uFF18\uFF186B33AF01C2"}) {
            check(MadeKeyPrompt.oneRow(bad).isEmpty() && MadeKeyPrompt.twoRows(bad).isEmpty()
                    && MadeKeyPrompt.firstRow(bad).isEmpty() && MadeKeyPrompt.spoken(bad).isEmpty(), "nothing for a non-key");
        }
    }

    /** K6: what the rider wrote down goes back into setup's typed-key field (the same parser). */
    private static void readBack() {
        Random random = new Random(11);
        for (int i = 0; i < 300; i++) {
            String key = NewOwnerKey.generate(random), one = MadeKeyPrompt.oneRow(key);
            for (String written : new String[] {one, one.replace(' ', ':'), one.replace(' ', '-'), one.toLowerCase(java.util.Locale.ROOT)}) {
                OwnerIdentityParser.Result parsed = OwnerIdentityParser.parse(written);
                check(parsed.identity.equals(key) && parsed.role == 0, "reads back as an owner key");
            }
        }
    }

    /** Failure mode 7 (the part the JVM can see): no message thrown here or by the parser holds a key's digits. */
    private static void neverInMessages() {
        String[] nearMisses = {"88 6B 33 AF 01", "88 6B 33 AF 01 C2 D4", "88 6B 33-AF 01 C2", "88  6B 33 AF 01 C2",
                "key 88 6B 33 AF 01 C2", "88 6B 33 AF 01 C2 x", "88_6B_33_AF_01_C2", "88 6B 33 AF 01 CG"};
        for (String input : nearMisses) {
            try { OwnerIdentityParser.parse(input); check(false, "accepted a near miss"); }
            catch (IllegalArgumentException refused) {
                String message = String.valueOf(refused.getMessage());
                check(!message.contains("6B") && !message.contains("6b") && !message.contains("AF 01"), "no key digits in the refusal");
            }
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        assertions++;
    }
}
