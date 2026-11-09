package com.jjricks.volt;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Riding mode's window: screen brightness (RidingBrightness, with the − / + slider) and the rotation
 * lock (owner Q14 A). RidingScreen makes one and calls detach() when it closes; everything comes back
 * as it was. MainActivity hides the navigation bar while riding and keeps the status bar for the
 * phone's battery and clock (#254); keep-awake stays there too. Only the owner's "Hide status bar"
 * setting (off by default) makes this hide the status bar as well, from the next time riding mode
 * opens, and show it again on leaving. The light sensor listens only while the activity is
 * resumed and Volt auto is chosen.
 */
final class RidingDisplay {
    /** The one attached display: a rebuild's old screen can't undo the new one's window state. */
    private static RidingDisplay current;

    private final Activity activity;
    private final Ui ui;
    private final RidingBrightness brightness;
    private final SensorManager sensors;
    private final Sensor light;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout controls;
    private final Track track;
    private final int previousOrientation;
    /** The owner's Hide status bar setting, or night vision (the red layer can't reach the status bar). */
    private boolean hidesStatusBar;
    private final boolean statusBarSetting;
    /** Night vision's amount: 0 the normal look, 1 full night vision, between while it fades (NightFade). */
    private float night;
    private final RidingDisplayState state;
    private boolean listening;

