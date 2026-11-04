package com.jjricks.volt;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B17: the welcome tiles' narrow label ladder without a TextView subclass.
 *   - The label is the plain TextView ui.text returns (the G7 census keys every view by its simple class
 *     name, so a subclass shows the four tile labels as changed on every welcome screen even when their text
 *     is identical): Ui.text builds `new TextView(context)`, features() keeps base's label line, and no class
 *     in OnboardingScreen extends TextView or one of its subclasses.
 *   - The rung choice (TileRung): the full word while it fits, the short rung when it does not, back to the
 *     full word when the room grows again, and no text set when the chosen rung equals the shown text (always
 *     the case in English, where both rungs are the same word).
 * Runs from the repository root (it reads sources).
 */
public final class OnboardingTileLabelTest {
    private static int checks;
    private static final Pattern SUBCLASS = Pattern.compile(
            "class\\s+\\w+\\s+extends\\s+(android\\.widget\\.)?(TextView|Button|EditText|CheckedTextView|Chronometer|TextClock|CheckBox|RadioButton|Switch|ToggleButton|CompoundButton|AutoCompleteTextView)\\b");

    public static void main(String[] args) {
        T.install(null);
        plainLabel();
        subclassSelfTest();
        rungs();
        System.out.println("OnboardingTileLabelTest: " + checks + " checks passed (plain label, rung choice)");
    }

    private static void plainLabel() {
        String screen = L10nFiles.read(new File(L10nFiles.root(), "src/com/jjricks/volt/OnboardingScreen.java"));
        String ui = L10nFiles.read(new File(L10nFiles.root(), "src/com/jjricks/volt/Ui.java"));
        Matcher subclass = SUBCLASS.matcher(screen);
        boolean found = subclass.find();
        check(!found, "OnboardingScreen declares no TextView subclass" + (found ? ": " + subclass.group() : ""));
        check(!Pattern.compile("\\bTileLabel\\b").matcher(screen).find(), "no TileLabel class in OnboardingScreen");
        String features = body(screen, "private View features()");
        check(features.contains("TextView label = ui.text(items[i][1], 12.5f, false);"),
                "the tile label is the TextView ui.text returns, as base built it");
        check(features.contains("label.setTextColor(ui.muted); label.setGravity(Gravity.CENTER); label.setMaxLines(1);"),
                "the tile label's styling calls are base's, in base's order");
        check(!Pattern.compile("new\\s+\\w*(Label|TextView)\\s*\\(").matcher(features).find(),
                "features() constructs no label view of its own");
        check(features.contains("fitTileLabel(tile, label, items[i][1], TILE_SHORT[i]);"), "each tile label gets the ladder listener");
        String text = body(ui, "public TextView text(String value, float sp, boolean bold)");
        check(text.contains("TextView view = new TextView(context);"), "Ui.text builds a plain TextView (getClass() == TextView.class)");
        String fit = body(screen, "private static void fitTileLabel(");
        check(fit.contains("addOnLayoutChangeListener"), "the ladder runs from a layout-change listener");
        check(fit.contains("if (TileRung.needsSet(pick, label.getText())) label.setText("), "the label's text is set only when the rung differs");
        check(!fit.contains("requestLayout") && !fit.contains("invalidate"), "the ladder asks for no extra layout or draw");
    }

    private static void subclassSelfTest() {
        check(SUBCLASS.matcher("private static final class TileLabel extends TextView {").find(), "self-test: a TextView subclass is caught");
        check(SUBCLASS.matcher("final class Pill extends android.widget.Button {").find(), "self-test: a Button subclass is caught");
        check(!SUBCLASS.matcher("private final class Progress extends View {").find(), "self-test: a View subclass is not a TextView one");
        check(!SUBCLASS.matcher("private final class Panes extends LinearLayout {").find(), "self-test: a layout is not a TextView one");
    }

    private static void rungs() {
        String full = "Einstellungen", brief = "Einst.";
        check(TileRung.pick(full, 120f, brief, 200) == full, "the full word when it fits");
        check(TileRung.pick(full, 200f, brief, 200) == full, "the full word when it fits exactly");
        check(TileRung.pick(full, 200.5f, brief, 200) == brief, "the short rung when the full word does not fit");
        check(TileRung.pick(full, 240f, brief, 0) == brief, "the short rung when the tile has no room");
        // A rotation or text-scale change: narrow, then wide again, back to the full word.
        CharSequence shown = full;
        CharSequence pick = TileRung.pick(full, 240f, brief, 180);
        check(TileRung.needsSet(pick, shown), "narrowing sets the short rung"); shown = pick;
        pick = TileRung.pick(full, 240f, brief, 180);
        check(!TileRung.needsSet(pick, shown), "the same width sets nothing again (no loop)");
        pick = TileRung.pick(full, 240f, brief, 320);
        check(TileRung.needsSet(pick, shown) && pick == full, "widening sets the full word again"); shown = pick;
        check(!TileRung.needsSet(TileRung.pick(full, 240f, brief, 320), shown), "the full word, once shown, is not set again");
        check(!TileRung.needsSet("Lock", new StringBuilder("Lock")), "equal content is not set again, whatever its CharSequence type");
        // English: each short rung is its full word, so neither width sets the text.
        CharSequence[][] english = {
                {T.onboardingWelcomeTileLock(), M.onboardingWelcomeTileLockShort()},
                {T.onboardingWelcomeTileSettings(), M.onboardingWelcomeTileSettingsShort()},
                {T.onboardingWelcomeTileBattery(), M.onboardingWelcomeTileBatteryShort()},
                {T.onboardingWelcomeTileRides(), M.onboardingWelcomeTileRidesShort()}};
        for (CharSequence[] tile : english) {
            CharSequence word = tile[0];
            check(!TileRung.needsSet(TileRung.pick(word, 80f, tile[1], 200), word), word + ": fits, nothing set");
            check(!TileRung.needsSet(TileRung.pick(word, 80f, tile[1], 10), word), word + ": too narrow, still nothing set in English");
        }
    }

    /** The text of the method or block starting at signature, through its matching closing brace. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "found " + signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int at = open; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, at + 1);
        }
        throw new AssertionError("unclosed " + signature);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
