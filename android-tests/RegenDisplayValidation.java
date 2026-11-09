package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.RideChartData;
import com.jjricks.volt.RideChartView;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Inferred regen on a scooter that doesn't sign its current (owner, Nov 9): ride mode's power on
 * the regen side in every frame, and a saved unsigned ride's graph below zero with its energy rows unavailable;
 * a signed ride stays exactly as measured. Synthetic only. Disposable emulator only.
 */
public final class RegenDisplayValidation extends Instrumentation {
    private MainActivity activity;
    private Object screen;
    private final Bundle result = new Bundle();
    private final ArrayList<String> notes = new ArrayList<>();
    private File evidence;
    private int assertions;

    @Override public void onCreate(Bundle args) { start(); }

    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "regen-display");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                        .putBoolean("developer_options", true).commit();
                new ScooterProfiles(getTargetContext()).create("Synthetic regen fixture", "000000000000", 0);
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready", 8000);
            ridesCheck();
            rideModeUnsigned();
            rideModeSigned();
            result.putString("result", "PASS");
        } catch (Throwable t) {
            result.putString("result", "FAIL");
            result.putString("failure", String.valueOf(t));
        } finally {
            result.putInt("assertions", assertions);
            result.putStringArrayList("notes", notes);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Saved rides: the same fixture, once unsigned and once signed with real regen. */
    private void ridesCheck() throws Exception {
        ScooterService service = activity.service();
        RideStore store = new RideStore(new File(getTargetContext().getFilesDir(), "rides"));
        store.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        long wall = System.currentTimeMillis() - 3_600_000;
        String unsigned = seed(store, "Unsigned fixture", wall, false);
        String signed = seed(store, "Signed fixture", wall - 1_000_000, true);
        main(() -> {
            setField(service, "rides", store);
            service.settings.edit().putStringSet("ride_chart_series", new LinkedHashSet<>(Arrays.asList("power_w", "speed_kmh"))).commit();
            call(activity, "select", new Class<?>[]{String.class}, "rides");
        });
        settle();
        SystemClock.sleep(2500); // the Rides list loads off the main thread
        screen = fieldOn(activity, "screen");
        main(() -> call(screen, "showDetails", new Class<?>[]{String.class}, unsigned));
        settle();
        shot("rides-unsigned-open");
        int negative = negatives(powerValues());
        note("unsigned ride negative power points: " + negative);
        require(negative >= 4, "An unsigned ride's power line should go below zero on the brake: " + negative);
        shot("rides-unsigned");
        energyRows("rides-unsigned-energy", true);
        main(() -> call(screen, "showDetails", new Class<?>[]{String.class}, signed));
        settle();
        SystemClock.sleep(1500);
        int signedNegative = negatives(powerValues());
        note("signed ride negative power points: " + signedNegative);
        require(signedNegative >= 4, "A signed ride shows its own regen");
        require(signedNegative == 6, "A signed ride plots its stored values, no more: " + signedNegative);
        shot("rides-signed");
        energyRows("rides-signed-energy", false);
    }

    /** Scrolls the open details to the energy card, reads its rows and takes a picture. */
    private void energyRows(String name, boolean unsigned) throws Exception {
        AtomicReference<List<String>> rows = new AtomicReference<>();
        main(() -> {
            List<String> all = new ArrayList<>();
            collect(root(), all);
            int at = -1;
            for (int i = 0; i < all.size() && at < 0; i++) if (all.get(i).startsWith("Battery draw")) at = i;
            require(at >= 0, "No draw row in " + all);
            rows.set(new ArrayList<>(all.subList(Math.max(0, at - 2), Math.min(all.size(), at + 8))));
            View draw = text(root(), "Battery draw");
            require(draw != null, "No draw label");
            draw.requestRectangleOnScreen(new android.graphics.Rect(0, 0, draw.getWidth(), draw.getHeight() + activity.ui().dp(160)), true);
        });
        note(name + ": " + rows.get());
        String joined = String.join(" | ", rows.get());
        if (unsigned) require(joined.matches(".*Battery draw[^|]*\\| \u2014 \\| Battery input[^|]*\\| \u2014.*"), name + ": draw and input stay unavailable: " + joined);
        else require(joined.matches(".*Battery draw[^|]*(\\| )?[^|]*[0-9.]+ Wh.*"), name + ": the draw row should show an amount: " + joined);
        if (unsigned) require(joined.contains("Direction ?"), name + ": the not-confirmed chip stays: " + joined);
        settle();
        shot(name);
    }
    private View root() {
        List<?> dialogs = (List<?>) fieldOn(screen, "dialogs");
        return dialogs.isEmpty() ? activity.getWindow().getDecorView() : ((android.app.AlertDialog) dialogs.get(dialogs.size() - 1)).getWindow().getDecorView();
    }
    private static View text(View view, String wanted) {
        if (view instanceof TextView && wanted.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View hit = text(((ViewGroup) view).getChildAt(i), wanted); if (hit != null) return hit; }
        return null;
    }

    private String seed(RideStore store, String label, long wall, boolean signed) throws Exception {
        String id = store.start("fixture", label, wall, 1000);
        long t = 0;
        double rest = 50, r = 0.08, kmh = 0;
        for (int i = 0; i < 8; i++) sample(store, wall, t++, signed ? 0.2 : 0.2, rest, 0, signed);
        for (int i = 0; i < 10; i++) { kmh += 3; sample(store, wall, t++, 18, rest - 18 * r, kmh, signed); }
        for (int i = 0; i < 10; i++) sample(store, wall, t++, 0.4, rest, kmh, signed);
        for (int i = 0; i < 6; i++) { kmh -= 3.2; sample(store, wall, t++, signed ? -8 : 8, rest + 8 * r, kmh, signed); }
        for (int i = 0; i < 10; i++) sample(store, wall, t++, 0.3, rest, kmh, signed);
        store.stop(wall + t * 1000, 1000 + t * 1000);
        return id;
    }
    private static void sample(RideStore store, long wall, long t, double amps, double volts, double kmh, boolean signed) throws Exception {
        long mono = 1000 + t * 1000;
        Map<String, RideStore.Sample> row = new LinkedHashMap<>();
        row.put("voltage_v", new RideStore.Sample(volts, "", "V", 0x72, mono));
        row.put("current_a", new RideStore.Sample(amps, "", "A", 0x72, mono));
        row.put("speed_kmh", new RideStore.Sample(kmh, mono));
        row.put("current_sign_state", new RideStore.Sample(signed ? 0d : 254d, mono));
        row.put("battery_percent", new RideStore.Sample(80d, mono));
        store.sample(wall + t * 1000, mono, row);
    }
    @SuppressWarnings("unchecked")
    private List<RideChartData.Value> powerValues() {
        // The details load off the main thread: wait for the chart and its data.
        await(() -> { View chart = find(root(), RideChartView.class); if (chart == null) return false;
            View v = chart; while (v != null && !v.getClass().getName().contains("ChartPanel")) v = (View) v.getParent();
            return v != null && fieldOn(v, "data") != null; }, "Chart never loaded", 12000);
        AtomicReference<List<RideChartData.Value>> out = new AtomicReference<>();
        main(() -> {
            View chart = find(root(), RideChartView.class);
            require(chart != null, "No chart");
            View v = chart;
            while (v != null && !v.getClass().getName().contains("ChartPanel")) v = (View) v.getParent();
            require(v != null, "No chart panel");
            List<RideChartData.Series> data = (List<RideChartData.Series>) fieldOn(v, "data");
            out.set(data.get(0).values);
        });
        return out.get();
    }
    private static int negatives(List<RideChartData.Value> values) {
        int n = 0;
        for (RideChartData.Value v : values) if (v.value != null && v.value < 0) n++;
        return n;
    }

    private void rideModeUnsigned() throws Exception {
        main(() -> { for (Object d : new ArrayList<>((List<?>) fieldOn(screen, "dialogs"))) ((android.app.AlertDialog) d).dismiss(); });
        settle();
        main(() -> {
            require(activity.service().setSimulation(true), "Simulation on failed");
            activity.service().ble.setSimulationScript("unsigned_regen");
        });
        await(() -> reading("speed_kmh") != null, "No simulated readings", 8000);
        for (String frame : new String[]{"focus", "halo", "trace"}) {
            setFrame(frame);
            openRiding();
            SystemClock.sleep(frame.equals("focus") ? 1000 : 500);
            await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())), "Riding mode never showed inferred regen on " + frame, 45000);
            AtomicReference<Object[]> power = new AtomicReference<>();
            main(() -> { Object p = power(); power.set(new Object[]{fieldOn(p, "watts"), fieldOn(p, "peakRegenW"), texts()}); });
            Double watts = (Double) power.get()[0]; double peak = (Double) power.get()[1];
            note(frame + ": watts=" + watts + " peakRegen=" + peak + " screen=" + power.get()[2]);
            require(watts != null && watts < 0, frame + ": inferred regen should read below zero: " + watts);
            require(peak > 0, frame + ": no regen peak");
            SystemClock.sleep(500);
            shot("riding-inferred-" + frame);
            if (frame.equals("trace")) {
                AtomicReference<Object> trace = new AtomicReference<>();
                main(() -> trace.set(call(values(), "powerTrace", new Class<?>[]{long.class, long.class}, SystemClock.elapsedRealtime(), 60_000L)));
                boolean below = false;
                for (Object point : (List<?>) trace.get()) below |= (double) fieldOn(point, "value") < 0;
                require(below, "The trace should go below zero");
            }
            main(() -> activity.onBackPressed());
            await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
            settle();
        }
    }

    private void rideModeSigned() throws Exception {
        main(() -> activity.service().ble.setSimulationScript("riding"));
        setFrame("focus");
        openRiding();
        await(() -> "REGEN".equals(String.valueOf(direction())), "A signed ride should reach confirmed regen", 60000);
        AtomicReference<Object> p = new AtomicReference<>();
        main(() -> p.set(power()));
        note("signed: direction REGEN, watts=" + fieldOn(p.get(), "watts"));
        require(!"REGEN_INFERRED".equals(String.valueOf(fieldOn(p.get(), "direction"))), "A signed scooter never infers");
        SystemClock.sleep(500);
        shot("riding-signed-regen");
        main(() -> activity.onBackPressed());
    }

    private void setFrame(String frame) {
        main(() -> {
            Object profile;
            try {
                Class<?> layoutClass = Class.forName("com.jjricks.volt.RidingLayout");
                Method parse = layoutClass.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
                Object layout = parse.invoke(null, (Object) null);
                Method setFrame = layoutClass.getDeclaredMethod("setFrame", String.class); setFrame.setAccessible(true);
                setFrame.invoke(layout, frame);
                Method toJson = layoutClass.getDeclaredMethod("toJson"); toJson.setAccessible(true);
                String json = (String) toJson.invoke(layout);
                profile = call(activity.service(), "activeProfileId", new Class<?>[0]);
                prefs("app_settings").edit().putString("riding_layout_v1_" + profile, json).commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private void openRiding() {
        main(() -> activity.openRiding());
        await(() -> fieldOn(activity, "riding") != null, "Riding mode did not open", 8000);
    }
    private Object session() { return fieldOn(activity, "riding"); }
    private Object values() { return fieldOn(session(), "values"); }
    private Object power() { return call(values(), "power", new Class<?>[]{long.class}, SystemClock.elapsedRealtime()); }
    private Object direction() { return call(values(), "direction", new Class<?>[0]); }
    private Double reading(String key) {
        Object telemetry = fieldOn(activity.service().ble, "telemetry");
        return (Double) call(telemetry, "number", new Class<?>[]{String.class}, key);
    }

    private List<String> texts() {
        List<String> out = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), out);
        return out;
    }
    private static void collect(View view, List<String> out) {
        if (!view.isShown()) return;
        CharSequence said = view.getContentDescription();
        if (said != null && said.length() > 0) out.add(said.toString());
        if (view instanceof TextView) { CharSequence t = ((TextView) view).getText(); if (t != null && t.length() > 0) out.add(t.toString()); }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }
    private static View find(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View hit = find(((ViewGroup) view).getChildAt(i), type); if (hit != null) return hit; }
        return null;
    }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null, "Screenshot failed");
        File image = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        TextCensus.write(this, image);
    }

    private void note(String text) { notes.add(text); }
    private void require(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get());
    }
    private void await(BooleanSupplier condition, String message, long timeoutMs) {
        long until = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < until) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError(message);
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(700); }
    private static Object fieldOn(Object target, String name) {
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException ignored) { }
            }
            throw new NoSuchFieldException(name);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void setField(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
