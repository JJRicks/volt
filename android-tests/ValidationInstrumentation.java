package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * What every emulator validation shares: a waitForIdleSync with a time limit, and a hold on the app's never-ending
 * animations.
 *
 * <p><b>Why.</b> Android's own waitForIdleSync waits, with no time limit, for a moment when the app's main thread has
 * nothing left to do. Volt's charging view animates without end (the glow and the cable pulses), and on an emulator
 * that draws in software each frame takes longer than the screen's refresh, so the next frame is always already
 * waiting: the main thread never runs out of work, and a validation step that waits for that moment stalls for
 * minutes with the emulator otherwise idle.
 *
 * <p><b>The hold.</b> The constructor sets the app's EndlessMotion hold (by reflection; the app has no other way in),
 * so the glow, the cable pulses, spinners and the other endless loops show the still look they use with animations
 * off. A validation that checks motion calls {@link #runEndlessMotion()} in its onCreate and sees them run as on a
 * phone. Against an app without the hold (an older build) it logs that and carries on.
 *
 * <p><b>Short rides.</b> The constructor also sets RideStore's short-ride hold (by reflection, the same way), so the
 * service keeps rides under 20 s or 0.01 mi: most validations record a ride of a few seconds and read it back. A
 * validation that checks the keep rule calls {@link #discardShortRides()}.
 *
 * <p><b>The bounded wait.</b> {@link #waitForIdleSync()} still waits for idle, and returns at once when idle comes,
 * exactly as Android's does. If the main thread is still busy after {@link #IDLE_LIMIT_MS} but is running its messages
 * (everything queued before the call has run), the step goes on: the checks that follow have their own waits and
 * messages, and a screen that is still animating is reported once, in the log and in the step's result
 * ({@code idle_waits}), with the main thread's stack. If the main thread has not even run a message posted at the
 * call after {@link #BLOCKED_LIMIT_MS}, it is stuck, and the step fails saying so, with that stack.
 *
 * <p><b>Touch mode.</b> A check that sends key events runs through {@link #keysThenTouchMode}, which puts the display
 * back in touch mode afterwards, passed or failed; see there.
 */
public abstract class ValidationInstrumentation extends Instrumentation {
    private static final String TAG = "VoltValidation";
    /** How long waitForIdleSync waits for an idle main thread before the step goes on without it. */
    static final long IDLE_LIMIT_MS = 12_000;
    /** How long the main thread may take to run one posted message before the step fails as stuck. */
    static final long BLOCKED_LIMIT_MS = 60_000;

    private final Object idleLock = new Object();
    private int busyWaits;
    private String firstBusy;

    protected ValidationInstrumentation() {
        holdEndlessMotion(true);
        keepShortRides(true);
    }

    /**
     * For validations that check the short-ride rule (RideStore.kept): the service discards rides under 20 s or 0.01 mi
     * as on a phone. Every other validation keeps its seconds-long synthetic rides, which its checks read back.
     */
    protected final void discardShortRides() {
        keepShortRides(false);
    }

    private static void keepShortRides(boolean keep) {
        try {
            Field field = Class.forName("com.jjricks.volt.RideStore").getDeclaredField("keepsShortForValidation");
            field.setAccessible(true);
            field.setBoolean(null, keep);
        } catch (ReflectiveOperationException | LinkageError missing) {
            if (keep) Log.w(TAG, "This app build has no short-ride hold: its rides follow the app's keep rule (" + missing + ")");
        }
    }

    /** For validations that check motion or transitions: the app's endless animations run as on a phone. */
    protected final void runEndlessMotion() {
        holdEndlessMotion(false);
    }

    /** Whether the app's endless animations are held still now (false against an app without the hold). */
    protected final boolean endlessMotionHeld() {
        try {
            return holdField().getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError missing) {
            return false;
        }
    }

    private static void holdEndlessMotion(boolean hold) {
        try {
            holdField().setBoolean(null, hold);
        } catch (ReflectiveOperationException | LinkageError missing) {
            if (hold) Log.w(TAG, "This app build has no EndlessMotion hold: its endless animations run (" + missing + ")");
        }
    }

    private static Field holdField() throws ReflectiveOperationException {
        Field field = Class.forName("com.jjricks.volt.EndlessMotion").getDeclaredField("held");
        field.setAccessible(true);
        return field;
    }

    /**
     * Waits for the main thread to go idle, at most {@link #IDLE_LIMIT_MS}; see the class comment. Every validation's
     * settle() and its other idle waits come here.
     */
    @Override public void waitForIdleSync() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new RuntimeException("This method can not be called from the main application thread");
        long started = SystemClock.uptimeMillis();
        MessageQueue queue = Looper.getMainLooper().getQueue();
        CountDownLatch idle = new CountDownLatch(1), ran = new CountDownLatch(1);
        MessageQueue.IdleHandler idler = () -> { idle.countDown(); return false; };
        queue.addIdleHandler(idler);
        // As Android's own does: a message, so a sleeping queue wakes and then reports idle.
        new Handler(Looper.getMainLooper()).post(ran::countDown);
        try {
            if (idle.await(IDLE_LIMIT_MS, TimeUnit.MILLISECONDS)) return;
            queue.removeIdleHandler(idler);
            long left = BLOCKED_LIMIT_MS - (SystemClock.uptimeMillis() - started);
            if (!ran.await(Math.max(0, left), TimeUnit.MILLISECONDS) && idle.getCount() > 0) {
                throw new IllegalStateException("The app's main thread is stuck: it ran no message for "
                        + (SystemClock.uptimeMillis() - started) / 1000 + " s. Main thread: " + mainStack());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the app's main thread", interrupted);
        }
        if (idle.getCount() == 0) return;  // idle came just after the limit
        busy(SystemClock.uptimeMillis() - started);
    }

    /** The main thread ran its messages but never went idle: note it (logged once per run) and go on. */
    private void busy(long waitedMs) {
        String caller = caller(), stack = null;
        synchronized (idleLock) {
            busyWaits++;
            if (firstBusy == null) {
                firstBusy = caller;
                stack = mainStack();
            }
        }
        if (stack != null) {
            Log.w(TAG, "The main thread never went idle in " + waitedMs + " ms (something on screen draws every frame;"
                    + " endless motion " + (endlessMotionHeld() ? "held" : "running") + "); going on. Waiting at "
                    + caller + ". Main thread: " + stack);
        }
    }

    /** A check that sends key events, for {@link #keysThenTouchMode}. */
    interface KeyCheck<E extends Exception> { void run() throws E; }

    /** How long a window may take to hear that the display is back in touch mode. */
    static final long TOUCH_MODE_LIMIT_MS = 10_000;

    /**
     * Runs a check that sends key events, then puts the display back in touch mode, whether the check passed or not.
     *
     * <p>A D-pad, Tab or Enter key, or a typed character (Instrumentation's sendKeyDownUpSync and sendStringSync are a
     * hardware keyboard's keys), takes the display out of touch mode until a real touch. That is the device's state,
     * not the app's: it outlives the app's reinstall between sweep steps, and out of it a focusable view that becomes
     * visible while nothing has focus takes focus (ViewRootImpl.focusableViewAvailable), so a later step's focus checks
     * failed (#606: the backups step's typed passwords, then reveal-motion-find-clean). Instrumentation.setInTouchMode
     * sets it for the display, as a tap does; it stays set after the step's process ends (checked with dumpsys window's
     * mInTouchMode after each step). When the check passed, the window (window, read afterwards) must then report touch
     * mode within {@link #TOUCH_MODE_LIMIT_MS}; when it failed, touch mode is still set and the failure is what's thrown.
     */
    static <E extends Exception> void keysThenTouchMode(Instrumentation runner, Supplier<View> window, KeyCheck<E> check) throws E {
        boolean passed = false;
        try {
            check.run();
            passed = true;
        } finally {
            runner.setInTouchMode(true);
            if (passed) {
                long end = SystemClock.uptimeMillis() + TOUCH_MODE_LIMIT_MS;
                AtomicBoolean back = new AtomicBoolean();
                while (true) {
                    runner.runOnMainSync(() -> {
                        try { View view = window.get(); back.set(view != null && view.isInTouchMode()); }
                        catch (RuntimeException gone) { back.set(false); }  // no window to ask yet: ask again
                    });
                    if (back.get()) break;
                    if (SystemClock.uptimeMillis() > end)
                        throw new IllegalStateException("The window did not go back to touch mode after the key checks");
                    SystemClock.sleep(50);
                }
                runner.waitForIdleSync();
            }
        }
    }

    /** {@link #keysThenTouchMode(Instrumentation, Supplier, KeyCheck)} for this validation. */
    protected final <E extends Exception> void keysThenTouchMode(Supplier<View> window, KeyCheck<E> check) throws E {
        keysThenTouchMode(this, window, check);
    }

    /** The step's result carries the count of idle waits that ran out, when there were any. */
    @Override public void finish(int resultCode, Bundle results) {
        Bundle out = results == null ? new Bundle() : results;
        synchronized (idleLock) {
            if (busyWaits > 0) {
                out.putString("idle_waits", busyWaits + " idle wait(s) went on after " + IDLE_LIMIT_MS / 1000
                        + " s with the main thread still busy; the first at " + firstBusy);
            }
        }
        super.finish(resultCode, out);
    }

    /** The first frame outside this class and Instrumentation: the validation line that waited. */
    private static String caller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String type = frame.getClassName();
            if (!type.equals(ValidationInstrumentation.class.getName()) && !type.equals(Instrumentation.class.getName()))
                return frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName()
                        + ":" + frame.getLineNumber();
        }
        return "unknown";
    }

    private static String mainStack() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : Looper.getMainLooper().getThread().getStackTrace()) {
            if (out.length() > 1500) { out.append(" …"); break; }
            if (out.length() > 0) out.append(" | ");
            out.append(frame);
        }
        return out.toString();
    }
}
