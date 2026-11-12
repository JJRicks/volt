package com.jjricks.volt;

import java.util.List;
import java.util.Random;

/**
 * The update dialog's release notes (ReleaseNotes): GitHub's Markdown read into blocks and style ranges, with
 * anything it doesn't read kept as text, and every letter and digit of the notes still there. Synthetic notes only.
 */
public final class ReleaseNotesTest {
    private static int checks;

    public static void main(String[] args) {
        blocks(); inline(); fallbacks(); release(); clip(); nothingLost();
        System.out.println("ReleaseNotesTest: " + checks + " checks passed");
    }

    private static void blocks() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("# Volt 9.9\n\n## Highlights\n\n- One\n- Two\n  - Nested\n      - Deeper\n\n1. First\n2) Second\n\nA paragraph\nwith a break.\n\nSecond paragraph.");
        check(b.size() == 10, "ten blocks: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.HEADING, 1, "", "Volt 9.9");
        block(b.get(1), ReleaseNotes.Kind.HEADING, 2, "", "Highlights");
        block(b.get(2), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "One");
        block(b.get(3), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "Two");
        block(b.get(4), ReleaseNotes.Kind.BULLET, 1, ReleaseNotes.BULLET, "Nested");
        block(b.get(5), ReleaseNotes.Kind.BULLET, 2, ReleaseNotes.BULLET, "Deeper");
        block(b.get(6), ReleaseNotes.Kind.NUMBERED, 0, "1.", "First");
        block(b.get(7), ReleaseNotes.Kind.NUMBERED, 0, "2)", "Second");
        block(b.get(8), ReleaseNotes.Kind.PARAGRAPH, 0, "", "A paragraph\nwith a break.");
        block(b.get(9), ReleaseNotes.Kind.PARAGRAPH, 0, "", "Second paragraph.");

        b = ReleaseNotes.parse("Title\n=====\nSub\n---\ntext\n\n***\n> quoted\n> more\n\n```java\n**not bold**\n\n  indented\n```\nafter\n### Closing ###");
        block(b.get(0), ReleaseNotes.Kind.HEADING, 1, "", "Title");
        block(b.get(1), ReleaseNotes.Kind.HEADING, 2, "", "Sub");
        block(b.get(2), ReleaseNotes.Kind.PARAGRAPH, 0, "", "text");
        check(b.get(3).kind == ReleaseNotes.Kind.RULE, "a rule");
        block(b.get(4), ReleaseNotes.Kind.QUOTE, 0, "", "quoted\nmore");
        block(b.get(5), ReleaseNotes.Kind.CODE, 0, "", "**not bold**\n\n  indented");
        check(b.get(5).spans.isEmpty(), "code is never styled inside");
        block(b.get(6), ReleaseNotes.Kind.PARAGRAPH, 0, "", "after");
        block(b.get(7), ReleaseNotes.Kind.HEADING, 3, "", "Closing");

        b = ReleaseNotes.parse("- item\ncontinues here\n\n- loose item\n\n  its second paragraph\n\n```\nunclosed fence");
        block(b.get(0), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "item\ncontinues here");
        block(b.get(1), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "loose item");
        block(b.get(2), ReleaseNotes.Kind.PARAGRAPH, 0, "", "its second paragraph");
        block(b.get(3), ReleaseNotes.Kind.CODE, 0, "", "unclosed fence");
        check(ReleaseNotes.parse("line one  \nline two\\\nthree").get(0).text.equals("line one\nline two\nthree"), "hard breaks are breaks");
        check(ReleaseNotes.parse("* star\n+ plus").get(1).marker.equals(ReleaseNotes.BULLET), "every bullet character");
        List<ReleaseNotes.Block> tabs = ReleaseNotes.parse("-\tTabbed\n\t1.\tUnder it\n\t# not a heading");
        block(tabs.get(0), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "Tabbed");
        block(tabs.get(1), ReleaseNotes.Kind.NUMBERED, 1, "1.", "Under it\n# not a heading");
        check(ReleaseNotes.parse("\r\n\r\n").isEmpty() && ReleaseNotes.parse(null).isEmpty() && ReleaseNotes.parse("").isEmpty(), "no notes");
    }

