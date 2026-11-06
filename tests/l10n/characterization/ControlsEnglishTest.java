package com.jjricks.volt;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * G5 characterization for B4 (controls catalogue and capability), committed before the batch changes
 * anything. It lists the English every control text producer gives today, for every input the
 * registry can produce, and compares the listing with controls-english.tsv line for line:
 *   - CapabilityResolver.text for every Reason;
 *   - every ScooterCommands feature (catalogue and OEM-only): label, group, description, unit, options,
 *     valueLabel over its domain and around it, and searchText;
 *   - every FeatureVariant ModelRegistry holds (its static fields, the Ut5 holder and the try table):
 *     label over its domain and around it, plus an OEM-group Decision's label (where it differs) and
 *     oemLabel (where it is not null);
 *   - ModelRegistry.caution for every variant on the OEM-group layer with each kind of OEM note, and
 *     experimentalCaution for every feature key (in and, where it differs, outside the low-start region);
 *   - ModelRegistry.modelLine for unknown, known, serial-only, advertised, conflicting and simulated
 *     scooters;
 *   - NaveeModels' series names and descriptions and search results;
 *   - DriveModeSurvey's labels and summaries;
 *   - the refusals a person can reach through the control builders: each one's getMessage(), and that
 *     it is still an IllegalArgumentException, plus PinState.toString.
 * The listing is the same with the phone set to en-US, de-DE, tr-TR and ar-EG.
 *
 * Run with "--write" from the repository root to print the listing (used once, on the base).
 */
public final class ControlsEnglishTest {
    static final String LISTING = "tests/l10n/characterization/controls-english.tsv";
    private static final int[] VALUES = {-1, 0, 1, 2, 3, 4, 5, 8, 20, 25, 32, 33, 40, 50, 70, 80, 81, 100, 101, 255};

