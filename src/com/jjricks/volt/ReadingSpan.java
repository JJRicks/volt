package com.jjricks.volt;

/**
 * How many columns the last Reading tile spans. A part-filled last row would leave empty cells; the
 * last tile takes that room instead, so 3 readings in 2 columns wrap 2 + 1 with the third as wide as
 * the row (owner, Nov 5: the last tile takes the wide slot; reorder readings to choose which one).
 * Full rows keep every tile one column wide.
 *
 * Three readings in a tall, narrow card (the unfolded Fold's Widgets Home puts them beside the overview,
 * about 290 x 246 dp) would be three thin pillars side by side; {@link #splitThree} wraps them 2 + 1
 * instead (owner, Nov 15 2026).
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

    /**
     * Whether 3 readings that fit one row ({@code columns} 3) should wrap 2 + 1 instead: when the two rows'
     * tiles come out closer to square than the one row's, and each of the two rows still holds a full tile
     * (icon, name and value; {@code fullTile} high). One row's tiles are the card's full height
     * ({@code budget}); two rows split it. Sizes in pixels; no height budget (0) never wraps.
     *
     * Only the card's size goes in, never the values, so a reading changing every second cannot flip it.
     */
    static boolean splitThree(int count, int columns, int width, int budget, int gap, int fullTile) {
        if (count != 3 || columns != 3 || width <= 0 || budget <= 0 || fullTile <= 0) return false;
        int across = (width - 2 * gap) / 3, half = (width - gap) / 2, rowHeight = (budget - gap) / 2;
        if (across <= 0 || half <= 0 || rowHeight < fullTile) return false;
        return squareness(budget, across) > squareness(rowHeight, half);
    }

    /** How far a tile is from square: 0 for square, the same for 2:1 and 1:2. */
    private static double squareness(int height, int width) {
        return Math.abs(Math.log((double) height / width));
    }

    /** Width of a tile spanning {@code span} cells of {@code cellWidth}, with the gaps it covers. */
    static int width(int span, int cellWidth, int gap) {
        int cells = Math.max(1, span);
        return cells * cellWidth + (cells - 1) * gap;
    }
}
