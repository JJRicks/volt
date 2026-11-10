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
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * The battery gauge follows the reading wherever the rider puts it (Focus and the highlight spot of Halo and Trace). For each
 * Focus slot, at 344 dp and wide, portrait and landscape, at normal and large text, it records whether the gauge shows and that
 * it sits whole inside its cell, and checks a cell without the battery holds no gauge at all. The default layout's geometry is
 * written out so a build without this change can be compared with it ({@code -e only default}). Simulator only.
 */
public final class RidingBatteryGaugeValidation extends Instrumentation {
    private static final String[] FOCUS_SLOTS = {"big", "highlight", "tile_1", "tile_2", "tile_3", "tile_4"};
    private static final int[][] WINDOWS = {{344, 882}, {850, 1100}, {882, 344}, {1100, 850}};
    private MainActivity activity;
    private String profileId, immersivePrevious;
    private File evidence;
    private final List<String> notes = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    private Bundle arguments;

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
            if (!(hw.contains("ranchu") || hw.contains("goldfish"))) throw new IllegalStateException("emulator only");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "riding-battery-gauge");
            if (!evidence.isDirectory() && !evidence.mkdirs()) throw new IllegalStateException("evidence folder");
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            shell("wm density 480");
            if (!prefs().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true).commit())
                throw new IllegalStateException("settings");
            main(() -> {
                OnboardingScreen.markDone(getTargetContext());
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic gauge scooter", "0F1E2D3C4B5A", 0);
                p.mac = "C0:FF:EE:00:00:08"; p.lastConnected = System.currentTimeMillis() - 60000; profiles.update(p);
                profileId = p.id;
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch"); settle();
            main(() -> { if (!activity.service().setSimulation(true)) throw new IllegalStateException("simulation"); }); settle();
            await(() -> activity.service().ble.ready(), "simulation ready");
            main(() -> activity.openRiding()); settle();
            await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding did not open");
            if ("default".equals(arguments.getString("only"))) defaults();
            else sweep();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            try { java.nio.file.Files.write(new File(evidence, "notes.txt").toPath(), notes); } catch (Exception ignored) { }
            if (activity != null) try { main(() -> { invoke(activity, "closeRiding"); activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            try {
                prefs().edit().remove("text_scale").remove("riding_layout_v1_" + profileId).commit();
                shell("wm size reset"); shell("wm density reset");
                shell(immersivePrevious == null || immersivePrevious.isEmpty() || immersivePrevious.equals("null") ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious);
            } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Default layout geometry, for comparing a build without this change. */
    private void defaults() throws Exception {
        for (int[] window : new int[][]{{344, 882}, {850, 1100}, {882, 344}}) {
            setup(null, null, window, 1f);
            for (Object cell : cells()) {
                View v = (View) cell;
                notes.add("default " + window[0] + "x" + window[1] + " " + field(cell, "content") + " " + v.getWidth() + "x" + v.getHeight() + "@" + v.getLeft() + "," + v.getTop());
            }
            shot("default-" + window[0] + "x" + window[1]);
        }
    }

    private void sweep() throws Exception {
        for (int[] window : WINDOWS) for (float text : new float[]{1f, 1.5f}) {
            setup(null, null, window, text);
            check("default", "tile_1", window, text);
            for (String slot : FOCUS_SLOTS) {
                setup("focus", slot, window, text);
                check("focus", slot, window, text);
            }
        }
        // Halo's and Trace's range spot (the spot they build the same way).
        for (String frame : new String[]{"halo", "trace"}) for (int[] window : new int[][]{{344, 882}, {882, 344}}) {
            setup(frame, "highlight", window, 1f);
            check(frame, "highlight", window, 1f);
        }
        bands();
    }

    private void setup(String frame, String slot, int[] window, float text) throws Exception {
        shell("wm size " + (window[0] * 3) + "x" + (window[1] * 3));
        boolean scaleSet = prefs().edit().putFloat("text_scale", text).commit();
        if (!scaleSet) throw new IllegalStateException("text scale");
        SharedPreferences.Editor e = prefs().edit();
        if (frame == null) e.remove("riding_layout_v1_" + profileId);
        else e.putString("riding_layout_v1_" + profileId, layout(frame, slot));
        if (!e.commit()) throw new IllegalStateException("layout");
        SystemClock.sleep(1200);
        await(() -> activity.getWindow().getDecorView().getWidth() == window[0] * 3, "window width");
        main(() -> activity.rebuild());
        settle();
        await(() -> "RidingScreen".equals(field(activity, "screen").getClass().getSimpleName()), "riding gone");
        main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); });
        settle(); SystemClock.sleep(500); settle();
    }

    private String layout(String frame, String slot) {
        // A moved battery leaves another reading in tile_1 (Focus) or highlight's range, so nothing else shows it.
        StringBuilder spots = new StringBuilder("\"" + slot + "\":\"battery\"");
        if (frame.equals("focus") && !slot.equals("tile_1")) spots.append(",\"tile_1\":\"voltage\"");
        if (!frame.equals("focus") && !slot.equals("tile_2")) spots.append(",\"tile_2\":\"voltage\"");
        return "{\"v\":1,\"frame\":\"" + frame + "\",\"frames\":{\"" + frame + "\":{\"spots\":{" + spots + "}}}}";
    }

    private void check(String frame, String slot, int[] window, float text) throws Exception {
        String label = frame + "-" + slot + "-" + window[0] + "x" + window[1] + (text > 1 ? "-large" : "");
        Object found = null;
        for (Object cell : cells()) {
            if ("battery".equals(field(cell, "content"))) found = cell;
            else if (frame.equals("focus") || frame.equals("default")) {
                require(field(cell, "gauge") == null, label + ": " + field(cell, "content") + " holds a gauge");
                View number = (View) field(cell, "number");
                if (number.getWidth() > 0) require(number.getWidth() == ((View) cell).getWidth(), label + ": " + field(cell, "content") + "'s number is short of its cell");
            }
        }
        if (found == null) {
            require(slot.equals("tile_3") && text > 1, label + ": no battery cell");
            notes.add(label + " absent (tile_3 drops at large text)");
            shot(label);
            return;
        }
        Object gauge = field(found, "gauge");
        require(gauge != null, label + ": the battery cell has no gauge");
        View g = (View) gauge, cell = (View) found;
        boolean shown = g.getVisibility() == View.VISIBLE;
        if (shown) {
            require(g.getWidth() > 0 && g.getHeight() > 0, label + ": gauge has no size");
            int[] at = new int[2], in = new int[2];
            g.getLocationOnScreen(at); cell.getLocationOnScreen(in);
            // Beside the number the segments' fixed height may hang over a short row a little (clipped, as they always were).
            int slack = g.getHeight() > ((View) field(found, "number")).getHeight() ? (g.getHeight() - ((View) field(found, "number")).getHeight()) / 2 + 1 : 0;
            require(at[1] >= in[1] - slack && at[1] + g.getHeight() <= in[1] + cell.getHeight() + slack, label + ": gauge outside its cell");
            require(at[0] >= in[0] && at[0] + g.getWidth() <= in[0] + cell.getWidth(), label + ": gauge outside its cell horizontally");
            View number = (View) field(found, "number");
            require(number.getHeight() > 0 && number.getWidth() > 0, label + ": number has no room");
        }
        View num = (View) field(found, "number");
        notes.add(label + " " + g.getClass().getSimpleName() + " " + (shown ? "shown" : "dropped") + " cell=" + cell.getWidth() + "x" + cell.getHeight() + " number=" + num.getWidth() + "x" + num.getHeight() + " ems=" + java.util.Arrays.toString((float[]) field(found, "referenceEms")));
        android.util.Log.i("VoltGauge", notes.get(notes.size() - 1));
        require(shown == expected(frame, slot, window, text), label + ": gauge " + (shown ? "shown" : "dropped") + " unexpectedly");
        shot(label);
    }

    /** Where the gauge must show. A portrait or wide window keeps it in every slot; a phone sideways only has room beside the number at normal text. */
    private boolean expected(String frame, String slot, int[] window, float text) {
        boolean sideways = window[0] > window[1], shortWindow = sideways && window[1] < 500;
        boolean tall = !(sideways && text > 1);
        if (frame.equals("default") || slot.equals("tile_1")) return tall || !shortWindow;
        // Halo's and Trace's range spot: the bar fits Halo portrait; Trace's card is short enough that the number keeps the room.
        if (!frame.equals("focus")) return frame.equals("halo") && !sideways;
        if (shortWindow) return slot.equals("highlight") && text == 1;
        return !(sideways && text > 1 && (slot.equals("big") || slot.startsWith("tile")));
    }

    /** The gauge colour follows the battery band and the bar empties with it, in a moved cell. */
    private void bands() throws Exception {
        setup("focus", "tile_2", WINDOWS[0], 1f);
        script("LOW_AMBER");
        Object gauge = field(batteryCell(), "gauge");
        require(((Number) field(gauge, "color")).intValue() == ((Number) field(activity.ui(), "orange")).intValue(), "amber band");
        shot("focus-tile_2-amber");
        script("LOW_RED");
        require(((Number) field(field(batteryCell(), "gauge"), "color")).intValue() == ((Number) field(activity.ui(), "danger")).intValue(), "red band");
        shot("focus-tile_2-red");
    }
    private void script(String name) throws Exception {
        main(() -> {
            try {
                Object sim = field(activity.service().ble, "simulation");
                Field constant = sim.getClass().getDeclaredField(name); constant.setAccessible(true);
                Field script = sim.getClass().getDeclaredField("script"); script.setAccessible(true); script.set(sim, constant.get(null));
                activity.service().ble.tickSimulation();
                activity.redraw();
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
        settle(); SystemClock.sleep(400); settle();
    }
    private Object batteryCell() { for (Object c : cells()) if ("battery".equals(field(c, "content"))) return c; throw new AssertionError("no battery cell"); }
    @SuppressWarnings("unchecked") private List<Object> cells() { return (List<Object>) field(field(activity, "screen"), "cells"); }

    private void shot(String name) throws Exception {
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("screenshot");
        File image = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
        TextCensus.write(this, image);
    }
    private String shell(String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            for (String line; (line = r.readLine()) != null; ) out.append(line).append('\n');
        }
        return out.toString();
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m) { long end = SystemClock.uptimeMillis() + 10000; while (!c.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); if (!c.getAsBoolean()) throw new IllegalStateException(m); }
    private static Object field(Object target, String name) { try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    private static void invoke(Object target, String name) { try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }
}
