package com.jjricks.volt;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4 (controls catalogue and capability), beyond the characterization test, which pins the English the
 * producers keep (labels, groups, options, reasons, refusals, cautions, the model line, series, drive modes):
 *
 *  - Display accessors in English: every display accessor B4 adds (Feature.label(), description(),
 *    groupLabel(), optionLabel(), valueText(); FeatureVariant.text(); Decision.labelText(), labelTexts(),
 *    oemText(), reasonMessage(); ModelRegistry.modelLineText; NaveeModels.Series.aboutLabel;
 *    DriveModeSurvey.Label.label() and uiSummary()) renders, with English installed, exactly the English
 *    its data field or method gives, and its english() is that text too.
 *  - Display accessors in another language: with a source that marks every catalogue text, every one of
 *    them comes from the catalogue, apart from what stays as the scooter or NAVEE names it (Eco, Turbo,
 *    model names, bare numbers) and a group the catalogue has no name for; every reason but NONE, every
 *    caution, the refusals a person can reach (ScooterCommands.Refused, still IllegalArgumentExceptions)
 *    and the drive-mode names come from it, while getMessage(), the labels, modelLine and summary() stay
 *    English. Every controls_option_* entry is the shown form of some table label.
 *  - Search: NaveeModels.search finds a series by its description in the shown language (display-locale
 *    lower case, accents stripped), and still by its English one.
 *  - DriveModeStep's screen templates (reported mode, Named:) equal the base's inline concatenations.
 */
public final class ControlsTextTest {
    private static int checks;

    /** A language whose every text is the English wrapped in marks, placeholders kept. */
    static final class Marked implements T.Source {
        public String string(int id) { return "«" + EnglishText.INSTANCE.string(id) + "»"; }
        public String plural(int id, long quantity) { return "«" + EnglishText.INSTANCE.plural(id, quantity) + "»"; }
        public String language() { return "en"; }
    }

    public static void main(String[] args) throws Exception {
        T.Source saved = T.source();
        try {
            T.install(null);
            english();
            T.install(new Marked());
            marked();
            search();
        } finally {
            T.install(saved);
        }
        System.out.println("ControlsTextTest: " + checks + " checks passed (display accessors in English and marked, refusals, search)");
    }

    private static List<ScooterCommands.Feature> features() {
        List<ScooterCommands.Feature> out = new ArrayList<>(ScooterCommands.catalog());
        out.addAll(ScooterCommands.oemOnly());
        return out;
    }

