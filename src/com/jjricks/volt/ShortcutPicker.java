package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The shortcut picker's sections (Home › Quick access and Setting tiles › Choose shortcut). Pure, so the JVM suite
 * tests it (tests/ShortcutPickerTest.java). Every catalog entry sits in exactly one section; a section's entries keep
 * the catalog's order, and the sections keep ORDER. The catalog's groups decide the sections, except that Lights &
 * sound gives its line-light and star-light controls (four each) sections of their own, and every shortcut the scooter
 * can't use goes last, in a closed section per reason, named by the resolver's own words for it ("Not on this model"
 * only where the model really lacks it, "Not mapped for this model" where Volt only lacks a mapping). Nothing here
 * changes which shortcuts exist or their ids.
 */
public final class ShortcutPicker {
    private ShortcutPicker() {}

    /**
     * Section ids: data, never shown (a section's shown name comes from its entries, see DashboardScreen). UNAVAILABLE
     * is the section of what the model lacks (CapabilityResolver.Reason.MODEL_EXCLUDED); every other reason a
     * shortcut can't be used gets "unavailable:" plus the reason's name in lower case (unavailableSection).
     */
    static final String RIDE = "ride", BATTERY = "battery", LIGHTS = "lights", LINE_LIGHTS = "line_lights",
            STAR_LIGHTS = "star_lights", SECURITY = "security", PREFERENCES = "preferences", PAGES = "pages",
            MENUS = "menus", EXPERIMENTAL = "experimental", UNAVAILABLE = "unavailable";
    /**
     * Controls first (what a Setting tile holds), then pages and menus, then the Lab, then what this scooter can't use
     * (UNAVAILABLE first, then the other reasons in CapabilityResolver.Reason's order).
     */
    static final List<String> ORDER = Collections.unmodifiableList(java.util.Arrays.asList(
            RIDE, BATTERY, LIGHTS, LINE_LIGHTS, STAR_LIGHTS, SECURITY, PREFERENCES, PAGES, MENUS, EXPERIMENTAL, UNAVAILABLE));

