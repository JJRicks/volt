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
 * count: instead every branch of every pattern must count a probe read of its own exactly, and the patterns must
 * miss their near-misses (selfTest), and the source walk must read the app's named files.
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
     * Reads scan() must count, by pattern, one for each branch: every receiver and field of a (?:a|b) group, label(
     * and labels(, find(key) and find() around a call, and a field on each Feature variable PROBE_HEAD declares. Each
     * goes in its own synthetic file, twice, to pin the count, so a branch taken away fails. selfTest also takes each
     * group word and optional letter out of the pattern in turn and needs a probe to go unmatched, so a branch added
     * without a probe of its own fails too, as does a PATTERNS key (or feature-field) with no probes.
     */
    static final Map<String, List<String>> PROBES = new LinkedHashMap<>();
    static {
        probe("reason-text", "notice(d.reasonText());");
        probe("resolver-text", "muted(CapabilityResolver.text(d));");
        probe("variant-label", "button(variant.label(v));", "button(v.label(choice));", "row(d.label(raw));",
                "value(decision.label(raw));", "chips(d.labels());");
        probe("oem-label", "value(decision.oemLabel(raw));");
        probe("find-field", "title(find(key).label);", "header(find(key).group);", "body(find(key).description);",
                "pick(find(key).options);", "title(find(list.get(0).feature).label);");
        probe("value-label", "value(f.valueLabel(3));");
        probe("search-text", "haystack.add(f.searchText());");
        probe("model-line", "title(ModelRegistry.modelLine(id));");
        probe("series-about", "body(series.about);");
        probe("refusal-message", "notice(e.getMessage());");
        probe("feature-field", "show(feature.label);", "header(feature.group);", "body(feature.description);",
                "pick(feature.options);", "show(f.label);");
    }
    static final String PROBE_HEAD = "class P {\n  void a(ScooterCommands.Feature feature, ScooterCommands.Feature f) {\n";
    /** feature-field reads in a file that declares no ScooterCommands.Feature: some other type's fields. */
    static final String NO_FEATURE = "class Q {\n  void a(Row feature, Row f) {\n"
            + "    show(feature.label); header(feature.group); body(f.description); pick(f.options);\n  }\n}\n";
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

    private static void probe(String id, String... reads) {
        PROBES.put(id, Arrays.asList(reads));
    }

    /**
     * The scanner is not blind, whatever the table holds (batches shrink it to its header): every branch of every
     * pattern has a probe that needs it, every probe is counted exactly, in its own file and nowhere else, and the
     * near-misses, a file without a Feature declaration and B4's own files count nothing.
     */
    static void selfTest() {
        check(withoutEachBranch("x(?:a|b)s?").equals(Arrays.asList("x(?:b)s?", "x(?:a)s?", "x(?:a|b)s", "x(?:a|b)")),
                "self-test: withoutEachBranch finds each branch");
        Set<String> ids = new LinkedHashSet<>(PATTERNS.keySet());
        ids.add("feature-field");
        check(PROBES.keySet().equals(ids), "every pattern has probes (patterns " + ids + ", probes " + PROBES.keySet() + ")");
        Map<String, String> sources = new TreeMap<>();
        Map<String, Integer> expected = new TreeMap<>();
        StringBuilder all = new StringBuilder(PROBE_HEAD);
        for (Map.Entry<String, List<String>> probes : PROBES.entrySet()) {
            String id = probes.getKey();
            List<String> reads = probes.getValue();
            check(!reads.isEmpty(), "every pattern has probes (" + id + " has none)");
            Pattern pattern = id.equals("feature-field") ? featureField(featureNames(PROBE_HEAD)) : PATTERNS.get(id);
            for (String branchless : withoutEachBranch(pattern.pattern())) {
                Pattern narrowed = Pattern.compile(branchless);
                boolean needed = false;
                for (String read : reads) needed |= !narrowed.matcher(read).find();
                check(needed, "self-test: every branch of " + id + " needs a probe of its own, but all its probes still match "
                        + branchless);
            }
            for (int i = 0; i < reads.size(); i++) {
                String file = "Probe_" + id + "_" + i + ".java";
                sources.put(file, PROBE_HEAD + "    " + reads.get(i) + "\n    " + reads.get(i) + "\n  }\n}\n");
                expected.put(file + "\t" + id, 2);
                all.append("    ").append(reads.get(i)).append('\n');
            }
        }
        all.append("  }\n}\n");
        sources.put("NearMisses.java", NEAR_MISSES);
        sources.put("NoFeature.java", NO_FEATURE);
        for (String own : OWN) sources.put(own, all.toString());
        Map<String, Integer> found = scan(sources);
        for (Map.Entry<String, Integer> e : expected.entrySet())
            check(e.getValue().equals(found.get(e.getKey())), "self-test: the scan counts " + found.getOrDefault(e.getKey(), 0)
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
            Set<String> names = featureNames(text);
            if (!names.isEmpty()) count(out, file, "feature-field", featureField(names), text);
        }
        return out;
    }

    /** The names a file gives its ScooterCommands.Feature variables and parameters. */
    static Set<String> featureNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = FEATURE_VARIABLE.matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /** feature-field: a field read, not a call, on one of the named Feature variables. */
    static Pattern featureField(Set<String> names) {
        List<String> quoted = new ArrayList<>();
        for (String name : names) quoted.add(Pattern.quote(name));
        return Pattern.compile("\\b(?:" + String.join("|", quoted) + ")\\.(?:label|group|description|options)\\b(?!\\s*\\()");
    }

    /**
     * The regex once for each branch it offers, with that branch taken away: each word of a (?:a|b) group, and each
     * optional letter (s?) made required and made absent. selfTest needs every one to miss a probe of the pattern.
     */
    static List<String> withoutEachBranch(String regex) {
        List<String> out = new ArrayList<>();
        Matcher group = Pattern.compile("\\(\\?:(\\w+(?:\\|\\w+)+)\\)").matcher(regex);
        while (group.find()) {
            List<String> words = Arrays.asList(group.group(1).split("\\|"));
            for (String word : words) {
                List<String> rest = new ArrayList<>(words);
                rest.remove(word);
                out.add(regex.substring(0, group.start(1)) + String.join("|", rest) + regex.substring(group.end(1)));
            }
        }
        Matcher optional = Pattern.compile("(?<!\\\\)(\\w)\\?").matcher(regex);
        while (optional.find()) {
            String before = regex.substring(0, optional.start()), after = regex.substring(optional.end());
            out.add(before + optional.group(1) + after);
            out.add(before + after);
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
