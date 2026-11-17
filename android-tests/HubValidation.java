package com.jjricks.volt.tests;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import com.jjricks.volt.DisplayTime;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.HubText;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.PhoneRideStore;
import com.jjricks.volt.RideStore;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;
import com.jjricks.volt.TelemetryHistory;
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
 *   <li><b>Search</b>: the header's search opens the search page with its field focused at the top (the page header
 *   stepped aside, Cancel beside the field) and the subjects listed; typing ("batt", as the plan's mock-up) hides the
 *   subjects and finds at least one result row (pictured with the keyboard gone); "service", "tyre", "tire" and
 *   "reminder" list Service › Maintenance's Reminders, and "maintenance" finds it and opens it over the search, and Back returns to the search with its query and results; the next Back clears the query
 *   (the subjects come back), and the one after returns to Home; Cancel returns to Home at once, with a query typed or
 *   with the field empty and left (the header stays hidden). Main's result check (#410's round) stays: "star" finds the
 *   Star lights page, whose tap opens Lights & sound › Star lights with Search under it, and Back returns to Search with its query
 *   and results kept (this part's search keeps them; main's Search was built afresh, empty).</li>
 *   <li><b>Moments</b>: the Lock bar (and the top area above it) stay at the same place and size while the top area
 *   changes from the battery card to the simulator's charging view and back, and to Just back for a ride saved just then
 *   (its ended line, distance, tiles and route bound by Clean Home's own refresh, the two lines on one line each, the
 *   three tile values at one size on one baseline and their names on one line each; the route panel, where the card's fit
 *   leaves it room, shown wholly inside the window with the route drawn (at least four points), required unless the step
 *   declares -e just_back_route optional, and then reported as not shown only where JustBackFit.split gives it up; the Ride
 *   row under it shows that ride, not "No rides yet"), and
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
 *   1.4x text, a phone dock that holds all seven). The dock is pinned to the bottom, always (the contributor's decision,
 *   14 Nov, #383): shown, with no offset, at HubPages.dockTop (HubTestPages.showDock) and at the same place in the window
 *   as Home opens, with Home scrolled, and after every Back; it never lifts above Widgets' Lock bar or steps aside for it.
 *   Instead Home scrolls the Lock bar wholly clear of the dock where it opens under it (725x604 dp at 1.4x text), and
 *   scrolled to its end, its last card and the Lock bar end above the dock (HubTestPages.requireEndClearOfDock).</li>
 *   <li><b>Page headers</b>: connected, every page but Home shows its back link and its title and no connection line;
 *   Settings never shows one. The walk ends the simulated connection on Battery & charging (the simulator's state only;
 *   nothing reaches a scooter): the line shows there, tappable, and Settings still has none; reconnected on the page, the
 *   line blanks with the title in place, and the page opened again has none.</li>
 *   <li><b>Wide Clean Home</b> (part 13): on a window with room for two panes (MainActivity.twoPaneHome), Clean Home is
 *   the scooter pane (tag hub-scooter-pane) beside the page pane (tag hub-page-pane), whose Home is HubHomePane. The
 *   walk above runs in the page pane (Home's subjects and search field are HubHomePane's; the moments are the scooter
 *   pane's Clean Home's), and every page it opens must leave the scooter pane the same view (never rebuilt by
 *   browsing). Battery & charging › Estimates & charger must hold the usable-energy panel and Settings › Home style
 *   both style previews inside the page pane, no wider than it. With -e wide true, Clean Home must take two panes
 *   every time Home shows where the window gets the wide layout at its text size (WideLayout.wide), and one where large
 *   text keeps the phone layout (725x604 dp at 1.4x, hub-725-large); with -e wide false, one.</li>
 *   <li><b>Taps</b>: every tap first scrolls its view into sight and, on Widgets Home, out from under the pinned dock as a
 *   rider would; the view's centre must then be on screen and, on a page with the dock, above the dock; a view an owner
 *   cannot see is not tapped.</li>
 *   <li><b>No scooter command</b>: the simulator's settings are unchanged at the end, no radio object, queued frame,
 *   pending change or recording exists, and nothing on the walk taps the Lock bar or a quick action.</li>
 * </ul>
 *
 * Pass -e mode all (the default) for all of the above. -e mode widgets-pages checks only how Widgets Home reaches Search
 * and Settings, starting on Widgets: the dock's All sheet lists both and they open over Home (part 11 kept main's header
 * on Widgets Home, at the owner's answer on #392). -e mode no-nearby runs with Nearby devices permission not granted (the
 * runner grants none; ScooterService then never binds, so there is no simulator): every scooter page and sub-page shows
 * the not-connected line, and its tap opens the connection details with "Allow Nearby devices"; Settings shows none
 * (withoutNearby). -e mode search-targets checks that every search result lands on what it names (searchTargets: every
 * page, setting, hand-built row and control search lists, and every row title a page's search words name; #393 council
 * review, second round). -e theme dark|light and -e text_scale (1 by default) as the other
 * fixture runners. -e lock_bar_arrival under|above|below requires where Widgets' Lock bar is, against the dock, as Widgets
 * Home opens at its top. Every
 * word it types or reads is English: run it with the emulator in en-US.
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
    /** The search result the walk opens: "star" finds the Star lights page (HubSearchTables' page words), by its result row's tag (HubSearchScreen.tag). */
    private static final String SEARCH_QUERY = "star", SEARCH_RESULT = "hub-search-page-" + HubPages.LIGHTS_STAR;
    /** -e mode no-nearby: the page headers without Nearby devices permission (the runner grants none). */
    private static final String NO_NEARBY = "no-nearby";
    /** -e mode search-targets: every search result lands on what it names (searchTargets). */
    private static final String SEARCH_TARGETS = "search-targets";
    private boolean noNearby;
    /** ROUTED's list for the simulated model the walk runs on. */
    private List<String> mayRoute = Collections.emptyList();
    private String simulatedModel = "";
    private MainActivity activity;
    private boolean admitted;
    /** The room a widget scrolled clear of the pinned dock keeps above it (HubTestPages.scrollClearOf's). */
    private static final int CLEAR_DP = 16;
    /** Widgets Home's dock's place in the window (x, y, width, height px) as Home opened: it stays there (dockStays). */
    private int[] dockHome;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>(), routed = new ArrayList<>();
    /** The simulator's settings when the walk began: a command would change them. */
    private final TreeMap<String, Integer> simulatedBefore = new TreeMap<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "all");
            require(mode.equals("all") || mode.equals("widgets-pages") || mode.equals(NO_NEARBY) || mode.equals(SEARCH_TARGETS),
                    "Unknown -e mode " + mode + " (all, widgets-pages, " + NO_NEARBY + " or " + SEARCH_TARGETS + ")");
            boolean widgetsFirst = mode.equals("widgets-pages");
            noNearby = mode.equals(NO_NEARBY);
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("auto_record_rides", false).putBoolean("riding_auto_open", false)
                    .putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1")))
                    // A style the owner chose, on a new installation: no What's new sheet covers Home.
                    .putString(STYLE_KEY, widgetsFirst ? WIDGETS : CLEAN).putBoolean(STYLE_CHOSEN, true).putString(STYLE_ORIGIN, "install")
                    .commit(), "Fixture settings failed");
            seed();
            launch();
            if (noNearby) {
                withoutNearby();
                noBind();
                targetWaitsForService();
            } else {
            // Clean Home's subject rows before the simulated scooter connects: Controls and Security have no summary yet.
            if (!widgetsFirst && !mode.equals(SEARCH_TARGETS)) rowsUnknown = evenSubjectRows(null);
            simulate();
            if (widgetsFirst) widgetsPages(true);
            else if (mode.equals(SEARCH_TARGETS)) searchTargets();
            else {
                cleanSubjects();
                quickActionsBothWays();
                pageStack();
                connectionLine();
                search();
                moments();
                widgets();
                edgeScrollbars();
            }
            noCommands();
            }
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("routed_pages", routed); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try {
                main(() -> { if (activity.service() != null) activity.service().setSimulation(false); if (controlDialog() != null) controlDialog().dismiss(); activity.finish(); ScooterService.setUiVisible(false);
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
        if (panesExpected())
            main(() -> require(twoPanes() && shown(root().findViewWithTag("hub-scooter-pane")) && shown(root().findViewWithTag("hub-page-pane")),
                    "Clean Home on a wide window did not take two panes"));
        Object[] pane = scooterPane();
        evenSubjectRows(rowsUnknown);
        screenshot("clean-home");
        for (String subject : HubPages.SUBJECTS) {
            tapTag("home-subject-" + subject);
            arrived(subject, HubPages.HOME, subject);
            main(() -> require(field(activity, "dock") == null, subject + " shows the dock"));
            requireSamePane(pane, subject);
            screenshot("subject-" + subject);
            for (String child : children(subject)) {
                reach(child, Arrays.asList("hub-link-" + child, "hub-row-" + child));
                arrived(child, HubPages.HOME, subject, child);
                requireSamePane(pane, child);
                if (child.equals(HubPages.BATTERY_ESTIMATES)) requireInPage("battery-usable-energy", "The usable-energy panel");
                HubTestPages.back(this, activity);
                arrived(subject, HubPages.HOME, subject);
            }
            systemBack();
            requireHome("CleanHomeScreen");
            requireStack(HubPages.HOME);
        }
        requireSamePane(pane, HubPages.HOME);
        checks.add("Clean Home: each subject row opens its subject over Home; each sub-page opens from it; back links and Back return");
        if (pane[0] != null) checks.add("Wide Clean Home: two panes; every page opens in the page pane and leaves the scooter pane as it was; Estimates & charger holds the usable-energy panel there");
        // Settings behind the gear, and its own rows.
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        screenshot("settings");
        for (String child : children(HubPages.SETTINGS)) {
            String tag = SETTINGS_ROWS.get(child);
            require(tag != null, "HubValidation names no Settings row for " + child + " (SETTINGS_ROWS)");
            reach(child, Collections.singletonList(tag));
            arrived(child, HubPages.HOME, HubPages.SETTINGS, child);
            requireSamePane(pane, child);
            if (child.equals(HubPages.SETTINGS_HOME)) {
                requireInPage("home-style-clean", "Home style's Clean preview");
                requireInPage("home-style-widgets", "Home style's Widgets preview");
            }
            systemBack();
            arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        }
        if (pane[0] != null) checks.add("Wide Clean Home: Settings › Home style shows both style previews inside the page pane, no wider than it");
        HubTestPages.back(this, activity);
        requireHome("CleanHomeScreen");
        checks.add("Clean Home's gear opens Settings over Home; Settings' rows open its pages; Back returns to Settings, its back link to Home");
        for (String page : mayRoute) require(routed.contains(page), page + " is listed as routed for model \"" + simulatedModel + "\" but the walk never reached it");
        checks.add("Routed pages: " + routed + ", exactly the ones the simulated model hides (" + mayRoute + ")");
    }

    /**
     * Wide Clean Home: quick actions edited either way reach the scooter pane, and neither edit undoes the other (#394
     * council review, second round, 1). Settings › Home style › Quick actions changes the fourth slot from the page pane:
     * the scooter pane shows it, still the same pane (never rebuilt), on Settings and back on Home. With Home style still
     * open, the scooter pane's Edit sheet (a long-press) changes the second: the scooter pane and Home style's Clean
     * preview show it (#394 council review, third round, P3). Then Home's own Edit
     * sheet (a long-press on the quick actions) changes the third: the scooter pane shows all three, and they are stored. The
     * four as stored before are put back at the end, and the scooter pane follows that too. Nothing taps a quick action.
     */
    private void quickActionsBothWays() throws Exception {
        Object[] pane = scooterPane();
        if (pane[0] == null) return;
        String key = onMain(() -> (String) staticCall(appClass("HomeQuickActions"), "key", new Class<?>[]{String.class},
                activity.service().profiles.active().id));
        String storedBefore = settings().getString(key, null);
        List<String> before = paneQuick();
        require(before.size() == 4, "The scooter pane does not show four quick actions: " + before);
        // From Settings › Home style, in the page pane: the fourth slot.
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        reach(HubPages.SETTINGS_HOME, Collections.singletonList(SETTINGS_ROWS.get(HubPages.SETTINGS_HOME)));
        arrived(HubPages.SETTINGS_HOME, HubPages.HOME, HubPages.SETTINGS, HubPages.SETTINGS_HOME);
        tapTag("home-style-quick-actions");
        Supplier<View> slots = () -> sheetRoot(field(activity, "screen"), "slotsSheet");
        await(() -> onMain(() -> slots.get() != null), "Home style's Quick actions did not open the four");
        tapIn(slots, "home-edit-slot-4", "Home style's fourth quick action");
        Supplier<View> settingsPicker = () -> pickerRoot(field(field(activity, "screen"), "chooser"));
        String fourth = pickOther(settingsPicker, before, "Home style's picker");
        List<String> afterSettings = new ArrayList<>(before);
        afterSettings.set(3, fourth);
        await(() -> onMain(() -> paneQuick().equals(afterSettings)), () -> "The scooter pane shows " + onMain(this::paneQuick)
                + " after Home style changed the fourth quick action to " + fourth + " (expected " + afterSettings + ")");
        requireSamePane(pane, "Home style's quick-action edit");
        main(() -> require(stylePreviewQuick().equals(afterSettings), "Home style's Clean preview shows " + stylePreviewQuick() + " after its own edit, not " + afterSettings));
        // Home style still open beside it, from the scooter pane's own Edit sheet (a long-press): the second slot. The
        // page isn't rebuilt (the scooter pane updates in place), so its Clean preview must take the stored four on its
        // refresh (#394 council review, third round, P3), as its Quick actions line does.
        main(() -> {
            ViewGroup grid = root().findViewWithTag("hub-scooter-pane").findViewWithTag("home-quick-actions");
            require(grid != null && grid.getChildCount() > 0 && grid.getChildAt(0).performLongClick(), "A long-press on the scooter pane's quick actions did not open Edit beside Home style");
        });
        settle();
        Supplier<View> besideEdit = () -> sheetRoot(field(activity, "homeScreen"), "editSheet");
        await(() -> onMain(() -> besideEdit.get() != null), "The scooter pane's Edit sheet did not open beside Home style");
        tapIn(besideEdit, "home-edit-slot-2", "Edit's second quick action beside Home style");
        String second = pickOther(() -> pickerRoot(field(field(activity, "homeScreen"), "parts")), afterSettings, "Home's picker beside Home style");
        List<String> afterBeside = new ArrayList<>(afterSettings);
        afterBeside.set(1, second);
        await(() -> onMain(() -> paneQuick().equals(afterBeside)), () -> "The scooter pane shows " + onMain(this::paneQuick)
                + " after its Edit sheet changed the second quick action to " + second + " beside Home style (expected " + afterBeside + ")");
        main(() -> require(HubPages.SETTINGS_HOME.equals(field(activity, "selectedPage")), "The page pane left Home style for " + field(activity, "selectedPage") + " after the scooter pane's edit"));
        await(() -> onMain(() -> stylePreviewQuick().equals(afterBeside)), () -> "Home style's Clean preview shows " + onMain(this::stylePreviewQuick)
                + " after the scooter pane's Edit sheet changed the second quick action to " + second + " (expected " + afterBeside + ")");
        requireSamePane(pane, "the scooter pane's quick-action edit beside Home style");
        screenshot("home-style-preview-after-pane-edit");
        HubTestPages.back(this, activity);
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        HubTestPages.back(this, activity);
        requireHome("CleanHomeScreen");
        main(() -> require(paneQuick().equals(afterBeside), "Back on Home the scooter pane shows " + paneQuick() + ", not " + afterBeside));
        requireSamePane(pane, HubPages.HOME);
        screenshot("home-after-settings-quick-edit");
        // From Home's own Edit sheet: the third slot. It starts from the four as stored, so the fourth and second stay.
        main(() -> {
            ViewGroup grid = root().findViewWithTag("home-quick-actions");
            require(grid != null && grid.getChildCount() > 0 && grid.getChildAt(0).performLongClick(), "A long-press on Home's quick actions did not open Edit");
        });
        settle();
        Supplier<View> edit = () -> sheetRoot(field(activity, "homeScreen"), "editSheet");
        await(() -> onMain(() -> edit.get() != null), "Home's Edit sheet did not open");
        tapIn(edit, "home-edit-slot-3", "Edit's third quick action");
        Supplier<View> homePicker = () -> pickerRoot(field(field(activity, "homeScreen"), "parts"));
        String third = pickOther(homePicker, afterBeside, "Home's picker");
        List<String> both = new ArrayList<>(afterBeside);
        both.set(2, third);
        await(() -> onMain(() -> paneQuick().equals(both)), () -> "The scooter pane shows " + onMain(this::paneQuick)
                + " after Home's Edit changed the third quick action to " + third + " (expected " + both + ": the earlier changes kept)");
        main(() -> require(storedQuick().equals(both), "Stored quick actions are " + storedQuick() + ", expected both changes " + both));
        requireSamePane(pane, "Home's quick-action edit");
        requireHome("CleanHomeScreen");
        // Put the four back as they were stored; the scooter pane follows that as well.
        SharedPreferences.Editor restore = settings().edit();
        require((storedBefore == null ? restore.remove(key) : restore.putString(key, storedBefore)).commit(), "Restoring the quick actions failed");
        await(() -> onMain(() -> paneQuick().equals(before)), () -> "The scooter pane shows " + onMain(this::paneQuick) + " after the four were put back to " + before);
        requireSamePane(pane, HubPages.HOME);
        checks.add("Wide Clean Home: a quick action changed in Settings › Home style shows in the scooter pane (not rebuilt) on Settings and back on Home; one changed from the scooter pane's Edit sheet beside Home style shows in the scooter pane and in Home style's Clean preview at once; one changed then from Home's Edit sheet keeps the earlier changes, and all are stored");
    }

    /** The ids Home style's Clean preview draws as quick actions (HomeStylePreview's quick list), on the page shown. Main thread. */
    private List<String> stylePreviewQuick() {
        List<View> found = new ArrayList<>();
        collectClass(root().findViewWithTag("hub-page-pane"), "HomeStylePreview", found);
        List<String> ids = new ArrayList<>();
        for (View preview : found) {
            if (!Boolean.TRUE.equals(field(preview, "clean"))) continue;
            for (Object action : (List<?>) field(preview, "quick")) ids.add(((String[]) action)[1]);
            return ids;
        }
        require(false, "Home style shows no Clean preview in the page pane");
        return ids;
    }
    private static void collectClass(View view, String simpleName, List<View> into) {
        if (view == null) return;
        if (view.getClass().getSimpleName().equals(simpleName)) into.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectClass(((ViewGroup) view).getChildAt(i), simpleName, into);
    }

    /** The ids of the quick actions the scooter pane shows (its grid's cells), in order. */
    private List<String> paneQuick() {
        ViewGroup grid = root().findViewWithTag("hub-scooter-pane").findViewWithTag("home-quick-actions");
        List<String> ids = new ArrayList<>();
        for (int i = 0; grid != null && i < grid.getChildCount(); i++) {
            Object entry = fieldOrNull(grid.getChildAt(i), "entry");
            if (entry != null) ids.add((String) field(entry, "id"));
        }
        return ids;
    }
    /** The active scooter's four as stored (HomeQuickEditor.load). */
    @SuppressWarnings("unchecked")
    private List<String> storedQuick() {
        return (List<String>) staticCall(appClass("HomeQuickEditor"), "load",
                new Class<?>[]{ScooterService.class, SharedPreferences.class}, activity.service(), settings());
    }
    /** A package-private class of the app's, by its simple name. */
    private Class<?> appClass(String name) {
        try { return Class.forName("com.jjricks.volt." + name, true, activity.getClassLoader()); } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
    }
    /** The window of the sheet in owner's field, while it shows; else null. */
    private static View sheetRoot(Object owner, String name) {
        Object sheet = owner == null ? null : fieldIn(owner, name);
        return sheet instanceof Dialog && ((Dialog) sheet).isShowing() ? ((Dialog) sheet).getWindow().getDecorView() : null;
    }
    /** The quick-action picker's window: a DashboardScreen part's (QuickChooser, CleanParts) outer screen's sheet. */
    private static View pickerRoot(Object part) {
        return part == null ? null : sheetRoot(fieldIn(part, "this$0"), "dialog");
    }
    /** Taps the first shown, enabled choice of the picker whose id is not in avoid; its id. */
    private String pickOther(Supplier<View> picker, List<String> avoid, String label) {
        await(() -> onMain(() -> picker.get() != null), label + " did not open");
        String id = onMain(() -> {
            List<View> rows = new ArrayList<>();
            collectTagged(picker.get(), "quick-choice:", rows);
            for (View row : rows) {
                String choice = ((String) row.getTag()).substring("quick-choice:".length());
                if (shown(row) && row.isEnabled() && !avoid.contains(choice)) return choice;
            }
            return null;
        });
        require(id != null, label + " offers no other choice than " + avoid);
        tapIn(picker, "quick-choice:" + id, label + "'s " + id);
        await(() -> onMain(() -> picker.get() == null), label + " stayed open after a choice");
        settle();
        return id;
    }
    private static void collectTagged(View view, String prefix, List<View> into) {
        if (view == null) return;
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix)) into.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectTagged(((ViewGroup) view).getChildAt(i), prefix, into);
    }
    private static Object fieldIn(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch (NoSuchFieldException next) { /* the superclass may declare it */ }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        return null;
    }

    /**
     * Two panes while disconnected (#394 council review, second round): the page header's connection line and Clean's
     * status line beside it give the same "last seen", the newer of the last connection and the newest reading (Clean's
     * rule). The last connection is saved at most once a minute; here it is put ten minutes before the newest reading,
     * in memory only and put back after, so the two rules would show different times.
     */
    private void sameLastSeen() {
        if (!onMain(this::twoPanes)) return;
        ScooterProfiles.Profile active = onMain(() -> activity.service().profiles.active());
        long saved = active.lastConnected;
        long newest = onMain(() -> {
            long found = 0;
            for (TelemetryHistory.Reading r : activity.service().displayReadings().values())
                if (r != null && r.value != null) found = Math.max(found, r.capturedWallMs);
            return found;
        });
        require(newest > 0, "The disconnected simulator kept no reading to be last seen by");
        main(() -> { active.lastConnected = newest - 10 * 60_000L; activity.redraw(); });
        try {
            String expected = onMain(() -> DisplayTime.dateTime(activity, settings(), newest));
            String[] read = new String[2];
            await(() -> onMain(() -> {
                read[0] = String.valueOf(root().findViewWithTag("hub-page-connection").getContentDescription());
                read[1] = String.valueOf(((TextView) invoke(field(field(activity, "homeScreen"), "parts"), "status", new Class<?>[0])).getContentDescription());
                return read[0].contains(expected) && read[1].contains(expected);
            }), () -> "Two panes disconnected: the page header says \"" + read[0] + "\" and Clean's status line \"" + read[1] + "\"; both should say last seen " + expected);
        } finally {
            main(() -> active.lastConnected = saved);
        }
        checks.add("Wide Clean Home, disconnected: the page header's connection line and Clean's status line give the same last-seen time (the newest reading's, newer than the last connection)");
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
     * The page header's connection line follows the connection (the contributor's decision, 14 Nov, #383): the walk ends
     * the simulated connection on Battery & charging (BleController.disconnect, which in simulation only sets the state:
     * no radio, no command) and the line shows there, tappable, between the back link and the title; Settings still shows
     * none. Reconnected on that page (BleController.connectLast, the simulator's own connect), the line goes with its
     * room and, once the motion settles, the title is where a connected page opened fresh has it (the owner's motion
     * sweep pick, 15 Nov, in place of the blank room #383 kept); opened again, connected, the page has no line. The
     * simulator's settings stay as they were (noCommands checks them at the end).
     */
    private void connectionLine() throws Exception {
        requireHome("CleanHomeScreen");
        tapTag("home-subject-" + HubPages.BATTERY);
        arrived(HubPages.BATTERY, HubPages.HOME, HubPages.BATTERY);
        BleController ble = activity.service().ble;
        main(ble::disconnect);
        await(() -> onMain(() -> !ble.ready() && shown(root().findViewWithTag("hub-page-connection"))), "Battery's header shows no connection line once the scooter disconnected");
        settle();
        int titleTop = requireHeader(HubPages.BATTERY, true);
        // On two panes the line is the page pane's own (part 13): it sits in the pane's header, not over the scooter pane.
        requireInPage("hub-page-connection", "Battery's connection line");
        String said = onMain(() -> ((TextView) root().findViewWithTag("hub-page-connection")).getText().toString());
        require(said.startsWith("Disconnected"), "Battery's connection line reads \"" + said + "\" while disconnected");
        sameLastSeen();
        screenshot("page-disconnected");
        systemBack();
        requireHome("CleanHomeScreen");
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        requireHeader(HubPages.SETTINGS, false);
        HubTestPages.back(this, activity);
        requireHome("CleanHomeScreen");
        tapTag("home-subject-" + HubPages.BATTERY);
        arrived(HubPages.BATTERY, HubPages.HOME, HubPages.BATTERY);
        require(requireHeader(HubPages.BATTERY, true) == titleTop, "Battery's title moved between two disconnected visits");
        main(ble::connectLast);
        await(() -> onMain(() -> ble.ready() && root().findViewWithTag("hub-page-connection").getVisibility() == View.GONE),
                () -> "Reconnected on Battery, its connection line is " + onMain(() -> root().findViewWithTag("hub-page-connection").getVisibility()) + ", not gone");
        settle();
        int reconnectedTop = requireHeader(HubPages.BATTERY, false);
        require(reconnectedTop < titleTop, "Battery's title stayed at " + reconnectedTop + " px (" + titleTop + " with the line) as the scooter reconnected");
        systemBack();
        requireHome("CleanHomeScreen");
        tapTag("home-subject-" + HubPages.BATTERY);
        arrived(HubPages.BATTERY, HubPages.HOME, HubPages.BATTERY);
        int freshTop = requireHeader(HubPages.BATTERY, false);
        require(reconnectedTop == freshTop, "Reconnected on Battery its title settled at " + reconnectedTop + " px, opened connected at " + freshTop + " px");
        systemBack();
        requireHome("CleanHomeScreen");
        checks.add("Page header: connected, every page shows its back link and title and no connection line, Settings never; disconnected (the simulator's state only), Battery shows"
                + " \"" + said + "\" between them, tappable, and Settings none; reconnected on the page the line goes and the title settles where a connected page has it, and the page opened again has none");
    }

    /**
     * -e mode no-nearby (#410 review): without Nearby devices permission ScooterService never binds (MainActivity's
     * ensureService), so the scooter is not connected: every subject page and every sub-page shows the connection line
     * between its back link and its title (HubPages.pageConnectionLine), and Settings and its pages show none. Search
     * shows neither: with Cancel beside its field its whole page header steps aside (HubSearch.headerHidden), the line
     * with it, and Cancel returns Home. The
     * subjects open from Home's rows; their sub-pages open by the page stack's route (without the service a hosted page
     * holds no rows; the full walk checks the rows). On Battery & charging and on its first sub-page the line's tap opens
     * the connection details, whose state reads "No permission" and whose button is "Allow Nearby devices" (pictured, not
     * tapped: it would ask for the permission). That sub-page rebuilt by an activity recreation (as a rotation makes) shows
     * the line again at once, as no bind is coming. Nothing binds the service (noBind).
     */
    private void withoutNearby() throws Exception {
        screenshot("no-nearby-home");
        String subPage = children(HubPages.BATTERY).get(0);
        for (String subject : HubPages.SUBJECTS) {
            tapTag("home-subject-" + subject);
            arrived(subject, HubPages.HOME, subject);
            requireHeader(subject, true);
            if (subject.equals(HubPages.BATTERY)) {
                screenshot("no-nearby-battery");
                connectionDetails(subject, "no-nearby-battery-details");
            }
            for (String child : children(subject)) {
                open(child);
                arrived(child, HubPages.HOME, subject, child);
                requireHeader(child, true);
                if (child.equals(subPage)) {
                    screenshot("no-nearby-" + child);
                    connectionDetails(child, "no-nearby-" + child + "-details");
                    recreateActivity();
                    arrived(child, HubPages.HOME, subject, child);
                    requireHeader(child, true);
                    screenshot("no-nearby-" + child + "-recreated");
                }
                systemBack();
                arrived(subject, HubPages.HOME, subject);
                requireHeader(subject, true);
            }
            systemBack();
            requireHome("CleanHomeScreen");
        }
        // Search (part 12): its page header steps aside for the field and Cancel (HubSearch.headerHidden, always with
        // Cancel), the connection line with it; Cancel is the one way out, and goes Home.
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        hideKeyboard();
        main(() -> {
            require(!shown(root().findViewWithTag("hub-back")) && !shown(root().findViewWithTag("hub-page-connection")),
                    "Search without Nearby permission shows its page header or the connection line with Cancel beside its field");
            require(shown(root().findViewWithTag("hub-search-cancel")), "Search without Nearby permission has no Cancel beside its field");
        });
        screenshot("no-nearby-search");
        tapTag("hub-search-cancel");
        requireHome("CleanHomeScreen");
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        requireHeader(HubPages.SETTINGS, false);
        for (String child : children(HubPages.SETTINGS)) {
            open(child);
            arrived(child, HubPages.HOME, HubPages.SETTINGS, child);
            requireHeader(child, false);
            systemBack();
            arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        }
        screenshot("no-nearby-settings");
        HubTestPages.back(this, activity);
        requireHome("CleanHomeScreen");
        checks.add("Without Nearby devices permission (none granted; the service never binds): every subject and every sub-page shows the connection line"
                + " between its back link and title (Search's header steps aside for its field and Cancel, the line with it); on Battery & charging and " + subPage + " its tap opens the connection details (\"No permission\", \"Allow Nearby devices\");"
                + " " + subPage + " rebuilt by a recreation shows it at once; Settings and its pages show none");
    }

    /**
     * Taps the page's connection line and requires the connection details it opens to say the permission is missing and
     * offer "Allow Nearby devices" (the button is not tapped); pictured as shot, then closed.
     */
    private void connectionDetails(String page, String shot) throws Exception {
        main(() -> click(root().findViewWithTag("hub-page-connection"), page + "'s connection line"));
        AlertDialog[] dialog = new AlertDialog[1];
        await(() -> onMain(() -> { dialog[0] = controlDialog(); return dialog[0] != null && dialog[0].isShowing(); }), page + "'s connection line did not open the connection details");
        settle();
        main(() -> {
            View content = dialog[0].getWindow().getDecorView();
            require(hasText(content, "No permission"), "The connection details from " + page + " do not say \"No permission\"");
            android.widget.Button allow = dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE);
            require(shown(allow) && "Allow Nearby devices".equalsIgnoreCase(allow.getText().toString()), "The connection details from " + page + " offer \""
                    + (allow == null ? null : allow.getText()) + "\", not \"Allow Nearby devices\"");
        });
        screenshot(shot, onMain(() -> dialog[0].getWindow().getDecorView()));
        main(() -> dialog[0].dismiss());
        await(() -> onMain(() -> !dialog[0].isShowing()), "The connection details did not close");
        settle();
    }

    /** MainActivity's connection details dialog (its controlDialog), or null. Main thread. */
    private AlertDialog controlDialog() { return activity == null ? null : (AlertDialog) field(activity, "controlDialog"); }

    /** Without Nearby permission nothing bound the service, so nothing could reach a scooter or start a recording. */
    private void noBind() {
        main(() -> {
            require(activity.service() == null, "ScooterService bound without Nearby permission");
            require(field(activity, "riding") == null, "Riding mode opened during the walk");
        });
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED, "The walk granted " + permission);
        checks.add("No scooter work: the service never bound, Nearby permission is still not granted, no riding mode");
    }

    /**
     * Without Nearby permission Settings is the loading page until the scooter service binds, and a search result's
     * target waits for the real page (#393 council review, third round, finding 4: the loading page used it up, so the
     * Updates sheet never opened). Check for updates' result opens Settings as the loading page with its target still
     * owed (MainActivity.pageArgs), through a rebuild too; then, Nearby devices granted and the loading page's Allow
     * pressed (requestBluetooth binds once granted), Settings is built and its Updates sheet opens holding Check for
     * updates. Runs after noBind, which this ends: the service binds here.
     */
    private void targetWaitsForService() throws Exception {
        String anchor = "check-updates", tag = "hub-search-setting-" + anchor;
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        hideKeyboard();
        require(typeAndFind("Check for updates", tag), "Search without Nearby permission lists no Check for updates result ("
                + onMain(() -> resultRows(root().findViewWithTag("hub-search-results"))) + " result rows)");
        tapTag(tag);
        await(() -> onMain(() -> HubPages.SETTINGS.equals(field(activity, "selectedPage")) && !Boolean.TRUE.equals(field(activity, "rebuilding"))),
                () -> "Check for updates' result opened " + onMain(() -> field(activity, "selectedPage")) + ", not Settings");
        settle();
        for (String when : new String[] {"as Settings opened", "after a rebuild"}) {
            if (when.startsWith("after")) { main(() -> activity.rebuild()); settle(); }
            main(() -> {
                String built = field(activity, "screen").getClass().getSimpleName();
                require(built.equals("LoadingScreen"), "Without Nearby permission Settings was built as " + built + ", not the loading page (" + when + ")");
                Object owed = field(activity, "pageArgs");
                require(owed != null && anchor.equals(field(owed, "anchor")), "The loading page used up Check for updates' target " + when
                        + " (owed: " + (owed == null ? "nothing" : field(owed, "anchor")) + ")");
            });
        }
        screenshot("no-nearby-settings-loading");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(), permission);
        main(() -> activity.requestBluetooth());
        await(() -> onMain(() -> activity.service() != null && field(activity, "screen").getClass().getSimpleName().equals("MoreScreen")),
                () -> "With Nearby granted, Settings was not built (" + onMain(() -> field(activity, "screen").getClass().getSimpleName()) + ", service "
                        + onMain(() -> activity.service() != null) + ")");
        String[] place = {"not checked", null};
        await(() -> { settle(); String[] now = onMain(() -> placeOf(anchor)); place[0] = now[0]; place[1] = now[1]; return now[0] == null && "sheet".equals(now[1]); },
                () -> "With Nearby granted, Settings did not open its Updates sheet with Check for updates in it (" + place[0] + ", " + place[1] + ")");
        main(() -> require(field(activity, "pageArgs") == null, "Settings, built, left Check for updates' target owed"));
        screenshot("no-nearby-settings-updates");
        checks.add("Without Nearby permission, Check for updates' result opens Settings as the loading page with its target kept through a rebuild;"
                + " once Nearby is granted and the service binds, Settings opens its Updates sheet holding Check for updates");
    }

    /**
     * -e mode search-targets (#393 council review, second round: three results opened a page that no longer held what
     * was searched for, after pages were rebuilt): every search result lands on what it names. The entries come from
     * search's own sources (HubSearch.pages, settings, rows and controls, read by reflection with MainActivity's own
     * gates), so an entry added later is covered as it is added. For each one the walk types the name it shows, taps its
     * result, and requires its page on top and, for an entry naming a row, that row shown and inside the window once
     * revealed; for a control a sheet over the page holds (Settings' Updates sheet: Automatic update checks and Check for
     * updates), the sheet open with the control shown in it. Then every row title a page's search words name
     * (HubSearchTables.pageRows): it types the title, opens that page's result and requires a row reading the title on
     * the page. Back returns to Search after each (closing a sheet first). Every mismatch is collected, and the step
     * fails listing them all. A setting search leaves out for a page of the same name (HubSearch.search) is checked
     * through that page's result.
     */
    private void searchTargets() throws Exception {
        requireHome("CleanHomeScreen");
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        hideKeyboard();
        List<String> problems = new ArrayList<>(), notes = new ArrayList<>();
        int checked = 0, anchored = 0, held = 0;
        for (Object entry : onMain(this::searchEntries)) {
            String kind = String.valueOf(field(entry, "kind")), page = (String) field(entry, "page"), anchor = (String) field(entry, "anchor");
            String name = onMain(() -> String.valueOf(field(entry, "shown")));
            String what = kind.toLowerCase(Locale.ROOT) + " \"" + name + "\" (" + page + (anchor == null ? "" : ", " + anchor) + ")";
            String tag = "hub-search-" + kind.toLowerCase(Locale.ROOT) + "-" + (anchor != null ? anchor : page);
            if (!typeAndFind(name, tag)) {
                String instead = "hub-search-page-" + samePage(name);
                if (!"SETTING".equals(kind) || samePage(name) == null || !onMain(() -> shown(root().findViewWithTag(instead)))) {
                    problems.add(what + ": typing its name lists no result for it (" + onMain(() -> resultRows(root().findViewWithTag("hub-search-results"))) + " result rows)");
                    continue;
                }
                notes.add(what + " is listed as the page " + samePage(name) + " of the same name");
                continue;
            }
            String problem = openResult(tag, page);
            if (problem == null && anchor != null) {
                String[] place = onMain(() -> placeOf(anchor));
                problem = place[0];
                if (problem == null) { anchored++; if ("sheet".equals(place[1])) held++; }
            }
            if (problem != null) problems.add(what + ": " + problem);
            else checked++;
            backToSearch();
        }
        int titles = 0;
        for (String page : HubPages.ALL) {
            for (String title : pageRows(page)) {
                String tag = "hub-search-page-" + page, what = "\"" + title + "\" (a row title in " + page + "'s search words)";
                if (!typeAndFind(title, tag)) {
                    if (onMain(() -> !pageListed(page))) notes.add(what + ": " + page + " is not offered on this model");
                    else problems.add(what + ": typing it does not list " + page);
                    continue;
                }
                String problem = openResult(tag, page);
                if (problem == null && !onMain(() -> hasFoldedText(((View) invoke(field(activity, "screen"), "view", new Class<?>[0])), fold(title))))
                    problem = page + " has no row reading \"" + title + "\"";
                if (problem != null) problems.add(what + ": " + problem);
                else titles++;
                backToSearch();
            }
        }
        checks.add("Search targets: " + checked + " results opened their page" + " (" + anchored + " with their row revealed inside the window, " + held
                + " of them in the sheet that holds them), " + titles + " row titles found on the page their words open" + (notes.isEmpty() ? "" : "; notes: " + notes));
        try { readingKept(); }
        catch (RuntimeException failure) { problems.add("a reading result: " + failure.getMessage()); }
        require(problems.isEmpty(), problems.size() + " search results do not land on what they name:\n  " + String.join("\n  ", problems));
    }

    /**
     * A reading opened from Search keeps Readings filtered to it through a rebuild (as a rotation or an unfold makes,
     * MainActivity.onConfigurationChanged) and an activity recreation while Data stays open; once the owner types their
     * own search on Readings, a recreation restores that search, not the reading (third round, finding 3); and the next
     * ordinary visit to Data shows every reading (#393 council review, second round, finding 6; the first review's finding 4).
     */
    private void readingKept() throws Exception {
        String[] key = new String[1];
        main(() -> { EditText field = firstEditText(activity.findViewById(android.R.id.content)); field.setText("battery"); });
        settle();
        main(() -> {
            View found = firstTagged(root().findViewWithTag("hub-search-results"), "hub-search-reading-");
            require(found != null, "Search for \"battery\" lists no reading");
            key[0] = ((String) found.getTag()).substring("hub-search-reading-".length());
        });
        tapTag("hub-search-reading-" + key[0]);
        arrived(HubPages.DATA, HubPages.HOME, HubPages.SEARCH, HubPages.DATA);
        requirePinned(key[0], "opened from Search");
        main(() -> activity.rebuild()); settle();
        requirePinned(key[0], "after a rebuild (as a rotation or an unfold makes)");
        recreateActivity();
        arrived(HubPages.DATA, HubPages.HOME, HubPages.SEARCH, HubPages.DATA);
        requirePinned(key[0], "after an activity recreation");
        screenshot("search-reading-recreated");
        // The owner types their own search on Readings: the reading is let go, and a recreation now restores their search,
        // not the reading (#393 council review, third round, finding 3: the reading saved at the last rebuild came back).
        main(() -> ((EditText) field(field(activity, "screen"), "search")).setText("volt"));
        settle();
        requirePinned(null, "once the owner typed their own search");
        recreateActivity();
        arrived(HubPages.DATA, HubPages.HOME, HubPages.SEARCH, HubPages.DATA);
        requirePinned(null, "after the owner typed their own search and a recreation");
        String[] typed = {null};
        await(() -> onMain(() -> "volt".contentEquals(typed[0] = String.valueOf(((EditText) field(field(activity, "screen"), "search")).getText()))),
                () -> "After the owner typed \"volt\" on Readings and a recreation, its field reads \"" + typed[0] + "\"");
        screenshot("search-reading-typed-recreated");
        // The owner's own search is kept in stats_query: clear it so later steps see Readings unfiltered.
        main(() -> ((EditText) field(field(activity, "screen"), "search")).setText(""));
        settle();
        backToSearch();
        tapTag("hub-search-cancel");
        requireHome("CleanHomeScreen");
        tapTag("home-subject-" + HubPages.DATA);
        arrived(HubPages.DATA, HubPages.HOME, HubPages.DATA);
        requirePinned(null, "on the next ordinary visit");
        systemBack();
        requireHome("CleanHomeScreen");
        checks.add("A reading opened from Search (" + key[0] + ") keeps Readings filtered to it through a rebuild and a recreation; once the owner types \"volt\" there a recreation restores their search, not the reading; the next ordinary visit shows every reading");
    }

    /** Data's Readings shows reading by itself (StatsScreen's pinned reading), or none for null. */
    private void requirePinned(String reading, String when) {
        await(() -> onMain(() -> field(activity, "screen").getClass().getSimpleName().equals("StatsScreen")), "Data's Readings did not open " + when);
        Object pinned = onMain(() -> field(field(activity, "screen"), "pinned"));
        require(reading == null ? pinned == null : reading.equals(pinned), "Readings " + when + " shows " + (pinned == null ? "every reading" : "only " + pinned)
                + ", not " + (reading == null ? "every reading" : "only " + reading));
    }

    /** A shown view under view with this tag, or null. Main thread. */
    private static View shownTagged(View view, String tag) {
        if (view == null) return null;
        if (tag.equals(view.getTag()) && view.isShown()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = shownTagged(((ViewGroup) view).getChildAt(i), tag); if (found != null) return found; }
        return null;
    }

    /** The first shown view under view whose tag starts with prefix, or null. Main thread. */
    private static View firstTagged(View view, String prefix) {
        if (view == null || !view.isShown()) return null;
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = firstTagged(((ViewGroup) view).getChildAt(i), prefix); if (found != null) return found; }
        return null;
    }

    /** Search's entries for pages, settings, hand-built rows and controls (those that open a page), as search builds them now. Main thread. */
    private List<Object> searchEntries() {
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> search = Class.forName("com.jjricks.volt.HubSearch", true, loader), gatesType = Class.forName("com.jjricks.volt.HubSearch$Gates", true, loader);
            Object gates = invoke(activity, "searchGates", new Class<?>[0]);
            List<Object> all = new ArrayList<>();
            all.addAll((List<?>) staticCall(search, "pages", new Class<?>[]{gatesType}, gates));
            Object settings;
            try { settings = staticCall(search, "settings", new Class<?>[]{gatesType}, gates); }
            catch (IllegalStateException older) { settings = staticCall(search, "settings", new Class<?>[0]); }
            all.addAll((List<?>) settings);
            all.addAll((List<?>) staticCall(search, "rows", new Class<?>[]{gatesType}, gates));
            Supplier<CharSequence> badge = () -> "Experimental";
            all.addAll((List<?>) staticCall(search, "controls", new Class<?>[]{gatesType, Supplier.class}, gates, badge));
            List<Object> opening = new ArrayList<>();
            for (Object entry : all) if ("PAGE".equals(String.valueOf(field(entry, "target")))) opening.add(entry);
            return opening;
        } catch (ClassNotFoundException missing) { throw new IllegalStateException(missing); }
    }

    /** HubSearchTables.pageRows(page) (the row titles a page's words name); none where the table has no such list. */
    private String[] pageRows(String page) {
        try { return (String[]) staticCall(Class.forName("com.jjricks.volt.HubSearchTables", true, activity.getClassLoader()), "pageRows", new Class<?>[]{String.class}, page); }
        catch (ClassNotFoundException missing) { throw new IllegalStateException(missing); }
    }

    /** Whether search lists page as a page result on this model (HubSearch.pages with MainActivity's gates). Main thread. */
    private boolean pageListed(String page) {
        for (Object entry : searchEntries()) if ("PAGE".equals(String.valueOf(field(entry, "kind"))) && page.equals(field(entry, "page"))) return true;
        return false;
    }

    /** The page whose title is name (folded), or null: a setting of that name is listed as that page (HubSearch.search). Main thread. */
    private String samePage(String name) {
        return onMain(() -> {
            for (Object entry : searchEntries())
                if ("PAGE".equals(String.valueOf(field(entry, "kind"))) && fold(String.valueOf(field(entry, "shown"))).equals(fold(name))) return (String) field(entry, "page");
            return null;
        });
    }

    /** Types text into Search's field and returns whether the result row with this tag is shown. */
    private boolean typeAndFind(String text, String tag) {
        main(() -> { EditText field = firstEditText(activity.findViewById(android.R.id.content)); require(field != null, "Search has no field"); field.setText(text); });
        settle();
        return onMain(() -> shown(root().findViewWithTag(tag)));
    }

    /** Taps the result with this tag; null once page is on top, else what went wrong. */
    private String openResult(String tag, String page) {
        try { tapTag(tag); }
        catch (RuntimeException failure) { return "its result could not be tapped (" + failure.getMessage() + ")"; }
        long end = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < end && !onMain(() -> page.equals(field(activity, "selectedPage")) && !Boolean.TRUE.equals(field(activity, "rebuilding"))))
            SystemClock.sleep(50);
        String now = onMain(() -> (String) field(activity, "selectedPage"));
        if (!page.equals(now)) return "opened " + now + ", not " + page;
        settle();
        return null;
    }

    /**
     * Where the row named by anchor is once the page revealed it: {problem or null, "page" or "sheet"}. In a sheet one of
     * the page's parts shows over it (its "dialogs"), the control must be shown and wholly inside the sheet's window;
     * on the page, the row the page names for it (AppScreen.anchor) or the view with that tag must be shown and inside
     * the window (at least 40 dp of it, or all of a shorter row). Main thread.
     */
    private String[] placeOf(String anchor) {
        Object screen = field(activity, "screen");
        List<Object> parts = new ArrayList<>();
        parts.add(screen);
        if (screen.getClass().getSimpleName().equals("HubPageScreen")) parts.addAll((List<?>) field(screen, "parts"));
        for (Object part : parts) {
            Object dialogs = fieldOrNull(part, "dialogs");
            if (!(dialogs instanceof List)) continue;
            for (Object dialog : (List<?>) dialogs) {
                if (!(dialog instanceof android.app.Dialog) || !((android.app.Dialog) dialog).isShowing() || ((android.app.Dialog) dialog).getWindow() == null) continue;
                View control = ((android.app.Dialog) dialog).getWindow().getDecorView().findViewWithTag(anchor);
                if (control == null) continue;
                Rect visible = new Rect();
                if (!shown(control) || !control.getGlobalVisibleRect(visible) || visible.height() < control.getHeight())
                    return new String[]{"the sheet over the page holds " + anchor + " but it is not shown wholly (" + chain(control) + ")", "sheet"};
                return new String[]{null, "sheet"};
            }
        }
        View target;
        try { Method method = screen.getClass().getMethod("anchor", String.class); method.setAccessible(true); target = (View) method.invoke(screen, anchor); }
        catch (Exception error) { throw new IllegalStateException(error); }
        View page = (View) invoke(screen, "view", new Class<?>[0]);
        // The row is the shown view with the tag, as MainActivity.revealAnchor finds it (a page may hold a hidden one too).
        if (target == null) target = shownTagged(page, anchor);
        if (target == null) target = page.findViewWithTag(anchor);
        if (target == null) return new String[]{"no view " + anchor + " on the page, and no sheet over it holds one", "page"};
        if (!shown(target)) return new String[]{anchor + " is on the page but not shown (" + chain(target) + ")", "page"};
        Rect visible = new Rect();
        int least = Math.min(target.getHeight(), Math.round(40 * activity.getResources().getDisplayMetrics().density));
        if (!target.getGlobalVisibleRect(visible) || visible.height() < least)
            return new String[]{anchor + " was not brought inside the window (" + visible.toShortString() + " of " + target.getHeight() + " px shown)", "page"};
        return new String[]{null, "page"};
    }

    /** Back to Search: Back closes a sheet over the result's page first, then leaves the page. */
    private void backToSearch() {
        for (int i = 0; i < 4; i++) {
            if (onMain(() -> HubPages.SEARCH.equals(field(activity, "selectedPage")) && !Boolean.TRUE.equals(field(activity, "rebuilding"))) && !dialogShown()) return;
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            settle();
        }
        require(onMain(() -> HubPages.SEARCH.equals(field(activity, "selectedPage"))), "Back did not return to Search from " + onMain(() -> field(activity, "selectedPage")));
    }

    /** Whether one of the shown screen's parts has a sheet open (its "dialogs"). */
    private boolean dialogShown() {
        return onMain(() -> {
            Object screen = field(activity, "screen");
            List<Object> parts = new ArrayList<>();
            parts.add(screen);
            if (screen.getClass().getSimpleName().equals("HubPageScreen")) parts.addAll((List<?>) field(screen, "parts"));
            for (Object part : parts) {
                Object dialogs = fieldOrNull(part, "dialogs");
                if (dialogs instanceof List) for (Object dialog : (List<?>) dialogs) if (dialog instanceof android.app.Dialog && ((android.app.Dialog) dialog).isShowing()) return true;
            }
            return false;
        });
    }

    /** Lower case, one space between words, a typographic apostrophe as a plain one: how a row's title is compared. */
    private static String fold(String text) {
        return text == null ? "" : text.replace('’', '\'').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Whether a shown TextView in view reads folded (fold) text. */
    private static boolean hasFoldedText(View view, String folded) {
        if (view instanceof TextView && view.isShown() && fold(((TextView) view).getText().toString()).equals(folded)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasFoldedText(((ViewGroup) view).getChildAt(i), folded)) return true;
        return false;
    }

    /**
     * The header's search (Hub layout part 12): its field focused at the very top, the page header stepped aside while
     * the owner searches, the subjects listed until typing; a result opens over the search and Back returns to it with
     * its query and results; Back then clears the query, then leaves. Cancel leaves at once with a query typed; with
     * the field empty and left the header stays hidden (Cancel the one way out) and Cancel goes Home at once.
     */
    private void search() throws Exception {
        Object[] pane = scooterPane();
        if (pane[0] != null) keyboardSearch(pane);
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        requireSamePane(pane, HubPages.SEARCH);
        if (pane[0] != null) requireInPage(SEARCH_FIELD, "The search page's field");
        EditText[] field = new EditText[1];
        await(() -> onMain(() -> { field[0] = firstEditText(activity.findViewById(android.R.id.content)); return field[0] != null && field[0].hasFocus(); }),
                "The search page's field is not focused");
        main(() -> {
            for (String subject : HubPages.SUBJECTS) require(shown(root().findViewWithTag("home-subject-" + subject)), "Search does not list " + subject + " while empty");
            require(!shown(root().findViewWithTag("hub-back")), "The page header did not step aside while the field has the cursor");
            require(shown(root().findViewWithTag("hub-search-cancel")), "Search has no Cancel beside its field");
        });
        // "batt", as the plan's search mock-up: Battery's pages, settings and readings (part 12's full search). The mock-up has
        // no count line, so the result rows themselves are counted (HubSearchScreen tags each "hub-search-<kind>-<id>").
        main(() -> field[0].setText("batt")); settle();
        main(() -> require(!shown(root().findViewWithTag("home-subject-" + HubPages.RIDE)), "Search still lists the subjects while a query is typed"));
        main(() -> {
            int found = resultRows(root().findViewWithTag("hub-search-results"));
            require(found > 0, "Search found nothing for \"batt\" (" + found + " result rows shown)");
        });
        // On two panes the results show in the page pane, and the scooter pane stays as it was.
        if (pane[0] != null) requireInPage("hub-search-results", "Search's results");
        requireSamePane(pane, "Search's results");
        // Pictured with the keyboard gone, so every window size pictures the typed query the same way.
        hideKeyboard();
        main(() -> require(!shown(root().findViewWithTag("hub-back")), "The page header came back while a query is typed"));
        screenshot("search-typed");
        // The connection dropping while the owner searches moves nothing: the header, connection line included, stays
        // stepped aside, the field at the very top with Cancel beside it (part 11's line shows only while not connected).
        BleController ble = activity.service().ble;
        Rect fieldAt = onMain(() -> windowRect(field[0])), cancelAt = onMain(() -> windowRect(root().findViewWithTag("hub-search-cancel")));
        main(ble::disconnect);
        await(() -> onMain(() -> !ble.ready()), "The simulated scooter stayed connected");
        settle();
        main(() -> {
            EditText now = firstEditText(activity.findViewById(android.R.id.content));
            require(now != null && "batt".contentEquals(now.getText()), "The search's query was lost as the connection dropped");
            require(!shown(root().findViewWithTag("hub-back")) && !shown(root().findViewWithTag("hub-page-connection")), "The page header came back as the connection dropped while searching");
            require(windowRect(now).equals(fieldAt), "The search field moved from " + fieldAt + " to " + windowRect(now) + " as the connection dropped");
            require(windowRect(root().findViewWithTag("hub-search-cancel")).equals(cancelAt), "Cancel moved as the connection dropped while searching");
        });
        screenshot("search-disconnected");
        main(ble::connectLast);
        await(() -> onMain(() -> ble.ready()), "The simulated scooter did not reconnect");
        settle();
        main(() -> {
            field[0] = firstEditText(activity.findViewById(android.R.id.content));
            require(field[0] != null && windowRect(field[0]).equals(fieldAt), "The search field moved as the scooter reconnected");
            require(!shown(root().findViewWithTag("hub-page-connection")), "Search shows a connection line after reconnecting");
        });
        // The Reminders result: HubSearchScreen tags each result "hub-search-<kind>-<anchor or page>". Each word an owner
        // might type for it finds it (#410 review: main's search found Maintenance reminders by no English word).
        String reminders = "hub-search-setting-" + HubPages.SERVICE_MAINTENANCE;
        for (String word : new String[] {"service", "tyre", "tire", "reminder"}) {
            main(() -> field[0].setText(word)); settle();
            main(() -> require(shown(root().findViewWithTag(reminders)), "Search for \"" + word + "\" does not list Service › Maintenance's Reminders ("
                    + resultRows(root().findViewWithTag("hub-search-results")) + " result rows)"));
        }
        // "maintenance" finds the Reminders setting, whose tap opens Service › Maintenance over Search.
        main(() -> field[0].setText("maintenance")); settle();
        tapTag(reminders);
        arrived(HubPages.SERVICE_MAINTENANCE, HubPages.HOME, HubPages.SEARCH, HubPages.SERVICE_MAINTENANCE);
        systemBack();
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        main(() -> {
            EditText now = firstEditText(activity.findViewById(android.R.id.content));
            require(now != null && "maintenance".contentEquals(now.getText()), "Back from a result lost the search's query");
            require(shown(root().findViewWithTag(reminders)), "Back from a result lost the search's results");
        });
        hideKeyboard();
        // Back reaches the page through the system's dispatch, which can land after settle() on a loaded emulator (hub-725
        // failed here once on the council's pool): wait for the cleared query, then for the subjects, each with its message.
        backPastKeyboard(() -> onMain(() -> { EditText now = firstEditText(activity.findViewById(android.R.id.content)); return now != null && now.getText().length() == 0; }),
                "Back did not clear the search's query first");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        await(() -> onMain(() -> shown(root().findViewWithTag("home-subject-" + HubPages.RIDE))), "Back cleared the query but the subjects did not come back");
        requireStack(HubPages.HOME, HubPages.SEARCH);
        backPastKeyboard(this::onHome, "Back from the empty search did not return to Home");
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        // Cancel leaves at once, query and all.
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        main(() -> firstEditText(activity.findViewById(android.R.id.content)).setText("batt")); settle();
        hideKeyboard();
        tapTag("hub-search-cancel");
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        // Empty and left: the header stays hidden, the field at the top with Cancel, which goes Home at once.
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        hideKeyboard();
        main(() -> {
            require(!shown(root().findViewWithTag("hub-back")), "The page header came back with the field empty and left");
            require(shown(root().findViewWithTag("hub-search-cancel")), "Search has no Cancel with the field empty and left");
        });
        tapTag("hub-search-cancel");
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        // A result opens its page over Search (#410's round, adapted): "star" finds the Star lights page (and not Line
        // lights), whose tap opens Lights & sound › Star lights with Search under it; Back returns to Search with "star"
        // and its results kept, as this part's search keeps them (main's Search was built afresh, empty).
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        main(() -> firstEditText(activity.findViewById(android.R.id.content)).setText(SEARCH_QUERY)); settle();
        main(() -> require(!shown(root().findViewWithTag("home-subject-" + HubPages.RIDE)), "Search still lists the subjects while \"" + SEARCH_QUERY + "\" is typed"));
        hideKeyboard();
        main(() -> require(shown(root().findViewWithTag(SEARCH_RESULT)), "Search for \"" + SEARCH_QUERY + "\" does not show the Star lights page ("
                + resultRows(root().findViewWithTag("hub-search-results")) + " result rows; " + chain(root().findViewWithTag(SEARCH_RESULT)) + ")"));
        screenshot("search-star");
        tapTag(SEARCH_RESULT);
        arrived(HubPages.LIGHTS_STAR, HubPages.HOME, HubPages.SEARCH, HubPages.LIGHTS_STAR);
        screenshot("search-opened-star-lights");
        systemBack();
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        String[] kept = new String[1];
        await(() -> onMain(() -> {
            EditText now = firstEditText(activity.findViewById(android.R.id.content));
            kept[0] = now == null ? null : now.getText().toString();
            return now != null && SEARCH_QUERY.contentEquals(now.getText()) && shown(root().findViewWithTag(SEARCH_RESULT));
        }), () -> "Back from the Star lights result shows Search with the query \"" + kept[0] + "\"; this part's search keeps \"" + SEARCH_QUERY + "\" and its results");
        hideKeyboard();
        tapTag("hub-search-cancel");
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        checks.add("Search: opens focused at the top with the header stepped aside and the subjects listed; typing \"batt\" hides them and finds at least one result row; \"service\", \"tyre\", \"tire\" and \"reminder\" list Service › Maintenance's Reminders, and \"maintenance\" finds it and opens Service › Maintenance over the search; the connection dropping and coming back while searching moves neither the field nor Cancel and shows no header; Back returns to the search with its query and results, then clears it, then returns to Home; Cancel returns to Home at once, with a query or empty, and the header stays hidden while the field is empty and left;"
                + " \"star\" finds Star lights, whose tap opens it over Search (stack Home, Search, Star lights); Back returns to Search with \"star\" and its results kept");
    }

    /** The search page's field (HubSearchScreen.TAG_FIELD). */
    private static final String SEARCH_FIELD = "hub-search-field";

    /**
     * The wide Home's search field from a keyboard (#394 early review, finding 1): it takes focus by key, not by touch,
     * and Enter on it opens the search in the page pane with the scooter pane unchanged; Back returns to Home.
     */
    private void keyboardSearch(Object[] pane) throws Exception {
        // A Tab leaves touch mode, as a keyboard's first key does.
        sendKeyDownUpSync(KeyEvent.KEYCODE_TAB); settle();
        main(() -> {
            View field = root().findViewWithTag("home-search");
            require(field != null && field.isFocusable() && !field.isFocusableInTouchMode(),
                    "The wide Home's search field cannot take focus from a keyboard (or a tap would raise the keyboard)");
            require(field.requestFocus() && field.hasFocus(), "The wide Home's search field did not take the keyboard's focus");
        });
        sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER); settle();
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        requireSamePane(pane, "Search from the keyboard");
        hideKeyboard();
        // Search asks for a keyboard (MainActivity.focusPageSearch) that can show after hideKeyboard on a loaded emulator
        // and take the first Back (#394 council review, second round: hub-725 failed 3 of 3 here on a second emulator).
        backPastKeyboard(this::onHome, "Back from the search opened by the keyboard did not return to Home");
        requireHome("CleanHomeScreen");
        requireStack(HubPages.HOME);
        checks.add("Wide Home: the search field takes focus from a keyboard, not a tap, and Enter opens Search in the page pane with the scooter pane unchanged");
    }

    /**
     * The Lock bar keeps its place and size, and Home's scooter name its place, while the top area changes moment; each
     * moment is pictured at the same scroll (Home's top).
     */
    private void moments() throws Exception {
        requireHome("CleanHomeScreen");
        // On two panes the moments are the scooter pane's Clean Home's, scrolled in that pane (part 13).
        Object clean = onMain(() -> twoPanes() ? field(activity, "homeScreen") : field(activity, "screen"));
        main(() -> homeScroll().scrollTo(0, 0));
        settle();
        // The scroll bar a scroll shows fades on its own; every moment is pictured without it.
        SystemClock.sleep(ViewConfiguration.getScrollDefaultDelay() + ViewConfiguration.getScrollBarFadeDuration() + 100);
        settle();
        main(() -> require(homeScroll().getScrollY() == 0, "Clean Home did not scroll back to its top"));
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
        int[] justBack = placeIn(clean, 2, "the Just back card");
        require(Arrays.equals(parked, justBack), "The Lock bar or the scooter name moved for the Just back card: " + describe(parked) + " -> " + describe(justBack));
        // The route panel as it is on screen: shown with at least the room it draws in (JustBackView's MIN_ROUTE_DP; under it
        // the panel draws nothing) and wholly inside the window, with the ride's route drawn in it: at least ROUTE_POINTS
        // points, read on a worker once the card is bound (an unreadable journal or a ride with no usable fixes gives the
        // empty route, drawn as a placeholder icon, which fails here; #410 council, final review, finding 1). The route is
        // required unless the step declares -e just_back_route optional (the sweep's four Hub steps declare nothing, so
        // 344x882 and 725x604 at 1x and 1.4x text must draw it); then the panel may be gone only where JustBackFit.split,
        // given the room the card's own layout leaves it, gives it up, and the walk says the route was not shown.
        String panelShown = onMain(() -> routeOnScreen(card)), routeShown = panelShown;
        boolean routeOptional = "optional".equals(arguments.getString("just_back_route", "required"));
        if (routeShown == null) {
            require(routeOptional, "Just back shows no route panel at this window (" + onMain(() -> routeFit(card)) + "); this step requires the route (-e just_back_route optional for a window where the fit gives it up)");
            require(onMain(() -> routeFit(card)[1] == 0), "Just back's route panel is not shown, though JustBackFit.split gives it room (" + Arrays.toString(onMain(() -> routeFit(card))) + ")");
        } else {
            int[] points = {0};
            await(() -> onMain(() -> { float[] route = (float[]) field(field(card, "route"), "route"); points[0] = route == null ? -1 : (route.length - 2) / 2; return points[0] >= ROUTE_POINTS; }),
                    () -> "Just back's route panel shows (" + panelShown + ") but its route has " + (points[0] < 0 ? "not arrived" : points[0] + " points") + ", not at least " + ROUTE_POINTS + " from the ride's 121 GPS fixes");
            routeShown = panelShown + ", " + points[0] + " points";
        }
        String routeSaid = routeShown;
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
        // seeded ride's 6.2 mi. On two panes the subjects are the page pane's (HubHomePane), which takes the scooter
        // pane's found ride (part 13).
        Object subjects = onMain(() -> twoPanes() ? field(field(activity, "screen"), "subjects") : field(clean, "subjects"));
        require(subjects != null, "Home's subject list was not found" + (twoPanes() ? " in the page pane" : ""));
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
                + " (a ride saved just now: its ended line, distance, tiles at one value size and baseline, and "
                + (routeSaid != null ? "its route drawn on screen (" + routeSaid + ")" : "no route shown: -e just_back_route optional, and JustBackFit.split gives up the route panel at this size")
                + "; the Ride row shows that ride); each pictured at that scroll");
    }

    /** The least number of route points Just back must draw for the seeded ride (a start, an end and two between). */
    private static final int ROUTE_POINTS = 4;

    /**
     * JustBackFit.split (package-private in the app, reached by reflection) for the room the card's own layout leaves
     * between its ended line and its tiles, as JustBackView.onMeasure asks it: {distance box, route panel, route gap} px.
     * Main thread.
     */
    private int[] routeFit(Object card) {
        ViewGroup view = (ViewGroup) card;
        View first = (View) field(card, "first"), tiles = (View) field(card, "tiles");
        int room = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom() - outer(first) - outer(tiles);
        float density = activity.getResources().getDisplayMetrics().density;
        try {
            Class<?> fit = Class.forName("com.jjricks.volt.JustBackFit", true, card.getClass().getClassLoader());
            Method split = fit.getDeclaredMethod("split", int.class, int.class, int.class, int.class, int.class);
            split.setAccessible(true);
            Field gap = card.getClass().getDeclaredField("ROUTE_GAP_DP");
            gap.setAccessible(true);
            return (int[]) split.invoke(null, room, (Integer) field(card, "distanceFull"), (Integer) field(card, "distanceFloor"),
                    Math.round(((Integer) field(card, "MIN_ROUTE_DP")) * density), Math.round(gap.getInt(null) * density));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    /** A child's height with its top and bottom margins (JustBackView.outer). */
    private static int outer(View child) {
        ViewGroup.MarginLayoutParams size = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        return child.getHeight() + size.topMargin + size.bottomMargin;
    }

    /**
     * Just back's route panel (JustBackView's route, tag home-just-back-route) where an owner sees it: shown, at least
     * MIN_ROUTE_DP high (under that it draws nothing) and wholly inside the window, unclipped. Its size and place, or null
     * where the card's fit gave the panel up (gone, or too low to draw). Main thread.
     */
    private String routeOnScreen(Object card) {
        View panel = (View) field(card, "route");
        int least = Math.round(((Integer) field(card, "MIN_ROUTE_DP")) * activity.getResources().getDisplayMetrics().density);
        if (!shown(panel) || panel.getHeight() < least) return null;
        Rect seen = new Rect(), place = windowRect(panel);
        require(panel.getGlobalVisibleRect(seen) && seen.width() >= panel.getWidth() - 1 && seen.height() >= panel.getHeight() - 1,
                "Just back's route panel " + place.toShortString() + " is not wholly on screen (visible " + seen.toShortString() + ")");
        Rect window = new Rect(0, 0, root().getWidth(), root().getHeight());
        require(window.contains(place), "Just back's route panel " + place.toShortString() + " runs outside the window " + window.toShortString());
        return panel.getWidth() + "x" + panel.getHeight() + " px at " + place.left + "," + place.top;
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
        // The contributor's decision (14 Nov, #383): the dock is pinned to the bottom, always. It never lifts above the Lock
        // bar or steps aside for it; the page scrolls the Lock bar (one of the rider's own widgets) clear of it instead.
        dockHome = dockPlace("Widgets Home as it opens");
        // Pictured as Home opens, at its top, with the dock in its place.
        screenshot("widgets-home");
        String lockBar = lockBarScrollsClear("Widgets Home");
        // -e lock_bar_arrival names where the step's window has Widgets' Lock bar as Home opens: under the dock (725x604 dp
        // at 1.4x text, where part 11 used to lift the dock), above it, or below it (off screen).
        if (arguments.containsKey("lock_bar_arrival"))
            require(lockBar.equals(arguments.getString("lock_bar_arrival")), "Widgets Home opened with the Lock bar " + lockBar + " the dock, expected " + arguments.getString("lock_bar_arrival"));
        main(() -> require(shown(root().findViewWithTag("hub-dock")) && shown(root().findViewWithTag("hub-dock-handle")), "The dock or its All item is not shown"));
        widgetsPages(false);
        dockStays("Widgets Home after Search and Settings");
        for (String subject : shown) {
            dockStays("Widgets Home before " + subject);
            main(() -> { View item = root().findViewWithTag("hub-dock-" + subject); require(shown(item), "The dock has no " + subject + " item");
                require(item.getContentDescription() != null && item.getContentDescription().length() > 0, "The dock's " + subject + " item has no spoken name"); });
            tapTag("hub-dock-" + subject);
            arrived(subject, HubPages.HOME, subject);
            main(() -> require(field(activity, "dock") == null, "The dock shows on " + subject));
            systemBack();
            requireHome("DashboardScreen");
            dockStays("Widgets Home after Back from " + subject);
        }
        for (String subject : HubPages.SUBJECTS) if (!shown.contains(subject))
            main(() -> require(root().findViewWithTag("hub-dock-" + subject) == null, "The dock has an item for " + subject + ", which it does not list"));
        checks.add("Widgets dock (" + layout + " layout at " + widthDp() + " dp, " + textScale() + "x text): " + shown + " each open their subject over Home; Back returns to Widgets Home; "
                + "no dock on subject pages; the dock pinned to the bottom at " + describeDock(dockHome) + " as Home opens, scrolled, and after every Back (never lifted or aside); "
                + "the Lock bar " + lockBar + " the dock as Home opens, and Home scrolls it fully clear of the dock");
        // All: the sheet with every subject; a row opens its subject and closes the sheet.
        for (String subject : HubPages.SUBJECTS) {
            View[] sheet = {openAllSheet()};
            main(() -> {
                View content = sheet[0];
                require(content.findViewWithTag("hub-dock-sheet") != null, "The All sheet has no content tag");
                for (String each : HubPages.SUBJECTS) require(shown(content.findViewWithTag("home-subject-" + each)), "The All sheet does not list " + each);
            });
            if (subject.equals(HubPages.SUBJECTS.get(0))) screenshot("widgets-all-sheet", sheet[0]);
            tapIn(() -> sheet[0], "home-subject-" + subject, "home-subject-" + subject + " in the All sheet");
            await(() -> onMain(() -> !sheet[0].isAttachedToWindow()), "The All sheet stayed open after opening " + subject);
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
     * The page scroller's bar at the window's edge (the owner, 16 Nov: "Screen edge is reasonable probably"): Clean Home and
     * a subject page (MainActivity's own scroller, with the side padding), Settings (its screen's own scroller) and Widgets Home.
     */
    private void edgeScrollbars() throws Exception {
        requireHome("CleanHomeScreen");
        requireEdgeScrollbar("Clean Home");
        String subject = HubPages.SUBJECTS.get(0);
        tapTag("home-subject-" + subject);
        arrived(subject, HubPages.HOME, subject);
        requireEdgeScrollbar(subject);
        systemBack();
        requireHome("CleanHomeScreen");
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        requireEdgeScrollbar("Settings");
        HubTestPages.back(this, activity);
        switchStyle(WIDGETS);
        requireHome("DashboardScreen");
        requireEdgeScrollbar("Widgets Home");
        switchStyle(CLEAN);
        requireHome("CleanHomeScreen");
        checks.add("Page scrollbar at the window's edge (SCROLLBARS_OUTSIDE_OVERLAY, in the side gutter) on Clean Home, " + subject + ", Settings and Widgets Home");
    }

    /**
     * MainActivity's page scroller draws its vertical bar over its padding (SCROLLBARS_OUTSIDE_OVERLAY), its edges are the
     * window's content edges (inside the system bars), and the bar fits the gutter on its side (the scroller's padding plus
     * its content's), so it never covers the content.
     */
    private void requireEdgeScrollbar(String where) {
        main(() -> {
            Object page = field(activity, "scroll");
            require(page instanceof ScrollView, where + " has no page scroller");
            ScrollView scroll = (ScrollView) page;
            require(scroll.getScrollBarStyle() == View.SCROLLBARS_OUTSIDE_OVERLAY, where + "'s page scrollbar style is " + scroll.getScrollBarStyle()
                    + ", expected SCROLLBARS_OUTSIDE_OVERLAY (" + View.SCROLLBARS_OUTSIDE_OVERLAY + ")");
            require(scroll.isVerticalScrollBarEnabled(), where + "'s page scroller has no vertical bar");
            View window = (View) field(activity, "root");
            Rect at = windowRect(scroll), frame = windowRect(window);
            int left = frame.left + window.getPaddingLeft(), right = frame.right - window.getPaddingRight();
            require(at.left == left && at.right == right, where + "'s page scroller spans " + at.left + ".." + at.right + " px, the window's content " + left + ".." + right);
            boolean rtl = scroll.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            View content = scroll.getChildCount() > 0 ? scroll.getChildAt(0) : null;
            int gutter = rtl ? scroll.getPaddingLeft() + (content == null ? 0 : content.getPaddingLeft())
                    : scroll.getPaddingRight() + (content == null ? 0 : content.getPaddingRight());
            require(scroll.getVerticalScrollbarWidth() > 0 && scroll.getVerticalScrollbarWidth() <= gutter, where + "'s page scrollbar is "
                    + scroll.getVerticalScrollbarWidth() + " px wide, its gutter " + gutter + " px");
        });
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
            View sheet = openAllSheet();
            main(() -> require(shown(sheet.findViewWithTag(each[0])), "The All sheet has no " + each[0] + " row"));
            if (alone && each[1].equals(HubPages.SEARCH)) screenshot("widgets-all-sheet-pages", sheet);
            tapIn(() -> sheet, each[0], each[0] + " in the All sheet");
            await(() -> onMain(() -> !sheet.isAttachedToWindow()), "The All sheet stayed open after " + each[0]);
            arrived(each[1], HubPages.HOME, each[1]);
            if (each[1].equals(HubPages.SEARCH)) {
                await(() -> onMain(() -> { EditText field = firstEditText(activity.findViewById(android.R.id.content)); return field != null && field.hasFocus(); }),
                        "Search opened from the All sheet without its field focused");
                hideKeyboard();
                backPastKeyboard(this::onHome, "Back from Search did not return to Widgets Home");
            } else {
                systemBack();
            }
            requireHome("DashboardScreen");
        }
        checks.add("Widgets Home keeps main's header; the All sheet's Search and Settings rows open those pages over Home, and Back returns to Widgets Home");
    }

    /**
     * Widgets Home's dock where a rider finds it (HubTestPages.showDock: shown, still, with no offset and at HubPages.dockTop
     * in its frame, pinned to the bottom): its place in the window, x, y, width and height (px).
     */
    private int[] dockPlace(String where) {
        HubTestPages.showDock(this, activity);
        return onMain(() -> {
            View dock = root().findViewWithTag("hub-dock");
            require(shown(dock), where + ": the dock is not shown");
            require(dock.getTranslationY() == 0, where + ": the dock is moved " + dock.getTranslationY() + " px off its place");
            int[] at = new int[2]; dock.getLocationInWindow(at);
            return new int[]{at[0], at[1], dock.getWidth(), dock.getHeight()};
        });
    }

    /** The dock is where it was as Widgets Home opened (dockHome): pinned, it never moves for the Lock bar or a scroll. */
    private void dockStays(String where) {
        int[] now = dockPlace(where);
        // Alone (-e mode widgets-pages), the first place found is the one it keeps.
        if (dockHome == null) { dockHome = now; return; }
        require(Arrays.equals(dockHome, now), where + ": the dock moved from " + describeDock(dockHome) + " to " + describeDock(now));
    }

    private static String describeDock(int[] place) { return place[2] + "x" + place[3] + " px at " + place[0] + "," + place[1]; }

    /**
     * Widgets' Lock bar (MainActivity.dashboardLock) and the pinned dock: with Home at its top, where the bar is (under the
     * dock, above it, or below it off screen); where it is under, the walk scrolls it clear as it would before a tap
     * (scrollClear) and requires the whole bar shown above the dock. Then HubTestPages.requireEndClearOfDock scrolls Home to
     * its end (its last card and the bar above the dock), Home goes back to its top, and the dock stayed in its place
     * throughout. The walk never taps the bar (it would send a command). Returns where the bar was.
     */
    private String lockBarScrollsClear(String where) {
        main(() -> ((ScrollView) field(activity, "scroll")).scrollTo(0, 0));
        settle();
        String arrival = onMain(() -> {
            View bar = (View) field(activity, "dashboardLock"), dock = root().findViewWithTag("hub-dock");
            require(bar != null && bar.isShown(), where + " has no Lock bar");
            Rect barAt = windowRect(bar), dockAt = windowRect(dock);
            return Rect.intersects(barAt, dockAt) ? "under" : barAt.bottom <= dockAt.top ? "above" : "below";
        });
        if (!arrival.equals("above")) {
            main(() -> scrollClear((View) field(activity, "dashboardLock"), root().findViewWithTag("hub-dock")));
            settle();
            main(() -> {
                View bar = (View) field(activity, "dashboardLock"), dock = root().findViewWithTag("hub-dock");
                Rect barAt = windowRect(bar), dockAt = windowRect(dock), seen = new Rect();
                require(bar.getGlobalVisibleRect(seen) && seen.height() >= bar.getHeight() - 1, where + ": the Lock bar scrolled toward the dock is not wholly shown (" + seen.toShortString() + ")");
                require(barAt.bottom <= dockAt.top, where + ": scrolled clear, the Lock bar " + barAt.toShortString() + " still runs under the dock " + dockAt.toShortString());
            });
            dockStays(where + " with the Lock bar scrolled clear");
        }
        String end = HubTestPages.requireEndClearOfDock(this, activity);
        dockStays(where + " scrolled to its end (" + end + ")");
        main(() -> ((ScrollView) field(activity, "scroll")).scrollTo(0, 0));
        settle();
        dockStays(where + " scrolled back to its top");
        return arrival;
    }

    /** A view's place in the window (px). Main thread. */
    private static Rect windowRect(View view) {
        int[] at = new int[2]; view.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /**
     * As a rider scrolls a widget out from under Widgets Home's pinned dock: the page's own scroller (MainActivity.scroll)
     * moves it to end CLEAR_DP above the dock's top. Not the nearest ScrollView: every Widgets card has its own inner
     * viewport (DashboardScreen's WidgetScrollView), and scrolling that leaves the card where it is (hub-725-large on
     * 91f8c0e5). Nothing where the view already ends above the dock. Main thread.
     */
    private void scrollClear(View view, View dock) {
        Rect at = windowRect(view), dockAt = windowRect(dock);
        int by = at.bottom - dockAt.top + Math.round(CLEAR_DP * activity.getResources().getDisplayMetrics().density);
        if (at.bottom <= dockAt.top || by <= 0) return;
        View page = (View) field(activity, "scroll");
        require(page != null && isInside(view, page), "The view under the dock is not on Widgets Home's page scroller");
        page.scrollBy(0, by);
    }

    /** Taps the dock's All item and returns its sheet (DragSheet's overlay in Volt's window), open and settled. */
    private View openAllSheet() {
        dockStays("Widgets Home before All");
        tapTag("hub-dock-handle");
        View[] sheet = new View[1];
        await(() -> onMain(() -> { sheet[0] = (View) field(dock(), "sheet"); return sheet[0] != null && sheet[0].isAttachedToWindow(); }), "All did not open the subjects sheet");
        // Until its spring has settled open (DragSheet's state OPEN), so a picture or a tap finds it in place.
        await(() -> onMain(() -> { Object drag = field(dock(), "drag"); return drag != null && Integer.valueOf(4).equals(field(drag, "state")); }), "The All sheet did not settle open");
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
        // Widgets Home's dock is pinned over the page's bottom: a view under it is scrolled clear first, as a rider would.
        main(() -> {
            View view = window.get().findViewWithTag(tag), dock = root().findViewWithTag("hub-dock");
            if (view.getRootView() == root() && shown(dock) && !isInside(view, dock)) scrollClear(view, dock);
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
    /**
     * Back meant for the page, past a keyboard that came up late. hideKeyboard waits out a keyboard already shown, but on
     * a loaded emulator the one Search asks for (MainActivity.focusPageSearch) can show after that, and Android gives Back
     * to a shown keyboard first. So a frame callback watches the keyboard: when Back closed one instead of reaching the
     * page (done still false), Back again, up to three times. Waits on what is seen, not on time; a Back that shows no
     * effect within 3 s (lost while a slow emulator's window was still settling) is pressed again too, within the three.
     */
    private void backPastKeyboard(BooleanSupplier done, String message) {
        if (Build.VERSION.SDK_INT < 30) { systemBack(); await(done, message); return; }
        int[] closed = {0};
        boolean[] watching = {true}, was = {false};
        android.view.Choreographer.FrameCallback[] watch = new android.view.Choreographer.FrameCallback[1];
        main(() -> {
            was[0] = keyboardShown();
            watch[0] = time -> {
                if (!watching[0]) return;
                boolean now = keyboardShown();
                if (was[0] && !now) closed[0]++;
                was[0] = now;
                android.view.Choreographer.getInstance().postFrameCallback(watch[0]);
            };
            android.view.Choreographer.getInstance().postFrameCallback(watch[0]);
        });
        try {
            List<String> missed = new ArrayList<>();
            for (int tries = 0; tries < 3; tries++) {
                int before = onMain(() -> closed[0]);
                systemBack();
                long end = SystemClock.uptimeMillis() + 3000;
                while (!done.getAsBoolean() && onMain(() -> closed[0] <= before) && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
                if (done.getAsBoolean()) return;
                missed.add(onMain(() -> closed[0] > before) ? "closed a keyboard" : "no effect in 3 s (" + onMain(this::whereNow) + ")");
            }
            require(false, message + " (three Backs: " + missed + ")");
        } finally {
            main(() -> watching[0] = false);
        }
    }
    private boolean keyboardShown() { WindowInsets insets = root().getRootWindowInsets(); return insets != null && insets.isVisible(WindowInsets.Type.ime()); }
    private boolean onHome() { return onMain(() -> HubPages.HOME.equals(field(activity, "selectedPage"))); }

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

    /**
     * The page shown is page, the stack is exactly stack, and a non-Home page has its back link: shown, except on Search
     * with Cancel beside its field, where the page header is hidden and Cancel is the one way out (part 12,
     * HubSearch.headerHidden); never both.
     */
    private void arrived(String page, String... stack) {
        await(() -> onMain(() -> page.equals(field(activity, "selectedPage")) && !Boolean.TRUE.equals(field(activity, "rebuilding"))), () -> "Expected page " + page + ", shown " + onMain(() -> field(activity, "selectedPage")));
        settle();
        // A page arrives sliding in from the side as it fades in (Motion.crossfade): what follows measures it once it has
        // come to rest (HubTestPages.awaitAtRest), as UiOrganizationValidation's navigation check does (#393, third round).
        if (!HubPages.HOME.equals(page)) {
            HubTestPages.awaitAtRest(this, () -> {
                List<View> moving = new ArrayList<>();
                moving.add(root().findViewWithTag("hub-back")); moving.add(root().findViewWithTag("hub-search-cancel"));
                moving.add(firstEditText(activity.findViewById(android.R.id.content)));
                return moving;
            });
        }
        requireStack(stack);
        if (!HubPages.HOME.equals(page)) main(() -> {
            View back = root().findViewWithTag("hub-back");
            EditText search = HubPages.SEARCH.equals(page) ? firstEditText(activity.findViewById(android.R.id.content)) : null;
            boolean aside = search != null && shown(root().findViewWithTag("hub-search-cancel"));
            require(back != null && (shown(back) || aside), page + " has no back link");
            require(!(aside && shown(back)), page + " shows both its back link and Cancel");
            require(back.getContentDescription() != null && back.getContentDescription().length() > 0, page + "'s back link has no spoken name");
        });
        if (!HubPages.HOME.equals(page)) {
            boolean connected = onMain(() -> activity.service() != null && activity.service().ble.ready());
            // Search with Cancel beside its field hides the whole header, the connection line with it (part 12).
            if (searchHeaderAside(page)) main(() -> require(!shown(root().findViewWithTag("hub-page-connection")), "Search shows a connection line with its header stepped aside"));
            // The contributor's decision (14 Nov, #383): the connection line only while not connected, never on Settings.
            else if (connected || HubPages.appPage(page)) requireHeader(page, false);
        }
    }

    /** Whether page is Search with Cancel beside its field, where the page header steps aside (HubSearch.headerHidden). */
    private boolean searchHeaderAside(String page) {
        return HubPages.SEARCH.equals(page) && onMain(() -> firstEditText(activity.findViewById(android.R.id.content)) != null
                && shown(root().findViewWithTag("hub-search-cancel")));
    }

    /**
     * The page's header (part 11): its back link, then the connection line (MainActivity.TAG_PAGE_CONNECTION) and the
     * page's title. line: whether the line must show ("Disconnected · last seen …", tappable for the connection details);
     * false requires it gone (a page opened connected, and Settings always). The title follows the line, shown, with the
     * page's own name (Data's is Live Stats' title with its dot). Returns the title's top in the window (px).
     */
    private int requireHeader(String page, boolean line) {
        return onMain(() -> {
            View connection = root().findViewWithTag("hub-page-connection");
            require(connection instanceof TextView, page + "'s header has no connection line view (hub-page-connection)");
            if (line) {
                require(shown(connection) && ((TextView) connection).getText().length() > 0, page + "'s header shows no connection line while not connected");
                require(connection.isClickable() || ((View) connection.getParent()).isClickable(), page + "'s connection line cannot be tapped for the connection details");
            } else {
                require(connection.getVisibility() == View.GONE, page + "'s header " + (connection.getVisibility() == View.VISIBLE ? "shows" : "keeps room for")
                        + " the connection line \"" + ((TextView) connection).getText() + "\"" + (HubPages.appPage(page) ? " on a Settings page" : " while connected"));
            }
            ViewGroup header = (ViewGroup) connection.getParent();
            int at = header.indexOfChild(connection);
            require(at >= 0 && at + 1 < header.getChildCount(), page + "'s header has no title after its connection line");
            View title = header.getChildAt(at + 1);
            require(shown(title), page + "'s header shows no title");
            if (!HubPages.DATA.equals(page) && title instanceof TextView)
                require(HubText.title(page).render().contentEquals(((TextView) title).getText()), page + "'s title reads \"" + ((TextView) title).getText() + "\"");
            require(shown(root().findViewWithTag("hub-back")), page + "'s header has no back link");
            int[] where = new int[2]; title.getLocationInWindow(where);
            return where[1];
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
        // The wide Clean Home (part 13): Home's page pane is HubHomePane, beside the Clean Home of the scooter pane.
        await(() -> onMain(() -> HubPages.HOME.equals(field(activity, "selectedPage")) && field(activity, "screen") != null
                && field(activity, "screen").getClass().getSimpleName().equals("CleanHomeScreen".equals(screenClass) && twoPanes() ? "HubHomePane" : screenClass)),
                () -> "Home is not " + screenClass + " (" + onMain(this::whereNow) + ")");
        settle();
        main(() -> require(root().findViewWithTag("hub-back") == null, "Home has a back link"));
        // -e wide true or false: Clean Home takes two panes where the window gets the wide layout at this text size, and
        // one pane on the phone and on a wide window whose large text keeps the phone layout, every time it shows.
        if ("CleanHomeScreen".equals(screenClass) && arguments.containsKey("wide")) {
            boolean wide = panesExpected();
            main(() -> require(twoPanes() == wide && shown(root().findViewWithTag("hub-scooter-pane")) == wide && shown(root().findViewWithTag("hub-page-pane")) == wide,
                    wide ? "Clean Home on a wide window did not take two panes" : "Clean Home took two panes on a window whose layout is the phone's ("
                            + widthDp() + " dp, " + textScale() + "x text)"));
        }
    }
    /** Each subject row's height (px) on Clean Home before the scooter connected (evenSubjectRows), in SUBJECTS' order. */
    private int[] rowsUnknown;
    /**
     * Clean Home's subject rows are all one height (the owner's request on #394, 17 Nov), on one pane and on two (the
     * page pane's list): a row whose summary isn't known keeps the height of a row with one, its name centred, and its
     * summary stays out (GONE, so TalkBack reads no empty line). before null: Controls and Security have no summary yet
     * (not connected); it returns the rows' heights. Otherwise, connected: it waits for their summaries and requires each
     * row at its height from before, and every row whose name is one line at one height. Returns the heights now.
     */
    private int[] evenSubjectRows(int[] before) throws Exception {
        requireHome("CleanHomeScreen");
        boolean connected = before != null;
        List<String> pending = Arrays.asList(HubPages.CONTROLS, HubPages.SECURITY);
        await(() -> onMain(() -> {
            for (String subject : pending) if (summaryShown(subject) != connected) return false;
            return true;
        }), () -> onMain(() -> "Controls' and Security's summaries should " + (connected ? "show once connected" : "not show before the scooter connects")
                + ": Controls " + chain(subjectPart(HubPages.CONTROLS, 1)) + ", Security " + chain(subjectPart(HubPages.SECURITY, 1))));
        settle();
        int[] heights = new int[HubPages.SUBJECTS.size()];
        String[] described = new String[1];
        main(() -> {
            StringBuilder all = new StringBuilder();
            int oneLine = -1;
            for (int i = 0; i < heights.length; i++) {
                String subject = HubPages.SUBJECTS.get(i);
                View row = root().findViewWithTag("home-subject-" + subject);
                require(row != null && row.getHeight() > 0, "Clean Home has no laid-out row for " + subject);
                TextView name = (TextView) subjectPart(subject, 0);
                View summary = subjectPart(subject, 1);
                heights[i] = row.getHeight();
                all.append(subject).append(' ').append(heights[i]).append("px").append(summaryShown(subject) ? "" : " (no summary)")
                        .append(name.getLineCount() > 1 ? " (two-line name)" : "").append("; ");
                // No summary: nothing under the name for TalkBack, the name centred in the row's words.
                if (!summaryShown(subject)) {
                    require(summary.getVisibility() == View.GONE, subject + "'s unknown summary is not GONE: " + chain(summary));
                    View words = (View) name.getParent();
                    int above = name.getTop(), below = words.getHeight() - name.getBottom();
                    require(Math.abs(above - below) <= 2, subject + "'s name is not centred in its row without a summary: " + above + " px above, " + below + " px below");
                }
                if (name.getLineCount() == 1) {
                    if (oneLine < 0) oneLine = heights[i];
                    require(Math.abs(heights[i] - oneLine) <= 1, "Clean Home's subject rows differ in height (" + (connected ? "connected" : "not connected") + "): " + all);
                }
                if (connected) require(Math.abs(heights[i] - before[i]) <= 1, subject + "'s row changed height when its summary came: " + before[i] + " px before, " + heights[i] + " px now");
            }
            described[0] = all.toString();
        });
        screenshot(connected ? "subjects-summaries" : "subjects-no-summaries");
        checks.add("Clean Home's subject rows " + (panesExpected() ? "(two panes, the page pane's list)" : "(one pane)") + (connected
                ? ", connected: Controls and Security show summaries and every row keeps the height it had without one: "
                : ", not connected: Controls and Security have no summary (GONE, name centred) and every one-line row is one height: ") + described[0]);
        return heights;
    }
    /** A part of a subject's row on Clean Home: 0 its name, 1 its summary line (CleanSubjects.line). Main thread. */
    private View subjectPart(String subject, int part) {
        View row = root().findViewWithTag("home-subject-" + subject);
        if (!(row instanceof ViewGroup)) return null;
        View words = ((ViewGroup) row).getChildAt(1);
        return words instanceof ViewGroup ? ((ViewGroup) words).getChildAt(part) : null;
    }
    /** Whether a subject's row shows a summary text. Main thread. */
    private boolean summaryShown(String subject) {
        View summary = subjectPart(subject, 1);
        if (!(summary instanceof ViewGroup) || summary.getVisibility() != View.VISIBLE) return false;
        View value = ((ViewGroup) summary).getChildAt(1);
        return value instanceof TextView && value.getVisibility() == View.VISIBLE && ((TextView) value).length() > 0;
    }
    /**
     * Two panes are expected with -e wide true on a window that gets the wide layout at this text size (WideLayout.wide):
     * at 725x604 dp and 1x; not at 1.4x, where 725 / 1.4 < 650 keeps the phone layout (hub-725-large).
     */
    private boolean panesExpected() {
        return Boolean.parseBoolean(arguments.getString("wide", "false")) && WideLayout.wide(widthDp(), textScale());
    }
    /** Where the app is, for a failure: the page, its screen, a rebuild in progress, the page stack and the focused window. */
    @SuppressWarnings("unchecked")
    private String whereNow() {
        Object screen = field(activity, "screen");
        String stack;
        try { stack = String.valueOf(invoke(field(activity, "nav"), "pages", new Class<?>[0])); } catch (RuntimeException error) { stack = "?"; }
        return "page " + field(activity, "selectedPage") + ", screen " + (screen == null ? "none" : screen.getClass().getSimpleName())
                + ", rebuilding " + field(activity, "rebuilding") + ", stack " + stack + ", activity focused " + activity.hasWindowFocus()
                + ", resumed " + !activity.isFinishing();
    }
    private Object dock() {
        Object dock = field(activity, "dock");
        require(dock != null, "Widgets Home has no dock");
        return dock;
    }
    /** The wide Clean Home's two panes are up (MainActivity.homeScreen is the scooter pane's Clean Home). Call on the main thread. */
    private boolean twoPanes() { return field(activity, "homeScreen") != null; }

    /** Home's scroller: the scooter pane on two panes (part 13), else the page's. Main thread. */
    private ScrollView homeScroll() {
        return twoPanes() ? (ScrollView) root().findViewWithTag("hub-scooter-pane") : (ScrollView) field(activity, "scroll");
    }
    /** The scooter pane's view and its Clean Home, or nulls on one pane. */
    private Object[] scooterPane() {
        return onMain(() -> twoPanes() ? new Object[]{root().findViewWithTag("hub-scooter-pane"), field(activity, "homeScreen")} : new Object[]{null, null});
    }
    /** On two panes: the scooter pane is still the one captured (browsing never rebuilds it). */
    private void requireSamePane(Object[] pane, String page) {
        if (pane[0] == null) return;
        main(() -> require(root().findViewWithTag("hub-scooter-pane") == pane[0] && field(activity, "homeScreen") == pane[1],
                "The scooter pane was rebuilt opening " + page));
    }
    /** The view tagged tag exists and, on two panes, sits inside the page pane and is no wider than it. */
    private void requireInPage(String tag, String what) {
        main(() -> {
            View view = root().findViewWithTag(tag);
            require(view != null, what + " is missing (tag " + tag + ")");
            if (!twoPanes()) return;
            View pane = root().findViewWithTag("hub-page-pane");
            boolean inside = false;
            for (View at = view; at != null && !inside; at = at.getParent() instanceof View ? (View) at.getParent() : null) inside = at == pane;
            require(inside, what + " is not in the page pane");
            require(view.getWidth() <= pane.getWidth(), what + " is wider than the page pane");
        });
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
        if (!noNearby) await(() -> activity.service() != null, "The recreated activity has no service");
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
            if (noNearby) require(getTargetContext().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED, "-e mode " + NO_NEARBY + " needs Nearby permission not granted (" + permission + " is)");
            else require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
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
        // Without Nearby permission the service never binds (MainActivity.ensureService); Home shows instead.
        if (noNearby) requireHome("CleanHomeScreen");
        else await(() -> activity.service() != null, "App did not launch");
        settle();
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
    /** The search's result rows shown under its results ("hub-search-<kind>-<id>", HubSearchScreen.tag); 0 for none. */
    private static int resultRows(View view) {
        if (view == null || !view.isShown()) return 0;
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).matches("hub-search-(page|setting|reading|ride)-.+")) return 1;
        int found = 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) found += resultRows(((ViewGroup) view).getChildAt(i));
        return found;
    }
    /** A view and its ancestors' classes and visibility (V, I, G) and heights, for a failure message. Main thread. */
    private static String chain(View view) {
        if (view == null) return "no such view";
        StringBuilder out = new StringBuilder();
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null)
            out.append(out.length() == 0 ? "" : " < ").append(at.getClass().getSimpleName()).append(at.getVisibility() == View.VISIBLE ? "/V" : at.getVisibility() == View.INVISIBLE ? "/I" : "/G").append(at.getHeight());
        return out.toString();
    }
    /** Whether a shown TextView in view reads text. */
    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && view.isShown() && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
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
    private Object staticCall(Class<?> type, String name, Class<?>[] types, Object... args) {
        try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args); }
        catch (Exception error) { throw new IllegalStateException(type.getSimpleName() + "." + name, error); }
    }
    private Object fieldOrNull(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch (NoSuchFieldException next) { /* the superclass may declare it */ }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        return null;
    }
}
