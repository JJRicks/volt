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
        // The value column's 140 dp, which stands in until Language's row has a width: "English" fits beside the title;
        // "System default (English)" goes under it, at the checked windows (344x882 and 725x604 dp) and text sizes (1x and
        // 1.4x, 13.5 sp values, an average character about 0.55 em wide).
        for (int window : new int[]{344, 725})
            for (float scale : new float[]{1f, 1.4f}) {
                float column = SettingsRows.VALUE_MAX_DP, em = 13.5f * scale * 0.55f;
                require(SettingsRows.valueBeside("English".length() * em, column), window + " dp at " + scale + "x: English beside the title");
                require(!SettingsRows.valueBeside("System default (English)".length() * em, column), window + " dp at " + scale + "x: a long language under the title");
            }
        require(SettingsRows.valueBeside(140, 140) && !SettingsRows.valueBeside(140.5f, 140), "exactly the column fits");
        // The room a measured row really has (the review of part 11: the 140 dp cap put Language under its title at
        // every width although the row had room): Settings' card is the window less 18 dp of page padding and 14 dp of
        // card padding a side; "Language" (15 sp) is about 66 dp wide and "System default (English)" (13.5 sp) about
        // 149 dp at 1x, both growing with the text size; the gap is 10 dp and the chevron 23 dp.
        require(SettingsRows.VALUE_GAP_DP == 10 && SettingsRows.CHEVRON_DP == 23, "the gap and the chevron as the rows build them");
        require(SettingsRows.valueRoom(280, 66, 10, 23) == 181, "344 dp at 1x: 181 dp of room beside the title");
        require(SettingsRows.valueBeside(149, SettingsRows.valueRoom(280, 66, 10, 23)), "149 dp value in 181 dp of room: beside, as the mock-up's \"English ›\"");
        require(!SettingsRows.valueBeside(182, SettingsRows.valueRoom(280, 66, 10, 23)), "a value wider than the room: under the title");
        require(SettingsRows.valueRoom(50, 66, 10, 23) == 0 && SettingsRows.valueRoom(280, -5, -1, -1) == 280, "room is never negative, nor grown by nonsense");
        for (int[] window : new int[][]{{344, 882}, {725, 604}})
            for (float scale : new float[]{1f, 1.4f}) {
                float inner = window[0] - 2 * 18 - 2 * 14, room = SettingsRows.valueRoom(inner, 66 * scale, SettingsRows.VALUE_GAP_DP, SettingsRows.CHEVRON_DP);
                boolean beside = SettingsRows.valueBeside(149 * scale, room);
                // Only the cover screen at 1.4x is too narrow: 280 - 92 - 33 = 155 dp of room for a 209 dp value.
                boolean expected = !(window[0] == 344 && scale > 1.3f);
                require(beside == expected, window[0] + "x" + window[1] + " dp at " + scale + "x: System default (English) "
                        + (expected ? "beside" : "under") + " the title (room " + room + ")");
                require(SettingsRows.valueBeside("English".length() * 13.5f * scale * 0.55f, room), window[0] + " dp at " + scale + "x: English beside");
            }
        require(!SettingsRows.valueBeside(-1, 140) && !SettingsRows.valueBeside(10, 0), "nonsense never goes beside");
        System.out.println("SettingsRows: " + checks + " checks passed");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
