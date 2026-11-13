package com.jjricks.volt;

import com.jjricks.volt.MaintenancePolicy.Reminder;

import java.time.ZoneId;
import java.util.*;

/**
 * The developer simulator's model picker and scenes (Developer options › Simulated model, Scene, Drive mode):
 * every named model from the registry, each scene's readings consistent for the UT5 MAX, a model without a charge
 * limit (GT3 Max) and a model whose controls differ by region (GT5 Max), switches that take effect at once, and the
 * pure writers a simulated ride or charge reaches (maintenance distance, auto-record, charge alerts, range following).
 */
public final class SimulatorScenesTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        pickerListsEveryNamedModel();
        regionEntriesFollowTheRules();
        modelFacts();
        settingsFollowTheModel();
        for (SimulatorModels.Entry e : threeModels()) {
            parked(e);
            riding(e);
            charging(e);
        }
        chargeLimitAndChargers();
        driveModes();
        switchesTakeEffectAtOnce();
        historyShapes();
        maintenanceDistanceStaysInMemory();
        autoStartIgnoresSimulatedMotion();
        rangeFollowForgetsAcrossToggles();
        System.out.println("SimulatorScenes tests passed: " + checks + " assertions");
    }

    /** Both UT5 MAX regions, the GT3 Max (no charge limit, unsigned current) and both GT5 Max regions. */
    private static List<SimulatorModels.Entry> threeModels() {
        List<SimulatorModels.Entry> out = new ArrayList<>();
        for (SimulatorModels.Entry e : SimulatorModels.entries())
            if (e.pid.isEmpty() || e.pid.equals("24182") || e.pid.equals("25462")) out.add(e);
        check(out.size() == 2 + 2 + 2, "two UT5 MAX, two GT3 Max and two GT5 Max entries: " + out);
        return out;
    }

    private static void pickerListsEveryNamedModel() {
        List<SimulatorModels.Entry> entries = SimulatorModels.entries();
        check(entries.get(0).pid.isEmpty() && entries.get(0).label().equals("UT5 MAX (US)") && "US".equals(entries.get(0).region)
                && entries.get(1).pid.isEmpty() && entries.get(1).label().equals("UT5 MAX (EU)") && "EU".equals(entries.get(1).region),
                "the UT5 MAX comes first, as UT5 MAX (US) and UT5 MAX (EU)");
        SimulatorModels.Entry mapped = entries.get(entries.size() - 2), unknown = entries.get(entries.size() - 1);
        check(mapped.pid.equals("24431") && unknown.pid.equals("39991"), "the first simulator's mapped and unknown models stay, last");
        check(SimulatorModels.shortLabel(mapped).equals("Mapped") && SimulatorModels.shortLabel(unknown).equals("Unknown"),
                "their short row values stay");
        Set<String> names = new HashSet<>(), labels = new HashSet<>();
        String previous = "";
        for (SimulatorModels.Entry e : entries.subList(2, entries.size() - 2)) {
            names.add(e.name);
            check(labels.add(e.label()), "every line is distinct: " + e.label());
            check(e.name.compareToIgnoreCase(previous) >= 0, "named models are in name order: " + e.name);
            previous = e.name;
            check(ModelRegistry.modelName(e.descriptor()).equals(e.name), "the entry simulates the model it names: " + e);
            check(!ModelRegistry.ut5Max(e.descriptor()), "a named model is never the UT5 MAX layer: " + e);
        }
        for (String[] known : ModelRegistry.knownModels().values())
            check(names.contains(known[0]), "every model the registry names is offered: " + known[0]);
        check(names.size() == ModelRegistry.knownModels().size(), "and nothing the registry doesn't name");
        check(SimulatorModels.find("", null) == entries.get(0) && SimulatorModels.find(null, "US") == entries.get(0)
                && SimulatorModels.find("", "EU") == entries.get(1) && SimulatorModels.find("", "DE") == entries.get(1),
                "the default UT5 MAX simulator (no region) shows as UT5 MAX (US); other regions as the entry with their rules");
        // The UT5 MAX entry chosen before this change (no region) still means US controls: the same decisions as UT5 MAX (US).
        check(SimulatorModels.signature(DeviceDescriptor.simulated("", 0, null)).equals(SimulatorModels.signature(entries.get(0).descriptor())),
                "the old UT5 MAX entry and UT5 MAX (US) decide every control alike");
        check(CapabilityResolver.decide("maximum_speed", entries.get(1).descriptor(), StatusFields.NONE, null).reason == CapabilityResolver.Reason.REGION_RULE
                && CapabilityResolver.decide("maximum_speed", entries.get(0).descriptor(), StatusFields.NONE, null).support == CapabilityResolver.Support.KNOWN_ENCODING,
                "UT5 MAX (EU) has no Top speed, as a real EU UT5 MAX; UT5 MAX (US) has it");
        check(SimulatorModels.find("99999", null) == entries.get(0), "an ID the picker doesn't offer shows as the UT5 MAX row");
    }

    private static void regionEntriesFollowTheRules() {
        Map<String, List<SimulatorModels.Entry>> byModel = new LinkedHashMap<>();
        for (SimulatorModels.Entry e : SimulatorModels.entries()) {
            if (!byModel.containsKey(e.pid)) byModel.put(e.pid, new ArrayList<>());
            byModel.get(e.pid).add(e);
        }
        for (List<SimulatorModels.Entry> group : byModel.values()) {
            Set<String> signatures = new HashSet<>();
            for (SimulatorModels.Entry e : group) check(signatures.add(SimulatorModels.signature(e.descriptor())),
                    "each region entry decides at least one control differently: " + e);
            if (group.size() == 1) continue;
            for (SimulatorModels.Entry e : group) check(e.region != null && e.label().endsWith("(" + e.region + ")"),
                    "a model with region entries names the region on every one: " + e);
            // Every serial region lands on one of the model's entries (find matches the rules, not the letters).
            for (String area : DeviceDescriptor.OEM_AREAS) {
                SimulatorModels.Entry found = SimulatorModels.find(group.get(0).pid, area);
                check(SimulatorModels.signature(found.descriptor()).equals(SimulatorModels.signature(DeviceDescriptor.simulated(found.pid, 0, area))),
                        "region " + area + " shows as the entry with its rules: " + found);
            }
        }
        // The GT5 Max's regions differ by Top speed (a US scooter offers it; elsewhere the official app's region rule hides it).
        SimulatorModels.Entry us = SimulatorModels.find("25462", "US"), eu = SimulatorModels.find("25462", "EU");
        check(us != eu && "US".equals(us.region) && "EU".equals(eu.region), "GT5 Max has US and EU entries");
        check(CapabilityResolver.decide("maximum_speed", us.descriptor(), StatusFields.NONE, null).support == CapabilityResolver.Support.KNOWN_ENCODING
                && CapabilityResolver.decide("maximum_speed", eu.descriptor(), StatusFields.NONE, null).reason == CapabilityResolver.Reason.REGION_RULE,
                "the US entry offers Top speed and the EU one refuses it by region rule");
        check(SimulatorModels.find("25462", "FR") == eu && SimulatorModels.find("25462", null) == eu, "France and no region behave as the EU entry");
    }

    private static void modelFacts() {
        for (SimulatorModels.Entry e : SimulatorModels.entries()) {
            boolean[] f = SimulatorModels.facts(e.descriptor());
            check(f[0] == !ChargeAlertPolicy.offered(e.descriptor()), "charge limit and charge alerts are opposite: " + e);
            check(f[0] == (CapabilityResolver.decide("charge_limit", e.descriptor(), StatusFields.NONE, null).support == CapabilityResolver.Support.KNOWN_ENCODING),
                    "a native charge limit is exactly where the registry offers the control: " + e);
            check(f[2] == ModelRegistry.ut5Max(e.descriptor()), "only the UT5 MAX tells the chargers apart: " + e);
        }
        boolean[] ut5 = SimulatorModels.facts(SimulatorModels.find("", null).descriptor());
        boolean[] gt3 = SimulatorModels.facts(SimulatorModels.find("24182", "US").descriptor());
        check(ut5[0] && ut5[1] && ut5[2], "UT5 MAX: charge limit, signed current, two chargers");
        check(!gt3[0] && !gt3[1] && !gt3[2], "GT3 Max: no charge limit (alerts instead), an unsigned current, one charger");
    }

    /**
     * Review finding (P2): a newly chosen model keeps no setting it doesn't offer. Every model after every other model
     * (each carrying in the settings the one before left), every control: inside its own choices as the screens read them.
     */
    private static void settingsFollowTheModel() {
        ScooterSimulation fresh = new ScooterSimulation();
        Map<String, Integer> defaults = fresh.features();
        fresh.conform(SimulatorModels.find("", null).descriptor());
        check(fresh.features().equals(defaults), "the UT5 MAX simulator's own settings are all its choices already: nothing changes");
        List<SimulatorModels.Entry> entries = SimulatorModels.entries();
        int controls = 0;
        for (SimulatorModels.Entry from : entries) for (SimulatorModels.Entry to : entries) {
            ScooterSimulation s = new ScooterSimulation();
            s.conform(from.descriptor());
            s.conform(to.descriptor());
            controls += inside(s, to, from + " then " + to);
        }
        check(controls > entries.size() * entries.size(), "the sweep looked at the models' controls: " + controls);
        // The finding's own case: the UT5 MAX's 50 km/h on a US GT5 Max becomes its nearest offered speed, 45.
        SimulatorModels.Entry gt5 = SimulatorModels.find("25462", "US");
        ScooterSimulation s = new ScooterSimulation();
        check(s.featureValue("maximum_speed") == 50, "the UT5 MAX simulator starts at 50 km/h");
        s.setModel(gt5.pid); s.conform(gt5.descriptor());
        int[] offered = CapabilityResolver.decide("maximum_speed", gt5.descriptor(), StatusFields.NONE, simulationRuntime()).values();
        check(Arrays.equals(offered, new int[]{25, 32, 40, 45}) && s.featureValue("maximum_speed") == 45,
                "a US GT5 Max opens at 45 km/h, its nearest choice: " + s.featureValue("maximum_speed") + " of " + Arrays.toString(offered));
        // A value the new model still offers is kept.
        ScooterSimulation kept = new ScooterSimulation();
        kept.set("maximum_speed", 32);
        kept.setModel(gt5.pid); kept.conform(gt5.descriptor());
        check(kept.featureValue("maximum_speed") == 32, "a Top speed the GT5 Max also offers stays");
        // Back on the UT5 MAX, 32 is one of its choices too and stays; the GT5 Max's 45 isn't, and becomes the nearer
        // lower choice, 40 (40 and 50 are as near; ties go lower).
        kept.setModel(""); kept.conform(SimulatorModels.find("", null).descriptor());
        check(kept.featureValue("maximum_speed") == 32, "returning to the UT5 MAX keeps 32 km/h");
        kept.setModel(gt5.pid); kept.conform(gt5.descriptor());
        kept.set("maximum_speed", 45, ScooterSimulation.offeredVariant("maximum_speed", gt5.descriptor()));
        kept.setModel(""); kept.conform(SimulatorModels.find("", null).descriptor());
        check(kept.featureValue("maximum_speed") == 40, "the GT5 Max's 45 km/h becomes the UT5 MAX's 40: " + kept.featureValue("maximum_speed"));
        // Review round 3 (P3): a value only Experimental controls offer is kept when the same entry is picked again.
        // UT5 MAX (EU): kick-start's own row offers 3-5 km/h; Experimental controls also try 0.
        SimulatorModels.Entry eu = SimulatorModels.find("", "EU");
        FeatureVariant euRow = ScooterSimulation.ordinaryVariant("start_speed", eu.descriptor()), euTry = ScooterSimulation.tryVariant("start_speed", eu.descriptor());
        check(euRow != null && !euRow.allows(0) && euTry != null && euTry.allows(0), "UT5 MAX (EU): kick-start 0 only through Experimental controls");
        ScooterSimulation tried = simulation(eu);
        tried.set("start_speed", 0, euTry);
        check(tried.featureValue("start_speed") == 0, "Experimental controls set kick-start 0");
        tried.conform(eu.descriptor());
        check(tried.featureValue("start_speed") == 0, "picking UT5 MAX (EU) again keeps the experimental 0 km/h: " + tried.featureValue("start_speed"));
        tried.conform(SimulatorModels.find("", null).descriptor());
        check(tried.featureValue("start_speed") == 0, "and so does UT5 MAX (US), whose own row offers 0");
        // A value neither variant offers still moves to the ordinary row's nearest choice.
        tried.setUnoffered("start_speed", 9);
        tried.conform(eu.descriptor());
        check(tried.featureValue("start_speed") == 5, "kick-start 9 on UT5 MAX (EU) becomes its nearest choice, 5: " + tried.featureValue("start_speed"));
        // A validation can still set an unoffered value on purpose (Home's unreadable dash).
        kept.setUnoffered("maximum_speed", 51);
        check(kept.featureValue("maximum_speed") == 51, "setUnoffered sets the value as given");
    }
    private static CapabilityResolver.Runtime simulationRuntime() {
        CapabilityResolver.Runtime rt = new CapabilityResolver.Runtime();
        rt.simulation = true; rt.ready = true;
        return rt;
    }
    /** Every simulated setting inside the model's offered variant and inside the choices a screen lists for it; the controls checked. */
    private static int inside(ScooterSimulation s, SimulatorModels.Entry e, String where) {
        DeviceDescriptor d = e.descriptor();
        int checked = 0;
        for (Map.Entry<String, Integer> f : s.features().entrySet()) {
            FeatureVariant v = ScooterSimulation.offeredVariant(f.getKey(), d), tried = ScooterSimulation.tryVariant(f.getKey(), d);
            if (v == null) continue;
            checked++;
            // Inside the ordinary variant, or (review round 3) inside the Experimental controls try's.
            FeatureVariant holds = f.getValue() != null && v.allows(f.getValue()) ? v : f.getValue() != null && tried != null && tried.allows(f.getValue()) ? tried : null;
            check(holds != null, where + ": " + f.getKey() + " = " + f.getValue() + " is outside " + v.id);
            for (CapabilityResolver.Decision shown : new CapabilityResolver.Decision[]{
                    CapabilityResolver.decide(f.getKey(), d, StatusFields.NONE, simulationRuntime()),
                    CapabilityResolver.decideTry(f.getKey(), d, StatusFields.NONE, simulationRuntime())}) {
                if (shown.support != CapabilityResolver.Support.KNOWN_ENCODING || shown.variant != holds || shown.values().length == 0) continue;
                boolean listed = false;
                for (int c : shown.values()) listed |= c == f.getValue();
                check(listed, where + ": " + f.getKey() + " = " + f.getValue() + " is not among the screen's choices " + Arrays.toString(shown.values()));
            }
        }
        return checked;
    }

    private static ScooterSimulation simulation(SimulatorModels.Entry e) {
        ScooterSimulation s = new ScooterSimulation();
        s.setModel(e.pid);
        boolean[] f = SimulatorModels.facts(e.descriptor());
        s.setModelFacts(f[0], f[1], f[2]);
        s.conform(e.descriptor());
        s.setDriveModes(SimulatorModels.driveModes(e.descriptor()));
        return s;
    }
    private static double n(Telemetry t, String key) {
        Double v = t.number(key);
        if (v == null) throw new AssertionError("no number for " + key);
        return v;
    }
    private static void consistentElectrics(Telemetry t, String where) {
        check(Math.abs(n(t, "power_w") - n(t, "voltage_v") * n(t, "current_a")) < .2, where + ": power is voltage × current");
        check(n(t, "voltage_v") > 40 && n(t, "voltage_v") <= 54.6, where + ": a 13-cell pack's voltage: " + n(t, "voltage_v"));
    }
    private static void signRule(Telemetry t, boolean signed, String where) {
        double state = n(t, "current_sign_state");
        if (signed) check(state == 0 || state == 1, where + ": a signed model's sign state is 0 or 1");
        else check(state == 254 && n(t, "current_a") >= 0 && n(t, "power_w") >= 0, where + ": an unsigned model reads 254 and a magnitude");
        // PowerSign must read the same direction the scene means.
        check(t.frames().isEmpty() && t.statuses().isEmpty(), where + ": no wire evidence");
    }

    private static void parked(SimulatorModels.Entry e) {
        ScooterSimulation s = simulation(e); Telemetry t = new Telemetry();
        boolean signed = SimulatorModels.facts(e.descriptor())[1];
        double battery = -1, odometer = -1;
        for (int second = 0; second <= 300; second++) {
            s.tick(t, second * 1000L);
            String where = e + " parked at " + second + " s";
            check(n(t, "speed_kmh") == 0 && n(t, "driving_status") == 0 && n(t, "charging_state") == 0, where + ": standing, not charging");
            consistentElectrics(t, where); signRule(t, signed, where);
            check(n(t, "current_a") > 0, where + ": a small standby draw");
            if (second == 0) { battery = n(t, "battery_percent"); odometer = n(t, "odometer_km"); }
            check(n(t, "battery_percent") == battery && n(t, "odometer_km") == odometer, where + ": battery and odometer hold");
        }
        s.set("lock", 1); s.tick(t, 301000);
        check(n(t, "lock_status") == 1, e + ": the lock reading follows the lock control");
        s.set("lock", 0); s.tick(t, 302000);
        check(n(t, "lock_status") == 0, e + ": and back");
    }

    private static void riding(SimulatorModels.Entry e) {
        ScooterSimulation s = simulation(e); Telemetry t = new Telemetry();
        boolean signed = SimulatorModels.facts(e.descriptor())[1];
        s.tick(t, 1000);
        double trip0 = n(t, "trip_km"), odo0 = n(t, "odometer_km"), minutes0 = n(t, "trip_minutes"), battery0 = n(t, "battery_percent");
        s.setScene(ScooterSimulation.Scene.RIDING);
        double top = 0, distance = 0, lastOdo = odo0, lastSpeed = 0;
        boolean pulled = false, braked = false, stood = false, regenSeen = false, drawSeen = false;
        for (int step = 1; step <= 4 * 120; step++) {
            long ms = 1000 + step * 250L;
            s.tick(t, ms);
            String where = e + " riding at " + ms + " ms";
            double speed = n(t, "speed_kmh"), loop = ((ms - 1000) / 1000.0) % ScooterSimulation.LOOP_S;
            consistentElectrics(t, where); signRule(t, signed, where);
            check(speed >= 0 && speed <= 30, where + ": a believable speed " + speed);
            check(n(t, "driving_status") == (speed > .05 ? 1 : 0), where + ": moving status agrees with speed");
            check(n(t, "charging_state") == 0, where + ": not charging");
            if (loop > 0 && loop < ScooterSimulation.ACCELERATE_S) { pulled |= speed > lastSpeed; drawSeen |= n(t, "power_w") > 100; }
            if (loop > ScooterSimulation.CRUISE_END_S + .5 && loop < ScooterSimulation.BRAKE_END_S - .5) {
                braked |= speed < lastSpeed;
                if (signed) { check(n(t, "power_w") < 0 && n(t, "current_a") < 0, where + ": regeneration reads negative on a signed model"); regenSeen = true; }
            }
            if (loop > ScooterSimulation.BRAKE_END_S + .5) { stood |= speed == 0; check(speed == 0, where + ": standing at the end of the loop"); }
            double odo = n(t, "odometer_km");
            check(odo >= lastOdo, where + ": the odometer never runs back");
            distance += (speed + lastSpeed) / 2 * .25 / 3600; lastOdo = odo; lastSpeed = speed; top = Math.max(top, speed);
        }
        check(pulled && braked && stood && drawSeen && (regenSeen || !signed), e + ": the loop rises, cruises, slows and stops");
        check(top > 20 && top < 30, e + ": Sport cruises in the twenties: " + top);
        double ridden = n(t, "odometer_km") - odo0;
        check(Math.abs(ridden - distance) < .01 && Math.abs((n(t, "trip_km") - trip0) - ridden) < 1e-6,
                e + ": trip and odometer advance by the distance the speed covers: " + ridden + " vs " + distance);
        check(Math.abs(n(t, "trip_minutes") - minutes0 - 2) < .02, e + ": two minutes of ride time");
        check(n(t, "battery_percent") <= battery0 && n(t, "battery_percent") >= battery0 - 2, e + ": the battery falls slowly");
        check(n(t, "maximum_speed_kmh") >= top - .05, e + ": the trip's top speed has seen the ride");
        check(n(t, "average_speed_kmh") > 0 && n(t, "average_speed_kmh") <= n(t, "maximum_speed_kmh"), e + ": an average under the top");
        // Locking stops the ride: the lock keeps working, and a locked scooter stands.
        s.set("lock", 1); s.tick(t, 200000); s.tick(t, 205000);
        check(n(t, "speed_kmh") == 0 && n(t, "driving_status") == 0 && n(t, "lock_status") == 1, e + ": a locked scooter stands");
        double odoLocked = n(t, "odometer_km"); s.tick(t, 215000);
        check(n(t, "odometer_km") == odoLocked, e + ": and rides no distance");
    }

    private static void charging(SimulatorModels.Entry e) {
        ScooterSimulation s = simulation(e); Telemetry t = new Telemetry();
        boolean[] f = SimulatorModels.facts(e.descriptor());
        s.tick(t, 1000);
        double start = s.battery();
        s.setScene(ScooterSimulation.Scene.CHARGING);
        for (int second = 2; second <= 600; second++) {
            s.tick(t, second * 1000L);
            String where = e + " charging at " + second + " s";
            consistentElectrics(t, where); signRule(t, f[1], where);
            check(n(t, "charging_state") == 1 && n(t, "speed_kmh") == 0, where + ": plugged in and standing");
            if (f[1]) check(n(t, "current_a") < 0 && n(t, "power_w") < 0 && n(t, "current_sign_state") == 1, where + ": charging reads negative with sign state 1");
            check(n(t, "voltage_v") > ScooterSimulation.restVolts(s.battery()), where + ": the charger lifts the voltage above rest");
        }
        double expected = s.chargeAmps() * 599 / 3600 / ScooterSimulation.RATED_AH * 100;
        check(Math.abs(s.battery() - start - expected) < .05, e + ": the battery rises at the charger's rate: " + (s.battery() - start) + " vs " + expected);
        check(f[0] ? n(t, "charge_limit_percent") == 100 : t.number("charge_limit_percent") == null,
                e + ": a charge limit reading only on a model with a native limit");
    }

    private static void chargeLimitAndChargers() {
        ScooterSimulation ut5 = simulation(SimulatorModels.find("", null)); Telemetry t = new Telemetry();
        ut5.set("charge_limit", 80);
        ut5.setScene(ScooterSimulation.Scene.CHARGING);
        ut5.setFastCharger(true);
        ut5.tick(t, 1000);
        check(ut5.chargeAmps() > 8 && ut5.chargeAmps() < 10.09, "UT5 MAX high-speed charger: 10.09 A tapering from 72 %, at 78 %: " + ut5.chargeAmps());
        long ms = 1000;
        while (ms < 3_600_000 && n(t, "charging_state") == 1) { ms += 5000; ut5.tick(t, ms); }
        check(n(t, "charging_state") == 0 && n(t, "battery_percent") == 80 && n(t, "current_a") == 0,
                "the UT5 MAX stops at its charge limit: " + n(t, "battery_percent") + " % at " + ms + " ms");
        ut5.tick(t, ms + 600000);
        check(n(t, "battery_percent") == 80, "and stays there");
        // Review finding (P3): a limit lowered below the level stops the charge and leaves the level where it is.
        ScooterSimulation above = simulation(SimulatorModels.find("", null)); t = new Telemetry();
        above.setScene(ScooterSimulation.Scene.CHARGING); above.setFastCharger(true);
        for (ms = 0; above.battery() < 86; ms += 5000) above.tick(t, ms);
        double reached = above.battery();
        above.set("charge_limit", 80);
        for (int i = 1; i <= 120; i++) above.tick(t, ms + i * 5000L);
        check(above.battery() == reached && n(t, "battery_percent") == Math.floor(reached) && n(t, "charging_state") == 0 && n(t, "current_a") == 0,
                "a limit lowered to 80 % at " + reached + " % stops the charge and keeps the level: " + above.battery());
        ScooterSimulation stock = simulation(SimulatorModels.find("", null));
        stock.setScene(ScooterSimulation.Scene.CHARGING); stock.tick(t = new Telemetry(), 1000);
        check(Math.abs(stock.chargeAmps() - 2.3) < 1e-9, "UT5 MAX stock charger: about 2.3 A");
        // Review round 3: Automatic finds the charger the Charging scene has plugged in (charger detection never runs while
        // simulating), only while charging, and only on a model whose chargers Volt tells apart.
        ScooterSimulation plugged = simulation(SimulatorModels.find("", null));
        check(plugged.detectedFastCharger() == null, "Parked: nothing for Automatic to find");
        plugged.setScene(ScooterSimulation.Scene.CHARGING);
        check(Boolean.FALSE.equals(plugged.detectedFastCharger()), "UT5 MAX charging on the stock charger: Automatic finds Stock");
        plugged.setFastCharger(true);
        check(Boolean.TRUE.equals(plugged.detectedFastCharger()), "on the high-speed charger: Automatic finds High-speed");
        plugged.setScene(ScooterSimulation.Scene.RIDING);
        check(plugged.detectedFastCharger() == null, "unplugged: nothing again");
        ScooterSimulation gt3 = simulation(SimulatorModels.find("24182", "US"));
        gt3.setScene(ScooterSimulation.Scene.CHARGING); gt3.setFastCharger(true);
        check(gt3.detectedFastCharger() == null, "a model with one charger family: Automatic finds nothing, as on a real one");
        gt3.setScene(ScooterSimulation.Scene.PARKED);
        gt3.set("charge_limit", 80);
        gt3.setFastCharger(true);
        check(!gt3.fastCharger(), "a model without the UT5 MAX references has one charger");
        gt3.setScene(ScooterSimulation.Scene.CHARGING); t = new Telemetry(); ms = 0; gt3.tick(t, 0);
        while (ms < 8 * 3_600_000L && n(t, "charging_state") == 1) { ms += 5000; gt3.tick(t, ms); }
        check(gt3.battery() == 100 && t.number("charge_limit_percent") == null,
                "the GT3 Max has no native limit: it charges past 80 % to full (charge alerts are its tool)");
        check(n(t, "charging_state") == 0 && n(t, "current_a") == 0 && n(t, "battery_percent") == 100, "full: the charger stops");
    }

    private static void driveModes() {
        check(Arrays.equals(ScooterSimulation.genericDriveModes(), new int[]{11, 2, 3}), "the generic codes: Walk, the low mode and Sport");
        // Each model's own named codes (Telemetry.drivingModeLabel), the generic three where Volt names none.
        check(Arrays.equals(SimulatorModels.driveModes(SimulatorModels.find("", null).descriptor()), new int[]{11, 2, 3}), "UT5 MAX: WALK, ECO, SPORT");
        check(Arrays.equals(SimulatorModels.driveModes(SimulatorModels.find("23452", "US").descriptor()), new int[]{11, 2, 3}), "ST3 Pro: exactly 11, 2 and 3");
        for (String gt5Family : new String[]{"25462", "22131", "24162", "24182"})
            check(Arrays.equals(SimulatorModels.driveModes(SimulatorModels.find(gt5Family, "US").descriptor()), new int[]{11, 2, 3, 5}), gt5Family + ": the GT5 Max table, Sport 5 too");
        check(Arrays.equals(SimulatorModels.driveModes(SimulatorModels.find(SimulatorModels.UNKNOWN, null).descriptor()), new int[]{11, 2, 3}), "an unknown model: the generic three");
        for (SimulatorModels.Entry e : SimulatorModels.entries()) {
            int product = ModelRegistry.driveModeLabelProduct(e.descriptor());
            int[] modes = SimulatorModels.driveModes(e.descriptor());
            check(modes.length >= 3 && modes[0] == 11, e + ": Walk first, at least three modes");
            if (product != 0) for (int code = 0; code <= 255; code++) {
                boolean offered = false; for (int m : modes) offered |= m == code;
                check(offered == !Telemetry.rawReading(null, Telemetry.drivingModeLabel(product, (double) code)), e + ": code " + code + " offered exactly when named");
            }
        }
        ScooterSimulation st3 = simulation(SimulatorModels.find("23452", "US"));
        st3.setDriveMode(3);
        boolean five = false;
        try { st3.setDriveMode(5); } catch (IllegalArgumentException expected) { five = true; }
        check(five && st3.driveMode() == 3, "the ST3 Pro refuses 5 (its Turbo acceleration value, not a drive mode)");
        ScooterSimulation gt5 = simulation(SimulatorModels.find("25462", "US")); Telemetry g = new Telemetry();
        gt5.setDriveMode(5); gt5.setScene(ScooterSimulation.Scene.RIDING);
        double sport5 = 0;
        for (int i = 0; i <= 120; i++) { gt5.tick(g, i * 500L); sport5 = Math.max(sport5, n(g, "speed_kmh")); }
        check(n(g, "driving_mode") == 5 && sport5 > 20, "GT5 Max Sport 5 rides as Sport: " + sport5);
        // A model change to one that doesn't name the current mode: its Sport.
        gt5.setDriveModes(SimulatorModels.driveModes(SimulatorModels.find("23452", "US").descriptor()));
        check(gt5.driveMode() == 3, "5 on a GT5 Max becomes the ST3 Pro's Sport, 3");
        ScooterSimulation s = simulation(SimulatorModels.find("25462", "US")); Telemetry t = new Telemetry();
        s.setScene(ScooterSimulation.Scene.RIDING);
        double top = 0;
        s.setDriveMode(ScooterSimulation.WALK);
        for (int i = 0; i <= 120; i++) { s.tick(t, i * 500L); top = Math.max(top, n(t, "speed_kmh")); }
        check(n(t, "driving_mode") == 11 && top <= 6.3 && top > 4, "Walk walks: " + top);
        s.setDriveMode(ScooterSimulation.LOW_MODE); s.tick(t, 61000);
        check(n(t, "driving_mode") == 2, "the reading follows a drive-mode change at once");
        boolean rejected = false;
        try { s.setDriveMode(7); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && s.driveMode() == 2, "an unoffered code is refused");
        ScooterSimulation capped = simulation(SimulatorModels.find("", null));
        capped.set("maximum_speed", ScooterCommands.find("maximum_speed").optionValues[0]);
        capped.setScene(ScooterSimulation.Scene.RIDING); top = 0; t = new Telemetry();
        for (int i = 0; i <= 120; i++) { capped.tick(t, i * 500L); top = Math.max(top, n(t, "speed_kmh")); }
        check(top < capped.featureValue("maximum_speed"), "the ride stays under the simulated top speed: " + top);
    }

    private static void switchesTakeEffectAtOnce() {
        ScooterSimulation s = simulation(SimulatorModels.find("", null)); Telemetry t = new Telemetry();
        s.setScene(ScooterSimulation.Scene.RIDING);
        for (int i = 0; i <= 80; i++) s.tick(t, i * 250L);
        check(n(t, "speed_kmh") > 10, "mid-ride");
        double odo = n(t, "odometer_km");
        s.setScene(ScooterSimulation.Scene.CHARGING); s.tick(t, 20250);
        check(n(t, "speed_kmh") == 0 && n(t, "driving_status") == 0 && n(t, "charging_state") == 1 && n(t, "current_a") < 0,
                "Riding to Charging: the next tick has no ride reading left");
        check(n(t, "odometer_km") == odo, "the odometer carries over (one scooter)");
        s.setScene(ScooterSimulation.Scene.PARKED); s.tick(t, 20500);
        check(n(t, "charging_state") == 0 && n(t, "current_a") > 0 && n(t, "speed_kmh") == 0, "Charging to Parked: unplugged at once");
        s.setScene(ScooterSimulation.Scene.RIDING); s.tick(t, 20750);
        check(n(t, "speed_kmh") < 1, "a new ride starts from a standstill");
        // A model switch mid-charge: the sign rule follows the new model on the next tick.
        s.setScene(ScooterSimulation.Scene.CHARGING); s.tick(t, 21000);
        check(n(t, "current_a") < 0 && n(t, "current_sign_state") == 1, "UT5 MAX charging is signed");
        boolean[] gt3 = SimulatorModels.facts(SimulatorModels.find("24182", "US").descriptor());
        s.setModel("24182"); s.setModelFacts(gt3[0], gt3[1], gt3[2]); s.tick(t, 21250);
        check(n(t, "current_a") > 0 && n(t, "current_sign_state") == 254 && t.number("charge_limit_percent") == null,
                "as a GT3 Max, the same charge reads unsigned with no limit reading");
        // The older charging switch and the riding-mode scripts keep working.
        ScooterSimulation old = new ScooterSimulation();
        old.setScript(ScooterSimulation.RIDING); old.tick(t = new Telemetry(), 1000);
        check(n(t, "speed_kmh") > 0, "a riding-mode script still rides in Parked");
        old.setScene(ScooterSimulation.Scene.CHARGING);
        check(old.script().isEmpty() && old.charging(), "choosing a scene ends a script");
        old.setCharging(false);
        check(old.scene() == ScooterSimulation.Scene.PARKED, "the older switch leaves Charging for Parked");
        old.setScene(ScooterSimulation.Scene.RIDING); old.setCharging(false);
        check(old.scene() == ScooterSimulation.Scene.RIDING, "and leaves another scene alone");
        check(ScooterSimulation.Scene.of("charging") == ScooterSimulation.Scene.CHARGING && ScooterSimulation.Scene.of("nonsense") == ScooterSimulation.Scene.PARKED
                && ScooterSimulation.Scene.of(null) == ScooterSimulation.Scene.PARKED, "scene ids");
    }

    private static void historyShapes() {
        ScooterSimulation s = simulation(SimulatorModels.find("", null)); Telemetry t = new Telemetry();
        s.tick(t, 1000);
        check(s.historyValue("battery_percent", 78, 0, 120) == 86 && s.historyValue("odometer_km", 248.36, 1, 0) == 248.36,
                "Parked keeps the first simulator's chart shapes");
        s.setScene(ScooterSimulation.Scene.CHARGING); s.tick(t, 2000);
        check(s.historyValue("battery_percent", 78, 0, 3600) < 78 && s.historyValue("battery_percent", 78, 1, 0) == 78,
                "Charging: the battery climbed to now");
        s.setScene(ScooterSimulation.Scene.RIDING);
        for (int i = 0; i <= 120; i++) s.tick(t, 2000 + i * 250L);
        check(s.historyValue("battery_percent", 70, 0, 3600) > 70 && s.historyValue("odometer_km", 250, 0, 3600) < 250,
                "Riding: the battery fell and the distance grew to now");
        boolean moving = false, standing = false;
        for (int back = 0; back < 60; back++) { double v = s.historyValue("speed_kmh", 0, 0, back); moving |= v > 10; standing |= v == 0; }
        check(moving && standing, "the speed chart replays the loop");
    }

    /** Writer: the maintenance odometer. A simulated ride's distance stays in the simulation scope and posts nothing. */
    private static void maintenanceDistanceStaysInMemory() {
        Map<String, String> stored = new HashMap<>();
        MaintenanceKeeper keeper = new MaintenanceKeeper(new MaintenanceKeeper.Storage() {
            public String read(String key) { return stored.get(key); }
            public void write(String key, String value) { stored.put(key, value); }
            public void remove(String key) { stored.remove(key); }
        });
        String scope = MaintenanceKeeper.simulationScope("p1");
        long wall = 1_800_000_000_000L;
        keeper.update(scope, s -> { s.on = true; MaintenancePolicy.add(s.reminders, MaintenancePolicy.create("brakes", "", "Check brakes", 1.0, 0, 0, true, wall, 0, wall)); });
        ScooterSimulation sim = simulation(SimulatorModels.find("", null)); Telemetry t = new Telemetry();
        sim.setScene(ScooterSimulation.Scene.RIDING);
        List<String> posted = new ArrayList<>();
        MaintenanceNotify.Poster shade = new MaintenanceNotify.Poster() {
            public void post(String sc, List<Reminder> due) { posted.add(sc); }
            public void cancel(String sc) {}
        };
        for (int i = 0; i <= 20 * 60; i++) {
            sim.tick(t, i * 1000L);
            keeper.observe(scope, n(t, "odometer_km"), MaintenanceDistance.BITS_32, wall + i * 1000L);
            // ScooterService.checkMaintenance: a simulation scope is checked quietly.
            MaintenanceNotify.check(keeper, scope, wall + i * 1000L, ZoneId.of("UTC"), MaintenanceKeeper.simulation(scope), () -> true, shade);
        }
        check(n(t, "odometer_km") > 248.36 + 1, "twenty minutes of riding passed the 1 km reminder");
        check(stored.isEmpty(), "nothing reached app_settings: " + stored.keySet());
        check(posted.isEmpty(), "no maintenance notice from a simulated ride");
    }

    /** Writer: auto-record and auto riding mode. Simulation is refused by the policy whatever the scene shows. */
    private static void autoStartIgnoresSimulatedMotion() {
        AutoStartPolicy p = new AutoStartPolicy();
        check(!p.shouldRecord(true, true, false, false, true, false, true), "a simulated ride never starts a recording");
        // BleController keeps speedRaw at 0 and the motion sample at its tick while simulating; the scene's speed is display only.
        check(!AutoStartPolicy.moving(true, 0, 1000, 1000), "the simulator's raw speed is a standstill");
    }

    /**
     * RangeFollowPolicy's per-profile memory (review round 3): the simulator's attempts are kept under their own key and a
     * simulation toggle forgets only those, so a real scooter's rejected or unconfirmed attempt is never repeated after
     * simulation ends, and neither side's attempt carries into the other.
     */
    private static void rangeFollowForgetsAcrossToggles() {
        int[] offered = {2, 3, 4};
        String real = "p1", simulated = RangeFollowPolicy.simulationKey(real);
        check(!simulated.equals(real), "the simulator's attempts have a key of their own");
        RangeFollowPolicy policy = new RangeFollowPolicy();
        check(follow(policy, real) != null, "the real scooter tried a range mode (rejected or never confirmed)");
        check(follow(policy, real) == null, "once per mode");
        policy.forgetSimulation(); // simulation on
        check(follow(policy, simulated) != null, "the real scooter's attempt doesn't carry into the simulation");
        check(follow(policy, simulated) == null, "the simulator tries once too");
        policy.forgetSimulation(); // simulation off
        for (int reconnect = 0; reconnect < 3; reconnect++)
            check(follow(policy, real) == null, "the real scooter's attempt survives simulation on and off: no repeated write");
        policy.forgetSimulation(); // on again
        check(follow(policy, simulated) != null, "the last simulation's attempt is forgotten by the toggle");
        // The other direction: an attempt made only while simulating never holds back the real scooter's first one.
        RangeFollowPolicy fresh = new RangeFollowPolicy();
        check(follow(fresh, simulated) != null, "the simulator tried first");
        fresh.forgetSimulation();
        check(follow(fresh, real) != null, "the simulator's attempt doesn't carry into the real scooter");
        // ScooterService wiring: the toggle forgets the simulator's attempts only, and simulated attempts use their key.
        String service = source("src/com/jjricks/volt/ScooterService.java");
        String toggle = service.substring(service.indexOf("public boolean setSimulation(boolean enabled)"), service.indexOf("public boolean isChargeRecording()"));
        check(toggle.contains("rangeFollowPolicy.forgetSimulation();") && !toggle.contains("rangeFollowPolicy.reset()"),
                "setSimulation forgets only the simulator's range-following attempts");
        String follow = service.substring(service.indexOf("private void followRangeMode()"));
        follow = follow.substring(0, follow.indexOf("\n    }\n"));
        check(follow.contains("simulating() ? RangeFollowPolicy.simulationKey(profile.id) : profile.id"),
                "followRangeMode keeps simulated attempts under the simulator's key");
    }
    private static Integer follow(RangeFollowPolicy policy, String key) {
        return policy.next(key, 2538, 3.0, new int[]{2, 3, 4}, 2, true, true, false, true, true);
    }
    private static String source(String path) {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError("read " + path, e); }
    }
}
