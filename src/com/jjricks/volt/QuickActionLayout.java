package com.jjricks.volt;

/**
 * Clean Home's quick actions as the Layout Plan draws them (owner's decision on #383, 2026-11-13): four equal circles
 * in one row, each action's name under its circle, outside it, and its value (90%, Off, Recording) as a second line
 * under the name. Pure Java, so the JVM suite checks it at the sizes the maintainers check (tests/QuickActionLayoutTest).
 *
 * <p>The circle does not grow with large text: only the words under it do. A name takes, in this order, the first
 * that fits its column: the full name on one line, the short form on one line, the short form on two lines of whole
 * words, the full name on two lines of whole words; only then does the short form get smaller (down to
 * {@link #MIN_SCALE} of its size, on one line or two), and failing that it takes two lines and ends in an ellipsis (its
 * spoken name stays whole). A value takes one line, else two lines of whole words; a lock word that fits neither drops
 * (the circle's open or closed padlock and the spoken state remain); any other value gets smaller the same way, else
 * ends in an ellipsis on its second line. Widths are measured at the full size, in the same unit as {@code room}.
 */
final class QuickActionLayout {
    /** The plan's circle (36 px in the mock-up, at 1.5 dp per px), and the smallest a circle gets on a narrow column. */
    static final int CIRCLE_DP = 54, MIN_CIRCLE_DP = 44;
    /** Four columns while each is at least this wide; else two rows of two. */
    static final int MIN_COLUMN_DP = 64;
    /**
     * The space between columns (and rows), the column's side padding (none: the gap already keeps two names apart),
     * and the space between the circle and the name.
     */
    static final int GAP_DP = 6, SIDE_DP = 0, NAME_GAP_DP = 6;
    /** The name's and the value's text sizes (sp, before the text scale). */
    static final float NAME_SP = 12, VALUE_SP = 11;
    /** Words get no smaller than this share of their size, and only once no form of them fits at full size. */
    static final float MIN_SCALE = .8f;
    private static final float SCALE_STEP = .05f;

    private QuickActionLayout() {}

    /** A text's width at the size being fitted. */
    interface Width { float of(String text); }

    /** What a line of words under the circle shows. */
    static final class Words {
        /** The text shown (the full name or its short form), or null when nothing shows. */
        final String text;
        /** One or two lines; 0 when nothing shows. */
        final int lines;
        /** The text was the short form. */
        final boolean brief;
        /** Two lines were not enough even at the smallest size: the second ends in an ellipsis. */
        final boolean clipped;
        /** The text's size as a share of its full size (1 unless no form fitted at full size). */
        final float scale;

        Words(String text, int lines, boolean brief, boolean clipped) { this(text, lines, brief, clipped, 1); }

        Words(String text, int lines, boolean brief, boolean clipped, float scale) {
            this.text = text; this.lines = lines; this.brief = brief; this.clipped = clipped; this.scale = scale;
        }

        @Override public String toString() {
            return text == null ? "none" : "\"" + text + "\" " + lines + (lines == 1 ? " line" : " lines")
                    + (scale < 1 ? " at " + scale : "") + (clipped ? ", clipped" : "");
        }
    }

    /** Columns for {@code count} actions across {@code width} dp: four in a row while each column is wide enough. */
    static int columns(float width, int count) {
        if (count <= 1) return 1;
        int columns = Math.min(4, count);
        while (columns > 1 && column(width, columns) < MIN_COLUMN_DP) columns = columns == 4 ? 2 : columns - 1;
        return columns;
    }

    /** One column's width in dp, for a row {@code width} dp wide and that many columns. */
    static float column(float width, int columns) {
        return (width - (columns - 1) * GAP_DP) / Math.max(1, columns);
    }

    /** The circle's diameter in a column this wide (dp): the plan's 54 dp, less on a narrow column. */
    static int circleDp(float column) {
        return (int) Math.max(MIN_CIRCLE_DP, Math.min(CIRCLE_DP, Math.floor(column - 2 * SIDE_DP)));
    }

    /** The room for words in a column this wide (dp): the column less its side padding. */
    static float room(float column) { return Math.max(0, column - 2 * SIDE_DP); }

    /** The name under a circle: short forms before anything wraps, and wrapping before anything is cut. */
    static Words name(String full, String brief, float room, Width width) {
        if (full == null || full.isEmpty()) return new Words(null, 0, false, false);
        boolean hasBrief = brief != null && !brief.isEmpty() && !brief.equals(full);
        if (fits(full, room, 1, width)) return new Words(full, 1, false, false);
        if (hasBrief && fits(brief, room, 1, width)) return new Words(brief, 1, true, false);
        if (hasBrief && fits(brief, room, 2, width)) return new Words(brief, 2, true, false);
        if (fits(full, room, 2, width)) return new Words(full, 2, false, false);
        return smaller(hasBrief ? brief : full, hasBrief, room, width);
    }

    /** The value line under the name: one line, else two whole-word lines; a lock word that fits neither drops. */
    static Words value(String value, boolean lockWord, float room, Width width) {
        if (value == null || value.isEmpty()) return new Words(null, 0, false, false);
        if (fits(value, room, 1, width)) return new Words(value, 1, false, false);
        if (fits(value, room, 2, width)) return new Words(value, 2, false, false);
        if (lockWord) return new Words(null, 0, false, false);
        return smaller(value, false, room, width);
    }

    /** The text at the largest size from {@link #MIN_SCALE} up that fits one line, else two; else clipped at full size. */
    private static Words smaller(String text, boolean brief, float room, Width width) {
        for (int step = 1; 1 - step * SCALE_STEP >= MIN_SCALE - 1e-4f; step++) {
            float scale = Math.round((1 - step * SCALE_STEP) * 100) / 100f;
            for (int lines = 1; lines <= 2; lines++)
                if (fits(text, room / scale, lines, width)) return new Words(text, lines, brief, false, scale);
        }
        return new Words(text, 2, brief, true);
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
