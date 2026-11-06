package com.jjricks.volt.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.PopupWindow;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.OnboardingScreen;
import com.jjricks.volt.ScooterProfiles;
import com.jjricks.volt.ScooterService;

/**
 * Fresh disposable emulator only. The Scooter tab's menu editor (scooter-menu-edit plan, PR 2) on the UT5 MAX
 * developer simulator (memory only, no frames, no Bluetooth): the header pencil opens the editor; a section moves
 * by its screen-reader action; holding a section's grip folds every section, a resting finger keeps the order and a
 * touch drag moves it; the eye hides a row with an Undo
 * notice; Done shows the new order and the hidden row on the live page; rotation and an Activity rebuild keep the
 * editor open and the layout; Reset with Undo, Add back and Back. The editor sends no scooter command.
 */
public final class ScooterMenuEditValidation extends Instrumentation {
    private static final String PKG = "com.jjricks.volt";
    private static final String MOVED = "Battery", ABOVE = "Ride", ROW = "traction_control", TOP_SPEED = "speed", LAYOUT = "scooter_menu_v1_";
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putString("theme", arguments.getString("theme", "dark")).putString("units", "mi")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed(); launch();
            main(() -> require(activity.service().setSimulation(true), "Simulation did not start")); settle();
            scooterPage();
            editAndMove();
            foldWhileHeld();
            hideRow();
            rotateWhileEditing();
            done();
            footerAndSearch();
            rebuildKeepsLayout();
            resetAndUndo();
            addBack();
            backFinishes();
            main(() -> require(activity.service().setSimulation(false), "Simulation did not stop")); settle();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false);
            if (open != null) try { touch((long)open[0], MotionEvent.ACTION_UP, open[1], open[2]); } catch (Throwable ignored) { }
            try { getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE); } catch (Throwable ignored) { }
            if (admitted && activity != null) try { main(() -> { if (activity.service() != null) activity.service().setSimulation(false); activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }

    // ---- steps ----
    private void scooterPage() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        main(() -> { activity.service().ble.tickSimulation(); activity.redraw(); }); settle();
        main(() -> {
            require(screen().getClass().getSimpleName().equals("ControlsScreen"), "Scooter page is not active");
            require(editAction().getVisibility() == View.VISIBLE, "No pencil in the Scooter header");
            require(String.valueOf(editAction().getContentDescription()).equals("Edit menu"), "Pencil says " + editAction().getContentDescription());
            require(menu("isDefault").equals(Boolean.TRUE), "Fresh menu is not the default");
            require(liveOrder().indexOf(ABOVE) < liveOrder().indexOf(MOVED), "Default live order " + liveOrder());
        });
        screenshot("live-default");
        // Lights page: no pencil (owner, Q38 B: no Lights sub-page editing).
        main(() -> invoke(screen(), "showLights")); settle(); SystemClock.sleep(400); settle();
        main(() -> require(editAction().getVisibility() != View.VISIBLE, "Pencil shown on the Lights page"));
        main(() -> invoke(screen(), "showMain")); settle(); SystemClock.sleep(400); settle();
        main(() -> require(editAction().getVisibility() == View.VISIBLE, "Pencil did not come back on the main page"));
        checks.add("Pencil on the Scooter main page only (not Lights); fresh menu is the default order");
    }

    private void editAndMove() throws Exception {
        main(() -> editAction().performClick());
        await(() -> editor() != null && editorView().isShown(), "Editor did not open");
        settle();
        main(() -> {
            require(settings().getBoolean("scooter_menu_editing", false), "Editing flag not saved");
            require(String.valueOf(editAction().getContentDescription()).equals("Finish editing menu"), "Done says " + editAction().getContentDescription());
            require(!((View)field(screen(), "view")).isShown(), "Live controls still shown while editing");
            require(editorOrder().indexOf(ABOVE) < editorOrder().indexOf(MOVED), "Editor order " + editorOrder());
            require(editorOrder().contains(TOP_SPEED), "Top speed is not its own section in the editor: " + editorOrder());
            // Maintenance (#257) names its own label; the editor must show that name, not the section id.
            require(editorOrder().contains("maintenance"), "No Maintenance section in the editor: " + editorOrder());
            String said = String.valueOf(header("maintenance").getContentDescription());
            require(said.startsWith("Maintenance section, "), "Maintenance header says " + said);
        });
        screenshot("editor");
        int up = getTargetContext().getResources().getIdentifier("reorder_move_up", "id", PKG);
        require(up != 0, "No reorder_move_up id");
        List<String> before = new ArrayList<>();
        main(() -> before.addAll(editorOrder()));
        main(() -> {
            View header = header(MOVED);
            AccessibilityNodeInfo info = header.createAccessibilityNodeInfo();
            boolean listed = false, hide = false;
            for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
                if (action.getId() == up) listed = true;
                if (String.valueOf(action.getLabel()).equals("Hide " + MOVED)) hide = true;
            }
            require(listed && hide, MOVED + " header lacks Move up or Hide actions: " + info.getActionList());
            require(String.valueOf(info.getContentDescription()).startsWith(MOVED + " section, "), MOVED + " header says " + info.getContentDescription());
            require(header.performAccessibilityAction(up, null), "Move up refused");
        });
        await(() -> sectionOrder().indexOf(MOVED) < sectionOrder().indexOf(ABOVE), "Move up did not change the saved order: " + sectionOrder());
        settle(); SystemClock.sleep(400); settle();
        main(() -> {
            List<String> after = editorOrder(), wanted = new ArrayList<>(before);
            int at = wanted.indexOf(MOVED); wanted.remove(at); wanted.add(at - 1, MOVED);
            require(after.equals(wanted), "Editor order after Move up " + after + ", wanted " + wanted);
            require(settings().getString(LAYOUT + profileId(), "").contains(MOVED), "Moved layout not saved");
        });
        checks.add("Pencil opens the editor (flag saved); " + MOVED + " moves above " + ABOVE + " by its Move up action; the layout saves at once");
    }

    private void foldWhileHeld() throws Exception {
        // MOVED is not the first section, and folding Top speed above it moves it up while the finger stays still.
        main(() -> require(editorOrder().indexOf(MOVED) > 0, MOVED + " is the first section: " + editorOrder()));
        int[] before = new int[1];
        main(() -> { int[] at = new int[2]; header(MOVED).getLocationOnScreen(at); before[0] = at[1]; });
        float[] finger = press(MOVED);
        long down = (long)finger[2];
        main(() -> {
            for (Object body : ((Map<?, ?>)field(editor(), "bodies")).values())
                require(((View)body).getVisibility() == View.GONE, "A section's rows stayed open while folded");
            int[] at = new int[2]; header(MOVED).getLocationOnScreen(at);
            require(at[1] < before[0], MOVED + " did not move up in the fold (" + before[0] + " to " + at[1] + "), so this would not test the anchor");
        });
        screenshot("editor-folded");
        // A finger that stays put, then jitters a little (under the touch slop), must not move the section.
        float x = finger[0], y = finger[1];
        for (float jitter : new float[]{0, 0, 3, -4, 6, -6, 2, 0}) { touch(down, MotionEvent.ACTION_MOVE, x, y + jitter); SystemClock.sleep(30); }
        main(() -> require(((View)editorView().findViewWithTag(MOVED)).getTranslationY() == 0, MOVED + " moved under a resting finger"));
        touch(down, MotionEvent.ACTION_UP, x, y);
        await(() -> !Boolean.TRUE.equals(field(editor(), "folded")), "Releasing the grip did not open the sections");
        settle(); SystemClock.sleep(500); settle();
        main(() -> require(sectionOrder().indexOf(MOVED) < sectionOrder().indexOf(ABOVE) && editorOrder().indexOf(MOVED) < editorOrder().indexOf(ABOVE),
                "A resting finger through the fold changed the order: " + sectionOrder()));
        checks.add("Holding " + MOVED + "'s grip folds every section; a resting or jittering finger through the fold keeps the order");
        // A deliberate drag still follows the finger: down past the next section, then back up.
        dragSection(MOVED, 3);
        main(() -> require(sectionOrder().indexOf(MOVED) > sectionOrder().indexOf(ABOVE), "Dragging " + MOVED + " down did not pass " + ABOVE + ": " + sectionOrder()));
        dragSection(MOVED, -3);
        main(() -> require(sectionOrder().indexOf(MOVED) < sectionOrder().indexOf(ABOVE) && editorOrder().indexOf(MOVED) < editorOrder().indexOf(ABOVE),
                "Dragging " + MOVED + " back up did not pass " + ABOVE + ": " + sectionOrder()));
        checks.add("Dragging " + MOVED + " by touch moves it below " + ABOVE + " and back; each drop saves");
    }

    /** Presses a section's grip and waits until the sections have folded and the section has lifted: x, y, down time. */
    private float[] press(String section) {
        // Bring the header to the upper third of the page first: below the fold it may sit off screen or under the tab bar.
        main(() -> {
            View scroll = (View)field(activity, "scroll"); int[] page = new int[2], at = new int[2];
            scroll.getLocationOnScreen(page); header(section).getLocationOnScreen(at);
            scroll.scrollBy(0, at[1] - page[1] - scroll.getHeight() / 3);
        });
        settle();
        View[] grip = new View[1];
        main(() -> grip[0] = gripOf(header(section)));
        int[] at = new int[2];
        main(() -> grip[0].getLocationOnScreen(at));
        float x = at[0] + grip[0].getWidth() / 2f, y = at[1] + grip[0].getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, x, y);
        await(() -> Boolean.TRUE.equals(field(editor(), "folded")), "Holding a section's grip did not fold the sections");
        await(() -> field(field(editor(), "sections"), "held") != null && !Boolean.TRUE.equals(field(field(editor(), "sections"), "waiting")), "The section did not lift after the fold");
        return new float[]{x, y, down};
    }
    /** Drags a section by {@code sections} folded section heights (down when positive), in small steps, and drops it. */
    private void dragSection(String section, int sections) throws Exception {
        float[] finger = press(section);
        long down = (long)finger[2];
        int[] pitch = new int[1];
        main(() -> pitch[0] = ((View)editorView().findViewWithTag(section)).getHeight() + ((ViewGroup.MarginLayoutParams)((View)editorView().findViewWithTag(section)).getLayoutParams()).bottomMargin);
        float x = finger[0], y = finger[1], total = sections * pitch[0];
        for (int step = 1; step <= 20; step++) { touch(down, MotionEvent.ACTION_MOVE, x, y + total * step / 20f); SystemClock.sleep(25); }
        SystemClock.sleep(300);
        touch(down, MotionEvent.ACTION_UP, x, y + total);
        await(() -> !Boolean.TRUE.equals(field(editor(), "folded")) && field(field(editor(), "sections"), "held") == null, "The dragged section did not settle");
        settle(); SystemClock.sleep(500); settle();
    }
    /** The open gesture (down time, x, y), so a failed check still lifts the finger: the system keeps an injected pointer down otherwise. */
    private float[] open;
    private void touch(long down, int action, float x, float y) {
        inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0));
        open = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? null : new float[]{down, x, y};
    }

    private void hideRow() throws Exception {
        main(() -> {
            View item = editorView().findViewWithTag(ROW);
            require(item != null && item.isShown(), "No " + ROW + " row in the editor");
            View eye = item.findViewWithTag("menu_hide");
            require(eye != null, "No eye on " + ROW);
            require(eye.performClick(), "Eye did not click");
        });
        settle();
        main(() -> {
            require(Boolean.TRUE.equals(invoke(menu(), "rowHidden", new Class<?>[]{String.class}, ROW)), ROW + " not hidden in the layout");
            require(editorView().findViewWithTag(ROW) == null, ROW + " still listed in the editor");
            require(noticeButton() != null, "No Undo notice after hiding");
        });
        screenshot("editor-hidden-notice");
        // Undo brings it back; hide it again for the live-page check.
        main(() -> noticeButton().performClick()); settle();
        main(() -> require(!Boolean.TRUE.equals(invoke(menu(), "rowHidden", new Class<?>[]{String.class}, ROW)) && editorView().findViewWithTag(ROW) != null, "Undo did not bring " + ROW + " back"));
        main(() -> require(editorView().findViewWithTag(ROW).findViewWithTag("menu_hide").performClick(), "Eye did not click")); settle();
        main(() -> require(Boolean.TRUE.equals(invoke(menu(), "rowHidden", new Class<?>[]{String.class}, ROW)), ROW + " not hidden again"));
        checks.add("The eye hides " + ROW + " with an Undo notice; Undo shows it again");
    }

    private void rotateWhileEditing() throws Exception {
        List<String> before = new ArrayList<>();
        main(() -> before.addAll(sectionOrder()));
        int start = rotate();
        await(() -> editor() != null && editorView().isShown(), "Editor closed on rotation");
        main(() -> {
            require(sectionOrder().equals(before), "Rotation changed the order " + sectionOrder());
            require(editorView().findViewWithTag(ROW) == null, ROW + " came back on rotation");
            require(editorOrder().indexOf(MOVED) < editorOrder().indexOf(ABOVE), "Rotated editor order " + editorOrder());
        });
        screenshot("editor-rotated");
        rotateBack(start);
        await(() -> editor() != null && editorView().isShown(), "Editor closed on rotating back");
        checks.add("Rotating while editing keeps the editor open, the order and the hidden row");
    }

    private void done() throws Exception {
        main(() -> editAction().performClick());
        await(() -> editor() == null && ((View)field(screen(), "view")).isShown(), "Done did not close the editor");
        settle(); SystemClock.sleep(400); settle();
        main(() -> {
            require(!settings().getBoolean("scooter_menu_editing", true), "Editing flag still set after Done");
            List<String> live = liveOrder();
            require(live.indexOf(MOVED) >= 0 && live.indexOf(MOVED) < live.indexOf(ABOVE), "Live order after Done " + live);
            require(((View)field(row(ROW), "card")).getVisibility() != View.VISIBLE, ROW + " still on the live page");
            require(String.valueOf(editAction().getContentDescription()).equals("Edit menu"), "Pencil did not come back");
        });
        screenshot("live-edited");
        checks.add("Done: the live page shows " + MOVED + " before " + ABOVE + " and no " + ROW + " row; the editing flag clears");
    }

    // PR 3, the edges: the live page's footer and search over hidden settings.
    private void footerAndSearch() throws Exception {
        String dot = " \u00b7 ";
        main(() -> {
            require(footer().getVisibility() == View.VISIBLE, "No hidden-settings footer on the live page");
            require(footer().getText().toString().equals("1 setting hidden" + dot + "Edit"), "Footer says " + footer().getText());
            require(String.valueOf(footer().getContentDescription()).equals("1 setting hidden. Edit menu"), "Footer is read as " + footer().getContentDescription());
            require(footer().getParent() == field(screen(), "page"), "Footer is not under the whole page");
            View caption = card(ROW).findViewWithTag("menu_hidden_caption");
            require(caption == null || caption.getVisibility() != View.VISIBLE, "Hidden caption outside a search");
        });
        main(() -> ((android.widget.ScrollView)field(activity, "scroll")).fullScroll(View.FOCUS_DOWN)); settle();
        main(() -> require(footer().isShown() && onScreen(footer()), "Footer is not at the bottom of the page"));
        screenshot("live-footer");
        // Search still finds the hidden row: it says Hidden, and its control works.
        main(() -> searchField().setText("traction")); settle();
        main(() -> {
            require(card(ROW).getVisibility() == View.VISIBLE, ROW + " not found by search");
            View caption = card(ROW).findViewWithTag("menu_hidden_caption");
            require(caption != null && caption.isShown(), "No Hidden caption on " + ROW);
            require(((android.widget.TextView)caption).getText().toString().equals("Hidden"), "Caption says " + ((android.widget.TextView)caption).getText());
            require(((View)field(row(ROW), "action")).isEnabled(), ROW + " is not usable from search");
            require(footer().getVisibility() != View.VISIBLE, "Footer shown during a search");
            require(editAction().getVisibility() != View.VISIBLE, "Pencil shown during a search");
        });
        main(() -> ((android.widget.ScrollView)field(activity, "scroll")).scrollTo(0, 0)); settle();
        screenshot("search-hidden");
        // A shown row found by the same kind of search has no caption.
        main(() -> searchField().setText("cruise")); settle();
        main(() -> {
            require(card("cruise_control").getVisibility() == View.VISIBLE, "cruise_control not found by search");
            View caption = card("cruise_control").findViewWithTag("menu_hidden_caption");
            require(caption == null || caption.getVisibility() != View.VISIBLE, "Hidden caption on a shown row");
        });
        // A hidden section: search finds its rows, each captioned; the footer counts its rows.
        main(() -> { invoke(menu(), "hide", new Class<?>[]{String.class}, "Battery"); invoke(screen(), "applyFilter"); });
        main(() -> searchField().setText("charge")); settle();
        main(() -> {
            require(card("charge_limit").getVisibility() == View.VISIBLE, "charge_limit in a hidden section not found by search");
            View caption = card("charge_limit").findViewWithTag("menu_hidden_caption");
            require(caption != null && caption.isShown(), "No Hidden caption in a hidden section");
        });
        screenshot("search-hidden-section");
        main(() -> invoke(screen(), "clearSearch")); settle();
        main(() -> {
            require(card("charge_limit").getVisibility() != View.VISIBLE, "Hidden section shown after the search");
            String said = footer().getText().toString();
            require(said.matches("[0-9]+ settings hidden" + dot + "Edit") && Integer.parseInt(said.split(" ")[0]) > 2, "Footer with a hidden section says " + said);
        });
        main(() -> { invoke(menu(), "show", new Class<?>[]{String.class}, "Battery"); invoke(screen(), "applyFilter"); }); settle();
        main(() -> {
            require(card(ROW).getVisibility() != View.VISIBLE, ROW + " shown after the search");
            require(footer().getVisibility() == View.VISIBLE && footer().getText().toString().startsWith("1 setting hidden"), "Footer after the search says " + footer().getText());
        });
        // Not on the Lights page.
        main(() -> invoke(screen(), "showLights")); settle(); SystemClock.sleep(400); settle();
        main(() -> require(footer().getVisibility() != View.VISIBLE, "Footer shown on the Lights page"));
        main(() -> invoke(screen(), "showMain")); settle(); SystemClock.sleep(400); settle();
        main(() -> require(footer().getVisibility() == View.VISIBLE, "Footer did not come back on the main page"));
        // The footer opens the editor; Done closes it again.
        main(() -> require(footer().performClick(), "Footer did not click"));
        await(() -> editor() != null && editorView().isShown(), "Footer did not open the editor");
        settle();
        main(() -> require(footer().getVisibility() != View.VISIBLE && settings().getBoolean("scooter_menu_editing", false), "Footer stayed while editing"));
        main(() -> editAction().performClick());
        await(() -> editor() == null && ((View)field(screen(), "view")).isShown(), "Done did not close the editor");
        settle(); SystemClock.sleep(400); settle();
        checks.add("Footer: '1 setting hidden \u00b7 Edit' under the whole page, not on Lights or during a search; it opens the editor");
        checks.add("Search finds the hidden row and a hidden section's rows with a Hidden caption, the control usable; shown rows have none");
    }
    private android.widget.TextView footer() { return (android.widget.TextView)field(screen(), "menuFooter"); }
    private android.widget.EditText searchField() { return (android.widget.EditText)field(screen(), "search"); }
    private View card(String key) { return (View)field(row(key), "card"); }
    private boolean onScreen(View view) {
        android.graphics.Rect seen = new android.graphics.Rect();
        return view.getGlobalVisibleRect(seen) && seen.height() == view.getHeight();
    }

    private void rebuildKeepsLayout() throws Exception {
        int start = rotate();
        main(() -> require(editor() == null && liveOrder().indexOf(MOVED) < liveOrder().indexOf(ABOVE) && ((View)field(row(ROW), "card")).getVisibility() != View.VISIBLE, "Rotated live page lost the layout: " + liveOrder()));
        rotateBack(start);
        // A new Activity loads the saved layout (and, editing, reopens the editor).
        main(() -> editAction().performClick());
        await(() -> editor() != null, "Editor did not open again");
        recreate();
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "controls")); settle();
        await(() -> screen().getClass().getSimpleName().equals("ControlsScreen") && editor() != null && editorView().isShown(), "A rebuilt Activity did not reopen the editor");
        main(() -> require(editorOrder().indexOf(MOVED) < editorOrder().indexOf(ABOVE) && editorView().findViewWithTag(ROW) == null, "A rebuilt Activity lost the layout: " + editorOrder()));
        checks.add("Rotating the live page keeps the layout; a rebuilt Activity reopens the editor with the saved layout");
    }

    private void resetAndUndo() throws Exception {
        String[] saved = new String[1];
        main(() -> saved[0] = settings().getString(LAYOUT + profileId(), ""));
        main(() -> require(editorView().findViewWithTag("menu_reset").performClick(), "Reset did not click")); settle();
        main(() -> {
            require(Boolean.TRUE.equals(menu("isDefault")), "Reset left a changed layout");
            require(editorOrder().indexOf(ABOVE) < editorOrder().indexOf(MOVED) && editorView().findViewWithTag(ROW) != null, "Editor after Reset " + editorOrder());
            require(noticeButton() != null, "No Undo notice after Reset");
        });
        screenshot("editor-reset-notice");
        main(() -> noticeButton().performClick()); settle(); SystemClock.sleep(300); settle();
        main(() -> {
            require(!Boolean.TRUE.equals(menu("isDefault")), "Undo did not restore the layout");
            require(sectionOrder().indexOf(MOVED) < sectionOrder().indexOf(ABOVE), "Order after Undo " + sectionOrder());
            require(Boolean.TRUE.equals(invoke(menu(), "rowHidden", new Class<?>[]{String.class}, ROW)), ROW + " not hidden after Undo");
            require(editorView().findViewWithTag(ROW) == null, ROW + " listed after Undo");
            require(settings().getString(LAYOUT + profileId(), "").equals(saved[0]), "Undo saved a different layout");
        });
        checks.add("Reset puts the default menu back with an Undo notice; Undo restores and saves the same layout");
    }

    private void addBack() throws Exception {
        main(() -> require(editorView().findViewWithTag("menu_add_back").performClick(), "Add back did not click"));
        await(() -> sheet() != null && sheet().isShowing(), "Add back sheet did not open");
        settle(); SystemClock.sleep(400); settle();
        main(() -> {
            View content = sheet().getWindow().getDecorView();
            List<View> plus = tagged(content, "menu_show");
            require(plus.size() == 1, "Add back lists " + plus.size() + " items");
            require(String.valueOf(plus.get(0).getContentDescription()).startsWith("Add back "), "Plus says " + plus.get(0).getContentDescription());
        });
        screenshot("add-back-sheet");
        main(() -> require(tagged(sheet().getWindow().getDecorView(), "menu_show").get(0).performClick(), "Plus did not click")); settle();
        await(() -> sheet() == null, "Sheet stayed open with nothing hidden");
        main(() -> {
            require(!Boolean.TRUE.equals(invoke(menu(), "rowHidden", new Class<?>[]{String.class}, ROW)), ROW + " still hidden");
            require(editorView().findViewWithTag(ROW) != null, ROW + " not back in the editor");
        });
        checks.add("Add back lists the hidden row; its plus shows it again and the sheet closes when nothing is hidden");
    }

    private void backFinishes() throws Exception {
        main(() -> activity.onBackPressed()); settle(); SystemClock.sleep(400); settle();
        main(() -> {
            require(screen().getClass().getSimpleName().equals("ControlsScreen"), "Back left the Scooter tab while editing");
            require(editor() == null && !settings().getBoolean("scooter_menu_editing", true), "Back did not finish editing");
            require(((View)field(row(ROW), "card")).getVisibility() == View.VISIBLE, ROW + " not back on the live page");
            require(liveOrder().indexOf(MOVED) < liveOrder().indexOf(ABOVE), "Live order after Back " + liveOrder());
        });
        screenshot("live-final");
        checks.add("Back finishes editing first and stays on the Scooter tab");
    }

    // ---- fixture ----
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
    }
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic menu fixture", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile);
            OnboardingScreen.markDone(getTargetContext());
        });
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch"); settle();
    }
    /** Turns the display a quarter (the system turns it even where large screens ignore an app's orientation request). */
    private int rotate() {
        int[] start = new int[1];
        main(() -> start[0] = activity.getResources().getConfiguration().screenWidthDp);
        require(getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90), "Rotation refused");
        await(() -> activity.getResources().getConfiguration().screenWidthDp != start[0], "Did not rotate");
        settle(); SystemClock.sleep(800); settle();
        return start[0];
    }
    private void rotateBack(int start) {
        require(getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0), "Rotation refused");
        await(() -> activity.getResources().getConfiguration().screenWidthDp == start, "Did not rotate back");
        getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
        settle(); SystemClock.sleep(800); settle();
    }
    private void recreate() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        try {
            main(() -> activity.recreate());
            android.app.Activity replacement = waitForMonitorWithTimeout(monitor, 6000);
            require(replacement instanceof MainActivity, "Activity recreation was not observed");
            activity = (MainActivity)replacement;
            await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "Recreated activity did not start"); settle();
        } finally { removeMonitor(monitor); }
    }

    // ---- helpers ----
    private Object screen() { return field(activity, "screen"); }
    private View editAction() { return (View)field(activity, "menuEditAction"); }
    private Object editor() { Object screen = screen(); return screen.getClass().getSimpleName().equals("ControlsScreen") ? field(screen, "editor") : null; }
    private View editorView() { return (View)field(editor(), "view"); }
    private Object menu() { return field(screen(), "menu"); }
    private Object menu(String method) { return invoke(menu(), method); }
    private String profileId() { return (String)field(screen(), "menuProfile"); }
    @SuppressWarnings("unchecked") private List<String> sectionOrder() { return new ArrayList<>((List<String>)menu("sections")); }
    private AlertDialog sheet() { Object editor = editor(); return editor == null ? null : (AlertDialog)field(editor, "sheet"); }
    private View noticeButton() {
        PopupWindow notice = (PopupWindow)field(editor(), "notice");
        return notice == null || !notice.isShowing() ? null : notice.getContentView().findViewWithTag("menu_notice_action");
    }
    /** The editor's section cards in screen order (each card's tag is its section id). */
    @SuppressWarnings("unchecked") private List<String> editorOrder() {
        ViewGroup list = (ViewGroup)field(editor(), "sections");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) out.add(String.valueOf(list.getChildAt(i).getTag()));
        return out;
    }
    private View header(String section) {
        View card = editorView().findViewWithTag(section);
        require(card != null, "No " + section + " card in the editor");
        return card.findViewWithTag("menu_section_header");
    }
    /** The header's last child: the drag grip. */
    private View gripOf(View header) { ViewGroup group = (ViewGroup)header; return group.getChildAt(group.getChildCount() - 1); }
    /** The live page's shown sections in reading order (the left column before the right on wide screens). */
    private List<String> liveOrder() {
        Object screen = screen();
        Map<?, ?> sections = (Map<?, ?>)field(screen, "sections");
        View topSpeed = (View)field(field(screen, "topSpeed"), "card");
        View rightPane = (View)field(screen, "rightPane");
        ArrayList<long[]> keys = new ArrayList<>(); ArrayList<String> names = new ArrayList<>();
        for (String name : sectionOrder()) {
            View section = name.equals(TOP_SPEED) ? topSpeed : (View)sections.get(name);
            if (section == null || section.getVisibility() != View.VISIBLE || !(section.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup)section.getParent();
            keys.add(new long[]{parent == rightPane ? 1 : 0, parent.indexOfChild(section), names.size()}); names.add(name);
        }
        keys.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        List<String> out = new ArrayList<>();
        for (long[] key : keys) out.add(names.get((int)key[2]));
        return out;
    }
    private Object row(String key) {
        for (Object row : (List<?>)field(screen(), "rows")) if (((com.jjricks.volt.ScooterCommands.Feature)field(row, "feature")).key.equals(key)) return row;
        throw new IllegalStateException("No row " + key);
    }
    private void inject(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(getUiAutomation().injectInputEvent(event, true), "Touch was not injected"); } finally { event.recycle(); }
    }
    private List<View> tagged(View view, String... tags) {
        ArrayList<View> found = new ArrayList<>();
        for (String tag : tags) if (tag.equals(view.getTag())) found.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) found.addAll(tagged(((ViewGroup)view).getChildAt(i), tags));
        return found;
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    /** Runs on the main thread; a failed check there fails the run here instead of crashing the app. */
    private void main(Runnable task) {
        Throwable[] failed = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failed[0] = error; } });
        if (failed[0] instanceof RuntimeException) throw (RuntimeException)failed[0];
        if (failed[0] instanceof Error) throw (Error)failed[0];
        if (failed[0] != null) throw new IllegalStateException(failed[0]);
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < end) { boolean[] ok = new boolean[1]; main(() -> ok[0] = condition.getAsBoolean()); if (ok[0]) break; SystemClock.sleep(40); }
        boolean[] ok = new boolean[1]; main(() -> ok[0] = condition.getAsBoolean()); require(ok[0], message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
    private void screenshot(String label) throws Exception {
        settle();
        File dir = new File(getTargetContext().getExternalFilesDir(null), "compatibility-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        String variant = arguments.getString("variant", "menu-edit").replaceAll("[^a-zA-Z0-9_-]", ""); File file = new File(dir, variant + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath()); TextCensus.write(this, file);
    }
}
