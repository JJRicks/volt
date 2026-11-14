package com.jjricks.volt;

/**
 * Settings' index as the Layout Plan draws it (the settings-page mock-up, checklist §7; the contributor's decisions on
 * #383): its rows' size, where Language sits in the first card, and when a row's value fits in the value column.
 * Pure Java.
 */
final class SettingsRows {
    /** A single-line row's height: the mock-up's rows are about 38-40 dp; a row is a 48 dp touch target at least. */
    static final int MIN_HEIGHT_DP = 48;
    /** The space above and below a row's words (dp). */
    static final int PAD_DP = 8;
    /** The value column's widest (dp), as MoreScreen's rows cap it. */
    static final int VALUE_MAX_DP = 140;

    private SettingsRows() {}

    /**
     * Where the Language row goes in the first card: right above the Haptic feedback row (index {@code before}), so the
     * card reads Home style, Appearance, Units, Text size, Language, Haptic feedback; at the end when that row is
     * missing ({@code before} &lt; 0 or past the end).
     */
    static int languageIndex(int before, int childCount) {
        int count = Math.max(0, childCount);
        return before < 0 || before > count ? count : before;
    }

    /**
     * Whether a value {@code width} wide (pixels, on one line) fits in the value column {@code column} wide: then it shows
     * there, as the mock-up's "English ›"; otherwise it goes under the row's title, so it is never cut.
     */
    static boolean valueBeside(float width, float column) {
        return width >= 0 && column > 0 && width <= column;
    }
}
