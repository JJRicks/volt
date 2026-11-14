package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The release hold (ReleaseHolds) as BleController uses it: a sent release that ends unconfirmed holds its scooter on
 * disk, a fresh process built from what was saved still holds it and still owes the question, and only Reconnect or
 * the profile going ends it. Then the connection code's own calls into it, read from the source: every connection path
 * asks heldBack, close() and the release timeout record the hold, failed() asks retries, the store loads from its own
 * preferences, Forget ends the hold, and no backup reads the namespace. Synthesized ids only.
 */
public final class ReleaseHoldsTest {
    private static int checks;
    private static final String A = "profile-a", B = "profile-b";

    /** A preference file standing in for SharedPreferences: what the last save left, as a fresh process reads it. */
    private static final class Disk implements ReleaseHolds.Store {
        Set<String> held, owed; int saves;
        public void save(Set<String> h, Set<String> o) { held = new LinkedHashSet<>(h); owed = new LinkedHashSet<>(o); saves++; }
        ReleaseHolds load() { return new ReleaseHolds(held, owed, this); }
    }

    public static void main(String[] args) {
        endings();
        freshProcess();
        answers();
        clearing();
        connectionCode();
        System.out.println("ReleaseHoldsTest: " + checks + " checks");
    }

    /** Only a sent release holds; nothing sent, no profile or a refusal's ending holds nothing. */
    private static void endings() {
        Disk disk = new Disk();
        ReleaseHolds holds = disk.load();
        check(!holds.held(A) && holds.owed(Arrays.asList(A, B)) == null, "an empty store holds nothing");
        holds.ended(A, false);
        holds.ended(null, true);
        holds.ended("", true);
        check(!holds.held(A) && disk.saves == 0, "an ending with no release sent, or no profile, holds nothing and writes nothing");
        check(holds.retries(A, false), "an ordinary drop retries");
        check(!holds.retries(A, true), "a drop with a release sent never retries");
        holds.ended(A, true);
        check(holds.held(A) && !holds.held(B), "a sent release that ended unconfirmed holds that profile only");
        check(!holds.retries(A, false) && holds.retries(B, false), "a held profile never retries; another scooter does");
        check(A.equals(holds.owed(Arrays.asList(A, B))), "and owes the owner its question");
        int saves = disk.saves;
        holds.ended(A, true);
        check(disk.saves == saves, "a second ending changes nothing");
        check(!holds.held(null) && holds.retries(null, false), "no profile: nothing held");
    }

    /** What was saved is what a new process holds: the hold and the owed question survive a restart. */
    private static void freshProcess() {
        Disk disk = new Disk();
        disk.load().ended(A, true);
        ReleaseHolds restarted = disk.load();
        check(restarted.held(A), "a fresh process built from storage still holds the scooter");
        check(!restarted.retries(A, false), "and its automatic retry stays off");
        check(A.equals(restarted.owed(Collections.singletonList(A))), "and still owes the question");
        check(new ReleaseHolds(null, null, null).owed(Collections.singletonList(A)) == null, "nothing saved: nothing held");
        ReleaseHolds odd = new ReleaseHolds(new LinkedHashSet<>(Arrays.asList(A, "", null)), new LinkedHashSet<>(Arrays.asList(A, B)), null);
        check(odd.held(A) && !odd.held("") && !odd.held(B), "blank ids are dropped on load");
        check(A.equals(odd.owed(Arrays.asList(A, B))) && !odd.held(B), "a question is owed only for a held profile");
    }

    /** Closing the sheet or picking an action answers the question; the hold stays, on disk too. */
    private static void answers() {
        Disk disk = new Disk();
        ReleaseHolds holds = disk.load();
        holds.ended(A, true);
        holds.asked(A);
        check(holds.held(A) && holds.owed(Collections.singletonList(A)) == null, "answered: still held, nothing owed");
        ReleaseHolds restarted = disk.load();
        check(restarted.held(A) && restarted.owed(Collections.singletonList(A)) == null, "after a restart: held, the answered question isn't asked again");
        int saves = disk.saves;
        restarted.asked(B); restarted.asked(null);
        check(disk.saves == saves, "answering a question nobody owes writes nothing");
    }

