package com.jjricks.volt;

import java.util.Arrays;
import java.util.List;

/** Home's default Setting tiles follow what the official app offers each model; a passing state never moves a tile. */
public final class ModelTilesTest {
    private static int assertions;
    public static void main(String[] args) {
        List<String> def = ModelTiles.DEFAULT;
        check(def.equals(Arrays.asList("feature:maximum_speed", "feature:timed_charging")), "The release default pair");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("25381")).equals(def), "The UT5 MAX keeps Top speed and Charge schedule");
        check(ModelTiles.settingTiles(ModelRegistryTest.both("Q2546ZZ9US7777777", "25462")).equals(Arrays.asList("feature:maximum_speed", "feature:cruise_control")),
                "A US GT5 Max keeps Top speed (offered, even with an unreadable value) and swaps scheduled charging for cruise control");
        // Version 17: Top speed follows the official app's region rule, so an EU (or unregioned) GT5 Max and the Spanish GT3 Max get other controls.
        List<String> cruiseEnergy = Arrays.asList("feature:cruise_control", "feature:energy_recovery");
        DeviceDescriptor gt5 = ModelRegistryTest.both("Q2546ZZ9EU7777777", "25462"), gt3 = ModelRegistryTest.both("Q2418ZZ9ES7777777", "24182");
        check(ModelTiles.settingTiles(gt5).equals(cruiseEnergy) && ModelTiles.settingTiles(ModelRegistryTest.advertised("25462")).equals(cruiseEnergy)
                && ModelTiles.settingTiles(gt3).equals(cruiseEnergy), "No Top speed tile where the official app shows no Top speed");
        // Their Homes may still hold the pick Volt made before: Home replaces exactly that untouched pick.
        List<String> before = Arrays.asList("feature:maximum_speed", "feature:cruise_control");
        check(before.equals(ModelTiles.previousSettingTiles(gt5)) && before.equals(ModelTiles.previousSettingTiles(gt3)),
                "The earlier GT5 Max and GT3 Max pick is recognised");
        check(ModelTiles.previousSettingTiles(ModelRegistryTest.both("Q2546ZZ9US7777777", "25462")) == null
                && ModelTiles.previousSettingTiles(ModelRegistryTest.serial("Q2538ZZ9US7777777")) == null
                && ModelTiles.previousSettingTiles(ModelRegistryTest.advertised("22131")) == null
                && ModelTiles.previousSettingTiles(DeviceDescriptor.simulated("25462", 0, "EU")) == null
                && ModelTiles.previousSettingTiles(null) == null, "Nothing to replace where the pick didn't change, or for the simulator");
        DeviceDescriptor ut5De = ModelRegistryTest.serial("Q2538ZZ9DE7777777");
        check(ModelTiles.settingTiles(ut5De).equals(Arrays.asList("feature:timed_charging", "feature:cruise_control")) && def.equals(ModelTiles.previousSettingTiles(ut5De)),
                "A DE UT5 MAX swaps Top speed for cruise control; its old pick was the release pair");
        check(ModelTiles.settingTiles(ModelRegistryTest.serial("Q2538ZZ9US7777777")).equals(def), "The US UT5 MAX keeps Top speed and Charge schedule");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("22131")).equals(Arrays.asList("feature:cruise_control", "feature:energy_recovery")),
                "The N65i gets two controls its official app offers");
        for (String pid : new String[]{"25381", "25462", "22131", "23452", "26191", "27011", "24431", "24182"})
            for (String area : new String[]{null, "US", "ES"}) {
                DeviceDescriptor d = area == null ? ModelRegistryTest.advertised(pid) : ModelRegistryTest.both("Q" + pid.substring(0, 4) + "ZZ9" + area + "7777777", pid);
                for (String id : ModelTiles.settingTiles(d)) {
                    String key = id.substring("feature:".length());
                    check(ModelTiles.offered(key, d) || ModelTiles.settingTiles(d) == def, "Model " + pid + " " + area + " is given only offered tiles: " + key);
                    check(!ScooterCommands.find(key).experimental && !key.equals("lock"), "No Lab control or lock tile: " + key);
                }
            }
        check(ModelTiles.settingTiles(null) == def && ModelTiles.settingTiles(DeviceDescriptor.of("", "", 0, DeviceDescriptor.Firmware.NONE, false, DeviceDescriptor.Transport.STANDARD, 0)) == def,
                "An unidentified scooter keeps the defaults until Volt knows its model");
        check(ModelTiles.settingTiles(DeviceDescriptor.simulated("25462", 0)) == def, "The simulator never rewrites a saved dashboard");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("39991")) == def, "A model offering none of them keeps the defaults rather than an empty card");
        check(ModelTiles.settingTiles(ModelRegistryTest.advertised("25462").withOwnerModel("UT5 Max")).equals(ModelTiles.settingTiles(ModelRegistryTest.advertised("25462")))
                && ModelTiles.settingTiles(ModelRegistryTest.advertised("23451").withOwnerModel("GT5 Max")).equals(ModelTiles.settingTiles(ModelRegistryTest.advertised("23451"))),
                "The owner's model answer never chooses controls");
        System.out.println("ModelTiles tests passed: " + assertions + " assertions");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
