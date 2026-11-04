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
 *
 * Batches are meant to shrink the table to its header, so the guard that the scan is not blind cannot be a row
 * count: instead every pattern must count a probe read exactly and miss its near-misses (selfTest), and the
 * source walk must read the app's named files.
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

    /** Files the real walk must always see, so a broken walk cannot pass by finding nothing. */
    static final List<String> ALWAYS = Arrays.asList("MainActivity.java", "BleController.java", "ScooterService.java");

    /**
     * One read per pattern, each of which scan() must count. Every PATTERNS key and feature-field needs a probe, so a
     * pattern added without one fails here. Each probe goes in its own synthetic file, twice, to pin the count.
     */
    static final Map<String, String> PROBES = new LinkedHashMap<>();
    static {
        PROBES.put("reason-text", "notice(d.reasonText());");
        PROBES.put("resolver-text", "muted(CapabilityResolver.text(d));");
        PROBES.put("variant-label", "button(variant.label(v));");
        PROBES.put("oem-label", "value(decision.oemLabel(raw));");
        PROBES.put("find-field", "title(find(list.get(0).feature).label);");
        PROBES.put("value-label", "value(f.valueLabel(3));");
        PROBES.put("search-text", "haystack.add(f.searchText());");
        PROBES.put("model-line", "title(ModelRegistry.modelLine(id));");
        PROBES.put("series-about", "body(series.about);");
        PROBES.put("refusal-message", "notice(e.getMessage());");
        PROBES.put("feature-field", "show(feature.label);");
    }
    /** Reads that must not be counted: call forms, display accessors, names that only contain a field, no Feature. */
    static final String NEAR_MISSES = "ScooterCommands.Feature feature = f;\n"
            + "show(feature.label()); show(feature.description()); show(feature.optionLabel(0)); show(feature.labels);\n"
            + "show(myfeature.label); show(featureX.group); show(other.label); show(find(key).label());\n"
            + "button(decision.labelText(v)); button(other.label(v)); value(decision.oemText(raw));\n"
            + "notice(d.reasonMessage()); title(ModelRegistry.modelLineText); body(series.aboutLabel); body(x.about());\n"
            + "notice(((ScooterCommands.Refused) e).msg()); haystack.add(f.search());\n";

    private static int checks;

    public static void main(String[] args) {
        selfTest();
        Map<String, String> sources = L10nFiles.appSources();
        check(sources.size() > 100, "the source walk sees the app (" + sources.size() + " files)");
        for (String name : ALWAYS)
            check(sources.containsKey(name) && sources.get(name).contains("class "), "the source walk reads " + name);
        Map<String, Integer> found = scan(sources);
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
        System.out.println("ControlsConsumersTest: " + checks + " checks passed (" + found.size() + " consumer rows).");
    }

    /**
     * The scanner is not blind, whatever the table holds (batches shrink it to its header): every pattern counts its
     * probe exactly, in its own file and nowhere else, and the near-misses and B4's own files count nothing.
     */
    static void selfTest() {
        Set<String> ids = new LinkedHashSet<>(PATTERNS.keySet());
        ids.add("feature-field");
        check(PROBES.keySet().equals(ids), "every pattern has a probe (patterns " + ids + ", probes " + PROBES.keySet() + ")");
        Map<String, String> sources = new TreeMap<>();
        Map<String, Integer> expected = new TreeMap<>();
        StringBuilder all = new StringBuilder("ScooterCommands.Feature feature = f;\n");
        for (Map.Entry<String, String> probe : PROBES.entrySet()) {
            String file = "Probe_" + probe.getKey() + ".java";
            sources.put(file, "class P {\n  void a(ScooterCommands.Feature feature) {\n    " + probe.getValue() + "\n    "
                    + probe.getValue() + "\n  }\n}\n");
            expected.put(file + "\t" + probe.getKey(), 2);
            all.append(probe.getValue()).append('\n');
        }
        sources.put("NearMisses.java", NEAR_MISSES);
        for (String own : OWN) sources.put(own, all.toString());
        Map<String, Integer> found = scan(sources);
        for (Map.Entry<String, Integer> e : expected.entrySet())
            check(e.getValue().equals(found.get(e.getKey())), "self-test: the scan counts " + found.get(e.getKey())
                    + " " + e.getKey().replace('\t', ' ') + " read(s), expected " + e.getValue());
        for (Map.Entry<String, Integer> e : found.entrySet())
            check(expected.containsKey(e.getKey()), "self-test: the scan counts " + e.getValue() + " "
                    + e.getKey().replace('\t', ' ') + " read(s) that are not reads (near-miss, own file or another probe)");
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