    /** Reconnect or the profile going ends the hold; a stale hold for a missing profile is dropped harmlessly. */
    private static void clearing() {
        Disk disk = new Disk();
        ReleaseHolds holds = disk.load();
        holds.ended(A, true); holds.ended(B, true);
        holds.end(A);
        check(!holds.held(A) && holds.held(B), "Reconnect or Forget ends that profile's hold only");
        check(!disk.load().held(A) && disk.load().held(B), "and the ending is on disk");
        check(holds.owed(Collections.singletonList(A)) == null, "a held profile that no longer exists isn't asked about");
        check(!holds.held(B) && !disk.load().held(B), "and its stale hold is dropped, on disk too");
        holds.ended(A, true);
        holds.prune(null);
        check(!holds.held(A), "no profiles at all: nothing held");
        int saves = disk.saves;
        holds.end(A); holds.end(null); holds.prune(Collections.singletonList(A));
        check(disk.saves == saves, "ending what isn't held writes nothing");
    }

    /** The connection code's calls into the store, from the source: nothing that connects skips it. */
    private static void connectionCode() {
        String ble = read("src/com/jjricks/volt/BleController.java");
        String load = body(ble, "private ReleaseHolds releaseHolds(){", "\n    }\n");
        check(load.contains("context.getSharedPreferences(ReleaseHolds.PREFERENCES,Context.MODE_PRIVATE)")
                && load.contains("getStringSet(ReleaseHolds.HELD,null)") && load.contains("getStringSet(ReleaseHolds.OWED,null)")
                && load.contains(".commit()"), "the store is loaded from its own preferences on first use and saved at once");
        check(!ble.contains("new HashSet<>();\n    private String releaseAsk"), "no in-memory hold beside it");
        check(body(ble, "public void connectLast(){", "\n").contains("if(heldBack(p))return;"), "connectLast (auto-connect, Bluetooth on, recordings, charge reconnects) asks the hold");
        String connect = body(ble, "private void connect(ScooterProfiles.Profile p,String mac){", "\n    }\n");
        check(connect.indexOf("if(heldBack(p))return;") > 0 && connect.indexOf("if(heldBack(p))return;") < connect.indexOf("close(State.CONNECTING)"), "every connection asks the hold before it starts");
        check(body(ble, "public void connectDiscovered(ScanResult result){", "\n    /**").contains("if(heldBack(p))return;"), "a scan pick asks the hold");
        check(body(ble, "private boolean heldBack(ScooterProfiles.Profile p){", "\n").contains("releaseHeld(p.id)"), "heldBack reads the store");
        String close = body(ble, "private void close(State next){", "queue.clear();");
        check(close.contains("if(profile!=null)releaseHolds().ended(profile.id,releaseSent(mutation));"), "a link ending with a sent release holds its scooter");
        String timeout = body(ble, "private void releaseTimeout(){", "\n    }\n");
        check(timeout.indexOf("releaseHolds().ended(profile.id,releaseSent(m));") > 0 && timeout.indexOf("releaseHolds().ended(") < timeout.indexOf("clearMutation();"), "the confirmation timeout holds before the release is cleared");
        String failure = body(ble, "private void failed(String text,boolean retry,ConnectionEventLog.Reason reason,int code){", "\n    private void notice(");
        check(failure.contains("boolean releaseHold=!releaseHolds().retries(profile==null?null:profile.id,releaseSent(mutation));")
                && failure.contains("if(releaseHold)reconnect=false;") && failure.contains("&&!releaseHold;recordDiagnostic"), "failed() retries only when the store allows it");
        String simulation = body(ble, "public void setSimulation(boolean enabled){", "clearMutation();");
        check(simulation.contains("releaseHolds().ended(profile.id,releaseSent(mutation));"), "simulation interrupting a sent release holds it too");
        String service = read("src/com/jjricks/volt/ScooterService.java");
        check(body(service, "private void forgetProfile(String id,boolean current){", "\n    }\n").contains("ble.endReleaseHold(id);"), "Forget, and a confirmed release, end the hold");
        String registry = read("src/com/jjricks/volt/BackupRegistry.java");
        check(body(registry, "static final String[] PRIVATE_NAMESPACES", ";").contains("\"" + ReleaseHolds.PREFERENCES + "\""), "the namespace is private: no backup reads, clears or restores it");
        check(!body(read("src/com/jjricks/volt/AppDataArchive.java"), "static final String[] PREFERENCES", ";").contains(ReleaseHolds.PREFERENCES), "and no backup lists it");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        String sheet = body(main, "private void showReleaseHold(String id){", "\n    }\n");
        check(sheet.contains("dialog.setOnCancelListener(d->ble.releaseAsked(id));") && !sheet.contains("scooterService.ble.releaseAsked(id);\n        final"),
                "the question is answered only by the owner, never by showing the sheet");
    }

    private static String body(String text, String start, String end) {
        int from = text.indexOf(start);
        check(from >= 0, "found: " + start);
        int to = text.indexOf(end, from + start.length());
        check(to > from, "end found after: " + start);
        return text.substring(from, to);
    }
    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError("cannot read " + path, e); }
    }
    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
