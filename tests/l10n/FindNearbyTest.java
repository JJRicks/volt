package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Find it nearby (FindNearby): the pure rules, then how BleController, MainActivity and MoreScreen apply them (read
 * from the source, as NewOwnerKeyTest reads the owner check). Synthetic addresses and serials only; no Android needed
 * (tests/l10n/find-nearby.sources). Each numbered failure mode in the spec has its own section.
 */
public final class FindNearbyTest {
    private static int assertions;
    private static final String SAVED = "C0:FF:EE:00:00:01", OTHER = "C0:FF:EE:00:00:09", SERIAL = "Q2538ZZ9US7777777";

    public static void main(String[] args) {
        eligibility();
        timeouts();
        homeStay();
        picks();
        serials();
        sheet();
        ordinaryScanner();
        verdict();
        signal();
        wiring();
        fixRound();
        secondRound();
        councilRound();
        System.out.println("FindNearbyTest: " + assertions + " assertions passed");
    }

    /** Failure mode 7: a profile with an empty saved serial never gets find mode (nor one with no saved address). */
    private static void eligibility() {
        check(FindNearby.eligible(SAVED, SERIAL), "saved address and serial: eligible");
        for (String serial : new String[]{"", null}) {
            check(!FindNearby.eligible(SAVED, serial), "no saved serial: not eligible");
            check(!FindNearby.offerRow(SAVED, serial), "no saved serial: no My scooters row");
            check(!FindNearby.offerHome(SAVED, serial, true, true, false, false, false, false), "no saved serial: no Home button");
            for (boolean confirmed : new boolean[]{false, true}) {
                check(FindNearby.pick(SAVED, serial, OTHER, false, confirmed) == FindNearby.Step.REFUSE, "no saved serial: a pick sends nothing");
                check(FindNearby.pick(SAVED, serial, SAVED, false, confirmed) == FindNearby.Step.REFUSE, "no saved serial: even the saved address isn't a find");
            }
        }
        for (String mac : new String[]{"", null}) {
            check(!FindNearby.eligible(mac, SERIAL), "no saved address: not eligible");
            check(!FindNearby.offerRow(mac, SERIAL), "no saved address: no row");
            check(FindNearby.pick(mac, SERIAL, OTHER, false, true) == FindNearby.Step.REFUSE, "no saved address: refused");
        }
        check(FindNearby.offerRow(SAVED, SERIAL), "the row needs no timeouts");
        // Home: every condition, then each one missing.
        check(FindNearby.offerHome(SAVED, SERIAL, true, true, false, false, false, false), "Home offers it after the timeouts");
        check(!FindNearby.offerHome(SAVED, SERIAL, false, true, false, false, false, false), "not while Volt wants no connection");
        check(!FindNearby.offerHome(SAVED, SERIAL, true, false, false, false, false, false), "not before two timeouts");
        check(!FindNearby.offerHome(SAVED, SERIAL, true, true, true, false, false, false), "not while connected");
        check(!FindNearby.offerHome(SAVED, SERIAL, true, true, false, true, false, false), "not while simulating");
        check(!FindNearby.offerHome(SAVED, SERIAL, true, true, false, false, true, false), "not while recording");
        check(!FindNearby.offerHome(SAVED, SERIAL, true, true, false, false, false, true), "not with a change pending");
    }

