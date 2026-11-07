package com.jjricks.volt;

/** ReadingSpan: the last Reading tile fills a part-filled last row; full rows are unchanged. */
public final class ReadingSpanTest {
    private static int checks;
    public static void main(String[] args) {
        // The unfolded Home case: 3 readings in 2 columns wrap 2 + 1, and the third spans the row.
        require(ReadingSpan.last(3, 2) == 2, "3 tiles in 2 columns: the last spans 2");
        // Leftover room in a wider grid goes to the last tile.
        require(ReadingSpan.last(3, 4) == 2, "3 tiles in 4 columns: the last takes the leftover 2");
        require(ReadingSpan.last(1, 4) == 4, "1 tile in 4 columns spans all 4");
        require(ReadingSpan.last(5, 4) == 4, "5 tiles in 4 columns: the lone fifth spans the row");
        require(ReadingSpan.last(5, 2) == 2, "5 tiles in 2 columns: the fifth spans 2");
        require(ReadingSpan.last(5, 3) == 2, "5 tiles in 3 columns: the fifth takes the leftover 2");
        // Full rows: every tile stays one cell.
        require(ReadingSpan.last(4, 2) == 1, "4 tiles in 2 columns: full rows");
        require(ReadingSpan.last(3, 3) == 1, "3 tiles in 3 columns: one full row");
        require(ReadingSpan.last(4, 4) == 1, "4 tiles in 4 columns: one full row");
        require(ReadingSpan.last(2, 2) == 1, "2 tiles in 2 columns: one full row");
        require(ReadingSpan.last(6, 3) == 1, "6 tiles in 3 columns: two full rows");
        // One column (large text, narrow cards): nothing to span.
        require(ReadingSpan.last(1, 1) == 1, "1 tile in 1 column");
        require(ReadingSpan.last(2, 1) == 1, "2 tiles in 1 column");
        require(ReadingSpan.last(3, 1) == 1, "3 tiles in 1 column");
        // Never wider than the grid, never under one cell, for every count and column choice.
        for (int columns = 1; columns <= 6; columns++)
            for (int count = 1; count <= 12; count++) {
                int span = ReadingSpan.last(count, columns);
                require(span >= 1 && span <= columns, count + " in " + columns + " stays inside the grid");
                int lastColumn = (count - 1) % columns;
                require(lastColumn + span == columns || span == 1 && lastColumn == columns - 1,
                        count + " in " + columns + " ends at the row's end edge");
            }
        // Empty or invalid input is one cell.
        require(ReadingSpan.last(0, 2) == 1 && ReadingSpan.last(3, 0) == 1, "No tiles or no columns: 1");
        // Width covers the spanned cells and the gaps between them.
        require(ReadingSpan.width(1, 100, 10) == 100, "One cell has no gap");
        require(ReadingSpan.width(2, 100, 10) == 210, "Two cells cover one gap");
        require(ReadingSpan.width(3, 90, 12) == 294, "Three cells cover two gaps");
        require(ReadingSpan.width(0, 100, 10) == 100, "A zero span is one cell");
        System.out.println("ReadingSpan: " + checks + " checks passed");
    }
    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
