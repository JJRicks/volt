package com.jjricks.volt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Home's charging time tile in the first minutes of a charge (review of #619, P3). The held estimate's state is the
 * notification's "Estimating time left" (nl 38 and it 34 characters), which wrapped at the tile's 12 sp floor at 344 dp,
 * so the tile grew for up to three minutes and then shrank. The tile now has a short rung, "Estimating", taken where the
 * full words don't fit on one line (FitLadder: a shorter rung before smaller text), and TalkBack keeps the full words.
 */
public final class ChargeTimeStateRungsTest {
    private static int checks;
    private static final long T0 = 80_000_000L;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            heldStateHasRungs();
            otherStatesHaveNone();
            followsTheLanguage();
            neverWrapsWhereTheShortFits();
            wired();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("ChargeTimeStateRungsTest: " + checks + " checks passed");
    }

    private static ChargingEstimate.Value live(double value, long t) { return new ChargingEstimate.Value(value, true, t); }

    /** A charge at the current rate, with a time: what ChargeEstimateHold holds at plug-in. */
    private static ChargingEstimate.Result rate() {
        return ChargingEstimate.calculate(live(1, T0), live(49.1, T0), live(-1.28, T0), live(58, T0), live(15.3, T0), live(80, T0));
    }

    /** The held form's state is the catalog's own message, so its rungs are Estimating time left, then Estimating. */
    private static void heldStateHasRungs() {
        ChargingEstimate.Result shown = ChargingEstimate.estimating(rate());
        check(shown.labelMsg != null && shown.labelMsg.id() == T.CHARGE_LIVE_TEXT_ESTIMATING,
                "the held state is charge_live_text_estimating by id, not rendered text: " + (shown.labelMsg == null ? null : T.key(shown.labelMsg.id())));
        check("Estimating time left".equals(shown.label) && "Estimating time left".equals(shown.labelMsg.render()), "its words are unchanged");
        String[] rungs = ChargingEstimate.stateRungs(shown);
        check(Arrays.equals(rungs, new String[] {"Estimating time left", "Estimating"}), "the tile's rungs: " + Arrays.toString(rungs));
        check(shown.status != null && shown.status.isEmpty(), "its status stays empty, so the tile says the words once");
    }

    /** A time, low current, waiting and not charging keep their own text (no rungs). */
    private static void otherStatesHaveNone() {
        ChargingEstimate.Result timed = rate();
        check(timed.remainingMinutesToLimit != null && ChargingEstimate.stateRungs(timed) == null, "a timed estimate has no state rungs");
        ChargingEstimate.Result low = ChargingEstimate.calculate(live(1, T0), live(49.1, T0), live(-.01, T0), live(58, T0), live(15.3, T0), live(80, T0));
        check(ChargingEstimate.LOW_CURRENT.equals(low.label) && ChargingEstimate.stateRungs(low) == null, "low current keeps its own text");
        ChargingEstimate.Result stopped = ChargingEstimate.calculate(live(0, T0), live(49.1, T0), live(0, T0), live(58, T0), live(15.3, T0), live(80, T0));
        check(ChargingEstimate.stateRungs(stopped) == null, "not charging keeps its own text");
        ChargingEstimate.Result waiting = ChargingEstimate.calculate(live(1, T0), null, null, live(58, T0), live(15.3, T0), live(80, T0));
        check(ChargingEstimate.stateRungs(waiting) == null, "waiting for readings keeps its own text");
        check(ChargingEstimate.stateRungs(null) == null, "no estimate, no rungs");
    }

    /** Both rungs come from the language current when shown, also after a change while the time is held. */
    private static void followsTheLanguage() {
        ChargingEstimate.Result shown = ChargingEstimate.estimating(rate());
        T.install(new Words("nl", "Resterende tijd wordt geschat", "Schatten"));
        try {
            check(Arrays.equals(ChargingEstimate.stateRungs(shown), new String[] {"Resterende tijd wordt geschat", "Schatten"}),
                    "rungs in the language now: " + Arrays.toString(ChargingEstimate.stateRungs(shown)));
            check("Estimating time left".equals(shown.label), "the English state stays English for code");
        } finally {
            T.install(null);
        }
        check(Arrays.equals(ChargingEstimate.stateRungs(shown), new String[] {"Estimating time left", "Estimating"}), "and back in English");
    }

    /**
     * FittedValue picks with FitLadder: one line at the largest size from its ceiling (26 sp) down to its floor (12 sp),
     * where a value that fits nowhere wraps at the floor. Widths here are characters times size, a stand-in for the font:
     * with no short rung a 29-character state wraps in a room of 250 (12 sp * 29 = 348); with one, it takes the short
     * rung on one line.
     */
    private static void neverWrapsWhereTheShortFits() {
        String[] rungs = {"Resterende tijd wordt geschat", "Schatten"};
        int room = 250;
        FitLadder.Fits one = (rung, size) -> rungs[0].length() * size <= room;
        check(FitLadder.pick(1, 26, 12, one)[1] == 12 && rungs[0].length() * 12 > room, "the full words alone fit on no line: they wrap at the floor");
        int[] pick = FitLadder.pick(rungs.length, 26, 12, (rung, size) -> rungs[rung].length() * size <= room);
        check(pick[0] == 1 && rungs[1].length() * pick[1] <= room, "the short rung on one line: " + Arrays.toString(pick));
        int[] wide = FitLadder.pick(rungs.length, 26, 12, (rung, size) -> rungs[rung].length() * size <= 1000);
        check(wide[0] == 0 && wide[1] == 26, "where the full words fit, they stay");
    }

    /** The charging time tile passes the rungs to its fitted value and speaks the value's full words. */
    private static void wired() {
        String dash = read("src/com/jjricks/volt/DashboardScreen.java");
        int fill = dash.indexOf("private void fill(int reading, String[] names, ChargingEstimate.Result estimate, Double minutes, String status) {");
        int rungs = dash.indexOf("showChargeTime(values[reading], tags[reading], estimate, minutes, minutes == null ? ChargingEstimate.stateRungs(estimate) : null);");
        int spoken = dash.indexOf("spoken[reading] = spokenChargeTime(names[0], fullText(values[reading]), tags[reading], status);");
        check(fill >= 0 && fill < rungs && rungs < spoken, "the charging time tile gives its state its rungs, then speaks the value's full text");
        check(dash.contains("CharSequence fullText() { return rungs != null ? rungs[0] : getText(); }"), "a fitted value's full text is its first rung");
        // Its text and rungs together (2.0.6): the same rungs again keep the rung drawn, never the long words until a measure.
        check(dash.contains("if (value instanceof FittedValue) ((FittedValue) value).show(text, minutes != null ? durationRungs(minutes) : stateRungs);")
                && dash.contains("if (next != null && Arrays.equals(next, rungs)) return;"), "a refresh with the same rungs leaves the drawn rung alone");
    }

    /** A text source with the two estimating strings only (others fall back to English). */
    private static final class Words implements T.Source {
        private final String language;
        private final Map<Integer, String> text = new HashMap<>();
        Words(String language, String full, String brief) {
            this.language = language;
            text.put(T.CHARGE_LIVE_TEXT_ESTIMATING, full);
            text.put(T.CHARGING_ESTIMATE_ESTIMATING, brief);
        }
        @Override public String string(int id) { return text.get(id); }
        @Override public String plural(int id, long quantity) { return null; }
        @Override public String language() { return language; }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
