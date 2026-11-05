package com.jjricks.volt;

import java.util.Locale;

/**
 * B16 (Live Stats and charge history): the pure text StatsScreen, StatsCharts and ChargeHistoryDialog
 * now take from the catalog. In English every piece prints what the screens built by concatenation
 * before the batch; with a marked source each one comes from the catalog, not from Java text.
 *   - StatsLabels.row: a raw-marked reading's row name is its short name without " (raw)";
 *   - StatsLabels.windows: the chart-window labels, in StatsCharts.WINDOWS order;
 *   - StatsLabels.unitSymbol: a stored unit code shows as its UnitCode symbol, an unknown code as itself;
 *   - StatsLabels.matchesShown and folded: the shown-language search adds nothing in English;
 *   - the templates and choices against the concatenations they replaced.
 */
public final class StatsCatalogTest {
    private static int checks;

    static final class Marked implements T.Source {
        public String string(int id) { return "«" + EnglishText.INSTANCE.string(id) + "»"; }
        public String plural(int id, long quantity) { return "«" + EnglishText.INSTANCE.plural(id, quantity) + "»"; }
        public String language() { return "en"; }
    }

    public static void main(String[] args) {
        T.Source saved = T.source();
        Locale phone = Locale.getDefault();
        try {
            T.install(null);
            for (Locale locale : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR")}) {
                Locale.setDefault(locale);
                rows();
                windows();
                units();
                search();
                templates();
                numbers();
            }
            Locale.setDefault(Locale.US);
            T.install(new Marked());
            marked();
        } finally {
            T.install(saved);
            Locale.setDefault(phone);
        }
        System.out.println("StatsCatalogTest: " + checks + " checks passed (row names, windows, units, search, "
                + "templates in English and marked)");
    }

    private static void rows() {
        int raw = 0;
        for (Telemetry.Metric metric : new Telemetry().metrics()) {
            String label = ScooterReadingLabels.label(metric.key, metric.label);
            String row = StatsLabels.row(metric.key);
            if (label.endsWith(" (raw)")) {
                raw++;
                same(label.replace(" (raw)", ""), row, metric.key + " row name");
            } else {
                same(null, row, metric.key + " keeps its short name");
            }
        }
        same(45, raw, "raw-marked readings");
        same(null, StatsLabels.row("response_7a"), "a reading outside the catalog keeps its fallback");
    }

    private static void windows() {
        String[] english = {"2 min", "10 min", "1 hour", "6 hours", "24 hours", "7 days", "30 days", "All"};
        String[] shown = StatsLabels.windows();
        same(english.length, shown.length, "window count");
        for (int i = 0; i < english.length; i++) same(english[i], shown[i], "window " + i);
    }

    private static void units() {
        for (UnitCode unit : UnitCode.values()) same(unit.code, StatsLabels.unitSymbol(unit.code), unit + " symbol");
        same("", StatsLabels.unitSymbol(""), "no unit");
        same("rpm", StatsLabels.unitSymbol("rpm"), "an unknown code shows as itself");
    }

    private static void search() {
        same(false, StatsLabels.matchesShown("batt", "Battery"), "English adds no shown-language match");
        same(false, StatsLabels.matchesShown("", "Battery"), "empty query");
        same(true, StatsLabels.folded("bat", "Battery"), "folded match ignores case");
        same(true, StatsLabels.folded("tem", null, "Pack temperature"), "folded match skips a missing text");
        same(false, StatsLabels.folded("  ", "Battery"), "a blank query matches nothing");
        same(false, StatsLabels.folded("zzz", "Battery", "Ride"), "no match");
    }

    private static void templates() {
        String v = "92 %", when = "14:02";
        for (boolean raw : new boolean[]{true, false}) {
            for (boolean live : new boolean[]{true, false}) {
                for (String w : new String[]{"", when}) {
                    String before = v + (raw ? " (raw)" : "") + " · " + (live ? "Live" : "Last seen") + (w.isEmpty() ? "" : " " + w);
                    String after = raw
                            ? live ? w.isEmpty() ? T.statsDetailRawLive(v) : T.statsDetailRawLiveWhen(v, w)
                            : w.isEmpty() ? T.statsDetailRawSeen(v) : T.statsDetailRawSeenWhen(v, w)
                            : live ? w.isEmpty() ? T.statsDetailLive(v) : T.statsDetailLiveWhen(v, w)
                            : w.isEmpty() ? T.statsDetailSeen(v) : T.statsDetailSeenWhen(v, w);
                    same(before, after, "detail raw=" + raw + " live=" + live + " when=" + !w.isEmpty());
                }
            }
        }
        same("12.5 km · Sample", T.statsChartSample("12.5 km"), "chart sample");
        same("12.5 km", T.statsChartLive("12.5 km"), "chart live");
        same("12.5 km · Last seen " + when, T.statsChartLastSeen("12.5 km", when), "chart last seen");
        same("7 mi / 12 km", T.statsRangeDual(Fmt.integer(7), T.unitsMi(), Fmt.integer(12), T.unitsKm()), "range dual");
        same("12 km", T.fmtQuantity(Fmt.integer(12), T.unitsKm()), "range km");
        same("≈ 7 mi / 12 km left", T.statsRangeLeft("7 mi / 12 km"), "range left");
        same("Pack 24 °C", T.statsPack() + "24 °C", "pack label");
        same("Health 92 %", T.statsHealth() + "92 %", "health label");
        same("Pack 24 °C · Health 92 %", T.statsPack() + "24 °C" + T.statsHealthAfter() + "92 %", "health after pack");
        same("est.", T.statsHealthChip(), "health chip");
        same(" estimated", T.statsHealthChipSaid(), "health chip spoken");
        same("100% observed", T.statsChargeFullObserved(), "full observed");
        same("Live Stats section", T.statsSectionsDescription(), "section tabs description");
    }

    /** The chart value keeps "%.0f" or "%.1f" with Locale.US while English, whatever the phone's locale. */
    private static void numbers() {
        for (double n : new double[]{0, 12.34, 999.95, 1000, 1234.5, -3.25}) {
            String before = String.format(Locale.US, Math.abs(n) >= 1000 ? "%.0f" : "%.1f", n);
            same(before, String.format(Fmt.locale(Locale.US), Math.abs(n) >= 1000 ? "%.0f" : "%.1f", n), "chart value " + n);
            same(before + " km/h", T.fmtQuantity(String.format(Fmt.locale(Locale.US), Math.abs(n) >= 1000 ? "%.0f" : "%.1f", n),
                    StatsLabels.unitSymbol("km/h")), "chart value with unit " + n);
        }
        same(Locale.getDefault(), Fmt.locale(Locale.getDefault()), "charge history keeps the phone locale in English");
    }

    private static void marked() {
        same("«Charging»", StatsLabels.row("charging_state"), "marked row name");
        same("«2 min»", StatsLabels.windows()[0], "marked window");
        same("«km/h»", StatsLabels.unitSymbol("km/h"), "marked unit symbol");
        same("rpm", StatsLabels.unitSymbol("rpm"), "an unknown code stays as it is");
        same("«92 % (raw) · Live 14:02»", T.statsDetailRawLiveWhen("92 %", "14:02"), "marked detail");
        same("«12 km · Sample»", T.statsChartSample("12 km"), "marked chart caption");
        same("«7 «mi» / 12 «km»»", T.statsRangeDual("7", T.unitsMi(), "12", T.unitsKm()), "marked range");
    }

    private static void same(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + " under " + Locale.getDefault().toLanguageTag() + ": expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }
}
