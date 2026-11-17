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
     * As {@link #actionBelow(float, float, float, float, float)} for words limited to {@code maxLines} lines (the clock
     * row's name, two): the pill also goes under them when beside it they would need {@code linesBeside} lines, more than
     * they may have, so a word is never cut off at the end of their last line.
     */
    static boolean actionBelow(float room, float pill, float gap, float longestWord, float reserve, int linesBeside, int maxLines) {
        return actionBelow(room, pill, gap, longestWord, reserve) || maxLines > 0 && linesBeside > maxLines;
    }

    /**
     * Under the words, whether what comes and goes beside them ({@code reserve}: the clock row's spinner) sits after the
     * pill on the pill's line: wherever the pill and its room fit the row. Only in a window so narrow that they do not
     * (below about 255 dp at 1.4x in German) does it stay at the end of the words' line, unless keeping it there would
     * break the words' longest word ({@link #asideByPill(float, float, float, float, float)}).
     */
    static boolean asideByPill(float room, float pill, float reserve) {
        return Math.max(0f, pill) + Math.max(0f, reserve) <= room;
    }

    /**
     * As {@link #asideByPill(float, float, float)} for words whose longest word is {@code longestWord} wide and a pill whose
     * label is {@code label} wide: where the pill and the room do not fit at the pill's own padding, but keeping the room
     * at the end of the words' line would break their longest word (French "trottinette" and Polish "hulajnogi" at
     * 1.3-1.4x in a 220 dp window; council review of #524), it still sits after the pill wherever the bare label and the
     * room fit the row, and the pill's padding narrows to what that line leaves ({@link #stackedLine}, {@link #stackedPad}).
     * The words then have the row's whole width. It depends on neither a sync nor the spinner, so it is the same idle and
     * syncing.
     */
    static boolean asideByPill(float room, float pill, float reserve, float label, float longestWord) {
        float kept = Math.max(0f, reserve);
        return asideByPill(room, pill, kept) || longestWord > room - kept && Math.max(0f, label) + kept <= room;
    }

    /**
     * The pill's room on its line under the words: the row's room less the pill's indent, and less {@code reserve} where
     * that sits after the pill ({@code byPill}). The pill's padding a side is {@link #stackedPad} of this room.
     */
    static float stackedLine(float room, float indent, float reserve, boolean byPill) {
        return room - Math.max(0f, indent) - (byPill ? Math.max(0f, reserve) : 0f);
    }

    /**
     * The words' room on their line: beside the pill, the room less the pill, the gap and {@code reserve}; under it, the
     * row's whole room when the reserve sits by the pill ({@code byPill}, from {@link #asideByPill}), else the room less
     * the reserve. The reserve is kept whether what it is for shows or not, so the words wrap the same way when it comes
     * and goes (a sync starting or ending).
     */
    static float wordsRoom(float room, float pill, float gap, float reserve, boolean below, boolean byPill) {
        float kept = Math.max(0f, reserve);
        if (!below) return room - Math.max(0f, pill) - Math.max(0f, gap) - kept;
        return byPill ? room : room - kept;
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
