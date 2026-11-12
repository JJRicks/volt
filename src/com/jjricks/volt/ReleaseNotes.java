package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The installed version's notes for the one-time What's new sheet (owner's answers 9, 10 and 12 on #383: it carries
 * the changelog). build.sh copies this version's section of CHANGELOG.md into the APK as {@link #ASSET}, so the
 * sheet needs no network and always shows the notes of the version that is installed. Pure Java: this class finds
 * a version's section, checks the bundled one belongs to the installed version, and turns its Markdown into items
 * of plain, bold and code spans for the sheet to style. Links keep their words; their addresses are dropped.
 */
final class ReleaseNotes {
    /** The bundled section, inside the APK's assets (beside, never among, the licence texts). */
    static final String ASSET = "whats-new/notes.md";
    /** The notes shown before the sheet points to the full release notes instead. */
    static final int MAX_CHARS = 4000;
    /** A version's release page; the version follows "v" ("…/tag/v1.14.0"). */
    static final String RELEASE_TAG = "https://github.com/JJRicks/volt/releases/tag/v";

    private ReleaseNotes() {}

    /** A run of text: plain, bold ({@code **…**}) or code ({@code `…`}). */
    static final class Span {
        final String text;
        final boolean bold, code;
        Span(String text, boolean bold, boolean code) { this.text = text; this.bold = bold; this.code = code; }
    }

    /** One list item or paragraph; nested when it was indented under another item. */
    static final class Item {
        final List<Span> spans;
        final boolean bullet, nested;
        Item(List<Span> spans, boolean bullet, boolean nested) {
            this.spans = Collections.unmodifiableList(spans); this.bullet = bullet; this.nested = nested;
        }
        String plain() {
            StringBuilder out = new StringBuilder();
            for (Span span : spans) out.append(span.text);
            return out.toString();
        }
    }

    /** A version's notes: the version as its heading names it ("1.14.0"), its items, and whether some were left out. */
    static final class Notes {
        final String version;
        final List<Item> items;
        final boolean cut;
        Notes(String version, List<Item> items, boolean cut) {
            this.version = version; this.items = Collections.unmodifiableList(items); this.cut = cut;
        }
        /** The release page with the full notes. */
        String page() { return RELEASE_TAG + version; }
    }

    /**
     * The section of a changelog for version (numbers only, "1.14.0"): its "## 1.14.0 — date" heading and every
     * line up to the next "## " heading, or null when the changelog has none. build.sh does the same in Python.
     */
    static String section(String changelog, String version) {
        if (changelog == null || version == null) return null;
        String[] lines = changelog.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = null;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (out != null) break;
                if (version.equals(headingVersion(line))) out = new StringBuilder();
            }
            if (out != null) out.append(line).append('\n');
        }
        return out == null ? null : out.toString();
    }

    /** The version a "## 1.14.0 — date" heading names, or null. */
    static String headingVersion(String line) {
        if (line == null || !line.startsWith("## ")) return null;
        String rest = line.substring(3).trim();
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) end++;
        String version = rest.substring(0, end);
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        return VersionName.numbers(version) == null || !version.matches("[0-9]+(\\.[0-9]+){1,3}") ? null : version;
    }

    /**
     * The bundled section's notes, only when its heading names the installed version (a dev build of 1.14.0 counts as
     * 1.14.0), so a stale or foreign file never shows. At most about {@link #MAX_CHARS} characters of items (always the
     * first); null when nothing usable is there.
     */
    static Notes parse(String section, String installed) {
        if (section == null || installed == null) return null;
        String[] lines = section.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String version = null;
        int start = 0;
        for (; start < lines.length; start++) {
            if (lines[start].trim().isEmpty()) continue;
            version = headingVersion(lines[start]);
            break;
        }
        int[] mine = VersionName.numbers(installed), theirs = version == null ? null : VersionName.numbers(version);
        if (mine == null || theirs == null || VersionName.compare(mine, theirs) != 0) return null;
        List<Item> items = new ArrayList<>();
        StringBuilder text = null;
        boolean bullet = false, nested = false;
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (line.startsWith("## ")) break;
            boolean listItem = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ");
            if (trimmed.isEmpty() || listItem || trimmed.startsWith("#")) {
                add(items, text, bullet, nested);
                text = null;
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("#")) {
                    // A smaller heading inside the section ("### Fixes") reads as a bold line of its own.
                    String words = trimmed.replaceFirst("^#+\\s*", "");
                    if (!words.isEmpty()) items.add(new Item(Collections.singletonList(new Span(words, true, false)), false, false));
                    continue;
                }
                bullet = true;
                nested = indent(line) >= 2;
                text = new StringBuilder(trimmed.substring(2).trim());
                continue;
            }
            if (text == null) {
                text = new StringBuilder(trimmed);
                bullet = false;
                nested = false;
            } else text.append(' ').append(trimmed);
        }
        add(items, text, bullet, nested);
        if (items.isEmpty()) return null;
        List<Item> shown = new ArrayList<>();
        int length = 0;
        for (Item item : items) {
            int next = item.plain().length();
            if (!shown.isEmpty() && length + next > MAX_CHARS) break;
            shown.add(item);
            length += next;
        }
        return new Notes(version, shown, shown.size() < items.size());
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    private static void add(List<Item> items, StringBuilder text, boolean bullet, boolean nested) {
        if (text == null) return;
        List<Span> spans = inline(text.toString());
        if (!spans.isEmpty()) items.add(new Item(spans, bullet, nested));
    }

    /**
     * Markdown's inline marks as spans: {@code **bold**} and {@code __bold__}, {@code `code`}, {@code *emphasis*}
     * and {@code _emphasis_} (shown plain), links ({@code [words](address)}, the words only) and backslash escapes.
     * An unclosed mark is kept as written.
     */
    static List<Span> inline(String text) {
        List<Span> spans = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean bold = false;
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_[]()#+-.!".indexOf(text.charAt(i + 1)) >= 0) {
                run.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i + 1) {
                    flush(spans, run, bold);
                    spans.add(new Span(text.substring(i + 1, close), bold, true));
                    i = close + 1;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && text.charAt(i + 1) == c) {
                String mark = text.substring(i, i + 2);
                if (bold || text.indexOf(mark, i + 2) > i + 2) {
                    flush(spans, run, bold);
                    bold = !bold;
                    i += 2;
                    continue;
                }
            }
            if (c == '*' || c == '_' && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                int close = text.indexOf(c, i + 1);
                if (close > i + 1 && (c == '*' || close + 1 >= n || !Character.isLetterOrDigit(text.charAt(close + 1)))) {
                    // Emphasis: its words, unmarked.
                    run.append(text, i + 1, close);
                    i = close + 1;
                    continue;
                }
            }
            if (c == '[') {
                int closeText = text.indexOf(']', i + 1);
                if (closeText > i && closeText + 1 < n && text.charAt(closeText + 1) == '(') {
                    int closeLink = text.indexOf(')', closeText + 2);
                    if (closeLink > closeText) {
                        run.append(text, i + 1, closeText);
                        i = closeLink + 1;
                        continue;
                    }
                }
            }
            run.append(c);
            i++;
        }
        flush(spans, run, bold);
        return spans;
    }

    private static void flush(List<Span> spans, StringBuilder run, boolean bold) {
        if (run.length() == 0) return;
        spans.add(new Span(run.toString(), bold, false));
        run.setLength(0);
    }
}
