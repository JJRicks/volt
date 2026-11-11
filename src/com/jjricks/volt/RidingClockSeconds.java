package com.jjricks.volt;

import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;

/**
 * Riding mode's clock with seconds (a tester's request: catching a bus or train, they watch how close the
 * next minute is). A switch in the tune menu, off by default and saved as an app preference. While it's on,
 * every Clock reading riding mode draws shows seconds, and a tick redraws those readings just after each
 * second turns. The tick runs only while the switch is on, the frame on screen holds a clock, and riding
 * mode is open and resumed: nothing else ever posts it. Each tick reads the wall clock afresh and waits for
 * the next whole second from it, so a clock or time zone change shows at the next tick and never drifts.
 * Pure Java, so a JVM test drives the rules.
 */
final class RidingClockSeconds {
    /** The switch (app_settings, Boolean). Absent, as in a 1.13.0 backup, means off. */
    static final String KEY = "riding_clock_seconds";
    /** A tick lands this long after the second turns, so the wall clock it reads has surely moved on. */
    static final long LATE_MS = 15;
    /**
     * A cell keeps the clock's AM/PM while the whole time fits at this size or more (dp), or at its spot's own
     * largest size; below both the marker goes first, so the digits keep their size and the seconds stay.
     */
    static final float MARKER_MIN_DP = 32;

    private RidingClockSeconds() {}

    /** The stored switch: only a stored true is on. Absent, null or any other value reads as off. */
    static boolean on(Object stored) { return Boolean.TRUE.equals(stored); }

    /** From wallMs to just after the next whole second: between LATE_MS and 1000 + LATE_MS, before 1970 too. */
    static long delayMs(long wallMs) { return 1000 - Math.floorMod(wallMs, 1000L) + LATE_MS; }

    /**
     * Whether a cell draws the clock with its AM/PM: yes while the full time fits at its spot's largest size
     * (fullPx, as the cell would draw it, equals maxPx) or at minPx or more. Otherwise the marker goes, as it
     * does in a chip on the row's tightest rung.
     */
    static boolean keepsMarker(float fullPx, float maxPx, float minPx) {
        return fullPx >= Math.min(maxPx, minPx);
    }

    /**
     * A formatted time without one of its fields (the AM/PM marker) and the spaces that set it apart, read
     * from the formatter's own field positions, never from the text. Null when the field isn't there (a
     * 24-hour clock), so there's nothing shorter to show.
     */
    static String without(AttributedCharacterIterator formatted, AttributedCharacterIterator.Attribute field) {
        StringBuilder out = new StringBuilder();
        boolean dropped = false;
        for (char c = formatted.first(); c != CharacterIterator.DONE; c = formatted.next()) {
            if (formatted.getAttribute(field) != null) { dropped = true; continue; }
            out.append(c);
        }
        if (!dropped) return null;
        // The space that stood beside the marker: at either end, or doubled where the marker sat between two.
        int start = 0, end = out.length();
        while (start < end && space(out.charAt(start))) start++;
        while (end > start && space(out.charAt(end - 1))) end--;
        StringBuilder trimmed = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = out.charAt(i);
            if (space(c) && space(out.charAt(i - 1))) continue;
            trimmed.append(c);
        }
        return trimmed.length() == 0 ? null : trimmed.toString();
    }

    private static boolean space(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

    /** A timer the tick posts itself on: the screen's main-thread handler, or a test's fake. */
    interface Timer {
        void post(long delayMs);
        void cancel();
    }

    /**
     * One riding screen's tick. What the screen shows is fixed when it's built (a menu change, a new frame or
     * layout rebuilds the screen, closing this one), so only the activity's pause and resume and the screen's
     * close move it. At most one tick is ever waiting.
     */
    static final class Tick {
        private final boolean wanted;
        private final Timer timer;
        private boolean resumed, closed, posted;

        /** secondsOn: the switch; clockShown: the frame as built holds a clock; resumed: the activity is resumed now. */
        Tick(boolean secondsOn, boolean clockShown, boolean resumed, Timer timer) {
            this.wanted = secondsOn && clockShown;
            this.resumed = resumed;
            this.timer = timer;
        }

        /** Whether a tick should be waiting now. */
        boolean live() { return wanted && resumed && !closed; }
        /** Whether one is waiting. */
        boolean posted() { return posted; }

        /** The activity resumed or paused. */
        void resumed(boolean resumed, long wallMs) {
            this.resumed = resumed;
            sync(wallMs);
        }

        /** The screen closed (leaving riding mode, a rebuild, the activity going): the tick never comes back. */
        void close() {
            closed = true;
            sync(0);
        }

        /** The waiting tick ran: true when the clock should redraw now, with the next one already waiting. */
        boolean fired(long wallMs) {
            posted = false;
            if (!live()) return false;
            sync(wallMs);
            return true;
        }

        /** Starts or stops the tick to match; every refresh calls it, so a missed resume still starts it. */
        void sync(long wallMs) {
            if (live()) {
                if (posted) return;
                posted = true;
                timer.post(delayMs(wallMs));
            } else if (posted) {
                posted = false;
                timer.cancel();
            }
        }
    }
}
