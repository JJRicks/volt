package com.jjricks.volt;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * G5 characterization for F3 (text logic that reads display text back), committed before the batch changes
 * anything. F3 replaces string operations on shown text with choices made from numbers, keys and English ids, so
 * English stays byte-identical only if the new choices print what the old text operations printed, for every
 * input that reaches them. This pins, on the base, with the screen code's own steps where the screen class is
 * not one tests/run.sh builds:
 *   - the slider sheet's two ends (MainActivity.editFeature): the low end loses the unit the high end ends with,
 *     cut at the high end's last space, for every slider control and every units preference;
 *   - the observation question's fallback value (CompatibilityPanel.question): every value a toggle's question
 *     can be asked about that is not "On" or "Off" reads the same lower-cased, and its display text is its id;
 *   - the button labels' word-joined unit (CompatibilityPanel.unbroken): the km/h symbol;
 *   - the drive-mode cue (Ui.styleDriveMode from StatsScreen's shown value): the shown drive-mode value names
 *     SPORT, ECO or WALK exactly when Telemetry's English label for the code does;
 *   - the Live Stats row name fallback (StatsScreen.rowLabel): taking " (raw)" off the shown short name is the
 *     same as taking it off the English fallback label, for every reading StatsLabels.row does not name.
 * The phone is set to en-US, tr-TR, de-DE and ar-EG in turn.
 */
public final class TextLogicEnglishTest {
    private static int checks;
    private static final String[] UNITS = {"mi", "km", "dual", "x"};
    private static final String[] TOGGLES = {"headlight", "turn_sound", "auto_sensor"};

    /** Every slider control's ends as the sheet shows them: key, units, low end, high end. */
    static final String[][] SLIDER_ENDS = {
        {"start_speed", "mi", "0.0", "3.1 mph"},
        {"start_speed", "km", "0", "5 km/h"},
        {"start_speed", "dual", "0.0 mph / 0", "3.1 mph / 5 km/h"},
        {"start_speed", "x", "0.0", "3.1 mph"},
        {"speed_limit", "mi", "Off", "19.9 mph"},
        {"speed_limit", "km", "Off", "32 km/h"},
        {"speed_limit", "dual", "Off", "19.9 mph / 32 km/h"},
        {"speed_limit", "x", "Off", "19.9 mph"},
        {"drive_speed_limit", "mi", "Off", "12.4 mph"},
        {"drive_speed_limit", "km", "Off", "20 km/h"},
        {"drive_speed_limit", "dual", "Off", "12.4 mph / 20 km/h"},
        {"drive_speed_limit", "x", "Off", "12.4 mph"},
    };

    /** Telemetry's drive-mode products: the UT5 MAX, the GT5 Max, and one with no named modes. */
    static final int[] PRODUCTS = {2538, 25462, 22131, 0};
    static final Double[] CODES = {null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 1.0, 2.0, 2.5, 3.0, 4.0,
            5.0, 6.0, 7.0, 10.0, 11.0, 12.0, 255.0};

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        try {
            for (Locale phone : new Locale[]{Locale.US, new Locale("tr", "TR"), Locale.GERMANY, new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                T.install(null);
                sliderEnds(phone);
                questionValues(phone);
                unbrokenUnit(phone);
                driveModes(phone);
                rowNames(phone);
            }
        } finally {
            T.install(null);
            Locale.setDefault(saved);
        }
        System.out.println("TextLogicEnglishTest: " + checks + " checks passed (the English F3's text logic prints, under 4 phone locales)");
    }

    /** MainActivity.editFeature's ends, by its own steps: featureValue of max and min, then the shared-unit trim. */
    static String[] baseEnds(ScooterCommands.Feature feature, String units) {
        String high = DisplayUnits.featureValue(feature, feature.max, units), low = DisplayUnits.featureValue(feature, feature.min, units);
        int unitAt = high.lastIndexOf(' ');
        if (unitAt > 0 && low.endsWith(high.substring(unitAt))) low = low.substring(0, low.length() - (high.length() - unitAt));
        return new String[]{low, high};
    }

    /** Every control a slider sheet opens for: type SLIDER, in the catalogue or OEM-only. */
    static List<ScooterCommands.Feature> sliders() {
        List<ScooterCommands.Feature> all = new ArrayList<>(ScooterCommands.catalog());
        all.addAll(ScooterCommands.oemOnly());
        List<ScooterCommands.Feature> out = new ArrayList<>();
        for (ScooterCommands.Feature f : all) if (f.type == ScooterCommands.Type.SLIDER) out.add(f);
        return out;
    }

    private static void sliderEnds(Locale phone) {
        List<ScooterCommands.Feature> sliders = sliders();
        equal("3", String.valueOf(sliders.size()), phone + " slider controls");
        int seen = 0;
        for (ScooterCommands.Feature f : sliders) {
            check(UnitCode.KMH.matches(f.unit), phone + " " + f.key + " is a speed");
            for (String units : UNITS) {
                String[] expected = null;
                for (String[] row : SLIDER_ENDS) if (row[0].equals(f.key) && row[1].equals(units)) expected = row;
                check(expected != null, phone + " " + f.key + " " + units + " has an expected row");
                String[] ends = baseEnds(f, units);
                equal(expected[2], ends[0], phone + " " + f.key + " " + units + " low end");
                equal(expected[3], ends[1], phone + " " + f.key + " " + units + " high end");
                equal(ends[1], DisplayUnits.featureValueText(f, f.max, units), phone + " " + f.key + " " + units + " high end display text");
                seen++;
            }
        }
        equal(String.valueOf(SLIDER_ENDS.length), String.valueOf(seen), phone + " slider ends");
    }

    /**
     * The question's fallback: a value that is not exactly "On" or "Off" is put into the sentence lower-cased.
     * Every value it can see for the three toggles (each variant's label, the catalogue's valueLabel, and the
     * number a decision without a variant names) reads the same lower-cased, and a decision's labelText(v) and
     * the feature's valueText(v) print that same id, so the sentence is unchanged when it names the shown value.
     */
    private static void questionValues(Locale phone) throws Exception {
        int asked = 0;
        for (Map.Entry<String, FeatureVariant> e : ControlsEnglishTest.variants().entrySet()) {
            FeatureVariant v = e.getValue();
            if (!isToggle(v.feature)) continue;
            CapabilityResolver.Decision d = decision(v.feature, v);
            for (int value = -2; value <= 260; value++) {
                String id = d.label(value);
                fallbackSafe(id, phone + " variant " + e.getKey() + " " + value);
                equal(id, d.labelText(value).toString(), phone + " variant " + e.getKey() + " " + value + " labelText");
                String oem = d.oemLabel(value);
                if (oem != null) equal(oem, d.oemText(value).toString(), phone + " variant " + e.getKey() + " " + value + " oemText");
                asked++;
            }
        }
        for (String key : TOGGLES) {
            ScooterCommands.Feature f = ScooterCommands.find(key);
            CapabilityResolver.Decision ut5 = decision(key, FeatureVariant.ut5(f)), none = decision(key, null);
            for (int value = -2; value <= 260; value++) {
                for (String units : UNITS) {
                    String shown = DisplayUnits.featureValue(f, value, units);
                    fallbackSafe(shown, phone + " " + key + " featureValue " + value + " " + units);
                    equal(shown, DisplayUnits.featureValueText(f, value, units), phone + " " + key + " featureValueText " + value + " " + units);
                }
                fallbackSafe(ut5.label(value), phone + " " + key + " UT5 variant " + value);
                equal(ut5.label(value), ut5.labelText(value).toString(), phone + " " + key + " UT5 labelText " + value);
                fallbackSafe(none.label(value), phone + " " + key + " no variant " + value);
                equal(none.label(value), none.labelText(value).toString(), phone + " " + key + " no-variant labelText " + value);
                asked++;
            }
        }
        check(asked > 1000, phone + ": toggle values read (" + asked + ")");
    }

    private static boolean isToggle(String feature) {
        for (String key : TOGGLES) if (key.equals(feature)) return true;
        return false;
    }

    private static void fallbackSafe(String id, String what) {
        if ("On".equals(id) || "Off".equals(id)) { checks++; return; }
        equal(id, id.toLowerCase(Locale.ROOT), what + " reads the same lower-cased");
    }

    private static void unbrokenUnit(Locale phone) {
        String unit = T.unitsKmh();
        equal("km/h", unit, phone + " km/h symbol");
        equal("Keep 25 km/⁠h", T.compatKeepValue("25 km/h").replace("km/h", "km/⁠h"), phone + " keep button");
        equal("Restore to 15.5 mph", T.compatRestoreTo("15.5 mph").replace("km/h", "km/⁠h"), phone + " restore button in mph");
    }

    /**
     * StatsScreen.display for a known drive-mode reading with a code shows Telemetry.displayValue of the English
     * label; Ui.DriveMode.of matches "SPORT", "ECO" and "WALK". Without a code it shows displayValue of the
     * stored value.
     */
    private static void driveModes(Locale phone) {
        int named = 0;
        for (int product : PRODUCTS)
            for (Double code : CODES) {
                if (code == null) continue;
                String english = Telemetry.drivingModeLabel(product, code);
                String shown = Telemetry.displayValue("driving_mode", english, code);
                boolean mode = isMode(shown);
                equal(String.valueOf(isMode(english)), String.valueOf(mode), phone + " product " + product + " code " + code + " names a mode");
                if (mode) { equal(english, shown, phone + " product " + product + " code " + code + " shown mode"); named++; }
            }
        equal("6", String.valueOf(named), phone + " named drive modes");
        for (String stored : new String[]{"SPORT", "ECO", "WALK", "DRIVE", "3 (raw)", "3", "—", ""}) {
            String shown = Telemetry.displayValue("driving_mode", stored, null);
            equal(String.valueOf(isMode(stored)), String.valueOf(isMode(shown)), phone + " stored " + stored + " names a mode");
            if (isMode(stored)) equal(stored, shown, phone + " stored " + stored + " shown");
        }
    }

    private static boolean isMode(String text) {
        return "SPORT".equals(text) || "ECO".equals(text) || "WALK".equals(text);
    }

    private static void rowNames(Locale phone) {
        int fallback = 0;
        List<String[]> readings = new ArrayList<>();
        for (Telemetry.Metric metric : new Telemetry().metrics()) readings.add(new String[]{metric.key, metric.label});
        readings.add(new String[]{"response_7a", "Response 0x7A (raw)"});
        readings.add(new String[]{"unknown_reading", "Unknown reading"});
        for (String[] reading : readings) {
            if (StatsLabels.row(reading[0]) != null) continue;
            String shown = ScooterReadingLabels.label(reading[0], reading[1]).replace(" (raw)", "");
            equal(shown, ScooterReadingLabels.label(reading[0], reading[1].replace(" (raw)", "")),
                    phone + " " + reading[0] + " row name");
            fallback++;
        }
        check(fallback > 20, phone + ": row-name fallbacks (" + fallback + ")");
        equal("Response 0x7A", ScooterReadingLabels.label("response_7a", "Response 0x7A (raw)").replace(" (raw)", ""), phone + " fallback");
        equal(T.statsScooterRange(), T.statsScooterRange().replace(" (raw)", ""), phone + " scooter range name");
        equal(T.statsDriveMode(), T.statsDriveMode().replace(" (raw)", ""), phone + " drive mode name");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static CapabilityResolver.Decision decision(String feature, FeatureVariant v) {
        ModelRegistry.Rule rule = new ModelRegistry.Rule(CapabilityResolver.Support.KNOWN_ENCODING, CapabilityResolver.Reason.NONE,
                CapabilityResolver.Evidence.OEM_MAPPED, "", EnumSet.noneOf(CapabilityResolver.Precondition.class), v);
        return new CapabilityResolver.Decision(feature, ModelRegistry.Layer.OEM_GROUP, rule, CapabilityResolver.Reason.NONE, null,
                v == null ? new int[0] : v.values);
    }

    static void equal(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
        checks++;
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
