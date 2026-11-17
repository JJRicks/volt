package com.jjricks.volt;

/**
 * How one line of the wide Home's Service card (HubServiceCard, hub layout plan 1.11, PR 13) places a check's status:
 * beside the name, at the line's end, as the Layout Plan draws it ("Tyre pressure · 40 mi left"), when the whole name
 * still fits in what the status leaves, in its two lines with no word broken; otherwise under the name, where the name
 * has the whole line (#394 early review, 3: a long German status beside "Schrauben & Klappmechanismus"; second review,
 * 2: "Bande de roulement des pneus" beside "En attente du kilométrage" in French, whose longest word fits but whose
 * whole name needs three lines there, so it ended in an ellipsis). Pure Java, so the JVM suite checks it
 * (tests/HubServiceFitTest); widths are in one unit (pixels on a device, dp in the tests).
 */
final class HubServiceFit {
    /** The name's and the status's text sizes (sp, before the text scale), as the mock-up's lines. */
    static final float NAME_SP = 15, STATUS_SP = 13.5f;
    /** The dot, the space after it, and the space between the name and a status beside it (dp). */
    static final int DOT_DP = 8, DOT_GAP_DP = 12, GAP_DP = 10;
    /** The name's most lines (the card's TextView keeps to them). */
    static final int NAME_LINES = 2;

    private HubServiceFit() { }

    /** A text's width at its shown size. */
    interface Width { float of(String text); }

    /**
     * Whether the status goes beside the name in a body {@code room} wide (the line less its dot): the status at its
     * natural width on one line, the gap, and the name's longest word all fit.
     */
    static boolean beside(float room, float gap, float statusWidth, float longestNameWord) {
        return room > 0 && room - gap - statusWidth >= longestNameWord;
    }

    /**
     * Whether the status goes beside the name: as {@link #beside(float, float, float, float)}, and the whole name wraps
     * into at most NAME_LINES lines in what the status leaves ({@link #lines}; the card wraps its name the same way, at
     * spaces, with no hyphenation).
     */
    static boolean beside(float room, float gap, float statusWidth, String name, Width width) {
        float left = room - gap - statusWidth;
        return beside(room, gap, statusWidth, longestWord(name, width)) && lines(name, left, width) <= NAME_LINES;
    }

    /**
     * How many lines text takes wrapped greedily at spaces in a line {@code room} wide, each line measured whole (words
     * joined by one space) with width; a word wider than the room takes a line of its own. 0 for no words.
     */
    static int lines(String text, float room, Width width) {
        if (text == null) return 0;
        int lines = 0;
        String line = null;
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            String longer = line == null ? null : line + " " + word;
            if (longer != null && width.of(longer) <= room) { line = longer; continue; }
            line = word;
            lines++;
        }
        return lines;
    }

    /** The widest single word of text (split at spaces), measured with width; 0 for none. */
    static float longestWord(String text, Width width) {
        float widest = 0;
        if (text == null) return 0;
        for (String word : text.trim().split("\\s+")) if (!word.isEmpty()) widest = Math.max(widest, width.of(word));
        return widest;
    }
}
