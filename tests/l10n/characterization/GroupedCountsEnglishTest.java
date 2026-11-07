package com.jjricks.volt;

import java.util.Locale;

/**
 * G5 characterization for F8 (the last hard-coded %,d texts), committed before the batch changes anything.
 *
 * MoreScreen and RidesScreen need Android, so a plain JVM test cannot build them; G2 (LedgerTest) proves
 * each new template renders the original. What this pins, copied from the merge base bd2f61e:
 *   - the Data & history storage card's per-kind count ("1,234 saved") and its screen-reader text;
 *   - the phone track's GPS caption: no fixes, the kept fixes ("1 fixes" too) and the dropped clause.
 * Every case runs with the phone set to en-US, de-DE, en-GB, en-IN and tr-TR; the base formats with
 * Locale.US, so the grouping is a comma on every phone.
 */
public final class GroupedCountsEnglishTest {
    private static int checks;

    public static void main(String[] args) {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, Locale.UK, new Locale("en", "IN"),
                    new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                cases(phone);
            }
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("GroupedCountsEnglishTest: " + checks + " checks passed (history counts, the storage card's "
                + "screen-reader text and the GPS caption under 5 phone locales)");
    }

    private static void cases(Locale phone) {
        equal("0 saved", Base.saved(0), phone, "saved at 0");
        equal("1 saved", Base.saved(1), phone, "saved at 1");
        equal("1,234 saved", Base.saved(1234), phone, "saved at 1234");
        equal("12,345,678,901 saved", Base.saved(12345678901L), phone, "saved, large");
        equal("Local storage, 1.5 of 256 MiB used. 1 detailed, 0 minute and 2 daily samples saved.",
                Base.storageDescription("1.5 of 256 MiB", 1, 0, 2), phone, "storage description");
        equal("Local storage, 120.0 of 256 MiB used. 1,234 detailed, 1,000,000 minute and 12,345,678,901 daily samples saved.",
                Base.storageDescription("120.0 of 256 MiB", 1234, 1000000, 12345678901L), phone, "storage description, grouped");
        equal("No GPS fixes", Base.fixes(0, 0), phone, "no fixes");
        equal("No GPS fixes", Base.fixes(0, 3), phone, "no fixes, some dropped");
        equal("1 fixes", Base.fixes(1, 0), phone, "one fix (the base's own English)");
        equal("2 fixes · 1 dropped", Base.fixes(2, 1), phone, "fixes and one dropped");
        equal("1,234 fixes · 1,000,000 dropped", Base.fixes(1234, 1000000), phone, "grouped fixes and dropped");
    }

    /** Copies of the base's expressions at bd2f61e. */
    static final class Base {
        private Base() {}
        /** MoreScreen.refreshHistoryStorage: each history kind's count. */
        static String saved(long rows) {
            return String.format(Locale.US, "%,d saved", rows);
        }
        /** MoreScreen.refreshHistoryStorage: the storage card's content description, with used as T.moreHistoryUsed printed it. */
        static String storageDescription(String used, long detailed, long minute, long daily) {
            long[] rows = {detailed, minute, daily};
            return "Local storage, " + used + " used. "
                    + String.format(Locale.US, "%,d detailed, %,d minute and %,d daily samples saved.", rows[0], rows[1], rows[2]);
        }
        /** RidesScreen.phoneRide: the GPS caption, with dropped = rejected locations + rejected elevations. */
        static String fixes(int locationCount, int dropped) {
            return locationCount == 0 ? "No GPS fixes" : String.format(Locale.US, "%,d fixes", locationCount) + (dropped > 0 ? String.format(Locale.US, " · %,d dropped", dropped) : "");
        }
    }

    private static void equal(String expected, String actual, Locale phone, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + " on a " + phone + " phone: expected <" + expected + "> but was <" + actual + ">");
        }
        checks++;
    }
}
