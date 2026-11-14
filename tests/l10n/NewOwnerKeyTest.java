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
        record();
        offers();
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

    /** R1, R3, R5's decision: what the controller does with the record before an owner check to an address. */
    private static void offers() {
        // Failure mode 4: a key without a record goes anywhere, exactly as before.
        check(NewOwnerKey.offer(null, A) == NewOwnerKey.Offer.SEND && NewOwnerKey.offer(null, B) == NewOwnerKey.Offer.SEND, "typed/imported key: sent as before");
        check(NewOwnerKey.offer(NewOwnerKey.MADE, A) == NewOwnerKey.Offer.TIE, "made key's first offer: tie to A first");
        check(NewOwnerKey.offer(NewOwnerKey.OFFERED, C) == NewOwnerKey.Offer.TIE, "refused key's next offer: tie to the new scooter first");
        check(NewOwnerKey.offer(A, A) == NewOwnerKey.Offer.SEND, "tied to A, at A: sent (rounds 2-3, reconnects, Try again)");
        check(NewOwnerKey.offer(A, A.toLowerCase(java.util.Locale.ROOT)) == NewOwnerKey.Offer.SEND, "address case does not matter");
        check(NewOwnerKey.offer(A, B) == NewOwnerKey.Offer.ELSEWHERE, "tied to A, at B: nothing sent (R3)");
        check(NewOwnerKey.offer(NewOwnerKey.MADE, "") == NewOwnerKey.Offer.ELSEWHERE && NewOwnerKey.offer(NewOwnerKey.MADE, null) == NewOwnerKey.Offer.ELSEWHERE,
                "a made key with no address to tie to is never sent");
        check(NewOwnerKey.offer(A, "") == NewOwnerKey.Offer.ELSEWHERE, "tied, no address: nothing sent");
        // R4: only the tied scooter's refusal unties; the key stays offered.
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.afterRefusal(A, A)), "A refuses: untied, still offered");
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.afterRefusal(A, A.toLowerCase(java.util.Locale.ROOT))), "A refuses (any case): untied");
        check(A.equals(NewOwnerKey.afterRefusal(A, B)), "a refusal from B never clears A's tie (failure mode 2)");
        check(NewOwnerKey.MADE.equals(NewOwnerKey.afterRefusal(NewOwnerKey.MADE, A)), "never offered: a refusal changes nothing");
        check(NewOwnerKey.OFFERED.equals(NewOwnerKey.afterRefusal(NewOwnerKey.OFFERED, A)), "already untied: unchanged");
        check(NewOwnerKey.afterRefusal(null, A) == null, "typed key's refusal: no record made");
        // R2: where a retry goes with no scan.
        check(A.equals(NewOwnerKey.connectAddress("", A)), "tied, no saved address: straight to A, no scan");
        check(A.equals(NewOwnerKey.connectAddress(A, A)), "completed: its saved scooter");
        check(NewOwnerKey.connectAddress("", NewOwnerKey.MADE).isEmpty() && NewOwnerKey.connectAddress("", NewOwnerKey.OFFERED).isEmpty(), "untied: scan and choose");
        check(NewOwnerKey.connectAddress("", null).isEmpty() && B.equals(NewOwnerKey.connectAddress(B, null)) && NewOwnerKey.connectAddress(null, null).isEmpty(),
                "typed/imported key: saved address or scan, as before");
    }

    /**
     * The council's lockout chains for #485 (F07, F17, F04, F02), as the record and the rules see them. The record is
     * made by the transport whatever started the connection, so none of these depend on setup's own pages; the
     * emulator harness (android-tests/NewOwnerKeyValidation.java) drives the screens and the real BleController.
     */
    private static void lockoutChains() {
        // Nearby devices granted while the service still binds, then setup's auto-connect; or the service's own
        // reconnect: the owner check to A ties the key to A, and setup keeps it.
        Link link = new Link(NewOwnerKey.MADE);
        check(link.offer(A) && A.equals(link.record) && !link.removable(), "first owner check: tied to A and kept");
        // Failure mode 1: one scooter. The link drops before remember() saves the address; Try again goes to A by
        // address (connectLast, no scan) and completes there, never refused.
        check(A.equals(NewOwnerKey.connectAddress("", link.record)) && link.offer(A) && !link.removable(), "drop, Try again: A again, sent, kept");
        // F04: two unowned scooters. A took the key and dropped; a scan picks B: B is refused before the owner check.
        check(!link.offer(B) && A.equals(link.record) && !link.removable(), "a second scooter is never offered the key");
        // Failure mode 2: a refusal reported for B (it was never offered the key) leaves A's tie.
        link.refused(B);
        check(A.equals(link.record) && !link.offer(B) && !link.removable(), "B's refusal: A's tie stands, nothing deleted");
        // Failure mode 3: A refuses (it has an owner, so it doesn't hold the key): untied, still kept, and the key may go
        // to C, which ties it; setup never deletes it on the way.
        link.refused(A);
        check(NewOwnerKey.OFFERED.equals(link.record) && !link.removable(), "A refuses: untied, kept");
        check(NewOwnerKey.connectAddress("", link.record).isEmpty(), "after the refusal Try again scans (A may be passed over)");
        check(link.offer(C) && C.equals(link.record) && !link.removable() && !link.offer(A), "C ties it; A is refused now");
        // Failure mode 7 (R5): the tie can't be written. Nothing is sent and the record is unchanged (never offered),
        // so setup may still delete a key no scooter heard.
        Link full = new Link(NewOwnerKey.MADE); full.writes = false;
        check(!full.offer(A) && NewOwnerKey.MADE.equals(full.record) && full.sent == 0 && full.removable(), "tie not saved: nothing sent");
        // The chooser is cancelled before any scooter is picked: no owner check, so nothing is recorded.
        check(new Link(NewOwnerKey.MADE).removable(), "chooser cancelled, never offered: may go");
        // A scooter saved under another profile is refused before the record (BleController.mayOffer): never offered.
        check(new Link(NewOwnerKey.MADE).removable(), "saved scooter refused before the owner check: may go");
        // Failure mode 4: a typed key has no record; nothing here touches it.
        Link typed = new Link(null);
        check(typed.offer(A) && typed.offer(B) && typed.record == null, "typed key: sent anywhere, no record");
    }

    /** A made key's record through owner checks and refusals, as BleController.mayOffer and ownerRefused drive it. */
    private static final class Link {
        String record; boolean writes = true; int sent;
        Link(String record) { this.record = record; }
        boolean offer(String address) {
            NewOwnerKey.Offer o = NewOwnerKey.offer(record, address);
            if (o == NewOwnerKey.Offer.TIE) { if (!writes) return false; record = address; o = NewOwnerKey.Offer.SEND; }
            if (o != NewOwnerKey.Offer.SEND) return false;
            sent++; return true;
        }
        void refused(String address) { record = NewOwnerKey.afterRefusal(record, address); }
        boolean removable() { return NewOwnerKey.removable(NewOwnerKey.offered(record), false); }
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
        int saved = may.indexOf("profiles.hardwareMatch(profile.id,address,null)"), hook = may.indexOf("OnboardingScreen.keyOffered(context,profile.id,address)");
        check(saved > 0 && saved < hook, "saved-hardware refusal before the record (failure mode 5)");
        check(may.contains("case ELSEWHERE:refused=M.bleFailedNewKeyElsewhere();") && may.contains("case UNSAVED:refused=M.bleFailedNewKeyUnsaved();")
                && may.indexOf("return true;") == may.indexOf("default:return true;") + 8, "only SEND lets the owner check go (R3, R5)");
        check(may.contains("wanted=false;") && may.contains("failed(refused,false,"), "a refusal before the owner check: no reconnect");
        String refused = body(ble, "private void ownerRefused(String address,int code){", "\n");
        check(refused.indexOf("OnboardingScreen.keyRefused(context,profile.id,address);") >= 0
                && refused.indexOf("OnboardingScreen.keyRefused(") < refused.indexOf("failed(OWNER_REJECTED,false,"), "a refusal unties before the link fails (R4)");
        check(ble.contains("if(f.error()!=0){ownerRefused(f.error());return;}") && ble.contains("private void ownerRefused(int code){ownerRefused(gatt==null?null:gatt.getDevice().getAddress(),code);}")
                && count(ble, "failed(OWNER_REJECTED,") == 1, "the owner check's refusal is the only path to OWNER_REJECTED, through ownerRefused");
        String last = body(ble, "public void connectLast(){", "\n");
        check(last.contains("String address=connectAddress(p);if(address.isEmpty())scan();else connect(p,address);"), "connectLast goes to a tied scooter by address (R2)");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(main.contains("scooterService.ble.connectLast();if(scooterService.ble.connectAddress(profile).isEmpty())showScanner(false);"),
                "Connect opens the chooser only when connectLast scans (R2)");

        String screen = read("src/com/jjricks/volt/OnboardingScreen.java");
        String offered = body(screen, "static NewOwnerKey.Offer keyOffered(Context context, String profileId, String address) {", "\n    }\n");
        check(offered.contains("if (offer != NewOwnerKey.Offer.TIE) return offer;")
                && offered.contains(".putString(NewOwnerKey.key(profileId), address).commit() ? NewOwnerKey.Offer.SEND : NewOwnerKey.Offer.UNSAVED;")
                && offered.contains("catch (RuntimeException failed) { return NewOwnerKey.Offer.UNSAVED; }"), "the tie and offer: one commit, and a failure sends nothing (R5)");
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
        check(registry.contains("RULES.add(new Rule(app, NewOwnerKey.PREFIX, true, String.class, null, DEVICE, true, false, false));"),
                "the record stays on this phone (BackupRegistry; no new profile field, failure mode 6)");
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
