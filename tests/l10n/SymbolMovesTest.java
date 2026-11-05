package com.jjricks.volt;

import java.io.File;

/**
 * S1's own suite: each catalog call that replaced a literal with a mark prints what the base expression
 * printed (SymbolMovesEnglishTest, frozen by G5), the marks survive as code points, and a supported read's
 * text stays out of the report (G9): only CompatibilityPanel's result rows call readValue.
 */
public final class SymbolMovesTest {
    private static int checks;

    private SymbolMovesTest() {}

    public static void main(String[] args) throws Exception {
        for (boolean on : new boolean[]{true, false}) {
            equal(SymbolMovesEnglishTest.battery(on), on ? T.morePrefsBatteryValueUnrestricted() : T.morePrefsBatteryValueOptimized());
            equal(SymbolMovesEnglishTest.pinChip(on), on ? T.moreChipPinOnValue() : T.moreChipPinOffValue());
            equal(SymbolMovesEnglishTest.pairedChip(on), on ? T.moreChipPairedValue() : T.moreChipNotPairedValue());
            equal(SymbolMovesEnglishTest.direction(on), on ? T.ridesDirectionChipConfirmed() : T.ridesDirectionChipUnconfirmed());
        }
        equal("Optimized ⚠︎", T.morePrefsBatteryValueOptimized());
        equal(SymbolMovesEnglishTest.favouritesHint(), T.statsFavouritesEmptyHint());
        equal(SymbolMovesEnglishTest.save(), T.dashboardSaveChecked());
        equal(SymbolMovesEnglishTest.compareValue(true, 3, 2), T.ridesCompareTwoSelectedValue());
        for (DriveModeSurvey.Label label : DriveModeSurvey.Label.values())
            equal(SymbolMovesEnglishTest.driveChoice(true, label.text), T.controlsDriveModeChoiceUsed(label.text));
        for (int length : new int[]{0, 1, 2, 3, 4, 5, 11, 12, 20, 21, 22, 25, 100, 101, 111, 255, 1000000}) {
            equal(SymbolMovesEnglishTest.supportedRead(length, false), T.compatReadSupported(length, length, ""));
            equal(SymbolMovesEnglishTest.supportedRead(length, true), T.compatReadSupported(length, length, T.compatReadLate()));
        }
        File[] sources = new File(L10nFiles.root(), "src/com/jjricks/volt").listFiles((d, n) -> n.endsWith(".java"));
        check(sources != null && sources.length > 0, "the sources are found");
        for (File source : sources) {
            if (source.getName().equals("CompatibilityPanel.java")) continue;
            check(!L10nFiles.read(source).contains("readValue("), source.getName() + " does not read a read's shown value");
        }
        System.out.println("SymbolMovesTest: " + checks + " checks passed (S1's keys print the base English; readValue stays on its screen)");
    }

    private static void equal(String expected, String actual) {
        check(expected.equals(actual), "expected [" + expected + "], got [" + actual + "]");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
