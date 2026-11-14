package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.Fmt;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.PhoneRideStore;
import com.jjricks.volt.RidesScreen;
import com.jjricks.volt.RouteMapView;
import com.jjricks.volt.ScooterCommands;
import com.jjricks.volt.ScooterService;
import com.jjricks.volt.T;
import com.jjricks.volt.Ui;

/** UI checks on a fresh emulator only. No owner profile, sensor recording, radio or network work. */
public final class UiOrganizationValidation extends Instrumentation {
    private static final String[] PREFS = {"app_settings", "profiles", "scooter", "pending_documents", "onboarding"};
    private final List<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private File fixtureDirectory;
    private AlertDialog preview;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            requireFreshEmulator(); admitted = true;
            SharedPreferences settings = settings();
            require(settings.edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").putString("more_section", "App").commit(), "Cannot set emulator UI preferences");
            // These runners check the navigation, so skip the first-run onboarding a fresh install shows. A saved More
            // submenu ("App") stays in the fixture: Settings must open on its index whatever the old tab saved.
            OnboardingScreen.markDone(getTargetContext());
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, 6000,
                    "Local workspace did not initialize");
            // The tab bar's five destinations (Home, Scooter, Live Stats, Rides, More) are Home and the pages that hold them
            // since part 11 (#392): Search (the Scooter index's search and subjects), Data, Ride and Settings, each reached
            // from Home by the rider's taps (the dock or its All sheet on Widgets Home, Clean Home's rows, search and gear).
            for (String page : DESTINATIONS) {
                if (HubPages.HOME.equals(page)) main(() -> HubTestPages.open(activity, HubPages.HOME));
                else HubTestPages.reach(this, activity, page);
                waitForIdleSync(); hideKeyboard();
                main(() -> require(page.equals(HubTestPages.current(activity)), "Navigation did not reach " + page + " (" + HubTestPages.current(activity) + ")"));
                assertNoScooterWork(); assertNavigationFits();
            }
            requireScreenText(APP_PREFERENCES_ROW);
            check("Home and the four pages that replaced the tabs are reachable by touch; Home's ways in and every page's back link remain visible");
            screenshot("more-index-dark");

            // The Hub layout (#385) made the Scooter tab an index of subject pages: its RIDE, BATTERY and PROFILE groups
            // became Controls, Battery & charging and Lights & sound pages, and Rename (the Profile group) left the Scooter
            // pages for Settings › My scooters. Part 11 (#392) replaced the index with Search, which lists Home's seven
            // subjects while its field is empty. The same rows are checked on the pages that hold them now.
            HubTestPages.reach(this, activity, HubPages.SEARCH); hideKeyboard();
            main(() -> {
                require(HubPages.SEARCH.equals(HubTestPages.current(activity)), "Search did not open");
                for (String subject : SUBJECTS) require(tagShown(subject(subject)), "Search lists no " + subject + " subject");
                // Part 12 (#393): Search is its own page (HubSearchScreen), results only once something is typed.
                require(searchFound().isEmpty(), "Search shows results before a search: " + searchFound());
            });
            screenshot("scooter-dark");
            clickTag(subject(HubPages.CONTROLS));
            requireVisibleControl("energy_recovery");
            main(() -> {
                require(HubPages.CONTROLS.equals(HubTestPages.current(activity)), "Search's Controls row did not open Controls");
                require(topSpeedShown(), "Top speed card missing from the Controls page");
                // Every control has a row, hidden unless offered; Maximum speed's row shows only where another model's map replaces the card.
                require(allFeatures().contains("maximum_speed") && !visibleFeatures().contains("maximum_speed"), "Maximum speed must live only in the top speed card");
                for (String key : visibleFeatures()) require(!isLight(key), "Lighting rows belong to Lights & sound, not Controls");
            });
            screenshot("controls-dark");
            clickTag("hub-link-" + HubPages.CONTROLS_ASSISTS);
            requireVisibleControl("start_speed");
            main(() -> require(HubPages.CONTROLS_ASSISTS.equals(HubTestPages.current(activity)), "Controls' Assists link did not open its page"));
            HubTestPages.reach(this, activity, HubPages.SEARCH); hideKeyboard();
            main(() -> first(root(), EditText.class).setText("speed"));
            waitForIdleSync();
            main(() -> {
                // Search's results (part 12): each setting found is a row tagged hub-search-setting-<row or control key>.
                Set<String> found = searchFound();
                require(found.contains(TOP_SPEED_ROW) && found.contains("start_speed"), "Speed search lost primary speed controls: " + found);
                require(!found.contains("energy_recovery"), "Speed search did not hide an unrelated setting");
                for (String key : found) require(!isLight(key), "Speed search should hide lighting settings: " + key);
                require(findText(root(), "LIGHTS") == null, "Speed search should hide the empty Lights group");
                require(!tagShown(subject(HubPages.BATTERY)), "Speed search left the subject list shown");
            });
            main(() -> invoke(activity, "navigateBack"));
            main(() -> require(searchFound().isEmpty() && "".equals(field(field(activity, "screen"), "query")), "Back did not clear the settings search"));
            main(() -> require(HubPages.SEARCH.equals(HubTestPages.current(activity)) && tagShown(subject(HubPages.BATTERY)), "Back did not bring back the subject list"));
            check("Search lists the subjects; Controls shows the top speed card; search hides empty groups and Back clears it without control writes");

