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
            resetSwitches();
            voltRange();
            disc();
            done();
            english();
            source();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingEditorTest: " + checks + " checks passed (working copy, spots and choices, per-frame memory, "
                + "Reset, tenths, Reset after each switch, Volt's range, disc motion and taps, Done while stopped, English, source)");
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
        check(RidingEditor.choices(chip).size() == 18 && !RidingEditor.choices(chip).contains(RidingLayout.SPEED), "chips offer every reading but speed");
        for (String id : new String[]{RidingLayout.DRIVE_MODE, RidingLayout.BATTERY_TEMPERATURE, RidingLayout.CLOCK, RidingLayout.AVERAGE_SPEED, RidingLayout.MAX_SPEED,
                RidingLayout.CURRENT, RidingLayout.ODOMETER, RidingLayout.BATTERY, RidingLayout.TRIP, RidingLayout.RIDE_TIME, RidingLayout.VOLTAGE, RidingLayout.POWER})
            check(RidingEditor.choices(chip).contains(id), "a chip can show " + id);
        for (RidingLayout.Content c : RidingLayout.CATALOG) check(!c.id.matches("(un)?lock.*") && !c.id.contains("_lock"), "no lock content (Q16): " + c.id);
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

    /** Reset follows every switch both ways: on once the frame differs, off once the last change is undone. */
    private static void resetSwitches() {
        for (String frame : RidingLayout.FRAMES) {
            RidingEditor e = new RidingEditor(new RidingLayout());
            e.showFrame(frame);
            for (String id : RidingEditor.switches(frame)) {
                check(!e.canReset(), frame + ": Reset off at the defaults, before " + id);
                e.set(id, false);
                check(e.canReset(), frame + ": Reset on after switching " + id + " off");
                e.set(id, true);
                check(!e.canReset(), frame + ": Reset off again once " + id + " is back on");
            }
            e.set(RidingLayout.TENTHS, false);
            e.set(RidingLayout.POWER_BAR, false);
            e.set(RidingLayout.TENTHS, true);
            check(e.canReset(), frame + ": still on while the power bar differs");
            e.set(RidingLayout.POWER_BAR, true);
            check(!e.canReset(), frame + ": off once the last change is undone");
        }
    }

    private static RangeReadout.Value readout(RangeReadout.State state, Double primary, int decimals, boolean approx) {
        return new RangeReadout.Value(state, primary, null, false, decimals, approx, false, false, false);
    }

    /**
     * Volt's range chosen in the editor shows the learned estimate from past rides at this battery
     * reading: never this ride's pace, never the scooter's. Learned, live and scooter values all present at once.
     */
    private static void voltRange() {
        RidingEditor e = new RidingEditor(new RidingLayout());
        e.showFrame(RidingLayout.HALO);
        check(RidingEditor.choices(RidingLayout.frameDef(RidingLayout.HALO).spot("highlight")).contains(RidingLayout.RANGE_VOLT), "Volt's range is offered");
        check(e.choose("highlight", RidingLayout.RANGE_VOLT), "Volt's range chosen");
        String content = RidingLayout.parse(e.working().toJson()).content(RidingLayout.HALO, "highlight");
        check(RidingLayout.RANGE_VOLT.equals(content), "Volt's range is saved");

        // Learned: 1000 Wh at 18 Wh/km, so 40 km at 72 %. Live pace: 17.3 km. Scooter: 38.4 km.
        RidingSession session = new RidingSession("p1", new RidingValues.PowerScale(), 0);
        check(session.learnedKm(72.0) == null, "no learned model: no learned range");
        session.setPrior(LiveRangeEstimator.Prior.synthetic(18, 1000, null));
        Double learned = session.learnedKm(72.0);
        check(learned != null && near(learned, 40), "the learned model's remaining km at 72 %: " + learned);
        check(session.learnedKm(null) == null, "no battery reading: no learned range");
        RangeReadout.Value live = readout(RangeReadout.State.LIVE, 17.3, 1, false);
        double scooterKm = 38.4;
        RidingFocus.Range volt = RidingFocus.range(content, live, scooterKm, false, learned, 72.0, false, "km");
        check(volt.kind == RidingFocus.RangeKind.VOLT && near(volt.value, 40) && volt.approx && volt.decimals == 0,
                "Volt's range shows the learned 40, not the live 17.3 or the scooter's 38: " + volt.kind + " " + volt.value);
        RidingFocus.Range pace = RidingFocus.range(RidingLayout.RANGE_AT_PACE, live, scooterKm, false, learned, 72.0, false, "km");
        check(pace.kind == RidingFocus.RangeKind.AT_PACE && near(pace.value, 17.3), "Range at this pace, same state, shows the live 17.3: " + pace.value);
        RidingFocus.Range scooter = RidingFocus.range(RidingLayout.RANGE_SCOOTER, live, scooterKm, false, learned, 72.0, false, "km");
        check(scooter.kind == RidingFocus.RangeKind.SCOOTER && near(scooter.value, 38), "the scooter's range, same state, stays the scooter's");
        check(near(RidingFocus.range(content, live, scooterKm, false, learned, 72.0, false, "mi").value, 25), "in miles: 40 km is about 25 mi");
        check(RidingFocus.range(content, live, scooterKm, false, learned, 72.0, true, "km").dim, "a stale battery reading dims it");

        RidingFocus.Range noModel = RidingFocus.range(content, live, scooterKm, false, null, 72.0, false, "km");
        check(noModel.kind == RidingFocus.RangeKind.NONE && noModel.value == null, "no learned model: no reading, never the live pace or the scooter's");
        check(RidingFocus.range(content, live, scooterKm, false, learned, null, false, "km").kind == RidingFocus.RangeKind.NONE, "no battery reading: no reading");
        check(RidingFocus.range(content, live, scooterKm, false, "km").kind == RidingFocus.RangeKind.NONE, "the old call without a learned range shows nothing for Volt's range");
        check(RidingFocus.range(content, live, scooterKm, false, session.learnedKm(1.0), 1.0, false, "km").kind == RidingFocus.RangeKind.ALMOST_EMPTY, "almost empty still says so");
        RidingFocus.Range fallback = RidingFocus.range(RidingLayout.RANGE_AT_PACE, readout(RangeReadout.State.NO_HISTORY, null, 0, false), scooterKm, false, learned, 72.0, false, "km");
        check(fallback.kind == RidingFocus.RangeKind.SCOOTER, "Range at this pace still falls back to the scooter's, labelled as such");
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
        check(near(RidingDisc.scale(0), 0) && near(RidingDisc.scale(1), 1) && near(RidingDisc.scale(.3f), RidingDisc.width(.3f)), "the disc grows with its place, so it stays round");
        check(near(RidingDisc.alpha(-1), 0) && near(RidingDisc.alpha(2), 1), "alpha is clamped");
        d.at(5);
        check(near(d.progress(), 1), "progress is clamped");
        check(near(RidingDisc.readout(0), 1) && near(RidingDisc.readout(.25f), .5) && near(RidingDisc.readout(.5f), 0) && near(RidingDisc.readout(1), 0), "the power readout has gone by the halfway point");
        check(near(RidingDisc.readout(-1), 1) && near(RidingDisc.readout(2), 0), "the readout's share is clamped");
        for (float t = 0; t <= 1; t += .02f) check(RidingDisc.readout(t) * RidingDisc.alpha(t) <= .126 , "the readout and the buttons are never both strong at " + t);
    }

    private static void done() {
        // Auto-save: every change on an open menu of an open screen saves, and the menu stays open (owner, Nov 9).
        RidingFrames.PickerSheet sheet = new RidingFrames.PickerSheet();
        check(sheet.open(true) && sheet.accept(0.0) && sheet.isOpen(), "a change saves and the menu stays open");
        RidingFrames.PickerSheet moving = new RidingFrames.PickerSheet();
        moving.open(true);
        check(moving.update(RidingFrames.MENU_CLOSE_KMH) && !moving.accept(0.0), "at 4 mph the menu closed, a late change saves nothing");
        RidingFrames.PickerSheet rebuilt = new RidingFrames.PickerSheet();
        rebuilt.open(true);
        rebuilt.close();
        check(!rebuilt.accept(0.0), "after a rebuild the old menu saves nothing");
    }

    private static void english() {
        check(T.ridingEditorSpots().equals("What each spot shows") && T.ridingEditorOptions().equals("Options"), "section headings");
        check(T.ridingEditorReset("Halo").equals("Reset Halo to default"), "Reset");
        check(T.ridingSpotBig().equals("Big number") && T.ridingSpotTile("2").equals("Tile 2") && T.ridingSpotChart().equals("Chart"), "spot names");
        check(T.ridingContentRingSpeed().equals("Speed + max") && T.ridingContentTracePower().equals("Power only"), "ring and chart choices");
        check(T.ridingSwitchTenths().equals("Tenths") && T.ridingSwitchPowerBarSummary().equals("Left edge: red for power, green for regen"), "switches");
        check(!T.ridingSwitchPowerBarSummary().contains("braking") && !T.ridingSwitchChipsSummary().matches("(?i).*\\block\\b.*"), "no braking, no lock (owner)");
        check(T.ridingEditorSpotSpoken("Big number", "Speed").equals("Big number, shows Speed"), "spoken row");
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void source() throws IOException {
        String screen = read("src/com/jjricks/volt/RidingScreen.java"), sheet = read("src/com/jjricks/volt/RidingLayoutSheet.java"),
                motion = read("src/com/jjricks/volt/RidingDiscMotion.java");
        check(screen.contains("boolean shownNow = tuneShown(now);") && screen.contains("disc.show(shownNow);"), "the disc follows the tune gate");
        check(screen.contains("if (disc != null && disc.takesTaps()) edit();"), "a tap while the disc hides opens nothing");
        check(screen.contains("new RidingLayoutSheet(") && screen.contains("return sheetState.accept(kmh);"), "the tune disc opens the editor; changes follow the sheet rules");
        check(!sheet.contains("commonDone") && sheet.contains("changed.save(editor.working())"), "no Done button: every change saves");
        check(screen.contains("layout.on(RidingLayout.TENTHS) && (speed.tenths"), "speed tenths follow the switch");
        check(screen.contains("units, layout.on(RidingLayout.TENTHS));"), "range tenths follow the switch");
        int toggle = sheet.indexOf("editor.set(id, on);");
        check(toggle > 0 && sheet.indexOf("reset.setEnabled(editor.canReset());", toggle) > toggle
                && sheet.indexOf("reset.setEnabled(editor.canReset());", toggle) < sheet.indexOf("content.addView(reset);"), "every switch refreshes Reset");
        check(screen.contains("content.equals(RidingLayout.RANGE_VOLT)"), "Volt's range keeps its name with no reading");
        check(screen.contains("session.learnedKm(percent), percent,"), "the screen passes Volt's learned range");
        check(read("src/com/jjricks/volt/LiveRangeEstimator.java").contains("r::remainingKm"), "the learned range is the learned result's own remainingKm (Home's)");
        check(read("src/com/jjricks/volt/RidingDiscAnimator.java").contains("Ui.motionEnabled()") && motion.contains("disc.setClickable(shown);"), "the motion honours animations off and stops taps");
        for (String s : new String[]{sheet, motion, read("src/com/jjricks/volt/RidingEditor.java")}) {
            check(!s.contains(".send(") && !s.contains("write("), "the editor sends nothing");
            check(!s.contains("\"lock\"") && !s.contains("\"unlock\""), "no lock in the editor");
        }
    }
}
