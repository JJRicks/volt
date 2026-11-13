package com.jjricks.volt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic, memory-only display samples. This class never builds scooter commands or frames. */
public final class ScooterSimulation {
    private final LinkedHashMap<String, Integer> features = new LinkedHashMap<>();
    private final LinkedHashMap<String, Sample> fixed = new LinkedHashMap<>();
    private long chargeStart;
    /** Synthetic advertised model ID; "" is the default UT5 MAX simulator. Never stored or exported. */
    private String model = "";
    /** Riding-mode screenshot scripts: memory only, display samples only, never a command. */
    static final String RIDING = "riding", STOPPED = "stopped", DROPOUT = "dropout", LOW_AMBER = "low_amber",
            LOW_RED = "low_red", WARNING = "warning", UNSIGNED_REGEN = "unsigned_regen";
    private String script = "";

    /**
     * Developer scenes (Developer options › Scene): what the simulated scooter is doing. Memory only, like
     * everything here. A riding-mode script above, when set, still wins over Parked (the screenshot validations).
     */
    public enum Scene {
        PARKED("parked"), RIDING("riding"), CHARGING("charging");
        public final String id;
        Scene(String id) { this.id = id; }
        /** The scene with this id; Parked for null or an unknown id. */
        public static Scene of(String id) { for (Scene s : values()) if (s.id.equals(id)) return s; return PARKED; }
    }
    private Scene scene = Scene.PARKED;
    /** The generic drive-mode codes: Walk, the low mode (Eco or Drive by model) and Sport, for a model with no named codes. */
    static final int WALK = 11, LOW_MODE = 2, SPORT = 3;
    private static final int[] DRIVE_MODES = {WALK, LOW_MODE, SPORT};
    /** The codes the simulated model names (SimulatorModels.driveModes); the generic three until the caller says. */
    private int[] driveModes = DRIVE_MODES.clone();
    private int driveMode = SPORT;
    /**
     * The model facts readings depend on, decided by the caller from the registry (BleController): whether the model has
     * a native charge limit, whether it signs its current (PowerSign: a GT3 Max reports 254 and an unsigned magnitude)
     * and whether the stock and high-speed chargers differ for it (the UT5 MAX references). The defaults are the UT5 MAX.
     */
    private boolean chargeLimitModel = true, signedCurrent = true, chargerFamilies = true, fastCharger;
    // The scenes' own state: carried from scene to scene so Parked after Riding shows the ride's battery, trip and odometer.
    private double battery = 78, trip = 4.26, tripMinutes = 18.5, odometer = 248.36, tripMax = 32, rideClock;
    /** Minutes spent moving this trip: the average speed counts these (18.4 km/h at the sample's 4.26 km). */
    private double movingMinutes = 4.26 / 18.4 * 60;
    private long lastTickMs = -1;
    /** The UT5 MAX pack (ModelRegistry.ratedWh: 716.04 Wh, 15.3 Ah at 46.8 V); one pack for every simulated model. */
    static final double RATED_AH = 15.3, NOMINAL_V = 46.8;
    /** Range the sample shows per battery percent (38.4 km at 78 %). */
    private static final double KM_PER_PERCENT = 38.4 / 78;
    /** One riding loop: pull away, cruise, slow down, stand. Seconds. */
    static final double LOOP_S = 60, ACCELERATE_S = 8, CRUISE_END_S = 38, BRAKE_END_S = 48;

    private static final class Sample {
        final String text;
        final Double number;
        Sample(String text, Double number) { this.text = text; this.number = number; }
    }

