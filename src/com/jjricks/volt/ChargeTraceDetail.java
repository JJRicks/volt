package com.jjricks.volt;

/**
 * The charge chart's estimate detail under a cursor reading ("Personal curve · Estimator … · 4 learned charges"), kept
 * inside the width its row shows (ChargeHistoryDialog). It steps down the narrow-label ladder rather than running off the
 * edge or breaking a word: the whole line with the estimator's full version when it fits on one line; else the same,
 * wrapped, when the version fits a line of its own; else the version's first part; else no version. A shown version
 * never breaks inside (its hyphens are joined when it may wrap). The full detail stays the line's spoken text.
 * Pure: no Android, so tests/ChargeTraceDetailTest checks it.
 */
final class ChargeTraceDetail {
    private ChargeTraceDetail() { }

    enum Rung { FULL, WRAPPED, SHORT_VERSION, NO_VERSION }

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
        return joined(dot > 0 ? version.substring(0, dot) + "\u2026" : version);
    }

    /** The version with every hyphen joined to what follows, so a wrapped line never breaks inside it. */
    static String joined(String version) { return version.replace("-", "-" + JOIN); }

    /** The version as a rung shows it: whole on one line, joined when wrapped, its first part, or none (null). */
    static String shown(String version, Rung rung) {
        if (version == null || rung == Rung.NO_VERSION) return null;
        if (rung == Rung.SHORT_VERSION) return shortVersion(version);
        return rung == Rung.WRAPPED ? joined(version) : version;
    }

    /**
     * fullLine: the whole detail's width on one line with the full version; version: the full version's own width;
     * shortVersion: the short version's own width (it never wraps inside); NaN for either with none; room: the width
     * the line may take.
     */
    static Rung pick(float fullLine, float version, float shortVersion, float room) {
        if (!(room > 0)) return Rung.NO_VERSION;
        if (fullLine <= room) return Rung.FULL;
        if (Float.isFinite(version) && version <= room) return Rung.WRAPPED;
        if (Float.isFinite(shortVersion) && shortVersion <= room) return Rung.SHORT_VERSION;
        return Rung.NO_VERSION;
    }
}
