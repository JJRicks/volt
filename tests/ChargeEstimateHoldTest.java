package com.jjricks.volt;

import java.nio.file.*;
import java.util.*;

/**
 * "Estimating time left" first (ChargeEstimateHold, ChargingEstimate.estimating): synthesized plug-ins (fast charger
 * soft start, Auto before detection settles, a charger stuck in its soft start, a standard charger read as the fast one,
 * a charge plugged in near full, a charge Volt joins midway, a charger pause, other models), the hold's shown form, and a
 * replay of the public fast-charge-taper-v7 charges. Argument: that data folder.
 */
public final class ChargeEstimateHoldTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long T0 = 80_000_000L, SEC = 1000L, MIN = 60_000L;
    private static final ChargeSessionStore.Charger FAST = ChargeSessionStore.Charger.HIGH_SPEED, STOCK = ChargeSessionStore.Charger.STOCK;
    private static final int UT5 = Ut5MaxStockChargeReference.PRODUCT_ID, GT3 = 24182;
    private static ChargingEstimate.Value live(double value, long t) { return new ChargingEstimate.Value(value, true, t); }

    public static void main(String[] args) throws Exception {
        softStart();
        settleAfterBulk();
        autoDetection();
        hardCap();
        nearFull();
        standardCharger();
        joinedMidway();
        chargerPause();
        scope();
        shownForm();
        replay(Paths.get(args[0]));
        System.out.println("ChargeEstimateHold: " + checks + " checks passed");
    }

    /** A hold that saw the charge begin: a not-charging reading a second before t. */
    private static ChargeEstimateHold plugIn(long t) {
        ChargeEstimateHold hold = new ChargeEstimateHold();
        hold.observe("a", UT5, false, null, null, null, t - SEC);
        return hold;
    }

    /**
     * The fast charger's soft start: about 1.3 A for a minute, where the only time is the current rate (about 300 minutes
     * for a charge that takes about an hour). Held throughout, then the bulk current and one more settling minute.
     */
    private static void softStart() {
        long t = T0;
        ChargingEstimate.Result rate = ChargingEstimate.forScooter(ChargingEstimate.calculate(live(1, t), live(49.1, t), live(-1.28, t),
                live(58, t), live(15.3, t), new ChargingEstimate.Value(null, false, 0)), live(58, t), live(15.3, t), UT5, FAST, null, null, null, t, t, null, null);
        check(rate.remainingMinutesToFull != null && rate.remainingMinutesToFull > 250, "The soft start's time is the current rate, far off: " + rate.remainingMinutesToFull);
        ChargeEstimateHold hold = plugIn(t);
        for (long s = 0; s <= 60; s += 10) {
            hold.observe("a", UT5, true, FAST, 49.1, -1.28, t + s * SEC);
            check(hold.holding("a", t + s * SEC), "Held in the soft start at " + s + " s");
        }
        ChargingEstimate.Result shown = ChargingEstimate.estimating(rate);
        check(shown.remainingMinutesToFull == null && shown.remainingMinutesToLimit == null, "The held form shows no time");
        check("Estimating time left".equals(shown.label), "The held form's state is Estimating time left: " + shown.label);
    }

    private static void settleAfterBulk() {
        long t = T0;
        ChargeEstimateHold hold = plugIn(t);
        hold.observe("a", UT5, true, FAST, 49.1, -1.28, t);
        hold.observe("a", UT5, true, FAST, 50.1, -10.06, t + 90 * SEC);
        check(hold.holding("a", t + 90 * SEC), "The bulk current's first reading is still held (settling)");
        check(hold.holding("a", t + 149 * SEC), "Held for the settling minute");
        check(!hold.holding("a", t + 150 * SEC), "Shown a minute after the bulk current started");
        hold.observe("a", UT5, true, FAST, 50.2, -1.5, t + 160 * SEC);
        check(!hold.holding("a", t + 160 * SEC), "Once shown, a dip below the bulk current doesn't hold it again");
        // Bulk from the first reading: just the settling minute.
        ChargeEstimateHold bulk = plugIn(t);
        bulk.observe("a", UT5, true, FAST, 50.0, -10.09, t);
        check(bulk.holding("a", t + 59 * SEC) && !bulk.holding("a", t + 60 * SEC), "Bulk at plug-in: one settling minute");
    }

    /** Auto: the real ChargerDetection decides the charger; no time until it has, at 10 A from the first reading. */
    private static void autoDetection() {
        long t = T0;
        ChargerDetection detection = new ChargerDetection();
        ChargeEstimateHold hold = plugIn(t);
        long identified = -1;
        for (long s = 0; s <= 120; s += 2) {
            long now = t + s * SEC;
            detection.observe("a", UT5, true, 61d, 49.9, -10.09, now, now);
            ChargeSessionStore.Charger charger = detection.selected("a", now);
            if (charger != null && identified < 0) identified = now;
            hold.observe("a", UT5, true, charger, 49.9, -10.09, now);
            if (identified < 0) check(hold.holding("a", now), "Held before the charger is identified, at " + s + " s");
        }
        check(identified > t, "Detection identified the fast charger");
        check(hold.holding("a", identified + 59 * SEC) && !hold.holding("a", identified + 60 * SEC), "Shown a minute after the charger is identified");
        // The soft start is not evidence for either charger: still unknown and held.
        ChargerDetection soft = new ChargerDetection();
        ChargeEstimateHold softHold = plugIn(t);
        for (long s = 0; s <= 60; s += 2) {
            long now = t + s * SEC;
            soft.observe("a", UT5, true, 58d, 49.1, -1.28, now, now);
            check(soft.selected("a", now) == null, "1.28 A identifies no charger");
            softHold.observe("a", UT5, true, soft.selected("a", now), 49.1, -1.28, now);
            check(softHold.holding("a", now), "Held while the charger is unknown");
        }
    }

    /** A charge that never becomes ready still gets a time at MAX_HOLD_MS. */
    private static void hardCap() {
        long t = T0, cap = ChargeEstimateHold.MAX_HOLD_MS;
        check(cap == 3 * MIN, "The cap is three minutes");
        ChargeEstimateHold stuck = plugIn(t), misread = plugIn(t), unknown = plugIn(t);
        for (long s = 0; s <= 240; s += 10) {
            long now = t + s * SEC;
            stuck.observe("a", UT5, true, FAST, 49.1, -1.28, now);       // a fast charger stuck in its soft start
            misread.observe("a", UT5, true, FAST, 49.5, -2.21, now);     // a standard charger read as the fast one
            unknown.observe("a", UT5, true, null, 50.0, -10.0, now);     // a charger never identified
            boolean before = now - t < cap;
            check(stuck.holding("a", now) == before && misread.holding("a", now) == before && unknown.holding("a", now) == before,
                    "Held only before the cap, at " + s + " s");
        }
        // The cap also ends a settling minute that would run past it.
        ChargeEstimateHold late = plugIn(t);
        late.observe("a", UT5, true, FAST, 49.1, -1.28, t);
        late.observe("a", UT5, true, FAST, 50.1, -10.06, t + 150 * SEC);
        check(late.holding("a", t + 179 * SEC) && !late.holding("a", t + cap), "The cap ends a late settling minute");
        // No reading arrives after plug-in (the link drops): the cap still ends the hold.
        ChargeEstimateHold silent = plugIn(t);
        silent.observe("a", UT5, true, FAST, 49.1, -1.28, t);
        check(silent.holding("a", t + cap - 1) && !silent.holding("a", t + cap + 5 * MIN), "Silence after plug-in: the cap");
    }

    /** Plugged in near full: the fast charger is at its voltage limit below 4 A, and the taper model times it. */
    private static void nearFull() {
        long t = T0;
        check(ChargeEstimateHold.ready(FAST, 54.30, -2.4), "At the voltage limit (54.30 + 0.107 x 2.4 V) the charge is ready below 4 A");
        check(!ChargeEstimateHold.ready(FAST, 52.40, -1.28), "Below the voltage limit 1.28 A is the soft start");
        check(ChargeEstimateHold.ready(FAST, 47.5, -4.0) && !ChargeEstimateHold.ready(FAST, 47.5, -3.99), "The bulk current starts at 4 A");
        check(!ChargeEstimateHold.ready(null, 54.3, -2.4) && !ChargeEstimateHold.ready(FAST, Double.NaN, -10), "Unknown charger or reading");
        ChargeEstimateHold hold = plugIn(t);
        hold.observe("a", UT5, true, FAST, 54.30, -2.4, t);
        check(hold.holding("a", t + 59 * SEC) && !hold.holding("a", t + 60 * SEC), "Near full: only the settling minute");
    }

    private static void standardCharger() {
        long t = T0;
        check(ChargeEstimateHold.ready(STOCK, 47.2, -2.12) && ChargeEstimateHold.ready(STOCK, 47.2, -1.2), "The standard charger's current at plug-in is its bulk");
        ChargeEstimateHold hold = plugIn(t);
        hold.observe("a", UT5, true, STOCK, 47.2, -2.12, t);
        check(hold.holding("a", t + 30 * SEC) && !hold.holding("a", t + 60 * SEC), "Standard charger: the settling minute only");
    }

    /** Volt joins a charge already running (a reconnect, a new process): held only until ready, no settling minute. */
    private static void joinedMidway() {
        long t = T0;
        ChargeEstimateHold hold = new ChargeEstimateHold();
        hold.observe("a", UT5, true, null, 52.0, -10.09, t);
        check(hold.holding("a", t + 2 * SEC), "Joined midway: held while the charger is identified");
        hold.observe("a", UT5, true, FAST, 52.0, -10.09, t + 4 * SEC);
        check(!hold.holding("a", t + 4 * SEC), "Joined midway: shown once the charger is known");
        ChargeEstimateHold manual = new ChargeEstimateHold();
        manual.observe("a", UT5, true, FAST, 52.0, -10.09, t);
        check(!manual.holding("a", t), "Joined midway with the charger chosen: never held");
        // An old not-charging reading (over the grace) doesn't count as seeing the start.
        ChargeEstimateHold stale = new ChargeEstimateHold();
        stale.observe("a", UT5, false, null, null, null, t - ChargeEstimateHold.START_GRACE_MS - SEC);
        stale.observe("a", UT5, true, FAST, 52.0, -10.09, t);
        check(!stale.holding("a", t), "A start not seen gets no settling minute");
    }

    /** A charger pause (not charging) begins a new charge; readings stopping for 30 minutes too; so does a new scooter. */
    private static void chargerPause() {
        long t = T0;
        ChargeEstimateHold hold = plugIn(t);
        hold.observe("a", UT5, true, FAST, 50.0, -10.09, t);
        check(!hold.holding("a", t + 2 * MIN), "Shown");
        hold.observe("a", UT5, false, FAST, null, null, t + 5 * MIN);
        check(!hold.holding("a", t + 5 * MIN), "Not charging: nothing held");
        hold.observe("a", UT5, true, FAST, 49.2, -1.28, t + 5 * MIN + 5 * SEC);
        check(hold.holding("a", t + 5 * MIN + 5 * SEC), "After a charger pause the next plug-in is held again");
        hold.observe("a", UT5, true, FAST, 50.0, -10.09, t + 5 * MIN + 30 * SEC);
        check(!hold.holding("a", t + 6 * MIN + 30 * SEC), "And shown a settling minute after its bulk current");
        hold.observe("a", UT5, true, FAST, 50.0, -10.09, t + 40 * MIN);
        check(!hold.holding("a", t + 40 * MIN), "Readings every few minutes keep the charge");
        hold.observe("a", UT5, true, null, 50.0, -10.09, t + 75 * MIN);
        check(hold.holding("a", t + 75 * MIN), "After 30 minutes without readings a new charge is watched");
        check(!hold.holding("b", t + 75 * MIN), "Another scooter's charge is not this one's");
        hold.observe("b", UT5, true, FAST, 50.0, -10.09, t + 76 * MIN);
        check(!hold.holding("a", t + 76 * MIN) && !hold.holding("b", t + 76 * MIN), "A new scooter starts afresh (joined midway, charger known)");
        hold.reset();
        check(!hold.holding("b", t + 76 * MIN), "Reset forgets the charge");
    }

    /** Only models with charger detection: elsewhere the charger is never identified and the hold would only delay. */
    private static void scope() {
        long t = T0;
        check(ChargeEstimateHold.applies(UT5) && !ChargeEstimateHold.applies(GT3) && !ChargeEstimateHold.applies(-1), "UT5 MAX only");
        ChargeEstimateHold hold = new ChargeEstimateHold();
        hold.observe("a", GT3, false, null, null, null, t - SEC);
        hold.observe("a", GT3, true, null, 41.0, -1.2, t);
        check(!hold.holding("a", t), "Another model is never held");
        check(!new ChargeEstimateHold().holding("a", t), "No watched charge, nothing held");
    }

    /** The shown form keeps everything but the time; recordings keep the estimate itself. */
    private static void shownForm() {
        long t = T0;
        ChargingEstimate.Result rate = ChargingEstimate.calculate(live(1, t), live(49.1, t), live(-1.28, t), live(58, t), live(15.3, t), live(80, t));
        ChargingEstimate.Result shown = ChargingEstimate.estimating(rate);
        check(shown.charging && shown.inputAmps.equals(rate.inputAmps) && shown.inputWatts.equals(rate.inputWatts)
                && shown.targetPercent.equals(80d), "Still charging, with its power, current and limit");
        check(shown.remainingMinutesToLimit == null && shown.remainingMinutesToFull == null
                && shown.planningMinutesToLimit == null && shown.planningMinutesToFull == null, "No time and nothing for Charge done by to plan with");
        check(!shown.learnedToFull && !shown.referenceToFull && shown.learnedSessions == 0 && shown.referenceCharger == null, "Claims no source");
        check(shown.labelMsg.render().equals("Estimating time left") && shown.status.equals("Estimating time left"), "The state and its tooltip: the live card's words");
        check(ChargingEstimate.minutesToFullBesideLimit(shown, 58d) == null && ChargingEstimate.statusBesideLimit(shown, 58d).equals(shown.status),
                "The 100% reading beside a limit is held too");
        check(ChargingEstimate.minutesToAlert(ChargingEstimate.estimating(rate), shown) == null, "So is the charge alert's countdown");
        ChargeSessionStore.Estimate recorded = ChargingEstimate.recordingSnapshot(rate);
        check(recorded.remainingMinutesToFull != null && recorded.fullSource == ChargeSessionStore.Source.CURRENT_RATE,
                "The recording keeps what the estimator computed, with its source");
        ChargingEstimate.Result stopped = ChargingEstimate.calculate(live(0, t), live(49.1, t), live(0, t), live(58, t), live(15.3, t), live(80, t));
        check(ChargingEstimate.estimating(stopped) == stopped, "Not charging: unchanged");
        ChargingEstimate.Result low = ChargingEstimate.calculate(live(1, t), live(49.1, t), live(-.01, t), live(58, t), live(15.3, t), live(80, t));
        check(ChargingEstimate.estimating(low) == low, "No time to hold (low current): its own state stays");
        check(ChargingEstimate.estimating(null) == null, "Null stays null");
    }

    /**
     * The public replay set (fast-charge-taper-v7, each charge as a plug-in Volt saw, its charger as labelled): no reading
     * is held past MAX_HOLD_MS; standard-charger charges hold exactly their first settling minute; and the first time a
     * fast charge shows is closer to the real finish than the first time it showed before.
     */
    private static void replay(Path data) throws Exception {
        List<FastChargeTaperReplay.Row> rows = FastChargeTaperReplay.replay(data.resolve("sessions.csv"));
        Map<Integer, List<FastChargeTaperReplay.Row>> sessions = new TreeMap<>();
        for (FastChargeTaperReplay.Row r : rows) sessions.computeIfAbsent(r.sample.session, k -> new ArrayList<>()).add(r);
        double before = 0, after = 0; int fast = 0, held = 0;
        for (Map.Entry<Integer, List<FastChargeTaperReplay.Row>> e : sessions.entrySet()) {
            List<FastChargeTaperReplay.Row> rs = e.getValue();
            ChargeSessionStore.Charger charger = ChargeSessionStore.Charger.valueOf(rs.get(0).sample.charger);
            long start = FastChargeTaperReplay.mono(rs.get(0).sample);
            ChargeEstimateHold hold = plugIn(start);
            FastChargeTaperReplay.Row firstBefore = null, firstAfter = null;
            for (FastChargeTaperReplay.Row r : rs) {
                long now = FastChargeTaperReplay.mono(r.sample);
                hold.observe("a", UT5, true, charger, r.sample.volts, r.sample.amps, now);
                boolean h = hold.holding("a", now);
                if (h) held++;
                check(!h || now - start < ChargeEstimateHold.MAX_HOLD_MS, "Session " + e.getKey() + ": held at " + r.minutes + " min");
                if (charger == STOCK) check(h == (now - start < ChargeEstimateHold.SETTLE_MS), "Session " + e.getKey() + ": a standard charge holds its first minute only");
                if (r.full != null && firstBefore == null) firstBefore = r;
                if (r.full != null && !h && firstAfter == null) firstAfter = r;
            }
            if (charger == FAST && firstBefore != null && firstBefore.actual != null && firstAfter != null && firstAfter.actual != null) {
                fast++;
                before += Math.abs(firstBefore.full - firstBefore.actual);
                after += Math.abs(firstAfter.full - firstAfter.actual);
                if (e.getKey() == 6) {
                    // Its first reading is a 1.98 A ramp, then a 14.7-minute recording gap: the current rate read 82 minutes long.
                    check(firstBefore.full - firstBefore.actual > 60, "Session 6 first showed the ramp's current rate");
                    check(Math.abs(firstAfter.full - firstAfter.actual) < 3, "Session 6 now first shows the model: " + (firstAfter.full - firstAfter.actual));
                }
            }
        }
        check(fast == 7, "Seven complete fast charges in the public set: " + fast);
        check(after < before / 3, String.format(Locale.US, "The first time shown is closer: mean |error| %.1f before, %.1f after", before / fast, after / fast));
        check(held > 0, "Some readings are held");
        System.out.println(String.format(Locale.US, "ChargeEstimateHold replay: %d fast charges, first time shown mean |error| %.1f -> %.1f min; %d of %d readings held",
                fast, before / fast, after / fast, held, rows.size()));
    }
}
