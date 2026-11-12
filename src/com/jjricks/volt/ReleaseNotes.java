package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release's notes, which GitHub writes in Markdown, read into blocks of plain text with style ranges that
 * ReleaseNotesView draws with Android spans. Pure Java: no Android, no HTML and no network.
 *
 * <p>It reads the common GitHub-flavoured forms: ATX ({@code ## Title}) and setext headings, bullet and numbered
 * lists nested by indent (a numbered list counted up from its first item's number, as GitHub shows it), quotes with
 * whatever blocks they hold, fenced code, rules, and paragraphs with their line breaks (GitHub shows a single newline in
 * release notes as a break); inline, bold, italic, strikethrough, code spans, links and images
 * ({@code [text](url)}, shown as "text (url)") and autolinks ({@code <https://...>}); a bare address stays exactly as
 * written. Anything else (tables, HTML, reference links, an unclosed {@code **}) stays as its literal text. Only the
 * markup itself is dropped, plus a code fence's language name, so every other character of the notes is shown (a
 * numbered item shows its place in the list, not the number written). Links are never made tappable here.
 */
final class ReleaseNotes {
    enum Kind { PARAGRAPH, HEADING, BULLET, NUMBERED, QUOTE, CODE, RULE }
    /** URL: a link's address, shown after its text. */
    enum Style { BOLD, ITALIC, STRIKE, CODE, URL }

    /** Deepest list level kept; deeper items sit at this level. */
    static final int MAX_DEPTH = 3;
    /** Deepest quote read as one; the ">" of quotes deeper still are dropped and their lines join this one. */
    static final int MAX_QUOTES = 8;
    /** Most quote bars drawn side by side for quotes inside quotes; deeper ones share the last bar. */
    static final int MAX_BARS = 3;
    static final String BULLET = "•";

    static final class Span {
        final Style style; final int start, end;
        Span(Style style, int start, int end) { this.style = style; this.start = start; this.end = end; }
        @Override public String toString() { return style + "[" + start + "," + end + ")"; }
    }

    static final class Block {
        final Kind kind;
        /** A heading's level (1 to 6), a list item's depth (0 to MAX_DEPTH), else 0. */
        final int level;
        /** A list item's marker, BULLET or its number in the list ("3." or "3)"); else "". */
        final String marker;
        /** A list item's marker as the notes wrote it ("-", "1." for a third item written "1."); else "". */
        final String written;
        /** Inside a quote: drawn with the quote's bar. A paragraph inside one is a QUOTE block. */
        final boolean quoted;
        /**
         * Which outermost quote the block is in, counted from 1 through the notes; 0 outside quotes. Blocks of one
         * quote share it, so "> a" and "> b" with a blank line between them are two quotes, as on GitHub.
         */
        final int quote;
        /** How many quotes the block is inside: 0 outside quotes, 2 for a quote inside a quote. */
        final int quotes;
        /**
         * For a block that is not a list item but belongs to one (an item's second paragraph, or a fence or heading
         * indented under it): that item's depth plus one, so it can sit under the item's text; else 0.
         */
        final int item;
        /**
         * For a block in a quote that is indented under a list item outside the quote: that item's depth plus one, so
         * the quote (its bar and all its blocks) sits under the item's text; else 0.
         */
        final int quoteItem;
        /** The block's text without its markup; "\n" for each line break inside it. */
        final String text;
        /** Style ranges over text, outermost after the ranges inside them. */
        final List<Span> spans;
        /** The quotes the block is in, outermost first, each numbered within the quote around it. */
        private final int[] path;
        Block(Kind kind, int level, String marker, String written, String text, List<Span> spans, int item) {
            this(kind, level, marker, written, text, spans, item, new int[0], 0);
        }
        private Block(Kind kind, int level, String marker, String written, String text, List<Span> spans, int item, int[] path, int quoteItem) {
            this.kind = kind; this.level = level; this.marker = marker; this.written = written; this.text = text;
            this.spans = Collections.unmodifiableList(spans); this.item = item; this.path = path; this.quoteItem = quoteItem;
            this.quoted = path.length > 0; this.quote = quoted ? path[0] : 0; this.quotes = path.length;
        }
        /**
         * This block as it reads inside the given quote, numbered within the quote around that one; item is
         * the depth plus one of the list item outside the quote that the quote is indented under, or 0.
         */
        Block inQuote(int quote, int item) {
            int[] outer = new int[path.length + 1];
            outer[0] = quote;
            System.arraycopy(path, 0, outer, 1, path.length);
            return new Block(kind == Kind.PARAGRAPH ? Kind.QUOTE : kind, level, marker, written, text, spans, this.item, outer, item);
        }
        /**
         * Whether this block and other are inside the same quote at the given depth (0 for the outermost), and so in
         * the same quotes around it: two blocks of one quote at that depth, which its bar joins.
         */
        boolean sameQuote(Block other, int depth) {
            if (other == null || depth >= path.length || depth >= other.path.length) return false;
            for (int i = 0; i <= depth; i++) if (path[i] != other.path[i]) return false;
            return true;
        }
    }

    private ReleaseNotes() {}

    /** A space or a tab, the only blanks Markdown counts in indents and after markers. */
    private static final String BLANK = "[ \\011]", TEXT = "([^ \\011].*)";
    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:" + BLANK + "+(.*?))?(?:" + BLANK + "+#+)?" + BLANK + "*$");
    private static final Pattern SETEXT = Pattern.compile("^ {0,3}(=+|-+)" + BLANK + "*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:" + BLANK + "*\\1){2,}" + BLANK + "*$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>" + BLANK + "?(.*)$");
    private static final Pattern BULLET_ITEM = Pattern.compile("^(" + BLANK + "*)([-*+])(" + BLANK + "+)" + TEXT + "$");
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^(" + BLANK + "*)([0-9]{1,9}[.)])(" + BLANK + "+)" + TEXT + "$");
    private static final Pattern AUTOLINK = Pattern.compile("(?:https?://|mailto:)[^\\s<>]+");

    /**
     * The notes cut to at most max characters (before the ellipsis): at a line break in the last fifth before the
     * limit, else at the last word break past halfway, else at the limit. A line break further back would drop more of
     * the notes than a cut between words.
     */
    static String clip(String markdown, int max) {
        String text = normalize(markdown).trim();
        if (text.length() <= max) return text;
        int line = text.lastIndexOf('\n', max);
        int cut = line >= max - max / 5 ? line : Math.max(line, text.lastIndexOf(' ', max));
        if (cut < max / 2) cut = max;
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
        return text.substring(0, cut).trim() + "…";
    }

    /** The notes' blocks, in order. Never throws for any text; null reads as no notes. */
    static List<Block> parse(String markdown) {
        Parser parser = new Parser(0);
        for (String line : normalize(markdown).split("\n", -1)) parser.line(line);
        parser.finish();
        return Collections.unmodifiableList(parser.out);
    }

    /** The blocks as plain text, one block per line, list items indented by two spaces a level with their marker. */
    static String plainText(List<Block> blocks) {
        StringBuilder out = new StringBuilder();
        for (Block block : blocks) {
            if (out.length() > 0) out.append('\n');
            if (!block.marker.isEmpty()) {
                for (int i = 0; i < block.level; i++) out.append("  ");
                out.append(block.marker).append(' ');
            }
            out.append(block.text);
        }
        return out.toString();
    }

    private static String normalize(String markdown) {
        return markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Line by line: one block is open at a time, and a blank line or a new block's first line closes it. A quote's
     * lines, without their ">", go to a parser of their own, so lists, headings and code inside it are read as such.
     */
    private static final class Parser {
        final List<Block> out = new ArrayList<>();
        /** How many quotes this parser's lines are inside. */
        final int depth;
        /** The open block's kind, or null; its level, markers, the item it belongs to (Block.item) and raw lines. */
        Kind open; int openLevel, openItem; String openMarker = "", openWritten = ""; final StringBuilder raw = new StringBuilder();
        /** The list items above this one, outermost first. */
        final List<Level> levels = new ArrayList<>();
        /**
         * For an open paragraph after a list item: the item's content column, past which a line still belongs to the
         * item; -1 for a paragraph outside a list.
         */
        int paragraphContent = -1;
        /** Inside a fence: its character and length, and how far its opening line was indented. */
        char fenceChar; int fenceLength, fenceIndent; boolean fenced;
        /** The fence has had a line, so the next one starts after a break. */
        boolean codeStarted;
        /** The open quote's own parser, or null; how many quotes this parser has closed; how far its ">" was indented. */
        Parser quote; int quotes, quoteIndent;
        /** The open quote's Block.quoteItem: the list item it is indented under, as Block.item counts it. */
        int quoteItem;

        Parser(int depth) { this.depth = depth; }

        void line(String line) {
            if (fenced) { fenceLine(line); return; }
            Matcher m;
            if (quote != null) {
                if ((m = QUOTE.matcher(line)).matches()) { quote.line(m.group(1)); return; }
                // A line without ">" carries on the quote's paragraph, unless it starts a block of its own.
                if (!line.trim().isEmpty() && !startsBlock(line) && !leavesQuote(line) && quote.lazy(line)) return;
                endQuote();
            }
            if (depth >= MAX_QUOTES) while ((m = QUOTE.matcher(line)).matches()) line = m.group(1);
            if (line.trim().isEmpty()) { close(); return; }
            if ((m = FENCE.matcher(line)).matches() && fenceOpens(m)) {
                close(); keepLevels(leadingSpaces(line));
                fenced = true; fenceChar = m.group(2).charAt(0); fenceLength = m.group(2).length(); fenceIndent = m.group(1).length();
                start(Kind.CODE, 0, "", "");
                return;
            }
            if ((m = HEADING.matcher(line)).matches()) {
                close(); keepLevels(leadingSpaces(line));
                String title = m.group(2) == null ? "" : m.group(2);
                add(Kind.HEADING, m.group(1).length(), "", "", title, itemDepth());
                return;
            }
            if (open == Kind.PARAGRAPH && (m = SETEXT.matcher(line)).matches()) {
                String title = raw.toString(); raw.setLength(0); open = null;
                add(Kind.HEADING, m.group(1).charAt(0) == '=' ? 1 : 2, "", "", title, openItem);
                return;
            }
            if (RULE.matcher(line).matches()) { close(); keepLevels(leadingSpaces(line)); out.add(new Block(Kind.RULE, 0, "", "", "", new ArrayList<Span>(), 0)); return; }
            if ((m = BULLET_ITEM.matcher(line)).matches()) { item(Kind.BULLET, m); return; }
            if ((m = NUMBERED_ITEM.matcher(line)).matches() && opensItem(m)) {
                item(Kind.NUMBERED, m);
                return;
            }
            if ((m = QUOTE.matcher(line)).matches()) {
                close(); quoteIndent = leadingSpaces(line); keepLevels(quoteIndent); quoteItem = itemDepth();
                quote = new Parser(depth + 1);
                quote.line(m.group(1));
                return;
            }
            if (open == null) {
                // After a blank line, a paragraph indented as far as a list item's text belongs to that item and
                // keeps its list open; one less indented ends the list.
                keepLevels(columns(line.substring(0, line.length() - line.replaceFirst("^" + BLANK + "+", "").length())));
                paragraphContent = levels.isEmpty() ? -1 : levels.get(levels.size() - 1).content;
                start(Kind.PARAGRAPH, 0, "", "");
            }
            // A line that follows a paragraph or list item continues it, after a line break.
            append(line);
        }

        /**
         * A block starting at this indent ends the list items whose text it is not indented as far as; the items it
         * is indented under stay open, so a fence, quote or heading inside an item doesn't restart the list's count.
         */
        private void keepLevels(int indent) {
            while (!levels.isEmpty() && indent < levels.get(levels.size() - 1).content) levels.remove(levels.size() - 1);
        }

        /** Block.item for a block that starts now: the depth of the innermost open list item plus one, or 0. */
        private int itemDepth() { return levels.isEmpty() ? 0 : Math.min(MAX_DEPTH, levels.size() - 1) + 1; }

        /**
         * Whether a numbered line (a NUMBERED_ITEM match) starts an item here rather than carrying on the open
         * paragraph. As on GitHub, a numbered item breaks into a paragraph only when it starts a new list at 1, so
         * prose wrapped onto a line such as "80. percent" stays in its paragraph. That holds for a list item's own
         * paragraph too: a line indented as far as the item's text ("2025. Both can update." wrapped inside a bullet)
         * is part of it unless it starts at 1, while one left of the item's text is the next item of its list, or of a
         * list around it. A paragraph that belongs to a numbered item ends, though, at the next item of that list
         * ("2." after the item's indented paragraph).
         */
        private boolean opensItem(Matcher m) {
            if (open == null || startsAtOne(m.group(2))) return true;
            int indent = columns(m.group(1));
            if (open == Kind.BULLET || open == Kind.NUMBERED) return indent < levels.get(levels.size() - 1).content;
            if (open != Kind.PARAGRAPH) return true;
            String written = m.group(2);
            return paragraphContent >= 0 && indent < paragraphContent && continuesNumbered(indent, written.charAt(written.length() - 1));
        }

        /**
         * Whether a numbered line without ">" is left of the open quote's ">": it then starts an item outside the
         * quote (the next item of the list the quote is in) instead of carrying on the quote's last paragraph.
         */
        private boolean leavesQuote(String line) {
            Matcher m = NUMBERED_ITEM.matcher(line);
            return m.matches() && columns(m.group(1)) < quoteIndent;
        }

        /** Whether an item at this indent would be the next of an open numbered list with this delimiter (as item() places it). */
        private boolean continuesNumbered(int indent, char delimiter) {
            for (int i = levels.size() - 1; i >= 0; i--) {
                Level level = levels.get(i);
                if (indent >= level.indent + 2) return false;
                if (indent >= level.indent) return level.kind == Kind.NUMBERED && level.delimiter == delimiter;
            }
            return false;
        }

        /**
         * Whether a line not marked with ">" starts a block, which ends a quote above it. A numbered line is left to
         * lazy(), which asks the quote's innermost block as it would a line marked with ">".
         */
        private static boolean startsBlock(String line) {
            Matcher m;
            return (m = FENCE.matcher(line)).matches() && fenceOpens(m) || HEADING.matcher(line).matches() || RULE.matcher(line).matches()
                    || BULLET_ITEM.matcher(line).matches();
        }

        /**
         * A line that carries on the open paragraph or list item, inside quotes as deep as they go; false if none is
         * open, or if the line is a numbered item that would start an item there.
         */
        boolean lazy(String line) {
            if (quote != null) return !leavesQuote(line) && quote.lazy(line);
            if (fenced || open == null || open == Kind.CODE) return false;
            Matcher m = NUMBERED_ITEM.matcher(line);
            if (m.matches() && opensItem(m)) return false;
            append(line);
            return true;
        }

        private void endQuote() {
            quote.finish();
            int id = ++quotes;
            for (Block block : quote.out) out.add(block.inQuote(id, quoteItem));
            quote = null;
        }

        private void fenceLine(String line) {
            String trimmed = line.trim();
            int run = 0;
            while (run < trimmed.length() && trimmed.charAt(run) == fenceChar) run++;
            if (leadingSpaces(line) <= 3 && run >= fenceLength && run == trimmed.length()) {
                fenced = false; closeCode(); return;
            }
            int strip = Math.min(fenceIndent, leadingSpaces(line));
            if (codeStarted) raw.append('\n');
            codeStarted = true;
            raw.append(line.substring(strip));
        }

        /**
         * A list item: groups 1 to 4 of BULLET_ITEM or NUMBERED_ITEM are its indent, marker, the blanks after the
         * marker and its text. A numbered item that continues its list shows the next number, as GitHub does.
         */
        private void item(Kind kind, Matcher m) {
            close();
            String indentText = m.group(1), written = m.group(2);
            int indent = columns(indentText), markerEnd = columns(indentText + written);
            int content = columns(indentText + written + m.group(3));
            // Text five or more columns past the marker would be indented code on GitHub; the item's text starts one
            // column after its marker then.
            if (content - markerEnd > 4) content = markerEnd + 1;
            while (!levels.isEmpty() && indent < levels.get(levels.size() - 1).indent) levels.remove(levels.size() - 1);
            // An item two or more columns further in than the one above nests under it; less is the same level.
            boolean fresh = levels.isEmpty() || indent >= levels.get(levels.size() - 1).indent + 2;
            if (fresh) levels.add(new Level(indent));
            Level level = levels.get(levels.size() - 1);
            level.content = content;
            String marker = BULLET;
            if (kind == Kind.NUMBERED) {
                char delimiter = written.charAt(written.length() - 1);
                // A new list, or a change of kind or of "." and ")", starts counting from the number written.
                if (fresh || level.kind != Kind.NUMBERED || level.delimiter != delimiter)
                    level.next = Long.parseLong(written.substring(0, written.length() - 1));
                level.delimiter = delimiter;
                marker = level.next++ + String.valueOf(delimiter);
            }
            level.kind = kind;
            start(kind, Math.min(MAX_DEPTH, levels.size() - 1), marker, written);
            append(m.group(4));
        }

        private void start(Kind kind, int level, String marker, String written) {
            open = kind; openLevel = level; openMarker = marker; openWritten = written; raw.setLength(0);
            openItem = kind == Kind.BULLET || kind == Kind.NUMBERED ? 0 : itemDepth();
        }

        /**
         * One more line of the open block. A hard break (two spaces, or a backslash, at the end of the line before)
         * becomes a plain break; a backslash only counts as one when a line follows it, so a block's last one stays.
         */
        private void append(String line) {
            if (raw.length() > 0) {
                if (trailingBackslashes(raw) % 2 == 1) {
                    raw.setLength(raw.length() - 1);
                    while (raw.length() > 0 && (raw.charAt(raw.length() - 1) == ' ' || raw.charAt(raw.length() - 1) == '\t')) raw.setLength(raw.length() - 1);
                }
                raw.append('\n');
            }
            raw.append(line.trim());
        }

        private void close() {
            if (open == null) return;
            if (open == Kind.CODE) { closeCode(); return; }
            add(open, openLevel, openMarker, openWritten, raw.toString(), openItem);
            open = null; raw.setLength(0);
        }

        private void closeCode() {
            out.add(new Block(Kind.CODE, 0, "", "", raw.toString(), new ArrayList<Span>(), openItem));
            open = null; raw.setLength(0); codeStarted = false;
        }

        /** An unclosed fence runs to the end, as on GitHub, and so does an open quote. */
        void finish() {
            if (quote != null) endQuote();
            fenced = false; close();
        }

        private void add(Kind kind, int level, String marker, String written, String source, int item) {
            StringBuilder text = new StringBuilder(); List<Span> spans = new ArrayList<>();
            new Inline(source, text, spans).run(0, source.length());
            out.add(new Block(kind, level, marker, written, text.toString(), spans, item));
        }
    }

    /** One level of open lists: its items' indent and text column, and for a numbered list its "." or ")" and next number. */
    private static final class Level {
        final int indent; int content; Kind kind; char delimiter; long next;
        Level(int indent) { this.indent = indent; }
    }

    /** A fence line opens a fence unless it is a backtick fence with a backtick after it (an inline code span). */
    private static boolean fenceOpens(Matcher fence) { return !(fence.group(2).charAt(0) == '`' && fence.group(3).indexOf('`') >= 0); }

    /** Inline markup over one block's source, appended to text with its style ranges. */
    private static final class Inline {
        final String s; final StringBuilder text; final List<Span> spans;
        Inline(String s, StringBuilder text, List<Span> spans) { this.s = s; this.text = text; this.spans = spans; }

        void run(int from, int to) {
            int i = from;
            while (i < to) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < to && punctuation(s.charAt(i + 1))) { text.append(s.charAt(i + 1)); i += 2; continue; }
                if (c == '`') { i = code(i, to); continue; }
                if (c == '!' && i + 1 < to && s.charAt(i + 1) == '[') {
                    int next = link(i + 1, to, true);
                    if (next > 0) { i = next; continue; }
                }
                if (c == '[') {
                    int next = link(i, to, false);
                    if (next > 0) { i = next; continue; }
                }
                if (c == '<') {
                    int close = s.indexOf('>', i + 1);
                    if (close > 0 && close < to && AUTOLINK.matcher(s.substring(i + 1, close)).matches()) {
                        text.append(s, i + 1, close); i = close + 1; continue;
                    }
                }
                if (bareLinkStart(i)) {
                    int end = bareLinkEnd(i, to);
                    if (end > 0) { text.append(s, i, end); i = end; continue; }
                }
                if (c == '*' || c == '_' || c == '~') { i = emphasis(i, to, c); continue; }
                text.append(c); i++;
            }
        }

        /** Where GitHub starts a bare address: at the start, after a blank, or after one of *, _, ~ and (. */
        private boolean bareLinkStart(int i) {
            char c = s.charAt(i);
            if (c != 'h' && c != 'm') return false;
            return i == 0 || Character.isWhitespace(s.charAt(i - 1)) || "*_~(".indexOf(s.charAt(i - 1)) >= 0;
        }

        /**
         * The end of the bare address at i, kept exactly as written (no emphasis inside it), or -1 when none starts
         * there. As on GitHub, trailing punctuation and an unmatched closing parenthesis are not part of it.
         */
        private int bareLinkEnd(int i, int to) {
            Matcher m = AUTOLINK.matcher(s).region(i, to);
            if (!m.lookingAt()) return -1;
            int end = m.end();
            while (end > i) {
                char last = s.charAt(end - 1);
                if ("?!.,:*_~'\"".indexOf(last) >= 0) { end--; continue; }
                if (last == ')' && count(i, end, ')') > count(i, end, '(')) { end--; continue; }
                break;
            }
            int scheme = s.startsWith("mailto:", i) ? i + 7 : s.indexOf("://", i) + 3;
            return end > scheme ? end : -1;
        }

        private int count(int from, int to, char c) { int n = 0; for (int j = from; j < to; j++) if (s.charAt(j) == c) n++; return n; }

        /** A code span between equal runs of backticks, or the backticks as they are when no run closes it. */
        private int code(int i, int to) {
            int n = run(i, to, '`');
            int j = i + n;
            while (j < to) {
                if (s.charAt(j) == '`') {
                    int m = run(j, to, '`');
                    if (m == n) {
                        String code = s.substring(i + n, j).replace('\n', ' ');
                        if (code.length() >= 2 && code.charAt(0) == ' ' && code.charAt(code.length() - 1) == ' ' && !code.trim().isEmpty())
                            code = code.substring(1, code.length() - 1);
                        styled(Style.CODE, code);
                        return j + n;
                    }
                    j += m;
                } else j++;
            }
            text.append(s, i, i + n);
            return i + n;
        }

        /**
         * [label](address) or, with image, ![alt](address): the label, then " (address)" unless the label already
         * is the address. Returns the index after it, or -1 when this is not a link (the bracket is then text).
         */
        private int link(int open, int to, boolean image) {
            int close = bracketEnd(open, to);
            if (close < 0 || close + 1 >= to || s.charAt(close + 1) != '(') return -1;
            int end = -1, depth = 0;
            for (int j = close + 2; j < to; j++) {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < to) { j++; continue; }
                if (Character.isWhitespace(c)) return -1;
                if (c == '(') depth++;
                else if (c == ')') { if (depth == 0) { end = j; break; } depth--; }
            }
            if (end < 0) return -1;
            String address = s.substring(close + 2, end);
            if (address.length() >= 2 && address.charAt(0) == '<' && address.charAt(address.length() - 1) == '>')
                address = address.substring(1, address.length() - 1);
            int labelStart = text.length();
            run(open + 1, close);
            String label = text.substring(labelStart);
            if (!address.isEmpty() && !address.equals(label.trim())) {
                if (text.length() > labelStart) text.append(' ');
                text.append('(');
                styled(Style.URL, address);
                text.append(')');
            } else if (label.isEmpty()) {
                // Neither label nor address: keep what was written.
                text.append(s, image ? open - 1 : open, end + 1);
            }
            return end + 1;
        }

        /** The ']' that closes the '[' at open, past nested brackets, escapes and code spans; -1 when none does. */
        private int bracketEnd(int open, int to) {
            int depth = 0;
            for (int j = open + 1; j < to; j++) {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < to) { j++; continue; }
                if (c == '`') { int skip = codeEnd(j, to); if (skip > 0) { j = skip - 1; continue; } }
                if (c == '[') depth++;
                else if (c == ']') { if (depth == 0) return j; depth--; }
            }
            return -1;
        }

        /**
         * Bold (** or __), italic (* or _) or strikethrough (~~) when a matching run closes it in this block;
         * otherwise the run is text. _ only opens and closes at word edges, so snake_case stays as written.
         */
        private int emphasis(int i, int to, char c) {
            int n = run(i, to, c);
            boolean underscore = c == '_';
            boolean canOpen = i + n < to && !Character.isWhitespace(s.charAt(i + n))
                    && !(underscore && i > 0 && Character.isLetterOrDigit(s.charAt(i - 1)));
            if (canOpen) {
                if (c == '~') {
                    if (n == 2) {
                        int close = closer(i + 2, to, c, 2, false);
                        if (close > 0) { wrap(Style.STRIKE, i + 2, close); return close + 2; }
                    }
                } else {
                    if (n >= 2) {
                        int close = closer(i + 2, to, c, 2, underscore);
                        if (close > 0) { wrap(Style.BOLD, i + 2, close); return close + 2; }
                    }
                    if (n == 1 || n == 3) {
                        int close = closer(i + 1, to, c, 1, underscore);
                        if (close > 0) { wrap(Style.ITALIC, i + 1, close); return close + 1; }
                    }
                }
            }
            text.append(s, i, i + n);
            return i + n;
        }

        /**
         * Where a run of `size` delimiters closes an opener whose content starts at from: a run of at least that
         * many (only exactly one for size 1), after a non-space, and for _ at a word edge. Content must not be
         * empty. Skips escapes and code spans. For a longer run it closes with the run's last delimiters.
         */
        private int closer(int from, int to, char c, int size, boolean underscore) {
            for (int j = from; j < to; j++) {
                char ch = s.charAt(j);
                if (ch == '\\' && j + 1 < to) { j++; continue; }
                if (ch == '`') { int skip = codeEnd(j, to); if (skip > 0) { j = skip - 1; continue; } }
                if (ch != c) continue;
                int m = run(j, to, c);
                boolean fits = size == 1 ? m == 1 || m == 3 : m >= size;
                int at = size == 1 ? j + m - 1 : j + m - size;
                boolean after = at > from && !Character.isWhitespace(s.charAt(j - 1))
                        && !(underscore && j + m < to && Character.isLetterOrDigit(s.charAt(j + m)));
                if (fits && after) return at;
                j += m - 1;
            }
            return -1;
        }

        /** The index after the code span starting at the backtick at i, or -1 when no equal run closes it. */
        private int codeEnd(int i, int to) {
            int n = run(i, to, '`');
            for (int j = i + n; j < to; ) {
                if (s.charAt(j) == '`') { int m = run(j, to, '`'); if (m == n) return j + n; j += m; } else j++;
            }
            return -1;
        }

        private void wrap(Style style, int from, int to) {
            int start = text.length();
            run(from, to);
            if (text.length() > start) spans.add(new Span(style, start, text.length()));
        }

        private void styled(Style style, String value) {
            int start = text.length();
            text.append(value);
            if (text.length() > start) spans.add(new Span(style, start, text.length()));
        }

        private int run(int i, int to, char c) { int j = i; while (j < to && s.charAt(j) == c) j++; return j - i; }
    }

    private static boolean punctuation(char c) { return c < 128 && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0; }

    /** A numbered item's marker ("1.", "01)") that starts its list at 1. */
    private static boolean startsAtOne(String marker) { return Integer.parseInt(marker.substring(0, marker.length() - 1)) == 1; }

    private static int trailingBackslashes(CharSequence text) {
        int n = 0;
        while (n < text.length() && text.charAt(text.length() - 1 - n) == '\\') n++;
        return n;
    }

    private static int leadingSpaces(String line) { int i = 0; while (i < line.length() && line.charAt(i) == ' ') i++; return i; }

    private static int columns(String indent) {
        int width = 0;
        for (int i = 0; i < indent.length(); i++) width = indent.charAt(i) == '\t' ? width + 4 - width % 4 : width + 1;
        return width;
    }
}
