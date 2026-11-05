package com.jjricks.volt;

/**
 * Grid arithmetic for the Reading tiles widget, in pixels, with no Android types. Tiles fill rows
 * in reading order; when the last row is short, its last tile stretches across the leftover
 * columns so the row has no empty slot (3 readings in 2 columns: two tiles, then one 2 wide).
 * Positions run from the start edge; the view mirrors them for right-to-left layouts.
 */
final class ReadingTileGrid {
    private ReadingTileGrid() {}

    /**
     * Columns for {@code count} tiles in {@code width}. Wraps (4 to 2, otherwise one fewer) while a
     * tile would be narrower than {@code minimum}, but only into rows {@code maxRows} can hold; then
     * keeps wrapping, whatever the rows, while a tile is narrower than {@code required}.
     */
    static int columns(int count, int width, int gap, int minimum, int maxRows, float required) {
        int columns = Math.max(1, count);
        while (columns > 1 && (width - (columns - 1) * gap) / columns < minimum) {
            int next = fewer(columns);
            if ((count + next - 1) / next > maxRows) break;
            columns = next;
        }
        while (columns > 1 && (width - (columns - 1) * gap) / columns < required) columns = fewer(columns);
        return columns;
    }

    private static int fewer(int columns) {
        return columns == 4 ? 2 : columns - 1;
    }

    static int rows(int count, int columns) {
        return (count + columns - 1) / columns;
    }

    /** One column's width; every tile is this wide except a stretched last tile. */
    static int cellWidth(int width, int columns, int gap) {
        return Math.max(1, (width - (columns - 1) * gap) / columns);
    }

    /** Columns tile {@code index} covers: 1, or the leftover columns for the last tile of a short row. */
    static int span(int index, int count, int columns) {
        if (columns <= 1 || index != count - 1) return 1;
        return columns - index % columns;
    }

    /** Width of tile {@code index}, including the gaps it spans. */
    static int width(int index, int count, int columns, int cellWidth, int gap) {
        int span = span(index, count, columns);
        return span * cellWidth + (span - 1) * gap;
    }

    /** Distance of tile {@code index}'s start edge from the widget's start edge. */
    static int start(int index, int columns, int cellWidth, int gap) {
        return (index % columns) * (cellWidth + gap);
    }

    static int top(int index, int columns, int rowHeight, int gap) {
        return (index / columns) * (rowHeight + gap);
    }
}
