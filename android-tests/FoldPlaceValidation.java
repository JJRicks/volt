package com.jjricks.volt;

import com.jjricks.volt.tests.HubTestPages;
import com.jjricks.volt.tests.ValidationInstrumentation;
import android.Manifest;
import android.app.Dialog;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Disposable empty emulator, radio off. Folding and unfolding keep the owner where they were (2.0.5; the owner on a
 * Pixel Fold: "There are some situations where folding and unfolding the phone will drop you back a menu layer or two":
 * a ride's map opened, then unfolded, landed on Rides; a charge recording's graph, then unfolded, landed a level back).
 *
 * <p>The size switches as the screenshot pipeline does: {@code wm size 2074x2153} (the Fold unfolded, 790 dp, the wide
 * layout) and back to the phone's 1080x2400, at density 420. Volt handles the size change itself (configChanges): the
 * activity stays and rebuilds its window. At each depth the checks look at what is on screen before and after a switch,
 * both ways, then press Back and check where it lands:
 * <ul>
 *   <li>Home on a phone, unfolded: Home (the wide Clean Home's page pane shows Home's subjects; no page opens), folded: Home.</li>
 *   <li>A ride's details page on All rides; its expanded route map (with its zoom); its expanded readings chart (with its
 *       span). On the wide Widgets layout All rides shows the ride in its detail pane instead of a details page.</li>
 *   <li>All rides on the wide Widgets layout with no ride picked, folded: the list, no ride's details nobody asked for;
 *       a ride picked there, folded: its details page.</li>
 *   <li>Charge history & recordings: a recording, its expanded chart (with its span), and the recording again.</li>
 *   <li>Battery & charging with its Live charging sheet open; a Settings sub-page, scrolled; the Hub search with a query;
 *       riding mode, and its tune menu.</li>
 *   <li>A page opened on the wide layout, folded: the same page, not Home; Back goes to its parent.</li>
 * </ul>
 * Arguments: home (clean or widgets), theme (dark or light), report (true: a missed check is listed and the run goes
 * on, for before pictures on a build without the fix; the result is still FAIL). Pictures name the case and the size.
 * Sends no scooter command (the developer simulator).
 */
