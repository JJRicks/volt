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
        splitThree();
        refit();
        System.out.println("ReadingSpan: " + checks + " checks passed");
    }
    /** 3 readings wrap 2 + 1 in a tall, narrow card; sizes in dp (gap 10, full tile 96 at 1x text). */
    private static void splitThree() {
        // The unfolded Fold's Widgets Home: the card beside the overview, about 291 x 246 dp.
        // One row: 90 x 246 pillars. Two rows: 140 x 118 tiles, closer to square, full tiles fit.
        require(ReadingSpan.splitThree(3, 3, 291, 246, 10, 96), "Unfolded Widgets: 3 pillars wrap 2 + 1");
        // Phones (Widgets Home's tiles card is two grid rows, about 118 dp): two rows can't hold full tiles.
        for (int width : new int[] {312, 328, 380})
            require(!ReadingSpan.splitThree(3, 3, width, 118, 10, 96), width + " dp phone card keeps one row");
        // A full-width wide card (758 x 246): one row's tiles are square already.
        require(!ReadingSpan.splitThree(3, 3, 758, 246, 10, 96), "Square tiles stay in one row");
        // A three-row card on the Fold (182 dp): two rows of 86 dp can't hold full tiles.
        require(!ReadingSpan.splitThree(3, 3, 291, 182, 10, 96), "Three grid rows: full tiles don't fit twice");
        // At 1.3x text the full tile is taller (125 dp) and the grid rows too (4 rows, 323 dp): still wraps.
        require(ReadingSpan.splitThree(3, 3, 291, 323, 10, 125), "Unfolded Widgets at 1.3x text wraps");
        // Only 3 readings, only from one row of 3, and only with a height budget.
        for (int count : new int[] {1, 2, 4, 5})
            require(!ReadingSpan.splitThree(count, Math.min(count, 3), 291, 246, 10, 96), count + " readings unchanged");
        require(!ReadingSpan.splitThree(3, 2, 291, 246, 10, 96), "Already 2 columns: nothing to do");
        require(!ReadingSpan.splitThree(3, 1, 291, 246, 10, 96), "One column: nothing to do");
        require(!ReadingSpan.splitThree(3, 3, 291, 0, 10, 96), "No height budget: never wraps");
        require(!ReadingSpan.splitThree(3, 3, 0, 246, 10, 96) && !ReadingSpan.splitThree(3, 3, 20, 246, 10, 96)
                && !ReadingSpan.splitThree(3, 3, 291, 246, 10, 0), "Degenerate sizes: never wraps");
        // Exactly two full tiles high wraps; a pixel less doesn't.
        require(ReadingSpan.splitThree(3, 3, 291, 202, 10, 96) && !ReadingSpan.splitThree(3, 3, 291, 201, 10, 96),
                "Two full rows exactly: the boundary");
        // Widening the card flips it back to one row once, and never again (no width band that flickers).
        for (int budget : new int[] {202, 246, 323, 500}) {
            int flips = 0;
            boolean previous = ReadingSpan.splitThree(3, 3, 60, budget, 10, 96);
            for (int width = 61; width <= 2000; width++) {
                boolean now = ReadingSpan.splitThree(3, 3, width, budget, 10, 96);
                if (now != previous) { flips++; require(previous, budget + " dp: only wrap-to-row flips, at " + width); }
                previous = now;
            }
            require(flips <= 1, budget + " dp high card flips " + flips + " times");
        }
        // The flip for the unfolded card's height sits between the Fold's 291 dp card and a square tile.
        int flip = 0;
        for (int width = 291; width <= 758 && flip == 0; width++)
            if (!ReadingSpan.splitThree(3, 3, width, 246, 10, 96)) flip = width;
        require(flip > 291 && flip < 758, "246 dp card: the flip lies between the Fold card and a full-width one (" + flip + ")");
    }

    /**
     * Whether the Reading tiles search their label mode again (#514 review, F02): a change of space always does; in the
     * same space, a value that grew past the row (a stored trips total gaining a digit) does, unless the tiles already
     * show values only (the last mode: searching again can't fit more, and would only ask for another layout).
     */
    static void refit() {
        require(ReadingSpan.refit(false, 40, 60, false) && ReadingSpan.refit(false, 80, 60, true), "a new size, budget or column count searches again");
        require(!ReadingSpan.refit(true, 60, 60, false) && !ReadingSpan.refit(true, 40, 60, false), "the same space with tiles that still fit keeps its mode");
        require(ReadingSpan.refit(true, 61, 60, false), "the same space with a value grown past the row searches again");
        require(!ReadingSpan.refit(true, 80, 60, true), "values only and still too tall: nothing more to drop, no new search");
        require(!ReadingSpan.refit(true, 80, Integer.MAX_VALUE, false), "no height budget: never too tall");
    }

    private static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