    /** The last section for a reason a shortcut can't be used (see UNAVAILABLE). */
    public static String unavailableSection(CapabilityResolver.Reason reason) {
        CapabilityResolver.Reason shown = canonical(reason);
        return shown == CapabilityResolver.Reason.MODEL_EXCLUDED ? UNAVAILABLE
                : UNAVAILABLE + ":" + shown.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether a section holds shortcuts the scooter can't use. */
    public static boolean unavailableSection(String sectionId) {
        return sectionId != null && (sectionId.equals(UNAVAILABLE) || sectionId.startsWith(UNAVAILABLE + ":"));
    }

    /** The reason an unavailable section stands for (its name is CapabilityResolver.message of it), or null. */
    public static CapabilityResolver.Reason reasonOf(String sectionId) {
        if (!unavailableSection(sectionId)) return null;
        if (sectionId.equals(UNAVAILABLE)) return CapabilityResolver.Reason.MODEL_EXCLUDED;
        try {
            return CapabilityResolver.Reason.valueOf(sectionId.substring(UNAVAILABLE.length() + 1).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * One reason per message, so two sections never share a name (CapabilityResolver.message words FULL_PID_NEEDED as
     * MODEL_NOT_IDENTIFIED, TRANSPORT_UNSUPPORTED as PRODUCT_UNSUPPORTED). No reason at all means Volt has no mapping:
     * never a claim about the scooter.
     */
    public static CapabilityResolver.Reason canonical(CapabilityResolver.Reason reason) {
        if (reason == null || reason == CapabilityResolver.Reason.NONE) return CapabilityResolver.Reason.MODEL_NOT_MAPPED;
        if (reason == CapabilityResolver.Reason.FULL_PID_NEEDED) return CapabilityResolver.Reason.MODEL_NOT_IDENTIFIED;
        if (reason == CapabilityResolver.Reason.TRANSPORT_UNSUPPORTED) return CapabilityResolver.Reason.PRODUCT_UNSUPPORTED;
        return reason;
    }

    /** UNAVAILABLE first, then the other reasons in their enum order. */
    private static int rank(String sectionId) {
        CapabilityResolver.Reason reason = reasonOf(sectionId);
        return reason == null || reason == CapabilityResolver.Reason.MODEL_EXCLUDED ? -1 : reason.ordinal();
    }

    /** One section and its entries, in the catalog's order. */
    static final class Section {
        final String id;
        final List<QuickAccessActions.Entry> entries;
        Section(String id, List<QuickAccessActions.Entry> entries) {
            this.id = id; this.entries = Collections.unmodifiableList(entries);
        }
        boolean holds(String entryId) {
            for (QuickAccessActions.Entry entry : entries) if (entry.id.equals(entryId)) return true;
            return false;
        }
    }

    /** The section an entry belongs to while the scooter offers it (its catalog group, with lights split up). */
    static String groupSection(QuickAccessActions.Entry entry) {
        ScooterCommands.Feature feature = entry.feature();
        if (feature != null) {
            if (feature.experimental) return EXPERIMENTAL;
            if (feature.key.startsWith("line_")) return LINE_LIGHTS;
            if (feature.key.startsWith("star_")) return STAR_LIGHTS;
            switch (entry.group) { // the feature's English group id (QuickAccessActions copies it)
                case ScooterCommands.GROUP_RIDE: return RIDE;
                case ScooterCommands.GROUP_BATTERY: return BATTERY;
                case ScooterCommands.GROUP_LIGHTS: return LIGHTS;
                case ScooterCommands.GROUP_SECURITY: return SECURITY;
                case ScooterCommands.GROUP_PREFERENCES: return PREFERENCES;
                default: return MENUS;
            }
        }
        // Volt's own battery shortcuts (the charge schedule's, charge alerts) carry the Battery group.
        if (ScooterCommands.GROUP_BATTERY.equals(entry.group)) return BATTERY;
        // Clean Home's own actions (HomeQuickActions; Ride and Record never in the catalog): riding mode and Record with the
        // ride's controls, charge alerts (Clean's and the catalog's, one id) with the battery's.
        if (QuickAccessActions.CHARGE_ALERTS_ID.equals(entry.id)) return BATTERY;
        if (HomeQuickActions.RIDING.equals(entry.id) || HomeQuickActions.RECORD.equals(entry.id)) return RIDE;
        if (entry.id.equals("stats") || entry.id.equals("rides")) return PAGES;
        return MENUS;
    }

    /**
     * The non-empty sections, in ORDER. {@code missing} gives why the scooter can't use an entry (null when it can);
     * those entries go in the last sections, one per reason (unavailableSection).
     */
    static List<Section> sections(Collection<QuickAccessActions.Entry> catalog,
            Function<QuickAccessActions.Entry, CapabilityResolver.Reason> missing) {
        Map<String, List<QuickAccessActions.Entry>> grouped = new LinkedHashMap<>();
        for (String id : ORDER) if (!id.equals(UNAVAILABLE)) grouped.put(id, new ArrayList<>());
        TreeMap<Integer, String> lastIds = new TreeMap<>();
        Map<String, List<QuickAccessActions.Entry>> last = new LinkedHashMap<>();
        for (QuickAccessActions.Entry entry : catalog) {
            CapabilityResolver.Reason reason = missing == null ? null : missing.apply(entry);
            if (reason == null) { grouped.get(groupSection(entry)).add(entry); continue; }
            String id = unavailableSection(reason);
            lastIds.put(rank(id), id);
            last.computeIfAbsent(id, key -> new ArrayList<>()).add(entry);
        }
        for (String id : lastIds.values()) grouped.put(id, last.get(id));
        List<Section> result = new ArrayList<>();
        for (Map.Entry<String, List<QuickAccessActions.Entry>> group : grouped.entrySet())
            if (!group.getValue().isEmpty()) result.add(new Section(group.getKey(), group.getValue()));
        return Collections.unmodifiableList(result);
    }

    /** The section holding this entry id, or null. */
    static Section sectionOf(List<Section> sections, String entryId) {
        if (entryId == null) return null;
        for (Section section : sections) if (section.holds(entryId)) return section;
        return null;
    }

    /**
     * The section that starts open: the one holding the shortcut being replaced; when adding one (no current choice),
     * the first section with an entry that can still be added (offered and not already chosen), else the first section.
     */
    static String firstOpen(List<Section> sections, String current, Collection<String> chosen) {
        Section holding = sectionOf(sections, current);
        if (holding != null) return holding.id;
        for (Section section : sections) {
            if (unavailableSection(section.id)) continue;
            for (QuickAccessActions.Entry entry : section.entries)
                if (chosen == null || !chosen.contains(entry.id)) return section.id;
        }
        return sections.isEmpty() ? null : sections.get(0).id;
    }

    /** A search's matches across every section, in the sections' order: one flat list, never a header. */
    static List<QuickAccessActions.Entry> matches(List<Section> sections, Predicate<QuickAccessActions.Entry> matcher) {
        List<QuickAccessActions.Entry> result = new ArrayList<>();
        for (Section section : sections)
            for (QuickAccessActions.Entry entry : section.entries) if (matcher.test(entry)) result.add(entry);
        return result;
    }

    /** What the picker needs to know about the scooter (the same facts as Home's tiles). */
    interface Scooter {
        /** Connected, or simulating: Volt judges controls by this scooter's model. */
        boolean connected();
        CapabilityResolver.Decision decision(String featureKey);
        boolean controlOffered(String featureKey);
        /** Why the selected scooter has no charge alerts (ScooterService.chargeAlertsMissing), or null when it has them. */
        CapabilityResolver.Reason chargeAlertsMissing();
        /**
         * Why the selected scooter can't schedule charging (ScooterService.chargeScheduleMissing: the Battery page's
         * Charge done by rule, timed_charging and charge_start both offered), or null when it can.
         */
        CapabilityResolver.Reason chargeScheduleMissing();
    }

    /**
     * Why the scooter can't use an entry, or null when it can (or nothing says otherwise). With no scooter connected,
     * every control stays in its own section, as the flat list showed them before. Volt's own Battery shortcuts follow
     * the selected scooter's model whether it is connected or not (its saved model offline): Charge alerts only where
     * the model has no built-in charge limit, Charge done by and its time only where it can schedule charging. The
     * Line lights and Star lights menus follow their controls: a model that can use none of them can't use the menu.
     * The other pages and menus open on every scooter (each of their rows says what it can't do there).
     */
    static CapabilityResolver.Reason missing(QuickAccessActions.Entry entry, Scooter scooter) {
        if (QuickAccessActions.CHARGE_ALERTS_ID.equals(entry.id))
            return scooter == null ? CapabilityResolver.Reason.NOT_CONNECTED : scooter.chargeAlertsMissing();
        if (SCHEDULE_IDS.contains(entry.id)) return scooter == null ? null : scooter.chargeScheduleMissing();
        if (scooter == null || !scooter.connected()) return null;
        String lights = LIGHT_MENUS.get(entry.id);
        if (lights != null) {
            CapabilityResolver.Reason first = null;
            for (ScooterCommands.Feature feature : ScooterCommands.catalog()) {
                if (!feature.key.startsWith(lights)) continue;
                CapabilityResolver.Decision decision = scooter.decision(feature.key);
                if (!unmapped(feature.key, decision, scooter.controlOffered(feature.key))) return null;
                if (first == null) first = canonical(decision.reason);
            }
            return first;
        }
        ScooterCommands.Feature feature = entry.feature();
        if (feature == null) return null;
        CapabilityResolver.Decision decision = scooter.decision(feature.key);
        return unmapped(feature.key, decision, scooter.controlOffered(feature.key)) ? canonical(decision.reason) : null;
    }

    /** Whether the scooter can't use an entry (missing gives why). */
    static boolean unavailable(QuickAccessActions.Entry entry, Scooter scooter) { return missing(entry, scooter) != null; }

    /** The charge schedule's own shortcuts (Charge done by, Charge done by time). */
    public static final List<String> SCHEDULE_IDS = Collections.unmodifiableList(java.util.Arrays.asList("charge_done_by", "charge_done_time"));
    /** The light menus that list only one light's controls, and those controls' key prefix (MainActivity.showQuickSettings). */
    private static final Map<String, String> LIGHT_MENUS = new LinkedHashMap<>();
    static { LIGHT_MENUS.put("section:Line lights", "line_"); LIGHT_MENUS.put("section:Star lights", "star_"); }

    /**
     * The Battery page's Charge done by rule (MainActivity.chargeDoneOffered, HomeQuickDefaults) for a model:
     * timed_charging and charge_start both offered (BleController.controlOffered's rule). Why not, or null. A profile
     * with no identity evidence yet shows them, as the Battery page does.
     */
    static CapabilityResolver.Reason scheduleMissing(DeviceDescriptor descriptor) {
        if (descriptor == null) return null;
        for (String key : new String[]{"timed_charging", "charge_start"}) {
            CapabilityResolver.Decision decision = CapabilityResolver.decide(key, descriptor, StatusFields.NONE, null);
            boolean offered = decision.support == CapabilityResolver.Support.KNOWN_ENCODING
                    || !descriptor.simulated && descriptor.pidSource() == DeviceDescriptor.PidSource.NONE && !ScooterCommands.oemOnly(key);
            if (!offered) return canonical(decision.reason);
        }
        return null;
    }

    /**
     * Home's rule for a control this model doesn't map (QuickAccessGrid's muted tile): no known encoding and not
     * offered. Range source is Volt's own choice and is always offered.
     */
    static boolean unmapped(String featureKey, CapabilityResolver.Decision decision, boolean controlOffered) {
        return decision != null && !"range_algorithm".equals(featureKey)
                && decision.support != CapabilityResolver.Support.KNOWN_ENCODING && !controlOffered;
    }
}
