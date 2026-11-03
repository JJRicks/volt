package com.jjricks.volt;

import java.util.Arrays;

/** versionName parsing and comparison, including build.sh's dev marker ("1.11.1-dev+abc1234[-dirty]"). */
public final class VersionNameTest {
    private static int assertions;
    public static void main(String[] args) {
        numbers(); comparison(); dev(); patterns(); updates(); reports();
        System.out.println("VersionName tests passed: " + assertions + " assertions");
    }

    private static void numbers() {
        check(Arrays.equals(VersionName.numbers("1.11.1"), new int[]{1, 11, 1}), "A release");
        check(Arrays.equals(VersionName.numbers("v1.11.1"), new int[]{1, 11, 1}), "A tag");
        for (String dev : new String[]{"1.11.1-dev", "1.11.1-dev+abc1234", "1.11.1-dev+abc1234-dirty", "1.11.1-dev+0123456789ab-dirty", " 1.11.1-dev+abc1234 "})
            check(Arrays.equals(VersionName.numbers(dev), new int[]{1, 11, 1}), "A dev build counts as its base version: " + dev);
        check(Arrays.equals(VersionName.numbers("1.5.0-n65i-test"), new int[]{1, 5, 0}), "An older test build");
        // Nothing unexpected throws; it is just unreadable.
        for (String bad : new String[]{null, "", " ", "v", "-dev+abc1234", "dev", "unknown", "1..5", "1.2.3.4.5", "99999999999.1",
                ".", "..", "+", "1.11.1-dev+abc1234".substring(6), "\u0000", "١.٢.٣"})
            check(VersionName.numbers(bad) == null, "Unreadable: " + bad);
    }

    private static void comparison() {
        int[] dev = VersionName.numbers("1.11.1-dev+abc1234");
        check(VersionName.compare(VersionName.numbers("1.11.1"), dev) == 0, "A dev build of 1.11.1 is not older than release 1.11.1");
        check(VersionName.compare(VersionName.numbers("1.11.2"), dev) > 0 && VersionName.compare(VersionName.numbers("1.12.0"), dev) > 0
                && VersionName.compare(VersionName.numbers("2.0"), dev) > 0, "Newer releases are newer than the dev build");
        check(VersionName.compare(VersionName.numbers("1.11.0"), dev) < 0, "An older release is older");
        check(VersionName.compare(VersionName.numbers("1.5"), VersionName.numbers("1.5.0")) == 0
                && VersionName.compare(VersionName.numbers("1.10.0"), VersionName.numbers("1.9.9")) > 0, "Numeric, not text, order");
    }

    private static void dev() {
        for (String yes : new String[]{"1.11.1-dev", "1.11.1-dev+abc1234", "1.11.1-dev+abc1234-dirty"})
            check(VersionName.dev(yes), "Dev build: " + yes);
        for (String no : new String[]{"1.11.1", "v1.11.1", "1.5.0-n65i-test", "1.11.1-device", "unknown", "", null, "-dev", "dev"})
            check(!VersionName.dev(no), "Not a dev build: " + no);
    }

    /** Every place a version is checked against a pattern keeps the dev marker. */
    private static void patterns() {
        for (String ok : new String[]{"1.11.1", "1.11.1-dev", "1.11.1-dev+abc1234", "1.11.1-dev+abc1234-dirty", "1.11.1-dev+0123456789ab-dirty", "1.5.0-n65i-test", "unknown"})
            check(ok.matches(CommandLog.APP_VERSION), "Command log keeps " + ok);
        for (String bad : new String[]{"", "1", "1.11.1 ", "1.11.1-dev+abc 1234", "1.11.1-dev+abc1234-dirty-x-y", "1.11.1-", "1.11.1-dev+", "N2546ZZ9US1234567", "C0:FF:EE:12:34:56"})
            check(!bad.matches(CommandLog.APP_VERSION), "Command log refuses " + bad);
    }

    /** A dev build of 1.11.1 is never told to update to 1.11.1, but is offered anything newer. */
    private static void updates() {
        UpdateCheck.fetcher = (url, agent) -> { throw new AssertionError("Tests must not fetch " + url); };
        for (String dev : new String[]{"1.11.1-dev", "1.11.1-dev+abc1234", "1.11.1-dev+abc1234-dirty"}) {
            check(UpdateCheck.evaluate(dev, release("v1.11.1")).outcome == UpdateCheck.Outcome.CURRENT, "Same base release is current: " + dev);
            check(UpdateCheck.evaluate(dev, release("v1.11.0")).outcome == UpdateCheck.Outcome.CURRENT, "An older release is current: " + dev);
            UpdateCheck.Result newer = UpdateCheck.evaluate(dev, release("v1.11.2"));
            check(newer.outcome == UpdateCheck.Outcome.AVAILABLE && newer.latest.equals("1.11.2"), "A newer release is offered: " + dev);
            check(UpdateCheck.evaluate(dev, release("v1.12.0")).outcome == UpdateCheck.Outcome.AVAILABLE, "A newer minor release is offered: " + dev);
        }
        check(UpdateCheck.userAgent("1.11.1-dev+abc1234").equals("Volt/1.11.1-dev+abc1234 (Android; https://github.com/JJRicks/volt)"), "User-Agent keeps the marker");
    }

    /** Reports carry the dev marker, which helps triage. */
    private static void reports() {
        String dev = "1.11.1-dev+abc1234-dirty";
        new CommandLog.Session(1_790_000_000_000L, dev, "25462", ModelRegistry.Layer.OEM_GROUP, "2.0.3.1", "1.4", false);
        String diagnostics = new DiagnosticsBundle(dev, 1_790_000_000_000L, false).json(java.util.Collections.<String>emptyList());
        check(diagnostics.contains("\"app_version\": \"" + dev + "\""), "Diagnostics keep the marker: " + diagnostics);
        CompatibilityReport report = CompatibilityReport.parse("{\"format\":\"volt-compat-report-v1\",\"app_version\":\"" + dev + "\"}");
        check(report != null && report.json().contains("\"app_version\": \"" + dev + "\""), "A compatibility report keeps the marker");
        check(IssueReport.subject(dev).equals("Volt issue report · Volt " + dev)
                && IssueReport.body(dev, "16", 36, "Google Pixel 9", "", false).contains("Volt " + dev + " · Android 16"), "Issue reports keep the marker");
        String connection = ConnectionReport.json(dev, 36, "Google Pixel 9", "25462", "aes", 23,
                java.util.Collections.<ConnectionEventLog.Record>emptyList(), java.util.Collections.<String>emptyList());
        check(connection.contains("\"app_version\": \"" + dev + "\""), "A connection report keeps the marker: " + connection);
    }

    private static String release(String tag) {
        return "{\"html_url\":\"https://github.com/JJRicks/volt/releases/tag/" + tag + "\",\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,\"body\":\"\"}";
    }

    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
