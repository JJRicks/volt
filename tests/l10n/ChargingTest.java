package com.jjricks.volt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * B5's own suite (the charging domain). With English installed it checks that every converted text
 * reads exactly as before; with a source that answers every id with its marked key it checks that the
 * text now comes from the catalog, while the English ids the code compares, stores or logs
 * (ChargingEstimate.label and LOW_CURRENT, ChargeMeasureWindow.Decision.reason,
 * BatteryCapacityEstimate.Result.reason and shortReason) stay English. It also renders the past-charges
 * plural and the time-limit plural (english-fix pending) at many counts.
 */
public final class ChargingTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;

    public static void main(String[] args) {
        english();
        T.install(echo());
        try {
            echoed();
        } finally {
            T.install(null);
        }
        plurals();
        System.out.println("ChargingTest: " + checks + " checks passed (alerts, estimate labels, measuring and capacity reasons and "
                + "schedule refusals: English unchanged, catalog-backed, ids English)");
    }

    private static T.Source echo() {
        return new T.Source() {
            public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›"; }
            public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "›" : null; }
            public String language() { return "xx"; }
        };
    }

    private static void english() {
        same("80%", ChargeAlertPolicy.levelText(80), "level");
        same("Full", ChargeAlertPolicy.levelText(100), "full level");
        same("Charging to 80%", ChargeAlertPolicy.watchTitle(80), "watch title");
        same("Charged to 80%", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.CHARGE, 80), "charge title");
        same("null is at 80%.", ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.CHARGE, null, 80), "a missing name prints as before");
        same("Volt lost the connection to null. Open Volt to keep watching this charge.",
                ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.PAUSED, null, 80), "a missing name in the paused text");

        ChargingEstimate.Result low = ChargingEstimate.calculate(value(1d), value(53.4), value(-0.01), value(50d), value(15.3), value(80d));
        same(ChargingEstimate.LOW_CURRENT, low.label, "low current keeps its id");
        same("Low current", low.labelMsg.english(), "low current stays verbatim until B13");
        check(low.labelMsg.id() == Msg.VERBATIM, "low current is a verbatim message");
        ChargingEstimate.Result rate = ChargingEstimate.calculate(value(1d), value(53.4), value(-2d), value(50d), value(15.3), value(80d));
        same("Current rate", rate.label, "current rate label");
        check(rate.labelMsg.id() == T.CHARGING_ESTIMATE_CURRENT_RATE, "current rate is its key");

        ChargeMeasureWindow.Decision d = started().evaluate(MONO + 60_000L, "other", true, 1, false);
        same("The selected scooter changed while measuring", d.reason, "measure reason English");
        check(d.message.id() == T.CHARGING_MEASURE_SCOOTER_CHANGED && d.step == ChargeMeasureWindow.Step.NOT_SET, "measure reason key");
        ChargeMeasureWindow.Decision armed = started().evaluate(MONO + 60_000L, "one", true, 1, true);
        check(armed.reason == null && armed.message == null, "arming has no reason");

        same("Gauge jumped", BatteryCapacityEstimate.shortReason(BatteryCapacityEstimate.GAUGE_JUMP), "short reason English");
        check(BatteryCapacityEstimate.shortReasonMsg(BatteryCapacityEstimate.GAUGE_JUMP).id() == T.CHARGING_CAPACITY_REASON_GAUGE_JUMPED, "short reason key");
        check(BatteryCapacityEstimate.shortReasonMsg(null) == null && BatteryCapacityEstimate.shortReason(null) == null, "no reason stays null");
        same("Insufficient data", BatteryCapacityEstimate.shortReason("unknown"), "unknown reason");
    }

    private static void echoed() {
        same("‹charging_alert_level_full›", ChargeAlertPolicy.levelText(100), "full level from the catalog");
        same("‹fmt_percent›", ChargeAlertPolicy.levelText(80), "level through fmt_percent");
        same("‹charging_alert_watch_full›", ChargeAlertPolicy.watchTitle(100), "watch full title");
        same("‹charging_alert_watch_title›", ChargeAlertPolicy.watchTitle(80), "watch title");
        same("‹charging_alert_title_full›", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.FULL, 100), "full title");
        same("‹charging_alert_title_low›", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.LOW, 20), "low title");
        same("‹charging_alert_title_charged›", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.CHARGE, 80), "charged title");
        same("‹charging_alert_title_paused›", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.PAUSED, 80), "paused title");
        same("‹charging_alert_title_paused›", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.TIME_LIMIT, 80), "time-limit title");
        same("‹charging_alert_text_paused›", ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.PAUSED, "UT5 MAX", 80), "paused text");
        same("‹charging_alert_text_time_limit›", ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.TIME_LIMIT, "UT5 MAX", 80), "time-limit text");
        same("‹charging_alert_text_level›", ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.LOW, "UT5 MAX", 19), "level text");
        // Deferred (G1 cannot model the goal chosen on an earlier line and its upper-casing): still English.
        same("42% now · alert at 80%", ChargeAlertPolicy.watchText(42, true, 80), "the watch text stays English");

        ChargingEstimate.Result waiting = ChargingEstimate.calculate(new ChargingEstimate.Value(1d, false, 100), value(53.4), value(-2d),
                value(50d), value(15.3), value(80d));
        same("Waiting for data", waiting.label, "the label stays English");
        same("‹charging_estimate_waiting_data›", waiting.labelMsg.render(), "the display label is the catalog's");
        same("Waiting for data", waiting.labelMsg.english(), "and its English is the label");
        same("Waiting for a fresh charging state.", waiting.status, "the status stays English (deferred to B13)");
        ChargingEstimate.Result low = ChargingEstimate.calculate(value(1d), value(53.4), value(-0.01), value(50d), value(15.3), value(80d));
        check(ChargingEstimate.LOW_CURRENT.equals(low.label), "LOW_CURRENT still compares");

        ChargeMeasureWindow.Decision d = started().evaluate(MONO + 13 * 60_000L, "one", false, null, false);
        same("The scooter disconnected while measuring", d.reason, "measure reason stays English");
        same("‹charging_measure_disconnected›", d.message.render(), "measure reason for display");

        same("Clock changed", BatteryCapacityEstimate.shortReason("recording clock changed"), "short reason stays English");
        same("‹charging_capacity_reason_clock›", BatteryCapacityEstimate.shortReasonMsg("recording clock changed").render(), "short reason for display");

        Rig r = new Rig();
        r.controller.setDoneTime(24, 0, r.results::add);
        same("‹charging_schedule_choose_valid_time›", r.last(), "invalid time refusal");
        r.controller.setManualEnabled(true, r.results::add);
        same("‹charging_schedule_choose_start_first›", r.last(), "no start refusal");
        r.c.estimateMinutes = null;
        r.controller.setDoneEnabled(true, r.results::add);
        same("‹charging_schedule_wait_estimate›", r.last(), "no estimate refusal");
        // The View's reasons stay English until their callers take a Msg (deferred, see the ledger header).
        same("Connect a charger first", viewWithoutCharger().doneByBlockedReason, "a View reason stays English");
    }

    private static void plurals() {
        for (long n : new long[]{0, 1, 2, 3, 11, 21, 100, 101}) {
            same("From " + n + " past charge" + (n == 1 ? "" : "s"), M.chargingEstimatePastCharges(n, n).english(), "past charges " + n);
            same("Volt stopped watching after " + n + " hours. Open Volt to keep watching this charge.",
                    M.chargingAlertTextTimeLimit(n, n).english(), "time limit " + n);
        }
    }

    private static ChargingEstimate.Value value(Double n) { return new ChargingEstimate.Value(n, true, 100); }

    private static ChargeMeasureWindow started() {
        ChargeMeasureWindow w = new ChargeMeasureWindow();
        w.start("one", 7 * 60, MONO, WALL, WALL + 6 * 60 * 60_000L);
        return w;
    }

    private static ChargeScheduleController.View viewWithoutCharger() {
        Rig r = new Rig();
        r.c.charging = 0;
        return r.controller.view();
    }

    private static final class Rig {
        final java.util.Map<String, String> saved = new java.util.HashMap<>();
        final ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        final List<String> results = new ArrayList<>();
        final ChargeScheduleController controller;
        Rig() {
            c.scope = "owner-A"; c.ready = true; c.generation = 1; c.now = WALL; c.mono = MONO; c.sampleMs = 1;
            c.charging = 1; c.enabled = 0; c.soc = 50d; c.target = 80d; c.estimateMinutes = 60d;
            c.zone = TimeZone.getTimeZone("UTC");
            controller = new ChargeScheduleController(new ChargeScheduleController.Store() {
                public String read(String scope) { return saved.get(scope); }
                public void write(String scope, String value) { saved.put(scope, value); }
            }, new ChargeScheduleController.Driver() {
                public ChargeScheduleController.Context context() { return c; }
                public void clock(Consumer<Boolean> done) { done.accept(true); }
                public void time(long wallMs, int offsetSeconds, Consumer<Boolean> done) { c.wireStart = wallMs / 1000 + offsetSeconds; done.accept(true); }
                public void enabled(boolean enabled, Consumer<Boolean> done) { c.enabled = enabled ? 1 : 0; done.accept(true); }
            });
        }
        String last() { return results.isEmpty() ? null : results.get(results.size() - 1); }
    }

    private static void same(String expected, String actual, String what) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(what + ": expected \"" + expected + "\" but was \"" + actual + "\" under " + Locale.getDefault());
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