    public ScooterSimulation() {
        for (ScooterCommands.Feature feature : ScooterCommands.catalog())
            if (!feature.sensitive && feature.type != ScooterCommands.Type.TIME)
                features.put(feature.key, feature.optionValues.length == 0
                        ? feature.min : feature.optionValues[0]);
        for (ScooterCommands.Feature feature : ScooterCommands.oemOnly())
            features.put(feature.key, feature.optionValues[0]);
        choose("energy_recovery", 30);
        choose("acceleration", 3);
        choose("headlight", 1);
        choose("auto_sensor", 1);
        choose("traction_control", 1);
        choose("turn_sound", 1);
        choose("range_algorithm", 2);
        choose("start_speed", 3);
        choose("maximum_speed", 50);
        choose("dual_drive", 1);
        choose("charge_limit", 100);
        choose("lock_expiry", 3);
        choose("low_power", 1);
        choose("hill_start_assist", 1);
        choose("parking_assist", 1);
        choose("line_light", 1);
        choose("star_light", 1);
        choose("line_boost_color", 1);
        choose("star_boost_color", 1);

        sample("battery_percent", "78 %", 78);
        sample("battery_health_percent", "97 %", 97);
        sample("battery_status", "Normal (simulation)", 0);
        sample("charge_cycles", "42", 42);
        sample("energy_throughput_wh", "14280 Wh", 14280);
        sample("capacity_throughput_ah", "282.40 Ah", 282.4);
        sample("deep_discharge", "2", 2);
        sample("rated_capacity_ah", "15.30 Ah", RATED_AH);
        sample("health_adjusted_capacity_ah", "14.84 Ah", RATED_AH * .97);
        text("battery_manufactured", "Simulation · 2026-01-15");
        text("high_temperature_duration", "Simulation · 0 y 0 mo 0 d 2 h 15 min 0 s");
        sample("speed_kmh", "0.0 km/h", 0);
        sample("remaining_range_km", "38.4 km", 38.4);
        sample("trip_km", "4.26 km", 4.26);
        sample("trip_minutes", "18.5 min", 18.5);
        sample("maximum_speed_kmh", "32.0 km/h", 32);
        sample("average_speed_kmh", "18.4 km/h", 18.4);
        sample("odometer_km", "248.36 km", 248.36);
        sample("driving_status", "Stopped (simulation)", 0);
        text("stored_trips", "Simulation · 12 sample trips");
        sample("binding_status", "Simulation owner", 1);
        sample("driving_mode", "SPORT (simulation)", 3);
        sample("range_algorithm_mode", "Simulation choices", 2);
        sample("tyre_reminder", "No reminder (simulation)", 0);
        sample("night_mode", "Off (simulation)", 0);
        sample("voice_language", "English (simulation)", 1);
        sample("logo_light", "On (simulation)", 1);
        sample("daytime_light", "On (simulation)", 1);
        sample("speed_limit_eligibility", "Available in simulation", 1);
        sample("weather", "Simulation · clear", 0);
        sample("overspeed_alert", "Off (simulation)", 0);
        sample("warning_code", "No warning (simulation)", 0);
        sample("lock_push_warning", "No movement (simulation)", 0);
        sample("hide_e9", "Off (simulation)", 0);
        text("ride_report_format", "Simulation · no BLE report");
        text("scooter_serial", "SIMULATED SCOOTER");
        text("battery_serial_raw", "SIMULATED BATTERY");
        for (String key : new String[]{"meter_version", "controller_version", "battery_version",
                "screen_version", "uwb_version"}) text(key, "Simulation · 1.0");
    }

    private void choose(String key, int value) { features.put(key, value); }
    private void sample(String key, String text, double number) { fixed.put(key, new Sample(text, number)); }
    private void text(String key, String text) { fixed.put(key, new Sample(text, null)); }

    public Map<String, Integer> features() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    public Integer featureValue(String key) { return features.get(key); }

    public int[] featureChoices(String key) {
        try { return ScooterCommands.find(key).optionValues.clone(); }
        catch (IllegalArgumentException unknown) { return new int[0]; }
    }

    /** Validates the named catalog domain, then changes only this in-memory instance. */
    public void set(String key, int value) {
        ScooterCommands.Feature feature = ScooterCommands.find(key);
        if (feature.sensitive || feature.type == ScooterCommands.Type.TIME || !feature.allows(value))
            throw new IllegalArgumentException("Unavailable simulation setting");
        features.put(key, value);
    }

    /**
     * The same, validated against the decided model variant (another simulated model's domain);
     * a UT5 MAX variant keeps the catalogue check, plus its own narrower list where it has one.
     */
    public void set(String key, int value, FeatureVariant variant) {
        if (variant == null || variant.decode == FeatureVariant.Decode.UT5_FEATURE) {
            if (variant != null && variant.values.length > 0 && !variant.allows(value))
                throw new IllegalArgumentException("Unavailable simulation setting");
            set(key, value); return;
        }
        ScooterCommands.Feature feature = ScooterCommands.find(key);
        if (feature.sensitive || feature.type == ScooterCommands.Type.TIME || !variant.feature.equals(key) || !variant.allows(value))
            throw new IllegalArgumentException("Unavailable simulation setting");
        features.put(key, value);
    }

