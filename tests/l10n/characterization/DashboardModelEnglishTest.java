package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * G5 characterization for B12 (dashboard model and quick access), committed before the batch changes
 * anything. It lists the English every quick-access shortcut carries today (id, label, icon, group and
 * the direct-toggle flag, in catalogue order) plus the default shortcut ids, and compares the listing
 * with dashboard-model-english.tsv line for line. The listing is the same with the phone set to en-US,
 * de-DE, tr-TR and ar-EG.
 *
 * QuickAccessGrid (an Android view) and DashboardConfig (SharedPreferences, org.json) cannot run on a
 * plain JVM; their English is pinned by the ledger (G1, G2) and the emulator census (G7).
 *
 * Run with "--write" from the repository root to print the listing (used once, on the base).
 */
public final class DashboardModelEnglishTest {
    static final String LISTING = "tests/l10n/characterization/dashboard-model-english.tsv";

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        List<String> first = null;
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                List<String> lines = listing();
                if (first == null) first = lines;
                else if (!first.equals(lines)) throw new AssertionError(phone + ": the listing differs from en-US");
            }
        } finally {
            Locale.setDefault(saved);
        }
        if (args.length > 0 && args[0].equals("--write")) {
            for (String line : first) System.out.println(line);
            return;
        }
        List<String> expected = Files.readAllLines(Paths.get(LISTING), StandardCharsets.UTF_8);
        int n = Math.min(expected.size(), first.size());
        for (int i = 0; i < n; i++)
            if (!expected.get(i).equals(first.get(i)))
                throw new AssertionError(LISTING + ":" + (i + 1) + ": expected \"" + expected.get(i) + "\" but was \"" + first.get(i) + "\"");
        if (expected.size() != first.size())
            throw new AssertionError(LISTING + ": expected " + expected.size() + " lines but the listing has " + first.size());
        System.out.println("DashboardModelEnglishTest: " + first.size() + " lines of quick-access English pinned, in 4 phone locales");
    }

    /** One "what\ttext" line per fact. */
    static List<String> listing() {
        List<String> out = new ArrayList<>();
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            String at = "entry " + e.id + " ";
            out.add(at + "label\t" + e.label);
            out.add(at + "icon\t" + e.icon);
            out.add(at + "group\t" + e.group);
            out.add(at + "featureKey\t" + e.featureKey);
            out.add(at + "directToggle\t" + e.directToggle());
        }
        out.add("defaults\t" + String.join(",", QuickAccessActions.defaultIds()));
        out.add("limits\t" + QuickAccessActions.MIN_ITEMS + "-" + QuickAccessActions.MAX_ITEMS);
        return out;
    }
}
