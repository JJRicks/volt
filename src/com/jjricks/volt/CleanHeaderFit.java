package com.jjricks.volt;

/**
 * Clean Home's header (hub layout plan 1.5, part 8b): the status line on its own band, then the scooter's name with
 * Edit, search and Settings beside it, level with the name, as the mock-up draws it. Pure Java, so the JVM suite checks
 * it at the sizes the maintainers check (tests/CleanHeaderFitTest).
 *
 * <p>Both lines are tap targets (the status line opens the connection details, the name switches scooter), and the
 * three buttons keep 48 dp targets around a {@link #DISC_DP} dp disc. The name row is a full {@link #TARGET_DP} dp
 * band. The status line gets its target the way the widget dashboard's header gives it one: {@link #STATUS_PAD_DP} dp
 * of padding above and below its text, taken back by margins of the same size, so the target overlaps the space
 * above the header and the top of the name row instead of adding a band of its own. The header is then about the
 * mock-up's height (two text lines, the checklist's 45 to 60 dp plus the 48 dp name row's own space), not two 48 dp
 * bands stacked.
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
    /**
     * The status line's padding above and below its text (taken back by margins, as on the widget dashboard), the
     * space above it (the header's top padding) and under the header (above the top area).
     */
    static final int STATUS_PAD_DP = 16, TOP_DP = 8, BOTTOM_DP = 4;
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

    /** The status line's touch target (dp) for a line {@code statusLineDp} tall: the line and its padding. */
    static float statusTargetDp(float statusLineDp) {
        return statusLineDp + 2 * STATUS_PAD_DP;
    }

    /** The name row's height (dp) for a name {@code nameDp} tall (all its lines): a full target or the name. */
    static float nameRowDp(float nameDp) {
        return Math.max(TARGET_DP, nameDp);
    }

    /** The header's height (dp): the space above, the status line's text, the name row and the space under it. */
    static float heightDp(float statusLineDp, float nameDp) {
        return TOP_DP + statusLineDp + nameRowDp(nameDp) + BOTTOM_DP;
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
