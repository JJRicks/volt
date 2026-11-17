package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * PROTOTYPE (charge transition, phase 1): drives the developer simulator between Parked and Charging on a fresh
 * emulator so the shell can screen-record Home's parked/charging transition. Not a validation: it asserts nothing
 * beyond its own setup, and logs (tag CTDRIVER) what Home did at each step: the Home object (a rebuild replaces
 * it), Clean's moment, and the moments' alpha. Phase 2 replaces it with a real motion validation.
 *
 * Arguments: style (clean|widgets), model ("" UT5 MAX, or a simulator model id), variant (0 today, 1 A, 2 B),
 * start (parked|charging), steps ("C:3000,P:3000": C charging, P parked, D toggles the Charge done by quick action
 * through the app's own dispatch, K the simulated scooter reacting to the schedule (charging follows done-by off/on),
 * then milliseconds to wait), lead (ms after READY before the first step), tour (a ChargePicture key to force).
 */
public final class ChargeTransitionDriver extends Instrumentation {
    private static final String TAG = "CTDRIVER";
    private Bundle arguments;
    private MainActivity activity;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            if (!(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone")))
                throw new IllegalStateException("Refusing a physical phone");
            String style = arguments.getString("style", "clean");
            String model = arguments.getString("model", "");
            int variant = Integer.parseInt(arguments.getString("variant", "0"));
            boolean charging = "charging".equals(arguments.getString("start", "parked"));
            settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", arguments.getString("theme", "dark")).putString("units", "km")
                    .putString("home_style", style).putBoolean("home_style_chosen", true).putBoolean("home_whats_new_shown", true)
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                if (profiles.active() == null) {
                    ScooterProfiles.Profile saved = profiles.create("UT5 MAX", "000000000000", 0);
                    saved.serial = "X2538-SYNTHETIC"; saved.lastConnected = 1767225600000L; profiles.update(saved);
                }
                OnboardingScreen.markDone(getTargetContext());
            });
            setStatic("com.jjricks.volt.ChargeTransition", "variant", variant);
            String tour = arguments.getString("tour", null);
            if (tour != null) setStatic("com.jjricks.volt.ChargePicture", "tourKey", tour);
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "App did not launch", 10000);
            SystemClock.sleep(1200); waitForIdleSync();
            ScooterService service = activity.service();
            main(() -> {
                if (!service.simulating()) service.setSimulation(true);
                service.ble.setSimulationModel(model);
                service.ble.setSimulationCharging(charging);
                service.ble.tickSimulation();
            });
            await(() -> service.ble.ready(), "The simulated scooter is not ready", 10000);
            main(() -> invoke(activity, "select", new Class<?>[] {String.class}, "home"));
            SystemClock.sleep(2500); waitForIdleSync();
            Log.i(TAG, "READY");
            SystemClock.sleep(Long.parseLong(arguments.getString("lead", "2500")));
            String steps = arguments.getString("steps", "C:3000,P:3000");
            boolean[] scheduled = {false};
            for (String step : steps.split(",")) {
                String[] parts = step.trim().split(":");
                String what = parts[0];
                long wait = parts.length > 1 ? Long.parseLong(parts[1]) : 3000;
                Object before = home();
                main(() -> {
                    switch (what) {
                        // The body refreshes at once, as a live reading does on its next refresh (exact timing for reversals).
                        case "C": service.ble.setSimulationCharging(true); activity.redraw(); break;
                        case "P": service.ble.setSimulationCharging(false); activity.redraw(); break;
                        case "D": invoke(activity, "quickAction", new Class<?>[] {String.class}, "charge_done_by"); break;
                        case "K": {
                            // The scooter's answer to the schedule: done-by on stops the charge, off resumes it.
                            Object view = invoke(service, "chargeSchedule", new Class<?>[0]);
                            boolean on = Boolean.TRUE.equals(field(view, "doneByEnabled"));
                            scheduled[0] = on;
                            service.ble.setSimulationCharging(!on);
                            break;
                        }
                        default: break;
                    }
                });
                Log.i(TAG, "STEP " + what + " at " + SystemClock.uptimeMillis());
                long end = SystemClock.uptimeMillis() + wait;
                while (SystemClock.uptimeMillis() < end) {
                    log(what, before);
                    SystemClock.sleep(wait > 1200 ? 100 : 50);
                }
            }
            Log.i(TAG, "DONE");
            result.putString("result", "ok");
            finish(0, result);
        } catch (Throwable failure) {
            Log.e(TAG, "FAILED", failure);
            result.putString("result", "FAILED " + failure);
            finish(1, result);
        }
    }

    private String last = "";

    private void log(String step, Object before) {
        StringBuilder line = new StringBuilder();
        main(() -> {
            Object home = home();
            line.append(home == before ? "same-home" : "NEW-HOME ").append(' ').append(home == null ? "null" : home.getClass().getSimpleName());
            try {
                Object moment = field(home, "moment");
                line.append(" moment=").append(moment);
                View[] moments = (View[]) field(home, "moments");
                for (View v : moments) line.append(String.format(Locale.US, " [%s v=%d a=%.2f]", v.getTag(), v.getVisibility(), v.getAlpha()));
                try {
                    android.widget.ImageView photo = (android.widget.ImageView) invoke(field(home, "parts"), "parkedPhoto", new Class<?>[0]);
                    if (photo != null) line.append(String.format(Locale.US, " photo[v=%d a=%.2f d=%s %dx%d]", photo.getVisibility(), photo.getAlpha(),
                            photo.getDrawable() == null ? "null" : String.valueOf(photo.getDrawable().getAlpha()), photo.getWidth(), photo.getHeight()));
                } catch (RuntimeException old) { }
            } catch (RuntimeException notClean) {
                try {
                    View hero = (View) field(home, "chargingHero");
                    line.append(String.format(Locale.US, " moment=%d widgets hero v=%d a=%.2f h=%d", hero.getVisibility() == View.VISIBLE ? 1 : 0, hero.getVisibility(), hero.getAlpha(), hero.getHeight()));
                } catch (RuntimeException none) { line.append(" (widgets)"); }
            }
            line.append(" motion=").append(android.animation.ValueAnimator.areAnimatorsEnabled());
        });
        String text = line.toString();
        if (!text.equals(last)) { Log.i(TAG, step + " " + SystemClock.uptimeMillis() + " " + text); last = text; }
    }

    private Object home() {
        // The two-pane Home's scooter pane is MainActivity's homeScreen; the page pane is the screen.
        Object pane = field(activity, "homeScreen");
        return pane != null ? pane : field(activity, "screen");
    }

    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            boolean[] ok = {false};
            main(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) return;
            SystemClock.sleep(100);
        }
        throw new IllegalStateException(message);
    }
    private static void setStatic(String type, String name, Object value) {
        try { Field f = Class.forName(type).getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
        catch (ReflectiveOperationException absent) { Log.i(TAG, "no " + type + "." + name); }
    }
    private static Object field(Object target, String name) {
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
                catch (NoSuchFieldException e) { type = type.getSuperclass(); }
            }
            throw new NoSuchFieldException(name);
        } catch (Exception e) { throw new IllegalStateException("Field " + name, e); }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try {
            Class<?> type = target.getClass(); Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values);
        } catch (Exception e) { throw new IllegalStateException("Invoke " + name, e); }
    }
}