    public static void main(String[] args) throws Exception {
        Locale saved = Locale.getDefault();
        List<String> first = null;
        try {
            for (Locale phone : new Locale[]{Locale.US, Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "EG")}) {
                Locale.setDefault(phone);
                List<String> lines = listing();
                if (first == null) first = lines;
                else if (!first.equals(lines)) throw new AssertionError(phone + ": the listing differs from en-US");
            }
        } finally {
            Locale.setDefault(saved);
        }
        if (args.length > 0 && args[0].equals("--write")) {
            for (String line : first) System.out.println(line);
            return;
        }
        List<String> expected = Files.readAllLines(Paths.get(LISTING), StandardCharsets.UTF_8);
        int n = Math.min(expected.size(), first.size());
        for (int i = 0; i < n; i++)
            if (!expected.get(i).equals(first.get(i)))
                throw new AssertionError(LISTING + ":" + (i + 1) + ": expected \"" + expected.get(i) + "\" but was \"" + first.get(i) + "\"");
        if (expected.size() != first.size())
            throw new AssertionError(LISTING + ": expected " + expected.size() + " lines but the listing has " + first.size());
        System.out.println("ControlsEnglishTest: " + first.size() + " lines of control English pinned, in 4 phone locales");
    }

    /** The whole listing, one "what\ttext" line each; "null" stands for a null result. */
    static List<String> listing() throws Exception {
        List<String> out = new ArrayList<>();
        for (CapabilityResolver.Reason r : CapabilityResolver.Reason.values())
            out.add("reason " + r + "\t" + CapabilityResolver.text(r));

        List<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        features.addAll(ScooterCommands.oemOnly());
        for (ScooterCommands.Feature f : features) {
            String at = "feature " + f.key + " ";
            out.add(at + "label\t" + f.label);
            out.add(at + "group\t" + f.group);
            out.add(at + "description\t" + f.description);
            out.add(at + "unit\t" + f.unit);
            out.add(at + "search\t" + f.searchText());
            for (int i = 0; i < f.options.length; i++) out.add(at + "option " + i + "\t" + f.options[i]);
            for (int v : values(f.optionValues, f.min, f.max)) out.add(at + "value " + v + "\t" + f.valueLabel(v));
        }

        Map<String, FeatureVariant> variants = variants();
        for (Map.Entry<String, FeatureVariant> e : variants.entrySet()) {
            FeatureVariant v = e.getValue();
            String at = "variant " + e.getKey() + " ";
            out.add(at + "id\t" + v.id);
            for (int value : values(v.values, v.min, v.max)) {
                out.add(at + "label " + value + "\t" + v.label(value));
                CapabilityResolver.Decision d = decision(v, ModelRegistry.Layer.OEM_GROUP, CapabilityResolver.Evidence.OEM_MAPPED, "");
                if (!d.label(value).equals(v.label(value))) out.add(at + "decision label " + value + "\t" + d.label(value));
                int shown = value;
                String oem = quietly(() -> d.oemLabel(shown));
                if (!oem.equals("null")) out.add(at + "decision oemLabel " + value + "\t" + oem);
            }
        }
        out.add("decision none label 7\t" + new CapabilityResolver.Decision("lock", ModelRegistry.Layer.SHARED,
                rule(null, CapabilityResolver.Evidence.NONE, ""), CapabilityResolver.Reason.NONE, null, null).label(7));

        String speedNote = (String) field(ModelRegistry.class, "SPEED_PROMPT_NOTE");
        String[] notes = {"", speedNote, "OEM 2.1.9 hides this in this region"};
        for (Map.Entry<String, FeatureVariant> e : variants.entrySet()) {
            FeatureVariant v = e.getValue();
            for (int n = 0; n < notes.length; n++)
                for (int value : VALUES) {
                    String text = ModelRegistry.caution(decision(v, ModelRegistry.Layer.OEM_GROUP, CapabilityResolver.Evidence.OEM_MAPPED, notes[n]), value);
                    if (text != null) out.add("caution " + e.getKey() + " note" + n + " " + value + "\t" + text);
                }
            out.add("caution " + e.getKey() + " ut5 0\t" + ModelRegistry.caution(decision(v, ModelRegistry.Layer.UT5_MAX, CapabilityResolver.Evidence.OEM_MAPPED, ""), 0));
            out.add("caution " + e.getKey() + " lab 0\t" + ModelRegistry.caution(decision(v, ModelRegistry.Layer.OEM_GROUP, CapabilityResolver.Evidence.VOLT_LAB, ""), 0));
        }
        out.add("caution null 0\t" + ModelRegistry.caution(null, 0));
        TreeSet<String> keys = new TreeSet<>();
        for (ScooterCommands.Feature f : features) keys.add(f.key);
        keys.add("clock_sync");
        keys.add("");
        for (String key : keys)
            for (int value : VALUES) {
                // Registry 19: the caution depends on the scooter's region; a US serial keeps the base listing's lines.
                out.add("experimental " + key + " " + value + "\t" + ModelRegistry.experimentalCaution(key, value, serial("Q2443ZZ9US7777777")));
                String outside = ModelRegistry.experimentalCaution(key, value, serial("Q2443ZZ9ES7777777"));
                if (!java.util.Objects.equals(outside, ModelRegistry.experimentalCaution(key, value, serial("Q2443ZZ9US7777777"))))
                    out.add("experimental outside region " + key + " " + value + "\t" + outside);
            }

        for (Map.Entry<String, DeviceDescriptor> e : descriptors().entrySet())
            out.add("modelLine " + e.getKey() + "\t" + ModelRegistry.modelLine(e.getValue()));

        for (NaveeModels.Series s : NaveeModels.SERIES) {
            out.add("series " + s.name + " about\t" + s.about);
            out.add("series " + s.name + " models\t" + String.join("|", s.models));
        }
        for (String q : new String[]{"", "high", "PERFORMANCE", "commut", "Commuting", "ut", "UT series", "xt series", "kids",
                "zzz", "off-road", "offroad", "suv", "SUV-style", "budget", "entry", "premium", "lightweight", "suspension",
                "all terrain", "g", "n65i", "easyride"})
            out.add("search " + q + "\t" + String.join("|", NaveeModels.search(q)));

        for (DriveModeSurvey.Label label : DriveModeSurvey.Label.values()) out.add("drive mode " + label + "\t" + label.text);
        for (int[] codes : new int[][]{{}, {2}, {3, 2}, {2, 3, 11}, {11, 2, 3, 7}}) {
            for (int unsureAt = -1; unsureAt < codes.length; unsureAt++) {
                DriveModeSurvey s = new DriveModeSurvey();
                DriveModeSurvey.Label[] order = {DriveModeSurvey.Label.ECO, DriveModeSurvey.Label.SPORT, DriveModeSurvey.Label.WALK, DriveModeSurvey.Label.DRIVE};
                for (int i = 0; i < codes.length; i++) {
                    s.observe(codes[i]);
                    s.name(i == unsureAt ? DriveModeSurvey.Label.UNSURE : order[i]);
                }
                out.add("drive summary " + Arrays.toString(codes) + " unsure " + unsureAt + "\t" + s.summary());
            }
        }

        refusals(out, features);
        return out;
    }

    private static void refusals(List<String> out, List<ScooterCommands.Feature> features) {
        refusal(out, "find unknown", () -> ScooterCommands.find("nope"));
        for (ScooterCommands.Feature f : features)
            for (int v : values(f.optionValues, f.min, f.max)) {
                String key = f.key;
                refusal(out, "set " + key + " " + v, () -> ScooterCommands.set(key, v));
            }
        refusal(out, "set range_algorithm no status", () -> ScooterCommands.set("range_algorithm", 0, null));
        refusal(out, "setParams null key", () -> ScooterCommands.setParams(null, 1));
        refusal(out, "setParams null values", () -> ScooterCommands.setParams("lock", (int[]) null));
        refusal(out, "setParams voice", () -> ScooterCommands.setParams("voice", 1, 2));
        refusal(out, "setParams two", () -> ScooterCommands.setParams("lock", 1, 2));
        for (boolean owner : new boolean[]{false, true}) {
            refusal(out, "readPin " + owner, () -> ScooterCommands.readPin(owner));
            for (String digits : new String[]{null, "", "123", "1234", "12a4", "12345", "٠١٢٣"})
                refusal(out, "setPin " + digits + " " + owner, () -> ScooterCommands.setPin(digits, owner));
            for (boolean enabled : new boolean[]{false, true})
                for (boolean known : new boolean[]{false, true})
                    refusal(out, "enablePin " + enabled + " " + owner + " " + known, () -> ScooterCommands.enablePin(enabled, owner, known));
            for (boolean enabled : new boolean[]{false, true})
                for (boolean bonded : new boolean[]{false, true})
                    refusal(out, "setProximity " + enabled + " " + owner + " " + bonded, () -> ScooterCommands.setProximity(enabled, owner, bonded, null));
            refusal(out, "parsePin null " + owner, () -> ScooterCommands.parsePin(null, owner));
            refusal(out, "pinReadbackMatches null " + owner, () -> ScooterCommands.pinReadbackMatches(null, true, null, owner));
        }
        for (Map.Entry<String, FeatureVariant> e : variantsQuietly().entrySet()) {
            FeatureVariant variant = e.getValue();
            for (int v : new int[]{-1, 0, 1, 7, 33, 101})
                refusal(out, "encode " + e.getKey() + " " + v, () -> variant.encode(v));
        }
    }

    private interface Call { Object run(); }

    /** The result, or what it throws (a clock_sync decision has no catalogue feature for oemLabel). */
    private static String quietly(Call call) {
        try {
            return String.valueOf(call.run());
        } catch (RuntimeException e) {
            return "throws " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void refusal(List<String> out, String what, Call call) {
        try {
            call.run();
        } catch (IllegalArgumentException refused) {
            out.add("refusal " + what + "\t" + refused.getMessage());
        } catch (RuntimeException other) {
            out.add("refusal " + what + " other\t" + other.getMessage());
        }
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

    private static ModelRegistry.Rule rule(FeatureVariant v, CapabilityResolver.Evidence evidence, String note) {
        return new ModelRegistry.Rule(v == null ? CapabilityResolver.Support.UNKNOWN_MAPPING : CapabilityResolver.Support.KNOWN_ENCODING,
                CapabilityResolver.Reason.NONE, evidence, note, EnumSet.noneOf(CapabilityResolver.Precondition.class), v);
    }

    private static CapabilityResolver.Decision decision(FeatureVariant v, ModelRegistry.Layer layer, CapabilityResolver.Evidence evidence, String note) {
        return new CapabilityResolver.Decision(v.feature, layer, rule(v, evidence, note), CapabilityResolver.Reason.NONE, null, v.values);
    }

    private static Map<String, FeatureVariant> variantsQuietly() {
        try { return variants(); } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Every FeatureVariant ModelRegistry holds, by field name (maps by field name and key), plus each catalogue feature's ut5 copy. */
    static Map<String, FeatureVariant> variants() throws Exception {
        Map<String, FeatureVariant> out = new LinkedHashMap<>();
        List<Class<?>> classes = new ArrayList<>();
        classes.add(ModelRegistry.class);
        classes.addAll(Arrays.asList(ModelRegistry.class.getDeclaredClasses()));
        for (Class<?> c : classes)
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value = f.get(null);
                String name = c.getSimpleName() + "." + f.getName();
                if (value instanceof FeatureVariant) out.put(name, (FeatureVariant) value);
                else if (value instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet())
                    if (e.getValue() instanceof FeatureVariant) out.put(name + "[" + e.getKey() + "]", (FeatureVariant) e.getValue());
            }
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) out.put("ut5(" + f.key + ")", FeatureVariant.ut5(f));
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) out.put("ut5(" + f.key + ")", FeatureVariant.ut5(f));
        return out;
    }

    private static Object field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static DeviceDescriptor advertised(String pid) {
        return DeviceDescriptor.of("", pid, 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
    }

    private static DeviceDescriptor serial(String serial) {
        return DeviceDescriptor.of(serial, "", 0, null, false, DeviceDescriptor.Transport.STANDARD, 0);
    }

    private static DeviceDescriptor both(String serial, String pid) {
        return DeviceDescriptor.of(serial, pid, 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
    }

    static Map<String, DeviceDescriptor> descriptors() {
        Map<String, DeviceDescriptor> out = new LinkedHashMap<>();
        out.put("null", null);
        out.put("empty", serial(""));
        out.put("ut5 serial", serial("Q2538ZZ9US7777777"));
        out.put("ut5 advertised", advertised("25381"));
        out.put("serial only 2443", serial("Q2443ZZ9US7777777"));
        out.put("advertised 24431", advertised("24431"));
        out.put("both 24431", both("Q2443ZZ9US7777777", "24431"));
        out.put("conflict", both("Q2443ZZ9US7777777", "25851"));
        out.put("ut5 conflict", both("Q2538ZZ9US7777777", "24431"));
        for (String pid : new String[]{"22131", "23452", "25462", "24182", "25852", "24162", "99999"}) out.put("known " + pid, advertised(pid));
        out.put("owner model", advertised("24431").withOwnerModel("GT3 Pro"));
        out.put("simulated ut5", DeviceDescriptor.simulated("25381", 0));
        out.put("simulated other", DeviceDescriptor.simulated("24431", 0));
        out.put("simulated none", DeviceDescriptor.simulated("", 0));
        out.put("exo", DeviceDescriptor.of("", "24431", 1, null, false, DeviceDescriptor.Transport.EXO, 0));
        return out;
    }

    static List<String> expected() throws IOException {
        return Files.readAllLines(Paths.get(LISTING), StandardCharsets.UTF_8);
    }

    static Path listingPath() { return Paths.get(LISTING); }
}