public final class FoldPlaceValidation extends ValidationInstrumentation {
    private static final String PHONE_SIZE = "1080x2400", FOLD_SIZE = "2074x2153", DENSITY = "420";
    private static final double LAT = 45.5200, LON = -122.6800;
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String home = "clean", theme = "dark", label;
    private boolean report;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), misses = new ArrayList<>(), screenshots = new ArrayList<>();
    private String rideA, rideB, rideC, chargeA, immersivePrevious;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            home = "widgets".equals(args.getString("home")) ? "widgets" : "clean";
            theme = "light".equals(args.getString("theme")) ? "light" : "dark";
            report = "true".equals(args.getString("report"));
            label = home + "-" + theme;
            shell("wm density " + DENSITY); shell("wm size " + PHONE_SIZE); SystemClock.sleep(1500);
            // Riding mode is full screen: Android's one-time "Viewing full screen" notice would take Back from its menu.
            immersivePrevious = shell("settings get secure immersive_mode_confirmations").trim();
            shell("settings put secure immersive_mode_confirmations confirmed");
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile p = profiles.create("Synthetic UT5 MAX", "000000000001", 0); p.serial = "Q2345ZZ9ES7777777";
                profiles.update(p); profiles.select(p.id); OnboardingScreen.markDone(getTargetContext());
            });
            boolean clean = "clean".equals(home);
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", theme).putString("units", "mi").putFloat("text_scale", 1f)
                    .putString("home_style", home).putBoolean("home_style_chosen", true).putString("home_style_origin", "install")
                    .putBoolean("whats_new_shown", true).commit(), "Could not seed preferences");
            launch();
            simulate();
            rideA = seedRide("Fold check ride A", loop(), 3 * 3600_000L);
            rideB = seedRide("Fold check ride B", line(), 3600_000L);
            rideC = seedRide("Fold check ride C", null, 2 * 3600_000L);
            chargeA = seedCharges();
            // Argument only: one case by name (a comma list), for a quick look; the sweep runs them all.
            Set<String> only = new HashSet<>(Arrays.asList(args.getString("only", "").split(",")));
            only.remove("");
            String[] names = {"home", "ride", "map", "chart", "pane", "charge", "battery", "settings", "search", "riding", "wide-page",
                    "twice", "search-ride", "compare", "kept-view", "compare-front"};
            ThrowingRunnable[] cases = {this::homeStays, this::rideDetails, this::expandedMap, this::expandedChartCase, this::widePaneRide,
                    this::chargeRecording, this::batterySheet, this::settingsSubpage, this::search, this::ridingMode, this::widePageFolds,
                    this::twoRebuilds, this::searchRideFolds, this::comparisonUnder, this::keptViewYields, this::comparisonInFront};
            for (int i = 0; i < names.length; i++) if (only.isEmpty() || only.contains(names[i])) { if (unfolded()) fold(); cases[i].run(); }
            if (!misses.isEmpty()) throw new IllegalStateException(misses.size() + " check(s) missed: " + String.join(" | ", misses));
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", String.valueOf(failure));
            StringBuilder trace = new StringBuilder(); for (StackTraceElement e : failure.getStackTrace()) { if (trace.length() > 1500) break; trace.append(e).append(" | "); }
            result.putString("trace", trace.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("misses", misses);
            result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (activity != null) try { main(() -> { if (service != null && service.simulating()) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            try { shell("wm size reset"); shell("wm density reset"); } catch (Throwable ignored) {}
            if (immersivePrevious != null) try { shell(immersivePrevious.isEmpty() || "null".equals(immersivePrevious)
                    ? "settings delete secure immersive_mode_confirmations" : "settings put secure immersive_mode_confirmations " + immersivePrevious); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- The cases --------------------------------------------------------------------------------------------------

    /** Home on a phone, unfolded and folded: Home each time, with nothing opened over it. */
    private void homeStays() throws Exception {
        open(HubPages.HOME);
        expect(page().equals(HubPages.HOME) && extraWindows().isEmpty(), "phone: Home with nothing over it");
        unfold();
        expect(page().equals(HubPages.HOME), "unfolded on Home: Home (got " + page() + ")");
        expect(extraWindows().isEmpty(), "unfolded on Home: nothing opened over it");
        if ("clean".equals(home)) expect(onMain(() -> field(activity, "screen") instanceof HubHomePane), "unfolded Clean Home: the page pane shows Home's subjects, not a page");
        shot("home-fold");
        fold();
        expect(page().equals(HubPages.HOME) && extraWindows().isEmpty(), "folded again: Home with nothing over it");
        checks.add(label + " Home: unfolded and folded, Home each time, nothing opened");
    }

    /** A ride's details page, unfolded and folded; Back closes it onto All rides. */
    private void rideDetails() throws Exception {
        open(HubPages.RIDE_RIDES);
        openRide(rideA);
        await(() -> rideShown("Fold check ride A"), 10000, "The ride's details did not open");
        shot("ride-details-phone");
        unfold();
        expect(page().equals(HubPages.RIDE_RIDES), "unfolded: still All rides (got " + page() + ")");
        expect(waitFor(() -> rideShown("Fold check ride A"), 8000), "unfolded: the ride's details still show");
        shot("ride-details-fold");
        fold();
        expect(waitFor(() -> detailsWindow() != null && rideShown("Fold check ride A"), 8000), "folded: the ride's details page is open again");
        back();
        expect(waitFor(() -> detailsWindow() == null, 5000) && page().equals(HubPages.RIDE_RIDES), "Back from the details: All rides' list (page " + page() + ")");
        checks.add(label + " ride details: kept unfolded and folded; Back lands on All rides");
    }

    /** The owner's first report: a ride's map expanded, then unfolded. Its zoom comes along. */
    private void expandedMap() throws Exception {
        open(HubPages.RIDE_RIDES);
        openRide(rideA);
        await(() -> inlineMap() != null, 15000, "The ride's map did not load in its details");
        main(() -> { View map = inlineMap(); map.requestRectangleOnScreen(new android.graphics.Rect(0, 0, map.getWidth(), map.getHeight()), true); });
        settle();
        View expand = onMain(() -> byDescription(detailsWindow(), T.ridesMapExpand()));
        require(expand != null, "No Expand map button");
        main(() -> require(expand.performClick(), "Expand map took no tap"));
        await(() -> expandedMapView() != null && expandedMapView().getWidth() > 0, 8000, "The expanded map did not open");
        main(() -> ((RouteMapView) expandedMapView()).zoomBy(1));
        settle();
        double zoom = onMain(() -> (Double) field(expandedMapView(), "zoom"));
        shot("map-phone");
        unfold();
        expect(page().equals(HubPages.RIDE_RIDES), "unfolded with the map expanded: still All rides (got " + page() + ")");
        expect(waitFor(() -> expandedMapView() != null, 10000), "unfolded: the expanded map is still open");
        if (onMain(() -> expandedMapView() != null)) {
            double after = onMain(() -> (Double) field(expandedMapView(), "zoom"));
            expect(Math.abs(after - zoom) < 0.01, "unfolded: the expanded map kept its zoom (" + zoom + " → " + after + ")");
        }
        shot("map-fold");
        fold();
        expect(waitFor(() -> expandedMapView() != null, 10000), "folded again: the expanded map is still open");
        shot("map-phone-again");
        back();
        expect(waitFor(() -> expandedMapView() == null, 5000), "Back closes the expanded map");
        expect(waitFor(() -> rideShown("Fold check ride A"), 5000), "… onto the ride's details");
        back();
        expect(waitFor(() -> detailsWindow() == null, 5000) && page().equals(HubPages.RIDE_RIDES), "Back again: All rides' list");
        checks.add(label + " expanded map: kept (zoom " + zoom + ") unfolded and folded; Back: details, then All rides");
    }

    /** The ride's readings chart expanded and zoomed, unfolded and folded: still open, with its span. */
    private void expandedChartCase() throws Exception {
        open(HubPages.RIDE_RIDES);
        openRide(rideA);
        await(() -> rideShown("Fold check ride A") && byText(detailsWindow(), T.ridesExpand()) != null, 10000, "The details' readings chart has no Expand");
        main(() -> require(byText(detailsWindow(), T.ridesExpand()).performClick(), "Expand chart took no tap"));
        await(() -> expandedChart() != null, 8000, "The expanded chart did not open");
        SystemClock.sleep(1500); settle(); // the full journal loads
        main(() -> ((RideChartView) field(expandedChart(), "chart")).zoomBy(4));
        settle();
        double span = onMain(() -> span((RideChartView) field(expandedChart(), "chart")));
        shot("chart-phone");
        unfold();
        expect(page().equals(HubPages.RIDE_RIDES), "unfolded with the chart expanded: still All rides");
        expect(waitFor(() -> expandedChart() != null, 10000), "unfolded: the expanded chart is still open");
        if (onMain(() -> expandedChart() != null)) {
            SystemClock.sleep(1500); settle();
            double after = onMain(() -> span((RideChartView) field(expandedChart(), "chart")));
            expect(Math.abs(after - span) <= span * 0.02 + 1, "unfolded: the expanded chart kept its zoom (" + span + " ms → " + after + " ms)");
        }
        shot("chart-fold");
        fold();
        expect(waitFor(() -> expandedChart() != null, 10000), "folded again: the expanded chart is still open");
        back();
        expect(waitFor(() -> expandedChart() == null, 5000) && waitFor(() -> rideShown("Fold check ride A"), 5000), "Back closes the chart onto the ride's details");
        back();
        expect(waitFor(() -> detailsWindow() == null, 5000), "Back again: All rides' list");
        checks.add(label + " expanded chart: kept with its span unfolded and folded; Back: details, then All rides");
    }

    /** Wide All rides on its own, folded: the list. A ride picked there, folded: its details. */
    private void widePaneRide() throws Exception {
        unfold();
        open(HubPages.RIDE_RIDES);
        settle(); SystemClock.sleep(800);
        expect(extraWindows().isEmpty(), "wide All rides opened: nothing over it");
        fold();
        expect(page().equals(HubPages.RIDE_RIDES), "folded from wide All rides: All rides");
        expect(detailsWindow() == null, "folded from wide All rides with no ride picked: no ride's details opened by themselves");
        unfold();
        openRide(rideB);
        await(() -> rideShown("Fold check ride B"), 10000, "Ride B did not show on the wide layout");
        shot("wide-picked-fold");
        fold();
        expect(waitFor(() -> detailsWindow() != null && rideShown("Fold check ride B"), 8000), "a ride picked on the wide layout, folded: its details page");
        shot("wide-picked-phone");
        back();
        expect(waitFor(() -> detailsWindow() == null, 5000) && page().equals(HubPages.RIDE_RIDES), "Back: All rides' list");
        checks.add(label + " wide All rides: unpicked folds to the list; picked folds to its details");
    }

    /** The owner's second report: a charge recording's chart expanded, then unfolded. */
    private void chargeRecording() throws Exception {
        open(HubPages.BATTERY_HISTORY);
        openRecording();
        shot("charge-detail-phone");
        unfold();
        expect(page().equals(HubPages.BATTERY_HISTORY), "unfolded: still Charge history (got " + page() + ")");
        expect(waitFor(() -> byTag("charge-history-detail") != null, 8000), "unfolded: the recording is still open");
        shot("charge-detail-fold");
        fold();
        expect(waitFor(() -> byTag("charge-history-detail") != null, 8000), "folded: the recording is still open");
        if (onMain(() -> byTag("charge-history-detail") == null)) openRecording(); // report mode: carry on from the recording
        main(() -> require(byTag("charge-history-expand").performClick(), "Expand took no tap"));
        await(() -> byTag("charge-history-fullscreen") != null, 8000, "The expanded charge chart did not open");
        main(() -> require(byTag("charge-history-fullscreen-zoom-in").performClick(), "Zoom in took no tap"));
        settle();
        double span = onMain(() -> span((RideChartView) byTag("charge-history-fullscreen-chart")));
        shot("charge-chart-phone");
        unfold();
        expect(page().equals(HubPages.BATTERY_HISTORY), "unfolded with the chart expanded: still Charge history");
        expect(waitFor(() -> byTag("charge-history-fullscreen") != null, 8000), "unfolded: the expanded charge chart is still open");
        if (onMain(() -> byTag("charge-history-fullscreen-chart") != null)) {
            double after = onMain(() -> span((RideChartView) byTag("charge-history-fullscreen-chart")));
            expect(Math.abs(after - span) <= span * 0.02 + 1, "unfolded: the expanded charge chart kept its zoom (" + span + " → " + after + ")");
        }
        shot("charge-chart-fold");
        fold();
        expect(waitFor(() -> byTag("charge-history-fullscreen") != null, 8000), "folded again: the expanded charge chart is still open");
        back();
        expect(waitFor(() -> byTag("charge-history-fullscreen") == null && byTag("charge-history-detail") != null, 5000), "Back closes the expanded chart onto the recording");
        main(() -> { View close = byDescription(windowWith("charge-history-detail"), T.statsChargeHistoryClose()); if (close != null) close.performClick(); });
        expect(waitFor(() -> byTag("charge-history-detail") == null, 5000) && page().equals(HubPages.BATTERY_HISTORY), "Closing the recordings: Charge history's page");
        checks.add(label + " charge recording: kept unfolded and folded; its expanded chart kept with its span; Back steps out");
    }
    /** Charge history & recordings, then the newer synthetic recording. */
    private void openRecording() {
        closeWindows();
        main(() -> { View row = root().findViewWithTag("charge-history-action"); require(row != null && row.isEnabled(), "No Charge recordings row"); row.performClick(); });
        await(() -> byTag("charge-history-session:" + chargeA) != null, 10000, "The recordings list did not load");
        main(() -> require(byTag("charge-history-session:" + chargeA).performClick(), "The recording took no tap"));
        await(() -> byTag("charge-history-detail") != null, 10000, "The recording did not open");
    }

    /** Battery & charging with its Live charging sheet open, unfolded: the page and the sheet. */
    private void batterySheet() throws Exception {
        open(HubPages.BATTERY);
        main(() -> invoke(activity, "showChargeLive", new Class<?>[]{}));
        await(() -> liveSheetShowing(), 5000, "The Live charging sheet did not open");
        shot("battery-sheet-phone");
        unfold();
        expect(page().equals(HubPages.BATTERY), "unfolded: still Battery & charging (got " + page() + ")");
        expect(waitFor(this::liveSheetShowing, 5000), "unfolded: the Live charging sheet is open again");
        shot("battery-sheet-fold");
        fold();
        expect(waitFor(this::liveSheetShowing, 5000), "folded: the Live charging sheet is open again");
        back();
        expect(waitFor(() -> !liveSheetShowing(), 5000) && page().equals(HubPages.BATTERY), "Back closes the sheet onto Battery & charging");
        checks.add(label + " Battery & charging: the page and its Live charging sheet kept both ways");
    }

    /** A Settings sub-page scrolled down, unfolded and folded: the same page; Back: Settings. */
    private void settingsSubpage() throws Exception {
        open(HubPages.SETTINGS);
        // Settings' sub-pages are its section rows (no Hub row tags): opened as their rows open them.
        main(() -> activity.open(HubPages.SETTINGS_HOME));
        settle(); SystemClock.sleep(400); settle();
        require(page().equals(HubPages.SETTINGS_HOME), "Could not open Home style (on " + page() + ")");
        main(() -> { ScrollView scroll = (ScrollView) field(activity, "scroll"); scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); });
        settle();
        unfold();
        expect(page().equals(HubPages.SETTINGS_HOME), "unfolded: still Home style (got " + page() + ")");
        shot("settings-fold");
        fold();
        expect(page().equals(HubPages.SETTINGS_HOME), "folded: still Home style (got " + page() + ")");
        boolean tall = onMain(() -> { ScrollView scroll = (ScrollView) field(activity, "scroll"); return scroll.getChildAt(0).getHeight() > scroll.getHeight() + 100; });
        int y = onMain(() -> ((ScrollView) field(activity, "scroll")).getScrollY());
        expect(!tall || y > 0, "folded: Home style keeps a scroll (" + y + " px)");
        back();
        expect(waitFor(() -> page().equals(HubPages.SETTINGS), 5000), "Back from Home style: Settings (got " + page() + ")");
        checks.add(label + " Settings › Home style: kept" + (tall ? " with its scroll (" + y + " px)" : "") + "; Back to Settings");
    }

    /** Search with a query, unfolded and folded: the same query and its results. */
    private void search() throws Exception {
        open(HubPages.SEARCH);
        main(() -> { EditText field = searchField(); require(field != null, "No search field"); field.setText("charge"); });
        settle();
        unfold();
        expect(page().equals(HubPages.SEARCH), "unfolded: still Search (got " + page() + ")");
        expect(onMain(() -> searchField() != null && "charge".contentEquals(searchField().getText())), "unfolded: the query is still there");
        shot("search-fold");
        fold();
        expect(onMain(() -> searchField() != null && "charge".contentEquals(searchField().getText())), "folded: the query is still there");
        main(() -> searchField().setText(""));
        checks.add(label + " search: the page and its query kept both ways");
    }

    /** Riding mode, and its tune menu, unfolded and folded. */
    private void ridingMode() throws Exception {
        open(HubPages.HOME);
        main(() -> activity.openRiding());
        await(() -> field(activity, "screen") instanceof RidingScreen, 8000, "Riding mode did not open");
        settle(); SystemClock.sleep(600);
        unfold();
        expect(onMain(() -> field(activity, "screen") instanceof RidingScreen), "unfolded: riding mode");
        fold();
        expect(onMain(() -> field(activity, "screen") instanceof RidingScreen), "folded: riding mode");
        main(() -> invoke(field(activity, "screen"), "edit", new Class<?>[]{}));
        settle();
        boolean menu = onMain(this::tuneMenu);
        if (!menu) checks.add(label + " riding mode: the tune menu didn't open here (not stopped?); its fold check is skipped");
        else {
            shot("riding-menu-phone");
            unfold();
            expect(waitFor(this::tuneMenu, 5000), "unfolded: riding mode's tune menu is open again");
            shot("riding-menu-fold");
            fold();
            expect(waitFor(this::tuneMenu, 5000), "folded: riding mode's tune menu is open again");
            back();
            expect(waitFor(() -> !tuneMenu(), 5000), "Back closes the tune menu");
        }
        main(() -> invoke(activity, "riderClosedRiding", new Class<?>[]{}));
        await(() -> !(field(activity, "screen") instanceof RidingScreen), 8000, "Riding mode did not close");
        checks.add(label + " riding mode: kept unfolded and folded" + (menu ? ", with its tune menu" : ""));
    }

    /** A page opened on the wide layout, folded: the same page, not Home; Back: its parent. */
    private void widePageFolds() throws Exception {
        unfold();
        open(HubPages.LIGHTS);
        expect(page().equals(HubPages.LIGHTS), "wide: Lights & sound open");
        fold();
        expect(page().equals(HubPages.LIGHTS), "folded from a wide page: the page, not Home (got " + page() + ")");
        back();
        expect(waitFor(() -> page().equals(HubPages.HOME), 5000), "Back: Home (got " + page() + ")");
        checks.add(label + " a wide page folds to the same page; Back to Home");
    }

    /**
     * Two rebuilds in one main-thread turn (an unfold's size change, then its posture: onConfigurationChanged and the
     * fold monitor each rebuild): the ride's details still come back (council round 1, F03).
     */
    private void twoRebuilds() throws Exception {
        open(HubPages.RIDE_RIDES);
        openRide(rideA);
        await(() -> rideShown("Fold check ride A"), 10000, "The ride's details did not open");
        main(() -> { activity.rebuild(); activity.rebuild(); });
        settle(); SystemClock.sleep(800); settle();
        expect(waitFor(() -> detailsWindow() != null && rideShown("Fold check ride A"), 8000), "two rebuilds in one turn: the ride's details are open again");
        unfold();
        main(() -> { activity.rebuild(); activity.rebuild(); });
        settle(); SystemClock.sleep(800); settle();
        expect(waitFor(() -> rideShown("Fold check ride A"), 8000), "unfolded, then two rebuilds in one turn: the ride still shows");
        fold();
        expect(waitFor(() -> detailsWindow() != null && rideShown("Fold check ride A"), 8000), "folded again: its details page");
        back();
        checks.add(label + " two rebuilds in one turn keep the ride's details, folded and unfolded");
    }

    /**
     * A ride opened as a search result does (back past All rides), on the wide layout, then folded: its details page's
     * Back goes to the page that opened it, not All rides (council round 1, F08).
     */
    private void searchRideFolds() throws Exception {
        unfold();
        open(HubPages.HOME);
        main(() -> activity.open(HubPages.RIDE_RIDES, HubNav.Args.rideAndBack(rideB)));
        settle(); SystemClock.sleep(800); settle();
        await(() -> rideShown("Fold check ride B"), 10000, "The search result's ride did not show on the wide layout");
        fold();
        expect(waitFor(() -> detailsWindow() != null && rideShown("Fold check ride B"), 8000), "folded: the search result's ride in its details page");
        back();
        expect(waitFor(() -> detailsWindow() == null && page().equals(HubPages.HOME), 5000), "Back from it goes back past All rides, to Home (got " + page() + ")");
        checks.add(label + " a search result's ride opened wide, folded: Back goes past All rides to the page that opened it");
    }

    /** A ride opened from Compare two rides, then unfolded and folded: the comparison is still under it (council round 1, F07). */
    private void comparisonUnder() throws Exception {
        require(settings().edit().putStringSet("ride_comparison", new LinkedHashSet<>(Arrays.asList(rideA, rideB))).commit(), "Could not choose two rides");
        open(HubPages.RIDE_RIDES);
        main(() -> invoke(field(activity, "screen"), "showComparison", new Class<?>[]{}));
        await(() -> byText(comparisonWindow(), T.ridesViewFirst()) != null, 8000, "Compare two rides did not open");
        main(() -> require(byText(comparisonWindow(), T.ridesViewFirst()).performClick(), "View first took no tap"));
        await(() -> detailsWindow() != null, 8000, "The first ride's details did not open from the comparison");
        unfold();
        expect(waitFor(() -> comparisonWindow() != null, 8000), "unfolded: the comparison is still open");
        // Wide too: the ride's details page in front of the comparison, as View first opens it, not the ride in a pane behind it.
        expect(waitFor(() -> detailsWindow() != null && inFront(detailsWindow(), comparisonWindow()), 8000), "unfolded: the ride's details in front of the comparison (round 2)");
        fold();
        expect(waitFor(() -> detailsWindow() != null && comparisonWindow() != null, 8000), "folded: the ride's details over the comparison");
        expect(onMain(() -> inFront(detailsWindow(), comparisonWindow())), "folded: the ride's details in front of the comparison (round 2)");
        back();
        expect(waitFor(() -> detailsWindow() == null && comparisonWindow() != null, 5000), "Back from the ride: the comparison under it");
        back();
        expect(waitFor(() -> comparisonWindow() == null && page().equals(HubPages.RIDE_RIDES), 5000), "Back again: All rides");
        checks.add(label + " a ride opened from Compare two rides keeps the comparison under it, both ways");
    }
    /**
     * A ride without phone files (no phone readings will come): its chart zoomed, folded; then zoomed further, its chart
     * expanded and closed: the newer zoom stays, and the next fold brings back the newer zoom, not the first (round 2).
     */
    private void keptViewYields() throws Exception {
        open(HubPages.RIDE_RIDES);
        openRide(rideC);
        await(() -> rideShown("Fold check ride C") && inlineChart() != null, 10000, "Ride C's details did not open");
        main(() -> require(byDescription(detailsWindow(), T.ridesChartZoomIn()).performClick(), "Zoom in took no tap"));
        settle();
        double first = onMain(() -> span(inlineChart()));
        unfold();
        await(() -> rideShown("Fold check ride C") && inlineChart() != null, 8000, "Ride C did not show unfolded");
        fold();
        await(() -> detailsWindow() != null && inlineChart() != null, 8000, "Ride C's details did not come back folded");
        settle();
        double back = onMain(() -> span(inlineChart()));
        expect(Math.abs(back - first) <= first * 0.02 + 1, "folded: the chart kept its zoom (" + first + " → " + back + ")");
        main(() -> require(byDescription(detailsWindow(), T.ridesChartZoomIn()).performClick(), "Zoom in took no tap"));
        settle();
        double newer = onMain(() -> span(inlineChart()));
        require(newer < first * 0.75, "The second zoom did not zoom in (" + first + " → " + newer + ")");
        main(() -> require(byText(detailsWindow(), T.ridesExpand()).performClick(), "Expand chart took no tap"));
        await(() -> expandedChart() != null, 8000, "The expanded chart did not open");
        SystemClock.sleep(1000); settle();
        back();
        await(() -> expandedChart() == null, 5000, "Back did not close the expanded chart");
        settle();
        double closed = onMain(() -> span(inlineChart()));
        expect(Math.abs(closed - newer) <= newer * 0.02 + 1, "closing the expanded chart keeps the rider's newer zoom (" + newer + " → " + closed + ")");
        unfold();
        await(() -> rideShown("Fold check ride C") && inlineChart() != null, 8000, "Ride C did not show unfolded again");
        settle();
        double later = onMain(() -> span(inlineChart()));
        expect(Math.abs(later - newer) <= newer * 0.02 + 1, "the next unfold brings back the newer zoom, not the first (" + newer + " → " + later + ")");
        fold();
        back();
        checks.add(label + " a ride without phone files: a fold's kept zoom gives way to the rider's own, through the expanded chart and the next fold");
    }
    /** The ride's own readings chart (not the expanded one), wherever it shows. */
    private RideChartView inlineChart() {
        for (View window : allWindows()) {
            if (!window.isShown()) continue;
            for (View v : find(window, x -> x.getClass().getName().endsWith("RidesScreen$ChartPanel") && x.isShown()))
                if (!Boolean.TRUE.equals(field(v, "full"))) return (RideChartView) field(v, "chart");
        }
        return null;
    }

    /**
     * Wide Widgets All rides: a ride picked in the pane, then Compare two rides over it, scrolled; folded: the ride's
     * details with the comparison in front, at its scroll (round 2). Clean's wide All rides has no pane.
     */
    private void comparisonInFront() throws Exception {
        if (!"widgets".equals(home)) { checks.add(label + " comparison over a pane ride: no panes on this Home style"); return; }
        require(settings().edit().putStringSet("ride_comparison", new LinkedHashSet<>(Arrays.asList(rideA, rideB))).commit(), "Could not choose two rides");
        unfold();
        open(HubPages.RIDE_RIDES);
        openRide(rideA);
        await(() -> rideShown("Fold check ride A"), 8000, "Ride A did not show in the pane");
        main(() -> invoke(field(activity, "screen"), "showComparison", new Class<?>[]{}));
        await(() -> comparisonWindow() != null, 8000, "Compare two rides did not open");
        SystemClock.sleep(800); settle();
        main(() -> { List<View> scrolls = find(comparisonWindow(), x -> x instanceof ScrollView); ((ScrollView) scrolls.get(0)).scrollTo(0, 240); });
        settle();
        int scrolled = onMain(() -> ((ScrollView) find(comparisonWindow(), x -> x instanceof ScrollView).get(0)).getScrollY());
        fold();
        expect(waitFor(() -> detailsWindow() != null && comparisonWindow() != null, 8000), "folded: the picked ride's details and the comparison");
        expect(onMain(() -> inFront(comparisonWindow(), detailsWindow())), "folded: the comparison in front of the ride, as it was");
        SystemClock.sleep(600); settle();
        int after = onMain(() -> ((ScrollView) find(comparisonWindow(), x -> x instanceof ScrollView).get(0)).getScrollY());
        expect(scrolled == 0 || after > 0, "folded: the comparison kept its scroll (" + scrolled + " → " + after + " px)");
        back();
        expect(waitFor(() -> comparisonWindow() == null && detailsWindow() != null, 5000), "Back closes the comparison onto the ride's details");
        back();
        expect(waitFor(() -> detailsWindow() == null, 5000), "Back again: All rides");
        checks.add(label + " a comparison over a picked pane ride stays in front, at its scroll, after a fold");
    }
    /** Whether front's window is above behind's (later in the window list). */
    private boolean inFront(View front, View behind) {
        if (front == null || behind == null) return false;
        List<View> windows = allWindows();
        return windows.indexOf(front) > windows.indexOf(behind);
    }

    /** Compare two rides' window: one holding its View first button. */
    private View comparisonWindow() {
        View main = activity.getWindow().getDecorView();
        for (View window : allWindows()) if (window != main && window.isShown() && byText(window, T.ridesViewFirst()) != null) return window;
        return null;
    }

    // ---- What is on screen ------------------------------------------------------------------------------------------

    private String page() { return onMain(() -> (String) field(activity, "selectedPage")); }
    private View root() { return (View) field(activity, "root"); }
    /** Volt's windows other than the activity's own (dialogs and sheets), shown. */
    private List<View> extraWindows() {
        return onMain(() -> {
            List<View> found = new ArrayList<>();
            View main = activity.getWindow().getDecorView();
            for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews())
                if (window != main && window.isShown() && window.getContext() != null) found.add(window);
            return found;
        });
    }
    private List<View> allWindows() { return android.view.inspector.WindowInspector.getGlobalWindowViews(); }
    /** The ride's title shown anywhere: its details page, or the wide pane. */
    private boolean rideShown(String name) {
        for (View window : allWindows()) for (View v : find(window, x -> "ride-title".equals(x.getTag()) && x.isShown()))
            if (v instanceof TextView && name.contentEquals(((TextView) v).getText())) return true;
        return false;
    }
    /** A ride's details page: a window of its own holding a ride title. */
    private View detailsWindow() {
        View main = activity.getWindow().getDecorView();
        for (View window : allWindows()) if (window != main && window.isShown() && !find(window, x -> "ride-title".equals(x.getTag())).isEmpty()) return window;
        return null;
    }
    /** The inline map in the details page (or the pane). */
    private View inlineMap() {
        View in = detailsWindow() != null ? detailsWindow() : activity.getWindow().getDecorView();
        List<View> maps = find(in, x -> x instanceof RouteMapView && x.isShown() && x.getHeight() > 0);
        return maps.isEmpty() ? null : maps.get(0);
    }
    /** The expanded map: a window with a route map and no ride title. */
    private View expandedMapView() {
        View main = activity.getWindow().getDecorView();
        for (View window : allWindows()) {
            if (window == main || !window.isShown() || !find(window, x -> "ride-title".equals(x.getTag())).isEmpty()) continue;
            List<View> maps = find(window, x -> x instanceof RouteMapView);
            if (!maps.isEmpty()) return maps.get(0);
        }
        return null;
    }
    /** The expanded readings chart (RidesScreen's ChartPanel with full set) in a window of its own. */
    private View expandedChart() {
        View main = activity.getWindow().getDecorView();
        for (View window : allWindows()) {
            if (window == main || !window.isShown()) continue;
            for (View v : find(window, x -> x.getClass().getName().endsWith("RidesScreen$ChartPanel")))
                if (Boolean.TRUE.equals(field(v, "full"))) return v;
        }
        return null;
    }
    private boolean liveSheetShowing() {
        Object sheet = field(activity, "chargeLiveSheet");
        return sheet != null && ((Dialog) field(sheet, "dialog")).isShowing();
    }
    private boolean tuneMenu() {
        Object screen = field(activity, "screen");
        if (!(screen instanceof RidingScreen)) return false;
        Object sheet = field(screen, "sheet");
        return sheet != null && ((Dialog) sheet).isShowing();
    }
    private EditText searchField() {
        List<View> fields = find(root(), x -> x instanceof EditText && x.isShown());
        return fields.isEmpty() ? null : (EditText) fields.get(0);
    }
    private View byTag(String tag) {
        for (View window : allWindows()) { if (!window.isShown()) continue; View v = window.findViewWithTag(tag); if (v != null && v.isShown()) return v; }
        return null;
    }
    private View windowWith(String tag) {
        for (View window : allWindows()) if (window.isShown() && window.findViewWithTag(tag) != null) return window;
        return null;
    }
    private static View byDescription(View in, String description) {
        if (in == null) return null;
        List<View> found = find(in, x -> x.isShown() && x.getContentDescription() != null && description.contentEquals(x.getContentDescription()));
        return found.isEmpty() ? null : found.get(0);
    }
    private static View byText(View in, String text) {
        if (in == null) return null;
        List<View> found = find(in, x -> x instanceof TextView && x.isShown() && text.contentEquals(((TextView) x).getText()));
        return found.isEmpty() ? null : found.get(0);
    }
    private static List<View> find(View in, java.util.function.Predicate<View> test) {
        List<View> found = new ArrayList<>(); ArrayDeque<View> queue = new ArrayDeque<>(); if (in != null) queue.add(in);
        while (!queue.isEmpty()) {
            View v = queue.poll(); if (test.test(v)) found.add(v);
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) queue.add(((ViewGroup) v).getChildAt(i));
        }
        return found;
    }
    private static double span(RideChartView chart) {
        RideChartView.State state = chart.getState(); return state.to - state.from;
    }

    // ---- Driving ----------------------------------------------------------------------------------------------------

    /** A page as the owner reaches it: the routing by touch the Hub validations use, from Home. */
    private void open(String page) throws Exception {
        closeWindows();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, HubPages.HOME));
        settle();
        if (!HubPages.HOME.equals(page)) HubTestPages.reach(this, activity, page);
        settle(); SystemClock.sleep(400); settle();
        require(page().equals(page), "Could not reach " + page + " (on " + page() + ")");
    }
    /** Closes whatever a failed check left open, so the next case starts on its page. */
    private void closeWindows() {
        for (int i = 0; i < 4 && !extraWindows().isEmpty(); i++) { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(); SystemClock.sleep(300); }
    }
    private void openRide(String id) {
        main(() -> { Object screen = field(activity, "screen"); require(screen instanceof RidesScreen, "All rides is not showing"); ((RidesScreen) screen).openRide(id); });
        settle(); SystemClock.sleep(500); settle();
    }
    private void back() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(); SystemClock.sleep(300); settle(); }
    private boolean unfolded() { return onMain(() -> activity.getResources().getConfiguration().screenWidthDp >= 650); }
    private void unfold() throws Exception { resize(FOLD_SIZE, true); }
    private void fold() throws Exception { resize(PHONE_SIZE, false); }
    private void resize(String size, boolean wide) throws Exception {
        shell("wm size " + size);
        await(() -> activity.getResources().getConfiguration().screenWidthDp >= 650 == wide && !(Boolean) field(activity, "rebuilding"), 10000,
                "The window did not change to " + size);
        settle(); SystemClock.sleep(1200); settle();
    }

    // ---- Fixtures ---------------------------------------------------------------------------------------------------

    private static double[][] loop() {
        double[][] fixes = new double[121][];
        for (int i = 0; i <= 120; i++) {
            double t = i / 120.0 * 2 * Math.PI;
            fixes[i] = new double[]{LAT + 0.010 * Math.sin(t) + 0.003 * Math.sin(3 * t), LON + 0.020 * (1 - Math.cos(t)) - 0.004 * Math.sin(2 * t)};
        }
        return fixes;
    }
    private static double[][] line() {
        double[][] fixes = new double[81][];
        for (int i = 0; i <= 80; i++) {
            double t = i / 80.0;
            fixes[i] = new double[]{LAT + 0.006 - 0.012 * t + 0.0015 * Math.sin(t * Math.PI), LON - 0.030 + 0.060 * t};
        }
        return fixes;
    }
    /** One finished, synthesized ride ended endedAgo ms ago, 20 min long, with these phone GPS fixes. Nothing reaches the scooter. */
    private String seedRide(String name, double[][] fixes, long endedAgo) {
        return onMain(() -> {
            long end = System.currentTimeMillis() - endedAgo, start = end - 20 * 60_000L;
            long mono = Math.max(1_000L, SystemClock.elapsedRealtime() - endedAgo - 20 * 60_000L), monoEnd = mono + 20 * 60_000L;
            int steps = 40; long step = (monoEnd - mono) / steps;
            String profile = service.profiles.active().id;
            RideStore rides = service.rides;
            String id = rides.start(profile, name, start, mono);
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
            // No fixes: a ride recorded without the phone's sensors (no phone files at all).
            if (fixes == null) { require(!service.isRecording(), "The synthetic ride is still recording"); return id; }
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
            return id;
        });
    }
    /** Two finished, synthesized charge recordings; returns the newer's id. */
    private String seedCharges() throws Exception {
        ChargeSessionStore store = (ChargeSessionStore) field(service, "chargeStore");
        String profile = service.profiles.active().id;
        long wall = System.currentTimeMillis() - 6 * 3600_000L, mono = 200_000L;
        for (int session = 0; session < 2; session++) {
            long w = wall + session * 3 * 3600_000L, m = mono + session * 3 * 3600_000L;
            store.start(profile, ChargeSessionStore.Charger.STOCK, w, m);
            for (int i = 0; i <= 60; i++) {
                double soc = Math.min(100, 40 + i);
                ChargeSessionStore.Frame frame = new ChargeSessionStore.Frame(w + i * 60_000L, m + i * 60_000L, new ChargeSessionStore.Value(1d, m + i * 60_000L, 0x72),
                        new ChargeSessionStore.Value(soc, m + i * 60_000L, 0x72), new ChargeSessionStore.Value(50d + i * 0.05, m + i * 60_000L, 0x72),
                        new ChargeSessionStore.Value(-2d, m + i * 60_000L, 0x72), new ChargeSessionStore.Value(23d, m + i * 60_000L, 0x72));
                require(store.sample(frame), "A synthetic charge sample was refused");
            }
            store.stop(ChargeSessionStore.Stop.STOPPED);
        }
        List<ChargeSessionStore.HistorySession> listed = store.recordings(profile);
        require(listed.size() == 2, "Synthetic charge recordings missing: " + listed.size());
        return listed.get(0).id;
    }
    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().getWidth() > 0, 10000, "App did not launch");
        service = activity.service();
        SystemClock.sleep(1200); settle();
    }
    private void simulate() {
        main(() -> require(service.setSimulation(true), "Simulation did not start"));
        await(() -> service.ble.ready(), 10000, "The simulated scooter did not connect");
        main(() -> { service.ble.tickSimulation(); activity.redraw(); });
        settle();
    }

    // ---- Plumbing ---------------------------------------------------------------------------------------------------

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
    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor out = getUiAutomation().executeShellCommand(command);
        StringBuilder text = new StringBuilder();
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(out)) {
            byte[] buffer = new byte[4096]; int n; while ((n = in.read(buffer)) > 0) text.append(new String(buffer, 0, n, "UTF-8"));
        }
        return text.toString();
    }
    private File dir() { File d = new File(getTargetContext().getExternalFilesDir(null), "fold-place-validation"); d.mkdirs(); return d; }
    private void shot(String name) throws Exception { settle(); SystemClock.sleep(600); waitForIdleSync(); screenshot(name); }
    private void screenshot(String name) throws Exception {
        File file = new File(dir(), label + "-" + name + ".png"); Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); com.jjricks.volt.tests.TextCensus.write(this, file);
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    private void await(BooleanSupplier condition, long ms, String message) { if (!waitFor(condition, ms)) throw new IllegalStateException(message); }
    private boolean waitFor(BooleanSupplier condition, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> ready.set(condition.getAsBoolean()));
            if (Boolean.TRUE.equals(ready.get())) return true;
            SystemClock.sleep(80);
        }
        return false;
    }
    private void main(ThrowingRunnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private <V> V onMain(Callable<V> action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { return action.call(); } catch (Exception error) { throw new IllegalStateException(error.getMessage(), error); }
        }
        AtomicReference<V> value = new AtomicReference<>(); main(() -> value.set(action.call())); return value.get();
    }
    private interface ThrowingRunnable { void run() throws Exception; }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    /** A check of the place kept: a miss fails the run; in report mode it is listed and the run goes on (before pictures). */
    private void expect(boolean ok, String message) {
        assertions++;
        if (ok) return;
        if (!report) throw new IllegalStateException(message);
        misses.add(label + ": " + message);
    }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
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