    private final SensorEventListener listener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (!state.active() || event.values.length == 0) return;
            if (brightness.light(event.values[0], SystemClock.uptimeMillis())) scheduleTick();
            track.invalidate();
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!state.active()) return;
            if (brightness.tick(SystemClock.uptimeMillis())) scheduleTick();
            track.invalidate();
        }
    };

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityResumed(Activity a) { if (a == activity && state.resume()) start(); }
        @Override public void onActivityPaused(Activity a) { if (a == activity && state.pause()) stopListening(); }
        @Override public void onActivityDestroyed(Activity a) { if (a == activity) detach(); }
        @Override public void onActivityCreated(Activity a, Bundle saved) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle out) { }
    };

    /** resumed: the activity is resumed now; otherwise the display waits for its next resume. */
    RidingDisplay(Activity activity, Ui ui, SharedPreferences preferences, boolean resumed) {
        this.activity = activity;
        state = new RidingDisplayState(resumed);
        this.ui = ui;
        sensors = activity.getSystemService(SensorManager.class);
        light = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        brightness = new RidingBrightness(store(preferences), this::window, light != null);

        Context context = activity;
        controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setTag("riding_brightness_slider");
        View minus = button(false), plus = button(true);
        track = new Track(context);
        controls.addView(minus, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        LinearLayout.LayoutParams trackSize = new LinearLayout.LayoutParams(0, ui.dp(56), 1);
        trackSize.setMarginStart(ui.dp(10));
        trackSize.setMarginEnd(ui.dp(10));
        controls.addView(track, trackSize);
        controls.addView(plus, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        controls.setVisibility(brightness.showsSlider() ? View.VISIBLE : View.GONE);

        if (current != null) current.detach();
        current = this;
        previousOrientation = activity.getRequestedOrientation();
        if (RidingRotation.locks(preferences.getString(RidingRotation.KEY, RidingRotation.LOCK)))
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        hidesStatusBar = preferences.getBoolean(RidingBars.HIDE_STATUS_BAR_KEY, false);
        statusBarSetting = hidesStatusBar;
        // Like MainActivity's bar styling, this waits until the screen is attached (Samsung's decor getter).
        if (hidesStatusBar) controls.post(() -> { if (!state.detached() && current == this) statusBar(false); });
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        // A rebuild while paused (onServiceConnected, onCreate) waits for onActivityResumed.
        if (state.active()) start();
    }

    /**
     * Night vision (RidingNight): Volt auto and Fixed use the night range (NightVision.level), blended by
     * the amount while night vision fades (NightFade.level). The status bar follows where the fade is
     * going ({@code on}), so the layout moves once, at the start, under the fade. Phone keeps Android's
     * brightness.
     */
    void night(float amount, boolean on) {
        amount = NightFade.clamp(amount);
        if (state.detached()) return;
        if (night != amount) {
            night = amount;
            float level = brightness.applied();
            // Only while resumed: start() sends the level again on the next resume.
            if (!Float.isNaN(level) && state.active()) window(level);
        }
        boolean hide = statusBarSetting || on;
        if (hide != hidesStatusBar) {
            hidesStatusBar = hide;
            controls.post(() -> { if (!state.detached() && current == this) statusBar(!hide); });
        }
    }

    /** The slider row along the bottom: −, the notched level, +. Gone in Phone mode. */
    View controls() { return controls; }

    /** The slider row's place under riding mode's readings. */
    static LinearLayout.LayoutParams slot(Ui ui) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, ui.dp(56));
        size.topMargin = ui.dp(8);
        return size;
    }

    /** Riding mode closed: the phone's brightness and rotation come back. */
    void detach() {
        if (!state.detach()) return;
        stopListening();
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        if (current != this) return;
        current = null;
        brightness.stop();
        if (activity.isDestroyed()) return;
        activity.setRequestedOrientation(previousOrientation);
        if (hidesStatusBar) statusBar(true);
    }

    /** The activity is resumed and the display attached: brightness, then the sensor in Volt auto. */
    private void start() {
        if (!state.active()) return;
        brightness.start(SystemClock.uptimeMillis());
        // The level may be unchanged since the pause while night vision switched: send it again.
        float level = brightness.applied();
        if (!Float.isNaN(level)) window(level);
        if (state.listens(brightness.usesSensor()) && !listening && light != null) {
            listening = sensors.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL, handler);
        }
        track.invalidate();
    }

    /** Paused or closed: no sensor and no ticks. The window keeps its level until leaving (detach). */
    private void stopListening() {
        if (listening) sensors.unregisterListener(listener);
        listening = false;
        handler.removeCallbacks(tick);
    }

    private void scheduleTick() {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, RidingBrightness.MIN_APPLY_MS);
    }

    /** This window only: never Android's brightness setting. */
    private void window(float level) {
        if (activity.isDestroyed()) return;
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        level = NightFade.level(level, night);
        attributes.screenBrightness = level < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : level;
        activity.getWindow().setAttributes(attributes);
    }

    /** The owner's setting: hide the status bar too (a swipe shows the bars for a moment), or show it again. */
    private void statusBar(boolean show) {
        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller == null) return;
            if (show) controller.show(WindowInsets.Type.statusBars());
            else {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            int visibility = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(show ? visibility & ~View.SYSTEM_UI_FLAG_FULLSCREEN : visibility | flags);
        }
    }

    private void changed(int delta) {
        if (!state.active()) return;
        brightness.step(delta, SystemClock.uptimeMillis());
        Haptics.tick(track);
        track.invalidate();
    }

    private View button(boolean plus) {
        FrameLayout button = new FrameLayout(activity);
        button.setBackground(ui.shape(ui.dark ? ui.surface : ui.raised, 28, 0));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(plus ? T.ridingBrightnessBrighter() : T.ridingBrightnessDimmer());
        button.setOnClickListener(v -> changed(plus ? 1 : -1));
        button.addView(new Sign(activity, plus), new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        return button;
    }

    /** A bold − or +. */
    private final class Sign extends View {
        private final boolean plus;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Sign(Context context, boolean plus) {
            super(context);
            this.plus = plus;
            paint.setColor(ui.text);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(ui.dp(3.5f));
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), inset = ui.dp(3);
            canvas.drawLine(inset, h / 2, w - inset, h / 2, paint);
            if (plus) canvas.drawLine(w / 2, inset, w / 2, h - inset, paint);
        }
    }

    /** The notched level: a tick per notch, filled up to the current one; a tap or drag picks a notch. */
    private final class Track extends View {
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                rail = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int dragged = -1;
        Track(Context context) {
            super(context);
            tickPaint.setColor(ui.muted);
            rail.setColor(ui.dark ? ui.surface : ui.raised);
            fill.setColor(ui.accent);
            thumb.setColor(ui.text);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private float left() { return ui.dp(14); }
        private float right() { return getWidth() - ui.dp(14); }
        private float x(int notch) { return left() + (right() - left()) * notch / BrightnessCurve.NOTCHES; }
        @Override protected void onDraw(Canvas canvas) {
            int notch = brightness.notch();
            float cy = getHeight() / 2f, half = ui.dp(4);
            canvas.drawRoundRect(left() - half, cy - half, right() + half, cy + half, half, half, rail);
            for (int n = 0; n <= BrightnessCurve.NOTCHES; n++) {
                float x = x(n), tall = n % 5 == 0 ? ui.dp(11) : ui.dp(7);
                tickPaint.setStrokeWidth(ui.dp(2));
                canvas.drawLine(x, cy - tall - half, x, cy - half - ui.dp(2), tickPaint);
            }
            if (notch < 0) return;
            canvas.drawRoundRect(left() - half, cy - half, x(notch) + half, cy + half, half, half, fill);
            canvas.drawCircle(x(notch), cy, ui.dp(11), thumb);
            canvas.drawCircle(x(notch), cy, ui.dp(6), fill);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                float t = (event.getX() - left()) / Math.max(1, right() - left());
                int notch = Math.max(0, Math.min(BrightnessCurve.NOTCHES, Math.round(t * BrightnessCurve.NOTCHES)));
                if (notch != dragged && notch != brightness.notch()) {
                    brightness.choose(notch, SystemClock.uptimeMillis());
                    Haptics.tick(this);
                }
                dragged = notch;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { dragged = -1; return true; }
            return super.onTouchEvent(event);
        }
    }

    private static RidingBrightness.Store store(SharedPreferences preferences) {
        return new RidingBrightness.Store() {
            @Override public String getString(String key, String fallback) { return preferences.getString(key, fallback); }
            @Override public float getFloat(String key, float fallback) { return preferences.getFloat(key, fallback); }
            @Override public boolean contains(String key) { return preferences.contains(key); }
            @Override public void putString(String key, String value) { preferences.edit().putString(key, value).apply(); }
            @Override public void putFloat(String key, float value) { preferences.edit().putFloat(key, value).apply(); }
            @Override public void remove(String key) { preferences.edit().remove(key).apply(); }
        };
    }

    /** The tune menu (RidingSettingsMenu): whether this phone has a light sensor, so Volt auto can be offered. */
    static boolean hasLightSensor(Context context) {
        SensorManager manager = context.getSystemService(SensorManager.class);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }

    /** The tune menu's settings store, for the reset row. */
    static RidingBrightness.Store settings(SharedPreferences preferences) { return store(preferences); }
}
