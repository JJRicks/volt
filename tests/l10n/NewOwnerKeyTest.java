package com.jjricks.volt;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Synthetic keys only; no Android needed (tests/l10n/new-owner-key.sources; BackupTestStore from tests/run.sh). */
public final class NewOwnerKeyTest {
    private static int assertions;

    public static void main(String[] args) {
        shape();
        randomness();
        acceptedEverywhereAKeyIs();
        removal();
        record();
        offers();
        writes();
        lockoutChains();
        try { backups(); } catch (java.io.IOException e) { throw new AssertionError(e); }
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

    private static final String A = "C0:FF:EE:00:00:0A", B = "C0:FF:EE:00:00:0B", C = "C0:FF:EE:00:00:0C";

    /** The made key's record (app_settings made_owner_key + profile ID) and what each value means. */
    private static void record() {
        check(NewOwnerKey.PREFIX.matches("[a-z_]*[a-z]") && NewOwnerKey.key("p-1").equals(NewOwnerKey.PREFIX + "p-1"), "record key: plain id + profile ID");
        check(NewOwnerKey.MADE.matches("[a-z]+") && NewOwnerKey.OFFERED.matches("[a-z]+") && !NewOwnerKey.MADE.equals(NewOwnerKey.OFFERED), "two plain states");
        check(!NewOwnerKey.offered(null), "no record (typed, imported, captured key): not a made key's offer");
        check(!NewOwnerKey.offered(NewOwnerKey.MADE), "made, never offered");
        check(NewOwnerKey.offered(NewOwnerKey.OFFERED), "offered, tied to none (refused): still offered");
        check(NewOwnerKey.offered(A), "tied to A: offered");
        check(NewOwnerKey.tie(null).isEmpty() && NewOwnerKey.tie(NewOwnerKey.MADE).isEmpty() && NewOwnerKey.tie(NewOwnerKey.OFFERED).isEmpty(), "no tie without an address");
        check(A.equals(NewOwnerKey.tie(A)), "the tie is the address");
        // An address always has ':' separators, so it never reads as either state.
        check(!A.equals(NewOwnerKey.MADE) && !A.equals(NewOwnerKey.OFFERED) && NewOwnerKey.tie(A).contains(":"), "addresses and states never collide");
    }

    /** The decision before an owner check to an address, from the record and the profile's saved scooter address. */
    private static void offers() {
        // A key without a record goes anywhere, exactly as before.
        check(NewOwnerKey.offer(null, "", A) == NewOwnerKey.Offer.SEND && NewOwnerKey.offer(null, "", B) == NewOwnerKey.Offer.SEND, "typed/imported key: sent as before");
        check(NewOwnerKey.offer(NewOwnerKey.MADE, "", A) == NewOwnerKey.Offer.TIE, "made key's first offer: tie to A first");
        check(NewOwnerKey.offer(NewOwnerKey.MADE, null, A) == NewOwnerKey.Offer.TIE, "no saved address (null): the tie governs");
        check(NewOwnerKey.offer(NewOwnerKey.OFFERED, "", C) == NewOwnerKey.Offer.TIE, "refused key's next offer: tie to the new scooter first");
        check(NewOwnerKey.offer(A, "", A) == NewOwnerKey.Offer.SEND, "tied to A, at A: sent (rounds 2-3, reconnects, Try again's pick of A)");
        check(NewOwnerKey.offer(A, "", A.toLowerCase(java.util.Locale.ROOT)) == NewOwnerKey.Offer.SEND, "address case does not matter");
        check(NewOwnerKey.offer(A, "", B) == NewOwnerKey.Offer.ELSEWHERE, "tied to A, at B (picked in the chooser): nothing sent");
        check(NewOwnerKey.offer(NewOwnerKey.MADE, "", "") == NewOwnerKey.Offer.ELSEWHERE && NewOwnerKey.offer(NewOwnerKey.MADE, "", null) == NewOwnerKey.Offer.ELSEWHERE,
                "a made key with no address to tie to is never sent");
        check(NewOwnerKey.offer(A, "", "") == NewOwnerKey.Offer.ELSEWHERE, "tied, no address: nothing sent");
        // R4: once a scooter address is saved (remember(): the key accepted, the serial checked), the tie stops: the saved
        // address and serial govern, as for any key. The record itself stays.
        String moved = "C0:FF:EE:00:00:AA";
        check(NewOwnerKey.offer(A, A, moved) == NewOwnerKey.Offer.SEND, "saved at A, now at a new address (repaired board): the owner check goes");
        check(NewOwnerKey.offer(A, A, A) == NewOwnerKey.Offer.SEND, "saved at A, at A: sent");
        check(NewOwnerKey.offer(NewOwnerKey.OFFERED, A, B) == NewOwnerKey.Offer.SEND && NewOwnerKey.offer(NewOwnerKey.MADE, A, B) == NewOwnerKey.Offer.SEND,
                "a saved address governs whatever the record says");
        check(NewOwnerKey.offered(A) && NewOwnerKey.offered(NewOwnerKey.OFFERED), "the record still marks an offered made key after the save");
        // Only the tied scooter's refusal unties; the key stays offered.
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.afterRefusal(A, A)), "A refuses: untied, still offered");
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.afterRefusal(A, A.toLowerCase(java.util.Locale.ROOT))), "A refuses (any case): untied");
        check(A.equals(NewOwnerKey.afterRefusal(A, B)), "a refusal from B never clears A's tie");
        check(NewOwnerKey.MADE.equals(NewOwnerKey.afterRefusal(NewOwnerKey.MADE, A)), "never offered: a refusal changes nothing");
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.afterRefusal(NewOwnerKey.OFFERED, A)), "already untied: unchanged");
        check(NewOwnerKey.afterRefusal(null, A) == null, "typed key's refusal: no record made");
        // A restore's merge (BackupArchive): never loosens this phone's record.
        check(A.equals(NewOwnerKey.merged(A, NewOwnerKey.MADE)) && A.equals(NewOwnerKey.merged(A, null)) && A.equals(NewOwnerKey.merged(A, B)), "this phone's tie stays");
        check(A.equals(NewOwnerKey.merged(NewOwnerKey.MADE, A)) && A.equals(NewOwnerKey.merged(null, A)), "an offered backup record wins over made or none");
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.merged(NewOwnerKey.OFFERED, A)), "two offered records: this phone's");
        check(NewOwnerKey.MADE.equals(NewOwnerKey.merged(NewOwnerKey.MADE, null)) && NewOwnerKey.MADE.equals(NewOwnerKey.merged(null, NewOwnerKey.MADE)), "made, from either side");
        check(NewOwnerKey.merged(null, null) == null, "an older backup with no record adds none");
    }

    /**
     * R1: Android's SharedPreferences changes its in-memory map before the disk write, so a failed commit() still shows
     * the new value. Every failed write of a made key's record puts the old value back in memory.
     */
    private static void writes() {
        // The tie can't be written: nothing is sent, memory and disk still say made, and a retry tries to tie again.
        Prefs full = new Prefs(); full.disk.put(NewOwnerKey.key(P), NewOwnerKey.MADE); full.memory.putAll(full.disk); full.fail = true;
        check(NewOwnerKey.offerTo(full, P, "", A) == NewOwnerKey.Offer.UNSAVED, "a failed tie: UNSAVED");
        check(NewOwnerKey.MADE.equals(full.memory.get(NewOwnerKey.key(P))) && NewOwnerKey.MADE.equals(full.disk.get(NewOwnerKey.key(P))), "a failed tie: made in memory and on disk");
        check(NewOwnerKey.offerTo(full, P, "", A) == NewOwnerKey.Offer.UNSAVED, "Try again after a failed tie: ties again, and again sends nothing");
        check(NewOwnerKey.offerTo(full, P, "", B) == NewOwnerKey.Offer.UNSAVED, "another scooter after a failed tie: ties, never ELSEWHERE or SEND");
        check(full.written.equals(java.util.Arrays.asList(A, NewOwnerKey.MADE, A, NewOwnerKey.MADE, B, NewOwnerKey.MADE)), "only the new value and the old one are ever written: " + full.written);
        full.fail = false;
        check(NewOwnerKey.offerTo(full, P, "", A) == NewOwnerKey.Offer.SEND && A.equals(full.disk.get(NewOwnerKey.key(P))) && A.equals(full.memory.get(NewOwnerKey.key(P))),
                "storage freed: the tie is written, then sent");
        // A write that throws after changing memory (the old fake returned before changing anything, which hid this).
        Prefs throwing = new Prefs(); throwing.disk.put(NewOwnerKey.key(P), NewOwnerKey.MADE); throwing.memory.putAll(throwing.disk); throwing.fail = true; throwing.throwing = true;
        check(NewOwnerKey.offerTo(throwing, P, "", A) == NewOwnerKey.Offer.UNSAVED && NewOwnerKey.MADE.equals(throwing.memory.get(NewOwnerKey.key(P))),
                "a write that throws: UNSAVED, made back in memory");
        check(NewOwnerKey.offerTo(throwing, P, "", A) == NewOwnerKey.Offer.UNSAVED, "and the retry still sends nothing");
        // The tied scooter's refusal can't be written: the tie stays in memory too, so another scooter is still refused.
        Prefs refusal = new Prefs(); refusal.disk.put(NewOwnerKey.key(P), A); refusal.memory.putAll(refusal.disk); refusal.fail = true;
        check(!NewOwnerKey.refusedBy(refusal, P, A) && A.equals(refusal.memory.get(NewOwnerKey.key(P))) && A.equals(refusal.disk.get(NewOwnerKey.key(P))), "a failed untie: the tie back in memory");
        check(NewOwnerKey.offerTo(refusal, P, "", B) == NewOwnerKey.Offer.ELSEWHERE, "after a failed untie, B is still refused");
        refusal.fail = false;
        check(NewOwnerKey.refusedBy(refusal, P, A) && NewOwnerKey.OFFERED.equals(refusal.disk.get(NewOwnerKey.key(P))), "the untie written once storage allows");
        Prefs other = new Prefs(); other.disk.put(NewOwnerKey.key(P), A); other.memory.putAll(other.disk); other.fail = true;
        check(NewOwnerKey.refusedBy(other, P, B) && other.written.isEmpty(), "another scooter's refusal writes nothing");
        // A new key's record can't be written: no record in memory either, so the key is never made.
        Prefs fresh = new Prefs(); fresh.fail = true;
        check(!NewOwnerKey.recordNew(fresh, P) && !fresh.memory.containsKey(NewOwnerKey.key(P)) && !fresh.disk.containsKey(NewOwnerKey.key(P)), "a failed record: removed from memory");
        check(fresh.written.equals(java.util.Arrays.asList(NewOwnerKey.MADE, null)), "the restore removes, never writes a value that wasn't there: " + fresh.written);
        fresh.fail = false;
        check(NewOwnerKey.recordNew(fresh, P) && NewOwnerKey.MADE.equals(fresh.disk.get(NewOwnerKey.key(P))), "a record written");
        // A typed key has no record: nothing is read into a write, nothing written.
        Prefs typed = new Prefs(); typed.fail = true;
        check(NewOwnerKey.offerTo(typed, P, "", A) == NewOwnerKey.Offer.SEND && NewOwnerKey.offerTo(typed, P, "", B) == NewOwnerKey.Offer.SEND && typed.written.isEmpty(), "typed key: no writes");
        // A saved address: the owner check goes anywhere, and nothing is written.
        Prefs saved = new Prefs(); saved.disk.put(NewOwnerKey.key(P), A); saved.memory.putAll(saved.disk); saved.fail = true;
        check(NewOwnerKey.offerTo(saved, P, A, B) == NewOwnerKey.Offer.SEND && saved.written.isEmpty() && A.equals(saved.memory.get(NewOwnerKey.key(P))), "saved address: sent, record kept, no write");
    }

    private static final String P = "f0000000-0000-4000-8000-00000000000f";

    /** SharedPreferences as Android behaves: memory changes on every write, and the disk only when the write succeeds. */
    private static final class Prefs implements NewOwnerKey.Records {
        final Map<String, String> memory = new HashMap<>(), disk = new HashMap<>();
        final List<String> written = new ArrayList<>();
        boolean fail, throwing;
        @Override public String get(String key) { return memory.get(key); }
        @Override public boolean write(String key, String value) {
            written.add(value);
            if (value == null) memory.remove(key); else memory.put(key, value);
            if (fail && throwing) throw new IllegalStateException("disk");
            if (fail) return false;
            if (value == null) disk.remove(key); else disk.put(key, value);
            return true;
        }
    }

    /**
     * The council's lockout chains for #485, as the record and the rules see them, through Android-shaped preferences.
     * The record is made by the transport whatever started the connection, so none of these depend on setup's own
     * pages; the emulator harness (android-tests/NewOwnerKeyValidation.java) drives the screens and the real BleController.
     */
    private static void lockoutChains() {
        // Nearby devices granted while the service still binds, then setup's auto-connect; or the service's own
        // reconnect: the owner check to A ties the key to A, and setup keeps it.
        Link link = new Link(NewOwnerKey.MADE);
        check(link.offer(A) && A.equals(link.record()) && !link.removable(), "first owner check: tied to A and kept");
        // One scooter. The link drops before remember() saves the address; Try again scans (no saved address), the chooser
        // lists A, picking it sends the key there again (connectDiscovered captures its model ID first).
        check(link.offer(A) && !link.removable(), "drop, Try again, pick A: sent, kept");
        // Two unowned scooters. A took the key and dropped; the chooser picks B: B is refused before the owner check.
        check(!link.offer(B) && A.equals(link.record()) && !link.removable(), "a second scooter picked in the chooser is never offered the key");
        // A refusal reported for B (it was never offered the key) leaves A's tie.
        link.refused(B);
        check(A.equals(link.record()) && !link.offer(B) && !link.removable(), "B's refusal: A's tie stands, nothing deleted");
        // A refuses (it has an owner, so it doesn't hold the key): untied, still kept, and the key may go to C, which ties
        // it; setup never deletes it on the way.
        link.refused(A);
        check(NewOwnerKey.OFFERED.equals(link.record()) && !link.removable(), "A refuses: untied, kept");
        check(link.offer(C) && C.equals(link.record()) && !link.removable() && !link.offer(A), "C ties it; A is refused now");
        // R4: C accepted the key and remember() saved it; later C's Bluetooth board is replaced and it shows up at a new
        // address. The owner check goes (remember() follows it on a serial match), and the record stays.
        link.saved = C;
        check(link.offer("C0:FF:EE:00:00:CD") && C.equals(link.record()) && !link.removable(), "a saved made key reaches its scooter at a new address");
        // R1: the tie can't be written. Nothing is sent and memory still says never offered, so a retry ties again and
        // again sends nothing, and setup may still delete a key no scooter heard.
        Link full = new Link(NewOwnerKey.MADE); full.prefs.fail = true;
        check(!full.offer(A) && NewOwnerKey.MADE.equals(full.record()) && full.sent == 0 && full.removable(), "tie not saved: nothing sent");
        check(!full.offer(A) && full.sent == 0 && full.removable(), "Try again with the disk still full: nothing sent");
        full.prefs.fail = false;
        check(full.offer(A) && full.sent == 1 && !full.removable(), "Try again once storage is freed: tied, sent, kept");
        // The chooser is cancelled before any scooter is picked: no owner check, so nothing is recorded.
        check(new Link(NewOwnerKey.MADE).removable(), "chooser cancelled, never offered: may go");
        // A scooter saved under another profile is refused before the record (BleController.mayOffer): never offered.
        check(new Link(NewOwnerKey.MADE).removable(), "saved scooter refused before the owner check: may go");
        // A typed key has no record; nothing here touches it.
        Link typed = new Link(null);
        check(typed.offer(A) && typed.offer(B) && typed.record() == null, "typed key: sent anywhere, no record");
    }

    /** A made key's record through owner checks and refusals, as BleController.mayOffer and ownerRefused drive it. */
    private static final class Link {
        final Prefs prefs = new Prefs(); String saved = ""; int sent;
        Link(String record) { if (record != null) { prefs.memory.put(NewOwnerKey.key(P), record); prefs.disk.put(NewOwnerKey.key(P), record); } }
        String record() { return prefs.memory.get(NewOwnerKey.key(P)); }
        boolean offer(String address) {
            if (NewOwnerKey.offerTo(prefs, P, saved, address) != NewOwnerKey.Offer.SEND) return false;
            check(java.util.Objects.equals(prefs.memory.get(NewOwnerKey.key(P)), prefs.disk.get(NewOwnerKey.key(P))), "sent only on a record the disk holds");
            sent++; return true;
        }
        void refused(String address) { NewOwnerKey.refusedBy(prefs, P, address); }
        boolean removable() { return NewOwnerKey.removable(NewOwnerKey.offered(record()), !saved.isEmpty()); }
    }

    /**
     * R2: the record travels with its profile in backups (Scooters & setup keys) and restores with it, through the real
     * BackupArchive and BackupTransaction (BackupTestStore). Setup's own pointers never travel, so setup never deletes a
     * restored made key.
     */
    private static void backups() throws java.io.IOException {
        File root = java.nio.file.Files.createTempDirectory("new-owner-key-backup").toFile();
        try {
            Set<String> scooters = Collections.singleton(BackupRegistry.SCOOTERS);
            String key = NewOwnerKey.key(P), made = madeItem(P, "88AA00000001", "", "");
            check(BackupRegistry.SCOOTERS.equals(BackupRegistry.category("app_settings", key)) && BackupRegistry.rule("app_settings", key).perProfile
                    && P.equals(BackupRegistry.rule("app_settings", key).profile(key)), "the record: Scooters & setup keys, per profile");
            for (String wizard : new String[]{"generated_profile_id", "created_profile_id", "profile_id"})
                check(BackupRegistry.rule("onboarding", wizard).excluded(), "setup's pointer stays out of backups: " + wizard);

            // A phone with the made key tied to A (no saved address), and setup pointing at it.
            BackupTestStore source = store(root, "source", made, P, A);
            source.put("onboarding", "generated_profile_id", P); source.put("onboarding", "created_profile_id", P); source.put("onboarding", "profile_id", P);
            byte[] tied = source.export(scooters);
            BackupArchive.Prepared staged = new BackupTestStore(new File(root, "peek")).stage(tied);
            check(A.equals(staged.preferences.get("app_settings").get(key)), "the backup holds the record");
            check(staged.preferences.get("onboarding").keySet().equals(Collections.singleton("onboarding_done")), "the backup holds none of setup's pointers: " + staged.preferences.get("onboarding"));

            // Restored on a fresh phone: the profile and its tie arrive together, so a second scooter is still refused.
            BackupTestStore fresh = new BackupTestStore(new File(root, "fresh"));
            fresh.restore(tied, scooters, false, null);
            check(A.equals(fresh.preferences().get("app_settings").get(key)) && BackupProfiles.find(BackupArchive.profiles(fresh.preferences()), P) != null,
                    "merge onto a fresh phone: the made key with its tie");
            check(NewOwnerKey.offer((String) fresh.preferences().get("app_settings").get(key), "", B) == NewOwnerKey.Offer.ELSEWHERE, "restored, a second scooter is refused");
            check(!fresh.preferences().get("onboarding").containsKey("generated_profile_id") && !fresh.preferences().get("onboarding").containsKey("created_profile_id"),
                    "restored: setup does not point at the made key, so never deletes it");

            // Merge onto a phone with the same profile and another record: this phone's record is never loosened.
            check(merged(root, "same-tied", made, A, NewOwnerKey.MADE).equals(A), "same profile, tied here, made in the backup: the tie stays");
            check(merged(root, "same-made", made, NewOwnerKey.MADE, A).equals(A), "same profile, made here, tied in the backup: tied");
            check(merged(root, "same-two", made, B, A).equals(B), "same profile, tied to B here and A in the backup: this phone's tie");
            check(merged(root, "same-offered", made, NewOwnerKey.OFFERED, NewOwnerKey.MADE).equals(NewOwnerKey.OFFERED), "same profile, offered here: stays offered");
            // An older backup with no record: as before, no record means not a made key; it adds none and loosens none.
            check(merged(root, "old-here", made, A, null).equals(A), "an older backup leaves this phone's tie");
            check(merged(root, "old-none", made, null, null).isEmpty(), "an older backup adds no record");
            BackupTestStore old = new BackupTestStore(new File(root, "old-fresh"));
            old.restore(store(root, "old-source", made, P, null).export(scooters), scooters, false, null);
            check(!old.preferences().get("app_settings").containsKey(key) && BackupProfiles.find(BackupArchive.profiles(old.preferences()), P) != null,
                    "an older backup on a fresh phone: the key, no record");
            // The same profile ID holding another key here: the record describes the backup's key, so it isn't applied.
            BackupTestStore otherKey = store(root, "other-key", madeItem(P, "88BB00000002", "", ""), P, null);
            otherKey.restore(tied, scooters, false, null);
            check(!otherKey.preferences().get("app_settings").containsKey(key), "another key under the same ID: no record copied");

            // Renamed: the backup's scooter (saved, serial S) lands on this phone's profile Q for the same scooter.
            String q = "e0000000-0000-4000-8000-00000000000e", qKey = NewOwnerKey.key(q);
            BackupTestStore sameKey = store(root, "rename-same", madeItem(q, "88AA00000001", A, "SERIAL-S"), q, null);
            BackupTestStore renameSource = store(root, "rename-source", madeItem(P, "88AA00000001", A, "SERIAL-S"), P, A);
            sameKey.restore(renameSource.export(scooters), scooters, false, null);
            check(A.equals(sameKey.preferences().get("app_settings").get(qKey)), "renamed onto Q with the same key: the record follows, under Q's ID");
            check(BackupProfiles.find(BackupArchive.profiles(sameKey.preferences()), P) == null, "renamed: no second profile");
            BackupTestStore otherScooterKey = store(root, "rename-other", madeItem(q, "88CC00000003", A, "SERIAL-S"), q, null);
            otherScooterKey.restore(renameSource.export(scooters), scooters, false, null);
            check(!otherScooterKey.preferences().get("app_settings").containsKey(qKey) && !otherScooterKey.preferences().get("app_settings").containsKey(key),
                    "renamed onto Q with another key: no record");

            // Replace everything: the backup's records, but never loosening this phone's record for the same key.
            Set<String> all = BackupTestStore.all();
            BackupTestStore replaced = new BackupTestStore(new File(root, "replace-fresh"));
            replaced.restore(complete(root, "replace-src", made, A), all, true, null);
            check(A.equals(replaced.preferences().get("app_settings").get(key)), "Replace everything onto an empty phone: the tie arrives");
            BackupTestStore keeps = store(root, "replace-keeps", made, P, A);
            keeps.restore(complete(root, "replace-made", made, NewOwnerKey.MADE), all, true, null);
            check(A.equals(keeps.preferences().get("app_settings").get(key)), "Replace everything with an older made record: this phone's tie stays");
            BackupTestStore older = store(root, "replace-older", made, P, A);
            older.restore(complete(root, "replace-none", made, null), all, true, null);
            check(A.equals(older.preferences().get("app_settings").get(key)), "Replace everything with an older backup (no record): this phone's tie stays");
        } finally { AppDataArchive.deleteTree(root); }
    }

    /** A made key's profile item: an owner (role 0) with this key, address and serial ("" before any scooter took it). */
    private static String madeItem(String id, String identity, String mac, String serial) {
        return "{\"id\":\"" + id + "\",\"name\":\"New key\",\"identity\":\"" + identity + "\",\"role\":0,\"mac\":\"" + mac + "\",\"serial\":\"" + serial + "\",\"lastConnected\":0}";
    }
    /** A store whose one profile is {@code item}, with this record for profile {@code id} (none when null). */
    private static BackupTestStore store(File root, String name, String item, String id, String record) throws java.io.IOException {
        BackupTestStore s = new BackupTestStore(new File(root, name));
        s.put("profiles", "items", "[" + item + "]"); s.put("profiles", "active", id); s.put("onboarding", "onboarding_done", true);
        if (record != null) s.put("app_settings", NewOwnerKey.key(id), record);
        return s;
    }
    /** A complete backup (every category) of one made-key profile with this record. */
    private static byte[] complete(File root, String name, String item, String record) throws java.io.IOException {
        return store(root, name, item, P, record).export(BackupTestStore.all());
    }
    /** Profile P's record after a Scooters merge of a backup holding {@code incoming} onto a phone holding {@code here} ("" for none). */
    private static String merged(File root, String name, String item, String here, String incoming) throws java.io.IOException {
        BackupTestStore phone = store(root, name + "-phone", item, P, here);
        phone.restore(store(root, name + "-backup", item, P, incoming).export(Collections.singleton(BackupRegistry.SCOOTERS)), Collections.singleton(BackupRegistry.SCOOTERS), false, null);
        Object kept = phone.preferences().get("app_settings").get(NewOwnerKey.key(P));
        return kept == null ? "" : (String) kept;
    }

    /** Where the rules live in Android code: their order in the transport, setup and the backup table. */
    private static void transportOrder() {
        String ble = read("src/com/jjricks/volt/BleController.java");
        String auth = body(ble, "private void authenticate(){", "private boolean mayOffer(String address){");
        int sim = auth.indexOf("if(simulating())return;"), gate = auth.indexOf("if(gatt==null||!mayOffer(gatt.getDevice().getAddress()))return;");
        int frame = auth.indexOf("send(Protocol.auth(");
        check(sim >= 0 && sim < gate, "simulation returns before anything (never reaches the record)");
        check(gate > 0 && gate < frame, "mayOffer decides before the owner check is queued");
        check(auth.indexOf("send(Protocol.auth(", frame + 1) < 0 && ble.indexOf("Protocol.auth(") == ble.indexOf("send(Protocol.auth(") + 5,
                "authenticate() is BleController's only owner check");
        String may = body(ble, "private boolean mayOffer(String address){", "private void ownerRefused(");
        int saved = may.indexOf("profiles.hardwareMatch(profile.id,address,null)"), hook = may.indexOf("OnboardingScreen.keyOffered(context,profile.id,profile.mac,address)");
        check(saved > 0 && saved < hook, "saved-hardware refusal before the record, which gets the saved address (R4: the tie stops once one is saved)");
        check(may.contains("case ELSEWHERE:refused=M.bleFailedNewKeyElsewhere();") && may.contains("case UNSAVED:refused=M.bleFailedNewKeyUnsaved();")
                && may.indexOf("return true;") == may.indexOf("default:return true;") + 8, "only SEND lets the owner check go");
        check(may.contains("wanted=false;") && may.contains("failed(refused,false,"), "a refusal before the owner check: no reconnect");
        String refused = body(ble, "private void ownerRefused(String address,int code){", "\n");
        check(refused.indexOf("OnboardingScreen.keyRefused(context,profile.id,address);") >= 0
                && refused.indexOf("OnboardingScreen.keyRefused(") < refused.indexOf("failed(OWNER_REJECTED,false,"), "a refusal unties before the link fails");
        check(ble.contains("if(f.error()!=0){ownerRefused(f.error());return;}") && ble.contains("private void ownerRefused(int code){ownerRefused(gatt==null?null:gatt.getDevice().getAddress(),code);}")
                && count(ble, "failed(OWNER_REJECTED,") == 1, "the owner check's refusal is the only path to OWNER_REJECTED, through ownerRefused");
        // R3: no direct reconnect to the tie. With no saved address, connectLast scans and Connect shows the chooser, as on
        // main; the pick goes through connectDiscovered (the advertised model ID captured), then mayOffer.
        String last = body(ble, "public void connectLast(){", "\n");
        check(last.contains("if(p.mac.isEmpty())scan();else connect(p,p.mac);") && !ble.contains("connectAddress"), "connectLast: the saved address, else a scan (R3)");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(main.contains("scooterService.ble.connectLast();if(profile.mac.isEmpty())showScanner(false);") && !main.contains("connectAddress"),
                "Connect opens the chooser whenever no scooter address is saved (R3)");
        String discovered = body(ble, "public void connectDiscovered(ScanResult result){", "\n    /**");
        check(discovered.indexOf("pendingAdvertised=id;") > 0 && discovered.indexOf("pendingAdvertised=id;") < discovered.indexOf("connect(p,mac);"), "the chooser's pick keeps its advertised model ID");
        String failure = body(ble, "private void failed(String text,boolean retry,ConnectionEventLog.Reason reason,int code){", "\n    private void notice(");
        check(failure.contains("boolean reconnect=retry&&wanted&&workAllowed()&&profile!=null&&!profile.mac.isEmpty();") && failure.contains("connect(profile,profile.mac);"),
                "the controller's own reconnect needs a saved address: none with an empty one (no loop, nowhere by itself)");

        String screen = read("src/com/jjricks/volt/OnboardingScreen.java");
        String offered = body(screen, "static NewOwnerKey.Offer keyOffered(Context context, String profileId, String savedMac, String address) {", "\n    }\n");
        check(offered.contains("try { return NewOwnerKey.offerTo(madeKeyRecords(context), profileId, savedMac, address); }")
                && offered.contains("catch (RuntimeException failed) { return NewOwnerKey.Offer.UNSAVED; }"), "the tie and offer: one commit through NewOwnerKey.save, and a failure sends nothing");
        check(body(screen, "static void keyRefused(Context context, String profileId, String address) {", "\n    }\n").contains("NewOwnerKey.refusedBy(madeKeyRecords(context), profileId, address);"),
                "the untie goes through NewOwnerKey.save (R1)");
        check(body(screen, "private boolean recordMade(String id) {", "\n    }\n").contains("NewOwnerKey.recordNew(madeKeyRecords(host.activity()), id);"),
                "a new key's record goes through NewOwnerKey.save (R1)");
        String records = body(screen, "private static NewOwnerKey.Records madeKeyRecords(Context context) {", "\n    }\n");
        check(records.contains("return p.getString(key, null);") && records.contains("return (value == null ? edit.remove(key) : edit.putString(key, value)).commit();"),
                "the record's reads and writes are SharedPreferences' memory and commit()");
        check(count(screen, ".putString(NewOwnerKey.key(") == 0, "no other code writes a made key's record");
        check(screen.contains("return context.getSharedPreferences(\"app_settings\", Context.MODE_PRIVATE);"), "records live outside setup's preferences (R6)");
        String connect = body(screen, "private void toConnect() {", "private void afterProfile()");
        check(!connect.contains("keyOffered(") && !connect.contains("NewOwnerKey.key("), "setup's Connect page does not decide whether the key was offered");
        String make = body(screen, "private void makeNewKey() {", "private boolean recordMade(String id) {");
        check(make.indexOf("if (!recordMade(id))") > 0 && make.indexOf("if (!recordMade(id))") < make.indexOf("store.createMade(id,"),
                "a made key's record is on disk before its profile exists");
        String pending = body(screen, "private void applyPendingName() {", "\n    }\n");
        check(pending.contains("if (p == null || madeKey(p)) return;"), "a pending name never renames a made key (F03)");
        String refresh = body(screen, "@Override public void refresh() {", "\n    }\n");
        check(refresh.indexOf("if (step == IMPORT) leaveMadeKey();") > 0 && refresh.indexOf("leaveMadeKey();") < refresh.indexOf("applyPendingName();"),
                "Import leaves the made key before a pending name is applied (F03)");
        check(body(screen, "private void go(int next) {", "\n    }\n").contains("if (next == IMPORT) leaveMadeKey();"), "every way into Import leaves the made key (F03)");
        String leave = body(screen, "private void leaveMadeKey() {", "\n    }\n");
        check(leave.indexOf("if (!removableMade(made)) return;") > 0 && leave.indexOf("if (!removableMade(made)) return;") < leave.indexOf("service.ble.disconnect();")
                && leave.indexOf("service.ble.disconnect();") < leave.indexOf("if (mayRemoveCreated(made)) store.remove(made.id);"),
                "Import removes only a never-offered key, after its connection ends");
        // Failure mode 8: every removal in setup is mayRemoveCreated's, or the undo of a key whose save failed before Connect.
        int makeAt = screen.indexOf("private void makeNewKey() {"), undoAt = screen.indexOf("if (!saved) {", makeAt), makeEnd = screen.indexOf("private boolean recordMade(", makeAt);
        int removals = 0, guarded = 0, undo = 0;
        for (int at = screen.indexOf("store.remove("); at >= 0; at = screen.indexOf("store.remove(", at + 1)) {
            removals++;
            String line = screen.substring(screen.lastIndexOf('\n', at) + 1, screen.indexOf('\n', at));
            if (line.contains("mayRemoveCreated(")) guarded++;
            else if (at > undoAt && undoAt > makeAt && at < makeEnd) undo++;
            else check(false, "a setup removal not guarded by mayRemoveCreated: " + line.trim());
        }
        check(removals == guarded + undo && undo == 1 && guarded >= 3, "setup removals: " + guarded + " through mayRemoveCreated, " + undo + " failed-save undo");
        String may2 = body(screen, "private boolean mayRemoveCreated(ScooterProfiles.Profile created) {", "\n    }\n");
        check(may2.indexOf("if (!created.mac.isEmpty()) return false;") >= 0 && may2.indexOf("if (!removableMade(created)) return false;") > 0, "mayRemoveCreated: accepted or offered made keys stay");
        check(body(screen, "private boolean removableMade(ScooterProfiles.Profile made) {", "\n    }\n").contains("record == null || NewOwnerKey.offered(record)"),
                "a made key without a readable record counts as offered");
        String registry = read("src/com/jjricks/volt/BackupRegistry.java");
        check(registry.contains("prefix(app, NewOwnerKey.PREFIX, String.class, SCOOTERS, true);"), "the record travels with its profile (BackupRegistry, R2)");
        String archive = read("src/com/jjricks/volt/BackupArchive.java");
        check(count(archive, "madeKeyRecord(result, ") == 2, "a merge and Replace everything both merge the record (BackupArchive)");
    }

    private static String body(String text, String start, String end) {
        int from = text.indexOf(start);
        check(from >= 0, "found: " + start);
        int to = text.indexOf(end, from + start.length());
        check(to > from, "end found after: " + start);
        return text.substring(from, to);
    }

    private static int count(String text, String part) { int n = 0; for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++; return n; }

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
