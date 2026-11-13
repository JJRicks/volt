package com.jjricks.volt;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B12 review fixes, beyond DashboardModelTextTest (QuickAccessGrid is an Android view, so its lines are
 * checked in src/ and the catalogue it calls is checked here):
 *
 *  - A Setting tile is announced by the full name (entry.label()), or by the two Scooter-page names it
 *    keeps (Charge schedule), while its visible label stays the short rung (Max. speed for Maximum speed).
 *  - Every full shortcut name, which a Quick access button shows, carries that button's width budget,
 *    derived from the cell's own layout numbers and its two lines of whole words (66dp@12sp*2), and the file's
 *    header says a single word must fit one line. A Setting tile's one-line label keeps 90dp@12.5sp.
 */
public final class DashboardModelTileTest {
    private static final String GRID = "src/com/jjricks/volt/QuickAccessGrid.java";
    private static final String STRINGS = "res/values/strings_dashboard_model.xml";
    private static int checks;

    /** Marks each text by its rung: short rungs "short:", every other key "full:". */
    static final class Rungs implements T.Source {
        final Set<Integer> shortIds = new HashSet<>();
        Rungs() throws Exception {
            for (Field f : T.class.getFields())
                if (f.getType() == int.class && Modifier.isStatic(f.getModifiers()) && f.getName().endsWith("_SHORT"))
                    shortIds.add(f.getInt(null));
        }
        public String string(int id) { return (shortIds.contains(id) ? "short:" : "full:") + EnglishText.INSTANCE.string(id); }
        public String plural(int id, long quantity) { return "full:" + EnglishText.INSTANCE.plural(id, quantity); }
        public String language() { return "en"; }
    }

    public static void main(String[] args) throws Exception {
        T.Source saved = T.source();
        try {
            T.install(new Rungs());
            spokenName();
            T.install(null);
            quickAccessBudget();
        } finally {
            T.install(saved);
        }
        System.out.println("DashboardModelTileTest: " + checks + " checks passed (Setting tile spoken name, Quick access budget)");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void same(String expected, CharSequence actual, String what) {
        check(expected.equals(actual.toString()), what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The text of the method that starts at signature, up to its closing brace at the method's indent. */
    private static String method(String source, String signature) {
        int at = source.indexOf(signature);
        check(at >= 0, signature + " not found");
        int end = source.indexOf("\n    }", at);
        check(end > at, signature + " has no end");
        return source.substring(at, end);
    }

    private static void spokenName() throws Exception {
        String grid = read(GRID);
        String spoken = method(grid, "private static String tileSpokenName(QuickAccessActions.Entry entry) {");
        check(!spoken.contains("dashboardModelTileTopSpeed"), "the Maximum speed tile is spoken by its full name, not the abbreviated Max. speed");
        check(spoken.contains("if (CHARGE_SCHEDULE_ID.equals(entry.id)) return T.dashboardModelTileChargeSchedule();"), "spoken Charge schedule");
        check(spoken.contains("return entry.label().render();") && !spoken.contains("shortLabel"), "spoken name is the full rung");
        String shown = method(grid, "private static String tileLabel(QuickAccessActions.Entry entry) {");
        check(shown.contains("return entry.shortLabel().render();"), "the visible tile label stays the short rung");
        check(grid.contains("String description = (tiles ? tileSpokenName(cell.entry) : cell.entry.label())"),
                "a tile's contentDescription starts with its spoken name");
        check(grid.contains("label = ui.text(tileLabel(entry), 12.5f, false);"), "a tile's visible label is tileLabel");
        check(grid.contains("cell.label.setTag(state.isEmpty() ? \"\" : tileLabel(cell.entry));")
                && grid.contains("String shownValue = state.isEmpty() ? tileLabel(cell.entry) : state;"), "a tile shows tileLabel");
        // The rungs really differ in a language: the spoken name takes the full one, the label the short one.
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) {
            if (e.featureKey != null) continue;
            same("full:" + e.label, e.label().render(), e.id + " full rung");
            same("short:" + DashboardModelTextTest.shortEnglish(e), e.shortLabel().render(), e.id + " short rung");
        }
        same("full:Max. speed", T.dashboardModelTileTopSpeed(), "Max. speed tile key");
        same("full:Charge schedule", T.dashboardModelTileChargeSchedule(), "Charge schedule tile key");
    }

    private static void quickAccessBudget() throws Exception {
        String grid = read(GRID);
        // The cell's layout: columns at least 72 dp, 3 dp padding each side, the label at 12 sp (then 10 sp).
        check(grid.contains("ui.dp(72 * ui.textScale) + gap"), "Quick access column minimum is 72 dp");
        check(grid.contains("setPadding(ui.dp(3), ui.dp(compact ? 3 : 7), ui.dp(3), ui.dp(compact ? 3 : 7));"), "Quick access padding is 3 dp");
        check(grid.contains("label = ui.text(entry.label(), 12, false);"), "Quick access label is the full name at 12 sp");
        check(grid.contains("label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);"), "simple line breaks");
        // Up to two lines of whole words (the budget's *2), never smaller; a Setting tile label is one line.
        check(grid.contains("StatusRungs.wholeLines(full, label, room, 2)"), "a Quick access name takes up to two lines");
        check(grid.contains("StatusRungs.wholeLines(full, label, room, 2) ? full : StatusRungs.wholeLines(brief, label, room, 2) ? brief : null"),
                "a Quick access name that does not fit shows its short rung in the same room (volt:overflow=\"short\")");
        check(grid.contains("label.setTextColor(ui.muted); label.setMaxLines(1);"), "a Setting tile label is one line");
        String budget = (72 - 3 - 3) + "dp@12sp*2";
        String strings = read(STRINGS);
        check(strings.contains("A single word must fit one line"), "the header says a single word must fit one line");
        check(strings.contains("volt:max=\"" + budget + "\" on every full name"), "the header names the budget");
        Matcher m = Pattern.compile("<string name=\"(dashboard_model_qa_[a-z_]+)\"([^>]*)>").matcher(strings);
        int full = 0, shorts = 0;
        while (m.find()) {
            String key = m.group(1), attrs = m.group(2);
            if (attrs.contains("volt:ladder=\"full\"")) {
                full++;
                check(attrs.contains("volt:max=\"" + budget + "\""), key + " has no Quick access budget " + budget);
                // Where the name does not fit, the button shows the short rung in the same room (check.py's overflow rule).
                check(attrs.contains("volt:overflow=\"short\""), key + " has no volt:overflow=\"short\"");
            } else if (attrs.contains("volt:ladder=\"short\"")) {
                shorts++;
                check(attrs.contains("volt:max=\"90dp@12.5sp\""), key + " lost its Setting tile budget");
            }
        }
        // Every Volt shortcut (not a scooter control, whose name is B4's) has one full and one short name.
        int volt = 0;
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog()) if (e.featureKey == null) volt++;
        check(full == volt && shorts == volt, "full " + full + ", short " + shorts + ", Volt shortcuts " + volt);
    }
}
