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
        // The ladder: full on one line, else the short version wrapped, else no version.
        check(ChargeTraceDetail.pick(300, 120, 360) == ChargeTraceDetail.Rung.FULL, "a line that fits stays whole");
        check(ChargeTraceDetail.pick(360, 120, 360) == ChargeTraceDetail.Rung.FULL, "exactly the room fits");
        check(ChargeTraceDetail.pick(620, 120, 360) == ChargeTraceDetail.Rung.SHORT_VERSION, "too long: the short version, wrapped at spaces");
        check(ChargeTraceDetail.pick(620, 400, 360) == ChargeTraceDetail.Rung.NO_VERSION, "a short version wider than the room: no version");
        check(ChargeTraceDetail.pick(620, Float.NaN, 360) == ChargeTraceDetail.Rung.NO_VERSION, "no version to shorten: no version");
        check(ChargeTraceDetail.pick(100, 50, 0) == ChargeTraceDetail.Rung.NO_VERSION && ChargeTraceDetail.pick(100, 50, Float.NaN) == ChargeTraceDetail.Rung.NO_VERSION, "no room measured: the shortest rung");
        System.out.println("ChargeTraceDetailTest: " + checks + " assertions passed");
    }
}
