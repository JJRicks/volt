package com.jjricks.volt;

public final class RecorderTapTest {
    private static int checks;
    public static void main(String[] args) {
        int none = RecorderTap.NONE, start = RecorderTap.START, connect = RecorderTap.CONNECT, stop = RecorderTap.STOP;

        // The red disc keeps all three of its jobs.
        require(RecorderTap.lead(true, true) == stop, "The disc stops a recording ride");
        require(RecorderTap.lead(false, true) == start, "The disc starts a ride when connected");
        require(RecorderTap.lead(false, false) == connect, "The grey disc starts a connection");

        // Anywhere else on the card starts the same way the disc does.
        require(RecorderTap.card(false, true, false, true) == start, "A card tap starts a ride when connected");
        require(RecorderTap.card(false, true, false, false) == connect, "A card tap while disconnected connects, like the grey disc");

        // ...but never stops, never acts in edit mode, and respects the disc's disabled state.
        for (boolean ready : new boolean[] {true, false}) {
            for (boolean enabled : new boolean[] {true, false}) {
                require(RecorderTap.card(false, enabled, true, ready) == none, "A card tap never stops a recording ride");
                require(RecorderTap.card(true, enabled, false, ready) == none, "Edit mode never starts a ride");
                require(RecorderTap.card(true, enabled, true, ready) != stop, "Edit mode never stops a ride");
            }
            require(RecorderTap.card(false, false, false, ready) == none, "Saving or connecting: the disabled disc's card does nothing");
        }

        System.out.println("RecorderTapTest: " + checks + " checks passed");
    }
    private static void require(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
