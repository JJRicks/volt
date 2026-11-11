package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** HomeQuickActions: Clean Home's four quick actions, defaults and storage (hub layout plan 1.9, PR 6). */
public final class HomeQuickActionsTest {
    private static int checks;
    private static final String MAX = "feature:maximum_speed", TIMED = "feature:timed_charging";

    public static void main(String[] args) {
        cleanOnly();
        defaults();
        normalize();
        storage();
        System.out.println("HomeQuickActions: " + checks + " checks passed");
    }

    private static void cleanOnly() {
        require(HomeQuickActions.known(HomeQuickActions.RIDING) && HomeQuickActions.known(HomeQuickActions.RECORD), "Clean offers Ride and Record");
        require(QuickAccessActions.find(HomeQuickActions.RIDING) == null && QuickAccessActions.find(HomeQuickActions.RECORD) == null,
                "Neither is in the Quick access catalog, so no widget dashboard, slot or transfer can hold one");
        require(!QuickAccessActions.normalize(Arrays.asList(HomeQuickActions.RIDING, HomeQuickActions.RECORD)).contains(HomeQuickActions.RIDING),
                "Widgets' normalize still drops them");
        require(HomeQuickActions.cleanOnly("riding") && HomeQuickActions.cleanOnly("record") && !HomeQuickActions.cleanOnly("rides"), "cleanOnly");
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) require(HomeQuickActions.known(entry.id), "Every catalog id is offered: " + entry.id);
        require(!HomeQuickActions.known("bogus") && !HomeQuickActions.known(null) && !HomeQuickActions.known(""), "Unknown ids are not");
        require(!HomeQuickActions.ICON_RIDING.isEmpty() && !HomeQuickActions.ICON_RECORD.isEmpty(), "Both have icons");
    }

    private static void defaults() {
        List<String> ut5 = HomeQuickActions.defaults(true, true, Arrays.asList(MAX, TIMED));
        require(ut5.equals(Arrays.asList("riding", "record", "charge_done_by", MAX)), "Ride, Record, Charge done by, Drive mode (Joel 3)");
        require(!ut5.contains("lights"), "No lights action by default");
        require(HomeQuickActions.defaults(false, true, Arrays.asList(MAX)).get(2).equals(TIMED), "No Charge done by: Timed charging");
        require(HomeQuickActions.defaults(false, false, Arrays.asList(MAX)).get(2).equals("rides"), "Neither: Rides");
        require(HomeQuickActions.defaults(true, true, Arrays.asList("feature:cruise_control", MAX)).get(3).equals(MAX), "Maximum speed wins wherever it is");
        require(HomeQuickActions.defaults(true, true, Arrays.asList("feature:cruise_control", "feature:headlight")).get(3).equals("feature:cruise_control"),
                "Otherwise the model's first setting tile");
        require(HomeQuickActions.defaults(false, true, Arrays.asList(TIMED, "feature:cruise_control")).get(3).equals("feature:cruise_control"),
                "A tile already listed is skipped");
        require(HomeQuickActions.defaults(true, true, Collections.<String>emptyList()).get(3).equals("controls"), "No tiles: Settings");
        require(HomeQuickActions.defaults(true, true, null).get(3).equals("controls"), "Unknown model: Settings");
        require(HomeQuickActions.defaults(true, true, Arrays.asList("bogus")).get(3).equals("controls"), "Unknown tile ids are ignored");
        for (boolean a : new boolean[]{false, true}) for (boolean b : new boolean[]{false, true}) {
            List<String> ids = HomeQuickActions.defaults(a, b, ModelTiles.DEFAULT);
            require(ids.size() == 4 && ids.stream().distinct().count() == 4 && ids.stream().allMatch(HomeQuickActions::known), "Four distinct known defaults");
        }
        try { ut5.add("x"); require(false, "Defaults are read-only"); } catch (UnsupportedOperationException expected) { checks++; }
    }

    private static void normalize() {
        List<String> d = HomeQuickActions.defaults(true, true, Arrays.asList(MAX));
        require(HomeQuickActions.normalize(null, d).equals(d), "Nothing stored: the defaults");
        require(HomeQuickActions.normalize(Arrays.asList("lights", "stats", "record", "rides"), d).equals(Arrays.asList("lights", "stats", "record", "rides")), "The owner's four in order");
        require(HomeQuickActions.normalize(Arrays.asList("lights", "bogus", "lights"), d).equals(Arrays.asList("lights", "riding", "record", "charge_done_by")),
                "Unknown and repeated ids drop; the defaults top up");
        require(HomeQuickActions.normalize(Arrays.asList("a", "b", "c", "d", "lights", "stats", "rides", "controls", "more:App"), d)
                .equals(Arrays.asList("lights", "stats", "rides", "controls")), "At most four");
        require(HomeQuickActions.normalize(Collections.<String>emptyList(), null).equals(QuickAccessActions.defaultIds()), "No defaults: Quick access's");
        require(HomeQuickActions.normalize(Arrays.asList("riding"), Arrays.asList("riding")).equals(Arrays.asList("riding", "lights", "controls", "stats")),
                "Short defaults top up from Quick access's");
    }

    private static void storage() {
        require(HomeQuickActions.key("abc-1").equals("home_quick_v1_abc-1"), "Per-scooter key");
        BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", HomeQuickActions.key("abc-1"));
        require(rule != null && BackupRegistry.SCOOTER_PREFERENCES.equals(rule.category) && "abc-1".equals(rule.profile("home_quick_v1_abc-1"))
                && rule.rename("home_quick_v1_abc-1", "new").equals("home_quick_v1_new"), "A scooter preference that follows its scooter on restore");
        require(BackupRegistry.rule("app_settings", "dashboard_v1_abc-1") != rule, "Not a dashboard key");
        List<String> four = Arrays.asList("riding", "section:Line lights", "feature:maximum_speed", "more:App");
        require(HomeQuickActions.encode(four).equals("riding,section:Line lights,feature:maximum_speed,more:App"), "Comma-joined");
        require(HomeQuickActions.parse(HomeQuickActions.encode(four)).equals(four), "Round trip");
        require(HomeQuickActions.parse(null) == null && HomeQuickActions.parse(" ") == null && HomeQuickActions.parse(",,") == null, "Blank is nothing stored");
        require(HomeQuickActions.parse(" lights , stats ").equals(Arrays.asList("lights", "stats")), "Spaces around ids are ignored");
        require(HomeQuickActions.encode(Arrays.asList("lights", "a,b", "", null, "stats")).equals("lights,stats"), "Ids that can't be stored are skipped");
        List<String> d = HomeQuickActions.defaults(true, true, Arrays.asList(MAX));
        require(HomeQuickActions.load(null, d).equals(d) && HomeQuickActions.load("record,lights", d).equals(Arrays.asList("record", "lights", "riding", "charge_done_by")), "load");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
