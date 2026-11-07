package com.jjricks.volt.tests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Rides' first-open timing for the hardware check's navigation step (ParkedValidation), kept free of Android so
 * the JVM tests can check it. The draw listener calls {@link #onDraw} on the main thread, at draw time, with whether
 * the history rows are built for that frame. The first such frame is the rows frame, so a frame drawn before any
 * other thread looks is still counted, and a later redraw never replaces it. Any thread may read the results:
 * the fields are volatile, and {@link #awaitRows} returns after the rows frame is published.
 */
public final class RidesOpenTiming {
    private final long tap;
    private volatile long firstFrame = -1, rowsFrame = -1;
    private final CountDownLatch rowsDrawn = new CountDownLatch(1);

    /** {@code tap}: when the tab tap started, on the same clock as the draws. */
    public RidesOpenTiming(long tap) { this.tap = tap; }

    /** Main thread only, from the draw listener: a frame drawn at {@code now}, with the history rows built or not. */
    public void onDraw(long now, boolean rowsReady) {
        if (now < tap) return;
        if (firstFrame < 0) firstFrame = now;
        if (rowsReady && rowsFrame < 0) { rowsFrame = now; rowsDrawn.countDown(); }
    }

    /** True once the rows frame has been drawn, waiting at most {@code timeoutMs}. */
    public boolean awaitRows(long timeoutMs) throws InterruptedException { return rowsDrawn.await(timeoutMs, TimeUnit.MILLISECONDS); }

    /** Milliseconds from the tap to the first frame, or null if none was drawn. */
    public Long tapToFirstFrame() { long frame = firstFrame; return frame < 0 ? null : frame - tap; }

    /** Milliseconds from the tap to the first frame with the history rows, or null if none was drawn. */
    public Long tapToRows() { long frame = rowsFrame; return frame < 0 ? null : frame - tap; }
}
