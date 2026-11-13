package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The shortcut picker's sections (Home › Quick access and Setting tiles › Choose shortcut). Pure, so the JVM suite
 * tests it (tests/ShortcutPickerTest.java). Every catalog entry sits in exactly one section; a section's entries keep
 * the catalog's order, and the sections keep ORDER. The catalog's groups decide the sections, except that Lights &
 * sound gives its line-light and star-light controls (four each) sections of their own, and every control the
 * connected scooter doesn't offer goes in UNAVAILABLE, last. Nothing here changes which shortcuts exist or their ids.
 */
final class ShortcutPicker {
    private ShortcutPicker() {}

    /** Section ids: data, never shown (a section's shown name comes from its entries, see DashboardScreen). */
    static final String RIDE = "ride", BATTERY = "battery", LIGHTS = "lights", LINE_LIGHTS = "line_lights",
            STAR_LIGHTS = "star_lights", SECURITY = "security", PREFERENCES = "preferences", PAGES = "pages",
            MENUS = "menus", EXPERIMENTAL = "experimental", UNAVAILABLE = "unavailable";
    /** Controls first (what a Setting tile holds), then pages and menus, then the Lab, then what this scooter lacks. */
    static final List<String> ORDER = Collections.unmodifiableList(java.util.Arrays.asList(
            RIDE, BATTERY, LIGHTS, LINE_LIGHTS, STAR_LIGHTS, SECURITY, PREFERENCES, PAGES, MENUS, EXPERIMENTAL, UNAVAILABLE));

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
        if (entry.id.equals("stats") || entry.id.equals("rides")) return PAGES;
        return MENUS;
    }

    /** The non-empty sections, in ORDER. {@code unavailable} marks the entries the connected scooter doesn't offer. */
    static List<Section> sections(Collection<QuickAccessActions.Entry> catalog, Predicate<QuickAccessActions.Entry> unavailable) {
        Map<String, List<QuickAccessActions.Entry>> grouped = new LinkedHashMap<>();
        for (String id : ORDER) grouped.put(id, new ArrayList<>());
        for (QuickAccessActions.Entry entry : catalog)
            grouped.get(unavailable != null && unavailable.test(entry) ? UNAVAILABLE : groupSection(entry)).add(entry);
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
            if (section.id.equals(UNAVAILABLE)) continue;
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
        /** ScooterService.chargeAlertsOffered: the selected scooter is a model without a built-in charge limit. */
        boolean chargeAlertsOffered();
    }

    /**
     * An entry the scooter doesn't offer. With no scooter connected, every control stays in its own section, as the
     * flat list showed them before. Charge alerts follow the selected scooter's model whether it is connected or not
     * (ScooterService.chargeAlertsOffered, which uses the saved model offline), so they never look usable on a model
     * with a built-in charge limit. Pages, menus and the charge schedule's shortcuts are always offered.
     */
    static boolean unavailable(QuickAccessActions.Entry entry, Scooter scooter) {
        if (QuickAccessActions.CHARGE_ALERTS_ID.equals(entry.id)) return scooter == null || !scooter.chargeAlertsOffered();
        ScooterCommands.Feature feature = entry.feature();
        if (feature == null || scooter == null || !scooter.connected()) return false;
        return unmapped(feature.key, scooter.decision(feature.key), scooter.controlOffered(feature.key));
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
