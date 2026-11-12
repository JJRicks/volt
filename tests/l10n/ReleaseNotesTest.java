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
        blocks(); inline(); fallbacks(); asGitHubReadsIt(); numbering(); quotes(); itemParagraphs(); wrappedNumbers();
        blocksInItems(); quoteInItem(); nestedQuotes(); release(); clip();
        footer(); nothingLost();
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
        check(b.get(0).item == 0 && b.get(2).item == 1 && b.get(3).item == 0, "the second paragraph sits under its item, the fence doesn't");
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

    /** Cases where a looser reading would drop or move characters that GitHub shows. */
    private static void asGitHubReadsIt() {
        // A bare address is kept exactly as written: no emphasis inside it, and trailing punctuation is not part of it.
        String bare = "See https://example.com/_docs_/x and https://x.y/a*b*c";
        ReleaseNotes.Block b = one(bare);
        check(b.text.equals(bare) && b.spans.isEmpty(), "bare addresses stay as written: " + b.text + " " + b.spans);
        b = one("Docs (https://example.com/a_b_c_d). Also *https://x.y/a_b_* and mailto:me@example.com");
        check(b.text.equals("Docs (https://example.com/a_b_c_d). Also https://x.y/a_b_ and mailto:me@example.com"), "address edges: " + b.text);
        span(b, ReleaseNotes.Style.ITALIC, "https://x.y/a_b_");
        check(one("https://").text.equals("https://") && one("snake_https://x_y_").text.equals("snake_https://x_y_"), "no address, no change");

        // A numbered item breaks into a paragraph only when it starts at 1.
        block(one("Battery at\n80. percent"), ReleaseNotes.Kind.PARAGRAPH, 0, "", "Battery at\n80. percent");
        List<ReleaseNotes.Block> list = ReleaseNotes.parse("Steps:\n1. Open More\n2. Tap Update");
        check(list.size() == 3, "a list from 1 interrupts: " + list.size());
        block(list.get(0), ReleaseNotes.Kind.PARAGRAPH, 0, "", "Steps:");
        block(list.get(2), ReleaseNotes.Kind.NUMBERED, 0, "2.", "Tap Update");
        block(ReleaseNotes.parse("- item\n3. next").get(1), ReleaseNotes.Kind.NUMBERED, 0, "3.", "next");

        // A backslash is a hard break only when a line follows it in the same block.
        block(one("a\\\nb"), ReleaseNotes.Kind.PARAGRAPH, 0, "", "a\nb");
        check(one("C:\\").text.equals("C:\\"), "a last backslash stays: " + one("C:\\").text);
        List<ReleaseNotes.Block> ends = ReleaseNotes.parse("Settings are in C:\\\n\n- Use a backslash \\\n");
        block(ends.get(0), ReleaseNotes.Kind.PARAGRAPH, 0, "", "Settings are in C:\\");
        block(ends.get(1), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "Use a backslash \\");
        block(one("odd \\\\\\\nnext"), ReleaseNotes.Kind.PARAGRAPH, 0, "", "odd \\\nnext");
    }

    /** A numbered list counts up from its first item's number, as GitHub shows it, whatever the later items say. */
    private static void numbering() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("1. Alpha\n1. Beta\n1. Gamma");
        check(b.size() == 3, "three items: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.NUMBERED, 0, "1.", "Alpha");
        block(b.get(1), ReleaseNotes.Kind.NUMBERED, 0, "2.", "Beta");
        block(b.get(2), ReleaseNotes.Kind.NUMBERED, 0, "3.", "Gamma");
        check(b.get(2).written.equals("1."), "the number as written is kept: " + b.get(2).written);
        b = ReleaseNotes.parse("3) Third\n7) Next\n\n- bullet\n\n5. Five\n1. Six");
        block(b.get(0), ReleaseNotes.Kind.NUMBERED, 0, "3)", "Third");
        block(b.get(1), ReleaseNotes.Kind.NUMBERED, 0, "4)", "Next");
        block(b.get(3), ReleaseNotes.Kind.NUMBERED, 0, "5.", "Five");
        block(b.get(4), ReleaseNotes.Kind.NUMBERED, 0, "6.", "Six");
        // Nested lists count on their own; the outer list carries on after them.
        b = ReleaseNotes.parse("1. One\n   1. a\n   1. b\n1. Two\n   1. c");
        check(ReleaseNotes.plainText(b).equals("1. One\n  1. a\n  2. b\n2. Two\n  1. c"), "nested numbering: " + ReleaseNotes.plainText(b));
        // A change from "." to ")", or a list ended by a heading or paragraph, starts counting again.
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n1) b\n1) c")).equals("1. a\n1) b\n2) c"), "delimiter change");
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n\nText\n\n1. b\n\n# H\n1. c")).equals("1. a\nText\n1. b\nH\n1. c"), "a list ends");
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n- b\n1. c")).equals("1. a\n• b\n1. c"), "bullets between start again");
    }

    /** A quote's lines are read as blocks of their own: lists, headings and code inside it show without their marks. */
    private static void quotes() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("> - quoted item\n> - another\n>\n> # quoted heading\n> 1. one\n> 1. two\n\nafter");
        check(b.size() == 6, "quoted blocks: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "quoted item");
        block(b.get(1), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "another");
        block(b.get(2), ReleaseNotes.Kind.HEADING, 1, "", "quoted heading");
        block(b.get(3), ReleaseNotes.Kind.NUMBERED, 0, "1.", "one");
        block(b.get(4), ReleaseNotes.Kind.NUMBERED, 0, "2.", "two");
        for (int i = 0; i < 5; i++) check(b.get(i).quoted, "inside the quote: " + i);
        block(b.get(5), ReleaseNotes.Kind.PARAGRAPH, 0, "", "after");
        check(!b.get(5).quoted, "a blank line ends the quote");
        b = ReleaseNotes.parse("> - item\ncarries on\n> ## Then\n> ```\n> **code**\n> ```\n> > deeper *quote*");
        block(b.get(0), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "item\ncarries on");
        block(b.get(1), ReleaseNotes.Kind.HEADING, 2, "", "Then");
        block(b.get(2), ReleaseNotes.Kind.CODE, 0, "", "**code**");
        block(b.get(3), ReleaseNotes.Kind.QUOTE, 0, "", "deeper quote");
        span(b.get(3), ReleaseNotes.Style.ITALIC, "quote");
        check(b.size() == 4 && b.get(0).quoted && b.get(2).quoted && b.get(3).quoted, "all quoted: " + b.size());
        // A line that starts a block of its own ends the quote; prose without ">" carries the quote's paragraph on.
        b = ReleaseNotes.parse("> quoted\nlazy\n- outside");
        block(b.get(0), ReleaseNotes.Kind.QUOTE, 0, "", "quoted\nlazy");
        block(b.get(1), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "outside");
        check(b.get(0).quoted && !b.get(1).quoted, "the item is outside the quote");
        // A numbered line without ">" that could not break into the quote's paragraph carries it on, as on GitHub;
        // one that would start an item there (a "1.", or the next item of a quoted list) ends the quote.
        b = ReleaseNotes.parse("> We grew by\n80. percent");
        check(b.size() == 1, "one quote: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.QUOTE, 0, "", "We grew by\n80. percent");
        b = ReleaseNotes.parse("> quoted\n1. outside");
        check(b.size() == 2 && b.get(0).quoted && !b.get(1).quoted, "a 1. ends the quote");
        block(b.get(1), ReleaseNotes.Kind.NUMBERED, 0, "1.", "outside");
        b = ReleaseNotes.parse("> 1. one\n2. two");
        check(b.size() == 2 && b.get(0).quoted && !b.get(1).quoted, "an item after a quoted item ends the quote");
        block(b.get(1), ReleaseNotes.Kind.NUMBERED, 0, "2.", "two");
        // Each quote has its own number, shared by its blocks, so two quotes with a blank line between them stay two.
        b = ReleaseNotes.parse("> a\n\n> b");
        check(b.size() == 2 && b.get(0).quote != 0 && b.get(1).quote != 0 && b.get(0).quote != b.get(1).quote, "two quotes");
        b = ReleaseNotes.parse("> a\n>\n> b\n> > c\n>\n> > d\n\nafter");
        check(b.size() == 5 && b.get(0).quote != 0 && b.get(1).quote == b.get(0).quote && b.get(2).quote == b.get(0).quote
                && b.get(3).quote == b.get(0).quote && b.get(4).quote == 0, "one quote, nested quotes in it");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append('>');
        b = ReleaseNotes.parse(deep + " # deep\n" + deep + " text");
        check(ReleaseNotes.plainText(b).equals("deep\ntext") && b.get(0).kind == ReleaseNotes.Kind.HEADING && b.get(1).quoted,
                "quotes nested very deep: " + ReleaseNotes.plainText(b));
    }

    /** A list item's indented second paragraph keeps its list open, so the next item is an item again. */
    private static void itemParagraphs() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("1. one\n\n   continued\n2. two");
        check(b.size() == 3, "item, paragraph, item: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.NUMBERED, 0, "1.", "one");
        block(b.get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "continued");
        block(b.get(2), ReleaseNotes.Kind.NUMBERED, 0, "2.", "two");
        // The list stays open through the paragraph, so it keeps counting.
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n\n   more\n\n1. b\n   1. c\n\n      deeper\n   1. d")).equals("1. a\nmore\n2. b\n  1. c\ndeeper\n  2. d"),
                "counting through paragraphs");
        // A paragraph less indented than the item's text ends the list, and a later number can't break into it.
        block(ReleaseNotes.parse("1. one\n\n  not in it\n2. two").get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "not in it\n2. two");
        block(ReleaseNotes.parse("1. one\n\ncontinued\n2. two").get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "continued\n2. two");
        // As indented as the item's text, the line is still the paragraph's.
        block(ReleaseNotes.parse("1. one\n\n   continued\n   2. two").get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "continued\n2. two");
        // Only the next item of an open numbered list ends the item's paragraph; any other number would start a new
        // list, which only "1." can do inside a paragraph, so the line stays prose.
        block(ReleaseNotes.parse("- one\n\n  continued\n2. two").get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "continued\n2. two");
        b = ReleaseNotes.parse("- a\n\n  We grew by\n80. percent this year");
        check(b.size() == 2, "bullet, paragraph: " + b.size());
        block(b.get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "We grew by\n80. percent this year");
        block(ReleaseNotes.parse("1. one\n\n   extra\n2) two").get(1), ReleaseNotes.Kind.PARAGRAPH, 0, "", "extra\n2) two");
        block(ReleaseNotes.parse("1. one\n   - sub\n\n     extra\n2. two").get(3), ReleaseNotes.Kind.NUMBERED, 0, "2.", "two");
        block(ReleaseNotes.parse("- one\n   1. sub\n\n      extra\n   2. two").get(3), ReleaseNotes.Kind.NUMBERED, 1, "2.", "two");
        block(ReleaseNotes.parse("- one\n\n  continued\n1. new").get(2), ReleaseNotes.Kind.NUMBERED, 0, "1.", "new");
    }

    /**
     * Prose wrapped inside a list item onto a line that starts with a number and a full stop stays prose, as on
     * GitHub: only "1." starts a list inside the item's paragraph, so the years below keep the numbers written.
     */
    private static void wrappedNumbers() {
        String years = "- The beta began in\n  2021. The stable release followed in\n  2025. Both can update.";
        List<ReleaseNotes.Block> b = ReleaseNotes.parse(years);
        check(b.size() == 1, "one bullet: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "The beta began in\n2021. The stable release followed in\n2025. Both can update.");
        check(ReleaseNotes.plainText(b).contains("2021.") && ReleaseNotes.plainText(b).contains("2025.") && !ReleaseNotes.plainText(b).contains("2022"),
                "the years as written: " + ReleaseNotes.plainText(b));
        // A numbered item's own paragraph, and the same text inside a quote (with or without its ">"), read alike.
        block(one("1. Released in\n   2025. Update now."), ReleaseNotes.Kind.NUMBERED, 0, "1.", "Released in\n2025. Update now.");
        block(one("3) Released in\n   2025) Update now."), ReleaseNotes.Kind.NUMBERED, 0, "3)", "Released in\n2025) Update now.");
        block(one("> - The beta began in\n>   2021. The stable release"), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "The beta began in\n2021. The stable release");
        block(one("> - The beta began in\n  2021. The stable release"), ReleaseNotes.Kind.BULLET, 0, ReleaseNotes.BULLET, "The beta began in\n2021. The stable release");
        // A number other than 1 under an item's text would start a new list there, which it can't inside a paragraph.
        b = ReleaseNotes.parse("1. a\n   2. still a\n2. b");
        check(b.size() == 2, "item, item: " + b.size());
        block(b.get(0), ReleaseNotes.Kind.NUMBERED, 0, "1.", "a\n2. still a");
        block(b.get(1), ReleaseNotes.Kind.NUMBERED, 0, "2.", "b");
        // "1." still starts a list inside the item, and its next items follow it; left of the item's text, a number
        // is the next item of the list it lines up with.
        b = ReleaseNotes.parse("- Steps:\n  1. Open More\n  2. Tap Update\n- Done");
        check(ReleaseNotes.plainText(b).equals("• Steps:\n  1. Open More\n  2. Tap Update\n• Done"), "a list inside an item: " + ReleaseNotes.plainText(b));
        b = ReleaseNotes.parse("1. One\n   1. a\n   7. b\n9. Two");
        check(ReleaseNotes.plainText(b).equals("1. One\n  1. a\n  2. b\n2. Two"), "items left of the text: " + ReleaseNotes.plainText(b));
        block(ReleaseNotes.parse("- item\n  - sub\n  3. next").get(2), ReleaseNotes.Kind.NUMBERED, 1, "3.", "next");
    }

    /** A fence, quote, heading or rule indented under a list item belongs to the item: its list keeps counting. */
    private static void blocksInItems() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("1. Download\n1. Install it:\n   ```\n   adb install volt.apk\n   ```\n1. Open Volt");
        check(ReleaseNotes.plainText(b).equals("1. Download\n2. Install it:\nadb install volt.apk\n3. Open Volt"), "count past a fence: " + ReleaseNotes.plainText(b));
        check(b.get(2).kind == ReleaseNotes.Kind.CODE && b.get(2).item == 1, "the fence sits under its item");
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n\n   ```\n   x\n   ```\n\n1. b\n   ## Then\n1. c\n   > note\n\n1. d\n   ***\n1. e"))
                .equals("1. a\nx\n2. b\nThen\n3. c\nnote\n4. d\n\n5. e"), "count past a fence, heading, quote and rule");
        b = ReleaseNotes.parse("1. a\n   ## Then\n1. b");
        check(b.get(1).kind == ReleaseNotes.Kind.HEADING && b.get(1).item == 1, "the heading sits under its item");
        // Indented under an outer item but not the inner one, the block ends the inner list only.
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n   1. sub\n   1. sub\n   ```\n   x\n   ```\n   1. again\n1. b"))
                .equals("1. a\n  1. sub\n  2. sub\nx\n  1. again\n2. b"), "a block ends the list it isn't under");
        // Not indented under the item, the block ends the list and the next "1." starts a new one.
        check(ReleaseNotes.plainText(ReleaseNotes.parse("1. a\n```\nx\n```\n1. b\n> q\n\n1. c\n# H\n1. d"))
                .equals("1. a\nx\n1. b\nq\n1. c\nH\n1. d"), "blocks outside the item end the list");
        // An item's further paragraphs sit under its text, at its own depth.
        b = ReleaseNotes.parse("1. one\n\n   second paragraph\n\n2. two\n   - sub\n\n     under sub\n\nafter");
        check(b.get(1).item == 1 && b.get(4).item == 2 && b.get(5).item == 0, "items of paragraphs: " + b.get(1).item + b.get(4).item + b.get(5).item);
        for (ReleaseNotes.Block block : b) check(block.marker.isEmpty() || block.item == 0, "an item belongs to no other item");
    }

    /** A numbered item holding a quote: the next item of its list ends the quote and is drawn as an item. */
    private static void quoteInItem() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("1. First item\n   > A note about it\n2. Next item");
        check(b.size() == 3, "item, quote, item: " + b.size());
        block(b.get(1), ReleaseNotes.Kind.QUOTE, 0, "", "A note about it");
        block(b.get(2), ReleaseNotes.Kind.NUMBERED, 0, "2.", "Next item");
        check(b.get(1).quoted && !b.get(2).quoted && !b.get(0).quoted, "only the note is quoted");
        b = ReleaseNotes.parse("1. First\n   > a note\n   > on two lines\n1. Second\n   > another\n7. Third");
        check(ReleaseNotes.plainText(b).equals("1. First\na note\non two lines\n2. Second\nanother\n3. Third") && !b.get(4).quoted,
                "a list with notes: " + ReleaseNotes.plainText(b));
        b = ReleaseNotes.parse("- A bullet\n  > its note\n3. Numbers");
        block(b.get(2), ReleaseNotes.Kind.NUMBERED, 0, "3.", "Numbers");
        check(!b.get(2).quoted, "a number left of the quote ends it");
        // Inside a quote, the same.
        b = ReleaseNotes.parse("> 1. a\n>    > q\n> 2. b");
        check(b.size() == 3 && b.get(2).quotes == 1 && b.get(1).quotes == 2, "inside a quote: " + b.size());
        block(b.get(2), ReleaseNotes.Kind.NUMBERED, 0, "2.", "b");
        // Prose wrapped onto a number as far in as the ">" still carries the quote on.
        block(ReleaseNotes.parse("1. a\n   > We grew by\n   80. percent").get(1), ReleaseNotes.Kind.QUOTE, 0, "", "We grew by\n80. percent");
        // A quote indented under an item sits under the item's text, with every block in it; one at the left edge doesn't.
        b = ReleaseNotes.parse("1. a\n   > q\n   > - x\n   >\n   > > deeper\n2. b");
        check(b.get(1).quoteItem == 1 && b.get(2).quoteItem == 1 && b.get(3).quoteItem == 1, "the quote is under its item");
        check(b.get(1).item == 0 && b.get(2).marker.equals(ReleaseNotes.BULLET) && b.get(3).quotes == 2, "quote blocks keep their own item");
        check(b.get(0).quoteItem == 0 && b.get(4).quoteItem == 0, "items are in no quote");
        b = ReleaseNotes.parse("- a\n  - sub\n  > r");
        check(b.size() == 3 && b.get(2).quoteItem == 1, "under the outer item, past the inner one: " + b.size());
        check(ReleaseNotes.parse("1. a\n> q").get(1).quoteItem == 0 && one("> q").quoteItem == 0 && one("q").quoteItem == 0, "a quote outside items");
    }

    /** A quote inside a quote keeps its depth, and blocks know which quote at each depth they are in. */
    private static void nestedQuotes() {
        List<ReleaseNotes.Block> b = ReleaseNotes.parse("> Alice wrote:\n> > The update is ready\n> > > and so is this\n>\n> Back out");
        check(b.size() == 4, "four blocks: " + b.size());
        check(b.get(0).quotes == 1 && b.get(1).quotes == 2 && b.get(2).quotes == 3 && b.get(3).quotes == 1, "depths");
        check(b.get(1).sameQuote(b.get(0), 0) && !b.get(1).sameQuote(b.get(0), 1) && b.get(2).sameQuote(b.get(1), 1)
                && b.get(3).sameQuote(b.get(2), 0) && !b.get(3).sameQuote(b.get(2), 1), "one outer quote, nested in it");
        block(b.get(1), ReleaseNotes.Kind.QUOTE, 0, "", "The update is ready");
        // Two inner quotes in one outer quote are two quotes at the inner depth.
        b = ReleaseNotes.parse("> > a\n>\n> > b");
        check(b.size() == 2 && b.get(1).sameQuote(b.get(0), 0) && !b.get(1).sameQuote(b.get(0), 1), "two inner quotes");
        b = ReleaseNotes.parse("> > a\n> >\n> > b");
        check(b.size() == 2 && b.get(1).sameQuote(b.get(0), 1), "one inner quote, two paragraphs");
        b = ReleaseNotes.parse("> a\n\n> b");
        check(!b.get(1).sameQuote(b.get(0), 0) && !b.get(0).sameQuote(null, 0), "two quotes");
        check(one("plain").quotes == 0 && !one("plain").sameQuote(one("plain"), 0), "no quote");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append('>');
        check(ReleaseNotes.parse(deep + " deep").get(0).quotes == ReleaseNotes.MAX_QUOTES, "depth stops at MAX_QUOTES");
        check(ReleaseNotes.MAX_BARS >= 2 && ReleaseNotes.MAX_BARS <= ReleaseNotes.MAX_QUOTES, "a few bars at most");
    }

    /** The update dialog keeps its buttons pinned unless that would leave the title and notes too little room. */
    private static void footer() {
        check(!PinnedFooter.scrolls(1600, 200, 3000, 300), "a tall window keeps the buttons pinned");
        check(!PinnedFooter.scrolls(700, 200, 500, 300), "short notes that fit keep them pinned");
        check(!PinnedFooter.scrolls(500, 200, 3000, 300), "exactly the minimum left");
        check(PinnedFooter.scrolls(499, 200, 3000, 300), "less than the minimum left");
        check(PinnedFooter.scrolls(550, 550, 3000, 300), "a footer as tall as the dialog");
        check(PinnedFooter.scrolls(600, 700, 3000, 300), "a footer taller than the dialog");
        check(!PinnedFooter.scrolls(260, 200, 60, 300), "a body that fits in what is left needs no more");
        check(PinnedFooter.scrolls(259, 200, 60, 300), "a body that no longer fits");
        check(!PinnedFooter.scrolls(0, 0, 0, 300) && !PinnedFooter.scrolls(0, 200, 3000, 300), "not laid out yet");
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
        // A line break far from the limit would drop most of what follows it: a word break nearer the limit wins.
        String first = words(1600), second = words(1500);
        String longCut = ReleaseNotes.clip(first + "\n" + second, 3000);
        check(longCut.length() > 2900 && longCut.length() <= 3001 && longCut.startsWith(first + "\n") && longCut.endsWith("word…"),
                "cut near the limit: " + longCut.length());
        // A line break within the last fifth still wins, so a cut list ends on a whole item.
        String nearBreak = ReleaseNotes.clip(words(2500) + "\n" + words(1000), 3000);
        check(nearBreak.equals(words(2500) + "…"), "a line break near the limit: " + nearBreak.length());
        check(ReleaseNotes.clip(words(2390) + "\n" + words(1000), 3000).length() > 2900, "a line break before the last fifth is passed by");
        check(ReleaseNotes.clip("a", 1).equals("a") && ReleaseNotes.clip("ab", 1).equals("a…"), "the smallest limit");
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
            // A numbered item shows its place in the list; with the numbers as written, nothing else may change.
            String shown = asWritten(blocks);
            check(letters(shown).equals(letters(input)), "letters and digits survive: [" + input + "] -> [" + shown + "]");
            for (ReleaseNotes.Block block : blocks)
                check(block.kind == ReleaseNotes.Kind.NUMBERED ? block.marker.matches("[0-9]+[.)]") && block.marker.endsWith(block.written.substring(block.written.length() - 1))
                        : block.marker.equals(block.kind == ReleaseNotes.Kind.BULLET ? ReleaseNotes.BULLET : ""), "markers: " + input);
        }
    }

    /** As plainText, with each list item's marker as the notes wrote it. */
    private static String asWritten(List<ReleaseNotes.Block> blocks) {
        StringBuilder out = new StringBuilder();
        for (ReleaseNotes.Block block : blocks) {
            if (out.length() > 0) out.append('\n');
            if (!block.written.isEmpty()) out.append(block.written).append(' ');
            out.append(block.text);
        }
        return out.toString();
    }

    private static String letters(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) out.append(text.charAt(i));
        return out.toString();
    }

    /** Words ("word ") filling length characters, without a trailing space. */
    private static String words(int length) {
        StringBuilder out = new StringBuilder();
        while (out.length() < length) out.append("word ");
        return out.substring(0, length).trim();
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