    public void setChargeStart(long seconds, int offset) {
        long local = seconds + (long) offset;
        if (seconds < 0 || local < 0 || local > 0xffffffffL || Math.abs((long) offset) > 86400)
            throw new IllegalArgumentException("Invalid simulation charging time");
        chargeStart = local;
    }

    /**
     * Developer model choice: the model ID only. The caller (BleController) then brings the settings to the model's own
     * choices with {@link #conform}, which needs the region too.
     */
    public void setModel(String advertisedPid) {
        String pid = advertisedPid == null ? "" : advertisedPid;
        if (!pid.isEmpty() && !pid.matches("[1-9][0-9]{0,4}"))
            throw new IllegalArgumentException("Invalid simulation model");
        model = pid;
    }
    public String model() { return model; }

    /** A riding-mode script id; "" or an unknown id gives the plain parked samples. */
    public void setScript(String id) {
        script = java.util.Arrays.asList(RIDING, STOPPED, DROPOUT, LOW_AMBER, LOW_RED, WARNING, UNSIGNED_REGEN).contains(id) ? id : "";
    }
    public String script() { return script; }

    public boolean charging() { return scene == Scene.CHARGING; }
    /** The older on/off charging switch: on is the Charging scene, off leaves Charging for Parked (a script keeps its place). */
    public void setCharging(boolean value) {
        if (value) changeScene(Scene.CHARGING);
        else if (scene == Scene.CHARGING) changeScene(Scene.PARKED);
    }

    public Scene scene() { return scene; }
    /** Switches at once: the next tick rewrites every scene reading, so no ride reading survives into Charging. Clears a script. */
    public void setScene(Scene next) {
        script = "";
        changeScene(next == null ? Scene.PARKED : next);
    }
    private void changeScene(Scene next) {
        if (next == scene) return;
        scene = next;
        // A ride starts from a standstill.
        rideClock = 0;
    }

    /** The generic drive-mode codes (Walk, the low mode, Sport), for a model whose codes Volt doesn't name. */
    public static int[] genericDriveModes() { return DRIVE_MODES.clone(); }
    /** The drive-mode codes this simulated model offers. */
    public int[] driveModes() { return driveModes.clone(); }
    /**
     * The model's own named codes (SimulatorModels.driveModes; the generic three when empty). A drive mode the new model
     * doesn't name becomes its Sport (3, which every table names), else its last code.
     */
    public void setDriveModes(int[] codes) {
        driveModes = codes == null || codes.length == 0 ? DRIVE_MODES.clone() : codes.clone();
        if (!offersDriveMode(driveMode)) driveMode = offersDriveMode(SPORT) ? SPORT : driveModes[driveModes.length - 1];
    }
    public boolean offersDriveMode(int code) { for (int mode : driveModes) if (mode == code) return true; return false; }
    public int driveMode() { return driveMode; }
    public void setDriveMode(int code) {
        if (!offersDriveMode(code)) throw new IllegalArgumentException("Unavailable simulation setting");
        driveMode = code;
    }

