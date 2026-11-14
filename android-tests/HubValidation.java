package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.HubText;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.PhoneRideStore;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;
import com.jjricks.volt.WideLayout;

/**
 * Fresh disposable emulator only (refuses a physical phone, existing app data and Bluetooth turned on). Walks the Hub
 * (hub layout plan, PR 11; part 11b of the series) on the developer simulator (a synthetic UT5 MAX profile, no
 * scooter), the way an owner would, by tapping the views Home and each page show:
 *
 * <ul>
 *   <li><b>Clean Home</b>: each of the seven subject rows opens its subject, with Home under it, a back link and its
 *   title; each sub-page opens from its subject's row or link, its back link returns to the subject and system Back to
 *   Home. The gear opens Settings; Settings' own rows open its sub-pages the same way. A page opens through the page
 *   stack's own route only where ROUTED lists it for the simulated model (a row that model hides); a page outside that
 *   list with no row fails the walk, and so does a listed page whose row shows.</li>
 *   <li><b>The page stack</b>: opening a page already in the stack closes the pages above it, Home resets it, and an
 *   activity recreation (as a rotation or a language change makes) keeps the whole stack and the page shown.</li>
 *   <li><b>Search</b>: the header's search opens the search page with its field focused and the subjects listed;
 *   typing ("batt", as the plan's mock-up) hides the subjects and finds at least one row (pictured with the keyboard gone); the system Back first clears the query (the subjects come back), then returns to Home;
 *   the page's back link returns to Home at once, even with a query typed. Search's own results (settings, readings,
 *   rides) arrive with part 12, which checks them.</li>
 *   <li><b>Moments</b>: the Lock bar (and the top area above it) stay at the same place and size while the top area
 *   changes from the battery card to the simulator's charging view and back, and to Just back for a ride saved just then
 *   (its ended line, distance, tiles and route bound by Clean Home's own refresh, the two lines on one line each, the
 *   three tile values at one size on one baseline and their names on one line each; the Ride row under it shows that
 *   ride, not "No rides yet"), and
 *   so does Home's scooter name (home-switch-scooter), so an app-side scroll jump between moments fails. Home is scrolled
 *   to its top first, and every moment is pictured at that scroll. Each place is measured only while the moment it
 *   belongs to is the one shown, its crossfade over. Every picture waits for a frame drawn after the views changed to
 *   reach the screen of the window it pictures (the activity's, or the All sheet's own).</li>
 *   <li><b>Widgets Home</b>: its header is main's (no search or gear of its own); Search and Settings are the first rows
 *   of the dock's All sheet and open over Home, and Back returns to Widgets Home; the dock shows on Widgets Home only
 *   (never on a subject page, never on Clean), each shown subject's icon opens it, and its All item opens the sheet
 *   listing all seven subjects, whose rows open their subject and close the sheet. With -e wide true (a window at least
 *   650 dp wide) or false (a narrower one), the dock must be the layout's: all seven when the window at this text size
 *   gets the wide layout (WideLayout.wide), else the phone's most-used ones (HubPages.dockSubjects; at 725x604 dp and
 *   1.4x text, a phone dock that holds all seven). While the dock shows, it covers no shown part of Widgets' Lock bar,
 *   on arrival and after each Back; where it would, it lifts to end 10 dp above the bar (part 11; 725x604 dp at 1.4x
 *   text, and the phones whose Shortcuts card pushes the bar down). It may step aside only where lifted it would leave
 *   the page; then the walk scrolls Home as a rider would until the dock comes back (HubTestPages.showDock) before
 *   tapping it.</li>
 *   <li><b>Taps</b>: every tap first scrolls its view into sight, and the view's centre must then be on screen and, on a
 *   page with the dock, above the dock; a view an owner cannot see is not tapped.</li>
 *   <li><b>No scooter command</b>: the simulator's settings are unchanged at the end, no radio object, queued frame,
 *   pending change or recording exists, and nothing on the walk taps the Lock bar or a quick action.</li>
 * </ul>
 *
 * Pass -e mode all (the default) for all of the above. -e mode widgets-pages checks only how Widgets Home reaches Search
 * and Settings, starting on Widgets: the dock's All sheet lists both and they open over Home (part 11 kept main's header
 * on Widgets Home, at the owner's answer on #392). -e theme dark|light and -e text_scale (1 by default) as the other
 * fixture runners. Every word it types or reads is English: run it with the emulator in en-US.
 */
