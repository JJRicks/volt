package com.jjricks.volt;

/** The charge chart's estimate detail (ChargeTraceDetail): the narrow-label ladder, and no break inside the version. */
public final class ChargeTraceDetailTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        String version = ChargingEstimate.ESTIMATOR_VERSION;
        String brief = ChargeTraceDetail.shortVersion(version);
        check(brief.replace("\u2060", "").equals(version.substring(0, version.indexOf('.')) + "\u2026"), "the version's first part and an ellipsis: " + brief);
        for (int i = 0; i < brief.length(); i++) if (brief.charAt(i) == '-') check(i + 1 < brief.length() && brief.charAt(i + 1) == '\u2060', "no break after the hyphen at " + i);
        check(ChargeTraceDetail.shortVersion("v12").equals("v12"), "a version with no dots stays whole, with no ellipsis");
        check(ChargeTraceDetail.shortVersion(null) == null && ChargeTraceDetail.shortVersion(" ") == null, "no version: none");
        // The ladder: full on one line; full and wrapped when the version fits a line; the short version; no version.
        check(ChargeTraceDetail.pick(300, 60, 60, 360) == ChargeTraceDetail.Rung.FULL, "a line that fits stays whole");
        check(ChargeTraceDetail.pick(360, 60, 60, 360) == ChargeTraceDetail.Rung.FULL, "exactly the room fits");
        check(ChargeTraceDetail.pick(500, 60, 60, 360) == ChargeTraceDetail.Rung.WRAPPED, "a short saved version (fixture-v1) keeps the whole detail, wrapped");
        check(ChargeTraceDetail.pick(620, 400, 120, 360) == ChargeTraceDetail.Rung.SHORT_VERSION, "a version wider than a line: its first part");
        check(ChargeTraceDetail.pick(620, 500, 400, 360) == ChargeTraceDetail.Rung.NO_VERSION, "a short version wider than the room: no version");
        check(ChargeTraceDetail.pick(620, Float.NaN, Float.NaN, 360) == ChargeTraceDetail.Rung.NO_VERSION, "no version: no version");
        check(ChargeTraceDetail.pick(100, 50, 50, 0) == ChargeTraceDetail.Rung.NO_VERSION && ChargeTraceDetail.pick(100, 50, 50, Float.NaN) == ChargeTraceDetail.Rung.NO_VERSION, "no room measured: the shortest rung");
        // The version as each rung shows it: a wrapped line never breaks inside the version (an older Volt's
        // "ut5-stock-finish-v9" must not split as "ut5-" / "stock-finish-v9").
        String older = "ut5-stock-finish-v9";
        check(ChargeTraceDetail.shown(older, ChargeTraceDetail.Rung.FULL).equals(older), "on one line the version is as saved");
        String wrapped = ChargeTraceDetail.shown(older, ChargeTraceDetail.Rung.WRAPPED);
        check(wrapped.replace("\u2060", "").equals(older), "wrapped, it reads as saved: " + wrapped);
        for (int i = 0; i < wrapped.length(); i++) if (wrapped.charAt(i) == '-') check(i + 1 < wrapped.length() && wrapped.charAt(i + 1) == '\u2060', "wrapped: no break after the hyphen at " + i);
        check(ChargeTraceDetail.shown(older, ChargeTraceDetail.Rung.SHORT_VERSION).equals(ChargeTraceDetail.shortVersion(older)), "short: the first part");
        check(ChargeTraceDetail.shown(older, ChargeTraceDetail.Rung.NO_VERSION) == null, "none: no version");
        System.out.println("ChargeTraceDetailTest: " + checks + " assertions passed");
    }
}
