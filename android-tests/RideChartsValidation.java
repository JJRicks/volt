package com.jjricks.volt.tests;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.ChargeSessionStore;
import com.jjricks.volt.ChargingEstimate;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.PhoneRideStore;
import com.jjricks.volt.RideChartData;
import com.jjricks.volt.RideChartView;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * The ride and charge graphs (2.0.5, owner's Nov 23 list). Emulator only, synthetic data, a fresh install.
 *
 * -e part ride: a ten-minute ride with power, speed, battery, an odometer and a phone journal (GPS and barometer), its
 * graph showing Power, Wheel speed, Battery %, Elevation and Distance (each line the app offers of those). Checks that
 * every chosen line's value axis shows without dragging the axis strip on the unfolded Fold, inline and expanded, and at
 * least three on a phone; and that the expanded graph fills its page down to Done, with no empty band above it. -e size
 * phone (1080x2400) or fold (2074x2153), both at 420 dpi; dark and light.
 *
 * -e part charge: a charge recording whose saved estimates name the estimator's real (long) version, from a personal
 * curve learned from four charges with an 80% limit. Checks that the cursor's estimate detail, inline and expanded,
 * stays inside its row: no line wider than the row, none clipped, no word broken, and the full detail still its spoken
 * text. Phone size, dark and light, at system font scale 1.0 and 1.3.
 */
public final class RideChartsValidation extends ValidationInstrumentation {
    private static final String PKG = "com.jjricks.volt";
    private static final long WALL = 1_791_835_200_000L;
    private static final int SECONDS = 600;
    private static final String[] FIXTURE_PREFS = {"app_settings", "profiles", "scooter", OnboardingScreen.PREFS};
    private Bundle args;
    private MainActivity activity;
    private ScooterService service;
    private String profileId, size, rideId, chargeId, fontWas;
    private File evidence;
    private RideStore store;
    private final List<String> notes = new ArrayList<>();
    private int assertions;
    private boolean resized, fontSet;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String part = args.getString("part", "ride");
        size = args.getString("size", "phone");
        boolean admitted = false;
        Map<String, Map<String, ?>> before = snapshot();
        try {
            require(part.equals("ride") || part.equals("charge"), "Use -e part ride or charge");
            require(size.equals("phone") || size.equals("fold"), "Use -e size phone or fold");
            guard();
            admitted = true;
            evidence = new File(getTargetContext().getExternalFilesDir(null), "ride-charts-" + part + "-" + size);
            require(evidence.isDirectory() || evidence.mkdirs(), "evidence folder");
            resized = true;
            shell("wm density 420");
            shell(size.equals("fold") ? "wm size 2074x2153" : "wm size 1080x2400");
            require(prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("units", "mi").putString("theme", "dark")
                    .putString("time_format", "24h").commit(), "settings");
            main(() -> {
                OnboardingScreen.markDone(getTargetContext());
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic chart scooter", "0F1E2D3C4B5A", 0);
                p.serial = "X2538-SYNTHETIC"; profiles.update(p);
                profileId = p.id;
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "launch", 10000);
            service = activity.service();
            settle();
            if (part.equals("ride")) ride(); else charge();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL");
            result.putString("error", android.util.Log.getStackTraceString(error));
            try { if (evidence != null) shot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions);
            if (evidence != null) try { java.nio.file.Files.write(new File(evidence, "notes.txt").toPath(), notes); } catch (Exception ignored) { }
            if (admitted) cleanup(before);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Runs only on an emulator with a fresh install: no scooter, no setup state, no settings, no rides or charges. */
    private void guard() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "emulator only");
        for (String name : FIXTURE_PREFS) require(prefs(name).getAll().isEmpty(), "Refusing existing " + name + "; use a fresh install");
        for (String folder : new String[]{"rides", "phone-rides", "charge-recordings"}) {
            String[] found = new File(getTargetContext().getFilesDir(), folder).list();
            require(found == null || found.length == 0, "Refusing existing " + folder + "; use a fresh install");
        }
    }

    // ---- Ride graph -------------------------------------------------------------------------------------------------

    /** The lines the graph is set to show, when the app offers them: the owner's four, then distance. */
    private static final List<String> CHOSEN = Arrays.asList("power_w", "speed_kmh", "battery_percent", "elevation", RideChartData.DISTANCE_KEY);

    private void ride() throws Exception {
        store = new RideStore(new File(getTargetContext().getFilesDir(), "rides"));
        rideId = seedRide();
        main(() -> {
            setField(service, "rides", store);
            service.settings.edit().putStringSet("ride_chart_series", new LinkedHashSet<>(CHOSEN)).commit();
        });
        for (String theme : new String[]{"dark", "light"}) {
            openRide(theme);
            List<RideChartData.Series> data = data(panel());
            int offered = 0;
            for (RideChartData.Series s : data) if (CHOSEN.contains(s.key)) offered++;
            require(offered >= 4, "the graph offers " + offered + " of the chosen lines: " + keys(data));
            note(theme + ": lines offered " + keys(data));
            // The distance line (2.0.5): last, drawn by the ride's odometer, so named plainly, and read in miles.
            require(offered == 5 && data.get(data.size() - 1).key.equals(RideChartData.DISTANCE_KEY) && data.get(data.size() - 1).displayLabel().equals("Distance")
                    && data.get(data.size() - 1).unit.equals("mi") && data.get(data.size() - 1).available(), "the distance line: " + keys(data) + " " + data.get(data.size() - 1).displayLabel());
            inspect(chart(panel()), 312_000);
            require(texts(panel()).contains("Distance") && texts(panel()).contains(" mi"), "the cursor reads the ride's distance: " + texts(panel()));
            int[] inline = axesShown(chart(panel()));
            note(theme + " inline: " + inline[0] + " of " + inline[1] + " axes shown, strip " + inline[2] + " px of chart " + inline[3] + " px");
            require(inline[1] == offered, "every chosen line has its own axis: " + inline[1] + " for " + offered);
            if (size.equals("fold")) require(inline[0] == inline[1], "unfolded, the detail pane's graph shows every axis: " + inline[0] + " of " + inline[1]);
            else require(inline[0] >= 3, "a phone's graph shows at least three axes: " + inline[0] + " of " + inline[1]);
            shotChart("inline-" + theme);

            View inlinePanel = panel();
            main(() -> call(inlinePanel, "expand", new Class<?>[0]));
            await(() -> expandedDialog(inlinePanel) != null && Boolean.TRUE.equals(field(expandedPanel(inlinePanel), "fullDataLoaded")), "the expanded graph never loaded", 20000);
            settle();
            View expanded = expandedPanel(inlinePanel);
            inspect(chart(expanded), 312_000);
            int[] full = axesShown(chart(expanded));
            note(theme + " expanded: " + full[0] + " of " + full[1] + " axes shown, strip " + full[2] + " px of chart " + full[3] + " px");
            if (size.equals("fold")) require(full[0] == full[1], "unfolded, the expanded graph shows every axis: " + full[0] + " of " + full[1]);
            else require(full[0] >= 3, "a phone's expanded graph shows at least three axes: " + full[0] + " of " + full[1]);
            int band = bandAboveDone(inlinePanel);
            note(theme + " expanded: " + band + " px between the graph's card and Done");
            require(band <= dp(24), "an empty band above Done: " + band + " px (" + band / density() + " dp)");
            shot("expanded-" + theme);
            main(() -> expandedDialog(inlinePanel).dismiss());
            settle();
        }
    }

    /** A ten-minute ride: rolling hills, an odometer in tenths of a km, and a phone journal with GPS and a barometer. */
    private String seedRide() throws Exception {
        String id = store.start(profileId, "Five readings", WALL, 1000);
        double km = 812.3, odometer = km;
        for (int t = 0; t < SECONDS; t++) {
            long mono = 1000 + t * 1000L;
            Map<String, RideStore.Sample> row = new java.util.LinkedHashMap<>();
            double kmh = t < 10 ? t * 2 : 21 + 6 * Math.sin(t / 40.0);
            km += kmh / 3600;
            if (km >= odometer + 0.1) odometer = Math.floor(km * 10) / 10;
            row.put("voltage_v", new RideStore.Sample(52 - t * 0.004 - kmh * 0.03, "", "V", 0x72, mono));
            row.put("current_a", new RideStore.Sample(3 + kmh * 0.5 + 4 * Math.sin(t / 25.0), "", "A", 0x72, mono));
            row.put("speed_kmh", new RideStore.Sample(kmh, mono));
            row.put("battery_percent", new RideStore.Sample(Math.floor(84 - t / 45.0), mono));
            row.put("odometer_km", new RideStore.Sample(odometer, mono));
            store.sample(WALL + t * 1000L, mono, row);
        }
        store.stop(WALL + SECONDS * 1000L, 1000 + SECONDS * 1000L);
        PhoneRideStore phones = service.phoneRides;
        phones.start(id, WALL, 1000);
        StringBuilder batch = new StringBuilder();
        double north = 0;
        for (int t = 1; t < SECONDS - 1; t++) {
            long w = WALL + t * 1000L, m = 1000 + t * 1000L;
            double mps = (t < 10 ? t * 2 : 21 + 6 * Math.sin(t / 40.0)) / 3.6;
            north += mps;
            batch.append(PhoneRideStore.row("PRESSURE", w, m, 1013.0 - 0.9 * Math.sin(t / 70.0) - t * 0.0006));
            batch.append(PhoneRideStore.row("GPS", w, m, 12.3456789 + north / 111_320.0, -98.7654321, 4.0, mps, 0.3, 30.0, 3.0));
        }
        phones.appendBatch(id, batch.toString(), "");
        phones.stop(id, WALL + SECONDS * 1000L, 1000 + SECONDS * 1000L);
        return id;
    }

    /** Opens the ride's details in a theme and waits for its graph with the phone journal loaded. */
    private void openRide(String theme) throws Exception {
        require(prefs("app_settings").edit().putString("theme", theme).commit(), "theme");
        SystemClock.sleep(500);
        main(() -> activity.rebuild());
        settle();
        main(() -> call(activity, "select", new Class<?>[]{String.class}, "rides"));
        settle();
        SystemClock.sleep(2500); // the Rides list loads off the main thread
        main(() -> call(field(activity, "screen"), "openRide", new Class<?>[]{String.class}, rideId));
        settle();
        await(() -> { View p = panel(); return p != null && field(p, "data") != null && field(p, "phone") != null; }, "the graph never loaded", 15000);
        settle();
    }

    /** {shown without dragging, all axes, strip width, chart width}: the shown count is the whole columns in the strip. */
    private int[] axesShown(RideChartView chart) {
        AtomicReference<int[]> out = new AtomicReference<>();
        main(() -> {
            List<?> axes = (List<?>) field(chart, "axes");
            float strip = (Float) field(chart, "axesWidth"), total = 0; int shown = 0;
            for (Object axis : axes) {
                total += (Float) call(chart, "axisWidth", new Class<?>[]{RideChartData.Series.class}, axis);
                if (total <= strip + 0.5f) shown++;
            }
            out.set(new int[]{shown, axes.size(), Math.round(strip), chart.getWidth()});
        });
        return out.get();
    }

    /** Pixels between the bottom of the expanded graph's card and the top of Done. */
    private int bandAboveDone(View inlinePanel) {
        AtomicReference<Integer> out = new AtomicReference<>();
        main(() -> {
            AlertDialog dialog = expandedDialog(inlinePanel);
            View card = expandedPanel(inlinePanel), done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            int[] a = new int[2], b = new int[2];
            card.getLocationOnScreen(a); done.getLocationOnScreen(b);
            out.set(b[1] - (a[1] + card.getHeight()));
            note("expanded card " + a[1] + ".." + (a[1] + card.getHeight()) + ", Done at " + b[1] + ", window " + dialog.getWindow().getDecorView().getHeight());
        });
        return out.get();
    }

    private void inspect(RideChartView chart, double time) {
        main(() -> { setField(chart, "inspectedTime", time); call(chart, "notifyInspection", new Class<?>[0]); chart.invalidate(); });
        settle();
    }

    private void shotChart(String name) throws Exception {
        main(() -> {
            View p = panel();
            p.requestRectangleOnScreen(new Rect(0, 0, p.getWidth(), Math.min(p.getHeight(), activity.getWindow().getDecorView().getHeight())), true);
        });
        settle();
        shot(name);
    }

    private View root() {
        Object screen = field(activity, "screen");
        List<?> dialogs = (List<?>) field(screen, "dialogs");
        return dialogs.isEmpty() ? activity.getWindow().getDecorView() : ((Dialog) dialogs.get(dialogs.size() - 1)).getWindow().getDecorView();
    }

    private View panel() { return panelOf(find(root(), RideChartView.class)); }

    private static View panelOf(View chart) {
        View v = chart;
        while (v != null && !v.getClass().getName().contains("ChartPanel")) v = v.getParent() instanceof View ? (View) v.getParent() : null;
        return v;
    }

    private static AlertDialog expandedDialog(View inlinePanel) { return (AlertDialog) field(inlinePanel, "expandedDialog"); }

    private static View expandedPanel(View inlinePanel) {
        AlertDialog dialog = expandedDialog(inlinePanel);
        return dialog == null ? null : panelOf(find(dialog.getWindow().getDecorView(), RideChartView.class));
    }

    private static RideChartView chart(View panel) { return (RideChartView) field(panel, "chart"); }

    @SuppressWarnings("unchecked")
    private List<RideChartData.Series> data(View panel) {
        AtomicReference<List<RideChartData.Series>> out = new AtomicReference<>();
        main(() -> out.set((List<RideChartData.Series>) field(panel, "data")));
        return out.get();
    }

    /** Every shown text under a view, joined. */
    private String texts(View view) {
        AtomicReference<StringBuilder> out = new AtomicReference<>(new StringBuilder());
        main(() -> appendTexts(view, out.get()));
        return out.get().toString();
    }

    private static void appendTexts(View view, StringBuilder out) {
        if (!view.isShown()) return;
        if (view instanceof TextView) out.append(((TextView) view).getText()).append(" | ");
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) appendTexts(((ViewGroup) view).getChildAt(i), out);
    }

    private static String keys(List<RideChartData.Series> data) {
        StringBuilder out = new StringBuilder();
        for (RideChartData.Series s : data) out.append(s.key).append(' ');
        return out.toString().trim();
    }

    // ---- Charge graph -----------------------------------------------------------------------------------------------

    private void charge() throws Exception {
        chargeId = seedCharge();
        String saved = fontWas = shell("settings get system font_scale").trim();
        fontSet = true;
        for (String scale : new String[]{"1.0", "1.3"})
            for (String theme : new String[]{"dark", "light"}) {
                // A new font scale recreates the activity: carry on with the new one.
                android.app.Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
                shell("settings put system font_scale " + scale);
                MainActivity recreated = (MainActivity) waitForMonitorWithTimeout(monitor, 5000);
                removeMonitor(monitor);
                if (recreated != null) {
                    activity = recreated;
                    await(() -> activity.service() != null && activity.getWindow().getDecorView().getWidth() > 0, "relaunch", 10000);
                    service = activity.service();
                }
                require(prefs("app_settings").edit().putString("theme", theme).commit(), "theme");
                SystemClock.sleep(800);
                main(() -> activity.rebuild());
                settle();
                HubTestPages.reach(this, activity, "battery.history");
                settle();
                main(() -> { View row = activity.getWindow().getDecorView().findViewWithTag("charge-history-action"); require(row != null && row.isEnabled(), "Charge history unavailable"); row.performClick(); });
                await(() -> historyDialog() != null && historyRoot().findViewWithTag("charge-history-session:" + chargeId) != null, "history did not load", 10000);
                main(() -> historyRoot().findViewWithTag("charge-history-session:" + chargeId).performClick());
                settle();
                String label = theme + "-" + scale + "x";
                RideChartView inline = (RideChartView) historyRoot().findViewWithTag("charge-history-chart");
                main(() -> {
                    for (String key : new String[]{"limit_countdown"}) {
                        android.widget.CheckBox box = (android.widget.CheckBox) historyRoot().findViewWithTag("charge-series:" + key);
                        if (!box.isChecked()) box.performClick();
                    }
                });
                settle();
                inspect(inline, 180_000);
                main(() -> { View trace = historyRoot().findViewWithTag("charge-history-trace"); trace.requestRectangleOnScreen(new Rect(0, 0, trace.getWidth(), trace.getHeight()), true); });
                settle();
                details(historyRoot().findViewWithTag("charge-history-trace"), "inline " + label);
                shot("charge-inline-" + label);
                main(() -> historyRoot().findViewWithTag("charge-history-expand").performClick());
                settle();
                Dialog expanded = (Dialog) field(viewer(), "expanded");
                require(expanded != null && expanded.isShowing(), "the expanded charge graph didn't open");
                View decor = expanded.getWindow().getDecorView();
                RideChartView expandedChart = (RideChartView) decor.findViewWithTag("charge-history-fullscreen-chart");
                // Past the last sample the cells show no estimate detail: the plot keeps its height either way.
                inspect(expandedChart, 10_000_000);
                int plain = expandedChart.getHeight();
                inspect(expandedChart, 180_000);
                require(expandedChart.getHeight() == plain, label + ": the plot changed height with the cursor: " + plain + " then " + expandedChart.getHeight() + " px");
                details(decor.findViewWithTag("charge-history-fullscreen-trace"), "expanded " + label);
                shot("charge-expanded-" + label);
                main(expanded::dismiss);
                settle();
                main(() -> historyDialog().dismiss());
                settle();
            }
        shell("settings put system font_scale " + (saved.isEmpty() || saved.equals("null") ? "1.0" : saved));
    }

    /** Every estimate detail line in a trace row: inside the row, nothing clipped, no word broken, full detail spoken. */
    private void details(View trace, String label) {
        main(() -> {
            View row = (View) trace.getParent();
            List<TextView> lines = new ArrayList<>();
            collectDetails(trace, lines);
            require(lines.size() >= 2, label + ": the cursor shows " + lines.size() + " estimate details");
            List<String> problems = new ArrayList<>();
            for (TextView line : lines) {
                TextFit.collectNarrowTextProblems(line, label + " detail", problems);
                require(line.getWidth() <= row.getWidth(), label + ": a detail line is wider than its row: " + line.getWidth() + " > " + row.getWidth() + ": " + line.getText());
                require(line.getWidth() <= dp(261), label + ": a detail line wider than a reading's 260 dp: " + line.getWidth() + " px");
                String spoken = String.valueOf(line.getContentDescription());
                require(spoken.contains(ChargingEstimate.ESTIMATOR_VERSION), label + ": the spoken detail lost the full version: " + spoken);
                note(label + ": '" + line.getText().toString().replace("\u2060", "") + "' " + line.getWidth() + " px, " + line.getLineCount() + " lines, row " + row.getWidth() + " px");
            }
            require(problems.isEmpty(), label + ": " + problems);
            // Scroll the row to the first detail's whole cell, for the screenshot.
            View cell = (View) lines.get(0).getParent();
            cell.requestRectangleOnScreen(new Rect(0, 0, cell.getWidth(), cell.getHeight()), true);
        });
        settle();
    }

    /** The detail lines: a trace cell's fourth line (label, reading, time, detail). */
    private static void collectDetails(View view, List<TextView> out) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 4 && group.getChildAt(3) instanceof TextView && group.getChildAt(0) instanceof TextView) { out.add((TextView) group.getChildAt(3)); return; }
        for (int i = 0; i < group.getChildCount(); i++) collectDetails(group.getChildAt(i), out);
    }

    /** A personal-curve charge, 40% to 81%, with the estimator's real version saved on every sample. */
    private String seedCharge() throws Exception {
        ChargeSessionStore charges = (ChargeSessionStore) field(service, "chargeStore");
        long wall = WALL + 86_400_000L, mono = 5_000_000;
        charges.start(profileId, ChargeSessionStore.Charger.STOCK, wall, mono);
        for (int i = 0; i <= 40; i++) {
            long w = wall + i * 10_000L, m = mono + i * 10_000L;
            ChargeSessionStore.Estimate estimate = new ChargeSessionStore.Estimate(ChargingEstimate.ESTIMATOR_VERSION, 160d - i * 0.25, 70d - i * 0.25, 80d,
                    ChargeSessionStore.Source.PERSONAL, ChargeSessionStore.Source.PERSONAL, 4);
            ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(w, m, new ChargeSessionStore.Value(1d, m, 0x72), new ChargeSessionStore.Value(40d + i, m, 0x72),
                    new ChargeSessionStore.Value(48d + i * 0.1, m, 0x72), new ChargeSessionStore.Value(-2.2, m, 0x72), new ChargeSessionStore.Value(22d, m, 0x72));
            require(charges.sample(frame, estimate), "charge sample " + i);
        }
        charges.stop(ChargeSessionStore.Stop.STOPPED);
        for (ChargeSessionStore.HistorySession session : charges.recordings(profileId)) if (session.startedMono == mono) return session.id;
        throw new IllegalStateException("charge fixture missing");
    }

    private Object viewer() { return field(field(activity, "screen"), "chargeHistoryDialog"); }
    private Dialog historyDialog() { Object viewer = viewer(); return viewer == null ? null : (Dialog) field(viewer, "dialog"); }
    private View historyRoot() { return historyDialog().getWindow().getDecorView(); }

    // ---- Shared -----------------------------------------------------------------------------------------------------

    private Map<String, Map<String, ?>> snapshot() {
        Map<String, Map<String, ?>> out = new java.util.LinkedHashMap<>();
        for (String name : FIXTURE_PREFS) out.put(name, new java.util.HashMap<>(prefs(name).getAll()));
        return out;
    }

    /** Undoes this run: its ride and charge, the window size and font scale, and the preference files as they were. */
    @SuppressWarnings("unchecked")
    private void cleanup(Map<String, Map<String, ?>> before) {
        try {
            if (activity != null) {
                if (rideId != null) {
                    try { store.delete(rideId); } catch (Throwable ignored) { }
                    try { service.phoneRides.delete(rideId); } catch (Throwable ignored) { }
                }
                if (chargeId != null) try { new File(getTargetContext().getFilesDir(), "charge-recordings/" + chargeId + ".csv").delete(); } catch (Throwable ignored) { }
                main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
                await(activity::isDestroyed, "the Activity did not finish", 8000);
            }
        } catch (Throwable ignored) { }
        try { if (fontSet) shell("settings put system font_scale " + (fontWas == null || fontWas.isEmpty() || fontWas.equals("null") ? "1.0" : fontWas)); } catch (Throwable ignored) { }
        try { if (resized) { shell("wm size reset"); shell("wm density reset"); } } catch (Throwable ignored) { }
        for (String name : FIXTURE_PREFS) try {
            SharedPreferences.Editor edit = prefs(name).edit().clear();
            for (Map.Entry<String, ?> entry : before.get(name).entrySet()) {
                Object v = entry.getValue(); String k = entry.getKey();
                if (v instanceof Boolean) edit.putBoolean(k, (Boolean) v);
                else if (v instanceof Float) edit.putFloat(k, (Float) v);
                else if (v instanceof Integer) edit.putInt(k, (Integer) v);
                else if (v instanceof Long) edit.putLong(k, (Long) v);
                else if (v instanceof String) edit.putString(k, (String) v);
                else if (v instanceof java.util.Set) edit.putStringSet(k, (java.util.Set<String>) v);
            }
            edit.commit();
        } catch (Throwable ignored) { }
    }

    private float density() { return getTargetContext().getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(value * density()); }

    private static View find(View view, Class<?> type) {
        if (view == null) return null;
        if (type.isInstance(view) && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View hit = find(((ViewGroup) view).getChildAt(i), type); if (hit != null) return hit; }
        return null;
    }

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

    private void note(String s) { notes.add(s); android.util.Log.i("VoltRideCharts", s); }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        Throwable[] thrown = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable t) { thrown[0] = t; } });
        if (thrown[0] != null) throw new AssertionError(thrown[0].toString(), thrown[0]);
    }
    private void await(BooleanSupplier c, String m, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> done = new AtomicReference<>(false);
            runOnMainSync(() -> { try { done.set(c.getAsBoolean()); } catch (Throwable ignored) { } });
            if (done.get()) return;
            SystemClock.sleep(50);
        }
        throw new IllegalStateException(m);
    }
    private static Object field(Object target, String name) {
        try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static void setField(Object target, String name, Object value) {
        try { Class<?> t = target.getClass(); while (t != null) { try { Field f = t.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> t = target.getClass(); while (t != null) { try { Method m = t.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values); } catch (NoSuchMethodException e) { t = t.getSuperclass(); } } throw new IllegalStateException(name); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
