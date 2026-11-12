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
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.jjricks.volt.BleController;
import com.jjricks.volt.HubPages;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Fresh disposable emulator only (refuses a physical phone, existing app data and Bluetooth turned on). Walks the Hub
 * (hub layout plan, PR 11; part 11b of the series) on the developer simulator (a synthetic UT5 MAX profile, no
 * scooter), the way an owner would, by tapping the views Home and each page show:
 *
 * <ul>
 *   <li><b>Clean Home</b>: each of the seven subject rows opens its subject, with Home under it, a back link and its
 *   title; each sub-page opens from its subject's row or link (or, where the simulated model hides that row, through
 *   the page stack's own route, which the result lists), its back link returns to the subject and system Back to
 *   Home. The gear opens Settings; Settings' own rows open its sub-pages the same way.</li>
 *   <li><b>The page stack</b>: opening a page already in the stack closes the pages above it, Home resets it, and an
 *   activity recreation (as a rotation or a language change makes) keeps the whole stack and the page shown.</li>
 *   <li><b>Search</b>: the header's search opens the search page (HubSearchScreen, part 12) with its field focused and
 *   the subjects listed; a search for "maintenance" shows the Reminders result, which opens Service › Maintenance
 *   reminders on top of the search; Back returns to the search with its query and results intact, Back again clears
 *   the query (the subjects show again), and Back once more returns to Home.</li>
 *   <li><b>Moments</b>: the Lock bar (and the top area above it) stay at the same place and size while the top area
 *   changes from the battery card to the simulator's charging view and back, and with the Just back card shown.</li>
 *   <li><b>Widgets Home</b>: its header's search and gear (as on Clean Home's header, since part 11) open Search and
 *   Settings over Home, and Back returns to Widgets Home; the dock shows on Widgets Home only (never on a subject
 *   page, never on Clean), each shown subject's icon opens it, and its All item opens the sheet listing all seven
 *   subjects, whose rows open their subject and close the sheet. With -e wide true or false, the dock must hold all seven (wide) or the
 *   phone's most-used ones (HubPages.dockSubjects).</li>
 *   <li><b>No scooter command</b>: the simulator's settings are unchanged at the end, no radio object, queued frame,
 *   pending change or recording exists, and nothing on the walk taps the Lock bar or a quick action.</li>
 * </ul>
 *
 * Pass -e mode all (the default) for all of the above. -e mode widgets-header checks only Widgets Home's header,
 * starting on Widgets: it has the search and gear buttons the product spec gives both Homes ("Settings (gear icon in
 * the Home header)"), and they open Search and Settings. -e theme dark|light and -e text_scale (1 by default) as the other
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
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
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
            require(mode.equals("all") || mode.equals("widgets-header"), "Unknown -e mode " + mode + " (all or widgets-header)");
            boolean widgetsFirst = mode.equals("widgets-header");
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
            if (widgetsFirst) widgetsHeader(true);
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
                tapTag("hub-back");
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
            reach(child, tag == null ? Collections.<String>emptyList() : Collections.singletonList(tag));
            arrived(child, HubPages.HOME, HubPages.SETTINGS, child);
            systemBack();
            arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        }
        tapTag("hub-back");
        requireHome("CleanHomeScreen");
        checks.add("Clean Home's gear opens Settings over Home; Settings' rows open its pages; Back returns to Settings, its back link to Home");
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

    /** The header's search: its field focused, the subjects listed, a result opening its page. */
    private void search() throws Exception {
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        EditText[] field = new EditText[1];
        await(() -> onMain(() -> { field[0] = firstEditText(activity.findViewById(android.R.id.content)); return field[0] != null && field[0].hasFocus(); }),
                "The search page's field is not focused");
        main(() -> {
            for (String subject : HubPages.SUBJECTS) require(shown(root().findViewWithTag("home-subject-" + subject)), "Search does not list " + subject + " while empty");
        });
        main(() -> field[0].setText("maintenance")); settle();
        main(() -> require(!shown(root().findViewWithTag("home-subject-" + HubPages.RIDE)), "Search still lists the subjects while showing results"));
        screenshot("search-maintenance");
        // The Reminders result: HubSearchScreen tags each result "hub-search-<kind>-<anchor or page>".
        String reminders = "hub-search-setting-" + HubPages.SERVICE_MAINTENANCE;
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
        systemBack();
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        main(() -> require(shown(root().findViewWithTag("home-subject-" + HubPages.RIDE)), "Back did not clear the search first"));
        systemBack();
        requireHome("CleanHomeScreen");
        checks.add("Search: opens focused with the subjects listed; \"maintenance\" finds Reminders, which opens over the search; Back returns to the search with its query and results, then clears it, then returns to Home");
    }

    /** The Lock bar keeps its place and size while the top area changes moment. */
    private void moments() throws Exception {
        requireHome("CleanHomeScreen");
        Object clean = field(activity, "screen");
        await(() -> onMain(() -> ((Integer) field(clean, "moment")) == 0), "Clean Home does not show the battery card while parked");
        int[] parked = lockPlace();
        BleController ble = activity.service().ble;
        main(() -> { ble.setSimulationCharging(true); activity.redraw(); });
        await(() -> onMain(() -> { activity.redraw(); return ((Integer) field(clean, "moment")) == 1; }), "Clean Home does not show the charging view while the simulator charges");
        SystemClock.sleep(600); settle();
        require(Arrays.equals(parked, lockPlace()), "The Lock bar moved for the charging view: " + Arrays.toString(parked) + " -> " + Arrays.toString(lockPlace()));
        screenshot("moment-charging");
        main(() -> { ble.setSimulationCharging(false); activity.redraw(); });
        await(() -> onMain(() -> { activity.redraw(); return ((Integer) field(clean, "moment")) == 0; }), "Clean Home did not return to the battery card");
        SystemClock.sleep(600); settle();
        require(Arrays.equals(parked, lockPlace()), "The Lock bar moved back from the charging view: " + Arrays.toString(parked) + " -> " + Arrays.toString(lockPlace()));
        // Just back needs a ride saved in the last hour; its card is shown directly here, for the layout only (no ride).
        main(() -> invoke(clean, "show", new Class<?>[]{int.class}, 2)); settle();
        int[] justBack = lockPlace();
        main(() -> invoke(clean, "show", new Class<?>[]{int.class}, 0)); settle();
        require(Arrays.equals(parked, justBack), "The Lock bar moved for the Just back card: " + Arrays.toString(parked) + " -> " + Arrays.toString(justBack));
        checks.add("Moments: the Lock bar and the top area keep their place and size for the battery card, charging and Just back");
    }

    /** Widgets Home: the dock, each of its items, and its All sheet. */
    private void widgets() throws Exception {
        switchStyle(WIDGETS);
        requireHome("DashboardScreen");
        Object dock = dock();
        @SuppressWarnings("unchecked") List<String> shown = new ArrayList<>((List<String>) invoke(dock, "shown", new Class<?>[0]));
        if (arguments.containsKey("wide")) {
            boolean wide = Boolean.parseBoolean(arguments.getString("wide"));
            List<String> expected = wide ? HubPages.SUBJECTS : HubPages.dockSubjects(dockRoomDp(), textScale());
            require(shown.equals(expected), "The " + (wide ? "wide" : "phone") + " dock shows " + shown + ", expected " + expected);
        }
        require(!shown.isEmpty() && HubPages.SUBJECTS.containsAll(shown), "The dock shows " + shown);
        main(() -> require(shown(root().findViewWithTag("hub-dock")) && shown(root().findViewWithTag("hub-dock-handle")), "The dock or its All item is not shown"));
        screenshot("widgets-home");
        widgetsHeader(false);
        for (String subject : shown) {
            main(() -> { View item = root().findViewWithTag("hub-dock-" + subject); require(shown(item), "The dock has no " + subject + " item");
                require(item.getContentDescription() != null && item.getContentDescription().length() > 0, "The dock's " + subject + " item has no spoken name"); });
            tapTag("hub-dock-" + subject);
            arrived(subject, HubPages.HOME, subject);
            main(() -> require(field(activity, "dock") == null, "The dock shows on " + subject));
            systemBack();
            requireHome("DashboardScreen");
        }
        for (String subject : HubPages.SUBJECTS) if (!shown.contains(subject))
            main(() -> require(root().findViewWithTag("hub-dock-" + subject) == null, "The dock has an item for " + subject + ", which it does not list"));
        checks.add("Widgets dock: " + shown + " each open their subject over Home; Back returns to Widgets Home; no dock on subject pages");
        // All: the sheet with every subject; a row opens its subject and closes the sheet.
        for (String subject : HubPages.SUBJECTS) {
            tapTag("hub-dock-handle");
            AlertDialog[] sheet = new AlertDialog[1];
            await(() -> onMain(() -> { sheet[0] = (AlertDialog) field(dock(), "sheet"); return sheet[0] != null && sheet[0].isShowing(); }), "All did not open the subjects sheet");
            settle();
            main(() -> {
                View content = sheet[0].getWindow().getDecorView();
                require(content.findViewWithTag("hub-dock-sheet") != null, "The All sheet has no content tag");
                for (String each : HubPages.SUBJECTS) require(shown(content.findViewWithTag("home-subject-" + each)), "The All sheet does not list " + each);
            });
            if (subject.equals(HubPages.SUBJECTS.get(0))) screenshot("widgets-all-sheet");
            main(() -> click(sheet[0].getWindow().getDecorView().findViewWithTag("home-subject-" + subject), "home-subject-" + subject + " in the All sheet"));
            settle();
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
     * Widgets Home's header: the search and gear buttons both Homes have (product spec), opening Search and Settings.
     * Alone (-e mode widgets-header) it takes its own screenshot; in the main walk, widgets-home shows the header.
     */
    private void widgetsHeader(boolean alone) throws Exception {
        requireHome("DashboardScreen");
        if (alone) screenshot("widgets-header");
        main(() -> require(shown(root().findViewWithTag("home-search")), "Widgets Home's header has no search button (home-search)"));
        main(() -> require(shown(root().findViewWithTag("home-settings")), "Widgets Home's header has no gear for Settings (home-settings)"));
        tapTag("home-search");
        arrived(HubPages.SEARCH, HubPages.HOME, HubPages.SEARCH);
        hideKeyboard();
        systemBack();
        requireHome("DashboardScreen");
        tapTag("home-settings");
        arrived(HubPages.SETTINGS, HubPages.HOME, HubPages.SETTINGS);
        systemBack();
        requireHome("DashboardScreen");
        checks.add("Widgets Home's header: search opens Search and the gear opens Settings, each over Home");
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

    /** Opens a page by tapping the first shown view with one of the tags; where none shows (the simulated model hides it), by its route. */
    private void reach(String page, List<String> tags) throws Exception {
        String found = null;
        for (String tag : tags) {
            boolean[] visible = new boolean[1];
            main(() -> visible[0] = shown(root().findViewWithTag(tag)));
            if (visible[0]) { found = tag; break; }
        }
        if (found != null) tapTag(found);
        else { routed.add(page); open(page); }
    }
    private void open(String page) {
        main(() -> invoke(activity, "open", new Class<?>[]{String.class}, page));
        settle();
    }
    private void tapTag(String tag) {
        main(() -> click(root().findViewWithTag(tag), tag));
        settle();
    }
    /** A tap on view, or on its first clickable descendant, or its nearest clickable ancestor. */
    private void click(View view, String label) {
        require(shown(view), label + " is not shown");
        View target = view.isClickable() ? view : clickableChild(view);
        for (View at = view; target == null && at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null)
            if (at.isClickable()) target = at;
        require(target != null && target.isEnabled(), label + " cannot be tapped");
        target.performClick();
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
        settle();
    }

    /** The page shown is page, the stack is exactly stack, and a non-Home page has its back link. */
    private void arrived(String page, String... stack) {
        await(() -> onMain(() -> page.equals(field(activity, "selectedPage")) && !Boolean.TRUE.equals(field(activity, "rebuilding"))), "Expected page " + page + ", shown " + onMain(() -> field(activity, "selectedPage")));
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
    private int dockRoomDp() { return HubPages.dockRoom(activity.getResources().getConfiguration().screenWidthDp); }
    /** App text size times Android's font size, at least 1 (HubDock's scale). */
    private float textScale() { return Math.max(1f, activity.ui().textScale * activity.getResources().getConfiguration().fontScale); }

    /** The Lock bar's place in the window and its size, and the top area's height (px). */
    private int[] lockPlace() {
        return onMain(() -> {
            View lock = root().findViewWithTag("home-lock"), top = root().findViewWithTag("home-top-area");
            require(shown(lock) && shown(top), "Clean Home has no Lock bar or top area");
            int[] at = new int[2]; lock.getLocationInWindow(at);
            return new int[]{at[0], at[1], lock.getWidth(), lock.getHeight(), top.getHeight()};
        });
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
        Activity next = null;
        long end = SystemClock.uptimeMillis() + 10000;
        while ((next == null || next == before) && SystemClock.uptimeMillis() < end) next = waitForMonitorWithTimeout(monitor, 500);
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
        main(() -> activity.redraw()); settle();
    }

    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "hub-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }

    // ===== Plumbing =====

    private View root() { return activity.getWindow().getDecorView(); }
    private static boolean shown(View view) { return view != null && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0; }
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
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException next) { type = type.getSuperclass(); } }
            throw new NoSuchMethodException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