    /**
     * Brings every simulated setting to a value this model and region offer, as the screens read its choices: the
     * ordinary rule's variant (CapabilityResolver.decide), else the Experimental controls try rule's. A value still
     * offered is kept; another becomes the nearest offered value (the lower one on a tie). A control the model doesn't
     * have keeps its value, which no screen shows. Memory only, like everything here.
     */
    public void conform(DeviceDescriptor d) {
        for (Map.Entry<String, Integer> entry : features.entrySet()) {
            FeatureVariant v = offeredVariant(entry.getKey(), d);
            if (v == null || entry.getValue() != null && v.allows(entry.getValue())) continue;
            Integer next = nearest(v, entry.getValue());
            if (next != null) entry.setValue(next);
        }
    }
    /** The variant whose choices a screen offers for this control on this model, or null where it has none. */
    static FeatureVariant offeredVariant(String key, DeviceDescriptor d) {
        CapabilityResolver.Decision decision = CapabilityResolver.decide(key, d, StatusFields.NONE, null);
        if (decision.support == CapabilityResolver.Support.KNOWN_ENCODING && decision.variant != null) return decision.variant;
        decision = CapabilityResolver.decideTry(key, d, StatusFields.NONE, null);
        return decision.support == CapabilityResolver.Support.KNOWN_ENCODING ? decision.variant : null;
    }
    /** The offered value nearest to this one (a slider's range clamped and stepped), or null when none is offered. */
    private static Integer nearest(FeatureVariant v, Integer value) {
        int[] candidates = v.values;
        int min = v.min, max = v.max, step = Math.max(1, v.step);
        if (candidates.length == 0 && v.decode == FeatureVariant.Decode.UT5_FEATURE) {
            ScooterCommands.Feature f = ScooterCommands.find(v.feature);
            candidates = f.optionValues; min = f.min; max = f.max; step = Math.max(1, f.step);
        }
        int target = value == null ? Integer.MIN_VALUE : value;
        if (candidates.length > 0) {
            Integer best = null;
            for (int c : candidates) {
                if (!v.allows(c)) continue;
                if (best == null || value != null && Math.abs((long) c - target) < Math.abs((long) best - target)) best = c;
            }
            return best;
        }
        if (min > max) return null;
        long clamped = Math.max(min, Math.min(max, (long) target));
        int stepped = (int) (min + (clamped - min) / step * step);
        for (int c = stepped; c >= min; c -= step) if (v.allows(c)) return c;
        return null;
    }
    /**
     * A setting the simulated model doesn't offer, as a scooter changed elsewhere can report one, for the validations
     * that check how a screen shows such a value (Home's unreadable dash). Memory only; no screen calls it.
     */
    void setUnoffered(String key, int value) {
        if (!features.containsKey(key)) throw new IllegalArgumentException("Unavailable simulation setting");
        features.put(key, value);
    }

    /** The registry's facts for the simulated model (see the fields); BleController sets them with the model. */
    public void setModelFacts(boolean chargeLimit, boolean signed, boolean chargers) {
        chargeLimitModel = chargeLimit; signedCurrent = signed; chargerFamilies = chargers;
    }
    public boolean chargeLimitModel() { return chargeLimitModel; }
    public boolean signedCurrent() { return signedCurrent; }
    /** The charger plugged in for the Charging scene: the high-speed one, or the stock one. Only the UT5 MAX tells them apart. */
    public void setFastCharger(boolean fast) { fastCharger = fast; }
    public boolean fastCharger() { return fastCharger && chargerFamilies; }
    /** The scene's battery level, for tests. */
    double battery() { return battery; }

