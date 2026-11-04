package com.jjricks.volt;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * G5 characterization for F2 (BleController, CompatibilityPanel, GuidedTest and DisplayUnits switching to the
 * wave-2 display accessors), committed before the batch changes anything. F2 replaces English reads with the
 * display accessors B4, B7 and B9 added, so English stays byte-identical only if each accessor prints exactly the
 * English it replaces, for every input; and the logs, the journal and the reports stay English only if each
 * accessor's english() is that English whatever text source is installed. This pins both, on the base:
 *   - every catalogue and OEM-only feature: label() renders label; valueText(v) renders valueLabel(v) over its
 *     domain and around it;
 *   - every FeatureVariant ModelRegistry holds: text(v) renders label(v), and an OEM-group Decision's labelText(v)
 *     renders its label(v);
 *   - every CapabilityResolver.Reason: a Decision's reasonMessage() renders its reasonText();
 *   - ModelRegistry.modelLineText renders modelLine for every descriptor ControlsEnglishTest lists;
 *   - GuidedTest.Outcome.label(token) prints describe(token) for every outcome and code;
 *   - each control builder refusal that is a ScooterCommands.Refused: msg() renders getMessage();
 *   - DisplayUnits.featureValue prints valueLabel(v) (then valueText(v)) for every value that is not a speed;
 *   - the Compatibility panel's group headings print its English group ids;
 * and, under a key-echo source, every english() above is still the English (what logs and reports keep).
 * The phone is set to en-US, tr-TR, de-DE and ar-EG in turn.
 */
