package com.jjricks.volt;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * G2, template equivalence: every exact, template and plural row of l10n/ledger/*.tsv (and each branch of a
 * choice row, a whole template of its own key), rendered through
 * EnglishText with sentinel arguments, equals the original Java concatenation evaluated with the same
 * sentinels, byte for byte. Text holes get "\u0001A" (A, B, ... by placeholder), whole numbers 1234567,
 * decimals 12.5 (Java's own string conversion, as the original "+" did). Owner-approved English changes
 * (l10n/english-changes.tsv) are undone first, so a row is compared with the English it was extracted
 * with. tools/l10n/ledger_check.py covers the plain %n$s rows in CI without Java; this covers every
 * conversion with Java's formatter.
 *
 * Plurals: every quantity in QUANTITIES goes through the catalog entry as EnglishText picks its form, and
 * must print what the base expression printed for that quantity. The base expression is the row's
 * "other" pieces (what G1 matched with the base line), except for a manual row (a base ternary with its
 * own branch for exactly 1), whose "one" pieces stand for that branch at 1. So a "one" item holding
 * English the base never printed fails whether or not the row lists it. A plural tagged
 * volt:english-fix="pending" keeps a known English bug, so each of its items, rendered at every quantity,
 * must print the base output. Each form the row lists is also checked against its own item.
 */
public final class LedgerTest {
    private static int checks, rows;
    /** The quantity sentinels every plural row is rendered with (CLDR's boundaries for one, few and many). */
    static final long[] QUANTITIES = {0, 1, 2, 3, 4, 5, 11, 12, 21, 22, 25, 100, 101, 111, 1000000};
    private static final String VOLT_NS = "https://volt.jjricks.com/ns/l10n";

    public static void main(String[] args) {
        Map<String, Integer> ids = new HashMap<>();
        for (int id = 0; id < T.size(); id++) ids.put(T.key(id), id);
        Map<String, List<String[]>> changes = changes(new File(L10nFiles.root(), "l10n/english-changes.tsv"));
        Map<String, String[]> plurals = plurals(new File(L10nFiles.root(), "res/values"));
        selfTest(ids);
        File[] ledgers = new File(L10nFiles.root(), "l10n/ledger").listFiles((d, n) -> n.endsWith(".tsv"));
        if (ledgers != null) {
            Arrays.sort(ledgers);
            for (File ledger : ledgers) {
                for (Map<String, String> row : L10nFiles.tsv(L10nFiles.read(ledger), "location", "pieces", "key", "kind", "note")) {
                    String where = "l10n/ledger/" + ledger.getName() + ":" + row.get("#line") + " " + row.get("location");
                    String kind = row.get("kind");
                    if (kind.equals("choice")) {
                        // Each branch is a whole template of its own key (G1 proved it is the base expression with
                        // its conditions forced); render each one with sentinels.
                        String[] keys = row.get("key").split(",", -1);
                        List<Object> leaves = new ArrayList<>();
                        branches(L10nFiles.json(row.get("pieces")), leaves);
                        check(keys.length == leaves.size(), where + ": " + keys.length + " keys for " + leaves.size() + " branches");
                        for (int i = 0; i < keys.length; i++) {
                            Integer branch = ids.get(keys[i]);
                            check(branch != null, where + ": key " + keys[i] + " is in the catalog");
                            List<String> problems = compare(branch, "template", leaves.get(i), changes);
                            check(problems.isEmpty(), where + ": branch " + keys[i] + ": " + problems);
                        }
                        rows++;
                        continue;
                    }
                    if (!kind.equals("exact") && !kind.equals("template") && !kind.equals("plural")) continue;
                    Integer id = ids.get(row.get("key"));
                    check(id != null, where + ": key " + row.get("key") + " is in the catalog");
                    List<String> problems = kind.equals("plural") && T.isPlural(id)
                            ? comparePlural(id, L10nFiles.json(row.get("pieces")), row.get("note").startsWith("manual:"), plurals, changes)
                            : compare(id, kind, L10nFiles.json(row.get("pieces")), changes);
                    check(problems.isEmpty(), where + ": " + problems);
                    rows++;
                }
            }
        }
        System.out.println("LedgerTest: " + checks + " checks passed (" + rows + " ledger rows rendered with sentinels)");
    }

