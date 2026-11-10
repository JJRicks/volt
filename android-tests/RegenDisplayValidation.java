package com.jjricks.volt.tests;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import com.jjricks.volt.BleController;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.RideChartData;
import com.jjricks.volt.RideChartView;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

import java.io.File;
import java.io.FileInputStream;
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
            signArrives();
            cueLadder();
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
        chartDirection("rides-unsigned-chart", true);
        energyRows("rides-unsigned-energy", true);
        main(() -> call(screen, "showDetails", new Class<?>[]{String.class}, signed));
        settle();
        SystemClock.sleep(1500);
        int signedNegative = negatives(powerValues());
        note("signed ride negative power points: " + signedNegative);
        require(signedNegative >= 4, "A signed ride shows its own regen");
        require(signedNegative == 6, "A signed ride plots its stored values, no more: " + signedNegative);
        shot("rides-signed");
        chartDirection("rides-signed-chart", false);
        energyRows("rides-signed-energy", false);
    }

    /** The Power toggle, the chart's description and the inspected value name an unconfirmed direction, and only on an unsigned ride. */
    private void chartDirection(String name, boolean unsigned) throws Exception {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        AtomicReference<String> described = new AtomicReference<>();
        List<RideChartData.Value> values = powerValues();
        main(() -> {
            List<String> all = new ArrayList<>();
            collect(root(), all);
            seen.set(all);
            View chart = find(root(), RideChartView.class);
            double at = Double.NaN;
            if (unsigned) for (RideChartData.Value v : values) if (v.value != null && v.value < 0) at = v.time;
            if (unsigned) {
                setFieldOn(chart, "inspectedTime", at);
                call(chart, "notifyInspection", new Class<?>[0]);
                collect(root(), seen.get());
            }
            described.set(String.valueOf(chart.getContentDescription()));
        });
        String joined = String.join(" | ", seen.get());
        note(name + ": " + described.get() + " | cells: " + joined);
        if (unsigned) {
            require(joined.contains("Power · Direction ?"), name + ": the Power toggle should carry the chip: " + joined);
            require(described.get().contains("Power · Direction ?: "), name + ": the chart's description should name it: " + described.get());
            require(described.get().matches(".*Power · Direction \\?: [-−].*"), name + ": at a regen point the value stays below zero: " + described.get());
            require(joined.contains("Power · Direction ?: "), name + ": the inspected cell's spoken text should name it: " + joined);
        } else {
            require(!joined.contains("Power · Direction"), name + ": a signed ride's power stays plain: " + joined);
            require(!described.get().contains("Direction ?"), name + ": a signed chart has no direction note: " + described.get());
        }
        settle();
        shot(name);
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
        if (unsigned) require(joined.contains("Battery draw | Not measured") && joined.contains("Battery input / charging | Not measured") && !joined.contains(" Wh"), name + ": draw and input stay unavailable: " + joined);
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
            main(() -> { Object p = power(); power.set(new Object[]{fieldOn(p, "watts"), fieldOn(p, "peakInferredRegenW"), texts(), fieldOn(p, "peakRegenW")}); });
            Double watts = (Double) power.get()[0]; double peak = (Double) power.get()[1], confirmedPeak = (Double) power.get()[3];
            note(frame + ": watts=" + watts + " peakInferredRegen=" + peak + " peakRegen=" + confirmedPeak + " screen=" + power.get()[2]);
            require(watts != null && watts < 0, frame + ": inferred regen should read below zero: " + watts);
            require(peak > 0, frame + ": no inferred regen peak");
            require(confirmedPeak == 0, frame + ": an unsigned scooter has no confirmed regen peak: " + confirmedPeak);
            SystemClock.sleep(500);
            caption(frame, true);
            spokenPower(frame, true);
            shot("riding-inferred-" + frame);
            if (frame.equals("focus")) barColor(false);
            if (frame.equals("trace")) {
                AtomicReference<Object> trace = new AtomicReference<>();
                main(() -> trace.set(call(values(), "powerTrace", new Class<?>[]{long.class, long.class}, SystemClock.elapsedRealtime(), 60_000L)));
                boolean below = false;
                for (Object point : (List<?>) trace.get()) below |= (double) fieldOn(point, "value") < 0;
                require(below, "The trace should go below zero");
            }
            if (frame.equals("focus")) connectionBoundary();
            main(() -> activity.onBackPressed());
            await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
            settle();
        }
        setFrame("halo", true);
        openRiding();
        await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())), "No inferred regen for the power ring", 45000);
        ringColor(false);
        SystemClock.sleep(300);
        shot("riding-inferred-halo-power-ring");
        main(() -> activity.onBackPressed());
        await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
        settle();
        setFrame("halo", false);
    }

    /**
     * Inferred regen never shows a minus sign without a cue: the readout's caption and a POWER tile climb the
     * ladder (full caption, then "Direction ?", then an unsigned muted number) at three widths, and the
     * spoken text always says the direction is unknown. 640 x 360 dp landscape is the width Codex measured.
     */
    private void cueLadder() throws Exception {
        boolean[] seenFull = {false}, seenCue = {false};
        try {
            cueCheck("default", null, seenFull, seenCue);
            shell("wm size 903x2004");
            settle();
            cueCheck("344dp", null, seenFull, seenCue);
            shell("wm size 945x1680");
            main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            SystemClock.sleep(2500);
            settle();
            cueCheck("640dp-landscape", "Direction ?", seenFull, seenCue);
            bareNumber();
        } finally {
            main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
            shell("wm size reset");
            SystemClock.sleep(2500);
            settle();
        }
        require(seenFull[0] && seenCue[0], "Both the full caption and the cue should have been reached across the widths");
    }

    private void cueCheck(String name, String expected, boolean[] seenFull, boolean[] seenCue) throws Exception {
        setPowerSpots("halo", "tile_1", "strip_1");
        openRiding();
        // Each pass polls until a refresh has drawn the inferred reading, so the checks read one consistent frame.
        for (int attempt = 0; ; attempt++) {
            AtomicReference<String> problem = new AtomicReference<>();
            AtomicReference<String> caption = new AtomicReference<>();
            await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())) && inferredDrawn(), name + ": no inferred reading on screen", 45000);
            main(() -> { caption.set(checkDrawn(name, problem)); });
            if (problem.get() != null) throw new AssertionError(problem.get());
            if (attempt == 0) spokenPower(name, true);
            SystemClock.sleep(250); // Let the checked frame reach the screen: idle isn't drawn.
            shot("riding-cue-" + name);
            // The direction can change between readings: only a frame still inferred after the shot counts as this rung's picture.
            AtomicReference<Boolean> still = new AtomicReference<>(false);
            main(() -> still.set(inferredDrawn() && captionText().equals(caption.get())));
            if (still.get() || attempt >= 40) {
                require(still.get(), name + ": never held an inferred frame across a screenshot");
                note(name + " caption \"" + caption.get() + "\" " + Arrays.toString(cellStates()));
                if (expected != null) require(expected.equals(caption.get()), name + ": expected the caption " + expected + ", got " + caption.get());
                seenFull[0] |= FULL.equals(caption.get());
                seenCue[0] |= CUE.equals(caption.get());
                require(!PLAIN.equals(caption.get()), name + ": the plain caption should not be needed at this width");
                break;
            }
            SystemClock.sleep(300);
        }
        main(() -> activity.onBackPressed());
        await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
        settle();
    }

    /** With a real view squeezed so no cue fits, and with a label hidden, the number drops its sign and stays muted. */
    private void bareNumber() throws Exception {
        setPowerSpots("halo", "big");
        openRiding();
        await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())) && inferredDrawn(), "big: no inferred reading on screen", 45000);
        SystemClock.sleep(1200);
        await(() -> inferredDrawn(), "big: inferred reading gone", 45000);
        main(() -> {
            View cell = powerCell(true);
            require(cell != null, "No hidden-label power cell");
            require(unsignedMain(cell), "A power number with its label hidden must not show a minus sign: " + cellState(cell));
            require(said(cell).contains("direction unknown"), "The hidden-label cell still speaks the uncertainty: " + said(cell));
        });
        spokenPower("hidden-label", true);
        shot("riding-cue-hidden-label");
        main(() -> activity.onBackPressed());
        await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
        settle();
        setPowerSpots("halo", "tile_1");
        openRiding();
        await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())) && inferredDrawn(), "squeeze: no inferred reading on screen", 45000);
        main(() -> {
            TextView caption = (TextView) fieldOn(screen(), "wattsCaption");
            // Room for the plain word only, so the rung shows as the rider would see it.
            caption.setPadding(0, 0, caption.getWidth() - Math.round(caption.getPaint().measureText(PLAIN)) - 4, 0);
            View cell = powerCell(false);
            TextView label = (TextView) fieldOn(cell, "label");
            label.setPadding(label.getWidth() / 2, 0, label.getWidth() / 2, 0);
            call(label, "fit", new Class<?>[0]);
        });
        SystemClock.sleep(1500);
        await(() -> inferredDrawn() && captionText().equals(PLAIN), "The squeezed caption should fall to the plain label", 45000);
        main(() -> {
            TextView caption = (TextView) fieldOn(screen(), "wattsCaption");
            require(unsigned(numberMain(fieldOn(screen(), "watts"))), "No cue fits: the readout must drop its minus sign: " + numberMain(fieldOn(screen(), "watts")));
            require(said(readout()).contains(FULL), "Spoken text keeps the uncertainty: " + said(readout()));
            require(caption.getCurrentTextColor() == captionMuted(), "The bare caption keeps its muted colour");
            require((Integer) fieldOn(fieldOn(screen(), "watts"), "color") == captionMuted(), "The unsigned number is in the muted colour");
            View cell = powerCell(false);
            require(unsignedMain(cell), "No cue fits: the tile must drop its minus sign: " + cellState(cell));
            require(said(cell).contains("direction unknown"), "The tile still speaks the uncertainty: " + said(cell));
        });
        spokenPower("no-room", true);
        shot("riding-cue-no-room");
        main(() -> activity.onBackPressed());
        await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
        settle();
        setFrame("halo", false);
    }

    private static final String FULL = "power (direction unknown)", CUE = "Direction ?", PLAIN = "power";

    /**
     * Reads the accessibility node tree as a screen reader gets it (views not important for accessibility left
     * out), and for the power readout and every power cell on screen finds the node at that view's bounds:
     * one node must carry the watts exactly as drawn and the note (the unknown direction when inferred, the
     * regen caption when signed), and nothing inside the readout may speak on its own. Readings move about once
     * a second, so a pass counts only when the drawn values were the same before and after the tree was read.
     */
    private void spokenPower(String name, boolean inferred) throws Exception {
        UiAutomation automation = getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        if ((info.flags & AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0) {
            info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(info);
        }
        String failure = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            AtomicReference<List<Object[]>> before = new AtomicReference<>(), after = new AtomicReference<>();
            AtomicReference<String> direction = new AtomicReference<>();
            main(() -> { before.set(powerSurfaces()); direction.set(String.valueOf(direction())); });
            List<Object[]> nodes = new ArrayList<>();
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) nodes(root, nodes);
            main(() -> after.set(powerSurfaces()));
            // Only a frame in the direction under test counts: signed regen, or inferred regen.
            if (!sameSurfaces(before.get(), after.get()) || root == null || !direction.get().equals(inferred ? "REGEN_INFERRED" : "REGEN")) {
                SystemClock.sleep(150);
                continue;
            }
            failure = null;
            boolean readoutSeen = false;
            for (Object[] surface : before.get()) {
                Rect bounds = (Rect) surface[0];
                String shown = (String) surface[1], kind = (String) surface[2];
                readoutSeen |= kind.equals("readout");
                String spoken = null;
                int inside = 0;
                for (Object[] node : nodes) {
                    Rect at = (Rect) node[0];
                    if (at.equals(bounds) && node[1] != null) spoken = (String) node[1];
                    else if (kind.equals("readout") && bounds.contains(at) && !at.equals(bounds) && node[1] != null) inside++;
                }
                if (spoken == null) { failure = name + ": no accessibility node for the " + kind + " at " + bounds; break; }
                boolean hasValue = spoken.contains(shown), hasNote = inferred ? spoken.contains(FULL) : spoken.contains("regen") && !spoken.contains("direction unknown");
                if (!hasValue || !hasNote) { failure = name + ": the " + kind + " node says \"" + spoken + "\" for the drawn \"" + shown + "\""; break; }
                if (inside > 0) { failure = name + ": " + inside + " node(s) inside the readout speak apart from it"; break; }
                note(name + " " + kind + " node: \"" + spoken + "\"");
            }
            if (failure == null && !readoutSeen) failure = name + ": the power readout wasn't on screen";
            if (failure == null) { assertions++; return; }
            SystemClock.sleep(200);
        }
        throw new AssertionError(failure == null ? name + ": the drawn values never held still for the node read" : failure);
    }
    /** Each power surface on screen: its bounds, the watts as drawn (prefix, number, unit), and which it is. */
    private List<Object[]> powerSurfaces() {
        List<Object[]> out = new ArrayList<>();
        View readout = readout();
        if (readout.isShown()) out.add(new Object[]{screenRect(readout), drawn(fieldOn(screen(), "watts")), "readout"});
        for (Object cell : (List<?>) fieldOn(screen(), "cells")) {
            if (!"power".equals(fieldOn(cell, "content")) || !((View) cell).isShown()) continue;
            out.add(new Object[]{screenRect((View) cell), drawn(fieldOn(cell, "number")), "cell"});
        }
        return out;
    }
    private static boolean sameSurfaces(List<Object[]> a, List<Object[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }
    private static String drawn(Object number) {
        return String.valueOf(fieldOn(number, "prefix")) + fieldOn(number, "main") + fieldOn(number, "fraction") + fieldOn(number, "suffix");
    }
    private static Rect screenRect(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }
    /** Every node a screen reader can reach: its screen bounds and what it says (description, else text). */
    private static void nodes(AccessibilityNodeInfo node, List<Object[]> out) {
        node.refresh();
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            CharSequence said = node.getContentDescription() != null ? node.getContentDescription() : node.getText();
            out.add(new Object[]{bounds, said == null || said.length() == 0 ? null : said.toString()});
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) nodes(child, out);
        }
    }

    private Object screen() { return fieldOn(activity, "screen"); }
    private View readout() { return (View) fieldOn(screen(), "power"); }
    private boolean inferredDrawn() { return said(readout()).contains(FULL); }
    private String captionText() {
        return ((TextView) fieldOn(screen(), "wattsCaption")).getText().toString();
    }
    private int captionMuted() { return ((TextView) fieldOn(screen(), "wattsCaption")).getCurrentTextColor(); }

    /** Returns the caption on show; records the first broken rule in problem. */
    private String checkDrawn(String name, AtomicReference<String> problem) {
        TextView caption = (TextView) fieldOn(screen(), "wattsCaption");
        String text = caption.getText().toString();
        Object watts = fieldOn(screen(), "watts");
        boolean cued = FULL.equals(text) || CUE.equals(text);
        if (!cued && !PLAIN.equals(text)) problem.set(name + ": unexpected caption " + text);
        if (cued == unsigned(numberMain(watts))) problem.set(name + ": caption \"" + text + "\" with number " + numberMain(watts));
        if (!said(readout()).contains(FULL)) problem.set(name + ": spoken readout " + said(readout()));
        // Measured, not assumed: a caption that shows must fit whole, never ellipsized.
        if (caption.getLayout() != null && caption.getLayout().getEllipsisCount(0) > 0) problem.set(name + ": caption ellipsized: " + text);
        for (Object cell : (List<?>) fieldOn(screen(), "cells")) {
            if (!"power".equals(fieldOn(cell, "content"))) continue;
            String state = cellState((View) cell);
            TextView label = (TextView) fieldOn(cell, "label");
            String labelText = label.getText().toString();
            boolean labelCued = FULL.equals(labelText) || CUE.equals(labelText);
            if (label.getVisibility() != View.VISIBLE) labelCued = false;
            if (labelCued == unsignedMain((View) cell)) problem.set(name + ": tile " + state);
            if (!said((View) cell).contains("direction unknown")) problem.set(name + ": tile spoken " + said((View) cell));
            if (label.getLayout() != null && label.getLayout().getEllipsisCount(0) > 0) problem.set(name + ": tile label ellipsized: " + state);
            if ((Integer) fieldOn(fieldOn(cell, "number"), "color") != captionMuted()) problem.set(name + ": tile number not muted: " + state);
        }
        return text;
    }
    private String[] cellStates() {
        AtomicReference<String[]> out = new AtomicReference<>();
        main(() -> {
            List<String> states = new ArrayList<>();
            for (Object cell : (List<?>) fieldOn(screen(), "cells")) if ("power".equals(fieldOn(cell, "content"))) states.add(cellState((View) cell));
            out.set(states.toArray(new String[0]));
        });
        return out.get();
    }
    private View powerCell(boolean hiddenLabel) {
        for (Object cell : (List<?>) fieldOn(screen(), "cells")) {
            if (!"power".equals(fieldOn(cell, "content"))) continue;
            boolean hidden = ((View) fieldOn(cell, "label")).getVisibility() != View.VISIBLE;
            if (hidden == hiddenLabel) return (View) cell;
        }
        return null;
    }
    private String cellState(View cell) { return "label=\"" + ((TextView) fieldOn(cell, "label")).getText() + "\" number=" + numberMain(fieldOn(cell, "number")) + " said=" + said(cell); }
    private static String numberMain(Object number) { return String.valueOf(fieldOn(number, "prefix")) + fieldOn(number, "main"); }
    private static boolean unsigned(String number) { return number.indexOf('-') < 0 && number.indexOf('\u2212') < 0; }
    private boolean unsignedMain(View cell) { return unsigned(numberMain(fieldOn(cell, "number"))); }
    private static String said(View view) { return String.valueOf(view.getContentDescription()); }
    private void shell(String command) throws Exception {
        ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { byte[] buffer = new byte[1024]; while (in.read(buffer) >= 0) { } }
    }
    private void setPowerSpots(String frame, String... spots) {
        main(() -> {
            try {
                Class<?> layoutClass = Class.forName("com.jjricks.volt.RidingLayout");
                Method parse = layoutClass.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
                Object layout = parse.invoke(null, (Object) null);
                Method setFrame = layoutClass.getDeclaredMethod("setFrame", String.class); setFrame.setAccessible(true);
                setFrame.invoke(layout, frame);
                Method setContent = layoutClass.getDeclaredMethod("setContent", String.class, String.class, String.class); setContent.setAccessible(true);
                for (String spot : spots) setContent.invoke(layout, frame, spot, "power");
                Method toJson = layoutClass.getDeclaredMethod("toJson"); toJson.setAccessible(true);
                Object profile = call(activity.service(), "activeProfileId", new Class<?>[0]);
                prefs("app_settings").edit().putString("riding_layout_v1_" + profile, (String) toJson.invoke(layout)).commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    /** The bar's below-zero fill: green only for regen the scooter confirmed (its pixels, so what draws is what's checked). */
    private void barColor(boolean confirmed) throws Exception {
        View bar = find(activity.getWindow().getDecorView(), Class.forName("com.jjricks.volt.RidingScreen$PowerBar"));
        require(bar != null, "No power bar");
        await(() -> { Object smoother = fieldOn(bar, "smoother"); return (double) call(smoother, "value", new Class<?>[]{long.class}, SystemClock.elapsedRealtime()) < -0.02; }, "The bar never went below zero", 60000);
        // Under load the direction can move on between the wait and the drawing: count only a frame drawn while it's still the one under test.
        String want = confirmed ? "REGEN" : "REGEN_INFERRED";
        AtomicReference<int[]> counts = new AtomicReference<>();
        for (int attempt = 0; attempt < 40 && counts.get() == null; attempt++) {
            if (attempt > 0) await(() -> { Object smoother = fieldOn(bar, "smoother"); return (double) call(smoother, "value", new Class<?>[]{long.class}, SystemClock.elapsedRealtime()) < -0.02; }, "The bar never went below zero again", 45000);
            main(() -> {
                if (!want.equals(String.valueOf(fieldOn(bar, "direction")))) return;
                if ((double) call(fieldOn(bar, "smoother"), "value", new Class<?>[]{long.class}, SystemClock.elapsedRealtime()) >= -0.02) return;
                Bitmap image = Bitmap.createBitmap(bar.getWidth(), bar.getHeight(), Bitmap.Config.ARGB_8888);
                bar.draw(new android.graphics.Canvas(image));
                int ok = (int) fieldOn(fieldOn(fieldOn(bar, "this$0"), "ui"), "ok"), green = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (image.getPixel(x, y) == ok) green++;
                counts.set(new int[]{green});
            });
            if (counts.get() == null) SystemClock.sleep(200);
        }
        require(counts.get() != null, "The bar never held " + want + " for a drawing");
        note("bar " + (confirmed ? "confirmed" : "inferred") + ": green pixels " + counts.get()[0]);
        require(confirmed ? counts.get()[0] > 0 : counts.get()[0] == 0, "Bar's regen fill, confirmed=" + confirmed + ": green pixels " + counts.get()[0]);
    }

    /** A power ring takes green only for confirmed regen. */
    private void ringColor(boolean confirmed) throws Exception {
        View ring = find(activity.getWindow().getDecorView(), Class.forName("com.jjricks.volt.RidingScreen$Ring"));
        require(ring != null, "No ring");
        int expected = (int) fieldOn(fieldOn(fieldOn(ring, "this$0"), "ui"), confirmed ? "ok" : "muted");
        await(() -> (int) fieldOn(ring, "color") == expected, "The power ring's colour, confirmed=" + confirmed, 60000);
        note("ring " + (confirmed ? "confirmed" : "inferred") + ": colour as expected");
    }

    /** The caption is the signal for inferred regen: the same wording as an unknown direction, never the regen one; confirmed regen keeps its own. */
    private void caption(String frame, boolean inferred) {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        main(() -> seen.set(texts()));
        String joined = String.join(" | ", seen.get());
        note(frame + " caption: " + joined);
        if (inferred) {
            require(seen.get().contains("power (direction unknown)") || seen.get().contains("power"), frame + ": inferred regen should use the unknown-direction caption: " + joined);
            require(!joined.contains("regen · peak") && !seen.get().contains("regen"), frame + ": inferred regen must not read as confirmed regen: " + joined);
        } else {
            require(joined.contains("regen · peak") || seen.get().contains("regen"), frame + ": confirmed regen keeps its caption: " + joined);
            require(!joined.contains("direction unknown"), frame + ": confirmed regen is not unknown: " + joined);
        }
    }

    /**
     * The real RidingScreen call: a connection boundary, seen by a later refresh, is recorded as the
     * connection's own start, and inference carries on in the new run of readings.
     */
    private void connectionBoundary() throws Exception {
        BleController ble = activity.service().ble;
        AtomicReference<Long> start = new AtomicReference<>();
        AtomicReference<Integer> segment = new AtomicReference<>();
        main(() -> {
            segment.set((Integer) fieldOn(values(), "inferenceSegment"));
            call(ble, "close", new Class<?>[]{BleController.State.class}, BleController.State.DISCONNECTED);
            setField(ble, "state", BleController.State.READY); // the simulation only ticks while ready
            start.set(ble.connectionStartMs());
        });
        require(start.get() > 0 && start.get() <= SystemClock.elapsedRealtime(), "No connection start");
        await(() -> (Integer) fieldOn(values(), "inferenceSegment") > segment.get(), "No refresh saw the new connection", 8000);
        AtomicReference<Long> floor = new AtomicReference<>();
        main(() -> floor.set((Long) fieldOn(values(), "inferenceFloorMs")));
        note("connection boundary: start " + start.get() + " floor " + floor.get());
        require(floor.get().equals(start.get()), "The floor must be the connection's own start, not the screen's refresh time: " + floor.get() + " vs " + start.get());
        await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())), "Inference should keep working in the new run", 60000);
        note("connection boundary: inference recovered after the new connection");
    }

    /**
     * The sign arrives mid-session (Codex, #353 at 6f02fd26): inferred regen first, then, with riding mode
     * still open, the scooter starts signing its current (the signed script's first negative reading proves
     * it) and confirmed regen follows. Its caption and spoken text name only the confirmed peak; the
     * inferred one (about 381 W, above the signed script's 180 W) is never named as confirmed.
     */
    private void signArrives() throws Exception {
        main(() -> activity.service().ble.setSimulationScript("unsigned_regen"));
        setFrame("focus");
        openRiding();
        await(() -> "REGEN_INFERRED".equals(String.valueOf(direction())), "No inferred regen before the sign arrives", 45000);
        main(() -> activity.service().ble.setSimulationScript("riding"));
        await(() -> "REGEN".equals(String.valueOf(direction())), "Confirmed regen never followed the inferred stretch", 60000);
        String failure = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            SystemClock.sleep(250);
            AtomicReference<Object[]> seen = new AtomicReference<>();
            main(() -> { Object p = power(); seen.set(new Object[]{String.valueOf(fieldOn(p, "direction")), fieldOn(p, "peakRegenW"), fieldOn(p, "peakInferredRegenW"), said(readout()), captionText()}); });
            if (!"REGEN".equals(seen.get()[0])) { failure = "not confirmed regen at the read"; continue; }
            double confirmed = (Double) seen.get()[1], inferredPeak = (Double) seen.get()[2];
            String spoken = (String) seen.get()[3], shownCaption = (String) seen.get()[4];
            String confirmedText = "regen · peak " + Math.round(confirmed), inferredText = "regen · peak " + Math.round(inferredPeak);
            note("sign arrives: confirmed peak " + confirmed + ", inferred peak " + inferredPeak + ", caption \"" + shownCaption + "\", said \"" + spoken + "\"");
            require(Math.round(inferredPeak) > Math.round(confirmed) && confirmed > 0, "The fixture needs an inferred peak above the confirmed one: " + inferredPeak + " vs " + confirmed);
            if (!spoken.contains(confirmedText)) { failure = "the readout says \"" + spoken + "\", not " + confirmedText; continue; }
            require(!spoken.contains(inferredText) && !shownCaption.contains(inferredText), "The inferred peak is named as confirmed: " + spoken + " / " + shownCaption);
            require(!shownCaption.contains("peak") || shownCaption.contains(confirmedText), "The caption names another peak: " + shownCaption);
            require(!spoken.contains("direction unknown"), "Confirmed regen gets no uncertainty note: " + spoken);
            failure = null;
            break;
        }
        if (failure != null) throw new AssertionError("sign arrives: " + failure);
        shot("riding-sign-arrives");
        main(() -> activity.onBackPressed());
        await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
        settle();
        main(() -> activity.service().ble.setSimulationScript("unsigned_regen"));
        // The next session must not start from the signed script's last negative reading (that would prove a sign).
        await(() -> { Double w = reading("power_w"); return w != null && w > 0; }, "The unsigned script did not resume", 8000);
        setFrame("halo", false);
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
        caption("signed", false);
        shot("riding-signed-regen");
        main(() -> {
            require(!said(readout()).contains("direction unknown"), "A signed reading gets no uncertainty note: " + said(readout()));
            require(!unsigned(numberMain(fieldOn(screen(), "watts"))), "Signed regen keeps its minus sign");
        });
        await(() -> "REGEN".equals(String.valueOf(direction())), "A signed ride should reach confirmed regen for the spoken check", 60000);
        spokenPower("signed", false);
        await(() -> "REGEN".equals(String.valueOf(direction())), "A signed ride should reach confirmed regen for the bar", 60000);
        barColor(true);
        main(() -> activity.onBackPressed());
        await(() -> fieldOn(activity, "riding") == null, "Back did not close riding mode", 8000);
        settle();
        setFrame("halo", true);
        openRiding();
        await(() -> "REGEN".equals(String.valueOf(direction())), "A signed ride should reach confirmed regen for the ring", 60000);
        ringColor(true);
        main(() -> activity.onBackPressed());
    }

    private void setFrame(String frame) { setFrame(frame, false); }
    private void setFrame(String frame, boolean powerRing) {
        main(() -> {
            Object profile;
            try {
                Class<?> layoutClass = Class.forName("com.jjricks.volt.RidingLayout");
                Method parse = layoutClass.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
                Object layout = parse.invoke(null, (Object) null);
                Method setFrame = layoutClass.getDeclaredMethod("setFrame", String.class); setFrame.setAccessible(true);
                setFrame.invoke(layout, frame);
                Method setContent = layoutClass.getDeclaredMethod("setContent", String.class, String.class, String.class); setContent.setAccessible(true);
                Field power = layoutClass.getDeclaredField(powerRing ? "POWER" : "RING_SPEED"); power.setAccessible(true);
                if (frame.equals("halo")) setContent.invoke(layout, frame, "ring", power.get(null));
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
    private static void setFieldOn(Object target, String name, double value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.setDouble(target, value); }
        catch (Exception e) { throw new RuntimeException(e); }
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