    /** Replaces every catalog reading with clearly synthetic source=-1 data; no wire bytes exist. */
    public void tick(Telemetry telemetry, long elapsedMs) {
        if (telemetry == null || elapsedMs < 0) throw new IllegalArgumentException("Invalid simulation clock");
        if (!telemetry.frames().isEmpty() || !telemetry.statuses().isEmpty())
            throw new IllegalArgumentException("Clear real telemetry before simulating");
        boolean scripted = !script.isEmpty() && scene == Scene.PARKED;
        if (!scripted) advance(elapsedMs); else lastTickMs = elapsedMs;
        for (Telemetry.Metric metric : telemetry.metrics()) {
            // A script's ride sets speed below at this same time: one reading per tick, as a real report has.
            if (scripted && metric.key.equals(RidingValues.SPEED_KEY)) continue;
            Sample value = fixed.get(metric.key);
            telemetry.putSimulationSample(metric.key,
                    value == null ? NO_RESPONSE : value.text,
                    value == null ? null : value.number, elapsedMs);
        }
        // Feature readback fields can be shared bit-packed status values. Compose
        // their documented masks for display, without constructing a BLE frame.
        LinkedHashMap<String, Integer> fields = new LinkedHashMap<>();
        for (ScooterCommands.Feature feature : ScooterCommands.catalog()) {
            Integer value = features.get(feature.key);
            if (value == null || feature.readbackKey.isEmpty()) continue;
            int previous = fields.containsKey(feature.readbackKey) ? fields.get(feature.readbackKey) : 0;
            fields.put(feature.readbackKey, (previous & ~feature.readbackMask)
                    | ((value << feature.readbackShift) & feature.readbackMask));
        }
        for (Map.Entry<String, Integer> field : fields.entrySet())
            telemetry.putSimulationSample(field.getKey(), field.getValue() + SIMULATION,
                    field.getValue().doubleValue(), elapsedMs);
        telemetry.putSimulationSample("lock_status", features.get("lock") == 0
                ? "Unlocked (simulation)" : "Locked (simulation)", features.get("lock").doubleValue(), elapsedMs);
        // A model without a native charge limit has no limit reading: it charges to full (ChargeAlertPolicy.offered).
        if (chargeLimitModel) telemetry.putSimulationSample("charge_limit_percent", features.get("charge_limit") + " %",
                features.get("charge_limit").doubleValue(), elapsedMs);
        else telemetry.putSimulationSample("charge_limit_percent", NO_RESPONSE, null, elapsedMs);
        telemetry.putSimulationSample("scheduled_charge_time_raw", chargeStart == 0
                ? "No schedule (simulation)" : chargeStart + SIMULATION, (double) chargeStart, elapsedMs);
        telemetry.putSimulationSample("temperature_sign_state", "0 (simulation)", 0.0, elapsedMs);
        double phase = elapsedMs / 1000.0;
        quantity(telemetry, "temperature_c", 23.5 + .5 * Math.sin(phase / 37), "°C", 1, elapsedMs);
        if (scripted) {
            // The riding-mode screenshot scripts keep their fixed readings (parked electrical samples beneath them).
            double volts = 50.4 + .08 * Math.sin(phase / 19), amps = .36 + .08 * Math.sin(phase / 7);
            quantity(telemetry, "voltage_v", volts, "V", 2, elapsedMs);
            quantity(telemetry, "current_a", amps, "A", 2, elapsedMs);
            quantity(telemetry, "power_w", volts * amps, "W", 1, elapsedMs);
            charging(telemetry, false, elapsedMs);
            signState(telemetry, false, elapsedMs);
            ride(telemetry, elapsedMs, phase);
            return;
        }
        scene(telemetry, elapsedMs, phase);
    }

    private static final String SIMULATION = " (simulation)";
    private static final String NO_RESPONSE = "Simulation · no real response";

    /** Moves the scene on by the time since the last tick (at most 5 s, so a paused screen doesn't jump). */
    private void advance(long elapsedMs) {
        double dt = lastTickMs < 0 ? 0 : Math.max(0, Math.min(5000, elapsedMs - lastTickMs)) / 1000.0;
        lastTickMs = elapsedMs;
        for (double left = dt; left > 1e-9; ) {
            double step = Math.min(.25, left); left -= step;
            if (scene == Scene.RIDING && !locked()) {
                double before = rideSpeed(rideClock);
                rideClock += step;
                double speed = rideSpeed(rideClock), km = (before + speed) / 2 * step / 3600;
                trip += km; odometer += km; tripMinutes += step / 60;
                if (speed > .05) movingMinutes += step / 60;
                tripMax = Math.max(tripMax, speed);
                battery = clampBattery(battery - ridePower(rideClock) * step / 3600 / (RATED_AH * NOMINAL_V) * 100);
            } else if (scene == Scene.CHARGING) {
                // Above a lowered limit the charger has stopped (0 A): the level stays where it is.
                double amps = chargeAmps();
                if (amps > 0) battery = Math.min(chargeTarget(), battery + amps * step / 3600 / RATED_AH * 100);
            }
        }
    }
    private static double clampBattery(double value) { return Math.max(3, Math.min(100, value)); }
    private boolean locked() { Integer lock = features.get("lock"); return lock != null && lock != 0; }

    /** Where charging stops: the charge limit on a model with one, else full. */
    private double chargeTarget() {
        Integer limit = features.get("charge_limit");
        return chargeLimitModel && limit != null && limit > 0 && limit <= 100 ? limit : 100;
    }
    /** Battery-side charging current at the scene's level: 0 at the target (the charger has stopped). */
    double chargeAmps() {
        if (battery >= chargeTarget() - 1e-9) return 0;
        double bulk, taperFrom, end;
        if (fastCharger()) { bulk = 10.09; taperFrom = 72; end = 2.0; }      // Ut5MaxFastChargeReference: ~10.09 A, taper from 72 %
        else if (chargerFamilies) { bulk = 2.3; taperFrom = 89; end = 1.0; } // Ut5MaxStockVoltageReference: ~2.3 A until 89 %
        else { bulk = 3.0; taperFrom = 85; end = .8; }
        if (battery <= taperFrom) return bulk;
        return Math.max(end, bulk - (bulk - end) * (battery - taperFrom) / (100 - taperFrom));
    }

