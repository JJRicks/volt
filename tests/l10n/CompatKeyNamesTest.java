package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F2's three keys follow the README key rule (area_screen_meaning, no batch name) and live in the
 * compatibility area's file: key names are permanent once translations exist. Pins the names a
 * translation would use, the English they carry, and that no English key names a batch (b9, f2, ...).
 */
public final class CompatKeyNamesTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            T.install(null);
            equal("Absent", T.compatFieldsValueAbsent(), "the Fields absent word's English");
            equal("Present", T.compatFieldsValuePresent(), "the Fields present word's English");
            equal("The test is still running.", M.compatReviewTestRunning().render(), "the running review's English");
            T.install(ECHO);
            equal("⟦compat_fields_value_absent⟧", T.compatFieldsValueAbsent(), "the absent word's key");
            equal("⟦compat_fields_value_present⟧", T.compatFieldsValuePresent(), "the present word's key");
            equal("⟦compat_review_test_running⟧", M.compatReviewTestRunning().render(), "the running review's key");
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }

        Path values = Paths.get("res/values");
        check(!Files.exists(values.resolve("strings_compat_f2.xml")), "no batch-named strings_compat_f2.xml");
        String compat = new String(Files.readAllBytes(values.resolve("strings_compat.xml")), StandardCharsets.UTF_8);
        for (String key : new String[]{"compat_fields_value_absent", "compat_fields_value_present", "compat_review_test_running"})
            check(compat.contains("<string name=\"" + key + "\""), key + " is in strings_compat.xml");

        Pattern name = Pattern.compile("<(?:string|plurals) name=\"([^\"]+)\"");
        Pattern batch = Pattern.compile("(?:^|_)(?:b\\d+[a-z]?|f\\d+|wave\\d*)(?:_|$)");
        int keys = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(values, "strings*.xml")) {
            for (Path file : files) {
                Matcher m = name.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) {
                    keys++;
                    check(!batch.matcher(m.group(1)).find(), file.getFileName() + ": key " + m.group(1) + " names a batch, not a screen");
                }
            }
        }
        check(keys > 1000, "every English key was scanned (" + keys + ")");
        System.out.println("CompatKeyNamesTest: " + checks + " checks passed (F2's keys follow the key rule)");
    }

    private static void equal(String expected, String actual, String what) {
        check(expected.equals(actual), what + ": expected <" + expected + "> got <" + actual + ">");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
