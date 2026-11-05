package com.jjricks.volt;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * B19's own suite: MainActivity's catalog text against the base English MainActivityTextTest.Base
 * pinned before the batch (G5), in English and under a key-echo text source.
 *
 * G2 (LedgerTest) proves each ledger row's template; this adds what a row cannot carry: every template
 * of a statement whose row names only one (each branch here calls the same keys, in the same shape, as
 * MainActivity), the short-hours form over every option value, the reasons ChargeScheduleText knows, and
 * the width thresholds of TextFit on Saira regular over every English label that reaches them.
 */
public final class MainTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("en", "IN"), new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                english();
                fits();
            }
            Locale.setDefault(Locale.US);
            T.install(null);
            separation();
            T.install(ECHO);
            follows();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("MainTextTest: " + checks + " checks passed (MainActivity keys against the base English, "
                + "short hours, charge reasons, width thresholds and the key-echo source)");
    }

    private static void english() {
        int[] numbers = {0, 1, 2, 9, 10, 999, 1000, 1023, 1024, 1536, 1048576};
        for (int n : numbers) {
            equal(MainActivityTextTest.Base.size(n), n < 1024 ? T.mainReportSizeBytes(n, n) : T.mainReportSizeKb(Fmt.decimal(Locale.ROOT, n / 1024.0, 1)), "size " + n);
            equal(MainActivityTextTest.Base.minutes(n), T.mainScheduleMeasuringValue(Fmt.integer(n)), "minutes " + n);
            equal(MainActivityTextTest.Base.below(n), T.mainAlertBelow(Fmt.integer(n)), "below " + n);
            equal(MainActivityTextTest.Base.measuringToast(n), T.mainScheduleMeasuringToast(Fmt.integer(n)), "toast " + n);
            equal(MainActivityTextTest.Base.measuringBody(n), T.mainScheduleMeasuringBody(Fmt.integer(n)), "body " + n);
            equal(MainActivityTextTest.Base.dualUnit(n), T.mainSpeedUnitDual(Fmt.integer(n)), "dual " + n);
            for (String units : new String[]{"km", "mi", "dual"}) {
                equal(MainActivityTextTest.Base.number(n, units), number(n, units), "number " + n);
                equal(MainActivityTextTest.Base.spoken(n, units), spoken(n, units), "spoken " + n);
                equal(MainActivityTextTest.Base.setTop(n, units), T.mainSpeedSet(spoken(n, units)), "set " + n);
                for (boolean ready : new boolean[]{false, true}) {
                    String v = spoken(n, units);
                    equal(MainActivityTextTest.Base.topSpeed(n, units, ready), ready ? T.mainSpeedValue(v) : T.mainSpeedValueSeen(v), "top " + n);
                }
            }
            equal(MainActivityTextTest.Base.scanStatus(false, n), n == 0 ? T.mainScanNone() : n == 1 ? T.mainScanFoundOne() : T.mainScanFoundMany(n, n), "scan " + n);
            equal(MainActivityTextTest.Base.results(n), T.mainSearchResults(n, n), "results " + n);
            for (boolean isTry : new boolean[]{false, true}) {
                String want = MainActivityTextTest.Base.banner(isTry, "Lights", n);
                String got = isTry ? (n > 1 ? T.mainTestTryMore("Lights", Fmt.integer(n - 1)) : T.mainTestTry("Lights"))
                        : (n > 1 ? T.mainTestTestMore("Lights", Fmt.integer(n - 1)) : T.mainTestTest("Lights"));
                equal(want, got, "banner " + n);
            }
        }
        equal(MainActivityTextTest.Base.scanStatus(true, 3), T.bleStatusSearching(), "searching");
        equal(MainActivityTextTest.Base.topSpeed(null, "km", true), T.mainSpeedNotReported(), "not reported");
        equal(MainActivityTextTest.Base.topSpeed(null, "km", false), T.mainSpeedNotReportedSeen(), "not reported, seen");
        for (String known : new String[]{null, "Locked", "Unlocked"}) {
            equal(MainActivityTextTest.Base.lockDescription(known), known == null ? T.mainLockStateUnknown() : T.mainLockLastKnownFull(known.toLowerCase(Locale.ROOT)), "lock " + known);
            for (boolean connecting : new boolean[]{false, true}) {
                equal(MainActivityTextTest.Base.lastKnown(known, connecting), known == null ? "" : connecting ? T.mainLockLastKnownWord(known.toLowerCase(Locale.ROOT)) : T.mainLockLastKnown(), "last " + known);
            }
        }
        equal(MainActivityTextTest.Base.connectingTo("Ann", "Locked"), T.mainLockConnectingTo("Ann", "Locked"), "connecting");
        equal(MainActivityTextTest.Base.lockSwitch(null), T.mainLockSwitchUnavailable(), "switch");
        equal(MainActivityTextTest.Base.lockSwitch(true), T.mainLockSwitchLocked(), "switch locked");
        equal(MainActivityTextTest.Base.lockSwitch(false), T.mainLockSwitchUnlocked(), "switch unlocked");
        equal(MainActivityTextTest.Base.line("A", "B"), T.mainLabelValue("A", "B"), "line");
        for (boolean saved : new boolean[]{false, true}) {
            for (boolean match : new boolean[]{false, true}) {
                equal(MainActivityTextTest.Base.scanOption("N", "S", saved, match, "aa:bb"), T.mainScanOption("N", "S", !saved ? "" : match ? T.mainScanStateYours() : T.mainScanStateSaved(), "aa:bb"), "scan option");
            }
        }
        equal(MainActivityTextTest.Base.trying("Headlight"), T.mainTestTrying("Headlight".toLowerCase(Locale.ROOT)), "trying");
        equal(MainActivityTextTest.Base.review(true), T.mainTestReviewTry(), "review try");
        equal(MainActivityTextTest.Base.review(false), T.mainControlsReviewUnfinished(), "review test");
        equal(MainActivityTextTest.Base.sent("14:02"), T.mainRowClockValue("14:02"), "sent");
        equal(MainActivityTextTest.Base.auto("High-speed"), T.mainRowAutoFamily("High-speed"), "auto");
        for (boolean low : new boolean[]{false, true}) {
            String name = low ? T.mainAlertLow() : T.mainAlertCharge();
            equal(MainActivityTextTest.Base.alertRow(low, "V", null), T.mainLabelValue(name, "V"), "alert");
            equal(MainActivityTextTest.Base.alertRow(low, "V", "S"), T.mainLabelValueDetail(name, "V", "S"), "alert sub");
        }
        equal(MainActivityTextTest.Base.measure(true), T.mainScheduleMeasureBody(), "measure");
        equal(MainActivityTextTest.Base.measure(false), T.mainScheduleMeasureBodyForeground(), "measure foreground");
        equal(MainActivityTextTest.Base.testBody("14:02"), T.mainScheduleTestBody("14:02"), "test body");
        equal(MainActivityTextTest.Base.testRow("", null), T.mainScheduleTestRow(), "test row");
        equal(MainActivityTextTest.Base.testRow("", "B"), T.mainLabelValue(T.mainScheduleTestRow(), "B"), "test row blocked");
        equal(MainActivityTextTest.Base.testRow("V", null), T.mainLabelValue(T.mainScheduleTestRow(), "V"), "test row value");
        equal(MainActivityTextTest.Base.testRow("V", "B"), T.mainScheduleTestRowBoth("V", "B"), "test row both");
        for (boolean time : new boolean[]{false, true}) {
            String name = time ? T.mainScheduleDoneByTime() : T.mainScheduleDoneBy();
            equal(MainActivityTextTest.Base.doneRow(time, "V", null), T.mainLabelValue(name, "V"), "done");
            equal(MainActivityTextTest.Base.doneRow(time, "V", "B"), T.mainLabelValueDetail(name, "V", "B"), "done blocked");
        }
        equal(MainActivityTextTest.Base.range("Auto"), T.mainRowRangeDescription("Auto"), "range");
        String[] states = {T.mainRowConfirmingPart(), T.mainRowStateNote("rejected"), T.mainRowStopPart(), T.mainRowStateNote("note"), ""};
        equal(MainActivityTextTest.Base.row("L", "S", true, "Rejected", true, "Note"), T.mainLabelValueState("L", "S", states[0]), "row confirming");
        equal(MainActivityTextTest.Base.row("L", "S", false, "Rejected", true, "Note"), T.mainLabelValueState("L", "S", states[1]), "row chip");
        equal(MainActivityTextTest.Base.row("L", "S", false, null, true, "Note"), T.mainLabelValueState("L", "S", states[2]), "row stop");
        equal(MainActivityTextTest.Base.row("L", "S", false, null, false, "Note"), T.mainLabelValueState("L", "S", states[3]), "row note");
        equal(MainActivityTextTest.Base.row("L", "S", false, null, false, null), T.mainLabelValueState("L", "S", states[4]), "row plain");
        for (String[] pair : new String[][]{{null, null}, {"B", null}, {null, "N"}, {"B", "N"}, {"B", "B"}}) {
            String blocked = pair[0], note = pair[1];
            String got = note == null || note.equals(blocked)
                    ? (blocked == null ? T.mainLabelValue("L", "S") : T.mainLabelValueDetail("L", "S", blocked))
                    : blocked == null ? T.mainLabelValueDetail("L", "S", note) : T.mainLabelValueDetails("L", "S", blocked, note);
            equal(MainActivityTextTest.Base.schedule("L", "S", blocked, note), got, "schedule row");
        }
        equal(MainActivityTextTest.Base.joinNote("T", "N"), T.mainJoinNote("T", "N"), "join");
        equal(MainActivityTextTest.Base.invite("25462"), T.mainReportInviteBody("25462"), "invite");
        equal(MainActivityTextTest.Base.to("a@b"), T.mainReportTo("a@b"), "to");
        equal(MainActivityTextTest.Base.attaches("f", "2 KB"), T.mainReportPreviewBody("f", "2 KB"), "attaches");
        equal(MainActivityTextTest.Base.learned("9 mi", 80, null), T.mainRangeSummary("9 mi", Fmt.integer(80)), "learned");
        equal(MainActivityTextTest.Base.learned("9 mi", 80, "Eco"), T.mainRangeSummaryLikely("9 mi", Fmt.integer(80), "Eco"), "learned likely");
        equal(MainActivityTextTest.Base.replaces("Ann"), T.mainImportReplaceBody("Ann"), "replaces");

        // Lock duration: every option's short form against the base's replace on its English label.
        for (String label : new String[]{"6 hours", "12 hours", "24 hours"}) {
            int value = label.equals("6 hours") ? 1 : label.equals("12 hours") ? 2 : 3;
            equal(MainActivityTextTest.Base.shortHours(label), T.mainValueHoursShort(Fmt.integer(DisplayUnits.lockHours(value))), "hours " + label);
        }
        check(DisplayUnits.lockHours(0) == 0 && DisplayUnits.lockHours(4) == 0, "a value with no hours falls back to the label");
        for (ScooterCommands.Feature f : features()) {
            if (!f.key.equals("lock_expiry") || f.optionValues == null) continue;
            for (int i = 0; i < f.optionValues.length; i++) {
                int hours = DisplayUnits.lockHours(f.optionValues[i]);
                if (hours > 0) equal(MainActivityTextTest.Base.shortHours(f.options[i]), T.mainValueHoursShort(Fmt.integer(hours)), f.key + " option " + i);
            }
        }

        // Charge schedule reasons: English stays what the controller said; what no table knows passes through.
        check(ChargeScheduleText.shown(null) == null, "null reason");
        for (String reason : new String[]{"Wait for the current scooter change", "Connect a charger first", "Something else",
                "Charging now — the calculated start time has passed", "Volt stopped before it finished; check that Scheduled charging is off",
                "Scheduled charging is off; restoring Charge start was not confirmed",
                T.mainScheduleOffRestoreDoneBy("Connect a charger first"), T.mainScheduleOffRestoreScheduled("Something else")}) {
            equal(reason, ChargeScheduleText.shown(reason), "reason");
        }
    }

    /** The same shape as Top speed's private helpers in MainActivity. */
    private static String number(int kmh, String units) {
        return "km".equals(units) ? Fmt.integer(kmh) : Fmt.integer(Math.round(kmh / 1.609344));
    }

    private static String spoken(int kmh, String units) {
        return "km".equals(units) ? T.mainSpeedSpokenKm(Fmt.integer(kmh)) : T.mainSpeedSpokenMi(number(kmh, units), Fmt.integer(kmh));
    }

    /** In English the layouts keep their character counts, whatever the measured width says. */
    private static void fits() {
        check(!TextFit.over(10, 10, 5.12, 9999f, 1f), "ten characters stay narrow in English");
        check(TextFit.over(11, 10, 5.12, 0f, 1f), "eleven characters are wide in English");
        check(!TextFit.over(18, 18, 9.2, 9999f, 1f), "eighteen characters stay compact in English");
        check(TextFit.over(19, 18, 9.2, 0f, 1f), "nineteen characters are not compact in English");
    }

    /** The em thresholds another language measures with draw the same line the counts draw, on every English label. */
    private static void separation() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Font regular = Font.createFont(Font.TRUETYPE_FONT, new File("res/font/saira_regular.ttf")).deriveFont(100f);
        FontRenderContext context = new FontRenderContext(null, true, true);
        TreeSet<String> labels = new TreeSet<>();
        for (ScooterCommands.Feature f : features()) {
            if (f.options != null) for (String option : f.options) labels.add(option);
            if (f.optionValues != null && f.optionValues.length > 0) {
                for (int value : f.optionValues) addValue(labels, f, value);
            } else {
                for (int value = f.min, end = Math.min(f.max, f.min + 600); value <= end; value += Math.max(1, f.step)) addValue(labels, f, value);
            }
        }
        for (java.lang.reflect.Field field : ModelRegistry.class.getDeclaredFields()) {
            if (field.getType() != FeatureVariant.class) continue;
            field.setAccessible(true);
            FeatureVariant variant = (FeatureVariant) field.get(null);
            if (variant.labels != null) for (String label : variant.labels) labels.add(label);
        }
        check(labels.size() > 500, "the labels were found: " + labels.size());
        for (String label : labels) {
            float width = (float) regular.getStringBounds(label, context).getWidth();
            check((width > 5.12 * 100) == (label.length() > 10), "5.12 em and 10 characters disagree on \"" + label + "\" (" + width + ")");
            check((width > 9.2 * 100) == (label.length() > 18), "9.2 em and 18 characters disagree on \"" + label + "\" (" + width + ")");
        }
    }

    private static void addValue(TreeSet<String> labels, ScooterCommands.Feature f, int value) {
        for (String units : new String[]{"mi", "km", "dual"}) labels.add(DisplayUnits.featureValue(f, value, units));
    }

    private static List<ScooterCommands.Feature> features() {
        List<ScooterCommands.Feature> all = new ArrayList<>(ScooterCommands.catalog());
        all.addAll(ScooterCommands.oemOnly());
        return all;
    }

    private static void follows() {
        String[][] shown = {
                {T.mainLabelValue("a", "b"), "main_label_value"}, {T.mainLabelValueDetail("a", "b", "c"), "main_label_value_detail"},
                {T.mainLabelValueDetails("a", "b", "c", "d"), "main_label_value_details"}, {T.mainLabelValueState("a", "b", "c"), "main_label_value_state"},
                {T.mainScanOption("a", "b", "c", "d"), "main_scan_option"}, {T.mainScanStateYours(), "main_scan_state_yours"}, {T.mainScanStateSaved(), "main_scan_state_saved"},
                {T.mainLockSwitchLocked(), "main_lock_switch_locked"}, {T.mainLockSwitchUnlocked(), "main_lock_switch_unlocked"},
                {T.mainValueHoursShort("6"), "main_value_hours_short"}, {T.mainScheduleTestRowBoth("a", "b"), "main_schedule_test_row_both"},
                {T.mainRangeStateRetry(), "main_range_state_retry"}, {T.mainSearchResults(2, 2), "main_search_results"},
                {T.mainReportSizeKb("1"), "main_report_size_kb"}, {T.mainSpeedNotReportedSeen(), "main_speed_not_reported_seen"},
        };
        for (String[] s : shown) check(s[0].startsWith("⟦" + s[1] + "⟧"), s[1] + " follows the source: " + s[0]);
        // A reason the controller says in English shows in the installed language; other text passes through.
        check(ChargeScheduleText.shown(M.commonConnectChargerFirst().english()).startsWith("⟦common_connect_charger_first⟧"), "a known reason follows the source");
        equal("typed", ChargeScheduleText.shown("typed"), "an unknown reason passes through");
        controllerSentences();
        // Once another language ships, its layouts measure (Fmt.legacy() is true until then, whatever the source says).
        if (!Fmt.legacy()) {
            check(TextFit.over(1, 10, 5.12, 600f, 100f), "wide by width");
            check(!TextFit.over(99, 10, 5.12, 100f, 100f), "narrow by width");
        }
    }

    /**
     * Every sentence ChargeScheduleController finishes with reaches a screen through ChargeScheduleText, so none may
     * stay in English under a translated source; the schedule test's dialog and notice pass through it as well.
     */
    private static void controllerSentences() {
        String controller = source("src/com/jjricks/volt/ChargeScheduleController.java");
        Matcher sentence = Pattern.compile("(?:completion\\.accept\\(|deliver\\(t,[^;]*?,failure,)\"([^\"]+)\"\\)").matcher(controller);
        int found = 0;
        for (; sentence.find(); found++) {
            String english = sentence.group(1), shown = ChargeScheduleText.shown(english);
            check(shown.startsWith("⟦"), "the controller's sentence follows the source: " + english);
        }
        check(found > 10, "the controller's sentences were found: " + found);
        check(ChargeScheduleText.shown("Scheduled charging is off; restoring Charge start was not confirmed").startsWith("⟦main_schedule_off_start_scheduled⟧"), "a cleanup follows the source");
        String service = source("src/com/jjricks/volt/ScooterService.java");
        check(service.contains("T.serviceTestNotStartedReason(ChargeScheduleText.shown(r.failure))"), "the test's failure reason is shown through ChargeScheduleText");
        check(service.contains("T.serviceTestCleanup(ChargeScheduleText.shown(r.cleanup))"), "the test's cleanup is shown through ChargeScheduleText");
    }

    private static String source(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(path, e);
        }
    }

    private static void equal(String want, CharSequence got, String what) {
        check(String.valueOf(want).equals(String.valueOf(got)), what + ": want \"" + want + "\", got \"" + got + "\" (" + Locale.getDefault() + ")");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
