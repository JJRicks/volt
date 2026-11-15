package com.jjricks.volt;

import java.util.List;

/** WhatsNewNotes: the bundled section of CHANGELOG.md for the What's new sheet (hub layout part 9; #383 answers 9, 10, 12). */
public final class WhatsNewNotesTest {
    private static int checks;

    private static final String CHANGELOG = "# Changelog\n\n"
            + "## 1.14.0 — 2026-11-20\n\n"
            + "- **Clean Home.** A fixed Home with `RIDE` and the [user guide](docs/user-guide.md).\n"
            + "  It continues on a second line.\n"
            + "  - A nested point with *emphasis* and snake_case_words.\n"
            + "- Second point, with \\*stars\\* kept.\n\n"
            + "### Fixes\n\n"
            + "A plain paragraph.\n\n"
            + "## 1.13.1 — 2026-11-11\n\n"
            + "- Older point.\n";

    public static void main(String[] args) {
        section();
        parse();
        inline();
        cut();
        System.out.println("WhatsNewNotes: " + checks + " checks passed");
    }

    private static void section() {
        String section = WhatsNewNotes.section(CHANGELOG, "1.14.0");
        require(section != null && section.startsWith("## 1.14.0 — 2026-11-20\n"), "The section starts at its heading");
        require(!section.contains("Older point") && section.contains("A plain paragraph."), "and stops at the next version");
        require(WhatsNewNotes.section(CHANGELOG, "1.13.1").contains("Older point."), "The last section runs to the end");
        require(WhatsNewNotes.section(CHANGELOG, "1.13") == null, "A version needs its own heading, not a prefix");
        require(WhatsNewNotes.section(CHANGELOG, "1.15.0") == null && WhatsNewNotes.section(null, "1.14.0") == null, "No section, no notes");
        require("1.14.0".equals(WhatsNewNotes.headingVersion("## 1.14.0 — 2026-11-20")), "The heading's version");
        require("1.14.0".equals(WhatsNewNotes.headingVersion("## v1.14.0")), "A leading v is dropped");
        require(WhatsNewNotes.headingVersion("## Unreleased") == null && WhatsNewNotes.headingVersion("### 1.14.0") == null,
                "Only a numbered second-level heading names a version");
    }

    private static void parse() {
        String section = WhatsNewNotes.section(CHANGELOG, "1.14.0");
        WhatsNewNotes.Notes notes = WhatsNewNotes.parse(section, "1.14.0");
        require(notes != null && notes.version.equals("1.14.0") && !notes.cut, "The installed version's notes");
        require(notes.page().equals("https://github.com/JJRicks/volt/releases/tag/v1.14.0"), "Its release page");
        require(notes.items.size() == 5, "Two points, one nested point, a heading and a paragraph: " + notes.items.size());
        WhatsNewNotes.Item first = notes.items.get(0);
        require(first.bullet && !first.nested, "A point is a bullet");
        require(first.plain().equals("Clean Home. A fixed Home with RIDE and the user guide. It continues on a second line."),
                "Marks and link addresses go, continuation lines join: " + first.plain());
        require(first.spans.get(0).bold && first.spans.get(0).text.equals("Clean Home."), "The bold lead stays bold");
        boolean code = false;
        for (WhatsNewNotes.Span span : first.spans) code |= span.code && span.text.equals("RIDE");
        require(code, "Code stays code");
        WhatsNewNotes.Item nested = notes.items.get(1);
        require(nested.bullet && nested.nested && nested.plain().equals("A nested point with emphasis and snake_case_words."),
                "An indented point is nested; underscores inside words stay: " + nested.plain());
        require(notes.items.get(2).plain().equals("Second point, with *stars* kept."), "Escapes show their character");
        require(!notes.items.get(3).bullet && notes.items.get(3).spans.get(0).bold && notes.items.get(3).plain().equals("Fixes"),
                "A smaller heading is a bold line");
        require(!notes.items.get(4).bullet && notes.items.get(4).plain().equals("A plain paragraph."), "A paragraph is no bullet");
        require(WhatsNewNotes.parse(section, "1.14.0-dev+abc1234-dirty") != null, "A dev build of the version shows its notes");
        require(WhatsNewNotes.parse(section, "1.14") != null, "1.14 is 1.14.0");
        require(WhatsNewNotes.parse(section, "1.13.1") == null, "Another version's file never shows");
        require(WhatsNewNotes.parse(section, "unknown") == null && WhatsNewNotes.parse(null, "1.14.0") == null, "Nothing to show");
        require(WhatsNewNotes.parse("## 1.14.0 — 2026-11-20\n\n", "1.14.0") == null, "A heading alone shows nothing");
        require(WhatsNewNotes.parse("Some text\n", "1.14.0") == null, "A file without a heading shows nothing");
    }

    private static void inline() {
        require(plain("**open") .equals("**open"), "An unclosed bold mark is kept");
        require(plain("a * b").equals("a * b"), "A lone star is kept");
        require(plain("`x").equals("`x"), "An unclosed code mark is kept");
        require(plain("[words](https://example.invalid/x)").equals("words"), "A link keeps its words");
        require(plain("[not a link] here").equals("[not a link] here"), "Brackets alone are kept");
        List<WhatsNewNotes.Span> spans = WhatsNewNotes.inline("__Bold__ then plain");
        require(spans.size() == 2 && spans.get(0).bold && !spans.get(1).bold, "Underscore bold");
    }

    private static void cut() {
        StringBuilder long_ = new StringBuilder("## 2.0.0 — 2027-01-01\n\n");
        for (int i = 0; i < 40; i++) long_.append("- Point ").append(i).append(' ').append(repeat("x", 100)).append('\n');
        WhatsNewNotes.Notes notes = WhatsNewNotes.parse(long_.toString(), "2.0.0");
        int length = 0;
        for (WhatsNewNotes.Item item : notes.items) length += item.plain().length();
        require(notes.cut && length <= WhatsNewNotes.MAX_CHARS && notes.items.size() < 40, "Long notes are cut between points");
        WhatsNewNotes.Notes one = WhatsNewNotes.parse("## 2.0.0\n\n- " + repeat("y", WhatsNewNotes.MAX_CHARS * 2) + "\n- next\n", "2.0.0");
        require(one.items.size() == 1 && one.cut, "The first point always shows");
    }

    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }

    private static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        for (WhatsNewNotes.Span span : WhatsNewNotes.inline(markdown)) out.append(span.text);
        return out.toString();
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
