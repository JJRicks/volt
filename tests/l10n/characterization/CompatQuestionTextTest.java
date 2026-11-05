package com.jjricks.volt;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * G5 characterization for F2's observation question, committed before the batch changes anything. The guided
 * try's question (CompatibilityPanel.question, a UI class tests/run.sh does not build) compares the value it is
 * given with the English "On"/"Off" and looks for "mph", so what its callers hand it is data:
 *   - the question's sentences print this exact English, with a lowercased value in the generic ones;
 *   - DisplayUnits.featureValue gives the toggles' "On"/"Off" ids and the unit labels naming mph or km/h, and a
 *     Decision's label(v) is its variant's label(v), for every variant ModelRegistry holds;
 *   - under a key-echo source (a stand-in for another language), d.label(v) and DisplayUnits.featureValue (what
 *     MoreScreen's labValue hands question(), and what MainActivity shortens as English) stay the English ids, so
 *     question() still matches them.
 * The phone is set to en-US, tr-TR, de-DE and ar-EG in turn.
 */
public final class CompatQuestionTextTest {
    private static int checks;
    private static final String[] UNITS = {"km", "mi", "dual", null};

    /** Answers every id with its key between marks. */
    private static final T.Source ECHO = new T.Source() {
        @Override public String string(int id) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String plural(int id, long quantity) { return "⟦" + T.key(id) + "⟧"; }
        @Override public String language() { return "es"; }
    };

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, new Locale("tr", "TR"), Locale.GERMANY, new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                T.install(null);
                sentences(phone);
                variants(phone);
                echoed(phone);
            }
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("CompatQuestionTextTest: " + checks + " checks passed (the observation question's English and the English ids its callers pass, under 4 phone locales)");
    }

    /** The question's sentences, as CompatibilityPanel.question picks them (a UI class tests/run.sh does not build). */
    private static void sentences(Locale phone) {
        String at = phone + " question ";
        equal("Does the scooter's display show mph?", T.compatQuestionUnitsMph(), at + "units mph");
        equal("Does the scooter's display show km/h?", T.compatQuestionUnitsKmh(), at + "units km/h");
        equal("Is the tail light on?", T.compatQuestionTailLightOn(), at + "tail light on");
        equal("Is the tail light off?", T.compatQuestionTailLightOff(), at + "tail light off");
        equal("Is the tail light follow headlight?", T.compatQuestionTailLight("follow headlight"), at + "tail light other");
        equal("With a turn signal on, is the sound on?", T.compatQuestionTurnSoundOn(), at + "turn sound on");
        equal("With a turn signal on, is the sound off?", T.compatQuestionTurnSoundOff(), at + "turn sound off");
        equal("With a turn signal on, is the sound loud?", T.compatQuestionTurnSound("loud"), at + "turn sound other");
        equal("Are automatic lights on?", T.compatQuestionAutoLightsOn(), at + "auto lights on");
        equal("Are automatic lights off?", T.compatQuestionAutoLightsOff(), at + "auto lights off");
        equal("Are automatic lights 2?", T.compatQuestionAutoLights("2"), at + "auto lights other");
        equal("Did the range estimate on the scooter's display change?", T.compatQuestionRange(), at + "range");
        equal("Did you see it change?", T.compatQuestionDefault(), at + "default");
    }

    /** The values question() is asked about: the toggles' ids are "On"/"Off", the units' labels name mph or km/h. */
    private static void variants(Locale phone) throws Exception {
        for (String key : new String[]{"headlight", "turn_sound", "auto_sensor"}) {
            ScooterCommands.Feature f = ScooterCommands.find(key);
            for (String units : UNITS) {
                equal("On", DisplayUnits.featureValue(f, 1, units), phone + " " + key + " featureValue(1)");
                equal("Off", DisplayUnits.featureValue(f, 0, units), phone + " " + key + " featureValue(0)");
            }
        }
        ScooterCommands.Feature units = ScooterCommands.find("display_units");
        equal("Miles / mph", DisplayUnits.featureValue(units, 0, "mi"), phone + " display_units featureValue(0)");
        equal("Kilometers / km/h", DisplayUnits.featureValue(units, 1, "mi"), phone + " display_units featureValue(1)");
        int asked = 0;
        for (Map.Entry<String, FeatureVariant> e : ControlsEnglishTest.variants().entrySet()) {
            FeatureVariant v = e.getValue();
            for (int value : v.values) { equal(v.label(value), decision(v).label(value), phone + " variant " + e.getKey() + " " + value + " label()"); asked++; }
        }
        check(asked > 20, phone + ": every variant value is read (" + asked + ")");
    }

    private static void echoed(Locale phone) throws Exception {
        List<Map.Entry<String, FeatureVariant>> variants = new ArrayList<>(ControlsEnglishTest.variants().entrySet());
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, FeatureVariant> e : variants)
            for (int value : e.getValue().values) labels.add(decision(e.getValue()).label(value));
        List<ScooterCommands.Feature> features = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        List<String> english = new ArrayList<>();
        for (ScooterCommands.Feature f : allFeatures())
            for (int v : f.optionValues) {
                if (UnitCode.KMH.matches(f.unit) && !(v == 0 && f.zeroIsOff())) continue;
                features.add(f);
                values.add(v);
                english.add(f.valueLabel(v));
            }
        T.install(ECHO);
        try {
            int i = 0;
            for (Map.Entry<String, FeatureVariant> e : variants)
                for (int value : e.getValue().values)
                    equal(labels.get(i++), decision(e.getValue()).label(value), phone + " variant " + e.getKey() + " " + value + ": label() stays the English id under another source");
            for (int k = 0; k < features.size(); k++) {
                ScooterCommands.Feature f = features.get(k);
                for (String units : UNITS)
                    equal(english.get(k), DisplayUnits.featureValue(f, values.get(k), units),
                            phone + " featureValue(" + f.key + ", " + values.get(k) + ", " + units + ") stays English under another source");
            }
            check(features.size() > 20, phone + ": featureValue is checked for the catalogued values (" + features.size() + ")");
            for (String key : new String[]{"headlight", "turn_sound", "auto_sensor"}) {
                ScooterCommands.Feature f = ScooterCommands.find(key);
                equal("On", DisplayUnits.featureValue(f, 1, "mi"), phone + " " + key + ": featureValue(1) is still the On id question() matches");
                equal("Off", DisplayUnits.featureValue(f, 0, "mi"), phone + " " + key + ": featureValue(0) is still the Off id question() matches");
            }
            ScooterCommands.Feature units = ScooterCommands.find("display_units");
            check(DisplayUnits.featureValue(units, 0, "mi").contains("mph"), phone + " display_units 0 still names mph");
            check(!DisplayUnits.featureValue(units, 1, "mi").contains("mph"), phone + " display_units 1 does not name mph");
        } finally {
            T.install(null);
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static List<ScooterCommands.Feature> allFeatures() {
        List<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        features.addAll(ScooterCommands.oemOnly());
        return features;
    }

    private static CapabilityResolver.Decision decision(FeatureVariant v) {
        ModelRegistry.Rule rule = new ModelRegistry.Rule(CapabilityResolver.Support.KNOWN_ENCODING, CapabilityResolver.Reason.NONE,
                CapabilityResolver.Evidence.OEM_MAPPED, "", EnumSet.noneOf(CapabilityResolver.Precondition.class), v);
        return new CapabilityResolver.Decision(v.feature, ModelRegistry.Layer.OEM_GROUP, rule, CapabilityResolver.Reason.NONE, null, v.values);
    }

    private static void equal(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
        checks++;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
