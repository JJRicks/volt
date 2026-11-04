package com.jjricks.volt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * G5 characterization for B5 (the charging domain), committed before the batch changes anything.
 *
 * It pins the English the batch moves into templates and plurals, each against the original
 * concatenation written out here, under five phone locales (the numbers are whole numbers joined by
 * string concatenation today, so no locale may change a digit):
 *   - ChargeAlertPolicy's notification wording: levelText, watchTitle, watchText (which stays), and
 *     alertTitle and alertText for every event, level and a few scooter names;
 *   - ChargingEstimate's short labels on every calculate() branch with its status, and the labels of
 *     learned-curve and reference estimates ("From 1 past charge", "From 2 past charges", ...), with a
 *     SHA-256 of the whole label and status corpus;
 *   - ChargeMeasureWindow's reasons for not arming;
 *   - BatteryCapacityEstimate.shortReason for every rejection reason;
 *   - the ChargeScheduleController refusals the batch moves (the ones handed straight to the caller).
 */
public final class ChargingTextTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;
    private static final String[] SCOOTERS = {"UT5 MAX", "", "Kid's \"Scooter\" %s 50%", "تجربة"};
    private static final int[] LEVELS = {0, 5, 50, 55, 80, 95, 99, 100, 120};

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        String corpus = null;
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG"),
                    new Locale("hi", "IN")}) {
                Locale.setDefault(phone);
                alerts();
                estimates();
                String next = curves();
                check(corpus == null || corpus.equals(next), "the estimate corpus is the same under " + phone);
                corpus = next;
                measureWindow();
                capacityReasons();
                scheduleCompletions();
            }
        } finally {
            Locale.setDefault(saved);
        }
        same("98a23bcf76e3d7eb", sha(corpus).substring(0, 16), "the estimate label and status corpus");
        System.out.println("ChargingTextTest: " + checks + " checks passed (alert wording, estimate labels and statuses, "
                + "measuring and capacity reasons under 5 phone locales)");
    }

    private static void alerts() {
        for (int level : LEVELS) {
            same(level >= 100 ? "Full" : level + "%", ChargeAlertPolicy.levelText(level), "levelText " + level);
            same(level >= 100 ? "Charging to full" : "Charging to " + level + "%", ChargeAlertPolicy.watchTitle(level), "watchTitle " + level);
            for (Integer percent : new Integer[]{null, 0, 42, 100}) for (boolean fresh : new boolean[]{true, false}) {
                String goal = level >= 100 ? "alert when full" : "alert at " + level + "%";
                String expected = percent == null ? Character.toUpperCase(goal.charAt(0)) + goal.substring(1)
                        : (fresh ? percent + "% now · " : "Last seen " + percent + "% · ") + goal;
                same(expected, ChargeAlertPolicy.watchText(percent, fresh, level), "watchText " + percent + " " + fresh + " " + level);
            }
            for (ChargeAlertPolicy.Event event : ChargeAlertPolicy.Event.values()) {
                String title = event == ChargeAlertPolicy.Event.FULL ? "Fully charged" : event == ChargeAlertPolicy.Event.LOW ? "Battery low"
                        : event == ChargeAlertPolicy.Event.CHARGE ? "Charged to " + level + "%" : "Charge alert paused";
                same(title, ChargeAlertPolicy.alertTitle(event, level), "alertTitle " + event + " " + level);
                for (String scooter : SCOOTERS) {
                    String text = event == ChargeAlertPolicy.Event.PAUSED
                            ? "Volt lost the connection to " + scooter + ". Open Volt to keep watching this charge."
                            : event == ChargeAlertPolicy.Event.TIME_LIMIT
                            ? "Volt stopped watching after 12 hours. Open Volt to keep watching this charge."
                            : scooter + " is at " + level + "%.";
                    same(text, ChargeAlertPolicy.alertText(event, scooter, level), "alertText " + event + " " + scooter + " " + level);
                }
            }
        }
        same("Charged to 80%", ChargeAlertPolicy.alertTitle(ChargeAlertPolicy.Event.CHARGE, 80), "a charge alert title");
        same("UT5 MAX is at 80%.", ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.CHARGE, "UT5 MAX", 80), "a charge alert text");
        same("Volt stopped watching after 12 hours. Open Volt to keep watching this charge.",
                ChargeAlertPolicy.alertText(ChargeAlertPolicy.Event.TIME_LIMIT, "UT5 MAX", 80), "the time limit says 12 hours");
        same("42% now · alert at 80%", ChargeAlertPolicy.watchText(42, true, 80), "a live watch text");
    }

    private static ChargingEstimate.Value value(Double n) { return new ChargingEstimate.Value(n, true, 100); }
    private static ChargingEstimate.Value stale(Double n) { return new ChargingEstimate.Value(n, false, 100); }

    private static void estimate(String label, String status, ChargingEstimate.Result r, String what) {
        same(label, r.label, what + " label");
        same(status, r.status, what + " status");
    }

    private static void estimates() {
        ChargingEstimate.Value on = value(1d), volts = value(53.4), amps = value(-2d), soc = value(50d), ah = value(15.3);
        estimate("Waiting for data", "Waiting for a fresh charging state.",
                ChargingEstimate.calculate(stale(1d), volts, amps, soc, ah, value(80d)), "no charging state");
        estimate("Not charging", "Not charging.", ChargingEstimate.calculate(value(0d), volts, amps, soc, ah, value(80d)), "not charging");
        estimate("Waiting for readings", "Waiting for paired voltage and current readings.",
                ChargingEstimate.calculate(on, stale(53.4), amps, soc, ah, value(80d)), "no voltage");
        estimate("Readings out of range", "Charging measurements are outside a usable range.",
                ChargingEstimate.calculate(on, value(1e308), value(-10d), soc, ah, value(80d)), "infinite power");
        estimate("Low current", "Charging current is too low for a useful time estimate.",
                ChargingEstimate.calculate(on, volts, value(-0.01), soc, ah, value(80d)), "low current");
        estimate("Capacity unknown", "A fresh rated battery capacity is needed for a time estimate.",
                ChargingEstimate.calculate(on, volts, amps, soc, stale(15.3), value(80d)), "no capacity");
        estimate("Waiting for battery %", "Waiting for a fresh battery percentage.",
                ChargingEstimate.calculate(on, volts, amps, value(120d), ah, value(80d)), "no battery percent");
        estimate("No estimate", "Charging time is outside a usable range.",
                ChargingEstimate.calculate(on, volts, value(-0.1), soc, value(1e308), value(80d)), "no estimate");
        estimate("Current rate", "At the current rate; taper and balancing can lengthen charging. Charge cutoff unknown; the 100% time assumes charging continues.",
                ChargingEstimate.calculate(on, volts, amps, soc, ah, stale(80d)), "unknown cutoff");
        estimate("Current rate", "At the current rate; taper and balancing can lengthen charging. The 100% time assumes a higher charge limit.",
                ChargingEstimate.calculate(on, volts, amps, soc, ah, value(80d)), "a lower cutoff");
        estimate("Current rate", "At the current rate; taper and balancing can lengthen charging.",
                ChargingEstimate.calculate(on, volts, amps, soc, ah, value(100d)), "a full cutoff");
        same(ChargingEstimate.LOW_CURRENT, "Low current", "the low-current id");
    }

    private static ChargeSessionStore.Value sample(double value, long mono) { return new ChargeSessionStore.Value(value, mono, 0x72); }

    private static void record(ChargeSessionStore store, ChargeSessionStore.Charger charger) throws Exception {
        store.start("p", charger, WALL, MONO);
        for (int i = 0; i <= 180; i++) {
            long t = i * 20_000L, mono = MONO + t;
            double soc = 40 + i / 3;
            ChargeSessionStore.Frame f = new ChargeSessionStore.Frame(WALL + t, mono, sample(soc == 100 ? 0 : 1, mono), sample(soc, mono),
                    sample(53.4, mono), sample(-2, mono), sample(23, mono));
            if (soc == 100) store.sampleCompletion(f); else store.sample(f);
        }
        store.stop(ChargeSessionStore.Stop.COMPLETE);
    }

    /** Learned-curve and reference labels over a grid; returns the whole label and status corpus. */
    private static String curves() throws Exception {
        StringBuilder corpus = new StringBuilder();
        TreeSet<String> labels = new TreeSet<>();
        for (ChargeSessionStore.Charger charger : new ChargeSessionStore.Charger[]{ChargeSessionStore.Charger.STOCK, ChargeSessionStore.Charger.HIGH_SPEED}) {
            for (int sessions = 1; sessions <= 3; sessions++) {
                File dir = Files.createTempDirectory("b5-charging-text-").toFile();
                ChargeSessionStore store = new ChargeSessionStore(dir);
                for (int s = 0; s < sessions; s++) record(store, charger);
                ChargeSessionStore.Curve curve = store.snapshot().curves.get(ChargeSessionStore.key("p", charger));
                check(curve != null && curve.qualifiedSessions == sessions, "the fixture records " + sessions + " charge(s)");
                for (double soc : new double[]{30, 45, 60, 85, 95, 99}) for (double amps : new double[]{0.5, 2, 4}) for (Double cut : new Double[]{null, 80d, 100d}) {
                    ChargingEstimate.Result base = ChargingEstimate.calculate(value(1d), value(53.4), value(-amps), value(soc), value(15.3),
                            cut == null ? stale(80d) : value(cut));
                    ChargingEstimate.Result observed = ChargingEstimate.withObservedCurve(base, value(soc), curve);
                    labels.add(observed.label);
                    corpus.append(observed.label).append('|').append(observed.status).append('\n');
                    for (int product : new int[]{Ut5MaxStockChargeReference.PRODUCT_ID, 1}) {
                        ChargingEstimate.Result shown = ChargingEstimate.forScooter(base, value(soc), value(15.3), product, charger, curve);
                        labels.add(shown.label);
                        corpus.append(shown.label).append('|').append(shown.status).append('\n');
                    }
                }
                delete(dir);
            }
        }
        same(Arrays.asList("Charger mismatch · rate estimate", "Current rate", "From 1 past charge", "From 2 past charges",
                "From 3 past charges", "High-speed reference", "Stock reference").toString(), labels.toString(), "the labels the grid shows");
        return corpus.toString();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void measureWindow() {
        long minute = 60_000L;
        ChargeMeasureWindow w = started();
        reason("The selected scooter changed while measuring", w.evaluate(MONO + minute, "other", true, 1, false), "another scooter");
        reason("The phone clock changed while measuring", started().evaluate(MONO - 1, "one", true, 1, false), "the clock went back");
        reason("Charging stopped before the schedule was set", started().evaluate(MONO + minute, "one", true, 0, false), "charging stopped");
        reason("Charging readings stopped while measuring", started().evaluate(MONO + 13 * minute, "one", true, null, false), "readings stopped");
        reason("The scooter disconnected while measuring", started().evaluate(MONO + 13 * minute, "one", false, null, false), "disconnected");
        reason(null, started().evaluate(MONO + minute, "one", true, 1, true), "measured");
        reason(null, started().evaluate(MONO + 13 * minute, "one", true, 1, false), "cautious");
        reason(null, started().evaluate(MONO + minute, "one", true, 1, false), "waiting");
    }

    private static ChargeMeasureWindow started() {
        ChargeMeasureWindow w = new ChargeMeasureWindow();
        w.start("one", 7 * 60, MONO, WALL, WALL + 6 * 60 * 60_000L);
        return w;
    }

    private static void reason(String expected, ChargeMeasureWindow.Decision d, String what) {
        checks++;
        if (expected == null ? d.reason != null : !expected.equals(d.reason))
            throw new AssertionError(what + ": expected " + expected + " but was " + d.reason + " under " + Locale.getDefault());
    }

    private static void capacityReasons() {
        String[][] pairs = {
                {"recording clock changed", "Clock changed"},
                {"recording has gaps or recovery", "Recording gaps"},
                {"current direction changed during the ride", "Current direction changed"},
                {"record at least 1 minute", "Under 1 minute"},
                {"battery end readings are missing", "Battery ends missing"},
                {"battery drop is below 5 percentage points", "Drop under 5%"},
                {"electrical coverage is below 95%", "Coverage under 95%"},
                {"at least 30 seconds of measured movement is needed", "Too little movement"},
                {"measured energy is unavailable", "No energy reading"},
                {"net measured energy is not positive", "No net energy"},
                {"endpoint power and battery readings are missing", "End readings missing"},
                {"power and battery readings do not align with both ride ends", "Ends misaligned"},
                {"battery gauge rebounded or rose while recording", "Battery gauge rose"},
                {BatteryCapacityEstimate.GAUGE_JUMP, "Gauge jumped"},
                {"insufficient data", "Insufficient data"},
                {"anything else", "Insufficient data"}};
        for (String[] pair : pairs) same(pair[1], BatteryCapacityEstimate.shortReason(pair[0]), "short reason for " + pair[0]);
        checks++;
        if (BatteryCapacityEstimate.shortReason(null) != null) throw new AssertionError("no reason stays null");
        same("battery gauge jumped during the ride", BatteryCapacityEstimate.GAUGE_JUMP, "the gauge-jump id");
    }

    /** A controller whose scooter answers every write at once. */
    private static final class Rig {
        final java.util.Map<String, String> saved = new java.util.HashMap<>();
        final ChargeScheduleController.Context c = new ChargeScheduleController.Context();
        final List<String> results = new ArrayList<>();
        final ChargeScheduleController controller;
        Rig() {
            c.scope = "owner-A"; c.ready = true; c.generation = 1; c.now = WALL; c.mono = MONO; c.sampleMs = 1;
            c.charging = 1; c.enabled = 0; c.soc = 50d; c.target = 80d; c.estimateMinutes = 60d;
            c.zone = java.util.TimeZone.getTimeZone("UTC");
            controller = new ChargeScheduleController(new ChargeScheduleController.Store() {
                public String read(String scope) { return saved.get(scope); }
                public void write(String scope, String value) { saved.put(scope, value); }
            }, new ChargeScheduleController.Driver() {
                public ChargeScheduleController.Context context() { return c; }
                public void clock(java.util.function.Consumer<Boolean> done) { done.accept(true); }
                public void time(long wallMs, int offsetSeconds, java.util.function.Consumer<Boolean> done) { c.wireStart = wallMs / 1000 + offsetSeconds; done.accept(true); }
                public void enabled(boolean enabled, java.util.function.Consumer<Boolean> done) { c.enabled = enabled ? 1 : 0; done.accept(true); }
            });
        }
        String last() { return results.isEmpty() ? null : results.get(results.size() - 1); }
    }

    private static void scheduleCompletions() {
        Rig r = new Rig();
        r.controller.setDoneTime(24, 0, r.results::add);
        same("Choose a valid time", r.last(), "an invalid finish time");
        r.controller.setManualStart(WALL - 1, r.results::add);
        same("Choose a charge start within 48 hours", r.last(), "a start in the past");
        r.c.wholeHours = true;
        r.controller.setManualStart(WALL + 90 * 60_000L, r.results::add);
        same("Choose a whole hour within 48 hours", r.last(), "a start off the hour");
        r.c.wholeHours = false;
        r.controller.setManualEnabled(true, r.results::add);
        same("Choose Charge start first", r.last(), "no start time yet");
        r.c.estimateMinutes = null;
        r.controller.setDoneEnabled(true, r.results::add);
        same("Wait for a charging time estimate", r.last(), "no estimate yet");
        r.c.estimateMinutes = 48 * 60 + 1d;
        r.controller.setDoneEnabled(true, r.results::add);
        same("Charging time is outside a usable range", r.last(), "an estimate past two days");
        r.c.estimateMinutes = 47 * 60d;
        r.controller.setDoneEnabled(true, r.results::add);
        same("Charging now — not enough time to wait before the deadline", r.last(), "no time to wait");

        Rig limit = new Rig();
        limit.c.soc = 80d; limit.c.estimateMinutes = null;
        limit.controller.setDoneEnabled(true, limit.results::add);
        same("Battery is already at its charge limit", limit.last(), "already at the limit");

        Rig armed = new Rig();
        armed.controller.setDoneEnabled(true, armed.results::add);
        checks++;
        if (armed.last() != null || !armed.controller.view().doneByEnabled) throw new AssertionError("Charge done by arms: " + armed.last());
        armed.c.charging = 0; armed.c.soc = 51d;
        armed.controller.setDoneEnabled(true, armed.results::add);
        same("A fresh charging estimate is needed; disable Charge done by and charge briefly", armed.last(), "a paused charge needs a new estimate");
    }

    private static String sha(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
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
