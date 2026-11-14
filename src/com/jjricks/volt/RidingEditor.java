package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The riding layout editor's choices (riding-mode plan PR 4), kept Android-free so plain JVM tests
 * run them. The editor works on a copy of the saved layout: the rider picks a frame, what each spot
 * shows and the frame's switches, and nothing is saved until Done. Each frame keeps its own choices
 * (per-frame memory), and Reset restores only the frame on show.
 *
 * Riding mode only reads: nothing here sends a scooter command, and there is no lock state (Q16). The
 * top bar's spot isn't offered: the Record pill was dropped (owner), so it always shows the status.
 */
final class RidingEditor {
    /** The switches in the order the editor lists them; a frame shows the ones it has. */
    static final List<String> SWITCHES = Collections.unmodifiableList(Arrays.asList(
            RidingLayout.POWER_BAR, RidingLayout.CHIPS, RidingLayout.TENTHS));

    private final String savedJson;
    private final RidingLayout working;

    /** Starts from the saved layout; the saved one itself is never changed. */
    RidingEditor(RidingLayout saved) {
        savedJson = saved.toJson();
        working = RidingLayout.parse(savedJson);
    }

    /** The copy being edited, for Done to save. */
    RidingLayout working() { return working; }
    String frame() { return working.frame(); }

    /** Shows another frame with its own remembered choices; false for an unknown frame. */
    boolean showFrame(String frameId) { return working.setFrame(frameId); }

    /**
     * The spots the editor lists for a frame, in the frame's order: every spot but the top bar.
     * Chips are left out while the frame's chips are switched off. A frame without a Chips switch
     * (Focus) lists its chips apart, under the spots (besideChips).
     */
    List<RidingLayout.Spot> spots(String frameId) {
        RidingLayout.Frame f = RidingLayout.frameDef(frameId);
        List<RidingLayout.Spot> out = new ArrayList<>();
        if (f == null) return out;
        boolean chips = f.switches.containsKey(RidingLayout.CHIPS) && working.on(frameId, RidingLayout.CHIPS);
        for (RidingLayout.Spot s : f.spots) {
            if (s.kind == RidingLayout.Kind.TOP) continue;
            if (s.kind == RidingLayout.Kind.CHIP && !chips) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * A frame's chips beside its speed that the editor lists in a group of their own (Focus's, a tester, Nov 14):
     * the chip spots of a frame without a Chips switch. Empty for the frames whose chips are among their spots.
     */
    static List<RidingLayout.Spot> besideChips(String frameId) {
        RidingLayout.Frame f = RidingLayout.frameDef(frameId);
        List<RidingLayout.Spot> out = new ArrayList<>();
        if (f == null || f.switches.containsKey(RidingLayout.CHIPS)) return out;
        for (RidingLayout.Spot s : f.spots) if (s.kind == RidingLayout.Kind.CHIP) out.add(s);
        return out;
    }

    /** What a spot can show, in catalog order; never the top bar's contents. */
    static List<String> choices(RidingLayout.Spot spot) {
        if (spot == null || spot.kind == RidingLayout.Kind.TOP) return Collections.emptyList();
        return RidingLayout.choices(spot.kind);
    }

    /**
     * What the editor offers for a spot: its readings, and for a chip spot first of all None, an empty slot that takes
     * no room (a tester, Nov 14). Everything else offers exactly its choices.
     */
    static List<String> options(RidingLayout.Spot spot) {
        List<String> readings = choices(spot);
        if (spot == null || spot.kind != RidingLayout.Kind.CHIP) return readings;
        List<String> out = new ArrayList<>();
        out.add(RidingLayout.NONE);
        out.addAll(readings);
        return out;
    }

    /** The switches a frame has, in the editor's order. */
    static List<String> switches(String frameId) {
        RidingLayout.Frame f = RidingLayout.frameDef(frameId);
        List<String> out = new ArrayList<>();
        if (f == null) return out;
        for (String id : SWITCHES) if (f.switches.containsKey(id)) out.add(id);
        return out;
    }

    String content(String spotId) { return working.content(spotId); }
    boolean on(String switchId) { return working.on(switchId); }

    /** Chooses what a spot of the frame on show displays; refused for the top bar or a content it can't hold. */
    boolean choose(String spotId, String contentId) {
        RidingLayout.Frame f = RidingLayout.frameDef(frame());
        RidingLayout.Spot s = f == null ? null : f.spot(spotId);
        if (s == null || s.kind == RidingLayout.Kind.TOP) return false;
        return working.setContent(frame(), spotId, contentId);
    }

    boolean set(String switchId, boolean value) { return working.setOn(frame(), switchId, value); }

    /** Reset is offered only when the frame on show differs from its shipped choices. */
    boolean canReset() { return !working.isDefault(frame()); }
    /** Restores the frame on show to its shipped choices; the other frames keep theirs. */
    void reset() { working.reset(frame()); }

    /** True when Done has something to save. */
    boolean changed() { return !working.toJson().equals(savedJson); }

    /** A spot's number among the frame's spots of its kind (the second tile is 2), or 0 for a spot that's alone. */
    static int number(String frameId, RidingLayout.Spot spot) {
        RidingLayout.Frame f = RidingLayout.frameDef(frameId);
        if (f == null || spot == null) return 0;
        int count = 0, at = 0;
        for (RidingLayout.Spot s : f.spots) {
            if (s.kind != spot.kind) continue;
            count++;
            if (s.id.equals(spot.id)) at = count;
        }
        return count > 1 ? at : 0;
    }
}
