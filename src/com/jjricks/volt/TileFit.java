package com.jjricks.volt;

import java.util.function.ToDoubleFunction;

/**
 * How Just back's three small tiles fit their names and values whole (council early review of part 9): the tiles
 * stand three in a row while every name fits a third of the row, else two in a row with the last spanning the second
 * row (as ReadingSpan spans a part-filled row), so a name is never broken inside a word nor cut at normal text size;
 * and a value takes the first rung of its own ladder that fits (both units, then the first alone). A rung fits when every word of it fits the room on its own (no
 * word is ever broken inside) and the words, wrapped greedily at spaces, take at most maxLines lines. Pure: the
 * caller measures text (a Paint at the size the text will be drawn, or at its readable floor for a value that shrinks).
 */
final class TileFit {
    private TileFit() {}

    /**
     * How many tiles stand in a row: 3 when every name fits whole in thirdRoom (at most nameLines lines), else 2.
     * Two in a row is the last step: with very large text a name may still not fit halfRoom, and it ends in an ellipsis.
     */
    static int columns(String[] names, ToDoubleFunction<String> width, double thirdRoom, int nameLines) {
        for (String name : names) if (!fits(name, width, thirdRoom, nameLines)) return 2;
        return 3;
    }

    /**
     * As {@link #columns(String[], ToDoubleFunction, double, int)}, but two in a row only when their height, twoRowHeight,
     * fits room: otherwise 3 (names then end in an ellipsis), since the tiles' area has a fixed height and nothing may
     * run past it.
     */
    static int columns(String[] names, ToDoubleFunction<String> width, double thirdRoom, int nameLines, double twoRowHeight, double room) {
        int columns = columns(names, width, thirdRoom, nameLines);
        return columns == 2 && twoRowHeight > room ? 3 : columns;
    }

    /** {@link #layout}'s answers: how the three tiles stand. */
    static final int THREE = 3, TWO = 2, TWO_TIGHT = 22, THREE_CUT = 33;

    /**
     * How the tiles stand in an area of fixed height (council's second review of part 9, finding 3: in a short split
     * screen, Swedish "Medelhastighet" broke inside the word when the tiles were forced three in a row). A name is never
     * broken inside a word:
     * <ol>
     * <li>{@link #THREE}, three in a row, while every name fits a third whole (at most nameLines lines);</li>
     * <li>else {@link #TWO}, two in a row with the last spanning the second row, when those two rows (twoRowHeight) fit room;</li>
     * <li>else {@link #TWO_TIGHT}, the same two rows with the tiles' tight padding (tightHeight), when they fit room;</li>
     * <li>else {@link #THREE_CUT}: three in a row, and a name with a word wider than its third takes one line ending in an
     * ellipsis ({@link #nameLines}): cut whole words at the end, never broken inside one. Only very large text in a
     * very short window gets here.</li>
     * </ol>
     */
    static int layout(String[] names, ToDoubleFunction<String> width, double thirdRoom, int nameLines, double twoRowHeight,
                      double tightHeight, double room) {
        if (columns(names, width, thirdRoom, nameLines) == 3) return THREE;
        if (twoRowHeight <= room) return TWO;
        if (tightHeight <= room) return TWO_TIGHT;
        return THREE_CUT;
    }

    /**
     * The lines a name may take in a tile room wide: nameLines while every word of it fits the room on its own (it wraps at
     * spaces, and ends in an ellipsis at worst), else 1, so a word too wide is cut at the end with an ellipsis instead of
     * being broken across lines inside the word.
     */
    static int nameLines(String name, ToDoubleFunction<String> width, double room, int nameLines) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return nameLines;
        for (String word : trimmed.split("\\s+")) if (width.applyAsDouble(word) > room) return 1;
        return nameLines;
    }

    /** The index of the first rung of rungs that fits room in maxLines lines; the last rung's index when none does. */
    static int pick(String[] rungs, ToDoubleFunction<String> width, double room, int maxLines) {
        for (int i = 0; i < rungs.length; i++) if (fits(rungs[i], width, room, maxLines)) return i;
        return rungs.length - 1;
    }

    /** Whether text fits room in at most maxLines lines without a word broken inside. Empty text fits. */
    static boolean fits(String text, ToDoubleFunction<String> width, double room, int maxLines) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return true;
        if (maxLines < 1 || room <= 0) return false;
        String[] words = trimmed.split("\\s+");
        int lines = 1;
        String line = "";
        for (String word : words) {
            if (width.applyAsDouble(word) > room) return false;
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width.applyAsDouble(joined) <= room) { line = joined; continue; }
            if (++lines > maxLines) return false;
            line = word;
        }
        return true;
    }
}
