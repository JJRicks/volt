package com.jjricks.volt;

/**
 * Clean Home's header (hub layout plan 1.5, part 8b): the status line on its own band, then the scooter's name with
 * Edit, search and Settings beside it, level with the name, as the mock-up draws it. Pure Java, so the JVM suite checks
 * it at the sizes the maintainers check (tests/CleanHeaderFitTest).
 *
 * <p>Both lines are tap targets (the status line opens the connection details, the name switches scooter), and the
 * three buttons keep 48 dp targets around a {@link #DISC_DP} dp disc. The name row is a full {@link #TARGET_DP} dp
 * band, and so is the status line: a band of its own from the header's top to the name row, its text at the band's
 * foot (at least {@link #TOP_DP} dp under the header's top), so no other view covers any of it. (Margins taken back
 * from padding, as the widget dashboard's header does, would put half of it under the name row, which takes every
 * touch there: Clean's name fills its row.) The header is two 48 dp bands, about 100 dp at 1x.
 *
 * <p>The name shares its row with the three buttons, so it takes, in this order, the first that fits: one line at full
 * size, one line smaller (down to {@link #MIN_NAME_SP} sp of {@link #NAME_SP}), two lines of whole words at full size,
 * two lines smaller; only then is its second line cut. Widths are measured at the full size, in the same unit as
 * {@code room}.
 */
final class CleanHeaderFit {
    /** A tap target's least height (and the buttons' size), and a header button's visible disc (the mock-up's ~39 dp). */
    static final int TARGET_DP = 48, DISC_DP = 40;
    /** Three buttons beside the name (Edit, search, Settings), and the space before the first. */
    static final int BUTTONS = 3, BUTTON_GAP_DP = 4;
    /** The least space above the status line's text (inside its band) and under the header (above the top area). */
    static final int TOP_DP = 8, BOTTOM_DP = 4;
    /** The name's text size and the smallest it gets before it wraps (sp, before the text scale). */
    static final float NAME_SP = 23, MIN_NAME_SP = 16;
    private static final float SCALE_STEP = .05f;

    private CleanHeaderFit() {}

    /** A text's width at the name's full size. */
    interface Width { float of(String text); }

    /** How the name shows. */
    static final class Name {
        /** One or two lines. */
        final int lines;
        /** The name's size as a share of its full size. */
        final float scale;
        /** Two lines were not enough even at the smallest size: the second ends in an ellipsis. */
        final boolean clipped;

        Name(int lines, float scale, boolean clipped) { this.lines = lines; this.scale = scale; this.clipped = clipped; }

        @Override public String toString() {
            return lines + (lines == 1 ? " line" : " lines") + (scale < 1 ? " at " + scale : "") + (clipped ? ", clipped" : "");
        }
    }

    /** The smallest share of its size the name gets. */
    static float minScale() { return MIN_NAME_SP / NAME_SP; }

    /** The room for the name (dp) in a header row {@code row} dp wide: the row less the three buttons. */
    static float nameRoomDp(float row) {
        return Math.max(0, row - BUTTONS * TARGET_DP - BUTTON_GAP_DP);
    }

    /** The name in {@code room}: smaller before it wraps, and wrapped before it is cut. */
    static Name name(String text, float room, Width width) {
        if (text == null || text.trim().isEmpty()) return new Name(1, 1, false);
        for (int lines = 1; lines <= 2; lines++) {
            if (fits(text, room, lines, width)) return new Name(lines, 1, false);
            for (int step = 1; 1 - step * SCALE_STEP >= minScale() - 1e-4f; step++) {
                float scale = Math.round((1 - step * SCALE_STEP) * 100) / 100f;
                if (fits(text, room / scale, lines, width)) return new Name(lines, scale, false);
            }
        }
        return new Name(2, Math.round(minScale() * 100) / 100f, true);
    }

    /**
     * The status line's touch target (dp) for a line {@code statusLineDp} tall: its whole band, the line and the space
     * above it, at least {@link #TARGET_DP}. Nothing overlaps it, so all of it takes touches.
     */
    static float statusTargetDp(float statusLineDp) {
        return Math.max(TARGET_DP, TOP_DP + statusLineDp);
    }

    /** The name row's height (dp) for a name {@code nameDp} tall (all its lines): a full target or the name. */
    static float nameRowDp(float nameDp) {
        return Math.max(TARGET_DP, nameDp);
    }

    /** The header's height (dp): the status line's band, the name row and the space under it. */
    static float heightDp(float statusLineDp, float nameDp) {
        return statusTargetDp(statusLineDp) + nameRowDp(nameDp) + BOTTOM_DP;
    }

    /** Whether {@code text} breaks into at most {@code lines} lines of whole words, each within {@code room}. */
    static boolean fits(String text, float room, int lines, Width width) {
        String[] words = text.trim().split(" +");
        int used = 1;
        String line = "";
        for (String word : words) {
            if (width.of(word) > room) return false;
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width.of(joined) <= room) { line = joined; continue; }
            if (++used > lines) return false;
            line = word;
        }
        return true;
    }
}
