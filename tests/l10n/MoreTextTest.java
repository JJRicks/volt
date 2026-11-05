package com.jjricks.volt;

import java.util.Locale;

/**
 * B18's own suite: the More screen's catalog text against the base English MoreScreenTextTest.Base
 * pinned before the batch (G5), in English and under a key-echo text source.
 *
 * G2 (LedgerTest) proves each ledger row's template; this adds what a row cannot carry:
 *  - the other branch of manual rows: more_lab_row (the row without a value line) beside
 *    more_lab_row_value, and more_plain, the branch that passes its text through;
 *  - the lab footer: each foot key with and without more_lab_kept (the leading-space sentence);
 *  - the shared-access suffix appended to a model;
 *  - every choice, plural and format the screen builds, at each branch and count, through the same
 *    Fmt calls MoreScreen passes (Fmt.decimal for MiB, Fmt.integer for percents, seconds and counts);
 *  - each of those keys follows the installed text source (no English left around them).
 */
public final class MoreTextTest {
    private static int checks;

    /** Answers every id with its key between marks, and says the text is Spanish. */
    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("en", "IN"), new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                english();
            }
            T.install(ECHO);
            follows();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("MoreTextTest: " + checks + " checks passed (More screen keys against the base English, "
                + "every branch and count, and the key-echo source)");
    }

    private static void english() {
        // Formats, through the Fmt calls MoreScreen passes.
        for (long bytes : new long[]{1, 1023, 52429, 1048576, 1572864, 104857600, 5368709120L}) {
            equal(MoreScreenTextTest.Base.mib(bytes), T.fmtQuantity(Fmt.decimal(Locale.US, bytes / 1048576.0, 1), T.moreUnitMib()), "mib " + bytes);
            long limit = 128L * 1048576;
            equal(MoreScreenTextTest.Base.storageUsed(bytes, limit), T.moreHistoryUsed(Fmt.decimal(Locale.US, bytes / 1048576.0, 1),
                    Fmt.decimal(Locale.US, limit / 1048576.0, 0), T.moreUnitMib()), "storage " + bytes);
        }
        for (float scale : new float[]{0.85f, 1f, 1.15f, 1.3f, 2f}) {
            equal(MoreScreenTextTest.Base.percent(scale), T.fmtPercent(Fmt.integer(Math.round(scale * 100))), "percent " + scale);
        }
        for (int ms : new int[]{1000, 2000, 5000, 10000}) {
            equal(MoreScreenTextTest.Base.seconds(ms), T.fmtQuantity(Fmt.integer(ms / 1000), T.moreUnitSecond()), "seconds " + ms);
        }
        // Choices, each branch.
        for (Boolean state : new Boolean[]{null, true, false}) {
            String title = "Haptic feedback";
            equal(MoreScreenTextTest.Base.toggleDescription(title, state),
                    state == null ? T.moreToggleNotReported(title) : T.morePlain(title), "toggle " + state);
        }
        for (boolean done : new boolean[]{true, false}) {
            equal(MoreScreenTextTest.Base.checklistStep(done, 2), done ? T.commonDone() : T.moreStep(2), "step " + done);
        }
        for (boolean all : new boolean[]{true, false}) {
            equal(MoreScreenTextTest.Base.clearHistory(all, "Blue"), all ? T.moreHistoryClearAllBody() : T.moreHistoryClearOneBody("Blue"), "clear " + all);
        }
        for (int n = 0; n <= 25; n++) {
            equal(MoreScreenTextTest.Base.clearTries(n), n == 0 ? T.moreLabClearedNone() : n == 1 ? T.moreLabClearedOne() : T.moreLabClearedMany(n), "tries " + n);
            equal(MoreScreenTextTest.Base.labWhy(n), n > 0 ? (n == 1 ? T.moreLabWhyOne(n) : T.moreLabWhyMany(n)) : T.moreLabWhyNone(), "why " + n);
            equal(MoreScreenTextTest.Base.taps(n), T.moreDevTaps(n, n), "taps " + n);
            for (boolean stock : new boolean[]{true, false}) {
                equal(MoreScreenTextTest.Base.qualified(n, stock), stock ? T.moreDevCurvesStock(Fmt.integer(n)) : T.moreDevCurvesFast(Fmt.integer(n)), "qualified " + n);
            }
        }
        for (int i = 0; i < 4; i++) equal(MoreScreenTextTest.Base.guideStep(i, "Hold brake"), T.morePinGuideStep((i + 1), "Hold brake"), "guide " + i);
        for (boolean stock : new boolean[]{true, false}) {
            equal(MoreScreenTextTest.Base.recordWith(stock), stock ? T.moreDevRecordStock() : T.moreDevRecordFast(), "record " + stock);
        }
        for (boolean back : new boolean[]{true, false}) {
            equal(MoreScreenTextTest.Base.labNow("20 km/h", "25 km/h", back),
                    back ? T.moreLabNowWas("20 km/h", "25 km/h") : T.moreLabNow("20 km/h"), "now " + back);
        }
        equal(MoreScreenTextTest.Base.labNow(null, "25 km/h", false), "", "now without a value");
        for (String text : new String[]{"", "Now On"}) {
            String reason = "Connect first";
            equal(MoreScreenTextTest.Base.labReason(text, reason), text.isEmpty() ? T.morePlain(reason) : T.moreLabValueReason(text, reason), "reason " + text);
            String label = "Tail light", status = "Untested on your model";
            equal(MoreScreenTextTest.Base.labLine(label, status, text),
                    text.isEmpty() ? T.moreLabRow(label, status) : T.moreLabRowValue(label, status, text), "lab line " + text);
        }
        for (boolean refused : new boolean[]{true, false}) {
            equal(MoreScreenTextTest.Base.go(refused, false, "On"), refused ? T.moreLabTryAgainValue("On") : T.moreLabTryValue("On"), "go " + refused);
        }
        equal(MoreScreenTextTest.Base.go(false, true, "On"), T.moreLabAlready("On"), "go already");
        for (boolean review : new boolean[]{true, false}) {
            for (boolean back : new boolean[]{true, false}) {
                String label = "Tail light", lower = label.toLowerCase(Locale.ROOT);
                equal(MoreScreenTextTest.Base.labAction(review, back, label, "Off"),
                        review ? T.moreLabReviewRow(lower) : back ? T.moreLabSetBackRow(lower, "Off") : T.moreLabTryRow(lower), "action " + review + back);
            }
        }
        // The lab footer: each foot with and without the kept sentence.
        for (String foot : new String[]{T.moreLabFootOem(), T.moreLabFootElsewhere(), T.moreLabFoot()}) {
            for (boolean rows : new boolean[]{true, false}) {
                equal(MoreScreenTextTest.Base.labFoot(foot, rows), foot + (rows ? T.moreLabKept() : ""), "foot " + rows);
            }
        }
        check(T.moreLabKept().startsWith(" Try results"), "more_lab_kept keeps its leading space");
        // The shared-access suffix appended to a model.
        for (String model : new String[]{"N65i", T.moreScootersModelUnknown()}) {
            equal(MoreScreenTextTest.Base.sharedModel(model), model + T.moreScootersSharedSuffix(), "shared " + model);
        }
        equal(MoreScreenTextTest.Base.proximity("Proximity unlock", "Owner only"), T.moreProximityDescription("Proximity unlock", "Owner only"), "proximity");
        equal(MoreScreenTextTest.Base.profileStatus("Owner", "Connected"), T.moreIndexStatus("Owner", "Connected"), "index status");
        sectionLabels();
    }

    /** The menu rows: SECTION_LABEL_IDS, read from MoreScreen's source, render SECTION_LABELS in order. */
    private static void sectionLabels() {
        String source;
        try {
            source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/MoreScreen.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new AssertionError("MoreScreen.java: " + e); }
        java.util.List<String> labels = items(source, "String[] SECTION_LABELS = {", "\"([^\"]*)\"");
        java.util.List<String> ids = items(source, "int[] SECTION_LABEL_IDS = {", "T\\.([A-Z0-9_]+)");
        check(labels.size() == 7 && ids.size() == labels.size(), "SECTION_LABELS " + labels + " and SECTION_LABEL_IDS " + ids);
        for (int i = 0; i < labels.size(); i++) {
            int id;
            try { id = T.class.getField(ids.get(i)).getInt(null); }
            catch (ReflectiveOperationException e) { throw new AssertionError("T." + ids.get(i) + ": " + e); }
            equal(labels.get(i), T.render(id), "menu row " + i);
        }
    }

    private static java.util.List<String> items(String source, String head, String item) {
        int at = source.indexOf(head);
        check(at >= 0, "MoreScreen declares " + head);
        String body = source.substring(at + head.length(), source.indexOf("};", at));
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(item).matcher(body);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    private static void follows() {
        String[][] shown = {
                {T.moreLabRow("a", "b"), "more_lab_row"}, {T.moreLabRowValue("a", "b", "c"), "more_lab_row_value"},
                {T.moreLabKept(), "more_lab_kept"}, {T.moreScootersSharedSuffix(), "more_scooters_shared_suffix"},
                {T.moreDevTaps(2, 2), "more_dev_taps"}, {T.moreLabClearedMany(3), "more_lab_cleared_many"},
                {T.fmtQuantity(Fmt.decimal(Locale.US, 1.5, 1), T.moreUnitMib()), "fmt_quantity"},
                {T.moreUnitMib(), "more_unit_mib"}, {T.moreUnitSecond(), "more_unit_second"},
                {T.moreHistoryUsed("1", "2", T.moreUnitMib()), "more_history_used"},
                {T.moreToggleNotReported("x"), "more_toggle_not_reported"}, {T.morePlain("x"), "more_plain"},
        };
        for (String[] s : shown) check(s[0].startsWith("⟦" + s[1] + "⟧"), s[1] + " follows the source: " + s[0]);
    }

    private static void equal(String want, CharSequence got, String what) {
        check(want.equals(String.valueOf(got)), what + ": want \"" + want + "\", got \"" + got + "\" (" + Locale.getDefault() + ")");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
