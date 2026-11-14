package com.jjricks.volt;

/** SettingsRows: Settings' index as the Layout Plan draws it (part 11; settings-page mock-up, checklist §7). */
public final class SettingsRowsTest {
    private static int checks;

    public static void main(String[] args) {
        // The first card is built as Home style, Appearance, Units, Text size, then Haptic feedback, with a divider before
        // each row after the first (9 children); Language goes right above Haptic feedback (child 8).
        require(SettingsRows.languageIndex(8, 9) == 8, "Language right above Haptic feedback, after Text size");
        require(SettingsRows.languageIndex(-1, 9) == 9 && SettingsRows.languageIndex(12, 9) == 9, "no Haptic row: Language goes last");
        require(SettingsRows.languageIndex(0, 0) == 0 && SettingsRows.languageIndex(-1, -3) == 0, "an empty card: the start");
        // A row is a 48 dp touch target with the words' padding, at every window and text size (the mock-up's ~40 dp rows).
        require(SettingsRows.MIN_HEIGHT_DP == 48 && SettingsRows.PAD_DP * 2 + 20 <= SettingsRows.MIN_HEIGHT_DP, "48 dp rows hold one 15 sp line at 1x");
        // The value column: "English" fits beside the title; "System default (English)" goes under it, at the checked
        // windows (344x882 and 725x604 dp; the column is 140 dp at both) and text sizes (1x and 1.4x, 13.5 sp values, an
        // average character about 0.55 em wide).
        for (int window : new int[]{344, 725})
            for (float scale : new float[]{1f, 1.4f}) {
                float column = SettingsRows.VALUE_MAX_DP, em = 13.5f * scale * 0.55f;
                require(SettingsRows.valueBeside("English".length() * em, column), window + " dp at " + scale + "x: English beside the title");
                require(!SettingsRows.valueBeside("System default (English)".length() * em, column), window + " dp at " + scale + "x: a long language under the title");
            }
        require(SettingsRows.valueBeside(140, 140) && !SettingsRows.valueBeside(140.5f, 140), "exactly the column fits");
        require(!SettingsRows.valueBeside(-1, 140) && !SettingsRows.valueBeside(10, 0), "nonsense never goes beside");
        System.out.println("SettingsRows: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
