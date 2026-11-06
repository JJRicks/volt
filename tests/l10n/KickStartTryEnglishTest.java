package com.jjricks.volt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The English of registry version 19's kick-start tries (KickStartTry), pinned line for line in
 * kickstart-try-english.tsv. It lists the same things the B4 controls characterization
 * (tests/l10n/characterization/ControlsEnglishTest) lists for every variant ModelRegistry holds: the id, the label
 * over the domain and around it, an OEM-group Decision's label (where it differs) and oemLabel (where it is not
 * null), ModelRegistry.caution with each kind of OEM note and on the UT5 MAX and Lab layers, and each encode
 * refusal. The listing is the same with the phone set to en-US, de-DE, tr-TR and ar-EG.
 *
 * Run with "--write" from the repository root to print the listing.
 */
public final class KickStartTryEnglishTest {
    static final String LISTING = "tests/l10n/kickstart-try-english.tsv";
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
        System.out.println("KickStartTryEnglishTest: " + first.size() + " lines of kick-start try English pinned, in 4 phone locales");
    }

    static Map<String, FeatureVariant> variants() {
        Map<String, FeatureVariant> out = new LinkedHashMap<>();
        out.put("KickStartTry.OEM", KickStartTry.OEM);
        out.put("KickStartTry.UT5", KickStartTry.UT5);
        return out;
    }

    static List<String> listing() throws Exception {
        List<String> out = new ArrayList<>();
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
        java.lang.reflect.Field note = ModelRegistry.class.getDeclaredField("SPEED_PROMPT_NOTE");
        note.setAccessible(true);
        String[] notes = {"", (String) note.get(null), "OEM 2.1.9 hides this in this region"};
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
        for (Map.Entry<String, FeatureVariant> e : variants.entrySet()) {
            FeatureVariant variant = e.getValue();
            for (int v : new int[]{-1, 0, 1, 7, 33, 101}) {
                try {
                    variant.encode(v);
                } catch (IllegalArgumentException refused) {
                    out.add("refusal encode " + e.getKey() + " " + v + "\t" + refused.getMessage());
                } catch (RuntimeException other) {
                    out.add("refusal encode " + e.getKey() + " " + v + " other\t" + other.getMessage());
                }
            }
        }
        return out;
    }

    private interface Call { Object run(); }

    private static String quietly(Call call) {
        try {
            return String.valueOf(call.run());
        } catch (RuntimeException e) {
            return "throws " + e.getClass().getSimpleName() + ": " + e.getMessage();
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

    private static CapabilityResolver.Decision decision(FeatureVariant v, ModelRegistry.Layer layer, CapabilityResolver.Evidence evidence, String note) {
        ModelRegistry.Rule rule = new ModelRegistry.Rule(CapabilityResolver.Support.KNOWN_ENCODING, CapabilityResolver.Reason.NONE,
                evidence, note, EnumSet.noneOf(CapabilityResolver.Precondition.class), v);
        return new CapabilityResolver.Decision(v.feature, layer, rule, CapabilityResolver.Reason.NONE, null, v.values);
    }
}
