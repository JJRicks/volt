package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Riding mode's Halo and Trace frames (riding-mode plan PR 3): the speed ring's scale and angles,
 * the live trace's runs, axes and neutral unknown-direction points, Halo's strip at large text, the
 * frame picker (only while stopped, Focus still the default), the frames' spots with no lock, and
 * the catalog English.
 */
public final class RidingFramesTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    /** 0x91 with this whole-unit speed, on a scooter that reports km/h. */
    private static byte[] rideReport(int kmh) { return new byte[]{0, 72, 1, (byte) kmh, 38, 3, 12, 30, 15, 100}; }
    private static Double menuKmh(Telemetry telemetry, boolean connected, long nowMs) { return RidingFrames.menuKmh(connected, telemetry.takeSpeedPeak(nowMs)); }

    /** The tune menu's close rule only hears this connection's own speeds; a new connection starts with none. */
    private static void menuSpeedAcrossConnections() {
        Telemetry telemetry = new Telemetry();
        RidingFrames.PickerSheet menu = new RidingFrames.PickerSheet();
        telemetry.accept(0x91, rideReport(12), 1_000);
        menu.open(true);
        check(!menu.update(menuKmh(telemetry, false, 1_100)) && menu.isOpen(), "the last connection's 12 km/h, disconnected: the menu stays");
        telemetry.accept(0x91, rideReport(12), 2_000);
        telemetry.clear();
        check(!menu.update(menuKmh(telemetry, true, 2_100)) && menu.isOpen(), "reconnected, no new speed: the previous connection's 12 km/h isn't reused");
        telemetry.accept(0x91, rideReport(3), 3_000);
        check(!menu.update(menuKmh(telemetry, true, 3_100)) && menu.isOpen(), "the first new sample below 4 mph: the menu stays");
        telemetry.clear();
        check(!menu.update(menuKmh(telemetry, true, 3_200)) && menu.isOpen(), "another reconnect, still no speed: the menu stays");
        telemetry.accept(0x91, rideReport(12), 4_000);
        check(menu.update(menuKmh(telemetry, true, 4_100)) && !menu.isOpen(), "the first new sample at 4 mph or more closes the menu");
    }

    /** A speed at 4 mph or more that a later sample replaced before the screen refreshed still counts, once. */
    private static void menuSeesCrossingBetweenRefreshes() {
        Telemetry telemetry = new Telemetry();
        RidingFrames.PickerSheet menu = new RidingFrames.PickerSheet();
        telemetry.accept(0x91, rideReport(0), 1_000);
        menu.open(true);
        telemetry.accept(0x91, rideReport(12), 1_300);
        telemetry.accept(0x91, rideReport(0), 1_600);
        check(menu.update(menuKmh(telemetry, true, 1_700)) && !menu.isOpen(), "0 then 12 then 0 km/h between refreshes closes the menu");
        check(menuKmh(telemetry, true, 1_700) == null, "a peak counts once");
        RidingFrames.PickerSheet slow = new RidingFrames.PickerSheet();
        slow.open(true);
        telemetry.accept(0x91, rideReport(5), 2_000);
        telemetry.accept(0x91, rideReport(0), 2_300);
        check(!slow.update(menuKmh(telemetry, true, 2_400)) && slow.isOpen(), "walking pace between refreshes: the menu stays");
        telemetry.accept(0x91, rideReport(12), 3_000);
        check(!slow.update(menuKmh(telemetry, false, 3_100)) && slow.isOpen(), "a peak unseen when the connection drops doesn't close the menu");
    }
    /** A peak older than the freshness limit is no speed, however it got there; a fresh crossing still counts. */
    private static void menuAgesThePeak() {
        long limit = AutoStartPolicy.SPEED_FRESH_MS;
        Telemetry telemetry = new Telemetry();
        RidingFrames.PickerSheet menu = new RidingFrames.PickerSheet();
        telemetry.accept(0x91, rideReport(0), 500);
        menu.open(true);
        menuKmh(telemetry, true, 600);
        // Paused with the connection up: 12 km/h at 1 s, 0 km/h at 9 s, the screen looks at 10 s.
        telemetry.accept(0x91, rideReport(12), 1_000);
        telemetry.accept(0x91, rideReport(0), 9_000);
        check(!menu.update(menuKmh(telemetry, true, 10_000)) && menu.isOpen(), "a 9 second old 12 km/h doesn't close the menu when the screen comes back");
        // A crossing that arrived during the pause but within the limit does.
        telemetry.accept(0x91, rideReport(12), 20_000);
        telemetry.accept(0x91, rideReport(0), 20_500);
        check(near(menuKmh(telemetry, true, 20_000 + limit), 12), "a peak exactly at the limit is fresh");
        telemetry.accept(0x91, rideReport(12), 30_000);
        check(menuKmh(telemetry, true, 30_001 + limit) == null, "a peak just past the limit is no speed");
        // An expired peak doesn't hide a slower one that is still fresh.
        telemetry.accept(0x91, rideReport(12), 40_000);
        telemetry.accept(0x91, rideReport(8), 42_900);
        telemetry.accept(0x91, rideReport(0), 43_500);
        check(near(menuKmh(telemetry, true, 44_000), 8), "the fastest fresh reading, once the older 12 km/h has expired");
        check(menuKmh(telemetry, true, 44_000) == null, "taken once");
        // Readings that arrive after an expiry keep working, and only the max survives.
        telemetry.accept(0x91, rideReport(5), 60_000);
        telemetry.accept(0x91, rideReport(12), 61_000);
        telemetry.accept(0x91, rideReport(3), 61_200);
        check(near(menuKmh(telemetry, true, 61_500), 12), "the fastest of the fresh readings");
        telemetry.accept(0x91, rideReport(12), 70_000);
        telemetry.accept(0x91, rideReport(20), 65_000);
        check(near(menuKmh(telemetry, true, 70_100), 12), "an out-of-order older reading isn't a new peak");
        telemetry.accept(0x91, rideReport(12), 80_000);
        check(menuKmh(telemetry, true, 79_000) == null, "a peak from the future isn't fresh");
        telemetry.accept(0x91, rideReport(12), 90_000);
        telemetry.clear();
        check(menuKmh(telemetry, true, 90_100) == null, "a new connection starts with none");
    }

    /** The speed check that closes the menu runs before a switch's prompt opens, and again on the answer. */
    private static void menuChecksBeforeAsking() {
        Telemetry telemetry = new Telemetry();
        RidingFrames.PickerSheet menu = new RidingFrames.PickerSheet();
        long[] now = {1_000};
        boolean[] stored = {false};
        Runnable[] asked = {null};
        int[] shown = {0};
        BooleanSupplier may = () -> {
            Double kmh = menuKmh(telemetry, true, now[0]);
            return !menu.update(kmh) && menu.accept(kmh);
        };
        Consumer<Runnable> prompt = answer -> asked[0] = answer;
        Runnable show = () -> shown[0]++;
        Runnable save = () -> { if (may.getAsBoolean()) stored[0] = true; show.run(); };
        // The permission-needed branch with a latched crossing: no ride yet, so the prompt would otherwise open.
        telemetry.accept(0x91, rideReport(0), 900);
        menu.open(true);
        menuKmh(telemetry, true, 950);
        telemetry.accept(0x91, rideReport(12), 960);
        telemetry.accept(0x91, rideReport(0), 990);
        RidingFrames.askBeforeSave(may, prompt, save, show);
        check(asked[0] == null && !stored[0] && shown[0] == 1, "a latched 0, 12, 0 km/h crossing just before the tap opens no prompt and shows what's stored");
        check(!menu.isOpen(), "and it closes the menu");
        // Below 4 mph the prompt opens; the delayed answer is checked again when it lands.
        RidingFrames.PickerSheet again = new RidingFrames.PickerSheet();
        again.open(true);
        BooleanSupplier mayAgain = () -> {
            Double kmh = menuKmh(telemetry, true, now[0]);
            return !again.update(kmh) && again.accept(kmh);
        };
        Runnable saveAgain = () -> { if (mayAgain.getAsBoolean()) stored[0] = true; show.run(); };
        now[0] = 2_000;
        RidingFrames.askBeforeSave(mayAgain, prompt, saveAgain, show);
        check(asked[0] == saveAgain && !stored[0] && shown[0] == 1, "below 4 mph the prompt opens and nothing is saved yet");
        telemetry.accept(0x91, rideReport(12), 2_500);
        now[0] = 2_600;
        asked[0].run();
        check(!stored[0] && shown[0] == 2 && !again.isOpen(), "a crossing before the delayed answer saves nothing and closes the menu");
        RidingFrames.PickerSheet calm = new RidingFrames.PickerSheet();
        calm.open(true);
        BooleanSupplier mayCalm = () -> {
            Double kmh = menuKmh(telemetry, true, now[0]);
            return !calm.update(kmh) && calm.accept(kmh);
        };
        Runnable saveCalm = () -> { if (mayCalm.getAsBoolean()) stored[0] = true; show.run(); };
        asked[0] = null;
        RidingFrames.askBeforeSave(mayCalm, prompt, saveCalm, show);
        asked[0].run();
        check(stored[0] && calm.isOpen(), "a calm answer saves and the menu stays open");
    }

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
        unsigned.sample(RidingValues.SIGN_KEY, 254, 0x79, 500);
        unsigned.sample(RidingValues.POWER_KEY, 300, 0x72, 1000);
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
        // The tune button: hidden while moving, and shown with no scooter connected (owner, Nov 9).
        check(RidingFrames.tuneShown(false, false, null) && RidingFrames.tuneShown(false, false, 22.0), "disconnected: the tune button shows");
        check(RidingFrames.tuneShown(true, true, 0.0), "connected and stopped: it shows");
        check(!RidingFrames.tuneShown(true, true, 5.0) && !RidingFrames.tuneShown(true, false, 0.0), "connected and moving: it hides");
        check(!RidingFrames.pickerShown(false, 0.0), "Lock and Record keep the stopped-only gate while disconnected");
        // Connected with an 8-second-old settings reply and a fresh 12 km/h speed: still "moving", never "disconnected".
        check(!RidingFrames.tuneShown(true, false, 12.0), "fresh speed on a connected scooter with stale settings: the button hides");
        check(RidingFrames.menuKmh(true, 12.0) == 12.0, "a connected scooter's peak reaches the close rule");
        check(RidingFrames.menuKmh(false, 12.0) == null && RidingFrames.menuKmh(true, null) == null, "no scooter connected, or no new reading: no speed");
        menuSpeedAcrossConnections();
        menuSeesCrossingBetweenRefreshes();
        menuAgesThePeak();
        menuChecksBeforeAsking();
        // The menu's close rule: only 4 mph (6.437 km/h) or more, in any display unit.
        check(RidingFrames.MENU_CLOSE_KMH == 6.437, "4 mph");
        RidingFrames.PickerSheet sheet = new RidingFrames.PickerSheet();
        check(!sheet.open(false), "the menu doesn't open while the tune button is hidden");
        check(sheet.open(true) && sheet.isOpen(), "the menu opens");
        check(!sheet.update(null) && sheet.isOpen(), "no live speed: the menu stays");
        check(!sheet.update(0.0) && !sheet.update(5.0) && !sheet.update(6.43) && sheet.isOpen(), "walking pace, under 4 mph: the menu stays");
        check(sheet.update(6.437) && !sheet.isOpen(), "4 mph closes the menu");
        check(!sheet.update(30.0), "it closes once");
        check(!sheet.accept(0.0), "a change after it closed saves nothing");
        RidingFrames.PickerSheet saving = new RidingFrames.PickerSheet();
        saving.open(true);
        check(saving.accept(0.0) && saving.isOpen() && saving.accept(null), "changes save and the menu stays open");
        check(!saving.accept(6.437) && !saving.accept(30.0), "a change that lands at 4 mph or more saves nothing");
        // Open, then the screen is rebuilt (rotation, folding): the old menu closes and can't save.
        RidingFrames.PickerSheet old = new RidingFrames.PickerSheet();
        old.open(true);
        check(old.close() && !old.isOpen(), "a rebuild closes the old screen's menu");
        check(!old.accept(0.0), "a change on the old screen's menu saves nothing");
        check(!old.open(true), "a closed screen opens no menu");
        RidingFrames.PickerSheet shut = new RidingFrames.PickerSheet();
        shut.open(true);
        shut.dismissed();
        check(!shut.close() && !shut.accept(0.0), "a menu closed by Back has nothing left to close or take");
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
        check(screen.contains("RidingFrames.pickerShown(ble != null && ble.stationary()"), "Lock and Record follow the stopped state");
        check(screen.contains("RidingFrames.tuneShown(ble != null && ble.connected(), ble != null && ble.stationary()"), "the tune button follows its own gate");
        check(screen.contains("if (sheetState.update(liveKmh())) dismissSheet();"), "live speed alone closes the menu");
        check(screen.contains("RidingFrames.menuKmh(ble.connected(), ble.telemetry.takeSpeedPeak(SystemClock.elapsedRealtime()))"), "only this connection's fresh readings count, peak included, on the clock they arrive on, and stale settings don't matter");
        check(screen.contains("if (!menuChangeAllowed()) return;"), "a menu change looks at the newest speed, not the last screen refresh");
        check(screen.contains("liveKmh(); // Speeds from before the menu opened"), "a menu starts with a clean peak");
        String more = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MoreScreen.java")), StandardCharsets.UTF_8);
        check(more.contains("private void consent() {\n            if (!may()) return;") && more.contains("private void time(String key, int fallback, String title) {\n            if (!may()) return;"), "the dialogs the menu opens check the newest speed first");
        check(more.contains("RidingFrames.askBeforeSave(this::may, beforeOn, save, showStored)"), "the switch checks the speed before asking");
        check(screen.contains("sheetState.close();") && screen.contains("return sheetState.accept(kmh);"), "the screen owns the menu on close and on a change");
        check(!screen.contains(".send(") && !screen.contains("write("), "riding mode sends nothing");
    }
}
