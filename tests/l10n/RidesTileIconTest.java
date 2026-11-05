package com.jjricks.volt;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B15: RidesScreen's detail tiles take their icon from the call site, never from their label's text.
 *
 * Since B15 the tile labels are catalog text, and the base picked each icon by searching the label
 * (label.contains("Distance") ...), so in any other language every tile fell back to the bolt icon.
 * This reads RidesScreen.java and checks that
 *   - tile() takes the icon as a parameter, draws exactly that icon, and uses its label only to show
 *     it (ui.caption) and speak it (the content description), so no text operation can pick an icon;
 *   - every tiles() call passes icon / label / value triples whose icon is a literal ui.icon id and
 *     whose label is a T call, and each icon is the one the base's search picked for that key's
 *     English, so English keeps every icon;
 *   - the value's quiet style ("Not measured") no longer reads the value's first character, which a
 *     language's template can make a word, a unit or a % sign ("%42" in Turkish, fmt_percent's note).
 *     The quiet rule is copied below (QUIET, NUMBER_LEADS, hasDigit), checked against the source, and
 *     shown to style every English value shape as the base rule did.
 * Self-tests plant each refused shape in a synthetic source.
 */
public final class RidesTileIconTest {
    private static int checks;

    private RidesTileIconTest() {}

    /** The ui.icon ids the tiles draw. */
    static final Set<String> ICONS = new HashSet<>(Arrays.asList("route", "clock", "speed", "battery", "hill", "bolt"));

    /** RidesScreen.tile()'s icon search at the merge base (93c47a07, RidesScreen.java:1569), to pin English's icons. */
    static String baseGlyph(String label) {
        return label.contains("speed") ? "speed" : label.contains("Distance") || label.contains("distance") ? "route" : label.contains("Duration") ? "clock" : label.contains("Battery") ? "battery" : label.contains("Ascent") ? "hill" : "bolt";
    }

    /** RidesScreen.tile()'s quiet-style condition at the merge base (RidesScreen.java:1575), parts == null. */
    static boolean baseQuiet(String value) {
        return value.isEmpty() || !Character.isDigit(value.charAt(0)) && "+-−≈".indexOf(value.charAt(0)) < 0;
    }

