package com.jjricks.volt;

/** The gauge spring: timing at 1, 2, 5 and 10 s intervals, no overshoot, no extrapolation. */
public final class GaugeSmootherTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        GaugeSmoother first = new GaugeSmoother();
        check(!first.started() && Double.isNaN(first.value(0)), "nothing to draw before the first reading");
        first.setTarget(12, 1000, 500);
        check(first.value(500) == 12 && first.value(9000) == 12 && first.velocity(600) == 0, "the first reading snaps, with no glide from zero");
        first.setTarget(Double.NaN, 1000, 600);
        check(first.target() == 12, "a non-finite reading is ignored");

        double previous;
        for (long interval : new long[]{1000, 2000, 5000, 10000}) {
            GaugeSmoother s = new GaugeSmoother();
            s.snap(0, 0);
            s.setTarget(100, interval, 0);
            check(s.value(0) == 0, "a step starts where the gauge was (" + interval + " ms)");
            double settle = s.value(Math.round(GaugeSmoother.SETTLE_FRACTION * interval));
            check(settle >= 94.9 && settle <= 100, "95 % of a step at 0.6 x the interval (" + interval + " ms): " + settle);
            check(s.value(interval) > 99.5, "the motion is done when the next reading lands (" + interval + " ms): " + s.value(interval));
            check(s.value(interval / 10) < 50, "and it is a glide, not a jump (" + interval + " ms)");
            previous = -1;
            for (long t = 0; t <= 3 * interval; t += interval / 50) {
                double v = s.value(t);
                check(v >= previous && v <= 100, "monotone with no overshoot (" + interval + " ms) at " + t);
                previous = v;
            }
            check(s.settled(3 * interval, 0.01), "settles");
        }

        // Retargets along the motion keep speed but never pass the latest reading.
        GaugeSmoother rising = new GaugeSmoother();
        rising.snap(0, 0);
        long now = 0;
        double target = 0, shown = 0;
        for (int i = 0; i < 40; i++) {
            target += 10;
            rising.setTarget(target, 1000, now);
            for (long t = now; t < now + 1000; t += 20) {
                double v = rising.value(t);
                check(v <= target + 1e-9 && v >= shown - 1e-9, "a rising reading never runs past the latest sample, at " + t);
                shown = v;
            }
            now += 1000;
        }
        // A fast retarget toward the gauge's motion: the carried speed is clamped at the target.
        GaugeSmoother fast = new GaugeSmoother();
        fast.snap(0, 0);
        fast.setTarget(100, 1000, 0);
        double moving = fast.value(150);
        fast.setTarget(moving + 0.5, 1000, 150);
        for (long t = 150; t < 2000; t += 5) check(fast.value(t) <= moving + 0.5 + 1e-9, "a carried speed stops at the new reading, at " + t);
        check(fast.value(2000) == moving + 0.5, "and holds it exactly");
        // A reversal starts from rest: it never continues the old way first.
        GaugeSmoother reverse = new GaugeSmoother();
        reverse.snap(0, 0);
        reverse.setTarget(100, 1000, 0);
        double at = reverse.value(200);
        reverse.setTarget(20, 1000, 200);
        previous = at;
        for (long t = 200; t < 3000; t += 10) {
            double v = reverse.value(t);
            check(v <= previous + 1e-9 && v >= 20 - 1e-9, "a reversal heads straight back with no overshoot, at " + t);
            previous = v;
        }
        // Down to zero (a stale power bar), and a regen swing below zero.
        GaugeSmoother bar = new GaugeSmoother();
        bar.snap(640, 0);
        bar.setTarget(-180, 2000, 0);
        for (long t = 0; t < 6000; t += 25) check(bar.value(t) >= -180 - 1e-9, "regen glides to the reading and no further");
        check(GaugeSmoother.omega(10) == GaugeSmoother.omega(GaugeSmoother.MIN_INTERVAL_MS), "a tiny interval is floored");
        System.out.println("GaugeSmootherTest: " + checks + " checks passed (1/2/5/10 s timing, monotone, no overshoot or extrapolation)");
    }
}
