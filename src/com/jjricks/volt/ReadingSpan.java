package com.jjricks.volt;

/**
 * How many columns the last Reading tile spans. A part-filled last row would leave empty cells; the
 * last tile takes that room instead, so 3 readings in 2 columns wrap 2 + 1 with the third as wide as
 * the row (owner, Nov 5: the last tile takes the wide slot; reorder readings to choose which one).
 * Full rows keep every tile one column wide.
 *
 * Pure Java (no Android), so the JVM tests reach it; DashboardScreen's ReadingTiles uses it.
 */
final class ReadingSpan {
    private ReadingSpan() {}

    /** Columns the last of {@code count} tiles spans in a {@code columns}-wide grid; 1 when the last row is full. */
    static int last(int count, int columns) {
        if (count <= 0 || columns <= 1) return 1;
        return columns - (count - 1) % columns;
    }

    /** Width of a tile spanning {@code span} cells of {@code cellWidth}, with the gaps it covers. */
    static int width(int span, int cellWidth, int gap) {
        int cells = Math.max(1, span);
        return cells * cellWidth + (cells - 1) * gap;
    }
}
