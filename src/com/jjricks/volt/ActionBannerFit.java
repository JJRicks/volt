package com.jjricks.volt;

/**
 * A subject page's banner with a pill action ("Last seen (disconnected)" with Connect, and the read-only and unfinished
 * test banners built the same way): the pill always shows its whole label at its own padding, and sits beside the
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
        return room - Math.max(0f, pill) - Math.max(0f, gap) < longestWord;
    }
}
