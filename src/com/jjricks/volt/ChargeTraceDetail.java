package com.jjricks.volt;

/**
 * The charge chart's estimate detail under a cursor reading ("Personal curve · Estimator … · 4 learned charges"), kept
 * inside the width its row shows (ChargeHistoryDialog). It steps down the narrow-label ladder rather than running off the
 * edge or breaking a word: the whole line with the estimator's full version when it fits on one line; else the version's
 * first part, wrapped at spaces; else no version, wrapped at spaces. The full detail stays the line's spoken text.
 * Pure: no Android, so tests/ChargeTraceDetailTest checks it.
 */
final class ChargeTraceDetail {
    private ChargeTraceDetail() { }

    enum Rung { FULL, SHORT_VERSION, NO_VERSION }

    /** A word joiner: no line break after a hyphen inside the short version. */
    private static final String JOIN = "\u2060";

    /**
     * The version's first dotted part, with "…" when there was more ("ut5-fast-cvpoint-v11…" of
     * "ut5-fast-cvpoint-v11.stitch-v1.temps-v1"), its hyphens joined so it never wraps inside. Null for no version.
     */
    static String shortVersion(String version) {
        if (version == null || version.trim().isEmpty()) return null;
        version = version.trim();
        int dot = version.indexOf('.');
        String head = dot > 0 ? version.substring(0, dot) + "\u2026" : version;
        return head.replace("-", "-" + JOIN);
    }

    /**
     * fullLine: the whole detail's width on one line with the full version; shortVersion: the short version's own width
     * (it never wraps inside), NaN with none; room: the width the line may take.
     */
    static Rung pick(float fullLine, float shortVersion, float room) {
        if (!(room > 0)) return Rung.NO_VERSION;
        if (fullLine <= room) return Rung.FULL;
        if (Float.isFinite(shortVersion) && shortVersion <= room) return Rung.SHORT_VERSION;
        return Rung.NO_VERSION;
    }
}
