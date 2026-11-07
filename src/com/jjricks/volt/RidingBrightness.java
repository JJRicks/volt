package com.jjricks.volt;

/**
 * Riding mode's screen brightness choice and its slider (pure Java; RidingDisplay connects it to the
 * window and the light sensor).
 * <ul>
 * <li>Phone (the default, owner Q20 A): Android's own brightness, untouched.
 * <li>Volt auto: the light sensor through BrightnessCurve, trained by the slider's − and +.
 * <li>Fixed: one level, set with the same slider.
 * </ul>
 * It only ever sets this window's brightness ({@link Window}); −1 hands the screen back to the
 * phone. It never writes Android's brightness setting.
 */
final class RidingBrightness {
    static final String MODE_KEY = "riding_brightness", FIXED_KEY = "riding_brightness_fixed";
    static final String PHONE = "phone", AUTO = "auto", FIXED = "fixed";
    /** One stored offset per light band (BrightnessCurve.BAND_EDGES); a missing key is an untrained band. */
    static final String[] BAND_KEYS = {"riding_brightness_band0", "riding_brightness_band1", "riding_brightness_band2",
            "riding_brightness_band3", "riding_brightness_band4", "riding_brightness_band5", "riding_brightness_band6"};
    /** The window hands brightness back to the phone (WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE). */
    static final float PHONE_LEVEL = -1f;
    static final int FIXED_DEFAULT_NOTCH = 6;
    /** At most ten window updates a second while the level glides. */
    static final long MIN_APPLY_MS = 100;

    /** The settings it reads and writes (app_settings). */
    interface Store {
        String getString(String key, String fallback);
        float getFloat(String key, float fallback);
        boolean contains(String key);
        void putString(String key, String value);
        void putFloat(String key, float value);
        void remove(String key);
    }

    /** This window's brightness: a level 0.05..1, or PHONE_LEVEL to hand it back. */
    interface Window {
        void brightness(float level);
    }

    private final Store store;
    private final Window window;
    private final String mode;
    private final BrightnessCurve curve;
    private final BrightnessCurve.Smoother smoother = new BrightnessCurve.Smoother();
    private float lux = Float.NaN, applied = Float.NaN;
    private long appliedMs = Long.MIN_VALUE / 2;
    private boolean running;

    RidingBrightness(Store store, Window window, boolean lightSensor) {
        this.store = store;
        this.window = window;
        mode = mode(store.getString(MODE_KEY, PHONE), lightSensor);
        curve = load(store);
    }

    /** The mode in use: Volt auto without a light sensor, or anything unknown, is Phone. */
    static String mode(String stored, boolean lightSensor) {
        if (FIXED.equals(stored)) return FIXED;
        if (AUTO.equals(stored) && lightSensor) return AUTO;
        return PHONE;
    }

    /** The learned curve kept in the settings. */
    static BrightnessCurve load(Store store) {
        BrightnessCurve curve = new BrightnessCurve();
        for (int band = 0; band < BAND_KEYS.length; band++)
            if (store.contains(BAND_KEYS[band])) curve.restore(band, store.getFloat(BAND_KEYS[band], 0));
        return curve;
    }

    /** Some light band has a rider's correction. */
    static boolean learned(Store store) {
        for (String key : BAND_KEYS) if (store.contains(key)) return true;
        return false;
    }

    /** Forgets every Volt auto correction (More → Riding mode). */
    static void forget(Store store) {
        for (String key : BAND_KEYS) store.remove(key);
    }

    String mode() { return mode; }
    /** The − / + slider shows in Volt auto and Fixed; Phone leaves brightness to Android. */
    boolean showsSlider() { return !PHONE.equals(mode); }
    boolean usesSensor() { return AUTO.equals(mode); }

    /** Riding mode is in front: Fixed applies its level; Volt auto waits for the first light reading. */
    void start(long nowMs) {
        running = true;
        if (FIXED.equals(mode)) apply(fixedLevel(), nowMs, true);
        else if (PHONE.equals(mode)) apply(PHONE_LEVEL, nowMs, true);
        else if (!Float.isNaN(lux)) tick(nowMs);
    }

    /** Riding mode left the screen: the phone's own brightness comes back. */
    void stop() {
        running = false;
        applied = Float.NaN;
        window.brightness(PHONE_LEVEL);
    }

    /** A light sensor reading. True while the level still glides and wants another tick. */
    boolean light(float lux, long nowMs) {
        if (!(lux >= 0)) return false;
        this.lux = lux;
        return tick(nowMs);
    }

    /** Moves the shown level on. True while it still glides toward the target. */
    boolean tick(long nowMs) {
        if (!running || !AUTO.equals(mode) || Float.isNaN(lux)) return false;
        float level = smoother.update(curve.target(lux), nowMs);
        boolean settled = smoother.settled();
        apply(level, nowMs, settled);
        return !settled || applied != level;
    }

    /** The slider's notch now. */
    int notch() {
        if (FIXED.equals(mode)) return BrightnessCurve.notch(fixedLevel());
        if (AUTO.equals(mode)) {
            float goal = smoother.goal();
            if (goal >= 0) return BrightnessCurve.notch(goal);
            return BrightnessCurve.notch(Float.isNaN(lux) ? BrightnessCurve.level(FIXED_DEFAULT_NOTCH) : curve.target(lux));
        }
        return -1;
    }

    /** − (−1) or + (+1) on the slider. */
    void step(int delta, long nowMs) {
        int now = notch();
        if (now < 0) return;
        choose(now + delta, nowMs);
    }

    /**
     * The rider picked a notch. Fixed keeps it as its level. Volt auto shows it at once and learns it
     * for the light band it is in now; the other bands keep theirs.
     */
    void choose(int notch, long nowMs) {
        int n = Math.max(0, Math.min(BrightnessCurve.NOTCHES, notch));
        float level = BrightnessCurve.level(n);
        if (FIXED.equals(mode)) {
            store.putFloat(FIXED_KEY, level);
            apply(level, nowMs, true);
        } else if (AUTO.equals(mode)) {
            if (!Float.isNaN(lux)) {
                curve.correct(lux, n);
                int band = BrightnessCurve.band(lux);
                store.putFloat(BAND_KEYS[band], curve.offset(band));
            }
            apply(smoother.jump(level, nowMs), nowMs, true);
        }
    }

    /** The level the window shows now, or NaN when brightness is the phone's. */
    float applied() { return applied; }

    private float fixedLevel() {
        return BrightnessCurve.clamp(store.getFloat(FIXED_KEY, BrightnessCurve.level(FIXED_DEFAULT_NOTCH)));
    }

    private void apply(float level, long nowMs, boolean force) {
        if (!running) return;
        if (!force && nowMs - appliedMs < MIN_APPLY_MS) return;
        if (level == applied) return;
        applied = level;
        appliedMs = nowMs;
        window.brightness(level);
    }
}
