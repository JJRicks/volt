package com.jjricks.volt;

/**
 * How Battery & charging's summary card (HubBatterySummary) places its large level, as the owner's Layout Plan draws it:
 * the digits' top level with the right column's first line, and the range line about 10 dp under the digits, whatever
 * empty space the font keeps above and below its glyphs. Pure, so the host tests run it; every figure is in pixels.
 */
final class BatterySummaryFit {
    private BatterySummaryFit() {}

    /** The gap the plan leaves between the level's digits and the range line under them, dp. */
    static final int RANGE_GAP_DP = 10;

    /**
     * The level's top and bottom margins: glyphTop is the space from the level's top (no font padding) to its digits'
     * top, glyphBottomGap the space from the digits' baseline-bottom to the view's bottom, besideCapTop where the right
     * column's first line's capitals start (0 when the column is stacked under the level, or hidden). The top margin
     * puts the digits' top at besideCapTop; the bottom margin takes the space under the digits away.
     */
    static int[] levelMargins(int glyphTop, int glyphBottomGap, int besideCapTop) {
        return new int[]{besideCapTop - glyphTop, -Math.max(0, glyphBottomGap)};
    }

    /** The range line's top padding: the wanted gap less the space its own font keeps above its capitals (never negative). */
    static int rangePadding(int wantedGap, int rangeCapTop) { return Math.max(0, wantedGap - Math.max(0, rangeCapTop)); }

    /**
     * Whether the right column goes under the level and range instead of beside them: when the level's width, the
     * column's and the gap between them don't fit the room (a narrow screen at large text). The level is never shrunk.
     */
    static boolean stacks(int room, int columnWidth, int gap, float levelWidth) { return room - columnWidth - gap < levelWidth; }

    /**
     * The right column's width: its widest shown line (a reading's name, the gap and its value, or the state line), so
     * "Health 97%" is never cut to the width of a shorter state line such as "Charging"; never more than the room.
     * A line that isn't shown is passed as 0 (or less).
     */
    static int columnWidth(int room, int... lineWidths) {
        int widest = 0;
        for (int width : lineWidths) widest = Math.max(widest, width);
        return Math.max(0, Math.min(room, widest));
    }

    /** A reading's line in the right column: its name, the gap and its value, rounded up so no glyph is clipped. */
    static int factWidth(float nameWidth, int gap, float valueWidth) {
        return (int) Math.ceil(nameWidth) + gap + (int) Math.ceil(valueWidth);
    }
}
