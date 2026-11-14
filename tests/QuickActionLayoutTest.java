package com.jjricks.volt;

/**
 * QuickActionLayout: Clean Home's four round quick actions as the Layout Plan's mock-up draws them (the contributor's
 * decisions on #383, 13 Nov 2026: one short name on one line under each circle, no value line), at the sizes the
 * maintainers check, 344x882 dp and 725x604 dp at 1x and 1.4x text, and 412 dp. The row's width is the window less
 * Home's 18 dp side padding (MainActivity's scroller), at most Clean's 560 dp column. Text widths come from a
 * per-letter estimate of Saira (narrow letters and spaces 0.3 em, m and w 0.85 em, capitals 0.65 em, the rest
 * 0.55 em) at the name's 13.5 sp times the text scale; the robustness checks run every case with text 15% wider.
 * The English names are the ones Clean shows (HubText.quickActionRoundName and the short rungs).
 */
public final class QuickActionLayoutTest {
    private static int checks;

    /** The short names Clean shows under its circles that fit at full size on a 344 dp phone at 1x. */
    private static final String[] SHORT = {"Ride", "Record", "Alerts", "Cruise", "Lights", "Traction", "Recovery", "Max. speed"};

    private static float em(char c) {
        if ("iljtfr .:,'I".indexOf(c) >= 0) return .3f;
        if (c == 'm' || c == 'w') return .85f;
        if (Character.isUpperCase(c)) return .65f;
        return .55f;
    }

    private static QuickActionLayout.Width width(float sp, float wider) {
        return text -> {
            float total = 0;
            for (int i = 0; i < text.length(); i++) total += em(text.charAt(i));
            return total * sp * wider;
        };
    }

    public static void main(String[] args) {
        for (int window : new int[]{344, 412, 725})
            for (float text : new float[]{1f, 1.4f})
                for (float wider : new float[]{1f, 1.15f}) size(window, text, wider);
        order();
        narrow();
        for (int window : new int[]{344, 412, 725})
            for (float text : new float[]{1f, 1.4f}) row(window, text);
        rowRule();
        System.out.println("QuickActionLayout: " + checks + " checks passed");
    }

    /** One of the maintainers' sizes: four equal circles in a row, every name on one line under its circle. */
    private static void size(int window, float text, float wider) {
        String at = window + " dp at " + text + "x" + (wider > 1 ? ", wider text" : "");
        float row = Math.min(window - 36, 560);
        require(QuickActionLayout.columns(row, 4) == 4, at + ": four in a row");
        float column = QuickActionLayout.column(row, 4);
        require(column >= 48, at + ": each column is a full touch target");
        require(QuickActionLayout.circleDp(column) == QuickActionLayout.CIRCLE_DP, at + ": the plan's 54 dp circle");
        float room = QuickActionLayout.room(column);
        QuickActionLayout.Width names = width(QuickActionLayout.NAME_SP * text, wider);
        for (String name : SHORT) {
            QuickActionLayout.Name fit = QuickActionLayout.name(name, room, names);
            require(fit.text.equals(name), at + ": " + name + " is shown as it is");
            if (wider == 1) require(!fit.clipped && (text > 1 || fit.scale == 1),
                    at + ": " + name + " whole on one line" + (text > 1 ? "" : ", at its size") + ": " + fit);
            if (wider == 1 && !fit.clipped) require(names.of(name) * fit.scale <= room, at + ": " + name + " fits its column: " + fit);
            if (fit.clipped) require(fit.scale == 1, at + ": " + name + " cut only at the row's size, never smaller: " + fit);
            System.out.println("  " + at + ": " + name + " -> " + fit);
        }
        // The four names the mock-up shows, and the defaults' short names, whole at full size on a 344 dp phone at 1x.
        if (wider == 1 && text == 1)
            for (String name : new String[]{"Ride", "Record", "Cruise", "Alerts"})
                require(QuickActionLayout.name(name, room, names).scale == 1, at + ": " + name + " at its full size");
        // Charge done by has no shorter English form yet (text for part 10): one line, a little smaller at 1x.
        QuickActionLayout.Name done = QuickActionLayout.name("Charge done by", room, names);
        if (window == 344 && text == 1 && wider == 1)
            require(!done.clipped && done.scale < 1 && done.scale >= QuickActionLayout.MIN_SCALE, at + ": Charge done by, smaller, on one line: " + done);
        // Where it cannot be whole even at the least size, it keeps the row's size (cut, until part 10's "Done by").
        require(done.clipped ? done.scale == 1 : done.scale >= QuickActionLayout.MIN_SCALE, at + ": Charge done by, whole or at the row's size: " + done);
    }

