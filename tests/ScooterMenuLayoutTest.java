package com.jjricks.volt;

import java.util.*;

/** The Scooter tab's menu layout: defaults, saved order and hiding, new and removed ids, storage and backups. */
public final class ScooterMenuLayoutTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    public static void main(String[] args) {
        defaults();
        changes();
        storage();
        newAndRemoved();
        tolerant();
        wideColumns();
        backup();
        System.out.println("ScooterMenuLayoutTest: " + checks + " checks passed (default order, moves, hiding, anchored inserts, storage, backups)");
    }

    /** Shaped like today's Scooter page (ControlsScreen builds the real one from its own code order). */
    private static LinkedHashMap<String, List<String>> today() {
        LinkedHashMap<String, List<String>> d = new LinkedHashMap<>();
        d.put("speed", Collections.singletonList("top_speed"));
        d.put("Ride", Arrays.asList("cruise_control", "energy_recovery", "traction_control", "hill_start_assist", "start_speed", "maximum_speed"));
        d.put("Battery", Arrays.asList("charge_limit", "low_power", "timed_charging", "charge_start", "clock_sync", "schedule_test", "charger", "charge_alert", "low_alert"));
        d.put("Lights", Arrays.asList("headlight_info", "auto_sensor", "headlight", "light_zones"));
        d.put("Dashboard & sound", Arrays.asList("display_units", "range_algorithm", "turn_sound"));
        d.put("Security", Arrays.asList("lock_expiry", "security_link"));
        d.put("Profile", Collections.singletonList("scooter_name"));
        return d;
    }

    private static void defaults() {
        LinkedHashMap<String, List<String>> d = today();
        for (String saved : new String[]{null, ""}) {
            ScooterMenuLayout layout = ScooterMenuLayout.resolve(d, saved);
            check(layout.isDefault(), "no saved layout is the default menu");
            check(layout.sections().equals(new ArrayList<>(d.keySet())), "default sections are today's order, Top speed first: " + layout.sections());
            for (Map.Entry<String, List<String>> e : d.entrySet()) check(layout.rows(e.getKey()).equals(e.getValue()), "default rows are today's order: " + e.getKey());
            check(layout.hiddenCount(new HashSet<>(Arrays.asList("cruise_control", "top_speed"))) == 0, "nothing hidden by default");
        }
        ScooterMenuLayout layout = ScooterMenuLayout.defaults(d);
        check(ScooterMenuLayout.resolve(d, layout.toJson()).isDefault(), "a saved default reads back as the default");
        check("Battery".equals(layout.sectionOf("charger")) && layout.sectionOf("nope") == null, "a row's section is its default section");
        check(layout.rows("Nowhere").isEmpty(), "an unknown section has no rows");
        Set<String> known = new HashSet<>(d.keySet());
        for (List<String> rows : d.values()) known.addAll(rows);
        check(layout.known().equals(known), "every default id is known");
    }

    private static void changes() {
        ScooterMenuLayout layout = ScooterMenuLayout.defaults(today());
        check(!layout.moveSection("speed", -1), "the first section can't move up");
        check(!layout.moveSection("Profile", 1), "the last section can't move down");
        check(!layout.moveSection("Nowhere", 1), "an unknown section doesn't move");
        check(layout.moveSection("Battery", -1) && layout.sections().subList(0, 3).equals(Arrays.asList("speed", "Battery", "Ride")), "Battery moves above Ride");
        check(layout.moveSectionTo("speed", 2) && layout.sections().subList(0, 3).equals(Arrays.asList("Battery", "Ride", "speed")), "Top speed moves below Ride (its own section)");
        check(layout.moveSection("Profile", -99) && layout.sections().get(0).equals("Profile"), "moves clamp at the top");
        check(layout.moveSection("Profile", 99) && layout.sections().get(layout.sections().size() - 1).equals("Profile"), "and at the bottom");
        check(layout.moveRow("Ride", "traction_control", -2) && layout.rows("Ride").get(0).equals("traction_control"), "a row moves within its section");
        check(!layout.moveRow("Ride", "charge_limit", 1) && !layout.rows("Ride").contains("charge_limit"), "a row can't move into another section");
        check(!layout.moveRow("Ride", "traction_control", -1), "no-op at the top of its section");
        check(layout.moveRowTo("Battery", "charger", 0) && layout.rows("Battery").get(0).equals("charger"), "a row drops at a position");
        check(!layout.isDefault(), "a reordered menu isn't the default");

        // Nothing is locked from hiding (owner, Q33 A), not even Security.
        check(layout.hide("Security") && layout.sectionHidden("Security") && !layout.hide("Security"), "a section hides once");
        check(layout.hide("security_link") && layout.hide("hill_descent_control") == false, "a row hides; an id this menu lacks doesn't");
        check(layout.hide("top_speed") && layout.hidden("speed", "top_speed"), "the top speed card hides");
        check(layout.hidden("Security", "lock_expiry") && !layout.rowHidden("lock_expiry"), "a hidden section hides its rows without flagging them");
        check(layout.hiddenCount(new HashSet<>(layout.known())) == 2 && layout.hiddenCount(new HashSet<>(Arrays.asList("cruise_control"))) == 0, "a hidden section counts once, a hidden row outside it once; only offered rows count");
        List<String> securityRows = new ArrayList<>(layout.rows("Security"));
        check(layout.show("Security") && layout.rowHidden("security_link") && layout.rows("Security").equals(securityRows), "a shown section keeps its order and its rows' own flags");
        check(!layout.show("Security") && layout.show("security_link") && !layout.hidden("Security", "security_link"), "a row shows again");
        layout.reset();
        check(layout.isDefault(), "reset restores the default order with everything shown");
    }

    private static void storage() {
        LinkedHashMap<String, List<String>> d = today();
        ScooterMenuLayout layout = ScooterMenuLayout.defaults(d);
        layout.moveSection("Battery", -1);
        layout.moveRow("Ride", "start_speed", -3);
        layout.hide("hill_start_assist");
        layout.hide("Dashboard & sound");
        String json = layout.toJson();
        check(json.startsWith("{\"v\":1,"), "versioned JSON: " + json);
        ScooterMenuLayout back = ScooterMenuLayout.resolve(d, json);
        check(back.sections().equals(layout.sections()) && back.rows("Ride").equals(layout.rows("Ride")), "order reads back");
        check(back.rowHidden("hill_start_assist") && back.sectionHidden("Dashboard & sound") && !back.rowHidden("cruise_control"), "hiding reads back");
        check(back.toJson().equals(json), "a read-back layout saves the same text");
        check(back.rows("Ride").equals(Arrays.asList("cruise_control", "start_speed", "energy_recovery", "traction_control", "hill_start_assist", "maximum_speed")), "Ride order: " + back.rows("Ride"));
    }

    private static void newAndRemoved() {
        LinkedHashMap<String, List<String>> old = today();
        ScooterMenuLayout saved = ScooterMenuLayout.defaults(old);
        saved.moveRow("Ride", "traction_control", -2);   // traction, cruise, energy, hill start, start, max
        saved.moveRow("Ride", "energy_recovery", 2);     // traction, cruise, hill start, start, energy, max
        saved.hide("hill_start_assist");
        saved.moveSection("Profile", -5);                // Top speed, Profile, Ride, ...
        String json = saved.toJson();

        // A newer app: a row after energy_recovery, one after a hidden row, one with no predecessor, one whose
        // predecessor was removed, a new section after Lights, and a removed row (start_speed).
        LinkedHashMap<String, List<String>> next = new LinkedHashMap<>();
        next.put("speed", Collections.singletonList("top_speed"));
        next.put("Ride", Arrays.asList("first_new", "cruise_control", "energy_recovery", "after_energy", "traction_control", "hill_start_assist", "after_hidden", "maximum_speed"));
        next.put("Battery", Arrays.asList("charge_limit", "low_power", "timed_charging", "charge_start", "clock_sync", "schedule_test", "charger", "charge_alert", "low_alert"));
        next.put("Lights", Arrays.asList("headlight_info", "auto_sensor", "headlight", "light_zones"));
        next.put("Lab", Collections.singletonList("lab_row"));
        next.put("Dashboard & sound", Arrays.asList("display_units", "range_algorithm", "turn_sound"));
        next.put("Security", Arrays.asList("lock_expiry", "security_link"));
        next.put("Profile", Collections.singletonList("scooter_name"));
        ScooterMenuLayout layout = ScooterMenuLayout.resolve(next, json);
        check(layout.rows("Ride").equals(Arrays.asList("first_new", "traction_control", "cruise_control", "hill_start_assist", "after_hidden", "energy_recovery", "after_energy", "maximum_speed")),
                "new rows land after their nearest saved predecessor (hidden ones too), or first: " + layout.rows("Ride"));
        check(!layout.rowHidden("after_hidden") && layout.rowHidden("hill_start_assist"), "a new row is shown; the saved hidden row stays hidden");
        check(!layout.rows("Ride").contains("start_speed"), "a removed row leaves the order");
        check(layout.known().contains("start_speed") && layout.known().contains("lab_row"), "known keeps removed ids and gains new ones");
        check(layout.sections().equals(Arrays.asList("speed", "Profile", "Ride", "Battery", "Lights", "Lab", "Dashboard & sound", "Security")), "a new section lands after its default predecessor: " + layout.sections());

        // A new row whose default predecessor is itself gone: it walks further back.
        LinkedHashMap<String, List<String>> later = new LinkedHashMap<>(old);
        later.put("Battery", Arrays.asList("charge_limit", "low_power", "timed_charging", "charge_start", "clock_sync", "schedule_test", "after_charger", "charge_alert", "low_alert"));
        ScooterMenuLayout battery = ScooterMenuLayout.defaults(old);
        battery.moveRow("Battery", "schedule_test", -5);
        ScooterMenuLayout walked = ScooterMenuLayout.resolve(later, battery.toJson());
        check(walked.rows("Battery").indexOf("after_charger") == walked.rows("Battery").indexOf("schedule_test") + 1, "a removed predecessor is skipped: " + walked.rows("Battery"));

        // A removed row that returns comes back where its default puts it, still hidden if it was hidden.
        ScooterMenuLayout hid = ScooterMenuLayout.defaults(old);
        hid.hide("start_speed");
        LinkedHashMap<String, List<String>> without = new LinkedHashMap<>(old);
        without.put("Ride", Arrays.asList("cruise_control", "energy_recovery", "traction_control", "hill_start_assist", "maximum_speed"));
        String gone = ScooterMenuLayout.resolve(without, hid.toJson()).toJson();
        ScooterMenuLayout returned = ScooterMenuLayout.resolve(old, gone);
        check(returned.rowHidden("start_speed") && returned.rows("Ride").equals(old.get("Ride")), "a returning id keeps its hidden flag and its usual spot");

        check(ScooterMenuLayout.merge(Arrays.asList("a", "b", "c"), Arrays.asList("c", "c", "x", "a")).equals(Arrays.asList("c", "a", "b")), "duplicates and unknown ids are dropped: " + ScooterMenuLayout.merge(Arrays.asList("a", "b", "c"), Arrays.asList("c", "c", "x", "a")));
    }

    private static void tolerant() {
        LinkedHashMap<String, List<String>> d = today();
        for (String bad : new String[]{"{broken", "[]", "7", "null", "{}", "{\"v\":2,\"sections\":[\"Profile\"]}", "{\"v\":\"1\",\"sections\":[\"Profile\"]}",
                "{\"v\":1,\"sections\":7,\"rows\":[],\"hiddenRows\":{\"a\":1},\"hiddenSections\":\"Ride\",\"known\":null}",
                "{\"v\":1,\"sections\":[[[[[[\"deep\"]]]]]]}", "{\"v\":1,\"v\":1}"}) {
            ScooterMenuLayout layout = ScooterMenuLayout.resolve(d, bad);
            check(layout.isDefault(), "unreadable or foreign text gives the default menu: " + bad);
        }
        ScooterMenuLayout odd = ScooterMenuLayout.resolve(d, "{\"v\":1,\"sections\":[\"Ride\",3,\"\",\"Ride\",\"speed\"],\"rows\":{\"Ride\":[\"maximum_speed\",\"cruise_control\",null],\"Nowhere\":[\"x\"]},\"hiddenRows\":[\"turn_sound\",5]}");
        check(odd.sections().indexOf("Ride") < odd.sections().indexOf("speed") && odd.rows("Ride").get(0).equals("maximum_speed") && odd.rowHidden("turn_sound"), "stray values are skipped, the rest applies: " + odd.sections());
    }

    private static void wideColumns() {
        ScooterMenuLayout layout = ScooterMenuLayout.defaults(today());
        check(layout.wideLeftCount() == 2, "default wide page: Top speed and Ride on the left");
        layout.hide("Ride");
        check(layout.wideLeftCount() == 3, "a hidden first section doesn't fill the left column");
        layout.reset();
        layout.moveSection("speed", 3);
        check(layout.wideLeftCount() == 1, "Ride first: only Ride on the left");
    }

    private static void backup() {
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", "scooter_menu_v1_abc-1");
        check(rule != null && BackupRegistry.SCOOTER_PREFERENCES.equals(rule.category) && rule.perProfile && rule.type == String.class, "the menu layout travels with Scooter preferences, per scooter");
        check("abc-1".equals(rule.profile("scooter_menu_v1_abc-1")) && "scooter_menu_v1_new".equals(rule.rename("scooter_menu_v1_abc-1", "new")), "it remaps onto another scooter");
        check(BackupRegistry.rule("app_settings", "scooter_menu_v1_") == null, "a bare prefix is not a key");
        check(!BackupRegistry.DASHBOARDS.equals(rule.category), "not Dashboards, which feeds the layout export and counts");
        Map<String, String> files = new TreeMap<>();
        files.put("ScooterMenuLayout.java", "final class ScooterMenuLayout{static final String PREFIX = \"scooter_menu_v1_\";}");
        files.put("Screen.java", "class Screen{void a(android.content.SharedPreferences p,String id){p.getString(ScooterMenuLayout.PREFIX+id,null);}}");
        check(BackupRegistryTest.scan(files).isEmpty(), "the registry scan finds the menu key classified: " + BackupRegistryTest.scan(files));
        files.put("ScooterMenuLayout.java", files.get("ScooterMenuLayout.java").replace("scooter_menu_v1_", "scooter_menu_v9_"));
        check(!BackupRegistryTest.scan(files).isEmpty(), "and would fail on an unregistered menu key");
        check(ScooterMenuLayout.PREFIX.equals("scooter_menu_v1_"), "the stored key prefix");
    }
}