    private static void inline() {
        ReleaseNotes.Block b = one("**Bold** and *italic*, __strong__ _em_, ~~gone~~ and `code`.");
        check(b.text.equals("Bold and italic, strong em, gone and code."), "inline text: " + b.text);
        span(b, ReleaseNotes.Style.BOLD, "Bold"); span(b, ReleaseNotes.Style.ITALIC, "italic");
        span(b, ReleaseNotes.Style.BOLD, "strong"); span(b, ReleaseNotes.Style.ITALIC, "em");
        span(b, ReleaseNotes.Style.STRIKE, "gone"); span(b, ReleaseNotes.Style.CODE, "code");

        b = one("***Both*** and **bold with *italic* inside**");
        check(b.text.equals("Both and bold with italic inside"), "nested: " + b.text);
        span(b, ReleaseNotes.Style.BOLD, "Both"); span(b, ReleaseNotes.Style.ITALIC, "Both");
        span(b, ReleaseNotes.Style.BOLD, "bold with italic inside"); span(b, ReleaseNotes.Style.ITALIC, "italic");

        b = one("See the [1.5.0 release notes](https://github.com/JJRicks/volt/releases/tag/v1.5.0) first.");
        check(b.text.equals("See the 1.5.0 release notes (https://github.com/JJRicks/volt/releases/tag/v1.5.0) first."), "link: " + b.text);
        span(b, ReleaseNotes.Style.URL, "https://github.com/JJRicks/volt/releases/tag/v1.5.0");
        b = one("[**Download**](https://example.com/a_(b)) and [https://example.com](https://example.com) and <https://example.com/x>");
        check(b.text.equals("Download (https://example.com/a_(b)) and https://example.com and https://example.com/x"), "links: " + b.text);
        span(b, ReleaseNotes.Style.BOLD, "Download");
        b = one("![Screenshot](https://example.com/s.png) ``a `tick` here`` \\*not italic\\*");
        check(b.text.equals("Screenshot (https://example.com/s.png) a `tick` here *not italic*"), "image, double ticks, escapes: " + b.text);
        span(b, ReleaseNotes.Style.CODE, "a `tick` here");
        b = one("**`volt-1.13.1.apk`** over");
        span(b, ReleaseNotes.Style.CODE, "volt-1.13.1.apk"); span(b, ReleaseNotes.Style.BOLD, "volt-1.13.1.apk");
    }

    private static void fallbacks() {
        String[] same = {"**not closed", "2 * 3 * 4", "snake_case_name and file_name.txt", "| a | b |\n|---|---|",
                "<br> and <b>html</b>", "[ref][1] and [not a link] (here)", "`unclosed code", "~single~ tilde", "#hashtag",
                "a ** b ** c", "[spaced](not a url)", "trailing \\\\"};
        for (String text : same) {
            String shown = ReleaseNotes.plainText(ReleaseNotes.parse(text));
            check(shown.equals(text.equals("trailing \\\\") ? "trailing \\" : text), "kept as written: " + text + " -> " + shown);
        }
        check(one("snake_case_name").spans.isEmpty() && one("**not closed").spans.isEmpty(), "no style from markup that doesn't close");
        check(one("a *b* c").spans.size() == 1, "an italic word inside text");
        check(ReleaseNotes.plainText(ReleaseNotes.parse("- one\n  1. two\n\n## Done")).equals("• one\n  1. two\nDone"), "plain text form");
    }

    /** The shape the project's release notes have used: a bold lead, highlights, links and known limits. */
    private static void release() {
        String notes = "**Faster charging estimates, and a tidier Home.** The estimate learns from each charge.\n\n"
                + "**Volt 9.9.9 updates 9.9.8 in place.** Install `volt-9.9.9.apk` over your current Volt. Coming from 1.4.x? Follow the [1.5.0 release notes](https://github.com/JJRicks/volt/releases/tag/v1.5.0) first.\n\n"
                + "## Highlights\n\n"
                + "- **Charging.** **More › Charging** shows the time to full, _even_ at night.\n"
                + "- **Riding mode:** chips show **Avg 14.2 mph**, shortening where room runs out.\n\n"
                + "Full list: [CHANGELOG.md](https://github.com/JJRicks/volt/blob/v9.9.9/CHANGELOG.md)\n\n"
                + "## Known limits\n\n"
                + "- Tested on emulators only.\n";
        List<ReleaseNotes.Block> b = ReleaseNotes.parse(notes);
        check(b.size() == 8, "release blocks: " + b.size());
        check(b.get(0).kind == ReleaseNotes.Kind.PARAGRAPH && b.get(0).text.startsWith("Faster charging estimates, and a tidier Home. The"), "lead paragraph");
        span(b.get(0), ReleaseNotes.Style.BOLD, "Faster charging estimates, and a tidier Home.");
        span(b.get(1), ReleaseNotes.Style.CODE, "volt-9.9.9.apk");
        block(b.get(2), ReleaseNotes.Kind.HEADING, 2, "", "Highlights");
        check(b.get(3).kind == ReleaseNotes.Kind.BULLET && b.get(3).text.equals("Charging. More › Charging shows the time to full, even at night."), "highlight: " + b.get(3).text);
        span(b.get(3), ReleaseNotes.Style.BOLD, "More › Charging"); span(b.get(3), ReleaseNotes.Style.ITALIC, "even");
        check(b.get(5).text.equals("Full list: CHANGELOG.md (https://github.com/JJRicks/volt/blob/v9.9.9/CHANGELOG.md)"), "changelog link");
        block(b.get(6), ReleaseNotes.Kind.HEADING, 2, "", "Known limits");
        block(b.get(7), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "Tested on emulators only.");

        // The dialog gets these notes as GitHub wrote them, beside the plain text it had before.
        String reply = "{\"html_url\":\"https://github.com/JJRicks/volt/releases/tag/v9.9.9\",\"tag_name\":\"v9.9.9\",\"draft\":false,"
                + "\"prerelease\":false,\"body\":" + BackupJson.quote(notes.replace("\n", "\r\n")) + "}";
        UpdateCheck.Result r = UpdateCheck.evaluate("9.9.8", reply);
        check(r.outcome == UpdateCheck.Outcome.AVAILABLE && r.markdown.equals(notes.trim()) && r.notes.equals(UpdateCheck.plain(notes)), "the reply keeps its Markdown");
        check(UpdateCheck.Result.unreadable().markdown.isEmpty(), "no notes on an unreadable reply");
    }

