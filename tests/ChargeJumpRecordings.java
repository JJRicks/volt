package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthesized charge recordings for the scooter's own percent jump (LearnedJumpTest, ChargeJumpEstimatesTest): each whole
 * percent held for a set number of seconds at a set current, a reading every ten seconds, optionally a stretch with no
 * readings (the phone out of reach) before a percent. Round, made-up numbers shaped like a UT5 MAX stock charge (about
 * 2 A, 4 to 5 minutes a percent in bulk); never a real recording. Uses only ChargeSessionStore's public recording calls.
 */
final class ChargeJumpRecordings {
    static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;

    /** Percents in order: {level, seconds held, amps, ms with no readings before it}. */
    static final class Plan {
        final List<double[]> steps = new ArrayList<>();
        private long gap;
        Plan hold(int level, int seconds, double amps) { steps.add(new double[]{level, seconds, amps, gap}); gap = 0; return this; }
        /** Each percent from (inclusive) to to (exclusive) held seconds at amps. */
        Plan range(int from, int to, int seconds, double amps) { for (int level = from; level < to; level++) hold(level, seconds, amps); return this; }
        /** The next percent's first reading comes ms after the last one (no readings between). */
        Plan gap(long ms) { gap = ms; return this; }
        /** The percents held each for its own seconds (one per entry), from from, at amps. */
        Plan each(int from, int[] seconds, double amps) { for (int i = 0; i < seconds.length; i++) hold(from + i, seconds[i], amps); return this; }
    }

    /** The jump's percents, 84% to 94%: most passed in 10–20 s, two (87% and 91%) in 30 s, as a real jump can have. */
    static final int[] JUMP_84_TO_94 = {20, 20, 20, 30, 20, 20, 10, 30, 20, 20, 20};
    /** The normal finish from 95%: slower percents as the current tapers. */
    static Plan finish(Plan plan) {
        return plan.hold(95, 360, 1.6).hold(96, 420, 1.3).hold(97, 480, 1.0).hold(98, 600, .8).hold(99, 900, .5);
    }
    /** A stock charge from 37% at a steady 1.98 A (280 s a percent), the scooter's percent jumping 83% to 95%, then the finish. */
    static Plan jump83() {
        return finish(new Plan().range(37, 84, 280, 1.98).each(84, JUMP_84_TO_94, 1.98));
    }
    /** The same charger and scooter another day: from 50%, the percent jumping from 86% to 95% instead. */
    static Plan jump86() {
        return finish(new Plan().range(50, 87, 280, 1.98).each(87, new int[]{20, 20, 20, 10, 20, 20, 20, 20}, 1.98));
    }

    /** Records plan as one charge (day: days after WALL); complete: it ends with a reading at 100%, else stopped. */
    static void record(ChargeSessionStore store, ChargeSessionStore.Charger charger, int day, Plan plan, boolean complete) throws Exception {
        long wall = WALL + day * 86_400_000L;
        store.start("synthetic", charger, wall, MONO);
        long t = 0; double last = 0; int level = 0;
        for (double[] step : plan.steps) {
            t += (long) step[3];
            level = (int) step[0]; last = step[2];
            for (long at = 0; at < step[1] * 1000; at += 10000, t += 10000) sample(store, wall, t, level, step[2], false);
        }
        if (complete) sample(store, wall, t, 100, last, true);
        store.stop(complete ? ChargeSessionStore.Stop.COMPLETE : ChargeSessionStore.Stop.STOPPED);
    }
    private static void sample(ChargeSessionStore store, long wall, long t, int level, double amps, boolean completion) throws Exception {
        long mono = MONO + t; double volts = 41 + 12 * Math.min(level, 100) / 100.0;
        ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(wall + t, mono, value(completion ? 0 : 1, mono), value(level, mono),
                value(volts, mono), value(-amps, mono), value(23, mono));
        boolean saved = completion ? store.sampleCompletion(frame) : store.sample(frame);
        if (!saved) throw new AssertionError("Synthesized sample not saved at " + level + "%");
    }
    private static ChargeSessionStore.Value value(double v, long mono) { return new ChargeSessionStore.Value(v, mono, 0x72); }

    private ChargeJumpRecordings() {}
}
