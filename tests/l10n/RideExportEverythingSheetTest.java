package com.jjricks.volt;

import java.util.*;

/**
 * The Export ride data sheet with Everything (JSON) on it: listed first, checked by default for a rider with no
 * remembered choice, added once to a choice remembered before it existed, and labelled from the catalog. The file
 * itself is RideExportEverythingTest's. Listed in tests/l10n/ride-export-everything-sheet.sources.
 */
public final class RideExportEverythingSheetTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        RideExportFiles.Format e = RideExportFiles.Format.EVERYTHING_JSON, csv = RideExportFiles.Format.SENSOR_CSV,
                json = RideExportFiles.Format.ANALYSIS_JSON, route = RideExportFiles.Format.ROUTE_CSV;
        check(RideExportFiles.EVERYTHING_ON_SHEET, "The sheet lists Everything");
        check(RideExportFiles.DEFAULT.equals(EnumSet.of(e)) && RideExportFiles.parse(null).equals(EnumSet.of(e)), "No remembered choice: Everything only");
        check(RideExportFiles.listed(null).equals(Arrays.asList(e, csv, json)), "A scooter ride lists Everything first: " + RideExportFiles.listed(null));
        check(RideExportFiles.initial(RideExportFiles.parse(null), null).equals(EnumSet.of(e)), "Checked when the sheet opens");
        Set<RideExportFiles.Format> before = RideExportFiles.parse("sensor_csv,analysis_json,route_csv");
        check(before.equals(EnumSet.of(e, csv, json, route)), "A choice from before Everything gains it once and keeps its boxes: " + before);
        String unchecked = RideExportFiles.format(RideExportFiles.remember(before, null, EnumSet.of(csv)));
        check(unchecked.equals("sensor_csv,route_csv,everything_json_off"), "Unchecking it is remembered: " + unchecked);
        check(RideExportFiles.parse(unchecked).equals(EnumSet.of(csv, route)), "and it stays unchecked");
        check(T.key(T.RIDES_EXPORT_EVERYTHING_JSON).equals("rides_export_everything_json"), "Its catalog label");
        System.out.println("RideExportEverythingSheetTest: " + checks + " checks passed");
    }
}