    private static void clip() {
        check(ReleaseNotes.clip("  short\r\nnotes \n", 100).equals("short\nnotes"), "short notes stay whole");
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 100; i++) lines.append("- **Line ").append(i).append("** of the notes\n");
        String cut = ReleaseNotes.clip(lines.toString(), 3000 / 4);
        check(cut.length() <= 751 && cut.endsWith("of the notes…"), "cut at a line break: " + cut.substring(cut.length() - 20));
        String words = ReleaseNotes.clip("word word word word word word word word", 22);
        check(words.equals("word word word word…"), "cut at a space: " + words);
        String solid = ReleaseNotes.clip("0123456789012345678901234", 10);
        check(solid.equals("0123456789…"), "cut anywhere without a break");
        check(ReleaseNotes.clip("abcd😀efgh", 5).equals("abcd…"), "never halves a surrogate pair");
    }

    /** Random Markdown-like text: never throws, ranges stay in their block, and every letter and digit survives in order. */
    private static void nothingLost() {
        String alphabet = "ab1 2*_~`#->[]()!\\.|<:\n\n  ";
        Random random = new Random(383);
        for (int round = 0; round < 4000; round++) {
            StringBuilder text = new StringBuilder();
            int length = 1 + random.nextInt(80);
            for (int i = 0; i < length; i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            // Links (whose label may repeat their address) and fences (whose language is dropped) have cases above.
            String input = text.toString().replace("](", "] (");
            while (input.contains("```") || input.contains("~~~")) input = input.replace("```", "``").replace("~~~", "~~");
            List<ReleaseNotes.Block> blocks = ReleaseNotes.parse(input);
            for (ReleaseNotes.Block block : blocks)
                for (ReleaseNotes.Span span : block.spans)
                    check(span.start >= 0 && span.start < span.end && span.end <= block.text.length(), "range inside its block: " + input);
            String shown = ReleaseNotes.plainText(blocks);
            check(letters(shown).equals(letters(input)), "letters and digits survive: [" + input + "] -> [" + shown + "]");
        }
    }

    private static String letters(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) out.append(text.charAt(i));
        return out.toString();
    }

    private static ReleaseNotes.Block one(String text) {
        List<ReleaseNotes.Block> blocks = ReleaseNotes.parse(text);
        check(blocks.size() == 1, "one block for: " + text);
        return blocks.get(0);
    }

    private static void block(ReleaseNotes.Block b, ReleaseNotes.Kind kind, int level, String marker, String text) {
        check(b.kind == kind && b.level == level && b.marker.equals(marker) && b.text.equals(text),
                "block " + kind + "/" + level + "/" + marker + "/" + text + " was " + b.kind + "/" + b.level + "/" + b.marker + "/" + b.text);
    }

    private static void span(ReleaseNotes.Block b, ReleaseNotes.Style style, String covered) {
        boolean found = false;
        for (ReleaseNotes.Span s : b.spans) if (s.style == style && b.text.substring(s.start, s.end).equals(covered)) found = true;
        check(found, style + " over \"" + covered + "\" in \"" + b.text + "\": " + b.spans);
    }

    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
