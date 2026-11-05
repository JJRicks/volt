package com.jjricks.volt;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Every character of the catalog text of each language that uses Saira (l10n/lang/<tag>.tsv font=saira)
 * is in all four bundled Saira weights (res/font/saira_*.ttf), read from their cmap tables, or is one of
 * the reviewed FALLBACK_SYMBOLS. A language whose letters Saira lacks must use font=system. Pseudo-locales
 * are not checked: en-XA's accents fall back to another font by design.
 *
 * A fallback symbol that Unicode also lets a phone draw as a colour emoji must be followed directly by
 * U+FE0E VARIATION SELECTOR-15 (TEXT_STYLE), and U+FE0E may appear nowhere else. That rule is about
 * Android's emoji font, not Saira, so it holds in font=system languages too. problems() is the check of
 * one text, entryProblems() the rule for one list entry and scan() the loop over a res/ folder's
 * languages; selfCheck() proves all three on synthetic text and a synthetic res/ tree. See
 * l10n/README.md, "Glyph coverage".
 */
public final class GlyphCoverageTest {
    private static int checks;

    /** VARIATION SELECTOR-15: asks for the symbol before it as text rather than as a colour emoji. */
    static final int TEXT_STYLE = 0xFE0E;

    /** A symbol no Saira weight maps that the catalog may still use, whether it needs TEXT_STYLE, and why it is listed. */
    static final class Fallback {
        final int codePoint;
        final boolean textStyle;
        final String why;

        Fallback(int codePoint, boolean textStyle, String why) {
            this.codePoint = codePoint;
            this.textStyle = textStyle;
            this.why = why;
        }
    }

    /**
     * The reviewed list. Each symbol is drawn as plain text by Android's own fallback font, and Volt shows
     * each today from Java literals. textStyle marks Unicode Emoji=Yes with Emoji_Presentation=No
     * (emoji-data.txt): text by default, but a phone may draw it as a colour emoji unless U+FE0E follows.
     * Only symbols go on it (entryProblems()). Adding one is a tooling pull request with review and
     * evidence that it renders as text on Android.
     */
    static final List<Fallback> FALLBACK_SYMBOLS = Collections.unmodifiableList(Arrays.asList(
            new Fallback(0x2713, false, "done marks: Save buttons, PIN, Paired and drive-mode chips, step pills, Background "
                    + "recording's Unrestricted state, Direction, Check finished, a compatibility read's byte count, Rides' "
                    + "compare row; not an emoji (U+2714 is)"),
            new Fallback(0x26A0, true, "Background recording's Optimized state; catalog text must use the text-style sequence; "
                    + "Emoji=Yes, Emoji_Presentation=No, so 26A0 is followed by FE0E"),
            new Fallback(0x2606, false, "a Live Stats reading that is not starred, and the Star readings to pin here hint; not an emoji"),
            new Fallback(0x2605, false, "a starred Live Stats reading; not an emoji (U+2B50 is)")));

    /** One language of l10n/lang as scan() reads it: its tag, its res/ folder and whether it uses Saira. */
    static final class Language {
        final String tag, folder;
        final boolean saira;

        Language(String tag, String folder, boolean saira) {
            this.tag = tag;
            this.folder = folder;
            this.saira = saira;
        }
    }

    /** What scan() read: the texts, the languages by font, the distinct characters checked against the weights, the symbols used. */
    static final class Scan {
        int texts, sairaLanguages, systemLanguages, characters;
        final Set<Integer> symbols = new TreeSet<>();
    }

