package com.jjricks.volt;

import java.util.Arrays;
import java.util.List;

/**
 * Settings › Home style › Overview readings' words (HubText, strings_dashboard.xml): the row and sheet title, the two
 * slots' names, how a slot's row is read, and the reading list's Not reported line and close button, in English and
 * under a key-echo text source (each one whole catalog string, never glued from pieces).
 */
public final class OverviewReadingsTextTest {
    private static int checks;

    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void equal(String expected, CharSequence actual, String message) {
        check(actual != null && expected.equals(actual.toString()), message + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void main(String[] args) {
        try {
            T.install(null);
            equal("Overview readings", HubText.overviewReadings().render(), "row title");
            equal("First reading", HubText.overviewReadingSlot(0).render(), "first slot");
            equal("Second reading", HubText.overviewReadingSlot(1).render(), "second slot");
            equal("First reading: Battery voltage. Change", HubText.overviewReadingSlotSpoken("First reading", "Battery voltage").render(), "slot read aloud");
            equal("Not reported", HubText.overviewReadingNotReported().render(), "not reported, the shared word");
            equal("Close", HubText.overviewReadingsClose().render(), "close, the shared word");
            T.install(ECHO);
            List<Msg> all = Arrays.asList(HubText.overviewReadings(), HubText.overviewReadingSlot(0), HubText.overviewReadingSlot(1),
                    HubText.overviewReadingSlotSpoken("x", "y"), HubText.overviewReadingNotReported(), HubText.overviewReadingsClose());
            List<String> keys = Arrays.asList("dashboard_overview_readings", "dashboard_overview_reading_first", "dashboard_overview_reading_second",
                    "dashboard_overview_reading_slot", "common_not_reported", "common_close");
            for (int i = 0; i < all.size(); i++) {
                Msg msg = all.get(i);
                check(msg != null && msg.id() >= 0, "a catalog message, not verbatim text");
                equal(keys.get(i), T.key(msg.id()), "its own key");
                equal("⟦" + keys.get(i) + "⟧", msg.render(), "one whole key");
            }
        } finally {
            T.install(null);
        }
        System.out.println("OverviewReadingsTextTest: " + checks + " checks passed (Overview readings' words, in English and one whole key each)");
    }
}
