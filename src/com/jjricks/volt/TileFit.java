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