    public static void main(String[] args) throws Exception {
        File root = L10nFiles.root();
        File[] fonts = new File(root, "res/font").listFiles((d, n) -> n.startsWith("saira") && n.endsWith(".ttf"));
        check(fonts != null && fonts.length == 4, "four Saira weights are bundled");
        Arrays.sort(fonts);
        Map<String, BitSet> weights = new TreeMap<>();
        for (File font : fonts) {
            BitSet set = cmap(L10nFiles.bytes(font));
            check(set.get('A') && set.get('z') && set.get('0') && set.get(' '), font.getName() + " maps Latin letters and digits");
            weights.put(font.getName(), set);
        }
        int fontChecks = checks;
        selfCheck(weights);
        int selfChecks = checks - fontChecks;
        List<Language> languages = new ArrayList<>();
        for (int i = 0; i < AppLanguages.size(); i++)
            languages.add(new Language(AppLanguages.tag(i), AppLanguages.FOLDERS[i], !AppLanguages.systemFont(i)));
        languagesMatchTables(new File(root, "l10n/lang"), languages);
        int languageChecks = checks - fontChecks - selfChecks;
        Scan scan = scan(new File(root, "res"), languages, weights);
        checks += scan.texts;
        System.out.println("GlyphCoverageTest: " + checks + " checks passed (" + fontChecks + " font, " + selfChecks + " self, "
                + languageChecks + " language, " + scan.texts + " text: " + scan.sairaLanguages + " font=saira and "
                + scan.systemLanguages + " font=system languages, " + scan.characters + " distinct characters in " + weights.size()
                + " Saira weights, " + scan.symbols.size() + " of " + FALLBACK_SYMBOLS.size() + " fallback symbols in use)");
    }

    /**
     * The language list main() scans agrees with l10n/lang/*.tsv, read here on its own: one entry per table,
     * the same folder, and font=saira exactly where the table says saira.
     */
    private static void languagesMatchTables(File lang, List<Language> languages) {
        String[] tables = lang.list((d, n) -> n.endsWith(".tsv"));
        check(tables != null && tables.length == languages.size() && !languages.isEmpty(),
                "one scanned language per l10n/lang table (" + languages.size() + " scanned)");
        for (Language language : languages) {
            Map<String, String> fields = new TreeMap<>();
            for (Map<String, String> row : L10nFiles.tsv(L10nFiles.read(new File(lang, language.tag + ".tsv")), "field", "value"))
                fields.put(row.get("field"), row.get("value"));
            check(language.folder.equals(fields.get("folder")) && (language.saira ? "saira" : "system").equals(fields.get("font")),
                    language.tag + " is scanned in res/" + language.folder + " as font=" + (language.saira ? "saira" : "system")
                    + ", as l10n/lang/" + language.tag + ".tsv says (" + fields.get("folder") + ", " + fields.get("font") + ")");
        }
    }

    /**
     * problems() on every text of every language's res/<folder>, a font=saira language against the weights;
     * throws listing every problem with its language, file and key. English must have text; another
     * language's folder may be absent (a draft before merge.py writes it).
     */
    static Scan scan(File res, List<Language> languages, Map<String, BitSet> weights) throws Exception {
        Scan scan = new Scan();
        List<String> failures = new ArrayList<>();
        for (Language language : languages) {
            if (language.saira) scan.sairaLanguages++;
            else scan.systemLanguages++;
            if (language.saira && weights.isEmpty()) failures.add(language.tag + ": no Saira weights to check font=saira text against");
            List<String[]> texts = texts(new File(res, language.folder));
            if (texts.isEmpty() && language.tag.equals(AppLanguages.ENGLISH))
                failures.add(language.tag + ": res/" + language.folder + " has no string or plural text");
            Set<Integer> distinct = new HashSet<>();
            for (String[] entry : texts) {
                scan.texts++;
                for (String problem : problems(entry[1], language.saira ? weights : Collections.<String, BitSet>emptyMap()))
                    failures.add(language.tag + " " + entry[0] + ": " + problem);
                for (int c : entry[1].codePoints().toArray()) {
                    if (fallback(c) != null) scan.symbols.add(c);
                    else if (language.saira && c != '\n' && c != '\t' && c != TEXT_STYLE) distinct.add(c);
                }
            }
            scan.characters += distinct.size();
        }
        if (!failures.isEmpty())
            throw new AssertionError(failures.size() + " glyph problem" + (failures.size() == 1 ? "" : "s") + " in the catalog:\n  "
                    + String.join("\n  ", failures));
        return scan;
    }

