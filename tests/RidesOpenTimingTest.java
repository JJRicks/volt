package com.jjricks.volt;

import com.jjricks.volt.tests.RidesOpenTiming;

/** The hardware check's Rides first-open timing: the rows frame is decided at draw time, never by a later poll. */
public final class RidesOpenTimingTest {
    private static int checks;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }

    public static void main(String[] args) throws Exception {
        // Codex's case: the rows are built and drawn before the waiting thread runs again; that frame still counts.
        RidesOpenTiming warm = new RidesOpenTiming(1000);
        warm.onDraw(1012, true);
        check(warm.awaitRows(0), "A rows frame drawn before the wait is seen at once");
        check(Long.valueOf(12).equals(warm.tapToRows()) && Long.valueOf(12).equals(warm.tapToFirstFrame()), "Warm open: first frame is the rows frame");
        // A later, unrelated redraw does not replace the first rows frame.
        warm.onDraw(1900, true);
        check(Long.valueOf(12).equals(warm.tapToRows()), "A later redraw keeps the first rows frame");

        // Cold open: skeleton frames first, then the rows.
        RidesOpenTiming cold = new RidesOpenTiming(5000);
        cold.onDraw(4990, true);
        check(cold.tapToFirstFrame() == null && cold.tapToRows() == null, "A frame before the tap is ignored");
        cold.onDraw(5030, false); cold.onDraw(5046, false);
        check(!cold.awaitRows(5), "Skeleton frames are not the rows frame");
        check(Long.valueOf(30).equals(cold.tapToFirstFrame()) && cold.tapToRows() == null, "Cold open: first frame without rows");
        cold.onDraw(9400, true);
        check(cold.awaitRows(0) && Long.valueOf(4400).equals(cold.tapToRows()), "Cold open: rows frame recorded");

        // No rows within the wait: null, never a later frame.
        RidesOpenTiming never = new RidesOpenTiming(0);
        never.onDraw(10, false);
        check(!never.awaitRows(5) && never.tapToRows() == null, "No rows frame stays null");

        // The draw is published to another thread: a "main" thread draws while this thread is not looking.
        RidesOpenTiming crossed = new RidesOpenTiming(100);
        Thread main = new Thread(() -> { crossed.onDraw(140, false); crossed.onDraw(171, true); crossed.onDraw(250, true); });
        main.start();
        check(crossed.awaitRows(5000), "Rows frame from the main thread is seen");
        main.join();
        check(Long.valueOf(71).equals(crossed.tapToRows()) && Long.valueOf(40).equals(crossed.tapToFirstFrame()), "Cross-thread values are the drawn frames");

        System.out.println("RidesOpenTimingTest: " + checks + " checks passed");
    }
}
