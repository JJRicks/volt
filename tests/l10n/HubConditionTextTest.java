package com.jjricks.volt;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Hub layout plan, part 3b: the text for the owner's conditions on the tracking issue (#383), in English and
 * under a key-echo text source (each line is one whole catalog string, never glued from pieces).
 *
 * Answer 17 (part 4): Charge history &amp; recordings explains why sending charge recordings helps and offers to
 * email them, with a row that shows the charger and links to the Charger setting. Answer 18 (part 5b): Recording
 * settings gets an opt-in for starting a recording in the background, with a note that such a ride has no GPS
 * route. HubTextTest is frozen for later batch parts, so these checks live in their own file.
 */
public final class HubConditionTextTest {
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
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR")}) {
                Locale.setDefault(phone);
                T.install(null);
                english();
            }
            Locale.setDefault(Locale.US);
            T.install(ECHO);
            echo();
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("HubConditionTextTest: " + checks + " checks passed (charge history and recording settings, "
                + "in English and under the key-echo source)");
    }

    private static void english() {
        equal("Email to Volt", HubText.chargeSendRow(), "send row");
        check(T.key(HubText.chargeSendRow().id()).equals("compat_email"), "the send row reuses the Compatibility panel's words");
        equal("Sending your charge recordings to Volt helps improve charging time estimates for your scooter's model.",
                HubText.chargeSendNote(), "send note");
        equal("No email app could open the charge recordings.", HubText.chargeSendNoEmail(), "no email app");
        equal("Charger for estimates", HubText.chargerRow(), "charger row");
        check(T.key(HubText.chargerRow().id()).equals("main_row_charger"), "the charger row reuses the Charger row's name");
        equal("Automatic", HubText.chargerAutomatic(), "charger automatic");
        equal("Stock", HubText.chargerValue(false, false), "stock chosen");
        equal("High-speed", HubText.chargerValue(true, false), "high-speed chosen");
        equal("Auto · Stock", HubText.chargerValue(false, true), "stock recognized");
        equal("Auto · High-speed", HubText.chargerValue(true, true), "high-speed recognized");
        equal("Stop charge recording to change", HubText.chargerStopRecording(), "charger while recording");
        equal("Start recording in the background", HubText.recordInBackground(), "background switch");
        equal("A ride that starts while Volt is in the background has no GPS route.", HubText.recordInBackgroundNote(),
                "background note");
    }

    /** Under the key-echo source every line is exactly one key: nothing is glued around a catalog string. */
    private static void echo() {
        List<Msg> all = Arrays.asList(HubText.chargeSendRow(), HubText.chargeSendNote(), HubText.chargeSendNoEmail(),
                HubText.chargerRow(), HubText.chargerAutomatic(), HubText.chargerValue(false, false),
                HubText.chargerValue(true, false), HubText.chargerValue(false, true), HubText.chargerValue(true, true),
                HubText.chargerStopRecording(), HubText.recordInBackground(), HubText.recordInBackgroundNote());
        for (Msg msg : all) {
            check(msg.id() >= 0, "a catalog message, not verbatim text");
            check(!msg.isPlural(), "nothing here counts anything: " + T.key(msg.id()));
            equal("⟦" + T.key(msg.id()) + "⟧", msg.render(), "one whole key");
        }
        equal("Auto · Stock", HubText.chargerValue(false, true).english(), "english() under another source");
    }
}