public final class ControllerConsumersTextTest {
    private static int checks;
    private static final int[] VALUES = {-1, 0, 1, 2, 3, 4, 5, 8, 20, 25, 32, 33, 40, 50, 70, 80, 81, 100, 101, 255};

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
                features(phone);
                variants(phone);
                reasons(phone);
                modelLines(phone);
                outcomes(phone);
                refusals(phone);
                displayUnits(phone);
                groups(phone);
            }
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("ControllerConsumersTextTest: " + checks + " checks passed (display accessors print the English ids F2's consumers read, under 4 phone locales)");
    }

    private static List<ScooterCommands.Feature> allFeatures() {
        List<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        features.addAll(ScooterCommands.oemOnly());
        return features;
    }

    private static void features(Locale phone) {
        for (ScooterCommands.Feature f : allFeatures()) {
            String at = phone + " feature " + f.key;
            shown(f.label, f.label(), at + " label()");
            for (int v : values(f.optionValues, f.min, f.max)) shown(f.valueLabel(v), f.valueText(v), at + " valueText(" + v + ")");
        }
    }

    private static void variants(Locale phone) throws Exception {
        for (Map.Entry<String, FeatureVariant> e : ControlsEnglishTest.variants().entrySet()) {
            FeatureVariant v = e.getValue();
            CapabilityResolver.Decision d = decision(v, ModelRegistry.Layer.OEM_GROUP, CapabilityResolver.Reason.NONE);
            for (int value : values(v.values, v.min, v.max)) {
                String at = phone + " variant " + e.getKey() + " " + value;
                shown(v.label(value), v.text(value), at + " text()");
                shown(d.label(value), d.labelText(value), at + " Decision.labelText()");
            }
        }
        CapabilityResolver.Decision none = new CapabilityResolver.Decision("lock", ModelRegistry.Layer.SHARED,
                rule(null), CapabilityResolver.Reason.NONE, null, null);
        for (int value : VALUES) shown(none.label(value), none.labelText(value), phone + " no variant labelText(" + value + ")");
    }

    private static void reasons(Locale phone) throws Exception {
        FeatureVariant any = ControlsEnglishTest.variants().values().iterator().next();
        for (CapabilityResolver.Reason r : CapabilityResolver.Reason.values()) {
            CapabilityResolver.Decision d = decision(any, ModelRegistry.Layer.UT5_MAX, r);
            String english = d.reasonText();
            equal(CapabilityResolver.text(r), english, phone + " reason " + r + " reasonText() is text(reason)");
            CharSequence message = d.reasonMessage();
            equal(english, message.toString(), phone + " reason " + r + " reasonMessage() renders reasonText()");
            T.install(ECHO);
            try {
                equal(english, english(message), phone + " reason " + r + " reasonMessage() keeps its English under another source");
            } finally {
                T.install(null);
            }
        }
    }

    private static void modelLines(Locale phone) {
        for (Map.Entry<String, DeviceDescriptor> e : ControlsEnglishTest.descriptors().entrySet()) {
            String english = ModelRegistry.modelLine(e.getValue());
            CharSequence shown = ModelRegistry.modelLineText(e.getValue());
            equal(english, shown.toString(), phone + " modelLineText(" + e.getKey() + ")");
            T.install(ECHO);
            try {
                equal(english, ModelRegistry.modelLine(e.getValue()), phone + " modelLine(" + e.getKey() + ") stays English under another source");
                equal(english, english(ModelRegistry.modelLineText(e.getValue())), phone + " modelLineText(" + e.getKey() + ") keeps its English");
            } finally {
                T.install(null);
            }
        }
    }

    private static void outcomes(Locale phone) {
        for (GuidedTest.Outcome o : GuidedTest.Outcome.values())
            for (int code = 0; code <= 255; code++) {
                String token = o.token(code);
                equal(GuidedTest.Outcome.describe(token), GuidedTest.Outcome.label(token), phone + " Outcome.label(" + token + ")");
                if (!o.carries()) break;
            }
        for (String token : new String[]{null, "", "nope", "rejected_", "rejected_256", "rejected_01", "read_supported_1"})
            equal(GuidedTest.Outcome.describe(token), GuidedTest.Outcome.label(token), phone + " Outcome.label(" + token + ")");
    }

    private interface Call { Object run(); }

    private static void refusals(Locale phone) throws Exception {
        List<Call> calls = new ArrayList<>();
        for (ScooterCommands.Feature f : allFeatures())
            for (int v : values(f.optionValues, f.min, f.max)) {
                String key = f.key;
                calls.add(() -> ScooterCommands.set(key, v));
            }
        for (boolean owner : new boolean[]{false, true}) {
            for (String digits : new String[]{null, "", "123", "1234", "12a4", "12345", "٠١٢٣"})
                calls.add(() -> ScooterCommands.setPin(digits, owner));
            for (boolean enabled : new boolean[]{false, true})
                for (boolean known : new boolean[]{false, true})
                    calls.add(() -> ScooterCommands.enablePin(enabled, owner, known));
            for (boolean enabled : new boolean[]{false, true})
                for (boolean bonded : new boolean[]{false, true})
                    calls.add(() -> ScooterCommands.setProximity(enabled, owner, bonded, null));
        }
        for (FeatureVariant variant : ControlsEnglishTest.variants().values())
            for (int v : new int[]{-1, 0, 1, 7, 33, 101}) calls.add(() -> variant.encode(v));
        int refused = 0;
        for (int i = 0; i < calls.size(); i++) {
            try {
                calls.get(i).run();
            } catch (ScooterCommands.Refused e) {
                refused++;
                equal(e.getMessage(), e.msg().render(), phone + " refusal " + i + ": msg() renders getMessage()");
                T.install(ECHO);
                try {
                    equal(e.getMessage(), e.msg().english(), phone + " refusal " + i + ": msg() keeps getMessage() as its English");
                } finally {
                    T.install(null);
                }
            } catch (RuntimeException other) {
                // A developer error stays English and is shown as it is.
            }
        }
        check(refused > 10, phone + ": the builders refuse with Refused (" + refused + ")");
    }

    private static void displayUnits(Locale phone) {
        for (ScooterCommands.Feature f : allFeatures()) {
            if (UnitCode.KMH.matches(f.unit)) continue;
            for (int v : values(f.optionValues, f.min, f.max))
                for (String units : new String[]{"km", "mi", "dual", null}) {
                    String shown = DisplayUnits.featureValue(f, v, units);
                    equal(f.valueLabel(v), shown, phone + " featureValue(" + f.key + ", " + v + ", " + units + ")");
                    equal(f.valueText(v).render(), shown, phone + " featureValue(" + f.key + ", " + v + ") is valueText");
                }
        }
        for (ScooterCommands.Feature f : allFeatures()) {
            if (!UnitCode.KMH.matches(f.unit) || !f.zeroIsOff()) continue;
            for (String units : new String[]{"km", "mi", "dual", null}) {
                equal(f.valueLabel(0), DisplayUnits.featureValue(f, 0, units), phone + " featureValue(" + f.key + ", 0) reads Off");
                equal(f.valueText(0).render(), DisplayUnits.featureValue(f, 0, units), phone + " featureValue(" + f.key + ", 0) is valueText");
            }
        }
    }

    private static void groups(Locale phone) {
        equal("Reads", T.compatGroupReads(), phone + " the Reads heading is its id");
        equal("Fields", T.compatGroupFields(), phone + " the Fields heading is its id");
        equal("Controls", T.compatGroupControls(), phone + " the Controls heading is its id");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** The accessor renders the English now, and its english() is the English under another source. */
    private static void shown(String english, CharSequence accessor, String what) {
        equal(english, accessor.toString(), what + " renders the English");
        T.install(ECHO);
        try {
            equal(english, english(accessor), what + " keeps the English under another source");
        } finally {
            T.install(null);
        }
    }

    private static String english(CharSequence text) {
        return text instanceof Msg ? ((Msg) text).english() : text.toString();
    }

    private static int[] values(int[] domain, int min, int max) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int v : domain) { out.add(v); out.add(v + 1); }
        for (int v : VALUES) out.add(v);
        if (max - min <= 40) for (int v = min - 1; v <= max + 1; v++) out.add(v);
        int[] values = new int[out.size()];
        int i = 0;
        for (int v : out) values[i++] = v;
        return values;
    }

    private static ModelRegistry.Rule rule(FeatureVariant v) {
        return new ModelRegistry.Rule(v == null ? CapabilityResolver.Support.UNKNOWN_MAPPING : CapabilityResolver.Support.KNOWN_ENCODING,
                CapabilityResolver.Reason.NONE, CapabilityResolver.Evidence.OEM_MAPPED, "", EnumSet.noneOf(CapabilityResolver.Precondition.class), v);
    }

    private static CapabilityResolver.Decision decision(FeatureVariant v, ModelRegistry.Layer layer, CapabilityResolver.Reason reason) {
        return new CapabilityResolver.Decision(v.feature, layer, rule(v), reason, null, v.values);
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
