package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Developer options › Simulated model: every model Volt knows by name (ModelRegistry's own list), in the order a
 * developer looks for them, and one entry per region where the registry's rules for that model differ by region.
 * Pure: nothing here is stored, and every rule comes from the registry, never a hand list.
 */
public final class SimulatorModels {
    private SimulatorModels() {}

    /** One picker entry: the advertised Model ID ("" is the UT5 MAX simulator), its serial region (or null) and its name. */
    public static final class Entry {
        public final String pid, region, name;
        Entry(String pid, String region, String name) { this.pid = pid; this.region = region; this.name = name; }
        /** The picker line: the model's name, with the region when the model has more than one. */
        public String label() { return region == null ? name : name + " (" + region + ")"; }
        public DeviceDescriptor descriptor() { return DeviceDescriptor.simulated(pid, 0, region); }
        @Override public String toString() { return label(); }
    }

    /** The synthetic IDs kept from the first simulator: a mapped model Volt can't name, and an unknown one. */
    static final String MAPPED = "24431", UNKNOWN = "39991";
    /** The region a model's entries are named after, most familiar first; then the registry's own area order. */
    private static final String[] NAMING_ORDER = {"US", "EU", "UK", "DE", "FR", "IT", "ES", "NL", "CA", "AU", "JP", "CN"};

    private static List<Entry> entries;

    /** UT5 MAX, then the named models by name (each region group in turn), then the mapped and unknown models. */
    public static synchronized List<Entry> entries() {
        if (entries != null) return entries;
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("", null, "UT5 MAX"));
        List<Map.Entry<String, String[]>> known = new ArrayList<>(ModelRegistry.knownModels().entrySet());
        Collections.sort(known, (a, b) -> a.getValue()[0].compareToIgnoreCase(b.getValue()[0]));
        for (Map.Entry<String, String[]> model : known) {
            List<String> regions = regionGroups(model.getKey());
            for (String region : regions)
                out.add(new Entry(model.getKey(), regions.size() == 1 ? null : region, model.getValue()[0]));
        }
        out.add(new Entry(MAPPED, null, "Mapped model (synthetic 24431)"));
        out.add(new Entry(UNKNOWN, null, "Unknown model (synthetic 39991)"));
        entries = Collections.unmodifiableList(out);
        return entries;
    }

    /** The entry for a simulated model and region: an exact match, else the model's first entry, else the UT5 MAX. */
    public static Entry find(String pid, String region) {
        String p = pid == null ? "" : pid;
        Entry first = null;
        for (Entry e : entries()) {
            if (!e.pid.equals(p)) continue;
            if (first == null) first = e;
            if (e.region == null ? region == null : e.region.equals(region)) return e;
        }
        if (first != null) {
            // A region the picker doesn't name: the entry whose rules match it.
            String signature = signature(DeviceDescriptor.simulated(p, 0, region));
            for (Entry e : entries()) if (e.pid.equals(p) && signature(e.descriptor()).equals(signature)) return e;
            return first;
        }
        return entries().get(0);
    }

    /**
     * Advertised Model IDs that answer the current sign state (0x79) with 254 and report an unsigned magnitude:
     * the GT3 Max, from its owner's capture (PowerSign). A reading fact, not a control.
     */
    static final java.util.Set<String> UNSIGNED_CURRENT = Collections.singleton("24182");

    /**
     * What the simulated readings need to know about a model, all from the registry's own rules:
     * {native charge limit (the charge_limit control is offered, so charge alerts aren't: ChargeAlertPolicy.offered),
     * signed current, stock and high-speed chargers told apart (the charge reference product is the UT5 MAX's)}.
     */
    public static boolean[] facts(DeviceDescriptor d) {
        return new boolean[]{!ChargeAlertPolicy.offered(d), d == null || !UNSIGNED_CURRENT.contains(d.rulePid()),
                ModelRegistry.chargeReferenceProduct(d) == 2538};
    }

    /** The row value: the picker line, with the first simulator's short words for the two synthetic models. */
    public static String shortLabel(Entry e) {
        return MAPPED.equals(e.pid) ? "Mapped" : UNKNOWN.equals(e.pid) ? "Unknown" : e.label();
    }

    /**
     * One region per group of serial regions whose rules agree on every control (support, reason, variant and values,
     * offered and Experimental): the region the group is named after. One group gives one entry with no region.
     */
    static List<String> regionGroups(String pid) {
        LinkedHashMap<String, String> groups = new LinkedHashMap<>();
        for (String area : areas()) {
            String signature = signature(DeviceDescriptor.simulated(pid, 0, area));
            if (!groups.containsKey(signature)) groups.put(signature, area);
        }
        return new ArrayList<>(groups.values());
    }
    private static List<String> areas() {
        List<String> out = new ArrayList<>(Arrays.asList(NAMING_ORDER));
        for (String area : DeviceDescriptor.OEM_AREAS) if (!out.contains(area)) out.add(area);
        return out;
    }

    /** Everything a region can change for a model: each control's offered and Experimental verdicts. */
    static String signature(DeviceDescriptor d) {
        StringBuilder out = new StringBuilder();
        List<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        features.addAll(ScooterCommands.oemOnly());
        for (ScooterCommands.Feature f : features) {
            out.append(f.key).append('=');
            verdict(out, CapabilityResolver.decide(f.key, d, StatusFields.NONE, null));
            out.append('/');
            verdict(out, CapabilityResolver.decideExperimental(f.key, d, StatusFields.NONE, null));
            out.append(';');
        }
        return out.toString();
    }
    private static void verdict(StringBuilder out, CapabilityResolver.Decision decision) {
        // The OEM note is provenance (reports only; no screen shows it), so it doesn't make a region its own entry.
        out.append(decision.support).append(',').append(decision.reason);
        FeatureVariant v = decision.variant;
        if (v != null) out.append(',').append(v.id).append(Arrays.toString(v.values)).append(v.min).append('-').append(v.max)
                .append(':').append(v.step);
    }
}
