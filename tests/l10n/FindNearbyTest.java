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
        picks();
        serials();
        sheet();
        wiring();
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
        t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        check(t.count("a") == 1 && !t.stuck("a"), "one timeout: not yet");
        t.failed("a", SAVED.toLowerCase(), SAVED, ConnectionEventLog.Reason.CONNECT_FAILED);
        check(t.count("a") == 2 && t.stuck("a"), "a timeout and a connectGatt failure at the saved address: stuck");
        check(!t.stuck("b") && t.count("b") == 0, "another profile is never stuck from this tally");
        // Non-timeout failures and other addresses change nothing, whatever the count.
        for (ConnectionEventLog.Reason r : ConnectionEventLog.Reason.values()) {
            if (r == ConnectionEventLog.Reason.CONNECT_TIMEOUT || r == ConnectionEventLog.Reason.CONNECT_FAILED) continue;
            FindNearby.Timeouts u = new FindNearby.Timeouts();
            u.attempt("a"); u.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
            u.failed("a", SAVED, SAVED, r);
            check(u.count("a") == 1, r + " is not counted and doesn't reset");
        }
        FindNearby.Timeouts other = new FindNearby.Timeouts();
        other.attempt("a");
        other.failed("a", OTHER, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        other.failed("a", OTHER, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        check(other.count("a") == 0, "timeouts at another address (a find) don't count");
        other.failed("a", SAVED, "", ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        other.failed("a", null, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        check(other.count("a") == 0, "no saved address or no attempt address: nothing counted");
        // Resets: a successful connect, a profile switch, an attempt for another profile.
        t.connected();
        check(t.count("a") == 0 && !t.stuck("a"), "a successful connect resets");
        t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        check(t.stuck("a"), "stuck again");
        t.active("a");
        check(t.stuck("a"), "the same active profile keeps the tally");
        t.active("b");
        check(!t.stuck("a") && t.count("b") == 0, "a profile switch resets");
        t.active("a");
        check(!t.stuck("a"), "switching back starts from zero");
        t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT); t.failed("a", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        t.attempt("b");
        check(!t.stuck("a") && t.count("b") == 0, "an attempt for another profile resets");
        t.attempt("b"); t.failed("b", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        t.attempt("b");
        check(t.count("b") == 1, "another attempt for the same profile keeps the tally");
        t.failed("c", SAVED, SAVED, ConnectionEventLog.Reason.CONNECT_TIMEOUT);
        check(t.count("c") == 1 && t.count("b") == 0, "a timeout for another profile starts its own tally");
        check(FindNearby.TIMEOUTS == 2, "two timeouts");
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
        check(serialCheck.contains("if(!finding()||f.cmd!=0x74||f.error()!=0||f.data.length<2)return false;")
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
        check(failure.indexOf("timeouts.failed(profile.id,attemptMac,profile.mac,reason);") < failure.indexOf("close("), "the tally sees the attempt before close");
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
        check(ble.contains("FindNearby.offerHome(p.mac,p.serial,wanted,timeouts.stuck(p.id),connected(),simulating(),recording,mutation!=null)"), "Home's button");
        // 8: Find during an attempt ends it first: the sheet disconnects before scanning.
        String show = body(main, "private void showScanner(boolean start,boolean find){", "\n    private void connectSelectedScan(){");
        check(show.contains("if(start){scooterService.ble.disconnect();scooterService.ble.scan();}") && main.contains("showScanner(true,true);"), "Find disconnects, then scans");
        // R6: the sheet's own refusals come first.
        check(show.indexOf("mainScanExitSimulationFind") < show.indexOf("ble.disconnect()") && show.indexOf("mainScanStopRecording") < show.indexOf("ble.disconnect()")
                && show.indexOf("mainScanBusy") < show.indexOf("ble.disconnect()"), "simulation, recording and busy are refused before anything");
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
