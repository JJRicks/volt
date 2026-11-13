package com.jjricks.volt.tests;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/**
 * Disposable empty emulator, radio disabled. Home's Choose shortcut picker (Quick access and Setting tiles): its
 * collapsible sections (name, count, a chevron that turns; the current choice's section open, Ride when adding), the
 * search's flat list and "No matching" line, choosing, replacing and each widget's maximum, controls the simulated
 * scooter doesn't offer in a last closed section, the opening animation (and none with animations off), the scroll
 * that shows a low section's first rows, a sheet that never changes height, screen-reader headings with their
 * expanded state, and a turn of the screen mid-animation and mid-search (the picker reopens as it was). Where the
 * catalog has the Charge alerts shortcut: its tile's Off and level, its sheet for the selected scooter only, and a
 * model with a built-in charge limit (UT5 MAX) showing it unavailable. Sends no scooter command.
 *   -e theme dark|light  -e text_scale 1|1.4  -e language <shipped tag> (the picker in that language: sections, a
 *   search by English name, fit)  -e mode look (opens the picker and takes its pictures, no assertions about the
 *   sections: runs on any build, for before/after pictures of the picker).
 */
public final class ShortcutPickerValidation extends Instrumentation {
    private static final String ALERTS = "charge_alerts";
    private static final List<String> ORDER = Arrays.asList("ride", "battery", "lights", "line_lights", "star_lights", "security",
            "preferences", "pages", "menus", "experimental", "unavailable");
    private static final Map<String, String> ENGLISH = new HashMap<>();
    static {
        String[] names = {"ride", "Ride", "battery", "Battery", "lights", "Lights & sound", "line_lights", "Line lights", "star_lights", "Star lights",
                "security", "Security", "preferences", "Preferences", "pages", "Pages", "menus", "Menus", "experimental", "Experimental controls",
                "unavailable", "Not on this model"};
        for (int i = 0; i < names.length; i += 2) ENGLISH.put(names[i], names[i + 1]);
    }
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String ut5, gt3, language, oldAnimator, oldSize;
    private boolean admitted, languageSet;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>(), nodes = new ArrayList<>();

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            language = args.getString("language", "");
            admit(); admitted = true;
            oldAnimator = shell("settings get global animator_duration_scale").trim();
            animations(true);
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("developer_options", true)
                    .putString("theme", args.getString("theme", "dark")).putFloat("text_scale", Float.parseFloat(args.getString("text_scale", "1"))).commit(), "Could not seed preferences");
            boolean alerts = QuickAccessActions.find(ALERTS) != null;
            main(() -> {
                ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
                ScooterProfiles.Profile a = profiles.create("Synthetic UT5 MAX", "000000000000", 0); a.serial = "X2538-SYNTHETIC"; profiles.update(a); ut5 = a.id;
                ScooterProfiles.Profile b = profiles.create("Synthetic GT3 Max", "000000000001", 0); b.serial = "Q2345ZZ9ES7777777";
                b.advertisedPid = "24182"; b.advertisedPidWall = System.currentTimeMillis(); profiles.update(b); gt3 = b.id;
                for (String id : new String[]{ut5, gt3}) {
                    DashboardConfig config = new DashboardConfig(settings(), id);
                    config.widgets.clear();
                    DashboardConfig.Widget tiles = new DashboardConfig.Widget("shortcuts"), quick = new DashboardConfig.Widget("quick");
                    tiles.quickActions.clear(); tiles.quickActions.addAll(alerts ? Arrays.asList(ALERTS, "feature:headlight") : Arrays.asList("feature:headlight", "charge_done_by"));
                    quick.quickActions.clear(); quick.quickActions.addAll(Arrays.asList("lights", "stats", "feature:cruise_control"));
                    int y = 0;
                    for (DashboardConfig.Widget w : new DashboardConfig.Widget[]{tiles, quick}) {
                        for (int columns = 1; columns <= DashboardConfig.MAX_COLUMNS; columns++)
                            w.layouts.put(columns, new DashboardLayout.Cell(w.id, 0, y, columns, w.defaultHeight()));
                        y += w.defaultHeight(); config.widgets.add(w);
                    }
                    config.save();
                }
                profiles.select(gt3); OnboardingScreen.markDone(getTargetContext());
            });
            if (!language.isEmpty()) { setLocale(language); languageSet = true; }
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && root().getWidth() > 0, "App did not launch"); service = activity.service();
            if (!language.isEmpty()) main(() -> require(activity.getResources().getConfiguration().getLocales().get(0).getLanguage()
                    .equals(Locale.forLanguageTag(language).getLanguage()), "The app is not in " + language));
            home();
            if ("look".equals(args.getString("mode", ""))) look();
            else if (!language.isEmpty()) verifyLanguage();
            else {
                verifySections();
                verifyOpenClose();
                verifySearch();
                verifyChooseAndReplace();
                verifyAccessibility();
                verifyReducedMotion();
                verifyTurn();
                verifyUnavailable(alerts);
                if (alerts) verifyChargeAlerts();
                else checks.add("This catalog has no Charge alerts shortcut yet: its checks wait for the catalog change");
                verifyMaximums();
            }
            assertNoRadio();
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", failure.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) {}
        } finally {
            try { if (!nodes.isEmpty()) write("accessibility-nodes.txt", String.join("\n", nodes) + "\n"); } catch (Throwable ignored) {}
            if (oldSize != null) try { restoreSize(); } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            if (oldAnimator != null) try {
                shell(oldAnimator.equals("null") ? "settings delete global animator_duration_scale" : "settings put global animator_duration_scale " + oldAnimator);
            } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putStringArrayList("screenshots", screenshots);
            result.putBoolean("physical_device_tested", false); result.putInt("scooter_mutations", 0);
            if (admitted && activity != null) try { main(() -> { if (service != null) service.setSimulation(false); activity.finish(); ScooterService.setUiVisible(false);
                getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) {}
            if (languageSet) try { setLocale(""); } catch (Throwable ignored) {}
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    /** The picker as it opens for adding, top and scrolled, on any build (no section assertions). */
    private void look() throws Exception {
        openAdding("quick");
        shot("look-top");
        main(() -> { ScrollView scroll = first(sheet(), ScrollView.class); scroll.scrollTo(0, scroll.getHeight() * 2 / 3); });
        shot("look-scrolled");
        main(() -> { EditText search = first(sheet(), EditText.class); search.setText("light"); });
        shot("look-search");
        checks.add("Pictures of the picker as it opens for adding, scrolled, and searched for light");
    }

    private void verifySections() throws Exception {
        openAdding("quick");
        main(() -> {
            List<String> ids = sectionIds();
            require(!ids.isEmpty(), "No sections");
            int last = -1, rows = 0;
            for (String id : ids) {
                int at = ORDER.indexOf(id); require(at > last, "Sections out of order: " + ids); last = at;
                ViewGroup body = body(id);
                int count = Integer.parseInt(text(sheet().findViewWithTag("quick-section-count:" + id)).replaceAll("[^0-9]", ""));
                require(count > 0 && count == body.getChildCount(), id + ": the count " + count + " is not its " + body.getChildCount() + " rows");
                rows += count;
                require(ENGLISH.get(id).equals(text(sheet().findViewWithTag("quick-section-name:" + id))), id + " is named " + text(sheet().findViewWithTag("quick-section-name:" + id)));
                require(sheet().findViewWithTag("quick-section-header:" + id).getHeight() >= activity.ui().dp(48), id + " header is under 48dp");
            }
            require(!ids.contains("unavailable"), "Offline, a control is judged unavailable: " + ids);
            require(rows == QuickAccessActions.catalog().size(), "Rows " + rows + " for " + QuickAccessActions.catalog().size() + " shortcuts");
            for (QuickAccessActions.Entry entry : QuickAccessActions.catalog())
                require(sheet().findViewWithTag("quick-choice:" + entry.id) != null, "No row for " + entry.id);
            require(expanded().equals(Collections.singleton("ride")), "Adding should open Ride alone, not " + expanded());
            for (String id : quick("quick").quickActions) require(!sheet().findViewWithTag("quick-choice:" + id).isEnabled(), "A chosen shortcut is choosable: " + id);
            if (QuickAccessActions.find(ALERTS) != null) require(sectionOf(ALERTS).equals("battery"), "Charge alerts are not in Battery");
            fit("sections");
        });
        shot("add-sections");
        checks.add("Adding opens Ride alone; sections in order, each named, with a count equal to its rows; every shortcut once; chosen ones disabled");
    }

    private void verifyOpenClose() throws Exception {
        int[] sheetBox = sheetBox();
        // Opening animates the rows' height and turns the chevron.
        AtomicReference<Integer> start = new AtomicReference<>();
        main(() -> {
            View head = sheet().findViewWithTag("quick-section-header:menus");
            require(head.performClick(), "Menus header did not click");
            start.set(body("menus").getLayoutParams().height);
            require(body("menus").getVisibility() == View.VISIBLE && start.get() >= 0, "Menus did not start opening (height " + start.get() + ")");
        });
        SystemClock.sleep(90);
        AtomicReference<String> mid = new AtomicReference<>();
        main(() -> mid.set(body("menus").getLayoutParams().height + "px, chevron " + chevron("menus").getRotation()));
        settleMotion();
        main(() -> {
            require(settled("menus", true) && settled("ride", true), "Menus or Ride is not open and settled");
            require(expanded().equals(new HashSet<>(Arrays.asList("ride", "menus"))), "Open sections " + expanded());
        });
        checks.add("Menus opened from 0px over Motion.CHANGE_MS (mid-way " + mid.get() + "), ending open with its chevron up");
        // A low section opened from the sheet's bottom edge scrolls to show its first rows, never past its header.
        main(() -> {
            ScrollView scroll = scroll(); View head = sheet().findViewWithTag("quick-section-header:experimental");
            int top = top(head, scroll);
            scroll.scrollTo(0, Math.max(0, top + head.getHeight() - viewport(scroll)));
            require(head.performClick(), "Experimental header did not click");
        });
        settleMotion();
        main(() -> {
            ScrollView scroll = scroll(); View head = sheet().findViewWithTag("quick-section-header:experimental");
            ViewGroup rows = body("experimental");
            int top = top(head, scroll), shown = Math.min(3, rows.getChildCount());
            View lastShown = rows.getChildAt(shown - 1);
            int bottom = top(lastShown, scroll) + lastShown.getHeight();
            require(top >= scroll.getScrollY() - 1, "The header went above the top: " + top + " < " + scroll.getScrollY());
            require(bottom <= scroll.getScrollY() + viewport(scroll) + 1 || top <= scroll.getScrollY() + 1,
                    "Experimental's first rows are below the sheet: " + bottom + " > " + (scroll.getScrollY() + viewport(scroll)));
        });
        shot("open-low-section");
        main(() -> sheet().findViewWithTag("quick-section-header:menus").performClick());
        settleMotion();
        main(() -> require(settled("menus", false) && body("menus").getVisibility() == View.GONE, "Menus did not close"));
        require(Arrays.equals(sheetBox, sheetBox()), "The sheet moved or changed height: " + Arrays.toString(sheetBox) + " → " + Arrays.toString(sheetBox()));
        checks.add("A low section scrolls its header and first rows into view; closing returns to height 0; the sheet keeps its place and height");
    }

    private void verifySearch() throws Exception {
        int[] sheetBox = sheetBox();
        Set<String> before = new HashSet<>();
        main(() -> before.addAll(expanded()));
        main(() -> search().setText("cruise"));
        settle();
        main(() -> {
            require(sectionIds().isEmpty(), "Search left section headers");
            View cruise = sheet().findViewWithTag("quick-choice:feature:cruise_control");
            require(cruise != null && cruise.isShown(), "Search for cruise lost Cruise control");
            for (View row : rows()) require(texts(row).size() >= 2, "A search row has no category caption: " + texts(row));
            require(texts(cruise).contains("Ride"), "Cruise control is not captioned Ride: " + texts(cruise));
            fit("search");
        });
        shot("search");
        main(() -> search().setText("nothing_matches_this_fixture"));
        settle();
        main(() -> {
            require(sectionIds().isEmpty() && rows().isEmpty(), "Unmatched search shows rows or headers");
            ViewGroup list = (ViewGroup) sheet().findViewWithTag("quick-choices");
            require(list.getChildCount() == 1 && text(list.getChildAt(0)).equals("No matching settings"), "No \"No matching\" line");
        });
        shot("search-none");
        main(() -> search().setText(""));
        settle();
        main(() -> require(expanded().equals(before) && !sectionIds().isEmpty(), "Clearing the search did not restore " + before + ": " + expanded()));
        require(Arrays.equals(sheetBox, sheetBox()), "Searching changed the sheet's place or height");
        checks.add("Search lists matches flat, captioned with their category, \"No matching settings\" with no headers; clearing restores the open sections");
    }

    private void verifyChooseAndReplace() throws Exception {
        DashboardConfig.Widget quick = quick("quick");
        int size = quick.quickActions.size();
        AtomicReference<String> chosen = new AtomicReference<>();
        main(() -> {
            for (View row : rows("ride")) if (row.isEnabled()) { chosen.set(tagId(row)); row.performClick(); break; }
            require(chosen.get() != null, "Nothing to add in Ride");
        });
        settleMotion();
        main(() -> {
            DashboardConfig.Widget now = quick("quick");
            require(now.quickActions.size() == size + 1 && now.quickActions.get(size).equals(chosen.get()), "Choosing did not add " + chosen.get() + ": " + now.quickActions);
            require(sheet().findViewWithTag("quick-options-rows") != null, "Choosing did not return to the shortcut list");
        });
        main(() -> sheet().findViewWithTag("quick-select:0").performClick());
        settleMotion();
        main(() -> {
            require(expanded().equals(Collections.singleton("menus")), "Replacing Lights should open Menus alone: " + expanded());
            require(sheet().findViewWithTag("quick-choice:lights").isEnabled() && !sheet().findViewWithTag("quick-choice:stats").isEnabled(), "Replace markers");
            fit("replace");
        });
        shot("replace-opens-menus");
        main(() -> sheet().findViewWithTag("quick-choice:more:About").performClick());
        settleMotion();
        main(() -> require(quick("quick").quickActions.get(0).equals("more:About"), "Replacing did not keep the place: " + quick("quick").quickActions));
        checks.add("Choosing adds at the end; replacing opens the current choice's section and keeps its place");
    }

    private void verifyAccessibility() throws Exception {
        openAdding("quick");
        main(() -> {
            for (String id : sectionIds()) {
                View head = sheet().findViewWithTag("quick-section-header:" + id);
                AccessibilityNodeInfo info = head.createAccessibilityNodeInfo();
                boolean open = body(id).getVisibility() == View.VISIBLE;
                List<Integer> actions = new ArrayList<>(); for (AccessibilityNodeInfo.AccessibilityAction a : info.getActionList()) actions.add(a.getId());
                StringBuilder line = new StringBuilder(id).append(": heading=").append(Build.VERSION.SDK_INT >= 28 && info.isHeading())
                        .append(" clickable=").append(info.isClickable()).append(" spoken=\"").append(String.join(" ", texts(head))).append('"');
                line.append(" expand=").append(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.getId()))
                        .append(" collapse=").append(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.getId()));
                if (Build.VERSION.SDK_INT >= 36) line.append(" expandedState=").append(info.getExpandedState());
                nodes.add(line.toString());
                require(Build.VERSION.SDK_INT < 28 || info.isHeading(), id + " is not a heading");
                require(info.isClickable() && actions.contains((open ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND).getId()), id + " has no " + (open ? "collapse" : "expand") + " action");
                if (Build.VERSION.SDK_INT >= 36) require(info.getExpandedState() == (open ? AccessibilityNodeInfo.EXPANDED_STATE_FULL : AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED),
                        id + " expanded state " + info.getExpandedState());
                require(texts(head).size() >= 2, id + " header does not say its name and count: " + texts(head));
            }
            View head = sheet().findViewWithTag("quick-section-header:battery");
            require(head.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.getId(), null), "Expand action refused");
        });
        settleMotion();
        main(() -> {
            require(settled("battery", true), "The expand action did not open Battery");
            require(!sheet().findViewWithTag("quick-section-header:battery").performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.getId(), null),
                    "Expanding an open section was accepted");
            require(sheet().findViewWithTag("quick-section-header:battery").performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.getId(), null), "Collapse refused");
        });
        settleMotion();
        main(() -> require(settled("battery", false), "The collapse action did not close Battery"));
        checks.add("Each header is a clickable heading that says its name and count, offers Expand or Collapse by state"
                + (Build.VERSION.SDK_INT >= 36 ? " and reports its expanded state" : "") + "; both actions work (node dump in accessibility-nodes.txt)");
    }

    private void verifyReducedMotion() throws Exception {
        animations(false);
        main(() -> {
            require(!ValueAnimator.areAnimatorsEnabled(), "Animations still on");
            require(sheet().findViewWithTag("quick-section-header:lights").performClick(), "Lights header did not click");
            require(settled("lights", true), "With animations off, Lights did not open at once");
            require(sheet().findViewWithTag("quick-section-header:lights").performClick(), "Lights header did not click again");
            require(settled("lights", false), "With animations off, Lights did not close at once");
        });
        animations(true);
        checks.add("With animations off, a section opens and closes at once, chevron included");
    }

    /** A turn of the screen mid-animation, then mid-search: Home is rebuilt and the picker reopens as it was. */
    private void verifyTurn() throws Exception {
        main(() -> require(settled("ride", true), "Ride is not open before the turn"));
        String size = shell("wm size");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Override size: (\\d+)x(\\d+)").matcher(size);
        if (!m.find()) { m = java.util.regex.Pattern.compile("Physical size: (\\d+)x(\\d+)").matcher(size); require(m.find(), "wm size: " + size); oldSize = "reset"; }
        else oldSize = m.group(1) + "x" + m.group(2);
        String turned = m.group(2) + "x" + m.group(1);
        boolean landscape = Integer.parseInt(m.group(2)) > Integer.parseInt(m.group(1));
        main(() -> require(sheet().findViewWithTag("quick-section-header:menus").performClick(), "Menus header did not click"));
        shell("wm size " + turned);
        await(() -> { View r = root(); return r.getWidth() > r.getHeight() == landscape && dialogShowing(); }, "The picker did not come back after the turn");
        settleMotion();
        main(() -> {
            require(expanded().equals(new HashSet<>(Arrays.asList("ride", "menus"))), "After the turn the open sections are " + expanded());
            for (String id : sectionIds()) require(settled(id, expanded().contains(id)), id + " is not settled after the turn");
            fit("turned");
        });
        shot("turned-mid-animation");
        main(() -> search().setText("light"));
        settle();
        restoreSize();
        await(() -> dialogShowing() && search() != null && "light".equals(search().getText().toString()), "The search text did not survive the turn back");
        settleMotion();
        main(() -> {
            require(sectionIds().isEmpty() && sheet().findViewWithTag("quick-choice:feature:headlight") != null, "The search's flat list did not come back");
            fit("turned-search");
        });
        shot("turned-back-search");
        main(() -> search().setText(""));
        settle();
        main(() -> require(expanded().equals(new HashSet<>(Arrays.asList("ride", "menus"))), "Clearing after the turn: " + expanded()));
        checks.add("Turning the screen mid-animation reopens the picker settled with the same sections open; mid-search it keeps the search text and results");
    }

    /** A simulated unknown model: the controls it lacks go last, closed, named Not on this model, still choosable. */
    private void verifyUnavailable(boolean alerts) throws Exception {
        main(() -> { require(service.setSimulation(true), "Simulation did not start"); service.ble.setSimulationModel("39991"); service.ble.tickSimulation(); });
        settle(); home();
        openAdding("quick");
        main(() -> {
            List<String> ids = sectionIds();
            require(ids.get(ids.size() - 1).equals("unavailable"), "Simulated 39991 has no last unavailable section: " + ids);
            require(!expanded().contains("unavailable"), "The unavailable section starts open");
            for (View row : rows("unavailable")) require(textViews(row).size() >= 2, "An unavailable row has no caption: " + texts(row));
            if (alerts) require(sectionOf(ALERTS).equals("battery"), "A model without a charge limit has Charge alerts unavailable");
            sheet().findViewWithTag("quick-section-header:unavailable").performClick();
        });
        settleMotion();
        main(() -> fit("unavailable"));
        shot("unavailable-open");
        main(() -> { service.ble.setSimulationModel(""); service.ble.tickSimulation(); });
        settle(); home();
        openAdding("quick");
        main(() -> {
            List<String> ids = sectionIds();
            if (alerts) require(sectionOf(ALERTS).equals("unavailable") && ids.get(ids.size() - 1).equals("unavailable"), "The simulated UT5 MAX offers Charge alerts: " + ids);
            else require(!ids.contains("unavailable") || !rows("unavailable").isEmpty(), "Empty unavailable section");
        });
        main(() -> { service.ble.setSimulationModel(""); require(service.setSimulation(false), "Simulation did not stop"); });
        settle(); home();
        checks.add("Simulating an unknown model puts what it lacks in a last, closed, captioned section; the simulated UT5 MAX "
                + (alerts ? "puts Charge alerts there" : "has no empty section"));
    }

    private void verifyChargeAlerts() throws Exception {
        choose(gt3);
        main(() -> require(service.chargeAlertsOffered(), "The GT3 Max offline does not offer alerts"));
        main(() -> require(service.setChargeAlerts(ChargeAlertPolicy.Settings.OFF), "Could not clear alerts"));
        // Home's body refresh is paced to once a second (MainActivity.BODY_REFRESH_MS): wait for it, not a fixed pause.
        await(() -> texts(tile(ALERTS)).contains("Off") && tile(ALERTS).getAlpha() == 1f, "The tile does not read Off");
        shot("alerts-tile-off");
        main(() -> require(service.setChargeAlerts(ChargeAlertPolicy.Settings.OFF.charge(true, 70)), "Could not save 70%"));
        await(() -> texts(tile(ALERTS)).contains("70%"), "The tile did not update to 70% within 10 s of saving");
        shot("alerts-tile-70");
        tap(tile(ALERTS));
        main(() -> require(alertSheet() != null, "The tile did not open the Charge alerts sheet"));
        shot("alerts-sheet-from-tile");
        choose(ut5);
        main(() -> require(alertSheet() == null, "The sheet stayed open for another scooter"));
        home();
        main(() -> {
            require(!service.chargeAlertsOffered(), "The UT5 MAX offers alerts");
            View cell = tile(ALERTS);
            require(texts(cell).contains("—") && !texts(cell).contains("70%") && !texts(cell).contains("Off"), "The UT5 MAX tile looks usable: " + texts(cell));
            require(String.join(" ", texts(cell)).toLowerCase(Locale.ROOT).contains("not on this model"), "The tile does not say why: " + texts(cell));
        });
        shot("alerts-tile-unavailable");
        tap(tile(ALERTS));
        SystemClock.sleep(500);
        main(() -> require(alertSheet() == null, "The UT5 MAX tile opened a sheet"));
        openAdding("shortcuts");
        main(() -> {
            require(sectionOf(ALERTS).equals("unavailable") && !expanded().contains("unavailable"), "The UT5 MAX picker offers Charge alerts");
            sheet().findViewWithTag("quick-section-header:unavailable").performClick();
        });
        settleMotion();
        shot("alerts-picker-ut5");
        main(() -> { require(service.setSimulation(true), "Simulation did not start"); service.ble.setSimulationModel("39991"); service.ble.tickSimulation(); });
        settle(); home();
        await(() -> service.chargeAlertsOffered() && texts(tile(ALERTS)).contains("Off"), "Simulating 39991 on the UT5 MAX's profile does not offer the alerts tile");
        main(() -> { service.ble.setSimulationModel(""); require(service.setSimulation(false), "Simulation did not stop"); });
        choose(gt3);
        checks.add("Charge alerts: Off, then 70% as saved; the tile opens the sheet; another scooter closes it; on the UT5 MAX the tile is muted with — and "
                + "Not on this model, opens nothing, and the picker puts it last; a simulated model without a limit offers it");
    }

    private void verifyMaximums() throws Exception {
        for (String type : new String[]{"quick", "shortcuts"}) {
            int most = DashboardConfig.maximumShortcuts(type);
            while (true) {
                main(() -> invoke(dashboard(), "quickOptions", new Class<?>[]{DashboardConfig.Widget.class}, quick(type)));
                settleMotion();
                AtomicReference<Boolean> full = new AtomicReference<>();
                main(() -> full.set(sheet().findViewWithTag("add-quick-shortcut") == null));
                if (full.get()) break;
                main(() -> sheet().findViewWithTag("add-quick-shortcut").performClick());
                settleMotion();
                if (type.equals("shortcuts")) { main(() -> fit("tiles-picker")); if (quick(type).quickActions.size() == 2) shot("tiles-picker"); }
                main(() -> {
                    for (QuickAccessActions.Entry entry : QuickAccessActions.catalog()) {
                        View row = sheet().findViewWithTag("quick-choice:" + entry.id);
                        if (row != null && row.isEnabled() && !entry.id.equals(ALERTS)) { row.performClick(); return; }
                    }
                    throw new IllegalStateException("Nothing left to add");
                });
                settleMotion();
                require(quick(type).quickActions.size() <= most, type + " passed its maximum");
            }
            require(quick(type).quickActions.size() == most, type + " stopped at " + quick(type).quickActions.size());
            main(() -> invoke(dashboard(), "dismiss"));
            settle();
        }
        checks.add("Quick access stops at 8 and Setting tiles at 4: no Add once full");
    }

    /** The picker in another language: sections named and fitting, a search by an English name. */
    private void verifyLanguage() throws Exception {
        openAdding("quick");
        main(() -> {
            require(!sectionIds().isEmpty() && expanded().equals(Collections.singleton("ride")), "Sections in " + language + ": " + sectionIds());
            for (String id : sectionIds()) require(!text(sheet().findViewWithTag("quick-section-name:" + id)).isEmpty(), id + " has no name");
            fit("language-sections");
        });
        shot("language-sections");
        main(() -> { for (String id : sectionIds()) if (!id.equals("ride")) sheet().findViewWithTag("quick-section-header:" + id).performClick(); });
        settleMotion();
        main(() -> fit("language-all-open"));
        main(() -> scroll().scrollTo(0, scroll().getChildAt(0).getHeight() / 3));
        shot("language-open");
        main(() -> search().setText("Cruise"));
        settle();
        main(() -> {
            View cruise = sheet().findViewWithTag("quick-choice:feature:cruise_control");
            require(cruise != null && cruise.isShown(), "Searching the English name in " + language + " lost Cruise control");
            fit("language-search");
        });
        shot("language-search");
        checks.add("In " + language + ": sections open and closed fit with no clipped or broken words; the English name still finds its shortcut");
    }

    // Picker queries (call on the main thread).
    private View sheet() { AlertDialog d = (AlertDialog) field(dashboard(), "dialog"); require(d != null && d.isShowing(), "No sheet open"); return d.getWindow().getDecorView(); }
    /** On the main thread: the picker's sheet is showing. */
    private boolean dialogShowing() {
        Object screen = field(activity, "screen"); if (!(screen instanceof DashboardScreen)) return false;
        Object d = field(screen, "dialog");
        return d != null && ((AlertDialog) d).isShowing() && ((AlertDialog) d).getWindow().getDecorView().findViewWithTag("quick-choices") != null;
    }
    private ScrollView scroll() { return (ScrollView) sheet().findViewWithTag("quick-choices").getParent(); }
    private EditText search() { return first(sheet(), EditText.class); }
    private List<String> sectionIds() {
        List<String> ids = new ArrayList<>(); ViewGroup list = (ViewGroup) sheet().findViewWithTag("quick-choices");
        for (int i = 0; i < list.getChildCount(); i++) { Object tag = list.getChildAt(i).getTag(); if (tag instanceof String && ((String) tag).startsWith("quick-section:")) ids.add(((String) tag).substring(14)); }
        return ids;
    }
    private ViewGroup body(String id) { View v = sheet().findViewWithTag("quick-section-rows:" + id); require(v instanceof ViewGroup, "No rows for " + id); return (ViewGroup) v; }
    private View chevron(String id) { ViewGroup head = (ViewGroup) sheet().findViewWithTag("quick-section-header:" + id); return head.getChildAt(head.getChildCount() - 1); }
    private Set<String> expanded() { Set<String> out = new HashSet<>(); for (String id : sectionIds()) if (body(id).getVisibility() == View.VISIBLE) out.add(id); return out; }
    /** Wholly open (or closed): no height of its own, full opacity, chevron at rest. */
    private boolean settled(String id, boolean open) {
        ViewGroup body = body(id);
        return body.getLayoutParams().height == ViewGroup.LayoutParams.WRAP_CONTENT && body.getAlpha() == 1f
                && body.getVisibility() == (open ? View.VISIBLE : View.GONE) && chevron(id).getRotation() == (open ? -90f : 90f);
    }
    private List<View> rows(String id) { List<View> out = new ArrayList<>(); ViewGroup body = body(id); for (int i = 0; i < body.getChildCount(); i++) out.add(body.getChildAt(i)); return out; }
    private List<View> rows() {
        List<View> out = new ArrayList<>(); ViewGroup list = (ViewGroup) sheet().findViewWithTag("quick-choices");
        for (int i = 0; i < list.getChildCount(); i++) if (tagId(list.getChildAt(i)) != null) out.add(list.getChildAt(i));
        return out;
    }
    private String tagId(View row) { Object tag = row.getTag(); return tag instanceof String && ((String) tag).startsWith("quick-choice:") ? ((String) tag).substring(13) : null; }
    private String sectionOf(String entry) { for (String id : sectionIds()) for (View row : rows(id)) if (entry.equals(tagId(row))) return id; return ""; }
    private DashboardConfig.Widget quick(String type) {
        for (DashboardConfig.Widget w : ((DashboardConfig) field(dashboard(), "config")).widgets) if (w.type.equals(type)) return w;
        throw new IllegalStateException("No " + type + " widget");
    }
    private View tile(String id) {
        View v = root().findViewWithTag("setting-tile:" + id); if (v == null) v = root().findViewWithTag("quick-action:" + id);
        require(v != null && v.isShown(), "No tile for " + id); return v;
    }
    private View alertSheet() {
        Object d = field(activity, "controlDialog");
        if (!(d instanceof AlertDialog) || !((AlertDialog) d).isShowing()) return null;
        return ((AlertDialog) d).getWindow().getDecorView().findViewWithTag("charge-alert-sheet");
    }
    /** Every shown text in the sheet fits its line: no clipping, no word broken mid-word, nothing ellipsized. */
    private void fit(String where) {
        List<String> problems = new ArrayList<>();
        for (TextView text : textViews(sheet())) if (text.isShown() && !(text instanceof EditText)) {
            TextFit.collectNarrowTextProblems(text, where + " " + text.getText(), problems);
            android.text.Layout layout = text.getLayout();
            if (layout != null) for (int line = 0; line < layout.getLineCount(); line++) if (layout.getEllipsisCount(line) != 0) problems.add(where + " ellipsized: " + text.getText());
        }
        require(problems.isEmpty(), "Text does not fit: " + problems);
    }
    private int[] sheetBox() { AtomicReference<int[]> out = new AtomicReference<>(); main(() -> { View panel = (View) scroll().getParent(); int[] at = new int[2]; panel.getLocationOnScreen(at); out.set(new int[]{at[1], panel.getHeight()}); }); return out.get(); }
    private int top(View view, View ancestor) { Rect r = new Rect(); view.getDrawingRect(r); ((ViewGroup) ancestor).offsetDescendantRectToMyCoords(view, r); return r.top; }
    private int viewport(ScrollView scroll) { return scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom(); }

    // Navigation.
    private DashboardScreen dashboard() { Object screen = field(activity, "screen"); require(screen instanceof DashboardScreen, "Home is not showing"); return (DashboardScreen) screen; }
    private void home() { main(() -> { invoke(activity, "select", new Class<?>[]{String.class}, "home"); activity.redraw(); }); settleMotion(); }
    private void choose(String id) { main(() -> { service.profiles.select(id); service.ble.profileSelected(); activity.rebuild(); }); settleMotion(); }
    private void openAdding(String type) {
        main(() -> invoke(dashboard(), "quickOptions", new Class<?>[]{DashboardConfig.Widget.class}, quick(type)));
        settleMotion();
        main(() -> { View add = sheet().findViewWithTag("add-quick-shortcut"); require(add != null, "No Add for " + type); add.performClick(); });
        settleMotion();
    }
    private void tap(View view) {
        int[] at = new int[2];
        main(() -> { view.getLocationOnScreen(at); at[0] += view.getWidth() / 2; at[1] += view.getHeight() / 2; });
        long when = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(when, when, MotionEvent.ACTION_DOWN, at[0], at[1], 0), up = MotionEvent.obtain(when, when + 70, MotionEvent.ACTION_UP, at[0], at[1], 0);
        try { sendPointerSync(down); SystemClock.sleep(70); sendPointerSync(up); } finally { down.recycle(); up.recycle(); }
        settleMotion();
    }
    private void animations(boolean on) {
        shell("settings put global animator_duration_scale " + (on ? "1" : "0"));
        long until = SystemClock.uptimeMillis() + 4000;
        while (ValueAnimator.areAnimatorsEnabled() != on && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
        require(ValueAnimator.areAnimatorsEnabled() == on, "Animator scale did not apply");
    }
    private void restoreSize() {
        String size = oldSize; oldSize = null;
        shell(size.equals("reset") ? "wm size reset" : "wm size " + size);
        SystemClock.sleep(800); waitForIdleSync();
    }
    private void setLocale(String tag) {
        LocaleManager manager = getTargetContext().getSystemService(LocaleManager.class);
        manager.setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        SystemClock.sleep(500); waitForIdleSync();
    }

    // Plumbing.
    private void admit() {
        String hw = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hw.contains("ranchu") || hw.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter"}) require(prefs(name).getAll().isEmpty(), "Refusing existing preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing app data");
        require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Grant emulator Nearby permission");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Disable emulator Bluetooth first");
        require(language.isEmpty() || Build.VERSION.SDK_INT >= 33, "A language run needs API 33+");
    }
    private void assertNoRadio() {
        main(() -> {
            for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(service.ble, name) == null, "Validation touched " + name);
            require(((Collection<?>) field(service.ble, "queue")).isEmpty(), "Validation queued radio bytes");
            require(!service.isRecording() && !service.isChargeRecording(), "The picker started a recording");
        });
        checks.add("No radio, no queued bytes, no recording");
    }
    private <V extends View> V first(View root, Class<V> type) {
        ArrayDeque<View> stack = new ArrayDeque<>(); stack.push(root);
        while (!stack.isEmpty()) { View v = stack.pop(); if (type.isInstance(v)) return type.cast(v); if (v instanceof ViewGroup) for (int i = ((ViewGroup) v).getChildCount() - 1; i >= 0; i--) stack.push(((ViewGroup) v).getChildAt(i)); }
        return null;
    }
    private List<TextView> textViews(View root) {
        List<TextView> out = new ArrayList<>(); ArrayDeque<View> stack = new ArrayDeque<>(); stack.push(root);
        while (!stack.isEmpty()) { View v = stack.pop(); if (v instanceof TextView) out.add((TextView) v); if (v instanceof ViewGroup) for (int i = ((ViewGroup) v).getChildCount() - 1; i >= 0; i--) stack.push(((ViewGroup) v).getChildAt(i)); }
        return out;
    }
    /** The shown texts under a view, in order, plus its own content description. */
    private List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        if (view.getContentDescription() != null) out.add(view.getContentDescription().toString());
        for (TextView t : textViews(view)) if (t.isShown() && t.length() > 0) out.add(t.getText().toString());
        return out;
    }
    private String text(View view) { require(view instanceof TextView, "Not text: " + view); return ((TextView) view).getText().toString(); }
    private void shot(String label) throws Exception { settle(); SystemClock.sleep(700); waitForIdleSync(); screenshot(label); }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View) field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(160); waitForIdleSync(); }
    /** Sheets slide in and sections open over Motion.CHANGE_MS; this waits them out. */
    private void settleMotion() { waitForIdleSync(); SystemClock.sleep(650); waitForIdleSync(); }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            try { main(() -> ready.set(condition.getAsBoolean())); } catch (RuntimeException notYet) { ready.set(false); }
            if (Boolean.TRUE.equals(ready.get())) return; SystemClock.sleep(80);
        }
        throw new IllegalStateException(message);
    }
    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new IllegalStateException(failure.get().getMessage(), failure.get());
    }
    private void require(boolean ok, String message) { assertions++; if (!ok) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException missing) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); }
        catch (Exception error) { throw new IllegalStateException("Field " + name, error); }
    }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... values) {
        try { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values); }
        catch (java.lang.reflect.InvocationTargetException thrown) { throw new IllegalStateException("Method " + name + ": " + thrown.getCause(), thrown.getCause()); }
        catch (Exception error) { throw new IllegalStateException("Method " + name, error); }
    }
    private String shell(String command) {
        try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int count; while ((count = in.read(buf)) >= 0) out.write(buf, 0, count); return out.toString("UTF-8");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private File evidence() { File dir = new File(getTargetContext().getExternalFilesDir(null), "shortcut-picker-validation"); require(dir.isDirectory() || dir.mkdirs(), "Evidence directory missing"); return dir; }
    private void write(String name, String text) throws Exception { try (FileOutputStream out = new FileOutputStream(new File(evidence(), name))) { out.write(text.getBytes(StandardCharsets.UTF_8)); } }
    private void screenshot(String label) throws Exception {
        String name = (language.isEmpty() ? "" : language + "-") + label;
        File file = new File(evidence(), name + ".png"); android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
}
