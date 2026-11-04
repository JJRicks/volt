package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * B16 review fixes (both English byte for byte):
 *   - the spoken "expanded"/"collapsed" after a group heading is its own key per heading
 *     (stats_group_readings_*_said for "<group> readings", stats_chart_group_*_said for
 *     "<group> charts"), so a translation can agree the word with its noun, and no shared
 *     stats_state_*_said key is left;
 *   - stats_charge_charger_stopped's "one" item is "once" in English (exactly 1); its note tells
 *     translators to keep %1$d where their "one" category covers more than 1 (21 in Russian).
 */
public final class StatsStateWordsTest {
    private static int checks;

    /** French-like text for the four state words, the rest English: agreement the shared key could not hold. */
    static final class Agreeing implements T.Source {
        public String string(int id) {
            if (id == T.STATS_GROUP_READINGS_EXPANDED_SAID) return "développées";
            if (id == T.STATS_GROUP_READINGS_COLLAPSED_SAID) return "réduites";
            if (id == T.STATS_CHART_GROUP_EXPANDED_SAID) return "développés";
            if (id == T.STATS_CHART_GROUP_COLLAPSED_SAID) return "réduits";
            return EnglishText.INSTANCE.string(id);
        }
        public String plural(int id, long quantity) { return EnglishText.INSTANCE.plural(id, quantity); }
        public String language() { return "fr"; }
    }

    public static void main(String[] args) throws Exception {
        T.Source saved = T.source();
        Locale phone = Locale.getDefault();
        try {
            T.install(null);
            for (Locale locale : new Locale[]{Locale.US, Locale.FRANCE, new Locale("tr", "TR"), new Locale("ru", "RU")}) {
                Locale.setDefault(locale);
                english();
                charger();
            }
            Locale.setDefault(Locale.US);
            keys();
            T.install(new Agreeing());
            agreeing();
            T.install(null);
            note();
        } finally {
            T.install(saved);
            Locale.setDefault(phone);
        }
        System.out.println("StatsStateWordsTest: " + checks + " checks passed (group state words, charger stops)");
    }

    /** What StatsScreen and StatsCharts say, against the concatenations of f529f43. */
    private static void english() {
        for (String name : new String[]{"Battery", "Ride", "Motor & controller", "50% \"x\"", ""}) {
            for (boolean open : new boolean[]{true, false}) {
                same(name + " readings, " + (open ? "expanded" : "collapsed"),
                        T.statsGroupReadingsState(name, open ? T.statsGroupReadingsExpandedSaid() : T.statsGroupReadingsCollapsedSaid()),
                        "readings heading open=" + open);
                same(name + " charts, " + (open ? "expanded" : "collapsed"),
                        T.statsChartGroupState(name, open ? T.statsChartGroupExpandedSaid() : T.statsChartGroupCollapsedSaid()),
                        "charts heading open=" + open);
            }
        }
    }

    /** ChargeHistoryDialog.paused's charger part, against the base ternary, at counts where CLDR "one" differs. */
    private static void charger() {
        for (long n : new long[]{1, 2, 3, 5, 11, 21, 22, 31, 101, 111, 1001, Integer.MAX_VALUE}) {
            String d = n % 2 == 0 ? "12 min" : "1 h 5 min";
            String before = " · charger stopped " + (n == 1 ? "once" : n + " times") + " (" + d + ")";
            same(before, T.statsChargeChargerStopped(n, n, d), "charger stopped " + n);
        }
    }

    private static void keys() {
        int[] ids = {T.STATS_GROUP_READINGS_EXPANDED_SAID, T.STATS_GROUP_READINGS_COLLAPSED_SAID,
                T.STATS_CHART_GROUP_EXPANDED_SAID, T.STATS_CHART_GROUP_COLLAPSED_SAID};
        for (int i = 0; i < ids.length; i++)
            for (int j = i + 1; j < ids.length; j++) same(true, ids[i] != ids[j], "state ids " + i + " and " + j + " differ");
        for (int id = 0; id < T.size(); id++) {
            String key = T.key(id);
            same(false, key.startsWith("stats_state_") && key.endsWith("_said"), key + " is not a shared spoken state word");
        }
    }

    private static void agreeing() {
        same("Battery readings, développées", T.statsGroupReadingsState("Battery", T.statsGroupReadingsExpandedSaid()), "readings agree");
        same("Battery readings, réduites", T.statsGroupReadingsState("Battery", T.statsGroupReadingsCollapsedSaid()), "readings agree closed");
        same("Battery charts, développés", T.statsChartGroupState("Battery", T.statsChartGroupExpandedSaid()), "charts agree");
        same("Battery charts, réduits", T.statsChartGroupState("Battery", T.statsChartGroupCollapsedSaid()), "charts agree closed");
    }

    /** The translator note on the charger-stopped plural: "once" is exactly 1; a wider "one" keeps the count. */
    private static void note() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("res/values/strings_stats.xml")), StandardCharsets.UTF_8);
        int plural = xml.indexOf("<plurals name=\"stats_charge_charger_stopped\"");
        same(true, plural > 0, "the plural is in strings_stats.xml");
        int open = xml.lastIndexOf("<!--", plural), close = xml.indexOf("-->", open);
        same(true, open >= 0 && close > open && xml.substring(close + 3, plural).trim().isEmpty(), "a note sits right before the plural");
        String note = xml.substring(open, close).replaceAll("\\s+", " ");
        for (String want : new String[]{"\"once\" means exactly 1", "must keep %1$d", "21, 31, 41", "Russian"})
            same(true, note.contains(want), "the note says " + want);
        same(" · charger stopped once (%2$s)", EnglishText.INSTANCE.plural(T.STATS_CHARGE_CHARGER_STOPPED, 1), "English one item");
        same(" · charger stopped %1$d times (%2$s)", EnglishText.INSTANCE.plural(T.STATS_CHARGE_CHARGER_STOPPED, 21), "English other item");
    }

    private static void same(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + " under " + Locale.getDefault().toLanguageTag() + ": expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }
}
