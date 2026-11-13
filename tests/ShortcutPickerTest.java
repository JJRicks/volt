package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** The shortcut picker's sections, first open section, search and availability, and the Charge alerts shortcut's value. */
public final class ShortcutPickerTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    /** Every shortcut id before the picker's sections (main at e2bdb2cd): none may change or disappear. */
    private static final List<String> PINNED = Arrays.asList(
            "lights", "controls", "stats", "rides", "section:Ride", "section:Battery", "section:Line lights",
            "section:Star lights", "section:Dashboard & sound", "section:Security", "section:Profile", "more:App",
            "more:Scooters", "more:Lab", "more:Data", "more:About", "charge_done_by", "charge_done_time", "feature:lock",
            "feature:cruise_control", "feature:energy_recovery", "feature:acceleration", "feature:headlight",
            "feature:auto_sensor", "feature:traction_control", "feature:turn_sound", "feature:display_units",
            "feature:range_algorithm", "feature:start_speed", "feature:speed_limit", "feature:maximum_speed",
            "feature:dual_drive", "feature:drive_speed_limit", "feature:charge_limit", "feature:timed_charging",
            "feature:charge_start", "feature:lock_expiry", "feature:low_power", "feature:long_range_mode",
            "feature:hill_start_assist", "feature:parking_assist", "feature:hill_descent_control",
            "feature:overspeed_control", "feature:prompt_tone", "feature:line_light", "feature:line_pattern",
            "feature:line_color", "feature:line_boost_color", "feature:star_light", "feature:star_pattern",
            "feature:star_color", "feature:star_boost_color", "feature:proximity_key", "feature:pin");

    /** A scooter of this model as BleController judges it (not simulating). */
    private static ShortcutPicker.Scooter scooter(DeviceDescriptor d, boolean connected) {
        return new ShortcutPicker.Scooter() {
            public boolean connected() { return connected; }
            public CapabilityResolver.Decision decision(String key) { return CapabilityResolver.decide(key, d, StatusFields.NONE, null); }
            public boolean controlOffered(String key) {
                return decision(key).support == CapabilityResolver.Support.KNOWN_ENCODING
                        || d.pidSource() == DeviceDescriptor.PidSource.NONE && !ScooterCommands.oemOnly(key);
            }
            public boolean chargeAlertsOffered() { return ChargeAlertPolicy.offered(d); }
        };
    }
    private static List<ShortcutPicker.Section> sections(ShortcutPicker.Scooter scooter) {
        return ShortcutPicker.sections(QuickAccessActions.catalog(), e -> ShortcutPicker.unavailable(e, scooter));
    }
    private static List<String> ids(List<QuickAccessActions.Entry> entries) {
        List<String> ids = new ArrayList<>(); for (QuickAccessActions.Entry e : entries) ids.add(e.id); return ids;
    }
    private static String section(List<ShortcutPicker.Section> sections, String id) {
        ShortcutPicker.Section s = ShortcutPicker.sectionOf(sections, id); return s == null ? null : s.id;
    }
    /** The picker's matcher with the language choice spelled out (English, as the JVM suite runs). */
    private static List<String> search(List<ShortcutPicker.Section> sections, String query) {
        String folded = SearchFold.fold(query, Locale.ROOT, true).trim();
        return ids(ShortcutPicker.matches(sections, e -> SearchFold.holds(e.label + " " + e.group, folded, Locale.ROOT, true)));
    }

    public static void main(String[] args) {
        // Nothing about which shortcuts exist or their ids changes; old saved lists still normalize to themselves.
        List<String> catalog = ids(QuickAccessActions.catalog());
        List<String> withoutAlerts = new ArrayList<>(catalog); withoutAlerts.remove(QuickAccessActions.CHARGE_ALERTS_ID);
        check(withoutAlerts.equals(PINNED), "the catalog keeps every id, in order: " + withoutAlerts);
        for (String id : PINNED) {
            check(QuickAccessActions.find(id) != null, "a saved id still resolves: " + id);
            check(QuickAccessActions.normalize(Arrays.asList(id, "lights"), 1, 4).get(0).equals(id), "a saved Setting tile keeps " + id);
        }
        check(QuickAccessActions.normalize(PINNED).size() == QuickAccessActions.MAX_ITEMS, "Quick access still holds at most eight");
        check(QuickAccessActions.normalize(PINNED, 1, 4).size() == 4, "Setting tiles (DashboardConfig: 1 to 4) still hold at most four");
        check(!QuickAccessActions.CHARGE_ALERTS_ID.equals("") && !PINNED.contains(QuickAccessActions.CHARGE_ALERTS_ID), "Charge alerts' id is new");

        // Sections: every entry in exactly one, sections in ORDER, entries in catalog order, none empty.
        List<ShortcutPicker.Section> all = ShortcutPicker.sections(QuickAccessActions.catalog(), null);
        HashSet<String> seen = new HashSet<>();
        int last = -1;
        for (ShortcutPicker.Section s : all) {
            check(!s.entries.isEmpty(), "no empty section: " + s.id);
            int at = ShortcutPicker.ORDER.indexOf(s.id);
            check(at > last, "sections keep their order: " + s.id); last = at;
            int previous = -1;
            for (QuickAccessActions.Entry e : s.entries) {
                check(seen.add(e.id), "one section per entry: " + e.id);
                check(catalog.indexOf(e.id) > previous, "catalog order inside " + s.id); previous = catalog.indexOf(e.id);
            }
        }
        check(seen.size() == catalog.size(), "every entry has a section");
        check(ShortcutPicker.sectionOf(all, ShortcutPicker.UNAVAILABLE) == null && section(all, "feature:charge_limit").equals(ShortcutPicker.BATTERY),
                "without an unavailable entry there is no last section");
        String[][] expected = {
                {"feature:cruise_control", "ride"}, {"feature:maximum_speed", "ride"}, {"feature:hill_descent_control", "ride"},
                {"charge_done_by", "battery"}, {"charge_done_time", "battery"}, {"feature:charge_limit", "battery"}, {"feature:low_power", "battery"},
                {"feature:headlight", "lights"}, {"feature:turn_sound", "lights"}, {"feature:line_light", "line_lights"},
                {"feature:line_boost_color", "line_lights"}, {"feature:star_pattern", "star_lights"}, {"feature:lock", "security"},
                {"feature:pin", "security"}, {"feature:display_units", "preferences"}, {"feature:range_algorithm", "preferences"},
                {"stats", "pages"}, {"rides", "pages"}, {"lights", "menus"}, {"section:Line lights", "menus"}, {"more:About", "menus"},
                {"feature:acceleration", "experimental"}, {"feature:prompt_tone", "experimental"}, {"feature:long_range_mode", "experimental"}};
        for (String[] pair : expected) check(pair[1].equals(section(all, pair[0])), pair[0] + " belongs in " + pair[1] + ", not " + section(all, pair[0]));
        int[] sizes = new int[ShortcutPicker.ORDER.size()];
        for (ShortcutPicker.Section s : all) sizes[ShortcutPicker.ORDER.indexOf(s.id)] = s.entries.size();
        check(sizes[0] == 8 && sizes[2] == 3 && sizes[3] == 4 && sizes[4] == 4 && sizes[5] == 4 && sizes[6] == 2 && sizes[7] == 2
                && sizes[8] == 14 && sizes[9] == 7, "section sizes: " + Arrays.toString(sizes));

        // The first open section: the current choice's; adding, the first with something still to add.
        check(ShortcutPicker.RIDE.equals(ShortcutPicker.firstOpen(all, null, Collections.<String>emptyList())), "adding opens Ride");
        check(ShortcutPicker.MENUS.equals(ShortcutPicker.firstOpen(all, "lights", Arrays.asList("lights", "stats"))), "replacing opens the current choice's section");
        check(ShortcutPicker.PAGES.equals(ShortcutPicker.firstOpen(all, "stats", null)), "replacing a page opens Pages");
        List<String> rideChosen = new ArrayList<>();
        for (ShortcutPicker.Section s : all) if (s.id.equals(ShortcutPicker.RIDE)) rideChosen.addAll(ids(s.entries));
        check(ShortcutPicker.BATTERY.equals(ShortcutPicker.firstOpen(all, null, rideChosen)), "adding skips a section that is all chosen");
        check(ShortcutPicker.RIDE.equals(ShortcutPicker.firstOpen(all, "gone:id", Collections.<String>emptyList())), "an unknown current id opens as adding does");
        check(ShortcutPicker.firstOpen(Collections.<ShortcutPicker.Section>emptyList(), null, null) == null, "no sections, nothing open");

        // Search: one flat list across the sections, in their order; nothing matches gives nothing (the picker's "no matching" line).
        check(search(all, "nothing_matches_this_fixture").isEmpty(), "an unmatched search is empty");
        check(search(all, "Cruise").equals(Collections.singletonList("feature:cruise_control")), "a name matches");
        List<String> battery = search(all, "battery");
        check(battery.contains("feature:charge_limit") && battery.contains("charge_done_by") && battery.contains("section:Battery"),
                "a group name matches its whole group and the menu named for it: " + battery);
        check(battery.indexOf("feature:charge_limit") < battery.indexOf("section:Battery"), "matches keep the sections' order");
        check(search(all, "").size() == catalog.size(), "an empty query matches everything (the picker shows sections instead)");

        // Availability by model. No scooter connected: every control stays in its own section, as the flat list did.
        DeviceDescriptor ut5 = DeviceDescriptor.of("X2538-SYNTHETIC", "", 0, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        DeviceDescriptor gt3 = DeviceDescriptor.of("Q2345ZZ9ES7777777", "24182", 1, null, false, DeviceDescriptor.Transport.STANDARD, 0);
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog())
            if (!e.id.equals(QuickAccessActions.CHARGE_ALERTS_ID)) {
                check(!ShortcutPicker.unavailable(e, scooter(gt3, false)), "disconnected, nothing is judged: " + e.id);
                check(!ShortcutPicker.unavailable(e, null), "no scooter at all, nothing is judged: " + e.id);
            }
        List<ShortcutPicker.Section> onUt5 = sections(scooter(ut5, true)), onGt3 = sections(scooter(gt3, true));
        check(ShortcutPicker.BATTERY.equals(section(onUt5, "feature:charge_limit")), "the UT5 MAX offers its charge limit");
        check(ShortcutPicker.UNAVAILABLE.equals(section(onGt3, "feature:charge_limit")), "the GT3 Max has no charge limit: last section");
        check(ShortcutPicker.UNAVAILABLE.equals(onGt3.get(onGt3.size() - 1).id), "the unavailable section is last");
        for (QuickAccessActions.Entry e : QuickAccessActions.catalog())
            if (e.feature() == null && !e.id.equals(QuickAccessActions.CHARGE_ALERTS_ID))
                check(!ShortcutPicker.UNAVAILABLE.equals(section(onGt3, e.id)), "pages, menus and the schedule's shortcuts are always offered: " + e.id);
        check(!ShortcutPicker.UNAVAILABLE.equals(section(onGt3, "feature:range_algorithm")), "range source is Volt's own and always offered");
        check(ShortcutPicker.UNAVAILABLE.equals(ShortcutPicker.firstOpen(onGt3, "feature:charge_limit", null)), "replacing an unavailable one opens the last section");
        check(!ShortcutPicker.UNAVAILABLE.equals(ShortcutPicker.firstOpen(onGt3, null, null)), "adding never opens the unavailable section first");
        int total = 0; for (ShortcutPicker.Section s : onGt3) total += s.entries.size();
        check(total == catalog.size(), "judging moves entries, never drops or repeats one");
        check(!ShortcutPicker.unmapped("range_algorithm", CapabilityResolver.decide("charge_limit", gt3, StatusFields.NONE, null), false)
                && !ShortcutPicker.unmapped("charge_limit", null, false), "the tiles' unmapped rule: range source and an unknown decision are never unmapped");

        // Charge alerts: only where the model has no built-in charge limit, whether connected or not.
        QuickAccessActions.Entry alerts = QuickAccessActions.find(QuickAccessActions.CHARGE_ALERTS_ID);
        if (alerts != null) {
            check(ShortcutPicker.BATTERY.equals(ShortcutPicker.groupSection(alerts)), "Charge alerts sit in Battery");
            check(!ShortcutPicker.unavailable(alerts, scooter(gt3, true)) && !ShortcutPicker.unavailable(alerts, scooter(gt3, false)),
                    "a model without a charge limit offers charge alerts, connected or not");
            check(ShortcutPicker.unavailable(alerts, scooter(ut5, true)) && ShortcutPicker.unavailable(alerts, scooter(ut5, false))
                    && ShortcutPicker.unavailable(alerts, null), "the UT5 MAX (built-in limit) and no scooter: never offered");
            check(ShortcutPicker.UNAVAILABLE.equals(section(onUt5, alerts.id)) && ShortcutPicker.BATTERY.equals(section(onGt3, alerts.id)),
                    "the UT5 MAX's picker puts Charge alerts last; the GT3 Max's in Battery");
            check(!alerts.directToggle() && alerts.feature() == null, "Charge alerts open their sheet; they send no scooter command");
            check(HubRoutes.ACTIONS.contains(alerts.id), "Charge alerts keep an action of their own");
        }
        check(ChargeAlertPolicy.shortcutLevel(false, ChargeAlertPolicy.Settings.OFF.charge(true, 70)) == null, "not offered: no value");
        check(ChargeAlertPolicy.shortcutLevel(true, ChargeAlertPolicy.Settings.OFF) == 0, "offered and off: Off");
        check(ChargeAlertPolicy.shortcutLevel(true, null) == 0, "no settings read: Off");
        check(ChargeAlertPolicy.shortcutLevel(true, ChargeAlertPolicy.Settings.OFF.charge(true, 70)) == 70, "the alert level");
        check(ChargeAlertPolicy.shortcutLevel(true, ChargeAlertPolicy.Settings.OFF.charge(true, 100)) == 100, "Full");
        check(ChargeAlertPolicy.shortcutLevel(true, ChargeAlertPolicy.Settings.OFF.low(true, 20)) == 0, "only the low alert on: the charge alert reads Off");
        check(ChargeAlertPolicy.shortcutLevel(true, ChargeAlertPolicy.Settings.decode("charge=1;at=72")) == 70, "a stored level snaps as the sheet saves it");
        System.out.println("ShortcutPickerTest: " + checks + " checks passed");
    }
}