    /**
     * What is wrong with one decoded catalog text, one message per problem; empty when it passes. With
     * weights (a font=saira language), every code point except a line feed, a tab, a FALLBACK_SYMBOLS entry
     * and TEXT_STYLE must be in each weight. An empty map is a font=system language, which only gets the
     * second rule: a textStyle entry must be followed directly by TEXT_STYLE, and TEXT_STYLE may follow
     * nothing else.
     */
    static List<String> problems(String text, Map<String, BitSet> weights) {
        Set<String> out = new LinkedHashSet<>();
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            int c = points[i];
            Fallback symbol = fallback(c);
            if (c == TEXT_STYLE) {
                Fallback before = i > 0 ? fallback(points[i - 1]) : null;
                if (before == null || !before.textStyle)
                    out.add(describe(c) + (i == 0 ? " starts the text" : " follows " + describe(points[i - 1]))
                            + "; it may only follow a symbol that needs it (" + textStyleSymbols() + ")");
            } else if (symbol != null) {
                if (symbol.textStyle && (i + 1 >= points.length || points[i + 1] != TEXT_STYLE))
                    out.add(describe(c) + " must be followed directly by U+FE0E (write " + new String(Character.toChars(c))
                            + "\\uFE0E), or a phone may draw it as a colour emoji; a Java literal without it gets it in an"
                            + " ordinary pull request before it moves (l10n/README.md, Glyph coverage)");
            } else if (c != '\n' && c != '\t') {
                List<String> lacking = new ArrayList<>();
                for (Map.Entry<String, BitSet> weight : weights.entrySet()) if (!weight.getValue().get(c)) lacking.add(weight.getKey());
                if (!lacking.isEmpty())
                    out.add(String.join(", ", lacking) + (lacking.size() == 1 ? " has" : " have") + " no glyph for " + describe(c)
                            + "; use font=system for this language (a symbol needs a reviewed GlyphCoverageTest.FALLBACK_SYMBOLS"
                            + " entry: l10n/README.md, Glyph coverage)");
            }
        }
        return new ArrayList<>(out);
    }

    static Fallback fallback(int codePoint) {
        for (Fallback symbol : FALLBACK_SYMBOLS) if (symbol.codePoint == codePoint) return symbol;
        return null;
    }

    /**
     * Why an entry may not be on FALLBACK_SYMBOLS; empty when it may. It is a symbol (general category Sm,
     * Sc, Sk or So: never a letter, mark, digit, space or format character such as U+FE0E), at least one
     * Saira weight lacks it, and it says why it is listed.
     */
    static List<String> entryProblems(Fallback symbol, Map<String, BitSet> weights) {
        List<String> out = new ArrayList<>();
        int type = Character.getType(symbol.codePoint);
        if (type != Character.MATH_SYMBOL && type != Character.CURRENCY_SYMBOL && type != Character.MODIFIER_SYMBOL
                && type != Character.OTHER_SYMBOL)
            out.add(describe(symbol.codePoint) + " is not a symbol: only symbols go on FALLBACK_SYMBOLS, and a language whose"
                    + " letters Saira lacks uses font=system");
        boolean lacking = false;
        for (BitSet weight : weights.values()) lacking |= !weight.get(symbol.codePoint);
        if (!lacking) out.add(describe(symbol.codePoint) + " is in every Saira weight: delete its FALLBACK_SYMBOLS entry");
        if (symbol.why.trim().isEmpty()) out.add(describe(symbol.codePoint) + " does not say why it is listed");
        return out;
    }

    private static String textStyleSymbols() {
        List<String> names = new ArrayList<>();
        for (Fallback symbol : FALLBACK_SYMBOLS) if (symbol.textStyle) names.add(String.format("U+%04X", symbol.codePoint));
        return String.join(", ", names);
    }

    private static String describe(int codePoint) {
        String name = Character.getName(codePoint);
        return String.format("U+%04X '%s' %s", codePoint, new String(Character.toChars(codePoint)), name == null ? "(unassigned)" : name);
    }

    /**
     * The list's own rules, then problems() on synthetic text with the real weights: each listed symbol
     * passes (a textStyle one only with U+FE0E), and the near misses fail with the problem they name.
     * Then texts() and scan() on a synthetic res/ tree: a font=saira English with two files and a
     * plural, a font=system language, and a font=saira language whose folder does not exist yet.
     */
    private static void selfCheck(Map<String, BitSet> weights) throws Exception {
        Map<String, BitSet> system = Collections.emptyMap();
        Set<Integer> listed = new HashSet<>();
        for (Fallback symbol : FALLBACK_SYMBOLS) {
            String s = new String(Character.toChars(symbol.codePoint));
            check(listed.add(symbol.codePoint), describe(symbol.codePoint) + " is listed once in FALLBACK_SYMBOLS");
            List<String> entry = entryProblems(symbol, weights);
            check(entry.isEmpty(), "FALLBACK_SYMBOLS: " + entry);
            String good = symbol.textStyle ? s + "\uFE0E" : s;
            accepts(weights, "Optimized " + good);
            accepts(weights, good + " Star readings to pin here");
            accepts(system, "\u0416 " + good);
            if (symbol.textStyle) {
                rejects(weights, "Optimized " + s, "must be followed directly by U+FE0E");
                rejects(weights, s + " Optimized", "must be followed directly by U+FE0E");
                rejects(system, "\u0416 " + s, "must be followed directly by U+FE0E");
            } else {
                rejects(weights, s + "\uFE0E", "U+FE0E '\uFE0E' VARIATION SELECTOR-15 follows " + describe(symbol.codePoint));
            }
        }
        entryRejects(weights, new Fallback(0x0411, false, "a letter Saira lacks"), "U+0411 '\u0411' CYRILLIC CAPITAL LETTER BE is not a symbol");
        entryRejects(weights, new Fallback(TEXT_STYLE, false, "the selector itself"), "VARIATION SELECTOR-15 is not a symbol");
        entryRejects(weights, new Fallback(0x2060, false, "km/h kept on one line"), "WORD JOINER is not a symbol");
        entryRejects(weights, new Fallback(0x00B0, false, "degrees"), "DEGREE SIGN is in every Saira weight");
        entryRejects(weights, new Fallback(0x2714, false, " "), "HEAVY CHECK MARK does not say why");
        check(entryProblems(new Fallback(0x2714, true, "a heavy check"), weights).isEmpty()
                && entryProblems(new Fallback(0x22EF, false, "the chart legend"), weights).isEmpty(),
                "entryProblems() accepts an other symbol (So) and a math symbol (Sm) that Saira lacks");
        accepts(weights, "Save 12 % \u00b7 line\nbreak\ttab");
        rejects(weights, "Optimized \u2714", "have no glyph for U+2714");
        rejects(weights, "Optimized \uFE0E", "follows U+0020");
        rejects(weights, "\uFE0EOptimized", "starts the text");
        rejects(weights, "Optimized \u26A0\uFE0E\uFE0E", "follows U+FE0E");
        rejects(weights, "Optimized \u26A0\uFE0F", "U+26A0 '\u26A0' WARNING SIGN must be followed directly by U+FE0E", "no glyph for U+FE0F");
        rejects(weights, "\u0416", "have no glyph for U+0416 '\u0416' CYRILLIC CAPITAL LETTER ZHE; use font=system");
        rejects(weights, "km/\u2060h", "have no glyph for U+2060");
        rejects(weights, "Battery \uD83D\uDD0B", "no glyph for U+1F50B");
        accepts(system, "\u0416 \u2714");
        rejects(system, "\u0416\uFE0E", "follows U+0416");
        accepts(weights, decode("Optimized \u26A0\\uFE0E"));
        rejects(weights, decode("\"Optimized \\u26A0\""), "must be followed directly by U+FE0E");

        File tree = Files.createTempDirectory("glyph-coverage").toFile();
        try {
            File folder = new File(tree, "probe");
            write(new File(folder, "strings_probe.xml"), "    <string name=\"probe_warning\">Optimized \u26A0\\uFE0E</string>\n"
                    + "    <string name=\"probe_save\">\"\u2713 Save\"</string>\n"
                    + "    <plurals name=\"probe_count\">\n"
                    + "        <item quantity=\"one\">%1$d \u26A0</item>\n"
                    + "        <item quantity=\"other\">%1$d \u26A0\\uFE0E</item>\n"
                    + "    </plurals>\n");
            List<String[]> probe = texts(folder);
            check(probe.size() == 4 && probe.get(0)[0].equals("strings_probe.xml probe_warning")
                    && probe.get(0)[1].equals("Optimized \u26A0\uFE0E") && probe.get(1)[1].equals("\u2713 Save")
                    && probe.get(2)[0].equals("strings_probe.xml probe_count[one]")
                    && probe.get(3)[0].equals("strings_probe.xml probe_count[other]"), "texts() reads string and plural items with their keys");
            check(problems(probe.get(0)[1], weights).isEmpty() && problems(probe.get(1)[1], weights).isEmpty()
                    && problems(probe.get(2)[1], weights).size() == 1 && problems(probe.get(3)[1], weights).isEmpty(),
                    "an escaped U+FE0E counts in a string and a plural item, and a plural item without it fails");

            File res = new File(tree, "res");
            List<Language> languages = Arrays.asList(new Language("en", "values", true), new Language("xx", "values-xx", false),
                    new Language("yy", "values-yy", true));
            File english = new File(res, "values"), second = new File(english, "strings_b.xml"), other = new File(res, "values-xx/strings_a.xml");
            String stars = "    <plurals name=\"b_stars\">\n        <item quantity=\"one\">\u2605</item>\n"
                    + "        <item quantity=\"other\">\u2605 b</item>\n    </plurals>\n";
            write(new File(english, "strings_a.xml"), "    <string name=\"a_save\">\"\u2713 Ab\"</string>\n");
            write(second, "    <string name=\"b_warning\">C \u26A0\\uFE0E</string>\n" + stars);
            write(other, "    <string name=\"a_save\">\u0416 \u2714</string>\n");
            Scan scan = scan(res, languages, weights);
            check(scan.texts == 5 && scan.sairaLanguages == 2 && scan.systemLanguages == 1 && scan.characters == 4
                    && scan.symbols.equals(new TreeSet<>(Arrays.asList(0x2605, 0x2713, 0x26A0))),
                    "scan() reads every file, string and plural item of each language, and counts 5 texts, 2 font=saira and"
                    + " 1 font=system languages, 4 distinct characters and 3 symbols; it counted " + scan.texts + ", "
                    + scan.sairaLanguages + ", " + scan.systemLanguages + ", " + scan.characters + " and " + scan.symbols);
            write(second, "    <string name=\"b_warning\">C \u0416</string>\n" + stars);
            scanRejects(res, languages, weights, "a letter Saira lacks in English's second file", "1 glyph problem in the catalog:",
                    "\n  en strings_b.xml b_warning: ", "have no glyph for U+0416");
            write(second, "    <string name=\"b_warning\">C \u26A0\\uFE0E</string>\n" + stars);
            write(other, "    <string name=\"a_save\">\u0416\\uFE0E</string>\n");
            scanRejects(res, languages, weights, "a stray U+FE0E in a font=system language", "1 glyph problem in the catalog:",
                    "\n  xx strings_a.xml a_save: U+FE0E", "follows U+0416");
            write(other, "    <string name=\"a_save\">\u0416 \u2714</string>\n");
            scanRejects(res, languages, system, "no Saira weights", "2 glyph problems in the catalog:",
                    "\n  en: no Saira weights", "\n  yy: no Saira weights");
            delete(english);
            scanRejects(res, languages, weights, "no English folder", "1 glyph problem in the catalog:",
                    "\n  en: res/values has no string or plural text");
        } finally {
            delete(tree);
        }
    }

    /** entryProblems() finds exactly one problem, containing the expected fragment. */
    private static void entryRejects(Map<String, BitSet> weights, Fallback symbol, String expected) {
        List<String> found = entryProblems(symbol, weights);
        check(found.size() == 1 && found.get(0).contains(expected), "self-check: a FALLBACK_SYMBOLS entry for "
                + describe(symbol.codePoint) + " fails with \"" + expected + "\", but: " + found);
    }

    /** scan() throws, and its message contains every expected fragment. */
    private static void scanRejects(File res, List<Language> languages, Map<String, BitSet> weights, String what, String... expected)
            throws Exception {
        String message = null;
        try {
            scan(res, languages, weights);
        } catch (AssertionError failed) {
            message = failed.getMessage();
        }
        boolean matches = message != null;
        for (String fragment : expected) matches = matches && message.contains(fragment);
        check(matches, "self-check: scan() with " + what + " fails with " + Arrays.toString(expected) + ", but: " + message);
    }

    private static void write(File file, String body) throws Exception {
        File folder = file.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("cannot create " + folder);
        Files.write(file.toPath(), ("<resources>\n" + body + "</resources>\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void delete(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) delete(child);
        if (!file.delete() && file.exists()) throw new IllegalStateException("cannot delete " + file);
    }

    private static void accepts(Map<String, BitSet> weights, String text) {
        List<String> found = problems(text, weights);
        check(found.isEmpty(), "self-check: " + quote(text) + (weights.isEmpty() ? " (font=system)" : "") + " passes, but: " + found);
    }

    /** problems() finds exactly one problem per expected fragment, in order, each containing it. */
    private static void rejects(Map<String, BitSet> weights, String text, String... expected) {
        List<String> found = problems(text, weights);
        boolean matches = found.size() == expected.length;
        for (int i = 0; matches && i < expected.length; i++) matches = found.get(i).contains(expected[i]);
        check(matches, "self-check: " + quote(text) + (weights.isEmpty() ? " (font=system)" : "") + " fails with "
                + Arrays.toString(expected) + ", but: " + found);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int c : text.codePoints().toArray()) out.append(c < 0x20 || c == TEXT_STYLE || c > 0xFFFF ? String.format("<U+%04X>", c) : new String(Character.toChars(c)));
        return out.append('"').toString();
    }

    /**
     * The decoded text of every string and plural item in a folder's strings*.xml files, each as
     * {"file key" or "file key[quantity]", text}.
     */
    static List<String[]> texts(File folder) throws Exception {
        List<String[]> out = new ArrayList<>();
        File[] files = folder.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files == null) return out;
        Arrays.sort(files);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (File file : files) {
            Document document = factory.newDocumentBuilder().parse(file);
            for (String tag : new String[]{"string", "item"}) {
                NodeList nodes = document.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element element = (Element) nodes.item(i);
                    String key = tag.equals("string") ? element.getAttribute("name")
                            : ((Element) element.getParentNode()).getAttribute("name") + "[" + element.getAttribute("quantity") + "]";
                    out.add(new String[]{file.getName() + " " + key, decode(element.getTextContent())});
                }
            }
        }
        return out;
    }

    /** Resource escapes (\n, \t, \', \", \\, \@, \?, unicode escapes) as aapt reads them; the wrapping quotes go. */
    private static String decode(String raw) {
        String text = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ? raw.substring(1, raw.length() - 1) : raw;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) { out.append(c); continue; }
            char e = text.charAt(++i);
            if (e == 'n') out.append('\n');
            else if (e == 't') out.append('\t');
            else if (e == 'u' && i + 4 < text.length()) { out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16)); i += 4; }
            else out.append(e);
        }
        return out.toString();
    }

    /** The code points a TrueType font maps, from its Unicode cmap subtable (format 12, else format 4). */
    static BitSet cmap(byte[] font) {
        ByteBuffer b = ByteBuffer.wrap(font);
        int tables = b.getShort(4) & 0xffff, cmap = -1;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            if (new String(font, record, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("cmap")) cmap = b.getInt(record + 8);
        }
        if (cmap < 0) throw new IllegalArgumentException("no cmap table");
        int subtables = b.getShort(cmap + 2) & 0xffff, best = -1, bestFormat = 0;
        for (int i = 0; i < subtables; i++) {
            int platform = b.getShort(cmap + 4 + 8 * i) & 0xffff, encoding = b.getShort(cmap + 6 + 8 * i) & 0xffff;
            int offset = cmap + b.getInt(cmap + 8 + 8 * i), format = b.getShort(offset) & 0xffff;
            boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
            if (unicode && (format == 12 || (format == 4 && bestFormat != 12))) { best = offset; bestFormat = format; }
        }
        if (best < 0) throw new IllegalArgumentException("no Unicode cmap subtable");
        BitSet set = new BitSet();
        if (bestFormat == 12) {
            long groups = b.getInt(best + 12) & 0xffffffffL;
            for (int g = 0; g < groups; g++) {
                int start = b.getInt(best + 16 + 12 * g), end = b.getInt(best + 20 + 12 * g);
                set.set(start, end + 1);
            }
            return set;
        }
        int segments = (b.getShort(best + 6) & 0xffff) / 2;
        int ends = best + 14, starts = ends + 2 * segments + 2, deltas = starts + 2 * segments, ranges = deltas + 2 * segments;
        for (int s = 0; s < segments; s++) {
            int end = b.getShort(ends + 2 * s) & 0xffff, start = b.getShort(starts + 2 * s) & 0xffff;
            int delta = b.getShort(deltas + 2 * s), rangeOffset = b.getShort(ranges + 2 * s) & 0xffff;
            for (int c = start; c <= end && c != 0xffff; c++) {
                int glyph;
                if (rangeOffset == 0) glyph = (c + delta) & 0xffff;
                else {
                    int at = ranges + 2 * s + rangeOffset + 2 * (c - start);
                    glyph = b.getShort(at) & 0xffff;
                    if (glyph != 0) glyph = (glyph + delta) & 0xffff;
                }
                if (glyph != 0) set.set(c);
            }
        }
        return set;
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
