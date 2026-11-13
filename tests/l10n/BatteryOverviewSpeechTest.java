package com.jjricks.volt;

import java.util.Locale;

/**
 * The Home battery overview card's spoken range part (BatteryOverviewSpeech): it names the remaining
 * range in every language, even where the drawn range line is the value alone, and leaves the charging
 * line as drawn. Follow-up to PR #399's review, where pt-PT's drawn line became "cerca de 38 km".
 */
public final class BatteryOverviewSpeechTest {
    private static int checks;

    /**
     * A language whose drawn range line is the value alone, as pt-PT's is. The spoken range and charging
     * keys show their key; everything else falls back to English.
     */
    private static final T.Source VALUE_ALONE = new T.Source() {
        @Override public String string(int id) {
            switch (T.key(id)) {
                case "dashboard_range_left": return "%1$s";
                case "dashboard_range_left_spoken": return "⟦dashboard_range_left_spoken⟧ %1$s";
                case "dashboard_charging": return "⟦dashboard_charging⟧";
                default: return null;
            }
        }
        @Override public String plural(int id, long quantity) { return null; }
        @Override public String language() { return "pt"; }
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            english();
            hubBatterySummary();
            T.install(VALUE_ALONE);
            valueAlone();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("BatteryOverviewSpeechTest: " + checks + " checks");
    }

    private static void english() {
        String distance = T.dashboardAbout("31 mi");
        equal("about 31 mi of range left", BatteryOverviewSpeech.line(false, distance), "range, spoken");
        equal("about 31 mi left", T.dashboardRangeLeft(distance), "range, drawn (unchanged)");
        equal("Charging", BatteryOverviewSpeech.line(true, distance), "charging is spoken as drawn");
        equal(T.dashboardCharging(), BatteryOverviewSpeech.line(true, ""), "charging with no range");
        equal("", BatteryOverviewSpeech.line(false, ""), "no range, not charging: no slot");
        equal("Battery 70 percent, about 31 mi of range left",
                M.dashboardOverviewPercentLineCount(70, "70", BatteryOverviewSpeech.line(false, distance)),
                "the card's description");
        equal("Battery 70 percent, about 31 mi of range left, last seen today 14:05",
                M.dashboardOverviewPercentLineSeenCount(70, "70", BatteryOverviewSpeech.line(false, distance), "today 14:05"),
                "the card's description, last seen");
        equal("Battery not reported, about 31 mi of range left",
                M.dashboardOverviewNotReportedLine(BatteryOverviewSpeech.line(false, distance)),
                "the card's description, level not reported");
        String spoken = BatteryOverviewSpeech.line(false, distance);
        check(spoken.indexOf("about") == spoken.lastIndexOf("about"), "the range's own wording is not doubled: " + spoken);
    }

    /**
     * Battery & charging's summary card (HubBatterySummary) speaks a last-seen level in Home's card's words, with the
     * range passed as Home's card passes it: wrapped in "about" (DashboardScreen.aboutRange, the form rangeText gives),
     * so the spoken line says the estimate the drawn line shows. Council review of #389, finding 2.
     */
    private static void hubBatterySummary() {
        String drawn = HubText.batteryLevelRange("62%", "40 mi").render();
        equal("62% · about 40 mi left", drawn, "the card's drawn line");
        String said = BatteryOverviewSpeech.line(false, T.dashboardAbout("40 mi"));
        equal("Battery 62 percent, about 40 mi of range left, last seen 7:02 AM",
                M.dashboardOverviewPercentLineSeenCount(62, "62", said, "7:02 AM"), "the card's spoken line, last seen");
        String card = read("src/com/jjricks/volt/HubBatterySummary.java").replaceAll("\\s+", " ");
        check(card.contains("BatteryOverviewSpeech.line(false, DashboardScreen.aboutRange(range))"),
                "HubBatterySummary passes the range wrapped in \"about\", as Home's card does");
        check(!card.contains("BatteryOverviewSpeech.line(false, range.toString())"), "never the bare range");
        String home = read("src/com/jjricks/volt/DashboardScreen.java").replaceAll("\\s+", " ");
        check(home.contains("static String aboutRange(CharSequence range) { return T.dashboardAbout(range); }"),
                "DashboardScreen.aboutRange is the dashboard_about form");
        check(home.contains("return aboutRange(format(r));") && home.contains("return aboutRange((units.equals(\"km\")"),
                "Home's card's rangeText goes through the same aboutRange");
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path + ": " + e);
        }
    }

    private static void valueAlone() {
        String distance = "cerca de 38 km";
        equal(distance, T.dashboardRangeLeft(distance), "the drawn line is the value alone");
        String spoken = BatteryOverviewSpeech.line(false, distance);
        check(!spoken.equals(T.dashboardRangeLeft(distance)), "the spoken range part is not the drawn line");
        equal("⟦dashboard_range_left_spoken⟧ cerca de 38 km", spoken, "the spoken range part uses its own key");
        equal("⟦dashboard_charging⟧", BatteryOverviewSpeech.line(true, distance), "charging keeps the charging key");
        equal("Battery 70 percent, ⟦dashboard_range_left_spoken⟧ cerca de 38 km",
                M.dashboardOverviewPercentLineCount(70, "70", spoken), "the card's description names the range");
    }
}
