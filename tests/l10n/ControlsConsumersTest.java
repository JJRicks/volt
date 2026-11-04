package com.jjricks.volt;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B4's consumer guard. B4 adds display accessors to the controls catalogue and capability classes (Feature.label(),
 * description(), groupLabel(), optionLabel(), valueText(); FeatureVariant.text(); Decision.labelText(),
 * labelTexts(), oemText(), reasonMessage(); ScooterCommands.Refused.msg(); ModelRegistry.modelLineText;
 * NaveeModels.Series.aboutLabel), but the screens and controllers that show this text belong to other batches,
 * several of them built in parallel from a base where these accessors do not exist. Until each one switches,
 * those sites keep showing the English.
 *
 * This test counts every direct read of the English (or of text rendered once, at call time) in src/ outside B4's
 * own files, by pattern, and holds the counts exactly to l10n/pending/b4-controls-consumers.tsv, where each row
 * names the batch or follow-up that switches it, or says why it stays English. It fails when a read is added
 * (a new English consumer nobody listed) and when one goes away without its row being updated, so the hand-off
 * list cannot go stale silently. The patterns go by name, so a row may count a read of another type with the
 * same name; the rows are per file, so that is noise for the follow-up to check, never a missed file.
 */
public final class ControlsConsumersTest {
    /** B4's own files (plan 3.B "B4"): they define the accessors and are not consumers. */
    static final Set<String> OWN = new LinkedHashSet<>(Arrays.asList(
            "ScooterCommands.java", "FeatureVariant.java", "ModelRegistry.java", "CapabilityResolver.java", "MutationGate.java",
            "ModelTiles.java", "NaveeModels.java", "WireCommands.java", "Protocol.java", "DeviceDescriptor.java",
            "AdvertisedIdentity.java", "ReadPolicy.java", "DiscoveryCoordinator.java", "DriveModeSurvey.java", "DriveModeStep.java"));

    /**
     * Pattern id to regex; feature-field is counted separately (it needs the file's Feature variable names).
     * find-field allows one level of parentheses in find()'s argument, as in find(list.get(0).feature).label.
     */
    static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
    static {
        PATTERNS.put("reason-text", Pattern.compile("\\.reasonText\\(\\)"));
        PATTERNS.put("resolver-text", Pattern.compile("\\bCapabilityResolver\\.text\\("));
        PATTERNS.put("variant-label", Pattern.compile("\\b(?:variant|v|d|decision)\\.labels?\\("));
        PATTERNS.put("oem-label", Pattern.compile("\\.oemLabel\\("));
        PATTERNS.put("find-field", Pattern.compile("\\bfind\\((?:[^()]|\\([^()]*\\))*\\)\\.(?:label|group|description|options)\\b(?!\\s*\\()"));
        PATTERNS.put("value-label", Pattern.compile("\\.valueLabel\\("));
        PATTERNS.put("search-text", Pattern.compile("\\.searchText\\(\\)"));
        PATTERNS.put("model-line", Pattern.compile("\\bModelRegistry\\.modelLine\\("));
        PATTERNS.put("series-about", Pattern.compile("\\.about\\b(?!\\s*\\()"));
        PATTERNS.put("refusal-message", Pattern.compile("\\bnotice\\(\\w+\\.getMessage\\(\\)\\)"));
    }
    static final Pattern FEATURE_VARIABLE = Pattern.compile("\\bScooterCommands\\.Feature\\s+([a-zA-Z_]\\w*)");
    static final String TSV = "l10n/pending/b4-controls-consumers.tsv";
    /** One read per pattern, as src/ writes them, and the accessor forms beside them, which count nothing. */
    static final String PROBE = "ScooterCommands.Feature f=null;notice(f.label);f.label();"
            + "x(d.reasonText());d.reasonMessage();CapabilityResolver.text(r);variant.label(1);variant.text(1);d.labelText(1);"
            + "o.oemLabel(1);find(list.get(0).feature).label;find(k).label();f.valueLabel(1);f.valueText(1);s.searchText();"
            + "ModelRegistry.modelLine(d);ModelRegistry.modelLineText(d);series.about;series.about();notice(e.getMessage());notice(refusal(e));";

