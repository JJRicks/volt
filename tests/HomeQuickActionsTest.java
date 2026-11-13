package com.jjricks.volt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** HomeQuickActions: Clean Home's four quick actions, defaults and storage (hub layout plan 1.9, PR 6). */
public final class HomeQuickActionsTest {
    private static int checks;
    private static final String MAX = "feature:maximum_speed", CRUISE = "feature:cruise_control";

    public static void main(String[] args) {
        cleanOnly();
        defaults();
        normalize();
        storage();
        entries();
        endsRide();
        System.out.println("HomeQuickActions: " + checks + " checks passed");
    }

    private static void cleanOnly() {
        require(HomeQuickActions.known(HomeQuickActions.RIDING) && HomeQuickActions.known(HomeQuickActions.RECORD), "Clean offers Ride and Record");
        require(QuickAccessActions.find(HomeQuickActions.RIDING) == null && QuickAccessActions.find(HomeQuickActions.RECORD) == null,
                "Neither is in the Quick access catalog, so no widget dashboard, slot or transfer can hold one");
        require(!QuickAccessActions.normalize(Arrays.asList(HomeQuickActions.RIDING, HomeQuickActions.RECORD)).contains(HomeQuickActions.RIDING),
                "Widgets' normalize still drops them");
        require(HomeQuickActions.cleanOnly("riding") && HomeQuickActions.cleanOnly("record") && HomeQuickActions.cleanOnly("charge_alerts")
                && !HomeQuickActions.cleanOnly("rides"), "cleanOnly");
        for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) require(HomeQuickActions.known(entry.id), "Every catalog id is offered: " + entry.id);
        require(!HomeQuickActions.known("bogus") && !HomeQuickActions.known(null) && !HomeQuickActions.known(""), "Unknown ids are not");
        require(!HomeQuickActions.ICON_RIDING.isEmpty() && !HomeQuickActions.ICON_RECORD.isEmpty() && !HomeQuickActions.ICON_CHARGE_ALERTS.isEmpty(), "All have icons");
    }

    private static void endsRide() {
        require(HomeQuickActions.endsRide(HomeQuickActions.RECORD, true), "Record while a ride records ends and saves it, and says so");
        require(!HomeQuickActions.endsRide(HomeQuickActions.RECORD, false), "Record with no ride recording starts one");
        require(!HomeQuickActions.endsRide(HomeQuickActions.RIDING, true) && !HomeQuickActions.endsRide("rides", true)
                && !HomeQuickActions.endsRide(null, true), "No other action ends a ride");
        require(HomeQuickActions.startsRide(HomeQuickActions.RECORD, false), "Record with no ride recording starts one, and says so");
        require(!HomeQuickActions.startsRide(HomeQuickActions.RECORD, true), "Record while a ride records does not start one");
        require(!HomeQuickActions.startsRide(HomeQuickActions.RIDING, false) && !HomeQuickActions.startsRide("rides", false)
                && !HomeQuickActions.startsRide(null, false), "No other action starts a ride");
        // Exactly one of the two holds for Record, whatever the state.
        for (boolean recording : new boolean[]{false, true})
            require(HomeQuickActions.startsRide(HomeQuickActions.RECORD, recording) != HomeQuickActions.endsRide(HomeQuickActions.RECORD, recording),
                    "Record either starts or ends a ride");
    }

    private static void defaults() {
        // The UT5 MAX: scheduled charging, no charge alerts (it has a charge limit), cruise control first.
        List<String> ut5 = HomeQuickActions.defaults(true, false, offers("cruise_control", "traction_control", "maximum_speed", "timed_charging"));
        require(ut5.equals(Arrays.asList("riding", "record", "charge_done_by", CRUISE)), "Ride, Record, Charge done by, then a control the scooter offers (answer 11)");
        require(!ut5.contains("lights"), "No lights action by default");
        require(!ut5.contains(MAX), "Maximum speed is no longer the Drive mode default");
        require(HomeQuickActions.defaults(false, true, offers("cruise_control")).equals(Arrays.asList("riding", "record", "charge_alerts", CRUISE)),
                "No scheduled charging: charge alerts take Charge done by's place");
        require(HomeQuickActions.defaults(true, true, offers("cruise_control")).get(2).equals("charge_done_by"), "Scheduled charging wins over alerts");
        require(HomeQuickActions.defaults(false, false, offers("cruise_control", "traction_control")).equals(Arrays.asList("riding", "record", CRUISE, "feature:traction_control")),
                "Neither: a second offered control fills the third slot");
        require(HomeQuickActions.defaults(true, false, offers("traction_control", "hill_start_assist")).get(3).equals("feature:hill_start_assist"),
                "Walk assist comes before traction control");
        require(HomeQuickActions.defaults(true, false, offers("parking_assist", "traction_control")).get(3).equals("feature:traction_control"),
                "Traction control comes before power assist");
        require(HomeQuickActions.defaults(true, false, offers("parking_assist")).get(3).equals("feature:parking_assist"), "Power assist when it is the only one");
        require(HomeQuickActions.defaults(true, false, offers("maximum_speed", "headlight", "acceleration")).get(3).equals("rides"),
                "Controls outside the order never become a default; Rides fills in");
        require(HomeQuickActions.defaults(false, false, null).equals(Arrays.asList("riding", "record", "rides", "controls")), "Nothing offered: Rides, then Settings");
        for (String key : HomeQuickActions.CONTROL_ORDER) {
            ScooterCommands.Feature feature = ScooterCommands.find(key);
            require(feature != null && !feature.sensitive, "Every control in the order is a catalog control: " + key);
            require(HomeQuickActions.known(QuickAccessActions.FEATURE_PREFIX + key), "and a quick action: " + key);
        }
        require(!HomeQuickActions.CONTROL_ORDER.contains("maximum_speed") && !HomeQuickActions.CONTROL_ORDER.contains("dual_drive"),
                "No drive mode stand-in among the default controls");
        require(!HomeQuickActions.CONTROL_ORDER.contains("acceleration"), "Nor the Eco/Turbo acceleration profile, which reads the drive mode");
        // The drive mode is a reading set on the scooter: never a quick action Clean offers (answer 11).
        require(!HomeQuickActions.known("driving_mode") && !HomeQuickActions.known(QuickAccessActions.FEATURE_PREFIX + "driving_mode"), "No drive mode action");
        for (boolean a : new boolean[]{false, true}) for (boolean b : new boolean[]{false, true})
            for (java.util.function.Predicate<String> p : Arrays.<java.util.function.Predicate<String>>asList(null, k -> true, k -> false, k -> k.equals("hill_descent_control"))) {
                List<String> ids = HomeQuickActions.defaults(a, b, p);
                require(ids.size() == 4 && ids.stream().distinct().count() == 4 && ids.stream().allMatch(HomeQuickActions::known), "Four distinct known defaults");
                require(ids.get(0).equals("riding") && ids.get(1).equals("record"), "Ride and Record always lead");
            }
        try { ut5.add("x"); require(false, "Defaults are read-only"); } catch (UnsupportedOperationException expected) { checks++; }
    }

    private static java.util.function.Predicate<String> offers(String... keys) {
        List<String> offered = Arrays.asList(keys);
        return offered::contains;
    }

    private static void normalize() {
        List<String> d = HomeQuickActions.defaults(true, false, offers("cruise_control"));
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
        List<String> d = HomeQuickActions.defaults(true, false, offers("cruise_control"));
        require(HomeQuickActions.load(null, d).equals(d) && HomeQuickActions.load("record,lights", d).equals(Arrays.asList("record", "lights", "riding", "charge_done_by")), "load");
        require(HomeQuickActions.load(" , ", d).equals(d) && HomeQuickActions.load("bogus,nonsense", d).equals(d), "Unreadable: the defaults");
        require(HomeQuickActions.load("feature:maximum_speed,lights,stats,rides", d).equals(Arrays.asList(MAX, "lights", "stats", "rides")),
                "An owner's stored four are kept as stored, Maximum speed included");
    }

    /** Clean's own as entries outside the catalog (PR 8): shown by Clean's grid and picker, never findable. */
    private static void entries() {
        for (String id : Arrays.asList(HomeQuickActions.RIDING, HomeQuickActions.RECORD, HomeQuickActions.CHARGE_ALERTS)) {
            QuickAccessActions.Entry entry = HomeQuickActions.entry(id);
            require(entry != null && entry.id.equals(id) && entry.featureKey == null && entry.feature() == null, "An entry for " + id);
            require(entry.label().id() == HubText.quickActionLabel(id).id() && entry.shortLabel().id() == HubText.quickActionShortLabel(id).id(),
                    "Named by HubText, with its own short rung: " + id);
            require(entry.icon.equals(id.equals(HomeQuickActions.RIDING) ? HomeQuickActions.ICON_RIDING
                    : id.equals(HomeQuickActions.RECORD) ? HomeQuickActions.ICON_RECORD : HomeQuickActions.ICON_CHARGE_ALERTS), "Its icon: " + id);
            require(!entry.directToggle(), "Not a direct toggle: " + id);
            require(QuickAccessActions.find(id) == null, "Still not in the catalog: " + id);
            for (QuickAccessActions.Entry listed : QuickAccessActions.catalog()) require(!listed.id.equals(id), "Not listed in the catalog: " + id);
        }
        require(HomeQuickActions.entry("lights") == QuickAccessActions.find("lights"), "A catalog id resolves to the catalog's entry");
        require(HomeQuickActions.entry("bogus") == null && HomeQuickActions.entry(null) == null, "Unknown ids resolve to nothing");
        List<QuickAccessActions.Entry> own = HomeQuickActions.ownEntries(false);
        require(own.size() == 2 && own.get(0).id.equals(HomeQuickActions.RIDING) && own.get(1).id.equals(HomeQuickActions.RECORD), "The picker lists Ride, then Record");
        own = HomeQuickActions.ownEntries(true);
        require(own.size() == 3 && own.get(2).id.equals(HomeQuickActions.CHARGE_ALERTS), "Battery alerts follow where the model has them");
        require(HomeQuickActions.entry(HomeQuickActions.RIDING).label().id() == M.ridingRideButton().id(), "Clean's way into riding mode says RIDE, as the Widgets header does");
        require(QuickAccessActions.find("lights").shortLabel().id() == M.dashboardModelQaLightsShort().id()
                && QuickAccessActions.find("rides").shortLabel().id() == M.dashboardModelQaRidesShort().id(), "Catalog short rungs unchanged");
        for (String id : HomeQuickActions.defaults(false, true, offers("cruise_control"))) require(HomeQuickActions.entry(id) != null, "Every default has an entry: " + id);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
