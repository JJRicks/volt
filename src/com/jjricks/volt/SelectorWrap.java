package com.jjricks.volt;

/**
 * A segmented selector that wraps its choices (SegmentedSelector.setWrapChoices): one row at the choices' natural widths
 * while they all fit, else rows of equal columns, as many as the widest word (with the choice's padding) allows, so
 * every choice shows its whole name at the user's text size with a full-size touch target, and nothing scrolls. A
 * selector can also ask for balanced rows ({@link #balancedColumns}).
 * Pure Java.
 */
final class SelectorWrap {
    private SelectorWrap() {}

    /** Whether the choices fit on one row at their natural widths (one unit throughout, pixels on a device). */
    static boolean oneRow(int naturalTotal, int available) {
        return naturalTotal <= available;
    }

    /**
     * The columns per row: every choice when they fit on one row, else as many columns of {@code available / columns}
     * as are at least {@code wordMinimum} wide (the widest word with its padding, never under a 48 dp target); at least
     * one, at most {@code count}. As every wrapping selector has always laid out its choices.
     */
    static int columns(int count, int naturalTotal, int available, int wordMinimum) {
        if (count <= 0) return 1;
        if (oneRow(naturalTotal, available)) return count;
        return Math.max(1, Math.min(count, available / Math.max(1, wordMinimum)));
    }

    /**
     * As {@link #columns(int, int, int, int)}, then balanced for a selector that asks for it (Data's section tabs): the
     * fewest columns that still need no more rows, so the last row is never a lone choice under a full row (four
     * choices that fit three to a row take two rows of two).
     */
    static int balancedColumns(int count, int naturalTotal, int available, int wordMinimum) {
        int most = columns(count, naturalTotal, available, wordMinimum);
        int rows = rows(count, most);
        return Math.max(1, (Math.max(1, count) + rows - 1) / rows);
    }

    /** The rows those columns make. */
    static int rows(int count, int columns) {
        return count <= 0 ? 1 : (count + Math.max(1, columns) - 1) / Math.max(1, columns);
    }
}
