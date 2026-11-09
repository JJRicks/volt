package com.jjricks.volt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Ride details' Export ride data sheet: the button's ladder (full, then short, then the icon alone), the sheet's
 * English, and the nine keys it replaced, kept untranslated for B15's ledger with no app caller
 * (l10n/pending/export-menu-followups.md).
 */
public final class ExportMenuTextTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        check("Export ride data".equals(T.ridesExportData()) && "Export".equals(T.ridesExportDataShort()), "button rungs");
        check(T.ridesExportDataShort().length() < T.ridesExportData().length(), "the short rung is shorter");
        float[] widths = {160, 60};
        check(TileRung.first(widths, 200) == 0 && TileRung.first(widths, 100) == 1 && TileRung.first(widths, 40) == -1, "full, short, then icon alone");
        check("Saved 1 file.".equals(T.ridesToastExportSaved(1, 1)) && "Saved 3 files.".equals(T.ridesToastExportSaved(3, 3)), "saved toast plural");
        check("Saved 2 of 3 files. Try again for the rest.".equals(T.ridesToastExportPartial(3, 2, 3)), "partial toast follows the total");
        check("Route CSV, unavailable: No GPS fixes in this ride".equals(T.ridesExportUnavailableDescription(T.ridesExportRouteCsv(), T.ridesExportReasonNoFixes())), "spoken greyed-out row");
        String[] retired = {"rides_export_csv", "rides_export_csv_description", "rides_export_json", "rides_export_json_description",
                "rides_export_raw", "rides_export_phone_journal", "rides_export_motion_csv", "rides_export_phone", "rides_export_all_json"};
        String[] calls = {"T.ridesExportCsv(", "T.ridesExportCsvDescription(", "T.ridesExportJson(", "T.ridesExportJsonDescription(",
                "T.ridesExportRaw(", "T.ridesExportPhoneJournal(", "T.ridesExportMotionCsv(", "T.ridesExportPhone(", "T.ridesExportAllJson("};
        String base = read("res/values/strings_rides.xml"), spanish = read("res/values-es/strings_rides.xml");
        for (int i = 0; i < retired.length; i++) {
            check(base.contains("<string name=\"" + retired[i] + "\" translatable=\"false\""), retired[i] + " is kept untranslated");
            check(!spanish.contains("name=\"" + retired[i] + "\""), retired[i] + " is not in values-es");
        }
        File[] sources = new File("src/com/jjricks/volt").listFiles((dir, name) -> name.endsWith(".java"));
        check(sources != null && sources.length > 40, "app sources are readable");
        for (File source : sources) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            for (String call : calls) check(!text.contains(call), source.getName() + " no longer calls " + call);
        }
        String rides = read("src/com/jjricks/volt/RidesScreen.java");
        check(rides.contains("T.ridesExportRouteCsv()") && rides.contains("T.ridesExportGpx()") && rides.contains("T.ridesExportPhoneRaw()")
                && rides.contains("T.ridesExportMotionRaw()"), "the four phone file labels are still shown, as checkboxes");
        System.out.println("ExportMenuTextTest: " + checks + " checks passed (button ladder, sheet text, retired export keys)");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
