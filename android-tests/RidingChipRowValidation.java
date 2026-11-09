package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Riding mode's real chip row with its first chip unavailable (a drive mode the scooter hasn't given). The row is measured at
 * every width from roomy to tiny, so it lands on each compact rung's exact boundary whatever the fonts: every chip it keeps
 * has to fit whole, gaps included, and the first one shown has to start at the row's edge. Simulator only; takes no screenshots.
 */
public final class RidingChipRowValidation extends Instrumentation {
    private MainActivity activity;
    private String profileId;

    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            if (!(hw.contains("ranchu") || hw.contains("goldfish"))) throw new IllegalStateException("emulator only");
            if (!prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true).commit())
                throw new IllegalStateException("settings");
            main(() -> {
                OnboardingScreen.markDone(getTargetContext());
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic chip scooter", "0F1E2D3C4B5A", 0);
                p.mac = "C0:FF:EE:00:00:08"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
                profileId = p.id;
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch"); settle();
            main(() -> { if (!activity.service().setSimulation(true)) throw new IllegalStateException("simulation"); }); settle();
            await(() -> activity.service().ble.ready(), "simulation ready");
            String json = "{\"v\":1,\"frame\":\"halo\",\"frames\":{\"halo\":{\"spots\":{\"chip_1\":\"drive_mode\",\"chip_2\":\"battery_temperature\",\"chip_3\":\"clock\"}}}}";
            if (!prefs().edit().putString("riding_layout_v1_" + profileId, json).commit()) throw new IllegalStateException("layout");
            main(() -> {
                try { unknownMode(); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                activity.openRiding();
            });
            settle();
            await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding did not open");
            main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); });
            settle(); SystemClock.sleep(900); settle();
            main(this::sweep);
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) try { main(() -> { invoke(activity, "closeRiding"); activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** A drive mode code no label knows: the chip has nothing to say and steps aside, as before the scooter reports one. */
    private void unknownMode() throws ReflectiveOperationException {
        Object sim = field(activity.service().ble, "simulation");
        Method sample = sim.getClass().getDeclaredMethod("sample", String.class, String.class, double.class); sample.setAccessible(true);
        sample.invoke(sim, "driving_mode", "99 (raw)", 99d);
        // The acceleration feature reads back through driving_mode on every tick.
        @SuppressWarnings("unchecked") Map<String, Integer> features = (Map<String, Integer>) field(sim, "features");
        features.put("acceleration", 99);
        activity.service().ble.tickSimulation();
    }

    private void sweep() {
        List<?> chips = (List<?>) field(field(activity, "screen"), "chips");
        View mode = (View) chips.get(0);
        require("drive_mode".equals(field(chips.get(0), "content")), "first chip is the drive mode");
        ViewGroup row = (ViewGroup) mode.getParent();
        int unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        row.measure(unbounded, unbounded);
        int natural = 0;
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).getVisibility() != View.GONE) natural += row.getChildAt(i).getMeasuredWidth() + margins(row.getChildAt(i));
        require(mode.getVisibility() == View.GONE, "the drive mode chip stepped aside");
        boolean sawTwo = false, sawCompactTwo = false;
        for (int width = natural + 40; width > 0; width--) {
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), unbounded);
            int used = 0, shown = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View chip = row.getChildAt(i);
                if (chip.getVisibility() == View.GONE) continue;
                used += chip.getMeasuredWidth() + margins(chip);
                TextView text = (TextView) field(chips.get(i), "text");
                float needed = text.getPaint().measureText(text.getText().toString());
                require(text.getMeasuredWidth() >= Math.floor(needed), "width " + width + ": '" + text.getText() + "' measured " + text.getMeasuredWidth() + " for text " + needed);
                require(shown > 0 || margins(chip) == 0, "width " + width + ": the first chip shown has a margin of " + margins(chip));
                View icon = (View) field(chips.get(i), "icon");
                if (shown == 1) { sawTwo = true; if (icon != null && icon.getVisibility() == View.GONE) sawCompactTwo = true; }
                shown++;
            }
            require(used <= width, "width " + width + ": chips and gaps need " + used);
        }
        require(sawTwo && sawCompactTwo, "the sweep never reached two compact chips");
        row.requestLayout();
    }

    private static int margins(View child) {
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        return p.leftMargin + p.rightMargin;
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    /** Runs on the main thread and fails this run, rather than crashing the process, on anything thrown. */
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m) { long end = SystemClock.uptimeMillis() + 10000; while (!c.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); if (!c.getAsBoolean()) throw new IllegalStateException(m); }
    private static Object field(Object target, String name) { try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    private static void invoke(Object target, String name) { try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }
}
