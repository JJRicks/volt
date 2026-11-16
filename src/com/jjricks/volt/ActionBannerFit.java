package com.jjricks.volt;

/**
 * A row with a pill action: a subject page's banner ("Last seen (disconnected)" with Connect, and the read-only and
 * unfinished test banners built the same way), the clock row's Sync and the Allow rows of the Live charging and Battery
 * alerts sheets: the pill always shows its whole label at its own padding, and sits beside the
 * banner's words while their longest word still fits on a line beside it; otherwise it goes under the words, so neither
 * the pill's label nor a word is ever cut. Pure Java.
 */
final class ActionBannerFit {
    /** The pill's own padding a side (dp): the label never comes closer to the pill's ends. */
    static final int PILL_PAD_DP = 16;
    /** The pill's narrowest (dp), as a short label still reads as a button. */
    static final int PILL_MIN_DP = 64;
    /** The gap between the words and the pill beside them (dp): the words' end margin. */
    static final int GAP_DP = 8;

    /** A text's width on one line, in the same unit as the room (pixels on a device). */
    interface Width { float of(String text); }

    private ActionBannerFit() {}

    /** The pill's width for a label {@code label} wide: the label with its padding, never under the pill's narrowest. */
    static float pill(float label, float pad, float minimum) {
        return Math.max(Math.max(0f, minimum), Math.max(0f, label) + 2 * Math.max(0f, pad));
    }

    /** The widest single word of a text (words split at white space); 0 for an empty or null text. */
    static float longestWord(String text, Width width) {
        float widest = 0;
        if (text == null) return 0;
        for (String word : text.trim().split("\\s+")) if (!word.isEmpty()) widest = Math.max(widest, width.of(word));
        return widest;
    }

    /**
     * Whether the pill goes under the words: the room beside it (the banner's room for its words and pill, less the pill
     * and the gap) is narrower than the words' longest word. Words that fit beside it wrap there as they need.
     */
    static boolean actionBelow(float room, float pill, float gap, float longestWord) {
        return actionBelow(room, pill, gap, longestWord, 0);
    }

    /**
     * As {@link #actionBelow(float, float, float, float)} for words that keep {@code reserve} of their room for something
     * that comes and goes beside them (the clock row's spinner while a sync is pending): the room is always counted
     * without it, so the decision never changes when it shows or hides and the row stays still.
     */
    static boolean actionBelow(float room, float pill, float gap, float longestWord, float reserve) {
        return room - Math.max(0f, reserve) - Math.max(0f, pill) - Math.max(0f, gap) < longestWord;
    }

    /**
     * Under the words, whether what comes and goes beside them ({@code reserve}: the clock row's spinner) sits after the
     * pill on the pill's line: wherever the pill and its room fit the row. Only in a window so narrow that they do not
     * (below about 255 dp at 1.4x in German) does it stay at the end of the words' line.
     */
    static boolean asideByPill(float room, float pill, float reserve) {
        return Math.max(0f, pill) + Math.max(0f, reserve) <= room;
    }

    /**
     * The words' room on their line: beside the pill, the room less the pill, the gap and {@code reserve}; under it, the
     * row's whole room when the reserve sits by the pill, else the room less the reserve. The reserve is kept whether
     * what it is for shows or not, so the words wrap the same way when it comes and goes (a sync starting or ending).
     */
    static float wordsRoom(float room, float pill, float gap, float reserve, boolean below) {
        float kept = Math.max(0f, reserve);
        if (!below) return room - Math.max(0f, pill) - Math.max(0f, gap) - kept;
        return asideByPill(room, pill, kept) ? room : room - kept;
    }

    /**
     * The pill's indent under the words: lined up with the words' text ({@code indent}, after a row's icon tile) where
     * the pill still fits beside that indent, else at the row's start, so its label stays whole on a narrow window.
     */
    static float stackedIndent(float room, float pill, float indent) {
        float wanted = Math.max(0f, indent);
        return Math.max(0f, pill) <= room - wanted ? wanted : 0f;
    }

    /**
     * The pill's padding a side under the words: its own {@code pad} while the pill fits the row's {@code room} with it;
     * in a window so narrow that the pill alone is wider than its row (Sync in German at 1.4x in Android's narrowest
     * 220 dp split-screen window), only what the row leaves around the label, so the label stays whole on one line.
     */
    static float stackedPad(float room, float label, float pad) {
        return Math.min(Math.max(0f, pad), Math.max(0f, (room - Math.max(0f, label)) / 2));
    }
}
