package com.jjricks.volt;

import java.util.function.DoubleUnaryOperator;

/**
 * Automatic's learned-curve charger match, automatic recordings' labels from it, and the "Different charger? Record it"
 * hint (LearnedChargerMatch, ChargeAutoRecordPolicy.detectedLabel, RecordChargerHint, Curve.familyWatts). Synthesized
 * charges only: 52 V packs, 2 A (104 W) for a stock charger and 8 A (416 W) for a high-speed one.
 */
public final class LearnedChargerMatchTest {
    static int checks;
    static final String ONE = "one", TWO = "two";
    static final ChargeSessionStore.Charger STOCK = ChargeSessionStore.Charger.STOCK, FAST = ChargeSessionStore.Charger.HIGH_SPEED;
    static final double V = 52;

    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        familyWatts();
        oneCurveMatches();
        bothCurves();
        bothMatchCloser();
        unknownAndNearFull();
        settledHoldsAndFlips();
        boundaries();
        labels();
        farOffThresholds();
        hintRun();
        hintGates();
        hintLabelsOtherFamily();
        automaticEstimateUsesMatchedCurve();
        System.out.println("LearnedChargerMatchTest: " + checks + " checks");
    }

    // ---- fixtures ----

    /** Curves for profile "one": the given charges, each {charger, amps, start, end} at a minute per percent. */
    static ChargeSessionStore.Snapshot curves(double[]... charges) throws Exception {
        ChargeSessionStore store = ChargeSessionStoreTest.store();
        for (double[] charge : charges) {
            final double amps = charge[1];
            ChargeSessionStoreTest.session(store, ONE, charge[0] == 0 ? STOCK : FAST, (int) charge[2], (int) charge[3], 60000,
                    false, false, soc -> amps);
        }
        return store.snapshot();
    }
    static double[] stock(double amps) { return new double[]{0, amps, 20, 100}; }
    static double[] fast(double amps) { return new double[]{1, amps, 20, 100}; }
    static ChargeSessionStore.Curve curve(ChargeSessionStore.Snapshot snapshot, ChargeSessionStore.Charger charger) {
        return snapshot.curves.get(ChargeSessionStore.key(ONE, charger));
    }

    /** Feeds frames every two seconds from t, all at soc and watts; returns the last answer. */
    static ChargeSessionStore.Charger feed(LearnedChargerMatch m, String profile, double soc, double watts, long[] t, int frames,
            ChargeSessionStore.Curve stock, ChargeSessionStore.Curve fast) {
        ChargeSessionStore.Charger last = null;
        for (int i = 0; i < frames; i++) { t[0] += 2000; last = m.observe(profile, true, soc, V, -watts / V, t[0], t[0], stock, fast); }
        return last;
    }

    // ---- Curve.familyWatts ----

    static void familyWatts() throws Exception {
        ChargeSessionStore.Snapshot one = curves(stock(2));
        ChargeSessionStore.Curve c = curve(one, STOCK);
        check(c != null && c.qualifiedSessions == 1, "One recording makes a curve");
        check(c.powerMatch(50, 104d) == ChargeSessionStore.Curve.PowerMatch.UNKNOWN,
                "powerMatch (timing guard) still needs two recordings: unchanged");
        check(c.familyWatts(50) != null && Math.abs(c.familyWatts(50) - 104) < .01, "One recording already gives family power: " + c.familyWatts(50));
        check(c.familyPowerMatch(50, 104d) == ChargeSessionStore.Curve.PowerMatch.MATCH, "Same power matches");
        check(c.familyPowerMatch(50, 104 * 1.3) == ChargeSessionStore.Curve.PowerMatch.MATCH, "Within 135% matches");
        check(c.familyPowerMatch(50, 104 * 1.4) == ChargeSessionStore.Curve.PowerMatch.DIFFERENT, "Above 135% differs");
        check(c.familyPowerMatch(50, 104 * .6) == ChargeSessionStore.Curve.PowerMatch.DIFFERENT, "Below 65% differs");
        check(c.familyPowerMatch(10, 104d) == ChargeSessionStore.Curve.PowerMatch.UNKNOWN, "No saved power below the recording");
        check(c.familyPowerMatch(50, null) == ChargeSessionStore.Curve.PowerMatch.UNKNOWN, "No live power: unknown");
        check(c.familyWatts(-1) == null && c.familyWatts(Double.NaN) == null, "Out-of-range levels have none");

        ChargeSessionStore.Curve two = curve(curves(stock(2), stock(2.2)), STOCK);
        check(two.powerMatch(50, 104d) == ChargeSessionStore.Curve.PowerMatch.MATCH, "Two recordings: powerMatch as before");
        check(Math.abs(two.familyWatts(50) - 109.2) < .01, "Two agreeing recordings: their median");
        // A charge mislabelled stock (really the fast charger): the pair disagrees, so neither tells the family. Fails closed.
        ChargeSessionStore.Curve split = curve(curves(stock(2), stock(8)), STOCK);
        check(split.familyWatts(50) == null && split.familyPowerMatch(50, 104d) == ChargeSessionStore.Curve.PowerMatch.UNKNOWN,
                "Two disagreeing recordings give no family power");
        ChargeSessionStore.Curve three = curve(curves(stock(2), stock(2), stock(8)), STOCK);
        check(Math.abs(three.familyWatts(50) - 104) < .01, "Three: the agreeing majority, the outlier left out");
    }

    // ---- LearnedChargerMatch ----

    static void oneCurveMatches() throws Exception {
        ChargeSessionStore.Snapshot s = curves(stock(2));
        ChargeSessionStore.Curve stock = curve(s, STOCK);
        LearnedChargerMatch m = new LearnedChargerMatch();
        long[] t = {1000};
        check(feed(m, ONE, 50, 110, t, 1, stock, null) == null, "One frame is not enough");
        check(feed(m, ONE, 50, 110, t, 1, stock, null) == null, "Two frames are not enough");
        check(feed(m, ONE, 50, 110, t, 1, stock, null) == STOCK, "Three frames over four seconds settle the only matching curve");
        check(m.selected(ONE, t[0]) == STOCK && m.selected(TWO, t[0]) == null, "Settled for this profile only");
        // Far-off power on the only curve: nothing.
        LearnedChargerMatch far = new LearnedChargerMatch();
        check(feed(far, ONE, 50, 430, new long[]{1000}, 6, stock, null) == null, "High-speed power never matches a stock-only curve");
        // Duplicate frames never add evidence.
        LearnedChargerMatch dup = new LearnedChargerMatch();
        for (int i = 0; i < 50; i++) check(dup.observe(ONE, true, 50d, V, -2d, 1000, 1000 + i * 200L, stock, null) == null,
                "Redrawing one frame cannot settle");
        check(dup.observe(ONE, true, 50d, V, -2d, 3000, 12000, stock, null) == null, "Second distinct frame");
        check(dup.observe(ONE, true, 50d, V, -2d, 5000, 12001, stock, null) == STOCK, "Third distinct frame settles");
        // A high-rate burst still waits for four seconds.
        LearnedChargerMatch quick = new LearnedChargerMatch();
        for (int i = 1; i <= 30; i++) check(quick.observe(ONE, true, 50d, V, -2d, i * 100L, i * 100L, stock, null) == null,
                "High frame rate waits for the duration");
        check(quick.observe(ONE, true, 50d, V, -2d, 4200, 4200, stock, null) == STOCK, "Then settles");
    }

    static void bothCurves() throws Exception {
        ChargeSessionStore.Snapshot s = curves(stock(2), fast(8));
        ChargeSessionStore.Curve stock = curve(s, STOCK), fast = curve(s, FAST);
        check(feed(new LearnedChargerMatch(), ONE, 50, 100, new long[]{1000}, 3, stock, fast) == STOCK, "Stock power picks stock");
        check(feed(new LearnedChargerMatch(), ONE, 50, 430, new long[]{1000}, 3, stock, fast) == FAST, "High-speed power picks high-speed");
        check(feed(new LearnedChargerMatch(), ONE, 50, 220, new long[]{1000}, 8, stock, fast) == null, "Between both curves: neither");
        check(feed(new LearnedChargerMatch(), ONE, 50, 900, new long[]{1000}, 8, stock, fast) == null, "Above both: neither");
        // Alternating frames never accumulate.
        LearnedChargerMatch alt = new LearnedChargerMatch();
        long[] t = {1000};
        for (int i = 0; i < 6; i++) check(feed(alt, ONE, 50, i % 2 == 0 ? 100 : 430, t, 1, stock, fast) == null, "Alternating power settles nothing");
    }

    static void bothMatchCloser() throws Exception {
        check(LearnedChargerMatch.closer(100, 100, 130) == STOCK, "Both match, live on the stock curve: stock");
        check(LearnedChargerMatch.closer(130, 100, 130) == FAST, "Both match, live on the high-speed curve: high-speed");
        check(LearnedChargerMatch.closer(115, 100, 130) == null, "Halfway between: ambiguous");
        check(LearnedChargerMatch.closer(100, 100, 110) == null, "Curves under 25% apart: never told apart");
        ChargeSessionStore.Snapshot s = curves(stock(2), fast(2.6));
        ChargeSessionStore.Curve stock = curve(s, STOCK), fast = curve(s, FAST);
        check(stock.familyPowerMatch(50, 104d) == ChargeSessionStore.Curve.PowerMatch.MATCH
                && fast.familyPowerMatch(50, 104d) == ChargeSessionStore.Curve.PowerMatch.MATCH, "Fixture: both curves match 104 W");
        check(feed(new LearnedChargerMatch(), ONE, 50, 104, new long[]{1000}, 3, stock, fast) == STOCK, "Clearly closer to stock: stock");
        check(feed(new LearnedChargerMatch(), ONE, 50, 118, new long[]{1000}, 6, stock, fast) == null, "Between two close curves: null");
    }

    static void unknownAndNearFull() throws Exception {
        ChargeSessionStore.Curve partial = curve(curves(new double[]{0, 2, 40, 100}), STOCK);
        check(feed(new LearnedChargerMatch(), ONE, 30, 104, new long[]{1000}, 6, partial, null) == null,
                "No saved power at this level (UNKNOWN for every curve): null");
        ChargeSessionStore.Snapshot s = curves(stock(2), fast(8));
        ChargeSessionStore.Curve stock = curve(s, STOCK), fast = curve(s, FAST);
        // First seen near full with one family recorded: stays unknown, even matching it.
        check(feed(new LearnedChargerMatch(), ONE, 95, 104, new long[]{1000}, 6, stock, null) == null,
                "Near full, one family recorded: never settles");
        // Both recorded and clearly apart: the tight two-sided match settles.
        check(feed(new LearnedChargerMatch(), ONE, 95, 106, new long[]{1000}, 3, stock, fast) == STOCK,
                "Near full, within 15% of stock and the high-speed curve far: stock");
        check(feed(new LearnedChargerMatch(), ONE, 95, 130, new long[]{1000}, 6, stock, fast) == null,
                "Near full, inside the match band but outside 15%: null");
        check(LearnedChargerMatch.evidence(89.9, 130, stock, fast) == STOCK, "Below 90% the ordinary band applies");
    }

    static void settledHoldsAndFlips() throws Exception {
        ChargeSessionStore.Snapshot s = curves(stock(2), fast(8));
        ChargeSessionStore.Curve stock = curve(s, STOCK), fast = curve(s, FAST);
        LearnedChargerMatch m = new LearnedChargerMatch();
        long[] t = {1000};
        check(feed(m, ONE, 80, 104, t, 3, stock, fast) == STOCK, "Settled stock at 80%");
        check(feed(m, ONE, 80, 220, t, 2, stock, fast) == STOCK, "A short contrary run keeps it");
        check(feed(m, ONE, 80, 104, t, 1, stock, fast) == STOCK, "A matching frame clears the run");
        check(feed(m, ONE, 81, 220, t, 3, stock, fast) == null, "A sustained run the stock curve rules out forgets it");
        check(feed(m, ONE, 81, 430, t, 3, stock, fast) == FAST, "Then the matching family settles");
        // Above 90 the settled family never flips frame by frame.
        LearnedChargerMatch top = new LearnedChargerMatch();
        long[] u = {1000};
        check(feed(top, ONE, 85, 430, u, 3, stock, fast) == FAST, "Settled high-speed at 85%");
        check(feed(top, ONE, 95, 104, u, 20, stock, fast) == FAST, "Taper power like stock's above 90%: kept");
        // A switch to the other family's power, evidence naming it, below 90.
        LearnedChargerMatch sw = new LearnedChargerMatch();
        long[] w = {1000};
        check(feed(sw, ONE, 60, 104, w, 3, stock, fast) == STOCK, "Stock");
        check(feed(sw, ONE, 60, 430, w, 2, stock, fast) == STOCK, "Two frames of high-speed power: still stock");
        check(feed(sw, ONE, 60, 430, w, 1, stock, fast) == FAST, "Sustained: high-speed (its candidate was gathered meanwhile)");
    }

    static void boundaries() throws Exception {
        ChargeSessionStore.Snapshot s = curves(stock(2), fast(8));
        ChargeSessionStore.Curve stock = curve(s, STOCK), fast = curve(s, FAST);
        long[] t = {1000};
        LearnedChargerMatch m = new LearnedChargerMatch();
        feed(m, ONE, 50, 104, t, 3, stock, fast);
        check(m.selected(ONE, t[0]) == STOCK, "Settled");
        check(m.observe(TWO, true, 50d, V, -2d, t[0] + 2000, t[0] + 2000, stock, fast) == null && m.selected(ONE, t[0] + 2000) == null,
                "Another scooter forgets it");
        m = new LearnedChargerMatch(); t[0] = 1000; feed(m, ONE, 50, 104, t, 3, stock, fast);
        check(m.observe(ONE, false, 50d, V, 0d, t[0] + 2000, t[0] + 2000, stock, fast) == null && m.selected(ONE, t[0] + 2000) == null,
                "Charging stopping forgets it");
        m = new LearnedChargerMatch(); t[0] = 1000; feed(m, ONE, 50, 104, t, 3, stock, fast);
        check(m.observe(ONE, true, 48d, V, -2d, t[0] + 2000, t[0] + 2000, stock, fast) == null, "The level falling a point is a new charge");
        m = new LearnedChargerMatch(); t[0] = 1000; feed(m, ONE, 50, 104, t, 3, stock, fast);
        check(m.selected(ONE, t[0] + ChargerDetection.RETAIN_MS) == STOCK && m.selected(ONE, t[0] + ChargerDetection.RETAIN_MS + 1) == null,
                "Kept for RETAIN_MS without a usable frame, then forgotten");
        check(m.observe(ONE, true, null, null, null, t[0] + 3000, t[0] + 3000, stock, fast) == STOCK, "An unusable frame keeps it");
        check(m.observe(ONE, true, 50d, V, -2d, t[0] - 5000, t[0] - 5000, stock, fast) == null, "The clock going back forgets it");
        // Reconnect below 90 on the same charger: kept. Near full with a frame that does not name it: forgotten.
        m = new LearnedChargerMatch(); t[0] = 1000; feed(m, ONE, 70, 104, t, 3, stock, fast);
        m.disconnected();
        check(feed(m, ONE, 70, 104, t, 1, stock, fast) == STOCK, "Reconnect below 90% with matching power keeps it");
        m.disconnected();
        check(feed(m, ONE, 70, 430, t, 1, stock, fast) == null, "Reconnect onto power the curve rules out: forgotten at once");
        m = new LearnedChargerMatch(); t[0] = 1000; feed(m, ONE, 85, 430, t, 3, stock, fast);
        m.disconnected();
        check(feed(m, ONE, 92, 410, t, 1, stock, fast) == FAST, "Reconnect near full on a frame that names it clearly: kept");
        m.disconnected();
        check(stock.familyPowerMatch(92, 300d) == ChargeSessionStore.Curve.PowerMatch.DIFFERENT
                && fast.familyPowerMatch(92, 300d) == ChargeSessionStore.Curve.PowerMatch.MATCH, "Fixture: 300 W matches only high-speed, loosely");
        check(feed(m, ONE, 92, 300, t, 1, stock, fast) == null,
                "Reconnect near full on a frame its curve allows but does not name clearly: a swap is possible, forgotten");
        // A curve no longer saved (recordings cleared) below 90: forgotten after a run.
        m = new LearnedChargerMatch(); t[0] = 1000; feed(m, ONE, 50, 104, t, 3, stock, fast);
        check(feed(m, ONE, 50, 104, t, 3, null, null) == null, "Cleared recordings: forgotten");
    }

    // ---- labels ----

    static void labels() {
        // detectedLabel(setting, detection, learned, farOff)
        check(ChargeAutoRecordPolicy.detectedLabel(null, FAST, STOCK, null) == FAST, "ChargerDetection first");
        check(ChargeAutoRecordPolicy.detectedLabel(STOCK, FAST, null, null) == FAST, "ChargerDetection outranks the setting, as since #555");
        check(ChargeAutoRecordPolicy.detectedLabel(null, null, STOCK, FAST) == STOCK, "Automatic: a learned match");
        check(ChargeAutoRecordPolicy.detectedLabel(null, null, null, FAST) == FAST, "Automatic: far above the only (stock) curve");
        check(ChargeAutoRecordPolicy.detectedLabel(STOCK, null, FAST, null) == null, "A setting: a learned match never replaces it");
        check(ChargeAutoRecordPolicy.detectedLabel(FAST, null, null, STOCK) == null, "A setting: a far-off run never replaces it");
        check(ChargeAutoRecordPolicy.detectedLabel(null, null, null, null) == null, "Nothing known: null");
        // label(): start labels.
        ChargeAutoRecordPolicy.Label l = ChargeAutoRecordPolicy.label(null, ChargeAutoRecordPolicy.detectedLabel(null, null, STOCK, null), 1, 0);
        check(l.charger == STOCK && l.source == ChargeSessionStore.LabelSource.DETECTED, "A clear match labels as detected");
        l = ChargeAutoRecordPolicy.label(null, ChargeAutoRecordPolicy.detectedLabel(null, null, null, FAST), 1, 0);
        check(l.charger == FAST && l.source == ChargeSessionStore.LabelSource.DETECTED, "Far above stock-only: high-speed, detected");
        l = ChargeAutoRecordPolicy.label(FAST, ChargeAutoRecordPolicy.detectedLabel(FAST, null, STOCK, null), 1, 0);
        check(l.charger == FAST && l.source == ChargeSessionStore.LabelSource.SETTING, "The setting still wins over a learned match");
        l = ChargeAutoRecordPolicy.label(null, ChargeAutoRecordPolicy.detectedLabel(null, null, null, null), 2, 1);
        check(l.charger == STOCK && l.source == ChargeSessionStore.LabelSource.GUESS, "Both recorded, neither matching: today's guess");
        // relabel(): a guess follows the learned family; a chosen label never does.
        check(ChargeAutoRecordPolicy.relabel(ChargeSessionStore.LabelSource.GUESS, STOCK, FAST) == FAST, "A guess takes the far-off family");
        check(ChargeAutoRecordPolicy.relabel(ChargeSessionStore.LabelSource.GUESS, STOCK, STOCK) == STOCK, "A guess confirmed becomes detected");
        check(ChargeAutoRecordPolicy.relabel(ChargeSessionStore.LabelSource.CHOSEN, STOCK, FAST) == null, "A chosen label is never replaced");
        check(ChargeAutoRecordPolicy.relabel(ChargeSessionStore.LabelSource.SETTING, STOCK,
                ChargeAutoRecordPolicy.detectedLabel(STOCK, null, FAST, FAST)) == null, "A setting's label keeps it against a likeness");
        check(ChargeAutoRecordPolicy.relabel(ChargeSessionStore.LabelSource.DETECTED, STOCK, null) == null, "A match forgotten keeps the label");
    }

    // ---- RecordChargerHint ----

    static void farOffThresholds() {
        // Stock 100-120 W against high-speed 400-500 W: must trip, both ways.
        for (double saved : new double[]{100, 110, 120}) for (double live : new double[]{400, 450, 500}) {
            check(RecordChargerHint.farOff(saved, live), "High-speed on a stock curve trips: " + saved + " / " + live);
            check(RecordChargerHint.farOff(live, saved), "Stock on a high-speed curve trips: " + live + " / " + saved);
        }
        // A cold or warm battery's 20-30% either way: never.
        for (double saved : new double[]{60, 110, 450}) for (double factor : new double[]{.7, .75, .8, 1.2, 1.25, 1.3}) {
            check(!RecordChargerHint.farOff(saved, saved * factor), "Temperature-sized drift does not trip: " + saved + " x " + factor);
        }
        check(!RecordChargerHint.farOff(110, 110 * 1.75), "Under 1.8x: no");
        check(RecordChargerHint.farOff(110, 110 * 1.8), "1.8x: yes");
        check(!RecordChargerHint.farOff(450, 450 * .55), "Above 0.5x: no (a throttled charge can run at 40-80%)");
        check(RecordChargerHint.farOff(450, 450 * .5), "0.5x: yes");
        check(RecordChargerHint.farOff(84, 168), "A small model's 2 A against a 4 A charger (2x, 84 W apart): yes");
        check(!RecordChargerHint.farOff(30, 60), "Tiny powers, only 30 W apart: no");
        check(!RecordChargerHint.farOff(Double.NaN, 400) && !RecordChargerHint.farOff(110, 0), "No saved or live power: no");
        check(RecordChargerHint.otherFamily(STOCK, 1) == FAST && RecordChargerHint.otherFamily(FAST, -1) == STOCK,
                "Far above stock is high-speed; far below high-speed is stock");
        check(RecordChargerHint.otherFamily(STOCK, -1) == null && RecordChargerHint.otherFamily(FAST, 1) == null,
                "Far below stock or far above high-speed: no known family");
    }

    /** Feeds frames every ten seconds (Volt's slowest battery poll) from t; returns sustained after the last. */
    static boolean hintFeed(RecordChargerHint h, double soc, double watts, long[] t, int frames,
            ChargeSessionStore.Curve stock, ChargeSessionStore.Curve fast) {
        for (int i = 0; i < frames; i++) { t[0] += 10000; h.observe(ONE, true, soc, V, -watts / V, t[0], t[0], stock, fast); }
        return h.sustained(ONE, t[0]);
    }

    static void hintRun() throws Exception {
        ChargeSessionStore.Curve stockOnly = curve(curves(stock(2)), STOCK);
        RecordChargerHint h = new RecordChargerHint();
        long[] t = {1000};
        // 60 s settling from the charge's first frame, then 60 s far off: the earliest is about two minutes in.
        check(!hintFeed(h, 50, 450, t, 6, stockOnly, null), "Nothing in the charge's first minute");
        check(!hintFeed(h, 50, 450, t, 6, stockOnly, null), "Under a minute of counted far-off frames");
        check(hintFeed(h, 50, 450, t, 1, stockOnly, null), "A minute far off after the first minute: sustained");
        check(h.otherFamily(ONE, t[0]) == FAST && h.otherFamily(TWO, t[0]) == null, "Points to high-speed, for this profile only");
        check(!h.sustained(ONE, t[0] + ChargerDetection.MAX_CONTINUITY_GAP_MS + 1), "Stale without fresh frames");
        check(!hintFeed(h, 50, 110, t, 1, stockOnly, null), "A frame back on the curve restarts the run");
        check(!hintFeed(h, 50, 450, t, 6, stockOnly, null), "Restarted");
        check(hintFeed(h, 50, 450, t, 1, stockOnly, null), "Sustained again");
        // Frames with no saved power at their level neither count nor break.
        // Two partial recordings leave 55-64% without saved power.
        ChargeSessionStore.Curve holed = curve(curves(new double[]{0, 2, 20, 55}, new double[]{0, 2, 65, 100}), STOCK);
        check(holed.familyWatts(60) == null && holed.familyWatts(50) != null && holed.familyWatts(70) != null, "Fixture: a hole at 55-64%");
        RecordChargerHint gap = new RecordChargerHint();
        long[] g = {1000};
        hintFeed(gap, 50, 450, g, 9, holed, null);
        check(!hintFeed(gap, 60, 450, g, 3, holed, null), "Unknown level: not counted");
        check(hintFeed(gap, 70, 450, g, 1, holed, null), "Nor broken");
        // A reconnect or a long gap restarts the settling minute.
        RecordChargerHint re = new RecordChargerHint();
        long[] r = {1000};
        hintFeed(re, 50, 450, r, 13, stockOnly, null);
        check(re.sustained(ONE, r[0]), "Sustained before the drop");
        re.disconnected();
        check(!hintFeed(re, 50, 450, r, 12, stockOnly, null), "After a reconnect: a new settling minute and a new run");
        check(hintFeed(re, 50, 450, r, 1, stockOnly, null), "Then sustained again");
        r[0] += ChargerDetection.MAX_CONTINUITY_GAP_MS;
        check(!hintFeed(re, 50, 450, r, 1, stockOnly, null), "A long gap restarts too");
    }

    static void hintGates() throws Exception {
        ChargeSessionStore.Curve stockOnly = curve(curves(stock(2)), STOCK);
        ChargeSessionStore.Snapshot both = curves(stock(2), fast(8));
        // Exactly one family recorded.
        check(!hintFeed(new RecordChargerHint(), 50, 900, new long[]{1000}, 20, curve(both, STOCK), curve(both, FAST)),
                "Both families recorded: never");
        check(!hintFeed(new RecordChargerHint(), 50, 450, new long[]{1000}, 20, null, null), "No recordings: never");
        check(hintFeed(new RecordChargerHint(), 50, 450, new long[]{1000}, 13, stockOnly, null), "One family: yes");
        ChargeSessionStore.Curve fastOnly = curve(curves(fast(8)), FAST);
        check(hintFeed(new RecordChargerHint(), 50, 104, new long[]{1000}, 13, null, fastOnly), "High-speed only, stock power: yes");
        // Far off, not just different.
        check(!hintFeed(new RecordChargerHint(), 50, 150, new long[]{1000}, 20, stockOnly, null), "Outside the band but not far: no");
        // Below 90%.
        check(!hintFeed(new RecordChargerHint(), 90, 450, new long[]{1000}, 20, stockOnly, null), "At 90%: no");
        RecordChargerHint top = new RecordChargerHint();
        long[] t = {1000};
        hintFeed(top, 85, 450, t, 13, stockOnly, null);
        check(top.sustained(ONE, t[0]), "Below 90: sustained");
        check(!hintFeed(top, 90, 450, t, 1, stockOnly, null), "Reaching 90% ends it");
        // Charging, a scooter change and a new charge.
        RecordChargerHint h = new RecordChargerHint();
        long[] u = {1000};
        hintFeed(h, 50, 450, u, 13, stockOnly, null);
        h.observe(ONE, false, 50d, V, 0d, u[0] + 10000, u[0] + 10000, stockOnly, null);
        check(!h.sustained(ONE, u[0] + 10000), "Charging stopped: over");
        h = new RecordChargerHint(); u[0] = 1000; hintFeed(h, 50, 450, u, 13, stockOnly, null);
        h.observe(TWO, true, 50d, V, -450 / V, u[0] + 10000, u[0] + 10000, stockOnly, null);
        check(!h.sustained(ONE, u[0] + 10000) && !h.sustained(TWO, u[0] + 10000), "Another scooter: over");
        // show(charging, sustained, recording, slowed): every gate both ways.
        check(RecordChargerHint.show(true, true, false, false), "All gates pass: shown");
        check(!RecordChargerHint.show(false, true, false, false), "Not charging: hidden");
        check(!RecordChargerHint.show(true, false, false, false), "Not sustained: hidden");
        check(!RecordChargerHint.show(true, true, true, false), "A recording running: hidden");
        check(!RecordChargerHint.show(true, true, false, true), "Charging slowed: hidden");
    }

    static void hintLabelsOtherFamily() throws Exception {
        ChargeSessionStore.Curve stockOnly = curve(curves(stock(2)), STOCK);
        RecordChargerHint h = new RecordChargerHint();
        long[] t = {1000};
        hintFeed(h, 50, 40, t, 13, stockOnly, null);
        check(h.sustained(ONE, t[0]) && h.otherFamily(ONE, t[0]) == null, "Far below stock: hint, but no family to label");
        ChargeSessionStore.Curve fastOnly = curve(curves(fast(8)), FAST);
        h = new RecordChargerHint(); t[0] = 1000;
        hintFeed(h, 50, 104, t, 13, null, fastOnly);
        check(h.otherFamily(ONE, t[0]) == STOCK, "Far below high-speed: stock");
        check(ChargeAutoRecordPolicy.label(null, ChargeAutoRecordPolicy.detectedLabel(null, null, null, h.otherFamily(ONE, t[0])), 0, 1).charger == STOCK,
                "...and an automatic recording under Automatic is labelled stock");
    }

    // ---- the estimate under Automatic ----

    static ChargingEstimate.Value live(double value) { return new ChargingEstimate.Value(value, true, 1000); }

    static void automaticEstimateUsesMatchedCurve() throws Exception {
        ChargeSessionStore.Snapshot s = curves(stock(2), stock(2), fast(8), fast(8));
        ChargeSessionStore.Curve stock = curve(s, STOCK), fast = curve(s, FAST);
        int otherModel = -1; // ModelRegistry.chargeReferenceProduct for any model but the UT5 MAX
        ChargerDetection detection = new ChargerDetection();
        LearnedChargerMatch match = new LearnedChargerMatch();
        ChargeSessionStore.Charger family = null;
        for (int i = 0; i < 3; i++) {
            long t = 1000 + i * 2000L;
            check(detection.observe(ONE, otherModel, true, 50d, V, -2d, t, t) == null, "ChargerDetection knows only the UT5 MAX");
            family = match.observe(ONE, true, 50d, V, -2d, t, t, stock, fast);
        }
        check(family == STOCK, "Automatic settles the learned stock curve on another model");
        ChargingEstimate.Result base = ChargingEstimate.calculate(live(1), live(V), live(-2), live(50), live(15.3), live(100));
        ChargingEstimate.Result before = ChargingEstimate.forScooter(base, live(50), live(15.3), otherModel, null, null);
        check(before == base && !before.learnedToFull, "Without the match (today): the current-rate baseline");
        ChargingEstimate.Result after = ChargingEstimate.forScooter(base, live(50), live(15.3), otherModel, family,
                s.curves.get(ChargeSessionStore.key(ONE, family)));
        check(after.learnedToFull && after.learnedSessions == 2, "With it: the learned stock curve's time to full");
        check(Math.abs(after.remainingMinutesToFull - 50) < .5, "Fifty learned one-minute percentages: " + after.remainingMinutesToFull);
        // High-speed power picks the high-speed curve; the guard still applies.
        LearnedChargerMatch fastMatch = new LearnedChargerMatch();
        ChargeSessionStore.Charger fam = null;
        for (int i = 0; i < 3; i++) { long t = 1000 + i * 2000L; fam = fastMatch.observe(ONE, true, 50d, V, -8d, t, t, stock, fast); }
        check(fam == FAST, "High-speed power settles the high-speed curve");
        ChargingEstimate.Result fastBase = ChargingEstimate.calculate(live(1), live(V), live(-8), live(50), live(15.3), live(100));
        check(ChargingEstimate.forScooter(fastBase, live(50), live(15.3), otherModel, fam, fast).learnedToFull, "...and its learned time");
        check(!ChargingEstimate.forScooter(fastBase, live(50), live(15.3), otherModel, STOCK, stock).learnedToFull,
                "The power-match guard still refuses the other family's curve");
    }
}
