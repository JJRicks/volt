package com.jjricks.volt;

import java.util.Locale;

/**
 * F8's own suite: the new catalog text against the base English GroupedCountsEnglishTest.Base pinned before
 * the batch (G5), in English and under a key-echo text source.
 *
 * G2 (LedgerTest) proves each ledger row's template with sentinels; this renders the calls the screens now
 * make, with real counts (0, 1, 2, 1234 and large ones, so the grouping and the plural's "one" item are both
 * compared):
 *  - each history kind's "%,d saved" count;
 *  - the storage card's screen-reader text, the template around the samples sentence;
 *  - the GPS caption: no fixes, the kept fixes ("1 fixes" stays) and the glued dropped clause;
 *  - each key follows the installed text source.
 */
public final class GroupedCountsTest {
    private static int checks;
    private static final long[] COUNTS = {0, 1, 2, 1234, 1000000, 12345678901L};

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
        System.out.println("GroupedCountsTest: " + checks + " checks passed (history counts, the storage card's "
                + "screen-reader text and the GPS caption against the base English, and the key-echo source)");
    }

    private static void english() {
        for (long n : COUNTS) {
            equal(GroupedCountsEnglishTest.Base.saved(n), T.moreHistorySamplesSaved(n, Fmt.groupedInteger(Locale.US, n)), "saved at " + n);
            for (long m : COUNTS) {
                equal(GroupedCountsEnglishTest.Base.storageDescription("1.5 of 256 MiB", n, m, n + m),
                        T.moreHistoryStorageDescription("1.5 of 256 MiB", T.moreHistorySamplesDescription(
                                Fmt.groupedInteger(Locale.US, n), Fmt.groupedInteger(Locale.US, m), Fmt.groupedInteger(Locale.US, n + m))),
                        "storage description at " + n + ", " + m);
            }
        }
        for (long n : COUNTS) {
            if (n > Integer.MAX_VALUE) continue;
            for (long d : COUNTS) {
                if (d > Integer.MAX_VALUE) continue;
                equal(GroupedCountsEnglishTest.Base.fixes((int) n, (int) d), caption((int) n, (int) d), "GPS caption at " + n + ", " + d + " dropped");
            }
        }
        equal("1 fixes", caption(1, 0), "the base's own English at one fix");
        equal(GroupedCountsEnglishTest.Base.fixes(Integer.MAX_VALUE, Integer.MAX_VALUE), caption(Integer.MAX_VALUE, Integer.MAX_VALUE), "GPS caption at the int limit");
    }

    /** RidesScreen.phoneRide's caption at head, with dropped = rejected locations + rejected elevations. */
    private static String caption(int locationCount, int dropped) {
        return locationCount == 0 ? T.ridesGpsNoFixes() : T.ridesGpsFixes(locationCount, Fmt.groupedInteger(Locale.US, locationCount))
                + (dropped > 0 ? T.ridesGpsDropped(dropped, Fmt.groupedInteger(Locale.US, dropped)) : "");
    }

    private static void follows() {
        String[][] shown = {
                {String.valueOf(T.moreHistorySamplesSaved(2, "2")), "more_history_samples_saved"},
                {String.valueOf(T.moreHistoryStorageDescription("x", "y")), "more_history_storage_description"},
                {String.valueOf(T.moreHistorySamplesDescription("1", "2", "3")), "more_history_samples_description"},
                {String.valueOf(T.ridesGpsNoFixes()), "rides_gps_no_fixes"},
                {String.valueOf(T.ridesGpsFixes(2, "2")), "rides_gps_fixes"},
                {String.valueOf(T.ridesGpsDropped(2, "2")), "rides_gps_dropped"},
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
