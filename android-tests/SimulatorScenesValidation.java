package com.jjricks.volt.tests;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.ChargeAlertPolicy;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ModelRegistry;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;
import com.jjricks.volt.ScooterSimulation;
import com.jjricks.volt.Telemetry;
import com.jjricks.volt.TelemetryHistory;

/**
 * Fresh disposable emulator only. The developer simulator's three models (UT5 MAX, GT3 Max with no charge limit and an
 * unsigned current, GT5 Max EU whose controls differ by region) in each scene (Parked, Riding, Charging): Home, the
 * Scooter index, the Battery page and its estimates, Live Stats and riding mode (opened by hand) each captured, with
 * auto-record and auto riding mode on to show a simulated ride starts neither. Then simulation ends and every private
 * store is compared with its state before: databases, files and no_backup byte for byte, preferences key by key, the
 * command log's new records (simulated sessions only), and no notification raised meanwhile.
 */
public final class SimulatorScenesValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private String immersivePrevious;
    private File evidence;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private final StringBuilder notes = new StringBuilder();
    /** model id, region, name: the three picker entries this validation simulates. */
    private static final String[][] MODELS = {{"", null, "UT5 MAX"}, {"24182", "US", "GT3 Max"}, {"25462", "EU", "GT5 Max"}};
    /** Preference keys that only record where the app was (the page and scroll last shown); everything else must stay. */
    private static final String[] NAVIGATION_KEYS = {"last_page", "hub_last_page", "hub_nav_stack", "dashboard_scroll"};

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "simulator-scenes");
            require(evidence.isDirectory() || evidence.mkdirs(), "Evidence directory");
            String theme = arguments.getString("theme", "dark");
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", theme).putString("units", "km")
                    // A simulated ride must start neither (AutoStartPolicy's keys).
                    .putBoolean("auto_record_rides", true).putBoolean("riding_auto_open", true)
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            String[] id = new String[1];
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile saved = profiles.create("UT5 MAX", "000000000000", 0);
                saved.serial = "X2538-SYNTHETIC"; saved.lastConnected = 1767225600000L; profiles.update(saved);
                id[0] = saved.id;
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch", 8000); settle();
            ScooterService service = activity.service();
            // Real data the simulator must leave alone: a last-seen snapshot and a history point, and charge alert settings
            // (so a model without a charge limit shows its countdown while charging).
            long wall = System.currentTimeMillis();
            Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
            readings.put("battery_percent", new TelemetryHistory.Reading("battery_percent", "battery_percent", "Fixture", "64 %", TelemetryHistory.unitFor("battery_percent"), 64.0, wall - 60000, true));
            readings.put("odometer_km", new TelemetryHistory.Reading("odometer_km", "odometer_km", "Fixture", "1234.5 km", TelemetryHistory.unitFor("odometer_km"), 1234.5, wall - 60000, true));
            Map<String, Integer> features = new LinkedHashMap<>(); features.put("lock", 1);
            service.history.record(id[0], new TelemetryHistory.Snapshot(readings, features, wall - 60000), 1, true);
            main(() -> require(service.setChargeAlerts(new ChargeAlertPolicy.Settings(true, 90, true, 20, true, false)), "Charge alert settings"));
            SystemClock.sleep(2500); settle();
            Map<String, String> filesBefore = files();
            Map<String, Map<String, String>> prefsBefore = preferences();
            int commandsBefore = commands().size();
            List<String> noticesBefore = notices();
            note("before: " + filesBefore.size() + " files; " + filesBefore.keySet());

            main(() -> require(service.setSimulation(true), "Simulation did not start")); settle();
            for (String[] model : MODELS) {
                main(() -> service.ble.setSimulationModel(model[0], model[1])); settle();
                ScooterProfiles.Profile active = service.profiles.active();
                require(ModelRegistry.modelName(service.modelDescriptor(active)).equals(model[2]), "The screens' model is " + model[2]);
                require(service.ble.descriptor().simulated && (model[1] == null || model[1].equals(service.ble.simulationRegion())), "Simulated descriptor and region");
                boolean alertsModel = !model[0].isEmpty();
                require(service.chargeAlertsOffered() == alertsModel, model[2] + ": charge alerts only where the model has no charge limit");
                for (ScooterSimulation.Scene scene : ScooterSimulation.Scene.values()) {
                    main(() -> service.ble.setSimulationScene(scene)); settle();
                    require(service.ble.simulationScene() == scene, "Scene " + scene);
                    String label = (model[0].isEmpty() ? "ut5max" : model[2].toLowerCase(Locale.ROOT).replace(' ', '-') + "-" + model[1].toLowerCase(Locale.ROOT)) + "-" + scene.id;
                    if (scene == ScooterSimulation.Scene.RIDING) {
                        // Mid-cruise (the loop cruises from 8 s to 38 s), with auto-record and auto riding mode on.
                        SystemClock.sleep(12000); main(() -> service.ble.tickSimulation()); settle();
                        Double speed = number(service, "speed_kmh");
                        require(speed != null && speed > 15, label + ": cruising, " + speed);
                        require(!service.isRecording() && !(Boolean)call(service, "ridingNow", new Class<?>[0]), label + ": a simulated ride is never recorded or counted as riding");
                        require(field(activity, "riding") == null, label + ": riding mode never opens by itself");
                    }
                    if (scene == ScooterSimulation.Scene.CHARGING) {
                        Double state = number(service, "charging_state"), amps = number(service, "current_a");
                        require(state != null && state == 1 && amps != null, label + ": charging");
                        require(model[0].equals("24182") ? amps > 0 : amps < 0, label + ": the model's current sign while charging, " + amps);
                        require(model[0].isEmpty() == (number(service, "charge_limit_percent") != null), label + ": a charge limit reading only on the UT5 MAX");
                        require(!service.chargeAlertWatching() && !service.chargeAlarmPlaying(), label + ": simulated charge alerts never watch or ring");
                    }
                    for (String page : new String[]{"home", "scooter", "battery", "battery.estimates", "data"}) {
                        go(page); capture(label + "-" + page.replace('.', '-'));
                    }
                    main(() -> activity.openRiding());
                    await(() -> field(activity, "riding") != null, label + ": riding mode did not open", 8000); settle();
                    capture(label + "-riding");
                    main(() -> activity.onBackPressed());
                    await(() -> field(activity, "riding") == null, label + ": Back did not close riding mode", 8000); settle();
                    List<String> notices = notices(); notices.removeAll(noticesBefore);
                    require(notices.isEmpty(), label + ": no notification from a simulated " + scene.id + ": " + notices);
                    require(!service.isRecording() && !service.isChargeRecording(), label + ": nothing recording");
                }
            }
            main(() -> { service.ble.setSimulationModel("", null); require(service.setSimulation(false), "Simulation did not stop"); }); settle();
            go("home"); SystemClock.sleep(2500); settle();

            // The real state is back: no sample reading anywhere, the saved model, and every store as it was.
            require(!service.ble.simulating() && !service.simulating(), "Simulation ended");
            for (Telemetry.Metric metric : service.ble.telemetry.metrics())
                require(metric.value == null && metric.updatedMs == -1, "A simulated reading survived: " + metric.key);
            for (TelemetryHistory.Reading reading : service.displayReadings().values())
                require(reading.value == null || !reading.value.toLowerCase(Locale.ROOT).contains("simulation"), "A simulated display reading survived: " + reading.key);
            ScooterProfiles.Profile active = service.profiles.active();
            require(service.modelDescriptor(active) != null && !service.modelDescriptor(active).simulated && ModelRegistry.ut5Max(service.modelDescriptor(active)),
                    "The screens' model is the saved UT5 MAX again");
            capture("real-home-after");
            Map<String, String> filesAfter = files();
            List<String> changed = new ArrayList<>();
            for (String name : union(filesBefore.keySet(), filesAfter.keySet()))
                if (!String.valueOf(filesBefore.get(name)).equals(String.valueOf(filesAfter.get(name)))) changed.add(name + " " + filesBefore.get(name) + " -> " + filesAfter.get(name));
            note("stores compared: " + filesAfter.keySet());
            require(changed.isEmpty(), "Private files changed by simulation: " + changed);
            Map<String, Map<String, String>> prefsAfter = preferences();
            List<String> prefChanges = new ArrayList<>();
            for (String file : union(prefsBefore.keySet(), prefsAfter.keySet())) {
                Map<String, String> a = prefsBefore.containsKey(file) ? prefsBefore.get(file) : new TreeMap<>(), b = prefsAfter.containsKey(file) ? prefsAfter.get(file) : new TreeMap<>();
                for (String key : union(a.keySet(), b.keySet()))
                    if (!String.valueOf(a.get(key)).equals(String.valueOf(b.get(key))) && !navigation(key)) prefChanges.add(file + ":" + key);
            }
            note("preference changes outside navigation: " + prefChanges);
            require(prefChanges.isEmpty(), "Preferences changed by simulation: " + prefChanges);
            List<Object> records = commands();
            boolean simulatedSession = false;
            for (Object record : records.subList(Math.min(commandsBefore, records.size()), records.size())) {
                if (record.getClass().getSimpleName().equals("Session")) simulatedSession = (Boolean)field(record, "simulated");
                else require(simulatedSession, "A command record outside a simulated session: " + record);
            }
            note("command log: " + (records.size() - commandsBefore) + " new records, every one in a simulated session");
            List<String> notices = notices(); notices.removeAll(noticesBefore);
            require(notices.isEmpty(), "Notifications left after simulation: " + notices);
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
        } finally {
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false);
            if (evidence != null) try (FileOutputStream out = new FileOutputStream(new File(evidence, "notes.txt"))) { out.write(notes.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            if (immersivePrevious != null) try {
                shell(immersivePrevious.isEmpty() || immersivePrevious.equals("null") ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious);
            } catch (Exception ignored) { }
            if (admitted && activity != null) try { main(() -> { activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        require(getTargetContext().databaseList().length == 0, "Refusing existing private data");
    }
    private void go(String page) {
        main(() -> call(activity, "select", new Class<?>[]{String.class}, page)); settle();
        main(() -> { View scroll = (View)field(activity, "scroll"); if (scroll != null) scroll.scrollTo(0, 0); }); settle();
    }
    private void capture(String label) throws Exception {
        main(() -> { if (activity.service().ble.simulating()) activity.service().ble.tickSimulation(); activity.redraw(); }); settle(); SystemClock.sleep(600); settle();
        String variant = arguments.getString("variant", "scenes").replaceAll("[^a-zA-Z0-9_.-]", "");
        StringBuilder tree = new StringBuilder(); main(() -> TextCensus.dump(activity.getWindow().getDecorView(), 0, tree));
        try (FileOutputStream out = new FileOutputStream(new File(evidence, variant + "-" + label + ".txt"))) { out.write(tree.toString().getBytes(StandardCharsets.UTF_8)); }
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        File file = new File(evidence, variant + "-" + label + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
        TextCensus.write(this, file);
    }
    private static Double number(ScooterService service, String key) { return service.ble.telemetry.number(key); }

    /** SHA-256 of every private file but the caches, preferences (compared by key) and the command log (by record). */
    private Map<String, String> files() throws Exception {
        TreeMap<String, String> out = new TreeMap<>();
        File data = getTargetContext().getDataDir();
        walk(data, data, out);
        return out;
    }
    private void walk(File root, File dir, Map<String, String> out) throws Exception {
        File[] children = dir.listFiles(); if (children == null) return;
        for (File child : children) {
            String name = root.toURI().relativize(child.toURI()).getPath();
            if (name.startsWith("cache/") || name.startsWith("code_cache/") || name.startsWith("shared_prefs/") || name.startsWith("app_") || name.startsWith("lib")
                    || name.startsWith("no_backup/command-events") || name.endsWith("-shm")) continue;
            if (child.isDirectory()) { walk(root, child, out); continue; }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(child)) { byte[] buffer = new byte[8192]; for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n); }
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            out.put(name, child.length() + ":" + hex.substring(0, 16));
        }
    }
    private Map<String, Map<String, String>> preferences() {
        TreeMap<String, Map<String, String>> out = new TreeMap<>();
        File[] files = new File(getTargetContext().getDataDir(), "shared_prefs").listFiles();
        if (files != null) for (File file : files) {
            if (!file.getName().endsWith(".xml")) continue;
            String name = file.getName().substring(0, file.getName().length() - 4);
            TreeMap<String, String> values = new TreeMap<>();
            for (Map.Entry<String, ?> e : prefs(name).getAll().entrySet()) values.put(e.getKey(), String.valueOf(e.getValue()));
            out.put(name, values);
        }
        return out;
    }
    private static boolean navigation(String key) {
        for (String k : NAVIGATION_KEYS) if (key.equals(k) || key.startsWith(k + "_")) return true;
        return false;
    }
    @SuppressWarnings("unchecked")
    private List<Object> commands() {
        Object log = field(activity.service().ble, "commands");
        return new ArrayList<>((List<Object>)call(log, "snapshot", new Class<?>[0]));
    }
    private List<String> notices() {
        List<String> out = new ArrayList<>();
        NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
        for (StatusBarNotification n : manager.getActiveNotifications()) out.add(n.getId() + ":" + n.getNotification().getChannelId());
        return out;
    }
    private static List<String> union(java.util.Set<String> a, java.util.Set<String> b) { java.util.TreeSet<String> all = new java.util.TreeSet<>(a); all.addAll(b); return new ArrayList<>(all); }
    private void note(String line) { notes.append(line).append('\n'); }

    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message, long ms) { long end = SystemClock.uptimeMillis() + ms; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); require(condition.getAsBoolean(), message); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) { byte[] buffer = new byte[1024]; for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n); }
        return out.toString("UTF-8");
    }
    private static Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
}
