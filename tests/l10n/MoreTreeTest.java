package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B18: English view trees stay as they were before the Language row (README, G7 census). The census
 * names every view by its child index from DecorView, and GONE children count, so a Language row kept
 * in the display card while hidden would shift every later sibling. MoreScreen cannot run on the JVM, so
 * this checks its source: languageRow() builds the row in a holder of its own that is never attached,
 * takes it out of that holder, and hides nothing; only placeLanguageRow() puts it, with its divider, at
 * the top of the display card (with Units and Text size), after the not-offered branch has removed both
 * and returned, and only while it is not attached already (no duplicate on refresh).
 */
public final class MoreTreeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/MoreScreen.java")), StandardCharsets.UTF_8);

        String build = body(source, "private void languageRow(LinearLayout display)");
        check(!build.contains("addView"), "languageRow() attaches nothing");
        check(!build.contains("setVisibility"), "languageRow() keeps no hidden row");
        check(build.contains("LinearLayout holder = column();") && !source.contains("addView(holder"), "the row's holder is never attached");
        check(build.contains("holder.removeView(language);"), "the row leaves its holder before it is placed");
        check(count(build, "removeView") == 1, "languageRow() removes only the row from its holder");
        check(build.contains("valueRefreshers.add("), "languageRow() places the row on refresh");
        check(build.contains("placeLanguageRow(display, language, divider, offered, ui.dp(1))"), "the refresher places the row by offered");

        String place = body(source, "private static void placeLanguageRow(LinearLayout display, View language, View divider, boolean offered, int line)");
        int notOffered = place.indexOf("if (!offered) { if (attached) { display.removeView(divider); display.removeView(language); } return; }");
        int already = place.indexOf("if (attached) return;");
        int add = place.indexOf("display.addView(language, 0, new LinearLayout.LayoutParams(-1, -2));");
        int line = place.indexOf("display.addView(divider, 1, new LinearLayout.LayoutParams(-1, line));");
        check(place.contains("boolean attached = language.getParent() == display;"), "attachment is read from the row");
        check(notOffered >= 0, "not offered: the row and its divider are removed and nothing is added");
        check(already > notOffered, "offered and attached: no second copy");
        check(add > already && line > add, "the row, then its divider, are added only after both early returns");
        check(count(place, "addView") == 2, "placeLanguageRow() adds the row and its divider once each");

        String prefs = body(source, "private void preferences()");
        int display = prefs.indexOf("display.setTag(\"preference-display-options\");");
        int call = prefs.indexOf("languageRow(display);");
        int units = prefs.indexOf("T.morePrefsUnits()");
        check(display >= 0 && call > display && units > call, "the Language row belongs to the display card, before Units");

        check(count(source, "addView(language") == 1, "the Language row is attached in one place");
        check(count(source, "\"preference_language\"") == 1, "one Language row tag");
        check(count(source, "languageRow(display);") == 1, "one Language row per screen");

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
