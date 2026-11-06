package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** The Home Ride recorder card's tap targets and its smallest size (RecorderTap). */
public final class RecorderTapTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        int none = RecorderTap.NONE, start = RecorderTap.START, connect = RecorderTap.CONNECT, stop = RecorderTap.STOP;

        // The red disc keeps all three of its jobs.
        require(RecorderTap.lead(true, true, false) == stop, "The disc stops a recording ride");
        require(RecorderTap.lead(true, false, false) == stop, "The disc stops a recording ride while the link is down");
        require(RecorderTap.lead(false, true, false) == start, "The disc starts a ride when connected");
        require(RecorderTap.lead(false, false, false) == connect, "The grey disc starts a connection");

        // "Starting…": a second tap never starts twice, but a ride that is already recording still stops.
        require(RecorderTap.lead(false, true, true) == none, "A second disc tap while starting does nothing");
        require(RecorderTap.lead(false, false, true) == none, "A second disc tap while starting never connects");
        require(RecorderTap.lead(true, true, true) == stop, "Once recording, the disc stops even if starting was not cleared yet");

        // Anywhere else on the card starts the same way the disc does.
        require(RecorderTap.card(false, true, false, true, false) == start, "A card tap starts a ride when connected");
        require(RecorderTap.card(false, true, false, false, false) == connect, "A card tap while disconnected connects, like the grey disc");
        require(RecorderTap.card(false, false, false, false, false) == none, "Disconnected and connecting (disc disabled): a card tap does nothing");

        // ...but never stops, never acts in edit mode or while starting, and respects the disc's disabled state.
        for (boolean ready : new boolean[] {true, false}) {
            for (boolean enabled : new boolean[] {true, false}) {
                for (boolean starting : new boolean[] {true, false}) {
                    require(RecorderTap.card(false, enabled, true, ready, starting) == none, "A card tap never stops a recording ride");
                    require(RecorderTap.card(true, enabled, false, ready, starting) == none, "Edit mode never starts a ride");
                    require(RecorderTap.card(true, enabled, true, ready, starting) == none, "Edit mode never stops a ride");
                    require(RecorderTap.card(false, enabled, false, ready, true) == none, "A card tap while starting does nothing");
                    require(RecorderTap.card(false, enabled, true, ready, starting) != stop
                            && RecorderTap.card(true, enabled, true, ready, starting) != stop, "No card tap ever stops a ride");
                }
                require(RecorderTap.card(false, false, false, ready, false) == none, "Saving or connecting: the disabled disc's card does nothing");
            }
        }

        require(RecorderTap.TYPE.equals("ride"), "The recorder's widget type is ride");

        // One row at half a phone's width (2 of 5 columns, about 144 dp); the narrowest cards take two.
        require(RecorderTap.minimumRows(144) == 1, "A half-width recorder fits one row");
        require(RecorderTap.minimumRows(192) == 1, "A 192 dp recorder fits one row (was three below 192 dp)");
        require(RecorderTap.minimumRows(312) == 1, "A wide recorder fits one row");
        require(RecorderTap.minimumRows(RecorderTap.ONE_ROW_MIN_WIDTH_DP) == 1, "The one-row threshold itself fits one row");
        require(RecorderTap.minimumRows(111.9f) == 2, "Just under the threshold takes two rows");
        require(RecorderTap.minimumRows(100) == 2, "A 100 dp recorder takes two rows");
        require(RecorderTap.minimumRows(67) == 2, "One of five columns takes two rows (was three)");

        // The rule is only a floor: a recorder the owner sized taller stays that tall (no migration).
        String config = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardConfig.java")), StandardCharsets.UTF_8);
        require(config.contains("if (RecorderTap.TYPE.equals(type))\n                return RecorderTap.minimumRows(widthDp);")
                && config.indexOf("RecorderTap.minimumRows(widthDp)") < config.indexOf("widthDp < 192) return 3;"),
                "DashboardConfig's width-aware minimum height for the recorder comes from RecorderTap");
        require(config.contains("cell.h = Math.max(cell.h, widgets.get(i).minimumHeight("),
                "A saved recorder height above the minimum is kept");

        // An accepted tap is never dropped and never starts twice (DashboardScreen: the start's
        // frame and close() both take the same PendingStart; a hand-off runs startRide once).
        Sim sim = new Sim();
        sim.tap(); sim.close(true); sim.frame();
        require(sim.starts == 1 && sim.reports == 0, "Tap, then a tab switch before the frame: the start is handed on and runs once");
        sim = new Sim();
        sim.tap(); sim.close(true); sim.frame(); sim.reopen(); sim.frame();
        require(sim.starts == 1 && sim.reports == 0, "Tap, then a rebuild before the frame: one start, the new Home has nothing pending");
        sim = new Sim();
        sim.tap(); sim.close(false); sim.frame();
        require(sim.starts == 0 && sim.reports == 1, "Tap, then the window goes away before the frame: Volt says it did not start");
        sim = new Sim();
        sim.tap(); sim.frame(); sim.close(true);
        require(sim.starts == 1 && sim.reports == 0, "Tap, frame, then close: no second start at close");
        sim = new Sim();
        sim.tap(); sim.frame(); sim.frame();
        require(sim.starts == 1, "A repeated frame callback never starts twice");
        sim = new Sim();
        sim.close(true); sim.close(false);
        require(sim.starts == 0 && sim.reports == 0, "Closing with nothing tapped does nothing");
        require(RecorderTap.onClose(false, true) == RecorderTap.CLOSE_NOTHING && RecorderTap.onClose(false, false) == RecorderTap.CLOSE_NOTHING,
                "Nothing pending: close does nothing");
        require(RecorderTap.onClose(true, true) == RecorderTap.CLOSE_HAND_OFF, "Pending, app staying: hand the start on");
        require(RecorderTap.onClose(true, false) == RecorderTap.CLOSE_REPORT, "Pending, app going away: report it");

        // DashboardScreen wires it that way: the frame takes the start and close() finishes it first.
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/DashboardScreen.java")), StandardCharsets.UTF_8);
        require(screen.contains("if (closed || !recorderStart.take()) return;"), "The frame callback takes the pending start");
        require(screen.contains("finishPendingStart();\n        closed = true;"), "close() finishes a pending start before marking Home closed");
        require(screen.contains("else start.startRide();"), "A handed-on start runs the same startRide path");

        System.out.println("RecorderTapTest: " + checks + " checks passed");
    }

    /** The DashboardScreen start flow, minus Android: one PendingStart shared by the frame and close(). */
    private static final class Sim {
        RecorderTap.PendingStart pending = new RecorderTap.PendingStart();
        boolean closed;
        int starts, reports;
        void tap() { pending.arm(); }
        void frame() { if (!closed && pending.take()) starts++; }
        void close(boolean staying) {
            int action = RecorderTap.onClose(pending.pending(), staying);
            if (action != RecorderTap.CLOSE_NOTHING && pending.take()) {
                if (action == RecorderTap.CLOSE_HAND_OFF) starts++; else reports++;
            }
            closed = true;
        }
        void reopen() { pending = new RecorderTap.PendingStart(); closed = false; }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
