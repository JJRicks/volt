package com.jjricks.volt;

/**
 * QuickActionLayout: Clean Home's four round quick actions (owner's decision on #383, 2026-11-13), at the sizes the
 * maintainers check, 344x882 dp and 725x604 dp at 1x and 1.4x text. The row's width is the window less Home's 18 dp
 * side padding (MainActivity's scroller), at most Clean's 560 dp column. Text widths come from a per-letter estimate
 * of Roboto (narrow letters and spaces 0.3 em, m and w 0.85 em, capitals 0.65 em, the rest 0.55 em) at the name's
 * 12 sp or the value's 11 sp times the text scale; the robustness checks run every case with text 15% wider. The
 * English names are the catalog's (Quick access, Clean's own and the controls' short rungs).
 */
public final class QuickActionLayoutTest {
    private static int checks;

    /** Full name and short form, as the round button shows them: Ride, Record, Battery alerts, Charge done by, Cruise control, Lights. */
    private static final String[][] NAMES = {
            {"RIDE", "RIDE"}, {"Record ride", "Record"}, {"Battery alerts", "Alerts"}, {"Charge done by", "Charge done by"},
            {"Cruise control", "Cruise control"}, {"Lights", "Lights"}, {"Maximum speed", "Max. speed"}};
    private static final String[] VALUES = {"90%", "Off", "On", "Recording", "On at 06:30"};

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
        for (int window : new int[]{344, 725})
            for (float text : new float[]{1f, 1.4f})
                for (float wider : new float[]{1f, 1.15f}) size(window, text, wider);
        order();
        values();
        narrow();
        System.out.println("QuickActionLayout: " + checks + " checks passed");
    }

    /** One of the maintainers' sizes: four equal circles in a row, every name and value whole under its circle. */
    private static void size(int window, float text, float wider) {
        String at = window + " dp at " + text + "x" + (wider > 1 ? ", wider text" : "");
        float row = Math.min(window - 36, 560);
        require(QuickActionLayout.columns(row, 4) == 4, at + ": four in a row");
        float column = QuickActionLayout.column(row, 4);
        require(column >= 48, at + ": each column is a full touch target");
        require(QuickActionLayout.circleDp(column) == QuickActionLayout.CIRCLE_DP, at + ": the plan's 54 dp circle");
        float room = QuickActionLayout.room(column);
        // The catalog's budget for a shortcut name is 64 dp at 12 sp on two lines (volt:max="64dp@12sp*2").
        require(room >= 64, at + ": a column holds the names' 64 dp budget");
        QuickActionLayout.Width names = width(QuickActionLayout.NAME_SP * text, wider), values = width(QuickActionLayout.VALUE_SP * text, wider);
        for (String[] name : NAMES) {
            QuickActionLayout.Words words = QuickActionLayout.name(name[0], name[1], room, names);
            require(words.lines >= 1 && words.lines <= 2 && !words.clipped, at + ": " + name[0] + " shows whole: " + words);
            if (wider == 1) require(words.scale == 1 || words.lines == 1 && words.scale >= QuickActionLayout.MIN_SCALE,
                    at + ": " + name[0] + " at its full size, or on one line a little smaller: " + words);
            if (words.lines == 2) require(words.text.indexOf('\n') > 0, at + ": " + name[0] + " breaks where the lines are even: " + words);
            System.out.println("  " + at + ": " + name[0] + " -> " + words);
        }
        for (String value : VALUES) {
            QuickActionLayout.Words words = QuickActionLayout.value(value, false, room, values);
            require(words.lines >= 1 && !words.clipped, at + ": the value " + value + " shows whole: " + words);
            if (wider == 1) require(words.lines <= 2 && words.scale == 1, at + ": the value " + value + " at its size: " + words);
            if (wider == 1 && value.indexOf(' ') < 0) require(words.lines == 1, at + ": the one-word value " + value + " on one line: " + words);
        }
        if (window == 725) for (String[] name : NAMES) {
            QuickActionLayout.Words words = QuickActionLayout.name(name[0], name[1], room, names);
            if (wider == 1) require(words.text.equals(name[0]) && words.lines == 1, at + ": the full name on one line on a wide window: " + words);
        }
        if (window == 344 && wider == 1) {
            // Record ride: whole at 1x, and its short form, on one line, at 1.4x.
            QuickActionLayout.Words record = QuickActionLayout.name("Record ride", "Record", room, names);
            require(text == 1 ? record.text.equals("Record ride") && record.lines == 1 : record.brief && record.lines == 1,
                    at + ": Record ride, else Record, on one line: " + record);
            QuickActionLayout.Words alerts = QuickActionLayout.name("Battery alerts", "Alerts", room, names);
            require(alerts.brief && alerts.lines == 1, at + ": Battery alerts takes its short form before it wraps: " + alerts);
            // Charge done by has no shorter English form (a shorter one is text for part 10): one line a little smaller at
            // 1x, so the value is the column's second line; at 1.4x two even lines, never "Charge done" / "by".
            QuickActionLayout.Words done = QuickActionLayout.name("Charge done by", "Charge done by", room, names);
            if (text == 1) require(!done.brief && done.lines == 1 && done.scale < 1 && done.scale >= QuickActionLayout.MIN_SCALE,
                    at + ": a name with no shorter form gets a little smaller before it wraps: " + done);
            else require(done.lines == 2 && done.text.equals("Charge\ndone by"), at + ": two even lines of whole words: " + done);
            QuickActionLayout.Words cruise = QuickActionLayout.name("Cruise control", "Cruise control", room, names);
            if (text == 1) require(cruise.lines == 1, at + ": Cruise control on one line: " + cruise);
        }
    }

    /** Short forms before anything wraps or gets smaller; smaller before anything is cut. */
    private static void order() {
        QuickActionLayout.Width w = text -> text.length() * 10f;
        QuickActionLayout.Words words = QuickActionLayout.name("Abcdef", "Abc", 60, w);
        require(words.text.equals("Abcdef") && words.lines == 1 && !words.brief, "the full name when it fits one line");
        words = QuickActionLayout.name("Abcdefgh", "Abc", 60, w);
        require(words.text.equals("Abc") && words.lines == 1 && words.brief, "the short form before the full name wraps");
        words = QuickActionLayout.name("Abcd efgh", "Abc defg", 60, w);
        require(words.text.equals("Abc\ndefg") && words.lines == 2 && words.brief, "the short form wraps before the full name does: " + words);
        words = QuickActionLayout.name("Abcd efgh", "Abcdefghijkl", 60, w);
        require(words.text.equals("Abcd\nefgh") && words.lines == 2 && !words.brief, "the full name wraps when the short form can't: " + words);
        words = QuickActionLayout.name("Abcdefg", "Abcdef", 55, w);
        require(words.text.equals("Abcdef") && words.lines == 1 && words.brief && words.scale < 1,
                "a little smaller on one line before anything wraps: " + words);
        require(QuickActionLayout.even("Ab cdefgh ij", 95, w).equals("Ab cdefgh\nij") || QuickActionLayout.even("Ab cdefgh ij", 95, w).equals("Ab\ncdefgh ij"),
                "two lines of whole words: " + QuickActionLayout.even("Ab cdefgh ij", 95, w));
        require(QuickActionLayout.even("Abcdef gh ij", 90, w).equals("Abcdef\ngh ij"), "the more even break, not the greedy one: "
                + QuickActionLayout.even("Abcdef gh ij", 90, w));
        require(QuickActionLayout.even("Abc", 20, w).equals("Abc") && QuickActionLayout.even("Ab cd", 60, w).equals("Ab cd"), "one word, or one line: as it is");
        words = QuickActionLayout.name("Abcdefghijkl", "Abcdefg", 60, w);
        require(words.text.equals("Abcdefg") && words.lines == 1 && words.scale < 1 && words.scale >= QuickActionLayout.MIN_SCALE && !words.clipped,
                "only then the short form gets smaller: " + words);
        words = QuickActionLayout.name("Abcdefghijklmnop", "Abcdefghijkl", 60, w);
        require(words.text.equals("Abcdefghijkl") && words.clipped && words.lines == 2 && words.scale == 1, "failing that it is cut, at its size: " + words);
        require(QuickActionLayout.name("", "x", 60, w).lines == 0 && QuickActionLayout.name(null, null, 60, w).lines == 0, "no name, no line");
        require(QuickActionLayout.fits("ab cd ef", 50, 2, w) && !QuickActionLayout.fits("ab cd ef", 40, 2, w) && QuickActionLayout.fits("ab cd ef", 80, 1, w),
                "whole words, greedily");
    }

    /** The value line: one line, two whole-word lines, then a lock word drops and anything else gets smaller. */
    private static void values() {
        QuickActionLayout.Width w = text -> text.length() * 10f;
        require(QuickActionLayout.value("", false, 60, w).lines == 0 && QuickActionLayout.value(null, false, 60, w).lines == 0, "no value, no line");
        require(QuickActionLayout.value("90%", false, 60, w).lines == 1, "90% on one line");
        require(QuickActionLayout.value("On at 6:30", false, 60, w).lines == 2, "a long value on two lines of whole words");
        require(QuickActionLayout.value("Unlocked", true, 60, w).lines == 0, "a lock word too wide drops (the padlock and the spoken state remain)");
        QuickActionLayout.Words recording = QuickActionLayout.value("Recording", false, 80, w);
        require(recording.lines == 1 && recording.scale < 1 && !recording.clipped, "a single word slightly too wide gets smaller: " + recording);
        require(QuickActionLayout.value("Abcdefghijklmnop", false, 60, w).clipped, "a value far too wide is cut");
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