    /** Pack voltage at rest for a battery level (50.4 V at the sample's 78 %; about 0.124 V per percent, as UT5 MAX charges show). */
    static double restVolts(double percent) { return 50.4 + .124 * (percent - 78); }

    /** The riding loop's cruise speed: by drive mode (Walk, the low mode, or a Sport code: 3, or 5 on the GT5 Max family), under the simulated top speed. */
    private double cruise() {
        double speed = driveMode == WALK ? 6 : driveMode == LOW_MODE ? 19 : 27;
        Integer top = features.get("maximum_speed");
        // The cruise wanders by up to 1.2 km/h: stay that far under the top speed.
        return top != null && top > 2 ? Math.min(speed, top - 1.5) : speed;
    }
    /** Speed (km/h) at a time into the loop: pull away, cruise with a little wander, slow down, stand. */
    double rideSpeed(double seconds) {
        double c = seconds % LOOP_S, cruise = cruise();
        if (c < ACCELERATE_S) return cruise * (1 - Math.cos(Math.PI * c / ACCELERATE_S)) / 2;
        if (c < CRUISE_END_S) return Math.max(0, cruise + (driveMode == WALK ? .2 : 1.2) * Math.sin(c * .9));
        if (c < BRAKE_END_S) return cruise * (1 + Math.cos(Math.PI * (c - CRUISE_END_S) / (BRAKE_END_S - CRUISE_END_S))) / 2;
        return 0;
    }
    /**
     * A chart point for the scene's story so far: the reading {@code secondsBefore} the latest one ({@code base}),
     * {@code fraction} of the way along the chart (0 the oldest point). Parked keeps the first simulator's gentle
     * shapes; Riding replays the loop and counts back distance and battery; Charging counts the battery back down.
     */
    public double historyValue(String key, double base, double fraction, double secondsBefore) {
        double hours = secondsBefore / 3600;
        if (scene == Scene.RIDING && !locked()) {
            double at = rideClock - secondsBefore, loop = ((at % LOOP_S) + LOOP_S) % LOOP_S;
            switch (key) {
                case "speed_kmh": return rideSpeed(loop);
                case "power_w": { double w = ridePower(loop); return signedCurrent ? w : Math.abs(w); }
                case "current_a": { double a = ridePower(loop) / restVolts(battery); return signedCurrent ? a : Math.abs(a); }
                // Battery and distance count back at the loop's average draw (about a third of its peak) and speed.
                case "battery_percent": return Math.min(100, base + 140 * hours / (RATED_AH * NOMINAL_V) * 100);
                case "odometer_km": return Math.max(0, base - .6 * cruise() * hours);
                case "trip_km": return Math.max(0, base - .6 * cruise() * hours);
                default: break;
            }
        } else if (scene == Scene.CHARGING) {
            double amps = Math.max(chargeAmps(), fastCharger() ? 10.09 : chargerFamilies ? 2.3 : 3.0);
            switch (key) {
                case "battery_percent": return Math.max(5, base - amps * hours / RATED_AH * 100);
                case "voltage_v": return Math.max(restVolts(5), base - .124 * amps * hours / RATED_AH * 100);
                case "current_a": case "power_w": return base;
                default: break;
            }
        }
        if (key.equals("power_w")) return base + 5 * Math.sin(fraction * 24);
        if (key.equals("voltage_v")) return base + .15 * Math.sin(fraction * 18);
        if (key.equals("temperature_c")) return base + .6 * Math.sin(fraction * 8);
        if (key.equals("battery_health_percent")) return Math.min(100, base + 1 - fraction);
        if (key.equals("battery_percent")) return Math.min(100, base + 8 * (1 - fraction));
        if (key.equals("odometer_km")) return Math.max(0, base - 3 * (1 - fraction));
        return base;
    }

