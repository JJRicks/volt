package com.jjricks.volt;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B17's own suite (first-run setup, OnboardingScreen). The screen itself needs Android; this checks the
 * text it draws from the catalog and the few code paths the batch changed:
 *   - markup: every onboarding_ string keeps its ** pairs and backtick pairs balanced, and any translation
 *     (a res/values-xx/strings_onboarding.xml) keeps the same number of ** pairs and exactly the English
 *     text inside each backtick pair (a command or a word the computer prints); a self-test shows the rule
 *     catches a dropped pair, a translated command and an extra bold;
 *   - the welcome tiles' ladders: each short rung's English is its full rung's, so English tiles are
 *     unchanged;
 *   - the spoken templates render the old concatenations for sample values (the step row, a numbered
 *     instruction with and without a detail, a setup choice with every optional part);
 *   - the words quoted from the English-only pairscooter website and the computer's output are marked
 *     do-not-translate, and no note asks for "the button word the website shows";
 *   - the source: the nothing-found test compares BleController.NOTHING_FOUND, the status line shows
 *     displayMessage(), and a refused typed identity is toasted through SetupException.shown.
 * Runs from the repository root (it reads sources and resources).
 */
public final class OnboardingTextTest {
    private static int checks;
    private static final Pattern STRING = Pattern.compile("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);

    public static void main(String[] args) {
        T.install(null);
        markupSelfTest();
        englishMarkup();
        translatedMarkup();
        ladders();
        templates();
        source();
        websiteWords();
        System.out.println("OnboardingTextTest: " + checks + " checks passed (markup, ladders, templates, source)");
    }

    /** Problems with a translation's markup against its English, or with English alone when other is null. */
    static List<String> markup(String english, String other) {
        List<String> problems = new ArrayList<>();
        String text = other == null ? english : other;
        if (count(text, "**") % 2 != 0) problems.add("unbalanced **");
        if (count(text, "`") % 2 != 0) problems.add("unbalanced backtick");
        if (other != null) {
            if (count(other, "**") != count(english, "**")) problems.add("** pairs differ from English");
            if (!spans(other).equals(spans(english))) problems.add("backtick text differs from English: " + spans(other));
        }
        return problems;
    }

    private static void markupSelfTest() {
        String english = "`adb devices` shows **device**";
        check(markup(english, null).isEmpty(), "self-test: English markup is balanced");
        check(markup(english, "`adb devices` muestra **device**").isEmpty(), "self-test: a faithful translation passes");
        check(!markup(english, "`adb devices` muestra **device").isEmpty(), "self-test: a dropped ** is caught");
        check(!markup(english, "`adb dispositivos` muestra **device**").isEmpty(), "self-test: a translated command is caught");
        check(!markup(english, "`adb devices` **muestra** **device**").isEmpty(), "self-test: an extra bold is caught");
        check(!markup("Tap **Allow", null).isEmpty(), "self-test: unbalanced English is caught");
    }

    private static void englishMarkup() {
        int seen = 0;
        for (int id = 0; id < T.size(); id++) {
            if (T.isPlural(id) || !T.key(id).startsWith("onboarding_")) continue;
            seen++;
            String english = EnglishText.INSTANCE.string(id);
            check(markup(english, null).isEmpty(), T.key(id) + ": " + markup(english, null));
        }
        check(seen > 150, "the onboarding_ strings are in the catalog (" + seen + ")");
        check(EnglishText.INSTANCE.string(T.ONBOARDING_COMPUTER_ADB_DEVICES).contains("`adb devices`"),
                "the adb devices instruction keeps its command span");
    }

    private static void translatedMarkup() {
        File res = new File(L10nFiles.root(), "res");
        File[] folders = res.listFiles((dir, name) -> name.startsWith("values-"));
        if (folders == null) return;
        for (File folder : folders) {
            File file = new File(folder, "strings_onboarding.xml");
            if (!file.isFile()) continue;
            Matcher m = STRING.matcher(L10nFiles.read(file));
            while (m.find()) {
                int id = idOf(m.group(1));
                if (id < 0) continue;
                String english = EnglishText.INSTANCE.string(id);
                List<String> problems = markup(english, m.group(2));
                check(problems.isEmpty(), folder.getName() + "/" + m.group(1) + ": " + problems);
            }
        }
    }

    private static void ladders() {
        int[][] rungs = {
                {T.ONBOARDING_WELCOME_TILE_LOCK, T.ONBOARDING_WELCOME_TILE_LOCK_SHORT},
                {T.ONBOARDING_WELCOME_TILE_SETTINGS, T.ONBOARDING_WELCOME_TILE_SETTINGS_SHORT},
                {T.ONBOARDING_WELCOME_TILE_BATTERY, T.ONBOARDING_WELCOME_TILE_BATTERY_SHORT},
                {T.ONBOARDING_WELCOME_TILE_RIDES, T.ONBOARDING_WELCOME_TILE_RIDES_SHORT}};
        String[] english = {"Lock", "Settings", "Battery", "Rides"};
        for (int i = 0; i < rungs.length; i++) {
            equal(english[i], T.render(rungs[i][0]), "tile " + i + " full rung");
            equal(english[i], T.render(rungs[i][1]), "tile " + i + " short rung is the full word in English");
            equal(T.key(rungs[i][0]) + "_short", T.key(rungs[i][1]), "tile " + i + " rung keys");
        }
    }

    private static void templates() {
        equal("Step 3, Connect computer, current",
                T.onboardingStepRow(3, M.onboardingStepConnectComputer(), T.onboardingStepStateCurrent()), "step row");
        equal("Step 5, Import, not needed",
                T.onboardingStepRow(5, M.onboardingStepImport(), T.onboardingStepStateNotNeeded()), "step row, skipped");
        equal("Step 2. Tap Allow on the prompt above", T.onboardingNumberedSpoken(2, "Tap Allow on the prompt above", ""),
                "numbered instruction without a detail");
        equal("Step 1. Settings › About phone. Tap Build number seven times", T.onboardingNumberedSpoken(1,
                "Settings › About phone", T.onboardingNumberedDetail("Tap Build number seven times")), "numbered instruction with a detail");
        String h = "Use Chrome and a USB cable", c = "Recommended", d = "Computer · a few minutes";
        equal(h, T.onboardingOptionSpoken(h), "choice, name only");
        equal(h + ". Needs Android 11 or newer", T.onboardingOptionSpokenUnavailable(h), "choice, unavailable");
        equal(h + ". " + d, T.onboardingOptionSpokenDetail(h, d), "choice with detail");
        equal(h + ", " + c, T.onboardingOptionSpokenChip(h, c), "choice with chip");
        equal(h + ", " + c + ". " + d + ". Needs Android 11 or newer",
                T.onboardingOptionSpokenChipDetailUnavailable(h, c, d), "choice with every part");
        equal("Private backup recognised · shared role", T.onboardingIdentityBackupShared(), "shared backup status");
        equal("Private backup recognised · owner role", T.onboardingIdentityBackupOwner(), "owner backup status");
        equal("Command: python3 setup/capture-owner.py --push",
                T.onboardingHelperCommandDescription("python3 setup/capture-owner.py --push"), "command description");
    }

    private static void source() {
        String screen = L10nFiles.read(new File(L10nFiles.root(), "src/com/jjricks/volt/OnboardingScreen.java"));
        check(screen.contains("problem.startsWith(BleController.NOTHING_FOUND)"), "nothing-found compares BleController's id");
        check(!screen.contains("\"No scooter found\""), "no copy of the nothing-found English in the screen");
        check(screen.contains("service.ble.displayMessage()"), "the status line shows the display message");
        check(screen.contains("host.toast(String.valueOf(SetupException.shown(invalid)))"), "a refused identity shows its catalog text");
        check(!screen.contains("invalid.getMessage()"), "no refusal is toasted as its English getMessage()");
    }

    /** The website (setup/web) and the helper's computer are English only, so their quoted words stay English. */
    private static void websiteWords() {
        String xml = L10nFiles.read(new File(L10nFiles.root(), "res/values/strings_onboarding.xml"));
        String[][] kept = {
                {"onboarding_browser_start", "**Start capture**"},
                {"onboarding_browser_save", "**Save to phone Downloads**"},
                {"onboarding_computer_adb_devices", "`adb devices`|**device**"},
                {"onboarding_computer_shows_device", "device"},
                {"onboarding_computer_unauthorized", "unauthorized"},
                {"onboarding_fix_exists_detail", "--output newname"}};
        for (String[] row : kept) {
            Matcher m = Pattern.compile("<string name=\"" + row[0] + "\"([^>]*)>").matcher(xml);
            check(m.find(), row[0] + " is in strings_onboarding.xml");
            check(m.group(1).contains("volt:dnt=\"" + row[1] + "\""), row[0] + " keeps " + row[1] + " untranslated");
        }
        check(!xml.contains("the website shows."), "no note asks for the website's button word in the language");
        check(!xml.contains("use the button word"), "no note asks translators to use a website button word");
    }

    private static int idOf(String key) {
        for (int id = 0; id < T.size(); id++) if (T.key(id).equals(key)) return id;
        return -1;
    }

    private static int count(String text, String token) {
        int n = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) n++;
        return n;
    }

    private static List<String> spans(String text) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]*)`").matcher(text);
        while (m.find()) result.add(m.group(1));
        return result;
    }

    private static void equal(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
