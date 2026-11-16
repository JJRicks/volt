package com.jjricks.volt;

/** SelectorWrap: a wrapping selector's rows and columns (the lights' patterns, Rides' colours, Settings' selectors), balanced for Data's tabs. */
public final class SelectorWrapTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        // One row while the natural widths fit, every choice its own column.
        require(SelectorWrap.oneRow(300, 300) && !SelectorWrap.oneRow(301, 300), "exactly the room fits on one row");
        require(SelectorWrap.columns(4, 250, 300, 107) == 4 && SelectorWrap.rows(4, 4) == 1, "fits: one row of four");
        // Too wide: as many columns as the widest word allows, as every wrapping selector has always laid them out (the
        // lights' patterns, Rides' colours, Settings' selectors keep exactly this).
        require(SelectorWrap.columns(4, 346, 300, 107) == 2 && SelectorWrap.rows(4, 2) == 2, "two fit a row: two rows of two");
        require(SelectorWrap.columns(4, 346, 331, 107) == 3 && SelectorWrap.rows(4, 3) == 2, "unbalanced: three and one");
        require(SelectorWrap.columns(5, 600, 450, 107) == 4, "unbalanced: four and one");
        require(SelectorWrap.columns(4, 400, 90, 107) == 1 && SelectorWrap.rows(4, 1) == 4, "narrower than one word: one column");
        require(SelectorWrap.columns(0, 0, 0, 0) == 1 && SelectorWrap.rows(0, 1) == 1 && SelectorWrap.columns(3, 9, 5, 0) == 3, "nonsense: still a column");
        for (int count = 1; count <= 9; count++)
            for (int available = 40; available <= 800; available += 20)
                for (int word = 1; word <= 200; word += 13) {
                    int natural = count * word + 1;
                    require(SelectorWrap.columns(count, natural, available, word) == Math.max(1, Math.min(count, available / word)), count + " choices in " + available + ": the old rule, unchanged");
                }
        // Balanced (Data's tabs ask for it): the fewest columns that need no more rows, so no lone last choice.
        require(SelectorWrap.balancedColumns(4, 250, 300, 107) == 4, "balanced, fits: one row of four");
        require(SelectorWrap.balancedColumns(4, 346, 300, 107) == 2, "balanced, two fit a row: two rows of two");
        require(SelectorWrap.balancedColumns(4, 346, 331, 107) == 2, "balanced, three would fit a row: two rows of two (not three and one)");
        require(SelectorWrap.balancedColumns(5, 600, 331, 107) == 3 && SelectorWrap.rows(5, 3) == 2, "balanced, five, three to a row: three and two");
        require(SelectorWrap.balancedColumns(5, 600, 450, 107) == 3, "balanced, five, four would fit: three and two (not four and one)");
        require(SelectorWrap.balancedColumns(7, 900, 331, 107) == 3 && SelectorWrap.rows(7, 3) == 3, "balanced, seven, three to a row: three rows");
        require(SelectorWrap.balancedColumns(6, 900, 450, 107) == 3, "balanced, six, four would fit: two rows of three");
        require(SelectorWrap.balancedColumns(4, 400, 90, 107) == 1 && SelectorWrap.balancedColumns(0, 0, 0, 0) == 1, "balanced: one column at least");
        for (int count = 1; count <= 9; count++)
            for (int available = 40; available <= 800; available += 20)
                for (int word = 48; word <= 200; word += 16) {
                    int natural = count * word + 1, most = SelectorWrap.columns(count, natural, available, word);
                    int columns = SelectorWrap.balancedColumns(count, natural, available, word), rows = SelectorWrap.rows(count, columns);
                    require(columns >= 1 && columns <= most, count + " choices in " + available + ": never more columns than the words allow");
                    require(rows == SelectorWrap.rows(count, most), count + " choices in " + available + ": balancing never adds a row");
                    require(columns * rows >= count && (columns - 1) * rows < count, count + " choices in " + available + ": the fewest columns for those rows");
                }
        // Data's section tabs (StatsScreen, compact padding 4 dp a side, 13 sp bold in Saira Bold): the page is the window
        // less 18 dp a side, the selector's 4 dp track padding a side. Each tab is its name and 8 dp of padding, 48 dp at
        // least. The council's note: at 344 dp with 1.4x text the one scrolling row cut "Diagnostics" to "Diagno".
        java.awt.Font bold = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(args[0]));
        java.awt.font.FontRenderContext context = new java.awt.font.FontRenderContext(null, true, true);
        String[][] languages = {{"en", "Overview", "Readings", "Charts", "Diagnostics"}, {"de", "Übersicht", "Messwerte", "Diagramme", "Diagnose"}};
        int[][] expected = {
                // window, text scale x10, columns in English, columns in German
                {344, 10, 4, 4}, {344, 14, 2, 2}, {360, 14, 2, 2}, {375, 14, 2, 2}, {412, 14, 4, 2}};
        for (int[] at : expected)
            for (int l = 0; l < languages.length; l++) {
                java.awt.Font font = bold.deriveFont(13f * at[1] / 10);
                int available = at[0] - 2 * 18 - 2 * 4, natural = 0, word = 48;
                for (int i = 1; i < languages[l].length; i++) {
                    int name = (int) Math.ceil(font.getStringBounds(languages[l][i], context).getWidth());
                    natural += Math.max(48, name + 8);
                    word = Math.max(word, name + 8);
                }
                int columns = SelectorWrap.balancedColumns(4, natural, available, word);
                require(columns == at[2 + l], languages[l][0] + " at " + at[0] + " dp, " + at[1] / 10f + "x: " + columns + " columns, expected " + at[2 + l]);
                // Every tab at least its widest name and a 48 dp target wide: no name is cut.
                require(available / columns >= word || columns == 4 && natural <= available, languages[l][0] + " at " + at[0] + " dp: every name whole");
            }
        System.out.println("SelectorWrapTest: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