    /** Signed power (W) at a time into the loop: draw pulling away and cruising, regeneration while slowing (with energy recovery on). */
    double ridePower(double seconds) {
        double c = seconds % LOOP_S, v = rideSpeed(seconds), walk = driveMode == WALK ? .25 : 1;
        if (c < ACCELERATE_S) return walk * (180 + 30 * v);
        if (c < CRUISE_END_S) return walk * (90 + 11 * v);
        if (c < BRAKE_END_S) {
            Integer recovery = features.get("energy_recovery");
            if (recovery == null || recovery <= 0) return 12;
            return -walk * (40 + 9 * v) * Math.max(.4, Math.min(1, recovery / 90.0));
        }
        return 18;
    }

    /** The scene's readings, written in one tick: every key any scene writes, so a switch leaves nothing behind. */
    private void scene(Telemetry telemetry, long time, double phase) {
        boolean riding = scene == Scene.RIDING && !locked(), charging = scene == Scene.CHARGING;
        double speed = riding ? rideSpeed(rideClock) : 0, volts, amps;
        if (riding) {
            double watts = ridePower(rideClock), rest = restVolts(battery);
            amps = watts / rest; volts = rest - .08 * amps;
        } else if (charging) {
            double charge = chargeAmps();
            volts = Math.min(54.6, restVolts(battery) + (charge > 0 ? .45 + .4 * charge : 0));
            amps = -charge;
        } else {
            // Parked: the sample's standby draw (about 0.36 A), as before.
            volts = restVolts(battery) + .08 * Math.sin(phase / 19);
            amps = .36 + .08 * Math.sin(phase / 7);
        }
        // A model that doesn't sign its current reports a magnitude only (PowerSign); its sign state reads 254.
        if (!signedCurrent) amps = Math.abs(amps);
        quantity(telemetry, "voltage_v", volts, "V", 2, time);
        quantity(telemetry, "current_a", amps, "A", 2, time);
        quantity(telemetry, "power_w", volts * amps, "W", 1, time);
        charging(telemetry, charging && chargeAmps() > 0, time);
        signState(telemetry, charging, time);
        reading(telemetry, "speed_kmh", speed, 1, Telemetry.SUFFIX_KMH, time);
        reading(telemetry, "battery_percent", Math.floor(battery), 0, Telemetry.SUFFIX_PERCENT, time);
        reading(telemetry, "remaining_range_km", Math.floor(battery) * KM_PER_PERCENT, 1, Telemetry.SUFFIX_RANGE, time);
        reading(telemetry, "trip_km", trip, 2, Telemetry.SUFFIX_KM, time);
        reading(telemetry, "trip_minutes", tripMinutes, 1, Telemetry.SUFFIX_MINUTE, time);
        reading(telemetry, "odometer_km", odometer, 2, Telemetry.SUFFIX_KM, time);
        reading(telemetry, "maximum_speed_kmh", tripMax, 1, Telemetry.SUFFIX_KMH, time);
        reading(telemetry, "average_speed_kmh", Math.min(tripMax, trip / (movingMinutes / 60)), 1, Telemetry.SUFFIX_KMH, time);
        int moving = speed > .05 ? 1 : 0;
        if (scene == Scene.RIDING) telemetry.putSimulationSample("driving_status", moving + SIMULATION, (double) moving, time);
        telemetry.putSimulationSample("driving_mode", driveMode + SIMULATION, (double) driveMode, time);
    }
    private static void charging(Telemetry telemetry, boolean on, long time) {
        telemetry.putSimulationSample("charging_state", on ? "Charging (simulation)" : "Not charging (simulation)", on ? 1.0 : 0.0, time);
    }
    private void signState(Telemetry telemetry, boolean charging, long time) {
        if (!signedCurrent) telemetry.putSimulationSample("current_sign_state", 254 + SIMULATION, 254.0, time);
        else telemetry.putSimulationSample("current_sign_state", charging ? "1 (simulation)" : "0 (simulation)", charging ? 1.0 : 0.0, time);
    }

