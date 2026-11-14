package com.jjricks.volt;

/**
 * How one line of the wide Home's Service card (HubServiceCard, hub layout plan 1.11, PR 13) places a check's status:
 * beside the name, at the line's end, as the Layout Plan draws it ("Tyre pressure · 40 mi left"), when the name's
 * longest word still fits in what the status leaves; otherwise under the name, so a word is never broken (#394 early
 * review, 3: a long German status beside "Schrauben & Klappmechanismus"). Pure Java, so the JVM suite checks it
 * (tests/HubServiceFitTest); widths are in one unit (pixels on a device, dp in the tests).
 */
final class HubServiceFit {
    /** The name's and the status's text sizes (sp, before the text scale), as the mock-up's lines. */
    static final float NAME_SP = 15, STATUS_SP = 13.5f;
    /** The dot, the space after it, and the space between the name and a status beside it (dp). */
    static final int DOT_DP = 8, DOT_GAP_DP = 12, GAP_DP = 10;

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

    /** The widest single word of text (split at spaces), measured with width; 0 for none. */
    static float longestWord(String text, Width width) {
        float widest = 0;
        if (text == null) return 0;
        for (String word : text.trim().split("\\s+")) if (!word.isEmpty()) widest = Math.max(widest, width.of(word));
        return widest;
    }
}
