package com.jjricks.volt;

import android.content.SharedPreferences;

import java.util.*;

/** Per-scooter riding storage, its backup rules, and the OLED burn-in drift. */
public final class RidingConfigTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    /** Map-backed preferences for the test-only SharedPreferences surface. */
    private static final class Prefs implements SharedPreferences {
        final Map<String, Object> map = new TreeMap<>();
        @Override public boolean getBoolean(String key, boolean fallback) { Object v = map.get(key); return v instanceof Boolean ? (Boolean) v : fallback; }
        @Override public String getString(String key, String fallback) { Object v = map.get(key); return v instanceof String ? (String) v : fallback; }
        @Override public Editor edit() {
            Map<String, Object> pending = new LinkedHashMap<>();
            Set<String> removed = new HashSet<>();
            return new Editor() {
                @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public Editor remove(String key) { removed.add(key); return this; }
                @Override public Editor clear() { map.clear(); return this; }
                @Override public boolean commit() { for (String k : removed) map.remove(k); map.putAll(pending); return true; }
            };
        }
    }

    public static void main(String[] args) {
        storage();
        simulatedScale();
        backup();
        burnIn();
        System.out.println("RidingConfigTest: " + checks + " checks passed (per-scooter layout and scale, simulated scale never saved, backup rules, burn-in drift)");
    }

    private static void storage() {
        Prefs prefs = new Prefs();
        RidingConfig a = new RidingConfig(prefs, "abc-1"), b = new RidingConfig(prefs, "xyz-2");
        check(a.layout().isDefault("focus") && "focus".equals(a.layout().frame()), "no saved layout gives the defaults");
        RidingLayout layout = a.layout();
        layout.setFrame("halo");
        layout.setContent("halo", "highlight", RidingLayout.RANGE_VOLT);
        a.save(layout);
        check(prefs.map.containsKey("riding_layout_v1_abc-1"), "stored under riding_layout_v1_<profile>");
        check("halo".equals(a.layout().frame()) && RidingLayout.RANGE_VOLT.equals(a.layout().content("halo", "highlight")), "the layout reads back");
        check("focus".equals(b.layout().frame()), "each scooter has its own layout");
        prefs.map.put("riding_layout_v1_xyz-2", "{broken");
        check(b.layout().isDefault("focus"), "a damaged layout shows the defaults");

        RidingValues.PowerScale scale = a.powerScale();
        check(scale.drawW == 1000 && scale.regenW == 500, "no saved scale starts at 1,000 W and 500 W");
        scale.observe(1420, RidingValues.Direction.DRAW);
        a.save(scale);
        check("{\"draw\":1500,\"regen\":500}".equals(prefs.map.get("riding_power_scale_v1_abc-1")), "stored under riding_power_scale_v1_<profile>: " + prefs.map.get("riding_power_scale_v1_abc-1"));
        check(a.powerScale().drawW == 1500, "the scale reads back for the next ride");
        check(b.powerScale().drawW == 1000, "each scooter has its own scale");
        for (String bad : new String[]{"x", "[]", "{\"draw\":\"big\"}", "{\"draw\":-5,\"regen\":1e9}"}) {
            prefs.map.put("riding_power_scale_v1_xyz-2", bad);
            RidingValues.PowerScale s = b.powerScale();
            check(s.drawW >= 1000 && s.drawW <= RidingValues.PowerScale.DRAW_CAP_W && s.regenW >= 500 && s.regenW <= RidingValues.PowerScale.REGEN_CAP_W, "a damaged scale is safe: " + bad);
        }
    }

    /**
     * Simulation never changes the real scooter's saved data (#431 review): the scale a simulated high-speed charge grows
     * shows on screen but is never saved, and is gone once simulation ends. The steps are RidingScreen.refresh()'s.
     */
    private static void simulatedScale() {
        Prefs prefs = new Prefs();
        RidingConfig config = new RidingConfig(prefs, "ut5-1");
        config.save(new RidingValues.PowerScale(1250, 500));
        String saved = prefs.map.get("riding_power_scale_v1_ut5-1").toString();
        RidingValues values = new RidingValues(config.powerScale());
        values.setNominalIntervals(1000, 2000);
        long[] t = {0};
        // Real readings first: a 1,400 W pull grows the draw scale, and that is saved.
        refresh(values, config, false, () -> { values.sample("current_sign_state", 0, 0x79, t[0]); values.sample("power_w", 1400, 0x72, t[0]); });
        check("{\"draw\":1500,\"regen\":500}".equals(prefs.map.get("riding_power_scale_v1_ut5-1")), "a real reading's scale is saved: " + prefs.map.get("riding_power_scale_v1_ut5-1"));
        saved = prefs.map.get("riding_power_scale_v1_ut5-1").toString();
        // Simulation on: the UT5 MAX Charging scene with the high-speed charger, below 74 %: about -700 W, signed.
        for (int i = 0; i < 5; i++) {
            t[0] += 2000;
            refresh(values, config, true, () -> { values.sample("current_sign_state", 1, 0x79, t[0]); values.sample("power_w", -705, 0x72, t[0]); });
        }
        check(values.scale().regenW == 750 && values.scale().drawW == 1500, "the simulated scene grows the scale it shows: " + values.scale().regenW + " / " + values.scale().drawW);
        check(saved.equals(prefs.map.get("riding_power_scale_v1_ut5-1")), "but nothing it grew is saved: " + prefs.map.get("riding_power_scale_v1_ut5-1"));
        // Simulation off: the screen shows the real saved scale again, and a later real reading never saves the simulated growth.
        t[0] += 2000;
        refresh(values, config, false, () -> { values.sample("current_sign_state", 0, 0x79, t[0]); values.sample("power_w", 300, 0x72, t[0]); });
        check(values.scale().drawW == 1500 && values.scale().regenW == 500, "after simulation the scale is the saved one: " + values.scale().drawW + " / " + values.scale().regenW);
        check(saved.equals(prefs.map.get("riding_power_scale_v1_ut5-1")), "and stays saved as it was");
        t[0] += 2000;
        refresh(values, config, false, () -> { values.sample("current_sign_state", 1, 0x79, t[0]); values.sample("power_w", -600, 0x72, t[0]); });
        check("{\"draw\":1500,\"regen\":750}".equals(prefs.map.get("riding_power_scale_v1_ut5-1")), "a real regen after simulation grows and saves from the real scale only: " + prefs.map.get("riding_power_scale_v1_ut5-1"));
        // A riding mode opened while simulating (the scale read from the saved one) never saves either.
        RidingValues opened = new RidingValues(config.powerScale());
        saved = prefs.map.get("riding_power_scale_v1_ut5-1").toString();
        refresh(opened, config, true, () -> { opened.sample("current_sign_state", 0, 0x79, 50_000); opened.sample("power_w", 3100, 0x72, 50_000); });
        check(opened.scale().drawW == 3250 && saved.equals(prefs.map.get("riding_power_scale_v1_ut5-1")), "a riding mode opened in simulation saves nothing it grows");
        // RidingScreen.refresh() follows the simulator before the feed and saves only after it.
        String screen;
        try { screen = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/com/jjricks/volt/RidingScreen.java")), java.nio.charset.StandardCharsets.UTF_8); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        int follow = screen.indexOf("session.values.followSimulation(service.simulating(), config::powerScale);"), feed = screen.indexOf("session.feed(ble.telemetry.metrics()"),
                save = screen.indexOf("if (session.values.takeScaleChanged()) config.save(session.values.scale());");
        check(follow > 0 && follow < feed && feed < save, "RidingScreen follows the simulator, then feeds, then saves");
        check(screen.split("values\\.scale\\(\\)\\)", -1).length == 2, "and saves the scale nowhere else");
    }

    /** One RidingScreen.refresh(): follow the simulator, take the readings, save a grown scale. */
    private static void refresh(RidingValues values, RidingConfig config, boolean simulating, Runnable readings) {
        values.followSimulation(simulating, config::powerScale);
        readings.run();
        if (values.takeScaleChanged()) config.save(values.scale());
    }

    private static void backup() {
        for (String key : new String[]{"riding_layout_v1_abc-1", "riding_power_scale_v1_abc-1"}) {
            BackupRegistry.Rule rule = BackupRegistry.rule("app_settings", key);
            check(rule != null && BackupRegistry.DASHBOARDS.equals(rule.category) && rule.perProfile && rule.type == String.class, "riding storage travels with Dashboards, per scooter: " + key);
            check("abc-1".equals(rule.profile(key)) && rule.rename(key, "new").equals(key.replace("abc-1", "new")), "it remaps onto another scooter: " + key);
        }
        check(BackupRegistry.rule("app_settings", "riding_layout_v1_") == null, "a bare prefix is not a key");
        // The source scan in BackupRegistryTest sees RidingConfig's keys through its constants.
        Map<String, String> files = new TreeMap<>();
        files.put("RidingConfig.java", "final class RidingConfig{static final String LAYOUT_PREFIX = \"riding_layout_v1_\", SCALE_PREFIX = \"riding_power_scale_v1_\";"
                + "void a(android.content.SharedPreferences p,String id){p.getString(LAYOUT_PREFIX + id,null);p.getString(SCALE_PREFIX + id,null);}}");
        check(BackupRegistryTest.scan(files).isEmpty(), "the registry scan finds the riding keys classified: " + BackupRegistryTest.scan(files));
        files.put("RidingConfig.java", files.get("RidingConfig.java").replace("riding_layout_v1_", "riding_layout_v9_"));
        check(!BackupRegistryTest.scan(files).isEmpty(), "and would fail on an unregistered riding key");
    }

    private static void burnIn() {
        float[] start = RidingBurnIn.offsetDp(0);
        check(start[0] == 0 && start[1] == 0, "the drift starts at the centre");
        double maxX = 0, maxY = 0, fastest = 0;
        float[] previous = start;
        Set<String> cells = new HashSet<>();
        for (long t = 1000; t <= 60 * 60_000L; t += 1000) {
            float[] o = RidingBurnIn.offsetDp(t);
            check(Math.abs(o[0]) <= RidingBurnIn.AMPLITUDE_DP + 1e-4 && Math.abs(o[1]) <= RidingBurnIn.AMPLITUDE_DP + 1e-4, "the drift stays inside the reserved margin");
            maxX = Math.max(maxX, Math.abs(o[0])); maxY = Math.max(maxY, Math.abs(o[1]));
            fastest = Math.max(fastest, Math.hypot(o[0] - previous[0], o[1] - previous[1]));
            cells.add(Math.round(o[0]) + "," + Math.round(o[1]));
            previous = o;
        }
        check(maxX > 3.9 && maxY > 3.9, "an hour's drift uses the whole amplitude, more than the plan's 1-2 px");
        check(fastest < 0.075, "at most about 0.07 dp a second, too slow to see: " + fastest);
        check(cells.size() >= 60, "the orbit covers the square instead of retracing a line: " + cells.size() + " dp cells");
        check(RidingBurnIn.chromeAlpha(0) == 1 && RidingBurnIn.chromeAlpha(RidingBurnIn.CHROME_FULL_MS) == 1, "chrome starts at full strength");
        check(RidingBurnIn.chromeAlpha(RidingBurnIn.CHROME_FULL_MS + RidingBurnIn.CHROME_RAMP_MS) == RidingBurnIn.CHROME_DIM
                && RidingBurnIn.chromeAlpha(3_600_000) == RidingBurnIn.CHROME_DIM, "and settles at the dim level");
        float last = 1;
        for (long t = RidingBurnIn.CHROME_FULL_MS; t <= RidingBurnIn.CHROME_FULL_MS + RidingBurnIn.CHROME_RAMP_MS; t += 1000) {
            float a = RidingBurnIn.chromeAlpha(t);
            check(a <= last && last - a < 0.01, "the dimming is a slow, even fade");
            last = a;
        }
    }
}