    private static int checks;

    public static void main(String[] args) {
        Map<String, Integer> found = scan(L10nFiles.appSources());
        if (args.length > 0 && args[0].equals("--print")) {
            for (Map.Entry<String, Integer> e : found.entrySet()) System.out.println(e.getKey() + "\t" + e.getValue());
            return;
        }
        Map<String, Integer> listed = new TreeMap<>();
        List<Map<String, String>> rows = L10nFiles.tsv(L10nFiles.read(new File(L10nFiles.root(), TSV)), "file", "pattern", "count", "owner", "follow_up");
        for (Map<String, String> row : rows) {
            String where = TSV + ":" + row.get("#line");
            String key = row.get("file") + "\t" + row.get("pattern");
            check(PATTERNS.containsKey(row.get("pattern")) || row.get("pattern").equals("feature-field"), where + ": unknown pattern " + row.get("pattern"));
            check(!OWN.contains(row.get("file")), where + ": " + row.get("file") + " is B4's own file");
            check(!row.get("owner").trim().isEmpty() && !row.get("follow_up").trim().isEmpty(), where + ": every row names its owner and follow-up");
            check(listed.put(key, Integer.parseInt(row.get("count"))) == null, where + ": duplicate row " + key);
        }
        for (Map.Entry<String, Integer> e : found.entrySet())
            check(e.getValue().equals(listed.get(e.getKey())), "src has " + e.getValue() + " " + e.getKey().replace('\t', ' ')
                    + " read(s) but " + TSV + " lists " + listed.get(e.getKey()) + ": wire them to B4's display accessors, or update the row");
        for (Map.Entry<String, Integer> e : listed.entrySet())
            check(found.containsKey(e.getKey()), TSV + " lists " + e.getValue() + " " + e.getKey().replace('\t', ' ') + " read(s) that src no longer has: update the row");
        // The scan itself works whatever is left to switch (the follow-ups shrink the table toward empty): it reads
        // src/, and each pattern counts one read in a probe written the way consumers write it.
        check(L10nFiles.appSources().size() > 100, "the scan reads src/ (" + L10nFiles.appSources().size() + " files)");
        check(L10nFiles.appSources().keySet().containsAll(OWN), "the scan sees B4's own files, which it skips");
        Map<String, Integer> probe = scan(java.util.Collections.singletonMap("Probe.java", PROBE));
        for (String id : PATTERNS.keySet()) check(Integer.valueOf(1).equals(probe.get("Probe.java\t" + id)), "pattern " + id + " counts the probe's one read (" + probe.get("Probe.java\t" + id) + ")");
        check(Integer.valueOf(1).equals(probe.get("Probe.java\tfeature-field")), "feature-field counts the probe's one read");
        System.out.println("ControlsConsumersTest: " + checks + " checks passed (" + found.size() + " consumer rows).");
    }

    /** "File.java\tpattern" to the number of reads, for every pair with at least one, in file then pattern order. */
    static Map<String, Integer> scan(Map<String, String> sources) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String file = source.getKey(), text = source.getValue();
            if (OWN.contains(file)) continue;
            for (Map.Entry<String, Pattern> p : PATTERNS.entrySet()) count(out, file, p.getKey(), p.getValue(), text);
            Set<String> names = new LinkedHashSet<>();
            Matcher m = FEATURE_VARIABLE.matcher(text);
            while (m.find()) names.add(m.group(1));
            if (!names.isEmpty()) {
                List<String> quoted = new ArrayList<>();
                for (String name : names) quoted.add(Pattern.quote(name));
                count(out, file, "feature-field", Pattern.compile("\\b(?:" + String.join("|", quoted)
                        + ")\\.(?:label|group|description|options)\\b(?!\\s*\\()"), text);
            }
        }
        return out;
    }

    private static void count(Map<String, Integer> out, String file, String id, Pattern pattern, String text) {
        int n = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) n++;
        if (n > 0) out.put(file + "\t" + id, n);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
