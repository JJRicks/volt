package com.jjricks.volt;

import com.jjricks.volt.tests.ValidationInstrumentation;
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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>Tile requests queued behind busy tile loaders when the switch goes off fetch nothing.</li>
 *   <li>The light theme the same way (no explanation the second time), and a provider that cannot be reached (an address
 *       on this device that refuses connections, standing in for no network): the panel stays plain, with no message.</li>
 *   <li>Map settings shows the card's own switch (Map on Home's last ride), on with it, and turns it off and on there without touching the
 *       ride maps' basemap.</li>
 *   <li>A ride ending in the switch's corner: the route, its end dot and its ring are fitted clear of the switch.</li>
 * </ul>
 * Then the ride's own map, opened from the card with the ride maps' basemap on, in both themes (pictures only: the ride
 * maps' tiles, one zoom finer, for comparison).
 * Pictures: off and on in both themes, the explanation, the unreachable provider and the corner ride. Argument
 * size_label names them. Sends no scooter command.
 *
 * <p>Argument mode map-style runs the online maps' shared dark/light style instead (MapStyle, the ride maps and this card
 * together; see mapStyle).
 */
public final class JustBackBasemapValidation extends ValidationInstrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String size = "size";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    /** Where the fixture rides are: a loop about 3 km across, and a line running north-west to south-east. */
    private static final double LAT = 45.5200, LON = -122.6800;
    /** Mode map-style: the online maps' shared dark/light style (MapStyle), instead of the card's basemap switch. */
    private static final String MAP_STYLE = "map-style";
    /** Whether the fixture's GPS speed rises and falls (map-style, for the speed colours) rather than holding 8 m/s. */
    private boolean varySpeed;

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
            if (MAP_STYLE.equals(args.getString("mode"))) { mapStyle(); result.putString("result", "PASS"); return; }
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
            // The dark theme's Map settings (#560 follow-up's label): the card's map on for it, then off again as relaunched left it.
            require(settings().edit().putBoolean(JustBackBasemap.KEY, true).commit(), "Could not turn the card's basemap on for Map settings");
            settle();
            mapSettings("dark");
            require(settings().edit().putBoolean(JustBackBasemap.KEY, false).commit(), "Could not turn the card's basemap off after Map settings");
            settle(); SystemClock.sleep(600); settle();
            queuedThenOff();
            lightTheme();
            mapSettings("light");
            unreachable();
            rideMap("dark");
            rideMap("light");
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

    /**
     * Requests already queued when the switch goes off fetch nothing (council, #560 r1): both tile loaders held busy, the
     * switch on and the panel drawn so tile requests queue behind them, the switch off, the loaders let go.
     */
    private void queuedThenOff() throws Exception {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1), busy = new java.util.concurrent.CountDownLatch(2);
        int[] before = {0, 0, 0};
        try {
            for (int i = 0; i < 2; i++) RouteMapView.TILES.execute(() -> {
                busy.countDown();
                try { hold.await(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            require(busy.await(10, java.util.concurrent.TimeUnit.SECONDS), "The tile loaders did not take the holds");
            main(() -> {
                View route = route();
                ((android.util.LruCache<?, ?>) field(route, "tiles")).evictAll();
                require(settings().edit().putBoolean(JustBackBasemap.KEY, true).commit(), "Could not turn the basemap on");
                // The layer at full at once (the change listener has started its fade), so this frame asks for tiles.
                require((Boolean) field(route, "basemap"), "The basemap did not follow the preference");
                setField(route, "fadeFrom", 1f); setField(route, "fadeTo", 1f);
                before[0] = (Integer) field(route, "requests");
                before[1] = ((java.util.concurrent.atomic.AtomicInteger) field(route, "fetches")).get();
                Bitmap frame = Bitmap.createBitmap(route.getWidth(), route.getHeight(), Bitmap.Config.ARGB_8888);
                route.draw(new android.graphics.Canvas(frame));
                frame.recycle();
                before[2] = (Integer) field(route, "requests");
                require(before[2] > before[0], "Drawing the panel with the basemap on queued no tile request");
                require(toggle().performClick(), "The basemap switch took no tap");
                require(!settings().getBoolean(JustBackBasemap.KEY, true), "The tap did not turn the basemap off");
            });
        } finally {
            hold.countDown();
        }
        SystemClock.sleep(2500); settle();
        main(() -> {
            int fetched = ((java.util.concurrent.atomic.AtomicInteger) field(route(), "fetches")).get();
            require(fetched == before[1], "Tile requests queued before the switch went off still fetched " + (fetched - before[1]) + " tile(s)");
            checks.add(size + " queued then off: " + (before[2] - before[0]) + " tile requests queued behind busy loaders, the switch turned off, none fetched");
        });
        requireNoTiles("queued then off");
    }

    /**
     * Map settings shows the card's own switch (Map on Home's last ride) beside the ride maps' choice, on while the card's basemap is
     * on, and turns it off and on again there (council, #560 r1). The ride maps' choice stays as it was.
     */
    private void mapSettings(String theme) throws Exception {
        boolean rides = settings().getBoolean("map_basemap_enabled", false);
        AlertDialog[] dialog = {null};
        main(() -> dialog[0] = MapPreferences.show((ScreenHost) field(clean(), "host")));
        settle(); SystemClock.sleep(400);
        android.widget.Switch[] row = {null};
        main(() -> {
            View found = dialog[0].getWindow().getDecorView().findViewWithTag("map-settings-just-back");
            require(found instanceof ViewGroup && found.isShown(), "Map settings has no switch for the card's basemap");
            row[0] = (android.widget.Switch) ((ViewGroup) found).getChildAt(0);
            require(row[0].isChecked(), "Map settings' card switch reads '" + row[0].getText() + "', checked " + row[0].isChecked() + ", with the card's basemap on");
            // Its own label, not the tab's name (#560 follow-up): whole on one line where it fits, never broken inside a word.
            require(T.mapsJustBackSwitch().contentEquals(row[0].getText()), "Map settings' card switch reads '" + row[0].getText() + "', not its own label");
            android.text.Layout lines = row[0].getLayout();
            require(lines != null && lines.getEllipsisCount(lines.getLineCount() - 1) == 0, "Map settings' card switch label is cut off: '" + row[0].getText() + "'");
            for (int line = 0; lines != null && line < lines.getLineCount() - 1; line++) {
                int end = lines.getLineEnd(line);
                require(end <= 0 || end >= row[0].getText().length() || Character.isWhitespace(row[0].getText().charAt(end - 1)), "Map settings' card switch label breaks inside a word at line " + line);
            }
            checks.add(size + " " + theme + " Map settings: the card's switch reads '" + row[0].getText() + "' on " + (lines == null ? 0 : lines.getLineCount()) + " line(s), " + Math.round(row[0].getWidth() / row[0].getResources().getDisplayMetrics().density) + " dp wide");
        });
        shot(theme + "-map-settings");
        main(() -> row[0].performClick());
        settle();
        require(!settings().getBoolean(JustBackBasemap.KEY, true), "Map settings' switch did not turn the card's basemap off");
        main(() -> require(!row[0].isChecked() && !toggle().isOn(), "Map settings' switch or the card still shows on"));
        main(() -> row[0].performClick());
        settle();
        require(settings().getBoolean(JustBackBasemap.KEY, false), "Map settings' switch did not turn the card's basemap on again");
        require(settings().getBoolean("map_basemap_enabled", false) == rides, "Map settings' card switch changed the ride maps' basemap");
        main(() -> dialog[0].dismiss());
        settle();
        checks.add(size + " Map settings: the card's own switch shows on, turns the card's basemap off and on, and leaves the ride maps' as it was");
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

    /**
     * The ride's own map (Rides, the ride's details) with the ride maps' basemap on, for the owner's comparison of the
     * finer tiles there (RouteMapView.FINER_TILES): opened from the card, as an owner would, in this theme.
     */
    private void rideMap(String theme) throws Exception {
        require(settings().edit().putString("theme", theme).putBoolean("map_basemap_enabled", true).commit(), "Could not turn the ride maps' basemap on");
        relaunch();
        showJustBack();
        main(() -> require(card().performClick(), "The card did not open its ride"));
        settle();
        await(() -> rideMapView() != null, 10000, "The ride's map did not open from the card");
        main(() -> { View map = rideMapView(); map.requestRectangleOnScreen(new android.graphics.Rect(0, 0, map.getWidth(), map.getHeight()), true); });
        SystemClock.sleep(600); settle();
        boolean drawn = waitFor(() -> ((android.util.LruCache<?, ?>) field(rideMapView(), "tiles")).size() > 0, 20000);
        SystemClock.sleep(900); settle();
        checks.add(size + " " + theme + " ride map: " + (drawn ? "tiles drawn, one zoom finer" : "no tile arrived in 20 s (emulator without a network?)"));
        shot(theme + "-ride-map");
        // The ride opens in its own window (its details sheet): Back closes it, then Home.
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        settle();
        await(() -> rideMapView() == null, 10000, "The ride's details did not close on Back");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle();
    }

    /** The ride map (RouteMapView) shown in any of the app's windows (the ride's details open in a window of their own), or null. */
    private static View rideMapView() {
        for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            View found = window.isAttachedToWindow() ? map(window) : null;
            if (found != null) return found;
        }
        return null;
    }

    /** The first ride map (RouteMapView) in the tree, or null. */
    private static View map(View view) {
        if (view instanceof RouteMapView && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = map(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    // ---- Mode map-style -------------------------------------------------------------------------------------------

    /**
     * Mode map-style. The online maps' shared dark/light style (MapStyle; owner, Nov 23 2026: "can we have a toggle for
     * that on the ride recording map itself as well? Remember and save what the user picked last and keep it. Then that
     * toggle could control both the 'just back' and ride recording maps together"), with both basemaps on and a ride whose
     * GPS speed rises and falls:
     * <ul>
     *   <li>Never picked, the dark theme: the Just back card and the ride's maps (inline and expanded) draw dark tiles;
     *       the Dark map switch beside Basemap is shown, checked, named Dark map and a switch to TalkBack, its label
     *       whole (beside Basemap or on its own row).</li>
     *   <li>Flipped on the expanded ride map: "light" is saved; the expanded and inline maps and, back on Home, the
     *       Just back card all draw light.</li>
     *   <li>The light theme with that pick: still light. Flipped there: "dark" saved, both maps dark in the light theme,
     *       and the expanded map's paper under loading tiles is dark too.</li>
     *   <li>The dark theme with the dark pick: dark. The pick removed (never picked): the light theme draws light and
     *       the dark theme dark again, and showing the maps saves no pick.</li>
     *   <li>A fold change (the emulator switched to the other form and back) with Home and then the expanded map open:
     *       the style holds, and a flip at the other size reaches the card.</li>
     *   <li>The ride maps' basemap off: no Dark map switch beside Basemap. Map settings: the Dark map row shows while
     *       either basemap is on, flips the shared style (the card follows), and hides with both off.</li>
     * </ul>
     * Pictures: the Just back card and the expanded ride map (speed colours) dark and light in both app themes, the
     * inline map with its switches, and Map settings' row in both themes.
     */
    private void mapStyle() throws Exception {
        String sizeBefore = overrideSize();
        try {
            SharedPreferences.Editor seed = settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", "dark").putString("units", "mi").putFloat("text_scale", 1f)
                    .putString("home_style", "clean").putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .putBoolean("map_basemap_enabled", true).putBoolean("map_basemap_explained", true).putBoolean(JustBackBasemap.KEY, true);
            MapSource.OSM.put(seed);
            require(seed.commit(), "Could not seed preferences");
            launch();
            simulate();
            varySpeed = true;
            seedRide(loop(), 5_000L);
            darkThemeNeverPicked();
            lightThemeKeepsPick();
            darkThemeKeepsPick();
            neverPickedFollowsTheme();
            foldChange(sizeBefore);
            basemapOff();
        } finally {
            try { shellText(sizeBefore == null ? "wm size reset" : "wm size " + sizeBefore); } catch (Throwable ignored) {}
        }
    }

    /** The fixture's GPS speed at fix i of n: 8 m/s, or (map-style) rising and falling twice between 2 and 12 m/s. */
    private Object gpsSpeed(int i, int n) {
        return varySpeed ? String.format(Locale.US, "%.2f", 7 - 5 * Math.cos(i / (double) Math.max(1, n - 1) * 4 * Math.PI)) : (Object) 8;
    }

    /** The dark theme, never picked: dark everywhere; flipped on the expanded map: light everywhere, saved. */
    private void darkThemeNeverPicked() throws Exception {
        require(!settings().contains(MapStyle.KEY), "A fresh install has a saved map style");
        showJustBack();
        requireCard(true, "dark theme, never picked");
        waitForCardTiles();
        shot("dark-theme-just-back-dark");
        openRide();
        requireInline(true, true, "dark theme, never picked");
        shot("dark-theme-ride-inline-dark");
        expand();
        requireExpanded(true, "dark theme, never picked");
        speedColours();
        waitForExpandedTiles();
        shot("dark-theme-ride-map-dark");
        flipExpanded();
        require(MapStyle.LIGHT.equals(settings().getString(MapStyle.KEY, null)), "Flipping the dark map did not save the light pick: " + settings().getString(MapStyle.KEY, null));
        requireExpanded(false, "dark theme, flipped to light");
        main(() -> require(!inlineMap().darkTiles(), "The inline map under the expanded one did not follow the flip"));
        waitForExpandedTiles();
        shot("dark-theme-ride-map-light");
        closeExpanded();
        requireInline(false, true, "dark theme, after the flip");
        closeRide();
        showJustBack();
        requireCard(false, "dark theme, light picked on the ride map");
        waitForCardTiles();
        shot("dark-theme-just-back-light");
        checks.add(size + " dark theme: never picked, the card and both ride maps drew dark; a flip on the expanded map saved 'light' and the expanded map, the inline map and the Just back card all drew light");
    }

    /** The light theme keeps the light pick; flipped there to dark: dark everywhere in the light theme. */
    private void lightThemeKeepsPick() throws Exception {
        require(settings().edit().putString("theme", "light").commit(), "Could not choose the light theme");
        relaunch();
        showJustBack();
        require(MapStyle.LIGHT.equals(settings().getString(MapStyle.KEY, null)), "The light pick was lost across the theme change");
        requireCard(false, "light theme, light picked");
        waitForCardTiles();
        shot("light-theme-just-back-light");
        openRide();
        requireInline(false, true, "light theme, light picked");
        expand();
        requireExpanded(false, "light theme, light picked");
        speedColours();
        waitForExpandedTiles();
        shot("light-theme-ride-map-light");
        flipExpanded();
        require(MapStyle.DARK.equals(settings().getString(MapStyle.KEY, null)), "Flipping the light map did not save the dark pick");
        requireExpanded(true, "light theme, flipped to dark");
        requireDarkPaper();
        waitForExpandedTiles();
        shot("light-theme-ride-map-dark");
        closeExpanded();
        requireInline(true, true, "light theme, after the flip");
        shot("light-theme-ride-inline-dark");
        closeRide();
        showJustBack();
        requireCard(true, "light theme, dark picked on the ride map");
        waitForCardTiles();
        shot("light-theme-just-back-dark");
        checks.add(size + " light theme: the light pick held across the theme change; a flip there saved 'dark' and both maps drew dark in the light theme, the expanded map's paper dark under its tiles");
    }

    /** The dark theme keeps the dark pick. */
    private void darkThemeKeepsPick() throws Exception {
        require(settings().edit().putString("theme", "dark").commit(), "Could not choose the dark theme");
        relaunch();
        showJustBack();
        requireCard(true, "dark theme, dark picked");
        openRide();
        requireInline(true, true, "dark theme, dark picked");
        closeRide();
        checks.add(size + " dark theme: the dark pick held across the theme change (card and ride map)");
    }

    /** Without a pick the maps follow the theme again, both ways. */
    private void neverPickedFollowsTheme() throws Exception {
        require(settings().edit().remove(MapStyle.KEY).putString("theme", "light").commit(), "Could not remove the pick");
        relaunch();
        showJustBack();
        requireCard(false, "light theme, never picked");
        openRide();
        requireInline(false, true, "light theme, never picked");
        closeRide();
        require(settings().edit().putString("theme", "dark").commit(), "Could not choose the dark theme");
        relaunch();
        showJustBack();
        requireCard(true, "dark theme, never picked again");
        require(!settings().contains(MapStyle.KEY), "Showing the maps saved a pick nobody made");
        checks.add(size + " never picked: the light theme drew light and the dark theme dark (card and ride map); merely showing the maps saved no pick");
    }

    /**
     * A fold change: the emulator switched to the other form (a narrow phone ↔ an open Fold panel) and back, with Home and
     * then the expanded map open. The style holds through each change, and a flip at the other size reaches the card.
     */
    private void foldChange(String before) throws Exception {
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        int widthDp = Math.round(onMain(() -> root().getWidth()) / density);
        // The other form: the open Fold panel from a phone, a phone from the open panel.
        String other = widthDp >= 600 ? Math.round(411 * density) + "x" + Math.round(900 * density) : Math.round(790 * density) + "x" + Math.round(820 * density);
        String back = before == null ? "reset" : before;
        try {
            showJustBack();
            requireCard(true, "before the fold change");
            shellText("wm size " + other); SystemClock.sleep(1800); settle();
            showJustBack();
            requireCard(true, "after the fold change to " + other);
            openRide();
            expand();
            requireExpanded(true, "expanded at " + other);
            flipExpanded();
            requireExpanded(false, "flipped at " + other);
            shellText("wm size " + back); SystemClock.sleep(1800); settle();
            // The size change may rebuild the page; whatever map is still open shows the pick, and so does Home.
            main(() -> { RouteMapView any = anyMap(); require(any == null || !any.darkTiles(), "A ride map open across the fold change lost the light pick"); });
            closeAllRideWindows();
            showJustBack();
            requireCard(false, "back at the first size, after a flip at the other");
            require(MapStyle.LIGHT.equals(settings().getString(MapStyle.KEY, null)), "The flip at the other size was not saved");
            checks.add(size + " fold change (" + widthDp + " dp ↔ " + other + " px): the style held on Home and on the expanded map; a flip at the other size reached the card");
        } finally {
            shellText("wm size " + back); SystemClock.sleep(1200); settle();
        }
    }

    /** The ride maps' basemap off: no Dark map switch there. Map settings: the row follows either basemap and flips the style. */
    private void basemapOff() throws Exception {
        require(settings().edit().putBoolean("map_basemap_enabled", false).putString(MapStyle.KEY, MapStyle.DARK).commit(), "Could not turn the ride maps' basemap off");
        relaunch();
        openRide();
        requireInline(true, false, "ride maps' basemap off");
        closeRide();
        showJustBack();
        // Map settings with only the card's basemap on: the row shows, checked (dark), and flips the shared style.
        settingsRow("dark", true, true);
        require(MapStyle.LIGHT.equals(settings().getString(MapStyle.KEY, null)), "Map settings' Dark map row did not save the light pick");
        requireCard(false, "flipped in Map settings");
        require(settings().edit().putBoolean(JustBackBasemap.KEY, false).commit(), "Could not turn the card's basemap off");
        settle();
        settingsRow("dark", false, false);
        require(settings().edit().putString("theme", "light").putBoolean("map_basemap_enabled", true).commit(), "Could not choose the light theme");
        relaunch();
        showJustBack();
        settingsRow("light", true, false);
        checks.add(size + " basemap off: no Dark map switch beside Basemap; Map settings' Dark map row showed with either basemap on, flipped the shared style (the card followed) and hid with both off");
    }

    /** Map settings' Dark map row: shown or hidden; when shown, checked as the maps draw, and (flip) tapped once. */
    private void settingsRow(String theme, boolean shownExpected, boolean flip) throws Exception {
        AlertDialog[] dialog = {null};
        main(() -> dialog[0] = MapPreferences.show((ScreenHost) field(clean(), "host")));
        settle(); SystemClock.sleep(400);
        Switch[] row = {null};
        main(() -> {
            View found = dialog[0].getWindow().getDecorView().findViewWithTag(MapStyleControls.TAG_SETTINGS);
            require(found instanceof ViewGroup, "Map settings has no Dark map row");
            require(found.isShown() == shownExpected, "Map settings' Dark map row is " + (found.isShown() ? "shown" : "hidden") + " with ride maps "
                    + settings().getBoolean("map_basemap_enabled", false) + ", card " + settings().getBoolean(JustBackBasemap.KEY, false));
            row[0] = (Switch) ((ViewGroup) found).getChildAt(0);
            if (shownExpected) {
                requireLabel(row[0], "Map settings' Dark map row");
                boolean dark = MapStyle.dark(settings().getString(MapStyle.KEY, null), "dark".equals(theme));
                require(row[0].isChecked() == dark, "Map settings' Dark map row shows " + row[0].isChecked() + " while the maps draw " + (dark ? "dark" : "light"));
            }
        });
        if (shownExpected) shot(theme + "-theme-map-settings");
        if (flip) { main(() -> row[0].performClick()); settle(); }
        main(() -> dialog[0].dismiss());
        settle();
    }

    /** Opens the Just back card's ride (its details in a window of their own) with the inline map on screen. */
    private void openRide() throws Exception {
        showJustBack();
        main(() -> require(card().performClick(), "The card did not open its ride"));
        settle();
        await(() -> inlineMap() != null, 10000, "The ride's map did not open from the card");
        main(() -> { View map = inlineMap(); map.requestRectangleOnScreen(new android.graphics.Rect(0, 0, map.getWidth(), map.getHeight()), true); });
        SystemClock.sleep(600); settle();
    }

    /** The inline map's expand button: the Route screen opens with the expanded map. */
    private void expand() throws Exception {
        main(() -> {
            View button = byDescription(inlineMap().getRootView(), T.ridesMapExpand());
            require(button != null, "The inline map has no expand button");
            require(button.performClick(), "The expand button took no tap");
        });
        settle();
        await(() -> expandedMap() != null, 10000, "The expanded map did not open");
        SystemClock.sleep(600); settle();
    }

    private void closeExpanded() throws Exception {
        main(() -> {
            View button = byDescription(expandedMap().getRootView(), T.ridesMapCloseExpanded());
            require(button != null && button.performClick(), "The expanded map's close button took no tap");
        });
        settle();
        await(() -> expandedMap() == null, 10000, "The expanded map did not close");
        SystemClock.sleep(400); settle();
    }

    /** Back closes the ride's details; then Home. */
    private void closeRide() throws Exception {
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        settle();
        await(() -> anyMap() == null, 10000, "The ride's details did not close on Back");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle();
    }

    /** Back until no ride map is open (the expanded map, then the details), then Home. */
    private void closeAllRideWindows() throws Exception {
        for (int i = 0; i < 4 && onMain(() -> anyMap() != null); i++) { sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); settle(); SystemClock.sleep(300); }
        await(() -> anyMap() == null, 10000, "A ride map is still open after Back");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home"));
        settle();
    }

    /** The expanded map's Dark map switch, tapped once. */
    private void flipExpanded() throws Exception {
        main(() -> {
            Switch style = styleSwitch(expandedMap());
            require(style != null && style.isShown(), "The expanded map has no Dark map switch");
            boolean was = style.isChecked();
            // A Switch with no click listener answers false; the state is the proof it took the tap.
            style.performClick();
            require(style.isChecked() != was, "The Dark map switch took no tap");
        });
        settle(); SystemClock.sleep(300); settle();
    }

    /** The expanded map coloured by speed, so the ramp's dark and light ends both show against the tiles. */
    private void speedColours() throws Exception {
        main(() -> {
            View option = byText(expandedMap().getRootView(), T.ridesRouteColorSpeed());
            require(option != null && option.performClick(), "The expanded map's Speed colour took no tap");
        });
        settle();
        main(() -> require("speed".equals(expandedMap().metric()), "The expanded map is not coloured by speed"));
    }

    /** The Just back card's tiles are drawn dark (or not), and its basemap is on. */
    private void requireCard(boolean dark, String what) {
        main(() -> {
            View route = route();
            boolean drawn = (Boolean) invoke(route, "darkTiles", new Class<?>[0]);
            require(drawn == dark, what + ": the Just back card draws " + (drawn ? "dark" : "light") + " tiles");
        });
    }

    /** The inline map draws dark (or not); its Dark map switch shows with the basemap on and is checked as it draws. */
    private void requireInline(boolean dark, boolean basemap, String what) {
        main(() -> {
            RouteMapView map = inlineMap();
            require(map != null, what + ": no inline map");
            Switch style = styleSwitch(map);
            require(style != null, what + ": no Dark map switch beside the inline map's Basemap switch");
            require(style.isShown() == basemap, what + ": the Dark map switch is " + (style.isShown() ? "shown" : "hidden") + " with the basemap " + (basemap ? "on" : "off"));
            if (!basemap) return;
            require(map.darkTiles() == dark, what + ": the inline map draws " + (map.darkTiles() ? "dark" : "light") + " tiles");
            require(style.isChecked() == dark, what + ": the inline Dark map switch reads " + style.isChecked());
            requireSwitchRow(style, what + ", inline");
        });
    }

    private void requireExpanded(boolean dark, String what) {
        main(() -> {
            RouteMapView map = expandedMap();
            require(map != null, what + ": no expanded map");
            require(map.darkTiles() == dark, what + ": the expanded map draws " + (map.darkTiles() ? "dark" : "light") + " tiles");
            Switch style = styleSwitch(map);
            require(style != null && style.isShown(), what + ": the expanded map has no Dark map switch");
            require(style.isChecked() == dark, what + ": the expanded Dark map switch reads " + style.isChecked());
            requireSwitchRow(style, what + ", expanded");
        });
    }

    /** The switch: a 48 dp target, named and spoken as a switch, its label whole on one line; beside Basemap or below it. */
    private void requireSwitchRow(Switch style, String what) {
        float density = style.getResources().getDisplayMetrics().density;
        require(style.getHeight() >= Math.round(48 * density), what + ": the Dark map switch is " + style.getHeight() + " px tall, under 48 dp");
        requireLabel(style, what + ": the Dark map switch");
        AccessibilityNodeInfo node = style.createAccessibilityNodeInfo();
        require(T.mapsDarkMap().contentEquals(node.getContentDescription()) && node.isCheckable() && node.isChecked() == style.isChecked(),
                what + ": TalkBack hears '" + node.getContentDescription() + "' checkable=" + node.isCheckable());
        node.recycle();
        // The row: the Basemap switch's own row (MapPreferences.basemapControl) first, then Dark map.
        ViewGroup row = (ViewGroup) style.getParent();
        View basemapRow = row.getChildAt(0);
        Switch basemap = (Switch) ((ViewGroup) basemapRow).getChildAt(0);
        requireLabel(basemap, what + ": the Basemap switch beside it");
        boolean beside = ((android.widget.LinearLayout) row).getOrientation() == android.widget.LinearLayout.HORIZONTAL;
        require(beside ? basemapRow.getRight() <= style.getLeft() || style.getRight() <= basemapRow.getLeft() : style.getTop() >= basemapRow.getBottom(),
                what + ": the two switches overlap");
        String note = size + " " + what + ": Dark map " + (beside ? "beside Basemap" : "on its own row under Basemap") + ", "
                + Math.round(style.getWidth() / density) + " dp wide";
        if (!checks.contains(note)) checks.add(note);
    }

    /** A switch's label is its full text on one line, never cut or shortened. */
    private void requireLabel(Switch view, String what) {
        android.text.Layout lines = view.getLayout();
        require(lines != null && lines.getLineCount() == 1 && lines.getEllipsisCount(0) == 0, what + " label is cut or wrapped: '" + view.getText() + "'");
    }

    /** With the dark style over the light theme, the expanded map's paper (under tiles still loading) is dark. */
    private void requireDarkPaper() throws Exception {
        Bitmap[] drawn = {null};
        main(() -> {
            RouteMapView map = expandedMap();
            // The paper alone: the map drawn with its tile cache emptied, then its tiles asked for again.
            ((android.util.LruCache<?, ?>) field(map, "tiles")).evictAll();
            Bitmap bitmap = Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
            map.draw(new android.graphics.Canvas(bitmap));
            drawn[0] = bitmap;
        });
        // A corner well away from the route, the scale chip and the controls: the middle of the left edge.
        int pixel = drawn[0].getPixel(Math.max(1, drawn[0].getWidth() / 40), drawn[0].getHeight() / 2);
        drawn[0].recycle();
        int light = Math.round(.2126f * ((pixel >> 16) & 255) + .7152f * ((pixel >> 8) & 255) + .0722f * (pixel & 255));
        require(light < 80, "The dark map's paper under loading tiles is light in the light theme: #" + Integer.toHexString(pixel));
        main(() -> expandedMap().invalidate());
        checks.add(size + " light theme, dark map: the paper under tiles still loading is dark (#" + Integer.toHexString(pixel & 0xffffff) + "), no light flash");
    }

    /** Tiles on the card (with a network), or a note that none came. */
    private void waitForCardTiles() {
        boolean drawn = waitFor(() -> (Boolean) field(route(), "mapShown"), 20000);
        SystemClock.sleep(700); settle();
        if (!drawn) checks.add(size + " no tile reached the Just back card in 20 s (emulator without a network?)");
    }

    private void waitForExpandedTiles() {
        boolean drawn = waitFor(() -> { RouteMapView map = expandedMap(); return map != null && ((android.util.LruCache<?, ?>) field(map, "tiles")).size() > 3; }, 20000);
        SystemClock.sleep(900); settle();
        if (!drawn) checks.add(size + " no tile reached the expanded map in 20 s (emulator without a network?)");
    }

    /** The Dark map switch in the controls of map's panel (its window's), or null. */
    private static Switch styleSwitch(RouteMapView map) {
        View found = map.getRootView().findViewWithTag(MapStyleControls.TAG_RIDE);
        return found instanceof Switch ? (Switch) found : null;
    }

    /** The ride details' inline map (a minimum height; the expanded one has none), or null. */
    private static RouteMapView inlineMap() { for (RouteMapView map : maps()) if (map.getMinimumHeight() > 0) return map; return null; }
    private static RouteMapView expandedMap() { for (RouteMapView map : maps()) if (map.getMinimumHeight() == 0) return map; return null; }
    private static RouteMapView anyMap() { List<RouteMapView> all = maps(); return all.isEmpty() ? null : all.get(0); }

    /** Every ride map shown in any of the app's windows. */
    private static List<RouteMapView> maps() {
        List<RouteMapView> out = new ArrayList<>();
        for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (window.isAttachedToWindow()) collect(window, out);
        return out;
    }

    private static void collect(View view, List<RouteMapView> out) {
        if (view instanceof RouteMapView && view.isShown()) { out.add((RouteMapView) view); return; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }

    private static View byDescription(View view, String description) {
        if (view.isShown() && description.contentEquals(String.valueOf(view.getContentDescription()))) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private static View byText(View view, String text) {
        if (view.isShown() && view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    /** The emulator's size override as the run found it ("1080x2400"), or null for none. */
    private String overrideSize() {
        Matcher m = Pattern.compile("Override size: (\\d+x\\d+)").matcher(shellText("wm size"));
        return m.find() ? m.group(1) : null;
    }

    private String shellText(String command) {
        try (ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException(command, e); }
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
                    rows.append(PhoneRideStore.row("GPS", start + i * gap, mono + i * gap, fixes[i][0], fixes[i][1], 4, gpsSpeed(i, fixes.length), 0.2, 1500, 4));
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