            // The Lights page's HEADLIGHTS, LINE LIGHTS and STAR LIGHTS groups are Lights & sound and its Line lights and
            // Star lights pages since the Hub layout (#385); the search over every row is Search's (the index's until part 11).
            clickTag(subject(HubPages.LIGHTS));
            main(() -> {
                Set<String> expected = new HashSet<>(Arrays.asList("headlight", "auto_sensor", "line_light", "line_pattern",
                        "line_color", "line_boost_color", "star_light", "star_pattern", "star_color", "star_boost_color"));
                require(allFeatures().containsAll(expected), "Scooter must retain all lighting settings");
                require(lightFeatures().equals(new HashSet<>(Arrays.asList("headlight", "auto_sensor"))), "Lights & sound shows lighting rows " + lightFeatures());
                require(!topSpeedShown(), "The Lights page should not show the top speed card");
                require(tagShown("hub-link-" + HubPages.LIGHTS_LINE) && tagShown("hub-link-" + HubPages.LIGHTS_STAR), "Lights page has no Line lights and Star lights links");
                require(tagShown("hub-back"), "Lights page has no way back to Search");
            });
            screenshot("lights-dark");
            for (String[] family : new String[][]{{HubPages.LIGHTS_LINE, "line_light", "line_pattern", "line_color", "line_boost_color"},
                    {HubPages.LIGHTS_STAR, "star_light", "star_pattern", "star_color", "star_boost_color"}}) {
                clickTag("hub-link-" + family[0]);
                main(() -> {
                    require(family[0].equals(HubTestPages.current(activity)), "The " + family[0] + " link did not open its page");
                    require(lightFeatures().equals(new HashSet<>(Arrays.asList(family).subList(1, family.length))), family[0] + ": a lighting zone is hidden: " + lightFeatures());
                    require(!topSpeedShown() && tagShown("hub-back"), family[0] + " shows the top speed card or has no way back");
                });
                screenshot(family[0].replace('.', '-') + "-dark");
                main(() -> invoke(activity, "navigateBack"));
                main(() -> require(HubPages.LIGHTS.equals(HubTestPages.current(activity)), "Back from " + family[0] + " did not return to Lights & sound"));
            }
            main(() -> invoke(activity, "navigateBack"));
            main(() -> require(HubPages.SEARCH.equals(HubTestPages.current(activity)) && tagShown(subject(HubPages.BATTERY)) && settings.getString("scooter_section", "").isEmpty(),
                    "Back from Lights did not return to Search"));
            main(() -> first(root(), EditText.class).setText("boost"));
            waitForIdleSync();
            main(() -> require(foundFeatures().equals(new HashSet<>(Arrays.asList("line_boost_color", "star_boost_color", "parking_assist"))),
                    "Global scooter search should find both Boost colors and Power Assist: " + foundFeatures()));
            main(() -> invoke(activity, "navigateBack"));
            main(() -> require(HubPages.SEARCH.equals(HubTestPages.current(activity)) && "".equals(field(field(activity, "screen"), "query")),
                    "Back did not clear the boost search"));
            check("Lights & sound links Line lights and Star lights, which hold the zones; Search covers all settings; Back returns to Search");

            // More › App preferences became Settings' own first card (part 11): Settings opens on it, one column on every
            // width, and its Back is the page stack's, back to Home (there is no menu pane to keep).
            HubTestPages.reach(this, activity, HubPages.SETTINGS);
            requireScreenText(APP_PREFERENCES_ROW); screenshot("preferences-dark");
            main(() -> invoke(activity, "navigateBack"));
            main(() -> require(HubPages.HOME.equals(HubTestPages.current(activity)), "Back from Settings did not return Home"));
            HubTestPages.reach(this, activity, HubPages.SETTINGS);
            requireScreenText("My scooters");
            check("Settings shows the app preferences and My scooters on its index; Back returns Home");
            verifyAwayReturn(settings);

            HubTestPages.reach(this, activity, HubPages.DATA);
            // Wide Stats keeps Readings in its own pane beside the sections, without a Readings tab.
            if (!Boolean.TRUE.equals(field(field(activity, "screen"), "wide"))) clickText(root(), "Readings");
            Object stats = field(activity, "screen");
            @SuppressWarnings("unchecked") Map<String, Object> readings = (Map<String, Object>) field(stats, "rows");
            View voltage = (View) field(readings.get("voltage_v"), "layout"); click(voltage);
            AlertDialog details = (AlertDialog) field(stats, "metricDialog");
            require(details != null && details.isShowing(), "Reading source details did not open");
            click(details.getButton(AlertDialog.BUTTON_NEUTRAL));
            require(settings.getStringSet("stats_favourites", new HashSet<>()).contains("voltage_v"), "Favourite action was not saved");
            screenshot("stats-readings-dark");
            check("Reading detail opens on tap; favourite action remains available without repeated source labels");

