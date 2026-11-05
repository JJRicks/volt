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
 * one text; selfCheck() proves it on synthetic text. See l10n/README.md, "Glyph coverage".
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
     * Adding one is a tooling pull request with review and evidence that it renders as text on Android.
     */
    static final List<Fallback> FALLBACK_SYMBOLS = Collections.unmodifiableList(Arrays.asList(
            new Fallback(0x2713, false, "done marks: PIN and Paired chips, Save buttons, Direction, Check finished, step pills; "
                    + "not an emoji (U+2714 is)"),
            new Fallback(0x26A0, true, "Background recording's Optimized state; Emoji=Yes, Emoji_Presentation=No, "
                    + "with the text-style sequence 26A0 FE0E"),
            new Fallback(0x2606, false, "a Live Stats reading that is not starred, and the Star readings to pin here hint; not an emoji"),
            new Fallback(0x2605, false, "a starred Live Stats reading; not an emoji (U+2B50 is)")));

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
        int selfChecks = checks - fontChecks, texts = 0, sairaLanguages = 0, characters = 0;
        Set<Integer> symbolsUsed = new TreeSet<>();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < AppLanguages.size(); i++) {
            boolean saira = !AppLanguages.systemFont(i);
            if (saira) sairaLanguages++;
            Set<Integer> distinct = new HashSet<>();
            for (String[] entry : texts(new File(root, "res/" + AppLanguages.FOLDERS[i]))) {
                texts++;
                checks++;
                for (String problem : problems(entry[1], saira ? weights : Collections.<String, BitSet>emptyMap()))
                    failures.add(AppLanguages.tag(i) + " " + entry[0] + ": " + problem);
                for (int c : entry[1].codePoints().toArray()) {
                    if (fallback(c) != null) symbolsUsed.add(c);
                    else if (saira && c != '\n' && c != '\t' && c != TEXT_STYLE) distinct.add(c);
                }
            }
            characters += distinct.size();
        }
        if (!failures.isEmpty())
            throw new AssertionError(failures.size() + " glyph problem" + (failures.size() == 1 ? "" : "s") + " in the catalog:\n  "
                    + String.join("\n  ", failures));
        System.out.println("GlyphCoverageTest: " + checks + " checks passed (" + fontChecks + " font, " + selfChecks + " self, "
                + texts + " text: " + sairaLanguages + " font=saira and " + (AppLanguages.size() - sairaLanguages)
                + " font=system languages, " + characters + " distinct characters in " + weights.size() + " Saira weights, "
                + symbolsUsed.size() + " of " + FALLBACK_SYMBOLS.size() + " fallback symbols in use)");
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
                            + "\\uFE0E), or a phone may draw it as a colour emoji");
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
     */
    private static void selfCheck(Map<String, BitSet> weights) throws Exception {
        Map<String, BitSet> system = Collections.emptyMap();
        Set<Integer> listed = new HashSet<>();
        for (Fallback symbol : FALLBACK_SYMBOLS) {
            String s = new String(Character.toChars(symbol.codePoint));
            check(listed.add(symbol.codePoint) && symbol.codePoint != TEXT_STYLE && !symbol.why.isEmpty(),
                    describe(symbol.codePoint) + ": a fallback entry is listed once, is not U+FE0E and says why");
            boolean lacking = false;
            for (BitSet weight : weights.values()) lacking |= !weight.get(symbol.codePoint);
            check(lacking, describe(symbol.codePoint) + " is in every Saira weight: delete its FALLBACK_SYMBOLS entry");
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
        accepts(weights, "Save 12 % \u00b7 line\nbreak\ttab");
        rejects(weights, "Optimized \u2714", "have no glyph for U+2714");
        rejects(weights, "Optimized \uFE0E", "follows U+0020");
        rejects(weights, "\uFE0EOptimized", "starts the text");
        rejects(weights, "Optimized \u26A0\uFE0E\uFE0E", "follows U+FE0E");
        rejects(weights, "Optimized \u26A0\uFE0F", "U+26A0 '\u26A0' WARNING SIGN must be followed directly by U+FE0E", "no glyph for U+FE0F");
        rejects(weights, "\u0416", "have no glyph for U+0416 '\u0416' CYRILLIC CAPITAL LETTER ZHE; use font=system");
        rejects(weights, "Battery \uD83D\uDD0B", "no glyph for U+1F50B");
        accepts(system, "\u0416 \u2714");
        rejects(system, "\u0416\uFE0E", "follows U+0416");
        accepts(weights, decode("Optimized \u26A0\\uFE0E"));
        rejects(weights, decode("\"Optimized \\u26A0\""), "must be followed directly by U+FE0E");

        File folder = Files.createTempDirectory("glyph-coverage").toFile();
        File file = new File(folder, "strings_probe.xml");
        try {
            Files.write(file.toPath(), ("<resources>\n"
                    + "    <string name=\"probe_warning\">Optimized \u26A0\\uFE0E</string>\n"
                    + "    <string name=\"probe_save\">\"\u2713 Save\"</string>\n"
                    + "    <plurals name=\"probe_count\">\n"
                    + "        <item quantity=\"one\">%1$d \u26A0</item>\n"
                    + "        <item quantity=\"other\">%1$d \u26A0\\uFE0E</item>\n"
                    + "    </plurals>\n"
                    + "</resources>\n").getBytes(StandardCharsets.UTF_8));
            List<String[]> probe = texts(folder);
            check(probe.size() == 4 && probe.get(0)[0].equals("strings_probe.xml probe_warning")
                    && probe.get(0)[1].equals("Optimized \u26A0\uFE0E") && probe.get(1)[1].equals("\u2713 Save")
                    && probe.get(2)[0].equals("strings_probe.xml probe_count[one]")
                    && probe.get(3)[0].equals("strings_probe.xml probe_count[other]"), "texts() reads string and plural items with their keys");
            check(problems(probe.get(0)[1], weights).isEmpty() && problems(probe.get(1)[1], weights).isEmpty()
                    && problems(probe.get(2)[1], weights).size() == 1 && problems(probe.get(3)[1], weights).isEmpty(),
                    "an escaped U+FE0E counts in a string and a plural item, and a plural item without it fails");
        } finally {
            if (!file.delete() && file.exists()) throw new IllegalStateException("cannot delete " + file);
            if (!folder.delete()) throw new IllegalStateException("cannot delete " + folder);
        }
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