    /** The comparison works: right templates pass, a changed space or a lost hole fails. */
    private static void selfTest(Map<String, Integer> ids) {
        Map<String, List<String[]>> none = new HashMap<>();
        Object quantity = L10nFiles.json("[{\"hole\": \"value\", \"arg\": 1, \"type\": \"s\"}, \" \", {\"hole\": \"unit\", \"arg\": 2, \"type\": \"s\"}]");
        check(compare(ids.get("fmt_quantity"), "template", quantity, none).isEmpty(), "a matching template passes");
        Object spaced = L10nFiles.json("[{\"hole\": \"value\", \"arg\": 1, \"type\": \"s\"}, \"  \", {\"hole\": \"unit\", \"arg\": 2, \"type\": \"s\"}]");
        check(!compare(ids.get("fmt_quantity"), "template", spaced, none).isEmpty(), "a doubled space fails");
        Object swapped = L10nFiles.json("[{\"hole\": \"unit\", \"arg\": 2, \"type\": \"s\"}, \" \", {\"hole\": \"value\", \"arg\": 1, \"type\": \"s\"}]");
        check(!compare(ids.get("fmt_quantity"), "template", swapped, none).isEmpty(), "swapped holes fail");
        Object percent = L10nFiles.json("[{\"hole\": \"Math.round(level)\", \"arg\": 1, \"type\": \"s\"}, \"%\"]");
        check(compare(ids.get("fmt_percent"), "template", percent, none).isEmpty(), "%% matches a literal percent sign");
        check(compare(ids.get("common_cancel"), "exact", L10nFiles.json("[\"Cancel\"]"), none).isEmpty(), "an exact row matches its literal");
        check(!compare(ids.get("common_cancel"), "exact", L10nFiles.json("[\"Cancel \"]"), none).isEmpty(), "an exact row with other text fails");
        Map<String, List<String[]>> changed = new HashMap<>();
        changed.put("common_cancel\tother", Arrays.asList(new String[][]{{"Cancel it", "Cancel"}}));
        check(compare(ids.get("common_cancel"), "exact", L10nFiles.json("[\"Cancel it\"]"), changed).isEmpty(), "a recorded English change is undone first");
        check(evaluate(L10nFiles.json("[\"x\", {\"hole\": \"n\", \"arg\": 1, \"type\": \"d\"}, {\"char\": \"!\"}]"), 0).equals("x1234567!"),
                "whole numbers and char pieces evaluate as Java's + does");
        Object readings = L10nFiles.json("[\"Readings for \", {\"helper\": \"plural\", \"model\": \"count_noun\", \"args\": [{\"hole\": \"n\", \"arg\": 1, \"type\": \"d\", \"quantity\": true}, \"scooter\"]}, \" skipped\"]");
        check(evaluate(readings, 1).equals("Readings for 1 scooter skipped") && evaluate(readings, 2).equals("Readings for 2 scooters skipped"),
                "a count_noun helper prints its count, a space and the noun, with an s unless the count is 1");
        List<Object> leaves = new ArrayList<>();
        branches(L10nFiles.json("{\"if\": \"a\", \"then\": [\"x\"], \"else\": {\"if\": \"b\", \"then\": [\"y\"], \"else\": [\"z\"]}}"), leaves);
        check(leaves.size() == 3 && evaluate(leaves.get(0), 0).equals("x") && evaluate(leaves.get(2), 0).equals("z"),
                "a choice row's branches are read then before else, depth first");
        check(render("%1$.1f km", new Object[]{12.5}).equals(evaluate(L10nFiles.json("[{\"hole\": \"d\", \"arg\": 1, \"type\": \"f\"}, \" km\"]"), 0)),
                "a one-decimal hole matches Double.toString of 12.5");
        // Plurals (B9): the base printed n + " minutes" for every n, including "1 minutes".
        String minutes = "[{\"hole\": \"n\", \"arg\": 1, \"type\": \"d\", \"quantity\": true}, \" minutes\"]";
        String minute = "[{\"hole\": \"n\", \"arg\": 1, \"type\": \"d\", \"quantity\": true}, \" minute\"]";
        Object otherOnly = L10nFiles.json("{\"other\": " + minutes + "}");
        Object both = L10nFiles.json("{\"one\": " + minute + ", \"other\": " + minutes + "}");
        Object same = L10nFiles.json("{\"one\": " + minutes + ", \"other\": " + minutes + "}");
        check(comparePlural("t", true, "%1$d minutes", "%1$d minutes", otherOnly, false, false, none).isEmpty(), "identical forms print the base at every quantity");
        check(comparePlural("t", true, null, "%1$d minutes", otherOnly, false, true, none).isEmpty(), "an \"other\"-only plural prints the base at every quantity");
        check(!comparePlural("t", true, "%1$d minute", "%1$d minutes", otherOnly, false, false, none).isEmpty(),
                "a \"one\" item the row does not list fails when it is not what the base printed for 1");
        check(!comparePlural("t", true, "%1$d minute", "%1$d minutes", both, false, false, none).isEmpty(),
                "a \"one\" form that is not the base expression fails without a manual (base ternary) row");
        check(comparePlural("t", true, "%1$d minute", "%1$d minutes", both, true, false, none).isEmpty(),
                "a manual row's \"one\" form stands for the base's branch for exactly 1");
        check(!comparePlural("t", true, "%1$d minute", "%1$d minutes", both, true, true, none).isEmpty(),
                "an english-fix=pending plural whose \"one\" item differs from the base output fails");
        check(comparePlural("t", true, "%1$d minutes", "%1$d minutes", same, false, true, none).isEmpty(),
                "an english-fix=pending plural with identical forms passes");
        check(!comparePlural("t", true, "%1$d minutes", "%1$d minute", otherOnly, false, false, none).isEmpty(), "a changed \"other\" item fails");
    }

