package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class OwnerCaptureEngineTest {
    private static int checks;
    private static final String TOKEN = repeat('a', 64), NOW = "1800000000.123456789";
    public static void main(String[] args) throws Exception {
        parsing(); transactions(); android11(); processBounds();
        System.out.println("OwnerCaptureEngineTest: " + checks + " checks passed");
    }
    private static void parsing() {
        check(OwnerCaptureEngine.validToken(TOKEN));
        check(!OwnerCaptureEngine.validToken("../bad"));
        check(!OwnerCaptureEngine.validToken(repeat('A', 64)));
        check(OwnerCaptureEngine.validProperty("  INFO  "));
        check(!OwnerCaptureEngine.validProperty("INFO\n"));
        equal("  INFO  ", OwnerCaptureEngine.propertyOutput("  INFO  \n"));
        equal("", OwnerCaptureEngine.propertyOutput("\n"));
        reject(() -> OwnerCaptureEngine.propertyOutput("INFO"));
        reject(() -> OwnerCaptureEngine.propertyOutput("INFO\nDEBUG\n"));
        equal("10123", OwnerCaptureEngine.packageUid("package:com.navee.ucaret uid:10123\n"));
        reject(() -> OwnerCaptureEngine.packageUid("package:com.navee.ucaret uid:110123\n"));
        reject(() -> OwnerCaptureEngine.packageUid("package:com.navee.ucaret.evil uid:10123"));
        reject(() -> OwnerCaptureEngine.packageUid("package:com.navee.ucaret uid:2000"));
        equal(2, OwnerCaptureEngine.androidUsers("Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{10:Work:30}").length);
        reject(() -> OwnerCaptureEngine.androidUsers("Users:\nUserInfo{10:Work:30}"));
        reject(() -> OwnerCaptureEngine.androidUsers("Users:\nUserInfo{0:Owner:13}\nUserInfo{0:Owner:13}"));
        reject(() -> OwnerCaptureEngine.androidUsers("garbage"));
        check(OwnerCaptureEngine.hasNaveePackage("package:com.navee.ucaret uid:1012345\n"));
        check(!OwnerCaptureEngine.hasNaveePackage(""));
        check(!OwnerCaptureEngine.hasNaveePackage("package:com.navee.ucaret.fake uid:1012345"));
        reject(() -> OwnerCaptureEngine.hasNaveePackage("permission denied"));
        equal("com.navee.ucaret/.MainActivity", OwnerCaptureEngine.launcher("com.navee.ucaret/.MainActivity\n"));
        reject(() -> OwnerCaptureEngine.launcher("evil.app/.MainActivity"));
        reject(() -> OwnerCaptureEngine.launcher("com.navee.ucaret/.MainActivity;id"));
        reject(() -> OwnerCaptureEngine.packageUid("package:com.navee.ucaret uid:10123\npackage:com.navee.ucaret uid:10124"));
        String good = line(NOW, packet("010203040506", 0));
        equal("010203040506", OwnerCaptureEngine.freshOwner(good, NOW).identity);
        check(OwnerCaptureEngine.freshOwner(line("1800000000.123456788", packet("010203040506", 0)), NOW) == null);
        check(OwnerCaptureEngine.freshOwner("Send authData: " + packet("010203040506", 0), NOW) == null);
        check(OwnerCaptureEngine.freshOwner(NOW + " D x: owner_identity: 010203040506", NOW) == null);
        check(OwnerCaptureEngine.freshOwner(good + " trailing text", NOW) == null);
        check(OwnerCaptureEngine.freshOwner(line(NOW, packet("010203040506", 0).replace("55AA", "54AA")), NOW) == null);
        check(OwnerCaptureEngine.freshOwner(line(NOW, "[" + packet("010203040506", 0) + "]"), NOW) != null);
        reject(() -> OwnerCaptureEngine.freshOwner(good + "\n" + line(NOW, packet("A1A2A3A4A5A6", 0)), NOW));
        reject(() -> OwnerCaptureEngine.freshOwner(good + "\n" + line(NOW, packet("010203040506", 1)), NOW));
        reject(() -> OwnerCaptureEngine.freshOwner(repeat('a', OwnerCaptureEngine.MAX_OUTPUT + 1), NOW));
    }
    private static void transactions() {
        Fake happy = new Fake();
        OwnerCaptureEngine.Result result = OwnerCaptureEngine.capture(happy, TOKEN);
        equal(OwnerCaptureEngine.OK, result.outcome); check(result.clean); check(result.owner != null);
        equal("  INFO  ", happy.property); check(!happy.lock); check(happy.events.indexOf("acquire") < happy.events.indexOf("getprop"));
        check(happy.events.indexOf("journal") < happy.events.indexOf("arm"));
        check(happy.events.indexOf("arm") < happy.events.indexOf("start"));
        check(happy.events.indexOf("restore") < happy.events.indexOf("release"));
        // Android 12+: --uid, asked of logcat once, and no process lookups.
        equal(1, Collections.frequency(happy.events, "check"));
        check(happy.commands.contains("/system/bin/logcat --uid=10123 -d -t 1"));
        check(happy.commands.contains("/system/bin/logcat -d -v epoch -t " + NOW + " --uid=10123 -e Send authData: -s com.navee.ucaret:D *:S"));
        for (String filter : happy.filters) equal("--uid=10123", filter);
        for (String failure : new String[] {"user", "uid", "users", "launcher", "acquire", "getprop", "clock", "initialize", "journal", "arm", "start", "log", "restore", "verify", "release"}) {
            Fake f = new Fake(); f.failure = failure;
            OwnerCaptureEngine.Result r = OwnerCaptureEngine.capture(f, TOKEN);
            check(r.owner == null);
            if (failure.equals("restore") || failure.equals("verify") || failure.equals("release")) check(!r.clean);
            if (failure.equals("journal") || failure.equals("initialize") || failure.equals("clock") || failure.equals("getprop")) check(!f.events.contains("start"));
        }
        Fake busy = new Fake(); busy.lock = true; busy.property = "OTHER";
        result = OwnerCaptureEngine.capture(busy, TOKEN);
        equal(OwnerCaptureEngine.BUSY, result.outcome); equal("OTHER", busy.property); check(!busy.events.contains("getprop"));
        Fake timeout = new Fake(); timeout.noPacket = true;
        result = OwnerCaptureEngine.capture(timeout, TOKEN);
        equal(OwnerCaptureEngine.TIMED_OUT, result.outcome); check(result.clean); check(result.owner == null);
        // Preparation and startup do not consume the advertised connect window.
        Fake slowStart = new Fake(); slowStart.noPacket = true; slowStart.preparationMillis = 70000;
        result = OwnerCaptureEngine.capture(slowStart, TOKEN);
        equal(OwnerCaptureEngine.TIMED_OUT, result.outcome);
        equal(160000L, slowStart.millis); equal(9, slowStart.logCount);
        // A candidate arriving on the last regular poll gets one confirmation.
        Fake last = new Fake(); last.packetAt = 9;
        result = OwnerCaptureEngine.capture(last, TOKEN);
        equal(OwnerCaptureEngine.OK, result.outcome); check(result.clean); check(result.owner != null);
        equal(10, last.logCount);
        Fake finalConflict = new Fake(); finalConflict.packetAt = 9; finalConflict.conflictAt = 10;
        result = OwnerCaptureEngine.capture(finalConflict, TOKEN);
        equal(OwnerCaptureEngine.CONFLICT, result.outcome); check(result.clean); check(result.owner == null);
        Fake noConfirmation = new Fake(); noConfirmation.packetAt = 9; noConfirmation.packetUntil = 9;
        result = OwnerCaptureEngine.capture(noConfirmation, TOKEN);
        equal(OwnerCaptureEngine.TIMED_OUT, result.outcome); equal(10, noConfirmation.logCount); check(result.owner == null);
        Fake cancel = new Fake(); cancel.cancelAfterLog = true;
        result = OwnerCaptureEngine.capture(cancel, TOKEN);
        equal(OwnerCaptureEngine.CANCELLED, result.outcome); check(result.clean); check(result.owner == null);
        Fake before = new Fake(); before.cancel = true;
        result = OwnerCaptureEngine.capture(before, TOKEN);
        equal(OwnerCaptureEngine.CANCELLED, result.outcome); check(!before.events.contains("arm")); check(!before.lock);
        Fake conflict = new Fake(); conflict.conflict = true;
        result = OwnerCaptureEngine.capture(conflict, TOKEN);
        equal(OwnerCaptureEngine.CONFLICT, result.outcome); check(result.clean); check(result.owner == null);
        Fake beforeToken = new Fake(); result = OwnerCaptureEngine.capture(beforeToken, "bad");
        check(beforeToken.events.isEmpty()); check(result.owner == null);
        Fake changedLock = new Fake(); changedLock.ownershipLost = true;
        result = OwnerCaptureEngine.capture(changedLock, TOKEN);
        check(!result.clean); check(result.owner == null); check(!changedLock.events.contains("restore"));
    }
    /** Android 11's logcat has no --uid: every row is read with the uid that wrote it, and only NAVEE's kept. */
    private static void android11() {
        String rowsRead = "/system/bin/logcat -d -v epoch -t " + NOW + " -v uid -e Send authData: -s com.navee.ucaret:D *:S";
        String own = "  1800000000.123456789 10123  4321  4321 D com.navee.ucaret: Send authData: x";
        equal("  1800000000.123456789 4321  4321 D com.navee.ucaret: Send authData: x\n", OwnerCaptureEngine.ownRows(own + "\r\n", "10123"));
        // Another uid, a uid sharing digits, shell, no uid column (a logcat ignoring -v uid), the divider.
        for (String other : new String[] {own.replace("10123", "10999"), own.replace("10123", "110123"), own.replace("10123", "shell"),
                own.replace(" 10123 ", " "), "--------- beginning of main", "10123  4321  4321 D com.navee.ucaret: Send authData: x"})
            equal("", OwnerCaptureEngine.ownRows(other, "10123"));
        // getpwuid names of five characters or fewer stand in for the number: uid 10005 prints as u0_a5.
        check(!OwnerCaptureEngine.ownRows(own.replace("10123", "u0_a5"), "10005").isEmpty());
        check(!OwnerCaptureEngine.ownRows(own.replace("10123", "10005"), "10005").isEmpty());
        equal("", OwnerCaptureEngine.ownRows(own.replace("10123", "u0_a5"), "10123"));
        reject(() -> OwnerCaptureEngine.ownRows(repeat('x', OwnerCaptureEngine.MAX_OUTPUT + 1), "10123"));
        // An impostor's checksum-valid rows under NAVEE's tag and pid, beside NAVEE's own, are never read.
        Fake rows = new Fake(); rows.noUid = true; rows.impostor = true;
        OwnerCaptureEngine.Result result = OwnerCaptureEngine.capture(rows, TOKEN);
        equal(OwnerCaptureEngine.OK, result.outcome); check(result.clean); equal("010203040506", result.owner.identity);
        equal(Arrays.asList("rows", "rows"), rows.filters); equal(1, Collections.frequency(rows.events, "check"));
        check(rows.commands.contains(rowsRead)); check(rows.events.indexOf("check") > rows.events.indexOf("start"));
        check(rows.events.indexOf("restore") < rows.events.indexOf("release"));
        // A failed check on a phone with --uid also reads rows.
        Fake checkFailed = new Fake(); checkFailed.failure = "check";
        result = OwnerCaptureEngine.capture(checkFailed, TOKEN);
        equal(OwnerCaptureEngine.OK, result.outcome); equal(Arrays.asList("rows", "rows"), checkFailed.filters);
        // A recycled pid: another uid wrote a valid frame as pid 123 and exited, and NAVEE, now pid 123, wrote
        // none. Nothing is captured, all window long.
        Fake reused = new Fake(); reused.noUid = true; reused.impostor = true; reused.noPacket = true;
        result = OwnerCaptureEngine.capture(reused, TOKEN);
        equal(OwnerCaptureEngine.TIMED_OUT, result.outcome); check(result.clean); check(result.owner == null); equal(9, reused.logCount);
        // Rows without a uid column are never trusted.
        Fake unlabeled = new Fake(); unlabeled.noUid = true; unlabeled.writer = null;
        result = OwnerCaptureEngine.capture(unlabeled, TOKEN);
        equal(OwnerCaptureEngine.TIMED_OUT, result.outcome); check(result.clean); check(result.owner == null);
        // NAVEE's own identities must still agree.
        Fake conflict = new Fake(); conflict.noUid = true; conflict.conflictAt = 2;
        result = OwnerCaptureEngine.capture(conflict, TOKEN);
        equal(OwnerCaptureEngine.CONFLICT, result.outcome); check(result.clean); check(result.owner == null);
        // A refused read is an error at once, in either mode, never "nothing found".
        for (boolean noUid : new boolean[] {true, false}) {
            Fake refused = new Fake(); refused.noUid = noUid; refused.refuseRead = true;
            result = OwnerCaptureEngine.capture(refused, TOKEN);
            equal(OwnerCaptureEngine.UNREADABLE, result.outcome); check(result.clean); check(result.owner == null); equal(1, refused.logCount);
        }
        // Cancelling during a refused read is still a cancellation.
        Fake cancelled = new Fake(); cancelled.refuseRead = true; cancelled.cancelAfterLog = true;
        equal(OwnerCaptureEngine.CANCELLED, OwnerCaptureEngine.capture(cancelled, TOKEN).outcome);
    }
    private static void processBounds() throws Exception {
        equal("ok", OwnerCaptureProcess.run("/bin/sh", "-c", "printf ok"));
        rejectChecked(() -> OwnerCaptureProcess.run("/bin/sh", "-c", "printf private-secret >&2; exit 1"));
        long before = System.nanoTime();
        rejectChecked(() -> OwnerCaptureProcess.runBounded(1, "/bin/sh", "-c", "sleep 5"));
        check((System.nanoTime() - before) / 1000000 < 3000);
        rejectChecked(() -> OwnerCaptureProcess.run("/bin/sh", "-c", "yes x | head -c 1048577"));
        rejectChecked(() -> OwnerCaptureProcess.run("/bin/sh", "-c", "yes x | head -c 1048577 >&2"));
        // A process ignoring TERM must still stop after the bounded grace period.
        before = System.nanoTime();
        rejectChecked(() -> OwnerCaptureProcess.runBounded(1, "/bin/sh", "-c", "trap '' TERM; while :; do sleep .1; done"));
        long elapsedMs = (System.nanoTime() - before) / 1000000;
        check(elapsedMs >= OwnerCaptureProcess.TERMINATION_GRACE_SECONDS * 1000);
        check(elapsedMs < (OwnerCaptureProcess.TERMINATION_GRACE_SECONDS + 4) * 1000);
    }
    /** A phone double. noUid: Android 11's logcat, refusing --uid. Read with -v uid, rows carry writer (null:
     *  no uid column), and impostor adds another uid's valid frame, as NAVEE's pid, before NAVEE's own. */
    private static final class Fake implements OwnerCaptureEngine.Host {
        final List<String> events = new ArrayList<>(), filters = new ArrayList<>(), commands = new ArrayList<>();
        String property = "  INFO  ", original, failure = "", writer = "10123";
        boolean lock, noPacket, cancel, cancelAfterLog, conflict, ownershipLost, noUid, refuseRead, impostor;
        int logCount, packetAt = 1, packetUntil = Integer.MAX_VALUE, conflictAt = Integer.MAX_VALUE; long millis, preparationMillis;
        void event(String name) throws Exception { events.add(name); if (failure.equals(name)) { failure = ""; throw new Exception(); } }
        @Override public String command(String... args) throws Exception {
            commands.add(String.join(" ", args));
            if (args[0].equals("/system/bin/logcat") && !Arrays.asList(args).contains("epoch")) {
                event("check"); if (noUid) throw new Exception("Unknown option"); return "";
            }
            if (args[0].endsWith("am")) { event("user"); return "0\n"; }
            if (args[0].endsWith("cmd")) {
                if (args[1].equals("user")) { event("users"); return "Users:\n\tUserInfo{0:Owner:13} running\n"; }
                if (args[2].equals("resolve-activity")) { event("launcher"); return "com.navee.ucaret/.MainActivity\n"; }
                event("uid"); return "package:com.navee.ucaret uid:10123\n";
            }
            if (args[0].endsWith("getprop")) { event(events.contains("restore") ? "verify" : "getprop"); return property + "\n"; }
            if (args[0].endsWith("date")) { event("clock"); return NOW; }
            if (args[0].endsWith("logcat")) {
                boolean rows = Arrays.asList(args).contains("uid");
                event("log"); logCount++; filters.add(rows ? "rows" : args[6]);
                if (cancelAfterLog) cancel = true;
                if (refuseRead || (noUid && !rows)) throw new Exception();
                String before = rows && impostor ? written("10999", packet("0A0B0C0D0E0F", 0)) + "\n" : "";
                if (noPacket || logCount < packetAt || logCount > packetUntil) return before;
                String own = packet((conflict && logCount > 1) || logCount >= conflictAt ? "A1A2A3A4A5A6" : "010203040506", 0);
                return before + (rows ? written(writer, own) : line(NOW, own));
            }
            throw new AssertionError("Unexpected command");
        }
        @Override public void acquire(String token) throws Exception { if (lock) throw new OwnerCaptureEngine.CaptureBusy(); event("acquire"); lock = true; }
        @Override public void initialize(String previous, String started) throws Exception { original = previous; event("initialize"); }
        @Override public void arm(String token) throws Exception { event("arm"); }
        @Override public void startLogging(String token, String component) throws Exception { property = "VERBOSE"; event("start"); millis += preparationMillis; }
        @Override public void restoreLogging(String token) throws Exception { event("restore"); property = original; }
        @Override public boolean owns(String token, String previous) { return lock && !ownershipLost; }
        @Override public void release(String token, String previous) throws Exception { event("release"); lock = false; }
        @Override public boolean journal(String token, String previous) throws Exception { event("journal"); return true; }
        @Override public void progress(int phase) { }
        @Override public boolean cancelled() { return cancel; }
        @Override public long elapsed() { return millis; }
        @Override public void pause() { millis += 10000; }
    }
    private static String line(String time, String packet) { return time + " 123 123 D com.navee.ucaret: Send authData: " + packet; }
    /** A row as -v epoch -v uid prints it, written by writer (null: without the uid column). */
    private static String written(String writer, String packet) {
        return "  " + NOW + (writer == null ? "" : " " + writer) + "   123   123 D com.navee.ucaret: Send authData: " + packet;
    }
    private static String packet(String identity, int role) {
        byte[] p = Protocol.fromHex("55AA0030090000" + identity + "0000FEFD"); p[6] = (byte)role;
        int sum = 0; for (int i = 0; i < 14; i++) sum += p[i] & 255; p[14] = (byte)sum;
        return Protocol.hex(p).toUpperCase(java.util.Locale.ROOT);
    }
    private static String repeat(char c, int count) { StringBuilder s = new StringBuilder(count); while (count-- > 0) s.append(c); return s.toString(); }
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static void equal(Object expected, Object actual) { check(expected.equals(actual)); }
    private static void reject(Runnable block) { boolean rejected = false; try { block.run(); } catch (IllegalArgumentException good) { rejected = true; } check(rejected); }
    interface Checked { void run() throws Exception; }
    private static void rejectChecked(Checked block) throws Exception { boolean rejected = false; try { block.run(); } catch (Exception good) { check(!good.toString().contains("private-secret")); rejected = true; } check(rejected); }
}
