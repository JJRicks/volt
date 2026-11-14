package com.jjricks.volt;

import java.util.List;
import java.util.Locale;

/**
 * Riding mode's added chip slots (a tester, Nov 14): Trace's third chip, stacked under its two, and Focus's three chips
 * beside the speed. Every added slot is None by default, so both frames look as they did until the rider picks a reading.
 */
public final class RidingChipSlotsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            trace();
            focus();
            saved();
            editor();
            column();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("RidingChipSlotsTest: " + checks + " checks passed (Trace's third chip, Focus's chips, "
                + "saved layouts, the editor, a short column)");
    }

    private static void trace() {
        RidingLayout.Frame f = RidingLayout.frameDef(RidingLayout.TRACE);
        check(RidingFrames.TRACE_COLUMN.size() == 3 && "chip_3".equals(RidingFrames.TRACE_COLUMN.get(2))
                && RidingFrames.TRACE_COLUMN.subList(0, 2).equals(RidingFrames.TRACE_CHIPS), "Trace stacks three chips, its two first");
        for (String spot : RidingFrames.TRACE_COLUMN) check(f.spot(spot) != null, "Trace's column names its spots: " + spot);
        RidingLayout.Spot third = f.spot("chip_3");
        check(third != null && third.kind == RidingLayout.Kind.CHIP, "Trace's third chip is a chip slot");
        RidingLayout layout = new RidingLayout();
        check(RidingLayout.NONE.equals(layout.content(RidingLayout.TRACE, "chip_3")), "it's None by default");
        check(RidingLayout.DRIVE_MODE.equals(layout.content(RidingLayout.TRACE, "chip_1"))
                && RidingLayout.BATTERY_TEMPERATURE.equals(layout.content(RidingLayout.TRACE, "chip_2")), "the first two are as they were");
        check(layout.setContent(RidingLayout.TRACE, "chip_3", RidingLayout.CLOCK), "it takes a reading");
        check(RidingLayout.CLOCK.equals(layout.content(RidingLayout.TRACE, "chip_3")), "and shows it");
    }

    private static void focus() {
        RidingLayout.Frame f = RidingLayout.frameDef(RidingLayout.FOCUS);
        check(RidingFrames.FOCUS_CHIPS.size() == 3, "Focus has three chips beside the speed");
        RidingLayout layout = new RidingLayout();
        for (String spot : RidingFrames.FOCUS_CHIPS) {
            RidingLayout.Spot s = f.spot(spot);
            check(s != null && s.kind == RidingLayout.Kind.CHIP, "Focus's " + spot + " is a chip slot");
            check(RidingLayout.NONE.equals(layout.content(RidingLayout.FOCUS, spot)), "Focus's " + spot + " is None by default");
        }
        check(!f.switches.containsKey(RidingLayout.CHIPS), "Focus still has no chips switch: None is how they're off");
        check(layout.setContent(RidingLayout.FOCUS, "chip_2", RidingLayout.AVERAGE_SPEED), "a Focus chip takes a reading");
        check(!layout.setContent(RidingLayout.FOCUS, "chip_2", RidingLayout.TRACE_SPEED_POWER), "but not a chart");
        check(layout.setContent(RidingLayout.FOCUS, "chip_2", RidingLayout.NONE), "and None again");
    }

    private static void saved() {
        RidingLayout layout = new RidingLayout();
        check(layout.setContent(RidingLayout.FOCUS, "chip_1", RidingLayout.VOLTAGE), "Focus chip set");
        check(layout.setContent(RidingLayout.FOCUS, "chip_3", RidingLayout.CLOCK), "another Focus chip set");
        check(layout.setContent(RidingLayout.TRACE, "chip_3", RidingLayout.EFFICIENCY), "Trace's third chip set");
        String json = layout.toJson();
        RidingLayout back = RidingLayout.parse(json);
        check(RidingLayout.VOLTAGE.equals(back.content(RidingLayout.FOCUS, "chip_1"))
                && RidingLayout.NONE.equals(back.content(RidingLayout.FOCUS, "chip_2"))
                && RidingLayout.CLOCK.equals(back.content(RidingLayout.FOCUS, "chip_3")), "Focus's chips come back from a backup");
        check(RidingLayout.EFFICIENCY.equals(back.content(RidingLayout.TRACE, "chip_3")), "Trace's third chip comes back");
        check(back.toJson().equals(json), "a backup round-trips unchanged");

        // A layout saved before these slots existed: they read as their default, None, so the frames look as they did.
        RidingLayout older = RidingLayout.parse("{\"v\":1,\"frame\":\"trace\",\"frames\":{\"trace\":{\"spots\":{\"chip_1\":\"clock\"}}}}");
        check(RidingLayout.NONE.equals(older.content(RidingLayout.TRACE, "chip_3")), "an older layout has no third Trace chip");
        for (String spot : RidingFrames.FOCUS_CHIPS)
            check(RidingLayout.NONE.equals(older.content(RidingLayout.FOCUS, spot)), "an older layout has no Focus chips: " + spot);
        check(RidingLayout.CLOCK.equals(older.content(RidingLayout.TRACE, "chip_1")), "and keeps what it chose");
    }

    private static void editor() {
        RidingEditor editor = new RidingEditor(new RidingLayout());
        check(editor.showFrame(RidingLayout.FOCUS), "Focus opens in the editor");
        List<RidingLayout.Spot> spots = editor.spots(RidingLayout.FOCUS);
        for (RidingLayout.Spot s : spots) check(s.kind != RidingLayout.Kind.CHIP, "Focus's spots list no chips: " + s.id);
        List<RidingLayout.Spot> beside = RidingEditor.besideChips(RidingLayout.FOCUS);
        check(beside.size() == 3, "Focus's chips are listed apart, beside the speed");
        for (int i = 0; i < 3; i++) {
            check(RidingFrames.FOCUS_CHIPS.get(i).equals(beside.get(i).id), "in order: " + i);
            check(RidingEditor.number(RidingLayout.FOCUS, beside.get(i)) == i + 1, "numbered 1 to 3: " + i);
            check(RidingLayout.NONE.equals(RidingEditor.options(beside.get(i)).get(0)), "each offers None first");
        }
        check(RidingEditor.besideChips(RidingLayout.HALO).isEmpty() && RidingEditor.besideChips(RidingLayout.TRACE).isEmpty(),
                "frames with a chips switch list their chips among their spots");
        check(editor.choose("chip_1", RidingLayout.BATTERY_TEMPERATURE), "the editor sets a Focus chip");
        check(RidingLayout.BATTERY_TEMPERATURE.equals(editor.content("chip_1")) && editor.changed(), "and it's a change to save");
        check("Chips beside the speed".equals(T.ridingEditorBesideSpeed()), "the group's heading");

        RidingEditor trace = new RidingEditor(new RidingLayout());
        check(trace.showFrame(RidingLayout.TRACE), "Trace opens in the editor");
        boolean third = false;
        for (RidingLayout.Spot s : trace.spots(RidingLayout.TRACE)) third |= "chip_3".equals(s.id);
        check(third, "Trace lists its third chip with its chips on");
        RidingLayout off = new RidingLayout();
        check(off.setOn(RidingLayout.TRACE, RidingLayout.CHIPS, false), "Trace's chips switched off");
        RidingEditor offEditor = new RidingEditor(off);
        for (RidingLayout.Spot s : offEditor.spots(RidingLayout.TRACE)) check(s.kind != RidingLayout.Kind.CHIP, "and lists none with them off: " + s.id);
    }

    /** Three stacked chips in a column too short for them all: the lowest step aside, and which ones never depends on values. */
    private static void column() {
        check(RidingChipFit.stackable(40, 8, 3 * 40 + 2 * 8) == 3, "three chips fit a column their height");
        check(RidingChipFit.stackable(40, 8, 3 * 40 + 2 * 8 - 1) == 2, "one pixel short, the lowest steps aside");
        check(RidingChipFit.stackable(40, 8, 40) == 1, "a column one chip tall shows the first");
    }
}
