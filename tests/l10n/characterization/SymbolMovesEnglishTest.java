package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for S1 (text carrying a mark moves to the catalog): the English at the merge base
 * (ddd69ce), pinned before the batch.
 *
 * These expressions live in Android screens, so each method below is the base expression copied token for
 * token, with the state it reads passed in as parameters; the comment names the base line. S1's own suite
 * (SymbolMovesTest) renders the catalog calls that replace each one and compares them with these copies, so
 * this file, frozen by G5, is the reference. The pins in main() spell the English out by hand, code point by
 * code point where a mark is involved, under several default locales: the base text never depends on them.
 */
public final class SymbolMovesEnglishTest {
    private static int checks;

    private SymbolMovesEnglishTest() {}

    /** MoreScreen.java:1038, the Background recording row's value. */
    static String battery(boolean unrestricted) { return unrestricted ? "Unrestricted ✓" : "Optimized ⚠︎"; }

    /** MoreScreen.java:2567, the PIN chip. */
    static String pinChip(boolean pinOn) { return pinOn ? "PIN ✓" : "PIN —"; }

    /** MoreScreen.java:2569, the pairing chip. */
    static String pairedChip(boolean paired) { return paired ? "Paired ✓" : "Paired —"; }

    /** StatsScreen.java:194, the empty favourites card. */
    static String favouritesHint() { return "☆ Star readings to pin here"; }

    /** DashboardScreen.java:5295, :5382 and :6035, the three Save buttons. */
    static String save() { return "✓ Save"; }

    /** RidesScreen.java:619, the compare row's value. */
    static String compareValue(boolean possible, int savedCount, int selected) {
        return !possible ? savedCount + "/2" : selected == 2 ? "2 ✓" : "";
    }

    /** RidesScreen.java:975, the Direction chip. */
    static String direction(boolean directionConfirmed) { return directionConfirmed ? "Direction ✓" : "Direction ?"; }

    /** DriveModeStep.java:153, a drive-mode choice; text is CHOICES[i].text. */
    static String driveChoice(boolean used, String text) { return used ? text + " ✓" : text; }

    /** CompatibilityPanel.java:307-309, a supported read's value. */
    static String supportedRead(int length, boolean isLate) {
        String late = isLate ? " (late)" : "";
        return "✓ " + length + " bytes" + late;
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                pins();
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("SymbolMovesEnglishTest: " + checks + " checks passed (battery row, chips, hint, Save buttons, "
                + "compare row, Direction chip, drive-mode choices and supported reads under 4 phone locales)");
    }

    private static void pins() {
        equal("Unrestricted ✓", battery(true));
        equal("Optimized ⚠︎", battery(false));
        equal("PIN ✓", pinChip(true));
        equal("PIN —", pinChip(false));
        equal("Paired ✓", pairedChip(true));
        equal("Paired —", pairedChip(false));
        equal("☆ Star readings to pin here", favouritesHint());
        equal("✓ Save", save());
        equal("0/2", compareValue(false, 0, 0));
        equal("1/2", compareValue(false, 1, 1));
        equal("2 ✓", compareValue(true, 5, 2));
        equal("", compareValue(true, 5, 1));
        equal("Direction ✓", direction(true));
        equal("Direction ?", direction(false));
        equal("Sport ✓", driveChoice(true, "Sport"));
        equal("Sport", driveChoice(false, "Sport"));
        equal("Walk ✓", driveChoice(true, DriveModeSurvey.Label.WALK.text));
        equal("Eco ✓", driveChoice(true, DriveModeSurvey.Label.ECO.text));
        equal("Drive ✓", driveChoice(true, DriveModeSurvey.Label.DRIVE.text));
        equal("Sport ✓", driveChoice(true, DriveModeSurvey.Label.SPORT.text));
        equal("✓ 0 bytes", supportedRead(0, false));
        equal("✓ 1 bytes", supportedRead(1, false));
        equal("✓ 2 bytes", supportedRead(2, false));
        equal("✓ 21 bytes (late)", supportedRead(21, true));
        equal("✓ 1 bytes (late)", supportedRead(1, true));
    }

    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected [" + expected + "], got [" + actual + "]");
    }
}