    /** Full size when it fits; smaller (never below the least share) while that keeps it whole; else an ellipsis at full size. */
    private static void order() {
        QuickActionLayout.Width w = text -> text.length() * 10f;
        QuickActionLayout.Name name = QuickActionLayout.name("Abcdef", 60, w);
        require(name.scale == 1 && !name.clipped, "the name at its size when it fits: " + name);
        name = QuickActionLayout.name("Abcdefg", 60, w);
        require(name.scale < 1 && name.scale >= QuickActionLayout.MIN_SCALE && !name.clipped && 70 * name.scale <= 60,
                "a little smaller when it is a little too wide: " + name);
        name = QuickActionLayout.name("Abcdefghijkl", 60, w);
        require(name.clipped && name.scale == 1, "failing that, an ellipsis at the row's size (a smaller cut name says no more): " + name);
        require(QuickActionLayout.name("", 60, w).text == null && QuickActionLayout.name(null, 60, w).text == null, "no name, nothing");
        // Each step is the largest that fits.
        for (int length = 6; length <= 8; length++) {
            String text = "Abcdefghij".substring(0, length);
            QuickActionLayout.Name fit = QuickActionLayout.name(text, 60, w);
            if (!fit.clipped && fit.scale < 1) require(length * 10 * (fit.scale + .05f) > 60, "the largest step that fits for " + text + ": " + fit);
        }
    }

    /**
     * One row of four at one of the maintainers' sizes (part 10's note, built in part 11): every name at the same size,
     * the largest that keeps every name that can be whole, whole; the defaults stay at full size where all fit.
     */
    private static void row(int window, float text) {
        String at = window + " dp at " + text + "x";
        float room = QuickActionLayout.room(QuickActionLayout.column(Math.min(window - 36, 560), 4));
        QuickActionLayout.Width names = width(QuickActionLayout.NAME_SP * text, 1f);
        for (String[] four : new String[][]{{"Ride", "Record", "Charge by", "Cruise"}, {"Ride", "Record", "Alerts", "Traction"},
                {"Ride", "Record", "Recovery", "Max. speed"}, {"Ride", "Record", "Charge done by", "Cruise"}}) {
            QuickActionLayout.Name[] fitted = QuickActionLayout.names(four, room, names);
            float least = 1;
            for (String name : four) { QuickActionLayout.Name alone = QuickActionLayout.name(name, room, names); if (!alone.clipped) least = Math.min(least, alone.scale); }
            for (int i = 0; i < 4; i++) {
                require(fitted[i].text.equals(four[i]), at + ": " + four[i] + " is shown as it is");
                require(fitted[i].scale == fitted[0].scale, at + ": " + String.join(", ", four) + " share one size: " + fitted[i] + " beside " + fitted[0]);
                require(fitted[i].scale == least, at + ": the size the smallest whole name needs: " + fitted[i]);
                require(fitted[i].clipped == (names.of(four[i]) * fitted[i].scale > room), at + ": cut only when it does not fit at that size: " + fitted[i]);
                require(fitted[i].scale >= QuickActionLayout.MIN_SCALE, at + ": never under the least size: " + fitted[i]);
            }
            System.out.println("  " + at + " row: " + java.util.Arrays.toString(fitted));
        }
    }

    /** The row rule on plain widths: one name a little too wide makes all four a little smaller; none too wide, none smaller. */
    private static void rowRule() {
        QuickActionLayout.Width w = text -> text.length() * 10f;
        QuickActionLayout.Name[] row = QuickActionLayout.names(new String[]{"Ab", "Abcd", "Abcdefg", "Abc"}, 60, w);
        for (QuickActionLayout.Name name : row) require(name.scale == row[2].scale && name.scale < 1 && !name.clipped, "one too wide: all four smaller: " + name);
        row = QuickActionLayout.names(new String[]{"Ab", "Abcd", "Abcdef", "Abc"}, 60, w);
        for (QuickActionLayout.Name name : row) require(name.scale == 1 && !name.clipped, "all fit: all at full size: " + name);
        row = QuickActionLayout.names(new String[]{"Ab", "Abcdefghijkl", "Abc", null}, 60, w);
        require(row[0].scale == 1 && row[1].scale == 1 && row[1].clipped && row[3].text == null, "a name cut even at the least size does not shrink the rest");
    }

    /** A narrow column (a split screen, a small phone): the circle gets smaller, then two rows of two. */
    private static void narrow() {
        require(QuickActionLayout.columns(280, 4) == 4 && QuickActionLayout.circleDp(QuickActionLayout.column(280, 4)) == 54, "280 dp: four, at 54 dp");
        require(QuickActionLayout.columns(240, 4) == 2, "240 dp: two rows of two");
        require(QuickActionLayout.circleDp(40) == QuickActionLayout.MIN_CIRCLE_DP && QuickActionLayout.circleDp(50) == 50, "the circle never gets smaller than 44 dp");
        require(QuickActionLayout.columns(400, 3) == 3 && QuickActionLayout.columns(400, 1) == 1 && QuickActionLayout.columns(400, 0) == 1, "fewer actions, fewer columns");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