    /** Failure mode 6: the tally ignores non-timeout failures and resets on a connect and on a profile switch. */
    private static void timeouts() {
        FindNearby.Timeouts t = new FindNearby.Timeouts();
        t.attempt("a");
        t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(t.count("a") == 1 && !t.stuck("a"), "one timeout: not yet");
        t.failed("a", SAVED.toLowerCase(), SAVED, ConnectionEventLog.Reason.CONNECT_FAILED, false);
        check(t.count("a") == 2 && t.stuck("a"), "a timeout and a connectGatt failure at the saved address: stuck");
        check(!t.stuck("b") && t.count("b") == 0, "another profile is never stuck from this tally");
        // Non-timeout failures and other addresses change nothing, whatever the count.
        for (ConnectionEventLog.Reason r : ConnectionEventLog.Reason.values()) {
            if (r == ConnectionEventLog.Reason.CONNECT_TIMEOUT || r == ConnectionEventLog.Reason.CONNECT_FAILED) continue;
            FindNearby.Timeouts u = new FindNearby.Timeouts();
            u.attempt("a"); u.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
            u.failed("a", SAVED, SAVED, r, false);
            check(u.count("a") == 1, r + " is not counted and doesn't reset");
        }
        // Fix round, P3: an error Android reports before the link ever reached STATE_CONNECTED counts like a timeout;
        // after it came up, a native drop is a lost link and doesn't. No other reason counts either way.
        for (ConnectionEventLog.Reason r : ConnectionEventLog.Reason.values()) {
            boolean counted = r == ConnectionEventLog.Reason.CONNECT_TIMEOUT || r == ConnectionEventLog.Reason.CONNECT_FAILED;
            check(FindNearby.counts(r, false) == counted, r + " after STATE_CONNECTED");
            check(FindNearby.counts(r, true) == (counted || r == ConnectionEventLog.Reason.NATIVE_DISCONNECT), r + " before STATE_CONNECTED");
        }
        FindNearby.Timeouts early = new FindNearby.Timeouts();
        early.attempt("a");
        early.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.NATIVE_DISCONNECT, true);
        early.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.NATIVE_DISCONNECT, true);
        check(early.stuck("a"), "two early native failures at the saved address: stuck");
        early.connected();
        early.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.NATIVE_DISCONNECT, false);
        check(early.count("a") == 0, "a link lost after it came up is not counted");
        early.failed("a", OTHER, SAVED, ConnectionEventLog.Reason.NATIVE_DISCONNECT, true);
        check(early.count("a") == 0, "an early failure at another address (a find) is not counted");
        FindNearby.Timeouts other = new FindNearby.Timeouts();
        other.attempt("a");
        other.failed("a", OTHER, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        other.failed("a", OTHER, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(other.count("a") == 0, "timeouts at another address (a find) don't count");
        other.failed("a", SAVED, "", ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        other.failed("a", null, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(other.count("a") == 0, "no saved address or no attempt address: nothing counted");
        // Resets: a successful connect, a profile switch, an attempt for another profile.
        t.connected();
        check(t.count("a") == 0 && !t.stuck("a"), "a successful connect resets");
        t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(t.stuck("a"), "stuck again");
        t.active("a");
        check(t.stuck("a"), "the same active profile keeps the tally");
        t.active("b");
        check(!t.stuck("a") && t.count("b") == 0, "a profile switch resets");
        t.active("a");
        check(!t.stuck("a"), "switching back starts from zero");
        t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.attempt("b");
        check(!t.stuck("a") && t.count("b") == 0, "an attempt for another profile resets");
        t.attempt("b"); t.failed("b", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.attempt("b");
        check(t.count("b") == 1, "another attempt for the same profile keeps the tally");
        t.failed("c", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(t.count("c") == 1 && t.count("b") == 0, "a timeout for another profile starts its own tally");
        check(FindNearby.TIMEOUTS == 2, "two timeouts");
    }

    /**
     * The owner's rule (Nov 2026: the card showed far too often): Home's card counts only failed attempts begun and ended
     * while Home stayed the page shown with Volt in front. Staying on Home through two shows it; visiting another page or
     * Volt leaving the screen starts the count again; an attempt begun before the stay, or a failure after it, doesn't count.
     */
    private static void homeStay() {
        // Stays on Home: two failed attempts, each begun and ended there, offer the card (and still need all else).
        FindNearby.Timeouts t = new FindNearby.Timeouts();
        t.home(true);
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(t.homeCount("a") == 1 && !t.stuckOnHome("a"), "one failed attempt on Home: not yet");
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(t.stuckOnHome("a") && t.stuck("a"), "two failed attempts while staying on Home: the card");
        check(FindNearby.offerHome(SAVED, SERIAL, true, t.stuckOnHome("a"), false, false, false, false), "the scooter at a new address while the rider waits on Home: offered");
        t.home(true);
        check(t.stuckOnHome("a"), "Home told again (a refresh) keeps the stay");
        // Another page: the count starts again, and a return to Home counts from zero.
        t.home(false);
        check(!t.stuckOnHome("a") && t.homeCount("a") == 0, "another page: no card, the count reset");
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(!t.stuckOnHome("a") && t.count("a") == 4, "failed attempts away from Home never count for the card (the raw tally still does)");
        t.home(true);
        check(!t.stuckOnHome("a") && t.homeCount("a") == 0, "back on Home: the count starts at zero");
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.home(false); t.home(true);
        check(t.homeCount("a") == 0, "a quick visit elsewhere between two attempts resets the count");
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(t.stuckOnHome("a"), "two more on Home after the visit: the card");
        // Volt in the background (onPause, the same false): reset, and the card goes.
        t.home(false);
        check(!t.stuckOnHome("a"), "Volt in the background: no card, the count reset");
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.home(true);
        check(!t.stuckOnHome("a") && t.homeCount("a") == 0, "background attempts (background connection on) don't count on return");
        // An attempt begun before the stay (on another page, or in the background) that fails on Home doesn't count.
        FindNearby.Timeouts early = new FindNearby.Timeouts();
        early.attempt("a");
        early.home(true);
        early.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(early.homeCount("a") == 0, "an attempt begun before Home showed doesn't count");
        early.attempt("a"); early.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        early.attempt("a"); early.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(early.stuckOnHome("a"), "the next two, begun on Home: the card");
        // An attempt begun on Home that fails after leaving and coming back doesn't count either.
        FindNearby.Timeouts away = new FindNearby.Timeouts();
        away.home(true);
        away.attempt("a"); away.home(false); away.home(true);
        away.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(away.homeCount("a") == 0, "an attempt that outlived a visit elsewhere doesn't count");
        // One failure per attempt: a second report for the same attempt isn't another.
        FindNearby.Timeouts once = new FindNearby.Timeouts();
        once.home(true);
        once.attempt("a");
        once.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.NATIVE_DISCONNECT, true);
        once.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(once.homeCount("a") == 1 && !once.stuckOnHome("a"), "two reports of one attempt count once on Home");
        // The tally's other rules hold on Home: only counted reasons at the saved address; a connection, a profile switch reset.
        FindNearby.Timeouts rules = new FindNearby.Timeouts();
        rules.home(true);
        rules.attempt("a"); rules.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.IDENTITY_REJECTED, false);
        rules.attempt("a"); rules.failed("a", OTHER, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        rules.attempt("a"); rules.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.NATIVE_DISCONNECT, false);
        check(rules.homeCount("a") == 0, "a refusal, a find's address and a lost link don't count on Home");
        rules.attempt("a"); rules.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        rules.attempt("a"); rules.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_FAILED, false);
        check(rules.stuckOnHome("a"), "a timeout and a connectGatt failure on Home: the card");
        rules.connected();
        check(!rules.stuckOnHome("a"), "a connection resets the Home count");
        rules.attempt("a"); rules.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        rules.attempt("a"); rules.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        rules.active("b");
        check(!rules.stuckOnHome("a") && !rules.stuckOnHome("b"), "a profile switch resets the Home count");
        rules.attempt("b"); rules.failed("b", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        rules.attempt("b"); rules.failed("b", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(rules.stuckOnHome("b") && !rules.stuckOnHome("a"), "the new profile counts its own stay");
        // Never told Home (a service with no activity, the default): never offered.
        FindNearby.Timeouts never = new FindNearby.Timeouts();
        for (int i = 0; i < 5; i++) { never.attempt("a"); never.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false); }
        check(never.stuck("a") && !never.stuckOnHome("a"), "without Home shown, any number of timeouts offers nothing");
    }

    /** Failure modes 2, 3 and 4: what a pick may send. */
    private static void picks() {
        // 2: an address saved under another profile: nothing, confirmed or not, even the saved address string.
        for (boolean confirmed : new boolean[]{false, true}) {
            check(FindNearby.pick(SAVED, SERIAL, OTHER, true, confirmed) == FindNearby.Step.REFUSE, "another profile's scooter: refused");
        }
        // 3: another address needs the confirmation; only then FIND.
        check(FindNearby.pick(SAVED, SERIAL, OTHER, false, false) == FindNearby.Step.ASK, "another address asks first");
        check(FindNearby.pick(SAVED, SERIAL, OTHER, false, true) == FindNearby.Step.FIND, "confirmed: find");
        // The saved address is an ordinary connection, no question (any letter case).
        check(FindNearby.pick(SAVED, SERIAL, SAVED, false, false) == FindNearby.Step.CONNECT, "the saved address connects as usual");
        check(FindNearby.pick(SAVED, SERIAL, SAVED.toLowerCase(), false, false) == FindNearby.Step.CONNECT, "case doesn't matter");
        check(FindNearby.pick(SAVED, SERIAL, "", false, true) == FindNearby.Step.REFUSE && FindNearby.pick(SAVED, SERIAL, null, false, true) == FindNearby.Step.REFUSE,
                "no picked address: refused");
    }

    /** Failure mode 1 (the rule): only a serial equal to the non-empty saved one matches. */
    private static void serials() {
        check(FindNearby.serial(SERIAL, SERIAL) == FindNearby.Serial.MATCH, "same serial matches");
        check(FindNearby.serial(SERIAL, "Q2538ZZ9US7777778") == FindNearby.Serial.MISMATCH, "another serial: mismatch");
        check(FindNearby.serial(SERIAL, SERIAL.toLowerCase()) == FindNearby.Serial.MISMATCH, "serials compare exactly");
        check(FindNearby.serial(SERIAL, "") == FindNearby.Serial.MISMATCH && FindNearby.serial(SERIAL, null) == FindNearby.Serial.MISMATCH, "none read: mismatch");
        check(FindNearby.serial("", SERIAL) == FindNearby.Serial.NO_SAVED_SERIAL && FindNearby.serial(null, SERIAL) == FindNearby.Serial.NO_SAVED_SERIAL,
                "no saved serial never matches (failure mode 7)");
    }

    private static void sheet() {
        List<FindNearby.Heard> heard = new ArrayList<>(Arrays.asList(
                new FindNearby.Heard("AA:00:00:00:00:01", -80, "25381", false),
                new FindNearby.Heard("AA:00:00:00:00:02", -55, "25382", false),
                new FindNearby.Heard("AA:00:00:00:00:03", -40, "25461", false),
                new FindNearby.Heard("AA:00:00:00:00:04", -70, null, false),
                new FindNearby.Heard("AA:00:00:00:00:05", -60, "25381", true)));
        FindNearby.Sheet s = FindNearby.sheet(heard, "AA:00:00:00:00:09", "2538");
        check(s.hidden == 1, "one other model hidden, got " + s.hidden);
        check(s.shown.size() == 4, "four shown");
        check(s.shown.get(0).mac.endsWith("02") && s.shown.get(1).mac.endsWith("05") && s.shown.get(2).mac.endsWith("04") && s.shown.get(3).mac.endsWith("01"),
                "closest first");
        check(s.preselected == null, "saved address not heard: nothing preselected");
        check(s.shown.get(1).savedElsewhere, "another profile's scooter is shown (disabled by the sheet)");
        // The saved address heard: always shown (even advertising another model), preselected.
        heard.add(new FindNearby.Heard("aa:00:00:00:00:09", -90, "9999", false));
        s = FindNearby.sheet(heard, "AA:00:00:00:00:09", "2538");
        check("aa:00:00:00:00:09".equals(s.preselected) && s.shown.size() == 5 && s.hidden == 1, "saved address shown and preselected");
        // Unknown model: nothing hidden.
        s = FindNearby.sheet(heard, "AA:00:00:00:00:09", "");
        check(s.hidden == 0 && s.shown.size() == 6, "unknown model hides nothing");
        // A full advertised PID compares by its family.
        check(FindNearby.sameModel(FindNearby.family("25381"), "25382") && !FindNearby.sameModel("2538", "25461") && FindNearby.sameModel("2538", null),
                "families compare; an advertisement without a model ID proves nothing");
        check(FindNearby.sheet(new ArrayList<>(), SAVED, "2538").shown.isEmpty(), "nothing heard");
    }

    /** Fix round, P1 (defence in depth): the ordinary scanner never sends a saved scooter's key to another address. */
    private static void ordinaryScanner() {
        check(FindNearby.ordinaryConnect("", OTHER) && FindNearby.ordinaryConnect(null, OTHER), "no saved address (first setup): any pick connects");
        check(FindNearby.ordinaryConnect(SAVED, SAVED) && FindNearby.ordinaryConnect(SAVED, SAVED.toLowerCase()), "the saved address connects");
        check(!FindNearby.ordinaryConnect(SAVED, OTHER) && !FindNearby.ordinaryConnect(SAVED, null) && !FindNearby.ordinaryConnect(SAVED, ""),
                "another address than the saved one never connects straight from the ordinary sheet");
        check(!FindNearby.mayConnect(SAVED, OTHER, "") && !FindNearby.mayConnect(SAVED, OTHER, null) && !FindNearby.mayConnect(SAVED, OTHER, SAVED),
                "the controller refuses another address without a confirmed find for it");
        check(FindNearby.mayConnect(SAVED, OTHER, OTHER), "a confirmed find connects to exactly its address");
        check(!FindNearby.mayConnect(SAVED, "", "") && FindNearby.mayConnect(SAVED, SAVED, ""), "no address is never a find; the saved address is ordinary");
    }

    /** Fix round, P3: a 0x74 reply that can't be read as a serial ends a find without a verdict. */
    private static void verdict() {
        check(FindNearby.serialUnreadable(1, 18) && FindNearby.serialUnreadable(0x10, 1), "an error status is unreadable");
        check(FindNearby.serialUnreadable(0, 1) && FindNearby.serialUnreadable(0, 0), "no bytes after the status byte is unreadable");
        check(!FindNearby.serialUnreadable(0, 18) && !FindNearby.serialUnreadable(0, 40), "a status 0 reply with a whole serial is read (and compared)");
        // #487 review, round 2: a reply cut short of a whole serial proves no other scooter: unreadable, not a refusal.
        for (int length = 2; length < 18; length++) check(FindNearby.serialUnreadable(0, length), "a status 0 reply of " + length + " bytes is unreadable");
    }

    /** Fix round, P3: the model filter's unknown ID, and the latest signal's buckets. */
    private static void signal() {
        // AdvertisedIdentity's fallback (marker -1, two fixed record bytes) is unknown, so it never hides a scooter.
        check(FindNearby.modelId("25461", -1) == null && "25461".equals(FindNearby.modelId("25461", 0)) && "25461".equals(FindNearby.modelId("25461", 5)),
                "only a manufacturer-data model ID is known");
        List<FindNearby.Heard> fallback = Arrays.asList(new FindNearby.Heard("AA:00:00:00:00:07", -50, FindNearby.modelId("25461", -1), false));
        FindNearby.Sheet f = FindNearby.sheet(fallback, SAVED, "2538");
        check(f.hidden == 0 && f.shown.size() == 1, "a packet without manufacturer data is shown, never filtered as another model");
        check(FindNearby.signalRank(-40) == 4 && FindNearby.signalRank(-60) == 4 && FindNearby.signalRank(-61) == 3 && FindNearby.signalRank(-70) == 3
                && FindNearby.signalRank(-71) == 2 && FindNearby.signalRank(-80) == 2 && FindNearby.signalRank(-81) == 1, "the four buckets");
        // First-heard order inside a bucket: a few dB within it never reorders rows.
        List<FindNearby.Heard> heard = Arrays.asList(
                new FindNearby.Heard("AA:00:00:00:00:01", -66, null, false),
                new FindNearby.Heard("AA:00:00:00:00:02", -62, null, false),
                new FindNearby.Heard("AA:00:00:00:00:03", -85, null, false));
        FindNearby.Sheet s = FindNearby.sheet(heard, SAVED, "");
        check(s.shown.get(0).mac.endsWith("01") && s.shown.get(1).mac.endsWith("02") && s.shown.get(2).mac.endsWith("03"), "equal buckets keep first-heard order");
        // The weaker one is walked to: its latest signal moves it up a bucket, and only then it leads.
        heard = Arrays.asList(
                new FindNearby.Heard("AA:00:00:00:00:01", -66, null, false),
                new FindNearby.Heard("AA:00:00:00:00:02", -62, null, false),
                new FindNearby.Heard("AA:00:00:00:00:03", -45, null, false));
        s = FindNearby.sheet(heard, SAVED, "");
        check(s.shown.get(0).mac.endsWith("03") && s.shown.get(1).mac.endsWith("01") && s.shown.get(2).mac.endsWith("02"), "a bucket change reorders");
    }

    /** How the controller and screens use the rules (failure modes 1 to 5, 7 and 8). */
    private static void wiring() {
        String ble = read("src/com/jjricks/volt/BleController.java");
        // 1: the profile's address and serial change in one place only, remember(), after the owner check and a serial
        // that passed the checks; a find's mismatch is refused before serial= and remember().
        check(count(ble, "profile.mac=") == 1 && count(ble, "profile.serial=") == 1, "one assignment each");
        String remember = body(ble, "private boolean remember(){", "\n    /** A verified frame");
        check(remember.contains("if(authenticated&&gatt!=null&&!serial.isEmpty()){") && remember.contains("profile.mac=address;profile.serial=serial;"),
                "remember() saves only for an authenticated link with a checked serial");
        int find = ble.indexOf("if(findSerialRefused(f))return;");
        String serialCheck = body(ble, "private boolean findSerialRefused(Protocol.Frame f){", "\n    }\n");
        check(serialCheck.contains("if(!finding()||f.cmd!=0x74)return false;")
                && serialCheck.contains("if(FindNearby.serial(profile.serial,received)==FindNearby.Serial.MATCH)return false;")
                && serialCheck.contains("findRefused();return true;"), "a find's serial: anything but a match is refused");
        int notSaved = ble.indexOf("if(!profile.serial.isEmpty()&&!profile.serial.equals(received)){failed(M.bleFailedNotSaved(),");
        int assigned = ble.indexOf("serial=received;", notSaved);
        check(find > 0 && find < notSaved && notSaved < assigned && assigned < ble.indexOf("if(!remember())return;", assigned),
                "a find's serial is checked before the usual check, the transport serial and remember()");
        check(ble.indexOf("send(Protocol.frame(0x74,null));") > ble.indexOf("else if(!authenticated){authenticated=true;"), "the serial is read only after the owner check passed");
        // 2: another profile's address: findAt refuses it, and mayOffer still refuses it before the owner check.
        String findAt = body(ble, "public FindNearby.Step findAt(ScanResult result,boolean confirmed){", "\n    }\n");
        check(findAt.contains("FindNearby.pick(p.mac,p.serial,mac,profiles.hardwareMatch(p.id,mac,null)!=null,confirmed)"), "findAt passes another profile's address");
        String auth = body(ble, "private void authenticate(){", "private boolean mayOffer(String address){");
        check(auth.indexOf("!mayOffer(") > 0 && auth.indexOf("!mayOffer(") < auth.indexOf("send(Protocol.auth("), "mayOffer before the owner check");
        check(body(ble, "private boolean mayOffer(String address){", "private void ownerRefused(").contains("profiles.hardwareMatch(profile.id,address,null)"), "mayOffer refuses saved hardware");
        // 3: only CONNECT and FIND reach connectDiscovered, and FIND needs confirmed.
        int gate = findAt.indexOf("if(step!=FindNearby.Step.CONNECT&&step!=FindNearby.Step.FIND)return step;");
        check(gate > 0 && gate < findAt.indexOf("connectDiscovered(result);") && count(findAt, "connectDiscovered(") == 1, "nothing is sent before the step allows it");
        check(findAt.indexOf("pendingFindMac=step==FindNearby.Step.FIND?mac:\"\";") < findAt.indexOf("connectDiscovered(result);")
                && findAt.lastIndexOf("pendingFindMac=\"\";") > findAt.indexOf("connectDiscovered(result);"), "the find address is for this connection only");
        check(count(ble, "pendingFindMac=") == 4, "pendingFindMac: declared, set and cleared by findAt, cleared by connect");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        String select = body(main, "private void findSelected(ScanResult result){", "\n    }\n");
        check(select.contains("scooterService.ble.findAt(result,false)") && select.contains("if(step==FindNearby.Step.ASK){confirmFind(result);return;}"),
                "the sheet's Connect asks first");
        check(count(main, "findAt(result,true)") == 1, "one confirmed call");
        // 4: Cancel and dismiss send nothing: the only confirmed call is in the confirmation's Connect.
        String confirm = body(main, "private void confirmFind(ScanResult result){", "\n    }\n");
        check(confirm.contains(".setNegativeButton(T.commonCancel(),null).setPositiveButton(T.mainActionConnect(),(d,which)->{")
                && confirm.indexOf("findAt(result,true)") > confirm.indexOf(".setPositiveButton(") && !confirm.contains("setOnDismissListener")
                && !confirm.contains("setOnCancelListener"), "only Connect sends; Cancel and dismiss do nothing");
        // 5: no automatic reconnect to the new address after a mismatch.
        String refused = body(ble, "private void findRefused(){", "\n    }\n");
        check(refused.contains("wanted=false;") && refused.contains("failed(findNotice,false,ConnectionEventLog.Reason.IDENTITY_REJECTED,-1);"), "a mismatch: no retry, not wanted");
        String failure = body(ble, "private void failed(String text,boolean retry,ConnectionEventLog.Reason reason,int code){", "\n    private void notice(");
        check(failure.contains("connect(profile,profile.mac);") && count(failure, "connect(") == 1, "the only reconnect goes to the saved address");
        check(failure.indexOf("timeouts.failed(profile.id,attemptMac,profile.mac,reason,!mtuRequested&&bluetoothEnabled());") > 0
                && failure.indexOf("timeouts.failed(") < failure.indexOf("close("), "the tally sees the attempt (and whether it came up) before close");
        String close = body(ble, "private void close(State next){", "\n    private void failed(String");
        check(close.contains("stopScan();endFind();") && ble.contains("private void endFind(){findMac=\"\";}"), "every close ends the find");
        check(ble.contains("findMac=mac.equals(pendingFindMac)?mac:\"\";pendingFindMac=\"\";"), "connect() starts a find only from a confirmed pick");
        String connect = body(ble, "private void connect(ScooterProfiles.Profile p,String mac){", "\n    private void close(");
        check(connect.indexOf("close(State.CONNECTING);") < connect.indexOf("findAttempt(p.id,mac);") && connect.indexOf("close(State.CONNECTING);") < connect.indexOf("connectGatt("),
                "the find address is set after the old link closed (8: one link)");
        // The found notice only on a find that reached ready (its serial matched above).
        check(remember.contains("if(justReady&&finding())foundAtNewAddress();")
                && ble.contains("private void foundAtNewAddress(){findMac=\"\";findNotice=M.bleFoundNewAddress(profile.shownName());notice(findNotice);}")
                && remember.indexOf("finding()") > remember.indexOf("profiles.update(profile);"), "found once saved");
        check(remember.contains("if(justReady)timeouts.connected();"), "a connection resets the tally");
        check(ble.contains("ScooterProfiles.Profile p=profiles.active();if(p!=null)timeouts.active(p.id);"), "a profile switch resets the tally before Home asks");
        // 7: find mode only for an eligible profile.
        check(main.contains("if(find&&!FindNearby.offerRow(finding.mac,finding.serial))return;"), "the sheet's find mode needs a saved address and serial");
        String more = read("src/com/jjricks/volt/MoreScreen.java");
        check(more.contains("if (FindNearby.offerRow(p.mac, p.serial)) {") && more.contains("if (p == null || !FindNearby.offerRow(p.mac, p.serial)) { host.redraw(); return; }"), "the row too");
        check(ble.contains("FindNearby.offerHome(p.mac,p.serial,wanted,timeouts.stuckOnHome(p.id),connected()||answeringAt(p.mac),simulating(),recording,mutation!=null)"), "Home's button: the Home stay's count");
        // The Home stay (owner, Nov 2026): MainActivity tells the tally where the page changes, on every refresh, and as
        // Volt leaves the screen (not for a recreation on screen); only Home, resumed, outside riding mode and setup, starts one.
        check(ble.contains("public void homeShown(boolean shown){timeouts.home(shown);}"), "the controller passes the stay on");
        String watch = body(main, "    private void watchHome(){", "\n    }\n");
        check(watch.contains("boolean home=HubPages.HOME.equals(selectedPage)&&riding==null&&onboarding==null&&!enteringMaintenance&&!startupRedirect&&!destroyed;")
                && watch.contains("if(!home)scooterService.ble.homeShown(false);") && watch.contains("else if(resumed)scooterService.ble.homeShown(true);"), "Home, resumed, starts a stay; anything else ends it");
        check(main.contains("if(scooterService!=null&&!isChangingConfigurations())scooterService.ble.homeShown(false);\n        resumed=false;"), "onPause ends the stay, a recreation doesn't");
        // #558 review, F02: a fresh start's first attempt begins in the service's onCreate, before the bind. The activity
        // keeps the stay where an unbound service reads it, the service tells the controller before it can attempt, and
        // the bind tells the bound service before visible() can start one.
        check(watch.indexOf("if(!home)ScooterService.homeHint(false);else if(resumed)ScooterService.homeHint(true);") >= 0
                && watch.indexOf("ScooterService.homeHint") < watch.indexOf("if(scooterService==null)return;"), "an unbound service hears the stay too");
        check(main.contains("if(!isChangingConfigurations())ScooterService.homeHint(false);\n        if(scooterService!=null&&!isChangingConfigurations())"), "onPause ends the kept stay as well");
        String service = read("src/com/jjricks/volt/ScooterService.java");
        check(service.contains("ble.homeShown(homeShown);ble.visibility("), "the service tells the controller the stay before its first attempt");
        // #580 review, round 2: Volt reopened on Home while the service still runs (its idle window after Back), before the
        // new activity binds: the running service's controller hears the stay at once, on the main thread or posted as
        // setUiVisible posts, and resume() tells it (watchHome) before setUiVisible can start the attempt.
        String hint = body(service, "    static void homeHint(boolean shown){", "\n    }\n");
        check(hint.contains("homeShown=shown;ScooterService service=running;if(service==null)return;")
                && hint.contains("if(Looper.myLooper()==Looper.getMainLooper()){if(service.ble!=null)service.ble.homeShown(shown);}")
                && hint.contains("else service.handler.post(()->{if(running==service&&service.ble!=null)service.ble.homeShown(homeShown);});"),
                "a running service's controller hears the stay at once");
        String resumed = body(main, "    private void resume(){", "\n    }\n");
        check(resumed.indexOf("watchHome();") >= 0 && resumed.indexOf("watchHome();") < resumed.indexOf("ScooterService.setUiVisible(true);"),
                "resume tells the stay before the service hears Volt is visible");
        String connected = body(main, "        private void connected(IBinder binder){", "\n        }\n");
        check(connected.indexOf("watchHome();") >= 0 && connected.indexOf("watchHome();") < connected.indexOf("scooterService.visible(resumed);")
                && connected.indexOf("watchHome();") > connected.indexOf("scooterService=((ScooterService.LocalBinder)binder).service();"), "the bind tells the stay before visible()");
        // The tally itself: a fresh start told Home before its first attempt counts that attempt.
        FindNearby.Timeouts fresh = new FindNearby.Timeouts();
        fresh.home(true);
        fresh.attempt("a"); fresh.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        fresh.home(true);  // the bind's watchHome: the same stay
        fresh.attempt("a"); fresh.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(fresh.stuckOnHome("a"), "told before the first attempt and again at the bind: both attempts count");
        // The rebind path: Back ended the stay (the service stays up in its idle window); Volt reopens on Home and the
        // running service's controller hears the stay before setUiVisible's attempt, so that attempt counts.
        FindNearby.Timeouts rebind = new FindNearby.Timeouts();
        rebind.home(true);
        rebind.home(false);  // Back: onPause's hint
        rebind.home(true);   // reopened: resume's watchHome reaches the running service, before setUiVisible
        rebind.attempt("a"); rebind.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(rebind.homeCount("a") == 1, "reopened on Home with the service still up: its first attempt counts");
        rebind.home(true);   // the bind's watchHome: the same stay
        rebind.attempt("a"); rebind.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        check(rebind.stuckOnHome("a"), "and with the next one, the card");
        check(body(main, "    private void resume(){", "\n    }\n").contains("resumed=true;resumes++;missedStartOffered=0;watchHome();ScooterService.setUiVisible(true);"),
                "a return to Home starts a stay before the service hears Volt is visible (its first attempt counts)");
        check(main.contains("String page=nav.current();selectedPage=page;preferences.edit().putString(\"last_page\",page).apply();watchHome();"), "every page change through the stack");
        check(main.contains("nav.reset(page);selectedPage=nav.current();watchHome();}") && main.contains("nav.reset(MaintenanceScreen.PAGE);selectedPage=nav.current();watchHome();}")
                && main.contains("preferences.edit().putString(\"last_page\",selectedPage).apply();watchHome();"), "every reset of the stack, and an expired return");
        check(main.contains("riding=session;watchHome();") && main.contains("    private void refreshBodyNow(){\n        watchHome();"), "riding mode, and every body refresh");
        // 8: Find during an attempt ends it first: the sheet disconnects before scanning.
        String show = body(main, "private void showScanner(boolean start,boolean find){", "\n    private void connectSelectedScan(){");
        check(show.contains("if(start){scooterService.ble.disconnect();scooterService.ble.scan();}") && main.contains("showScanner(true,true);"), "Find disconnects, then scans");
        // R6: the sheet's own refusals come first.
        check(show.indexOf("mainScanExitSimulationFind") < show.indexOf("ble.disconnect()") && show.indexOf("mainScanStopRecording") < show.indexOf("ble.disconnect()")
                && show.indexOf("mainScanBusy") < show.indexOf("ble.disconnect()"), "simulation, recording and busy are refused before anything");
    }

    /** The fix round's wiring (council review of a47b5ab7): each upheld finding where the controller and screens apply it. */
    private static void fixRound() {
        String main = read("src/com/jjricks/volt/MainActivity.java");
        String ble = read("src/com/jjricks/volt/BleController.java");
        // P1: every wait keeps the find mode, and every resume reopens in it. No resume opens the ordinary scanner.
        check(!main.contains("showScanner(true);") && !main.contains("pendingScan=false;showScanner("), "no resume site reopens the ordinary scanner by itself");
        check(count(main, "if(pendingScan){resumePendingScan();}") == 4, "onServiceConnected, onResume, requestBluetooth and Bluetooth enable resume through resumePendingScan");
        check(main.contains("private void resumePendingScan(){boolean find=pendingFind,home=find&&pendingHomeFind;String tapped=pendingHomeFindId;pendingScan=pendingFind=pendingHomeFind=false;pendingHomeFindId=null;")
                && main.contains("homeFind=home;homeFindId=tapped;try{showScanner(true,find);}finally{homeFind=false;homeFindId=null;}}"), "a resume reopens in the asked mode");
        check(main.contains("private void deferScan(boolean find){pendingScan=true;pendingFind=find;pendingHomeFind=find&&homeFind;pendingHomeFindId=pendingHomeFind?homeFindId:null;}") && count(main, "pendingScan=true") == 1,
                "pendingScan is set only with its mode");
        String show = body(main, "private void showScanner(boolean start,boolean find){", "\n    private void connectSelectedScan(){");
        check(count(show, "deferScan(find);") == 4 && !show.contains("pendingScan"), "the four waits in showScanner (resume, permission, the service, Bluetooth on) keep the mode");
        check(count(main, "pendingConnect=pendingScan=pendingFind=false;") == 3 && !main.contains("pendingConnect=pendingScan=false;"), "every clear clears the mode too");
        check(main.contains("pendingScan=saved.getBoolean(\"pending_scan\",false);") && main.contains("if(saved!=null)pendingFind=pendingScan&&saved.getBoolean(\"pending_find\",false);")
                && main.contains("saved.putBoolean(\"pending_find\",pendingFind);"), "saved and restored with pendingScan");
        // Every assignment of pendingScan is one of those.
        int assignments = count(main, "pendingScan=") - count(main, "pendingScan==");
        check(assignments == 1 + 1 + 3 + 1, "pendingScan: deferScan, resumePendingScan, three clears and the restore, got " + assignments);
        // P1, defence in depth: the ordinary sheet routes another address through the find's steps; the controller refuses it.
        String connectScan = body(main, "private void connectSelectedScan(){", "\n    private static int signalRank(");
        check(connectScan.indexOf("if(scanFind||active!=null&&!FindNearby.ordinaryConnect(active.mac,scanSelected)){") > 0
                && connectScan.indexOf("findSelected(result)") < connectScan.indexOf("scooterService.ble.connectDiscovered(result)"), "the ordinary sheet's other address goes through findSelected");
        String discovered = body(ble, "public void connectDiscovered(ScanResult result){", "\n    /**");
        check(discovered.indexOf("if(!FindNearby.mayConnect(p.mac,result.getDevice().getAddress(),pendingFindMac))return;") > 0
                && discovered.indexOf("FindNearby.mayConnect(") < discovered.indexOf("wanted=true;") && discovered.indexOf("FindNearby.mayConnect(") < discovered.indexOf("connect(p,mac);"),
                "connectDiscovered refuses another address without a confirmed find, before anything");
        // P2: Home's Find refuses what entry B refuses, isn't offered then, and a stale tap never ends a live link.
        String find = body(main, "private void findNearby(boolean home){", "\n    }\n");
        check(find.contains("String unavailable=scooterService.scooterChangeUnavailable();if(unavailable!=null){toast(unavailable);return;}")
                && find.indexOf("scooterChangeUnavailable()") < find.indexOf("if(home&&!homeFindOffered()){redraw();return;}")
                && find.indexOf("if(home&&!homeFindOffered()){redraw();return;}") < find.indexOf("showScanner(true,true);", find.indexOf("homeFindOffered")),
                "the guard and the stale check come before the sheet disconnects");
        check(main.contains("private boolean homeFindOffered(){return scooterService!=null&&scooterService.scooterChangeUnavailable()==null&&scooterService.ble.findNearbyOffered();}")
                && main.contains("public void findNearby(){MainActivity.this.findNearby(true);}public boolean findNearbyOffered(){return homeFindOffered();}"), "Home's card: offered and tapped through the same rule");
        check(show.contains("if(find){String unavailable=scooterService.scooterChangeUnavailable();if(unavailable!=null){toast(unavailable);return;}}")
                && show.indexOf("scooterChangeUnavailable()") < show.indexOf("ble.disconnect()"), "a find that comes back from a wait is refused the same way");
        check(ble.contains("connected()||answeringAt(p.mac)") && ble.contains("private boolean answeringAt(String mac){return gatt!=null&&(mtuRequested||authenticated)&&mac!=null&&mac.equalsIgnoreCase(gatt.getDevice().getAddress());}"),
                "a link that came up at the saved address counts as connected");
        // P3: a find that ends without a verdict.
        String failure = body(ble, "private void failed(String text,boolean retry,ConnectionEventLog.Reason reason,int code){", "\n    private void notice(");
        check(failure.indexOf("if(retry&&findUnverified()){findUnchecked(reason,code);return;}") > 0
                && failure.indexOf("findUnverified()") < failure.indexOf("recordDiagnostic(") && failure.indexOf("findUnverified()") < failure.indexOf("connect(profile,profile.mac);"),
                "a retrying failure of an unverified find shows the find's notice first");
        String unchecked = body(ble, "private void findUnchecked(ConnectionEventLog.Reason reason,int code){", "\n    }\n");
        check(unchecked.indexOf("endFind();") >= 0 && unchecked.indexOf("endFind();") < unchecked.indexOf("failed(findNotice,true,reason,code);")
                && unchecked.contains("findNotice=M.bleFailedFindUnchecked(profile.shownName());"), "one notice, then the ordinary retry to the saved address (no loop)");
        check(ble.contains("private boolean findUnverified(){return !findMac.isEmpty()&&profile!=null&&!findMac.equalsIgnoreCase(profile.mac);}"), "only before the address was saved");
        String serial = body(ble, "private boolean findSerialRefused(Protocol.Frame f){", "\n    }\n");
        check(serial.indexOf("if(FindNearby.serialUnreadable(f.error(),f.data.length)){findUnchecked(") > 0
                && serial.indexOf("serialUnreadable") < serial.indexOf("FindNearby.serial(profile.serial,received)"), "an unreadable serial ends the find at once");
        // P3: the latest signal, and only manufacturer-data model IDs.
        check(ble.contains("Integer before=discoveryRssi.put(mac,r.getRssi());") && ble.contains("FindNearby.signalRank(before)!=FindNearby.signalRank(r.getRssi())")
                && ble.contains("if(!discoveries.containsKey(mac)){discoveries.put(mac,r);changed();}"), "the first result stays; the latest signal is kept, redrawn on a bucket change");
        check(ble.contains("public AdvertisedIdentity.Result advertisedAt(String mac){return advertisedModels.get(mac);}")
                && ble.contains("FindNearby.modelId(id.pid,id.marker)!=null&&advertisedModels.put(mac,id)"), "the find sheet's model IDs come from manufacturer data only");
        check(main.contains("scooterService.ble.signal(result),id==null?null:FindNearby.modelId(id.pid,id.marker),") && main.contains("int rank=signalRank(scooterService.ble.signal(result));"),
                "the find sheet and the rows use the latest signal");
        // P3: every scooter switch resets the tally for a different profile.
        check(ble.contains("public void scooterSwitched(){ScooterProfiles.Profile p=profiles.active();timeouts.active(p==null?null:p.id);profileSelected();}"), "scooterSwitched resets, then profileSelected");
        for (String path : new String[]{"src/com/jjricks/volt/MainActivity.java", "src/com/jjricks/volt/MoreScreen.java", "src/com/jjricks/volt/ScooterService.java"})
            check(!read(path).contains("ble.profileSelected();"), path + ": switches go through scooterSwitched");
        check(count(read("src/com/jjricks/volt/MoreScreen.java"), "s.ble.scooterSwitched();") == 2 && main.contains("scooterService.ble.scooterSwitched();"), "Use, Find at a new address and imports");
    }

    /** The #487 review's second-round P3s: each rule, then where the controller, the screens and setup apply it. */
    private static void secondRound() {
        // A fallback model ID (marker -1: two fixed bytes, maybe the name's letters) that disagrees with the serial isn't kept.
        check(!FindNearby.captureModelId("22081", -1, SERIAL), "fallback bytes that disagree with the serial are not a model ID");
        check(FindNearby.captureModelId("25381", -1, SERIAL), "fallback bytes that agree with the serial are kept, as before");
        check(FindNearby.captureModelId("22081", 0, SERIAL) && FindNearby.captureModelId("25381", 0xb0, SERIAL),
                "manufacturer data is kept as before, a conflict included (the resolver handles it)");
        check(!FindNearby.captureModelId("", 0, SERIAL) && !FindNearby.captureModelId(null, -1, SERIAL), "nothing to keep");
        // The find sheet's family: a saved ID that disagrees with the serial hides nothing.
        DeviceDescriptor agreeing = DeviceDescriptor.of(SERIAL, "25381", 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor conflicting = DeviceDescriptor.of(SERIAL, "22081", 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor serialOnly = DeviceDescriptor.of(SERIAL, "", 0, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        check(conflicting.conflict() && FindNearby.family(conflicting).isEmpty() && "2538".equals(FindNearby.family(agreeing))
                && "2538".equals(FindNearby.family(serialOnly)) && FindNearby.family((DeviceDescriptor) null).isEmpty(), "the find family ignores a conflicting saved ID");
        List<FindNearby.Heard> heard = Arrays.asList(new FindNearby.Heard(OTHER, -50, "25381", false));
        check(FindNearby.sheet(heard, SAVED, FindNearby.family(conflicting)).shown.size() == 1
                && FindNearby.sheet(heard, SAVED, conflicting.family()).hidden == 1, "so the scooter looked for is listed, where the saved ID hid it");

        String ble = read("src/com/jjricks/volt/BleController.java");
        String remember = body(ble, "private boolean remember(){", "\n    /** A verified frame");
        check(remember.contains("&&FindNearby.captureModelId(pendingAdvertised.pid,pendingAdvertised.marker,serial);"), "remember() keeps only a model ID the rule keeps");
        // The scan: a listed scooter's packet without its name or service ID updates its signal, and nothing else.
        String scan = body(ble, "scanCallback=new ScanCallback(){", "@Override public void onScanFailed(");
        int filter = scan.indexOf("(name==null||!name.toLowerCase(Locale.ROOT).contains(\"navee\"))){");
        int rssi = scan.indexOf("if(discoveries.containsKey(mac)){Integer before=discoveryRssi.put(mac,r.getRssi());", filter);
        check(filter > 0 && rssi > filter && scan.indexOf("return;", rssi) < scan.indexOf("AdvertisedIdentity.parse(", filter), "the filtered packet updates the signal and returns before the model ID is read");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(main.contains("FindNearby.Sheet sheet=FindNearby.sheet(heard,profile.mac,FindNearby.family(profile.savedDescriptor()));"), "the sheet filters on the safe family");
        // Home's find that waited is asked again before the sheet disconnects anything.
        String find = body(main, "private void findNearby(boolean home){", "\n    }\n");
        check(find.contains("if(scooterService==null){homeFind=home;homeFindId=null;try{showScanner(true,true);}finally{homeFind=false;}return;}")
                && find.contains("homeFind=home;homeFindId=home&&profile!=null?profile.id:null;try{showScanner(true,true);}finally{homeFind=false;homeFindId=null;}")
                && !find.contains("\n        showScanner(true,true);"), "both of Home's calls mark it, with the profile it was tapped for");
        check(main.contains("if(saved!=null)pendingHomeFind=pendingFind&&saved.getBoolean(\"pending_home_find\",false);")
                && main.contains("saved.putBoolean(\"pending_home_find\",pendingHomeFind);"), "kept across a recreation");
        // Setup's own profile changes reset the tally as My scooters' Use does.
        String setup = read("src/com/jjricks/volt/OnboardingScreen.java");
        check(setup.contains("private void scooterSwitched() {\n        ScooterService service = host.service();\n        if (service != null) service.ble.scooterSwitched();"), "setup tells the controller");
        check(body(setup, "profile = store.createMade(id, name, NewOwnerKey.generate());", "hideKeyboard();").contains("scooterSwitched();"), "after a made key");
        check(body(setup, "profile = store.create(name, parsed.identity, parsed.role", "identityField.setText(\"\");").contains("scooterSwitched();"), "after a typed key");
        check(setup.contains("if (mayRemoveCreated(made)) { store.remove(made.id); scooterSwitched(); }"), "after leaving a made key");
        // The tally the rule protects: A, then B, then A again starts A from zero.
        FindNearby.Timeouts t = new FindNearby.Timeouts();
        t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false);
        t.active("made"); t.active("a");
        check(t.count("a") == 0, "a profile switch through setup starts the tally again");
    }

    /** #621 council round 1: the fixes' rules, then where BleController and MainActivity apply them. */
    private static void councilRound() {
        // [P2] A refused fallback ID never stands in for the model: a UT5 MAX whose fallback bytes read as a golf-cart ID
        // (24352) stays a UT5 MAX, so statusRead keeps refusing its short 0x70 frames (it accepts them only off the UT5 MAX).
        String live = FindNearby.liveModelId("24352", -1, SERIAL, "");
        DeviceDescriptor link = DeviceDescriptor.of(SERIAL, live, 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor refusedId = DeviceDescriptor.of(SERIAL, "24352", 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        check(live.isEmpty() && ModelRegistry.ut5Max(link) && !ModelRegistry.ut5Max(refusedId), "the link stays a UT5 MAX: short status frames stay refused");
        check("25381".equals(FindNearby.liveModelId("25381", -1, SERIAL, "")) && "22081".equals(FindNearby.liveModelId("22081", 0, SERIAL, ""))
                && "25381".equals(FindNearby.liveModelId("22081", -1, SERIAL, "25381")) && "22081".equals(FindNearby.liveModelId("22081", -1, "", "")),
                "an agreeing or manufacturer-data ID still leads; a refused one gives the saved ID; no serial yet, nothing to refuse");
        String ble = read("src/com/jjricks/volt/BleController.java");
        String remember = body(ble, "private boolean remember(){", "\n    /** A verified frame");
        int captured = remember.indexOf("if(captured){profile.advertisedPid=pendingAdvertised.pid;");
        check(captured > 0 && remember.indexOf("else if(pendingAdvertised!=null&&address.equals(pendingAdvertisedMac)){pendingAdvertised=null;pendingAdvertisedMac=\"\";}") > captured,
                "a refused ID is cleared in remember()");
        String transport = body(ble, "private DeviceDescriptor transportDescriptor(){", "\n    }\n");
        check(transport.contains("FindNearby.liveModelId(pendingAdvertised.pid,pendingAdvertised.marker,s,saved)") && !transport.contains("?pendingAdvertised.pid:"),
                "the link's model never reads a refused ID");
        check(ble.contains("private boolean statusRead(Protocol.Frame f){return f.cmd==0x70&&f.error()==0&&f.data.length>=4&&(f.data[3]&255)<=1&&(f.data.length>=14||!ModelRegistry.ut5Max(transportDescriptor()));}"),
                "statusRead judges short frames by the link's model");

        // [P2] Bluetooth off, Home's find tapped, Android's Bluetooth dialog, enabled, back: the sheet opens.
        FindNearby.Timeouts t = new FindNearby.Timeouts();
        t.home(true);
        for (int i = 0; i < FindNearby.TIMEOUTS; i++) { t.attempt("a"); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT, false); }
        check(FindNearby.offerHome(SAVED, SERIAL, true, t.stuckOnHome("a"), false, false, false, false), "Home offers the find");
        t.home(false);  // the dialog pauses Volt: the stay ends
        t.home(true);   // back on Home
        check(!FindNearby.offerHome(SAVED, SERIAL, true, t.stuckOnHome("a"), false, false, false, false), "the offer's own rule would drop it now (the old resume)");
        check(FindNearby.homeFindStillWanted("a", "a", SAVED, SERIAL, false, false, false, false), "the tapped find still opens its sheet");
        // Item 11's safety point: nothing is disconnected unless the find is still wanted.
        check(!FindNearby.homeFindStillWanted("a", "a", SAVED, SERIAL, true, false, false, false), "a connection that came up meanwhile isn't ended");
        check(!FindNearby.homeFindStillWanted("a", "b", SAVED, SERIAL, false, false, false, false) && !FindNearby.homeFindStillWanted(null, "a", SAVED, SERIAL, false, false, false, false),
                "another scooter selected meanwhile, or no tap recorded");
        check(!FindNearby.homeFindStillWanted("a", "a", SAVED, SERIAL, false, true, false, false) && !FindNearby.homeFindStillWanted("a", "a", SAVED, SERIAL, false, false, true, false)
                && !FindNearby.homeFindStillWanted("a", "a", SAVED, SERIAL, false, false, false, true) && !FindNearby.homeFindStillWanted("a", "a", SAVED, "", false, false, false, false),
                "simulation, a recording, a change in flight, or no saved serial");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        String resume = body(main, "private void resumePendingScan(){", "}}");
        check(resume.contains("if(home&&scooterService!=null&&!(tapped!=null?scooterService.ble.homeFindStillWanted(tapped):homeFindOffered())){redraw();return;}")
                && resume.indexOf("homeFindStillWanted(tapped)") < resume.indexOf("showScanner(true,find)"), "the resume asks the tapped find's rule before the sheet disconnects anything");
        check(main.contains("if(request==ENABLE_BLUETOOTH){if(result==RESULT_OK){if(pendingScan){resumePendingScan();}"), "Bluetooth enabled resumes the waiting find");
        check(main.contains("if(saved!=null)pendingHomeFindId=pendingHomeFind?saved.getString(\"pending_home_find_id\"):null;")
                && main.contains("saved.putString(\"pending_home_find_id\",pendingHomeFindId);"), "the tapped profile survives a recreation");
        check(ble.contains("return p!=null&&FindNearby.homeFindStillWanted(tappedFor,p.id,p.mac,p.serial,connected()||answeringAt(p.mac),simulating(),recording,mutation!=null);"),
                "the controller applies it to the active scooter");
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

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        assertions++;
    }
}
