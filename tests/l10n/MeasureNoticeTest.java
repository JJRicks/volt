package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Charge done by's "not set" notice (ScooterService.endChargeMeasure). B8 converted the sentence while
 * B5's Decision.message was still unmerged, so the service passed the English Decision.reason into a
 * translated sentence. The service now passes Decision.message. This suite pins, for every NOT_SET
 * reason ChargeMeasureWindow.evaluate can give and for the Bluetooth permission path:
 * - in English the notice reads exactly as the merge base built it (ServiceEnglishTest.notSet, frozen
 *   by G5, from the English reason), under several phone locales;
 * - under a marked language the reason inside the notice is marked too, so the whole notice comes
 *   from the catalog, while Decision.reason (the English id for logs) stays English.
 * ScooterService is an Android class, so notice() repeats its notice expression, and service() checks the
 * service source: the NOT_SET branch passes Decision.message, endChargeMeasure builds the notice with that
 * expression, and Decision.reason reaches neither.
 */
public final class MeasureNoticeTest {
    private static int checks;
    private static final long WALL = 1_800_000_000_000L, MONO = 1_000_000L;

    /** Answers every id with its English text between marks, so text from the catalog shows them. */
    private static final T.Source MARKED = new T.Source() {
        @Override public String string(int id) { return "⟦" + EnglishText.INSTANCE.string(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + EnglishText.INSTANCE.plural(id, quantity) + "⟧"; }
        @Override public String language() { return "xx"; }
    };

    public static void main(String[] args) throws Exception {
        service();
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"),
                    Locale.forLanguageTag("ar-EG-u-nu-arab"), Locale.forLanguageTag("th-TH-u-nu-thai")}) {
                Locale.setDefault(phone);
                english();
            }
            Locale.setDefault(Locale.US);
            T.install(MARKED);
            try {
                marked();
            } finally {
                T.install(null);
            }
            english();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("MeasureNoticeTest: " + checks + " checks passed (Charge done by's not-set notice: English unchanged, "
                + "the service passes Decision.message, reason id English)");
    }

    /** The five NOT_SET decisions evaluate() can give, with the English the base showed for each. */
    private static ChargeMeasureWindow.Decision[] notSet() {
        return new ChargeMeasureWindow.Decision[]{
                started().evaluate(MONO + 60_000L, "other", true, 1, false),
                started().evaluate(MONO - 1, "one", true, 1, false),
                started().evaluate(MONO + 60_000L, "one", true, 0, false),
                started().evaluate(MONO + 13 * 60_000L, "one", true, null, false),
                started().evaluate(MONO + 13 * 60_000L, "one", false, null, false),
        };
    }

    private static final String[] ENGLISH = {
            "The selected scooter changed while measuring. Charge done by is off.",
            "The phone clock changed while measuring. Charge done by is off.",
            "Charging stopped before the schedule was set. Charge done by is off.",
            "Charging readings stopped while measuring. Charge done by is off.",
            "The scooter disconnected while measuring. Charge done by is off.",
    };

    private static void english() {
        ChargeMeasureWindow.Decision[] decisions = notSet();
        for (int i = 0; i < decisions.length; i++) {
            ChargeMeasureWindow.Decision d = decisions[i];
            check(d.step == ChargeMeasureWindow.Step.NOT_SET && d.message != null && d.reason != null, "decision " + i + " is NOT_SET with a reason");
            same(ENGLISH[i], ServiceEnglishTest.notSet(d.reason), "the base notice " + i);
            same(ServiceEnglishTest.notSet(d.reason), notice(d.message), "the head notice " + i);
            same(ServiceEnglishTest.doneToast(false, ServiceEnglishTest.notSet(d.reason)), T.serviceDoneToastNotSet(notice(d.message)), "the toast " + i);
        }
        same(ServiceEnglishTest.notSet("Bluetooth permission was removed while measuring"), notice(T.serviceMeasureBluetoothRemoved()),
                "the permission-removed notice");
        // A decision without a reason never reaches the notice; if one did, it would print as the base did.
        same(ServiceEnglishTest.notSet(null), notice(null), "a null reason prints as before");
    }

    private static void marked() {
        for (ChargeMeasureWindow.Decision d : notSet()) {
            String english = d.message.english();
            same(english, d.reason, "the reason id stays English");
            check(!english.contains("⟦"), "the English reason is unmarked: " + english);
            same("⟦⟦" + english + "⟧. Charge done by is off.⟧", notice(d.message), "the whole notice is the shown language's");
        }
        same("⟦⟦Bluetooth permission was removed while measuring⟧. Charge done by is off.⟧", notice(T.serviceMeasureBluetoothRemoved()),
                "the permission-removed notice is the shown language's");
    }

    /** endChargeMeasure's notice expression, which service() checks in ScooterService. */
    private static String notice(CharSequence reason) {
        return T.serviceMeasureNotSet(reason);
    }

    /** The service passes Decision.message, builds the notice as notice() does, and never reads Decision.reason. */
    private static void service() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/ScooterService.java")), StandardCharsets.UTF_8);
        check(count(src, "endChargeMeasure(") == 3, "ScooterService declares endChargeMeasure once and calls it twice");
        check(count(src, "else if(decision.step==ChargeMeasureWindow.Step.NOT_SET)endChargeMeasure(decision.message);") == 1,
                "the NOT_SET branch passes Decision.message");
        check(count(src, "endChargeMeasure(T.serviceMeasureBluetoothRemoved());") == 1, "the permission path passes the catalog reason");
        Matcher body = Pattern.compile("private void endChargeMeasure\\(CharSequence reason\\)\\{([^}]*)\\}").matcher(src);
        check(body.find(), "endChargeMeasure takes a CharSequence reason");
        check(count(body.group(1), "chargeDoneNotice(false,T.serviceMeasureNotSet(reason));") == 1,
                "endChargeMeasure's notice is notice(reason)");
        check(!Pattern.compile("\\bdecision\\s*\\.\\s*reason\\b").matcher(src).find(), "ScooterService never reads Decision.reason");
        check(count(src, "T.serviceMeasureNotSet(") == 1, "only endChargeMeasure builds the not-set notice");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    private static ChargeMeasureWindow started() {
        ChargeMeasureWindow w = new ChargeMeasureWindow();
        w.start("one", 7 * 60, MONO, WALL, WALL + 6 * 60 * 60_000L);
        return w;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void same(String expected, CharSequence actual, String what) {
        checks++;
        String shown = actual == null ? null : actual.toString();
        if (!expected.equals(shown))
            throw new AssertionError(what + " under " + Locale.getDefault() + ": expected <" + expected + "> but got <" + shown + ">");
    }
}
