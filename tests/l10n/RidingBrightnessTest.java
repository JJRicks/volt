package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Riding mode's screen brightness (ride-display plan, PR 2): the Volt auto curve and its learning
 * (BrightnessCurve), the Phone / Volt auto / Fixed choice and slider (RidingBrightness), the rotation
 * setting (RidingRotation), and a scan that nothing in the app writes Android's brightness setting.
 * Listed in tests/l10n/riding-display.sources; tests/run.sh runs it from the repository root.
 */
public final class RidingBrightnessTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final float EPS = 1e-4f;

    public static void main(String[] args) throws IOException {
        curve();
        bands();
        corrections();
        resetAndRestore();
        smoother();
        modes();
        fixedMode();
        autoMode();
        storeRoundTrip();
        rotation();
        neverWritesSystemSetting();
        System.out.println("RidingBrightnessTest: " + checks + " checks passed");
    }

    private static void curve() {
        check(BrightnessCurve.base(0) == BrightnessCurve.FLOOR, "dark is the floor");
        check(BrightnessCurve.base(-5) == BrightnessCurve.FLOOR && BrightnessCurve.base(Float.NaN) == BrightnessCurve.FLOOR, "bad lux is the floor");
        check(Math.abs(BrightnessCurve.base(10) - 0.15f) < EPS, "10 lux is about 0.15");
        check(BrightnessCurve.base(10_000) == 1f && BrightnessCurve.base(100_000) == 1f, "10 000 lux and up is full");
        float last = 0;
        for (float lux = 0; lux < 30_000; lux = lux * 1.1f + 0.5f) {
            float level = BrightnessCurve.base(lux);
            check(level >= last - EPS, "the base curve never dims as light rises, at " + lux);
            check(level >= BrightnessCurve.FLOOR && level <= BrightnessCurve.CEILING, "the base curve stays in range");
            last = level;
        }
        check(BrightnessCurve.level(0) == BrightnessCurve.FLOOR && BrightnessCurve.level(BrightnessCurve.NOTCHES) == 1f, "notch ends");
        check(BrightnessCurve.level(-3) == BrightnessCurve.FLOOR && BrightnessCurve.level(99) == 1f, "notches clamp");
        for (int n = 0; n <= BrightnessCurve.NOTCHES; n++) check(BrightnessCurve.notch(BrightnessCurve.level(n)) == n, "notch round trip " + n);
        check(BrightnessCurve.clamp(Float.NaN) == BrightnessCurve.FLOOR && BrightnessCurve.clamp(3) == 1f && BrightnessCurve.clamp(0) == BrightnessCurve.FLOOR, "clamp");
    }

    private static void bands() {
        check(BrightnessCurve.BANDS == 7, "seven bands");
        check(BrightnessCurve.band(0) == 0 && BrightnessCurve.band(9.99f) == 0, "dark band");
        float[] edges = BrightnessCurve.BAND_EDGES;
        for (int i = 0; i < edges.length; i++) {
            check(BrightnessCurve.band(edges[i]) == i + 1, "an edge belongs to the brighter band: " + edges[i]);
            check(BrightnessCurve.band(Math.nextDown(edges[i])) == i, "just under an edge is the dimmer band: " + edges[i]);
        }
        check(BrightnessCurve.band(1_000_000) == 6, "sun band");
    }

    private static void corrections() {
        BrightnessCurve c = new BrightnessCurve();
        check(!c.learnedAny(), "a new curve has nothing learned");
        check(c.target(300) == BrightnessCurve.base(300), "untrained target is the base curve");
        c.correct(300, 9);
        check(Math.abs(c.target(300) - BrightnessCurve.level(9)) < EPS, "the chosen notch comes back in the same light");
        check(c.learned(3) && c.learnedAny(), "band 3 learned");
        for (int b = 0; b < BrightnessCurve.BANDS; b++) if (b != 3) check(!c.learned(b) && c.offset(b) == 0, "other bands untouched: " + b);
        check(c.target(50) == BrightnessCurve.base(50) && c.target(1000) == BrightnessCurve.base(1000), "neighbouring bands keep the base curve");
        check(c.target(800) > BrightnessCurve.base(800), "the offset moves the whole band");
        c.correct(300, 2);
        check(Math.abs(c.target(300) - BrightnessCurve.level(2)) < EPS, "a later correction replaces the earlier one");
        c.correct(20_000, 0);
        check(Math.abs(c.target(50_000) - BrightnessCurve.FLOOR) < EPS, "target reaches the floor");
        c.correct(1, 10);
        check(Math.abs(c.target(1) - 1f) < EPS, "target reaches full");
        for (int b = 0; b < BrightnessCurve.BANDS; b++) check(Math.abs(c.offset(b)) <= BrightnessCurve.MAX_OFFSET, "offset bound " + b);
    }

    private static void resetAndRestore() {
        BrightnessCurve c = new BrightnessCurve();
        c.correct(5, 8);
        c.correct(6000, 3);
        c.reset();
        check(!c.learnedAny(), "reset forgets every band");
        check(c.target(5) == BrightnessCurve.base(5) && c.target(6000) == BrightnessCurve.base(6000), "reset is the base curve");
        c.restore(2, 0.25f);
        check(c.learned(2) && Math.abs(c.offset(2) - 0.25f) < EPS, "restore sets a band");
        c.restore(4, Float.NaN);
        c.restore(5, Float.POSITIVE_INFINITY);
        c.restore(-1, 0.1f);
        c.restore(BrightnessCurve.BANDS, 0.1f);
        check(!c.learned(4) && !c.learned(5), "bad stored values are ignored");
        c.restore(1, 7f);
        check(c.offset(1) == BrightnessCurve.MAX_OFFSET, "a stored offset is bounded");
    }

    private static void smoother() {
        BrightnessCurve.Smoother s = new BrightnessCurve.Smoother();
        check(s.shown() < 0 && !s.settled(), "nothing shown before the first update");
        check(s.update(0.5f, 0) == 0.5f && s.settled(), "the first reading shows at once");
        check(s.update(0.52f, 100) == 0.5f && s.goal() == 0.5f, "within 8 % is ignored (a passing shadow)");
        // Brightening: about 0.5 s; most of the way there in a second.
        s.update(0.9f, 1000);
        float up = s.update(0.9f, 1500);
        check(up > 0.7f && up < 0.9f, "brightening is fast: " + up);
        for (long t = 1600; t < 5000; t += 100) s.update(0.9f, t);
        check(s.settled() && s.shown() == 0.9f, "it settles on the goal");
        // Dimming: about 4 s; little has changed after half a second.
        s.update(0.3f, 10_000);
        float down = s.update(0.3f, 10_500);
        check(down > 0.8f, "dimming is slow: " + down);
        check(0.9f - down < up - 0.5f, "dimming moves less than brightening in the same time");
        check(s.jump(0.2f, 11_000) == 0.2f && s.settled(), "a rider's pick shows at once");
        // The floor and full are always reachable, even within the hysteresis band.
        BrightnessCurve.Smoother edge = new BrightnessCurve.Smoother();
        edge.update(0.97f, 0);
        edge.update(1f, 100);
        check(edge.goal() == 1f, "full is reachable from just below it");
    }

    private static void modes() {
        check(RidingBrightness.mode(null, true).equals(RidingBrightness.PHONE), "Phone is the default (owner Q20 A)");
        check(RidingBrightness.mode("auto", true).equals(RidingBrightness.AUTO), "Volt auto with a sensor");
        check(RidingBrightness.mode("auto", false).equals(RidingBrightness.PHONE), "Volt auto without a sensor falls back to Phone");
        check(RidingBrightness.mode("fixed", false).equals(RidingBrightness.FIXED), "Fixed needs no sensor");
        check(RidingBrightness.mode("bright", true).equals(RidingBrightness.PHONE), "unknown is Phone");
        Fake f = new Fake();
        RidingBrightness phone = new RidingBrightness(f, f, true);
        check(!phone.showsSlider() && !phone.usesSensor() && phone.notch() < 0, "Phone has no slider and no sensor");
        phone.start(0);
        check(f.levels.size() == 1 && f.levels.get(0) == RidingBrightness.PHONE_LEVEL, "Phone hands the window to Android");
        phone.light(5000, 10);
        phone.step(1, 20);
        phone.choose(9, 30);
        check(f.levels.size() == 1 && f.writes.isEmpty(), "Phone never changes brightness or stores anything");
        phone.stop();
        check(last(f) == RidingBrightness.PHONE_LEVEL, "stop hands it back");
    }

    private static void fixedMode() {
        Fake f = new Fake();
        f.strings.put(RidingBrightness.MODE_KEY, RidingBrightness.FIXED);
        RidingBrightness r = new RidingBrightness(f, f, false);
        check(r.showsSlider() && !r.usesSensor(), "Fixed shows the slider, no sensor");
        check(r.notch() == RidingBrightness.FIXED_DEFAULT_NOTCH, "Fixed starts at the default notch");
        r.choose(3, 0);
        check(f.levels.isEmpty(), "nothing reaches the window before riding mode is in front");
        r.start(0);
        check(last(f) == BrightnessCurve.level(3), "start applies the fixed level");
        r.step(1, 10);
        check(last(f) == BrightnessCurve.level(4) && r.notch() == 4, "+ raises a notch at once");
        check(f.floats.get(RidingBrightness.FIXED_KEY) == BrightnessCurve.level(4), "the level is stored");
        r.step(-1, 20);
        r.step(-1, 30);
        check(r.notch() == 2, "− lowers");
        for (int i = 0; i < 20; i++) r.step(-1, 40 + i);
        check(r.notch() == 0 && last(f) == BrightnessCurve.FLOOR, "never below the floor");
        r.stop();
        check(last(f) == RidingBrightness.PHONE_LEVEL && Float.isNaN(r.applied()), "leaving restores the phone's brightness");
        int count = f.levels.size();
        r.step(1, 100);
        check(f.levels.size() == count, "after leaving, nothing reaches the window");
        RidingBrightness again = new RidingBrightness(f, f, false);
        again.start(200);
        check(last(f) == BrightnessCurve.level(1), "the stored level comes back next time");
    }

    private static void autoMode() {
        Fake f = new Fake();
        f.strings.put(RidingBrightness.MODE_KEY, RidingBrightness.AUTO);
        RidingBrightness r = new RidingBrightness(f, f, true);
        check(r.showsSlider() && r.usesSensor(), "Volt auto uses the sensor and the slider");
        r.start(0);
        check(f.levels.isEmpty(), "Volt auto waits for the first light reading");
        r.light(300, 10);
        check(last(f) == BrightnessCurve.base(300), "first reading applies the base curve at once");
        // Riding into sun: it brightens over ticks, rate-capped to ten updates a second.
        long t = 1000;
        boolean more = r.light(15_000, t);
        int before = f.levels.size();
        check(more, "it wants ticks while it glides");
        r.tick(t + 100);
        check(f.levels.size() == before + 1, "a tick moves it on");
        r.tick(t + 130);
        check(f.levels.size() == before + 1, "no second update within 100 ms");
        t += 130;
        while (more && t < 20_000) { t += 100; more = r.tick(t); }
        check(last(f) == 1f, "it reaches full in sun");
        check(f.levels.size() - before <= (t - 1000) / RidingBrightness.MIN_APPLY_MS + 1, "at most ten updates a second");
        // The rider says it's too bright in this band: − learns for this band only.
        r.step(-1, t + 10);
        check(last(f) == BrightnessCurve.level(9), "− shows at once");
        int sun = BrightnessCurve.band(15_000);
        check(f.floats.containsKey(RidingBrightness.BAND_KEYS[sun]), "the correction is stored for the band");
        check(f.floats.size() == 1, "only that band is stored");
        check(RidingBrightness.learned(f), "learned");
        // Later, a new session in the same light comes back to the rider's level.
        RidingBrightness next = new RidingBrightness(f, f, true);
        next.start(0);
        next.light(15_000, 0);
        check(Math.abs(last(f) - BrightnessCurve.level(9)) < EPS, "the learned level returns in that light");
        next.light(300, 1000);
        for (long u = 1100; u < 60_000; u += 100) next.tick(u);
        check(Math.abs(last(f) - BrightnessCurve.base(300)) < 0.01f, "other bands keep the base curve");
        // A slider pick before any light reading changes the window but learns nothing.
        Fake g = new Fake();
        g.strings.put(RidingBrightness.MODE_KEY, RidingBrightness.AUTO);
        RidingBrightness blind = new RidingBrightness(g, g, true);
        blind.start(0);
        blind.choose(4, 0);
        check(last(g) == BrightnessCurve.level(4) && g.floats.isEmpty(), "no band without a reading");
        // Reset forgets.
        RidingBrightness.forget(f);
        check(!RidingBrightness.learned(f) && !RidingBrightness.load(f).learnedAny(), "forget clears every band");
    }

    private static void storeRoundTrip() {
        Fake f = new Fake();
        BrightnessCurve c = new BrightnessCurve();
        c.correct(25, 7);
        c.correct(7000, 4);
        for (int b = 0; b < BrightnessCurve.BANDS; b++) if (c.learned(b)) f.putFloat(RidingBrightness.BAND_KEYS[b], c.offset(b));
        BrightnessCurve back = RidingBrightness.load(f);
        for (int b = 0; b < BrightnessCurve.BANDS; b++) {
            check(back.learned(b) == c.learned(b), "learned round trip " + b);
            check(Math.abs(back.offset(b) - c.offset(b)) < EPS, "offset round trip " + b);
        }
        check(RidingBrightness.BAND_KEYS.length == BrightnessCurve.BANDS, "a key per band");
    }

    private static void rotation() {
        check(RidingRotation.locks(null) && RidingRotation.locks(RidingRotation.LOCK), "Lock on open is the default (owner Q14 A)");
        check(!RidingRotation.locks(RidingRotation.FOLLOW), "Follow phone turns");
        check(RidingRotation.locks("portrait"), "unknown locks");
    }

    /** Volt sets only riding mode's window brightness: no source writes Android's setting, and the app asks for no WRITE_SETTINGS. */
    private static void neverWritesSystemSetting() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get("src"))) { walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add); }
        check(files.size() > 50, "the scan sees the app's sources");
        for (Path p : files) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            check(!text.contains("SCREEN_BRIGHTNESS"), "no Settings.System brightness in " + p);
            check(!text.contains("Settings.System.put"), "no Settings.System writes in " + p);
            check(!text.contains("ACTION_MANAGE_WRITE_SETTINGS"), "no write-settings request in " + p);
        }
        String manifest = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(!manifest.contains("WRITE_SETTINGS"), "the manifest asks for no WRITE_SETTINGS");
        String display = new String(Files.readAllBytes(Paths.get("src/com/jjricks/volt/RidingDisplay.java")), StandardCharsets.UTF_8);
        check(display.contains("attributes.screenBrightness"), "RidingDisplay sets the window's brightness");
        // The status bar stays by default (#254); only the owner's setting hides it, and leaving shows it again.
        check(display.contains("preferences.getBoolean(HIDE_STATUS_BAR_KEY, false)"), "Hide status bar is off by default");
        check(display.contains("if (hidesStatusBar) statusBar(true);"), "leaving shows the status bar it hid");
    }

    private static float last(Fake f) { return f.levels.get(f.levels.size() - 1); }

    private static final class Fake implements RidingBrightness.Store, RidingBrightness.Window {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Float> floats = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        final List<Float> levels = new ArrayList<>();
        @Override public String getString(String key, String fallback) { return strings.getOrDefault(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return floats.getOrDefault(key, fallback); }
        @Override public boolean contains(String key) { return strings.containsKey(key) || floats.containsKey(key); }
        @Override public void putString(String key, String value) { writes.add(key); strings.put(key, value); }
        @Override public void putFloat(String key, float value) { writes.add(key); floats.put(key, value); }
        @Override public void remove(String key) { writes.add(key); strings.remove(key); floats.remove(key); }
        @Override public void brightness(float level) { levels.add(level); }
    }
}
