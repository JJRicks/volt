package com.jjricks.volt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Phone placement is gone: Phone & route's motion caption is one plural, rides_motion_samples, for every
 * ride, old or new. The five placement keys stay in the catalog only for B15's ledger and its tests,
 * untranslated, and no app code calls them.
 */
public final class MotionSamplesTextTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        check("1 motion sample".equals(T.ridesMotionSamples(1, Fmt.groupedInteger(Locale.US, 1))), "one sample");
        check("0 motion samples".equals(T.ridesMotionSamples(0, Fmt.groupedInteger(Locale.US, 0))), "no samples");
        check("1,200 motion samples".equals(T.ridesMotionSamples(1200, Fmt.groupedInteger(Locale.US, 1200))), "grouped count");
        check("3,000,000,000 motion samples".equals(T.ridesMotionSamples(3000000000L, Fmt.groupedInteger(Locale.US, 3000000000L))), "a long count");
        String[] retired = {"rides_placement", "rides_placement_mounted", "rides_placement_pocket", "rides_motion_note_mounted", "rides_motion_note_pocket"};
        String[] calls = {"T.ridesPlacement(", "T.ridesPlacementMounted(", "T.ridesPlacementPocket(", "T.ridesMotionNoteMounted(", "T.ridesMotionNotePocket("};
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
            check(!text.contains("\"phone_placement\""), source.getName() + " no longer reads or writes phone_placement");
        }
        System.out.println("MotionSamplesTextTest: " + checks + " checks passed (one motion caption, retired placement keys)");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
