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

    /** The UT5 MAX, the GT3 Max (no charge limit, unsigned current) and both GT5 Max regions. */
    private static List<SimulatorModels.Entry> threeModels() {
        List<SimulatorModels.Entry> out = new ArrayList<>();
        for (SimulatorModels.Entry e : SimulatorModels.entries())
            if (e.pid.isEmpty() || e.pid.equals("24182") || e.pid.equals("25462")) out.add(e);
        check(out.size() == 1 + 2 + 2, "UT5 MAX, two GT3 Max and two GT5 Max entries: " + out);
        return out;
    }

    private static void pickerListsEveryNamedModel() {
        List<SimulatorModels.Entry> entries = SimulatorModels.entries();
        check(entries.get(0).pid.isEmpty() && entries.get(0).label().equals("UT5 MAX") && entries.get(0).region == null,
                "the UT5 MAX comes first, with no region");
        SimulatorModels.Entry mapped = entries.get(entries.size() - 2), unknown = entries.get(entries.size() - 1);
        check(mapped.pid.equals("24431") && unknown.pid.equals("39991"), "the first simulator's mapped and unknown models stay, last");
        check(SimulatorModels.shortLabel(mapped).equals("Mapped") && SimulatorModels.shortLabel(unknown).equals("Unknown"),
                "their short row values stay");
        Set<String> names = new HashSet<>(), labels = new HashSet<>();
        String previous = "";
        for (SimulatorModels.Entry e : entries.subList(1, entries.size() - 2)) {
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
        check(SimulatorModels.find("", null) == entries.get(0) && SimulatorModels.find(null, "US") == entries.get(0),
                "the UT5 MAX is found with or without a region (it takes none)");
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

    private static ScooterSimulation simulation(SimulatorModels.Entry e) {
        ScooterSimulation s = new ScooterSimulation();
        s.setModel(e.pid);
        boolean[] f = SimulatorModels.facts(e.descriptor());
        s.setModelFacts(f[0], f[1], f[2]);
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
        ScooterSimulation stock = simulation(SimulatorModels.find("", null));
        stock.setScene(ScooterSimulation.Scene.CHARGING); stock.tick(t = new Telemetry(), 1000);
        check(Math.abs(stock.chargeAmps() - 2.3) < 1e-9, "UT5 MAX stock charger: about 2.3 A");
        ScooterSimulation gt3 = simulation(SimulatorModels.find("24182", "US"));
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
        int[] modes = ScooterSimulation.driveModes();
        check(Arrays.equals(modes, new int[]{11, 2, 3}), "Walk, the low mode and Sport");
        for (int mode : modes) {
            check(!Telemetry.drivingModeLabel(2538, (double) mode).matches("[0-9]+") && !Telemetry.drivingModeLabel(25462, (double) mode).matches("[0-9]+"),
                    "the UT5 MAX and the GT5 Max family name every offered code: " + mode);
        }
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

    /** RangeFollowPolicy's per-profile memory: ScooterService resets it on every simulation toggle. */
    private static void rangeFollowForgetsAcrossToggles() {
        RangeFollowPolicy policy = new RangeFollowPolicy();
        int[] offered = {2, 3, 4};
        Integer first = policy.next("p1", 2538, 3.0, offered, 2, true, true, false, true, true);
        check(first != null, "the real side tried a range mode");
        check(policy.next("p1", 2538, 3.0, offered, 2, true, true, false, true, true) == null, "once per mode");
        policy.reset();
        check(policy.next("p1", 2538, 3.0, offered, 2, true, true, false, true, true) != null, "after a toggle the other side starts fresh");
    }
}
