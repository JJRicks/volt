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

        System.out.println("RecorderTapTest: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