    private static int[] values(FeatureVariant v) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int x : v.values) out.add(x);
        for (int x = 0; x <= 101; x++) out.add(x);
        if (v.sentinel >= 0) out.add(v.sentinel);
        out.add(255);
        int[] values = new int[out.size()];
        int i = 0;
        for (int x : out) values[i++] = x;
        return values;
    }

    private static CapabilityResolver.Decision decision(FeatureVariant v) {
        return new CapabilityResolver.Decision(v.feature, ModelRegistry.Layer.OEM_GROUP,
                new ModelRegistry.Rule(CapabilityResolver.Support.KNOWN_ENCODING, CapabilityResolver.Reason.NONE,
                        CapabilityResolver.Evidence.OEM_MAPPED, "", EnumSet.noneOf(CapabilityResolver.Precondition.class), v),
                CapabilityResolver.Reason.NONE, null, v.values);
    }

    /** English installed: each accessor shows exactly the English its data keeps. */
    private static void english() throws Exception {
        for (ScooterCommands.Feature f : features()) {
            same(f.label().toString(), f.label, f.key + " label");
            same(f.label().english(), f.label, f.key + " label english");
            same(f.description().toString(), f.description, f.key + " description");
            same(f.groupLabel().toString(), f.group, f.key + " group");
            for (int i = 0; i < f.options.length; i++) same(f.optionLabel(i).toString(), f.options[i], f.key + " option " + i);
            for (int v = 0; v <= 102; v++) {
                same(f.valueText(v).toString(), f.valueLabel(v), f.key + " value " + v);
                same(f.valueText(v).english(), f.valueLabel(v), f.key + " value english " + v);
            }
        }
        for (Map.Entry<String, FeatureVariant> e : ControlsEnglishTest.variants().entrySet()) {
            FeatureVariant v = e.getValue();
            CapabilityResolver.Decision d = decision(v);
            for (int value : values(v)) {
                same(v.text(value).toString(), v.label(value), e.getKey() + " text " + value);
                same(d.labelText(value).toString(), d.label(value), e.getKey() + " decision " + value);
                String oem;
                try { oem = d.oemLabel(value); } catch (IllegalArgumentException noFeature) { continue; }
                Msg shown = d.oemText(value);
                check(oem == null ? shown == null : shown != null && shown.toString().equals(oem), e.getKey() + " oemText " + value);
            }
            Msg[] texts = d.labelTexts();
            String[] labels = d.labels();
            check(texts.length == labels.length, e.getKey() + " labelTexts length");
            for (int i = 0; i < labels.length; i++) same(texts[i].toString(), labels[i], e.getKey() + " labelTexts " + i);
        }
        for (CapabilityResolver.Reason r : CapabilityResolver.Reason.values()) {
            same(CapabilityResolver.message(r).toString(), CapabilityResolver.text(r), "reason " + r);
        }
        for (Map.Entry<String, DeviceDescriptor> e : ControlsEnglishTest.descriptors().entrySet()) {
            CharSequence line = ModelRegistry.modelLineText(e.getValue());
            same(line.toString(), ModelRegistry.modelLine(e.getValue()), "modelLineText " + e.getKey());
        }
        for (NaveeModels.Series s : NaveeModels.SERIES) same(s.aboutLabel.toString(), s.about, "series " + s.name);
        for (DriveModeSurvey.Label label : DriveModeSurvey.Label.values()) same(label.label().toString(), label.text, "drive mode " + label);
        for (DriveModeSurvey s : sessions()) same(s.uiSummary().toString(), s.summary(), "uiSummary " + s.summary());
        // DriveModeStep's two screen templates, against the concatenations the base built inline in refresh().
        for (int code : new int[]{0, 1, 2, 3, 7, 11, 255, -1, -128, 1000, 12345, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            Integer boxed = code;
            same(T.controlsDriveModeReported(Fmt.integer(boxed)).toString(), "Your scooter reports mode " + boxed, "drive reported " + code);
        }
        for (DriveModeSurvey s : sessions()) same(T.controlsDriveModeNamed(s.uiSummary()).toString(), "Named: " + s.summary(), "drive named " + s.summary());
        for (String odd : new String[]{"%1$s", "%s %n", "100%", "a\\b", "$1", ""}) same(T.controlsDriveModeNamed(odd).toString(), "Named: " + odd, "drive named " + odd);
    }

    /** Naming sessions over every order of the four mode names and Not sure, one to five modes. */
    private static List<DriveModeSurvey> sessions() {
        List<DriveModeSurvey> out = new ArrayList<>();
        DriveModeSurvey.Label[] labels = DriveModeSurvey.Label.values();
        for (int a = 0; a < labels.length; a++)
            for (int b = -1; b < labels.length; b++)
                for (int c = -1; c < labels.length; c++) {
                    DriveModeSurvey s = new DriveModeSurvey();
                    int code = 1;
                    for (int pick : new int[]{a, b, c, a == 4 ? 4 : -1}) {
                        if (pick < 0) continue;
                        s.observe(code++);
                        s.name(labels[pick]);
                    }
                    out.add(s);
                }
        return out;
    }

    private static boolean marked(CharSequence text) {
        String s = text.toString();
        return s.startsWith("«") || s.contains("«");
    }

    /** Another language installed: what a screen shows comes from the catalogue; the English data stays. */
    private static void marked() throws Exception {
        Set<Integer> optionIdsShown = new LinkedHashSet<>();
        for (ScooterCommands.Feature f : features()) {
            check(marked(f.label()), f.key + " label from the catalogue");
            check(!marked(f.label), f.key + " label field stays English");
            check(f.description.isEmpty() ? f.description().length() == 0 : marked(f.description()), f.key + " description from the catalogue");
            check(marked(f.groupLabel()), f.key + " group from the catalogue");
            check(!marked(f.group), f.key + " group id stays English");
            for (int i = 0; i < f.options.length; i++) {
                Msg shown = f.optionLabel(i);
                if (shown.id() >= 0) optionIdsShown.add(shown.id());
                check(marked(shown) || verbatimByDesign(f.options[i]), f.key + " option " + f.options[i]);
            }
            for (int v : f.optionValues) check(marked(f.valueText(v)) || verbatimByDesign(f.valueLabel(v)), f.key + " value " + v);
        }
        for (Map.Entry<String, FeatureVariant> e : ControlsEnglishTest.variants().entrySet()) {
            FeatureVariant v = e.getValue();
            for (int value : values(v)) {
                String english = v.label(value);
                Msg shown = v.text(value);
                if (shown.id() >= 0) optionIdsShown.add(shown.id());
                check(marked(shown) || verbatimByDesign(english), e.getKey() + " label " + value + " \"" + english + "\" comes from the catalogue");
                check(shown.english().equals(english), e.getKey() + " label " + value + " english");
            }
        }
        // Every option entry is what some table label shows.
        for (int id : ScooterCommands.Options.ids()) check(optionIdsShown.contains(id), T.key(id) + " is shown for some table label");
        for (String key : new String[]{"controls_option_hours", "controls_option_sound", "controls_option_default", "controls_option_percent_spaced"}) {
            boolean seen = false;
            for (int id : optionIdsShown) seen |= T.key(id).equals(key);
            check(seen, key + " is shown for some table label");
        }
        for (CapabilityResolver.Reason r : CapabilityResolver.Reason.values()) {
            CharSequence shown = CapabilityResolver.message(r);
            check(r == CapabilityResolver.Reason.NONE ? shown.length() == 0 : marked(shown) && shown instanceof Msg, "reason " + r + " from the catalogue");
            check(r == CapabilityResolver.Reason.NONE || marked(CapabilityResolver.text(r)), "reason text " + r + " rendered from the catalogue");
            if (shown instanceof Msg) check(!marked(((Msg) shown).english()), "reason " + r + " english");
        }
        // Cautions: whole templates from the catalogue; the English characterization pins their English.
        int cautions = 0;
        for (Map.Entry<String, FeatureVariant> e : ControlsEnglishTest.variants().entrySet())
            for (String note : new String[]{"", "OEM 2.1.9 asks before speeds above 32 km/h"})
                for (int value : new int[]{0, 1, 2, 33, 40, 70}) {
                    CapabilityResolver.Decision d = new CapabilityResolver.Decision(e.getValue().feature, ModelRegistry.Layer.OEM_GROUP,
                            new ModelRegistry.Rule(CapabilityResolver.Support.KNOWN_ENCODING, CapabilityResolver.Reason.NONE,
                                    CapabilityResolver.Evidence.OEM_MAPPED, note, EnumSet.noneOf(CapabilityResolver.Precondition.class), e.getValue()),
                            CapabilityResolver.Reason.NONE, null, e.getValue().values);
                    String text = ModelRegistry.caution(d, value);
                    if (text == null) continue;
                    cautions++;
                    check(text.startsWith("«") && text.indexOf("«", 1) > 0, "caution " + e.getKey() + " " + value + " is two catalogue sentences: " + text);
                }
        check(cautions > 0, "some caution was shown");
        for (String key : new String[]{"traction_control", "start_speed", "energy_recovery", "speed_limit"})
            for (int value : new int[]{0, 1, 2, 5})
                if (ModelRegistry.experimentalCaution(key, value) != null) check(marked(ModelRegistry.experimentalCaution(key, value)), "experimental " + key);
        // The model line: the words come from the catalogue; modelLine stays English for reports.
        for (Map.Entry<String, DeviceDescriptor> e : ControlsEnglishTest.descriptors().entrySet()) {
            CharSequence shown = ModelRegistry.modelLineText(e.getValue());
            String english = ModelRegistry.modelLine(e.getValue());
            check(!marked(english), "modelLine " + e.getKey() + " stays English");
            check(english.startsWith("Model ") == marked(shown), "modelLineText " + e.getKey() + " words from the catalogue: " + shown);
        }
        for (NaveeModels.Series s : NaveeModels.SERIES) {
            check(marked(s.aboutLabel), "series " + s.name + " from the catalogue");
            check(!marked(s.about), "series " + s.name + " English stays");
        }
        check(marked(DriveModeSurvey.Label.UNSURE.label()), "Not sure from the catalogue");
        for (DriveModeSurvey.Label label : DriveModeSurvey.Label.values()) {
            check(!marked(label.text), label + " text stays English");
            if (label != DriveModeSurvey.Label.UNSURE) check(label.label().toString().equals(label.text), label + " keeps the scooter's name");
        }
        for (DriveModeSurvey s : sessions()) {
            check(!marked(s.summary()), "summary stays English");
            check(s.count() < 2 ? !s.uiSummary().toString().contains("→") || marked(s.uiSummary()) : marked(s.uiSummary()), "uiSummary joins in the catalogue");
        }
        refusals();
    }

    private interface Call { Object run(); }

    private static void refused(String what, Call call, boolean shown) {
        try {
            call.run();
            check(false, what + " was refused");
        } catch (ScooterCommands.Refused refused) {
            check(shown, what + " is a refusal a person sees");
            check(marked(refused.msg()), what + " shows catalogue text");
            check(!marked(refused.getMessage()) && refused.getMessage().equals(refused.msg().english()), what + " keeps English in getMessage()");
            check(refused instanceof IllegalArgumentException, what + " is still an IllegalArgumentException");
        } catch (IllegalArgumentException developer) {
            check(!shown, what + " is a developer error: " + developer.getMessage());
            check(!marked(developer.getMessage()), what + " stays English");
        }
    }

    private static void refusals() {
        refused("readPin", () -> ScooterCommands.readPin(false), true);
        refused("setPin owner", () -> ScooterCommands.setPin("1234", false), true);
        refused("setPin digits", () -> ScooterCommands.setPin("12a4", true), true);
        refused("enablePin owner", () -> ScooterCommands.enablePin(true, false, true), true);
        refused("enablePin unknown", () -> ScooterCommands.enablePin(true, true, false), true);
        refused("setProximity owner", () -> ScooterCommands.setProximity(true, false, true, null), true);
        refused("setProximity bond", () -> ScooterCommands.setProximity(true, true, false, null), true);
        refused("setProximity pin", () -> ScooterCommands.setProximity(true, true, true, null), true);
        refused("parsePin owner", () -> ScooterCommands.parsePin(null, false), true);
        refused("pinReadbackMatches owner", () -> ScooterCommands.pinReadbackMatches(null, true, null, false), true);
        refused("set outside", () -> ScooterCommands.set("charge_limit", 81), true);
        refused("set not available", () -> ScooterCommands.set("range_algorithm", 0, null), true);
        refused("encode outside", () -> ModelRegistry.OEM_CHARGE_LIMIT.encode(81), true);
        refused("find unknown", () -> ScooterCommands.find("nope"), false);
        refused("set dedicated", () -> ScooterCommands.set("pin", 0), false);
        refused("setParams null", () -> ScooterCommands.setParams(null, 1), false);
        refused("parsePin shape", () -> ScooterCommands.parsePin(null, true), false);
        refused("encode dedicated", () -> ModelRegistry.CLOCK.encode(0), false);
    }

    /** Text that stays as the scooter or NAVEE shows it: mode names and bare numbers. */
    private static boolean verbatimByDesign(String english) {
        return english.equals("Eco") || english.equals("Turbo") || english.matches("[0-9]+");
    }

    /** Search finds a series by its shown description and by its English one. */
    private static void search() {
        T.install(new T.Source() {
            public String string(int id) {
                return T.key(id).equals("controls_series_k") ? "Enfants" : EnglishText.INSTANCE.string(id);
            }
            public String plural(int id, long quantity) { return EnglishText.INSTANCE.plural(id, quantity); }
            public String language() { return "en"; }
        });
        List<String> kids = NaveeModels.SERIES.get(NaveeModels.SERIES.size() - 1).models;
        check(NaveeModels.search("enfant").equals(kids), "the shown description finds the Kids series: " + NaveeModels.search("enfant"));
        check(NaveeModels.search("kids").equals(kids), "the English description still finds it");
        check(NaveeModels.search("zzz").isEmpty(), "nothing else matches");
        check(NaveeModels.search("").size() == NaveeModels.all().size(), "an empty query lists every model");
        T.install(null);
        check(NaveeModels.search("enfant").isEmpty(), "in English the French word finds nothing");
        // Shown descriptions fold with the display locale's lower case and without accents (plan 4.6).
        installK("Niños");
        check(NaveeModels.search("ninos").equals(kids), "an unaccented query finds an accented description: " + NaveeModels.search("ninos"));
        check(NaveeModels.search("NIÑOS").equals(kids), "an accented capital query finds it too");
        java.util.Locale phone = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            installK("Çocuklar İçin");
            check(NaveeModels.search("için").equals(kids), "a Turkish lower-case query finds İçin: " + NaveeModels.search("için"));
            check(NaveeModels.search("İÇİN").equals(kids), "a Turkish capital query finds it: " + NaveeModels.search("İÇİN"));
            check(NaveeModels.search("cocuk").equals(kids), "an unaccented Turkish query finds it");
        } finally {
            java.util.Locale.setDefault(phone);
        }
        T.install(null);
        check(NaveeModels.displayFold("Kids").equals(NaveeModels.fold("Kids")), "displayFold agrees with fold on plain English");
        check(NaveeModels.search("kïds").isEmpty(), "in English an accented query still matches only as fold does");
    }

    private static void installK(String text) {
        T.install(new T.Source() {
            public String string(int id) { return T.key(id).equals("controls_series_k") ? text : EnglishText.INSTANCE.string(id); }
            public String plural(int id, long quantity) { return EnglishText.INSTANCE.plural(id, quantity); }
            public String language() { return "en"; }
        });
    }

    private static void same(String actual, String expected, String what) {
        check(actual.equals(expected), what + ": expected \"" + expected + "\" but was \"" + actual + "\"");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
