package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Charge done by vs switching scooters. While the measuring window runs, Use on another scooter (and Find at a new
 * address, setup, an owner import) disconnected the measured scooter, and its next update ended the window without
 * arming. ScooterService.scooterChangeUnavailable now refuses with the measuring reason, as dataMaintenanceUnavailable
 * and startScheduleTest already do. ScooterService is an Android class, so window() checks the rule the guard leans
 * on (arming implies active, so one active() check covers the arming writes) and service() reads the guard's source.
 */
public final class ScooterChangeMeasureTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;

    public static void main(String[] args) {
        window();
        service();
        System.out.println("ScooterChangeMeasureTest: " + checks + " checks passed (a measuring window refuses a scooter change)");
    }

    /** active() is true from start to finish, the arming writes included; arming() is never true without it. */
    private static void window() {
        ChargeMeasureWindow w = new ChargeMeasureWindow();
        check(!w.active() && !w.arming(), "idle: neither");
        w.arm();
        check(!w.active() && !w.arming(), "arm() with no window does nothing");
        w.start("profile-a", 7 * 60, MONO, WALL, WALL + 60 * 60_000L);
        check(w.active() && !w.arming(), "measuring: active");
        w.arm();
        check(w.active() && w.arming(), "arming: still active");
        w.finish();
        check(!w.active() && !w.arming(), "finished: neither");
    }

    private static void service() {
        String service = read("src/com/jjricks/volt/ScooterService.java");
        String guard = body(service, "public String scooterChangeUnavailable() {", "\n    }\n");
        String measuring = "if (chargeMeasure.active()) return T.serviceWaitMeasuring();";
        check(count(guard, measuring) == 1, "the guard refuses a measuring window with the measuring reason");
        check(!guard.contains("arming()"), "one active() check, which covers arming");
        // The order: maintenance, then a recording (the owner's own to stop), then the measuring window, before the brief waits.
        String[] order = {
                "return T.serviceWaitBackup();",
                "return T.serviceStopRecordingFirst();",
                measuring,
                "return T.serviceWaitRideSaving();",
                "return T.serviceWaitScooterChange();",
                "return T.serviceWaitScheduleTest();",
                "return null;"};
        for (int i = 1; i < order.length; i++)
            check(guard.indexOf(order[i - 1]) >= 0 && guard.indexOf(order[i - 1]) < guard.indexOf(order[i]), order[i - 1] + " before " + order[i]);
        // The neighbouring guards keep theirs.
        check(body(service, "public String dataMaintenanceUnavailable() {", "\n    }\n").contains(measuring), "backup still refuses a measuring window");
        check(body(service, "public String startScheduleTest(){", "\n    }\n").contains("if(chargeMeasure.active())return T.serviceWaitMeasuring();"),
                "the schedule test still refuses a measuring window");
        // Use and Find at a new address reach the guard before anything disconnects (FindNearbyTest pins the rest).
        String more = read("src/com/jjricks/volt/MoreScreen.java");
        check(more.contains("String unavailable = s == null ? T.moreServiceStarting() : s.scooterChangeUnavailable();"), "My scooters' Use, add and forget");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        String find = body(main, "private void findNearby(boolean home){", "\n    }\n");
        check(find.indexOf("scooterService.scooterChangeUnavailable()") >= 0 && find.indexOf("scooterService.scooterChangeUnavailable()") < find.lastIndexOf("showScanner(true,true);"),
                "Home's Find refuses first");
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
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError("cannot read " + path, e); }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
