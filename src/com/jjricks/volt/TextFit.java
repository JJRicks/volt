package com.jjricks.volt;

/**
 * Whether a label is too wide for a layout's compact form. English keeps the character count these
 * layouts always used, so its layout cannot move with font rendering; every other language measures,
 * because a character count means nothing across scripts.
 *
 * The em thresholds separate the same English labels the counts did on Saira regular, measured over
 * every English label that reaches them (tests/l10n/MainTextTest.java proves the separation).
 * Pure Java (no Android), so the JVM tests reach it.
 */
final class TextFit {
    private TextFit() {}

    /** Over the limit: more than chars characters in English, else wider than ems times the text size. */
    static boolean over(int length, int chars, double ems, float width, float textSize) {
        return Fmt.legacy() ? length > chars : width > ems * textSize;
    }
}