    /** The head condition in tile(), as L10nFiles.code() spells it (no string literal in it). */
    static final String QUIET = "if (parts == null && (value.isEmpty() || !hasDigit(value) && NUMBER_LEADS.indexOf(value.charAt(0)) < 0)) {";
    /** RidesScreen.NUMBER_LEADS. */
    static final String NUMBER_LEADS = "+-−≈";
    /** RidesScreen.hasDigit(), copied. */
    static boolean hasDigit(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isDigit(value.charAt(i))) return true;
        return false;
    }
    /** The head condition, parts == null. */
    static boolean headQuiet(String value) {
        return value.isEmpty() || !hasDigit(value) && NUMBER_LEADS.indexOf(value.charAt(0)) < 0;
    }

    public static void main(String[] args) {
        String source = L10nFiles.read(new File(L10nFiles.root(), "src/com/jjricks/volt/RidesScreen.java"));
        List<String> found = problems(source, 3);
        check(found.isEmpty(), "RidesScreen's tiles must take their icons from the call site:\n  " + String.join("\n  ", found));
        quietRule(source);
        selfTest();
        System.out.println("RidesTileIconTest: " + checks + " checks passed");
    }

    /** What is wrong with a RidesScreen-like source's tiles; empty when no icon or quiet style comes from text. */
    static List<String> problems(String source, int minimumCalls) {
        List<String> found = new ArrayList<>();
        String code = L10nFiles.code(source);
        Matcher tile = Pattern.compile("\\bView\\s+tile\\s*\\(([^)]*)\\)\\s*\\{").matcher(code);
        if (!tile.find()) {
            found.add("no tile(...) method");
        } else if (!tile.group(1).replaceAll("\\s+", " ").trim().equals("String glyph, String label, String value")) {
            found.add("tile(" + tile.group(1).trim() + ") takes no icon parameter: it must be tile(String glyph, String label, String value)");
        } else {
            int open = tile.end() - 1;
            String body = code.substring(open, closingBrace(code, open));
            List<String> labelUses = uses(body, "label");
            if (!labelUses.equals(Arrays.asList("ui.caption(label)", "tile.setContentDescription(label + \"\" + value)")))
                found.add("tile() uses its label for more than showing and speaking it: " + labelUses);
            if (!uses(body, "glyph").equals(Arrays.asList("ui.icon(glyph, 16, ui.muted)")))
                found.add("tile() must draw the icon its caller passed, as it is (ui.icon(glyph, 16, ui.muted)): " + uses(body, "glyph"));
            if (Pattern.compile("isDigit\\s*\\(\\s*value\\s*\\.\\s*charAt\\s*\\(\\s*0\\s*\\)\\s*\\)").matcher(body).find())
                found.add("tile()'s value style reads the value's first character, which a language's template can make a word, unit or % sign");
        }
        List<String[]> tokens = tokens(source);
        int calls = 0;
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if (!is(tokens.get(i), "w", "tiles") || !is(tokens.get(i + 1), "p", "(") || is(tokens.get(i - 1), "p", ">")) continue;
            calls++;
            String where = "tiles() call at line " + L10nFiles.line(source, Integer.parseInt(tokens.get(i)[2]));
            int at = i + 2;
            while (at < tokens.size() && !is(tokens.get(at), "p", "{") && !is(tokens.get(at), "p", ";")) at++;
            if (at >= tokens.size() || !is(tokens.get(at), "p", "{") || !spelled(tokens, at - 4, "new", "String", "[", "]")) {
                found.add(where + " passes no new String[]{...}");
                continue;
            }
            List<List<String[]>> cells = new ArrayList<>();
            List<String[]> cell = new ArrayList<>();
            int depth = 0;
            for (at++; at < tokens.size(); at++) {
                String[] t = tokens.get(at);
                if (depth == 0 && is(t, "p", "}")) break;
                if (depth == 0 && is(t, "p", ",")) { cells.add(cell); cell = new ArrayList<>(); continue; }
                if (t[0].equals("p") && "([{".contains(t[1])) depth++;
                if (t[0].equals("p") && ")]}".contains(t[1])) depth--;
                cell.add(t);
            }
            cells.add(cell);
            if (cells.isEmpty() || cells.size() % 3 != 0) {
                found.add(where + " passes " + cells.size() + " cells, not icon / label / value triples");
                continue;
            }
            for (int k = 0; k < cells.size(); k += 3) {
                List<String[]> icon = cells.get(k), label = cells.get(k + 1);
                String name = "tile " + (k / 3 + 1) + " of the " + where;
                if (icon.size() != 1 || !icon.get(0)[0].equals("s") || !ICONS.contains(icon.get(0)[1])) {
                    found.add(name + ": its icon is not a ui.icon id literal (" + ICONS + "): " + text(icon));
                    continue;
                }
                List<String> accessors = new ArrayList<>();
                for (int j = 0; j + 3 < label.size(); j++)
                    if (is(label.get(j), "w", "T") && is(label.get(j + 1), "p", ".") && label.get(j + 2)[0].equals("w") && is(label.get(j + 3), "p", "("))
                        accessors.add(label.get(j + 2)[1]);
                if (accessors.isEmpty()) found.add(name + ": its label is not a T call: " + text(label));
                for (String accessor : accessors) {
                    String key = snake(accessor), english = english(key);
                    if (english == null) found.add(name + ": no catalog key " + key);
                    else if (!baseGlyph(english).equals(icon.get(0)[1]))
                        found.add(name + ": icon \"" + icon.get(0)[1] + "\" for T." + accessor + "() (\"" + english + "\"), but the base drew \""
                                + baseGlyph(english) + "\"; English keeps its icons");
                }
            }
        }
        if (calls < minimumCalls) found.add("the scan sees " + calls + " tiles() call(s), fewer than " + minimumCalls);
        return found;
    }

    /** The quiet-style rule: the source holds the copied rule, and it styles every English value shape as the base did. */
    private static void quietRule(String source) {
        String code = L10nFiles.code(source);
        check(count(code, QUIET) == 1, "tile()'s quiet-style condition is the copied QUIET, once");
        check(source.contains("private static final String NUMBER_LEADS = \"" + NUMBER_LEADS + "\";"), "RidesScreen.NUMBER_LEADS is the copied value");
        check(code.replaceAll("\\s+", " ").contains("private static boolean hasDigit(String value) { for (int i = 0; i < value.length(); i++) "
                + "if (Character.isDigit(value.charAt(i))) return true; return false; }"), "RidesScreen.hasDigit is the copied method");
        // Every value shape a tile shows in English: distances, speeds, durations, percentages, energy, ascent, the missing forms
        // and the non-finite numbers String.format prints. The head styles each as the base did.
        for (String value : new String[]{"", "Not measured", "—", "— / —", "6.80 mi", "4.23 mi / 6.80 km", "12.3 mph", "-3.20 mi", "24m 05s",
                "1h 02m", "5s", "42%", "0%", "12.34 Wh", "54.3 Wh/mi · net", "12.3 Wh/mi / 7.6 Wh/km · throughput", "≈12 ft / 10 ft",
                "≈4 m / 3 m", "NaN mi", "NaN mi / NaN km", "Infinity Wh", "-Infinity mi", "≈NaN ft / NaN ft", "−3%", "+2%"})
            check(headQuiet(value) == baseQuiet(value), "English value <" + value + "> keeps its style");
        // A value with its number after a word, unit or % sign is a reading, not "Not measured"; the base styled it quiet.
        for (String value : new String[]{"%42", "% 42", "netto: 54,3 Wh/mi", "mi 6,80"}) {
            check(baseQuiet(value), "the base styled <" + value + "> as quiet text");
            check(!headQuiet(value), "the head styles <" + value + "> as a reading");
        }
        check(headQuiet("Nicht gemessen") && headQuiet("—"), "a word with no number stays quiet in any language");
    }

    /** Each refused shape is found in a synthetic source, and the head shape passes. */
    private static void selfTest() {
        String good = source("String glyph, String label, String value", "", "\"route\", T.ridesTileDistance(), v, \"clock\", T.ridesTileDuration(), w");
        check(problems(good, 1).isEmpty(), "self-test: the head shape passes: " + problems(good, 1));
        String battery = source("String glyph, String label, String value", "", "\"battery\", gain ? T.ridesTileBatteryGain() : T.ridesTileBatteryDrop(), v");
        check(problems(battery, 1).isEmpty(), "self-test: both labels of a ternary keep the icon: " + problems(battery, 1));
        expect("the base's label search", source("String label, String value",
                "String glyph = label.contains(\"speed\") ? \"speed\" : \"bolt\";", "T.ridesTileDistance(), v"),
                "takes no icon parameter", "passes 2 cells, not icon / label / value triples");
        expect("a search left in the head shape", source("String glyph, String label, String value",
                "if (label.contains(\"Distance\")) glyph = \"route\";", "\"route\", T.ridesTileDistance(), v"),
                "uses its label for more than", "must draw the icon its caller passed");
        expect("a helper that picks from the label", source("String glyph, String label, String value",
                "String shown = pick(label);", "\"route\", T.ridesTileDistance(), v"), "uses its label for more than");
        expect("an icon chosen at the call site from text", source("String glyph, String label, String value", "",
                "glyphOf(T.ridesTileDistance()), T.ridesTileDistance(), v"), "its icon is not a ui.icon id literal");
        expect("a wrong icon", source("String glyph, String label, String value", "", "\"bolt\", T.ridesTileDistance(), v"),
                "icon \"bolt\" for T.ridesTileDistance() (\"Distance\"), but the base drew \"route\"");
        expect("an English label", source("String glyph, String label, String value", "", "\"route\", \"Distance\", v"),
                "its label is not a T call");
        expect("the first-character rule", source("String glyph, String label, String value",
                "boolean quiet = !Character.isDigit(value.charAt(0));", "\"route\", T.ridesTileDistance(), v"), "reads the value's first character");
        expect("no call site", source("String glyph, String label, String value", "", null), "the scan sees 0 tiles() call(s)");
    }

    /** A synthetic RidesScreen: tile() with the given parameters and extra statement, and one tiles() call with the given cells. */
    private static String source(String parameters, String extra, String cells) {
        return "final class S {\n"
                + "  void build() {\n"
                + (cells == null ? "" : "    List<View> made = tiles(body, new String[]{" + cells + "});\n")
                + "  }\n"
                + "  private List<View> tiles(LinearLayout parent, String[] cells) { return null; }\n"
                + "  private View tile(" + parameters + ") {\n"
                + "    " + extra + "\n"
                + "    heading.addView(ui.icon(glyph, 16, ui.muted), params);\n"
                + "    TextView name = ui.caption(label);\n"
                + "    tile.setContentDescription(label + \", \" + value);\n"
                + "    return tile;\n"
                + "  }\n"
                + "}\n";
    }

    private static void expect(String what, String source, String... wanted) {
        List<String> found = problems(source, 1);
        for (String part : wanted) {
            boolean seen = false;
            for (String problem : found) seen |= problem.contains(part);
            check(seen, "self-test (" + what + "): expected a problem containing <" + part + ">, found " + found);
        }
    }

    /** Each use of name in body, as the call or expression around it (L10nFiles.code form). */
    private static List<String> uses(String body, String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b" + name + "\\b").matcher(body);
        while (m.find()) {
            int start = m.start(), depth = 0;
            // Back to the call that holds the use: the identifier before the nearest unclosed parenthesis.
            while (start > 0) {
                char c = body.charAt(start - 1);
                if (c == ')') depth++;
                else if (c == '(' && depth-- == 0) break;
                else if (depth == 0 && (c == ';' || c == '{' || c == '}' || c == '=')) break;
                start--;
            }
            int call = start;
            if (start > 0 && body.charAt(start - 1) == '(') {
                call = start - 1;
                while (call > 0 && (Character.isJavaIdentifierPart(body.charAt(call - 1)) || body.charAt(call - 1) == '.')) call--;
            }
            int end = start > 0 && body.charAt(start - 1) == '(' ? L10nFiles.closing(body, start - 1) : m.end();
            out.add(body.substring(call, end < 0 ? m.end() : end).trim());
        }
        return out;
    }

    /** Java tokens of source with comments skipped: {kind, text, index}; kind "s" a string literal (its contents), "c" a char, "w" a word, "p" one other character. */
    static List<String[]> tokens(String source) {
        List<String[]> out = new ArrayList<>();
        int i = 0, n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) j += source.charAt(j) == '\\' ? 2 : 1;
                out.add(new String[]{c == '"' ? "s" : "c", source.substring(i + 1, Math.min(j, n)), Integer.toString(i)});
                i = j + 1;
            } else if (Character.isJavaIdentifierPart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(source.charAt(j))) j++;
                out.add(new String[]{"w", source.substring(i, j), Integer.toString(i)});
                i = j;
            } else {
                out.add(new String[]{"p", String.valueOf(c), Integer.toString(i)});
                i++;
            }
        }
        return out;
    }

    private static boolean is(String[] token, String kind, String text) {
        return token[0].equals(kind) && token[1].equals(text);
    }

    private static boolean spelled(List<String[]> tokens, int from, String... words) {
        if (from < 0) return false;
        for (int k = 0; k < words.length; k++) if (from + k >= tokens.size() || !tokens.get(from + k)[1].equals(words[k])) return false;
        return true;
    }

    private static String text(List<String[]> tokens) {
        StringBuilder out = new StringBuilder();
        for (String[] t : tokens) out.append(out.length() == 0 ? "" : " ").append(t[0].equals("s") ? "\"" + t[1] + "\"" : t[1]);
        return out.toString();
    }

    /** A T accessor's key: ridesTileGpsDistance is rides_tile_gps_distance. */
    static String snake(String accessor) {
        StringBuilder key = new StringBuilder();
        for (char c : accessor.toCharArray()) key.append(Character.isUpperCase(c) ? "_" + Character.toLowerCase(c) : String.valueOf(c));
        return key.toString();
    }

    private static String english(String key) {
        for (int id = 0; id < T.size(); id++) if (T.key(id).equals(key)) return EnglishText.INSTANCE.string(id);
        return null;
    }

    /** The index just after the brace that closes the one at open (L10nFiles.code form, so no brace hides in text). */
    private static int closingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            else if (code.charAt(i) == '}' && --depth == 0) return i + 1;
        }
        return code.length();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
