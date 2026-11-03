package com.jjricks.volt;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Every character of the catalog text of each language that uses Saira (l10n/lang/<tag>.tsv font=saira)
 * is in all four bundled Saira weights (res/font/saira_*.ttf), read from their cmap tables. A language
 * whose letters Saira lacks must use font=system. Pseudo-locales are not checked: en-XA's accents fall
 * back to another font by design.
 */
public final class GlyphCoverageTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = L10nFiles.root();
        File[] fonts = new File(root, "res/font").listFiles((d, n) -> n.startsWith("saira") && n.endsWith(".ttf"));
        check(fonts != null && fonts.length == 4, "four Saira weights are bundled");
        Arrays.sort(fonts);
        Map<String, BitSet> covered = new TreeMap<>();
        for (File font : fonts) {
            BitSet set = cmap(L10nFiles.bytes(font));
            check(set.get('A') && set.get('z') && set.get('0') && set.get(' '), font.getName() + " maps Latin letters and digits");
            covered.put(font.getName(), set);
        }
        int characters = 0;
        for (int i = 0; i < AppLanguages.size(); i++) {
            if (AppLanguages.systemFont(i)) continue;
            File folder = new File(root, "res/" + AppLanguages.FOLDERS[i]);
            for (int codePoint : text(folder).codePoints().distinct().toArray()) {
                if (codePoint == '\n' || codePoint == '\t') continue;
                characters++;
                for (Map.Entry<String, BitSet> font : covered.entrySet())
                    check(font.getValue().get(codePoint), AppLanguages.tag(i) + ": " + font.getKey() + " has no glyph for U+"
                            + String.format("%04X", codePoint) + " '" + new String(Character.toChars(codePoint)) + "'; use font=system for this language");
            }
        }
        System.out.println("GlyphCoverageTest: " + checks + " checks passed (" + characters + " distinct characters in " + covered.size() + " Saira weights)");
    }

    /** The decoded text of every string and plural item in a folder's strings*.xml files. */
    private static String text(File folder) throws Exception {
        StringBuilder out = new StringBuilder();
        File[] files = folder.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files == null) return "";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (File file : files) {
            for (String tag : new String[]{"string", "item"}) {
                NodeList nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) out.append(decode(((Element) nodes.item(i)).getTextContent())).append('\n');
            }
        }
        return out.toString();
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
