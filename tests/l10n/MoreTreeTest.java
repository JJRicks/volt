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
 * takes it out of that holder, and hides nothing; only placeLanguageRow() puts it, with its divider, in
 * the display card right above Haptic feedback (after Units and Text size, the Layout Plan's order since part 11),
 * after the not-offered branch has removed both and returned, and only while it is not attached already (no
 * duplicate on refresh).
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
        // The plan's "English ›" beside the title only when it fits there on one line (SettingsRows.valueBeside) in the room
        // the row really has (SettingsRows.valueRoom: the row less its padding, the title, the gap and the chevron; the
        // 140 dp column until the row has a width), and then held to one line in that room; otherwise under the title,
        // so the value is never cut. Placed on every refresh and again when the row's width changes.
        check(build.contains("boolean beside = SettingsRows.valueBeside(row.value.getPaint().measureText(value), room);")
                && build.contains("SettingsRows.valueRoom(inner, row.title.getPaint().measureText(row.title.getText().toString()),")
                && build.contains("ui.dp(SettingsRows.VALUE_GAP_DP), ui.dp(SettingsRows.CHEVRON_DP)) : ui.dp(SettingsRows.VALUE_MAX_DP);")
                && build.contains("if (beside) { row.value.setMaxLines(1); row.value.setMaxWidth((int) Math.ceil(room)); }")
                && build.contains("row.value(beside ? value : null);") && build.contains("row.sub(beside ? null : value);")
                && count(build, "row.value(") == 1 && count(build, "row.sub(") == 1,
                "the language shows beside the title only when it fits the row's room on one line, else under it, never cut");
        check(build.contains("addOnLayoutChangeListener(") && build.contains("v.post(placeValue)") && build.contains("placeValue.run();"),
                "the language's place is decided on refresh and again when the row's width changes");
        check(build.contains("placeLanguageRow(display, language, divider, offered, ui.dp(1), hapticsRow)"), "the refresher places the row by offered");

        String place = body(source, "private static void placeLanguageRow(LinearLayout display, View language, View divider, boolean offered, int line, View before)");
        int notOffered = place.indexOf("if (!offered) { if (attached) { display.removeView(divider); display.removeView(language); } return; }");
        int already = place.indexOf("if (attached) return;");
        int at = place.indexOf("int at = SettingsRows.languageIndex(display.indexOfChild(before), display.getChildCount());");
        int add = place.indexOf("display.addView(language, at, new LinearLayout.LayoutParams(-1, -2));");
        int line = place.indexOf("display.addView(divider, at + 1, new LinearLayout.LayoutParams(-1, line));");
        check(at > already && add > at, "the row's place is worked out only once it is to be added");
        check(place.contains("boolean attached = language.getParent() == display;"), "attachment is read from the row");
        check(notOffered >= 0, "not offered: the row and its divider are removed and nothing is added");
        check(already > notOffered, "offered and attached: no second copy");
        check(add > already && line > add, "the row, then its divider, are added only after both early returns");
        check(count(place, "addView") == 2, "placeLanguageRow() adds the row and its divider once each");

        String prefs = body(source, "private void preferences()");
        int display = prefs.indexOf("display.setTag(\"preference-display-options\");");
        int call = prefs.indexOf("languageRow(display);");
        int units = prefs.indexOf("T.morePrefsUnits()"), haptics = prefs.indexOf("hapticsRow = haptics.view;");
        check(display >= 0 && call > display && units > call, "the Language row belongs to the display card, set up before its rows");
        check(haptics > units, "the Haptic feedback row Language goes above is the display card's");

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
