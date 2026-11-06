package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Riding mode's layout editor (riding-mode plan PR 4): a working copy that saves nothing until Done,
 * per-frame memory, Reset of the frame on show only, the spots offered (never the top bar, no lock),
 * the tenths switch (on by default, owner Q23 B), the tune disc's motion (taps stop the instant it
 * hides, no motion with animations off), the stopped-only sheet rules for Done, and the English.
 */
public final class RidingEditorTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    public static void main(String[] args) throws IOException {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            editor();
            memoryAndReset();
            tenths();
            disc();
            done();
            english();
            source();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingEditorTest: " + checks + " checks passed (working copy, spots and choices, per-frame memory, "
                + "Reset, tenths, disc motion and taps, Done while stopped, English, source)");
    }

    private static void editor() {
        RidingLayout saved = new RidingLayout();
        String before = saved.toJson();
        RidingEditor e = new RidingEditor(saved);
        check(RidingLayout.FOCUS.equals(e.frame()), "the editor opens on the frame on show, Focus by default");
        check(!e.changed() && !e.canReset(), "nothing to save or reset at the defaults");
        for (String frame : RidingLayout.FRAMES)
            for (RidingLayout.Spot s : e.spots(frame)) check(s.kind != RidingLayout.Kind.TOP, "the top bar is not offered in " + frame);
        check(e.spots(RidingLayout.FOCUS).size() == 6, "Focus offers its big number, highlight and four tiles");
        check(e.spots(RidingLayout.HALO).size() == 10, "Halo offers everything but the top bar");
        check(!e.choose("top", RidingLayout.STATUS) && !e.choose("top", RidingLayout.RECORD), "the top bar can't be set (Record dropped)");
        check(e.choose("tile_2", RidingLayout.ODOMETER) && RidingLayout.ODOMETER.equals(e.content("tile_2")), "a tile takes a reading");
        check(e.changed() && e.canReset(), "a change can be saved and reset");
        check(saved.toJson().equals(before), "the saved layout is untouched until Done");
        check(!e.choose("tile_2", RidingLayout.RING_SPEED), "a tile can't hold the ring's speed");
        check(!e.choose("nowhere", RidingLayout.SPEED), "an unknown spot is refused");
        check(e.showFrame(RidingLayout.HALO) && RidingLayout.HALO.equals(e.frame()), "frames switch in the editor");
        check(!e.showFrame("cockpit") && RidingLayout.HALO.equals(e.frame()), "an unknown frame is refused");
        RidingLayout.Spot ring = RidingLayout.frameDef(RidingLayout.HALO).spot("ring");
        List<String> ringChoices = RidingEditor.choices(ring);
        check(ringChoices.contains(RidingLayout.RING_SPEED) && ringChoices.contains(RidingLayout.BATTERY) && ringChoices.contains(RidingLayout.POWER)
                && ringChoices.size() == 3, "the ring offers speed + max, battery and power");
        RidingLayout.Spot chip = RidingLayout.frameDef(RidingLayout.HALO).spot("chip_1");
        check(RidingEditor.choices(chip).size() == 3 && !RidingEditor.choices(chip).contains(RidingLayout.SPEED), "chips offer drive mode, temperature and the clock");
        for (RidingLayout.Content c : RidingLayout.CATALOG) check(!c.id.contains("lock"), "no lock content (Q16): " + c.id);
        check(RidingEditor.choices(RidingLayout.frameDef(RidingLayout.FOCUS).spot("top")).isEmpty(), "no choices for the top bar");
        check(e.set(RidingLayout.CHIPS, false) && !e.on(RidingLayout.CHIPS), "chips switch off");
        for (RidingLayout.Spot s : e.spots(RidingLayout.HALO)) check(s.kind != RidingLayout.Kind.CHIP, "chip rows go while chips are off");
        check(e.spots(RidingLayout.HALO).size() == 7, "Halo without chips offers seven spots");
        check(!e.set("glow", true), "an unknown switch is refused");
        check(RidingEditor.switches(RidingLayout.FOCUS).equals(java.util.Arrays.asList(RidingLayout.POWER_BAR, RidingLayout.TENTHS)), "Focus: power bar and tenths");
        check(RidingEditor.switches(RidingLayout.TRACE).equals(RidingEditor.SWITCHES), "Trace: power bar, chips and tenths");
        RidingLayout.Frame focus = RidingLayout.frameDef(RidingLayout.FOCUS), halo = RidingLayout.frameDef(RidingLayout.HALO);
        check(RidingEditor.number(RidingLayout.FOCUS, focus.spot("tile_3")) == 3 && RidingEditor.number(RidingLayout.FOCUS, focus.spot("big")) == 0, "tiles are numbered, a lone spot isn't");
        check(RidingEditor.number(RidingLayout.HALO, halo.spot("strip_2")) == 2 && RidingEditor.number(RidingLayout.HALO, halo.spot("ring")) == 0, "strip numbers");
    }

    private static void memoryAndReset() {
        RidingEditor e = new RidingEditor(new RidingLayout());
        e.choose("tile_1", RidingLayout.VOLTAGE);
        e.showFrame(RidingLayout.TRACE);
        e.choose("trace", RidingLayout.TRACE_POWER);
        e.set(RidingLayout.POWER_BAR, false);
        e.showFrame(RidingLayout.FOCUS);
        check(RidingLayout.VOLTAGE.equals(e.content("tile_1")), "Focus remembers its tile while Trace was edited");
        e.showFrame(RidingLayout.TRACE);
        check(RidingLayout.TRACE_POWER.equals(e.content("trace")) && !e.on(RidingLayout.POWER_BAR), "Trace remembers its chart and power bar");
        e.reset();
        check(RidingLayout.TRACE_SPEED_POWER.equals(e.content("trace")) && e.on(RidingLayout.POWER_BAR) && !e.canReset(), "Reset restores Trace");
        e.showFrame(RidingLayout.FOCUS);
        check(RidingLayout.VOLTAGE.equals(e.content("tile_1")) && e.canReset(), "Reset leaves Focus's choices alone");
        RidingLayout back = RidingLayout.parse(e.working().toJson());
        check(RidingLayout.FOCUS.equals(back.frame()) && RidingLayout.VOLTAGE.equals(back.content(RidingLayout.FOCUS, "tile_1"))
                && back.isDefault(RidingLayout.TRACE), "the working copy saves what the editor shows");
        RidingEditor again = new RidingEditor(back);
        check(!again.changed() && RidingLayout.VOLTAGE.equals(again.content("tile_1")), "reopening starts from the saved choices");
    }

    private static void tenths() {
        RidingLayout layout = new RidingLayout();
        for (String frame : RidingLayout.FRAMES) check(layout.on(frame, RidingLayout.TENTHS), "tenths on by default in " + frame + " (Q23 B)");
        check(!layout.toJson().contains(RidingLayout.TENTHS), "the default layout's text doesn't change");
        RidingEditor e = new RidingEditor(layout);
        check(e.set(RidingLayout.TENTHS, false) && !e.on(RidingLayout.TENTHS) && e.changed(), "tenths switch off");
        RidingLayout back = RidingLayout.parse(e.working().toJson());
        check(!back.on(RidingLayout.FOCUS, RidingLayout.TENTHS) && back.on(RidingLayout.HALO, RidingLayout.TENTHS), "tenths are kept per frame");
        RidingLayout old = RidingLayout.parse("{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"switches\":{\"chips\":false}}}}");
        check(old.on(RidingLayout.HALO, RidingLayout.TENTHS) && !old.on(RidingLayout.HALO, RidingLayout.CHIPS), "a layout saved before tenths keeps them on");
    }

    private static void disc() {
        RidingDisc d = new RidingDisc();
        check(!d.visible() && !d.takesTaps(), "the disc starts gone");
        check(d.want(true) && d.takesTaps() && !d.want(true), "showing takes taps at once; asking again changes nothing");
        d.at(.5f);
        check(d.visible() && near(d.progress(), .5), "halfway in");
        check(d.want(false) && !d.takesTaps(), "taps stop the instant it starts to hide");
        check(d.visible(), "it is still on screen while it shrinks away");
        check(RidingDisc.duration(220, .5f, false, true) == 110, "the rest of the way takes the rest of the time");
        check(RidingDisc.duration(220, 0, true, true) == 220 && RidingDisc.duration(220, 1, true, true) == 0, "a full show, and nothing left");
        check(RidingDisc.duration(220, 0, true, false) == 0 && RidingDisc.duration(220, 1, false, false) == 0, "no motion with animations off");
        d.finish();
        check(!d.visible() && near(d.progress(), 0), "finished: gone");
        check(near(RidingDisc.width(0), 0) && near(RidingDisc.width(1), 1) && near(RidingDisc.width(.5f), .5), "the width eases from none to full");
        check(RidingDisc.width(.1f) < .1f && RidingDisc.width(.9f) > .9f, "gentle at both ends, so the pill doesn't jump");
        check(near(RidingDisc.scale(0), RidingDisc.MIN_SCALE) && near(RidingDisc.scale(1), 1), "it grows from a smaller disc");
        check(near(RidingDisc.alpha(-1), 0) && near(RidingDisc.alpha(2), 1), "alpha is clamped");
        d.at(5);
        check(near(d.progress(), 1), "progress is clamped");
    }

    private static void done() {
        // The editor's Done follows #265's sheet rules: only on an open screen's sheet while still stopped.
        RidingFrames.PickerSheet sheet = new RidingFrames.PickerSheet();
        check(sheet.open(true) && sheet.accept(true), "Done while stopped saves");
        RidingFrames.PickerSheet moving = new RidingFrames.PickerSheet();
        moving.open(true);
        check(moving.update(false) && !moving.accept(true), "riding resumed: the editor closed, a late Done saves nothing");
        RidingFrames.PickerSheet rebuilt = new RidingFrames.PickerSheet();
        rebuilt.open(true);
        rebuilt.close();
        check(!rebuilt.accept(true), "after a rebuild the old editor's Done saves nothing");
    }

    private static void english() {
        check(T.ridingEditorSpots().equals("What each spot shows") && T.ridingEditorOptions().equals("Options"), "section headings");
        check(T.ridingEditorReset("Halo").equals("Reset Halo to default"), "Reset");
        check(T.ridingSpotBig().equals("Big number") && T.ridingSpotTile("2").equals("Tile 2") && T.ridingSpotChart().equals("Chart"), "spot names");
        check(T.ridingContentRingSpeed().equals("Speed + max") && T.ridingContentTracePower().equals("Power only"), "ring and chart choices");
        check(T.ridingSwitchTenths().equals("Tenths") && T.ridingSwitchPowerBarSummary().equals("Left edge: red for power, green for regen"), "switches");
        check(!T.ridingSwitchPowerBarSummary().contains("braking") && !T.ridingSwitchChipsSummary().contains("lock"), "no braking, no lock (owner)");
        check(T.ridingEditorSpotSpoken("Big number", "Speed").equals("Big number, shows Speed"), "spoken row");
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void source() throws IOException {
        String screen = read("src/com/jjricks/volt/RidingScreen.java"), sheet = read("src/com/jjricks/volt/RidingLayoutSheet.java"),
                motion = read("src/com/jjricks/volt/RidingDiscMotion.java");
        check(screen.contains("disc.show(stoppedNow);"), "the disc follows the stopped state");
        check(screen.contains("if (disc != null && disc.takesTaps()) edit();"), "a tap while the disc hides opens nothing");
        check(screen.contains("new RidingLayoutSheet(") && screen.contains("sheetState.accept(stopped("), "the tune disc opens the editor; Done follows the sheet rules");
        check(screen.contains("layout.on(RidingLayout.TENTHS) && (speed.tenths"), "speed tenths follow the switch");
        check(screen.contains("units, layout.on(RidingLayout.TENTHS));"), "range tenths follow the switch");
        check(motion.contains("Ui.motionEnabled()") && motion.contains("disc.setClickable(shown);"), "the motion honours animations off and stops taps");
        for (String s : new String[]{sheet, motion, read("src/com/jjricks/volt/RidingEditor.java")}) {
            check(!s.contains(".send(") && !s.contains("write("), "the editor sends nothing");
            check(!s.contains("\"lock\"") && !s.contains("\"unlock\""), "no lock in the editor");
        }
    }
}
