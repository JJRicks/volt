package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * F6's own suite. With English installed it compares what the dashboard now shows with the base's expressions, kept in
 * tests/l10n/characterization/DashboardFollowupsEnglishTest.java, over many inputs and five phone locales (the header
 * status, windowName and the recording text are new members that G1 does not prove: this comparison is their proof).
 * With other sources installed it checks that the text comes from the catalog, that a no-break space or a unit-first
 * language keeps the small unit, that search folds, and that the charge schedule's reasons stay English ids.
 */
public final class DashboardFollowupsTest {
    private static int checks;
    private static final Locale[] PHONES = {
            Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG"), new Locale("hi", "IN")};
    private static final int[] COUNTS = {0, 1, 2, 3, 5, 11, 21, 100, 1000, 1234567, -1};

    public static void main(String[] args) {
        Locale phone = Locale.getDefault();
        try {
            for (Locale locale : PHONES) {
                Locale.setDefault(locale);
                sentences();
                numbers();
                search();
                readingSplits();
            }
        } finally {
            Locale.setDefault(phone);
        }
        scaleSpokenStaysItsOwnKeys();
        fromTheCatalog();
        noBreakSpaceKeepsTheUnit();
        unitFirstKeepsTheUnit();
        folds();
        exampleCopies();
        reasons();
        System.out.println("DashboardFollowupsTest: " + checks + " checks passed (status sentences, counts, "
                + "formats, search folding, reading parts, example copies, charge reasons)");
    }

    private static void sentences() {
        String recent = "2 min ago", when = "Nov 4, 3:15 PM";
        for (String mode : new String[]{null, "Sport", "Eco", "D"}) {
            same(DashboardFollowupsEnglishTest.statusLine(true, false, mode, 0, recent), DashboardWords.statusLine(true, mode, null));
            same(DashboardFollowupsEnglishTest.statusLine(true, false, mode, 9, recent), DashboardWords.statusLine(true, mode, null));
            same(DashboardFollowupsEnglishTest.statusSpoken(true, false, mode, 0, when), DashboardWords.statusSpoken(true, false, mode, null));
            same(DashboardFollowupsEnglishTest.statusSpoken(true, false, mode, 9, when), DashboardWords.statusSpoken(true, false, mode, null));
        }
        // mode is only ever set while ready, so these are the states left.
        same(DashboardFollowupsEnglishTest.statusLine(false, false, null, 0, recent), DashboardWords.statusLine(false, null, null));
        same(DashboardFollowupsEnglishTest.statusLine(false, false, null, 9, recent), DashboardWords.statusLine(false, null, recent));
        same(DashboardFollowupsEnglishTest.statusSpoken(false, false, null, 0, when), DashboardWords.statusSpoken(false, false, null, null));
        same(DashboardFollowupsEnglishTest.statusSpoken(false, false, null, 9, when), DashboardWords.statusSpoken(false, false, null, when));
        same(DashboardFollowupsEnglishTest.statusSpoken(false, true, null, 0, when), DashboardWords.statusSpoken(false, true, null, null));
        same(DashboardFollowupsEnglishTest.statusSpoken(false, true, null, 9, when), DashboardWords.statusSpoken(false, true, null, null));

        for (long ms : new long[]{0, 59999, 60000, 120000, 600000, 3599999, 3600000, 7200000, 86399999, 86400000,
                172800000, 90 * 86400000L})
            same(DashboardFollowupsEnglishTest.windowName(ms), DashboardWords.windowName(ms));
        List<List<String>> lists = new ArrayList<>();
        lists.add(new ArrayList<String>());
        lists.add(Arrays.asList("1.2 km"));
        lists.add(Arrays.asList("1.2 km", "14 km/h"));
        lists.add(Arrays.asList("1.2 km", "14 km/h", "32 Wh"));
        for (List<String> parts : lists)
            for (String clock : new String[]{"0:05", "1:02:03"})
                same(DashboardFollowupsEnglishTest.recordingSpoken(clock, parts), DashboardWords.recordingSpoken(clock, parts));

        for (int n : COUNTS) {
            for (boolean live : new boolean[]{false, true})
                same(DashboardFollowupsEnglishTest.windowValue(n, live), !live ? T.dashboardEstimateWindowDays(n, n)
                        : T.dashboardEstimateWindowLive(T.dashboardEstimateWindowDays(n, n)));
            if (n > 0) {
                same(DashboardFollowupsEnglishTest.columnSpoken(n, true), T.dashboardColumnCount(n, n));
                same(DashboardFollowupsEnglishTest.columnSpoken(n, false), T.dashboardColumnCountNeedsWider(T.dashboardColumnCount(n, n)));
            }
            same(DashboardFollowupsEnglishTest.slotSummary(true, true, n, "just now"), T.dashboardSlotSummary(n, n, "just now"));
            for (int most : COUNTS)
                same(DashboardFollowupsEnglishTest.countOfSelected(n, most), T.dashboardSelectedOfMost(n, Fmt.integer(n), Fmt.integer(most)));
            same(DashboardFollowupsEnglishTest.rejected(n, null), Fmt.integer(n));
            same(DashboardFollowupsEnglishTest.rejected(n, "Too short"), T.dashboardRejectedReason(Fmt.integer(n), "Too short"));
        }
        same(DashboardFollowupsEnglishTest.slotSummary(false, true, 0, "x"), T.dashboardSlotUnreadable());
        same(DashboardFollowupsEnglishTest.slotSummary(false, false, 0, "x"), T.dashboardSlotEmpty());
        same(DashboardFollowupsEnglishTest.scaleSpoken(true, ""), T.dashboardScaleMaximum());
        same(DashboardFollowupsEnglishTest.scaleSpoken(false, ""), T.dashboardScaleMinimum());
        for (String unit : new String[]{"km/h", "mph", "°C", "V"}) {
            same(DashboardFollowupsEnglishTest.scaleSpoken(true, unit), T.dashboardScaleMaximumIn(unit));
            same(DashboardFollowupsEnglishTest.scaleSpoken(false, unit), T.dashboardScaleMinimumIn(unit));
        }
    }

    private static void scaleSpokenStaysItsOwnKeys() {
        same("Scale maximum in km/h", T.dashboardScaleMaximumIn("km/h"));
        same("1 rides · 2 points used", T.dashboardCapacitySummary(T.dashboardCapacityRides(1, Fmt.integer(1)),
                T.dashboardCapacityPointsUsed(2, Fmt.decimal(Locale.US, 2, 0))));
    }

    private static void numbers() {
        for (double value : new double[]{0, .004, .005, 1, 1.5, 2.675, 480, 1000.125, 12345.678}) {
            same(DashboardFollowupsEnglishTest.ratingValue(value, 0), rating(value, 0));
            same(DashboardFollowupsEnglishTest.ratingValue(value, 480), rating(value, 480));
            same(DashboardFollowupsEnglishTest.scaleSummary(value, "V"),
                    T.fmtQuantity(String.format(Fmt.locale(Locale.US), "%.2f", value), "V"));
            same(DashboardFollowupsEnglishTest.scaleSummary(value, ""),
                    T.fmtQuantity(String.format(Fmt.locale(Locale.US), "%.2f", value), ""));
            same(DashboardFollowupsEnglishTest.pointsValue(true, value, 0, 0),
                    String.format(Fmt.locale(Locale.US), "%.0f", value));
            same(DashboardFollowupsEnglishTest.pointsValue(false, 0, value, 40),
                    String.format(Fmt.locale(Locale.US), "%.0f / %.0f", Math.floor(value), 40d));
            for (int rides : COUNTS) {
                same(DashboardFollowupsEnglishTest.ridesPoints(rides, value),
                        T.dashboardCapacitySummary(T.dashboardCapacityRides(rides, Fmt.integer(rides)),
                                T.dashboardCapacityPointsUsed(Math.round(value), Fmt.decimal(Locale.US, value, 0))));
                same(DashboardFollowupsEnglishTest.ridesRange(rides, value, value + 40.5),
                        T.dashboardCapacitySummary(T.dashboardCapacityRides(rides, Fmt.integer(rides)),
                                T.fmtRange(Fmt.decimal(Locale.US, value, 0), Fmt.decimal(Locale.US, value + 40.5, 0), T.unitsWattHour())));
            }
        }
        for (long seconds : new long[]{0, 5, 59, 60, 3599, 3600, 3661, 86400, 360000})
            same(DashboardFollowupsEnglishTest.clock(seconds), seconds >= 3600
                    ? String.format(Fmt.locale(Locale.US), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    : String.format(Fmt.locale(Locale.US), "%d:%02d", seconds / 60, seconds % 60));
    }

    private static String rating(double value, double advertisedWh) {
        return T.dashboardRatingValue(Fmt.decimal(Locale.US, value, 2), T.unitsWattHour(),
                advertisedWh == 0 ? T.dashboardRatingModel() : "");
    }

    private static void search() {
        String[] typed = {"", " ", "lock", "LOCK", " Lo", "k s", "I", "ı", "İ", "Security", "é", "trip reset"};
        for (String query : typed)
            for (String[] entry : new String[][]{{"Lock", "Security"}, {"Trip Reset", "Riding"}, {"INFO", "İzmir"}, {"Café", "Éa"}}) {
                String folded = SearchFold.fold(query).trim();
                same(DashboardFollowupsEnglishTest.shortcutMatch(query, entry[0], entry[1]),
                        folded.isEmpty() || SearchFold.holds(entry[0], entry[1], folded)
                                || SearchFold.holdsShown(entry[0], entry[1], folded));
                check(!SearchFold.holdsShown(entry[0], entry[1], folded), "in English a shown name adds nothing");
            }
    }

    private static void readingSplits() {
        String[] values = {"", " ", "--", "412 W", "85%", "85 %", "12.5 km/h", "7.8 mph", "21.0 °C", "70 °F", "7.8 mi / 12.5 km",
                "7.8 mph / 12.5 km/h", "ON", "Sport", "3 cells", "3 charges", "12 toolong", "A B", "5 ", " 5", "5  W", "1 2", "12.5 km",
                "7.77 mi", "3.7 V", "1.2 Ah", "60 %", "1,234 W", "12 a/b", "12 9"};
        for (String value : values) {
            same(Arrays.toString(DashboardFollowupsEnglishTest.unitSpan(value)), Arrays.toString(ReadingUnits.smallPart(value, null)));
            same(Arrays.toString(DashboardFollowupsEnglishTest.gaugeParts(value)), Arrays.toString(ReadingUnits.gaugeParts(value, null)));
        }
        for (double kmh : new double[]{0, 5, 12.5, 25, 99.9})
            for (String units : new String[]{"km", "mi", "dual"}) {
                Formatted parts = DisplayUnits.speedQuantity(kmh, units);
                String text = DisplayUnits.speed(kmh, units);
                same(text, parts.toString());
                same(Arrays.toString(DashboardFollowupsEnglishTest.unitSpan(text)), Arrays.toString(ReadingUnits.smallPart(text, parts)));
                same(Arrays.toString(DashboardFollowupsEnglishTest.gaugeParts(text)), Arrays.toString(ReadingUnits.gaugeParts(text, parts)));
            }
        for (double celsius : new double[]{-20, 0, 21.4, 21.5, 80})
            for (String unit : new String[]{"c", "f"}) {
                Formatted parts = DisplayTemperature.quantity(celsius, unit, 0);
                String text = DisplayTemperature.format(celsius, unit, 0);
                same(text, parts.toString());
                same(Arrays.toString(DashboardFollowupsEnglishTest.unitSpan(text)), Arrays.toString(ReadingUnits.smallPart(text, parts)));
                same(Arrays.toString(DashboardFollowupsEnglishTest.gaugeParts(text)), Arrays.toString(ReadingUnits.gaugeParts(text, parts)));
            }
    }

    private static T.Source language(final int id, final String text) {
        return new T.Source() {
            public String string(int asked) { return asked == id ? text : null; }
            public String plural(int asked, long quantity) { return null; }
            public String language() { return "de"; }
        };
    }

    private static void fromTheCatalog() {
        T.install(new T.Source() {
            public String string(int id) { return T.isPlural(id) ? null : "‹" + T.key(id) + "›"; }
            public String plural(int id, long quantity) { return T.isPlural(id) ? "‹" + T.key(id) + "›" : null; }
            public String language() { return "de"; }
        });
        try {
            same("‹dashboard_status_connected›", DashboardWords.statusLine(true, null, null));
            same("‹dashboard_status_connected_mode›", DashboardWords.statusLine(true, "Sport", null));
            same("‹dashboard_status_disconnected›", DashboardWords.statusLine(false, null, null));
            same("‹dashboard_status_disconnected_seen›", DashboardWords.statusLine(false, null, "x"));
            same("‹dashboard_status_spoken_connected›", DashboardWords.statusSpoken(true, false, null, null));
            same("‹dashboard_status_spoken_connected_mode›", DashboardWords.statusSpoken(true, false, "Sport", null));
            same("‹dashboard_status_spoken_connecting›", DashboardWords.statusSpoken(false, true, null, null));
            same("‹dashboard_status_spoken_disconnected›", DashboardWords.statusSpoken(false, false, null, null));
            same("‹dashboard_status_spoken_disconnected_seen›", DashboardWords.statusSpoken(false, false, null, "x"));
            same("‹dashboard_window_name_minutes›", DashboardWords.windowName(60000));
            same("‹dashboard_window_name_hours›", DashboardWords.windowName(3600000));
            same("‹dashboard_window_name_days›", DashboardWords.windowName(86400000));
            same("‹dashboard_recording_spoken›", DashboardWords.recordingSpoken("0:05", new ArrayList<String>()));
            same("‹dashboard_recording_spoken_parts›", DashboardWords.recordingSpoken("0:05", Arrays.asList("a", "b")));
        } finally {
            T.install(null);
        }
    }

    private static void noBreakSpaceKeepsTheUnit() {
        for (String space : new String[]{" ", " "}) {
            T.install(language(T.FMT_QUANTITY, "%1$s" + space + "%2$s"));
            try {
                Formatted mph = DisplayUnits.speedQuantity(25, "mi");
                String text = mph.toString();
                check(text.contains(space), "the language's space is in " + text);
                same(Arrays.toString(new int[]{mph.unitStart(0) - 1, text.length()}), Arrays.toString(ReadingUnits.smallPart(text, mph)));
                check(!ReadingUnits.gaugeParts(text, mph)[1].isEmpty(), "a gauge keeps the unit");
                same(text.substring(mph.unitStart(0)), ReadingUnits.gaugeParts(text, mph)[1]);
                String plain = "12.50" + space + "km";
                same(Arrays.toString(new int[]{5, plain.length()}), Arrays.toString(ReadingUnits.smallPart(plain, null)));
                same("[12.50, km, ]", Arrays.toString(ReadingUnits.gaugeParts(plain, null)));
                String dual = "7.77" + space + "mi / 12.50" + space + "km";
                same(Arrays.toString(new String[]{"7.77", "mi", "12.50" + space + "km"}), Arrays.toString(ReadingUnits.gaugeParts(dual, null)));
            } finally {
                T.install(null);
            }
        }
    }

    private static void unitFirstKeepsTheUnit() {
        T.install(language(T.FMT_QUANTITY, "%2$s %1$s"));
        try {
            Formatted mph = DisplayUnits.speedQuantity(25, "mi");
            String text = mph.toString();
            same(Arrays.toString(new int[]{mph.unitStart(0), mph.numberStart(0)}), Arrays.toString(ReadingUnits.smallPart(text, mph)));
            String[] gauge = ReadingUnits.gaugeParts(text, mph);
            same(text.substring(mph.numberStart(0), mph.numberEnd(0)), gauge[0]);
            same(text.substring(mph.unitStart(0), mph.unitEnd(0)), gauge[1]);
            same(null, ReadingUnits.smallPart(DisplayUnits.speedQuantity(25, "km").toString(), DisplayUnits.speedQuantity(25, "km")));
        } finally {
            T.install(null);
        }
    }

    private static void folds() {
        check(!SearchFold.holds("Café", "cafe"), "English folding keeps the accent, as the base did");
        same("cafe", SearchFold.fold("CAFÉ", Locale.FRANCE, false));
        same("ea", SearchFold.fold("Éa", Locale.FRANCE, false));
        same("uber", SearchFold.fold("ÜBER", Locale.GERMANY, false));
        same("ı", SearchFold.fold("I", new Locale("tr"), false));
        same("i", SearchFold.fold("İ", new Locale("tr"), false));
        same("café", SearchFold.fold("CAFÉ", Locale.ROOT, true));
        same(Boolean.TRUE, SearchFold.fold("Bremse Fahren", Locale.GERMANY, false).contains(SearchFold.fold("SE FA", Locale.GERMANY, false)));
        check(Fmt.legacy() && Fmt.locale(Locale.ROOT).equals(Locale.ROOT), "fold's English path is lower case by ROOT");
    }

    /** DashboardConfig needs Android, so android-tests/DashboardSlotsValidation runs a real copy; here, who makes it. */
    private static void exampleCopies() {
        check(DashboardExampleIds.is(DashboardExampleIds.create()), "a copy's new example id is an example id");
        String screen = read("src/com/jjricks/volt/DashboardScreen.java");
        check(screen.contains("AllWidgetsDashboard.emptyCopy(widget)"), "duplicateWidget starts from emptyCopy");
        String model = read("src/com/jjricks/volt/AllWidgetsDashboard.java");
        check(model.contains("exampleTitle(widget) != null || exampleNote(widget) != null\n"
                + "                ? new DashboardConfig.Widget(DashboardExampleIds.create(), widget.type)"), "only a widget showing the example's words");
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void reasons() {
        String[][] ids = {
                {"Wait for the current change to finish", "common_wait_current_change"},
                {"Wait for the schedule test to finish", "charging_schedule_wait_test"},
                {"Disable Charge done by first", "charging_schedule_disable_done_by"},
                {"Connect a charger first", "common_connect_charger_first"},
                {"Choose a scooter first", "charging_schedule_choose_scooter"},
                {"Connect the scooter first", "charging_schedule_connect_scooter"},
                {"Not available on this model", "charging_schedule_not_on_model"},
                {"Turn off Charge done by first", "charging_schedule_turn_off_done_by"},
                {"Turn off Scheduled charging first", "charging_schedule_turn_off_scheduled"},
                {"Wait for fresh charging readings", "charging_schedule_wait_readings"},
                {"Battery is already at its charge limit", "charging_schedule_at_limit"}};
        for (String[] id : ids) same(id[0], ChargeScheduleController.reasonText(id[0]));
        same(null, ChargeScheduleController.reasonText(null));
        same("Some other reason", ChargeScheduleController.reasonText("Some other reason"));
        T.install(new T.Source() {
            public String string(int id) { return "‹" + T.key(id) + "›"; }
            public String plural(int id, long quantity) { return null; }
            public String language() { return "de"; }
        });
        try {
            for (String[] id : ids) same("‹" + id[1] + "›", ChargeScheduleController.reasonText(id[0]));
            same(null, ChargeScheduleController.reasonText(null));
            same("Some other reason", ChargeScheduleController.reasonText("Some other reason"));
        } finally {
            T.install(null);
        }
    }

    private static void same(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
}
