package com.jjricks.volt;

/** Reading tiles grid arithmetic: rows fill without an empty slot; 1, 2 and 4 tiles keep their grid. */
public final class ReadingTileGridTest {
    private static int checks;
    private static final int GAP = 10;

    public static void main(String[] args) {
        lastTileFillsShortRow();
        fullRowsKeepEqualTiles();
        columnChoiceUnchanged();
        unfoldedDefaultHasNoGap();
        System.out.println("ReadingTileGrid tests passed: " + checks + " checks");
    }

    private static void lastTileFillsShortRow() {
        equal(ReadingTileGrid.span(0, 3, 2), 1, "3 in 2: first tile one column");
        equal(ReadingTileGrid.span(1, 3, 2), 1, "3 in 2: second tile one column");
        equal(ReadingTileGrid.span(2, 3, 2), 2, "3 in 2: last tile spans the row");
        // General rule, should the 4-reading cap ever rise.
        equal(ReadingTileGrid.span(4, 5, 3), 2, "5 in 3: last tile takes both leftover columns");
        equal(ReadingTileGrid.span(6, 7, 3), 3, "7 in 3: lone last tile spans the row");
        equal(ReadingTileGrid.span(3, 5, 3), 1, "5 in 3: tiles before the last stay one column");
        int cell = 98;
        equal(ReadingTileGrid.width(2, 3, 2, cell, GAP), 2 * cell + GAP, "Stretched tile covers both columns and the gap");
        equal(ReadingTileGrid.start(2, 2, cell, GAP), 0, "Stretched tile starts at the row start");
        equal(ReadingTileGrid.top(2, 2, 60, GAP), 70, "Stretched tile sits on row 2");
        equal(ReadingTileGrid.start(1, 2, cell, GAP), cell + GAP, "Second tile keeps its column");
    }

    private static void fullRowsKeepEqualTiles() {
        int[][] cases = {{1, 1}, {2, 1}, {2, 2}, {3, 1}, {3, 3}, {4, 1}, {4, 2}, {4, 4}};
        for (int[] c : cases) {
            int count = c[0], columns = c[1], cell = 77;
            for (int i = 0; i < count; i++) {
                String at = count + " in " + columns + ", tile " + i;
                equal(ReadingTileGrid.span(i, count, columns), 1, at + ": one column");
                equal(ReadingTileGrid.width(i, count, columns, cell, GAP), cell, at + ": column width");
                equal(ReadingTileGrid.start(i, columns, cell, GAP), (i % columns) * (cell + GAP), at + ": same start as before");
            }
        }
    }

    /** The column choice moved out of ReadingTiles unchanged; pin its old behaviour. */
    private static void columnChoiceUnchanged() {
        int min = 88;
        equal(ReadingTileGrid.columns(1, 50, GAP, min, 1, 0), 1, "One tile is always one column");
        equal(ReadingTileGrid.columns(2, 400, GAP, min, 4, 0), 2, "Two wide tiles stay side by side");
        equal(ReadingTileGrid.columns(2, 150, GAP, min, 4, 0), 1, "Two narrow tiles stack when rows allow");
        equal(ReadingTileGrid.columns(2, 150, GAP, min, 1, 0), 2, "A one-row card keeps both on one row");
        equal(ReadingTileGrid.columns(4, 400, GAP, min, 4, 0), 4, "Four wide tiles stay on one row");
        equal(ReadingTileGrid.columns(4, 300, GAP, min, 4, 0), 2, "Four wrap to 2x2, never 3+1");
        equal(ReadingTileGrid.columns(4, 300, GAP, min, 1, 0), 4, "Four on a one-row card stay in one row");
        equal(ReadingTileGrid.columns(4, 300, GAP, min, 1, 100), 2, "Numbers that need room still wrap past the row limit");
        equal(ReadingTileGrid.columns(3, 300, GAP, min, 4, 0), 3, "Three that fit stay on one row");
        equal(ReadingTileGrid.columns(3, 206, GAP, min, 4, 0), 2, "Three too narrow wrap to two columns");
        equal(ReadingTileGrid.rows(3, 2), 2, "Three in two columns take two rows");
        equal(ReadingTileGrid.rows(4, 2), 2, "Four in two columns take two rows");
        equal(ReadingTileGrid.cellWidth(206, 2, GAP), 98, "Column width shares the gap");
        equal(ReadingTileGrid.cellWidth(5, 4, GAP), 1, "Column width never drops below one pixel");
    }

    /** About 206 px of tile space (the unfolded default, in dp) with 3 readings: no empty slot. */
    private static void unfoldedDefaultHasNoGap() {
        int width = 206, count = 3;
        int columns = ReadingTileGrid.columns(count, width, GAP, 88, 4, 0);
        int cell = ReadingTileGrid.cellWidth(width, columns, GAP);
        int rowEnd = ReadingTileGrid.start(columns - 1, columns, cell, GAP) + cell;
        int last = count - 1;
        equal(ReadingTileGrid.start(last, columns, cell, GAP), 0, "Last tile's start edge meets the first tile's");
        equal(ReadingTileGrid.start(last, columns, cell, GAP) + ReadingTileGrid.width(last, count, columns, cell, GAP),
                rowEnd, "Last tile's end edge meets the second tile's");
        check(rowEnd <= width, "Row stays inside the widget");
    }

    private static void equal(int actual, int expected, String message) {
        check(actual == expected, message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
