package com.jjricks.volt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Riding mode's display follows the activity's real lifecycle (Codex review of #268, round 4): a riding
 * screen rebuilt while the activity is paused (onServiceConnected, onCreate) registers no light sensor,
 * schedules no ticks and sets no window brightness until the next resume. The model below wires
 * RidingDisplayState and RidingBrightness the way RidingDisplay does; source checks tie the two together.
 * Listed in tests/l10n/riding-display.sources; tests/run.sh runs it from the repository root.
 */
public final class RidingDisplayStateTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws IOException {
        stateMachine();
        backgroundRebuild(RidingBrightness.AUTO);
        backgroundRebuild(RidingBrightness.FIXED);
        backgroundRebuild(RidingBrightness.PHONE);
        foregroundRebuild();
        detachWhilePaused();
        wiring();
        System.out.println("RidingDisplayStateTest: " + checks + " checks passed");
    }

    private static void stateMachine() {
        RidingDisplayState paused = new RidingDisplayState(false);
        check(!paused.active() && !paused.listens(true), "made while paused: not active, no sensor");
        check(!paused.pause(), "a pause while paused changes nothing");
        check(paused.resume() && paused.active() && paused.listens(true) && !paused.listens(false), "resume starts it");
        check(!paused.resume(), "a second resume changes nothing");
        check(paused.pause() && !paused.active(), "pause stops it");
        check(paused.detach() && paused.detached() && !paused.detach(), "detach happens once");
        check(!paused.resume() && !paused.active() && !paused.listens(true), "after detach nothing restarts it");
        RidingDisplayState resumed = new RidingDisplayState(true);
        check(resumed.active() && resumed.listens(true), "made while resumed: active at once");
        check(resumed.detach() && !resumed.active() && !resumed.pause(), "detach while resumed ends it");
    }

    /** paused → rebuild (onServiceConnected) → still paused → resume → pause → resume → close. */
    private static void backgroundRebuild(String mode) {
        Fake f = new Fake();
        f.strings.put(RidingBrightness.MODE_KEY, mode);
        Display old = new Display(f, true);
        old.pause(1);
        int before = f.levels.size();
        old.detach();
        check(f.levels.size() == before + 1 && last(f) == RidingBrightness.PHONE_LEVEL, mode + ": the old screen hands the window back");
        Display rebuilt = new Display(f, false);
        rebuilt.light(5000, 10);
        rebuilt.tick(20);
        check(!rebuilt.listening && !rebuilt.ticking, mode + ": a rebuild while paused registers no sensor and no ticks");
        check(f.levels.size() == before + 1, mode + ": a rebuild while paused sets no window brightness");
        rebuilt.pause(30);
        rebuilt.light(20000, 40);
        check(!rebuilt.listening && f.levels.size() == before + 1, mode + ": still paused, still nothing");
        rebuilt.resume(50);
        check(rebuilt.listening == RidingBrightness.AUTO.equals(mode), mode + ": the sensor starts on resume in Volt auto only");
        // Volt auto waits for the sensor's first reading, which only arrives once it is registered.
        if (RidingBrightness.AUTO.equals(mode)) rebuilt.light(5000, 55);
        check(f.levels.size() > before + 1, mode + ": brightness is applied once resumed");
        if (!RidingBrightness.PHONE.equals(mode))
            check(last(f) != RidingBrightness.PHONE_LEVEL, mode + ": resume applies riding mode's level");
        rebuilt.pause(60);
        check(!rebuilt.listening && !rebuilt.ticking, mode + ": pause stops the sensor and ticks");
        int paused = f.levels.size();
        rebuilt.light(50, 70);
        rebuilt.tick(80);
        check(f.levels.size() == paused, mode + ": nothing reaches the window while paused");
        rebuilt.resume(90);
        check(rebuilt.listening == RidingBrightness.AUTO.equals(mode), mode + ": a later resume starts it again");
        rebuilt.detach();
        check(!rebuilt.listening && last(f) == RidingBrightness.PHONE_LEVEL, mode + ": close hands the window back");
    }

    private static void foregroundRebuild() {
        Fake f = new Fake();
        f.strings.put(RidingBrightness.MODE_KEY, RidingBrightness.AUTO);
        Display old = new Display(f, true);
        old.light(5000, 5);
        check(old.listening && !f.levels.isEmpty(), "a riding screen opened while resumed starts at once");
        old.detach();
        Display rebuilt = new Display(f, true);
        rebuilt.light(5000, 15);
        check(rebuilt.listening && last(f) != RidingBrightness.PHONE_LEVEL, "a rebuild while resumed keeps riding mode's brightness");
        rebuilt.detach();
    }

    private static void detachWhilePaused() {
        Fake f = new Fake();
        f.strings.put(RidingBrightness.MODE_KEY, RidingBrightness.FIXED);
        Display d = new Display(f, false);
        d.detach();
        check(!d.listening && last(f) == RidingBrightness.PHONE_LEVEL, "closed before it ever resumed: the window is Android's");
        int count = f.levels.size();
        d.resume(10);
        check(!d.listening && f.levels.size() == count, "a resume after close does nothing");
    }

    private static void wiring() throws IOException {
        String display = read("src/com/jjricks/volt/RidingDisplay.java");
        String screen = read("src/com/jjricks/volt/RidingScreen.java");
        String host = read("src/com/jjricks/volt/ScreenHost.java");
        String main = read("src/com/jjricks/volt/MainActivity.java");
        check(screen.contains("new RidingDisplay(host.activity(), ui, preferences, host.resumed())"), "RidingScreen passes the activity's real state");
        check(host.contains("default boolean resumed() { return false; }"), "a host that doesn't say keeps the display paused");
        check(main.contains("@Override public boolean resumed(){return resumed;}"), "MainActivity reports its resumed flag");
        check(display.contains("state = new RidingDisplayState(resumed);"), "RidingDisplay starts from that state");
        check(display.contains("if (state.active()) start();"), "construction starts only while resumed");
        check(!display.contains("        resume();\n"), "no unconditional start");
        check(display.contains("if (a == activity && state.resume()) start();"), "onActivityResumed starts it");
        check(display.contains("if (a == activity && state.pause()) stopListening();"), "onActivityPaused stops it");
        check(display.contains("state.listens(brightness.usesSensor())"), "the sensor follows the state");
        check(display.contains("if (!state.active() || event.values.length == 0) return;"), "readings while paused are dropped");
        check(display.contains("if (!state.active()) return;\n            if (brightness.tick("), "ticks while paused do nothing");
    }

    /** RidingDisplay's lifecycle wiring without Android: listening and ticking stand for the sensor and handler. */
    private static final class Display {
        final RidingDisplayState state;
        final RidingBrightness brightness;
        boolean listening, ticking;
        Display(Fake f, boolean resumed) {
            state = new RidingDisplayState(resumed);
            brightness = new RidingBrightness(f, f, true);
            if (state.active()) start(0);
        }
        void resume(long now) { if (state.resume()) start(now); }
        void pause(long now) { if (state.pause()) stopListening(); }
        void detach() {
            if (!state.detach()) return;
            stopListening();
            brightness.stop();
        }
        void light(float lux, long now) {
            if (!state.active()) return;
            if (brightness.light(lux, now)) ticking = true;
        }
        void tick(long now) {
            if (!state.active()) return;
            ticking = brightness.tick(now);
        }
        private void start(long now) {
            if (!state.active()) return;
            brightness.start(now);
            if (state.listens(brightness.usesSensor())) listening = true;
        }
        private void stopListening() { listening = false; ticking = false; }
    }

    private static float last(Fake f) { return f.levels.get(f.levels.size() - 1); }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static final class Fake implements RidingBrightness.Store, RidingBrightness.Window {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Float> floats = new HashMap<>();
        final List<Float> levels = new ArrayList<>();
        @Override public String getString(String key, String fallback) { return strings.getOrDefault(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return floats.getOrDefault(key, fallback); }
        @Override public boolean contains(String key) { return strings.containsKey(key) || floats.containsKey(key); }
        @Override public void putString(String key, String value) { strings.put(key, value); }
        @Override public void putFloat(String key, float value) { floats.put(key, value); }
        @Override public void remove(String key) { strings.remove(key); floats.remove(key); }
        @Override public void brightness(float level) { levels.add(level); }
    }
}