public final class HubValidation extends Instrumentation {
    private static final String FIXTURE_NAME = "Synthetic hub fixture", FIXTURE_IDENTITY = "A1B2C3D4E5F6",
            FIXTURE_SERIAL = "X2538-SYNTHETIC", FIXTURE_MAC = "C0:FF:EE:12:34:56";
    /** home_style values and keys (HomeStyle, package-private in the app). */
    private static final String STYLE_KEY = "home_style", STYLE_CHOSEN = "home_style_chosen", STYLE_ORIGIN = "home_style_origin",
            CLEAN = "clean", WIDGETS = "widgets";
    /** Settings' rows that open its sub-pages, by their unchanged tags (MoreScreen). */
    private static final Map<String, String> SETTINGS_ROWS = new TreeMap<>();
    static {
        SETTINGS_ROWS.put(HubPages.SETTINGS_HOME, "home-style-menu");
        SETTINGS_ROWS.put(HubPages.SETTINGS_UNITS, "preference-units-menu");
        SETTINGS_ROWS.put(HubPages.SETTINGS_DEVELOPER, "developer-options-menu");
    }
    /**
     * The pages the walk may open by the page stack's route, per simulated model (BleController.simulationModel(); "" is
     * the UT5 MAX), because that model hides their row or link. Any other page must be tapped from its row, and a listed
     * page whose row shows fails the walk too, so the list stays true. The UT5 MAX shows every row the walk taps.
     */
    private static final Map<String, List<String>> ROUTED = new TreeMap<>();
    static {
        ROUTED.put("", Collections.<String>emptyList());
    }
    /** Settings' rows for My scooters and About (MoreScreen; tagged in part 11b). */
    static {
        SETTINGS_ROWS.put(HubPages.SETTINGS_SCOOTERS, "my-scooters-menu");
        SETTINGS_ROWS.put(HubPages.SETTINGS_ABOUT, "about-menu");
    }
    private Bundle arguments;
    /** ROUTED's list for the simulated model the walk runs on. */
    private List<String> mayRoute = Collections.emptyList();
    private String simulatedModel = "";
    private MainActivity activity;
    private boolean admitted;
    /** The gap the dock keeps above the Lock bar when it lifts (HubDock.BOTTOM_DP, HubPages.dockLift's gap). */
    private static final int DOCK_GAP_DP = 10;
    /** The dock was seen lifted above the Lock bar (dockClearOfLock). */
    private boolean dockLifted;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>(), routed = new ArrayList<>();
    /** The simulator's settings when the walk began: a command would change them. */
    private final TreeMap<String, Integer> simulatedBefore = new TreeMap<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            String mode = arguments.getString("mode", "all");
            require(mode.equals("all") || mode.equals("widgets-pages"), "Unknown -e mode " + mode + " (all or widgets-pages)");
            boolean widgetsFirst = mode.equals("widgets-pages");
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1")))
                    // A style the owner chose, on a new installation: no What's new sheet covers Home.
                    .putString(STYLE_KEY, widgetsFirst ? WIDGETS : CLEAN).putBoolean(STYLE_CHOSEN, true).putString(STYLE_ORIGIN, "install")
                    .commit(), "Fixture settings failed");
            seed();
            launch();
            simulate();
            if (widgetsFirst) widgetsPages(true);
            else {
                cleanSubjects();
                pageStack();
                search();
                moments();
                widgets();
            }
            noCommands();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("routed_pages", routed); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try {
                main(() -> { if (activity.service() != null) activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ===== The walk =====

    /** Clean Home: every subject from its row, every sub-page from its subject, back links and system Back; the gear. */
    private void cleanSubjects() throws Exception {
        requireHome("CleanHomeScreen");
        main(() -> require(field(activity, "dock") == null, "Clean Home shows the dock"));
        screenshot("clean-home");
        for (String subject : HubPages.SUBJECTS) {
            tapTag("home-subject-" + subject);
            arrived(subject, HubPages.HOME, subject);
            main(() -> require(field(activity, "dock") == null, subject + " shows the dock"));
            screenshot("subject-" + subject);
            for (String child : children(subject)) {
                reach(child, Arrays.asList("hub-link-" + child, "hub-row-" + child));
                arrived(child, HubPages.HOME, subject, child);
                HubTestPages.back(this, activity);
                arrived(subject, HubPages.HOME, subject);
            }
            systemBack();
            requireHome("CleanHomeScreen");
            requireStack(HubPages.HOME);
        }
        checks.add("Clean Home: each subject row opens its subject over Home; each sub-page opens from it; back links and Back return");
        // Settings behind the gear, and its own rows.
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        screenshot("settings");
        for (String child : children(HubPages.SETTINGS)) {
            String tag = SETTINGS_ROWS.get(child);
            require(tag != null, "HubValidation names no Settings row for " + child + " (SETTINGS_ROWS)");
            reach(child, Collections.singletonList(tag));
            arrived(child, HubPages.HOME, HubPages.SETTINGS, child);
            systemBack();
            arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        }
        HubTestPages.back(this, activity);
        requireHome("CleanHomeScreen");
        checks.add("Clean Home's gear opens Settings over Home; Settings' rows open its pages; Back returns to Settings, its back link to Home");
        for (String page : mayRoute) require(routed.contains(page), page + " is listed as routed for model \"" + simulatedModel + "\" but the walk never reached it");
        checks.add("Routed pages: " + routed + ", exactly the ones the simulated model hides (" + mayRoute + ")");
    }

    /** Opening a page already in the stack closes the pages above it; Home resets it; recreation keeps it. */
    private void pageStack() throws Exception {
        tapTag("home-subject-" + HubPages.SERVICE);
        reach(HubPages.SERVICE_MAINTENANCE, Collections.singletonList("hub-row-" + HubPages.SERVICE_MAINTENANCE));
        open(HubPages.SERVICE_REPORTS);
        requireStack(HubPages.HOME, HubPages.SERVICE, HubPages.SERVICE_MAINTENANCE, HubPages.SERVICE_REPORTS);
        open(HubPages.SERVICE);
        requireStack(HubPages.HOME, HubPages.SERVICE);
        open(HubPages.SERVICE_MAINTENANCE);
        recreateActivity();
        arrived(HubPages.SERVICE_MAINTENANCE, HubPages.HOME, HubPages.SERVICE, HubPages.SERVICE_MAINTENANCE);
        screenshot("recreated-maintenance");
        systemBack();
        arrived(HubPages.SERVICE, HubPages.HOME, HubPages.SERVICE);
        open(HubPages.SERVICE_COMPATIBILITY);
        open(HubPages.HOME);
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        checks.add("Page stack: a page already open closes those above it, Home resets the stack, recreation keeps it and the page shown");
    }

    /**
     * The header's search, as far as part 11 has it: its field focused, the subjects listed, typing hides them; Back
     * clears the query before it leaves, the page's back link leaves at once. Search's results arrive with part 12.
     */
    private void search() throws Exception {
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        EditText[] field = new EditText[1];
        await(() -> onMain(() -> { field[0] = firstEditText(activity.findViewById(android.R.id.content)); return field[0] != null && field[0].hasFocus(); }),
                "The search page's field is not focused");
        main(() -> {
            for (String subject : HubPages.SUBJECTS) require(shown(root().findViewWithTag("home-subject-" + subject)), "Search does not list " + subject + " while empty");
        });
        // "batt", as the plan's search mock-up: part 11's search over the scooter rows finds Battery's (the full search, with
        // settings, readings and rides, is part 12's).
        main(() -> field[0].setText("batt")); settle();
        main(() -> require(!shown(root().findViewWithTag("home-subject-" + HubPages.RIDE)), "Search still lists the subjects while a query is typed"));
        main(() -> {
            int found = resultCount(activity.findViewById(android.R.id.content));
            require(found > 0, "Search found nothing for \"batt\" (its count line reads " + found + ")");
        });
        // Pictured with the keyboard gone, so every window size pictures the typed query the same way.
        hideKeyboard();
        screenshot("search-typed");
        systemBack();
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        main(() -> {
            EditText now = firstEditText(activity.findViewById(android.R.id.content));
            require(now != null && now.getText().length() == 0, "Back did not clear the search's query first");
            require(shown(root().findViewWithTag("home-subject-" + HubPages.RIDE)), "Back cleared the query but the subjects did not come back");
        });
        systemBack();
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        // The page's link ("Back to Home") goes there at once, query or not (HubNav.linkSkipsPageBack).
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        main(() -> firstEditText(activity.findViewById(android.R.id.content)).setText("batt")); settle();
        hideKeyboard();
        HubTestPages.back(this, activity);
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        checks.add("Search: opens focused with the subjects listed; typing \"batt\" hides them and finds at least one row; Back clears the query, then returns to Home; the back link returns to Home at once");
    }

    /**
     * The Lock bar keeps its place and size, and Home's scooter name its place, while the top area changes moment; each
     * moment is pictured at the same scroll (Home's top).
     */
    private void moments() throws Exception {
        requireHome("CleanHomeScreen");
        Object clean = field(activity, "screen");
        main(() -> ((ScrollView) field(activity, "scroll")).scrollTo(0, 0));
        settle();
        // The scroll bar a scroll shows fades on its own; every moment is pictured without it.
        SystemClock.sleep(ViewConfiguration.getScrollDefaultDelay() + ViewConfiguration.getScrollBarFadeDuration() + 100);
        settle();
        main(() -> require(((ScrollView) field(activity, "scroll")).getScrollY() == 0, "Clean Home did not scroll back to its top"));
        int[] parked = placeIn(clean, 0, "the battery card");
        screenshot("moment-parked");
        // Still the battery card after the picture, at the place measured.
        int[] afterPicture = placeIn(clean, 0, "the battery card");
        require(Arrays.equals(parked, afterPicture), "Clean Home moved while the parked moment was pictured: " + describe(parked) + " -> " + describe(afterPicture));
        BleController ble = activity.service().ble;
        main(() -> { ble.setSimulationCharging(true); activity.redraw(); });
        await(() -> onMain(() -> { activity.redraw(); return ((Integer) field(clean, "moment")) == 1; }), "Clean Home does not show the charging view while the simulator charges");
        int[] charging = placeIn(clean, 1, "the charging view");
        require(Arrays.equals(parked, charging), "The Lock bar or the scooter name moved for the charging view: " + describe(parked) + " -> " + describe(charging));
        screenshot("moment-charging");
        main(() -> { ble.setSimulationCharging(false); activity.redraw(); });
        await(() -> onMain(() -> { activity.redraw(); return ((Integer) field(clean, "moment")) == 0; }), "Clean Home did not return to the battery card");
        int[] back = placeIn(clean, 0, "the battery card");
        require(Arrays.equals(parked, back), "The Lock bar or the scooter name moved back from the charging view: " + describe(parked) + " -> " + describe(back));
        // Just back for a real ride: one finished ride saved now, 20 min and 10 km (6.2 mi) long, 80 % to 62 %, with the
        // phone's GPS loop for its route, so Clean Home's own refresh (HomeMoment.choose, the ride moment on by default)
        // picks it and binds the card: the ended line, the distance, the three tiles and the route, as an owner sees it.
        String ride = seedFinishedRide();
        main(() -> { setField(clean, "ridesLooked", -1L); activity.redraw(); });
        await(() -> onMain(() -> { activity.redraw(); return ((Integer) field(clean, "moment")) == 2; }), "Clean Home does not show Just back for a ride that just ended");
        Object card = onMain(() -> field(clean, "justBack"));
        main(() -> require(ride.equals(invoke(card, "rideId", new Class<?>[0])), "Just back shows another ride than the one just saved"));
        // The route is read on a worker once the card is bound.
        await(() -> onMain(() -> field(field(card, "route"), "route") != null), "Just back drew no route for the ride's GPS fixes");
        int[] justBack = placeIn(clean, 2, "the Just back card");
        require(Arrays.equals(parked, justBack), "The Lock bar or the scooter name moved for the Just back card: " + describe(parked) + " -> " + describe(justBack));
        main(() -> {
            for (String name : new String[]{"saved", "distance"}) {
                TextView text = (TextView) field(card, name);
                require(shown(text) && text.getText().length() > 0 && !"—".contentEquals(text.getText()), "Just back's " + name + " line is empty: \"" + text.getText() + "\"");
                require(text.getLineCount() == 1, "Just back's " + name + " line \"" + text.getText() + "\" takes " + text.getLineCount() + " lines");
            }
            require(((TextView) field(card, "saved")).getText().toString().startsWith("Ride ended"), "Just back's ended line reads \"" + ((TextView) field(card, "saved")).getText() + "\"");
            for (String name : new String[]{"time", "speed", "battery"}) {
                TextView value = (TextView) field(field(card, name), "value");
                require(value.getText().length() > 0 && !"—".contentEquals(value.getText()), "Just back's " + name + " tile shows no value: \"" + value.getText() + "\"");
            }
            TextView time = (TextView) field(field(card, "time"), "value");
            require("20m".contentEquals(time.getText()), "Just back's Ride time reads \"" + time.getText() + "\" for a 20 min ride");
            // Part 9: the three values at one size on one baseline, and the three names each on one line at one size.
            TextView first = null, firstName = null;
            for (String name : new String[]{"time", "speed", "battery"}) {
                TextView value = (TextView) field(field(card, name), "value"), label = (TextView) field(field(card, name), "name");
                require(label.getLineCount() == 1, "Just back's " + name + " tile name \"" + label.getText() + "\" takes " + label.getLineCount() + " lines");
                if (first == null) { first = value; firstName = label; continue; }
                require(value.getTextSize() == first.getTextSize(), "Just back's " + name + " value is " + value.getTextSize() + " px, the Ride time's " + first.getTextSize() + " px");
                require(baselineY(value) == baselineY(first), "Just back's " + name + " value sits on baseline " + baselineY(value) + ", the Ride time's on " + baselineY(first));
                require(label.getTextSize() == firstName.getTextSize(), "Just back's " + name + " name is " + label.getTextSize() + " px, the Ride time's " + firstName.getTextSize() + " px");
            }
        });
        // Part 9: the Ride row under it shows the ride Just back shows (CleanSubjects.rideFound), not "No rides yet": the
        // seeded ride's 6.2 mi.
        Object subjects = onMain(() -> field(clean, "subjects"));
        String none = HubText.rideNone().render();
        String[] rideLine = {null};
        await(() -> onMain(() -> {
            activity.redraw();
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) field(subjects, "ids");
            @SuppressWarnings("unchecked") List<TextView> values = (List<TextView>) field(subjects, "values");
            TextView value = values.get(ids.indexOf(HubPages.RIDE));
            rideLine[0] = shown(value) ? value.getText().toString() : null;
            return rideLine[0] != null && !rideLine[0].equals(none) && rideLine[0].contains("6.2");
        }), () -> "The Ride row reads \"" + rideLine[0] + "\" while Just back shows the 6.2 mi ride just saved");
        screenshot("moment-just-back");
        // Still Just back after the picture, at the place measured (only a closed card or a newer ride would change it).
        int[] afterJustBack = placeIn(clean, 2, "the Just back card");
        require(Arrays.equals(parked, afterJustBack), "Clean Home moved while Just back was pictured: " + describe(parked) + " -> " + describe(afterJustBack));
        checks.add("Moments: at Home's top, the Lock bar and the top area keep their place and size, and the scooter name its place, for the battery card, charging and Just back"
                + " (a ride saved just now: its ended line, distance, tiles at one value size and baseline, and route shown; the Ride row shows that ride); each pictured at that scroll");
    }

    /**
     * One finished ride in the service's own stores, ended a few seconds ago: 20 min, 10 km, 80 % to 62 %, with a GPS
     * loop in its phone journal (as the design tour's). Nothing reaches the scooter; the recording is closed at once.
     */
    private String seedFinishedRide() {
        return onMain(() -> {
            ScooterService service = activity.service();
            long end = System.currentTimeMillis() - 5_000L, start = end - 20 * 60_000L;
            // The ride's own monotonic clock, as the design tour's: a fresh emulator may have been up for less than 20 min.
            long mono = 100_000L, monoEnd = mono + 20 * 60_000L;
            int steps = 60;
            long step = (monoEnd - mono) / steps;
            try {
                ScooterProfiles.Profile active = service.profiles.active();
                require(active != null, "No scooter profile for the synthetic ride");
                String profile = active.id;
                RideStore rides = service.rides;
                String id = rides.start(profile, "Synthetic Hub ride", start, mono);
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
                for (int i = 0; i <= 120; i++) {
                    double t = i / 120.0 * 2 * Math.PI;
                    double lat = 43.8200 + 0.012 * Math.sin(t) + 0.004 * Math.sin(3 * t), lon = -111.7800 + 0.018 * (1 - Math.cos(t)) - 0.003 * Math.sin(2 * t);
                    rows.append(PhoneRideStore.row("GPS", start + i * 10_000L, mono + i * 10_000L, lat, lon, 4, 8, 0.2, 1500, 4));
                }
                phone.appendBatch(id, rows.toString(), "");
                phone.stop(id, end, monoEnd);
                require(!service.isRecording(), "The synthetic ride is still recording");
                return id;
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        });
    }

    /** Widgets Home: the dock, each of its items, and its All sheet. */
    private void widgets() throws Exception {
        switchStyle(WIDGETS);
        requireHome("DashboardScreen");
        Object dock = dock();
        @SuppressWarnings("unchecked") List<String> shown = new ArrayList<>((List<String>) invoke(dock, "shown", new Class<?>[0]));
        boolean layoutWide = WideLayout.wide(widthDp(), textScale());
        main(() -> require(Boolean.valueOf(layoutWide).equals(field(dock, "wide")), "The dock's layout is " + (layoutWide ? "not " : "") + "the wide one at "
                + widthDp() + " dp and " + textScale() + "x text"));
        String layout = layoutWide ? "wide" : "phone";
        if (arguments.containsKey("wide")) {
            // -e wide names the window: true, at least the wide layout's 650 dp (725x604 dp); false, a phone's. The wide
            // window gets the wide dock (all seven) at 1x text; at 1.4x it has the phone layout (725 / 1.4 < 650 dp),
            // whose dock still holds all seven in its 689 dp of room (HubPages.dockSubjects).
            boolean wideWindow = Boolean.parseBoolean(arguments.getString("wide"));
            require(wideWindow == (widthDp() >= WideLayout.MIN_TEXT_DP), "-e wide " + wideWindow + " on a window " + widthDp() + " dp wide");
            if (!wideWindow) require(!layoutWide, "A window under " + WideLayout.MIN_TEXT_DP + " dp got the wide layout");
            if (wideWindow && textScale() <= 1f) require(layoutWide, "The wide window at 1x text did not get the wide layout");
            List<String> expected = layoutWide ? HubPages.SUBJECTS : HubPages.dockSubjects(dockRoomDp(), textScale());
            require(shown.equals(expected), "The " + layout + " dock shows " + shown + ", expected " + expected);
        }
        require(!shown.isEmpty() && HubPages.SUBJECTS.containsAll(shown), "The dock shows " + shown);
        main(() -> require(root().findViewWithTag("hub-dock") != null && root().findViewWithTag("hub-dock-handle") != null, "Widgets Home has no dock or All item"));
        boolean aside = dockClearOfLock("Widgets Home");
        // Pictured as Home opens: with the dock lifted above the Lock bar where it would cover it.
        screenshot("widgets-home");
        dockInReach("Widgets Home");
        main(() -> require(shown(root().findViewWithTag("hub-dock")) && shown(root().findViewWithTag("hub-dock-handle")), "The dock or its All item is not shown"));
        widgetsPages(false);
        for (String subject : shown) {
            dockInReach("Widgets Home before " + subject);
            main(() -> { View item = root().findViewWithTag("hub-dock-" + subject); require(shown(item), "The dock has no " + subject + " item");
                require(item.getContentDescription() != null && item.getContentDescription().length() > 0, "The dock's " + subject + " item has no spoken name"); });
            tapTag("hub-dock-" + subject);
            arrived(subject, HubPages.HOME, subject);
            main(() -> require(field(activity, "dock") == null, "The dock shows on " + subject));
            systemBack();
            requireHome("DashboardScreen");
            dockClearOfLock("Widgets Home after " + subject);
        }
        for (String subject : HubPages.SUBJECTS) if (!shown.contains(subject))
            main(() -> require(root().findViewWithTag("hub-dock-" + subject) == null, "The dock has an item for " + subject + ", which it does not list"));
        checks.add("Widgets dock (" + layout + " layout at " + widthDp() + " dp, " + textScale() + "x text): " + shown + " each open their subject over Home; Back returns to Widgets Home; "
                + "no dock on subject pages; the dock never over the Lock bar" + (dockLifted ? "; it lifted " + DOCK_GAP_DP + " dp above the Lock bar where it would cover it" : "")
                + (aside ? "; it stepped aside over the Lock bar as Home opened (no room to lift it) and came back once Home scrolled" : "; never aside"));
        // All: the sheet with every subject; a row opens its subject and closes the sheet.
        for (String subject : HubPages.SUBJECTS) {
            AlertDialog[] sheet = {openAllSheet()};
            main(() -> {
                View content = sheet[0].getWindow().getDecorView();
                require(content.findViewWithTag("hub-dock-sheet") != null, "The All sheet has no content tag");
                for (String each : HubPages.SUBJECTS) require(shown(content.findViewWithTag("home-subject-" + each)), "The All sheet does not list " + each);
            });
            if (subject.equals(HubPages.SUBJECTS.get(0))) screenshot("widgets-all-sheet", onMain(() -> sheet[0].getWindow().getDecorView()));
            tapIn(() -> sheet[0].getWindow().getDecorView(), "home-subject-" + subject, "home-subject-" + subject + " in the All sheet");
            await(() -> onMain(() -> !sheet[0].isShowing()), "The All sheet stayed open after opening " + subject);
            arrived(subject, HubPages.HOME, subject);
            systemBack();
            requireHome("DashboardScreen");
        }
        checks.add("Widgets dock: All opens the sheet listing all seven subjects; each row opens its subject and closes the sheet");
        // Switching back never leaves a dock behind.
        switchStyle(CLEAN);
        requireHome("CleanHomeScreen");
        main(() -> require(field(activity, "dock") == null && root().findViewWithTag("hub-dock") == null, "Clean Home kept the dock"));
        checks.add("Home style switches between Clean and Widgets in place; only Widgets has the dock");
    }

    /**
     * How Widgets Home reaches Search and Settings: its header is main's (no search or gear of its own, the owner's answer
     * on #392), and the dock's All sheet lists both first, under Clean Home's header tags; each opens over Home and Back
     * returns to Widgets Home. Alone (-e mode widgets-pages) it takes its own screenshots.
     */
    private void widgetsPages(boolean alone) throws Exception {
        requireHome("DashboardScreen");
        if (alone) screenshot("widgets-home");
        main(() -> require(root().findViewWithTag("home-search") == null && root().findViewWithTag("home-settings") == null,
                "Widgets Home's header has a search or gear of its own; part 11 keeps main's header"));
        for (String[] each : new String[][]{{"home-search", HubPages.SEARCH}, {"home-settings", HubPages.SETTINGS}}) {
            AlertDialog sheet = openAllSheet();
            main(() -> require(shown(sheet.getWindow().getDecorView().findViewWithTag(each[0])), "The All sheet has no " + each[0] + " row"));
            if (alone && each[1].equals(HubPages.SEARCH)) screenshot("widgets-all-sheet-pages", onMain(() -> sheet.getWindow().getDecorView()));
            tapIn(() -> sheet.getWindow().getDecorView(), each[0], each[0] + " in the All sheet");
            await(() -> onMain(() -> !sheet.isShowing()), "The All sheet stayed open after " + each[0]);
            arrived(each[1], HubPages.HOME, each[1]);
            if (each[1].equals(HubPages.SEARCH)) {
                await(() -> onMain(() -> { EditText field = firstEditText(activity.findViewById(android.R.id.content)); return field != null && field.hasFocus(); }),
                        "Search opened from the All sheet without its field focused");
                hideKeyboard();
            }
            systemBack();
            requireHome("DashboardScreen");
        }
        checks.add("Widgets Home keeps main's header; the All sheet's Search and Settings rows open those pages over Home, and Back returns to Widgets Home");
    }

    /**
     * Part 11 keeps the dock off Widgets' Lock bar: where it would cover any shown part of it, the dock lifts to float
     * 10 dp above the bar (HubPages.dockLift), and steps aside only where lifted it would leave the page. While the dock
     * shows, no shown part of the Lock bar (MainActivity.dashboardLock) is under it, and a lifted dock ends 10 dp above
     * the bar; while it has stepped aside (invisible, keeping its place), it would cover the bar there AND lifted it would
     * start above the page frame's top, so a dock aside where it had room fails. Measured in window pixels here, not with
     * the app's own rules (HubPages.dockCovers, dockLift). True when the dock is aside.
     */
    private boolean dockClearOfLock(String where) {
        return onMain(() -> {
            View dock = root().findViewWithTag("hub-dock"), lock = (View) field(activity, "dashboardLock");
            require(lock != null, where + " has no Lock bar");
            require(dock != null && dock.getVisibility() != View.GONE, where + " has no dock");
            Rect bar = new Rect();
            boolean barShown = lock.isShown() && lock.getGlobalVisibleRect(bar);
            int[] lockAt = new int[2]; lock.getLocationInWindow(lockAt);
            int gap = Math.round(DOCK_GAP_DP * activity.getResources().getDisplayMetrics().density);
            if (dock.getVisibility() == View.INVISIBLE) {
                int[] at = new int[2]; dock.getLocationInWindow(at);
                Rect place = new Rect(at[0], at[1], at[0] + dock.getWidth(), at[1] + dock.getHeight());
                require(barShown && Rect.intersects(place, bar), where + ": the dock stepped aside but its place " + place.toShortString()
                        + " is clear of the Lock bar's shown part " + (barShown ? bar.toShortString() : "(none)"));
                // Aside only where there is no room to lift it: floated gap above the bar, its top would be above the
                // top of the page frame it floats in (under the app's bars).
                View frame = (View) dock.getParent();
                int[] frameAt = new int[2]; frame.getLocationInWindow(frameAt);
                int frameTop = frameAt[1] + frame.getPaddingTop(), liftedTop = lockAt[1] - gap - dock.getHeight();
                require(liftedTop < frameTop, where + ": the dock stepped aside although lifted " + DOCK_GAP_DP + " dp above the Lock bar (top " + lockAt[1]
                        + ") it would start at " + liftedTop + ", inside the page from " + frameTop);
                return true;
            }
            if (!shown(dock) || !barShown) return false;
            Rect over = new Rect();
            require(!dock.getGlobalVisibleRect(over) || !Rect.intersects(over, bar), where + ": the dock " + over.toShortString() + " covers the Lock bar's shown part " + bar.toShortString());
            if (dock.getTranslationY() < 0) {
                // Lifted: it floats the gap above the bar, not anywhere higher (a dock lifted further than it needs hides
                // more of the page for nothing).
                int[] at = new int[2]; dock.getLocationInWindow(at);
                int bottom = at[1] + dock.getHeight();
                require(Math.abs(lockAt[1] - gap - bottom) <= 1, where + ": the lifted dock ends at " + bottom + ", not " + DOCK_GAP_DP + " dp above the Lock bar at " + lockAt[1]);
                dockLifted = true;
            }
            return false;
        });
    }

    /**
     * The dock as a rider finds it before a tap (HubTestPages.showDock: shown and still; where it had stepped aside, Home
     * scrolled to its end as a rider would until it shows again), then dockClearOfLock on it.
     */
    private void dockInReach(String where) {
        HubTestPages.showDock(this, activity);
        settle();
        require(!dockClearOfLock(where + " with the dock shown"), where + ": the dock stepped aside again");
    }

    /** Taps the dock's All item and returns its sheet, open and settled. */
    private AlertDialog openAllSheet() {
        dockInReach("Widgets Home before All");
        tapTag("hub-dock-handle");
        AlertDialog[] sheet = new AlertDialog[1];
        await(() -> onMain(() -> { sheet[0] = (AlertDialog) field(dock(), "sheet"); return sheet[0] != null && sheet[0].isShowing(); }), "All did not open the subjects sheet");
        settle();
        return sheet[0];
    }

    /** Nothing on the walk reached the scooter: the simulator's settings, radio, queue, pending change and recordings. */
    private void noCommands() {
        main(() -> {
            BleController ble = activity.service().ble;
            require(ble.simulating(), "The simulator stopped during the walk");
            require(simulatedBefore.equals(new TreeMap<>(ble.simulationFeatures())), "A simulated setting changed: " + simulatedBefore + " -> " + ble.simulationFeatures());
            for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(ble, name) == null, "The walk touched " + name);
            require(((Collection<?>) field(ble, "queue")).isEmpty(), "The walk queued radio bytes");
            require(ble.pending() == null, "The walk left a pending change: " + ble.pending());
            ScooterService service = activity.service();
            require(!service.isRecording() && !service.isChargeRecording() && !service.phoneSaving(), "The walk started a recording");
            require(field(activity, "riding") == null, "Riding mode opened during the walk");
        });
        checks.add("No scooter command: simulated settings unchanged; no radio, queued frame, pending change, recording or riding mode");
    }

    // ===== Navigation helpers =====

    /** The pages directly under a page, in HubPages.ALL's order. */
    private static List<String> children(String page) {
        ArrayList<String> found = new ArrayList<>();
        for (String id : HubPages.ALL) if (page.equals(HubPages.parent(id))) found.add(id);
        return found;
    }

    /**
     * Opens a page by tapping the first shown view with one of the tags. Only a page ROUTED lists for the simulated model
     * (whose row that model hides) opens by its route instead; a missing row for any other page fails, and so does a
     * shown row for a listed page.
     */
    private void reach(String page, List<String> tags) throws Exception {
        String found = null;
        for (String tag : tags) {
            boolean[] visible = new boolean[1];
            main(() -> visible[0] = shown(root().findViewWithTag(tag)));
            if (visible[0]) { found = tag; break; }
        }
        boolean listed = mayRoute.contains(page);
        if (found != null) {
            require(!listed, page + " is listed as routed for model \"" + simulatedModel + "\" but its row shows (" + found + "): update ROUTED");
            tapTag(found);
        } else {
            require(listed, "No row or link for " + page + " (" + tags + ") on model \"" + simulatedModel + "\", which ROUTED does not list as hiding it");
            routed.add(page); open(page);
        }
    }
    private void open(String page) {
        main(() -> invoke(activity, "open", new Class<?>[]{String.class}, page));
        settle();
    }
    private void tapTag(String tag) { tapIn(this::root, tag, tag); }
    /**
     * Scrolls the view with tag (in window's views) into sight as an owner would, then taps it: only where its centre is
     * on screen and, on a page with the dock, above the dock.
     */
    private void tapIn(Supplier<View> window, String tag, String label) {
        main(() -> {
            View view = window.get().findViewWithTag(tag);
            require(shown(view), label + " is not shown");
            view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
        });
        settle();
        main(() -> click(window.get().findViewWithTag(tag), label));
        settle();
    }
    /** A tap on view, or on its first clickable descendant, or its nearest clickable ancestor; the view must be in sight. */
    private void click(View view, String label) {
        require(shown(view), label + " is not shown");
        View target = view.isClickable() ? view : clickableChild(view);
        for (View at = view; target == null && at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null)
            if (at.isClickable()) target = at;
        require(target != null && target.isEnabled(), label + " cannot be tapped");
        requireInSight(target == view || isInside(target, view) ? target : view, label);
        target.performClick();
    }
    /**
     * The view's centre is inside its visible part of the window (not scrolled away or clipped) and, in the activity's
     * window with the dock shown, above the dock (unless the view is part of the dock).
     */
    private void requireInSight(View view, String label) {
        int[] at = new int[2]; view.getLocationInWindow(at);
        int x = at[0] + view.getWidth() / 2, y = at[1] + view.getHeight() / 2;
        Rect visible = new Rect();
        require(view.getGlobalVisibleRect(visible) && visible.contains(x, y), label + " is not on screen (centre " + x + "," + y + ", visible " + visible.toShortString() + ")");
        if (view.getRootView() != root()) return;
        View dock = root().findViewWithTag("hub-dock");
        if (!shown(dock) || isInside(view, dock)) return;
        int[] dockAt = new int[2]; dock.getLocationInWindow(dockAt);
        require(y < dockAt[1], label + " is under the dock (centre y " + y + ", dock top " + dockAt[1] + ")");
    }
    private static boolean isInside(View view, View ancestor) {
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) if (at == ancestor) return true;
        return false;
    }
    private View clickableChild(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isClickable()) return child;
            View found = clickableChild(child);
            if (found != null) return found;
        }
        return null;
    }
    private void systemBack() { sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(); }
    private void hideKeyboard() {
        main(() -> {
            View focus = activity.getCurrentFocus();
            android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (focus != null && input != null) { input.hideSoftInputFromWindow(focus.getWindowToken(), 0); focus.clearFocus(); }
        });
        // Until the keyboard has left the window (its insets), so what follows (a picture, Back) sees it gone.
        if (Build.VERSION.SDK_INT >= 30) await(() -> onMain(() -> { WindowInsets insets = root().getRootWindowInsets(); return insets == null || !insets.isVisible(WindowInsets.Type.ime()); }),
                "The keyboard stayed shown after hiding it");
        settle();
    }

    /** The page shown is page, the stack is exactly stack, and a non-Home page has its back link. */
    private void arrived(String page, String... stack) {
        await(() -> onMain(() -> page.equals(field(activity, "selectedPage")) && !Boolean.TRUE.equals(field(activity, "rebuilding"))), () -> "Expected page " + page + ", shown " + onMain(() -> field(activity, "selectedPage")));
        settle();
        requireStack(stack);
        if (!HubPages.HOME.equals(page)) main(() -> {
            View back = root().findViewWithTag("hub-back");
            require(shown(back), page + " has no back link");
            require(back.getContentDescription() != null && back.getContentDescription().length() > 0, page + "'s back link has no spoken name");
        });
    }
    @SuppressWarnings("unchecked")
    private void requireStack(String... expected) {
        main(() -> {
            List<String> pages = (List<String>) invoke(field(activity, "nav"), "pages", new Class<?>[0]);
            require(Arrays.asList(expected).equals(pages), "Page stack " + pages + ", expected " + Arrays.asList(expected));
        });
    }
    private void requireHome(String screenClass) {
        await(() -> onMain(() -> HubPages.HOME.equals(field(activity, "selectedPage")) && field(activity, "screen") != null
                && field(activity, "screen").getClass().getSimpleName().equals(screenClass)), "Home is not " + screenClass);
        settle();
        main(() -> require(root().findViewWithTag("hub-back") == null, "Home has a back link"));
    }
    private Object dock() {
        Object dock = field(activity, "dock");
        require(dock != null, "Widgets Home has no dock");
        return dock;
    }
    /** The room a phone dock has (HubPages.dockRoom of the window's width, as HubDock measures it). */
    private int dockRoomDp() { return HubPages.dockRoom(widthDp()); }
    /** The window's width in dp (Ui.wideLayout's). */
    private int widthDp() { return activity.getResources().getConfiguration().screenWidthDp; }
    /** App text size times Android's font size, at least 1 (HubDock's scale). */
    private float textScale() { return Math.max(1f, activity.ui().textScale * activity.getResources().getConfiguration().fontScale); }


    /**
     * The Lock bar's place in the window and its size, the top area's height, and the scooter name's (home-switch-scooter)
     * place in the window (px): a scroll jump between moments moves the name even where the top area keeps its size. On
     * the main thread.
     */
    private int[] lockPlaceNow() {
        View lock = root().findViewWithTag("home-lock"), top = root().findViewWithTag("home-top-area"), name = root().findViewWithTag("home-switch-scooter");
        require(shown(lock) && shown(top), "Clean Home has no Lock bar or top area");
        require(shown(name), "Clean Home shows no scooter name (home-switch-scooter)");
        int[] at = new int[2], named = new int[2]; lock.getLocationInWindow(at); name.getLocationInWindow(named);
        return new int[]{at[0], at[1], lock.getWidth(), lock.getHeight(), top.getHeight(), named[0], named[1]};
    }
    /** A text's baseline in the window (px). Main thread. */
    private static int baselineY(TextView text) { int[] at = new int[2]; text.getLocationInWindow(at); return at[1] + text.getBaseline(); }
    /** A place from lockPlaceNow, named. */
    private static String describe(int[] place) {
        return place == null ? "none" : "Lock bar at " + place[0] + "," + place[1] + " size " + place[2] + "x" + place[3] + ", top area " + place[4]
                + " high, scooter name at " + place[5] + "," + place[6];
    }

    /** lockPlaceNow measured while Clean Home shows moment (a state that holds, such as charging), within 10 s. */
    private int[] placeIn(Object clean, int moment, String name) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            int[] place = placeWhile(clean, moment, 1500);
            if (place != null) return place;
            main(() -> activity.redraw());
        }
        require(false, "Clean Home does not show " + name);
        return null;
    }

    /**
     * lockPlaceNow, measured at an instant when moment is the moment Clean Home shows, its view alone in the top area at
     * full opacity (the crossfade over) and laid out; null when Clean Home turned to another moment first or within
     * timeoutMs. The moment is read in the same main-thread step as the measurement, so a refresh cannot come between.
     */
    private int[] placeWhile(Object clean, int moment, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            waitForIdleSync();
            int[][] place = new int[1][];
            boolean[] gone = new boolean[1];
            main(() -> {
                if (((Integer) field(clean, "moment")) != moment) { gone[0] = true; return; }
                View[] moments = (View[]) field(clean, "moments");
                for (int i = 0; i < moments.length; i++) {
                    View each = moments[i];
                    if (i == moment ? each.getVisibility() != View.VISIBLE || each.getAlpha() < 1f : each.getVisibility() != View.GONE) return;
                }
                View top = root().findViewWithTag("home-top-area"), lock = root().findViewWithTag("home-lock"), name = root().findViewWithTag("home-switch-scooter");
                if (top == null || lock == null || name == null || top.isLayoutRequested() || lock.isLayoutRequested() || name.isLayoutRequested()
                        || root().isLayoutRequested()) return;
                place[0] = lockPlaceNow();
            });
            if (gone[0]) return null;
            if (place[0] != null) return place[0];
            SystemClock.sleep(50);
        }
        return null;
    }

    /** Switches Home style the way Settings › Home style does (only home_style changes); the activity rebuilds Home. */
    private void switchStyle(String style) {
        open(HubPages.HOME);
        require(settings().edit().putString(STYLE_KEY, style).putBoolean(STYLE_CHOSEN, true).commit(), "Home style write failed");
        settle();
    }

    private void recreateActivity() {
        Instrumentation.ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity before = activity;
        main(before::recreate);
        // One wait for the whole time: Instrumentation removes the monitor when a wait ends, even one that timed out, so a
        // second wait on it could never see the new activity.
        Activity next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        require(next instanceof MainActivity && next != before, "The activity was not recreated");
        activity = (MainActivity) next;
        await(() -> activity.service() != null, "The recreated activity has no service");
        settle();
    }

    // ===== Fixture =====

    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence", "onboarding"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create(FIXTURE_NAME, FIXTURE_IDENTITY, 0);
            profile.serial = FIXTURE_SERIAL; profile.mac = FIXTURE_MAC; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private void launch() {
        activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null, "App did not launch"); settle();
    }
    /** The developer simulator's UT5 MAX, connected; its settings now are the ones a command would change. */
    private void simulate() {
        main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
        await(() -> onMain(() -> activity.service().ble.ready()), "The simulated scooter did not connect");
        main(() -> { activity.service().ble.tickSimulation(); simulatedBefore.putAll(activity.service().ble.simulationFeatures()); });
        require(!simulatedBefore.isEmpty(), "The simulator reports no settings");
        simulatedModel = onMain(() -> activity.service().ble.simulationModel());
        require(ROUTED.containsKey(simulatedModel), "ROUTED has no list for the simulated model \"" + simulatedModel + "\"");
        mayRoute = ROUTED.get(simulatedModel);
        main(() -> activity.redraw()); settle();
    }

    private void screenshot(String label) throws Exception { screenshot(label, onMain(this::root)); }
    /** A picture once a frame of window (the decor view of the window it pictures, such as a sheet's) is on screen. */
    private void screenshot(String label, View window) throws Exception {
        awaitFrameShown(window);
        File dir = new File(getTargetContext().getExternalFilesDir(null), "hub-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
    /**
     * Waits until a frame drawn after this call has been committed to the window's surface, then a little more for the
     * system to put it on screen, so a screenshot shows what the views hold now and not the frame before (the emulator's
     * run on 6db305f8 kept a Just back "picture" that still showed the parked card while its census listed Just back).
     */
    private void awaitFrameShown() { awaitFrameShown(onMain(this::root)); }
    /** As awaitFrameShown, for the window whose decor view is window (the activity's, or a dialog's own). */
    private void awaitFrameShown(View window) {
        if (Build.VERSION.SDK_INT < 29) { settle(); return; }
        CountDownLatch committed = new CountDownLatch(1);
        main(() -> { window.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); window.invalidate(); });
        boolean drawn;
        try { drawn = committed.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
        require(drawn, "No frame was drawn within 3 s for a screenshot");
        SystemClock.sleep(150);
    }

    // ===== Plumbing =====

    private View root() { return activity.getWindow().getDecorView(); }
    private static boolean shown(View view) { return view != null && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0; }
    /** The number on the search's shown count line ("12 settings", English), 0 for none or "No matching settings". */
    private static int resultCount(View view) {
        if (view instanceof TextView && view.isShown()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) settings?").matcher(((TextView) view).getText());
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { int found = resultCount(((ViewGroup) view).getChildAt(i)); if (found > 0) return found; }
        return 0;
    }
    private static EditText firstEditText(View view) {
        if (view instanceof EditText && view.isShown()) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { EditText found = firstEditText(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private <T> T onMain(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(task.get()));
        return value.get();
    }
    private void await(BooleanSupplier condition, String message) { await(condition, () -> message); }
    /** As await, with the message built only on failure, so it can describe the state at the timeout. */
    private void await(BooleanSupplier condition, Supplier<String> message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        boolean met = condition.getAsBoolean();
        require(met, met ? "" : message.get());
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void setField(Object target, String name, Object value) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException next) { type = type.getSuperclass(); } }
            throw new NoSuchMethodException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
