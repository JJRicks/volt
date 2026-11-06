package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Riding mode's Halo and Trace frames (riding-mode plan PR 3): the speed ring's scale and angles,
 * the live trace's runs, axes and neutral unknown-direction points, Halo's strip at large text, the
 * frame picker (only while stopped, Focus still the default), the frames' spots with no lock, and
 * the catalog English.
 */
public final class RidingFramesTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    public static void main(String[] args) throws IOException {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            ring();
            trace();
            frames();
            picker();
            english();
            source();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingFramesTest: " + checks + " checks passed (ring scale and angles, trace runs, gaps and axes, "
                + "unknown direction, frames and spots, no lock, strip at large text, picker, English, source)");
    }

    private static void ring() {
        check(RidingFrames.ringTop(null, "mi") == 20 && RidingFrames.ringTop(null, "km") == 30, "the ring starts at 20 mph or 30 km/h");
        check(RidingFrames.ringTop(0.0, "mi") == 20 && RidingFrames.ringTop(Double.NaN, "km") == 30, "no speed keeps the floor");
        check(RidingFrames.ringTop(20 * RidingFocus.KM_PER_MI, "mi") == 20, "exactly 20 mph stays at 20");
        check(RidingFrames.ringTop(35.6, "mi") == 25, "22.1 mph rounds up to 25");
        check(RidingFrames.ringTop(35.6, "km") == 40, "35.6 km/h rounds up to 40");
        check(RidingFrames.ringTop(40.0, "km") == 40, "a round number stays");
        check(RidingFrames.ringTop(12.0, "km") == 30, "a slow ride keeps the floor");
        check(RidingFrames.ringTop(35.6, "dual") == 25, "dual units read in miles");
        check(RidingFrames.share(null, 25) == 0 && RidingFrames.share(-3.0, 25) == 0 && RidingFrames.share(5.0, 0) == 0, "missing or unusable is empty");
        check(RidingFrames.share(40.0, 25) == 1, "the share never passes the top");
        check(near(RidingFrames.share(12.5, 25), .5), "half the scale");
        check(RidingFrames.ringAngle(0) == 135 && RidingFrames.ringAngle(1) == 405 && RidingFrames.ringAngle(.5f) == 270, "a 270° sweep from 135°");
        check(RidingFrames.ringAngle(2) == 405 && RidingFrames.ringAngle(-1) == 135, "angles clamp to the sweep");
    }

    private static void trace() {
        RidingValues v = new RidingValues();
        for (long t = 0; t <= 4000; t += 1000) v.sample(RidingValues.SPEED_KEY, 20 + t / 1000.0, 0x92, t);
        // Eight seconds without a reading, then two more: a gap, drawn as a gap.
        v.sample(RidingValues.SPEED_KEY, 12, 0x92, 12000);
        v.sample(RidingValues.SPEED_KEY, 13, 0x92, 13000);
        List<RidingValues.TracePoint> speeds = v.speedTrace(13000, RidingFrames.TRACE_WINDOW_MS);
        List<List<RidingValues.TracePoint>> runs = RidingFrames.runs(speeds);
        check(speeds.size() == 7 && runs.size() == 2, "a dropout splits the line in two");
        check(runs.get(0).size() == 5 && runs.get(1).size() == 2, "each run keeps its own points");
        check(runs.get(1).get(0).value == 12, "the second run starts at the first reading after the gap, never at zero");
        check(RidingFrames.runs(java.util.Collections.<RidingValues.TracePoint>emptyList()).isEmpty(), "no points, no runs");
        check(RidingFrames.fastest(speeds, null) == 24, "the fastest point");
        check(RidingFrames.fastest(speeds, 35.6) == 35.6, "the scooter's trip max wins when higher");
        check(RidingFrames.fastest(java.util.Collections.<RidingValues.TracePoint>emptyList(), null) == null, "nothing seen");
        // A minute's window: older points leave the chart.
        check(v.speedTrace(72500, RidingFrames.TRACE_WINDOW_MS).size() == 1, "only the last minute shows");
        check(RidingFrames.x(13000, 13000) == 1 && RidingFrames.x(13000 - 60000, 13000) == 0, "now at the right, a minute ago at the left");
        check(near(RidingFrames.x(13000 - 30000, 13000), .5) && RidingFrames.x(0, 120000) == 0, "half way, and clamped");
        check(RidingFrames.x(14000, 13000) == 1, "nothing runs past now");

        RidingValues.PowerScale scale = new RidingValues.PowerScale();
        check(near(RidingFrames.powerY(0, scale), RidingFrames.TRACE_ZERO), "zero power on the zero line");
        check(near(RidingFrames.powerY(scale.drawW, scale), 0) && near(RidingFrames.powerY(scale.drawW * 3, scale), 0), "full draw at the top, clamped");
        check(near(RidingFrames.powerY(scale.drawW / 2, scale), RidingFrames.TRACE_ZERO / 2), "half draw half way up");
        check(near(RidingFrames.powerY(-scale.regenW, scale), 1), "full regen at the bottom");
        check(RidingFrames.powerY(-100, scale) > RidingFrames.TRACE_ZERO, "regen falls below the zero line");
        check(near(RidingFrames.speedY(0, 25, "mi"), 1) && near(RidingFrames.speedY(25 * RidingFocus.KM_PER_MI, 25, "mi"), 0), "speed from the bottom to the ring's top");

        // Power without a known 0x79 direction is a magnitude, drawn neutral; with one, regen stays negative.
        RidingValues unsigned = new RidingValues();
        unsigned.sample(RidingValues.POWER_KEY, -300, 0x72, 1000);
        RidingValues.TracePoint p = unsigned.powerTrace(1000, RidingFrames.TRACE_WINDOW_MS).get(0);
        check(p.directionUnknown && p.value == 300, "an unsigned reading is a neutral magnitude, never regen");
        RidingValues signed = new RidingValues();
        signed.sample(RidingValues.SIGN_KEY, 0, 0x79, 500);
        signed.sample(RidingValues.POWER_KEY, -300, 0x72, 1000);
        signed.sample(RidingValues.POWER_KEY, 420, 0x72, 3000);
        List<RidingValues.TracePoint> powers = signed.powerTrace(3000, RidingFrames.TRACE_WINDOW_MS);
        check(!powers.get(0).directionUnknown && powers.get(0).value == -300, "a confirmed regen stays below zero");
        check(!powers.get(1).directionUnknown && powers.get(1).value == 420, "a confirmed draw above it");
        check(signed.speedTrace(3000, RidingFrames.TRACE_WINDOW_MS).isEmpty() || !signed.speedTrace(3000, 60000).get(0).directionUnknown,
                "speed points are never marked as an unknown direction");
    }

    private static void frames() {
        RidingLayout layout = new RidingLayout();
        check(RidingLayout.FOCUS.equals(layout.frame()) && RidingLayout.FOCUS.equals(RidingLayout.DEFAULT_FRAME), "Focus stays the default (owner, Q10)");
        check(Arrays.asList(RidingLayout.FRAMES).equals(Arrays.asList("focus", "halo", "trace")), "three frames, Focus first");
        RidingLayout.Frame halo = RidingLayout.frameDef(RidingLayout.HALO), trace = RidingLayout.frameDef(RidingLayout.TRACE);
        check(halo.spot("ring") != null && RidingLayout.RING_SPEED.equals(layout.content(RidingLayout.HALO, "ring")), "Halo's ring shows speed");
        for (String s : RidingFrames.HALO_STRIP) check(halo.spot(s) != null && halo.spot(s).kind == RidingLayout.Kind.STRIP, "Halo strip spot " + s);
        for (String s : RidingFrames.HALO_CHIPS) check(halo.spot(s) != null && halo.spot(s).kind == RidingLayout.Kind.CHIP, "Halo chip " + s);
        for (String s : RidingFrames.TRACE_CHIPS) check(trace.spot(s) != null && trace.spot(s).kind == RidingLayout.Kind.CHIP, "Trace chip " + s);
        for (String s : RidingFrames.TILES) {
            check(halo.spot(s).kind == RidingLayout.Kind.TILE && trace.spot(s).kind == RidingLayout.Kind.TILE, "tile " + s);
        }
        check(trace.spot("trace") != null && RidingLayout.TRACE_SPEED_POWER.equals(layout.content(RidingLayout.TRACE, "trace")), "Trace charts speed over power");
        check(RidingLayout.BATTERY_TEMPERATURE.equals(layout.content(RidingLayout.HALO, "chip_2")), "Halo's bottom row has battery temperature (owner)");
        check(RidingLayout.BATTERY_TEMPERATURE.equals(layout.content(RidingLayout.TRACE, "chip_2")), "Trace shows battery temperature beside the speed");
        check(RidingLayout.BATTERY.equals(layout.content(RidingLayout.HALO, "tile_2")) && RidingLayout.BATTERY.equals(layout.content(RidingLayout.TRACE, "tile_2")),
                "both frames have a battery tile for their gauge");
        for (RidingLayout.Content c : RidingLayout.CATALOG) check(!c.id.contains("lock") || c.id.equals(RidingLayout.CLOCK), "no lock content: " + c.id);
        for (String f : RidingLayout.FRAMES)
            for (RidingLayout.Spot s : RidingLayout.frameDef(f).spots) check(!s.fallback.contains("lock") || s.fallback.equals(RidingLayout.CLOCK), "no lock spot in " + f);
        check(RidingFrames.haloStrip(false).equals(Arrays.asList("strip_1", "strip_2")), "the strip at normal text");
        check(RidingFrames.haloStrip(true).isEmpty(), "large text leaves the tiles their room");
        check(layout.on(RidingLayout.HALO, RidingLayout.CHIPS) && layout.on(RidingLayout.TRACE, RidingLayout.CHIPS), "chips on by default");
        check(layout.on(RidingLayout.HALO, RidingLayout.POWER_BAR) && layout.on(RidingLayout.TRACE, RidingLayout.POWER_BAR), "the power bar on by default");
    }

    private static void picker() {
        check(RidingFrames.pickerShown(true, null), "stopped with no speed: the picker shows");
        check(RidingFrames.pickerShown(true, 0.4), "a crawl under 1 km/h counts as stopped");
        check(!RidingFrames.pickerShown(true, 5.0), "moving: no picker");
        check(!RidingFrames.pickerShown(false, 0.0), "the scooter isn't stationary: no picker");
        RidingLayout layout = new RidingLayout();
        layout.setContent(RidingLayout.FOCUS, "tile_2", RidingLayout.ODOMETER);
        check(RidingFrames.choose(layout, RidingLayout.HALO) && RidingLayout.HALO.equals(layout.frame()), "choosing Halo");
        check(!RidingFrames.choose(layout, RidingLayout.HALO), "the shown frame needs no save");
        check(!RidingFrames.choose(layout, "cockpit") && RidingLayout.HALO.equals(layout.frame()), "an unknown frame is refused");
        RidingLayout back = RidingLayout.parse(layout.toJson());
        check(RidingLayout.HALO.equals(back.frame()), "the choice survives saving");
        check(RidingLayout.ODOMETER.equals(back.content(RidingLayout.FOCUS, "tile_2")), "Focus keeps its own choices while Halo shows");
        check(RidingFrames.choose(back, RidingLayout.TRACE) && RidingFrames.choose(back, RidingLayout.FOCUS), "every frame is one tap away");
    }

    private static void english() {
        check(T.ridingFrameFocus().equals("Focus") && T.ridingFrameHalo().equals("Halo") && T.ridingFrameTrace().equals("Trace"), "frame names");
        check(T.ridingLayoutTitle().equals("Layout") && T.ridingLayoutChoose().equals("Choose a layout"), "picker title and button");
        check(T.ridingFrameHaloSummary().equals("Speed inside a speedometer ring"), "Halo summary");
        check(T.ridingRingMax("22.1 mph").equals("max 22.1 mph"), "ring caption");
        check(T.ridingTraceRegen().equals("Regen") && T.ridingTraceStart().equals("1 min ago") && T.ridingTraceNow().equals("now"), "chart text");
        check(T.ridingPowerRegenPeak("640 W").equals("regen · peak 640 W") && !T.ridingPowerRegenPeak("640 W").contains("braking"), "no braking in the regen line (owner)");
    }

    private static void source() throws IOException {
        String screen = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidingScreen.java")), StandardCharsets.UTF_8);
        check(!screen.contains("lock_status") && !screen.contains("lock_push_warning") && !screen.contains("\"unlock\"") && !screen.contains("\"lock\""), "no lock state or control in riding mode");
        check(screen.contains("RidingFrames.pickerShown(ble != null && ble.stationary()"), "the picker follows the stopped state");
        check(!screen.contains(".send(") && !screen.contains("write("), "riding mode sends nothing");
    }
}
