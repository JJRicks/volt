package com.jjricks.volt;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Disposable empty emulator, radio off. Clean Home's Just back card's basemap switch (owner, Nov 19 2026: "a tiny switch
 * or toggle button in one of the map corners to toggle basemap; default off, and when the user switches it remembers
 * that preference and saves it to whatever the user left it on"), at the emulator's current size, on the developer
 * simulator with a synthesized finished ride (a loop in a city, its phone journal's GPS fixes):
 * <ul>
 *   <li>Off by default: the switch shows in the route panel's bottom corner, a 48 dp target named Basemap, spoken as an
 *       unchecked switch; no tile is asked for and none drawn (the plain panel).</li>
 *   <li>The first tap shows the privacy explanation the ride maps share, with still no tile asked for; Enable basemap
 *       turns on the card's switch only, never the ride maps' (map_basemap_enabled stays as it was), and remembers the
 *       approval. Then the tile path fires (requests sent); with a network the tiles are drawn, without one the card
 *       stays plain: the checks say which.</li>
 *   <li>Relaunched, the switch is still on (also in the preferences file on disk) and fetches again; tapped off, nothing
 *       more is asked for and no tile is drawn once the layer has faded.</li>
 *   <li>The light theme the same way (no explanation the second time), and a provider that cannot be reached (an address
 *       on this device that refuses connections, standing in for no network): the panel stays plain, with no message.</li>
 *   <li>A ride ending in the switch's corner: the route, its end dot and its ring are fitted clear of the switch.</li>
 * </ul>
 * Pictures: off and on in both themes, the explanation, the unreachable provider and the corner ride. Argument
 * size_label names them. Sends no scooter command.
 */
public final class JustBackBasemapValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String size = "size";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    /** Where the fixture rides are: a loop about 3 km across, and a line running north-west to south-east. */
    private static final double LAT = 45.5200, LON = -122.6800;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            size = args.getString("size_label", "size");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", "dark").putString("units", "mi").putFloat("text_scale", 1f)
                    .putString("home_style", "clean").putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .commit(), "Could not seed preferences");
            launch();
            simulate();
            seedRide(loop(), 5_000L);
            showJustBack();
            darkFirstUse();
            relaunched();
            lightTheme();
            unreachable();
            corner();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** Off by default, then the first tap: the explanation, then on, fetching. Dark theme. */
    private void darkFirstUse() throws Exception {
        require(!settings().contains(JustBackBasemap.KEY), "A fresh install has a saved Just back basemap");
        requireSwitch(false, "dark, off by default");
        requireNoTiles("dark, off by default");
        shot("dark-off");
        int[] before = {0};
        main(() -> { before[0] = (Integer) field(route(), "requests"); require(toggle().performClick(), "The basemap switch took no tap"); });
        settle();
        AlertDialog prompt = onMain(() -> (AlertDialog) field(route(), "prompt"));
        require(prompt != null && onMain(prompt::isShowing), "The first tap did not show the map privacy explanation");
        require(!settings().getBoolean(JustBackBasemap.KEY, false) && !settings().getBoolean("map_basemap_explained", false),
                "The basemap turned on before the explanation was approved");
        main(() -> require((Integer) field(route(), "requests") == before[0] && !toggle().isOn(), "A tile was asked for, or the switch shows on, before the explanation was approved"));
        shot("dark-explanation");
        main(() -> prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        settle();
        require(settings().getBoolean(JustBackBasemap.KEY, false) && settings().getBoolean("map_basemap_explained", false),
                "Enable basemap did not turn the card's basemap on and remember the approval");
        require(!settings().getBoolean("map_basemap_enabled", false), "Enable basemap on the card turned the ride maps' basemap on too");
        checks.add(size + " dark: off by default with no tile asked for; the first tap showed the explanation (no tile before it); "
                + "approving turned on only the card's switch (ride maps' basemap still off)");
        requireSwitch(true, "dark, on");
        requireFetching("dark, first on");
        shot("dark-on");
    }

    /** Relaunched: still on, from the preferences file too; then off, fetching nothing and drawing nothing. */
    private void relaunched() throws Exception {
        relaunch();
        showJustBack();
        String file = new String(Files.readAllBytes(new File(getTargetContext().getDataDir(), "shared_prefs/app_settings.xml").toPath()), StandardCharsets.UTF_8);
        require(file.contains("name=\"" + JustBackBasemap.KEY + "\" value=\"true\""), "The basemap's choice is not in the preferences file on disk");
        requireSwitch(true, "relaunched");
        requireFetching("relaunched");
        checks.add(size + " relaunch: the switch is still on (saved in the preferences file) and the card fetches again");
        main(() -> require(toggle().performClick(), "The basemap switch took no tap"));
        settle();
        require(onMain(() -> field(route(), "prompt")) == null, "Turning the basemap off asked something");
        require(!settings().getBoolean(JustBackBasemap.KEY, true), "The tap did not turn the basemap off");
        requireSwitch(false, "turned off");
        requireNoTiles("turned off");
        checks.add(size + " off again: saved off, no tile asked for or drawn after the fade");
        shot("dark-off-again");
    }

    /** The light theme: off and on (no explanation now), then left off. */
    private void lightTheme() throws Exception {
        require(settings().edit().putString("theme", "light").commit(), "Could not choose the light theme");
        relaunch();
        showJustBack();
        requireSwitch(false, "light, off");
        requireNoTiles("light, off");
        shot("light-off");
        main(() -> require(toggle().performClick(), "The basemap switch took no tap"));
        settle();
        require(onMain(() -> field(route(), "prompt")) == null, "The explanation showed again after it was approved");
        require(settings().getBoolean(JustBackBasemap.KEY, false), "The tap did not turn the basemap on");
        requireSwitch(true, "light, on");
        requireFetching("light, on");
        shot("light-on");
        checks.add(size + " light: off, then on at a tap with no second explanation");
    }

    /** A provider that refuses connections stands in for no network: plain panel, no message, still on. */
    private void unreachable() throws Exception {
        // The provider the explanation's approval saved (OpenStreetMap), put back afterwards: removing it would leave an
        // approved basemap with no provider saved, which MapDefault reads as the old USGS default.
        Map<String, Object> saved = new HashMap<>();
        for (String key : new String[]{"map_tile_url", "map_tile_attribution", "map_tile_attribution_url"}) saved.put(key, settings().getString(key, null));
        require(MapSource.OSM_URL.equals(saved.get("map_tile_url")), "Approving the explanation did not save the provider it named: " + saved.get("map_tile_url"));
        require(settings().edit().putString("theme", "dark").putString("map_tile_url", "https://127.0.0.1:9/{z}/{x}/{y}.png")
                .putString("map_tile_attribution", "Unreachable test provider").putString("map_tile_attribution_url", "https://127.0.0.1:9/").commit(),
                "Could not set the unreachable provider");
        relaunch();
        showJustBack();
        requireSwitch(true, "unreachable provider");
        Set<String> wordsBefore = onMain(() -> words(card()));
        await(() -> (Integer) field(route(), "requests") > 0, 15000, "The card asked for no tile from the unreachable provider");
        SystemClock.sleep(2500); settle();
        main(() -> require(!(Boolean) field(route(), "mapShown"), "A tile was drawn from a provider that cannot be reached"));
        Set<String> wordsAfter = onMain(() -> words(card()));
        require(wordsBefore.equals(wordsAfter), "The card's words changed without a network: " + wordsBefore + " -> " + wordsAfter);
        checks.add(size + " no network (a provider that refuses connections): tiles asked for, none drawn, the panel plain, no words added");
        shot("dark-no-network");
        SharedPreferences.Editor back = settings().edit();
        for (Map.Entry<String, Object> e : saved.entrySet()) back.putString(e.getKey(), (String) e.getValue());
        require(back.commit(), "Could not restore the provider");
    }

    /** A newer ride running north-west to south-east, so its end lies in the switch's corner under the usual fit. */
    private void corner() throws Exception {
        relaunch();
        seedRide(line(), 1_000L);
        showJustBack();
        requireClearOfSwitch("the corner ride");
        shot("dark-corner");
        main(() -> require(toggle().performClick(), "The basemap switch took no tap"));
        settle();
        requireSwitch(false, "the corner ride, off");
        requireClearOfSwitch("the corner ride, off");
        shot("dark-corner-off");
        checks.add(size + " a ride ending in the switch's corner: its route, end dot and ring are fitted clear of the switch, on and off");
    }

    // ---- Checks --------------------------------------------------------------------------------------------------

    /** The switch: shown, a 48 dp target in the panel's bottom corner, named Basemap, a switch with this state. */
    private void requireSwitch(boolean on, String what) {
        main(() -> {
            BasemapView view = new BasemapView();
            require(view.toggle.isShown(), what + ": the basemap switch is not shown");
            float density = activity.getResources().getDisplayMetrics().density;
            require(view.toggle.getWidth() >= Math.round(48 * density) && view.toggle.getHeight() >= Math.round(48 * density),
                    what + ": the switch's target is " + view.toggle.getWidth() + "x" + view.toggle.getHeight() + " px, under 48 dp");
            require(view.toggle.getBottom() == view.route.getHeight(), what + ": the switch is not at the panel's bottom");
            boolean rtl = view.route.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            require(rtl ? view.toggle.getLeft() == 0 : view.toggle.getRight() == view.route.getWidth(), what + ": the switch is not in the end corner");
            require(view.toggle.isOn() == on, what + ": the switch shows " + (view.toggle.isOn() ? "on" : "off"));
            AccessibilityNodeInfo node = view.toggle.createAccessibilityNodeInfo();
            require("Basemap".contentEquals(node.getContentDescription()) && node.isCheckable() && node.isChecked() == on
                            && android.widget.Switch.class.getName().contentEquals(node.getClassName()) && node.isClickable(),
                    what + ": TalkBack hears " + node.getClassName() + " '" + node.getContentDescription() + "' checkable=" + node.isCheckable() + " checked=" + node.isChecked());
            node.recycle();
        });
        requireClearOfSwitch(what);
    }

    /** No tile asked for and none drawn (after any fade), over a second. */
    private void requireNoTiles(String what) {
        int[] asked = {0};
        main(() -> asked[0] = (Integer) field(route(), "requests"));
        await(() -> !(Boolean) field(route(), "mapShown") && ((Number) field(route(), "fadeTo")).floatValue() == 0, 10000, what + ": a tile is still drawn");
        SystemClock.sleep(1200); settle();
        main(() -> {
            route().invalidate();
            require((Integer) field(route(), "requests") == asked[0], what + ": tiles were asked for while the basemap is off");
            require(!(Boolean) field(route(), "mapShown"), what + ": a tile is drawn while the basemap is off");
        });
    }

    /** Tiles asked for (the tile path fired); then drawn when the emulator has a network, else recorded as plain. */
    private void requireFetching(String what) {
        await(() -> (Integer) field(route(), "requests") > 0, 10000, what + ": no tile was asked for with the basemap on");
        boolean drawn = waitFor(() -> (Boolean) field(route(), "mapShown"), 20000);
        SystemClock.sleep(900); settle();
        int asked = onMain(() -> (Integer) field(route(), "requests"));
        checks.add(size + " " + what + ": tile path fired (" + asked + " tiles asked for); "
                + (drawn ? "tiles drawn behind the route" : "no tile arrived in 20 s (emulator without a network?): the panel stayed plain"));
    }

    /** The route's fixes, the stretches between them and the end dots' rings all clear of the switch's target. */
    private void requireClearOfSwitch(String what) {
        main(() -> {
            BasemapView view = new BasemapView();
            float[] outline = (float[]) field(view.route, "route"), fit = (float[]) field(view.route, "fit");
            require(outline != null && outline.length >= 10, what + ": the route has not arrived");
            float ring = 7 * activity.getResources().getDisplayMetrics().density;
            float[] corner = {view.toggle.getLeft(), view.toggle.getTop(), view.toggle.getRight(), view.toggle.getBottom()};
            require(!JustBackBasemap.touches(outline, fit[0], fit[1], fit[2], corner, ring),
                    what + ": the route or an end dot reaches under the basemap switch " + Arrays.toString(corner) + " (fit " + Arrays.toString(fit) + ")");
            float ex = fit[0] + outline[outline.length - 2] * fit[2], ey = fit[1] + outline[outline.length - 1] * fit[2];
            require(ex >= ring && ex <= view.route.getWidth() - ring && ey >= ring && ey <= view.route.getHeight() - ring,
                    what + ": the end dot (" + ex + ", " + ey + ") is not wholly inside the panel");
        });
    }

    /** The visible words in a view tree. */
    private static Set<String> words(View view) {
        Set<String> out = new TreeSet<>();
        if (!view.isShown()) return out;
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.addAll(words(((ViewGroup) view).getChildAt(i)));
        return out;
    }

    // ---- Fixtures ------------------------------------------------------------------------------------------------

    private final class BasemapView {
        final View route = route();
        final JustBackViewSwitch toggle = new JustBackViewSwitch((View) field(route, "toggle"));
    }

    /** JustBackView's BasemapSwitch, read through its view and its state. */
    private final class JustBackViewSwitch {
        final View view;
        JustBackViewSwitch(View view) { this.view = view; }
        boolean isShown() { return view.isShown(); }
        boolean isOn() { return (Boolean) field(view, "on"); }
        int getWidth() { return view.getWidth(); }
        int getHeight() { return view.getHeight(); }
        int getLeft() { return view.getLeft(); }
        int getTop() { return view.getTop(); }
        int getRight() { return view.getRight(); }
        int getBottom() { return view.getBottom(); }
        boolean performClick() { return view.performClick(); }
        AccessibilityNodeInfo createAccessibilityNodeInfo() { return view.createAccessibilityNodeInfo(); }
    }

    private View card() { View card = root().findViewWithTag(JustBackView.TAG); require(card != null, "No Just back card"); return card; }
    private View route() { return (View) field(card(), "route"); }
    private JustBackViewSwitch toggle() { return new JustBackViewSwitch((View) field(route(), "toggle")); }

    /** Clean Home (the scooter pane's on a two-pane window). */
    private Object clean() {
        Object screen = field(activity, "screen");
        return screen instanceof CleanHomeScreen ? screen : field(activity, "homeScreen");
    }

    /** Clean Home looks for rides again and shows Just back for the newest, with its route read. */
    private void showJustBack() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle();
        await(() -> { Object clean = clean(); if (clean == null) return false; setField(clean, "ridesLooked", -1L); activity.redraw();
            return ((Integer) field(clean, "moment")) == HomeMoment.JUST_BACK; }, 10000, "Clean Home does not show Just back for the ride just saved");
        await(() -> { float[] outline = (float[]) field(route(), "route"); return outline != null && outline.length >= 10 && route().getHeight() > 0; },
                10000, "Just back's route did not arrive");
        SystemClock.sleep(700); settle();
    }

    private static double[][] loop() {
        double[][] fixes = new double[121][];
        for (int i = 0; i <= 120; i++) {
            double t = i / 120.0 * 2 * Math.PI;
            fixes[i] = new double[]{LAT + 0.010 * Math.sin(t) + 0.003 * Math.sin(3 * t), LON + 0.020 * (1 - Math.cos(t)) - 0.004 * Math.sin(2 * t)};
        }
        return fixes;
    }

    /** North-west to south-east, a little bent, wider than tall: under the usual fit its end is the panel's bottom right. */
    private static double[][] line() {
        double[][] fixes = new double[81][];
        for (int i = 0; i <= 80; i++) {
            double t = i / 80.0;
            fixes[i] = new double[]{LAT + 0.006 - 0.012 * t + 0.0015 * Math.sin(t * Math.PI), LON - 0.030 + 0.060 * t};
        }
        return fixes;
    }

    /** One finished ride ended endedAgo ms ago, 20 min long, with these phone GPS fixes. Nothing reaches the scooter. */
    private void seedRide(double[][] fixes, long endedAgo) {
        main(() -> {
            long end = System.currentTimeMillis() - endedAgo, start = end - 20 * 60_000L;
            long mono = 100_000L + (5_000L - endedAgo) * 1000, monoEnd = mono + 20 * 60_000L;
            int steps = 40;
            long step = (monoEnd - mono) / steps;
            try {
                String profile = service.profiles.active().id;
                RideStore rides = service.rides;
                String id = rides.start(profile, "Synthetic basemap ride", start, mono);
                rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
                for (int i = 0; i <= steps; i++) {
                    long at = mono + i * step;
                    Map<String, RideStore.Sample> s = new LinkedHashMap<>();
                    s.put("battery_percent", new RideStore.Sample(80d - 18d * i / steps, at));
                    s.put("voltage_v", new RideStore.Sample(52d - 3d * i / steps, at));
                    s.put("current_a", new RideStore.Sample(i == 0 || i == steps ? 0.5 : 9d + (i % 7), at));
                    s.put("speed_kmh", new RideStore.Sample(i == 0 || i == steps ? 0d : 27d + (i % 5), at));
                    s.put("odometer_km", new RideStore.Sample(1200d + 10d * i / steps, at));
                    rides.sample(start + i * step, at, s);
                }
                rides.stop(end, monoEnd);
                PhoneRideStore phone = service.phoneRides;
                require(phone != null, "The service has no phone ride store");
                phone.start(id, start, mono);
                StringBuilder rows = new StringBuilder();
                long gap = (end - start) / (fixes.length - 1);
                for (int i = 0; i < fixes.length; i++)
                    rows.append(PhoneRideStore.row("GPS", start + i * gap, mono + i * gap, fixes[i][0], fixes[i][1], 4, 8, 0.2, 1500, 4));
                phone.appendBatch(id, rows.toString(), "");
                phone.stop(id, end, monoEnd);
                require(!service.isRecording(), "The synthetic ride is still recording");
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }

    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, 10000, "App did not launch");
        service = activity.service();
        SystemClock.sleep(1200); settle();
    }

    private void relaunch() {
        MainActivity old = activity;
        main(old::finish);
        SystemClock.sleep(800); settle();
        launch();
        await(() -> service.ble.ready(), 10000, "The simulated scooter is not ready after the relaunch");
    }

    /** The developer simulator's UT5 MAX, connected and parked. */
    private void simulate() {
        main(() -> require(service.setSimulation(true), "Simulation did not start"));
        await(() -> service.ble.ready(), 10000, "The simulated scooter did not connect");
        main(() -> { service.ble.tickSimulation(); activity.redraw(); });
        settle();
    }

    // ---- Plumbing ------------------------------------------------------------------------------------------------

    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "just-back-basemap-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(500); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), size + "-" + name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); com.jjricks.volt.tests.TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, long ms, String message) {
        if (!waitFor(condition, ms)) throw new IllegalStateException(message);
    }
    private boolean waitFor(BooleanSupplier condition, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> ready.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(ready.get())) return true;
            SystemClock.sleep(60);
        }
        return false;
    }
    private void main(ThrowingRunnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private <V> V onMain(Callable<V> action) {
        AtomicReference<V> value = new AtomicReference<>();
        main(() -> value.set(action.call()));
        return value.get();
    }
    private interface ThrowingRunnable { void run() throws Exception; }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private void setField(Object target, String name, Object value) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Class<?> type = target.getClass(); java.lang.reflect.Method method = null;
            while (type != null && method == null) { try { method = type.getDeclaredMethod(name, types); } catch (NoSuchMethodException missing) { type = type.getSuperclass(); } }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
}
