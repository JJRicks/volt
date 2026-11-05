package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B18: English view trees stay as they were before the Language row (README, G7 census). The census
 * names every view by its child index from DecorView, and GONE children count, so a Language card kept
 * in App preferences while hidden would shift every later sibling. MoreScreen cannot run on the JVM, so
 * this checks its source: languageRow() builds the card without attaching or hiding it, and only
 * placeLanguageRow() attaches it, after the not-offered branch has removed it and returned, and only
 * while it is not attached already (no duplicate on refresh).
 */
public final class MoreTreeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MoreScreen.java")), StandardCharsets.UTF_8);

        String build = body(source, "private void languageRow()");
        check(!build.contains("addView"), "languageRow() attaches nothing");
        check(!build.contains("setVisibility"), "languageRow() keeps no hidden card");
        check(!build.contains("removeView"), "languageRow() removes nothing");
        check(build.contains("valueRefreshers.add("), "languageRow() places the card on refresh");
        check(build.contains("placeLanguageRow(section, language, offered)"), "the refresher places the card by offered");
        check(build.indexOf("LinearLayout section = cardDestination;") >= 0
                && build.indexOf("LinearLayout section = cardDestination;") < build.indexOf("ui.groupCard()"),
                "the section is captured before the card is built (cardDestination moves on)");

        String place = body(source, "private static void placeLanguageRow(LinearLayout section, View language, boolean offered)");
        int notOffered = place.indexOf("if (!offered) { if (attached) section.removeView(language); return; }");
        int already = place.indexOf("if (attached) return;");
        int add = place.indexOf("section.addView(language, below);");
        check(place.contains("boolean attached = language.getParent() == section;"), "attachment is read from the card");
        check(notOffered >= 0, "not offered: the card is removed and nothing is added");
        check(already > notOffered, "offered and attached: no second copy");
        check(add > already, "the card is added only after both early returns");
        check(count(place, "addView") == 1, "placeLanguageRow() adds the card once");

        check(count(source, "addView(language") == 1, "the Language card is attached in one place");
        check(count(source, "\"preference_language\"") == 1, "one Language card tag");
        check(count(source, "languageRow();") == 1, "one Language row per screen");

        System.out.println("MoreTreeTest: " + checks + " checks passed");
    }

    /** The body of the method whose declaration is given, by brace matching (no braces in its strings). */
    private static String body(String source, String declaration) {
        int at = source.indexOf(declaration);
        check(at >= 0, "found " + declaration);
        check(source.indexOf(declaration, at + 1) < 0, "one " + declaration);
        int open = source.indexOf('{', at), depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open + 1, i);
        }
        throw new AssertionError("unbalanced " + declaration);
    }

    private static int count(String text, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("MoreTreeTest: " + what);
        checks++;
    }
}