            main(() -> settings.edit().putString("theme", "light").putFloat("text_scale", 1.4f).apply());
            await(() -> !activity.ui().dark && activity.ui().textScale > 1.3f, 4000, "Large light theme did not apply");
            main(() -> HubTestPages.open(activity, HubPages.HOME)); waitForIdleSync(); assertNavigationFits();
            HubTestPages.reach(this, activity, HubPages.SEARCH); hideKeyboard(); assertNavigationFits(); screenshot("scooter-large-light");
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "lights")); waitForIdleSync(); assertNavigationFits(); screenshot("lights-large-light");
            HubTestPages.reach(this, activity, HubPages.SETTINGS); requireScreenText(APP_PREFERENCES_ROW); screenshot("preferences-large-light");
            check("Light theme and 140% app text preserve navigation and grouped settings");
            main(() -> settings.edit().putString("theme", "dark").putFloat("text_scale", 1f).apply());
            await(() -> activity.ui().dark && activity.ui().textScale == 1f, 4000, "Dark theme did not restore");

            PhoneRideStore.Track fixture = syntheticTrack();
            require(fixture.locationCount >= 30 && fixture.maxGpsAccelerationMps2 != null, "Synthetic UI fixture did not load");
            // The Rides tab is the Ride page (HubPages' legacy "rides").
            HubTestPages.reach(this, activity, HubPages.RIDE);
            Object rides = HubTestPages.part(activity, "RidesScreen");
            main(() -> {
                settings.edit().putBoolean("map_basemap_enabled", false).commit();
                invoke(rides, "presentPhoneRide", new Class<?>[]{PhoneRideStore.Track.class}, fixture);
                @SuppressWarnings("unchecked") List<AlertDialog> dialogs = (List<AlertDialog>) field(rides, "dialogs");
                preview = dialogs.get(dialogs.size() - 1);
                preview.setTitle("Synthetic UI preview · Route");
            });
            await(() -> preview.isShowing() && first(preview.getWindow().getDecorView(), RouteMapView.class).getWidth() > 0,
                    4000, "Route preview did not attach");
            View dialogRoot = preview.getWindow().getDecorView();
            RouteMapView map = first(dialogRoot, RouteMapView.class);
            require(!((Boolean)field(map, "streets")), "Explicitly disabled basemap must remain offline");
            verifyMapSources(map);
            reveal(map);
            main(() -> {
                require(((Number)field(map, "selected")).intValue() == -1, "Route readings should wait for a deliberate line tap");
                java.util.List<?> route = (java.util.List<?>)field(map, "points");
                PhoneRideStore.Point point = (PhoneRideStore.Point)route.get(route.size() / 2);
                float x = ((Number)invoke(map, "screenX", new Class<?>[]{PhoneRideStore.Point.class}, point)).floatValue();
                float y = ((Number)invoke(map, "screenY", new Class<?>[]{PhoneRideStore.Point.class}, point)).floatValue();
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, x, y, 0);
                try { map.dispatchTouchEvent(down); map.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
                require(((Number)field(map, "selected")).intValue() >= 0, "Tapping the route did not select the tapped point");
                View power = findReadout(dialogRoot, "Power, ");
                require(power != null && !"Power, not measured".contentEquals(power.getContentDescription()),
                        "Tapping the route did not expose synchronized readings");
            });
            verifyMapGestures(map);
            clickText(dialogRoot, "Grade"); clickText(dialogRoot, "Accel"); clickText(dialogRoot, "Power");
            clickDescription(dialogRoot, "Zoom in"); clickDescription(dialogRoot, "Zoom out"); clickDescription(dialogRoot, "Fit route");
            ScrollView scroller = first(dialogRoot, ScrollView.class);
            main(() -> scroller.fullScroll(View.FOCUS_UP)); waitForIdleSync();
            screenshot("synthetic-route-top-dark");
            main(() -> scroller.fullScroll(View.FOCUS_DOWN)); waitForIdleSync();
            // The phone section's own export button became ride details' one Export ride data sheet (#253):
            // the preview now ends with the motion-sample note, which must still scroll fully into view.
            TextView last = findText(dialogRoot, T.ridesMotionSamples(fixture.motionSampleCount, Fmt.groupedInteger(java.util.Locale.US, fixture.motionSampleCount)));
            require(last != null, "Route preview's last block is missing"); reveal(last); requireFullyVisible(last, "Route preview's last block");
            require(findText(dialogRoot, T.ridesExportData()) == null, "Route preview kept a separate export action");
            screenshot("synthetic-route-bottom-dark");
            main(() -> {
                require(!((Boolean)field(map, "streets")), "Preview enabled network tile loading");
                require(((Set<?>)field(map, "pending")).isEmpty(), "Offline preview queued a tile download");
            });
            click(preview.getButton(AlertDialog.BUTTON_POSITIVE)); preview = null;
            assertNoScooterWork();
            check("Synthetic route renders offline, responds to touch/zoom/color controls, and scrolls to its last block");

            result.putString("result", "PASS"); result.putStringArrayList("checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            result.putInt("check_count", checks.size()); result.putInt("scooter_mutations", 0);
            result.putInt("width_dp", getTargetContext().getResources().getConfiguration().screenWidthDp);
            result.putBoolean("hardware_sensor_claims", false); result.putBoolean("street_tiles_enabled", false);
            cleanup(); finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            result.putStringArrayList("completed_checks", new ArrayList<>(checks));
            result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            try { if (admitted && activity != null && !activity.isDestroyed()) screenshot("failure"); } catch (Throwable ignored) { }
            try { cleanup(); } catch (Throwable failure) { result.putString("cleanup_error", failure.toString()); }
            finish(1, result);
        }
    }

    private void verifyMapGestures(RouteMapView map) {
        main(() -> {
            AtomicInteger taps = new AtomicInteger(); map.setOnClickListener(v -> taps.incrementAndGet());
            try {
                long now = SystemClock.uptimeMillis();
                float cx = map.getWidth()*.6f, cy = map.getHeight()*.45f;
                float half = Math.min(map.getWidth()*.12f, map.getHeight()*.1f);
                float dx = activity.ui().dp(13), dy = activity.ui().dp(9);
                double oldZoom = number(map, "zoom");
                double anchorX = mapCoordinate(map, cx, true), anchorY = mapCoordinate(map, cy, false);
                mapTouch(map, now, 0, MotionEvent.ACTION_DOWN, new int[]{3}, cx-half,cy);
                mapTouch(map, now, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{3,7}, cx-half,cy,cx+half,cy);
                float fx = cx+dx, fy = cy+dy, spread = half*1.4f;
                mapTouch(map, now, 2, MotionEvent.ACTION_MOVE, new int[]{3,7}, fx-spread,fy,fx+spread,fy);
                double nextZoom = number(map, "zoom");
                require(Math.abs(nextZoom-(oldZoom+Math.log(1.4)/Math.log(2))) < .00001,
                        "Pinch did not produce smooth fractional zoom");
                require(Math.abs(nextZoom-Math.rint(nextZoom)) > .05, "Pinch snapped to a whole zoom level");
                assertMapAnchor(map, fx, fy, anchorX, anchorY, "Pinch focal point drifted");

                // Reorder pointer indices while moving the centroid, with their separation unchanged.
                fx += dx; fy -= dy;
                mapTouch(map, now, 3, MotionEvent.ACTION_MOVE, new int[]{7,3}, fx+spread,fy,fx-spread,fy);
                require(Math.abs(number(map, "zoom")-nextZoom) < .00001, "Two-finger pan changed zoom");
                assertMapAnchor(map, fx, fy, anchorX, anchorY, "Two-finger pan lost its anchor");
                double beforeX = number(map,"centerX"), beforeY = number(map,"centerY");
                // Lift pointer 3 and continue with pointer 7 at its own coordinates.
                mapTouch(map, now, 4, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{7,3}, fx+spread,fy,fx-spread,fy);
                mapTouch(map, now, 5, MotionEvent.ACTION_MOVE, new int[]{7}, fx+spread,fy);
                require(Math.abs(number(map,"centerX")-beforeX) < 1e-10 && Math.abs(number(map,"centerY")-beforeY) < 1e-10,
                        "Map jumped when the first finger lifted");
                double handoffX = mapCoordinate(map, fx+spread, true), handoffY = mapCoordinate(map, fy, false);
                mapTouch(map, now, 6, MotionEvent.ACTION_MOVE, new int[]{7}, fx+spread+dx,fy+dy);
                assertMapAnchor(map, fx+spread+dx, fy+dy, handoffX, handoffY, "Remaining finger did not pan naturally");
                mapTouch(map, now, 7, MotionEvent.ACTION_UP, new int[]{7}, fx+spread+dx,fy+dy);
                require(taps.get() == 0, "Pinching or handing off a pointer selected a route point");
                require(((Integer)field(map,"activePointer")) == -1, "Final pointer-up did not end the gesture");

                map.zoomBy(100); pinchAtBound(map, now+200, cx, cy, half, 1.5f, 19);
                map.zoomBy(-100); pinchAtBound(map, now+400, cx, cy, half, .6f, 2);
                require(taps.get() == 0, "A clamped pinch selected a route point");

                // Cancellation must leave the next one-finger tap usable and keep small jitter still.
                mapTouch(map, now+600, 0, MotionEvent.ACTION_DOWN, new int[]{3}, cx-half,cy);
                mapTouch(map, now+600, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        new int[]{3,7}, cx-half,cy,cx+half,cy);
                mapTouch(map, now+600, 2, MotionEvent.ACTION_CANCEL, new int[]{3,7}, cx-half,cy,cx+half,cy);
                require(((Integer)field(map,"activePointer")) == -1, "Cancel did not release the active pointer");
                beforeX = number(map,"centerX"); beforeY = number(map,"centerY");
                mapTouch(map, now+800, 0, MotionEvent.ACTION_DOWN, new int[]{4}, cx,cy);
                mapTouch(map, now+800, 1, MotionEvent.ACTION_MOVE, new int[]{4}, cx+1,cy+1);
                mapTouch(map, now+800, 2, MotionEvent.ACTION_UP, new int[]{4}, cx+1,cy+1);
                require(taps.get() == 1, "A normal tap stopped selecting after a canceled pinch");
                require(Math.abs(number(map,"centerX")-beforeX) < 1e-10 && Math.abs(number(map,"centerY")-beforeY) < 1e-10,
                        "Tap jitter moved the map before touch slop");
                require(!((Boolean)field(map,"streets")) && ((Set<?>)field(map,"pending")).isEmpty(),
                        "Offline gestures enabled map tile requests");
            } finally { map.setOnClickListener(null); map.fitRoute(); }
        }); waitForIdleSync();
        check("Pinch zoom and two-finger pan keep their focal point, pointer handoff stays steady, bounds clamp, and canceled pinches preserve taps");
    }
    private void pinchAtBound(RouteMapView map, long now, float x, float y, float half, float factor, double expected) {
        mapTouch(map, now, 0, MotionEvent.ACTION_DOWN, new int[]{3}, x-half,y);
        mapTouch(map, now, 1, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3,7}, x-half,y,x+half,y);
        mapTouch(map, now, 2, MotionEvent.ACTION_MOVE, new int[]{3,7}, x-half*factor,y,x+half*factor,y);
        require(number(map,"zoom") == expected, "Pinch exceeded the zoom bound " + expected);
        mapTouch(map, now, 3, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3,7}, x-half*factor,y,x+half*factor,y);
        mapTouch(map, now, 4, MotionEvent.ACTION_UP, new int[]{3}, x-half*factor,y);
    }
    private static double number(Object object, String name) { return ((Number)field(object,name)).doubleValue(); }
    private static double mapCoordinate(RouteMapView map, float pixel, boolean horizontal) {
        return number(map, horizontal ? "centerX" : "centerY")
                + (pixel-(horizontal ? map.getWidth() : map.getHeight())/2.0)/((Number)invoke(map,"world")).doubleValue();
    }
    private static void assertMapAnchor(RouteMapView map, float x, float y, double expectedX, double expectedY, String message) {
        double dx = mapCoordinate(map,x,true)-expectedX; dx -= Math.rint(dx);
        double dy = mapCoordinate(map,y,false)-expectedY;
        double world = ((Number)invoke(map,"world")).doubleValue();
        require(Math.abs(dx)*world < .01 && Math.abs(dy)*world < .01, message);
    }
    private static void mapTouch(RouteMapView map, long down, int step, int action, int[] ids, float... xy) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i=0; i<ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = xy[2*i]; coordinates[i].y = xy[2*i+1];
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down,down+step*16L,action,ids.length,properties,coordinates,0,0,1,1,0,0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try { require(map.dispatchTouchEvent(event), "Map did not consume touch action " + action); }
        finally { event.recycle(); }
    }

    private void verifyMapSources(RouteMapView map) throws Exception {
        String usgs = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}";
        String osm = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
        main(() -> {
            map.setSource(osm);
            require(((Integer)field(map, "maximumTileZoom")) == 19, "OpenStreetMap zoom limit");
            require(!((Boolean)field(map, "streets")), "Source changes preserve offline mode");
            map.setSource(usgs);
            require(((Integer)field(map, "maximumTileZoom")) == 16, "USGS zoom limit");
        });

        // Hold both existing workers so the old request can be canceled before any network work.
        ExecutorService executor = (ExecutorService)field(map, "TILES");
        CountDownLatch occupied = new CountDownLatch(2), releaseOne = new CountDownLatch(1), releaseTwo = new CountDownLatch(1);
        Future<?> firstWorker = executor.submit(() -> waitForWorker(occupied, releaseOne));
        Future<?> secondWorker = executor.submit(() -> waitForWorker(occupied, releaseTwo));
        String key = "2/1/1";
        try {
            require(occupied.await(5, TimeUnit.SECONDS), "Tile workers did not reach the queue fixture");
            main(() -> {
                map.setStreets(true);
                invoke(map, "request", new Class<?>[]{String.class, int.class, int.class, int.class}, key, 2, 1, 1);
                require(((Set<?>)field(map, "pending")).contains(key), "Old-provider request was not queued");
                map.setSource(usgs); map.setStreets(false);
                @SuppressWarnings("unchecked") Set<String> pending = (Set<String>)field(map, "pending");
                pending.add(key); // Stand in for the new source's matching key while keeping network loading off.
            });
            Future<?> drained = executor.submit(() -> {});
            releaseOne.countDown(); drained.get(5, TimeUnit.SECONDS); waitForIdleSync();
            main(() -> {
                require(((Set<?>)field(map, "pending")).contains(key), "A stale provider callback cleared the new source's pending tile");
                require(((android.util.LruCache<?,?>)field(map, "tiles")).size() == 0, "Stale provider supplied a tile");
                require(((Map<?,?>)field(map, "failed")).isEmpty(), "Stale provider supplied a failure");
            });
        } finally {
            main(() -> { map.setStreets(false); map.setSource(usgs); });
            releaseOne.countDown(); releaseTwo.countDown();
            firstWorker.get(5, TimeUnit.SECONDS); secondWorker.get(5, TimeUnit.SECONDS);
        }
        main(() -> require(((Set<?>)field(map, "pending")).isEmpty(), "Offline source has no queued tiles"));
        check("USGS and OpenStreetMap preserve offline loading; stale queued callbacks cannot alter the replacement source");
    }
    private static void waitForWorker(CountDownLatch occupied, CountDownLatch release) {
        occupied.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Tile queue fixture timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    /**
     * A row that shows the app preferences are open. It was Connect automatically until the Hub layout (#389) moved the
     * connection rows to Settings › My scooters; Haptic feedback stays with the app preferences, on Settings' index since part 11.
     */
    private static final String APP_PREFERENCES_ROW = "Haptic feedback";
    /** The tab bar's five destinations as part 11 (#392) holds them: Home, Search (Scooter), Data (Live Stats), Ride (Rides), Settings (More). */
    private static final String[] DESTINATIONS = {HubPages.HOME, HubPages.SEARCH, HubPages.DATA, HubPages.RIDE, HubPages.SETTINGS};
    private static final String[] SUBJECTS = {HubPages.RIDE, HubPages.BATTERY, HubPages.CONTROLS, HubPages.LIGHTS, HubPages.SECURITY, HubPages.SERVICE, HubPages.DATA};
    /** A subject's row on Search (and Clean Home, and the dock's All sheet): CleanSubjects' tag. */
    private static String subject(String page) { return "home-subject-" + page; }
    /**
     * More's one-minute submenu rule (its own away timer, expireMoreSection and more_section) became the Hub's away rule
     * in part 11 (#392): after a minute or more away from Volt, or a reboot, the open sub-pages close back to their
     * subject (HubNav.awayExpired, hub_away_elapsed / hub_away_boot); a brief return and a rebuild keep them, and Home or a
     * subject itself is never forced. Settings › Units stands in for More's App preferences submenu.
     */
    private void verifyAwayReturn(SharedPreferences settings) {
        openUnits();
        main(() -> activity.rebuild()); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS_UNITS.equals(HubTestPages.current(activity)), "A rebuild closed Settings › Units"));
        main(() -> require(!settings.contains(HUB_AWAY_AT), "Volt on screen must not run an away clock"));
        // The rule itself, at its edges (no long sleep): just under a minute keeps sub-pages, a minute or another boot closes them.
        long base = 1_000_000L;
        require(!awayExpired(base, 7, base + 59_999, 7), "Sub-pages expired before one minute");
        require(awayExpired(base, 7, base + 60_000, 7), "Sub-pages did not expire at one minute");
        require(awayExpired(base, 6, base + 1, 7), "Another boot did not expire sub-pages");
        require(!awayExpired(-1, 7, base, 7), "Sub-pages expired without an away time");

        // A brief leave: the clock starts once, a second leave does not move it, and coming back keeps the page and stops it.
        main(() -> callActivityOnPause(activity));
        long left = settings.getLong(HUB_AWAY_AT, -1);
        require(left >= 0, "Leaving Volt did not start the away clock");
        SystemClock.sleep(50);
        main(() -> invoke(activity, "markAway"));
        require(settings.getLong(HUB_AWAY_AT, -2) == left, "A second leave moved the away clock");
        main(() -> callActivityOnResume(activity)); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS_UNITS.equals(HubTestPages.current(activity)), "A brief return closed Settings › Units"));
        require(!settings.contains(HUB_AWAY_AT), "Coming back did not stop the away clock");
        check("Sub-pages stay through brief returns and rebuilds, no clock runs while Volt is on screen, and the rule expires at exactly one minute or another boot");

        // A minute away (the clock backdated): Settings › Units closes back to Settings, its subject.
        main(() -> {
            callActivityOnPause(activity);
            settings.edit().putLong(HUB_AWAY_AT, SystemClock.elapsedRealtime() - 60_000).commit();
            callActivityOnResume(activity);
        }); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS.equals(HubTestPages.current(activity)), "A minute away left " + HubTestPages.current(activity) + " open"));
        requireScreenText(APP_PREFERENCES_ROW);
        require(settings.getString("more_section", "").equals("App"), "Settings wrote the retired more_section");
        // Another boot: the same.
        openUnits();
        main(() -> {
            callActivityOnPause(activity);
            settings.edit().putInt(HUB_AWAY_BOOT, -2).commit();
            callActivityOnResume(activity);
        }); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS.equals(HubTestPages.current(activity)), "A reboot left " + HubTestPages.current(activity) + " open"));
        requireScreenText(APP_PREFERENCES_ROW);
        // A subject is never closed: an expired return to Settings itself stays there.
        main(() -> {
            callActivityOnPause(activity);
            settings.edit().putInt(HUB_AWAY_BOOT, -2).commit();
            callActivityOnResume(activity);
        }); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS.equals(HubTestPages.current(activity)), "An expired return closed Settings itself"));
        check("A minute or a reboot away closes Settings › Units back to Settings, which an expired return never closes");

        // Settings' Quick access shortcut (more:App) opens Settings over Home, and an expired return keeps it there.
        main(() -> HubTestPages.open(activity, HubPages.HOME)); waitForIdleSync();
        main(() -> {
            settings.edit().putLong(HUB_AWAY_AT, SystemClock.elapsedRealtime()).putInt(HUB_AWAY_BOOT, -2).commit();
            invoke(activity, "quickAction", new Class<?>[]{String.class}, "more:App");
        }); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS.equals(HubTestPages.current(activity)), "The Settings shortcut opened " + HubTestPages.current(activity)));
        requireScreenText(APP_PREFERENCES_ROW);
        main(() -> { callActivityOnPause(activity); callActivityOnResume(activity); }); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS.equals(HubTestPages.current(activity)), "An expired return replaced the Settings shortcut's page"));
        requireScreenText(APP_PREFERENCES_ROW);
        main(() -> invoke(activity, "navigateBack"));
        main(() -> require(HubPages.HOME.equals(HubTestPages.current(activity)), "Back from the Settings shortcut did not return Home"));
        main(() -> {
            settings.edit().putInt(HUB_AWAY_BOOT, -2).commit();
            invoke(activity, "quickAction", new Class<?>[]{String.class}, "section:Security");
        }); waitForIdleSync();
        main(() -> require(HubPages.SECURITY.equals(HubTestPages.current(activity)), "Security shortcut opened " + HubTestPages.current(activity)));
        requireScreenText("Handlebar PIN");
        main(() -> invoke(activity, "navigateBack"));
        main(() -> require("home".equals(field(activity, "selectedPage")), "Back from the Security shortcut did not return Home"));
        check("The Settings and Security shortcuts open their pages over Home, an expired return keeps them, and Back returns Home");
    }
    private static final String HUB_AWAY_AT = "hub_away_elapsed", HUB_AWAY_BOOT = "hub_away_boot";
    /** Settings › Units, by the rider's taps: Settings (from Home), then its Units row. */
    private void openUnits() {
        HubTestPages.reach(this, activity, HubPages.SETTINGS);
        View[] units = {null};
        main(() -> units[0] = HubTestPages.shownTag(activity, "preference-units-menu"));
        HubTestPages.tapView(this, activity, units[0], "the Units row"); waitForIdleSync();
        main(() -> require(HubPages.SETTINGS_UNITS.equals(HubTestPages.current(activity)), "The Units row did not open its page"));
    }
    private static boolean awayExpired(long awayAt, int awayBoot, long now, int boot) {
        try {
            Method method = Class.forName("com.jjricks.volt.HubNav").getDeclaredMethod("awayExpired", long.class, int.class, long.class, int.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(null, awayAt, awayBoot, now, boot);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot invoke HubNav.awayExpired", e); }
    }
    private void requireFreshEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"),
                "UI validation refuses physical phones");
        require(Build.VERSION.SDK_INT >= 31, "Use an Android 12 or newer emulator");
        for (String key : PREFS) require(getTargetContext().getSharedPreferences(key, Context.MODE_PRIVATE).getAll().isEmpty(),
                "Fresh emulator app data required; existing preferences refused");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0, "Fresh emulator app data required; existing private files refused");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                "Grant Nearby devices externally on the fresh emulator before this runner");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be off or unavailable; this runner never changes radio state");
    }
    private void assertNoScooterWork() {
        main(() -> {
            ScooterService s = activity.service(); require(s != null, "Scooter workspace disappeared");
            require(s.profiles.all().isEmpty() && s.profiles.active() == null, "Unexpected owner profile");
            require(!s.isRecording() && s.ble.state() == BleController.State.DISCONNECTED, "Unexpected recording or connection work");
            require(((Number)field(s.ble, "mutationSequence")).longValue() == 0 && s.ble.commandResults().isEmpty(), "Unexpected scooter write");
            try { require(s.rides.list().isEmpty(), "UI preview must not create real ride history"); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
    }
    private PhoneRideStore.Track syntheticTrack() throws Exception {
        fixtureDirectory = new File(getTargetContext().getCacheDir(), "ui-synthetic-" + SystemClock.elapsedRealtime());
        require(fixtureDirectory.mkdir(), "Could not create isolated UI fixture");
        PhoneRideStore store = new PhoneRideStore(fixtureDirectory);
        String id = "synthetic-ui-preview"; long start = 100000, wall = 1700000000000L;
        store.start(id, wall, start); StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            long mono = start + i * 1000, stamp = wall + i * 1000;
            if (i == 36) rows.append(PhoneRideStore.row("GAP", stamp, mono));
            double speed = 6 + Math.sin(i / 5.0), power = 900 + 600 * Math.sin(i / 12.0);
            rows.append(PhoneRideStore.row("PRESSURE", stamp, mono, 1000 - i * .03));
            rows.append(PhoneRideStore.row("TELEMETRY", stamp, mono, speed * 3.6, power, 95 - i * .03, mono, mono, mono));
            rows.append(PhoneRideStore.row("GPS", stamp, mono, 0.1 + i * .000045, 0.1 + Math.sin(i / 12.0) * .0001,
                    3, speed, .2, 120 + i * .25, 4));
        }
        rows.append(PhoneRideStore.row("MOTION", wall + 69000, start + 69000, 500, 2.8));
        store.appendBatch(id, rows.toString(), ""); store.stop(id, wall + 70000, start + 70000);
        return store.load(id);
    }
    /**
     * The way around fits on the page shown, as the tab bar's five targets had to: on Home, Home's ways in (Widgets Home's
     * dock items and its All item; Clean Home's subject rows, search and gear); on any other page, its back link. Each is
     * shown, uncut, at least 48 dp tall (and wide, where it has no caption), with its caption laid out whole.
     */
    private void assertNavigationFits() {
        main(() -> {
            List<View> targets = new ArrayList<>();
            if (HubPages.HOME.equals(HubTestPages.current(activity))) {
                Object dock = field(activity, "dock");
                View tree = dock != null ? (View) invoke(dock, "view") : root();
                for (View view : descendants(tree)) {
                    Object tag = view.getTag();
                    if (!(tag instanceof String) || !view.isShown()) continue;
                    String name = (String) tag;
                    if (dock != null ? name.startsWith("hub-dock-") && !name.equals("hub-dock-sheet")
                            : name.startsWith("home-subject-") || name.equals("home-settings") || name.equals("home-search")) targets.add(view);
                }
                require(targets.size() >= (dock != null ? 2 : 9), "Home shows only " + targets.size() + " ways in");
                if (dock != null) require(root().findViewWithTag("hub-dock-handle") != null && root().findViewWithTag("hub-dock-handle").isShown(), "Widgets Home's dock has no All item");
            } else {
                View back = root().findViewWithTag("hub-back");
                // Search's header steps aside for its field (part 12, #393): Cancel beside the field is then its way back.
                if (HubPages.SEARCH.equals(HubTestPages.current(activity)) && (back == null || !back.isShown())) back = root().findViewWithTag("hub-search-cancel");
                require(back != null && back.isShown(), "Missing navigation: page " + HubTestPages.current(activity) + " has no back link");
                targets.add(back);
            }
            int minimum = Math.round(48 * root().getResources().getDisplayMetrics().density);
            for (View item : targets) {
                String name = String.valueOf(item.getTag());
                requireFullyVisibleNow(item, "Navigation " + name);
                require(item.getHeight() >= minimum, "Navigation target under 48dp tall: " + name);
                TextView label = first(item, TextView.class);
                if (label != null) {
                    require(label.getLayout() != null, "Navigation caption did not lay out: " + name);
                    require(label.getLayout().getHeight() <= label.getHeight() + 2, "Navigation caption clipped vertically: " + name);
                } else require(item.getWidth() >= minimum, "Navigation target narrower than 48dp: " + name);
            }
        });
    }
    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>(); out.add(root);
        for (int i = 0; i < out.size(); i++) if (out.get(i) instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) out.get(i);
            for (int c = 0; c < group.getChildCount(); c++) out.add(group.getChildAt(c));
        }
        return out;
    }
    /** Search opens with its keyboard up; put it away so the page is measured and tapped as a rider sees it after typing. */
    private void hideKeyboard() {
        main(() -> {
            android.view.inputmethod.InputMethodManager input = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (input != null) input.hideSoftInputFromWindow(root().getWindowToken(), 0);
        });
        waitForIdleSync();
    }
    /** The shown Scooter page's rows: a ControlsScreen, alone or a part of the page (the index, Battery & charging). */
    private Object controls() { return HubTestPages.part(activity, "ControlsScreen"); }
    /** HubSearchTables' row id for the top speed card (ScooterMenuLayout.TOP_SPEED). */
    private static final String TOP_SPEED_ROW = "top_speed";
    /** The settings Search shows now (part 12's HubSearchScreen): each shown result tagged hub-search-setting-<id>, by id. Main thread. */
    private Set<String> searchFound() {
        Set<String> found = new HashSet<>();
        View results = root().findViewWithTag("hub-search-results");
        if (results == null || !results.isShown()) return found;
        for (View view : descendants(results)) {
            Object tag = view.getTag();
            if (tag instanceof String && ((String) tag).startsWith("hub-search-setting-") && view.isShown()) found.add(((String) tag).substring("hub-search-setting-".length()));
        }
        return found;
    }
    /** searchFound() limited to the scooter's controls (ScooterCommands' catalog). Main thread. */
    private Set<String> foundFeatures() {
        Set<String> keys = new HashSet<>();
        for (ScooterCommands.Feature feature : ScooterCommands.catalog()) keys.add(feature.key);
        Set<String> found = searchFound(); found.retainAll(keys);
        return found;
    }
    private boolean topSpeedShown() { return ((View)field(field(controls(), "topSpeed"), "card")).isShown(); }
    private Set<String> lightFeatures() { Set<String> keys = new HashSet<>(); for (String key : visibleFeatures()) if (isLight(key)) keys.add(key); return keys; }
    private boolean tagShown(String tag) { View view = root().findViewWithTag(tag); return view != null && view.isShown(); }
    private void clickTag(String tag) { View[] view = {null}; main(() -> view[0] = root().findViewWithTag(tag)); require(view[0] != null && view[0].isShown(), "Missing " + tag); click(view[0]); }
    private void requireVisibleControl(String key) { main(() -> require(visibleFeatures().contains(key), "Missing visible control " + key)); }
    private Set<String> allFeatures() { return features(false); }
    private Set<String> visibleFeatures() { return features(true); }
    private Set<String> features(boolean visible) {
        Set<String> keys = new HashSet<>(); Object screen = controls();
        for (Object row : (List<?>)field(screen, "rows")) {
            View card = (View)field(row, "card");
            if (!visible || card.isShown()) keys.add(((ScooterCommands.Feature)field(row, "feature")).key);
        }
        return keys;
    }
    private static boolean isLight(String key) { return key.startsWith("line_") || key.startsWith("star_") || key.equals("headlight") || key.equals("auto_sensor"); }
    private void clickDescription(String description) { View view = findDescription(root(), description); require(view != null, "Missing navigation " + description); click(view); }
    private void clickDescription(View tree, String description) { View view = findDescription(tree, description); require(view != null, "Missing control " + description); click(view); }
    private void clickText(View tree, String text) { TextView view = findText(tree, text); require(view != null, "Missing UI label " + text); click(view); }
    private void requireScreenText(String text) { main(() -> require(findText(root(), text) != null, "Expected screen label " + text)); }
    private void click(View target) {
        View actionable = target;
        while (!actionable.isClickable() && actionable.getParent() instanceof View) actionable = (View)actionable.getParent();
        final View button = actionable;
        require(button.isClickable() && button.isEnabled(), "View is not an enabled action: " + target.getContentDescription());
        reveal(button); main(() -> { require(button.performClick(), "Action did not dispatch"); }); waitForIdleSync();
    }
    private void reveal(View view) {
        main(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        waitForIdleSync();
    }
    private void requireFullyVisible(View view, String name) { main(() -> requireFullyVisibleNow(view, name)); }
    private void requireFullyVisibleNow(View view, String name) {
        Rect rect = new Rect(); require(view.getGlobalVisibleRect(rect), name + " is not visible");
        require(rect.width() >= view.getWidth() - 2 && rect.height() >= view.getHeight() - 2, name + " is clipped");
    }
    private View root() { return (View)field(activity, "root"); }
    private SharedPreferences settings() { return getTargetContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE); }
    private void screenshot(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(120);
        Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Emulator screenshot unavailable");
        String variant = arguments.getString("variant", "ui").replaceAll("[^A-Za-z0-9_-]", "");
        File directory = new File(getTargetContext().getExternalFilesDir(null), "ui-validation");
        require(directory.isDirectory() || directory.mkdirs(), "Could not create screenshot directory");
        File output = new File(directory, variant + "-" + name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "Screenshot encoding failed"); }
        finally { bitmap.recycle(); }
        screenshots.add(output.getAbsolutePath()); TextCensus.write(this, output);
    }
    private void cleanup() {
        if (!admitted) return;
        if (activity != null && !activity.isDestroyed()) {
            main(() -> { if (preview != null) preview.dismiss(); activity.finish(); });
            await(activity::isDestroyed, 4000, "UI activity did not finish");
        }
        main(() -> { ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        waitForIdleSync();
        for (String name : PREFS) getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        // Delete only our dedicated synthetic cache fixture. Real ride storage is never touched.
        if (fixtureDirectory != null) { File[] files = fixtureDirectory.listFiles(); if (files != null) for (File file : files) file.delete(); fixtureDirectory.delete(); }
        admitted = false;
    }
    private void await(BooleanSupplier condition, long timeout, String message) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            AtomicReference<Boolean> found = new AtomicReference<>(false); main(() -> found.set(condition.getAsBoolean()));
            if (found.get()) return; SystemClock.sleep(40);
        }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private static Object field(Object value, String name) {
        if (value == null) throw new IllegalStateException("Null object while reading " + name);
        try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect " + name, e); }
    }
    private static Object invoke(Object value, String name) { return invoke(value, name, new Class<?>[0]); }
    private static Object invoke(Object value, String name, Class<?>[] signature, Object... args) {
        try { Method method = value.getClass().getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(value, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot invoke " + name + ": " + e.getCause(), e); }
    }
    private static View findDescription(View root, String description) {
        if (root.isShown() && description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription()) && root.isClickable()) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = findDescription(((ViewGroup)root).getChildAt(i), description); if (found != null) return found; }
        return null;
    }
    private static TextView findText(View root, String text) {
        if (root.isShown() && root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { TextView found = findText(((ViewGroup)root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    /** A readout cell (label over value) whose description starts with the given label; not a legend. */
    private static View findReadout(View root, String prefix) {
        CharSequence description = root.getContentDescription();
        if (root.isShown() && root instanceof ViewGroup && description != null && description.toString().startsWith(prefix)
                && !root.getClass().getSimpleName().contains("Legend")) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = findReadout(((ViewGroup)root).getChildAt(i), prefix); if (found != null) return found; }
        return null;
    }
    private static TextView findTextContaining(View root, String text) {
        if (root.isShown() && root instanceof TextView && ((TextView)root).getText().toString().contains(text)) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { TextView found = findTextContaining(((ViewGroup)root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static TextView findTextIncludingHidden(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText())) return (TextView)root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) {
            TextView found = findTextIncludingHidden(((ViewGroup)root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static <T> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { T found = first(((ViewGroup)root).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
    private void check(String text) { checks.add(text); Bundle status = new Bundle(); status.putString("check", text); sendStatus(0, status); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