    /**
     * A scooter that doesn't sign its current (a GT3 Max answers 0x79 with 254): always a positive
     * reading, with a repeating pull-away, cruise and brake where the voltage rises. Regen inference only.
     */
    private void unsignedRegenRide(Telemetry telemetry, long time, double phase) {
        double cycle = phase % 32, volts = 50.4, amps, speed;
        if (cycle < 6) { speed = 6 + 4 * cycle; amps = 16; volts -= 16 * .08; }
        else if (cycle < 14) { speed = 30; amps = .45; }
        else if (cycle < 20) { speed = 30 - 3.5 * (cycle - 14); amps = 7.5; volts += 7.5 * .06; }
        else { speed = 9 - .05 * (cycle - 20); amps = .35; }
        reading(telemetry, "voltage_v", volts, 3, Telemetry.SUFFIX_VOLT, time);
        reading(telemetry, "current_a", amps, 2, Telemetry.SUFFIX_AMP, time);
        reading(telemetry, "power_w", volts * amps, 1, Telemetry.SUFFIX_WATT, time);
        reading(telemetry, "speed_kmh", speed, 1, Telemetry.SUFFIX_KMH, time);
        telemetry.putSimulationSample("current_sign_state", "254 (simulation)", 254.0, time);
        reading(telemetry, "battery_percent", 72, 0, Telemetry.SUFFIX_PERCENT, time);
        reading(telemetry, "remaining_range_km", 18, 1, Telemetry.SUFFIX_RANGE, time);
        reading(telemetry, "trip_km", 2.4, 2, Telemetry.SUFFIX_KM, time);
        reading(telemetry, "trip_minutes", 9, 1, Telemetry.SUFFIX_MINUTE, time);
    }

    /** A ride for riding mode's screenshots: speed and power that swing into regen, a growing trip. */
    private void ride(Telemetry telemetry, long time, double phase) {
        if (script.equals(UNSIGNED_REGEN)) { unsignedRegenRide(telemetry, time, phase); return; }
        boolean moving = !script.equals(STOPPED);
        double speed = moving ? 24.3 + 5.5 * Math.sin(phase / 9) : 0;
        double watts = moving ? 540 + 720 * Math.sin(phase / 7) : 6;
        double battery = script.equals(LOW_RED) ? 11 : script.equals(LOW_AMBER) ? 24 : 72;
        // The trip grows at the ride's steady pace: multiplying the swinging speed by elapsed time would make it fall.
        double pace = moving ? 24.3 : 0;
        if (script.equals(LOW_RED) || script.equals(LOW_AMBER)) { speed *= .7; watts *= .7; pace *= .7; }
        reading(telemetry, "speed_kmh", speed, 1, Telemetry.SUFFIX_KMH, time);
        reading(telemetry, "power_w", watts, 1, Telemetry.SUFFIX_WATT, time);
        reading(telemetry, "current_a", watts / 50.4, 2, Telemetry.SUFFIX_AMP, time);
        reading(telemetry, "battery_percent", battery, 0, Telemetry.SUFFIX_PERCENT, time);
        reading(telemetry, "remaining_range_km", battery * .5, 1, Telemetry.SUFFIX_RANGE, time);
        reading(telemetry, "trip_km", 6.84 + phase * pace / 3600, 2, Telemetry.SUFFIX_KM, time);
        reading(telemetry, "trip_minutes", 23.5 + phase / 60, 1, Telemetry.SUFFIX_MINUTE, time);
        reading(telemetry, "temperature_c", 31.2, 1, Telemetry.SUFFIX_CELSIUS, time);
        // This trip's top and average speed, for Halo's ring tick and dot (riding-mode plan PR 3).
        reading(telemetry, "maximum_speed_kmh", 35.6, 1, Telemetry.SUFFIX_KMH, time);
        reading(telemetry, "average_speed_kmh", 21.4, 1, Telemetry.SUFFIX_KMH, time);
        if (script.equals(WARNING)) telemetry.putSimulationSample("warning_code", Telemetry.format(14, 0), 14.0, time);
    }

    private static void reading(Telemetry telemetry, String key, double number, int decimals, String suffix, long time) {
        telemetry.putSimulationSample(key, Telemetry.format(number, decimals) + suffix, number, time);
    }

    private static void quantity(Telemetry telemetry, String key, double number,
            String unit, int decimals, long time) {
        telemetry.putSimulationSample(key, String.format(Locale.US, "%." + decimals + "f %s", number, unit), number, time);
    }
}