    /** Plurals of res/values: key -> {quantities the entry has, "pending" when tagged volt:english-fix="pending"}. */
    static Map<String, String[]> plurals(File values) {
        Map<String, String[]> result = new HashMap<>();
        File[] files = values.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files == null) return result;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            for (File file : files) {
                NodeList nodes = factory.newDocumentBuilder().parse(file).getDocumentElement().getChildNodes();
                for (int i = 0; i < nodes.getLength(); i++) {
                    Node node = nodes.item(i);
                    if (!(node instanceof Element) || !((Element) node).getTagName().equals("plurals")) continue;
                    Element plural = (Element) node;
                    StringBuilder quantities = new StringBuilder();
                    NodeList items = plural.getElementsByTagName("item");
                    for (int k = 0; k < items.getLength(); k++) quantities.append(k == 0 ? "" : ",").append(((Element) items.item(k)).getAttribute("quantity"));
                    result.put(plural.getAttribute("name"), new String[]{quantities.toString(), plural.getAttributeNS(VOLT_NS, "english-fix")});
                }
            }
        } catch (Exception error) {
            throw new AssertionError("res/values: " + error);
        }
        return result;
    }

    /** A catalog plural against its ledger row, with EnglishText's English. */
    static List<String> comparePlural(int id, Object pieces, boolean manual, Map<String, String[]> plurals, Map<String, List<String[]>> changes) {
        String key = T.key(id);
        String[] entry = plurals.get(key);
        if (entry == null) { List<String> problems = new ArrayList<>(); problems.add(key + " is not a <plurals> of res/values"); return problems; }
        Set<String> quantities = new HashSet<>(Arrays.asList(entry[0].split(",")));
        // EnglishText serves "one" for exactly 1 and "other" for every other quantity.
        String one = quantities.contains("one") ? EnglishText.INSTANCE.plural(id, 1) : null;
        return comparePlural(key, T.isFormatted(id), one, EnglishText.INSTANCE.plural(id, 0), pieces, manual, "pending".equals(entry[1]), changes);
    }

    /**
     * Problems with one plural: its raw English "one" item (null when it has none) and "other" item, against
     * the row's forms. See the class comment for what each quantity must print.
     */
    static List<String> comparePlural(String key, boolean formatted, String one, String other, Object pieces, boolean manual, boolean pending,
                                      Map<String, List<String[]>> changes) {
        List<String> problems = new ArrayList<>();
        @SuppressWarnings("unchecked") Map<String, Object> forms = (Map<String, Object>) pieces;
        Map<String, String> items = new HashMap<>();
        if (one != null) items.put("one", undo(key, "one", one, changes));
        items.put("other", undo(key, "other", other, changes));
        // Each form the row lists, through its own item, at the quantities English uses it for.
        for (Map.Entry<String, Object> form : forms.entrySet()) {
            String item = items.get(form.getKey());
            if (item == null) { problems.add(form.getKey() + " is listed, but " + key + " has no such English item"); continue; }
            for (long q : form.getKey().equals("one") ? new long[]{1} : form.getKey().equals("other") ? QUANTITIES : new long[0]) {
                if (form.getKey().equals("other") && q == 1) continue;
                String expected = evaluate(form.getValue(), q);
                String actual = formatted ? render(item, arguments(form.getValue(), q)) : item;
                if (!actual.equals(expected)) problems.add(form.getKey() + " q=" + q + " renders " + quote(actual) + ", the original " + quote(expected));
            }
        }
        Object base = forms.get("other");
        if (base == null) { problems.add("the row has no \"other\" form, the base expression"); return problems; }
        for (long q : QUANTITIES) {
            Object original = manual && q == 1 && forms.containsKey("one") ? forms.get("one") : base;
            String expected = evaluate(original, q);
            // The form EnglishText (and, through AndroidText, the phone while Volt shows English) serves for q.
            String served = q == 1 && items.containsKey("one") ? "one" : "other";
            String actual = formatted ? render(items.get(served), arguments(original, q)) : items.get(served);
            if (!actual.equals(expected))
                problems.add("q=" + q + " shows the " + served + " item " + quote(actual) + ", but the base printed " + quote(expected)
                        + (manual ? "" : " (only a manual row, for a base with its own branch for 1, may differ from the \"other\" pieces)"));
            if (!pending) continue;
            for (Map.Entry<String, String> item : items.entrySet()) {
                String shown = formatted ? render(item.getValue(), arguments(base, q)) : item.getValue();
                String printed = evaluate(base, q);
                if (!shown.equals(printed))
                    problems.add("english-fix=pending: the " + item.getKey() + " item renders " + quote(shown) + " for q=" + q
                            + ", but the base printed " + quote(printed) + "; a pending plural keeps the base output in every form");
            }
        }
        return problems;
    }

    /** A choice row's branches, then before else, depth first: {"if": ..., "then": ..., "else": ...} or pieces. */
    static void branches(Object node, List<Object> out) {
        if (node instanceof Map && ((Map<?, ?>) node).containsKey("if")) {
            branches(((Map<?, ?>) node).get("then"), out);
            branches(((Map<?, ?>) node).get("else"), out);
        } else {
            out.add(node);
        }
    }

    /** Problems comparing one row's original expression with the catalog English rendered with sentinels. */
    static List<String> compare(int id, String kind, Object pieces, Map<String, List<String[]>> changes) {
        List<String> problems = new ArrayList<>();
        String key = T.key(id);
        if (kind.equals("plural") != T.isPlural(id)) { problems.add(key + " kind " + kind); return problems; }
        if (kind.equals("plural")) throw new IllegalArgumentException(key + ": plural rows go through comparePlural");
        String english = undo(key, "other", EnglishText.INSTANCE.string(id), changes);
        String expected = evaluate(pieces, 0);
        String actual = kind.equals("exact") || !T.isFormatted(id) ? english : render(english, arguments(pieces, 0));
        if (!actual.equals(expected)) problems.add("renders " + quote(actual) + ", the original " + quote(expected));
        return problems;
    }

    private static String render(String template, Object[] args) { return String.format(Locale.ROOT, template, args); }

    /** The original concatenation with sentinel hole values. */
    static String evaluate(Object pieces, long quantity) {
        StringBuilder out = new StringBuilder();
        for (Object piece : (List<?>) pieces) {
            if (piece instanceof String) { out.append((String) piece); continue; }
            Map<?, ?> hole = (Map<?, ?>) piece;
            if (hole.containsKey("char")) { out.append((String) hole.get("char")); continue; }
            if (hole.containsKey("helper")) { out.append(helper(hole, quantity)); continue; }
            out.append(String.valueOf(sentinel(hole, quantity)));
        }
        return out.toString();
    }

    /**
     * What a modelled helper call prints (tools/l10n/ledger_check.py HELPER_MODELS, whose declaration G1
     * matched token for token at the merge base): count_noun is BackupArchive.plural's
     * {@code count + " " + noun + (count == 1 ? "" : "s")}.
     */
    static String helper(Map<?, ?> piece, long quantity) {
        List<?> args = (List<?>) piece.get("args");
        Object[] values = new Object[args.size()];
        for (int i = 0; i < values.length; i++) values[i] = args.get(i) instanceof Map ? sentinel((Map<?, ?>) args.get(i), quantity) : args.get(i);
        if ("count_noun".equals(piece.get("model"))) {
            long count = ((Number) values[0]).longValue();
            return count + " " + values[1] + (count == 1 ? "" : "s");
        }
        throw new AssertionError("unknown helper model " + piece.get("model"));
    }

    /** The holes of one form, with those inside a helper call's arguments. */
    private static List<Map<?, ?>> holes(Object pieces) {
        List<Map<?, ?>> found = new ArrayList<>();
        for (Object piece : (List<?>) pieces) {
            if (!(piece instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) piece;
            if (map.containsKey("hole")) found.add(map);
            if (map.containsKey("helper")) for (Object arg : (List<?>) map.get("args")) if (arg instanceof Map) found.add((Map<?, ?>) arg);
        }
        return found;
    }

    /** Placeholder arguments in index order for the holes of one form. */
    private static Object[] arguments(Object pieces, long quantity) {
        Map<Integer, Object> byArg = new HashMap<>();
        int max = 0;
        for (Object piece : holes(pieces)) {
            Map<?, ?> hole = (Map<?, ?>) piece;
            if (!hole.containsKey("arg")) continue;
            int arg = ((Long) hole.get("arg")).intValue();
            byArg.put(arg, sentinel(hole, quantity));
            max = Math.max(max, arg);
        }
        // Placeholders the original never filled (a plural's "one" form may drop the number) still get a value.
        Object[] args = new Object[Math.max(max, 9)];
        for (int i = 0; i < args.length; i++) args[i] = byArg.containsKey(i + 1) ? byArg.get(i + 1) : "\u0001" + (char) ('A' + i);
        return args;
    }

    private static Object sentinel(Map<?, ?> hole, long quantity) {
        if (Boolean.TRUE.equals(hole.get("quantity"))) return quantity;
        long arg = hole.containsKey("arg") ? (Long) hole.get("arg") : 1;
        // A hole passed differently at head ("as", e.g. through Fmt) arrives as text.
        if (hole.containsKey("as")) return "\u0001" + (char) ('A' + arg - 1);
        switch ((String) hole.get("type")) {
            case "d": return 1234567L;
            case "f": return 12.5;
            default: return "\u0001" + (char) ('A' + arg - 1);
        }
    }

    private static String undo(String key, String quantity, String text, Map<String, List<String[]>> changes) {
        List<String[]> list = changes.get(key + "\t" + quantity);
        if (list == null) return text;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!text.equals(list.get(i)[1])) throw new AssertionError("l10n/english-changes.tsv: " + key + "[" + quantity + "] became " + quote(list.get(i)[1]) + " but the next text is " + quote(text));
            text = list.get(i)[0];
        }
        return text;
    }

    /** key + tab + quantity -> [old, new] in file order. */
    private static Map<String, List<String[]>> changes(File file) {
        Map<String, List<String[]>> result = new HashMap<>();
        if (!file.isFile()) return result;
        for (Map<String, String> row : L10nFiles.tsv(L10nFiles.read(file), "key", "quantity", "old", "new", "note")) {
            String quantity = row.get("quantity").equals("-") ? "other" : row.get("quantity");
            result.computeIfAbsent(row.get("key") + "\t" + quantity, k -> new ArrayList<>())
                    .add(new String[]{L10nFiles.unescape(row.get("old")), L10nFiles.unescape(row.get("new"))});
        }
        return result;
    }

    private static String quote(String value) { return "\"" + value.replace("\u0001", "\\u0001").replace("\n", "\\n") + "\""; }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
