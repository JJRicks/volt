package com.jjricks.volt;

import java.util.Arrays;
import java.util.Locale;

/**
 * Riding mode's Focus frame (riding-mode plan PR 2): the arrangement and cells, connection state,
 * battery segments and band ticks, stopwatch and tenths digits, the range spot's fallbacks, a session
 * fed by the simulator's riding scripts, and the catalog English.
 */
public final class RidingFocusTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            arrangement();
            link();
            segments();
            digits();
            rideButton();
            range();
            session();
            scripts();
            priorDelivery();
            english();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingFocusTest: " + checks + " checks passed (arrangement, large text, link, segments, band ticks, "
                + "clock and tenths, RIDE label, range fallbacks, simulated session, scripts, prior delivery, English)");
    }

    private static void arrangement() {
        check(!RidingFocus.largeText(1.15f) && RidingFocus.largeText(1.3f) && RidingFocus.largeText(1.6f), "large text starts at 1.3");
        check(!RidingFocus.sideBySide(411, 891, false), "a portrait phone stacks");
        check(RidingFocus.sideBySide(891, 411, false), "a landscape phone sits side by side");
        check(RidingFocus.sideBySide(891, 411, true), "a short landscape window stays side by side at large text");
        check(!RidingFocus.sideBySide(900, 700, true), "a tall wide window stacks at large text");
        check(RidingFocus.sideBySide(900, 700, false), "the same window sits side by side at normal text");
        check(!RidingFocus.sideBySide(600, 600, false), "a square window stacks");
        check(RidingFocus.bottomRow(false).equals(Arrays.asList("tile_2", "tile_3", "tile_4")), "three bottom cells");
        check(RidingFocus.bottomRow(true).equals(Arrays.asList("tile_2", "tile_4")), "large text drops ride time, keeps battery temperature");
        check(RidingFocus.labelCap(true, 344) == RidingFocus.SHORT_LABEL_CAP, "a sideways phone caps labels lower");
        check(RidingFocus.labelCap(true, 604) == RidingFocus.LABEL_CAP && RidingFocus.labelCap(false, 344) == RidingFocus.LABEL_CAP, "elsewhere the usual cap");
        RidingLayout layout = new RidingLayout();
        check(RidingLayout.BATTERY.equals(layout.content(RidingLayout.FOCUS, RidingFocus.BATTERY_ROW)), "the battery row");
        check(RidingLayout.TRIP.equals(layout.content(RidingLayout.FOCUS, "tile_2")), "trip first in the bottom row");
        check(RidingLayout.RIDE_TIME.equals(layout.content(RidingLayout.FOCUS, RidingFocus.DROPPED_AT_LARGE_TEXT)), "ride time is the dropped cell");
        check(RidingLayout.BATTERY_TEMPERATURE.equals(layout.content(RidingLayout.FOCUS, "tile_4")), "battery temperature last (owner)");
        check(RidingLayout.RANGE_AT_PACE.equals(layout.content(RidingLayout.FOCUS, "highlight")), "Range at this pace is the highlight (Q25 A)");
    }

    private static void link() {
        check(RidingFocus.link(true, false) == RidingFocus.Link.CONNECTED, "ready is connected");
        check(RidingFocus.link(true, true) == RidingFocus.Link.CONNECTED, "ready wins");
        check(RidingFocus.link(false, true) == RidingFocus.Link.RECONNECTING, "reconnecting");
        check(RidingFocus.link(false, false) == RidingFocus.Link.NOT_CONNECTED, "not connected");
    }

    private static void segments() {
        check(RidingFocus.segments(72) == 7, "72 % fills 7");
        check(RidingFocus.segments(100) == 10 && RidingFocus.segments(140) == 10, "full");
        check(RidingFocus.segments(4) == 1 && RidingFocus.segments(.5) == 1, "any charge lights one");
        check(RidingFocus.segments(0) == 0 && RidingFocus.segments(Double.NaN) == 0 && RidingFocus.segments(-3) == 0, "empty or unknown");
        check(RidingFocus.segments(24) == 2 && RidingFocus.segments(11) == 1, "amber and red rows");
        check(RidingFocus.droppedBand(RidingValues.Band.GREEN, RidingValues.Band.AMBER), "green to amber ticks");
        check(RidingFocus.droppedBand(RidingValues.Band.AMBER, RidingValues.Band.RED), "amber to red ticks");
        check(RidingFocus.droppedBand(RidingValues.Band.GREEN, RidingValues.Band.RED), "a jump ticks");
        check(!RidingFocus.droppedBand(RidingValues.Band.RED, RidingValues.Band.AMBER), "charging up never ticks");
        check(!RidingFocus.droppedBand(null, RidingValues.Band.RED), "opening at red never ticks");
        check(!RidingFocus.droppedBand(RidingValues.Band.AMBER, null), "a lost reading never ticks");
    }

    private static void digits() {
        check(Arrays.equals(RidingFocus.clock(0), new long[]{0, 0, 0}), "zero");
        check(Arrays.equals(RidingFocus.clock(768_900), new long[]{0, 12, 48}), "12:48");
        check(Arrays.equals(RidingFocus.clock(3_768_000), new long[]{1, 2, 48}), "1:02:48");
        check(Arrays.equals(RidingFocus.clock(-5), new long[]{0, 0, 0}), "never negative");
        check(RidingFocus.clockText(768_900).equals("12:48") && RidingFocus.clockText(3_768_000).equals("1:02:48")
                && RidingFocus.clockText(65_000).equals("1:05"), "stopwatch text");
        check(Arrays.equals(RidingFocus.tenths(18.96), new long[]{19, 0}), "18.96 rounds to 19.0, never 18.10");
        check(Arrays.equals(RidingFocus.tenths(24.34), new long[]{24, 3}), "24.3");
        check(Arrays.equals(RidingFocus.tenths(0.04), new long[]{0, 0}), "0.0");
        check(Math.abs(RidingFocus.distance(16.09344, "mi") - 10) < 1e-9 && RidingFocus.distance(10, "km") == 10
                && Math.abs(RidingFocus.distance(16.09344, "dual") - 10) < 1e-9, "dual shows miles first");
    }

    private static void rideButton() {
        check(RidingFocus.rideLabelFits(120, 400), "a phone header keeps RIDE");
        check(!RidingFocus.rideLabelFits(120, 300), "a tight header shows the icon alone");
        check(!RidingFocus.rideLabelFits(120, 0), "unmeasured: icon");
    }

    private static RangeReadout.Value readout(RangeReadout.State state, Double primary, int decimals, boolean approx, boolean held, boolean stale) {
        return new RangeReadout.Value(state, primary, null, false, decimals, approx, false, held, stale);
    }

    private static void range() {
        RidingFocus.Range none = RidingFocus.range(RidingLayout.RANGE_AT_PACE, null, null, false, "mi");
        check(none.kind == RidingFocus.RangeKind.NONE && none.value == null, "nothing yet");
        RidingFocus.Range scooter = RidingFocus.range(RidingLayout.RANGE_AT_PACE, null, 38.4, false, "mi");
        check(scooter.kind == RidingFocus.RangeKind.SCOOTER && scooter.value == 24 && !scooter.approx, "falls back to the scooter's, labelled, in miles");
        check(RidingFocus.range(RidingLayout.RANGE_AT_PACE, null, 38.4, true, "km").dim, "a stale scooter estimate dims");
        check(RidingFocus.range(RidingLayout.RANGE_SCOOTER, readout(RangeReadout.State.LIVE, 9.4, 1, false, false, false), 38.4, false, "km").kind
                == RidingFocus.RangeKind.SCOOTER, "the scooter's content stays the scooter's");
        RidingFocus.Range live = RidingFocus.range(RidingLayout.RANGE_AT_PACE, readout(RangeReadout.State.LIVE, 9.4, 1, false, false, false), 38.4, false, "mi");
        check(live.kind == RidingFocus.RangeKind.AT_PACE && live.value == 9.4 && live.decimals == 1 && !live.dim, "live range wins");
        check(RidingFocus.range(RidingLayout.RANGE_AT_PACE, readout(RangeReadout.State.LIVE, 9.4, 1, false, true, false), 38.4, false, "mi").dim, "held dims");
        RidingFocus.Range history = RidingFocus.range(RidingLayout.RANGE_AT_PACE, readout(RangeReadout.State.NO_HISTORY, 21.0, 0, true, false, false), 38.4, false, "mi");
        check(history.kind == RidingFocus.RangeKind.VOLT && history.approx && history.decimals == 0, "Volt's distance estimate, approximate");
        check(RidingFocus.range(RidingLayout.RANGE_AT_PACE, readout(RangeReadout.State.ALMOST_EMPTY, null, 0, false, false, false), 38.4, false, "mi").kind
                == RidingFocus.RangeKind.ALMOST_EMPTY, "almost empty shows words");
        check(RidingFocus.range(RidingLayout.RANGE_AT_PACE, readout(RangeReadout.State.HIDDEN, null, 0, false, false, false), 38.4, false, "mi").kind
                == RidingFocus.RangeKind.SCOOTER, "hidden falls back");
        check(RidingFocus.range(RidingLayout.RANGE_AT_PACE, null, -1.0, false, "mi").kind == RidingFocus.RangeKind.NONE, "a bad estimate shows nothing");
    }

    /** Ticks the simulator and the session once a second, as the screen's refresh does. */
    private static long run(ScooterSimulation sim, Telemetry telemetry, RidingSession session, long from, long to) {
        for (long t = from; t <= to; t += 1000) {
            sim.tick(telemetry, t);
            session.feed(telemetry.metrics(), t, false, "km", true);
        }
        return to;
    }

    private static void session() {
        ScooterSimulation sim = new ScooterSimulation();
        sim.setScript(ScooterSimulation.RIDING);
        Telemetry telemetry = new Telemetry();
        RidingSession session = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        check(session.range() == null && session.lastReadingMs(0) == -1, "nothing before the first feed");
        session.setPrior(LiveRangeEstimator.Prior.synthetic(18, 1000, null));
        check(session.priorSet(), "prior set");
        long now = run(sim, telemetry, session, 1000, 240_000);
        RidingValues.Speed speed = session.values.speed(now);
        check(speed.kmh != null && speed.kmh > 10 && !speed.stale, "riding speed");
        check(!speed.tenths, "simulated speed has no 0x92 tenths flag (the screen allows tenths while simulating)");
        RidingValues.Power power = session.values.power(now);
        check(power.watts != null && power.direction != RidingValues.Direction.UNKNOWN, "power with a known direction");
        check(power.peakDrawW > 1000 && power.peakRegenW > 0, "draw and regen peaks: " + power.peakDrawW + " / " + power.peakRegenW);
        check(session.values.batteryBand(now) == RidingValues.Band.GREEN, "72 % is green");
        check(session.lastReadingMs(now) == now, "last seen is the newest reading");
        RangeReadout.Value range = session.range();
        check(range != null && range.state != RangeReadout.State.HIDDEN, "live range runs: " + (range == null ? null : range.state));
        RidingFocus.Range spot = RidingFocus.range(RidingLayout.RANGE_AT_PACE, range, 36.0, false, "km");
        check(spot.kind == RidingFocus.RangeKind.AT_PACE && spot.value != null && spot.value > 0, "Range at this pace shows: " + spot.kind);
        check(!session.takeBandDrop(), "no band drop yet");

        // The battery drops to red: one tick, then none.
        sim.setScript(ScooterSimulation.LOW_RED);
        now = run(sim, telemetry, session, now + 1000, now + 3000);
        check(session.values.batteryBand(now) == RidingValues.Band.RED, "11 % is red");
        check(session.takeBandDrop() && !session.takeBandDrop(), "one tick per drop");

        // A dropout: no more readings. Speed shows a dash after 6 s with its last value kept.
        long last = now;
        for (long t = now + 1000; t <= now + 8000; t += 1000) session.feed(telemetry.metrics(), t, false, "km", true);
        now += 8000;
        speed = session.values.speed(now);
        check(speed.kmh == null && speed.lastKmh != null && speed.stale, "speed older than 6 s is gone, last kept");
        check(session.lastReadingMs(now) == last, "last seen stays at the last reading");
        check(session.values.power(now).watts == null, "stale power shows no number");
    }

    private static void scripts() {
        ScooterSimulation sim = new ScooterSimulation();
        sim.setScript("unlock");
        check(sim.script().isEmpty(), "an unknown script is ignored");
        Telemetry telemetry = new Telemetry();
        sim.setScript(ScooterSimulation.WARNING);
        sim.tick(telemetry, 5000);
        check(telemetry.number("warning_code") == 14, "the warning script shows a code");
        sim.setScript(ScooterSimulation.STOPPED);
        sim.tick(telemetry, 6000);
        check(telemetry.number("speed_kmh") == 0 && telemetry.number("warning_code") == 0, "stopped");
        sim.setScript(ScooterSimulation.LOW_AMBER);
        sim.tick(telemetry, 7000);
        check(telemetry.number("battery_percent") == 24, "amber battery");
        sim.setScript("");
        sim.tick(telemetry, 8000);
        check(telemetry.number("battery_percent") == 78 && telemetry.number("speed_kmh") == 0, "no script: parked samples");
        Integer lock = sim.featureValue("lock");
        for (String id : new String[]{ScooterSimulation.RIDING, ScooterSimulation.STOPPED, ScooterSimulation.DROPOUT,
                ScooterSimulation.LOW_AMBER, ScooterSimulation.LOW_RED, ScooterSimulation.WARNING}) {
            sim.setScript(id);
            sim.tick(telemetry, 9000);
        }
        check(java.util.Objects.equals(lock, sim.featureValue("lock")), "scripts never touch the lock");
    }

    /** The learned prior lands whether the service answers at once (cached) or later; a late answer for a closed session or another scooter is dropped. */
    private static void priorDelivery() {
        LiveRangeEstimator.Prior prior = LiveRangeEstimator.Prior.synthetic(18, 1000, null);
        String[] active = {"p1"};
        RidingSession cached = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        cached.requestPrior(answer -> answer.accept(prior), () -> active[0]);
        check(cached.priorSet(), "a cached answer delivered at once lands");
        ScooterSimulation sim = new ScooterSimulation();
        sim.setScript(ScooterSimulation.RIDING);
        run(sim, new Telemetry(), cached, 1000, 120_000);
        check(RidingFocus.range(RidingLayout.RANGE_AT_PACE, cached.range(), 36.0, false, "km").kind == RidingFocus.RangeKind.AT_PACE,
                "with the cached prior, Range at this pace runs");

        java.util.List<java.util.function.Consumer<LiveRangeEstimator.Prior>> pending = new java.util.ArrayList<>();
        RidingSession later = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        later.requestPrior(pending::add, () -> active[0]);
        check(!later.priorSet() && pending.size() == 1, "nothing yet while the answer is computed");
        pending.get(0).accept(prior);
        check(later.priorSet(), "a later answer lands");

        RidingSession closed = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        closed.requestPrior(pending::add, () -> active[0]);
        closed.close();
        pending.get(1).accept(prior);
        check(closed.closed() && !closed.priorSet(), "a late answer for a closed session is dropped");

        RidingSession switched = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        switched.requestPrior(pending::add, () -> active[0]);
        active[0] = "p2";
        pending.get(2).accept(prior);
        check(!switched.priorSet(), "a late answer after a scooter switch is dropped");
        RidingSession gone = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        gone.requestPrior(answer -> answer.accept(prior), () -> null);
        check(!gone.priorSet(), "no active session (the activity is gone): dropped");

        // MainActivity makes the session the open one before asking, so a cached answer's guard sees it (Codex, #254).
        String main = read("src/com/jjricks/volt/MainActivity.java");
        int start = main.indexOf("private void openRidingSession(){");
        int end = main.indexOf("\n    }", start);
        check(start >= 0 && end > start, "openRidingSession exists");
        String body = main.substring(start, end);
        int assign = body.indexOf("riding=session;"), request = body.indexOf("session.requestPrior(");
        check(assign >= 0 && request > assign, "riding is assigned before the prior is requested");
        check(!main.contains("riding=newRidingSession()"), "no caller assigns the session after its request");
    }

    private static String read(String path) {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError("read " + path, e); }
    }

    private static void english() {
        check(T.ridingRideButton().equals("RIDE"), "RIDE");
        check(T.ridingStatusConnectedMode("SPORT").equals("SPORT · Connected"), "mode and connection, no lock state");
        check(T.ridingStatusReconnecting().equals("Reconnecting…"), "reconnecting");
        check(T.ridingStatusNotConnectedSeen("0:12").equals("Not connected · last seen 0:12"), "last seen");
        check(T.ridingLabelRangeAtPace().equals("Range at this pace"), "Q25 A label");
        check(T.ridingLabelRangeScooter().equals("Scooter's range"), "the scooter's estimate is labelled");
        check(T.ridingLabelRideDistance().equals("Ride") && T.ridingLabelTrip().equals("Trip"), "Q15 B labels");
        check(T.ridingLabelBatteryTemperatureShort().equals("Batt. temp"), "abbreviated rung, whole words");
        check(T.ridingPowerRegenPeak("320 W").equals("regen · peak 320 W"), "regen, not braking (owner)");
        check(T.ridingWarningCode("14").equals("Scooter code 14") && T.ridingWarningCodeShort("14").equals("Code 14"), "warning code");
        check(T.ridingNoValue().equals("—"), "the dash");
    }
}
