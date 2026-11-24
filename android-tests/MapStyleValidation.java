package com.jjricks.volt;

import com.jjricks.volt.tests.ValidationInstrumentation;
import android.Manifest;
import android.app.AlertDialog;
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
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disposable empty emulator, radio off. The online maps' shared dark/light style (MapStyle; owner, Nov 23 2026: "can we
 * have a toggle for that on the ride recording map itself as well? Remember and save what the user picked last and keep
 * it. Then that toggle could control both the 'just back' and ride recording maps together"), at the emulator's current
 * size, on the developer simulator with a synthesized finished ride (a loop in a city, its phone journal's GPS fixes with
 * a speed that rises and falls), both basemaps on:
 * <ul>
 *   <li>Never picked, the dark theme: the Just back card and the ride's maps (inline and expanded) draw dark tiles; the
 *       Dark map switch beside the Basemap switch is shown, checked, named Dark map and a switch to TalkBack, its label
 *       whole (beside Basemap or on its own row).</li>
 *   <li>Flipped on the expanded ride map: "light" is saved; the expanded and inline maps and, back on Home, the Just back
 *       card all draw light.</li>
 *   <li>The light theme with that saved pick: still light everywhere. Flipped on the expanded map there: "dark" saved,
 *       both maps dark in the light theme, and the expanded map's paper under its tiles is dark too.</li>
 *   <li>The dark theme with the dark pick: dark. The pick removed (never picked): the light theme draws light and the
 *       dark theme dark again.</li>
 *   <li>A fold change (the emulator's size switched to the other form and back) with Home and then the expanded map
 *       open: the style holds, and a flip at the other size reaches the card.</li>
 *   <li>The ride maps' basemap off: no Dark map switch beside Basemap. Map settings: the Dark map row shows while either
 *       basemap is on, flips the shared style (the card follows), and hides with both off.</li>
 * </ul>
 * Pictures (argument size_label names them): the Just back card and the expanded ride map (speed colours, so the ramp
 * and the S/E markers show) dark and light in both app themes, the inline map with its switches, and Map settings' row
 * in both themes. Sends no scooter command.
 */
public final class MapStyleValidation extends ValidationInstrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String size = "size";
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    /** Where the fixture ride is: a synthetic loop about 3 km across. */
    private static final double LAT = 45.5200, LON = -122.6800;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String sizeBefore = null;
        try {
            admit();
            size = args.getString("size_label", "size");
            sizeBefore = overrideSize();
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            SharedPreferences.Editor seed = settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", "dark").putString("units", "mi").putFloat("text_scale", 1f)
                    .putString("home_style", "clean").putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .putBoolean("map_basemap_enabled", true).putBoolean("map_basemap_explained", true).putBoolean(JustBackBasemap.KEY, true);
            MapSource.OSM.put(seed);
            require(seed.commit(), "Could not seed preferences");
            launch();
            simulate();
            seedRide(loop(), 5_000L);
            darkThemeNeverPicked();
            lightThemeKeepsPick();
            darkThemeKeepsPick();
            neverPickedFollowsTheme();
            foldChange(sizeBefore);
            basemapOff();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            try { shellText(sizeBefore == null ? "wm size reset" : "wm size " + sizeBefore); } catch (Throwable ignored) {}
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- Scenarios -----------------------------------------------------------------------------------------------

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

    // ---- Steps ---------------------------------------------------------------------------------------------------

    /** Map settings' Dark map row: shown or hidden; when shown, checked as the maps draw, and (flip) tapped once. */
    private void settingsRow(String theme, boolean shownExpected, boolean flip) throws Exception {
        AlertDialog[] dialog = {null};
        main(() -> dialog[0] = MapPreferences.show((ScreenHost) field(clean(), "host")));
        settle(); SystemClock.sleep(400);
        Switch[] row = {null};
        main(() -> {
            View found = dialog[0].getWindow().getDecorView().findViewWithTag("map-settings-style");
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
            require(style.performClick(), "The Dark map switch took no tap");
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

    // ---- Checks --------------------------------------------------------------------------------------------------

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
        ViewGroup row = (ViewGroup) style.getParent();
        Switch basemap = (Switch) row.getChildAt(0);
        requireLabel(basemap, what + ": the Basemap switch beside it");
        boolean beside = ((android.widget.LinearLayout) row).getOrientation() == android.widget.LinearLayout.HORIZONTAL;
        require(beside ? basemap.getRight() <= style.getLeft() || style.getRight() <= basemap.getLeft() : style.getTop() >= basemap.getBottom(),
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

    // ---- Finding views -------------------------------------------------------------------------------------------

    private View card() { View card = root().findViewWithTag(JustBackView.TAG); require(card != null, "No Just back card"); return card; }
    private View route() { return (View) field(card(), "route"); }

    /** The Dark map switch in the controls of map's panel (its window's), or null. */
    private static Switch styleSwitch(RouteMapView map) {
        View found = map.getRootView().findViewWithTag(MapPreferences.TAG_RIDE_STYLE);
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

    // ---- Fixtures ------------------------------------------------------------------------------------------------

    private static double[][] loop() {
        double[][] fixes = new double[121][];
        for (int i = 0; i <= 120; i++) {
            double t = i / 120.0 * 2 * Math.PI;
            fixes[i] = new double[]{LAT + 0.010 * Math.sin(t) + 0.003 * Math.sin(3 * t), LON + 0.020 * (1 - Math.cos(t)) - 0.004 * Math.sin(2 * t)};
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
                String id = rides.start(profile, "Synthetic map style ride", start, mono);
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
                // GPS speed rises and falls twice around the loop (2 to 12 m/s), so the speed colours span their ramp.
                for (int i = 0; i < fixes.length; i++) {
                    double speed = 7 - 5 * Math.cos(i / (double) (fixes.length - 1) * 4 * Math.PI);
                    rows.append(PhoneRideStore.row("GPS", start + i * gap, mono + i * gap, fixes[i][0], fixes[i][1], 4, String.format(Locale.US, "%.2f", speed), 0.2, 1500, 4));
                }
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
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "map-style-validation"); d.mkdirs(); return d; }
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
